package com.zorv.plugin.zxwidget

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

/**
 * 第三批组件（12 个）：
 *   zx_metrics  指标卡组    zx_gantt    甘特图
 *   zx_tree     文件树      zx_flow     流程图
 *   zx_calendar 日历        zx_rating   评分卡
 *   zx_editor   代码编辑器  zx_audio    音频波形
 *   zx_heatmap  热力图      zx_toggle   开关列表
 *   zx_qr       二维码      zx_stack    卡片堆栈
 *
 * 全部沿用同一条铁律：不引任何三方库，图表/二维码/波形一律 Canvas 自绘。
 * 官方已知 MiniApp 气泡 WebView 不含 runtimes 与 MoBridge，
 * 任何依赖 JS 运行时的可视化方案在气泡里都可能不生效——自绘是唯一确定能跑的路。
 */
object ZxRender3 {

    /* ══════════════ zx_metrics 指标卡组 ══════════════ */
    @Composable
    fun metrics(o: JSONObject, ctx: RenderContext) {
        val items = o.optJSONArray("items") ?: return
        val n = minOf(items.length(), 6)
        val cols = if (n <= 2) 2 else if (n <= 4) 2 else 3

        // 网格：手写列布局而不是 LazyVerticalGrid，气泡内不需要虚拟化
        Column(Modifier.fillMaxWidth()) {
            for (r in 0 until (n + cols - 1) / cols) {
                Row(Modifier.fillMaxWidth()) {
                    for (c in 0 until cols) {
                        val i = r * cols + c
                        if (i >= n) { Spacer(Modifier.weight(1f)); continue }
                        val it = items.optJSONObject(i) ?: continue
                        val delta = it.optString("delta")
                        val up = delta.startsWith("+") || delta.startsWith("↑")
                        val down = delta.startsWith("-") || delta.startsWith("↓")
                        val color = if (up) ZxT.Ok else if (down) ZxT.Err else ZxT.Text3

                        Box(Modifier.weight(1f).padding(end = if (c < cols - 1) 7.dp else 0.dp,
                            bottom = 7.dp)) {
                            Column(Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(11.dp))
                                .background(ZxT.Surface2)
                                .border(1.dp, ZxT.Line, RoundedCornerShape(11.dp))
                                .clickable {
                                    ctx.callTool("zx_select", mapOf(
                                        "choice" to it.optString("k", "m$i"), "source" to "zx_metrics"))
                                }
                                .padding(10.dp, 11.dp)) {
                                Text(it.optString("k", ""), fontSize = 10.5.sp, color = ZxT.Text3,
                                     maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(3.dp))
                                Text(it.optString("v", "—"), fontSize = 19.sp,
                                     fontWeight = FontWeight.Bold, color = ZxT.Text)
                                if (delta.isNotBlank()) {
                                    Spacer(Modifier.height(2.dp))
                                    Text("${if (up) "↑" else if (down) "↓" else ""} ${delta.trimStart('+', '-', '↑', '↓').trim()}",
                                         fontSize = 10.5.sp, color = color)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /* ══════════════ zx_gantt 甘特图 ══════════════ */
    @Composable
    fun gantt(o: JSONObject, ctx: RenderContext) {
        val tasks = o.optJSONArray("tasks") ?: return
        val n = minOf(tasks.length(), 12)
        // 先算出时间轴范围，条的位置按 [start, end] 归一化
        val starts = DoubleArray(n); val ends = DoubleArray(n)
        for (i in 0 until n) {
            val t = tasks.optJSONObject(i) ?: continue
            starts[i] = t.optDouble("s", i.toDouble())
            ends[i] = t.optDouble("e", i + 1.0)
        }
        val min = starts.minOrNull() ?: 0.0
        val max = ends.maxOrNull() ?: 1.0
        val span = (max - min).coerceAtLeast(0.001)

        Column(Modifier.fillMaxWidth()) {
            // 时间刻度
            Row(Modifier.fillMaxWidth().padding(bottom = 5.dp)) {
                Box(Modifier.width(72.dp))
                for (i in 0..4) {
                    Text("%.0f".format(min + span * i / 4), fontSize = 9.sp, color = ZxT.Text3,
                         modifier = Modifier.weight(1f))
                }
            }
            for (i in 0 until n) {
                val t = tasks.optJSONObject(i) ?: continue
                val st = t.optString("st")
                val c = when (st) {
                    "done" -> ZxT.Ok; "run" -> ZxT.Primary
                    "block" -> ZxT.Err; else -> ZxT.Surface3
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(t.optString("n", ""), fontSize = 10.5.sp, color = ZxT.Text2,
                         modifier = Modifier.width(72.dp), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    // 条：外层轨道 + 内层进度，位置按归一化值偏移
                    Box(Modifier.weight(1f).height(16.dp)) {
                        val left = ((starts[i] - min) / span).toFloat().coerceIn(0f, 1f)
                        val w = ((ends[i] - starts[i]) / span).toFloat().coerceIn(0.04f, 1f)
                        Box(Modifier.fillMaxWidth(w).offset(x = (left * 100).dp.coerceAtMost(280.dp))
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(4.dp))
                            .background(c.copy(alpha = if (st == "todo") .5f else 1f))
                            .clickable {
                                ctx.callTool("zx_select", mapOf(
                                    "choice" to t.optString("n", "t$i"), "source" to "zx_gantt"))
                            })
                    }
                }
            }
        }
    }

    /* ══════════════ zx_tree 文件树 ══════════════ */
    @Composable
    fun tree(o: JSONObject, ctx: RenderContext) {
        val nodes = o.optJSONArray("nodes") ?: return
        // 展开状态按路径记忆，重组合时不会丢
        val open = remember { mutableStateMapOf<String, Boolean>() }

        fun walk(arr: JSONArray, depth: Int): List<Pair<JSONObject, Int>> {
            val out = mutableListOf<Pair<JSONObject, Int>>()
            for (i in 0 until arr.length()) {
                val nd = arr.optJSONObject(i) ?: continue
                out += nd to depth
                val key = nd.optString("path", nd.optString("n"))
                if (open[key] == true) {
                    val kids = nd.optJSONArray("children")
                    if (kids != null) out += walk(kids, depth + 1)
                }
            }
            return out
        }

        val flat = walk(nodes, 0)
        Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            flat.forEach { (nd, d) ->
                val name = nd.optString("n", "")
                val isDir = nd.optJSONArray("children") != null
                val key = nd.optString("path", name)
                val tag = nd.optString("tag")
                Row(Modifier.fillMaxWidth()
                    .clickable {
                        if (isDir) open[key] = open[key] != true
                        else ctx.callTool("zx_select", mapOf("choice" to key, "source" to "zx_tree"))
                    }
                    .padding(start = (d * 13).dp, top = 3.dp, bottom = 3.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if (isDir) if (open[key] == true) "▾" else "▸" else "·",
                         fontSize = 11.sp, color = ZxT.Text3, modifier = Modifier.width(13.dp))
                    Text(name, fontSize = 11.5.sp,
                         color = if (isDir) ZxT.Text else ZxT.Text2, maxLines = 1)
                    if (tag.isNotBlank()) {
                        Spacer(Modifier.width(6.dp))
                        Text(tag, fontSize = 9.sp, color = ZxT.PriLite,
                             modifier = Modifier.clip(RoundedCornerShape(4.dp))
                                 .background(ZxT.Primary.copy(.16f)).padding(4.dp, 1.dp))
                    }
                }
            }
        }
    }

    /* ══════════════ zx_flow 流程图（Canvas 连线） ══════════════ */
    @Composable
    fun flow(o: JSONObject, ctx: RenderContext) {
        val nodes = o.optJSONArray("nodes") ?: return
        val edges = o.optJSONArray("edges")
        val n = minOf(nodes.length(), 10)

        // 布局：单列纵向排列，节点位置可算，连线才画得准
        val H = 46f * n + 8f
        var sel by remember { mutableIntStateOf(-1) }

        Canvas(Modifier.fillMaxWidth().height(H.dp)) {
            val w = size.width
            val bw = w * 0.62f
            val bx = (w - bw) / 2
            val bh = 34f
            val gap = 46f

            fun cx(i: Int) = bx + bw / 2
            fun cy(i: Int) = 4f + gap * i + bh / 2

            // 先画连线，再画节点，线才不会盖住框
            if (edges != null) {
                for (e in 0 until edges.length()) {
                    val ed = edges.optJSONObject(e) ?: continue
                    val a = ed.optInt("from", -1); val b = ed.optInt("to", -1)
                    if (a < 0 || b < 0 || a >= n || b >= n) continue
                    val p = Path().apply {
                        moveTo(cx(a), cy(a) + bh / 2)
                        lineTo(cx(a), cy(a) + bh / 2 + (gap - bh) / 2)
                        lineTo(cx(b), cy(a) + bh / 2 + (gap - bh) / 2)
                        lineTo(cx(b), cy(b) - bh / 2)
                    }
                    drawPath(p, Color(0xFF3A4150), style = Stroke(width = 1.6f))
                    // 箭头
                    drawCircle(Color(0xFF3A4150), 2.2f, Offset(cx(b), cy(b) - bh / 2))
                }
            }
            for (i in 0 until n) {
                val nd = nodes.optJSONObject(i) ?: continue
                val st = nd.optString("st")
                val c = when (st) {
                    "done" -> ZxT.Ok; "run" -> ZxT.Primary; "err" -> ZxT.Err; else -> ZxT.Surface3
                }
                drawRoundRect(
                    color = if (i == sel) c else c.copy(alpha = .85f),
                    topLeft = Offset(bx, cy(i) - bh / 2),
                    size = Size(bw, bh),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f)
                )
                drawRoundRect(
                    color = if (i == sel) ZxT.Text else ZxT.Line,
                    topLeft = Offset(bx, cy(i) - bh / 2),
                    size = Size(bw, bh),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f),
                    style = Stroke(width = if (i == sel) 1.6f else 1f)
                )
            }
        }
        // 文字层单独用 Row 排，Canvas 里画中文要自己处理字体，代价不划算
        Column(Modifier.fillMaxWidth()) {
            for (i in 0 until n) {
                val nd = nodes.optJSONObject(i) ?: continue
                Box(Modifier.fillMaxWidth().height(46.dp), contentAlignment = Alignment.Center) {
                    Text(nd.optString("n", ""), fontSize = 11.5.sp,
                         color = if (i == sel) ZxT.Text else ZxT.Text2,
                         modifier = Modifier.clickable {
                             sel = i
                             ctx.callTool("zx_select", mapOf(
                                 "choice" to nd.optString("n", "n$i"), "source" to "zx_flow"))
                         })
                }
            }
        }
    }

    /* ══════════════ zx_calendar 日历 ══════════════ */
    @Composable
    fun calendar(o: JSONObject, ctx: RenderContext) {
        val days = o.optJSONArray("days")     // [{"d":1,"mark":"done"}]
        val month = o.optString("month", "")
        var sel by remember { mutableIntStateOf(o.optInt("sel", -1)) }
        val marks = remember(days) {
            val m = mutableMapOf<Int, String>()
            if (days != null) for (i in 0 until days.length()) {
                val d = days.optJSONObject(i) ?: continue
                m[d.optInt("d", -1)] = d.optString("mark")
            }
            m
        }
        // 首日偏移：1 号是周几。没给 firstDow 就按周日开局
        val off = o.optInt("firstDow", 0).coerceIn(0, 6)
        val total = o.optInt("total", 30).coerceIn(28, 31)

        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(month, fontSize = 12.5.sp, color = ZxT.Text, modifier = Modifier.weight(1f))
                Text(if (sel > 0) "已选 $sel 日" else "点选日期", fontSize = 10.5.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(7.dp))
            Row(Modifier.fillMaxWidth()) {
                listOf("日", "一", "二", "三", "四", "五", "六").forEach { w ->
                    Text(w, fontSize = 10.sp, color = ZxT.Text3,
                         modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            Spacer(Modifier.height(4.dp))
            val cells = off + total
            val rows = (cells + 6) / 7
            for (r in 0 until rows) {
                Row(Modifier.fillMaxWidth()) {
                    for (c in 0..6) {
                        val idx = r * 7 + c - off + 1
                        if (idx < 1 || idx > total) { Spacer(Modifier.weight(1f)); continue }
                        val mk = marks[idx]
                        val isSel = idx == sel
                        Box(Modifier.weight(1f).height(30.dp).padding(1.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (isSel) ZxT.Primary else Color.Transparent)
                            .clickable {
                                sel = idx
                                ctx.callTool("zx_select", mapOf(
                                    "choice" to idx.toString(), "source" to "zx_calendar"))
                            }, contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("$idx", fontSize = 11.sp,
                                     color = if (isSel) Color.White else ZxT.Text2)
                                if (!mk.isNullOrBlank()) {
                                    Box(Modifier.size(4.dp)
                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                        .background(when (mk) {
                                            "done" -> ZxT.Ok; "warn" -> ZxT.Warn
                                            "err" -> ZxT.Err; else -> ZxT.Text3 }))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /* ══════════════ zx_rating 评分卡 ══════════════ */
    @Composable
    fun rating(o: JSONObject, ctx: RenderContext) {
        val dims = o.optJSONArray("dims") ?: return
        val n = minOf(dims.length(), 8)
        // 每项分数独立记忆，提交时打包
        val scores = remember { mutableStateMapOf<Int, Int>() }

        Column(Modifier.fillMaxWidth()) {
            for (i in 0 until n) {
                val d = dims.optJSONObject(i) ?: continue
                val max = d.optInt("max", 5).coerceIn(2, 10)
                val cur = scores[i] ?: d.optInt("v", 0)
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(d.optString("n", ""), fontSize = 11.5.sp, color = ZxT.Text2,
                         modifier = Modifier.width(84.dp), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    Row(Modifier.weight(1f)) {
                        for (s in 1..max) {
                            Text(if (s <= cur) "★" else "☆", fontSize = 15.sp,
                                 color = if (s <= cur) ZxT.Warn else ZxT.Text3,
                                 modifier = Modifier.clickable { scores[i] = s }
                                     .padding(horizontal = 2.dp))
                        }
                    }
                    Text("$cur/$max", fontSize = 10.sp, color = ZxT.Text3)
                }
            }
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.Button(
                onClick = {
                    val payload = JSONObject()
                    for (i in 0 until n) {
                        val d = dims.optJSONObject(i) ?: continue
                        payload.put(d.optString("n", "d$i"), scores[i] ?: 0)
                    }
                    ctx.callTool("zx_form_submit", mapOf("payload" to payload.toString()))
                },
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = ZxT.Primary)
            ) { Text("提交评分", fontSize = 12.5.sp) }
        }
    }

    /* ══════════════ zx_editor 可编辑代码 ══════════════ */
    @Composable
    fun editor(o: JSONObject, ctx: RenderContext) {
        val initial = o.optString("code", "")
        var text by remember(o) { mutableStateOf(initial) }
        val dirty by remember(text, initial) { derivedStateOf { text != initial } }
        val lines = text.lineSequence().toList()

        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("lang", "kotlin"), fontSize = 10.sp, color = ZxT.PriLite,
                     modifier = Modifier.clip(RoundedCornerShape(5.dp))
                         .background(ZxT.Primary.copy(.16f)).padding(6.dp, 2.dp))
                Spacer(Modifier.width(7.dp))
                Text(o.optString("path", ""), fontSize = 10.5.sp, color = ZxT.Text3,
                     modifier = Modifier.weight(1f), maxLines = 1,
                     overflow = TextOverflow.Ellipsis)
                if (dirty) Text("已修改", fontSize = 10.sp, color = ZxT.Warn)
            }
            // 行号 + 编辑区：行号用固定宽度保证对齐
            Row(Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(9.dp))
                .background(Color(0xFF0C0E12))
                .border(1.dp, ZxT.Line, RoundedCornerShape(9.dp))) {
                Column(Modifier.padding(8.dp, 8.dp)) {
                    lines.forEachIndexed { i, _ ->
                        Text("${i + 1}", fontSize = 10.5.sp, color = ZxT.Text3,
                             fontFamily = ZxT.Mono, lineHeight = 15.sp)
                    }
                }
                androidx.compose.material3.TextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.weight(1f).heightIn(min = 80.dp, max = 220.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = ZxT.Mono, fontSize = 10.5.sp,
                        color = ZxT.Text2, lineHeight = 15.sp),
                    colors = androidx.compose.material3.TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = ZxT.Primary)
                )
            }
            if (dirty) {
                Spacer(Modifier.height(7.dp))
                Row(Modifier.fillMaxWidth()) {
                    androidx.compose.material3.Button(
                        onClick = { text = initial },
                        modifier = Modifier.weight(1f),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = ZxT.Surface3)) {
                        Text("还原", fontSize = 12.sp, color = ZxT.Text2)
                    }
                    Spacer(Modifier.width(8.dp))
                    androidx.compose.material3.Button(
                        onClick = {
                            ctx.callTool("zx_table_commit", mapOf(
                                "rows" to JSONObject().put("path", o.optString("path"))
                                    .put("code", text).toString()))
                        },
                        modifier = Modifier.weight(1f),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = ZxT.Primary)) {
                        Text("保存", fontSize = 12.sp)
                    }
                }
            }
        }
    }

    /* ══════════════ zx_audio 音频波形 ══════════════ */
    @Composable
    fun audio(o: JSONObject, ctx: RenderContext) {
        val peaks = o.optJSONArray("peaks")
        val n = if (peaks != null) minOf(peaks.length(), 64) else 48
        val amp = FloatArray(n) { i ->
            if (peaks != null) peaks.optDouble(i, .3).toFloat().coerceIn(.05f, 1f)
            else (.2f + .8f * kotlin.math.abs(kotlin.math.sin(i * .7f)))
        }
        val dur = o.optInt("dur", 0)
        var playing by remember { mutableStateOf(false) }
        var progress by remember { mutableFloatStateOf(o.optDouble("at", 0.0).toFloat()) }

        // 播放态下推进进度；停了就停，不留后台任务
        LaunchedEffect(playing) {
            while (playing) {
                progress = (progress + 0.004f).coerceAtMost(1f)
                if (progress >= 1f) playing = false
                kotlinx.coroutines.delay(40)
            }
        }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(ZxT.Primary).clickable { playing = !playing },
                    contentAlignment = Alignment.Center) {
                    Text(if (playing) "❚❚" else "▶", fontSize = 12.sp, color = Color.White)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(o.optString("title", "音频"), fontSize = 12.sp, color = ZxT.Text,
                         maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(fmtTime((progress * dur).toInt()) + " / " + fmtTime(dur),
                         fontSize = 10.sp, color = ZxT.Text3)
                }
            }
            Spacer(Modifier.height(8.dp))
            // 波形：Canvas 自绘，已播部分高亮
            Canvas(Modifier.fillMaxWidth().height(38.dp).clickable {
                // 点击定位（真实工程用 onGloballyPositioned 拿宽度算比例）
                ctx.callTool("zx_select", mapOf("choice" to "seek", "source" to "zx_audio"))
            }) {
                val w = size.width; val h = size.height
                val bw = w / n
                for (i in 0 until n) {
                    val bh = amp[i] * h * .9f
                    val played = i.toFloat() / n <= progress
                    drawRoundRect(
                        color = if (played) ZxT.PriLite else ZxT.Surface3,
                        topLeft = Offset(i * bw + bw * .18f, (h - bh) / 2),
                        size = Size(bw * .64f, bh),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5f, 1.5f)
                    )
                }
            }
        }
    }

    private fun fmtTime(s: Int): String = "%d:%02d".format(s / 60, s % 60)

    /* ══════════════ zx_heatmap 热力图 ══════════════ */
    @Composable
    fun heatmap(o: JSONObject, ctx: RenderContext) {
        val rows = o.optJSONArray("rows") ?: return
        val r = minOf(rows.length(), 14)
        var hover by remember { mutableIntStateOf(-1) }
        val max = remember(rows) {
            var m = 1.0
            for (i in 0 until r) {
                val arr = rows.optJSONArray(i) ?: continue
                for (j in 0 until arr.length()) m = maxOf(m, arr.optDouble(j, 0.0))
            }
            m
        }

        Canvas(Modifier.fillMaxWidth().height((r * 15 + 4).dp)) {
            for (i in 0 until r) {
                val arr = rows.optJSONArray(i) ?: continue
                val c = minOf(arr.length(), 24)
                val bw = size.width / c
                for (j in 0 until c) {
                    val v = (arr.optDouble(j, 0.0) / max).toFloat()
                    // 靛蓝 → 青的插值，低值不发灰，保证弱信号也看得见
                    val col = Color(
                        red = (0.31f + .38f * v).coerceIn(0f, 1f),
                        green = (0.27f + .55f * v).coerceIn(0f, 1f),
                        blue = (0.89f - .30f * v).coerceIn(0f, 1f)
                    )
                    drawRoundRect(
                        color = col,
                        topLeft = Offset(j * bw + 1, i * 15f + 1),
                        size = Size(bw - 2, 13f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f)
                    )
                }
            }
        }
        if (hover >= 0) Text("第 ${hover + 1} 行", fontSize = 10.sp, color = ZxT.Text3)
    }

    /* ══════════════ zx_toggle 开关列表 ══════════════ */
    @Composable
    fun toggle(o: JSONObject, ctx: RenderContext) {
        val items = o.optJSONArray("items") ?: return
        val n = minOf(items.length(), 10)
        val state = remember {
            mutableStateMapOf<Int, Boolean>().apply {
                for (i in 0 until n) put(i, items.optJSONObject(i)?.optBoolean("on", false) ?: false)
            }
        }

        Column(Modifier.fillMaxWidth()) {
            for (i in 0 until n) {
                val it = items.optJSONObject(i) ?: continue
                val on = state[i] == true
                val locked = it.optBoolean("locked", false)
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(it.optString("n", ""), fontSize = 12.sp, color = ZxT.Text)
                        val d = it.optString("d")
                        if (d.isNotBlank()) Text(d, fontSize = 10.5.sp, color = ZxT.Text3,
                                                 lineHeight = 14.sp)
                    }
                    Spacer(Modifier.width(9.dp))
                    // 开关本体：轨道 + 滑块，滑块位移用动画
                    val x by animateFloatAsState(if (on) 1f else 0f, tween(180), label = "sw")
                    Box(Modifier.width(38.dp).height(21.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(if (on) ZxT.Primary else ZxT.Surface3)
                        .clickable(enabled = !locked) {
                            val nv = !on
                            state[i] = nv
                            ctx.callTool("zx_toggle", mapOf(
                                "key" to it.optString("k", "t$i"), "on" to nv.toString()))
                        }) {
                        Box(Modifier.size(17.dp)
                            .offset(x = (3 + x * 15).dp, y = 2.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(Color.White))
                    }
                }
            }
        }
    }

    /* ══════════════ zx_qr 二维码（自绘） ══════════════ */
    @Composable
    fun qr(o: JSONObject, ctx: RenderContext) {
        val text = o.optString("text")
        if (text.isBlank()) {
            Text("二维码内容为空", fontSize = 11.sp, color = ZxT.Err); return
        }
        // 用内容哈希铺点阵 + 三个定位角。真实工程应接 zxing，
        // 但气泡里引三方库有运行时风险，这里先保证"有图案可扫"的占位与结构正确。
        val n = 25
        val grid = remember(text) { buildQrMatrix(text, n) }

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(150.dp)) {
                val cell = size.width / n
                for (r in 0 until n) for (c in 0 until n) {
                    if (!grid[r][c]) continue
                    drawRect(Color.Black, Offset(c * cell, r * cell), Size(cell, cell))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(o.optString("caption", "扫码"), fontSize = 10.5.sp, color = ZxT.Text3,
             modifier = Modifier.fillMaxWidth(),
             textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }

    /** 三个定位角 + 内容哈希铺点，保证结构可识别（占位实现） */
    private fun buildQrMatrix(text: String, n: Int): Array<BooleanArray> {
        val g = Array(n) { BooleanArray(n) }
        fun finder(r0: Int, c0: Int) {
            for (r in 0..6) for (c in 0..6) {
                val rr = r0 + r; val cc = c0 + c
                if (rr >= n || cc >= n) continue
                val edge = r == 0 || r == 6 || c == 0 || c == 6
                val core = r in 2..4 && c in 2..4
                g[rr][cc] = edge || core
            }
        }
        finder(0, 0); finder(0, n - 7); finder(n - 7, 0)
        var h = text.fold(0) { a, ch -> (a * 31 + ch.code) and 0x7FFFFFFF }
        for (r in 0 until n) for (c in 0 until n) {
            if (g[r][c]) continue
            if (r < 8 && c < 8) continue
            if (r < 8 && c >= n - 8) continue
            if (r >= n - 8 && c < 8) continue
            h = (h * 1103515245 + 12345) and 0x7FFFFFFF
            g[r][c] = ((h ushr 16) and 1) == 1
        }
        return g
    }

    /* ══════════════ zx_stack 卡片堆栈 ══════════════ */
    @Composable
    fun stack(o: JSONObject, ctx: RenderContext) {
        val cards = o.optJSONArray("cards") ?: return
        val n = minOf(cards.length(), 8)
        var idx by remember { mutableIntStateOf(0) }

        Column(Modifier.fillMaxWidth()) {
            // 只显示顶层 + 后面两张的露边，形成堆栈感
            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                for (k in 2 downTo 0) {
                    val i = idx + k
                    if (i >= n) continue
                    val c = cards.optJSONObject(i) ?: continue
                    Box(Modifier.fillMaxWidth(1f - k * .05f)
                        .height(110.dp)
                        .offset(y = (k * 6).dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(when (k) {
                            0 -> ZxT.Surface2; 1 -> ZxT.Surface2.copy(alpha = .7f); else -> ZxT.Surface3 })
                        .border(1.dp, ZxT.Line, RoundedCornerShape(13.dp))
                        .clickable {
                            if (k == 0) ctx.callTool("zx_select", mapOf(
                                "choice" to c.optString("k", "c$i"), "source" to "zx_stack"))
                            else if (k == 1) idx++
                        }
                        .padding(12.dp, 13.dp)) {
                        if (k == 0) {
                            Column {
                                Text(c.optString("t", ""), fontSize = 13.5.sp,
                                     fontWeight = FontWeight.SemiBold, color = ZxT.Text)
                                Spacer(Modifier.height(5.dp))
                                Text(c.optString("d", ""), fontSize = 11.sp, color = ZxT.Text3,
                                     lineHeight = 15.sp, maxLines = 3,
                                     overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${idx + 1}/$n", fontSize = 10.5.sp, color = ZxT.Text3)
                Spacer(Modifier.weight(1f))
                Text("跳过", fontSize = 11.sp, color = ZxT.Text2,
                     modifier = Modifier.clickable { if (idx < n - 1) idx++ }.padding(horizontal = 9.dp))
                Text("上一个", fontSize = 11.sp, color = ZxT.Text2,
                     modifier = Modifier.clickable { if (idx > 0) idx-- }.padding(horizontal = 9.dp))
            }
        }
    }
}
