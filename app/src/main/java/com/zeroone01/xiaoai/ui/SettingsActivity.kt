package com.zeroone01.xiaoai.ui

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.zeroone01.xiaoai.core.XLog

/**
 * 设置页 Activity（v0.1.0-beta16 原生版本）。
 *
 * Compose/Miuix 因依赖 compileSdk 37 暂时无法使用，改回原生 Activity。
 * 后续找到兼容的 Compose 版本后重新引入。
 */
class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        XLog.i("SettingsActivity 启动（模块进程，pid=${android.os.Process.myPid()}）")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F5F5"))
            setPadding(48, 96, 48, 48)
        }

        val title = TextView(this).apply {
            text = "手环小爱 AI 增强"
            textSize = 22f
            setTextColor(Color.parseColor("#212121"))
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "v0.1.0-beta16（内核版，设置 UI 后续补回）"
            textSize = 14f
            setTextColor(Color.parseColor("#757575"))
            setPadding(0, 16, 0, 0)
            gravity = Gravity.CENTER
        }

        val hint = TextView(this).apply {
            text = "模块已通过 SSL_read native hook 接管 AI 回复。\n" +
                "日志请在 LSPosed 管理器的「日志」页查看。"
            textSize = 14f
            setTextColor(Color.parseColor("#424242"))
            setPadding(0, 48, 0, 0)
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(hint)
        setContentView(root)
    }
}
