package com.ai.assistance.quro.core.websearch.model

/**
 * 联网搜索领域模型。
 * 全链路只传递结构化数据，不传递 HTML，这是与"浏览器套壳"方案的根本区别。
 */

/** 检索命中的一条候选结果（尚未取正文） */
data class SearchHit(
    val title: String,
    val url: String,
    val snippet: String,
    /** 来源引擎标识，用于投票加权：ddg / bing / gnews / searxng */
    val engine: String,
    /** 该结果在所属引擎结果页中的位次，从 1 开始 */
    val position: Int = 0,
    /** RSS 等源可能带发布时间（epoch millis），无则 -1 */
    val publishedAt: Long = -1L,
    /** 被多个引擎同时命中的次数，合并后填充 */
    var votes: Int = 1,
    /** 重排得分，由 ResultReranker 填充 */
    var score: Double = 0.0
)

/** 抓取并抽取正文后的文章 */
data class Article(
    val url: String,
    val title: String,
    /** 抽取后的正文，已 markdown 化 */
    val markdown: String,
    /** 抽取前 HTML 字节数，用于评估抽取质量 */
    val rawSize: Int,
    /** 抽取后正文字符数 */
    val textSize: Int,
    /** 抽取是否成功；失败时 markdown 为空串 */
    val ok: Boolean,
    val sourceDomain: String
) {
    /** 压缩比，过低说明可能是列表页/空页，可信度打折 */
    val yieldRate: Float get() = if (rawSize <= 0) 0f else textSize.toFloat() / rawSize
}

/** 最终交付给 LLM 的一段引用块 */
data class Citation(
    /** 引用编号，从 1 开始，对应正文中的 [n] */
    val index: Int,
    val title: String,
    val url: String,
    val domain: String,
    val publishedAt: Long,
    /** 已按 token 预算截断的正文摘录 */
    val excerpt: String,
    val truncated: Boolean
)

/** 一次完整联网检索的产物 */
data class SearchBundle(
    /** 供 LLM 阅读的上下文，带 [n] 编号 */
    val context: String,
    /** 与 context 中 [n] 一一对应，供 UI 渲染可点击引用卡片 */
    val citations: List<Citation>,
    /** 实际使用的查询词（改写后） */
    val queries: List<String>,
    /** 命中但未被采用的候选，便于调试与"换一批" */
    val dropped: List<SearchHit> = emptyList(),
    /** 各阶段耗时，毫秒 */
    val timings: Map<String, Long> = emptyMap()
)
