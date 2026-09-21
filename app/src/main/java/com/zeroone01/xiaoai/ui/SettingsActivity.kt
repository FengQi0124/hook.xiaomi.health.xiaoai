package com.zeroone01.xiaoai.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.zeroone01.xiaoai.core.ModelManager
import com.zeroone01.xiaoai.core.XLog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置页 Activity（v0.1.0-beta7 起为**独立 Activity**，运行在模块自己的进程）。
 *
 * ## 为什么独立
 * 用户明确要求（beta6 嵌入宿主设置页的覆盖层被否决）：
 *  - 嵌入版的返回键被宿主顶栏的 popBackStack 抢走，"back 回到我的页而不是上一页"；
 *  - 没有转场动画；
 *  - 视觉上两层标题栏叠加。
 *
 * 独立后：
 *  - 系统转场动画免费获得；
 *  - back 键 = Activity.finish → 回到跳转来源（宿主"设置"页），栈行为 = 普通页面；
 *  - 页内"诊断页"等子导航用 [BackHandler] 接管：子页 back 回主设置页。
 *
 * ## 进程与配置
 * 本 Activity 在模块进程，与宿主 hook 进程通过 [com.zeroone01.xiaoai.core.ConfigSync]
 * 广播同步配置：保存 → 落盘模块 filesDir + 广播推给宿主 → 宿主热加载。
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        ModelManager.ensureInit(applicationContext)
        XLog.i("SettingsActivity 启动（模块进程，pid=${android.os.Process.myPid()}）")

        setContent {
            val isDark = resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            MiuixTheme {
                val config by ModelManager.configFlow.collectAsState()
                var showDiagnostics by remember { mutableStateOf(false) }

                // ★ 子页（诊断页）back → 回主设置页；主设置页 back → 系统默认 finish
                BackHandler(enabled = showDiagnostics) { showDiagnostics = false }

                if (showDiagnostics) {
                    DiagnosticsScreen(onBack = { showDiagnostics = false })
                } else {
                    SettingsScreen(
                        config = config,
                        onSave = { cfg -> ModelManager.saveConfig(cfg) },
                        onClose = { finish() },
                        onOpenDiagnostics = { showDiagnostics = true },
                        isDarkTheme = isDark,
                    )
                }
            }
        }
    }
}
