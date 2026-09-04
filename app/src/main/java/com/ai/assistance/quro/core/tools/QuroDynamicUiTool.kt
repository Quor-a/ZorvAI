package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.ui.dynamicui.QuroUiColor
import com.ai.assistance.quro.core.ui.dynamicui.QuroUiDslParser
import com.ai.assistance.quro.core.ui.dynamicui.QuroUiIcons
import com.ai.assistance.quro.core.ui.dynamicui.QuroUiNode
import com.ai.assistance.quro.core.ui.dynamicui.QuroUiParseResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * 动态 UI 配套工具（参照 Kai 的 `kai-ui` 能力重写）。
 *
 * 为什么需要工具而不只是「让 AI 自己写代码块」：
 *  - [UiDslSpecTool] 让模型**按需**拉取完整 DSL 规范，避免把冗长 schema 常驻系统提示词白白烧 token；
 *  - [UiValidateTool] 让模型在输出前自检，把「渲染失败」从事后崩溃变成事前修正。
 *
 * UI 的主渲染路径是：模型在回复里写 ```quro-ui 代码块 → 消息层检测 → [QuroUiRenderer] 原生渲染。
 * 工具只负责「教会模型」与「让模型自检」两件事。
 */

/** 拉取动态 UI 的 DSL 规范（节点类型、字段、动作、示例）。 */
class UiDslSpecTool : QuroTool {
    override val name = "ui_dsl_spec"
    override val description =
        "获取「动态 UI」的 DSL 规范：可用的节点类型、字段、动作以及完整示例。" +
            "当你需要生成一个可交互的原生界面（表单、卡片、设置面板、数据展示等）时，" +
            "先调用本工具了解规范，然后在回复中输出 ```quro-ui 代码块即可渲染成真实原生控件。"

    override val parametersJson: String = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("section", JSONObject().apply {
                put("type", "string")
                put("description", "要查看的章节：nodes(节点) / actions(动作) / example(示例) / icons(可用图标名)。留空返回全部。")
                put("enum", JSONArray().apply {
                    put("nodes"); put("actions"); put("example"); put("icons")
                })
            })
        })
        put("required", JSONArray())
    }.toString()

    override fun run(context: Context, arguments: String): String {
        val section = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
            .optString("section", "").trim().lowercase()

        return when (section) {
            "nodes" -> NODE_SPEC
            "actions" -> ACTION_SPEC
            "example" -> EXAMPLE
            "icons" -> "可用图标名（部分，未列出的名字会回落为 info 图标，不会报错）：\n" +
                QuroUiIcons.availableNames().joinToString(", ")
            else -> "===== 动态 UI DSL 规范 =====\n\n" +
                "用法：在回复中直接输出 ```quro-ui 围栏代码块，内容为 JSON 节点树，\n" +
                "系统会自动渲染为原生可交互控件（不是 HTML，不需要 WebView）。\n\n" +
                NODE_SPEC + "\n" + ACTION_SPEC + "\n" + EXAMPLE
        }
    }

    private companion object {
        val NODE_SPEC = """
【节点类型】每个节点必须有 "type" 字段。所有节点都可带 "id"（交互节点必填，用于收集用户输入）。

■ 布局
- column：纵向排列。children[], spacing, padding, align(start|center|end), scrollable
- row：横向排列。children[], spacing, padding, align(top|center|bottom), scrollable
- box：层叠。children[], padding
- card：卡片容器。children[], title, padding, corner_radius, on_click(动作)

■ 内容
- text：文本。value, style(title|headline|body|caption|label), bold, italic, color, size, align, max_lines
- image：图片。url(支持 http(s) 与 data:image/png;base64,...), alt, height, corner_radius
- icon：图标。name(见 ui_dsl_spec section=icons), size, tint
- badge：胶囊标签。text, color, background
- progress：进度条。progress(0.0~1.0，省略则显示不确定动画), label
- divider：分隔线。thickness
- spacer：空白。height / width

■ 交互（必须有 id）
- button：按钮。label, action(动作), variant(filled|outlined|text), icon
- text_input：输入框。label, placeholder, value, multiline, lines, input_type(text|number|password|email|phone)
- checkbox：复选框。label, checked
- switch：开关。label, checked
- select：下拉选择。label, options[], selected
- slider：滑块。label, value, min, max, step
- list：列表。items[], item(模板节点，可用 {{item}} 与 {{index}} 占位), max_height
- tabs：标签页。tabs[{title, node}]

【颜色】支持 #RGB / #RRGGBB / #AARRGGBB，或语义名：
red green blue yellow orange purple pink teal indigo gray primary secondary error warning success info muted
越界或非法颜色会被忽略，回落到主题默认色，不会导致渲染失败。
""".trimIndent()

        val ACTION_SPEC = """
【动作】写在 button 的 "action" 或 card 的 "on_click" 上。

- {"type":"callback","event":"事件名","collect_from":["控件id"],"data":{"键":"值"}}
  → 把收集到的表单值作为用户消息回发给你（AI），你据此继续处理。最常用。
- {"type":"tool_call","tool":"工具名","arguments":{"键":"值"},"collect_from":["控件id"]}
  → 客户端直连 ZorvAI 真实执行该内置工具（如 get_battery / run_code / http_request / launch_app /
    set_clipboard 等全部内部功能），不是只回发文本。工具返回的结果会回传给你继续组织回复。
- {"type":"skill","skill":"技能名","input":"输入","collect_from":["控件id"]}
  → 客户端直连激活已安装技能（技能指令回灌给你），不是只回发文本。
- {"type":"open_url","url":"https://..."} → 客户端真实在应用内浏览器打开网页。
- {"type":"copy","text":"要复制的文本","label":"提示"} → 客户端真实写入系统剪贴板（即时可粘贴）。
- {"type":"open_app","package_name":"com.example"} → 客户端真实启动应用（支持精确包名或应用名模糊匹配）。
- {"type":"toggle","target_id":"节点id"} → 切换目标节点的显示/隐藏（纯本地，不打扰你）。

【重要】以上动作在客户端即「真调用」，无需你再二次解析文本去执行——点一下按钮就落地（复制/打开应用/
打开网页/跑工具/激活技能）。tool_call 与 skill 的执行结果会回传给你，便于你接着对话。
【收集表单值】collect_from 填交互控件的 id 数组；留空则收集整个表单的全部值。
用户点击后，值会以「键=值」的形式回发，你就能拿到用户输入继续干活。
""".trimIndent()

        val EXAMPLE = """
【示例】一个「日报生成」表单：

```quro-ui
{"type":"card","title":"生成日报","children":[
  {"type":"text","value":"选择范围并补充要点","style":"caption","color":"muted"},
  {"type":"select","id":"range","label":"时间范围","options":["今天","本周","本月"],"selected":"今天"},
  {"type":"text_input","id":"highlights","label":"本阶段要点","placeholder":"用一句话概括","multiline":true,"lines":3},
  {"type":"row","children":[
    {"type":"checkbox","id":"include_todo","label":"包含待办","checked":true},
    {"type":"switch","id":"notify","label":"生成后提醒我","checked":false}
  ]},
  {"type":"divider"},
  {"type":"row","children":[
    {"type":"button","label":"生成","variant":"filled",
     "action":{"type":"callback","event":"generate_report","collect_from":["range","highlights","include_todo","notify"]}},
    {"type":"button","label":"取消","variant":"text","action":{"type":"toggle","target_id":"form"}}
  ]}
]}
```

输出时注意：
1. 代码块语言标识必须写 quro-ui；
2. 内容只放 JSON，不要写注释、不要加解释文字；
3. 每个交互控件都要有唯一 id，否则无法收集用户输入；
4. 不确定语法是否正确时，可先用 ui_validate 工具自检。
""".trimIndent()
    }
}

