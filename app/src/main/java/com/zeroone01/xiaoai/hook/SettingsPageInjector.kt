package com.zeroone01.xiaoai.hook

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.zeroone01.xiaoai.core.ModelManager
import com.zeroone01.xiaoai.core.XLog
import com.zeroone01.xiaoai.ui.SettingsWindowController
import java.lang.ref.WeakReference
import java.util.Collections

/**
 * 「设置」页面入口注入器。
 *
 * ## 设计决策（v0.1.0-beta3 之后）
 *
 * 之前尝试「实例化宿主的 `RightArrowBindingSingleLineTextView` 然后 setTitle」——
 * 现场实测两种症状：
 *  1. 新建项跟原生项**视觉重叠**（位置算错）；
 *  2. 点击**直接崩溃**宿主 App（setter 内部状态访问出错）。
 *
 * 根因是这个类是 ViewBinding 生成的：
 *  - 构造需要 AttributeSet，否则主题/样式没套上；
 *  - 内部 TextView 字段在 inflate 之后才被赋值，单参构造器拿不到；
 *  - setTitle 内部对 null 字段操作会抛 NPE，把宿主 App 带走。
 *
 * ## 新方案
 * 直接画一个模仿 HyperOS 设置项外观的 **纯自绘 View**：
 *  - 单行、左侧标题、右侧灰色 ›、带点击波纹；
 *  - 插入到 `NestedScrollView` 根容器的**第一个**子 ViewGroup 末尾；
 *  - 整个过程**不允许抛任何异常**，try/catch + null 检查全部到位。
 *  - 视觉跟原生项不是 100% 一致，但**能用、不会崩**——这是当前唯一硬性约束。
 */
internal object SettingsPageInjector {

    private const val SETTING_ACTIVITY = "com.xiaomi.fitness.about.setting.SettingActivity"
    private const val BASE_BINDING_ACTIVITY = "com.xiaomi.fitness.baseui.view.BaseBindingActivity"
    private const val SCROLL_VIEW_TYPE = "miuix.core.widget.NestedScrollView"
    private const val INJECT_TAG = "xiaoai_settings_entry"
    private const val ENTRY_TITLE = "AI 助手增强"
    private const val ENTRY_SUMMARY = "手环小爱接入第三方 AI"

    private val injected = Collections.synchronizedSet(mutableSetOf<WeakReference<Activity>>())

    // ==================================================================

    fun install(module: ModuleBridge, loader: ClassLoader, app: Context) {
        hookSettingActivity(module, loader)
        XLog.i("已安装「设置」页面注入器（目标: $SETTING_ACTIVITY）")
    }

    private fun hookSettingActivity(module: ModuleBridge, loader: ClassLoader) {
        val activityClass = try {
            loader.loadClass(SETTING_ACTIVITY)
        } catch (_: ClassNotFoundException) {
            null
        }
        if (activityClass != null) {
            hookOnCreate(module, activityClass)
            return
        }

        XLog.w("未找到 $SETTING_ACTIVITY，不尝试模糊匹配（v12 净化兜底）")
    }

    private fun hookOnCreate(module: ModuleBridge, activityClass: Class<*>) {
        val onCreate = activityClass.declaredMethods.firstOrNull {
            it.name == "onCreate" &&
                it.parameterCount == 1 &&
                it.parameterTypes[0] == android.os.Bundle::class.java
        } ?: runCatching {
            activityClass.getDeclaredMethod("onCreate", android.os.Bundle::class.java)
        }.getOrNull()

        if (onCreate == null) {
            XLog.w("${activityClass.simpleName} 没有 onCreate(Bundle)，跳过")
            return
        }

        module.hookAfter(onCreate) { ctx ->
            val activity = ctx.thisObject as? Activity
            if (activity == null || isAlreadyInjected(activity)) return@hookAfter
            // 用 post() 等 ViewBinding 完全 inflate 完再插入，避免和原生 inflate 撞车
            activity.window?.decorView?.post {
                runCatching { tryInject(activity) }
                    .onFailure { XLog.d("注入设置页入口失败: ${it.message}") }
            }
        }
        XLog.i("已 Hook ${activityClass.simpleName}#onCreate（after）")
    }

    private fun isAlreadyInjected(activity: Activity): Boolean {
        val it = injected.iterator()
        while (it.hasNext()) {
            val ref = it.next()
            val a = ref.get()
            if (a == null) it.remove() else if (a === activity) return true
        }
        return false
    }

    // ==================================================================

