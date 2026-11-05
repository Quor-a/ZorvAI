package com.ai.assistance.quro.core.ui.card.compose

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ai.assistance.quro.core.ui.card.spec.Action
import com.ai.assistance.quro.core.ui.card.spec.CardSpec
import com.ai.assistance.quro.core.ui.card.spec.ColorToken
import com.ai.assistance.quro.core.ui.card.spec.LayoutNode
import kotlin.math.cos
import kotlin.math.sin

/**
 * 自定义布局卡（`custom`）——AI 自写、不套内置模板的小卡片形态。
 *
 * ## 历史问题与现在的解法
 *
 * 旧版整棵布局树在**单个 Canvas** 上手绘：`measureNode` 用 `ch.code > 0x2E80 ? 1f : 0.55f`
 * 估文字宽度、`childAllocs` 手算 weight 压缩、`drawChildren` 裸 `return` 修右侧消失、
 * 未知节点 `get(0)` 尺寸塌陷 —— 于是文字宽度错估、右侧内容整块消失、行溢出、
 * 未知节点内容凭空不见，全是同一个架构错误的连锁反应。
 *
 * 现在是 SDUI 标准做法：容器/文字/徽章/按钮/图标全部交给 Compose 原语自动排版，
 * **只有连续几何**（环形 / 进度条）走局部 Canvas。weight 分配、文字测量、命中测试
 * 都由布局引擎负责，上述那一串 bug 从架构上不可能再发生。
 *
 * 本次补的能力（每一处都对应真机上「卡片看着不对」的具体现象）：
 *  - **百分比归一**：`value:0.86` 与 `value:86` 都当 86%（旧版只认后者，前者画出来是空的）；
 *  - **grid 多列**：按 `columns` 分列，而不是把网格当成竖排单列；
 *  - **image 走 Coil**：`url`/`src` 真的把图显示出来（旧版只画一个灰方块）；
 *  - **文本样式**：`align` / `maxLines` / `lineHeight` / `italic`；
 *  - **卡片描边与阴影**：`border` / `borderColor` / `shadow`，深浅色主题下都有层次；
 *  - **ring 下方标签 + bar 数值**：环形下方补标签、进度条右侧补百分比数字。
 */
@Composable
fun CustomCardView(
    spec: CardSpec,
    modifier: Modifier = Modifier,
    onAction: (Action) -> Unit,
    resolveToken: (ColorToken) -> Color,
) {
    val root = spec.layout ?: return
    // 入场动画相位 0→1：控制数字滚动 / 环形扫角 / 进度生长。
    val phase by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
        label = "customCardPhase",
    )
    val ctx = CustomRenderContext(spec, onAction, resolveToken, phase, IntArray(1))
    NodeView(root, ctx)
}

/** 渲染上下文：沿递归树向下传递，避免每个节点重复传一堆参数。 */
private data class CustomRenderContext(
    val spec: CardSpec,
    val onAction: (Action) -> Unit,
    val resolveToken: (ColorToken) -> Color,
    val phase: Float,
    val buttonSeq: IntArray, // 可变计数器：custom 按钮按出现顺序映射到 spec.actions
)

