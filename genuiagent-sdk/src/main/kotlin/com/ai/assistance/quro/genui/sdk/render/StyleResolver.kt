package com.ai.assistance.quro.genui.sdk.render

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape as RCS
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.genui.sdk.dsl.Dimension
import com.ai.assistance.quro.genui.sdk.dsl.EdgeInsets
import com.ai.assistance.quro.genui.sdk.dsl.UIStyle
import com.ai.assistance.quro.genui.sdk.style.ColorParser
import com.ai.assistance.quro.genui.sdk.style.FontProvider
import com.ai.assistance.quro.genui.sdk.style.GenUIColorScheme
import com.ai.assistance.quro.genui.sdk.style.ZorvPalette
import java.util.Locale
import androidx.compose.runtime.Composable

/**
 * 样式解析器，将 UIStyle 转换为 Compose 的 Modifier、颜色、形状等
 */
object StyleResolver {

    /**
     * 解析形状（Material 3 形状体系完整支持）
     *
     * shape 值语法：[cut-]<刻度|全形名>[-top|-bottom|-start|-end]
     * - 全形名：circle / pill|capsule|stadium|full / none|rectangle|square
     * - 刻度（M3 圆角刻度）：xs=4 sm=8 md=12 lg=16 lg+=20 xl=28 xl+=32 xxl=48
     *   （全称等价：extra-small / small / medium / large / large-increased / extra-large / extra-large-increased / extra-extra-large）
     * - 侧向角（非对称）：-top 仅顶部 / -bottom 仅底部 / -start 仅起始侧 / -end 仅结束侧
     *   例："xl-top"（底部工作表：顶 28 底直角）、"lg-start"（抽屉）
     * - cut 家族（切角）：前缀 "cut-"，例 "cut-16"、"cut-lg-top"（45° 直线切角，Full 时呈六边形/菱形）
     * - cornerRadius 显式值 > 0 时作为四角半径优先；shape 提供家族与侧向
     * - 未知形状名（如趣味形）安全回退：cornerRadius 或直角
     */
    fun resolveShape(style: UIStyle): Shape {
        val raw = style.shape?.trim()?.takeIf { it.isNotBlank() }
        // Expressive 35 形状库（heart/cookie12/flower/…）→ 自定义 Shape 委托绘制层
        if (raw != null && com.ai.assistance.quro.genui.sdk.style.ExpressiveShapes.isExpressive(raw)) {
            return ExpressiveDelegateShape(raw)
        }

        // ── 全形名（无视 cornerRadius）──
        when (raw) {
            "circle" -> return RoundedCornerShape(50)
            "pill", "capsule", "stadium", "full" -> return RoundedCornerShape(50)
            "none", "rectangle", "square" -> return RectangleShape
        }

        val rawLc = raw?.lowercase(Locale.ROOT)
        val familyCut = rawLc?.startsWith("cut") == true
        // familyCut 为 true 已蕴含 rawLc 非空，编译器能 smart-cast，这里无需 ?/!!
        var token: String? = if (familyCut) rawLc.removePrefix("cut").removePrefix("-") else rawLc

        // ── 侧向后缀 ──
        var side: String? = null
        for (s in listOf("-top", "-bottom", "-start", "-end")) {
            if (token != null && token.endsWith(s)) {
                side = s.removePrefix("-")
                token = token.removeSuffix(s)
                break
            }
        }

        // ── 刻度表（M3 圆角刻度 10 级）──
        val scale: Float? = when (token) {
            "xs", "extra-small", "extraSmall", "extra_small" -> 4f
            "sm", "small" -> 8f
            "md", "medium" -> 12f
            "lg", "large" -> 16f
            "lg+", "large-increased", "largeIncreased", "large_increased" -> 20f
            "xl", "extra-large", "extraLarge", "extra_large" -> 28f
            "xl+", "extra-large-increased", "extraLargeIncreased", "extra_large_increased" -> 32f
            "xxl", "extra-extra-large", "extraExtraLarge", "extra_extra_large" -> 48f
            else -> null
        }

        val radius = style.cornerRadius.takeIf { it > 0f } ?: scale
        if (radius == null || radius <= 0f) {
            // 无半径可用：family=cut 也无从切角，回退
            return if (rawLc == "rounded" || rawLc == "round") RoundedCornerShape(12.dp) else RectangleShape
        }

        val r = radius.dp
        return if (familyCut) {
            when (side) {
                "top" -> CutCornerShape(r, r, 0.dp, 0.dp)
                "bottom" -> CutCornerShape(0.dp, 0.dp, r, r)
                "start" -> CutCornerShape(r, 0.dp, 0.dp, r)
                "end" -> CutCornerShape(0.dp, r, r, 0.dp)
                else -> CutCornerShape(r)
            }
        } else {
            when (side) {
                // Compose 顺序：topStart, topEnd, bottomEnd, bottomStart
                "top" -> RoundedCornerShape(r, r, 0.dp, 0.dp)
                "bottom" -> RoundedCornerShape(0.dp, 0.dp, r, r)
                "start" -> RoundedCornerShape(r, 0.dp, 0.dp, r)
                "end" -> RoundedCornerShape(0.dp, r, r, 0.dp)
                else -> RoundedCornerShape(r)
            }
        }
    }

