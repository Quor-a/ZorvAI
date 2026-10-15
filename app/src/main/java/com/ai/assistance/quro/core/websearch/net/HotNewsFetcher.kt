package com.ai.assistance.quro.core.websearch.net

import com.ai.assistance.quro.core.websearch.model.SearchHit
import org.json.JSONObject

/**
 * HotNewsFetcher —— 抓取"实时热榜"类数据源，补充通用搜索无法覆盖的"此刻在热议什么"。
 *
 * 设计取舍：热榜源大多是 JSON 接口（无需 HTML 解析），且多数不强制鉴权。
 * 任一源不可达时静默跳过（与 EngineHealth 熔断哲学一致），由 Orchestrator 兜底回退到通用检索，
 * 绝不让"取热榜"这一步拖垮或中断主流程。
 *
 * 当前接入：
 * - 哔哩哔哩热门榜（api.bilibili.com，境内稳定可达，无需登录）
 * 后续可在此追加微博热搜、知乎热榜、百度热点等（各自加一个私有方法并在 [hotList] 里汇总即可）。
 */
object HotNewsFetcher {

    /** 汇总所有热榜源，返回统一结构的 [SearchHit] 列表（每个源失败则跳过） */
    fun hotList(limit: Int): List<SearchHit> {
        val out = ArrayList<SearchHit>()
        out += bilibiliHot(limit)
        // 未来追加：out += weiboHot(limit); out += zhihuHot(limit)
        return out
    }

    /** 哔哩哔哩热门视频榜：返回当下最热的内容条目 */
    private fun bilibiliHot(limit: Int): List<SearchHit> {
        val url = "https://api.bilibili.com/x/web-interface/popular?ps=$limit&pn=1"
        val json = HttpStack.getWithHeaders(
            url,
            mapOf(
                "Referer" to "https://www.bilibili.com",
                "Origin" to "https://www.bilibili.com"
            )
        ) ?: return emptyList()

        return runCatching {
            val obj = JSONObject(json)
            val arr = obj.optJSONObject("data")?.optJSONArray("list") ?: return emptyList()
            val out = ArrayList<SearchHit>()
            for (i in 0 until minOf(arr.length(), limit)) {
                val o = arr.optJSONObject(i) ?: continue
                val title = o.optString("title").takeIf { it.isNotBlank() } ?: continue
                val bvid = o.optString("bvid")
                val link = if (bvid.isNotBlank()) "https://www.bilibili.com/video/$bvid" else continue
                val desc = o.optString("desc").takeIf { it.isNotBlank() }
                    ?: o.optString("rcmd_reason").takeIf { it.isNotBlank() }.orEmpty()
                out.add(SearchHit(title, link, desc, "bili", i + 1, -1, 1))
            }
            out
        }.getOrDefault(emptyList())
    }
}
