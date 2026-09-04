package com.ai.assistance.quro.core.ui.dynamicui

import org.json.JSONArray
import org.json.JSONObject

/**
 * A2UI 第④层：JSON Pointer 数据绑定（RFC 6901）。
 *
 * 组件树描述「结构」，数据模型描述「数据」，两者通过指针分开：
 *  - 文本/标签字段里写 `@/path/to/field`（如 `@/booking/date`）即引用数据模型；
 *  - `updateDataModel` 推来新数据时，所有 `@/path` 就地解析为最新值 —— 结构不变、数据流动。
 *
 * 红线：指针只在「字符串字段里做文本替换」，绝不执行任何代码、绝不把数据当指令。
 */
object QuroUiPointer {

    /** 解析 JSON Pointer 为路径段（处理 ~0 ~1 转义）。 */
    fun parse(pointer: String): List<String> {
        if (pointer.isEmpty() || pointer == "/") return emptyList()
        require(pointer.startsWith("/")) { "JSON Pointer 必须以 / 开头：$pointer" }
        return pointer.removePrefix("/").split("/").map { it.replace("~1", "/").replace("~0", "~") }
    }

    /** 从嵌套 Map/List 结构按指针取值（越界/缺失返回 null）。 */
    fun resolve(model: Map<String, Any?>, pointer: String): Any? {
        var cur: Any? = model
        for (seg in parse(pointer)) {
            cur = when (cur) {
                is Map<*, *> -> cur[seg]
                is List<*> -> seg.toIntOrNull()?.let { if (it in cur.indices) cur[it] else null }
                else -> null
            } ?: return null
        }
        return cur
    }

    /** 把文本中的 `@/path` 替换为数据模型里对应值；找不到保留原样（不丢信息）。 */
    fun resolveText(text: String, model: Map<String, Any?>): String {
        if (!text.contains("@/")) return text
        return Regex("""@(/[^\s@]+)""").replace(text) { m ->
            resolve(model, m.groupValues[1])?.toString() ?: m.value
        }
    }

    /** JSON 对象 → 嵌套 Map<String, Any?>（供指针解析）。 */
    fun toModel(json: JSONObject): Map<String, Any?> = json.toMap()

