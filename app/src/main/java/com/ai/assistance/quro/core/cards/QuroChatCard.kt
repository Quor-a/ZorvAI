package com.ai.assistance.quro.core.cards

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 对话框内可交互的「富卡片 / UI 组件」模型（v132 起，v134 大幅扩展）。
 *
 * AI 通过 [com.ai.assistance.quro.core.tools.QuroToolsUiWidget] 的 `ui_widget` 工具下发结构化组件，
 * 组件在对话框底部卡片栏渲染为真正可交互的 Compose 控件（按钮触发动作、开关滑动、进度条、
 * 表格、评分、倒计时、标签页、表单……），直接在对话框里「展示出来」并随用户操作即时变化，
 * 而不是只能打开某个界面或只显示纯文本。
 *
 * 组件种类丰富（按钮/开关/滑块/进度/统计/提醒/表格/列表/分段/饼图/评分/倒计时/标签页/
 * 折叠/表单/标签/步骤/仪表/媒体/信息），每种又带大量可配置属性，组合即「几百款」UI 输出。
 */
sealed interface QuroChatCard {
    val id: String
    val title: String

    // ───────────── v132 历史组件 ─────────────
    data class TodoCard(
        override val id: String,
        override val title: String,
        val items: List<TodoItem>,
    ) : QuroChatCard {
        data class TodoItem(val text: String, val done: Boolean)
    }

    data class ChartCard(
        override val id: String,
        override val title: String,
        /** "bar" | "line" */
        val type: String,
        val series: List<SeriesPoint>,
    ) : QuroChatCard {
        data class SeriesPoint(val label: String, val value: Float)
    }

    data class NoteCard(
        override val id: String,
        override val title: String,
        val body: String,
        /** 代码语言（非空时等宽字体渲染，如 "kotlin" / "json" / "python"） */
        val lang: String?,
    ) : QuroChatCard

    data class ActionCard(
        override val id: String,
        override val title: String,
        /** command 可填 ui_open_* / ui_toggle_* / "linux:install" / "run:<cmd>" */
        val actions: List<CardAction>,
    ) : QuroChatCard {
        data class CardAction(val label: String, val command: String)
    }

    // ───────────── v134 新增内联交互组件 ─────────────

    /** 单个按钮：点击触发 command（ui_open_* / run:<cmd> 等）。 */
    data class ButtonCard(
        override val id: String,
        override val title: String,
        val label: String,
        val command: String,
        /** filled / outlined / tonal / text */
        val variant: String = "filled",
        val icon: String? = null,
    ) : QuroChatCard

    /** 开关：本地切换状态，可选 command 在变化时回传 AI。 */
    data class ToggleCard(
        override val id: String,
        override val title: String,
        val label: String,
        val checked: Boolean,
        val command: String = "",
    ) : QuroChatCard

    /** 滑块：本地拖动，可选 command 回传最终值。 */
    data class SliderCard(
        override val id: String,
        override val title: String,
        val label: String,
        val value: Float,
        val min: Float,
        val max: Float,
        val step: Float,
        val unit: String = "",
        val command: String = "",
    ) : QuroChatCard

    /** 进度条 0..100（或 0..max）。 */
    data class ProgressCard(
        override val id: String,
        override val title: String,
        val label: String,
        val value: Float,
        val max: Float = 100f,
        val suffix: String = "%",
    ) : QuroChatCard

    /** 统计数字 + 同比/环比 delta。 */
    data class StatCard(
        override val id: String,
        override val title: String,
        val label: String,
        val value: String,
        val unit: String = "",
        val delta: String = "",
        /** up / down / flat */
        val trend: String = "flat",
    ) : QuroChatCard

    /** 提醒条：severity = info / success / warning / error。 */
    data class AlertCard(
        override val id: String,
        override val title: String,
        val severity: String,
        val text: String,
    ) : QuroChatCard

    /** 表格。 */
    data class TableCard(
        override val id: String,
        override val title: String,
        val headers: List<String>,
        val rows: List<List<String>>,
    ) : QuroChatCard

    /** 可选项列表（可选中）。 */
    data class ListCard(
        override val id: String,
        override val title: String,
        val items: List<ListItem>,
        val selectable: Boolean = false,
        val command: String = "",
    ) : QuroChatCard {
        data class ListItem(val text: String, val sub: String = "", val selected: Boolean = false)
    }

    /** 分段选择器：选择一个选项，command 回传所选 index。 */
    data class SegmentedCard(
        override val id: String,
        override val title: String,
        val label: String,
        val options: List<String>,
        val selectedIndex: Int,
        val command: String = "",
    ) : QuroChatCard

    /** 饼图 / 占比环。 */
    data class PieCard(
        override val id: String,
        override val title: String,
        val segments: List<PieSeg>,
    ) : QuroChatCard {
        data class PieSeg(val name: String, val value: Float, val color: String)
    }

    /** 星级评分。 */
    data class RatingCard(
        override val id: String,
        override val title: String,
        val label: String,
        val max: Int,
        val value: Int,
        val command: String = "",
    ) : QuroChatCard

    /** 倒计时：targetEpochMs 为目标时刻。 */
    data class CountdownCard(
        override val id: String,
        override val title: String,
        val label: String,
        val targetEpochMs: Long,
    ) : QuroChatCard

    /** 标签页：多个 Tab 切换查看内容。 */
    data class TabsCard(
        override val id: String,
        override val title: String,
        val tabs: List<Tab>,
        val selectedIndex: Int,
    ) : QuroChatCard {
        data class Tab(val title: String, val body: String)
    }

    /** 可折叠文本块。 */
    data class ExpandableCard(
        override val id: String,
        override val title: String,
        val body: String,
        val expanded: Boolean,
    ) : QuroChatCard

    /** 表单：若干输入项 + 提交按钮（command 回传填写结果）。 */
    data class FormCard(
        override val id: String,
        override val title: String,
        val fields: List<FormField>,
        val submitCommand: String,
    ) : QuroChatCard {
        data class FormField(
            val key: String,
            val label: String,
            val value: String,
            val placeholder: String = "",
            val secret: Boolean = false,
        )
    }

    /** 标签组：可选中（单选/多选），command 回传所选。 */
    data class ChipsCard(
        override val id: String,
        override val title: String,
        val label: String,
        val chips: List<String>,
        val selected: List<String>,
        val multi: Boolean,
        val command: String = "",
    ) : QuroChatCard

    /** 步骤条：current 高亮当前步。 */
    data class StepsCard(
        override val id: String,
        override val title: String,
        val steps: List<Step>,
        val current: Int,
    ) : QuroChatCard {
        data class Step(val title: String, val status: String) // done / active / todo
    }

    /** 仪表盘（环形进度）。 */
    data class GaugeCard(
        override val id: String,
        override val title: String,
        val label: String,
        val value: Float,
        val max: Float = 100f,
        val unit: String = "%",
    ) : QuroChatCard

    /** 媒体：image / audio / video 链接。 */
    data class MediaCard(
        override val id: String,
        override val title: String,
        val mediaUrl: String,
        val mediaType: String,
    ) : QuroChatCard

    /** 信息块（纯文本，可对齐）。 */
    data class InfoCard(
        override val id: String,
        override val title: String,
        val body: String,
        val align: String = "start",
    ) : QuroChatCard

