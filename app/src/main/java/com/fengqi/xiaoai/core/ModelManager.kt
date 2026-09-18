package com.fengqi.xiaoai.core

import android.annotation.SuppressLint
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 模型管理器（全局单例）。
 *
 * ## 为什么用单例 + 双向初始化
 * 这个类会被**两个进程**分别加载：
 *  1. 本模块进程（设置页 UI）；
 *  2. 小米运动健康进程（LSPosed 注入后）。
 * 它们各自持有一份内存副本，通过 [FileConfigStore] 的 mtime 探测做同步。
 *
 * ## 同步机制（双保险）
 *  - **状态广播**：本模块进程改模型时发一条 App 内部广播，目标进程立刻响应（毫秒级）；
 *  - **轮询兜底**：后台协程每 1.5s 调一次 [ConfigStore.reloadIfChanged]，即使广播被系统拦截也能同步。
 *
 * ## 生命周期
 *  - UI 进程：`Application.onCreate` 调用 [init]
 *  - Hook 进程：Hook 入口调用 [init]，并调用 [startPolling]
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

    @Volatile
    private var initialized = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** dialog_id -> 待处理的用户语音文本，Hook 线程与网络回调共用 */
    val pendingQueries = ConcurrentHashMap<String, PendingQuery>()

    /** 最近一次对话记录（UI 展示用），最多保留 50 条 */
    val recentDialogs = java.util.Collections.synchronizedList(ArrayDeque<DialogRecord>())

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

    @SuppressLint("HardwareIds")
    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        val dir = context.filesDir
        store = FileConfigStore(File(dir, "xiaoai_config.json"))
        // 先初始化日志路径，确保后续 init 阶段的日志能落盘
        XLog.init(context)
        val cfg = store.get()
        _configFlow.value = cfg
        _activeModel.value = cfg.activeModel()
        XLog.verbose = cfg.verboseLog
        initialized = true
        XLog.i("ModelManager 初始化完成，active=${_activeModel.value.displayName}")
    }

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
            _activeModel.value = cfg.activeModel()
            XLog.verbose = cfg.verboseLog
        }
        return changed
    }

    /** 保存配置（仅 UI 进程调用） */
    fun saveConfig(cfg: AiConfig) {
        if (!initialized) return
        store.set(cfg)
        _configFlow.value = cfg
        _activeModel.value = cfg.activeModel()
        XLog.verbose = cfg.verboseLog
    }

    /** 切换模型，返回是否真的发生了变化 */
    fun switchModel(model: ModelId, source: String = "unknown"): Boolean {
        if (!initialized) return false
        val current = _activeModel.value
        saveConfig(store.get().copy(activeModelKey = model.key))
        val changed = current != model
        _events.tryEmit(ModuleEvent.ModelChanged(model, source))
        XLog.i("模型切换: ${current.displayName} -> ${model.displayName} (来源: $source)")
        return changed
    }

    fun setSwitching(value: Boolean) {
        _switching.value = value
    }

    fun isSwitching(): Boolean = _switching.value

    /**
     * 启动配置轮询同步。只在 Hook 注入的进程里调用；
     * 每 [intervalMs] 毫秒检查一次文件 mtime，实现「手机端切换 → 手环端立即生效」。
     */
    fun startPolling(intervalMs: Long = 1500L) {
        if (!initialized) return
        scope.launch {
            XLog.i("配置同步轮询已启动，间隔 ${intervalMs}ms")
            while (isActive) {
                delay(intervalMs)
                runCatching { refresh() }
            }
        }
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
