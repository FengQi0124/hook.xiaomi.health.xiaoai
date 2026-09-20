package com.zeroone01.xiaoai.hook

import com.zeroone01.xiaoai.core.XLog
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method

/**
 * Hook 封装层 —— 把 libxposed API 102 的拦截器链 API
 * 包装成接近 legacy `XC_MethodHook` 的 before/after 写法。
 *
 * ## API 102 的真实模型（已按官方 api-102.0.0.aar 反编译核对）
 *
 * ```java
 * // XposedInterface（XposedModule 继承 XposedInterfaceWrapper，它实现了本接口）
 * HookBuilder hook(Executable)
 * class HookBuilder {
 *     HookBuilder setPriority(int)
 *     HookBuilder setExceptionMode(ExceptionMode)
 *     HookBuilder setId(String)
 *     HookHandle intercept(Hooker)
 * }
 * interface Hooker {
 *     Object intercept(Chain chain) throws Throwable
 * }
 * interface Chain {
 *     Executable getExecutable()
 *     Object getThisObject()
 *     List<Object> getArgs()          // ← 注意是 List，不是数组
 *     Object getArg(int)
 *     Object proceed() throws Throwable
 *     Object proceed(Object[]) throws Throwable
 *     Object proceedWith(Object) throws Throwable
 *     Object proceedWith(Object, Object[]) throws Throwable
 * }
 * ```
 *
 * ## 与 legacy 的关键差异
 *
 * | legacy `XC_MethodHook` | API 102 |
 * |---|---|
 * | `beforeHookedMethod` / `afterHookedMethod` 两个独立回调 | **一次** `intercept(chain)`，前后两段 |
 * | `param.thisObject` | `chain.getThisObject()` |
 * | `param.args`（Object[]） | `chain.getArgs()`（**List**） |
 * | `param.result` | 就是 `chain.proceed()` 的返回值，**不存在独立 getter** |
 * | `param.setResult(x)` | `chain.proceedWith(x)` |
 * | `param.setThrowable(t)` | proceed 抛异常，或调 `proceedWith` 前自行抛出 |
 *
 * 本类用 [BeforeAfterHooker] 在一次 intercept 里先跑 before、再 proceed、最后跑 after，
 * 把差异重新抹平，让 [InterceptEngine] 等业务代码无需感知底层形态。
 *
 * ## 异常处理
 * Hook 回调里抛异常 = 宿主 App 崩溃。所有业务回调统一被 try/catch 包住，
 * 只记日志。**唯一例外**：`proceed()` 自己抛出的异常必须原样向上传递，
 * 否则会改变宿主程序的正常控制流。
 */
internal object HookCompat {

    /**
     * 把一个方法/构造器挂上 before/after 回调。
     *
     * @param before 在原方法执行前调用
     * @param after  在原方法执行后调用（无论是否抛异常都会执行）
     * @return 成功返回 true
     */
    fun hook(
        module: ModuleBridge,
        executable: Executable,
        priority: Int = XposedInterface.PRIORITY_DEFAULT,
        before: ((HookContext) -> Unit)? = null,
        after: ((HookContext) -> Unit)? = null,
        tag: String = "",
    ): Boolean {
        return runCatching {
            runCatching { executable.isAccessible = true }
            val builder = module.hookExecutable(executable)
            if (priority != XposedInterface.PRIORITY_DEFAULT) {
                builder.setPriority(priority)
            }
            // 关键：使用 PROTECTIVE 异常模式，Hook 内部异常不会波及宿主
            runCatching {
                builder.setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            }
            runCatching { if (tag.isNotEmpty()) builder.setId(tag) }
            builder.intercept(BeforeAfterHooker(before, after, tag))
            true
        }.getOrElse {
            XLog.d("Hook ${executable.name} 失败: ${it.message}")
            false
        }
    }

    /**
     * Hook 一个类的所有同名方法。
     *
     * @param paramCount 限定参数个数；-1 表示不限
     * @return 成功挂上的数量
     */
    fun hookMethod(
        module: ModuleBridge,
        clazz: Class<*>,
        methodName: String,
        paramCount: Int = -1,
        priority: Int = XposedInterface.PRIORITY_DEFAULT,
        before: ((HookContext) -> Unit)? = null,
        after: ((HookContext) -> Unit)? = null,
    ): Int = Reflect.findMethodsByName(clazz, methodName)
        .filter { paramCount < 0 || it.parameterCount == paramCount }
        .count {
            hook(module, it, priority, before, after, "${clazz.simpleName}#$methodName")
        }

    /** Hook 构造器 */
    fun hookConstructor(
        module: ModuleBridge,
        clazz: Class<*>,
        paramCount: Int = -1,
        before: ((HookContext) -> Unit)? = null,
        after: ((HookContext) -> Unit)? = null,
    ): Int = clazz.declaredConstructors
        .filter { paramCount < 0 || it.parameterCount == paramCount }
        .count {
            hook(module, it, before = before, after = after, tag = "${clazz.simpleName}<init>")
        }

