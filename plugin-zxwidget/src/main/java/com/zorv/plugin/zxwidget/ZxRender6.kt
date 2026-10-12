package com.zorv.plugin.zxwidget

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 第六批组件（12 个）—— 数据密集 + 直接操纵。
 *
 * 这批的两个主题：
 *   一、把"看数据"的密度推到极限（词云/漏斗/树图/和弦/迷你趋势）
 *       —— 都是在小面积里塞进尽可能多的信息，靠编码而非文字
 *   二、把"直接操纵"推到极限（看板/热点/拖放/分栏）
 *       —— 用户直接在结果上改，而不是通过表单间接改
 *
 * 共同难点：都需要在极小面积内做命中判定。
 * 树图一个格子可能只有 20×14dp，看板一张卡可能只有 60dp 宽——
 * 命中区域必须比视觉区域大，否则手指点不中。
 */
object ZxRender6 {

    /** 命中容差：视觉区域外扩这么多，弥补手指精度 */
    private const val HIT_SLOP = 6

    private val PALETTE = listOf(
        Color(0xFF5046E4), Color(0xFF3FBF7F), Color(0xFFE8A33D),
        Color(0xFFE5544B), Color(0xFF4A9BE8), Color(0xFF8E86FF)
    )

    /* ══════════════ zx_wordcloud 词云 ══════════════ */
    @Composable
    fun wordcloud(o: JSONObject, ctx: RenderContext) {
        val words = o.optJSONArray("words") ?: return
        val n = minOf(words.length(), 24)
        val max = remember(words) {
            var m = 0.0
            for (i in 0 until n) m = maxOf(m, words.optJSONObject(i)?.optDouble("w", 0.0) ?: 0.0)
            m.coerceAtLeast(0.001)
        }
        var sel by remember { mutableIntStateOf(-1) }

        // 手写流式排布：逐词测量累计宽度，超宽换行
        // 不用 FlowRow 是为了能按权重决定字号后再算行
        Column(Modifier.fillMaxWidth()) {
            var rowW = 0f
            var row = mutableListOf<Pair<String, Float>>()
            val rows = mutableListOf<List<Pair<String, Float>>>()
            val availPx = 300f     // 近似可用宽度，实际以 weight 分配
            for (i in 0 until n) {
                val w = words.optJSONObject(i) ?: continue
                val t = w.optString("t")
                val ratio = ((w.optDouble("w", 0.0) / max).toFloat()).coerceIn(.15f, 1f)
                val fs = 10f + ratio * 14f          // 10–24sp
                val estW = t.length * fs * 0.62f
                if (rowW + estW > availPx && row.isNotEmpty()) {
                    rows.add(row); row = mutableListOf(); rowW = 0f
                }
                row.add(t to fs); rowW += estW + 8f
            }
            if (row.isNotEmpty()) rows.add(row)

            rows.forEachIndexed { ri, r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    r.forEach { (t, fs) ->
                        val idx = (0 until n).firstOrNull { words.optJSONObject(it)?.optString("t") == t } ?: -1
                        val on = idx == sel
                        Text(t, fontSize = fs.sp,
                             color = if (on) ZxT.PriLite else PALETTE[(ri + t.length) % PALETTE.size].copy(.82f),
                             fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                             modifier = Modifier.padding(end = 7.dp).clickable {
                                 sel = idx
                                 ctx.callTool("zx_select", mapOf("choice" to t, "source" to "zx_wordcloud"))
                             })
                    }
                }
            }
            Text("字号编码权重，点词回传", fontSize = 10.sp, color = ZxT.Text3,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════════ zx_funnel 漏斗图 ══════════════ */
    @Composable
    fun funnel(o: JSONObject, ctx: RenderContext) {
        val stages = o.optJSONArray("stages") ?: return
        val n = minOf(stages.length(), 8)
        if (n < 2) { Text("漏斗至少 2 级", fontSize = 11.sp, color = ZxT.Err); return }
        val top = (stages.optJSONObject(0)?.optDouble("v", 0.0) ?: 0.0).coerceAtLeast(1.0)

        Column(Modifier.fillMaxWidth()) {
            for (i in 0 until n) {
                val s = stages.optJSONObject(i) ?: continue
                val v = s.optDouble("v", 0.0)
                val w = (v / top).toFloat().coerceIn(.08f, 1f)
                val prev = if (i == 0) v else stages.optJSONObject(i - 1)?.optDouble("v", 0.0) ?: v
                val drop = if (prev > 0) (1 - v / prev) * 100 else 0.0
                val c = PALETTE[i % PALETTE.size]
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    // 梯形：用 clip 的 shape 做不到，改用两个 Box 叠出缩进效果
                    Box(Modifier.weight(1f).height(30.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.fillMaxWidth(w).fillMaxHeight()
                            .clip(RoundedCornerShape(4.dp))
                            .background(Brush.horizontalGradient(
                                listOf(c.copy(.55f), c.copy(.9f)))))
                        Text(s.optString("n", ""), fontSize = 11.sp, color = Color.White,
                             maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.width(62.dp), horizontalAlignment = Alignment.End) {
                        Text("%.0f".format(v), fontSize = 11.sp, color = ZxT.Text2)
                        if (i > 0) Text("↓${"%.0f".format(drop)}%", fontSize = 9.5.sp,
                                        color = if (drop > 50) ZxT.Err else ZxT.Text3)
                    }
                }
                if (i < n - 1 && drop > 0) {
                    Text("流失 ${"%.0f".format(drop)}%", fontSize = 9.5.sp,
                         color = ZxT.Text3, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
                }
            }
        }
    }

    /* ══════════════ zx_treemap 树图 ══════════════ */
    @Composable
    fun treemap(o: JSONObject, ctx: RenderContext) {
        val items = o.optJSONArray("items") ?: return
        val n = minOf(items.length(), 10)
        val total = remember(items) {
            var t = 0.0
            for (i in 0 until n) t += items.optJSONObject(i)?.optDouble("v", 0.0) ?: 0.0
            t.coerceAtLeast(0.001)
        }
        var sel by remember { mutableIntStateOf(-1) }

        // 递归切分：按累计占比决定每个块的宽高
        // 简化为两行布局——气泡里高度有限，做完整 squarified 不划算
        val half = (n + 1) / 2
        Column(Modifier.fillMaxWidth()) {
            listOf(0 until half, half until n).forEach { range ->
                Row(Modifier.fillMaxWidth().height(52.dp).padding(vertical = 2.dp)) {
                    range.forEach { i ->
                        val it = items.optJSONObject(i) ?: return@forEach
                        val frac = ((it.optDouble("v", 0.0) / total).toFloat()).coerceIn(.06f, 1f)
                        val c = PALETTE[i % PALETTE.size]
                        Box(Modifier.weight(frac).fillMaxHeight().padding(1.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(c.copy(if (sel == i) .95f else .62f))
                            .border(if (sel == i) 1.6.dp else 0.dp, ZxT.Text, RoundedCornerShape(6.dp))
                            .clickable {
                                sel = i
                                ctx.callTool("zx_select", mapOf(
                                    "choice" to it.optString("k", "t$i"), "source" to "zx_treemap"))
                            }.padding(4.dp)) {
                            // 面积太小时只显示百分比，避免文字溢出
                            if (frac > .12f) {
                                Column {
                                    Text(it.optString("n", ""), fontSize = 9.5.sp, color = Color.White,
                                         maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${(frac * 100).toInt()}%", fontSize = 10.sp,
                                         color = Color.White.copy(.8f))
                                }
                            } else {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("${(frac * 100).toInt()}", fontSize = 9.sp,
                                         color = Color.White.copy(.85f))
                                }
                            }
                        }
                    }
                }
            }
            Text("面积编码占比 · 格子过小只显示百分比",
                 fontSize = 10.sp, color = ZxT.Text3, modifier = Modifier.padding(top = 4.dp))
        }
    }

    /* ══════════════ zx_chord 和弦图 ══════════════ */
    @Composable
    fun chord(o: JSONObject, ctx: RenderContext) {
        val nodes = o.optJSONArray("nodes") ?: return
        val links = o.optJSONArray("links")
        val n = minOf(nodes.length(), 8)
        if (n < 2) { Text("和弦图至少 2 个节点", fontSize = 11.sp, color = ZxT.Err); return }

        val out = DoubleArray(n)
        var total = 0.0
        if (links != null) {
            for (i in 0 until minOf(links.length(), 20)) {
                val l = links.optJSONObject(i) ?: continue
                val f = l.optInt("from", -1); val t = l.optInt("to", -1)
                val v = l.optDouble("v", 0.0)
                if (f in 0 until n && t in 0 until n) { out[f] += v; total += v }
            }
        }
        total = total.coerceAtLeast(0.001)

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(150.dp)) {
                val cx = size.width / 2; val cy = size.height / 2
                val r = minOf(cx, cy) * .78f
                var ang = -PI.toFloat() / 2
                for (i in 0 until n) {
                    val sweep = (out[i] / total * 2 * PI.toFloat()).toFloat()
                    val c = PALETTE[i % PALETTE.size]
                    // 外弧
                    drawArc(c.copy(.75f), ang * 180f / PI.toFloat(), sweep * 180f / PI.toFloat(),
                            false, topLeft = Offset(cx - r, cy - r), size = Size(r * 2, r * 2),
                            style = Stroke(width = 13f))
                    // 弦：从本弧中点到目标弧中点，二次贝塞尔往圆心弯
                    if (links != null) {
                        for (k in 0 until minOf(links.length(), 20)) {
                            val l = links.optJSONObject(k) ?: continue
                            if (l.optInt("from", -1) != i) continue
                            val t = l.optInt("to", 0)
                            val a1 = ang + sweep * .5f
                            val a2 = ang + sweep * .5f + PI.toFloat() * .6f
                            val p = Path().apply {
                                moveTo(cx + r * cos(a1), cy + r * sin(a1))
                                quadraticBezierTo(cx * .82f, cy * .82f,
                                                  cx + r * cos(a2), cy + r * sin(a2))
                            }
                            drawPath(p, c.copy(.28f), style = Stroke(width = 3f))
                        }
                    }
                    ang += sweep + .06f
                }
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (i in 0 until n) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(PALETTE[i % PALETTE.size]))
                    Spacer(Modifier.width(4.dp))
                    Text(nodes.optString(i, ""), fontSize = 10.sp, color = ZxT.Text3)
                }
            }
        }
    }

    /* ══════════════ zx_sparkline 迷你趋势 ══════════════ */
    @Composable
    fun sparkline(o: JSONObject, ctx: RenderContext) {
        val pts = o.optJSONArray("points") ?: return
        val n = minOf(pts.length(), 40)
        if (n < 2) { Text("迷你趋势至少 2 个点", fontSize = 11.sp, color = ZxT.Err); return }
        val vals = FloatArray(n) { pts.optDouble(it, 0.0).toFloat() }
        val min = vals.min(); val max = vals.max()
        val span = (max - min).coerceAtLeast(0.0001f)
        val up = vals[n - 1] >= vals[0]
        val c = if (up) ZxT.Ok else ZxT.Err
        val last = vals[n - 1]

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (o.optString("label").isNotBlank()) {
                Column(Modifier.width(72.dp)) {
                    Text(o.optString("label"), fontSize = 10.5.sp, color = ZxT.Text3,
                         maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (last == last.toLong().toFloat()) last.toLong().toString()
                         else "%.1f".format(last), fontSize = 14.sp,
                         fontWeight = FontWeight.SemiBold, color = ZxT.Text)
                }
                Spacer(Modifier.width(10.dp))
            }
            Box(Modifier.weight(1f).height(30.dp)) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val p = Path()
                    for (i in 0 until n) {
                        val x = size.width * i / (n - 1)
                        val y = size.height - (vals[i] - min) / span * size.height * .86f - size.height * .07f
                        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                    }
                    // 面积填充：只填不描边，30dp 高里描边看不清
                    val area = Path().apply {
                        addPath(p)
                        lineTo(size.width, size.height); lineTo(0f, size.height); close()
                    }
                    drawPath(area, Brush.verticalGradient(listOf(c.copy(.35f), Color.Transparent)))
                    drawPath(p, c, style = Stroke(width = 2f))
                    // 末点强调
                    val lx = size.width
                    val ly = size.height - (last - min) / span * size.height * .86f - size.height * .07f
                    drawCircle(c, 3.2f, Offset(lx - 2, ly))
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(if (up) "▲" else "▼", fontSize = 10.sp, color = c)
        }
    }

    /* ══════════════ zx_kanban 看板 ══════════════ */
    @Composable
    fun kanban(o: JSONObject, ctx: RenderContext) {
        val cols = o.optJSONArray("cols") ?: return
        val nc = minOf(cols.length(), 4)
        // cards: [{k, t, col}]
        val cards = remember { mutableStateListOf<Triple<String, String, Int>>() }
        LaunchedEffect(Unit) {
            val src = o.optJSONArray("cards")
            if (src != null) for (i in 0 until minOf(src.length(), 20)) {
                val c = src.optJSONObject(i) ?: continue
                cards.add(Triple(c.optString("k", "c$i"), c.optString("t", ""), c.optInt("col", 0)))
            }
        }
        var dragging by remember { mutableIntStateOf(-1) }
        var dx by remember { mutableFloatStateOf(0f) }

        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (ci in 0 until nc) {
                val col = cols.optJSONObject(ci) ?: continue
                Column(Modifier.width(96.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(ZxT.Surface2)
                    .border(1.dp, if (dragging >= 0 && cards.getOrNull(dragging)?.third != ci) ZxT.Primary.copy(.4f) else ZxT.Line,
                            RoundedCornerShape(10.dp))
                    .padding(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(col.optString("n", ""), fontSize = 10.5.sp, color = ZxT.Text2,
                             modifier = Modifier.weight(1f), maxLines = 1,
                             overflow = TextOverflow.Ellipsis)
                        Text("${cards.count { it.third == ci }}", fontSize = 9.5.sp, color = ZxT.Text3)
                    }
                    Spacer(Modifier.height(5.dp))
                    cards.forEachIndexed { i, c ->
                        if (c.third != ci) return@forEachIndexed
                        Box(Modifier.fillMaxWidth().padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (dragging == i) ZxT.Primary else ZxT.Surface3)
                            .pointerInput(i) {
                                detectDragGestures(
                                    onDragStart = { dragging = i; dx = 0f },
                                    onDragEnd = {
                                        // 按横向位移算目标列，列宽约 104dp
                                        val target = (c.third + (dx / 104f).toInt()).coerceIn(0, nc - 1)
                                        if (target != c.third) {
                                            cards[i] = c.copy(third = target)
                                            ctx.callTool("zx_form_submit", mapOf("payload" to
                                                JSONObject().put("k", c.first).put("from", c.third)
                                                    .put("to", target).toString()))
                                        }
                                        dragging = -1; dx = 0f
                                    }
                                ) { _, d -> dx += d.x }
                            }
                            .padding(7.dp, 6.dp)) {
                            Text(c.second, fontSize = 10.5.sp,
                                 color = if (dragging == i) Color.White else ZxT.Text2,
                                 maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }

    /* ══════════════ zx_hotspot 图片热点 ══════════════ */
    @Composable
    fun hotspot(o: JSONObject, ctx: RenderContext) {
        val src = o.optString("src")
        val spots = o.optJSONArray("spots")
        val marks = remember { mutableStateListOf<Triple<Float, Float, String>>() }
        LaunchedEffect(Unit) {
            if (spots != null) for (i in 0 until minOf(spots.length(), 12)) {
                val s = spots.optJSONObject(i) ?: continue
                marks.add(Triple(s.optDouble("x", .5).toFloat(),
                                 s.optDouble("y", .5).toFloat(), s.optString("t", "")))
            }
        }
        var sel by remember { mutableIntStateOf(-1) }

        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(140.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(ZxT.Surface3)
                .border(1.dp, ZxT.Line, RoundedCornerShape(10.dp))
                .clickable {
                    // 演示：点空白处加一个标记，真实工程应读图片
                    marks.add(Triple(.5f, .5f, "标记 ${marks.size + 1}"))
                }) {
                if (src.isBlank()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("（图片占位）点空白添加标记", fontSize = 10.5.sp, color = ZxT.Text3)
                    }
                }
                marks.forEachIndexed { i, m ->
                    Box(Modifier.align(Alignment.TopStart)
                        .offset(x = (m.first * 280).dp, y = (m.second * 100).dp)
                        .size(if (sel == i) 18.dp else 14.dp)
                        .clip(CircleShape)
                        .background(if (sel == i) ZxT.Warn else ZxT.Primary)
                        .border(1.5.dp, Color.White, CircleShape)
                        .clickable {
                            sel = i
                            ctx.callTool("zx_select", mapOf("choice" to m.third,
                                                            "source" to "zx_hotspot"))
                        })
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Text("${marks.size} 个标记", fontSize = 10.5.sp, color = ZxT.Text3,
                     modifier = Modifier.weight(1f))
                Text("清空", fontSize = 11.sp, color = ZxT.Text2,
                     modifier = Modifier.clickable { marks.clear(); sel = -1 }.padding(horizontal = 8.dp))
            }
            Text("坐标存 0-1 相对值，换图换屏都不失效",
                 fontSize = 10.sp, color = ZxT.Text3, modifier = Modifier.padding(top = 3.dp))
        }
    }

    /* ══════════════ zx_stepper 步进器 ══════════════ */
    @Composable
    fun stepper(o: JSONObject, ctx: RenderContext) {
        val min = o.optDouble("min", 0.0)
        val max = o.optDouble("max", 99.0)
        val step = o.optDouble("step", 1.0)
        var v by remember { mutableDoubleStateOf(o.optDouble("v", min).coerceIn(min, max)) }

        fun set(nv: Double) {
            v = nv.coerceIn(min, max)
            ctx.callTool("zx_form_submit", mapOf("payload" to JSONObject().put("v", v).toString()))
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (o.optString("label").isNotBlank())
                Text(o.optString("label"), fontSize = 12.sp, color = ZxT.Text2,
                     modifier = Modifier.weight(1f))
            else Spacer(Modifier.weight(1f))
            listOf("−", "+").forEachIndexed { i, s ->
                Box(Modifier.size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(ZxT.Surface3)
                    .clickable { set(if (i == 0) v - step else v + step) },
                    contentAlignment = Alignment.Center) {
                    Text(s, fontSize = 16.sp, color = ZxT.Text2)
                }
                if (i == 0) {
                    Text(fmt(v), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                         color = ZxT.Text, textAlign = TextAlign.Center,
                         modifier = Modifier.widthIn(min = 46.dp))
                }
            }
        }
    }

    private fun fmt(v: Double): String =
        if (v == kotlin.math.floor(v) && kotlin.math.abs(v) < 1e9) v.toLong().toString()
        else "%.2f".format(v)

    /* ══════════════ zx_segmented 分段控件 ══════════════ */
    @Composable
    fun segmented(o: JSONObject, ctx: RenderContext) {
        val segs = o.optJSONArray("segs") ?: return
        val n = minOf(segs.length(), 5)
        var sel by remember { mutableIntStateOf(o.optInt("sel", 0).coerceIn(0, n - 1)) }

        Row(Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(ZxT.Surface3)
            .padding(2.dp)) {
            for (i in 0 until n) {
                val s = segs.optJSONObject(i)
                val label = s?.optString("t") ?: segs.optString(i)
                val on = i == sel
                Box(Modifier.weight(1f)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (on) ZxT.Primary else Color.Transparent)
                    .clickable {
                        sel = i
                        val k = s?.optString("k") ?: label
                        ctx.callTool("zx_select", mapOf("choice" to k, "index" to i.toString(),
                                                        "source" to "zx_segmented"))
                    }
                    .padding(vertical = 7.dp), contentAlignment = Alignment.Center) {
                    Text(label, fontSize = 11.5.sp, color = if (on) Color.White else ZxT.Text3,
                         maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

    /* ══════════════ zx_mention @提及输入 ══════════════ */
    @Composable
    fun mention(o: JSONObject, ctx: RenderContext) {
        val people = o.optJSONArray("people") ?: return
        val n = minOf(people.length(), 10)
        var text by remember { mutableStateOf(o.optString("text", "")) }
        var show by remember { mutableStateOf(false) }
        var q by remember { mutableStateOf("") }

        Column(Modifier.fillMaxWidth()) {
            androidx.compose.material3.TextField(
                value = text,
                onValueChange = { nv ->
                    text = nv
                    // 末尾是 @ 或 @xxx 就弹候选
                    val at = nv.lastIndexOf('@')
                    if (at >= 0 && (at == nv.length - 1 || !nv.substring(at).contains(' '))) {
                        show = true; q = nv.substring(at + 1)
                    } else show = false
                },
                placeholder = { Text(o.optString("hint", "输入 @ 提及某人"), fontSize = 11.sp, color = ZxT.Text3) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp, max = 96.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = ZxT.Text,
                                                               lineHeight = 17.sp),
                colors = androidx.compose.material3.TextFieldDefaults.colors(
                    focusedContainerColor = ZxT.Surface2,
                    unfocusedContainerColor = ZxT.Surface2,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = ZxT.Primary)
            )
            if (show) {
                val cand = (0 until n).map { people.optJSONObject(it) }
                    .filter { it != null && (q.isBlank() ||
                              it.optString("n").contains(q, true) || it.optString("k").contains(q, true)) }
                if (cand.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth().padding(top = 5.dp)
                        .clip(RoundedCornerShape(9.dp)).background(ZxT.Surface2)
                        .border(1.dp, ZxT.Line, RoundedCornerShape(9.dp))) {
                        cand.take(5).forEach { p ->
                            val pp = p ?: return@forEach
                            Row(Modifier.fillMaxWidth()
                                .clickable {
                                    val at = text.lastIndexOf('@')
                                    text = text.substring(0, at) + "@" + pp.optString("n") + " "
                                    show = false; q = ""
                                }.padding(10.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(22.dp).clip(CircleShape).background(ZxT.Surface3),
                                    contentAlignment = Alignment.Center) {
                                    Text(pp.optString("n", "?").take(1), fontSize = 10.sp, color = ZxT.Text2)
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(pp.optString("n", ""), fontSize = 11.5.sp, color = ZxT.Text2)
                                if (pp.optString("r").isNotBlank())
                                    Text("  ${pp.optString("r")}", fontSize = 10.sp, color = ZxT.Text3)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(7.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${text.length} 字", fontSize = 10.sp, color = ZxT.Text3,
                     modifier = Modifier.weight(1f))
                Text("发送", fontSize = 12.sp,
                     color = if (text.isBlank()) ZxT.Text3 else ZxT.PriLite,
                     modifier = Modifier.clickable(enabled = text.isNotBlank()) {
                         // 回传结构化提及，不让模型自己去解析文本
                         val arr = JSONArray()
                         for (i in 0 until n) {
                             val p = people.optJSONObject(i) ?: continue
                             if (text.contains("@" + p.optString("n")))
                                 arr.put(p.optString("k", p.optString("n")))
                         }
                         ctx.callTool("zx_form_submit", mapOf("payload" to
                             JSONObject().put("text", text).put("mentions", arr).toString()))
                         text = ""
                     })
            }
        }
    }

    /* ══════════════ zx_dropzone 拖放区 ══════════════ */
    @Composable
    fun dropzone(o: JSONObject, ctx: RenderContext) {
        val accepts = o.optJSONArray("accept")
        val acceptSet = remember(accepts) {
            buildSet {
                if (accepts != null) for (i in 0 until accepts.length()) add(accepts.optString(i))
            }
        }
        val dropped = remember { mutableStateListOf<String>() }
        var hot by remember { mutableStateOf(false) }
        var err by remember { mutableStateOf("") }

        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(92.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(if (hot) ZxT.Primary.copy(.16f) else ZxT.Surface2)
                .border(if (err.isNotBlank()) 1.4.dp else if (hot) 1.8.dp else 1.dp,
                        if (err.isNotBlank()) ZxT.Err else if (hot) ZxT.Primary else ZxT.Line,
                        RoundedCornerShape(11.dp))
                .pointerInput(Unit) {
                    // 真实工程应接宿主的拖放回调；这里用点击模拟放置
                    detectDragGestures(
                        onDragStart = { hot = true; err = "" },
                        onDragEnd = { hot = false }
                    ) { _, _ -> }
                }
                .clickable {
                    // 演示：模拟放入一个文件
                    val name = "demo_${dropped.size + 1}.png"
                    val ext = name.substringAfterLast('.')
                    if (acceptSet.isNotEmpty() && ext !in acceptSet) {
                        err = "不接受 .$ext 类型${if (acceptSet.isNotEmpty()) "，仅 ${acceptSet.joinToString("/")}" else ""}"
                    } else { dropped.add(name); err = "" }
                }, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (err.isNotBlank()) "✕" else "＋", fontSize = 18.sp,
                         color = if (err.isNotBlank()) ZxT.Err else ZxT.Text3)
                    Spacer(Modifier.height(3.dp))
                    Text(if (err.isNotBlank()) err else o.optString("hint", "拖入或点击选择"),
                         fontSize = 10.5.sp,
                         color = if (err.isNotBlank()) ZxT.Err else ZxT.Text3)
                }
            }
            if (dropped.isNotEmpty()) {
                Spacer(Modifier.height(7.dp))
                dropped.forEach { f ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)
                        .clip(RoundedCornerShape(7.dp)).background(ZxT.Surface2)
                        .padding(8.dp, 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(f, fontSize = 11.sp, color = ZxT.Text2, modifier = Modifier.weight(1f),
                             maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("×", fontSize = 12.sp, color = ZxT.Text3,
                             modifier = Modifier.clickable { dropped.remove(f) }.padding(start = 8.dp))
                    }
                }
                Spacer(Modifier.height(7.dp))
                androidx.compose.material3.Button(
                    onClick = { ctx.callTool("zx_form_submit", mapOf("payload" to
                        JSONObject().put("files", JSONArray().apply { dropped.forEach { put(it) } }).toString())) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = ZxT.Primary)
                ) { Text("上传 ${dropped.size} 个", fontSize = 12.5.sp) }
            }
        }
    }

    /* ══════════════ zx_resizable 可拖拽分栏 ══════════════ */
    @Composable
    fun resizable(o: JSONObject, ctx: RenderContext) {
        val left = o.optJSONArray("left") ?: return
        val right = o.optJSONArray("right") ?: return
        // 比例 0.2–0.8：两端留最小宽度，否则一边会被拖没
        var ratio by remember { mutableFloatStateOf(o.optDouble("ratio", .5).toFloat().coerceIn(.2f, .8f)) }

        Row(Modifier.fillMaxWidth().height(110.dp)) {
            Box(Modifier.weight(ratio).fillMaxHeight()
                .clip(RoundedCornerShape(9.dp)).background(ZxT.Surface2)
                .border(1.dp, ZxT.Line, RoundedCornerShape(9.dp))
                .padding(8.dp)) {
                Column {
                    Text("左 · ${(ratio * 100).toInt()}%", fontSize = 10.sp, color = ZxT.Text3)
                    for (i in 0 until minOf(left.length(), 5))
                        Text(left.optString(i, ""), fontSize = 11.sp, color = ZxT.Text2,
                             modifier = Modifier.padding(top = 3.dp), maxLines = 1,
                             overflow = TextOverflow.Ellipsis)
                }
            }
            // 分隔条：视觉 6dp，命中区域外扩到 24dp
            Box(Modifier.width(24.dp).fillMaxHeight()
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        ratio = (change.position.x / size.width).coerceIn(.2f, .8f)
                    }
                }, contentAlignment = Alignment.Center) {
                Box(Modifier.width(6.dp).fillMaxHeight(.6f)
                    .clip(RoundedCornerShape(3.dp)).background(ZxT.Primary.copy(.7f)))
            }
            Box(Modifier.weight(1f - ratio).fillMaxHeight()
                .clip(RoundedCornerShape(9.dp)).background(ZxT.Surface2)
                .border(1.dp, ZxT.Line, RoundedCornerShape(9.dp))
                .padding(8.dp)) {
                Column {
                    Text("右 · ${((1 - ratio) * 100).toInt()}%", fontSize = 10.sp, color = ZxT.Text3)
                    for (i in 0 until minOf(right.length(), 5))
                        Text(right.optString(i, ""), fontSize = 11.sp, color = ZxT.Text2,
                             modifier = Modifier.padding(top = 3.dp), maxLines = 1,
                             overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Text("拖分隔条调比例，两端各留 20% 最小宽度",
             fontSize = 10.sp, color = ZxT.Text3, modifier = Modifier.padding(top = 5.dp))
    }
}
