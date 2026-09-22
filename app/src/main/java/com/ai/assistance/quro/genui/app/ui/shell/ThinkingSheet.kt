package com.ai.assistance.quro.genui.app.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.genui.app.agent.ThinkingTimeline
import com.ai.assistance.quro.genui.app.ui.theme.GenTheme

/**
 * 思考时间线抽屉 —— 让"AI 到底干了什么"变成可读的账本。
 *
 * 设计取舍：
 *  - **纵向时间轴**而非流水列表：人读过程习惯"从上到下、有线连着"，轴线 + 节点
 *    一眼能看出先后与归属。左侧轴线用 drawBehind 画，不占布局重量。
 *  - **每种事件一个原创字形**（手绘矢量，不用 Material 图标），颜色区分语义：
 *    思考=琥珀、工具=青、拒绝=红、结果=绿、绘制=紫。
 *  - **进行中的那条会呼吸**：running 状态的节点画成空心圈，配合外侧淡环，
 *    用户一眼知道"卡在哪一步"。
 *  - **自动滚到底**：生成中不断追加，视口跟着走；用户手动上滑就不再抢滚动
 *    （避免"我想看前面它却一直往下跳"）。
 */
@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun ThinkingSheet(
    timeline: ThinkingTimeline,
    building: Boolean,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val entries = timeline.entries
    val listState = rememberLazyListState()

    // 生成中自动跟随最新；用户上滑离底部则停止跟随
    val follow by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= entries.lastIndex - 1
        }
    }
    LaunchedEffect(entries.size, building) {
        if (building && entries.isNotEmpty() && follow) {
            listState.animateScrollToItem(entries.lastIndex)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = GenTheme.Panel,
        scrimColor = Color.Black.copy(alpha = 0.6f)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            // —— 标题栏 ——
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "思 考 过 程",
                    color = GenTheme.Text, fontSize = 15.sp,
                    fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium,
                    letterSpacing = 2.sp
                )
                Spacer(Modifier.width(10.dp))
                if (building) {
                    Text("进行中", color = GenTheme.Amber, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "${entries.size} 步",
                    color = GenTheme.Dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                if (building) "AI 正在推理与调用工具，实时更新"
                else "本次生成的完整轨迹 · 可向上翻看",
                color = GenTheme.Dim, fontSize = 10.sp
            )
            Spacer(Modifier.height(14.dp))

            if (entries.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                    Text("还没有过程记录", color = GenTheme.Dim, fontSize = 12.sp)
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                    contentPadding = PaddingValues(bottom = 28.dp)
                ) {
                    itemsIndexed(entries) { i, e ->
                        TimelineRow(
                            entry = e,
                            isLast = i == entries.lastIndex,
                            isFirst = i == 0
                        )
                    }
                }
            }
        }
    }
}

