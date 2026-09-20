package com.zeroone01.xiaoai.hook

import com.zeroone01.xiaoai.core.AiConfig
import com.zeroone01.xiaoai.core.ModelManager
import com.zeroone01.xiaoai.core.ProviderConfig
import com.zeroone01.xiaoai.core.XLog

/**
 * 手环语音指令处理器。
 *
 * ## 交互设计
 * 用户对手环说「切换模型」→ 模块拦截，**不调用任何 AI**，直接把手环上的回答
 * 换成模型列表：
 * ```
 * 请选择模型
 * 1.小爱同学
 * 2.<用户起的名字>
 * 3.<用户起的名字>
 * ...
 * N.退出
 * ```
 * 用户接着说数字（1..N）→ 模块切换模型并回复「已切换到 XXX」。
 *
 * ## 关键点
 *  - 选择模式是**有状态的**，且必须带 dialog_id 维度：小爱同学的连续对话会复用/切换
 *    dialog_id，用「全局一个布尔量」在多轮对话下会串味。
 *  - 状态有时效（默认 30s），避免用户说完「切换模型」后去干别的事，回来一句
 *    「1」被当成模型选择。
 *  - 数字识别要宽松：ASR 可能返回「1」「一」「第一个」「1.」甚至「1号」。
 *  - **可选项**：用户说「退出」/`N+1` 时直接退出选择模式，不切换。
 *
 * ## v0.1.0-beta5 起的菜单顺序
 *  菜单项直接来自 [com.zeroone01.xiaoai.core.AiConfig.providers]（保持 UI 显示顺序）：
 *   1) XIAOAI 行（key="xiaoai"）
 *   2..N) 各 ProviderConfig（按 UI 顺序，enabled=true 的）
 *   N+1) 「退出」
 */
internal object VoiceCommandHandler {

    /** 选择模式的超时时间：超过这个时间自动退出选择模式 */
    private const val SELECTION_TIMEOUT_MS = 30_000L

    /** 选择模式状态，按 dialog_id 隔离 */
    private data class SelectionState(val dialogId: String, val enterAt: Long)

    @Volatile
    private var selection: SelectionState? = null

    /** 是否处于选择模式 */
    fun isSelecting(): Boolean {
        val s = selection ?: return false
        if (System.currentTimeMillis() - s.enterAt > SELECTION_TIMEOUT_MS) {
            selection = null
            ModelManager.setSwitching(false)
            XLog.i("模型选择模式超时，自动退出")
            return false
        }
        return true
    }

    fun exitSelection(reason: String) {
        if (selection != null) {
            XLog.i("退出模型选择模式: $reason")
        }
        selection = null
        ModelManager.setSwitching(false)
    }

    // ------------------------------------------------------------------ 1. 判定是否为「切换模型」指令

    /** 是否为切换模型指令（匹配用户配置的关键词，做了模糊化处理） */
    fun isSwitchCommand(text: String): Boolean {
        val cfg = ModelManager.config()
        if (!cfg.enableVoiceSwitch) return false
        val normalized = normalize(text)
        if (normalized.isEmpty()) return false
        return cfg.switchKeywords.any { kw ->
            val k = normalize(kw)
            k.isNotEmpty() && normalized.contains(k)
        }
    }

    // ------------------------------------------------------------------ 2. 处理识别结果

    sealed interface OnRecognize {
        /** 正常文本，继续走 AI 替换流程 */
        data class Normal(val text: String) : OnRecognize

        /** 进入模型选择模式，应当把回答替换为模型列表 */
        data class EnterSelection(val menuText: String) : OnRecognize

        /** 处于选择模式，用户报了序号：已完成切换，应当回复确认文本 */
        data class Switched(val providerKey: String, val providerName: String, val replyText: String) : OnRecognize

        /** 处于选择模式但用户说了无效内容：应当回复提示 */
        data class InvalidChoice(val replyText: String) : OnRecognize

        /** 无害，放行原始回答 */
        object Pass : OnRecognize
    }

