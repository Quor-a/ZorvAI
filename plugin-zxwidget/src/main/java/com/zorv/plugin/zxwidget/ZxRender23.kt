package com.zorv.plugin.zxwidget

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * 第二十三批组件（12 个）—— 推演与规则操纵。
 *
 * 前二十二批铺完了"看什么、填什么、改什么、凭什么、多密、\n* 怎么对齐、怎么撤回、谁在改"。剩下的是一类
 * **一直靠人脑做、界面帮不上忙**的事：
 *
 *   一、**推演**。"如果这样会怎样"——
 *       决策树要能沿路径求值知道落在哪个叶子（branch 只画结构），
 *       权衡卡要能看出动一个另一个怎么变（slidergroup 是分蛋糕，
 *       这个是两个目标互相打架），
 *       降级链要能看出主方案挂了走哪个备胎（statemachine 是状态，
 *       这个是"主→备"的**策略链**），
 *       影响波及要能看出改这个会波及哪些（chain 找最弱一节，
 *       这个是**反向**：从"改谁"出发找"谁受害"），
 *       重试卡要能看出下一次什么时候试、还要试几次。
 *
 *   二、**规则操纵**。这批改的往往不是"一个值"，
 *       而是"**系统怎么运转**"：
 *       决策树是规则、权衡是约束、降级链是策略、
 *       重试是策略、重映射是替换规则、批量是动作。
 *       改这些的后果比改一个单元格大得多，
 *       所以每个都要**先看后果再提交**。
 *
 *   三、**操纵的载体**。
 *       区间刷选是在有数据的图上拖区间（crop 是图片、
 *       range 没有数据背景），
 *       优先级象限可拖动改归属（matrix 是点选、
 *       board 是自由画布、两个都不是"象限归属"），
 *       探针是此刻对 N 个端点各探一次（uptime 是时间序列，
 *       这个是**快照**），
 *       检查器是字段级来源追溯（keyvalue 只展示值，
 *       不回答"这个值哪来的"），
 *       汇总树是缩进表格+父值=子和（treeview 有勾选半选，
 *       icicle 是矩形树图，都不是"带聚合值的缩进表"）。
 *
 * 贯穿这批的一条：**能推演出"如果这样会怎样"**。
 *
 *   展示已经发生的事 ≠ 能预测将发生什么。
 *   决策树画出来但不求值，用户得自己在脑子里走一遍；
 *   权衡卡只给两个滑块不给后果，用户得自己算；
 *   波及图只画依赖不给"改这个会怎样"，等于没画。
 *
 * 校验重心也跟着变：这批查的是**"推不出结论"**——
 *   决策树有叶子没结果（走到头却不知道答案）
 *   条件无出口（输入会卡在中间节点）
 *   权衡两个目标同向（那不是权衡，是并列）
 *   降级链没有终点（备胎也挂了就无处可去）
 *   重映射有环（a→b→a 永远替换不完）
 *   汇总树父值 ≠ 子和（汇总不可信）
 *
 * 这些都不崩，只是让用户面对一个**看起来完整、
 * 实际推不出结论**的界面——比崩溃更糟，
 * 因为模型会以为下发成功了。
 */
object ZxRender23 {

    private val PAL = listOf(
        Color(0xFF5046E4), Color(0xFF3FBF7F), Color(0xFFE8A33D),
        Color(0xFFE5544B), Color(0xFF4A9BE8), Color(0xFF8E86FF)
    )

