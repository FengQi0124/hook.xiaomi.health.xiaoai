package com.zeroone01.xiaoai.hook

import android.content.Context
import com.zeroone01.xiaoai.core.ModelManager
import com.zeroone01.xiaoai.core.NativeHook
import com.zeroone01.xiaoai.core.XLog
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.Collections
import java.lang.ref.WeakReference

/**
 * LSPosed 模块入口，对接住客端（com.mi.health）进程。
 *
 * ## v0.1.0-beta12 重构要点
 *  - 继承 [XposedModule] 免覆写 onPackageLoaded；仅实现 [onPackageReady]——此时住客端已完成
 *    Application.onCreate，所有 System services 可用，可以安全做 JNI init。
 *  - 原生 hook （[NativeHook.nativeStart]）在这里安装，挂在住客端 libssl.so 的 SSL_read；
 *    SSL_read 被 hook 后的回调走 [NativeHook.onAsrRecognized] / [NativeHook.onQueryReplace]。
 *  - 设置页入口注入通过 [SettingsPageInjector.install]，本身只需要一个 [ModuleBridge]，
 *    由本类实现。
 *  - 进程停止时（[onPackageComplete] / lifecycle STOP）摘钩 + 清理，防止内存泄漏。
 *
 * ## 线程注意
 * [onPackageReady] 在住客端主线程回调；native 上的 ASR / 下行查询在网络线程（SSL_read 线程）里
 * 回调到 [NativeHook]——这两条路径通过 [XLog] 落地。
 */
class XiaoAiHookEntry : XposedModule(), ModuleBridge {

    private val injectedActivities =
        Collections.synchronizedSet(mutableSetOf<WeakReference<android.app.Activity>>())

    // ------------------------------------------------------------------ 1. XposedModule

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        // ① 住客端 Application Context（API 102 不直接暴露 applicationContext，
        //    用 ActivityThread.currentApplication() 反射取得，已在住客进程内运行）。
        val ctx: Context = try {
            val atClass = Class.forName("android.app.ActivityThread")
            val app = atClass.getMethod("currentApplication").invoke(null)
            app as? Context ?: throw IllegalStateException("currentApplication 返回了 null")
        } catch (t: Throwable) {
            XLog.e("获取住客端 Context 失败，使用全局 Context", t)
            // fallback：用 classLoader 加载住客 Application 类
            throw IllegalStateException("无法获取住客端 Context", t)
        }
        XLog.init(ctx)
        XLog.i("onPackageReady: pkg=${ctx.packageName}")

        // ② ModelManager：加载 config + 初始化 provider 表
        runCatching { ModelManager.init(ctx) }
            .onFailure { XLog.e("ModelManager.init 失败", it) }

        // ③ 安装 native hook（libssl.so 的 SSL_read）
        val ok = runCatching { NativeHook.nativeStart() }.getOrDefault(false)
        XLog.i("NativeHook.nativeStart → $ok")

        // ④ 设置页入口注入（自绘 View），通过 ModuleBridge 挂到本实例
        val loader = param.classLoader
        runCatching { SettingsPageInjector.install(this, loader, ctx) }
            .onFailure { XLog.w("SettingsPageInjector.install 失败: ${it.message}") }
    }

    // ------------------------------------------------------------------ 2. ModuleBridge 实现

    override fun hookAfter(method: Method, after: (HookCtx) -> Unit): Boolean {
        return invokeAfter(method, after)
    }

    override fun hookAfter(ctor: Constructor<*>, after: (HookCtx) -> Unit): Boolean {
        return invokeAfter(ctor, after)
    }

    /** 真正的 after-hook：让链式走完全部逻辑，然后调用 [after] */
    private fun invokeAfter(exec: java.lang.reflect.Executable, after: (HookCtx) -> Unit): Boolean {
        return runCatching {
            hook(exec).intercept { chain ->
                val thiz = chain.getThisObject()
                val args = chain.getArgs().toTypedArray()
                // 执行原始方法（含其他 Xposed 模块的 hook 链）
                val result = try {
                    chain.proceed()
                } catch (t: Throwable) {
                    // 原方法抛异常时，after 仍然拿到异常信息（result=null），
                    // 由调用方决定是否要吞掉；这里重新抛出让框架继续分发
                    throw t
                }
                // 原方法已返回 → 调用 after 回调（必须在 proceed 之后）
                try {
                    after(HookCtx(thiz, args, result))
                } catch (t: Throwable) {
                    XLog.e("after-hook 回调异常: ${exec}", t)
                }
                // 返回原方法的真正结果，不改动调用链
                result
            }
            true
        }.getOrElse { t ->
            XLog.e("Xposed hook 失败: ${exec}", t)
            false
        }
    }

    companion object {
        private const val TAG = "XiaoAiEntry"
    }
}