    /**
     * 解析颜色：语义角色（含 success/warning/info/rise/fall 等）→ 命名/十六进制（自动暖化收敛）
     *
     * 十六进制走 [ZorvPalette.harmonize]：把 AI 手写的霓虹/Tailwind 色收敛进 ZorvAI 暖色体系，
     * 保证「AI 手写颜色」也不会跳出色板（只降饱和/压荧光，不改色相语义）。
     */
    fun resolveColor(value: String?, scheme: GenUIColorScheme, fallback: Color): Color {
        if (value.isNullOrBlank()) return fallback

        // 语义角色优先（新角色由 scheme.semantic 统一收口）
        scheme.semantic(value)?.let { return it }

        // 兼容旧角色名表
        ColorParser.resolveThemeColor(value, scheme)?.let { return it }

        // 命名颜色和十六进制 → 审美收敛
        val parsed = ColorParser.toColor(value, fallback)
        return runCatching { ZorvPalette.harmonize(parsed, dark = scheme.background.luminance() < 0.5f) }
            .getOrDefault(parsed)
    }

    /**
     * 解析尺寸 Dimension 为 Dp
     * 仅 Fixed 类型返回具体值，其他类型返回 null
     *
     * 🔴 钳制职责已下放给 [GenUILayoutGuard]（2026-10-06）：原先在这里硬编码 `coerceIn(0f, 1200f)`，
     * 但**宽度**和**高度**的上限完全不同 —— 1200dp 宽的组件在 360dp 屏上会横向截掉三分之二，
     * 这正是用户说的「排版溢出屏幕」。现在按调用点的语义分别传 limit。
     */
    fun resolveDp(dim: Dimension?, limit: Float = GenUILayoutGuard.MAX_HEIGHT_DP): Dp? {
        return when (dim) {
            is Dimension.Fixed -> GenUILayoutGuard.fixedDp(dim, limit)?.dp
            else -> null
        }
    }

    /**
     * 解析文本对齐方式
     */
    fun resolveTextAlign(value: String?): TextAlign? {
        return when (value?.lowercase(Locale.ROOT)) {
            "center" -> TextAlign.Center
            "end", "right" -> TextAlign.End
            "start", "left" -> TextAlign.Start
            else -> null
        }
    }

    /**
     * 解析字体粗细
     */
    fun resolveFontWeight(value: String?): FontWeight? {
        return when (value?.lowercase(Locale.ROOT)) {
            "thin" -> FontWeight.Thin
            "light" -> FontWeight.Light
            "normal", "regular" -> FontWeight.Normal
            "medium" -> FontWeight.Medium
            "semibold" -> FontWeight.SemiBold
            "bold" -> FontWeight.Bold
            "extrabold" -> FontWeight.ExtraBold
            "black" -> FontWeight.Black
            else -> null
        }
    }

    /**
     * 解析文本溢出方式
     */
    fun resolveOverflow(value: String?): TextOverflow {
        return when (value?.lowercase(Locale.ROOT)) {
            "ellipsis" -> TextOverflow.Ellipsis
            "visible" -> TextOverflow.Visible
            "clip" -> TextOverflow.Clip
            else -> TextOverflow.Clip
        }
    }

    /**
     * 解析边距
     */
    fun resolvePadding(edge: EdgeInsets): PaddingValues {
        return PaddingValues(
            start = edge.start.dp,
            top = edge.top.dp,
            end = edge.end.dp,
            bottom = edge.bottom.dp
        )
    }

