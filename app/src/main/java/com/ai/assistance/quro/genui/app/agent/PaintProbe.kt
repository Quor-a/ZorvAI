package com.ai.assistance.quro.genui.app.agent

/**
 * 绘制过程探针 —— 从**流式到达的 HTML 增量**里实时解析"正在画什么"。
 *
 * 设计立场：这些结论全部来自对已到达文本的真实观测，不是预设的假进度条。
 * 探针不认识"组件白名单"，只在文档里找**结构里程碑**（标签、语义区块、图表容器、
 * 脚本边界），因此 AI 怎么自由发挥都不影响判断。
 *
 * 为什么不做精确解析：流式文档随时处于"半截标签"状态，任何严格解析都会失败。
 * 这里只用**已经闭合的片段**做证据（如出现 `</style>` 就知道样式写完），
 * 宁可晚一步报，也不错报。
 *
 * 阶段权重的取法：按真实文档里各阶段通常占的比例给粗粒度值，保证单调不减。
 */
class PaintProbe {

    /** 阶段定义复用 AgentEvent.PaintStep，权重在此表按"真实文档里的篇幅占比"给 */
    private val weightOf = mapOf(
        AgentEvent.PaintStep.HEAD to 0.05f,
        AgentEvent.PaintStep.STYLE to 0.25f,
        AgentEvent.PaintStep.LAYOUT to 0.45f,
        AgentEvent.PaintStep.CONTENT to 0.65f,
        AgentEvent.PaintStep.CHART to 0.80f,
        AgentEvent.PaintStep.SCRIPT to 0.92f,
        AgentEvent.PaintStep.POLISH to 1.00f,
    )

    /** 阶段的中文说明，用于时间线与状态行 */
    private val labelOf = mapOf(
        AgentEvent.PaintStep.HEAD to "读取文档头",
        AgentEvent.PaintStep.STYLE to "铺设样式",
        AgentEvent.PaintStep.LAYOUT to "搭建骨架",
        AgentEvent.PaintStep.CONTENT to "填充内容",
        AgentEvent.PaintStep.CHART to "绘制图表",
        AgentEvent.PaintStep.SCRIPT to "接线交互",
        AgentEvent.PaintStep.POLISH to "收尾校验",
    )

    fun weight(s: AgentEvent.PaintStep): Float = weightOf[s] ?: 0.5f

    /** 阶段的中文短名（供 UI 直接展示） */
    fun label(s: AgentEvent.PaintStep): String = labelOf[s] ?: s.name

    private var reached: AgentEvent.PaintStep = AgentEvent.PaintStep.HEAD

    private val seen = mutableSetOf<AgentEvent.PaintStep>()

    /** 已播报过的"内容区块"数量，用于避免同一区块反复播报 */
    private var contentSections = 0
    private var lastDetail = ""

