package com.zeroone01.xiaoai.core

import android.content.Context
import android.content.pm.ApplicationInfo

/**
 * 模块进程内的「宿主环境常量」仓库。
 *
 * ## 解决什么问题
 * 模块从 LSPosed 进程被注入到 `com.mi.health` 进程后，会遇到两类「资源/路径」需求：
 *  1. **拿不到模块自己的 Resources / 主题 / 字符串**：直接 `createPackageContext("com.mi.health")` 是宿主反过来拿不到模块；
 *     反过来 `createPackageContext("com.zeroone01.xiaoai")` 在 Android 11+ 上因为 `<queries>` 没声明而失败。
 *  2. **拿不到宿主的 APK 路径**：路径只暴露在宿主的 `ApplicationInfo` 上，而
 *     `param.getApplicationInfo()` 在 `PackageLoadedParam` 里返回的就是宿主的；模块自己如果想做
 *     兜底（不依赖宿主的 Application），就要靠 [moduleAppInfo]（来自 [io.github.libxposed.api.XposedModule.moduleApplicationInfo]）。
 *
 * ## 设计原则
 *  - **零反射 / 零业务依赖**：只持有 framework 直接给出的 ApplicationInfo / 路径常量；
 *  - **可空 + 兜底**：拿不到时返回 null，调用方必须降级；
 *  - **多进程安全**：所有字段都是 @Volatile，写在 XposedModule 的生命周期里，读在 Hook 安装 / 设置页启动时。
 *
 * ## 字段说明
 *  - [moduleAppInfo]：模块自己的 ApplicationInfo，**来自 XposedModule**，**不受 Android 11+ 包可见性影响**；
 *  - [moduleApkPath]：模块 APK 的绝对路径，恒等于 `moduleAppInfo.sourceDir`；
 *  - [hostAppInfo] / [hostApkPath]：宿主 `com.mi.health` 的 ApplicationInfo / APK 路径，
 *    由 [XiaoAiHookEntry] 在 `onPackageLoaded` 时写入。
 */
object HostEnv {

    /** 模块自己的 ApplicationInfo（来自 XposedModule.moduleApplicationInfo） */
    @Volatile
    var moduleAppInfo: ApplicationInfo? = null

    /** 模块 APK 路径，等于 `moduleAppInfo.sourceDir`（冗余缓存，省一次判空） */
    @Volatile
    var moduleApkPath: String? = null

    /** 宿主 APK 路径（=宿主的 sourceDir）；可能为 null（罕见） */
    @Volatile
    var hostApkPath: String? = null

    /** 宿主的 split APK 路径列表 */
    @Volatile
    var hostSplitApkPaths: List<String> = emptyList()

    /**
     * 一次性把模块 ApplicationInfo 拆好（缓存常用字段）。
     *
     * 必须在 [io.github.libxposed.api.XposedModule.onModuleLoaded] 阶段调用，因为：
     *  - XposedModule 本身就是 framework 注入的对象；
     *  - `moduleApplicationInfo` 在那个时间点一定可用；
     *  - 而宿主 Application 还没起来，宿主的 ApplicationInfo 还要等 onPackageLoaded。
     */
    fun cacheModuleAppInfo(info: ApplicationInfo) {
        moduleAppInfo = info
        moduleApkPath = info.sourceDir
        XLog.i("[HostEnv] 模块 APK 已就绪：${info.packageName} → ${info.sourceDir}")
    }

    /**
     * 把宿主的 ApplicationInfo 同步给 [DexClassScanner] 兜底。
     * 调用时机：[XiaoAiHookEntry.onPackageLoaded] 拿到 `param.applicationInfo` 时。
     */
    fun cacheHostAppInfo(info: ApplicationInfo) {
        hostApkPath = info.sourceDir
        hostSplitApkPaths = info.splitSourceDirs?.toList() ?: emptyList()
        XLog.i(
            "[HostEnv] 宿主 APK 已就绪：${info.packageName}\n" +
                "  sourceDir=${info.sourceDir}\n" +
                "  splitSourceDirs=${hostSplitApkPaths.joinToString(",").ifEmpty { "(无)" }}"
        )
    }

    /**
     * 拿到模块 Context。
     *
     * 优先用 [XposedModule.moduleApplicationInfo][io.github.libxposed.api.XposedModule.moduleApplicationInfo] +
     * `PackageManager.getResourcesForApplication(ApplicationInfo)` 这条**公开 API** 通道，
     * 完全绕过 Android 11+ 的 `<queries>` 限制。
     *
     * @param fallback 当模块资源拿不到时退回的 Context（一般是宿主 Activity）
     */
    fun buildModuleContext(
        hostContext: Context,
        moduleAppInfo: ApplicationInfo,
    ): Context {
        val pm = hostContext.packageManager
        val resources = runCatching {
            @Suppress("DEPRECATION")
            pm.getResourcesForApplication(moduleAppInfo)
        }.getOrElse {
            XLog.w("[HostEnv] getResourcesForApplication 失败：${it.message}")
            null
        }
        if (resources == null) return hostContext

        // 用 ContextWrapper 拦截 Resources/Assets/Theme —— 这样宿主的
        // density / 系统主题不会反向污染模块的 UI（如 Spinner、Popup 等）
        return object : android.content.ContextWrapper(hostContext) {
            override fun getResources() = resources
            override fun getAssets() = resources.assets
            override fun getTheme() = hostContext.theme // 主题跟随宿主（Miuix 强制深浅模式由 ThemeController 控制）
            override fun getPackageName() = moduleAppInfo.packageName
            override fun getApplicationInfo() = moduleAppInfo
            override fun getApplicationContext(): Context = this
        }
    }
}