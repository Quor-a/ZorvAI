package com.ai.assistance.quro.genui.app.agent.tools

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * AiBrowser —— ZorvAI 对话框同款联网搜索/研究能力（原版移植）。
 *
 * 与 WebSearchOrchestrator 管线的分工：管线重（改写→并发→精读→重排，10-20s）但产出
 * 结构化引用；本工具轻（3-8s）且稳（多引擎顺序回退 + 专属/通用双解析 + 18s 总预算），
 * 是 TA 实机验证「完整可用」的那条路径。
 *
 * 多引擎回退：DuckDuckGo Lite → Bing → Sogou → Baidu，任一引擎可用即返回，
 * 彻底解决单点依赖 html.duckduckgo.com 失效导致「联网失败」的问题（ZorvAI 原版注释原话）。
 */
object AiBrowser {

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    /** 搜索：返回人类可读的编号结果文本；全程 18s 总预算防阻塞。 */
    fun search(query: String, limit: Int = 5): String {
        return when (val out = parseResults(query)) {
            is SearchOutcome.Results -> {
                val results = out.list
                if (results.isEmpty()) return "未从搜索引擎解析到结果（可能触发了人机验证，请稍后重试或更换关键词）。"
                buildString {
                    append("联网搜索「$query」命中 ${results.size} 条：\n")
                    results.take(limit).forEachIndexed { i, (t, u) -> append("${i + 1}. $t\n   $u\n") }
                }
            }
            SearchOutcome.Unparseable ->
                "联网搜索失败：搜索引擎返回了页面但未能解析出结果（可能触发了人机验证或页面结构变动）。可稍后重试或更换关键词。"
            SearchOutcome.NoConnection ->
                "联网搜索抓取失败（App 无法直连搜索引擎，请检查设备网络/VPN/私人DNS 设置后重试）。"
        }
    }

    /** 自动研究：搜索 → 抓前 depth 篇正文（22s 预算）→ 合并带出处简报，一次调用完成研究。 */
    fun automate(query: String, depth: Int = 4): String {
        val out = parseResults(query)
        if (out !is SearchOutcome.Results) return "自动化研究失败：搜索引擎暂不可用，请稍后重试。"
        val results = out.list
        if (results.isEmpty()) return "自动化研究失败：未解析到搜索结果。"
        val top = results.take(depth)
        val sections = mutableListOf<String>()
        val startMs = System.currentTimeMillis()
        val budgetMs = 22_000
        top.forEachIndexed { i, (title, url) ->
            if (System.currentTimeMillis() - startMs > budgetMs) {
                sections.add("【来源 ${i + 1}】$title\n$url\n\n（因总耗时预算已到，未继续抓取后续页面）")
                return@forEachIndexed
            }
            val text = runCatching { readPage(url) }.getOrNull().orEmpty()
            val body = if (text.isBlank()) "（该页面未能抓取正文）" else text.take(2200)
            sections.add("【来源 ${i + 1}】$title\n$url\n\n$body")
        }
        return buildString {
            append("自动化研究简报：「$query」\n")
            append("已检索 ${results.size} 条结果，已抓取其中 ${top.size} 条正文并合并如下：\n\n")
            sections.forEach { append(it); append("\n\n---\n\n") }
        }
    }

    /** 抓取网页正文（article/main 优先），上限 8000 字。 */
    fun readPage(url: String): String {
        val html = fetch(url) ?: return "抓取失败：无法获取网页 $url"
        val text = htmlToText(html)
        return if (text.isBlank()) "网页未解析到正文：$url" else text.take(8000)
    }

    // ---------------- 多引擎搜索 ----------------

    private sealed class SearchOutcome {
        data class Results(val list: List<Pair<String, String>>) : SearchOutcome()
        object Unparseable : SearchOutcome()
        object NoConnection : SearchOutcome()
    }

    private fun parseResults(query: String): SearchOutcome {
        val enc = URLEncoder.encode(query, "UTF-8")
        val engines = listOf(
            "https://lite.duckduckgo.com/lite/?q=$enc" to ::parseDdgLite,
            "https://www.bing.com/search?q=$enc" to ::parseBing,
            "https://www.sogou.com/web?query=$enc" to ::parseSogou,
            "https://www.baidu.com/s?wd=$enc" to ::parseBaidu,
        )
        var anyConnected = false
        val startMs = System.currentTimeMillis()
        for ((url, parser) in engines) {
            // 总超时保护：超 18s 立即终止，避免阻塞 Agent 循环
            if (System.currentTimeMillis() - startMs > 18_000) break
            val html = fetch(url) ?: continue
            anyConnected = true
            val specific = runCatching { parser(html) }.getOrNull().orEmpty()
            if (specific.isNotEmpty()) return SearchOutcome.Results(specific)
            val generic = runCatching { parseGeneric(html) }.getOrNull().orEmpty()
            if (generic.isNotEmpty()) return SearchOutcome.Results(generic)
        }
        return if (anyConnected) SearchOutcome.Unparseable else SearchOutcome.NoConnection
    }

