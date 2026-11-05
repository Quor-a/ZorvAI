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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.core.agent.QuroAgentTrace
import com.ai.assistance.quro.core.canvas.Aip
import com.ai.assistance.quro.core.tools.QuroUiActionBridge
import com.ai.assistance.quro.ui.canvas.AipCanvas
import com.ai.assistance.quro.ui.data.ToolCallUi
import com.ai.assistance.quro.ui.icons.LucideIcon
import com.ai.assistance.quro.ui.theme.Accent
import com.ai.assistance.quro.ui.theme.InkSoft
import com.ai.assistance.quro.ui.theme.Line
import com.ai.assistance.quro.ui.theme.Line2
import com.ai.assistance.quro.ui.theme.Muted

/**
 * 工具调用 / 思考过程的**底层渲染组件**。
 *
 * ## 活动流主结构（2026-11-03 全面重做后的现状）
 *
 * 消息流里，工具与思考是**一条活动流**：
 * ```
 * 思考、运行了 6 条命令、操作了 1 次浏览器、…     ← 汇总行
 * 🧠 思考了 1秒                                  ← 逐条（失败红字）
 * >_ 探测本地 MCP 端点
 * ```
 * 点任意一条 → 底部弹层看该次细节。
 *
 * 这套**主结构**现在由以下两个新文件承载：
 * - `core/chat/ActivityStream.kt`：活动类别枚举 + 条目模型 + `display_intent` 提取；
 * - `ui/chat/ActivityStreamView.kt`：汇总行 + 逐条列表；
 * - `ui/chat/ActivityDetailSheet.kt`：按类别分派的详情弹层。
 *
 * 本文件保留的是**底层能力**（供上述组件与富组件卡片调用）：
 * - [toolCategory]：工具名 → 类目（图标 + 颜色 + 中文标签）；
 * - [detectResultStatus] / [parseRiskLevel]：结果状态与风险级别判定；
 * - [FormattedResultContent]：结果美化渲染（JSON 键值对 / 列表项目符号 / `[标签]` 内联）。
 *
 * 旧的 [ToolsInlineContent] / [ToolCallDetailCard] 已随活动流改造一并移除
 * （前者已无调用点，后者的能力迁移进了 ActivityDetailSheet）。
 *
 * ## 图标名的硬约束
 * [LucideIcon] 的 `when` 是**精确匹配**，落到 `else` 会返回 `ic_x`（界面上显示成 ×）。
 * 本文件所有图标名都必须是本仓 `LucideIcon.kt` 里真实存在的分支，
 * 已逐一核对：qic_device / qic_system / qic_terminal / qic_web / qic_doc / qic_memory /
 * qic_media / qic_code_run / qic_file_read / qic_file_write / qic_ui / qic_other /
 * qic_ok / qic_fail / qic_warn / qic_info / file_text / image / video / bot / bell /
 * layout_dashboard / folder / settings / sparkles / blocks / code / link /
 * chevron_up / chevron_down / chevron_right。
 */

// ── 工具类目 ─────────────────────────────────────────────────────────

/** 工具类目：图标名 + 主题色 + 中文标签。 */
data class ToolCategory(val icon: String, val color: Color, val label: String)

/**
 * 工具名 → 类目。
 *
 * 🔴 图标名必须命中本仓 `LucideIcon` 的真实分支，否则 fallback 成 `ic_x`（显示成 ×）。
 */
