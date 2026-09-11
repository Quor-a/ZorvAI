package com.zorv.genui.prompt

/**
 * 模型契约（#634 / 方案 §3，A2UI 修订版）。
 *
 * 重要修订（A2UI 铁律）：模型输出永远是「数据」不是「代码」。
 * 本 prompt 不再教模型写 ```zorv/ui lang=jsx 并在 WebView 里执行 JS（那是 Play / App Store 2.5.2 红线），
 * 而是教它写 **quro-ui 组合式原生 DSL**——一段 JSON 节点树，由客户端原生解释器
 * （QuroUiDslParser + A2uiInterpreter + QuroUiCatalog + QuroUiRenderer）翻译成真实原生控件。
 *
 * 这条 prompt 通过 [SYSTEM_PROMPT] 拼进对话 system；[FEW_SHOT] 作为首轮 few-shot 示范。
 * 与 [com.ai.assistance.quro.ui.QuroChatViewModel] 里「动态 UI（quro-ui 原生组件）」段落保持一致口径。
 */
object GenUiPrompt {

    val SYSTEM_PROMPT: String = """
# 动态 UI 输出规范（A2UI：原生、声明式、数据不是代码）

## 何时生成 UI
满足以下任一条件时，用 UI 卡片替代或补充纯文字回答：
- 数据在对比、趋势、占比上更适合可视化（表格、图表占位、清单）
- 用户需要反复调整参数才能得到结果（计算器、模拟器）
- 内容是结构化清单且需要交互（待办、行程、选项器）
- 用户明确要求"做个界面/画个图/给我一个工具"

纯解释性、纯叙述性内容不要生成 UI。

## 输出格式（数据，不是代码）
在 Markdown 正文中用围栏，info string 必须是：
```quro-ui
（即 ```` ```quro-ui ```` 后跟一棵 JSON 节点树；不要写 JSX / HTML / 任何代码）

## 组合式节点树（关键：不是固定卡片，是节点任意嵌套）
根是单个 JSON 对象，用 `type` 指明组件，用 `children` 放子节点，可任意嵌套：
- 布局：column（纵向）/ row（横向）/ box（层叠）/ card（卡片容器，可带 title）
- 内容：text / image / icon / badge / progress / divider / spacer
       / markdown（原生 Markdown 排版，不是 HTML）
       / video / audio / browser（内嵌播放器/浏览器）/ code（代码块，runnable 可运行）
- 交互：button / text_input / checkbox / switch / select / slider / list / tabs
       每个交互节点必须有 `id`（用于收集输入值回传）

## 数据绑定（结构 / 数据分离）
文本或标签字段里可写 `@/path/to/field` 引用数据模型，例如 `{"type":"text","value":"@/booking/date"}`。
客户端会按 JSON Pointer 把 `@/...` 替换为最新数据；`updateDataModel` 推来新值时原地刷新，不用重发整棵树。

## 动作（写在 button.action 或 card.on_click 上，动作也是数据）
- callback：把表单值作为用户消息回发给你
- tool_call：客户端直连执行内置工具（run_code / http_request / launch_app 等），结果回传
- skill：激活已安装技能
- open_url：应用内浏览器打开网页
- copy：写入剪贴板
- open_app：真实启动第三方 App（包名或应用名）
- toggle：切换节点显隐
- open_screen / render_html / render_vispro(mermaid) / visual_popup / visual_ask：打开 ZorvAI 内部界面或弹层

## 约束（违反将降级为静态文本，绝不执行代码）
1. 只能使用上面列出的组件与动作（白名单，即 Catalog）；未知组件/动作会被校验降级。
2. 绝对不要输出 JavaScript / JSX / HTML / CSS 让端上执行——本端没有代码执行运行时，那是红线。
3. 所有内容都是 JSON 节点树，不是可运行代码；颜色用 #RRGGBB。
4. 单个节点写错不影响整棵树：解析器会把它降级成一行提示文本，其余正常渲染。

## 布局约束（移动端，宽度 320-430dp）
- 根不要设固定 width/height，宽度撑满父容器，高度由内容决定
- 列表/横向内容可能溢出时交给 column/row 的 `scrollable:true`
- 最小点击区域尽量 ≥ 44x44dp
""".trimIndent()