    // ───────────── v135 升级：工具调用 / 流式 / 媒体播放 ─────────────

    /** AI 工具调用时间线：状态 pending / running / done / error，附进度与日志。 */
    data class ToolCallCard(
        override val id: String,
        override val title: String,
        val tool: String,
        val status: String = "pending",
        val progress: Float = 0f,
        val message: String = "",
    ) : QuroChatCard

    /** 流式输出卡片：逐行追加的实时输出（日志 / 生成过程）。 */
    data class StreamCard(
        override val id: String,
        override val title: String,
        val lines: List<String> = emptyList(),
    ) : QuroChatCard

    /** 内联媒体播放卡片：audio / video，点击即调起应用内播放器。 */
    data class MediaPlayCard(
        override val id: String,
        override val title: String,
        val mediaType: String,
        val uri: String,
        val label: String = "",
    ) : QuroChatCard

    // ───────────── v149 升级：气泡内富组件（关联功能 / 自由化 / 气泡自包含） ─────────────

    /** 快捷回复：点击建议直接回发聊天（组件驱动对话，让气泡自化）。reply 文本经 command 路由 send()。 */
    data class QuickReplyCard(
        override val id: String,
        override val title: String,
        val replies: List<String>,
        val multi: Boolean = false,
    ) : QuroChatCard

    /** 快捷动作宫格：每个磁贴触发 command（打开应用内任意功能，自由化）。 */
    data class QuickActionCard(
        override val id: String,
        override val title: String,
        val actions: List<QuickAction>,
    ) : QuroChatCard {
        data class QuickAction(val label: String, val icon: String, val command: String)
    }

    /** 时间线：纵向事件流（时间 / 标题 / 描述 / 状态）。 */
    data class TimelineCard(
        override val id: String,
        override val title: String,
        val events: List<TimeEvent>,
    ) : QuroChatCard {
        data class TimeEvent(val time: String, val title: String, val desc: String = "", val status: String = "done")
    }

    /** 日历热力图：values 为每日强度（0..max），按周（7×weeks）排列。 */
    data class HeatmapCard(
        override val id: String,
        override val title: String,
        val values: List<Int>,
        val weeks: Int = 12,
        val label: String = "",
    ) : QuroChatCard

    /** 双栏对比：left / right 各一组要点，正反向不同色调。 */
    data class CompareCard(
        override val id: String,
        override val title: String,
        val left: CompareSide,
        val right: CompareSide,
    ) : QuroChatCard {
        data class CompareSide(val title: String, val points: List<String>, val positive: Boolean)
    }

    /** 雷达图：多维能力（axes.value 0..100）。 */
    data class RadarCard(
        override val id: String,
        override val title: String,
        val axes: List<RadarAxis>,
    ) : QuroChatCard {
        data class RadarAxis(val name: String, val value: Float) // 0..100
    }

    /** 交互计时器：开始/暂停/重置，结束时可选回传 command。 */
    data class TimerCard(
        override val id: String,
        override val title: String,
        val seconds: Int,
        val command: String = "",
    ) : QuroChatCard

    /** 轮播卡片：左右滑动查看多张特性卡。 */
    data class CarouselCard(
        override val id: String,
        override val title: String,
        val slides: List<Slide>,
    ) : QuroChatCard {
        data class Slide(val title: String, val body: String, val color: String = "")
    }

    /** 看板：多列任务。 */
    data class KanbanCard(
        override val id: String,
        override val title: String,
        val columns: List<KanbanColumn>,
    ) : QuroChatCard {
        data class KanbanColumn(val name: String, val items: List<String>)
    }

    /** 链接回答单条链接（多链接卡片里的一行）。 */
    data class YuanbaoLink(val title: String, val url: String)

    /** 链接回答预览卡：气泡内点击即在应用内浏览器打开该回答（原生安卓点击查看体验）。
     *  v294：支持多链接——[links] 非空时渲染多行，否则回退单 [url]；二者皆空时由渲染层用预设清单兜底。 */
    data class YuanbaoCard(
        override val id: String,
        override val title: String,
        val url: String,
        val links: List<YuanbaoLink> = emptyList(),
    ) : QuroChatCard

    // ───────────── v221 富事件 / 声明式目录新增卡片 ─────────────

    /** 调色板：一组颜色色块。点击色块复制其十六进制值；若 command 非空则改为触发 command（如 screen: / ai:）。 */
    data class ColorCard(
        override val id: String,
        override val title: String,
        val colors: List<String>,
        val label: String = "",
        val command: String = "",
    ) : QuroChatCard

    /** 计数器：± 步进，本地持久化 value（见 QuroChatCardStore.setCounter），变更时回传 command。 */
    data class CounterCard(
        override val id: String,
        override val title: String,
        val label: String = "",
        val value: Int = 0,
        val min: Int = 0,
        val max: Int = 100,
        val step: Int = 1,
        val command: String = "",
    ) : QuroChatCard

    /** 面包屑导航：层级路径，点击某级触发其 command。 */
    data class BreadcrumbCard(
        override val id: String,
        override val title: String,
        val crumbs: List<Breadcrumb>,
    ) : QuroChatCard {
        data class Breadcrumb(val label: String, val command: String)
    }

    /** 标签云：按权重（weight）缩放字号的可点击标签。 */
    data class TagCloudCard(
        override val id: String,
        override val title: String,
        val tags: List<Tag>,
    ) : QuroChatCard {
        data class Tag(val label: String, val weight: Int = 1, val command: String = "")
    }

    /** 徽章组：彩色徽章集合，点击触发各自 command。 */
    data class BadgeCard(
        override val id: String,
        override val title: String,
        val badges: List<Badge>,
    ) : QuroChatCard {
        data class Badge(val label: String, val color: String = "", val command: String = "")
    }

    /** 头像组：重叠头像（url 为空时显示姓名首字），点击触发 command。 */
    data class AvatarGroupCard(
        override val id: String,
        override val title: String,
        val avatars: List<Avatar>,
    ) : QuroChatCard {
        data class Avatar(val name: String, val url: String = "", val command: String = "")
    }

    // ───────────── v300 可视化编程 / AI 自写图表 ─────────────

    /**
     * AI 自写的可视化图表（v300 新增）：客户端**不内置任何固定流程图**，
     * 只渲染 AI 通过 `source` 下发的 Mermaid 文本（flowchart / sequenceDiagram /
     * stateDiagram-v2 / classDiagram / mindmap / gitGraph / pie / timeline …）。
     * 即「要可视化的 AI 自己写出来，而不是内置」——客户端仅提供通用 Mermaid 渲染器。
     *
     * @param source AI 生成的 Mermaid 源码（多行字符串）
     * @param theme  可选主题：default | dark | forest | neutral | base；缺省时按系统深浅色自动选 default/dark
     */
    data class MermaidCard(
        override val id: String,
        override val title: String,
        val source: String,
        val theme: String = "",
    ) : QuroChatCard
}

/**
 * 解析 UI 组件 JSON 规格为 [QuroChatCard]。
 * 供 `ui_widget` 工具（底部卡片栏）与聊天消息内联组件（AI 在文本里下发组件 JSON）共用同一套解析，
 * 保证「工具下发」与「文本内联」渲染完全一致。
 *
 * - 合法组件：返回对应 [QuroChatCard]；
 * - 非法（未知 type / 字段缺失 / JSON 损坏）：返回 null，由调用方决定是报错还是忽略。
 * - title 缺省为 ""（不显示标题），避免 AI 漏写 title 时出现难看的「组件」占位字。
 */
