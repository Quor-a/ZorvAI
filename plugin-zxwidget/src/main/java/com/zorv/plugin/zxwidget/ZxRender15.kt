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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 第十五批组件（12 个）—— 关系 / 文本 / 小众图表。
 *
 * 前十四批把主流图表铺完了，剩下的是三类长期被将就的：
 *
 *   一、**关系与空间**。网络图一直用 depgraph（有向依赖）顶替，
 *       但无向的、带权重的、需要力导向的关系画不出来；
 *       路径图更是完全没有——而"从 A 到 B 怎么走"是高频需求。
 *
 *   二、**文本协作**。已有 rich / comment / diff / diffview，
 *       但没有"在原文上标"这件事本身：
 *       diff 是两版对照，annotate 是在同一份上挂意见。
 *
 *   三、**小众图表**。子弹图、冰柱图、队列留存、斜率图、
 *       哑铃图、华夫饼、山脊线、阶梯面积——
 *       每一个都有"用柱状图硬凑但凑不像"的明确场景。
 *
 * 贯穿这批的一条：**替代方案要真的更省，不是更花哨**。
 * 子弹图比仪表盘省一半高度且能同时表达实际/目标/量程；
 * 斜率图在只有两个时间点时比折线图清楚得多；
 * 华夫饼在要表达"每一格是一个人"时比饼图直观。
 * 只是换个画法而没有省，就不该加。
 */
object ZxRender15 {

    private val PAL = listOf(
        Color(0xFF5046E4), Color(0xFF3FBF7F), Color(0xFFE8A33D),
        Color(0xFFE5544B), Color(0xFF4A9BE8), Color(0xFF8E86FF)
    )

