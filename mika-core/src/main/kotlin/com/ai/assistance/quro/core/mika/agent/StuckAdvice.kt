package com.ai.assistance.quro.core.mika.agent

import com.ai.assistance.quro.core.mika.guard.GuardVerdict
import com.ai.assistance.quro.core.mika.guard.Severity
import com.ai.assistance.quro.core.mika.prompt.Feedback
import com.ai.assistance.quro.core.mika.tool.Verb
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 卡住提醒的记账:守卫报过几次「卡住」类提醒;每段字在每个地方(App 或网站)成功输过几次。 */
internal data class StuckState(val warnings: Int = 0, val typed: Map<String, Int> = emptyMap())

/**
 * 一个动作(单步,或批里的一个子步)交给卡住提醒的事实:守卫的结论(动作 + 目标重复、动过之后画面没变、页面没变时反复读),
 * 这次成功输入的明文([StuckAdvice.typedText],不是输入或是密码框为 null),当时在哪。
 */
internal data class StuckInput(val verdicts: List<GuardVerdict>, val typed: String?, val place: String?, val progressed: Boolean = false)

/**
 * 卡住时给模型的建议(放进工具结果的 advice 字段):卡住类提醒第二次起建议找遍了就如实放弃;
 * 同一段字在同一个地方又输了一遍(换着词反复搜同几样东西,守卫的窗口看不出来)时说已经输过几次。
 * 单步和批里的子步走同一套。纯函数。
 */
internal object StuckAdvice {
    private val STUCK_CODES = setOf("no_progress", "no_new_state", "read_loop")

    /** 返回之后的记账和要给的建议(没有为 null)。 */
    fun next(s: StuckState, input: StuckInput): Pair<StuckState, String?> {
        val warnings = (if (input.progressed) 0 else s.warnings) + input.verdicts.count { it.severity == Severity.Warn && it.code in STUCK_CODES }
        var typed = if (input.progressed) emptyMap() else s.typed
        // 按「当前地方 + 文字」计数:在别的 App 里搜同一个词不算重复
        val again = input.typed?.let { t ->
            val key = "${input.place}\u0000$t"
            val n = (typed[key] ?: 0) + 1
            typed = typed + (key to n)
            if (n >= 2 && input.verdicts.any { it.code in STUCK_CODES })
                Feedback.format("repeated_input_advice", "text" to t, "times" to n, "answer_or_failure" to ANSWER_OR_FAILURE) else null
        }
        val advice = if (warnings >= 2 && input.verdicts.any { it.code in STUCK_CODES })
            Feedback.format("give_up_advice", "answer_or_failure" to ANSWER_OR_FAILURE) else again
        return StuckState(warnings, typed) to advice
    }

    /**
     * 这次成功输入的文字(去掉首尾空白,空的不算);不是输入、没成功,或端口没明确说「不是密码框」时为 null:
     * 提示里会带上原文,而工具结果要进运行记录。
     */
    fun typedText(verb: Verb?, args: JsonObject, content: JsonObject?): String? {
        if (verb != Verb.TypeText || content?.get("ok") != JsonPrimitive(true) || content["secret"] != JsonPrimitive(false)) return null
        return (args["text"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }
}
