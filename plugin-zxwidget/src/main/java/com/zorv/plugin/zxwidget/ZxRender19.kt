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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import androidx.compose.foundation.border

/**
 * 第十九批组件（12 个）—— 序列 / 结构 / 证据。
 *
 * 前十八批把"看什么、填什么、改什么"铺完了。
 * 剩下的是三类**用来推理而不是用来展示**的：
 *
 *   一、**序列**。已有 timeline（事件）、gantt（任务）、
 *        spiral（螺旋时间轴），但**"这一步之后能走哪几条路"**
 *        没有——而 AI 排查问题时最需要的就是这个。
 *        `zx_traceback`（回溯路径）、`zx_seq`（时序图）。
 *
 *   二、**结构**。已有 org / orgchart / tree / ast，
 *        但**"这个东西由哪些部分拼成、比例各多少"**
 *        （爆炸视图）和**"谁依赖谁、哪条是瓶颈"**
 *        （关键路径）没有。
 *
 *   三、**证据**。已有 diff / baseline / logview，
 *        但**"这个结论的依据是什么"**（证据链）和
 *        **"两条证据冲突时怎么呈现"**（对质）没有。
 *        AI 给出的结论要能被检验，光有结论不够。
 *
 * 贯穿这批的一条：**要能看出"为什么"**。
 * 回溯路径要标出哪一步分叉、时序图要标出谁在等谁、
 * 证据链要标出哪条是推断哪条是事实、关键路径要标出浮动时间。
 * **只有结论没有依据的界面，用户只能选择信或不信。**
 */
object ZxRender19 {

    private val PAL = listOf(
        Color(0xFF5046E4), Color(0xFF3FBF7F), Color(0xFFE8A33D),
        Color(0xFFE5544B), Color(0xFF4A9BE8), Color(0xFF8E86FF)
    )

    /* ══════════ zx_traceback 回溯路径 ══════════ */
    @Composable
    fun traceback(o: JSONObject, ctx: RenderContext) {
        val nodes = o.optJSONArray("nodes") ?: return
        val n = minOf(nodes.length(), 12)
        var sel by remember { mutableIntStateOf(-1) }
        // 分叉：指向同一目标的边 ≥2，就是分叉点
        val kids = (0 until n).map { i ->
            (0 until n).filter { j ->
                nodes.optJSONObject(j)?.optInt("p", -1) == i }
        }
        val roots = (0 until n).filter { nodes.optJSONObject(it)?.optInt("p", -1) !in 0 until n }
        val fork = kids.map { it.size >= 2 }

        Column(Modifier.fillMaxWidth()) {
            // 按层排：同一父的兄弟并排
            val layers = mutableListOf<List<Int>>()
            var cur = roots
            val seen = mutableSetOf<Int>()
            while (cur.isNotEmpty() && layers.size < 6) {
                layers.add(cur)
                cur.forEach { seen.add(it) }
                val next = mutableListOf<Int>()
                cur.forEach { i -> kids[i].forEach { if (it !in seen) next.add(it) } }
                cur = next
            }
            layers.forEachIndexed { li, row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    row.forEach { i ->
                        val nd = nodes.optJSONObject(i) ?: return@forEach
                        val on = sel == i
                        Box(Modifier.weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (on) PAL[0] else ZxT.Surface2)
                            .clickable {
                                sel = i
                                ctx.callTool("zx_select", mapOf(
                                    "node" to nd.optString("n", "$i"),
                                    "depth" to li.toString(),
                                    "fork" to fork[i].toString(),
                                    "source" to "zx_traceback"))
                            }.padding(7.dp, 5.dp)) {
                            Column {
                                Text(nd.optString("n", ""), fontSize = 10.sp,
                                     color = if (on) Color.White else ZxT.Text,
                                     maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    // 分叉标记：这是这个组件存在的意义
                                    if (fork[i])
                                        Text("⑂${kids[i].size}", fontSize = 8.sp,
                                             color = ZxT.Warn)
                                    Spacer(Modifier.width(3.dp))
                                    Text(nd.optString("k", ""), fontSize = 8.sp,
                                         color = if (on) Color.White.copy(.7f) else ZxT.Text3,
                                         maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                if (li < layers.size - 1)
                    Text("↓", fontSize = 9.sp, color = ZxT.Text3,
                         modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("⑂ = 分叉点", fontSize = 9.sp, color = ZxT.Warn)
                Spacer(Modifier.width(8.dp))
                Text("${fork.count { it }} 处分叉", fontSize = 9.5.sp, color = ZxT.Text3)
                Spacer(Modifier.weight(1f))
                if (sel >= 0) {
                    val p = nodes.optJSONObject(sel)?.optInt("p", -1) ?: -1
                    Text(if (p in 0 until n) "来自「${nodes.optJSONObject(p)?.optString("n")}」"
                         else "起点", fontSize = 9.5.sp, color = ZxT.PriLite)
                }
            }
            Text("已有 timeline（事件流）是平的。这个要回答的是" +
                 "**「这一步之后能走哪几条路」**——排查时最需要的信息",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 5.dp))
        }
    }

    /* ══════════ zx_seq 时序图 ══════════ */
    @Composable
    fun seq(o: JSONObject, ctx: RenderContext) {
        val actors = o.optJSONArray("actors") ?: return
        val na = minOf(actors.length(), 5)
        val msgs = o.optJSONArray("msgs") ?: return
        val nm = minOf(msgs.length(), 10)
        var sel by remember { mutableIntStateOf(-1) }

        Column(Modifier.fillMaxWidth()) {
            // 参与者头
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                for (i in 0 until na)
                    Text(actors.optString(i), fontSize = 9.sp, color = ZxT.Text2,
                         modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                         maxLines = 1, overflow = TextOverflow.Clip)
            }
            Box(Modifier.fillMaxWidth().height((nm * 20 + 8).coerceAtMost(200).dp)) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height
                    // 生命线
                    for (i in 0 until na) {
                        val x = W * (i + .5f) / na
                        drawLine(ZxT.Surface3, Offset(x, 0f), Offset(x, H), 1f,
                                 pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 4f)))
                    }
                    for (k in 0 until nm) {
                        val m = msgs.optJSONObject(k) ?: continue
                        val f = m.optInt("f", -1); val t = m.optInt("t", -1)
                        if (f !in 0 until na || t !in 0 until na) continue
                        val y = 6f + k * 20f
                        val x0 = W * (f + .5f) / na
                        val x1 = W * (t + .5f) / na
                        val wait = m.optBoolean("wait", false)
                        // 等待用虚线——这是时序图里最关键的信息
                        drawLine(if (sel == k) ZxT.Text else
                                 (if (wait) ZxT.Warn else PAL[0]),
                                 Offset(x0, y), Offset(x1, y), if (sel == k) 2.4f else 1.6f,
                                 pathEffect = if (wait)
                                     PathEffect.dashPathEffect(floatArrayOf(4f, 3f)) else null)
                        // 箭头
                        val dir = if (x1 > x0) 1f else -1f
                        drawPath(Path().apply {
                            moveTo(x1, y); lineTo(x1 - dir * 5f, y - 3.5f)
                            lineTo(x1 - dir * 5f, y + 3.5f); close() },
                            if (sel == k) ZxT.Text else (if (wait) ZxT.Warn else PAL[0]))
                    }
                }
                Row(Modifier.fillMaxWidth().height((nm * 20 + 8).coerceAtMost(200).dp)) {
                    (0 until nm).forEach { k ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = k
                            val m = msgs.optJSONObject(k)
                            ctx.callTool("zx_select", mapOf(
                                "msg" to (m?.optString("n", "") ?: ""),
                                "from" to actors.optString(m?.optInt("f", -1) ?: -1),
                                "to" to actors.optString(m?.optInt("t", -1) ?: -1),
                                "wait" to (m?.optBoolean("wait", false) ?: false).toString(),
                                "source" to "zx_seq"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(14.dp).height(1.6.dp).background(PAL[0]))
                    Spacer(Modifier.width(4.dp))
                    Text("调用", fontSize = 9.sp, color = ZxT.Text3)
                }
                Spacer(Modifier.width(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(14.dp).height(1.6.dp).background(ZxT.Warn))
                    Spacer(Modifier.width(4.dp))
                    Text("等待（虚线）", fontSize = 9.sp, color = ZxT.Text3)
                }
                Spacer(Modifier.weight(1f))
                val waits = (0 until nm).count { msgs.optJSONObject(it)?.optBoolean("wait", false) ?: false }
                Text("$waits 处等待", fontSize = 9.5.sp,
                     color = if (waits > 0) ZxT.Warn else ZxT.Text3)
            }
            if (sel >= 0) {
                val m = msgs.optJSONObject(sel)
                Text("${actors.optString(m?.optInt("f", -1) ?: -1)} → " +
                     "${actors.optString(m?.optInt("t", -1) ?: -1)}：" +
                     "${m?.optString("n", "")}" +
                     (if (m?.optBoolean("wait", false) == true) "（阻塞）" else ""),
                     fontSize = 10.sp, color = ZxT.PriLite,
                     modifier = Modifier.padding(top = 3.dp))
            }
            Text("时序图要标出**谁在等谁**——这是找性能瓶颈时唯一有用的信息。" +
                 "已有 flow / dag 都是有向图，表达不了时序与阻塞",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 5.dp))
        }
    }

