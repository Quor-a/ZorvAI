package com.ai.assistance.quro.genui.sdk.render

import com.ai.assistance.quro.genui.sdk.dsl.Dimension
import com.ai.assistance.quro.genui.sdk.dsl.EdgeInsets

/**
 * 布局守卫 —— GenUI 排版的**唯一数值入口**。
 *
 * ## 为什么需要这一层（2026-10-06 真机排查根因）
 *
 * 用户反馈三类排版问题：溢出屏幕 / 方向不对 / 位置不对。追下来它们**不是三个 bug，
 * 而是同一个根因的三个切面**：AI 产出的数值**没有任何边界约束**，直接喂给 Compose。
 *
 * 具体表现：
 *  - 溢出屏幕：模型把像素当 dp 写（`"width": 1080`）→ 1080dp 宽的组件在 360dp 屏上
 *    右边整整截掉三分之二；`"fontSize": 96` 让一行标题占满整屏；
 *    `"padding": 200` 把内容挤成一个点。
 *  - 位置不对：`padding` 上下不对称时内容明显偏上/偏下；`spacing` 写成 200 让两个元素
 *    隔着一屏。
 *  - 方向不对：`direction` 只认 `"horizontal"` 精确值，模型写 `"Horizontal"` / `"row"` /
 *    `"h"` 就静默回退成竖排。
 *
 * ## 设计原则
 *
 * 1. **静默收敛，不抛异常**：AI 产出永远只是「数据」，一个离谱数值不该让整张卡崩掉。
 * 2. **钳制而非丢弃**：`width: 1080` 收敛到屏幕可用宽度，仍然是个可用的满宽组件 ——
 *    比丢成 wrap 更符合模型本意。
 * 3. **纯函数、无 Compose 依赖**：本工程单测无 Robolectric，纯函数才能被钉死。
 *
 * ## 阈值依据（Material 3 / Apple HIG）
 *
 *  - 间距 8pt 网格（Apple HIG）：4 / 8 / 12 / 16 / 24 / 32 是全部合法档位。
 *  - 字号 M3 Type Scale：bodyLarge 16sp 是上限，展示型最大 displaySmall 36sp。
 *    超过 48sp 在手机上必然换行成"竖排字"，属于排版事故。
 *  - 内容最大宽度 720dp：与 [GenUIRenderer] 的 PageCanvas 阅读宽度上限一致。
 */
object GenUILayoutGuard {

    // ── 间距（8pt 网格，Apple HIG）───────────────────────────────

    /** 间距上限：超过 64dp 的间距在手机上就是一整屏空白，属于排版事故。 */
    const val MAX_SPACING_DP = 64f

    /**
     * 常规间距档位，供「未指定」时兜底。用 8 而不是 0：模型漏写 spacing 时 0 会让元素糊成一片。
     *
     * 取值来自 [GenUIDesignTokens.spacingDefault]（Apple HIG 8pt 网格的第一档），
     * 不在这里另立一个魔数 —— 否则「默认间距」又会有两个来源。
     */
    val DEFAULT_SPACING_DP: Float = com.ai.assistance.quro.genui.sdk.style.GenUIDesignTokens.spacingDefault

    // ── 尺寸 ──────────────────────────────────────────────────

    /**
     * 宽度上限。
     * 🔴 不能只靠「屏宽」判断 —— 本层是纯函数，拿不到 density/屏宽。
     * 取 720dp 与 PageCanvas 的阅读宽度上限对齐：超过这个宽度即便在平板上也是超长行。
     */
    const val MAX_WIDTH_DP = 720f

    /**
     * 高度上限。
     * 原 StyleResolver 写的是 1200f —— 1200dp 高的组件在 640dp 高的内容区里
     * 会把外层滚动条拉得极长，且模型几乎不会真写这么高，多半是把 px 当 dp。
     * 收到 900dp：仍够表达"接近满屏的大图/长列表"，但不会离谱。
     */
    const val MAX_HEIGHT_DP = 900f

    /** 单侧 padding 上限：超过 120dp 内容区就没了。 */
    const val MAX_PADDING_DP = 120f

    // ── 字号（Material 3 Type Scale）────────────────────────────

