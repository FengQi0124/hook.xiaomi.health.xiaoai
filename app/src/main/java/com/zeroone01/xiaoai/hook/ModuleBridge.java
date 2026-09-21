package com.zeroone01.xiaoai.hook;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Module capability abstraction (wraps libxposed API).
 * Java-only so the Java entry class can use it without Kotlin stdlib friction.
 */
public interface ModuleBridge {
    boolean hookAfter(Method method, HookCallback callback);
    boolean hookAfter(Constructor<?> ctor, HookCallback callback);
}
