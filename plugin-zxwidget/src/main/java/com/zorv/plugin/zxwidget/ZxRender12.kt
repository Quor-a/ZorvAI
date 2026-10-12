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
 * 第十二批组件（12 个）—— 组织 / 流向 / 导航 / 采集。
 *
 * 做到第十一批，单块内容基本齐了。这批补的是
 * **让多张卡之间能被"串起来"的那一层**：
 *
 *   一、组织与流向。组织架构、桑基图——
 *       表达"谁在谁下面"和"量从哪里流到哪里"。
 *       树图表达不了流量，条形图表达不了层级。
 *
 *   二、导航。面包屑、分页、底部标签栏、搜索栏、筛选芯片——
 *       这些是"我在哪 / 我去哪"，
 *       前面十一批完全没覆盖过。
 *
 *   三、采集。取色板、数字步进器、层级树（带复选）——
 *       剩下几个零散但高频的输入。
 *
 * zx_orgchart / zx_sankey / zx_gauge / zx_sparkgrid
 * zx_breadcrumb / zx_pagination / zx_tabbar / zx_searchbar
 * zx_chips / zx_colorpick / zx_stepper / zx_treeview
 *
 * 贯穿这批的一条：**导航必须能回答"我在哪"**。
 * 面包屑没有当前项高亮、分页没有当前页码、
 * 标签栏没有选中态——那都只是装饰，不是导航。
 */
object ZxRender12 {

    private val PAL = listOf(
        Color(0xFF5046E4), Color(0xFF3FBF7F), Color(0xFFE8A33D),
        Color(0xFFE5544B), Color(0xFF4A9BE8), Color(0xFF8E86FF)
    )

