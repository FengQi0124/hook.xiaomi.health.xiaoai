package com.zeroone01.xiaoai.hook

import com.zeroone01.xiaoai.core.XLog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AIVS SDK 的「模型映射」。
 *
 * 逆向报告给出的类名（`Message` / `EventHeader` / `InstructionHeader` / `Template$Toast` …）
 * 都是**未混淆名**，实际 APK 中可能变化。所以这里不硬编码，而是按下面的优先级解析：
 *
 *  1. 直接用报告中的名字构造常见全限定名（不同版本包名不同，逐个试）；
 *  2. 用**字段指纹**扫描 DEX 找类；
 *  3. 全部失败 → 打印一条含「诊断报告」的日志，提示用户改用"消息 JSON 层"兜底模式。
 *
 * 一旦解析成功，把 Field/Method 都缓存起来（[Reflector] 内部已做缓存）。
 */
internal class AivsModel private constructor() {

    /** 消息基类（含 header / payload 字段） */
    var messageClass: Class<*>? = null
        private set

    /** 事件头 / 指令头（含 dialog_id） */
    var eventHeaderClass: Class<*>? = null
        private set
    var instructionHeaderClass: Class<*>? = null
        private set

    /** 具体 payload 类 */
    var recognizeResultClass: Class<*>? = null
    var recognizeResultItemClass: Class<*>? = null
    var toastClass: Class<*>? = null
    var toastStreamClass: Class<*>? = null
    var toastV2Class: Class<*>? = null
    var styleToastStreamStartClass: Class<*>? = null
    /** 3.57.0 上唯一的 Cloud→App 回答类型：`Application$GenerateSpeak`，仅 `text: Optional<String>` 一字段 */
    var generateSpeakClass: Class<*>? = null

    /** Apis 分发接口（ApiNameMapping / MessageHandler 等） */
    var apiNameMappingClass: Class<*>? = null

    /** 消息分发相关的候选方法名（用于 Hook） */
    val dispatchMethodCandidates = mutableListOf<MethodRef>()

    data class MethodRef(val owner: Class<*>, val name: String, val paramCount: Int)

    /**
     * 「模型已解析」：能识别消息 + 至少识别一个 Cloud→App 回答类型。
     *
     * 注意：3.57.0 上 Toast 是 `InstructionPayload`（App→Cloud，不是回答），所以 `toastClass`
     * 可能为 null；这时只要 `generateSpeakClass` 命中，就视为已解析。
     */
    val resolved: Boolean
        get() = messageClass != null && eventHeaderClass != null &&
            (toastClass != null || toastV2Class != null ||
                toastStreamClass != null || styleToastStreamStartClass != null ||
                generateSpeakClass != null)

    companion object {
        private val once = AtomicBoolean(false)

        @Volatile
        private var instance: AivsModel? = null

        fun get(): AivsModel {
            instance?.let { return it }
            synchronized(once) {
                instance?.let { return it }
                val m = AivsModel()
                runCatching { m.resolveAll() }.onFailure { XLog.e("AIVS 模型解析异常", it) }
                instance = m
                return m
            }
        }

        // 消息名常量（所有版本共享）
        const val NAME_RECOGNIZE_RESULT = "RecognizeResult"
        const val NAME_TOAST = "Toast"
        const val NAME_TOAST_V2 = "ToastV2"
        const val NAME_TOAST_STREAM = "ToastStream"
        const val NAME_STYLE_TOAST_STREAM_START = "StyleToastStreamStart"
        const val NAME_GENERATE_SPEAK = "GenerateSpeak"

        /** Template 命名空间下的所有回答类（按消息名） */
        val ANSWER_TEMPLATE_NAMES = setOf(
            NAME_TOAST, NAME_TOAST_V2, NAME_TOAST_STREAM, NAME_STYLE_TOAST_STREAM_START,
        )
    }

    // ------------------------------------------------------------------