/** 单条时间线：左侧节点+连线，右侧文本。 */
@Composable
private fun TimelineRow(
    entry: ThinkingTimeline.Entry,
    isFirst: Boolean,
    isLast: Boolean
) {
    val (color, glyph) = kindStyle(entry)

    Row(Modifier.fillMaxWidth()) {
        // —— 轴线列 ——
        Box(
            Modifier
                .width(26.dp)
                .then(
                    Modifier.drawBehind {
                        val cx = size.width / 2f
                        val nodeR = 6.dp.toPx()
                        val topY = if (isFirst) 12.dp.toPx() else 0f
                        val botY = if (isLast) 12.dp.toPx() else size.height
                        // 上段线
                        if (!isFirst) drawLine(
                            GenTheme.Line, Offset(cx, topY), Offset(cx, 12.dp.toPx()),
                            strokeWidth = 1.5.dp.toPx()
                        )
                        // 下段线
                        if (!isLast) drawLine(
                            GenTheme.Line, Offset(cx, 12.dp.toPx() + nodeR * 2 + 2), Offset(cx, botY),
                            strokeWidth = 1.5.dp.toPx()
                        )
                        // 节点：running 空心+外环，完成实心
                        val cy = 12.dp.toPx() + nodeR
                        if (entry.running) {
                            drawCircle(color.copy(alpha = 0.25f), nodeR + 3.5.dp.toPx(), Offset(cx, cy), style = Stroke(1.dp.toPx()))
                            drawCircle(color, nodeR, Offset(cx, cy), style = Stroke(1.6.dp.toPx()))
                        } else {
                            drawCircle(color, nodeR * 0.82f, Offset(cx, cy))
                        }
                    }
                )
        )
        // —— 内容列 ——
        Column(Modifier.weight(1f).padding(start = 4.dp, top = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Glyph(glyph, color, Modifier.size(13.dp))
                Spacer(Modifier.width(7.dp))
                Text(
                    kindLabel(entry.kind),
                    color = color, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.weight(1f))
                Text(
                    clock(entry.ts),
                    color = GenTheme.Dim.copy(alpha = 0.7f), fontSize = 9.sp, fontFamily = FontFamily.Monospace
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                entry.text,
                color = if (entry.running) GenTheme.Text else GenTheme.Text.copy(alpha = 0.88f),
                fontSize = 12.5.sp, lineHeight = 17.sp
            )
            if (entry.detail.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Column(
                    Modifier.fillMaxWidth()
                        .background(GenTheme.Screen.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                        .border(0.5.dp, GenTheme.Line, RoundedCornerShape(6.dp))
                        .padding(horizontal = 9.dp, vertical = 6.dp)
                ) {
                    Text(
                        entry.detail,
                        color = GenTheme.Dim, fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace, lineHeight = 15.sp
                    )
                }
            }
        }
    }
}

/** 每种事件的语义色 + 字形标识 */
private fun kindStyle(e: ThinkingTimeline.Entry): Pair<Color, Glyph> = when (e.kind) {
    ThinkingTimeline.Kind.START -> GenTheme.Amber to Glyph.SPARK
    ThinkingTimeline.Kind.THINK -> GenTheme.AmberDim to Glyph.BRAIN
    ThinkingTimeline.Kind.DECIDE -> GenTheme.Amber to Glyph.FORK
    ThinkingTimeline.Kind.TOOL -> Color(0xFF5FA8A0) to Glyph.WRENCH
    ThinkingTimeline.Kind.AUTH -> Color(0xFF5FA8A0) to Glyph.KEY
    ThinkingTimeline.Kind.DENIED -> GenTheme.Red to Glyph.BLOCK
    ThinkingTimeline.Kind.RESULT ->
        (if (e.text.startsWith("失败")) GenTheme.Red else GenTheme.Green) to Glyph.CHECK
    ThinkingTimeline.Kind.RENDER -> Color(0xFF8A7BC8) to Glyph.PEN
    ThinkingTimeline.Kind.PAINT -> Color(0xFFB08BD8) to Glyph.BRUSH
    ThinkingTimeline.Kind.DONE -> GenTheme.Green to Glyph.FLAG
    ThinkingTimeline.Kind.ERROR -> GenTheme.Red to Glyph.BLOCK
}

private fun kindLabel(k: ThinkingTimeline.Kind): String = when (k) {
    ThinkingTimeline.Kind.START -> "开 始"
    ThinkingTimeline.Kind.THINK -> "思 考"
    ThinkingTimeline.Kind.DECIDE -> "决 策"
    ThinkingTimeline.Kind.TOOL -> "调 用"
    ThinkingTimeline.Kind.AUTH -> "授 权"
    ThinkingTimeline.Kind.DENIED -> "拒 绝"
    ThinkingTimeline.Kind.RESULT -> "结 果"
    ThinkingTimeline.Kind.RENDER -> "绘 制"
    ThinkingTimeline.Kind.PAINT -> "落 笔"
    ThinkingTimeline.Kind.DONE -> "完 成"
    ThinkingTimeline.Kind.ERROR -> "出 错"
}

private fun clock(ts: Long): String {
    val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA)
    return sdf.format(java.util.Date(ts))
}

/** 原创手绘字形（16×16 视口）——不依赖 Material 图标集，保持整体调性。 */
private enum class Glyph { SPARK, BRAIN, FORK, WRENCH, KEY, BLOCK, CHECK, PEN, BRUSH, FLAG }

@Composable
private fun Glyph(g: Glyph, tint: Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width; val h = size.height
        val sw = 1.3.dp.toPx()
        val st = Stroke(width = sw, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        val p = Path()
        when (g) {
            Glyph.SPARK -> {
                // 四角星
                p.moveTo(w * .5f, h * .06f); p.lineTo(w * .6f, h * .4f); p.lineTo(w * .94f, h * .5f)
                p.lineTo(w * .6f, h * .6f); p.lineTo(w * .5f, h * .94f); p.lineTo(w * .4f, h * .6f)
                p.lineTo(w * .06f, h * .5f); p.lineTo(w * .4f, h * .4f); p.close()
                drawPath(p, tint, style = st)
            }
            Glyph.BRAIN -> {
                // 脑：外轮廓 + 中缝 + 两道沟
                p.moveTo(w * .5f, h * .16f)
                p.cubicTo(w * .22f, h * .06f, w * .1f, h * .34f, w * .2f, h * .46f)
                p.cubicTo(w * .08f, h * .62f, w * .24f, h * .86f, w * .5f, h * .8f)
                p.cubicTo(w * .76f, h * .86f, w * .92f, h * .62f, w * .8f, h * .46f)
                p.cubicTo(w * .9f, h * .34f, w * .78f, h * .06f, w * .5f, h * .16f)
                drawPath(p, tint, style = st)
                drawLine(tint, Offset(w * .5f, h * .16f), Offset(w * .5f, h * .8f), sw * .8f)
                drawLine(tint, Offset(w * .28f, h * .42f), Offset(w * .44f, h * .5f), sw * .7f)
                drawLine(tint, Offset(w * .72f, h * .42f), Offset(w * .56f, h * .5f), sw * .7f)
            }
            Glyph.FORK -> {
                // 分叉：决策点
                drawCircle(tint, w * .1f, Offset(w * .5f, h * .5f), style = st)
                p.moveTo(w * .5f, h * .5f); p.lineTo(w * .78f, h * .2f)
                drawPath(p, tint, style = st)
                p.moveTo(w * .5f, h * .5f); p.lineTo(w * .78f, h * .8f)
                drawPath(p, tint, style = st)
                drawCircle(tint, w * .09f, Offset(w * .84f, h * .18f))
                drawCircle(tint, w * .09f, Offset(w * .84f, h * .82f))
            }
            Glyph.WRENCH -> {
                // 扳手：斜杆 + 两端开口环
                drawLine(tint, Offset(w * .3f, h * .7f), Offset(w * .7f, h * .3f), sw)
                drawCircle(tint, w * .17f, Offset(w * .22f, h * .78f), style = st)
                drawCircle(tint, w * .17f, Offset(w * .78f, h * .22f), style = st)
            }
            Glyph.KEY -> {
                drawCircle(tint, w * .18f, Offset(w * .28f, h * .5f), style = st)
                drawLine(tint, Offset(w * .44f, h * .5f), Offset(w * .92f, h * .5f), sw)
                drawLine(tint, Offset(w * .78f, h * .5f), Offset(w * .78f, h * .68f), sw)
            }
            Glyph.BLOCK -> {
                drawCircle(tint, w * .4f, Offset(w * .5f, h * .5f), style = st)
                drawLine(tint, Offset(w * .22f, h * .78f), Offset(w * .78f, h * .22f), sw)
            }
            Glyph.CHECK -> {
                p.moveTo(w * .16f, h * .52f); p.lineTo(w * .42f, h * .78f); p.lineTo(w * .86f, h * .24f)
                drawPath(p, tint, style = st)
            }
            Glyph.PEN -> {
                p.moveTo(w * .2f, h * .8f); p.lineTo(w * .3f, h * .52f); p.lineTo(w * .74f, h * .1f)
                p.lineTo(w * .9f, h * .26f); p.lineTo(w * .46f, h * .68f); p.close()
                drawPath(p, tint, style = st)
                drawLine(tint, Offset(w * .3f, h * .52f), Offset(w * .46f, h * .68f), sw * .8f)
            }
            Glyph.BRUSH -> {
                // 笔刷：斜向笔杆 + 分叉笔尖 —— 用于"正在绘制"的过程条目
                p.moveTo(w * .78f, h * .08f); p.lineTo(w * .92f, h * .22f); p.lineTo(w * .46f, h * .68f)
                p.lineTo(w * .3f, h * .74f); p.lineTo(w * .36f, h * .58f); p.close()
                drawPath(p, tint, style = st)
                // 笔尖甩出的两道痕迹，暗示"正在画"
                drawLine(tint, Offset(w * .18f, h * .52f), Offset(w * .06f, h * .4f), sw * .8f)
                drawLine(tint, Offset(w * .24f, h * .82f), Offset(w * .08f, h * .84f), sw * .7f)
            }
            Glyph.FLAG -> {
                drawLine(tint, Offset(w * .22f, h * .1f), Offset(w * .22f, h * .92f), sw)
                p.moveTo(w * .22f, h * .14f); p.lineTo(w * .86f, h * .3f); p.lineTo(w * .22f, h * .5f); p.close()
                drawPath(p, tint, style = Stroke(sw * .9f, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            }
        }
    }
}
