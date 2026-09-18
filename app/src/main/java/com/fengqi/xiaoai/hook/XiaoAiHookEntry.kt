package com.fengqi.xiaoai.hook

import android.app.Application
import android.content.Context
import com.fengqi.xiaoai.core.ModelManager
import com.fengqi.xiaoai.core.XLog
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.ConcurrentHashMap

/**
 * LSPosed 模块入口。
 *
 * ## Hook 策略总览
 *
 * 逆向报告确认 AIVS 的通信在 `libaivs_jni.so`（native），Java 层只提供
 * 「类型定义 + 回调接口」。因此 Java 层的 Hook 点有三层，按可靠性从高到低：
 *
 * | 层级 | 目标 | 说明 | 可靠性 |
 * |---|---|---|---|
 * | L1 | `Message` 子类实例的 `payload` 字段读取时机 | 找到消息分发方法，直接读 Event 对象 | ★★★ |
 * | L2 | `ApiNameMapping.findClass(namespace, name)` | 反查 (namespace,name)→payload 类，用于**动态发现**类名 | ★★★ |
 * | L3 | 字段指纹兜底 | 类名混淆时按字段名找类 | ★★ |
 *
 * 本模块同时挂下面几类 Hook，任何一类命中即可工作：
 *
 * 1. **分发方法 Hook**：扫描候选类（Message 的子类、含 message 参数的方法），
 *    Hook 参数类型为 Message 子类的方法，在 `afterHookedMethod` 里拿参数。
 * 2. **ApiNameMapping.findClass Hook**：被动收集 (namespace, name) → Class 的映射表，
 *    用于补齐 [AivsModel] 里没识别到的类，并在首次触发时**热更新** Hook 目标。
 * 3. **Application.attach**：拿到宿主的 Context / ClassLoader，初始化配置。
 * 4. **“我的”页面入口注入**（见 MinePageInjector）。
 *
 * ## 为什么用 afterHookedMethod 而不是 beforeHookedMethod
 * RecognizeResult 我们只需要**读取**（提取文本），读发生在方法执行前更自然，但
 * 有些实现里 payload 是在方法内部才被解析赋值的，所以采用「before + after 双读」，
 * 谁先拿到有效数据用谁。
 *
 * Toast 需要**改写**，改写必须在方法真正使用 payload 之前完成——所以用 before。
 */
class XiaoAiHookEntry : IXposedHookLoadPackage, IXposedHookZygoteInit {

    companion object {
        /** 目标包名（不同版本/渠道不同，全部覆盖） */
        private val TARGET_PACKAGES = setOf(
            "com.xiaomi.wearable",   // 小米运动健康（国际/新版）
            "com.xiaomi.hm.health",  // 小米运动健康（国内旧版）
        )

        @Volatile
        var modulePath: String? = null
            private set

        @Volatile
        private var engine: InterceptEngine? = null

        @Volatile
        var isHookedTarget = false
            private set
    }

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        modulePath = startupParam.modulePath
        XLog.i("Zygote 初始化，modulePath=${startupParam.modulePath}")
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName
        // 只有系统框架和目标 App 需要处理
        if (pkg != "android" && pkg !in TARGET_PACKAGES) return
        if (pkg !in TARGET_PACKAGES) return

        XLog.i("命中目标进程: $pkg (process=${lpparam.processName})")
        isHookedTarget = true

