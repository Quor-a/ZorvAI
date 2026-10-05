package com.ai.assistance.quro.core.cards

import org.json.JSONArray
import org.json.JSONObject

/**
 * 卡片 SDK 的**单一名册**（v1400 起）。
 *
 * ## 解决什么问题
 *
 * 原来新增一种卡片要同步改六个地方：`sealed` 分支、`parseComponentSpec`、
 * `serializeCard`、`parseCard`、`QuroChatCards` 渲染分派、`CARD_CATALOG`。
 * 六处**漏一处不会编译报错**，只会静默失效 —— 新卡片能解析但渲染成空白，
 * 或能渲染但存档后读不回来。这类 bug 极难查。
 *
 * 现在 [CardSpec] 是唯一定义处，[all] 汇总为一份名册，
 * 供解析 / 目录生成 / 一致性自检**共用**。
 *
 * ## 🔴 存量类型签名一律不动
 *
 * 存量 47 种卡片的数据类字段**一个字都没改** —— 它们已在真机上跑着，
 * 改字段会连带影响 [serializeCard] / [parseCard] / `QuroChatCards` 三处，
 * 以及历史存档的兼容性。本文件对存量类型只做「按现有签名构造」，
 * 想要的能力一律用**新类型**实现（`keyvalue` / `ring` / `stackedbar` …）。
 *
 * 本文件是**纯逻辑、无 Android 依赖**，可 JVM 单测。
 */
object CardSdk {

    /**
     * 一种卡片的完整定义。
     *
     * @param type        JSON 里的 `type` 值（契约，改了老数据认不出来）
     * @param category    归类，用于目录分组
     * @param description 用途说明（会进 AI 提示词，必须说清什么时候用）
     * @param sample      可直接下发的样例 JSON（AI 照抄）
     * @param builder     从 JSON 构造。null = 不接受 JSON 构造（走 [CardSdk.fallbackCard]）
     */
    data class CardSpec(
        val type: String,
        val category: String,
        val description: String,
        val sample: String,
        val builder: ((JSONObject) -> QuroChatCard?)? = null,
    )