    /* ══════════ zx_explode 爆炸视图 ══════════ */
    @Composable
    fun explode(o: JSONObject, ctx: RenderContext) {
        val parts = o.optJSONArray("parts") ?: return
        val n = minOf(parts.length(), 8)
        var sel by remember { mutableIntStateOf(-1) }
        var spread by remember { mutableFloatStateOf(o.optDouble("spread", .5).toFloat()) }
        val tot = (0 until n).sumOf { parts.optJSONObject(it)?.optDouble("w", 1.0) ?: 1.0 }
            .coerceAtLeast(1e-6)

        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(150.dp)) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val cx = size.width / 2
                    val cy = size.height / 2
                    // 沿对角散开：不散开就叠在一起，看不出由几部分组成
                    for (i in 0 until n) {
                        val p = parts.optJSONObject(i) ?: continue
                        val w = p.optDouble("w", 1.0)
                        val t = (i - (n - 1) / 2f)
                        val off = t * 26f * spread
                        val s = (12f + (w / tot) * 46f).toFloat()
                        val col = PAL[i % PAL.size]
                        drawRoundRect(if (sel == i) col else col.copy(.7f),
                            Offset(cx - s / 2 + off, cy - s / 2 - off),
                            Size(s, s),
                            androidx.compose.ui.geometry.CornerRadius(5f, 5f))
                        if (sel == i)
                            drawRoundRect(ZxT.Text,
                                Offset(cx - s / 2 + off, cy - s / 2 - off),
                                Size(s, s),
                                androidx.compose.ui.geometry.CornerRadius(5f, 5f),
                                style = Stroke(1.6f))
                    }
                }
                Row(Modifier.fillMaxWidth().height(150.dp)) {
                    (0 until n).forEach { i ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = i
                            val p = parts.optJSONObject(i)
                            ctx.callTool("zx_select", mapOf(
                                "part" to (p?.optString("n", "$i") ?: ""),
                                "w" to (p?.optDouble("w", 1.0) ?: 1.0).toString(),
                                "pct" to ((p?.optDouble("w", 1.0) ?: 1.0) / tot * 100).toString(),
                                "source" to "zx_explode"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("散开", fontSize = 9.sp, color = ZxT.Text3,
                     modifier = Modifier.width(28.dp))
                Box(Modifier.weight(1f).height(18.dp)
                    .clip(RoundedCornerShape(9.dp)).background(ZxT.Surface2)
                    .clickable {
                        spread = if (spread >= 1f) 0f else 1f
                    }, contentAlignment = Alignment.CenterStart) {
                    Box(Modifier.fillMaxWidth(spread).height(18.dp)
                        .clip(RoundedCornerShape(9.dp)).background(PAL[0].copy(.6f)))
                }
                Spacer(Modifier.width(8.dp))
                Text(if (spread > 0f) "散开" else "合拢", fontSize = 9.5.sp,
                     color = ZxT.Text3, modifier = Modifier.width(28.dp))
            }
            if (sel >= 0) {
                val p = parts.optJSONObject(sel)
                val w = p?.optDouble("w", 1.0) ?: 1.0
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text(p?.optString("n", "") ?: "", fontSize = 10.5.sp, color = ZxT.Text)
                    Spacer(Modifier.width(6.dp))
                    Text("占 ${(w / tot * 100).toInt()}%", fontSize = 10.sp,
                         color = ZxT.Text3, fontFamily = ZxT.Mono)
                    Spacer(Modifier.weight(1f))
                    Text(p?.optString("d", "") ?: "", fontSize = 9.sp, color = ZxT.Text3,
                         maxLines = 1, overflow = TextOverflow.Ellipsis,
                         modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                }
            }
            Text("已有 orgchart / tree 是层级，treemap 是占比，" +
                 "但**「这个东西由哪些部分拼成」**两者都表达不了",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 5.dp))
        }
    }

    /* ══════════ zx_critical 关键路径 ══════════ */
    @Composable
    fun critical(o: JSONObject, ctx: RenderContext) {
        val tasks = o.optJSONArray("tasks") ?: return
        val n = minOf(tasks.length(), 10)
        var sel by remember { mutableIntStateOf(-1) }
        // 简化 CPM：只算最早开始，浮动作 0 即关键
        val dur = (0 until n).map { tasks.optJSONObject(it)?.optDouble("d", 1.0) ?: 1.0 }
        val dep = (0 until n).map { i ->
            val arr = tasks.optJSONObject(i)?.optJSONArray("after")
            (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optInt(it) }
                .filter { it in 0 until n }
        }
        val es = DoubleArray(n)
        for (i in 0 until n) {
            es[i] = dep[i].maxOfOrNull { es[it] + dur[it] } ?: 0.0
        }
        val projEnd = (0 until n).maxOfOrNull { es[it] + dur[it] } ?: 0.0
        // 浮动 = 最晚可开始 - 最早可开始；这里用"拖了会不会延后总工期"近似
        val slack = (0 until n).map { i ->
            var late = projEnd
            for (j in 0 until n) {
                if (i in dep[j]) {
                    late = minOf(late, es[j])
                }
            }
            (late - es[i] - dur[i]).coerceAtLeast(0.0)
        }
        val crit = slack.map { it < 1e-6 }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "关键路径"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("总工期 %.0f".format(projEnd), fontSize = 10.sp,
                     color = ZxT.Text3, fontFamily = ZxT.Mono)
                Spacer(Modifier.width(6.dp))
                Text("关键 ${crit.count { it }}", fontSize = 10.sp, color = ZxT.Err)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height((n * 18 + 4).coerceAtMost(180).dp)) {
                Column {
                    for (i in 0 until n) {
                        val t = tasks.optJSONObject(i) ?: continue
                        val p0 = (es[i] / projEnd.coerceAtLeast(1e-6)).toFloat().coerceIn(0f, 1f)
                        val p1 = ((es[i] + dur[i]) / projEnd.coerceAtLeast(1e-6))
                            .toFloat().coerceIn(0f, 1f)
                        Row(Modifier.fillMaxWidth().height(18.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (sel == i) ZxT.Surface2 else Color.Transparent)
                            .clickable {
                                sel = i
                                ctx.callTool("zx_select", mapOf(
                                    "task" to t.optString("n", "$i"),
                                    "slack" to slack[i].toString(),
                                    "critical" to crit[i].toString(),
                                    "source" to "zx_critical"))
                            }, verticalAlignment = Alignment.CenterVertically) {
                            Text(t.optString("n", ""), fontSize = 8.5.sp,
                                 color = ZxT.Text3, modifier = Modifier.width(44.dp),
                                 maxLines = 1, overflow = TextOverflow.Clip)
                            Box(Modifier.weight(1f).height(11.dp)
                                .clip(RoundedCornerShape(2.dp)).background(ZxT.Surface3)) {
                                Box(Modifier.fillMaxWidth(p1).height(11.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Color.Transparent)) {
                                    Box(Modifier.fillMaxWidth(if (p1 > 0f) 1f - p0 / p1 else 0f)
                                        .align(Alignment.CenterEnd).height(11.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(if (crit[i]) ZxT.Err
                                                    else PAL[1].copy(.6f)))
                                }
                            }
                            // 浮动时间必须标出——这是关键路径的全部意义
                            Text(if (crit[i]) "关键" else "+%.0f".format(slack[i]),
                                 fontSize = 8.sp,
                                 color = if (crit[i]) ZxT.Err else ZxT.Text3,
                                 modifier = Modifier.width(30.dp), textAlign = TextAlign.End)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(14.dp).height(8.dp)
                        .clip(RoundedCornerShape(2.dp)).background(ZxT.Err))
                    Spacer(Modifier.width(4.dp))
                    Text("关键（浮动 0）", fontSize = 9.sp, color = ZxT.Text3)
                }
                Spacer(Modifier.width(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(14.dp).height(8.dp)
                        .clip(RoundedCornerShape(2.dp)).background(PAL[1].copy(.6f)))
                    Spacer(Modifier.width(4.dp))
                    Text("有浮动", fontSize = 9.sp, color = ZxT.Text3)
                }
                Spacer(Modifier.weight(1f))
                if (sel >= 0)
                    Text(if (crit[sel]) "拖它会延后总工期"
                         else "可延后 %.0f".format(slack[sel]),
                         fontSize = 9.5.sp,
                         color = if (crit[sel]) ZxT.Err else ZxT.Ok)
            }
            Text("已有 gantt 只画条，不告诉你" +
                 "**哪条不能拖**。关键路径的全部意义就是那个浮动时间",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 5.dp))
        }
    }

    /* ══════════ zx_evidence 证据链 ══════════ */
    @Composable
    fun evidence(o: JSONObject, ctx: RenderContext) {
        val items = o.optJSONArray("items") ?: return
        val n = minOf(items.length(), 8)
        var sel by remember { mutableIntStateOf(-1) }
        // 事实 vs 推断：这是这个组件存在的意义
        val kinds = mapOf("fact" to ZxT.Ok, "infer" to ZxT.Warn, "quote" to ZxT.Info,
                          "guess" to ZxT.Err)
        val labels = mapOf("fact" to "事实", "infer" to "推断", "quote" to "引用",
                           "guess" to "猜测")
        val conf = (0 until n).map { items.optJSONObject(it)?.optDouble("c", .5) ?: .5 }
        val concl = o.optString("concl", "")
        val avg = conf.average()

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("结论", fontSize = 11.sp, color = ZxT.Text3)
                Spacer(Modifier.width(6.dp))
                Text("%.0f%%".format(avg * 100), fontSize = 11.sp,
                     color = when { avg > .8 -> ZxT.Ok; avg > .5 -> ZxT.Warn; else -> ZxT.Err },
                     fontFamily = ZxT.Mono)
                Spacer(Modifier.weight(1f))
                Text("$n 条依据", fontSize = 9.5.sp, color = ZxT.Text3)
            }
            if (concl.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(ZxT.Surface2).padding(9.dp, 7.dp)) {
                    Text(concl, fontSize = 11.sp, color = ZxT.Text, lineHeight = 16.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
            for (i in 0 until n) {
                val it = items.optJSONObject(i) ?: continue
                val k = it.optString("k", "fact")
                val col = kinds[k] ?: ZxT.Text3
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (sel == i) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        sel = i
                        ctx.callTool("zx_select", mapOf(
                            "item" to it.optString("t", ""),
                            "kind" to k, "conf" to conf[i].toString(),
                            "source" to "zx_evidence"))
                    }.padding(7.dp, 5.dp)) {
                    // 连接线：视觉上"挂"在结论下
                    Box(Modifier.width(2.dp).height(28.dp)
                        .clip(RoundedCornerShape(1.dp)).background(col))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(labels[k] ?: k, fontSize = 8.5.sp, color = col)
                            Spacer(Modifier.width(5.dp))
                            Text("%.0f%%".format(conf[i] * 100), fontSize = 8.5.sp,
                                 color = ZxT.Text3, fontFamily = ZxT.Mono)
                        }
                        Text(it.optString("t", ""), fontSize = 10.sp, color = ZxT.Text2,
                             lineHeight = 14.sp, maxLines = 3,
                             overflow = TextOverflow.Ellipsis)
                        it.optString("src").takeIf { it.isNotBlank() }?.let {
                            Text(it, fontSize = 8.5.sp, color = ZxT.Text3, maxLines = 1,
                                 overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)
                .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                labels.forEach { (k, lab) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(CircleShape)
                            .background(kinds[k] ?: ZxT.Text3))
                        Spacer(Modifier.width(3.dp))
                        Text(lab, fontSize = 8.5.sp, color = ZxT.Text3)
                    }
                }
            }
            Text("已有 diff / baseline 是给人看差异的，" +
                 "这个要回答的是**「这个结论的依据是什么」**——" +
                 "只有结论没有依据，用户只能选择信或不信",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 5.dp))
        }
    }

    /* ══════════ zx_confront 证据对质 ══════════ */
    @Composable
    fun confront(o: JSONObject, ctx: RenderContext) {
        val a = o.optJSONObject("a") ?: return
        val b = o.optJSONObject("b") ?: return
        var sel by remember { mutableIntStateOf(-1) }
        var verdict by remember { mutableStateOf(o.optString("verdict", "")) }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "冲突"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("冲突", fontSize = 9.sp, color = ZxT.Err,
                     modifier = Modifier.clip(RoundedCornerShape(8.dp))
                         .background(ZxT.Err.copy(.16f)).padding(7.dp, 3.dp))
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(a to 0, b to 1).forEach { (side, idx) ->
                    val on = sel == idx
                    Column(Modifier.weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (on) ZxT.Surface2 else ZxT.Surface2.copy(.55f))
                        .border(if (on) 1.2.dp else 0.dp, PAL[idx], RoundedCornerShape(8.dp))
                        .clickable {
                            sel = idx
                            ctx.callTool("zx_select", mapOf(
                                "side" to side.optString("n", ""),
                                "claim" to side.optString("t", ""),
                                "conf" to side.optDouble("c", .5).toString(),
                                "source" to "zx_confront"))
                        }.padding(8.dp, 7.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(6.dp).clip(CircleShape).background(PAL[idx]))
                            Spacer(Modifier.width(5.dp))
                            Text(side.optString("n", if (idx == 0) "A" else "B"),
                                 fontSize = 10.sp, color = ZxT.Text,
                                 modifier = Modifier.weight(1f), maxLines = 1,
                                 overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(side.optString("t", ""), fontSize = 10.sp, color = ZxT.Text2,
                             lineHeight = 14.sp, maxLines = 3,
                             overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(5.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("置信度", fontSize = 8.5.sp, color = ZxT.Text3)
                            Spacer(Modifier.width(4.dp))
                            Box(Modifier.weight(1f).height(5.dp)
                                .clip(RoundedCornerShape(3.dp)).background(ZxT.Surface3)) {
                                Box(Modifier.fillMaxWidth(
                                    side.optDouble("c", .5).toFloat().coerceIn(0f, 1f))
                                    .height(5.dp)
                                    .clip(RoundedCornerShape(3.dp)).background(PAL[idx]))
                            }
                            Spacer(Modifier.width(4.dp))
                            Text("%.0f%%".format(side.optDouble("c", .5) * 100),
                                 fontSize = 8.5.sp, color = ZxT.Text3,
                                 fontFamily = ZxT.Mono)
                        }
                    }
                }
            }
            Spacer(Modifier.height(7.dp))
            Text("采信", fontSize = 9.5.sp, color = ZxT.Text3)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf(a.optString("n", "A") to "A", b.optString("n", "B") to "B",
                       "都成立" to "both", "都不成立" to "none").forEach { (lab, v) ->
                    val on = verdict == v
                    Text(lab, fontSize = 10.sp,
                         color = if (on) Color.White else ZxT.Text3,
                         modifier = Modifier
                             .clip(RoundedCornerShape(9.dp))
                             .background(if (on) PAL[0] else ZxT.Surface2)
                             .clickable {
                                 verdict = if (on) "" else v
                                 ctx.callTool("zx_select", mapOf(
                                     "verdict" to v, "source" to "zx_confront"))
                             }.padding(9.dp, 4.dp))
                }
            }
            Text("两条证据冲突时，界面要能把它们**并排放着让人判**，" +
                 "而不是让模型自己悄悄选一条。采信结果回传，模型才知道下一步怎么走",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_matrix3 影响矩阵 ══════════ */
    @Composable
    fun matrix3(o: JSONObject, ctx: RenderContext) {
        val items = o.optJSONArray("items") ?: return
        val n = minOf(items.length(), 12)
        var sel by remember { mutableIntStateOf(-1) }
        val imp = (0 until n).map { items.optJSONObject(it)?.optDouble("i", .5) ?: .5 }
        val eff = (0 until n).map { items.optJSONObject(it)?.optDouble("e", .5) ?: .5 }
        // 四象限分界 0.5
        fun quad(i: Int) = when {
            imp[i] >= .5 && eff[i] >= .5 -> "重点做"
            imp[i] >= .5 -> "排期做"
            eff[i] >= .5 -> "顺手做"
            else -> "别做"
        }
        val QC = mapOf("重点做" to ZxT.Ok, "排期做" to ZxT.PriLite,
                       "顺手做" to ZxT.Info, "别做" to ZxT.Text3)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "影响矩阵"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("${imp.count { it >= .5 }} 项重要", fontSize = 9.5.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(150.dp)) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    // 象限底色——不铺底色看不出四象限
                    val w = size.width; val h = size.height
                    drawRect(ZxT.Surface3, Offset(w / 2, 0f), Size(w / 2, h / 2))
                    drawRect(ZxT.Surface2, Offset(0f, h / 2), Size(w / 2, h / 2))
                    drawLine(ZxT.Text3, Offset(w / 2, 0f), Offset(w / 2, h), 1f)
                    drawLine(ZxT.Text3, Offset(0f, h / 2), Offset(w, h / 2), 1f)
                    for (i in 0 until n) {
                        val x = imp[i].toFloat().coerceIn(0f, 1f) * w
                        // y 翻转：影响力越大越靠上
                        val y = h - eff[i].toFloat().coerceIn(0f, 1f) * h
                        val col = QC[quad(i)] ?: ZxT.Text3
                        drawCircle(col.copy(.3f), 13f, Offset(x, y))
                        drawCircle(if (sel == i) ZxT.Text else col, 6f, Offset(x, y))
                    }
                }
                Row(Modifier.fillMaxWidth().height(150.dp)) {
                    (0 until n).forEach { i ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = i
                            ctx.callTool("zx_select", mapOf(
                                "choice" to items.optJSONObject(i)?.optString("n", "$i"),
                                "quad" to quad(i), "source" to "zx_matrix3"))
                        })
                    }
                }
                // 象限标签
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth()) {
                        Spacer(Modifier.weight(1f))
                        Text("重点做", fontSize = 8.sp, color = ZxT.Text3,
                             modifier = Modifier.padding(3.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    Row(Modifier.fillMaxWidth()) {
                        Text("别做", fontSize = 8.sp, color = ZxT.Text3,
                             modifier = Modifier.padding(3.dp))
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text("重要性 →", fontSize = 9.sp, color = ZxT.Text3,
                     modifier = Modifier.weight(1f))
                Text("↑ 影响力", fontSize = 9.sp, color = ZxT.Text3)
            }
            if (sel >= 0) {
                val it = items.optJSONObject(sel)
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text(it?.optString("n", "") ?: "", fontSize = 10.5.sp, color = ZxT.Text)
                    Spacer(Modifier.width(6.dp))
                    Text(quad(sel), fontSize = 10.sp, color = QC[quad(sel)] ?: ZxT.Text3)
                    Spacer(Modifier.weight(1f))
                    Text("i=${"%.2f".format(imp[sel])} e=${"%.2f".format(eff[sel])}",
                         fontSize = 9.sp, color = ZxT.Text3, fontFamily = ZxT.Mono)
                }
            }
            Text("已有 matrix（热力矩阵）、scatter（散点，若有），" +
                 "但**四象限要给结论**（重点做/别做）。只画点，用户还得自己判",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 5.dp))
        }
    }

    /* ══════════ zx_receipt 回执凭据 ══════════ */
    @Composable
    fun receipt(o: JSONObject, ctx: RenderContext) {
        val id = o.optString("id", "—")
        val at = o.optString("at", "—")
        val rows = o.optJSONArray("rows")
        val n = minOf(rows?.length() ?: 0, 8)
        val status = o.optString("st", "ok")
        val col = when (status) { "ok" -> ZxT.Ok; "warn" -> ZxT.Warn; else -> ZxT.Err }
        var copied by remember { mutableStateOf(false) }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(col))
                Spacer(Modifier.width(6.dp))
                Text(o.optString("n", "回执"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(when (status) { "ok" -> "成功"; "warn" -> "部分成功"; else -> "失败" },
                     fontSize = 9.5.sp, color = col)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(8.dp)).background(ZxT.Surface2)
                .padding(10.dp, 8.dp)) {
                Column {
                    Row {
                        Text("单号", fontSize = 9.sp, color = ZxT.Text3,
                             modifier = Modifier.width(44.dp))
                        Text(id, fontSize = 10.sp, color = ZxT.Text,
                             fontFamily = ZxT.Mono)
                    }
                    Spacer(Modifier.height(3.dp))
                    Row {
                        Text("时间", fontSize = 9.sp, color = ZxT.Text3,
                             modifier = Modifier.width(44.dp))
                        Text(at, fontSize = 10.sp, color = ZxT.Text2,
                             fontFamily = ZxT.Mono)
                    }
                    if (n > 0) {
                        Spacer(Modifier.height(5.dp))
                        Box(Modifier.fillMaxWidth().height(1.dp).background(ZxT.Surface3))
                        Spacer(Modifier.height(5.dp))
                        for (i in 0 until n) {
                            val r = rows!!.optJSONObject(i) ?: continue
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                Text(r.optString("k", ""), fontSize = 9.sp,
                                     color = ZxT.Text3, modifier = Modifier.width(44.dp),
                                     maxLines = 1, overflow = TextOverflow.Clip)
                                Text(r.optString("v", ""), fontSize = 9.5.sp,
                                     color = ZxT.Text2, modifier = Modifier.weight(1f),
                                     maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(if (copied) "已复制" else "复制单号", fontSize = 10.sp,
                     color = if (copied) ZxT.Ok else ZxT.PriLite,
                     modifier = Modifier.clip(RoundedCornerShape(8.dp))
                         .background(ZxT.Surface2)
                         .clickable {
                             copied = true
                             ctx.callTool("zx_copy", mapOf("text" to id))
                         }.padding(9.dp, 4.dp))
                Text("查看", fontSize = 10.sp, color = ZxT.Text3,
                     modifier = Modifier.clip(RoundedCornerShape(8.dp))
                         .background(ZxT.Surface2)
                         .clickable {
                             ctx.callTool("zx_select", mapOf("id" to id,
                                                             "source" to "zx_receipt"))
                         }.padding(9.dp, 4.dp))
                Spacer(Modifier.weight(1f))
                Text("单号要能一键复制——让人手抄就是在制造错误",
                     fontSize = 8.5.sp, color = ZxT.Text3,
                     modifier = Modifier.align(Alignment.CenterVertically))
            }
        }
    }

    /* ══════════ zx_ledger 流水账 ══════════ */
    @Composable
    fun ledger(o: JSONObject, ctx: RenderContext) {
        val rows = o.optJSONArray("rows") ?: return
        val n = minOf(rows.length(), 10)
        var sel by remember { mutableIntStateOf(-1) }
        var running = 0.0
        val bal = (0 until n).map { i ->
            val d = rows.optJSONObject(i)?.optDouble("d", 0.0) ?: 0.0
            running += d
            running
        }
        val minB = bal.minOrNull() ?: 0.0
        val maxB = bal.maxOrNull() ?: 0.0
        val span = (maxB - minB).coerceAtLeast(1e-6)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "流水"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("余额 %.0f".format(running), fontSize = 10.5.sp,
                     color = if (running >= 0) ZxT.Ok else ZxT.Err,
                     fontFamily = ZxT.Mono)
            }
            Spacer(Modifier.height(6.dp))
            for (i in 0 until n) {
                val r = rows.optJSONObject(i) ?: continue
                val d = r.optDouble("d", 0.0)
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == i) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        sel = i
                        ctx.callTool("zx_select", mapOf(
                            "item" to r.optString("n", ""),
                            "d" to d.toString(), "bal" to bal[i].toString(),
                            "source" to "zx_ledger"))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(r.optString("n", ""), fontSize = 10.sp, color = ZxT.Text2,
                         modifier = Modifier.weight(1f), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    // 余额走势：迷你折线，看出"是在涨还是在跌"
                    Box(Modifier.width(46.dp).height(14.dp)) {
                        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                            if (n < 2) return@Canvas
                            val path = Path()
                            for (k in 0 until n) {
                                val x = size.width * k / (n - 1)
                                val y = size.height - ((bal[k] - minB) / span).toFloat()
                                    .coerceIn(0f, 1f) * size.height
                                if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
                            }
                            drawPath(path, PAL[1].copy(.55f), style = Stroke(1.4f))
                            val xi = size.width * i / (n - 1).coerceAtLeast(1)
                            val yi = size.height - ((bal[i] - minB) / span).toFloat()
                                .coerceIn(0f, 1f) * size.height
                            drawCircle(if (sel == i) ZxT.Text else PAL[1], 2.6f, Offset(xi, yi))
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    Text("%+.0f".format(d), fontSize = 10.sp,
                         color = if (d >= 0) ZxT.Ok else ZxT.Err,
                         fontFamily = ZxT.Mono, modifier = Modifier.width(38.dp),
                         textAlign = TextAlign.End)
                    Text("%.0f".format(bal[i]), fontSize = 9.sp, color = ZxT.Text3,
                         fontFamily = ZxT.Mono, modifier = Modifier.width(40.dp),
                         textAlign = TextAlign.End)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text("最低 %.0f".format(minB), fontSize = 9.sp, color = ZxT.Text3,
                     fontFamily = ZxT.Mono)
                Spacer(Modifier.weight(1f))
                Text("最高 %.0f".format(maxB), fontSize = 9.sp, color = ZxT.Text3,
                     fontFamily = ZxT.Mono)
            }
            Text("已有 table 能列，但**余额要连续算**——" +
                 "用户要看的是「这次操作之后还剩多少」，不是一堆孤立数字",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 5.dp))
        }
    }

    /* ══════════ zx_gauntlet 多轮筛选 ══════════ */
    @Composable
    fun gauntlet(o: JSONObject, ctx: RenderContext) {
        val rounds = o.optJSONArray("rounds") ?: return
        val n = minOf(rounds.length(), 6)
        var sel by remember { mutableIntStateOf(-1) }
        val counts = (0 until n).map { rounds.optJSONObject(it)?.optInt("n", 0) ?: 0 }
        val start = counts.firstOrNull() ?: 0
        val passed = remember { mutableStateListOf<Boolean>().apply {
            for (i in 0 until n) add(rounds.optJSONObject(i)?.optBoolean("pass", true) ?: true) } }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "筛选"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                val last = counts.lastOrNull() ?: 0
                Text("$start → $last", fontSize = 10.sp, color = ZxT.PriLite,
                     fontFamily = ZxT.Mono)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                for (i in 0 until n) {
                    val r = rounds.optJSONObject(i) ?: continue
                    val h = (counts[i].toFloat() / start.coerceAtLeast(1) * 68f)
                        .coerceIn(6f, 68f)
                    Column(Modifier.weight(1f),
                           horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${counts[i]}", fontSize = 9.sp,
                             color = if (sel == i) ZxT.Text else ZxT.Text3,
                             fontFamily = ZxT.Mono)
                        Spacer(Modifier.height(2.dp))
                        Box(Modifier.fillMaxWidth(.82f).height(h.dp)
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            .background(if (passed[i]) PAL[i % PAL.size].copy(.65f)
                                        else ZxT.Surface3)
                            .clickable {
                                sel = i
                                ctx.callTool("zx_select", mapOf(
                                    "round" to r.optString("n", ""),
                                    "count" to counts[i].toString(),
                                    "pass" to passed[i].toString(),
                                    "source" to "zx_gauntlet"))
                            })
                        Spacer(Modifier.height(3.dp))
                        Text(r.optString("n", ""), fontSize = 8.sp, color = ZxT.Text3,
                             maxLines = 1, overflow = TextOverflow.Clip,
                             textAlign = TextAlign.Center)
                    }
                    if (i < n - 1)
                        Text("›", fontSize = 10.sp, color = ZxT.Text3,
                             modifier = Modifier.padding(horizontal = 1.dp))
                }
            }
            if (sel >= 0) {
                val r = rounds.optJSONObject(sel)
                val drop = if (sel == 0) 0 else counts[sel - 1] - counts[sel]
                Row(Modifier.fillMaxWidth().padding(top = 5.dp)) {
                    Text(r?.optString("n", "") ?: "", fontSize = 10.5.sp, color = ZxT.Text)
                    Spacer(Modifier.width(6.dp))
                    Text(if (sel == 0) "起始 $start 项"
                         else "淘汰 $drop 项（剩 ${counts[sel]}）",
                         fontSize = 10.sp,
                         color = if (sel == 0) ZxT.Text3 else ZxT.Err)
                    Spacer(Modifier.weight(1f))
                    Text(if (passed[sel]) "本轮通过" else "本轮中止", fontSize = 9.5.sp,
                         color = if (passed[sel]) ZxT.Ok else ZxT.Text3)
                }
            }
            Text("已有 funnel（漏斗）画的是转化，这个画的是**淘汰**——" +
                 "每轮筛掉多少、在哪一轮被卡住，这才是排查筛选条件时要看的",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 5.dp))
        }
    }

    /* ══════════ zx_chain 依赖链 ══════════ */
    @Composable
    fun chain(o: JSONObject, ctx: RenderContext) {
        val links = o.optJSONArray("links") ?: return
        val n = minOf(links.length(), 8)
        var sel by remember { mutableIntStateOf(-1) }
        // 最弱一环：强度最低的那节决定整条链的强度
        val strs = (0 until n).map { links.optJSONObject(it)?.optDouble("s", .5) ?: .5 }
        val weakest = strs.indices.minByOrNull { strs[it] } ?: 0
        val chainStr = strs.minOrNull() ?: .5

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "依赖链"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("整链 %.0f%%".format(chainStr * 100), fontSize = 10.5.sp,
                     color = when { chainStr > .8 -> ZxT.Ok
                                    chainStr > .5 -> ZxT.Warn; else -> ZxT.Err },
                     fontFamily = ZxT.Mono)
            }
            Spacer(Modifier.height(7.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                for (i in 0 until n) {
                    val l = links.optJSONObject(i) ?: continue
                    val on = sel == i
                    val isWeak = i == weakest
                    Column(Modifier.weight(1f),
                           horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.fillMaxWidth(.88f).height(34.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (on) PAL[0] else ZxT.Surface2)
                            .border(if (isWeak) 1.4.dp else 0.dp, ZxT.Err,
                                    RoundedCornerShape(6.dp))
                            .clickable {
                                sel = i
                                ctx.callTool("zx_select", mapOf(
                                    "link" to l.optString("n", ""),
                                    "s" to strs[i].toString(),
                                    "weakest" to isWeak.toString(),
                                    "source" to "zx_chain"))
                            }, contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(l.optString("n", ""), fontSize = 9.sp,
                                     color = if (on) Color.White else ZxT.Text,
                                     maxLines = 1, overflow = TextOverflow.Clip)
                                Text("%.0f%%".format(strs[i] * 100), fontSize = 8.5.sp,
                                     color = if (on) Color.White.copy(.75f)
                                             else if (isWeak) ZxT.Err else ZxT.Text3,
                                     fontFamily = ZxT.Mono)
                            }
                        }
                        Spacer(Modifier.height(3.dp))
                        // 链节：连接处画个小环
                        if (i < n - 1)
                            Box(Modifier.size(6.dp)
                                .clip(CircleShape)
                                .background(if (isWeak || i + 1 == weakest) ZxT.Err
                                            else ZxT.Text3))
                    }
                    if (i < n - 1) Spacer(Modifier.width(2.dp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Text("最弱一环：", fontSize = 9.5.sp, color = ZxT.Text3)
                Text(links.optJSONObject(weakest)?.optString("n", "") ?: "",
                     fontSize = 10.sp, color = ZxT.Err)
                Spacer(Modifier.weight(1f))
                Text("整链强度 = 最弱一节", fontSize = 8.5.sp, color = ZxT.Text3)
            }
            Text("已有 depgraph / dag 画的是结构，这个要指出**瓶颈在哪一节**——" +
                 "补强最弱的一环才有意义，补强的环节没用",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 5.dp))
        }
    }

    /* ══════════ zx_bracket 淘汰对阵 ══════════ */
    @Composable
    fun bracket(o: JSONObject, ctx: RenderContext) {
        val rounds = o.optJSONArray("rounds") ?: return
        val n = minOf(rounds.length(), 4)
        var sel by remember { mutableStateOf<Pair<Int, Int>?>(null) }

        Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                for (r in 0 until n) {
                    val arr = rounds.optJSONArray(r) ?: continue
                    val m = minOf(arr.length(), 8)
                    Column(Modifier.width(76.dp)) {
                        Text(o.optJSONArray("names")?.optString(r, "第 ${r + 1} 轮")
                             ?: "第 ${r + 1} 轮", fontSize = 8.5.sp, color = ZxT.Text3,
                             modifier = Modifier.padding(bottom = 3.dp))
                        for (i in 0 until m) {
                            val g = arr.optJSONObject(i) ?: continue
                            val on = sel?.first == r && sel?.second == i
                            // 每轮间隔递增：视觉上形成对阵树的收敛
                            val top = if (r == 0) 0 else (i % 2) * 0
                            Column(Modifier.fillMaxWidth()
                                .padding(vertical = if (r == 0) 2.dp else (8 * (1 shl (r - 1))).dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(if (on) ZxT.Surface2 else ZxT.Surface2.copy(.5f))
                                .clickable {
                                    sel = r to i
                                    ctx.callTool("zx_select", mapOf(
                                        "a" to g.optString("a", ""),
                                        "b" to g.optString("b", ""),
                                        "win" to g.optString("w", ""),
                                        "round" to r.toString(),
                                        "source" to "zx_bracket"))
                                }.padding(5.dp, 3.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(g.optString("a", "—"), fontSize = 8.5.sp,
                                         color = if (g.optString("w", "") == g.optString("a", ""))
                                             ZxT.Text else ZxT.Text3,
                                         modifier = Modifier.weight(1f), maxLines = 1,
                                         overflow = TextOverflow.Clip)
                                    Text(g.optString("sa", "—"), fontSize = 8.sp,
                                         color = ZxT.Text3, fontFamily = ZxT.Mono)
                                }
                                Spacer(Modifier.height(1.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(g.optString("b", "—"), fontSize = 8.5.sp,
                                         color = if (g.optString("w", "") == g.optString("b", ""))
                                             ZxT.Text else ZxT.Text3,
                                         modifier = Modifier.weight(1f), maxLines = 1,
                                         overflow = TextOverflow.Clip)
                                    Text(g.optString("sb", "—"), fontSize = 8.sp,
                                         color = ZxT.Text3, fontFamily = ZxT.Mono)
                                }
                            }
                        }
                    }
                    if (r < n - 1)
                        Text("›", fontSize = 12.sp, color = ZxT.Text3,
                             modifier = Modifier.padding(horizontal = 3.dp))
                }
            }
            if (sel != null) {
                val g = rounds.optJSONArray(sel!!.first)?.optJSONObject(sel!!.second)
                if (g != null)
                    Text("第 ${sel!!.first + 1} 轮：${g.optString("a")} ${g.optString("sa", "")} " +
                         "vs ${g.optString("b")} ${g.optString("sb", "")} → " +
                         "胜 ${g.optString("w", "")}",
                         fontSize = 10.sp, color = ZxT.PriLite,
                         modifier = Modifier.padding(top = 5.dp))
            }
            Text("已有 tree / ladder 是层级与排名，这个表达的是 " +
                 "**两两淘汰、谁赢了谁**——并且要能看出一路怎么走过来的",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 5.dp))
        }
    }
}
