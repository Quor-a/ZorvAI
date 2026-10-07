package com.ai.assistance.quro.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.R
import com.ai.assistance.quro.core.model.QuroToolSummary
import com.ai.assistance.quro.ui.data.ToolCallUi
import com.ai.assistance.quro.ui.icons.LucideIcon
import com.ai.assistance.quro.util.qstr

/**
 * 工具族视觉映射：图标 + 主题色。
 *
 * 🔴 图标名必须与 `LucideIcon.kt` 的 `when` 分支**逐一对应**，否则会静默退化成 X 图标。
 * 这正是旧实现 `toolCategory` 用 `"terminal"` / `"globe"` / `"folder-open"` 却全都显示成 X 的原因 ——
 * 本仓 drawable 只有 34 个 `ic_*` 资源，这些名字一个都不存在。
 */
private data class FamilyStyle(val icon: String, val color: Color)

private fun familyStyle(f: QuroToolSummary.Family): FamilyStyle = when (f) {
    QuroToolSummary.Family.CODE -> FamilyStyle("qic_code_run", Color(0xFF8B5CF6))
    QuroToolSummary.Family.FILE_WRITE -> FamilyStyle("qic_file_write", Color(0xFF0EA5E9))
    QuroToolSummary.Family.FILE_READ -> FamilyStyle("qic_file_read", Color(0xFF64748B))
    QuroToolSummary.Family.WEB -> FamilyStyle("qic_web", Color(0xFF10B981))
    QuroToolSummary.Family.TERMINAL -> FamilyStyle("qic_terminal", Color(0xFFF59E0B))
    QuroToolSummary.Family.DEVICE -> FamilyStyle("qic_device", Color(0xFF6366F1))
    QuroToolSummary.Family.SYSTEM -> FamilyStyle("qic_system", Color(0xFFEF4444))
    QuroToolSummary.Family.DOC -> FamilyStyle("qic_doc", Color(0xFF14B8A6))
    QuroToolSummary.Family.MEDIA -> FamilyStyle("qic_media", Color(0xFFEC4899))
    QuroToolSummary.Family.MEMORY -> FamilyStyle("qic_memory", Color(0xFF06B6D4))
    QuroToolSummary.Family.UI -> FamilyStyle("qic_ui", Color(0xFFA855F7))
    QuroToolSummary.Family.OTHER -> FamilyStyle("qic_other", Color(0xFF94A3B8))
}

private fun statusColor(s: QuroToolSummary.Status): Color = when (s) {
    QuroToolSummary.Status.SUCCESS -> Color(0xFF22C55E)
    QuroToolSummary.Status.ERROR -> Color(0xFFEF4444)
    QuroToolSummary.Status.WARNING -> Color(0xFFF59E0B)
    QuroToolSummary.Status.RUNNING -> Color(0xFF3B82F6)
    QuroToolSummary.Status.INFO -> Color(0xFF94A3B8)
}

private fun statusIcon(s: QuroToolSummary.Status): String = when (s) {
    QuroToolSummary.Status.SUCCESS -> "qic_ok"
    QuroToolSummary.Status.ERROR -> "qic_fail"
    QuroToolSummary.Status.WARNING -> "qic_warn"
    QuroToolSummary.Status.RUNNING -> "qic_running"
    QuroToolSummary.Status.INFO -> "qic_info"
}

/** 耗时格式化：>0 才显示。 */
private fun fmtDuration(ms: Long): String? = when {
    ms <= 0 -> null
    ms < 1000 -> "${ms}ms"
    else -> String.format("%.1fs", ms / 1000.0)
}

/**
 * 单条工具调用的完整卡片。
 *
 * ## 设计（对齐 Claude Code / Cursor / Cline 的通行范式）
 * ```
 * [族图标] write_file        …/Documents/notes.md      [4 行] [512B]  [成功] [38ms]  v
 * ── 展开后 ──
 * 参数 · 返回 · 全文（等宽字体、可横向滚动）
 * ```
 *
 * **折叠态就能判断「它到底干了什么」** —— 这是旧实现最大的缺失：
 * 旧版折叠态只有「工具名 + 57 字符截断 JSON」，用户必须逐个点开才知道细节。
 */