    /**
     * 喂入一段新增的 delta，返回本次**新达成**的里程碑（可能为空）。
     * 调用方拿到就播报，拿不到就不播 —— 事件频率与文档结构变化强相关，
     * 不会像"每 4KB 一次"那样在长文档里刷屏或在短文档里沉默。
     */
    fun feed(delta: String, full: String): List<AgentEvent.Painting> {
        val out = mutableListOf<AgentEvent.Painting>()

        fun advance(step: AgentEvent.PaintStep, detail: String) {
            if (!seen.add(step)) {
                // 阶段已达成过：只在细节变化时补报，避免重复条目
                if (detail != lastDetail && step == AgentEvent.PaintStep.CONTENT) {
                    lastDetail = detail
                    out += AgentEvent.Painting(step, detail, weight(step))
                }
                return
            }
            reached = if (weight(step) >= weight(reached)) step else reached
            lastDetail = detail
            out += AgentEvent.Painting(step, detail, weight(step))
        }

        // —— HEAD：<head> 开且已出现 <body（说明头部结构完整） ——
        if (!seen.contains(AgentEvent.PaintStep.HEAD) && full.contains("<body", ignoreCase = true)) {
            advance(AgentEvent.PaintStep.HEAD, "文档头就绪")
        }
        // —— STYLE：<style> 已闭合。闭合才认，半截的样式表说明还没写完 ——
        if (!seen.contains(AgentEvent.PaintStep.STYLE) && full.contains("</style>", ignoreCase = true)) {
            advance(AgentEvent.PaintStep.STYLE, "样式表已就位")
        }
        // —— LAYOUT：出现第一个容器级区块 ——
        if (!seen.contains(AgentEvent.PaintStep.LAYOUT) && hasContainer(full)) {
            advance(AgentEvent.PaintStep.LAYOUT, "主体骨架已搭建")
        }
        // —— CONTENT：每出现一个有意义的语义区块就报一次 ——
        val sections = countSections(full)
        if (sections > contentSections) {
            contentSections = sections
            val name = lastSectionName(full)
            if (sections >= 2) {
                advance(AgentEvent.PaintStep.CONTENT,
                    if (name.isNotBlank()) "正在写「$name」" else "正在填充第 $sections 个区块")
            }
        }
        // —— CHART：图表/画布/可视化容器出现 ——
        if (!seen.contains(AgentEvent.PaintStep.CHART) && hasChart(full)) {
            advance(AgentEvent.PaintStep.CHART, "图表区域正在成型")
        }
        // —— SCRIPT：首个 <script> 闭合（交互接线完成） ——
        if (!seen.contains(AgentEvent.PaintStep.SCRIPT) && full.contains("</script>", ignoreCase = true)) {
            advance(AgentEvent.PaintStep.SCRIPT, "交互逻辑已接线")
        }
        // —— POLISH：文档收尾 ——
        if (!seen.contains(AgentEvent.PaintStep.POLISH) && full.contains("</html>", ignoreCase = true)) {
            advance(AgentEvent.PaintStep.POLISH, "正在做最后校验")
        }
        return out
    }

    /** 已到达的最高阶段（用于状态行摘要） */
    fun currentStep(): AgentEvent.PaintStep = reached

    /** 容器级标签：只要出现其一，就说明骨架在搭了 */
    private fun hasContainer(s: String): Boolean =
        Regex("<(main|section|header|nav|article|aside|table|form)[\\s>]", RegexOption.IGNORE_CASE)
            .containsMatchIn(s)

    /**
     * 统计"有意义的区块数"。
     * 用语义标签 + 带 class 的容器两类证据：AI 用 <div class="card"> 很常见，
     * 但也常用语义标签，两者都算，避免漏计。
     */
    private fun countSections(s: String): Int {
        val semantic = Regex("<(section|article|header|footer|nav|aside|table|form)[\\s>]", RegexOption.IGNORE_CASE)
            .findAll(s).count()
        val carousel = Regex("class=[\"'][^\"']*\\b(card|panel|block|group|item|section)\\b", RegexOption.IGNORE_CASE)
            .findAll(s).count()
        return semantic + carousel / 2   // class 匹配噪声大，折半计
    }

    /** 抓最近一个区块的可见标题，用作"正在写「xxx」"的素材 */
    private fun lastSectionName(s: String): String {
        // 找最近的 h1-h4 文本，取纯文本
        val m = Regex("<h[1-4][^>]*>([\\s\\S]{1,60}?)</h[1-4]>", RegexOption.IGNORE_CASE).findAll(s).lastOrNull()
        val raw = m?.groupValues?.get(1)?.replace(Regex("<[^>]+>"), "")?.trim() ?: return ""
        return raw.take(14)
    }

    /** 图表证据：canvas / svg 图形密集 / 内置运行时图表库的初始化调用 */
    private fun hasChart(s: String): Boolean {
        if (Regex("<canvas[\\s>]", RegexOption.IGNORE_CASE).containsMatchIn(s)) return true
        if (Regex("\\bnew\\s+(Chart|echarts|THREE)\\b", RegexOption.IGNORE_CASE).containsMatchIn(s)) return true
        if (Regex("\\b(echarts|chart\\.js|three\\.min)\\.(init|render)", RegexOption.IGNORE_CASE).containsMatchIn(s)) return true
        // SVG 里出现折线/柱形类路径或元素
        val svgShapes = Regex("<(polyline|polygon|circle|rect|path)[\\s>]", RegexOption.IGNORE_CASE).findAll(s).count()
        return svgShapes >= 6   // 少量图标不算图表，够多才当可视化
    }
}
