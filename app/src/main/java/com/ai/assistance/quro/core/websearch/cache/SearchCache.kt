package com.ai.assistance.quro.core.websearch.cache

import com.ai.assistance.quro.core.websearch.model.Article
import com.ai.assistance.quro.core.websearch.model.SearchHit

/**
 * 缓存层：联网能力里最容易被忽略、但体验收益最大的一环。
 *
 * 缓存命中的价值不止省流量：
 * - 延迟从"数秒"降到"毫秒级"，Agent 的响应速度直接决定它是否好用；
 * - 断网/弱网时仍可回答近期问过的问题（配合本地模型即完全离线可用）；
 * - 降低对搜索引擎的请求密度，减少被限流概率。
 *
 * 这里默认提供纯 Kotlin 的内存 LRU 实现（零依赖、开箱可用）；
 * 需要跨进程持久化时，用同包下的 RoomCache 替换即可，接口完全一致。
 */
interface CacheStore {
    fun getArticle(url: String): Article?
    fun putArticle(a: Article)
    fun getHits(query: String): List<SearchHit>?
    fun putHits(query: String, hits: List<SearchHit>)
    fun clear()
}

class MemoryCacheStore(
    private val articleTtlMs: Long = 7 * 24 * 3600_000L,
    private val hitTtlMs: Long = 30 * 60_000L,
    maxArticles: Int = 200
) : CacheStore {

    private class Box<T>(val v: T, val at: Long)

    private val articles = object : LinkedHashMap<String, Box<Article>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Box<Article>>?): Boolean =
            size > maxArticles
    }
    private val hitMap = LinkedHashMap<String, Box<List<SearchHit>>>(64)

    @Synchronized
    override fun getArticle(url: String): Article? {
        val b = articles[url] ?: return null
        if (System.currentTimeMillis() - b.at > articleTtlMs) { articles.remove(url); return null }
        return b.v
    }

    @Synchronized
    override fun putArticle(a: Article) { articles[a.url] = Box(a, System.currentTimeMillis()) }

    @Synchronized
    override fun getHits(query: String): List<SearchHit>? {
        val b = hitMap[query] ?: return null
        if (System.currentTimeMillis() - b.at > hitTtlMs) { hitMap.remove(query); return null }
        return b.v
    }

    @Synchronized
    override fun putHits(query: String, hits: List<SearchHit>) {
        hitMap[query] = Box(hits, System.currentTimeMillis())
        if (hitMap.size > 100) {
            val it = hitMap.entries.iterator()
            it.next()
            it.remove()
        }
    }

    @Synchronized
    override fun clear() { articles.clear(); hitMap.clear() }
}
