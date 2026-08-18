package com.ai.assistance.quro.ui

import android.content.Context
import android.content.Intent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import android.widget.Toast
import android.content.ClipData
import android.content.ClipboardManager
import android.provider.MediaStore
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import java.io.IOException
import org.json.JSONObject
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ai.assistance.quro.core.QuroBrowserBridge
import com.ai.assistance.quro.core.cards.QuroChatCard
import com.ai.assistance.quro.core.cards.QuroChatCardStore
import com.ai.assistance.quro.core.media.QuroVideoLauncher
import com.ai.assistance.quro.core.tools.QuroMediaController
import com.ai.assistance.quro.service.QuroMediaService
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 对话框底部卡片栏：渲染 AI 下发的可交互 UI 组件（按钮/开关/滑块/进度/统计/提醒/表格/列表/
 * 分段/饼图/评分/倒计时/标签页/折叠/表单/标签/步骤/仪表/媒体/信息/待办/图表/笔记/动作）。
 *
 * 设计要点（v147 重做）：
 * - 全部使用 [MaterialTheme.colorScheme] token，深浅主题自适应，不再硬编码深色。
 * - 底部卡片栏改为「横向滑动卡片带」：折叠态为可横滑的固定宽卡片（不再纵向撑高覆盖全屏）；
 *   点击「展开」才纵向铺开（高度仍封顶），多卡片时也不溢出。
 * - 内联卡片（聊天气泡内）与托盘卡片共用同一套主题化 [CardShell]，视觉一致。
 *
 * [onCommand] 收到卡片动作的 command（ui_open_* / ui_toggle_* / linux:install / run:<cmd>）。
 */

/**
 * 单卡关闭回调的局部上下文：仅在底部卡片栏（[QuroChatCardTray]）中提供，
 * 聊天消息内联组件不提供，因此内联卡片不显示关闭按钮（它们是消息历史的一部分）。
 */
private val LocalCardDismiss = compositionLocalOf<(() -> Unit)?> { null }