    /** 全部卡片定义。新增卡片**只改这里**。 */
    val all: List<CardSpec> = buildList {

        // ════════════════ 输入交互 ════════════════
        add(CardSpec("button", "input", "单个按钮，点击触发 command", """{"type":"button","title":"开始","label":"点击我","command":"reply:你好","variant":"filled"}""") { s ->
            QuroChatCard.ButtonCard(
                s.id(), s.title(),
                s.optString("label", "确定"), s.optString("command", ""),
                s.optString("variant", "filled"), s.strOrNull("icon"),
            )
        })
        add(CardSpec("toggle", "input", "开关，本地切换并回传 command", """{"type":"toggle","label":"启用通知","checked":false,"command":"ui_toggle_notify"}""") { s ->
            QuroChatCard.ToggleCard(s.id(), s.title(), s.optString("label", ""), s.optBoolean("checked", false), s.optString("command", ""))
        })
        add(CardSpec("slider", "input", "滑块，拖动结束回传 command", """{"type":"slider","label":"音量","value":50,"min":0,"max":100,"step":1,"unit":"%","command":"ui_set_volume"}""") { s ->
            QuroChatCard.SliderCard(
                s.id(), s.title(), s.optString("label", ""),
                s.optDouble("value", 0.0).toFloat(), s.optDouble("min", 0.0).toFloat(),
                s.optDouble("max", 100.0).toFloat(), s.optDouble("step", 1.0).toFloat(),
                s.optString("unit", ""), s.optString("command", ""),
            )
        })
        add(CardSpec("counter", "input", "计数器，±步进并持久化，回传 command", """{"type":"counter","title":"计数","label":"数量","value":3,"min":0,"max":10,"step":1,"command":"ui_count"}""") { s ->
            QuroChatCard.CounterCard(
                s.id(), s.title(), s.optString("label", ""),
                s.optInt("value", 0), s.optInt("min", 0), s.optInt("max", 100),
                s.optInt("step", 1), s.optString("command", ""),
            )
        })
        add(CardSpec("form", "input", "表单，填写后提交到 submitCommand", """{"type":"form","fields":[{"key":"user","label":"用户名","placeholder":"输入"}],"submitCommand":"ui_submit"}""") { s ->
            QuroChatCard.FormCard(
                s.id(), s.title(),
                (0 until s.arrLen("fields")).map { i ->
                    val f = s.objAt("fields", i)
                    QuroChatCard.FormCard.FormField(
                        f.optString("key", "f$i"), f.optString("label", ""),
                        f.optString("value", ""), f.optString("placeholder", ""),
                        f.optBoolean("secret", false),
                    )
                },
                s.optString("submitCommand", ""),
            )
        })
        add(CardSpec("chips", "input", "标签多选/单选，变更回传 command", """{"type":"chips","label":"选择","chips":["红","绿","蓝"],"selected":["红"],"multi":true,"command":"ui_chip"}""") { s ->
            QuroChatCard.ChipsCard(
                s.id(), s.title(), s.optString("label", ""),
                s.strArr("chips"), s.strArr("selected"), s.optBoolean("multi", false), s.optString("command", ""),
            )
        })
        add(CardSpec("segmented", "input", "分段选择器，切换回传 command", """{"type":"segmented","label":"主题","options":["浅色","深色"],"selectedIndex":0,"command":"ui_theme"}""") { s ->
            QuroChatCard.SegmentedCard(
                s.id(), s.title(), s.optString("label", ""),
                s.strArr("options"), s.optInt("selectedIndex", 0), s.optString("command", ""),
            )
        })
        add(CardSpec("rating", "input", "评分星，变更回传 command", """{"type":"rating","label":"评分","max":5,"value":4,"command":"ui_rate"}""") { s ->
            QuroChatCard.RatingCard(s.id(), s.title(), s.optString("label", ""), s.optInt("max", 5), s.optInt("value", 0), s.optString("command", ""))
        })
        add(CardSpec("list", "input", "可选列表，选中回传 command", """{"type":"list","items":[{"text":"选项A","sub":"说明","selected":false}],"selectable":true,"command":"ui_select"}""") { s ->
            QuroChatCard.ListCard(
                s.id(), s.title(),
                (0 until s.arrLen("items")).map { i ->
                    val it = s.objAt("items", i)
                    QuroChatCard.ListCard.ListItem(
                        it.optString("text", ""), it.optString("sub", ""), it.optBoolean("selected", false),
                    )
                },
                s.optBoolean("selectable", false), s.optString("command", ""),
            )
        })

        // ════════════════ 数据展示（存量，签名不动） ════════════════
        add(CardSpec("stat", "data", "关键指标卡（含涨跌趋势）", """{"type":"stat","label":"用户","value":"1.2k","unit":"人","delta":"+5%","trend":"up"}""") { s ->
            QuroChatCard.StatCard(
                s.id(), s.title(), s.optString("label", ""), s.optString("value", ""),
                s.optString("unit", ""), s.optString("delta", ""), s.optString("trend", "flat"),
            )
        })
        add(CardSpec("progress", "data", "进度条", """{"type":"progress","label":"下载","value":60,"max":100,"suffix":"%"}""") { s ->
            QuroChatCard.ProgressCard(s.id(), s.title(), s.optString("label", ""), s.optDouble("value", 0.0).toFloat(), s.optDouble("max", 100.0).toFloat(), s.optString("suffix", "%"))
        })
        add(CardSpec("gauge", "data", "仪表盘（环形进度）", """{"type":"gauge","label":"CPU","value":72,"max":100,"unit":"%"}""") { s ->
            QuroChatCard.GaugeCard(s.id(), s.title(), s.optString("label", ""), s.optDouble("value", 0.0).toFloat(), s.optDouble("max", 100.0).toFloat(), s.optString("unit", "%"))
        })
        add(CardSpec("alert", "data", "告警横幅（info/warning/error/success）", """{"type":"alert","severity":"warning","text":"磁盘空间不足 10%"}""") { s ->
            QuroChatCard.AlertCard(s.id(), s.title(), s.optString("severity", "info"), s.optString("text", ""))
        })
        add(CardSpec("table", "data", "表格", """{"type":"table","headers":["名称","数量"],"rows":[["苹果","3"],["香蕉","5"]]}""") { s ->
            QuroChatCard.TableCard(s.id(), s.title(), s.strArr("headers"), s.rowsArrStr("rows"))
        })
        add(CardSpec("chart", "data", "柱状/折线图（chart_type=bar|line）", """{"type":"chart","chart_type":"bar","series":[{"label":"周一","value":3},{"label":"周二","value":5}]}""") { s ->
            QuroChatCard.ChartCard(
                s.id(), s.title(), s.optString("chart_type", "bar"),
                (0 until s.arrLen("series")).map { i ->
                    val p = s.objAt("series", i)
                    QuroChatCard.ChartCard.SeriesPoint(p.optString("label", ""), p.optDouble("value", 0.0).toFloat())
                },
            )
        })
        add(CardSpec("pie", "data", "饼图 / 占比环", """{"type":"pie","segments":[{"name":"工作","value":40,"color":"#4CAF50"},{"name":"生活","value":60,"color":"#2196F3"}]}""") { s ->
            QuroChatCard.PieCard(
                s.id(), s.title(),
                (0 until s.arrLen("segments")).map { i ->
                    val it = s.objAt("segments", i)
                    QuroChatCard.PieCard.PieSeg(it.optString("name", ""), it.optDouble("value", 0.0).toFloat(), it.optString("color", ""))
                },
            )
        })
        add(CardSpec("radar", "data", "雷达图（多维 0~100）", """{"type":"radar","axes":[{"name":"速度","value":80},{"name":"稳定","value":60}]}""") { s ->
            QuroChatCard.RadarCard(
                s.id(), s.title(),
                (0 until s.arrLen("axes")).map { i ->
                    val a = s.objAt("axes", i)
                    QuroChatCard.RadarCard.RadarAxis(a.optString("name", ""), a.optDouble("value", 0.0).toFloat())
                },
            )
        })
        add(CardSpec("heatmap", "data", "热力图（按周聚合）", """{"type":"heatmap","values":[1,3,5,2,4,0,6],"weeks":12,"label":"活跃度"}""") { s ->
            QuroChatCard.HeatmapCard(s.id(), s.title(), s.intArr("values"), s.optInt("weeks", 12), s.optString("label", ""))
        })
        add(CardSpec("compare", "data", "左右对比", """{"type":"compare","left_title":"方案A","left_points":["便宜","简单"],"left_positive":true,"right_title":"方案B","right_points":["强大"],"right_positive":false}""") { s ->
            QuroChatCard.CompareCard(
                s.id(), s.title(),
                QuroChatCard.CompareCard.CompareSide(
                    s.optString("left_title", ""), s.strArr("left_points"), s.optBoolean("left_positive", true),
                ),
                QuroChatCard.CompareCard.CompareSide(
                    s.optString("right_title", ""), s.strArr("right_points"), s.optBoolean("right_positive", false),
                ),
            )
        })
        add(CardSpec("countdown", "data", "倒计时（目标时间）", """{"type":"countdown","label":"距活动","target":"2026-12-31 23:59:59"}""") { s ->
            QuroChatCard.CountdownCard(s.id(), s.title(), s.optString("label", ""), s.targetEpochMs())
        })
        add(CardSpec("timer", "data", "计时器，到点回传 command", """{"type":"timer","seconds":30,"command":"ui_timer_done"}""") { s ->
            QuroChatCard.TimerCard(s.id(), s.title(), s.optInt("seconds", 30), s.optString("command", ""))
        })

        // ════════════════ 媒体（存量） ════════════════
        add(CardSpec("media", "media", "图片/视频媒体", """{"type":"media","mediaUrl":"https://example.com/a.png","mediaType":"image"}""") { s ->
            QuroChatCard.MediaCard(s.id(), s.title(), s.optString("mediaUrl", ""), s.optString("mediaType", "image"))
        })
        add(CardSpec("mediaplay", "media", "音频/视频播放器", """{"type":"mediaplay","mediaType":"audio","uri":"https://example.com/a.mp3","label":"播放"}""") { s ->
            QuroChatCard.MediaPlayCard(s.id(), s.title(), s.optString("mediaType", "audio"), s.optString("uri", ""), s.optString("label", ""))
        })
        add(CardSpec("stream", "media", "流式日志行", """{"type":"stream","lines":["第一行","第二行"]}""") { s ->
            QuroChatCard.StreamCard(s.id(), s.title(), s.strArr("lines"))
        })
        add(CardSpec("toolcall", "media", "工具调用状态卡", """{"type":"toolcall","tool":"search","status":"running","progress":0.5,"message":"搜索中"}""") { s ->
            QuroChatCard.ToolCallCard(s.id(), s.title(), s.optString("tool", ""), s.optString("status", "pending"), s.optDouble("progress", 0.0).toFloat(), s.optString("message", ""))
        })
        add(CardSpec("carousel", "media", "轮播卡片", """{"type":"carousel","slides":[{"title":"页1","body":"内容","color":"#FF9800"},{"title":"页2","body":"内容"}]}""") { s ->
            QuroChatCard.CarouselCard(
                s.id(), s.title(),
                (0 until s.arrLen("slides")).map { i ->
                    val it = s.objAt("slides", i)
                    QuroChatCard.CarouselCard.Slide(it.optString("title", ""), it.optString("body", ""), it.optString("color", ""))
                },
            )
        })

        // ════════════════ 布局 / 结构（存量） ════════════════
        add(CardSpec("note", "layout", "笔记 / 代码块（带语言高亮）", """{"type":"note","body":"println(1)","lang":"kotlin"}""") { s ->
            QuroChatCard.NoteCard(s.id(), s.title(), s.optString("body", ""), s.strOrNull("lang"))
        })
        add(CardSpec("info", "layout", "信息文字块（可对齐）", """{"type":"info","body":"这是一段说明文字","align":"start"}""") { s ->
            QuroChatCard.InfoCard(s.id(), s.title(), s.optString("body", ""), s.optString("align", "start"))
        })
        add(CardSpec("expandable", "layout", "可折叠面板", """{"type":"expandable","body":"展开内容","expanded":false}""") { s ->
            QuroChatCard.ExpandableCard(s.id(), s.title(), s.optString("body", ""), s.optBoolean("expanded", false))
        })
        add(CardSpec("tabs", "layout", "标签页", """{"type":"tabs","tabs":[{"title":"概览","body":"内容"},{"title":"详情","body":"..."}],"selectedIndex":0}""") { s ->
            QuroChatCard.TabsCard(
                s.id(), s.title(),
                (0 until s.arrLen("tabs")).map { i ->
                    val t = s.objAt("tabs", i)
                    QuroChatCard.TabsCard.Tab(t.optString("title", ""), t.optString("body", ""))
                },
                s.optInt("selectedIndex", 0),
            )
        })
        add(CardSpec("steps", "layout", "步骤条", """{"type":"steps","steps":[{"title":"下单","status":"done"},{"title":"发货","status":"active"}],"current":1}""") { s ->
            QuroChatCard.StepsCard(
                s.id(), s.title(),
                (0 until s.arrLen("steps")).map { i ->
                    val it = s.objAt("steps", i)
                    QuroChatCard.StepsCard.Step(it.optString("title", ""), it.optString("status", "todo"))
                },
                s.optInt("current", 0),
            )
        })
        add(CardSpec("timeline", "layout", "时间轴", """{"type":"timeline","events":[{"time":"09:00","title":"起床","status":"done"},{"time":"12:00","title":"午饭","status":"active"}]}""") { s ->
            QuroChatCard.TimelineCard(
                s.id(), s.title(),
                (0 until s.arrLen("events")).map { i ->
                    val it = s.objAt("events", i)
                    QuroChatCard.TimelineCard.TimeEvent(
                        it.optString("time", ""), it.optString("title", ""),
                        it.optString("desc", ""), it.optString("status", "done"),
                    )
                },
            )
        })
        add(CardSpec("kanban", "layout", "看板（多列）", """{"type":"kanban","columns":[{"name":"待办","items":["任务1","任务2"]},{"name":"完成","items":["任务0"]}]}""") { s ->
            QuroChatCard.KanbanCard(
                s.id(), s.title(),
                (0 until s.arrLen("columns")).map { i ->
                    val c = s.objAt("columns", i)
                    QuroChatCard.KanbanCard.KanbanColumn(c.optString("name", ""), c.strArr("items"))
                },
            )
        })
        add(CardSpec("todo", "layout", "待办清单（可勾选）", """{"type":"todo","items":[{"text":"买菜","done":false}]}""") { s ->
            QuroChatCard.TodoCard(
                s.id(), s.title(),
                (0 until s.arrLen("items")).map { i ->
                    val it = s.objAt("items", i)
                    QuroChatCard.TodoCard.TodoItem(it.optString("text", ""), it.optBoolean("done", false))
                },
            )
        })

        // ════════════════ 动作 / 快捷（存量） ════════════════
        add(CardSpec("actions", "action", "动作按钮组", """{"type":"actions","actions":[{"label":"复制","command":"copy:文本"},{"label":"打开","command":"open:https://example.com"}]}""") { s ->
            QuroChatCard.ActionCard(
                s.id(), s.title(),
                (0 until s.arrLen("actions")).map { i ->
                    val a = s.objAt("actions", i)
                    QuroChatCard.ActionCard.CardAction(a.optString("label", ""), a.optString("command", ""))
                },
            )
        })
        add(CardSpec("quickreply", "action", "快捷回复建议", """{"type":"quickreply","replies":["好的","稍等","不行"],"multi":false}""") { s ->
            QuroChatCard.QuickReplyCard(s.id(), s.title(), s.strArr("replies"), s.optBoolean("multi", false))
        })
        add(CardSpec("quickaction", "action", "快捷动作（带图标）", """{"type":"quickaction","actions":[{"label":"搜索","icon":"search","command":"ai:搜索最新新闻"}]}""") { s ->
            QuroChatCard.QuickActionCard(
                s.id(), s.title(),
                (0 until s.arrLen("actions")).map { i ->
                    val a = s.objAt("actions", i)
                    QuroChatCard.QuickActionCard.QuickAction(a.optString("label", ""), a.optString("icon", ""), a.optString("command", ""))
                },
            )
        })
        add(CardSpec("yuanbao", "action", "链接回答跳转卡", """{"type":"yuanbao","url":"https://yuanbao.tencent.com/abc"}""") { s ->
            QuroChatCard.YuanbaoCard(s.id(), s.title(), s.optString("url", ""))
        })

        // ════════════════ 导航（存量） ════════════════
        add(CardSpec("breadcrumb", "nav", "面包屑导航，点击层级触发 command", """{"type":"breadcrumb","title":"路径","crumbs":[{"label":"首页","command":"screen:home"},{"label":"设置","command":"screen:settings"}]}""") { s ->
            QuroChatCard.BreadcrumbCard(
                s.id(), s.title(),
                (0 until s.arrLen("crumbs")).map { i ->
                    val c = s.objAt("crumbs", i)
                    QuroChatCard.BreadcrumbCard.Breadcrumb(c.optString("label", ""), c.optString("command", ""))
                },
            )
        })

        // ════════════════ 装饰（存量） ════════════════
        add(CardSpec("color", "decoration", "调色板，点击复制十六进制或触发 command", """{"type":"color","title":"主题色","colors":["#FF5722","#2196F3","#4CAF50"],"label":"点击复制"}""") { s ->
            QuroChatCard.ColorCard(s.id(), s.title(), s.strArr("colors"), s.optString("label", ""), s.optString("command", ""))
        })
        add(CardSpec("tagcloud", "decoration", "标签云，按权重缩放字号", """{"type":"tagcloud","title":"热门标签","tags":[{"label":"AI","weight":5,"command":"ai:讲讲AI"},{"label":"编程","weight":3,"command":"ai:编程技巧"}]}""") { s ->
            QuroChatCard.TagCloudCard(
                s.id(), s.title(),
                (0 until s.arrLen("tags")).map { i ->
                    val t = s.objAt("tags", i)
                    QuroChatCard.TagCloudCard.Tag(t.optString("label", ""), t.optInt("weight", 1), t.optString("command", ""))
                },
            )
        })
        add(CardSpec("badge", "decoration", "彩色徽章组", """{"type":"badge","title":"成就","badges":[{"label":"新人","color":"#4CAF50","command":""},{"label":"活跃","color":"#FF9800"}]}""") { s ->
            QuroChatCard.BadgeCard(
                s.id(), s.title(),
                (0 until s.arrLen("badges")).map { i ->
                    val b = s.objAt("badges", i)
                    QuroChatCard.BadgeCard.Badge(b.optString("label", ""), b.optString("color", ""), b.optString("command", ""))
                },
            )
        })
        add(CardSpec("avatargroup", "decoration", "重叠头像组", """{"type":"avatargroup","title":"在线成员","avatars":[{"name":"小明","url":"","command":"screen:profile"}]}""") { s ->
            QuroChatCard.AvatarGroupCard(
                s.id(), s.title(),
                (0 until s.arrLen("avatars")).map { i ->
                    val a = s.objAt("avatars", i)
                    QuroChatCard.AvatarGroupCard.Avatar(a.optString("name", ""), a.optString("url", ""), a.optString("command", ""))
                },
            )
        })

        // ════════════════ AI 自写 / 组合（存量） ════════════════
        add(CardSpec("mermaid", "aiwrite", "AI 自写 Mermaid 图表（流程/时序/状态机/类图/思维导图/git 图等）", """{"type":"mermaid","title":"流程图","source":"graph TD; A-->B; B-->C;"}""") { s ->
            QuroChatCard.MermaidCard(s.id(), s.title(), s.optString("source", ""), s.optString("theme", ""))
        })
        add(CardSpec("miniapp", "aiwrite", "AI 生成 Web 应用（HTML+JS+CSS）实时渲染为可交互页面", """{"type":"miniapp","title":"计算器","html":"<button data-action='tap' data-bind='n'>点我</button>"}""") { s ->
            QuroChatCard.MiniAppCard(s.id(), s.title(), s.optString("html", ""))
        })
        add(CardSpec("htmlpreview", "aiwrite", "HTML 预览卡（气泡内渲染自写 HTML）", """{"type":"htmlpreview","title":"预览","html":"<h3>你好</h3><p>这是 HTML 卡</p>"}""") { s ->
            QuroChatCard.HtmlPreviewCard(s.id(), s.title(), s.optString("html", ""))
        })
        add(CardSpec("composite", "aiwrite", "组合卡：多子卡聚合（stack 堆叠 / tabs 标签页 / accordion 折叠）", """{"type":"composite","layout":"stack","children":[{"type":"stat","label":"内存","value":"6G"},{"type":"progress","label":"下载","value":60}],"description":"系统概览"}""") { s ->
            val kids = (0 until s.arrLen("children")).mapNotNull { s.objOrNull("children", it) }
                .mapNotNull { parseObj(it) }
            if (kids.isEmpty()) null else QuroChatCard.CompositeCard(
                s.id(), s.title(), s.optString("layout", "stack"), kids,
                s.strOrNull("description"),
            )
        })

        // ═══════════════════════════════════════════════════════════
        // ═══════════ v1400 新增：34 种增强组件 ═══════════════════
        // ═══════════════════════════════════════════════════════════

        // ── 数据增强 ──
        add(CardSpec("keyvalue", "data", "键值对列表（详情页最常用，比表格更紧凑）", """{"type":"keyvalue","title":"详情","items":[{"k":"版本","v":"1.1.3"},{"k":"大小","v":"385 MB"}]}""") { s ->
            KeyValueCard(
                s.id(), s.title(),
                (0 until s.arrLen("items")).map { i ->
                    val it = s.objAt("items", i)
                    KeyValueCard.Row(
                        it.optString("k", it.optString("key", "")),
                        it.optString("v", it.optString("value", "")),
                        it.strOrNull("icon"),
                        it.strOrNull("command"),
                    )
                },
            )
        })
        add(CardSpec("ring", "data", "环形进度（value/max，可带中心文案）", """{"type":"ring","label":"完成度","value":0.68,"max":1,"caption":"68%","color":"#4CAF50"}""") { s ->
            RingCard(
                s.id(), s.title(), s.optString("label", ""), s.optDouble("value", 0.0).toFloat(),
                s.optDouble("max", 1.0).toFloat(), s.optString("caption", ""), s.optString("color", ""),
                s.optDouble("thickness", 0.12).toFloat(),
            )
        })
        add(CardSpec("stackedbar", "data", "堆叠柱状图（多系列按分类堆叠）", """{"type":"stackedbar","categories":["1月","2月"],"series":[{"name":"男","values":[20,30]},{"name":"女","values":[15,25]}]}""") { s ->
            StackedBarCard(
                s.id(), s.title(), s.strArr("categories"),
                (0 until s.arrLen("series")).map { i ->
                    val it = s.objAt("series", i)
                    StackedBarCard.Series(it.optString("name", ""), it.floatArr("values"), it.optString("color", ""))
                },
            )
        })
        add(CardSpec("scatter", "data", "散点图（相关性分析）", """{"type":"scatter","points":[{"x":1,"y":2,"label":"A"}],"xLabel":"X","yLabel":"Y"}""") { s ->
            ScatterCard(
                s.id(), s.title(),
                (0 until s.arrLen("points")).map { i ->
                    val p = s.objAt("points", i)
                    ScatterCard.Point(
                        p.optDouble("x", 0.0).toFloat(), p.optDouble("y", 0.0).toFloat(), s.strOrNull2(p, "label"),
                    )
                },
                s.optString("xLabel", ""), s.optString("yLabel", ""),
            )
        })
        add(CardSpec("funnel", "data", "漏斗图（转化率分析）", """{"type":"funnel","steps":[{"name":"曝光","value":1000},{"name":"点击","value":300},{"name":"下单","value":80}]}""") { s ->
            FunnelCard(
                s.id(), s.title(),
                (0 until s.arrLen("steps")).map { i ->
                    val it = s.objAt("steps", i)
                    FunnelCard.Step(it.optString("name", ""), it.optDouble("value", 0.0).toFloat(), it.optString("color", ""))
                },
            )
        })
        add(CardSpec("candlestick", "data", "K线图（金融行情）", """{"type":"candlestick","candles":[{"o":10,"h":12,"l":9,"c":11,"t":"10-01"}]}""") { s ->
            CandlestickCard(
                s.id(), s.title(),
                (0 until s.arrLen("candles")).map { i ->
                    val c = s.objAt("candles", i)
                    CandlestickCard.Candle(
                        c.optDouble("o", 0.0).toFloat(), c.optDouble("h", 0.0).toFloat(),
                        c.optDouble("l", 0.0).toFloat(), c.optDouble("c", 0.0).toFloat(),
                        c.optString("t", ""),
                    )
                },
            )
        })
        add(CardSpec("boxplot", "data", "箱线图（分布统计）", """{"type":"boxplot","groups":[{"name":"A组","min":1,"q1":2,"median":3,"q3":4,"max":5}]}""") { s ->
            BoxPlotCard(
                s.id(), s.title(),
                (0 until s.arrLen("groups")).map { i ->
                    val g = s.objAt("groups", i)
                    BoxPlotCard.Group(
                        g.optString("name", ""), g.optDouble("min", 0.0).toFloat(),
                        g.optDouble("q1", 0.0).toFloat(), g.optDouble("median", 0.0).toFloat(),
                        g.optDouble("q3", 0.0).toFloat(), g.optDouble("max", 0.0).toFloat(),
                    )
                },
            )
        })
        add(CardSpec("speedometer", "data", "速度表（指针仪表 + 多区间配色）", """{"type":"speedometer","value":85,"max":200,"label":"网速","unit":"Mbps","zones":["#4CAF50","#FFC107","#F44336"]}""") { s ->
            SpeedometerCard(
                s.id(), s.title(), s.optDouble("value", 0.0).toFloat(), s.optDouble("max", 100.0).toFloat(),
                s.optString("label", ""), s.optString("unit", ""), s.strArr("zones"),
            )
        })
        add(CardSpec("sparkline", "data", "迷你趋势线（行内小图）", """{"type":"sparkline","values":[1,4,2,7],"caption":"近 7 天","color":"#4CAF50"}""") { s ->
            SparklineCard(
                s.id(), s.title(), s.floatArr("values"), s.optString("caption", ""), s.optString("color", "#4CAF50"),
            )
        })

        // ── 输入增强 ──
        add(CardSpec("searchbox", "input", "搜索输入框，提交回传 command", """{"type":"searchbox","label":"搜索","placeholder":"输入关键词","command":"ai:搜索","hint":"回车提交"}""") { s ->
            SearchBoxCard(
                s.id(), s.title(), s.optString("label", ""), s.optString("placeholder", "搜索…"),
                s.optString("value", ""), s.optString("command", ""), s.optString("hint", ""),
            )
        })
        add(CardSpec("poll", "input", "投票卡（选项 + 结果条 + 总数）", """{"type":"poll","question":"选哪个？","options":[{"label":"A","count":10},{"label":"B","count":22}],"multi":false,"total":32,"command":"ui_vote"}""") { s ->
            PollCard(
                s.id(), s.title(), s.optString("question", ""),
                (0 until s.arrLen("options")).map { i ->
                    val o = s.objAt("options", i)
                    PollCard.Option(o.optString("label", ""), o.optInt("count", 0), o.optString("color", ""))
                },
                s.optBoolean("multi", false), s.optInt("total", 0), s.optString("command", ""),
            )
        })
        add(CardSpec("checklist", "input", "清单（勾选框 + 说明 + 完成度摘要）", """{"type":"checklist","items":[{"text":"检查配置","done":true,"note":"已通过"}],"summary":"2/3 完成"}""") { s ->
            CheckListCard(
                s.id(), s.title(),
                (0 until s.arrLen("items")).map { i ->
                    val it = s.objAt("items", i)
                    CheckListCard.Item(it.optString("text", ""), it.optBoolean("done", false), it.optString("note", ""))
                },
                s.optString("summary", ""),
            )
        })

        // ── 结构增强 ──
        add(CardSpec("accordion", "layout", "手风琴（多项各自独立展开）", """{"type":"accordion","items":[{"title":"标题1","body":"内容1"}],"expandedIndex":0}""") { s ->
            AccordionCard(
                s.id(), s.title(),
                (0 until s.arrLen("items")).map { i ->
                    val it = s.objAt("items", i)
                    AccordionCard.Item(it.optString("title", ""), it.optString("body", ""))
                },
                s.optInt("expandedIndex", -1),
            )
        })
        add(CardSpec("groupedlist", "layout", "分组列表（小标题分区）", """{"type":"groupedlist","sections":[{"title":"今天","items":[{"text":"任务","sub":"说明","value":"3 项"}]}]}""") { s ->
            GroupedListCard(
                s.id(), s.title(),
                (0 until s.arrLen("sections")).map { i ->
                    val sec = s.objAt("sections", i)
                    GroupedListCard.Section(
                        sec.optString("title", ""),
                        (0 until sec.arrLen("items")).map { j ->
                            val it = sec.objAt("items", j)
                            GroupedListCard.Item(
                                it.optString("text", ""), it.optString("sub", ""), it.optString("icon", ""), it.optString("value", ""),
                            )
                        },
                    )
                },
            )
        })
        add(CardSpec("tree", "layout", "树形层级（文件树 / 组织架构）", """{"type":"tree","nodes":[{"label":"src","children":[{"label":"main.kt"}]}],"expandedDepth":2}""") { s ->
            fun node(j: JSONObject): TreeCard.Node {
                val kids = j.optJSONArray("children")
                return TreeCard.Node(
                    j.optString("label", ""), j.optString("icon", ""), j.optBoolean("expanded", false), j.optString("value", ""),
                    if (kids == null) emptyList() else (0 until kids.length()).mapNotNull { kids.optJSONObject(it) }.map { node(it) },
                )
            }
            TreeCard(
                s.id(), s.title(),
                (0 until s.arrLen("nodes")).mapNotNull { s.objOrNull("nodes", it) }.map { node(it) },
                s.optInt("expandedDepth", 2),
            )
        })
        add(CardSpec("quote", "layout", "引述块（可署名、可标来源）", """{"type":"quote","text":"引用内容","author":"某人","source":"某书"}""") { s ->
            QuoteCard(s.id(), s.title(), s.optString("text", ""), s.strOrNull("author"), s.strOrNull("source"))
        })
        add(CardSpec("diff", "layout", "差异对比（增删行 + 统计）", """{"type":"diff","file":"a.kt","additions":3,"deletions":1,"rows":[{"kind":"add","text":"新增行"},{"kind":"del","text":"删除行"}]}""") { s ->
            DiffCard(
                s.id(), s.title(),
                (0 until s.arrLen("rows")).map { i ->
                    val r = s.objAt("rows", i)
                    DiffCard.Row(r.optString("kind", "add"), r.optString("text", ""), s.strOrNull2(r, "oldText"))
                },
                s.optString("file", ""), s.optInt("additions", 0), s.optInt("deletions", 0),
            )
        })

        // ── 流程 / 层级 ──
        add(CardSpec("flow", "flow", "流程图（节点 + 箭头，可点击）", """{"type":"flow","direction":"vertical","nodes":[{"label":"开始","kind":"start"},{"label":"处理","kind":"process","desc":"执行中"},{"label":"结束","kind":"end"}]}""") { s ->
            FlowCard(
                s.id(), s.title(),
                (0 until s.arrLen("nodes")).map { i ->
                    val n = s.objAt("nodes", i)
                    FlowCard.Node(n.optString("label", ""), n.optString("kind", "process"), n.optString("desc", ""), n.optString("command", ""))
                },
                s.optString("direction", "vertical"),
            )
        })
        add(CardSpec("hierarchy", "flow", "层级流程（阶段分组）", """{"type":"hierarchy","stages":[{"name":"准备","items":["调研","选型"],"color":"#4CAF50"},{"name":"执行","items":["开发","测试"]}]}""") { s ->
            HierarchyCard(
                s.id(), s.title(),
                (0 until s.arrLen("stages")).map { i ->
                    val st = s.objAt("stages", i)
                    HierarchyCard.Stage(st.optString("name", ""), st.strArr("items"), st.optString("color", ""))
                },
            )
        })

        // ── 实体卡 ──
        add(CardSpec("contact", "action", "联系人卡（头像 + 状态 + 快捷动作）", """{"type":"contact","name":"小明","role":"后端工程师","status":"online","actions":[{"label":"发消息","icon":"chat","command":"ai:你好"}]}""") { s ->
            ContactCard(
                s.id(), s.title(), s.optString("name", ""), s.optString("role", ""),
                s.optString("avatar", ""), s.optString("status", "offline"),
                (0 until s.arrLen("actions")).map { i ->
                    val a = s.objAt("actions", i)
                    ContactCard.QA(a.optString("label", ""), a.optString("icon", ""), a.optString("command", ""))
                },
            )
        })
        add(CardSpec("product", "action", "商品卡（图 + 价 + 划线价 + 评分销量）", """{"type":"product","name":"商品名","price":"99.00","originalPrice":"129.00","image":"https://a.png","rating":4.5,"sold":1234,"tag":"热销"}""") { s ->
            ProductCard(
                s.id(), s.title(), s.optString("name", ""), s.optString("price", ""),
                s.strOrNull("originalPrice"), s.optString("image", ""),
                s.optDouble("rating", 0.0).toFloat(), s.optInt("sold", 0),
                s.optString("command", ""), s.optString("tag", ""),
            )
        })
        add(CardSpec("schedule", "action", "日程卡（日期 + 时间 + 地点）", """{"type":"schedule","event":"周会","date":"2026-10-06","time":"10:00-11:00","place":"3 楼会议室"}""") { s ->
            ScheduleCard(
                s.id(), s.title(), s.optString("event", ""), s.optString("date", ""),
                s.optString("time", ""), s.optString("place", ""), s.optString("command", ""),
            )
        })
        add(CardSpec("filecard", "action", "文件卡（类型 + 大小 + 路径）", """{"type":"filecard","name":"report.pdf","size":"2.4 MB","path":"/sdcard/report.pdf","mime":"application/pdf"}""") { s ->
            FileCard(
                s.id(), s.title(), s.optString("name", ""), s.optString("size", ""),
                s.optString("path", ""), s.optString("mime", ""), s.optString("command", ""),
            )
        })
        add(CardSpec("achievement", "action", "成就卡（徽章 + 进度）", """{"type":"achievement","name":"连续签到","desc":"已 7 天","icon":"🏆","progress":0.7}""") { s ->
            AchievementCard(
                s.id(), s.title(), s.optString("name", ""), s.optString("desc", ""),
                s.optString("icon", ""), s.optDouble("progress", -1.0).toFloat(), s.optString("command", ""),
            )
        })
        add(CardSpec("weather", "action", "天气卡（当前 + 逐时预报）", """{"type":"weather","city":"深圳","temp":"28","condition":"多云","icon":"⛅","humidity":"70%","hours":[{"t":"14时","v":"30℃","i":"☀️"}]}""") { s ->
            WeatherCard(
                s.id(), s.title(), s.optString("city", ""), s.optString("temp", ""),
                s.optString("condition", ""), s.optString("icon", ""),
                s.optString("humidity", ""), s.optString("wind", ""),
                (0 until s.arrLen("hours")).map { i ->
                    val h = s.objAt("hours", i)
                    WeatherCard.Hour(h.optString("t", ""), h.optString("v", ""), h.optString("i", ""))
                },
            )
        })
        add(CardSpec("map", "action", "地图卡（坐标 + 标记点）", """{"type":"map","lat":22.54,"lng":114.05,"zoom":13,"markers":[{"lat":22.54,"lng":114.05,"label":"公司"}]}""") { s ->
            MapCard(
                s.id(), s.title(), s.optDouble("lat", 0.0), s.optDouble("lng", 0.0), s.optInt("zoom", 12),
                (0 until s.arrLen("markers")).map { i ->
                    val m = s.objAt("markers", i)
                    MapCard.Marker(m.optDouble("lat", 0.0), m.optDouble("lng", 0.0), m.optString("label", ""))
                },
            )
        })
        add(CardSpec("qrcode", "media", "二维码（内容 + 尺寸 + 配色）", """{"type":"qrcode","content":"https://example.com","size":180,"caption":"扫码打开","color":"#000000"}""") { s ->
            QrCodeCard(
                s.id(), s.title(), s.optString("content", ""), s.optInt("size", 180),
                s.optString("caption", ""), s.optString("color", "#000000"),
            )
        })
        add(CardSpec("gallery", "media", "图片网格（多图排列）", """{"type":"gallery","images":[{"url":"https://a.png","caption":"图1"}],"columns":2,"aspectRatio":1.0}""") { s ->
            GalleryCard(
                s.id(), s.title(),
                (0 until s.arrLen("images")).map { i ->
                    val it = s.objAt("images", i)
                    GalleryCard.Item(it.optString("url", ""), it.optString("caption", ""))
                },
                s.optInt("columns", 2), s.optDouble("aspectRatio", 1.0).toFloat(),
            )
        })
        add(CardSpec("terminal", "media", "终端输出（命令 + 行 + 退出码）", """{"type":"terminal","command":"ls -la","lines":["total 32","drwxr-xr-x 2 root root"],"exitCode":0}""") { s ->
            TerminalCard(
                s.id(), s.title(), s.strArr("lines"),
                if (s.has("exitCode")) s.optInt("exitCode") else -1,
                s.optString("command", ""),
            )
        })

        // ── 导航增强 ──
        add(CardSpec("linklist", "nav", "链接列表（外链 / 文档）", """{"type":"linklist","links":[{"label":"官方文档","url":"https://x.com","desc":"说明","icon":"link"}]}""") { s ->
            LinkListCard(
                s.id(), s.title(),
                (0 until s.arrLen("links")).map { i ->
                    val l = s.objAt("links", i)
                    LinkListCard.Link(l.optString("label", ""), l.optString("url", ""), l.optString("desc", ""), l.optString("icon", ""))
                },
            )
        })
        add(CardSpec("pagination", "nav", "分页器（点页码触发 command）", """{"type":"pagination","page":1,"total":5,"command":"ai:第 2 页"}""") { s ->
            PaginationCard(s.id(), s.title(), s.optInt("page", 1), s.optInt("total", 1), s.optString("command", ""))
        })

        // ── 布局装饰 ──
        add(CardSpec("divider", "decoration", "分隔线（可带居中文字）", """{"type":"divider","text":"或者","dashed":false}""") { s ->
            DividerCard(s.id(), s.title(), s.optString("text", ""), s.optBoolean("dashed", false))
        })
        // ════════════════ 第二批增强（v1400-b2） ════════════════
        add(CardSpec("gantt", "data", "甘特图：任务排期与跨度重叠（table 看值、gantt 看时间长度）", """{"type":"gantt","title":"项目排期","unit":"day","total":7,"axisStart":"10/01","tasks":[{"name":"设计","start":1,"duration":2,"progress":100,"owner":"设计"},{"name":"开发","start":3,"duration":4,"progress":40}]}""") { s ->
            GanttCard(
                s.id(), s.title(),
                (0 until s.arrLen("tasks")).map { i ->
                    val t = s.objAt("tasks", i)
                    GanttCard.Task(
                        t.optString("name", ""), t.optInt("start", 1), t.optInt("duration", 1),
                        t.optInt("progress", -1), t.optString("color", ""), t.optString("owner", ""),
                    )
                },
                s.optString("unit", "day"), s.optInt("total", 0), s.optString("axisStart", ""),
            )
        })
        add(CardSpec("invoice", "data", "账单/小票：明细 + 小计/折扣/合计（table 是中性表格，invoice 有金额语义）", """{"type":"invoice","title":"10月账单","merchant":"云主机","currency":"¥","items":[{"name":"CPU 实例","qty":2,"price":"99.00","amount":"198.00"}],"subtotal":"198.00","discount":"-20.00","total":"178.00","paid":false}""") { s ->
            InvoiceCard(
                s.id(), s.title(), s.optString("merchant", ""),
                (0 until s.arrLen("items")).map { i ->
                    val it = s.objAt("items", i)
                    InvoiceCard.Item(
                        it.optString("name", ""), it.optInt("qty", 1),
                        it.optString("price", ""), it.optString("amount", ""),
                    )
                },
                s.optString("currency", "¥"), s.optString("subtotal", ""),
                s.optString("discount", ""), s.optString("total", ""),
                s.optString("note", ""), s.optBoolean("paid", false), s.optString("command", ""),
            )
        })
        add(CardSpec("currency", "data", "多币种换算：基准额 + 各币种汇率与涨跌（keyvalue 是属性表，currency 是换算表）", """{"type":"currency","title":"1 元能换多少","base":"CNY","value":"1","rates":[{"code":"USD","symbol":"$","rate":"0.1392","change":"up"},{"code":"EUR","symbol":"€","rate":"0.1281"}]}""") { s ->
            CurrencyCard(
                s.id(), s.title(), s.optString("base", "CNY"), s.optString("value", "1"),
                (0 until s.arrLen("rates")).map { i ->
                    val r = s.objAt("rates", i)
                    CurrencyCard.Rate(r.optString("code", ""), r.optString("symbol", ""), r.optString("rate", ""), r.optString("change", ""))
                },
                s.optString("updated", ""),
            )
        })
        add(CardSpec("clock", "data", "跨时区时刻对照：本地区刻 + 各城市偏移（回答「纽约现在几点」）", """{"type":"clock","title":"此刻","current":"2026-10-05 14:30","format":"24h","zones":[{"city":"北京","offset":"+08:00","diff":"本机"},{"city":"纽约","offset":"-04:00","diff":"-12h"}]}""") { s ->
            ClockCard(
                s.id(), s.title(), s.optString("current", ""),
                (0 until s.arrLen("zones")).map { i ->
                    val z = s.objAt("zones", i)
                    ClockCard.Zone(z.optString("city", ""), z.optString("offset", ""), z.optString("diff", ""))
                },
                s.optString("format", "24h"),
            )
        })
        add(CardSpec("tracker", "data", "习惯打卡：逐天格子 + 连续天数（checklist 是待办清单，tracker 是已成 history 的格子）", """{"type":"tracker","title":"10月阅读打卡","name":"阅读","days":["1","1","0","1","1","1","1"],"target":21,"streak":5}""") { s ->
            TrackerCard(
                s.id(), s.title(), s.optString("name", ""), s.strArr("days"),
                s.optInt("target", 0), s.optInt("streak", 0), s.optString("unit", "天"),
            )
        })
        add(CardSpec("scoreboard", "data", "比分板：主客队 + 比分 + 节次/时间/比赛状态", """{"type":"scoreboard","title":"小组赛","home":"主队","homeScore":"2","away":"客队","awayScore":"1","period":"上半场","time":"45:00","status":"live"}""") { s ->
            ScoreboardCard(
                s.id(), s.title(), s.optString("home", ""), s.optString("homeScore", ""),
                s.optString("away", ""), s.optString("awayScore", ""),
                s.optString("period", ""), s.optString("time", ""), s.optString("status", "live"),
            )
        })
        add(CardSpec("vocab", "aiwrite", "词汇卡：单词 + 音标 + 词性 + 释义 + 中英例句（背单词场景）", """{"type":"vocab","title":"resilient","word":"resilient","phonetic":"/rɪˈzɪliənt/","pos":"adj","meaning":"有韧性的；受冲击后能快速恢复的","examples":[{"en":"She is resilient under pressure.","zh":"她在压力下很有韧性。"}],"tags":["性格","高频"]}""") { s ->
            VocabCard(
                s.id(), s.title(), s.optString("word", ""), s.optString("phonetic", ""),
                s.optString("pos", ""), s.optString("meaning", ""),
                (0 until s.arrLen("examples")).map { i ->
                    val e = s.objAt("examples", i)
                    VocabCard.Example(e.optString("en", ""), e.optString("zh", ""))
                },
                s.strArr("tags"),
            )
        })
        add(CardSpec("formula", "aiwrite", "公式卡：公式 + 变量释义（项目无 KaTeX，[^] 上标与 _下标_ 做极简渲染，其余原样）", """{"type":"formula","title":"质能方程","expr":"E = m c^2","vars":[{"name":"E","desc":"能量"},{"name":"m","desc":"质量"}],"note":"光速平方约为 9e16"}""") { s ->
            FormulaCard(
                s.id(), s.title(), s.optString("expr", ""),
                (0 until s.arrLen("vars")).map { i ->
                    val v = s.objAt("vars", i)
                    FormulaCard.Var(v.optString("name", ""), v.optString("desc", ""))
                },
                s.optString("note", ""),
            )
        })
        add(CardSpec("translate", "aiwrite", "翻译对照：源文/译文上下对照 + 备选译法（compare 是方案取舍，不是上下对照）", """{"type":"translate","title":"翻译","srcLang":"英文","dstLang":"中文","src":"Good morning, everyone.","dst":"大家早上好。","alt":["各位早安。"]}""") { s ->
            TranslateCard(
                s.id(), s.title(), s.optString("srcLang", ""), s.optString("dstLang", ""),
                s.optString("src", ""), s.optString("dst", ""), s.strArr("alt"), s.optString("audio", ""),
            )
        })
        add(CardSpec("palette", "decoration", "色卡：一组色 + 每色用途名（color 是单色块，palette 是一整套配色）", """{"type":"palette","title":"秋季配色","name":"陶土","colors":[{"name":"主色","hex":"#C96F4A"},{"name":"辅色","hex":"#E8C39E"},{"name":"底色","hex":"#F5EFE6"}],"copyable":true}""") { s ->
            PaletteCard(
                s.id(), s.title(), s.optString("name", ""),
                (0 until s.arrLen("colors")).map { i ->
                    val c = s.objAt("colors", i)
                    PaletteCard.Swatch(c.optString("name", ""), c.optString("hex", ""))
                },
                s.optBoolean("copyable", true),
            )
        })
        add(CardSpec("stopwatch", "input", "秒表（正计时累加，与 timer 的倒计时互补）", """{"type":"stopwatch","title":"耗时","label":"本次翻页耗时","seconds":0,"command":"ui_stopwatch"}""") { s ->
            StopwatchCard(s.id(), s.title(), s.optString("label", ""), s.optLong("seconds", 0L), s.optString("command", ""))
        })
        add(CardSpec("barcode", "media", "条码卡：一维码观感 + 码字可复制（项目无条码库，只给原文不画假码）", """{"type":"barcode","title":"商品条码","code":"6901234567892","format":"EAN13","caption":"扫码支付可识别"}""") { s ->
            BarcodeCard(s.id(), s.title(), s.optString("code", ""), s.optString("format", "CODE128"), s.optString("caption", ""))
        })
        add(CardSpec("spacer", "decoration", "垂直间隔（调整节奏）", """{"type":"spacer","height":16}""") { s ->
            SpacerCard(s.id(), s.title(), s.optInt("height", 16))
        })
    }

