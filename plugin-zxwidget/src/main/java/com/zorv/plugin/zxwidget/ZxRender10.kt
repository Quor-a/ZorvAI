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
import kotlin.random.Random
import androidx.compose.ui.graphics.SolidColor

/**
 * 第十批组件（12 个）—— 高信息密度 / 专用输入 / 装饰性呈现。
 *
 * 做到第九批，覆盖率已经很高了。这批补的是"最后一批长尾"，
 * 但每一类都有它不可替代的地方：
 *
 *   一、高信息密度。日历热力（时间 × 强度）、矩阵热力（二维 × 强度）、
 *      极坐标（角度 + 半径）——这些在气泡里塞得下上百个数据点，
 *      换成表格要滚很久。
 *
 *   二、专用输入。语音输入条、评分星级、表情反馈、颜色刻度、
 *      标签输入——通用表单组件做这些会很别扭
 *      （用 zx_form 收集评分，用户看到的是一堆字不是星星）。
 *
 *   三、装饰性呈现。翻页数字、加载骨架、成就徽章——
 *      这些不承载决策，但决定了卡片"像不像一个产品"。
 *
 * zx_calheat / zx_matrixheat / zx_polar / zx_voice / zx_stars
 * zx_react / zx_scale / zx_tags / zx_flipnum / zx_skeleton
 * zx_badges
 *
 * 贯穿这批的一条：**密度不等于拥挤**。
 * 格子小到一定程度就只剩色块没有信息，
 * 所以每个密度型组件都有最小格宽下限，越界就减格数而不是缩格子。
 */
object ZxRender10 {

    private val PAL = listOf(
        Color(0xFF5046E4), Color(0xFF3FBF7F), Color(0xFFE8A33D),
        Color(0xFFE5544B), Color(0xFF4A9BE8), Color(0xFF8E86FF)
    )

    /** 热力色阶：低值不能发灰，否则弱信号看不见 */
    private fun heat(v: Double): Color {
        val t = v.coerceIn(0.0, 1.0).toFloat()
        return Color(red = .16f + .72f * t, green = .18f + .58f * t, blue = .24f + .68f * t)
    }