fun toolCategory(name: String): ToolCategory = when {
    name == "aip_compose" || name == "aiwps_create" || name == "enhanced_doc_create" || name.contains("doc") ->
        ToolCategory("file_text", Color(0xFF0EA5E9), "文档排版")

    name.startsWith("read_screen") || name.startsWith("tap") || name.startsWith("swipe") ||
        name.startsWith("input_text") || name.startsWith("scroll") || name.startsWith("global_action") ||
        name.startsWith("get_foreground") || name.startsWith("get_screen_state") ->
        ToolCategory("qic_device", Color(0xFF6366F1), "无障碍控屏")

    name.contains("shizuku") || name.contains("root_exec") || name.contains("root_status") ||
        name.contains("device_admin") || name.contains("lock_screen") || name.contains("set_camera") ->
        ToolCategory("qic_system", Color(0xFFEF4444), "系统权限")

    name.contains("terminal") || name.contains("run_shell") || name.startsWith("linux_") ||
        name.startsWith("open_12306") || name == "home" || name == "open_app" ->
        ToolCategory("qic_terminal", Color(0xFFF59E0B), "终端执行")

    name == "cms_list" || name == "cms_call" || name == "priv_status" ||
        name == "get_device_info" || name.contains("draw_qwen") ||
        name == "open_repo" || name == "open_calendar" || name == "set_alarm" ->
        ToolCategory("settings", Color(0xFF06B6D4), "系统能力")

    name.contains("list_dir") || name.contains("read_file") || name.contains("write_file") ||
        name.contains("file_") || name.contains("download") ->
        ToolCategory("folder", Color(0xFF8B5CF6), "文件工具")

    name.contains("web_search") || name.contains("open_url") || name.startsWith("web_") ->
        ToolCategory("qic_web", Color(0xFF10B981), "网络能力")

    name == "echo_step" ->
        ToolCategory("bell", Color(0xFFEC4899), "提醒与步骤")

    name.startsWith("ui_open_") || name.startsWith("ui_toggle_") ||
        name.startsWith("ui_clear_") || name.startsWith("ui_new_") ->
        ToolCategory("qic_ui", Color(0xFF14B8A6), "界面控制")

    name.contains("image_gen") || name.contains("generate_image") || name.contains("text_to_image") ->
        ToolCategory("image", Color(0xFFEC4899), "图像生成")

    name.contains("video_gen") || name.contains("generate_video") ->
        ToolCategory("video", Color(0xFF8B5CF6), "视频生成")

    name.contains("memory_save") || name.contains("memory_list") || name.contains("memory_search") || name.contains("memory_delete") ->
        ToolCategory("qic_memory", Color(0xFF06B6D4), "记忆库")

    name.contains("summary") || name.contains("context") ->
        ToolCategory("qic_doc", Color(0xFF14B8A6), "上下文总结")

    name.contains("incubate") || name == "persona_hatch" ->
        ToolCategory("bot", Color(0xFFF59E0B), "人格孵化")

    name.contains("code") || name.contains("script") ->
        ToolCategory("qic_code_run", Color(0xFF8B5CF6), "代码执行")

    else -> ToolCategory("qic_other", Color(0xFF64748B), "工具")
}

// ── 结果状态与风险级别 ───────────────────────────────────────────────

enum class ResultStatus { SUCCESS, ERROR, WARNING, INFO }

/**
 * 结果状态判定。
 *
 * 只扫前 200 字符：正文里偶然出现「失败/error」字样的成功结果不该被误标红。
 */
fun detectResultStatus(result: String): ResultStatus {
    val head = result.take(200)
    return when {
        result.startsWith("❌") || result.startsWith("✗") || head.contains("失败") || head.contains("error", ignoreCase = true) -> ResultStatus.ERROR
        result.startsWith("⚠️") || result.startsWith("⚠") || head.contains("警告") || head.contains("warning", ignoreCase = true) -> ResultStatus.WARNING
        result.startsWith("✅") || result.startsWith("✔") || head.contains("成功") -> ResultStatus.SUCCESS
        else -> ResultStatus.INFO
    }
}

private val RE_RISK_LEVEL = Regex("""风险级别[：:]\s*(\S+)""")

data class RiskLevel(val label: String, val color: Color, val bgAlpha: Float)

/** 从结果文本里解析风险级别标记（结果里带「风险级别：xxx」时显示彩色徽标）。 */
@Composable
private fun parseRiskLevel(text: String): RiskLevel? {
    val match = RE_RISK_LEVEL.find(text) ?: return null
    return when (match.groupValues[1].lowercase()) {
        "critical", "高危" -> RiskLevel("高危", Color(0xFFEF4444), 0.18f)
        "warning", "中危" -> RiskLevel("中危", Color(0xFFF59E0B), 0.16f)
        "normal", "low" -> RiskLevel("Normal", Color(0xFF22C55E), 0.14f)
        "safe", "安全" -> RiskLevel("安全", Color(0xFF06B6D4), 0.14f)
        else -> RiskLevel(match.groupValues[1], Muted, 0.12f)
    }
}

private val RE_BRACKET = Regex("""\[([^\]]+)\]\s*(.*)""")

private fun formatJsonValue(v: String): String = when {
    v.length > 60 -> "${v.take(57)}…"
    v.isEmpty() -> "\"\""
    else -> v
}

private fun statusColorOf(s: ResultStatus): Color = when (s) {
    ResultStatus.SUCCESS -> Color(0xFF22C55E)
    ResultStatus.ERROR -> Color(0xFFEF4444)
    ResultStatus.WARNING -> Color(0xFFF59E0B)
    ResultStatus.INFO -> Color(0xFF64748B)
}