    /**
     * 合法类目白名单。
     *
     * 类目同时喂给目录页分组和提示词组名，拼错一个字母的结果是「这类组件在目录页
     * 整个消失、AI 也拿不到这个名字」—— 不报错、不崩溃，只是悄悄少一批能力。
     */
    private val KNOWN_CATEGORIES = setOf(
        "input", "data", "layout", "action", "media", "nav", "flow", "decoration", "aiwrite",
    )

    /** type → 定义。 */
    val byType: Map<String, CardSpec> = all.associateBy { it.type }

    /** type 集合。 */
    val types: Set<String> = byType.keys

    /** 去重后的 type 数量。 */
    val typeCount: Int get() = byType.size

    /** 分类 → 该类全部 type。 */
    val byCategory: Map<String, List<String>> = all.groupBy { it.category }.mapValues { (_, v) -> v.map { it.type } }

    /** 解析组件 spec。未知 type 落 [fallbackCard] 而不是返回 null（静默丢卡体验最差）。 */
    fun parse(spec: String): QuroChatCard? {
        val s = runCatching { JSONObject(spec) }.getOrNull() ?: return null
        return parseObj(s)
    }

    /** 解析 [JSONObject]。 */
    fun parseObj(s: JSONObject): QuroChatCard? {
        val type = s.optString("type", "").trim().lowercase()
        if (type.isEmpty()) return null
        val b = byType[type]?.builder ?: return fallbackCard(type, s)
        return runCatching { b(s) }.getOrNull() ?: fallbackCard(type, s)
    }

