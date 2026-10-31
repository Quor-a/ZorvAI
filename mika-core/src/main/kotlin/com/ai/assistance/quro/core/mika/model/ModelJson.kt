package com.ai.assistance.quro.core.mika.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 读模型按要求交回的 JSON(核查结论、路线匹配、记忆变更……)。不同模型交回的样子不一样:套代码块、前后带几句话、
 * 先想一段再给、一个对象后面又跟一段带花括号的说明、键名大小写或写法不同。这里统一容忍这些;
 * 实在读不出来时再问一次,明确只要 JSON([completeParsed])。
 */
object ModelJson {
    private val lenient = Json { isLenient = true; ignoreUnknownKeys = true }

    /** 回复里第一个能读成 JSON 对象的、括号配平的片段(字符串里的括号不算);没有为 null。 */
    fun firstObject(text: String): JsonObject? {
        var from = text.indexOf('{')
        while (from >= 0) {
            val end = balancedEnd(text, from)
            if (end > from) {
                val obj = try { lenient.parseToJsonElement(text.substring(from, end + 1)) as? JsonObject } catch (e: Exception) { null }
                if (obj != null) return obj
            }
            from = text.indexOf('{', from + 1)
        }
        return null
    }

    /** 从 [start] 的 { 起,配平的 } 的位置;配不平为 -1。 */
    private fun balancedEnd(s: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until s.length) {
            val c = s[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> if (--depth == 0) return i
            }
        }
        return -1
    }

    /** 键名比对前去掉大小写、下划线、连字符和空格(verdict、Verdict、final_verdict 里的写法差异)。 */
    private fun key(k: String) = k.lowercase().filter { it.isLetterOrDigit() }

    /** 按 [names] 依次找(写法不同也认);都没有为 null。 */
    fun JsonObject.field(vararg names: String): JsonElement? {
        for (n in names) {
            this[n]?.let { return it }
            val want = key(n)
            entries.firstOrNull { key(it.key) == want }?.let { return it.value }
        }
        return null
    }

    /** 第一次读不出来时补的一句。 */
    const val JSON_ONLY = "上一条回复读不出 JSON。请只输出一个 JSON 对象,不要代码块标记,不要任何其他文字,格式照系统提示。"

    /**
     * 调一次模型并用 [parse] 读回复;读不出来就把那次回复和 [JSON_ONLY] 接在后面再问一次。两次都不行为 null。
     * 模型出错(异常)照常抛给调用方。
     */
    suspend fun <T : Any> completeParsed(model: ChatModel, system: String, messages: List<Message>, parse: (String) -> T?): T? {
        val first = model.complete(system, messages, emptyList())
        val firstText = first.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
        parse(firstText)?.let { return it }
        val retry = messages + Message(Role.Assistant, listOf(Part.Text(firstText.ifBlank { "(空)" })), Author.ZorvAI) +
            Message(Role.User, listOf(Part.Text(JSON_ONLY)), Author.ZorvAI)
        val second = model.complete(system, retry, emptyList())
        return parse(second.parts.filterIsInstance<Part.Text>().joinToString("") { it.text })
    }
}
