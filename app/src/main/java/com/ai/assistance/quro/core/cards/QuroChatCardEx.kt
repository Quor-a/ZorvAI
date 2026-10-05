package com.ai.assistance.quro.core.cards

/**
 * 可视化组件 SDK v1400 —— 34 种增强组件（[QuroChatCard] 的 sealed 子类，独立成文件）。
 *
 * ## 为什么要独立文件
 *
 * Kotlin 1.5 起，sealed 子类允许分散在**同包同模块**的任意文件，
 * 所以新增组件**不需要**改 `QuroChatCard.kt`。改那一处需要靠脚本做花括号配平，
 * 一旦定位偏了就是整文件截断（2026-10-05 真踩过：尾部 parse/serialize/store 全丢）。
 * 独立文件可 diff 复核、编译报错能直接跳行，零风险。
 *
 * ## 🔴 存量类型签名一律不动
 *
 * 存量 47 种的数据类字段**一个字都没改**——它们已在真机跑着，
 * 改字段会连带 serializeCard / parseCard / 渲染分派 / 历史存档四处。
 * 本批全部是**新类型**，每种对应一个真实缺口（见下方注释）。
 */

// ═══════════════════════════════════════════════════════════════
// ═════ v1400：34 种增强组件（可视化组件 SDK 扩展）═════════════
// ═══════════════════════════════════════════════════════════════
//
// 设计约束：**不改动上面任何存量类型的字段**。
// 存量 47 种已在真机上跑着，改字段会连带影响 serializeCard / parseCard /
// QuroChatCards 渲染分派，以及历史存档的兼容性。
// 所以本批增强全部是**新类型**，每种对应一个真实缺口：
//
//  · 数据：keyvalue(详情页) / ring(环形进度) / stackedbar / scatter /
//         funnel(转化) / candlestick(金融) / boxplot(分布) /
//         speedometer(指针表) / sparkline(行内趋势)
//  · 输入：searchbox / poll(投票) / checklist(清单)
//  · 结构：accordion / groupedlist(分区) / tree(树) / quote(引述) / diff(差异)
//  · 流程：flow(流程图) / hierarchy(阶段)
//  · 实体：contact / product / schedule / filecard / achievement /
//         weather / map / qrcode / gallery / terminal
//  · 导航：linklist / pagination
//  · 装饰：divider / spacer
//  · 兜底：custom(未知 type 的原样保留)

// ───────────── 数据增强 ─────────────

/** 键值对列表：详情页最常用，比表格紧凑、比纯文本结构化。 */
data class KeyValueCard(
    override val id: String,
    override val title: String,
    val rows: List<Row>,
) : QuroChatCard {
    data class Row(val k: String, val v: String, val icon: String? = null, val command: String? = null)
}

/**
 * 环形进度：比 [GaugeCard] 多「中心文案」与「自定义色」，
 * 且 thickness 允许画细环（进度指示器）而不是粗仪表。
 */
data class RingCard(
    override val id: String,
    override val title: String,
    val label: String,
    val value: Float,
    val max: Float = 1f,
    val caption: String = "",
    val color: String = "",
    /** 环宽占比 0~1 */
    val thickness: Float = 0.12f,
) : QuroChatCard

/** 堆叠柱状图：多系列按分类堆叠，看总量构成随时间的变化。 */
data class StackedBarCard(
    override val id: String,
    override val title: String,
    val categories: List<String>,
    val series: List<Series>,
) : QuroChatCard {
    data class Series(val name: String, val values: List<Float>, val color: String = "")
}

/** 散点图：相关性分析。 */
data class ScatterCard(
    override val id: String,
    override val title: String,
    val points: List<Point>,
    val xLabel: String = "",
    val yLabel: String = "",
) : QuroChatCard {
    data class Point(val x: Float, val y: Float, val label: String? = null)
}

/**
 * 漏斗图：转化率分析。
 *
 * v1400 第三批扩展（**只加带默认值的可选字段，不改存量字段名与顺序**）：
 *  - [showRate] 每级是否显示相对首级的留存率；
 *  - [unit] 数值单位（如「人」「元」）；
 *  - [Step.hint] 本级流失原因等补充说明。
 *
 * 老存档里的 `{"steps":[{"name":..,"value":..}]}` 三个新字段全走默认值，解析行为不变。
 */
data class FunnelCard(
    override val id: String,
    override val title: String,
    val steps: List<Step>,
    val showRate: Boolean = true,
    val unit: String = "",
) : QuroChatCard {
    /**
     * @param value 用 Double 而非 Float：第三批的样例会出现小数（如 4.1），
     *             Float 会静默截断成 4.1f 后参与运算出现精度毛刺。
     *             JSON 反序列化对数字字面量两种类型都能读，老数据不受影响。
     */
    data class Step(
        val name: String,
        val value: Double,
        val color: String = "",
        /** 流失原因等补充（v1400-b3 新增，缺省空串） */
        val hint: String = "",
    )
}