    /**
     * 未知 / 解析失败 type 的兜底卡片。
     *
     * 保留原始 JSON 而不是丢弃：渲染层能显示「AI 想画一个我没认识的组件」+ 原始 JSON，
     * 用户反馈时能直接看到 AI 到底输出了什么。
     */
    fun fallbackCard(type: String, s: JSONObject): QuroChatCard = CustomCard(
        id = s.optString("id", "").ifBlank { QuroChatCardStore.newId() },
        title = s.optString("title", "").ifBlank { "" },
        kind = type,
        payload = s.toString(),
        children = emptyList(),
    )

    /**
     * **紧凑清单**：一行一个类目，形如 `data(27): stat, table, ...`。
     *
     * ## 为什么要紧凑而不是把 [catalogJson] 整份塞进工具描述
     *
     * 92 种组件的完整样例加起来十几 KB，每轮对话都带进系统提示词会实打实吃掉上下文预算，
     * 而模型大多数时候只需要知道「有没有这种卡」；真要写复杂卡时再按需拉样例即可。
     * 所以拆成两级：
     *  - 常驻：compactCatalog()，约 2KB，进 ui_widget / ui_card 的工具描述；
     *  - 按需：[CardCatalogTool] 走 samples()/catalogJson() 拉完整样例。
     *
     * ## 为什么这个函数以前不存在是个真缺口
     *
     * 名册扩到 92 种，但工具描述里那份手写清单还停在 v1068 的四十来种 ——
     * 模型看不见新增的组件，只能靠围栏里撞见样例去猜。加组件而不接这一环，
     * 等于新组件只对「已经知道它存在」的模型有效，这正是要避免的静默失效。
     */
    fun compactCatalog(): String = byCategory.entries
        .sortedBy { CATEGORY_ORDER.indexOf(it.key) }
        .joinToString("; ") { (cat, list) ->
            "$cat(${list.size}): " + list.joinToString(",")
        }

