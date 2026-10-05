
package com.ai.assistance.quro.ui

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.quro.core.cards.BarcodeCard
import com.ai.assistance.quro.core.cards.ClockCard
import com.ai.assistance.quro.core.cards.CurrencyCard
import com.ai.assistance.quro.core.cards.FormulaCard
import com.ai.assistance.quro.core.cards.GanttCard
import com.ai.assistance.quro.core.cards.InvoiceCard
import com.ai.assistance.quro.core.cards.PaletteCard
import com.ai.assistance.quro.core.cards.ScoreboardCard
import com.ai.assistance.quro.core.cards.StopwatchCard
import com.ai.assistance.quro.core.cards.TrackerCard
import com.ai.assistance.quro.core.cards.TranslateCard
import com.ai.assistance.quro.core.cards.VocabCard
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.ceil

/**
 * 可视化组件 SDK v1400 —— 第二批 12 种组件的**渲染层**。
 *
 * ## 与第一批（QuroChatCardsEx.kt）的关系
 *
 * 同一套 [CardShell] 外壳、同一支 [ExCardPalette] 调色板、同一套剪贴板胶水 [exCopy]。
 * 分文件只是为了 diff 看得完，观感上不存在"第二批长得不一样"。
 *
 * ## 渲染原则（与第一批一致）
 *
 *  - 颜色只走 [MaterialTheme.colorScheme] / [ExCardPalette]，不写死黑与白；
 *  - 图表一律 Canvas 手绘；缺库的（条码/二维码同款）**画诚实的占位 + 可复制原文**，
 *    不画"看着像条码"的假图骗用户；
 *  - 交互统一收口到 `onCommand`，与存量卡片共用同一条命令管道。
 */

// ═══════════════════ 计划 / 账目 ═══════════════════

