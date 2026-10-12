package com.zorv.plugin.zxwidget

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 第二十批组件（12 个）—— 密度 / 分布 / 编码。
 *
 * 前十九批把"看什么、填什么、改什么、凭什么"铺完了。
 * 剩下的是三类**常规图表库有、但气泡里一直没有**的：
 *
 *   一、**高密度**。已有 line / area / sparkline，
 *       但**地平线图**（把一条长时序折叠成几条带）
 *       和**流图**（多条序列堆叠的趋势）没有。
 *       气泡空间小，密度就是信息量。
 *
 *   二、**分布**。已有 histogram / boxplot / ridgeline，
 *       但**小提琴图**（看出是不是双峰）、
 *       **蜂群图**（看出每个个体在哪）没有。
 *       "平均值一样"不代表"分布一样"。
 *
 *   三、**多变量编码**。已有 radar / parallel 式组件，
 *       但**平行坐标**（高维看相关性）、
 *       **字形网格**（一个形状编码五个变量）没有。
 *
 * 贯穿这批的一条：**密度不等于拥挤**。
 * 每个都定死上限，超了就拦——格子小到只剩色块
 * 就没有信息了，而**看不清比渲染失败更糟**，
 * 因为模型会以为成功了。
 *
 * 另一个共同点：这批大多有**"形状前提"**。
 * 等高线不闭合、流图总量为 0、小提琴所有值相同
 * ——这些都不崩，只是画出一个**看起来正常、
 * 实际是错的**图形。用户会基于错误形状做判断，
 * 所以必须在校验阶段拦掉。
 */
object ZxRender20 {

    private val PAL = listOf(
        Color(0xFF5046E4), Color(0xFF3FBF7F), Color(0xFFE8A33D),
        Color(0xFFE5544B), Color(0xFF4A9BE8), Color(0xFF8E86FF)
    )

