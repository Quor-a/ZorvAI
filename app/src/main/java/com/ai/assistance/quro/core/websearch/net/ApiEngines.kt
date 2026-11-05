package com.ai.assistance.quro.core.websearch.net

import com.ai.assistance.quro.core.websearch.model.SearchHit
import java.net.URLEncoder

/**
 * KagiEngine —— Kagi Search API（付费账号，需 API Key）。
 * https://help.kagi.com/kagi/api/search.html
 */
class KagiEngine(override val enabled: Boolean = true) : SearchEngine {
    override val id = "kagi"
    override suspend fun search(query: String, limit: Int): List<SearchHit> {
        val key = WebSearchKeys.kagiKey
        if (key.isBlank()) return emptyList()
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "https://kagi.com/api/v0/search?q=$q&limit=$limit"
        val json = HttpStack.getWithHeaders(
            url,
            mapOf("Authorization" to "Bot $key", "Accept" to "application/json"),
            timeoutMs = 10_000,
        ) ?: return emptyList()
        return runCatching {
            val root = org.json.JSONObject(json)
            val arr = root.optJSONArray("data") ?: return emptyList()
            val out = ArrayList<SearchHit>()
            for (i in 0 until minOf(arr.length(), limit)) {
                val o = arr.optJSONObject(i) ?: continue
                val u = o.optString("url")
                val t = o.optString("title")
                if (u.isBlank() || t.isBlank()) continue
                out.add(SearchHit(title = t, url = u, snippet = o.optString("snippet"), engine = id, position = i + 1))
            }
            out
        }.getOrDefault(emptyList())
    }
}

/**
 * SerperEngine —— Serper.dev（Google 结果的商业中转，稳定 JSON，需 API Key）。
 * https://serper.dev
 */
class SerperEngine(override val enabled: Boolean = true) : SearchEngine {
    override val id = "serper"
    override suspend fun search(query: String, limit: Int): List<SearchHit> {
        val key = WebSearchKeys.serperKey
        if (key.isBlank()) return emptyList()
        val url = "https://google.serper.dev/search"
        val body = org.json.JSONObject().apply {
            put("q", query)
            put("gl", "cn")
            put("hl", "zh-cn")
            put("num", limit.coerceAtMost(10))
        }.toString()
        val json = HttpStack.postJson(
            url,
            body,
            mapOf("X-API-KEY" to key, "Content-Type" to "application/json"),
            timeoutMs = 10_000,
        ) ?: return emptyList()
        return runCatching {
            val root = org.json.JSONObject(json)
            val arr = root.optJSONArray("organic") ?: return emptyList()
            val out = ArrayList<SearchHit>()
            for (i in 0 until minOf(arr.length(), limit)) {
                val o = arr.optJSONObject(i) ?: continue
                val u = o.optString("link")
                val t = o.optString("title")
                if (u.isBlank() || t.isBlank()) continue
                out.add(SearchHit(title = t, url = u, snippet = o.optString("snippet"), engine = id, position = i + 1))
            }
            out
        }.getOrDefault(emptyList())
    }
}

/**
 * TavilyEngine —— Tavily Search API（面向 AI 的搜索，需 API Key）。
 * https://tavily.com
 */
class TavilyEngine(override val enabled: Boolean = true) : SearchEngine {
    override val id = "tavily"
    override suspend fun search(query: String, limit: Int): List<SearchHit> {
        val key = WebSearchKeys.tavilyKey
        if (key.isBlank()) return emptyList()
        val url = "https://api.tavily.com/search"
        val body = org.json.JSONObject().apply {
            put("query", query)
            put("max_results", limit.coerceAtMost(20))
            put("search_depth", "basic")
        }.toString()
        val json = HttpStack.postJson(
            url,
            body,
            mapOf("Authorization" to "Bearer $key", "Content-Type" to "application/json"),
            timeoutMs = 10_000,
        ) ?: return emptyList()
        return runCatching {
            val root = org.json.JSONObject(json)
            val arr = root.optJSONArray("results") ?: return emptyList()
            val out = ArrayList<SearchHit>()
            for (i in 0 until minOf(arr.length(), limit)) {
                val o = arr.optJSONObject(i) ?: continue
                val u = o.optString("url")
                val t = o.optString("title")
                if (u.isBlank() || t.isBlank()) continue
                out.add(SearchHit(title = t, url = u, snippet = o.optString("content"), engine = id, position = i + 1))
            }
            out
        }.getOrDefault(emptyList())
    }
}
