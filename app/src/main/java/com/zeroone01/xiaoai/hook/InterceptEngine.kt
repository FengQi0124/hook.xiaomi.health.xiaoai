package com.zeroone01.xiaoai.hook

import com.zeroone01.xiaoai.core.ChatMessage
import com.zeroone01.xiaoai.core.ChatResult
import com.zeroone01.xiaoai.core.ModelId
import com.zeroone01.xiaoai.core.ModelManager
import com.zeroone01.xiaoai.core.XLog
import com.zeroone01.xiaoai.net.AiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 消息拦截引擎。
 *
 * ## 核心流程（完全对齐 mitmproxy 脚本 xiaoai_hijack.py 的语义）
 *
 * ```
 * 云端 Event ──► [分发方法 Hook]
 *                  │
 *                  ├─ SpeechRecognizer.RecognizeResult
 *                  │    is_final==false ─► 放行（ASR 中间结果，忽略）
 *                  │    is_final==true  ─► 取 origin_text + dialog_id
 *                  │        ├─ 命中"切换模型"  ─► 标记 selection，返回模型列表文本
 *                  │        ├─ 处于 selection    ─► 解析序号 → 切换模型 → 返回确认文本
 *                  │        └─ 普通文本          ─► pendingQueries[dialogId] = text，放行
 *                  │
 *                  └─ Template.Toast / ToastStream
 *                       小爱原生 ─► 放行
 *                       第三方   ─► 取 pendingQueries[dialogId]
 *                                   ├─ 空 ─► 放行（无对应提问，可能是欢迎语/公告）
 *                                   └─ 有 ─► 挂起当前消息 → 异步调用 AI → 修改 payload.text
 *                                            → 唤醒 → 原方法继续（手环显示替换后的文本）
 * ```
 *
 * ## 为什么必须「挂起」
 * 手环等待回答时有自己的超时。如果我们在 AI 返回后才发送响应，就等于把 AI 的网络延迟
 * 直接叠加到用户等待时间上——这正是 mitmproxy 方案的体验。为了让体验可控：
 *  - [AiConfig.timeoutMs] 是硬上限，默认 8s；
 *  - 超时/异常时**必定放行原始回答**（如果 [AiConfig.fallbackToOriginal] 开启），
 *    保证手环不会一直转圈。
 *
 * ## 线程模型
 * Toast 的处理发生在宿主 App 的消息线程（多为 HandlerThread 或 native 回调线程），
 * 阻塞它是**安全**的（不是主线程），而且这正是 mitmproxy「暂停转发」的等价实现。
 * 但当检测到当前线程就是主线程时，会拒绝阻塞（防止 ANR），直接放行。
 */
internal class InterceptEngine {

