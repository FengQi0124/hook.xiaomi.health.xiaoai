package com.zeroone01.xiaoai.net

import com.zeroone01.xiaoai.core.AiConfig
import com.zeroone01.xiaoai.core.ChatMessage
import com.zeroone01.xiaoai.core.ChatRequest
import com.zeroone01.xiaoai.core.ChatResult
import com.zeroone01.xiaoai.core.ModelManager
import com.zeroone01.xiaoai.core.XLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * OpenAI 兼容的 Chat Completions 客户端。
 *
 * 支持：DeepSeek、智谱 GLM、OpenAI、Kimi、通义千问、硅基流动、OpenRouter、
 * 百川、零一万物…… 只要对方提供 OpenAI 兼容端点（`POST {baseUrl}/chat/completions`）。
 *
 * 设计要点：
 *  - OkHttp 单例，连接池复用。**注意**：Hook 进程里这个客户端与宿主 App 的客户端互不影响。
 *  - 每次请求的超时从配置读取，保证「超时兜底」生效。
 *  - 使用 `callTimeout` 而不是分散的 connect/read/write，语义更直观：整个请求的总耗时上限。
 *  - 响应解析手写 JSON（不依赖数据类），这样服务端返回额外字段不会导致解析失败。
 */
object AiClient {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val mediaType = "application/json; charset=utf-8".toMediaType()

    /** 共享的 OkHttpClient：超时通过 newBuilder 按请求派生，避免频繁创建连接池 */
    private val baseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * 发起一次对话补全。
     *
     * @param userText 用户本轮输入
     * @param stream   是否使用 SSE 流式；最终仍返回完整文本（Hook 场景需要完整文本替换 payload）
     * @param onDelta  流式增量回调（可选），用于 ToastStream 场景边收边替换
     */
    suspend fun chat(
        request: ChatRequest,
        userText: String,
        stream: Boolean = false,
        onDelta: ((String) -> Unit)? = null,
    ): ChatResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val url = normalizeUrl(request.baseUrl)
        XLog.d("AI 请求 → $url model=${request.model} stream=$stream q=${userText.take(30)}")

        if (request.apiKey.isBlank()) {
            return@withContext ChatResult.Failure("未配置 API Key", fatal = true)
        }

