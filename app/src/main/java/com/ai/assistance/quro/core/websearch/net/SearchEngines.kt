package com.ai.assistance.quro.core.websearch.net

import com.ai.assistance.quro.core.websearch.html.MiniHtml
import com.ai.assistance.quro.core.websearch.html.MiniHtml.textOf
import com.ai.assistance.quro.core.websearch.model.SearchHit
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 检索层：多引擎并发 + 结果合并去重。
 *
 * 为什么必须多引擎：
 * 单源必然失效——改版、验证码、地区封锁、运营商污染，任何一条都会让联网能力整体不可用。
 * 多引擎并发的价值不只是"备份"，更在于投票：被多个引擎同时命中的结果，可信度显著更高，
 * 这个信号会直接进入重排打分（见 ResultReranker）。
 *
 * 引擎选择原则：优先 RSS / JSON 这类天然结构化输出，把 HTML 解析留给兜底路径。
 */
interface SearchEngine {
    val id: String
    /** 是否启用（用户可关闭国内不可达的引擎） */
    val enabled: Boolean
    suspend fun search(query: String, limit: Int): List<SearchHit>
}

// ---------------------------------------------------------------- 各引擎实现

/** DuckDuckGo HTML 端点：无需 key，返回结构稳定的结果页 */
class DdgHtmlEngine(override val enabled: Boolean = true) : SearchEngine {
    override val id = "ddg"
    override suspend fun search(query: String, limit: Int): List<SearchHit> {
        val html = HttpStack.postForm(
            "https://html.duckduckgo.com/html/",
            mapOf("q" to query, "kl" to "cn-zh")
        ) ?: return emptyList()
        val root = MiniHtml.parse(html)
        val out = ArrayList<SearchHit>()
        var pos = 0
        MiniHtml.walk(root) { n ->
            if (n.tag == "a" && n.attr("class").contains("result__a")) {
                val raw = n.attr("href")
                val url = decodeDdg(raw)
                val title = textOf(n).trim()
                if (url.startsWith("http") && title.isNotBlank()) {
                    out.add(SearchHit(title, url, "", id, ++pos))
                }
            }
            if (n.tag == "a" && n.attr("class").contains("result__snippet") && out.isNotEmpty()) {
                val s = textOf(n).trim()
                if (s.isNotBlank() && out.last().snippet.isEmpty()) {
                    out[out.lastIndex] = out.last().copy(snippet = s)
                }
            }
        }
        return out.take(limit)
    }

    /** DDG 的 href 常为 /l/?uddg=<encoded>，需还原真实地址 */
    private fun decodeDdg(raw: String): String {
        if (raw.startsWith("http")) return raw
        val i = raw.indexOf("uddg=")
        if (i >= 0) {
            val enc = raw.substring(i + 5).substringBefore('&')
            return runCatching { URLDecoder.decode(enc, "UTF-8") }.getOrDefault(raw)
        }
        return raw
    }
}

/**
 * Bing RSS —— 本方案的"主力源"。
 * Bing 搜索结果支持 &format=rss 直接输出 XML，天然结构化：无需解析 HTML、无 JS 依赖、
 * 字段稳定（title/link/description/pubDate），是自建联网能力里性价比最高的入口。
 */
class BingRssEngine(override val enabled: Boolean = true) : SearchEngine {
    override val id = "bing"
    override suspend fun search(query: String, limit: Int): List<SearchHit> {
        val url = "https://www.bing.com/search?q=" +
            URLEncoder.encode(query, "UTF-8") + "&format=rss&count=$limit"
        val xml = HttpStack.get(url) ?: return emptyList()
        return parseRss(xml, id, limit)
    }
}

/** Google News RSS：时效性查询（新闻、公告、行情）的最佳源 */
class GoogleNewsRssEngine(
    private val lang: String = "zh-CN",
    override val enabled: Boolean = true
) : SearchEngine {
    override val id = "gnews"
    override suspend fun search(query: String, limit: Int): List<SearchHit> {
        val url = "https://news.google.com/rss/search?q=" +
            URLEncoder.encode(query, "UTF-8") + "&hl=$lang&gl=CN&ceid=CN:zh-Hans"
        val xml = HttpStack.get(url) ?: return emptyList()
        return parseRss(xml, id, limit)
    }
}

/**
 * 自建 SearXNG：完全自主可控的元搜索，输出 JSON，无配额、无 key、无封锁。
 * 需要在设置里填写实例地址，例如 https://your-searx.example.com
 */
class SearXngEngine(
    private val baseUrl: String,
    override val enabled: Boolean = true
) : SearchEngine {
    override val id = "searxng"
    override suspend fun search(query: String, limit: Int): List<SearchHit> {
        if (baseUrl.isBlank()) return emptyList()
        val url = baseUrl.trimEnd('/') + "/search?q=" +
            URLEncoder.encode(query, "UTF-8") + "&format=json&language=zh-CN"
        val json = HttpStack.get(url) ?: return emptyList()
        return parseSearXng(json, limit)
    }
}

// ---------------------------------------------------------------- 解析工具

/**
 * RSS/XML 解析：复用 MiniHtml（标签语言通用），抽取 item 三元组。
 *
 * 注意必须传 xmlMode = true —— RSS 的 <link> 是成对标签，
 * 沿用 HTML 空元素规则会导致 URL 取不到，全部条目被判为无效。
 */
