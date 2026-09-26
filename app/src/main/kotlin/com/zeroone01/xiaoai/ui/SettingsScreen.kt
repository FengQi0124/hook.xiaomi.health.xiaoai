@file:OptIn(ExperimentalScrollBarApi::class)

package com.zeroone01.xiaoai.ui

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.libxposed.service.XposedService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.zeroone01.xiaoai.BuildConfig
import com.zeroone01.xiaoai.LsposedBinding
import com.zeroone01.xiaoai.config.ConfigKeys
import com.zeroone01.xiaoai.config.ConfigStore
import com.zeroone01.xiaoai.config.ModelEntry
import com.zeroone01.xiaoai.config.ModelPresets
import com.zeroone01.xiaoai.config.StatsStore
import com.zeroone01.xiaoai.hook.LlmClient
import com.zeroone01.xiaoai.log.LogCollector
import com.zeroone01.xiaoai.ui.VisualPrefs
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.Hide
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Show
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import com.zeroone01.xiaoai.ui.BlurredBar
import com.zeroone01.xiaoai.ui.LocalEnableBlur
import com.zeroone01.xiaoai.ui.LocalEnableFloatingBar
import com.zeroone01.xiaoai.ui.LocalEnableFloatingBarBlur
import com.zeroone01.xiaoai.ui.LocalPageScale
import com.zeroone01.xiaoai.ui.rememberBlurBackdrop
import com.zeroone01.xiaoai.ui.component.FloatingBottomBar
import com.zeroone01.xiaoai.ui.component.FloatingBottomBarItem

/**
 * 手环小爱AI —— 完整设置页（参考 KernelSU Manager 设计风格）。
 *
 * 3 个 Tab 通过 HorizontalPager 左右滑动切换：
 * 0=首页 1=配置 2=记录
 *
 * @param binding LSPosed Service 绑定信息；为 null 表示未检测到框架，显示提示
 * @param pagerState Pager 状态（由外层提升注入：主题切换动画会重建本页面组合，
 *                   状态提升到 AnimatedContent 之外以保持 Tab 位置存活）
 * @param showThemePage 是否显示主题设置二级页（同样提升注入，避免切换主题后被踢回主页）
 * @param onOpenThemePage 打开主题设置页
 * @param onCloseThemePage 关闭主题设置页
 * @param onThemeModeChange 主题模式变更回调（持久化 + 驱动 AppTheme 重建）
 */
@Composable
fun SettingsScreen(
    binding: LsposedBinding?,
    pagerState: PagerState,
    showThemePage: Boolean,
    onOpenThemePage: () -> Unit,
    onCloseThemePage: () -> Unit,
    onThemeModeChange: (String) -> Unit = {},
    onVisualPrefsChange: (VisualPrefs) -> Unit = {},
) {
    val context = LocalContext.current
    StatsStore.init(context)
    val scope = rememberCoroutineScope()

    val config = binding?.config

    data class TabInfo(val label: String, val icon: ImageVector)
    val tabs = listOf(
        TabInfo("首页", MiuixIcons.Home),
        TabInfo("配置", MiuixIcons.Settings),
        TabInfo("记录", MiuixIcons.Info),
    )

    // 主题设置页时拦截系统返回：回到主设置页而非直接退出
    BackHandler(enabled = showThemePage && config != null) {
        onCloseThemePage()
    }

    // 主题设置页需要 config 才能操作
    if (showThemePage && config != null) {
        ThemeSettingsScreen(
            config = config,
            onBack = onCloseThemePage,
            onThemeModeChange = onThemeModeChange,
            onVisualPrefsChange = onVisualPrefsChange,
        )
        return
    }

    // 视觉效果（从 CompositionLocal 读取，由 SettingsActivity 提供）
    val enableBlur = LocalEnableBlur.current
    val enableFloatingBar = LocalEnableFloatingBar.current
    val enableFloatingBarBlur = LocalEnableFloatingBarBlur.current
    val pageScale = LocalPageScale.current
    // 顶部栏模糊 backdrop（参考 KernelSU：blur 开启且支持时非空）
    val blurBackdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = blurBackdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface
    // 悬浮底栏玻璃效果 backdrop（参考 KernelSU：始终创建，pager 内容挂在上面）
    val floatingSurface = MiuixTheme.colorScheme.surface
    val floatingBackdrop = rememberLayerBackdrop {
        drawRect(floatingSurface)
        drawContent()
    }

    Scaffold(
        topBar = {
            if (blurActive) {
                BlurredBar(blurBackdrop) {
                    SmallTopAppBar(title = "手环小爱 AI 增强", color = barColor)
                }
            } else {
                SmallTopAppBar(title = "手环小爱 AI 增强")
            }
        },
        bottomBar = {
            if (enableFloatingBar) {
                // 悬浮胶囊底部导航栏（居中显示，参考 KernelSU FloatingBottomBar）
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    FloatingBottomBar(
                        modifier = Modifier.padding(bottom = 12.dp),
                        selectedIndex = { pagerState.currentPage },
                        onSelected = { scope.launch { pagerState.animateScrollToPage(it) } },
                        backdrop = floatingBackdrop,
                        tabsCount = tabs.size,
                        isBlurEnabled = enableFloatingBarBlur,
                    ) {
                        tabs.forEachIndexed { i, tab ->
                            FloatingBottomBarItem(
                                onClick = { scope.launch { pagerState.animateScrollToPage(i) } },
                                modifier = Modifier.defaultMinSize(minWidth = 76.dp),
                            ) {
                                Icon(imageVector = tab.icon, contentDescription = tab.label)
                                Text(
                                    text = tab.label,
                                    fontSize = 11.sp,
                                    lineHeight = 14.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Visible,
                                )
                            }
                        }
                    }
                }
            } else {
                 NavigationBar {
                     tabs.forEachIndexed { i, tab ->
                         NavigationBarItem(
                             selected = pagerState.currentPage == i,
                             onClick = { scope.launch { pagerState.animateScrollToPage(i) } },
                             icon = tab.icon,
                             label = tab.label,
                         )
                     }
                 }
             }
        },
    ) { innerPadding ->
        val contentPadding = PaddingValues(
            top = innerPadding.calculateTopPadding() + 12.dp,
            bottom = innerPadding.calculateBottomPadding() + 12.dp,
        )

        // 页面缩放：包裹 Pager 内容
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    // 顶部栏模糊：把内容绘制到 blur backdrop 上，顶栏才能模糊到滚动内容
                    if (blurActive) Modifier.layerBackdrop(blurBackdrop!!) else Modifier
                )
                .graphicsLayer {
                    scaleX = pageScale
                    scaleY = pageScale
                },
        ) {
            // 悬浮底栏玻璃效果：把 Pager 内容绘制到浮动底栏 backdrop 上
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.then(
                    if (enableFloatingBar && enableFloatingBarBlur) {
                        Modifier.layerBackdrop(floatingBackdrop)
                    } else {
                        Modifier
                    }
                ),
            ) { page ->
                when (page) {
                    0 -> StatusTabContent(
                        binding = binding,
                        config = config,
                        context = context,
                        scope = scope,
                        contentPadding = contentPadding,
                        onOpenThemePage = onOpenThemePage,
                    )
                    1 -> ConfigTabContent(config = config, context = context, scope = scope, contentPadding = contentPadding)
                    2 -> StatsTabContent(context = context, scope = scope, contentPadding = contentPadding)
                }
            }
        }
    }
}

// ====================================================================
// Tab 0：首页 —— 工作状态 + 关于（日志/测试/版本/链接）+ 快捷操作
// ====================================================================