fun parseComponentSpec(spec: String): QuroChatCard? {
    return try {
        val s = JSONObject(spec)
        val type = s.optString("type", "").trim().lowercase()
        val title = s.optString("title", "").ifBlank { "" }
        val id = s.optString("id", QuroChatCardStore.newId())
        when (type) {
            // ── v134 内联交互组件 ──
            "button" -> QuroChatCard.ButtonCard(
                id, title, s.optString("label", "按钮"),
                s.optString("command", ""), s.optString("variant", "filled"), s.optString("icon", "").ifBlank { null },
            )
            "toggle" -> QuroChatCard.ToggleCard(
                id, title, s.optString("label", ""), s.optBoolean("checked", false), s.optString("command", ""),
            )
            "slider" -> QuroChatCard.SliderCard(
                id, title, s.optString("label", ""),
                s.optDouble("value", 0.0).toFloat(),
                s.optDouble("min", 0.0).toFloat(),
                s.optDouble("max", 100.0).toFloat(),
                s.optDouble("step", 1.0).toFloat(),
                s.optString("unit", ""), s.optString("command", ""),
            )
            "progress" -> QuroChatCard.ProgressCard(
                id, title, s.optString("label", ""),
                s.optDouble("value", 0.0).toFloat(),
                s.optDouble("max", 100.0).toFloat(), s.optString("suffix", "%"),
            )
            "stat" -> QuroChatCard.StatCard(
                id, title, s.optString("label", ""), s.optString("value", ""),
                s.optString("unit", ""), s.optString("delta", ""), s.optString("trend", "flat"),
            )
            "alert" -> QuroChatCard.AlertCard(
                id, title, s.optString("severity", "info"), s.optString("text", ""),
            )
            "table" -> QuroChatCard.TableCard(
                id, title,
                arrStr(s.optJSONArray("headers")),
                arrArrStr(s.optJSONArray("rows")),
            )
            "list" -> QuroChatCard.ListCard(
                id, title,
                s.optJSONArray("items")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
                        QuroChatCard.ListCard.ListItem(
                            it.optString("text", ""),
                            it.optString("sub", ""),
                            it.optBoolean("selected", false),
                        )
                    }
                } ?: emptyList(),
                s.optBoolean("selectable", false), s.optString("command", ""),
            )
            "segmented" -> QuroChatCard.SegmentedCard(
                id, title, s.optString("label", ""),
                arrStr(s.optJSONArray("options")),
                s.optInt("selectedIndex", 0), s.optString("command", ""),
            )
            "pie" -> QuroChatCard.PieCard(
                id, title,
                s.optJSONArray("segments")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
                        QuroChatCard.PieCard.PieSeg(
                            it.optString("name", ""),
                            runCatching { it.optDouble("value", 0.0).toFloat() }.getOrDefault(0f),
                            it.optString("color", ""),
                        )
                    }
                } ?: emptyList(),
            )
            "rating" -> QuroChatCard.RatingCard(
                id, title, s.optString("label", ""),
                s.optInt("max", 5), s.optInt("value", 0), s.optString("command", ""),
            )
            "countdown" -> QuroChatCard.CountdownCard(
                id, title, s.optString("label", ""),
                parseTarget(s.opt("target")),
            )
            "tabs" -> QuroChatCard.TabsCard(
                id, title,
                s.optJSONArray("tabs")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
                        QuroChatCard.TabsCard.Tab(it.optString("title", ""), it.optString("body", ""))
                    }
                } ?: emptyList(),
                s.optInt("selectedIndex", 0),
            )
            "expandable" -> QuroChatCard.ExpandableCard(
                id, title, s.optString("body", ""), s.optBoolean("expanded", false),
            )
            "form" -> QuroChatCard.FormCard(
                id, title,
                s.optJSONArray("fields")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
                        QuroChatCard.FormCard.FormField(
                            it.optString("key", ""), it.optString("label", ""),
                            it.optString("value", ""), it.optString("placeholder", ""),
                            it.optBoolean("secret", false),
                        )
                    }
                } ?: emptyList(),
                s.optString("submitCommand", ""),
            )
            "chips" -> QuroChatCard.ChipsCard(
                id, title, s.optString("label", ""),
                arrStr(s.optJSONArray("chips")),
                arrStr(s.optJSONArray("selected")),
                s.optBoolean("multi", false), s.optString("command", ""),
            )
            "steps" -> QuroChatCard.StepsCard(
                id, title,
                s.optJSONArray("steps")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
                        QuroChatCard.StepsCard.Step(it.optString("title", ""), it.optString("status", "todo"))
                    }
                } ?: emptyList(),
                s.optInt("current", 0),
            )
            "gauge" -> QuroChatCard.GaugeCard(
                id, title, s.optString("label", ""),
                s.optDouble("value", 0.0).toFloat(),
                s.optDouble("max", 100.0).toFloat(), s.optString("unit", "%"),
            )
            "media" -> QuroChatCard.MediaCard(
                id, title, s.optString("mediaUrl", ""), s.optString("mediaType", "image"),
            )
            "info" -> QuroChatCard.InfoCard(
                id, title, s.optString("body", ""), s.optString("align", "start"),
            )
            // ── v135 工具调用 / 流式 / 媒体播放 ──
            "toolcall" -> QuroChatCard.ToolCallCard(
                id, title,
                s.optString("tool", ""),
                s.optString("status", "pending"),
                s.optDouble("progress", 0.0).toFloat(),
                s.optString("message", ""),
            )
            "stream" -> QuroChatCard.StreamCard(
                id, title,
                s.optJSONArray("lines")?.let { arr ->
                    (0 until arr.length()).map { arr.optString(it, "") }
                } ?: emptyList(),
            )
            "mediaplay" -> QuroChatCard.MediaPlayCard(
                id, title,
                s.optString("mediaType", "audio"),
                s.optString("uri", ""),
                s.optString("label", ""),
            )
            // ── v132 legacy 兼容 ──
            "todo" -> {
                val items = arrayListOf<QuroChatCard.TodoCard.TodoItem>()
                val arr = s.optJSONArray("items") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val it = arr.optJSONObject(i) ?: continue
                    items.add(QuroChatCard.TodoCard.TodoItem(it.optString("text", ""), it.optBoolean("done", false)))
                }
                QuroChatCard.TodoCard(id, title, items)
            }
            "chart" -> {
                val series = arrayListOf<QuroChatCard.ChartCard.SeriesPoint>()
                val arr = s.optJSONArray("series") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val it = arr.optJSONObject(i) ?: continue
                    val v = runCatching { it.optDouble("value", 0.0).toFloat() }.getOrDefault(0f)
                    series.add(QuroChatCard.ChartCard.SeriesPoint(it.optString("label", ""), v))
                }
                QuroChatCard.ChartCard(id, title, s.optString("chart_type", "bar"), series)
            }
            "note" -> QuroChatCard.NoteCard(id, title, s.optString("body", ""), s.optString("lang", "").ifBlank { null })
            "actions" -> {
                val actions = arrayListOf<QuroChatCard.ActionCard.CardAction>()
                val arr = s.optJSONArray("actions") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val it = arr.optJSONObject(i) ?: continue
                    actions.add(QuroChatCard.ActionCard.CardAction(it.optString("label", "动作"), it.optString("command", "")))
                }
                QuroChatCard.ActionCard(id, title, actions)
            }
            // ── v149 气泡内富组件（关联功能 / 自由化） ──
            "quickreply" -> QuroChatCard.QuickReplyCard(
                id, title,
                arrStr(s.optJSONArray("replies")),
                s.optBoolean("multi", false),
            )
            "quickaction" -> QuroChatCard.QuickActionCard(
                id, title,
                s.optJSONArray("actions")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
                        QuroChatCard.QuickActionCard.QuickAction(
                            it.optString("label", ""),
                            it.optString("icon", "sparkles"),
                            it.optString("command", ""),
                        )
                    }
                } ?: emptyList(),
            )
            "timeline" -> QuroChatCard.TimelineCard(
                id, title,
                s.optJSONArray("events")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
                        QuroChatCard.TimelineCard.TimeEvent(
                            it.optString("time", ""),
                            it.optString("title", ""),
                            it.optString("desc", ""),
                            it.optString("status", "done"),
                        )
                    }
                } ?: emptyList(),
            )
            "heatmap" -> QuroChatCard.HeatmapCard(
                id, title,
                s.optJSONArray("values")?.let { arr -> (0 until arr.length()).map { arr.optInt(it, 0) } } ?: emptyList(),
                s.optInt("weeks", 12), s.optString("label", ""),
            )
            "compare" -> run {
                fun side(prefix: String) = QuroChatCard.CompareCard.CompareSide(
                    s.optString("${prefix}_title", prefix),
                    arrStr(s.optJSONArray("${prefix}_points")),
                    s.optBoolean("${prefix}_positive", prefix == "left"),
                )
                QuroChatCard.CompareCard(id, title, side("left"), side("right"))
            }
            "radar" -> QuroChatCard.RadarCard(
                id, title,
                s.optJSONArray("axes")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
                        QuroChatCard.RadarCard.RadarAxis(
                            it.optString("name", ""),
                            runCatching { it.optDouble("value", 0.0).toFloat() }.getOrDefault(0f).coerceIn(0f, 100f),
                        )
                    }
                } ?: emptyList(),
            )
            "timer" -> QuroChatCard.TimerCard(
                id, title, s.optInt("seconds", 0), s.optString("command", ""),
            )
            "carousel" -> QuroChatCard.CarouselCard(
                id, title,
                s.optJSONArray("slides")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
                        QuroChatCard.CarouselCard.Slide(
                            it.optString("title", ""),
                            it.optString("body", ""),
                            it.optString("color", ""),
                        )
                    }
                } ?: emptyList(),
            )
            "kanban" -> QuroChatCard.KanbanCard(
                id, title,
                s.optJSONArray("columns")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
                        QuroChatCard.KanbanCard.KanbanColumn(
                            it.optString("name", ""),
                            arrStr(it.optJSONArray("items")),
                        )
                    }
                } ?: emptyList(),
            )
            // ── v221 富事件 / 声明式目录新增卡片 ──
            "color" -> QuroChatCard.ColorCard(
                id, title,
                s.optJSONArray("colors")?.let { arr -> (0 until arr.length()).map { arr.optString(it, "") } } ?: emptyList(),
                s.optString("label", ""),
                s.optString("command", ""),
            )
            "counter" -> QuroChatCard.CounterCard(
                id, title,
                s.optString("label", ""),
                s.optInt("value", 0), s.optInt("min", 0), s.optInt("max", 100), s.optInt("step", 1),
                s.optString("command", ""),
            )
            "breadcrumb" -> QuroChatCard.BreadcrumbCard(
                id, title,
                s.optJSONArray("crumbs")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
                        QuroChatCard.BreadcrumbCard.Breadcrumb(it.optString("label", ""), it.optString("command", ""))
                    }
                } ?: emptyList(),
            )
            "tagcloud" -> QuroChatCard.TagCloudCard(
                id, title,
                s.optJSONArray("tags")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
                        QuroChatCard.TagCloudCard.Tag(it.optString("label", ""), it.optInt("weight", 1), it.optString("command", ""))
                    }
                } ?: emptyList(),
            )
            "badge" -> QuroChatCard.BadgeCard(
                id, title,
                s.optJSONArray("badges")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
                        QuroChatCard.BadgeCard.Badge(it.optString("label", ""), it.optString("color", ""), it.optString("command", ""))
                    }
                } ?: emptyList(),
            )
            "avatargroup" -> QuroChatCard.AvatarGroupCard(
                id, title,
                s.optJSONArray("avatars")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
                        QuroChatCard.AvatarGroupCard.Avatar(it.optString("name", ""), it.optString("url", ""), it.optString("command", ""))
                    }
                } ?: emptyList(),
            )
            // ── v300 可视化编程 / AI 自写图表 ──
            "mermaid" -> QuroChatCard.MermaidCard(
                id, title,
                s.optString("source", "").ifBlank { s.optString("text", "") },
                s.optString("theme", "").ifBlank { "" },
            )
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * 把 [QuroChatCard] 序列化为 JSON（落盘用）。
 * 与 [parseComponentSpec] 互逆的是 [parseCard]（从落盘 JSON 还原），二者字段一一对应。
 * 判别字段 `cardType` 使用与 parseComponentSpec 一致的稳定小写类型名（不依赖类名反射，抗混淆）。
 */
