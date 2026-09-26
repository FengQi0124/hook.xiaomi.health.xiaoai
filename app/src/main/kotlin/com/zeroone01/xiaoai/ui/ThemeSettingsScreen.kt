package com.zeroone01.xiaoai.ui

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zeroone01.xiaoai.config.ConfigStore
import com.zeroone01.xiaoai.ui.VisualPrefs
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Background
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.icon.extended.CloudFill
import top.yukonga.miuix.kmp.icon.extended.Sidebar
import top.yukonga.miuix.kmp.icon.extended.ZoomOut
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 独立主题设置页面 —— 一比一复刻 KernelSU ColorPaletteScreenMiuix。
 *
 * 功能：
 * 1. ThemePreviewCard 实时预览
 * 2. TabRow 主题模式（跟随系统/浅色/深色）
 * 3. 视觉效果卡：Blur/悬浮底部栏/玻璃效果/页面缩放（预测性返回开关已删）
 *
 * 第三轮精简：莫奈取色（动态取色/种子色/调色板风格/色彩规范）与导航角标开关已删除。
 */
@Composable
fun ThemeSettingsScreen(
    config: ConfigStore,
    onBack: () -> Unit,
    onThemeModeChange: (String) -> Unit,
    onVisualPrefsChange: (VisualPrefs) -> Unit = {},
) {
    val monet = config.isMiuixMonet()
    // 当前基础模式索引（0=System, 1=Light, 2=Dark），忽略 Monet 偏移
    val rawMode = config.getThemeMode().lowercase()
    val baseIndex = when {
        rawMode in listOf("light", "monetlight") -> 1
        rawMode in listOf("dark", "monetdark") -> 2
        else -> 0
    }
    // 本地响应式状态：视觉效果开关（初始值从 config 读取，切换时上报 onVisualPrefsChange 驱动主界面重组）
    var prefs by remember {
        mutableStateOf(
            VisualPrefs(
                enableBlur = config.isEnableBlur(),
                enableFloatingBar = config.isFloatingBottomBar(),
                enableFloatingBarBlur = config.isFloatingBottomBarBlur(),
                enableNavigationBadge = config.isEnableNavigationBadge(),
                pageScale = config.getPageScale(),
            )
        )
    }
    val enableBlur = prefs.enableBlur
    val enableFloatingBar = prefs.enableFloatingBar
    val enableFloatingBarBlur = prefs.enableFloatingBarBlur

    // 更新视觉效果：更新本地状态 + 持久化 + 上报主界面
    fun updateVisual(transform: VisualPrefs.() -> VisualPrefs) {
        prefs = transform(prefs)
        onVisualPrefsChange(prefs)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "设置主题",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = "返回",
                            tint = MiuixTheme.colorScheme.onBackground,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            contentPadding = innerPadding,
        ) {
            // ---------- ThemePreviewCard 实时预览 ----------
            item(key = "preview") {
                Spacer(modifier = Modifier.height(32.dp))
                ThemePreviewCard(
                    monet = monet,
                    keyColor = config.getKeyColor(),
                )
                Spacer(modifier = Modifier.height(72.dp))
            }

            // ---------- TabRow 主题模式 ----------
            item(key = "modeTab") {
                TabRow(
                    tabs = listOf("跟随系统", "浅色模式", "深色模式"),
                    selectedTabIndex = baseIndex,
                    onTabSelected = { index ->
                        // 存 base 值（不含 Monet 偏移；莫奈取色 UI 已删除，isMiuixMonet 恒 false）
                        val base = when (index) {
                            1 -> "light"
                            2 -> "dark"
                            else -> "system"
                        }
                        onThemeModeChange(base)
                    },
                )
            }

            // ---------- 视觉效果 ----------
            item(key = "visualCard") {
                Card(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth(),
                ) {
                    // 模糊效果（SDK 33+）
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        SwitchPreference(
                            title = "模糊效果",
                            summary = "顶部栏与底部栏背景模糊",
                            startAction = {
                                Icon(
                                    MiuixIcons.Background,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = "模糊效果",
                                )
                            },
                            checked = enableBlur,
                            onCheckedChange = { updateVisual { copy(enableBlur = it) } },
                        )
                    }

                    // 悬浮底部栏
                    SwitchPreference(
                        title = "悬浮底部栏",
                        summary = "iOS 风格悬浮胶囊底部导航栏",
                        startAction = {
                            Icon(
                                MiuixIcons.Sidebar,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = "悬浮底部栏",
                                tint = MiuixTheme.colorScheme.onBackground,
                            )
                        },
                        checked = enableFloatingBar,
                        onCheckedChange = { updateVisual { copy(enableFloatingBar = it) } },
                    )

                    // 玻璃效果（条件展开：悬浮底部栏开启 && SDK 33+）
                    AnimatedVisibility(visible = enableFloatingBar && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        SwitchPreference(
                            title = "玻璃效果",
                            summary = "悬浮底部栏液滴玻璃模糊效果",
                            startAction = {
                                Icon(
                                    MiuixIcons.CloudFill,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = "玻璃效果",
                                    tint = MiuixTheme.colorScheme.onBackground,
                                )
                            },
                            checked = enableFloatingBarBlur,
                            onCheckedChange = { updateVisual { copy(enableFloatingBarBlur = it) } },
                        )
                    }

                    // 导航角标开关与「首页」角标点已一并删除（0.5.0-beta 第三轮）：
                    // NavigationBar 不再渲染 Badge，保留开关会形成死 UI。
                    // 预测性返回开关已删除：Manifest 不再声明 enableOnBackInvokedCallback，
                    // 反射隐藏 API 的切换逻辑一并移除，返回手势走系统默认行为。

                    // 页面缩放
                    var showScaleSlider by remember { mutableStateOf(false) }
                    var sliderValue by remember(config.getPageScale()) { mutableFloatStateOf(config.getPageScale()) }
                    ArrowPreference(
                        title = "页面缩放",
                        summary = "调整界面显示密度",
                        startAction = {
                            Icon(
                                MiuixIcons.ZoomOut,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = "页面缩放",
                            )
                        },
                        endActions = {
                            Text(
                                text = "${(sliderValue * 100).toInt()}%",
                                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                            )
                        },
                        holdDownState = showScaleSlider,
                        onClick = { showScaleSlider = !showScaleSlider },
                        bottomAction = {
                            Slider(
                                value = sliderValue,
                                onValueChange = { sliderValue = it },
                                onValueChangeFinished = { updateVisual { copy(pageScale = sliderValue) } },
                                valueRange = 0.8f..1.1f,
                                showKeyPoints = true,
                                keyPoints = listOf(0.8f, 0.9f, 1f, 1.1f),
                                magnetThreshold = 0.01f,
                                hapticEffect = SliderDefaults.SliderHapticEffect.Step,
                            )
                        },
                    )
                }
            }

            item(key = "bottomSpacer") {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

/**
 * 实时预览卡片 —— 使用 MiuixTheme 当前配色模拟手机界面效果。
 * 参考 KernelSU ThemePreviewCardMiuix 布局（缩略手机 + 顶部栏 + 强调卡片 + 底部导航）。
 */
@Composable
private fun ThemePreviewCard(
    monet: Boolean,
    keyColor: Long,
) {
    val bgColor = MiuixTheme.colorScheme.surface
    val textColor = MiuixTheme.colorScheme.onSurface
    val accentCardColor = MiuixTheme.colorScheme.secondaryContainer
    val cardColor = MiuixTheme.colorScheme.surfaceContainerHighest
    val navBarColor = MiuixTheme.colorScheme.surfaceContainer
    val iconColor = MiuixTheme.colorScheme.primary

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.4f)
                .aspectRatio(0.46f)
                .clip(RoundedCornerShape(20.dp))
                .background(bgColor)
                .border(1.dp, MiuixTheme.colorScheme.outline, RoundedCornerShape(20.dp)),
        ) {
            Column {
                // 顶部栏
                Row(
                    modifier = Modifier
                        .height(48.dp)
                        .fillMaxWidth()
                        .padding(start = 12.dp, top = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "手环小爱AI",
                        fontSize = 12.sp,
                        color = textColor,
                    )
                }

                // 强调卡片
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(45.dp)
                        .padding(horizontal = 8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(accentCardColor),
                )

                // 内容卡片
                Box(modifier = Modifier.weight(1f)) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(cardColor),
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(12.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(cardColor),
                        )
                    }
                }

                // 底部导航栏
                Column(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(MiuixTheme.colorScheme.dividerLine),
                    )
                    Row(
                        modifier = Modifier
                            .height(36.dp)
                            .fillMaxWidth()
                            .background(navBarColor)
                            .padding(top = 2.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        repeat(4) {
                            Box(
                                modifier = Modifier
                                    .size(15.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(if (it == 0) iconColor else textColor.copy(alpha = 0.5f)),
                            )
                        }
                    }
                }
            }
        }
    }
}