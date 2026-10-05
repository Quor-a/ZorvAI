

package com.ai.assistance.quro.ui

import android.content.ClipData
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ai.assistance.quro.R
import androidx.compose.runtime.DisposableEffect
import com.ai.assistance.quro.core.cards.AccordionCard
import com.ai.assistance.quro.core.cards.CardAction
import com.ai.assistance.quro.core.cards.CardActionBus
import com.ai.assistance.quro.core.cards.BoxPlotCard
import com.ai.assistance.quro.core.cards.CandlestickCard
import com.ai.assistance.quro.core.cards.CheckListCard
import com.ai.assistance.quro.core.cards.ContactCard
import com.ai.assistance.quro.core.cards.CustomCard
import com.ai.assistance.quro.core.cards.DiffCard
import com.ai.assistance.quro.core.cards.DividerCard
import com.ai.assistance.quro.core.cards.FileCard
import com.ai.assistance.quro.core.cards.FlowCard
import com.ai.assistance.quro.core.cards.FunnelCard
import com.ai.assistance.quro.core.cards.GalleryCard
import com.ai.assistance.quro.core.cards.GroupedListCard
import com.ai.assistance.quro.core.cards.HierarchyCard
import com.ai.assistance.quro.core.cards.KeyValueCard
import com.ai.assistance.quro.core.cards.LinkListCard
import com.ai.assistance.quro.core.cards.MapCard
import com.ai.assistance.quro.core.cards.PaginationCard
import com.ai.assistance.quro.core.cards.PollCard
import com.ai.assistance.quro.core.cards.ProductCard
import com.ai.assistance.quro.core.cards.QuoteCard
import com.ai.assistance.quro.core.cards.QrCodeCard
import com.ai.assistance.quro.core.cards.QuoteCard as QuoteCardKt
import com.ai.assistance.quro.core.cards.RingCard
import com.ai.assistance.quro.core.cards.ScheduleCard
import com.ai.assistance.quro.core.cards.ScatterCard
import com.ai.assistance.quro.core.cards.SearchBoxCard
import com.ai.assistance.quro.core.cards.SpeedometerCard
import com.ai.assistance.quro.core.cards.SpacerCard
import com.ai.assistance.quro.core.cards.SparklineCard
import com.ai.assistance.quro.core.cards.StackedBarCard
import com.ai.assistance.quro.core.cards.TerminalCard
import com.ai.assistance.quro.core.cards.TreeCard
import com.ai.assistance.quro.core.cards.WeatherCard
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.max
import kotlin.math.min

/**
 * 可视化组件 SDK v1400 —— 34 种增强组件的**渲染层**。
 *
 * ## 为什么单独一个文件
 *
 * 渲染分派（`QuroChatCardView` 里那个 exhaustive `when`）在 `QuroChatCards.kt`，
 * 这里只放 `@Composable` 实现。好处：
 *  - 那 2470 行的聊天卡片渲染文件不再被动，diff 一眼看得完；
 *  - 新组件编译报错时直接跳到本文件，定位快。
 *
 * 分派侧只要加一行 `is KeyValueCard -> KeyValueCardView(card, onCommand)`，
 * 漏了会**编译不过**（`when` 不穷尽）。这正是本 SDK 要的「漏一处就炸」，
 * 而不是「能解析但渲染成空白」这种静默失效。
 *
 * ## 渲染原则
 *
 *  - 颜色取 [MaterialTheme.colorScheme] / [ExCardPalette]，不硬编码死黑死白；
 *  - 图表一律 [Canvas] 手绘（项目没引图表库，为几张图多拖一个库不划算）；
 *  - 画不了的（真地图需要地图 SDK、二维码需要编码库）**画诚实的占位 + 可复制原文**，
 *    绝不画一张「看起来像地图/二维码」的假图骗用户；
 *  - 所有交互最终收口到 `onCommand`，与存量 47 种卡片共用同一条命令管道。
 */

// ═══════════════════ 调色板 ═══════════════════

/** 语义色。AI 下发的是 `#RRGGBB` 字符串，这里做兜底映射，解析不出来走语义色而非白字。 */
private object ExCardPalette {
    val SUCCESS = Color(0xFF7BE0A0)
    val WARNING = Color(0xFFFFB74D)
    val ERROR = Color(0xFFFF8A80)
    val INFO = Color(0xFF6CB6FF)

    fun color(s: String): Color {
        if (s.startsWith("#")) {
            runCatching {
                val hex = s.substring(1)
                val v = hex.toLong(16).toInt()
                return if (hex.length == 6) Color((0xFF shl 24) or v) else Color(v)
            }
        }
        return when (s.lowercase()) {
            "success", "ok", "green" -> SUCCESS
            "warning", "warn", "yellow" -> WARNING
            "error", "danger", "red" -> ERROR
            "info", "blue" -> INFO
            else -> Color(0xFF6CB6FF)
        }
    }
}

// ═══════════════════ 数据增强 ═══════════════════

