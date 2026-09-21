package com.zeroone01.xiaoai.hook;

/**
 * Functional callback invoked after the hooked method completes.
 * Mirrors a Kotlin `(HookCtx) -> Unit` but is plain SAM for Java interop.
 */
public interface HookCallback {
    void onAfter(HookCtx ctx);
}
