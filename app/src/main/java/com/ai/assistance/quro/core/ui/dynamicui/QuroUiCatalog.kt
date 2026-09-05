package com.ai.assistance.quro.core.ui.dynamicui

/**
 * A2UI 第①层：Catalog（组件白名单「词汇表」+ 参数校验）。
 *
 * 核心理念：模型输出永远是「数据」不是「代码」；它只能从 Catalog 里点菜——
 * 组件类型、动作类型都必须在白名单内，越界参数一律按 A2UI 铁律
 * 「校验不过降级到静态布局，绝不尽量渲染」。
 *
 * 本层与 [QuroUiDslParser]（第⑤层 Kotlin 翻译器）配合：解析器先把 JSON 翻成节点树，
 * Catalog 再对树做「白名单 + 约束」体检；体检不过的节点就地降级成一行静态提示文本，
 * 保证「单个节点坏掉不影响整棵树」，且不渲染任何未经许可的结构。
 */
object QuroUiCatalog {

    /** 允许出现的组件类型（白名单词汇表）。 */
    val COMPONENTS: Set<String> = setOf(
        "column", "row", "box", "card", "pane", "text", "image", "icon", "badge", "progress",
        "divider", "spacer", "markdown", "video", "audio", "browser", "code", "html",
        "button", "text_input", "checkbox", "switch", "select", "slider", "list", "tabs"
    )

    /** 允许出现的动作类型（A2UI 第②小语种：动作语言）。 */
    val ACTIONS: Set<String> = setOf(
        "callback", "toggle", "open_url", "copy", "tool_call", "skill", "open_app",
        "open_screen", "render_html", "render_vispro", "visual_popup", "visual_ask"
    )

    enum class Severity { DEGRADE, WARN }

    data class Violation(val path: String, val message: String, val severity: Severity)

    data class CatalogResult(
        val root: QuroUiNode,
        val violations: List<Violation>,
        val degraded: Boolean
    )

    private val COLOR_RE = Regex("^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")

    /** 校验整棵树，返回净化后的树（非法节点就地降级为静态文本）。 */
    fun validate(root: QuroUiNode): CatalogResult {
        val violations = mutableListOf<Violation>()
        val cleaned = validateNode(root, "root", violations)
        return CatalogResult(cleaned, violations, violations.any { it.severity == Severity.DEGRADE })
    }

    private fun validateNode(node: QuroUiNode, path: String, v: MutableList<Violation>): QuroUiNode {
        val type = nodeType(node)
        if (type !in COMPONENTS) {
            v.add(Violation(path, "未知组件类型：$type（已降级为静态文本）", Severity.DEGRADE))
            return QuroTextNode(value = "⚠️ 未识别的组件：$type", typography = "caption")
        }
        return when (node) {
            is QuroColumnNode -> node.copy(children = node.children.map { validateNode(it, "$path/column", v) })
            is QuroRowNode -> node.copy(children = node.children.map { validateNode(it, "$path/row", v) })
            is QuroBoxNode -> node.copy(children = node.children.map { validateNode(it, "$path/box", v) })
            is QuroPaneNode -> node.copy(children = node.children.map { validateNode(it, "$path/pane", v) })
            is QuroCardNode -> node.copy(
                children = node.children.map { validateNode(it, "$path/card", v) },
                onClick = validateAction(node.onClick, "$path.card.onClick", v),
            )
            is QuroTabsNode -> node.copy(tabs = node.tabs.map {
                it.copy(node = it.node?.let { n -> validateNode(n, "$path/tabs", v) })
            })
            is QuroListNode -> node.copy(itemTemplate = node.itemTemplate?.let { validateNode(it, "$path/list", v) })
            is QuroButtonNode -> node.copy(action = validateAction(node.action, "$path.button.action", v))
            is QuroTextNode -> validateText(node, path, v)
            is QuroImageNode -> if (isSafeUrl(node.url)) node else degradeText("$path.image.url", "非法图片地址：${node.url}", v)
            is QuroProgressNode -> validateProgress(node, path, v)
            is QuroSliderNode -> validateSlider(node, path, v)
            is QuroBrowserNode -> if (isSafeUrl(node.url)) node else degradeText("$path.browser.url", "非法浏览器地址：${node.url}", v)
            is QuroVideoNode -> if (isSafeUrl(node.url)) node else degradeText("$path.video.url", "非法视频地址：${node.url}", v)
            is QuroAudioNode -> if (isSafeUrl(node.url)) node else degradeText("$path.audio.url", "非法音频地址：${node.url}", v)
            // html 节点：AI 自写 HTML，长度上限 100KB 防 OOM，超长降级为文本提示
            is QuroHtmlNode -> if (node.html.length > 100 * 1024) degradeText("$path.html", "HTML 内容超长（${node.html.length} 字符，上限 100KB）", v) else node
            // 其余叶子节点：无额外约束，原样放行（未知字段由渲染器忽略）
            is QuroIconNode -> node
            is QuroBadgeNode -> node
            is QuroMarkdownNode -> node
            is QuroCodeNode -> node
            is QuroTextInputNode -> node
            is QuroCheckboxNode -> node
            is QuroSwitchNode -> node
            is QuroSelectNode -> node
            is QuroDividerNode -> node
            is QuroSpacerNode -> node
        }
    }

