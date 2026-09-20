package com.zeroone01.xiaoai.core

import kotlinx.serialization.Serializable

/**
 * 单个提供方的配置。
 *
 * v0.1.0-beta5 起改成「可任意增删的列表」模型：UI 的每一行就是一个 ProviderConfig，
 * 第一行（小爱同学）固定为 XIAOAI 不可删除；后续行由用户点击「添加」按钮生成，
 * 每行可以单独展开/收起。
 *
 * 字段含义与之前一致，但 [key] 不再与 [ModelId] 强绑定——小爱那一行 key="xiaoai"
 * 且 isXiaoAi=true，其余行 key 是模块生成的 UUID（写到磁盘稳定，但允许删除）。
 */
@Serializable
data class ProviderConfig(
    /** 内部存储 key：XIAOAI 那行写 "xiaoai"；其余行写 UUID */
    val key: String,
    /** 用户起的展示名（左侧编辑框内容），默认同 [providerType] 展示名 */
    val displayName: String = "",
    /** 是否启用：关闭的行不出现在手环选择菜单里、且不参与 activeModelKey 切换 */
    val enabled: Boolean = true,
    /** OpenAI 兼容 Base URL，例如 https://api.deepseek.com/v1 */
    val baseUrl: String = "",
    val apiKey: String = "",
    /** 模型名，例如 deepseek-chat / glm-4-flash / gpt-4o-mini */
    val model: String = "",
    /**
     * 提供商类型：决定 UI 默认填的 baseUrl/model 模板，并决定「模型提供方」。
     *
     * - DEEPSEEK / ZHIPU / OPENAI / MOONSHOT / CUSTOM
     * - XIAOAI：保留但 UI 上不暴露（小爱那行固定用 key="xiaoai" + providerType=XIAOAI）
     */
    val providerType: ProviderType = ProviderType.CUSTOM,
) {
    /** 是否为小爱原生行（不可删除、不可编辑 providerType） */
    val isXiaoAi: Boolean get() = providerType == ProviderType.XIAOAI

    /** 第三方提供方才需要 API Key / Base URL */
    val needsAuth: Boolean get() = providerType != ProviderType.XIAOAI
}

/**
 * 提供商类型枚举。
 *
 * 每个类型决定一组默认值（baseUrl / 默认模型名 / 展示名）。
 * UI 上作为下拉框选项；用户选了之后可以再手改 baseUrl 和 model。
 */
