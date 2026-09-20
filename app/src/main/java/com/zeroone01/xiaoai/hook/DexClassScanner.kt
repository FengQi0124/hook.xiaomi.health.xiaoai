package com.zeroone01.xiaoai.hook

import com.zeroone01.xiaoai.core.XLog
import java.io.File
import java.util.jar.JarFile
import java.util.zip.ZipEntry

/**
 * DEX 类名扫描器。
 *
 * 用途：当按固定类名找不到 AIVS 的类时（被混淆 / 版本改名），退化为「扫描所有类名，
 * 逐个用反射检查字段指纹」来找目标类。只在 Hook 初始化阶段执行一次。
 *
 * 实现方式：读取 APK 里的 classes*.dex，用 DEX 文件格式解析 `string_ids` 中的
 * 「类型描述符」（形如 `Lcom/xxx/Yyy;`），把想找的类过滤成短列表，避免为几万个类
 * 都做 `Class.forName`（那会非常慢）。
 *
 * 这里只做**字符串级**解析，不依赖任何第三方 DEX 库，保证零额外依赖。
 */
internal object DexClassScanner {

    /** 缓存扫描结果，避免重复 IO */
    private var cached: List<String>? = null

    /** 只关心这些前缀的类（AIVS 相关的包名/类名特征） */
    private val interestingPrefixes = listOf(
        "com/xiaomi", "miui", "com/mi", "ai/xiaomi", "com/xiaomi/ai",
    )

    /** 明确不关心的包，减少候选量 */
    private val skipPrefixes = listOf(
        "android/", "androidx/", "kotlin/", "kotlinx/", "java/", "javax/",
        "okhttp3/", "okio/", "com/squareup/", "org/",
    )

    /**
     * 返回 APK 中所有「疑似 AIVS 相关」的类名（点分形式）。
     * @param loader 宿主 App 的 ClassLoader，用于定位 APK 路径
     */
    fun scan(loader: ClassLoader): List<String> {
        cached?.let { return it }
        val result = mutableListOf<String>()
        runCatching {
            val apkPaths = resolveApkPaths(loader)
            XLog.i("DEX 扫描：发现 ${apkPaths.size} 个 apk/dex 路径")
            apkPaths.forEach { path ->
                if (path.endsWith(".apk") || path.endsWith(".jar")) {
                    scanArchive(path, result)
                } else if (path.endsWith(".dex")) {
                    runCatching { scanDex(File(path).readBytes(), result) }
                }
            }
        }.onFailure { XLog.w("DEX 扫描失败: ${it.message}") }

        val distinct = result.distinct()
        cached = distinct
        XLog.i("DEX 扫描完成：候选类 ${distinct.size} 个")
        return distinct
    }

    // ------------------------------------------------------------------

    /** 从 ClassLoader 推断 APK / DEX 文件路径（兼容 PathClassLoader 与 DelegateLastClassLoader） */
    private fun resolveApkPaths(loader: ClassLoader): List<String> {
        val paths = linkedSetOf<String>()
        var cl: ClassLoader? = loader
        while (cl != null) {
            runCatching {
                val f = cl.javaClass.getDeclaredField("pathList")
                f.isAccessible = true
                val pathList = f.get(cl) ?: return@runCatching
                val dexElements = pathList.javaClass.getDeclaredField("dexElements").let {
                    it.isAccessible = true; it.get(pathList)
                } as? Array<*> ?: return@runCatching
                dexElements.forEach { element ->
                    if (element == null) return@forEach
                    runCatching {
                        val dexFile = element.javaClass.getDeclaredField("dexFile").let {
                            it.isAccessible = true; it.get(element)
                        }
                        val path = dexFile?.javaClass?.getDeclaredMethod("getName")?.let {
                            it.isAccessible = true; it.invoke(dexFile) as? String
                        }
                        if (path != null) paths.add(path)
                    }
                }
            }
            cl = cl.parent
        }
        return paths.toList()
    }

