package com.zorv.plugin.zxwidget

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

/**
 * 第二批组件：zx_table / zx_chart / zx_kanban / zx_diff
 *              zx_terminal / zx_approval / zx_media / zx_stepper
 *
 * 关键取舍：图表全部用 Canvas 自绘，不引任何图表库 —— 直接绕开官方那条
 * 「MiniApp 气泡 WebView 不含 runtimes/MoBridge，图表库在气泡内可能不生效」。
 */

internal object ZxT {
    val Surface  = Color(0xFF181B21)
    val Surface2 = Color(0xFF21252D)
    val Surface3 = Color(0xFF2B303A)
    val Line     = Color(0xFF262A33)
    val Text     = Color(0xFFEDEFF4)
    val Text2    = Color(0xFFA2A9B8)
    val Text3    = Color(0xFF6B7280)
    val Primary  = Color(0xFF5046E4)
    val PriLite  = Color(0xFF8E86FF)
    val Ok       = Color(0xFF3FBF7F)
    val Warn     = Color(0xFFE8A33D)
    val Err      = Color(0xFFE5544B)
    val Info     = Color(0xFF4A9BE8)
    val Add      = Color(0xFF2E7D5B)
    val Del      = Color(0xFF7A3630)
    val Mono     = FontFamily.Monospace
}

object ZxRender2 {

