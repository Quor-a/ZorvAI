package com.ai.assistance.quro.ui.chat

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.core.chat.ActivityCategory
import com.ai.assistance.quro.core.chat.ActivityEntry
import com.ai.assistance.quro.ui.icons.LucideIcon
import org.json.JSONObject

/**
 * 单次工具调用的详情弹层。
 *
 * ## 按类别分派
 *
 * 不同类型的工具，细节的形态完全不同，混在一个「参数/结果」JSON 框里没法看：
 *
 * | 类别 | 详情形态 |
 * |---|---|
 * | 命令类 | 工作目录 chip + exit/失败/执行中徽标 + 耗时 + 等宽命令 + 输出 |
 * | 写/改文件 | 文件路径 + 大小 + 带行号的内容 |
 * | 搜索类 | 搜索关键词 chip + 结果列表 |
 * | 思考 | 完整推理文本 |
 * | 其它 | 参数 / 结果 / 错误 三段（等宽、可滚动） |
 *
 * ## 🔴 动作按钮：按工具**深度适配**，不是「全部塞进 IDE」
 *
 * 调用涉及工作区 → 直接打开那个工作区；
 * 图片/视频生成 → 直接打开那/generated的媒体；终端命令 → 直接打开终端。
 * 旧实现给几十种工具全部挂同一个「在 IDE 中打开」——点哪个都是弹同一个文本编辑器，
 * 既不是「对应物」，对大多数工具（搜网页/存记忆/操作屏幕）也毫无意义。
 *
 * 现在改为 [deepLinkActionsFor]：按工具名 + 参数提取**直达对应界面/对应物的动作**，
 * 没有明确对应物的工具就**不挂打开按钮**（只保留「复制全部」）。
 *
 * @param entry 要展示的那一条。
 * @param onDismiss 关闭。
 * @param onOpenEditor 打开文本编辑器（仅纯文本编辑类工具使用）。
 * @param onOpenTerminal 打开终端面板（命令类工具）。
 * @param onOpenWorkspace 打开工作区代码浏览（文件类工具，落到该工具参数里的路径）。
 * @param onOpenImage 打开全屏图片查看器（图片生成/标记类工具产物）。
 * @param onOpenVideo 打开应用内视频播放器（视频生成类工具产物）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityDetailSheet(
    entry: ActivityEntry,
    onDismiss: () -> Unit,
    onOpenEditor: () -> Unit,
    onOpenTerminal: () -> Unit = {},
    onOpenWorkspace: (String?) -> Unit = {},
    onOpenImage: (String) -> Unit = {},
    onOpenVideo: (String) -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val cs = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    val detailText = remember(entry) {
        buildString {
            appendLine(entry.title)
            appendLine()
            appendLine("参数：")
            appendLine(prettifyJson(entry.args))
            if (!entry.result.isNullOrBlank()) {
                appendLine()
                appendLine("结果：")
                appendLine(entry.result)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = cs.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp),
        ) {
            // ── 标题 = 那一条的描述文案（与列表行完全一致，点进来能对上）──
            //    右侧动作区：复制全部 + 按工具深度适配的直达动作（见 deepLinkActionsFor）。
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.title,
                    Modifier.weight(1f),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (entry.failed) Color(0xFFE5484D) else cs.onSurface,
                )
                TextButton(onClick = { clipboard.setText(AnnotatedString(detailText)) }) {
                    Text("复制全部", fontSize = 12.sp)
                }
            }
            // ── 深度适配动作行：直达该工具真正对应的界面/产物 ──
            val deepActions = remember(entry) {
                deepLinkActionsFor(entry, onOpenEditor, onOpenTerminal, onOpenWorkspace, onOpenImage, onOpenVideo)
            }
            if (deepActions.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    deepActions.forEach { act ->
                        TextButton(
                            onClick = {
                                onDismiss()
                                act.action()
                            },
                        ) {
                            LucideIcon(act.icon, null, Modifier.size(12.dp), tint = cs.primary)
                            Spacer(Modifier.width(4.dp))
                            Text(act.label, fontSize = 12.sp, color = cs.primary)
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))

            when (entry.category) {
                ActivityCategory.THINK -> ThinkDetail(entry)
                ActivityCategory.RUN_COMMAND -> CommandDetail(entry)
                ActivityCategory.WRITE_FILE, ActivityCategory.EDIT_FILE -> FileDetail(entry)
                ActivityCategory.SEARCH_FILE, ActivityCategory.WEB_SEARCH -> SearchDetail(entry)
                else -> GenericDetail(entry)
            }
        }
    }
}

/** 等宽文本块（命令 / 输出 / JSON 共用）。 */
@Composable
private fun MonoBlock(
    text: String,
    modifier: Modifier = Modifier,
    maxHeight: Int = 320,
    color: Color? = null,
) {
    val cs = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    val hScroll = rememberScrollState()
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(cs.surfaceVariant.copy(alpha = 0.45f))
            .verticalScroll(scroll)
            // 🔴 超长单行 JSON / 命令行此前被横向裁掉，看不到结果尾部。
            .horizontalScroll(hScroll),
    ) {
        Text(
            text,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
            color = color ?: cs.onSurface.copy(alpha = 0.9f),
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
        )
    }
}

