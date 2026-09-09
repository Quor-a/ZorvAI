package com.ai.assistance.quro.core.websearch.html

/**
 * SemanticChunker —— 按语义单元切块，替代"整篇按 token 截断"。
 *
 * 为什么必须换掉整篇截断：
 * 机械截断会破坏跨段论证。尤其"原因—条件—结论"型内容，
 * 条件在前、结论在后时，截断后进入提示词的结论已经失去约束条件，
 * 模型会把它当成无条件事实复述——这是编造引用的主要来源之一。
 *
 * 切块原则：
 * 1. 优先按标题层级切分，保留 heading_path 让模型理解上下文位置；
 * 2. 短块合并、超长块按段落边界再拆，不跨语义边界硬切；
 * 3. 表格和代码块作为原子单元，绝不拆散（数值拍平会直接毁掉信息）；
 * 4. 相邻块保留少量重叠，避免跨块论证被切断。
 */
object SemanticChunker {

    /** 一个语义块 */
    data class Chunk(
        /** 稳定标识：docId:index，供引用回溯 */
        val id: String,
        val docId: String,
        val index: Int,
        val text: String,
        /** 所属标题路径，如 ["安装", "常见问题"] */
        val headingPath: List<String>,
        /** 块类型，表格/代码需独立计预算 */
        val kind: Kind,
        /** 在原文中的字符偏移，便于高亮定位 */
        val offset: Int,
        /** 由 EvidenceReranker 填充 */
        var score: Double = 0.0
    )

    enum class Kind { TEXT, TABLE, CODE, LIST, HEADING }

    /** 块类型对应的输入 */
    data class Block(
        val text: String,
        val kind: Kind,
        val heading: String? = null,
        val offset: Int = 0
    )

    /**
     * @param docId 文档标识，用于生成 chunk_id
     * @param maxChars 单块字符上限（约 512 token）
     * @param overlapChars 相邻块重叠字符数
     */
    fun chunk(
        docId: String,
        blocks: List<Block>,
        maxChars: Int = 800,
        overlapChars: Int = 80
    ): List<Chunk> {
        if (blocks.isEmpty()) return emptyList()

        // 第一步：按标题路径归组，表格/代码独立成块
        val atoms = ArrayList<Atom>()
        val path = ArrayList<String>()
        var buf = StringBuilder()
        var bufOffset = blocks.firstOrNull()?.offset ?: 0

        fun flushText() {
            val t = buf.toString().trim()
            if (t.isNotBlank()) atoms.add(Atom(t, Kind.TEXT, path.toList(), bufOffset))
            buf = StringBuilder()
        }

        for (b in blocks) {
            when (b.kind) {
                Kind.HEADING -> {
                    flushText()
                    // 维护标题层级：同级替换，更浅则回退
                    val level = b.heading?.count { it == '#' }?.coerceIn(1, 4) ?: 2
                    while (path.size >= level) path.removeAt(path.lastIndex)
                    path.add(b.text.trimStart('#').trim())
                    bufOffset = b.offset
                }
                Kind.TABLE, Kind.CODE -> {
                    flushText()
                    atoms.add(Atom(b.text, b.kind, path.toList(), b.offset))
                }
                else -> {
                    if (buf.isNotEmpty()) buf.append('\n')
                    buf.append(b.text)
                }
            }
        }
        flushText()

        // 第二步：短块合并、长块拆分
        val merged = ArrayList<Atom>()
        var carry: Atom? = null
        for (a in atoms) {
            val c = carry
            when {
                // 表格/代码不与文本合并，也不被合并
                a.kind == Kind.TABLE || a.kind == Kind.CODE -> {
                    if (c != null) { merged.add(c); carry = null }
                    merged.add(a)
                }
                c == null -> carry = a
                c.text.length + a.text.length + 1 <= maxChars && c.headingPath == a.headingPath ->
                    carry = c.copy(text = c.text + "\n" + a.text)
                else -> { merged.add(c); carry = a }
            }
        }
        carry?.let { merged.add(it) }

        // 第三步：超长文本块按段落边界再拆
        val out = ArrayList<Chunk>()
        var idx = 0
        for (a in merged) {
            if (a.kind != Kind.TEXT || a.text.length <= maxChars) {
                out.add(a.toChunk(docId, idx++))
                continue
            }
            val pieces = splitByParagraph(a.text, maxChars, overlapChars)
            for (p in pieces) {
                out.add(
                    Chunk(
                        id = "$docId:$idx",
                        docId = docId,
                        index = idx++,
                        text = p,
                        headingPath = a.headingPath,
                        kind = Kind.TEXT,
                        offset = a.offset
                    )
                )
            }
        }
        return out
    }

    private data class Atom(
        val text: String,
        val kind: Kind,
        val headingPath: List<String>,
        val offset: Int
    ) {
        fun toChunk(docId: String, i: Int) = Chunk(
            id = "$docId:$i", docId = docId, index = i, text = text,
            headingPath = headingPath, kind = kind, offset = offset
        )
    }

    private val SENT_END = Regex("""[。！？!?.；;]\s*|\n+""")

    /**
     * 取尾部 overlap 个字符，但对齐到句子边界，避免半截句子。
     * 找不到边界时（如整段无标点）退化为按字符截取。
     */
    internal fun sentenceAlignedTail(text: String, overlap: Int): String {
        if (text.length <= overlap) return text
        val start = text.length - overlap
        val m = SENT_END.find(text, start)
        if (m != null && m.range.last + 1 < text.length) {
            val cut = m.range.last + 1
            val tail = text.substring(cut).trim()
            if (tail.isNotBlank()) return tail
        }
        return text.takeLast(overlap)
    }

    /** 按段落边界拆分，带重叠，避免跨块论证被切断 */
    internal fun splitByParagraph(text: String, maxChars: Int, overlap: Int): List<String> {
        val paras = text.split(Regex("""\n+""")).filter { it.isNotBlank() }
        if (paras.isEmpty()) return listOf(text)

        val out = ArrayList<String>()
        var cur = StringBuilder()
        for (p in paras) {
            if (cur.length + p.length + 1 > maxChars && cur.isNotEmpty()) {
                val full = cur.toString().trim()
                out.add(full)
                // 重叠：保留上一段尾部，但要对齐到句子边界，
                // 否则新块会以半截句子开头（实测中出现的"论证文本，说明条件…"就是这么来的）
                cur = StringBuilder(sentenceAlignedTail(full, overlap))
                if (cur.isNotEmpty()) cur.append('\n')
            }
            if (cur.isNotEmpty()) cur.append('\n')
            cur.append(p)
        }
        if (cur.toString().isNotBlank()) out.add(cur.toString().trim())
        return out.ifEmpty { listOf(text) }
    }
}
