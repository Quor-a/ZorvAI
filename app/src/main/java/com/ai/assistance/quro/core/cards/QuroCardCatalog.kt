package com.ai.assistance.quro.core.cards

/**
 * 卡片目录（声明式）：AI 可据此「照单点菜」生成数百款可交互卡片。
 *
 * 每个条目描述一种 [QuroChatCard] 子类型的 `type`、归类 `category`、用途说明与一份
 * 可直接下发的 `sampleJson`。AI 只需挑选目录里的 `type` 并填满对应字段，即可产出卡片，
 * 无需记忆字段细节，从而实现「千款卡片」化生产。
 *
 * ## 命令语法（command 字段取值）
 * 卡片动作统一走 [com.ai.assistance.quro.ui.ChatScreen.handleCardCommand] 分发，支持：
 * - `reply:<文本>`            —— 把文本作为用户消息发给 AI。
 * - `ui_open_* / ui_toggle_*` —— 走 [com.ai.assistance.quro.core.tools.QuroUiActionBridge] 执行界面动作。
 * - `linux:install`           —— 触发 Linux 沙箱安装。
 * - `run:<命令>`              —— 把命令喂给内置终端执行。
 * - `open:<url>`              —— 在内置浏览器打开链接（v221 新增）。
 * - `copy:<文本>`             —— 复制文本到剪贴板并 Toast 提示「已复制」（v221 新增）。
 * - `ai:<提示词>`             —— 把提示词直接发送给 AI（v221 新增）。
 * - `screen:<名称>`           —— 派发界面导航/动作（v221 新增）。
 * 任意 `command` 字段留空表示无动作（如部分纯展示卡片）。
 */
data class CardTemplate(
    val type: String,
    val category: String,
    val description: String,
    val sampleJson: String,
)

