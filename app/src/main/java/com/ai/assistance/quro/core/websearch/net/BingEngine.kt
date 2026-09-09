package com.ai.assistance.quro.core.websearch.net

import com.ai.assistance.quro.core.websearch.html.MiniHtml
import com.ai.assistance.quro.core.websearch.html.MiniHtml.textOf
import com.ai.assistance.quro.core.websearch.model.SearchHit
import java.net.URLEncoder

/**
 * BingEngine —— 双通道引擎，本方案的主力源。
 *
 * 为什么必须双通道：
 * 单押 RSS 是有风险的。实测中 format=rss 与普通搜索页是两套不同的后端路径，
 * 在部分网络环境/运营商下会出现"普通搜索页 200 正常、RSS 端点被拦截"的情况。
 * 因此这里采用 RSS 优先、HTML 兜底的两级策略：
 *   1. RSS：结构化、零解析噪声、带 pubDate，是首选；
 *   2. HTML：只要普通搜索页能打开就一定能用，虽然要解析 DOM，但可用性最高。
 *
 * HTML 解析刻意不依赖 b_algo 这类易变的 class 名，
 * 而是用"含 h2/h3 子节点的 <a> 标签"这一结构特征——只要 Bing 还把标题放在标题标签里就不会失效。
 */
class BingEngine(
    private val market: String = "zh-CN",
    override val enabled: Boolean = true
) : SearchEngine {

    override val id = "bing"

    override suspend fun search(query: String, limit: Int): List<SearchHit> {
        val q = URLEncoder.encode(query, "UTF-8")
        val rssUrl = "https://www.bing.com/search?q=$q&format=rss&count=$limit&mkt=$market"

        // 通道一：RSS
        val rssRaw = HttpStack.request(rssUrl, timeoutMs = 8_000)
        if (rssRaw.ok && !AntiBot.isBlocked(rssRaw.body)) {
            val hits = parseRss(rssRaw.body!!, id, limit)
            if (hits.isNotEmpty()) return hits
        }

        // 通道二：HTML 兜底
        val htmlUrl = "https://www.bing.com/search?q=$q&mkt=$market&count=$limit"
        val htmlRaw = HttpStack.request(htmlUrl, forHtml = true, timeoutMs = 10_000)
        if (htmlRaw.ok && !AntiBot.isBlocked(htmlRaw.body)) {
            return parseBingHtml(htmlRaw.body!!, limit)
        }
        return emptyList()
    }

    /**
     * 解析 Bing 搜索结果页。
     * 结构特征：<a href="..."> ... <h2>标题</h2> ... </a>
     * 摘要位于后续的 .b_caption 中，取不到不影响主流程（相关度打分会降级）。
     */
    internal fun parseBingHtml(html: String, limit: Int): List<SearchHit> {
        val root = MiniHtml.parse(html)
        val out = ArrayList<SearchHit>()
        val seen = HashSet<String>()

        MiniHtml.walk(root) { n ->
            if (n.tag != "a") return@walk
            val href = n.attr("href")
            if (!href.startsWith("http")) return@walk

            val heading = findHeading(n) ?: return@walk
            val title = textOf(heading).trim().replace(Regex("""\s+"""), " ")
            if (title.length < 4) return@walk

            // 排除 Bing 自有域名与明显非结果链接
            val domain = domainOf(href)
            if (domain in SELF_DOMAINS) return@walk

            val key = EngineRouter.normalizeUrl(href)
            if (!seen.add(key)) return@walk

            val snippet = findSnippet(n)
            out.add(SearchHit(title, href, snippet, id, out.size + 1))
        }
        return out.take(limit)
    }

    /** 在 a 的子树中找第一个 h2/h3 */
    private fun findHeading(n: com.ai.assistance.quro.core.websearch.html.HNode)
        : com.ai.assistance.quro.core.websearch.html.HNode? {
        if (n.tag == "h2" || n.tag == "h3") return n
        for (c in n.children) {
            val r = findHeading(c)
            if (r != null) return r
        }
        return null
    }

    /** 在 a 的父级兄弟中找摘要段落（b_caption / b_lineclamp） */
    private fun findSnippet(a: com.ai.assistance.quro.core.websearch.html.HNode): String {
        var p = a.parent
        repeat(3) {
            if (p == null) return ""
            val s = collectCaption(p)
            if (s.isNotBlank()) return s
            p = p.parent
        }
        return ""
    }

    private fun collectCaption(n: com.ai.assistance.quro.core.websearch.html.HNode): String {
        val sb = StringBuilder()
        MiniHtml.walk(n) { x ->
            if (sb.length < 200 && (x.tag == "p" || x.tag == "span")) {
                val cls = x.attr("class")
                if (cls.contains("b_lineclamp") || cls.contains("b_caption") ||
                    cls.contains("b_snippet") || cls.contains("b_dList")
                ) {
                    val t = textOf(x).trim()
                    if (t.length > 20) sb.append(t)
                }
            }
        }
        return sb.toString()
    }

    private fun domainOf(u: String): String =
        u.removePrefix("https://").removePrefix("http://")
            .substringBefore('/').substringBefore(':').removePrefix("www.")

    private val SELF_DOMAINS = setOf(
        "bing.com", "www.bing.com", "cn.bing.com", "microsoft.com",
        "msn.com", "go.microsoft.com", "support.microsoft.com", "live.com"
    )
}