/** 序列化方向：List<String> -> JSONArray */
private fun strArr(list: List<String>): JSONArray =
    JSONArray().also { a -> list.forEach { a.put(it) } }

/** 序列化方向：List<List<String>> -> JSONArray(JSONArray) */
private fun strArrArr(list: List<List<String>>): JSONArray =
    JSONArray().also { a -> list.forEach { row -> a.put(strArr(row)) } }

fun serializeCard(card: QuroChatCard): JSONObject {
    val o = JSONObject()
    o.put("cardType", when (card) {
        is QuroChatCard.TodoCard -> "todo"
        is QuroChatCard.ChartCard -> "chart"
        is QuroChatCard.NoteCard -> "note"
        is QuroChatCard.ActionCard -> "actions"
        is QuroChatCard.ButtonCard -> "button"
        is QuroChatCard.ToggleCard -> "toggle"
        is QuroChatCard.SliderCard -> "slider"
        is QuroChatCard.ProgressCard -> "progress"
        is QuroChatCard.StatCard -> "stat"
        is QuroChatCard.AlertCard -> "alert"
        is QuroChatCard.TableCard -> "table"
        is QuroChatCard.ListCard -> "list"
        is QuroChatCard.SegmentedCard -> "segmented"
        is QuroChatCard.PieCard -> "pie"
        is QuroChatCard.RatingCard -> "rating"
        is QuroChatCard.CountdownCard -> "countdown"
        is QuroChatCard.TabsCard -> "tabs"
        is QuroChatCard.ExpandableCard -> "expandable"
        is QuroChatCard.FormCard -> "form"
        is QuroChatCard.ChipsCard -> "chips"
        is QuroChatCard.StepsCard -> "steps"
        is QuroChatCard.GaugeCard -> "gauge"
        is QuroChatCard.MediaCard -> "media"
        is QuroChatCard.InfoCard -> "info"
        is QuroChatCard.ToolCallCard -> "toolcall"
        is QuroChatCard.StreamCard -> "stream"
        is QuroChatCard.MediaPlayCard -> "mediaplay"
        is QuroChatCard.QuickReplyCard -> "quickreply"
        is QuroChatCard.QuickActionCard -> "quickaction"
        is QuroChatCard.TimelineCard -> "timeline"
        is QuroChatCard.HeatmapCard -> "heatmap"
        is QuroChatCard.CompareCard -> "compare"
        is QuroChatCard.RadarCard -> "radar"
        is QuroChatCard.TimerCard -> "timer"
        is QuroChatCard.CarouselCard -> "carousel"
        is QuroChatCard.KanbanCard -> "kanban"
        is QuroChatCard.YuanbaoCard -> "yuanbao"
        is QuroChatCard.ColorCard -> "color"
        is QuroChatCard.CounterCard -> "counter"
        is QuroChatCard.BreadcrumbCard -> "breadcrumb"
        is QuroChatCard.TagCloudCard -> "tagcloud"
        is QuroChatCard.BadgeCard -> "badge"
        is QuroChatCard.AvatarGroupCard -> "avatargroup"
        is QuroChatCard.MermaidCard -> "mermaid"
    })
    o.put("id", card.id)
    o.put("title", card.title)
    when (card) {
        is QuroChatCard.TodoCard -> o.put("items", JSONArray().also { a ->
            card.items.forEach { i -> a.put(JSONObject().apply { put("text", i.text); put("done", i.done) }) }
        })
        is QuroChatCard.ChartCard -> {
            o.put("type", card.type)
            o.put("series", JSONArray().also { a ->
                card.series.forEach { s -> a.put(JSONObject().apply { put("label", s.label); put("value", s.value) }) }
            })
        }
        is QuroChatCard.NoteCard -> {
            o.put("body", card.body)
            o.put("lang", card.lang ?: JSONObject.NULL)
        }
        is QuroChatCard.ActionCard -> o.put("actions", JSONArray().also { a ->
            card.actions.forEach { ac -> a.put(JSONObject().apply { put("label", ac.label); put("command", ac.command) }) }
        })
        is QuroChatCard.ButtonCard -> {
            o.put("label", card.label); o.put("command", card.command); o.put("variant", card.variant)
            o.put("icon", card.icon ?: JSONObject.NULL)
        }
        is QuroChatCard.ToggleCard -> {
            o.put("label", card.label); o.put("checked", card.checked); o.put("command", card.command)
        }
        is QuroChatCard.SliderCard -> {
            o.put("label", card.label); o.put("value", card.value); o.put("min", card.min); o.put("max", card.max)
            o.put("step", card.step); o.put("unit", card.unit); o.put("command", card.command)
        }
        is QuroChatCard.ProgressCard -> {
            o.put("label", card.label); o.put("value", card.value); o.put("max", card.max); o.put("suffix", card.suffix)
        }
        is QuroChatCard.StatCard -> {
            o.put("label", card.label); o.put("value", card.value); o.put("unit", card.unit)
            o.put("delta", card.delta); o.put("trend", card.trend)
        }
        is QuroChatCard.AlertCard -> { o.put("severity", card.severity); o.put("text", card.text) }
        is QuroChatCard.TableCard -> { o.put("headers", strArr(card.headers)); o.put("rows", strArrArr(card.rows)) }
        is QuroChatCard.ListCard -> {
            o.put("items", JSONArray().also { a ->
                card.items.forEach { i -> a.put(JSONObject().apply { put("text", i.text); put("sub", i.sub); put("selected", i.selected) }) }
            })
            o.put("selectable", card.selectable); o.put("command", card.command)
        }
        is QuroChatCard.SegmentedCard -> {
            o.put("label", card.label); o.put("options", strArr(card.options))
            o.put("selectedIndex", card.selectedIndex); o.put("command", card.command)
        }
        is QuroChatCard.PieCard -> o.put("segments", JSONArray().also { a ->
            card.segments.forEach { s -> a.put(JSONObject().apply { put("name", s.name); put("value", s.value); put("color", s.color) }) }
        })
        is QuroChatCard.RatingCard -> {
            o.put("label", card.label); o.put("max", card.max); o.put("value", card.value); o.put("command", card.command)
        }
        is QuroChatCard.CountdownCard -> { o.put("label", card.label); o.put("targetEpochMs", card.targetEpochMs) }
        is QuroChatCard.TabsCard -> {
            o.put("tabs", JSONArray().also { a ->
                card.tabs.forEach { t -> a.put(JSONObject().apply { put("title", t.title); put("body", t.body) }) }
            })
            o.put("selectedIndex", card.selectedIndex)
        }
        is QuroChatCard.ExpandableCard -> { o.put("body", card.body); o.put("expanded", card.expanded) }
        is QuroChatCard.FormCard -> {
            o.put("fields", JSONArray().also { a ->
                card.fields.forEach { f -> a.put(JSONObject().apply {
                    put("key", f.key); put("label", f.label); put("value", f.value)
                    put("placeholder", f.placeholder); put("secret", f.secret)
                }) }
            })
            o.put("submitCommand", card.submitCommand)
        }
        is QuroChatCard.ChipsCard -> {
            o.put("label", card.label); o.put("chips", strArr(card.chips)); o.put("selected", strArr(card.selected))
            o.put("multi", card.multi); o.put("command", card.command)
        }
        is QuroChatCard.StepsCard -> {
            o.put("steps", JSONArray().also { a ->
                card.steps.forEach { s -> a.put(JSONObject().apply { put("title", s.title); put("status", s.status) }) }
            })
            o.put("current", card.current)
        }
        is QuroChatCard.GaugeCard -> {
            o.put("label", card.label); o.put("value", card.value); o.put("max", card.max); o.put("unit", card.unit)
        }
        is QuroChatCard.MediaCard -> { o.put("mediaUrl", card.mediaUrl); o.put("mediaType", card.mediaType) }
        is QuroChatCard.InfoCard -> { o.put("body", card.body); o.put("align", card.align) }
        is QuroChatCard.ToolCallCard -> {
            o.put("tool", card.tool); o.put("status", card.status); o.put("progress", card.progress); o.put("message", card.message)
        }
        is QuroChatCard.StreamCard -> o.put("lines", strArr(card.lines))
        is QuroChatCard.MediaPlayCard -> { o.put("mediaType", card.mediaType); o.put("uri", card.uri); o.put("label", card.label) }
        is QuroChatCard.QuickReplyCard -> { o.put("replies", strArr(card.replies)); o.put("multi", card.multi) }
        is QuroChatCard.QuickActionCard -> o.put("actions", JSONArray().also { a ->
            card.actions.forEach { ac -> a.put(JSONObject().apply { put("label", ac.label); put("icon", ac.icon); put("command", ac.command) }) }
        })
        is QuroChatCard.TimelineCard -> o.put("events", JSONArray().also { a ->
            card.events.forEach { e -> a.put(JSONObject().apply { put("time", e.time); put("title", e.title); put("desc", e.desc); put("status", e.status) }) }
        })
        is QuroChatCard.HeatmapCard -> {
            o.put("values", JSONArray().also { a -> card.values.forEach { a.put(it) } })
            o.put("weeks", card.weeks); o.put("label", card.label)
        }
        is QuroChatCard.CompareCard -> {
            fun side(name: String, s: QuroChatCard.CompareCard.CompareSide) = JSONObject().apply {
                put("title", s.title); put("points", strArr(s.points)); put("positive", s.positive)
            }
            o.put("left", side("left", card.left)); o.put("right", side("right", card.right))
        }
        is QuroChatCard.RadarCard -> o.put("axes", JSONArray().also { a ->
            card.axes.forEach { ax -> a.put(JSONObject().apply { put("name", ax.name); put("value", ax.value) }) }
        })
        is QuroChatCard.TimerCard -> { o.put("seconds", card.seconds); o.put("command", card.command) }
        is QuroChatCard.CarouselCard -> o.put("slides", JSONArray().also { a ->
            card.slides.forEach { s -> a.put(JSONObject().apply { put("title", s.title); put("body", s.body); put("color", s.color) }) }
        })
        is QuroChatCard.KanbanCard -> o.put("columns", JSONArray().also { a ->
            card.columns.forEach { c -> a.put(JSONObject().apply { put("name", c.name); put("items", strArr(c.items)) }) }
        })
        is QuroChatCard.YuanbaoCard -> {
            o.put("url", card.url)
            o.put("links", JSONArray().also { a ->
                card.links.forEach { l -> a.put(JSONObject().apply { put("title", l.title); put("url", l.url) }) }
            })
        }
        is QuroChatCard.ColorCard -> {
            o.put("colors", strArr(card.colors))
            o.put("label", card.label); o.put("command", card.command)
        }
        is QuroChatCard.CounterCard -> {
            o.put("label", card.label); o.put("value", card.value); o.put("min", card.min)
            o.put("max", card.max); o.put("step", card.step); o.put("command", card.command)
        }
        is QuroChatCard.BreadcrumbCard -> o.put("crumbs", JSONArray().also { a ->
            card.crumbs.forEach { c -> a.put(JSONObject().apply { put("label", c.label); put("command", c.command) }) }
        })
        is QuroChatCard.TagCloudCard -> o.put("tags", JSONArray().also { a ->
            card.tags.forEach { t -> a.put(JSONObject().apply { put("label", t.label); put("weight", t.weight); put("command", t.command) }) }
        })
        is QuroChatCard.BadgeCard -> o.put("badges", JSONArray().also { a ->
            card.badges.forEach { b -> a.put(JSONObject().apply { put("label", b.label); put("color", b.color); put("command", b.command) }) }
        })
        is QuroChatCard.AvatarGroupCard -> o.put("avatars", JSONArray().also { a ->
            card.avatars.forEach { av -> a.put(JSONObject().apply { put("name", av.name); put("url", av.url); put("command", av.command) }) }
        })
        is QuroChatCard.MermaidCard -> {
            o.put("source", card.source)
            o.put("theme", card.theme.ifBlank { JSONObject.NULL })
        }
    }
    return o
}

