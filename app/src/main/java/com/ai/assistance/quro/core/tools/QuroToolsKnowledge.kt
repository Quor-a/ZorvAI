package com.ai.assistance.quro.core.tools

import android.content.Context
import java.io.File
import org.json.JSONObject
import com.ai.assistance.quro.ui.extractOfficeText

/**
 * 文件知识库（Path ②）：把结构化文档（Markdown / JSON / TXT）放在应用专属目录的
 * `knowledge_base/` 下，AI 用 [KnowledgeSearchTool] 做关键词 + 段落级检索返回相关片段，
 * 用 [KnowledgeAddTool] 往库里追加内容。零基建、立即可用，覆盖日常 90% 的知识检索需求。
 * （真正语义检索的 RAG / 向量库留待文档量变大时再上。）
 */
object QuroKnowledgeFiles {
    fun dir(context: Context): File {
        val root = context.getExternalFilesDir(null)
            ?: throw IllegalStateException("无法访问外部存储")
        return File(root, "knowledge_base")
    }
}

/** 在知识库里按关键词检索，返回命中片段（文件:行号 + 内容），按命中词数排序。 */
class KnowledgeSearchTool : QuroTool {
    override val name = "knowledge_search"
    override val description = "🔍 知识库关键词检索：在本地知识库里按关键词精确匹配。" +
        "与 knowledge_rag_search 的区别：knowledge_search 是简单关键词匹配（快、精确），" +
        "knowledge_rag_search 是语义/词法混合检索（更智能、支持同义词）。" +
        "日常检索优先用 knowledge_rag_search，需要精确关键词匹配时用此工具。" +
        "参数：{\"query\":\"关键词\",\"limit\":8}。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "query":{"type":"string","description":"检索关键词（中文整句或英文多词均可）"},
            "limit":{"type":"integer","description":"返回条数，默认 8"}
        },
        "required":["query"]
    }"""
    override fun run(context: Context, arguments: String): String {
        val jo = JSONObject(arguments)
        val query = jo.optString("query", "").trim()
        if (query.isEmpty()) return "缺少 query 参数"
        val limit = jo.optInt("limit", 8).coerceIn(1, 30)
        val kb = QuroKnowledgeFiles.dir(context)
        if (!kb.exists()) kb.mkdirs()
        val files = kb.walkTopDown()
            // 关键词检索上限 20MB（原 5MB）：大文档也能参与关键词检索；RAG 索引本身不限大小
            .filter { it.isFile && it.extension.lowercase() in setOf("md", "txt", "json", "csv", "docx", "xlsx", "pptx") && it.length() <= 20_000_000L }
            .toList()
        if (files.isEmpty()) {
            return "知识库为空（目录：${kb.absolutePath}）。可用 knowledge_add 添加文件，或直接把 Markdown/JSON/TXT 放进该目录；" +
                "添加后再次 knowledge_search 即可检索。"
        }
        // 分词：有空格按词（过滤过短词），否则整句作为一个词；统一小写
        val tokens = if (query.contains(' ')) {
            query.lowercase().split(Regex("\\s+")).map { it.trim() }.filter { it.length >= 2 }
        } else {
            listOf(query.lowercase())
        }
        if (tokens.isEmpty()) return "检索词过短，请提供更有意义的关键词。"

        data class Hit(val score: Int, val path: String, val line: Int, val text: String)
        val hits = mutableListOf<Hit>()
        for (f in files) {
            val rel = f.relativeTo(kb).path
            val lowerName = f.name.lowercase()
            val ext = f.extension.lowercase()
            val isOffice = ext in setOf("docx", "xlsx", "pptx")
            // 文件名命中：整文件作为候选
            if (tokens.any { lowerName.contains(it) }) {
                val head = if (isOffice) extractOfficeText(f).lineSequence().firstOrNull() ?: ""
                else f.readText().lineSequence().firstOrNull() ?: ""
                hits.add(Hit(tokens.count { lowerName.contains(it) } + 1, rel, 0, "（文件名命中）${head.take(80)}"))
            }
            if (isOffice) {
                // Office 文档：抽取纯文本后按行检索
                val text = extractOfficeText(f)
                text.lineSequence().forEachIndexed { i, line ->
                    val low = line.lowercase()
                    val cnt = tokens.count { low.contains(it) }
                    if (cnt > 0) hits.add(Hit(cnt, rel, i + 1, line.take(200).trim()))
                }
            } else {
                runCatching {
                    f.readLines().forEachIndexed { i, line ->
                        val low = line.lowercase()
                        val cnt = tokens.count { low.contains(it) }
                        if (cnt > 0) hits.add(Hit(cnt, rel, i + 1, line.take(200).trim()))
                    }
                }
            }
        }
        if (hits.isEmpty()) {
            return "未在知识库中找到与「$query」相关的内容（已扫描 ${files.size} 个文件）。可换关键词，或用 knowledge_add 补充资料。"
        }
        val top = hits.sortedWith(compareByDescending<Hit> { it.score }.thenBy { it.path }.thenBy { it.line }).take(limit)
        val sb = StringBuilder()
        sb.append("知识库检索「$query」命中 ${hits.size} 处，展示前 ${top.size} 条：\n")
        for (h in top) {
            val loc = if (h.line > 0) "$h.path:${h.line}" else h.path
            sb.append("[$loc] ${h.text}\n")
        }
        return sb.toString().take(4000)
    }
}

/** 往知识库追加/写入一个文档。 */
class KnowledgeAddTool : QuroTool {
    override val name = "knowledge_add"
    override val description = "向本地知识库写入/追加一个文档（Markdown/JSON/TXT），参数 {\"path\":\"主题/文件名.md\",\"content\":\"内容\",\"append\":false}。" +
        "path 相对于 knowledge_base 目录；写入后可用 knowledge_search 检索。适合沉淀项目规范、技术笔记、领域资料。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "path":{"type":"string","description":"相对 knowledge_base 的路径，如 规范/编码风格.md"},
            "content":{"type":"string","description":"文档内容"},
            "append":{"type":"boolean","description":"是否追加，默认 false（覆盖）"}
        },
        "required":["path","content"]
    }"""
    override fun run(context: Context, arguments: String): String {
        val jo = JSONObject(arguments)
        val rel = jo.optString("path", "").trim()
        val content = jo.optString("content", "")
        if (rel.isEmpty()) return "缺少 path 参数"
        val kb = QuroKnowledgeFiles.dir(context)
        kb.mkdirs()
        val f = File(kb, rel.trimStart('/'))
        return try {
            f.parentFile?.mkdirs()
            if (jo.optBoolean("append", false)) f.appendText(content) else f.writeText(content)
            "已写入知识库：${f.absolutePath}（${f.length()} 字节）。可用 knowledge_search 检索其中的内容。"
        } catch (e: Exception) {
            "写入知识库失败：${e.message}"
        }
    }
}