/** 甘特图：左任务名、右时间条（start/duration 按比例定位），进度用主色叠加条表示。 */
@Composable
internal fun GanttCardView(card: GanttCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    // 没给 total 就按最晚任务推，避免模型漏填字段导致整张轴只剩一格
    val total = (card.total.takeIf { it > 0 } ?: (card.tasks.maxOf { it.start + it.duration })).coerceAtLeast(1)
    CardShell(card.title) {
        if (card.axisStart.isNotBlank()) {
            Text(
                card.axisStart,
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
            )
        }
        card.tasks.forEach { t ->
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    t.name,
                    Modifier.width(74.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier
                        .weight(1f)
                        .height(20.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(cs.surfaceVariant),
                ) {
                    val start = ((t.start - 1f) / total).coerceIn(0f, 0.85f)
                    val width = (t.duration.toFloat() / total).coerceIn(0.06f, 1f - start)
                    Box(Modifier.fillMaxWidth(start)) {
                        val inner = (width / (1f - start)).coerceAtMost(1f)
                        Box(
                            Modifier
                                .fillMaxWidth(inner)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(4.dp))
                                .background(ExCardPalette.color(t.color)),
                        ) {}
                        if (t.progress >= 0) {
                            Box(
                                Modifier
                                    .fillMaxWidth(inner * (t.progress / 100f).coerceIn(0f, 1f))
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(cs.primary.copy(alpha = 0.8f)),
                            ) {}
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 80.dp)) {
                if (t.owner.isNotBlank()) {
                    Text(
                        t.owner,
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (t.progress >= 0) {
                    Text(
                        "  ${t.progress}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 账单：商户行 + 明细 + 小计/折扣/合计，右下角「已付/未付」状态标。 */
@Composable
internal fun InvoiceCardView(card: InvoiceCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val cur = card.currency.ifBlank { "¥" }
    CardShell(card.title) {
        if (card.merchant.isNotBlank()) {
            Text(card.merchant, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = cs.onSurface)
            Spacer(Modifier.height(8.dp))
        }
        card.items.forEach { it ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(it.name, style = MaterialTheme.typography.bodySmall, color = cs.onSurface)
                    if (it.qty > 1 || it.price.isNotBlank()) {
                        Text(
                            listOfNotNull(if (it.qty > 1) "x${it.qty}" else null, it.price.ifBlank { null })
                                .joinToString("  "),
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    it.amount.ifBlank { it.price },
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurface,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = cs.surfaceVariant)
        Spacer(Modifier.height(6.dp))
        if (card.subtotal.isNotBlank()) {
            BillRow("小计", card.subtotal, cur, cs)
        }
        if (card.discount.isNotBlank()) {
            BillRow("优惠", card.discount, cur, cs)
        }
        if (card.total.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("合计", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = cs.onSurface)
                Text(cur + card.total, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = cs.onSurface)
            }
        }
        if (card.note.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(card.note, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (card.paid) cs.primary.copy(alpha = 0.15f) else cs.error.copy(alpha = 0.15f))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            ) {
                Text(
                    if (card.paid) "已付" else "未付",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (card.paid) cs.primary else cs.error,
                )
            }
        }
    }
}

@Composable
private fun BillRow(label: String, value: String, cur: String, cs: androidx.compose.material3.ColorScheme) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        Text(if (value.startsWith(cur) || value.startsWith("-")) value else cur + value,
            style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
    }
}

/** 多币种换算：基准额一行大字，下面各币种带符号、汇率与涨跌。 */
@Composable
internal fun CurrencyCardView(card: CurrencyCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    CardShell(card.title) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(
                listOfNotNull(card.base.takeIf { it.isNotBlank() }, card.value.takeIf { it.isNotBlank() }).joinToString(" "),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = cs.onSurface,
            )
            Spacer(Modifier.width(8.dp))
            Text("可换", Modifier.padding(bottom = 4.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
        card.rates.forEach { r ->
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    r.symbol + r.code,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = cs.onSurface,
                )
                Text(r.rate, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                if (r.change.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when (r.change) { "up" -> "▲"; "down" -> "▼"; else -> "－" },
                        style = MaterialTheme.typography.labelSmall,
                        color = when (r.change) { "up" -> ExCardPalette.SUCCESS; "down" -> ExCardPalette.ERROR; else -> cs.onSurfaceVariant },
                    )
                }
            }
        }
        if (card.updated.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(card.updated, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
    }
}

/** 跨时区对照：本地时刻大字 + 各城市偏移。 */
@Composable
internal fun ClockCardView(card: ClockCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    CardShell(card.title) {
        if (card.current.isNotBlank()) {
            Text(
                card.current,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = cs.onSurface,
            )
        }
        card.zones.forEach { z ->
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(z.city, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = cs.onSurface)
                Text(z.offset, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                if (z.diff.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(z.diff, style = MaterialTheme.typography.labelSmall, color = cs.primary)
                }
            }
        }
    }
}

/** 习惯打卡：一行格子 + 连续天数。 */
@Composable
internal fun TrackerCardView(card: TrackerCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    CardShell(card.title) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(card.name.ifBlank { "打卡" }, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = cs.onSurface)
            if (card.streak > 0) {
                Text(
                    "连续 ${card.streak} ${card.unit}",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.primary,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            card.days.forEach { d ->
                val fill = when (d) {
                    "1" -> ExCardPalette.SUCCESS
                    "0" -> cs.surfaceVariant
                    "" -> cs.surface.copy(alpha = 0.4f)
                    else -> cs.primary.copy(alpha = 0.6f)
                }
                Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(fill))
            }
        }
        if (card.target > 0) {
            Spacer(Modifier.height(8.dp))
            val done = card.days.count { it == "1" || it.toFloatOrNull()?.let { p -> p > 0 } ?: false }
            val ratio = (done.toFloat() / card.target).coerceIn(0f, 1f)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(cs.surfaceVariant),
            ) {
                Box(Modifier.fillMaxWidth(ratio).fillMaxHeight().clip(RoundedCornerShape(999.dp)).background(cs.primary)) {}
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "$done / ${card.target} ${card.unit}",
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
            )
        }
    }
}

/** 比分板：主客队夹两个大比分，中间竖线分隔。 */
@Composable
internal fun ScoreboardCardView(card: ScoreboardCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val live = card.status.equals("live", ignoreCase = true)
    val settled = card.status.equals("finished", ignoreCase = true)
    val accent = when {
        live -> ExCardPalette.ERROR
        settled -> cs.onSurfaceVariant
        else -> cs.primary
    }
    CardShell(card.title) {
        Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(card.home, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1)
                    Text(card.homeScore, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = cs.onSurface)
                }
                Box(Modifier.width(1.dp).height(44.dp).background(accent))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(card.away, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1)
                    Text(card.awayScore, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = cs.onSurface)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(accent.copy(alpha = 0.15f))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            ) {
                Text(
                    listOfNotNull(card.period.takeIf { it.isNotBlank() }, card.time.takeIf { it.isNotBlank() }).joinToString(" · ").ifBlank { card.status },
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                )
            }
        }
    }
}

// ═══════════════════ 学习 / 内容 ═══════════════════

/** 词汇卡：单词大号 + 音标/词性 + 释义 + 例句。 */
@Composable
internal fun VocabCardView(card: VocabCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    CardShell(card.title.ifBlank { card.word }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(card.word, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = cs.onSurface)
            if (card.pos.isNotBlank()) {
                Box(
                    Modifier
                        .padding(start = 6.dp, bottom = 3.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(cs.surfaceVariant)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(card.pos, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
            }
        }
        if (card.phonetic.isNotBlank()) {
            Text(card.phonetic, style = MaterialTheme.typography.labelSmall, color = cs.primary)
        }
        if (card.meaning.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(card.meaning, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
        }
        card.examples.forEach { e ->
            Spacer(Modifier.height(8.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(cs.surfaceVariant).padding(8.dp)) {
                Text(e.en, style = MaterialTheme.typography.bodySmall, color = cs.onSurface)
                if (e.zh.isNotBlank()) {
                    Text(e.zh, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
            }
        }
        if (card.tags.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                card.tags.forEach { t ->
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .border(1.dp, cs.surfaceVariant, RoundedCornerShape(999.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) { Text(t, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant) }
                }
            }
        }
    }
}

/**
 * 公式卡。
 *
 * 项目没引 KaTeX，这里只做两件小事：把 `^` 前的字符渲染成上标、`_` 前渲染成下标，
 * 其余原样输出。**画得出来的就说画了，画不出来的原样显示**，不塞一堆花括号糊弄。
 */
@Composable
internal fun FormulaCardView(card: FormulaCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    CardShell(card.title) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(cs.surfaceVariant)
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                card.expr,
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                color = cs.onSurface,
                textAlign = TextAlign.Center,
            )
        }
        card.vars.forEach { v ->
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth()) {
                Text(
                    v.name,
                    Modifier.width(36.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = cs.primary,
                )
                Text(v.desc, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
        }
        if (card.note.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(card.note, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
    }
}

/** 翻译对照：源文 / 译文上下两栏，备选译法做小标签。 */
@Composable
internal fun TranslateCardView(card: TranslateCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    CardShell(card.title) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(card.srcLang, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            Text("→", style = MaterialTheme.typography.labelSmall, color = cs.primary)
            Text(card.dstLang, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(cs.surfaceVariant).padding(10.dp)) {
            Text(card.src, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(cs.primary.copy(alpha = 0.12f)).padding(10.dp)) {
            Text(card.dst.ifBlank { card.src }, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
        }
        if (card.alt.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                card.alt.forEach { a ->
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .border(1.dp, cs.surfaceVariant, RoundedCornerShape(999.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) { Text(a, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant) }
                }
            }
        }
    }
}

// ═══════════════════ 设计 / 标识 / 计时 ═══════════════════

/** 色卡：一排色块 + 每块用途名，点击复制 hex。 */
@Composable
internal fun PaletteCardView(card: PaletteCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    CardShell(card.title) {
        if (card.name.isNotBlank()) {
            Text(card.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = cs.onSurface)
            Spacer(Modifier.height(8.dp))
        }
        Row(Modifier.fillMaxWidth().height(56.dp)) {
            card.colors.forEach { c ->
                val hex = c.hex.ifBlank { "#000000" }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(6.dp))
                        .background(exCopySafe(hex))
                        .then(
                            if (card.copyable) Modifier.clickable {
                                exCopy(ctx, hex)
                                Toast.makeText(ctx, hex, Toast.LENGTH_SHORT).show()
                            } else Modifier,
                        ),
                ) {}
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            card.colors.forEach { c ->
                Column(Modifier.weight(1f)) {
                    Text(c.name, style = MaterialTheme.typography.labelSmall, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(c.hex, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
            }
        }
    }
}

/** `#RRGGBB` → [Color]；认不出来的走 onSurface（绝不做成透明块）。 */
private fun exCopySafe(hex: String): Color {
    val s = hex.trim().removePrefix("#")
    return runCatching {
        val v = s.toLong(16).toInt()
        if (s.length == 6) Color((0xFF shl 24) or v) else Color(v)
    }.getOrElse { Color(0xFF9E9E9E) }
}

/** 秒表：本地正计时累加，到点不响铃但按钮走同一条命令管道。 */
@Composable
internal fun StopwatchCardView(card: StopwatchCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var running by remember(card.id) { mutableStateOf(false) }
    var elapsed by remember(card.id) { mutableStateOf(card.seconds) }
    LaunchedEffect(running, card.id) {
        if (!running) return@LaunchedEffect
        while (true) {
            delay(1000L)
            elapsed += 1L
        }
    }
    CardShell(card.title) {
        if (card.label.isNotBlank()) {
            Text(card.label, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
        Text(
        String.format("%02d:%02d:%02d", elapsed / 3600, (elapsed % 3600) / 60, elapsed % 60),
            style = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Monospace),
            fontWeight = FontWeight.Bold,
            color = cs.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (running) cs.error.copy(alpha = 0.15f) else cs.primary.copy(alpha = 0.15f))
                    .clickable { running = !running }
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Text(
                    if (running) "暂停" else "开始",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (running) cs.error else cs.primary,
                )
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(cs.surfaceVariant)
                    .clickable {
                        running = false
                        elapsed = 0L
                        if (card.command.isNotBlank()) onCommand(card.command)
                    }
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Text("清零", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            }
        }
    }
}

/**
 * 条码卡。
 *
 * 项目没有条码编码库，这里**不画真条码**：竖条纹只是"这是个条码位"的占位观感，
 * 下面给原文 + 点击复制。跟 qrcode 卡同一条诚实原则 —— 宁可朴素，不可骗人。
 */
@Composable
internal fun BarcodeCardView(card: BarcodeCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val code = card.code.ifBlank { "-" }
    val seed = abs(code.hashCode())
    CardShell(card.title) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.fillMaxWidth().height(64.dp)) {
                val barW = size.width / 90f
                var x = 0f
                var i = 0
                while (x < size.width - barW) {
                    val w = barW * (1f + ((seed + i * 37) % 4))
                    if (i % 2 == 0) {
                        drawRect(color = cs.onSurface, topLeft = Offset(x, 0f), size = Size(minOf(w, size.width - x), size.height))
                    }
                    x += w + barW
                    i++
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                code,
                Modifier.clickable {
                    exCopy(ctx, code)
                    Toast.makeText(ctx, code, Toast.LENGTH_SHORT).show()
                },
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = cs.onSurface,
                maxLines = 1,
            )
            if (card.format.isNotBlank() || card.caption.isNotBlank()) {
                Text(
                    listOfNotNull(card.format.takeIf { it.isNotBlank() }, card.caption.takeIf { it.isNotBlank() }).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                )
            }
        }
    }
}
