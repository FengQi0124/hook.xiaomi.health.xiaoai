package com.zeroone01.xiaoai.hook

import com.zeroone01.xiaoai.config.ConfigKeys
import com.zeroone01.xiaoai.config.ConfigStore
import com.zeroone01.xiaoai.log.LogCollector

/**
 * 手环小爱AI —— 回答模式内存状态机（Hook 进程单例）
 *
 * 实现「语音指令切换小爱 / LLM 回答」的核心状态：
 * - 默认模式（LLM 或小爱）存于 Remote Preferences（[ConfigStore] 可读写）；
 * - 语音切换后（[active] / [currentMode]）**长期生效**，没有到期时间：
 *   切换一直保持，直到再次用语音切回、或宿主进程（com.mi.health）重启后
 *   回到设置页选定的默认模式。
 *
 * 关键约束：Hook 进程对 Remote Prefs 只读，运行时切换状态只能放本内存对象，
 * 而不可写入配置（setter 会被框架静默忽略）。
 */
object ModeState {

    private const val TAG = "ModeState"

    /** 配置读取器：由 [init] 注入 */
    private var config: ConfigStore? = null

    /** 是否有进行中的语音切换 */
    @Volatile
    private var active = false

    /** 进行中切换的目标模式（仅 [active] 时有意义） */
    @Volatile
    private var currentMode = AnswerMode.LLM

    /** 注入配置读取器（幂等；MiHealthHook 安装时调用） */
    fun init(config: ConfigStore) {
        if (this.config == null) this.config = config
    }

    /**
     * 返回当前应生效的回答模式。
     * 有语音切换 → 返回切换后的模式（**长期生效，不做过期回退**）；否则回退配置的默认模式。
     */
    fun resolveMode(): AnswerMode {
        val cfg = config ?: return AnswerMode.LLM
        return if (active) currentMode else cfg.toDefaultAnswerMode()
    }

    /**
     * 执行一次模式切换（由语音指令命中触发）。
     * 若目标模式 == 配置默认模式，则视为「切回默认」——取消切换；
     * 否则开启切换并**一直保持**（原「小爱/LLM 模式持续时长」机制已整体删除）。
     */
    fun switchTo(mode: AnswerMode) {
        val cfg = config ?: return
        val defaultMode = cfg.toDefaultAnswerMode()

        if (mode == defaultMode) {
            active = false
            LogCollector.i(TAG, "指令命中「默认模式」，已取消切换（${mode.label()}）")
            return
        }

        active = true
        currentMode = mode
        LogCollector.i(TAG, "已切换到${mode.label()}模式（长期生效，不自动恢复）")
    }

    /** 生成固定确认文案（切换长期生效），供指令 Toast 替换显示 */
    fun buildConfirmation(mode: AnswerMode): String = when (mode) {
        AnswerMode.LLM -> "已切换到 AI 模式（长期有效）"
        AnswerMode.XIAOAI -> "已切换到小爱模式（长期有效）"
    }

    /** 生成当前实际回答模式的查询文案（查询指令用） */
    fun buildModeStatus(): String = when (resolveMode()) {
        AnswerMode.LLM -> "当前是 AI(LLM) 在回答"
        AnswerMode.XIAOAI -> "当前是小爱在回答"
    }

    /** 把配置字符串解析为默认模式枚举；非法值回退 LLM */
    private fun ConfigStore.toDefaultAnswerMode(): AnswerMode =
        when (getDefaultMode().trim().lowercase()) {
            ConfigKeys.VALUE_MODE_XIAOAI -> AnswerMode.XIAOAI
            else -> AnswerMode.LLM
        }

    private fun AnswerMode.label(): String =
        when (this) {
            AnswerMode.LLM -> "LLM"
            AnswerMode.XIAOAI -> "小爱"
        }
}

/** 回答模式枚举 */
enum class AnswerMode {
    LLM, XIAOAI
}
