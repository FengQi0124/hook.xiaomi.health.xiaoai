package com.zeroone01.xiaoai.hook

import android.app.Application
import android.content.Context
import android.util.Log
import com.zeroone01.xiaoai.core.HostEnv
import com.zeroone01.xiaoai.core.ModelManager
import com.zeroone01.xiaoai.core.XLog
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.ArrayList
import java.util.concurrent.ConcurrentHashMap

/**
 * LSPosed 模块入口（libxposed 现代 API，API 102）。
 *
 * ## ★ 本次修复重点：hookDispatchMethods 中 RecognizeResult 的完整路由
 *
 * 之前的问题：dispatch hook 捕获到 RecognizeResult 后只是读取 payload 字段，
 * 但没有调用 engine.onRecognizeResult() 把文本交给 VoiceCommandHandler 处理。
 * 因此"切换模型"指令永远不被检测，永远不进入选择模式，手环无响应。
 *
 * 修复后：RecognizeResult → engine.onRecognizeResult() → 返回值非空时
 *        → engine.stashDirectText(text) 暂存 → 等 Toast 到来时替换
 */
class XiaoAiHookEntry : XposedModule(), ModuleBridge {

    companion object {
        private val TARGET_PACKAGES = setOf(
            "com.mi.health",
            "com.xiaomi.wearable",
            "com.xiaomi.hm.health",
        )
        private const val TAG = "XiaoAi"
        @Volatile var isHookedTarget = false
            private set
    }

    @Volatile private var engine: InterceptEngine? = null
    @Volatile private var uiInstalled = false

    override fun hookExecutable(executable: Executable): XposedInterface.HookBuilder = hook(executable)

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        log(Log.INFO, TAG, "═══════════════════════════════════════════")
        log(Log.INFO, TAG, "✅ 模块已被 LSPosed 加载（现代 API）")
        log(Log.INFO, TAG, "框架: $frameworkName / $frameworkVersion")
        log(Log.INFO, TAG, "API: $apiVersion")
        log(Log.INFO, TAG, "进程: ${param.processName}")
        runCatching { HostEnv.cacheModuleAppInfo(moduleApplicationInfo) }
            .onFailure { XLog.e("[$TAG] 缓存模块 ApplicationInfo 失败", it) }
        XLog.frameworkLogger = { priority, tag, msg ->
            try { log(priority, tag, msg) } catch (_: Throwable) {}
        }
        XLog.i("[$TAG] XLog.frameworkLogger 已桥接")
        log(Log.INFO, TAG, "═══════════════════════════════════════════")
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        val pkg = param.packageName
        if (pkg !in TARGET_PACKAGES) return
        XLog.i("[$TAG] 🎯 命中目标进程: $pkg")
        isHookedTarget = true
        runCatching { param.applicationInfo?.let { HostEnv.cacheHostAppInfo(it) } }
            .onFailure { XLog.e("[$TAG] 缓存宿主 ApplicationInfo 失败", it) }
        Reflector.ClassLoaderHolder.loader = param.defaultClassLoader
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        val pkg = param.packageName
        if (pkg !in TARGET_PACKAGES) return
        if (uiInstalled) return
        val loader = param.classLoader
        Reflector.ClassLoaderHolder.loader = loader
        XLog.i("[$TAG] PackageReady: $pkg")

        val app = appFrom(loader)
        runCatching { installAivsHooks(loader, app) }
            .onFailure { XLog.e("[$TAG] 安装 AIVS Hook 失败", it) }

