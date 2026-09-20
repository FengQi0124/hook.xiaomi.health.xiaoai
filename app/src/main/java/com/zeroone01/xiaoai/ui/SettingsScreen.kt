package com.zeroone01.xiaoai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.ExpandLess
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.zeroone01.xiaoai.core.AiConfig
import com.zeroone01.xiaoai.core.ModelManager
import com.zeroone01.xiaoai.core.ProviderConfig
import com.zeroone01.xiaoai.core.ProviderType
import java.util.UUID

/**
 * 设置页主界面（v0.1.0-beta5：行式布局）。
 *
 * 规范（来自用户）：
 *  - 第 1 行：小爱同学（默认、不可删除）
 *  - 第 2 行：「+ 添加」按钮（点击新增一行，按钮自动下移到新行末尾）
 *  - 后续每一行：左侧 = 用户起的名字（可编辑）；右侧 = 「设置/收起」按钮
 *  - 展开状态：行内显示「提供商类型 / API Key / 模型名 / Base URL」等字段
 *  - 顶部右上角：保存按钮（写入磁盘）
 *  - 顶部右上角：关闭按钮（退回上一页）
 *
 * 设计要点：
 *  - 编辑直接走 in-memory 的 `draftProviders`（可立即反映在 UI 上），
 *    点保存才落盘。
 *  - 行的「展开/收起」状态用 mutableStateMapOf 按 key 维护。
 *  - 小爱那一行不能编辑名字、不能删除、不能展开。
 */
