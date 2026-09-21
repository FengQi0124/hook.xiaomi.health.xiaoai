package com.zeroone01.xiaoai.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * [ConfigStore] 的 JSON 文件实现。
 *
 * 线程安全：
 *  - [config] 用 @Volatile 持有不可变数据类，读操作无锁；
 *  - 写操作用 [writeLock] 串行，且通过临时文件 + rename 保证原子性；
 *  - [reloadIfChanged] 通过 mtime 判断，避免每次 Hook 都读盘。
 *
 * 多进程同步：
 *  设置页写文件 → 目标进程下一次 [reloadIfChanged] 时发现 mtime 变化 → 重新加载。
 *  由于 mtime 精度问题，额外维护一个内存版本号，保证同一秒内的多次写入也能被感知。
 */
class FileConfigStore(private val file: File) : ConfigStore {

    companion object {
        /** 共享 JSON 实例（序列化格式必须与落盘一致，供 ConfigSync 广播序列化用） */
        val sharedJson: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
            isLenient = true
        }

        /** 把配置序列化成与磁盘格式完全一致的 JSON（ConfigSync 广播载荷） */
        fun toJson(cfg: AiConfig): String = sharedJson.encodeToString(AiConfig.serializer(), cfg)
    }

    private val json = sharedJson

    private val writeLock = Any()
    private val lastLoadedMtime = AtomicLong(-1L)

    @Volatile
    private var config: AiConfig = AiConfig()

    init {
        runCatching { loadFromDisk() }
            .onFailure { XLog.e("初始化配置失败，使用默认配置", it) }
    }

    override fun get(): AiConfig = config

    override fun set(newConfig: AiConfig) {
        synchronized(writeLock) {
            config = newConfig
            runCatching { saveToDisk(newConfig) }
                .onFailure { XLog.e("保存配置失败", it) }
        }
    }

    override fun lastModified(): Long = if (file.exists()) file.lastModified() else 0L

    override fun reloadIfChanged(): Boolean {
        val mtime = if (file.exists()) file.lastModified() else 0L
        if (mtime == lastLoadedMtime.get()) return false
        synchronized(writeLock) {
            if (mtime == lastLoadedMtime.get()) return false
            return runCatching {
                loadFromDisk()
                true
            }.getOrElse {
                XLog.e("重载配置失败", it)
                false
            }
        }
    }

    // ---------------------------------------------------------------------

    private fun loadFromDisk() {
        if (!file.exists()) {
            // 首次运行：落盘一份默认配置，方便用户直接编辑
            saveToDisk(config)
            lastLoadedMtime.set(file.lastModified())
            return
        }
        val text = file.readText()
        if (text.isBlank()) {
            lastLoadedMtime.set(file.lastModified())
            return
        }
        // 解析：先新版，失败再尝试旧版迁移
        val parsed = runCatching {
            json.decodeFromString(AiConfig.serializer(), text)
        }.getOrElse { firstErr ->
            XLog.w("按新版解析配置失败，尝试旧版迁移：${firstErr.message}")
            tryMigrateLegacy(text) ?: throw firstErr
        }
        // ★ beta6 修复：清理「从 beta5 残留的、没人填的预设行」（key 以 preset- 开头
        //   且 apiKey / baseUrl / model 全部为空）——否则用户从 beta5 升上来还会看见
        //   一堆空行，体验差。
        val cleaned = parsed.providers.filterNot { p ->
            p.isXiaoAi == false &&
                p.key.startsWith("preset-") &&
                p.apiKey.isBlank() &&
                p.baseUrl.isBlank() &&
                p.model.isBlank()
        }
        // 补齐默认列表里缺失的行（仅追加，不删用户的）
        val defaults = AiConfig.defaultProviders()
        val existingKeys = cleaned.map { it.key }.toSet()
        val augmented = cleaned + defaults.filter { it.key !in existingKeys }
        // 保证 XIAOAI 在第一位
        val reordered = augmented.sortedBy { if (it.isXiaoAi) 0 else 1 }
        val merged = parsed.copy(providers = reordered)
        // activeModelKey 必须命中 providers 里某一行的 key，否则回落 XIAOAI
        val safeMerged = if (merged.activeModelKey !in merged.providers.map { it.key }) {
            merged.copy(activeModelKey = AiConfig.XIAOAI_KEY)
        } else merged
        config = safeMerged
        lastLoadedMtime.set(file.lastModified())
        XLog.i(
            "配置已加载: active=${safeMerged.activeModelKey} verbose=${safeMerged.verboseLog} " +
                "行数=${safeMerged.providers.size}"
        )
    }

    /** 旧版 JSON（providers 是 Map）解析失败时的兜底迁移 */
    private fun tryMigrateLegacy(text: String): AiConfig? = runCatching {
        val element = json.parseToJsonElement(text)
        if (element !is JsonObject) return@runCatching null
        val rawMap = element.toMapOfAny()
        AiConfig.migrateLegacy(rawMap)
    }.getOrElse {
        XLog.e("旧版配置迁移失败", it)
        null
    }

    private fun saveToDisk(cfg: AiConfig) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.parentFile?.mkdirs()
        tmp.writeText(json.encodeToString(AiConfig.serializer(), cfg))
        if (!tmp.renameTo(file)) {
            // rename 在极少数情况下会失败（跨挂载点），退化为直接写
            file.writeText(tmp.readText())
            tmp.delete()
        }
        lastLoadedMtime.set(file.lastModified())
    }
}

/* ---- JSON -> Map<String, Any?> 辅助 ---- */

private fun JsonElement.toMapOfAny(): Map<String, Any?> = when (this) {
    is JsonObject -> entries.associate { (k, v) -> k to v.toAnyValue() }
    else -> emptyMap()
}

private fun JsonElement.toAnyValue(): Any? = when (this) {
    is JsonPrimitive -> {
        val s = content
        s.toBooleanStrictOrNull() ?: s.toLongOrNull() ?: s.toDoubleOrNull() ?: s
    }
    is JsonObject -> toMapOfAny()
    is JsonArray -> map { it.toAnyValue() }
    else -> null
}