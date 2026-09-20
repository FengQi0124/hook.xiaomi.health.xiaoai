package com.zeroone01.xiaoai.hook

import android.app.Application
import android.content.Context
import android.util.Log
import com.zeroone01.xiaoai.core.ModelManager
import com.zeroone01.xiaoai.core.XLog
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * LSPosed 模块入口（libxposed 现代 API，API 102）。
 *
 * ## 为什么不用 legacy API
 * `de.robv.android.xposed`（API 82 及更早）是 Xposed 时代的旧接口。LSPosed 从 1.9 起
 * 就不再加载 legacy 模块，模块在管理器里能看到、也能勾选作用域，但**框架根本不会调用它**——
 * 表现为「App 里没有入口、LSPosed 日志里也没有任何本模块输出」。
 *
 * 现代 API 的入口形态完全不同：
 *
 * | legacy | 现代 API（102） |
 * |---|---|
 * | `implements IXposedHookLoadPackage` | `extends XposedModule` |
 * | `initZygote(StartupParam)` | `onModuleLoaded(ModuleLoadedParam)` |
 * | `handleLoadPackage(lpparam)` | `onPackageLoaded(PackageLoadedParam)` |
 * | 无 | `onPackageReady(PackageReadyParam)` ← 有 Application/Context 了 |
 * | `XposedHelpers` / `XposedBridge` | **框架不再提供**，见 [Reflect] / [HookCompat] |
 * | `AndroidAppHelper` | `param.getApplicationInfo().getClassLoader()` |
 *
 * ## Hook 策略总览
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
 *    Hook 参数类型为 Message 子类的方法，在 after 阶段拿参数。
 * 2. **ApiNameMapping.findClass Hook**：被动收集 (namespace, name) → Class 的映射表，
 *    用于补齐 [AivsModel] 里没识别到的类，并在首次触发时**热更新** Hook 目标。
 * 3. **Application.attach**：拿到宿主的 Context / ClassLoader，初始化配置。
 * 4. **「设置」页面入口注入**（见 [SettingsPageInjector]）。
 *
 * ## 为什么用 before + after 双读
 * RecognizeResult 我们只需要**读取**（提取文本），读发生在方法执行前更自然，但
 * 有些实现里 payload 是在方法内部才被解析赋值的，所以采用「before + after 双读」，
 * 谁先拿到有效数据用谁。
 *
 * Toast 需要**改写**，改写必须在方法真正使用 payload 之前完成——所以用 before。
 */
class XiaoAiHookEntry : XposedModule(), ModuleBridge {

    companion object {
        /** 目标包名（不同版本/渠道不同，全部覆盖） */
        private val TARGET_PACKAGES = setOf(
            "com.mi.health",          // 小米运动健康（3.59.1+，com.xiaomi.wearable 被替换）
            "com.xiaomi.wearable",    // 小米运动健康（国际/老版）
            "com.xiaomi.hm.health",   // 小米运动健康（国内旧版）
        )

        private const val TAG = "XiaoAi"

        @Volatile
        var isHookedTarget = false
            private set
    }

    /** 引擎实例，进程内唯一 */
    @Volatile
    private var engine: InterceptEngine? = null

    /** 已注入 UI 入口的 Application，防止重复注入 */
    @Volatile
    private var uiInstalled = false

    // ==================================================================
    // ModuleBridge —— 把框架的 hook 能力暴露给业务代码（避免业务类直接依赖 XposedModule）
    // ==================================================================

    override fun hookExecutable(executable: Executable): XposedInterface.HookBuilder =
        hook(executable)

    // ==================================================================
    // 现代 API 生命周期
    // ==================================================================

    /**
     * 模块被框架加载时调用（替代 legacy 的 `initZygote`）。
     *
     * 这里**必须**打日志：这是验证「模块到底有没有被 LSPosed 加载」的唯一可靠手段。
     * 如果日志里看不到这一行，说明框架根本没加载模块（作用域没勾、API 版本不对、
     * 或模块被系统/管理器禁用了）。
     */
    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        // 模块加载阶段 framework 尚未 attach 到进程，这里要同时做两件事：
        //  1) 把框架自带的 log() 通道接上 —— 它是「框架有没有加载本模块」最直接的证据，
        //     不依赖任何文件 IO，LSPosed 管理器里直接能看到；
        //  2) 初始化我们自己的文件日志（XLog 内部会写模块 filesDir 下的 xiaoai.log）。
        log(Log.INFO, TAG, "═══════════════════════════════════════════")
        log(Log.INFO, TAG, "✅ 模块已被 LSPosed 加载（现代 API）")
        log(Log.INFO, TAG, "框架: $frameworkName / $frameworkVersion")
        log(Log.INFO, TAG, "API: $apiVersion  (frameworkVersionCode=$frameworkVersionCode)")
        log(Log.INFO, TAG, "进程: ${param.processName}  systemServer=${param.isSystemServer}")
        log(Log.INFO, TAG, "模块包: ${moduleApplicationInfo.packageName}")

