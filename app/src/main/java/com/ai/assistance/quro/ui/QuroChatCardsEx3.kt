package com.ai.assistance.quro.ui

import android.widget.Toast
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.core.cards.ConfirmCard
import com.ai.assistance.quro.core.cards.DecisionCard
import com.ai.assistance.quro.core.cards.FeedCard
import com.ai.assistance.quro.core.cards.FunnelCard
import com.ai.assistance.quro.core.cards.GraphCard
import com.ai.assistance.quro.core.cards.MatrixCard
import com.ai.assistance.quro.core.cards.QuadrantCard
import com.ai.assistance.quro.core.cards.SankeyCard
import com.ai.assistance.quro.core.cards.SectionCard
import com.ai.assistance.quro.core.cards.WaterfallCard
import kotlin.math.abs
import kotlin.math.max

/**
 * 可视化组件渲染层 —— 第三批（AI 征询决策 + 可视化进阶）。
 *
 * 复用第二批的 [ExCardPalette] 与 [exCopy]，不另起一套配色与剪贴板胶水。
 *
 * ## 诚实渲染原则（与前两批一致）
 *
 * 没有图形库/物理引擎就不硬凑：
 *  - **sankey**：不做真实贝塞尔曲线排布，按 [SankeyCard.Node.col] 的声明顺序分层、
 *    带宽按 value 比例线性映射 —— 结果可预期，模型能反推；
 *  - **graph**：不做力导向，按节点显式网格坐标摆放，缺坐标按出现顺序顺排；
 *  - 没有条码/KaTeX 库的一律原样显示或占位，绝不画「看起来像」的假图形骗人。
 */

// ═══════════════ 一、AI 征询决策 ═══════════════

/**
 * 决策征询卡。
 *
 * 「其它 → 自由输入」出口用 [ExCardPalette.INFO] 标出，视觉上区别于 AI 给的候选项 ——
 * 让用户一眼看出「我还能自己说」，而不是被圈在 AI 的选项里。
 */
@Composable
fun DecisionCardView(card: DecisionCard, onCommand: ((String) -> Unit)?) {
    val ctx = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Text(
            card.question.ifBlank { card.title },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
        )
        if (card.context.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                card.context,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(10.dp))
        card.options.forEach { opt ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (opt.recommended) ExCardPalette.INFO.copy(alpha = 0.10f)
                        else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
                    )
                    .border(
                        if (opt.recommended) 1.dp else 0.5.dp,
                        if (opt.recommended) ExCardPalette.INFO.copy(alpha = 0.5f)
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.18f),
                        RoundedCornerShape(10.dp),
                    )
                    .clickable { onCommand?.invoke("reply:${opt.value.ifBlank { opt.label }}") }
                    .padding(horizontal = 11.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            opt.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (opt.recommended) FontWeight.Medium else FontWeight.Normal,
                        )
                        if (opt.recommended) {
                            Spacer(Modifier.width(6.dp))
                            B3Tag("推荐", ExCardPalette.INFO)
                        }
                    }
                    if (opt.detail.isNotBlank()) {
                        Text(
                            opt.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (card.allowCustom) {
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, ExCardPalette.INFO.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                    .clickable {
                        // 自由输入：把提示文案回给 AI，由 AI 追问或转自由文本输入
                        onCommand?.invoke("ai:${card.customHint.ifBlank { "我另有一个选择" }}")
                    }
                    .padding(horizontal = 11.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    card.customHint.ifBlank { "或者直接输入你的选择" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = ExCardPalette.INFO,
                )
            }
        }
        if (!card.required) {
            Spacer(Modifier.height(4.dp))
            Text(
                "可跳过",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { onCommand?.invoke("reply:skip") },
            )
        }
    }
}

/**
 * 确认卡。
 *
 * 危险操作（[ConfirmCard.danger]）用红并把「取消」放在**左边**（列表阅读顺序里
 * 第一个焦点落在安全选项上，减少误触）。
 */
