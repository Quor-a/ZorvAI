package com.ai.assistance.quro.core.cards

/**
 * 可视化组件 SDK v1400 —— 第二批 12 种增强组件（[QuroChatCard] 的 sealed 子类）。
 *
 * ## 与第一批（QuroChatCardEx.kt）的关系
 *
 * 同包同模块，sealed 子类分散在任意文件都成立；分成两个文件只是为了让
 * diff 一眼看得完（第一批 34 种 + 本批 12 种 = v1400 共 46 种增强组件）。
 *
 * ## 🔴 追加纪律
 *
 * 本批**同样不改动任何存量类型字段**，清一色是新 data class。
 * 每种组件对应一个真实缺口，注释里写清「与谁的区别」，
 * 避免 AI 在两种相似卡之间选错（选错 = 卡片能画但表达不准）。
 */

// ───────────── 计划 / 账目（数据增强） ─────────────

/**
 * 甘特图：排期视图。
 *
 * 与 `table` 的区别：table 看**值**，gantt 看**时间跨度与重叠**。
 * 与 `timeline` 的区别：timeline 是事件流水（有先后无长度），
 * gantt 是**带长度的条**（start 从哪天开始、duration 持续多久）。
 *
 * @param total 时间轴总长度（单位同 [GanttCard.Task.start]），0 = 自动按 max(start+duration) 算
 */
data class GanttCard(
    override val id: String,
    override val title: String,
    val tasks: List<Task>,
    /** 时间单位，仅作标注：day / week / hour */
    val unit: String = "day",
    val total: Int = 0,
    /** 日期表头起点（如 "10/01"），不填则按序号 1..N 标注 */
    val axisStart: String = "",
) : QuroChatCard {
    /**
     * @param start     起始位置（第几天开始，从 1 计）
     * @param duration  持续长度（天）
     * @param progress  完成度百分比；< 0 = 不画进度覆盖层
     * @param color     条颜色，空走语义色
     * @param owner     负责人，显示在条右侧
     */
    data class Task(
        val name: String,
        val start: Int = 1,
        val duration: Int = 1,
        val progress: Int = -1,
        val color: String = "",
        val owner: String = "",
    )
}

/**
 * 账单 / 小票。
 *
 * 与 `table` 的区别：table 是中性表格，invoice 是**带金额语义**的收据观感
 * （合计、折扣、已付、商户名），一眼能看出「多少钱、给了多少」。
 */
data class InvoiceCard(
    override val id: String,
    override val title: String,
    val merchant: String = "",
    val items: List<Item>,
    val currency: String = "¥",
    val subtotal: String = "",
    val discount: String = "",
    val total: String = "",
    val note: String = "",
    val paid: Boolean = false,
    val command: String = "",
) : QuroChatCard {
    data class Item(val name: String, val qty: Int = 1, val price: String = "", val amount: String = "")
}

/**
 * 多币种换算。
 *
 * 与 `keyvalue` 的区别：keyvalue 是纯键值对，currency 的每行都有
 * **符号 + 汇率 + 涨跌**，是「换算表」而不是「属性表」。
 */
data class CurrencyCard(
    override val id: String,
    override val title: String,
    val base: String = "CNY",
    val value: String = "1",
    val rates: List<Rate>,
    val updated: String = "",
) : QuroChatCard {
    /** @param change up | down | flat，空则不画涨跌箭头 */
    data class Rate(val code: String, val symbol: String = "", val rate: String = "", val change: String = "")
}

/**
 * 世界时钟 / 当前时刻。
 *
 * 与 `clock` 类组件无关（本项目没有系统时钟卡），这里给的是**文本时刻 + 时区偏移对照**，
 * 适合 AI 回答「现在纽约几点」这类跨时区问题。
 */
data class ClockCard(
    override val id: String,
    override val title: String,
    /** AI 算好的本地时刻，如 "2026-10-05 14:30"；空则不显示大号时间 */
    val current: String = "",
    val zones: List<Zone>,
    /** 24h | 12h */
    val format: String = "24h",
) : QuroChatCard {
    data class Zone(val city: String, val offset: String = "", val diff: String = "")
}

// ───────────── 学习 / 内容（写作增强） ─────────────

/**
 * 词汇卡：单词 + 音标 + 词性 + 释义 + 例句。
 *
 * 与 `info` 的区别：info 是一坨纯文本，vocab 是**结构化学习卡**
 * （音标、词性、中英例句对照），适合背单词场景。
 */
data class VocabCard(
    override val id: String,
    override val title: String,
    val word: String,
    val phonetic: String = "",
    val pos: String = "",
    val meaning: String = "",
    val examples: List<Example> = emptyList(),
    val tags: List<String> = emptyList(),
) : QuroChatCard {
    data class Example(val en: String, val zh: String = "")
}

