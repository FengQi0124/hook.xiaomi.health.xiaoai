package com.zeroone01.xiaoai.core

import com.zeroone01.xiaoai.hook.VoiceCommandHandler
import com.zeroone01.xiaoai.net.AiClient
import java.util.concurrent.ConcurrentHashMap

/**
 * JNI 桥接：对接 native 层的 SSL_read hook。
 *
 * 整个对话链由住客端 AIVS SDK 在 libaivs_jni.so 里通过加密 socket 与云端通信；
 * Java/Vm 层拿不到明文 TLS 字节。native hook 住客端 libssl.so/libboringssl.so 的
 * SSL_read，在解密后的缓冲区里看到了 WebSocket 文本帧里的 JSON，再通过本类把
 * 上行识别结果 / 下行回答文本透传给 Java 业务层。
 *
 * ## 生命周期
 *  1. XiaoAiHookEntry.onPackageReady 中调用 [nativeStart]；
 *  2. native 在 **住客端调用 SSL_read 的工作线程**（网络线程，非 java 主线程）里
 *     通过 CallVoidMethod/CallObjectMethod 调回到本类的 [onAsrRecognized] /
 *     [onQueryReplace]；
 *  3. 卸载 / 进程退出前调用 [nativeStop] 释放 JNI 全局引用。
 *
 * ## 线程安全
 *  - [pendingReplacements] 是 [ConcurrentHashMap]，上行（ASR 识别）和下行（Toast）
 *    很可能跑在不同线程；
 *  - 同步调用 [VoiceCommandHandler] 是状态机，仅在 ASR 上行路径上变更。
 */
object NativeHook {

    private const val TAG = "XiaoAiN-Java"

    /** 让 System.loadLibrary 加载进 native 符号 */
    @JvmStatic
    fun ensureLoaded() {
        runCatching { System.loadLibrary("xiaoai_native") }
            .onFailure { XLog.e("[$TAG] 加载 libxiaoai_native.so 失败", it) }
    }

    // ------------------------------------------------------------------ 1. ASR 上行

    /**
     * native 回调：一次 is_final 的 ASR 识别结果。
     *
     *  - 检测是否「切换模型」指令 → 命中则返回显示菜单的文本（高优先级，由 VoiceCommandHandler 处理）；
     *  - 非切换指令时，在 [pendingReplacements] 里按 dialog_id 登记本次用户文本，
     *    下行 [onQueryReplace] 触发时直接调用第三方 AI 替换。
     *
     * @param dialogId 对话 ID（JSON header.dialog_id）
     * @param text     ASR 识别到的用户文本（JSON payload.results[0].origin_text）
     */
    @Suppress("unused")
    @JvmStatic
    fun onAsrRecognized(dialogId: String, text: String) {
        if (dialogId.isEmpty() || text.isBlank()) return
        val trimmed = text.trim()
        XLog.i("[$TAG] ASR dialog=$dialogId → $trimmed")

        val handled = VoiceCommandHandler.onRecognize(trimmed, dialogId)
        when (handled) {
            is VoiceCommandHandler.OnRecognize.EnterSelection ->
                registerReplacement(dialogId, handled.menuText)
            is VoiceCommandHandler.OnRecognize.Switched ->
                registerReplacement(dialogId, handled.replyText)
            is VoiceCommandHandler.OnRecognize.InvalidChoice ->
                registerReplacement(dialogId, handled.replyText)
            is VoiceCommandHandler.OnRecognize.Normal ->
                registerQuestion(dialogId, trimmed)
            VoiceCommandHandler.OnRecognize.Pass -> { /* 什么都不做 */ }
        }
    }

    @JvmStatic
    fun warmUp() {
        // 给 native 端一个最早的时机把 JNI 回调目标类加载进住客 ClassLoader
        XLog.i("[$TAG] warmUp")
    }

    // ------------------------------------------------------------------ 2. 下行查询

    /**
     * native 回调：一条下行消息（Template.Toast / Template.ToastV2 /
     * Template.ToastStream / Template.StyleToastStreamStart /
     * Application.GenerateSpeak）到来。
     *
     * native 端会在调用本方法的同时阻塞住客端 SSL_read 的工作线程：
     *  - 替换文本长度 ≤ 原字段长度时，原地改写 JSON（UTF-8 bytes）；
     *  - 返回 null 时放行原始字节。
     *
     * ## 行为：
     *  1. 优先消费 [pendingReplacements] 中 dialog_id 命中的暂存（切模型菜单 /
     *     选择结果），native 收到后直接做等长替换；
     *  2. 没有暂存 → 查 [pendingQuestions]（正常提问路径），**同步**走一次 AI 请求，
     *     通过 [com.zeroone01.xiaoai.net.AiClient] 拿 reply，返回给 native 替换；
     *     超过 [AiConfig.timeoutMs] 或请求失败 → 返回 null，走原始小爱回答。
     *
     * @return 要替换的文本（长度宜 ≤ 原始字段字节数）；null 表示不替换
     */
    @Suppress("unused")
    @JvmStatic
    fun onQueryReplace(dialogId: String, namespace: String, name: String): String? {
        // 1) 暂存（切模型菜单 / 选择结果）优先
        pendingReplacements.remove(dialogId)?.let { repl ->
            XLog.i("[$TAG] 下行命中暂存：$namespace.$name → ${repl.take(60)}")
            return repl
        }

        // 2) 正常提问路径 → AI 同步调用
        val question = pendingQuestions.remove(dialogId) ?: run {
            XLog.d("[$TAG] 下行 $namespace.$name 无匹配暂存/提问，放行原始回答")
            return null
        }
        XLog.i("[$TAG] 下行 AI 替换：dialog=$dialogId q=${question.take(60)}")
        return runCatching { AiClient.fetchReply(question) }
            .onFailure { XLog.e("[$TAG] AI 请求失败，放行原始回答", it) }
            .getOrNull()
    }

    // ------------------------------------------------------------------ 3. 内部暂存

    /** dialog_id → 需要替换的确切文本（切模型菜单、选择结果） */
    private val pendingReplacements = ConcurrentHashMap<String, String>()

    /** dialog_id → 用户原始提问文本（正常问答路径） */
    private val pendingQuestions = ConcurrentHashMap<String, String>()

    private fun registerReplacement(dialogId: String, text: String) {
        pendingReplacements[dialogId] = text
        XLog.i("[$TAG] 暂存替换[$dialogId]: ${text.take(60)}")
    }

    private fun registerQuestion(dialogId: String, question: String) {
        pendingQuestions[dialogId] = question
        XLog.i("[$TAG] 暂存提问[$dialogId]: ${question.take(60)}")
    }

    // ------------------------------------------------------------------ 4. native 方法

    /** 启动 SSL_read 内联 hook；返回 true 表示 hook 安装成功 */
    @JvmStatic
    external fun nativeStart(): Boolean

    /** 停止 hook（进程退出 / 卸载时调用） */
    @JvmStatic
    external fun nativeStop()

    /** 设置 native 日志开关 */
    @JvmStatic
    external fun nativeSetLogEnable(enable: Boolean)
}
