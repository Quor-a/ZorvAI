package com.ai.assistance.quro.genui.app.websearch.net

import com.ai.assistance.quro.genui.app.websearch.model.SearchHit
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 国内引擎 —— ZorvAI 管线的中国网络适配层。
 *
 * 为什么要有这个文件：ZorvAI 的默认引擎组合（Bing RSS / Google News / DDG）为海外网络设计，
 * 在中国大陆：Google News 与 DDG 不可达（熔断但浪费首次超时），Bing RSS 时有时无。
 * 而 GenUI 旧版 WebSearch 在国内验证过的是百度/搜狗/Bing中国站的 HTML 抓取。
 *
 * 这里把三者适配成管线统一的 [SearchEngine] 接口，接入 EngineRouter：
 * 国内网络 → 百度/搜狗/Bing cn 兜底；海外网络 → 原有引擎照常。多源并发互不拖累。
 *
 * 解析正则忠实移植自 GenUI 旧版 WebSearch.kt（线上验证过的抽取规则）。
 */
object ChinaText {
    /** HTML → 纯文本：去标签 + 实体解码 + 压空白 */
    fun clean(html: String): String {
        val noTag = html.replace(Regex("(?s)<[^>]*>"), " ")
        val decoded = noTag
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&ldquo;", "“").replace("&rdquo;", "”").replace("&middot;", "·")
        return decoded.replace(Regex("\\s+"), " ").trim()
    }
}


/** 搜狗：纯中文场景补充 */
class SogouEngine(override val enabled: Boolean = true) : SearchEngine {
    override val id = "sogou"

    private val VR_RE = Regex("""(?s)<div class="vrwrap"[^>]*>(.*?)(?=<div class="vrwrap"|<div class="page"|</body>)""")
    private val HREF_RE = Regex("""<a[^>]*href="([^"]+)"""")
    private val TITLE_RE = Regex("""(?s)<h3[^>]*>.*?<a[^>]*>(.*?)</a>""")
    private val SNIP_RE = Regex("""(?s)<div class="(?:fz-mid|str-text-info|space-txt|text-layout)"[^>]*>(.*?)</div>""")

    override suspend fun search(query: String, limit: Int): List<SearchHit> {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "https://www.sogou.com/web?query=$q"
        val html = HttpStack.request(url, timeoutMs = 9_000).takeIf { it.ok }?.body
            ?: return emptyList()
        val out = ArrayList<SearchHit>()
        for ((i, blk) in VR_RE.findAll(html).withIndex()) {
            if (out.size >= limit) break
            val v = blk.value
            val raw = HREF_RE.find(v)?.groupValues?.get(1) ?: continue
            val url = when {
                raw.startsWith("http") -> raw
                raw.startsWith("/") -> "https://www.sogou.com$raw"
                else -> continue
            }
            val title = ChinaText.clean(TITLE_RE.find(v)?.groupValues?.get(1) ?: continue)
            if (title.isBlank()) continue
            val snippet = ChinaText.clean(SNIP_RE.find(v)?.groupValues?.get(1) ?: "").take(240)
            out.add(SearchHit(title, url, snippet, id, position = out.size + 1))
        }
        return out
    }
}

/**
 * Bing 中国站 HTML 版（cn.bing.com）——BingEngine 的 RSS 通道在国内时灵时不灵，
 * 这里提供 HTML 抽取作为同一引擎族内的第二通道（独立熔断计数，互不拖累）。
 */
class BingCnHtmlEngine(override val enabled: Boolean = true) : SearchEngine {
    override val id = "bing-cn"

    private val H3_RE = Regex("""(?s)<li class="b_algo"[^>]*>.*?</li>""")
    private val HREF_RE = Regex("""<h2[^>]*><a[^>]*href="(https?://[^"]+)".*?>(.*?)</a>""")
    private val SNIP_RE = Regex("""(?s)<p[^>]*>(.*?)</p>""")

    override suspend fun search(query: String, limit: Int): List<SearchHit> {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "https://cn.bing.com/search?q=$q&setlang=zh-hans&count=${limit.coerceIn(5, 15)}"
        val html = HttpStack.request(url, timeoutMs = 9_000).takeIf { it.ok }?.body
            ?: return emptyList()
        val out = ArrayList<SearchHit>()
        for ((i, blk) in H3_RE.findAll(html).withIndex()) {
            if (out.size >= limit) break
            val v = blk.value
            val m = HREF_RE.find(v) ?: continue
            val url = m.groupValues[1]
            val title = ChinaText.clean(m.groupValues[2])
            if (title.isBlank() || !url.startsWith("http")) continue
            // 跳转链接还原（bing 的链接有时是 bing.com/ck/a 形式，u= 参数 base64 藏真址）
            val real = Regex("[?&]u=a1([^&]+)").find(url)?.let {
                runCatching {
                    val b64 = it.groupValues[1].replace('-', '+').replace('_', '/')
                    String(android.util.Base64.decode(b64, android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING))
                }.getOrNull()
            } ?: url
            if (!real.startsWith("http")) continue
            val snippet = ChinaText.clean(SNIP_RE.find(v)?.groupValues?.get(1) ?: "").take(240)
            out.add(SearchHit(title, real, snippet, id, position = out.size + 1))
        }
        return out
    }
}
