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
 * 第十六批组件（12 个）—— 时间 / 对比 / 状态机。
 *
 * 前十五批把图表、输入、文本、关系都铺完了，
 * 剩下的是三类一直靠通用组件硬凑的：
 *
 *   一、**时间维度**。已有 timeline（事件流）、gantt（任务条）、
 *        calendar（月历），但**没有"一段时间里的强度"**——
 *        日历热力（calheat）是按天，脉冲是按任意粒度，
 *        而"值班表""营业时间""营业时段"这类
 *        「一周 × 一天若干段」的矩阵完全没有。
 *
 *   二、**对比**。已有 compare（并排列）、diff（文本 diff）、
 *        diffview（并排 diff）、baseline（基线对比），
 *        但都是"两个东西比"。
 *        「三个方案打分」「多版本 A/B」「谁赢了」没有专门组件。
 *
 *   三、**状态机**。已有 statelist（状态列表）、flow（流程图），
 *        但没有"当前状态 + 能走到哪 + 走过来的路径"这三者合一的东西。
 *        而 AI 执行多步任务时，用户最想知道的就是这个。
 *
 * 贯穿这批的一条：**别让用户自己算**。
 * 打分组要给出总分和排名，不然用户要在脑子里加权求和；
 * 状态机要高亮"能走到哪"，不然用户要猜下一步能点什么；
 * 营业时段要能看出"现在开不开"，不然用户要拿当前时间去比。
 */
object ZxRender16 {

    private val PAL = listOf(
        Color(0xFF5046E4), Color(0xFF3FBF7F), Color(0xFFE8A33D),
        Color(0xFFE5544B), Color(0xFF4A9BE8), Color(0xFF8E86FF)
    )

