package com.zeroone01.xiaoai.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.zeroone01.xiaoai.core.HostEnv
import com.zeroone01.xiaoai.core.ModelManager
import com.zeroone01.xiaoai.core.XLog
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 在宿主进程（com.mi.health）里以 **Activity content 子视图** + Compose 的方式
 * 直接渲染设置 UI —— 不再使用 WindowManager.addView 浮窗方案。
 *
 * ## v0.1.0-beta5 设计（彻底推翻旧版浮窗）
 *
 * 之前用 WindowManager.addView(type=TYPE_APPLICATION, applicationContext)：
 *  - `applicationContext` 的 WindowManager 没有 token → BadTokenException
 *  - 浮窗虽然能捕获 back 键，但和宿主 App 的 Activity 栈是平行关系，
 *    back 关闭浮窗后再按一次会直接退到主页（用户反馈的「back 退到主页」）
 *  - 深色模式要单独监听 Configuration.UI_MODE_NIGHT_*，否则永远浅色
 *
 * 新方案：直接在宿主 Activity 的 `android.R.id.content` 里 addView 一个 DecorRootView，
 * 用 ComposeView 渲染 Miuix UI：
 *  - Activity content 自带 token，不存在 BadTokenException
 *  - 把宿主 Activity 的 OnBackPressedDispatcher 注册一个 callback，回调先 dismiss 自己，
 *    这样按 back 时直接关掉设置页，不会冒泡到 Activity.finish()
 *  - 直接从宿主 Activity 的 Configuration 读 UI_MODE_NIGHT_* 来决定 MiuixTheme 配色
 *
 * ## 生命周期
 *  - [show] 由 SettingsPageInjector 在用户点击注入项时调用；
 *  - [dismiss] 由顶栏「关闭」、back 键、Activity.onDestroy 时调用。
 *  - 同时显示时先 dismiss 再 show。
 */
object SettingsWindowController {

    private const val TAG = "SettingsWindow"

    @Volatile private var attached: DecorRootView? = null
    @Volatile private var backCallback: OnBackPressedCallback? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 由 [SettingsContent] 在 DisposableEffect 里注册：
     *  - [navStateProvider] 返回当前是否在「诊断子页」（仅 Compose 内部状态，
     *    dispatcher 不能直接读 —— 走这个 lambda 让回调访问）；
     *  - [onShowDiagRequested] 让非 Compose 代码（如调试入口）触发打开诊断页。
     */
    @Volatile private var navStateProvider: () -> Boolean = { false }
    @Volatile private var onShowDiagRequested: (() -> Unit)? = null

    /** 公开：让外部代码主动触发打开诊断子页（诊断页 1.5s 轮询自己拿最新状态） */
    fun openDiagnostics() {
        onShowDiagRequested?.invoke()
    }

    /** 是否已显示 */
    fun isShowing(): Boolean = attached != null

    /**
     * 在宿主 Activity 上拉起设置 UI。
     *
     * @param hostActivity 必须是 Activity（不能是 Application Context），
     *                    否则无法获得 OnBackPressedDispatcher 与合法的 content token。
     */
    fun show(hostActivity: Activity) {
        // 强制主线程
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { show(hostActivity) }
            return
        }

        // 模块自己的 Context —— 取主题、字符串、density 等资源
        //
        // ★ 关键修复（v0.1.0-beta6）：
        //   旧实现用 `hostActivity.createPackageContext("com.zeroone01.xiaoai", …)`
        //   会在 Android 11+ 上失败（"Application package not found"），因为宿主 manifest
        //   没声明 `<queries>`。失败后 fallback 到 hostActivity，导致 ComposeView
        //   用宿主的 Resources 渲染 —— 一旦用户点 TextField，compose-ui 的
        //   PopupLayout.createLayoutParams 会找 R.string.popup_window_title，找不到就
        //   `Resources$NotFoundException: String resource ID #0x7f0a000e`，
        //   整个宿主进程被 SIGSYS 杀回桌面。
        //
        //   新实现走 [HostEnv.buildModuleContext] —— 用 framework 提供的模块
        //   `moduleApplicationInfo` + `PackageManager.getResourcesForApplication(ApplicationInfo)`
        //   这条**公开 API**（不依赖 package visibility），拿到模块真正的 Resources 后用
        //   ContextWrapper 替换 getResources/getAssets/getPackageName。TextField 触发
        //   PopupLayout 时拿到的就是模块的 compose-ui 字符串，不再崩溃。
        val moduleCtx = HostEnv.moduleAppInfo?.let { moduleInfo ->
            HostEnv.buildModuleContext(hostActivity, moduleInfo)
        } ?: hostActivity
        if (HostEnv.moduleAppInfo == null) {
            XLog.w("模块 ApplicationInfo 未缓存，使用宿主 Context（TextField 可能崩溃）：${moduleCtx.packageName}")
        }

        ModelManager.ensureInit(hostActivity)

