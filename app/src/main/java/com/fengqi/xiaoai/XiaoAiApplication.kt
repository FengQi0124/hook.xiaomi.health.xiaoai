package com.fengqi.xiaoai

import android.app.Application
import com.fengqi.xiaoai.core.ModelManager
import com.fengqi.xiaoai.core.XLog

/**
 * 模块自己的 Application。
 *
 * 注意：这个类**只在本模块进程里生效**。Hook 注入到小米运动健康进程后，
 * 使用的是宿主自己的 Application，模块的初始化走
 * [com.fengqi.xiaoai.hook.XiaoAiHookEntry] 里的 `Application.attach` Hook。
 *
 * 也就是说：两个进程各自初始化一份 [ModelManager]，通过配置文件同步状态。
 */
class XiaoAiApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        ModelManager.ensureInit(this)
        // UI 进程也启动轮询，这样手环切了模型，手机上打开设置页能立刻看到
        ModelManager.startPolling(2000L)
        XLog.i("模块进程启动: ${packageName}")
    }
}
