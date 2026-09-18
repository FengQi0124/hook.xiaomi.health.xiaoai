package com.fengqi.xiaoai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.fengqi.xiaoai.core.AiConfig
import com.fengqi.xiaoai.core.ModelId
import com.fengqi.xiaoai.core.ProviderConfig

/**
 * 设置页主界面（Miuix / Compose）。
 *
 * 结构（顶栏 + 卡片切换）：
 *  - TopAppBar：左侧标题，右侧关闭按钮（Miuix 标准模式，不被 ScrollView 推出视口）
 *  - 当前模型选择（卡片，单选）
 *  - 各提供方参数（API Key / Base URL / 模型名）
 *  - 生成参数（系统提示词、超时、max_tokens、温度）
 *  - 手环语音切换（开关 + 触发词）
 *  - 高级（流式劫持、兜底、日志）
 *
 * 所有修改通过 [onSave] 立即持久化（用户点一下开关就写盘，不需要"保存"按钮），
 * 这样手环端能第一时间同步到新配置。
 */
@Composable
fun SettingsScreen(
    config: AiConfig,
    onSave: (AiConfig) -> Unit,
    onClose: () -> Unit,
    onOpenDiagnostics: () -> Unit = {},
) {
    val activeModel = ModelId.fromKey(config.activeModelKey)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        // ---------------------------------------------------------- 顶栏
        SmallTopAppBar(
            title = "AI 助手设置",
            actions = {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.padding(end = 16.dp),
                ) {
                    Icon(
                        imageVector = MiuixIcons.Close,
                        contentDescription = "关闭",
                        modifier = Modifier.size(20.dp),
                    )
                }
            },
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ---------------------------------------------------------- 副标题
            Text(
                text = "在小米运动健康里把「小爱同学」换成你自己的 AI，并可用手环语音切换模型。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceContainerHigh,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
            )

            // ---------------------------------------------------------- 1. 当前模型
            SmallTitle(text = "当前模型")
            Card {
                ModelId.entries.filter { it != ModelId.CUSTOM }
                    .forEach { model ->
                        ModelRow(
                            model = model,
                            selected = model == activeModel,
                            enabled = !model.isThirdParty || isModelReady(config, model),
                            onSelect = { onSave(config.copy(activeModelKey = model.key)) },
                        )
                        HorizontalDivider()
                    }
                ModelRow(
                    model = ModelId.CUSTOM,
                    selected = activeModel == ModelId.CUSTOM,
                    enabled = isModelReady(config, ModelId.CUSTOM),
                    onSelect = { onSave(config.copy(activeModelKey = ModelId.CUSTOM.key)) },
                )
            }

            if (activeModel == ModelId.XIAOAI) {
                HintText("小爱同学模式下不做任何拦截，走小米原生流程。")
            } else if (!isModelReady(config, activeModel)) {
                HintText("⚠ 当前模型缺少 API Key 或模型名，请先在下方配置，否则会放行原始回答。", warn = true)
            }

            // ---------------------------------------------------------- 2. 模型参数
            SmallTitle(text = "模型参数")

            listOf(ModelId.DEEPSEEK, ModelId.ZHIPU, ModelId.CUSTOM).forEachIndexed { idx, model ->
                ProviderCard(
                    model = model,
                    provider = config.providerOf(model),
                    onChange = { updated ->
                        onSave(config.copy(providers = config.providers + (model.key to updated)))
                    },
                )
                if (idx != 2) Spacer(Modifier.height(8.dp))
            }

            // ---------------------------------------------------------- 3. 生成参数
            SmallTitle(text = "生成参数")
            Card {
                FieldRow(
                    label = "系统提示词",
                    value = config.systemPrompt,
                    singleLine = false,
                    helper = "可用占位符：<modelname>（替换为当前模型名，如 DeepSeek / 智谱 / 自定义）",
                    onValueChange = { onSave(config.copy(systemPrompt = it)) },
                )
                HorizontalDivider()
                FieldRow(
                    label = "超时时间（毫秒）",
                    value = config.timeoutMs.toString(),
                    keyboardType = KeyboardType.Number,
                    helper = "超过该时间仍未拿到 AI 回复，就放行小爱的原始回答，避免手环卡住。建议 5000 ~ 10000。",
                    onValueChange = { text ->
                        text.toLongOrNull()?.let { onSave(config.copy(timeoutMs = it.coerceIn(1000L, 60000L))) }
                    },
                )
                HorizontalDivider()
                FieldRow(
                    label = "最大 Token",
                    value = config.maxTokens.toString(),
                    keyboardType = KeyboardType.Number,
                    onValueChange = { text ->
                        text.toIntOrNull()?.let { onSave(config.copy(maxTokens = it.coerceIn(32, 4096))) }
                    },
                )
                HorizontalDivider()
                FieldRow(
                    label = "温度",
                    value = config.temperature.toString(),
                    keyboardType = KeyboardType.Decimal,
                    onValueChange = { text ->
                        text.toDoubleOrNull()?.let { onSave(config.copy(temperature = it.coerceIn(0.0, 2.0))) }
                    },
                )
            }

            // ---------------------------------------------------------- 4. 手环语音
            SmallTitle(text = "手环语音控制")
            Card {
                SwitchRow(
                    title = "启用语音切换模型",
                    summary = "对手环说「切换模型」，会直接回复模型列表供你选择。",
                    checked = config.enableVoiceSwitch,
                    onCheckedChange = { onSave(config.copy(enableVoiceSwitch = it)) },
                )
                HorizontalDivider()
                FieldRow(
                    label = "触发词（用逗号分隔）",
                    value = config.switchKeywords.joinToString(","),
                    helper = "说出的内容命中任一触发词即进入选择模式。",
                    onValueChange = { text ->
                        val list = text.split(',', '，')
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                        onSave(config.copy(switchKeywords = list.ifEmpty { AiConfig().switchKeywords }))
                    },
                )
            }

            // ---------------------------------------------------------- 5. 高级
            SmallTitle(text = "高级")
            Card {
                SwitchRow(
                    title = "多轮上下文",
                    summary = "把最近几轮问答带给模型，让回答更连贯。",
                    checked = config.enableHistory,
                    onCheckedChange = { onSave(config.copy(enableHistory = it)) },
                )
                HorizontalDivider()
                SwitchRow(
                    title = "流式回答也替换",
                    summary = "小爱同学的新版流式回答（ToastStream）同样会被替换。",
                    checked = config.hookToastStream,
                    onCheckedChange = { onSave(config.copy(hookToastStream = it)) },
                )
                HorizontalDivider()
                SwitchRow(
                    title = "失败时放行原始回答",
                    summary = "超时或报错时保留小爱的原始回答而不是卡住。强烈建议开启。",
                    checked = config.fallbackToOriginal,
                    onCheckedChange = { onSave(config.copy(fallbackToOriginal = it)) },
                )
                HorizontalDivider()
                SwitchRow(
                    title = "在「我的」页面显示入口",
                    summary = "在小米运动健康的「我的路线库」和「App设置」之间插入入口。",
                    checked = config.injectMineEntry,
                    onCheckedChange = { onSave(config.copy(injectMineEntry = it)) },
                )
                HorizontalDivider()
                SwitchRow(
                    title = "详细日志",
                    summary = "输出调试日志，可用 adb logcat -s XiaoAiHijack:V 查看。",
                    checked = config.verboseLog,
                    onCheckedChange = { onSave(config.copy(verboseLog = it)) },
                )
            }

            Spacer(Modifier.height(8.dp))
            TextButton(
                text = "运行诊断",
                onClick = onOpenDiagnostics,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ======================================================================
// 组件

@Composable
private fun ModelRow(
    model: ModelId,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    top.yukonga.miuix.kmp.basic.BasicComponent(
        onClick = { if (enabled || !model.isThirdParty) onSelect() },
        enabled = enabled,
        title = model.displayName,
        summary = when {
            !model.isThirdParty -> "小米原生，不拦截"
            !enabled -> "未配置，请先在下方填写 API Key"
            selected -> "当前使用中"
            else -> null
        },
        endActions = {
            Text(
                text = if (selected) "✓ 已选" else "选择",
                color = if (selected) MiuixTheme.colorScheme.primary
                else MiuixTheme.colorScheme.onSurfaceContainerHigh,
                style = MiuixTheme.textStyles.footnote1,
            )
        },
    )
}

@Composable
private fun ProviderCard(
    model: ModelId,
    provider: ProviderConfig,
    onChange: (ProviderConfig) -> Unit,
) {
    Card {
        Text(
            text = model.displayName + if (provider.isPreset) "（预设）" else "（OpenAI 兼容）",
            style = MiuixTheme.textStyles.title2,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
        )
        Text(
            text = "Base URL: ${provider.baseUrl.ifBlank { "未设置" }}  ·  模型: ${provider.model.ifBlank { "未设置" }}",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceContainerHigh,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )

        FieldRow(
            label = "API Key",
            value = provider.apiKey,
            masked = true,
            singleLine = true,
            onValueChange = { onChange(provider.copy(apiKey = it)) },
        )

        if (!provider.isPreset) {
            HorizontalDivider()
            FieldRow(
                label = "Base URL",
                value = provider.baseUrl,
                singleLine = true,
                helper = "OpenAI 兼容端点，例如 https://api.openai.com/v1",
                onValueChange = { onChange(provider.copy(baseUrl = it)) },
            )
            HorizontalDivider()
            FieldRow(
                label = "模型名称",
                value = provider.model,
                singleLine = true,
                helper = "例如 gpt-4o-mini / moonshot-v1-8k / qwen-plus",
                onValueChange = { onChange(provider.copy(model = it)) },
            )
        }
    }
}

@Composable
private fun FieldRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean = false,
    masked: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    helper: String? = null,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            singleLine = singleLine,
            maxLines = if (singleLine) 1 else 6,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
        if (helper != null) {
            Text(
                text = helper,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceContainerHigh,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp),
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    top.yukonga.miuix.kmp.basic.BasicComponent(
        title = title,
        summary = summary,
        onClick = { onCheckedChange(!checked) },
        endActions = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
    )
}

@Composable
private fun HintText(text: String, warn: Boolean = false) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote2,
        color = if (warn) MiuixTheme.colorScheme.error
        else MiuixTheme.colorScheme.onSurfaceContainerHigh,
        modifier = Modifier.padding(start = 8.dp, top = 6.dp, end = 8.dp),
    )
}

/** 模型是否已配置完整（内置模型只需 API Key） */
private fun isModelReady(config: AiConfig, model: ModelId): Boolean {
    if (!model.isThirdParty) return true
    val p = config.providerOf(model)
    return p.apiKey.isNotBlank() && p.model.isNotBlank() && p.baseUrl.isNotBlank()
}
