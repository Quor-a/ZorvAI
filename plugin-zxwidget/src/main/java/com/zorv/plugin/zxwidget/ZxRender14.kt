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
 * 第十四批组件（12 个）—— 分布 / 富媒体 / 产出。
 *
 * 前三批把"能画什么"铺得很开了，但还有三处明显是空的：
 *
 *   一、**分布类图表**。已有 bar/line/pie/radar/treemap，
 *       但它们回答的都是"谁多谁少"。
 *       回答不了"数据长什么样"：
 *       集中在哪、离散到什么程度、有没有异常点。
 *       直方图 / 箱线图 / 控制图回答的是这个。
 *
 *   二、**富媒体**。已有 gallery / audio / hotspot，
 *       但没有视频、文档、3D 模型——
 *       而这三类在真实会话里出现频率极高。
 *
 *   三、**产出**。前十三批全都是"看"和"填"，
 *       没有"拿走"：导出、分享、打印。
 *       对话的终点往往是一个文件，不是一个界面。
 *
 * 贯穿这批的一条：**分布类必须标出"异常在哪"**。
 * 直方图只有柱子没有均值线、箱线图不给异常点、
 * 控制图不画超出控制限的红点——
 * 那都只是"另一种柱状图"，用户还得自己找问题。
 */
object ZxRender14 {

    private val PAL = listOf(
        Color(0xFF5046E4), Color(0xFF3FBF7F), Color(0xFFE8A33D),
        Color(0xFFE5544B), Color(0xFF4A9BE8), Color(0xFF8E86FF)
    )

