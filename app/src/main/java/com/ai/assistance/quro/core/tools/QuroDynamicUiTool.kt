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
【节点类型】每个节点必须有 "type" 字段。所有节点都可带 "id"（交互节点必填，用于收集用户输入），
且**任何节点都能挂一个通用 "style" 对象**描述视觉样式（背景/边框/圆角/阴影/边距/尺寸/对齐/显隐），
让你像写 Compose 一样自由描述任意元素的样子——见文末【通用样式 style（v1.0.83）】。

■ 布局
- column：纵向排列。children[], spacing, padding, align(start|center|end), scrollable
- row：横向排列。children[], spacing, padding, align(top|center|bottom), scrollable
- box：层叠。children[], padding
- card：卡片容器。children[], title, padding, corner_radius, on_click(动作)
- pane：多 pane 布局容器（宽屏并排 / 窄屏竖排，WindowSizeClass 风格）。children[], direction(auto|row|column，默认 auto 跟随容器宽度),
  spacing, padding。direction=auto 时容器宽度≥840dp 的格并排、否则竖排；row 强制横排、column 强制竖排。
  每个子区块内部按 360dp 设计稿独立等比缩放并贴满所占那一格，并排时每格更窄但内容不会溢出。
  子区块若自身带 weight（column/row/box/card 支持，如 1 与 2）则按权重分配并排宽度，实现「侧栏 1 : 主区 2」非等比双栏。
  用于「主从双栏」「表单+预览」这类需要随宽度自适应并排/竖排的场景。

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
- list：列表。items[]（每项可为普通字符串或 JSON 对象字符串）, item(模板节点，可用 {{item}}、{{item.field}}、{{index}} 占位), max_height
- tabs：标签页。tabs[{title, node}]

■ 富媒体 / 文档（v1.0.81 新增，原生渲染，非 HTML/WebView）
- markdown：原生 Markdown 富文本排版（不是 HTML）。value（也接受 value/content/text）；
  支持 #/##/### 标题、有序/无序列表、引用、加粗/斜体、行内代码、链接（点击在应用内打开）、围栏代码块。
  适合「文档排版」「说明文字」「条款/协议」这类纯文本格式化场景。