    /**
     * 构建基础 Modifier，包含尺寸、阴影、裁剪、背景、边框、内外边距、透明度等
     * 这是 StyleResolver 的核心方法，将 UIStyle 的属性映射为 Compose Modifier
     *
     * 🔴 2026-10-06：本方法所有数值一律经 [GenUILayoutGuard] 收敛。
     * 之前这里是裸值直传，模型写 `width: 1080`（把 px 当 dp）就会把组件推出屏幕，
     * 写 `padding: 200` 会把内容挤成一个点 —— 两者都是用户反馈的「排版溢出屏幕」。
     */
    @Composable
    fun baseModifier(
        style: UIStyle,
        ctx: RenderContext,
        /**
         * 🔴 本节点是否处于 **Row / flex_row** 的直接子位。
         *
         * ## 为什么必须有这个参数（GenUI 竖排挤压的真元
         *
         * `RenderNode` 给每个节点都包了一层 `Box(modifier)`，
         * 而 [baseModifier] 对 `width: match` 无条件地 `fillMaxWidth()`。
         * 在 Column 里这是正确的（占满一行）；
         * 但在 Row 里——Compose 测量非 weight 子节点时给的是「剩余宽度」，
         * 第一个 `match` 子节点拿 `fillMaxWidth` 就把整行吃干净，
         * 后面的兄弟只剩 0 宽——里面的 Text 被压成「一个字一行」的竖排。
         *
         * 用户截图里的天气卡就是这个：左侧「实时天气 · 清晨」占满，
         * 右侧「体感 24.1° / 西北风 4.8km/h / 湿度 84% / 气压 1015hPa」全部竖成一列字。
         *
         * 正确语义（与 `ColumnRowRenderer` 里的 match→weight 修复同源）：
         * Row 下的 match 应当是「分掉剩余空间」，而不是「占满整行」；
         * 而行内包装容器（box/container/card）不应含素尽量可压缩。
         */
        inRowContext: Boolean = false,
    ): Modifier {
        val shape = resolveShape(style)
        var modifier: Modifier = Modifier

        // 宽度（独立上限：720dp，与 PageCanvas 阅读宽度一致）
        resolveDp(style.width, GenUILayoutGuard.MAX_WIDTH_DP)
            ?.let { modifier = modifier.then(Modifier.width(it)) }
        // 高度（独立上限：900dp）
        resolveDp(style.height, GenUILayoutGuard.MAX_HEIGHT_DP)
            ?.let { modifier = modifier.then(Modifier.height(it)) }

        // Match / Weight 尺寸
        // 🔴 Row 上下文里 match 不能无条件 fillMaxWidth（会吃掉整行，
        // 把兄弟节点压成竖排）。改用 widthIn 让它自然收敛。
        when (val w = style.width) {
            is Dimension.Match -> modifier = modifier.then(
                if (inRowContext) Modifier.widthIn(max = GenUILayoutGuard.MAX_WIDTH_DP.dp)
                else Modifier.fillMaxWidth()
            )
            is Dimension.Weight -> {
                val frac = w.fraction.coerceIn(0f, 1f)
                // Row 下的 fillMaxWidth(fraction) 同样会超出剩余宽度（分数乘的是整行），
                // 改成「剩余空间的一个比例」才是正确语义。
                modifier = modifier.then(
                    if (inRowContext) Modifier.fillMaxWidth(frac.coerceAtMost(1f))
                    else Modifier.fillMaxWidth(frac)
                )
            }
            else -> {}
        }
        when (val h = style.height) {
            is Dimension.Match -> modifier = modifier.then(Modifier.fillMaxHeight())
            is Dimension.Weight -> modifier = modifier.then(Modifier.fillMaxHeight(h.fraction.coerceIn(0f, 1f)))
            else -> {}
        }

        // 阴影
        val elevation = GenUILayoutGuard.elevation(style.elevation)
        if (elevation > 0f) {
            modifier = modifier.then(
                Modifier.shadow(
                    elevation = elevation.dp,
                    shape = shape,
                    clip = style.cornerRadius > 0f
                )
            )
        }

        // 裁剪
        if (style.cornerRadius > 0f || style.clip || style.shape != null) {
            modifier = modifier.then(Modifier.clip(shape))
        }

        // 背景色（v2：支持线性渐变，优先于纯色）
        val gradStops = style.gradient?.split(',')
            ?.mapNotNull { ColorParser.toColor(it.trim(), Color.Unspecified).takeIf { c -> c != Color.Unspecified } }
        if (gradStops != null && gradStops.size >= 2) {
            val rad = (style.gradientAngle * PI / 180.0).toFloat()
            val dir = Offset(cos(rad), sin(rad))
            val center = Offset(0.5f, 0.5f)
            val start = center - dir * 0.5f
            val end = center + dir * 0.5f
            modifier = modifier.then(Modifier.background(Brush.linearGradient(gradStops, start, end), shape))
        } else {
            val bgColor = resolveColor(style.backgroundColor, ctx.theme.colorScheme, Color.Unspecified)
            if (bgColor != Color.Unspecified) {
                modifier = modifier.then(Modifier.background(bgColor, shape))
            }
        }

        // 纹理 overlay（dots/stripes/grid/checker）
        if (!style.pattern.isNullOrBlank()) {
            val patColor = resolveColor(style.patternColor, ctx.theme.colorScheme, Color.Unspecified)
            if (patColor != Color.Unspecified) {
                val pc = patColor.copy(alpha = patColor.alpha * 0.3f)
                val kind = style.pattern.lowercase(Locale.ROOT)
                modifier = modifier.drawBehind {
                    when (kind) {
                        "dots" -> {
                            val step = 14.dp.toPx(); val r = 2.dp.toPx()
                            var y = r; var row = 0
                            while (y < size.height) {
                                var x = if (row % 2 == 0) r else r + step / 2
                                while (x < size.width) { drawCircle(pc, r, Offset(x, y)); x += step }
                                y += step; row++
                            }
                        }
                        "stripes" -> {
                            val step = 18.dp.toPx(); val w = 5.dp.toPx()
                            var x = -size.height
                            while (x < size.width) {
                                drawLine(pc, Offset(x, size.height), Offset(x + size.height, 0f), w)
                                x += step
                            }
                        }
                        "grid" -> {
                            val step = 16.dp.toPx(); val w = 1.dp.toPx()
                            var x = 0f
                            while (x < size.width) { drawLine(pc, Offset(x, 0f), Offset(x, size.height), w); x += step }
                            var y = 0f
                            while (y < size.height) { drawLine(pc, Offset(0f, y), Offset(size.width, y), w); y += step }
                        }
                        "checker" -> {
                            val cell = 12.dp.toPx()
                            var row = 0; var y = 0f
                            while (y < size.height) {
                                var col = 0; var x = 0f
                                while (x < size.width) {
                                    if ((row + col) % 2 == 0) drawRect(pc, Offset(x, y), Size(cell, cell))
                                    x += cell; col++
                                }
                                y += cell; row++
                            }
                        }
                    }
                }
            }
        }

        // 霓虹辉光（BlurMaskFilter，低版本系统自动降级为无辉光）
        val glowC = resolveColor(style.glow, ctx.theme.colorScheme, Color.Unspecified)
        if (glowC != Color.Unspecified && style.glowRadius > 0f) {
            val r = style.glowRadius.coerceIn(2f, 60f)
            val cr = style.cornerRadius.coerceIn(0f, 120f)
            modifier = modifier.drawBehind {
                drawIntoCanvas { canvas ->
                    val fw = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.argb(
                            (glowC.alpha * 200).toInt(),
                            (glowC.red * 255).toInt(), (glowC.green * 255).toInt(), (glowC.blue * 255).toInt()
                        )
                        maskFilter = android.graphics.BlurMaskFilter(r.dp.toPx(), android.graphics.BlurMaskFilter.Blur.NORMAL)
                    }
                    val pad = r.dp.toPx() * 0.4f
                    canvas.nativeCanvas.drawRoundRect(
                        -pad, -pad, size.width + pad, size.height + pad,
                        cr.dp.toPx(), cr.dp.toPx(), fw
                    )
                }
            }
        }