    /** 回调所处阶段 */
    enum class Phase { BEFORE, AFTER }

    /**
     * 回调上下文 —— 把 [XposedInterface.Chain] 适配成 legacy `MethodHookParam` 的用法，
     * 让业务代码保持熟悉的写法。
     */
    class HookContext internal constructor(
        private val chain: XposedInterface.Chain,
    ) {
        /** 当前阶段 */
        internal var phase: Phase = Phase.BEFORE

        /** 是否处于 before 阶段 */
        val isBefore: Boolean get() = phase == Phase.BEFORE

        /** 原方法的返回值（仅 after 阶段有效，等价 legacy `param.result`） */
        internal var resultValue: Any? = null

        /** 原方法抛出的异常（仅 after 阶段有效，等价 legacy `param.throwable`） */
        internal var resultError: Throwable? = null

        /** 在 after 阶段读取原方法返回值 */
        val result: Any? get() = resultValue

        /** 在 after 阶段读取原方法异常 */
        val throwable: Throwable? get() = resultError

        /** 被调用的对象实例（等价 `param.thisObject`） */
        val thisObject: Any? get() = chain.thisObject

        /** 方法参数（API 102 是 List，等价 legacy 的 `param.args` 数组） */
        val args: List<Any?> get() = chain.args

        /** 被 Hook 的方法/构造器 */
        val executable: Executable get() = chain.executable

        /** 取单个参数 */
        fun arg(index: Int): Any? = runCatching { chain.getArg(index) }.getOrNull()

        /** 继续执行原方法（一般无需手动调，框架已代劳） */
        @Throws(Throwable::class)
        fun proceed(): Any? = chain.proceed()

        /** 用指定参数继续执行原方法 */
        @Throws(Throwable::class)
        fun proceed(newArgs: Array<Any?>): Any? = chain.proceed(newArgs)

        /**
         * 在 before 阶段跳过原方法并返回指定值。
         *
         * 语义与 legacy 的 `param.setResult(x)` 一致：**原方法体不会执行**。
         *
         * 注意 API 102 的签名是 `proceedWith(Object)`（形参非空），传 null 会让
         * Kotlin 的 null 检查在编译期报错。如果确实需要「返回 null 并跳过」，
         * 标准做法是抛一个受控异常，或者干脆让原方法执行完再改结果——
         * 本模块走后者，所以这里用 `Any` 而非 `Any?`。
         */
        @Throws(Throwable::class)
        fun returnAndSkip(value: Any): Any? = chain.proceedWith(value)

        /** 改写参数 */
        fun setArg(index: Int, value: Any?) {
            val list = chain.args
            runCatching { list[index] = value }
        }
    }

    /**
     * API 102 Hooker 实现：一次 intercept 内完成 before → proceed → after。
     *
     * 关于「after 阶段怎么拿到返回值」：API 102 的 Chain 没有 `getResult()`，
     * 返回值只能来自 `proceed()`。所以这里先调 proceed 拿到结果，
     * 再把结果通过 [HookContext] 传给 after 回调。
     */
    private class BeforeAfterHooker(
        private val before: ((HookContext) -> Unit)?,
        private val after: ((HookContext) -> Unit)?,
        private val tag: String,
    ) : XposedInterface.Hooker {

        override fun intercept(chain: XposedInterface.Chain): Any? {
            val ctx = HookContext(chain)

            // ---------- before ----------
            if (before != null) {
                ctx.phase = Phase.BEFORE
                try {
                    before(ctx)
                } catch (t: Throwable) {
                    XLog.e("Hook[$tag] before 异常", t)
                }
            }

            // ---------- proceed（执行原方法） ----------
            var result: Any? = null
            var error: Throwable? = null
            try {
                result = chain.proceed()
            } catch (t: Throwable) {
                // 原方法自身的异常必须原样向上抛，绝不能被 Hook 逻辑吞掉，
                // 否则会改变宿主程序的正常控制流。
                error = t
            }

            // ---------- after ----------
            if (after != null) {
                ctx.phase = Phase.AFTER
                ctx.resultValue = result
                ctx.resultError = error
                try {
                    after(ctx)
                } catch (t: Throwable) {
                    XLog.e("Hook[$tag] after 异常", t)
                }
            }

            if (error != null) throw error
            return result
        }
    }
}

/**
 * 模块与框架之间的桥。
 *
 * 让业务类不必直接依赖 `XposedModule` 类型（便于解耦与测试）。
 * [XiaoAiHookEntry] 在 `onModuleLoaded` 时把自己的 hook 能力注入进来。
 */
internal interface ModuleBridge {

    /** 挂载目标，返回可继续配置的 HookBuilder */
    fun hookExecutable(executable: Executable): XposedInterface.HookBuilder
}