    /**
     * 按类目/类型筛样例，供模型按需拉取完整用法。
     *
     * @param category 类目名，null/空 = 不限
     * @param types    指定 type 列表，null/空 = 该类目全部
     * @param maxChars 字符上限，防止一次把上下文吃光
     * @return JSON 文本；类目非法或无匹配时返回带提示的 JSON 数组（不抛异常，让模型能自我纠正）
     */
    fun samples(category: String?, types: List<String>?, maxChars: Int = 8000): String {
        val cat = category?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (cat != null && cat !in KNOWN_CATEGORIES) {
            return JSONArray().put(JSONObject().apply {
                put("error", "未知类目 $cat")
                put("known", JSONArray(KNOWN_CATEGORIES.toList()))
            }).toString()
        }
        val want = types?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
        val picked = all.filter { spec ->
            (cat == null || spec.category == cat) && (want.isEmpty() || spec.type in want)
        }
        val arr = JSONArray()
        var used = 2
        for (spec in picked) {
            val o = JSONObject().apply {
                put("type", spec.type)
                put("category", spec.category)
                put("description", spec.description)
                put("sample", spec.sample)
            }
            val line = o.toString()
            if (used + line.length + 1 > maxChars) {
                arr.put(JSONObject().apply {
                    put("type", spec.type)
                    put("note", "已截断，剩余 ${picked.size - arr.length()} 种请缩小 category/types 范围再取")
                })
                break
            }
            arr.put(o)
            used += line.length + 1
        }
        return arr.toString()
    }