@Composable
private fun NodeView(node: LayoutNode, ctx: CustomRenderContext) {
    val p = node.props
    val pad = CardExtractors.num(p, "padding", node.style.paddingDp)
    val gap = CardExtractors.num(p, "gap", 8f)

    val sizeModifier = Modifier
        .then(if (node.widthDp != null) Modifier.width(node.widthDp!!.dp) else Modifier)
        .then(if (node.heightDp != null) Modifier.height(node.heightDp!!.dp) else Modifier)

    when (node.type) {
        "card", "box" -> {
            val radius = CardExtractors.num(p, "radius", node.style.cornerDp)
            val shape = RoundedCornerShape(radius.dp)
            val bg = nodeBackground(node, ctx)
            val shadow = CardExtractors.num(p, "shadow", CardExtractors.num(p, "elevation", 0f))
            val borderW = CardExtractors.num(p, "border", 0f)
            val borderColor = CardExtractors.parseColor(p["borderColor"], ctx.resolveToken)
                ?: ctx.resolveToken(ColorToken.Outline)
            Box(
                Modifier
                    .fillMaxWidth()
                    .then(if (shadow > 0f) Modifier.shadow(shadow.dp, shape) else Modifier)
                    .then(sizeModifier)
                    .clip(shape)
                    .let { if (bg is Brush) it.background(bg, shape) else it.background(bg as Color, shape) }
                    .then(
                        if (borderW > 0f) {
                            Modifier.border(borderW.dp, borderColor.copy(alpha = 0.55f), shape)
                        } else {
                            Modifier
                        },
                    )
                    .padding(pad.dp),
            ) {
                ChildColumn(node, ctx, gap)
            }
        }
        "column" -> ChildColumn(node, ctx, gap, sizeModifier.fillMaxWidth())
        "row" -> Row(
            Modifier.fillMaxWidth().then(sizeModifier),
            horizontalArrangement = Arrangement.spacedBy(gap.dp),
        ) {
            node.children.forEach { child ->
                // weight 直接作用在 Row 的直接子节点上：Compose 原生按权重分配剩余空间，
                // 彻底消除旧版「weight 子节点分到 0 宽、右侧内容整块消失」的 bug。
                val weightMod = if (child.weight > 0f) Modifier.weight(child.weight) else Modifier
                Box(Modifier.then(weightMod)) { NodeView(child, ctx) }
            }
        }
        // 网格：按 columns 分列（默认 2 列）。旧版把 grid 当竖列处理，四宫格变四行。
        "grid" -> {
            val columns = CardExtractors.num(p, "columns", 2f).toInt().coerceIn(1, 6)
            Column(
                Modifier.fillMaxWidth().then(sizeModifier),
                verticalArrangement = Arrangement.spacedBy(gap.dp),
            ) {
                node.children.chunked(columns).forEach { rowItems ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(gap.dp),
                    ) {
                        rowItems.forEach { child -> Box(Modifier.weight(1f)) { NodeView(child, ctx) } }
                        repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        "text" -> TextNode(node, ctx, sizeModifier.fillMaxWidth())
        "badge", "chip", "tag", "pill" -> BadgeNode(node, ctx)
        "button" -> ButtonNode(node, ctx, sizeModifier)
        "icon", "avatar" -> IconNode(node, ctx)
        "image" -> ImageNode(node, ctx, sizeModifier)
        "spacer" -> {
            val h = CardExtractors.num(p, "h", 8f)
            val w = CardExtractors.num(p, "w", 0f)
            Spacer(Modifier.height(h.dp).then(if (w > 0f) Modifier.width(w.dp) else Modifier))
        }
        "divider" -> Box(
            Modifier.fillMaxWidth().height(1.dp)
                .background(ctx.resolveToken(ColorToken.Outline)),
        )
        "ring" -> RingNode(node, ctx)
        "bar" -> BarNode(node, ctx)
        "list", "items", "group" -> {
            if (node.children.isNotEmpty()) {
                ChildColumn(node, ctx, gap, sizeModifier.fillMaxWidth())
            } else {
                val items = p["items"]
                if (items is List<*>) {
                    Column(
                        Modifier.fillMaxWidth().then(sizeModifier),
                        verticalArrangement = Arrangement.spacedBy(gap.dp),
                    ) {
                        items.forEach { item ->
                            val s = when (item) {
                                is Map<*, *> -> (item["text"] ?: item["label"] ?: item["title"] ?: "").toString()
                                else -> item?.toString() ?: ""
                            }
                            if (s.isNotEmpty()) {
                                Text(
                                    s,
                                    fontSize = CardExtractors.num(p, "fontSize", node.style.fontSizeSp).sp,
                                    color = textColor(p["color"], node.style.fg, ctx),
                                )
                            }
                        }
                    }
                }
            }
        }
        // 未知节点兜底：带文字就画出来，绝不让内容凭空消失（旧版返回 0 尺寸 → 整块区域消失）。
        else -> {
            val t = p["text"]?.toString() ?: p["label"]?.toString() ?: ""
            if (t.isNotEmpty()) {
                Text(
                    t,
                    fontSize = CardExtractors.num(p, "fontSize", node.style.fontSizeSp).sp,
                    color = textColor(p["color"], node.style.fg, ctx),
                )
            } else if (node.children.isNotEmpty()) {
                ChildColumn(node, ctx, gap, sizeModifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ChildColumn(node: LayoutNode, ctx: CustomRenderContext, gap: Float, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(gap.dp)) {
        node.children.forEach { NodeView(it, ctx) }
    }
}

@Composable
private fun TextNode(node: LayoutNode, ctx: CustomRenderContext, modifier: Modifier) {
    val p = node.props
    val fs = CardExtractors.num(p, "fontSize", node.style.fontSizeSp)
    val weight = when {
        p.containsKey("fontWeight") -> CardExtractors.num(p, "fontWeight", 400f).toInt()
        (p["bold"] as? Boolean) == true -> 700
        else -> node.style.fontWeight
    }
    val align = when (p["align"]?.toString()?.lowercase()) {
        "center" -> TextAlign.Center
        "right", "end" -> TextAlign.End
        else -> TextAlign.Start
    }
    val maxLines = (p["maxLines"] as? Number)?.toInt() ?: Int.MAX_VALUE
    val lineHeight = (p["lineHeight"] as? Number)?.toFloat()
    Text(
        displayText(node, ctx),
        fontSize = fs.sp,
        color = textColor(p["color"], node.style.fg, ctx),
        fontWeight = FontWeight(weight.coerceIn(1, 1000)),
        fontStyle = if ((p["italic"] as? Boolean) == true) FontStyle.Italic else FontStyle.Normal,
        textAlign = align,
        maxLines = maxLines,
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
        lineHeight = if (lineHeight != null) lineHeight.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
        modifier = modifier,
    )
}

@Composable
private fun BadgeNode(node: LayoutNode, ctx: CustomRenderContext) {
    val p = node.props
    val fs = CardExtractors.num(p, "fontSize", 11f)
    val t = p["text"]?.toString() ?: p["label"]?.toString() ?: ""
    val hp = CardExtractors.num(p, "paddingX", 8f)
    val vp = CardExtractors.num(p, "paddingY", 4f)
    val bgc = CardExtractors.parseColor(p["bg"], ctx.resolveToken) ?: ctx.resolveToken(ColorToken.Primary)
    val fg = CardExtractors.parseColor(p["color"], ctx.resolveToken) ?: ctx.resolveToken(ColorToken.OnPrimary)
    Box(
        Modifier.background(bgc, RoundedCornerShape(CardExtractors.num(p, "radius", 999f).dp))
            .padding(horizontal = hp.dp, vertical = vp.dp),
    ) {
        Text(t, fontSize = fs.sp, color = fg, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun ButtonNode(node: LayoutNode, ctx: CustomRenderContext, sizeModifier: Modifier) {
    val p = node.props
    val fs = CardExtractors.num(p, "fontSize", 14f)
    val t = p["text"]?.toString() ?: p["label"]?.toString() ?: ""
    val bgc = CardExtractors.parseColor(p["bg"], ctx.resolveToken) ?: ctx.resolveToken(ColorToken.Primary)
    val fg = CardExtractors.parseColor(p["color"], ctx.resolveToken) ?: ctx.resolveToken(ColorToken.OnPrimary)
    // 按出现顺序映射到 spec.actions（自定义按钮也能触发动作，旧版完全不响应）
    val idx = (p["actionIndex"] as? Number)?.toInt()
        ?: run { val i = ctx.buttonSeq[0]; ctx.buttonSeq[0] = i + 1; i }
    val action = ctx.spec.actions.getOrNull(idx)
    Box(
        Modifier
            .then(if (node.widthDp != null) Modifier.width(node.widthDp!!.dp) else Modifier.fillMaxWidth())
            .clip(RoundedCornerShape(CardExtractors.num(p, "radius", 10f).dp))
            .background(bgc)
            .then(if (action != null) Modifier.clickable { ctx.onAction(action) } else Modifier)
            .padding(vertical = CardExtractors.num(p, "paddingY", 10f).dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            t,
            fontSize = fs.sp,
            color = fg,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun IconNode(node: LayoutNode, ctx: CustomRenderContext) {
    val p = node.props
    val s = CardExtractors.num(p, "size", 24f)
    val bgc = CardExtractors.parseColor(p["bg"], ctx.resolveToken) ?: ctx.resolveToken(ColorToken.SurfaceVariant)
    val radius = if (node.type == "avatar") s / 2 else CardExtractors.num(p, "radius", 6f)
    val t = p["text"]?.toString() ?: p["emoji"]?.toString() ?: ""
    Box(
        Modifier.size(s.dp).background(bgc, RoundedCornerShape(radius.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (t.isNotEmpty()) Text(t, fontSize = CardExtractors.num(p, "fontSize", 14f).sp)
    }
}

/** 图片节点：优先 `url`/`src`/`image` 走 Coil 真显示；没有地址时退化为灰底占位（不塌成 0 高）。 */
@Composable
private fun ImageNode(node: LayoutNode, ctx: CustomRenderContext, sizeModifier: Modifier) {
    val p = node.props
    val h = CardExtractors.num(p, "h", CardExtractors.num(p, "height", 120f))
    val radius = CardExtractors.num(p, "radius", 10f)
    val url = (p["url"] ?: p["src"] ?: p["image"] ?: p["uri"])?.toString()?.takeIf { it.startsWith("http") }
    val shape = RoundedCornerShape(radius.dp)
    if (url != null) {
        AsyncImage(
            model = url,
            contentDescription = p["alt"]?.toString() ?: p["text"]?.toString(),
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .then(sizeModifier)
                .height(h.dp)
                .clip(shape)
                .background(ctx.resolveToken(ColorToken.SurfaceVariant)),
        )
    } else {
        Box(
            Modifier
                .fillMaxWidth()
                .then(sizeModifier)
                .height(h.dp)
                .clip(shape)
                .background(ctx.resolveToken(ColorToken.SurfaceVariant)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                p["alt"]?.toString() ?: p["text"]?.toString() ?: "图片",
                fontSize = 12.sp,
                color = ctx.resolveToken(ColorToken.OnSurfaceVariant),
            )
        }
    }
}

@Composable
private fun RingNode(node: LayoutNode, ctx: CustomRenderContext) {
    val p = node.props
    val side = CardExtractors.num(p, "size", 90f)
    val stroke = CardExtractors.num(p, "stroke", 8f)
    // 🔴 0~100 与 0~1 两种写法都归一（旧版只认 0~100，写 0.86 就几乎不画弧）
    val ratio = CardExtractors.percent(p)
    val col = CardExtractors.parseColor(p["color"], ctx.resolveToken) ?: ctx.resolveToken(ColorToken.Primary)
    val track = CardExtractors.parseColor(p["track"], ctx.resolveToken)
        ?: ctx.resolveToken(ColorToken.Outline).copy(alpha = 0.45f)
    val ct = p["text"]?.toString() ?: "${(ratio * 100).toInt()}%"
    val label = p["label"]?.toString()
    val inner = (side - stroke).coerceAtLeast(1f)
    val sweepTarget = 360f * ratio * easeOutCubic(ctx.phase)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(side.dp), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Canvas(Modifier.size(side.dp)) {
                val sw = stroke.dp.toPx()
                val topLeft = Offset(sw / 2f, sw / 2f)
                val arcSize = Size(inner.dp.toPx(), inner.dp.toPx())
                drawArc(
                    track, startAngle = 0f, sweepAngle = 360f,
                    useCenter = false, style = androidx.compose.ui.graphics.drawscope.Stroke(sw),
                    topLeft = topLeft, size = arcSize,
                )
                if (sweepTarget > 0.5f) {
                    drawArc(
                        col, startAngle = -90f, sweepAngle = sweepTarget,
                        useCenter = false, style = androidx.compose.ui.graphics.drawscope.Stroke(sw),
                        topLeft = topLeft, size = arcSize,
                    )
                }
            }
            Text(
                ct,
                fontSize = CardExtractors.num(p, "textSize", 20f).sp,
                fontWeight = FontWeight.Bold,
                color = textColor(p["textColor"], node.style.fg, ctx),
                maxLines = 1,
            )
        }
        if (label != null && label.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                label,
                fontSize = 11.sp,
                color = ctx.resolveToken(ColorToken.OnSurfaceVariant),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun BarNode(node: LayoutNode, ctx: CustomRenderContext) {
    val p = node.props
    val h = CardExtractors.num(p, "h", 10f)
    // 同样两套写法归一：0.6 与 60 都当 60%
    val ratio = CardExtractors.percent(p)
    val radius = CardExtractors.num(p, "radius", 6f)
    val track = CardExtractors.parseColor(p["track"], ctx.resolveToken)
        ?: ctx.resolveToken(ColorToken.SurfaceVariant)
    val grad = CardExtractors.extractGradientColors(p["gradient"], ctx.resolveToken)
    val col = grad?.firstOrNull()
        ?: CardExtractors.parseColor(p["color"], ctx.resolveToken)
        ?: ctx.resolveToken(ColorToken.Primary)
    val fill = ratio * easeOutCubic(ctx.phase)
    val label = p["label"]?.toString()
    val showValue = (p["showValue"] as? Boolean) ?: (p["showPercent"] as? Boolean) ?: false
    Column(Modifier.fillMaxWidth()) {
        if (label != null || showValue) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (label != null) {
                    Text(
                        label,
                        fontSize = 11.sp,
                        color = ctx.resolveToken(ColorToken.OnSurfaceVariant),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (showValue) {
                    Text(
                        "${(fill * 100).toInt()}%",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = ctx.resolveToken(ColorToken.OnSurface),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        Box(
            Modifier.fillMaxWidth().height(h.dp)
                .clip(RoundedCornerShape(radius.dp))
                .background(track, RoundedCornerShape(radius.dp)),
        ) {
            Box(
                Modifier.fillMaxWidth(fill.coerceIn(0f, 1f)).height(h.dp).then(
                    if (grad != null) Modifier.background(Brush.linearGradient(grad), RoundedCornerShape(radius.dp))
                    else Modifier.background(col, RoundedCornerShape(radius.dp)),
                ),
            )
        }
    }
}

// ───────────────────── 私有工具 ─────────────────────

/** 容器背景：渐变优先（Brush），否则单色 / 主题 token。 */
private fun nodeBackground(node: LayoutNode, ctx: CustomRenderContext): Any {
    val p = node.props
    val grad = CardExtractors.extractGradientColors(p["gradient"], ctx.resolveToken)
    if (grad != null) return angleBrush(grad, CardExtractors.num(p, "angle", 135f))
    return CardExtractors.parseColor(p["bg"], ctx.resolveToken) ?: ctx.resolveToken(node.style.bg)
}

/** 渐变方向 Brush（按角度），默认 135° 左上→右下。 */
private fun angleBrush(colors: List<Color>, angleDeg: Float): Brush {
    val rad = Math.toRadians(angleDeg.toDouble()).toFloat()
    val dx = cos(rad)
    val dy = sin(rad)
    return Brush.linearGradient(
        colors,
        start = Offset(0.5f - dx * 0.5f, 0.5f - dy * 0.5f),
        end = Offset(0.5f + dx * 0.5f, 0.5f + dy * 0.5f),
    )
}

private fun textColor(any: Any?, token: ColorToken, ctx: CustomRenderContext): Color =
    CardExtractors.parseColor(any, ctx.resolveToken) ?: ctx.resolveToken(token)

/** 文字显示内容：countTo 数字按入场相位滚动；否则静态 text。 */
private fun displayText(node: LayoutNode, ctx: CustomRenderContext): String {
    val p = node.props
    val ct = p["countTo"] as? Number
    if (ct != null) {
        val v = ct.toFloat() * easeOutCubic(ctx.phase)
        val pre = p["prefix"]?.toString() ?: ""
        val suf = p["suffix"]?.toString() ?: ""
        val body = if (ct.toFloat() == ct.toLong().toFloat()) "%,d".format(v.toLong()) else String.format("%.1f", v)
        return pre + body + suf
    }
    return p["text"]?.toString() ?: ""
}

private fun easeOutCubic(p: Float): Float {
    val q = 1f - p.coerceIn(0f, 1f)
    return 1f - q * q * q
}
