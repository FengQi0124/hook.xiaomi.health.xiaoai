package com.zeroone01.xiaoai.config

import kotlinx.serialization.Serializable

/**
 * 多模型列表中的一个 LLM 提供方条目（0.5.0-beta 新增）。
 *
 * 说明：
 * - 列表**不含**第 1 行固定「小爱同学」（其 id 为 [ConfigKeys.VALUE_MODEL_XIAOAI]，
 *   仅作为 [ConfigKeys.KEY_ACTIVE_MODEL] 的特殊取值存在）；
 * - [apiKey] 在进程内以**明文**持有；写入 Remote Preferences 时由 [ConfigStore.setModelList]
 *   加密，读取时由 [ConfigStore.getModelList] 解密 —— 明文不会落盘、不会进日志；
 * - 条目字段决定请求参数（base_url / model / api_type / append_api_path），
 *   激活某条目后 [ConfigStore.getBaseUrl] 等读取器自动改走该条目的值。
 */
@Serializable
data class ModelEntry(
    /** 唯一 id（UI 创建时生成，用于激活态定位，勿复用） */
    val id: String,
    /** 显示名称（模型菜单与设置页列表行标题） */
    val name: String = "",
    /** 请求地址（空白时回退全局 base_url 默认值） */
    val baseUrl: String = "",
    /** API Key 明文（存储时加密） */
    val apiKey: String = "",
    /** 模型名称 */
    val model: String = "",
    /** API 类型："openai" / "anthropic" */
    val apiType: String = ConfigKeys.DEFAULT_API_TYPE,
    /** 是否自动拼接 /v1/chat/completions 等路径 */
    val appendApiPath: Boolean = ConfigKeys.DEFAULT_APPEND_API_PATH,
)

/**
 * 常见 API 提供方预设 —— 「添加模型」对话框下拉菜单的数据源。
 *
 * 选择预设仅预填 baseUrl / model / apiType / appendApiPath，不携带任何 Key；
 * apiType=anthropic 的提供方 appendApiPath=false（其地址即完整 messages 端点时按需调整）。
 */
object ModelPresets {

    /** 一条提供方预设 */
    data class Preset(
        /** 下拉菜单显示名 */
        val label: String,
        /** 预填请求地址 */
        val baseUrl: String,
        /** 预填模型名 */
        val model: String,
        /** 预填 API 类型 */
        val apiType: String = ConfigKeys.DEFAULT_API_TYPE,
        /** 预填是否拼接 API 路径 */
        val appendApiPath: Boolean = ConfigKeys.DEFAULT_APPEND_API_PATH,
    )

    /** 自定义项（不清空已有输入，仅作为下拉末项） */
    const val CUSTOM_LABEL = "自定义"

    /** 提供方预设列表（按国内常用排序） */
    val ALL: List<Preset> = listOf(
        Preset("DeepSeek", "https://api.deepseek.com", "deepseek-v4-flash"),
        Preset("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-flash"),
        Preset("智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"),
        Preset("硅基流动", "https://api.siliconflow.cn/v1", "Qwen/Qwen2.5-7B-Instruct"),
        Preset("火山方舟", "https://ark.cn-beijing.volces.com/api/v3", "doubao-seed-1-6-flash-250615"),
        Preset("讯飞星火", "https://spark-api-open.xf-yun.com/v1", "generalv3.5"),
        Preset("月之暗面 Kimi", "https://api.moonshot.cn/v1", "moonshot-v1-8k"),
        Preset("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
        Preset(
            "Google Gemini",
            "https://generativelanguage.googleapis.com/v1beta/openai",
            "gemini-2.5-flash",
        ),
        Preset("OpenRouter", "https://openrouter.ai/api/v1", "openrouter/auto"),
        Preset(CUSTOM_LABEL, "", ""),
    )

    /** 按下拉显示名查预设；未命中返回 null */
    fun byLabel(label: String): Preset? = ALL.firstOrNull { it.label == label }
}