    /** 通用抽取：引擎改版兜底——所有外链+合理长度标题，过滤导航/自身链接，前 20 条。 */
    private fun parseGeneric(html: String): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val seen = mutableSetOf<String>()
        val regex = """<a\s+[^>]*href="(https?://[^"]+)"[^>]*>(.*?)</a>"""
            .toRegex(setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        regex.findAll(html).forEach { m ->
            var url = m.groupValues[1]
            val title = stripTags(m.groupValues[2]).take(120)
            if (title.length < 12 || title.length > 110) return@forEach
            if (url.contains("/search?") || url.contains("/preferences") || url.contains("/account")
                || url.contains("javascript:") || url.contains("mailto:")) return@forEach
            if (url.contains("uddg=")) url = resolveDdgUrl(url) ?: return@forEach
            if (url.startsWith("http") && seen.add(url)) results.add(title to url)
        }
        return results.take(20)
    }

    private fun parseDdgLite(html: String): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val regex = """<a[^>]*class="(?:result-link|result__a)"[^>]*href="([^"]+)"[^>]*>(.*?)</a>"""
            .toRegex(setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        regex.findAll(html).forEach { m ->
            val realUrl = resolveDdgUrl(m.groupValues[1]) ?: return@forEach
            val title = stripTags(m.groupValues[2]).take(120)
            if (title.isNotBlank()) results.add(title to realUrl)
        }
        return results
    }

    private fun parseBing(html: String): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val regex = """<li[^>]*class="b_algo"[^>]*>.*?<h2>\s*<a[^>]*href="([^"]+)"[^>]*>(.*?)</a>"""
            .toRegex(setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        regex.findAll(html).forEach { m ->
            val url = m.groupValues[1]
            val title = stripTags(m.groupValues[2]).take(120)
            if (url.startsWith("http") && title.isNotBlank()) results.add(title to url)
        }
        return results
    }

    private fun parseSogou(html: String): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val regex = """<h3[^>]*class="[^"]*vr-title[^"]*"[^>]*>\s*<a[^>]*href="([^"]+)"[^>]*>(.*?)</a>"""
            .toRegex(setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        regex.findAll(html).forEach { m ->
            val url = m.groupValues[1]
            val title = stripTags(m.groupValues[2]).take(120)
            if (url.startsWith("http") && title.isNotBlank()) results.add(title to url)
        }
        return results
    }

    private fun parseBaidu(html: String): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val regex = """<h3[^>]*class="[^"]*t[^"]*"[^>]*>\s*<a[^>]*href="([^"]+)"[^>]*>(.*?)</a>"""
            .toRegex(setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        regex.findAll(html).forEach { m ->
            val url = m.groupValues[1]
            val title = stripTags(m.groupValues[2]).take(120)
            if (url.startsWith("http") && title.isNotBlank()) results.add(title to url)
        }
        return results
    }

    private fun resolveDdgUrl(raw: String): String? {
        if (raw.startsWith("http")) return raw
        val uddg = """uddg=([^&]+)""".toRegex().find(raw)?.groupValues?.get(1)
        return if (uddg != null) runCatching { URLDecoder.decode(uddg, "UTF-8") }.getOrNull() else raw
    }

    private fun fetch(url: String): String? {
        return try {
            val req = Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string()?.takeIf { it.isNotBlank() } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun stripTags(s: String): String =
        s.replace(Regex("(?i)<[^>]+>"), "").replace(Regex("&[a-z]+;"), " ")
            .replace(Regex("\\s+"), " ").trim()

    private fun htmlToText(html: String): String {
        val main = Regex("(?i)<(article|main)[^>]*>.*?</\\1>", setOf(RegexOption.DOT_MATCHES_ALL)).find(html)?.value
        var s = main ?: html
        s = s.replace(Regex("(?i)<script[^>]*>.*?</script>", setOf(RegexOption.DOT_MATCHES_ALL)), " ")
        s = s.replace(Regex("(?i)<style[^>]*>.*?</style>", setOf(RegexOption.DOT_MATCHES_ALL)), " ")
        s = s.replace(Regex("(?i)<head[^>]*>.*?</head>", setOf(RegexOption.DOT_MATCHES_ALL)), " ")
        s = s.replace(Regex("(?i)<nav[^>]*>.*?</nav>", setOf(RegexOption.DOT_MATCHES_ALL)), " ")
        s = s.replace(Regex("(?i)<footer[^>]*>.*?</footer>", setOf(RegexOption.DOT_MATCHES_ALL)), " ")
        s = s.replace(Regex("(?i)<[^>]+>"), " ")
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        return s.replace(Regex("\\s+\\n"), "\n").replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n").trim()
    }
}