        val bodyJson = buildJsonObject {
            put("model", request.model)
            put("messages", buildJsonArray {
                // 系统提示词
                if (request.systemPrompt.isNotBlank()) {
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", request.systemPrompt)
                    })
                }
                // 历史上下文
                request.history.forEach { msg ->
                    add(buildJsonObject {
                        put("role", msg.role)
                        put("content", msg.content)
                    })
                }
                // 本轮输入
                add(buildJsonObject {
                    put("role", "user")
                    put("content", userText)
                })
            })
            put("max_tokens", request.maxTokens)
            put("temperature", request.temperature)
            if (stream) put("stream", true)
        }.toString()

        val httpRequest = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${request.apiKey}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", if (stream) "text/event-stream" else "application/json")
            .addHeader("User-Agent", "XiaoAiHijack/1.0")
            .post(bodyJson.toRequestBody(mediaType))
            .build()

        // 按配置的超时派生客户端：callTimeout 覆盖「连接+写+读」的总时长
        val client = baseClient.newBuilder()
            .callTimeout(request.timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(request.timeoutMs, TimeUnit.MILLISECONDS)
            .build()

        try {
            client.newCall(httpRequest).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                    val msg = "HTTP ${resp.code} ${resp.message}: ${errBody.take(300)}"
                    XLog.e("AI 请求失败 $msg")
                    val fatal = resp.code == 401 || resp.code == 403
                    return@use ChatResult.Failure(msg, fatal)
                }
                val text = if (stream) {
                    readSse(resp, onDelta)
                } else {
                    parseNonStream(resp)
                }
                val elapsed = System.currentTimeMillis() - started
                if (text.isNullOrBlank()) {
                    XLog.w("AI 返回空内容（耗时 ${elapsed}ms）")
                    ChatResult.Failure("模型返回空内容")
                } else {
                    XLog.i("AI 回复(${elapsed}ms): ${text.take(80)}")
                    ChatResult.Success(text, elapsed)
                }
            }
        } catch (t: Throwable) {
            val elapsed = System.currentTimeMillis() - started
            val reason = when {
                t is java.net.SocketTimeoutException -> "请求超时(${elapsed}ms)"
                t is java.io.InterruptedIOException -> "请求被中断(${elapsed}ms)"
                else -> "网络异常: ${t.javaClass.simpleName} ${t.message}"
            }
            XLog.e("AI 请求异常: $reason", t)
            ChatResult.Failure(reason)
        }
    }

    // ------------------------------------------------------------------ 同步路径（native 回调线程）

    /**
     * **同步**版本，供 native 回调线程（Libssl.SSL_read 工作线程）调用。
     *
     * 在住客端的网络线程里做同步 HTTP，看起来是「阻塞住客端网络线程」，
     * 但该线程本身就在等 AI 云端回复（下行 Toast 就是云端话音回答），
     * 所以阻塞它的同时也在等同一份数据 —— 不会额外增加用户体感延迟。
     *
     * @return 模型回复的纯文本；配置不完整 / 超时 / 网络失败时返回 null，由调用方决定放行原始回答
     */
    fun fetchReply(question: String): String? = runBlocking {
        val cfg = ModelManager.config()
        if (cfg.isXiaoAiActive) return@runBlocking null  // 走小爱原生，不替换
        val history = if (cfg.enableHistory) buildHistory(cfg.historyRounds) else emptyList()
        val req = cfg.toChatRequest(history) ?: return@runBlocking null
        when (val r = chat(req, question)) {
            is ChatResult.Success -> r.text
            is ChatResult.Failure -> null
        }
    }

    private fun buildHistory(rounds: Int): List<ChatMessage> {
        val all = ModelManager.dialogs()
        val msgs = mutableListOf<ChatMessage>()
        for (d in all.reversed()) {
            if (msgs.size >= rounds * 2) break
            msgs.add(0, ChatMessage(role = "assistant", content = d.answer))
            msgs.add(0, ChatMessage(role = "user", content = d.question))
        }
        return msgs
    }

    // ------------------------------------------------------------------ 解析

    /** 非流式：choices[0].message.content */
    private fun parseNonStream(resp: Response): String? {
        val raw = resp.body?.string() ?: return null
        return runCatching {
            val root = json.parseToJsonElement(raw).jsonObject
            val choices = root["choices"]?.jsonArray ?: return@runCatching null
            if (choices.isEmpty()) return@runCatching null
            val first = choices[0].jsonObject
            // 标准字段
            first["message"]?.jsonObject?.get("content")?.jsonPrimitive?.content
                // 兼容部分只返回 text 的网关
                ?: first["text"]?.jsonPrimitive?.content
        }.onFailure { XLog.e("解析响应失败: ${raw.take(200)}", it) }.getOrNull()
    }

    /** 流式：逐行解析 data: {...}，拼接 delta.content */
    private fun readSse(resp: Response, onDelta: ((String) -> Unit)?): String {
        val sb = StringBuilder()
        val input = resp.body?.byteStream() ?: return ""
        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) continue
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                runCatching {
                    val root = json.parseToJsonElement(data).jsonObject
                    val choices = root["choices"]?.jsonArray ?: return@runCatching
                    if (choices.isEmpty()) return@runCatching
                    val delta = choices[0].jsonObject["delta"]?.jsonObject ?: return@runCatching
                    val piece = delta["content"]?.jsonPrimitive?.content ?: return@runCatching
                    if (piece.isNotEmpty()) {
                        sb.append(piece)
                        onDelta?.invoke(piece)
                    }
                }
            }
        }
        return sb.toString()
    }

    /**
     * 规范化 Base URL：
     *  - 去掉尾部斜杠
     *  - 如果用户填的是完整端点（已含 /chat/completions）则原样使用
     *  - 否则补 `/chat/completions`
     */
    internal fun normalizeUrl(baseUrl: String): String {
        var url = baseUrl.trim().trimEnd('/')
        if (url.endsWith("/chat/completions")) return url
        return "$url/chat/completions"
    }

    /** 供调试：把 URL 里的 key 脱敏 */
    internal fun maskKey(key: String): String =
        if (key.length <= 8) "***" else "${key.take(4)}***${key.takeLast(4)}"
}