        // ★ 把框架 log() 通道桥接到 XLog —— 这样后续所有 XLog.i/w/e() 都会
        //   同时出现在 LSPosed verbose 日志里。这是诊断 AIVS Hook 是否生效的
        //   唯一可靠通道（XLog 文件/Logcat 用户不容易抓到）。
        XLog.frameworkLogger = { priority, tag, msg ->
            try {
                log(priority, tag, msg)
            } catch (_: Throwable) {
                // frameworkLogger 永远不能抛异常（会污染业务日志）
            }
        }
        XLog.i("[$TAG] XLog.frameworkLogger 已桥接（XLog 输出将进入 LSPosed verbose 日志）")

        // ModuleLoadedParam 没有 ClassLoader，没法做 createPackageContext，
        // 所以这里**不能**初始化文件日志——XLog.init(null) 会因为拿不到 Context
        // 而写出到宿主 dataDir。文件日志的真正落地在 onAppReady 里（此时有 Application）。
        // 这里只依赖框架自己的 log() 通道，它已经足够证明「模块被加载了」。
        log(Log.INFO, TAG, "═══════════════════════════════════════════")
    }

    /**
     * 目标包被加载时调用（替代 legacy 的 `handleLoadPackage`）。
     *
     * 此时 `Application` 还没创建，但 ClassLoader 已经可用——所有**类级别**的
     * Hook（方法/构造器）都应该在这里挂，因为这时候挂最稳，不会漏掉早期的调用。
     */
    /**
     * 目标包被加载时调用（替代 legacy 的 `handleLoadPackage`）。
     *
     * ## 这里只做「探测」，真正的 Hook 安装放在 [onPackageReady]
     *
     * 原因：`PackageLoadedParam` 只提供 `getDefaultClassLoader()`，而官方文档明确
     * 指出「libxposed 的 `onPackageReady` 才给出**正确的** classLoader」。
     * 宿主 App 常有多个 ClassLoader（split APK / 动态加载 / webview-provider），
     * 拿 default 那个去 `Class.forName` 混淆类，很容易找不到目标类 —— 这正是
     * 「Hook 装了但完全不生效」的常见根因。
     *
     * 所以：
     *  - onPackageLoaded：只记录命中、记住 ClassLoader 作为**兜底**；
     *  - onPackageReady ：用 param.getClassLoader() 真正安装所有 Hook。
     */
    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        val pkg = param.packageName
        if (pkg !in TARGET_PACKAGES) return

        XLog.i("[$TAG] 🎯 命中目标进程: $pkg（首次=${param.isFirstPackage}）")
        isHookedTarget = true

        // 兜底：万一 onPackageReady 没被调用（个别 ROM），至少反射层有个 loader 可用
        Reflector.ClassLoaderHolder.loader = param.defaultClassLoader
    }

    /**
     * 目标包完全就绪时调用（`Application` 已创建）。
     *
     * 这是**唯一**同时满足下列两个条件的时间点：
     *  1. `param.getClassLoader()` 是宿主真实、完整的 ClassLoader —— 能查到混淆类；
     *  2. Application 已存在 —— 能初始化配置、能用 Context 构造 View。
     *
     * 所以所有实质工作（安装 AIVS Hook + 注入设置页入口）都在这里做。
     */
    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        val pkg = param.packageName
        if (pkg !in TARGET_PACKAGES) return
        if (uiInstalled) return

        val loader = param.classLoader
        // 用 onPackageReady 提供的 loader 覆盖兜底值 —— 这是官方推荐的那个
        Reflector.ClassLoaderHolder.loader = loader
        XLog.i("[$TAG] PackageReady: $pkg，ClassLoader=${loader.javaClass.name}")

        // 1) 安装 AIVS Hook（类级 Hook，这一步之后就生效）
        runCatching { installAivsHooks(loader) }
            .onFailure { XLog.e("[$TAG] 安装 AIVS Hook 失败", it) }

        // 2) 初始化配置 + 注入设置页入口（需要 Application）
        val app = appFrom(loader) ?: run {
            XLog.w("[$TAG] 拿不到 Application，改挂 Application.attach 兜底")
            hookApplicationAttach(pkg, loader)
            return
        }
        onAppReady(app, pkg, loader)
    }

    /**
     * 取当前进程的 Application 实例。
     *
     * 为什么要绕这么一圈：`PackageReadyParam` 只暴露 ClassLoader 和 AppComponentFactory，
     * **没有** `getApplication()`。但此刻 Application 实例已经存在，它被 ActivityThread
     * 持有，所以反射 `ActivityThread` 是标准做法。
     */
    private fun appFrom(loader: ClassLoader): Application? {
        // 1) ActivityThread.mInitialApplication（最直接）
        runCatching {
            val atClass = Class.forName("android.app.ActivityThread", false, loader)
            val at = Reflect.callStaticMethod(atClass, "currentActivityThread") ?: return@runCatching
            Reflect.get<Application>(at, "mInitialApplication")?.let { return it }
        }
        // 2) ActivityThread.mAllApplications 的第一个
        runCatching {
            val atClass = Class.forName("android.app.ActivityThread", false, loader)
            val at = Reflect.callStaticMethod(atClass, "currentActivityThread") ?: return@runCatching
            val all = Reflect.get<ArrayList<Application>>(at, "mAllApplications")
            all?.firstOrNull()?.let { return it }
        }
        // 3) 交给 Application.attach 兜底
        return null
    }

    // ==================================================================
    // 1. Application 就绪 —— 初始化配置、注入 UI 入口
    // ==================================================================

    /**
     * 兜底：若 `onPackageReady` 拿不到 Application，退化为 Hook `Application.attach(Context)`。
     *
     * 正常路径不会走到这里，保留是因为个别 ROM（尤其定制系统）里
     * `ActivityThread.mInitialApplication` 可能还没被赋值。
     */
    private fun hookApplicationAttach(pkg: String, loader: ClassLoader) {
        val attach = runCatching {
            Application::class.java.getDeclaredMethod("attach", Context::class.java)
        }.getOrNull() ?: run {
            XLog.e("[$TAG] 找不到 Application.attach(Context)，无法初始化")
            return
        }

        HookCompat.hook(
            this, attach,
            after = { ctx ->
                val app = ctx.thisObject as? Application ?: return@hook
                onAppReady(app, pkg, loader)
            },
            tag = "Application#attach",
        )
        XLog.i("[$TAG] 已挂 Application.attach 兜底")
    }

    private fun onAppReady(app: Application, pkg: String, loader: ClassLoader) {
        if (uiInstalled) return
        synchronized(this) {
            if (uiInstalled) return
            uiInstalled = true

            // 1) 会话级日志路径就绪提示（XLog.init 会 mark）
            XLog.i("[$TAG] Hook 进程启动：pkg=$pkg pid=${android.os.Process.myPid()}")

            // 2) 配置（读 files/xiaoai_config.json，与设置窗口共享同一份）
            runCatching { ModelManager.init(app) }
                .onFailure { XLog.e("[$TAG] ModelManager 初始化失败", it) }

            // 3) 注入「设置」页面入口（UI 层 Hook，依赖 Application 的 Context）
            if (ModelManager.config().injectMineEntry) {
                runCatching { SettingsPageInjector.install(this, loader, app) }
                    .onFailure { XLog.e("[$TAG] 注入设置页入口失败", it) }
            } else {
                XLog.i("[$TAG] 配置里关闭了设置页入口，跳过注入")
            }

            XLog.i("[$TAG] ✓ XiaoAi Hook 已生效：HOST_PID=${android.os.Process.myPid()} PACKAGE=$pkg")
        }
    }

    // ==================================================================
    // 2. 安装消息 Hook
    // ==================================================================

    private fun installAivsHooks(loader: ClassLoader) {
        val model = AivsModel.get()

        if (!model.resolved) {
            XLog.w(
                "[$TAG] ⚠ AIVS 模型未完全解析（message=${model.messageClass} toast=${model.toastClass}）。\n" +
                    "  将启用「JSON 兜底模式」：Hook ApiNameMapping.findClass 与常见分发方法。\n" +
                    "  请把上面的类解析日志反馈给开发者。"
            )
        }

        val e = InterceptEngine()
        engine = e

        var installed = 0
        installed += hookDispatchMethods(loader, e, model)
        installed += hookApiNameMapping(loader, model)
        installed += hookJsonFallback(loader, e, model)

        XLog.i("[$TAG] Hook 安装完成，共安装 $installed 个 Hook 点")
        if (installed == 0) {
            XLog.e(
                "[$TAG] ✗ 没有安装任何 Hook 点，模块不会生效！\n" +
                    "  请检查：LSPosed 作用域是否勾选了 ${TARGET_PACKAGES.joinToString()}"
            )
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

        // 扫描所有 Message 的子类（Event / Instruction / 各种具体消息）
        messageClass?.let { mc ->
            DexClassScanner.scan(loader).forEach { name ->
                runCatching {
                    val c = Class.forName(name, false, loader)
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
                    if (!Modifier.isStatic(m.modifiers) &&
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

                    val ok = HookCompat.hook(
                        this, m,
                        before = { ctx -> dispatch(engine, owner, m, ctx, isBefore = true) },
                        after = { ctx -> dispatch(engine, owner, m, ctx, isBefore = false) },
                        tag = "${owner.simpleName}#${m.name}",
                    )
                    if (ok) {
                        count++
                        XLog.i(
                            "[$TAG] 已 Hook 分发方法: ${owner.simpleName}#${m.name}" +
                                "(${m.parameterTypes.joinToString { it.simpleName }})"
                        )
                    }
                }
            }
        }
        return count
    }

    /**
     * 分发方法的统一处理逻辑。
     *
     * 关键：**不直接修改方法返回值**，而是在 before 阶段改写 payload 字段。
     * 因为方法内部往往会把 Event 转交给别的组件（UI、TTS），改返回值没用，
     * 只有改对象状态才能让后续所有消费者看到新文本。
     */
    private fun dispatch(
        engine: InterceptEngine,
        owner: Class<*>,
        method: Method,
        ctx: HookCompat.HookContext,
        isBefore: Boolean,
    ) {
        try {
            val model = AivsModel.get()

            // 找到可能是消息对象的参数
            val candidates = ctx.args.filterNotNull().filter { arg ->
                val header = Reflector.get<Any>(arg, null, "header")
                val payload = Reflector.get<Any>(arg, null, "payload")
                (header != null && payload != null) || model.messageClass?.isInstance(arg) == true
            }
            if (candidates.isEmpty()) return

            for (msg in candidates) {
                // ---------- ASR 识别结果（所有版本都是 App→Cloud） ----------
                if (model.isRecognizeMessage(msg)) {
                    if (!isBefore) continue
                    val direct = engine.onRecognizeResult(msg)
                    if (direct != null) {
                        // 把应答文本暂存，等 Toast 到来时写入（手环会先显示"识别结果"，
                        // 真正展示给用户的是随后的 Toast）
                        engine.stashDirectText(direct)
                        XLog.i("[$TAG] 指令应答已暂存，等待 Toast 落地")
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
        } catch (t: Throwable) {
            XLog.e("[$TAG] DispatchHook 异常 (${owner.simpleName}#${method.name}, before=$isBefore)", t)
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
        XLog.d("[$TAG] 写回 payload.${if (isStream && hasMarkdown) "markdown_text" else "text"} => $ok")
        return ok
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
    private fun hookApiNameMapping(loader: ClassLoader, model: AivsModel): Int {
        var count = 0
        // 接口可能有多个实现，全部挂上
        val owners = mutableListOf<Class<*>>()
        model.apiNameMappingClass?.let { owners.add(it) }

        // 从 DEX 里补找实现类（接口的实现类名可能不含 ApiNameMapping）
        DexClassScanner.scan(loader).forEach { n ->
            runCatching {
                val c = Class.forName(n, false, loader)
                if (c.declaredMethods.any { m -> m.name == "findClass" && m.parameterCount == 2 }) {
                    owners.add(c)
                }
            }
        }

        owners.distinct().forEach { owner ->
            Reflect.findMethodsByName(owner, "findClass")
                .filter { it.parameterCount == 2 }
                .forEach { m ->
                    val ok = HookCompat.hook(
                        this, m,
                        after = { ctx ->
                            runCatching {
                                val ns = ctx.arg(0) as? String ?: return@runCatching
                                val name = ctx.arg(1) as? String ?: return@runCatching
                                val cls = ctx.result as? Class<*> ?: return@runCatching
                                ApiRegistry.register(ns, name, cls)
                            }
                        },
                        tag = "${owner.simpleName}#findClass",
                    )
                    if (ok) {
                        count++
                        XLog.i("[$TAG] 已 Hook ApiNameMapping: ${owner.simpleName}#findClass")
                    }
                }
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
        loader: ClassLoader,
        engine: InterceptEngine,
        model: AivsModel,
    ): Int {
        if (model.resolved) return 0
        XLog.w("[$TAG] 启用 JSON 兜底模式（兜底 Hook 收益有限，仅用于诊断）")

        var count = 0
        // Hook Message.setPayload：任何 payload 被设置时，尝试向上找 namespace/name
        model.messageClass?.let { mc ->
            Reflect.findMethodsByName(mc, "setPayload").forEach { m ->
                val ok = HookCompat.hook(
                    this, m,
                    before = { ctx ->
                        runCatching {
                            val self = ctx.thisObject ?: return@runCatching
                            val ns = model.namespaceOf(self) ?: return@runCatching
                            val name = model.nameOf(self) ?: return@runCatching
                            if (ns == "Template" && name == "Toast") {
                                engine.onAnswerMessage(self) { text ->
                                    Reflector.set(ctx.arg(0), "text", text)
                                }
                            }
                        }
                    },
                    tag = "${mc.simpleName}#setPayload",
                )
                if (ok) {
                    count++
                    XLog.i("[$TAG] 已 Hook Message#setPayload（兜底）")
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
                    XLog.i("[ApiNameMapping] $key -> ${cls.name}")
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