        try {
            hookApplicationAttach(lpparam)
        } catch (t: Throwable) {
            XLog.e("Hook 初始化失败", t)
        }
    }

    // ==================================================================
    // 1. Application.attach —— 拿 Context / ClassLoader，初始化引擎
    // ==================================================================

    private fun hookApplicationAttach(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedHelpers.findAndHookMethod(
            Application::class.java,
            "attach",
            Context::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val ctx = param.args[0] as? Context ?: return
                    onAppContextReady(ctx, lpparam)
                }
            }
        )
    }

    private fun onAppContextReady(ctx: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        if (engine != null) return
        synchronized(this) {
            if (engine != null) return

            // 1) 让反射层拿到宿主 ClassLoader —— 这是所有后续查找的基础
            Reflector.ClassLoaderHolder.loader = lpparam.classLoader

            // 1.5) Hook 跑在 com.mi.health 进程，UI 跑在 com.fengqi.xiaoai 进程。
            //      XLog.init(ctx) 内部会用 createPackageContext 跨 uid 拿到模块自己的
            //      filesDir，让两边日志落在同一路径。ModelManager.init 会顺带调用。
            //      这一行只为了让"路径已就绪"出现在最早的日志里。
            runCatching {
                XLog.i("Hook 进程启动，包名=${lpparam.packageName} pid=${android.os.Process.myPid()}")
            }

            // 2) 初始化配置（读 files/xiaoai_config.json，与设置页共享）
            runCatching { ModelManager.init(ctx) }
                .onFailure { XLog.e("ModelManager 初始化失败", it) }

            // 3) 启动配置轮询：手机端改设置后自动同步到本进程
            ModelManager.startPolling(1500L)

            val e = InterceptEngine()
            engine = e

            // 4) 解析 AIVS 模型 & 安装 Hook
            runCatching { installHooks(lpparam, e) }
                .onFailure { XLog.e("安装 Hook 失败", it) }

            // 5) 注入「我的」页面入口
            if (ModelManager.config().injectMineEntry) {
                runCatching { MinePageInjector.install(lpparam, ctx) }
                    .onFailure { XLog.e("注入 UI 入口失败", it) }

                // 终乐观标记：LSPosed 接入成功
                XLog.i("✓ XiaoAi Hook 已生效：HOST_PID=${android.os.Process.myPid()} PACKAGE=${lpparam.packageName}")
            }
        }
    }

    // ==================================================================
    // 2. 安装消息 Hook
    // ==================================================================

    private fun installHooks(lpparam: XC_LoadPackage.LoadPackageParam, engine: InterceptEngine) {
        val model = AivsModel.get()

        if (!model.resolved) {
            XLog.w(
                "⚠ AIVS 模型未完全解析（message=${model.messageClass} toast=${model.toastClass}）。\n" +
                    "  将启用「JSON 兜底模式」：Hook ApiNameMapping.findClass 与常见分发方法。\n" +
                    "  请把上面的类解析日志反馈给开发者。"
            )
        }

        var installed = 0
        installed += hookDispatchMethods(lpparam, engine, model)
        installed += hookApiNameMapping(lpparam, model)
        installed += hookJsonFallback(lpparam, engine)

        XLog.i("Hook 安装完成，共安装 $installed 个 Hook 点")
        if (installed == 0) {
            XLog.e("✗ 没有安装任何 Hook 点，模块不会生效！请检查 LSPosed 作用域是否勾选了 $TARGET_PACKAGES")
        }
    }

    // ---------------------------------------------------------------- 2.1 分发方法

    /**
     * Hook 所有「可能是消息分发入口」的方法。
     *
     * 判定条件（宽松匹配，宁可多挂）：
     *  - 方法有 1~3 个参数；
     *  - 任意一个参数类型可以接受 `Message`（即 Message 是它的父类/接口）；
     *  - 方法不是 getter/setter、不是构造器、不是 toString。
     */
    private fun hookDispatchMethods(
        lpparam: XC_LoadPackage.LoadPackageParam,
        engine: InterceptEngine,
        model: AivsModel,
    ): Int {
        val messageClass = model.messageClass
        var count = 0

        val candidateClasses = linkedSetOf<Class<*>>()
        model.messageClass?.let { candidateClasses.add(it) }
        model.eventHeaderClass?.let { candidateClasses.add(it) }
        model.apiNameMappingClass?.let { candidateClasses.add(it) }

        // 扫描所有 Message 的子类（Event / Instruction / 各种具体消息）
        messageClass?.let { mc ->
            DexClassScanner.scan(lpparam.classLoader).forEach { name ->
                runCatching {
                    val c = Class.forName(name, false, lpparam.classLoader)
                    if (mc.isAssignableFrom(c)) candidateClasses.add(c)
                }
            }
        }

        val skipNames = setOf(
            "toString", "hashCode", "equals", "clone", "writeReplace", "readResolve",
        )

        candidateClasses.forEach { owner ->
            runCatching {
                owner.declaredMethods.forEach { m ->
                    if (m.name in skipNames) return@forEach
                    if (java.lang.reflect.Modifier.isStatic(m.modifiers).not() &&
                        m.name.startsWith("get") && m.parameterCount == 0
                    ) {
                        return@forEach
                    }
                    if (m.parameterCount == 0 || m.parameterCount > 3) return@forEach
                    if (m.name.contains("$")) return@forEach

                    // 参数里是否有可能是 Message 的
                    val hasMessageParam = m.parameterTypes.any { pt ->
                        messageClass == null ||
                            pt.isAssignableFrom(messageClass) ||
                            messageClass.isAssignableFrom(pt) ||
                            pt.name.contains("Message") ||
                            pt.name.contains("Event") ||
                            pt.name.contains("Instruction")
                    }
                    if (!hasMessageParam) return@forEach

                    runCatching {
                        XposedBridge.hookMethod(m, DispatchHook(engine, owner, m))
                        count++
                        XLog.i("已 Hook 分发方法: ${owner.simpleName}#${m.name}(${m.parameterTypes.joinToString { it.simpleName }})")
                    }.onFailure { XLog.d("Hook ${owner.simpleName}#${m.name} 失败: ${it.message}") }
                }
            }
        }
        return count
    }

    /**
     * 分发方法的 Hook 实现。
     *
     * 关键：**不直接修改方法返回值**，而是在 before 阶段改写 payload 字段。
     * 因为方法内部往往会把 Event 转交给别的组件（UI、TTS），改返回值没用，
     * 只有改对象状态才能让后续所有消费者看到新文本。
     */
    private class DispatchHook(
        private val engine: InterceptEngine,
        private val owner: Class<*>,
        private val method: java.lang.reflect.Method,
    ) : XC_MethodHook() {

        override fun beforeHookedMethod(param: MethodHookParam) {
            try {
                handle(param, isBefore = true)
            } catch (t: Throwable) {
                XLog.e("DispatchHook before 异常 (${owner.simpleName}#${method.name})", t)
            }
        }

        override fun afterHookedMethod(param: MethodHookParam) {
            try {
                handle(param, isBefore = false)
            } catch (t: Throwable) {
                XLog.e("DispatchHook after 异常 (${owner.simpleName}#${method.name})", t)
            }
        }

        private fun handle(param: XC_MethodHook.MethodHookParam, isBefore: Boolean) {
            // 找到可能是消息对象的参数
            val candidates = param.args.filterNotNull().filter { arg ->
                val m = AivsModel.get()
                val header = Reflector.get<Any>(arg, null, "header")
                val payload = Reflector.get<Any>(arg, null, "payload")
                header != null && payload != null ||
                    (m.messageClass?.isInstance(arg) == true)
            }
            if (candidates.isEmpty()) return

            for (msg in candidates) {
                val model = AivsModel.get()

                // ---------- ASR 识别结果（所有版本都是 App→Cloud） ----------
                if (model.isRecognizeMessage(msg)) {
                    if (!isBefore) continue
                    val direct = engine.onRecognizeResult(msg)
                    if (direct != null) {
                        // 把应答文本暂存，等 Toast 到来时写入（手环会先显示"识别结果"，
                        // 真正展示给用户的是随后的 Toast）
                        engine.stashDirectText(direct)
                        XLog.i("指令应答已暂存，等待 Toast 落地")
                    }
                    continue
                }

                // ---------- 回答消息（Cloud→App；3.57.0 与 3.59.1 候选不同） ----------
                if (!model.isAnswerMessage(msg)) continue
                if (!isBefore) continue

                val cfg = ModelManager.config()
                val name = model.nameOf(msg).orEmpty()
                val ns = model.namespaceOf(msg).orEmpty()

                // 流式回答按配置决定是否拦截
                val isStream = name == AivsModel.NAME_TOAST_STREAM ||
                    name == AivsModel.NAME_STYLE_TOAST_STREAM_START
                if (isStream && !cfg.hookToastStream) continue

                val handled = engine.onAnswerMessage(msg) { text ->
                    writeText(msg, ns, name, text)
                }
                if (handled) {
                    engine.clearDirectText()
                }
            }
        }

        /**
         * 把文本写回 payload。
         *
         * 优先调用 setter（`setText(String)` / `setMarkdownText(String)`），
         * 因为 setter 里可能有额外的副作用（比如同步更新内部 JSON 缓存）。
         *
         * 字段选择与 [InterceptEngine.payloadTextField] 完全对应。
         */
        private fun writeText(msg: Any, ns: String, name: String, text: String): Boolean {
            val model = AivsModel.get()
            val payload = model.payloadOf(msg) ?: return false

            val isStream = name == AivsModel.NAME_TOAST_STREAM ||
                name == AivsModel.NAME_STYLE_TOAST_STREAM_START ||
                Reflector.fieldOf(payload.javaClass, "markdown_text") != null
            val hasMarkdown = Reflector.fieldOf(payload.javaClass, "markdown_text") != null

            val ok = if (isStream && hasMarkdown) {
                Reflector.invokeSetter(payload, "setMarkdownText", "markdown_text", text) ||
                    Reflector.invokeSetter(payload, "setMarkdownText", "text", text) ||
                    Reflector.set(payload, "markdown_text", text) ||
                    Reflector.set(payload, "text", text)
            } else {
                Reflector.invokeSetter(payload, "setText", "text", text) ||
                    Reflector.set(payload, "text", text)
            }
            XLog.d("写回 payload.${if (isStream && hasMarkdown) "markdown_text" else "text"} => ${ok}")
            return ok
        }
    }

    // ---------------------------------------------------------------- 2.2 ApiNameMapping

    /**
     * Hook `ApiNameMapping.findClass(namespace, name)`。
     *
     * 这是逆向报告里明确指出的类型路由入口，价值有两个：
     *  1. **被动发现**：每收到一条真实消息，native 就会调一次 findClass，
     *     我们由此得到准确的 (namespace,name) → Class 映射，用于校验/补齐模型。
     *  2. **自愈能力**：如果某个 payload 类没被提前解析到，第一次遇到时就补上并打印，
     *     方便开发者针对新版本快速适配。
     */
    private fun hookApiNameMapping(
        lpparam: XC_LoadPackage.LoadPackageParam,
        model: AivsModel,
    ): Int {
        var count = 0
        // 接口可能有多个实现，全部挂上
        val owners = mutableListOf<Class<*>>()
        model.apiNameMappingClass?.let { owners.add(it) }

        // 从 DEX 里补找实现类（接口的实现类名可能不含 ApiNameMapping）
        DexClassScanner.scan(lpparam.classLoader).forEach { n ->
            runCatching {
                val c = Class.forName(n, false, lpparam.classLoader)
                if (c.declaredMethods.any { m ->
                        m.name == "findClass" && m.parameterCount == 2
                    }
                ) {
                    owners.add(c)
                }
            }
        }

        owners.distinct().forEach { owner ->
            runCatching {
                owner.declaredMethods
                    .filter { it.name == "findClass" && it.parameterCount == 2 }
                    .forEach { m ->
                        m.isAccessible = true
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                runCatching {
                                    val ns = param.args.getOrNull(0) as? String ?: return
                                    val name = param.args.getOrNull(1) as? String ?: return
                                    val cls = param.result as? Class<*> ?: return
                                    ApiRegistry.register(ns, name, cls)
                                }
                            }
                        })
                        count++
                        XLog.i("已 Hook ApiNameMapping: ${owner.simpleName}#findClass")
                    }
            }.onFailure { XLog.d("Hook findClass 失败: ${it.message}") }
        }
        return count
    }

    // ---------------------------------------------------------------- 2.3 JSON 兜底

    /**
     * JSON 兜底模式。
     *
     * 当 AIVS 的类完全无法识别（比如被强混淆、或换成了纯 Kotlin data class），
     * 退化为 Hook **JSON 反序列化** 的公共出口。可以工作是因为：
     * AIVS 的 Java 层最终一定把 JSON 转成对象；即便 native 直接生成对象，
     * 也存在 `Message` 的 `setPayload` / `setHeader`。
     *
     * 注意：这一层只在前面完全失败时才启用（[AivsModel.resolved] 为 false）。
     */
    private fun hookJsonFallback(
        lpparam: XC_LoadPackage.LoadPackageParam,
        engine: InterceptEngine,
    ): Int {
        val model = AivsModel.get()
        if (model.resolved) return 0
        XLog.w("启用 JSON 兜底模式（兜底 Hook 收益有限，仅用于诊断）")

        var count = 0
        // Hook Message.setPayload：任何 payload 被设置时，尝试向上找 namespace/name
        model.messageClass?.let { mc ->
            runCatching {
                val m = mc.declaredMethods.firstOrNull { it.name == "setPayload" }
                if (m != null) {
                    m.isAccessible = true
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            runCatching {
                                val self = param.thisObject ?: return
                                val ns = model.namespaceOf(self) ?: return
                                val name = model.nameOf(self) ?: return
                                if (ns == "Template" && name == "Toast") {
                                    engine.onAnswerMessage(self) { text ->
                                        Reflector.set(param.args[0], "text", text)
                                    }
                                }
                            }
                        }
                    })
                    count++
                    XLog.i("已 Hook Message#setPayload（兜底）")
                }
            }
        }
        return count
    }
}

