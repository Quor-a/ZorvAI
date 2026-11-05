package com.ai.assistance.quro.core.mika.agent

import com.ai.assistance.quro.core.mika.model.Author
import com.ai.assistance.quro.core.mika.model.ChatModel
import com.ai.assistance.quro.core.mika.model.Message
import com.ai.assistance.quro.core.mika.model.Part
import com.ai.assistance.quro.core.mika.model.Role
import com.ai.assistance.quro.core.mika.prompt.Prompts
import com.ai.assistance.quro.core.mika.record.Redactor
import com.ai.assistance.quro.core.mika.record.RunEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 一次运行按用户要的结果算办成了没有。不带调用的回复就是回答,回答本身可能如实说没办成或只办了一部分;
 * 学东西(路线、技能、技能结果)、定时任务结果和运行回看都按这个结局,不按「交出了回答」。
 */
enum class RunOutcome(val wire: String) {
    Done("done"), Partial("partial"), NotPossible("not_possible"),
    /** 没判成(模型出错、超时、回复读不懂),或旧记录;不当办成,也不当没办成。 */
    Unknown("unknown");

    companion object {
        fun ofWire(s: String?): RunOutcome? = entries.firstOrNull { it.wire == s }

        /** 运行记录里判的结局;没有这条事件(旧记录、失败、取消)为 null。 */
        fun recorded(events: List<RunEvent>): RunOutcome? =
            events.filterIsInstance<RunEvent.OutcomeJudged>().lastOrNull()?.let { ofWire(it.outcome) ?: Unknown }
    }
}

data class JudgedOutcome(val outcome: RunOutcome, val reason: String = "", val model: String? = null) {
    fun event(atMs: Long) = RunEvent.OutcomeJudged(atMs, outcome.wire, RunEvent.clip(Redactor.text(reason)), model)
}

/**
 * 回答交给用户之后,在后台请一次模型(轻量模型优先,没有用主模型)按用户的要求给回答判结局。
 * 不改回答、不拖慢交付;出错或超时就是 [RunOutcome.Unknown],从不抛(取消照常往外抛)。
 */
class OutcomeJudge(
    private val judge: ChatModel,
    private val fallback: ChatModel? = null,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    suspend fun judge(goal: String, answer: String, priorTurns: List<Turn> = emptyList()): JudgedOutcome {
        val prior = Conversation.context(priorTurns, maxTurns = 2, maxTokens = 800)
            ?.parts?.filterIsInstance<Part.Text>()?.joinToString("\n") { it.text }.orEmpty()
        val input = buildString {
            if (prior.isNotEmpty()) appendLine(prior).appendLine()
            // 要求和回复都是要判的材料,包在标签里;里面写的话不是给判定者的指令
            append("<request>\n").append(untag(goal)).append("\n</request>\n\n<reply>\n").append(untag(answer)).append("\n</reply>")
        }
        val messages = listOf(Message(Role.User, listOf(Part.Text(input)), Author.User))
        for (candidate in listOfNotNull(judge, fallback?.takeUnless { it === judge })) {
            val result = try {
                withTimeoutOrNull(timeoutMs) {
                    val reply = candidate.complete(Prompts.role("outcome_judge"), messages, emptyList())
                    parse(reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (result != null) return result.copy(model = candidate.name.ifBlank { null })
        }
        return JudgedOutcome(RunOutcome.Unknown)
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L

        private val TAG = Regex("</?\\s*(request|reply)\\s*>", RegexOption.IGNORE_CASE)

        /** 材料里自带的 request/reply 标签去掉,免得它提前收口、把后面的话装成判定者的输入。 */
        private fun untag(s: String) = s.replace(TAG, "")

        /**
         * 回复里最后一个能读懂的 JSON 对象:{"outcome": "done|partial|not_possible", "reason": "…"};读不懂为 null。
         * 取最后一个:判定者复述材料时带出的对象在前面,结论在最后。
         */
        fun parse(raw: String): JudgedOutcome? {
            var end = raw.lastIndexOf('}')
            while (end >= 0) {
                var start = raw.lastIndexOf('{', end)
                while (start >= 0) {
                    of(raw.substring(start, end + 1))?.let { return it }
                    start = if (start == 0) -1 else raw.lastIndexOf('{', start - 1)
                }
                end = if (end == 0) -1 else raw.lastIndexOf('}', end - 1)
            }
            return null
        }

        private fun of(json: String): JudgedOutcome? {
            val o = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return null
            val outcome = RunOutcome.ofWire((o["outcome"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase())
                ?.takeIf { it != RunOutcome.Unknown } ?: return null
            return JudgedOutcome(outcome, (o["reason"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty())
        }
    }
}