/** 完整卡片目录：覆盖全部历史类型 + v221 新增 6 款富事件卡片。 */
val CARD_CATALOG: List<CardTemplate> = listOf(
    // ── 输入交互 ──
    CardTemplate("button", "input", "单个按钮，点击触发 command", """{"type":"button","title":"开始","label":"点击我","command":"reply:你好","variant":"filled"}"""),
    CardTemplate("toggle", "input", "开关，本地切换并回传 command", """{"type":"toggle","title":"开关","label":"启用通知","checked":false,"command":"ui_toggle_notify"}"""),
    CardTemplate("slider", "input", "滑块，拖动结束回传 command", """{"type":"slider","label":"音量","value":50,"min":0,"max":100,"step":1,"unit":"%","command":"ui_set_volume"}"""),
    CardTemplate("form", "input", "表单，填写后提交到 submitCommand", """{"type":"form","fields":[{"key":"user","label":"用户名","value":"","placeholder":"输入","secret":false}],"submitCommand":"ui_submit"}"""),
    CardTemplate("chips", "input", "标签多选/单选，变更回传 command", """{"type":"chips","label":"选择","chips":["红","绿","蓝"],"selected":["红"],"multi":true,"command":"ui_chip"}"""),
    CardTemplate("segmented", "input", "分段选择器，切换回传 command", """{"type":"segmented","label":"主题","options":["浅色","深色","跟随系统"],"selectedIndex":0,"command":"ui_theme"}"""),
    CardTemplate("rating", "input", "评分星，变更回传 command", """{"type":"rating","label":"评分","max":5,"value":4,"command":"ui_rate"}"""),
    CardTemplate("list", "input", "可选列表，选中回传 command", """{"type":"list","items":[{"text":"选项A","sub":"说明","selected":false}],"selectable":true,"command":"ui_select"}"""),
    CardTemplate("counter", "input", "计数器，±步进并持久化，回传 command（v221）", """{"type":"counter","title":"计数","label":"数量","value":3,"min":0,"max":10,"step":1,"command":"ui_count"}"""),
    // ── 数据展示 ──
    CardTemplate("chart", "data", "柱状/折线图", """{"type":"chart","chart_type":"bar","series":[{"label":"周一","value":3},{"label":"周二","value":5}]}"""),
    CardTemplate("stat", "data", "关键指标卡（含涨跌趋势）", """{"type":"stat","label":"用户","value":"1.2k","unit":"人","delta":"+5%","trend":"up"}"""),
    CardTemplate("table", "data", "表格", """{"type":"table","headers":["名称","数量"],"rows":[["苹果","3"],["香蕉","5"]]}"""),
    CardTemplate("pie", "data", "饼图（支持自定义颜色）", """{"type":"pie","segments":[{"name":"工作","value":40,"color":"#4CAF50"},{"name":"生活","value":60,"color":"#2196F3"}]}"""),
    CardTemplate("gauge", "data", "仪表盘", """{"type":"gauge","label":"CPU","value":72,"max":100,"unit":"%"}"""),
    CardTemplate("progress", "data", "进度条", """{"type":"progress","label":"下载","value":60,"max":100,"suffix":"%"}"""),
    CardTemplate("radar", "data", "雷达图（多维度评分 0~100）", """{"type":"radar","axes":[{"name":"速度","value":80},{"name":"稳定","value":60}]}"""),
    CardTemplate("heatmap", "data", "热力图（按周聚合）", """{"type":"heatmap","values":[1,3,5,2,4,0,6],"weeks":12,"label":"活跃度"}"""),
    CardTemplate("compare", "data", "左右对比", """{"type":"compare","left_title":"方案A","left_points":["便宜","简单"],"left_positive":true,"right_title":"方案B","right_points":["强大"],"right_positive":false}"""),
    CardTemplate("countdown", "data", "倒计时（目标时间）", """{"type":"countdown","label":"距活动","target":"2026-12-31 23:59:59"}"""),
    CardTemplate("timer", "data", "计时器，到点回传 command", """{"type":"timer","seconds":30,"command":"ui_timer_done"}"""),
    // ── 媒体 ──
    CardTemplate("media", "media", "图片/视频媒体", """{"type":"media","mediaUrl":"https://example.com/a.png","mediaType":"image"}"""),
    CardTemplate("mediaplay", "media", "音频/视频播放器", """{"type":"mediaplay","mediaType":"audio","uri":"https://example.com/a.mp3","label":"播放"}"""),
    CardTemplate("stream", "media", "流式日志行", """{"type":"stream","lines":["第一行","第二行"]}"""),
    CardTemplate("toolcall", "media", "工具调用状态卡", """{"type":"toolcall","tool":"search","status":"running","progress":0.5,"message":"搜索中"}"""),
    CardTemplate("carousel", "media", "轮播卡片", """{"type":"carousel","slides":[{"title":"页1","body":"内容","color":"#FF9800"},{"title":"页2","body":"内容"}]}"""),
    // ── 布局/结构 ──
    CardTemplate("note", "layout", "笔记/代码块", """{"type":"note","body":"代码：println(1)","lang":"kotlin"}"""),
    CardTemplate("info", "layout", "信息文字块", """{"type":"info","body":"这是一段说明文字","align":"start"}"""),
    CardTemplate("expandable", "layout", "可折叠面板", """{"type":"expandable","body":"展开内容","expanded":false}"""),
    CardTemplate("tabs", "layout", "标签页", """{"type":"tabs","tabs":[{"title":"概览","body":"内容"},{"title":"详情","body":"..."}],"selectedIndex":0}"""),
    CardTemplate("steps", "layout", "步骤条", """{"type":"steps","steps":[{"title":"下单","status":"done"},{"title":"发货","status":"active"},{"title":"收货","status":"todo"}],"current":1}"""),
    CardTemplate("timeline", "layout", "时间线", """{"type":"timeline","events":[{"time":"09:00","title":"起床","desc":"","status":"done"},{"time":"12:00","title":"午饭","status":"active"}]}"""),
    CardTemplate("kanban", "layout", "看板（多列）", """{"type":"kanban","columns":[{"name":"待办","items":["任务1","任务2"]},{"name":"完成","items":["任务0"]}]}"""),
    // ── 动作/快捷 ──
    CardTemplate("actions", "action", "动作按钮组", """{"type":"actions","actions":[{"label":"复制","command":"copy:文本"},{"label":"打开","command":"open:https://example.com"}]}"""),
    CardTemplate("quickreply", "action", "快捷回复建议", """{"type":"quickreply","replies":["好的","稍等","不行"],"multi":false}"""),
    CardTemplate("quickaction", "action", "快捷动作（带图标）", """{"type":"quickaction","actions":[{"label":"搜索","icon":"search","command":"ai:搜索最新新闻"},{"label":"打开","icon":"open","command":"open:https://example.com"}]}"""),
    CardTemplate("yuanbao", "action", "链接回答跳转", """{"type":"yuanbao","url":"https://yuanbao.tencent.com/abc"}"""),
    // ── 导航 ──
    CardTemplate("breadcrumb", "nav", "面包屑导航，点击层级触发 command（v221）", """{"type":"breadcrumb","title":"路径","crumbs":[{"label":"首页","command":"screen:home"},{"label":"设置","command":"screen:settings"}]}"""),
    // ── 装饰/可视化 ──
    CardTemplate("color", "decoration", "调色板，点击复制十六进制或触发 command（v221）", """{"type":"color","title":"主题色","colors":["#FF5722","#2196F3","#4CAF50"],"label":"点击复制","command":""}"""),
    CardTemplate("tagcloud", "decoration", "标签云，按权重缩放字号，点击触发 command（v221）", """{"type":"tagcloud","title":"热门标签","tags":[{"label":"AI","weight":5,"command":"ai:讲讲AI"},{"label":"编程","weight":3,"command":"ai:编程技巧"}]}"""),
    CardTemplate("badge", "decoration", "彩色徽章组，点击触发各自 command（v221）", """{"type":"badge","title":"成就","badges":[{"label":"新人","color":"#4CAF50","command":"open:https://example.com/badge"},{"label":"活跃","color":"#FF9800","command":""}]}"""),
    CardTemplate("avatargroup", "decoration", "重叠头像组，点击触发 command（v221）", """{"type":"avatargroup","title":"在线成员","avatars":[{"name":"小明","url":"","command":"screen:profile"},{"name":"小红","url":"","command":"screen:profile"}]}"""),
)

/** 把目录序列化为紧凑 JSON，便于注入 AI 系统提示词/工具说明。 */
fun cardCatalogJson(): String = org.json.JSONArray().also { a ->
    CARD_CATALOG.forEach { t ->
        a.put(org.json.JSONObject().apply {
            put("type", t.type); put("category", t.category)
            put("description", t.description); put("sample", t.sampleJson)
        })
    }
}.toString()