/**
 * 从落盘 JSON 还原 [QuroChatCard]（[serializeCard] 的逆操作）。
 * 解析失败的卡片返回 null，由调用方忽略——绝不抛出，保证历史数据加载不中断。
 */
fun parseCard(o: JSONObject): QuroChatCard? {
    return runCatching {
        val t = o.optString("cardType", "")
        val id = o.optString("id", "")
        val title = o.optString("title", "")
        when (t) {
            "todo" -> QuroChatCard.TodoCard(id, title, (0 until (o.optJSONArray("items")?.length() ?: 0)).map { i ->
                val it = o.optJSONArray("items")!!.optJSONObject(i)
                QuroChatCard.TodoCard.TodoItem(it.optString("text", ""), it.optBoolean("done", false))
            })
            "chart" -> QuroChatCard.ChartCard(id, title, o.optString("type", "bar"), (0 until (o.optJSONArray("series")?.length() ?: 0)).map { i ->
                val it = o.optJSONArray("series")!!.optJSONObject(i)
                QuroChatCard.ChartCard.SeriesPoint(it.optString("label", ""), it.optDouble("value", 0.0).toFloat())
            })
            "note" -> QuroChatCard.NoteCard(id, title, o.optString("body", ""), if (o.has("lang") && !o.isNull("lang")) o.optString("lang") else null)
            "actions" -> QuroChatCard.ActionCard(id, title, (0 until (o.optJSONArray("actions")?.length() ?: 0)).map { i ->
                val it = o.optJSONArray("actions")!!.optJSONObject(i)
                QuroChatCard.ActionCard.CardAction(it.optString("label", "动作"), it.optString("command", ""))
            })
            "button" -> QuroChatCard.ButtonCard(id, title, o.optString("label", "按钮"), o.optString("command", ""), o.optString("variant", "filled"), if (o.has("icon") && !o.isNull("icon")) o.optString("icon") else null)
            "toggle" -> QuroChatCard.ToggleCard(id, title, o.optString("label", ""), o.optBoolean("checked", false), o.optString("command", ""))
            "slider" -> QuroChatCard.SliderCard(id, title, o.optString("label", ""), o.optDouble("value", 0.0).toFloat(), o.optDouble("min", 0.0).toFloat(), o.optDouble("max", 100.0).toFloat(), o.optDouble("step", 1.0).toFloat(), o.optString("unit", ""), o.optString("command", ""))
            "progress" -> QuroChatCard.ProgressCard(id, title, o.optString("label", ""), o.optDouble("value", 0.0).toFloat(), o.optDouble("max", 100.0).toFloat(), o.optString("suffix", "%"))
            "stat" -> QuroChatCard.StatCard(id, title, o.optString("label", ""), o.optString("value", ""), o.optString("unit", ""), o.optString("delta", ""), o.optString("trend", "flat"))
            "alert" -> QuroChatCard.AlertCard(id, title, o.optString("severity", "info"), o.optString("text", ""))
            "table" -> QuroChatCard.TableCard(id, title, arrStr(o.optJSONArray("headers")), arrArrStr(o.optJSONArray("rows")))
            "list" -> QuroChatCard.ListCard(id, title, (0 until (o.optJSONArray("items")?.length() ?: 0)).map { i ->
                val it = o.optJSONArray("items")!!.optJSONObject(i)
                QuroChatCard.ListCard.ListItem(it.optString("text", ""), it.optString("sub", ""), it.optBoolean("selected", false))
            }, o.optBoolean("selectable", false), o.optString("command", ""))
            "segmented" -> QuroChatCard.SegmentedCard(id, title, o.optString("label", ""), arrStr(o.optJSONArray("options")), o.optInt("selectedIndex", 0), o.optString("command", ""))
            "pie" -> QuroChatCard.PieCard(id, title, (0 until (o.optJSONArray("segments")?.length() ?: 0)).map { i ->
                val it = o.optJSONArray("segments")!!.optJSONObject(i)
                QuroChatCard.PieCard.PieSeg(it.optString("name", ""), it.optDouble("value", 0.0).toFloat(), it.optString("color", ""))
            })
            "rating" -> QuroChatCard.RatingCard(id, title, o.optString("label", ""), o.optInt("max", 5), o.optInt("value", 0), o.optString("command", ""))
            "countdown" -> QuroChatCard.CountdownCard(id, title, o.optString("label", ""), o.optLong("targetEpochMs", Long.MAX_VALUE))
            "tabs" -> QuroChatCard.TabsCard(id, title, (0 until (o.optJSONArray("tabs")?.length() ?: 0)).map { i ->
                val it = o.optJSONArray("tabs")!!.optJSONObject(i)
                QuroChatCard.TabsCard.Tab(it.optString("title", ""), it.optString("body", ""))
            }, o.optInt("selectedIndex", 0))
            "expandable" -> QuroChatCard.ExpandableCard(id, title, o.optString("body", ""), o.optBoolean("expanded", false))
            "form" -> QuroChatCard.FormCard(id, title, (0 until (o.optJSONArray("fields")?.length() ?: 0)).map { i ->
                val it = o.optJSONArray("fields")!!.optJSONObject(i)
                QuroChatCard.FormCard.FormField(it.optString("key", ""), it.optString("label", ""), it.optString("value", ""), it.optString("placeholder", ""), it.optBoolean("secret", false))
            }, o.optString("submitCommand", ""))
            "chips" -> QuroChatCard.ChipsCard(id, title, o.optString("label", ""), arrStr(o.optJSONArray("chips")), arrStr(o.optJSONArray("selected")), o.optBoolean("multi", false), o.optString("command", ""))
            "steps" -> QuroChatCard.StepsCard(id, title, (0 until (o.optJSONArray("steps")?.length() ?: 0)).map { i ->
                val it = o.optJSONArray("steps")!!.optJSONObject(i)
                QuroChatCard.StepsCard.Step(it.optString("title", ""), it.optString("status", "todo"))
            }, o.optInt("current", 0))
            "gauge" -> QuroChatCard.GaugeCard(id, title, o.optString("label", ""), o.optDouble("value", 0.0).toFloat(), o.optDouble("max", 100.0).toFloat(), o.optString("unit", "%"))
            "media" -> QuroChatCard.MediaCard(id, title, o.optString("mediaUrl", ""), o.optString("mediaType", "image"))
            "info" -> QuroChatCard.InfoCard(id, title, o.optString("body", ""), o.optString("align", "start"))
            "toolcall" -> QuroChatCard.ToolCallCard(id, title, o.optString("tool", ""), o.optString("status", "pending"), o.optDouble("progress", 0.0).toFloat(), o.optString("message", ""))
            "stream" -> QuroChatCard.StreamCard(id, title, arrStr(o.optJSONArray("lines")))
            "mediaplay" -> QuroChatCard.MediaPlayCard(id, title, o.optString("mediaType", "audio"), o.optString("uri", ""), o.optString("label", ""))
            "quickreply" -> QuroChatCard.QuickReplyCard(id, title, arrStr(o.optJSONArray("replies")), o.optBoolean("multi", false))
            "quickaction" -> QuroChatCard.QuickActionCard(id, title, (0 until (o.optJSONArray("actions")?.length() ?: 0)).map { i ->
                val it = o.optJSONArray("actions")!!.optJSONObject(i)
                QuroChatCard.QuickActionCard.QuickAction(it.optString("label", ""), it.optString("icon", "sparkles"), it.optString("command", ""))
            })
            "timeline" -> QuroChatCard.TimelineCard(id, title, (0 until (o.optJSONArray("events")?.length() ?: 0)).map { i ->
                val it = o.optJSONArray("events")!!.optJSONObject(i)
                QuroChatCard.TimelineCard.TimeEvent(it.optString("time", ""), it.optString("title", ""), it.optString("desc", ""), it.optString("status", "done"))
            })
            "heatmap" -> QuroChatCard.HeatmapCard(id, title, (0 until (o.optJSONArray("values")?.length() ?: 0)).map { i -> o.optJSONArray("values")!!.optInt(i, 0) }, o.optInt("weeks", 12), o.optString("label", ""))
            "compare" -> run {
                fun side(name: String) = o.optJSONObject(name)?.let { s ->
                    QuroChatCard.CompareCard.CompareSide(s.optString("title", name), arrStr(s.optJSONArray("points")), s.optBoolean("positive", name == "left"))
                } ?: QuroChatCard.CompareCard.CompareSide(name, emptyList(), name == "left")
                QuroChatCard.CompareCard(id, title, side("left"), side("right"))
            }
            "radar" -> QuroChatCard.RadarCard(id, title, (0 until (o.optJSONArray("axes")?.length() ?: 0)).map { i ->
                val it = o.optJSONArray("axes")!!.optJSONObject(i)
                QuroChatCard.RadarCard.RadarAxis(it.optString("name", ""), it.optDouble("value", 0.0).toFloat())
            })
            "timer" -> QuroChatCard.TimerCard(id, title, o.optInt("seconds", 0), o.optString("command", ""))
            "carousel" -> QuroChatCard.CarouselCard(id, title, (0 until (o.optJSONArray("slides")?.length() ?: 0)).map { i ->
                val it = o.optJSONArray("slides")!!.optJSONObject(i)
                QuroChatCard.CarouselCard.Slide(it.optString("title", ""), it.optString("body", ""), it.optString("color", ""))
            })
            "kanban" -> QuroChatCard.KanbanCard(id, title, (0 until (o.optJSONArray("columns")?.length() ?: 0)).map { i ->
                val it = o.optJSONArray("columns")!!.optJSONObject(i)
                QuroChatCard.KanbanCard.KanbanColumn(it.optString("name", ""), arrStr(it.optJSONArray("items")))
            })
            "yuanbao", "linkAnswer" -> {
                // 兼容去品牌化期间发布的 v1.0.26（wire-type 曾为 "linkAnswer"）已保存的卡片
                val linksArr = o.optJSONArray("links")
                val links = if (linksArr != null) {
                    (0 until linksArr.length()).map { i ->
                        val it = linksArr.optJSONObject(i)
                        QuroChatCard.YuanbaoLink(it.optString("title", ""), it.optString("url", ""))
                    }
                } else emptyList()
                QuroChatCard.YuanbaoCard(id, title, o.optString("url", ""), links)
            }
            "color" -> QuroChatCard.ColorCard(
                id, title, arrStr(o.optJSONArray("colors")), o.optString("label", ""), o.optString("command", ""),
            )
            "counter" -> QuroChatCard.CounterCard(
                id, title, o.optString("label", ""), o.optInt("value", 0), o.optInt("min", 0), o.optInt("max", 100), o.optInt("step", 1), o.optString("command", ""),
            )
            "breadcrumb" -> QuroChatCard.BreadcrumbCard(
                id, title, (0 until (o.optJSONArray("crumbs")?.length() ?: 0)).map { i ->
                    val it = o.optJSONArray("crumbs")!!.optJSONObject(i)
                    QuroChatCard.BreadcrumbCard.Breadcrumb(it.optString("label", ""), it.optString("command", ""))
                },
            )
            "tagcloud" -> QuroChatCard.TagCloudCard(
                id, title, (0 until (o.optJSONArray("tags")?.length() ?: 0)).map { i ->
                    val it = o.optJSONArray("tags")!!.optJSONObject(i)
                    QuroChatCard.TagCloudCard.Tag(it.optString("label", ""), it.optInt("weight", 1), it.optString("command", ""))
                },
            )
            "badge" -> QuroChatCard.BadgeCard(
                id, title, (0 until (o.optJSONArray("badges")?.length() ?: 0)).map { i ->
                    val it = o.optJSONArray("badges")!!.optJSONObject(i)
                    QuroChatCard.BadgeCard.Badge(it.optString("label", ""), it.optString("color", ""), it.optString("command", ""))
                },
            )
            "avatargroup" -> QuroChatCard.AvatarGroupCard(
                id, title, (0 until (o.optJSONArray("avatars")?.length() ?: 0)).map { i ->
                    val it = o.optJSONArray("avatars")!!.optJSONObject(i)
                    QuroChatCard.AvatarGroupCard.Avatar(it.optString("name", ""), it.optString("url", ""), it.optString("command", ""))
                },
            )
            "mermaid" -> QuroChatCard.MermaidCard(
                id, title,
                o.optString("source", ""),
                if (o.has("theme") && !o.isNull("theme")) o.optString("theme") else "",
            )
            else -> null
        }
    }.getOrNull()
}

