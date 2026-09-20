package com.zeroone01.xiaoai.hook

import com.zeroone01.xiaoai.core.XLog
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * 反射访问器。
 *
 * ## 为什么要写这一层
 * 逆向报告确认 AIVS SDK 的类定义（`Message` / `InstructionHeader` / `Template$Toast` …）
 * 存在于 DEX 中，但：
 *  1. 这些类是 **Native 层通过反射使用** 的 POJO，字段名就是 JSON key（`dialog_id`、`is_final`、
 *     `origin_text`…），并且**大概率被混淆器改了类名，但字段名不会被改**——因为字段名要被
 *     `libaivs_jni.so` 拿来做 JSON 反序列化映射。所以 **按字段名反射是可靠锚点**。
 *  2. 不同 App 版本之间类名可能变化，硬编码类名会让模块在新版本上直接失效。
 *
 * ## 策略
 *  - 主路径：按 **类名（含未混淆的备用名）** 加载，失败则按 **字段指纹** 扫描 DEX 中的类。
 *  - 字段查找带缓存，避免 Hook 热路径反复反射。
 *  - 每个 getter/setter 都对 `Optional<T>` 做了透明解包，因为 SDK 大量使用 `Optional<String>`。
 *
 * 所有方法都不抛异常，失败一律返回 null / false，调用方负责「放行原始流程」兜底。
 */
internal object Reflector {

    private val fieldCache = ConcurrentHashMap<String, Field?>()
    private val methodCache = ConcurrentHashMap<String, Method?>()

    // ------------------------------------------------------------------ 类查找

    /** 按候选类名列表依次尝试加载 */
    fun findClass(vararg names: String): Class<*>? {
        for (n in names) {
            runCatching {
                val c = Class.forName(n, false, ClassLoaderHolder.loader ?: ClassLoader.getSystemClassLoader())
                XLog.i("找到类: $n")
                return c
            }
        }
        return null
    }

    /**
     * 按「字段指纹」在 classLoader 能看到的类中搜索。
     * 只在按类名查找失败时使用，成本较高（但只在初始化时执行一次）。
     */
    fun findClassByFields(
        requiredFields: Set<String>,
        classFilter: (String) -> Boolean = { true },
    ): Class<*>? = runCatching {
        val loader = ClassLoaderHolder.loader ?: return null
        val candidates = DexClassScanner.scan(loader)
        for (name in candidates) {
            if (!classFilter(name)) continue
            val c = runCatching { Class.forName(name, false, loader) }.getOrNull() ?: continue
            val fieldNames = c.declaredFields.map { it.name }.toSet()
            if (fieldNames.containsAll(requiredFields)) {
                XLog.i("按字段指纹命中类: $name (fields=${requiredFields.joinToString()})")
                return c
            }
        }
        null
    }.getOrElse {
        XLog.w("按字段指纹查找类失败: ${it.message}")
        null
    }

    // ------------------------------------------------------------------ 字段

    private fun fieldKey(cls: Class<*>, name: String) = "${cls.name}#$name"

