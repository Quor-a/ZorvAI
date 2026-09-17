package com.ai.assistance.quro.core.tools

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 多引擎联网搜索聚合（统一入口）。
 * - duckduckgo：默认，免费无需密钥，走 HTML 结果解析。
 * - tavily：需先在 auth_service 配置名为 tavily 的 API Key（type=apikey）。
 */
class QuroSearchProviderTool : QuroTool {
    override val name = "search_provider"
    override val description = "多引擎联网搜索聚合（统一入口）：支持 duckduckgo（默认，免费无需密钥）、tavily（需先在 auth_service 配置名为 tavily 的 API Key，type=apikey）。" +
        "参数 {\"provider\":\"duckduckgo|tavily\",\"query\":\"搜索词（必填）\",\"max_results\":10（1-20）}。" +
        "返回结构化结果（标题/链接/摘要）。duckduckgo 走 HTML 结果解析；tavily 走官方 API。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "provider":{"type":"string","description":"搜索引擎：duckduckgo（默认）| tavily（需密钥）"},
            "query":{"type":"string","description":"搜索关键词（必填）"},
            "max_results":{"type":"integer","description":"返回条数，默认 10，最大 20"}
        },
        "required":["query"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val jo = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
        val query = jo.optString("query", "").trim()
        if (query.isEmpty()) return "❌ 缺少 query 参数"
        val provider = jo.optString("provider", "duckduckgo").lowercase()
        val maxResults = jo.optInt("max_results", 10).coerceIn(1, 20)
        return runBlocking(Dispatchers.IO) {
            try {
                when (provider) {
                    "tavily" -> searchTavily(context, query, maxResults)
                    "google_search", "google" -> searchGoogle(query, maxResults)
                    "zhipu_search", "zhipu" -> searchZhipu(context, query, maxResults)
                    "various_search", "various" -> searchVarious(context, query, maxResults)
                    else -> searchDuckDuckGo(query, maxResults)
                }
            } catch (e: Exception) {
                "❌ 搜索失败：${e.message}"
            }
        }
    }

    private fun searchDuckDuckGo(query: String, maxResults: Int): String {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "https://html.duckduckgo.com/html/?q=$q"
        val html = fetch(url, mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36",
            "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8"
        )) ?: return "❌ 无法访问 DuckDuckGo（可能返回验证码或网络受限）"
        val results = mutableListOf<Pair<String, String>>()
        val linkRegex = Regex("""class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        linkRegex.findAll(html).take(maxResults).forEach { m ->
            val href = m.groupValues[1]
            val rawTitle = m.groupValues[2]
            results.add(decodeDdgUrl(href) to stripTags(rawTitle).trim())
        }
        if (results.isEmpty()) return "未找到结果（DuckDuckGo 可能返回了验证码或空页）。"
        val sb = StringBuilder("🔍 DuckDuckGo 搜索结果（${results.size}）：\n")
        results.forEachIndexed { i, (link, title) ->
            sb.append("${i + 1}. $title\n   $link\n")
        }
        return sb.toString().trim()
    }

    private fun decodeDdgUrl(href: String): String {
        val idx = href.indexOf("uddg=")
        if (idx < 0) return href
        val start = idx + 5
        val end = href.indexOf('&', start).let { if (it < 0) href.length else it }
        return runCatching { java.net.URLDecoder.decode(href.substring(start, end), "UTF-8") }.getOrElse { href }
    }

    private fun searchTavily(context: Context, query: String, maxResults: Int): String {
        val svc = QuroAuthStore.get(context, "tavily")
        if (svc == null || svc.token.isBlank()) {
            return "❌ 未配置 tavily 密钥。请先用 auth_service_add 添加：name=tavily, type=apikey, token=<你的Tavily API Key>。"
        }
        val conn = (URL("https://api.tavily.com/search").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${svc.token}")
            connectTimeout = 15000
            readTimeout = 25000
            doOutput = true
        }
        val body = JSONObject().apply {
            put("query", query)
            put("max_results", maxResults)
            put("search_depth", "basic")
        }.toString()
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
        conn.disconnect()
        if (code !in 200..299) return "❌ Tavily 请求失败 HTTP $code: ${text.take(300)}"
        val root = JSONObject(text)
        val arr = root.optJSONArray("results") ?: return "未找到结果。"
        val sb = StringBuilder("🔍 Tavily 搜索结果（${arr.length()}）：\n")
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            sb.append("${i + 1}. ${r.optString("title")}\n   ${r.optString("url")}\n")
            val content = r.optString("content")
            if (content.isNotBlank()) sb.append("   ${content.take(200)}\n")
        }
        return sb.toString().trim()
    }

    private fun searchGoogle(query: String, maxResults: Int): String {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "https://www.google.com/search?q=$q&num=${maxResults.coerceAtMost(20)}"
        val html = fetch(url, mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36",
            "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8"
        )) ?: return "❌ 无法访问 Google（可能网络受限或被拦截）。"
        val linkRegex = Regex("""/url\?q=([^"&]+)""")
        val titleRegex = Regex("""<h3[^>]*>(.*?)</h3>""", RegexOption.DOT_MATCHES_ALL)
        val links = linkRegex.findAll(html).map { m ->
            runCatching { java.net.URLDecoder.decode(m.groupValues[1], "UTF-8") }.getOrElse { m.groupValues[1] }
        }.filter { it.startsWith("http") }.distinct().take(maxResults).toList()
        val titles = titleRegex.findAll(html).map { stripTags(m.groupValues[1]).trim() }.filter { it.isNotEmpty() }.toList()
        if (links.isEmpty()) return "未找到结果（Google 可能返回了验证码或空页）。"
        val sb = StringBuilder("🔍 Google 搜索结果（${links.size}）：\n")
        links.forEachIndexed { i, link ->
            val title = titles.getOrNull(i) ?: link
            sb.append("${i + 1}. $title\n   $link\n")
        }
        return sb.toString().trim()
    }

    private fun searchZhipu(context: Context, query: String, maxResults: Int): String {
        val svc = QuroAuthStore.get(context, "zhipu")
        if (svc == null || svc.token.isBlank()) {
            return "❌ 未配置 zhipu 密钥。请先用 auth_service_add 添加：name=zhipu, type=apikey, token=<你的智谱 API Key>。"
        }
        val conn = (URL("https://open.bigmodel.cn/api/paas/v4/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${svc.token}")
            connectTimeout = 15000
            readTimeout = 25000
            doOutput = true
        }
        val body = JSONObject().apply {
            put("model", "glm-4-plus")
            put("stream", false)
            put("messages", org.json.JSONArray().apply {
                put(JSONObject().apply { put("role", "user"); put("content", query) })
            })
            put("tools", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "web_search")
                    put("web_search", JSONObject().apply { put("search_result", true); put("search_query", query) })
                })
            })
        }.toString()
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
        conn.disconnect()
        if (code !in 200..299) return "❌ 智谱请求失败 HTTP $code: ${text.take(300)}"
        val root = JSONObject(text)
        val msg = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: return "未找到结果（智谱未返回 choices）。"
        val sb = StringBuilder()
        val content = msg.optString("content")
        if (content.isNotBlank()) sb.append("📝 智谱综合回答：\n${content.take(600)}\n\n")
        val wsArray = msg.optJSONArray("web_search")
        var count = 0
        if (wsArray != null) {
            for (i in 0 until wsArray.length()) {
                val sr = wsArray.optJSONObject(i)?.optJSONArray("search_result") ?: continue
                for (j in 0 until sr.length()) {
                    if (count >= maxResults) break
                    val r = sr.optJSONObject(j) ?: continue
                    val link = r.optString("link")
                    if (link.isBlank()) continue
                    count++
                    sb.append("$count. ${r.optString("title")}\n   $link\n")
                    val c = r.optString("content")
                    if (c.isNotBlank()) sb.append("   ${c.take(200)}\n")
                }
            }
        }
        return if (sb.isBlank()) "未找到结果（智谱未返回搜索条目）。" else sb.toString().trim()
    }

    private fun searchVarious(context: Context, query: String, maxResults: Int): String {
        val per = (maxResults / 2).coerceAtLeast(3)
        val merged = linkedMapOf<String, String>()
        runCatching { searchDuckDuckGo(query, per) }.getOrNull()?.let { parseSimple(it) }?.forEach { (link, title) ->
            merged.putIfAbsent(link, title)
        }
        runCatching { searchGoogle(query, per) }.getOrNull()?.let { parseSimple(it) }?.forEach { (link, title) ->
            merged.putIfAbsent(link, title)
        }
        if (merged.isEmpty()) return "聚合搜索未找到结果。"
        val sb = StringBuilder("🔍 聚合搜索结果（duckduckgo + google，去重 ${merged.size}）：\n")
        merged.keys.take(maxResults).forEachIndexed { i, link ->
            sb.append("${i + 1}. ${merged[link]}\n   $link\n")
        }
        return sb.toString().trim()
    }

    /** 从搜索结果文本里粗提取「标题\n   链接」对（链接以 http 开头）。 */
    private fun parseSimple(text: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val l = lines[i].trim()
            if (l.startsWith("http")) {
                val prev = lines.getOrNull(i - 1)?.trim() ?: l
                val title = if (prev.startsWith("http")) l else prev.replace(Regex("^\\d+\\.\\s*"), "")
                out.add(l to title)
            }
            i++
        }
        return out
    }

    private fun fetch(url: String, headers: Map<String, String>): String? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 20000
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        val code = conn.responseCode
        val out = if (code in 200..299) conn.inputStream?.bufferedReader()?.readText() else null
        conn.disconnect()
        out
    }.getOrNull()

    private fun stripTags(s: String): String =
        s.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'").trim()
}
