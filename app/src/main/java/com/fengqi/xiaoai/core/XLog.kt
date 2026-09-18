package com.fengqi.xiaoai.core

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 统一的日志门面。
 *
 * 为什么不用 XposedBridge.log：
 *  - XposedBridge.log 会把日志写进 Xposed 自己的 log，抓取需要 root + 特定命令；
 *  - 这里同时输出到 Logcat（TAG：XiaoAiHijack）和内存环形缓冲，方便 App 内“运行日志”页面查看。
 *
 * 查看方式：
 *   adb logcat -s XiaoAiHijack:V
 */
object XLog {

    const val TAG = "XiaoAiHijack"

    /** 是否输出详细（debug）日志，由设置页控制 */
    @Volatile
    var verbose: Boolean = true

    private const val MAX_BUFFER = 300

    private val buffer = CopyOnWriteArrayList<LogEntry>()

    data class LogEntry(val time: String, val level: String, val message: String)

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun d(message: String) = log("D", message, null, verbose)

    fun i(message: String) = log("I", message, null, true)

    fun w(message: String) = log("W", message, null, true)

    fun e(message: String, t: Throwable? = null) = log("E", message, t, true)

    private fun log(level: String, message: String, t: Throwable?, enabled: Boolean) {
        if (!enabled) return
        val text = if (t != null) "$message\n${Log.getStackTraceString(t)}" else message
        // Logcat：即使 Tag 被系统限流也不会抛异常
        runCatching {
            when (level) {
                "E" -> Log.e(TAG, text)
                "W" -> Log.w(TAG, text)
                "I" -> Log.i(TAG, text)
                else -> Log.d(TAG, text)
            }
        }
        runCatching {
            buffer.add(LogEntry(timeFmt.format(Date()), level, text))
            while (buffer.size > MAX_BUFFER) buffer.removeAt(0)
        }
    }

    /** 读取最近日志（新的在后），供 UI 展示 */
    fun snapshot(): List<LogEntry> = buffer.toList()

    fun clear() = buffer.clear()
}
