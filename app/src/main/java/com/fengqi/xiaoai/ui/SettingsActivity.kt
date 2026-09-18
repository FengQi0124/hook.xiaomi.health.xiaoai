package com.fengqi.xiaoai.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.fengqi.xiaoai.core.ModelManager
import com.fengqi.xiaoai.core.XLog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置页 Activity。
 *
 * 使用 Miuix（Compose Multiplatform）渲染界面，风格对齐 HyperOS。
 * 本 Activity 同时作为：
 *  - 从桌面图标启动的入口（App 本身就是个设置工具）；
 *  - 从小米运动健康「我的」页面注入项启动的入口。
 *
 * 注意：本 Activity 运行在**模块自己的进程**里，与 Hook 进程通过配置文件同步状态。
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        ModelManager.ensureInit(applicationContext)
        XLog.i("SettingsActivity 启动")

        setContent {
            MiuixTheme {
                val config by ModelManager.configFlow.collectAsState()
                var showDiagnostics by remember { mutableStateOf(false) }

                if (showDiagnostics) {
                    DiagnosticsScreen(onBack = { showDiagnostics = false })
                } else {
                    SettingsScreen(
                        config = config,
                        onSave = { cfg -> ModelManager.saveConfig(cfg) },
                        onClose = { finish() },
                        onOpenDiagnostics = { showDiagnostics = true },
                    )
                }
            }
        }
    }
}