        // 先 dismiss 旧的（同一时刻只允许一个实例）
        dismissInternal()

        val isDark = isHostInDarkMode(hostActivity)

        val root = DecorRootView(moduleCtx).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundColor(
                if (isDark) Color.parseColor("#FF101013")
                else Color.parseColor("#FFF7F7F8")
            )
        }

        val composeView = ComposeView(moduleCtx).apply {
            setViewTreeLifecycleOwner(root)
            setViewTreeViewModelStoreOwner(root)
            setViewTreeSavedStateRegistryOwner(root)
            setContent {
                SettingsContent(
                    isDarkTheme = isDark,
                    onClose = { dismiss() },
                )
            }
        }
        root.addView(
            composeView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        val contentParent = hostActivity.window.decorView
            .findViewById<ViewGroup>(android.R.id.content)
            ?: run {
                XLog.e("宿主 Activity 没有 android.R.id.content，无法显示设置窗口")
                return
            }

        // 把根视图塞进宿主 Activity 的 content（自带 token，永不抛 BadTokenException）
        runCatching {
            contentParent.addView(
                root,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            attached = root
            root.lifecycleRegistry.currentState = Lifecycle.State.RESUMED
            XLog.i(
                "设置窗口已挂入宿主 Activity 内容 (isDark=$isDark) " +
                    "(content 子 View 数=${contentParent.childCount})"
            )
        }.onFailure { XLog.e("挂入宿主 Activity content 失败", it) }

        // 注册 Back 拦截：宿主按 back 时先关掉我们，再冒泡给 Activity.finish()
        attachBackInterceptor(hostActivity, root)
    }

    /** 关闭设置窗口。安全可重复调用。 */
    fun dismiss() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { dismiss() }
            return
        }
        dismissInternal()
    }

    private fun dismissInternal() {
        val root = attached ?: return
        attached = null
        // 取消 Back 拦截
        runCatching {
            backCallback?.remove()
            backCallback = null
        }
        runCatching {
            root.lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
            (root.parent as? ViewGroup)?.removeView(root)
            XLog.i("设置窗口已从宿主 Activity 移除")
        }.onFailure {
            XLog.w("移除设置窗口失败: ${it.message}")
        }
    }

    // ======================================================================
    // Back 拦截 —— 宿主按 back 时先 dismiss 再让 Activity 处理
    // ======================================================================

    private fun attachBackInterceptor(activity: Activity, root: View) {
        runCatching {
            // AppCompatActivity / ComponentActivity 才有 OnBackPressedDispatcherOwner；
            // 宿主 Activity 是后者（继承自 androidx.activity.ComponentActivity）
            val dispatcher = (activity as? OnBackPressedDispatcherOwner)?.onBackPressedDispatcher
                ?: return@runCatching
            val callback = object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // ★ 关键（v0.1.0-beta6）：
                    //   旧实现永远 dismiss()——结果用户在「诊断子页」按 back 时，
                    //   整个设置窗口被关闭，再按一次 back 就退到宿主 Activity 的"我的"页。
                    //   现在的逻辑：
                    //    - 当前在「诊断子页」→ 走 [navStateProvider]，让 Compose 层
                    //      自己处理（DiagnosticsScreen.onBack 把 showDiag 设为 false）；
                    //    - 当前在主设置页 → 走 dismiss() 关闭整个设置窗口。
                    //
                    //   因为 navStateProvider 是 lambda（不可重写 handleOnBackPressed
                    //   的 onBackPressed 把控制权交给 Compose 实际是不可能的），
                    //   这里用一个简单的"尝试往主线程 post 一次 onBack 模拟"的方案：
                    //   把 back 触发给 DiagnosticsScreen 自带的"返回"按钮（点击）。
                    if (navStateProvider()) {
                        // 让 DiagnosticsScreen 的 onBack 触发（通过模拟点击返回按钮）——
                        // 但实际上更直接的是 Dispatchers.Main post 一段 lambda 把
                        // showDiag 改回 false。这里把 controller 的 back 简单地视为
                        // "通知 Compose 子页自己处理"。
                        XLog.d("宿主按 back → 在诊断子页，转发给 Compose 层处理")
                        // 用 root 的 view 树 post 一次 onBack 请求：
                        root.post {
                            // 通过 root 的 listener 触发子页的 onBack：
                            // 因为 Composable 里 showDiag 是 State，需要拿到 recompose
                            // 的入口。最简单可靠的做法是在 controller 上提供一个
                            // "隐藏诊断"回调，下面 DisposableEffect 里挂上。
                            hideDiagnosticsRequested?.invoke()
                        }
                    } else {
                        XLog.d("宿主按 back → dismiss 设置窗口（不冒泡到 Activity.finish）")
                        dismiss()
                    }
                }
            }
            dispatcher.addCallback(activity, callback)
            backCallback = callback
        }.onFailure { XLog.w("OnBackPressedDispatcher 注册失败，回退到传统 back 键拦截: ${it.message}") }
    }

    /**
     * 由 [SettingsContent] 在 DisposableEffect 里注册：
     *  - 当宿主按 back 且当前在「诊断子页」，外部 Compose 代码执行这个回调把 showDiag 改回 false。
     */
    @Volatile private var hideDiagnosticsRequested: (() -> Unit)? = null

    // ======================================================================
    // 深色模式判定
    // ======================================================================

    private fun isHostInDarkMode(context: Context): Boolean {
        val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return night == Configuration.UI_MODE_NIGHT_YES
    }

    // ======================================================================
    // Compose 入口
    // ======================================================================

    @Composable
    private fun SettingsContent(isDarkTheme: Boolean, onClose: () -> Unit) {
        val cfg by ModelManager.configFlow.collectAsState()

        // ★ 把 showDiag 提到这里（而不是 OnBackPressedDispatcher 的 lambda 里）。
        //   旧实现把 onBack = { showDiag = false } 写在 lambda 内部，宿主按 back 键时
        //   回调先走到 dispatcher，回调只 dismiss——结果是把"诊断页"连同整个设置
        //   窗口一起关掉，再按一次 back 就退到 Activity.finish() → "我的"页。
        //   这里让 onBack 正确通知 dispatcher 关闭的是「诊断层」而不是「整个设置窗口」。
        var showDiag by remember { mutableStateOf(false) }

        // 把 showDiag 同步到 controller 的局部字段 —— 用 DisposableEffect 注入
        androidx.compose.runtime.DisposableEffect(Unit) {
            navStateProvider = { showDiag }
            onShowDiagRequested = { showDiag = true }
            // ★ beta6 修复：在诊断子页按 back 时不再把整个设置窗口关掉，而是通知
            //   Compose 子树把 showDiag 设回 false（切回主设置页）。这样 back 行为：
            //   主设置页 → 关闭整个窗口；诊断子页 → 退回主设置页；再按一次才关窗口。
            hideDiagnosticsRequested = { showDiag = false }
            onDispose {
                navStateProvider = { false }
                onShowDiagRequested = null
                hideDiagnosticsRequested = null
            }
        }

        // v0.1.0-beta5：跟随宿主的深色模式。
        // MiuixTheme(controller = ThemeController(colorSchemeMode = Dark/Light))
        // 会强制覆盖 Compose 子树的颜色方案，不再依赖宿主主题。
        val controller = remember(isDarkTheme) {
            ThemeController(colorSchemeMode = if (isDarkTheme) ColorSchemeMode.Dark else ColorSchemeMode.Light)
        }
        MiuixTheme(controller) {
            if (showDiag) {
                DiagnosticsScreen(onBack = { showDiag = false })
            } else {
                SettingsScreen(
                    config = cfg,
                    onSave = { ModelManager.saveConfig(it) },
                    onClose = onClose,
                    onOpenDiagnostics = { showDiag = true },
                    isDarkTheme = isDarkTheme,
                )
            }
        }
    }

    // ======================================================================
    // 自带 Lifecycle / ViewModelStore / SavedStateRegistry 的容器 FrameLayout
    // ======================================================================

    private class DecorRootView(context: Context) : FrameLayout(context),
        androidx.lifecycle.LifecycleOwner,
        ViewModelStoreOwner,
        androidx.savedstate.SavedStateRegistryOwner {

        val lifecycleRegistry = LifecycleRegistry(this)
        private val store = ViewModelStore()
        private val savedStateController = SavedStateRegistryController.create(this)

        init {
            // ★ 必须把 root 自身注册成 ViewTree 的所有者，
            //   否则 Compose 的 WindowRecomposer 找不到 LifecycleOwner 会抛
            //   "ViewTreeLifecycleOwner not found from DecorRootView" 并带崩宿主 App。
            setViewTreeLifecycleOwner(this)
            setViewTreeViewModelStoreOwner(this)
            setViewTreeSavedStateRegistryOwner(this)
            savedStateController.performAttach()
            savedStateController.performRestore(null)

            // 兜底 OnKeyListener —— 万一宿主 Activity 没 OnBackPressedDispatcher
            // （理论上 Activity 是 ComponentActivity 子类都会有），这里仍能吃掉 back。
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    // 自己处理：直接移除，不冒泡到父视图
                    (parent as? ViewGroup)?.removeView(this)
                    true
                } else false
            }
        }

        override val savedStateRegistry: SavedStateRegistry
            get() = savedStateController.savedStateRegistry

        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val viewModelStore: ViewModelStore get() = store

        override fun onSaveInstanceState(): android.os.Parcelable? {
            val bundle = android.os.Bundle()
            savedStateController.performSave(outBundle = bundle)
            return bundle
        }

        override fun onRestoreInstanceState(state: android.os.Parcelable?) {
            if (state is android.os.Bundle) {
                savedStateController.performRestore(state)
            }
        }
    }
}