    private fun scanArchive(path: String, out: MutableList<String>) {
        runCatching {
            JarFile(path).use { jar ->
                val entries = jar.entries().toList().filter { it.name.endsWith(".dex") }
                entries.forEach { entry ->
                    runCatching {
                        jar.getInputStream(entry as ZipEntry).use { input ->
                            scanDex(input.readBytes(), out)
                        }
                    }
                }
            }
        }.onFailure { XLog.w("扫描 $path 失败: ${it.message}") }
    }

    /**
     * 解析 DEX 的 string_ids 段。
     *
     * DEX 头部布局（小端）：
     *   0x00 magic(8) / 0x08 checksum(4) / 0x0C signature(20) / 0x20 file_size(4)
     *   0x24 header_size(4) / 0x28 endian_tag(4) / 0x2C link_size(4) / 0x30 link_off(4)
     *   0x34 map_off(4) / 0x38 string_ids_size(4) / 0x3C string_ids_off(4) ...
     * 每个 string_id 是 4 字节的偏移，指向 string_data_item：
     *   uleb128(utf16_size) + MUTF-8 字符串 + 0x00
     */
    private fun scanDex(data: ByteArray, out: MutableList<String>) {
        if (data.size < 0x70) return
        if (!(data[0] == 'd'.code.toByte() && data[1] == 'e'.code.toByte() &&
                data[2] == 'x'.code.toByte() && data[3] == '\n'.code.toByte())
        ) {
            return // 不是标准 dex（可能是 compact dex），跳过
        }
        val stringIdsSize = readIntLE(data, 0x38)
        val stringIdsOff = readIntLE(data, 0x3C)
        if (stringIdsSize <= 0 || stringIdsOff <= 0) return
        if (stringIdsOff + stringIdsSize * 4 > data.size) return

        for (i in 0 until stringIdsSize) {
            val dataOff = readIntLE(data, stringIdsOff + i * 4)
            if (dataOff <= 0 || dataOff >= data.size) continue
            val str = readStringData(data, dataOff) ?: continue
            // 只保留类描述符 L...;
            if (str.length < 3 || str[0] != 'L' || str[str.length - 1] != ';') continue
            val internal = str.substring(1, str.length - 1)
            if (skipPrefixes.any { internal.startsWith(it) }) continue
            if (interestingPrefixes.none { internal.startsWith(it) }) continue
            // 排除内部类（含 $ 的仍保留，因为 Template$Toast 就是内部类）
            out.add(internal.replace('/', '.'))
        }
    }

    private fun readIntLE(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun readStringData(b: ByteArray, off: Int): String? {
        var idx = off
        // uleb128 长度（我们不用这个值，直接读到 0x00 结束更稳）
        var shift = 0
        while (idx < b.size && shift < 35) {
            val byte = b[idx].toInt() and 0xFF
            idx++
            if (byte and 0x80 == 0) break
            shift += 7
        }
        if (idx >= b.size) return null
        val sb = StringBuilder()
        val limit = minOf(b.size, idx + 512) // 类名不可能太长，防御性截断
        var i = idx
        while (i < limit) {
            val c = b[i].toInt() and 0xFF
            if (c == 0) return sb.toString()
            when {
                c < 0x80 -> {
                    sb.append(c.toChar()); i++
                }
                // MUTF-8 双字节
                c and 0xE0 == 0xC0 && i + 1 < limit -> {
                    val c2 = b[i + 1].toInt() and 0xFF
                    sb.append((((c and 0x1F) shl 6) or (c2 and 0x3F)).toChar()); i += 2
                }
                // MUTF-8 三字节
                c and 0xF0 == 0xE0 && i + 2 < limit -> {
                    val c2 = b[i + 1].toInt() and 0xFF
                    val c3 = b[i + 2].toInt() and 0xFF
                    sb.append((((c and 0x0F) shl 12) or ((c2 and 0x3F) shl 6) or (c3 and 0x3F)).toChar())
                    i += 3
                }
                else -> return null
            }
        }
        return null
    }
}
