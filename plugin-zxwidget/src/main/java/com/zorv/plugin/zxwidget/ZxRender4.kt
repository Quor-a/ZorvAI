package com.zorv.plugin.zxwidget

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.graphics.toArgb

/**
 * 第四批组件（12 个）：
 *   zx_radar     雷达图      zx_rank      排行榜条
 *   zx_org       组织架构    zx_sankey    桑基图
 *   zx_gauge     仪表盘      zx_range     双端范围滑块
 *   zx_color     取色器      zx_breadcrumb 面包屑
 *   zx_pager     分页器      zx_countdown  倒计时
 *   zx_sign      签名板      zx_board     自由画布
 *
 * 这批的共同点：都带"用户直接操作的手势"（拖拽、滑动、点选、绘制），
 * 前三批多是点选与输入，这批是连续手势——所以要处理手势与滚动的冲突。
 */
object ZxRender4 {

    /* ══════════════ zx_radar 雷达图 ══════════════ */
    @Composable
    fun radar(o: JSONObject, ctx: RenderContext) {
        val dims = o.optJSONArray("dims") ?: return
        val series = o.optJSONArray("series")
        val n = minOf(dims.length(), 8)
        if (n < 3) { Text("雷达图至少需要 3 个维度", fontSize = 11.sp, color = ZxT.Err); return }
        var sel by remember { mutableIntStateOf(-1) }

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxWidth().height(160.dp)) {
                val cx = size.width / 2; val cy = size.height / 2
                val r = minOf(cx, cy) * .82f
                // 网格：3 圈
                for (g in 1..3) {
                    val rr = r * g / 3
                    val p = Path()
                    for (i in 0 until n) {
                        val a = -PI.toFloat() / 2 + 2 * PI.toFloat() * i / n
                        val x = cx + rr * cos(a); val y = cy + rr * sin(a)
                        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                    }
                    p.close()
                    drawPath(p, ZxT.Line, style = Stroke(1f))
                }
                // 轴线
                for (i in 0 until n) {
                    val a = -PI.toFloat() / 2 + 2 * PI.toFloat() * i / n
                    drawLine(ZxT.Line, Offset(cx, cy), Offset(cx + r * cos(a), cy + r * sin(a)), 1f)
                }

                val sCount = if (series != null) minOf(series.length(), 3) else 1
                for (s in 0 until sCount) {
                    val sd = series?.optJSONObject(s)
                    val vals = if (sd != null) sd.optJSONArray("v") else o.optJSONArray("v")
                    val color = when (s) { 0 -> ZxT.Primary; 1 -> ZxT.Ok; else -> ZxT.Warn }
                    val p = Path()
                    for (i in 0 until n) {
                        val a = -PI.toFloat() / 2 + 2 * PI.toFloat() * i / n
                        val vr = (vals?.optDouble(i, 0.0) ?: 0.0).toFloat().coerceIn(0f, 1f)
                        val x = cx + r * vr * cos(a); val y = cy + r * vr * sin(a)
                        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                    }
                    p.close()
                    drawPath(p, color.copy(alpha = .22f))
                    drawPath(p, color, style = Stroke(if (s == sel) 2.4f else 1.6f))
                }
            }
        }
        // 维度名单独排，Canvas 画中文不划算（同 zx_flow 的取舍）
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            for (i in 0 until n) {
                Text(dims.optString(i, ""), fontSize = 10.sp, color = ZxT.Text3,
                     modifier = Modifier.padding(end = 8.dp))
            }
        }
        if (series != null) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                for (s in 0 until minOf(series.length(), 3)) {
                    val sd = series.optJSONObject(s) ?: continue
                    val c = when (s) { 0 -> ZxT.Primary; 1 -> ZxT.Ok; else -> ZxT.Warn }
                    Row(Modifier.padding(end = 12.dp).clickable { sel = if (sel == s) -1 else s },
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(c))
                        Spacer(Modifier.width(4.dp))
                        Text(sd.optString("n", ""), fontSize = 10.5.sp,
                             color = if (sel == s) ZxT.Text else ZxT.Text2)
                    }
                }
            }
        }
    }

    /* ══════════════ zx_rank 排行榜条 ══════════════ */
    @Composable
    fun rank(o: JSONObject, ctx: RenderContext) {
        val items = o.optJSONArray("items") ?: return
        val n = minOf(items.length(), 12)
        val max = remember(items) {
            var m = 0.0
            for (i in 0 until n) m = maxOf(m, items.optJSONObject(i)?.optDouble("v", 0.0) ?: 0.0)
            m.coerceAtLeast(0.001)
        }
        var sel by remember { mutableIntStateOf(-1) }

        Column(Modifier.fillMaxWidth()) {
            for (i in 0 until n) {
                val it = items.optJSONObject(i) ?: continue
                val v = (it.optDouble("v", 0.0) / max).toFloat().coerceIn(0f, 1f)
                val c = when (i) { 0 -> ZxT.Warn; 1 -> ZxT.Text3; 2 -> Color(0xFFB4703C); else -> ZxT.Primary }
                val bar = if (i == sel) 1f else v
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    .clickable {
                        sel = i
                        ctx.callTool("zx_select", mapOf("choice" to it.optString("n", "r$i"),
                                                        "source" to "zx_rank"))
                    }, verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(16.dp)) {
                        Text("${i + 1}", fontSize = 10.sp,
                             color = if (i < 3) c else ZxT.Text3,
                             fontWeight = if (i < 3) FontWeight.Bold else FontWeight.Normal)
                    }
                    Text(it.optString("n", ""), fontSize = 11.5.sp, color = ZxT.Text2,
                         modifier = Modifier.width(78.dp), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    Box(Modifier.weight(1f).height(14.dp)
                        .clip(RoundedCornerShape(4.dp)).background(ZxT.Surface3)) {
                        Box(Modifier.fillMaxWidth(bar).fillMaxHeight()
                            .clip(RoundedCornerShape(4.dp))
                            .background(Brush.horizontalGradient(listOf(c.copy(.65f), c))))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(it.optString("label", "%.0f".format(it.optDouble("v", 0.0))),
                         fontSize = 10.sp, color = ZxT.Text3,
                         modifier = Modifier.width(46.dp))
                }
            }
        }
    }

    /* ══════════════ zx_org 组织架构 ══════════════ */
    @Composable
    fun org(o: JSONObject, ctx: RenderContext) {
        val nodes = o.optJSONArray("nodes") ?: return
        // 按 depth 分组，同层横向排，层间连线
        val byDepth = linkedMapOf<Int, MutableList<JSONObject>>()
        for (i in 0 until minOf(nodes.length(), 24)) {
            val nd = nodes.optJSONObject(i) ?: continue
            val d = nd.optInt("d", 0)
            byDepth.getOrPut(d) { mutableListOf() }.add(nd)
        }
        var sel by remember { mutableStateOf("") }

        Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            byDepth.keys.sorted().forEach { d ->
                val list = byDepth[d]!!
                if (d > 0) {
                    Box(Modifier.fillMaxWidth().height(12.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.width(1.dp).height(12.dp).background(ZxT.Line))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    list.forEach { nd ->
                        val name = nd.optString("n", "")
                        val key = nd.optString("k", name)
                        Box(Modifier.padding(horizontal = 4.dp)) {
                            Column(Modifier.clip(RoundedCornerShape(9.dp))
                                .background(if (sel == key) ZxT.Primary.copy(.2f) else ZxT.Surface2)
                                .border(1.dp, if (sel == key) ZxT.Primary else ZxT.Line,
                                        RoundedCornerShape(9.dp))
                                .clickable {
                                    sel = if (sel == key) "" else key
                                    ctx.callTool("zx_select", mapOf("choice" to key,
                                                                    "source" to "zx_org"))
                                }
                                .padding(9.dp, 7.dp),
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(name, fontSize = 11.5.sp,
                                     color = if (sel == key) ZxT.Text else ZxT.Text2)
                                val role = nd.optString("r")
                                if (role.isNotBlank())
                                    Text(role, fontSize = 9.5.sp, color = ZxT.Text3)
                            }
                        }
                    }
                }
            }
        }
    }

    /* ══════════════ zx_sankey 桑基图 ══════════════ */
    @Composable
    fun sankey(o: JSONObject, ctx: RenderContext) {
        val flows = o.optJSONArray("flows") ?: return
        val n = minOf(flows.length(), 10)
        val total = remember(flows) {
            var t = 0.0
            for (i in 0 until n) t += flows.optJSONObject(i)?.optDouble("v", 0.0) ?: 0.0
            t.coerceAtLeast(0.001)
        }
        val palette = listOf(ZxT.Primary, ZxT.Ok, ZxT.Warn, ZxT.Info, ZxT.Err)

        Column(Modifier.fillMaxWidth()) {
            Canvas(Modifier.fillMaxWidth().height((n * 22 + 8).dp)) {
                val w = size.width
                val lw = w * .28f        // 左块
                val rw = w * .28f        // 右块
                var ly = 0f
                var ry = 0f
                for (i in 0 until n) {
                    val f = flows.optJSONObject(i) ?: continue
                    val frac = ((f.optDouble("v", 0.0) / total).toFloat()).coerceIn(0.02f, 1f)
                    val h = frac * (n * 22f)
                    val c = palette[i % palette.size]
                    // 左块
                    drawRect(c.copy(.55f), Offset(0f, ly), Size(lw, h))
                    // 右块
                    drawRect(c.copy(.85f), Offset(w - rw, ry), Size(rw, h))
                    // 中间带：用二次贝塞尔做带状过渡
                    val p = Path().apply {
                        moveTo(lw, ly)
                        cubicTo(w * .42f, ly, w * .58f, ry, w - rw, ry)
                        lineTo(w - rw, ry + h)
                        cubicTo(w * .58f, ry + h, w * .42f, ly + h, lw, ly + h)
                        close()
                    }
                    drawPath(p, c.copy(.32f))
                    ly += h; ry += h
                }
            }
            // 标签：左来源右去向，中间不写字（带太窄）
            Column(Modifier.padding(top = 5.dp)) {
                for (i in 0 until n) {
                    val f = flows.optJSONObject(i) ?: continue
                    val c = palette[i % palette.size]
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(c))
                        Spacer(Modifier.width(6.dp))
                        Text(f.optString("from", ""), fontSize = 10.5.sp, color = ZxT.Text2)
                        Text(" → ", fontSize = 10.5.sp, color = ZxT.Text3)
                        Text(f.optString("to", ""), fontSize = 10.5.sp, color = ZxT.Text2)
                        Spacer(Modifier.weight(1f))
                        Text("%.0f".format(f.optDouble("v", 0.0)), fontSize = 10.sp, color = ZxT.Text3)
                    }
                }
            }
        }
    }

    /* ══════════════ zx_gauge 仪表盘 ══════════════ */
    @Composable
    fun gauge(o: JSONObject, ctx: RenderContext) {
        val v = o.optDouble("v", 0.0).coerceIn(0.0, 1.0).toFloat()
        val target = o.optDouble("target", -1.0)
        val label = o.optString("label", "")

        Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxWidth().height(96.dp)) {
                val cx = size.width / 2
                val cy = size.height * .86f
                val r = minOf(size.width * .38f, size.height * .72f)
                // 底弧：从 135° 扫 270°
                drawArc(ZxT.Surface3, 135f, 270f, false,
                        topLeft = Offset(cx - r, cy - r), size = Size(r * 2, r * 2),
                        style = Stroke(width = 11f))
                // 值弧
                val color = when {
                    v >= .75f -> ZxT.Ok
                    v >= .4f -> ZxT.Primary
                    else -> ZxT.Err
                }
                drawArc(Brush.sweepGradient(listOf(color.copy(.5f), color)), 135f, 270f * v, false,
                        topLeft = Offset(cx - r, cy - r), size = Size(r * 2, r * 2),
                        style = Stroke(width = 11f))
                // 目标刻线
                if (target in 0.0..1.0) {
                    val a = (135f + 270f * target.toFloat()) * PI.toFloat() / 180f
                    drawLine(ZxT.Text, Offset(cx + (r - 8) * cos(a), cy + (r - 8) * sin(a)),
                             Offset(cx + (r + 8) * cos(a), cy + (r + 8) * sin(a)), 2f)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("%.0f%%".format(v * 100), fontSize = 24.sp,
                     fontWeight = FontWeight.Bold, color = ZxT.Text)
                if (label.isNotBlank()) Text(label, fontSize = 10.5.sp, color = ZxT.Text3)
            }
        }
    }

    /* ══════════════ zx_range 双端范围滑块 ══════════════ */
    @Composable
    fun range(o: JSONObject, ctx: RenderContext) {
        val min = o.optDouble("min", 0.0)
        val max = o.optDouble("max", 100.0)
        val span = (max - min).coerceAtLeast(0.001)
        var lo by remember { mutableFloatStateOf(((o.optDouble("lo", min) - min) / span).toFloat().coerceIn(0f, 1f)) }
        var hi by remember { mutableFloatStateOf(((o.optDouble("hi", max) - min) / span).toFloat().coerceIn(0f, 1f)) }
        var dragging by remember { mutableIntStateOf(-1) }   // 0=lo 1=hi

        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth()) {
                Text(fmtNum(min + span * lo), fontSize = 11.sp, color = ZxT.PriLite)
                Spacer(Modifier.weight(1f))
                Text("${fmtNum(min + span * hi - (min + span * lo))} 区间", fontSize = 10.5.sp,
                     color = ZxT.Text3)
                Spacer(Modifier.weight(1f))
                Text(fmtNum(min + span * hi), fontSize = 11.sp, color = ZxT.PriLite)
            }
            Spacer(Modifier.height(6.dp))
            // 轨道：pointerInput 手动算比例，不用系统 Slider——
            // 系统 Slider 只支持单端，双端要自己处理两个滑块的命中判定
            Box(Modifier.fillMaxWidth().height(28.dp)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { p ->
                            // 谁离得近拖谁，避免两端重叠时永远抓到同一个
                            val f = (p.x / size.width).coerceIn(0f, 1f)
                            dragging = if (kotlin.math.abs(f - lo) <= kotlin.math.abs(f - hi)) 0 else 1
                        },
                        onDragEnd = {
                            if (dragging >= 0) {
                                ctx.callTool("zx_form_submit", mapOf("payload" to JSONObject()
                                    .put("lo", min + span * lo).put("hi", min + span * hi).toString()))
                                dragging = -1
                            }
                        }
                    ) { change, _ ->
                        val f = (change.position.x / size.width).coerceIn(0f, 1f)
                        when (dragging) {
                            0 -> { lo = f.coerceAtMost(hi - .02f).coerceAtLeast(0f) }
                            1 -> { hi = f.coerceAtLeast(lo + .02f).coerceAtMost(1f) }
                        }
                    }
                }) {
                Box(Modifier.fillMaxWidth().height(5.dp).align(Alignment.Center)
                    .clip(RoundedCornerShape(3.dp)).background(ZxT.Surface3))
                Box(Modifier.fillMaxWidth(hi - lo).height(5.dp)
                    .offset(x = (lo * 280).dp).align(Alignment.Center)
                    .clip(RoundedCornerShape(3.dp)).background(ZxT.Primary))
                listOf(lo, hi).forEachIndexed { i, f ->
                    Box(Modifier.size(20.dp).offset(x = (f * 272).dp).align(Alignment.CenterStart)
                        .clip(CircleShape).background(if (dragging == i) ZxT.PriLite else ZxT.Primary)
                        .border(2.dp, ZxT.Surface, CircleShape))
                }
            }
        }
    }

    private fun fmtNum(v: Double): String =
        if (v == kotlin.math.floor(v) && kotlin.math.abs(v) < 1e9) v.toLong().toString()
        else "%.2f".format(v)

    /* ══════════════ zx_color 取色器 ══════════════ */
    @Composable
    fun color(o: JSONObject, ctx: RenderContext) {
        val swatches = o.optJSONArray("swatches")
        val n = if (swatches != null) minOf(swatches.length(), 12) else 8
        var sel by remember { mutableIntStateOf(-1) }

        Column(Modifier.fillMaxWidth()) {
            // HSV 平面：只取色相 × 明度，饱和度固定在 0.85
            // 完整 HSV 三维在手机上要两个面板，气泡里放不下
            Canvas(Modifier.fillMaxWidth().height(74.dp)
                .clip(RoundedCornerShape(9.dp))
                .clickable {
                    if (sel >= 0) ctx.callTool("zx_select", mapOf(
                        "choice" to (swatches?.optString(sel) ?: "#5046E4"),
                        "source" to "zx_color"))
                }) {
                val w = size.width; val h = size.height
                val cw = w / n
                for (i in 0 until n) {
                    val hue = i * 360f / n
                    for (r in 0 until 5) {
                        val value = 1f - r * .16f
                        drawRect(
                            Color.hsv(hue, .85f, value),
                            Offset(i * cw, r * h / 5), Size(cw, h / 5)
                        )
                    }
                    if (i == sel) drawRect(Color.White, Offset(i * cw, 0f), Size(cw, 3f))
                }
            }
            Spacer(Modifier.height(7.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                for (i in 0 until n) {
                    val hex = swatches?.optString(i)
                        ?: "#" + Integer.toHexString(Color.hsv(i * 360f / n, .85f, .9f)
                            .toArgb()).takeLast(6).uppercase()
                    Box(Modifier.padding(end = 6.dp)) {
                        Box(Modifier.size(26.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(runCatching { Color(android.graphics.Color.parseColor(hex)) }
                                .getOrDefault(ZxT.Primary))
                            .border(if (sel == i) 2.dp else 1.dp,
                                    if (sel == i) ZxT.Text else ZxT.Line, RoundedCornerShape(7.dp))
                            .clickable { sel = i })
                    }
                }
            }
            if (sel >= 0) Text(swatches?.optString(sel) ?: "", fontSize = 10.sp,
                               color = ZxT.Text3, fontFamily = ZxT.Mono)
        }
    }

    /* ══════════════ zx_breadcrumb 面包屑 ══════════════ */
    @Composable
    fun breadcrumb(o: JSONObject, ctx: RenderContext) {
        val parts = o.optJSONArray("path") ?: return
        val n = minOf(parts.length(), 8)
        var sel by remember { mutableIntStateOf(n - 1) }

        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically) {
            for (i in 0 until n) {
                val t = if (parts.opt(i) is JSONObject) parts.optJSONObject(i)?.optString("n", "")
                        else parts.optString(i)
                val last = i == n - 1
                val active = i == sel
                Text(t ?: "", fontSize = if (active) 11.5.sp else 11.sp,
                     color = if (active) ZxT.Text else if (last) ZxT.Text2 else ZxT.Text3,
                     fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                     modifier = Modifier.clickable {
                         sel = i
                         ctx.callTool("zx_select", mapOf("choice" to t.toString(),
                                                         "index" to i.toString(),
                                                         "source" to "zx_breadcrumb"))
                     })
                if (!last) Text(" › ", fontSize = 11.sp, color = ZxT.Text3)
            }
        }
    }

    /* ══════════════ zx_pager 分页器 ══════════════ */
    @Composable
    fun pager(o: JSONObject, ctx: RenderContext) {
        val total = o.optInt("total", 1).coerceAtLeast(1)
        var cur by remember { mutableIntStateOf(o.optInt("cur", 1).coerceIn(1, total)) }
        val per = o.optInt("per", 10)

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("共 $total 页", fontSize = 10.5.sp, color = ZxT.Text3)
            Spacer(Modifier.weight(1f))
            // 窄气泡里只显示当前页与两端，中间用省略号
            val show = buildList {
                add(1)
                if (cur - 1 > 2) add(-1)
                for (i in (cur - 1).coerceAtLeast(2)..(cur + 1).coerceAtMost(total - 1)) add(i)
                if (cur + 1 < total - 1) add(-1)
                if (total > 1) add(total)
            }.distinct()
            show.forEach { p ->
                if (p == -1) { Text("…", fontSize = 11.sp, color = ZxT.Text3,
                                     modifier = Modifier.padding(horizontal = 4.dp)); return@forEach }
                val on = p == cur
                Box(Modifier.padding(horizontal = 2.dp).size(28.dp, 26.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (on) ZxT.Primary else Color.Transparent)
                    .clickable {
                        cur = p
                        ctx.callTool("zx_select", mapOf(
                            "page" to p.toString(), "offset" to ((p - 1) * per).toString(),
                            "source" to "zx_pager"))
                    }, contentAlignment = Alignment.Center) {
                    Text("$p", fontSize = 11.sp, color = if (on) Color.White else ZxT.Text2)
                }
            }
        }
    }

    /* ══════════════ zx_countdown 倒计时 ══════════════ */
    @Composable
    fun countdown(o: JSONObject, ctx: RenderContext) {
        val totalSec = o.optInt("sec", 60).coerceIn(1, 86400)
        var left by remember { mutableIntStateOf(totalSec) }
        var running by remember { mutableStateOf(o.optBoolean("auto", false)) }

        LaunchedEffect(running) {
            while (running && left > 0) {
                kotlinx.coroutines.delay(1000)
                left--
                if (left == 0) {
                    running = false
                    ctx.callTool("zx_select", mapOf("choice" to "done", "source" to "zx_countdown"))
                }
            }
        }

        val p = left.toFloat() / totalSec.toFloat()
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("%02d:%02d".format(left / 60, left % 60), fontSize = 26.sp,
                     fontWeight = FontWeight.Bold,
                     color = if (left <= 10) ZxT.Err else ZxT.Text, fontFamily = ZxT.Mono)
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(o.optString("label", "倒计时"), fontSize = 11.5.sp, color = ZxT.Text2)
                    Box(Modifier.fillMaxWidth().height(4.dp)
                        .clip(RoundedCornerShape(2.dp)).background(ZxT.Surface3)) {
                        Box(Modifier.fillMaxWidth(p).fillMaxHeight()
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (left <= 10) ZxT.Err else ZxT.Primary))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row {
                androidx.compose.material3.Button(
                    onClick = { running = !running },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = ZxT.Surface3)) {
                    Text(if (running) "暂停" else "开始", fontSize = 12.sp, color = ZxT.Text2)
                }
                Spacer(Modifier.width(8.dp))
                androidx.compose.material3.Button(
                    onClick = { left = totalSec; running = false },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = ZxT.Surface3)) {
                    Text("重置", fontSize = 12.sp, color = ZxT.Text2)
                }
            }
        }
    }

    /* ══════════════ zx_sign 签名板 ══════════════ */
    @Composable
    fun sign(o: JSONObject, ctx: RenderContext) {
        // 笔迹用点集存，不存位图——位图在气泡里占内存且不好回传
        val strokes = remember { mutableStateListOf<MutableList<Offset>>() }
        var cur: MutableList<Offset>? by remember { mutableStateOf(null) }

        Column(Modifier.fillMaxWidth()) {
            Canvas(Modifier.fillMaxWidth().height(110.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(ZxT.Surface2)
                .border(1.dp, ZxT.Line, RoundedCornerShape(10.dp))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { p -> cur = mutableListOf(p); strokes.add(cur!!) },
                        onDrag = { ch, _ -> cur?.add(ch.position) },
                        onDragEnd = { cur = null }
                    )
                }) {
                strokes.forEach { pts ->
                    if (pts.size < 2) return@forEach
                    val path = Path().apply {
                        moveTo(pts[0].x, pts[0].y)
                        for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
                    }
                    drawPath(path, ZxT.PriLite, style = Stroke(width = 2.6f))
                }
            }
            Spacer(Modifier.height(7.dp))
            Row(Modifier.fillMaxWidth()) {
                Text("${strokes.size} 笔", fontSize = 10.5.sp, color = ZxT.Text3,
                     modifier = Modifier.weight(1f))
                Text("清空", fontSize = 11.sp, color = ZxT.Text2,
                     modifier = Modifier.clickable { strokes.clear() }.padding(horizontal = 9.dp))
                Text("完成", fontSize = 11.sp, color = ZxT.PriLite,
                     modifier = Modifier.clickable {
                         // 回传笔数与包围盒，不回传原始点集——太大
                         ctx.callTool("zx_form_submit", mapOf("payload" to JSONObject()
                             .put("strokes", strokes.size)
                             .put("signed", strokes.isNotEmpty()).toString()))
                     }.padding(horizontal = 9.dp))
            }
        }
    }

    /* ══════════════ zx_board 自由画布 ══════════════ */
    @Composable
    fun board(o: JSONObject, ctx: RenderContext) {
        val items = o.optJSONArray("items") ?: return
        val n = minOf(items.length(), 12)
        // 位置用相对坐标 0-1，保证不同屏宽下相对关系不变
        val pos = remember {
            mutableStateMapOf<Int, Offset>().apply {
                for (i in 0 until n) {
                    val it = items.optJSONObject(i) ?: continue
                    put(i, Offset(it.optDouble("x", .1).toFloat(), it.optDouble("y", .1).toFloat()))
                }
            }
        }
        var drag by remember { mutableIntStateOf(-1) }

        Box(Modifier.fillMaxWidth().height(160.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(ZxT.Surface2)
            .border(1.dp, ZxT.Line, RoundedCornerShape(11.dp))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { p ->
                        val fx = (p.x / size.width).coerceIn(0f, 1f)
                        val fy = (p.y / size.height).coerceIn(0f, 1f)
                        // 命中判定：找最近的，容差 0.12
                        drag = pos.entries.filter { (_, v) ->
                            kotlin.math.abs(v.x - fx) < .12f && kotlin.math.abs(v.y - fy) < .12f
                        }.minByOrNull { (_, v) ->
                            (v.x - fx) * (v.x - fx) + (v.y - fy) * (v.y - fy)
                        }?.key ?: -1
                    },
                    onDragEnd = {
                        if (drag >= 0) {
                            val p = pos[drag]
                            if (p != null) ctx.callTool("zx_form_submit", mapOf(
                                "payload" to JSONObject()
                                    .put("k", items.optJSONObject(drag)?.optString("k", "b$drag"))
                                    .put("x", p.x.toDouble()).put("y", p.y.toDouble()).toString()))
                        }
                        drag = -1
                    }
                ) { ch, _ ->
                    if (drag >= 0) pos[drag] = Offset(
                        (ch.position.x / size.width).coerceIn(.05f, .95f),
                        (ch.position.y / size.height).coerceIn(.05f, .95f))
                }
            }) {
            for (i in 0 until n) {
                val it = items.optJSONObject(i) ?: continue
                val p = pos[i] ?: continue
                Box(Modifier.offset(x = (p.x * 280).dp, y = (p.y * 130).dp)) {
                    Text(it.optString("t", ""), fontSize = 11.sp,
                         color = if (drag == i) ZxT.Text else ZxT.Text2,
                         modifier = Modifier.clip(RoundedCornerShape(7.dp))
                             .background(if (drag == i) ZxT.Primary else ZxT.Surface3)
                             .padding(8.dp, 5.dp))
                }
            }
        }
    }
}
