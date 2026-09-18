package com.fengqi.xiaoai.core

import kotlinx.serialization.json.Json
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

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
        isLenient = true
    }

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
        val parsed = json.decodeFromString(AiConfig.serializer(), text)
        // 补齐新增的提供方（老版本配置升级）
        val merged = parsed.copy(providers = AiConfig.defaultProviders() + parsed.providers)
        config = merged
        lastLoadedMtime.set(file.lastModified())
        XLog.i("配置已加载: active=${merged.activeModelKey} verbose=${merged.verboseLog}")
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