    const val MIN_FONT_SIZE_SP = 6f
    const val MAX_FONT_SIZE_SP = 48f

    // ── 其他视觉量 ──────────────────────────────────────────────

    const val MAX_CORNER_RADIUS_DP = 96f
    const val MAX_BORDER_WIDTH_DP = 24f
    const val MAX_ELEVATION_DP = 48f
    const val MIN_OPACITY = 0f
    const val MAX_OPACITY = 1f
    const val MAX_ROTATION_DEG = 180f

    /**
     * 钳制间距。负值（AI 写 `spacing: -8`）与超大值都收敛到 [MAX_SPACING_DP]。
     */
    fun spacing(value: Float?): Float {
        val v = value ?: return DEFAULT_SPACING_DP
        if (v.isNaN()) return DEFAULT_SPACING_DP
        return v.coerceIn(0f, MAX_SPACING_DP)
    }

    /**
     * 钳制宽度。模型写 px（1080/1440）时收敛到 [MAX_WIDTH_DP]，不再把组件推出屏幕。
     */
    fun width(value: Float?): Float? {
        val v = value ?: return null
        if (v.isNaN()) return null
        return v.coerceIn(0f, MAX_WIDTH_DP)
    }

    /**
     * 钳制高度。
     */
    fun height(value: Float?): Float? {
        val v = value ?: return null
        if (v.isNaN()) return null
        return v.coerceIn(0f, MAX_HEIGHT_DP)
    }

