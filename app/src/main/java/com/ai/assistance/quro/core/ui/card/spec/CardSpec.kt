package com.ai.assistance.quro.core.ui.card.spec

import androidx.compose.ui.graphics.Color

/**
 * 协议层：卡片自描述。
 *
 * 设计前提：服务端（或端上 AI）只吐「数据 + 形态声明」，端上决定怎么画。
 * 这是「渲染层完全自研、不依赖任何内置/三方成品卡片控件」的根基——
 * 所有形态都来自 [CardSpec]，没有任何 Android View / Material Card 参与。
 *
 * 本文件与已有的 `core.ui.dynamicui` 包**没有任何依赖关系**，是完全独立的新功能。
 */

/** 卡片动作（点击/提交等回传），纯数据，不含任何业务逻辑。 */
data class Action(
    val type: String,                 // callback / tool_call / skill / open_url / copy / open_app / toggle ...
    val name: String? = null,         // 工具名 / 技能名
    val url: String? = null,          // open_url
    val appName: String? = null,      // open_app
    val payload: Map<String, Any?> = emptyMap(),
)

/** 主题令牌：所有颜色/字号/圆角/间距都走令牌，暗色与字体缩放自动生效。 */
data class StyleToken(
    val bg: ColorToken = ColorToken.Surface,
    val fg: ColorToken = ColorToken.OnSurface,
    val accent: ColorToken = ColorToken.Primary,
    val cornerDp: Float = 12f,
    val paddingDp: Float = 12f,
    val fontSizeSp: Float = 14f,
    val fontWeight: Int = 400,
)

/** 语义色令牌（与 Material 主题解耦，由宿主按当前主题解析成具体 Color）。 */
enum class ColorToken {
    Primary, OnPrimary, Secondary, OnSecondary,
    Surface, OnSurface, SurfaceVariant, OnSurfaceVariant,
    Background, OnBackground, Outline,
    Success, Warning, Danger, Info,
}

/** 无障碍信息。 */
data class A11y(
    val role: String? = null,
    val label: String? = null,
    val hint: String? = null,
    val state: String? = null,
)

/**
 * 自描述布局树节点（不是 Android View 树）。
 * 用 tree 形式表达结构，编排层/布局层据此自写测量与排版。
 */
data class LayoutNode(
    val type: String,                       // column / row / box / card / text / spacer ...
    val id: String? = null,
    val weight: Float = 0f,                 // 在父容器中的占比（0 = 按内容）
    val widthDp: Float? = null,
    val heightDp: Float? = null,
    val flex: Int = 0,                       // 同权重下的排序档
    val style: StyleToken = StyleToken(),
    val children: List<LayoutNode> = emptyList(),
    val props: Map<String, Any?> = emptyMap(),
)

/** 卡片数据类型（按 type 区分具体结构）。 */
sealed interface CardData {
    /** 空数据（如骨架卡）。 */
    object Empty : CardData

    /** 数据可视化：折线/柱/饼 等序列点。 */
    data class Chart(
        val chartType: String,              // line / bar / pie / metric / sparkline
        val series: List<Series>,
        val axis: AxisConfig = AxisConfig(),
    ) : CardData

    data class Series(
        val name: String,
        val color: ColorToken = ColorToken.Primary,
        val points: List<Float>,
        val labels: List<String> = emptyList(),
    )

    data class AxisConfig(
        val showX: Boolean = true,
        val showY: Boolean = true,
        val yMin: Float? = null,
        val yMax: Float? = null,
    )

    /** 富媒体：表格/代码/图片组/时间线/折叠区。 */
    data class Media(
        val mediaType: String,              // table / code / image_group / timeline / collapsible
        val rows: List<List<String>> = emptyList(),
        val headers: List<String> = emptyList(),
        val code: String? = null,
        val lang: String? = null,
        val images: List<String> = emptyList(),
        val items: List<TimelineItem> = emptyList(),
    ) : CardData

    data class TimelineItem(val time: String, val text: String, val done: Boolean = false)

    /** 交互控件：按钮组/表单/滑块/选择器/反馈条。 */
    data class Form(
        val formType: String,               // button_group / form / slider / selector / feedback
        val buttons: List<ButtonSpec> = emptyList(),
        val fields: List<FieldSpec> = emptyList(),
        val min: Float = 0f,
        val max: Float = 100f,
        val value: Float = 0f,
        val options: List<String> = emptyList(),
        val selected: List<String> = emptyList(),
    ) : CardData

    data class ButtonSpec(val label: String, val action: Action, val variant: String = "filled")
    data class FieldSpec(val key: String, val label: String, val placeholder: String = "", val value: String = "")

    /** 流式状态：骨架/工具调用/进度/错误重试。 */
    data class Status(
        val statusType: String,             // skeleton / tool_call / progress / error
        val text: String = "",
        val progress: Float = 0f,           // 0..1
        val retryable: Boolean = false,
        val reason: String? = null,
    ) : CardData
}

/**
 * 一张卡片的完整自描述规范。
 *
 * @param id        稳定唯一 ID（用于流式增量按 id patch、复用池按 id 缓存 Bitmap）。
 * @param type      卡片类型，必须命中 [CardRegistry] 白名单，否则降级为 Markdown 气泡。
 * @param version   协议版本，不匹配走 [CardMigrator]。
 * @param layout    自描述布局树（可选，部分卡片由 renderer 内部按 data 自绘）。
 * @param data      业务数据。
 * @param actions   动作列表。
 * @param style     主题令牌。
 * @param a11y      无障碍。
 * @param renderHint 渲染底座提示：canvas / view / gl，编排层据此选底座（上层无感）。
 */
data class CardSpec(
    val id: String,
    val type: String,
    val version: Int = 1,
    val layout: LayoutNode? = null,
    val data: CardData = CardData.Empty,
    val actions: List<Action> = emptyList(),
    val style: StyleToken = StyleToken(),
    val a11y: A11y = A11y(),
    val renderHint: String = "canvas",
)

/** 协议版本迁移器：旧会话里的老卡片也能渲染。 */
object CardMigrator {
    private val handlers = mutableMapOf<Pair<String, IntRange>, (CardSpec) -> CardSpec>()

    fun register(type: String, range: IntRange, fix: (CardSpec) -> CardSpec) {
        handlers[type to range] = fix
    }

    fun migrate(spec: CardSpec): CardSpec {
        val fix = handlers.entries.firstOrNull { (k, _) ->
            k.first == spec.type && spec.version in k.second
        }?.value ?: return spec
        return fix(spec)
    }
}