    /**
     * 处理一条 is_final 的识别文本。
     *
     * @param text      ASR 原始文本
     * @param dialogId  当前对话 ID
     */
    fun onRecognize(text: String, dialogId: String): OnRecognize {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return OnRecognize.Pass

        val cfg = ModelManager.config()
        val menuItems = menuItems(cfg)

        // --- 选择模式优先 ---
        if (isSelecting()) {
            val choice = parseChoice(trimmed, menuItems.size)
            if (choice != null) {
                return handleChoice(choice, dialogId, menuItems)
            }
            // 选择模式下说了别的内容：提示用户，不退出，避免误触
            XLog.i("选择模式下收到非序号内容「$trimmed」，提示重选")
            return OnRecognize.InvalidChoice(
                buildString {
                    appendLine("没有听懂，请说数字选择：")
                    append(menuBody(menuItems))
                }
            )
        }

        // --- 切换指令 ---
        if (isSwitchCommand(trimmed)) {
            selection = SelectionState(dialogId, System.currentTimeMillis())
            ModelManager.setSwitching(true)
            XLog.i("检测到切换模型指令「$trimmed」，进入选择模式 (dialog=$dialogId)")
            return OnRecognize.EnterSelection(menuText(menuItems))
        }

        return OnRecognize.Normal(trimmed)
    }

    // ------------------------------------------------------------------ 3. 序号解析

    /**
     * 解析用户说的序号。
     *
     * @param menuSize 菜单总项数（含小爱 + 所有第三方 provider）
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

        // 关键词兜底
        val lower = t.lowercase()
        if (lower.contains("退出") || lower.contains("取消") || lower.contains("算了")) return exitChoice

        // 「小爱」直接选原生（永远在第 1 项）
        if (lower.contains("小爱") || lower.contains("原生")) return 1
        // 常见 provider 名字兜底
        if (lower.contains("deepseek") || lower.contains("深度求索")) return 2
        if (lower.contains("智谱") || lower.contains("glm")) return 3

        // 「选择2」「选3」这类
        Regex("""([1-9])""").find(t)?.let {
            val n = it.groupValues[1].toIntOrNull()
            if (n != null && n in 1..exitChoice) return n
        }

        return null
    }

    private fun handleChoice(index: Int, dialogId: String, menuItems: List<ProviderConfig>): OnRecognize {
        val exitChoice = menuItems.size + 1

        if (index == exitChoice) {
            exitSelection("用户选择退出")
            return OnRecognize.Switched(
                providerKey = AiConfig.XIAOAI_KEY,
                providerName = "小爱同学",
                replyText = "已退出模型选择。\n当前模型：${ModelManager.activeModel.value.displayName}",
            )
        }

        val picked = menuItems.getOrNull(index - 1)
        if (picked == null) {
            return OnRecognize.InvalidChoice("无效的选项，请说 1 到 ${exitChoice} 之间的数字。")
        }

        // 小爱行永远可切
        if (!picked.isXiaoAi) {
            // 第三方必须配置完整
            if (picked.apiKey.isBlank() || picked.model.isBlank() || picked.baseUrl.isBlank()) {
                XLog.w("provider ${picked.key} 未配置完成，拒绝切换")
                return OnRecognize.InvalidChoice(
                    "${picked.displayName.ifBlank { picked.providerType.displayName }} 还没有配置 API Key，" +
                        "请先在手机上完成配置。"
                )
            }
        }

        ModelManager.setActiveProvider(picked.key)
        exitSelection("切换完成 -> ${picked.displayName}")
        XLog.i("手环语音切换 provider: ${picked.key} (dialog=$dialogId)")
        return OnRecognize.Switched(
            providerKey = picked.key,
            providerName = picked.displayName.ifBlank { picked.providerType.displayName },
            replyText = "已切换到 ${picked.displayName.ifBlank { picked.providerType.displayName }}",
        )
    }

    // ------------------------------------------------------------------ 4. 文案

    /** 当前可被选的菜单项（XIAOAI + 所有 enabled 第三方 provider，保持 AiConfig.providers 的顺序） */
    fun menuItems(cfg: AiConfig = ModelManager.config()): List<ProviderConfig> =
        cfg.providers.filter { it.isXiaoAi || it.enabled }

    fun menuText(items: List<ProviderConfig> = menuItems()): String = buildString {
        appendLine("请选择模型")
        append(menuBody(items))
    }

    private fun menuBody(items: List<ProviderConfig>): String = buildString {
        items.forEachIndexed { i, p ->
            append(i + 1).append('.').append(p.displayName.ifBlank { p.providerType.displayName }).append('\n')
        }
        append(items.size + 1).append(".退出")
    }

    /** 确保每行都以「序号.」开头，方便 TTS 断句 */
    private fun normalize(s: String): String = s.trim()
        .lowercase()
        .replace(" ", "")
        .replace("\u3000", "")
}
