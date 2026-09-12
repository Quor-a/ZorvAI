package com.ai.assistance.quro.core.websearch.net

import com.ai.assistance.quro.core.websearch.html.HNode
import com.ai.assistance.quro.core.websearch.html.MiniHtml
import com.ai.assistance.quro.core.websearch.html.MiniHtml.textOf
import com.ai.assistance.quro.core.websearch.model.SearchHit
import java.net.URLEncoder

/**
 * BaiduEngine —— 中文原生索引源，无需 key、默认开启。
 *
 * 解决"单点 Bing 依赖"：百度对中文 query 的召回与中文站点覆盖明显优于 Bing/DDG，
 * 是中文场景下质量最高的独立备用源。接入即受 EngineHealth 熔断保护，无额外配置。
 *
 * 解析策略：百度结果标题容器为 `<h3 class="t">` 内嵌 `<a mu="真实地址" href="...baidu.com/link?url=...">`。
 * - 优先取 `<a>` 的 `mu` 属性（真实地址）；
 * - 取不到 `mu` 时用 [HttpStack.finalUrl] 还原 `baidu.com/link?url=` 包裹链接；
 * - 两者皆无则保留原始包裹链接（仍可点击，但排序权重受限）。
 *
 * 判定"这是一条结果标题链接"的条件：`<a>` 带有 `mu` 属性，或其父级为 `<h3 class="t">`。
 * 这把导航/页脚等噪音链接挡在门外，避免误召回。
 */
class BaiduEngine(override val enabled: Boolean = true) : SearchEngine {

    override val id = "baidu"

    override suspend fun search(query: String, limit: Int): List<SearchHit> {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "https://www.baidu.com/s?wd=$q&rn=$limit&ie=utf-8"
        val raw = HttpStack.request(url, forHtml = true, timeoutMs = 10_000)
        if (!raw.ok || AntiBot.isBlocked(raw.body)) return emptyList()
        return parseBaiduHtml(raw.body!!, limit)
    }

    internal fun parseBaiduHtml(html: String, limit: Int): List<SearchHit> {
        val root = MiniHtml.parse(html)
        val out = ArrayList<SearchHit>()
        val seen = HashSet<String>()

        MiniHtml.walk(root) { n ->
            if (n.tag != "a") return@walk
            val href = n.attr("href")
            if (!href.startsWith("http")) return@walk

            // 必须是结果标题链接：带 mu 属性，或其父级是 h3.t
            val mu = n.attr("mu")
            val parentIsTitle = n.parent?.let { it.tag == "h3" && it.attr("class").contains("t") } ?: false
            if (mu.isBlank() && !parentIsTitle) return@walk

            val title = textOf(n).trim().replace(Regex("""\s+"""), " ")
            if (title.length < 4) return@walk

            val real = mu.takeIf { it.startsWith("http") }
                ?: HttpStack.finalUrl(href).takeIf { it != null && !it.contains("baidu.com/link") }
                ?: href

            val domain = domainOf(real)
            if (domain in SELF_DOMAINS) return@walk

            val key = EngineRouter.normalizeUrl(real)
            if (!seen.add(key)) return@walk

            val snippet = findSnippet(n)
            out.add(SearchHit(title, real, snippet, id, out.size + 1))
        }
        return out.take(limit)
    }

    /** 在 a 的父/祖父级中找摘要段落（c-abstract 等） */
    private fun findSnippet(a: HNode): String {
        var p = a.parent
        repeat(3) {
            if (p == null) return ""
            val s = collectCaption(p)
            if (s.isNotBlank()) return s
            p = p.parent
        }
        return ""
    }

    private fun collectCaption(n: HNode): String {
        val sb = StringBuilder()
        MiniHtml.walk(n) { x ->
            if (sb.length < 200 && x.tag == "div") {
                val cls = x.attr("class")
                if (cls.contains("c-abstract") || cls.contains("c-span-last") ||
                    cls.contains("content-right") || cls.contains("c-abstract-content")
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
        "baidu.com", "www.baidu.com", "baike.baidu.com", "tieba.baidu.com",
        "map.baidu.com", "music.baidu.com", "zhidao.baidu.com", "pan.baidu.com",
        "wenku.baidu.com", "fanyi.baidu.com"
    )
}
