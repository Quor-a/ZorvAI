package com.ai.assistance.quro.core.canvas

/**
 * Canvas 混合路由器（PRD 3.3）：三档通道，四级决策。
 *
 *  A 增强 Markdown（Compose 原生，默认档，覆盖 70% 问答）
 *  B 结构化 AIP JSON + 原生渲染（长文档 / 导图 / PPT / 图表主力）
 *  C WebView 模板（逃生舱，V1 仅在 B 无法表达时启用——当前实现保留枚举与判定，
 *    实际 C 通道渲染复用既有 html 工件路径，此处不重复建设）
 *
 * 决策顺序（严格按 PRD）：
 *  1. 硬指令优先：用户显式指定形态（「做成 PPT」「画思维导图」「写成文档」）→ 锁定；
 *  2. 意图分类：端侧规则分类器对 query 打标（PRD 允许规则 + 小模型，V1 用规则）；
 *  3. 信封头信号：模型流式输出的前 120 token 信封 kind 字段 → 定档；
 *  4. 复杂度启发式：长度 > 1500 字 / 二级标题 ≥ 3 / 含表格图表 → A 升 B；
 *  5. 都不命中 → A 通道兜底。
 */
object CanvasRouter {

    enum class Channel { A, B, C }

    data class RouteDecision(
        val channel: Channel,
        /** 命中的决策级别（排查 / 审计用）。 */
        val reason: String,
        /** 硬指令或分类器锁定的形态，交给提示词让模型按 AIP 输出。 */
        val hintKind: String?,   // doc | deck | mindmap | null
    )

    /* ---- 形态关键词（意图分类规则表，PRD 表 2-1 的场景映射） ---- */

    private val DECK_WORDS = listOf("ppt", "幻灯", "汇报材料", "演示文稿", "slide", "做成演示", "deck")
    private val MINDMAP_WORDS = listOf("思维导图", "导图", "脑图", "mindmap", "发散一下", "头脑风暴", "梳理一下结构")
    private val DOC_WORDS = listOf("写成文档", "写一份", "调研报告", "建设方案", "行业方案", "长文档", "word", "docx")

    /**
     * 路由决策。参数对应四级：
     *  [query] 用户原始输入；[envelopeKind] 流式前 120 token 嗅探到的信封 kind（无则 null）；
     *  [contentLength] 已生成内容长度；[h2Count] 二级标题数；[hasTableOrChart] 是否含表格/图表。
     */
    fun route(
        query: String,
        envelopeKind: String? = null,
        contentLength: Int = 0,
        h2Count: Int = 0,
        hasTableOrChart: Boolean = false,
    ): RouteDecision {
        val q = query.lowercase()

        // 1. 硬指令（用户显式指定形态，直接锁定）
        val hardKind = when {
            DECK_WORDS.any { q.contains(it) } -> "deck"
            MINDMAP_WORDS.any { q.contains(it) } -> "mindmap"
            DOC_WORDS.any { q.contains(it) } -> "doc"
            else -> null
        }
        if (hardKind != null) return RouteDecision(Channel.B, "硬指令:$hardKind", hardKind)

        // 3. 信封头信号（模型自报形态，优先级高于分类器——模型最清楚自己要输出什么）
        if (envelopeKind != null && envelopeKind in listOf("doc", "deck", "mindmap")) {
            return RouteDecision(Channel.B, "信封头:$envelopeKind", envelopeKind)
        }

        // 2. 意图分类（规则版，置信度低的场景自然落不到这里）
        // 4. 复杂度启发式：重内容 → 升 B
        if (contentLength > 1500 || h2Count >= 3 || hasTableOrChart) {
            return RouteDecision(Channel.B, "复杂度启发", null)
        }

        // 5. 默认兜底：增强 Markdown
        return RouteDecision(Channel.A, "默认", null)
    }

    /**
     * 生成中途软重路由判定（PRD 3.3 中途改道）：信封声明 kind 与实际内容不符时触发。
     * 典型：声明 doc，但模型持续输出纯 Markdown（前 120 token 后仍无 "blocks"）。
     * 返回 true = 允许软重路由（保留已渲染内容、切解析档位，不打断不重绘）。
     */
    fun shouldSoftReroute(envelopeKind: String?, streamedSoFar: String, tokenCount: Int): Boolean {
        if (envelopeKind == null && tokenCount > 120) return true
        if (envelopeKind != null && tokenCount > 240 && !streamedSoFar.contains("\"blocks\"")) return true
        return false
    }
}
