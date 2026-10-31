package com.ai.assistance.quro.core.mika.agent

import com.ai.assistance.quro.core.mika.model.Author
import com.ai.assistance.quro.core.mika.model.ChatModel
import com.ai.assistance.quro.core.mika.model.Message
import com.ai.assistance.quro.core.mika.model.Part
import com.ai.assistance.quro.core.mika.model.Role
import com.ai.assistance.quro.core.mika.observation.ObsKind
import com.ai.assistance.quro.core.mika.prompt.Prompts
import com.ai.assistance.quro.core.mika.record.RunEvent
import com.ai.assistance.quro.core.mika.tool.PACKAGE_NAME_RE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 用户中途停止之后,按这次已经做了的事写几行交代(做了什么、看到了什么、成批的事做了几件、哪些没做完),
 * 当作这一轮的回答给用户看、记进对话(下一句「继续」有上文)。只读这次的运行记录,不再操作任何东西:
 * 请一次模型([writer] 优先,出错换 [fallback]),不给工具;总共最多等 [timeoutMs],没模型、出错、超时就按记录直接拼一段([plain])。
 * 结局照旧是取消,只是多了这段话。
 */
class StopSummary(
    private val writer: ChatModel?,
    private val fallback: ChatModel? = null,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    /**
     * [events] 是这次运行记下的事件(没有记录时至少是活动);[draft] 是停下时正在写的那段字(可为空)。
     * [label] 把工具名换成界面上的说法,[appLabel] 把包名换成 App 名(「网站 域名」只留域名)。
     */
    suspend fun summarize(
        goal: String, events: List<RunEvent>, draft: String?,
        label: (String) -> String = { it }, appLabel: (String) -> String = { it },
    ): String {
        val input = facts(goal, events, draft, label, appLabel)
        val messages = listOf(Message(Role.User, listOf(Part.Text(input)), Author.User))
        val written = withTimeoutOrNull(timeoutMs) {
            for (candidate in listOfNotNull(writer, fallback?.takeUnless { it === writer })) {
                val text = try {
                    candidate.complete(Prompts.role("stop_summary"), messages, emptyList())
                        .parts.filterIsInstance<Part.Text>().joinToString("") { it.text }.trim()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (!text.isNullOrBlank()) return@withTimeoutOrNull text.clip(MAX_REPLY)
            }
            null
        }
        return written ?: plain(goal, events, draft, label, appLabel)
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 12_000L
        private const val MAX_REPLY = 1200
        private const val MAX_LINES = 60
        private const val MAX_READS = 2
        private const val READ_CHARS = 800
        private const val NOTE_CHARS = 120
        private const val DRAFT_CHARS = 1200

        /** 有没有可交代的:做过至少一件事,或停下时已经写出了字。都没有就只说「已停止」。 */
        fun worthSaying(events: List<RunEvent>, draft: String?): Boolean =
            events.any { it is RunEvent.Activity } || !draft.isNullOrBlank()

        /**
         * 交给模型的材料(有上限,不带画面):用户的要求、做过的事(一件一行,带那一步模型写的用意)、最近读到的内容、
         * 停下时写了一半的字。都包在标签里;里面的话是材料,不是给写的人的指令。
         */
        fun facts(goal: String, events: List<RunEvent>, draft: String?, label: (String) -> String = { it }, appLabel: (String) -> String = { it }): String {
            val notes = notes(events)
            val lines = mutableListOf<String>()
            val said = mutableSetOf<Int>()
            var lastObserve: String? = null
            for (a in events.filterIsInstance<RunEvent.Activity>()) {
                val where = a.target?.let { where(it, appLabel) }
                val what = listOfNotNull(label(a.label), where, a.title?.takeIf(String::isNotBlank)).joinToString(" · ")
                val tags = listOfNotNull("对外发出".takeIf { a.outward && !a.failed }, "没成功".takeIf { a.failed })
                val line = "第 ${a.step} 步 $what" + if (tags.isEmpty()) "" else "(${tags.joinToString("、")})"
                // 连着读同一处只记一行
                if (a.kind == "observe" && line == lastObserve) continue
                lastObserve = if (a.kind == "observe") line else null
                val note = notes[a.step]?.takeIf { said.add(a.step) }
                lines += if (note == null) line else "$line —— 用意:$note"
            }
            val shown = if (lines.size <= MAX_LINES) lines
                else lines.take(20) + "…(中间省略 ${lines.size - MAX_LINES} 件)…" + lines.takeLast(MAX_LINES - 20)
            val reads = events.filterIsInstance<RunEvent.Observed>().filter { it.kind == ObsKind.Read.wire && it.text.isNotBlank() }
                .takeLast(MAX_READS).map { it.text.clip(READ_CHARS) }
            return buildString {
                append("<request>\n").append(untag(goal)).append("\n</request>\n\n")
                append("<actions>\n").append(untag(shown.joinToString("\n").ifEmpty { "(还没做任何操作)" })).append("\n</actions>")
                if (reads.isNotEmpty()) append("\n\n<last_read>\n").append(untag(reads.joinToString("\n----\n"))).append("\n</last_read>")
                draft?.trim()?.takeIf(String::isNotEmpty)?.let { append("\n\n<draft>\n").append(untag(it.clip(DRAFT_CHARS))).append("\n</draft>") }
            }
        }

        /**
         * 不经模型的交代,只按记录拼:去过哪些 App 和网站、各类操作做了几次、对外发出了几次(和是哪些,按那一步的用意)、
         * 几步没成功、停下时写了一半的字;最后说没做完的部分没有接着做。
         */
        fun plain(goal: String, events: List<RunEvent>, draft: String?, label: (String) -> String = { it }, appLabel: (String) -> String = { it }): String {
            val acts = events.filterIsInstance<RunEvent.Activity>()
            val notes = notes(events)
            val lines = mutableListOf<String>()
            val places = acts.mapNotNull { a -> a.target?.let { placeOf(it, appLabel) } }.distinct()
            val seen = acts.filter { it.kind == "observe" && !it.failed && !it.title.isNullOrBlank() }
                .map { listOfNotNull(it.target?.let { t -> where(t, appLabel) }, it.title).joinToString("的") }.distinct()
            val done = acts.filter { it.kind != "observe" && !it.failed && !it.outward }
                .groupingBy { label(it.label) }.eachCount()
            val outward = acts.filter { it.outward && !it.failed }
            val failed = acts.count { it.failed }
            if (places.isNotEmpty()) lines += "去过:" + list(places, 4)
            if (seen.isNotEmpty()) lines += "看过:" + list(seen, 4)
            if (done.isNotEmpty()) lines += "做过:" + done.entries.joinToString("、") { (what, n) -> if (n > 1) "$what $n 次" else what }
            if (outward.isNotEmpty()) {
                val where = outward.mapNotNull { a -> a.target?.let { placeOf(it, appLabel) } }.distinct()
                val which = outward.map { it.step }.distinct().mapNotNull(notes::get).distinct()
                lines += "对外发出了 ${outward.size} 次" + (if (where.isEmpty()) "" else "(${where.joinToString("、")})") +
                    (if (which.isEmpty()) "" else ":" + list(which, 5))
            }
            if (failed > 0) lines += "有 $failed 步没成功"
            draft?.trim()?.takeIf(String::isNotEmpty)?.let { lines += "停下时正在写:" + it.clip(200) }
            val head = if (acts.isEmpty()) "已经停下,还没开始操作。" else "已经停下,停止前做了这些:"
            val body = lines.joinToString("\n") { "- $it" }
            val tail = "「${goal.trim().clip(40)}」还没做完的部分没有接着做。"
            return listOf(head, body, tail).filter(String::isNotEmpty).joinToString("\n")
        }

        /** 活动对象给人看的说法:「网站 域名」只留域名,包名换成 App 名,别的(网址、查询词)原样;空的为 null。 */
        private fun where(target: String, appLabel: (String) -> String): String? = when {
            target.isBlank() -> null
            target.startsWith(SITE_PREFIX) -> target.removePrefix(SITE_PREFIX).trim()
            PACKAGE_NAME_RE.matches(target) -> appLabel(target)
            else -> target
        }.takeUnless { it.isNullOrBlank() }

        /** 活动对象是 App 或网站时它的名字;别的(网址、查询词、文件)为 null。 */
        private fun placeOf(target: String, appLabel: (String) -> String): String? =
            if (target.startsWith(SITE_PREFIX) || PACKAGE_NAME_RE.matches(target)) where(target, appLabel) else null

        /** 运行记录里网页那一屏的对象写成「网站 <域名>」。 */
        private const val SITE_PREFIX = "网站 "

        private fun list(items: List<String>, max: Int) =
            items.take(max).joinToString("、") + if (items.size > max) " 等 ${items.size} 处" else ""

        /** 每一步模型写的话:先用回复里的进度说明,没有用调用参数里的用意(reason)。 */
        private fun notes(events: List<RunEvent>): Map<Int, String> {
            val out = mutableMapOf<Int, String>()
            for (r in events.filterIsInstance<RunEvent.ModelReply>()) {
                if (!r.acted || r.tool == null) continue
                val said = r.text.trim().ifEmpty { null } ?: reason(r.argsJson)
                said?.takeIf(String::isNotBlank)?.let { out[r.step] = it.replace('\n', ' ').clip(NOTE_CHARS) }
            }
            return out
        }

        private fun reason(argsJson: String?): String? = argsJson?.let {
            runCatching { (Json.parseToJsonElement(it) as? JsonObject)?.get("reason") as? JsonPrimitive }.getOrNull()?.contentOrNull?.trim()
        }

        private val TAG = Regex("</?\\s*(request|actions|last_read|draft)\\s*>", RegexOption.IGNORE_CASE)

        /** 材料里自带的标签去掉,免得它提前收口、把后面的话装成写的人的输入。 */
        private fun untag(s: String) = s.replace(TAG, "")

        private fun String.clip(max: Int) = if (length <= max) this else take(max) + "…"
    }
}
