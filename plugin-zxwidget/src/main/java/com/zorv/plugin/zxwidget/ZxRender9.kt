package com.zorv.plugin.zxwidget

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 第九批组件（12 个）—— 复合图表 / 结构图 / 复合容器。
 *
 * 前八批已经覆盖了绝大多数"单意图"组件。这批是最后的拼图，
 * 分三类：
 *
 *   一、复合图表。瀑布、金字塔、气泡、韦恩、K线——
 *      这些不是"换一种画法"，而是**换一种问法**：
 *      瀑布图回答"从 A 到 B 中间发生了什么"，
 *      韦恩图回答"两组的交集是什么"。
 *      用柱状图硬答这两个问题，读者要自己在脑子里做减法。
 *
 *   二、结构图。DAG、泳道、思维导图——
 *      表达的是**关系**而不是数值，之前只有 zx_tree（树形）
 *      和 zx_flow（流程），覆盖不到"多对多依赖"和"跨角色分带"。
 *
 *   三、复合容器。画廊、向导、电子表格、模拟对话——
 *      这些是"一张卡里装一组东西"，之前要拆成多张卡，
 *      但它们的语义是一体的（向导的几步属于同一个任务）。
 *
 * zx_waterfall / zx_pyramid / zx_bubble / zx_venn / zx_candle
 * zx_dag / zx_swimlane / zx_mindmap / zx_gallery / zx_wizard
 * zx_spreadsheet / zx_conversation
 */
object ZxRender9 {

    private val PAL = listOf(
        Color(0xFF5046E4), Color(0xFF3FBF7F), Color(0xFFE8A33D),
        Color(0xFFE5544B), Color(0xFF4A9BE8), Color(0xFF8E86FF)
    )