    private fun resolveAll() {
        val loader = Reflector.ClassLoaderHolder.loader
        XLog.i("开始解析 AIVS 模型，classLoader=$loader")

        messageClass = findMessageClass()
        eventHeaderClass = Reflector.findClass(
            "com.xiaomi.ai.api.EventHeader",
            "com.xiaomi.ai.api.common.EventHeader",
            "com.xiaomi.ai.core.EventHeader",
            "com.xiaomi.ai.aivs.core.EventHeader",
            "ai.xiaomi.api.EventHeader",
            "com.xiaomi.aiasst.EventHeader",
        ) ?: Reflector.findClassByFields(
            setOf("namespace", "name"), classFilter = { it.endsWith("EventHeader") }
        )

        instructionHeaderClass = Reflector.findClass(
            "com.xiaomi.ai.api.InstructionHeader",
            "com.xiaomi.ai.api.common.InstructionHeader",
            "com.xiaomi.ai.core.InstructionHeader",
            "com.xiaomi.ai.aivs.core.InstructionHeader",
            "ai.xiaomi.api.InstructionHeader",
        ) ?: Reflector.findClassByFields(
            setOf("namespace", "name", "dialog_id"),
            classFilter = { it.endsWith("InstructionHeader") }
        )

        recognizeResultClass = Reflector.findClass(
            "com.xiaomi.ai.api.SpeechRecognizer\$RecognizeResult",
            "com.xiaomi.ai.aivs.api.SpeechRecognizer\$RecognizeResult",
            "ai.xiaomi.api.SpeechRecognizer\$RecognizeResult",
        ) ?: Reflector.findClassByFields(
            setOf("is_final", "results"), classFilter = { it.contains("RecognizeResult") }
        )

        recognizeResultItemClass = Reflector.findClass(
            "com.xiaomi.ai.api.SpeechRecognizer\$RecognizeResultItem",
            "com.xiaomi.ai.aivs.api.SpeechRecognizer\$RecognizeResultItem",
        ) ?: recognizeResultClass?.declaredClasses
            ?.firstOrNull { it.simpleName.contains("Item") }

        toastClass = findToastClass()
        toastV2Class = Reflector.findClass(
            "com.xiaomi.ai.api.Template\$ToastV2",
            "com.xiaomi.ai.aivs.api.Template\$ToastV2",
            "ai.xiaomi.api.Template\$ToastV2",
        )
        toastStreamClass = Reflector.findClass(
            "com.xiaomi.ai.api.Template\$ToastStream",
            "com.xiaomi.ai.aivs.api.Template\$ToastStream",
        ) ?: Reflector.findClassByFields(
            setOf("markdown_text"), classFilter = { it.endsWith("ToastStream") }
        )
        styleToastStreamStartClass = Reflector.findClass(
            "com.xiaomi.ai.api.Template\$StyleToastStreamStart",
            "com.xiaomi.ai.aivs.api.Template\$StyleToastStreamStart",
        )

        // 3.57.0 上 Cloud→App 唯一的回答类型：
        //   Application$GenerateSpeak（EventPayload，仅 text: Optional<String> 一字段）
        // 在 3.59.1 上不存在，所以这里允许 null。
        generateSpeakClass = Reflector.findClass(
            "com.xiaomi.ai.api.Application\$GenerateSpeak",
            "com.xiaomi.ai.aivs.api.Application\$GenerateSpeak",
            "ai.xiaomi.api.Application\$GenerateSpeak",
        ) ?: Reflector.findClassByFields(
            setOf("text"),
            classFilter = { name ->
                name.endsWith("GenerateSpeak") && name.contains("Application")
            }
        )

        apiNameMappingClass = Reflector.findClass(
            "com.xiaomi.ai.api.ApiNameMapping",
            "com.xiaomi.ai.aivs.ApiNameMapping",
            "com.xiaomi.ai.api.AIApiNameMapping",
        ) ?: Reflector.findClassByFields(
            setOf(), classFilter = { it.endsWith("ApiNameMapping") }
        )

        collectDispatchCandidates()

        XLog.i(
            "AIVS 模型解析结果:\n" +
                "  Message              = ${messageClass?.name}\n" +
                "  EventHeader          = ${eventHeaderClass?.name}\n" +
                "  InstructionHeader    = ${instructionHeaderClass?.name}\n" +
                "  RecognizeResult      = ${recognizeResultClass?.name}\n" +
                "  RecognizeResultItem  = ${recognizeResultItemClass?.name}\n" +
                "  Toast                = ${toastClass?.name}\n" +
                "  ToastV2              = ${toastV2Class?.name}\n" +
                "  ToastStream          = ${toastStreamClass?.name}\n" +
                "  StyleToastStreamStart= ${styleToastStreamStartClass?.name}\n" +
                "  GenerateSpeak(3.57.0)= ${generateSpeakClass?.name}\n" +
                "  ApiNameMapping       = ${apiNameMappingClass?.name}\n" +
                "  分发候选方法         = ${dispatchMethodCandidates.size} 个"
        )
    }

