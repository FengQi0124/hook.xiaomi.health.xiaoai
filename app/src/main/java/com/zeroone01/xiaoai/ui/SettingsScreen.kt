package com.zeroone01.xiaoai.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.zeroone01.xiaoai.core.AiConfig
import com.zeroone01.xiaoai.core.ProviderConfig
import com.zeroone01.xiaoai.core.ProviderType
import java.util.UUID

/**
 * 设置页主界面（v0.1.0-beta7：独立 Activity + 行内操作版）。
 *
 * ## 交互规范（逐条来自用户反馈）
 *  - **添加 = 下拉菜单**：点「添加」展开提供商类型选择列表（DeepSeek/智谱/OpenAI/
 *    月之暗面/自定义），选中才建行并预填模板值 —— 不再一上来就多一行空白。
 *  - **行内保存**：每行展开区底部「保存」，点击立即落盘并广播给手环端，收起表单。
 *    屏幕右上角的全局保存已删除（用户：谁注意得到？）。
 *  - **删除 = 确认即保存**：点删除弹确认框，确认后立即落盘，不需要再去点保存。
 *  - **排序**：每行有 ↑ ↓ 按钮，调整顺序立即生效。
 *  - **编辑**：每行右侧"编辑"展开表单（名称/类型下拉/API Key/Base URL/模型名/启用）。
 *  - **切换模型**：点行主体立即切换为当前使用（高亮 ✓）。
 *  - **高级开关**：改动即保存。
 *  - **动画**：行展开/收起用 animateContentSize 过渡。
 *  - **返回**：顶栏 ← = Activity.finish（回宿主上一页）；诊断子页由 BackHandler 接管。
 */
@Composable
fun SettingsScreen(
    config: AiConfig,
    onSave: (AiConfig) -> Unit,
    onClose: () -> Unit,
    onOpenDiagnostics: () -> Unit = {},
    isDarkTheme: Boolean = false,
) {
    // ---- 编辑草稿（未点"保存"前的临时值）----
    val drafts = remember { mutableStateMapOf<String, ProviderConfig>() }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val adding = remember { mutableStateOf(false) }
    val deleteTarget = remember { mutableStateOf<String?>(null) }

    // ---- 顺序（磁盘顺序为准；上移/下移操作此列表后立即保存）----
    val rowOrder = remember(config.providers) {
        mutableStateListOf<String>().apply { addAll(config.providers.map { it.key }) }
    }

    fun saveProviders(newProviders: List<ProviderConfig>, newOrder: List<String> = rowOrder.toList()) {
        val ordered = newOrder.mapNotNull { key -> newProviders.firstOrNull { it.key == key } }
            .let { list ->
                // 未进 order 的新行追加在尾部
                list + newProviders.filter { p -> list.none { it.key == p.key } }
            }
        // 小爱行永远排第一
        val fixed = ordered.sortedBy { if (it.isXiaoAi) 0 else 1 }
        onSave(config.copy(providers = fixed))
    }

    fun draftOf(p: ProviderConfig): ProviderConfig = drafts[p.key] ?: p

    fun updateDraft(p: ProviderConfig) {
        drafts[p.key] = p
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isDarkTheme) Color(0xFF101013) else Color(0xFFF7F7F8))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            // ======================================================== 顶栏（← 标题）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "←",
                    fontSize = 24.sp,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { onClose() }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
                Text(
                    text = "AI 助手设置",
                    style = MiuixTheme.textStyles.title2,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "把「小爱同学」换成你自己的 AI，每行改完点「保存」立即生效。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (isDarkTheme) Color(0xFFB0B3B5) else Color(0xFF606060),
                    modifier = Modifier.padding(start = 4.dp),
                )

                // ================================================ 行 1：小爱同学
                val xiaoAi = config.providers.firstOrNull { it.isXiaoAi }
                if (xiaoAi != null) {
                    Card {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSave(config.copy(activeModelKey = xiaoAi.key)) }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("小爱同学", style = MiuixTheme.textStyles.title3)
                                Text(
                                    "小米原生，不拦截",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceContainerHigh,
                                )
                            }
                            if (config.activeModelKey == xiaoAi.key) {
                                Text(
                                    "✓ 使用中",
                                    color = MiuixTheme.colorScheme.primary,
                                    style = MiuixTheme.textStyles.footnote1,
                                )
                            } else {
                                Text(
                                    "点击使用",
                                    color = MiuixTheme.colorScheme.primary,
                                    style = MiuixTheme.textStyles.footnote1,
                                )
                            }
                        }
                    }
                }

                // ================================================ 第三方 provider 行
                rowOrder.forEach { key ->
                    val saved = config.providers.firstOrNull { it.key == key } ?: return@forEach
                    if (saved.isXiaoAi) return@forEach
                    val p = draftOf(saved)
                    val xiaoAiExists = config.providers.any { it.isXiaoAi }
                    ProviderRow(
                        saved = saved,
                        draft = p,
                        expanded = expanded[key] == true,
                        isActive = config.activeModelKey == key,
                        isFirst = rowOrder.indexOf(key) <= (if (xiaoAiExists) 1 else 0),
                        isLast = key == rowOrder.lastOrNull(),
                        onToggleExpand = { expanded[key] = !(expanded[key] ?: false) },
                        onDraftChange = { updateDraft(it) },
                        onSaveRow = {
                            // 行内保存：草稿合入 config → 立即落盘（onSave 内部会广播）
                            val merged = config.providers.map { if (it.key == key) p else it }
                            saveProviders(merged)
                            drafts.remove(key)
                            expanded[key] = false
                        },
                        onDelete = { deleteTarget.value = key },
                        onMoveUp = {
                            val idx = rowOrder.indexOf(key)
                            if (idx > 1) {
                                rowOrder.removeAt(idx)
                                rowOrder.add(idx - 1, key)
                                saveProviders(config.providers)
                            }
                        },
                        onMoveDown = {
                            val idx = rowOrder.indexOf(key)
                            if (idx in 1 until rowOrder.lastIndex) {
                                rowOrder.removeAt(idx)
                                rowOrder.add(idx + 1, key)
                                saveProviders(config.providers)
                            }
                        },
                        onSetActive = { onSave(config.copy(activeModelKey = key)) },
                        onEnabledChange = { enabled ->
                            val merged = config.providers.map {
                                if (it.key == key) p.copy(enabled = enabled) else it
                            }
                            saveProviders(merged)
                        },
                        isDarkTheme = isDarkTheme,
                    )
                }

                // ================================================ 添加（下拉菜单）
                AddProviderCard(
                    expanded = adding.value,
                    onToggle = { adding.value = !adding.value },
                    onSelectType = { type ->
                        adding.value = false
                        val newKey = "u-" + UUID.randomUUID().toString().take(8)
                        val np = ProviderConfig(
                            key = newKey,
                            displayName = type.displayName,
                            providerType = type,
                            baseUrl = type.defaultBaseUrl,
                            model = type.defaultModel,
                            enabled = false,
                        )
                        drafts[newKey] = np
                        // 新行进磁盘（占位，enabled=false 不影响拦截），并展开编辑
                        saveProviders(config.providers + np, rowOrder.toList() + newKey)
                        rowOrder.add(newKey)
                        expanded[newKey] = true
                    },
                )

                Spacer(Modifier.height(8.dp))

                // ================================================ 高级设置（开关即保存）
                AdvancedCard(
                    config = config,
                    onUpdate = { onSave(it) },
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

        // ================================================ 删除确认框（自绘，确认即保存）
        deleteTarget.value?.let { target ->
            ConfirmDialog(
                title = "删除这个提供方？",
                message = "「${config.providers.firstOrNull { it.key == target }?.displayName ?: target}」将被移除并立即保存。",
                confirmText = "删除",
                onConfirm = {
                    deleteTarget.value = null
                    drafts.remove(target)
                    expanded.remove(target)
                    val idx = rowOrder.indexOf(target)
                    if (idx >= 0) rowOrder.removeAt(idx)
                    saveProviders(config.providers.filter { it.key != target })
                },
                onDismiss = { deleteTarget.value = null },
                isDarkTheme = isDarkTheme,
            )
        }
    }
}

