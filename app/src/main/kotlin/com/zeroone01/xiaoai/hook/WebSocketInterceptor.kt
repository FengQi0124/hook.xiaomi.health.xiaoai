@file:Suppress("unused")

package com.zeroone01.xiaoai.hook

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.zeroone01.xiaoai.config.ConfigKeys
import com.zeroone01.xiaoai.config.ConfigStore
import com.zeroone01.xiaoai.log.LogCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 手环小爱AI —— WebSocket 消息拦截层
 *
 * 与具体的 Hook 点解耦：
 * - [WsMessage] 描述一条已完成解码/解密的原始 JSON 消息；
 * - [WebSocketInterceptor] 是可插拔的拦截器接口，不同 Hook 点（方案 A 底层
 *   oav.onMessage / 方案 B Instruction 层 / 方案 C APIUtils.readInstruction）
 *   各自实现该接口，统一产出 [WsMessage] 交给 [WebSocketMessageProcessor] 处理；
 * - [WebSocketMessageProcessor] 承载核心业务逻辑：识别 RecognizeResult（记录
 *   用户语音文本）与 Toast（触发 LLM 替换回答），与具体 Hook 点彻底解耦。
 */

/** 一条待处理的 WebSocket 消息（已完成解码/解密的原始 JSON 字符串） */
data class WsMessage(
    val rawJson: String,          // 原始 JSON 字符串
    val dialogId: String?,        // header.dialog_id
    val namespace: String?,       // header.namespace
    val name: String?,            // header.name
) {
    companion object {
        /** 伴生对象自持 Json 实例（默认配置，兼容未知字段） */
        private val json = Json { ignoreUnknownKeys = true }

        /** 解析原始 JSON 的 header 生成 WsMessage；解析失败返回空字段实例（不影响透传） */
        fun parse(rawJson: String): WsMessage {
            return try {
                val root = json.parseToJsonElement(rawJson).jsonObject
                val header = root["header"]?.jsonObject
                WsMessage(
                    rawJson = rawJson,
                    dialogId = header?.get("dialog_id")?.jsonPrimitive?.contentOrNull,
                    namespace = header?.get("namespace")?.jsonPrimitive?.contentOrNull,
                    name = header?.get("name")?.jsonPrimitive?.contentOrNull,
                )
            } catch (_: Throwable) {
                WsMessage(rawJson, null, null, null)
            }
        }
    }
}

/** WebSocket 拦截器接口：不同 Hook 点提供不同实现，产出 WsMessage 交由 processor 处理 */
interface WebSocketInterceptor {
    fun onMessage(ws: WsMessage)
}

/**
 * 消息处理器：识别 RecognizeResult（记录识别文本）与 Toast（调用 LLM 替换），其他透传。
 *
 * 处理策略：
 * - RecognizeResult：把「dialogId -> 用户识别文本」存入内存缓存 [pendingQueries]；
 *   同时启动后台任务把识别文本交给 LLM 预取回答（预取结果经 [registerReplacementCallback]
 *   回传，供具体 Hook 实现按需注入）。消息本身始终放行（设备侧正常显示识别过程）。
 * - Toast：通常此时 [pendingQueries] 已存在对应 dialogId 的识别文本。处理器启动后台任务
 *   调用 [LlmClient.ask] 生成替换文本，完成后通过替换回调回传；Toast 原消息先放行作为兜底
 *   （具体替换注入由 Hook 实现决定，如 MiHealthHook 在进入 App 前改写 JSON 的 payload.text）。
 * - 其他消息：放行。
 */
class WebSocketMessageProcessor(private val config: ConfigStore) {

    private val tag = "WsProcessor"

    /** 全局 Json 实例：解析失败不抛出，兼容未知字段 */
    private val json = Json { ignoreUnknownKeys = true }

    /** 识别文本缓存：dialogId -> 用户语音识别文本（最终结果） */
    private val pendingQueries = ConcurrentHashMap<String, String>()

    /**
     * 指令命中缓存的对话：dialogId -> 该对话 Toast 应替换的**回复文案**。
     * 文案在识别到指令时即生成（含模式切换确认 / 模式查询 / 模型菜单 / 序号选择结果），
     * 供 MiHealthHook 消费一次后替换 payload.text。
     */
    private val commandDialogIds = ConcurrentHashMap<String, String>()