    /* ══════════ zx_route 路径图 ══════════ */
    @Composable
    fun route(o: JSONObject, ctx: RenderContext) {
        val stops = o.optJSONArray("stops") ?: return
        val n = minOf(stops.length(), 8)
        var sel by remember { mutableIntStateOf(-1) }

        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth()) {
                // 左侧竖向轨道：路线图的核心就是"顺序 + 在哪换乘"
                Column(Modifier.width(26.dp)) {
                    for (i in 0 until n) {
                        val s = stops.optJSONObject(i)
                        val kind = s?.optString("k", "stop") ?: "stop"
                        val col = if (kind == "change") ZxT.Warn else PAL[0]
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(if (sel == i) 12.dp else 9.dp)
                                .clip(CircleShape)
                                .background(if (sel == i) ZxT.Text else col))
                            if (i < n - 1)
                                Box(Modifier.width(2.dp).height(24.dp)
                                    .background(col.copy(.4f)))
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    for (i in 0 until n) {
                        val s = stops.optJSONObject(i) ?: continue
                        val kind = s.optString("k", "stop")
                        Row(Modifier.fillMaxWidth().height(33.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (sel == i) ZxT.Surface2 else Color.Transparent)
                            .clickable {
                                sel = i
                                ctx.callTool("zx_select", mapOf(
                                    "choice" to s.optString("n", "$i"),
                                    "kind" to kind, "source" to "zx_route"))
                            }.padding(5.dp, 2.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(s.optString("n", ""), fontSize = 11.sp, color = ZxT.Text,
                                 modifier = Modifier.weight(1f), maxLines = 1,
                                 overflow = TextOverflow.Ellipsis)
                            if (kind == "change")
                                Text("换乘", fontSize = 8.5.sp, color = ZxT.Warn,
                                     modifier = Modifier.clip(RoundedCornerShape(4.dp))
                                         .background(ZxT.Warn.copy(.16f)).padding(5.dp, 2.dp))
                            s.optString("t").takeIf { it.isNotBlank() }?.let {
                                Text(it, fontSize = 9.5.sp, color = ZxT.Text3,
                                     fontFamily = ZxT.Mono,
                                     modifier = Modifier.padding(start = 6.dp))
                            }
                        }
                    }
                }
            }
            if (sel >= 0) {
                val s = stops.optJSONObject(sel)
                Text("第 ${sel + 1}/${n} 站 · ${s?.optString("d", "")}",
                     fontSize = 10.sp, color = ZxT.Text3,
                     modifier = Modifier.padding(top = 5.dp))
            }
        }
    }

    /* ══════════ zx_network 关系网络 ══════════ */
    @Composable
    fun network(o: JSONObject, ctx: RenderContext) {
        val nodes = o.optJSONArray("nodes") ?: return
        val links = o.optJSONArray("links")
        val n = minOf(nodes.length(), 12)
        var sel by remember { mutableIntStateOf(-1) }
        // 力导向：固定迭代次数让布局稳定，不能每次重绘都跳
        val pos = remember(n) {
            val p = (0 until n).map { i ->
                val a = i * 2.399963
                Offset(165f + (kotlin.math.cos(a) * (30 + i * 6f)).toFloat(),
                       56f + (kotlin.math.sin(a) * (20 + i * 4f)).toFloat())
            }.toMutableList()
            repeat(40) {
                for (i in 0 until n) for (j in i + 1 until n) {
                    val dx = p[i].x - p[j].x; val dy = p[i].y - p[j].y
                    val d = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                    val f = 2200f / (d * d)
                    p[i] = Offset(p[i].x + dx / d * f, p[i].y + dy / d * f)
                    p[j] = Offset(p[j].x - dx / d * f, p[j].y - dy / d * f)
                }
                for (k in 0 until (links?.length() ?: 0)) {
                    val l = links!!.optJSONObject(k) ?: continue
                    val a = l.optInt("a"); val b = l.optInt("b")
                    if (a >= n || b >= n) continue
                    val dx = p[b].x - p[a].x; val dy = p[b].y - p[a].y
                    val d = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                    val f = (d - 70f) * 0.02f * (l.optDouble("w", 1.0)).toFloat()
                    p[a] = Offset(p[a].x + dx / d * f, p[a].y + dy / d * f)
                    p[b] = Offset(p[b].x - dx / d * f, p[b].y - dy / d * f)
                }
                for (i in 0 until n) {
                    p[i] = Offset(p[i].x.coerceIn(24f, 306f), p[i].y.coerceIn(18f, 94f))
                }
            }
            p.toList()
        }
        val nb = (0 until n).map { i ->
            val s = mutableSetOf<Int>()
            for (k in 0 until (links?.length() ?: 0)) {
                val l = links!!.optJSONObject(k) ?: continue
                if (l.optInt("a") == i) s.add(l.optInt("b"))
                if (l.optInt("b") == i) s.add(l.optInt("a"))
            }
            s
        }

        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(112.dp)) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    for (k in 0 until (links?.length() ?: 0)) {
                        val l = links!!.optJSONObject(k) ?: continue
                        val a = l.optInt("a"); val b = l.optInt("b")
                        if (a >= n || b >= n) continue
                        val hot = sel >= 0 && (a == sel || b == sel)
                        drawLine(if (hot) PAL[0] else ZxT.Line, pos[a], pos[b],
                                 if (hot) 2f else (l.optDouble("w", 1.0)).toFloat())
                    }
                    for (i in 0 until n) {
                        val hot = sel >= 0 && (i == sel || i in nb[sel])
                        val r = 8f + (nodes.optJSONObject(i)?.optDouble("v", 1.0)
                            ?.toFloat() ?: 1f) * 2f
                        drawCircle(if (hot) PAL[0] else PAL[0].copy(.6f), r, pos[i])
                    }
                }
                pos.forEachIndexed { i, p ->
                    Text(nodes.optJSONObject(i)?.optString("n", "") ?: "",
                         fontSize = 8.sp, color = ZxT.Text3, maxLines = 1,
                         modifier = Modifier
                             .offset(x = Dp(p.x / 330 * 330), y = Dp(p.y / 112 * 112 + 8))
                             .width(56.dp), textAlign = TextAlign.Center)
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                for (i in 0 until n) {
                    val nd = nodes.optJSONObject(i) ?: continue
                    val on = sel == i
                    Text(nd.optString("n", "$i"), fontSize = 10.sp,
                         color = if (on) ZxT.PriLite else ZxT.Text3,
                         modifier = Modifier.clip(RoundedCornerShape(10.dp))
                             .background(if (on) PAL[0].copy(.2f) else ZxT.Surface2)
                             .clickable {
                                 sel = i
                                 ctx.callTool("zx_select", mapOf(
                                     "choice" to nd.optString("k", "$i"),
                                     "degree" to nb[i].size.toString(),
                                     "source" to "zx_network"))
                             }.padding(8.dp, 4.dp))
                }
            }
            if (sel >= 0)
                Text("度数 ${nb[sel].size}：${nodes.optJSONObject(sel)?.optString("n")} 与 ${nb[sel].size} 个节点相连",
                     fontSize = 10.sp, color = ZxT.Text3,
                     modifier = Modifier.padding(top = 5.dp))
        }
    }

    /* ══════════ zx_annotate 文本批注 ══════════ */
    @Composable
    fun annotate(o: JSONObject, ctx: RenderContext) {
        val text = o.optString("text")
        val marks = o.optJSONArray("marks")
        var active by remember { mutableIntStateOf(-1) }
        var draft by remember { mutableStateOf("") }

        // 批注与 diff 的分工：diff 是两版对照，annotate 是在同一份上挂意见
        val segs = remember(text, marks) {
            if (marks == null || marks.length() == 0) listOf(Triple(text, -1, false))
            else {
                val out = mutableListOf<Triple<String, Int, Boolean>>()
                var cursor = 0
                val spans = (0 until marks.length()).mapNotNull { i ->
                    val m = marks.optJSONObject(i) ?: return@mapNotNull null
                    val s = m.optInt("s"); val e = m.optInt("e")
                    if (s < 0 || e > text.length || s >= e) null
                    else Triple(s, e, i)
                }.sortedBy { it.first }
                spans.forEach { (s, e, i) ->
                    if (s > cursor) out.add(Triple(text.substring(cursor, s), -1, false))
                    out.add(Triple(text.substring(s, e), i, true))
                    cursor = e
                }
                if (cursor < text.length) out.add(Triple(text.substring(cursor), -1, false))
                out
            }
        }

        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(8.dp)).background(ZxT.Surface2).padding(10.dp)) {
                // 用 Row 包多个 Text 近似行内 span，避免 Canvas 里处理字体度量
                val sb = StringBuilder()
                androidx.compose.material3.Text(
                    buildString {
                        segs.forEach { (t, i, marked) ->
                            if (marked) append("⟦").append(t).append("⟧") else append(t)
                        }
                    }, fontSize = 11.5.sp, color = ZxT.Text2, lineHeight = 17.sp)
            }
            Spacer(Modifier.height(7.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                for (i in 0 until (marks?.length() ?: 0)) {
                    val m = marks!!.optJSONObject(i) ?: continue
                    val on = active == i
                    Text(m.optString("by", "批注"), fontSize = 10.sp,
                         color = if (on) ZxT.PriLite else ZxT.Text3,
                         modifier = Modifier.clip(RoundedCornerShape(10.dp))
                             .background(if (on) ZxT.Warn.copy(.25f) else ZxT.Surface2)
                             .clickable { active = i }.padding(8.dp, 4.dp))
                }
            }
            if (active >= 0) {
                val m = marks!!.optJSONObject(active)
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Box(Modifier.width(2.dp).height(34.dp).background(ZxT.Warn))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m?.optString("q", "") ?: "", fontSize = 10.sp,
                             color = ZxT.Text3, maxLines = 2,
                             overflow = TextOverflow.Ellipsis)
                        Text(m?.optString("c", "") ?: "", fontSize = 11.sp,
                             color = ZxT.Text, lineHeight = 15.sp)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.TextField(
                        value = draft, onValueChange = { draft = it },
                        placeholder = { Text("回复…", fontSize = 10.sp, color = ZxT.Text3) },
                        modifier = Modifier.weight(1f).height(34.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = ZxT.Text),
                        singleLine = true,
                        colors = androidx.compose.material3.TextFieldDefaults.colors(
                            focusedContainerColor = ZxT.Surface2,
                            unfocusedContainerColor = ZxT.Surface2,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = ZxT.Primary))
                    Spacer(Modifier.width(6.dp))
                    Text("回复", fontSize = 11.sp, color = ZxT.PriLite,
                         modifier = Modifier.clickable(enabled = draft.isNotBlank()) {
                             ctx.callTool("zx_select", mapOf(
                                 "mark" to active.toString(), "reply" to draft,
                                 "source" to "zx_annotate"))
                             draft = ""
                         })
                }
            }
        }
    }

    /* ══════════ zx_revision 修订标记 ══════════ */
    @Composable
    fun revision(o: JSONObject, ctx: RenderContext) {
        val items = o.optJSONArray("items") ?: return
        val n = minOf(items.length(), 8)
        val acc = remember { mutableStateMapOf<Int, Boolean>() }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "修订"), fontSize = 11.5.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("${acc.count { it.value }}/$n 已接受", fontSize = 10.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(6.dp))
            for (i in 0 until n) {
                val it = items.optJSONObject(i) ?: continue
                val kind = it.optString("k", "mod")
                val on = acc[i] ?: false
                val (col, bg) = when (kind) {
                    "add" -> ZxT.Ok to ZxT.Ok.copy(.12f)
                    "del" -> ZxT.Err to ZxT.Err.copy(.12f)
                    else -> ZxT.Warn to ZxT.Warn.copy(.12f)
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(bg)
                    .border(1.dp, if (on) ZxT.Line else col.copy(.45f),
                            RoundedCornerShape(7.dp))
                    .clickable {
                        acc[i] = !on
                        ctx.callTool("zx_diff_apply", mapOf(
                            "accepted" to acc.filter { it.value }.keys.joinToString(","),
                            "path" to o.optString("path", "")))
                    }.padding(9.dp, 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(when (kind) { "add" -> "＋"; "del" -> "－"; else -> "～" },
                         fontSize = 11.sp, color = col, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(7.dp))
                    Column(Modifier.weight(1f)) {
                        if (kind != "add")
                            Text(it.optString("old", ""), fontSize = 10.sp,
                                 color = ZxT.Text3, fontFamily = ZxT.Mono,
                                 maxLines = 1, overflow = TextOverflow.Ellipsis,
                                 textDecoration = androidx.compose.ui.text.style
                                     .TextDecoration.LineThrough)
                        if (kind != "del")
                            Text(it.optString("new", ""), fontSize = 10.5.sp,
                                 color = ZxT.Text, fontFamily = ZxT.Mono,
                                 maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Text(if (on) "✓" else "·", fontSize = 11.sp,
                         color = if (on) ZxT.Ok else ZxT.Text3)
                }
            }
        }
    }

    /* ══════════ zx_bullet 子弹图 ══════════ */
    @Composable
    fun bullet(o: JSONObject, ctx: RenderContext) {
        val items = o.optJSONArray("items") ?: return
        val n = minOf(items.length(), 6)
        var sel by remember { mutableIntStateOf(-1) }

        Column(Modifier.fillMaxWidth()) {
            for (i in 0 until n) {
                val it = items.optJSONObject(i) ?: continue
                val max = it.optDouble("max", 100.0).coerceAtLeast(0.001)
                val v = it.optDouble("v", 0.0)
                val tgt = it.optDouble("tgt", Double.NaN)
                val bad = it.optDouble("bad", max * .5)
                val ok = it.optDouble("ok", max * .75)
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (sel == i) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        sel = i
                        ctx.callTool("zx_select", mapOf(
                            "choice" to it.optString("n", "$i"), "v" to v.toString(),
                            "tgt" to tgt.toString(), "source" to "zx_bullet"))
                    }.padding(5.dp, 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(it.optString("n", ""), fontSize = 10.5.sp, color = ZxT.Text2,
                         modifier = Modifier.width(54.dp), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    Box(Modifier.weight(1f).height(18.dp)) {
                        // 三段底色：差/良/优。子弹图比仪表盘省一半高度
                        Box(Modifier.fillMaxWidth().height(14.dp)
                            .clip(RoundedCornerShape(3.dp)).background(ZxT.Surface3))
                        Box(Modifier.fillMaxWidth((ok / max).toFloat().coerceIn(0f, 1f))
                            .height(14.dp).clip(RoundedCornerShape(3.dp))
                            .background(ZxT.Text3.copy(.22f)))
                        Box(Modifier.fillMaxWidth((bad / max).toFloat().coerceIn(0f, 1f))
                            .height(14.dp).clip(RoundedCornerShape(3.dp))
                            .background(ZxT.Text3.copy(.34f)))
                        // 实际值条
                        Box(Modifier.fillMaxWidth((v / max).toFloat().coerceIn(0f, 1f))
                            .height(6.dp).align(Alignment.CenterStart)
                            .clip(RoundedCornerShape(2.dp)).background(PAL[0]))
                        // 目标刻线
                        if (!tgt.isNaN()) {
                            Box(Modifier.fillMaxWidth().height(14.dp),
                                contentAlignment = Alignment.CenterStart) {
                                Box(Modifier
                                    .offset(x = ((tgt / max * 300).toInt() - 1).dp)
                                    .width(2.dp).height(18.dp).background(ZxT.Text))
                            }
                        }
                    }
                    Text(if (v == kotlin.math.floor(v)) v.toLong().toString()
                         else "%.1f".format(v),
                         fontSize = 10.sp, color = ZxT.Text2, fontFamily = ZxT.Mono,
                         modifier = Modifier.width(36.dp), textAlign = TextAlign.End)
                }
            }
            Text("深色底＝差/良区间，蓝条＝实际，竖线＝目标。" +
                 "子弹图比仪表盘省一半高度，且能同时表达实际/目标/量程",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 5.dp))
        }
    }

    /* ══════════ zx_icicle 冰柱图 ══════════ */
    @Composable
    fun icicle(o: JSONObject, ctx: RenderContext) {
        val root = o.optJSONObject("root") ?: return
        var sel by remember { mutableStateOf<String?>(null) }

        data class Row(val label: String, val depth: Int, val w: Float, val v: Double, val path: String)
        val rows = remember(root) {
            val out = mutableListOf<Row>()
            fun walk(n: JSONObject, depth: Int, w: Float, path: String) {
                val nm = n.optString("n", "?")
                val v = n.optDouble("v", 0.0)
                val p = if (path.isEmpty()) nm else "$path/$nm"
                out.add(Row(nm, depth, w, v, p))
                val kids = n.optJSONArray("c")
                if (kids == null || kids.length() == 0 || depth >= 3) return
                var acc = 0.0
                val tot = n.optDouble("v", 0.0).takeIf { it > 0 }
                    ?: (0 until kids.length()).sumOf { kids.optJSONObject(it)?.optDouble("v", 0.0) ?: 0.0 }
                for (i in 0 until kids.length()) {
                    val k = kids.optJSONObject(i) ?: continue
                    val kv = k.optDouble("v", 0.0)
                    val kw = if (tot > 0) (kv / tot).toFloat() * w else 0f
                    walk(k, depth + 1, kw, p)
                    acc += kv
                }
            }
            walk(root, 0, 1f, "")
            out
        }

        Column(Modifier.fillMaxWidth()) {
            rows.forEach { r ->
                val on = sel == r.path
                Row(Modifier.fillMaxWidth()
                    .padding(start = (r.depth * 10).dp, top = 2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(PAL[r.depth % PAL.size].copy(if (on) .85f else .3f + r.depth * .12f))
                    .clickable {
                        sel = r.path
                        ctx.callTool("zx_select", mapOf("choice" to r.path,
                                                        "v" to r.v.toString(), "source" to "zx_icicle"))
                    }.padding(7.dp, 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(r.label, fontSize = 10.5.sp, color = ZxT.Text,
                         modifier = Modifier.weight(1f), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    Text("%.0f".format(r.v), fontSize = 9.5.sp, color = ZxT.Text2,
                         fontFamily = ZxT.Mono)
                }
            }
            Text("宽度即占比，缩进即层级。比旭日图好在标签有地方放（旭日图扇区太窄放不下字）",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 5.dp))
        }
    }

    /* ══════════ zx_cohort 队列留存 ══════════ */
    @Composable
    fun cohort(o: JSONObject, ctx: RenderContext) {
        val rows = o.optJSONArray("rows") ?: return
        val n = minOf(rows.length(), 8)
        var sel by remember { mutableStateOf<Pair<Int, Int>?>(null) }
        val periods = o.optInt("periods", 5)

        Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Row(Modifier.padding(start = 52.dp)) {
                Text(o.optString("unit", "期"), fontSize = 8.5.sp, color = ZxT.Text3,
                     modifier = Modifier.width(40.dp), textAlign = TextAlign.Center)
                for (p in 0 until periods)
                    Text("+$p", fontSize = 8.5.sp, color = ZxT.Text3,
                         modifier = Modifier.width(38.dp), textAlign = TextAlign.Center)
            }
            for (i in 0 until n) {
                val r = rows.optJSONObject(i) ?: continue
                val arr = r.optJSONArray("v")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text3,
                         modifier = Modifier.width(44.dp), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    Text("%d".format(r.optInt("size", 0)), fontSize = 9.sp,
                         color = ZxT.Text3, fontFamily = ZxT.Mono,
                         modifier = Modifier.width(38.dp), textAlign = TextAlign.Center)
                    for (p in 0 until periods) {
                        val v = arr?.optDouble(p, Double.NaN) ?: Double.NaN
                        val t = if (v.isNaN()) 0f else (v / 100.0).toFloat().coerceIn(0f, 1f)
                        val on = sel?.first == i && sel?.second == p
                        Box(Modifier.width(38.dp).height(26.dp).padding(1.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (v.isNaN()) ZxT.Surface
                                        else Color(red = .17f + .38f * t, green = .27f + .53f * t,
                                                   blue = .89f - .28f * t))
                            .border(if (on) 1.5.dp else 0.dp, ZxT.Text, RoundedCornerShape(4.dp))
                            .clickable {
                                sel = i to p
                                ctx.callTool("zx_select", mapOf(
                                    "cohort" to r.optString("n", "$i"),
                                    "period" to p.toString(),
                                    "retain" to (if (v.isNaN()) "" else v.toString()),
                                    "source" to "zx_cohort"))
                            }, contentAlignment = Alignment.Center) {
                            if (!v.isNaN())
                                Text(if (v == kotlin.math.floor(v)) "${v.toLong()}%" else "%.1f".format(v),
                                     fontSize = 9.sp,
                                     color = if (t > .45f) Color.White else ZxT.Text3)
                        }
                    }
                }
            }
            // 平均行：不给平均值，用户要自己在脑子里算横行的趋势
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp)) {
                Text("平均", fontSize = 9.5.sp, color = ZxT.Text2,
                     modifier = Modifier.width(44.dp))
                Text("", modifier = Modifier.width(38.dp))
                for (p in 0 until periods) {
                    var s = 0.0; var c = 0
                    for (i in 0 until n) {
                        val v = rows.optJSONObject(i)?.optJSONArray("v")?.optDouble(p, Double.NaN)
                        if (v != null && !v.isNaN()) { s += v; c++ }
                    }
                    Text(if (c == 0) "—" else "${(s / c).toInt()}%", fontSize = 9.sp,
                         color = ZxT.Text2, fontFamily = ZxT.Mono,
                         modifier = Modifier.width(38.dp), textAlign = TextAlign.Center)
                }
            }
        }
    }

    /* ══════════ zx_slope 斜率图 ══════════ */
    @Composable
    fun slope(o: JSONObject, ctx: RenderContext) {
        val items = o.optJSONArray("items") ?: return
        val n = minOf(items.length(), 8)
        var sel by remember { mutableIntStateOf(-1) }
        val aLab = o.optString("a", "起"); val bLab = o.optString("b", "止")
        val all = (0 until n).flatMap { i ->
            val it = items.optJSONObject(i) ?: JSONObject()
            listOf(it.optDouble("a", 0.0), it.optDouble("b", 0.0))
        }
        val gLo = all.minOrNull() ?: 0.0
        val gHi = all.maxOrNull() ?: 1.0
        val span = (gHi - gLo).coerceAtLeast(1e-6)

        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(104.dp)) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    fun y(v: Double) = size.height - ((v - gLo) / span).toFloat() * size.height
                    for (i in 0 until n) {
                        val it = items.optJSONObject(i) ?: continue
                        val av = it.optDouble("a", 0.0); val bv = it.optDouble("b", 0.0)
                        val up = bv >= av
                        val col = PAL[i % PAL.size]
                        val c = if (sel == i) col else col.copy(.55f)
                        drawLine(c, Offset(16f, y(av)), Offset(size.width - 16f, y(bv)),
                                 if (sel == i) 2.4f else 1.6f)
                        drawCircle(c, if (sel == i) 5f else 3.6f, Offset(16f, y(av)))
                        drawCircle(c, if (sel == i) 5f else 3.6f,
                                   Offset(size.width - 16f, y(bv)))
                    }
                }
                Row(Modifier.fillMaxWidth().height(104.dp)) {
                    (0 until n).forEach { i ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = i
                            ctx.callTool("zx_select", mapOf(
                                "choice" to items.optJSONObject(i)?.optString("n", "$i"),
                                "delta" to (((items.optJSONObject(i)?.optDouble("b", 0.0) ?: 0.0)
                                    - (items.optJSONObject(i)?.optDouble("a", 0.0) ?: 0.0))).toString(),
                                "source" to "zx_slope"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                Text(aLab, fontSize = 9.sp, color = ZxT.Text3,
                     modifier = Modifier.width(30.dp))
                if (sel >= 0) {
                    val it = items.optJSONObject(sel)
                    Text("${it?.optString("n")}：${it?.optDouble("a", 0.0)} → ${it?.optDouble("b", 0.0)}",
                         fontSize = 10.5.sp, color = ZxT.PriLite,
                         modifier = Modifier.weight(1f))
                } else
                    Text("点线看变化量", fontSize = 10.sp, color = ZxT.Text3,
                         modifier = Modifier.weight(1f))
                Text(bLab, fontSize = 9.sp, color = ZxT.Text3, textAlign = TextAlign.End)
            }
            Text("只有两个时间点时，斜率图比折线图清楚得多——折线图那个「中间」是假的",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 4.dp))
        }
    }

    /* ══════════ zx_dumbbell 哑铃图 ══════════ */
    @Composable
    fun dumbbell(o: JSONObject, ctx: RenderContext) {
        val items = o.optJSONArray("items") ?: return
        val n = minOf(items.length(), 8)
        var sel by remember { mutableIntStateOf(-1) }
        val all = (0 until n).flatMap { i ->
            val it = items.optJSONObject(i) ?: JSONObject()
            listOf(it.optDouble("a", 0.0), it.optDouble("b", 0.0))
        }
        val gLo = (all.minOrNull() ?: 0.0) - 1
        val gHi = (all.maxOrNull() ?: 1.0) + 1
        val span = (gHi - gLo).coerceAtLeast(1e-6)

        Column(Modifier.fillMaxWidth()) {
            for (i in 0 until n) {
                val it = items.optJSONObject(i) ?: continue
                val av = it.optDouble("a", 0.0); val bv = it.optDouble("b", 0.0)
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (sel == i) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        sel = i
                        ctx.callTool("zx_select", mapOf(
                            "choice" to it.optString("n", "$i"),
                            "gap" to (bv - av).toString(), "source" to "zx_dumbbell"))
                    }.padding(5.dp, 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(it.optString("n", ""), fontSize = 10.5.sp, color = ZxT.Text2,
                         modifier = Modifier.width(52.dp), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    Box(Modifier.weight(1f).height(20.dp)) {
                        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                            val y = size.height / 2
                            val xa = ((av - gLo) / span).toFloat() * size.width
                            val xb = ((bv - gLo) / span).toFloat() * size.width
                            drawLine(if (sel == i) PAL[0] else PAL[0].copy(.45f),
                                     Offset(xa, y), Offset(xb, y), 2.5f)
                            drawCircle(PAL[1], 5f, Offset(xa, y))
                            drawCircle(PAL[0], 5f, Offset(xb, y))
                        }
                    }
                    Text("%+.1f".format(bv - av), fontSize = 9.5.sp,
                         color = if (bv >= av) ZxT.Ok else ZxT.Err,
                         fontFamily = ZxT.Mono, modifier = Modifier.width(40.dp),
                         textAlign = TextAlign.End)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(PAL[1]))
                Spacer(Modifier.width(4.dp))
                Text(o.optString("a", "前"), fontSize = 9.sp, color = ZxT.Text3)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.size(7.dp).clip(CircleShape).background(PAL[0]))
                Spacer(Modifier.width(4.dp))
                Text(o.optString("b", "后"), fontSize = 9.sp, color = ZxT.Text3)
                Spacer(Modifier.weight(1f))
                Text("相邻项差值一目了然——用两组柱状图看要来回扫",
                     fontSize = 9.sp, color = ZxT.Text3)
            }
        }
    }

    /* ══════════ zx_waffle 华夫饼 ══════════ */
    @Composable
    fun waffle(o: JSONObject, ctx: RenderContext) {
        val parts = o.optJSONArray("parts") ?: return
        val n = minOf(parts.length(), 5)
        val vals = (0 until n).map { parts.optJSONObject(it)?.optInt("v", 0) ?: 0 }
        val total = vals.sum().coerceAtLeast(1)
        val cols = o.optInt("cols", 10).coerceIn(2, 12)
        val cells = cols * ((total + cols - 1) / cols).coerceIn(1, 10)
        var sel by remember { mutableIntStateOf(-1) }
        // 每格代表多少：不标出来，用户会误以为一格一人
        val per = o.optDouble("per", Double.NaN)

        Column(Modifier.fillMaxWidth()) {
            val rowsCnt = (cells + cols - 1) / cols
            for (r in 0 until rowsCnt) {
                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    for (c in 0 until cols) {
                        val idx = r * cols + c
                        var owner = -1
                        var acc = 0
                        for (k in 0 until n) {
                            acc += vals[k]
                            if (idx * total / cells < acc) { owner = k; break }
                        }
                        Box(Modifier.weight(1f).aspectRatio(1f)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (owner < 0) ZxT.Surface3
                                        else PAL[owner % PAL.size]
                                            .copy(if (sel == owner) 1f else .72f)))
                    }
                }
            }
            Spacer(Modifier.height(7.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                for (i in 0 until n) {
                    val p = parts.optJSONObject(i) ?: continue
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp))
                            .background(if (sel == i) PAL[i % PAL.size].copy(.25f) else ZxT.Surface2)
                            .clickable {
                                sel = i
                                ctx.callTool("zx_select", mapOf(
                                    "choice" to p.optString("n", "$i"),
                                    "pct" to (vals[i] * 100.0 / total).toString(),
                                    "source" to "zx_waffle"))
                            }.padding(7.dp, 3.dp)) {
                        Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp))
                            .background(PAL[i % PAL.size]))
                        Spacer(Modifier.width(4.dp))
                        Text("${p.optString("n", "")} ${vals[i] * 100 / total}%",
                             fontSize = 9.5.sp, color = ZxT.Text2, maxLines = 1)
                    }
                }
            }
            if (!per.isNaN())
                Text("每格 = $per（不标出来，用户会误以为一格一人）",
                     fontSize = 10.sp, color = ZxT.Text3,
                     modifier = Modifier.padding(top = 5.dp))
        }
    }

    /* ══════════ zx_ridgeline 山脊线 ══════════ */
    @Composable
    fun ridgeline(o: JSONObject, ctx: RenderContext) {
        val rows = o.optJSONArray("rows") ?: return
        val n = minOf(rows.length(), 6)
        var sel by remember { mutableIntStateOf(-1) }
        val gHi = (0 until n).maxOf { r ->
            val arr = rows.optJSONObject(r)?.optJSONArray("v")
            (0 until (arr?.length() ?: 0)).maxOf { arr?.optDouble(it, 0.0) ?: 0.0 }
        }.coerceAtLeast(1e-6)

        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(20.dp * n)) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val rh = size.height / n
                    // 从后往前画，让后面的山脊被前面的压住（这是山脊线的特征）
                    for (i in n - 1 downTo 0) {
                        val r = rows.optJSONObject(i) ?: continue
                        val arr = r.optJSONArray("v") ?: continue
                        val cnt = arr.length()
                        val path = Path()
                        val base = (i + 1) * rh
                        path.moveTo(0f, base)
                        for (k in 0 until cnt) {
                            val x = k.toFloat() / (cnt - 1).coerceAtLeast(1) * size.width
                            val v = arr.optDouble(k, 0.0)
                            val y = base - (v / gHi).toFloat() * rh * 1.9f
                            path.lineTo(x, y)
                        }
                        path.lineTo(size.width, base)
                        path.close()
                        drawPath(path, PAL[i % PAL.size].copy(if (sel == i) .85f else .42f))
                        drawPath(path, PAL[i % PAL.size], style = Stroke(1.4f))
                    }
                }
                Column(Modifier.fillMaxWidth()) {
                    for (i in 0 until n) {
                        val r = rows.optJSONObject(i) ?: continue
                        Row(Modifier.fillMaxWidth().height(20.dp)
                            .clickable {
                                sel = i
                                ctx.callTool("zx_select", mapOf(
                                    "choice" to r.optString("n", "$i"), "source" to "zx_ridgeline"))
                            }.padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(r.optString("n", ""), fontSize = 9.sp,
                                 color = if (sel == i) ZxT.Text else ZxT.Text3)
                        }
                    }
                }
            }
            Text("多条分布叠在一起看迁移——换成并排小图，对比就靠记忆了",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 5.dp))
        }
    }

    /* ══════════ zx_steparea 阶梯面积 ══════════ */
    @Composable
    fun steparea(o: JSONObject, ctx: RenderContext) {
        val pts = o.optJSONArray("pts") ?: return
        val n = minOf(pts.length(), 24)
        val vals = (0 until n).map { pts.optDouble(it, 0.0) }
        var sel by remember { mutableIntStateOf(-1) }
        val gHi = vals.maxOrNull()?.coerceAtLeast(1e-6) ?: 1.0

        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(96.dp)) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val step = size.width / (n - 1).coerceAtLeast(1)
                    fun y(v: Double) = size.height - (v / gHi).toFloat() * size.height
                    val path = Path()
                    val fill = Path()
                    var px = 0f; var py = y(vals[0])
                    path.moveTo(px, py); fill.moveTo(px, size.height)
                    fill.lineTo(px, py)
                    for (i in 1 until n) {
                        val x = i * step; val yy = y(vals[i])
                        // 阶梯：先横后竖，表达"这个值保持到下一个点才变"
                        path.lineTo(x, py); path.lineTo(x, yy)
                        fill.lineTo(x, py); fill.lineTo(x, yy)
                        px = x; py = yy
                    }
                    fill.lineTo(size.width, size.height); fill.close()
                    drawPath(fill, Brush.verticalGradient(
                        listOf(PAL[0].copy(.42f), PAL[0].copy(.05f))))
                    drawPath(path, PAL[0], style = Stroke(1.8f))
                    vals.forEachIndexed { i, v ->
                        drawCircle(if (sel == i) ZxT.Text else PAL[0],
                                   if (sel == i) 4.5f else 2.6f, Offset(i * step, y(v)))
                    }
                }
                Row(Modifier.fillMaxWidth().height(96.dp)) {
                    (0 until n).forEach { i ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = i
                            ctx.callTool("zx_select", mapOf(
                                "index" to i.toString(), "v" to vals[i].toString(),
                                "source" to "zx_steparea"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text("峰值 %.1f".format(gHi), fontSize = 10.sp, color = ZxT.Text3)
                Spacer(Modifier.weight(1f))
                if (sel >= 0) Text("第 ${sel + 1} 点：${vals[sel]}", fontSize = 10.5.sp,
                                   color = ZxT.PriLite)
            }
            Text("阶梯而非斜线——表达「这个值保持到下一个采样点才变」，" +
                 "用折线画会暗示中间有过渡，那是不存在的",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 4.dp))
        }
    }
}