    val FEW_SHOT: String = """
用户：帮我做个简单的「今日待办」界面，能勾选完成。

助手：
好的，这是一个可勾选的待办卡片：

```quro-ui
{
  "type": "card",
  "title": "今日待办",
  "children": [
    { "type": "text", "value": "完成下面的事项后勾选：", "style": "body" },
    { "type": "checkbox", "id": "t1", "label": "写周报" },
    { "type": "checkbox", "id": "t2", "label": "回复邮件", "checked": true },
    {
      "type": "button", "label": "提交完成项",
      "action": { "type": "callback", "event": "todo_done", "collectFrom": ["t1", "t2"] }
    }
  ]
}
```
""".trimIndent()

    /** 拼装完整 system（可在此追加产品定制段落） */
    fun build(): String = build("html")

    /**
     * 按【对话框选择的 GenUI 渲染通道】拼装 system。
     *
     * 一个对话框可随时切换渲染通道（网页 / 原生布局 / Compose / 画布），切换即生效于后续生成。
     * 通道决定模型该往「什么观感、什么结构」上靠——端上 A2UI 解释器统一把 quro-ui DSL
     * 翻译成真实原生控件，因此这里不改 DSL 语法，只调整输出的风格侧重与题材导向，
     * 让"切换模式"在生成结果上真实可见。
     */
    fun build(mode: String): String = buildString {
        append(SYSTEM_PROMPT)
        append("\n\n")
        append(GenUiModes.byId(mode).promptSection())
    }
}

/**
 * GenUI 渲染通道注册表 —— 对话框里"切换模式"的唯一事实来源。
 * 顺序即切换器里的展示顺序；id 即持久化到会话 meta 的 genUiMode 值。
 */
object GenUiModes {
    data class Mode(
        val id: String,
        val label: String,
        /** 切换器副标题（简短说明该模式侧重） */
        val hint: String,
        /** 注入 system 的通道专属段落 */
        val prompt: String
    ) {
        fun promptSection(): String = buildString {
            appendLine("## 当前对话框 GenUI 渲染通道：$label（$id）")
            appendLine(prompt)
        }
    }

    val ALL: List<Mode> = listOf(
        Mode(
            id = "html",
            label = "网页通道",
            hint = "HTML/CSS 风格 · 最灵活",
            prompt = """
你应优先产出**网页质感**的可交互界面：卡片用圆角阴影、间距宽松、配色明快，
图表/表单/清单尽量用 quro-ui 的 card / list / tabs / slider 等节点组合出接近 Web 页面的视觉。
可大量使用 markdown 节点承载富文本与表格，整体观感对标一个精致的 H5 页面。
            """.trimIndent()
        ),
        Mode(
            id = "xml",
            label = "原生布局",
            hint = "安卓原生控件观感",
            prompt = """
你应优先产出**系统原生控件质感**的界面：多用真实的输入/选择类节点
（text_input / checkbox / switch / select / slider / list），
观感对标 Android 设置页/原生表单——扁平、克制的间距、系统级控件精确对齐。
少用装饰性阴影，强调"这是能直接操作系统能力的原生面板"。
            """.trimIndent()
        ),
        Mode(
            id = "compose",
            label = "Compose",
            hint = "Material 声明式",
            prompt = """
你应优先产出**Material Design 风格**的声明式界面：用 column / row / card / box 做清晰的信息层级，
圆角、 elevation、分类色板遵循 Material 3 审美；组件之间用一致的 padding 与 gap 形成呼吸感。
多数列表面板、仪表盘、工具页都按"Material 组件树"方式组织节点。
            """.trimIndent()
        ),
        Mode(
            id = "canvas",
            label = "画布",
            hint = "绘制优先 · 视觉化",
            prompt = """
你应优先产出**绘制/可视化优先**的界面：信息图、仪表盘、进度环、插画风卡片、数据可视化占位，
用大色块、图形、渐变与排版构成强烈视觉冲击，弱化表单、强调"画面"。
适合做封面式汇报卡、统计看板、品牌风格页。结构仍用 quro-ui DSL 节点，但视觉上对标一张设计稿。
            """.trimIndent()
        )
    )

    fun byId(id: String): Mode = ALL.firstOrNull { it.id == id } ?: ALL.first()
}
