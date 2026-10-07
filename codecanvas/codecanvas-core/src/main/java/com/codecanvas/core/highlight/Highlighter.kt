package com.codecanvas.core.highlight

/**
 * 代码高亮抽象。
 *
 * 独立成接口的原因：代码卡片场景里，高亮器（highlight.js / Prism / 原生 lexer）
 * 与渲染后端（WebView / Canvas / Compose）是**正交**的两件事，可以任意组合。
 */
interface Highlighter {

    /** 该高亮器支持的语言 id 集合，空集表示「支持全部 / 不校验」 */
    suspend fun supportedLanguages(): Set<String>

    /**
     * 高亮为带样式的 HTML 片段（<span class="hljs-keyword">…</span>）。
     * WEBVIEW 后端直接吃这个结果。
     */
    suspend fun highlightToHtml(code: String, language: String, theme: String): String

    /**
     * 高亮为**逐行**的 token 列表，供 Canvas / Compose / SVG 后端用 [RichText] 指令绘制。
     *
     * 返回行列表而非扁平列表的原因：代码卡片需要行号与自动换行，
     * 只有保留行边界才能正确排版。
     */
    suspend fun highlightToLines(
        code: String,
        language: String,
        theme: String,
    ): List<HighlightedLine>

    /** 自动识别语言 */
    suspend fun detectLanguage(code: String): String?

    data class Span(val text: String, val color: String, val bold: Boolean = false)
}

/** 一行代码的高亮结果（Canvas/Compose/SVG 绘制用） */
data class HighlightedLine(
    val lineNumber: Int,
    val spans: List<Highlighter.Span>,
) {
    /** 该行纯文本，用于测量宽度与自动换行 */
    val plain: String get() = spans.joinToString("") { it.text }
}

/** 高亮器不可用时的兜底：全文本单色输出，保证链路不中断 */
class PlainHighlighter(private val defaultColor: String = "#E6EDF3") : Highlighter {
    override suspend fun supportedLanguages(): Set<String> = emptySet()

    override suspend fun highlightToHtml(code: String, language: String, theme: String): String =
        "<pre><code>${escapeHtml(code)}</code></pre>"

    override suspend fun highlightToLines(code: String, language: String, theme: String) =
        code.split('\n').mapIndexed { i, line ->
            HighlightedLine(i + 1, listOf(Highlighter.Span(line, defaultColor, false)))
        }

    override suspend fun detectLanguage(code: String): String? = null

    private fun escapeHtml(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
