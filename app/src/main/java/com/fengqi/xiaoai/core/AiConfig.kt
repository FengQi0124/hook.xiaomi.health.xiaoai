package com.fengqi.xiaoai.core

import kotlinx.serialization.Serializable

/**
 * 单个提供方的配置。
 *
 * 只有 [isPreset] 为 true 的提供方（DeepSeek / 智谱）才允许只填 API Key；
 * 自定义提供方必须填 [baseUrl] 与 [model]。
 */
@Serializable
data class ProviderConfig(
    /** 对应 [ModelId.key] */
    val key: String,
    val enabled: Boolean = true,
    /** OpenAI 兼容 Base URL，例如 https://api.deepseek.com/v1 */
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    /** 预设提供方不允许用户改 Base URL / 模型名 */
    val isPreset: Boolean = true,
    /** 该提供方是否支持流式 SSE 解析 */
    val supportStream: Boolean = true,
)

/**
 * 应用全局配置。
 *
 * 注意：所有字段都给默认值，这样 JSON 缺字段时也能安全反序列化（向前兼容）。
 */
@Serializable
data class AiConfig(
    /** 当前生效的模型 key，见 [ModelId] */
    val activeModelKey: String = ModelId.XIAOAI.key,

    /** 各提供方配置，键为 [ModelId.key] */
    val providers: Map<String, ProviderConfig> = defaultProviders(),

    /** 系统提示词 */
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,

    /** 第三方 AI 请求超时（毫秒）。超过此时间就放行原始回答 */
    val timeoutMs: Long = 8000L,

    /** 最大生成 token */
    val maxTokens: Int = 200,

    /** 温度 */
    val temperature: Double = 0.7,

    /** 是否把上一轮对话作为上下文带给模型（多轮记忆） */
    val enableHistory: Boolean = true,

    /** 保留的上下文字数（条，一问一答算 2 条） */
    val historyRounds: Int = 3,

    /** 手环语音切换模型开关 */
    val enableVoiceSwitch: Boolean = true,

    /** 切换触发词（命中即进入选择模式） */
    val switchKeywords: List<String> = listOf("切换模型", "换一个模型", "切换ai", "更换模型"),

    /** 是否也替换 Template.ToastStream（流式） */
    val hookToastStream: Boolean = true,

    /** 是否放行超时/出错时的原始回答（建议开启，否则手环会一直转圈） */
    val fallbackToOriginal: Boolean = true,

    /** 是否输出详细日志 */
    val verboseLog: Boolean = true,

    /** 是否在“我的”页面注入入口 */
    val injectMineEntry: Boolean = true,
) {
    fun activeModel(): ModelId = ModelId.fromKey(activeModelKey)

    fun providerOf(model: ModelId): ProviderConfig =
        providers[model.key] ?: defaultProviders().getValue(model.key)

    /** 把配置拍平成一次请求所需的参数；配置不完整时返回 null */
    fun toChatRequest(model: ModelId, history: List<ChatMessage>): ChatRequest? {
        val p = providerOf(model)
        if (!model.isThirdParty) return null
        val url = p.baseUrl.trim().ifBlank { return null }
        val key = p.apiKey.trim().ifBlank { return null }
        val mdl = p.model.trim().ifBlank { return null }
        return ChatRequest(
            providerKey = p.key,
            displayName = model.displayName,
            baseUrl = url,
            apiKey = key,
            model = mdl,
            systemPrompt = resolveSystemPrompt(model),
            timeoutMs = timeoutMs,
            maxTokens = maxTokens,
            temperature = temperature,
            history = history,
        )
    }

    /**
     * 把系统提示词里的占位符替换成实际值。
     *
     * 当前支持的占位：
     *  - `<modelname>` → 当前模型的展示名（DeepSeek / 智谱 / 自定义）。
     *    让模型明确知道「我是谁」，避免它误以为自己是 ChatGPT。
     *
     * 占位语法刻意简单：`<xxx>` 形式，未来扩展 `<provider>` / `<date>` 等直接加进 regex 即可。
     */
    fun resolveSystemPrompt(model: ModelId): String =
        systemPrompt.replace("<modelname>", model.displayName)

    companion object {
        // 注意：默认提示词包含 `<modelname>` 占位符，会在请求时被
        // [resolveSystemPrompt] 替换为当前模型的展示名。
        const val DEFAULT_SYSTEM_PROMPT =
            "你是一个由 <modelname> 驱动的语音助手（运行在小米手环上）。" +
                "请始终以「<modelname>」的人设回答用户问题，不要伪装成其他公司产品。" +
                "用户使用语音输入，可能存在错别字，请结合上下文合理理解。" +
                "回答务必简洁，尽量控制在80字以内，不要使用 Markdown 格式，" +
                "不要输出表情符号，因为你说的每一句话都会被读出来。"

        fun defaultProviders(): Map<String, ProviderConfig> = mapOf(
            ModelId.XIAOAI.key to ProviderConfig(
                key = ModelId.XIAOAI.key,
                baseUrl = "",
                model = "",
                isPreset = true,
                supportStream = false,
            ),
            ModelId.DEEPSEEK.key to ProviderConfig(
                key = ModelId.DEEPSEEK.key,
                baseUrl = "https://api.deepseek.com/v1",
                model = "deepseek-chat",
                isPreset = true,
            ),
            ModelId.ZHIPU.key to ProviderConfig(
                key = ModelId.ZHIPU.key,
                baseUrl = "https://open.bigmodel.cn/api/paas/v4",
                model = "glm-4-flash",
                isPreset = true,
            ),
            ModelId.CUSTOM.key to ProviderConfig(
                key = ModelId.CUSTOM.key,
                baseUrl = "https://api.openai.com/v1",
                model = "gpt-4o-mini",
                isPreset = false,
            ),
        )
    }
}
