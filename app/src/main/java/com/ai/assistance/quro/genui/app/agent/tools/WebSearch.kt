package com.ai.assistance.quro.genui.app.agent.tools

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 多引擎联网搜索。
 *
 * 设计要点（为什么这么做）：
 * 1. **不依赖单一引擎**。免 key 的 HTML 版搜索页随时可能改版或限流，单引擎必然出现
 *    "偶尔搜索失败"。这里把每个引擎封装成独立的 [Engine]，任何一个挂了都不影响整体。
 * 2. **按顺序探测 + 提前收敛**。不并行轰炸（手机端省电省流量，也不容易触发风控），
 *    而是按可靠性排序依次尝试，一旦拿到足够结果就停止。
 * 3. **跨引擎去重**。同一 URL 会被多个引擎返回，用规范化 URL（去 scheme/query/尾斜杠）
 *    做 key，避免模型看到重复信息浪费上下文。
 * 4. **失败要说人话**。[report] 里带上每个引擎的具体失败原因，模型才能决定是换关键词
 *    还是换引擎，而不是收到一句无用的"搜索失败"。
 */
object WebSearch {

    /** 单个搜索引擎。返回的 JSONObject 形如 {results: JSONArray, note: String?} */
    private interface Engine {
        val id: String
        val label: String
        fun search(query: String, want: Int): List<Hit>
    }

    /** 一条搜索结果 */
    data class Hit(
        val title: String,
        val url: String,
        val snippet: String,
        val engine: String,
    ) {
        /** 规范化 URL，用于跨引擎去重 */
        val key: String get() = normalizeUrl(url)
    }

    // ---------------- 引擎实现 ----------------

    /** DuckDuckGo HTML 版：免 key、无风控、中文尚可，作为首选。 */
    private class DuckDuckGo(private val http: OkHttpClient) : Engine {
        override val id = "ddg"
        override val label = "DuckDuckGo"

        override fun search(query: String, want: Int): List<Hit> {
            val body = FormBody.Builder()
                .add("q", query)
                .add("kl", "cn-zh")
                .build()
            val req = Request.Builder()
                .url("https://html.duckduckgo.com/html/")
                .header("User-Agent", UA)
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .post(body)
                .build()
            http.newCall(req).execute().use { resp ->
                check(resp.isSuccessful) { "HTTP ${resp.code}" }
                val html = resp.body?.string().orEmpty()
                check(html.isNotBlank()) { "响应为空" }
                val titles = TITLE_RE.findAll(html).toList()
                val snips = SNIP_RE.findAll(html).map { Text.extract(it.groupValues[1]) }.toList()
                val out = ArrayList<Hit>()
                for ((i, m) in titles.withIndex()) {
                    if (out.size >= want) break
                    val raw = m.groupValues[1]
                    val abs = if (raw.startsWith("//")) "https:$raw" else raw
                    // DDG 结果是跳转链接，真实地址藏在 uddg= 参数里
                    val real = UDDG_RE.find(abs)
                        ?.let { runCatching { URLDecoder.decode(it.groupValues[1], "UTF-8") }.getOrNull() }
                        ?: abs
                    if (!real.startsWith("http")) continue
                    val title = Text.extract(m.groupValues[2])
                    if (title.isBlank()) continue
                    out += Hit(title, real, snips.getOrElse(i) { "" }, id)
                }
                check(out.isNotEmpty()) { "页面无结果（可能被限流或改版）" }
                return out
            }
        }
    }

    /** Bing 国际版：中文结果质量好，作为第二顺位。 */
    private class Bing(private val http: OkHttpClient) : Engine {
        override val id = "bing"
        override val label = "Bing"