    /**
     * 钳制字号。`fontSize: 96` 这种值会让标题占满整屏并把兄弟元素挤出屏幕。
     */
    fun fontSize(value: Float?): Float? {
        val v = value ?: return null
        if (v.isNaN()) return null
        return v.coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)
    }

    /**
     * 钳制内边距四边。
     */
    fun padding(edge: EdgeInsets): EdgeInsets = clampEdge(edge, MAX_PADDING_DP)

    /**
     * 钳制外边距四边。
     * ⚠️ 与 padding 用**同一个**上限：外边距超过 120dp 同样会让内容推出版心。
     */
    fun margin(edge: EdgeInsets): EdgeInsets = clampEdge(edge, MAX_PADDING_DP)

    private fun clampEdge(edge: EdgeInsets, limit: Float): EdgeInsets {
        if (edge.start == 0f && edge.top == 0f && edge.end == 0f && edge.bottom == 0f) return edge
        fun c(v: Float) = if (v.isNaN()) 0f else v.coerceIn(0f, limit)
        return EdgeInsets(c(edge.start), c(edge.top), c(edge.end), c(edge.bottom))
    }

    fun cornerRadius(value: Float): Float =
        if (value.isNaN()) 0f else value.coerceIn(0f, MAX_CORNER_RADIUS_DP)

    fun borderWidth(value: Float): Float =
        if (value.isNaN()) 0f else value.coerceIn(0f, MAX_BORDER_WIDTH_DP)

    fun elevation(value: Float): Float =
        if (value.isNaN()) 0f else value.coerceIn(0f, MAX_ELEVATION_DP)

    fun opacity(value: Float): Float =
        if (value.isNaN()) 1f else value.coerceIn(MIN_OPACITY, MAX_OPACITY)

    fun rotation(value: Float): Float =
        if (value.isNaN()) 0f else value.coerceIn(-MAX_ROTATION_DEG, MAX_ROTATION_DEG)

    /**
     * 归一化容器方向。
     *
     * 🔴 为什么必须容错（用户反馈「排版方向不对」的根因之一）：
     * 原实现只判 `== "horizontal"`，模型写 `"Horizontal"`（首字母大写）、`"row"`、
     * `"h"`、`"x"` 全部静默落回竖排 —— 而用户看到的是一个**方向反了**的列表，
     * 不会有任何报错提示，只会让人觉得"AI 排版乱"。
     *
     * 横向别名覆盖：horizontal / row / h / x / sideway / inline
     */
    fun isHorizontal(raw: String?): Boolean {
        val v = normalize(raw) ?: return false
        return v in HORIZONTAL_ALIASES
    }

    private val HORIZONTAL_ALIASES = setOf(
        "horizontal", "horizontally", "row", "h", "x", "sideway", "sideways", "inline",
        "left_to_right", "ltr",
    )

    private val VERTICAL_ALIASES = setOf(
        "vertical", "vertically", "column", "col", "v", "y", "stacked", "stack",
        "top_to_bottom", "ttb",
    )

    /**
     * 归一化纵向别名。`null`（未指定）与非法值都返回 false —— 容器默认横向。
     */
    fun isVertical(raw: String?): Boolean {
        val v = normalize(raw) ?: return false
        return v in VERTICAL_ALIASES
    }

    /**
     * 关键字归一化：小写 + 把分隔符统一成下划线 + 去首尾空白。
     *
     * 🔴 为什么必须做这一步（2026-10-06 单测当场抓出）：原先别名表里逐个手写
     * `flex_start`，结果模型写 CSS 风格的 `flex-start`（连字符）就落进 else →
     * 静默变成 Top。同一个语义两种写法，模型无从判断，只能靠猜。
     * 归一化后 `flex-start` / `flex_start` / `FLEX START` 全部命中同一条目。
     */
    private fun normalize(raw: String?): String? {
        val v = raw?.trim()?.lowercase() ?: return null
        if (v.isEmpty()) return null
        // '-' 与空格统一成 '_'：让 flex-start / flex_start / FLEX START 命中同一条目
        return v.replace('-', '_').replace(' ', '_')
    }

    /**
     * 归一化交叉轴对齐（水平方向）。
     *
     * 🔴 `stretch` 之前被映射成 `CenterHorizontally` —— 这是**语义错误**：
     * stretch 的意思是"撑满"，不是"居中"。模型写 stretch 想让子元素铺满，
     * 结果全被推到中间，两侧留白。
     * 现在 stretch 返回 `null`，由调用方改用 fillMaxWidth 语义（见
     * [resolveCrossAxisStretch]）。
     */
    fun crossAxisStretch(value: String?): Boolean {
        val v = normalize(value) ?: return false
        return v in STRETCH_ALIASES
    }

    private val STRETCH_ALIASES = setOf("stretch", "fill", "stretch_fill", "fillx")

    /**
     * 主轴排列归一化。返回 null 表示未指定，调用方走默认 Top/Start。
     */
    fun arrangement(value: String?): String? = when (normalize(value)) {
        "start", "top", "left", "flex_start", "leading" -> "start"
        "center", "centre", "middle", "center_horizontally" -> "center"
        "end", "bottom", "right", "flex_end", "trailing" -> "end"
        "space_between", "between" -> "space_between"
        "space_around", "around" -> "space_around"
        "space_evenly", "evenly", "distributed" -> "space_evenly"
        else -> null
    }

    /**
     * 盒内对齐归一化（Box / stack 场景）。返回 null 表示未指定。
     *
     * 🔴 别名合并的坑（单测当场抓出）：若把 `top_left` 并进 `start`，
     * TopStart 就变成 CenterStart —— 元素被推到**垂直居中**而不是顶部，
     * 正是用户说的「排的位置不对」。所以带 `top_` / `bottom_` 前缀的别名
     * 必须映射到带前缀的目标，绝不能与裸 `left` / `start` 混同。
     */
    fun boxAlignment(value: String?): String? = when (normalize(value)) {
        "top", "top_center", "topcenter" -> "top"
        "bottom", "bottom_center", "bottomcenter" -> "bottom"
        "center", "centre", "middle" -> "center"
        // ⚠️ 带 top_/bottom_ 前缀的必须先判，否则被下面的裸别名吃掉
        "top_start", "top_left", "topleft" -> "top_start"
        "top_end", "top_right", "topright" -> "top_end"
        "bottom_start", "bottom_left", "bottomleft" -> "bottom_start"
        "bottom_end", "bottom_right", "bottomright" -> "bottom_end"
        "start", "left", "center_start", "centerstart" -> "start"
        "end", "right", "center_end", "centerend" -> "end"
        else -> null
    }

    fun fixedDp(dim: Dimension?, limit: Float = MAX_WIDTH_DP): Float? {
        val d = dim as? Dimension.Fixed ?: return null
        val v = d.dp
        if (v.isNaN()) return null
        return v.coerceIn(0f, limit)
    }
}