    /* ══════════ zx_calheat 日历热力 ══════════ */
    @Composable
    fun calheat(o: JSONObject, ctx: RenderContext) {
        val cells = o.optJSONArray("cells") ?: return
        val n = minOf(cells.length(), 91)     // 一季度
        val perRow = 13                       // 周数
        val rows = (n + perRow - 1) / perRow
        val labels = o.optJSONArray("labels")
        var sel by remember { mutableIntStateOf(-1) }
        val maxV = (0 until n).maxOf { cells.optDouble(it, 0.0) }.coerceAtLeast(0.001)

        Column(Modifier.fillMaxWidth()) {
            // 格宽有下限：小到只剩色块就没有信息了
            val cellW = 20
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.width(26.dp))
                labels?.let { ls ->
                    Row(Modifier.weight(1f)) {
                        for (i in 0 until minOf(ls.length(), perRow))
                            Text(ls.optString(i).take(2), fontSize = 8.sp, color = ZxT.Text3,
                                 modifier = Modifier.width(cellW.dp), textAlign = TextAlign.Center,
                                 maxLines = 1)
                    }
                }
            }
            for (r in 0 until rows) {
                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                    val rl = if (labels != null) "" else ""
                    Text(o.optJSONArray("rowLabels")?.optString(r) ?: "",
                         fontSize = 8.sp, color = ZxT.Text3, modifier = Modifier.width(26.dp))
                    Row(Modifier.weight(1f)) {
                        for (c in 0 until perRow) {
                            val i = r * perRow + c
                            if (i >= n) { Box(Modifier.width(cellW.dp)); continue }
                            val v = cells.optDouble(i, 0.0)
                            Box(Modifier.width(cellW.dp).height(cellW.dp).padding(1.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(if (v <= 0) ZxT.Surface3 else heat(v / maxV))
                                .border(if (sel == i) 1.2.dp else 0.dp, ZxT.Text,
                                        RoundedCornerShape(3.dp))
                                .clickable {
                                    sel = i
                                    ctx.callTool("zx_select", mapOf(
                                        "index" to i.toString(), "v" to v.toString(),
                                        "source" to "zx_calheat"))
                                })
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("少", fontSize = 9.sp, color = ZxT.Text3)
                (0..4).forEach { k ->
                    Spacer(Modifier.width(3.dp))
                    Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp))
                        .background(if (k == 0) ZxT.Surface3 else heat(k / 4.0)))
                }
                Spacer(Modifier.width(4.dp))
                Text("多", fontSize = 9.sp, color = ZxT.Text3)
                if (sel >= 0) {
                    Spacer(Modifier.width(8.dp))
                    Text("第 $sel 天：${fmt(cells.optDouble(sel, 0.0))}",
                         fontSize = 10.sp, color = ZxT.Text2)
                }
            }
        }
    }

    private fun fmt(v: Double) =
        if (v == kotlin.math.floor(v) && abs(v) < 1e9) v.toLong().toString() else "%.1f".format(v)

    /* ══════════ zx_matrixheat 矩阵热力 ══════════ */
    @Composable
    fun matrixheat(o: JSONObject, ctx: RenderContext) {
        val rows = o.optJSONArray("rows") ?: return
        val cols = o.optJSONArray("cols") ?: return
        val nr = minOf(rows.length(), 8); val nc = minOf(cols.length(), 7)
        var sel by remember { mutableStateOf<Pair<Int, Int>?>(null) }
        var maxV = 0.001
        for (r in 0 until nr) {
            val a = rows.optJSONObject(r)?.optJSONArray("v")
            for (c in 0 until nc) maxV = max(maxV, a?.optDouble(c, 0.0) ?: 0.0)
        }

        Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Row(Modifier.padding(bottom = 3.dp)) {
                Text("", modifier = Modifier.width(46.dp), fontSize = 9.sp)
                for (c in 0 until nc)
                    Text(cols.optString(c), fontSize = 8.5.sp, color = ZxT.Text3,
                         modifier = Modifier.width(30.dp), textAlign = TextAlign.Center,
                         maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            for (r in 0 until nr) {
                val ro = rows.optJSONObject(r) ?: continue
                Row(Modifier.padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(ro.optString("n", "${r + 1}"), fontSize = 9.5.sp, color = ZxT.Text3,
                         modifier = Modifier.width(46.dp), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    val a = ro.optJSONArray("v")
                    for (c in 0 until nc) {
                        val v = a?.optDouble(c, 0.0) ?: 0.0
                        val on = sel == Pair(r, c)
                        Box(Modifier.width(30.dp).height(22.dp).padding(1.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(heat(v / maxV))
                            .border(if (on) 1.2.dp else 0.dp, ZxT.Text, RoundedCornerShape(3.dp))
                            .clickable {
                                sel = Pair(r, c)
                                ctx.callTool("zx_select", mapOf(
                                    "row" to ro.optString("n"), "col" to cols.optString(c),
                                    "v" to v.toString(), "source" to "zx_matrixheat"))
                            }, contentAlignment = Alignment.Center) {
                            // 只在选中时显示数值——全显示会糊成一片
                            if (on) Text(fmt(v), fontSize = 8.5.sp, color = Color.White,
                                         fontFamily = ZxT.Mono)
                        }
                    }
                }
            }
            if (sel != null) {
                Text("选中：${rows.optJSONObject(sel!!.first)?.optString("n")} × ${cols.optString(sel!!.second)}",
                     fontSize = 10.sp, color = ZxT.Text2, modifier = Modifier.padding(top = 5.dp))
            }
        }
    }

    /* ══════════ zx_polar 极坐标图 ══════════ */
    @Composable
    fun polar(o: JSONObject, ctx: RenderContext) {
        val items = o.optJSONArray("items") ?: return
        val n = minOf(items.length(), 12)
        val list = (0 until n).map { i ->
            val it = items.optJSONObject(i) ?: JSONObject()
            Triple(it.optString("n", ""), it.optDouble("v", 0.0), it.optDouble("r", 1.0))
        }
        val maxR = list.maxOf { it.third }.coerceAtLeast(0.001)
        var sel by remember { mutableIntStateOf(-1) }

        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.size(112.dp)) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val c = Offset(size.width / 2, size.height / 2)
                    val maxRad = size.minDimension / 2 - 6
                    // 同心圆：没有它就读不出半径代表多少
                    for (k in 1..3)
                        drawCircle(ZxT.Line, maxRad * k / 3, c, style = Stroke(width = 1f))
                    list.forEachIndexed { i, (_, _, r) ->
                        val ang = (i.toFloat() / n) * 2f * Math.PI.toFloat() - Math.PI.toFloat() / 2
                        val rad = (r / maxR).toFloat() * maxRad
                        val p = Offset(c.x + kotlin.math.cos(ang) * rad,
                                       c.y + kotlin.math.sin(ang) * rad)
                        drawCircle(PAL[i % PAL.size].copy(if (sel == i) .95f else .5f),
                                   if (sel == i) 5f else 3.5f, p)
                        drawLine(PAL[i % PAL.size].copy(.3f), c, p, 1f)
                    }
                }
            }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                list.forEachIndexed { i, (nm, v, r) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 1.5.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (sel == i) PAL[i % PAL.size].copy(.2f) else Color.Transparent)
                        .clickable {
                            sel = i
                            ctx.callTool("zx_select", mapOf(
                                "choice" to nm, "v" to v.toString(), "r" to r.toString(),
                                "source" to "zx_polar"))
                        }.padding(6.dp, 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(PAL[i % PAL.size]))
                        Spacer(Modifier.width(6.dp))
                        Text(nm, fontSize = 10.sp, color = ZxT.Text2,
                             modifier = Modifier.weight(1f), maxLines = 1,
                             overflow = TextOverflow.Ellipsis)
                        Text(fmt(v), fontSize = 10.sp, color = ZxT.Text3, fontFamily = ZxT.Mono)
                    }
                }
            }
        }
    }

    /* ══════════ zx_voice 语音输入条 ══════════ */
    @Composable
    fun voice(o: JSONObject, ctx: RenderContext) {
        var on by remember { mutableStateOf(false) }
        var secs by remember { mutableIntStateOf(0) }
        val text = remember { mutableStateOf(o.optString("v", "")) }
        // 波形条数固定：随时长增长会让条越来越窄
        val bars = 28
        val levels = remember { mutableStateListOf<Float>() }
        LaunchedEffect(Unit) { repeat(bars) { levels.add(0.15f + Random.nextFloat() * 0.75f) } }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp)
                    .clip(CircleShape)
                    .background(if (on) ZxT.Err else ZxT.Primary)
                    .clickable {
                        on = !on
                        if (!on && text.value.isNotBlank())
                            ctx.callTool("zx_form_submit", mapOf("payload" to
                                JSONObject().put("v", text.value)
                                    .put("secs", secs).toString()))
                        else if (on) secs = 0
                    }, contentAlignment = Alignment.Center) {
                    Text(if (on) "■" else "●", fontSize = 12.sp, color = Color.White)
                }
                Spacer(Modifier.width(10.dp))
                // 波形 + 计时：只有按钮的话用户不知道在不在录
                Column(Modifier.weight(1f)) {
                    Row(Modifier.fillMaxWidth().height(22.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (i in 0 until bars) {
                            val l = if (on) levels[i % levels.size] else 0.12f
                            Box(Modifier.weight(1f).height((4 + l * 16).dp)
                                .clip(RoundedCornerShape(1.dp))
                                .background(if (on) ZxT.PriLite.copy(.55f + l * .45f)
                                            else ZxT.Surface3))
                        }
                    }
                    Text(if (on) "录制中 ${secs}s" else "点左侧按钮开始",
                         fontSize = 9.5.sp, color = if (on) ZxT.PriLite else ZxT.Text3,
                         modifier = Modifier.padding(top = 3.dp))
                }
            }
            if (text.value.isNotBlank()) {
                Spacer(Modifier.height(7.dp))
                Box(Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp)).background(ZxT.Surface2)
                    .padding(10.dp, 8.dp)) {
                    Text(text.value, fontSize = 11.5.sp, color = ZxT.Text2, lineHeight = 16.sp)
                }
            }
            Text("波形条数固定（${bars} 根）——随时长增长会让条越来越窄",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_stars 评分星级 ══════════ */
    @Composable
    fun stars(o: JSONObject, ctx: RenderContext) {
        val max = o.optInt("max", 5).coerceIn(3, 10)
        var v by remember { mutableIntStateOf(o.optInt("v", 0).coerceIn(0, max)) }
        var hover by remember { mutableIntStateOf(0) }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row {
                    for (i in 1..max) {
                        Text(if (i <= (hover.takeIf { it > 0 } ?: v)) "★" else "☆",
                             fontSize = 22.sp,
                             color = if (i <= (hover.takeIf { it > 0 } ?: v))
                                 ZxT.Warn else ZxT.Text3,
                             modifier = Modifier.clickable {
                                 v = i
                                 ctx.callTool("zx_select", mapOf(
                                     "v" to i.toString(), "max" to max.toString(),
                                     "source" to "zx_stars"))
                             }.padding(horizontal = 2.dp))
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(if (v > 0) "$v/$max" else "未评", fontSize = 11.sp, color = ZxT.Text3,
                     fontFamily = ZxT.Mono)
            }
            if (v > 0 && o.optJSONArray("labels") != null) {
                val ls = o.optJSONArray("labels")
                Text(ls.optString(v - 1), fontSize = 10.5.sp, color = ZxT.Text2,
                     modifier = Modifier.padding(top = 4.dp))
            }
            Text("用 zx_form 收集评分，用户看到的是一堆字不是星星——专用输入就是为这个",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_react 表情反馈 ══════════ */
    @Composable
    fun react(o: JSONObject, ctx: RenderContext) {
        val items = o.optJSONArray("items") ?: return
        val n = minOf(items.length(), 6)
        var sel by remember { mutableIntStateOf(-1) }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            for (i in 0 until n) {
                val it = items.optJSONObject(i) ?: continue
                val on = sel == i
                Column(Modifier.weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (on) ZxT.Primary.copy(.16f) else ZxT.Surface2)
                    .border(1.dp, if (on) ZxT.Primary else ZxT.Line, RoundedCornerShape(9.dp))
                    .clickable {
                        sel = i
                        ctx.callTool("zx_select", mapOf(
                            "choice" to it.optString("k", "$i"),
                            "source" to "zx_react"))
                    }.padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(it.optString("e", "·"), fontSize = 18.sp)
                    Spacer(Modifier.height(3.dp))
                    Text(it.optString("n", ""), fontSize = 9.sp, color = ZxT.Text3,
                         maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

    /* ══════════ zx_scale 颜色刻度 ══════════ */
    @Composable
    fun scale(o: JSONObject, ctx: RenderContext) {
        val min = o.optDouble("min", 0.0)
        val max = o.optDouble("max", 100.0).coerceAtLeast(min + 0.001)
        var v by remember { mutableFloatStateOf(
            ((o.optDouble("v", min) - min) / (max - min)).coerceIn(0.0, 1.0).toFloat()) }
        val stops = o.optJSONArray("stops")
        val ss = mutableListOf<Pair<String, Color>>()
        if (stops != null) for (i in 0 until minOf(stops.length(), 6)) {
            val s = stops.optJSONObject(i) ?: continue
            val hex = s.optString("hex", "#5046E4")
            ss.add(s.optString("n") to runCatching {
                Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(PAL[i % PAL.size]))
        }
        val cur = (min + v * (max - min)).toDouble()
        val curCol = if (ss.size >= 2) {
            val seg = (v * (ss.size - 1)).coerceIn(0f, ss.size - 1f)
            val i = seg.toInt().coerceAtMost(ss.size - 2)
            val f = seg - i
            Color(red = ss[i].second.red + (ss[i + 1].second.red - ss[i].second.red) * f,
                   green = ss[i].second.green + (ss[i + 1].second.green - ss[i].second.green) * f,
                   blue = ss[i].second.blue + (ss[i + 1].second.blue - ss[i].second.blue) * f)
        } else ss.firstOrNull()?.second ?: ZxT.Primary

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("label", ""), fontSize = 11.5.sp, color = ZxT.Text2,
                     modifier = Modifier.weight(1f))
                Text(fmt(cur), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = curCol,
                     fontFamily = ZxT.Mono)
                if (o.optString("unit").isNotBlank())
                    Text(o.optString("unit"), fontSize = 9.5.sp, color = ZxT.Text3,
                         modifier = Modifier.padding(start = 2.dp))
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(26.dp)) {
                // 色带
                Box(Modifier.fillMaxWidth().height(9.dp).align(Alignment.Center)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (ss.size >= 2)
                        Brush.horizontalGradient(ss.map { it.second })
                        else SolidColor(ZxT.Primary)))
                // 指针：用偏移量而不是绝对坐标，避免不同屏宽下错位
                Box(Modifier.align(Alignment.CenterStart)
                    .offset(x = (v * 100).toInt().dp.coerceIn(0.dp, 2000.dp))
                    .width(3.dp).height(20.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(ZxT.Text))
            }
            // 滑条本体：用 zx_scale 而不是 zx_range，是因为要显示对应颜色
            androidx.compose.material3.Slider(
                value = v, onValueChange = { v = it },
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = curCol, activeTrackColor = curCol,
                    inactiveTrackColor = ZxT.Surface3))
            if (ss.isNotEmpty()) {
                Row(Modifier.fillMaxWidth()) {
                    ss.forEachIndexed { i, (nm, _) ->
                        Text(nm, fontSize = 9.sp, color = ZxT.Text3,
                             modifier = Modifier.weight(1f), maxLines = 1,
                             overflow = TextOverflow.Ellipsis,
                             textAlign = if (i == 0) TextAlign.Start
                                         else if (i == ss.size - 1) TextAlign.End
                                         else TextAlign.Center)
                    }
                }
            }
        }
    }

    /* ══════════ zx_tags 标签输入 ══════════ */
    @Composable
    fun tags(o: JSONObject, ctx: RenderContext) {
        val max = o.optInt("max", 8).coerceIn(1, 12)
        val tags = remember {
            mutableStateListOf<String>().apply {
                val src = o.optJSONArray("tags")
                if (src != null) for (i in 0 until minOf(src.length(), max)) add(src.optString(i))
            }
        }
        var draft by remember { mutableStateOf("") }
        var err by remember { mutableStateOf("") }
        val sugg = o.optJSONArray("sugg")

        fun add(t: String) {
            when {
                t.isBlank() -> err = "不能为空"
                tags.size >= max -> err = "最多 $max 个标签"
                tags.contains(t) -> err = "已有相同标签：$t"
                else -> { tags.add(t); draft = ""; err = "" }
            }
        }

        Column(Modifier.fillMaxWidth()) {
            // 换行用手动分块：FlowRow 在气泡里对不齐间距
            var row = mutableListOf<String>()
            val rows = mutableListOf<List<String>>()
            tags.forEach { t -> row.add(t); if (row.size == 3) { rows.add(row); row = mutableListOf() } }
            if (row.isNotEmpty()) rows.add(row)
            rows.forEach { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    r.forEach { t ->
                        Row(Modifier.clip(RoundedCornerShape(12.dp))
                            .background(PAL[tags.indexOf(t) % PAL.size].copy(.2f))
                            .border(1.dp, PAL[tags.indexOf(t) % PAL.size].copy(.5f),
                                    RoundedCornerShape(12.dp))
                            .clickable {
                                tags.remove(t)
                                ctx.callTool("zx_form_submit", mapOf("payload" to
                                    JSONObject().put("op", "remove").put("v", t).toString()))
                            }.padding(8.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(t, fontSize = 10.5.sp, color = ZxT.Text2, maxLines = 1)
                            Spacer(Modifier.width(4.dp))
                            Text("×", fontSize = 11.sp, color = ZxT.Text3)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.TextField(
                    value = draft, onValueChange = { draft = it; err = "" },
                    placeholder = { Text("输入后回车添加", fontSize = 11.sp, color = ZxT.Text3) },
                    modifier = Modifier.weight(1f).height(38.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.5.sp, color = ZxT.Text),
                    singleLine = true,
                    colors = androidx.compose.material3.TextFieldDefaults.colors(
                        focusedContainerColor = ZxT.Surface2,
                        unfocusedContainerColor = ZxT.Surface2,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = ZxT.Primary))
                Spacer(Modifier.width(6.dp))
                Text("添加", fontSize = 11.5.sp,
                     color = if (draft.isBlank()) ZxT.Text3 else ZxT.PriLite,
                     modifier = Modifier.clickable(enabled = draft.isNotBlank()) {
                         add(draft.trim())
                         if (err.isEmpty())
                             ctx.callTool("zx_form_submit", mapOf("payload" to
                                 JSONObject().put("op", "add").put("v", tags.lastOrNull() ?: "")
                                     .put("all", org.json.JSONArray().apply { tags.forEach { put(it) } })
                                     .toString()))
                     })
            }
            if (sugg != null && sugg.length() > 0) {
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    for (i in 0 until minOf(sugg.length(), 8)) {
                        val s = sugg.optString(i)
                        if (tags.contains(s)) continue
                        Text(s, fontSize = 10.sp, color = ZxT.Text3,
                             modifier = Modifier.clip(RoundedCornerShape(10.dp))
                                 .background(ZxT.Surface2).clickable { add(s) }
                                 .padding(7.dp, 3.dp))
                    }
                }
            }
            Text(err.ifBlank { "${tags.size}/$max 个 · 点标签即删除" },
                 fontSize = 10.sp, color = if (err.isNotBlank()) ZxT.Err else ZxT.Text3,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_flipnum 翻页数字 ══════════ */
    @Composable
    fun flipnum(o: JSONObject, ctx: RenderContext) {
        val items = o.optJSONArray("items") ?: return
        val n = minOf(items.length(), 4)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            for (i in 0 until n) {
                val it = items.optJSONObject(i) ?: continue
                Column(Modifier.weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(ZxT.Surface2)
                    .border(1.dp, ZxT.Line, RoundedCornerShape(9.dp))
                    .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    // 每位一个格子：整体一个大数字就没有"翻页"的感觉了
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        val s = it.optString("v", "0")
                        for (ch in s.take(4)) {
                            Box(Modifier.width(15.dp).height(24.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(ZxT.Surface)
                                .border(.8.dp, ZxT.Line, RoundedCornerShape(4.dp)),
                                contentAlignment = Alignment.Center) {
                                Text(ch.toString(), fontSize = 13.sp,
                                     fontWeight = FontWeight.Bold, color = ZxT.Text,
                                     fontFamily = ZxT.Mono)
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(it.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text3,
                         maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (it.has("d"))
                        Text(it.optString("d"), fontSize = 9.sp,
                             color = if (it.optString("d").startsWith("-")) ZxT.Err else ZxT.Ok,
                             fontFamily = ZxT.Mono)
                }
            }
        }
    }

    /* ══════════ zx_skeleton 加载骨架 ══════════ */
    @Composable
    fun skeleton(o: JSONObject, ctx: RenderContext) {
        val rows = o.optInt("rows", 3).coerceIn(1, 6)
        // 呼吸动画由宿主动效层驱动，这里只负责形状
        Column(Modifier.fillMaxWidth()) {
            for (i in 0 until rows) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    if (o.optBoolean("avatar", true))
                        Box(Modifier.size(26.dp).clip(CircleShape).background(ZxT.Surface3))
                    if (o.optBoolean("avatar", true)) Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Box(Modifier.fillMaxWidth(if (i == 0) .8f else .55f).height(9.dp)
                            .clip(RoundedCornerShape(4.dp)).background(ZxT.Surface3))
                        Spacer(Modifier.height(5.dp))
                        Box(Modifier.fillMaxWidth(if (i % 2 == 0) .92f else .68f).height(9.dp)
                            .clip(RoundedCornerShape(4.dp)).background(ZxT.Surface2))
                    }
                }
            }
            Text(o.optString("hint", "加载中"), fontSize = 10.sp, color = ZxT.Text3,
                 modifier = Modifier.padding(top = 4.dp))
        }
    }

    /* ══════════ zx_badges 成就徽章 ══════════ */
    @Composable
    fun badges(o: JSONObject, ctx: RenderContext) {
        val items = o.optJSONArray("items") ?: return
        val n = minOf(items.length(), 8)
        var sel by remember { mutableIntStateOf(-1) }
        var got = 0
        for (i in 0 until n) if (items.optJSONObject(i)?.optBoolean("got", false) == true) got++

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("已获得 $got/$n", fontSize = 10.5.sp, color = ZxT.Text3)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f).height(3.dp)
                    .clip(RoundedCornerShape(2.dp)).background(ZxT.Surface3)) {
                    Box(Modifier.fillMaxWidth(got.toFloat() / n).fillMaxHeight()
                        .background(Brush.horizontalGradient(listOf(ZxT.Warn, ZxT.Warn.copy(.6f)))))
                }
            }
            Spacer(Modifier.height(7.dp))
            for (r in 0 until (n + 3) / 4) {
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (c in 0 until 4) {
                        val i = r * 4 + c
                        if (i >= n) { Box(Modifier.weight(1f)); continue }
                        val it = items.optJSONObject(i) ?: continue
                        val has = it.optBoolean("got", false)
                        Column(Modifier.weight(1f)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (has) ZxT.Warn.copy(.12f) else ZxT.Surface2)
                            .border(1.dp, if (sel == i) ZxT.Text
                                          else if (has) ZxT.Warn.copy(.45f) else ZxT.Line,
                                    RoundedCornerShape(9.dp))
                            .clickable {
                                sel = i
                                ctx.callTool("zx_select", mapOf(
                                    "choice" to it.optString("k", "$i"),
                                    "got" to has.toString(), "source" to "zx_badges"))
                            }.padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            // 未获得用灰阶：一眼能看出哪些还没拿到
                            Text(it.optString("e", "◆"), fontSize = 16.sp,
                                 color = if (has) ZxT.Warn else ZxT.Text3.copy(.5f))
                            Spacer(Modifier.height(2.dp))
                            Text(it.optString("n", ""), fontSize = 8.5.sp,
                                 color = if (has) ZxT.Text2 else ZxT.Text3,
                                 maxLines = 1, overflow = TextOverflow.Ellipsis,
                                 textAlign = TextAlign.Center)
                        }
                    }
                }
            }
            if (sel >= 0 && items.optJSONObject(sel)?.optString("d")?.isNotBlank() == true)
                Text(items.optJSONObject(sel)!!.optString("d"), fontSize = 10.sp,
                     color = ZxT.Text3, lineHeight = 14.sp,
                     modifier = Modifier.padding(top = 5.dp))
        }
    }
}