    /* ══════════ zx_weekgrid 周网格（营业时段 / 值班表） ══════════ */
    @Composable
    fun weekgrid(o: JSONObject, ctx: RenderContext) {
        val days = o.optJSONArray("days") ?: return
        val slots = o.optJSONArray("slots") ?: return
        val nd = minOf(days.length(), 7)
        val ns = minOf(slots.length(), 6)
        val cells = o.optJSONArray("cells")
        val nowD = o.optInt("nowD", -1)
        val nowS = o.optInt("nowS", -1)
        var sel by remember { mutableStateOf<Pair<Int, Int>?>(null) }

        Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Row(Modifier.padding(start = 34.dp)) {
                for (d in 0 until nd)
                    Text(days.optString(d), fontSize = 8.5.sp,
                         color = if (d == nowD) ZxT.PriLite else ZxT.Text3,
                         modifier = Modifier.width(42.dp), textAlign = TextAlign.Center,
                         maxLines = 1, overflow = TextOverflow.Clip)
            }
            for (s in 0 until ns) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(slots.optString(s), fontSize = 8.sp,
                         color = if (s == nowS) ZxT.PriLite else ZxT.Text3,
                         modifier = Modifier.width(34.dp), maxLines = 1,
                         overflow = TextOverflow.Clip)
                    for (d in 0 until nd) {
                        var on = false; var label = ""
                        for (k in 0 until (cells?.length() ?: 0)) {
                            val c = cells!!.optJSONObject(k) ?: continue
                            if (c.optInt("d") == d && c.optInt("s") == s) {
                                on = true; label = c.optString("t", ""); break
                            }
                        }
                        val picked = sel?.first == d && sel?.second == s
                        val isNow = d == nowD && s == nowS
                        Box(Modifier.width(42.dp).height(24.dp).padding(1.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (on) PAL[(d + s) % PAL.size].copy(.32f)
                                        else ZxT.Surface2)
                            .border(if (picked) 1.5.dp else if (isNow) 1.dp else 0.dp,
                                    if (picked) ZxT.Text else ZxT.PriLite,
                                    RoundedCornerShape(4.dp))
                            .clickable {
                                sel = d to s
                                ctx.callTool("zx_select", mapOf(
                                    "d" to d.toString(), "s" to s.toString(),
                                    "on" to on.toString(), "t" to label,
                                    "source" to "zx_weekgrid"))
                            }, contentAlignment = Alignment.Center) {
                            if (label.isNotBlank())
                                Text(label, fontSize = 8.sp, color = ZxT.Text2,
                                     maxLines = 1, overflow = TextOverflow.Clip)
                            else if (on) Box(Modifier.size(4.dp).clip(CircleShape)
                                .background(PAL[(d + s) % PAL.size]))
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 5.dp)) {
                // 现在开不开：不给出这个，用户要拿当前时间去比
                val open = sel?.let { (d, s) ->
                    (0 until (cells?.length() ?: 0)).any {
                        cells!!.optJSONObject(it)?.optInt("d") == d &&
                        cells!!.optJSONObject(it)?.optInt("s") == s }
                }
                if (sel != null && open != null)
                    Text("${days.optString(sel!!.first)} ${slots.optString(sel!!.second)}：" +
                         if (open) "开放" else "关闭",
                         fontSize = 10.5.sp,
                         color = if (open) ZxT.Ok else ZxT.Text3)
                else Text("点格子看该时段状态", fontSize = 10.sp, color = ZxT.Text3)
                Spacer(Modifier.weight(1f))
                if (nowD >= 0) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(ZxT.PriLite))
                    Spacer(Modifier.width(4.dp))
                    Text("当前时段", fontSize = 9.sp, color = ZxT.Text3)
                }
            }
        }
    }

    /* ══════════ zx_pulse 脉冲图（时间强度） ══════════ */
    @Composable
    fun pulse(o: JSONObject, ctx: RenderContext) {
        val items = o.optJSONArray("items") ?: return
        val n = minOf(items.length(), 30)
        val vals = (0 until n).map { items.optJSONObject(it)?.optDouble("v", 0.0) ?: 0.0 }
        var sel by remember { mutableIntStateOf(-1) }
        val mx = vals.maxOrNull()?.coerceAtLeast(1e-6) ?: 1.0
        val avg = vals.average()

        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(84.dp)) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val bw = size.width / n
                    vals.forEachIndexed { i, v ->
                        val h = (v / mx).toFloat() * size.height * .82f
                        val col = if (v >= avg) PAL[0] else PAL[0].copy(.4f)
                        drawRoundRect(
                            if (sel == i) ZxT.Text else col,
                            Offset(i * bw + bw * .12f, size.height - h),
                            androidx.compose.ui.geometry.Size(bw * .76f, h),
                            androidx.compose.ui.geometry.CornerRadius(2f, 2f))
                    }
                    // 均值线：没有它就看不出哪些时段高于平均
                    val ay = size.height - (avg / mx).toFloat() * size.height * .82f
                    drawLine(ZxT.Warn, Offset(0f, ay), Offset(size.width, ay), 1f,
                             pathEffect = androidx.compose.ui.graphics.PathEffect
                                 .dashPathEffect(floatArrayOf(4f, 3f)))
                }
                Row(Modifier.fillMaxWidth().height(84.dp)) {
                    (0 until n).forEach { i ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = i
                            ctx.callTool("zx_select", mapOf(
                                "choice" to items.optJSONObject(i)?.optString("n", "$i"),
                                "v" to vals[i].toString(),
                                "above" to (vals[i] >= avg).toString(),
                                "source" to "zx_pulse"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text("峰值 %.0f".format(mx), fontSize = 9.5.sp, color = ZxT.Text3)
                Spacer(Modifier.width(10.dp))
                Text("均值 %.1f".format(avg), fontSize = 9.5.sp, color = ZxT.Warn)
                Spacer(Modifier.weight(1f))
                if (sel >= 0)
                    Text("${items.optJSONObject(sel)?.optString("n")}：${vals[sel].toLong()}",
                         fontSize = 10.5.sp, color = ZxT.PriLite)
            }
            Text("高于均值的染深、低于的染浅——不给均值线就分不出「哪些时段算忙」",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 4.dp))
        }
    }

    /* ══════════ zx_scoreboard 打分组 ══════════ */
    @Composable
    fun scoreboard(o: JSONObject, ctx: RenderContext) {
        val opts = o.optJSONArray("opts") ?: return
        val crit = o.optJSONArray("crit") ?: return
        val no = minOf(opts.length(), 4)
        val nc = minOf(crit.length(), 5)
        val scores = o.optJSONArray("scores")
        var sel by remember { mutableIntStateOf(-1) }
        val weights = (0 until nc).map { crit.optJSONObject(it)?.optDouble("w", 1.0) ?: 1.0 }
        val wSum = weights.sum().coerceAtLeast(1e-6)
        // 总分：不给出的话用户要在脑子里加权求和
        val totals = (0 until no).map { oi ->
            (0 until nc).sumOf { ci ->
                val v = scores?.optJSONArray(oi)?.optDouble(ci, 0.0) ?: 0.0
                v * weights[ci]
            } / wSum
        }
        val best = totals.indices.maxByOrNull { totals[it] } ?: 0

        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(bottom = 5.dp)) {
                Text("", modifier = Modifier.width(58.dp))
                for (c in 0 until no)
                    Text(opts.optString(c), fontSize = 9.5.sp,
                         color = if (c == best) ZxT.Ok else ZxT.Text3,
                         modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                         maxLines = 1, overflow = TextOverflow.Clip)
            }
            for (ci in 0 until nc) {
                val cr = crit.optJSONObject(ci) ?: continue
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (sel == ci) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        sel = ci
                        ctx.callTool("zx_select", mapOf("criterion" to cr.optString("n", "$ci"),
                                                        "source" to "zx_scoreboard"))
                    }.padding(4.dp, 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(cr.optString("n", ""), fontSize = 10.sp, color = ZxT.Text2,
                         modifier = Modifier.width(58.dp), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    for (oi in 0 until no) {
                        val v = scores?.optJSONArray(oi)?.optDouble(ci, 0.0) ?: 0.0
                        val t = (v / 10.0).toFloat().coerceIn(0f, 1f)
                        Box(Modifier.weight(1f).height(16.dp)) {
                            Box(Modifier.fillMaxWidth().height(12.dp)
                                .clip(RoundedCornerShape(3.dp)).background(ZxT.Surface3))
                            Box(Modifier.fillMaxWidth(t).height(12.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(if (oi == best) PAL[1] else PAL[0].copy(.65f)))
                        }
                    }
                }
            }
            Spacer(Modifier.height(5.dp))
            Row(Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(7.dp)).background(ZxT.Surface2)
                .padding(6.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("加权总分", fontSize = 10.sp, color = ZxT.Text2,
                     modifier = Modifier.width(58.dp))
                for (oi in 0 until no) {
                    val win = totals[oi] >= totals.maxOrNull()!! - 1e-9
                    Text("%.1f".format(totals[oi]), fontSize = 11.sp,
                         fontWeight = if (win) FontWeight.Bold else FontWeight.Normal,
                         color = if (win) ZxT.Ok else ZxT.Text3,
                         fontFamily = ZxT.Mono,
                         modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                }
            }
            Text(if (no > 1) "胜出：${opts.optString(best)}（加权总分 %.1f）".format(totals[best])
                 else "只有一个方案，无法比较",
                 fontSize = 10.5.sp, color = ZxT.Ok,
                 modifier = Modifier.padding(top = 5.dp))
        }
    }

    /* ══════════ zx_abtest A/B 对比 ══════════ */
    @Composable
    fun abtest(o: JSONObject, ctx: RenderContext) {
        val variants = o.optJSONArray("variants") ?: return
        val n = minOf(variants.length(), 4)
        var sel by remember { mutableIntStateOf(-1) }
        val best = (0 until n).maxByOrNull { variants.optJSONObject(it)?.optDouble("cr", 0.0) ?: 0.0 } ?: 0
        val base = variants.optJSONObject(0)?.optDouble("cr", 0.0) ?: 0.0

        Column(Modifier.fillMaxWidth()) {
            for (i in 0 until n) {
                val v = variants.optJSONObject(i) ?: continue
                val cr = v.optDouble("cr", 0.0)
                val lift = if (base > 0 && i > 0) (cr - base) / base * 100 else Double.NaN
                val win = i == best
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (sel == i) ZxT.Surface2 else Color.Transparent)
                    .border(if (win) 1.dp else 0.dp, if (win) ZxT.Ok else Color.Transparent,
                            RoundedCornerShape(7.dp))
                    .clickable {
                        sel = i
                        ctx.callTool("zx_select", mapOf(
                            "choice" to v.optString("n", "$i"), "cr" to cr.toString(),
                            "lift" to (if (lift.isNaN()) "" else lift.toString()),
                            "source" to "zx_abtest"))
                    }.padding(8.dp, 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.width(56.dp)) {
                        Text(v.optString("n", ""), fontSize = 11.sp, color = ZxT.Text,
                             maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("%d 人".format(v.optInt("n_", 0)), fontSize = 9.sp,
                             color = ZxT.Text3, fontFamily = ZxT.Mono)
                    }
                    Box(Modifier.weight(1f).height(20.dp)) {
                        val mx = (0 until n).maxOf { variants.optJSONObject(it)?.optDouble("cr", 0.0) ?: 0.0 }
                            .coerceAtLeast(1e-6)
                        Box(Modifier.fillMaxWidth().height(14.dp)
                            .clip(RoundedCornerShape(3.dp)).background(ZxT.Surface3))
                        Box(Modifier.fillMaxWidth((cr / mx).toFloat().coerceIn(0f, 1f))
                            .height(14.dp).clip(RoundedCornerShape(3.dp))
                            .background(if (win) PAL[1] else PAL[0].copy(.6f)))
                    }
                    Column(Modifier.width(62.dp), horizontalAlignment = Alignment.End) {
                        Text("%.2f%%".format(cr), fontSize = 10.5.sp, color = ZxT.Text,
                             fontFamily = ZxT.Mono)
                        if (!lift.isNaN())
                            Text("%+.1f%%".format(lift), fontSize = 9.sp,
                                 color = if (lift >= 0) ZxT.Ok else ZxT.Err,
                                 fontFamily = ZxT.Mono)
                    }
                }
            }
            Text(if (n > 1) "胜出：${variants.optJSONObject(best)?.optString("n")}；" +
                           "提升是相对第 1 个变体算的" else "只有一个变体，无法比较",
                 fontSize = 10.5.sp, color = ZxT.Ok,
                 modifier = Modifier.padding(top = 5.dp))
            o.optString("sig").takeIf { it.isNotBlank() }?.let {
                Text("显著性：$it", fontSize = 10.sp, color = ZxT.Text3,
                     modifier = Modifier.padding(top = 3.dp))
            }
        }
    }

    /* ══════════ zx_statemachine 状态机 ══════════ */
    @Composable
    fun statemachine(o: JSONObject, ctx: RenderContext) {
        val states = o.optJSONArray("states") ?: return
        val trans = o.optJSONArray("trans")
        val n = minOf(states.length(), 8)
        var cur by remember { mutableIntStateOf(o.optInt("at", 0).coerceIn(0, n - 1)) }
        var sel by remember { mutableIntStateOf(-1) }
        val history = remember { mutableStateListOf<Int>().apply { add(cur) } }

        val canGo = (0 until n).map { i ->
            (0 until (trans?.length() ?: 0)).any {
                val t = trans!!.optJSONObject(it) ?: return@any false
                t.optInt("f") == cur && t.optInt("t") == i
            }
        }

        Column(Modifier.fillMaxWidth()) {
            // 当前状态 + 走过的路径
            Row(verticalAlignment = Alignment.CenterVertically) {
                val cs = states.optJSONObject(cur)
                Box(Modifier.size(9.dp).clip(CircleShape).background(ZxT.PriLite))
                Spacer(Modifier.width(6.dp))
                Text(cs?.optString("n", "") ?: "", fontSize = 12.sp,
                     fontWeight = FontWeight.SemiBold, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(history.joinToString(" → ") { states.optJSONObject(it)?.optString("n", "") ?: "" },
                     fontSize = 9.sp, color = ZxT.Text3, maxLines = 1,
                     overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(7.dp))
            // 状态节点：能走到哪要能看出来
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                for (i in 0 until n) {
                    val s = states.optJSONObject(i) ?: continue
                    val isCur = i == cur
                    val reachable = canGo[i]
                    Text(s.optString("n", ""), fontSize = 10.sp,
                         color = when {
                             isCur -> ZxT.Text
                             reachable -> ZxT.PriLite
                             else -> ZxT.Text3
                         },
                         modifier = Modifier.clip(RoundedCornerShape(10.dp))
                             .background(when {
                                 isCur -> ZxT.Primary
                                 reachable -> ZxT.Primary.copy(.18f)
                                 else -> ZxT.Surface2
                             })
                             .border(if (reachable && !isCur) 1.dp else 0.dp,
                                     ZxT.Primary.copy(.5f), RoundedCornerShape(10.dp))
                             .clickable {
                                 sel = i
                                 if (reachable && !isCur) {
                                     cur = i; history.add(i)
                                     ctx.callTool("zx_select", mapOf(
                                         "from" to (history.getOrNull(history.size - 2) ?: i).toString(),
                                         "to" to i.toString(),
                                         "state" to s.optString("k", "$i"),
                                         "source" to "zx_statemachine"))
                                 }
                             }.padding(8.dp, 4.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
            // 可迁移边
            Text("可从当前状态走到：", fontSize = 10.sp, color = ZxT.Text3)
            val outs = (0 until (trans?.length() ?: 0)).mapNotNull { k ->
                val t = trans!!.optJSONObject(k) ?: return@mapNotNull null
                if (t.optInt("f") != cur) null
                else states.optJSONObject(t.optInt("t"))?.optString("n", "") to t.optString("a", "")
            }
            if (outs.isEmpty())
                Text("（终态，没有出边）", fontSize = 10.sp, color = ZxT.Text3,
                     modifier = Modifier.padding(top = 3.dp))
            else outs.forEach { (to, act) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("→ $to", fontSize = 10.5.sp, color = ZxT.PriLite)
                    if (act.isNotBlank()) {
                        Spacer(Modifier.width(6.dp))
                        Text("[$act]", fontSize = 9.5.sp, color = ZxT.Text3,
                             fontFamily = ZxT.Mono)
                    }
                }
            }
            Text("已有 statelist（状态列表）和 flow（流程图），" +
                 "但没有「当前状态 + 能走到哪 + 走过来的路径」三者合一的东西——" +
                 "而 AI 执行多步任务时，用户最想知道的就是这个",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_checkflow 检查清单流程 ══════════ */
    @Composable
    fun checkflow(o: JSONObject, ctx: RenderContext) {
        val items = o.optJSONArray("items") ?: return
        val n = minOf(items.length(), 8)
        val done = remember { mutableStateMapOf<Int, Boolean>() }
        var sel by remember { mutableIntStateOf(-1) }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "检查清单"), fontSize = 11.5.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                val c = done.count { it.value }
                Text("$c/$n", fontSize = 10.5.sp,
                     color = if (c == n) ZxT.Ok else ZxT.Text3, fontFamily = ZxT.Mono)
            }
            Spacer(Modifier.height(5.dp))
            Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp))
                .background(ZxT.Surface3)) {
                Box(Modifier.fillMaxWidth(if (n == 0) 0f else done.count { it.value }.toFloat() / n)
                    .height(3.dp).clip(RoundedCornerShape(2.dp)).background(ZxT.Ok))
            }
            Spacer(Modifier.height(6.dp))
            for (i in 0 until n) {
                val it = items.optJSONObject(i) ?: continue
                val on = done[i] ?: false
                val req = it.optBoolean("req", true)
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (sel == i) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        sel = i
                        ctx.callTool("zx_select", mapOf(
                            "item" to it.optString("n", "$i"), "done" to on.toString(),
                            "source" to "zx_checkflow"))
                    }.padding(5.dp, 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (on) ZxT.Ok else Color.Transparent)
                        .border(1.2.dp, if (on) ZxT.Ok else if (req) ZxT.Text3 else ZxT.Line,
                                RoundedCornerShape(4.dp))
                        .clickable {
                            // 前置未完成则不允许勾选（阻塞项的意义就在于此）
                            val dep = it.optInt("after", -1)
                            if (!on && dep >= 0 && !(done[dep] ?: false)) {
                                ctx.callTool("zx_select", mapOf(
                                    "blocked" to it.optString("n", "$i"),
                                    "need" to (items.optJSONObject(dep)?.optString("n") ?: ""),
                                    "source" to "zx_checkflow"))
                                return@clickable
                            }
                            done[i] = !on
                            ctx.callTool("zx_form_submit", mapOf("payload" to
                                JSONObject().put("done", done.filter { it.value }
                                    .keys.joinToString(",")).toString()))
                        }, contentAlignment = Alignment.Center) {
                        if (on) Text("✓", fontSize = 10.sp, color = Color.White)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(it.optString("n", ""), fontSize = 10.5.sp,
                         color = if (on) ZxT.Text3 else ZxT.Text,
                         modifier = Modifier.weight(1f), maxLines = 2,
                         overflow = TextOverflow.Ellipsis,
                         textDecoration = if (on) androidx.compose.ui.text.style
                             .TextDecoration.LineThrough else null)
                    if (!req)
                        Text("可选", fontSize = 8.5.sp, color = ZxT.Text3,
                             modifier = Modifier.clip(RoundedCornerShape(4.dp))
                                 .background(ZxT.Surface2).padding(5.dp, 2.dp))
                    val dep = it.optInt("after", -1)
                    if (dep >= 0)
                        Text("← ${items.optJSONObject(dep)?.optString("n", "")}",
                             fontSize = 8.5.sp, color = ZxT.Text3,
                             modifier = Modifier.padding(start = 5.dp))
                }
            }
        }
    }

    /* ══════════ zx_branch 分支对比图 ══════════ */
    @Composable
    fun branch(o: JSONObject, ctx: RenderContext) {
        val rows = o.optJSONArray("rows") ?: return
        val n = minOf(rows.length(), 4)
        val cells = o.optJSONArray("cells")
        val cols = o.optInt("cols", 6)
        var sel by remember { mutableStateOf<Pair<Int, Int>?>(null) }
        val head = o.optInt("head", -1)

        Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Row(Modifier.padding(start = 62.dp)) {
                for (c in 0 until cols)
                    Text(if (c == head) "●" else "$c", fontSize = 8.sp,
                         color = if (c == head) ZxT.PriLite else ZxT.Text3,
                         modifier = Modifier.width(30.dp), textAlign = TextAlign.Center)
            }
            for (i in 0 until n) {
                val r = rows.optJSONObject(i) ?: continue
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text2,
                         modifier = Modifier.width(62.dp), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    for (c in 0 until cols) {
                        var kind = ""; var lbl = ""
                        for (k in 0 until (cells?.length() ?: 0)) {
                            val x = cells!!.optJSONObject(k) ?: continue
                            if (x.optInt("r") == i && x.optInt("c") == c) {
                                kind = x.optString("k", "commit"); lbl = x.optString("t", ""); break
                            }
                        }
                        val picked = sel?.first == i && sel?.second == c
                        val (bg, ln) = when (kind) {
                            "merge" -> ZxT.Warn.copy(.6f) to true
                            "branch" -> PAL[1].copy(.6f) to true
                            "commit" -> PAL[0].copy(.7f) to true
                            else -> Color.Transparent to false
                        }
                        Box(Modifier.width(30.dp).height(22.dp),
                            contentAlignment = Alignment.Center) {
                            if (ln)
                                Box(Modifier.fillMaxWidth().height(2.dp)
                                    .background(PAL[0].copy(.25f)))
                            Box(Modifier.size(if (picked) 12.dp else 9.dp)
                                .clip(CircleShape)
                                .background(if (kind.isEmpty()) ZxT.Surface3 else bg)
                                .border(if (picked) 1.5.dp else 0.dp, ZxT.Text, CircleShape)
                                .clickable {
                                    sel = i to c
                                    ctx.callTool("zx_select", mapOf(
                                        "branch" to r.optString("n", "$i"),
                                        "col" to c.toString(), "kind" to kind, "t" to lbl,
                                        "source" to "zx_branch"))
                                })
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                listOf("commit" to "提交", "branch" to "分支", "merge" to "合并").forEach { (k, lab) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape)
                            .background(if (k == "merge") ZxT.Warn.copy(.6f)
                                        else if (k == "branch") PAL[1].copy(.6f)
                                        else PAL[0].copy(.7f)))
                        Spacer(Modifier.width(4.dp))
                        Text(lab, fontSize = 9.sp, color = ZxT.Text3)
                    }
                }
                Spacer(Modifier.weight(1f))
                if (sel != null)
                    Text("${rows.optJSONObject(sel!!.first)?.optString("n")} @${sel!!.second}",
                         fontSize = 9.5.sp, color = ZxT.Text3)
            }
        }
    }

    /* ══════════ zx_sla SLA 追踪 ══════════ */
    @Composable
    fun sla(o: JSONObject, ctx: RenderContext) {
        val items = o.optJSONArray("items") ?: return
        val n = minOf(items.length(), 6)
        var sel by remember { mutableIntStateOf(-1) }

        Column(Modifier.fillMaxWidth()) {
            for (i in 0 until n) {
                val it = items.optJSONObject(i) ?: continue
                val used = it.optDouble("used", 0.0)
                val budget = it.optDouble("budget", 100.0).coerceAtLeast(1e-6)
                val t = (used / budget).coerceIn(0.0, 3.0)
                val col = when {
                    t >= 1.0 -> ZxT.Err
                    t >= .85 -> ZxT.Warn
                    else -> ZxT.Ok
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (sel == i) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        sel = i
                        ctx.callTool("zx_select", mapOf(
                            "choice" to it.optString("n", "$i"),
                            "used" to used.toString(), "budget" to budget.toString(),
                            "breached" to (t >= 1.0).toString(), "source" to "zx_sla"))
                    }.padding(5.dp, 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(it.optString("n", ""), fontSize = 10.5.sp, color = ZxT.Text2,
                         modifier = Modifier.width(60.dp), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    Box(Modifier.weight(1f).height(18.dp)) {
                        Box(Modifier.fillMaxWidth().height(12.dp)
                            .clip(RoundedCornerShape(3.dp)).background(ZxT.Surface3))
                        // 预算 100% 处画刻线，超出部分用红色显示"超了多少"
                        Box(Modifier.fillMaxWidth((t / 3.0).toFloat().coerceIn(0f, 1f))
                            .height(12.dp).clip(RoundedCornerShape(3.dp))
                            .background(col))
                        Box(Modifier.fillMaxWidth().height(12.dp),
                            contentAlignment = Alignment.CenterStart) {
                            Box(Modifier.offset(x = (300 / 3 - 1).dp).width(1.5.dp)
                                .height(16.dp).background(ZxT.Text))
                        }
                    }
                    Text("%.0f%%".format(t * 100), fontSize = 10.sp, color = col,
                         fontFamily = ZxT.Mono, modifier = Modifier.width(40.dp),
                         textAlign = TextAlign.End)
                }
            }
            val breached = (0 until n).count {
                val it = items.optJSONObject(it) ?: return@count false
                it.optDouble("used", 0.0) >= it.optDouble("budget", 100.0) }
            Text(if (breached > 0) "$breached 项已超预算（刻线在 100% 处，超出部分染红）"
                 else "全部在预算内",
                 fontSize = 10.sp,
                 color = if (breached > 0) ZxT.Err else ZxT.Ok,
                 modifier = Modifier.padding(top = 5.dp))
        }
    }

    /* ══════════ zx_repost 转发卡 ══════════ */
    @Composable
    fun repost(o: JSONObject, ctx: RenderContext) {
        val q = o.optJSONObject("quote")
        var liked by remember { mutableStateOf(o.optBoolean("liked", false)) }
        var reposted by remember { mutableStateOf(o.optBoolean("reposted", false)) }
        var nLike by remember { mutableIntStateOf(o.optInt("likes", 0)) }
        var nRep by remember { mutableIntStateOf(o.optInt("reposts", 0)) }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(26.dp).clip(CircleShape)
                    .background(PAL[0].copy(.5f)), contentAlignment = Alignment.Center) {
                    Text(o.optString("by", "?").take(1), fontSize = 12.sp, color = ZxT.Text)
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(o.optString("by", ""), fontSize = 11.sp, color = ZxT.Text)
                    Text(o.optString("at", ""), fontSize = 9.sp, color = ZxT.Text3)
                }
            }
            Spacer(Modifier.height(7.dp))
            Text(o.optString("text", ""), fontSize = 11.5.sp, color = ZxT.Text,
                 lineHeight = 16.sp)
            q?.let {
                Spacer(Modifier.height(7.dp))
                Box(Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(ZxT.Surface2)
                    .border(1.dp, ZxT.Line, RoundedCornerShape(8.dp))
                    .clickable {
                        ctx.callTool("zx_select", mapOf("quote" to it.optString("by", ""),
                                                        "source" to "zx_repost"))
                    }.padding(9.dp)) {
                    Column {
                        Text(it.optString("by", ""), fontSize = 10.sp, color = ZxT.PriLite)
                        Spacer(Modifier.height(3.dp))
                        Text(it.optString("text", ""), fontSize = 10.5.sp,
                             color = ZxT.Text3, lineHeight = 15.sp, maxLines = 4,
                             overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                listOf(
                    Triple("💬", o.optInt("replies", 0).toString(), false) to { },
                    Triple(if (reposted) "⟳" else "⟳", nRep.toString(), reposted) to {
                        reposted = !reposted; nRep += if (reposted) 1 else -1
                        ctx.callTool("zx_select", mapOf("reposted" to reposted.toString(),
                                                        "source" to "zx_repost"))
                    },
                    Triple(if (liked) "♥" else "♡", nLike.toString(), liked) to {
                        liked = !liked; nLike += if (liked) 1 else -1
                        ctx.callTool("zx_select", mapOf("liked" to liked.toString(),
                                                        "source" to "zx_repost"))
                    }
                ).forEach { e ->
                    val icon = e.first.first; val cnt = e.first.second; val on = e.first.third
                    val act = e.second
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp))
                            .clickable { act() }.padding(6.dp, 3.dp)) {
                        Text(icon, fontSize = 11.sp,
                             color = if (on) PAL[3] else ZxT.Text3)
                        Spacer(Modifier.width(4.dp))
                        Text(cnt, fontSize = 10.sp,
                             color = if (on) PAL[3] else ZxT.Text3,
                             fontFamily = ZxT.Mono)
                    }
                }
            }
        }
    }

    /* ══════════ zx_thread 话题串 ══════════ */
    @Composable
    fun thread(o: JSONObject, ctx: RenderContext) {
        val posts = o.optJSONArray("posts") ?: return
        val n = minOf(posts.length(), 8)
        val open = remember { mutableStateMapOf<Int, Boolean>() }
        var sel by remember { mutableIntStateOf(-1) }

        Column(Modifier.fillMaxWidth()) {
            for (i in 0 until n) {
                val p = posts.optJSONObject(i) ?: continue
                val depth = p.optInt("d", 0).coerceIn(0, 3)
                val hasKids = (i + 1 until n).any {
                    posts.optJSONObject(it)?.optInt("d", 0) ?: 0 > depth }
                val expanded = open[i] ?: true
                Row(Modifier.fillMaxWidth().padding(start = (depth * 12).dp)) {
                    if (depth > 0)
                        Box(Modifier.width(2.dp).height(38.dp)
                            .background(ZxT.Line))
                    Spacer(Modifier.width(7.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.optString("by", ""), fontSize = 10.5.sp, color = ZxT.Text)
                            Spacer(Modifier.width(6.dp))
                            Text(p.optString("at", ""), fontSize = 9.sp, color = ZxT.Text3)
                            Spacer(Modifier.weight(1f))
                            if (hasKids)
                                Text(if (expanded) "收起" else "展开", fontSize = 9.sp,
                                     color = ZxT.Text3,
                                     modifier = Modifier.clickable {
                                         open[i] = !expanded
                                         ctx.callTool("zx_select", mapOf(
                                             "post" to i.toString(),
                                             "collapsed" to expanded.toString(),
                                             "source" to "zx_thread"))
                                     })
                        }
                        if (expanded)
                            Text(p.optString("t", ""), fontSize = 10.5.sp,
                                 color = ZxT.Text2, lineHeight = 15.sp,
                                 modifier = Modifier.padding(top = 2.dp)
                                     .clickable { sel = i })
                    }
                }
                Spacer(Modifier.height(5.dp))
            }
        }
    }

    /* ══════════ zx_splitflap 翻页牌 ══════════ */
    @Composable
    fun splitflap(o: JSONObject, ctx: RenderContext) {
        val digits = o.optJSONArray("digits") ?: return
        val n = minOf(digits.length(), 8)

        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (i in 0 until n) {
                val d = digits.optJSONObject(i) ?: continue
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.width(34.dp).height(46.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(ZxT.Surface)
                        .border(1.dp, ZxT.Line, RoundedCornerShape(5.dp))
                        .clickable {
                            ctx.callTool("zx_select", mapOf(
                                "pos" to i.toString(), "v" to d.optString("v", ""),
                                "source" to "zx_splitflap"))
                        }, contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(d.optString("v", "0"), fontSize = 22.sp,
                                 fontWeight = FontWeight.Bold, color = ZxT.Text,
                                 fontFamily = ZxT.Mono)
                            // 中缝：翻页牌的特征
                            Box(Modifier.fillMaxWidth(.85f).height(1.dp)
                                .background(ZxT.Surface3))
                        }
                    }
                    d.optString("n").takeIf { it.isNotBlank() }?.let {
                        Text(it, fontSize = 8.5.sp, color = ZxT.Text3,
                             modifier = Modifier.padding(top = 3.dp))
                    }
                }
            }
        }
    }

    /* ══════════ zx_runbook 操作手册 ══════════ */
    @Composable
    fun runbook(o: JSONObject, ctx: RenderContext) {
        val steps = o.optJSONArray("steps") ?: return
        val n = minOf(steps.length(), 8)
        var cur by remember { mutableIntStateOf(o.optInt("at", 0).coerceIn(0, n - 1)) }
        var confirmed by remember { mutableStateOf(false) }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "操作手册"), fontSize = 11.5.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("${cur + 1}/$n", fontSize = 10.5.sp, color = ZxT.Text3,
                     fontFamily = ZxT.Mono)
            }
            Spacer(Modifier.height(6.dp))
            // 步骤条：点任意一步可跳
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (i in 0 until n) {
                    Box(Modifier.weight(1f).height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (i <= cur) ZxT.Primary else ZxT.Surface3)
                        .clickable { cur = i; confirmed = false })
                }
            }
            Spacer(Modifier.height(9.dp))
            val s = steps.optJSONObject(cur) ?: return
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(20.dp).clip(CircleShape)
                    .background(if (s.optBoolean("risk", false)) ZxT.Err.copy(.25f)
                                else ZxT.Primary.copy(.25f)),
                    contentAlignment = Alignment.Center) {
                    Text("${cur + 1}", fontSize = 10.sp,
                         color = if (s.optBoolean("risk", false)) ZxT.Err else ZxT.PriLite)
                }
                Spacer(Modifier.width(8.dp))
                Text(s.optString("n", ""), fontSize = 11.5.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                if (s.optBoolean("risk", false))
                    Text("危险", fontSize = 8.5.sp, color = ZxT.Err,
                         modifier = Modifier.clip(RoundedCornerShape(4.dp))
                             .background(ZxT.Err.copy(.16f)).padding(5.dp, 2.dp))
            }
            Spacer(Modifier.height(6.dp))
            Text(s.optString("cmd", ""), fontSize = 10.5.sp, color = ZxT.Text2,
                 fontFamily = ZxT.Mono, lineHeight = 15.sp,
                 modifier = Modifier.fillMaxWidth()
                     .clip(RoundedCornerShape(7.dp)).background(ZxT.Surface2).padding(9.dp))
            s.optString("note").takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(5.dp))
                Row {
                    Box(Modifier.width(2.dp).height(30.dp).background(ZxT.Warn))
                    Spacer(Modifier.width(7.dp))
                    Text(it, fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                         modifier = Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(8.dp))
            if (s.optBoolean("risk", false) && !confirmed) {
                // 危险步骤强制二次确认
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.weight(1f)
                        .clip(RoundedCornerShape(8.dp)).background(ZxT.Surface2)
                        .clickable { confirmed = true }.padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center) {
                        Text("我确认风险", fontSize = 11.sp, color = ZxT.Text2)
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (cur == 0) ZxT.Surface3 else ZxT.Surface2)
                        .clickable(enabled = cur > 0) { cur--; confirmed = false }
                        .padding(vertical = 7.dp), contentAlignment = Alignment.Center) {
                        Text("上一步", fontSize = 11.sp,
                             color = if (cur == 0) ZxT.Text3 else ZxT.Text2)
                    }
                    Box(Modifier.weight(1.4f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (cur == n - 1) ZxT.Ok else ZxT.Primary)
                        .clickable {
                            ctx.callTool("zx_select", mapOf(
                                "step" to cur.toString(), "n" to s.optString("n", ""),
                                "done" to (cur == n - 1).toString(), "source" to "zx_runbook"))
                            if (cur < n - 1) { cur++; confirmed = false }
                        }.padding(vertical = 7.dp), contentAlignment = Alignment.Center) {
                        Text(if (cur == n - 1) "完成" else "执行并下一步",
                             fontSize = 11.5.sp, color = Color.White)
                    }
                }
            }
        }
    }
}
