package com.zeroone01.xiaoai.core

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import java.io.File

/**
 * 跨进程日志通道（ContentProvider）。
 *
 * ## 解决什么问题
 * Hook 跑在宿主进程（com.mi.health），设置 UI / 诊断页跑在模块进程
 * （com.zeroone01.xiaoai），两个进程 uid 不同，`/data/data` 互不可写。
 * XLog 在 hook 进程里 `createPackageContext("com.zeroone01.xiaoai")` 在
 * Android 11+ 包可见性下必失败，日志实际落到宿主 filesDir，
 * 诊断页（模块进程）永远看不到 hook 进程的日志 —— 这就是用户反馈
 * 「日志里没有我的记录」的根因。
 *
 * ## 通道选择
 * ContentProvider 是 framework 提供的标准跨进程通道：
 *  - exported 后 hook 进程用 `ContentResolver.call()` 把日志行推给模块进程落盘；
 *  - 模块进程未启动时 call 会自动拉起它（provider 随进程创建）；
 *  - 同步调用，写入立即可读，诊断页刷新即可见。
 *
 * ## 安全
 * 本 provider 只接受「追加日志行」和「读取日志」两个 method，
 * 不暴露任何其他数据，低风险故不加权限。
 */
class LogProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.zeroone01.xiaoai.logprovider"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY")

        /** method：追加日志行（arg = 一行或多行文本，\n 分隔） */
        const val METHOD_APPEND = "append"

        private const val LOG_FILE_NAME = "xiaoai.log"
        private const val LOG_FILE_MAX_BYTES = 512 * 1024 // 512 KB，写满轮转

        fun logFile(context: Context): File = File(context.filesDir, LOG_FILE_NAME)

        /** 追加一行（或多行）到模块日志文件。两个进程共用同一个实现。 */
        @Synchronized
        fun appendLines(context: Context, text: String) {
            if (text.isEmpty()) return
            runCatching {
                val file = logFile(context)
                file.parentFile?.mkdirs()
                if (file.length() > LOG_FILE_MAX_BYTES) {
                    val keep = file.readLines().takeLast(400)
                    file.writeText("")
                    keep.forEach { file.appendText("$it\n") }
                }
                file.appendText(text)
                if (!text.endsWith("\n")) file.appendText("\n")
            }
        }
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        return when (method) {
            METHOD_APPEND -> {
                if (!arg.isNullOrEmpty()) appendLines(ctx, arg)
                Bundle()
            }
            else -> super.call(method, arg, extras)
        }
    }

    // ---- 不用的抽象方法占位 ----
    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
    ): Int = 0
}