// ======================================================================
// 第三方 provider 行
// ======================================================================

@Composable
private fun ProviderRow(
    saved: ProviderConfig,
    draft: ProviderConfig,
    expanded: Boolean,
    isActive: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    onToggleExpand: () -> Unit,
    onDraftChange: (ProviderConfig) -> Unit,
    onSaveRow: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onSetActive: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    isDarkTheme: Boolean,
) {
    Card(modifier = Modifier.animateContentSize()) {
        // ---------- 折叠标题栏 ----------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSetActive() }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = draft.displayName.ifBlank { draft.providerType.displayName },
                    style = MiuixTheme.textStyles.title3,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = buildString {
                        append(draft.providerType.displayName)
                        if (draft.model.isNotBlank()) append(" · ${draft.model}")
                        if (!saved.enabled) append("（已停用）")
                        // 有未保存草稿时提示
                        if (draft != saved) append(" · 有未保存修改")
                    },
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceContainerHigh,
                )
            }
            if (isActive) {
                Text(
                    "✓ 使用中",
                    color = MiuixTheme.colorScheme.primary,
                    style = MiuixTheme.textStyles.footnote1,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Text(
                text = if (expanded) "收起" else "编辑",
                color = MiuixTheme.colorScheme.primary,
                style = MiuixTheme.textStyles.footnote1,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onToggleExpand() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }

        // ---------- 展开编辑区 ----------
        if (expanded) {
            HorizontalDivider()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 名称
                TextField(
                    value = draft.displayName,
                    onValueChange = { onDraftChange(draft.copy(displayName = it)) },
                    label = "名称",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // 提供商类型（下拉菜单）
                ProviderTypeDropdown(
                    current = draft.providerType,
                    onSelect = { type ->
                        onDraftChange(
                            draft.copy(
                                providerType = type,
                                baseUrl = type.defaultBaseUrl,
                                model = type.defaultModel,
                            )
                        )
                    },
                )

                // API Key
                TextField(
                    value = draft.apiKey,
                    onValueChange = { onDraftChange(draft.copy(apiKey = it)) },
                    label = "API Key",
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )

                // Base URL
                TextField(
                    value = draft.baseUrl,
                    onValueChange = { onDraftChange(draft.copy(baseUrl = it)) },
                    label = "Base URL",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // 模型名
                TextField(
                    value = draft.model,
                    onValueChange = { onDraftChange(draft.copy(model = it)) },
                    label = "模型名",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // 启用开关（直接生效）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("启用", style = MiuixTheme.textStyles.body1)
                        Text(
                            "停用的行不出现在手环切换菜单里",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceContainerHigh,
                        )
                    }
                    Switch(checked = saved.enabled, onCheckedChange = onEnabledChange)
                }

                HorizontalDivider()

                // ---------- 操作区：排序 / 删除 / 保存 ----------
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "↑",
                        fontSize = 20.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = !isFirst) { onMoveUp() }
                            .background(
                                if (isDarkTheme) Color(0xFF2A2A2E) else Color(0xFFF0F0F2)
                            )
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        color = if (isFirst) Color.Gray else MiuixTheme.colorScheme.onSurface,
                    )
                    Text(
                        "↓",
                        fontSize = 20.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = !isLast) { onMoveDown() }
                            .background(
                                if (isDarkTheme) Color(0xFF2A2A2E) else Color(0xFFF0F0F2)
                            )
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        color = if (isLast) Color.Gray else MiuixTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "删除",
                        color = Color(0xFFE53935),
                        style = MiuixTheme.textStyles.body1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onDelete() }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                    TextButton(
                        text = "保存",
                        onClick = onSaveRow,
                        modifier = Modifier.weight(0.35f),
                    )
                }
            }
        }
    }
}

