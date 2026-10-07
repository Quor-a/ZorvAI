package com.codecanvas.core.extract

/**
 * 从 LLM 输出里抽取代码块。
 *
 * AI 返回的是 Markdown，代码块必须完整闭合后才可高亮 ——
 * 流式过程中渲染半截 ``` 会产生乱码，所以提供 [isMdComplete] 供上层判断。
 */
object CodeBlockExtractor {

    // 支持 ```lang 与 ~~~lang，并容忍首行前后空白
    private val FENCE = Regex(
        """^\s{0,3}(```+|~~~+)\s*([A-Za-z0-9+#._-]*)\s*\R([\s\S]*?)^\s{0,3}\1\s*$""",
        RegexOption.MULTILINE
    )

    data class Block(
        val language: String,
        val code: String,
        val startIndex: Int,
        val endIndex: Int,
    )

    fun extract(markdown: String): List<Block> = FENCE.findAll(markdown).map { m ->
        Block(
            language = normalizeLang(m.groupValues[2]),
            code = m.groupValues[3].trimEnd('\n'),
            startIndex = m.range.first,
            endIndex = m.range.last,
        )
    }.toList()

    /**
     * 流式过程中代码块是否已完整闭合（半截 ``` 时返回 false）。
     *
     * 判定只看**行首围栏标记**是否成对：数 ` 和 ~~~ 的出现次数即可。
     *
     * 早期实现是 `count('`') % 2 == 0 && !FENCE.containsMatchIn(md + "\n```")`，
     * 后半句恒为 true（追加的 ``` 本身就构成一次匹配），导致**完整的代码块
     * 也被判成未完成**，AI 场景里卡片永远渲染不出来。已改为纯计数。
     */
    fun isMdComplete(markdown: String): Boolean {
        val ticks = FENCE_OPEN_TICK.findAll(markdown).count()
        val tildes = FENCE_OPEN_TILDE.findAll(markdown).count()
        return ticks % 2 == 0 && tildes % 2 == 0
    }

    private val FENCE_OPEN_TICK = Regex("^\\s{0,3}```", RegexOption.MULTILINE)
    private val FENCE_OPEN_TILDE = Regex("^\\s{0,3}~~~", RegexOption.MULTILINE)

    /** 去掉围栏后的纯文本（用于预览 AI 的解释部分） */
    fun stripCode(markdown: String): String = FENCE.replace(markdown, "〔代码块〕")

    /** 语言别名归一：LLM 常输出 ts/jsx/py 等非标准 id */
    fun normalizeLang(raw: String): String {
        val s = raw.lowercase().trim()
        return ALIASES[s] ?: s.ifBlank { "plaintext" }
    }

    private val ALIASES = mapOf(
        "ts" to "typescript", "tsx" to "typescript", "js" to "javascript",
        "jsx" to "javascript", "mjs" to "javascript", "py" to "python",
        "kt" to "kotlin", "kts" to "kotlin", "c++" to "cpp", "cc" to "cpp",
        "cs" to "csharp", "rb" to "ruby", "rs" to "rust", "sh" to "bash",
        "shell" to "bash", "yml" to "yaml", "md" to "markdown",
        "golang" to "go", "objc" to "objectivec", "plaintext" to "plaintext",
    )
}