    /* ══════════ zx_contour 等高线 ══════════ */
    @Composable
    fun contour(o: JSONObject, ctx: RenderContext) {
        val grid = o.optJSONArray("grid") ?: return
        val rows = minOf(grid.length(), 12)
        val cols = (0 until rows).maxOf { grid.optJSONArray(it)?.length() ?: 0 }
            .coerceAtMost(12).coerceAtLeast(1)
        val levels = o.optJSONArray("levels")
        val nl = minOf(levels?.length() ?: 5, 7)
        var sel by remember { mutableIntStateOf(-1) }
        // 取值范围
        var mn = Double.MAX_VALUE; var mx = -Double.MAX_VALUE
        for (r in 0 until rows) for (c in 0 until cols) {
            val v = grid.optJSONArray(r)?.optDouble(c, Double.NaN) ?: continue
            if (v.isNaN()) continue
            mn = min(mn, v); mx = max(mx, v)
        }
        if (mn == Double.MAX_VALUE) { mn = 0.0; mx = 1.0 }
        val span = (mx - mn).coerceAtLeast(1e-9)
        val lvs = (0 until nl).map { i ->
            levels?.optDouble(i) ?: (mn + span * (i + 1) / (nl + 1))
        }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "等高线"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("%.1f – %.1f".format(mn, mx), fontSize = 9.sp, color = ZxT.Text3,
                     fontFamily = ZxT.Mono)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(150.dp)) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height
                    //  marching squares 简化版：逐格画等值线段
                    fun at(r: Int, c: Int): Double {
                        if (r !in 0 until rows || c !in 0 until cols) return mn
                        val v = grid.optJSONArray(r)?.optDouble(c, Double.NaN) ?: return mn
                        return if (v.isNaN()) mn else v
                    }
                    lvs.forEachIndexed { li, lv ->
                        val col = PAL[li % PAL.size]
                        val on = sel == li
                        for (r in 0 until rows - 1) for (c in 0 until cols - 1) {
                            val x0 = W * c / cols; val x1 = W * (c + 1) / cols
                            val y0 = H * r / rows; val y1 = H * (r + 1) / rows
                            val a = at(r, c); val b = at(r, c + 1)
                            val d = at(r + 1, c); val e = at(r + 1, c + 1)
                            // 四条边上的交点，线性插值
                            val pts = mutableListOf<Offset>()
                            fun lerpX(v: Double) = x0 + (x1 - x0) * ((lv - a) / (b - a)).toFloat()
                            fun lerpY(v: Double) = y0 + (y1 - y0) * ((lv - a) / (d - a)).toFloat()
                            if ((a < lv) != (b < lv)) pts.add(Offset(lerpX(b), y0))
                            if ((b < lv) != (e < lv)) pts.add(Offset(x1, y0 + (y1 - y0) *
                                ((lv - b) / (e - b)).toFloat()))
                            if ((d < lv) != (e < lv)) pts.add(Offset(x0 + (x1 - x0) *
                                ((lv - d) / (e - d)).toFloat(), y1))
                            if ((a < lv) != (d < lv)) pts.add(Offset(x0, lerpY(d)))
                            for (k in 0 until pts.size - 1 step 2)
                                drawLine(if (on) ZxT.Text else col, pts[k], pts[k + 1],
                                         if (on) 2f else 1.2f)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().height(150.dp)) {
                    (0 until nl).forEach { i ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = i
                            ctx.callTool("zx_select", mapOf(
                                "level" to lvs[i].toString(),
                                "source" to "zx_contour"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)
                .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                lvs.forEachIndexed { i, lv ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(12.dp).height(1.6.dp)
                            .background(if (sel == i) ZxT.Text else PAL[i % PAL.size]))
                        Spacer(Modifier.width(3.dp))
                        Text("%.1f".format(lv), fontSize = 8.5.sp, color = ZxT.Text3,
                             fontFamily = ZxT.Mono)
                    }
                }
            }
            Text("已有 heatmap / matrixheat 用色块表达高度，" +
                 "这个用「等值线」——能看出梯度陡不陡。" +
                 "值全同会被拦：画不出任何等值线",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 5.dp))
        }
    }

    /* ══════════ zx_stream 流图 ══════════ */
    @Composable
    fun stream(o: JSONObject, ctx: RenderContext) {
        val series = o.optJSONArray("series") ?: return
        val n = minOf(series.length(), 6)
        val len = (0 until n).maxOf { series.optJSONObject(it)?.optJSONArray("v")?.length() ?: 0 }
            .coerceAtMost(24)
        var sel by remember { mutableIntStateOf(-1) }
        var hover by remember { mutableIntStateOf(-1) }
        val vals = (0 until n).map { i ->
            val arr = series.optJSONObject(i)?.optJSONArray("v")
            (0 until len).map { arr?.optDouble(it, 0.0) ?: 0.0 }
        }
        val totals = (0 until len).map { k -> (0 until n).sumOf { vals[it][k] } }
        val maxTot = totals.maxOrNull()?.coerceAtLeast(1e-9) ?: 1.0

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "流图"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("$n 序列 × $len 点", fontSize = 9.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(140.dp)) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height
                    // 基线居中（ThemeRiver）：上下对称堆叠，形状更像"河流"
                    for (k in 0 until len) {
                        val x = W * k / (len - 1).coerceAtLeast(1)
                        var yTop = H / 2f
                        var yBot = H / 2f
                        for (i in 0 until n) {
                            val frac = (vals[i][k] / maxTot).toFloat()
                            val band = frac * H * .92f
                            // 偶数序列向上、奇数向下：视觉上交错
                            if (i % 2 == 0) { yTop -= band / 2; }
                            else { yBot += band / 2; }
                        }
                        Unit
                    }
                    // 逐段画带宽：每段一个梯形，避免整条曲线自交
                    var prevTop = FloatArray(n)
                    var prevBot = FloatArray(n)
                    for (k in 0 until len) {
                        val x = W * k / (len - 1).coerceAtLeast(1)
                        val xPrev = if (k == 0) 0f else W * (k - 1) / (len - 1).coerceAtLeast(1)
                        var acc = 0f
                        val tot = totals[k].toFloat()
                        var y0 = H / 2f - (tot / maxTot.toFloat()) * H * .46f
                        for (i in 0 until n) {
                            val h = (vals[i][k] / maxTot).toFloat() * H * .92f
                            val y1 = y0 + h
                            val col = PAL[i % PAL.size]
                            val alpha = if (sel < 0 || sel == i) .78f else .22f
                            if (k > 0) {
                                val pTop = prevTop[i]; val pBot = prevBot[i]
                                drawPath(Path().apply {
                                    moveTo(xPrev, pTop); lineTo(x, y0)
                                    lineTo(x, y1); lineTo(xPrev, pBot); close() },
                                    col.copy(alpha))
                            }
                            prevTop[i] = y0; prevBot[i] = y1
                            y0 = y1
                        }
                        if (hover == k)
                            drawLine(ZxT.Text, Offset(x, 0f), Offset(x, H), 1.2f)
                    }
                }
                Row(Modifier.fillMaxWidth().height(140.dp)) {
                    (0 until len).forEach { k ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            hover = k
                            ctx.callTool("zx_select", mapOf(
                                "point" to k.toString(),
                                "total" to totals[k].toString(),
                                "source" to "zx_stream"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)
                .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (i in 0 until n) {
                    val nm = series.optJSONObject(i)?.optString("n", "") ?: ""
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { sel = if (sel == i) -1 else i }) {
                        Box(Modifier.size(7.dp).clip(CircleShape)
                            .background(PAL[i % PAL.size].copy(if (sel < 0 || sel == i) 1f else .3f)))
                        Spacer(Modifier.width(3.dp))
                        Text(nm, fontSize = 8.5.sp,
                             color = if (sel == i) ZxT.Text else ZxT.Text3,
                             maxLines = 1)
                    }
                }
            }
            Text("已有 stack（堆叠）是直筒的、area 是单序列，" +
                 "这个用「居中基线」——形状更像河流，能看出各序列此消彼长。" +
                 "总量全 0 会被拦",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 5.dp))
        }
    }

    /* ══════════ zx_horizon 地平线图 ══════════ */
    @Composable
    fun horizon(o: JSONObject, ctx: RenderContext) {
        val pts = o.optJSONArray("pts") ?: return
        val n = minOf(pts.length(), 120)
        val bands = o.optInt("bands", 3).coerceIn(2, 5)
        var sel by remember { mutableIntStateOf(-1) }
        val vs = (0 until n).map { pts.optDouble(it, 0.0) }
        val mx = vs.maxOrNull()?.coerceAtLeast(1e-9) ?: 1.0
        // 折叠：每段高度 = mx / bands，超出就往上叠一层
        val bandH = mx / bands

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "地平线"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("$n 点 · $bands 层", fontSize = 9.sp, color = ZxT.Text3)
                Spacer(Modifier.width(6.dp))
                Text("峰值 %.0f".format(mx), fontSize = 9.sp, color = ZxT.Text3,
                     fontFamily = ZxT.Mono)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(70.dp)) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height
                    for (b in 0 until bands) {
                        val alpha = .28f + b * (0.62f / bands)
                        val col = PAL[0].copy(alpha)
                        val path = Path()
                        var started = false
                        for (k in 0 until n) {
                            val x = W * k / (n - 1).coerceAtLeast(1)
                            // 该点在这一层的高度：v 减去 b 层基底，夹在本层内
                            val raw = vs[k] - b * bandH
                            val h = raw.coerceIn(0.0, bandH) / bandH * H
                            val y = H - h.toFloat()
                            if (!started) { path.moveTo(x, H); path.lineTo(x, y); started = true }
                            else path.lineTo(x, y)
                        }
                        // 反向闭合，形成"从底部填充"的带
                        for (k in n - 1 downTo 0) {
                            val x = W * k / (n - 1).coerceAtLeast(1)
                            val raw = vs[k] - b * bandH
                            val h = raw.coerceIn(0.0, bandH) / bandH * H
                            path.lineTo(x, H - h.toFloat())
                        }
                        path.close()
                        drawPath(path, col)
                    }
                    if (sel >= 0) {
                        val x = W * sel / (n - 1).coerceAtLeast(1)
                        drawLine(ZxT.Text, Offset(x, 0f), Offset(x, H), 1.2f)
                    }
                }
                Row(Modifier.fillMaxWidth().height(70.dp)) {
                    (0 until n).forEach { k ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = k
                            ctx.callTool("zx_select", mapOf(
                                "point" to k.toString(), "v" to vs[k].toString(),
                                "band" to minOf((vs[k] / bandH).toInt(), bands - 1).toString(),
                                "source" to "zx_horizon"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text("把一条长时序折叠成 $bands 层，越亮越高", fontSize = 9.sp,
                     color = ZxT.Text3)
                Spacer(Modifier.weight(1f))
                if (sel >= 0)
                    Text("#$sel = %.0f".format(vs[sel]), fontSize = 9.5.sp,
                         color = ZxT.PriLite, fontFamily = ZxT.Mono)
            }
            Text("同样的高度能装下 3-5 倍的分辨率——" +
                 "120 个点在 70dp 里用折线画就是一团糊。" +
                 "「负值会被拦」：地平线图无法表达负值",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 5.dp))
        }
    }

    /* ══════════ zx_parallel 平行坐标 ══════════ */
    @Composable
    fun parallel(o: JSONObject, ctx: RenderContext) {
        val axes = o.optJSONArray("axes") ?: return
        val na = minOf(axes.length(), 6)
        val rows = o.optJSONArray("rows") ?: return
        val nr = minOf(rows.length(), 14)
        var sel by remember { mutableIntStateOf(-1) }
        // 每轴范围：min/max，缺省按数据推
        val ranges = (0 until na).map { i ->
            val ax = axes.optJSONObject(i)
            var lo = ax?.optDouble("min", Double.NaN) ?: Double.NaN
            var hi = ax?.optDouble("max", Double.NaN) ?: Double.NaN
            if (lo.isNaN() || hi.isNaN()) {
                var a = Double.MAX_VALUE; var b = -Double.MAX_VALUE
                for (r in 0 until nr) {
                    val v = rows.optJSONArray(r)?.optDouble(i, Double.NaN) ?: continue
                    if (v.isNaN()) continue
                    a = min(a, v); b = max(b, v)
                }
                if (a == Double.MAX_VALUE) { a = 0.0; b = 1.0 }
                if (lo.isNaN()) lo = a
                if (hi.isNaN()) hi = b
            }
            lo to hi
        }

        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                for (i in 0 until na)
                    Text(axes.optJSONObject(i)?.optString("n", "") ?: "", fontSize = 8.sp,
                         color = ZxT.Text3, modifier = Modifier.width(46.dp),
                         textAlign = TextAlign.Center, maxLines = 1,
                         overflow = TextOverflow.Clip)
            }
            Box(Modifier.fillMaxWidth().height(140.dp)) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height
                    fun xAt(i: Int) = W * (i + .5f) / na
                    // 轴
                    for (i in 0 until na) {
                        val x = xAt(i)
                        drawLine(ZxT.Surface3, Offset(x, 4f), Offset(x, H - 12f), 1f)
                    }
                    for (r in 0 until nr) {
                        val arr = rows.optJSONArray(r) ?: continue
                        val on = sel == r
                        val path = Path()
                        var started = false
                        for (i in 0 until na) {
                            val v = arr.optDouble(i, Double.NaN)
                            if (v.isNaN()) continue
                            val (lo, hi) = ranges[i]
                            val t = ((v - lo) / (hi - lo).coerceAtLeast(1e-9))
                                .coerceIn(0.0, 1.0).toFloat()
                            val x = xAt(i)
                            val y = H - 12f - t * (H - 16f)
                            if (!started) { path.moveTo(x, y); started = true }
                            else path.lineTo(x, y)
                        }
                        drawPath(path, if (on) ZxT.Text
                        else PAL[r % PAL.size].copy(.42f),
                            style = Stroke(if (on) 2.2f else 1.3f))
                    }
                }
                Row(Modifier.fillMaxWidth().height(140.dp)) {
                    (0 until nr).forEach { r ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = r
                            ctx.callTool("zx_select", mapOf(
                                "row" to r.toString(), "source" to "zx_parallel"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text("每条折线是一条记录", fontSize = 9.sp, color = ZxT.Text3)
                Spacer(Modifier.weight(1f))
                if (sel >= 0) Text("第 ${sel + 1} 条", fontSize = 9.5.sp, color = ZxT.PriLite)
            }
            Text("已有 radar 只能看 6-8 个维度且轴是放射的，" +
                 "这个能看「任意多维」，且能看出「相关性」" +
                 "（一束平行线 = 正相关，交叉 = 负相关）",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 5.dp))
        }
    }

    /* ══════════ zx_beeswarm 蜂群图 ══════════ */
    @Composable
    fun beeswarm(o: JSONObject, ctx: RenderContext) {
        val items = o.optJSONArray("items") ?: return
        val n = minOf(items.length(), 60)
        var sel by remember { mutableIntStateOf(-1) }
        val vs = (0 until n).map { items.optJSONObject(it)?.optDouble("v", 0.0) ?: 0.0 }
        val mn = vs.minOrNull() ?: 0.0
        val mx = vs.maxOrNull() ?: 1.0
        val span = (mx - mn).coerceAtLeast(1e-9)
        // 抖动：同一 x 附近若已有近点，往上挪一行
        val placed = mutableListOf<Pair<Float, Float>>()
        val pos = vs.map { v ->
            val x = ((v - mn) / span).toFloat()
            var y = 0f
            var guard = 0
            while (guard++ < 12) {
                val clash = placed.any { (px, py) ->
                    abs(px - x) < .022f && abs(py - y) < .55f }
                if (!clash) break
                y += (if (guard % 2 == 1) 1f else -1f) * guard * .5f
            }
            placed.add(x to y)
            x to y.coerceIn(-2.2f, 2.2f)
        }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "分布"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("$n 个个体", fontSize = 9.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(120.dp)) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height
                    // 基线
                    drawLine(ZxT.Surface3, Offset(0f, H - 8f), Offset(W, H - 8f), 1f)
                    val maxAbs = pos.maxOfOrNull { abs(it.second) }?.coerceAtLeast(.5f) ?: 1f
                    for (i in 0 until n) {
                        val (x, y) = pos[i]
                        val cx = 14f + x * (W - 28f)
                        val cy = H / 2f + y / maxAbs * (H / 2f - 14f)
                        val on = sel == i
                        drawCircle(if (on) ZxT.Text else PAL[0].copy(.6f),
                                   if (on) 4.5f else 3.4f, Offset(cx, cy))
                    }
                }
                Row(Modifier.fillMaxWidth().height(120.dp)) {
                    (0 until n).forEach { i ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = i
                            ctx.callTool("zx_select", mapOf(
                                "item" to items.optJSONObject(i)?.optString("n", "$i"),
                                "v" to vs[i].toString(), "source" to "zx_beeswarm"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                Text("%.1f".format(mn), fontSize = 8.5.sp, color = ZxT.Text3,
                     fontFamily = ZxT.Mono)
                Spacer(Modifier.weight(1f))
                Text("%.1f".format(mx), fontSize = 8.5.sp, color = ZxT.Text3,
                     fontFamily = ZxT.Mono)
            }
            if (sel >= 0)
                Text("${items.optJSONObject(sel)?.optString("n", "")} = %.1f".format(vs[sel]),
                     fontSize = 10.sp, color = ZxT.PriLite,
                     modifier = Modifier.padding(top = 3.dp))
            Text("已有 histogram 只给分箱计数、boxplot 只给五数概括，" +
                 "这个能「看到每一个个体」在哪—— outliers 是谁一目了然",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 5.dp))
        }
    }

    /* ══════════ zx_violin 小提琴图 ══════════ */
    @Composable
    fun violin(o: JSONObject, ctx: RenderContext) {
        val groups = o.optJSONArray("groups") ?: return
        val n = minOf(groups.length(), 5)
        var sel by remember { mutableIntStateOf(-1) }
        // 用直方图近似密度轮廓
        fun widthAt(i: Int, t: Float): Float {
            val g = groups.optJSONObject(i) ?: return 0f
            val pts = g.optJSONArray("pts") ?: return 0f
            val m = minOf(pts.length(), 40)
            if (m == 0) return 0f
            val vs = (0 until m).map { pts.optDouble(it, 0.0) }
            val lo = g.optDouble("min", vs.minOrNull() ?: 0.0)
            val hi = g.optDouble("max", vs.maxOrNull() ?: 1.0)
            val span = (hi - lo).coerceAtLeast(1e-9)
            val target = lo + span * t
            // 核密度简化：统计窗口内的点数
            val win = span * .12
            val cnt = vs.count { abs(it - target) < win }
            return (cnt.toFloat() / m).coerceIn(0f, 1f)
        }
        val globalMax = run {
            var a = Double.MAX_VALUE; var b = -Double.MAX_VALUE
            for (i in 0 until n) {
                val g = groups.optJSONObject(i) ?: continue
                val pts = g.optJSONArray("pts") ?: continue
                for (k in 0 until minOf(pts.length(), 40)) {
                    val v = pts.optDouble(k, Double.NaN)
                    if (v.isNaN()) continue
                    a = min(a, v); b = max(b, v)
                }
            }
            if (a == Double.MAX_VALUE) 0.0 to 1.0 else a to b
        }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "分布对比"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("$n 组", fontSize = 9.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(150.dp)) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height
                    for (i in 0 until n) {
                        val cx = W * (i + .5f) / n
                        val maxW = W / n * .38f
                        val on = sel == i
                        // 左右对称画轮廓
                        val path = Path()
                        val steps = 24
                        for (k in 0..steps) {
                            val t = k.toFloat() / steps
                            val y = H - 10f - t * (H - 20f)
                            val w = widthAt(i, t) * maxW
                            if (k == 0) path.moveTo(cx - w, y) else path.lineTo(cx - w, y)
                        }
                        for (k in steps downTo 0) {
                            val t = k.toFloat() / steps
                            val y = H - 10f - t * (H - 20f)
                            val w = widthAt(i, t) * maxW
                            path.lineTo(cx + w, y)
                        }
                        path.close()
                        val col = PAL[i % PAL.size]
                        drawPath(path, col.copy(if (on) .5f else .26f))
                        drawPath(path, col, style = Stroke(if (on) 1.8f else 1.1f))
                        // 中位数线
                        val g = groups.optJSONObject(i)
                        val med = g?.optDouble("med", Double.NaN) ?: Double.NaN
                        if (!med.isNaN()) {
                            val (lo, hi) = globalMax
                            val tt = ((med - lo) / (hi - lo).coerceAtLeast(1e-9))
                                .coerceIn(0.0, 1.0).toFloat()
                            val y = H - 10f - tt * (H - 20f)
                            drawLine(ZxT.Text, Offset(cx - maxW * .5f, y),
                                     Offset(cx + maxW * .5f, y), 2f)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().height(150.dp)) {
                    (0 until n).forEach { i ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = i
                            ctx.callTool("zx_select", mapOf(
                                "group" to groups.optJSONObject(i)?.optString("n", "$i"),
                                "source" to "zx_violin"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                for (i in 0 until n)
                    Text(groups.optJSONObject(i)?.optString("n", "") ?: "",
                         fontSize = 8.sp, color = if (sel == i) ZxT.Text else ZxT.Text3,
                         modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                         maxLines = 1, overflow = TextOverflow.Clip)
            }
            Text("已有 boxplot 只有五数概括，「看不出是不是双峰」。" +
                 "这个能看出分布形状——两个峰和均匀分布的中位数可以完全一样",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 5.dp))
        }
    }

    /* ══════════ zx_glyph 字形网格 ══════════ */
    @Composable
    fun glyph(o: JSONObject, ctx: RenderContext) {
        val dims = o.optJSONArray("dims") ?: return
        val nd = minOf(dims.length(), 5)
        val items = o.optJSONArray("items") ?: return
        val n = minOf(items.length(), 24)
        var sel by remember { mutableIntStateOf(-1) }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "多维对比"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("$n × $nd 维", fontSize = 9.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(6.dp))
            // 自适应列数：≤6 个一行 3 个，否则 4-6 个
            val cols = when { n <= 6 -> 3; n <= 12 -> 4; else -> 6 }
            val rowsN = (n + cols - 1) / cols
            Column {
                for (r in 0 until rowsN) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (c in 0 until cols) {
                            val i = r * cols + c
                            if (i >= n) { Spacer(Modifier.weight(1f)); continue }
                            val it = items.optJSONObject(i)
                            val vs = (0 until nd).map { d ->
                                it?.optJSONArray("v")?.optDouble(d, .5)?.coerceIn(0.0, 1.0)
                                    ?: .5 }
                            val on = sel == i
                            Box(Modifier.weight(1f).aspectRatio(1f)
                                .clip(RoundedCornerShape(5.dp))
                                .background(if (on) ZxT.Surface2 else Color.Transparent)
                                .clickable {
                                    sel = i
                                    ctx.callTool("zx_select", mapOf(
                                        "item" to (it?.optString("n", "$i") ?: ""),
                                        "source" to "zx_glyph"))
                                }) {
                                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                                    val cx = size.width / 2; val cy = size.height / 2
                                    val R = minOf(size.width, size.height) * .40f
                                    // 星形：每个维度一个角，半径 = 该维度值
                                    val path = Path()
                                    for (d in 0 until nd) {
                                        val a = (Math.PI * 2 * d / nd - Math.PI / 2)
                                        val rr = R * (.28f + vs[d].toFloat() * .72f)
                                        val x = cx + cos(a).toFloat() * rr
                                        val y = cy + sin(a).toFloat() * rr
                                        if (d == 0) path.moveTo(x, y) else path.lineTo(x, y)
                                    }
                                    path.close()
                                    drawPath(path, PAL[i % PAL.size]
                                        .copy(if (on) .8f else .5f))
                                    // 参考圆：没有它看不出"大"是多少
                                    drawCircle(ZxT.Text3.copy(.35f), R * .28f,
                                               Offset(cx, cy), style = Stroke(.8f))
                                }
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)
                .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (d in 0 until nd)
                    Text(dims.optString(d), fontSize = 8.5.sp, color = ZxT.Text3)
            }
            if (sel >= 0) {
                val it = items.optJSONObject(sel)
                Text("${it?.optString("n", "")}：" + (0 until nd).map { d ->
                    "${dims.optString(d)} ${"%.2f".format(it?.optJSONArray("v")?.optDouble(d, .5) ?: .5)}" }.joinToString(" · "),
                     fontSize = 9.5.sp, color = ZxT.PriLite, lineHeight = 13.sp,
                     modifier = Modifier.padding(top = 3.dp))
            }
            Text("已有 radar 一次只能比 2-3 个对象，" +
                 "这个用「小倍图」一次比 24 个——形状差异一眼扫出来。" +
                 "中间参考圆必须有，没有它看不出「大」是多少",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 5.dp))
        }
    }

    /* ══════════ zx_arc 弧形图 ══════════ */
    @Composable
    fun arc(o: JSONObject, ctx: RenderContext) {
        val nodes = o.optJSONArray("nodes") ?: return
        val n = minOf(nodes.length(), 24)
        val edges = o.optJSONArray("edges") ?: return
        val ne = minOf(edges.length(), 30)
        var sel by remember { mutableIntStateOf(-1) }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "关系弧"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("$n 节点 · $ne 连线", fontSize = 9.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(130.dp)) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height
                    val baseY = H - 12f
                    fun xAt(i: Int) = 12f + (W - 24f) * i / (n - 1).coerceAtLeast(1)
                    // 基线
                    drawLine(ZxT.Surface3, Offset(8f, baseY), Offset(W - 8f, baseY), 1f)
                    for (i in 0 until n)
                        drawCircle(ZxT.Text3, 2f, Offset(xAt(i), baseY))
                    for (k in 0 until ne) {
                        val e = edges.optJSONObject(k) ?: continue
                        val f = e.optInt("f", -1); val t = e.optInt("t", -1)
                        if (f !in 0 until n || t !in 0 until n) continue
                        val a = minOf(f, t); val b = maxOf(f, t)
                        val x0 = xAt(a); val x1 = xAt(b)
                        val r = (x1 - x0) / 2f
                        val on = sel == k
                        // 半圆：跨度越大弧越高
                        val p = Path()
                        p.moveTo(x0, baseY)
                        p.addArc(androidx.compose.ui.geometry.Rect(
                            x0, baseY - r * .9f, x1, baseY + r * .9f), 180f, 180f)
                        drawPath(p, (if (on) ZxT.Text else PAL[k % PAL.size].copy(.55f)),
                                 style = Stroke(if (on) 2f else 1.2f))
                    }
                }
                Row(Modifier.fillMaxWidth().height(130.dp)) {
                    (0 until ne).forEach { k ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = k
                            ctx.callTool("zx_select", mapOf(
                                "from" to nodes.optString(edges.optJSONObject(k)?.optInt("f", -1) ?: -1),
                                "to" to nodes.optString(edges.optJSONObject(k)?.optInt("t", -1) ?: -1),
                                "source" to "zx_arc"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                Text(nodes.optString(0), fontSize = 8.sp, color = ZxT.Text3)
                Spacer(Modifier.weight(1f))
                Text(nodes.optString(n - 1), fontSize = 8.sp, color = ZxT.Text3)
            }
            if (sel >= 0) {
                val e = edges.optJSONObject(sel)
                Text("${nodes.optString(e?.optInt("f", -1) ?: -1)} ↔ " +
                     "${nodes.optString(e?.optInt("t", -1) ?: -1)}" +
                     "（跨度 ${abs((e?.optInt("t", 0) ?: 0) - (e?.optInt("f", 0) ?: 0))}）",
                     fontSize = 10.sp, color = ZxT.PriLite,
                     modifier = Modifier.padding(top = 3.dp))
            }
            Text("已有 network（网络图）适合看聚类，" +
                 "这个把节点排在一条线上、连线画成弧——" +
                 "适合看「序列里的远距离关联」（比如代码里的跨文件引用）",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 5.dp))
        }
    }

    /* ══════════ zx_mosaic 马赛克图 ══════════ */
    @Composable
    fun mosaic(o: JSONObject, ctx: RenderContext) {
        val rows = o.optJSONArray("rows") ?: return
        val nr = minOf(rows.length(), 5)
        val cols = o.optJSONArray("cols") ?: return
        val nc = minOf(cols.length(), 5)
        var sel by remember { mutableStateOf<Pair<Int, Int>?>(null) }
        val mat = (0 until nr).map { r ->
            val arr = rows.optJSONObject(r)?.optJSONArray("v")
            (0 until nc).map { c -> arr?.optDouble(c, 0.0) ?: 0.0 }
        }
        val rowTot = (0 until nr).map { r -> mat[r].sum().coerceAtLeast(1e-9) }
        val grand = rowTot.sum().coerceAtLeast(1e-9)
        // 皮尔逊残差：看出哪个格子"比预期多/少"
        val resid = (0 until nr).map { r ->
            (0 until nc).map { c ->
                val colTot = (0 until nr).sumOf { mat[it][c] }
                val exp = rowTot[r] * colTot / grand
                if (exp < 1e-9) 0.0 else (mat[r][c] - exp) / kotlin.math.sqrt(exp)
            }
        }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "列联表"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("残差着色", fontSize = 9.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().height(150.dp)) {
                // 行标签
                Column(Modifier.width(46.dp).fillMaxHeight()) {
                    for (r in 0 until nr) {
                        Box(Modifier.weight(rowTot[r].toFloat()).fillMaxWidth(),
                            contentAlignment = Alignment.CenterStart) {
                            Text(rows.optJSONObject(r)?.optString("n", "") ?: "",
                                 fontSize = 8.sp, color = ZxT.Text3, maxLines = 1,
                                 overflow = TextOverflow.Clip)
                        }
                    }
                }
                Spacer(Modifier.width(4.dp))
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                        var y = 0f
                        for (r in 0 until nr) {
                            val h = (rowTot[r] / grand).toFloat() * size.height
                            var x = 0f
                            for (c in 0 until nc) {
                                val w = (mat[r][c] / rowTot[r]).toFloat() * size.width
                                val rr = resid[r][c]
                                val on = sel?.first == r && sel?.second == c
                                // 蓝=比预期多，红=比预期少
                                val col = when {
                                    rr > 2 -> Color(0xFF4A9BE8)
                                    rr > .8 -> Color(0xFF4A9BE8).copy(.55f)
                                    rr < -2 -> ZxT.Err
                                    rr < -.8 -> ZxT.Err.copy(.55f)
                                    else -> ZxT.Surface3
                                }
                                drawRect(if (on) ZxT.Text else col,
                                         Offset(x, y), Size(w - 1.5f, h - 1.5f))
                                x += w
                            }
                            y += h
                        }
                    }
                    Row(Modifier.fillMaxSize()) {
                        for (c in 0 until nc)
                            Box(Modifier.weight(1f).fillMaxHeight().clickable { })
                    }
                    // 命中层：粗略按行列均分（马赛克格子不等宽，精确命中成本高）
                    Column(Modifier.fillMaxSize()) {
                        for (r in 0 until nr)
                            Row(Modifier.weight(1f).fillMaxWidth()) {
                                for (c in 0 until nc)
                                    Box(Modifier.weight(1f).fillMaxHeight().clickable {
                                        sel = r to c
                                        ctx.callTool("zx_select", mapOf(
                                            "row" to (rows.optJSONObject(r)?.optString("n", "") ?: ""),
                                            "col" to cols.optString(c),
                                            "v" to mat[r][c].toString(),
                                            "resid" to resid[r][c].toString(),
                                            "source" to "zx_mosaic"))
                                    })
                            }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 50.dp, top = 3.dp)) {
                for (c in 0 until nc)
                    Text(cols.optString(c), fontSize = 8.sp, color = ZxT.Text3,
                         modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                         maxLines = 1, overflow = TextOverflow.Clip)
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(RoundedCornerShape(2.dp))
                        .background(Color(0xFF4A9BE8)))
                    Spacer(Modifier.width(3.dp))
                    Text("比预期多", fontSize = 8.5.sp, color = ZxT.Text3)
                }
                Spacer(Modifier.width(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(RoundedCornerShape(2.dp))
                        .background(ZxT.Err))
                    Spacer(Modifier.width(3.dp))
                    Text("比预期少", fontSize = 8.5.sp, color = ZxT.Text3)
                }
                Spacer(Modifier.weight(1f))
                if (sel != null)
                    Text("残差 %+.2f".format(resid[sel!!.first][sel!!.second]),
                         fontSize = 9.5.sp,
                         color = if (resid[sel!!.first][sel!!.second] >= 0)
                             Color(0xFF4A9BE8) else ZxT.Err,
                         fontFamily = ZxT.Mono)
            }
            Text("已有 crosstab（交叉表）只给数字，" +
                 "这个用「面积表达边际、颜色表达残差」——" +
                 "哪个组合「异常地多」一眼看出来",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 5.dp))
        }
    }

    /* ══════════ zx_trail 轨迹图 ══════════ */
    @Composable
    fun trail(o: JSONObject, ctx: RenderContext) {
        val pts = o.optJSONArray("pts") ?: return
        val n = minOf(pts.length(), 60)
        var sel by remember { mutableIntStateOf(-1) }
        val xs = (0 until n).map { pts.optJSONObject(it)?.optDouble("x", .5) ?: .5 }
        val ys = (0 until n).map { pts.optJSONObject(it)?.optDouble("y", .5) ?: .5 }
        val ts = (0 until n).map { pts.optJSONObject(it)?.optDouble("t", it.toDouble()) ?: it.toDouble() }
        val tSpan = (ts.maxOrNull() ?: 1.0) - (ts.minOrNull() ?: 0.0)
        val bg = o.optString("bg", "")

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "轨迹"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("$n 个点", fontSize = 9.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(150.dp)) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height
                    if (bg.isNotBlank())
                        drawRect(ZxT.Surface2, Offset(0f, 0f), Size(W, H))
                    // 连线：越晚越亮
                    for (k in 0 until n - 1) {
                        val a = Offset((xs[k] * W).toFloat(), (ys[k] * H).toFloat())
                        val b = Offset((xs[k + 1] * W).toFloat(), (ys[k + 1] * H).toFloat())
                        val prog = (k.toFloat() / (n - 1).coerceAtLeast(1))
                        drawLine(PAL[0].copy(.18f + prog * .62f), a, b, 1f + prog * 1.6f)
                    }
                    // 采样点：不画点看不出停留
                    for (k in 0 until n step maxOf(1, n / 12)) {
                        val p = Offset((xs[k] * W).toFloat(), (ys[k] * H).toFloat())
                        drawCircle(PAL[1].copy(.5f), 2.2f, p)
                    }
                    if (sel >= 0) {
                        val p = Offset((xs[sel] * W).toFloat(), (ys[sel] * H).toFloat())
                        drawCircle(ZxT.Text, 5.5f, p)
                        drawCircle(ZxT.Text, 3f, p)
                    }
                }
                Row(Modifier.fillMaxWidth().height(150.dp)) {
                    (0 until n).forEach { k ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = k
                            ctx.callTool("zx_select", mapOf(
                                "point" to k.toString(),
                                "x" to xs[k].toString(), "y" to ys[k].toString(),
                                "t" to ts[k].toString(), "source" to "zx_trail"))
                        })
                    }
                }
            }
            // 时间轴
            Box(Modifier.fillMaxWidth().height(16.dp)) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    drawLine(ZxT.Surface3, Offset(0f, size.height / 2),
                             Offset(size.width, size.height / 2), 1f)
                    for (k in 0 until n) {
                        val t = if (tSpan <= 0) 0f else ((ts[k] - (ts.minOrNull() ?: 0.0)) / tSpan).toFloat()
                        drawCircle(PAL[0].copy(.5f), 2f,
                                   Offset(t * size.width, size.height / 2))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                Text("越亮 = 越晚", fontSize = 8.5.sp, color = ZxT.Text3)
                Spacer(Modifier.weight(1f))
                if (sel >= 0)
                    Text("#$sel (%.2f, %.2f)".format(xs[sel], ys[sel]),
                         fontSize = 9.5.sp, color = ZxT.PriLite, fontFamily = ZxT.Mono)
            }
            Text("已有 scatter 是无序的点集、geo 是静态位置，" +
                 "这个表达「先后顺序」——越亮越晚，能看出在哪停留最久。" +
                 "少于 3 个点会被拦：两点只能画一条线",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 5.dp))
        }
    }

    /* ══════════ zx_pictogram 象形图 ══════════ */
    @Composable
    fun pictogram(o: JSONObject, ctx: RenderContext) {
        val total = o.optInt("total", 100)
        val per = o.optInt("per", 1).coerceAtLeast(1)
        val groups = o.optJSONArray("groups") ?: return
        val n = minOf(groups.length(), 5)
        var sel by remember { mutableIntStateOf(-1) }
        val unit = o.optString("unit", "人")
        val icons = (0 until n).map { i ->
            groups.optJSONObject(i)?.optInt("n", 0)?.let { it / per } ?: 0
        }
        val cols = 10

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "构成"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("1 格 = $per $unit", fontSize = 9.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(7.dp))
            for (i in 0 until n) {
                val cnt = minOf(icons[i], 100)
                val g = groups.optJSONObject(i) ?: continue
                val on = sel == i
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (on) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        sel = i
                        ctx.callTool("zx_select", mapOf(
                            "group" to g.optString("n", ""),
                            "count" to (g.optInt("n", 0)).toString(),
                            "pct" to (g.optInt("n", 0).toDouble() /
                                (if (total > 0) total.toDouble() else 1.0) * 100).toString(),
                            "source" to "zx_pictogram"))
                    }.padding(4.dp, 3.dp)) {
                    Text(g.optString("n", ""), fontSize = 9.sp, color = ZxT.Text3,
                         modifier = Modifier.width(48.dp), maxLines = 1,
                         overflow = TextOverflow.Clip)
                    // 图标网格：wrap 布局
                    val rowsN = (cnt + cols - 1) / cols
                    Column(Modifier.weight(1f)) {
                        for (r in 0 until rowsN) {
                            Row {
                                for (c in 0 until cols) {
                                    val k = r * cols + c
                                    if (k >= cnt) break
                                    Box(Modifier.size(9.dp).padding(1.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(if (on) PAL[i] else PAL[i].copy(.62f)))
                                }
                            }
                        }
                    }
                    Text("${g.optInt("n", 0)}", fontSize = 9.sp, color = ZxT.Text3,
                         fontFamily = ZxT.Mono, modifier = Modifier.width(30.dp),
                         textAlign = TextAlign.End)
                }
            }
            Text("已有 waffle（华夫饼）是 10×10 的方形，" +
                 "这个用「象形单位」表达「1 格 = N 个」——" +
                 "统计数字变成看得见的个体，比百分比更有冲击力",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_dotstrip 单位点图 ══════════ */
    @Composable
    fun dotstrip(o: JSONObject, ctx: RenderContext) {
        val items = o.optJSONArray("items") ?: return
        val n = minOf(items.length(), 8)
        var sel by remember { mutableIntStateOf(-1) }
        val cnts = (0 until n).map { items.optJSONObject(it)?.optInt("n", 0) ?: 0 }
        val mx = cnts.maxOrNull()?.coerceAtLeast(1) ?: 1
        // 一列最多 10 个，超了就换行
        val perRow = 10

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "计数"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("最多 $mx", fontSize = 9.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(6.dp))
            for (i in 0 until n) {
                val it = items.optJSONObject(i) ?: continue
                val c = cnts[i]
                val on = sel == i
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (on) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        sel = i
                        ctx.callTool("zx_select", mapOf(
                            "item" to it.optString("n", ""),
                            "n" to c.toString(), "source" to "zx_dotstrip"))
                    }.padding(5.dp, 3.dp)) {
                    Text(it.optString("n", ""), fontSize = 9.sp,
                         color = if (on) ZxT.Text else ZxT.Text2,
                         modifier = Modifier.width(56.dp), maxLines = 1,
                         overflow = TextOverflow.Clip)
                    // 点阵：10 个一行
                    val rowsN = (c + perRow - 1) / perRow
                    Column(Modifier.weight(1f)) {
                        for (r in 0 until rowsN) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                for (k in 0 until perRow) {
                                    val idx = r * perRow + k
                                    if (idx >= c) break
                                    Box(Modifier.size(8.dp).padding(1.dp)
                                        .clip(CircleShape)
                                        .background(if (on) PAL[i % PAL.size]
                                                    else PAL[i % PAL.size].copy(.55f)))
                                }
                            }
                        }
                    }
                    Text("$c", fontSize = 10.sp, color = if (on) ZxT.Text else ZxT.Text3,
                         fontFamily = ZxT.Mono, modifier = Modifier.width(28.dp),
                         textAlign = TextAlign.End)
                }
            }
            Text("已有 bar（柱状）用长度表达数量，" +
                 "这个用「可数的点」——小数量差别（3 vs 4）" +
                 "用柱子几乎看不出，用点一眼就数得出来",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }
}
