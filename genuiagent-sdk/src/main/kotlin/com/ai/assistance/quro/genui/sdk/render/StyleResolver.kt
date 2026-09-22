package com.ai.assistance.quro.genui.sdk.render

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
        var token: String? = if (familyCut) rawLc?.removePrefix("cut")?.removePrefix("-") else rawLc

        // ── 侧向后缀 ──
        var side: String? = null
        for (s in listOf("-top", "-bottom", "-start", "-end")) {
            if (token != null && token.endsWith(s)) {
                side = s.removePrefix("-")
                token = token?.removeSuffix(s)
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
     * 解析颜色，支持主题色、命名颜色和十六进制颜色
     */
    fun resolveColor(value: String?, scheme: GenUIColorScheme, fallback: Color): Color {
        if (value.isNullOrBlank()) return fallback

        // 先尝试主题色
        ColorParser.resolveThemeColor(value, scheme)?.let { return it }

        // 再尝试命名颜色和十六进制
        return ColorParser.toColor(value, fallback)
    }

    /**
     * 解析尺寸 Dimension 为 Dp
     * 仅 Fixed 类型返回具体值，其他类型返回 null
     */
    fun resolveDp(dim: Dimension?): Dp? {
        return when (dim) {
            is Dimension.Fixed -> {
                // 防御：模型可能把像素值当 dp 输出（如 1080/2400），
                // 会把组件推出屏幕外。钳制到合理范围（屏幕高度不超过 ~1000dp）。
                val clamped = dim.dp.coerceIn(0f, 1200f)
                clamped.dp
            }
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
     * 构建基础 Modifier，包含尺寸、阴影、裁剪、背景、边框、内边距、透明度等
     * 这是 StyleResolver 的核心方法，将 UIStyle 的 28 个属性映射为 Compose Modifier
     */
    @Composable
    fun baseModifier(style: UIStyle, ctx: RenderContext): Modifier {
        val shape = resolveShape(style)
        var modifier: Modifier = Modifier

        // 宽度
        resolveDp(style.width)?.let { modifier = modifier.then(Modifier.width(it)) }
        // 高度
        resolveDp(style.height)?.let { modifier = modifier.then(Modifier.height(it)) }

        // Match / Weight 尺寸
        when (style.width) {
            is Dimension.Match -> modifier = modifier.then(Modifier.fillMaxWidth())
            is Dimension.Weight -> modifier = modifier.then(Modifier.fillMaxWidth((style.width as Dimension.Weight).fraction.coerceIn(0f, 1f)))
            else -> {}
        }
        when (style.height) {
            is Dimension.Match -> modifier = modifier.then(Modifier.fillMaxHeight())
            is Dimension.Weight -> modifier = modifier.then(Modifier.fillMaxHeight((style.height as Dimension.Weight).fraction.coerceIn(0f, 1f)))
            else -> {}
        }

        // 阴影
        if (style.elevation > 0f) {
            modifier = modifier.then(
                Modifier.shadow(
                    elevation = style.elevation.dp,
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
                val kind = style.pattern!!.lowercase(Locale.ROOT)
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
        if (style.rotate != 0f) {
            val rot = style.rotate.coerceIn(-180f, 180f)
            modifier = modifier.graphicsLayer { rotationZ = rot }
        }

        // 边框
        if (style.borderWidth > 0f && !style.borderColor.isNullOrBlank()) {
            val borderColor = resolveColor(
                style.borderColor,
                ctx.theme.colorScheme,
                ctx.theme.colorScheme.outline
            )
            modifier = modifier.then(
                Modifier.border(style.borderWidth.dp, borderColor, shape)
            )
        }

        // 内边距
        val padding = style.padding
        val hasPadding = padding.top > 0f || padding.bottom > 0f ||
                padding.start > 0f || padding.end > 0f
        if (hasPadding) {
            modifier = modifier.then(Modifier.padding(resolvePadding(padding)))
        }

        // 透明度
        if (style.opacity < 1f) {
            modifier = modifier.then(Modifier.alpha(style.opacity))
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
        style.textSize?.let { textStyle = textStyle.copy(fontSize = it.sp) }

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