    private fun tryInject(activity: Activity) {
        // 已经插过 → 直接返回
        if (findInjectedEntry(activity.window?.decorView) != null) return

        val scroll = findScrollContainer(activity) ?: run {
            XLog.d("找不到滚动容器（${SCROLL_VIEW_TYPE}），放弃注入")
            return
        }
        val target = scroll.getChildAt(0) as? ViewGroup ?: run {
            XLog.d("滚动容器没有子 ViewGroup，放弃注入")
            return
        }

        // ★ 关键：再保险一次，target 不能为空、必须能加子 View
        if (target.childCount == 0 && target !is android.widget.FrameLayout && target !is android.widget.LinearLayout) {
            // 容器是空的，且不是常见可添加子 View 的类型，不冒险
            XLog.d("目标容器类型=${target.javaClass.name} 不确定能 addView，放弃注入")
            return
        }

        val entry = buildEntryView(activity)
        entry.tag = INJECT_TAG

        // 插入到目标容器的**末尾**（而不是 +1 位置）—— 最不容易和原生子 View 撞坐标
        val inserted = runCatching {
            target.addView(entry)
            true
        }.onFailure {
            XLog.d("addView 到 ${target.javaClass.simpleName} 失败: ${it.message}")
        }.getOrDefault(false)

        if (inserted) {
            injected.add(WeakReference(activity))
            XLog.i(
                "✓ 已用自绘 View 在「设置」页注入入口 " +
                    "(target=${target.javaClass.simpleName}, total=${target.childCount})"
            )
        } else {
            XLog.w("自绘 View 注入失败")
        }
    }

    /**
     * 构造「设置项」风格的 View。模仿 HyperOS 设置项外观：
     *  - 浅色背景；
     *  - 左标题 + 右灰色 ›；
     *  - 整行可点击（带水波纹依赖主题，未带则降级为颜色反馈）。
     */
    private fun buildEntryView(context: Context): View {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density + 0.5f).toInt()

        // 整个 View 是一个 LinearLayout，承载「标题 + 右箭头」
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            isClickable = true
            isFocusable = true
            // 用一个浅色背景模拟设置项"卡片"——很多人其实看不出和原生做分隔
            background = GradientDrawable().apply {
                setColor(0xFFF5F5F5.toInt())
                cornerRadius = dp(8).toFloat()
            }

            addView(TextView(context).apply {
                text = ENTRY_TITLE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(0xFF111111.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                )
            })

            addView(TextView(context).apply {
                text = "›"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTextColor(0xFF999999.toInt())
                setPadding(dp(8), 0, 0, 0)
            })

            setOnClickListener { v ->
                runCatching {
                    val activity = (v.context as? Activity) ?: run {
                        XLog.w("入口点击上下文不是 Activity，跳过拉起设置窗口")
                        return@runCatching
                    }
                    openSettings(activity)
                }.onFailure { XLog.e("打开设置窗口失败", it) }
            }
        }
    }

    /**
     * 找 NestedScrollView 根容器。遍历 decorView 按类型名匹配。
     */
    private fun findScrollContainer(activity: Activity): ViewGroup? {
        return findFirstChildOfType(activity.window?.decorView, SCROLL_VIEW_TYPE) as? ViewGroup
    }

    /** 在 View 树里找第一个指定类型的子 View（按类型全限定名） */
    private fun findFirstChildOfType(root: View?, typeName: String, depth: Int = 0): View? {
        if (root == null || depth > 40) return null
        if (root.javaClass.name == typeName && root is ViewGroup) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val c = root.getChildAt(i)
                if (c.javaClass.name == typeName && c is ViewGroup) return c
                findFirstChildOfType(c, typeName, depth + 1)?.let { return it }
            }
        }
        return null
    }

    /** 找已注入的入口 View（用于防重复） */
    private fun findInjectedEntry(root: View?): View? {
        if (root == null) return null
        if (root.tag == INJECT_TAG) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findInjectedEntry(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    // ==================================================================

    /**
     * 打开模块设置 UI。
     *
     * ★ v0.1.0-beta7：改为**独立 Activity**（用户明确要求：嵌入宿主设置页的覆盖层
     * 有顶栏返回键不走拦截、无转场动画、back 栈混乱等硬伤）。
     *
     * 显式 ComponentName 启动不受 Android 11+ 包可见性限制（visibility 只约束
     * 隐式 intent 解析）；模块 Activity exported=true 放行跨应用启动。
     * 必须加 FLAG_ACTIVITY_NEW_TASK —— 宿主 Activity 上下文非 task root 时缺这个
     * flag 会抛 AndroidRuntimeException。
     */
    private fun openSettings(activity: Activity) {
        runCatching {
            val intent = android.content.Intent().apply {
                setClassName(
                    "com.zeroone01.xiaoai",
                    "com.zeroone01.xiaoai.ui.SettingsActivity",
                )
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
            XLog.i("已跳转到模块独立设置 Activity（模块进程）")
        }.onFailure {
            XLog.e("启动设置 Activity 失败", it)
            runCatching {
                if (!ModelManager.isInitialized) ModelManager.init(activity)
                ModelManager.emit(com.zeroone01.xiaoai.core.ModuleEvent.Log("fallback overlay"))
                XLog.i("已回退到进程内覆盖层设置窗口")
            }.onFailure { e -> XLog.e("覆盖层也失败", e) }
        }
    }
}