/**
 * 首页 —— 工作状态大卡片 + 原「关于」页内容（日志/测试/版本/链接/主题）+ 快捷操作。
 * 参考 KernelSU HomePager 的 StatusCard + InfoCard 设计风格。
 */
@Composable
private fun StatusTabContent(
    binding: LsposedBinding?,
    config: ConfigStore?,
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    contentPadding: PaddingValues,
    onOpenThemePage: () -> Unit,
) {
    val listState = rememberLazyListState()
    // 协程作用域（与配置读取的局部变量区分命名）
    val uiScope = rememberCoroutineScope()
    // 是否正在执行 Root 重启
    var restarting by remember { mutableStateOf(false) }
    // 重启二次确认弹窗
    var showRestartDialog by remember { mutableStateOf(false) }

    // 模块版本 / 编译日期（「关于」信息，随 BuildConfig 生成）
    val versionName = BuildConfig.VERSION_NAME
    val buildTime = BuildConfig.BUILD_TIME

    // 是否已激活（Service 绑定成功）
    val activated = binding != null

    // 大卡片配色（恢复原经典配色，与动态取色无关）：未激活浅红，激活浅绿
    val cardBg = if (!activated) Color(0xFFF8D7DA) else Color(0xFFDFFAE4)
    val cardFg = if (!activated) Color(0xFF8B1A1A) else Color(0xFF1A3825)

    Box {
        LazyColumn(state = listState, contentPadding = contentPadding) {
            // ---------- 主状态卡片（0.5.0-beta 第三轮：去掉大图标与左下角重复标签） ----------
            item(key = "statusCard") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    colors = CardDefaults.defaultColors(color = cardBg),
                    onClick = {
                        // 点击卡片尝试打开 LSPosed 管理器
                        runCatching {
                            val intent = context.packageManager.getLaunchIntentForPackage("org.lsposed.manager")
                            if (intent != null) context.startActivity(intent)
                        }
                    },
                    showIndication = true,
                    pressFeedbackType = PressFeedbackType.Tilt,
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp, 14.dp)) {
                        Text(
                            text = if (activated) "工作中" else "LSPosed 未激活",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = cardFg,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (activated) "手环小爱 AI 增强 · v$versionName"
                            else "请在 LSPosed 管理器中启用本模块",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = cardFg,
                        )
                    }
                }
            }

            // ---------- 关于（原独立「关于」Tab 已删除，内容并入首页） ----------
            item(key = "aboutTitle") {
                Spacer(modifier = Modifier.height(8.dp))
                SmallTitle("关于")
            }
            item(key = "about") {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    ArrowPreference(
                        title = "项目地址",
                        summary = "github.com/FengQi0124/hook.xiaomi.health.xiaoai",
                        onClick = {
                            runCatching {
                                val intent = Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://github.com/FengQi0124/hook.xiaomi.health.xiaoai"),
                                )
                                context.startActivity(intent)
                            }
                        },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ArrowPreference(
                        title = "界面组件库",
                        summary = "Miuix · github.com/compose-miuix-ui/miuix",
                        onClick = {
                            runCatching {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/compose-miuix-ui/miuix"))
                                context.startActivity(intent)
                            }
                        },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ArrowPreference(
                        title = "设置主题",
                        summary = "主题模式 / 视觉效果 / 页面缩放",
                        onClick = onOpenThemePage,
                    )
                    Text(
                        text = "手环小爱 AI 增强 · 版本 $versionName\n编译日期：$buildTime",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            // ---------- 快速操作 ----------
            item(key = "quickTitle") {
                Spacer(modifier = Modifier.height(8.dp))
                SmallTitle("快速操作")
            }
            item(key = "quick") {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    // 「打开 LSPosed 管理器」快捷项已删除（0.5.0-beta 第三轮）：
                    // 保留主状态卡 onClick 打开 LSPosed 的入口，避免重复。
                    ArrowPreference(
                        title = "自启动设置",
                        summary = "为小米运动健康开启自启动权限，保证后台常驻",
                        onClick = {
                            openAutoStartSettings(context, TARGET_APP_PACKAGE)
                        },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ArrowPreference(
                        title = "省电策略设置",
                        summary = "设置小米运动健康的省电策略，避免后台被系统限制",
                        onClick = {
                            openBatteryOptimizationSettings(context, TARGET_APP_PACKAGE)
                        },
                    )
                }
            }

            // ---------- 重启目标应用（需 Root） ----------
            item(key = "restartTitle") {
                Spacer(modifier = Modifier.height(8.dp))
                SmallTitle("目标应用")
            }
            item(key = "restart") {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    ArrowPreference(
                        title = "重启小米运动健康",
                        summary = if (restarting) {
                            "正在以 Root 权限重启…"
                        } else {
                            "以 Root 权限强制重启 com.mi.health，使模块 Hook 立即生效"
                        },
                        enabled = !restarting,
                        onClick = {
                            // 二次确认后再执行 Root 重启
                            showRestartDialog = true
                        },
                    )
                }
            }

            // ---------- 日志导出（0.5.0-beta 第三轮：移至首页最底部，目标应用/重启之后） ----------
            item(key = "logTitle") {
                Spacer(modifier = Modifier.height(8.dp))
                SmallTitle("日志")
            }
            item(key = "log") {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    ArrowPreference(
                        title = "导出日志",
                        summary = "通过系统分享发送日志文件",
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                val file = LogCollector.exportLogFile()
                                withContext(Dispatchers.Main) {
                                    if (file != null) {
                                        // 通过 FileProvider 生成 content:// Uri，交给系统分享
                                        val uri = runCatching {
                                            FileProvider.getUriForFile(
                                                context,
                                                "${context.packageName}.fileprovider",
                                                file,
                                            )
                                        }.getOrNull()
                                        if (uri != null) {
                                            val share = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_STREAM, uri)
                                                putExtra(Intent.EXTRA_TEXT, "手环小爱AI 日志文件")
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            runCatching {
                                                context.startActivity(Intent.createChooser(share, "分享日志"))
                                            }.onFailure {
                                                Toast.makeText(context, "未找到可分享的应用", Toast.LENGTH_SHORT).show()
                                            }
                                        } else {
                                            Toast.makeText(context, "日志导出失败", Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        Toast.makeText(context, "日志导出失败", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                    )
                }
            }

            item(key = "bottomSpacer") {
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        VerticalScrollBar(
            adapter = rememberScrollBarAdapter(listState),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight(),
            trackPadding = contentPadding,
        )
    }

    // 重启二次确认对话框（StatusTabContent 位于 SettingsScreen 的 Scaffold 内，满足 Overlay 宿主要求）
    OverlayDialog(
        show = showRestartDialog,
        title = "重启小米运动健康",
        summary = "将以 Root 权限强制重启 com.mi.health，模块 Hook 会重新加载生效",
        onDismissRequest = { showRestartDialog = false },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(
                text = "取消",
                modifier = Modifier.weight(1f),
                onClick = { showRestartDialog = false },
            )
            TextButton(
                text = "确认重启",
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = {
                    showRestartDialog = false
                    restarting = true
                    uiScope.launch(Dispatchers.IO) {
                        val ok = restartAppWithRoot(TARGET_APP_PACKAGE)
                        withContext(Dispatchers.Main) {
                            restarting = false
                            Toast.makeText(
                                context,
                                if (ok) "已重启小米运动健康" else "重启失败：请检查 Root 授权",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                },
            )
        }
    }

    // 重启超级小爱二次确认对话框已随「手机端小爱」功能一并移除（0.5.0-beta）
}

/** 目标应用包名（重启/状态展示统一使用） */
private const val TARGET_APP_PACKAGE = "com.mi.health"

/**
 * 以 Root 权限强制重启目标应用（com.mi.health）。
 *
 * 通过 su 执行 am force-stop 强制停止目标应用，等待短暂时间后
 * 使用 monkey 重新拉起其 Launcher Activity，使模块 Hook 重新注入生效。
 * Root 不可用或授权被拒时返回 false。
 */
private fun restartAppWithRoot(packageName: String): Boolean {
    return try {
        // su -c 直接执行合并命令；失败（无 Root/授权被拒）时 exit code 非 0
        val process = ProcessBuilder("su", "-c", "am force-stop $packageName && sleep 1 && monkey -p $packageName -c android.intent.category.LAUNCHER 1")
            .redirectErrorStream(true)
            .start()
        // 读取输出，避免管道缓冲阻塞；最多等待 15s
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor(15, TimeUnit.SECONDS)
        // force-stop 成功 + monkey 注入事件成功才算重启完成
        process.exitValue() == 0 && output.contains("Events injected: 1")
    } catch (_: Throwable) {
        false
    }
}

/**
 * 跳转系统「自启动设置」。
 *
 * 优先尝试 MIUI/HyperOS 安全中心的自启动管理页（方便为指定应用开启自启动），
 * 无法解析时回退到系统应用详情页（多数系统在此页提供自启动入口）。
 * 全部失败时给出 Toast 提示，不抛出异常。
 */
private fun openAutoStartSettings(context: Context, packageName: String) {
    // MIUI/HyperOS 自启动管理（com.miui.securitycenter 的 AutoStart 管理 Activity）
    val miuiIntent = Intent("miui.intent.action.OP_AUTO_START")
    // 通用回退：系统应用详情页
    val detailsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:$packageName")
    }
    if (launchFirstAvailable(context, miuiIntent, detailsIntent)) return
    Toast.makeText(context, "未找到自启动设置入口", Toast.LENGTH_SHORT).show()
}

/**
 * 跳转系统「省电策略设置」。
 *
 * 优先尝试系统电池优化设置页，无法解析时回退到系统应用详情页，
 * 多数系统（含 MIUI/HyperOS）的应用详情页内置「省电策略/电池」入口。
 */
private fun openBatteryOptimizationSettings(context: Context, packageName: String) {
    // 通用电池优化设置（Android 系统设置，可选择忽略优化）
    val batteryIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    // 通用回退：系统应用详情页（含省电策略入口）
    val detailsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:$packageName")
    }
    if (launchFirstAvailable(context, batteryIntent, detailsIntent)) return
    Toast.makeText(context, "未找到省电策略设置入口", Toast.LENGTH_SHORT).show()
}

/**
 * 依次尝试启动 Intent，返回是否有一个成功启动。
 * 需要以 Activity 上下文启动，故加上 NEW_TASK 标志以防缺少 Activity 栈。
 */
private fun launchFirstAvailable(context: Context, vararg intents: Intent): Boolean {
    for (intent in intents) {
        try {
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            }
        } catch (_: Throwable) {
            // 尝试下一个候选
        }
    }
    return false
}

// ====================================================================
// Tab 1：配置 —— 基本设置 + 回答模式 + 生成参数 + 会话设置
// ====================================================================

/**
 * Tab 1：配置 —— 基本设置（API）+ 生成参数 + 会话设置，各分组含预设管理。
 */
@Composable
private fun ConfigTabContent(
    config: ConfigStore?,
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    contentPadding: PaddingValues,
) {
    // 未激活（无 Service）时显示占位提示
    if (config == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "LSPosed 未激活\n请在 LSPosed 管理器中启用本模块后\n配置 API 参数",
                textAlign = TextAlign.Center,
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        return
    }
    val listState = rememberLazyListState()
    Box {
        LazyColumn(
            state = listState,
            contentPadding = contentPadding,
        ) {
            // ---------- 分组 0：模型列表（0.5.0-beta 多模型：第1行小爱固定，其后为用户模型，到「添加模型」为止） ----------
            item(key = "modelTitle") {
                SmallTitle("模型列表")
            }
            item(key = "models") {
                ModelListSection(config = config, context = context, scope = scope)
            }

            // ---------- 分组 0b：其它（原「模型」卡底部两行拆出：测试连接 + 智能家居直通） ----------
            item(key = "miscTitle") {
                SmallTitle("其它")
            }
            item(key = "misc") {
                MiscSection(config = config, context = context, scope = scope)
            }

            // ---------- 分组 2：配置（0.6.0-beta1 真机反馈）----------
            // 原「回答模式」栏改名「配置」：切换模型提示词 + 系统提示词（后者从「生成参数」移来）；
            // 两条「模式持续时长」设置整体删除（0.7.2.1 连配置键/取值/到期回退一并移除）：
            // 语音切换后的模式长期生效，不再有「X 分钟后自动恢复」。
            item(key = "configTitle") {
                SmallTitle("配置")
            }
            item(key = "config") {
                var refreshTick by remember { mutableStateOf(0) }
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    key(refreshTick) {
                        // 默认回答模式下拉已移除：由「模型列表」分组的行选择取代
                        //（选第 1 行=小爱接管、选模型行=LLM 接管，两者同时写 default_mode）。
                        // 「切换到 LLM / 切换到小爱 / 查询当前模式」三组指令词与「拦截米家(General)」
                        // 开发中开关已删除（0.5.0-beta）：模式切换只走「切换模型」菜单，
                        // 米家/设备控制由 SmartHomeRules 词表直通原生链路（不再占位开发中）。
                        TextInputField(
                            initialValue = config.getCmdSwitchModel().joinToString("\n"),
                            label = "切换模型提示词",
                            singleLine = false,
                            placeholder = "每行一个，命中后手环进入选模型菜单",
                            onValueChange = { text ->
                                config.setCmdSwitchModel(
                                    text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
                                )
                            },
                        )
                        TextInputField(
                            initialValue = config.getSystemPromptRaw(),
                            label = "系统提示词",
                            singleLine = false,
                            placeholder = "如：你是{<ModelName>}，通过小米手环回答用户问题…（{<ModelName>} 发送前自动替换为模型昵称）",
                            onValueChange = { config.setSystemPrompt(it) },
                        )
                    }
                }
            }

            // ---------- 分组 3：生成参数 ----------
            item(key = "generationTitle") {
                SmallTitle("生成参数")
            }
            item(key = "generation") {
                var refreshTick by remember { mutableStateOf(0) }
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    key(refreshTick) {
                        // 温度 / Top P / Top K 输入框已删除（0.5.0-beta 第三轮精简）：
                        // ConfigStore.getTemperature/getTopP/getTopK 恒返回 null，
                        // LlmClient 不再发送这些参数，统一走 API 默认值。
                        // 思考模式 / 思考强度 / 思考专属超时与 Token 已全部删除（0.5.0-beta）：
                        // 实测有 bug，ConfigStore.isThinkingMode() 恒为 false，请求一律走普通生成路径。
                        // 「系统提示词」已移到上方「配置」栏（0.6.0-beta1）。
                        NumberInputField(
                            label = "超时时间（毫秒）",
                            initialValue = config.getTimeoutMs().toInt(),
                            onValueChange = { config.setTimeoutMs(it) },
                        )
                        NumberInputField(
                            label = "最大 Token",
                            initialValue = config.getMaxTokens(),
                            onValueChange = { config.setMaxTokens(it) },
                        )
                    }
                }
            }

            // ---------- 分组 3：会话设置 ----------
            item(key = "sessionTitle") {
                SmallTitle("会话设置")
            }
            item(key = "session") {
                var refreshTick by remember { mutableStateOf(0) }
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    key(refreshTick) {
                        // 受控组件用本地状态驱动 UI，避免 RemotePreferences 非响应式导致界面不更新
                        var contextModeIndex by remember {
                            mutableStateOf(if (config.getContextMode().trim().lowercase() == "independent") 1 else 0)
                        }
                        OverlayDropdownPreference(
                            title = "会话模式",
                            summary = "single 连续上下文 / independent 独立会话",
                            items = listOf("single", "independent"),
                            selectedIndex = contextModeIndex,
                            onSelectedIndexChange = { index ->
                                contextModeIndex = index
                                config.setContextMode(if (index == 1) "independent" else "single")
                            },
                        )
                        NumberInputField(
                            label = "会话窗口时长（毫秒）",
                            initialValue = config.getContextWindowMs().toInt(),
                            onValueChange = { config.setContextWindowMs(it) },
                        )
                        NumberInputField(
                            label = "上下文长度（消息条数）",
                            initialValue = config.getContextLength(),
                            onValueChange = { config.setContextLength(it) },
                        )
                        // —— 省 token 说明：历史是每轮原样回传的，条数直接决定花费 ——
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        Text(
                            text = "省 token：上下文条数越少越便宜（默认 4 条，且单次最多回传 1200 字历史）；" +
                                "选 independent 不带历史最省。",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 12.dp),
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            item(key = "bottomSpacer") {
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        // 右侧纵向滚动条
        VerticalScrollBar(
            adapter = rememberScrollBarAdapter(listState),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight(),
            trackPadding = contentPadding,
        )
    }
}

// ====================================================================
// Tab 2：记录 —— API 调用记录与 token 用量（0.5.0-beta 第三轮：「统计」改名「记录」）
// ====================================================================

/**
 * Tab 2：记录 —— API 调用记录与 token 用量（持久化存储）。
 */
@Composable
private fun StatsTabContent(
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    contentPadding: PaddingValues,
) {
    val listState = rememberLazyListState()
    Box {
        LazyColumn(
            state = listState,
            contentPadding = contentPadding,
        ) {
            item(key = "statsTitle") {
                SmallTitle("API 调用记录")
            }
            item(key = "stats") {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    var statsRefresh by remember { mutableStateOf(0) }
                    key(statsRefresh) {
                        val stats = StatsStore.readCallStats()
                        Text(
                            text = "总调用 ${stats.totalCalls} 次 · 失败 ${stats.totalFailures} 次",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            fontSize = MiuixTheme.textStyles.body2.fontSize,
                        )
                        Text(
                            text = "输入 ${stats.totalPromptTokens} tokens · 输出 ${stats.totalCompletionTokens} tokens · 合计 ${stats.totalTokens} tokens",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                            fontSize = MiuixTheme.textStyles.body2.fontSize,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        if (stats.recentCalls.isNotEmpty()) {
                            TokenBarChart(calls = stats.recentCalls)
                        }
                        if (stats.recentCalls.isEmpty()) {
                            Text(
                                text = "暂无调用记录。手环真实调用（Hook 进程）的记录写入日志，可导出日志查看",
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                fontSize = MiuixTheme.textStyles.body2.fontSize,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        } else {
                            stats.recentCalls.take(5).forEach { call ->
                                val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                                    .format(java.util.Date(call.timestamp))
                                val status = if (call.success) "✓" else "✗"
                                Text(
                                    text = "$time $status ${call.model} · ${call.promptTokens}+${call.completionTokens}tk · ${call.querySummary}",
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    ArrowPreference(
                        title = "刷新记录",
                        summary = "重新读取持久化记录",
                        onClick = { statsRefresh++ },
                    )
                    ArrowPreference(
                        title = "清除记录",
                        summary = "清除持久化记录与当前进程的内存统计",
                        onClick = {
                            StatsStore.clear()
                            LlmClient.clearStats()
                            statsRefresh++
                            Toast.makeText(context, "记录已清除", Toast.LENGTH_SHORT).show()
                        },
                    )
                }
            }

            item(key = "bottomSpacer") {
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        VerticalScrollBar(
            adapter = rememberScrollBarAdapter(listState),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight(),
            trackPadding = contentPadding,
        )
    }
}

// ====================================================================
// 模型列表 —— 0.5.0-beta 多模型：第 1 行小爱固定 + 用户模型排序/删除/编辑
// ====================================================================

/**
 * 「模型列表」分组卡片（Config Tab 首位）：只放小爱同学 → 添加模型。
 *
 * - 第 1 行固定「小爱同学」（不可编辑/删除），选中 = `active_model=xiaoai` + `default_mode=xiaoai`；
 * - 第 2 行起为用户模型条目，选中 = `active_model=<id>` + `default_mode=llm`；
 * - 首次进入若从未写过 active_model 且列表为空，用 legacy API 字段播种一条（迁移旧配置）；
 * - 「添加」对话框内含常见提供方预设（通义/硅基/火山/讯飞/Gemini…），编辑走行内展开的精简表单；
 * - 「测试模型可用性」「智能家居指令直通」已拆到紧随其后的「其它」分组（[MiscSection]）。
 *
 * @param context Toast 与分享所需 Context
 * @param scope 拖动排序落位动画的协程作用域
 */
@Composable
private fun ModelListSection(
    config: ConfigStore,
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    // —— 迁移播种（只跑一次）：legacy 字段 → 首条模型，激活态尊重旧 default_mode ——
    remember {
        if (!config.hasActiveModelKey() && config.getModelList().isEmpty()) {
            val seed = ModelEntry(
                id = "seed",
                name = "DeepSeek",
                baseUrl = config.getBaseUrl(),
                apiKey = config.getApiKey(),
                model = config.getModel(),
                apiType = config.getApiType(),
                appendApiPath = config.isAppendApiPath(),
            )
            config.setModelList(listOf(seed))
            val xiaoaiDefault = config.getDefaultMode().trim().equals("xiaoai", ignoreCase = true)
            config.setActiveModelId(
                if (xiaoaiDefault) ConfigKeys.VALUE_MODEL_XIAOAI else seed.id
            )
        }
        true
    }

    var modelList by remember { mutableStateOf(config.getModelList()) }
    var activeId by remember { mutableStateOf(config.getActiveModelId()) }
    var showAdd by remember { mutableStateOf(false) }
    var deletingEntry by remember { mutableStateOf<ModelEntry?>(null) }
    // 行尾三横手柄的点按 = 该行行内展开「精简编辑界面」（0.5.1-beta1）：
    // Base URL / 模型名 / API Key 三栏 + 删除·取消·保存 直接在这一行下方展开，
    // 整块只有三行输入框高，不弹任何浮层；再点一次三横收起；
    // 三横长按 500ms 则进入拖动排序，像拖文件一样跟手上下移动（上移/下移按钮已删，全靠拖动）。
    var expandedId by remember { mutableStateOf<String?>(null) }

    /** 选中某行：同时写 active_model 与 default_mode，保持两键语义一致 */
    fun selectRow(id: String, defaultMode: String) {
        expandedId = null
        activeId = id
        config.setActiveModelId(id)
        config.setDefaultMode(defaultMode)
    }

    /** 写回内存与 Remote Preferences */
    fun persist(list: List<ModelEntry>) {
        modelList = list
        config.setModelList(list)
    }

    // —— 三横手柄排序（0.5.1-beta1）：长按手柄 500ms 进入排序态，跟手拖动，松手吸附落位 ——
    var dragIndex by remember { mutableStateOf(-1) }      // 正在拖动的行下标（-1 = 未在排序）
    var dragOffset by remember { mutableStateOf(0f) }     // 拖动行相对其原始槽位的像素偏移
    var blockHeightPx by remember { mutableStateOf(0f) }  // 单个行块（行 + 分隔线）实测高度，各块等高
    // 重排代数：落位重排时 +1，让其余行的位移动画「重新出生」直接停在新槽位。
    // 不加这一下，它们会带着旧偏移（±块高）先跳离一格，再动画飞回来 = 「飞出去又回来」。
    var shiftEpoch by remember { mutableStateOf(0) }
    // 行内编辑面板收起后 400ms 内不采信块高实测（收起动画中的高度是过渡值）
    var blockHLockUntil by remember { mutableStateOf(0L) }
    var lastExpandedId by remember { mutableStateOf<String?>(null) }
    val settleJob = remember { mutableStateOf<Job?>(null) }
    val haptics = LocalHapticFeedback.current

    // 展开 → 收起（点三横收起 / 保存 / 取消 / 删除 / 选中别的行）：锁住块高，避开收起动画的过渡高度
    LaunchedEffect(expandedId) {
        if (lastExpandedId != null && expandedId == null) {
            blockHLockUntil = System.currentTimeMillis() + 400L
        }
        lastExpandedId = expandedId
    }

    /** 松手落位：先动画吸到整格，再一次性重排并持久化，避免半行跳变 */
    fun endDrag() {
        val origin = dragIndex
        settleJob.value?.cancel()
        settleJob.value = null
        if (origin < 0) return
        val h = blockHeightPx
        if (h <= 0f || origin !in modelList.indices) {
            dragIndex = -1
            dragOffset = 0f
            return
        }
        val target = (origin + (dragOffset / h).roundToInt()).coerceIn(0, modelList.lastIndex)
        val snapped = (target - origin) * h
        val from = dragOffset
        settleJob.value = scope.launch {
            animate(
                initialValue = from,
                targetValue = snapped,
                animationSpec = tween(durationMillis = 160),
            ) { value, _ -> dragOffset = value }
            if (target != origin && origin in modelList.indices && target in modelList.indices) {
                val list = modelList.toMutableList()
                list.add(target, list.removeAt(origin))
                persist(list)
                shiftEpoch++ // 重排已生效 → 位移动画重新开始，各行走新槽位，不回头飞
            }
            dragIndex = -1
            dragOffset = 0f
            settleJob.value = null
        }
    }

    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
        // —— 第 1 行：小爱同学（固定位，不参与排序，无手柄） ——
        ModelRow(
            title = "小爱同学",
            subtitle = "小爱原生回答，不调用大模型",
            selected = activeId == ConfigKeys.VALUE_MODEL_XIAOAI,
            onClick = { if (dragIndex < 0) selectRow(ConfigKeys.VALUE_MODEL_XIAOAI, "xiaoai") },
        )
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

        // —— 排序几何：块高由 onSizeChanged 实测；拖动时除拖动行外的行块
        //    按目标槽位整体平移（带动画），拖动行跟手偏移并置顶绘制 ——
        val h = blockHeightPx
        val draggingIndex = dragIndex
        val targetIndex = if (draggingIndex >= 0 && h > 0f) {
            (draggingIndex + (dragOffset / h).roundToInt()).coerceIn(0, modelList.lastIndex)
        } else {
            -1
        }

        // —— 第 2 行起：用户模型（点行选中 / 点三横行内展开编辑界面 / 长按三横 500ms 拖动排序） ——
        modelList.forEachIndexed { index, entry ->
            key(entry.id) {
                val expanded = expandedId == entry.id
                val shiftPx = when {
                    draggingIndex < 0 -> 0f
                    index == draggingIndex -> dragOffset
                    draggingIndex < targetIndex && index in (draggingIndex + 1)..targetIndex -> -h
                    draggingIndex > targetIndex && index in targetIndex until draggingIndex -> h
                    else -> 0f
                }
                // shiftEpoch 变化（刚落位重排）→ 重新创建动画实例，直接停在新槽位，不往回飞
                val animatedShift by key(shiftEpoch) {
                    animateFloatAsState(
                        targetValue = shiftPx,
                        animationSpec = tween(durationMillis = 160),
                        label = "modelRowShift",
                    )
                }
                val sorting = index == draggingIndex
                val offsetY = if (sorting) dragOffset else animatedShift
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(if (sorting) 1f else 0f)
                        .graphicsLayer { translationY = offsetY }
                        .onSizeChanged { size ->
                            val measured = size.height.toFloat()
                            // 展开中的行块更高、收起动画中的高度是过渡值，都不参与块高实测
                            // （排序位移始终按「收起态」的真实块高算）
                            val locked = System.currentTimeMillis() < blockHLockUntil
                            if (!expanded && !locked && measured > 0f && blockHeightPx != measured) {
                                blockHeightPx = measured
                            }
                        },
                ) {
                    ModelRow(
                        title = entry.name.ifBlank { entry.model.ifBlank { "未命名模型" } },
                        subtitle = modelSubtitle(entry),
                        selected = activeId == entry.id,
                        sorting = sorting,
                        expanded = expanded,
                        onClick = { if (dragIndex < 0) selectRow(entry.id, "llm") },
                        handle = RowHandleActions(
                            onTap = {
                                if (dragIndex < 0) {
                                    expandedId = if (expanded) null else entry.id
                                }
                            },
                            onSortStart = {
                                if (dragIndex < 0) {
                                    expandedId = null // 拖动排序时收起行内面板
                                    dragIndex = index
                                    dragOffset = 0f
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    true
                                } else {
                                    false // 已有行在排序（如松手吸附动画中），本次手势让位
                                }
                            },
                            onSortDrag = { dy ->
                                if (dragIndex == index && blockHeightPx > 0f) {
                                    val maxDown = (modelList.lastIndex - index) * blockHeightPx
                                    val maxUp = -index * blockHeightPx
                                    dragOffset = (dragOffset + dy).coerceIn(maxUp, maxDown)
                                }
                            },
                            onSortEnd = { if (dragIndex == index) endDrag() },
                        ),
                    )
                    // —— 行内展开 = 精简编辑界面（点三横开合，带展开/收起动画）：
                    //    只露 Base URL / 模型名 / API Key + 删除·取消·保存，整块只有三行输入框高，
                    //    展开在这一行下方，不弹任何浮层 ——
                    AnimatedVisibility(
                        visible = expanded,
                        enter = expandVertically(animationSpec = tween(220)) + fadeIn(tween(180)),
                        exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(tween(150)),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                            ModelInlineEdit(
                                entry = entry,
                                onCancel = { expandedId = null },
                                onSave = { updated ->
                                    persist(modelList.map { if (it.id == updated.id) updated else it })
                                    expandedId = null
                                },
                                onDelete = {
                                    expandedId = null
                                    deletingEntry = entry
                                },
                            )
                        }
                    }
                    // 每个行块自带底部分隔线（含末行 → 添加模型），保证块等高且间隔一致
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }

        // 0.6.0-beta1 真机反馈：只留「+ 添加模型」四个字并整行水平居中
        //（原「箭头行 + 长说明」删除），+ 用 Miuix 的 Add 图标。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 56.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable { showAdd = true }
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = MiuixIcons.Add,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MiuixTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = "添加模型",
                fontSize = MiuixTheme.textStyles.headline1.fontSize,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.primary,
            )
        }
        // —— 「测试模型可用性」「智能家居指令直通」已拆到独立的「其它」分组卡片（MiscSection）——
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        Text(
            text = "选中的行即默认回答来源（小爱行=原生接管，模型行=LLM 接管）；" +
                "手环上说「${config.getCmdSwitchModel().firstOrNull() ?: "换模型"}」可语音切换。",
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 12.dp),
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }

    // —— 添加对话框 ——
    if (showAdd) {
        ModelEditDialog(
            entry = null,
            onDismiss = { showAdd = false },
            onSave = { new ->
                persist(modelList + new)
                showAdd = false
            },
        )
    }

    // —— 编辑已在行内完成（点三横展开编辑界面），不再弹编辑对话框；「添加模型」仍走对话框 ——

    // —— 删除确认 ——
    deletingEntry?.let { entry ->
        ModelDeleteDialog(
            entry = entry,
            onDismiss = { deletingEntry = null },
            onConfirm = {
                val list = modelList.filterNot { it.id == entry.id }
                persist(list)
                // 删除的恰好是激活模型 → 回落到「小爱同学」，避免悬空 id
                if (activeId == entry.id) {
                    selectRow(ConfigKeys.VALUE_MODEL_XIAOAI, "xiaoai")
                }
                deletingEntry = null
            },
        )
    }

    // —— 行内展开的精简编辑界面由行块自身渲染，无需浮层 ——
}

/**
 * 「其它」分组卡片（Config Tab，紧接「模型列表」之后）：
 * 测试模型可用性（原「关于」页「测试连接」移入并改名）+ 智能家居指令直通（原「基本设置」卡）。
 *
 * @param context Toast 所需 Context
 * @param scope 测试连接的协程作用域（IO 请求 + Main 回主线程）
 */
@Composable
private fun MiscSection(
    config: ConfigStore,
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
        // 测试连接（0.5.0-beta 第三轮移入配置 Tab，第六轮拆到「其它」卡）
        var testing by remember { mutableStateOf(false) }
        ArrowPreference(
            title = "测试模型可用性",
            summary = if (testing) {
                "正在用当前激活模型请求一次…"
            } else {
                "用当前激活模型请求一次，验证 Base URL / API Key 是否有效"
            },
            enabled = !testing,
            onClick = {
                testing = true
                scope.launch(Dispatchers.IO) {
                    LlmClient.init(config)
                    val result = LlmClient.ask("settings-connection-test", "连接测试：请回答连接成功")
                    withContext(Dispatchers.Main) {
                        testing = false
                        val msg = if (result.isNullOrBlank()) {
                            "连接失败：请检查 Base URL / API Key / 超时设置"
                        } else {
                            "连接成功：${result.trim().take(40)}"
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                }
            },
        )
        // 智能家居指令直通（原「基本设置」卡）
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        var smartHomePass by remember { mutableStateOf(config.getSmartHomePass()) }
        SwitchPreference(
            title = "智能家居指令直通",
            summary = "控制设备/场景的语音指令不调用大模型，直接交小爱原生执行",
            checked = smartHomePass,
            onCheckedChange = {
                smartHomePass = it
                config.setSmartHomePass(it)
            },
        )
    }
}

/** 模型行副标题：模型名 · 主机（anthropic 额外标注） */
private fun modelSubtitle(entry: ModelEntry): String {
    val url = entry.baseUrl.trim().trimEnd('/')
    val host = url.substringAfter("://", "").substringBefore("/").ifBlank { url }
    val type = if (entry.apiType.trim().equals("anthropic", ignoreCase = true)) " · anthropic" else ""
    val model = entry.model.ifBlank { "(未填模型)" }
    return if (host.isBlank()) model else "$model · $host$type"
}

/**
 * 三横手柄回调：点按 = 该行行内展开「编辑界面」 / 长按进排序（返回是否成功进入，
 * 失败则本次手势退化为点按）/ 拖动位移（px，+ 向下）/ 松手落位。
 */
private class RowHandleActions(
    val onTap: () -> Unit,
    val onSortStart: () -> Boolean,
    val onSortDrag: (Float) -> Unit,
    val onSortEnd: () -> Unit,
)

/** 手柄长按阈值：按住 500ms 进入排序态（像拖文件一样把行「拖出来」） */
private const val HANDLE_LONG_PRESS_MS = 500L

/**
 * 单行模型条目（0.5.1-beta1 三横手柄 + 行内展开）：
 * - 外层 Row = 「可点主体 + 行尾三横手柄」的兄弟结构，手柄不在整行点击的命中路径内，
 *   点手柄不会再顺带选中该行；
 * - 主体复用 Miuix `BasicComponent`，与「添加模型 / 测试模型可用性」等偏好行同内边距（16dp）、
 *   同最小高度（56dp）、同字体（headline1 标题 / body2 副标题），保证同卡内行高一致；
 * - 主体可点 = 选中，选中行 primary 浅底色 + 标题后「使用中」胶囊；
 * - `sorting`（拖动中）时行变成「被拎起来的卡片」：圆角裁切 + 主题色底 + 描边 + 置顶绘制，
 *   随手指上下移动，原槽位让给其他行（同 Windows 拖文件的观感）；
 * - `expanded` 时三横高亮，表示该行的编辑界面已展开；
 * - `handle = null`（小爱行）不渲染手柄。
 */
@Composable
private fun ModelRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    handle: RowHandleActions? = null,
    sorting: Boolean = false,
    expanded: Boolean = false,
) {
    Row(
        modifier = if (sorting) {
            // 拖动中：圆角卡片 + 主题色底 + 描边（四周留白处透出下方行，像被「拎起」）
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.16f))
                .border(
                    width = 1.dp,
                    color = MiuixTheme.colorScheme.primary.copy(alpha = 0.40f),
                    shape = RoundedCornerShape(12.dp),
                )
        } else {
            Modifier
                .fillMaxWidth()
                .background(
                    if (selected) {
                        MiuixTheme.colorScheme.primary.copy(alpha = 0.07f)
                    } else {
                        Color.Transparent
                    },
                )
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicComponent(
            modifier = Modifier.weight(1f),
            onClick = onClick,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    fontSize = MiuixTheme.textStyles.headline1.fontSize,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (selected) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.onBackground
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (selected) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "使用中",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            Text(
                text = subtitle,
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (handle != null) {
            RowDragHandle(actions = handle, highlighted = sorting || expanded)
        }
    }
}

/**
 * 行尾「三横」拖动手柄（0.5.1-beta1）：三条 16dp 横线，右缘与行内 16dp 内边距对齐。
 * - 点按（500ms 内松开且未明显位移）→ 该行**行内展开精简编辑界面**（Base URL/模型名/API Key + 删除·取消·保存，再点收起）；
 * - 按住满 500ms → 把行「拖出来」进入排序态（横线转 primary 色 + 震动反馈），
 *   随手指上下移动实时重排，松手吸附落位；
 * - 长按前位移超过 2×touchSlop → 视为误触，整段手势取消（长按计时一并取消）。
 * 长按用 [LaunchedEffect] 独立计时（不依赖 awaitPointerEvent 的超时取消，更稳）；
 * 排序态才消费指针事件（接管列表滚动）；未进排序时若事件已被父级消费（= 列表开始滚动）
 * 则整段让位，因此手柄上也能正常滑动列表。
 */
@Composable
private fun RowDragHandle(actions: RowHandleActions, highlighted: Boolean) {
    val onTap by rememberUpdatedState(actions.onTap)
    val onSortStart by rememberUpdatedState(actions.onSortStart)
    val onSortDrag by rememberUpdatedState(actions.onSortDrag)
    val onSortEnd by rememberUpdatedState(actions.onSortEnd)

    // 按压标记 → 驱动 500ms 长按计时；抬手 / 位移取消时置回 false，计时协程随之取消
    var pressing by remember { mutableStateOf(false) }
    // 本手势是否已进入排序态：由长按计时器在 onSortStart 成功后同步置位，
    // 比等父级 recompose 更新 sorting 更及时
    var sortArmed by remember { mutableStateOf(false) }
    LaunchedEffect(pressing) {
        if (pressing) {
            delay(HANDLE_LONG_PRESS_MS)
            if (onSortStart()) sortArmed = true
        }
    }

    Box(
        modifier = Modifier
            .width(40.dp)
            .height(36.dp)
            .pointerInput(Unit) {
                val tolerance = viewConfiguration.touchSlop * 2f
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        pressing = true
                        var lastY = down.position.y
                        var cancelled = false

                        while (true) {
                            val change = awaitPointerEvent(PointerEventPass.Main)
                                .changes.firstOrNull { it.id == down.id }
                            if (change == null || !change.pressed) break // 抬手
                            if (sortArmed) {
                                // 排序态：跟手位移并消费事件，列表滚动到此被接管
                                val dy = change.position.y - lastY
                                lastY = change.position.y
                                change.consume()
                                if (dy != 0f) onSortDrag(dy)
                            } else {
                                // 未进排序：父级（列表滚动）已消费 或 位移过大 → 取消整段手势
                                if (change.isConsumed ||
                                    (change.position - down.position).getDistance() > tolerance
                                ) {
                                    cancelled = true
                                    break
                                }
                            }
                        }

                        val wasSorting = sortArmed
                        pressing = false
                        sortArmed = false
                        if (cancelled) continue // 误触 / 已让位给滚动：既不点按也不排序
                        if (wasSorting) onSortEnd() else onTap()
                    }
                }
            }
            .padding(end = 16.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(3) {
                Box(
                    modifier = Modifier
                        .width(16.dp)
                        .height(1.8.dp)
                        .background(
                            color = if (highlighted) {
                                MiuixTheme.colorScheme.primary
                            } else {
                                MiuixTheme.colorScheme.onSurfaceVariantSummary
                            },
                            shape = RoundedCornerShape(2.dp),
                        ),
                )
            }
        }
    }
}

/**
 * 模型行内联的**精简**编辑界面（点三横展开）：
 * 只露 显示名称 / Base URL / 模型名 / API Key 四个输入框 + 删除·取消·保存，
 * 展开在该行下方、不弹任何浮层；预设 chips / API 类型 / 自动拼接等大件只留在「添加模型」对话框里。
 * 排序走长按三横拖动，故这里不再放上移/下移。
 */
@Composable
private fun ModelInlineEdit(
    entry: ModelEntry,
    onCancel: () -> Unit,
    onSave: (ModelEntry) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember(entry.id) { mutableStateOf(entry.name) }
    var baseUrl by remember(entry.id) { mutableStateOf(entry.baseUrl) }
    var apiKey by remember(entry.id) { mutableStateOf(entry.apiKey) }
    var model by remember(entry.id) { mutableStateOf(entry.model) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
    ) {
        TextField(
            value = name,
            onValueChange = { name = it },
            label = "显示名称",
            singleLine = true,
        )
        Spacer(modifier = Modifier.height(8.dp))
        TextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = "Base URL",
            singleLine = true,
        )
        Spacer(modifier = Modifier.height(8.dp))
        TextField(
            value = model,
            onValueChange = { model = it },
            label = "模型名",
            singleLine = true,
        )
        Spacer(modifier = Modifier.height(8.dp))
        ApiKeyField(
            initialValue = apiKey,
            onValueChange = { apiKey = it },
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                text = "删除",
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColors(textColor = MiuixTheme.colorScheme.error),
                onClick = onDelete,
            )
            TextButton(
                text = "取消",
                modifier = Modifier.weight(1f),
                onClick = onCancel,
            )
            TextButton(
                text = "保存",
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = {
                    onSave(
                        entry.copy(
                            name = name.trim(),
                            baseUrl = baseUrl.trim(),
                            apiKey = apiKey.trim(),
                            model = model.trim(),
                        ),
                    )
                },
            )
        }
    }
}

/**
 * 「添加模型」对话框：套壳 [ModelEditForm] —— 编辑不再走对话框，而是直接在模型行内展开同一张表单。
 * @param entry null = 新增
 */
@Composable
private fun ModelEditDialog(
    entry: ModelEntry?,
    onDismiss: () -> Unit,
    onSave: (ModelEntry) -> Unit,
) {
    OverlayDialog(
        show = true,
        title = "添加模型",
        summary = "选择提供方预设可自动填入地址与模型",
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            ModelEditForm(entry = entry, onDismiss = onDismiss, onSave = onSave)
        }
    }
}

/**
 * 模型编辑表单（「添加模型」对话框与模型行内展开的编辑界面共用）：
 * 提供方预设 chips + 名称 / Base URL / 模型名 / API Key + API 类型 + 自动拼接 + 取消/保存。
 * 自身不带滚动容器（由外层决定），因此可以原样嵌进模型行的展开区。
 * @param entry null = 新增；非 null = 编辑（以该条目初始化字段）
 */
@Composable
private fun ModelEditForm(
    entry: ModelEntry?,
    onDismiss: () -> Unit,
    onSave: (ModelEntry) -> Unit,
) {
    val editingId = entry?.id
    var name by remember(editingId) { mutableStateOf(entry?.name ?: "") }
    var baseUrl by remember(editingId) { mutableStateOf(entry?.baseUrl ?: "") }
    var apiKey by remember(editingId) { mutableStateOf(entry?.apiKey ?: "") }
    var model by remember(editingId) { mutableStateOf(entry?.model ?: "") }
    var apiType by remember(editingId) { mutableStateOf(entry?.apiType ?: ConfigKeys.DEFAULT_API_TYPE) }
    var appendPath by remember(editingId) { mutableStateOf(entry?.appendApiPath ?: ConfigKeys.DEFAULT_APPEND_API_PATH) }
    var presetLabel by remember(editingId) { mutableStateOf(ModelPresets.CUSTOM_LABEL) }

    Column(
        modifier = Modifier
            .fillMaxWidth(),
        content = {
            // —— 提供方预设 chips ——
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ModelPresets.ALL.map { it.label }) { label ->
                    Chip(
                        label = label,
                        selected = label == presetLabel,
                        onClick = {
                            presetLabel = label
                            ModelPresets.byLabel(label)?.let { p ->
                                // 自定义项（地址为空）不覆盖已填内容
                                if (p.baseUrl.isNotBlank()) {
                                    baseUrl = p.baseUrl
                                    model = p.model
                                    apiType = p.apiType
                                    appendPath = p.appendApiPath
                                }
                            }
                        },
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            TextField(
                value = name,
                onValueChange = { name = it },
                label = "显示名称（模型菜单中显示）",
                singleLine = true,
            )
            Spacer(modifier = Modifier.height(12.dp))
            TextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = "Base URL",
                singleLine = true,
            )
            Spacer(modifier = Modifier.height(12.dp))
            TextField(
                value = model,
                onValueChange = { model = it },
                label = "模型名",
                singleLine = true,
            )
            Spacer(modifier = Modifier.height(8.dp))
            ApiKeyField(
                initialValue = apiKey,
                onValueChange = { apiKey = it },
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(
                    label = "openai",
                    selected = apiType == "openai",
                    onClick = { apiType = "openai" },
                )
                Chip(
                    label = "anthropic",
                    selected = apiType == "anthropic",
                    onClick = { apiType = "anthropic" },
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            SwitchPreference(
                title = "自动拼接 API 路径",
                summary = "开：补全 /v1/chat/completions 或 /v1/messages；关：Base URL 作为完整地址",
                checked = appendPath,
                onCheckedChange = { appendPath = it },
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    text = "取消",
                    modifier = Modifier.weight(1f),
                    onClick = onDismiss,
                )
                TextButton(
                    text = "保存",
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        val id = editingId ?: ("m_" + System.currentTimeMillis())
                        onSave(
                            ModelEntry(
                                id = id,
                                name = name.trim(),
                                baseUrl = baseUrl.trim(),
                                apiKey = apiKey.trim(),
                                model = model.trim(),
                                apiType = apiType,
                                appendApiPath = appendPath,
                            )
                        )
                    },
                )
            }
        },
    )
}

/** 删除确认对话框 */
@Composable
private fun ModelDeleteDialog(
    entry: ModelEntry,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val title = entry.name.ifBlank { entry.model.ifBlank { "未命名模型" } }
    OverlayDialog(
        show = true,
        title = "删除模型",
        summary = "确定删除「$title」？删除后不可恢复。",
        onDismissRequest = onDismiss,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(
                text = "取消",
                modifier = Modifier.weight(1f),
                onClick = onDismiss,
            )
            TextButton(
                text = "删除",
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = onConfirm,
            )
        }
    }
}

/** 提供方/API 类型选择用的小圆角标签 */
@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (selected) {
                    MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)
                } else {
                    MiuixTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        fontSize = 13.sp,
        color = if (selected) {
            MiuixTheme.colorScheme.primary
        } else {
            MiuixTheme.colorScheme.onSurfaceVariantSummary
        },
    )
}

// ====================================================================
// 输入组件 —— 自定义文本/数字/可空输入框
// ====================================================================

/**
 * 单行文本输入：Base URL / 模型 等字符串配置。
 *
 * 0.5.0-beta 第三轮：Miuix TextField 没有独立 placeholder 参数（label 状态机在
 * 空值时显示于输入区、有值时上浮），因此用动态 label 实现「示例仅空时显示」：
 * - 内容为空 → `label（示例）` 作为输入区内的提示；
 * - 内容非空 → 只剩 `label`，避免长示例常驻占位（如系统提示词）。
 */
@Composable
private fun TextInputField(
    initialValue: String,
    label: String,
    placeholder: String = "",
    singleLine: Boolean = true,
    onValueChange: (String) -> Unit,
) {
    var text by remember(initialValue) { mutableStateOf(initialValue) }
    val effectiveLabel = when {
        placeholder.isEmpty() -> label
        text.isEmpty() -> "$label（$placeholder）"
        else -> label
    }
    TextField(
        value = text,
        onValueChange = { input ->
            text = input
            onValueChange(input)
        },
        label = effectiveLabel,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

/**
 * API Key 输入：默认掩码显示，点击尾部图标可临时切换明文。
 *
 * 密钥在设置页一律不默认明文回显，避免肩窥 / 录屏 / 投屏场景下泄露；
 * 明文仅在用户主动点击后短暂展示，且不会改变已存储的值。
 */
@Composable
private fun ApiKeyField(
    initialValue: String,
    onValueChange: (String) -> Unit,
) {
    var text by remember(initialValue) { mutableStateOf(initialValue) }
    var visible by remember { mutableStateOf(false) }
    TextField(
        value = text,
        onValueChange = { input ->
            text = input
            onValueChange(input)
        },
        label = "API Key",
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) MiuixIcons.Hide else MiuixIcons.Show,
                    contentDescription = if (visible) "隐藏 API Key" else "显示 API Key",
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

/** 数字输入：超时 / Token / 会话等必填整型配置 */
@Composable
private fun NumberInputField(
    label: String,
    initialValue: Int,
    onValueChange: (Int) -> Unit,
) {
    var text by remember(initialValue) { mutableStateOf(initialValue.toString()) }
    TextField(
        value = text,
        onValueChange = { input ->
            text = input
            input.toIntOrNull()?.let { onValueChange(it) }
        },
        label = label,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

// ====================================================================
// Token 柱状图 —— 统计页可视化组件
// ====================================================================

/**
 * Token 用量柱状图 —— 展示最近若干次调用的输入（prompt）/ 输出（completion）token 占比。
 */
@Composable
private fun TokenBarChart(
    calls: List<LlmClient.ApiCallRecord>,
    maxBars: Int = 10,
) {
    if (calls.isEmpty()) return

    val data = calls.takeLast(maxBars)
    val maxToken = (data.maxOfOrNull { it.promptTokens + it.completionTokens } ?: 1).coerceAtLeast(1)

    val yStep = ((maxToken / 4).coerceAtLeast(1) + 9) / 10 * 10
    val yMax = yStep * 4

    val promptColor = MiuixTheme.colorScheme.primary
    val completionColor = MiuixTheme.colorScheme.primaryContainer
    val failureColor = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.4f)
    val textColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val gridColor = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.15f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LegendDot(color = promptColor, label = "输入 token")
            LegendDot(color = completionColor, label = "输出 token")
            LegendDot(color = failureColor, label = "失败")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
        ) {
            val leftPad = 44.dp.toPx()
            val bottomPad = 22.dp.toPx()
            val topPad = 8.dp.toPx()
            val chartW = size.width - leftPad
            val chartH = size.height - bottomPad - topPad

            val textPaint = Paint().apply {
                color = textColor.toArgb()
                textSize = 10.sp.toPx()
                textAlign = Paint.Align.RIGHT
                isAntiAlias = true
            }
            for (i in 0..4) {
                val v = yStep * i
                val y = topPad + chartH - (v.toFloat() / yMax) * chartH
                drawLine(
                    color = gridColor,
                    start = Offset(leftPad, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.dp.toPx(),
                )
                drawContext.canvas.nativeCanvas.drawText(
                    v.toString(),
                    leftPad - 4.dp.toPx(),
                    y + textPaint.textSize / 3,
                    textPaint,
                )
            }

            val barCount = data.size
            val slotW = chartW / barCount
            val barW = (slotW * 0.6f).coerceAtMost(36.dp.toPx())

            val timePaint = Paint().apply {
                color = textColor.toArgb()
                textSize = 9.sp.toPx()
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
            }
            data.forEachIndexed { i, call ->
                val total = call.promptTokens + call.completionTokens
                val barH = if (total > 0) (total.toFloat() / yMax) * chartH else 0f
                val x = leftPad + i * slotW + (slotW - barW) / 2
                val bottom = topPad + chartH

                if (!call.success) {
                    drawRect(
                        color = failureColor,
                        topLeft = Offset(x, bottom - barH),
                        size = androidx.compose.ui.geometry.Size(barW, barH),
                    )
                } else {
                    val promptH = if (call.promptTokens > 0) (call.promptTokens.toFloat() / yMax) * chartH else 0f
                    val completionH = if (call.completionTokens > 0) (call.completionTokens.toFloat() / yMax) * chartH else 0f
                    drawRect(
                        color = completionColor,
                        topLeft = Offset(x, bottom - completionH),
                        size = androidx.compose.ui.geometry.Size(barW, completionH),
                    )
                    drawRect(
                        color = promptColor,
                        topLeft = Offset(x, bottom - completionH - promptH),
                        size = androidx.compose.ui.geometry.Size(barW, promptH),
                    )
                }

                val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                    .format(java.util.Date(call.timestamp))
                drawContext.canvas.nativeCanvas.drawText(
                    time,
                    leftPad + i * slotW + slotW / 2,
                    size.height - 6.dp.toPx(),
                    timePaint,
                )
            }

            drawRect(
                color = gridColor,
                topLeft = Offset(leftPad, topPad),
                size = androidx.compose.ui.geometry.Size(chartW, chartH),
                style = Stroke(width = 1.dp.toPx()),
            )
        }
    }
}

/** 图例小圆点 + 文字 */
@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier
            .size(10.dp)
            .padding(0.dp)) {
            drawCircle(color = color, radius = 4.dp.toPx())
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            fontSize = MiuixTheme.textStyles.body2.fontSize,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}