/** K线图：金融行情。 */
data class CandlestickCard(
    override val id: String,
    override val title: String,
    val candles: List<Candle>,
) : QuroChatCard {
    /** o=开 h=高 l=低 c=收 t=时间标签 */
    data class Candle(val o: Float, val h: Float, val l: Float, val c: Float, val t: String = "")
}

/** 箱线图：分布统计（五数概括）。 */
data class BoxPlotCard(
    override val id: String,
    override val title: String,
    val groups: List<Group>,
) : QuroChatCard {
    data class Group(
        val name: String,
        val min: Float, val q1: Float, val median: Float, val q3: Float, val max: Float,
    )
}

/** 速度表：指针式仪表 + 多区间配色（绿/黄/红）。 */
data class SpeedometerCard(
    override val id: String,
    override val title: String,
    val value: Float,
    val max: Float = 100f,
    val label: String = "",
    val unit: String = "",
    /** 区间配色，缺省三档（绿黄红） */
    val zones: List<String> = emptyList(),
) : QuroChatCard

/** 迷你趋势线：行内小图，一行内看趋势。 */
data class SparklineCard(
    override val id: String,
    override val title: String,
    val values: List<Float>,
    val caption: String = "",
    val color: String = "#4CAF50",
) : QuroChatCard

// ───────────── 输入增强 ─────────────

/** 搜索输入框：比 form 更轻，只有一栏 + 回车提交。 */
data class SearchBoxCard(
    override val id: String,
    override val title: String,
    val label: String = "",
    val placeholder: String = "",
    val value: String = "",
    val command: String = "",
    val hint: String = "",
) : QuroChatCard

/** 投票卡：选项 + 结果条 + 总数。 */
data class PollCard(
    override val id: String,
    override val title: String,
    val question: String,
    val options: List<Option>,
    val multi: Boolean = false,
    val total: Int = 0,
    val command: String = "",
) : QuroChatCard {
    data class Option(val label: String, val count: Int = 0, val color: String = "")
}

/** 清单：勾选框 + 说明 + 完成度摘要。 */
data class CheckListCard(
    override val id: String,
    override val title: String,
    val items: List<Item>,
    val summary: String = "",
) : QuroChatCard {
    data class Item(val text: String, val done: Boolean = false, val note: String = "")
}

// ───────────── 结构增强 ─────────────

/** 手风琴：多项各自独立展开（与 CompositeCard 的 accordion 布局不同，这里是独立卡片类型）。 */
data class AccordionCard(
    override val id: String,
    override val title: String,
    val items: List<Item>,
    /** 初始展开项索引，-1 = 全收起 */
    val expandedIndex: Int = -1,
) : QuroChatCard {
    data class Item(val title: String, val body: String)
}

/** 分组列表：小标题分区，每项可带右侧值。 */
data class GroupedListCard(
    override val id: String,
    override val title: String,
    val sections: List<Section>,
) : QuroChatCard {
    data class Section(val title: String, val items: List<Item>)
    data class Item(val text: String, val sub: String = "", val icon: String = "", val value: String = "")
}

/** 树形层级：文件树 / 组织架构。 */
data class TreeCard(
    override val id: String,
    override val title: String,
    val nodes: List<Node>,
    val expandedDepth: Int = 2,
) : QuroChatCard {
    data class Node(
        val label: String,
        val icon: String = "",
        val expanded: Boolean = false,
        val value: String = "",
        val children: List<Node> = emptyList(),
    )
}

/** 引述块：可署名、可标来源。 */
data class QuoteCard(
    override val id: String,
    override val title: String,
    val text: String,
    val author: String? = null,
    val source: String? = null,
) : QuroChatCard

/** 差异对比：增删行 + 增删统计。 */
data class DiffCard(
    override val id: String,
    override val title: String,
    val rows: List<Row>,
    val file: String = "",
    val additions: Int = 0,
    val deletions: Int = 0,
) : QuroChatCard {
    /** kind = add | del | mod */
    data class Row(val kind: String, val text: String, val oldText: String? = null)
}

// ───────────── 流程 / 层级 ─────────────

/** 流程图：节点 + 箭头，节点可点击。 */
data class FlowCard(
    override val id: String,
    override val title: String,
    val nodes: List<Node>,
    val direction: String = "vertical",
) : QuroChatCard {
    /** kind = start | process | decision | end */
    data class Node(
        val label: String,
        val kind: String = "process",
        val desc: String = "",
        val command: String = "",
    )
}

/** 层级流程：阶段分组（如「准备 / 执行 / 验收」）。 */
data class HierarchyCard(
    override val id: String,
    override val title: String,
    val stages: List<Stage>,
) : QuroChatCard {
    data class Stage(val name: String, val items: List<String>, val color: String = "")
}

// ───────────── 实体卡 ─────────────