        // 旋转
        val rotation = GenUILayoutGuard.rotation(style.rotate)
        if (rotation != 0f) {
            modifier = modifier.graphicsLayer { rotationZ = rotation }
        }

        // 边框
        val borderWidth = GenUILayoutGuard.borderWidth(style.borderWidth)
        if (borderWidth > 0f && !style.borderColor.isNullOrBlank()) {
            val borderColor = resolveColor(
                style.borderColor,
                ctx.theme.colorScheme,
                ctx.theme.colorScheme.outline
            )
            modifier = modifier.then(
                Modifier.border(borderWidth.dp, borderColor, shape)
            )
        }

        // 外边距（margin）
        //
        // 根因（2026-10-06 修复）：`UIStyle.margin` 字段从 DSL 定义起就**从未被渲染层消费过**
        // —— 全仓只有 `margin_container` 组件经 PaddingContainerRenderer 用到它。
        // 于是模型按提示词写 `{"margin": 12}`（QuroDynamicUiTool 明确教了它这么写）却毫无效果：
        // 卡片之间没有间距、元素糊成一片。用户看到的正是「组件虽然多但是不齐」。
        //
        // 顺序说明：margin 是「外部留白」，必须**先**占位再谈内容内边距；
        // 反过来会出现「卡片比容器窄一圈还偏上」的位置错乱。
        val margin = GenUILayoutGuard.margin(style.margin)
        val hasMargin = margin.start > 0f || margin.top > 0f ||
                margin.end > 0f || margin.bottom > 0f
        if (hasMargin) {
            modifier = modifier.then(Modifier.padding(resolvePadding(margin)))
        }