    /** 紧凑清单的类目顺序（与目录页一致，缺后不影响正确性）。 */
    private val CATEGORY_ORDER = listOf(
        "input", "data", "layout", "action", "nav", "media", "flow", "decoration", "aiwrite",
    )

    /** 目录条目（字段与旧 `CARD_CATALOG` 一致，调用方零改动）。 */
    fun catalog(): List<CardTemplate> = all.map { CardTemplate(it.type, it.category, it.description, it.sample) }

    /** 目录 JSON（注入 AI 提示词用）。 */
    fun catalogJson(): String = JSONArray().also { a ->
        all.forEach { spec ->
            a.put(JSONObject().apply {
                put("type", spec.type); put("category", spec.category)
                put("description", spec.description); put("sample", spec.sample)
            })
        }
    }.toString()

    /**
     * 一致性自检，正常应返回空列表。由单测调用。
     *
     * 把「改六处漏一处」从**运行期静默失效**变成**构建期失败**。
     */
    fun lint(): List<String> {
        val issues = ArrayList<String>()
        all.groupBy { it.type }.filterValues { it.size > 1 }.forEach { (t, v) ->
            issues += "type 重复：$t 出现 ${v.size} 次"
        }
        // type 命名规范：normalizeType 会把 A2UI 的 PascalCase 名 snake_case 化后查名册，
        // 名册里混进 camelCase 就会让「同名组件从围栏进来时认不出来」这类玄学问题。
        val typeRe = Regex("^[a-z][a-z0-9_]*$")
        all.forEach { spec ->
            if (!typeRe.matches(spec.type)) issues += "type 不规范（须 snake_case）：${spec.type}"
        }
        // 样例自检：AI 照抄的是样例，样例错了 = 整类卡片全废，而且**编译不报、运行不报**
        all.forEach { spec ->
            val root = runCatching { JSONObject(spec.sample) }.getOrNull()
            if (root == null) {
                issues += "${spec.type}：样例不是合法 JSON"
                return@forEach
            }
            if (root.optString("type", "") != spec.type) {
                issues += "${spec.type}：样例里的 type 写成了 ${root.optString("type", "(空)")}"
            }
            if (spec.description.isBlank()) {
                issues += "${spec.type}：description 为空，AI 不知道什么时候该用"
            }
            if (spec.category !in KNOWN_CATEGORIES) {
                issues += "${spec.type}：未知类目 ${spec.category}（目录页会整类消失）"
            }
        }
        // 构造 + 往返：漏掉 encode 分支的卡片能解析、能渲染，但存档读回来变另一张卡
        all.forEach { spec ->
            val built = spec.builder?.let { b -> runCatching { b(JSONObject(spec.sample)) }.getOrNull() }
            if (built == null) {
                issues += "${spec.type}：样例无法构造"
                return@forEach
            }
            val round = runCatching { parseCard(serializeCard(built)) }.getOrNull()
            if (round == null) issues += "${spec.type}：serialize → parse 往返失败"
            else if (round::class != built::class) issues += "${spec.type}：往返后类型变了"
        }
        if (catalog().size != all.size) issues += "catalog 数量 ≠ all 数量"
        return issues
    }

