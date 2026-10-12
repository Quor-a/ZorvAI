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
import kotlin.random.Random

/**
 * 第十三批组件（12 个）—— 对照 / 诊断 / 协同。
 *
 * 这批的共同点是**它们都用于处理"问题"**，
 * 而不是展示"结果"：
 *
 *   一、对照。并排差异、基线对比——
 *       回答"改了什么"和"离目标差多少"。
 *       已有的 zx_diff 是单列逐块接受，
 *       并排才是读 diff 最自然的方式。
 *
 *   二、诊断。日志流、控制台、依赖图——
 *       回答"哪里出问题了"。
 *       日志用 terminal 看会糊成一片，
 *       因为没有级别着色和过滤。
 *
 *   三、协同。冲突解决、活动流——
 *       回答"谁动了什么"。
 *       这是多端同步之后必然要有的界面。
 *
 * zx_diffview / zx_baseline / zx_logview / zx_console
 * zx_depgraph / zx_datamap / zx_conflict / zx_activity
 * zx_timerange / zx_gridform / zx_scorecard / zx_waveedit
 *
 * 贯穿这批的一条：**问题的呈现必须能定位**。
 * 日志没有级别、diff 没有行号、依赖图没有高亮传递路径——
 * 那都只是"看起来很专业"，实际找不到问题在哪。
 */
object ZxRender13 {

    private val PAL = listOf(
        Color(0xFF5046E4), Color(0xFF3FBF7F), Color(0xFFE8A33D),
        Color(0xFFE5544B), Color(0xFF4A9BE8), Color(0xFF8E86FF)
    )
    private val LVC = mapOf(
        "err" to Color(0xFFE5544B), "warn" to Color(0xFFE8A33D),
        "info" to Color(0xFF4A9BE8), "ok" to Color(0xFF3FBF7F),
        "dbg" to Color(0xFF7A8496)
    )