    /** Message 基类：同时具备 header 和 payload 两个字段 */
    private fun findMessageClass(): Class<*>? {
        Reflector.findClass(
            "com.xiaomi.ai.api.Message",
            "com.xiaomi.ai.api.common.Message",
            "com.xiaomi.ai.core.Message",
            "com.xiaomi.ai.aivs.core.Message",
            "ai.xiaomi.api.Message",
            "com.xiaomi.aiasst.core.Message",
        )?.let { return it }
        // 指纹：header + payload
        return Reflector.findClassByFields(setOf("header", "payload"))
    }

    /** Toast 类：具备 text 字段，且类名含 Toast 但不含 Stream/V2 */
    private fun findToastClass(): Class<*>? {
        Reflector.findClass(
            "com.xiaomi.ai.api.Template\$Toast",
            "com.xiaomi.ai.aivs.api.Template\$Toast",
            "ai.xiaomi.api.Template\$Toast",
        )?.let { return it }

        val loader = Reflector.ClassLoaderHolder.loader ?: return null
        return Reflector.findClassByFields(
            setOf("text", "query"),
            classFilter = { name ->
                name.contains("Toast") && !name.contains("Stream") && !name.contains("V2")
            }
        )?.let { exact ->
            exact
        } ?: run {
            // 再放宽一点：只要类名以 Toast 结尾且含 text
            DexClassScanner.scan(loader)
                .filter { it.endsWith("Toast") && it.contains("Template") }
                .firstNotNullOfOrNull { n ->
                    runCatching {
                        val c = Class.forName(n, false, loader)
                        if (c.declaredFields.any { it.name == "text" }) c else null
                    }.getOrNull()
                }
        }
    }

    /**
     * 判断 header 是否为 `EventHeader`（Cloud→App）。
     *
     * AIVS 中所有从云端下发的消息 header 都是 `EventHeader`（`InstructionHeader` 只用于
     * App→Cloud）。这个判断在 3.57.0 与 3.59.1 上都成立——两份代码里 `EventHeader`
     * 都是 `MessageHeader` 的子类，类名不变。
     *
     * 如果 `eventHeaderClass` 解析失败（极少见，类被完全混淆），保守地返回 false —— 这样
     * 会漏拦，但不会误拦；Hook 层会用 namespace 白名单再兜一道。
     */
    fun isEventHeader(header: Any?): Boolean {
        val cls = eventHeaderClass ?: return false
        return cls.isInstance(header)
    }

    /**
     * 消息的方向判断 —— **尽力而为，绝不阻塞拦截**。
     *
     * ★ v0.1.0-beta7 教训（照 xiaoai_hijack.py 重写）：
     * 之前 [isAnswerMessage] 强依赖「header 是 EventHeader」来判断 Cloud→App 方向，
     * 但用户 APK 里 `EventHeader` 类解析结果是 null（直接类名不存在、字段指纹也被
     * scanner 0-paths 拖垮），导致**所有回答消息都被判为"非回答"，一条都拦不到**——
     * 这就是用户反馈"手环说切换模型，小爱还是直接回复"的根因。
     *
     * py 脚本（网络层）根本不需要判方向 —— WebSocket 天然分上下行。Java 层我们没有
     * 免费的方向信息，所以策略改为：
     *  1. **只按 (namespace, name) 白名单**（与 py 完全一致）；
     *  2. 方向信息**仅用于排除**：能确凿证明 header 是 InstructionHeader（App→Cloud
     *     指令）时才放行，证据不足照样拦 —— 宁可多拦（改写下行显示文本），不可漏拦。
     *     把 App→Cloud 的上行 Toast 改写掉并无实际危害（手环只渲染下行回答）。
     */
    fun isCloudToApp(message: Any?): Boolean {
        val header = headerOf(message) ?: return true // 无 header 信息时保守当作下行
        // 确凿的上行证据：header 明确是 InstructionHeader 类型
        if (instructionHeaderClass?.isInstance(header) == true) return false
        // EventHeader 解析成功且命中 → 下行
        if (eventHeaderClass?.isInstance(header) == true) return true
        // 类型解析不出来 → 看 header 类名（AIVS 未混淆字段/类名场景）
        val clsName = header.javaClass.simpleName
        if (clsName.contains("InstructionHeader")) return false
        return true
    }