@Composable
fun ConfirmCardView(card: ConfirmCard, onCommand: ((String) -> Unit)?) {
    val accent = if (card.danger) ExCardPalette.ERROR else MaterialTheme.colorScheme.primary
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Text(
            card.message.ifBlank { card.title },
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        if (card.detail.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(accent.copy(alpha = 0.08f))
                    .padding(9.dp),
            ) {
                Text(
                    card.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // 取消在前：默认焦点落在安全选项上
            B3Button(
                text = card.cancelLabel.ifBlank { "取消" },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            ) { onCommand?.invoke("reply:cancel") }
            B3Button(
                text = card.confirmLabel.ifBlank { "确认" },
                color = accent,
                filled = true,
                modifier = Modifier.weight(1f),
            ) { onCommand?.invoke("reply:confirm") }
        }
    }
}

// ═══════════════ 二、数据可视化进阶 ═══════════════

/**
 * 桑基图。
 *
 * 不做贝塞尔曲线排布（那需要真实布局引擎）：按节点声明顺序**分层**，
 * 每层内节点竖向平铺，带宽按 value 线性映射 —— 结果确定可预期。
 * 边用两条竖向色带的简单连线表示流向，宽度 ∝ value。
 */
@Composable
fun SankeyCardView(card: SankeyCard) {
    val labels = card.nodes.associate { it.id to it.label.ifBlank { it.id } }
    if (card.nodes.isEmpty() || card.links.isEmpty()) {
        B3Empty("桑基图缺少节点或连线")
        return
    }
    val maxVal = card.links.maxOf { abs(it.value) }.coerceAtLeast(0.0001)
    // Canvas 的 lambda 是 DrawScope（不是 @Composable），颜色必须在外面取好再传进去
    val nodeColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
    val linkColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)

    // 分层：每个节点挂在它出现过的最深层
    val depth = LinkedHashMap<String, Int>()
    card.links.forEach { l ->
        val df = (depth[l.from] ?: 0)
        depth[l.to] = max(depth[l.to] ?: 0, df + 1)
    }
    val layers = card.nodes.groupBy { depth[it.id] ?: 0 }.toSortedMap()
    val layerCount = max(layers.size, 1)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f))
            .padding(14.dp),
    ) {
        if (card.title.isNotBlank()) B3Title(card.title)
        Spacer(Modifier.height(10.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height((layerCount * 46 + 24).dp),
        ) {
            val w = size.width
            val rowH = size.height / layerCount
            // 节点位置
            val pos = HashMap<String, Offset>()
            layers.forEach { (d, nodes) ->
                val stepH = rowH / (nodes.size + 1)
                nodes.forEachIndexed { i, n ->
                    val x = if (layerCount == 1) w * 0.5f else w * (d.toFloat() / (layerCount - 1).toFloat())
                    val y = stepH * (i + 1)
                    val bw = max(6.0, abs(card.links.firstOrNull { it.from == n.id || it.to == n.id }?.value ?: maxVal) / maxVal * 22.0).toFloat()
                    pos[n.id] = Offset(x, y)
                    drawRoundRect(
                        color = nodeColor,
                        topLeft = Offset(x - bw / 2, y - 6f),
                        size = Size(bw, 12f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f),
                    )
                }
            }
            // 连线：起点 → 终点，宽度 ∝ value
            card.links.forEach { l ->
                val a = pos[l.from] ?: return@forEach
                val b = pos[l.to] ?: return@forEach
                val lw = max(1.5, abs(l.value) / maxVal * 14.0).toFloat()
                drawLine(
                    color = linkColor,
                    start = a, end = b, strokeWidth = lw, cap = StrokeCap.Round,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        // 图例：按层列出节点，避免画布上文字重叠
        layers.forEach { (d, nodes) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
            ) {
                Text(
                    "第 ${d + 1} 层",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(52.dp),
                )
                Text(
                    nodes.joinToString(" · ") { labels[it.id].orEmpty() },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (card.unit.isNotBlank()) {
            Text(
                "单位：${card.unit}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 漏斗：每级一个居中的梯形条 + 右侧留存率。 */
@Composable
fun FunnelCardView(card: FunnelCard) {
    if (card.steps.isEmpty()) {
        B3Empty("漏斗没有数据")
        return
    }
    val top = card.steps.first().value
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f))
            .padding(14.dp),
    ) {
        if (card.title.isNotBlank()) B3Title(card.title)
        Spacer(Modifier.height(10.dp))
        card.steps.forEachIndexed { i, st ->
            val ratio = if (top > 0) (st.value / top).toFloat().coerceIn(0f, 1f) else 0f
            val drop = if (i > 0) {
                val prev = card.steps[i - 1].value
                if (prev > 0) ((prev - st.value) / prev * 100).toInt() else 0
            } else -1
            Column(Modifier.padding(vertical = 3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        st.name,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        buildString {
                            append(if (st.value % 1.0 == 0.0) st.value.toLong().toString() else st.value.toString())
                            if (card.unit.isNotBlank()) append(" ${card.unit}")
                            if (card.showRate && top > 0) append(" · ${(ratio * 100).toInt()}%")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(3.dp))
                Box(
                    Modifier
                        .fillMaxWidth(ratio.coerceAtLeast(0.02f))
                        .height(18.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f - i * 0.04f)),
                )
                if (drop > 0) {
                    Text(
                        "↓ 流失 $drop%${if (st.hint.isNotBlank()) " · ${st.hint}" else ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = ExCardPalette.WARNING,
                    )
                } else if (st.hint.isNotBlank()) {
                    Text(st.hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** 瀑布图：每根柱按累加位置浮动，增量为负向下。 */
@Composable
fun WaterfallCardView(card: WaterfallCard) {
    if (card.steps.isEmpty()) {
        B3Empty("瀑布图没有数据")
        return
    }
    // 先算出每根柱的起止值
    val bars = ArrayList<Triple<Double, Double, WaterfallCard.Step>>()
    var acc = card.start ?: card.steps.firstOrNull { it.isTotal }?.value ?: 0.0
    card.steps.forEach { st ->
        if (st.isTotal) {
            bars.add(Triple(0.0, st.value, st))
            acc = st.value
        } else {
            bars.add(Triple(acc, acc + st.delta, st))
            acc += st.delta
        }
    }
    val lo = bars.minOf { minOf(it.first, it.second) }
    val hi = bars.maxOf { maxOf(it.first, it.second) }
    val span = (hi - lo).coerceAtLeast(0.0001)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f))
            .padding(14.dp),
    ) {
        if (card.title.isNotBlank()) B3Title(card.title)
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .height(120.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            bars.forEach { (from, to, st) ->
                val isTotal = st.isTotal
                val topFrac = ((hi - max(from, to)) / span).toFloat().coerceIn(0f, 1f)
                val heightFrac = (abs(to - from) / span).toFloat().coerceIn(0.02f, 1f)
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        when {
                            isTotal -> B3Num(st.value)
                            st.delta >= 0 -> "+" + B3Num(st.delta)
                            else -> B3Num(st.delta)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            isTotal -> MaterialTheme.colorScheme.primary
                            st.delta >= 0 -> ExCardPalette.SUCCESS
                            else -> ExCardPalette.ERROR
                        },
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(2.dp))
                    // 柱体：起点由 topFrac 决定，浮动而非落地 —— 这就是瀑布与柱状图的区别
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .weight(heightFrac, fill = true)
                            .padding(top = ((1f - topFrac) * 44).dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                when {
                                    isTotal -> MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
                                    st.delta >= 0 -> ExCardPalette.SUCCESS.copy(alpha = 0.6f)
                                    else -> ExCardPalette.ERROR.copy(alpha = 0.6f)
                                }
                            ),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        bars.forEach { (_, to, st) ->
            Text(
                "${st.label} · ${B3Num(to)}${if (card.unit.isNotBlank()) " ${card.unit}" else ""}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 四象限：四条分割线 + 象限名 + 点按 (x, y) 定位。 */
@Composable
fun QuadrantCardView(card: QuadrantCard) {
    if (card.items.isEmpty()) {
        B3Empty("四象限没有数据点")
        return
    }
    val maxA = card.axisMax.coerceAtLeast(0.0001)
    // 同上：Canvas 外取色
    val hiFill = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
    val axisLine = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val dotColor = MaterialTheme.colorScheme.primary
    // 象限名顺序：左下 → 左上 → 右上 → 右下（与坐标系一致，避免「象限 1/2/3/4」记不清）
    val qn = card.quadrants
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f))
            .padding(14.dp),
    ) {
        if (card.title.isNotBlank()) B3Title(card.title)
        Spacer(Modifier.height(10.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(150.dp),
        ) {
            val w = size.width
            val h = size.height
            // 象限底色：右上（高价值）稍深
            drawRect(color = hiFill, topLeft = Offset(w / 2, 0f), size = Size(w / 2, h / 2))
            drawLine(color = axisLine, start = Offset(w / 2, 0f), end = Offset(w / 2, h), strokeWidth = 1f)
            drawLine(color = axisLine, start = Offset(0f, h / 2), end = Offset(w, h / 2), strokeWidth = 1f)
            card.items.forEach { it ->
                val x = (it.x / maxA).toFloat().coerceIn(0f, 1f) * w
                // Canvas y 轴向下，而象限语义是 y 向上，故取反
                val y = (1f - (it.y / maxA).toFloat().coerceIn(0f, 1f)) * h
                drawCircle(color = dotColor, radius = 5f, center = Offset(x, y))
            }
        }
        Spacer(Modifier.height(8.dp))
        if (qn.size >= 4) {
            Row(Modifier.fillMaxWidth()) {
                Text(qn[0], style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(qn[1], style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth()) {
                Text(qn[2], style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                Text(qn[3], style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(6.dp))
        card.items.forEach { it ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    it.label + if (it.tag.isNotBlank()) " · ${it.tag}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "(${B3Num(it.x)}, ${B3Num(it.y)})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (card.xLabel.isNotBlank() || card.yLabel.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "横轴 ${card.xLabel}（0..${B3Num(card.axisMax)}） · 纵轴 ${card.yLabel}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 逐维对照：每行指标 + 左右值 + 差值着色（按 better 判优劣）。 */
@Composable
fun MatrixCardView(card: MatrixCard) {
    if (card.rows.isEmpty()) {
        B3Empty("对照表没有行")
        return
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f))
            .padding(14.dp),
    ) {
        if (card.title.isNotBlank()) B3Title(card.title)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1.3f),
            )
            Text(
                card.leftLabel,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
            Text(
                card.rightLabel,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        card.rows.forEach { r ->
            val diff = B3NumDiff(r.left, r.right)
            val winner = when {
                diff == null -> 0
                r.better == null -> 0
                diff > 0 && r.better == "high" -> 1
                diff < 0 && r.better == "high" -> 2
                diff < 0 && r.better == "low" -> 1
                else -> 2
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    r.label,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1.3f),
                )
                Text(
                    r.left,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (winner == 1) FontWeight.Medium else FontWeight.Normal,
                    color = if (winner == 1) ExCardPalette.SUCCESS else MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    r.right,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (winner == 2) FontWeight.Medium else FontWeight.Normal,
                    color = if (winner == 2) ExCardPalette.SUCCESS else MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (card.showDiff && card.rows.any { it.better != null }) {
            Spacer(Modifier.height(4.dp))
            Text(
                "绿色为更优项（按每行的 better 判定）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 事件流水：时间轴 + 彩色圆点（level 决定颜色）。 */
@Composable
fun FeedCardView(card: FeedCard) {
    if (card.items.isEmpty()) {
        B3Empty("没有事件")
        return
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                card.title.ifBlank { "事件流" },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            if (card.source.isNotBlank()) {
                Text(
                    card.source,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        card.items.forEachIndexed { i, it ->
            val dot = when (it.level.lowercase()) {
                "success" -> ExCardPalette.SUCCESS
                "warning" -> ExCardPalette.WARNING
                "error" -> ExCardPalette.ERROR
                else -> ExCardPalette.INFO
            }
            Row(Modifier.fillMaxWidth()) {
                // 左侧时间列 + 竖线，形成时间轴
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(58.dp)) {
                    Text(
                        it.time,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.width(9.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(dot))
                    if (i < card.items.size - 1) {
                        Box(
                            Modifier
                                .width(1.dp)
                                .height(22.dp)
                                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                        )
                    }
                }
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f).padding(bottom = if (i < card.items.size - 1) 8.dp else 0.dp)) {
                    Text(it.text, style = MaterialTheme.typography.bodySmall)
                    if (it.actor.isNotBlank()) {
                        Text(
                            it.actor,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 关系图：按显式网格坐标摆放节点 + 直线连边。
 *
 * 不做力导向布局 —— 那需要物理引擎与手势，且同一份 JSON 每次渲染位置都不一样，
 * 无法预期也无法测试。模型给 col/row 即可，缺省按出现顺序顺排。
 */
@Composable
fun GraphCardView(card: GraphCard) {
    if (card.nodes.isEmpty()) {
        B3Empty("关系图没有节点")
        return
    }
    // 键是节点名（String），值是网格坐标；像素换算留到 Canvas 内做
    val pos = HashMap<String, Offset>()
    val colOf = HashMap<String, Int>()
    val rowOf = HashMap<String, Int>()
    card.nodes.forEachIndexed { i, n ->
        val c = if (n.col >= 0) n.col else (i % 3)
        val r = if (n.row >= 0) n.row else (i / 3)
        colOf[n.label] = c
        rowOf[n.label] = r
        pos[n.label] = Offset(c.toFloat(), r.toFloat())  // 网格坐标，真实像素在 Canvas 里换算
    }
    val maxCol = card.nodes.indices.maxOf { i ->
        val n = card.nodes[i]
        if (n.col >= 0) n.col else i % 3
    }.coerceAtLeast(1)
    val maxRow = card.nodes.indices.maxOf { i ->
        val n = card.nodes[i]
        if (n.row >= 0) n.row else i / 3
    }.coerceAtLeast(1)
    val edgeColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    val shapeColor = mapOf(
        "service" to MaterialTheme.colorScheme.primary,
        "db" to ExCardPalette.INFO,
        "queue" to ExCardPalette.WARNING,
    )
    val fallbackNodeColor = MaterialTheme.colorScheme.secondary
    val cellW = if (maxCol == 0) 100f else 100f / maxCol
    val cellH = if (maxRow == 0) 40f else 40f / maxRow

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f))
            .padding(14.dp),
    ) {
        if (card.title.isNotBlank()) B3Title(card.title)
        Spacer(Modifier.height(10.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(((maxRow + 1) * 44 + 16).dp),
        ) {
            val ox = size.width * 0.5f / (maxCol + 1).toFloat()
            val oy = size.height * 0.5f / (maxRow + 1).toFloat()
            fun pt(label: String): Offset {
                val p = pos[label] ?: return Offset(-100f, -100f)
                return Offset(ox + p.x * cellW, oy + p.y * cellH)
            }
            card.edges.forEach { e ->
                val a = pt(e.from)
                val b = pt(e.to)
                if (a.x < 0 || b.x < 0) return@forEach
                drawLine(
                    color = edgeColor,
                    start = a, end = b, strokeWidth = 1.2f,
                    pathEffect = when (e.kind.lowercase()) {
                        "dep" -> PathEffect.dashPathEffect(floatArrayOf(6f, 5f))
                        else -> null
                    },
                )
            }
            card.nodes.forEach { n ->
                val p = pt(n.label)
                if (p.x < 0) return@forEach
                val c = shapeColor[n.shape.lowercase()] ?: fallbackNodeColor
                drawRoundRect(
                    color = c.copy(alpha = 0.75f),
                    topLeft = Offset(p.x - 34f, p.y - 11f),
                    size = Size(68f, 22f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        card.nodes.forEach { n ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(9.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(shapeColor[n.shape.lowercase()] ?: fallbackNodeColor),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    n.label,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "(${colOf[n.label] ?: 0}, ${rowOf[n.label] ?: 0})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val labels = card.edges.filter { it.label.isNotBlank() }
        if (labels.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            HorizontalDivider()
            Spacer(Modifier.height(6.dp))
            labels.forEach { e ->
                Text(
                    "${e.from} → ${e.to}${if (e.label.isNotBlank()) " · ${e.label}" else ""}" +
                        if (e.kind.lowercase() == "dep") "（依赖）" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 分区明细：每组标题 + 组内键值行。 */
@Composable
fun SectionCardView(card: SectionCard) {
    if (card.sections.isEmpty()) {
        B3Empty("没有分区内容")
        return
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f))
            .padding(14.dp),
    ) {
        if (card.title.isNotBlank()) B3Title(card.title)
        card.sections.forEachIndexed { si, sec ->
            Spacer(Modifier.height(if (si == 0) 10.dp else 12.dp))
            Text(
                sec.title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            sec.rows.forEach { (k, v) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(
                        k,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        v,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1.4f),
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
    }
}

// ═══════════════ 第三批内部小工具 ═══════════════

/** 卡片小标题（第三批共用）。 */
@Composable
internal fun B3Title(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Medium,
    )
}

/** 空态占位：缺数据时说清缺什么，而不是画一张空白卡。 */
@Composable
internal fun B3Empty(hint: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 小标签（用于「推荐」等标记）。 */
@Composable
internal fun B3Tag(text: String, color: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, fontSize = 10.sp)
    }
}

/** 第三批通用按钮。 */
@Composable
internal fun B3Button(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (filled) color else color.copy(alpha = 0.10f))
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (filled) Color.White else color,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/** 数字格式化：整数值不带小数点，避免图表标签出现 "100.0"。 */
internal fun B3Num(v: Double): String =
    if (v % 1.0 == 0.0) v.toLong().toString() else String.format("%.2f", v)

/**
 * 解析两列的数值差；任一列非纯数字则返回 null（不硬算）。
 *
 * 返回 `right - left`：正数表示右边更大。
 */
internal fun B3NumDiff(left: String, right: String): Double? {
    val l = left.trim().toDoubleOrNull() ?: return null
    val r = right.trim().toDoubleOrNull() ?: return null
    return r - l
}