private fun arrStr(a: JSONArray?): List<String> =
    a?.let { (0 until it.length()).map { idx -> it.optString(idx, "") } } ?: emptyList()

private fun arrArrStr(a: JSONArray?): List<List<String>> {
    if (a == null) return emptyList()
    val out = mutableListOf<List<String>>()
    for (i in 0 until a.length()) {
        val row = a.optJSONArray(i) ?: continue
        out.add((0 until row.length()).map { row.optString(it, "") })
    }
    return out
}

/** target 可为 epoch 毫秒（数字）或日期字符串，解析为毫秒；无法解析返回 Long.MAX_VALUE（表示“目标未定”）。 */
private fun parseTarget(o: Any?): Long {
    if (o == null) return Long.MAX_VALUE
    if (o is Number) return o.toLong()
    val str = o.toString().trim()
    if (str.isEmpty()) return Long.MAX_VALUE
    val fmts = listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd", "yyyy/MM/dd HH:mm:ss")
    for (f in fmts) {
        runCatching {
            val d: java.util.Date =
                java.text.SimpleDateFormat(f, java.util.Locale.US).parse(str) ?: return@runCatching null
            return d.time
        }
    }
    return Long.MAX_VALUE
}

/** 卡片仓库：单例，AI 工具写入、对话框卡片栏观察并渲染。 */
object QuroChatCardStore {
    private val _cards: SnapshotStateList<QuroChatCard> = mutableStateListOf()
    val cards: SnapshotStateList<QuroChatCard> get() = _cards