    /**
     * 「语音选择模型」菜单的到期时间戳（毫秒）：0 = 未处于选择模式。
     * 说出进入菜单指令后开启，[ConfigKeys.MODEL_MENU_TIMEOUT_MS] 超时自动退出。
     */
    @Volatile
    private var modelMenuDeadlineMs = 0L

    /** 替换回调集合：LLM 回答生成后回调 (dialogId, 替换文本) */
    private val replacementCallbacks = java.util.concurrent.CopyOnWriteArrayList<(String, String) -> Unit>()

    /** 后台单线程池：串行执行 LLM 请求，避免并发改写会话历史导致乱序 */
    private val llmExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "RingOnLLM-WsProcessor").apply { isDaemon = true }
    }

    companion object {
        /* 识别文本缓存大小上限：超出后清理最旧条目，防止内存膨胀 */
        private const val MAX_PENDING_QUERIES = 100

        /** 指令命中缓存大小上限：超出后清理最旧条目，防止残留膨胀 */
        private const val MAX_COMMAND_IDS = 50
        private const val TAG = "WsProcessor"

        // 消息命名空间/名称常量（与小米 AI 协议一致）
        private const val NS_SPEECH_RECOGNIZER = "SpeechRecognizer"
        private const val NAME_RECOGNIZE_RESULT = "RecognizeResult"
        private const val NS_TEMPLATE = "Template"
        private const val NAME_TOAST = "Toast"
        private const val NAME_GENERAL = "General"
    }

    // ==================== 替换回调 ====================

    /**
     * 注册替换回调：LLM 为 Toast 生成替换文本后触发 (dialogId, newText)。
     * 由具体 Hook 实现决定如何注入（如改写 oav.onMessage 的 str 参数）。
     */
    fun registerReplacementCallback(cb: (dialogId: String, newText: String) -> Unit) {
        replacementCallbacks.add(cb)
    }

    /**
     * 注销替换回调：供一次性回调（阻塞等待 LLM 结果的场景）用完即删，避免泄漏。
     */
    fun unregisterReplacementCallback(cb: (dialogId: String, newText: String) -> Unit) {
        replacementCallbacks.remove(cb)
    }

    // ==================== 核心处理 ====================

    /**
     * 处理一条 WebSocket 消息。
     *
     * @return true 表示应放行（透传/替换后放行），false 表示应丢弃
     */
    fun process(msg: WsMessage): Boolean {
        // 解析失败等容错场景一律放行，绝不影响宿主消息流转
        val root = parseRoot(msg.rawJson) ?: return true

        return try {
            when {
                // —— 语音识别结果：记录用户语音文本 ——
                msg.namespace == NS_SPEECH_RECOGNIZER && msg.name == NAME_RECOGNIZE_RESULT -> {
                    handleRecognizeResult(msg, root)
                    true // 放行原消息，设备侧正常显示识别过程
                }
                // —— 小爱回答 Toast：触发 LLM 替换 ——
                msg.namespace == NS_TEMPLATE && msg.name == NAME_TOAST -> {
                    handleToast(msg, root)
                    true // 始终放行（先放行原始 Toast，LLM 结果经回调注入）
                }
                // —— Template.General（米家/设备类文本）：仅在开关开启时触发替换，默认放行 ——
                msg.namespace == NS_TEMPLATE && msg.name == NAME_GENERAL -> {
                    if (config.getInterceptGeneral()) {
                        handleToast(msg, root)
                    } else {
                        LogCollector.i(tag, "未开启拦截 General，General 透传 dialogId=${msg.dialogId}")
                    }
                    true
                }
                // —— 其他消息：透传 ——
                else -> true
            }
        } catch (t: Throwable) {
            // 任何解析/处理异常都不外抛，保证不干扰宿主消息流转
            LogCollector.w(tag, "处理消息异常，放行原消息: ${t.message}")
            true
        }
    }

    /** 读取某 dialogId 的识别文本（供 Hook 实现同步获取用户问题） */
    fun getPendingQuery(dialogId: String?): String? =
        dialogId?.let { pendingQueries[it] }

    /** 消费某 dialogId 的指令回复文案（用于该对话 Toast 替换为确认/查询/菜单文案）。
     * 使用 remove 语义：方案 A 与方案 C 双命中同一条 Toast 时只消费一次。
     */
    fun consumeReply(dialogId: String?): String? =
        dialogId?.let { commandDialogIds.remove(it) }

    // ==================== 内部实现 ====================

    /** 解析 JSON 根对象；失败返回 null（上层据此放行） */
    private fun parseRoot(rawJson: String): JsonObject? = try {
        json.parseToJsonElement(rawJson).jsonObject
    } catch (t: Throwable) {
        LogCollector.w(tag, "解析 WebSocket 消息失败，放行原消息: ${t.message}")
        null
    }

    /** 安全取对象：非 JsonObject 时返回 null（避免 jsonObject 强转抛异常） */
    private fun asJsonObject(element: JsonElement?): JsonObject? =
        if (element is JsonObject) element else null

    /** 安全取字符串字段：非 JsonPrimitive 时返回 null */
    private fun asText(element: JsonElement?): String? =
        if (element is JsonPrimitive) element.contentOrNull else null

    /** 处理 RecognizeResult：直通/菜单/指令优先，未命中再读取识别文本写入缓存 */
    private fun handleRecognizeResult(msg: WsMessage, root: JsonObject) {
        val dialogId = msg.dialogId ?: return
        val payload = asJsonObject(root["payload"]) ?: return
        // 仅记录最终识别结果（is_final==true），中间结果跳过
        val isFinal = asText(payload["is_final"])?.toBooleanStrictOrNull() ?: false
        if (!isFinal) return

        val text = extractResultsText(root) ?: return
        if (text.isBlank()) return

        // —— 双 Hook 点防重：本 dialog 已产生过回复文案时直接跳过（避免菜单被重复解析成序号）——
        if (commandDialogIds.containsKey(dialogId)) return

        // —— 智能家居直通（最高优先级）：不暂存识别文本，Toast/General 原样放行 ——
        if (config.getSmartHomePass() && SmartHomeRules.isSmartHomeCommand(text)) {
            SmartHomeRules.logPass(text)
            return
        }

        // —— 模型选择菜单开启中：解析序号/名称/退出，回复固定文案 ——
        if (isModelMenuOpen()) {
            val reply = handleModelMenuChoice(text)
            commandDialogIds[dialogId] = reply
            trimCommandIds()
            LogCollector.i(tag, "模型菜单选择 dialogId=$dialogId text=${text.take(60)} → ${reply.take(40)}")
            return
        }

        // —— 「切换模型」指令词：开启 30s 模型选择菜单并以 Toast 回显菜单，不写入识别文本 ——
        if (config.getCmdSwitchModel().any { text.contains(it, ignoreCase = true) }) {
            openModelMenu()
            commandDialogIds[dialogId] = buildModelMenuText()
            trimCommandIds()
            LogCollector.i(tag, "进入模型选择菜单 dialogId=$dialogId text=${text.take(60)}")
            return
        }

        pendingQueries[dialogId] = text
        LogCollector.i(tag, "记录识别文本 dialogId=$dialogId text=${text.take(60)}")
        trimPendingQueries()
    }

    // ==================== 语音选择模型菜单 ====================

    /** 菜单是否开启（未到期）；到期时顺带复位状态 */
    private fun isModelMenuOpen(): Boolean {
        val deadline = modelMenuDeadlineMs
        if (deadline == 0L) return false
        if (System.currentTimeMillis() > deadline) {
            modelMenuDeadlineMs = 0L
            LogCollector.i(tag, "模型选择菜单已超时，自动退出")
            return false
        }
        return true
    }

    /** 开启菜单：刷新 30s 有效期 */
    private fun openModelMenu() {
        modelMenuDeadlineMs = System.currentTimeMillis() + ConfigKeys.MODEL_MENU_TIMEOUT_MS
    }

    /** 关闭菜单 */
    private fun closeModelMenu() {
        modelMenuDeadlineMs = 0L
    }

    /**
     * 生成模型选择菜单文案（替换该 dialog 的 Toast 显示）。
     * 第 1 项固定「小爱同学」，其后为用户模型列表（与设置页列表顺序一致）。
     */
    private fun buildModelMenuText(): String {
        val sb = StringBuilder("选择模型：\n1. 小爱同学")
        config.getModelList().forEachIndexed { i, e ->
            sb.append("\n${i + 2}. ${e.name.ifBlank { e.model }}")
        }
        sb.append("\n（30秒内说序号或名称，说退出取消）")
        return sb.toString()
    }

    /**
     * 解析菜单态下的用户语音并执行选择。
     *
     * @return 替换 Toast 的回复文案（始终非空：成功确认 / 退出 / 未听懂重发菜单）
     */
    private fun handleModelMenuChoice(text: String): String {
        val list = config.getModelList()
        val menuSize = 1 + list.size
        val choice = parseChoice(text, menuSize)

        return when {
            // 听不懂 → 保持菜单开启，重发一次菜单
            choice == null -> buildModelMenuText()
            // 退出/取消（menuSize+1）
            choice > menuSize -> {
                closeModelMenu()
                "已退出模型选择"
            }
            // 第 1 项：小爱同学（原生接管，走既有模式切换语义）
            choice == 1 -> {
                closeModelMenu()
                ModeState.switchTo(AnswerMode.XIAOAI)
                ModeState.buildConfirmation(AnswerMode.XIAOAI)
            }
            // 第 N 项：切换到对应模型（运行时覆盖 + 切到 LLM 模式）
            else -> {
                closeModelMenu()
                val entry = list.getOrNull(choice - 2)
                if (entry == null) {
                    buildModelMenuText()
                } else {
                    config.setRuntimeModelId(entry.id)
                    ModeState.switchTo(AnswerMode.LLM)
                    "已切换到 ${entry.name.ifBlank { entry.model }}"
                }
            }
        }
    }

    /**
     * 解析用户说的序号（移植旧版 VoiceCommandHandler.parseChoice）。
     *
     * @param menuSize 菜单总项数（小爱 + 所有模型）
     * @return 1..menuSize 表示选某项；menuSize+1 表示「退出」；null 表示听不懂
     */
    private fun parseChoice(raw: String, menuSize: Int): Int? {
        val t = raw.trim()
            .replace("。", "").replace("，", "").replace(",", "")
            .replace(".", "").replace("、", "").replace("号", "")
            .replace("第", "").replace("个", "")
            .replace("选项", "").replace("选择", "")

        val exitChoice = menuSize + 1

        // 纯阿拉伯数字
        t.toIntOrNull()?.let { if (it in 1..exitChoice) return it }

        // 中文数字（只支持 1..9；菜单项多就靠 ASR 纯数字）
        val chinese = mapOf(
            "一" to 1, "二" to 2, "两" to 2, "三" to 3, "四" to 4, "五" to 5,
            "六" to 6, "七" to 7, "八" to 8, "九" to 9,
            "壹" to 1, "贰" to 2, "叁" to 3, "肆" to 4, "伍" to 5,
            "陆" to 6, "柒" to 7, "捌" to 8, "玖" to 9,
        )
        chinese[t]?.let { if (it in 1..exitChoice) return it }

        // 关键词兜底：退出
        val lower = t.lowercase()
        if (lower.contains("退出") || lower.contains("取消") || lower.contains("算了")) return exitChoice

        // 「小爱」直接选原生（永远在第 1 项）
        if (lower.contains("小爱") || lower.contains("原生")) return 1

        // 按模型名称/模型 id 兜底匹配（与设置页填写一致）
        config.getModelList().forEachIndexed { i, e ->
            if (e.name.isNotBlank() && lower.contains(e.name.lowercase())) return i + 2
            if (e.model.isNotBlank() && lower.contains(e.model.lowercase())) return i + 2
        }

        // 「选择2」「选3」这类（数字已在上面toIntOrNull覆盖不到时的残留）
        Regex("""\d+""").find(lower)?.value?.toIntOrNull()
            ?.let { if (it in 1..exitChoice) return it }
        return null
    }

    /** 指令命中缓存裁剪：超过上限时移除最旧条目 */
    private fun trimCommandIds() {
        if (commandDialogIds.size <= MAX_COMMAND_IDS) return
        val it = commandDialogIds.entries.iterator()
        var removed = 0
        while (it.hasNext() && commandDialogIds.size - removed > MAX_COMMAND_IDS) {
            it.next()
            it.remove()
            removed++
        }
    }

    /**
     * 从 payload.results[0] 提取识别文本：
     * 优先 origin_text，其次 getText，再兜底 text。
     */
    private fun extractResultsText(root: JsonObject): String? {
        val payload = asJsonObject(root["payload"]) ?: return null
        val results = payload["results"] as? JsonArray ?: return null
        val first = results.firstOrNull()?.let { asJsonObject(it) } ?: return null
        for (key in listOf("origin_text", "getText", "text")) {
            val v = asText(first[key])
            if (!v.isNullOrBlank()) return v
        }
        return null
    }

    /** 处理 Toast：启动后台 LLM 任务，结果经替换回调回传 */
    private fun handleToast(msg: WsMessage, root: JsonObject) {
        val dialogId = msg.dialogId ?: return
        // 未启用 / 未配置 Key / 无对应识别文本 —— 无需替换，直接放行
        if (!config.isEnabled()) {
            LogCollector.i(tag, "模块未启用，Toast 透传 dialogId=$dialogId")
            return
        }
        // 用手端小爱回答时无需自配 API Key；仅 DeepSeek 模式要求 Key
        if (!config.getUsePhoneXiaoai() && config.getApiKey().isBlank()) {
            LogCollector.w(tag, "API Key 为空，Toast 透传 dialogId=$dialogId")
            return
        }
        val queryText = pendingQueries[dialogId]
        if (queryText.isNullOrBlank()) {
            LogCollector.w(tag, "无对应识别文本，Toast 透传 dialogId=$dialogId")
            return
        }

        // 后台单线程执行 LLM 请求（LlmClient.ask 为同步网络调用，勿在主线程执行）
        llmExecutor.execute {
            try {
                LogCollector.i(tag, "调用 LLM 替换回答 dialogId=$dialogId query=${queryText.take(60)}")
                // 回答来源：手端小爱(miclaw/fast) 或 自配 LLM(DeepSeek)
                val answer = if (config.getUsePhoneXiaoai()) {
                    val timeout = config.getTimeoutMs().toInt().coerceIn(3_000, 20_000)
                    val engine = config.getXiaoaiEngine()
                    var a = XiaoaiAgentClient.ask(queryText, engine, timeout)
                    // miclaw 失败自动回退 fast（fast 仍失败则 a=null，放行原始 Toast）
                    if (a == null && engine != "fast") {
                        LogCollector.i(tag, "miclaw 无回答，回退 fast dialogId=$dialogId")
                        a = XiaoaiAgentClient.ask(queryText, "fast", timeout)
                    }
                    a
                } else {
                    LlmClient.ask(dialogId, queryText)
                }
                if (!answer.isNullOrBlank()) {
                    for (cb in replacementCallbacks) {
                        try {
                            cb(dialogId, answer)
                        } catch (t: Throwable) {
                            LogCollector.e(tag, "替换回调执行失败", t)
                        }
                    }
                } else {
                    LogCollector.w(tag, "LLM 未返回回答，保留原始 Toast dialogId=$dialogId")
                }
            } catch (t: Throwable) {
                LogCollector.e(tag, "LLM 调用异常，保留原始 Toast", t)
            }
        }
    }

    /** 缓存裁剪：超过上限时移除最旧条目（ConcurrentHashMap 无序，取任意条目删除到上限内） */
    private fun trimPendingQueries() {
        if (pendingQueries.size <= MAX_PENDING_QUERIES) return
        val it = pendingQueries.entries.iterator()
        var removed = 0
        while (it.hasNext() && pendingQueries.size - removed > MAX_PENDING_QUERIES) {
            it.next()
            it.remove()
            removed++
        }
        LogCollector.w(tag, "识别文本缓存超过上限，已裁剪 $removed 条")
    }
}