    // ════════════════ JSON 取值助手 ════════════════

    /** id：缺省自动生成。 */
    fun JSONObject.id(): String = optString("id", "").ifBlank { QuroChatCardStore.newId() }

    /** title：缺省空串（不显示标题）。 */
    fun JSONObject.title(): String = optString("title", "").ifBlank { "" }

    /** 字符串 → 空串归一为 null。 */
    fun JSONObject.strOrNull(key: String): String? = optString(key, "").ifBlank { null }

    /** 从另一个对象取字符串 → 空串归一为 null。 */
    fun JSONObject.strOrNull2(src: JSONObject, key: String): String? = src.optString(key, "").ifBlank { null }

    /** 数组长度，缺省 0。 */
    fun JSONObject.arrLen(key: String): Int = optJSONArray(key)?.length() ?: 0

    /** 数组第 i 项，缺省返回空对象（调用方用 optString 会拿到默认值，安全）。 */
    fun JSONObject.objAt(key: String, i: Int): JSONObject = optJSONArray(key)?.optJSONObject(i) ?: JSONObject()

    /** 数组第 i 项，可为 null。 */
    fun JSONObject.objOrNull(key: String, i: Int): JSONObject? = optJSONArray(key)?.optJSONObject(i)

    /** 字符串数组。 */
    fun JSONObject.strArr(key: String): List<String> {
        val a = optJSONArray(key) ?: return emptyList()
        val out = ArrayList<String>(a.length())
        for (i in 0 until a.length()) if (a.isNull(i)) out += "" else out += a.optString(i, "")
        return out
    }

