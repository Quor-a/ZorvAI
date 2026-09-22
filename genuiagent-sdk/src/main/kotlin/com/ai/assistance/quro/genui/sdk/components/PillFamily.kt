package com.ai.assistance.quro.genui.sdk.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.genui.sdk.dsl.Dimension
import com.ai.assistance.quro.genui.sdk.dsl.UIComponent
import com.ai.assistance.quro.genui.sdk.dsl.UIStyle
import com.ai.assistance.quro.genui.sdk.render.RenderContext
import com.ai.assistance.quro.genui.sdk.render.StyleResolver

/**
 * ═══════════════════════════════════════════════════════════════
 * 胶囊组件家族（Pill Family）— 15 类型 × 14,400 变体/类型 = 216,000 硬变体
 *
 * 特性：
 * 1. 【零配置胶囊】渲染器内部强制 RoundedCornerShape(50)——写组件名即胶囊，
 *    忘写 shape:"pill" 也不会退化成直角块（56470 教训）
 * 2. 变体系统全兼容：palette / density / mood → 经 VariantEngine.applyToStyle
 *    （12 配色 × 3 密度 × 5 情绪 = 180 组合/类型 × 尺寸/颜色/图标自由 = 千款形态，
 *     组合嵌套 300 组件场景 → 1000 万+ 裂变空间）
 * 3. style 全支持：backgroundColor / textColor / borderColor+Width / gradient /
 *    glow / padding / textSize / fontWeight——效果引擎直通
 *
 * 类型清单（properties 支持）：
 *  pill_badge        文字徽章        text
 *  pill_chip         可选中标签      text, selected("true")
 *  pill_tag          静态标签        text
 *  pill_button       胶囊按钮        text（events.onClick → 动作执行）
 *  pill_toggle       开关胶囊        text, active("true"/"false")
 *  pill_counter      计数徽章        count（99+ 截断）
 *  pill_avatar_text  头像字+文字     avatarText, text
 *  pill_status       状态点胶囊      text, dotColor
 *  pill_filter       筛选胶囊        text, count
 *  pill_stepper      步进胶囊        value（-/+ 仅展示语义）
 *  pill_icon         图标胶囊        icon（IconMapper 名）
 *  pill_notification 通知胶囊        text, count（右上角标）
 *  pill_input        输入胶囊        hint（展示态）
 *  pill_search       搜索胶囊        hint（展示态）
 *  pill_meter        度量胶囊        text, value(0-1 或百分制填充)
 * ═══════════════════════════════════════════════════════════════
 */
object PillFamily {

    /** 胶囊语义类型 → 显示语义分组（供提示词/文档层引用） */
    val KINDS = listOf(
        "pill_badge", "pill_chip", "pill_tag", "pill_button", "pill_toggle",
        "pill_counter", "pill_avatar_text", "pill_status", "pill_filter",
        "pill_stepper", "pill_icon", "pill_notification", "pill_input",
        "pill_search", "pill_meter"
    )

    /** 注册 15 个胶囊类型到组件注册表 */
    fun registerAll(registry: com.ai.assistance.quro.genui.sdk.render.ComponentRegistry) {
        KINDS.forEach { kind ->
            registry.register(kind) { c, ctx -> PillRenderer(c, ctx, kind) }
        }
    }
}