internal fun parseRss(xml: String, engine: String, limit: Int): List<SearchHit> {
    val root = MiniHtml.parse(xml, xmlMode = true)
    val out = ArrayList<SearchHit>()
    var pos = 0
    MiniHtml.walk(root) { n ->
        if (n.tag != "item") return@walk
        var title = ""
        var link = ""
        var desc = ""
        var pub = -1L
        for (c in n.children) {
            val t = textOf(c).trim()
            when (c.tag) {
                "title" -> title = t
                "link" -> link = t
                "description" -> desc = MiniHtml.unescape(t)
                "pubdate" -> pub = parseRfc822(t)
            }
        }
        if (link.startsWith("http") && title.isNotBlank()) {
            out.add(SearchHit(title, link, desc, engine, ++pos, pub))
        }
    }
    return out.take(limit)
}

/** SearXNG JSON 解析：只用 org.json，避免引入序列化框架 */
internal fun parseSearXng(json: String, limit: Int): List<SearchHit> {
    return runCatching {
        val arr = org.json.JSONObject(json).optJSONArray("results") ?: return emptyList()
        val out = ArrayList<SearchHit>()
        for (i in 0 until minOf(arr.length(), limit)) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url")
            val title = o.optString("title")
            if (url.isBlank() || title.isBlank()) continue
            out.add(
                SearchHit(
                    title = title,
                    url = url,
                    snippet = o.optString("content"),
                    engine = "searxng",
                    position = i + 1,
                    publishedAt = o.optLong("publishedDate", -1L)
                )
            )
        }
        out
    }.getOrDefault(emptyList())
}

private val MONTHS = mapOf(
    "jan" to 0, "feb" to 1, "mar" to 2, "apr" to 3, "may" to 4, "jun" to 5,
    "jul" to 6, "aug" to 7, "sep" to 8, "oct" to 9, "nov" to 10, "dec" to 11
)

/**
 * 日期解析，够用即可，失败返回 -1 不影响主流程。
 *
 * 需同时兼容两种格式：
 * - 英文：Mon, 07 Sep 2026 02:31:00 GMT
 * - 中文（Bing 中文 RSS 实际返回）：周一, 07 9月 2026 02:31:00 GMT
 * 中文场景若按英文月份表匹配会全部失败，导致新鲜度信号失效。
 */
private val DATE_RE = Regex("""(\d{1,2})\s+([A-Za-z]{3,9}|\d{1,2})\s*月?\s+(\d{4})""")
private val TIME_RE = Regex("""(\d{1,2}):(\d{2})(?::(\d{2}))?""")

internal fun parseRfc822(s: String): Long {
    return runCatching {
        val d = DATE_RE.find(s) ?: return -1L
        val day = d.groupValues[1].toInt()
        val monRaw = d.groupValues[2]
        val mon = if (monRaw.all { it.isDigit() }) {
            monRaw.toInt() - 1
        } else {
            MONTHS[monRaw.lowercase().take(3)] ?: return -1L
        }
        val year = d.groupValues[3].toInt()
        val t = TIME_RE.find(s)
        val hour = t?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val min = t?.groupValues?.get(2)?.toIntOrNull() ?: 0
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("GMT"))
        cal.set(year, mon, day, hour, min, 0)
        cal.timeInMillis
    }.getOrDefault(-1L)
}

// ---------------------------------------------------------------- 路由

/**
 * EngineRouter —— 并发发起所有可用引擎，合并去重并统计投票。
 * 任一引擎失败不影响整体；全部失败才向上抛空。
 */
object EngineRouter {

    /**
     * 并发检索并合并。
     *
     * @param useHealth 是否启用熔断。默认开启：不可达源会在连续失败后被跳过，
     *                  避免每次请求都被它们的超时拖慢（这在受限网络下是主要延迟来源）。
     *                  自检时请传 false，以便拿到所有引擎的真实状态。
     */
    suspend fun search(
        engines: List<SearchEngine>,
        query: String,
        limit: Int = 8,
        timeoutMs: Long = 9_000,
        useHealth: Boolean = true
    ): List<SearchHit> = coroutineScope {
        val active = engines.filter { e ->
            e.enabled && !(useHealth && EngineHealth.shouldSkip(e.id))
        }

        val jobs = active.map { e ->
            async {
                val tmo = if (useHealth) EngineHealth.timeoutFor(e.id) else timeoutMs
                val res = withTimeoutOrNull(tmo) {
                    runCatching { e.search(query, limit) }.getOrDefault(emptyList())
                }
                if (res.isNullOrEmpty()) EngineHealth.recordFailure(e.id)
                else EngineHealth.recordSuccess(e.id)
                res ?: emptyList()
            }
        }

        val merged = LinkedHashMap<String, SearchHit>()
        for (j in jobs) {
            for (hit in j.await()) {
                val key = normalizeUrl(hit.url)
                val exist = merged[key]
                if (exist == null) {
                    merged[key] = hit
                } else {
                    // 多引擎命中 → 投票 +1；用更完整的信息补全
                    merged[key] = exist.copy(
                        votes = exist.votes + 1,
                        snippet = exist.snippet.ifBlank { hit.snippet },
                        publishedAt = if (exist.publishedAt > 0) exist.publishedAt else hit.publishedAt
                    )
                }
            }
        }
        merged.values.toList()
    }

    /** URL 归一化：去协议、去 www、去锚点、去常见追踪参数 */
    fun normalizeUrl(u: String): String {
        var s = u.trim().substringBefore('#')
        s = s.removePrefix("https://").removePrefix("http://")
        s = s.removePrefix("www.")
        val q = s.indexOf('?')
        if (q >= 0) {
            val query = s.substring(q + 1)
                .split('&')
                .filter { p ->
                    val k = p.substringBefore('=')
                    k !in TRACKING
                }
                .joinToString("&")
            s = s.substring(0, q) + if (query.isBlank()) "" else "?$query"
        }
        return s.trimEnd('/')
    }

    private val TRACKING = setOf(
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
        "spm", "from", "ref", "referrer", "share_token", "sid", "gclid", "fbclid"
    )
}
