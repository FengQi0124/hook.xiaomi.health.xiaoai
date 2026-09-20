package com.zeroone01.xiaoai.hook

import com.zeroone01.xiaoai.core.ModelId
import com.zeroone01.xiaoai.core.ModelManager
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
 * 2.deepseek
 * 3.智谱
 * 4.退出
 * ```
 * 用户接着说数字（1/2/3/4）→ 模块切换模型并回复「已切换到 XXX」。
 *
 * ## 关键点
 *  - 选择模式是**有状态的**，且必须带 dialog_id 维度：小爱同学的连续对话会复用/切换
 *    dialog_id，用「全局一个布尔量」在多轮对话下会串味。
 *  - 状态有时效（默认 30s），避免用户说完「切换模型」后去干别的事，回来一句
 *    「1」被当成模型选择。
 *  - 数字识别要宽松：ASR 可能返回「1」「一」「第一个」「1.」甚至「1号」。
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
        data class Switched(val model: ModelId, val replyText: String) : OnRecognize

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

        // --- 选择模式优先 ---
        if (isSelecting()) {
            val choice = parseChoice(trimmed)
            if (choice != null) {
                return handleChoice(choice, dialogId)
            }
            // 选择模式下说了别的内容：提示用户，不退出，避免误触
            XLog.i("选择模式下收到非序号内容「$trimmed」，提示重选")
            return OnRecognize.InvalidChoice(
                buildString {
                    appendLine("没有听懂，请说数字选择：")
                    append(menuBody())
                }
            )
        }

        // --- 切换指令 ---
        if (isSwitchCommand(trimmed)) {
            selection = SelectionState(dialogId, System.currentTimeMillis())
            ModelManager.setSwitching(true)
            XLog.i("检测到切换模型指令「$trimmed」，进入选择模式 (dialog=$dialogId)")
            return OnRecognize.EnterSelection(menuText())
        }

        return OnRecognize.Normal(trimmed)
    }

    // ------------------------------------------------------------------ 3. 序号解析

    /** 解析用户说的序号，返回 1..4，无法识别返回 null */
    private fun parseChoice(raw: String): Int? {
        val t = raw.trim()
            .replace("。", "").replace("，", "").replace(",", "")
            .replace(".", "").replace("、", "").replace("号", "")
            .replace("第", "").replace("个", "")
            .replace("选项", "").replace("选择", "")

        // 纯阿拉伯数字
        t.toIntOrNull()?.let { if (it in 1..4) return it }

        // 中文数字
        val chinese = mapOf(
            "一" to 1, "二" to 2, "两" to 2, "三" to 3, "四" to 4,
            "壹" to 1, "贰" to 2, "叁" to 3, "肆" to 4,
        )
        chinese[t]?.let { return it }

        // 关键词兜底
        val lower = t.lowercase()
        if (lower.contains("退出") || lower.contains("取消") || lower.contains("算了")) return 4

        // 「小爱」直接选原生
        if (lower.contains("小爱")) return 1
        if (lower.contains("deepseek") || lower.contains("深度求索")) return 2
        if (lower.contains("智谱") || lower.contains("glm")) return 3

        // 「选择2」「选3」这类
        Regex("""([1-4])""").find(t)?.let { return it.groupValues[1].toIntOrNull() }

        return null
    }

    private fun handleChoice(index: Int, dialogId: String): OnRecognize {
        // 4 = 退出
        if (index == 4) {
            exitSelection("用户选择退出")
            return OnRecognize.Switched(
                ModelId.XIAOAI,
                "已退出模型选择。\n当前模型：${ModelManager.activeModel.value.displayName}",
            )
        }

        val model = ModelId.fromMenuIndex(index)
        if (model == null) {
            return OnRecognize.InvalidChoice("无效的选项，请说 1 到 4 之间的数字。")
        }

        // 检查第三方模型是否已配置 API Key
        val cfg = ModelManager.config()
        if (model.isThirdParty) {
            val provider = cfg.providerOf(model)
            if (provider.apiKey.isBlank() || provider.model.isBlank()) {
                XLog.w("模型 ${model.displayName} 未配置完成，拒绝切换")
                return OnRecognize.InvalidChoice(
                    "该模型还没有配置 API Key，请先在手机上完成配置。"
                )
            }
        }

        ModelManager.switchModel(model, source = "band_voice")
        exitSelection("切换完成 -> ${model.displayName}")
        XLog.i("手环语音切换模型成功: ${model.displayName} (dialog=$dialogId)")
        return OnRecognize.Switched(model, "已切换到 ${model.displayName}")
    }

    // ------------------------------------------------------------------ 4. 文案

    fun menuText(): String = buildString {
        appendLine("请选择模型")
        append(menuBody())
    }

    private fun menuBody(): String = ModelId.menuModels.joinToString("\n") {
        "${it.menuIndex}.${it.displayName}"
    } + "\n4.退出"

    /** 确保每行都以「序号.」开头，方便 TTS 断句 */
    private fun normalize(s: String): String = s.trim()
        .lowercase()
        .replace(" ", "")
        .replace("\u3000", "")
}
