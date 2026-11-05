package com.ai.assistance.quro.ui.chat

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.R
import com.ai.assistance.quro.core.chat.ContextSegmentKind
import com.ai.assistance.quro.core.chat.ContextWindowUsage
import com.ai.assistance.quro.util.qstr

/**
 *
 *
 * ```
 * 上下文窗口
 * 14% 已用                              17.5K / 128K
 * ▓▓▓░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░   ← 多色堆叠条
 * ■ 消息              211        0.2%
 * ■ 系统工具          13.5K     10.5%
 * ■ 技能              419        0.3%
 * ■ 系统提示词        1.5K       1.2%
 * ■ 记忆文件          1.8K       1.4%
 * ■ Bot 目录          59         0.0%
 * ─────────────────────────────
 * ■ 自动压缩预留      41.0K     32.0%
 * ■ 剩余空间          69.6K     54.3%
 *        关闭        压缩上下文
 * ```
 *
 * 与参考一致的关键点：
 * - 顶部是**多色堆叠条**（每段一个颜色），不是单色进度条 —— 一眼看出谁在吃窗口；
 * - 每段一行：**色块 + 名称 + token 数 + 百分比**；
 * - 底部两行是**预留**与**剩余**，用分隔线与实际占用隔开；
 * - 底部按钮「关闭」/「压缩上下文」。
 */
@Composable
fun ContextWindowSheet(
    usage: ContextWindowUsage,
    onDismiss: () -> Unit,
    /** 压缩是否正在进行（true 时显示「压缩中…」，按钮变成「停止压缩」）。 */
    compactionRunning: Boolean = false,
    /** 触发 / 停止摘要压缩。 */
    onCompact: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val window = usage.window
    val used = usage.used
    val reserved = usage.reservedForReply
    val free = usage.free
    val total = used + reserved + free

    val segColors = mapOf(
        ContextSegmentKind.MESSAGES to Color(0xFF3B82F6),
        ContextSegmentKind.SYSTEM_TOOLS to Color(0xFFF97316),
        ContextSegmentKind.MCP_TOOLS to Color(0xFF06B6D4),
        ContextSegmentKind.SKILLS to Color(0xFFEAB308),
        ContextSegmentKind.SYSTEM_PROMPT to Color(0xFF9CA3AF),
        ContextSegmentKind.MEMORY to Color(0xFF8B5CF6),
        ContextSegmentKind.INJECTED to Color(0xFFEC4899),
        ContextSegmentKind.OTHER to Color(0xFF6B7280),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Row {
                if (compactionRunning) {
                    TextButton(onClick = onCompact) { Text(qstr(R.string.qk_04525)) }
                } else {
                    TextButton(onClick = onCompact) { Text(qstr(R.string.qk_04524)) }
                }
                TextButton(onClick = onDismiss) { Text(qstr(R.string.qk_04465)) }
            }
        },
        title = {
            Text(
                qstr(R.string.qk_04452),
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // ── 概览：N% 已用  +  used / window ──
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Text(
                        if (window > 0) "${(used.toFloat() / window * 100).toInt()}% ${qstr(R.string.qk_04452_used)}"
                        else qstr(R.string.qk_04452_used),
                        fontSize = 15.sp,
                        color = cs.onSurface,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${fmt(used)} / ${fmt(window)}",
                        fontSize = 14.sp,
                        color = cs.onSurface.copy(alpha = 0.72f),
                    )
                }
                Spacer(Modifier.height(8.dp))

                // ── 多色堆叠条 ──
                if (window > 0 && total > 0) {
                    // 打开弹层时从 0 → 实际比例的展开动画（600ms 减速曲线），数据加载感更平滑。
                    var segAnimPlayed by remember { mutableStateOf(false) }
                    val segProgress by animateFloatAsState(
                        targetValue = if (segAnimPlayed) 1f else 0f,
                        animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
                        label = "ctxSegProgress",
                    )
                    LaunchedEffect(Unit) { segAnimPlayed = true }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(cs.surfaceVariant),
                    ) {
                        // 实际占用：按类别分段着色
                        usage.segments.forEach { seg ->
                            if (seg.tokens <= 0) return@forEach
                            val w = seg.tokens.toFloat() / total * segProgress
                            Box(
                                Modifier
                                    .weight(w.coerceAtLeast(0.0001f))
                                    .fillMaxWidth()
                                    .height(10.dp)
                                    .background(segColors[seg.kind] ?: Color(0xFF6B7280)),
                            )
                        }
                        // 自动压缩预留（灰色实心）
                        if (reserved > 0) {
                            val rw = reserved.toFloat() / total * segProgress
                            Box(
                                Modifier
                                    .weight(rw.coerceAtLeast(0.0001f))
                                    .fillMaxWidth()
                                    .height(10.dp)
                                    .background(Color(0xFF9CA3AF).copy(alpha = 0.55f)),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ── 分类明细 ──
                usage.segments.forEach { seg ->
                    val share = if (window > 0) seg.tokens.toFloat() / window else 0f
                    SegmentRow(
                        color = segColors[seg.kind] ?: Color(0xFF6B7280),
                        label = segmentLabel(seg.kind),
                        tokens = fmt(seg.tokens),
                        percent = "${(share * 100).toInt()}%",
                    )
                }
                if (usage.segments.isEmpty()) {
                    Text(
                        qstr(R.string.qk_04463),
                        fontSize = 12.sp,
                        color = cs.onSurface.copy(alpha = 0.55f),
                    )
                }

                // ── 分隔线 ──
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(cs.outlineVariant.copy(alpha = 0.35f)),
                )
                Spacer(Modifier.height(6.dp))

                // ── 自动压缩预留 + 剩余空间 ──
                SegmentRow(
                    color = Color(0xFF9CA3AF),
                    label = qstr(R.string.qk_04452_reserved),
                    tokens = fmt(reserved),
                    percent = if (window > 0) "${(reserved.toFloat() / window * 100).toInt()}%" else "—",
                )
                SegmentRow(
                    color = Color(0xFFE5E7EB),
                    label = qstr(R.string.qk_04452_free),
                    tokens = fmt(free),
                    percent = if (window > 0) "${(free.toFloat() / window * 100).toInt()}%" else "—",
                )

                Spacer(Modifier.height(8.dp))
                Text(
                    qstr(R.string.qk_04466),
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    color = cs.onSurface.copy(alpha = 0.45f),
                )
            }
        },
    )
}

