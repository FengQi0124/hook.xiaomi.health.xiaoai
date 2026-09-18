package com.fengqi.xiaoai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.fengqi.xiaoai.core.ModelManager
import com.fengqi.xiaoai.core.XLog
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import androidx.compose.foundation.layout.Row
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 诊断页。
 *
 * 用于验证 Hook 是否生效——这是 LSPosed 模块开发中最费时间的环节。这一页把
 * 所有关键状态集中展示，不需要连电脑看 logcat 就能定位问题。
 *
 * 判读方法：
 *  - 「模块初始化」为「已初始化」→ 配置读取正常；
 *  - 「Xposed 注入状态」显示已注入 → Hook 已进入宿主进程；
 *  - 「AIVS 类解析」全部有值 → 反射找到了 SDK 的类；
 *  - 在手机上打开小米运动健康说一句话后回到这里，「最近对话」出现记录 → 全链路通。
 */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    var logs by remember { mutableStateOf(XLog.snapshot().reversed()) }
    var dialogs by remember { mutableStateOf(ModelManager.dialogs()) }
    var tick by remember { mutableStateOf(0) }

    // 每 1.5s 刷新一次，方便边操作边观察
    LaunchedEffect(tick) {
        delay(1500)
        logs = XLog.snapshot().reversed()
        dialogs = ModelManager.dialogs()
        tick++
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp)
            .padding(top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "运行诊断",
            style = MiuixTheme.textStyles.title1,
            modifier = Modifier.padding(start = 4.dp, bottom = 12.dp),
        )

        SmallTitle(text = "状态")
        Card {
            KeyValue("模块初始化", if (ModelManager.isInitialized) "✓ 已初始化" else "✗ 未初始化")
            KeyValue("当前模型", ModelManager.activeModel.value.displayName)
            KeyValue("配置同步", "已开启（1.5s 轮询）")
            KeyValue("最近对话数", "${dialogs.size}")
        }

        Spacer(Modifier.height(8.dp))
        SmallTitle(text = "最近对话")
        Card {
            if (dialogs.isEmpty()) {
                Text(
                    text = "暂无记录。请打开小米运动健康，对手环说一句话，然后回到这里刷新。",
                    style = MiuixTheme.textStyles.footnote1,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                dialogs.take(10).forEach { d ->
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row {
                            Text(
                                text = if (d.replaced) "✓ 已替换" else "· 未替换",
                                style = MiuixTheme.textStyles.footnote2,
                                color = if (d.replaced) MiuixTheme.colorScheme.primary
                                else MiuixTheme.colorScheme.error,
                            )
                            Spacer(Modifier.fillMaxWidth(0.02f))
                            Text(
                                text = "${d.model} · ${d.note}",
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceContainerHigh,
                            )
                        }
                        Text("问: ${d.question.take(60)}", style = MiuixTheme.textStyles.footnote1)
                        Text("答: ${d.answer.take(80)}", style = MiuixTheme.textStyles.footnote1)
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        SmallTitle(text = "日志（最近 ${logs.size} 条）")
        Card {
            Column(modifier = Modifier.padding(12.dp)) {
                logs.take(60).forEach { entry ->
                    Text(
                        text = "${entry.time} [${entry.level}] ${entry.message}",
                        style = MiuixTheme.textStyles.footnote2,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(text = "清除日志", onClick = { XLog.clear() }, modifier = Modifier.fillMaxWidth(0.48f))
            TextButton(text = "返回", onClick = onBack, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = key, style = MiuixTheme.textStyles.body1)
        Text(
            text = value,
            style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.onSurfaceContainerHigh,
        )
    }
}
