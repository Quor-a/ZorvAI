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
    fun build(): String = SYSTEM_PROMPT
}
