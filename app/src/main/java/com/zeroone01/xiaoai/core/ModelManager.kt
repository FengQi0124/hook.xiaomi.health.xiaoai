package com.zeroone01.xiaoai.core

import android.annotation.SuppressLint
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 模型管理器（全局单例）。
 *
 * ## v1.2.0 单进程架构
 *  - UI（设置窗口、诊断窗口）与 Hook（小爱回答拦截）**都在 com.mi.health 进程里运行**；
 *  - 因此 [configFlow] / [activeModel] 在同进程内是同一个实例，UI 切换 → Hook 即时生效；
 *  - 跨进程文件 IO 仍然保留（落盘持久化 + 让 LSPosed 共享/调试场景能观察），但不再依赖轮询同步。
 *
 * ## 生命周期
 *  - UI 窗口：[SettingsWindowController.show] 时调用 [ensureInit];
 *  - Hook：[XiaoAiHookEntry] 在 handleLoadPackage 里调用 [init]。
 */
object ModelManager {

    /** 全局事件流：模型切换 / 日志 等，供 UI 收集 */
    private val _events = MutableSharedFlow<ModuleEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<ModuleEvent> = _events.asSharedFlow()

    private val _activeModel = MutableStateFlow(ModelId.XIAOAI)

    /** 当前生效模型，UI 直接 collectAsState 即可 */
    val activeModel: StateFlow<ModelId> = _activeModel.asStateFlow()

    private val _configFlow = MutableStateFlow(AiConfig())
    val configFlow: StateFlow<AiConfig> = _configFlow.asStateFlow()

    /** 手环是否正处于“选择模型”状态，UI 可用来显示提示 */
    private val _switching = MutableStateFlow(false)
    val switching: StateFlow<Boolean> = _switching.asStateFlow()

    private lateinit var store: ConfigStore
    private lateinit var appContext: Context

    @Volatile
    private var initialized = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** dialog_id -> 待处理的用户语音文本，Hook 线程与网络回调共用 */
    val pendingQueries = ConcurrentHashMap<String, PendingQuery>()

    data class PendingQuery(
        val text: String,
        val timestamp: Long,
        /** 模型在本轮对话开始时的快照，避免处理途中用户改了模型导致错配 */
        val modelKey: String,
    )

    data class DialogRecord(
        val time: Long,
        val dialogId: String,
        val question: String,
        val answer: String,
        val model: String,
        val replaced: Boolean,
        val note: String = "",
    )

    /** 本进程是否为模块自身进程（SettingsActivity 所在）；false = 宿主 hook 进程 */
    val isModuleProcess: Boolean
        get() = runCatching { appContext.packageName == MODULE_PACKAGE }.getOrDefault(false)

    const val MODULE_PACKAGE = "com.zeroone01.xiaoai"