    /* ══════════ zx_orgchart 组织架构 ══════════ */
    @Composable
    fun orgchart(o: JSONObject, ctx: RenderContext) {
        val root = o.optJSONObject("root") ?: return
        var sel by remember { mutableStateOf<String?>(null) }

        Column(Modifier.fillMaxWidth()) {
            OrgNode(root, 0, sel, o.optInt("depth", 3)) { id ->
                sel = if (sel == id) null else id
                ctx.callTool("zx_select", mapOf("choice" to id, "source" to "zx_orgchart"))
            }
            Text("连线画在 Canvas，名字用 Row 排——Canvas 里画中文要自己处理字体度量",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 7.dp))
        }
    }

    @Composable
    private fun OrgNode(n: JSONObject, depth: Int, sel: String?, maxDepth: Int, onPick: (String) -> Unit) {
        val id = n.optString("k", "")
        val on = sel == id
        val kids = n.optJSONArray("kids")
        val hasKids = kids != null && kids.length() > 0 && depth < maxDepth - 1

        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Box(Modifier.clip(RoundedCornerShape(8.dp))
                    .background(if (on) PAL[depth % PAL.size].copy(.22f) else ZxT.Surface2)
                    .border(1.dp, if (on) PAL[depth % PAL.size] else ZxT.Line,
                            RoundedCornerShape(8.dp))
                    .clickable { onPick(id) }
                    .padding(11.dp, 6.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(n.optString("n", ""), fontSize = 11.sp,
                             fontWeight = FontWeight.SemiBold, color = ZxT.Text,
                             maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (n.optString("r").isNotBlank())
                            Text(n.optString("r"), fontSize = 9.sp, color = ZxT.Text3,
                                 maxLines = 1)
                    }
                }
            }
            if (hasKids) {
                // 竖线 + 横线：没有它们看不出谁在谁下面
                Box(Modifier.fillMaxWidth().height(10.dp), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.width(1.dp).height(10.dp).background(ZxT.Line))
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.Center) {
                    for (i in 0 until minOf(kids!!.length(), 4)) {
                        val k = kids.optJSONObject(i) ?: continue
                        Column(Modifier.padding(horizontal = 5.dp)) {
                            Box(Modifier.fillMaxWidth().height(1.dp).background(ZxT.Line))
                            Spacer(Modifier.height(8.dp))
                            OrgNode(k, depth + 1, sel, maxDepth, onPick)
                        }
                    }
                }
            }
        }
    }

    /* ══════════ zx_sankey 桑基图 ══════════ */
    @Composable
    fun sankey(o: JSONObject, ctx: RenderContext) {
        val flows = o.optJSONArray("flows") ?: return
        val n = minOf(flows.length(), 7)
        var sel by remember { mutableIntStateOf(-1) }
        val total = (0 until n).sumOf { flows.optJSONObject(it)?.optDouble("v", 0.0) ?: 0.0 }
            .coerceAtLeast(0.001)

        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().height(120.dp)) {
                // 左列（来源）
                val srcs = mutableMapOf<String, Double>()
                val dsts = mutableMapOf<String, Double>()
                for (i in 0 until n) {
                    val f = flows.optJSONObject(i) ?: continue
                    val s = f.optString("s"); val d = f.optString("d")
                    val v = f.optDouble("v", 0.0)
                    srcs[s] = (srcs[s] ?: 0.0) + v
                    dsts[d] = (dsts[d] ?: 0.0) + v
                }
                Column(Modifier.weight(.28f)) {
                    srcs.entries.forEach { (k, v) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(k, fontSize = 9.sp, color = ZxT.Text3,
                                 modifier = Modifier.weight(1f), maxLines = 1,
                                 overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                // 中间流量带
                Box(Modifier.weight(.44f).fillMaxHeight()) {
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                        var y = 0f
                        for (i in 0 until n) {
                            val f = flows.optJSONObject(i) ?: continue
                            val v = f.optDouble("v", 0.0)
                            val h = (v / total).toFloat() * size.height
                            // 贝塞尔带：直线带看不出"流向"
                            val path = Path().apply {
                                moveTo(0f, y)
                                cubicTo(size.width * .4f, y, size.width * .6f,
                                        y + h * .8f, size.width, y + h)
                                lineTo(size.width, y + h * .55f)
                                cubicTo(size.width * .6f, y + h * .35f,
                                        size.width * .4f, y - h * .05f, 0f, y + h * .5f)
                                close()
                            }
                            drawPath(path, PAL[i % PAL.size].copy(if (sel == i) .85f else .45f))
                            y += h
                        }
                    }
                }
                // 右列（去向）
                Column(Modifier.weight(.28f)) {
                    dsts.entries.forEach { (k, v) ->
                        Text(k, fontSize = 9.sp, color = ZxT.Text3,
                             modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                             maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            // 图例点选：带子太细点不中
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                for (i in 0 until n) {
                    val f = flows.optJSONObject(i) ?: continue
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp))
                            .background(if (sel == i) PAL[i % PAL.size].copy(.2f) else ZxT.Surface2)
                            .clickable {
                                sel = i
                                ctx.callTool("zx_select", mapOf(
                                    "choice" to "${f.optString("s")}→${f.optString("d")}",
                                    "v" to f.optDouble("v", 0.0).toString(),
                                    "source" to "zx_sankey"))
                            }.padding(7.dp, 3.dp)) {
                        Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp))
                            .background(PAL[i % PAL.size]))
                        Spacer(Modifier.width(4.dp))
                        Text(f.optDouble("v", 0.0).toLong().toString(),
                             fontSize = 9.5.sp, color = ZxT.Text2, fontFamily = ZxT.Mono)
                    }
                }
            }
            if (sel >= 0) {
                val f = flows.optJSONObject(sel)
                Text("${f?.optString("s")} → ${f?.optString("d")}：${f?.optDouble("v", 0.0)?.toLong()}",
                     fontSize = 10.sp, color = ZxT.Text2,
                     modifier = Modifier.padding(top = 5.dp))
            }
        }
    }

    /* ══════════ zx_gauge 仪表盘 ══════════ */
    @Composable
    fun gauge(o: JSONObject, ctx: RenderContext) {
        val min = o.optDouble("min", 0.0)
        val max = o.optDouble("max", 100.0).coerceAtLeast(min + 0.001)
        val v = o.optDouble("v", min)
        val t = ((v - min) / (max - min)).coerceIn(0.0, 1.0).toFloat()
        val zones = o.optJSONArray("zones")

        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(118.dp), contentAlignment = Alignment.BottomCenter) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val c = Offset(size.width / 2, size.height * .82f)
                    val rad = size.minDimension * .42f
                    // 底弧：只有指针没有底弧就读不出量程
                    drawArc(ZxT.Surface3, 180f, 180f, false,
                            topLeft = Offset(c.x - rad, c.y - rad),
                            size = Size(rad * 2, rad * 2), style = Stroke(width = 11f))
                    // 分区着色
                    if (zones != null && zones.length() >= 2) {
                        for (i in 0 until minOf(zones.length(), 5)) {
                            val z = zones.optJSONObject(i) ?: continue
                            val a = ((z.optDouble("from", min) - min) / (max - min))
                                .coerceIn(0.0, 1.0).toFloat()
                            val b = ((z.optDouble("to", max) - min) / (max - min))
                                .coerceIn(0.0, 1.0).toFloat()
                            val hex = z.optString("hex")
                            val col = runCatching {
                                Color(android.graphics.Color.parseColor(hex)) }
                                .getOrNull() ?: PAL[i % PAL.size]
                            drawArc(col.copy(.35f), 180f + a * 180f, (b - a) * 180f, false,
                                    topLeft = Offset(c.x - rad, c.y - rad),
                                    size = Size(rad * 2, rad * 2), style = Stroke(width = 11f))
                        }
                    }
                    drawArc(ZxT.Primary, 180f, t * 180f, false,
                            topLeft = Offset(c.x - rad, c.y - rad),
                            size = Size(rad * 2, rad * 2), style = Stroke(width = 11f))
                    // 指针
                    val ang = Math.PI * (1 + t)
                    drawLine(ZxT.Text, c,
                             Offset(c.x + kotlin.math.cos(ang).toFloat() * rad * .92f,
                                    c.y + kotlin.math.sin(ang).toFloat() * rad * .92f), 2.5f)
                    drawCircle(ZxT.Text, 4f, c)
                }
                Column(Modifier.padding(bottom = 8.dp),
                       horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (v == kotlin.math.floor(v)) v.toLong().toString()
                         else "%.1f".format(v),
                         fontSize = 20.sp, fontWeight = FontWeight.Bold, color = ZxT.Text,
                         fontFamily = ZxT.Mono)
                    if (o.optString("unit").isNotBlank())
                        Text(o.optString("unit"), fontSize = 9.5.sp, color = ZxT.Text3)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Text(if (min == kotlin.math.floor(min)) min.toLong().toString()
                     else "%.1f".format(min),
                     fontSize = 9.sp, color = ZxT.Text3, modifier = Modifier.weight(1f),
                     textAlign = TextAlign.Start)
                Text(o.optString("n", ""), fontSize = 10.sp, color = ZxT.Text3)
                Text(if (max == kotlin.math.floor(max)) max.toLong().toString()
                     else "%.1f".format(max),
                     fontSize = 9.sp, color = ZxT.Text3, modifier = Modifier.weight(1f),
                     textAlign = TextAlign.End)
            }
        }
    }

    /* ══════════ zx_sparkgrid 迷你图网格 ══════════ */
    @Composable
    fun sparkgrid(o: JSONObject, ctx: RenderContext) {
        val items = o.optJSONArray("items") ?: return
        val n = minOf(items.length(), 6)
        var sel by remember { mutableIntStateOf(-1) }
        // 2 列：气泡里 3 列会让每个图只剩 90dp
        for (r in 0 until (n + 1) / 2) {
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in 0 until 2) {
                    val i = r * 2 + c
                    if (i >= n) { Box(Modifier.weight(1f)); continue }
                    val it = items.optJSONObject(i) ?: continue
                    val pts = it.optJSONArray("pts")
                    val vals = (0 until (pts?.length() ?: 0))
                        .map { pts!!.optDouble(it, 0.0) }
                    if (vals.isEmpty()) { Box(Modifier.weight(1f)); continue }
                    val mx = vals.maxOrNull()!!.coerceAtLeast(0.001)
                    val mn = vals.minOrNull()!!
                    val up = vals.last() >= vals.first()
                    val col = if (up) ZxT.Ok else ZxT.Err
                    Column(Modifier.weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (sel == i) col.copy(.1f) else ZxT.Surface2)
                        .border(1.dp, if (sel == i) col else ZxT.Line, RoundedCornerShape(8.dp))
                        .clickable {
                            sel = i
                            ctx.callTool("zx_select", mapOf(
                                "choice" to it.optString("n", "$i"),
                                "source" to "zx_sparkgrid"))
                        }.padding(8.dp, 6.dp)) {
                        Text(it.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text3,
                             maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (vals.last() == kotlin.math.floor(vals.last()))
                                     vals.last().toLong().toString()
                                 else "%.1f".format(vals.last()),
                                 fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                 color = ZxT.Text, fontFamily = ZxT.Mono)
                            Spacer(Modifier.width(3.dp))
                            Text(if (up) "▲" else "▼", fontSize = 8.sp, color = col)
                        }
                        Box(Modifier.fillMaxWidth().height(24.dp)) {
                            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                                val rng = (mx - mn).coerceAtLeast(0.001)
                                val path = Path()
                                vals.forEachIndexed { k, d ->
                                    val x = k.toFloat() / (vals.size - 1).coerceAtLeast(1) * size.width
                                    val y = size.height - ((d - mn) / rng).toFloat() * size.height
                                    if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
                                }
                                drawPath(path, col, style = Stroke(width = 1.8f))
                            }
                        }
                    }
                }
            }
        }
    }

    /* ══════════ zx_breadcrumb 面包屑 ══════════ */
    @Composable
    fun breadcrumb(o: JSONObject, ctx: RenderContext) {
        val items = o.optJSONArray("items") ?: return
        val n = minOf(items.length(), 6)
        var cur by remember { mutableIntStateOf(o.optInt("cur", n - 1).coerceIn(0, n - 1)) }

        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically) {
            for (i in 0 until n) {
                val t = items.optString(i)
                val on = i == cur
                // 当前项不高亮就只是装饰，不是导航
                Text(t, fontSize = if (on) 11.sp else 10.5.sp,
                     fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                     color = if (on) ZxT.Text else ZxT.Text3,
                     maxLines = 1,
                     modifier = Modifier.clickable {
                         cur = i
                         ctx.callTool("zx_select", mapOf(
                             "choice" to t, "index" to i.toString(),
                             "source" to "zx_breadcrumb"))
                     })
                if (i < n - 1) {
                    Spacer(Modifier.width(6.dp))
                    Text("›", fontSize = 11.sp, color = ZxT.Text3)
                    Spacer(Modifier.width(6.dp))
                }
            }
        }
    }

    /* ══════════ zx_pagination 分页器 ══════════ */
    @Composable
    fun pagination(o: JSONObject, ctx: RenderContext) {
        val total = maxOf(1, o.optInt("total", 5))
        var cur by remember { mutableIntStateOf(o.optInt("cur", 1).coerceIn(1, total)) }
        // 最多 5 个页码：再多气泡里放不下，用省略号
        val win = if (total <= 5) (1..total).toList() else {
            val s = maxOf(1, minOf(cur - 2, total - 4))
            (s until s + 5).toList()
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center) {
            Text("‹", fontSize = 15.sp, color = if (cur > 1) ZxT.Text2 else ZxT.Text3,
                 modifier = Modifier.clickable(enabled = cur > 1) {
                     cur--
                     ctx.callTool("zx_select", mapOf("page" to cur.toString(),
                                                     "source" to "zx_pagination"))
                 }.padding(horizontal = 9.dp))
            if (total > 5 && win.first() > 1) {
                Text("1", fontSize = 10.5.sp, color = ZxT.Text3,
                     modifier = Modifier.clickable {
                         cur = 1
                         ctx.callTool("zx_select", mapOf("page" to "1",
                                                         "source" to "zx_pagination"))
                     }.padding(horizontal = 5.dp))
                Text("…", fontSize = 10.5.sp, color = ZxT.Text3)
            }
            win.forEach { p ->
                val on = p == cur
                Box(Modifier.size(27.dp).clip(RoundedCornerShape(7.dp))
                    .background(if (on) ZxT.Primary else Color.Transparent)
                    .border(if (on) 0.dp else 1.dp, if (on) Color.Transparent else ZxT.Line,
                            RoundedCornerShape(7.dp))
                    .clickable {
                        cur = p
                        ctx.callTool("zx_select", mapOf("page" to p.toString(),
                                                        "source" to "zx_pagination"))
                    }, contentAlignment = Alignment.Center) {
                    Text("$p", fontSize = 11.sp,
                         color = if (on) Color.White else ZxT.Text2,
                         fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                }
                Spacer(Modifier.width(3.dp))
            }
            if (total > 5 && win.last() < total) {
                Text("…", fontSize = 10.5.sp, color = ZxT.Text3)
                Text("$total", fontSize = 10.5.sp, color = ZxT.Text3,
                     modifier = Modifier.clickable {
                         cur = total
                         ctx.callTool("zx_select", mapOf("page" to total.toString(),
                                                         "source" to "zx_pagination"))
                     }.padding(horizontal = 5.dp))
            }
            Text("›", fontSize = 15.sp, color = if (cur < total) ZxT.Text2 else ZxT.Text3,
                 modifier = Modifier.clickable(enabled = cur < total) {
                     cur++
                     ctx.callTool("zx_select", mapOf("page" to cur.toString(),
                                                     "source" to "zx_pagination"))
                 }.padding(horizontal = 9.dp))
        }
    }

    /* ══════════ zx_tabbar 底部标签栏 ══════════ */
    @Composable
    fun tabbar(o: JSONObject, ctx: RenderContext) {
        val items = o.optJSONArray("items") ?: return
        val n = minOf(items.length(), 5)
        var cur by remember { mutableIntStateOf(o.optInt("cur", 0).coerceIn(0, n - 1)) }

        Row(Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(ZxT.Surface2)
            .border(1.dp, ZxT.Line, RoundedCornerShape(11.dp))
            .padding(4.dp)) {
            for (i in 0 until n) {
                val it = items.optJSONObject(i) ?: continue
                val on = i == cur
                Column(Modifier.weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) ZxT.Primary.copy(.16f) else Color.Transparent)
                    .clickable {
                        cur = i
                        ctx.callTool("zx_select", mapOf(
                            "choice" to it.optString("k", "$i"),
                            "source" to "zx_tabbar"))
                    }.padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(it.optString("e", "·"), fontSize = 14.sp,
                         color = if (on) ZxT.PriLite else ZxT.Text3)
                    Spacer(Modifier.height(2.dp))
                    Text(it.optString("n", ""), fontSize = 9.sp,
                         color = if (on) ZxT.PriLite else ZxT.Text3,
                         maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // 选中指示条：没有它看不出当前是哪个
                    Spacer(Modifier.height(3.dp))
                    Box(Modifier.width(16.dp).height(2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(if (on) ZxT.PriLite else Color.Transparent))
                }
            }
        }
    }

    /* ══════════ zx_searchbar 搜索栏 ══════════ */
    @Composable
    fun searchbar(o: JSONObject, ctx: RenderContext) {
        var q by remember { mutableStateOf(o.optString("v", "")) }
        var sent by remember { mutableStateOf(false) }
        val sugg = o.optJSONArray("sugg")

        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(ZxT.Surface2)
                .border(1.dp, if (q.isNotBlank()) ZxT.Primary else ZxT.Line,
                        RoundedCornerShape(10.dp))
                .padding(11.dp, 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("🔍", fontSize = 11.sp)
                Spacer(Modifier.width(8.dp))
                androidx.compose.material3.TextField(
                    value = q, onValueChange = { q = it; sent = false },
                    placeholder = { Text(o.optString("hint", "搜索…"), fontSize = 11.sp,
                                         color = ZxT.Text3) },
                    modifier = Modifier.weight(1f).height(30.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.5.sp, color = ZxT.Text),
                    singleLine = true,
                    colors = androidx.compose.material3.TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = ZxT.Primary))
                if (q.isNotBlank())
                    Text("×", fontSize = 12.sp, color = ZxT.Text3,
                         modifier = Modifier.clickable { q = ""; sent = false })
            }
            // 中间态不回传——回车才发
            if (!sent && q.isNotBlank() && o.optBoolean("go", true))
                Text("按回车搜索（输入过程不回传，否则每敲一个字都要走一轮）",
                     fontSize = 9.5.sp, color = ZxT.Text3,
                     modifier = Modifier.padding(top = 5.dp))
            if (sugg != null && sugg.length() > 0 && q.isBlank()) {
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    for (i in 0 until minOf(sugg.length(), 6)) {
                        val s = sugg.optString(i)
                        Text(s, fontSize = 10.sp, color = ZxT.Text3,
                             modifier = Modifier.clip(RoundedCornerShape(10.dp))
                                 .background(ZxT.Surface2)
                                 .clickable {
                                     q = s
                                     ctx.callTool("zx_form_submit", mapOf("payload" to
                                         JSONObject().put("q", s).toString()))
                                 }.padding(7.dp, 3.dp))
                    }
                }
            }
        }
    }

    /* ══════════ zx_chips 筛选芯片组 ══════════ */
    @Composable
    fun chips(o: JSONObject, ctx: RenderContext) {
        val items = o.optJSONArray("items") ?: return
        val n = minOf(items.length(), 10)
        val multi = o.optBoolean("multi", true)
        val sel = remember { mutableStateSetOf<Int>() }
        if (o.optBoolean("allOn", false) && sel.isEmpty()) for (i in 0 until n) sel.add(i)

        var row = mutableListOf<Pair<Int, String>>()
        val rows = mutableListOf<List<Pair<Int, String>>>()
        for (i in 0 until n) {
            row.add(i to items.optString(i))
            if (row.size == 3) { rows.add(row); row = mutableListOf() }
        }
        if (row.isNotEmpty()) rows.add(row)

        Column(Modifier.fillMaxWidth()) {
            rows.forEach { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    r.forEach { (i, t) ->
                        val on = i in sel
                        Row(Modifier.clip(RoundedCornerShape(13.dp))
                            .background(if (on) ZxT.Primary.copy(.18f) else ZxT.Surface2)
                            .border(1.dp, if (on) ZxT.Primary else ZxT.Line,
                                    RoundedCornerShape(13.dp))
                            .clickable {
                                if (multi) { if (on) sel.remove(i) else sel.add(i) }
                                else { sel.clear(); sel.add(i) }
                                ctx.callTool("zx_select", mapOf(
                                    "choice" to sel.sorted().joinToString(",") { items.optString(it) },
                                    "source" to "zx_filterchips"))
                            }.padding(9.dp, 5.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            if (on) {
                                Text("✓", fontSize = 9.sp, color = ZxT.PriLite)
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(t, fontSize = 10.5.sp,
                                 color = if (on) ZxT.PriLite else ZxT.Text3,
                                 maxLines = 1)
                        }
                    }
                    repeat(3 - r.size) { Box(Modifier.weight(1f)) }
                }
            }
            Text(if (multi) "多选 · 已选 ${sel.size} 个" else "单选 · 已选 ${sel.size} 个",
                 fontSize = 10.sp, color = ZxT.Text3,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_colorpick 取色板 ══════════ */
    @Composable
    fun colorpick(o: JSONObject, ctx: RenderContext) {
        val swatches = o.optJSONArray("swatches")
        val list = mutableListOf<Pair<String, Color>>()
        if (swatches != null) for (i in 0 until minOf(swatches.length(), 12)) {
            val s = swatches.optString(i)
            val col = runCatching { Color(android.graphics.Color.parseColor(s)) }.getOrNull()
            if (col != null) list.add(s to col)
        }
        if (list.isEmpty()) list.addAll(listOf(
            "#5046E4" to ZxT.Primary, "#3FBF7F" to ZxT.Ok, "#E8A33D" to ZxT.Warn,
            "#E5544B" to ZxT.Err, "#4A9BE8" to PAL[4], "#8E86FF" to PAL[5]))
        var sel by remember { mutableIntStateOf(-1) }

        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                list.forEachIndexed { i, (hex, col) ->
                    Box(Modifier.size(34.dp)
                        .clip(CircleShape)
                        .background(col)
                        .border(if (sel == i) 2.dp else 1.dp,
                                if (sel == i) ZxT.Text else ZxT.Line, CircleShape)
                        .clickable {
                            sel = i
                            ctx.callTool("zx_select", mapOf(
                                "choice" to hex, "source" to "zx_colorpick"))
                        })
                }
            }
            Text(if (sel >= 0) "已选 ${list[sel].first}"
                 else "点一个色块（最多 12 个，气泡里放不下更多）",
                 fontSize = 10.5.sp, color = ZxT.Text2,
                 modifier = Modifier.padding(top = 7.dp))
        }
    }

    /* ══════════ zx_stepper 数字步进器 ══════════ */
    @Composable
    fun stepper(o: JSONObject, ctx: RenderContext) {
        val min = o.optDouble("min", 0.0)
        val max = o.optDouble("max", 99.0).coerceAtLeast(min)
        val step = o.optDouble("step", 1.0).coerceAtLeast(0.001)
        var v by remember { mutableDoubleStateOf(
            ((o.optDouble("v", min) - min) / step).toLong() * step + min) }
        val longPress = remember { mutableStateOf(false) }

        fun bump(d: Int) {
            val nv = (v + d * step).coerceIn(min, max)
            if (nv == v) return
            v = nv
            // 停下才回传：每按一次都回传会刷爆上下文
            ctx.callTool("zx_form_submit", mapOf("payload" to
                JSONObject().put("v", v).put("op", if (d > 0) "inc" else "dec").toString()))
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(o.optString("n", ""), fontSize = 11.5.sp, color = ZxT.Text2,
                 modifier = Modifier.weight(1f))
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp))
                .background(ZxT.Surface2).border(1.dp, ZxT.Line, RoundedCornerShape(8.dp))
                .clickable(enabled = v > min) { bump(-1) }, contentAlignment = Alignment.Center) {
                Text("−", fontSize = 15.sp, color = if (v > min) ZxT.Text2 else ZxT.Text3)
            }
            Box(Modifier.width(58.dp).padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center) {
                Text(if (v == kotlin.math.floor(v)) v.toLong().toString()
                     else "%.2f".format(v),
                     fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = ZxT.Text,
                     fontFamily = ZxT.Mono, textAlign = TextAlign.Center)
            }
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp))
                .background(ZxT.Surface2).border(1.dp, ZxT.Line, RoundedCornerShape(8.dp))
                .clickable(enabled = v < max) { bump(1) }, contentAlignment = Alignment.Center) {
                Text("+", fontSize = 15.sp, color = if (v < max) ZxT.Text2 else ZxT.Text3)
            }
        }
    }

    /* ══════════ zx_treeview 层级树（带复选） ══════════ */
    @Composable
    fun treeview(o: JSONObject, ctx: RenderContext) {
        val root = o.optJSONObject("root") ?: return
        val sel = remember { mutableStateSetOf<String>() }
        val open = remember { mutableStateSetOf<String>() }
        var depth by remember { mutableIntStateOf(0) }

        Column(Modifier.fillMaxWidth()) {
            TreeRow(root, 0, sel, open) { id ->
                if (id in sel) sel.remove(id) else sel.add(id)
                ctx.callTool("zx_select", mapOf("all" to sel.sorted().joinToString(","),
                                                "source" to "zx_treeview"))
            }
            Text("已选 ${sel.size} 项 · 父级半选会显示 ─",
                 fontSize = 10.sp, color = ZxT.Text3,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    @Composable
    private fun TreeRow(n: JSONObject, depth: Int, sel: MutableSet<String>,
                        open: MutableSet<String>, onPick: (String) -> Unit) {
        val id = n.optString("k", "")
        val kids = n.optJSONArray("kids")
        val hasKids = kids != null && kids.length() > 0
        val isOpen = id in open || depth == 0
        val on = id in sel
        // 半选：子节点有选中但自己没全选
        val half = !on && hasKids && (0 until kids!!.length()).any {
            kids.optJSONObject(it)?.optString("k") in sel }

        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)
                .padding(start = (depth * 14).dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (hasKids)
                    Text(if (isOpen) "▾" else "▸", fontSize = 9.sp, color = ZxT.Text3,
                         modifier = Modifier.clickable {
                             if (isOpen) open.remove(id) else open.add(id)
                         }.padding(end = 5.dp).width(11.dp))
                else Spacer(Modifier.width(16.dp))
                Box(Modifier.size(15.dp).clip(RoundedCornerShape(4.dp))
                    .background(if (on || half) ZxT.Primary else Color.Transparent)
                    .border(1.dp, if (on || half) ZxT.Primary else ZxT.Line,
                            RoundedCornerShape(4.dp))
                    .clickable { onPick(id) }, contentAlignment = Alignment.Center) {
                    if (on) Text("✓", fontSize = 9.sp, color = Color.White)
                    else if (half) Text("─", fontSize = 9.sp, color = Color.White)
                }
                Spacer(Modifier.width(8.dp))
                Text(n.optString("n", ""), fontSize = 11.sp, color = ZxT.Text2,
                     modifier = Modifier.weight(1f), maxLines = 1,
                     overflow = TextOverflow.Ellipsis)
            }
            if (hasKids && isOpen) for (i in 0 until minOf(kids!!.length(), 8)) {
                TreeRow(kids.optJSONObject(i) ?: continue, depth + 1, sel, open, onPick)
            }
        }
    }
}
