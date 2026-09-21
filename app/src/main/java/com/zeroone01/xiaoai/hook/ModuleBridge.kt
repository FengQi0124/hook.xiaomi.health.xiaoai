package com.zeroone01.xiaoai.hook

import java.lang.reflect.Constructor
import java.lang.reflect.Method

/**
 * module 能力抽象接口（对接 `io.github.libxposed.api.XposedModule`）。
 *
 * - [XiaoAiHookEntry] 本身继承 [XposedModule] 并实现本接口；
 * - [SettingsPageInjector] 通过本接口完成两种钩子注入，
 *   避免直接 import XposedModule（XposedModule 在模块进程侧没有实现类）。
 *
 * 所有钩子都是「after」形态：hook 设置页 onCreate 时已经走完 ViewBinding inflate，
 * 设置页 hook 完成后再去追加自绘入口 View。
 */
interface ModuleBridge {

    /**
     * Hook 某个方法的 after 钩子。
     *
     * @param method  要 hook 的方法（含构造器）
     * @param after   在原方法成功执行后调用；含 thisObject、args、result
     */
    fun hookAfter(method: Method, after: (HookCtx) -> Unit): Boolean

    /**
     * Hook 某个构造器的 after 钩子。
     */
    fun hookAfter(ctor: Constructor<*>, after: (HookCtx) -> Unit): Boolean
}

/** 回调上下文；thisObject / args / 方法执行结果 */
data class HookCtx(
    val thisObject: Any?,
    val args: Array<out Any?>,
    val result: Any?,
)
