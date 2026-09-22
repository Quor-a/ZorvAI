package com.ai.assistance.quro.genui.app.websearch

import com.ai.assistance.quro.genui.app.websearch.html.MiniHtml
import com.ai.assistance.quro.genui.app.websearch.net.ChinaText
import com.ai.assistance.quro.genui.app.websearch.net.HttpStack
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Explore —— 三大垂直探索引擎（新闻 / 社区内容 / GitHub）。
 *
 * 与 WebSearchOrchestrator 的关系：那是"通用网页搜索"，这里是"垂直源直达"——
 * 直接命中每个领域里最结构化、最可信的数据源（RSS / 官方 JSON API），零解析歧义。
 * 多源并发 + 容错：任何一源失败不影响其它源，失败原因进 diagnostics。
 *
 * 全部免 key、全 https；Reddit 部分地区可能 403，由容错层吞掉。
 */
object Explore {

    private const val TIMEOUT_MS = 9_000L

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun err(e: Throwable) = (e.javaClass.simpleName + ": " + (e.message ?: ""))

    // ---------------------------------------------------------------- 新闻

    /**
     * 新闻探索：Google News RSS + Bing News RSS 双源并发。
     * 返回 {items:[{title,url,source,date,snippet}], sources:{...}}
     */
    suspend fun news(query: String, max: Int = 10): JSONObject = coroutineScope {
        val bing = async {
            runCatching {
                val xml = withTimeoutOrNull(TIMEOUT_MS) {
                    HttpStack.get("https://cn.bing.com/news/search?q=${enc(query)}&format=RSS&setmkt=zh-CN")
                } ?: throw IllegalStateException("超时/无响应")
                parseRss(xml, "bing-news", max)
            }.getOrElse { Pair(emptyList(), err(it)) }
        }
        val gnews = async {
            runCatching {
                val xml = withTimeoutOrNull(TIMEOUT_MS) {
                    HttpStack.get("https://news.google.com/rss/search?q=${enc(query)}&hl=zh-CN&gl=CN&ceid=CN:zh-Hans")
                } ?: throw IllegalStateException("超时/无响应")
                parseRss(xml, "google-news", max)
            }.getOrElse { Pair(emptyList(), err(it)) }
        }
        val sogou = async {
            runCatching {
                val html = withTimeoutOrNull(TIMEOUT_MS) {
                    HttpStack.get("https://news.sogou.com/news?query=${enc(query)}")
                } ?: throw IllegalStateException("超时/无响应")
                parseSogouNews(html, max)
            }.getOrElse { Pair(emptyList(), err(it)) }
        }
        val baidu = async {
            runCatching {
                val html = withTimeoutOrNull(TIMEOUT_MS) {
                    HttpStack.get("https://www.baidu.com/s?tn=news&word=${enc(query)}")
                } ?: throw IllegalStateException("超时/无响应")
                parseBaiduNews(html, max)
            }.getOrElse { Pair(emptyList(), err(it)) }
        }
        val (biItems, biDiag) = bing.await()
        val (gItems, gDiag) = gnews.await()
        val (sItems, sDiag) = sogou.await()
        val (bItems, bDiag) = baidu.await()
        val all = mergeDedupe(biItems + gItems + sItems + bItems, max)
        if (all.length() == 0) {
            // 四源全空：明确报错并带各源死因，绝不给模型"空结果自己编"的空间
            return@coroutineScope JSONObject()
                .put("error", "新闻搜索全部数据源失败（bing-news:$biDiag; google-news:$gDiag; " +
                    "sogou-news:$sDiag; baidu-news:$bDiag）。" +
                    "当前设备网络拿不到任何新闻数据 —— 必须在回复中如实告知用户搜索失败，" +
                    "严禁虚构任何新闻标题或内容。")
                .put("items", JSONArray())
                .put("sources", JSONObject()
                    .put("bing-news", biDiag).put("sogou-news", sDiag)
                    .put("baidu-news", bDiag).put("google-news", gDiag))
        }
        JSONObject()
            .put("items", all)
            .put("sources", JSONObject()
                .put("bing-news", biDiag).put("sogou-news", sDiag)
                .put("baidu-news", bDiag).put("google-news", gDiag))
    }