@Serializable
enum class ProviderType(
    val displayName: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
) {
    XIAOAI("小爱同学（原生）", "", ""),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
    ZHIPU("智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"),
    OPENAI("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
    MOONSHOT("月之暗面", "https://api.moonshot.cn/v1", "moonshot-v1-8k"),
    CUSTOM("自定义 OpenAI 兼容", "", ""),
    ;
}

/**
 * 应用全局配置。
 *
 * v0.1.0-beta5 起结构：
 *  - providers 是**有序列表**，UI 渲染顺序 = 列表顺序；
 *  - 第一项永远是 XIAOAI 那一行（不可删除）；
 *  - activeModelKey 必须命中 providers 里某一行的 key，否则当作 XIAOAI 处理；
 *  - UI 操作（添加/删除/编辑某一行）会触发 list 的整体替换（copy）。
 *
 * 注意：所有字段都给默认值，这样 JSON 缺字段时也能安全反序列化（向前兼容）。
 */
@Serializable
data class AiConfig(
    /** 当前生效的提供方 key，必须命中 providers 里某一行的 key */
    val activeModelKey: String = XIAOAI_KEY,

    /** 有序的提供方列表（第一项固定为 XIAOAI 那一行） */
    val providers: List<ProviderConfig> = defaultProviders(),

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

    /** 是否在小米运动健康的「设置」页面注入入口 */
    val injectMineEntry: Boolean = true,
) {
    /** 当前 activeModel 对应的 provider 找 XIAOAI 行（永远存在） */
    fun activeProvider(): ProviderConfig =
        providers.firstOrNull { it.key == activeModelKey }
            ?: providers.firstOrNull { it.isXiaoAi }
            ?: defaultProviders().first()

    /** 是否处于「用小爱原生」状态（UI 用来决定是否禁用大段配置） */
    val isXiaoAiActive: Boolean get() = activeProvider().isXiaoAi

    /**
     * 把配置拍平成一次请求所需的参数；配置不完整时返回 null。
     *
     * 注意：activeProvider().providerType 决定 protocol；
     * XIAOAI 行直接返回 null（不调用任何 AI）。
     */
    fun toChatRequest(history: List<ChatMessage>): ChatRequest? {
        val p = activeProvider()
        if (p.isXiaoAi) return null
        val url = p.baseUrl.trim().ifBlank { return null }
        val key = p.apiKey.trim().ifBlank { return null }
        val mdl = p.model.trim().ifBlank { return null }
        return ChatRequest(
            providerKey = p.key,
            displayName = p.displayName.ifBlank { p.providerType.displayName },
            baseUrl = url,
            apiKey = key,
            model = mdl,
            systemPrompt = resolveSystemPrompt(),
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
     *  - `<modelname>` → 当前模型的展示名
     */
    fun resolveSystemPrompt(): String =
        systemPrompt.replace("<modelname>", activeProvider().displayName)

    companion object {
        /** 小爱原生行的固定 key，所有地方都用这个常量 */
        const val XIAOAI_KEY = "xiaoai"

        const val DEFAULT_SYSTEM_PROMPT =
            "你是一个由 <modelname> 驱动的语音助手（运行在小米手环上）。" +
                "请始终以「<modelname>」的人设回答用户问题，不要伪装成其他公司产品。" +
                "用户使用语音输入，可能存在错别字，请结合上下文合理理解。" +
                "回答务必简洁，尽量控制在80字以内，不要使用 Markdown 格式，" +
                "不要输出表情符号，因为你说的每一句话都会被读出来。"

        /** 默认配置：第一行 = 小爱（不可删）；后面两行示例提供方（用户可删） */
        fun defaultProviders(): List<ProviderConfig> = listOf(
            ProviderConfig(
                key = XIAOAI_KEY,
                displayName = "小爱同学",
                providerType = ProviderType.XIAOAI,
                enabled = true,
            ),
            ProviderConfig(
                key = "preset-deepseek",
                displayName = "DeepSeek",
                providerType = ProviderType.DEEPSEEK,
                enabled = true,
            ),
            ProviderConfig(
                key = "preset-zhipu",
                displayName = "智谱",
                providerType = ProviderType.ZHIPU,
                enabled = true,
            ),
        )

        /** 升级旧版本（providers 还是 Map<String, ProviderConfig>）的配置 */
        @Suppress("UNCHECKED_CAST")
        fun migrateLegacy(raw: Map<String, Any?>): AiConfig {
            val oldProviders = raw["providers"] as? Map<String, Map<String, Any?>> ?: emptyMap()
            val migrated = mutableListOf<ProviderConfig>()
            // 第一行固定小爱
            migrated.add(
                ProviderConfig(
                    key = XIAOAI_KEY,
                    displayName = "小爱同学",
                    providerType = ProviderType.XIAOAI,
                )
            )
            oldProviders.forEach { (k, v) ->
                if (k == XIAOAI_KEY) return@forEach
                val type = when (k) {
                    "deepseek" -> ProviderType.DEEPSEEK
                    "zhipu" -> ProviderType.ZHIPU
                    "custom" -> ProviderType.CUSTOM
                    else -> ProviderType.CUSTOM
                }
                migrated.add(
                    ProviderConfig(
                        key = if (k in setOf("deepseek", "zhipu", "custom")) "preset-$k" else k,
                        displayName = type.displayName,
                        providerType = type,
                        baseUrl = v["baseUrl"] as? String ?: type.defaultBaseUrl,
                        apiKey = v["apiKey"] as? String ?: "",
                        model = v["model"] as? String ?: type.defaultModel,
                        enabled = (v["enabled"] as? Boolean) ?: true,
                    )
                )
            }
            return AiConfig(
                activeModelKey = raw["activeModelKey"] as? String ?: XIAOAI_KEY,
                providers = migrated,
                systemPrompt = raw["systemPrompt"] as? String ?: DEFAULT_SYSTEM_PROMPT,
                timeoutMs = (raw["timeoutMs"] as? Long) ?: 8000L,
                maxTokens = (raw["maxTokens"] as? Int) ?: 200,
                temperature = (raw["temperature"] as? Double) ?: 0.7,
                enableHistory = (raw["enableHistory"] as? Boolean) ?: true,
                historyRounds = (raw["historyRounds"] as? Int) ?: 3,
                enableVoiceSwitch = (raw["enableVoiceSwitch"] as? Boolean) ?: true,
                switchKeywords = (raw["switchKeywords"] as? List<String>)
                    ?: listOf("切换模型", "换一个模型", "切换ai", "更换模型"),
                hookToastStream = (raw["hookToastStream"] as? Boolean) ?: true,
                fallbackToOriginal = (raw["fallbackToOriginal"] as? Boolean) ?: true,
                verboseLog = (raw["verboseLog"] as? Boolean) ?: true,
                injectMineEntry = (raw["injectMineEntry"] as? Boolean) ?: true,
            )
        }
    }
}