    /** 在上溯继承链中查找字段 */
    fun fieldOf(cls: Class<*>?, name: String): Field? {
        if (cls == null) return null
        val key = fieldKey(cls, name)
        fieldCache[key]?.let { return it }
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            runCatching {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                fieldCache[key] = f
                return f
            }
            c = c.superclass
        }
        fieldCache[key] = null
        return null
    }

    /** 读字段并做 Optional 解包，返回 [T] 或 null */
    @Suppress("UNCHECKED_CAST")
    fun <T> get(target: Any?, cls: Class<*>?, name: String): T? {
        val f = fieldOf(target?.javaClass ?: cls, name) ?: return null
        return runCatching { unwrap(f.get(target)) as? T }.getOrNull()
    }

    /** 读字段，返回字符串（含 Optional 解包） */
    fun getString(target: Any?, name: String): String? = get<String>(target, null, name)

    /** 读布尔字段，兼容 boolean / Boolean / Optional<Boolean> */
    fun getBoolean(target: Any?, name: String, default: Boolean = false): Boolean {
        val v = get<Any>(target, null, name) ?: return default
        return when (v) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> v.equals("true", ignoreCase = true)
            else -> default
        }
    }

    fun getInt(target: Any?, name: String, default: Int = 0): Int {
        val v = get<Any>(target, null, name) ?: return default
        return when (v) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull() ?: default
            else -> default
        }
    }

    /** 写字段（自动按字段类型和声明类型做 Optional 包装） */
    fun set(target: Any?, name: String, value: Any?): Boolean {
        val f = fieldOf(target?.javaClass, name) ?: return false
        return runCatching {
            f.set(target, wrapFor(f, value))
            true
        }.getOrElse {
            XLog.w("写字段 $name 失败: ${it.message}")
            false
        }
    }

    // ------------------------------------------------------------------ 方法

    private fun methodKey(cls: Class<*>, name: String) = "${cls.name}#$name"

    fun methodOf(cls: Class<*>?, name: String, paramTypes: Array<Class<*>>? = null): Method? {
        if (cls == null) return null
        val key = methodKey(cls, name) + (paramTypes?.joinToString { it.name } ?: "")
        methodCache[key]?.let { return it }
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            // 先按参数类型精确匹配
            if (paramTypes != null) {
                runCatching {
                    val m = c.getDeclaredMethod(name, *paramTypes)
                    m.isAccessible = true
                    methodCache[key] = m
                    return m
                }
            }
            // 再按名字取第一个（无参优先）
            runCatching {
                val candidates = c.declaredMethods.filter { it.name == name }
                val m = candidates.firstOrNull { it.parameterCount == 0 }
                    ?: candidates.minByOrNull { it.parameterCount }
                if (m != null) {
                    m.isAccessible = true
                    methodCache[key] = m
                    return m
                }
            }
            c = c.superclass
        }
        methodCache[key] = null
        return null
    }

    /** 调用无参方法并解包 */
    @Suppress("UNCHECKED_CAST")
    fun <T> call(target: Any?, cls: Class<*>?, name: String): T? {
        val m = methodOf(target?.javaClass ?: cls, name) ?: return null
        return runCatching { unwrap(m.invoke(target)) as? T }.getOrNull()
    }

    fun callString(target: Any?, name: String): String? = call<String>(target, null, name)

    fun callBoolean(target: Any?, name: String): Boolean? {
        val v = call<Any>(target, null, name) ?: return null
        return v as? Boolean
    }

    /** 调用 setter（先试方法，失败退化到直接写字段） */
    fun invokeSetter(target: Any?, setterName: String, fieldName: String, value: Any?): Boolean {
        val cls = target?.javaClass ?: return false
        val m = cls.declaredMethods.firstOrNull { it.name == setterName && it.parameterCount == 1 }
        if (m != null) {
            m.isAccessible = true
            val ok = runCatching {
                m.invoke(target, wrapForParam(m, value))
                true
            }.getOrDefault(false)
            if (ok) return true
        }
        return set(target, fieldName, value)
    }

    // ------------------------------------------------------------------ Optional 处理

    /** Optional<T> → T，其他类型原样返回 */
    fun unwrap(value: Any?): Any? = when (value) {
        null -> null
        is Optional<*> -> if (value.isPresent) unwrap(value.get()) else null
        else -> value
    }

    /** 按字段声明类型包装成 Optional<T> */
    private fun wrapFor(f: Field, value: Any?): Any? =
        if (Optional::class.java.isAssignableFrom(f.type)) toOptional(value) else value

    private fun wrapForParam(m: Method, value: Any?): Any? {
        val p = m.parameterTypes[0]
        return if (Optional::class.java.isAssignableFrom(p)) toOptional(value) else value
    }

    @Suppress("UNCHECKED_CAST")
    fun toOptional(value: Any?): Optional<Any> =
        if (value == null) Optional.empty<Any>()
        else Optional.ofNullable(value)

    /**
     * ClassLoader 持有者。
     * Hook 时用宿主 App 的 classLoader，UI 进程用本模块的。
     */
    object ClassLoaderHolder {
        @Volatile
        var loader: ClassLoader? = null
    }
}