    private fun JSONObject.toMap(): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        keys().forEach { k -> map[k] = wrap(get(k)) }
        return map
    }

    private fun wrap(v: Any?): Any? = when {
        v == null || v === JSONObject.NULL -> null
        v is JSONObject -> v.toMap()
        v is JSONArray -> List(v.length()) { i -> wrap(v.get(i)) }
        else -> v
    }

    /** 把指针绑定的「数据模型」应用到一棵节点树，返回解析后的新树（原树不变）。 */
    fun bindTree(root: QuroUiNode, model: Map<String, Any?>): QuroUiNode = bindNode(root, model)

    private fun rt(s: String?, model: Map<String, Any?>): String? = s?.let { resolveText(it, model) }

    private fun bindNode(node: QuroUiNode, model: Map<String, Any?>): QuroUiNode = when (node) {
        is QuroColumnNode -> node.copy(children = node.children.map { bindNode(it, model) })
        is QuroRowNode -> node.copy(children = node.children.map { bindNode(it, model) })
        is QuroBoxNode -> node.copy(children = node.children.map { bindNode(it, model) })
        is QuroCardNode -> node.copy(
            title = rt(node.title, model) ?: node.title,
            children = node.children.map { bindNode(it, model) },
            onClick = bindAction(node.onClick, model),
        )
        is QuroTabsNode -> node.copy(tabs = node.tabs.map {
            it.copy(title = rt(it.title, model) ?: it.title, node = it.node?.let { n -> bindNode(n, model) })
        })
        is QuroListNode -> node.copy(
            items = node.items.mapNotNull { rt(it, model) },
            itemTemplate = node.itemTemplate?.let { bindNode(it, model) },
        )
        is QuroTextNode -> node.copy(
            value = rt(node.value, model) ?: node.value,
            color = rt(node.color, model) ?: node.color,
            style = rt(node.style, model) ?: node.style,
            align = rt(node.align, model) ?: node.align,
        )
        is QuroImageNode -> node.copy(url = rt(node.url, model) ?: node.url, alt = rt(node.alt, model) ?: node.alt)
        is QuroIconNode -> node.copy(
            name = rt(node.name, model) ?: node.name,
            description = rt(node.description, model) ?: node.description,
            tint = rt(node.tint, model) ?: node.tint,
        )
        is QuroBadgeNode -> node.copy(
            text = rt(node.text, model) ?: node.text,
            color = rt(node.color, model) ?: node.color,
            background = rt(node.background, model) ?: node.background,
        )
        is QuroMarkdownNode -> node.copy(value = rt(node.value, model) ?: node.value)
        is QuroVideoNode -> node.copy(url = rt(node.url, model) ?: node.url, title = rt(node.title, model) ?: node.title)
        is QuroAudioNode -> node.copy(url = rt(node.url, model) ?: node.url, title = rt(node.title, model) ?: node.title)
        is QuroBrowserNode -> node.copy(url = rt(node.url, model) ?: node.url)
        is QuroCodeNode -> node.copy(
            code = rt(node.code, model) ?: node.code,
            lang = rt(node.lang, model) ?: node.lang,
            title = rt(node.title, model) ?: node.title,
        )
        is QuroButtonNode -> node.copy(
            label = rt(node.label, model) ?: node.label,
            icon = rt(node.icon, model) ?: node.icon,
            action = bindAction(node.action, model),
        )
        is QuroTextInputNode -> node.copy(
            label = rt(node.label, model) ?: node.label,
            placeholder = rt(node.placeholder, model) ?: node.placeholder,
            value = rt(node.value, model) ?: node.value,
        )
        is QuroCheckboxNode -> node.copy(label = rt(node.label, model) ?: node.label)
        is QuroSwitchNode -> node.copy(label = rt(node.label, model) ?: node.label)
        is QuroSelectNode -> node.copy(
            label = rt(node.label, model) ?: node.label,
            selected = rt(node.selected, model) ?: node.selected,
            options = node.options.mapNotNull { rt(it, model) },
        )
        is QuroSliderNode -> node.copy(label = rt(node.label, model) ?: node.label)
        is QuroProgressNode -> node.copy(label = rt(node.label, model) ?: node.label)
        is QuroDividerNode -> node
        is QuroSpacerNode -> node
    }

    private fun bindAction(action: QuroUiAction?, model: Map<String, Any?>): QuroUiAction? = when (action) {
        null -> null
        is QuroCallbackAction -> action.copy(
            event = rt(action.event, model) ?: action.event,
            data = action.data.mapValues { (_, v) -> rt(v, model) ?: v },
        )
        is QuroCopyAction -> action.copy(
            text = rt(action.text, model) ?: action.text,
            label = rt(action.label, model) ?: action.label,
        )
        is QuroOpenUrlAction -> action.copy(url = rt(action.url, model) ?: action.url)
        is QuroToolCallAction -> action.copy(
            tool = rt(action.tool, model) ?: action.tool,
            arguments = action.arguments.mapValues { (_, v) -> rt(v, model) ?: v },
        )
        is QuroSkillAction -> action.copy(
            skill = rt(action.skill, model) ?: action.skill,
            input = rt(action.input, model) ?: action.input,
        )
        is QuroOpenAppAction -> action.copy(packageName = rt(action.packageName, model) ?: action.packageName)
        is QuroOpenScreenAction -> action.copy(target = rt(action.target, model) ?: action.target)
        is QuroRenderHtmlAction -> action.copy(html = rt(action.html, model) ?: action.html)
        is QuroRenderVisproAction -> action.copy(source = rt(action.source, model) ?: action.source)
        is QuroVisualPopupAction -> action.copy(
            title = rt(action.title, model) ?: action.title,
            content = rt(action.content, model) ?: action.content,
        )
        is QuroVisualAskAction -> action.copy(
            prompt = rt(action.prompt, model) ?: action.prompt,
            options = action.options.mapNotNull { rt(it, model) },
        )
        is QuroToggleAction -> action
    }
}
