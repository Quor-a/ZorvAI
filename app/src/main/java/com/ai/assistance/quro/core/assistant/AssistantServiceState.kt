package com.ai.assistance.quro.core.assistant

import android.content.Context
import com.ai.assistance.quro.util.QuroDiag

/**
 * 助手服务总开关 + 唤醒通道开关。
 *
 * 小布设置页第一项：
 * > **小布助手服务**「关闭后，任何情况下小布助手将不允许启动」
 * > **唤醒设置**：语音唤醒 / 连续对话 / 长按电源键唤醒 / 线控耳机唤醒 / 蓝牙耳机唤醒
 *
 * 小布把「服务」和「唤醒通道」分成两层：总开关关了就是彻底停机（连外部调起也不行），
 * 通道开关只关某一种触发方式。我们照这个模型做，因为它解决了一个真实问题——
 * 用户想彻底静默时，只关通知/磁贴是关不干净的（广播、分享、耳机键都还能把它叫起来）。
 *
 * ## 闸门（gate）是硬约束
 * 每个入口在**执行任何动作之前**必须调用 [gate]：返回非 null 就直接把这个结果回给调用方，
 * 不许再往下走。这样"服务已关闭"是一个**可证实**的状态，而不是靠各入口自觉。
 */
enum class AssistantChannel(val wire: String, val label: String, val desc: String, val defaultOn: Boolean) {
    /** 语音唤醒词（"小布小布" 等价物）。 */
    VOICE_WAKEWORD(
        "voice-wakeword", "语音唤醒",
        "说出唤醒词直接唤起（需端侧唤醒词引擎）",
        // 端侧唤醒词需要模型权重与常驻音频前处理，本仓尚未接入 —— 默认关，
        // 避免给用户一个"打开了但叫不醒"的假开关。
        false,
    ),

    /** 长按电源键 / 系统助手键（走 android.intent.action.ASSIST）。 */
    ASSIST_KEY(
        "assist-key", "长按电源键唤醒",
        "长按电源键 0.5 秒唤起，输入语音或文字指令",
        true,
    ),

    /** 线控耳机：长按播放键。 */
    HEADSET_WIRED(
        "headset-wired", "线控耳机唤醒",
        "长按线控耳机播放键唤起",
        true,
    ),

    /** 蓝牙耳机：触碰或长按。 */
    HEADSET_BT(
        "headset-bt", "蓝牙耳机唤醒",
        "触碰或长按蓝牙耳机唤起",
        true,
    ),

    /** 下拉快捷面板磁贴。 */
    TILE("tile", "快捷磁贴", "从快捷面板一键唤起", true),

    /** 桌面长按图标的快捷方式。 */
    SHORTCUT("shortcut", "桌面快捷方式", "长按图标直达能力广场 / 新对话", true),

    /** 系统分享与选中文本。 */
    SHARE("share", "分享与选中文本", "其他应用把文本分享或选中后发给 ZorvAI", true),

    /** 外部文本指令广播 / 深链。 */
    DIRECTIVE("directive", "外部指令", "第三方应用通过广播或深链把一句话交进来", true),

    /** 后台任务中转。 */
    RELAY("relay", "后台任务", "第三方提交长任务，宿主后台跑完回执", true),

    /** AI 工具调用（模型自己编排）。 */
    AI_TOOL("ai-tool", "AI 工具调用", "对话里由模型自己调用内置能力", true),
}

object AssistantServiceState {
    private const val TAG = "assistant-service"
    private const val PREFS = "quro_assistant_service"

    /** 总开关键：对应小布「小布助手服务」那一项。 */
    private const val KEY_ENABLED = "service_enabled"
    private const val KEY_CHANNEL_PREFIX = "channel_"

    private fun prefs(ctx: Context) =
        runCatching { ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }.getOrNull()

    /** 助手服务是否开启。默认开（开箱即用），用户关了必须处处生效。 */
    fun isServiceEnabled(ctx: Context): Boolean =
        prefs(ctx)?.getBoolean(KEY_ENABLED, true) ?: true

    fun setServiceEnabled(ctx: Context, on: Boolean) {
        prefs(ctx)?.edit()?.putBoolean(KEY_ENABLED, on)?.apply()
        QuroDiag.log(TAG, "service ${if (on) "enabled" else "DISABLED（所有入口一律拒绝启动）"}")
        if (!on) {
            // 关闭瞬间还要收尾：正在响的铃声停掉，避免"关了还在响"
            runCatching { FindMyPhone.stop("service-disabled") }
        }
    }

    fun isChannelEnabled(ctx: Context, channel: AssistantChannel): Boolean =
        prefs(ctx)?.getBoolean(KEY_CHANNEL_PREFIX + channel.wire, channel.defaultOn) ?: channel.defaultOn

    fun setChannelEnabled(ctx: Context, channel: AssistantChannel, on: Boolean) {
        prefs(ctx)?.edit()?.putBoolean(KEY_CHANNEL_PREFIX + channel.wire, on)?.apply()
        QuroDiag.log(TAG, "channel ${channel.wire} ${if (on) "on" else "off"}")
    }

    /**
     * 闸门：所有入口在动作前调用。
     *
     * @return null = 放行；非 null = 应当**原样回给调用方**的拒绝结果
     */
    fun gate(ctx: Context, channel: AssistantChannel): AssistantResult? {
        if (!isServiceEnabled(ctx)) {
            QuroDiag.log(TAG, "gate deny: service disabled channel=${channel.wire}")
            return AssistantResult.fail(
                AssistantCodes.SERVICE_DISABLED,
                "ZorvAI 助手服务已关闭，任何情况下都不会启动。可在「能力广场 → 助手设置」里重新开启。",
                data = mapOf("channel" to channel.wire),
            )
        }
        if (!isChannelEnabled(ctx, channel)) {
            QuroDiag.log(TAG, "gate deny: channel off channel=${channel.wire}")
            return AssistantResult.fail(
                AssistantCodes.CHANNEL_DISABLED,
                "「${channel.label}」已关闭（可在助手设置里打开）",
                data = mapOf("channel" to channel.wire),
            )
        }
        return null
    }

    /**
     * 只查总开关（不查通道）。
     *
     * 用在「用户在应用内自己点」的路径（能力广场执行、设置页本身）：
     * 那不属于任何"唤醒通道"，但总开关必须照样管住 —— 小布那条
     * 「关闭后任何情况下不允许启动」是字面意思。
     */
    fun gateServiceOnly(ctx: Context): AssistantResult? {
        if (isServiceEnabled(ctx)) return null
        QuroDiag.log(TAG, "gate deny (service only)")
        return AssistantResult.fail(
            AssistantCodes.SERVICE_DISABLED,
            "ZorvAI 助手服务已关闭，任何情况下都不会启动。可在「助手设置」里重新开启。",
        )
    }

    /** 当前所有通道的快照，设置页用。 */
    fun channelSnapshot(ctx: Context): List<Pair<AssistantChannel, Boolean>> =
        AssistantChannel.entries.map { it to isChannelEnabled(ctx, it) }
}