@Composable
fun ToolCallRichCard(
    t: ToolCallUi,
    modifier: Modifier = Modifier,
    defaultExpanded: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val sum = remember(t.name, t.args, t.result, t.durationMs) {
        QuroToolSummary.of(t.name, t.args, t.result, t.durationMs)
    }
    val style = familyStyle(sum.family)
    var expanded by remember(t.name, t.args, t.result) { mutableStateOf(defaultExpanded) }

    val sc = statusColor(sum.status)
    val dur = fmtDuration(sum.durationMs)

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (sum.status == QuroToolSummary.Status.RUNNING) style.color.copy(alpha = 0.05f) else cs.surfaceVariant.copy(alpha = 0.35f))
            .border(
                0.5.dp,
                if (sum.status == QuroToolSummary.Status.ERROR) Color(0xFFEF4444).copy(alpha = 0.45f)
                else cs.outlineVariant.copy(alpha = 0.35f),
                RoundedCornerShape(12.dp),
            )
    ) {
        // ── 头部：图标 + 工具名 + 副标题 + 指标 + 状态 + 耗时 ──
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(start = 10.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(20.dp).clip(RoundedCornerShape(6.dp))
                    .background(style.color.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                LucideIcon(style.icon, null, Modifier.size(12.dp), tint = style.color)
            }
            Spacer(Modifier.width(7.dp))
            Text(t.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface, maxLines = 1)

            // 🔴 副标题 = 「目标物」，这是本次升级的核心（旧版完全没有这一项）
            if (sum.subtitle.isNotBlank()) {
                Spacer(Modifier.width(7.dp))
                Text(
                    sum.subtitle,
                    fontSize = 11.sp,
                    color = style.color.copy(alpha = 0.95f),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).padding(end = 4.dp),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }

            // 指标徽标：行数 / 字节 / 退出码 / 结果数
            sum.metrics.take(3).forEach { m ->
                MetricChip(m.label, m.value, style.color)
                Spacer(Modifier.width(4.dp))
            }

            // 状态
            if (sum.status == QuroToolSummary.Status.RUNNING) {
                val pulse by rememberInfiniteTransition().animateFloat(
                    initialValue = 0.3f, targetValue = 1f,
                    infiniteRepeatable(tween(700), RepeatMode.Reverse),
                )
                Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFF3B82F6).copy(alpha = pulse)))
            } else {
                LucideIcon(statusIcon(sum.status), null, Modifier.size(13.dp), tint = sc)
            }

            // 耗时（旧版有数据但从不显示）
            dur?.let {
                Spacer(Modifier.width(5.dp))
                Text(it, fontSize = 9.sp, color = cs.onSurface.copy(alpha = 0.5f))
            }

            Spacer(Modifier.width(4.dp))
            LucideIcon(if (expanded) "chevron_up" else "chevron_down", null, Modifier.size(13.dp), tint = cs.onSurface.copy(alpha = 0.4f))
        }

        // ── 展开体 ──
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(180)) + fadeIn(),
            exit = shrinkVertically(tween(150)) + fadeOut(),
        ) {
            Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) {
                HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.25f))
                Spacer(Modifier.height(8.dp))

                // 参数
                if (t.args.isNotBlank() && t.args.trim() != "{}") {
                    SectionLabel(qstr(R.string.qk_00142))
                    ToolBodyText(t.args, JsonMode)
                    Spacer(Modifier.height(8.dp))
                }

                // 返回（按族选择渲染器）
                if (sum.body != QuroToolSummary.Body.EMPTY && sum.bodyText.isNotBlank()) {
                    SectionLabel(qstr(R.string.qk_00084))
                    ToolBodyText(sum.bodyText, sum.body)
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 9.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(bottom = 3.dp),
    )
}

/** 指标小胶囊。 */
@Composable
private fun MetricChip(label: String, value: String, tint: Color) {
    Row(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(tint.copy(alpha = 0.1f))
            .padding(horizontal = 4.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 8.sp, color = tint.copy(alpha = 0.8f))
        Spacer(Modifier.width(2.dp))
        Text(value, fontSize = 9.sp, color = tint, fontWeight = FontWeight.SemiBold)
    }
}