    @SuppressLint("HardwareIds")
    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        // ★ v0.1.0-beta7（独立 Activity 架构）：
        // 配置文件跟随**当前进程**的 filesDir：
        //  - 宿主 hook 进程（com.mi.health）：写宿主 filesDir，Hook 直接读；
        //    模块 UI 保存后通过 [ConfigSync] 广播推送最新 JSON 过来（见下）。
        //  - 模块进程（SettingsActivity）：写模块 filesDir；保存时同时广播推给宿主。
        // 之前尝试 createPackageContext("com.zeroone01.xiaoai") 跨 uid 读模块目录，
        // Android 11+ 包可见性下必失败（beta5 日志 "Application package not found"），
        // 反而把配置悄悄写到宿主目录 —— 现在显式承认这个行为并按进程分流。
        val appCtx = context.applicationContext ?: context
        appContext = appCtx
        val dir = appCtx.filesDir
        store = FileConfigStore(File(dir, "xiaoai_config.json"))
        // 先初始化日志路径（XLog.init 内部也会按进程决定写哪里）
        XLog.init(appCtx)
        val cfg = store.get()
        _configFlow.value = cfg
        val active = cfg.activeProvider()
        _activeModel.value = if (active.isXiaoAi) ModelId.XIAOAI
        else active.providerType.toModelId()
        XLog.verbose = cfg.verboseLog
        initialized = true
        XLog.i(
            "ModelManager 初始化完成：pkg=${appCtx.packageName} dataDir=${dir.absolutePath} " +
                "active=${active.displayName}"
        )
        // 宿主端：注册广播接收，随时准备接收模块 UI 推送的配置
        if (!isModuleProcess) {
            ConfigSync.registerHostReceiver(appCtx)
        }
    }

    /** 最近一次对话记录（UI 展示用），最多保留 50 条 */
    val recentDialogs = java.util.Collections.synchronizedList(ArrayDeque<DialogRecord>())

    /** 确保已初始化（Hook 侧拿到的 Context 可能是 App 的，也可能是模块的） */
    fun ensureInit(context: Context) {
        if (!initialized) init(context)
    }

    val isInitialized: Boolean get() = initialized

    fun config(): AiConfig = if (initialized) store.get() else AiConfig()

    fun storeOrNull(): ConfigStore? = if (initialized) store else null

    /** 从磁盘重载（如果变了）并把最新值推给 UI */
    fun refresh(): Boolean {
        if (!initialized) return false
        val changed = store.reloadIfChanged()
        if (changed) {
            val cfg = store.get()
            _configFlow.value = cfg
            val active = cfg.activeProvider()
            _activeModel.value = if (active.isXiaoAi) ModelId.XIAOAI
            else active.providerType.toModelId()
            XLog.verbose = cfg.verboseLog
        }
        return changed
    }

    /** 保存配置。
     *
     * ★ v0.1.0-beta7：模块进程（SettingsActivity）保存后，**立即广播推送给宿主**，
     * 宿主 hook 进程的 ConfigSync receiver 落盘 + 热加载，语音拦截下一句就生效。
     */
    fun saveConfig(cfg: AiConfig) {
        if (!initialized) return
        store.set(cfg)
        _configFlow.value = cfg
        // 同步 activeModel StateFlow（沿用 ModelId 表示当前是「小爱」还是「第三方」）
        val active = cfg.activeProvider()
        _activeModel.value = if (active.isXiaoAi) ModelId.XIAOAI
        else active.providerType.toModelId()
        XLog.verbose = cfg.verboseLog
        // 模块端：推送最新配置给宿主进程
        if (isModuleProcess) {
            ConfigSync.sendConfigToHost(appContext, FileConfigStore.toJson(cfg))
        }
    }

    /** 更新单个 provider；返回新的 AiConfig */
    fun updateProvider(updated: ProviderConfig): AiConfig {
        val cfg = config()
        val newList = cfg.providers.map { if (it.key == updated.key) updated else it }
        val newCfg = cfg.copy(providers = newList)
        saveConfig(newCfg)
        return newCfg
    }

    /** 新增一行；key 由模块生成 UUID */
    fun addProvider(provider: ProviderConfig): AiConfig {
        val cfg = config()
        val newCfg = cfg.copy(providers = cfg.providers + provider)
        saveConfig(newCfg)
        return newCfg
    }

    /** 删除一行；key="xiaoai" 不允许删除 */
    fun deleteProvider(key: String): AiConfig {
        if (key == AiConfig.XIAOAI_KEY) {
            XLog.w("尝试删除小爱原生行，已忽略")
            return config()
        }
        val cfg = config()
        val newList = cfg.providers.filter { it.key != key }
        val newActive = if (cfg.activeModelKey == key) AiConfig.XIAOAI_KEY else cfg.activeModelKey
        val newCfg = cfg.copy(providers = newList, activeModelKey = newActive)
        saveConfig(newCfg)
        return newCfg
    }

    /** 切换当前激活 provider */
    fun setActiveProvider(key: String) {
        val cfg = config()
        val exists = cfg.providers.any { it.key == key }
        if (!exists) {
            XLog.w("尝试切换到不存在的 provider: $key")
            return
        }
        saveConfig(cfg.copy(activeModelKey = key))
    }

    /** 切换模型，返回是否真的发生了变化 */
    fun switchModel(model: ModelId, source: String = "unknown"): Boolean {
        if (!initialized) return false
        val cfg = store.get()
        // 兼容旧路径：用 ModelId.key 去匹配新结构里 key="xiaoai" 的行
        val targetKey = when (model) {
            ModelId.XIAOAI -> AiConfig.XIAOAI_KEY
            else -> cfg.providers.firstOrNull { !it.isXiaoAi && it.enabled }?.key
                ?: return false
        }
        if (targetKey == cfg.activeModelKey) return false
        saveConfig(cfg.copy(activeModelKey = targetKey))
        _events.tryEmit(ModuleEvent.ModelChanged(model, source))
        XLog.i("模型切换: -> $targetKey (来源: $source)")
        return true
    }

    fun setSwitching(value: Boolean) {
        _switching.value = value
    }

    fun isSwitching(): Boolean = _switching.value

    /**
     * v1.2.0 起 UI 与 Hook 同一进程，不再需要文件轮询。
     * 但保留 API 不抛错以便老调用方不炸；只打一条警告。
     */
    fun startPolling(intervalMs: Long = 1500L) {
        XLog.i("startPolling 已废弃（v1.2.0 单进程架构），UI 与 Hook 共享内存实例，无需轮询")
    }

    /** 记录一次对话，用于 UI 展示 */
    fun recordDialog(record: DialogRecord) {
        synchronized(recentDialogs) {
            recentDialogs.addFirst(record)
            while (recentDialogs.size > 50) recentDialogs.removeLast()
        }
    }

    fun dialogs(): List<DialogRecord> = synchronized(recentDialogs) { recentDialogs.toList() }

    fun emit(event: ModuleEvent) {
        _events.tryEmit(event)
    }

    /** 清理过期的 pending query，防止内存泄漏（超过 60s 视为无效） */
    fun purgeStalePending(maxAgeMs: Long = 60_000L) {
        val now = System.currentTimeMillis()
        val it = pendingQueries.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value.timestamp > maxAgeMs) it.remove()
        }
        if (pendingQueries.size > 32) {
            // 异常堆积时直接清空，宁可放行原始回答
            XLog.w("pendingQueries 异常堆积(${pendingQueries.size})，已清空")
            pendingQueries.clear()
        }
    }
}

/** 模块内部事件 */
sealed interface ModuleEvent {
    data class ModelChanged(val model: ModelId, val source: String) : ModuleEvent
    data class Log(val message: String) : ModuleEvent
}