/** 分类行：色块 + 名称 + token 数 + 百分比。 */
@Composable
private fun SegmentRow(color: Color, label: String, tokens: String, percent: String) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(11.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            fontSize = 13.sp,
            color = cs.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            tokens,
            fontSize = 13.sp,
            color = cs.onSurface.copy(alpha = 0.72f),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            percent,
            fontSize = 13.sp,
            color = cs.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.width(48.dp),
        )
    }
}

/** 上下文用量还没算出来时的提示（不弹空白卡片）。 */
@Composable
fun ContextWindowUnavailableDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(qstr(R.string.qk_04465)) }
        },
        title = { Text(qstr(R.string.qk_04452), fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Text(
                qstr(R.string.qk_04452_unavailable),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            )
        },
    )
}

/** token 数的展示格式：k 级以上不再显示个位，避免「估算值假装精确」。 */
private fun fmt(tokens: Int): String = when {
    tokens <= 0 -> "0"
    tokens < 1000 -> tokens.toString()
    tokens < 100_000 -> String.format("%.1fK", tokens / 1000.0)
    else -> String.format("%.0fK", tokens / 1000.0)
}

private fun segmentLabel(kind: ContextSegmentKind): String = when (kind) {
    ContextSegmentKind.SYSTEM_PROMPT -> qstr(R.string.qk_04456)
    ContextSegmentKind.SYSTEM_TOOLS -> qstr(R.string.qk_04457)
    ContextSegmentKind.MCP_TOOLS -> qstr(R.string.qk_04458)
    ContextSegmentKind.SKILLS -> qstr(R.string.qk_04459)
    ContextSegmentKind.MEMORY -> qstr(R.string.qk_04460)
    ContextSegmentKind.MESSAGES -> qstr(R.string.qk_04461)
    ContextSegmentKind.INJECTED -> qstr(R.string.qk_04462)
    ContextSegmentKind.OTHER -> qstr(R.string.qk_04463)
}