/**
 * ApiNameMapping 观测到的注册表。
 *
 * 用途：诊断 + 自愈。当发现 AIVS 实际使用的类与我们解析到的类不一致时，
 * 打印告警，便于开发者快速定位版本差异。
 */
internal object ApiRegistry {

    private val map = ConcurrentHashMap<String, Class<*>>()
    private val logged = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    fun register(namespace: String, name: String, cls: Class<*>) {
        val key = "$namespace.$name"
        val prev = map.put(key, cls)
        if (prev == null || prev != cls) {
            // 只在我们关心的命名空间打印，避免刷屏
            if (namespace == "Template" || namespace == "SpeechRecognizer") {
                if (logged.add(key)) {
                    XLog.i("ApiNameMapping: $key -> ${cls.name}")
                }
            }
        }
    }

    fun get(namespace: String, name: String): Class<*>? = map["$namespace.$name"]

    fun all(): Map<String, Class<*>> = map.toMap()

    /** 诊断用：打印所有观测到的 SpeechRecognizer / Template 相关映射 */
    fun dumpRelevant(): String = map.entries
        .filter { it.key.startsWith("Template.") || it.key.startsWith("SpeechRecognizer.") }
        .joinToString("\n") { "  ${it.key} -> ${it.value.name}" }
        .ifBlank { "  (尚未观测到任何映射，请先在手环上说一句话)" }
}
