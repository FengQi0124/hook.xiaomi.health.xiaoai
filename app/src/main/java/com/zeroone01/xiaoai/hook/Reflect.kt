package com.zeroone01.xiaoai.hook

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 自研反射工具 —— 替代 legacy Xposed 的 `XposedHelpers`。
 *
 * ## 为什么要自己写
 * libxposed API 102 起，框架**不再提供** `XposedHelpers` / `XposedBridge` 这些工具类
 * （见 LSPosed 官方 wiki《Develop Xposed Modules Using Modern Xposed API》：
 * "We no longer provide interfaces like XposedHelpers in the framework anymore"）。
 *
 * 这些 helper 本质上就是对 `java.lang.reflect` 的语法糖，功能完全可以用标准反射实现，
 * 自己写反而更可控：不依赖框架内部实现，跨 API 版本稳定，也便于调试。
 *
 * ## 与 XposedHelpers 的行为对应
 * | legacy | 本类 |
 * |---|---|
 * | `findClass(name, loader)` | [findClass] |
 * | `findMethodExact` | [findMethod] |
 * | `callMethod(obj, name, args...)` | [callMethod] |
 * | `newInstance(cls, args...)` | [newInstance] |
 * | `getObjectField` / `setObjectField` | [getField] / [setField] |
 * | `findAndHookMethod` | 已由 [XposedCompat] 的 Hook 封装取代 |
 */
internal object Reflect {

    /** 按类名查找类；先试给定 loader，失败再试系统 loader */
    fun findClass(name: String, loader: ClassLoader?): Class<*>? {
        if (loader != null) {
            runCatching { return Class.forName(name, false, loader) }
        }
        runCatching { return Class.forName(name) }
        return null
    }

    /** 查找方法（含继承链），按名字 + 参数个数匹配；参数类型已知时优先精确匹配 */
    fun findMethod(
        clazz: Class<*>,
        name: String,
        vararg paramTypes: Class<*>,
    ): Method? {
        // 1) 精确匹配
        runCatching {
            val m = clazz.getDeclaredMethod(name, *paramTypes)
            m.isAccessible = true
            return m
        }
        // 2) 沿继承链按 名字 + 参数个数 匹配
        var c: Class<*>? = clazz
        while (c != null) {
            c.declaredMethods.firstOrNull {
                it.name == name && it.parameterCount == paramTypes.size
            }?.let {
                it.isAccessible = true
                return it
            }
            c = c.superclass
        }
        // 3) 宽松匹配：只按名字（参数类型不确定时兜底）
        return findMethodsByName(clazz, name).firstOrNull()
    }

    /** 查找所有同名方法（含继承链） */
    fun findMethodsByName(clazz: Class<*>, name: String): List<Method> {
        val out = mutableListOf<Method>()
        var c: Class<*>? = clazz
        while (c != null) {
            c.declaredMethods.filter { it.name == name }.forEach {
                it.isAccessible = true
                out.add(it)
            }
            c = c.superclass
        }
        return out
    }

    /** 查找字段（含继承链） */
    fun findField(clazz: Class<*>, name: String): Field? {
        var c: Class<*>? = clazz
        while (c != null) {
            c.declaredFields.firstOrNull { it.name == name }?.let {
                it.isAccessible = true
                return it
            }
            c = c.superclass
        }
        return null
    }

    /** 查找构造器，按参数个数匹配 */
    fun findConstructor(clazz: Class<*>, vararg paramTypes: Class<*>): Constructor<*>? {
        runCatching {
            val c = clazz.getDeclaredConstructor(*paramTypes)
            c.isAccessible = true
            return c
        }
        return clazz.declaredConstructors.firstOrNull { it.parameterCount == paramTypes.size }?.also {
            it.isAccessible = true
        }
    }

    // ------------------------------------------------------------------
    // 读写字段
    // ------------------------------------------------------------------

    /** 读字段（按名字，含继承链），失败返回 null */
    fun <T> get(instance: Any?, fieldName: String): T? {
        val target = instance ?: return null
        val f = findField(target.javaClass, fieldName) ?: return null
        return runCatching { @Suppress("UNCHECKED_CAST") f.get(target) as T? }.getOrNull()
    }

    /** 写字段（按名字，含继承链） */
    fun set(instance: Any?, fieldName: String, value: Any?): Boolean {
        val target = instance ?: return false
        val f = findField(target.javaClass, fieldName) ?: return false
        return runCatching { f.set(target, value); true }.getOrDefault(false)
    }

    /** 读静态字段 */
    fun <T> getStatic(clazz: Class<*>, fieldName: String): T? {
        val f = findField(clazz, fieldName) ?: return null
        return runCatching { @Suppress("UNCHECKED_CAST") f.get(null) as T? }.getOrNull()
    }

    // ------------------------------------------------------------------
    // 调用方法
    // ------------------------------------------------------------------