/**
 * 校验 AI 写的 UI DSL 能否被正确解析。
 * 让模型在正式输出前把 JSON 拿来跑一遍，解析失败会给出修复建议。
 */
class UiValidateTool : QuroTool {
    override val name = "ui_validate"
    override val description =
        "校验一段动态 UI 的 DSL（JSON）能否被正确解析。输出前先自检，可避免渲染失败。" +
            "返回解析结果、修复后的 JSON（若语法有误）以及节点统计。"

    override val parametersJson: String = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("dsl", JSONObject().apply {
                put("type", "string")
                put("description", "待校验的 UI DSL JSON（节点树，不要带 ``` 围栏）")
            })
        })
        put("required", JSONArray().apply { put("dsl") })
    }.toString()

    override fun run(context: Context, arguments: String): String {
        val dsl = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
            .optString("dsl", "").trim()
        if (dsl.isBlank()) return "未提供 dsl 参数"

        return when (val result = QuroUiDslParser.parseBlock(dsl)) {
            is QuroUiParseResult.Success -> {
                val stats = countNodes(result.root)
                val ids = collectIds(result.root)
                buildString {
                    appendLine("✅ 解析成功")
                    appendLine("节点总数：$stats")
                    if (ids.isNotEmpty()) appendLine("交互控件 id：${ids.joinToString(", ")}")
                    else appendLine("提示：未发现任何带 id 的交互控件，将无法收集用户输入。")
                    val dupIds = ids.groupBy { it }.filter { it.value.size > 1 }.keys
                    if (dupIds.isNotEmpty()) {
                        appendLine("⚠️ 存在重复 id：${dupIds.joinToString(", ")}，会导致取值互相覆盖，请改为唯一。")
                    }
                    val unresolved = collectIconNames(result.root).filter { name ->
                        // 无法直接比对 ImageVector，用「是否命中别名表」近似判断
                        !ICON_NAMES.contains(name)
                    }
                    if (unresolved.isNotEmpty()) {
                        appendLine("⚠️ 以下图标名未收录，将回落为 info 图标：${unresolved.joinToString(", ")}")
                    }
                }.trim()
            }
            is QuroUiParseResult.Failure -> buildString {
                appendLine("❌ 解析失败：${result.reason}")
                appendLine("系统已尝试自动修复，修复后的 JSON：")
                appendLine(result.rawJson.take(2000))
                appendLine("请修正后重新输出，或改用更简单的结构。")
            }.trim()
        }
    }

    /** 递归统计节点数。 */
    private fun countNodes(node: com.ai.assistance.quro.core.ui.dynamicui.QuroUiNode): Int {
        val children = childrenOf(node)
        return 1 + children.sumOf { countNodes(it) }
    }

    /** 收集所有交互节点 id。 */
    private fun collectIds(node: com.ai.assistance.quro.core.ui.dynamicui.QuroUiNode): List<String> {
        val self = when (node) {
            is com.ai.assistance.quro.core.ui.dynamicui.QuroTextInputNode -> listOf(node.id)
            is com.ai.assistance.quro.core.ui.dynamicui.QuroCheckboxNode -> listOf(node.id)
            is com.ai.assistance.quro.core.ui.dynamicui.QuroSwitchNode -> listOf(node.id)
            is com.ai.assistance.quro.core.ui.dynamicui.QuroSelectNode -> listOf(node.id)
            is com.ai.assistance.quro.core.ui.dynamicui.QuroSliderNode -> listOf(node.id)
            else -> emptyList()
        }
        return self + childrenOf(node).flatMap { collectIds(it) }
    }

    private fun collectIconNames(node: com.ai.assistance.quro.core.ui.dynamicui.QuroUiNode): List<String> {
        val self = when (node) {
            is com.ai.assistance.quro.core.ui.dynamicui.QuroIconNode -> listOf(node.name.lowercase())
            is com.ai.assistance.quro.core.ui.dynamicui.QuroButtonNode ->
                node.icon?.let { listOf(it.lowercase()) } ?: emptyList()
            else -> emptyList()
        }
        val badColors = checkColors(node)
        return self + badColors + childrenOf(node).flatMap { collectIconNames(it) }
    }

    /** 颜色非法时把原值回传，用于提示模型修正。 */
    private fun checkColors(node: com.ai.assistance.quro.core.ui.dynamicui.QuroUiNode): List<String> {
        val raw = when (node) {
            is com.ai.assistance.quro.core.ui.dynamicui.QuroTextNode -> node.color
            is com.ai.assistance.quro.core.ui.dynamicui.QuroIconNode -> node.tint
            is com.ai.assistance.quro.core.ui.dynamicui.QuroBadgeNode -> node.color
            else -> null
        }
        return if (raw != null && QuroUiColor.parse(raw) == null) listOf(raw) else emptyList()
    }

    /** 取任意节点的子节点列表（含 card/column/row/box/list 模板/tabs 内容）。 */
    private fun childrenOf(node: com.ai.assistance.quro.core.ui.dynamicui.QuroUiNode):
        List<com.ai.assistance.quro.core.ui.dynamicui.QuroUiNode> {
        return when (node) {
            is com.ai.assistance.quro.core.ui.dynamicui.QuroColumnNode -> node.children
            is com.ai.assistance.quro.core.ui.dynamicui.QuroRowNode -> node.children
            is com.ai.assistance.quro.core.ui.dynamicui.QuroBoxNode -> node.children
            is com.ai.assistance.quro.core.ui.dynamicui.QuroCardNode -> node.children
            is com.ai.assistance.quro.core.ui.dynamicui.QuroListNode ->
                node.itemTemplate?.let { listOf(it) } ?: emptyList()
            is com.ai.assistance.quro.core.ui.dynamicui.QuroTabsNode -> node.tabs.mapNotNull { it.node }
            else -> emptyList()
        }
    }

    private val ICON_NAMES: Set<String> by lazy { QuroUiIcons.availableNames().toSet() }
}