    fun add(card: QuroChatCard) {
        // 同名则替换，避免重复堆叠
        val idx = _cards.indexOfFirst { it.id == card.id }
        if (idx >= 0) _cards[idx] = card else _cards.add(card)
    }

    fun remove(id: String) {
        _cards.removeAll { it.id == id }
    }

    fun clear() = _cards.clear()

    /** 切换待办项勾选状态（就地更新，保持列表稳定）。 */
    fun toggleTodo(cardId: String, itemIndex: Int) {
        val idx = _cards.indexOfFirst { it.id == cardId }
        if (idx < 0) return
        val card = _cards[idx]
        if (card !is QuroChatCard.TodoCard) return
        val newItems = card.items.mapIndexed { i, it ->
            if (i == itemIndex) it.copy(done = !it.done) else it
        }
        _cards[idx] = card.copy(items = newItems)
    }

    // ── 交互组件就地更新（保持列表引用稳定，Compose 自动重绘） ──
    fun setToggle(cardId: String, checked: Boolean) {
        val idx = _cards.indexOfFirst { it.id == cardId }
        if (idx < 0) return
        val c = _cards[idx]
        if (c is QuroChatCard.ToggleCard) _cards[idx] = c.copy(checked = checked)
    }

    fun setSlider(cardId: String, value: Float) {
        val idx = _cards.indexOfFirst { it.id == cardId }
        if (idx < 0) return
        val c = _cards[idx]
        if (c is QuroChatCard.SliderCard) _cards[idx] = c.copy(value = value)
    }

