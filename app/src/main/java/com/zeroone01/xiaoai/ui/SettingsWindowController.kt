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
        val moduleCtx = runCatching {
            hostActivity.createPackageContext(
                "com.zeroone01.xiaoai",
                Context.CONTEXT_IGNORE_SECURITY,
            )
        }.getOrElse {
            XLog.w("获取模块 Context 失败，使用宿主 Context：${it.message}")
            hostActivity
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
                    XLog.d("宿主按 back → dismiss 设置窗口（不冒泡到 Activity.finish）")
                    dismiss()
                }
            }
            dispatcher.addCallback(activity, callback)
            backCallback = callback
        }.onFailure { XLog.w("OnBackPressedDispatcher 注册失败，回退到传统 back 键拦截: ${it.message}") }
    }

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
        var showDiag by remember { mutableStateOf(false) }
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