/** 联系人卡：头像 + 角色 + 在线状态 + 快捷动作。 */
data class ContactCard(
    override val id: String,
    override val title: String,
    val name: String,
    val role: String = "",
    val avatar: String = "",
    /** offline | online | busy | away */
    val status: String = "offline",
    val actions: List<QA> = emptyList(),
) : QuroChatCard {
    data class QA(val label: String, val icon: String = "", val command: String = "")
}

/** 商品卡：图 + 价 + 划线价 + 评分 + 销量 + 角标。 */
data class ProductCard(
    override val id: String,
    override val title: String,
    val name: String,
    val price: String,
    val originalPrice: String? = null,
    val image: String = "",
    val rating: Float = 0f,
    val sold: Int = 0,
    val command: String = "",
    val tag: String = "",
) : QuroChatCard

/**
 * 日程卡。
 *
 * 字段名用 `event` 而不是 `eventTitle` —— JSON 侧就是 `"event"`，
 * 少一层映射、少一处可能写错的地方。
 */
data class ScheduleCard(
    override val id: String,
    override val title: String,
    val event: String,
    val date: String = "",
    val time: String = "",
    val place: String = "",
    val command: String = "",
) : QuroChatCard

/** 文件卡：类型图标 + 大小 + 路径。 */
data class FileCard(
    override val id: String,
    override val title: String,
    val name: String,
    val size: String = "",
    val path: String = "",
    val mime: String = "",
    val command: String = "",
) : QuroChatCard

/** 成就卡：徽章 + 描述 + 进度（progress < 0 = 不显示进度）。 */
data class AchievementCard(
    override val id: String,
    override val title: String,
    val name: String,
    val desc: String = "",
    val icon: String = "",
    val progress: Float = -1f,
    val command: String = "",
) : QuroChatCard

/** 天气卡：当前实况 + 逐时预报。 */
data class WeatherCard(
    override val id: String,
    override val title: String,
    val city: String,
    val temp: String,
    val condition: String = "",
    val icon: String = "",
    val humidity: String = "",
    val wind: String = "",
    val hours: List<Hour> = emptyList(),
) : QuroChatCard {
    data class Hour(val t: String, val v: String, val i: String = "")
}

/** 地图卡：坐标 + 标记点。 */
data class MapCard(
    override val id: String,
    override val title: String,
    val lat: Double,
    val lng: Double,
    val zoom: Int = 12,
    val markers: List<Marker> = emptyList(),
) : QuroChatCard {
    data class Marker(val lat: Double, val lng: Double, val label: String = "")
}

/** 二维码卡。 */
data class QrCodeCard(
    override val id: String,
    override val title: String,
    val content: String,
    val size: Int = 180,
    val caption: String = "",
    val color: String = "#000000",
) : QuroChatCard

/** 图片网格。 */
data class GalleryCard(
    override val id: String,
    override val title: String,
    val images: List<Item>,
    val columns: Int = 2,
    val aspectRatio: Float = 1f,
) : QuroChatCard {
    data class Item(val url: String, val caption: String? = null)
}

/**
 * 终端输出卡。
 *
 * @param exitCode -1 = 尚未结束；>=0 = 已结束且成功；< -1 = 已结束且失败
 */
data class TerminalCard(
    override val id: String,
    override val title: String,
    val lines: List<String> = emptyList(),
    val exitCode: Int = -1,
    val command: String = "",
) : QuroChatCard

// ───────────── 导航增强 ─────────────

/** 链接列表。 */
data class LinkListCard(
    override val id: String,
    override val title: String,
    val links: List<Link>,
) : QuroChatCard {
    data class Link(val label: String, val url: String, val desc: String? = null, val icon: String? = null)
}

/** 分页器。 */
data class PaginationCard(
    override val id: String,
    override val title: String,
    val page: Int = 1,
    val total: Int = 1,
    val command: String = "",
) : QuroChatCard

// ───────────── 布局装饰 ─────────────

/** 分隔线（可带居中文字）。 */
data class DividerCard(
    override val id: String,
    override val title: String,
    val text: String? = null,
    val dashed: Boolean = false,
) : QuroChatCard

/** 垂直间隔：用来调整卡片之间的节奏（AI 排版常用）。 */
data class SpacerCard(
    override val id: String,
    override val title: String,
    val height: Int = 16,
) : QuroChatCard

// ───────────── 兜底 ─────────────

/**
 * 自定义 / 未知组件的**原样保留**卡。
 *
 * AI 下发了客户端不认识的 `type` 时，丢掉它等于「AI 什么都没发生」——
 * 用户无从判断是模型没画还是客户端没认。保留原文后至少能显示出来，
 * 用户反馈时也能直接看到 AI 到底输出了什么。
 *
 * @param kind    原始 type 字符串（客户端不认识的那个）
 * @param payload 原始 JSON 全文
 */
data class CustomCard(
    override val id: String,
    override val title: String,
    val kind: String,
    val payload: String,
    val children: List<QuroChatCard> = emptyList(),
) : QuroChatCard