    fun setCounter(cardId: String, value: Int) {
        val idx = _cards.indexOfFirst { it.id == cardId }
        if (idx < 0) return
        val c = _cards[idx]
        if (c is QuroChatCard.CounterCard) _cards[idx] = c.copy(value = value)
    }

    fun setSegmented(cardId: String, index: Int) {
        val idx = _cards.indexOfFirst { it.id == cardId }
        if (idx < 0) return
        val c = _cards[idx]
        if (c is QuroChatCard.SegmentedCard) _cards[idx] = c.copy(selectedIndex = index)
    }

    fun setRating(cardId: String, value: Int) {
        val idx = _cards.indexOfFirst { it.id == cardId }
        if (idx < 0) return
        val c = _cards[idx]
        if (c is QuroChatCard.RatingCard) _cards[idx] = c.copy(value = value)
    }

    fun setChips(cardId: String, selected: List<String>) {
        val idx = _cards.indexOfFirst { it.id == cardId }
        if (idx < 0) return
        val c = _cards[idx]
        if (c is QuroChatCard.ChipsCard) _cards[idx] = c.copy(selected = selected)
    }

    fun setTabs(cardId: String, index: Int) {
        val idx = _cards.indexOfFirst { it.id == cardId }
        if (idx < 0) return
        val c = _cards[idx]
        if (c is QuroChatCard.TabsCard) _cards[idx] = c.copy(selectedIndex = index)
    }

    fun setExpandable(cardId: String, expanded: Boolean) {
        val idx = _cards.indexOfFirst { it.id == cardId }
        if (idx < 0) return
        val c = _cards[idx]
        if (c is QuroChatCard.ExpandableCard) _cards[idx] = c.copy(expanded = expanded)
    }

    fun setFormField(cardId: String, key: String, value: String) {
        val idx = _cards.indexOfFirst { it.id == cardId }
        if (idx < 0) return
        val c = _cards[idx]
        if (c !is QuroChatCard.FormCard) return
        _cards[idx] = c.copy(fields = c.fields.map { if (it.key == key) it.copy(value = value) else it })
    }

    fun setListItemSelected(cardId: String, index: Int, selected: Boolean) {
        val idx = _cards.indexOfFirst { it.id == cardId }
        if (idx < 0) return
        val c = _cards[idx]
        if (c !is QuroChatCard.ListCard) return
        _cards[idx] = c.copy(items = c.items.mapIndexed { i, it -> if (i == index) it.copy(selected = selected) else it })
    }

    // ── v135 工具调用 / 流式 ──
    fun addToolCall(card: QuroChatCard.ToolCallCard) = add(card)

    fun updateToolCall(cardId: String, status: String? = null, progress: Float? = null, message: String? = null) {
        val idx = _cards.indexOfFirst { it.id == cardId }
        if (idx < 0) return
        val c = _cards[idx]
        if (c !is QuroChatCard.ToolCallCard) return
        _cards[idx] = c.copy(
            status = status ?: c.status,
            progress = progress ?: c.progress,
            message = message ?: c.message,
        )
    }

    fun appendStreamLine(cardId: String, line: String) {
        val idx = _cards.indexOfFirst { it.id == cardId }
        if (idx < 0) return
        val c = _cards[idx]
        if (c !is QuroChatCard.StreamCard) return
        _cards[idx] = c.copy(lines = c.lines + line)
    }

    fun newId(): String = "card_" + UUID.randomUUID().toString().take(8)
}