// ======================================================================
// 提供商类型下拉菜单
// ======================================================================

/**
 * 「下拉菜单」选择器（用户三次强调的交互）。
 *
 * 收起态 = 一行当前值（+ 右侧 ∨）；点击展开选项列表（animateContentSize 动画），
 * 点选即更新并收起。不依赖 Popup/DropdownMenu 组件 —— 那条链在宿主进程会踩
 * Resources$NotFoundException（beta6 教训）；独立 Activity 下虽然资源没问题，
 * 但自实现下拉的行为与 HyperOS 设置项一致、且零依赖风险。
 */
@Composable
private fun ProviderTypeDropdown(
    current: ProviderType,
    onSelect: (ProviderType) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val options = remember { ProviderType.entries.filter { it != ProviderType.XIAOAI } }

    Column(modifier = Modifier.fillMaxWidth()) {
        // 当前值行（点击展开/收起）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MiuixTheme.colorScheme.surface)
                .clickable { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "提供商类型",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceContainerHigh,
                )
                Text(current.displayName, style = MiuixTheme.textStyles.body1)
            }
            Text(
                text = if (expanded) "∧" else "∨",
                fontSize = 16.sp,
                color = MiuixTheme.colorScheme.onSurfaceContainerHigh,
            )
        }
        // 选项列表（展开动画）
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MiuixTheme.colorScheme.surface)
                    .animateContentSize(),
            ) {
                options.forEachIndexed { i, type ->
                    if (i > 0) HorizontalDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(type)
                                expanded = false
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(type.displayName, style = MiuixTheme.textStyles.body1)
                            if (type.defaultBaseUrl.isNotBlank()) {
                                Text(
                                    "${type.defaultBaseUrl} · ${type.defaultModel}",
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onSurfaceContainerHigh,
                                )
                            }
                        }
                        if (type == current) {
                            Text(
                                "✓",
                                color = MiuixTheme.colorScheme.primary,
                                style = MiuixTheme.textStyles.body1,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ======================================================================
// 「添加」卡片：点开 = 提供商类型下拉
// ======================================================================

@Composable
private fun AddProviderCard(
    expanded: Boolean,
    onToggle: () -> Unit,
    onSelectType: (ProviderType) -> Unit,
) {
    Card(modifier = Modifier.animateContentSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = if (expanded) "∧ 收起选项" else "＋ 添加提供方",
                color = MiuixTheme.colorScheme.primary,
                style = MiuixTheme.textStyles.button,
            )
        }
        if (expanded) {
            HorizontalDivider()
            Column(modifier = Modifier.fillMaxWidth()) {
                ProviderType.entries.filter { it != ProviderType.XIAOAI }.forEachIndexed { i, type ->
                    if (i > 0) HorizontalDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectType(type) }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(type.displayName, style = MiuixTheme.textStyles.body1)
                            if (type.defaultBaseUrl.isNotBlank()) {
                                Text(
                                    "${type.defaultBaseUrl} · ${type.defaultModel}",
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onSurfaceContainerHigh,
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
// 确认对话框（自绘遮罩 + 卡片；确认即执行）
// ======================================================================

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    isDarkTheme: Boolean,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier
                .padding(horizontal = 32.dp)
                .clickable(enabled = false) {},
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(title, style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.SemiBold)
                Text(
                    message,
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurfaceContainerHigh,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TextButton(
                        text = "取消",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = confirmText,
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

// ======================================================================
// 高级设置（开关改动即保存）
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
