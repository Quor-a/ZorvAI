package com.ai.assistance.quro.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.core.chat.ActivityCategory
import com.ai.assistance.quro.core.chat.ActivityEntry
import com.ai.assistance.quro.ui.icons.LucideIcon

/**
 * 活动流 —— 一轮回复里「思考 + 工具调用」的逐条明细。
 *
 * ## 为什么是这个形态（而不是一个「· N 工具」折叠块）
 *
 * 折叠块的问题是**看不见过程**：用户只看到"用了 6 个工具"，
 * 但不知道跑了什么命令、写了哪个文件、哪一步失败了。
 * 现在的形态：
 *
 * ```
 * 思考、运行了 6 条命令、操作了 1 次浏览器、…     ← 汇总行（可点）
 * 🧠 思考了 1秒                                  ← 逐条（失败红字）
 * >_ 探测本地 MCP 端点
 * 🧠 思考了 1秒
 * >_ 检查可用的网络工具                          ← 失败 = 红色
 * ```
 *
 * 点任意一条 → 底部弹层看该次的完整细节。
 *
 * @param entries 这一轮的明细条目（思考条目与工具条目混排，保持真实时序）。
 * @param onOpenEntry 点某一条 → 打开详情弹层。
 */
@Composable
fun ActivityStream(
    entries: List<ActivityEntry>,
    onOpenEntry: (ActivityEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    // 🔴 汇总行**默认收起**：活动流展开后应是常驻的逐条列表，
    //   放在正文里**始终可见**。
    //   本仓的活动流是「点名字行入口才展开」的二级层，
    //   所以展开后应该**直接列出全部条目**（不再二次折叠），
    //   否则用户点开还是只看到 3 条 → 又是「显示没做对」。
    // 🔴 收起状态不能用 entries 对象当 remember key：流式每帧聚合出的都是**新 List**，
    //    `remember(entries)` 会把用户刚点收起的 summaryOpen 重置回 true ——
    //    这正是「显示不会收起来」在原生侧的残留根因（WebUI 侧另有一处）。
    //    改用条目数：工具数量真变了才重置，token 增量重绘不再吃掉用户操作。
    var summaryOpen by remember(entries.size) { mutableStateOf(true) }

    Column(modifier.fillMaxWidth()) {
        // ── 汇总行 ──
        val summary = buildActivitySummary(entries)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable { summaryOpen = !summaryOpen }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                summary,
                fontSize = 13.sp,
                color = cs.onSurface.copy(alpha = 0.86f),
                maxLines = if (summaryOpen) Int.MAX_VALUE else 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(4.dp))
            // chevron 旋转过渡（而不是硬切图标）：展开时朝上，收起时回正。
            val chevronRot by animateFloatAsState(
                targetValue = if (summaryOpen) 180f else 0f,
                animationSpec = tween(220),
                label = "activityChevron",
            )
            LucideIcon(
                "chevron_down",
                null,
                Modifier
                    .size(13.dp)
                    .rotate(chevronRot),
                tint = cs.onSurface.copy(alpha = 0.5f),
            )
        }

        // ── 逐条明细 ──
        // 汇总行展开时列出全部；收起时只列最近 3 条（折叠块里的"预览"）。
        val visible = if (summaryOpen) entries else entries.takeLast(3)
        AnimatedVisibility(
            visible = summaryOpen || entries.size <= 3,
            enter = fadeIn(tween(160)) + expandVertically(tween(200)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(200)),
        ) {
            Column {
                visible.forEach { e ->
                    ActivityRow(e) { onOpenEntry(e) }
                }
            }
        }
        if (!summaryOpen && entries.size > 3) {
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(tween(160)),
                exit = fadeOut(tween(120)),
            ) {
                Text(
                    "还有 ${entries.size - 3} 条…",
                    fontSize = 11.sp,
                    color = cs.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier
                        .clickable { summaryOpen = true }
                        .padding(start = 24.dp, top = 2.dp),
                )
            }
        }
    }
}

/**
 * 单条活动行：图标 + 文案。
 *
 * 失败行整体染红（图标 + 文字），这是用户判断"哪一步出错"的唯一视觉线索。
 * 按压时背景泛一层淡色（按下即反馈，不用等点击回调）。
 */
