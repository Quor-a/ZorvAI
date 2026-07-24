package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.QuroBrowserBridge
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.text.RegexOption

/**
 * AI 自动化浏览器 + 联网搜索（升级版「浏览器」工具）。
 * 支持在不打开前台的情况下执行：
 * - action="search"：联网搜索，返回结果标题 + 链接（后台可用，AI 直接拿到检索结果）。
 * - action="read"  ：抓取网页并抽取正文纯文本（后台可用）。
 * - action="open"  ：在应用内置浏览器中打开（前台呈现，AI 自动化浏览器界面）。
 * - action="automate"：自动研究（搜索 → 抓取前 N 个正文 → 合并简报）。
 *
 * 联网搜索采用多引擎回退（DuckDuckGo Lite → Bing → Sogou），任一引擎可用即返回结果，
 * 彻底解决旧版单点依赖 html.duckduckgo.com 失效导致「联网失败 / 自动研究失败 / 读取网页失败」的问题。
 * 复用项目已引入的 OkHttp（Square, Apache-2.0）。
 */
class AiBrowserTool : QuroTool {
    override val name = "ai_browser"
    override val description = "AI 自动化浏览器 + 联网搜索 + 文件下载：可后台联网检索/抓取网页正文/下载文件，也可打开内置浏览器。" +
        "【关键用法】研究类/资料收集类任务（如「查天气」「查某物资料」「搜索某关键词」）必须且只调用一次 action=\"automate\"，它会在【单个工具调用内】完成「联网搜索→抓取前 depth 个结果正文→合并成带出处的研究简报」并一次性返回；" +
        "严禁把研究拆成先 search 再逐个 read 的多步调用——那样会产生大量重复工具调用、严重拖慢对话并且容易卡死。" +
        "参数 {\"action\":\"automate|search|read|open|download\",\"query\":\"搜索/研究主题(automate/search 用)\",\"url\":\"目标网址(read/open/download 用)\",\"limit\":5,\"depth\":4(automate 抓取前N页,默认4)}。" +
        "automate=★推荐的研究方式(一次搞定,后台执行)；search=仅返回标题+链接(单步)；read=抓单页正文(单步)；open=应用内浏览器打开；" +
        "download=下载文件到 Download/Quro（自研，无需 apl 自动操控）。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "action":{"type":"string","description":"【研究/查资料务必用 automate：一次调用完成搜索+抓取+合并,不要分步search+read】; search=仅搜标题链接(单步) / read=抓单页正文(单步) / open=打开内置浏览器 / automate=自动研究(搜索+抓取+合并,推荐) / download=下载文件"},
            "query":{"type":"string","description":"search/automate 时的搜索词"},
            "url":{"type":"string","description":"read/open/download 时的目标网址"},
            "limit":{"type":"integer","description":"search 返回条数，默认 5"},
            "depth":{"type":"integer","description":"automate 抓取前 N 个页面的数量，默认 4"},
            "contentDisposition":{"type":"string","description":"download 时的 Content-Disposition 头（可选，用于推断文件名）"},
            "mime":{"type":"string","description":"download 时的 MIME 类型（可选）"}
        },
        "required":["action"]
    }"""
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** Chrome UA（较新版本，降低被搜索引擎当爬虫拦截的概率）。 */
    private val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    override fun run(context: Context, arguments: String): String {
        val jo = JSONObject(arguments)
        val action = jo.optString("action", "").trim().lowercase()
        return when (action) {
            "search" -> {
                val q = jo.optString("query", "").trim()
                if (q.isEmpty()) return "search 缺少 query 参数"
                webSearch(q, jo.optInt("limit", 5).coerceIn(1, 20))
            }
            "read" -> {
                val url = jo.optString("url", "").trim()
                if (url.isEmpty()) return "read 缺少 url 参数"
                readPage(url)
            }
            "open" -> {
                val url = jo.optString("url", "").trim()
                if (url.isEmpty()) return "open 缺少 url 参数"
                QuroBrowserBridge.open(url)
                "已在应用内置浏览器打开：$url"
            }
            "download" -> {
                val url = jo.optString("url", "").trim()
                if (url.isEmpty()) return "download 缺少 url 参数"
                val cd = jo.optString("contentDisposition", null).takeIf { it.isNotEmpty() }
                val mime = jo.optString("mime", null).takeIf { it.isNotEmpty() }
                val res = QuroDownloadUtil.download(context, url, null, cd, mime)
                when {
                    res.startsWith("OK:") -> "已下载并保存到 Download/Quro：${res.substring(3)}"
                    res.startsWith("FALLBACK:") -> "已保存到应用目录：${res.substring(9)}"
                    else -> res
                }
            }
            "automate" -> {
                val q = jo.optString("query", "").trim()
                if (q.isEmpty()) return "automate 缺少 query 参数"
                automateResearch(q, jo.optInt("depth", 4).coerceIn(1, 8))
            }
            else -> "未知 action: $action（支持 search / read / open / automate / download）"
        }
    }

    private fun webSearch(query: String, limit: Int): String {
        return when (val out = parseResults(query)) {
            is SearchOutcome.Results -> {
                val results = out.list
                if (results.isEmpty()) return "未从搜索引擎解析到结果（可能触发了人机验证，请稍后重试或更换关键词）。"
                buildString {
                    append("联网搜索「$query」命中 ${results.size} 条（展示前 $limit 条）：\n")
                    results.take(limit).forEachIndexed { i, (t, u) -> append("${i + 1}. $t\n   $u\n") }
                }
            }
            SearchOutcome.Unparseable ->
                "联网搜索失败：搜索引擎返回了页面但未能解析出结果（可能触发了人机验证或页面结构变动）。可稍后重试，或让我用「打开浏览器」直接查看。"
            SearchOutcome.NoConnection -> {
                val enc = URLEncoder.encode(query, "UTF-8")
                runCatching { QuroBrowserBridge.open("https://www.baidu.com/s?wd=$enc") }
                "联网搜索抓取失败（App 无法直连搜索引擎）。已在应用内置浏览器打开百度搜索页供你直接查看：$query"
            }
        }
    }

    /**
     * AI 自动化研究（v128）：联网搜索 → 抓取前 depth 个结果正文 → 合并成一份带出处的背景简报。
     * 全程后台执行，不打开任何前台界面。属于「AI 自动化浏览器」的核心能力。
     */
    private fun automateResearch(query: String, depth: Int): String {
        val out = parseResults(query)
        if (out !is SearchOutcome.Results) return "自动化研究失败：搜索引擎暂不可用（${if (out is SearchOutcome.NoConnection) "网络无法直连" else "页面结构变动/风控"}），请稍后重试。"
        val results = out.list
        if (results.isEmpty()) return "自动化研究失败：未解析到搜索结果。"
        val top = results.take(depth)
        val sections = mutableListOf<String>()
        // 🔑 总耗时预算：automate 内部会顺序抓 depth 个页面，每个最多 12s。
        // 不设上限则最坏 depth×12s 直接阻塞对话框协程。到点即停、已抓的照常合并返回。
        val startMs = System.currentTimeMillis()
        val budgetMs = 22000
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
            append("（以上为 AI 自动化浏览器后台抓取合并，未打开前台；可调用 read/open 进一步深入单页。）")
        }
    }

    /** 搜索结果判定：命中 / 连通但解析不出 / 全部直连失败。 */
    private sealed class SearchOutcome {
        data class Results(val list: List<Pair<String, String>>) : SearchOutcome()
        object Unparseable : SearchOutcome()
        object NoConnection : SearchOutcome()
    }

    /**
     * 多引擎联网搜索：依次尝试 DuckDuckGo Lite → Bing → Sogou → Baidu。
     * 每个引擎先用专属解析器；专属解析为空时兜底用通用链接抽取（应对页面结构变动）。
     * 返回 SearchOutcome：命中结果 / 连通但解析不出(风控或改版) / 全部直连失败。
     */
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
            // 🔑 总超时保护：任一时点超过 18s 立即终止尝试，避免对话框协程被同步阻塞过久（卡 UI）。
            if (System.currentTimeMillis() - startMs > 18000) break
            val html = fetch(url) ?: continue
            anyConnected = true
            val specific = runCatching { parser(html) }.getOrNull().orEmpty()
            if (specific.isNotEmpty()) return SearchOutcome.Results(specific)
            val generic = runCatching { parseGeneric(html) }.getOrNull().orEmpty()
            if (generic.isNotEmpty()) return SearchOutcome.Results(generic)
        }
        return if (anyConnected) SearchOutcome.Unparseable else SearchOutcome.NoConnection
    }

    /**
     * 通用结果抽取：当专属解析器因引擎改版失效时兜底。
     * 抽取所有 http(s) 外链 + 合理长度标题，过滤导航/引擎自身链接与重复，取前 20 条。
     */
    private fun parseGeneric(html: String): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val seen = mutableSetOf<String>()
        val regex = """<a\s+[^>]*href="(https?://[^"]+)"[^>]*>(.*?)</a>""".toRegex(setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
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

    /** DuckDuckGo Lite：结果在 <a class="result-link" href="..."> 或 <a class="result__a" href="..."> */
    private fun parseDdgLite(html: String): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val regex = """<a[^>]*class="(?:result-link|result__a)"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""".toRegex(setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        regex.findAll(html).forEach { m ->
            val rawHref = m.groupValues[1]
            val realUrl = resolveDdgUrl(rawHref)
            val title = stripTags(m.groupValues[2]).take(120)
            if (realUrl != null && title.isNotBlank()) results.add(title to realUrl)
        }
        return results
    }

    /** Bing：结果在 <li class="b_algo"><h2><a href="URL">TITLE</a></h2> */
    private fun parseBing(html: String): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val regex = """<li[^>]*class="b_algo"[^>]*>.*?<h2>\s*<a[^>]*href="([^"]+)"[^>]*>(.*?)</a>""".toRegex(setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        regex.findAll(html).forEach { m ->
            val url = m.groupValues[1]
            val title = stripTags(m.groupValues[2]).take(120)
            if (url.startsWith("http") && title.isNotBlank()) results.add(title to url)
        }
        return results
    }

    /** Sogou：结果在 <h3 class="vr-title"><a href="URL">TITLE</a></h3> */
    private fun parseSogou(html: String): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val regex = """<h3[^>]*class="[^"]*vr-title[^"]*"[^>]*>\s*<a[^>]*href="([^"]+)"[^>]*>(.*?)</a>""".toRegex(setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        regex.findAll(html).forEach { m ->
            val url = m.groupValues[1]
            val title = stripTags(m.groupValues[2]).take(120)
            if (url.startsWith("http") && title.isNotBlank()) results.add(title to url)
        }
        return results
    }

    /** Baidu：结果在 <h3 class="t"><a href="URL">TITLE</a></h3> */
    private fun parseBaidu(html: String): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val regex = """<h3[^>]*class="[^"]*t[^"]*"[^>]*>\s*<a[^>]*href="([^"]+)"[^>]*>(.*?)</a>""".toRegex(setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        regex.findAll(html).forEach { m ->
            val url = m.groupValues[1]
            val title = stripTags(m.groupValues[2]).take(120)
            if (url.startsWith("http") && title.isNotBlank()) results.add(title to url)
        }
        return results
    }

    /** 解析 DuckDuckGo 的 uddg= 重定向参数为真实地址。 */
    private fun resolveDdgUrl(raw: String): String? {
        if (raw.startsWith("http")) return raw
        val uddg = """uddg=([^&]+)""".toRegex().find(raw)?.groupValues?.get(1)
        return if (uddg != null) runCatching { URLDecoder.decode(uddg, "UTF-8") }.getOrNull() else raw
    }

    private fun readPage(url: String): String {
        val html = fetch(url) ?: return "抓取失败：无法获取网页 $url"
        val text = htmlToText(html)
        return if (text.isBlank()) "网页未解析到正文：$url" else text.take(8000)
    }

    /** 带 Chrome UA 的抓取；单次请求（超时已收紧），失败返回 null。不重试——连不上就是连不上，避免对话框长时间卡住。 */
    private fun fetch(url: String): String? {
        repeat(1) { attempt ->
            try {
                val req = Request.Builder().url(url)
                    .header("User-Agent", UA)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .build()
                val resp = client.newCall(req).execute()
                if (resp.isSuccessful) {
                    val body = resp.body?.string()
                    if (!body.isNullOrBlank()) return body
                }
                resp.body?.close()
            } catch (e: Exception) {
                if (attempt == 0) return@repeat // 重试
            }
        }
        return null
    }

    private fun stripTags(s: String): String =
        s.replace(Regex("(?i)<[^>]+>"), "").replace(Regex("&[a-z]+;"), " ").replace(Regex("\\s+"), " ").trim()

    private fun htmlToText(html: String): String {
        // 优先抽取正文容器（article/main），提升正文纯度
        val main = Regex("(?i)<(article|main)[^>]*>.*?</\\1>", setOf(RegexOption.DOT_MATCHES_ALL)).find(html)?.value
        var s = main ?: html
        s = s.replace(Regex("(?i)<script[^>]*>.*?</script>", setOf(RegexOption.DOT_MATCHES_ALL)), " ")
        s = s.replace(Regex("(?i)<style[^>]*>.*?</style>", setOf(RegexOption.DOT_MATCHES_ALL)), " ")
        s = s.replace(Regex("(?i)<head[^>]*>.*?</head>", setOf(RegexOption.DOT_MATCHES_ALL)), " ")
        s = s.replace(Regex("(?i)<nav[^>]*>.*?</nav>", setOf(RegexOption.DOT_MATCHES_ALL)), " ")
        s = s.replace(Regex("(?i)<footer[^>]*>.*?</footer>", setOf(RegexOption.DOT_MATCHES_ALL)), " ")
        s = s.replace(Regex("(?i)<[^>]+>"), " ")
        s = s.replace(Regex("&nbsp;"), " ")
        s = s.replace(Regex("&amp;"), "&")
        s = s.replace(Regex("&lt;"), "<")
        s = s.replace(Regex("&gt;"), ">")
        s = s.replace(Regex("&quot;"), "\"")
        s = s.replace(Regex("&#39;"), "'")
        return s.replace(Regex("\\s+\\n"), "\n").replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()
    }
}
