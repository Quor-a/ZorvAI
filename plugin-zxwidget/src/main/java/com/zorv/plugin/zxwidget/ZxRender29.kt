package com.zorv.plugin.zxwidget

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 第二十九批组件（12 个）—— 知识与判断的留存。
 *
 * 前二十八批一直在帮人"**这一刻做出决定**"：
 *
 *   看什么 → 填什么 → 改什么 → 凭什么 → 是不是因果 →
 *   在多大范围内成立 → 接下来该做什么 → 做完了怎么样
 *
 * 到第二十八批，rollout / incident / rca 已经能把"行动之后"也管起来。
 *
 * 但决定做完就**散了**。四件事从来没有承载物：
 *
 *   "当时为什么这么选？"           → 只剩聊天记录里的一句话
 *   "这个系统到底能撑多少量？"       → 每次都重新拍脑袋估
 *   "上游挂了会影响哪些？"          → 只看单点健康度
 *   "上次那次事故留下了什么？"       → 只有一条时间线，没有行动项
 *   "这条经验还管用吗？"            → 三个月前的结论，没人知道过没过期
 *   "技术债该先还哪个？"            → 没有利息就无法排序
 *
 * 十二个组件分成四组：
 *
 *   **容量**（系统能撑多少 —— 不给直觉，给算出来的数）
 *     zx_capacity    排队论：λ / μ / ρ → 等待时间与队列长度
 *     zx_queue       可视化 Little's Law（L = λW）
 *     zx_loadtest    吞吐-延迟曲线，**标出拐点**
 *
 *   **风险**（这次动它有多危险）
 *     zx_topology    服务依赖拓扑 + 健康传播
 *     zx_changerisk  变更风险评分，**每条都要有依据**
 *
 *   **处置**（出事了按什么顺序做）
 *     zx_playbook    处置剧本，分阶段且**必须带回滚**
 *     zx_escalation  升级路径，**超时自动往上升**
 *
 *   **留存**（下次还能用上）
 *     zx_postmortem  复盘，**必须有行动项**
 *     zx_knowledge   知识沉淀，**必须标时效与来源**
 *     zx_decisionlog 决策日志，**必须写依据**
 *     zx_debt        技术债，**必须标利息**
 *     zx_checkpoint  长任务的保存点
 *
 * 贯穿这批的一条：**记录了，还得能下次用上**。
 *
 *   容量卡不给「能撑多少」，只给「现在很闲」 → 下次大促还是拍脑袋
 *   拓扑卡只看单点健康，不给传播            → 上游挂了才发现影响面
 *   剧本没有回滚步骤                        → 真的要回滚时临时想
 *   复盘没有行动项                          → 等于没复盘，下次照样挂
 *   知识不标时效                            → 过期知识比没有更糟，它是错的且看起来可信
 *   升级路径没有终点                        → 一级级传上去最后没人接
 *   技术债不标利息                          → 清单永远排不上优先级
 *
 *   **只记录的留存等于没留存。**
 *
 * 校验因此换了第七档。前六档拦的是
 *   "画不出来" → "统计前提不成立" → "区间前提不成立" →
 *   "因果前提无从判断" → "看起来确定了其实没那么确定" →
 *   "状态摆出来了但推不出该做什么"，
 * 这批拦的是
 *   **"记是记下来了，但下次用不上"**：
 *
 *   capacity   μ ≤ λ（ρ≥1，系统不稳定，队列会无限长）
 *   queue      λ 或 μ 非正（Little's Law 无从算起）
 *   loadtest   吞吐未升序（画不出拐点）
 *   topology   有环（传播会无限循环）/ 边指向不存在的节点
 *   changerisk 高风险却没写依据（评分变成拍脑袋）
 *   playbook   缺回滚步骤
 *   escalation 没有终点 / 超时未升序
 *   postmortem 缺行动项
 *   knowledge  缺时效或来源（无法判断是否过期）
 *   decisionlog 缺依据
 *   debt       利息为 0（那不叫债，那叫待办）
 *   checkpoint 恢复点未升序
 */
object ZxRender29 {

    private val PAL = listOf(
        Color(0xFF5046E4), Color(0xFF3FBF7F), Color(0xFFE8A33D),
        Color(0xFFE5544B), Color(0xFF4A9BE8), Color(0xFF8E86FF)
    )
    private fun sc(s: String) = when (s) {
        "ok" -> ZxT.Ok; "warn" -> ZxT.Warn; "err" -> ZxT.Err
        "crit" -> ZxT.Err; "high" -> ZxT.Err; "med" -> ZxT.Warn
        "low" -> ZxT.Ok; else -> ZxT.Text3
    }

