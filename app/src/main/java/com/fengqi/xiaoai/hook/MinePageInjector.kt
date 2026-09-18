package com.fengqi.xiaoai.hook

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.fengqi.xiaoai.core.XLog
import com.fengqi.xiaoai.ui.SettingsWindowController
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.ref.WeakReference
import java.util.Collections

/**
 * 「我的」页面入口注入器。
 *
 * ## 逆向线索
 * 「我的」页面在小米运动健康里通常有两种实现：
 *  - 传统 View 体系：Fragment 的 `onViewCreated` / `onCreateView`；
 *  - Compose：`AndroidComposeView` 承载，节点通过语义树暴露。
 *
 * 由于无法拿到该 App 的完整源码，这里采用 **「不依赖具体类名」的注入策略**：
 *
 *  1. Hook `Activity.onResume`，在每次页面切换后扫描当前 Activity 的 View 树；
 *  2. 在 View 树里寻找**「我的路线库」和「App设置」两个 TextView 的公共父容器**；
 *  3. 在二者之间插入我们自己的一个 View（样式模仿 MIUI 设置项）；
 *  4. 用 WeakReference + 标记 tag 避免重复注入。
 *
 * 这样即便页面是 Compose 渲染的（最终仍是 View 树上的 AndroidComposeView），
 * 只要两个锚点文字能被找到，注入就成立。找不到时不会报错，只是不注入。
 *
 * ## 为什么用「锚点文字」而不是指定 View id
 * View id 在不同版本间会变（`0x7f0a1234`），但用户可见的中文文案几乎不会变。
 * 用文案做锚点是跨版本最稳的做法。
 */
internal object MinePageInjector {

    /** 「我的」页面里位于插入点前后的两个锚点文案 */
    private val ANCHOR_BEFORE = listOf("我的路线库", "路线库", "我的线路")
    private val ANCHOR_AFTER = listOf("App设置", "应用设置", "设置")

    private const val INJECT_TAG = "xiaoai_injected_entry"

    /** 模块自己的包名（必须写死，不能用 hostContext.packageName 推断） */
    private const val MODULE_PACKAGE = "com.fengqi.xiaoai"

    /** 已注入的 Activity，弱引用避免泄漏 */
    private val injected = Collections.synchronizedSet(mutableSetOf<WeakReference<Activity>>())

    private val mainHandler = Handler(Looper.getMainLooper())

    fun install(lpparam: XC_LoadPackage.LoadPackageParam, ctx: Context) {
        hookActivityResume(lpparam)
        XLog.i("已安装「我的」页面注入器（锚点: ${ANCHOR_BEFORE.first()} / ${ANCHOR_AFTER.first()}）")
    }

