package com.zeroone01.xiaoai.core

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 统一的日志门面。
 *
 * 为什么不用 XposedBridge.log：
 *  - XposedBridge.log 会把日志写进 Xposed 自己的 log，抓取需要 root + 特定命令；
 *  - 这里同时输出到 Logcat（TAG：XiaoAiHijack）、内存环形缓冲、文件。
 *
 * 文件落盘是关键：Hook 跑在「小米运动健康」进程，UI 跑在「模块自己」进程，
 * 两者内存完全隔离，只有文件能跨进程共享，让诊断页看到 Hook 实际做了什么。
 *
 * Hook 进程的 Context 是 com.mi.health 的，filesDir 是宿主 dataDir；用
 * [Context.createPackageContext] 跨 uid 拿到模块自己的 filesDir，两边写同一份文件。
 *
 * ## LSPosed 框架日志通道（关键）
 * 普通 XLog 只走 Logcat/文件，**LSPosed verbose 日志里看不到**。
 * [setFrameworkLogger] 由 XiaoAiHookEntry 在 onModuleLoaded 注册一个回调，
 * 该回调内部调用 XposedModule.log() —— 这才是「用户在 LSPosed 管理器里能直接看到」
 * 的通道。注册前 XLog 输出照常工作，注册后 I/W/E 会同时进入框架通道，
 * 任何后续运行的模块就能在 LSPosed 日志里看到完整诊断信息。
 *
 * 查看方式：
 *   adb logcat -s XiaoAiHijack:V
 *   adb shell run-as com.zeroone01.xiaoai cat /data/data/com.zeroone01.xiaoai/files/xiaoai.log
 *   LSPosed 管理器 → 模块 → 「日志」（开启 verbose 即可看到框架通道输出）
 */
object XLog {

    const val TAG = "XiaoAiHijack"

    /** 是否输出详细（debug）日志，由设置页控制 */
    @Volatile
    var verbose: Boolean = true

    private const val MAX_BUFFER = 300
    private const val LOG_FILE_NAME = "xiaoai.log"
    private const val LOG_FILE_MAX_BYTES = 512 * 1024 // 512 KB，写满轮转

    private val buffer = CopyOnWriteArrayList<LogEntry>()

    data class LogEntry(val time: String, val level: String, val message: String)

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    @Volatile
    private var logFile: File? = null

    /**
     * LSPosed 框架日志回调，签名兼容 `XposedModule.log(priority, tag, msg)`。
     *
     * 注册时机：[XiaoAiHookEntry.onModuleLoaded]。注册后所有 I/W/E 会**同时**走
     * 框架通道（用户在 LSPosed 管理器里看 verbose 日志就能看到）。
     *
     * 之所以不直接持有 XposedModule 引用：XLog 在 UI 进程也会被调用，
     * UI 进程里没有 XposedModule；用回调解耦后两边都能安全使用。
     */
    @Volatile
    var frameworkLogger: ((priority: Int, tag: String, message: String) -> Unit)? = null

    /**
     * 初始化日志文件路径。由 [ModelManager.init] 在拿到 Context 后调用。
     *
     * 关键：Hook 进程拿到的 Context 是宿主（com.mi.health）的，写文件会落到
     * /data/data/com.mi.health/files/，UI 进程（com.zeroone01.xiaoai）没权限读。
     * 用 [Context.createPackageContext] 拿到模块自己的 Context（不受 uid 限制），
     * 这样 Hook 进程也能把日志写到 /data/data/com.zeroone01.xiaoai/files/，
     * UI 进程读同一路径即可。
     */
    fun init(context: Context) {
        try {
            val moduleCtx = try {
                context.createPackageContext(
                    "com.zeroone01.xiaoai",
                    Context.CONTEXT_IGNORE_SECURITY,
                )
            } catch (_: Throwable) { null } ?: context
            val dir = moduleCtx.filesDir
            if (!dir.exists()) dir.mkdirs()
            logFile = File(dir, LOG_FILE_NAME)
            Log.i(TAG, "日志文件已就绪：${logFile?.absolutePath}")
        } catch (t: Throwable) {
            Log.w(TAG, "初始化日志文件失败", t)
        }
    }

    fun d(message: String) = log("D", message, null, verbose)

    fun i(message: String) = log("I", message, null, true)

    fun w(message: String) = log("W", message, null, true)

    fun e(message: String, t: Throwable? = null) = log("E", message, t, true)

    private fun log(level: String, message: String, t: Throwable?, enabled: Boolean) {
        if (!enabled) return
        val text = if (t != null) "$message\n${Log.getStackTraceString(t)}" else message
        // 1) Logcat
        runCatching {
            when (level) {
                "E" -> Log.e(TAG, text)
                "W" -> Log.w(TAG, text)
                "I" -> Log.i(TAG, text)
                else -> Log.d(TAG, text)
            }
        }
        // 1.5) LSPosed 框架通道（仅 I/W/E —— D 级别太吵）。注册后才有效。
        //      路径：com.zeroone01.xiaoai 进程里调用是 no-op；Hook 进程里
        //      XiaoAiHookEntry.onModuleLoaded 会把 frameworkLogger 接上，
        //      把 XLog 的输出转发到 LSPosed verbose 日志。
        runCatching {
            val fw = frameworkLogger ?: return@runCatching
            val priority = when (level) {
                "E" -> Log.ERROR
                "W" -> Log.WARN
                "I" -> Log.INFO
                else -> return@runCatching
            }
            fw(priority, TAG, text)
        }
        val timeShort = timeFmt.format(Date())
        val timeLong = dateFmt.format(Date())
        // 2) 内存环形缓冲（仅当前进程可见）
        runCatching {
            buffer.add(LogEntry(timeShort, level, text))
            while (buffer.size > MAX_BUFFER) buffer.removeAt(0)
        }
        // 3) 文件（跨进程共享）
        runCatching {
            val file = logFile ?: return@runCatching
            if (file.length() > LOG_FILE_MAX_BYTES) {
                // 简单轮转：截断保留后半段
                val lines = file.readLines()
                val keep = lines.takeLast(400)
                file.writeText("")
                keep.forEach { file.appendText("$it\n") }
            }
            file.appendText("$timeLong [$level] $text\n")
        }
    }

    /**
     * 读取日志：合并文件 + 内存，按时间倒序返回。
     */
    fun snapshot(): List<LogEntry> {
        val fromFile = readFromFile()
        val fromMem = buffer.toList()
        val seen = HashSet<String>()
        val merged = ArrayList<LogEntry>(fromMem.size + fromFile.size)
        fromFile.forEach { e ->
            val k = "${e.time}|${e.message}"
            if (seen.add(k)) merged.add(e)
        }
        fromMem.forEach { e ->
            val k = "${e.time}|${e.message}"
            if (seen.add(k)) merged.add(e)
        }
        return merged
    }

    private fun readFromFile(): List<LogEntry> {
        val file = logFile ?: return emptyList()
        if (!file.exists() || file.length() == 0L) return emptyList()
        return runCatching {
            file.readLines()
                .mapNotNull { line ->
                    val m = Regex("^(\\S+ \\S+) \\[([DIWE])\\] (.*)$").matchEntire(line)
                        ?: return@mapNotNull null
                    val ts = m.groupValues[1]
                    val lvl = m.groupValues[2]
                    val msg = m.groupValues[3]
                    val short = ts.substringAfter(' ').take(12)
                    LogEntry(short, lvl, msg)
                }
        }.getOrDefault(emptyList())
    }

    fun clear() {
        buffer.clear()
        runCatching { logFile?.writeText("") }
    }
}