        override fun search(query: String, want: Int): List<Hit> {
            val q = URLEncoder.encode(query, "UTF-8")
            val req = Request.Builder()
                .url("https://www.bing.com/search?q=$q&setlang=zh-hans&count=$want")
                .header("User-Agent", UA)
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .build()
            http.newCall(req).execute().use { resp ->
                check(resp.isSuccessful) { "HTTP ${resp.code}" }
                val html = resp.body?.string().orEmpty()
                check(html.isNotBlank()) { "响应为空" }
                val out = ArrayList<Hit>()
                for (b in B_ALGO_RE.findAll(html)) {
                    if (out.size >= want) break
                    val blk = b.value
                    val url = HREF_RE.find(blk)?.groupValues?.get(1) ?: continue
                    val host = runCatching { URI(url).host }.getOrNull().orEmpty()
                    if (host.isBlank()) continue
                    val title = Text.extract(
                        TITLE_H2_RE.find(blk)?.groupValues?.get(1) ?: continue
                    )
                    if (title.isBlank()) continue
                    val snippet = Text.extract(P_RE.find(blk)?.groupValues?.get(1) ?: "")
                    out += Hit(title, url, snippet.take(240), id)
                }
                check(out.isNotEmpty()) { "页面无结果" }
                return out
            }
        }
    }

    /** 搜狗：纯中文场景补充，作为第三顺位。 */
    private class Sogou(private val http: OkHttpClient) : Engine {
        override val id = "sogou"
        override val label = "搜狗"

        override fun search(query: String, want: Int): List<Hit> {
            val q = URLEncoder.encode(query, "UTF-8")
            val req = Request.Builder()
                .url("https://www.sogou.com/web?query=$q")
                .header("User-Agent", UA)
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .build()
            http.newCall(req).execute().use { resp ->
                check(resp.isSuccessful) { "HTTP ${resp.code}" }
                val html = resp.body?.string().orEmpty()
                check(html.isNotBlank()) { "响应为空" }
                val out = ArrayList<Hit>()
                // 搜狗结果块：<div class="vrwrap">…<h3 class="vr-title"><a href="…">标题</a></h3><div class="fz-mid">摘要</div>
                for (blk in SOGOU_VR_RE.findAll(html)) {
                    if (out.size >= want) break
                    val v = blk.value
                    val url = SOGOU_HREF_RE.find(v)?.groupValues?.get(1) ?: continue
                    val abs = when {
                        url.startsWith("http") -> url
                        url.startsWith("/") -> "https://www.sogou.com$url"
                        else -> continue
                    }
                    val title = Text.extract(SOGOU_TITLE_RE.find(v)?.groupValues?.get(1) ?: continue)
                    if (title.isBlank()) continue
                    val snippet = Text.extract(SOGOU_SNIP_RE.find(v)?.groupValues?.get(1) ?: "")
                    out += Hit(title, abs, snippet.take(240), id)
                }
                check(out.isNotEmpty()) { "页面无结果" }
                return out
            }
        }
    }

    // ---------------- 对外入口 ----------------

