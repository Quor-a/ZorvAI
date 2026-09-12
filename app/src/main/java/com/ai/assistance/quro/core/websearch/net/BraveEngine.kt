package com.ai.assistance.quro.core.websearch.net

import com.ai.assistance.quro.core.websearch.model.SearchHit
import java.net.URLEncoder

/**
 * BraveEngine —— 独立商业索引源，由 Brave Search API 驱动。
 *
 * 独立性价值：Brave 使用自有索引（非 Bing/Google 转售），在受限网络下常能与 Bing 形成互补，
 * 是解"单点 Bing 依赖"的关键一极。
 *
 * 凭据由 [WebSearchKeys] 持有（仅存本机 SharedPreferences，绝不外传）；未配置 key 时
 * [search] 直接返回空（被 EngineRouter 视为该引擎暂不可达并走熔断，不影响其余引擎）。
 * 这与 SearXngEngine（baseUrl 为空时跳过）的处理方式一致。
 */
class BraveEngine(override val enabled: Boolean = true) : SearchEngine {

    override val id = "brave"

    override suspend fun search(query: String, limit: Int): List<SearchHit> {
        val key = WebSearchKeys.braveKey
        if (key.isBlank()) return emptyList()

        val q = URLEncoder.encode(query, "UTF-8")
        val url = "https://api.search.brave.com/res/v1/web/search" +
            "?q=$q&count=$limit&country=cn&search_lang=zh-hans&result_filter=web"
        val headers = mapOf(
            "Accept" to "application/json",
            "Accept-Encoding" to "gzip",
            "X-Subscription-Token" to key
        )
        val json = HttpStack.getWithHeaders(url, headers, timeoutMs = 10_000) ?: return emptyList()
        return parseBrave(json, limit)
    }

    private fun parseBrave(json: String, limit: Int): List<SearchHit> {
        return runCatching {
            val root = org.json.JSONObject(json)
            val web = root.optJSONObject("web") ?: return emptyList()
            val arr = web.optJSONArray("results") ?: return emptyList()
            val out = ArrayList<SearchHit>()
            for (i in 0 until minOf(arr.length(), limit)) {
                val o = arr.optJSONObject(i) ?: continue
                val u = o.optString("url")
                val t = o.optString("title")
                if (u.isBlank() || t.isBlank()) continue
                val desc = o.optString("description")
                val age = o.optString("age")
                out.add(
                    SearchHit(
                        title = t,
                        url = u,
                        snippet = desc,
                        engine = id,
                        position = i + 1,
                        publishedAt = parseAge(age)
                    )
                )
            }
            out
        }.getOrDefault(emptyList())
    }

    /**
     * 解析 Brave 的 age 字段（"3 days ago" / "2 hours ago" / "1 month ago"），
     * 转成 epoch millis 用于新鲜度打分；解析失败返回 -1。
     */
    private fun parseAge(age: String): Long {
        if (age.isBlank()) return -1L
        return runCatching {
            val parts = age.trim().split("\\s+".toRegex())
            val num = parts.getOrNull(0)?.toIntOrNull() ?: return -1L
            val unit = parts.getOrNull(1)?.lowercase() ?: return -1L
            val millis = when {
                unit.startsWith("second") -> num * 1_000L
                unit.startsWith("minute") -> num * 60_000L
                unit.startsWith("hour") -> num * 3_600_000L
                unit.startsWith("day") -> num * 86_400_000L
                unit.startsWith("week") -> num * 604_800_000L
                unit.startsWith("month") -> num * 2_592_000_000L
                unit.startsWith("year") -> num * 31_536_000_000L
                else -> return -1L
            }
            System.currentTimeMillis() - millis
        }.getOrDefault(-1L)
    }
}