    /* ══════════ zx_capacity 容量规划（排队论 M/M/1） ══════════ */
    @Composable
    fun capacity(o: JSONObject, ctx: RenderContext) {
        val lam = o.optDouble("lambda", 0.0)      // 到达率
        val mu = o.optDouble("mu", 0.0)           // 服务率
        val n = o.optInt("servers", 1)            // 并行服务台数
        val rho = if (mu > 0) lam / (mu * n) else Double.NaN
        val stable = rho < 1.0
        // M/M/1 近似：Lq = ρ²/(1-ρ)，Wq = Lq/λ，W = Wq + 1/μ
        val lq = if (stable) rho.pow(2) / (1 - rho) else Double.NaN
        val wq = if (stable && lam > 0) lq / lam else Double.NaN
        val w = if (stable && mu > 0) wq + 1.0 / mu else Double.NaN
        var sel by remember { mutableIntStateOf(-1) }
        val curve = (1..20).map { k -> (k / 20.0 * 0.97) to
            ((k / 20.0 * 0.97).pow(2) / (1 - k / 20.0 * 0.97)) }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "容量规划"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (!stable) "过载 ρ≥1" else when {
                    rho > 0.85 -> "接近饱和"; rho > 0.6 -> "偏紧"; else -> "有余量" },
                    fontSize = 9.sp, color = if (!stable) ZxT.Err
                    else if (rho > 0.85) ZxT.Warn else ZxT.Ok)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth()) {
                listOf("到达 λ" to "%.0f".format(lam),
                       "服务 μ" to "%.0f".format(mu),
                       "台数" to n.toString(),
                       "利用率 ρ" to if (rho.isNaN()) "—" else "%.3f".format(rho)
                ).forEach { (k, v) ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(v, fontSize = 10.sp, color = ZxT.Text, fontFamily = ZxT.Mono)
                        Text(k, fontSize = 7.5.sp, color = ZxT.Text3)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(64.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val w0 = size.width; val h0 = size.height
                    val mx = curve.maxOf { it.second }
                    // 队列长度随 ρ 的增长曲线：越接近 1 越陡
                    drawPath(Path().apply {
                        curve.forEachIndexed { k, (r, l) ->
                            val x = (r * w0).toFloat(); val y = (h0 - (l / mx * (h0 - 6f))).toFloat()
                            if (k == 0) moveTo(x, y) else lineTo(x, y)
                        }
                    }, PAL[0], style = Stroke(1.8f))
                    // 当前位置
                    if (stable) {
                        val x = rho * size.width
                        drawLine(ZxT.Err, Offset(x.toFloat(), 0f), Offset(x.toFloat(), size.height), 1.3f)
                        drawCircle(Color.White, 3.5f,
                                   Offset(x.toFloat(), (size.height - (lq / mx * (size.height - 6f))).toFloat()))
                    }
                }
            }
            Row(Modifier.fillMaxWidth()) {
                Text("队列长度随 ρ 增长（红线=当前）", fontSize = 7.5.sp, color = ZxT.Text3,
                     modifier = Modifier.weight(1f))
                Text("越陡越危险", fontSize = 7.5.sp, color = ZxT.Text3)
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                listOf("等待 Wq" to (if (wq.isNaN()) "—" else "%.4f".format(wq)),
                       "队长 Lq" to (if (lq.isNaN()) "—" else "%.2f".format(lq)),
                       "总时延 W" to (if (w.isNaN()) "—" else "%.4f".format(w))
                ).forEach { (k, v) ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(v, fontSize = 9.5.sp, color = ZxT.Text, fontFamily = ZxT.Mono)
                        Text(k, fontSize = 7.5.sp, color = ZxT.Text3)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (!stable) ZxT.Err.copy(.10f)
                            else if (rho > 0.85) ZxT.Warn.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (!stable) "✗" else if (rho > 0.85) "!" else "✓", fontSize = 11.sp,
                     color = if (!stable) ZxT.Err else if (rho > 0.85) ZxT.Warn else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (!stable) "μ ≤ λ，**系统会无限堆积**——这不是慢，是永远不会追上"
                         else if (rho > 0.85) "ρ=${"%.2f".format(rho)}，排队对增长极其敏感：" +
                            "再加 10% 流量，等待时间会涨${"%.0f".format((1 / (1 - rho * 1.1) - 1 / (1 - rho)) * 100)}%"
                         else "ρ=${"%.2f".format(rho)}，还有余量",
                        fontSize = 9.sp, color = if (!stable) ZxT.Err
                        else if (rho > 0.85) ZxT.Warn else ZxT.Ok)
                    Text("容量规划的价值不是「现在闲不闲」，是**流量涨到多少会崩**",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 meter 给<b>一个量</b>、budget 给<b>分档条</b>、slo 给<b>可用额度</b>，" +
                 "这个算的是「<b>这个系统还能吃多少流量</b>」。" +
                 "关键是<b>要算不要估</b>——「现在很闲」是个直觉，" +
                 "而直觉在 ρ 接近 1 时完全失效（曲线在那是陡的）。" +
                 "<b>μ ≤ λ 会被拦</b>：ρ≥1 时队列无限长，" +
                 "画出来的等待时间是假的。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_queue 排队可视化（Little's Law） ══════════ */
    @Composable
    fun queue(o: JSONObject, ctx: RenderContext) {
        val lam = o.optDouble("lambda", 0.0)
        val mu = o.optDouble("mu", 0.0)
        val itemsA = o.optJSONArray("items") ?: return
        val n = minOf(itemsA.length(), 12)
        val items = (0 until n).map { i ->
            val it = itemsA.optJSONObject(i) ?: JSONObject()
            Triple(it.optString("n", "任务${i + 1}"),
                   it.optDouble("at", 0.0),
                   it.optDouble("svc", 0.0))
        }
        var sel by remember { mutableIntStateOf(-1) }
        // Little's Law: L = λW
        val W = if (mu > lam && lam > 0) 1.0 / (mu - lam) else Double.NaN
        val L = if (W.isNaN()) Double.NaN else lam * W
        val rho = if (mu > 0) lam / mu else Double.NaN
        val tMax = items.maxOf { it.second + it.third }.coerceAtLeast(1.0)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "排队"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (L.isNaN()) "不稳定" else "L=%.2f".format(L), fontSize = 9.sp,
                     color = if (L.isNaN()) ZxT.Err else if (L > n * 0.6) ZxT.Warn else ZxT.Ok)
            }
            Spacer(Modifier.height(6.dp))
            items.forEachIndexed { k, (nm, at, svc) ->
                val col = PAL[k % PAL.size]
                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (sel == k) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_select", mapOf(
                            "item" to nm, "arrive" to "%.2f".format(at),
                            "service" to "%.2f".format(svc), "source" to "zx_queue"))
                    }.padding(4.dp, 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(nm, fontSize = 8.5.sp, color = ZxT.Text2,
                         modifier = Modifier.width(46.dp), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    Box(Modifier.weight(1f).height(9.dp)
                        .clip(RoundedCornerShape(4.dp)).background(ZxT.Surface2)) {
                        Box(Modifier.fillMaxWidth((svc / tMax).toFloat().coerceIn(0.02f, 1f))
                            .fillMaxHeight()
                            .padding(start = ((at / tMax) * 300).dp.coerceAtMost(280.dp))
                            .background(col))
                    }
                    Text("%.1f".format(at), fontSize = 7.5.sp, color = ZxT.Text3,
                         modifier = Modifier.width(24.dp), textAlign = TextAlign.End,
                         fontFamily = ZxT.Mono)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                listOf("λ" to "%.2f".format(lam), "μ" to "%.2f".format(mu),
                       "ρ" to (if (rho.isNaN()) "—" else "%.2f".format(rho)),
                       "W" to (if (W.isNaN()) "∞" else "%.2f".format(W)),
                       "L" to (if (L.isNaN()) "∞" else "%.2f".format(L))
                ).forEach { (k, v) ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(v, fontSize = 9.5.sp, color = ZxT.Text, fontFamily = ZxT.Mono)
                        Text(k, fontSize = 7.5.sp, color = ZxT.Text3)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (L.isNaN()) ZxT.Err.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (L.isNaN()) "✗" else "✓", fontSize = 11.sp,
                     color = if (L.isNaN()) ZxT.Err else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (L.isNaN()) "λ ≥ μ，**队列会一直变长**——处理速度追不上到达速度"
                         else "Little's Law：L = λW = ${"%.2f".format(lam)} × ${"%.2f".format(W)} = ${"%.2f".format(L)}",
                        fontSize = 9.sp, color = if (L.isNaN()) ZxT.Err else ZxT.Ok)
                    Text("想减队列只有两条路：**少来（降 λ）或快点处理（升 μ）**——" +
                        "加机器是后者，限流是前者",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 gantt 画<b>计划排期</b>、sched 排<b>值班表</b>，" +
                 "这个画的是<b>到达与处理的真实时序</b>。" +
                 "关键是<b>Little's Law 要算出来</b>——" +
                 "只画甘特图，看不出「队列为什么会越来越长」。" +
                 "<b>λ 或 μ 非正会被拦</b>：没有到达率和服务率，" +
                 "排队论里的每个量都无从算起。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_loadtest 负载测试（找拐点） ══════════ */
    @Composable
    fun loadtest(o: JSONObject, ctx: RenderContext) {
        val ptsA = o.optJSONArray("pts") ?: return
        val n = minOf(ptsA.length(), 24)
        val pts = (0 until n).map {
            val p = ptsA.optJSONObject(it) ?: JSONObject()
            p.optDouble("q", it.toDouble()) to p.optDouble("lat", 0.0)
        }
        var sel by remember { mutableIntStateOf(-1) }
        val sla = o.optDouble("sla", Double.NaN)
        // 拐点：延迟开始非线性上升的位置（二阶差分最大）
        var knee = -1; var maxAcc = 0.0
        for (k in 1 until n - 1) {
            val acc = (pts[k + 1].second - pts[k].second) - (pts[k].second - pts[k - 1].second)
            if (acc > maxAcc) { maxAcc = acc; knee = k }
        }
        val kneeQ = if (knee >= 0) pts[knee].first else Double.NaN
        val maxLat = pts.maxOf { it.second }.coerceAtLeast(1.0)
        val maxQ = pts.maxOf { it.first }.coerceAtLeast(1.0)
        val W = 330f; val H = 78f; val pad = 8f
        val px = { k: Int -> pad + (k.toDouble() / (n - 1).coerceAtLeast(1) * (W - 2 * pad)).toFloat() }
        val py = { v: Double -> H - pad - (v / maxLat * (H - 2 * pad)).toFloat() }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "负载测试"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (knee >= 0) "拐点 ${"%.0f".format(kneeQ)} QPS" else "未见拐点",
                    fontSize = 9.sp, color = if (knee >= 0) ZxT.Warn else ZxT.Ok)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(78.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    if (!sla.isNaN()) {
                        val y = H - pad - (sla / maxLat * (H - 2 * pad)).toFloat()
                        drawLine(ZxT.Err, Offset(pad, y), Offset(W - pad, y), 1.2f)
                    }
                    drawPath(Path().apply {
                        pts.forEachIndexed { k, (_, lat) ->
                            val x = px(k); val y = py(lat)
                            if (k == 0) moveTo(x, y) else lineTo(x, y)
                        }
                    }, PAL[0], style = Stroke(1.8f))
                    if (knee >= 0) {
                        val x = px(knee); val y = py(pts[knee].second)
                        drawLine(ZxT.Warn, Offset(x, pad), Offset(x, H - pad), 1.2f)
                        drawCircle(Color.White, 4f, Offset(x, y))
                    }
                    if (sel >= 0)
                        drawCircle(Color.White, 3.2f, Offset(px(sel), py(pts[sel].second)))
                }
                Row(Modifier.fillMaxWidth().height(78.dp)) {
                    repeat(n) { k ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = k
                            ctx.callTool("zx_select", mapOf(
                                "qps" to "%.0f".format(pts[k].first),
                                "latency" to "%.1f".format(pts[k].second),
                                "at_knee" to (if (k == knee) "yes" else "no"),
                                "source" to "zx_loadtest"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth()) {
                Text(if (!sla.isNaN()) "红线 = SLA ${"%.0f".format(sla)}ms" else "未设 SLA",
                    fontSize = 7.5.sp, color = ZxT.Text3, modifier = Modifier.weight(1f))
                Text("黄线 = 拐点", fontSize = 7.5.sp, color = ZxT.Text3)
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                listOf("拐点 QPS" to (if (knee >= 0) "%.0f".format(kneeQ) else "—"),
                       "该点延迟" to (if (knee >= 0) "%.0f".format(pts[knee].second) else "—"),
                       "峰值延迟" to "%.0f".format(maxLat)
                ).forEach { (k, v) ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(v, fontSize = 9.5.sp, color = ZxT.Text, fontFamily = ZxT.Mono)
                        Text(k, fontSize = 7.5.sp, color = ZxT.Text3)
                    }
                }
            }
            if (sel >= 0)
                Row(Modifier.fillMaxWidth().padding(top = 3.dp)
                    .clip(RoundedCornerShape(5.dp)).background(ZxT.Surface2)
                    .padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${"%.0f".format(pts[sel].first)} QPS", fontSize = 9.sp,
                         color = ZxT.Text2, modifier = Modifier.weight(1f))
                    Text("延迟 ${"%.0f".format(pts[sel].second)}ms", fontSize = 8.5.sp, color = PAL[0])
                    if (!sla.isNaN())
                        Text(if (pts[sel].second > sla) "超 SLA" else "达标",
                            fontSize = 8.5.sp,
                            color = if (pts[sel].second > sla) ZxT.Err else ZxT.Ok,
                            modifier = Modifier.padding(start = 6.dp))
                }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (knee >= 0) ZxT.Warn.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (knee >= 0) "!" else "✓", fontSize = 11.sp,
                     color = if (knee >= 0) ZxT.Warn else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (knee >= 0) "拐点在 ${"%.0f".format(kneeQ)} QPS——" +
                            "**超过这个值延迟会陡增**，容量上限要按拐点定，不是按峰值定"
                         else "测试范围内没出现拐点——可能还没压到极限，加量再测",
                        fontSize = 9.sp, color = if (knee >= 0) ZxT.Warn else ZxT.Ok)
                    Text("「能撑 1000 QPS」这个说法很危险：**撑得住和撑得好的分界线是拐点**",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 line 画<b>一条曲线</b>、backtest 看<b>策略收益</b>，" +
                 "这个找的是<b>吞吐-延迟的拐点</b>。" +
                 "关键是<b>要标出拐点</b>：只看「峰值能到 1200 QPS」，" +
                 "不知道 800 之后延迟已经开始指数上升。" +
                 "<b>吞吐未升序会被拦</b>——乱序画不出拐点。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_topology 服务拓扑与健康传播 ══════════ */
    @Composable
    fun topology(o: JSONObject, ctx: RenderContext) {
        val nodesA = o.optJSONArray("nodes") ?: return
        val n = minOf(nodesA.length(), 10)
        val nodes = (0 until n).map {
            val nd = nodesA.optJSONObject(it) ?: JSONObject()
            Triple(nd.optString("n", "服务${it + 1}"), nd.optString("s", "ok"),
                   nd.optString("d", ""))
        }
        val edgesA = o.optJSONArray("edges") ?: return
        val edges = (0 until minOf(edgesA.length(), 16)).map {
            val e = edgesA.optJSONObject(it) ?: JSONObject()
            e.optInt("f", 0) to e.optInt("t", 0)
        }
        var sel by remember { mutableIntStateOf(-1) }
        // 传播：从异常节点 BFS 计算受影响集合
        val down = nodes.mapIndexed { k, x -> k to x.second }
            .filter { it.second == "err" || it.second == "crit" }.map { it.first }
        val affected = LinkedHashSet<Int>()
        val queue = ArrayList(down)
        affected.addAll(down)
        var guard = 0
        while (queue.isNotEmpty() && guard++ < 200) {
            val cur = queue.removeAt(0)
            edges.filter { it.first == cur }.forEach { (_, t) ->
                if (t !in affected) { affected.add(t); queue.add(t) }
            }
        }
        val impacted = affected.filter { it !in down }.sorted()
        val W = 330f; val H = 96f
        val pos = nodes.mapIndexed { k, _ ->
            val a = (k.toDouble() / n * 2 * Math.PI) - Math.PI / 2
            (W / 2 + (W / 2 - 26) * kotlin.math.cos(a)) to
                (H / 2 + (H / 2 - 14) * kotlin.math.sin(a))
        }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "服务拓扑"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (down.isEmpty()) "全部正常" else "${down.size} 个异常 / 波及 ${impacted.size} 个",
                    fontSize = 9.sp, color = if (down.isEmpty()) ZxT.Ok else ZxT.Err)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(96.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    edges.forEach { (f, t) ->
                        if (f >= n || t >= n) return@forEach
                        drawLine(if (f in affected) ZxT.Err.copy(.7f) else ZxT.Text3.copy(.35f),
                                 Offset(pos[f].first.toFloat(), pos[f].second.toFloat()),
                                 Offset(pos[t].first.toFloat(), pos[t].second.toFloat()), 1.2f)
                    }
                    nodes.forEachIndexed { k, x ->
                        val c = if (k in down) ZxT.Err
                                else if (k in impacted) ZxT.Warn else ZxT.Ok
                        drawCircle(c, if (sel == k) 9f else 7f,
                                   Offset(pos[k].first.toFloat(), pos[k].second.toFloat()))
                    }
                }
                Row(Modifier.fillMaxWidth().height(96.dp)) {
                    repeat(n) { k ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = k
                            ctx.callTool("zx_select", mapOf(
                                "node" to nodes[k].first, "state" to nodes[k].second,
                                "directly_down" to (if (k in down) "yes" else "no"),
                                "impacted" to (if (k in impacted) "yes" else "no"),
                                "source" to "zx_topology"))
                        })
                    }
                }
            }
            nodes.forEachIndexed { k, (nm, s, d) ->
                if (k !in affected && sel != k) return@forEachIndexed
                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(RoundedCornerShape(1.dp))
                        .background(if (k in down) ZxT.Err else ZxT.Warn))
                    Text(nm, fontSize = 9.sp, color = ZxT.Text2,
                         modifier = Modifier.weight(1f).padding(start = 5.dp), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    Text(if (k in down) "异常（根）" else "被波及", fontSize = 8.sp,
                         color = if (k in down) ZxT.Err else ZxT.Warn,
                         modifier = Modifier.width(52.dp), textAlign = TextAlign.End)
                    if (d.isNotBlank())
                        Text(d, fontSize = 7.5.sp, color = ZxT.Text3,
                             modifier = Modifier.width(60.dp), maxLines = 1,
                             overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (down.isEmpty()) ZxT.Ok.copy(.10f) else ZxT.Err.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (down.isEmpty()) "✓" else "✗", fontSize = 11.sp,
                     color = if (down.isEmpty()) ZxT.Ok else ZxT.Err)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (down.isEmpty()) "全部正常"
                         else if (impacted.isEmpty()) "${down.size} 个服务异常，暂时没有波及下游"
                         else "${down.size} 个异常 → 波及 ${impacted.size} 个：" +
                            "${impacted.take(3).map { nodes[it].first }.joinToString("、")}" +
                            "${if (impacted.size > 3) " 等" else ""}",
                        fontSize = 9.sp, color = if (down.isEmpty()) ZxT.Ok else ZxT.Err)
                    Text("只看单点健康度不够：**影响面才是事故的严重性**——" +
                        "一个只影响自己的服务和一个带崩八个的服务不是一回事",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 depgraph 画<b>依赖关系</b>、incident 记<b>事故时间</b>，" +
                 "这个做的是<b>健康状态沿依赖边传播</b>。" +
                 "关键是<b>要算出影响面</b>：只看「订单服务异常」，" +
                 "不知道它会带崩支付、库存、通知。<b>有环会被拦</b>——" +
                 "传播会无限循环；<b>边指向不存在的节点会被拦</b>——" +
                 "那会画出一条连到空白的线。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_changerisk 变更风险评分 ══════════ */
    @Composable
    fun changerisk(o: JSONObject, ctx: RenderContext) {
        val factorsA = o.optJSONArray("factors") ?: return
        val n = minOf(factorsA.length(), 6)
        val factors = (0 until n).map {
            val f = factorsA.optJSONObject(it) ?: JSONObject()
            Triple(f.optString("n", "因素${it + 1}"), f.optDouble("w", 0.0),
                   f.optString("why", ""))
        }
        var sel by remember { mutableIntStateOf(-1) }
        val raw = factors.sumOf { it.second }
        val score = (raw / n).coerceIn(0.0, 10.0)
        val level = when { score >= 7 -> "high"; score >= 4 -> "med"; else -> "low" }
        val noWhy = factors.count { it.third.isBlank() }
        val mx = factors.maxOf { it.second }.coerceAtLeast(1.0)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "变更风险"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(when (level) { "high" -> "高风险"; "med" -> "中风险"; else -> "低风险" } +
                        "　${"%.1f".format(score)}", fontSize = 9.sp, color = sc(level))
            }
            Spacer(Modifier.height(6.dp))
            factors.forEachIndexed { k, (nm, w, why) ->
                val c = if (w >= 7) ZxT.Err else if (w >= 4) ZxT.Warn else ZxT.Ok
                Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (sel == k) ZxT.Surface2
                                else if (why.isBlank()) ZxT.Warn.copy(.06f) else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_select", mapOf(
                            "factor" to nm, "score" to "%.1f".format(w), "why" to why,
                            "source" to "zx_changerisk"))
                    }.padding(5.dp, 3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(nm, fontSize = 9.sp, color = ZxT.Text2,
                             modifier = Modifier.width(64.dp), maxLines = 1,
                             overflow = TextOverflow.Ellipsis)
                        Box(Modifier.weight(1f).height(8.dp)
                            .clip(RoundedCornerShape(4.dp)).background(ZxT.Surface2)) {
                            Box(Modifier.fillMaxWidth((w / 10.0).toFloat().coerceIn(0.02f, 1f))
                                .fillMaxHeight().background(c))
                        }
                        Text("%.1f".format(w), fontSize = 8.5.sp, color = c,
                             modifier = Modifier.width(24.dp), textAlign = TextAlign.End,
                             fontFamily = ZxT.Mono)
                    }
                    Text(if (why.isBlank()) "没写依据 —— 这个分数是怎么来的？" else why,
                        fontSize = 8.sp,
                        color = if (why.isBlank()) ZxT.Warn else ZxT.Text3,
                        modifier = Modifier.padding(start = 64.dp, top = 1.dp), maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(sc(level).copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (level == "high") "✗" else if (level == "med") "!" else "✓",
                     fontSize = 11.sp, color = sc(level))
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (level == "high") "**高风险**：建议分批放量 + 全程盯指标，" +
                            "并提前准备好回滚${if (noWhy > 0) "——另外有 $noWhy 项没写依据，先补上" else ""}"
                         else if (level == "med") "中风险：按常规灰度走，注意观察前两个批次" +
                            if (noWhy > 0) "；有 $noWhy 项没写依据" else ""
                         else "低风险：可以正常发布${if (noWhy > 0) "；有 $noWhy 项没写依据" else ""}",
                        fontSize = 9.sp, color = sc(level))
                    Text("风险评分的价值不在那个数字，在**每条都要有依据**——" +
                        "没有依据的评分就是拍脑袋，下次不会再有人信它",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 riskmatrix 画<b>概率×影响</b>、healthcheck 看<b>若干检查项</b>，" +
                 "这个评的是<b>这次变更有多危险</b>。" +
                 "关键是<b>每条因素都要写依据</b>——" +
                 "「数据库迁移：8 分」这种空评分下次没人敢用，因为不知道 8 是怎么来的。" +
                 "<b>高风险却有因素没写依据会被拦</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_playbook 处置剧本 ══════════ */
    @Composable
    fun playbook(o: JSONObject, ctx: RenderContext) {
        val stepsA = o.optJSONArray("steps") ?: return
        val n = minOf(stepsA.length(), 8)
        val steps = (0 until n).map {
            val s = stepsA.optJSONObject(it) ?: JSONObject()
            Triple(s.optString("n", "步骤${it + 1}"), s.optString("d", ""),
                   s.optInt("sec", 0))
        }
        var done by remember { mutableStateOf(setOf<Int>()) }
        val hasRollback = steps.any { it.first.contains("回滚") || it.first.contains("撤销") }
        val total = steps.sumOf { it.third }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "处置剧本"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("${done.size}/$n 步", fontSize = 9.sp,
                     color = if (done.size == n) ZxT.Ok else ZxT.Text3)
            }
            Spacer(Modifier.height(6.dp))
            steps.forEachIndexed { k, (nm, d, sec) ->
                val isDone = k in done
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (isDone) ZxT.Ok.copy(.09f) else ZxT.Surface2)
                    .clickable {
                        done = if (isDone) done - k else done + k
                        ctx.callTool("zx_select", mapOf(
                            "step" to (k + 1).toString(), "name" to nm,
                            "done" to (if (!isDone) "yes" else "no"),
                            "source" to "zx_playbook"))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.Top) {
                    Text(if (isDone) "✓" else "${k + 1}", fontSize = 9.sp,
                         color = if (isDone) ZxT.Ok else ZxT.Text3,
                         modifier = Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(nm, fontSize = 9.5.sp,
                                 color = if (isDone) ZxT.Text3 else ZxT.Text,
                                 modifier = Modifier.weight(1f))
                            if (sec > 0)
                                Text("~${sec}s", fontSize = 7.5.sp, color = ZxT.Text3)
                        }
                        if (d.isNotBlank())
                            Text(d, fontSize = 8.5.sp, color = ZxT.Text3,
                                 modifier = Modifier.padding(top = 1.dp), maxLines = 2,
                                 overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (!hasRollback) ZxT.Err.copy(.10f)
                            else if (done.size == n) ZxT.Ok.copy(.10f) else ZxT.Surface3)
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (!hasRollback) "✗" else if (done.size == n) "✓" else "·",
                     fontSize = 11.sp, color = if (!hasRollback) ZxT.Err
                     else if (done.size == n) ZxT.Ok else ZxT.Text3)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (!hasRollback) "**没有回滚步骤**——真的要回滚时只能临时想，" +
                            "而那时人是最慌的"
                         else if (done.size == n) "全部执行完毕（预计耗时 ${total}s）"
                         else "还剩 ${n - done.size} 步${if (total > 0) "，预计 ${total}s" else ""}",
                        fontSize = 9.sp, color = if (!hasRollback) ZxT.Err
                        else if (done.size == n) ZxT.Ok else ZxT.Text2)
                    Text("剧本的意义是**出事时不用想**——顺序、耗时、回滚都提前定好",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 checklist 是<b>要勾的清单</b>、guide 是<b>操作手册</b>，" +
                 "这个的区别是<b>为某一次具体处置而写、且必须带回滚</b>。" +
                 "关键是<b>回滚步骤不能少</b>——" +
                 "只有「怎么做」没有「做错了怎么退」，" +
                 "一旦中间某步出问题就卡住了。" +
                 "<b>缺回滚会被拦</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_escalation 升级路径 ══════════ */
    @Composable
    fun escalation(o: JSONObject, ctx: RenderContext) {
        val lvA = o.optJSONArray("levels") ?: return
        val n = minOf(lvA.length(), 5)
        val levels = (0 until n).map {
            val l = lvA.optJSONObject(it) ?: JSONObject()
            Triple(l.optString("who", "第${it + 1}级"), l.optInt("min", 0),
                   l.optString("how", ""))
        }
        var cur by remember { mutableIntStateOf(0) }
        val last = levels.lastOrNull()

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "升级路径"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (cur >= n - 1) "已到最高级" else "当前第 ${cur + 1} 级",
                    fontSize = 9.sp, color = if (cur >= n - 1) ZxT.Err else ZxT.Text3)
            }
            Spacer(Modifier.height(6.dp))
            levels.forEachIndexed { k, (who, min, how) ->
                val active = k <= cur
                val c = if (k == n - 1) ZxT.Err else if (k >= 2) ZxT.Warn else PAL[0]
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (active) c.copy(.10f) else ZxT.Surface2)
                    .clickable {
                        cur = k
                        ctx.callTool("zx_select", mapOf(
                            "level" to (k + 1).toString(), "who" to who,
                            "after_min" to min.toString(), "source" to "zx_escalation"))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("L${k + 1}", fontSize = 8.sp, color = c,
                         modifier = Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text(who, fontSize = 9.5.sp,
                             color = if (active) ZxT.Text else ZxT.Text3)
                        Row {
                            Text(if (min > 0) "${min} 分钟未响应则升级" else "第一响应人",
                                fontSize = 7.5.sp, color = ZxT.Text3,
                                modifier = Modifier.weight(1f))
                            if (how.isNotBlank())
                                Text(how, fontSize = 7.5.sp, color = ZxT.Text3, maxLines = 1,
                                     overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (active && k == cur)
                        Text("● 当前", fontSize = 7.5.sp, color = c)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (cur >= n - 1) ZxT.Err.copy(.10f)
                            else if (last?.second ?: 0 > 0) ZxT.Ok.copy(.10f) else ZxT.Warn.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (cur >= n - 1) "✗" else if (last?.second ?: 0 > 0) "✓" else "!",
                     fontSize = 11.sp, color = if (cur >= n - 1) ZxT.Err
                     else if (last?.second ?: 0 > 0) ZxT.Ok else ZxT.Warn)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (cur >= n - 1) "已经升到最高级「${last?.first}」——" +
                            "**再往上没有人了**，这时要的是并行扩散而不是继续升级"
                         else if (cur > 0) "已升到 L${cur + 1}「${levels[cur].first}」"
                         else "等待 L1 响应${last?.second?.let { "，${it} 分钟后自动升级" } ?: ""}",
                        fontSize = 9.sp, color = if (cur >= n - 1) ZxT.Err else ZxT.Text2)
                    Text("只写「联系张三」是不够的：**他睡着了就没人管了**——" +
                        "所以每级都要有超时",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 assign 分配<b>责任人</b>、alert 列<b>告警与动作</b>，" +
                 "这个定的是<b>没人响应时往谁那里升</b>。" +
                 "关键是<b>每级都要有超时、且要有终点</b>——" +
                 "没有超时，一级级传上去可能半天没人看；" +
                 "没有终点，升到最后没人接。<b>超时未升序或没有终点会被拦</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_postmortem 复盘 ══════════ */
    @Composable
    fun postmortem(o: JSONObject, ctx: RenderContext) {
        val actsA = o.optJSONArray("actions") ?: return
        val n = minOf(actsA.length(), 6)
        val acts = (0 until n).map {
            val a = actsA.optJSONObject(it) ?: JSONObject()
            Triple(a.optString("n", "行动项${it + 1}"), a.optString("who", ""),
                   a.optString("by", ""))
        }
        var sel by remember { mutableIntStateOf(-1) }
        val summary = o.optString("summary", "")
        val noOwner = acts.count { it.second.isBlank() }
        val noDue = acts.count { it.third.isBlank() }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "复盘"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (acts.isEmpty()) "无行动项" else "${acts.size} 项行动",
                    fontSize = 9.sp, color = if (acts.isEmpty()) ZxT.Err else ZxT.Ok)
            }
            if (summary.isNotBlank())
                Text(summary, fontSize = 9.5.sp, color = ZxT.Text2,
                     modifier = Modifier.padding(top = 5.dp), lineHeight = 14.sp)
            Spacer(Modifier.height(5.dp))
            listOf("影响" to o.optString("impact", ""),
                   "根因" to o.optString("root", "")).forEach { (k, v) ->
                if (v.isBlank()) return@forEach
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(k, fontSize = 8.sp, color = ZxT.Text3, modifier = Modifier.width(32.dp))
                    Text(v, fontSize = 9.sp, color = ZxT.Text2,
                         modifier = Modifier.weight(1f), lineHeight = 13.sp)
                }
            }
            Spacer(Modifier.height(4.dp))
            acts.forEachIndexed { k, (nm, who, by) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (sel == k) ZxT.Surface2
                                else if (who.isBlank()) ZxT.Warn.copy(.06f) else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_select", mapOf(
                            "action" to nm, "owner" to who, "due" to by,
                            "source" to "zx_postmortem"))
                    }.padding(5.dp, 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("◦", fontSize = 9.sp, color = PAL[0], modifier = Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(nm, fontSize = 9.5.sp, color = ZxT.Text)
                        Row {
                            Text(if (who.isBlank()) "**没有负责人**" else who,
                                fontSize = 8.sp,
                                color = if (who.isBlank()) ZxT.Warn else ZxT.Text3,
                                modifier = Modifier.weight(1f))
                            Text(if (by.isBlank()) "无期限" else by, fontSize = 8.sp,
                                 color = if (by.isBlank()) ZxT.Text3 else ZxT.Text3)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (acts.isEmpty()) ZxT.Err.copy(.10f)
                            else if (noOwner > 0) ZxT.Warn.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (acts.isEmpty()) "✗" else if (noOwner > 0) "!" else "✓",
                     fontSize = 11.sp, color = if (acts.isEmpty()) ZxT.Err
                     else if (noOwner > 0) ZxT.Warn else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (acts.isEmpty()) "**一条行动项都没有**——只有时间线的复盘等于没复盘，" +
                            "下次照样挂"
                         else if (noOwner > 0) "$noOwner 项没有负责人${if (noDue > 0) "、$noDue 项没有期限" else ""}" +
                            "——**没人的行动项不会被执行**"
                         else "${acts.size} 项行动都有负责人和期限",
                        fontSize = 9.sp, color = if (acts.isEmpty()) ZxT.Err
                        else if (noOwner > 0) ZxT.Warn else ZxT.Ok)
                    Text("复盘的目的是**下次不再发生**，不是**记录这次发生了什么**",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 incident 记<b>事故时间线</b>、rca 追<b>根因</b>，" +
                 "这个把两者合成<b>一份可执行的结论</b>。" +
                 "关键是<b>必须有行动项，且每项要有负责人</b>——" +
                 "「以后要注意容量评估」不是行动项，" +
                 "「容量复盘进发布流程 @李四 本季度」才是。" +
                 "<b>没有行动项会被拦</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_knowledge 知识沉淀 ══════════ */
    @Composable
    fun knowledge(o: JSONObject, ctx: RenderContext) {
        val itemsA = o.optJSONArray("items") ?: return
        val n = minOf(itemsA.length(), 6)
        val items = (0 until n).map { i ->
            val it = itemsA.optJSONObject(i) ?: JSONObject()
            Triple(it.optString("n", "知识${i + 1}"), it.optString("src", ""),
                   it.optString("at", ""))
        }
        var sel by remember { mutableIntStateOf(-1) }
        val staleAt = o.optInt("stale", 90)   // 超过多少天算过期
        val noSrc = items.count { it.second.isBlank() }
        val noAt = items.count { it.third.isBlank() }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "知识沉淀"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (noSrc + noAt > 0) "${noSrc + noAt} 项缺出处" else "出处齐全",
                    fontSize = 9.sp, color = if (noSrc + noAt > 0) ZxT.Warn else ZxT.Ok)
            }
            Spacer(Modifier.height(6.dp))
            items.forEachIndexed { k, (nm, src, at) ->
                val days = at.toIntOrNull()
                val stale = days != null && days > staleAt
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2
                                else if (stale) ZxT.Warn.copy(.07f) else ZxT.Surface2.copy(.5f))
                    .clickable {
                        sel = k
                        ctx.callTool("zx_select", mapOf(
                            "item" to nm, "source" to src, "age_days" to at,
                            "stale" to (if (stale) "yes" else "no"), "source" to "zx_knowledge"))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.Top) {
                    Text(if (stale) "!" else "·", fontSize = 9.sp,
                         color = if (stale) ZxT.Warn else PAL[0],
                         modifier = Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(nm, fontSize = 9.5.sp, color = ZxT.Text, lineHeight = 13.sp)
                        Row(Modifier.padding(top = 2.dp)) {
                            Text(if (src.isBlank()) "**没写出处**" else src,
                                fontSize = 8.sp,
                                color = if (src.isBlank()) ZxT.Warn else ZxT.Text3,
                                modifier = Modifier.weight(1f), maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text(if (at.isBlank()) "**没写时间**"
                                 else if (stale) "$at 天前（超过 ${staleAt} 天，可能已过期）"
                                 else "$at 天前",
                                fontSize = 8.sp,
                                color = if (at.isBlank()) ZxT.Warn
                                else if (stale) ZxT.Warn else ZxT.Text3)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (noSrc + noAt > 0) ZxT.Warn.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (noSrc + noAt > 0) "!" else "✓", fontSize = 11.sp,
                     color = if (noSrc + noAt > 0) ZxT.Warn else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (noSrc > 0) "$noSrc 项没写出处——**无法复核**，" +
                            "下次要么重查要么盲信"
                         else if (noAt > 0) "$noAt 项没写时间——**无法判断是否过期**"
                         else "每条都有出处和时间",
                        fontSize = 9.sp, color = if (noSrc + noAt > 0) ZxT.Warn else ZxT.Ok)
                    Text("**过期的知识比没有知识更糟**——它是错的，而且看起来可信。" +
                        "所以必须标时效（阈值 ${staleAt} 天）",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 howto 写<b>操作手册</b>、ledger 记<b>假设查过没有</b>，" +
                 "这个存的是<b>结论性的经验</b>。" +
                 "区别在<b>必须标出处和时效</b>：—条「Redis 连接池设 200 合适」" +
                 "三个月后可能已经不适用，而看的人不知道。<b>缺出处或时间会被拦</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_decisionlog 决策日志 ══════════ */
    @Composable
    fun decisionlog(o: JSONObject, ctx: RenderContext) {
        val itemsA = o.optJSONArray("items") ?: return
        val n = minOf(itemsA.length(), 6)
        val items = (0 until n).map { i ->
            val it = itemsA.optJSONObject(i) ?: JSONObject()
            Triple(it.optString("n", "决策${i + 1}"), it.optString("why", ""),
                   it.optString("who", ""))
        }
        var sel by remember { mutableIntStateOf(-1) }
        val noWhy = items.count { it.second.isBlank() }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "决策日志"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (noWhy > 0) "$noWhy 项缺依据" else "依据齐全", fontSize = 9.sp,
                     color = if (noWhy > 0) ZxT.Warn else ZxT.Ok)
            }
            Spacer(Modifier.height(6.dp))
            items.forEachIndexed { k, (nm, why, who) ->
                Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2
                                else if (why.isBlank()) ZxT.Warn.copy(.06f) else ZxT.Surface2.copy(.5f))
                    .clickable {
                        sel = k
                        ctx.callTool("zx_select", mapOf(
                            "decision" to nm, "why" to why, "who" to who,
                            "source" to "zx_decisionlog"))
                    }.padding(6.dp, 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(2.dp).height(14.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(if (why.isBlank()) ZxT.Warn else PAL[0]))
                        Text(nm, fontSize = 9.5.sp, color = ZxT.Text,
                             modifier = Modifier.weight(1f).padding(start = 6.dp),
                             lineHeight = 13.sp)
                        if (who.isNotBlank())
                            Text(who, fontSize = 8.sp, color = ZxT.Text3)
                    }
                    Text(if (why.isBlank()) "**没写为什么** —— 半年后没人知道当初为什么这么选"
                         else why, fontSize = 8.5.sp,
                         color = if (why.isBlank()) ZxT.Warn else ZxT.Text3,
                         modifier = Modifier.padding(start = 8.dp, top = 2.dp),
                         lineHeight = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (noWhy > 0) ZxT.Warn.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (noWhy > 0) "!" else "✓", fontSize = 11.sp,
                     color = if (noWhy > 0) ZxT.Warn else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (noWhy > 0) "$noWhy 项只记了结论没记依据——" +
                            "**半年后没人知道当初为什么这么选**，也就没法判断现在该不该改"
                         else "${items.size} 项都写了依据",
                        fontSize = 9.sp, color = if (noWhy > 0) ZxT.Warn else ZxT.Ok)
                    Text("决策会过期，依据不会——**换依据才知道该不该换决策**",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 timeline 记<b>发生了什么</b>、regret 看<b>选得对不对</b>，" +
                 "这个记的是<b>当时为什么这么选</b>。" +
                 "关键是<b>必须写依据</b>：只记「决定用 Kafka」，" +
                 "半年后没人知道是因为吞吐还是因为团队熟悉。" +
                 "<b>缺依据会被拦</b>——记了等于没记。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_debt 技术债（带利息） ══════════ */
    @Composable
    fun debt(o: JSONObject, ctx: RenderContext) {
        val itemsA = o.optJSONArray("items") ?: return
        val n = minOf(itemsA.length(), 8)
        val items = (0 until n).map { i ->
            val it = itemsA.optJSONObject(i) ?: JSONObject()
            Triple(it.optString("n", "债务${i + 1}"), it.optDouble("int", 0.0),
                   it.optDouble("cost", 0.0))
        }.sortedByDescending { it.second }
        var sel by remember { mutableIntStateOf(-1) }
        val maxInt = items.maxOf { it.second }.coerceAtLeast(1.0)
        val maxCost = items.maxOf { it.third }.coerceAtLeast(1.0)
        // 优先级 = 利息 / 偿还成本（单位成本能省多少利息）
        val ranked = items.mapIndexed { k, x -> k to (x.second / x.third.coerceAtLeast(0.5)) }
            .sortedByDescending { it.second }
        val zeroInt = items.count { it.second <= 0 }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "技术债"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (zeroInt > 0) "$zeroInt 项利息为 0" else "利息/成本排序",
                    fontSize = 9.sp, color = if (zeroInt > 0) ZxT.Warn else ZxT.Ok)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
                Text("债务", fontSize = 8.sp, color = ZxT.Text3, modifier = Modifier.weight(1f))
                Text("利息/月", fontSize = 8.sp, color = ZxT.Text3,
                     modifier = Modifier.width(40.dp), textAlign = TextAlign.End)
                Text("偿还", fontSize = 8.sp, color = ZxT.Text3,
                     modifier = Modifier.width(32.dp), textAlign = TextAlign.End)
                Text("性价比", fontSize = 8.sp, color = ZxT.Text3,
                     modifier = Modifier.width(34.dp), textAlign = TextAlign.End)
            }
            items.forEachIndexed { k, (nm, int, cost) ->
                val eff = int / cost.coerceAtLeast(0.5)
                val best = ranked.firstOrNull()?.first == k
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (sel == k) ZxT.Surface2
                                else if (best) ZxT.Ok.copy(.09f)
                                else if (int <= 0) ZxT.Warn.copy(.06f) else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_select", mapOf(
                            "debt" to nm, "interest" to "%.1f".format(int),
                            "cost" to "%.1f".format(cost), "eff" to "%.2f".format(eff),
                            "best" to (if (best) "yes" else "no"), "source" to "zx_debt"))
                    }.padding(5.dp, 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(nm, fontSize = 9.sp, color = ZxT.Text2,
                         modifier = Modifier.weight(1f), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    Box(Modifier.width(40.dp).height(7.dp)
                        .clip(RoundedCornerShape(3.dp)).background(ZxT.Surface2)) {
                        Box(Modifier.fillMaxWidth((int / maxInt).toFloat().coerceIn(0.01f, 1f))
                            .fillMaxHeight()
                            .background(if (int <= 0) ZxT.Text3 else ZxT.Err))
                    }
                    Text("%.0f".format(cost), fontSize = 8.5.sp, color = ZxT.Text3,
                         modifier = Modifier.width(32.dp), textAlign = TextAlign.End,
                         fontFamily = ZxT.Mono)
                    Text("%.2f".format(eff), fontSize = 8.5.sp,
                         color = if (best) ZxT.Ok else if (int <= 0) ZxT.Warn else ZxT.Text2,
                         modifier = Modifier.width(34.dp), textAlign = TextAlign.End,
                         fontFamily = ZxT.Mono)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (zeroInt > 0) ZxT.Warn.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (zeroInt > 0) "!" else "✓", fontSize = 11.sp,
                     color = if (zeroInt > 0) ZxT.Warn else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (zeroInt > 0) "$zeroInt 项利息为 0——**那不叫债，那叫待办**，" +
                            "混在债清单里会挤掉真正该还的"
                         else "先还「${items[ranked.first().first].first}」：" +
                            "利息 ${"%.0f".format(items[ranked.first().first].second)}/月，" +
                            "只要 ${"%.0f".format(items[ranked.first().first].third)} 人天，" +
                            "性价比最高",
                        fontSize = 9.sp, color = if (zeroInt > 0) ZxT.Warn else ZxT.Ok)
                    Text("**按利息排会永远先还最大的；按利息/成本排才先还最省的**——" +
                        "后者能滚起来",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 backlog 列<b>待办</b>、priority 排<b>优先级</b>，" +
                 "这个算的是<b>债务的利息</b>。" +
                 "关键是<b>必须标利息</b>：不还也不会怎样的那不叫债；" +
                 "而且排序要用<b>利息/成本</b>而不是利息——" +
                 "先还最省的那个，省下的时间再去还下一个。" +
                 "<b>利息为 0 会被拦</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_checkpoint 检查点 ══════════ */
    @Composable
    fun checkpoint(o: JSONObject, ctx: RenderContext) {
        val ptsA = o.optJSONArray("pts") ?: return
        val n = minOf(ptsA.length(), 8)
        val pts = (0 until n).map {
            val p = ptsA.optJSONObject(it) ?: JSONObject()
            p.optDouble("p", 0.0) to p.optString("n", "检查点${it + 1}")
        }
        var sel by remember { mutableIntStateOf(-1) }
        val total = o.optDouble("total", 100.0)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "检查点"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("${pts.size} 个 / 进度 ${"%.0f".format(pts.lastOrNull()?.first ?: 0.0)}%",
                    fontSize = 9.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(10.dp)
                .clip(RoundedCornerShape(5.dp)).background(ZxT.Surface2)) {
                Box(Modifier.fillMaxWidth(((pts.lastOrNull()?.first ?: 0.0) / total)
                    .toFloat().coerceIn(0.01f, 1f)).fillMaxHeight()
                    .background(PAL[0]))
            }
            Spacer(Modifier.height(4.dp))
            pts.forEachIndexed { k, (p, nm) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (sel == k) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_select", mapOf(
                            "checkpoint" to nm, "progress" to "%.1f".format(p),
                            "index" to k.toString(), "source" to "zx_checkpoint"))
                    }.padding(5.dp, 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(RoundedCornerShape(3.5.dp))
                        .background(if (k == n - 1) PAL[0] else ZxT.Text3.copy(.5f)))
                    Text(nm, fontSize = 9.sp, color = ZxT.Text2,
                         modifier = Modifier.weight(1f).padding(start = 6.dp), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    Text("${"%.0f".format(p)}%", fontSize = 8.5.sp, color = ZxT.Text2,
                         modifier = Modifier.width(30.dp), textAlign = TextAlign.End,
                         fontFamily = ZxT.Mono)
                    if (k < n - 1) {
                        val next = pts[k + 1].first
                        Text("丢 ${"%.0f".format(next - p)}%", fontSize = 7.5.sp,
                             color = if (next - p > 25) ZxT.Err else ZxT.Text3,
                             modifier = Modifier.width(40.dp).padding(start = 4.dp),
                             textAlign = TextAlign.End)
                    } else Text("最新", fontSize = 7.5.sp, color = ZxT.Ok,
                                modifier = Modifier.width(40.dp).padding(start = 4.dp),
                                textAlign = TextAlign.End)
                }
            }
            val worst = (1 until n).map { k -> k to (pts[k].first - pts[k - 1].first) }
                .maxByOrNull { it.second }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (worst != null && worst.second > 25) ZxT.Err.copy(.10f)
                            else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (worst != null && worst.second > 25) "✗" else "✓", fontSize = 11.sp,
                     color = if (worst != null && worst.second > 25) ZxT.Err else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (worst != null && worst.second > 25)
                        "最大间隔 ${"%.0f".format(worst.second)}%（${pts[worst.first - 1].second} → " +
                            "${pts[worst.first].second}）——**崩在这一段会丢掉这么多进度**，" +
                            "该在中间补一个检查点"
                         else "间隔都比较小，崩了也不会丢太多",
                        fontSize = 9.sp,
                        color = if (worst != null && worst.second > 25) ZxT.Err else ZxT.Ok)
                    Text("长任务不存检查点的代价是**崩了要重头开始**——" +
                        "而崩往往发生在跑了很久之后",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 progress 给<b>一个百分比</b>、stepper 走<b>定好的流程</b>，" +
                 "这个管的是<b>长任务的保存点</b>。" +
                 "关键是<b>要看间隔</b>：只标「进度 80%」，" +
                 "不知道上一个保存点是 30%——那意味着崩了要丢 50%。" +
                 "<b>进度未升序会被拦</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }
}