    /**
     * 按名字调用方法。
     *
     * 先尝试精确签名，失败后回退到「同名 + 参数个数 + 参数可赋值性」的宽松匹配 ——
     * 目标 App 的方法参数常常是混淆过的类型（如 `a0`），无法在编译期写死，
     * 宽松匹配是这里的关键。
     */
    fun callMethod(instance: Any?, methodName: String, vararg args: Any?): Any? {
        val target = instance ?: return null
        return callMethodOn(target.javaClass, target, methodName, *args)
    }

    fun callStaticMethod(clazz: Class<*>, methodName: String, vararg args: Any?): Any? =
        callMethodOn(clazz, null, methodName, *args)

    private fun callMethodOn(
        clazz: Class<*>,
        instance: Any?,
        methodName: String,
        vararg args: Any?,
    ): Any? {
        // 1) 精确匹配（参数个数相同 + 类型可赋值）
        val candidates = findMethodsByName(clazz, methodName)
        if (candidates.isEmpty()) return null

        for (m in candidates) {
            if (m.parameterCount != args.size) continue
            if (!argsAssignable(m.parameterTypes, args)) continue
            return runCatching { m.invoke(instance, *args) }.getOrNull()
        }

        // 2) 宽松：名字 + 个数匹配（忽略类型），用于参数类型被混淆的情况
        val loose = candidates.firstOrNull { it.parameterCount == args.size }
        if (loose != null) {
            return runCatching { loose.invoke(instance, *args) }.getOrNull()
        }

        // 3) 名字匹配但参数个数不同：只取无参的作为最后手段
        return candidates.firstOrNull { it.parameterCount == 0 }
            ?.let { runCatching { it.invoke(instance) }.getOrNull() }
    }

    /** 判断实参能否赋给形参 */
    private fun argsAssignable(paramTypes: Array<Class<*>>, args: Array<out Any?>): Boolean {
        for (i in paramTypes.indices) {
            val p = paramTypes[i]
            val a = args.getOrNull(i) ?: run {
                if (p.isPrimitive) return false else continue
            }
            if (p.isPrimitive) {
                // 基本类型做装箱兼容判断
                val boxed = when (p) {
                    java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
                    java.lang.Byte.TYPE -> java.lang.Byte::class.java
                    java.lang.Character.TYPE -> java.lang.Character::class.java
                    java.lang.Short.TYPE -> java.lang.Short::class.java
                    java.lang.Integer.TYPE -> java.lang.Integer::class.java
                    java.lang.Long.TYPE -> java.lang.Long::class.java
                    java.lang.Float.TYPE -> java.lang.Float::class.java
                    java.lang.Double.TYPE -> java.lang.Double::class.java
                    else -> p
                }
                if (!boxed.isInstance(a)) return false
            } else if (!p.isInstance(a)) {
                return false
            }
        }
        return true
    }

    // ------------------------------------------------------------------
    // 构造实例
    // ------------------------------------------------------------------

    /**
     * 实例化。参数类型被混淆时无法精确匹配构造器，所以按
     * 「参数个数 + 可赋值性」逐个试，与 [callMethod] 同一套思路。
     */
    fun newInstance(clazz: Class<*>, vararg args: Any?): Any? {
        val ctors = clazz.declaredConstructors.filter { it.parameterCount == args.size }
        for (c in ctors) {
            c.isAccessible = true
            if (!argsAssignable(c.parameterTypes, args)) continue
            runCatching { return c.newInstance(*args) }
        }
        // 宽松：直接试每个同参数个数的构造器
        for (c in ctors) {
            c.isAccessible = true
            runCatching { return c.newInstance(*args) }
        }
        return null
    }

    /**
     * 在任何类里查找第一个「名字匹配」的静态字段值。
     * 用于读取 R 常量、混淆后的静态配置等。
     */
    fun findStaticFieldValue(clazz: Class<*>, name: String): Any? = getStatic(clazz, name)

    /** 该字段是否为 final */
    fun isFinal(field: Field): Boolean = Modifier.isFinal(field.modifiers)

    /**
     * 解 final 限制后写字段。
     *
     * Java 的 `Field.set` 对 final 实例字段在反射下通常也能写（JDK 9+ 对部分场景会拦），
     * 这里先清 final 修饰位再写，提高成功率。
     */
    fun setIgnoreFinal(instance: Any?, fieldName: String, value: Any?): Boolean {
        val target = instance ?: return false
        val f = findField(target.javaClass, fieldName) ?: return false
        if (isFinal(f)) {
            runCatching {
                val modifiers = Field::class.java.getDeclaredField("modifiers")
                modifiers.isAccessible = true
                modifiers.setInt(f, f.modifiers and Modifier.FINAL.inv())
            }
        }
        return runCatching { f.set(target, value); true }.getOrDefault(false)
    }
}