    private fun hookActivityResume(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        if (isAlreadyInjected(activity)) return
                        // 延迟一点，等页面完成布局
                        mainHandler.postDelayed({
                            runCatching { tryInject(activity) }
                                .onFailure { XLog.d("注入尝试失败: ${it.message}") }
                        }, 350L)
                    }
                }
            )
        }.onFailure { XLog.w("Hook Activity.onResume 失败: ${it.message}") }
    }

    private fun isAlreadyInjected(activity: Activity): Boolean {
        val it = injected.iterator()
        while (it.hasNext()) {
            val ref = it.next()
            val a = ref.get()
            if (a == null) it.remove()
            else if (a === activity) return true
        }
        return false
    }

    // ==================================================================

    private fun tryInject(activity: Activity) {
        val decor = activity.window?.decorView ?: return
        if (decor.findViewWithTag<View>(INJECT_TAG) != null) return

        val root = decor as? ViewGroup ?: return
        val beforeNode = findViewByTexts(root, ANCHOR_BEFORE) ?: return
        val afterNode = findViewByTexts(root, ANCHOR_AFTER)

        val parent = beforeNode.parent as? ViewGroup ?: return

        // 计算插入位置：紧跟「我的路线库」之后
        val beforeIndex = (0 until parent.childCount).firstOrNull { parent.getChildAt(it) === beforeNode }
            ?: return

        var insertIndex = beforeIndex + 1
        if (afterNode != null && afterNode.parent === parent) {
            val afterIndex = (0 until parent.childCount).firstOrNull { parent.getChildAt(it) === afterNode }
            if (afterIndex != null && afterIndex > beforeIndex) {
                // 插在「App设置」之前（即两者之间），保证顺序：
                // 我的路线库 → AI 助手设置 → App设置
                insertIndex = afterIndex
            }
        }

        val entry = buildEntryView(activity, beforeNode)
        entry.tag = INJECT_TAG

        runCatching {
            parent.addView(entry, insertIndex.coerceIn(0, parent.childCount))
            injected.add(WeakReference(activity))
            XLog.i("已在「我的」页面注入 AI 助手设置入口 (index=$insertIndex, parent=${parent.javaClass.simpleName})")
        }.onFailure {
            XLog.w("注入失败: ${it.message}")
        }
    }

    /**
     * 深度优先查找文案匹配的 TextView。
     * 只匹配「叶子级」文本节点，避免匹配到容器上的聚合文本。
     */
    private fun findViewByTexts(root: View, texts: List<String>, depth: Int = 0): View? {
        if (depth > 30) return null
        if (root is TextView) {
            val content = root.text?.toString()?.trim().orEmpty()
            if (texts.any { content == it || content.endsWith(it) }) return root
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findViewByTexts(root.getChildAt(i), texts, depth + 1)?.let { return it }
            }
        }
        return null
    }

    /**
     * 构造与 MIUI 设置项风格一致的入口 View。
     *
     * 由于宿主 App 的主题/字体不可知，这里手动绘制：
     *  圆角白底容器 + 左侧图标块 + 标题 + 右侧箭头。
     * 高度、内边距用 dp 换算，保证不同屏幕密度下一致。
     */
    private fun buildEntryView(context: Context, sample: View): View {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density + 0.5f).toInt()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            isFocusable = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.WHITE)
            }
            // 水波纹反馈
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            if (outValue.resourceId != 0) {
                foreground = context.getDrawable(outValue.resourceId)
            }
        }

        // 左侧图标（用文字符号代替图片资源，避免依赖宿主 drawable）
        val icon = TextView(context).apply {
            text = "\uD83E\uDD16" // 🤖
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
        }
        container.addView(icon, LinearLayout.LayoutParams(dp(32), dp(32)).apply {
            marginEnd = dp(12)
        })

        // 标题
        val title = TextView(context).apply {
            text = "AI 助手设置"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(inheritTextColor(sample))
        }
        container.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // 右侧描述 + 箭头
        val arrow = TextView(context).apply {
            text = "›"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(Color.parseColor("#999999"))
            gravity = Gravity.CENTER
        }
        container.addView(arrow)

        container.setOnClickListener { v ->
            openSettings(v.context)
        }

        return container
    }

    /** 尽量沿用锚点 TextView 的文字颜色，让注入项融合进页面 */
    private fun inheritTextColor(sample: View): Int {
        val tv = sample as? TextView ?: return Color.parseColor("#1A1A1A")
        return runCatching {
            val colors = tv.textColors
            colors?.getColorForState(tv.drawableState, Color.BLACK) ?: Color.BLACK
        }.getOrDefault(Color.BLACK)
    }

    // ==================================================================

    /**
     * 打开设置页。
     *
     * v1.2.0：完全抛弃独立 Activity 进程，直接在 com.mi.health 宿主进程里用
     * [SettingsWindowController] 拉一个 [android.view.WindowManager] 子窗口渲染 UI。
     * 好处：
     *  - 用户杀后台 = 杀掉小米运动健康 = 本来就在用，没有"模块被独立杀"的风险；
     *  - 不需要 SYSTEM_ALERT_WINDOW 权限；
     *  - 与 Hook 进程同一 Context / 同一 XLog / 同一 ModelManager 内存实例，零延迟。
     */
    private fun openSettings(hostContext: Context) {
        ModelManager.ensureInit(hostContext)
        runCatching {
            SettingsWindowController.show(hostContext)
            XLog.i("已在 com.mi.health 进程内拉起设置窗口")
        }.onFailure { XLog.e("SettingsWindowController.show() 失败", it) }
    }
}
