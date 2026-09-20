package com.zeroone01.xiaoai.hook

import android.app.Activity
import android.content.Context
import android.util.TypedValue
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
 * ## 逆向结论（基于小米运动健康 3.59.1，classes10.dex / classes13.dex）
 *
 * 设置页 `com.xiaomi.fitness.about.setting.SettingActivity`：
 *  - 继承 `com.xiaomi.fitness.baseui.view.BaseBindingActivity`，使用 **ViewBinding**；
 *  - binding 类是 `Li00;`（混淆后名），根容器字段为 `N`，类型 `miuix.core.widget.NestedScrollView`；
 *  - 其余字段全部是小米自研的「设置项」控件：
 *
 *    | 字段 | 类型 | 说明 |
 *    |---|---|---|
 *    | `C` | `LinearLayout` | 分组内容容器 |
 *    | `B` | `ConstraintLayout` | 顶层布局 |
 *    | `L` `K` `E` `H` `M` | `RightArrowBindingSingleLineTextView` | 单行「文字 + 右箭头」项 |
 *    | `I` `J` `F` | `SwitchButtonBindingTwoLineTextView` | 「两行文字 + 开关」项 |
 *    | `A` `O` | `RightArrowTwoLineTextView` | 双行「文字 + 右箭头」项 |
 *    | `G` | `View` | 分隔线 |
 *
 * 其中 `RightArrowBindingSingleLineTextView` 继承 `RightArrowSingleLineTextView`，
 * 对外 API（来自 classes13.dex）：
 *  - `setTitle(String / Int)`
 *  - `setRemindText(String / SpannableString / Int)` —— 右侧灰色小字
 *  - `setIcon(Drawable / Int / String)`
 *  - `setRightTextWithDot(String)`
 *
 * ## 注入策略
 * 本注入器**不新建自己的 View**，而是：
 *  1. Hook `SettingActivity.onCreate(Bundle)` 的 after；
 *  2. 通过 `BaseBindingActivity.getMBinding()` 拿到 binding 对象，反射取出根容器 `N`；
 *  3. 反射 **实例化宿主的 `RightArrowBindingSingleLineTextView`**，调用它的 `setTitle` /
 *     `setRemindText` 设置文案，再挂上 `OnClickListener`；
 *  4. 把它 addView 到根容器（`NestedScrollView` → 唯一的子 `LinearLayout`）里。
 *
 * 这样得到的是一个**货真价实的宿主原生控件**：主题、字体、水波纹、暗黑模式、
 * 内边距全部自动与「账号与安全」「清理缓存」等原生项完全一致，不会有任何割裂感。
 *
 * ## 兜底
 * 若上述任一环节失败（比如换了版本、类名/字段名变了），自动降级为
 * [fallbackInjectByText]：遍历 View 树找到原生设置项的公共父容器，插入自绘 View。
 * 两条路都失败时只记日志，绝不崩溃宿主。
 */
internal object SettingsPageInjector {

    /** 设置页 Activity 的类名（3.59.1 实测；不同版本可能不同，用前缀匹配兜底） */
    private const val SETTING_ACTIVITY = "com.xiaomi.fitness.about.setting.SettingActivity"

    /** ViewBinding 基类，提供 getMBinding()；用它比直接用具体 binding 类名更抗混淆 */
    private const val BASE_BINDING_ACTIVITY = "com.xiaomi.fitness.baseui.view.BaseBindingActivity"

    /** 宿主原生「单行 + 右箭头」设置项控件 */
    private const val ITEM_CLASS = "com.xiaomi.fitness.widget.RightArrowBindingSingleLineTextView"

    private const val INJECT_TAG = "xiaoai_settings_entry"
    private const val ENTRY_TITLE = "AI 助手增强"
    private const val ENTRY_SUMMARY = "手环小爱接入第三方 AI"

    private val injected = Collections.synchronizedSet(mutableSetOf<WeakReference<Activity>>())

    // ==================================================================

    /**
     * 安装注入器（现代 API 版本）。
     *
     * @param module 模块实例，提供 Hook 能力
     * @param loader 宿主 ClassLoader（由 PackageLoadedParam 提供）
     * @param app    宿主 Application（由 PackageReadyParam 提供），用作 View 的 Context
     */
    fun install(module: ModuleBridge, loader: ClassLoader, app: Context) {
        hookSettingActivity(module, loader)
        XLog.i("已安装「设置」页面注入器（目标: $SETTING_ACTIVITY）")
    }

