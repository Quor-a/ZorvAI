package com.ai.assistance.quro.core.websearch

import com.ai.assistance.quro.core.websearch.model.SearchHit
import com.ai.assistance.quro.core.websearch.net.AntiBot
import com.ai.assistance.quro.core.websearch.net.EngineHealth
import com.ai.assistance.quro.core.websearch.net.EngineRouter
import com.ai.assistance.quro.core.websearch.net.HttpStack
import com.ai.assistance.quro.core.websearch.net.SearchEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SearchDiagnostics —— 联网能力自检工具。
 *
 * 存在的意义：web_search 返回空时，可能原因跨越四层——
 *   网络层不通 / 引擎拒绝（403、验证码）/ 返回 200 但解析出 0 条 / 重排后全被过滤。
 * 没有这个工具，只能靠猜。跑一次就能精确定位到具体引擎和具体阶段。
 *
 * 建议在 App 设置页放一个"联网自检"按钮调用 diagnose()，
 * 或让 Agent 在 web_search 连续失败时自动调用。
 */
object SearchDiagnostics {

    data class EngineReport(
        val id: String,
        val enabled: Boolean,
        /** HTTP 状态码；-1 表示连接层异常（超时 / DNS / 证书） */
        val httpCode: Int,
        val error: String?,
        /** 解析出的结果条数 */
        val parsed: Int,
        val costMs: Long,
        val sample: String?,
        val verdict: String,
        /** 是否命中反爬拦截页（状态码 200 但无真实结果） */
        val blocked: Boolean = false
    )

    data class Report(
        val engines: List<EngineReport>,
        val afterMerge: Int,
        val afterRerank: Int,
        val summary: String,
        /** 建议启用的引擎 id（实测可用的） */
        val recommended: List<String> = emptyList()
    )

    /**
     * 逐个引擎实测一次真实查询。
     * @param probeUrl 各引擎需要探测的 URL，由引擎自身提供（见 probeUrl 扩展）
     */
    suspend fun diagnose(
        engines: List<SearchEngine>,
        query: String = "Jetpack Compose 教程"
    ): Report = withContext(Dispatchers.IO) {
        // 自检是一次"全量体检"，先清掉熔断记录，否则刚被熔断的引擎会被误判为不可用
        EngineHealth.reset()
        val reports = ArrayList<EngineReport>()

        for (e in engines) {
            if (!e.enabled) {
                reports.add(
                    EngineReport(e.id, false, 0, null, 0, 0, null, "已禁用")
                )
                continue
            }
            val t0 = System.currentTimeMillis()
            val url = e.probeUrl(query)
            val raw = if (url != null) {
                HttpStack.request(url, forHtml = e.id == "ddg", timeoutMs = 12_000)
            } else null

            val hits: List<SearchHit> = try {
                e.search(query, 5)
            } catch (ex: Exception) {
                emptyList()
            }
            val cost = System.currentTimeMillis() - t0

            val code = raw?.code ?: -1
            val blocked = raw?.body?.let { AntiBot.isBlocked(it) } ?: false
            val blockReason = raw?.body?.let { AntiBot.reason(it) }
            val verdict = when {
                hits.isNotEmpty() -> "可用"
                blocked -> "被反爬拦截（HTTP 200 但无真实结果）"
                url == null -> "未配置探测地址（以 search 实测结果为准）"
                code == -1 -> "连接失败（超时/无网络/DNS）"
                code == 403 || code == 429 -> "被拒绝（$code，可能触发反爬）"
                code in 200..299 -> "返回 200 但解析出 0 条 —— 解析器需适配"
                else -> "HTTP $code"
            }
            reports.add(
                EngineReport(
                    id = e.id,
                    enabled = true,
                    httpCode = code,
                    error = raw?.error ?: blockReason,
                    parsed = hits.size,
                    costMs = cost,
                    sample = hits.firstOrNull()?.title?.take(50),
                    verdict = verdict,
                    blocked = blocked
                )
            )
        }

        // 合并与重排后的存活量
        // 自检场景必须绕过熔断（useHealth = false），否则被熔断的引擎会被跳过，
        // 报告里显示"已禁用"，无法拿到真实可用性
        val merged = try {
            EngineRouter.search(engines, query, 8, 12_000, useHealth = false)
        } catch (e: Exception) {
            emptyList()
        }
        val ranked = if (merged.isNotEmpty()) {
            com.ai.assistance.quro.core.websearch.rank.ResultReranker.rerank(
                merged, query, recencyBias = 0.3
            )
        } else emptyList()

        val usable = reports.count { it.parsed > 0 }
        val recommended = reports.filter { it.parsed > 0 }.map { it.id }
        val blockedIds = reports.filter { it.blocked }.map { it.id }
        val deadIds = reports.filter { it.httpCode == -1 }.map { it.id }

        val summary = buildString {
            when {
                usable == 0 -> append("所有引擎均无结果。")
                merged.isEmpty() -> append("有引擎返回但合并后为空，检查 URL 归一化逻辑。")
                ranked.isEmpty() -> append("重排过滤过强，检查 ResultReranker 阈值。")
                else -> append(
                    "联网可用：$usable/${engines.size} 个引擎正常，" +
                        "合并 ${merged.size} 条，重排后 ${ranked.size} 条。"
                )
            }
            if (deadIds.isNotEmpty()) {
                append(" 不可达源（建议关闭或依赖熔断跳过）：${deadIds.joinToString()}。")
            }
            if (blockedIds.isNotEmpty()) {
                append(" 被反爬拦截：${blockedIds.joinToString()}。")
            }
            if (usable == 0) {
                append(" 建议：仅保留 Bing 双通道，或配置自建 SearXNG 实例。")
            }
        }

        Report(reports, merged.size, ranked.size, summary, recommended)
    }

    /** 各引擎用于探测的原始 URL，便于单独验证状态码 */
    private fun SearchEngine.probeUrl(query: String): String? {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        return when (id) {
            "bing" -> "https://www.bing.com/search?q=$q&format=rss&count=5"
            "gnews" -> "https://news.google.com/rss/search?q=$q&hl=zh-CN&gl=CN&ceid=CN:zh-Hans"
            "searxng" -> null
            "ddg" -> "https://html.duckduckgo.com/html/?q=$q"
            else -> null
        }
    }

    fun render(r: Report): String = buildString {
        appendLine("=== 联网自检报告 ===")
        for (e in r.engines) {
            if (!e.enabled) {
                appendLine("[${e.id}] 已禁用")
                continue
            }
            appendLine("[${e.id}] ${e.verdict}")
            appendLine("    HTTP=${e.httpCode} 解析=${e.parsed}条 耗时=${e.costMs}ms")
            if (e.error != null) appendLine("    错误: ${e.error}")
            if (e.sample != null) appendLine("    样例: $e.sample")
        }
        appendLine("合并后: ${r.afterMerge} 条 | 重排后: ${r.afterRerank} 条")
        appendLine("引擎健康度: ${EngineHealth.summary()}")
        if (r.recommended.isNotEmpty()) {
            appendLine("建议启用: ${r.recommended.joinToString()}（其余可关闭以提速）")
        }
        appendLine("结论: ${r.summary}")
    }
}
