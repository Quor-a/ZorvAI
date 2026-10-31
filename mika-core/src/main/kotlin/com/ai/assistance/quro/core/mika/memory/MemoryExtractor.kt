package com.ai.assistance.quro.core.mika.memory

import com.ai.assistance.quro.core.mika.model.Author
import com.ai.assistance.quro.core.mika.model.ChatModel
import com.ai.assistance.quro.core.mika.model.Message
import com.ai.assistance.quro.core.mika.model.ModelJson
import com.ai.assistance.quro.core.mika.model.ModelJson.field
import com.ai.assistance.quro.core.mika.model.Part
import com.ai.assistance.quro.core.mika.model.Role
import com.ai.assistance.quro.core.mika.prompt.Prompts
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Suppress("EnumEntryName")
enum class NoteKind { added, updated, forgot, pending }

/**
 * 结果卡片下的一行。只带种类和编号,不带记忆内容:对话文件是明文,内容每次从加密的记忆里现取([text])。
 * [undoToken] 交给 [MemoryStore.undo];[pendingId] 是待批准的做法(「好」= approve,「不要」= 撤销或删除)。
 */
data class MemoryNote(val kind: NoteKind, val memoryId: String, val undoToken: String?, val pendingId: String?) {
    /** [item] 是 [memoryId] 此刻的那条;已经不在了(删掉、清空)时写「一条已删除的记忆」。 */
    fun text(item: MemoryItem?): String {
        val content = item?.content ?: DELETED
        return when (kind) {
            NoteKind.added -> "记住了:$content"
            NoteKind.updated -> "更新了:$content" + (item?.replaces?.let { "(原来:$it)" } ?: "")
            NoteKind.forgot -> if (item == null) "忘掉了一条记忆" else "忘掉了:$content"
            NoteKind.pending -> "要我以后都这样做吗?$content"
        }
    }

    companion object {
        const val DELETED = "一条已删除的记忆"
    }
}

/**
 * 任务结束后的一次单独调用(轻量模型,不带工具):只看用户本次说的话和相关的已有记忆,给出增改删。
 * 模型给的每一条都由代码再校验一遍;模型出错或回复不合格时什么也不做,不影响任务结局。
 */
class MemoryExtractor(private val model: ChatModel) {
    suspend fun extract(utterances: List<String>, related: List<MemoryItem>, store: MemoryStore, source: (String) -> MemorySource): List<Change> {
        if (utterances.none { it.isNotBlank() }) return emptyList()
        val labels = related.take(MAX_RELATED).mapIndexed { i, m -> "#${i + 1}" to m }.toMap()
        val ops = try {
            ModelJson.completeParsed(model, PROMPT, listOf(Message(Role.User, listOf(Part.Text(request(utterances, labels))), Author.ZorvAI)), ::parse)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        val said = UserUtterances().apply { utterances.forEach(::add) }
        return ops.take(MAX_OPS).mapNotNull { apply(it, labels, said, store, source) }
    }

    private fun apply(o: JsonObject, labels: Map<String, MemoryItem>, said: UserUtterances, store: MemoryStore, source: (String) -> MemorySource): Change? {
        fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()
        val quote = str("quote")?.takeIf { said.contains(it) } ?: return null
        val keywords = (o["keywords"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: emptyList()
        val target = str("target")?.let { t -> labels[t] ?: labels["#$t"] }
        fun fromUser(content: String) = (listOf(content) + keywords).all { said.unsupportedSpecifics(it, quote).isEmpty() }
        return when (str("op")) {
            "add" -> {
                val kind = MemoryKind.entries.find { it.name == str("kind") } ?: return null
                val content = str("content")?.takeIf(::fromUser) ?: return null
                store.add(kind, content, keywords, source(quote)).getOrNull()
            }
            "update" -> {
                val t = target ?: return null
                // 一次性参数不能把长期偏好改掉
                if (ONE_OFF.containsMatchIn(quote)) return null
                val content = str("content")?.takeIf(::fromUser) ?: return null
                store.update(t.id, content, keywords.takeIf { it.isNotEmpty() }, source(quote)).getOrNull()
            }
            "forget" -> {
                val t = target ?: return null
                if (!FORGET.containsMatchIn(quote)) return null
                store.forget(t.id).getOrNull()
            }
            else -> null
        }
    }

    private fun request(utterances: List<String>, labels: Map<String, MemoryItem>) = buildString {
        appendLine("用户本次说的话:")
        utterances.filter { it.isNotBlank() }.forEachIndexed { i, u -> appendLine("${i + 1}. $u") }
        appendLine()
        if (labels.isEmpty()) append("已有的相关记忆:无")
        else {
            appendLine("已有的相关记忆:")
            labels.forEach { (label, m) -> appendLine("$label [${m.kind.name}] ${m.content}") }
        }
    }.trimEnd()

    companion object {
        const val MAX_OPS = 5
        const val MAX_RELATED = 10
        private val FORGET = Regex("忘|别记|删|不用记|forget", RegexOption.IGNORE_CASE)
        private val ONE_OFF = Regex("这次|这一次|今天先|this time", RegexOption.IGNORE_CASE)

        val PROMPT: String by lazy { Prompts.role("memory_extract") }

        /** 与这次说的话相关的已有记忆(待批准的也给,免得重复提出同一条做法)。 */
        fun related(utterances: List<String>, items: List<MemoryItem>): List<MemoryItem> =
            MemoryIndex(items).search(utterances.joinToString("\n"), MAX_RELATED, includePending = true).map { it.first }

        /** 变更翻成结果卡片下的提示行。 */
        fun notes(changes: List<Change>): List<MemoryNote> = changes.map { c ->
            when (c) {
                is Change.Added ->
                    if (c.item.status == MemoryStatus.pending) MemoryNote(NoteKind.pending, c.item.id, c.undoToken, c.item.id)
                    else MemoryNote(NoteKind.added, c.item.id, c.undoToken, null)
                is Change.Updated ->
                    if (c.item.status == MemoryStatus.pending) MemoryNote(NoteKind.pending, c.item.id, c.undoToken, c.item.id)
                    else MemoryNote(NoteKind.updated, c.item.id, c.undoToken, null)
                is Change.Forgot -> MemoryNote(NoteKind.forgot, c.before.id, c.undoToken, null)
            }
        }

        /** 回复里第一个能读的 JSON 对象(见 [ModelJson]);不合格返回 null。 */
        internal fun parse(text: String): List<JsonObject>? {
            val obj = ModelJson.firstObject(text) ?: return null
            return (obj.field("ops", "operations") as? JsonArray)?.mapNotNull { it as? JsonObject }
        }
    }
}