    /** Float 数组。 */
    fun JSONObject.floatArr(key: String): List<Float> {
        val a = optJSONArray(key) ?: return emptyList()
        val out = ArrayList<Float>(a.length())
        for (i in 0 until a.length()) out += a.optDouble(i, 0.0).toFloat()
        return out
    }

    /** Int 数组。 */
    fun JSONObject.intArr(key: String): List<Int> {
        val a = optJSONArray(key) ?: return emptyList()
        val out = ArrayList<Int>(a.length())
        for (i in 0 until a.length()) out += a.optInt(i, 0)
        return out
    }

    /** 二维字符串数组。 */
    fun JSONObject.rowsArrStr(key: String): List<List<String>> {
        val a = optJSONArray(key) ?: return emptyList()
        val out = ArrayList<List<String>>(a.length())
        for (i in 0 until a.length()) {
            val row = a.optJSONArray(i) ?: continue
            val r = ArrayList<String>(row.length())
            for (j in 0 until row.length()) r += row.optString(j, "")
            out += r
        }
        return out
    }

    /**
     * 倒计时目标时间。吃两种写法：epoch 毫秒数，或 `yyyy-MM-dd HH:mm:ss`。
     * 解析失败返回 [Long.MAX_VALUE]（永不结束）—— 宁可显示一个不倒数的卡，
     * 也不要因为时间格式不对就崩掉整张卡。
     */
    fun JSONObject.targetEpochMs(): Long {
        if (has("targetEpochMs")) return optLong("targetEpochMs", Long.MAX_VALUE)
        if (!has("target")) return Long.MAX_VALUE
        val raw = optString("target", "").trim()
        if (raw.isEmpty()) return Long.MAX_VALUE
        raw.toLongOrNull()?.let { return it }
        return parseTargetTime(raw)
    }

    /** 解析多种时间格式。失败返回 [Long.MAX_VALUE]。 */
    fun parseTargetTime(raw: String): Long {
        val patterns = listOf(
            "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss", "yyyy/MM/dd HH:mm:ss",
            "yyyy-MM-dd HH:mm", "yyyy-MM-dd",
        )
        for (p in patterns) {
            val t = runCatching {
                val fmt = java.text.SimpleDateFormat(p, java.util.Locale.US)
                fmt.isLenient = false
                fmt.parse(raw)?.time
            }.getOrNull()
            if (t != null) return t
        }
        return Long.MAX_VALUE
    }
}