    private fun hookSettingActivity(module: ModuleBridge, loader: ClassLoader) {
        val activityClass = Reflect.findClass(SETTING_ACTIVITY, loader)

        if (activityClass != null) {
            hookOnCreate(module, activityClass)
            return
        }

        // 模糊匹配：某些版本类名可能不同，按包名 + 类名后缀找
        XLog.w("未找到 $SETTING_ACTIVITY，尝试模糊匹配…")
        DexClassScanner.scan(loader)
            .filter { it.endsWith("about.setting.SettingActivity") || it.endsWith("SettingActivity") }
            .forEach { name ->
                Reflect.findClass(name, loader)?.let { hookOnCreate(module, it) }
            }
    }

    private fun hookOnCreate(module: ModuleBridge, activityClass: Class<*>) {
        // 只挂真正的 onCreate(Bundle)，避免把 onCreate(...) 的重载和
        // 各种 Hilt/Binding 版本全挂一遍导致重复注入。
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

        val ok = HookCompat.hook(
            module, onCreate,
            after = { ctx ->
                val activity = ctx.thisObject as? Activity ?: return@hook
                if (isAlreadyInjected(activity)) return@hook
                // 等布局完成（ViewBinding 已 inflate 完）
                activity.window?.decorView?.post {
                    runCatching { tryInject(activity) }
                        .onFailure { XLog.d("注入设置页入口失败: ${it.message}") }
                }
            },
            tag = "${activityClass.simpleName}#onCreate",
        )
        if (ok) {
            XLog.i("已 Hook ${activityClass.simpleName}#onCreate")
        } else {
            XLog.w("Hook ${activityClass.simpleName}#onCreate 失败")
        }
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
        val decor = activity.window?.decorView ?: return
        if (decor.findViewWithTag<View>(INJECT_TAG) != null) return

        // ---------- 路线 1：用宿主原生控件注入（首选） ----------
        if (injectNativeItem(activity, decor)) {
            injected.add(WeakReference(activity))
            return
        }

        // ---------- 路线 2：自绘 View 兜底 ----------
        XLog.w("原生控件注入失败，降级为自绘 View")
        if (fallbackInjectByText(activity, decor)) {
            injected.add(WeakReference(activity))
        }
    }

    /**
     * 首选方案：实例化宿主的 `RightArrowBindingSingleLineTextView`，
     * 外观与「账号与安全」「清理缓存」等原生设置项 100% 一致。
     */
    private fun injectNativeItem(activity: Activity, decor: View): Boolean {
        val loader = activity.classLoader

        // 1) 拿 binding：BaseBindingActivity.getMBinding()
        //    用 BaseBindingActivity 做锚点比直接用混淆后的具体 binding 类名抗版本差异。
        val bindingActivityClass = Reflect.findClass(BASE_BINDING_ACTIVITY, loader)
        val binding = Reflect.callMethod(activity, "getMBinding")
            ?: bindingActivityClass?.methods?.firstOrNull {
                it.parameterCount == 0 && it.returnType.name.contains("databinding")
            }?.let { m ->
                runCatching { m.isAccessible = true; m.invoke(activity) }.getOrNull()
            }
            ?: return false

        // 2) 从 binding 里找 NestedScrollView（根滚动容器）
        val scroll = findFieldByTypeOrName(binding, "miuix.core.widget.NestedScrollView", "N")
            as? ViewGroup ?: return false

        // 3) 找原生单行设置项，用它所属的父容器作为插入目标
        val itemClass = Reflect.findClass(ITEM_CLASS, loader) ?: run {
            XLog.d("找不到原生设置项控件 $ITEM_CLASS")
            return false
        }

        val nativeItem = findFirstChildOfType(scroll, itemClass) ?: return false
        val parent = nativeItem.parent as? ViewGroup ?: return false

        // 4) 实例化一个新的设置项（用宿主 Class，所以主题/样式全自动）
        val newItem = Reflect.newInstance(itemClass, activity) as? View
            ?: Reflect.newInstance(itemClass, activity, null) as? View
            ?: return false

        newItem.tag = INJECT_TAG

        // 5) 设置文案（setTitle / setRemindText 均来自 RightArrowSingleLineTextView）
        if (Reflect.callMethod(newItem, "setTitle", ENTRY_TITLE) == null) {
            XLog.d("setTitle 未生效，尝试 setTitleRes / setText")
            Reflect.callMethod(newItem, "setTitleRes", ENTRY_TITLE)
            Reflect.callMethod(newItem, "setText", ENTRY_TITLE)
        }

        if (Reflect.callMethod(newItem, "setRemindText", ENTRY_SUMMARY) == null) {
            Reflect.callMethod(newItem, "setReminText", ENTRY_SUMMARY)
        }

        // 6) 点击打开模块设置窗口
        newItem.setOnClickListener { v -> openSettings(v.context) }
        newItem.isClickable = true

        // 7) 插入：紧跟原生第一项之后
        return runCatching {
            val idx = (0 until parent.childCount)
                .firstOrNull { parent.getChildAt(it) === nativeItem } ?: 0
            parent.addView(newItem, (idx + 1).coerceIn(0, parent.childCount))
            XLog.i("✓ 已用宿主原生控件在「设置」页注入入口 (parent=${parent.javaClass.simpleName}, index=${idx + 1})")
            true
        }.getOrElse {
            XLog.w("addView 原生项失败: ${it.message}")
            false
        }
    }