        if (app == null) {
            XLog.w("[$TAG] 拿不到 Application，改挂 Application.attach 兜底")
            hookApplicationAttach(pkg, loader)
            return
        }
        onAppReady(app, pkg, loader)
    }

    private fun appFrom(loader: ClassLoader): Application? {
        runCatching {
            val atClass = Class.forName("android.app.ActivityThread", false, loader)
            val at = Reflect.callStaticMethod(atClass, "currentActivityThread") ?: return@runCatching
            Reflect.get<Application>(at, "mInitialApplication")?.let { return it }
        }
        runCatching {
            val atClass = Class.forName("android.app.ActivityThread", false, loader)
            val at = Reflect.callStaticMethod(atClass, "currentActivityThread") ?: return@runCatching
            val all = Reflect.get<ArrayList<Application>>(at, "mAllApplications")
            all?.firstOrNull()?.let { return it }
        }
        return null
    }

    private fun hookApplicationAttach(pkg: String, loader: ClassLoader) {
        val attach = runCatching {
            Application::class.java.getDeclaredMethod("attach", Context::class.java)
        }.getOrNull() ?: return
        HookCompat.hook(this, attach,
            after = { ctx -> val app = ctx.thisObject as? Application ?: return@hook; onAppReady(app, pkg, loader) },
            tag = "Application#attach")
    }

    private fun onAppReady(app: Application, pkg: String, loader: ClassLoader) {
        if (uiInstalled) return
        synchronized(this) {
            if (uiInstalled) return
            uiInstalled = true
            XLog.i("[$TAG] Hook 进程启动：pkg=$pkg")
            runCatching { ModelManager.init(app) }.onFailure { XLog.e("[$TAG] ModelManager 初始化失败", it) }
            if (ModelManager.config().injectMineEntry) {
                runCatching { SettingsPageInjector.install(this, loader, app) }
                    .onFailure { XLog.e("[$TAG] 注入设置页入口失败", it) }
            }
            XLog.i("[$TAG] ✓ XiaoAi Hook 已生效")
        }
    }

    // ==================================================================
    // 核心修复：完整实现 installAivsHooks + hookDispatchMethods
    // ==================================================================

    private fun installAivsHooks(loader: ClassLoader, app: Application?) {
        val model = AivsModel.get()
        if (!model.resolved) {
            XLog.w("[$TAG] ⚠ AIVS 模型未完全解析，将启用 JSON 兜底模式")
        }
        val e = InterceptEngine()
        engine = e

        var installed = 0
        // 第 1 层：线上 JSON 钩子（对齐 xiaoai_hijack.py 的网络层视角，最可靠）
        installed += hookJsonLayer(loader, e, model)
        // 第 2 层：消息分发方法（扫描范围扩大到 engine / listener 类，不只 Message 家族）
        installed += hookDispatchMethods(loader, e, model)
        // 第 3 层：Message 子类构造器（对象创建即路由）
        installed += hookMessageConstructors(loader, e, model)
        // 第 4 层：Message.getPayload / payload getter 保底
        installed += hookApiNameMapping(loader, model)
        installed += hookMessageGetPayload(loader, e, model)
        installed += hookPayloadGetters(e, model)

        XLog.i("[$TAG] Hook 安装完成，共安装 $installed 个 Hook 点")
        if (installed == 0) {
            XLog.e("[$TAG] ✗ 没有安装任何 Hook 点，模块不会生效！")
        }
    }

    // ==================================================================
    // 第 0 层：统一消息路由 + 去重
    // ==================================================================

    /** 已处理过的消息（identity hash），防止同一条消息被多层钩子重复路由 */
    private val seenMessages: MutableSet<Int> =
        java.util.Collections.synchronizedSet(mutableSetOf<Int>())

    private fun markSeen(msg: Any): Boolean {
        val added = seenMessages.add(System.identityHashCode(msg))
        if (seenMessages.size > 4096) seenMessages.clear() // 防缓慢泄漏
        return added
    }

    /** 已处理过的线上消息（按 dialog+文本去重，JSON 层不同对象重复解析同一条消息时用） */
    private val seenWireKeys: MutableSet<String> =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private fun markWireSeen(key: String): Boolean {
        val added = seenWireKeys.add(key)
        if (seenWireKeys.size > 512) seenWireKeys.clear()
        return added
    }

    /**
     * 把一条 Java Message 对象路由给引擎。
     *
     * 所有 Java 对象层钩子（dispatch / 构造器 / getPayload / Gson）都走这里，
     * 由 [markSeen] 保证同一条消息只被处理一次。
     */
    private fun routeMessage(engine: InterceptEngine, model: AivsModel, message: Any, source: String) {
        if (!markSeen(message)) return
        try {
            when {
                model.isRecognizeMessage(message) -> {
                    XLog.i("[$TAG] ($source) 捕获 RecognizeResult")
                    val directText = engine.onRecognizeResult(message)
                    if (directText != null) {
                        engine.stashDirectText(directText)
                        XLog.i("[$TAG] ($source) 指令应答已暂存: ${directText.take(40)}")
                    }
                }
                model.isAnswerMessage(message) -> {
                    XLog.i("[$TAG] ($source) 捕获回答消息: ${model.fullName(message)}")
                    val handled = engine.onAnswerMessage(message) { text ->
                        writePayloadText(message, model.nameOf(message) ?: "", text)
                    }
                    if (handled) XLog.i("[$TAG] ($source) 回答已替换")
                }
                else -> {
                    val full = model.fullName(message)
                    if (full != null) XLog.d("[$TAG] ($source) 消息: $full")
                }
            }
        } catch (t: Throwable) {
            XLog.d("[$TAG] ($source) 路由异常: ${t.message}")
        }
    }

    /**
     * ★ 核心修复点：hookDispatchMethods
     *
     * 之前的问题：这个方法是截断的，ASR 识别结果(RecognizeResult)虽然被 hook 捕获，
     * 但只是读了字段，没有调用 engine.onRecognizeResult() 进行语音指令路由。
     *
     * 修复后流程：
     * 1. 遍历所有可能是消息分发入口的方法
     * 2. 在 after 阶段读取参数中的 Message 对象
     * 3. 通过 AivsModel 判断消息类型（RecognizeResult vs Toast vs 其他）
     * 4. RecognizeResult → engine.onRecognizeResult() → 返回菜单文本 → stashDirectText
     * 5. Toast/onAnswerMessage → 检查 pendingDirectText → 替换 payload
     */
    private fun hookDispatchMethods(
        loader: ClassLoader,
        engine: InterceptEngine,
        model: AivsModel,
    ): Int {
        val messageClass = model.messageClass
        var count = 0
        val candidateClasses = linkedSetOf<Class<*>>()
        messageClass?.let { candidateClasses.add(it) }
        model.eventHeaderClass?.let { candidateClasses.add(it) }
        model.apiNameMappingClass?.let { candidateClasses.add(it) }

        // ★ 扩大扫描范围：不只扫 Message 子类。
        // AIVS SDK 真正的分发方法在 engine / listener / handler / callback 类里，
        // 它们**不是** Message 的子类（Message 子类只是纯数据 POJO，没有分发逻辑）。
        // 按包名前缀圈定 AIVS SDK + 宿主语音模块，逐个类找「带 Message 参数的方法」。
        DexClassScanner.scan(loader)
            .filter { name ->
                name.startsWith("com.xiaomi.ai") ||
                    name.startsWith("com.xiaomi.aiasst") ||
                    name.contains("aivs", ignoreCase = true) ||
                    name.contains("speech", ignoreCase = true)
            }
            .forEach { name ->
                runCatching {
                    val c = Class.forName(name, false, loader)
                    if (!c.isInterface && !c.isEnum && !c.isAnnotation) candidateClasses.add(c)
                }
            }
        XLog.i("[$TAG] 分发方法扫描候选类 ${candidateClasses.size} 个")

        val skipNames = setOf(
            "toString", "hashCode", "equals", "clone", "writeReplace", "readResolve", "findClass",
        )

        candidateClasses.forEach { owner ->
            runCatching {
                owner.declaredMethods.forEach { m ->
                    if (m.name in skipNames) return@forEach
                    if (!Modifier.isStatic(m.modifiers) && m.name.startsWith("get") && m.parameterCount == 0) return@forEach
                    if (m.parameterCount == 0 || m.parameterCount > 3) return@forEach
                    if (m.name.contains("$")) return@forEach

                    val hasMessageParam = m.parameterTypes.any { pt ->
                        pt.name.contains("Message") ||
                            pt.name.contains("Event") ||
                            pt.name.contains("Instruction") ||
                            (messageClass != null && pt != Any::class.java &&
                                (pt.isAssignableFrom(messageClass) || messageClass.isAssignableFrom(pt)))
                    }
                    if (!hasMessageParam) return@forEach

                    val ok = HookCompat.hook(
                        this, m,
                        after = { ctx ->
                            try {
                                // 从参数里找 Message 对象
                                val message = ctx.args.firstOrNull { arg ->
                                    arg != null && messageClass?.isInstance(arg) == true
                                        || arg != null && (arg.javaClass.name.contains("Message")
                                        || arg.javaClass.name.contains("Event")
                                        || arg.javaClass.name.contains("Instruction"))
                                } ?: return@hook
                                routeMessage(engine, model, message, "dispatch:${owner.simpleName}#${m.name}")
                            } catch (t: Throwable) {
                                XLog.d("[$TAG] dispatch after 处理异常: ${t.message}")
                            }
                        },
                        tag = "${owner.simpleName}#${m.name}",
                    )
                    if (ok) {
                        count++
                        XLog.i("[$TAG] 已挂载分发方法: ${owner.simpleName}#${m.name}")
                    }
                }
            }.onFailure { XLog.d("[$TAG] hook ${owner.simpleName} 失败: ${it.message}") }
        }
        return count
    }

    // ---------------------------------------------------------------- Message 构造器钩子

    /**
     * 第 3 层：hook 所有 Message 子类的构造器。
     *
     * 无论 SDK 是 native NewObject、Gson 反射、还是手动 new，消息对象一定要走构造器。
     * 构造完成后 header/payload 已填好，直接路由。
     */
    private fun hookMessageConstructors(
        loader: ClassLoader,
        engine: InterceptEngine,
        model: AivsModel,
    ): Int {
        val mc = model.messageClass ?: return 0
        var count = 0
        DexClassScanner.scan(loader).forEach { name ->
            runCatching {
                val c = Class.forName(name, false, loader)
                if (!mc.isAssignableFrom(c) || c == mc || c.isInterface) return@runCatching
                c.declaredConstructors.forEach { ctor ->
                    val ok = HookCompat.hook(this, ctor,
                        after = { ctx ->
                            val msg = ctx.thisObject ?: return@hook
                            routeMessage(engine, model, msg, "ctor:${c.simpleName}")
                        },
                        tag = "${c.simpleName}#<init>")
                    if (ok) count++
                }
            }
        }
        if (count > 0) XLog.i("[$TAG] 已挂载 $count 个 Message 构造器钩子")
        return count
    }

    // ==================================================================
    // 第 1 层：线上 JSON 钩子（与 xiaoai_hijack.py 的网络层视角完全等价）
    // ==================================================================

    /**
     * py 脚本在 WebSocket 文本帧上工作：看到 JSON、识别 namespace.name、改写 payload.text。
     * Java 层等价物：SDK 解析线上 JSON 的入口点。
     *
     *  - `org.json.JSONObject(String)`：构造后 SDK 才开始读字段，
     *    after-hook 里改 payload 等同于 py 的「转发前改 body」；
     *  - `Gson.fromJson(String, …)`：SDK 若用 Gson，返回的就是 Message 对象，直接路由；
     *    同时把原始 JSON 字符串过一遍 handleWireJsonText（应对 payload 反序列化成 Map 的情况）；
     *  - Jackson / Moshi：存在才挂，逻辑同上。
     */
    private fun hookJsonLayer(loader: ClassLoader, engine: InterceptEngine, model: AivsModel): Int {
        var count = 0
        count += hookOrgJson(loader, engine)
        count += hookGson(loader, engine, model)
        count += hookJackson(loader, engine)
        if (count == 0) {
            XLog.w("[$TAG] JSON 层钩子一个都没挂上（SDK 可能在 native 层解析 JSON）")
        }
        return count
    }

    private fun hookOrgJson(loader: ClassLoader, engine: InterceptEngine): Int {
        val jsonClass = runCatching {
            Class.forName("org.json.JSONObject", false, loader)
        }.getOrNull() ?: return 0
        var count = 0
        jsonClass.declaredConstructors
            .filter { it.parameterCount == 1 && it.parameterTypes[0] == String::class.java }
            .forEach { ctor ->
                val ok = HookCompat.hook(this, ctor,
                    after = { ctx ->
                        val jo = ctx.thisObject as? org.json.JSONObject ?: return@hook
                        handleWireJson(jo, engine, "org.json")
                    },
                    tag = "JSONObject#<init>(String)")
                if (ok) count++
            }
        if (count > 0) XLog.i("[$TAG] 已挂载 org.json.JSONObject(String) 构造器钩子")
        return count
    }

    private fun hookGson(loader: ClassLoader, engine: InterceptEngine, model: AivsModel): Int {
        val gsonClass = runCatching {
            Class.forName("com.google.gson.Gson", false, loader)
        }.getOrNull() ?: return 0
        var count = 0
        gsonClass.declaredMethods
            .filter { it.name == "fromJson" && it.parameterCount == 2 }
            .forEach { m ->
                val ok = HookCompat.hook(this, m,
                    after = { ctx ->
                        // 路径 A：反序列化结果就是 Message 对象 → 直接路由（改写会随对象传播）
                        val result = ctx.result
                        if (result != null && model.messageClass?.isInstance(result) == true) {
                            routeMessage(engine, model, result, "gson")
                            return@hook
                        }
                        // 路径 B：结果是 Map/其他 → 用原始 JSON 文本走线上协议处理
                        val raw = ctx.arg(0) as? String ?: return@hook
                        handleWireJsonText(raw, engine, "gson-text")
                    },
                    tag = "Gson#fromJson")
                if (ok) count++
            }
        if (count > 0) XLog.i("[$TAG] 已挂载 $count 个 Gson.fromJson 钩子")
        return count
    }

    private fun hookJackson(loader: ClassLoader, engine: InterceptEngine): Int {
        val mapperClass = runCatching {
            Class.forName("com.fasterxml.jackson.databind.ObjectMapper", false, loader)
        }.getOrNull() ?: return 0
        var count = 0
        mapperClass.declaredMethods
            .filter { (it.name == "readValue" || it.name == "readTree") && it.parameterCount >= 1 }
            .forEach { m ->
                val ok = HookCompat.hook(this, m,
                    after = { ctx ->
                        val raw = ctx.arg(0) as? String ?: return@hook
                        handleWireJsonText(raw, engine, "jackson")
                    },
                    tag = "ObjectMapper#${m.name}")
                if (ok) count++
            }
        if (count > 0) XLog.i("[$TAG] 已挂载 $count 个 Jackson 钩子")
        return count
    }

    // ---------------------------------------------------------------- 线上 JSON 处理

    /**
     * 文本快速预检：99% 的 JSON 都不是 AIVS 协议消息，先按关键字过滤再真正解析，
     * 避免给宿主 App 的每次 JSON 解析都加一次 new JSONObject 的开销。
     */
    private fun handleWireJsonText(raw: String, engine: InterceptEngine, source: String) {
        if (raw.length < 20 || raw.length > 256 * 1024) return
        if (!raw.contains("\"namespace\"")) return
        if (!raw.contains("SpeechRecognizer") && !raw.contains("\"Template\"")
            && !raw.contains("GenerateSpeak")) return
        runCatching { handleWireJson(org.json.JSONObject(raw), engine, source) }
    }

    /**
     * 处理一条线上协议 JSON —— 与 xiaoai_hijack.py 的 websocket_message 完全对齐：
     *
     * ```
     * SpeechRecognizer.RecognizeResult(is_final) → 记录 origin_text / 检测语音指令
     * Template.Toast(等)                        → 用 pending 提问调 AI，改写 payload.text
     * ```
     */
    private fun handleWireJson(jo: org.json.JSONObject, engine: InterceptEngine, source: String) {
        try {
            val header = jo.optJSONObject("header") ?: return
            val ns = header.optString("namespace", "")
            val name = header.optString("name", "")
            if (ns.isEmpty() || name.isEmpty()) return
            val dialogId = header.optString("dialog_id", "")

            when {
                ns == "SpeechRecognizer" && name == "RecognizeResult" -> {
                    val payload = jo.optJSONObject("payload") ?: return
                    if (!payload.optBoolean("is_final", false)) return
                    val item = payload.optJSONArray("results")?.optJSONObject(0)
                    val text = item?.optString("origin_text", "")
                        ?.ifBlank { item.optString("text", "") }
                        ?.ifBlank { item.optString("query_before_itn", "") }
                    if (text.isNullOrBlank()) return
                    // 去重：同一条识别文本只处理一次（防止多层 JSON 钩子重复触发指令状态机）
                    if (!markWireSeen("RR.$dialogId.${text.hashCode()}")) return
                    XLog.i("[$TAG] ($source) 线上 RecognizeResult: $text")
                    val direct = engine.onRecognizeJson(dialogId, text)
                    if (direct != null) {
                        engine.stashDirectText(direct)
                        XLog.i("[$TAG] ($source) 指令应答已暂存: ${direct.take(40)}")
                    }
                }
                ns == "Template" && name in AivsModel.ANSWER_TEMPLATE_NAMES -> {
                    val payload = jo.optJSONObject("payload") ?: return
                    val field = if (payload.has("markdown_text")) "markdown_text" else "text"
                    if (!payload.has(field)) return
                    if (!markWireSeen("ANS.$dialogId.$name")) return
                    XLog.i("[$TAG] ($source) 线上回答消息: $ns.$name dialog=$dialogId")
                    val handled = engine.onAnswerJson(dialogId) { newText ->
                        payload.put(field, newText)
                        XLog.i("[$TAG] ($source) 已改写 payload.$field")
                        true
                    }
                    if (handled) XLog.i("[$TAG] ($source) 回答已替换")
                }
                ns == "Application" && name == AivsModel.NAME_GENERATE_SPEAK -> {
                    val payload = jo.optJSONObject("payload") ?: return
                    if (!payload.has("text")) return
                    if (!markWireSeen("ANS.$dialogId.$name")) return
                    XLog.i("[$TAG] ($source) 线上回答消息: $ns.$name dialog=$dialogId")
                    engine.onAnswerJson(dialogId) { newText ->
                        payload.put("text", newText)
                        true
                    }
                }
            }
        } catch (t: Throwable) {
            XLog.d("[$TAG] ($source) 线上 JSON 处理异常: ${t.message}")
        }
    }

    // ---------------------------------------------------------------- payload getter 保底

    private val processingPayloads = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<Any, Boolean>()
    )

    private fun hookPayloadGetters(engine: InterceptEngine, model: AivsModel): Int {
        var count = 0
        data class GetterTarget(val owner: Class<*>, val getters: List<String>)

        val targets = mutableListOf<GetterTarget>()
        model.toastClass?.let { targets.add(GetterTarget(it, listOf("getText"))) }
        model.toastV2Class?.let { targets.add(GetterTarget(it, listOf("getText"))) }
        model.toastStreamClass?.let { targets.add(GetterTarget(it, listOf("getMarkdownText", "getText"))) }
        model.styleToastStreamStartClass?.let { targets.add(GetterTarget(it, listOf("getMarkdownText", "getText"))) }
        model.generateSpeakClass?.let { targets.add(GetterTarget(it, listOf("getText"))) }

        targets.forEach { target ->
            target.getters.forEach { getterName ->
                Reflect.findMethodsByName(target.owner, getterName)
                    .filter { it.parameterCount == 0 && it.returnType == String::class.java }
                    .forEach { m ->
                        val ok = HookCompat.hook(this, m,
                            before = { ctx ->
                                val payload = ctx.thisObject ?: return@hook
                                if (!processingPayloads.add(payload)) return@hook
                                try {
                                    val handled = engine.onAnswerPayloadOnly(payload) { text ->
                                        writePayloadText(payload, currentName(payload), text)
                                    }
                                    if (handled) {
                                        val reply = Reflector.getString(payload, currentFieldName(payload))
                                        if (!reply.isNullOrBlank()) {
                                            ctx.returnAndSkip(reply)
                                        }
                                    }
                                } finally {
                                    processingPayloads.remove(payload)
                                }
                            },
                            tag = "${target.owner.simpleName}#$getterName",
                        )
                        if (ok) {
                            count++
                            XLog.i("[$TAG] 已 Hook ${target.owner.simpleName}#$getterName（getter 保底）")
                        }
                    }
            }
        }
        return count
    }

    private fun currentName(payload: Any): String {
        val cls = payload.javaClass.simpleName
        return when {
            cls.contains("ToastStream") || cls.contains("StyleToastStream") -> AivsModel.NAME_TOAST_STREAM
            else -> AivsModel.NAME_TOAST
        }
    }

    private fun currentFieldName(payload: Any): String =
        if (Reflector.fieldOf(payload?.javaClass, "markdown_text") != null) "markdown_text" else "text"

    // ---------------------------------------------------------------- ApiNameMapping

    private fun hookApiNameMapping(loader: ClassLoader, model: AivsModel): Int {
        val mappingClass = model.apiNameMappingClass ?: return 0
        var count = 0
        val findClassMethod = runCatching {
            mappingClass.getDeclaredMethod("findClass", String::class.java, String::class.java)
                .also { it.isAccessible = true }
        }.getOrNull() ?: return 0

        val ok = HookCompat.hook(this, findClassMethod, after = { ctx ->
            val result = ctx.result
            val ns = ctx.arg(0) as? String ?: return@hook
            val name = ctx.arg(1) as? String ?: return@hook
            if (result is Class<*>) {
                XLog.d("[$TAG] ApiNameMapping: $ns.$name → ${result.name}")
            }
        }, tag = "ApiNameMapping#findClass")
        if (ok) count++
        return count
    }

    // ---------------------------------------------------------------- Message.getPayload 兜底

    private fun hookMessageGetPayload(loader: ClassLoader, engine: InterceptEngine, model: AivsModel): Int {
        val messageClass = model.messageClass ?: return 0
        var count = 0
        val getPayloadMethods = Reflect.findMethodsByName(messageClass, "getPayload")
            .filter { it.parameterCount == 0 }
        getPayloadMethods.forEach { m ->
            val ok = HookCompat.hook(this, m, before = { ctx ->
                val message = ctx.thisObject ?: return@hook
                routeMessage(engine, model, message, "getPayload")
            }, tag = "Message#getPayload")
            if (ok) {
                count++
                XLog.i("[$TAG] 已挂载 Message#getPayload 通用分发钩子")
            }
        }
        return count
    }

    // ---------------------------------------------------------------- 工具方法

    /**
     * 把文本写回 payload 对象的 text/markdown_text 字段
     */
    private fun writePayloadText(message: Any, name: String, text: String): Boolean {
        val payload = AivsModel.get().payloadOf(message) ?: return false
        val fieldName = if (Reflector.fieldOf(payload.javaClass, "markdown_text") != null) "markdown_text" else "text"
        return Reflector.set(payload, fieldName, text)
    }
}
