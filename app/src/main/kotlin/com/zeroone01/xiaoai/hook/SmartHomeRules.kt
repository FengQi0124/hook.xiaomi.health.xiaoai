package com.zeroone01.xiaoai.hook

/**
 * 智能家居 / 设备控制指令识别（0.5.0-beta 新增，移植自旧版 VoiceCommandHandler）。
 *
 * 命中后 [WebSocketMessageProcessor] **不暂存**该 dialog 的识别文本：
 * 下行 Toast/General 因查不到识别文本而原样放行 —— 小爱原生执行控制并显示原始确认，
 * 不会进入 LLM（既省 Token，也避免大模型「脑补」执行结果）。
 * 上行链路（识别文本 → 云端执行）模块从不拦截，控制本身永远可用。
 *
 * 判定策略（强信号优先，避免普通提问误放行）：
 * 1. 场景/模式词（「回家」「观影」…）：无设备名词也放行；
 * 2. 数值调节（「调到26度」「音量50」）：正则命中即放行；
 * 3. 动作动词 + 设备名词组合：必须两者同时出现才放行，
 *    故「灯是什么做的」（无动作）、「帮我查下天气」（无设备）不会误命中。
 */
internal object SmartHomeRules {

    private const val TAG = "SmartHomeRules"

    /** 智能家居/设备名词（强信号） */
    private val DEVICES = listOf(
        "灯", "空调", "风扇", "窗帘", "扫地机", "扫地机器人", "净化器", "加湿器", "除湿机",
        "热水器", "插座", "开关面板", "电视", "机顶盒", "门锁", "摄像头", "地暖", "暖气片",
        "新风", "浴霸", "晾衣架", "香薰机", "电饭煲", "冰箱", "洗衣机", "微波炉", "烤箱",
        "投影", "音箱", "充电器", "打印机", "路由器", "浴缸", "饮水机", "咖啡机",
    )

    /** 智能家居动作动词 */
    private val ACTIONS = listOf(
        "打开", "关闭", "开启", "关掉", "关了", "开了", "开一下", "关一下",
        "调高", "调低", "调亮", "调暗", "调到", "调至", "调成", "设置", "设定",
        "定时", "倒计时", "启动", "停止", "暂停", "恢复", "切换到", "来个", "来点",
    )

    /** 场景/模式类指令（无设备名词也要放行） */
    private val SCENES = listOf(
        "回家", "离家", "外出", "观影", "看电影", "睡眠模式", "夜灯", "起床模式", "起床音乐",
    )

    /** 数值调节类（「调到26度」「音量50」），无需设备名词 */
    private val VALUE_REGEX =
        Regex("""(调[高低到至设成]|设置?成?|音量|亮度)\s*[0-9零一二两三四五六七八九十]+\s*(%|度|档|格)?""")

    /**
     * 是否为智能家居控制指令（命中即直通小爱原生链路）。
     * 任何异常一律返回 false（宁可调 LLM，也不误吞正常提问）。
     */
    fun isSmartHomeCommand(text: String): Boolean = try {
        val normalized = normalize(text)
        if (normalized.isEmpty()) {
            false
        } else if (SCENES.any { normalized.contains(normalize(it)) }) {
            true
        } else if (VALUE_REGEX.containsMatchIn(normalized)) {
            true
        } else {
            val hasAction = ACTIONS.any { normalized.contains(normalize(it)) }
            hasAction && (
                DEVICES.any { normalized.contains(normalize(it)) } ||
                    normalized.contains("温度") ||
                    normalized.contains("湿度")
                )
        }
    } catch (_: Throwable) {
        false
    }

    /** 归一化：去空白并转小写（词表全部用中文，小写仅影响夹杂的英文） */
    private fun normalize(s: String): String =
        s.replace(Regex("""\s+"""), "").lowercase()

    /** 记录命中日志（由调用方在 debug 需要时使用，保持词表判定纯净） */
    fun logPass(text: String) {
        com.zeroone01.xiaoai.log.LogCollector.i(TAG, "智能家居指令直通（不调 LLM）: ${text.take(40)}")
    }
}