@Composable
fun QuroChatCardTray(onCommand: (String) -> Unit) {
    val cards = QuroChatCardStore.cards
    if (cards.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surface,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            // 头部：标题(含数量) + 展开/收起(多卡片时) + 一键清除
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "交互组件 · ${cards.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (cards.size > 1) {
                    IconButton(onClick = { expanded = !expanded }, Modifier.size(30.dp)) {
                        Icon(
                            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (expanded) "收起" else "展开",
                            modifier = Modifier.size(18.dp),
                            tint = cs.onSurfaceVariant,
                        )
                    }
                }
                TextButton(onClick = { QuroChatCardStore.clear() }, Modifier.height(30.dp)) {
                    Text("清除全部", color = cs.primary, style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = cs.outlineVariant)
            Spacer(Modifier.height(8.dp))
            if (expanded) {
                // 展开：纵向铺开，整体高度封顶，内部滚动
                Column(
                    Modifier.fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    cards.forEach { card ->
                        Spacer(Modifier.height(8.dp))
                        CompositionLocalProvider(LocalCardDismiss provides { QuroChatCardStore.remove(card.id) }) {
                            QuroChatCardView(card, onCommand)
                        }
                    }
                }
            } else {
                // 折叠：横向滑动卡片带（固定宽，绝不纵向撑高）
                Row(
                    Modifier.fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    cards.forEach { card ->
                        CompositionLocalProvider(LocalCardDismiss provides { QuroChatCardStore.remove(card.id) }) {
                            Box(Modifier.widthIn(min = 240.dp, max = 300.dp)) {
                                QuroChatCardView(card, onCommand)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 单张 UI 组件的可交互渲染（与底部卡片栏、聊天消息内联组件共用）。
 * 不在本函数内处理标题显隐——标题为空时 [CardShell] 自动隐藏。
 */
@Composable
fun QuroChatCardView(card: QuroChatCard, onCommand: (String) -> Unit) {
    when (card) {
        is QuroChatCard.TodoCard -> TodoCardView(card)
        is QuroChatCard.ChartCard -> ChartCardView(card)
        is QuroChatCard.NoteCard -> NoteCardView(card)
        is QuroChatCard.ActionCard -> ActionCardView(card, onCommand)
        is QuroChatCard.ButtonCard -> ButtonCardView(card, onCommand)
        is QuroChatCard.ToggleCard -> ToggleCardView(card, onCommand)
        is QuroChatCard.SliderCard -> SliderCardView(card, onCommand)
        is QuroChatCard.ProgressCard -> ProgressCardView(card)
        is QuroChatCard.StatCard -> StatCardView(card)
        is QuroChatCard.AlertCard -> AlertCardView(card)
        is QuroChatCard.TableCard -> TableCardView(card)
        is QuroChatCard.ListCard -> ListCardView(card, onCommand)
        is QuroChatCard.SegmentedCard -> SegmentedCardView(card, onCommand)
        is QuroChatCard.PieCard -> PieCardView(card)
        is QuroChatCard.RatingCard -> RatingCardView(card, onCommand)
        is QuroChatCard.CountdownCard -> CountdownCardView(card)
        is QuroChatCard.TabsCard -> TabsCardView(card)
        is QuroChatCard.ExpandableCard -> ExpandableCardView(card)
        is QuroChatCard.FormCard -> FormCardView(card, onCommand)
        is QuroChatCard.ChipsCard -> ChipsCardView(card, onCommand)
        is QuroChatCard.StepsCard -> StepsCardView(card)
        is QuroChatCard.GaugeCard -> GaugeCardView(card)
        is QuroChatCard.MediaCard -> MediaCardView(card)
        is QuroChatCard.InfoCard -> InfoCardView(card)
        is QuroChatCard.ToolCallCard -> ToolCallCardView(card)
        is QuroChatCard.StreamCard -> StreamCardView(card)
        is QuroChatCard.MediaPlayCard -> MediaPlayCardView(card)
        is QuroChatCard.QuickReplyCard -> QuickReplyCardView(card, onCommand)
        is QuroChatCard.QuickActionCard -> QuickActionCardView(card, onCommand)
        is QuroChatCard.TimelineCard -> TimelineCardView(card)
        is QuroChatCard.HeatmapCard -> HeatmapCardView(card)
        is QuroChatCard.CompareCard -> CompareCardView(card)
        is QuroChatCard.RadarCard -> RadarCardView(card)
        is QuroChatCard.TimerCard -> TimerCardView(card, onCommand)
        is QuroChatCard.CarouselCard -> CarouselCardView(card)
        is QuroChatCard.KanbanCard -> KanbanCardView(card)
        is QuroChatCard.YuanbaoCard -> YuanbaoCardView(card)
        is QuroChatCard.ColorCard -> ColorCardView(card, onCommand)
        is QuroChatCard.CounterCard -> CounterCardView(card, onCommand)
        is QuroChatCard.BreadcrumbCard -> BreadcrumbCardView(card, onCommand)
        is QuroChatCard.TagCloudCard -> TagCloudCardView(card, onCommand)
        is QuroChatCard.BadgeCard -> BadgeCardView(card, onCommand)
        is QuroChatCard.AvatarGroupCard -> AvatarGroupCardView(card, onCommand)
        is QuroChatCard.MermaidCard -> MermaidCardView(card)
        is QuroChatCard.HtmlPreviewCard -> HtmlPreviewCardView(card)
    }
}

@Composable
private fun CardShell(
    title: String,
    modifier: Modifier = Modifier,
    headerEnd: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val dismiss = LocalCardDismiss.current
    val cs = MaterialTheme.colorScheme
    Card(
        Modifier.fillMaxWidth().then(modifier),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = null,
    ) {
        Column(Modifier.padding(14.dp)) {
            if (title.isNotBlank() || dismiss != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (title.isNotBlank()) {
                        Text(
                            title,
                            color = cs.onSurface,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    headerEnd()
                    if (dismiss != null) {
                        IconButton(onClick = dismiss, Modifier.size(28.dp)) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "关闭",
                                tint = cs.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            content()
        }
    }
}

// ───────────── 通用颜色工具 ─────────────
private val PALETTE = listOf(
    Color(0xFF6CB6FF), Color(0xFF7BE0A0), Color(0xFFFFB74D), Color(0xFFFF8A80),
    Color(0xFFB388FF), Color(0xFF80DEEA), Color(0xFFFFF176), Color(0xFFA1887F),
    Color(0xFF4DB6AC), Color(0xFFF06292),
)

private val SUCCESS = Color(0xFF7BE0A0)
private val WARNING = Color(0xFFFFB74D)
private val ERROR = Color(0xFFFF8A80)

private fun parseColor(s: String, fallback: Color): Color {
    if (s.startsWith("#")) {
        runCatching {
            val hex = s.substring(1)
            val vi = hex.toLong(16).toInt()
            return if (hex.length == 6) Color((0xFF shl 24) or vi) else Color(vi)
        }
    }
    return fallback
}

// ───────────── 按钮 ─────────────
@Composable
private fun ButtonCardView(card: QuroChatCard.ButtonCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    CardShell(card.title) {
        val labelComposable: @Composable RowScope.() -> Unit = {
            if (!card.icon.isNullOrBlank()) Text(card.icon!!, fontSize = 14.sp)
            Text(card.label, color = cs.onPrimary, fontSize = 13.sp)
        }
        when (card.variant) {
            "outlined" -> OutlinedButton(onClick = { onCommand(card.command) }, Modifier.fillMaxWidth()) { Text(card.label, color = cs.primary, fontSize = 13.sp) }
            "tonal" -> FilledTonalButton(onClick = { onCommand(card.command) }, Modifier.fillMaxWidth()) { Text(card.label, color = cs.onSecondaryContainer, fontSize = 13.sp) }
            "text" -> TextButton(onClick = { onCommand(card.command) }, Modifier.fillMaxWidth()) { Text(card.label, color = cs.primary, fontSize = 13.sp) }
            else -> Button(onClick = { onCommand(card.command) }, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = cs.primary)) { labelComposable() }
        }
    }
}

// ───────────── 开关 ─────────────
@Composable
private fun ToggleCardView(card: QuroChatCard.ToggleCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    CardShell(card.title) {
        Row(
            Modifier.fillMaxWidth().clickable {
                QuroChatCardStore.setToggle(card.id, !card.checked)
                if (card.command.isNotBlank()) onCommand(card.command)
            }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (card.label.isNotBlank()) Text(card.label, color = cs.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Switch(checked = card.checked, onCheckedChange = {
                QuroChatCardStore.setToggle(card.id, it)
                if (card.command.isNotBlank()) onCommand(card.command)
            })
        }
    }
}

// ───────────── 滑块 ─────────────
@Composable
private fun SliderCardView(card: QuroChatCard.SliderCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    CardShell(card.title) {
        Text(card.label, color = cs.onSurfaceVariant, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = card.value.coerceIn(card.min, card.max),
                onValueChange = { QuroChatCardStore.setSlider(card.id, it) },
                onValueChangeFinished = { if (card.command.isNotBlank()) onCommand(card.command) },
                valueRange = card.min..card.max,
                steps = if (card.step > 0f) ((card.max - card.min) / card.step).toInt().coerceAtLeast(0) else 0,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = cs.primary,
                    activeTrackColor = cs.primary,
                    inactiveTrackColor = cs.outlineVariant,
                ),
            )
            Text("${"%.2f".format(card.value)}${card.unit}", color = cs.primary, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

// ───────────── 进度条 ─────────────
@Composable
private fun ProgressCardView(card: QuroChatCard.ProgressCard) {
    val cs = MaterialTheme.colorScheme
    CardShell(card.title) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (card.label.isNotBlank()) Text(card.label, color = cs.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text("${card.value.toInt()}${card.suffix}", color = SUCCESS, fontSize = 12.sp)
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = (card.value / card.max).coerceIn(0f, 1f),
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            color = cs.primary, trackColor = cs.outlineVariant,
        )
    }
}

// ───────────── 统计 ─────────────
@Composable
private fun StatCardView(card: QuroChatCard.StatCard) {
    val cs = MaterialTheme.colorScheme
    CardShell(card.title) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(card.value, color = cs.onSurface, fontSize = 26.sp)
            if (card.unit.isNotBlank()) Text(card.unit, color = cs.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, bottom = 3.dp))
            Spacer(Modifier.weight(1f))
            if (card.delta.isNotBlank()) {
                val c = when (card.trend) {
                    "up" -> SUCCESS; "down" -> ERROR; else -> cs.onSurfaceVariant
                }
                Text(card.delta, color = c, fontSize = 12.sp)
            }
        }
        if (card.label.isNotBlank()) Text(card.label, color = cs.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

// ───────────── 提醒 ─────────────
@Composable
private fun AlertCardView(card: QuroChatCard.AlertCard) {
    val cs = MaterialTheme.colorScheme
    val (bg, fg, icon) = when (card.severity.lowercase()) {
        "success" -> Triple(Color(0xFF14361F), SUCCESS, Icons.Filled.CheckCircle)
        "warning" -> Triple(Color(0xFF3A2E12), WARNING, Icons.Filled.Warning)
        "error" -> Triple(cs.errorContainer, cs.error, Icons.Filled.Error)
        else -> Triple(cs.primaryContainer, cs.primary, Icons.Filled.Info)
    }
    Card(
        Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = bg),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column {
                if (card.title.isNotBlank()) Text(card.title, color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(card.text, color = fg.copy(alpha = 0.85f), fontSize = 13.sp)
            }
        }
    }
}

// ───────────── 表格 ─────────────
@Composable
private fun TableCardView(card: QuroChatCard.TableCard) {
    val cs = MaterialTheme.colorScheme
    CardShell(card.title) {
        if (card.headers.isEmpty() && card.rows.isEmpty()) {
            Text("（无数据）", color = cs.onSurfaceVariant, fontSize = 12.sp); return@CardShell
        }
        Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            if (card.headers.isNotEmpty()) {
                Row(Modifier.background(cs.primaryContainer).padding(vertical = 6.dp)) {
                    card.headers.forEach { h ->
                        Text(h, color = cs.onPrimaryContainer, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.widthIn(min = 80.dp, max = 220.dp).padding(horizontal = 8.dp))
                    }
                }
            }
            // ★ ANR 防御：大表（工具/查询返回上百行）原 Column+forEach 主线程一次性布局 → 卡顿/ANR。
            // 上限渲染 80 行，超出显示脚注（完整数据仍在卡片 JSON 中）。
            val shownRows = card.rows.take(80)
            shownRows.forEachIndexed { ri, row ->
                Row(Modifier.padding(vertical = 6.dp).then(if (ri % 2 == 1) Modifier.background(cs.outlineVariant.copy(alpha = 0.18f)) else Modifier)) {
                    row.forEach { cell ->
                        Text(cell, color = cs.onSurfaceVariant, fontSize = 12.sp,
                            modifier = Modifier.widthIn(min = 80.dp, max = 220.dp).padding(horizontal = 8.dp))
                    }
                }
            }
            if (card.rows.size > shownRows.size) {
                Text("… 还有 ${card.rows.size - shownRows.size} 行已省略渲染", color = cs.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(vertical = 6.dp, horizontal = 8.dp))
            }
        }
    }
}

// ───────────── 列表 ─────────────
@Composable
private fun ListCardView(card: QuroChatCard.ListCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    CardShell(card.title) {
        // ★ ANR 防御：大列表（工具/查询返回上百项）原 Column+forEach 主线程一次性布局 → 卡顿/ANR。
        // 上限渲染 80 项，超出显示脚注（完整数据仍在卡片 JSON 中）。
        val shownItems = card.items.take(80)
        shownItems.forEachIndexed { i, it ->
            Row(
                Modifier.fillMaxWidth()
                    .clickable {
                        if (card.selectable) {
                            QuroChatCardStore.setListItemSelected(card.id, i, !it.selected)
                            if (card.command.isNotBlank()) onCommand(card.command)
                        }
                    }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (card.selectable) Checkbox(checked = it.selected, onCheckedChange = {
                    QuroChatCardStore.setListItemSelected(card.id, i, it); if (card.command.isNotBlank()) onCommand(card.command)
                })
                Column(Modifier.weight(1f)) {
                    Text(it.text, color = cs.onSurface, fontSize = 13.sp)
                    if (it.sub.isNotBlank()) Text(it.sub, color = cs.onSurfaceVariant, fontSize = 11.sp)
                }
            }
            if (i < shownItems.lastIndex) HorizontalDivider(color = cs.outlineVariant)
        }
        if (card.items.size > shownItems.size) {
            Text("… 还有 ${card.items.size - shownItems.size} 项已省略渲染", color = cs.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(vertical = 6.dp))
        }
    }
}

// ───────────── 分段选择 ─────────────
@Composable
private fun SegmentedCardView(card: QuroChatCard.SegmentedCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    CardShell(card.title) {
        if (card.label.isNotBlank()) Text(card.label, color = cs.onSurfaceVariant, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            card.options.forEachIndexed { idx, opt ->
                val sel = idx == card.selectedIndex
                Button(
                    onClick = { QuroChatCardStore.setSegmented(card.id, idx); if (card.command.isNotBlank()) onCommand(card.command) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (sel) cs.primary else cs.surface,
                        contentColor = if (sel) cs.onPrimary else cs.onSurfaceVariant,
                    ),
                    border = if (sel) null else ButtonDefaults.outlinedButtonBorder(enabled = true),
                    modifier = Modifier.wrapContentWidth(),
                ) { Text(opt, fontSize = 12.sp) }
            }
        }
    }
}

// ───────────── 饼图 ─────────────
@Composable
private fun PieCardView(card: QuroChatCard.PieCard) {
    val cs = MaterialTheme.colorScheme
    CardShell(card.title) {
        if (card.segments.isEmpty()) { Text("（无数据）", color = cs.onSurfaceVariant, fontSize = 12.sp); return@CardShell }
        val total = card.segments.sumOf { it.value.toDouble() }.coerceAtLeast(0.0001)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(96.dp)) {
                val r = size.minDimension / 2
                var start = -90f
                card.segments.forEachIndexed { i, seg ->
                    val sweep = (seg.value / total.toFloat()) * 360f
                    drawArc(
                        color = parseColor(seg.color, PALETTE[i % PALETTE.size]),
                        startAngle = start, sweepAngle = sweep, useCenter = true,
                        topLeft = Offset(size.width / 2 - r, size.height / 2 - r),
                        size = Size(r * 2, r * 2),
                    )
                    start += sweep
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                card.segments.forEachIndexed { i, seg ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(parseColor(seg.color, PALETTE[i % PALETTE.size])))
                        Spacer(Modifier.width(6.dp))
                        Text("${seg.name}  ${"%.1f".format(seg.value)}", color = cs.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text("${"%.0f".format(seg.value / total * 100)}%", color = cs.onSurfaceVariant, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

// ───────────── 评分 ─────────────
@Composable
private fun RatingCardView(card: QuroChatCard.RatingCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    CardShell(card.title) {
        if (card.label.isNotBlank()) Text(card.label, color = cs.onSurfaceVariant, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Row {
            for (i in 1..card.max) {
                Icon(
                    if (i <= card.value) Icons.Filled.Star else Icons.Filled.StarBorder,
                    null,
                    tint = if (i <= card.value) WARNING else cs.outline,
                    modifier = Modifier.size(28.dp).clickable {
                        QuroChatCardStore.setRating(card.id, i); if (card.command.isNotBlank()) onCommand(card.command)
                    },
                )
            }
        }
    }
}

// ───────────── 倒计时 ─────────────
@Composable
private fun CountdownCardView(card: QuroChatCard.CountdownCard) {
    val cs = MaterialTheme.colorScheme
    var remaining by remember { mutableStateOf(card.targetEpochMs - System.currentTimeMillis()) }
    LaunchedEffect(card.id) {
        while (true) {
            remaining = card.targetEpochMs - System.currentTimeMillis()
            if (remaining <= 0) break
            delay(1000)
        }
    }
    CardShell(card.title) {
        if (card.label.isNotBlank()) Text(card.label, color = cs.onSurfaceVariant, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        val txt = if (remaining <= 0) "已结束" else run {
            val s = (remaining / 1000).toInt()
            val d = s / 86400; val h = (s % 86400) / 3600; val m = (s % 3600) / 60; val sec = s % 60
            buildString { if (d > 0) append("${d}天 "); append("%02d:%02d:%02d".format(h, m, sec)) }
        }
        Text(txt, color = cs.primary, fontSize = 22.sp, fontFamily = FontFamily.Monospace)
    }
}

// ───────────── 标签页 ─────────────
@Composable
private fun TabsCardView(card: QuroChatCard.TabsCard) {
    val cs = MaterialTheme.colorScheme
    CardShell(card.title) {
        if (card.tabs.isEmpty()) { Text("（无内容）", color = cs.onSurfaceVariant, fontSize = 12.sp); return@CardShell }
        val idx = card.selectedIndex.coerceIn(0, card.tabs.lastIndex)
        ScrollableTabRow(
            selectedTabIndex = idx,
            edgePadding = 0.dp,
            containerColor = cs.surfaceVariant,
            contentColor = cs.primary,
            divider = {},
        ) {
            card.tabs.forEachIndexed { i, t ->
                Tab(selected = i == idx, onClick = { QuroChatCardStore.setTabs(card.id, i) },
                    text = { Text(t.title, color = if (i == idx) cs.primary else cs.onSurfaceVariant, fontSize = 12.sp) })
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(card.tabs[idx].body, color = cs.onSurfaceVariant, fontSize = 13.sp)
    }
}

// ───────────── 折叠块 ─────────────
@Composable
private fun ExpandableCardView(card: QuroChatCard.ExpandableCard) {
    val cs = MaterialTheme.colorScheme
    CardShell(card.title) {
        Row(Modifier.fillMaxWidth().clickable { QuroChatCardStore.setExpandable(card.id, !card.expanded) },
            verticalAlignment = Alignment.CenterVertically) {
            Text(card.title.ifBlank { "详情" }, color = cs.onSurface, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Icon(if (card.expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = cs.onSurfaceVariant)
        }
        if (card.expanded) {
            Spacer(Modifier.height(8.dp))
            Text(card.body, color = cs.onSurfaceVariant, fontSize = 13.sp)
        }
    }
}

// ───────────── 表单 ─────────────
@Composable
private fun FormCardView(card: QuroChatCard.FormCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    CardShell(card.title) {
        card.fields.forEach { f ->
            OutlinedTextField(
                value = f.value,
                onValueChange = { QuroChatCardStore.setFormField(card.id, f.key, it) },
                label = { Text(f.label, color = cs.onSurfaceVariant) },
                placeholder = if (f.placeholder.isBlank()) null else ({ Text(f.placeholder, color = cs.onSurfaceVariant) }),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = cs.primary,
                    unfocusedBorderColor = cs.outline,
                    focusedTextColor = cs.onSurface,
                    unfocusedTextColor = cs.onSurface,
                ),
                visualTransformation = if (f.secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            )
            Spacer(Modifier.height(6.dp))
        }
        Button(onClick = { onCommand(card.submitCommand) }, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = cs.primary)) {
            Text("提交", color = cs.onPrimary, fontSize = 13.sp)
        }
    }
}

// ───────────── 标签组 ─────────────
@Composable
private fun ChipsCardView(card: QuroChatCard.ChipsCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    CardShell(card.title) {
        if (card.label.isNotBlank()) Text(card.label, color = cs.onSurfaceVariant, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            card.chips.forEach { chip ->
                val sel = card.selected.contains(chip)
                FilterChip(
                    selected = sel,
                    onClick = {
                        val newSel = if (card.multi) {
                            if (sel) card.selected - chip else card.selected + chip
                        } else {
                            if (sel) emptyList() else listOf(chip)
                        }
                        QuroChatCardStore.setChips(card.id, newSel)
                        if (card.command.isNotBlank()) onCommand(card.command)
                    },
                    label = { Text(chip, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = cs.primaryContainer,
                        selectedLabelColor = cs.onPrimaryContainer,
                        labelColor = cs.onSurfaceVariant,
                    ),
                )
            }
        }
    }
}

// ───────────── 步骤条 ─────────────
@Composable
private fun StepsCardView(card: QuroChatCard.StepsCard) {
    val cs = MaterialTheme.colorScheme
    CardShell(card.title) {
        if (card.steps.isEmpty()) { Text("（无步骤）", color = cs.onSurfaceVariant, fontSize = 12.sp); return@CardShell }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            card.steps.forEachIndexed { i, step ->
                val done = step.status == "done" || i < card.current
                val active = i == card.current || step.status == "active"
                val color = when {
                    done -> SUCCESS
                    active -> cs.primary
                    else -> cs.outlineVariant
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
                    Box(Modifier.size(26.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                        Icon(if (done) Icons.Filled.Check else Icons.Filled.Circle, null,
                            tint = if (done || active) cs.onPrimary else cs.onSurfaceVariant, modifier = Modifier.size(14.dp))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(step.title, color = if (done || active) cs.onSurface else cs.onSurfaceVariant, fontSize = 11.sp, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
                }
                if (i < card.steps.lastIndex) Box(Modifier.width(18.dp).height(2.dp).background(if (done) SUCCESS else cs.outlineVariant))
            }
        }
    }
}

// ───────────── 仪表盘 ─────────────
@Composable
private fun GaugeCardView(card: QuroChatCard.GaugeCard) {
    val cs = MaterialTheme.colorScheme
    CardShell(card.title) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(96.dp)) {
                val r = size.minDimension / 2
                drawArc(color = cs.outlineVariant, startAngle = 135f, sweepAngle = 270f, useCenter = false,
                    topLeft = Offset(size.width / 2 - r, size.height / 2 - r), size = Size(r * 2, r * 2),
                    style = Stroke(width = 10.dp.toPx()))
                val frac = (card.value / card.max).coerceIn(0f, 1f)
                drawArc(color = cs.primary, startAngle = 135f, sweepAngle = 270f * frac, useCenter = false,
                    topLeft = Offset(size.width / 2 - r, size.height / 2 - r), size = Size(r * 2, r * 2),
                    style = Stroke(width = 10.dp.toPx()))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                if (card.label.isNotBlank()) Text(card.label, color = cs.onSurfaceVariant, fontSize = 13.sp)
                Text("${"%.1f".format(card.value)}${card.unit}", color = cs.onSurface, fontSize = 22.sp)
            }
        }
    }
}

// ───────────── 媒体 ─────────────
@Composable
private fun MediaCardView(card: QuroChatCard.MediaCard) {
    val cs = MaterialTheme.colorScheme
    if (card.mediaType.lowercase() == "image") {
        // AI 内联图片（{"type":"media","mediaType":"image","mediaUrl":...}）：直接渲染成自适应图片气泡
        CardShell(card.title) {
            NetworkImageBubble(url = card.mediaUrl, modifier = Modifier.fillMaxWidth())
        }
    } else {
        CardShell(card.title) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when (card.mediaType.lowercase()) {
                        "audio" -> Icons.Filled.Audiotrack
                        "video" -> Icons.Filled.PlayCircle
                        else -> Icons.Filled.Image
                    }, null, tint = cs.primary, modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(card.mediaType.uppercase(), color = cs.onSurfaceVariant, fontSize = 11.sp)
                    Text(card.mediaUrl, color = cs.onSurfaceVariant, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = { QuroBrowserBridge.open(card.mediaUrl) }) {
                    Text("打开", color = cs.primary, fontSize = 12.sp)
                }
            }
        }
    }
}

// ───────────── 信息块 ─────────────
@Composable
private fun InfoCardView(card: QuroChatCard.InfoCard) {
    val cs = MaterialTheme.colorScheme
    // 🛡️ 过滤 body 中的 HTML 标签（AI 模型常在 info card body 中嵌入原始 HTML），
    //   将 <div style=...> 这类标签剥离，只保留可读文本内容。
    val cleanBody = remember(card.body) {
        card.body
            .replace(Regex("<[^>]*>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
    CardShell(card.title) {
        SelectionContainer {
            Text(
                cleanBody, color = cs.onSurfaceVariant, fontSize = 13.sp,
                textAlign = when (card.align) { "center" -> TextAlign.Center; "end" -> TextAlign.End; else -> TextAlign.Start },
            )
        }
    }
}

// ───────────── v135 工具调用 / 流式 / 媒体播放 ─────────────
@Composable
private fun ToolCallCardView(card: QuroChatCard.ToolCallCard) {
    val cs = MaterialTheme.colorScheme
    val (icon, color, label) = when (card.status.lowercase()) {
        "running" -> Triple(Icons.Filled.Autorenew, cs.primary, "运行中")
        "done" -> Triple(Icons.Filled.CheckCircle, SUCCESS, "完成")
        "error" -> Triple(Icons.Filled.Error, ERROR, "失败")
        else -> Triple(Icons.Filled.Schedule, WARNING, "等待中")
    }
    CardShell(card.title) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (card.status == "running") {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp), color = color)
            } else {
                Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(card.tool.ifBlank { "工具调用" }, color = cs.onSurface, fontSize = 13.sp)
                if (card.message.isNotBlank()) Text(card.message, color = cs.onSurfaceVariant, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(label, color = color, fontSize = 11.sp)
        }
        if (card.progress > 0f || card.status == "running") {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = (card.progress / 100f).coerceIn(0f, 1f),
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = color, trackColor = cs.outlineVariant,
            )
        }
    }
}

@Composable
private fun StreamCardView(card: QuroChatCard.StreamCard) {
    val cs = MaterialTheme.colorScheme
    CardShell(card.title) {
        if (card.lines.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp), color = cs.primary); Spacer(Modifier.width(6.dp)); Text("等待输出…", color = cs.onSurfaceVariant, fontSize = 11.sp) }
            return@CardShell
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 200.dp).background(cs.outlineVariant.copy(alpha = 0.18f)).clip(RoundedCornerShape(8.dp)).padding(8.dp)) {
            items(card.lines) { line ->
                Text(line, color = cs.primary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp))
            }
        }
    }
}

@Composable
private fun MediaPlayCardView(card: QuroChatCard.MediaPlayCard) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    CardShell(card.title) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (card.mediaType == "video") Icons.Filled.PlayCircle else Icons.Filled.Audiotrack, null, tint = cs.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text((card.label.ifBlank { card.mediaType.uppercase() }), color = cs.onSurface, fontSize = 13.sp)
                Text(card.uri, color = cs.onSurfaceVariant, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = {
                if (card.mediaType == "video") {
                    QuroVideoLauncher.open(card.uri, card.label)
                } else {
                    val intent = Intent(ctx, QuroMediaService::class.java)
                        .putExtra(QuroMediaService.EXTRA_URI, card.uri)
                        .putExtra(QuroMediaService.EXTRA_TITLE, card.label)
                    runCatching { ctx.startForegroundService(intent) }
                }
            }) {
                Icon(Icons.Filled.PlayArrow, "播放", tint = cs.primary, modifier = Modifier.size(28.dp))
            }
        }
    }
}

// ───────────── v132 legacy ─────────────
@Composable
private fun TodoCardView(card: QuroChatCard.TodoCard) {
    val cs = MaterialTheme.colorScheme
    CardShell(card.title) {
        card.items.forEachIndexed { i, item ->
            Row(
                Modifier.fillMaxWidth().clickable { QuroChatCardStore.toggleTodo(card.id, i) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = item.done, onCheckedChange = { QuroChatCardStore.toggleTodo(card.id, i) })
                Text(
                    item.text,
                    color = if (item.done) cs.onSurfaceVariant else cs.onSurface,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
        if (card.items.isNotEmpty()) {
            val done = card.items.count { it.done }
            Text("已完成 $done / ${card.items.size}", color = SUCCESS, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun ChartCardView(card: QuroChatCard.ChartCard) {
    val cs = MaterialTheme.colorScheme
    CardShell(card.title) {
        if (card.series.isEmpty()) {
            Text("（无数据）", color = cs.onSurfaceVariant, fontSize = 12.sp)
            return@CardShell
        }
        val max = card.series.maxOf { it.value }.coerceAtLeast(0.0001f)
        if (card.type == "line") {
            val h = 120.dp
            val w = (card.series.size * 36).dp.coerceAtLeast(160.dp)
            Canvas(Modifier.height(h).width(w).horizontalScroll(rememberScrollState())) {
                val cw = size.width
                val ch = size.height
                val step = if (card.series.size > 1) cw / (card.series.size - 1) else cw
                val path = Path()
                card.series.forEachIndexed { i, p ->
                    val x = i * step
                    val y = ch - (p.value / max) * (ch - 16.dp.toPx()) - 8.dp.toPx()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color = cs.primary, style = Stroke(width = 3.dp.toPx()))
            }
        } else {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.Bottom,
            ) {
                card.series.forEach { p ->
                    Column(
                        Modifier.width(44.dp).padding(horizontal = 3.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(p.value.toInt().toString(), color = cs.onSurface, fontSize = 11.sp)
                        Box(
                            Modifier.fillMaxWidth().height((p.value / max * 100).dp.coerceAtLeast(4.dp))
                                .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                                .background(cs.primary)
                        )
                        Text(p.label, color = cs.onSurfaceVariant, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteCardView(card: QuroChatCard.NoteCard) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    CardShell(card.title) {
        Text(
            card.body,
            color = cs.onSurfaceVariant,
            fontSize = 13.sp,
            fontFamily = if (card.lang != null) FontFamily.Monospace else FontFamily.Default,
            modifier = Modifier.fillMaxWidth()
                .background(cs.outlineVariant.copy(alpha = 0.18f)).clip(RoundedCornerShape(8.dp)).padding(8.dp)
                .heightIn(max = 240.dp)
                .verticalScroll(rememberScrollState()),
        )
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = {
                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText(card.title, card.body))
                Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
            }) { Text("复制", color = cs.primary) }
        }
    }
}

@Composable
private fun ActionCardView(card: QuroChatCard.ActionCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    CardShell(card.title) {
        card.actions.forEach { act ->
            Button(
                onClick = { onCommand(act.command) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                colors = ButtonDefaults.buttonColors(containerColor = cs.primary),
            ) {
                Text(act.label, color = cs.onPrimary, fontSize = 13.sp)
            }
        }
    }
}

// ───────────── v149 气泡内富组件（关联功能 / 自由化） ─────────────

/** 图标名 → Material 图标映射（组件可自由关联应用内功能）。 */
private fun cardIcon(name: String): ImageVector = when (name.lowercase()) {
    "sparkles", "brain", "ai" -> Icons.Filled.AutoAwesome
    "docs", "file", "document" -> Icons.Filled.Description
    "music", "audio" -> Icons.Filled.Audiotrack
    "video" -> Icons.Filled.PlayCircle
    "terminal" -> Icons.Filled.Terminal
    "settings", "gear" -> Icons.Filled.Settings
    "code" -> Icons.Filled.Code
    "image", "photo" -> Icons.Filled.Image
    "calendar", "date" -> Icons.Filled.DateRange
    "list" -> Icons.Filled.List
    "bolt", "fast" -> Icons.Filled.Bolt
    "chat", "message" -> Icons.Filled.Chat
    "search" -> Icons.Filled.Search
    "tool", "build" -> Icons.Filled.Build
    "star" -> Icons.Filled.Star
    "play" -> Icons.Filled.PlayArrow
    "link" -> Icons.Filled.Link
    "map" -> Icons.Filled.Map
    "timer" -> Icons.Filled.Timer
    "mail" -> Icons.Filled.Email
    "phone" -> Icons.Filled.Phone
    "home" -> Icons.Filled.Home
    "person", "user" -> Icons.Filled.Person
    else -> Icons.Filled.Circle
}

/** 快捷回复：点建议直接回发聊天（组件驱动对话，气泡自化）。 */
@Composable
private fun QuickReplyCardView(card: QuroChatCard.QuickReplyCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    CardShell(card.title) {
        if (card.replies.isEmpty()) { Text("（无建议）", color = cs.onSurfaceVariant, fontSize = 12.sp); return@CardShell }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            card.replies.forEach { r ->
                Button(
                    onClick = { onCommand("reply:" + r) },
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = cs.primaryContainer, contentColor = cs.onPrimaryContainer),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) { Text(r, fontSize = 13.sp) }
            }
        }
    }
}

/** 快捷动作宫格：每个磁贴触发 command（打开应用内任意功能）。 */
@Composable
private fun QuickActionCardView(card: QuroChatCard.QuickActionCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    CardShell(card.title) {
        if (card.actions.isEmpty()) { Text("（无动作）", color = cs.onSurfaceVariant, fontSize = 12.sp); return@CardShell }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            card.actions.forEach { a ->
                Column(
                    Modifier.width(72.dp).clickable { if (a.command.isNotBlank()) onCommand(a.command) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Surface(color = cs.primaryContainer, shape = RoundedCornerShape(14.dp), modifier = Modifier.size(48.dp)) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(cardIcon(a.icon), null, tint = cs.onPrimaryContainer, modifier = Modifier.size(24.dp))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(a.label, color = cs.onSurface, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** 时间线：纵向事件流。 */
@Composable
private fun TimelineCardView(card: QuroChatCard.TimelineCard) {
    val cs = MaterialTheme.colorScheme
    CardShell(card.title) {
        if (card.events.isEmpty()) { Text("（无事件）", color = cs.onSurfaceVariant, fontSize = 12.sp); return@CardShell }
        Column(Modifier.fillMaxWidth()) {
            // ★ ANR 防御：大时间线（工具/日志返回上百事件）原 Column+forEach 主线程一次性布局 → 卡顿/ANR。
            // 上限渲染 60 事件，超出显示脚注。
            val shownEvents = card.events.take(60)
            shownEvents.forEachIndexed { i, ev ->
                Row(Modifier.fillMaxWidth()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(54.dp)) {
                        Text(ev.time, color = cs.onSurfaceVariant, fontSize = 11.sp, maxLines = 1)
                        Spacer(Modifier.height(4.dp))
                        Box(Modifier.size(10.dp).clip(CircleShape).background(if (ev.status == "todo") cs.outline else cs.primary))
                        if (i < shownEvents.lastIndex) Spacer(Modifier.height(4.dp))
                        if (i < shownEvents.lastIndex) Box(Modifier.width(2.dp).height(22.dp).background(cs.outlineVariant))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f).padding(bottom = if (i < shownEvents.lastIndex) 12.dp else 0.dp)) {
                        Text(ev.title, color = cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        if (ev.desc.isNotBlank()) Text(ev.desc, color = cs.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
            }
            if (card.events.size > shownEvents.size) {
                Text("… 还有 ${card.events.size - shownEvents.size} 个事件已省略渲染", color = cs.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

/** 日历热力图：values 按 7×weeks 排列，颜色随强度。 */
@Composable
private fun HeatmapCardView(card: QuroChatCard.HeatmapCard) {
    val cs = MaterialTheme.colorScheme
    CardShell(card.title) {
        if (card.values.isEmpty()) { Text("（无数据）", color = cs.onSurfaceVariant, fontSize = 12.sp); return@CardShell }
        val max = card.values.maxOrNull()?.coerceAtLeast(1) ?: 1
        val weeks = card.weeks.coerceAtLeast(1)
        Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            for (row in 0..6) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    for (col in 0 until weeks) {
                        val idx = col * 7 + row
                        if (idx >= card.values.size) break
                        val frac = card.values[idx].toFloat() / max
                        val color = cs.primary.copy(alpha = (0.15f + 0.85f * frac).coerceIn(0.15f, 1f))
                        Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(color))
                    }
                }
            }
            if (card.label.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(card.label, color = cs.onSurfaceVariant, fontSize = 11.sp) }
        }
    }
}

/** 双栏对比。 */
@Composable
private fun CompareCardView(card: QuroChatCard.CompareCard) {
    CardShell(card.title) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CompareSideView(card.left, Modifier.weight(1f))
            CompareSideView(card.right, Modifier.weight(1f))
        }
    }
}

@Composable
private fun CompareSideView(side: QuroChatCard.CompareCard.CompareSide, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val fg = if (side.positive) SUCCESS else ERROR
    Column(modifier.fillMaxWidth().background(fg.copy(alpha = 0.08f)).clip(RoundedCornerShape(10.dp)).padding(10.dp)) {
        Text(side.title, color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        side.points.forEach { p ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
                Icon(if (side.positive) Icons.Filled.Check else Icons.Filled.Close, null, tint = fg, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(p, color = cs.onSurface, fontSize = 12.sp, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** 雷达图：多维能力。 */
@Composable
private fun RadarCardView(card: QuroChatCard.RadarCard) {
    val cs = MaterialTheme.colorScheme
    CardShell(card.title) {
        if (card.axes.isEmpty()) { Text("（无数据）", color = cs.onSurfaceVariant, fontSize = 12.sp); return@CardShell }
        val n = card.axes.size
        Canvas(Modifier.size(180.dp).align(Alignment.CenterHorizontally)) {
            val cx = size.width / 2f; val cy = size.height / 2f
            val r = size.minDimension / 2f - 16.dp.toPx()
            for (ring in 1..4) {
                val rr = r * ring / 4f
                val path = Path()
                for (i in 0 until n) {
                    val a = -PI / 2 + i * 2 * PI / n
                    val x = cx + rr * cos(a).toFloat(); val y = cy + rr * sin(a).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                drawPath(path, color = cs.outlineVariant, style = Stroke(width = 1.dp.toPx()))
            }
            val path = Path()
            card.axes.forEachIndexed { i, ax ->
                val frac = (ax.value / 100f).coerceIn(0f, 1f)
                val a = -PI / 2 + i * 2 * PI / n
                val x = cx + r * frac * cos(a).toFloat(); val y = cy + r * frac * sin(a).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            drawPath(path, color = cs.primary.copy(alpha = 0.35f))
            drawPath(path, color = cs.primary, style = Stroke(width = 2.dp.toPx()))
        }
        Column(Modifier.fillMaxWidth()) {
            card.axes.forEach { ax ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(ax.name, color = cs.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text("${ax.value.toInt()}", color = cs.primary, fontSize = 12.sp)
                }
            }
        }
    }
}

/** 交互计时器：开始/暂停/重置。 */
@Composable
private fun TimerCardView(card: QuroChatCard.TimerCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var remaining by remember { mutableStateOf(card.seconds) }
    var running by remember { mutableStateOf(false) }
    LaunchedEffect(card.id) {
        while (true) {
            if (running && remaining > 0) { delay(1000); remaining-- }
            else delay(200)
            if (remaining <= 0) { running = false; if (card.command.isNotBlank()) onCommand(card.command) }
        }
    }
    CardShell(card.title) {
        val txt = "%02d:%02d".format(remaining / 60, remaining % 60)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(txt, color = cs.primary, fontSize = 28.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
            IconButton(onClick = { running = !running }) { Icon(if (running) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = cs.primary) }
            IconButton(onClick = { running = false; remaining = card.seconds }) { Icon(Icons.Filled.Refresh, null, tint = cs.onSurfaceVariant) }
        }
    }
}

/** 轮播卡片：点击内容切换下一张。 */
@Composable
private fun CarouselCardView(card: QuroChatCard.CarouselCard) {
    val cs = MaterialTheme.colorScheme
    var page by remember { mutableStateOf(0) }
    CardShell(card.title) {
        if (card.slides.isEmpty()) { Text("（无内容）", color = cs.onSurfaceVariant, fontSize = 12.sp); return@CardShell }
        val p = page.coerceIn(0, card.slides.lastIndex)
        val slide = card.slides[p]
        val accent = parseColor(slide.color.ifBlank { "#6CB6FF" }, PALETTE[0])
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(accent.copy(alpha = 0.12f)).padding(14.dp)
                .clickable { page = (p + 1) % card.slides.size },
        ) {
            Text(slide.title, color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(slide.body, color = cs.onSurfaceVariant, fontSize = 13.sp)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            card.slides.forEachIndexed { i, _ ->
                Surface(
                    color = if (i == p) cs.primary else cs.outlineVariant,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.size(width = if (i == p) 20.dp else 8.dp, height = 8.dp).clickable { page = i },
                ) {}
            }
        }
    }
}

/** 看板：多列任务。 */
@Composable
private fun KanbanCardView(card: QuroChatCard.KanbanCard) {
    val cs = MaterialTheme.colorScheme
    CardShell(card.title) {
        if (card.columns.isEmpty()) { Text("（无看板）", color = cs.onSurfaceVariant, fontSize = 12.sp); return@CardShell }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // ★ ANR 防御：大看板（多列多卡）原 Column+forEach 主线程一次性布局 → 卡顿/ANR。
            // 上限渲染 12 列、每列 40 张卡，超出显示脚注。
            card.columns.take(12).forEach { col ->
                Column(Modifier.width(150.dp).background(cs.outlineVariant.copy(alpha = 0.18f)).clip(RoundedCornerShape(10.dp)).padding(8.dp)) {
                    Text(col.name, color = cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    col.items.take(40).forEach { it2 ->
                        Surface(color = cs.surface, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, cs.outlineVariant), modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text(it2, color = cs.onSurface, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
                        }
                    }
                    if (col.items.size > 40) Text("… +${col.items.size - 40}", color = cs.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
        if (card.columns.size > 12) {
            Text("… 还有 ${card.columns.size - 12} 列已省略渲染", color = cs.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** 链接回答预设清单（用户逐项登记的「点击查看链接回答」链接）。
 *  卡片无可用链接时兜底展示，确保已登记的话题永远有去处。 */
private val PRESET_YUANBAO_LINKS = listOf(
    QuroChatCard.YuanbaoLink(
        "百分百开源安卓数字人",
        "https://yb.tencent.com/s/I9x5hnu8zJqm",
    ),
    QuroChatCard.YuanbaoLink(
        "二、3D 全离线（LLM+ASR+TTS+A2BS+渲染都在手机）",
        "https://yb.tencent.com/s/TsfOddkjerlh",
    ),
)

/** 链接回答预览卡：点击在应用内浏览器打开该回答（原生安卓点击查看体验）。
 *  v294：支持多条链接，逐行可点；无链接时兜底展示预设清单。 */
@Composable
private fun YuanbaoCardView(card: QuroChatCard.YuanbaoCard) {
    val cs = MaterialTheme.colorScheme
    val items = when {
        card.links.isNotEmpty() -> card.links
        card.url.isNotBlank() -> listOf(QuroChatCard.YuanbaoLink(card.title.ifBlank { "链接回答" }, card.url))
        else -> PRESET_YUANBAO_LINKS
    }
    CardShell(card.title.ifBlank { "链接回答" }) {
        Text("需要点击查看链接回答", color = cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        items.forEach { link ->
            Row(
                Modifier.fillMaxWidth()
                    .clickable { QuroBrowserBridge.open(link.url.ifBlank { PRESET_YUANBAO_LINKS.first().url }) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Chat, null, tint = cs.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    if (link.title.isNotBlank()) {
                        Text(link.title, color = cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(2.dp))
                    }
                    Text(link.url, color = cs.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Filled.OpenInBrowser, null, tint = cs.primary, modifier = Modifier.size(20.dp))
            }
        }
    }
}

// ───────────── v221 富事件 / 声明式目录新增卡片 ─────────────

/** 调色板：色块网格，点击复制十六进制（或触发 command）。 */
@Composable
private fun ColorCardView(card: QuroChatCard.ColorCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    CardShell(card.title) {
        if (card.label.isNotBlank()) {
            Text(card.label, color = cs.onSurfaceVariant, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            card.colors.forEach { hex ->
                val color = parseColor(hex, cs.primary)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { onCommand(if (card.command.isNotBlank()) card.command else "copy:$hex") },
                ) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(color))
                    Spacer(Modifier.height(2.dp))
                    Text(hex, color = cs.onSurfaceVariant, fontSize = 10.sp)
                }
            }
        }
    }
}

/** 计数器：± 步进，本地持久化并回传 command。 */
@Composable
private fun CounterCardView(card: QuroChatCard.CounterCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val value = card.value
    CardShell(card.title) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (card.label.isNotBlank()) {
                Text(card.label, color = cs.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.weight(1f))
            }
            IconButton(onClick = {
                QuroChatCardStore.setCounter(card.id, (value - card.step).coerceAtLeast(card.min))
                if (card.command.isNotBlank()) onCommand(card.command)
            }, Modifier.size(32.dp)) {
                Icon(Icons.Filled.Remove, null, tint = cs.primary)
            }
            Text("$value", color = cs.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp))
            IconButton(onClick = {
                QuroChatCardStore.setCounter(card.id, (value + card.step).coerceAtMost(card.max))
                if (card.command.isNotBlank()) onCommand(card.command)
            }, Modifier.size(32.dp)) {
                Icon(Icons.Filled.Add, null, tint = cs.primary)
            }
        }
    }
}

/** 面包屑导航：层级路径，点击某级触发其 command。 */
@Composable
private fun BreadcrumbCardView(card: QuroChatCard.BreadcrumbCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    CardShell(card.title) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            card.crumbs.forEachIndexed { idx, crumb ->
                if (idx > 0) {
                    Text("›", color = cs.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 6.dp))
                }
                Text(
                    crumb.label,
                    color = if (idx == card.crumbs.lastIndex) cs.primary else cs.onSurfaceVariant,
                    fontSize = 13.sp,
                    modifier = Modifier.clickable { if (crumb.command.isNotBlank()) onCommand(crumb.command) },
                )
            }
        }
    }
}

/** 标签云：按权重缩放字号的可点击标签。 */
@Composable
private fun TagCloudCardView(card: QuroChatCard.TagCloudCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    CardShell(card.title) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            card.tags.forEach { tag ->
                val size = (11 + tag.weight.coerceIn(1, 6) * 2).sp
                Surface(
                    color = cs.secondaryContainer,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.clickable { if (tag.command.isNotBlank()) onCommand(tag.command) },
                ) {
                    Text(tag.label, color = cs.onSecondaryContainer, fontSize = size, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                }
            }
        }
    }
}

/** 徽章组：彩色徽章集合，点击触发各自 command。 */
@Composable
private fun BadgeCardView(card: QuroChatCard.BadgeCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    CardShell(card.title) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            card.badges.forEach { badge ->
                val tint = parseColor(badge.color, cs.primary)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(tint.copy(alpha = 0.15f))
                        .clickable { if (badge.command.isNotBlank()) onCommand(badge.command) }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(tint))
                    Spacer(Modifier.width(6.dp))
                    Text(badge.label, color = tint, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/** 头像组：重叠头像（首字占位），点击触发 command。 */
@Composable
private fun AvatarGroupCardView(card: QuroChatCard.AvatarGroupCard, onCommand: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    CardShell(card.title) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            card.avatars.forEachIndexed { idx, av ->
                val initial = av.name.firstOrNull()?.toString() ?: "?"
                Box(
                    Modifier
                        .then(if (idx > 0) Modifier.offset(x = (-8).dp) else Modifier)
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(cs.primaryContainer)
                        .clickable { if (av.command.isNotBlank()) onCommand(av.command) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(initial, color = cs.onPrimaryContainer, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * AI 自写图表（v300→v316）：用 WebView 加载 assets 内的 Mermaid.js 离线渲染 AI 下发的 Mermaid 文本。
 *
 * - 客户端【不内置】任何固定流程图；AI 爱画什么画什么（flowchart / 时序图 / 状态机 / 类图 / 思维导图 / git 图 …）。
 * - 深浅主题自适应：theme 缺省时按系统深浅色选 default / dark。
 * - 渲染完成后通过 AndroidBridge.onHeight 回调把真实高度回传给 Compose，WebView 据此自适应高度（无写死裁切）。
 * - 顶部操作区提供【全屏 / 下载 SVG / 复制源码】；全屏页同样支持双指缩放与拖动查看完整图。
 */
@Composable
private fun MermaidCardView(card: QuroChatCard.MermaidCard) {
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current.density
    val context = LocalContext.current
    var heightPx by remember(card.id) { mutableStateOf(160) }
    var svgRef by remember(card.id) { mutableStateOf<String?>(null) }
    var fullscreen by remember(card.id) { mutableStateOf(false) }
    val title = card.title.ifBlank { "流程图" }

    CardShell(
        title = title,
        headerEnd = {
            IconButton(onClick = { fullscreen = true }, Modifier.size(30.dp)) {
                Icon(Icons.Filled.Fullscreen, "全屏查看", tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = {
                if (svgRef != null) {
                    val ok = saveSvgToDownloads(context, "mermaid_${card.id}.svg", svgRef!!)
                    Toast.makeText(context, if (ok) "已保存 SVG 到 Download 文件夹" else "保存失败", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "图表尚未渲染完成，请稍候", Toast.LENGTH_SHORT).show()
                }
            }, Modifier.size(30.dp)) {
                Icon(Icons.Filled.Download, "下载 SVG", tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = { copyText(context, card.source, "已复制 Mermaid 源码") }, Modifier.size(30.dp)) {
                Icon(Icons.Filled.ContentCopy, "复制源码", tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        },
    ) {
        if (card.source.isBlank()) {
            Text("（无图表内容）", color = cs.onSurfaceVariant, fontSize = 12.sp)
            return@CardShell
        }
        MermaidWebView(
            card = card,
            modifier = Modifier
                .fillMaxWidth()
                .height((heightPx / density).dp)
                .clip(RoundedCornerShape(10.dp)),
            zoomable = false,
            onHeight = { heightPx = it },
            onSvg = { svgRef = it },
        )
    }

    if (fullscreen) {
        MermaidFullscreen(card = card, onDismiss = { fullscreen = false })
    }
}

/**
 * 可复用的 Mermaid WebView：离线城市渲染，渲染完成后回调真实高度与 SVG 文本。
 * [zoomable]=true 时开启双指缩放（用于全屏页），否则按内容高度自适应（用于内联卡片）。
 */
@Composable
private fun MermaidWebView(
    card: QuroChatCard.MermaidCard,
    modifier: Modifier = Modifier,
    zoomable: Boolean = false,
    onHeight: (Int) -> Unit = {},
    onSvg: ((String) -> Unit)? = null,
) {
    val dark = isSystemInDarkTheme()
    val theme = run {
        val t = card.theme.trim().lowercase()
        if (t in setOf("default", "dark", "forest", "neutral", "base")) t else if (dark) "dark" else "default"
    }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                settings.loadsImagesAutomatically = true
                settings.setSupportZoom(zoomable)
                settings.builtInZoomControls = zoomable
                settings.displayZoomControls = false
                setBackgroundColor(0x00000000)
                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onHeight(px: Int) {
                        if (px > 0) onHeight(px)
                    }

                    @JavascriptInterface
                    fun onSvg(svg: String) {
                        onSvg?.invoke(svg)
                    }

                    @JavascriptInterface
                    fun onReady() {}
                }, "AndroidBridge")
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        val src = JSONObject.quote(card.source)
                        view?.evaluateJavascript("window.__render($src, '$theme')", null)
                    }
                }
                loadUrl("file:///android_asset/www/mermaid_render.html")
            }
        },
        update = { wv ->
            val src = JSONObject.quote(card.source)
            wv.evaluateJavascript("window.__render($src, '$theme')", null)
        }
    )
}

/**
 * 全屏查看：占据整屏，支持双指缩放 / 拖动查看完整图表，并提供下载 SVG、复制源码、关闭。
 */
@Composable
private fun MermaidFullscreen(card: QuroChatCard.MermaidCard, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    var svgRef by remember(card.id) { mutableStateOf<String?>(null) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(color = cs.surface, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        card.title.ifBlank { "流程图" },
                        color = cs.onSurface,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        if (svgRef != null) {
                            val ok = saveSvgToDownloads(context, "mermaid_${card.id}.svg", svgRef!!)
                            Toast.makeText(context, if (ok) "已保存 SVG 到 Download 文件夹" else "保存失败", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "图表尚未渲染完成，请稍候", Toast.LENGTH_SHORT).show()
                        }
                    }, Modifier.size(36.dp)) {
                        Icon(Icons.Filled.Download, "下载 SVG", tint = cs.onSurface, modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { copyText(context, card.source, "已复制 Mermaid 源码") }, Modifier.size(36.dp)) {
                        Icon(Icons.Filled.ContentCopy, "复制源码", tint = cs.onSurface, modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = onDismiss, Modifier.size(36.dp)) {
                        Icon(Icons.Filled.Close, "关闭", tint = cs.onSurface, modifier = Modifier.size(20.dp))
                    }
                }
                HorizontalDivider(color = cs.outlineVariant)
                Box(Modifier.fillMaxSize().padding(8.dp)) {
                    MermaidWebView(
                        card = card,
                        modifier = Modifier.fillMaxSize(),
                        zoomable = true,
                        onSvg = { svgRef = it },
                    )
                }
            }
        }
    }
}

/**
 * AI 运行代码产物（run_code lang=html）的网页预览卡片（v1057）。
 *
 * 与 MermaidCard 同一思路：客户端不内置任何网页，只渲染 AI 通过 `html` 字段下发的完整 HTML 源码。
 * - WebView 用 loadDataWithBaseURL 加载，JS 开启（AI 生成的可信工件，需跑 Chart.js / Three.js 等脚本）。
 * - 渲染完成后按 `document.documentElement.scrollHeight` 自适应高度（上限 720dp，避免无限撑高）。
 * - 顶部提供【全屏 / 复制源码】：全屏页同样走 WebView 自适应高度。
 */
@Composable
private fun HtmlPreviewCardView(card: QuroChatCard.HtmlPreviewCard) {
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current.density
    val context = LocalContext.current
    var heightPx by remember(card.id) { mutableStateOf(360) }
    var fullscreen by remember(card.id) { mutableStateOf(false) }
    val title = card.title.ifBlank { "网页预览（AI 运行产物）" }

    CardShell(
        title = title,
        headerEnd = {
            IconButton(onClick = { fullscreen = true }, Modifier.size(30.dp)) {
                Icon(Icons.Filled.Fullscreen, "全屏查看", tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = { copyText(context, card.html, "已复制网页源码") }, Modifier.size(30.dp)) {
                Icon(Icons.Filled.ContentCopy, "复制源码", tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        },
    ) {
        if (card.html.isBlank()) {
            Text("（无网页内容）", color = cs.onSurfaceVariant, fontSize = 12.sp)
            return@CardShell
        }
        HtmlPreviewWebView(
            html = card.html,
            modifier = Modifier
                .fillMaxWidth()
                .height((heightPx / density).dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White),
            onHeight = { heightPx = it },
        )
    }

    if (fullscreen) {
        Dialog(
            onDismissRequest = { fullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(color = cs.surface, modifier = Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            title,
                            color = cs.onSurface,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { copyText(context, card.html, "已复制网页源码") }, Modifier.size(36.dp)) {
                            Icon(Icons.Filled.ContentCopy, "复制源码", tint = cs.onSurface, modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = { fullscreen = false }, Modifier.size(36.dp)) {
                            Icon(Icons.Filled.Close, "关闭", tint = cs.onSurface, modifier = Modifier.size(20.dp))
                        }
                    }
                    HorizontalDivider(color = cs.outlineVariant)
                    Box(Modifier.fillMaxSize().background(Color.White).padding(8.dp)) {
                        HtmlPreviewWebView(
                            html = card.html,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 可复用的网页预览 WebView：loadDataWithBaseURL 加载 AI 下发的 HTML，
 * JS 开启以支持图表/3D 脚本；onPageFinished 读真实内容高度回调（上限 720dp）。
 */
@Composable
private fun HtmlPreviewWebView(
    html: String,
    modifier: Modifier = Modifier,
    onHeight: (Int) -> Unit = {},
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadsImagesAutomatically = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        view?.evaluateJavascript("document.documentElement.scrollHeight") { value ->
                            val px = value?.replace("\"", "")?.toIntOrNull() ?: return@evaluateJavascript
                            onHeight(px.coerceIn(160, 1440))
                        }
                    }
                }
                tag = html
                loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
        },
        update = { wv ->
            // 仅当 HTML 内容变化时才重载，避免每次 recomposition 重复加载造成闪烁
            if (wv.tag != html) {
                wv.tag = html
                wv.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
        },
    )
}

/** 复制文本到剪贴板并 toast 提示。 */
private fun copyText(context: Context, text: String, toast: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("quro", text))
    Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
}

/** 把 SVG 文本写入公共 Download 目录（API 29+ 用 MediaStore，无需存储权限）。返回是否成功。 */
private fun saveSvgToDownloads(context: Context, fileName: String, svg: String): Boolean {
    return runCatching {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "image/svg+xml")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        }
        val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("无法在 Download 目录创建文件")
        resolver.openOutputStream(uri)?.use { os -> os.write(svg.toByteArray(Charsets.UTF_8)) }
            ?: throw IOException("无法写入 SVG 文件")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        true
    }.getOrDefault(false)
}

/**
 * AI 网络图片气泡：通过 HttpURLConnection 下载 URL 图片并渲染。
 * 使用 inSampleSize 降采样避免 OOM，限制最大高度 260dp。
 */
@Composable
private fun NetworkImageBubble(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }

    LaunchedEffect(url) {
        loading = true
        error = false
        bitmap = null
        runCatching {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 10000
            conn.doInput = true
            conn.connect()
            if (conn.responseCode == 200) {
                val inputStream = conn.inputStream
                val opts = android.graphics.BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                android.graphics.BitmapFactory.decodeStream(inputStream, null, opts)
                inputStream.close()
                val targetW = (context.resources.displayMetrics.density * 280).toInt()
                val targetH = (context.resources.displayMetrics.density * 260).toInt()
                var sampleSize = 1
                while (opts.outWidth / (sampleSize * 2) >= targetW && opts.outHeight / (sampleSize * 2) >= targetH) {
                    sampleSize *= 2
                }
                val conn2 = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn2.connectTimeout = 8000
                conn2.readTimeout = 10000
                conn2.doInput = true
                conn2.connect()
                if (conn2.responseCode == 200) {
                    val decodeOpts = android.graphics.BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                    }
                    bitmap = android.graphics.BitmapFactory.decodeStream(conn2.inputStream, null, decodeOpts)
                }
                conn2.disconnect()
            }
            conn.disconnect()
        }.onFailure {
            error = true
        }
        loading = false
    }

    Box(
        modifier
            .heightIn(max = 260.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFF0F0F0))
    ) {
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
            }
            error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.BrokenImage, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
            }
            bitmap != null -> {
                val b = bitmap!!
                Image(
                    bitmap = b.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