@Composable
private fun ActivityRow(e: ActivityEntry, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val tint = if (e.failed) Color(0xFFE5484D) else cs.onSurface.copy(alpha = 0.82f)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg = if (pressed) cs.onSurface.copy(alpha = 0.06f) else Color.Transparent
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LucideIcon(
            activityIcon(e.category),
            null,
            Modifier.size(15.dp),
            tint = tint,
        )
        Spacer(Modifier.width(9.dp))
        Text(
            e.title,
            fontSize = 13.sp,
            color = tint,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

/** 类别 → 图标名（必须用本仓 LucideIcon 真实存在的分支名，否则会 fallback 成 ic_x）。 */
fun activityIcon(c: ActivityCategory): String = when (c) {
    ActivityCategory.THINK -> "sparkles"
    ActivityCategory.RUN_COMMAND -> "qic_terminal"
    ActivityCategory.READ_FILE -> "qic_file_read"
    ActivityCategory.WRITE_FILE -> "qic_file_write"
    ActivityCategory.EDIT_FILE -> "qic_file_write"
    ActivityCategory.SEARCH_FILE -> "qic_search"
    ActivityCategory.WEB_SEARCH -> "qic_search"
    ActivityCategory.WEB_FETCH -> "qic_web"
    ActivityCategory.BROWSER -> "qic_web"
    ActivityCategory.MEMORY -> "qic_memory"
    ActivityCategory.COMPUTER -> "qic_device"
    ActivityCategory.PHONE -> "qic_device"
    ActivityCategory.SCHEDULE -> "bell"
    ActivityCategory.CALENDAR -> "qic_system"
    ActivityCategory.AGENT -> "bot"
    ActivityCategory.TOOL -> "qic_other"
}

/**
 * 生成汇总行文案。
 *
 * 规则：
 * - 按类别计数，去掉 0 项；
 * - 顺序按 [ActivityCategory] 声明序（思考 → 命令 → 文件 → 网页 → …），保证同一轮多次生成结果一致；
 * - 用「、」连接；
 * - 末尾若总耗时 ≥ 1 秒，追加「 · 耗时」。
 *
 * 措辞：思考 / 运行了 N 条命令 / 读取了 N 个文件 / 写入了 N 个文件 /
 * 搜索了 N 次文件 / 搜索了 N 次网页 / 读取了 N 个网页 / 操作了 N 次浏览器 /
 * 读写了 N 次记忆 / 操作了 N 次电脑 / 操作了 N 次手机 / 管理了 N 次日程 /
 * 读写了 N 次日历 / 调用了 N 次智能体 / 调用了 N 个工具。
 */
fun buildActivitySummary(entries: List<ActivityEntry>): String {
    val counts = LinkedHashMap<ActivityCategory, Int>()
    entries.forEach { e ->
        counts[e.category] = (counts[e.category] ?: 0) + 1
    }
    if (counts.isEmpty()) return "处理了步骤"
    val parts = mutableListOf<String>()
    for (c in ActivityCategory.entries) {
        val n = counts[c] ?: continue
        parts += activityCountLabel(c, n)
    }
    var out = parts.joinToString("、")
    // 🔴 耗时口径：内核把「整批工具的墙钟时长」写进了同一批**每一条**工具
    //    （QuroAssistant.enrichedCalls 共用 dur），旧实现再 sumOf 就是把总时长
    //    放大 N 倍（5 个工具各 3s → 显示 15s）。改取 max = 本轮墙钟。
    //    thinkDurationMs 从未被赋值（恒 0），一并去掉这条无效累加。
    val totalMs = entries.maxOfOrNull { it.durationMs } ?: 0L
    if (totalMs >= 1000) out += " · " + formatDurationMs(totalMs)
    return out
}

/** 类别计数 → 中文措辞。 */
fun activityCountLabel(c: ActivityCategory, n: Int): String = when (c) {
    ActivityCategory.THINK -> "思考"
    ActivityCategory.RUN_COMMAND -> "运行了 $n 条命令"
    ActivityCategory.READ_FILE -> "读取了 $n 个文件"
    ActivityCategory.WRITE_FILE -> "写入了 $n 个文件"
    ActivityCategory.EDIT_FILE -> "编辑了 $n 个文件"
    ActivityCategory.SEARCH_FILE -> "搜索了 $n 次文件"
    ActivityCategory.WEB_SEARCH -> "搜索了 $n 次网页"
    ActivityCategory.WEB_FETCH -> "读取了 $n 个网页"
    ActivityCategory.BROWSER -> "操作了 $n 次浏览器"
    ActivityCategory.MEMORY -> "读写了 $n 次记忆"
    ActivityCategory.COMPUTER -> "操作了 $n 次电脑"
    ActivityCategory.PHONE -> "操作了 $n 次手机"
    ActivityCategory.SCHEDULE -> "管理了 $n 次日程"
    ActivityCategory.CALENDAR -> "读写了 $n 次日历"
    ActivityCategory.AGENT -> "调用了 $n 次智能体"
    ActivityCategory.TOOL -> "调用了 $n 个工具"
}

/**
 * 耗时格式化。
 *
 * 规则：`(ms + 500) / 1000` 四舍五入到秒，再分三档：
 * - ≥ 1 小时 → 「X小时 Y分钟」
 * - ≥ 1 分钟 → 「X分钟 Y秒」
 * - 否则 → 「X秒」
 *
 * 四舍五入（而不是截断）是为了让「1.4 秒」显示成「1秒」而不是「0秒」——
 * 后者在对话里看起来像"没耗时"。
 */
fun formatDurationMs(ms: Long): String {
    val totalSec = (ms + 500) / 1000
    if (totalSec <= 0) return "0秒"
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0 -> "${h}小时 ${m}分钟"
        m > 0 -> "${m}分钟 ${s}秒"
        else -> "${s}秒"
    }
}
