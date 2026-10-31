package com.ai.assistance.quro.core.mika.agent

import com.ai.assistance.quro.core.mika.model.Author
import com.ai.assistance.quro.core.mika.model.Message
import com.ai.assistance.quro.core.mika.model.Part
import com.ai.assistance.quro.core.mika.model.Role

/**
 * 一轮对话:用户说了什么、结果如何。[outcome] 是成功时的答案或失败原因;
 * [status] 为 succeeded / unverified(办完了但没能核实,[note] 是原因,[unconfirmed] 是标成未确认的几处)/ failed / cancelled。
 * [runId] 是这轮的运行记录;没开起记录或更早的轮次为 null。
 */
/**
 * [memoryNotes]/[usedMemory]/[skillNotes]:结果卡片下的记忆与技能提示,任务结束后补上;不进给模型的上文。
 * [result]:交出回答后判的结局([RunOutcome.wire]),后台补上;没判过为 null。
 */
data class Turn(
    val userText: String, val outcome: String, val status: String, val note: String? = null, val runId: String? = null,
    val memoryNotes: List<com.ai.assistance.quro.core.mika.memory.MemoryNote> = emptyList(),
    val usedMemory: List<String> = emptyList(),
    val skillNotes: List<com.ai.assistance.quro.core.mika.skills.SkillNote> = emptyList(),
    val unconfirmed: List<String> = emptyList(),
    val result: String? = null,
    /** 任务进行中用户插的、已交给这个任务的话(界面在那一轮下面显示)。 */
    val steers: List<String> = emptyList(),
    /** 和 [steers] 一一对应:每句插话打断的那段回复里用户已经看到的字,没打断的是空串。只给界面显示,不进给模型的上文。 */
    val interruptedReplies: List<String> = emptyList(),
    /** 和 [steers] 一一对应:每句插话交给模型之前、已经说完显示出来的那段回复,没有的是空串。只给界面显示。 */
    val finishedReplies: List<String> = emptyList(),
    /** 这一轮真的用过的能力组(手机、网页、沙箱……);下一轮开局预先展开,不进给模型的上文。 */
    val groups: List<String> = emptyList(),
)

object Conversation {
    const val HEADER = "Earlier turns of this conversation (for reference; what you read this time shows the current state):"

    /** 停止的那一轮还没有交代(或没什么可交代)时记下的话。 */
    const val CANCELLED_OUTCOME = "已取消"

    /** 停止的那一轮补上的交代;没有时为 null。 */
    fun stopSummary(t: Turn): String? =
        t.outcome.trim().takeIf { t.status == "cancelled" && it.isNotEmpty() && it != CANCELLED_OUTCOME }

    /** 把之前的轮次做成一条用户角色的参考消息,最近的在最后;太长就丢掉较早的轮次。没有轮次返回 null。 */
    fun context(turns: List<Turn>, maxTurns: Int = 5, maxChars: Int = 4000): Message? {
        var recent = turns.takeLast(maxTurns.coerceAtLeast(0))
        if (recent.isEmpty()) return null
        fun render(ts: List<Turn>, clip: Int) = ts.mapIndexed { i, t -> line(i + 1, t, clip) }.joinToString("\n")
        while (recent.size > 1 && HEADER.length + 1 + render(recent, Int.MAX_VALUE).length > maxChars) recent = recent.drop(1)
        var body = render(recent, Int.MAX_VALUE)
        if (HEADER.length + 1 + body.length > maxChars) {
            // 只剩一轮还超长:各字段截断
            body = render(recent, ((maxChars - HEADER.length) / 3).coerceAtLeast(20))
        }
        return Message(Role.User, listOf(Part.Text("$HEADER\n$body")), Author.ZorvAI)
    }

    private fun line(n: Int, t: Turn, clip: Int): String {
        // 回答如实说没办成或只办了一部分:按交出后判的结局说,下一轮别当它办成了
        val answered = t.status == "succeeded" || t.status == "unverified"
        val result = when {
            answered && t.result == RunOutcome.Partial.wire -> "only partly done: ${t.outcome.clip(clip)}"
            answered && t.result == RunOutcome.NotPossible.wire -> "not done: ${t.outcome.clip(clip)}"
            t.status == "succeeded" -> "result: ${t.outcome.clip(clip)}"
            t.status == "unverified" -> "result (could not be verified): ${t.outcome.clip(clip)}"
            // 停止后补上的交代(做过什么、哪些没做完)带上,下一句「继续」才知道从哪接着做;旧记录里只有「已取消」
            t.status == "cancelled" -> stopSummary(t)?.let { "the user stopped this task partway; before the stop: ${it.clip(clip)}" } ?: "the user cancelled this task"
            else -> "not finished, reason: ${t.outcome.clip(clip)}"
        }
        return "$n. The user said: ${t.userText.clip(clip)}; $result"
    }

    private fun String.clip(max: Int) = if (length <= max) this else take(max) + "…"
}