    /* ══════════ zx_histogram 直方图 ══════════ */
    @Composable
    fun histogram(o: JSONObject, ctx: RenderContext) {
        val bins = o.optJSONArray("bins") ?: return
        val n = minOf(bins.length(), 14)
        val vals = (0 until n).map { bins.optJSONObject(it)?.optDouble("v", 0.0) ?: 0.0 }
        val mean = o.optDouble("mean", Double.NaN)
        val mx = vals.maxOrNull()?.coerceAtLeast(0.001) ?: 1.0
        var sel by remember { mutableIntStateOf(-1) }

        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(88.dp)) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val bw = size.width / n
                    vals.forEachIndexed { i, v ->
                        val h = (v / mx).toFloat() * size.height
                        drawRect(
                            if (sel == i) PAL[0] else PAL[0].copy(.55f),
                            Offset(i * bw + 1f, size.height - h),
                            Size(bw * .82f, h))
                    }
                    // 均值线：没有它就只是「另一种柱状图」
                    if (!mean.isNaN() && mx > 0) {
                        // 均值是数据值，不是频数——用它在 bins 里的位置定位
                        val names = (0 until n).map {
                            bins.optJSONObject(it)?.optDouble("to", (it + 1).toDouble()) ?: 0.0 }
                        val idx = names.indexOfFirst { it >= mean }
                        val x = if (idx < 0) size.width else (idx + .5f) * bw
                        drawLine(ZxT.Err, Offset(x, 0f), Offset(x, size.height), 1.5f,
                                 pathEffect = androidx.compose.ui.graphics.PathEffect
                                     .dashPathEffect(floatArrayOf(4f, 3f)))
                    }
                }
                Row(Modifier.fillMaxWidth().height(88.dp)) {
                    vals.indices.forEach { i ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = i
                            ctx.callTool("zx_select", mapOf(
                                "choice" to bins.optJSONObject(i)?.optString("n", "$i"),
                                "v" to vals[i].toString(), "source" to "zx_histogram"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                (0 until n).forEach { i ->
                    Text(bins.optJSONObject(i)?.optString("n", "") ?: "",
                         fontSize = 7.5.sp, color = ZxT.Text3,
                         modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                         maxLines = 1, overflow = TextOverflow.Clip)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 5.dp)) {
                if (sel >= 0) {
                    Text("${bins.optJSONObject(sel)?.optString("n")}：${vals[sel].toLong()}",
                         fontSize = 10.5.sp, color = ZxT.PriLite)
                } else Text("点柱子看该区间频数", fontSize = 10.sp, color = ZxT.Text3)
                if (!mean.isNaN()) {
                    Spacer(Modifier.weight(1f))
                    Text("均值 %.1f".format(mean), fontSize = 10.sp, color = ZxT.Err)
                }
            }
        }
    }

    /* ══════════ zx_boxplot 箱线图 ══════════ */
    @Composable
    fun boxplot(o: JSONObject, ctx: RenderContext) {
        val items = o.optJSONArray("items") ?: return
        val n = minOf(items.length(), 6)
        var sel by remember { mutableIntStateOf(-1) }
        val lo = o.optDouble("min", Double.NaN)
        val hi = o.optDouble("max", Double.NaN)

        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(104.dp)) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    // 量程取全部数据的 min/max
                    var gLo = Double.MAX_VALUE; var gHi = -Double.MAX_VALUE
                    for (i in 0 until n) {
                        val it = items.optJSONObject(i) ?: continue
                        gLo = minOf(gLo, it.optDouble("lo", 0.0))
                        gHi = maxOf(gHi, it.optDouble("hi", 1.0))
                    }
                    if (gHi <= gLo) { gHi = gLo + 1 }
                    fun y(v: Double) = size.height - ((v - gLo) / (gHi - gLo)).toFloat() * size.height
                    val cw = size.width / n
                    for (i in 0 until n) {
                        val it = items.optJSONObject(i) ?: continue
                        val q1 = it.optDouble("q1"); val q2 = it.optDouble("q2")
                        val q3 = it.optDouble("q3")
                        val l = it.optDouble("lo"); val h = it.optDouble("hi")
                        val cx = (i + .5f) * cw
                        val col = if (sel == i) PAL[0] else PAL[0].copy(.6f)
                        // 须线
                        drawLine(col, Offset(cx, y(h)), Offset(cx, y(q3)), 1.2f)
                        drawLine(col, Offset(cx, y(l)), Offset(cx, y(q1)), 1.2f)
                        drawLine(col, Offset(cx - cw * .12f, y(h)), Offset(cx + cw * .12f, y(h)), 1.2f)
                        drawLine(col, Offset(cx - cw * .12f, y(l)), Offset(cx + cw * .12f, y(l)), 1.2f)
                        // 箱体
                        drawRect(col.copy(.35f),
                                 Offset(cx - cw * .22f, y(q3)),
                                 Size(cw * .44f, y(q1) - y(q3)))
                        drawRect(col.copy(.35f),
                                 Offset(cx - cw * .22f, y(q3)),
                                 Size(cw * .44f, y(q1) - y(q3)),
                                 style = Stroke(1.2f))
                        // 中位数：粗线，箱线图的核心
                        drawLine(ZxT.Text, Offset(cx - cw * .22f, y(q2)),
                                 Offset(cx + cw * .22f, y(q2)), 2.5f)
                        // 异常点：不给异常点就只是「另一种柱状图」
                        val out = it.optJSONArray("out")
                        for (k in 0 until (out?.length() ?: 0)) {
                            drawCircle(ZxT.Err, 2.6f, Offset(cx, y(out!!.optDouble(k, 0.0))))
                        }
                    }
                    // 参考量程线（可选）
                    if (!lo.isNaN()) drawLine(ZxT.Warn.copy(.5f), Offset(0f, y(lo)),
                                               Offset(size.width, y(lo)), 1f)
                    if (!hi.isNaN()) drawLine(ZxT.Warn.copy(.5f), Offset(0f, y(hi)),
                                               Offset(size.width, y(hi)), 1f)
                }
                Row(Modifier.fillMaxWidth().height(104.dp)) {
                    (0 until n).forEach { i ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = i
                            ctx.callTool("zx_select", mapOf(
                                "choice" to items.optJSONObject(i)?.optString("n", "$i"),
                                "source" to "zx_boxplot"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                (0 until n).forEach { i ->
                    Text(items.optJSONObject(i)?.optString("n", "") ?: "",
                         fontSize = 8.5.sp,
                         color = if (sel == i) ZxT.PriLite else ZxT.Text3,
                         modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                         maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (sel >= 0) {
                val it = items.optJSONObject(sel)
                Text("中位 %.1f · Q1 %.1f · Q3 %.1f · 范围 %.1f–%.1f".format(
                        it?.optDouble("q2", 0.0), it?.optDouble("q1", 0.0),
                        it?.optDouble("q3", 0.0), it?.optDouble("lo", 0.0),
                        it?.optDouble("hi", 0.0)) +
                     (it?.optJSONArray("out")?.let { " · 异常 ${it.length()} 个" } ?: ""),
                     fontSize = 10.sp, color = ZxT.Text2,
                     modifier = Modifier.padding(top = 5.dp))
            }
        }
    }

    /* ══════════ zx_pareto 帕累托图 ══════════ */
    @Composable
    fun pareto(o: JSONObject, ctx: RenderContext) {
        val items = o.optJSONArray("items") ?: return
        val n = minOf(items.length(), 8)
        val vals = (0 until n).map { items.optJSONObject(it)?.optDouble("v", 0.0) ?: 0.0 }
        val total = vals.sum().coerceAtLeast(0.001)
        val cum = vals.runningFold(0.0) { a, b -> a + b }.drop(1).map { it / total }
        val mx = vals.maxOrNull()?.coerceAtLeast(0.001) ?: 1.0
        var sel by remember { mutableIntStateOf(-1) }
        // 80% 线：帕累托的意义全在这条线上
        val cut = cum.indexOfFirst { it >= .8 }

        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(96.dp)) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val bw = size.width / n
                    vals.forEachIndexed { i, v ->
                        val h = (v / mx).toFloat() * (size.height - 12f)
                        drawRect(if (sel == i) PAL[0] else PAL[0].copy(.55f),
                                 Offset(i * bw + 1f, size.height - 12f - h),
                                 Size(bw * .78f, h))
                    }
                    // 累计折线
                    val path = Path()
                    cum.forEachIndexed { i, c ->
                        val x = (i + .5f) * bw
                        val y = size.height - 12f - c.toFloat() * (size.height - 12f)
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, ZxT.Warn, style = Stroke(1.8f))
                    cum.forEachIndexed { i, c ->
                        drawCircle(if (sel == i) ZxT.Text else ZxT.Warn, 2.8f,
                                   Offset((i + .5f) * bw,
                                          size.height - 12f - c.toFloat() * (size.height - 12f)))
                    }
                    // 80% 参考线
                    val y80 = size.height - 12f - .8f * (size.height - 12f)
                    drawLine(ZxT.Err, Offset(0f, y80), Offset(size.width, y80), 1.2f,
                             pathEffect = androidx.compose.ui.graphics.PathEffect
                                 .dashPathEffect(floatArrayOf(4f, 3f)))
                }
                Row(Modifier.fillMaxWidth().height(96.dp)) {
                    (0 until n).forEach { i ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = i
                            ctx.callTool("zx_select", mapOf(
                                "choice" to items.optJSONObject(i)?.optString("n", "$i"),
                                "source" to "zx_pareto"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                (0 until n).forEach { i ->
                    Text(items.optJSONObject(i)?.optString("n", "") ?: "",
                         fontSize = 7.5.sp, color = ZxT.Text3,
                         modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                         maxLines = 1, overflow = TextOverflow.Clip)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 5.dp)) {
                if (sel >= 0)
                    Text("${items.optJSONObject(sel)?.optString("n")}：${vals[sel].toLong()}" +
                         "（累计 ${(cum[sel] * 100).toInt()}%）",
                         fontSize = 10.5.sp, color = ZxT.PriLite)
                else Text("点柱子看累计占比", fontSize = 10.sp, color = ZxT.Text3)
                Spacer(Modifier.weight(1f))
                if (cut >= 0) Text("前 ${cut + 1} 项占 80%", fontSize = 10.sp, color = ZxT.Err)
            }
        }
    }

    /* ══════════ zx_control 控制图（SPC） ══════════ */
    @Composable
    fun control(o: JSONObject, ctx: RenderContext) {
        val pts = o.optJSONArray("pts") ?: return
        val n = minOf(pts.length(), 30)
        val vals = (0 until n).map { pts.optDouble(it, 0.0) }
        val cl = o.optDouble("cl", vals.average())
        val ucl = o.optDouble("ucl", cl + 3.0)
        val lcl = o.optDouble("lcl", cl - 3.0)
        var sel by remember { mutableIntStateOf(-1) }
        // 超界点：控制图的全部价值就在这些红点上
        val oos = vals.mapIndexedNotNull { i, v ->
            if (v > ucl || v < lcl) i else null }

        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(92.dp)) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val gLo = minOf(lcl, vals.minOrNull() ?: cl) - (ucl - lcl) * .1
                    val gHi = maxOf(ucl, vals.maxOrNull() ?: cl) + (ucl - lcl) * .1
                    fun y(v: Double) = size.height -
                        ((v - gLo) / (gHi - gLo).coerceAtLeast(1e-6)).toFloat() * size.height
                    // 控制限带
                    drawRect(ZxT.Ok.copy(.08f), Offset(0f, y(ucl)),
                             Size(size.width, y(lcl) - y(ucl)))
                    drawLine(ZxT.Text3, Offset(0f, y(cl)), Offset(size.width, y(cl)), 1.2f,
                             pathEffect = androidx.compose.ui.graphics.PathEffect
                                 .dashPathEffect(floatArrayOf(5f, 3f)))
                    drawLine(ZxT.Err.copy(.6f), Offset(0f, y(ucl)), Offset(size.width, y(ucl)), 1f)
                    drawLine(ZxT.Err.copy(.6f), Offset(0f, y(lcl)), Offset(size.width, y(lcl)), 1f)
                    val path = Path()
                    val step = size.width / (n - 1).coerceAtLeast(1)
                    vals.forEachIndexed { i, v ->
                        val x = i * step; val yy = y(v)
                        if (i == 0) path.moveTo(x, yy) else path.lineTo(x, yy)
                    }
                    drawPath(path, PAL[0], style = Stroke(1.8f))
                    vals.forEachIndexed { i, v ->
                        val out = v > ucl || v < lcl
                        drawCircle(if (out) ZxT.Err else (if (sel == i) ZxT.Text else PAL[0]),
                                   if (out) 4f else 2.8f, Offset(i * step, y(v)))
                    }
                }
                Row(Modifier.fillMaxWidth().height(92.dp)) {
                    (0 until n).forEach { i ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = i
                            ctx.callTool("zx_select", mapOf(
                                "index" to i.toString(), "v" to vals[i].toString(),
                                "oos" to (vals[i] > ucl || vals[i] < lcl).toString(),
                                "source" to "zx_control"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("CL %.1f".format(cl), fontSize = 9.5.sp, color = ZxT.Text3)
                Spacer(Modifier.width(8.dp))
                Text("UCL %.1f".format(ucl), fontSize = 9.5.sp, color = ZxT.Err)
                Spacer(Modifier.width(8.dp))
                Text("LCL %.1f".format(lcl), fontSize = 9.5.sp, color = ZxT.Err)
                Spacer(Modifier.weight(1f))
                // 超界数量：不给这个，用户还得自己数红点
                Text(if (oos.isEmpty()) "无超界点" else "${oos.size} 个超界点",
                     fontSize = 10.sp, color = if (oos.isEmpty()) ZxT.Ok else ZxT.Err)
            }
        }
    }

    /* ══════════ zx_sunburst 旭日图 ══════════ */
    @Composable
    fun sunburst(o: JSONObject, ctx: RenderContext) {
        val rings = o.optJSONArray("rings") ?: return
        var sel by remember { mutableIntStateOf(-1) }
        val n = minOf(rings.length(), 3)
        val parts = (0 until n).map { r ->
            val arr = rings.optJSONObject(r)?.optJSONArray("items") ?: org.json.JSONArray()
            (0 until minOf(arr.length(), 8)).map { i ->
                Triple(arr.optJSONObject(i)?.optString("n", "") ?: "",
                       arr.optJSONObject(i)?.optDouble("v", 0.0) ?: 0.0, r)
            }
        }
        val grand = parts.sumOf { it.sumOf { it2 -> it2.second } }.coerceAtLeast(0.001)

        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val cx = size.width / 2; val cy = size.height / 2
                    val rIn = size.minDimension * .17f
                    val rOut = size.minDimension * .48f
                    val step = (rOut - rIn) / n
                    parts.forEachIndexed { ri, items2 ->
                        val tot = items2.sumOf { it.second }.coerceAtLeast(0.001)
                        var a0 = -90f
                        items2.forEachIndexed { i, (nm, v, _) ->
                            val sweep = (v / tot).toFloat() * 360f
                            val col = PAL[(ri * 3 + i) % PAL.size]
                            val hot = sel == ri * 100 + i
                            drawArc(col.copy(if (hot) .95f else .55f + ri * .12f),
                                    a0, sweep, true,
                                    topLeft = Offset(cx - (rIn + (ri + 1) * step), cy - (rIn + (ri + 1) * step)),
                                    size = Size((rIn + (ri + 1) * step) * 2, (rIn + (ri + 1) * step) * 2),
                                    style = Stroke(width = step * .82f))
                            a0 += sweep
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (sel >= 0) {
                        val r = sel / 100; val i = sel % 100
                        parts.getOrNull(r)?.getOrNull(i)?.first ?: ""
                    } else o.optString("n", "总计"),
                         fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = ZxT.Text,
                         maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (sel >= 0) {
                        val r = sel / 100; val i = sel % 100
                        val v = parts.getOrNull(r)?.getOrNull(i)?.second ?: 0.0
                        "%.0f（%.0f%%）".format(v, v / grand * 100)
                    } else "%d".format(grand.toLong()),
                         fontSize = 14.sp, fontWeight = FontWeight.Bold, color = ZxT.PriLite,
                         fontFamily = ZxT.Mono)
                }
            }
            // 图例点选：扇区太细点不中
            Row(Modifier.fillMaxWidth().padding(top = 6.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                parts.forEachIndexed { ri, items2 ->
                    items2.forEachIndexed { i, (nm, v, _) ->
                        val key = ri * 100 + i
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clip(RoundedCornerShape(10.dp))
                                .background(if (sel == key) PAL[(ri * 3 + i) % PAL.size].copy(.25f)
                                            else ZxT.Surface2)
                                .clickable {
                                    sel = key
                                    ctx.callTool("zx_select", mapOf(
                                        "choice" to nm, "ring" to ri.toString(),
                                        "source" to "zx_sunburst"))
                                }.padding(7.dp, 3.dp)) {
                            Box(Modifier.size(7.dp).clip(RoundedCornerShape(2.dp))
                                .background(PAL[(ri * 3 + i) % PAL.size]))
                            Spacer(Modifier.width(4.dp))
                            Text(nm, fontSize = 9.5.sp, color = ZxT.Text2, maxLines = 1)
                        }
                    }
                }
            }
        }
    }

    /* ══════════ zx_video 视频播放器 ══════════ */
    @Composable
    fun video(o: JSONObject, ctx: RenderContext) {
        val dur = maxOf(1, o.optInt("dur", 100))
        var pos by remember { mutableFloatStateOf(o.optDouble("at", 0.0).toFloat()) }
        var playing by remember { mutableStateOf(false) }
        var rate by remember { mutableFloatStateOf(o.optDouble("rate", 1.0).toFloat()) }
        val marks = o.optJSONArray("marks")

        fun fmt(s: Int) = "%d:%02d".format(s / 60, s % 60)

        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(104.dp)
                .clip(RoundedCornerShape(9.dp)).background(ZxT.Surface)
                .clickable { playing = !playing }, contentAlignment = Alignment.Center) {
                // 缩略占位：气泡里不放真实解码器
                Text(if (playing) "⏸" else "▶", fontSize = 26.sp, color = ZxT.Text3)
                Text(o.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text3,
                     modifier = Modifier.align(Alignment.TopStart).padding(9.dp))
                if (rate != 1f)
                    Text("${rate}×", fontSize = 9.5.sp, color = ZxT.PriLite,
                         modifier = Modifier.align(Alignment.TopEnd).padding(9.dp))
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(fmt((pos * dur).toInt()), fontSize = 9.5.sp, color = ZxT.Text3,
                     fontFamily = ZxT.Mono)
                Spacer(Modifier.width(7.dp))
                Box(Modifier.weight(1f).height(16.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp))
                        .background(ZxT.Surface3))
                    Box(Modifier.fillMaxWidth(pos).height(3.dp).clip(RoundedCornerShape(2.dp))
                        .background(ZxT.Primary))
                    // 标记点：跳章节点的唯一入口
                    if (marks != null) for (i in 0 until minOf(marks.length(), 6)) {
                        val m = marks.optJSONObject(i) ?: continue
                        val t = (m.optDouble("at", 0.0) / dur).toFloat().coerceIn(0f, 1f)
                        Box(Modifier.fillMaxWidth().height(16.dp), contentAlignment = Alignment.CenterStart) {
                            Box(Modifier.offset(x = (t * 300).toInt().dp).size(3.dp, 9.dp)
                                .background(ZxT.Warn))
                        }
                    }
                }
                Spacer(Modifier.width(7.dp))
                Text(fmt(dur), fontSize = 9.5.sp, color = ZxT.Text3, fontFamily = ZxT.Mono)
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically) {
                listOf("−10s" to -10, "+10s" to 10, "0.5×" to -99).forEach { (t, d) ->
                    Text(t, fontSize = 10.sp, color = ZxT.Text3,
                         modifier = Modifier.clip(RoundedCornerShape(8.dp))
                             .background(ZxT.Surface2)
                             .clickable {
                                 if (d == -99) rate = if (rate == 1f) 0.5f else 1f
                                 else pos = (pos + d.toFloat() / dur).coerceIn(0f, 1f)
                                 ctx.callTool("zx_form_submit", mapOf("payload" to
                                     JSONObject().put("at", pos).put("rate", rate.toDouble())
                                         .put("playing", playing).toString()))
                             }.padding(8.dp, 4.dp))
                }
                Spacer(Modifier.weight(1f))
                Text(if (playing) "播放中" else "已暂停", fontSize = 10.sp, color = ZxT.Text3)
            }
        }
    }

    /* ══════════ zx_docview 文档预览 ══════════ */
    @Composable
    fun docview(o: JSONObject, ctx: RenderContext) {
        val pages = o.optJSONArray("pages")
        val total = maxOf(1, pages?.length() ?: o.optInt("total", 1))
        var cur by remember { mutableIntStateOf(o.optInt("at", 1).coerceIn(1, total)) }
        var zoom by remember { mutableFloatStateOf(o.optDouble("zoom", 1.0).toFloat()) }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "文档"), fontSize = 11.5.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f), maxLines = 1,
                     overflow = TextOverflow.Ellipsis)
                Text("${(zoom * 100).toInt()}%", fontSize = 9.5.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(140.dp)
                .clip(RoundedCornerShape(8.dp)).background(ZxT.Surface)
                .border(1.dp, ZxT.Line, RoundedCornerShape(8.dp))) {
                // 页面缩略：真实实现接 PDF 渲染，这里画骨架
                Column(Modifier.fillMaxSize().padding(12.dp)) {
                    val lines = if (zoom >= 1f) 8 else 5
                    repeat(lines) { i ->
                        Box(Modifier.fillMaxWidth(if (i % 3 == 2) .72f else 1f)
                            .height(if (i == 0) 9.dp else 5.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(ZxT.Surface3))
                        Spacer(Modifier.height(6.dp))
                    }
                }
                Text("第 $cur 页", fontSize = 9.5.sp, color = ZxT.Text3,
                     modifier = Modifier.align(Alignment.BottomEnd).padding(9.dp))
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("‹", fontSize = 14.sp, color = if (cur > 1) ZxT.Text2 else ZxT.Text3,
                     modifier = Modifier.clickable(enabled = cur > 1) {
                         cur--
                         ctx.callTool("zx_select", mapOf("page" to cur.toString(),
                                                         "source" to "zx_docview"))
                     }.padding(horizontal = 8.dp))
                Text("$cur / $total", fontSize = 10.5.sp, color = ZxT.Text2,
                     fontFamily = ZxT.Mono)
                Text("›", fontSize = 14.sp, color = if (cur < total) ZxT.Text2 else ZxT.Text3,
                     modifier = Modifier.clickable(enabled = cur < total) {
                         cur++
                         ctx.callTool("zx_select", mapOf("page" to cur.toString(),
                                                         "source" to "zx_docview"))
                     }.padding(horizontal = 8.dp))
                Spacer(Modifier.weight(1f))
                // 缩放：文档预览不缩放等于没法读
                Text("−", fontSize = 13.sp, color = ZxT.Text3,
                     modifier = Modifier.clickable {
                         zoom = (zoom - .25f).coerceIn(.5f, 2f) })
                Spacer(Modifier.width(8.dp))
                Text("+", fontSize = 13.sp, color = ZxT.Text3,
                     modifier = Modifier.clickable {
                         zoom = (zoom + .25f).coerceIn(.5f, 2f) })
            }
        }
    }

    /* ══════════ zx_model3d 模型查看器 ══════════ */
    @Composable
    fun model3d(o: JSONObject, ctx: RenderContext) {
        var yaw by remember { mutableFloatStateOf(o.optDouble("yaw", 0.0).toFloat()) }
        var pitch by remember { mutableFloatStateOf(o.optDouble("pitch", 0.0).toFloat()) }
        var wire by remember { mutableStateOf(o.optBoolean("wire", false)) }
        val verts = o.optJSONArray("verts")

        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().height(128.dp)
                .clip(RoundedCornerShape(9.dp)).background(ZxT.Surface)
                .clickable { wire = !wire }) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val cx = size.width / 2; val cy = size.height / 2
                    val s = size.minDimension * .3f
                    // 立方体 8 顶点
                    val base = listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f)
                        .flatMap { (x, z) -> listOf(Triple(x, -1f, z), Triple(x, 1f, z)) }
                    val cy2 = kotlin.math.cos(yaw); val sy = kotlin.math.sin(yaw)
                    val cp = kotlin.math.cos(pitch); val sp = kotlin.math.sin(pitch)
                    val proj = base.map { (x, y, z) ->
                        val x1 = x * cy2 - z * sy
                        val z1 = x * sy + z * cy2
                        val y1 = y * cp - z1 * sp
                        Offset(cx + x1 * s, cy + y1 * s)
                    }
                    val edges = listOf(0 to 1, 1 to 3, 3 to 2, 2 to 0,
                                       4 to 5, 5 to 7, 7 to 6, 6 to 4,
                                       0 to 4, 1 to 5, 2 to 6, 3 to 7)
                    edges.forEach { (a, b) ->
                        drawLine(if (wire) ZxT.PriLite else PAL[0].copy(.75f),
                                 proj[a], proj[b], if (wire) 1.2f else 1.8f)
                    }
                    if (!wire) proj.forEach { drawCircle(PAL[0], 3f, it) }
                }
                Text(if (wire) "线框" else "实体", fontSize = 9.5.sp, color = ZxT.Text3,
                     modifier = Modifier.align(Alignment.TopEnd).padding(9.dp))
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf("↺" to -1, "↻" to 1, "↑" to -2, "↓" to 2).forEach { (t, d) ->
                    Text(t, fontSize = 12.sp, color = ZxT.Text3,
                         modifier = Modifier.clip(RoundedCornerShape(8.dp))
                             .background(ZxT.Surface2)
                             .clickable {
                                 if (d == -1 || d == 1) yaw += d * .35f
                                 else pitch += (d / 2) * .3f
                                 ctx.callTool("zx_form_submit", mapOf("payload" to
                                     JSONObject().put("yaw", yaw.toDouble())
                                         .put("pitch", pitch.toDouble()).toString()))
                             }.padding(11.dp, 4.dp))
                }
            }
            Text("偏航 %.0f° · 俯仰 %.0f° · 点画面切线框".format(
                     yaw * 57.3f, pitch * 57.3f),
                 fontSize = 10.sp, color = ZxT.Text3,
                 modifier = Modifier.padding(top = 5.dp))
        }
    }

    /* ══════════ zx_schedule 排班表 ══════════ */
    @Composable
    fun schedule(o: JSONObject, ctx: RenderContext) {
        val days = o.optJSONArray("days") ?: return
        val slots = o.optJSONArray("slots") ?: return
        val nd = minOf(days.length(), 7)
        val ns = minOf(slots.length(), 5)
        val cells = o.optJSONArray("cells")
        var sel by remember { mutableStateOf<Pair<Int, Int>?>(null) }

        Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Row(Modifier.padding(start = 40.dp)) {
                (0 until nd).forEach { d ->
                    Text(days.optString(d), fontSize = 9.sp, color = ZxT.Text3,
                         modifier = Modifier.width(46.dp), textAlign = TextAlign.Center)
                }
            }
            for (s in 0 until ns) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(slots.optString(s), fontSize = 9.sp, color = ZxT.Text3,
                         modifier = Modifier.width(40.dp))
                    for (d in 0 until nd) {
                        var who = ""; var filled = false
                        if (cells != null) for (k in 0 until cells.length()) {
                            val c = cells.optJSONObject(k) ?: continue
                            if (c.optInt("s") == s && c.optInt("d") == d) {
                                who = c.optString("who"); filled = who.isNotBlank(); break
                            }
                        }
                        val on = sel?.first == d && sel?.second == s
                        Box(Modifier.width(46.dp).height(30.dp).padding(1.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(if (filled) PAL[(d + s) % PAL.size].copy(.28f)
                                        else ZxT.Surface2)
                            .border(if (on) 1.5.dp else 0.dp, ZxT.Text, RoundedCornerShape(5.dp))
                            .clickable {
                                sel = d to s
                                ctx.callTool("zx_select", mapOf(
                                    "d" to d.toString(), "s" to s.toString(),
                                    "who" to who, "source" to "zx_schedule"))
                            }, contentAlignment = Alignment.Center) {
                            if (who.isNotBlank())
                                Text(who, fontSize = 9.sp, color = ZxT.Text2, maxLines = 1,
                                     overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (sel != null)
                Text("${days.optString(sel!!.first)} ${slots.optString(sel!!.second)}",
                     fontSize = 10.sp, color = ZxT.Text3,
                     modifier = Modifier.padding(top = 5.dp))
        }
    }

    /* ══════════ zx_nps NPS 评分条 ══════════ */
    @Composable
    fun nps(o: JSONObject, ctx: RenderContext) {
        val detr = o.optInt("detractors", 0)
        val pass = o.optInt("passives", 0)
        val prom = o.optInt("promoters", 0)
        val total = maxOf(1, o.optInt("total", detr + pass + prom))
        val score = ((prom - detr).toDouble() / total * 100).toInt()
        var sel by remember { mutableIntStateOf(-1) }
        val segs = listOf(
            "贬损者 0-6" to detr to ZxT.Err,
            "被动者 7-8" to pass to ZxT.Warn,
            "推荐者 9-10" to prom to ZxT.Ok)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "NPS"), fontSize = 11.5.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("$score", fontSize = 22.sp, fontWeight = FontWeight.Bold,
                     color = if (score >= 0) ZxT.Ok else ZxT.Err, fontFamily = ZxT.Mono)
            }
            Spacer(Modifier.height(7.dp))
            Row(Modifier.fillMaxWidth().height(20.dp)
                .clip(RoundedCornerShape(5.dp))) {
                segs.forEachIndexed { i, e ->
                    val nm = e.first.first; val v = e.first.second; val col = e.second
                    Box(Modifier.weight(v.toFloat().coerceAtLeast(0.01f)).fillMaxHeight()
                        .background(col.copy(if (sel == i) .95f else .65f))
                        .clickable {
                            sel = i
                            ctx.callTool("zx_select", mapOf("choice" to nm, "v" to v.toString(),
                                                            "source" to "zx_nps"))
                        }, contentAlignment = Alignment.Center) {
                        if (v * 100 / total > 8)
                            Text("${v * 100 / total}%", fontSize = 9.sp, color = Color.White)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)) {
                segs.forEach { e ->
                    val nm = e.first.first; val v = e.first.second; val col = e.second
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(RoundedCornerShape(2.dp)).background(col))
                        Spacer(Modifier.width(4.dp))
                        Text(nm.split(" ").first(), fontSize = 8.5.sp, color = ZxT.Text3,
                             maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Text("NPS = 推荐者% − 贬损者% = $score（样本 $total）",
                 fontSize = 10.sp, color = ZxT.Text3,
                 modifier = Modifier.padding(top = 5.dp))
        }
    }

    /* ══════════ zx_export 导出卡 ══════════ */
    @Composable
    fun export(o: JSONObject, ctx: RenderContext) {
        val fmts = o.optJSONArray("fmts") ?: return
        val n = minOf(fmts.length(), 5)
        var sel by remember { mutableIntStateOf(-1) }
        var done by remember { mutableStateOf<String?>(null) }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "导出"), fontSize = 11.5.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                if (o.optString("size").isNotBlank())
                    Text(o.optString("size"), fontSize = 10.sp, color = ZxT.Text3,
                         fontFamily = ZxT.Mono)
            }
            Spacer(Modifier.height(7.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                for (i in 0 until n) {
                    val f = fmts.optJSONObject(i) ?: continue
                    val on = sel == i
                    Row(Modifier.clip(RoundedCornerShape(8.dp))
                        .background(if (on) ZxT.Primary.copy(.2f) else ZxT.Surface2)
                        .border(1.dp, if (on) ZxT.Primary else ZxT.Line,
                                RoundedCornerShape(8.dp))
                        .clickable { sel = i }.padding(10.dp, 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(f.optString("e", "▤"), fontSize = 11.sp, color = ZxT.Text3)
                        Spacer(Modifier.width(5.dp))
                        Text(f.optString("n", ""), fontSize = 10.5.sp,
                             color = if (on) ZxT.PriLite else ZxT.Text2)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (sel < 0) ZxT.Surface3 else ZxT.Primary)
                .clickable(enabled = sel >= 0) {
                    val f = fmts.optJSONObject(sel)
                    done = f?.optString("n")
                    ctx.callTool("zx_form_submit", mapOf("payload" to
                        JSONObject().put("fmt", f?.optString("k"))
                            .put("target", o.optString("target", "")).toString()))
                }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                Text(if (done != null) "已导出 $done" else if (sel < 0) "先选一个格式" else "导出",
                     fontSize = 11.5.sp, color = if (sel < 0) ZxT.Text3 else Color.White)
            }
            // 未选格式时按钮禁用而不是点了没反应
            Text("对话的终点往往是一个文件，不是一个界面——前十三批全都是「看」和「填」，缺的就是「拿走」",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_bargauge 条形仪表 ══════════ */
    @Composable
    fun bargauge(o: JSONObject, ctx: RenderContext) {
        val items = o.optJSONArray("items") ?: return
        val n = minOf(items.length(), 6)
        var sel by remember { mutableIntStateOf(-1) }
        val maxV = o.optDouble("max", Double.NaN).let {
            if (it.isNaN()) (0 until n).maxOf { items.optJSONObject(it)?.optDouble("v", 0.0) ?: 0.0 }
                .coerceAtLeast(0.001) else it }

        Column(Modifier.fillMaxWidth()) {
            for (i in 0 until n) {
                val it = items.optJSONObject(i) ?: continue
                val v = it.optDouble("v", 0.0)
                val t = (v / maxV).coerceIn(0.0, 1.0).toFloat()
                val tgt = it.optDouble("tgt", Double.NaN)
                val col = runCatching {
                    Color(android.graphics.Color.parseColor(it.optString("hex"))) }.getOrNull()
                    ?: PAL[i % PAL.size]
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (sel == i) col.copy(.1f) else Color.Transparent)
                    .clickable {
                        sel = i
                        ctx.callTool("zx_select", mapOf(
                            "choice" to it.optString("n", "$i"), "v" to v.toString(),
                            "source" to "zx_bargauge"))
                    }.padding(5.dp, 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(it.optString("n", ""), fontSize = 10.5.sp, color = ZxT.Text2,
                         modifier = Modifier.width(58.dp), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    Box(Modifier.weight(1f).height(16.dp)) {
                        Box(Modifier.fillMaxWidth().height(10.dp)
                            .clip(RoundedCornerShape(3.dp)).background(ZxT.Surface3))
                        Box(Modifier.fillMaxWidth(t).height(10.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Brush.horizontalGradient(
                                listOf(col.copy(.55f), col))))
                        // 目标刻线：不画就看不出差多少
                        if (!tgt.isNaN() && tgt <= maxV) {
                            val tt = (tgt / maxV).toFloat().coerceIn(0f, 1f)
                            Box(Modifier.fillMaxWidth().height(10.dp),
                                contentAlignment = Alignment.CenterStart) {
                                Box(Modifier.offset(x = (tt * 300 - 1).toInt().dp)
                                    .width(2.dp).height(14.dp).background(ZxT.Text))
                            }
                        }
                    }
                    Text(if (v == kotlin.math.floor(v)) v.toLong().toString()
                         else "%.1f".format(v),
                         fontSize = 10.sp, color = ZxT.Text2, fontFamily = ZxT.Mono,
                         modifier = Modifier.width(38.dp), textAlign = TextAlign.End)
                }
            }
            Text("量程 0–${if (maxV == kotlin.math.floor(maxV)) maxV.toLong().toString()else "%.1f".format(maxV)} · 竖线为目标刻线",
                 fontSize = 10.sp, color = ZxT.Text3,
                 modifier = Modifier.padding(top = 5.dp))
        }
    }
}