    /**
     * 搜索主入口。
     *
     * @param query 关键词（已经是最终查询串，调用方负责清洗）
     * @param want  期望条数，会被夹在 3..12
     * @return  {query, count, results:[{title,url,snippet,engine}], engines:"ddg,bing" | note?, hint? }
     *          或 {error, query, tried:[{engine,error}]}
     */
    fun search(http: OkHttpClient, query: String, want: Int = 8): JSONObject {
        val q = query.trim()
        if (q.isBlank()) return JSONObject().put("error", "query 不能为空")
        val n = want.coerceIn(3, 12)

        val engines = listOf(DuckDuckGo(http), Bing(http), Sogou(http))
        val merged = ArrayList<Hit>()
        val used = ArrayList<String>()
        val failures = ArrayList<Pair<String, String>>()
        // 每条引擎失败前先重试一次：HTML 搜索的失败绝大多数是瞬时网络抖动
        for (e in engines) {
            if (merged.size >= n) break
            val got = attempt(e, q, n - merged.size, failures)
            if (got.isNotEmpty()) {
                used += e.id
                merged += got
            }
        }

        if (merged.isEmpty()) {
            val tried = JSONArray()
            failures.forEach { (id, err) -> tried.put(JSONObject().put("engine", id).put("error", err)) }
            return JSONObject()
                .put("error", "所有搜索引擎都没拿到结果")
                .put("query", q)
                .put("tried", tried)
                .put("hint", "可能原因：网络不可达 / 被限流 / 关键词太生僻。先试 web_fetch 直接抓已知网址，或换更通用的关键词。")
        }

        // 跨引擎去重 + 同域降权（同一站点最多保留 2 条，避免结果被一个站霸屏）
        val dedup = ArrayList<Hit>()
        val seenUrl = HashSet<String>()
        val perHost = HashMap<String, Int>()
        for (h in merged) {
            if (!seenUrl.add(h.key)) continue
            val host = runCatching { URI(h.url).host }.getOrNull().orEmpty()
            val c = perHost.getOrDefault(host, 0)
            if (host.isNotBlank() && c >= 2) continue
            perHost[host] = c + 1
            dedup += h
            if (dedup.size >= n) break
        }

        val arr = JSONArray()
        dedup.forEach { h ->
            arr.put(
                JSONObject()
                    .put("title", h.title)
                    .put("url", h.url)
                    .put("snippet", h.snippet)
                    .put("engine", h.engine)
            )
        }
        return JSONObject()
            .put("query", q)
            .put("count", dedup.size)
            .put("engines", used.joinToString(","))
            .put("results", arr)
            .also { obj ->
                if (failures.isNotEmpty()) obj.put(
                    "partial_failures",
                    JSONArray().also { a -> failures.forEach { (id, err) -> a.put(JSONObject().put("engine", id).put("error", err)) } }
                )
                // 时效性提示：搜索结果天然有时效，提醒模型别把旧信息当现状
                obj.put("note", "以上为实时检索结果（截取于当前时刻），引用时请以链接页面为准。")
            }
    }

    /** 单个引擎执行：失败重试一次，两次都失败则记录原因。 */
    private fun attempt(e: Engine, q: String, want: Int, failures: MutableList<Pair<String, String>>): List<Hit> {
        var lastErr = ""
        repeat(2) { i ->
            try {
                return e.search(q, want.coerceAtLeast(3))
            } catch (t: Throwable) {
                lastErr = t.message ?: t.javaClass.simpleName
                if (i == 0) Thread.sleep(350)
            }
        }
        failures += e.id to lastErr
        return emptyList()
    }

    // ---------------- 工具函数 ----------------

    private fun normalizeUrl(url: String): String {
        val noScheme = url.removePrefix("https://").removePrefix("http://")
        val noQuery = noScheme.substringBefore('?').substringBefore('#')
        return noQuery.trimEnd('/').lowercase()
    }

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"

    private val TITLE_RE = Regex(
        """<a[^>]*class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val SNIP_RE = Regex(
        """<a[^>]*class="result__snippet"[^>]*>(.*?)</a>""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val UDDG_RE = Regex("""uddg=([^&]+)""")

    private val B_ALGO_RE = Regex("""(?s)<li class="b_algo".*?</li>""")
    private val HREF_RE = Regex("""href="(https?://[^"]+)"""")
    private val TITLE_H2_RE = Regex("""(?s)<h2>.*?<a[^>]*>(.*?)</a>""")
    private val P_RE = Regex("""(?s)<p[^>]*>(.*?)</p>""")

    private val SOGOU_VR_RE = Regex("""(?s)<div class="vrwrap"[^>]*>(.*?)(?=<div class="vrwrap"|<div class="page"|</body>)""")
    private val SOGOU_HREF_RE = Regex("""<a[^>]*href="([^"]+)"""")
    private val SOGOU_TITLE_RE = Regex("""(?s)<h3[^>]*>.*?<a[^>]*>(.*?)</a>""")
    private val SOGOU_SNIP_RE = Regex("""(?s)<div class="(?:fz-mid|str-text-info|space-txt|text-layout)"[^>]*>(.*?)</div>""")
}