    /**
     * 这个消息是不是我们要拦截的「回答」消息。
     *
     * ★ v0.1.0-beta7（完全对齐 xiaoai_hijack.py）：
     * 判定只看 (namespace, name) 白名单，方向判断仅作为「确凿上行证据才排除」的
     * 辅助（见 [isCloudToApp]）。之前强依赖 EventHeader 类型判断，用户 APK 上该类
     * 解析失败 → 恒 false → 手环永远收到小爱原始回答。
     *
     * 匹配列表（与 py 一致 + 向前兼容）：
     *  - `Template.Toast` / `Template.ToastV2` / `Template.ToastStream` /
     *    `Template.StyleToastStreamStart`（3.59.1 下行回答）
     *  - `Application.GenerateSpeak`（3.57.0 下行回答）
     */
    fun isAnswerMessage(message: Any?): Boolean {
        val ns = namespaceOf(message) ?: return false
        val name = nameOf(message) ?: return false
        val nameMatches = when (ns) {
            "Template" -> name in ANSWER_TEMPLATE_NAMES
            "Application" -> name == NAME_GENERATE_SPEAK
            else -> false
        }
        if (!nameMatches) return false
        // 方向防误判：能确凿证明是 App→Cloud 指令时才放行（证据不足照样拦）
        return isCloudToApp(message)
    }

    /** App→Cloud 的语音识别结果（所有版本方向一致） */
    fun isRecognizeMessage(message: Any?): Boolean {
        val ns = namespaceOf(message) ?: return false
        val name = nameOf(message) ?: return false
        return ns == "SpeechRecognizer" && name == "RecognizeResult"
    }

    /**
     * 收集消息分发方法的候选。
     *
     * 逆向报告指出核心逻辑在 native 层，但 Java/Kotlin 层一定存在：
     *  - 一个把 (namespace, name) 映射到 payload Class 的接口实现（ApiNameMapping.findClass）
     *  - 一个接收 Event 并分发的监听器（常见签名：onEvent(Event) / onReceiveMessage(Message) /
     *    dispatchMessage(Message) / handleEvent(Event) / onMessage(Message)）
     *
     * 我们把这些候选全部记录下来，Hook 时逐个尝试（能挂上就挂，挂不上换下一个）。
     */
    private fun collectDispatchCandidates() {
        val names = listOf(
            "onEvent", "onReceiveEvent", "handleEvent", "dispatchEvent",
            "onMessage", "onReceiveMessage", "handleMessage", "dispatchMessage",
            "onInstruction", "onReceiveInstruction",
            "a", "b", "c", // 混淆后的单字母方法（配合参数类型过滤）
        )
        val owners = listOfNotNull(messageClass, eventHeaderClass, apiNameMappingClass)
            .toMutableList()
        // 加上所有继承 Message 的类（Event / Instruction）
        messageClass?.let { mc ->
            val loader = Reflector.ClassLoaderHolder.loader
            if (loader != null) {
                DexClassScanner.scan(loader).take(2000).forEach { n ->
                    runCatching {
                        val c = Class.forName(n, false, loader)
                        if (mc.isAssignableFrom(c) && c != mc) owners.add(c)
                    }
                }
            }
        }

        owners.distinct().forEach { owner ->
            names.forEach { mn ->
                val m = runCatching {
                    owner.declaredMethods.firstOrNull { it.name == mn && it.parameterCount >= 1 }
                }.getOrNull() ?: return@forEach
                dispatchMethodCandidates.add(MethodRef(owner, mn, m.parameterCount))
            }
        }
        XLog.i("收集到 ${dispatchMethodCandidates.size} 个分发方法候选")
    }

    // ------------------------------------------------------------------
    // 便捷读取器：所有方法都对混淆/版本差异做了容错

    /** 读 namespace，例如 "Template" */
    fun namespaceOf(messageOrHeader: Any?): String? {
        Reflector.getString(messageOrHeader, "namespace")?.let { return it }
        val header = Reflector.get<Any>(messageOrHeader, null, "header") ?: return null
        return Reflector.getString(header, "namespace")
    }

    /** 读 name，例如 "Toast" */
    fun nameOf(messageOrHeader: Any?): String? {
        Reflector.getString(messageOrHeader, "name")?.let { return it }
        val header = Reflector.get<Any>(messageOrHeader, null, "header") ?: return null
        return Reflector.getString(header, "name")
    }

    /** 读 header 对象 */
    fun headerOf(message: Any?): Any? = Reflector.get(message, null, "header")

    /** 读 dialog_id（可能在 header 上，也可能在 message 上） */
    fun dialogIdOf(message: Any?): String? {
        Reflector.getString(message, "dialog_id")?.let { if (it.isNotBlank()) return it }
        val header = headerOf(message) ?: return null
        return Reflector.getString(header, "dialog_id")
    }

    /** 读 payload 对象 */
    fun payloadOf(message: Any?): Any? = Reflector.get(message, null, "payload")

    /** 完整事件名，例如 "Template.Toast" */
    fun fullName(message: Any?): String? {
        val ns = namespaceOf(message) ?: return null
        val n = nameOf(message) ?: return null
        return "$ns.$n"
    }
}
