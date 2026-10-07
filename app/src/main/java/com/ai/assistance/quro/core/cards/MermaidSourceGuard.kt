package com.ai.assistance.quro.core.cards

/**
 * Mermaid 源码守卫（#181）。
 *
 * ##为什么需要它
 *
 * 真机截图（23:44:54）给出的 mermaid 失败原文是：
 * ```
 * 流程图渲染失败：
 * Lexical error on line 4. Unrecognized text.
 * ...quro-ui 渲染原生控件」 B -->{D[纯文本回复] C -
 * -----------
 * ```
 * 两件事同时成立：
 *
 * 1. **模型把散文混进了 mermaid 源码**。第 4 行前半段`...quro-ui 渲染原生控件」`
 *    是自然语言（还带一个没配对的全角引号`「...」`），mermaid 词法器遇到裸文本直接报错。
 * 2. **宿主把报错糊成一坨**。[QuroChatCards] 的失败分支用 `err.take(100)` +
 *    `maxLines = 3` 截断，用户看到的是被切碎的 `-----------` 和半截字符，
 *    既不知道是第几行，也不知道自己（或模型）到底写了什么。
 *
 * 引擎侧修不了模型写的图，但**宿主有责任说清楚错在哪、错的是哪一行**。
 *
 * 本文件只放纯逻辑（无 Compose / 无 Android 依赖），以便单测钉死。
 */

/** 一次 mermaid 渲染失败的定位结果。 */
data class MermaidDiag(
    /** 报错指向的源码行号（1起）；解析不出来时为 null。 */
    val line: Int?,
    /** 报错指向的源码原文（[line] 为 null 时为空串）。 */
    val lineText: String,
) {
    val hasLine: Boolean get() = line != null && lineText.isNotBlank()
}

/**
 * mermaid 各版本报错文案格式不统一，实测见过这几种：
 * - `Lexical error on line 4. Unrecognized text.`（v10词法器）
 * - `Parse error on line 12:`（语法阶段）
 * - `error on line 3, column 5:`（老版本）
 * 所以大小写、`Parse`/`Lexical` 都得认，`line` 后面的数字也可能是多位。
 */
private val RE_ERR_LINE = Regex("""(?i)\b(?:on|at)\s+line\s+(\d{1,6})""")

/**
 * 从 mermaid 报错文案里解析出错行号，并在 [source] 中定位该行原文。
 *
 * 行号越界、解析不出数字、该行是空白时都返回 `hasLine=false` 的诊断对象——
 * 宁可只显示原始报错，也不要显示一个错误的「第 999 行」。
 */
fun mermaidErrorDiag(error: String, source: String): MermaidDiag {
    val raw = error ?: ""
    val n = RE_ERR_LINE.find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
    if (n == null || n < 1) return MermaidDiag(null, "")
    val text = source.split('\n').getOrNull(n - 1)?.trim().orEmpty()
    return MermaidDiag(n, text)
}

/**
 * mermaid 语句关键字（行首）。命中即视为合法图语句，原样保留。
 *
 * 只列**图结构语法**：图类型头、子图、类/样式/交互、结束、以及以`-->`/`---`/`.->`
 * 开头的边（边也常被模型写成 `A-->B` 这种粘在行首的形态）。
 */
private val MERMAID_KEYWORDS = listOf(
    "graph", "flowchart", "sequencediagram", "classdiagram", "statediagram", "statediagram-v2",
    "erdiagram", "journey", "gantt", "pie", "gitgraph", "mindmap", "timeline", "quadrantchart",
    "requirementdiagram", "sankey-beta", "xychart-beta", "block-beta", "packet-beta", "kanban",
    "c4context", "architecture-beta", "radar-beta", "treemap-beta", "info", "note", "subgraph",
    "end", "classdef", "class", "style", "linkstyle", "click", "callback", "href", "accTitle",
    "accDescr", "direction", "link", "%%", "-->", "---", "-.->", "==>", "-.-", "==="
)

/**
 * 一行里含这些符号时，说明它**至少在尝试**描述图结构，保留（宁可让引擎报错，
 * 也不能把模型写的真节点行删掉）。
 */
private val STRUCT_HINTS = listOf("-->", "---", "-.->", "==>", ":::", "[", "]", "{", "}", "(", ")", "|")

/**
 * 清理 mermaid 源码里**纯散文行**。
 *
 * 只做一件事：把「整行不含任何 mermaid 结构线索」的句子转成`%%` 注释行。
 * 这样模型不小心写下的自然语言不会让整张图挂掉，同时原文仍留在源码里可追溯。
 *
 * **绝不做的事**：
 * - 不动行内混排（散文 + `A-->B`粘在一行）——那种情况猜错的风险远大于收益，
 *   交给 [mermaidErrorDiag] 指出行号让用户/模型去改。
 * - 不删任何含`-->`/`[`/`{` 等结构线索的行。
 * - 不改引号、缩进、节点 id —— 那是引擎的活。
 *
 * @return 处理后的源码；与入参一致时返回同一个字符串引用（便于调用方跳过重组）。
 */
fun sanitizeMermaidSource(source: String): String {
    val src = source ?: return ""
    if (src.isBlank()) return src
    if (src.lineSequence().none { looksLikeMermaidProse(it) }) return src

    val out = StringBuilder(src.length + 16)
    src.split('\n').forEachIndexed { i, line ->
        if (i > 0) out.append('\n')
        if (looksLikeMermaidProse(line)) {
            out.append("%% ").append(line.trim())
        } else {
            out.append(line)
        }
    }
    return out.toString()
}

/**
 * 判断这一行是否是「模型误塞进来的纯散文」。
 *
 * 判定为散文需同时满足：
 * - 非空白；
 * - 不是 `%` 开头的注释；
 * - 去掉markdown 装饰（`**`/`__`/`#` 列表符/中文冒号引号）后，
 *   仍不以任何 [MERMAID_KEYWORDS] 开头；
 * - 且不含任何 [STRUCT_HINTS] 结构符号。
 */
private fun looksLikeMermaidProse(line: String): Boolean {
    val t = line.trim()
    if (t.isEmpty()) return false
    if (t.startsWith("%")) return false
    if (STRUCT_HINTS.any { t.contains(it) }) return false
    val head = t.trimStart('*', '_', '#', '-', ' ', '>', '|')
    return MERMAID_KEYWORDS.none { kw -> head.startsWith(kw, ignoreCase = true) }
}