/** 思考详情：完整推理文本。 */
@Composable
private fun ThinkDetail(entry: ActivityEntry) {
    val cs = MaterialTheme.colorScheme
    val text = entry.result?.trim().orEmpty()
    if (text.isEmpty()) {
        Text("这次思考没有留下文本内容。", fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.55f))
        return
    }
    MonoBlock(text, maxHeight = 420)
}

/**
 * 命令详情：工作目录 + exit 徽标 + 命令 + 输出。
 *
 * 输出里含错误时（stderr 常见形态：以 "Error"/"error:" 开头，或含 "not found"）标红，
 * 用户一眼能看出命令为什么失败。
 */
@Composable
private fun CommandDetail(entry: ActivityEntry) {
    val cs = MaterialTheme.colorScheme
    val args = parseArgs(entry.args)
    val cmd = args.optString("command", "").ifBlank {
        args.optString("cmd", "").ifBlank { entry.args }
    }
    val cwd = args.optString("cwd", "").ifBlank {
        args.optString("workdir", "").ifBlank { args.optString("working_dir", "") }
    }
    val out = entry.result.orEmpty()

    // 工作目录 + exit 徽标
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (cwd.isNotBlank()) {
            Text(
                cwd,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = cs.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
            )
            Spacer(Modifier.width(10.dp))
        }
        // 🔴 状态机：结果尚未回填 = 执行中，不是「成功完成」。
        //   旧实现只要 failed=false 就渲染绿色「exit 0」，工具还在跑就告诉用户
        //   已成功；同一份空结果在 GenericDetail 里又显示「仍在执行」，自相矛盾。
        val pending = entry.result.isNullOrBlank()
        val stopped = entry.result?.startsWith("⏹ 已停止") == true
        when {
            entry.failed -> StatusBadge("失败", Color(0xFFE5484D))
            stopped -> StatusBadge("已停止", Color(0xFFE5A00D))
            pending -> StatusBadge("执行中", cs.onSurface.copy(alpha = 0.5f))
            else -> StatusBadge("exit 0", Color(0xFF30A46C))
        }
        if (entry.durationMs > 0) {
            Spacer(Modifier.width(8.dp))
            Text(
                formatDurationMs(entry.durationMs),
                fontSize = 10.sp,
                color = cs.onSurface.copy(alpha = 0.5f),
            )
        }
    }
    Spacer(Modifier.height(8.dp))

    // 命令 + 输出
    val cmdBlock = buildString {
        append("$ ")
        append(cmd.trim().ifEmpty { "（未记录命令）" })
    }
    MonoBlock(cmdBlock, maxHeight = 120)
    Spacer(Modifier.height(8.dp))
    if (out.isNotBlank()) {
        MonoBlock(
            out,
            maxHeight = 280,
            color = if (looksLikeErrorOutput(out)) Color(0xFFE5484D) else null,
        )
    }
}