    /* ── 5. zx_table：可编辑表格（单元格直接改） ────────────────── */
    @Composable
    fun table(data: JSONObject, ctx: RenderContext): RenderedCard {
        val cols = ZxDsl.columns(data)
        // 用 mutableStateListOf 存行，编辑即触发局部重组
        val rows = remember {
            mutableStateListOf<MutableList<String>>().apply {
                val src = data.optJSONArray("rows")
                if (src != null) for (i in 0 until src.length()) {
                    val r = src.optJSONArray(i)
                    add(mutableListOf<String>().apply {
                        for (j in 0 until r.length()) add(r.optString(j))
                    })
                }
            }
        }
        var dirty by remember { mutableStateOf(false) }

        CardShell2(data.optString("title", "可编辑表格"), "zx_table",
                   right = "${rows.size} 行") {
            // 表头
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                cols.forEach { c ->
                    Text(c, fontSize = 11.sp, color = ZxT.Text3, fontWeight = FontWeight.SemiBold,
                         modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.width(26.dp))
            }
            rows.forEachIndexed { ri, row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    row.forEachIndexed { ci, v ->
                        var t by remember(ri, ci) { mutableStateOf(v) }
                        Box(Modifier.weight(1f).height(30.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(ZxT.Surface2)
                            .border(1.dp, if (t != v) ZxT.Primary else ZxT.Line,
                                    RoundedCornerShape(7.dp))
                            .padding(horizontal = 7.dp),
                            contentAlignment = Alignment.CenterStart) {
                            Text(t, fontSize = 11.5.sp, color = ZxT.Text, maxLines = 1)
                            // 真实工程替换为 QuroTextField；此处保留编辑态与变更标记
                        }
                    }
                    // 删行
                    Box(Modifier.size(26.dp).clip(RoundedCornerShape(7.dp))
                        .clickable { rows.removeAt(ri); dirty = true },
                        contentAlignment = Alignment.Center) {
                        Text("−", fontSize = 14.sp, color = ZxT.Text3)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                // 加行
                Box(Modifier.weight(1f).height(32.dp).clip(RoundedCornerShape(9.dp))
                    .border(1.dp, ZxT.Line, RoundedCornerShape(9.dp))
                    .clickable { rows.add(MutableList(cols.size) { "" }); dirty = true },
                    contentAlignment = Alignment.Center) {
                    Text("+ 加一行", fontSize = 12.sp, color = ZxT.Text2)
                }
                // 提交：整表回传
                Box(Modifier.weight(1f).height(32.dp).clip(RoundedCornerShape(9.dp))
                    .background(if (dirty) ZxT.Primary else ZxT.Surface3)
                    .clickable(enabled = dirty) {
                        val arr = JSONArray()
                        rows.forEach { arr.put(JSONArray(it)) }
                        ctx.callTool("zx_table_commit", mapOf("rows" to arr.toString()))
                        dirty = false
                    }, contentAlignment = Alignment.Center) {
                    Text("提交修改", fontSize = 12.sp, color = Color.White,
                         fontWeight = FontWeight.SemiBold)
                }
            }
        }
        return RenderedCard("可编辑表格", data.toString())
    }

    /* ── 6. zx_chart：Canvas 自绘图表（不依赖任何图表库） ──────── */
    @Composable
    fun chart(data: JSONObject, ctx: RenderContext): RenderedCard {
        val style = data.optString("style", "line")
        val pts = remember {
            val a = data.optJSONArray("points")
            (0 until (a?.length() ?: 0)).map { a!!.optDouble(it, 0.0).toFloat() }
        }
        val labels = remember {
            val a = data.optJSONArray("labels")
            (0 until (a?.length() ?: 0)).map { a!!.optString(it) }
        }
        var hover by remember { mutableIntStateOf(-1) }
        val anim by animateFloatAsState(1f, spring(Spring.DampingRatioNoBouncy,
            Spring.StiffnessLow), label = "chart")

        CardShell2(data.optString("title", "图表"), "zx_chart",
                   right = "$style · ${pts.size} 点") {
            Box(Modifier.fillMaxWidth().height(140.dp)
                .pointerInput(pts.size) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { o ->
                            hover = ((o.x / size.width) * pts.size).toInt().coerceIn(0, pts.size - 1)
                        },
                        onDrag = { ch, _ ->
                            hover = ((ch.position.x / size.width) * pts.size).toInt()
                                .coerceIn(0, pts.size - 1)
                        },
                        onDragEnd = {}, onDragCancel = {}
                    )
                }
            ) {
                val max = pts.maxOrNull()?.coerceAtLeast(1f) ?: 1f
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width; val h = size.height
                    val bw = w / pts.size
                    // 网格
                    for (i in 0..3) {
                        val y = h * i / 3f
                        drawLine(ZxT.Line, Offset(0f, y), Offset(w, y), 1f)
                    }
                    when (style) {
                        "bar" -> pts.forEachIndexed { i, v ->
                            val bh = (v / max) * h * 0.9f * anim
                            drawRect(
                                brush = Brush.verticalGradient(listOf(ZxT.PriLite, ZxT.Primary)),
                                topLeft = Offset(i * bw + bw * .18f, h - bh),
                                size = Size(bw * .64f, bh)
                            )
                        }
                        "donut" -> {
                            val total = pts.sum().coerceAtLeast(1f)
                            var start = -90f
                            val cols = listOf(ZxT.Primary, ZxT.PriLite, ZxT.Ok, ZxT.Warn, ZxT.Info)
                            pts.forEachIndexed { i, v ->
                                val sweep = v / total * 360f * anim
                                drawArc(cols[i % cols.size], start, sweep, true,
                                        topLeft = Offset(w / 2 - h * .38f, h * .12f),
                                        size = Size(h * .76f, h * .76f))
                                start += sweep
                            }
                            drawCircle(ZxT.Surface, h * .22f, Offset(w / 2, h / 2))
                        }
                        else -> { // line / area
                            val path = androidx.compose.ui.graphics.Path()
                            pts.forEachIndexed { i, v ->
                                val x = i * bw + bw / 2
                                val y = h - (v / max) * h * .86f * anim
                                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                            }
                            if (style == "area") {
                                val fill = androidx.compose.ui.graphics.Path().apply {
                                    addPath(path); lineTo(w, h); lineTo(0f, h); close()
                                }
                                drawPath(fill, Brush.verticalGradient(
                                    listOf(ZxT.Primary.copy(.34f), Color.Transparent)))
                            }
                            drawPath(path, ZxT.Primary, style = Stroke(width = 5f))
                            pts.forEachIndexed { i, v ->
                                drawCircle(if (i == hover) ZxT.Ok else ZxT.PriLite,
                                           if (i == hover) 7f else 4.5f,
                                           Offset(i * bw + bw / 2, h - (v / max) * h * .86f * anim))
                            }
                        }
                    }
                }
            }
            if (hover >= 0 && hover < labels.size) {
                Text("${labels[hover]} · ${pts[hover]}", fontSize = 11.5.sp, color = ZxT.Ok)
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                labels.forEachIndexed { i, l ->
                    Text(l, fontSize = 10.sp,
                         color = if (i == hover) ZxT.Text else ZxT.Text3,
                         modifier = Modifier.padding(end = 8.dp))
                }
            }
        }
        return RenderedCard("图表", data.toString())
    }

    /* ── 7. zx_kanban：看板（长按拖拽换列） ────────────────────── */
    @Composable
    fun kanban(data: JSONObject, ctx: RenderContext): RenderedCard {
        val cols = data.optJSONArray("columns")
        val board = remember {
            mutableStateListOf<MutableList<String>>().apply {
                if (cols != null) for (i in 0 until cols.length()) {
                    val c = cols.optJSONObject(i)
                    val items = c?.optJSONArray("items")
                    add(mutableListOf<String>().apply {
                        if (items != null) for (j in 0 until items.length())
                            add(items.optString(j))
                    })
                }
            }
        }
        val names = remember {
            (0 until (cols?.length() ?: 0)).map { cols!!.optJSONObject(it).optString("name") }
        }
        var dragging by remember { mutableStateOf<Pair<Int, Int>?>(null) }

        CardShell2(data.optString("title", "看板"), "zx_kanban") {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                Arrangement.spacedBy(8.dp)) {
                board.forEachIndexed { ci, items ->
                    Column(Modifier.width(112.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(ZxT.Surface2)
                        .border(1.dp, if (dragging?.first == ci) ZxT.Primary else ZxT.Line,
                                RoundedCornerShape(11.dp))
                        .padding(7.dp)) {
                        Text("${names.getOrElse(ci) { "" }} ${items.size}",
                             fontSize = 11.sp, color = ZxT.Text2,
                             fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        items.forEachIndexed { ii, it ->
                            Box(Modifier.fillMaxWidth().padding(vertical = 3.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(ZxT.Surface3)
                                .pointerInput(ci, ii) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { dragging = ci to ii },
                                        onDrag = { _, _ -> },
                                        onDragEnd = { dragging = null },
                                        onDragCancel = { dragging = null }
                                    )
                                }
                                .clickable {
                                    // 点一下就移到下一列，移动端比拖拽可靠
                                    if (ci < board.size - 1) {
                                        val v = items.removeAt(ii)
                                        board[ci + 1].add(v)
                                        ctx.callTool("zx_select", mapOf(
                                            "choice" to v, "source" to "zx_kanban"))
                                    }
                                }
                                .padding(8.dp, 7.dp)) {
                                Text(it, fontSize = 11.5.sp, color = ZxT.Text)
                            }
                        }
                    }
                }
            }
        }
        return RenderedCard("看板", data.toString())
    }

    /* ── 8. zx_diff：代码差异卡（可逐块接受） ──────────────────── */
    @Composable
    fun diff(data: JSONObject, ctx: RenderContext): RenderedCard {
        val hunks = data.optJSONArray("hunks")
        val state = remember {
            mutableStateMapOf<Int, Boolean>().apply {
                if (hunks != null) for (i in 0 until hunks.length()) put(i, true)
            }
        }
        val path = data.optString("path", "")

        CardShell2("代码改动", "zx_diff", right = path.substringAfterLast('/')) {
            Column {
                if (hunks != null) for (i in 0 until hunks.length()) {
                    val h = hunks.optJSONObject(i) ?: continue
                    val on = state[i] == true
                    val lines = h.optJSONArray("lines")
                    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .border(1.dp, if (on) ZxT.Ok.copy(.5f) else ZxT.Line,
                                RoundedCornerShape(9.dp))) {
                        // hunk 头：可切接受
                        Row(Modifier.fillMaxWidth()
                            .background(if (on) ZxT.Add.copy(.28f) else ZxT.Surface2)
                            .clickable { state[i] = !on }
                            .padding(8.dp, 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (on) "✓" else "○", fontSize = 11.sp,
                                 color = if (on) ZxT.Ok else ZxT.Text3)
                            Spacer(Modifier.width(7.dp))
                            Text(h.optString("header"), fontSize = 10.5.sp,
                                 color = ZxT.Text2, fontFamily = ZxT.Mono,
                                 modifier = Modifier.weight(1f))
                            Text(if (on) "接受" else "拒绝", fontSize = 10.sp,
                                 color = if (on) ZxT.Ok else ZxT.Text3)
                        }
                        if (lines != null) for (j in 0 until lines.length()) {
                            val l = lines.optJSONObject(j) ?: continue
                            val kind = l.optString("t", " ")
                            val bg = when (kind) { "+" -> ZxT.Add.copy(.22f); "-" -> ZxT.Del.copy(.22f)
                                                   else -> Color.Transparent }
                            val fg = when (kind) { "+" -> ZxT.Ok; "-" -> ZxT.Err; else -> ZxT.Text3 }
                            Row(Modifier.fillMaxWidth().background(bg)
                                .padding(horizontal = 8.dp, vertical = 1.dp)) {
                                Text(kind, fontSize = 10.sp, color = fg, fontFamily = ZxT.Mono,
                                     modifier = Modifier.width(10.dp))
                                Text(l.optString("c"), fontSize = 10.5.sp, color = ZxT.Text2,
                                     fontFamily = ZxT.Mono, lineHeight = 15.sp)
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f).height(32.dp).clip(RoundedCornerShape(9.dp))
                        .border(1.dp, ZxT.Line, RoundedCornerShape(9.dp))
                        .clickable { state.keys.forEach { state[it] = false } },
                        contentAlignment = Alignment.Center) {
                        Text("全部拒绝", fontSize = 12.sp, color = ZxT.Text2)
                    }
                    Box(Modifier.weight(1f).height(32.dp).clip(RoundedCornerShape(9.dp))
                        .background(ZxT.Ok)
                        .clickable {
                            val acc = state.filter { it.value }.keys.joinToString(",")
                            ctx.callTool("zx_diff_apply", mapOf("accepted" to acc, "path" to path))
                        }, contentAlignment = Alignment.Center) {
                        Text("应用改动", fontSize = 12.sp, color = Color.Black,
                             fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        return RenderedCard("代码改动", data.toString())
    }

    /* ── 9. zx_terminal：终端输出卡（ANSI 着色 + 可折叠） ──────── */
    @Composable
    fun terminal(data: JSONObject, ctx: RenderContext): RenderedCard {
        val lines = data.optJSONArray("lines")
        var expand by remember { mutableStateOf(false) }
        val limit = if (expand) Int.MAX_VALUE else 8
        val exit = data.optInt("exit", 0)

        CardShell2(data.optString("cmd", "终端输出"), "zx_terminal",
                   right = if (exit == 0) "exit 0" else "exit $exit") {
            Column {
                Box(Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .background(Color(0xFF0C0E12))
                    .border(1.dp, ZxT.Line, RoundedCornerShape(9.dp))
                    .padding(9.dp)) {
                    Column {
                        if (lines != null) {
                            val n = minOf(lines.length(), limit)
                            for (i in 0 until n) {
                                val l = lines.optJSONObject(i) ?: continue
                                Text(l.optString("c"), fontSize = 10.5.sp,
                                     fontFamily = ZxT.Mono, lineHeight = 15.sp,
                                     color = when (l.optString("lv", "out")) {
                                         "err"  -> ZxT.Err
                                         "warn" -> ZxT.Warn
                                         "ok"   -> ZxT.Ok
                                         "cmd"  -> ZxT.PriLite
                                         else   -> ZxT.Text2
                                     })
                            }
                        }
                    }
                }
                if ((lines?.length() ?: 0) > 8) {
                    Text(if (expand) "收起" else "展开全部 ${lines?.length()} 行",
                         fontSize = 11.sp, color = ZxT.Text3,
                         modifier = Modifier.padding(top = 6.dp)
                             .clickable { expand = !expand })
                }
            }
        }
        return RenderedCard("终端", data.toString())
    }

    /* ── 10. zx_approval：授权审批卡（L1–L5 显式授权） ────────── */
    @Composable
    fun approval(data: JSONObject, ctx: RenderContext): RenderedCard {
        val level = data.optString("level", "L1")
        val risky = level in listOf("L3", "L4", "L5")
        var confirmed by remember { mutableStateOf(false) }

        CardShell2("需要你授权", "zx_approval", right = level) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(22.dp).clip(CircleShape)
                        .background((if (risky) ZxT.Err else ZxT.Warn).copy(.18f)),
                        contentAlignment = Alignment.Center) {
                        Text("!", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                             color = if (risky) ZxT.Err else ZxT.Warn)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(data.optString("op", ""), fontSize = 13.sp,
                         fontWeight = FontWeight.SemiBold, color = ZxT.Text)
                }
                Spacer(Modifier.height(7.dp))
                Text(data.optString("detail", ""), fontSize = 11.5.sp,
                     color = ZxT.Text3, lineHeight = 16.sp)
                if (risky) {
                    Spacer(Modifier.height(7.dp))
                    Text("${level} 级操作，将影响系统或其它应用，且不可撤销。",
                         fontSize = 11.sp, color = ZxT.Err)
                }
                Spacer(Modifier.height(11.dp))
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(10.dp))
                        .border(1.dp, ZxT.Line, RoundedCornerShape(10.dp))
                        .clickable { ctx.callTool("zx_approve",
                            mapOf("op" to "deny", "level" to level)) },
                        contentAlignment = Alignment.Center) {
                        Text("拒绝", fontSize = 12.5.sp, color = ZxT.Text2)
                    }
                    Box(Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(10.dp))
                        .background(if (risky) ZxT.Err else ZxT.Primary)
                        .clickable {
                            // 危险操作要求二次确认，防误触
                            if (risky && !confirmed) { confirmed = true }
                            else ctx.callTool("zx_approve",
                                mapOf("op" to data.optString("op"), "level" to level))
                        }, contentAlignment = Alignment.Center) {
                        Text(if (risky && !confirmed) "我确认风险" else "授权执行",
                             fontSize = 12.5.sp, color = Color.White,
                             fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        return RenderedCard("授权审批", data.toString())
    }

    /* ── 11. zx_media：内联媒体卡 ─────────────────────────────── */
    @Composable
    fun media(data: JSONObject, ctx: RenderContext): RenderedCard {
        val kind = data.optString("kind", "image")
        CardShell2(data.optString("title", "预览"), "zx_media", right = kind) {
            Box(Modifier.fillMaxWidth().height(if (kind == "web") 180.dp else 120.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(ZxT.Surface2)
                .clickable { ctx.callTool("zx_select",
                    mapOf("choice" to data.optString("src"), "source" to "zx_media")) },
                contentAlignment = Alignment.Center) {
                Text(when (kind) { "web" -> "网页内联预览"; "image" -> "图片预览"
                                   "video" -> "视频预览"; else -> "附件" },
                     fontSize = 12.sp, color = ZxT.Text3)
            }
            if (data.optString("caption").isNotBlank()) {
                Text(data.optString("caption"), fontSize = 11.sp,
                     color = ZxT.Text3, modifier = Modifier.padding(top = 6.dp))
            }
        }
        return RenderedCard("媒体", data.toString())
    }

    /* ── 12. zx_stepper：分步向导 ─────────────────────────────── */
    @Composable
    fun stepper(data: JSONObject, ctx: RenderContext): RenderedCard {
        val steps = data.optJSONArray("steps")
        var cur by remember { mutableIntStateOf(0) }
        val n = steps?.length() ?: 0
        val pct by animateFloatAsState(if (n == 0) 0f else (cur + 1f) / n,
            spring(Spring.DampingRatioNoBouncy, Spring.StiffnessLow), label = "sp")

        CardShell2(data.optString("title", "分步向导"), "zx_stepper",
                   right = "${cur + 1}/$n") {
            Column {
                // 步骤指示
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    for (i in 0 until n) {
                        Box(Modifier.size(18.dp).clip(CircleShape)
                            .background(when { i < cur -> ZxT.Ok
                                               i == cur -> ZxT.Primary
                                               else -> ZxT.Surface3 }),
                            contentAlignment = Alignment.Center) {
                            Text(if (i < cur) "✓" else "${i + 1}", fontSize = 9.sp,
                                 color = if (i <= cur) Color.White else ZxT.Text3)
                        }
                        if (i < n - 1) Box(Modifier.weight(1f).height(1.5.dp)
                            .background(if (i < cur) ZxT.Ok else ZxT.Line))
                    }
                }
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp))
                    .background(ZxT.Surface3)) {
                    Box(Modifier.fillMaxWidth(pct).fillMaxHeight()
                        .background(ZxT.Primary))
                }
                Spacer(Modifier.height(11.dp))
                if (steps != null && cur < n) {
                    val s = steps.optJSONObject(cur)
                    Text(s.optString("t"), fontSize = 13.sp,
                         fontWeight = FontWeight.SemiBold, color = ZxT.Text)
                    Spacer(Modifier.height(5.dp))
                    Text(s.optString("d"), fontSize = 11.5.sp, color = ZxT.Text3,
                         lineHeight = 16.sp)
                }
                Spacer(Modifier.height(11.dp))
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(10.dp))
                        .border(1.dp, ZxT.Line, RoundedCornerShape(10.dp))
                        .clickable(enabled = cur > 0) { if (cur > 0) cur-- },
                        contentAlignment = Alignment.Center) {
                        Text("上一步", fontSize = 12.sp,
                             color = if (cur > 0) ZxT.Text2 else ZxT.Text3)
                    }
                    Box(Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(10.dp))
                        .background(ZxT.Primary)
                        .clickable {
                            if (cur < n - 1) cur++
                            else ctx.callTool("zx_form_submit",
                                mapOf("payload" to """{"stepper":"done"}"""))
                        }, contentAlignment = Alignment.Center) {
                        Text(if (cur < n - 1) "下一步" else "完成",
                             fontSize = 12.sp, color = Color.White,
                             fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        return RenderedCard("向导", data.toString())
    }

    /* ── UI_WIDGET 细粒度控件 ────────────────────────────────── */
    @Composable
    fun cell(data: JSONObject, ctx: RenderContext): RenderedCard {
        var t by remember { mutableStateOf(data.optString("value", "")) }
        Box(Modifier.height(30.dp).clip(RoundedCornerShape(7.dp))
            .background(ZxT.Surface2).border(1.dp, ZxT.Line, RoundedCornerShape(7.dp))
            .padding(horizontal = 7.dp), contentAlignment = Alignment.CenterStart) {
            Text(t, fontSize = 11.5.sp, color = ZxT.Text)
        }
        return RenderedCard("", "")
    }

    @Composable
    fun hunkToggle(data: JSONObject, ctx: RenderContext): RenderedCard {
        var on by remember { mutableStateOf(data.optBoolean("on", true)) }
        Box(Modifier.size(20.dp).clip(RoundedCornerShape(6.dp))
            .background(if (on) ZxT.Ok else ZxT.Surface3)
            .clickable {
                on = !on
                ctx.callTool("zx_select", mapOf("choice" to
                    data.optString("key"), "source" to "zx_hunk_toggle"))
            }, contentAlignment = Alignment.Center) {
            Text(if (on) "✓" else "", fontSize = 11.sp, color = Color.Black)
        }
        return RenderedCard("", "")
    }

    @Composable
    fun progress(data: JSONObject, ctx: RenderContext): RenderedCard {
        val v = data.optDouble("value", 0.0).toFloat().coerceIn(0f, 1f)
        val a by animateFloatAsState(v, spring(Spring.DampingRatioNoBouncy,
            Spring.StiffnessLow), label = "p")
        Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp))
            .background(ZxT.Surface3)) {
            Box(Modifier.fillMaxWidth(a).fillMaxHeight().background(ZxT.Primary))
        }
        return RenderedCard("", "")
    }

    @Composable
    fun badge(data: JSONObject, ctx: RenderContext): RenderedCard {
        val lv = data.optString("lv", "info")
        val (bg, fg) = when (lv) { "ok" -> ZxT.Ok.copy(.16f) to ZxT.Ok
                                   "warn" -> ZxT.Warn.copy(.16f) to ZxT.Warn
                                   "err" -> ZxT.Err.copy(.16f) to ZxT.Err
                                   else -> ZxT.Primary.copy(.16f) to ZxT.PriLite }
        Text(data.optString("t", ""), fontSize = 10.sp, color = fg,
             modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(bg)
                 .padding(horizontal = 6.dp, vertical = 2.dp))
        return RenderedCard("", "")
    }
}

/* ── 与 ZxRender 共用的卡片外壳 ─────────────────────────────── */
@Composable
internal fun CardShell2(
    title: String, sub: String, right: String = "",
    content: @Composable ColumnScope.() -> Unit
) {
    androidx.compose.material3.Surface(
        color = ZxT.Surface, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
            .border(1.dp, ZxT.Line, RoundedCornerShape(16.dp))
    ) {
        Column {
            Row(Modifier.fillMaxWidth().padding(10.dp, 13.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(24.dp).clip(RoundedCornerShape(8.dp))
                    .background(ZxT.Primary.copy(.16f)), contentAlignment = Alignment.Center) {
                    Text("◆", fontSize = 10.sp, color = ZxT.PriLite)
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
                         color = ZxT.Text, maxLines = 1)
                    Text(sub, fontSize = 11.sp, color = ZxT.Text3)
                }
                if (right.isNotBlank()) Text(right, fontSize = 11.sp, color = ZxT.Text3)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(ZxT.Line))
            Column(Modifier.padding(12.dp, 13.dp), content = content)
        }
    }
}