    /* ══════════ zx_decision 决策树（可求值） ══════════ */
    @Composable
    fun decision(o: JSONObject, ctx: RenderContext) {
        val nodes = o.optJSONArray("nodes") ?: return
        val n = minOf(nodes.length(), 12)
        // 输入：用户点选，树沿路径求值——画出来不求值等于让用户自己走一遍
        val inputs = remember { mutableStateMapOf<Int, String>() }
        var path by remember { mutableStateOf<List<Int>>(emptyList()) }
        var leaf by remember { mutableStateOf<String?>(null) }

        fun eval() {
            val walked = ArrayList<Int>()
            var cur = 0
            var guard = 0
            while (guard++ < n) {
                val nd = nodes.optJSONObject(cur) ?: break
                walked.add(cur)
                if (nd.optBoolean("leaf", false)) { leaf = nd.optString("r", ""); break }
                val pick = inputs[cur]
                val nxt = if (pick.isNullOrBlank()) -1 else nd.optInt("y$pick", -1)
                if (nxt < 0 || nxt >= n) { leaf = null; break }   // 走不下去
                cur = nxt
            }
            path = walked
        }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "决策树"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (leaf != null) "已求值" else "待输入", fontSize = 9.5.sp,
                     color = if (leaf != null) ZxT.Ok else ZxT.Text3)
            }
            Spacer(Modifier.height(6.dp))
            for (i in 0 until n) {
                val nd = nodes.optJSONObject(i) ?: continue
                val isLeaf = nd.optBoolean("leaf", false)
                val onPath = i in path
                val cond = nd.optString("c", "")
                val branches = nd.optJSONArray("b")
                Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (onPath) ZxT.Surface2 else Color.Transparent)
                    .padding(6.dp, 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (isLeaf) "◉" else (if (onPath) "▸" else "○"),
                             fontSize = 9.sp,
                             color = if (isLeaf) ZxT.Ok else
                                 (if (onPath) PAL[0] else ZxT.Text3))
                        Spacer(Modifier.width(6.dp))
                        Text(if (isLeaf) nd.optString("r", "") else cond,
                             fontSize = 10.sp,
                             color = if (onPath) ZxT.Text else ZxT.Text2,
                             modifier = Modifier.weight(1f), lineHeight = 13.sp)
                    }
                    if (!isLeaf && branches != null) {
                        Row(Modifier.fillMaxWidth().padding(start = 15.dp, top = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (k in 0 until minOf(branches.length(), 3)) {
                                val b = branches.optString(k, "")
                                val on = inputs[i] == (k + 1).toString()
                                Text(b, fontSize = 9.sp,
                                     color = if (on) Color.White else ZxT.Text3,
                                     modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                         .background(if (on) PAL[0] else ZxT.Surface2)
                                         .clickable {
                                             inputs[i] = (k + 1).toString()
                                             eval()
                                             ctx.callTool("zx_select", mapOf(
                                                 "node" to i.toString(),
                                                 "branch" to b, "source" to "zx_decision"))
                                         }.padding(8.dp, 2.dp))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(5.dp))
            Row(Modifier.fillMaxWidth()) {
                Text(leaf?.let { "结论：$it" }
                    ?: if (path.isNotEmpty()) "还没走完，继续选"
                    else "从第一个条件开始选",
                    fontSize = 10.sp,
                    color = leaf?.let { ZxT.Ok } ?: ZxT.Text3,
                    modifier = Modifier.weight(1f), lineHeight = 13.sp)
                leaf?.let {
                    ctx.callTool("zx_select", mapOf(
                        "result" to it, "path" to path.joinToString(">"),
                        "source" to "zx_decision"))
                }
            }
            Text("已有 branch 画**结构**、traceback 看**已发生的分叉**，" +
                 "这个是**规则本身 + 沿路径求值**——点选条件就知道落在哪个叶子。" +
                 "画出来但不求值，用户得自己在脑子里走一遍",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_tradeoff 权衡卡 ══════════ */
    @Composable
    fun tradeoff(o: JSONObject, ctx: RenderContext) {
        val a = o.optString("a", "目标A")
        val b = o.optString("b", "目标B")
        // 两个互斥目标：一动另一个反向动。给滑块不给后果，用户得自己算
        var t by remember { mutableFloatStateOf(o.optDouble("t", 0.5).toFloat().coerceIn(0f, 1f)) }
        val pts = o.optJSONArray("front")
        val fa = { x: Float -> if (o.optBoolean("linear", true)) x else 1f - (1f - x).pow(2f) }
        val fb = { x: Float -> 1f - x }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "权衡"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("%.0f / %.0f".format(fa(t) * 100, fb(t) * 100), fontSize = 9.5.sp,
                     color = ZxT.Text3, fontFamily = ZxT.Mono)
            }
            Spacer(Modifier.height(6.dp))
            // Pareto 前沿：能看出"已经贴着前沿了，再要 A 就得放弃多少 B"
            Box(Modifier.fillMaxWidth().height(96.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height
                    drawRoundRect(ZxT.Surface2, Offset(0f, 0f), Size(W, H),
                                  cornerRadius =
                                  androidx.compose.ui.geometry.CornerRadius(6f, 6f))
                    // 前沿曲线
                    if (pts != null && pts.length() >= 2) {
                        drawPath(Path().apply {
                            for (k in 0 until pts.length()) {
                                val p = pts.optJSONObject(k) ?: continue
                                val x = p.optDouble("a", 0.0).toFloat().coerceIn(0f, 1f)
                                val y = (1.0 - p.optDouble("b", 0.0)).toFloat().coerceIn(0f, 1f)
                                val px = x * W; val py = y * H
                                if (k == 0) moveTo(px, py) else lineTo(px, py)
                            } }, PAL[0].copy(.45f), style = Stroke(1.6f))
                    } else {
                        // 默认前沿：y = 1 - x 的凹曲线，说明"边际收益递减"
                        drawPath(Path().apply {
                            for (k in 0..40) {
                                val x = k / 40f
                                val y = (1f - x).pow(1.6f)
                                val px = x * W; val py = y * H
                                if (k == 0) moveTo(px, py) else lineTo(px, py)
                            } }, PAL[0].copy(.45f), style = Stroke(1.6f))
                    }
                    // 当前点
                    val px = t * W; val py = fb(t) * H
                    drawLine(ZxT.Surface3, Offset(px, 0f), Offset(px, H), 1f)
                    drawLine(ZxT.Surface3, Offset(0f, py), Offset(W, py), 1f)
                    drawCircle(PAL[0], 5.5f, Offset(px, py))
                    drawCircle(Color.White, 2.2f, Offset(px, py))
                }
                Row(Modifier.fillMaxWidth().height(96.dp)) {
                    for (k in 0..20) {
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            t = k / 20f
                            ctx.callTool("zx_select", mapOf(
                                "t" to t.toString(),
                                "a" to (fa(t) * 100).toInt().toString(),
                                "b" to (fb(t) * 100).toInt().toString(),
                                "source" to "zx_tradeoff"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text("← $a ${(fa(t) * 100).toInt()}", fontSize = 8.5.sp,
                     color = PAL[0], modifier = Modifier.weight(1f))
                Text("$b ${(fb(t) * 100).toInt()} →", fontSize = 8.5.sp,
                     color = PAL[1], textAlign = TextAlign.End)
            }
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("A", fontSize = 8.5.sp, color = ZxT.Text3,
                     modifier = Modifier.width(14.dp))
                Box(Modifier.weight(1f).height(6.dp)
                    .clip(RoundedCornerShape(3.dp)).background(ZxT.Surface3)) {
                    Box(Modifier.fillMaxWidth(fa(t)).fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp)).background(PAL[0]))
                }
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("B", fontSize = 8.5.sp, color = ZxT.Text3,
                     modifier = Modifier.width(14.dp))
                Box(Modifier.weight(1f).height(6.dp)
                    .clip(RoundedCornerShape(3.dp)).background(ZxT.Surface3)) {
                    Box(Modifier.fillMaxWidth(fb(t)).fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp)).background(PAL[1]))
                }
            }
            Text("已有 slidergroup 是**分蛋糕**（总额固定，几项平分），" +
                 "这个是**两个目标互相打架**——要 A 就得放弃 B，曲线是 Pareto 前沿。" +
                 "只给滑块不给后果，用户得自己算放弃了多少",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_fallback 降级链 ══════════ */
    @Composable
    fun fallback(o: JSONObject, ctx: RenderContext) {
        val links = o.optJSONArray("links") ?: return
        val n = minOf(links.length(), 6)
        var cur by remember { mutableIntStateOf(o.optInt("cur", 0).coerceIn(0, n - 1)) }
        var sel by remember { mutableIntStateOf(-1) }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "降级链"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("当前第 ${cur + 1} 级", fontSize = 9.5.sp, color = ZxT.Warn)
            }
            Spacer(Modifier.height(6.dp))
            for (i in 0 until n) {
                val lk = links.optJSONObject(i) ?: continue
                val st = lk.optInt("st", 0)      // 0 正常 / 1 降级中 / 2 已挂
                val on = sel == i
                val active = i == cur
                val col = if (st == 2) ZxT.Err else (if (active) ZxT.Warn else PAL[0])
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (on) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        sel = i
                        ctx.callTool("zx_select", mapOf(
                            "level" to i.toString(), "name" to lk.optString("n", ""),
                            "state" to st.toString(), "source" to "zx_fallback"))
                    }.padding(6.dp, 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if (st == 2) "✕" else (if (active) "●" else "○"),
                         fontSize = 9.sp, color = col,
                         modifier = Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(lk.optString("n", ""), fontSize = 10.sp,
                             color = if (st == 2) ZxT.Text3 else
                                 (if (active) ZxT.Text else ZxT.Text2),
                             maxLines = 1, overflow = TextOverflow.Ellipsis)
                        lk.optString("cost").takeIf { it.isNotBlank() }?.let {
                            Text("代价：$it", fontSize = 8.5.sp, color = ZxT.Text3,
                                 maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    // 最后一级没有下一级可去：必须让用户看见这是尽头
                    if (i == n - 1)
                        Text("终点", fontSize = 8.sp, color = ZxT.Err,
                             modifier = Modifier.clip(RoundedCornerShape(7.dp))
                                 .background(ZxT.Err.copy(.14f)).padding(6.dp, 1.dp))
                    else Text("↓", fontSize = 9.sp, color = ZxT.Text3)
                }
            }
            Spacer(Modifier.height(5.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("降级一级", fontSize = 9.5.sp, color = ZxT.Text3,
                     modifier = Modifier.clip(RoundedCornerShape(9.dp))
                         .background(ZxT.Surface2).clickable {
                             if (cur < n - 1) {
                                 cur++
                                 ctx.callTool("zx_select", mapOf(
                                     "fallback_to" to cur.toString(),
                                     "source" to "zx_fallback"))
                             }
                         }.padding(9.dp, 3.dp))
                Text("恢复主链路", fontSize = 9.5.sp, color = ZxT.Text3,
                     modifier = Modifier.clip(RoundedCornerShape(9.dp))
                         .background(ZxT.Surface2).clickable {
                             cur = 0
                             ctx.callTool("zx_select", mapOf(
                                 "fallback_to" to "0", "source" to "zx_fallback"))
                         }.padding(9.dp, 3.dp))
            }
            Text("已有 statemachine 是**状态迁移**、uptime 是**时间序列**，" +
                 "这个是**「主→备」的策略链**——主方案挂了走哪个备胎、代价是什么。" +
                 "最后一级必须标「终点」，否则用户以为还能再降",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_retry 重试策略卡 ══════════ */
    @Composable
    fun retry(o: JSONObject, ctx: RenderContext) {
        val maxN = o.optInt("max", 5).coerceIn(1, 10)
        var used by remember { mutableIntStateOf(o.optInt("used", 0).coerceIn(0, maxN)) }
        val base = o.optDouble("base", 1.0).coerceAtLeast(0.1)
        val factor = o.optDouble("factor", 2.0).coerceAtLeast(1.0)
        val cap = o.optDouble("cap", 60.0).coerceAtLeast(base)
        // 退避：第 k 次的等待 = min(base * factor^k, cap)
        val waits = (0 until maxN).map { minOf(base * factor.pow(it.toDouble()), cap) }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "重试策略"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("$used/$maxN", fontSize = 9.5.sp,
                     color = if (used >= maxN) ZxT.Err else ZxT.Text3,
                     fontFamily = ZxT.Mono)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                val mx = waits.maxOrNull() ?: 1.0
                waits.forEachIndexed { k, w ->
                    val h = (w / mx * 44f).toFloat().coerceAtLeast(6f)
                    val done = k < used
                    val next = k == used
                    Column(Modifier.weight(1f),
                           horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("%.0fs".format(w), fontSize = 7.5.sp,
                             color = if (done) ZxT.Text3 else
                                 (if (next) ZxT.Warn else ZxT.Text2),
                             fontFamily = ZxT.Mono)
                        Spacer(Modifier.height(2.dp))
                        Box(Modifier.fillMaxWidth().height(h.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (done) ZxT.Surface3
                            else (if (next) ZxT.Warn else PAL[0].copy(.65f)))
                            .clickable {
                                used = k
                                ctx.callTool("zx_select", mapOf(
                                    "attempt" to k.toString(),
                                    "wait" to w.toString(), "source" to "zx_retry"))
                            })
                    }
                }
            }
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (used >= maxN) "已用尽，不再重试"
                else "下次等待 %.1fs".format(waits[used]),
                    fontSize = 10.sp,
                    color = if (used >= maxN) ZxT.Err else ZxT.Warn,
                    modifier = Modifier.weight(1f))
                Text("第 ${used + 1} 次", fontSize = 8.5.sp, color = ZxT.Text3,
                     fontFamily = ZxT.Mono)
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("试一次", fontSize = 9.5.sp,
                     color = if (used >= maxN) ZxT.Text3 else Color.White,
                     modifier = Modifier.clip(RoundedCornerShape(9.dp))
                         .background(if (used >= maxN) ZxT.Surface3 else PAL[0])
                         .clickable {
                             if (used < maxN) used++
                             ctx.callTool("zx_select", mapOf(
                                 "retry" to used.toString(), "source" to "zx_retry"))
                         }.padding(10.dp, 3.dp))
                Text("重置", fontSize = 9.5.sp, color = ZxT.Text3,
                     modifier = Modifier.clip(RoundedCornerShape(9.dp))
                         .background(ZxT.Surface2).clickable { used = 0 }
                         .padding(10.dp, 3.dp))
                Spacer(Modifier.weight(1f))
                Text("cap ${"%.0f".format(cap)}s", fontSize = 8.5.sp,
                     color = ZxT.Text3, fontFamily = ZxT.Mono)
            }
            Text("已有 cmd 能重跑一条、cmdhist 记录跑过什么，" +
                 "但都回答不了「**下一次什么时候试、还要试几次**」——" +
                 "这个把退避曲线画出来，当前这次标橙。" +
                 "没有 cap 的退避会无限增长，必须看得见上限",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_brush 区间刷选 ══════════ */
    @Composable
    fun brush(o: JSONObject, ctx: RenderContext) {
        val data = o.optJSONArray("data") ?: return
        val n = minOf(data.length(), 48)
        val vals = (0 until n).map { data.optDouble(it, 0.0) }
        val mn = vals.minOrNull() ?: 0.0
        val mx = vals.maxOrNull() ?: 1.0
        val span = (mx - mn).coerceAtLeast(1e-9)
        var lo by remember { mutableIntStateOf(o.optInt("lo", 0).coerceIn(0, n - 1)) }
        var hi by remember { mutableIntStateOf(o.optInt("hi", n - 1).coerceIn(0, n - 1)) }
        val inRange = (lo..hi).map { vals[it] }
        val sum = inRange.sum(); val avg = if (inRange.isEmpty()) 0.0 else sum / inRange.size

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "区间刷选"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("$lo–$hi（${hi - lo + 1} 点）", fontSize = 9.sp, color = ZxT.Text3,
                     fontFamily = ZxT.Mono)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(64.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height
                    val bw = W / n
                    for (k in 0 until n) {
                        if (k < lo || k > hi) continue
                        val t = ((vals[k] - mn) / span).toFloat().coerceIn(0f, 1f)
                        val h = (t * H * .92f).coerceAtLeast(2f)
                        drawRoundRect(PAL[0], Offset(k * bw + .5f, H - h),
                                      Size((bw - 1f).coerceAtLeast(1f), h),
                                      cornerRadius =
                                      androidx.compose.ui.geometry.CornerRadius(1.5f, 1.5f))
                    }
                    // 未选中的画暗色：不画就不知道自己排除了什么
                    for (k in 0 until n) {
                        if (k in lo..hi) continue
                        val t = ((vals[k] - mn) / span).toFloat().coerceIn(0f, 1f)
                        val h = (t * H * .92f).coerceAtLeast(2f)
                        drawRoundRect(ZxT.Surface3, Offset(k * bw + .5f, H - h),
                                      Size((bw - 1f).coerceAtLeast(1f), h),
                                      cornerRadius =
                                      androidx.compose.ui.geometry.CornerRadius(1.5f, 1.5f))
                    }
                }
                Row(Modifier.fillMaxWidth().height(64.dp)) {
                    for (k in 0 until n) {
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            // 点一下：离哪边近就移动哪边
                            if (abs(k - lo) <= abs(k - hi)) lo = minOf(k, hi)
                            else hi = maxOf(k, lo)
                            ctx.callTool("zx_select", mapOf(
                                "lo" to lo.toString(), "hi" to hi.toString(),
                                "avg" to avg.toString(), "source" to "zx_brush"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text("区间均值 %.1f".format(avg), fontSize = 9.5.sp,
                     color = ZxT.PriLite, modifier = Modifier.weight(1f))
                Text("合计 %.1f".format(sum), fontSize = 8.5.sp, color = ZxT.Text3,
                     fontFamily = ZxT.Mono)
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("全选", fontSize = 9.sp, color = ZxT.Text3,
                     modifier = Modifier.clip(RoundedCornerShape(8.dp))
                         .background(ZxT.Surface2).clickable { lo = 0; hi = n - 1 }
                         .padding(8.dp, 2.dp))
                Text("收窄", fontSize = 9.sp, color = ZxT.Text3,
                     modifier = Modifier.clip(RoundedCornerShape(8.dp))
                         .background(ZxT.Surface2).clickable {
                             val c = (lo + hi) / 2
                             lo = maxOf(lo, c - 1); hi = minOf(hi, c + 1) }
                         .padding(8.dp, 2.dp))
            }
            Text("已有 crop 是**图片裁剪**、range 是**无数据背景的双滑块**，" +
                 "这个是**在有数据的图上拖区间**——选中的高亮、未选中的画暗色。" +
                 "未选中的不画，用户就不知道自己排除了什么",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_blast 影响波及 ══════════ */
    @Composable
    fun blast(o: JSONObject, ctx: RenderContext) {
        val nodes = o.optJSONArray("nodes") ?: return
        val n = minOf(nodes.length(), 10)
        val edges = o.optJSONArray("edges")
        var root by remember { mutableIntStateOf(o.optInt("root", 0).coerceIn(0, n - 1)) }
        // 反向 BFS：从"改谁"出发找"谁受害"
        val hit = remember(root) {
            val seen = LinkedHashMap<Int, Int>()
            val q = ArrayDeque<Pair<Int, Int>>()
            q.add(root to 0); seen[root] = 0
            while (q.isNotEmpty()) {
                val (cur, d) = q.removeFirst()
                for (k in 0 until (edges?.length() ?: 0)) {
                    val e = edges!!.optJSONObject(k) ?: continue
                    if (e.optInt("f", -1) != cur) continue
                    val t = e.optInt("t", -1)
                    if (t < 0 || t >= n) continue
                    if (!seen.containsKey(t)) { seen[t] = d + 1; q.add(t to d + 1) }
                }
            }
            seen
        }
        val maxD = hit.values.maxOrNull() ?: 0

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "影响波及"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("波及 ${hit.size - 1} 个 · ${maxD} 层", fontSize = 9.sp,
                     color = if (hit.size > 1) ZxT.Warn else ZxT.Text3)
            }
            Spacer(Modifier.height(6.dp))
            for (i in 0 until n) {
                val nd = nodes.optJSONObject(i) ?: continue
                val d = hit[i]
                val isRoot = i == root
                // 越近越红：直接影响和间接影响不能看起来一样
                val col = when {
                    isRoot -> ZxT.Err
                    d == 1 -> ZxT.Warn
                    d != null -> PAL[1].copy(.7f)
                    else -> ZxT.Text3
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (d != null) ZxT.Surface2.copy(.6f) else Color.Transparent)
                    .clickable {
                        root = i
                        ctx.callTool("zx_select", mapOf(
                            "root" to nd.optString("n", ""),
                            "affected" to (hit.size - 1).toString(),
                            "depth" to maxD.toString(), "source" to "zx_blast"))
                    }.padding(6.dp, 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if (isRoot) "◉" else (if (d != null) "●" else "○"),
                         fontSize = 9.sp, color = col,
                         modifier = Modifier.width(14.dp))
                    Text(nd.optString("n", ""), fontSize = 10.sp,
                         color = if (d != null) ZxT.Text else ZxT.Text3,
                         modifier = Modifier.weight(1f), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    if (d != null && !isRoot)
                        Text("${d} 跳", fontSize = 8.sp, color = ZxT.Text3,
                             fontFamily = ZxT.Mono,
                             modifier = Modifier.clip(RoundedCornerShape(7.dp))
                                 .background(ZxT.Surface3).padding(6.dp, 1.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("改这个" to ZxT.Err, "直接影响" to ZxT.Warn,
                       "间接影响" to PAL[1].copy(.7f)).forEach { (lab, c) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(c))
                        Spacer(Modifier.width(3.dp))
                        Text(lab, fontSize = 8.sp, color = ZxT.Text3)
                    }
                }
            }
            Text("已有 chain 找**最弱一节**、depgraph 画**依赖结构**，" +
                 "这个方向相反：从「**改谁**」出发找「**谁受害**」，" +
                 "且区分直接影响（1 跳）和间接影响。" +
                 "只画依赖不给「改这个会怎样」，等于没画",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_batch 批量操作 ══════════ */
    @Composable
    fun batch(o: JSONObject, ctx: RenderContext) {
        val items = o.optJSONArray("items") ?: return
        val n = minOf(items.length(), 10)
        val acts = o.optJSONArray("acts")
        val picked = remember { mutableStateListOf<Boolean>().apply { repeat(n) { add(true) } } }
        var act by remember { mutableIntStateOf(0) }
        var run by remember { mutableStateOf(false) }
        // 逐项结果：批量操作最怕"一起跑完了不知道哪个失败"
        val res = remember { mutableStateListOf<String>().apply { repeat(n) { add("") } } }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "批量操作"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("已选 ${picked.count { it }}/$n", fontSize = 9.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(6.dp))
            for (i in 0 until n) {
                val it = items.optJSONObject(i) ?: continue
                val on = picked[i]
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (on) ZxT.Surface2 else Color.Transparent)
                    .clickable { picked[i] = !on }.padding(6.dp, 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(15.dp).clip(RoundedCornerShape(3.dp))
                        .background(if (on) PAL[0] else ZxT.Surface3))
                    Spacer(Modifier.width(7.dp))
                    Text(it.optString("n", ""), fontSize = 10.sp,
                         color = if (on) ZxT.Text else ZxT.Text3,
                         modifier = Modifier.weight(1f), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    if (res[i].isNotBlank())
                        Text(res[i], fontSize = 8.5.sp,
                             color = if (res[i].startsWith("✓")) ZxT.Ok else ZxT.Err,
                             fontFamily = ZxT.Mono)
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (k in 0 until minOf(acts?.length() ?: 0, 3)) {
                    val a = acts!!.optString(k, "")
                    val on = act == k
                    Text(a, fontSize = 9.sp,
                         color = if (on) Color.White else ZxT.Text3,
                         modifier = Modifier.clip(RoundedCornerShape(8.dp))
                             .background(if (on) PAL[0] else ZxT.Surface2)
                             .clickable { act = k }.padding(9.dp, 2.dp))
                }
            }
            Spacer(Modifier.height(5.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("全选", fontSize = 9.sp, color = ZxT.Text3,
                     modifier = Modifier.clip(RoundedCornerShape(8.dp))
                         .background(ZxT.Surface2).clickable {
                             for (i in 0 until n) picked[i] = true }.padding(9.dp, 2.dp))
                Text("反选", fontSize = 9.sp, color = ZxT.Text3,
                     modifier = Modifier.clip(RoundedCornerShape(8.dp))
                         .background(ZxT.Surface2).clickable {
                             for (i in 0 until n) picked[i] = !picked[i] }.padding(9.dp, 2.dp))
                Spacer(Modifier.weight(1f))
                Text(if (run) "已执行" else "执行", fontSize = 9.5.sp,
                     color = if (picked.count { it } == 0) ZxT.Text3 else Color.White,
                     modifier = Modifier.clip(RoundedCornerShape(9.dp))
                         .background(if (picked.count { it } == 0) ZxT.Surface3 else PAL[0])
                         .clickable {
                             if (picked.count { it } == 0) return@clickable
                             run = true
                             // 逐项给结果，不整体报"完成"
                             for (i in 0 until n) res[i] = if (picked[i]) "✓" else "—"
                             ctx.callTool("zx_select", mapOf(
                                 "action" to (acts?.optString(act, "") ?: ""),
                                 "targets" to (0 until n).filter { picked[it] }
                                     .joinToString(",") { items.optJSONObject(it)
                                         ?.optString("n", "") ?: "" },
                                 "count" to picked.count { it }.toString(),
                                 "source" to "zx_batch"))
                         }.padding(11.dp, 3.dp))
            }
            Text("已有 checklist 是**打勾清单**、runbook 是**步骤手册**，" +
                 "这个是**选中 N 项执行同一动作**——执行后逐项给结果。" +
                 "整体报「完成」用户不知道哪一项失败了",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_priority 优先级象限（可拖动归属） ══════════ */
    @Composable
    fun priority(o: JSONObject, ctx: RenderContext) {
        val items = o.optJSONArray("items") ?: return
        val n = minOf(items.length(), 8)
        val thr = o.optDouble("thr", 50.0)
        var sel by remember { mutableIntStateOf(-1) }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "优先级"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("$n 项 · 阈值 $thr", fontSize = 9.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(6.dp))
            // 四个象限，各放归属的项
            val quad = Array(4) { ArrayList<Int>() }
            for (i in 0 until n) {
                val it = items.optJSONObject(i) ?: continue
                val x = it.optDouble("x", 0.0); val y = it.optDouble("y", 0.0)
                val q = (if (y >= thr) 0 else 2) + (if (x >= thr) 0 else 1)
                quad[q].add(i)
            }
            val labs = listOf("高价值·高成本", "低价值·高成本",
                              "高价值·低成本", "低价值·低成本")
            val cols = listOf(ZxT.Warn, ZxT.Err, ZxT.Ok, ZxT.Text3)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                for (r in 0..1) {
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        for (c in 0..1) {
                            val q = r * 2 + c
                            Column(Modifier.weight(1f).height(66.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(ZxT.Surface2)
                                .border(1.dp, cols[q].copy(.35f),
                                    RoundedCornerShape(5.dp))
                                .padding(5.dp)) {
                                Text(labs[q], fontSize = 7.5.sp, color = cols[q],
                                     maxLines = 1, overflow = TextOverflow.Clip)
                                Spacer(Modifier.height(3.dp))
                                quad[q].forEach { i ->
                                    val it = items.optJSONObject(i) ?: return@forEach
                                    val on = sel == i
                                    Text(it.optString("n", ""), fontSize = 8.5.sp,
                                         color = if (on) ZxT.Text else ZxT.Text2,
                                         maxLines = 1, overflow = TextOverflow.Ellipsis,
                                         modifier = Modifier.fillMaxWidth()
                                             .clip(RoundedCornerShape(4.dp))
                                             .background(if (on) ZxT.Surface3
                                             else Color.Transparent)
                                             .clickable {
                                                 sel = i
                                                 ctx.callTool("zx_select", mapOf(
                                                     "item" to it.optString("n", ""),
                                                     "quadrant" to labs[q],
                                                     "source" to "zx_priority"))
                                             }.padding(4.dp, 1.dp))
                                }
                                if (quad[q].isEmpty())
                                    Text("—", fontSize = 8.sp, color = ZxT.Text3)
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (sel >= 0) {
                    val it = items.optJSONObject(sel)
                    // 拖动归属：直接改 x/y 把项挪到相邻象限
                    listOf("↑提价值" to (0 to 8.0), "↓降价值" to (0 to -8.0),
                           "→提成本" to (1 to 8.0), "←降成本" to (1 to -8.0))
                        .forEach { (lab, mv) ->
                            Text(lab, fontSize = 8.5.sp, color = ZxT.Text3,
                                 modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                     .background(ZxT.Surface2).clickable {
                                         ctx.callTool("zx_select", mapOf(
                                             "item" to (it?.optString("n", "") ?: ""),
                                             "move" to lab, "source" to "zx_priority"))
                                     }.padding(6.dp, 2.dp))
                        }
                }
            }
            Text("已有 matrix 是**点选矩阵**、board 是**自由画布**，" +
                 "这个是**象限归属**——按两个维度自动分到四格，且能挪。" +
                 "「高价值低成本」要做、「低价值高成本」要砍，象限的意义在此",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_probe 探针卡（此刻快照） ══════════ */
    @Composable
    fun probe(o: JSONObject, ctx: RenderContext) {
        val eps = o.optJSONArray("eps") ?: return
        val n = minOf(eps.length(), 8)
        var sel by remember { mutableIntStateOf(-1) }
        var run by remember { mutableStateOf(o.optBoolean("ran", true)) }
        val slow = o.optDouble("slow", 300.0)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "端点探测"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (run) "重新探测" else "探测", fontSize = 9.sp,
                         color = ZxT.Text3,
                         modifier = Modifier.clip(RoundedCornerShape(8.dp))
                             .background(ZxT.Surface2).clickable { run = true }
                             .padding(8.dp, 2.dp))
                    Text("$n 个", fontSize = 9.sp, color = ZxT.Text3)
                }
            }
            Spacer(Modifier.height(6.dp))
            if (!run)
                Text("还没探测", fontSize = 10.sp, color = ZxT.Text3,
                     modifier = Modifier.padding(vertical = 10.dp))
            else for (i in 0 until n) {
                val e = eps.optJSONObject(i) ?: continue
                val ms = e.optDouble("ms", 0.0)
                val ok = e.optInt("rc", 0) == 0
                val slowOne = ms > slow
                val on = sel == i
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (on) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        sel = i
                        ctx.callTool("zx_select", mapOf(
                            "endpoint" to e.optString("n", ""),
                            "ms" to ms.toString(), "rc" to e.optInt("rc", 0).toString(),
                            "source" to "zx_probe"))
                    }.padding(6.dp, 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape)
                        .background(if (!ok) ZxT.Err else (if (slowOne) ZxT.Warn else ZxT.Ok)))
                    Spacer(Modifier.width(6.dp))
                    Text(e.optString("n", ""), fontSize = 9.5.sp,
                         color = if (ok) ZxT.Text2 else ZxT.Err,
                         modifier = Modifier.weight(1f), maxLines = 1,
                         overflow = TextOverflow.Ellipsis, fontFamily = ZxT.Mono)
                    // 耗时条：只有状态点不够，得看出谁慢
                    val mx = (0 until n).maxOf { eps.optJSONObject(it)?.optDouble("ms", 0.0) ?: 0.0 }
                        .coerceAtLeast(1.0)
                    Box(Modifier.width(46.dp).height(4.dp)
                        .clip(RoundedCornerShape(2.dp)).background(ZxT.Surface3)) {
                        Box(Modifier.fillMaxWidth((ms / mx).toFloat().coerceIn(0f, 1f))
                            .fillMaxHeight().clip(RoundedCornerShape(2.dp))
                            .background(if (slowOne) ZxT.Warn else PAL[0]))
                    }
                    Spacer(Modifier.width(5.dp))
                    Text("%.0fms".format(ms), fontSize = 8.5.sp,
                         color = if (slowOne) ZxT.Warn else ZxT.Text3,
                         fontFamily = ZxT.Mono)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("阈值 ${"%.0f".format(slow)}ms 以上算慢", fontSize = 8.5.sp,
                 color = ZxT.Text3)
            Text("已有 uptime 是**时间序列**（每格一个时间片）、pipe 是**吞吐**，" +
                 "这个是**此刻快照**——对 N 个端点各探一次，看谁活着、谁慢。" +
                 "只有状态点不够，得有耗时条才看得出谁慢",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_inspect 字段检查器（来源追溯） ══════════ */
    @Composable
    fun inspect(o: JSONObject, ctx: RenderContext) {
        val fields = o.optJSONArray("fields") ?: return
        val n = minOf(fields.length(), 10)
        var open by remember { mutableIntStateOf(-1) }
        var onlyUn by remember { mutableStateOf(false) }
        val srcs = setOf("user", "ai", "ref", "default", "derived")
        val shown = (0 until n).filter {
            !onlyUn || fields.optJSONObject(it)?.optString("src", "") !in srcs ||
            fields.optJSONObject(it)?.optBoolean("ok", true) == false }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "字段检查"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("${shown.size} 个字段", fontSize = 9.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(6.dp))
            if (shown.isEmpty())
                Text("没有异常字段", fontSize = 10.sp, color = ZxT.Text3,
                     modifier = Modifier.padding(vertical = 10.dp))
            shown.forEach { i ->
                val f = fields.optJSONObject(i) ?: return@forEach
                val on = open == i
                val src = f.optString("src", "")
                val ok = f.optBoolean("ok", true)
                val col = when (src) {
                    "user" -> PAL[0]; "ai" -> PAL[1]; "ref" -> PAL[4]
                    "derived" -> PAL[5]; "default" -> ZxT.Text3
                    else -> ZxT.Err
                }
                Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (on) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        open = if (on) -1 else i
                        ctx.callTool("zx_select", mapOf(
                            "field" to f.optString("k", ""), "src" to src,
                            "source" to "zx_inspect"))
                    }.padding(6.dp, 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(f.optString("k", ""), fontSize = 9.5.sp,
                             color = if (ok) ZxT.Text2 else ZxT.Err,
                             fontFamily = ZxT.Mono, modifier = Modifier.weight(1f),
                             maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(src, fontSize = 8.sp, color = col,
                             modifier = Modifier.clip(RoundedCornerShape(7.dp))
                                 .background(col.copy(.16f)).padding(6.dp, 1.dp))
                        if (!ok)
                            Text("!", fontSize = 9.sp, color = ZxT.Err,
                                 modifier = Modifier.padding(start = 4.dp))
                    }
                    Row {
                        Text(f.optString("v", ""), fontSize = 9.sp, color = ZxT.Text3,
                             modifier = Modifier.weight(1f), maxLines = 1,
                             overflow = TextOverflow.Ellipsis)
                        if (on) {
                            // 来源追溯：这个值哪来的、什么时候、可不可信
                            f.optString("at").takeIf { it.isNotBlank() }?.let {
                                Text(it, fontSize = 8.sp, color = ZxT.Text3,
                                     fontFamily = ZxT.Mono)
                            }
                        }
                    }
                    if (on) {
                        f.optString("why").takeIf { it.isNotBlank() }?.let {
                            Spacer(Modifier.height(2.dp))
                            Text(it, fontSize = 9.sp, color = ZxT.Text2, lineHeight = 13.sp)
                        }
                        f.optString("from").takeIf { it.isNotBlank() }?.let {
                            Text("来自：$it", fontSize = 8.5.sp, color = ZxT.Text3)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)) {
                Text(if (onlyUn) "看全部" else "只看异常", fontSize = 9.sp,
                     color = if (onlyUn) ZxT.PriLite else ZxT.Text3,
                     modifier = Modifier.clip(RoundedCornerShape(8.dp))
                         .background(if (onlyUn) ZxT.Surface2 else Color.Transparent)
                         .clickable { onlyUn = !onlyUn }.padding(8.dp, 2.dp))
            }
            Text("已有 keyvalue 只**展示键值**、datalist 是**列表**，" +
                 "都不回答「**这个值哪来的**」——这个标出每个字段的来源" +
                 "（用户填的 / AI 推断的 / 引用来的 / 推导出的 / 默认值）。" +
                 "**来源不明的值不该被当真**",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_remap 值重映射 ══════════ */
    @Composable
    fun remap(o: JSONObject, ctx: RenderContext) {
        val rules = o.optJSONArray("rules") ?: return
        val n = minOf(rules.length(), 10)
        var sel by remember { mutableIntStateOf(-1) }
        // 环检测：a→b→a 永远替换不完，必须在界面上就说出来
        val cyc = remember(n) {
            val map = HashMap<String, String>()
            for (i in 0 until n) map[rules.optJSONObject(i)?.optString("f", "")
                ?: ""] = rules.optJSONObject(i)?.optString("t", "") ?: ""
            val bad = LinkedHashSet<String>()
            map.keys.forEach { k ->
                val seen = LinkedHashSet<String>(); var cur = k
                while (cur.isNotBlank() && seen.add(cur)) cur = map[cur] ?: ""
                if (map[cur] != null && cur.isNotBlank()) bad.add(k)
            }
            bad
        }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "值重映射"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (cyc.isEmpty()) "$n 条规则" else "${cyc.size} 条成环",
                     fontSize = 9.sp, color = if (cyc.isEmpty()) ZxT.Text3 else ZxT.Err)
            }
            Spacer(Modifier.height(6.dp))
            if (cyc.isNotEmpty())
                Text("成环：${cyc.joinToString("、")} —— 会永远替换不完",
                     fontSize = 9.5.sp, color = ZxT.Err, lineHeight = 13.sp,
                     modifier = Modifier.fillMaxWidth()
                         .clip(RoundedCornerShape(5.dp))
                         .background(ZxT.Err.copy(.12f)).padding(7.dp, 5.dp))
            Spacer(Modifier.height(4.dp))
            for (i in 0 until n) {
                val r = rules.optJSONObject(i) ?: continue
                val on = sel == i
                val f = r.optString("f", ""); val t = r.optString("t", "")
                val bad = cyc.contains(f)
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (on) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        sel = i
                        ctx.callTool("zx_select", mapOf(
                            "from" to f, "to" to t, "cyclic" to bad.toString(),
                            "source" to "zx_remap"))
                    }.padding(6.dp, 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(f, fontSize = 9.5.sp,
                         color = if (bad) ZxT.Err else ZxT.Text2,
                         fontFamily = ZxT.Mono, modifier = Modifier.weight(1f),
                         maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("→", fontSize = 9.sp, color = if (bad) ZxT.Err else ZxT.Text3)
                    Spacer(Modifier.width(5.dp))
                    Text(t, fontSize = 9.5.sp,
                         color = if (bad) ZxT.Err else ZxT.Text,
                         fontFamily = ZxT.Mono, modifier = Modifier.weight(1f),
                         maxLines = 1, overflow = TextOverflow.Ellipsis,
                         textAlign = TextAlign.End)
                    r.optString("n").takeIf { it.isNotBlank() }?.let {
                        Text(it, fontSize = 8.sp, color = ZxT.Text3,
                             modifier = Modifier.padding(start = 5.dp))
                    }
                }
            }
            Spacer(Modifier.height(5.dp))
            Text("点规则看它会不会引发连锁替换；成环的标红且不参与预览",
                 fontSize = 9.sp, color = ZxT.Text3)
            Text("已有 mapping 是**字段映射**（左栏对应右栏），" +
                 "这个是**值替换规则**（a 换成 b）——改的是数据不是结构。" +
                 "**成环必须拦**：a→b→a 会永远替换不完",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_rollup 汇总树 ══════════ */
    @Composable
    fun rollup(o: JSONObject, ctx: RenderContext) {
        val rows = o.optJSONArray("rows") ?: return
        val n = minOf(rows.length(), 14)
        var collapsed by remember { mutableStateOf(setOf<Int>()) }
        var sel by remember { mutableIntStateOf(-1) }
        val vals = (0 until n).map { rows.optJSONObject(it)?.optDouble("v", 0.0) ?: 0.0 }
        // 只算顶层（lv=1）之和作为总量
        val total = (0 until n).filter { rows.optJSONObject(it)?.optInt("lv", 1) == 1 }
            .sumOf { vals[it] }.coerceAtLeast(1e-9)
        // 父值 ≠ 子和就是汇总不可信，界面上要标出来
        val mismatch = remember(n) {
            val bad = LinkedHashSet<Int>()
            for (i in 0 until n) {
                val lv = rows.optJSONObject(i)?.optInt("lv", 1) ?: 1
                if (lv != 1) continue
                var s = 0.0
                for (j in i + 1 until n) {
                    val lj = rows.optJSONObject(j)?.optInt("lv", 1) ?: 1
                    if (lj <= lv) break
                    if (lj == lv + 1) s += vals[j]
                }
                if (abs(s - vals[i]) > 0.01) bad.add(i)
            }
            bad
        }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "汇总"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("合计 %.1f".format(total), fontSize = 9.sp, color = ZxT.Text3,
                     fontFamily = ZxT.Mono)
            }
            if (mismatch.isNotEmpty())
                Text("${mismatch.size} 个父节点的值不等于子项之和——汇总不可信",
                     fontSize = 9.5.sp, color = ZxT.Err, lineHeight = 13.sp,
                     modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                         .clip(RoundedCornerShape(5.dp))
                         .background(ZxT.Err.copy(.12f)).padding(7.dp, 4.dp))
            Spacer(Modifier.height(6.dp))
            var hideTo = -1
            for (i in 0 until n) {
                val r = rows.optJSONObject(i) ?: continue
                val lv = r.optInt("lv", 1).coerceIn(1, 3)
                if (hideTo >= 0) { if (lv > hideTo) continue else hideTo = -1 }
                val isParent = i + 1 < n &&
                    (rows.optJSONObject(i + 1)?.optInt("lv", 1) ?: 1) > lv
                val folded = i in collapsed
                if (isParent && folded) hideTo = lv
                val on = sel == i
                val bad = i in mismatch
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .padding(start = ((lv - 1) * 11).dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (on) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        sel = i
                        ctx.callTool("zx_select", mapOf(
                            "row" to r.optString("n", ""), "v" to vals[i].toString(),
                            "level" to lv.toString(), "source" to "zx_rollup"))
                    }.padding(6.dp, 3.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if (!isParent) "·" else (if (folded) "▸" else "▾"),
                         fontSize = 9.sp, color = ZxT.Text3,
                         modifier = Modifier.width(13.dp)
                             .let { if (isParent) it.clickable {
                                 collapsed = if (folded) collapsed - i else collapsed + i
                             } else it })
                    Text(r.optString("n", ""), fontSize = if (11 - lv >= 9) (11 - lv).sp else 9.sp,
                         color = if (on) ZxT.Text else
                             (if (lv == 1) ZxT.Text2 else ZxT.Text3),
                         modifier = Modifier.weight(1f), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    // 占比条：缩进表 + 值还不够，得看出占多少
                    Box(Modifier.width(38.dp).height(4.dp)
                        .clip(RoundedCornerShape(2.dp)).background(ZxT.Surface3)) {
                        Box(Modifier.fillMaxWidth((vals[i] / total).toFloat().coerceIn(0f, 1f))
                            .fillMaxHeight().clip(RoundedCornerShape(2.dp))
                            .background(if (lv == 1) PAL[0] else PAL[0].copy(.5f)))
                    }
                    Spacer(Modifier.width(5.dp))
                    Text("%.1f".format(vals[i]), fontSize = 8.5.sp,
                         color = if (bad) ZxT.Err else
                             (if (lv == 1) ZxT.Text2 else ZxT.Text3),
                         fontFamily = ZxT.Mono)
                }
            }
            Text("已有 tree 是**文件树**、treeview 是**带勾选的层级**、" +
                 "icicle/sunburst 是**矩形树图**，" +
                 "这个是**带聚合值的缩进表**——父节点值等于子项之和。" +
                 "**父值不等于子和时汇总就不可信**，必须标出来",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }
}