    /**
     * 兜底方案：在 View 树里找原生设置项（`RightArrowBindingSingleLineTextView`）的
     * 公共父容器，插入自绘 View。
     */
    private fun fallbackInjectByText(activity: Activity, decor: View): Boolean {
        val loader = activity.classLoader
        val itemClass = Reflect.findClass(ITEM_CLASS, loader) ?: return false

        val root = decor as? ViewGroup ?: return false
        val anchor = findFirstChildOfType(root, itemClass) ?: return false
        val parent = anchor.parent as? ViewGroup ?: return false

        val entry = buildFallbackView(activity).apply { tag = INJECT_TAG }
        return runCatching {
            val idx = (0 until parent.childCount)
                .firstOrNull { parent.getChildAt(it) === anchor } ?: 0
            parent.addView(entry, (idx + 1).coerceIn(0, parent.childCount))
            XLog.i("✓ 已用兜底自绘 View 注入设置页入口")
            true
        }.getOrElse {
            XLog.w("兜底注入失败: ${it.message}")
            false
        }
    }

    private fun buildFallbackView(context: Context): View {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density + 0.5f).toInt()

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true

            addView(TextView(context).apply {
                text = ENTRY_TITLE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            addView(TextView(context).apply {
                text = "›"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTextColor(android.graphics.Color.parseColor("#999999"))
            })

            setOnClickListener { v -> openSettings(v.context) }
        }
    }

    // ==================================================================

    /** 在 View 树里找第一个指定类型的子 View */
    private fun findFirstChildOfType(root: View, clazz: Class<*>, depth: Int = 0): View? {
        if (depth > 40) return null
        if (clazz.isInstance(root) && root !is ViewGroup) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val child = root.getChildAt(i)
                if (clazz.isInstance(child)) return child
                findFirstChildOfType(child, clazz, depth + 1)?.let { return it }
            }
        }
        return null
    }

    /** 反射从对象里取字段：先按名字找，找不到再按类型名匹配第一个 */
    private fun findFieldByTypeOrName(obj: Any, typeName: String, preferredName: String): Any? {
        // 按名字（含继承链）
        Reflect.get<Any>(obj, preferredName)?.let { return it }

        // 按类型（含父类）
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            c.declaredFields.firstOrNull { it.type.name == typeName }?.let { f ->
                runCatching {
                    f.isAccessible = true
                    return f.get(obj)
                }
            }
            c = c.superclass
        }
        return null
    }

    // ==================================================================

    /**
     * 打开模块设置 UI。
     *
     * 用 [SettingsWindowController] 在 com.mi.health 进程内以 WindowManager 子窗口渲染，
     * 与 Hook 同进程、同 Context、同内存实例，零延迟。
     */
    private fun openSettings(hostContext: Context) {
        ModelManager.ensureInit(hostContext)
        runCatching {
            SettingsWindowController.show(hostContext)
            XLog.i("已在 com.mi.health 进程内拉起设置窗口")
        }.onFailure { XLog.e("SettingsWindowController.show() 失败", it) }
    }
}
