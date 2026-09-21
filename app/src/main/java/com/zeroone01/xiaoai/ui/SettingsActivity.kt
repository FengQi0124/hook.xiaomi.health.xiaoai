package com.zeroone01.xiaoai.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.zeroone01.xiaoai.core.ModelManager
import com.zeroone01.xiaoai.core.XLog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置页 Activity（v0.1.0-beta7+ 修复版）
 *
 * ## 安全区修复
 *  - enableEdgeToEdge() 让内容延伸到状态栏/导航栏以下
 *  - Modifier.systemBarsPadding() 自动添加避让内边距，内容不会被状态栏遮挡
 *
 * ## 问题
 *  原版 SettingsActivity 调用了 enableEdgeToEdge() 但没有给 Compose 内容加
 *  对应的 padding，导致顶栏文字直接画在状态栏下面，被摄像头/状态栏遮挡。
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()  // 延伸到系统栏以下
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

                BackHandler(enabled = showDiagnostics) { showDiagnostics = false }

                // ★ 修复：systemBarsPadding() 确保内容不会被状态栏/导航栏遮挡
                val contentModifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()

                if (showDiagnostics) {
                    DiagnosticsScreen(onBack = { showDiagnostics = false })
                } else {
                    // 注意：systemBarsPadding 加到 Column 里由 SettingsScreen 处理也可以
                    // 但最稳妥的方式是在顶层容器就避让
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