@Composable
fun SettingsScreen(
    config: AiConfig,
    onSave: (AiConfig) -> Unit,
    onClose: () -> Unit,
    onOpenDiagnostics: () -> Unit = {},
    isDarkTheme: Boolean = false,
) {
    val scope = rememberCoroutineScope()

    // 草稿：以 providers 的 key 为索引；保证编辑过程中行不会重排
    val draftProviders = remember {
        mutableStateMapOf<String, ProviderConfig>().apply {
            config.providers.forEach { put(it.key, it) }
        }
    }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    // 行顺序：磁盘顺序（保留 XIAOAI 在第一位）
    // ★ beta6 修复：用 mutableStateListOf 让 add/remove 触发 recomposition。
    //   旧的 `MutableList` 不是 Compose observable，加行 / 删行 UI 不刷新。
    val rowOrder = remember(config.providers) {
        mutableStateListOf<String>().apply { addAll(config.providers.map { it.key }) }
    }

    // 同步磁盘版本到草稿（外部进程修改后切回来 UI 时也能跟上）
    LaunchedEffect(config.providers) {
        config.providers.forEach { p ->
            if (!draftProviders.containsKey(p.key)) {
                draftProviders[p.key] = p
            }
        }
        // 清理已被磁盘删掉的行
        val diskKeys = config.providers.map { it.key }.toSet()
        val removed = draftProviders.keys.filter { it !in diskKeys }
        removed.forEach { draftProviders.remove(it); expanded.remove(it) }
        // rowOrder 同步（observable list，自动触发 recomposition）
        val toRemove = rowOrder.filter { it !in diskKeys }
        toRemove.forEach { rowOrder.remove(it) }
        config.providers.map { it.key }.forEach { k ->
            if (k !in rowOrder) rowOrder.add(k)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isDarkTheme) Color(0xFF101013) else Color(0xFFF7F7F8))
            .verticalScroll(rememberScrollState()),
    ) {
        // ============================================================ 顶栏
        SmallTopAppBar(
            title = "AI 助手设置",
            actions = {
                IconButton(
                    onClick = {
                        // 把草稿按 rowOrder 拍平成新 list 写盘
                        val newList = rowOrder.mapNotNull { draftProviders[it] }
                        onSave(config.copy(providers = newList))
                        scope.launch { /* 视觉反馈占位 */ }
                    },
                    modifier = Modifier.padding(end = 4.dp),
                ) {
                    Text(
                        text = "保存",
                        style = MiuixTheme.textStyles.button,
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.padding(end = 12.dp),
                ) {
                    Text(
                        text = "关闭",
                        style = MiuixTheme.textStyles.button,
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
            },
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "在小米运动健康里把「小爱同学」换成你自己的 AI，并可用手环语音切换模型。",
                style = MiuixTheme.textStyles.footnote1,
                color = if (isDarkTheme) Color(0xFFB0B3B5) else Color(0xFF606060),
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
            )

            // ================================================== 提供方列表
            rowOrder.forEach { key ->
                val provider = draftProviders[key] ?: return@forEach
                if (provider.isXiaoAi) {
                    XIAOAIRow(provider = provider, isActive = config.activeModelKey == key)
                } else {
                    ProviderRow(
                        provider = provider,
                        expanded = expanded[key] == true,
                        isActive = config.activeModelKey == key,
                        onNameChange = { newName ->
                            draftProviders[key] = provider.copy(displayName = newName)
                        },
                        onToggle = { expanded[key] = !(expanded[key] ?: false) },
                        onUpdate = { updated ->
                            draftProviders[key] = updated
                        },
                        onDelete = {
                            draftProviders.remove(key)
                            expanded.remove(key)
                            rowOrder.remove(key)
                        },
                        isDarkTheme = isDarkTheme,
                    )
                }
            }

            // ================================================== 「添加」按钮（永远在最后一行）
            AddProviderRow(
                onAdd = {
                    val newKey = "u-" + UUID.randomUUID().toString().take(8)
                    val newProvider = ProviderConfig(
                        key = newKey,
                        displayName = "新提供方",
                        providerType = ProviderType.CUSTOM,
                    )
                    draftProviders[newKey] = newProvider
                    expanded[newKey] = true
                    rowOrder.add(newKey)
                },
            )

            Spacer(Modifier.height(8.dp))

            // ================================================== 高级设置
            AdvancedCard(
                config = config,
                onUpdate = { updated -> onSave(updated) },
                isDarkTheme = isDarkTheme,
            )

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
// 行 1：小爱同学（固定）
// ======================================================================

@Composable
private fun XIAOAIRow(provider: ProviderConfig, isActive: Boolean) {
    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "小爱同学",
                    style = MiuixTheme.textStyles.title3,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "小米原生，不拦截",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceContainerHigh,
                )
            }
            if (isActive) {
                Text(
                    text = "✓ 当前使用",
                    color = MiuixTheme.colorScheme.primary,
                    style = MiuixTheme.textStyles.footnote1,
                )
            } else {
                Text(
                    text = "切换",
                    color = MiuixTheme.colorScheme.onSurfaceContainerHigh,
                    style = MiuixTheme.textStyles.footnote1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

// ======================================================================
// 行 2+：可编辑的 provider 行
// ======================================================================

@Composable
private fun ProviderRow(
    provider: ProviderConfig,
    expanded: Boolean,
    isActive: Boolean,
    onNameChange: (String) -> Unit,
    onToggle: () -> Unit,
    onUpdate: (ProviderConfig) -> Unit,
    onDelete: () -> Unit,
    isDarkTheme: Boolean,
) {
    Card {
        Column(modifier = Modifier.fillMaxWidth()) {
            // ---------- 标题栏 ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    TextField(
                        value = provider.displayName.ifBlank { provider.providerType.displayName },
                        onValueChange = onNameChange,
                        singleLine = true,
                        label = "名称",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (isActive) {
                        Text(
                            text = "✓ 当前使用中",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 2.dp, start = 4.dp),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                if (expanded) {
                    IconButton(onClick = onToggle) {
                        Icon(imageVector = MiuixIcons.ExpandLess, contentDescription = "收起")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = MiuixIcons.Delete,
                            contentDescription = "删除",
                            tint = Color(0xFFE53935),
                        )
                    }
                } else {
                    IconButton(onClick = onToggle) {
                        Icon(imageVector = MiuixIcons.Settings, contentDescription = "设置")
                    }
                }
            }
            // ---------- 展开 ----------
            if (expanded) {
                HorizontalDivider()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ProviderTypeSelector(
                        current = provider.providerType,
                        onSelect = { newType ->
                            val updated = provider.copy(
                                providerType = newType,
                                baseUrl = provider.baseUrl.ifBlank { newType.defaultBaseUrl },
                                model = provider.model.ifBlank { newType.defaultModel },
                                displayName = provider.displayName.ifBlank { newType.displayName },
                            )
                            onUpdate(updated)
                        },
                    )

                    TextField(
                        value = provider.apiKey,
                        onValueChange = { onUpdate(provider.copy(apiKey = it)) },
                        label = "API Key",
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    TextField(
                        value = provider.baseUrl,
                        onValueChange = { onUpdate(provider.copy(baseUrl = it)) },
                        label = "Base URL",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "OpenAI 兼容端点，例如 https://api.deepseek.com/v1",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceContainerHigh,
                        modifier = Modifier.padding(start = 4.dp),
                    )

                    TextField(
                        value = provider.model,
                        onValueChange = { onUpdate(provider.copy(model = it)) },
                        label = "模型名",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "例如 deepseek-chat / glm-4-flash / gpt-4o-mini",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceContainerHigh,
                        modifier = Modifier.padding(start = 4.dp),
                    )

                    val ready = provider.apiKey.isNotBlank() &&
                        provider.baseUrl.isNotBlank() &&
                        provider.model.isNotBlank()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (ready) {
                            Text(
                                text = "可启用",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceContainerHigh,
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            Text(
                                text = "需要先填齐 API Key / Base URL / 模型名才能启用",
                                style = MiuixTheme.textStyles.footnote1,
                                color = if (isDarkTheme) Color(0xFFE0B400) else Color(0xFFB58900),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Switch(
                            checked = provider.enabled,
                            onCheckedChange = { onUpdate(provider.copy(enabled = it)) },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (provider.enabled) "已启用" else "已停用",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceContainerHigh,
                        )
                    }
                }
            }
        }
    }
}

// ======================================================================
// 添加按钮行（永远在最后一行）
// ======================================================================

@Composable
private fun AddProviderRow(onAdd: () -> Unit) {
    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            IconButton(onClick = onAdd) {
                Icon(
                    imageVector = MiuixIcons.Add,
                    contentDescription = "添加提供方",
                    tint = MiuixTheme.colorScheme.primary,
                )
            }
            Text(
                text = "添加",
                color = MiuixTheme.colorScheme.primary,
                style = MiuixTheme.textStyles.button,
                modifier = Modifier.padding(start = 4.dp, end = 8.dp),
            )
        }
    }
}

// ======================================================================
// 提供商类型选择 —— 点击展开式下拉（v0.1.0-beta6）
// ======================================================================

/**
 * 规格（用户原话）：「我要的选择模型是下拉菜单」。
 *
 * 设计：Miuix 提供的 `Dropdown` 在 LSPosed 模块的 Compose 子树里依赖 PopupWindow，
 * 而 Popup 链路会触发宿主 Resources$NotFoundException（见 SettingsWindowController
 * 关于 ContextWrapper 的修复说明）。所以这里**手写一个轻量下拉**：
 *  - 第一行：当前选中的类型（ChevronDown 图标 + 描述文字）
 *  - 点击第一行 → 展开一组 BasicComponent 风格的选项
 *  - 选中某项 → 自动收起，调用 [onSelect]
 *
 * 这样避开了任何 Popup/Dropdown 组件依赖，且视觉上仍然是"下拉菜单"的形态。
 */
@Composable
private fun ProviderTypeSelector(
    current: ProviderType,
    onSelect: (ProviderType) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val options = remember { ProviderType.entries.filter { it != ProviderType.XIAOAI } }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "提供商类型",
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceContainerHigh,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
        Card {
            Column(modifier = Modifier.fillMaxWidth()) {
                // ---------- 当前选中行（点击展开 / 收起） ----------
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = current.displayName, style = MiuixTheme.textStyles.body1)
                        if (current.defaultBaseUrl.isNotBlank()) {
                            Text(
                                text = "${current.defaultBaseUrl} · ${current.defaultModel}",
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceContainerHigh,
                            )
                        }
                    }
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            imageVector = if (expanded) MiuixIcons.ExpandLess else MiuixIcons.Settings,
                            contentDescription = if (expanded) "收起" else "展开",
                        )
                    }
                }
                // ---------- 展开后的选项列表 ----------
                if (expanded) {
                    HorizontalDivider()
                    options.forEachIndexed { i, type ->
                        if (i > 0) HorizontalDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = type.displayName, style = MiuixTheme.textStyles.body1)
                                if (type.defaultBaseUrl.isNotBlank()) {
                                    Text(
                                        text = "${type.defaultBaseUrl} · ${type.defaultModel}",
                                        style = MiuixTheme.textStyles.footnote2,
                                        color = MiuixTheme.colorScheme.onSurfaceContainerHigh,
                                    )
                                }
                            }
                            if (type == current) {
                                Text(
                                    text = "✓ 当前",
                                    color = MiuixTheme.colorScheme.primary,
                                    style = MiuixTheme.textStyles.footnote1,
                                )
                            } else {
                                TextButton(
                                    text = "选择",
                                    onClick = {
                                        onSelect(type)
                                        expanded = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ======================================================================
// 高级设置
// ======================================================================

@Composable
private fun AdvancedCard(
    config: AiConfig,
    onUpdate: (AiConfig) -> Unit,
    isDarkTheme: Boolean,
) {
    Card {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "高级",
                style = MiuixTheme.textStyles.title2,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
            )
            SwitchRow(
                title = "启用语音切换模型",
                summary = "对手环说「切换模型」会直接回复模型列表。",
                checked = config.enableVoiceSwitch,
                onCheckedChange = { onUpdate(config.copy(enableVoiceSwitch = it)) },
            )
            HorizontalDivider()
            SwitchRow(
                title = "流式回答也替换",
                summary = "新版小爱流式回答（ToastStream）同样会被替换。",
                checked = config.hookToastStream,
                onCheckedChange = { onUpdate(config.copy(hookToastStream = it)) },
            )
            HorizontalDivider()
            SwitchRow(
                title = "失败时放行原始回答",
                summary = "超时或报错时保留小爱的原始回答而不是卡住。",
                checked = config.fallbackToOriginal,
                onCheckedChange = { onUpdate(config.copy(fallbackToOriginal = it)) },
            )
            HorizontalDivider()
            SwitchRow(
                title = "在「设置」页面显示入口",
                summary = "在小米运动健康的「设置」列表里插入入口。",
                checked = config.injectMineEntry,
                onCheckedChange = { onUpdate(config.copy(injectMineEntry = it)) },
            )
            HorizontalDivider()
            SwitchRow(
                title = "详细日志",
                summary = "输出调试日志。",
                checked = config.verboseLog,
                onCheckedChange = { onUpdate(config.copy(verboseLog = it)) },
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MiuixTheme.textStyles.body1)
            Text(
                text = summary,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceContainerHigh,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}