/** 键值对列表：左键右值，右值超长省略号截断。 */
@Composable
internal fun KeyValueCardView(card: KeyValueCard, onCommand: (String) -> Unit) {
    CardShell(card.title) {
        card.rows.forEach { r ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !r.command.isNullOrBlank()) { r.command?.let { onCommand(it) } }
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    r.k,
                    Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    r.v,
                    Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.End,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 环形进度：中心给百分比，thickness 可画细环。 */
@Composable
internal fun RingCardView(card: RingCard, onCommand: (String) -> Unit) {
    val ratio = if (card.max <= 0f) 0f else (card.value / card.max).coerceIn(0f, 1f)
    val color = ExCardPalette.color(card.color)
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(96.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val pad = 6.dp.toPx()
                val sw = (card.thickness * size.minDimension).coerceIn(4f, size.minDimension - pad * 2)
                val topLeft = Offset(pad, pad)
                val sizeArg = Size(size.width - pad * 2, size.height - pad * 2)
                drawArc(color = cs.surfaceVariant, startAngle = -90f, sweepAngle = 360f, useCenter = false, style = Stroke(width = sw), topLeft = topLeft, size = sizeArg)
                if (ratio > 0f) {
                    drawArc(
                        brush = Brush.sweepGradient(listOf(color, color.copy(alpha = 0.55f))),
                        startAngle = -90f,
                        sweepAngle = 360f * ratio,
                        useCenter = false,
                        style = Stroke(width = sw),
                        topLeft = topLeft,
                        size = sizeArg,
                    )
                }
            }
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    if (card.caption.isNotBlank()) card.caption else "%.0f%%".format(ratio * 100f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = cs.onSurface,
                )
                if (card.value != card.max) {
                    Text(
                        "%.0f/%.0f".format(card.value, card.max),
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
        }
        if (card.label.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(card.label, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        }
    }
}

/** 堆叠柱状图：一分类一根柱，多系列按比例堆。 */
@Composable
internal fun StackedBarCardView(card: StackedBarCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    if (card.categories.isEmpty() || card.series.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            card.series.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(ExCardPalette.color(s.color)))
                    Spacer(Modifier.width(5.dp))
                    Text(s.name, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            card.categories.forEachIndexed { ci, c ->
                val totals = card.series.map { it.values.getOrElse(ci) { 0f } }
                val sum = totals.sum()
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                        Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.Bottom) {
                            card.series.forEachIndexed { i, s ->
                                val v = totals[i]
                                val frac = if (sum > 0f) v / sum else 0f
                                if (v > 0f) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth(0.72f)
                                            .height((frac * 90).dp)
                                            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                                            .background(ExCardPalette.color(s.color)),
                                    )
                                } else {
                                    Spacer(Modifier.height(0.dp))
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(c, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}

/** 散点图：手绘坐标系 + 轴标签。 */
@Composable
internal fun ScatterCardView(card: ScatterCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    if (card.points.isEmpty()) return
    val maxX = max(card.points.maxOf { it.x }, 1f)
    val maxY = max(card.points.maxOf { it.y }, 1f)
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(140.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                drawLine(cs.outlineVariant, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1f)
                drawLine(cs.outlineVariant, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 1f)
                val inset = 8.dp.toPx()
                card.points.forEach { p ->
                    val x = (p.x / maxX) * (size.width - inset * 2) + inset
                    val y = size.height - ((p.y / maxY) * (size.height - inset * 2)) - inset
                    drawCircle(cs.primary, radius = 4.dp.toPx(), center = Offset(x, y))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(card.xLabel, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            Text(card.yLabel, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
    }
}

/** 漏斗图：层宽按 value/max 收窄，层间画下箭头。 */
@Composable
internal fun FunnelCardView(card: FunnelCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    if (card.steps.isEmpty()) return
    val maxV = max(card.steps.maxOf { it.value }, 1f)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        card.steps.forEachIndexed { i, s ->
            val w = (s.value / maxV).coerceIn(0.18f, 1f)
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .fillMaxWidth(w)
                        .height(34.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(ExCardPalette.color(s.color).copy(alpha = 0.85f))
                        .clickable { onCommand("ai:${s.name}") },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${s.name} · %.0f".format(s.value),
                        style = MaterialTheme.typography.labelMedium,
                        color = cs.onSurface,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (i < card.steps.size - 1) {
                    Spacer(Modifier.height(2.dp))
                    Text("↓", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    Spacer(Modifier.height(2.dp))
                }
            }
        }
    }
}

/** K 线图：每根烛台画 open/high/low/close。 */
@Composable
internal fun CandlestickCardView(card: CandlestickCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    if (card.candles.isEmpty()) return
    val hs = card.candles.flatMap { listOf(it.h, it.l) }
    val hi = max(hs.maxOrNull() ?: 1f, 1f)
    val lo = hs.minOrNull() ?: 0f
    val span = max(hi - lo, 1f)
    Box(
        Modifier
            .fillMaxWidth()
            .height(160.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(cs.surfaceVariant.copy(alpha = 0.35f)),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val slot = size.width / card.candles.size
            val inset = 6.dp.toPx()
            card.candles.forEachIndexed { i, c ->
                val x = slot * i + slot / 2f
                val yOf = { v: Float -> size.height - ((v - lo) / span) * (size.height - inset * 2) - inset }
                val up = c.c >= c.o
                val col = if (up) ExCardPalette.SUCCESS else ExCardPalette.ERROR
                drawLine(col, Offset(x, yOf(c.h)), Offset(x, yOf(c.l)), strokeWidth = 1.5f)
                val top = min(yOf(c.o), yOf(c.c))
                val bottom = max(yOf(c.o), yOf(c.c))
                drawRect(col, topLeft = Offset(x - slot * 0.28f, top), size = Size(slot * 0.56f, max(bottom - top, 1.5f)))
            }
        }
        if (card.candles.last().t.isNotBlank()) {
            Text(
                card.candles.last().t,
                Modifier.padding(6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
            )
        }
    }
}

/** 箱线图：箱体 + 上下须 + 中位线。 */
@Composable
internal fun BoxPlotCardView(card: BoxPlotCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    if (card.groups.isEmpty()) return
    val all = card.groups.flatMap { listOf(it.min, it.max) }
    val hi = max(all.maxOrNull() ?: 1f, 1f)
    val lo = all.minOrNull() ?: 0f
    val span = max(hi - lo, 1f)
    Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        card.groups.forEach { g ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Canvas(Modifier.fillMaxSize()) {
                        val inset = 10.dp.toPx()
                        val yOf = { v: Float -> size.height - ((v - lo) / span) * (size.height - inset * 2) - inset }
                        drawLine(cs.outlineVariant, Offset(size.width / 2f, yOf(g.max)), Offset(size.width / 2f, yOf(g.min)), strokeWidth = 1f)
                        drawRect(
                            cs.primary.copy(alpha = 0.35f),
                            topLeft = Offset(size.width * 0.2f, yOf(g.q3)),
                            size = Size(size.width * 0.6f, max(yOf(g.q1) - yOf(g.q3), 1f)),
                        )
                        drawLine(cs.primary, Offset(size.width * 0.08f, yOf(g.min)), Offset(size.width * 0.92f, yOf(g.min)), strokeWidth = 1.5f)
                        drawLine(cs.primary, Offset(size.width * 0.08f, yOf(g.max)), Offset(size.width * 0.92f, yOf(g.max)), strokeWidth = 1.5f)
                        drawLine(cs.onSurface, Offset(0f, yOf(g.median)), Offset(size.width, yOf(g.median)), strokeWidth = 2f)
                    }
                }
                Text(g.name, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

/** 速度表：弧形轨道 + 指针，区间配色按 value 占比取。 */
@Composable
internal fun SpeedometerCardView(card: SpeedometerCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ratio = (card.value / max(card.max, 1f)).coerceIn(0f, 1f)
    val zoneIdx = min((ratio * 2).toInt(), 2)
    val zone = when (zoneIdx) {
        0 -> ExCardPalette.SUCCESS
        1 -> ExCardPalette.WARNING
        else -> ExCardPalette.ERROR
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(140.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val pad = 12.dp.toPx()
                val t = Offset(pad, pad)
                val s = Size(size.width - pad * 2, size.height - pad * 2)
                drawArc(color = cs.surfaceVariant, startAngle = 135f, sweepAngle = 270f, useCenter = false, style = Stroke(width = 10.dp.toPx()), topLeft = t, size = s)
                if (ratio > 0f) drawArc(color = zone, startAngle = 135f, sweepAngle = 270f * ratio, useCenter = false, style = Stroke(width = 10.dp.toPx()), topLeft = t, size = s)
                val rad = Math.toRadians((135 + 270 * ratio).toDouble())
                val cx = size.width / 2f
                val cy = size.height / 2f + size.height * 0.12f
                val r = size.width * 0.36f
                drawLine(cs.onSurface, Offset(cx, cy), Offset(cx + cos(rad).toFloat() * r, cy + sin(rad).toFloat() * r), strokeWidth = 3.dp.toPx())
                drawCircle(cs.onSurface, 4.dp.toPx(), Offset(cx, cy))
            }
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (card.unit.isNotBlank()) "%.0f%s".format(card.value, card.unit) else "%.0f".format(card.value),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = cs.onSurface,
                )
                if (card.label.isNotBlank()) {
                    Text(card.label, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                }
            }
        }
    }
}

/** 迷你趋势线：只给趋势，不画坐标轴。 */
@Composable
internal fun SparklineCardView(card: SparklineCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    if (card.values.size < 2) return
    val lo = card.values.minOrNull() ?: 0f
    val hi = max(card.values.maxOrNull() ?: 1f, lo + 1f)
    val span = max(hi - lo, 1f)
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(48.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val yOf = { v: Float -> size.height - ((v - lo) / span) * size.height }
                drawLine(
                    ExCardPalette.color(card.color),
                    Offset(0f, yOf(card.values.first())),
                    Offset(size.width, yOf(card.values.last())),
                    strokeWidth = 2.dp.toPx(),
                )
            }
        }
        if (card.caption.isNotBlank()) {
            Text(card.caption, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
    }
}

// ═══════════════════ 输入增强 ═══════════════════

/** 搜索框：回车把输入内容拼进 command 回传；空内容不触发，避免空查询。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchBoxCardView(card: SearchBoxCard, onCommand: (String) -> Unit) {
    var text by remember { mutableStateOf(card.value) }
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(card.label, style = MaterialTheme.typography.labelSmall) },
            placeholder = {
                Text(card.placeholder, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            },
            keyboardOptions = KeyboardOptions.Default,
            keyboardActions = KeyboardActions(onDone = {
                if (text.isNotBlank()) onCommand(if (card.command.isBlank()) "ai:" else card.command + text)
            }),
        )
        if (card.hint.isNotBlank()) {
            Text(card.hint, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
    }
}

/**
 * 投票卡：选项条 + 百分比 + 总数。
 *
 * 点选项是**本地投票**（当帧就高亮 + 重算百分比），同 id 的兄弟卡通过
 * [CardActionBus] 一起更新；只有当卡片自带 `command` 时才顺带回传给宿主
 * —— 否则 `poll:<i>` 这种串宿主从来没分支接，点了等于石沉大海。
 */
@Composable
internal fun PollCardView(card: PollCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var voted by remember(card.id) { mutableStateOf(-1) }
    DisposableEffect(card.id) {
        val off = CardActionBus.subscribe(card.id) { a ->
            parseCardTogglePayload(a.arg("data"))?.let { (i, _) -> voted = i }
            true
        }
        onDispose { off() }
    }
    val total = if (card.total > 0) card.total else card.options.sumOf { it.count }
    val votedTotal = if (voted >= 0) total + 1 else total
    Column(Modifier.fillMaxWidth()) {
        if (card.question.isNotBlank()) {
            Text(card.question, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
            Spacer(Modifier.height(6.dp))
        }
        card.options.forEachIndexed { i, o ->
            val mine = voted == i
            val count = o.count + (if (mine) 1 else 0)
            val pct = if (votedTotal <= 0) 0f else count / votedTotal.toFloat()
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        voted = i
                        if (card.command.isNotBlank()) onCommand(card.command + ":$i")
                    }
                    .padding(vertical = 3.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (if (mine) "OK " else "") + o.label,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (mine) cs.primary else cs.onSurface,
                    )
                    Text(
                        if (votedTotal > 0) "%.0f%%".format(pct * 100f) else o.count.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(3.dp))
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(cs.surfaceVariant)) {
                    Box(Modifier.fillMaxWidth(pct).fillMaxSize().clip(RoundedCornerShape(3.dp)).background(ExCardPalette.color(o.color)))
                }
            }
        }
        if (total > 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                "$votedTotal 人参与${if (card.multi) " · 多选" else ""}",
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
            )
        }
    }
}

/** 清单：勾选 + 说明 + 完成度。勾选项是**本地状态**，同 id 的兄弟卡一起跟着变。 */
@Composable
internal fun CheckListCardView(card: CheckListCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var checked by remember(card.id) { mutableStateOf(card.items.map { it.done }) }

    // 同 id 联动：AI 常把同一份清单下发两次（待办 / 已完成），勾一张另一张要跟着走。
    // 以前 `card.id:toggle:i` 扔给宿主，宿主没有分支 —— 点了完全没反应。
    DisposableEffect(card.id) {
        val off = CardActionBus.subscribe(card.id) { a ->
            parseCardTogglePayload(a.arg("data"))?.let { (i, v) ->
                if (i in checked.indices) checked = checked.toMutableList().also { it[i] = v != 0 }
            }
            true
        }
        onDispose { off() }
    }

    fun toggle(i: Int) {
        val v = !checked.getOrElse(i) { false }
        checked = checked.toMutableList().also { it[i] = v }
        emitCardToggle(card.id, i, if (v) 1 else 0, onCommand)
    }

    val done = checked.count { it }
    Column(Modifier.fillMaxWidth()) {
        card.items.forEachIndexed { i, it ->
            val isDone = if (i < checked.size) checked[i] else card.items[i].done
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { toggle(i) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = isDone, onCheckedChange = { toggle(i) })
                Column(Modifier.weight(1f)) {
                    Text(
                        it.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isDone) cs.onSurfaceVariant else cs.onSurface,
                        textDecoration = if (isDone) TextDecoration.LineThrough else null,
                    )
                    if (it.note.isNotBlank()) {
                        Text(it.note, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    }
                }
            }
        }
        if (card.items.isNotEmpty()) {
            Text(
                if (card.summary.isBlank()) "$done/${card.items.size} 完成" else card.summary,
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
            )
        }
    }
}

// ═══════════════════ 结构增强 ═══════════════════

/** 手风琴：只展开一项，点击标题切换。 */
@Composable
internal fun AccordionCardView(card: AccordionCard, onCommand: (String) -> Unit) {
    var expanded by remember { mutableStateOf(card.expandedIndex) }
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        card.items.forEachIndexed { i, it ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = if (expanded == i) -1 else i }
                    .padding(vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (expanded == i) "▲" else "▼",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(it.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
                }
                AnimatedVisibility(visible = expanded == i) {
                    Column(Modifier.padding(start = 24.dp, top = 6.dp)) {
                        Text(it.body, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
            }
            if (i < card.items.size - 1) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(cs.outlineVariant.copy(alpha = 0.5f)))
            }
        }
    }
}

/** 分组列表：小标题分区 + 副标题 + 富余值。 */
@Composable
internal fun GroupedListCardView(card: GroupedListCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        card.sections.forEach { sec ->
            Text(
                sec.title,
                Modifier.padding(vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                color = cs.primary,
                fontWeight = FontWeight.Bold,
            )
            sec.items.forEach { it ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onCommand("ai:${it.text}") }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(it.text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = cs.onSurface)
                    if (it.sub.isNotBlank()) {
                        Text(it.sub, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    }
                    if (it.value.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text(it.value, style = MaterialTheme.typography.labelSmall, color = cs.primary)
                    }
                }
            }
        }
    }
}

/** 树形层级：递归缩进，前 expandedDepth 层默认展开。 */
@Composable
internal fun TreeCardView(card: TreeCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        card.nodes.forEach { node -> TreeRow(node, 0, card.expandedDepth, cs, onCommand) }
    }
}

@Composable
private fun TreeRow(
    node: TreeCard.Node,
    depth: Int,
    expandedDepth: Int,
    cs: ColorScheme,
    onCommand: (String) -> Unit,
) {
    var open by remember { mutableStateOf(depth < expandedDepth) }
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = node.children.isNotEmpty()) { open = !open }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (node.children.isNotEmpty()) {
                Text(
                    if (open) "▾" else "▸",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                )
            } else {
                Spacer(Modifier.width(16.dp))
            }
            Spacer(Modifier.width(4.dp))
            Text(node.label, Modifier.padding(start = (depth * 12).dp), style = MaterialTheme.typography.bodySmall, color = cs.onSurface)
            if (node.value.isNotBlank()) {
                Spacer(Modifier.width(6.dp))
                Text(node.value, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
        }
        AnimatedVisibility(visible = open && node.children.isNotEmpty()) {
            Column(Modifier.padding(start = 14.dp)) {
                node.children.forEach { c -> TreeRow(c, depth + 1, expandedDepth, cs, onCommand) }
            }
        }
    }
}

/** 引述块：左边框 + 斜体正文 + 署名/来源。 */
@Composable
internal fun QuoteCardView(card: com.ai.assistance.quro.core.cards.QuoteCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onCommand("ai:${card.text}") }
            .border(
                width = 3.dp,
                color = cs.primary.copy(alpha = 0.7f),
                shape = RoundedCornerShape(0.dp, 8.dp, 8.dp, 0.dp),
            )
            .padding(12.dp),
    ) {
        Text(
            "“${card.text}”",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurface,
            fontStyle = FontStyle.Italic,
        )
        val who = listOfNotNull(card.author, card.source).joinToString(" · ")
        if (who.isNotBlank()) {
            Text(who, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
    }
}

/** 差异对比：增删行着色 + +/- 统计 + 一键复制。 */
@Composable
internal fun DiffCardView(card: DiffCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                card.file.ifBlank { "diff" },
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                color = cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("+${card.additions}", style = MaterialTheme.typography.labelSmall, color = ExCardPalette.SUCCESS)
            Spacer(Modifier.width(6.dp))
            Text("-${card.deletions}", style = MaterialTheme.typography.labelSmall, color = ExCardPalette.ERROR)
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = {
                exCopy(ctx, card.rows.joinToString("\n") { "${it.kind}: ${it.text}" })
                Toast.makeText(ctx, ctx.getString(R.string.qk_00023), Toast.LENGTH_SHORT).show()
            }) {
                Icon(Icons.Filled.ContentCopy, null, Modifier.size(16.dp), cs.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(6.dp))
        card.rows.forEach { r ->
            val (bg, fg) =
                if (r.kind == "del") ExCardPalette.ERROR.copy(alpha = 0.16f) to ExCardPalette.ERROR
                else ExCardPalette.SUCCESS.copy(alpha = 0.16f) to ExCardPalette.SUCCESS
            Text(
                "${if (r.kind == "del") "-" else "+"}${r.oldText?.let { "$it -> " } ?: ""}${r.text}",
                Modifier
                    .fillMaxWidth()
                    .background(bg)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = fg,
            )
        }
    }
}

// ═══════════════════ 流程 / 实体 ═══════════════════

/** 流程图：节点竖排（或横排），节点间画箭头。 */
@Composable
internal fun FlowCardView(card: FlowCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    if (card.nodes.isEmpty()) return
    val vertical = card.direction != "horizontal"
    Column(Modifier.fillMaxWidth()) {
        card.nodes.forEachIndexed { i, n ->
            val tint =
                when (n.kind) {
                    "start" -> ExCardPalette.SUCCESS
                    "end" -> ExCardPalette.INFO
                    else -> cs.primary
                }
            Box(
                Modifier
                    .then(if (vertical) Modifier.fillMaxWidth() else Modifier.widthIn(min = 110.dp))
                    .clickable { onCommand(if (n.command.isNotBlank()) n.command else "ai:${n.label}") }
                    .clip(RoundedCornerShape(10.dp))
                    .background(tint.copy(alpha = 0.16f))
                    .border(1.dp, tint.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                Column {
                    Text(n.label, style = MaterialTheme.typography.bodySmall, color = cs.onSurface, fontWeight = FontWeight.Medium)
                    if (n.desc.isNotBlank()) {
                        Text(n.desc, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
            if (i < card.nodes.size - 1) {
                if (vertical) {
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth().height(10.dp), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.size(10.dp, 10.dp)) {
                            drawPath(
                                Path().apply {
                                    moveTo(size.width / 2f, 0f)
                                    lineTo(size.width / 2f, size.height)
                                    lineTo(size.width * 0.2f, size.height - 4f)
                                    moveTo(size.width / 2f, size.height)
                                    lineTo(size.width * 0.8f, size.height - 4f)
                                },
                                cs.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                } else {
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
    }
}

/** 层级流程：阶段纵向连列，阶段内条目做成胶囊。 */
@Composable
internal fun HierarchyCardView(card: HierarchyCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        card.stages.forEachIndexed { i, st ->
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.width(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(ExCardPalette.color(st.color)))
                    if (i < card.stages.size - 1) {
                        Spacer(Modifier.height(4.dp))
                        Box(Modifier.width(2.dp).height(24.dp).background(cs.outlineVariant))
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(st.name, style = MaterialTheme.typography.titleSmall, color = cs.onSurface, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(5.dp))
                    st.items.forEach { item ->
                        Box(
                            Modifier
                                .padding(bottom = 5.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(cs.surfaceVariant)
                                .clickable { onCommand("ai:$item") }
                                .padding(horizontal = 9.dp, vertical = 3.dp),
                        ) {
                            Text(item, style = MaterialTheme.typography.labelSmall, color = cs.onSurface)
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

/** 联系人卡：头像 + 状态点 + 快捷动作。 */
@Composable
internal fun ContactCardView(card: ContactCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (card.avatar.isNotBlank()) {
                AsyncImage(card.avatar, null, Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)))
            } else {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(cs.primary.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(card.name.take(1).ifBlank { "?" }, style = MaterialTheme.typography.titleMedium, color = cs.primary)
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(card.name, style = MaterialTheme.typography.titleSmall, color = cs.onSurface, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                when (card.status) {
                                    "online" -> ExCardPalette.SUCCESS
                                    "busy" -> ExCardPalette.ERROR
                                    "away" -> ExCardPalette.WARNING
                                    else -> cs.outlineVariant
                                },
                            ),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        card.role.ifBlank { card.status },
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
        if (card.actions.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                card.actions.forEach { q ->
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(cs.surfaceVariant)
                            .clickable { onCommand(q.command) }
                            .padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(q.label, style = MaterialTheme.typography.labelSmall, color = cs.primary)
                    }
                }
            }
        }
    }
}

/** 商品卡：图 + 现价 + 划线价 + 评分销量。 */
@Composable
internal fun ProductCardView(card: ProductCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val target = card.image.takeIf { it.startsWith("http", true) }.orEmpty()
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onCommand(if (card.command.isBlank()) "ai:${card.name}" else card.command) }
            .padding(4.dp),
    ) {
        if (target.isNotBlank()) {
            AsyncImage(
                target,
                null,
                Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        } else {
            Box(Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)).background(cs.surfaceVariant))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    card.name,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    color = cs.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (card.tag.isNotBlank()) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(ExCardPalette.WARNING.copy(alpha = 0.2f))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    ) {
                        Text(card.tag, style = MaterialTheme.typography.labelSmall, color = ExCardPalette.WARNING)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(card.price, style = MaterialTheme.typography.titleMedium, color = ExCardPalette.ERROR, fontWeight = FontWeight.Bold)
                if (!card.originalPrice.isNullOrBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        card.originalPrice,
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                        textDecoration = TextDecoration.LineThrough,
                    )
                }
            }
            if (card.rating > 0f || card.sold > 0) {
                Text(
                    listOfNotNull(
                        if (card.rating > 0f) "★ %.1f".format(card.rating) else null,
                        if (card.sold > 0) "已售 $card.sold" else null,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

/** 日程卡：事件 + 日期/时间/地点 + 跳转按钮。 */
@Composable
internal fun ScheduleCardView(card: ScheduleCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onCommand(if (card.command.isBlank()) "ai:${card.event}" else card.command) }
            .clip(RoundedCornerShape(10.dp))
            .background(cs.surfaceVariant.copy(alpha = 0.5f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(card.event, style = MaterialTheme.typography.titleSmall, color = cs.onSurface, fontWeight = FontWeight.SemiBold, maxLines = 1)
            val meta = listOfNotNull(card.date, card.time, card.place).joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(meta, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 2)
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(cs.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp), cs.primary)
        }
    }
}

/** 文件卡：类型徽标 + 名称 + 大小/MIME + 复制路径。 */
@Composable
internal fun FileCardView(card: FileCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clickable { onCommand(if (card.command.isBlank()) card.path else card.command) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(cs.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                card.name.substringAfterLast('.').take(3).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = cs.primary,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(card.name, style = MaterialTheme.typography.bodySmall, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(card.size.takeIf { it.isNotBlank() }, card.mime.takeIf { it.isNotBlank() }).joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(meta, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
        }
        IconButton(onClick = {
            exCopy(ctx, card.path)
            Toast.makeText(ctx, ctx.getString(R.string.qk_00023), Toast.LENGTH_SHORT).show()
        }) {
            Icon(Icons.Filled.ContentCopy, null, Modifier.size(16.dp), cs.onSurfaceVariant)
        }
    }
}

/** 成就卡：徽章 + 描述 + 进度（progress<0 表示不画进度条）。 */
@Composable
internal fun AchievementCardView(card: com.ai.assistance.quro.core.cards.AchievementCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onCommand(if (card.command.isBlank()) "ai:${card.name}" else card.command) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(cs.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(card.icon.takeIf { it.isNotBlank() } ?: "★", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(card.name, style = MaterialTheme.typography.titleSmall, color = cs.onSurface, fontWeight = FontWeight.SemiBold)
            if (card.desc.isNotBlank()) {
                Text(card.desc, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 2)
            }
            if (card.progress >= 0f) {
                Spacer(Modifier.height(5.dp))
                Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(cs.surfaceVariant)) {
                    Box(Modifier.fillMaxWidth(card.progress.coerceIn(0f, 1f)).fillMaxSize().background(cs.primary))
                }
            }
        }
    }
}

// ═══════════════════ 天气 / 地图 / 二维码 / 图集 / 终端 ═══════════════════

/** 天气卡：当前天气 + 逐时预报（横滑）。 */
@Composable
internal fun WeatherCardView(card: WeatherCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onCommand("ai:${card.city}天气") }
            .padding(vertical = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(card.icon.takeIf { it.isNotBlank() } ?: "☀️", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(card.city, style = MaterialTheme.typography.titleMedium, color = cs.onSurface, fontWeight = FontWeight.Bold)
                Text(
                    listOfNotNull(card.temp, card.condition).joinToString(" · "),
                    style = MaterialTheme.typography.titleSmall,
                    color = cs.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                if (card.humidity.isNotBlank()) {
                    Text("湿度 ${card.humidity}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
                if (card.wind.isNotBlank()) {
                    Text("风 ${card.wind}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
            }
        }
        if (card.hours.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                card.hours.forEach { h ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(h.t, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                        Text(h.i.takeIf { it.isNotBlank() } ?: "·", style = MaterialTheme.typography.titleSmall)
                        Text(h.v, style = MaterialTheme.typography.labelSmall, color = cs.onSurface)
                    }
                }
            }
        }
    }
}

/**
 * 地图卡：**不画假地图**。
 *
 * 项目没内置地图 SDK（为一个坐标拖进几 MB 的地图库不划算）。这里画
 * 「坐标 + 标记名 + 复制经纬度 + 打开链接」，明确告诉用户要真地图请点外链。
 * 画一张渐变色块假装是地图属于骗人 —— 宁可少炫，不能误导。
 */
@Composable
internal fun MapCardView(card: MapCard, onCommand: (String) -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(140.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, cs.outlineVariant, RoundedCornerShape(10.dp))
                .background(cs.surfaceVariant.copy(alpha = 0.4f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("📍", style = MaterialTheme.typography.titleLarge)
                Text(
                    "%.5f, %.5f".format(card.lat, card.lng),
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.onSurfaceVariant,
                )
                Text("zoom ${card.zoom}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant.copy(alpha = 0.7f))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (card.markers.isNotEmpty()) {
                Text(
                    card.markers.joinToString("、") { it.label.ifBlank { "未命名" } },
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurface,
                    maxLines = 2,
                )
            }
            IconButton(onClick = {
                exCopy(ctx, "%.6f,%.6f".format(card.lat, card.lng))
                Toast.makeText(ctx, ctx.getString(R.string.qk_00023), Toast.LENGTH_SHORT).show()
            }) { Icon(Icons.Filled.ContentCopy, null, Modifier.size(16.dp), cs.onSurfaceVariant) }
            IconButton(onClick = { onCommand("open:geo:${card.lat},${card.lng}") }) {
                Icon(Icons.Filled.Link, null, Modifier.size(16.dp), cs.primary)
            }
        }
    }
}

/** 二维码：**不画假二维码**。可点击复制原文，避免看起来像但扫不出来。 */
@Composable
internal fun QrCodeCardView(card: QrCodeCard, onCommand: (String) -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(card.size.coerceIn(120, 200).dp)
                .clip(RoundedCornerShape(10.dp))
                .background(ExCardPalette.color(card.color))
                .clickable {
                    exCopy(ctx, card.content)
                    Toast.makeText(ctx, ctx.getString(R.string.qk_00023), Toast.LENGTH_SHORT).show()
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                card.content,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 6,
            )
        }
        if (card.caption.isNotBlank()) {
            Text(card.caption, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
    }
}

/** 图片网格：columns 列 + aspectRatio 定高。 */
@Composable
internal fun GalleryCardView(card: GalleryCard, onCommand: (String) -> Unit) {
    if (card.images.isEmpty()) return
    val cols = card.columns.coerceAtLeast(1)
    val rows = (card.images.size + cols - 1) / cols
    Column(Modifier.fillMaxWidth()) {
        repeat(rows) { r ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val slice = card.images.subList(r * cols, min(r * cols + cols, card.images.size))
                slice.forEach { im ->
                    Column(Modifier.weight(1f)) {
                        AsyncImage(
                            im.url,
                            null,
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(card.aspectRatio.coerceAtLeast(0.2f))
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onCommand("ai:${im.caption ?: im.url}") },
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        )
                        if (!im.caption.isNullOrBlank()) {
                            Text(
                                im.caption,
                                Modifier.padding(top = 3.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = csOnSurfaceVariant(),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            if (r < rows - 1) Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun csOnSurfaceVariant(): Color = MaterialTheme.colorScheme.onSurfaceVariant

/** 终端输出：等宽 + 滚动，非 0 退出码顶部标红。 */
@Composable
internal fun TerminalCardView(card: TerminalCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        if (card.command.isNotBlank()) {
            Text(
                "$ ${card.command}",
                Modifier.padding(bottom = 4.dp),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = cs.primary,
                maxLines = 2,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (card.exitCode == 0) cs.surfaceVariant.copy(alpha = 0.6f) else ExCardPalette.ERROR.copy(alpha = 0.12f),
                )
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
        ) {
            Column {
                card.lines.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = cs.onSurface)
                }
                if (card.lines.isEmpty()) {
                    Text("(无输出)", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
        }
        if (card.exitCode != 0) {
            Text(
                "退出码 $card.exitCode",
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = ExCardPalette.ERROR,
            )
        }
    }
}

// ═══════════════════ 导航 / 装饰 / 兜底 ═══════════════════

/** 链接列表：一条一行，点击走既有打开器。 */
@Composable
internal fun LinkListCardView(card: LinkListCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        card.links.forEach { l ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onCommand("open:${l.url}") }
                    .padding(vertical = 7.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(l.icon ?: "🔗", Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(l.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = cs.primary)
                    Icon(Icons.Filled.Link, null, Modifier.size(14.dp), cs.onSurfaceVariant)
                }
                if (!l.desc.isNullOrBlank()) {
                    Text(
                        l.desc,
                        Modifier.padding(start = 22.dp, top = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

/**
 * 分页器：当前页是**本地状态**（翻页当场高亮），再把页码回传给对话方。
 *
 * 只发联动（同 id 兄弟卡一起翻）不回传，还是回传（AI 那侧也要翻页），
 * 取决于卡片有没有配 `command` —— 有就两边都做。
 */
@Composable
internal fun PaginationCardView(card: PaginationCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var page by remember(card.id) { mutableStateOf(card.page) }
    DisposableEffect(card.id) {
        val off = CardActionBus.subscribe(card.id) { a ->
            parseCardTogglePayload(a.arg("data"))?.let { (p, _) -> if (p > 0) page = p }
            true
        }
        onDispose { off() }
    }
    val total = card.total.coerceAtLeast(1)
    val shown = min(total, 7)
    val base = (page - 2).coerceIn(1, (total - shown + 1).coerceAtLeast(1))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until shown) {
            val p = base + i
            val sel = p == page
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .size(width = 30.dp, height = 28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (sel) cs.primary else cs.surfaceVariant)
                    .clickable {
                        page = p
                        if (card.command.isBlank()) {
                            // 没有 command 就只做本地联动：让同 id 的另一张分页器一起翻
                            emitCardToggle(card.id, p, 1, onCommand)
                        } else {
                            onCommand(card.command + " " + p + " 页")
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(p.toString(), style = MaterialTheme.typography.labelSmall, color = if (sel) cs.onPrimary else cs.onSurface)
            }
        }
        if (total > shown) {
            Text("… $total", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
    }
}

/** 分隔线：可带居中文字；dashed 用较浅色，别引第三方。 */
@Composable
internal fun DividerCardView(card: DividerCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val lineColor = if (card.dashed) cs.outlineVariant else cs.outline
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onCommand("ai:${card.text.orEmpty()}") }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(lineColor))
        card.text?.let { t ->
            Spacer(Modifier.width(8.dp))
            Text(t, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
        }
        Box(Modifier.weight(1f).height(1.dp).background(lineColor))
    }
}

/** 垂直间隔：纯占位高度，用来调卡片之间节奏。 */
@Composable
internal fun SpacerCardView(card: SpacerCard, onCommand: (String) -> Unit) {
    Spacer(Modifier.height(card.height.coerceIn(4, 64).dp))
}

/**
 * 兜底卡：AI 下了客户端不认的 type。
 *
 * 直接丢掉会让人以为「AI 什么都没发生」；这里显示原始 JSON，
 * 既让用户看见 AI 想画什么，也方便人肉定位「客户端缺哪个组件」。
 */
@Composable
internal fun CustomCardView(card: CustomCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("⚠️", Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                "未识别组件：${card.kind}",
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = {
                exCopy(ctx, card.payload)
                Toast.makeText(ctx, ctx.getString(R.string.qk_00023), Toast.LENGTH_SHORT).show()
            }) { Icon(Icons.Filled.ContentCopy, null, Modifier.size(16.dp), cs.onSurfaceVariant) }
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 160.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(cs.surfaceVariant.copy(alpha = 0.5f))
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
        ) {
            Text(card.payload, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = cs.onSurface)
        }
    }
}

/** 写系统剪贴板（各渲染器共用这段胶水，别重复）。 */
private fun exCopy(ctx: Context, text: String) {
    val mgr = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
    mgr?.setPrimaryClip(ClipData.newPlainText("card", text))
}

// ─────────────── 卡片内部联动的小协议 ───────────────
//
// 一张卡点一下，同 id 的另一张卡也要跟着变（AI 爱在一条消息里下发两张
// 同 id 的清单：一份「待办」一份「已完成」）。以前只能 `onCommand("card.id:toggle:i")`
// 扔给宿主，宿主 `handleCardCommand` 里根本没有这个分支 —— 点了没反应。
// 现在走 [CardAction] 的 EMIT 族 + [CardActionBus]，同帧同步、不绕 AI。
//
// 载荷约定：只带一个串 `"下标:0|1"`。别往里塞 JSON —— 协议上 data 就一条，
// 塞 JSON 只会让下游再写一套解析。

/** 勾选/投票类联动载荷。 */
internal fun cardTogglePayload(index: Int, v: Int): String = "$index:$v"

/** 解析 [cardTogglePayload]；格式不对返回 null（宁可不动，也别把下标猜错涂错行）。 */
internal fun parseCardTogglePayload(data: String): Pair<Int, Int>? {
    val c = data.indexOf(':')
    if (c <= 0 || c == data.length - 1) return null
    val i = data.substring(0, c).toIntOrNull() ?: return null
    val v = data.substring(c + 1).toIntOrNull() ?: return null
    return i to v
}

/** 一张卡发联动：本地先更新，再广播给同 id 的其他实例。 */
internal fun emitCardToggle(cardId: String, index: Int, v: Int, onCommand: (String) -> Unit) {
    onCommand(CardAction.emit(cardId, cardTogglePayload(index, v)).toCommand())
}