    /* ══════════ zx_waterfall 瀑布图 ══════════ */
    @Composable
    fun waterfall(o: JSONObject, ctx: RenderContext) {
        val steps = o.optJSONArray("steps") ?: return
        val n = minOf(steps.length(), 8)
        val list = (0 until n).map { i ->
            val s = steps.optJSONObject(i) ?: JSONObject()
            s.optString("n", "") to s.optDouble("v", 0.0)
        }
        // 累计位置：瀑布图的每根柱子是"从哪到哪"，不是"有多高"
        val cum = DoubleArray(n + 1)
        for (i in 0 until n) cum[i + 1] = cum[i] + list[i].second
        val lo = min(0.0, cum.minOrNull() ?: 0.0)
        val hi = max(0.0, cum.maxOrNull() ?: 0.0)
        val span = (hi - lo).coerceAtLeast(0.001)
        var sel by remember { mutableIntStateOf(-1) }

        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(110.dp)) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val w = size.width / n
                    val bw = w * 0.62f
                    val base = size.height - 12f
                    fun y(v: Double) = (base - ((v - lo) / span * (base - 14f))).toFloat()
                    for (i in 0 until n) {
                        val (_, v) = list[i]
                        val y0 = y(cum[i]); val y1 = y(cum[i + 1])
                        val top = min(y0, y1); val h = abs(y1 - y0).coerceAtLeast(1.5f)
                        val col = if (v >= 0) ZxT.Ok else ZxT.Err
                        drawRect(
                            if (sel == i) col else col.copy(.68f),
                            Offset(i * w + (w - bw) / 2, top), Size(bw, h))
                        // 连接线：断开就看不出"累计"这件事
                        if (i < n - 1) drawLine(
                            ZxT.Text3.copy(.5f),
                            Offset(i * w + w / 2 + bw / 2, y1),
                            Offset((i + 1) * w + w / 2 - bw / 2, y1), 1.4f)
                    }
                }
            }
            Spacer(Modifier.height(5.dp))
            Row(Modifier.fillMaxWidth()) {
                list.forEachIndexed { i, (nm, v) ->
                    Column(Modifier.weight(1f)
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (sel == i) ZxT.Surface2 else Color.Transparent)
                        .clickable {
                            sel = i
                            ctx.callTool("zx_select", mapOf(
                                "choice" to nm, "v" to v.toString(),
                                "cum" to cum[i + 1].toString(), "source" to "zx_waterfall"))
                        }.padding(vertical = 3.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(nm, fontSize = 8.5.sp, color = ZxT.Text3, maxLines = 1,
                             overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        Text(if (v >= 0) "+${fmt(v)}" else fmt(v), fontSize = 9.5.sp,
                             color = if (v >= 0) ZxT.Ok else ZxT.Err, fontFamily = ZxT.Mono)
                    }
                }
            }
            Text("回答「从 A 到 B 中间发生了什么」——用柱状图硬答，读者要自己在脑子里做减法",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    private fun fmt(v: Double): String =
        if (v == kotlin.math.floor(v) && abs(v) < 1e9) v.toLong().toString() else "%.1f".format(v)

    /* ══════════ zx_pyramid 金字塔 ══════════ */
    @Composable
    fun pyramid(o: JSONObject, ctx: RenderContext) {
        val levels = o.optJSONArray("levels") ?: return
        val n = minOf(levels.length(), 7)
        val list = (0 until n).map { i ->
            val l = levels.optJSONObject(i) ?: JSONObject()
            Triple(l.optString("n", ""), l.optDouble("v", 0.0), l.optString("d"))
        }
        val maxV = list.maxOf { it.second }.coerceAtLeast(0.001)
        var sel by remember { mutableIntStateOf(-1) }

        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            list.forEachIndexed { i, (nm, v, d) ->
                val w = (v / maxV).coerceIn(0.08, 1.0)
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clickable {
                        sel = i
                        ctx.callTool("zx_select", mapOf(
                            "choice" to nm, "v" to v.toString(), "source" to "zx_pyramid"))
                    }, verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        Box(Modifier.fillMaxWidth(w.toFloat()).height(24.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(Brush.horizontalGradient(
                                listOf(PAL[i % PAL.size].copy(.9f), PAL[i % PAL.size].copy(.5f)))))
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.width(78.dp), horizontalAlignment = Alignment.End) {
                        Text(nm, fontSize = 10.5.sp,
                             color = if (sel == i) ZxT.Text else ZxT.Text2, maxLines = 1,
                             overflow = TextOverflow.Ellipsis)
                        Text(fmt(v), fontSize = 10.sp, color = ZxT.Text3, fontFamily = ZxT.Mono)
                    }
                }
                if (d.isNotBlank() && sel == i)
                    Text(d, fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                         modifier = Modifier.padding(start = 4.dp, bottom = 3.dp))
            }
            Text("层级必须是递减的——金字塔的形状本身就是信息，乱序就变成了普通条形图",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_bubble 气泡图 ══════════ */
    @Composable
    fun bubble(o: JSONObject, ctx: RenderContext) {
        val points = o.optJSONArray("points") ?: return
        val n = minOf(points.length(), 14)
        val list = (0 until n).mapNotNull { i ->
            val p = points.optJSONObject(i) ?: return@mapNotNull null
            val x = p.optDouble("x", Double.NaN); val y = p.optDouble("y", Double.NaN)
            if (x.isNaN() || y.isNaN()) null
            else Triple(x to y, p.optDouble("r", 8.0), p.optString("n", ""))
        }
        if (list.isEmpty()) return
        val xs = list.map { it.first.first }; val ys = list.map { it.first.second }
        val x0 = xs.minOrNull()!!; val x1 = xs.maxOrNull()!!
        val y0 = ys.minOrNull()!!; val y1 = ys.maxOrNull()!!
        val dx = (x1 - x0).coerceAtLeast(0.001); val dy = (y1 - y0).coerceAtLeast(0.001)
        val rMax = list.maxOf { it.second }.coerceAtLeast(0.001)
        var sel by remember { mutableIntStateOf(-1) }

        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(126.dp)
                .clip(RoundedCornerShape(8.dp)).background(ZxT.Surface2)) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()
                    .clickable { sel = -1 }) {
                    // 网格先画，气泡后画，否则网格会盖住气泡
                    for (k in 1..3) {
                        drawLine(ZxT.Line, Offset(0f, size.height * k / 4),
                                 Offset(size.width, size.height * k / 4), 1f)
                        drawLine(ZxT.Line, Offset(size.width * k / 4, 0f),
                                 Offset(size.width * k / 4, size.height), 1f)
                    }
                    list.forEachIndexed { i, e ->
                        val x = e.first.first; val y = e.first.second; val r = e.second
                        val cx = ((((x - x0) / dx) * 0.82f + 0.09f) * size.width).toFloat()
                        val cy = (((1f - ((y - y0) / dy).toFloat()) * 0.82f + 0.09f) * size.height).toFloat()
                        val rr = ((r / rMax * 0.16f + 0.035f) * size.minDimension).toFloat()
                        drawCircle(PAL[i % PAL.size].copy(if (sel == i) .85f else .45f), rr,
                                   Offset(cx, cy))
                        if (sel == i) drawCircle(ZxT.Text, rr, Offset(cx, cy),
                                                 style = Stroke(width = 1.6f))
                    }
                }
            }
            // 图例在下面：气泡重叠时没法在图上标名字
            Row(Modifier.fillMaxWidth().padding(top = 6.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                list.forEachIndexed { i, (_, r, nm) ->
                    if (nm.isBlank()) return@forEachIndexed
                    Row(Modifier.clip(RoundedCornerShape(12.dp))
                        .background(if (sel == i) PAL[i % PAL.size].copy(.22f) else ZxT.Surface2)
                        .clickable {
                            sel = i
                            ctx.callTool("zx_select", mapOf(
                                "choice" to nm, "x" to list[i].first.first.toString(),
                                "y" to list[i].first.second.toString(),
                                "r" to r.toString(), "source" to "zx_bubble"))
                        }.padding(7.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(PAL[i % PAL.size]))
                        Spacer(Modifier.width(4.dp))
                        Text(nm, fontSize = 9.5.sp, color = ZxT.Text2)
                    }
                }
            }
            Text("两个坐标 + 一个尺寸 = 三个变量；重叠时靠下方图例点选，不在图上标名字",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_venn 韦恩图 ══════════ */
    @Composable
    fun venn(o: JSONObject, ctx: RenderContext) {
        val groups = o.optJSONArray("groups") ?: return
        val n = minOf(groups.length(), 3)
        if (n < 2) return
        val list = (0 until n).map { i ->
            val g = groups.optJSONObject(i) ?: JSONObject()
            Triple(g.optString("n", ""), g.optDouble("v", 0.0), g.optString("hex"))
        }
        var sel by remember { mutableIntStateOf(-1) }

        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().height(118.dp)) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val r = size.minDimension * 0.28f
                    val cy = size.height / 2
                    val cs = listOf(
                        Offset(size.width / 2 - r * 0.55f, cy - r * 0.35f),
                        Offset(size.width / 2 + r * 0.55f, cy - r * 0.35f),
                        Offset(size.width / 2, cy + r * 0.6f))
                    list.forEachIndexed { i, (_, _, hex) ->
                        val col = runCatching {
                            Color(android.graphics.Color.parseColor(hex.ifBlank { "#5046E4" }))
                        }.getOrDefault(PAL[i % PAL.size])
                        drawCircle(col.copy(if (sel == i) .5f else .3f), r, cs[i])
                        drawCircle(col.copy(if (sel == i) 1f else .7f), r, cs[i],
                                   style = Stroke(width = if (sel == i) 2.4f else 1.4f))
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                list.forEachIndexed { i, (nm, v, hex) ->
                    val col = runCatching {
                        Color(android.graphics.Color.parseColor(hex.ifBlank { "#5046E4" }))
                    }.getOrDefault(PAL[i % PAL.size])
                    Row(Modifier.clip(RoundedCornerShape(14.dp))
                        .background(if (sel == i) col.copy(.2f) else ZxT.Surface2)
                        .border(1.dp, if (sel == i) col else ZxT.Line, RoundedCornerShape(14.dp))
                        .clickable {
                            sel = i
                            ctx.callTool("zx_select", mapOf(
                                "choice" to nm, "v" to v.toString(), "source" to "zx_venn"))
                        }.padding(8.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(col))
                        Spacer(Modifier.width(5.dp))
                        Text(nm, fontSize = 10.5.sp, color = ZxT.Text2)
                        Spacer(Modifier.width(5.dp))
                        Text(fmt(v), fontSize = 10.sp, color = ZxT.Text3, fontFamily = ZxT.Mono)
                    }
                }
            }
            Text("回答「两组的交集是什么」——最多 3 组，4 组以上画不出可读的图",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_candle K线图 ══════════ */
    @Composable
    fun candle(o: JSONObject, ctx: RenderContext) {
        val ks = o.optJSONArray("bars") ?: return
        val n = minOf(ks.length(), 24)
        val list = (0 until n).mapNotNull { i ->
            val b = ks.optJSONObject(i) ?: return@mapNotNull null
            val hi = b.optDouble("h", Double.NaN); val lo = b.optDouble("l", Double.NaN)
            val op = b.optDouble("o", Double.NaN); val cl = b.optDouble("c", Double.NaN)
            if (listOf(hi, lo, op, cl).any { it.isNaN() }) null
            else listOf(hi, lo, op, cl)
        }
        if (list.isEmpty()) return
        val hi = list.maxOf { it[0] }; val lo = list.minOf { it[1] }
        val span = (hi - lo).coerceAtLeast(0.001)
        var sel by remember { mutableIntStateOf(-1) }

        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(112.dp)
                .clip(RoundedCornerShape(8.dp)).background(ZxT.Surface2)) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val w = size.width / n
                    val bw = (w * 0.55f).coerceAtLeast(1.5f)
                    fun y(v: Double) = ((hi - v) / span * (size.height - 16f) + 8f).toFloat()
                    list.forEachIndexed { i, b ->
                        val up = b[3] >= b[2]
                        val col = if (up) ZxT.Ok else ZxT.Err
                        val cx = i * w + w / 2
                        drawLine(col, Offset(cx, y(b[0])), Offset(cx, y(b[1])), 1.2f)
                        val top = y(max(b[2], b[3])); val bot = y(min(b[2], b[3]))
                        drawRect(col.copy(if (sel == i) 1f else .75f),
                                 Offset(cx - bw / 2, top),
                                 Size(bw, (bot - top).coerceAtLeast(1.5f)))
                    }
                    if (sel >= 0) {
                        val cx = sel * w + w / 2
                        drawLine(ZxT.Text3.copy(.55f), Offset(cx, 0f),
                                 Offset(cx, size.height), 1f)
                    }
                }
            }
            if (sel >= 0) {
                val b = list[sel]
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    listOf("高" to b[0], "低" to b[1], "开" to b[2], "收" to b[3])
                        .forEach { (k, v) ->
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(k, fontSize = 9.sp, color = ZxT.Text3)
                                Text(fmt(v), fontSize = 10.5.sp,
                                     color = if (b[3] >= b[2]) ZxT.Ok else ZxT.Err,
                                     fontFamily = ZxT.Mono)
                            }
                        }
                }
            }
            // 横向点选条：K线太密，直接点蜡烛点不中
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                for (i in 0 until n) {
                    Box(Modifier.weight(1f).height(20.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (sel == i) ZxT.Primary.copy(.3f) else Color.Transparent)
                        .clickable {
                            sel = i
                            ctx.callTool("zx_select", mapOf(
                                "index" to i.toString(), "o" to list[i][2].toString(),
                                "c" to list[i][3].toString(), "source" to "zx_candle"))
                        })
                }
            }
        }
    }

    /* ══════════ zx_dag 依赖有向无环图 ══════════ */
    @Composable
    fun dag(o: JSONObject, ctx: RenderContext) {
        val nodes = o.optJSONArray("nodes") ?: return
        val edges = o.optJSONArray("edges")
        val n = minOf(nodes.length(), 10)
        val names = (0 until n).map { nodes.optString(it, "") }
        // 分层：没有入度的在前——不分层的话节点会画成一团
        val indeg = IntArray(n)
        val es = mutableListOf<Pair<Int, Int>>()
        if (edges != null) for (i in 0 until edges.length()) {
            val e = edges.optJSONObject(i) ?: continue
            val f = e.optInt("f", -1); val t = e.optInt("t", -1)
            if (f in 0 until n && t in 0 until n && f != t) {
                es.add(f to t); indeg[t]++
            }
        }
        val layer = IntArray(n) { -1 }
        var cur = (0 until n).filter { indeg[it] == 0 }
        var lv = 0
        val seen = BooleanArray(n)
        while (cur.isNotEmpty() && lv < 6) {
            cur.forEach { if (layer[it] < 0) { layer[it] = lv; seen[it] = true } }
            val nxt = mutableListOf<Int>()
            cur.forEach { f -> es.filter { it.first == f }.forEach { (_, t) ->
                indeg[t]--; if (indeg[t] == 0) nxt.add(t) } }
            cur = nxt.distinct(); lv++
        }
        (0 until n).forEach { if (layer[it] < 0) layer[it] = lv }
        var sel by remember { mutableIntStateOf(-1) }

        Column(Modifier.fillMaxWidth()) {
            for (l in 0..lv) {
                val idx = (0 until n).filter { layer[it] == l }
                if (idx.isEmpty()) continue
                if (l > 0) Text("↓", fontSize = 10.sp, color = ZxT.Text3,
                                modifier = Modifier.padding(start = 12.dp, top = 1.dp, bottom = 1.dp))
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    idx.forEach { i ->
                        Box(Modifier.clip(RoundedCornerShape(7.dp))
                            .background(if (sel == i) PAL[i % PAL.size].copy(.35f) else ZxT.Surface2)
                            .border(1.dp, if (sel == i) PAL[i % PAL.size] else ZxT.Line,
                                    RoundedCornerShape(7.dp))
                            .clickable {
                                sel = i
                                ctx.callTool("zx_select", mapOf(
                                    "choice" to names[i], "layer" to l.toString(),
                                    "source" to "zx_dag"))
                            }.padding(9.dp, 6.dp)) {
                            Text(names[i], fontSize = 10.5.sp, color = ZxT.Text2,
                                 maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Text("表达「关系」而不是数值——之前只有树形和流程，覆盖不到多对多依赖",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_swimlane 泳道图 ══════════ */
    @Composable
    fun swimlane(o: JSONObject, ctx: RenderContext) {
        val lanes = o.optJSONArray("lanes") ?: return
        val n = minOf(lanes.length(), 5)
        var sel by remember { mutableStateOf<Pair<Int, Int>?>(null) }

        Column(Modifier.fillMaxWidth()) {
            for (li in 0 until n) {
                val l = lanes.optJSONObject(li) ?: continue
                val cells = l.optJSONArray("cells")
                val m = if (cells == null) 0 else minOf(cells.length(), 6)
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(l.optString("n", ""), fontSize = 10.sp, color = ZxT.Text3,
                         modifier = Modifier.width(52.dp), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (ci in 0 until m) {
                            val c = cells?.optJSONObject(ci)
                            val txt = c?.optString("t", "").orEmpty()
                            val lv = c?.optString("lv", "todo") ?: "todo"
                            val col = when (lv) {
                                "done" -> ZxT.Ok; "doing" -> ZxT.Warn
                                "block" -> ZxT.Err; else -> ZxT.Surface3
                            }
                            Box(Modifier.weight(1f).height(34.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(col.copy(if (sel == Pair(li, ci)) .4f else .16f))
                                .border(1.dp, col.copy(if (sel == Pair(li, ci)) 1f else .45f),
                                        RoundedCornerShape(6.dp))
                                .clickable {
                                    sel = Pair(li, ci)
                                    ctx.callTool("zx_select", mapOf(
                                        "lane" to l.optString("n"), "t" to txt,
                                        "lv" to lv, "source" to "zx_swimlane"))
                                }.padding(5.dp, 4.dp)) {
                                if (txt.isNotBlank())
                                    Text(txt, fontSize = 9.5.sp, color = ZxT.Text2,
                                         maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                if (li < n - 1) Box(Modifier.fillMaxWidth().height(1.dp).background(ZxT.Line))
            }
            Text("跨角色分带——同一时间点不同角色在做什么，是树形和流程都表达不了的",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_mindmap 思维导图 ══════════ */
    @Composable
    fun mindmap(o: JSONObject, ctx: RenderContext) {
        val root = o.optString("root", "中心")
        val arms = o.optJSONArray("arms") ?: return
        val n = minOf(arms.length(), 6)
        val collapsed = remember { mutableStateSetOf<Int>() }
        var sel by remember { mutableStateOf<Pair<Int, Int>?>(null) }

        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(9.dp))
                .background(ZxT.Primary.copy(.18f))
                .border(1.2.dp, ZxT.Primary, RoundedCornerShape(9.dp))
                .padding(11.dp, 9.dp), contentAlignment = Alignment.Center) {
                Text(root, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = ZxT.Text)
            }
            Spacer(Modifier.height(4.dp))
            for (i in 0 until n) {
                val a = arms.optJSONObject(i) ?: continue
                val kids = a.optJSONArray("kids")
                val open = i !in collapsed
                Row(Modifier.fillMaxWidth().padding(start = ((i % 2) * 10).dp)) {
                    Box(Modifier.width(12.dp)) {
                        Box(Modifier.align(Alignment.TopCenter).width(1.4.dp).height(16.dp)
                            .background(PAL[i % PAL.size].copy(.55f)))
                    }
                    Column(Modifier.weight(1f)) {
                        Row(Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(7.dp))
                            .background(PAL[i % PAL.size].copy(.14f))
                            .clickable {
                                if (open) collapsed.add(i) else collapsed.remove(i)
                            }.padding(9.dp, 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(7.dp).clip(CircleShape)
                                .background(PAL[i % PAL.size]))
                            Spacer(Modifier.width(7.dp))
                            Text(a.optString("n", ""), fontSize = 11.5.sp, color = ZxT.Text,
                                 modifier = Modifier.weight(1f), maxLines = 1,
                                 overflow = TextOverflow.Ellipsis)
                            if (kids != null && kids.length() > 0)
                                Text(if (open) "−" else "+${kids.length()}", fontSize = 11.sp,
                                     color = ZxT.Text3)
                        }
                        if (open && kids != null) {
                            for (k in 0 until minOf(kids.length(), 5)) {
                                val kid = kids.optJSONObject(k)
                                val nm = kid?.optString("n") ?: kids.optString(k)
                                Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 2.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (sel == Pair(i, k)) ZxT.Surface2 else Color.Transparent)
                                    .clickable {
                                        sel = Pair(i, k)
                                        ctx.callTool("zx_select", mapOf(
                                            "arm" to a.optString("n"), "choice" to nm,
                                            "source" to "zx_mindmap"))
                                    }.padding(8.dp, 5.dp)) {
                                    Text("·", fontSize = 11.sp, color = PAL[i % PAL.size])
                                    Spacer(Modifier.width(5.dp))
                                    Text(nm, fontSize = 10.5.sp, color = ZxT.Text2,
                                         maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
            Text("点主枝折叠/展开——气泡里全展开会很长，让用户自己控制深度",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_gallery 图片画廊 ══════════ */
    @Composable
    fun gallery(o: JSONObject, ctx: RenderContext) {
        val items = o.optJSONArray("items") ?: return
        val n = minOf(items.length(), 9)
        var sel by remember { mutableIntStateOf(-1) }

        Column(Modifier.fillMaxWidth()) {
            // 3 列固定：气泡里列数随内容变会让缩略图忽大忽小
            for (r in 0 until (n + 2) / 3) {
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    for (c in 0 until 3) {
                        val i = r * 3 + c
                        if (i >= n) { Box(Modifier.weight(1f)); continue }
                        val it = items.optJSONObject(i) ?: continue
                        val hex = it.optString("hex")
                        val col = runCatching {
                            Color(android.graphics.Color.parseColor(hex.ifBlank { "#2B303A" }))
                        }.getOrDefault(ZxT.Surface3)
                        Box(Modifier.weight(1f).aspectRatio(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(col)
                            .border(if (sel == i) 2.dp else 0.dp, ZxT.Text,
                                    RoundedCornerShape(8.dp))
                            .clickable {
                                sel = i
                                ctx.callTool("zx_select", mapOf(
                                    "choice" to it.optString("k", "$i"),
                                    "index" to i.toString(), "source" to "zx_gallery"))
                            }, contentAlignment = Alignment.BottomStart) {
                            if (it.optString("n").isNotBlank())
                                Text(it.optString("n"), fontSize = 9.sp, color = Color.White,
                                     modifier = Modifier.padding(5.dp)
                                         .background(Color.Black.copy(.35f),
                                                     RoundedCornerShape(4.dp))
                                         .padding(4.dp, 2.dp),
                                     maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Text("3 列固定——列数随内容变会让缩略图忽大忽小；点选回传 index",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_wizard 分步表单 ══════════ */
    @Composable
    fun wizard(o: JSONObject, ctx: RenderContext) {
        val steps = o.optJSONArray("steps") ?: return
        val n = minOf(steps.length(), 5)
        var cur by remember { mutableIntStateOf(0) }
        val picks = remember { mutableStateMapOf<Int, Int>() }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                for (i in 0 until n) {
                    val done = i < cur; val act = i == cur
                    Box(Modifier.size(if (act) 9.dp else 7.dp)
                        .clip(CircleShape)
                        .background(if (act) ZxT.Primary else if (done) ZxT.Ok else ZxT.Surface3))
                    if (i < n - 1) Box(Modifier.weight(1f).height(1.6.dp)
                        .background(if (done) ZxT.Ok else ZxT.Surface3))
                }
                Spacer(Modifier.width(8.dp))
                Text("${cur + 1}/$n", fontSize = 10.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(9.dp))
            val s = steps.optJSONObject(cur) ?: return
            Text(s.optString("q", ""), fontSize = 12.sp, color = ZxT.Text,
                 fontWeight = FontWeight.Medium, lineHeight = 17.sp)
            Spacer(Modifier.height(7.dp))
            val opts = s.optJSONArray("options")
            if (opts != null) for (k in 0 until minOf(opts.length(), 5)) {
                val op = opts.optJSONObject(k)
                val t = op?.optString("t") ?: opts.optString(k)
                val on = picks[cur] == k
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) ZxT.Primary.copy(.14f) else ZxT.Surface2)
                    .border(1.dp, if (on) ZxT.Primary else ZxT.Line, RoundedCornerShape(8.dp))
                    .clickable { picks[cur] = k }
                    .padding(10.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(t, fontSize = 11.5.sp, color = ZxT.Text2, modifier = Modifier.weight(1f),
                         maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (on) Text("✓", fontSize = 11.sp, color = ZxT.PriLite)
                }
            }
            Spacer(Modifier.height(9.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("上一步", fontSize = 11.5.sp,
                     color = if (cur > 0) ZxT.Text2 else ZxT.Text3,
                     modifier = Modifier.clickable(enabled = cur > 0) { cur-- }.padding(end = 16.dp))
                Spacer(Modifier.weight(1f))
                // 没选就不能下一步：静默跳过会让最后收集的 payload 缺项
                val can = picks[cur] != null || (opts == null || opts.length() == 0)
                Text(if (cur == n - 1) "提交" else "下一步", fontSize = 12.sp,
                     color = if (can) ZxT.PriLite else ZxT.Text3,
                     modifier = Modifier.clickable(enabled = can) {
                         if (cur < n - 1) cur++
                         else {
                             val payload = JSONObject()
                             picks.forEach { (step, k) ->
                                 val st = steps.optJSONObject(step)
                                 val oo = st?.optJSONArray("options")?.optJSONObject(k)
                                 payload.put(st?.optString("k", "s$step").orEmpty(),
                                             oo?.optString("k") ?: oo?.optString("t") ?: "$k")
                             }
                             ctx.callTool("zx_form_submit", mapOf("payload" to payload.toString()))
                         }
                     })
            }
            Text("与 zx_tour 不同：tour 是引导（只读），wizard 是收集（每步都要出值）",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 7.dp))
        }
    }

    /* ══════════ zx_spreadsheet 可编辑网格 ══════════ */
    @Composable
    fun spreadsheet(o: JSONObject, ctx: RenderContext) {
        val cols = o.optJSONArray("cols") ?: return
        val rows = o.optJSONArray("rows") ?: return
        val nc = minOf(cols.length(), 5); val nr = minOf(rows.length(), 7)
        // 与 zx_table 的区别：这里的单元格能改，且改动会回传
        val grid = remember {
            mutableStateMapOf<String, String>().apply {
                for (r in 0 until nr) {
                    val arr = rows.optJSONObject(r)?.optJSONArray("v")
                    for (c in 0 until nc) put("$r,$c", arr?.optString(c, "").orEmpty())
                }
            }
        }
        val edited = remember { mutableStateSetOf<String>() }
        var cell by remember { mutableStateOf<Pair<Int, Int>?>(null) }
        var draft by remember { mutableStateOf("") }

        Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Row(Modifier.padding(bottom = 4.dp)) {
                Box(Modifier.width(22.dp))
                for (c in 0 until nc)
                    Text(cols.optString(c, ""), fontSize = 9.5.sp, color = ZxT.Text3,
                         modifier = Modifier.width(62.dp), textAlign = TextAlign.End,
                         maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            for (r in 0 until nr) {
                val ro = rows.optJSONObject(r)
                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(ro?.optString("n", "${r + 1}").orEmpty(), fontSize = 9.5.sp,
                         color = ZxT.Text3, modifier = Modifier.width(22.dp))
                    for (c in 0 until nc) {
                        val key = "$r,$c"
                        val v = grid[key].orEmpty()
                        val isEdit = cell == Pair(r, c)
                        val dirty = key in edited
                        Box(Modifier.width(62.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isEdit) ZxT.Primary.copy(.3f)
                                        else if (dirty) ZxT.Warn.copy(.12f) else Color.Transparent)
                            .border(if (dirty) 1.dp else 0.dp, ZxT.Warn.copy(.5f),
                                    RoundedCornerShape(4.dp))
                            .clickable {
                                cell = Pair(r, c); draft = v
                            }.padding(5.dp, 5.dp), contentAlignment = Alignment.CenterEnd) {
                            if (isEdit)
                                androidx.compose.material3.TextField(
                                    value = draft, onValueChange = { draft = it },
                                    modifier = Modifier.height(30.dp),
                                    textStyle = androidx.compose.ui.text.TextStyle(
                                        fontSize = 10.5.sp, color = ZxT.Text),
                                    singleLine = true,
                                    colors = androidx.compose.material3.TextFieldDefaults.colors(
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent,
                                        cursorColor = ZxT.Primary))
                            else Text(v, fontSize = 10.5.sp,
                                      color = if (dirty) ZxT.Warn else ZxT.Text2,
                                      maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 7.dp)) {
                Text("选中单元格后可改，改动标黄", fontSize = 10.sp, color = ZxT.Text3,
                     modifier = Modifier.weight(1f))
                Text("提交变更", fontSize = 11.5.sp,
                     color = if (edited.isEmpty()) ZxT.Text3 else ZxT.PriLite,
                     modifier = Modifier.clickable(enabled = edited.isNotEmpty()) {
                         val payload = JSONObject()
                         edited.forEach { key ->
                             val (r, c) = key.split(",").let { it[0].toInt() to it[1].toInt() }
                             val rn = rows.optJSONObject(r)?.optString("k", "$r").orEmpty()
                             payload.put("${rn}.${cols.optString(c)}", grid[key].orEmpty())
                         }
                         edited.clear()
                         ctx.callTool("zx_form_submit", mapOf("payload" to payload.toString()))
                     })
            }
        }
    }

    /* ══════════ zx_conversation 模拟对话 ══════════ */
    @Composable
    fun conversation(o: JSONObject, ctx: RenderContext) {
        val turns = o.optJSONArray("turns") ?: return
        val n = minOf(turns.length(), 8)
        var sel by remember { mutableIntStateOf(-1) }

        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (i in 0 until n) {
                val t = turns.optJSONObject(i) ?: continue
                val me = t.optString("who", "them") == "me"
                Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = if (me) Arrangement.End else Arrangement.Start) {
                    if (!me && t.optString("n").isNotBlank())
                        Box(Modifier.size(20.dp).clip(CircleShape)
                            .background(PAL[i % PAL.size]), contentAlignment = Alignment.Center) {
                            Text(t.optString("n").take(1), fontSize = 9.sp, color = Color.White)
                        }
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.widthIn(max = 232.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(if (me) ZxT.Primary.copy(.22f)
                                    else if (sel == i) ZxT.Surface2 else ZxT.Surface3)
                        .clickable {
                            sel = i
                            ctx.callTool("zx_select", mapOf(
                                "index" to i.toString(), "who" to t.optString("who", "them"),
                                "source" to "zx_conversation"))
                        }.padding(10.dp, 7.dp)) {
                        if (!me && t.optString("n").isNotBlank())
                            Text(t.optString("n"), fontSize = 9.5.sp, color = PAL[i % PAL.size])
                        Text(t.optString("t", ""), fontSize = 11.5.sp, color = ZxT.Text2,
                             lineHeight = 16.sp)
                        if (t.optString("d").isNotBlank())
                            Text(t.optString("d"), fontSize = 9.5.sp, color = ZxT.Text3)
                    }
                }
            }
            Text("用来展示「AI 与某人会怎么对话」这类示例，而不是真开一轮会话",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 5.dp))
        }
    }
}