- video：内嵌视频播放（原生播放器）。url(支持 http(s)/本地路径/content://)，title
- audio：内嵌音频/音乐播放（原生播放器，带播放/暂停与进度条）。url, title
- image：图片（见上「内容」段，已原生支持）。url, alt, height, corner_radius
- browser：内嵌完整功能浏览器（WebView，支持 JS / DOM 存储 / 缩放 / 前进后退）。
  url, height(可选 dp 高度，默认 320)。底部自带「在浏览器打开」「刷新」按钮。
- html：自写 HTML（v1.0.84 新增）。AI 完全自写 HTML/CSS/JS，直接内联渲染到对话气泡。
  html(完整 HTML 源码，也接受 content/value), height(可选 dp，默认 400，上限 600)。
  这是「自写 UI」核心节点——当 DSL 白名单节点不够用时（复杂表单、图表、动画、小工具），
  直接写一个 html 节点放任意 HTML 即可，无需 WebView 跳转。长度上限 100KB。
- code：代码块（展示 ZorvAI 支持的所有语言，语法高亮）。code(也接受 code/content/value), lang,
  title, runnable(为 true 时显示「运行」按钮，将经 run_code 真正执行并把结果回传给你)。

■ 第三方跳转
- 任意 button 的 action 用 {"type":"open_app","package_name":"com.example"} 或
  {"type":"open_app","app_name":"微信"} 即可把用户带去第三方 App（精确包名或应用名模糊匹配）。
  想让用户在一个动态 UI 里「先选功能 → 点按钮跳去别的软件」直接这么写即可。

【颜色】支持 #RGB / #RRGGBB / #AARRGGBB，或语义名：
red green blue yellow orange purple pink teal indigo gray primary secondary error warning success info muted
越界或非法颜色会被忽略，回落到主题默认色，不会导致渲染失败。

【通用样式 style（v1.0.83）】任意节点都能挂一个 "style" 对象，让 AI 自由写任意样式。
两种等价写法（解析器都会收进同一个通用样式对象）：
  ① 嵌套对象：{"type":"box","style":{"backgroundColor":"#fff","borderRadius":12,"padding":8},"children":[...]}
  ② 顶层平铺（style 的字段直接放节点上）：{"type":"box","backgroundColor":"#fff","borderRadius":12,"padding":8,"children":[...]}
字段一览（全部可选，非法值回落默认）：
  - backgroundColor / background：背景色（"#fff"），或 {"color":"#fff"}。也支持 gradient：
      {"gradient":{"colors":["#ff9a9e","#fad0c4"],"direction":"vertical"}}  // direction: vertical|horizontal|radial|diagonal
  - borderColor / borderWidth：边框颜色与宽度(dp)
  - borderRadius：圆角(dp)
  - shadowElevation / shadowColor：阴影高度(dp)与颜色
  - padding / margin：内/外边距。数字=四边同值；对象可单边 {top,bottom,start,end,horizontal,vertical,all}
  - width / height：尺寸。"fill"=撑满父容器；"auto"=自适应内容；数字或 {"fixed":N}=固定 N dp；{"weight":N}=按比例分配
  - maxWidth / maxHeight：最大宽高(dp)
  - opacity：透明度 0~1（也接受 0~100，会自动 ÷100）
  - align：父容器内对齐。column 里用 start|center|end（控水平）；row 里用 top|center|bottom（控垂直）
  - visible：false 则该节点整体不渲染（数据驱动显隐，不惊动模型）
注意：模型输出永远是「数据」不是「代码」——再自由的样式也只是声明，端上忠实渲染成原生控件，
且任意字段非法都会回落默认值，绝不会导致渲染失败。

【未知节点类型】若写了白名单之外的 type（如拼写错或自定义组件），系统不会崩溃，
而是把该节点降级为一个「带通用样式的竖向容器」（保留你给的 style 与 children，并在顶部追加一行降级提示），
保证「单个节点坏掉不影响整棵树」。请优先从上面白名单里挑 type。
""".trimIndent()

        val ACTION_SPEC = """
【动作】写在 button 的 "action" 或 card 的 "on_click" 上。

- {"type":"callback","event":"事件名","collect_from":["控件id"],"data":{"键":"值"}}
  → 把收集到的表单值作为用户消息回发给你（AI），你据此继续处理。最常用。
- {"type":"tool_call","tool":"工具名","arguments":{"键":"值"},"collect_from":["控件id"]}
  → 客户端直连 ZorvAI 真实执行该内置工具（如 get_battery / run_code / http_request / launch_app /
    set_clipboard 等全部内部功能），不是只回发文本。工具返回的结果会回传给你继续组织回复。
  · run_code 支持 ZorvAI 所有已接入语言：python, node/javascript, shell/bash, html, json, css,
    xml, svg, c, cpp, java, kotlin, dart, go, rust, php, ruby, swift 等。配合 code 节点的
    runnable 按钮可直接把代码交给 run_code 真正跑起来。
- {"type":"skill","skill":"技能名","input":"输入","collect_from":["控件id"]}
  → 客户端直连激活已安装技能（技能指令回灌给你），不是只回发文本。
- {"type":"open_url","url":"https://..."} → 客户端真实在应用内浏览器打开网页。
- {"type":"copy","text":"要复制的文本","label":"提示"} → 客户端真实写入系统剪贴板（即时可粘贴）。
- {"type":"open_app","package_name":"com.example"} → 客户端真实启动应用（支持精确包名或应用名模糊匹配）。
  也支持 {"type":"open_app","app_name":"微信"} 用应用名模糊匹配；这是「动态 UI 弹转第三方软件」的标准做法。
- {"type":"toggle","target_id":"节点id"} → 切换目标节点的显示/隐藏（纯本地，不打扰你）。

■ 多层渲染 / 深链导航（v1.0.81 新增，让按钮直接打开 ZorvAI 内部界面或渲染内容到气泡）
- {"type":"open_screen","target":"terminal"} → 客户端直接打开内置界面（深链）。target 取值：
  editor(代码编辑器) / terminal(终端) / toolbox(工具箱) / knowledge(知识库) / cms(内容管理) /
  aci(受控端) / about(关于) / appearance(外观) / soul(人格) / memory(记忆) / permission(权限) /
  model_config(模型配置) / voice(语音) / settings(设置) / tool_center(工具中心)，
  以及工具中心子能力 vispro(可视化编程) / node_editor(流程图) / miniapp(小程序) / workbench(工作台)。
  例：点「打开终端」按钮 → 直接进入终端界面，无需你再解析指令。
- {"type":"render_html","html":"<h1>你好</h1>"} → 把 HTML 直接渲染进对话气泡（第一层渲染，复用小程序运行时），
  不是弹出新页面，用户就在聊天里看到渲染结果。也可写 "type":"miniapp"。
- {"type":"render_vispro","source":"graph TD;A-->B"} → 把 mermaid 源码直接渲染成可视化图进对话气泡（第一层渲染），
  也可写 "type":"mermaid"。
- {"type":"visual_popup","title":"提示","content":"这是一段说明文字"} → 在对话上方弹出可视化信息框（纯展示）。
- {"type":"visual_ask","prompt":"请选择操作","options":["方案A","方案B","取消"]} → 弹出选项让用户点选，
  选中项会作为用户消息回发给你继续对话（轻量版「询问」，比 callback 表单更简单）。

【多层渲染说明】动态 UI 组件既能渲染在第一层（如上面 render_html / render_vispro 直接进气泡），
也能作为导航中枢：按钮点进去可进终端、模型配置、可视化编程、小程序等任意内置界面（open_screen），
或弹可视化弹窗 / 询问（visual_popup / visual_ask）。所有动作均在客户端真调用，无需你二次解析。

【重要】以上动作在客户端即「真调用」，无需你再二次解析文本去执行——点一下按钮就落地（复制/打开应用/
打开网页/跑工具/激活技能/进内置界面/渲染内容/弹窗询问）。tool_call 与 skill 的执行结果会回传给你，便于你接着对话。
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

【富媒体 / 文档 示例】一个「产品介绍」动态 UI：

```quro-ui
{"type":"card","title":"ZorvAI 介绍","children":[
  {"type":"markdown","value":"# ZorvAI\\n这是一款**原生 AI 助手**应用。\\n- 支持文档排版\\n- 支持音视频播放"},
  {"type":"image","url":"https://example.com/cover.png","height":160,"corner_radius":12},
  {"type":"video","url":"https://example.com/demo.mp4","title":"功能演示"},
  {"type":"audio","url":"https://example.com/speech.mp3","title":"语音解说"},
  {"type":"code","lang":"python","runnable":true,"code":"print('hello ZorvAI')"},
  {"type":"browser","url":"https://zorvai.example.com","height":360},
  {"type":"button","label":"打开第三方 App","variant":"filled",
   "action":{"type":"open_app","app_name":"微信"}},
  {"type":"button","label":"在应用内打开官网","variant":"outlined",
   "action":{"type":"open_url","url":"https://zorvai.example.com"}}
]}
```

【多 pane 布局 示例】一个「编辑 + 预览」双栏（宽屏并排 / 窄屏竖排，自动切换）：

```quro-ui
{"type":"card","title":"个人资料","children":[
  {"type":"pane","direction":"auto","spacing":12,"children":[
    {"type":"column","children":[
      {"type":"text","value":"左侧：编辑区","style":"label","color":"muted"},
      {"type":"text_input","id":"name","label":"昵称","placeholder":"输入昵称"},
      {"type":"switch","id":"public","label":"公开资料","checked":true}
    ]},
    {"type":"column","children":[
      {"type":"text","value":"右侧：预览区","style":"label","color":"muted"},
      {"type":"badge","text":"预览","background":"primary"},
      {"type":"markdown","value":"昵称会实时显示在这里。"}
    ]}
  ]}
]}
```
说明：pane 的 direction=auto 时容器宽 ≥840dp 自动并排、否则竖排；每个子区块内部都按 360dp 设计稿等比缩放，并排时每格更窄但内容不溢出。
也可写 direction:"row" 强制横排、direction:"column" 强制竖排。嵌套结构（如 list 的 itemTemplate 为 pane、pane 内再套 pane）也都支持，动态更新同样下钻生效。

【语言支持】code / run_code 支持 python, node/javascript, shell/bash, html, json, css, xml,
svg, c, cpp, java, kotlin, dart, go, rust, php, ruby, swift 等 ZorvAI 已接入的全部语言；
选择在 code 节点的 lang 字段标注即可。
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
