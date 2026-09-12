package com.ai.assistance.quro.genui.app.render

/**
 * 渲染通道调度 —— 从 AI 产出里识别"这个界面用什么技术画的"并分派。
 *
 * AI 在文档里写形态标记（沿用既有的 `<!--gen:xxx-->` 约定，扩展 `stack:` 前缀）：
 *   <!--stack:html-->      网页通道（默认，WebView 整体渲染）
 *   <!--stack:xml-->       AI 在 <script type="text/xml-layout"> 里给了 XML 布局
 *   <!--stack:compose-->   AI 在 <script type="text/x-compose"> 里给了组件树描述
 *
 * 关键设计：**HTML 始终是宿主**。
 * 无论 AI 选哪条通道，最终产出的都是一份 HTML 文档 —— WebView 负责承载与排版。
 * 当 AI 声明 xml / compose 通道时，它在文档里嵌入一个占位容器（约定 id），
 * 端上把该容器替换为**真实原生渲染结果**（原生控件 / Compose 组件）。
 *
 * 为什么这样设计：
 * 1. **零回归** —— 现有 HTML 流式渲染管线完全不动，出问题也不影响默认路径；
 * 2. **混合能力** —— 一个界面里可以既有 HTML 画的图表、又有原生控件做的表单，
 *    各取所长（这正是"融合技术全部支持"的实际价值）；
 * 3. **可降级** —— 原生渲染失败时页面其余部分照常显示，只是那块给出错误提示。
 */
object RenderChannel {

    /** AI 声明的技术通道 */
    enum class Kind { WEB, XML, COMPOSE, CANVAS }

    /** 占位容器的 id 约定：AI 用它标出"这块交给原生渲染" */
    const val XML_CONTAINER_ID = "gen-xml"
    const val COMPOSE_CONTAINER_ID = "gen-compose"
    const val CANVAS_CONTAINER_ID = "gen-canvas"

    data class Plan(
        val kind: Kind,
        /** 抽取出的 XML 布局源码（kind = XML 时非空） */
        val xml: String? = null,
        /** 抽取出的 Compose 描述 JSON（kind = COMPOSE 时非空） */
        val composeJson: String? = null,
        /** 抽取出的 GenCanvas 绘制指令 JSON（kind = CANVAS 时非空） */
        val canvasJson: String? = null,
        /** 页面里是否存在对应的占位容器（决定要不要走原生替换） */
        val hasContainer: Boolean = false,
        /** 是否检测到"原生工程"风格的多文件块（作为内容呈现） */
        val codeBlocks: List<CodeBlock> = emptyList()
    )

    /** 页面里成块呈现的代码（Kotlin/Java/C++/Python 等，作为界面题材） */
    data class CodeBlock(val lang: String, val code: String)

    /**
     * 分析一份（可能是流式不完整的）HTML，得出渲染计划。
     *
     * 流式安全：生成过程中文档是不完整的，此时只能做"宽松识别"——
     * 找不到结尾标记也照常返回已抽到的内容，让原生部分尽早显示。
     */
    fun analyze(html: String): Plan {
        val kind = detectKind(html)

        // 抽取 <script type="text/xml-layout">…</script>
        val xml = extractTagged(html, "text/xml-layout")
        // 抽取 <script type="text/x-compose">…</script>
        val compose = extractTagged(html, "text/x-compose")
        // 抽取 <script type="text/x-canvas">…</script>（GenCanvas 绘制指令 JSON）
        val canvas = extractTagged(html, "text/x-canvas")

        // 代码块（作为内容呈现）：<script type="text/x-code" data-lang="kotlin">
        val codes = Regex(
            "<script[^>]*type=[\"']text/x-code[\"'][^>]*data-lang=[\"']([^\"']+)[\"'][^>]*>([\\s\\S]*?)</script>",
            RegexOption.IGNORE_CASE
        ).findAll(html).map { m ->
            CodeBlock(m.groupValues[1].lowercase(), m.groupValues[2].trim())
        }.toList()

        val hasXmlContainer = html.contains("id=\"$XML_CONTAINER_ID\"") || html.contains("id='$XML_CONTAINER_ID'")
        val hasComposeContainer = html.contains("id=\"$COMPOSE_CONTAINER_ID\"") || html.contains("id='$COMPOSE_CONTAINER_ID'")
        val hasCanvasContainer = html.contains("id=\"$CANVAS_CONTAINER_ID\"") || html.contains("id='$CANVAS_CONTAINER_ID'")

        return when (kind) {
            Kind.XML -> Plan(
                kind, xml = xml ?: "", composeJson = null, canvasJson = null,
                hasContainer = hasXmlContainer || xml != null, codeBlocks = codes
            )
            Kind.COMPOSE -> Plan(
                kind, composeJson = compose ?: "", canvasJson = null,
                hasContainer = hasComposeContainer || compose != null, codeBlocks = codes
            )
            Kind.CANVAS -> Plan(
                kind, canvasJson = canvas ?: "", hasContainer = hasCanvasContainer || canvas != null,
                codeBlocks = codes
            )
            Kind.WEB -> Plan(Kind.WEB, codeBlocks = codes)
        }
    }

    /**
     * 识别技术通道。优先级：显式 stack 标记 > 内容特征 > 默认网页。
     */
    private fun detectKind(html: String): Kind {
        // 1) 显式标记（AI 按提示词约定标注）
        val m = Regex("<!--\\s*stack:(\\w+)\\s*-->", RegexOption.IGNORE_CASE).find(html)
        when (m?.groupValues?.get(1)?.lowercase()) {
            "xml" -> return Kind.XML
            "compose" -> return Kind.COMPOSE
            "canvas" -> return Kind.CANVAS
            "html", "web" -> return Kind.WEB
        }
        // 2) 内容特征兜底（AI 忘了写标记）
        if (Regex("<script[^>]*type=[\"']text/xml-layout[\"']", RegexOption.IGNORE_CASE).containsMatchIn(html))
            return Kind.XML
        if (Regex("<script[^>]*type=[\"']text/x-compose[\"']", RegexOption.IGNORE_CASE).containsMatchIn(html))
            return Kind.COMPOSE
        return Kind.WEB
    }

    /**
     * 抽取 `<script type="tag">内容</script>` 的原始内容。
     * 流式不完整时：没有 `</script>` 就取到字符串末尾（就当它还没写完）。
     */
    private fun extractTagged(html: String, tag: String): String? {
        val open = Regex(
            "<script[^>]*type=[\"']${Regex.escape(tag)}[\"'][^>]*>",
            RegexOption.IGNORE_CASE
        ).find(html) ?: return null
        val start = open.range.last + 1
        if (start >= html.length) return ""
        val end = html.indexOf("</script>", start).let { if (it < 0) html.length else it }
        return html.substring(start, end).trim()
    }
}