/** 文件详情：路径 + 大小 + 内容（带行号）。 */
@Composable
private fun FileDetail(entry: ActivityEntry) {
    val cs = MaterialTheme.colorScheme
    val args = parseArgs(entry.args)
    val path = args.optString("path", "").ifBlank {
        args.optString("file_path", "").ifBlank { args.optString("filePath", "") }
    }
    val resultObj = runCatching { JSONObject(entry.result.orEmpty()) }.getOrNull()
    val content = resultObj?.optString("content", "").orEmpty().ifBlank {
        // 结果不是 JSON 时，把原文当内容（多数写文件工具直接返回内容）
        entry.result.orEmpty().takeIf { it.isNotBlank() && !it.trimStart().startsWith("{") }.orEmpty()
    }
    val sizeBytes = resultObj?.optLong("size_bytes", 0L) ?: 0L

    Row(verticalAlignment = Alignment.CenterVertically) {
        LucideIcon("file_text", null, Modifier.size(13.dp), tint = cs.onSurface.copy(alpha = 0.55f))
        Spacer(Modifier.width(6.dp))
        Text(
            path.ifBlank { "（未知路径）" },
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = cs.onSurface.copy(alpha = 0.7f),
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (sizeBytes > 0) {
            Text(
                formatBytes(sizeBytes),
                fontSize = 10.sp,
                color = cs.onSurface.copy(alpha = 0.5f),
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    if (content.isNotBlank()) {
        MonoBlock(withLineNumbers(content), maxHeight = 380)
    } else {
        Text("（没有返回文件内容）", fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.55f))
    }
}

/** 搜索详情：关键词 + 结果列表。 */
@Composable
private fun SearchDetail(entry: ActivityEntry) {
    val cs = MaterialTheme.colorScheme
    val args = parseArgs(entry.args)
    val kw = args.optString("pattern", "").ifBlank {
        args.optString("query", "").ifBlank { args.optString("keyword", "") }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        LucideIcon("qic_search", null, Modifier.size(13.dp), tint = cs.onSurface.copy(alpha = 0.55f))
        Spacer(Modifier.width(6.dp))
        Text(
            kw.ifBlank { "（全部）" },
            fontSize = 12.sp,
            color = cs.onSurface.copy(alpha = 0.75f),
        )
    }
    Spacer(Modifier.height(8.dp))
    val out = entry.result.orEmpty()
    if (out.isBlank()) {
        Text("（没有结果）", fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.55f))
    } else {
        MonoBlock(out, maxHeight = 380)
    }
}

/** 通用详情：参数 / 结果 / 错误 三段。 */
@Composable
private fun GenericDetail(entry: ActivityEntry) {
    val cs = MaterialTheme.colorScheme
    val args = entry.args.trim()
    val res = entry.result.orEmpty()
    // 结果的等宽/等宽字号基准（沿用消息流里的 scaled 口径：正文 15sp 基准）。
    val scaled: (Int) -> androidx.compose.ui.unit.TextUnit = { it.sp }

    if (args.isNotBlank() && args != "{}") {
        SectionLabel("参数")
        MonoBlock(prettifyJson(args), maxHeight = 200)
        Spacer(Modifier.height(10.dp))
    }
    if (res.isNotBlank()) {
        SectionLabel("结果")
        // AIP 信封走 Canvas 引擎渲染成完整文档（与富文本渲染同源）。
        val aipJson = res.substringBefore("\n\n[导出]")
        if (com.ai.assistance.quro.core.canvas.Aip.looksLikeAip(aipJson)) {
            com.ai.assistance.quro.ui.canvas.AipCanvas(source = aipJson)
            val exportNote = res.substringAfter("\n\n[导出]", "")
            if (exportNote.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(exportNote, fontSize = 11.sp, color = cs.onSurface.copy(alpha = 0.6f))
            }
        } else if (entry.failed) {
            // 失败结果：等宽 + 红色，一眼看出问题出在哪
            MonoBlock(res, maxHeight = 300, color = Color(0xFFE5484D))
        } else {
            // 成功结果：走统一的结果美化（JSON 键值对 / 列表项目符号 / [标签] 内联）
            com.ai.assistance.quro.ui.FormattedResultContent(res, scaled)
        }
    }
    if (res.isBlank() && !entry.failed) {
        Text("（工具仍在执行）", fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.55f))
    }
    // UI 类工具（ui_*）：可重新执行 —— 与旧 ToolCallDetailCard 的能力一致，迁移到这里。
    if (entry.toolName.startsWith("ui_")) {
        Spacer(Modifier.height(8.dp))
        val actLabel = if (entry.toolName.startsWith("ui_open_")) "重新打开" else "再次执行"
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            androidx.compose.material3.TextButton(
                onClick = { com.ai.assistance.quro.core.tools.QuroUiActionBridge.dispatch?.invoke(entry.toolName) },
            ) {
                LucideIcon("link", null, Modifier.size(12.dp), tint = cs.primary)
                Spacer(Modifier.width(4.dp))
                Text(actLabel, fontSize = 11.sp, color = cs.primary)
            }
        }
    }
}