/**
 * 公式卡。
 *
 * 与 `note` 的区别：note 是代码块观感（等宽、可复制），
 * formula 是**数学公式观感**（居中大号 + 变量释义表）。
 * 项目没引 KaTeX，[^] 上标与 _下标_ 做极简渲染，其余原样显示 ——
 * 画诚实的公式，不画一个"看着像公式"的乱码。
 */
data class FormulaCard(
    override val id: String,
    override val title: String,
    val expr: String,
    val vars: List<Var> = emptyList(),
    val note: String = "",
) : QuroChatCard {
    data class Var(val name: String, val desc: String = "")
}

/**
 * 翻译对照（源文 / 译文 / 备选译法）。
 *
 * 与 `info` 的区别：info 没有源译分栏；与 `compare` 的区别：
 * compare 是**方案取舍**（正负点列表），translate 是**上下对照**（原文一行、译文一行）。
 */
data class TranslateCard(
    override val id: String,
    override val title: String,
    val srcLang: String = "",
    val dstLang: String = "",
    val src: String,
    val dst: String = "",
    val alt: List<String> = emptyList(),
    val audio: String = "",
) : QuroChatCard

/**
 * 习惯打卡。
 *
 * 与 `checklist` 的区别：checklist 是**待办清单**（有完成态、可勾选），
 * tracker 是**已经过去的时间格**（每天一个格、看连续天数），偏统计不做交互。
 *
 * @param days   逐天值：1 达成 / 0 未达成 / "x" 部分达成（x 为 0~1 的字符串）/ "" 无数据
 * @param target 目标连续天数，0 = 不显示
 */
data class TrackerCard(
    override val id: String,
    override val title: String,
    val name: String = "",
    val days: List<String>,
    val target: Int = 0,
    val streak: Int = 0,
    val unit: String = "天",
) : QuroChatCard

// ───────────── 比分 / 计时 ─────────────

/**
 * 比分板：主客队 + 比分 + 节次/时间。
 *
 * 与 `compare` 的区别：compare 是观点对比（正负点文字），
 * scoreboard 是**比分**（两个数字 + 状态灯）。
 */
data class ScoreboardCard(
    override val id: String,
    override val title: String,
    val home: String,
    val homeScore: String,
    val away: String,
    val awayScore: String,
    val period: String = "",
    val time: String = "",
    /** live | finished | scheduled | paused */
    val status: String = "live",
) : QuroChatCard

/**
 * 通用排行榜：名次 + 条目 + 数值（可带单位/涨跌幅）。
 *
 * 与 `ScoreboardCard` 的区别（2026-10-08 补）：
 * scoreboard 是**体育比分**（主客队 + 比分 + 节次/时间/状态），而通用
 * 「排名 / 排行 / top N」此前在名册里**无卡可用**，AI 只能退回 table ——
 * 这正是「老是使用同一个类型组件」的根因之一，故单独立卡。
 */
data class RankingCard(
    override val id: String,
    override val title: String,
    val items: List<RankItem>,
    /** 数值单位，如「分」「万」「%」。 */
    val unit: String = "",
    /** 前几名高亮（默认前三）。 */
    val topN: Int = 3,
) : QuroChatCard {
    data class RankItem(
        val name: String,
        val value: Float,
        /** 涨跌幅文本，如「+12%」；空串表示不显示。 */
        val delta: String = "",
        /** up / down / flat；空串表示不显示箭头。 */
        val trend: String = "",
    )
}

/**
 * 秒表（正计时）。
 *
 * 与 `timer` 的区别：timer 是**倒计时**（有终点，到点发 command），
 * stopwatch 是**正计时累加**（一直走，记录用了多久）。
 * 渲染层各自持有运行状态，两者不会互相干扰。
 */
data class StopwatchCard(
    override val id: String,
    override val title: String,
    val label: String = "",
    /** 起始秒数（AI 给定指令后的初始值），0 = 从 0 开始 */
    val seconds: Long = 0,
    val command: String = "",
) : QuroChatCard

// ───────────── 设计 / 标识 ─────────────

/**
 * 色卡（配色方案）。
 *
 * 与 `color` 的区别：color 是**单个色块**，palette 是**一组色 + 每个色的用途名**，
 * 适合给 AI 输出「这套配色长什么样」。
 */
data class PaletteCard(
    override val id: String,
    override val title: String,
    val name: String = "",
    val colors: List<Swatch>,
    /** 点击色块复制 hex（默认开） */
    val copyable: Boolean = true,
) : QuroChatCard {
    data class Swatch(val name: String, val hex: String)
}

/**
 * 条码卡。
 *
 * 与 `qrcode` 的区别：qrcode 是二维码（二维码库缺席，同为**诚实占位 + 可复制**），
 * barcode 是一维码观感（竖条纹 + 码字）。两者都不画"假码型图"骗人，
 * 只展示原文并提供复制 —— 缺库不硬凑。
 */
data class BarcodeCard(
    override val id: String,
    override val title: String,
    val code: String,
    val format: String = "CODE128",
    val caption: String = "",
) : QuroChatCard
