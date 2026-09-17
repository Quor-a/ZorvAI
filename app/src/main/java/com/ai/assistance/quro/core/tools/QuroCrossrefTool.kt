package com.ai.assistance.quro.core.tools

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Crossref 学术文献检索（免费、无需鉴权）。
 * 把 Crossref REST API 当作对话内文献搜索引擎，返回标题/作者/年份/DOI。
 */
class QuroCrossrefTool : QuroTool {
    override val name = "crossref_search"
    override val description = "检索 Crossref 学术文献数据库（免费、无需鉴权）：按关键词查找论文/书籍/章节，返回标题、作者、年份、DOI 与链接。" +
        "参数 {\"query\":\"关键词（必填）\",\"rows\":10（1-30 默认 10），\"type\":\"可选过滤 article|book|chapter|journal_article\"}。" +
        "适用：AI 做文献调研、引用溯源、找相关论文。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "query":{"type":"string","description":"检索关键词（必填）"},
            "rows":{"type":"integer","description":"返回条数，默认 10，最大 30"},
            "type":{"type":"string","description":"文献类型过滤：article / book / chapter / journal_article（可选）"}
        },
        "required":["query"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val jo = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
        val query = jo.optString("query", "").trim()
        if (query.isEmpty()) return "❌ 缺少 query 参数"
        val rows = jo.optInt("rows", 10).coerceIn(1, 30)
        val type = jo.optString("type", "").trim()
        return runBlocking(Dispatchers.IO) {
            try {
                val q = URLEncoder.encode(query, "UTF-8")
                val url = buildString {
                    append("https://api.crossref.org/works?query=").append(q)
                    append("&rows=").append(rows)
                    if (type.isNotBlank()) append("&filter=type:").append(URLEncoder.encode(type, "UTF-8"))
                }
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "ZorvAI/1.0 (mailto:quro@example.com)")
                    connectTimeout = 15000
                    readTimeout = 20000
                }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
                conn.disconnect()
                if (code !in 200..299) return@runBlocking "❌ Crossref 请求失败 HTTP $code"
                formatResults(text)
            } catch (e: Exception) {
                "❌ Crossref 检索失败：${e.message}"
            }
        }
    }

    private fun formatResults(text: String): String {
        val root = JSONObject(text)
        val items = root.optJSONObject("message")?.optJSONArray("items") ?: return "未找到相关文献。"
        if (items.length() == 0) return "未找到相关文献。"
        val sb = StringBuilder("📚 Crossref 检索结果（${items.length()}）：\n")
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            val titleArr = it.optJSONArray("title")
            val title = if (titleArr != null && titleArr.length() > 0) titleArr.optString(0) else "(无标题)"
            val doi = it.optString("DOI", "")
            val year = it.optJSONObject("issued")?.optJSONArray("date-parts")?.optJSONArray(0)?.optInt(0, -1) ?: -1
            sb.append("${i + 1}. $title")
            if (year > 0) sb.append(" ($year)")
            sb.append('\n')
            val authors = buildAuthors(it)
            if (authors.isNotBlank()) sb.append("   作者: $authors\n")
            if (doi.isNotBlank()) sb.append("   DOI: https://doi.org/$doi\n")
            sb.append('\n')
        }
        return sb.toString().trim()
    }

    private fun buildAuthors(it: JSONObject): String {
        val arr = it.optJSONArray("author") ?: return ""
        val list = mutableListOf<String>()
        for (i in 0 until arr.length().coerceAtMost(5)) {
            val a = arr.optJSONObject(i) ?: continue
            val name = "${a.optString("given", "")} ${a.optString("family", "")}".trim()
            if (name.isNotEmpty()) list.add(name)
        }
        return list.joinToString(", ")
    }
}