    /** 搜狗新闻 HTML：结果块 <h3 class="vr-tit"><a href>标题</a></h3> + 摘要 div（fz-mid/space-txt/text-layout） */
    private fun parseSogouNews(html: String, max: Int): Pair<List<JSONObject>, String> {
        val out = mutableListOf<JSONObject>()
        val blockRe = Regex("(?s)<h3 class=\"vr-tit[^\"]*\"[^>]*>(.*?)</h3>(.*?)(?=<h3 class=\"vr-tit|$)")
        for (m in blockRe.findAll(html)) {
            if (out.size >= max) break
            val a = Regex("<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
                .find(m.groupValues[1]) ?: continue
            val url = a.groupValues[1]
            val title = ChinaText.clean(a.groupValues[2])
            if (title.isBlank() || !url.startsWith("http")) continue
            val snip = ChinaText.clean(Regex("(?s)<div class=\"(?:fz-mid|space-txt|text-layout)[^\"]*\"[^>]*>(.*?)</div>")
                .find(m.groupValues[2])?.groupValues?.get(1) ?: "").take(200)
            out.add(JSONObject().put("title", title).put("url", url)
                .put("source", "sogou-news").put("engine", "sogou")
                .put("heat", max - out.size).put("date", "").put("snippet", snip))
        }
        return Pair(out, if (out.isEmpty()) "无结果" else "ok(${out.size})")
    }

    /** 百度新闻 HTML：结果块 <h3 class="news-title_1YtI1"><a href>标题</a></h3>，真实地址优先 mu= 属性 */
    private fun parseBaiduNews(html: String, max: Int): Pair<List<JSONObject>, String> {
        val out = mutableListOf<JSONObject>()
        val blockRe = Regex("(?s)<h3 class=\"news-title[^\"*]*\"[^>]*>.*?<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>(.*?)(?=<h3 class=\"news-title|$)")
        for (m in blockRe.findAll(html)) {
            if (out.size >= max) break
            var url = m.groupValues[1]
            val title = ChinaText.clean(m.groupValues[2])
            if (title.isBlank()) continue
            Regex("mu=\"(https?://[^\"]+)\"").find(m.groupValues[3])?.let { url = it.groupValues[1] }
            if (!url.startsWith("http")) continue
            val snip = ChinaText.clean(Regex("(?s)<span class=\"c-color-text[^\"]*\">(.*?)</span>")
                .find(m.groupValues[3])?.groupValues?.get(1) ?: "").take(200)
            out.add(JSONObject().put("title", title).put("url", url)
                .put("source", "baidu-news").put("engine", "baidu")
                .put("heat", max - out.size).put("date", "").put("snippet", snip))
        }
        return Pair(out, if (out.isEmpty()) "无结果" else "ok(${out.size})")
    }

    // ---------------------------------------------------------------- 社区

    /**
     * 社区内容探索：Hacker News（Algolia）+ Stack Overflow + Reddit 三源并发。
     * 返回 {items:[{title,url,source,score,author,date,snippet}], sources:{...}}
     */
    suspend fun community(query: String, max: Int = 10): JSONObject = coroutineScope {
        val hn = async {
            runCatching {
                val body = withTimeoutOrNull(TIMEOUT_MS) {
                    HttpStack.get("https://hn.algolia.com/api/v1/search?query=${enc(query)}&tags=story&hitsPerPage=$max")
                } ?: throw IllegalStateException("超时/无响应")
                val hits = JSONObject(body).optJSONArray("hits") ?: JSONArray()
                val out = JSONArray()
                for (i in 0 until hits.length()) {
                    val h = hits.optJSONObject(i) ?: continue
                    val title = h.optString("title").ifBlank { h.optString("story_title") }
                    if (title.isBlank()) continue
                    out.put(JSONObject()
                        .put("title", title)
                        .put("url", h.optString("url").ifBlank {
                            "https://news.ycombinator.com/item?id=" + h.optString("objectID")
                        })
                        .put("source", "hackernews")
                        .put("score", h.optInt("points", 0))
                        .put("author", h.optString("author"))
                        .put("date", h.optString("created_at").take(10))
                        .put("snippet", h.optString("story_text")
                            .replace(Regex("<[^>]+>"), "").take(200)))
                }
                Pair(out, "ok(${out.length()})")
            }.getOrElse { Pair(JSONArray(), err(it)) }
        }
        val so = async {
            runCatching {
                val body = withTimeoutOrNull(TIMEOUT_MS) {
                    HttpStack.get("https://api.stackexchange.com/2.3/search/advanced?order=desc&sort=relevance&pagesize=$max&site=stackoverflow&q=${enc(query)}")
                } ?: throw IllegalStateException("超时/无响应")
                val items = JSONObject(body).optJSONArray("items") ?: JSONArray()
                val out = JSONArray()
                for (i in 0 until items.length()) {
                    val it = items.optJSONObject(i) ?: continue
                    out.put(JSONObject()
                        .put("title", it.optString("title"))
                        .put("url", it.optString("link"))
                        .put("source", "stackoverflow")
                        .put("score", it.optInt("score", 0))
                        .put("author", it.optJSONObject("owner")?.optString("display_name") ?: "")
                        .put("date", "")
                        .put("snippet", "回答 ${it.optInt("answer_count", 0)} · 浏览 ${it.optInt("view_count", 0)}"))
                }
                Pair(out, "ok(${out.length()})")
            }.getOrElse { Pair(JSONArray(), err(it)) }
        }
        val reddit = async {
            runCatching {
                val body = withTimeoutOrNull(TIMEOUT_MS) {
                    HttpStack.get("https://www.reddit.com/search.json?q=${enc(query)}&limit=$max&sort=relevance")
                } ?: throw IllegalStateException("超时/无响应")
                val children = JSONObject(body).optJSONObject("data")?.optJSONArray("children") ?: JSONArray()
                val out = JSONArray()
                for (i in 0 until children.length()) {
                    val d = children.optJSONObject(i)?.optJSONObject("data") ?: continue
                    out.put(JSONObject()
                        .put("title", d.optString("title"))
                        .put("url", "https://www.reddit.com" + d.optString("permalink"))
                        .put("source", "r/" + d.optString("subreddit"))
                        .put("score", d.optInt("score", 0))
                        .put("author", d.optString("author"))
                        .put("date", "")
                        .put("snippet", d.optString("selftext").take(200)))
                }
                Pair(out, "ok(${out.length()})")
            }.getOrElse { Pair(JSONArray(), err(it)) }
        }
        val merged = JSONArray()
        listOf(hn.await(), so.await(), reddit.await()).forEach { (arr, _) ->
            for (i in 0 until arr.length()) merged.put(arr.getJSONObject(i))
        }
        JSONObject()
            .put("items", merged)
            .put("sources", JSONObject()
                .put("hackernews", hn.await().second)
                .put("stackoverflow", so.await().second)
                .put("reddit", reddit.await().second))
    }

    // ---------------------------------------------------------------- GitHub

    /**
     * GitHub 探索：官方 Search API（免 key，10 次/分钟限额由调用方把握）。
     * type = repositories | users。返回 {items:[...], total, diag}
     */
    suspend fun github(query: String, type: String = "repositories", max: Int = 10): JSONObject {
        val t = if (type == "users") "users" else "repositories"
        return runCatching {
            val body = withTimeoutOrNull(TIMEOUT_MS) {
                HttpStack.get(
                    "https://api.github.com/search/$t?q=${enc(query)}&per_page=$max" +
                        if (t == "repositories") "&sort=stars" else ""
                )
            } ?: throw IllegalStateException("超时/无响应")
            val json = JSONObject(body)
            val items = json.optJSONArray("items") ?: JSONArray()
            val out = JSONArray()
            for (i in 0 until items.length()) {
                val r = items.optJSONObject(i) ?: continue
                val o = JSONObject()
                if (t == "repositories") {
                    o.put("name", r.optString("full_name"))
                        .put("url", r.optString("html_url"))
                        .put("stars", r.optInt("stargazers_count", 0))
                        .put("language", r.optString("language"))
                        .put("updated", r.optString("updated_at").take(10))
                        .put("desc", r.optString("description"))
                } else {
                    o.put("name", r.optString("login"))
                        .put("url", r.optString("html_url"))
                        .put("type", r.optString("type"))
                        .put("desc", r.optString("bio"))
                }
                out.put(o)
            }
            JSONObject().put("items", out)
                .put("total", json.optInt("total_count", out.length()))
                .put("diag", "ok(${out.length()})")
        }.getOrElse {
            JSONObject().put("items", JSONArray()).put("total", 0).put("diag", err(it))
        }
    }

    // ---------------------------------------------------------------- 解析工具

    /** 极简 RSS/Atom 解析（复用 MiniHtml 的标签语言容错解析，xmlMode 保真自闭合标签） */
    private fun parseRss(xml: String, source: String, max: Int): Pair<List<JSONObject>, String> {
        val root = runCatching { MiniHtml.parse(xml, xmlMode = true) }.getOrNull()
            ?: return Pair(emptyList(), "parse失败")
        val out = mutableListOf<JSONObject>()
        MiniHtml.walk(root) { n ->
            if ((n.tag == "item" || n.tag == "entry") && out.size < max) {
                var title = ""
                var link = ""
                var date = ""
                var desc = ""
                MiniHtml.walk(n) { c ->
                    when (c.tag) {
                        "title" -> if (title.isBlank()) title = MiniHtml.textOf(c).trim()
                        "link" -> {
                            val href = c.attr("href")
                            val t = MiniHtml.textOf(c).trim()
                            if (link.isBlank()) link = href.ifBlank { t }
                        }
                        "pubdate", "published", "updated" -> if (date.isBlank()) date = MiniHtml.textOf(c).trim().take(16)
                        "description", "summary", "content" -> if (desc.isBlank())
                            desc = MiniHtml.textOf(c).trim().replace(Regex("<[^>]+>"), "").take(200)
                    }
                }
                if (title.isNotBlank()) out.add(JSONObject()
                    .put("title", title)
                    .put("url", link)
                    .put("source", source)
                    .put("engine", source)
                    .put("heat", max - out.size)
                    .put("date", date)
                    .put("snippet", desc))
            }
        }
        return Pair(out, "ok(${out.size})")
    }

    /** 跨源合并去重（规范化 URL 为 key），保留源顺序（先到先得） */
    private fun mergeDedupe(items: List<JSONObject>, max: Int): JSONArray {
        val seen = HashSet<String>()
        val out = JSONArray()
        for (it in items) {
            val key = it.optString("url")
                .removePrefix("https://").removePrefix("http://")
                .substringBefore('/').substringBefore('?')
            if (key.isBlank() || !seen.add(key)) continue
            out.put(it)
            if (out.length() >= max) break
        }
        return out
    }
}
