package com.ai.assistance.quro.core.mika.prompt

/**
 * 工具结果与主循环里给模型的反馈文字(提示、错误说明、提醒、参考资料的标题),都在 prompts/feedback/ 下的 .md 文件里:
 * 每条以一行「## 键」开头,下面到下一条之前是原文(可以多行,首尾空行不算);{{name}} 由调用方填。
 */
object Feedback {
    /** 反馈文件;键在所有文件里唯一。 */
    val FILES = listOf("tools", "loop")
    private val HEADING = Regex("^## ([a-z0-9_.]+)$")

    private val entries: Map<String, String> by lazy {
        val out = LinkedHashMap<String, String>()
        for (f in FILES) parse(Prompts.read("feedback/$f.md")).forEach { (k, v) -> check(out.put(k, v) == null) { "duplicate feedback key $k" } }
        out
    }

    /** 一个反馈文件里的各条(键 → 原文),按出现顺序;第一条之前的说明不算。 */
    fun parse(text: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var key: String? = null
        val body = mutableListOf<String>()
        fun flush() { key?.let { out += it to body.joinToString("\n").trim('\n') }; body.clear() }
        for (line in text.lines()) {
            val m = HEADING.matchEntire(line)
            if (m != null) { flush(); key = m.groupValues[1] } else if (key != null) body += line
        }
        flush()
        return out
    }

    operator fun get(key: String): String = Prompts.fill(raw(key), emptyMap())

    fun format(key: String, vararg vars: Pair<String, Any?>): String = Prompts.fill(raw(key), vars.toMap())

    fun has(key: String): Boolean = key in entries

    /** 键以 [prefix] 开头的各条,键去掉前缀(例如按错误码给的说明)。 */
    fun section(prefix: String): Map<String, String> =
        entries.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }

    val keys: Set<String> get() = entries.keys

    private fun raw(key: String): String = checkNotNull(entries[key]) { "missing feedback $key" }
}