/** 参数区固定按 JSON 展示（不用摘要层的 body）。 */
private val JsonMode = QuroToolSummary.Body.JSON/**
 * 正文渲染：按 [QuroToolSummary.Body] 选呈现方式。
 *
 * 🔴 旧实现在这里做了 `take(57) + 省略号` 的截断 —— 用户抱怨「不知道执行了什么代码」
 * 就是这一行造成的。这里改为：**默认全文，最多 400 行**（再长则明确告知已截断，
 * 而不是假装内容就这么多）。
 */
@Composable
private fun ToolBodyText(
    text: String,
    mode: QuroToolSummary.Body,
) {
    val cs = MaterialTheme.colorScheme
    val maxLines = 400
    val lines = remember(text, maxLines) { text.lines() }
    val truncated = lines.size > maxLines
    val shown = if (truncated) lines.take(maxLines) else lines
    val isMonospace = mode == QuroToolSummary.Body.CODE ||
        mode == QuroToolSummary.Body.DIFF ||
        mode == QuroToolSummary.Body.JSON

    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(cs.surface.copy(alpha = 0.6f))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            Text(
                text = shown.joinToString("\n"),
                fontSize = if (isMonospace) 11.sp else 12.sp,
                lineHeight = if (isMonospace) 16.sp else 17.sp,
                fontFamily = if (isMonospace) FontFamily.Monospace else FontFamily.Default,
                color = if (mode == QuroToolSummary.Body.DIFF) cs.onSurface else cs.onSurface.copy(alpha = 0.88f),
            )
        }
        if (truncated) {
            Spacer(Modifier.height(4.dp))
            Text(
                qstr(R.string.qk_03915, lines.size.toString()),
                fontSize = 9.sp,
                color = cs.onSurface.copy(alpha = 0.55f),
            )
        }
    }
}

/**
 * 工具调用组：标题栏（聚合状态）+ 逐条卡片 + 可选执行轨迹。
 *
 * 与旧 [ToolsInlineContent] 的差别就是核心升级：
 * 旧版把 N 个工具塞进一个「· N 工具」折叠块，展开后每条只有两行 57 字符；
 * 现在每条工具都是独立可展开的卡片，折叠态就能看懂目标物 + 指标 + 耗时 + 状态。
 */
@Composable
fun ToolCallGroup(
    tools: List<ToolCallUi>,
    modifier: Modifier = Modifier,
    traceContent: (@Composable () -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    if (tools.isEmpty()) return

    // 聚合状态：任一失败即整组标红
    val agg = remember(tools) {
        val all = tools.map { QuroToolSummary.of(it.name, it.args, it.result, it.durationMs).status }
        when {
            all.any { it == QuroToolSummary.Status.RUNNING } -> QuroToolSummary.Status.RUNNING
            all.any { it == QuroToolSummary.Status.ERROR } -> QuroToolSummary.Status.ERROR
            all.any { it == QuroToolSummary.Status.WARNING } -> QuroToolSummary.Status.WARNING
            all.all { it == QuroToolSummary.Status.SUCCESS } -> QuroToolSummary.Status.SUCCESS
            else -> QuroToolSummary.Status.INFO
        }
    }
    val totalMs = tools.sumOf { it.durationMs }
    val running = tools.any { it.result.isNullOrBlank() }

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(cs.surfaceVariant.copy(alpha = 0.25f))
            .border(0.5.dp, cs.outlineVariant.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 组标题
        Row(verticalAlignment = Alignment.CenterVertically) {
            LucideIcon(
                if (running) "play" else "blocks", null, Modifier.size(13.dp),
                tint = if (running) Color(0xFF3B82F6) else cs.primary,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                qstr(R.string.qk_00151, tools.size.toString()),
                fontSize = 11.sp,
                color = cs.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            if (totalMs > 0) {
                Text(fmtDuration(totalMs) ?: "", fontSize = 9.sp, color = cs.onSurface.copy(alpha = 0.5f))
                Spacer(Modifier.width(6.dp))
            }
            Box(Modifier.size(7.dp).clip(CircleShape).background(statusColor(agg)))
        }

        tools.forEach { t ->
            ToolCallRichCard(t)
        }

        traceContent?.invoke()
    }
}
