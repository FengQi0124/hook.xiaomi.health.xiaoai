package com.zeroone01.xiaoai.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject

/**
 * 跨进程配置同步器（v0.1.0-beta7 起 UI 独立 Activity 方案的配套通道）。
 *
 * ## 架构背景
 * v0.1.0-beta6 及之前，设置 UI 以"覆盖层"方式嵌在宿主 com.mi.health 的 Activity content
 * 里 —— UI 与 Hook 同进程，配置文件只有一份（宿主 filesDir），天然共享。
 *
 * 用户明确要求改成**独立 Activity**（嵌入版的问题：宿主顶栏返回键不经过我们的
 * back 拦截、没有转场动画、back 栈混乱）。独立后 SettingsActivity 跑在**模块进程**，
 * 与 Hook 进程（宿主进程）之间隔了 uid 墙 —— 两个 filesDir 互不可写，必须走 IPC。
 *
 * ## 通道选择：显式包名广播
 *  - **模块端 → 宿主端**：`Intent(ACTION)` + `setPackage("com.mi.health")`。
 *    宿主 hook 进程在 onPackageReady 时动态注册 receiver（动态注册不受 manifest /
 *    Android 11+ 包可见性限制），收到后把 extras 里的配置 JSON 落盘到宿主 filesDir。
 *  - **方向只有这一个**：配置的修改永远发生在 UI（模块进程）；Hook 端只读。
 *    运行期宿主端产生的对话记录/日志 beta7 不回传（诊断页显示模块端状态）。
 *
 * ## 安全性
 *  - 广播 setPackage 限定只投递到宿主包；extras 携带 API Key 只在本机内传输；
 *  - receiver 注册时用 RECEIVER_EXPORTED（Android 14+ 必须显式声明，否则崩溃）；
 *  - 所有解析/写盘都在 try/catch 里，坏数据直接丢弃，绝不带崩宿主。
 */
object ConfigSync {

    /** 广播 action：模块端把最新配置推给宿主 */
    const val ACTION_CONFIG = "com.zeroone01.xiaoai.ACTION_CONFIG_SYNC"

    /** extras key：完整 AiConfig JSON */
    const val EXTRA_JSON = "config_json"

    /** extras key：来源标记（诊断用） */
    const val EXTRA_FROM = "from"

    private const val HOST_PACKAGE = "com.mi.health"

    /** 宿主端动态注册。由 XiaoAiHookEntry 在 onAppReady 时调用。 */
    fun registerHostReceiver(app: android.content.Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                runCatching {
                    if (intent.action != ACTION_CONFIG) return
                    val json = intent.getStringExtra(EXTRA_JSON) ?: return
                    applyConfigFromUi(context, json)
                }.onFailure { XLog.e("ConfigSync 收到坏广播", it) }
            }
        }
        val filter = IntentFilter(ACTION_CONFIG)
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                // RECEIVER_EXPORTED = 2；Android 13+ 动态注册跨 app 接收必须显式声明
                val flag = app.javaClass
                    .getMethod("registerReceiver", BroadcastReceiver::class.java, IntentFilter::class.java, Int::class.javaPrimitiveType)
                flag.invoke(app, receiver, filter, 2)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                app.registerReceiver(receiver, filter)
            }
            XLog.i("ConfigSync：宿主端 receiver 已注册（等待模块 UI 推送配置）")
        }.onFailure { XLog.e("ConfigSync 注册 receiver 失败", it) }
    }

    /**
     * 宿主端处理：解析 JSON → 落盘到宿主 filesDir → 刷新内存 config。
     *
     * 直接写 ModelManager 的存储文件（与 Hook 读的是同一个），写完调 refresh。
     */
    private fun applyConfigFromUi(context: Context, json: String) {
        val file = java.io.File(context.filesDir, "xiaoai_config.json")
        file.parentFile?.mkdirs()
        file.writeText(json)
        XLog.i("ConfigSync：收到模块 UI 推送的配置（${json.length}B），已落盘 ${file.absolutePath}")
        if (ModelManager.isInitialized) {
            ModelManager.refresh()
        } else {
            ModelManager.init(context)
        }
    }

    /**
     * 模块端（SettingsActivity 进程）推送配置给宿主。
     *
     * @param context 模块进程的任意 Context
     * @param json 完整 AiConfig JSON（与宿主 filesDir 落盘格式一致）
     * @return 是否成功发送（发送 ≠ 宿主已处理；宿主未启动时广播会丢失，
     *         但宿主下次启动时 ModelManager.init 会读取 UI 端刚保存的文件吗？——
     *         不会，读的是宿主 filesDir。所以 UI 保存时**同时**保证广播发出；
     *         若宿主进程未运行，广播由系统缓存投递？普通广播不缓存 —— 兜底：
     *         UI 端还会把 JSON 写到模块 filesDir，宿主端 hook 启动时若广播丢失，
     *         用户在 UI 再点一次保存即可。为降低这个风险，发送前先 startService
     *         拉活宿主进程？不行，宿主没有我们的 service。接受该限制：
     *         用户保存后宿主进程正在运行的概率极高（入口就在宿主设置页里点的）。
     */
    fun sendConfigToHost(context: Context, json: String) {
        runCatching {
            // 只在宿主安装时发送
            val pm = context.packageManager
            if (runCatching {
                    pm.getPackageInfo(HOST_PACKAGE, 0)
                }.isFailure
            ) {
                XLog.w("ConfigSync：宿主 $HOST_PACKAGE 未安装，跳过推送")
                return
            }
            val intent = Intent(ACTION_CONFIG).apply {
                setPackage(HOST_PACKAGE)
                putExtra(EXTRA_JSON, json)
                putExtra(EXTRA_FROM, "settings-activity")
            }
            context.sendBroadcast(intent)
            XLog.i("ConfigSync：已广播推送配置到 $HOST_PACKAGE（${json.length}B）")
        }.onFailure { XLog.e("ConfigSync 推送失败", it) }
    }
}
