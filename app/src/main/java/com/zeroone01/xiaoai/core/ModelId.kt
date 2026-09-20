package com.zeroone01.xiaoai.core

/**
 * 模型标识。
 *
 * 设计说明：这里只定义“预设”模型。`CUSTOM` 是一个占位符，实际请求参数
 * 从 [com.zeroone01.xiaoai.data.AiConfig] 的 custom 字段读取。
 *
 * 序号（用于手环语音选择）：
 *  1 = XIAOAI（小爱同学，原生，不劫持）
 *  2 = DEEPSEEK
 *  3 = ZHIPU
 *  4 = 退出（仅手环菜单语义，不是一个模型）
 */
enum class ModelId(
    /** 内部存储名（小写，稳定不变，不要随意改，否则用户配置会失效） */
    val key: String,
    /** 展示名 */
    val displayName: String,
    /** 手环模型列表中的序号，0 表示不参与手环快捷菜单 */
    val menuIndex: Int,
) {
    /** 小米原生小爱同学：不进行任何替换 */
    XIAOAI("xiaoai", "小爱同学", 1),

    /** DeepSeek（OpenAI 兼容） */
    DEEPSEEK("deepseek", "DeepSeek", 2),

    /** 智谱 GLM（OpenAI 兼容） */
    ZHIPU("zhipu", "智谱", 3),

    /** 自定义：OpenAI 兼容端点，Base URL / API Key / 模型名全部自填 */
    CUSTOM("custom", "自定义", 0),
    ;

    /** 是否为第三方（非小爱原生），即需要劫持回答 */
    val isThirdParty: Boolean get() = this != XIAOAI

    companion object {
        /** 手环菜单里可选的模型（按序号排序） */
        val menuModels: List<ModelId> = listOf(XIAOAI, DEEPSEEK, ZHIPU).sortedBy { it.menuIndex }

        fun fromKey(key: String?): ModelId =
            entries.firstOrNull { it.key == key } ?: XIAOAI

        /** 手环菜单序号 → 模型。序号 4 是“退出”，返回 null */
        fun fromMenuIndex(index: Int): ModelId? = menuModels.firstOrNull { it.menuIndex == index }
    }
}

/** 单次对话的一条消息，OpenAI Chat Completions 格式 */
data class ChatMessage(val role: String, val content: String)

/**
 * 一次模型调用的完整参数快照（从配置里拍平，避免请求时再读配置造成竞态）
 */
data class ChatRequest(
    val providerKey: String,
    val displayName: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val systemPrompt: String,
    val timeoutMs: Long,
    val maxTokens: Int,
    val temperature: Double,
    val history: List<ChatMessage> = emptyList(),
)

/** 调用结果 */
sealed interface ChatResult {
    /** 成功拿到回复文本 */
    data class Success(val text: String, val elapsedMs: Long) : ChatResult

    /**
     * 失败。调用方应当“放行原始回答”，保证手环至少有响应。
     * @param fatal true 表示配置类错误（缺少 API Key 等），可以提示用户
     */
    data class Failure(val reason: String, val fatal: Boolean = false) : ChatResult
}
