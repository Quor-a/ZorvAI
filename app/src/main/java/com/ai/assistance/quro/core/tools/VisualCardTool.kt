package com.ai.assistance.quro.core.tools

import android.content.Context

/**
 * 🔴 `visual_card` 工具 —— 可视化小卡片的**唯一出口**（2026-11-05 重写分类；2026-11-06 自由生成反转）。
 * AI 自由写卡体 HTML，对话框里以统一卡壳（Widget Lab 观感）内联渲染。
 *
 * ## 定位（用户拍板：可视化小卡片是独立分类）
 * 「可视化小卡片」**不属于动态UI组件、不属于富组件、不属于可视化弹窗、不属于网页预览**。
 * 它只指这一类：单块、只读、看完即走、内联在消息流里的仪表盘小组件。
 * 骨架只统一「卡壳」：圆角 20、头部（emoji+标题+副标题）、亮暗自适应、
 * 卡体 WebView（自动 viewport 兜底 / 内容不超屏 / https 外部资源）。
 *
 * ## 🔴 自由生成是主路（2026-11-06 用户拍板反转）
 * 「要的不是只支持内置小卡，要的是 AI 可以自己写出来没有的小卡片。」
 * 旧版把 `visual_card_skeleton` 说成「推荐两步/结构不发明」，模型于是只会从 13 份
 * 内置骨架里挑一张填数——库里没有的场景（收件箱/番茄钟/心率/温控/登机牌/汇率/
 * 战绩/直播…见设计稿 01/02 共 180+ 场景）就写不出来。
 * 现在：**html 参数本身就是完整的自由创作空间**，骨架只是「没把握时的参考样板」。
 * 任何场景都能直接写：布局 flex/grid、颜色用主题 token（--card/--border/--muted…）或
 * 固定 hex、图形用内联 SVG、等宽数据用 ui-monospace。没有「不在清单里就不能做」这回事。
 *
 * ## 与相邻通道的边界
 *  - 单块只读展示 → 本工具（一工具一卡）；
 *  - 要输入/按钮回传 → ```quro-ui 动态 UI 或 visual_popup（另一分类）；
 *  - 现成组件库标准件 → ui_card / ui_widget（富组件，另一分类）；
 *  - 完整网页/游戏 → ```html 网页预览（另一分类）。
 *
 * 执行本身只做参数校验与回执：真正渲染由聊天聚合层把本次 tool call
 * 转成 `QuroChatCard.VisualMiniCard` 内联展示（重启后由 toolCalls 重放还原）。
 */
class VisualCardTool : QuroTool {
    override val name = "visual_card"

    override val description = """visual_card：生成一张**可视化小卡片**，直接内联渲染在对话框里（用户无需点开）。
这是「可视化小卡片」的唯一出口，且是**独立分类**——不是动态UI组件（```quro-ui）、不是富组件（ui_card/ui_widget）、不是可视化弹窗（visual_popup）、不是网页预览（```html），严禁用它们顶替。

适合：**任何单块只读的仪表盘小组件**——天气 / 新闻 / 终端 / 日程 / 音乐 / 行情 / 待办 / 监控 / 位置 / 电量 / 时钟，以及收件箱、番茄钟、会议倒计时、项目进度、便签、活动圆环、心率、喝水、睡眠、灯光、温控、门锁、物流、打车、电影票、登机牌、停车、汇率、预算、支出、储蓄目标、自选股、订阅、报销、动态、播客、相册、战绩、直播、评分……**不限于任何清单，用户要什么场景就写什么场景。**

【自由写是主路】html 参数就是你的创作空间，直接写这个场景最合适的布局：
- 布局：flex / grid，宽度全部用 %（容器约 340dp 宽），多列用 grid-template-columns；
- 颜色：主文字 color:CanvasText（亮暗自适应）；线/底用 rgba(128,138,160,.x) 或主题 token（--card/--border/--muted/--primary 已注入）；强调色用固定 hex（蓝 #4f6bff / 青 #18b89a / 橙 #ff9a4c / 红 #ff5f6d；行情涨红跌绿）；
- 图形：进度环/折线/柱状/二维码/路线图用内联 SVG（零依赖）；大数字用 font-weight:300 + letter-spacing:-.04em；
- 字体：正文 11-13px，等宽数据用 ui-monospace,monospace；
- 不写 <script>、不引外部资源、不要 <html>/<body> 包裹（宿主自动处理 viewport/亮暗/防超屏）。

【没把握时才取参考】visual_card_skeleton(kind=...) 内置 13 份样板（weather/news/calendar/terminal/music/market/todo/monitor/location/battery/shortcuts/clock/quote），可取来改造或组合——但它们只是参考样板，不是白名单；场景对不上就大胆自己写。

卡壳已统一处理：圆角 20、头部标题栏、亮暗主题、viewport、图片/表格不超屏——你只管写卡体 body 内容，不要重复画卡片边框。

参数：
- title（必填）：卡片标题，如「今日天气 · 广州」
- subtitle：副标题，如「11月4日 周三 · 夜间 21:14 更新」
- emoji：头部图标，如「📍」「🎵」
- accent：点缀色 #RRGGBB（标题区底色/边框/圆点），如「#4B7CF6」
- html（必填）：卡体完整 HTML（<body> 内容级别即可，会自动包文档）
- height：卡体固定高度 dp（不传则按内容自适应，140~620）

示例（自由写一张登机牌卡——不在任何内置清单里）：
html = "<div style='display:flex;gap:12px;align-items:stretch'>"
      + "<div style='flex:1'><div style='font:600 10px ui-monospace,monospace;opacity:.55'>CA1234 · CAN → PEK</div>"
      + "<div style='font:300 34px/1.2 ui-monospace,monospace;margin-top:6px'>08:24</div>"
      + "<div style='font-size:11px;opacity:.6'>登机口 C12 · 准点</div></div></div>"
emoji = "✈️"  accent = "#4F6BFF"""


    override val parametersJson = """{
        "type": "object",
        "properties": {
            "title": {"type": "string", "description": "卡片标题"},
            "subtitle": {"type": "string", "description": "副标题（可空）"},
            "emoji": {"type": "string", "description": "头部 emoji 图标（可空）"},
            "accent": {"type": "string", "description": "点缀色 #RRGGBB（可空=主题色）"},
            "html": {"type": "string", "description": "卡体完整 HTML（自由写，任何场景）"},
            "height": {"type": "integer", "description": "卡体固定高度 dp（可空=自适应）"}
        },
        "required": ["title", "html"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val o = runCatching { org.json.JSONObject(arguments) }.getOrNull()
            ?: return "❌ 参数不是合法 JSON"
        val html = o.optString("html", "").trim()
        if (html.isBlank()) return "❌ html 不能为空：请写卡体完整 HTML。"
        val title = o.optString("title", "").ifBlank { "可视化小卡" }
        return "✅ 可视化小卡「$title」已生成并渲染在对话框。"
    }
}