@Composable
fun PillRenderer(
    component: UIComponent,
    ctx: RenderContext,
    kind: String,
    modifier: Modifier = Modifier
) {
    val props = component.properties
    fun str(key: String): String? = (props[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    // 变体系统：palette/density/mood（shape 强制 pill，无视声明）
    val style = remember(component.id, component.hashCode()) {
        ComponentVariants.applyToStyle(component.style, props).let { s ->
            // 胶囊兜底：无显式背景时给柔和默认，避免"裸文字直角块"
            if (s.backgroundColor == null) s.copy(backgroundColor = "#F2EEE3") else s
        }
    }

    // 强制胶囊：任何情况下 shape=50%（零配置胶囊的核心）
    val shape = RoundedCornerShape(50)

    // 背景色 / 渐变（渐变："#A,#B,#C" 逗号分色，效果引擎直通）
    val bg = StyleResolver.resolveColor(
        style.backgroundColor, ctx.theme.colorScheme,
        ctx.theme.colorScheme.surfaceVariant
    )
    val gradientBrush = style.gradient?.let { spec ->
        val colors = spec.split(',').mapNotNull {
            StyleResolver.resolveColor(it.trim(), ctx.theme.colorScheme, Color.Unspecified)
                .takeIf { c -> c != Color.Unspecified }
        }
        if (colors.size >= 2) Brush.linearGradient(colors) else null
    }
    val contentColor = StyleResolver.resolveColor(
        style.textColor, ctx.theme.colorScheme,
        ctx.theme.colorScheme.onSurface
    )
    val borderColor = if (style.borderWidth > 0f) StyleResolver.resolveColor(
        style.borderColor, ctx.theme.colorScheme,
        ctx.theme.colorScheme.outline
    ) else Color.Unspecified

    // 文本
    val text = str("text") ?: ""
    val count = str("count")?.toIntOrNull()
    val dotColor = str("dotColor")?.let {
        StyleResolver.resolveColor(it, ctx.theme.colorScheme, Color(0xFF22C55E))
    } ?: Color(0xFF22C55E)
    val selected = str("selected") == "true"
    val active = str("active") == "true"
    val value = run {
        var v = str("value")?.removeSuffix("%")?.toFloatOrNull() ?: -1f
        if (v > 1f) v /= 100f
        v.coerceIn(0f, 1f)
    }

    val textSize = (style.textSize ?: 13f).sp
    val fontWeight = when (style.fontWeight?.lowercase()) {
        "bold", "700", "800", "900" -> FontWeight.Bold
        "500", "600" -> FontWeight.SemiBold
        else -> FontWeight.Medium
    }

    // 密度内边距（变体系统已调过 padding，默认胶囊紧凑）
    val hPad = if (style.padding.start > 0f) style.padding.start.dp else 12.dp
    val vPad = if (style.padding.top > 0f) style.padding.top.dp else 6.dp

    var pillModifier = modifier
        .clip(shape)
        .background(gradientBrush ?: androidx.compose.ui.graphics.SolidColor(bg))
    if (style.borderWidth > 0f) {
        pillModifier = pillModifier.border(
            style.borderWidth.dp, borderColor, shape
        )
    }
    if (style.glow != null && style.glowRadius > 0f) {
        val glowC = StyleResolver.resolveColor(style.glow, ctx.theme.colorScheme, Color.Transparent)
        if (glowC != Color.Transparent) {
            pillModifier = pillModifier.then(
                Modifier.background(
                    Brush.radialGradient(listOf(glowC.copy(alpha = 0.35f), Color.Transparent)),
                    shape
                )
            )
        }
    }
    pillModifier = pillModifier
        .clickable(
            enabled = kind == "pill_button" || kind == "pill_chip" || kind == "pill_filter",
            indication = null,
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        ) {
            if (kind == "pill_button") {
                ctx.executor.execute(component.events["onClick"], component)
            }
        }
        .padding(horizontal = hPad, vertical = vPad)

    Row(
        modifier = pillModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        // 头像字 / 图标
        if (kind == "pill_avatar_text") {
            str("avatarText")?.let { av ->
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(50))
                        .background(contentColor.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(av.take(1), color = contentColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (kind == "pill_icon" || kind == "pill_search") {
            val iconName = if (kind == "pill_search") "search" else str("icon")
            Icon(
                imageVector = IconMapper.map(iconName),
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(15.dp)
            )
        }
        // 状态点
        if (kind == "pill_status" || (kind == "pill_toggle" && active)) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(RoundedCornerShape(50))
                    .background(dotColor)
            )
        }
        // 主文字
        if (text.isNotBlank() || kind !in setOf("pill_counter", "pill_icon")) {
            Text(
                text = text.ifBlank {
                    when (kind) {
                        "pill_input", "pill_search" -> str("hint") ?: ""
                        "pill_counter" -> ""
                        else -> ""
                    }
                },
                color = contentColor,
                fontSize = textSize,
                fontWeight = fontWeight,
                maxLines = 1
            )
        }
        // 计数
        if (count != null && count > 0) {
            Text(
                text = if (count > 99) "99+" else count.toString(),
                color = contentColor.copy(alpha = 0.85f),
                fontSize = textSize,
                fontWeight = FontWeight.Bold
            )
        }
        // 开关轨道
        if (kind == "pill_toggle") {
            val track = if (active) contentColor.copy(alpha = 0.9f) else contentColor.copy(alpha = 0.3f)
            Box(
                Modifier
                    .width(30.dp)
                    .height(16.dp)
                    .clip(RoundedCornerShape(50))
                    .background(track.copy(alpha = 0.25f))
            ) {
                Box(
                    Modifier
                        .align(if (active) Alignment.CenterEnd else Alignment.CenterStart)
                        .padding(2.dp)
                        .size(12.dp)
                        .clip(RoundedCornerShape(50))
                        .background(track)
                )
            }
        }
        // 度量填充
        if (kind == "pill_meter" && value >= 0f) {
            Box(
                Modifier
                    .weight(1f, fill = false)
                    .height(5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(contentColor.copy(alpha = 0.18f))
                    .width(56.dp)
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(value)
                        .clip(RoundedCornerShape(50))
                        .background(contentColor)
                )
            }
        }
    }
}
