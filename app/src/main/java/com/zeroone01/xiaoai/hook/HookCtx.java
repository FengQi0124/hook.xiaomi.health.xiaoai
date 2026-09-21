package com.zeroone01.xiaoai.hook;

/**
 * After-hook callback context.
 * Plain Java class — avoids Kotlin data-class / componentN() noise in Java-side code.
 */
public final class HookCtx {
    public final Object thisObject;
    public final Object[] args;
    public final Object result;

    public HookCtx(Object thisObject, Object[] args, Object result) {
        this.thisObject = thisObject;
        this.args = args;
        this.result = result;
    }
}