/** 小节标题。 */
@Composable
private fun SectionLabel(text: String) {
    val cs = MaterialTheme.colorScheme
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = cs.onSurface.copy(alpha = 0.55f),
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

/** 状态徽标（exit 0 / 失败）。 */
@Composable
private fun StatusBadge(text: String, color: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = color,
        )
    }
}

// ── 辅助函数 ──

private fun parseArgs(raw: String?): JSONObject =
    runCatching { JSONObject(raw?.trim().orEmpty()) }.getOrElse { JSONObject() }

/** 内容加行号（左侧等宽行号列，与 diff 视图观感一致）。 */
private fun withLineNumbers(content: String): String {
    val lines = content.split("\n")
    val w = lines.size.toString().length
    return lines.mapIndexed { i, l -> (i + 1).toString().padStart(w) + "  " + l }.joinToString("\n")
}

/** JSON 美化；不是合法 JSON 就原样返回。 */
private fun prettifyJson(raw: String): String = runCatching {
    val t = raw.trim()
    if (!t.startsWith("{") && !t.startsWith("[")) return raw
    JSONObject(t).toString(2)
}.getOrElse { raw }

/** 输出是否像错误。 */
private fun looksLikeErrorOutput(out: String): Boolean {
    val head = out.trimStart().take(200).lowercase()
    return head.startsWith("error") || head.contains("not found") ||
        head.startsWith("traceback") || head.contains("exception")
}

/** 字节数格式化。 */
private fun formatBytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> String.format("%.1f KB", b / 1024.0)
    else -> String.format("%.1f MB", b / (1024.0 * 1024.0))
}

// ═══════════════════════════════════════════════════════════════════
// 深度适配：按工具解析「直达对应物」的动作
// ═══════════════════════════════════════════════════════════════════

/** 一个深度适配动作：图标 + 文案 + 点击后要执行的事。 */
private data class DeepAction(val icon: String, val label: String, val action: () -> Unit)

/**
 * 从工具结果文本里提取本地文件路径。
 *
 * 生成类工具（image_gen/video_gen）的结果形态是「…成功！\n路径：/data/…」；
 * 文件类工具结果可能是 JSON（{"path": …}）或纯路径。逐种容忍。
 */
