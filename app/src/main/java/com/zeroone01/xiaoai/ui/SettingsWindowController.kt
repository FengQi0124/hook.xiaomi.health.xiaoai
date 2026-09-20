package com.zeroone01.xiaoai.ui

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.ViewGroup
import android.widget.FrameLayout
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
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 在宿主进程（com.mi.health）里以 [WindowManager] + [ComposeView] 的方式
 * 直接渲染设置 UI —— 不再需要单独的 Activity / APK 进程。
 *
 * ## 设计目标
 *  - 一切跑在 com.mi.health 进程里，用户杀后台 = 杀掉小米运动健康 = 本来就在用，模块随宿主生死。
 *  - 不需要 `SYSTEM_ALERT_WINDOW` 权限，使用 [WindowManager.LayoutParams.TYPE_APPLICATION]。
 *  - 取代 `SettingsActivity` 和 `LauncherProxyActivity`，模块 APK 不再声明任何 Activity。
 *
 * ## 生命周期
 *  - 由 LSPosed 注入器（`MinePageInjector`）在用户点击「我的」页面注入项时调用 [show]；
 *  - 手动 back 键或点击关闭按钮调用 [dismiss]。
 */
object SettingsWindowController {

    private const val TAG = "SettingsWindow"

    @Volatile private var attached: DecorRootView? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 在宿主 Context 上拉起设置 UI。
     * 同一时刻只允许一个实例，重复调用等价于 dismiss + show。
     */
    fun show(hostContext: Context) {
        // 强制主线程
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { show(hostContext) }
            return
        }

        // 模块自己的 Context —— 取主题、字符串、density 等资源（确保 Miuix 主题生效）
        val moduleCtx = runCatching {
            hostContext.createPackageContext(
                "com.zeroone01.xiaoai",
                Context.CONTEXT_IGNORE_SECURITY,
            )
        }.getOrElse {
            XLog.w("获取模块 Context 失败，使用宿主 Context：${it.message}")
            hostContext
        }

        ModelManager.ensureInit(hostContext)

        dismissInternal()

        val appCtx = hostContext.applicationContext
        val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val root = DecorRootView(moduleCtx).apply {
            // 自己持有 back 键
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundColor(Color.parseColor("#FFF7F7F8"))
            onBack = { dismiss() }
        }

        // 用 ComposeView 渲染 Miuix UI，并提供「Activity 等价」的 ViewTree 所有者
        val composeView = ComposeView(moduleCtx).apply {
            setViewTreeLifecycleOwner(root)
            setViewTreeViewModelStoreOwner(root)
            setViewTreeSavedStateRegistryOwner(root)
            setContent { SettingsContent(onClose = { dismiss() }) }
        }
        root.addView(
            composeView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        val lp = WindowManager.LayoutParams().apply {
            // 关键：TYPE_APPLICATION 不需要任何权限，普通应用 Context 即可创建。
            // TYPE_SYSTEM_ALERT 等需要 SYSTEM_ALERT_WINDOW 权限，这里不行。
            type = WindowManager.LayoutParams.TYPE_APPLICATION
            // 全屏的「子窗口」
            flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            gravity = Gravity.TOP or Gravity.START
            format = PixelFormat.TRANSLUCENT
            // 让窗口吃 back 键
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            title = "XiaoAiSettingsWindow"
        }

        try {
            wm.addView(root, lp)
            attached = root
            // 触发 lifecycle STARTED / RESUMED 让 Compose 进入正常组合
            root.lifecycleRegistry.currentState = Lifecycle.State.RESUMED
            XLog.i("设置窗口已附加到 com.mi.health 进程 (TYPE_APPLICATION)")
        } catch (t: Throwable) {
            XLog.e("添加 setWindow 到宿主进程失败", t)
            // 兜底：把 ComposeView 当普通 View 用宿主 DecorView 接管（理论上不会到这）
            runCatching {
                (hostContext as? android.app.Activity)?.let { act ->
                    act.window.decorView.findViewById<ViewGroup>(android.R.id.content)
                        ?.addView(root, ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        ))
                    root.lifecycleRegistry.currentState = Lifecycle.State.RESUMED
                    attached = root
                }
            }.onFailure { XLog.e("宿主 Activity 兜底也失败", it) }
        }
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
        runCatching {
            root.lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
            val wm = root.context.applicationContext
                .getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.removeView(root)
            XLog.i("设置窗口已移除")
        }.onFailure {
            XLog.w("移除设置窗口失败: ${it.message}")
        }
    }

    /** 是否已显示，用于 UI 提示和防重复 */
    fun isShowing(): Boolean = attached != null

    // ======================================================================
    // Compose 入口 —— 取代 SettingsActivity 的 setContent{}
    //
    @Composable
    private fun SettingsContent(onClose: () -> Unit) {
        val cfg by ModelManager.configFlow.collectAsState()
        var showDiag by remember { mutableStateOf(false) }
        MiuixTheme {
            if (showDiag) {
                DiagnosticsScreen(onBack = { showDiag = false })
            } else {
                SettingsScreen(
                    config = cfg,
                    onSave = { ModelManager.saveConfig(it) },
                    onClose = onClose,
                    onOpenDiagnostics = { showDiag = true },
                )
            }
        }
    }

    // ======================================================================
    // 自带 Lifecycle / ViewModelStore / SavedStateRegistry 的容器 FrameLayout
    //
    private class DecorRootView(context: Context) : FrameLayout(context),
        androidx.lifecycle.LifecycleOwner,
        ViewModelStoreOwner,
        SavedStateRegistry.SavedStateProvider,
        androidx.savedstate.SavedStateRegistryOwner {

        val lifecycleRegistry = LifecycleRegistry(this)
        private val store = ViewModelStore()
        private val savedStateController = SavedStateRegistryController.create(this)

        init {
            // ★ 关键：把自己注册为 ViewTree 的所有者。
            //
            // Compose 内部的 WindowRecomposer 是从 View 树根（这里是 DecorRootView）
            // 向上找 ViewTreeLifecycleOwner 的。DecorRootView 本身实现了 LifecycleOwner
            // 接口但接口不会被 Compose 自动发现——必须显式调用 setViewTree... 注入。
            // 不然 AbstractComposeView.resolveComposeViewContext 会报
            // "ViewTreeLifecycleOwner not found from DecorRootView"，把宿主 App 一起带崩。
            setViewTreeLifecycleOwner(this)
            setViewTreeViewModelStoreOwner(this)
            setViewTreeSavedStateRegistryOwner(this)

            savedStateController.performAttach()
            savedStateController.performRestore(null)
            // 注册 SavedStateProvider：API 1.x 需要在 SavedStateRegistry 上注册。
            // key 用一个稳定的常量即可；本 View 不持久化任何状态。
            savedStateController.savedStateRegistry.registerSavedStateProvider(
                KEY_SAVED_STATE,
                this,
            )
            // 监听 back 键
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    onBack?.invoke()
                    true
                } else false
            }
        }

        private companion object {
            const val KEY_SAVED_STATE = "xiaoai_settings_window_state"
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

        /** 供外部设置 back 回调 */
        var onBack: (() -> Unit)? = null

        /** 必须实现：返回本 View 保存到父 SavedStateRegistry 的内容 */
        override fun saveState(): android.os.Bundle = android.os.Bundle()
    }
}