    private fun nodeType(node: QuroUiNode): String = when (node) {
        is QuroColumnNode -> "column"
        is QuroRowNode -> "row"
        is QuroBoxNode -> "box"
        is QuroPaneNode -> "pane"
        is QuroCardNode -> "card"
        is QuroTextNode -> "text"
        is QuroImageNode -> "image"
        is QuroIconNode -> "icon"
        is QuroBadgeNode -> "badge"
        is QuroProgressNode -> "progress"
        is QuroDividerNode -> "divider"
        is QuroSpacerNode -> "spacer"
        is QuroMarkdownNode -> "markdown"
        is QuroVideoNode -> "video"
        is QuroAudioNode -> "audio"
        is QuroBrowserNode -> "browser"
        is QuroHtmlNode -> "html"
        is QuroCodeNode -> "code"
        is QuroButtonNode -> "button"
        is QuroTextInputNode -> "text_input"
        is QuroCheckboxNode -> "checkbox"
        is QuroSwitchNode -> "switch"
        is QuroSelectNode -> "select"
        is QuroSliderNode -> "slider"
        is QuroListNode -> "list"
        is QuroTabsNode -> "tabs"
    }

    private fun validateAction(action: QuroUiAction?, path: String, v: MutableList<Violation>): QuroUiAction? {
        if (action == null) return null
        val type = actionType(action)
        if (type !in ACTIONS) {
            v.add(Violation(path, "未知动作类型：$type（已丢弃该动作，节点保留）", Severity.WARN))
            return null
        }
        return action
    }

    private fun actionType(a: QuroUiAction): String = when (a) {
        is QuroCallbackAction -> "callback"
        is QuroToggleAction -> "toggle"
        is QuroOpenUrlAction -> "open_url"
        is QuroCopyAction -> "copy"
        is QuroToolCallAction -> "tool_call"
        is QuroSkillAction -> "skill"
        is QuroOpenAppAction -> "open_app"
        is QuroOpenScreenAction -> "open_screen"
        is QuroRenderHtmlAction -> "render_html"
        is QuroRenderVisproAction -> "render_vispro"
        is QuroVisualPopupAction -> "visual_popup"
        is QuroVisualAskAction -> "visual_ask"
    }

    // ─── 参数约束 ───

    private fun validateText(node: QuroTextNode, path: String, v: MutableList<Violation>): QuroUiNode {
        val styleOk = node.typography == null || node.typography in setOf("title", "headline", "body", "caption", "label")
        if (!styleOk) v.add(Violation("$path.text.style", "非法 style：${node.typography}（已忽略，回退默认）", Severity.WARN))
        val alignOk = node.align == null || node.align in setOf("start", "center", "end")
        if (!alignOk) v.add(Violation("$path.text.align", "非法 align：${node.align}（已忽略，回退默认）", Severity.WARN))
        // 修复：原 COLOR_RE 只认 #RRGGBB/#AARRGGBB，但 QuroUiColor.parse 与 prompt
        // （EXAMPLE 里就写 "color":"muted"）都支持命名色 red/muted/primary/#RGB，
        // 这里把所有命名字体颜色误判为非法并置 null，与工具定义冲突。
        // 改用 QuroUiColor.parse 作为合法判据，三方对齐。
        val colorOk = node.color == null || QuroUiColor.parse(node.color) != null
        if (!colorOk) v.add(Violation("$path.text.color", "非法颜色：${node.color}（已忽略）", Severity.WARN))
        return node.copy(
            typography = if (styleOk) node.typography else null,
            align = if (alignOk) node.align else null,
            color = if (colorOk) node.color else null,
        )
    }

    private fun validateProgress(node: QuroProgressNode, path: String, v: MutableList<Violation>): QuroUiNode {
        val p = node.progress ?: return node
        if (p < 0f || p > 1f) {
            v.add(Violation("$path.progress", "progress 越界 $p（已 clamp 到 [0,1]）", Severity.WARN))
            return node.copy(progress = p.coerceIn(0f, 1f))
        }
        return node
    }

    private fun validateSlider(node: QuroSliderNode, path: String, v: MutableList<Violation>): QuroUiNode {
        var (min, max) = node.min to node.max
        if (min > max) {
            v.add(Violation("$path.slider", "min>max（$min>$max），已交换", Severity.WARN))
            val t = min
            min = max
            max = t
        }
        return node.copy(min = min, max = max, value = node.value.coerceIn(min, max))
    }

    private fun degradeText(path: String, msg: String, v: MutableList<Violation>): QuroTextNode {
        v.add(Violation(path, "$msg（已降级为静态文本）", Severity.DEGRADE))
        return QuroTextNode(value = "⚠️ $msg", typography = "caption")
    }

    private fun isSafeUrl(url: String): Boolean {
        if (url.isBlank()) return false
        return url.startsWith("http://", true) ||
                url.startsWith("https://", true) ||
                url.startsWith("data:", true) ||
                url.startsWith("content://", true)
    }
}