private fun statusIconOf(s: ResultStatus): String = when (s) {
    ResultStatus.SUCCESS -> "qic_ok"
    ResultStatus.ERROR -> "qic_fail"
    ResultStatus.WARNING -> "qic_warn"
    ResultStatus.INFO -> "qic_info"
}

// ── 结果格式化 ───────────────────────────────────────────────────────

/**
 * 格式化输出结果：JSON → 键值对渲染；列表 → 项目符号；纯文本 → 内联 [标签] 渲染。
 *
 * 公开（不再 private）：活动流的详情弹层（ActivityDetailSheet）也用它来渲染「结果」段。
 */
@Composable
fun FormattedResultContent(result: String, scaled: (Int) -> TextUnit) {
    val cs = MaterialTheme.colorScheme
    val lines = result.lines()
    val isListLike = lines.size > 1 && lines.count { it.trimStart().startsWith("- ") || it.trimStart().startsWith("→ ") } >= lines.size / 2
    val isJson = runCatching { org.json.JSONObject(result); true }.getOrElse { false }

    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(cs.surfaceContainerLowest)
            .verticalScroll(rememberScrollState())
            .padding(10.dp)
    ) {
        when {
            isJson -> JsonFormattedText(result, scaled)
            isListLike -> {
                Column {
                    lines.filter { it.isNotBlank() }.forEach { line ->
                        val trimmed = line.trim()
                        val isBullet = trimmed.startsWith("- ") || trimmed.startsWith("→ ")
                        Row(Modifier.padding(vertical = 0.5.dp)) {
                            if (isBullet) {
                                Text("• ", fontSize = scaled(11), color = cs.primary, fontWeight = FontWeight.Bold)
                                val content = trimmed.removePrefix("- ").removePrefix("→ ")
                                RenderInlineFormatted(content, scaled, cs)
                            } else {
                                Text("  ", fontSize = scaled(11))
                                RenderInlineFormatted(trimmed, scaled, cs)
                            }
                        }
                    }
                }
            }
            else -> {
                Column {
                    lines.filterIndexed { i, s -> s.isNotBlank() || i < lines.lastIndex }.forEach { line ->
                        if (line.isBlank()) {
                            Spacer(Modifier.height(4.dp))
                        } else {
                            RenderInlineFormatted(line.trim(), scaled, cs)
                            Spacer(Modifier.height(1.dp))
                        }
                    }
                }
            }
        }
    }
}

/** 渲染带 [方括号] 标签的内联文本。 */
@Composable
private fun RenderInlineFormatted(text: String, scaled: (Int) -> TextUnit, cs: androidx.compose.material3.ColorScheme) {
    val match = RE_BRACKET.matchEntire(text)
    if (match != null) {
        val tag = match.groupValues[1]
        val rest = match.groupValues[2]
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("[", fontSize = scaled(11), color = Muted)
            Text(tag, fontSize = scaled(11), color = Accent, fontWeight = FontWeight.Medium)
            Text("]", fontSize = scaled(11), color = Muted)
            if (rest.isNotBlank()) {
                Spacer(Modifier.width(3.dp))
                Text(rest, fontSize = scaled(11), color = cs.onSurfaceVariant)
            }
        }
    } else {
        Text(text, fontSize = scaled(11), color = cs.onSurfaceVariant, lineHeight = scaled(17))
    }
}

@Composable
private fun JsonFormattedText(jsonStr: String, scaled: (Int) -> TextUnit) {
    val cs = MaterialTheme.colorScheme
    val jsonPairs = remember(jsonStr) {
        runCatching {
            val obj = org.json.JSONObject(jsonStr)
            obj.keys().asSequence().map { key -> key to obj.optString(key, "").take(200) }.toList()
        }.getOrDefault(null)
    }

    if (jsonPairs != null) {
        Column {
            jsonPairs.forEachIndexed { idx, (key, value) ->
                Row(Modifier.padding(vertical = 1.dp), verticalAlignment = Alignment.Top) {
                    Text(key, fontSize = scaled(11), color = Accent, fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace)
                    Text(": ", fontSize = scaled(11), color = Muted, fontFamily = FontFamily.Monospace)
                    Text(formatJsonValue(value), fontSize = scaled(11),
                        color = cs.onSurfaceVariant, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f))
                }
                if (idx < jsonPairs.size - 1) Spacer(Modifier.height(2.dp))
            }
        }
    } else {
        Text(jsonStr, fontSize = scaled(11), fontFamily = FontFamily.Monospace,
            color = InkSoft, lineHeight = scaled(17))
    }
}
