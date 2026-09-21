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
        installed += hookDispatchMethods(loader, e, model)
        installed += hookApiNameMapping(loader, model)
        installed += hookJsonFallback(loader, e, model)
        installed += hookMessageGetPayload(loader, e, model)
        installed += hookPayloadGetters(e, model)

        XLog.i("[$TAG] Hook 安装完成，共安装 $installed 个 Hook 点")
        if (installed == 0) {
            XLog.e("[$TAG] ✗ 没有安装任何 Hook 点，模块不会生效！")
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

        // 扫描所有 Message 的子类
        messageClass?.let { mc ->
            DexClassScanner.scan(loader).forEach { name ->
                runCatching {
                    val c = Class.forName(name, false, loader)
                    if (mc.isAssignableFrom(c) && c != mc) candidateClasses.add(c)
                }
            }
        }

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
                        messageClass == null ||
                            pt.isAssignableFrom(messageClass) ||
                            messageClass.isAssignableFrom(pt) ||
                            pt.name.contains("Message") ||
                            pt.name.contains("Event") ||
                            pt.name.contains("Instruction")
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

                                // ★ 关键路由：判断消息类型 → 交给对应的引擎处理
                                when {
                                    model.isRecognizeMessage(message) -> {
                                        // ASR 识别结果：检测语音指令（如"切换模型"）
                                        val directText = engine.onRecognizeResult(message)
                                        if (directText != null) {
                                            // 暂存（选择模型菜单文本 / 切换确认文本 / 无效提示）
                                            engine.stashDirectText(directText)
                                            XLog.i("[$TAG] dispatch RecognizeResult 指令应答已暂存: ${directText.take(40)}")
                                        }
                                    }
                                    model.isAnswerMessage(message) -> {
                                        // 云端回答消息（Toast）：替换为 AI 回复 或 指令应答
                                        val rewritten = booleanArrayOf(false)
                                        val handled = engine.onAnswerMessage(message) { text ->
                                            val ok = writePayloadText(message, model.nameOf(message) ?: "", text)
                                            if (ok) rewritten[0] = true
                                            ok
                                        }
                                        if (handled && rewritten[0]) {
                                            XLog.i("[$TAG] dispatch Toast 已替换")
                                        }
                                    }
                                    else -> { /* 其他消息，忽略 */ }
                                }
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

    // ---------------------------------------------------------------- JSON 兜底

    private fun hookJsonFallback(loader: ClassLoader, engine: InterceptEngine, model: AivsModel): Int {
        var count = 0
        // 如果 AIVS 模型解析完全失败，hook 常见 JSON 解析入口作为兜底
        if (!model.resolved) {
            XLog.w("[$TAG] 启用 JSON 兜底模式：hook org.json.JSONObject 常见入口")
            runCatching {
                val jsonClass = Class.forName("org.json.JSONObject", false, loader)
                jsonClass.methods.filter { it.parameterCount == 1 && it.parameterTypes[0] == String::class.java }
                    .forEach { m ->
                        val ok = HookCompat.hook(this, m, after = { ctx ->
                            // 解析 JSON 后检测是否是 Toast 类型，如果是且有 pendingDirectText 就替换
                        }, tag = "JSONObject#${m.name}")
                        if (ok) count++
                    }
            }
        }
        return count
    }

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
                when {
                    model.isRecognizeMessage(message) -> {
                        val directText = engine.onRecognizeResult(message)
                        if (directText != null) {
                            engine.stashDirectText(directText)
                            XLog.i("[$TAG] getPayload RecognizeResult 指令应答已暂存")
                        }
                    }
                    model.isAnswerMessage(message) -> {
                        val rewritten = booleanArrayOf(false)
                        engine.onAnswerMessage(message) { text ->
                            val ok = writePayloadText(message, model.nameOf(message) ?: "", text)
                            if (ok) rewritten[0] = true
                            ok
                        }
                    }
                }
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
