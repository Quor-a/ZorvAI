package com.ai.assistance.quro.core.ui.card.spec

/**
 * 卡片类型名与「数据 → 渲染器」推断的唯一真相源（纯 JVM，无 Compose 依赖，可单测）。
 *
 * ## 为什么要单独有这一层
 *
 * 项目里有**两条**卡片通道，类型名天然会撞：
 *  - 本包（`quro-card` 围栏，自研 Compose 渲染，注册在 [com.ai.assistance.quro.core.ui.card.registry.CardRegistry]）；
 *  - `core.cards.CardSdk`（`ui_card` / `ui_widget` 围栏，103 种组件的富卡片渲染层）。
 *
 * 规则定死在这里，避免任何一方偷偷劫持另一方：
 *  1. [CANONICAL_TYPES] 是本通道**自有**的名字（不与 CardSdk 重名）；
 *  2. 模型写了本通道不认识的 type 时，[inferType] 按 **data 的形状**推断出等价渲染器 ——
 *     所以「type 写错/写新」也不会掉成空白或协议报错；
 *  3. 与 CardSdk 重名的 type（`chart` / `pie` / `timeline` / `stat` …）**不注册**，
 *     继续交给富卡片层渲染，避免字段名不兼容（CardSdk 用 `chart_type`、`series[].label/value`）导致空白。
 */
object CardTypes {

    // ── 本通道自有类型（注册进 CardRegistry 白名单） ──
    const val METRIC = "metric"
    const val LINE_CHART = "line_chart"
    const val BAR_CHART = "bar_chart"
    const val PIE_CHART = "pie_chart"
    const val AREA_CHART = "area_chart"
    const val SPARKLINE = "sparkline_chart"
    const val TABLE = "table"
    const val BUTTON_GROUP = "button_group"
    const val FORM = "form_card"
    const val MEDIA = "media_card"
    const val CODE = "code_block"
    const val IMAGES = "image_group"
    const val STATUS = "status"
    const val SKELETON = "skeleton"
    const val CUSTOM = "custom"

    /** 全部自有类型。 */
    val CANONICAL_TYPES: Set<String> = linkedSetOf(
        METRIC, LINE_CHART, BAR_CHART, PIE_CHART, AREA_CHART, SPARKLINE,
        TABLE, BUTTON_GROUP, FORM, MEDIA, CODE, IMAGES, STATUS, SKELETON, CUSTOM,
    )

    /**
     * 别名 → 自有类型。**只放自有类型之间的同义写法**，绝不放 CardSdk 的类型名。
     *
     * 铁律：`CardSdk` 那 103 个名字（`chart` / `pie` / `stat` / `form` / `media` / `gallery` /
     * `sparkline` / `timeline` / `composite` …）一个都不能出现在这里 —— 一旦出现，
     * `SelfCardMount` 的白名单判断会把它们从富卡片层抢过来，而两边的字段名不兼容
     * （CardSdk 用 `chart_type`、`series[].label/value`），结果是**能渲染的卡变成空白**。
     * `CardPipelineTest` 有一条断言专门盯着这件事（`composite` 必须保持未注册）。
     */
    val ALIASES: Map<String, String> = mapOf(
        "linechart" to LINE_CHART,
        "line" to LINE_CHART,
        "curve" to LINE_CHART,
        "barchart" to BAR_CHART,
        "bar" to BAR_CHART,
        "column_chart" to BAR_CHART,
        "histogram" to BAR_CHART,
        "piechart" to PIE_CHART,
        "donut" to PIE_CHART,
        "doughnut" to PIE_CHART,
        "areachart" to AREA_CHART,
        "area" to AREA_CHART,
        "spark" to SPARKLINE,
        "sparkline_chart" to SPARKLINE,
        "code" to CODE,
        "snippet" to CODE,
        "images" to IMAGES,
        "photo_group" to IMAGES,
        "fields" to FORM,
        "input_form" to FORM,
        "metrics" to METRIC,
        "kpi" to METRIC,
        "buttons" to BUTTON_GROUP,
        "loading" to SKELETON,
        "placeholder" to SKELETON,
        "shimmer" to SKELETON,
    )

    /** 归一：trim + 小写 + 连字符转下划线，再查别名表。 */
    fun canonical(raw: String): String {
        val k = raw.trim().lowercase().replace('-', '_').replace(' ', '_')
        return ALIASES[k] ?: k
    }

    fun isCanonical(type: String): Boolean = canonical(type) in CANONICAL_TYPES

    /**
     * 按 data / layout 的形状推断该用哪个渲染器；推断不出返回 null。
     *
     * 这是「未知 type 也不掉空白」的兜底：AI 把 `bar_chart` 写成 `barChart`、
     * 或干脆只给 `{"data":{"kind":"chart","chartType":"bar",...}}`，都能落到正确渲染器。
     */
    fun inferType(spec: CardSpec): String? {
        return when (val d = spec.data) {
            is CardData.Chart -> when (d.chartType.trim().lowercase()) {
                "bar", "column", "stacked_bar", "histogram" -> BAR_CHART
                "pie", "donut", "doughnut", "ring" -> PIE_CHART
                "area" -> AREA_CHART
                "sparkline", "spark" -> SPARKLINE
                "metric", "stat", "kpi", "number" -> METRIC
                else -> if (d.series.isNotEmpty()) LINE_CHART else null
            }
            is CardData.Media -> when (d.mediaType.trim().lowercase()) {
                "code", "snippet", "pre" -> CODE
                "image_group", "images", "gallery" -> IMAGES
                "table" -> TABLE
                "timeline", "list" -> MEDIA
                else -> MEDIA
            }
            is CardData.Form -> when (d.formType.trim().lowercase()) {
                "button_group", "buttons" -> BUTTON_GROUP
                else -> FORM
            }
            is CardData.Status -> if (d.statusType.equals("skeleton", true)) SKELETON else STATUS
            CardData.Empty -> if (spec.layout != null) CUSTOM else null
        }
    }

    /**
     * 图表系列配色盘：按序号取主题令牌。
     * 饼图/多系列柱状图需要「第 N 个扇区是什么颜色」，而 CardData.Series.color 默认全是 Primary，
     * 必须由渲染层补一套稳定的可辨识序列（与主题联动，暗色/亮色都成立）。
     */
    val SERIES_PALETTE: List<ColorToken> = listOf(
        ColorToken.Primary,
        ColorToken.Secondary,
        ColorToken.Success,
        ColorToken.Warning,
        ColorToken.Danger,
        ColorToken.Info,
        ColorToken.OnSurfaceVariant,
    )

    fun seriesColor(index: Int, declared: ColorToken?): ColorToken =
        declared ?: SERIES_PALETTE[index % SERIES_PALETTE.size]
}