    /* ══════════ zx_diffview 并排代码差异 ══════════ */
    @Composable
    fun diffview(o: JSONObject, ctx: RenderContext) {
        val lines = o.optJSONArray("lines") ?: return
        val n = minOf(lines.length(), 14)
        val acc = remember { mutableStateMapOf<Int, Boolean>() }

        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                Text("改前", fontSize = 9.sp, color = ZxT.Text3, modifier = Modifier.weight(1f))
                Text("改后", fontSize = 9.sp, color = ZxT.Text3, modifier = Modifier.weight(1f))
            }
            for (i in 0 until n) {
                val l = lines.optJSONObject(i) ?: continue
                val kind = l.optString("t", "same")
                val a = l.optString("a"); val b = l.optString("b")
                val on = acc[i] ?: false
                val bg = when (kind) {
                    "add" -> ZxT.Ok.copy(.1f)
                    "del" -> ZxT.Err.copy(.1f)
                    "mod" -> ZxT.Warn.copy(.1f)
                    else -> Color.Transparent
                }
                Row(Modifier.fillMaxWidth()
                    .background(bg)
                    .clickable(enabled = kind != "same") {
                        acc[i] = !on
                        ctx.callTool("zx_diff_apply", mapOf(
                            "accepted" to acc.filter { it.value }.keys.joinToString(","),
                            "line" to i.toString()))
                    }) {
                    // 行号：没有它无法定位改动在哪
                    Text(l.optString("no", "${i + 1}"), fontSize = 8.sp, color = ZxT.Text3,
                         fontFamily = ZxT.Mono, modifier = Modifier.width(20.dp))
                    Text(if (kind == "del" || kind == "mod") a else "",
                         fontSize = 10.sp, color = if (kind == "del") ZxT.Err else ZxT.Text2,
                         fontFamily = ZxT.Mono, modifier = Modifier.weight(1f),
                         maxLines = 1, overflow = TextOverflow.Clip)
                    Text(if (kind == "add" || kind == "mod") b else a,
                         fontSize = 10.sp, color = if (kind == "add") ZxT.Ok else ZxT.Text2,
                         fontFamily = ZxT.Mono, modifier = Modifier.weight(1f),
                         maxLines = 1, overflow = TextOverflow.Clip)
                    Text(if (kind == "same") " " else (if (on) "✓" else "·"),
                         fontSize = 10.sp, color = if (on) ZxT.Ok else ZxT.Text3,
                         modifier = Modifier.width(14.dp))
                }
            }
            Text("已接受 ${acc.count { it.value }}/${(0 until n).count {lines.optJSONObject(it)?.optString("t", "same") != "same" }} 处",
                 fontSize = 10.sp, color = ZxT.Text3,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_baseline 基线对比 ══════════ */
    @Composable
    fun baseline(o: JSONObject, ctx: RenderContext) {
        val items = o.optJSONArray("items") ?: return
        val n = minOf(items.length(), 6)
        var sel by remember { mutableIntStateOf(-1) }

        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(bottom = 5.dp)) {
                Text("", modifier = Modifier.width(56.dp))
                listOf("基线", "当前", "目标").forEachIndexed { i, t ->
                    Text(t, fontSize = 8.5.sp,
                         color = if (i == 1) ZxT.Text2 else ZxT.Text3,
                         modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                }
            }
            for (i in 0 until n) {
                val it = items.optJSONObject(i) ?: continue
                val base = it.optDouble("base", 0.0)
                val cur = it.optDouble("cur", 0.0)
                val tgt = it.optDouble("tgt", base)
                val lower = it.optBoolean("lower", false)
                // 达标判定：lowerBetter 的方向是反的，不能一律"涨绿跌红"
                val good = if (lower) cur <= tgt else cur >= tgt
                val mx = maxOf(base, cur, tgt, 0.001)
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (sel == i) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        sel = i
                        ctx.callTool("zx_select", mapOf(
                            "choice" to it.optString("n", "$i"),
                            "cur" to cur.toString(), "tgt" to tgt.toString(),
                            "source" to "zx_baseline"))
                    }.padding(5.dp, 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.width(56.dp)) {
                        Text(it.optString("n", ""), fontSize = 10.5.sp, color = ZxT.Text2,
                             maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(5.dp).clip(CircleShape)
                                .background(if (good) ZxT.Ok else ZxT.Err))
                            Spacer(Modifier.width(3.dp))
                            Text(if (good) "达标" else "未达", fontSize = 8.sp,
                                 color = if (good) ZxT.Ok else ZxT.Err)
                        }
                    }
                    Bar(base / mx, ZxT.Text3.copy(.45f))
                    Bar(cur / mx, if (good) ZxT.Ok else ZxT.Warn)
                    Bar(tgt / mx, ZxT.Primary.copy(.7f))
                }
                // 目标刻线：没有它就看不出差多少
                Box(Modifier.fillMaxWidth().height(1.dp).background(ZxT.Line))
            }
        }
    }

    @Composable
    private fun RowScope.Bar(v: Double, col: Color) {
        Box(Modifier.weight(1f).height(14.dp), contentAlignment = Alignment.CenterEnd) {
            Box(Modifier.fillMaxWidth(v.toFloat().coerceIn(0f, 1f)).height(9.dp)
                .clip(RoundedCornerShape(3.dp)).background(col))
            Text("%.0f".format(v * 100), fontSize = 7.5.sp, color = ZxT.Text3,
                 modifier = Modifier.padding(end = 4.dp))
        }
    }

    /* ══════════ zx_logview 日志流 ══════════ */
    @Composable
    fun logview(o: JSONObject, ctx: RenderContext) {
        val rows = o.optJSONArray("rows") ?: return
        val n = minOf(rows.length(), 30)
        var filter by remember { mutableStateOf("all") }
        var sel by remember { mutableIntStateOf(-1) }
        val levels = listOf("all", "err", "warn", "info", "ok", "dbg")

        Column(Modifier.fillMaxWidth()) {
            // 过滤芯片：日志没有过滤就只能靠眼睛扫
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                levels.forEach { lv ->
                    val on = filter == lv
                    Text(if (lv == "all") "全部" else lv.uppercase(),
                         fontSize = 9.5.sp,
                         color = if (on) ZxT.PriLite else ZxT.Text3,
                         modifier = Modifier.clip(RoundedCornerShape(10.dp))
                             .background(if (on) ZxT.Primary.copy(.2f) else ZxT.Surface2)
                             .clickable { filter = lv; sel = -1 }
                             .padding(7.dp, 3.dp))
                }
            }
            Spacer(Modifier.height(5.dp))
            var shown = 0
            for (i in 0 until n) {
                val r = rows.optJSONObject(i) ?: continue
                val lv = r.optString("lv", "info")
                if (filter != "all" && lv != filter) continue
                shown++
                val col = LVC[lv] ?: ZxT.Text3
                Row(Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (sel == i) col.copy(.12f) else Color.Transparent)
                    .clickable {
                        sel = i
                        ctx.callTool("zx_select", mapOf("line" to i.toString(),
                                                        "lv" to lv, "source" to "zx_logview"))
                    }.padding(5.dp, 2.dp)) {
                    Text(r.optString("t", ""), fontSize = 8.5.sp, color = ZxT.Text3,
                         fontFamily = ZxT.Mono, modifier = Modifier.width(48.dp))
                    Box(Modifier.width(34.dp)) {
                        Text(lv.uppercase(), fontSize = 8.sp, color = col,
                             fontFamily = ZxT.Mono, fontWeight = FontWeight.SemiBold)
                    }
                    Text(r.optString("v", ""), fontSize = 10.sp, color = ZxT.Text2,
                         fontFamily = ZxT.Mono, modifier = Modifier.weight(1f),
                         maxLines = if (sel == i) 4 else 1,
                         overflow = TextOverflow.Ellipsis)
                }
            }
            Text("显示 $shown / $n 条", fontSize = 10.sp, color = ZxT.Text3,
                 modifier = Modifier.padding(top = 5.dp))
        }
    }

    /* ══════════ zx_console 交互式控制台 ══════════ */
    @Composable
    fun console(o: JSONObject, ctx: RenderContext) {
        val hist = o.optJSONArray("hist")
        val lines = remember {
            mutableStateListOf<Pair<String, String>>().apply {
                if (hist != null) for (i in 0 until minOf(hist.length(), 8))
                    add("out" to hist.optString(i))
            }
        }
        var input by remember { mutableStateOf("") }
        var hi by remember { mutableIntStateOf(-1) }

        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(8.dp)).background(ZxT.Surface)
                .padding(9.dp)) {
                Column {
                    lines.forEach { (kind, t) ->
                        val col = if (kind == "in") ZxT.PriLite
                                  else if (t.startsWith("err")) ZxT.Err else ZxT.Text2
                        Text((if (kind == "in") "$ " else "  ") + t,
                             fontSize = 10.sp, color = col, fontFamily = ZxT.Mono,
                             lineHeight = 15.sp)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$", fontSize = 11.sp, color = ZxT.PriLite, fontFamily = ZxT.Mono)
                Spacer(Modifier.width(7.dp))
                androidx.compose.material3.TextField(
                    value = input, onValueChange = { input = it },
                    placeholder = { Text("输入命令，↑ 取历史", fontSize = 10.5.sp, color = ZxT.Text3) },
                    modifier = Modifier.weight(1f).height(34.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.5.sp, color = ZxT.Text,
                                                                  fontFamily = ZxT.Mono),
                    singleLine = true,
                    colors = androidx.compose.material3.TextFieldDefaults.colors(
                        focusedContainerColor = ZxT.Surface2,
                        unfocusedContainerColor = ZxT.Surface2,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = ZxT.Primary))
                Spacer(Modifier.width(6.dp))
                // ↑ 取历史：控制台没有历史就没有交互价值
                Text("↑", fontSize = 12.sp, color = ZxT.Text3,
                     modifier = Modifier.clickable {
                         val ins = lines.mapIndexedNotNull { i, p ->
                             if (p.first == "in") i to p.second else null }
                         if (ins.isNotEmpty()) {
                             hi = if (hi <= 0) ins.last().first
                                  else (ins.firstOrNull { it.first < hi }?.first ?: ins.last().first)
                             input = lines[hi].second
                         }
                     })
            }
        }
    }

    /* ══════════ zx_depgraph 依赖关系图 ══════════ */
    @Composable
    fun depgraph(o: JSONObject, ctx: RenderContext) {
        val nodes = o.optJSONArray("nodes") ?: return
        val edges = o.optJSONArray("edges")
        val n = minOf(nodes.length(), 10)
        var sel by remember { mutableIntStateOf(-1) }
        // 传递依赖：选中一个节点要高亮它全部上游，否则看不出影响面
        val upstream = (0 until n).map { i ->
            val set = mutableSetOf<Int>()
            var frontier = listOf(i)
            var guard = 0
            while (frontier.isNotEmpty() && guard++ < 12) {
                val next = mutableListOf<Int>()
                for (e in 0 until (edges?.length() ?: 0)) {
                    val ed = edges!!.optJSONObject(e) ?: continue
                    if (ed.optInt("t") in frontier && ed.optInt("f") !in set) {
                        set.add(ed.optInt("f")); next.add(ed.optInt("f"))
                    }
                }
                frontier = next
            }
            set
        }

        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(112.dp)) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val cols = 3
                    val cw = size.width / (cols + 1)
                    val ch = size.height / ((n + cols - 1) / cols + 1)
                    fun pos(i: Int): Offset {
                        val r = i / cols; val c = i % cols
                        return Offset(cw * (c + 1), ch * (r + 1))
                    }
                    for (e in 0 until (edges?.length() ?: 0)) {
                        val ed = edges!!.optJSONObject(e) ?: continue
                        val f = ed.optInt("f"); val t = ed.optInt("t")
                        if (f >= n || t >= n) continue
                        val hot = sel >= 0 && (f == sel || t == sel || f in upstream[sel])
                        drawLine(if (hot) PAL[0] else ZxT.Line, pos(f), pos(t),
                                 if (hot) 2f else 1f)
                    }
                    for (i in 0 until n) {
                        val p = pos(i)
                        val hot = sel >= 0 && (i == sel || i in upstream[sel])
                        drawCircle(if (hot) PAL[0] else ZxT.Surface3, 9f, p)
                        drawCircle(if (hot) ZxT.Text else ZxT.Line, 9f, p,
                                   style = Stroke(1.2f))
                    }
                }
                for (i in 0 until n) {
                    val nd = nodes.optJSONObject(i) ?: continue
                    val r = i / 3; val c = i % 3
                    Text(nd.optString("n", ""), fontSize = 8.sp, color = ZxT.Text3,
                         maxLines = 1,
                         modifier = Modifier
                             .offset(x = (330 / 4 * (c + 1) - 30).dp,
                                     y = (112 / (((n + 2) / 3) + 1) * (r + 1) + 8).dp)
                             .width(60.dp), textAlign = TextAlign.Center)
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
                                     "upstream" to upstream[i].joinToString(","),
                                     "source" to "zx_depgraph"))
                             }.padding(8.dp, 4.dp))
                }
            }
            if (sel >= 0)
                Text("上游 ${upstream[sel].size} 个：改动 ${nodes.optJSONObject(sel)?.optString("n")} 会影响它们",
                     fontSize = 10.sp, color = ZxT.Text3,
                     modifier = Modifier.padding(top = 5.dp))
        }
    }

    /* ══════════ zx_datamap 数据地图 ══════════ */
    @Composable
    fun datamap(o: JSONObject, ctx: RenderContext) {
        val regions = o.optJSONArray("regions") ?: return
        val n = minOf(regions.length(), 12)
        var sel by remember { mutableIntStateOf(-1) }
        val mx = (0 until n).maxOf { regions.optJSONObject(it)?.optDouble("v", 0.0) ?: 0.0 }
            .coerceAtLeast(0.001)

        Column(Modifier.fillMaxWidth()) {
            // 用矩形网格近似地图——气泡里画真实地图不划算且要数据
            val cols = 4
            for (r in 0 until (n + cols - 1) / cols) {
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (c in 0 until cols) {
                        val i = r * cols + c
                        if (i >= n) { Box(Modifier.weight(1f)); continue }
                        val rg = regions.optJSONObject(i) ?: continue
                        val t = (rg.optDouble("v", 0.0) / mx).coerceIn(0.0, 1.0).toFloat()
                        Box(Modifier.weight(1f).height(38.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(Color(red = .17f + .6f * t, green = .20f + .5f * t,
                                              blue = .30f + .55f * t))
                            .border(if (sel == i) 1.5.dp else 0.dp, ZxT.Text,
                                    RoundedCornerShape(5.dp))
                            .clickable {
                                sel = i
                                ctx.callTool("zx_select", mapOf(
                                    "choice" to rg.optString("n", "$i"),
                                    "v" to rg.optDouble("v", 0.0).toString(),
                                    "source" to "zx_datamap"))
                            }.padding(4.dp)) {
                            Column {
                                Text(rg.optString("n", ""), fontSize = 8.5.sp, color = Color.White,
                                     maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.weight(1f))
                                val v = rg.optDouble("v", 0.0)
                                Text(if (v == kotlin.math.floor(v)) v.toLong().toString()
                                     else "%.1f".format(v),
                                     fontSize = 9.5.sp, color = Color.White,
                                     fontFamily = ZxT.Mono, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("低", fontSize = 9.sp, color = ZxT.Text3)
                (0..4).forEach { k ->
                    Spacer(Modifier.width(3.dp))
                    val t = k / 4f
                    Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp))
                        .background(Color(red = .17f + .6f * t, green = .20f + .5f * t,
                                          blue = .30f + .55f * t)))
                }
                Spacer(Modifier.width(4.dp))
                Text("高", fontSize = 9.sp, color = ZxT.Text3)
            }
        }
    }

    /* ══════════ zx_conflict 冲突解决卡 ══════════ */
    @Composable
    fun conflict(o: JSONObject, ctx: RenderContext) {
        val items = o.optJSONArray("items") ?: return
        val n = minOf(items.length(), 6)
        val pick = remember { mutableStateMapOf<Int, String>() }   // i → LOCAL/REMOTE/MANUAL
        val manual = remember { mutableStateMapOf<Int, String>() }

        Column(Modifier.fillMaxWidth()) {
            Text("预览 ${o.optInt("n", n)} 处冲突（未修改任何状态）",
                 fontSize = 10.5.sp, color = ZxT.Warn,
                 modifier = Modifier.padding(bottom = 6.dp))
            for (i in 0 until n) {
                val it = items.optJSONObject(i) ?: continue
                val sev = it.optString("sev", "SOFT")
                val cur = pick[i]
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(ZxT.Surface2)
                    .border(1.dp, if (cur == null) ZxT.Warn.copy(.5f) else ZxT.Line,
                            RoundedCornerShape(8.dp))
                    .padding(9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(it.optString("label", it.optString("path")),
                             fontSize = 11.sp, color = ZxT.Text,
                             modifier = Modifier.weight(1f), maxLines = 1,
                             overflow = TextOverflow.Ellipsis)
                        if (sev != "SOFT")
                            Text(if (sev == "SHAPE") "结构冲突" else "删除 vs 修改",
                                 fontSize = 8.5.sp, color = ZxT.Err,
                                 modifier = Modifier.clip(RoundedCornerShape(4.dp))
                                     .background(ZxT.Err.copy(.15f)).padding(5.dp, 2.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                    // 三组选项：只给「确定/取消」等于没给选
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Opt("本地 ${it.optString("local")}", cur == "LOCAL") {
                            pick[i] = "LOCAL"
                            ctx.callTool("zx_select", mapOf("side" to "LOCAL",
                                                            "path" to it.optString("path")))
                        }
                        Opt("远端 ${it.optString("remote")}", cur == "REMOTE") {
                            pick[i] = "REMOTE"
                            ctx.callTool("zx_select", mapOf("side" to "REMOTE",
                                                            "path" to it.optString("path")))
                        }
                    }
                    Spacer(Modifier.height(5.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.TextField(
                            value = manual[i] ?: "",
                            onValueChange = { manual[i] = it; pick[i] = "MANUAL" },
                            placeholder = { Text("或手工填一个新值", fontSize = 10.sp,
                                                 color = ZxT.Text3) },
                            modifier = Modifier.weight(1f).height(34.dp),
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = ZxT.Text),
                            singleLine = true,
                            colors = androidx.compose.material3.TextFieldDefaults.colors(
                                focusedContainerColor = ZxT.Surface3,
                                unfocusedContainerColor = ZxT.Surface3,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                cursorColor = ZxT.Primary))
                    }
                }
            }
            Text("已解决 ${pick.size}/$n · 解决后可撤销（否则「解决冲突」成了不可逆操作）",
                 fontSize = 10.sp, color = ZxT.Text3,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    @Composable
    private fun Opt(text: String, on: Boolean, onClick: () -> Unit) {
        Row(Modifier.clip(RoundedCornerShape(7.dp))
            .background(if (on) ZxT.Primary.copy(.2f) else ZxT.Surface3)
            .border(1.dp, if (on) ZxT.Primary else ZxT.Line, RoundedCornerShape(7.dp))
            .clickable(onClick = onClick).padding(8.dp, 5.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(if (on) "●" else "○", fontSize = 8.sp, color = ZxT.PriLite)
            Spacer(Modifier.width(5.dp))
            Text(text, fontSize = 10.5.sp, color = if (on) ZxT.PriLite else ZxT.Text2,
                 maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    /* ══════════ zx_activity 活动流 ══════════ */
    @Composable
    fun activity(o: JSONObject, ctx: RenderContext) {
        val items = o.optJSONArray("items") ?: return
        val n = minOf(items.length(), 10)
        var sel by remember { mutableIntStateOf(-1) }

        Column(Modifier.fillMaxWidth()) {
            for (i in 0 until n) {
                val it = items.optJSONObject(i) ?: continue
                val kind = it.optString("k", "edit")
                val col = when (kind) {
                    "add" -> ZxT.Ok; "del" -> ZxT.Err
                    "comment" -> PAL[4]; else -> ZxT.Warn
                }
                Row(Modifier.fillMaxWidth()) {
                    // 时间线：竖线 + 圆点，没有它只是一条列表
                    Column(Modifier.width(18.dp),
                           horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(col))
                        if (i < n - 1)
                            Box(Modifier.width(1.dp).height(20.dp).background(ZxT.Line))
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (sel == i) col.copy(.1f) else Color.Transparent)
                        .clickable {
                            sel = i
                            ctx.callTool("zx_select", mapOf(
                                "choice" to it.optString("k", "$i"),
                                "who" to it.optString("who"), "source" to "zx_activity"))
                        }.padding(5.dp, 3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(it.optString("who", ""), fontSize = 10.5.sp,
                                 fontWeight = FontWeight.SemiBold, color = ZxT.Text)
                            Spacer(Modifier.width(5.dp))
                            Text(when (kind) { "add" -> "新增"; "del" -> "删除"
                                               "comment" -> "评论"; else -> "修改" },
                                 fontSize = 9.5.sp, color = col)
                            Spacer(Modifier.weight(1f))
                            Text(it.optString("t", ""), fontSize = 9.sp, color = ZxT.Text3)
                        }
                        Text(it.optString("v", ""), fontSize = 10.5.sp, color = ZxT.Text2,
                             lineHeight = 15.sp, maxLines = if (sel == i) 4 else 2,
                             overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }

    /* ══════════ zx_timerange 时间区间选择 ══════════ */
    @Composable
    fun timerange(o: JSONObject, ctx: RenderContext) {
        val total = maxOf(1, o.optInt("total", 24))
        var a by remember { mutableFloatStateOf(o.optDouble("a", 0.0).toFloat()) }
        var b by remember { mutableFloatStateOf(o.optDouble("b", 1.0).toFloat()) }
        var drag by remember { mutableIntStateOf(-1) }

        fun fmt(t: Float): String {
            val h = (t * total).toInt().coerceIn(0, total)
            return if (total <= 24) "%02d:00".format(h) else h.toString()
        }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(fmt(a), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                     color = ZxT.PriLite, fontFamily = ZxT.Mono)
                Spacer(Modifier.width(6.dp))
                Text("→", fontSize = 11.sp, color = ZxT.Text3)
                Spacer(Modifier.width(6.dp))
                Text(fmt(b), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                     color = ZxT.PriLite, fontFamily = ZxT.Mono)
                Spacer(Modifier.weight(1f))
                Text("${((b - a) * total).toInt()} 单位", fontSize = 10.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(34.dp)) {
                // 轨道
                Box(Modifier.fillMaxWidth().height(8.dp).align(Alignment.Center)
                    .clip(RoundedCornerShape(4.dp)).background(ZxT.Surface3))
                // 选中段
                // 选中段：用 offset + 宽度，不用 padding（padding 会挤压渐变）
                Box(Modifier.align(Alignment.CenterStart)
                    .offset(x = (a * 300).toInt().dp)
                    .width(((b - a) * 300).toInt().dp).height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Brush.horizontalGradient(listOf(PAL[0], PAL[5]))))
                listOf(0 to a, 1 to b).forEach { (which, v) ->
                    Box(Modifier.align(Alignment.CenterStart)
                        .offset(x = (v * 300).toInt().dp)
                        .size(18.dp).clip(CircleShape)
                        .background(ZxT.Text)
                        .border(2.dp, ZxT.Surface, CircleShape))
                }
            }
            // 整段拖动 vs 单端拖动：只做单端的话调范围很累
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf("−1h" to -1f, "+1h" to 1f, "整段左移" to -99f).forEach { (t, d) ->
                    Text(t, fontSize = 10.sp, color = ZxT.Text3,
                         modifier = Modifier.clip(RoundedCornerShape(8.dp))
                             .background(ZxT.Surface2)
                             .clickable {
                                 if (d == -99f) {
                                     val w = b - a
                                     a = (a - 1f / total).coerceIn(0f, 1f - w)
                                     b = a + w
                                 } else {
                                     val w = b - a
                                     b = (b + d / total).coerceIn(a + 1f / total, 1f)
                                     a = (b - w).coerceIn(0f, b)
                                 }
                                 ctx.callTool("zx_form_submit", mapOf("payload" to
                                     JSONObject().put("a", a).put("b", b).toString()))
                             }.padding(8.dp, 4.dp))
                }
            }
        }
    }

    /* ══════════ zx_gridform 栅格表单 ══════════ */
    @Composable
    fun gridform(o: JSONObject, ctx: RenderContext) {
        val fields = o.optJSONArray("fields") ?: return
        val n = minOf(fields.length(), 8)
        val cols = o.optInt("cols", 2).coerceIn(1, 3)
        val vals = remember { mutableStateMapOf<Int, String>() }

        Column(Modifier.fillMaxWidth()) {
            for (r in 0 until (n + cols - 1) / cols) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (c in 0 until cols) {
                        val i = r * cols + c
                        if (i >= n) { Box(Modifier.weight(1f)); continue }
                        val f = fields.optJSONObject(i) ?: continue
                        Column(Modifier.weight(1f).padding(vertical = 3.dp)) {
                            Text(f.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text3,
                                 maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(3.dp))
                            androidx.compose.material3.TextField(
                                value = vals[i] ?: f.optString("v", ""),
                                onValueChange = { vals[i] = it },
                                placeholder = { Text(f.optString("hint", ""), fontSize = 10.sp,
                                                     color = ZxT.Text3) },
                                modifier = Modifier.fillMaxWidth().height(36.dp),
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = ZxT.Text),
                                singleLine = true,
                                colors = androidx.compose.material3.TextFieldDefaults.colors(
                                    focusedContainerColor = ZxT.Surface2,
                                    unfocusedContainerColor = ZxT.Surface2,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    cursorColor = ZxT.Primary))
                        }
                    }
                }
            }
            Spacer(Modifier.height(7.dp))
            Box(Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(8.dp)).background(ZxT.Primary)
                .clickable {
                    val p = JSONObject()
                    for (i in 0 until n) {
                        val f = fields.optJSONObject(i) ?: continue
                        p.put(f.optString("k", "$i"), vals[i] ?: f.optString("v", ""))
                    }
                    ctx.callTool("zx_form_submit", mapOf("payload" to p.toString()))
                }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                Text(o.optString("btn", "提交"), fontSize = 11.5.sp, color = Color.White)
            }
        }
    }

    /* ══════════ zx_scorecard 评分卡 ══════════ */
    @Composable
    fun scorecard(o: JSONObject, ctx: RenderContext) {
        val items = o.optJSONArray("items") ?: return
        val n = minOf(items.length(), 6)
        val sc = remember { mutableStateMapOf<Int, Int>() }
        var sel by remember { mutableIntStateOf(-1) }

        val total = (0 until n).sumOf { sc[it] ?: items.optJSONObject(it)?.optInt("v", 0) ?: 0 }
        val wsum = (0 until n).sumOf {
            items.optJSONObject(it)?.optDouble("w", 1.0) ?: 1.0 }.coerceAtLeast(0.001)
        val weighted = (0 until n).sumOf { i ->
            val w = items.optJSONObject(i)?.optDouble("w", 1.0) ?: 1.0
            (sc[i] ?: items.optJSONObject(i)?.optInt("v", 0) ?: 0) * w
        } / wsum

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "评分"), fontSize = 12.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("加权 %.1f".format(weighted), fontSize = 15.sp,
                     fontWeight = FontWeight.Bold, color = ZxT.Primary,
                     fontFamily = ZxT.Mono)
            }
            Spacer(Modifier.height(6.dp))
            for (i in 0 until n) {
                val it = items.optJSONObject(i) ?: continue
                val max = it.optInt("max", 5)
                val v = sc[i] ?: it.optInt("v", 0)
                val w = it.optDouble("w", 1.0)
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (sel == i) ZxT.Surface2 else Color.Transparent)
                    .clickable { sel = i }.padding(5.dp, 3.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(it.optString("n", ""), fontSize = 10.5.sp, color = ZxT.Text2,
                             maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (w != 1.0)
                            Text("权重 $w", fontSize = 8.5.sp, color = ZxT.Text3)
                    }
                    // 权重不显示的话，总分怎么算的用户完全不知道
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        for (k in 1..max) {
                            Box(Modifier.size(19.dp).clip(RoundedCornerShape(5.dp))
                                .background(if (k <= v) ZxT.Primary else ZxT.Surface3)
                                .clickable {
                                    sc[i] = if (sc[i] == k) k - 1 else k
                                    ctx.callTool("zx_select", mapOf(
                                        "choice" to it.optString("k", "$i"),
                                        "v" to (sc[i] ?: 0).toString(),
                                        "source" to "zx_scorecard"))
                                }, contentAlignment = Alignment.Center) {
                                if (k <= v) Text(k.toString(), fontSize = 9.sp, color = Color.White)
                            }
                        }
                    }
                }
            }
            Text("原始合计 $total · 加权 %.1f（权重已显示，否则用户不知道总分怎么来的）"
                     .format(weighted),
                 fontSize = 10.sp, color = ZxT.Text3,
                 modifier = Modifier.padding(top = 5.dp))
        }
    }

    /* ══════════ zx_waveedit 波形编辑 ══════════ */
    @Composable
    fun waveedit(o: JSONObject, ctx: RenderContext) {
        val peaks = o.optJSONArray("peaks")
        val cnt = 60
        val data = remember {
            mutableListOf<Float>().apply {
                if (peaks != null) for (i in 0 until minOf(peaks.length(), cnt))
                    add(peaks.optDouble(i, 0.0).toFloat())
                else repeat(cnt) { add(0.15f + Random.nextFloat() * 0.75f) }
            }
        }
        var selA by remember { mutableIntStateOf(-1) }
        var selB by remember { mutableIntStateOf(-1) }

        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(56.dp)) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val bw = size.width / data.size
                    data.forEachIndexed { i, v ->
                        val inSel = selA >= 0 && selB >= 0 && i in selA..selB
                        drawRect(
                            if (inSel) PAL[0] else ZxT.Surface3,
                            Offset(i * bw, size.height / 2 - v * size.height / 2),
                            Size(bw * .7f, v * size.height))
                    }
                }
                // 点击选区：波形上拖选太难，点击定两端更好点
                Row(Modifier.fillMaxWidth().height(56.dp)) {
                    data.indices.forEach { i ->
                        Box(Modifier.weight(1f).fillMaxHeight()
                            .clickable {
                                if (selA < 0 || (selB >= 0)) { selA = i; selB = -1 }
                                else if (i > selA) {
                                    selB = i
                                    ctx.callTool("zx_select", mapOf(
                                        "from" to selA.toString(), "to" to selB.toString(),
                                        "source" to "zx_waveedit"))
                                } else selA = i
                            })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (selA >= 0 && selB >= 0) {
                    Text("选区 ${selA}–${selB}（${selB - selA + 1} 帧）",
                         fontSize = 10.5.sp, color = ZxT.PriLite)
                    Spacer(Modifier.weight(1f))
                    Text("清除", fontSize = 10.5.sp, color = ZxT.Text3,
                         modifier = Modifier.clickable {
                             for (i in selA..selB) if (i < data.size) data[i] = 0.02f
                             ctx.callTool("zx_form_submit", mapOf("payload" to
                                 JSONObject().put("op", "mute")
                                     .put("from", selA).put("to", selB).toString()))
                         })
                } else {
                    Text("点两下选一段（第一次点起点，第二次点终点）",
                         fontSize = 10.5.sp, color = ZxT.Text3)
                }
            }
        }
    }
}