private fun extractLocalPath(entry: ActivityEntry): String? {
    // 1) 参数里明确给了 path（文件类工具）
    val argPath = parseArgs(entry.args).let { o ->
        listOf("path", "file_path", "filePath", "filename")
            .firstNotNullOfOrNull { k -> o.optString(k, "").trim().takeIf { it.isNotEmpty() } }
    }
    // 2) 结果文本里的「路径：xxx」行（生成类工具）
    val resPath = entry.result?.lineSequence()
        ?.firstOrNull { it.trim().startsWith("路径：") || it.trim().startsWith("路径:") }
        ?.trim()?.substringAfter('：')?.substringAfter(':')?.trim()
    // 3) 结果本身就是 JSON 且带 path
    val jsonPath = runCatching { JSONObject(entry.result.orEmpty()).optString("path", "") }
        .getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    return listOfNotNull(argPath, resPath, jsonPath).firstOrNull { it.isNotEmpty() }
}

/** 路径是否指向一张图片（按扩展名）。 */
private fun isImagePath(p: String): Boolean = p.lowercase().let {
    it.endsWith(".png") || it.endsWith(".jpg") || it.endsWith(".jpeg") ||
        it.endsWith(".webp") || it.endsWith(".gif") || it.endsWith(".bmp")
}

/** 路径是否指向一段视频。 */
private fun isVideoPath(p: String): Boolean = p.lowercase().let {
    it.endsWith(".mp4") || it.endsWith(".webm") || it.endsWith(".mkv") || it.endsWith(".mov")
}

/** 文件存在才允许「打开」；生成产物路径可能已被清理，避免跳进一个空查看器。 */
private fun fileExists(p: String?): Boolean =
    p != null && java.io.File(p).isFile()

/**
 * 按工具名 + 类别 + 参数，解析详情弹层顶部的深度适配动作。
 *
 * 规则（宁可缺省，不硬塞）：
 * - 命令类（quroterm_exec / run_code / shell…）→「打开终端」（去终端里看会话/重跑）；
 * - 文件类（workspace_write / read / edit…）且参数带路径 →「在工作区打开」（落到该路径）；
 * - 图片产物（image_gen 结果路径 / code_canvas 输出）且文件仍在 →「查看图片」（全屏查看器）；
 * - 视频产物（video_gen 结果路径）且文件仍在 →「播放视频」（应用内播放器）；
 * - 纯文本编辑器类（write_text / draft…）→「在编辑器打开」；
 * - 其余工具（网页搜索/记忆/屏幕操作…）**不挂打开按钮**——没有对应物就是没有。
 */
private fun deepLinkActionsFor(
    entry: ActivityEntry,
    onOpenEditor: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenWorkspace: (String?) -> Unit,
    onOpenImage: (String) -> Unit,
    onOpenVideo: (String) -> Unit,
): List<DeepAction> {
    val n = entry.toolName.trim().lowercase()
    val path = extractLocalPath(entry)

    // 图片/视频生成：产物文件还在 → 直接打开产物（最高优先级，先于类别判断）
    if (path != null && fileExists(path) && isImagePath(path)) {
        return listOf(DeepAction("image", "查看图片") { onOpenImage(path) })
    }
    if (path != null && fileExists(path) && isVideoPath(path)) {
        return listOf(DeepAction("video", "播放视频") { onOpenVideo(path) })
    }

    return when {
        // 命令类 → 打开终端
        entry.category == ActivityCategory.RUN_COMMAND ->
            listOf(DeepAction("qic_terminal", "打开终端") { onOpenTerminal() })
        // 文件类且带路径 → 工作区浏览落到该路径
        entry.category in FILE_CATEGORIES && !path.isNullOrBlank() ->
            listOf(DeepAction("folder", "在工作区打开") { onOpenWorkspace(path) })
        // 纯文本编辑类
        n in EDITOR_TOOLS ->
            listOf(DeepAction("file_text", "在编辑器打开") { onOpenEditor() })
        else -> emptyList()
    }
}

private val FILE_CATEGORIES = setOf(
    ActivityCategory.WRITE_FILE, ActivityCategory.EDIT_FILE, ActivityCategory.READ_FILE,
)

/** 纯文本编辑器类工具（产物是草稿文本而非工作区文件）。 */
private val EDITOR_TOOLS = setOf("write_text", "draft_text", "text_edit")