        // 内边距
        val padding = GenUILayoutGuard.padding(style.padding)
        val hasPadding = padding.top > 0f || padding.bottom > 0f ||
                padding.start > 0f || padding.end > 0f
        if (hasPadding) {
            modifier = modifier.then(Modifier.padding(resolvePadding(padding)))
        }

        // 透明度
        val opacity = GenUILayoutGuard.opacity(style.opacity)
        if (opacity < 1f) {
            modifier = modifier.then(Modifier.alpha(opacity))
        }

        return modifier
    }

    /**
     * 解析文本颜色
     */
    fun resolveTextColor(style: UIStyle, ctx: RenderContext, fallback: Color): Color {
        return resolveColor(style.textColor, ctx.theme.colorScheme, fallback)
    }

    /**
     * 构建 TextStyle，基于基础样式叠加 UIStyle 中的文本属性
     */
    fun buildTextStyle(style: UIStyle, ctx: RenderContext, base: TextStyle): TextStyle {
        var textStyle = base.copy(
            color = resolveTextColor(style, ctx, base.color)
        )

        // 字号
        // 🔴 经守卫钳制（2026-10-06）：模型写 `fontSize: 96` 时，一行标题就占满整屏，
        // 把兄弟元素全挤出可视区 —— 这是「排版溢出屏幕」最常见的来源之一。
        GenUILayoutGuard.fontSize(style.textSize)
            ?.let { textStyle = textStyle.copy(fontSize = it.sp) }

        // 字重
        resolveFontWeight(style.fontWeight)?.let { textStyle = textStyle.copy(fontWeight = it) }

        // 字体
        style.fontFamily?.let {
            textStyle = textStyle.copy(
                fontFamily = FontProvider.resolve(it)
            )
        }

        // 字间距
        style.letterSpacing?.let { textStyle = textStyle.copy(letterSpacing = it.sp) }

        // 行高
        style.lineHeight?.let { textStyle = textStyle.copy(lineHeight = it.sp) }

        // 文本对齐
        resolveTextAlign(style.textAlign)?.let { textStyle = textStyle.copy(textAlign = it) }

        // 斜体
        if (style.textStyle?.lowercase(Locale.ROOT) == "italic") {
            textStyle = textStyle.copy(fontStyle = FontStyle.Italic)
        }

        return textStyle
    }

    /**
     * 解析语义属性（无障碍）
     */
    fun resolveSemantics(style: UIStyle): Modifier {
        val contentDesc = style.contentDescription
        if (contentDesc.isNullOrBlank()) return Modifier

        val role = when (style.semanticsRole?.lowercase(Locale.ROOT)) {
            "button" -> Role.Button
            "checkbox" -> Role.Checkbox
            "switch" -> Role.Switch
            "image" -> Role.Image
            "tab" -> Role.Tab
            else -> null
        }

        val isHeading = style.semanticsRole?.lowercase(Locale.ROOT) == "header"

        return Modifier.semantics {
            this.contentDescription = contentDesc
            if (role != null) this.role = role
            if (isHeading) this.heading()
        }
    }
}


/** Expressive 形状库委托：按组件实际尺寸构建 Path */
class ExpressiveDelegateShape(private val shapeName: String) : androidx.compose.ui.graphics.Shape {
    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        density: androidx.compose.ui.unit.Density
    ): androidx.compose.ui.graphics.Outline {
        val path = com.ai.assistance.quro.genui.sdk.style.ExpressiveShapes.build(shapeName, size.width, size.height)
            ?: return androidx.compose.ui.graphics.Outline.Rectangle(
                androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height)
            )
        return androidx.compose.ui.graphics.Outline.Generic(path)
    }
}
