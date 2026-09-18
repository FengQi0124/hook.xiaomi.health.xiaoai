package com.fengqi.xiaoai.ui

import android.app.Activity
import android.os.Bundle
import com.fengqi.xiaoai.core.ModelManager
import com.fengqi.xiaoai.core.XLog

/**
 * 透明代理 Activity。
 *
 * 存在意义：部分 MIUI/HyperOS 版本会拦截「宿主 App 启动模块 APK 的 Activity」，
 * 报 `Permission Denial` 或直接吞掉 Intent。这个 Activity 声明在模块自己的 manifest 里
 * 且 `exported=false`，只能被模块内部启动，作为「跳板」：
 *
 *   宿主 Context → 启动本 Activity（同模块包，风险更低）
 *                 → 本 Activity 立即启动首屏并 finish
 *
 * 目前主路径已经能从宿主 Context 直接拉起 [SettingsActivity]，这个类作为兜底保留。
 */
class LauncherProxyActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ModelManager.ensureInit(applicationContext)
        XLog.i("LauncherProxyActivity 启动，转发到 SettingsActivity")
        runCatching {
            startActivity(
                android.content.Intent(this, SettingsActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        finish()
    }
}