    companion object {
        private const val NS_SPEECH = "SpeechRecognizer"
        private const val NS_TEMPLATE = "Template"
        private const val NS_APPLICATION = "Application"

        /** 主线程判定阈值：单次等待最多 10s */
        private const val MAX_BLOCK_MS = 10_000L

        private val pendingSeq = AtomicLong(0)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** dialog_id -> 等待 AI 结果的信号，key 为 "dialogId#seq" */
    private val waiting = ConcurrentHashMap<String, PendingReply>()

    private class PendingReply(
        val latch: CountDownLatch,
        @Volatile var text: String? = null,
        @Volatile var note: String = "",
    )

    // ==================================================================
    // 入口 1：识别结果
    // ==================================================================

    /**
     * 处理 RecognizeResult。
     *
     * @return 需要「覆盖回答文本」时返回非 null 文本，否则返回 null 表示放行
     */
    fun onRecognizeResult(message: Any?): String? {
        val model = AivsModel.get()
        val payload = model.payloadOf(message) ?: return null.also { XLog.d("RecognizeResult 无 payload") }

        // 中间结果直接忽略（asr.enable_partial_result = true，会有大量 is_final=false）
        val isFinal = Reflector.getBoolean(payload, "is_final")
        if (!isFinal) {
            XLog.d("ASR 中间结果，忽略")
            return null
        }

        val text = extractRecognizeText(payload)
        if (text.isNullOrBlank()) {
            XLog.d("RecognizeResult 未取到 origin_text，忽略")
            return null
        }

        val dialogId = model.dialogIdOf(message).orEmpty()
        return routeRecognizeText(dialogId, text)
    }

    /**
     * JSON 层的 RecognizeResult 入口（org.json / Gson 文本路径，见 XiaoAiHookEntry.handleWireJson）。
     *
     * 与 [onRecognizeResult] 等价，但输入已经是解析好的纯文本，
     * 不需要再从 Message 对象反射提取。
     *
     * @return 需要「覆盖回答文本」时返回非 null 文本，否则返回 null 表示放行
     */
    fun onRecognizeJson(dialogId: String, text: String): String? =
        routeRecognizeText(dialogId, text)

    /**
     * 识别文本的统一路由（Message 路径与 JSON 路径共用）。
     */
    private fun routeRecognizeText(dialogId: String, text: String): String? {
        XLog.i("识别到用户语音 [dialog=$dialogId]: $text")

        ModelManager.purgeStalePending()

        return when (val action = VoiceCommandHandler.onRecognize(text, dialogId)) {
            is VoiceCommandHandler.OnRecognize.Normal -> {
                // 普通提问：记下来，等 Toast 到来时替换
                ModelManager.pendingQueries[dialogId] = ModelManager.PendingQuery(
                    text = action.text,
                    timestamp = System.currentTimeMillis(),
                    modelKey = ModelManager.activeModel.value.key,
                )
                XLog.i("已记录待处理提问 [dialog=$dialogId]: ${action.text}")
                null
            }

            is VoiceCommandHandler.OnRecognize.EnterSelection -> {
                XLog.i("拦截：进入模型选择模式")
                action.menuText
            }

            is VoiceCommandHandler.OnRecognize.Switched -> {
                XLog.i("拦截：模型切换 -> ${action.providerName}")
                action.replyText
            }

            is VoiceCommandHandler.OnRecognize.InvalidChoice -> {
                XLog.i("拦截：选项无效")
                action.replyText
            }

            VoiceCommandHandler.OnRecognize.Pass -> null
        }
    }

    /**
     * 提取识别文本。
     *
     * 优先级：results[0].origin_text > results[0].text > payload.text
     * 与脚本一致（脚本用 origin_text，即未经 ITN 的原始识别文本）。
     */
    private fun extractRecognizeText(payload: Any?): String? {
        val results = Reflector.get<Any>(payload, null, "results")
        val list = asList(results)
        if (list.isNotEmpty()) {
            val first = list[0]
            Reflector.getString(first, "origin_text")?.takeIf { it.isNotBlank() }?.let { return it }
            Reflector.getString(first, "text")?.takeIf { it.isNotBlank() }?.let { return it }
            // 部分版本字段名可能是 query_before_itn
            Reflector.getString(first, "query_before_itn")?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return Reflector.getString(payload, "text")
    }

    // ==================================================================
    // pending query 查找（py 的 pending_queries[dialog_id] 等价实现）
    // ==================================================================

    /**
     * 按 dialog_id 精确查找待处理提问；查不到时回退到**最近一条**（60s 内）。
     *
     * ★ v0.1.0-beta7：getter 保底 Hook（见 XiaoAiHookEntry.hookPayloadGetters）拿不到
     * header，因此没有 dialog_id —— 手环同一时间只有一轮语音对话，用"最近一条提问"
     * 语义与 py 的 pending_queries 完全等价。
     *
     * @return 已从队列移除的 pending；没有匹配返回 null
     */
    private fun pendingQueriesLookup(dialogId: String): ModelManager.PendingQuery? {
        // 1) 精确匹配（py 语义）
        if (dialogId.isNotBlank()) {
            ModelManager.pendingQueries.remove(dialogId)?.let { return it }
        }
        // 2) fallback：最近一条未过期的
        val entries = ModelManager.pendingQueries.entries.sortedByDescending { it.value.timestamp }
        val now = System.currentTimeMillis()
        for ((_, v) in entries) {
            if (now - v.timestamp <= 60_000L) {
                // 从任意 key 移除（命中即消费）
                ModelManager.pendingQueries.entries.removeIf { it.value === v }
                XLog.d("dialog_id 不匹配，使用最近一条提问 fallback: ${v.text}")
                return v
            }
        }
        return null
    }

    // ==================================================================
    // 入口 2：回答消息（统一处理 Template.* / Application.GenerateSpeak）
    // ==================================================================

    /**
     * 处理 Cloud→App 的回答消息。
     *
     * 兼容 3.57.0 与 3.59.1：
     *  - 3.59.1：`Template.Toast` / `Template.ToastV2` / `Template.ToastStream` /
     *           `Template.StyleToastStreamStart`，文本字段 `text` / `markdown_text`
     *  - 3.57.0：`Application.GenerateSpeak`，文本字段 `text`
     *
     * 调用方（Hook 层）必须先用 [AivsModel.isAnswerMessage] 过滤一次，确保不会误拦
     * App→Cloud 方向的同名消息（3.57.0 上 `Template.Toast` 就是 InstructionPayload）。
     *
     * @param rewrite 由 Hook 层提供：把文本写回消息对象的回调
     * @return true 表示已经（或即将）改写文本并把结果写回了对象；false 表示放行原始流程
     */
    fun onAnswerMessage(message: Any?, rewrite: (String) -> Boolean): Boolean {
        val model = AivsModel.get()
        val payload = model.payloadOf(message) ?: return false
        val dialogId = model.dialogIdOf(message).orEmpty()
        val fieldName = payloadTextField(message, payload)
        return onAnswerPayload(dialogId, payload, fieldName, rewrite)
    }

    /**
     * payload-only 入口：给 **getter 保底 Hook** 用（见 XiaoAiHookEntry.hookPayloadGetters）。
     *
     * getter hook 挂在 `Template$Toast#getText()` 这类 payload 方法上，`this` 就是
     * payload 本身，拿不到外层 Message / header / dialog_id —— dialogId 传空串，
     * 由 [pendingQueriesLookup] 的「最近一条提问」fallback 兜住。
     */
    fun onAnswerPayloadOnly(payload: Any?, rewrite: (String) -> Boolean): Boolean {
        val fieldName = payloadTextField(null, payload)
        return onAnswerPayload("", payload, fieldName, rewrite)
    }

    /**
     * JSON 层的回答消息入口（org.json / Gson 文本路径）。
     *
     * payload 传 null：onAnswerPayload 内部只在「读原文做日志」时用 payload，
     * 空安全（Reflector.getString(null, ...) 返回 null）。
     *
     * @param rewrite 由 Hook 层提供：把新文本写回 JSONObject 的回调
     */
    fun onAnswerJson(dialogId: String, rewrite: (String) -> Boolean): Boolean =
        onAnswerPayload(dialogId, null, "text", rewrite)

    /**
     * 回答替换的核心实现（[onAnswerMessage] / [onAnswerPayloadOnly] 共用）。
     */
    private fun onAnswerPayload(
        dialogId: String,
        payload: Any?,
        fieldName: String,
        rewrite: (String) -> Boolean,
    ): Boolean {
        val cfg = ModelManager.config()
        val active = cfg.activeProvider()
        val activeDisplay = active.displayName.ifBlank { active.providerType.displayName }

        // 情况 A：手环正在选择模式，或刚才的语音指令产生了「待覆盖文本」，
        //        这些场景在 onRecognizeResult 里已经算好文本但无法直接写回 RecognizeResult，
        //        因此在这里统一落地。
        val direct = pendingDirectText
        if (direct != null) {
            pendingDirectText = null // 读取即消费
            XLog.i("使用指令应答文本覆盖 Toast: ${direct.take(50)}")
            if (rewrite(direct)) {
                recordDialog(dialogId, "", direct, "语音指令", true, "语音指令")
                return true
            }
            return false
        }

        // 情况 B：常规 AI 替换
        if (active.isXiaoAi) {
            XLog.d("当前为小爱同学模式，不劫持 Toast")
            return false
        }

        val pending = pendingQueriesLookup(dialogId)
        if (pending == null || pending.text.isBlank()) {
            XLog.i("Toast [dialog=$dialogId] 无对应提问文本，放行原始回答")
            return false
        }

        val request = cfg.toChatRequest(buildHistory()) ?: run {
            XLog.w("$activeDisplay 配置不完整（缺少 BaseUrl/APIKey/模型名），放行原始回答")
            return false
        }

        // 主线程保护：绝不能在主线程阻塞等待
        if (isMainThread()) {
            XLog.w("检测到主线程调用，跳过替换以保证不 ANR")
            return false
        }

        // ---- 挂起当前消息，异步请求 AI ----
        val waitKey = "$dialogId#${pendingSeq.incrementAndGet()}"
        val pendingReply = PendingReply(CountDownLatch(1))
        waiting[waitKey] = pendingReply

        val originalText = Reflector.getString(payload, fieldName).orEmpty()
        XLog.i("开始替换 [dialog=$dialogId] 模型=$activeDisplay 提问=${pending.text}")

        scope.launch {
            try {
                val history = if (cfg.enableHistory) buildHistory() else emptyList()
                val req = cfg.toChatRequest(history)
                val result = if (req == null) {
                    ChatResult.Failure("配置不完整", fatal = true)
                } else {
                    AiClient.chat(req, pending.text, stream = false)
                }
                when (result) {
                    is ChatResult.Success -> {
                        pendingReply.text = result.text
                        pendingReply.note = "AI(${result.elapsedMs}ms)"
                    }
                    is ChatResult.Failure -> {
                        pendingReply.text = null
                        pendingReply.note = "失败: ${result.reason}"
                        XLog.w("AI 调用失败，将放行原始回答: ${result.reason}")
                    }
                }
            } catch (t: Throwable) {
                pendingReply.text = null
                pendingReply.note = "异常: ${t.message}"
                XLog.e("AI 调用异常", t)
            } finally {
                waiting.remove(waitKey)
                pendingReply.latch.countDown()
            }
        }

        // 等待结果，硬超时 = 配置超时 + 500ms 余量，且不超过 MAX_BLOCK_MS
        val waitMs = (request.timeoutMs + 500).coerceAtMost(MAX_BLOCK_MS)
        val finished = runCatching {
            pendingReply.latch.await(waitMs, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)

        val reply = if (finished) pendingReply.text else null

        if (reply.isNullOrBlank()) {
            val reason = if (finished) pendingReply.note else "等待超时(${waitMs}ms)"
            XLog.w("未取得 AI 回答（$reason），放行原始回答")
            recordDialog(dialogId, pending.text, originalText, activeDisplay, false, reason)
            return false
        }

        // ---- 写回文本 ----
        val ok = rewrite(reply)
        if (ok) {
            XLog.i("替换成功 [dialog=$dialogId]: ${reply.take(60)}")
            recordDialog(dialogId, pending.text, reply, activeDisplay, true, pendingReply.note)
            rememberHistory(pending.text, reply)
        } else {
            XLog.w("写回 payload.$fieldName 失败，放行原始回答")
        }
        return ok
    }

    /**
     * 回答消息的文本字段名。
     *
     * 规则：
     *  - `Template.Toast` / `Template.ToastV2` / `Application.GenerateSpeak`  → `text`
     *  - `Template.ToastStream` / `Template.StyleToastStreamStart`           → 优先
     *    `markdown_text`，没有再退回 `text`（部分版本字段名只有 text）。
     *
     * `name` 参数可能为 null（header 解析失败），这时默认用 `text` —— 把原 payload
     * 上「看起来像文本」的字段拿出来。后续 setter 仍会按字段名优先匹配。
     */
    private fun payloadTextField(message: Any?, payload: Any?): String {
        val name = AivsModel.get().nameOf(message)
        val hasMarkdown = Reflector.fieldOf(payload?.javaClass, "markdown_text") != null
        val isStream = name == AivsModel.NAME_TOAST_STREAM ||
            name == AivsModel.NAME_STYLE_TOAST_STREAM_START ||
            hasMarkdown
        return if (isStream && hasMarkdown) "markdown_text" else "text"
    }

    // ==================================================================
    // 指令应答的临时落地（RecognizeResult 与 Toast 之间的桥）
    // ==================================================================

    /**
     * ★ v0.1.0-beta7：从 ThreadLocal 改为全局字段。
     * 原因：RecognizeResult（ASR 事件分发线程）与 Template.Toast（UI/TTS 线程）
     * **不在同一个线程**，ThreadLocal 暂存会让「切换模型」的菜单文本永远丢包——
     * 这正是用户反馈"手环说切换模型没有显示菜单"的另一个根因。
     * 语音指令场景同一时刻只有一条待落地应答，全局单槽足够；读取即消费。
     */
    @Volatile
    private var pendingDirectText: String? = null

    /** 由 Hook 层在 RecognizeResult 被拦截后调用，暂存应答文本 */
    fun stashDirectText(text: String?) {
        pendingDirectText = text
    }

    fun clearDirectText() {
        pendingDirectText = null
    }

    // ==================================================================
    // 上下文 / 记录

    private val history = ArrayDeque<ChatMessage>()

    @Synchronized
    private fun buildHistory(): List<ChatMessage> {
        val cfg = ModelManager.config()
        if (!cfg.enableHistory) return emptyList()
        val max = (cfg.historyRounds * 2).coerceIn(0, 20)
        return if (history.size <= max) history.toList() else history.toList().takeLast(max)
    }

    @Synchronized
    private fun rememberHistory(question: String, answer: String) {
        if (!ModelManager.config().enableHistory) return
        history.addLast(ChatMessage("user", question))
        history.addLast(ChatMessage("assistant", answer))
        while (history.size > 20) history.removeFirst()
    }

    @Synchronized
    fun clearHistory() = history.clear()

    private fun recordDialog(
        dialogId: String,
        question: String,
        answer: String,
        model: String,
        replaced: Boolean,
        note: String,
    ) {
        ModelManager.recordDialog(
            ModelManager.DialogRecord(
                time = System.currentTimeMillis(),
                dialogId = dialogId,
                question = question,
                answer = answer,
                model = model,
                replaced = replaced,
                note = note,
            )
        )
    }

    // ==================================================================
    // 工具

    private fun isMainThread(): Boolean =
        runCatching { android.os.Looper.myLooper() == android.os.Looper.getMainLooper() }
            .getOrDefault(false)

    @Suppress("UNCHECKED_CAST")
    private fun asList(value: Any?): List<Any> = when (value) {
        is List<*> -> value.filterNotNull()
        is Array<*> -> value.filterNotNull()
        is java.util.Optional<*> -> if (value.isPresent) asList(value.get()) else emptyList()
        else -> emptyList()
    }

    /** 供健康检查：目前有多少个挂起的替换任务 */
    fun pendingCount(): Int = waiting.size
}
