package com.zeroone01.xiaoai.hook;

import android.content.Context;

import com.zeroone01.xiaoai.core.ModelManager;
import com.zeroone01.xiaoai.core.NativeHook;
import com.zeroone01.xiaoai.core.XLog;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * LSPosed module entry (Java implementation).
 *
 * Matches beta7-era class shape: {@code public class XiaoAiHookEntry extends XposedModule}
 * with a public no-arg constructor. This is what LSPosed 102 framework expects and how it
 * was when the module worked in beta7.
 */
public class XiaoAiHookEntry extends XposedModule implements ModuleBridge {

    public XiaoAiHookEntry() {
        super();
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        Context ctx = param.getApplicationContext();
        if (ctx == null) ctx = param.getAppContext();
        if (ctx == null) {
            try {
                Class<?> at = Class.forName("android.app.ActivityThread");
                Object app = at.getMethod("currentApplication").invoke(null);
                if (app instanceof Context) ctx = (Context) app;
            } catch (Throwable t) {
                XLog.e("Failed to obtain Context", t);
                return;
            }
        }
        XLog.init(ctx);
        XLog.i("onPackageReady: pkg=" + ctx.getPackageName());

        try {
            ModelManager.init(ctx);
        } catch (Throwable t) {
            XLog.e("ModelManager.init failed", t);
        }

        boolean ok = false;
        try {
            ok = NativeHook.nativeStart();
        } catch (Throwable t) {
            XLog.e("NativeHook.nativeStart failed", t);
        }
        XLog.i("NativeHook.nativeStart → " + ok);

        ClassLoader loader = param.getClassLoader();
        if (loader != null) {
            try {
                SettingsPageInjector.install(this, loader, ctx);
            } catch (Throwable t) {
                XLog.w("SettingsPageInjector.install failed: " + t.getMessage());
            }
        } else {
            XLog.w("param.getClassLoader() is null");
        }
    }

    // ------------------------------------------------------------------ ModuleBridge

    @Override
    public boolean hookAfter(Method method, HookCallback callback) {
        return invokeAfter(method, callback);
    }

    @Override
    public boolean hookAfter(Constructor<?> ctor, HookCallback callback) {
        return invokeAfter(ctor, callback);
    }

    private boolean invokeAfter(Executable exec, HookCallback callback) {
        try {
            hook(exec).intercept(chain -> {
                Object thiz = chain.getThisObject();
                Object[] args = chain.getArgs().toArray();
                Object result;
                try {
                    result = chain.proceed();
                } catch (Throwable t) {
                    throw t;
                }
                try {
                    callback.onAfter(new HookCtx(thiz, args, result));
                } catch (Throwable t) {
                    XLog.e("after-hook callback threw: " + exec, t);
                }
                return result;
            });
            return true;
        } catch (Throwable t) {
            XLog.e("Xposed hook failed: " + exec, t);
            return false;
        }
    }
}
