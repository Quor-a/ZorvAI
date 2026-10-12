package com.zorv.plugin.zxwidget

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 第三十一批组件（12 个）—— 模拟与推演。
 *
 * 前三十批有一条共同的边界：**输入都是已经发生的数据**。
 *
 *   看什么     → 已有数据长什么样
 *   填什么     → 让用户给数据
 *   凭什么     → 已有数据支持什么结论
 *   是不是因果 → 已有数据里 A 是不是导致 B
 *   接下来     → 基于已有数据该做什么
 *   做完了     → 行动之后发生了什么
 *   治理       → 已有权限/成本/合规怎么管
 *
 * 但真实决策里最常问的恰恰是**还没有数据**的那句：
 *
 *   「如果我把价格提到 39，销量会掉到多少？」
 *   「这个方案最坏能坏到什么程度？」
 *   「要达成 100 万，我得先做到哪一步？」
 *   「现在就上，还是再等一个月看清楚再上？」
 *   「系统能扛住双十一的三倍流量吗？」
 *
 * 十二个组件分成四组：
 *
 *   **多情景**（未来不是一条线）
 *     zx_scenario      情景推演：乐观/中性/悲观并列，画出色带
 *     zx_montecarlo    蒙特卡洛：跑 N 次，给结果分布而不只是期望
 *     zx_stress        压力情景：极端条件下哪个先崩
 *
 *   **敏感性**（什么在真正驱动结果）
 *     zx_tornado       龙卷风图：单因素敏感性排序
 *     zx_whatif        假设推演：改一个输入，全链路跟着变
 *     zx_breakeven     盈亏平衡：找到临界点，且临界点要能移动
 *
 *   **动力学**（会怎么演化）
 *     zx_systemdyn     系统动力学：存量-流量，看长期行为
 *     zx_markov        马尔可夫链：n 步后分布与稳态
 *     zx_optpath       最优路径：带代价的路径寻优
 *
 *   **决策结构**（不只是算，是博弈与时点）
 *     zx_payoff        博弈支付矩阵：占优策略与纳什均衡
 *     zx_reversesolve  反向求解：要达标，输入得是多少
 *     zx_realoption    实物期权：**等待本身有价值**
 *
 * 贯穿这批的一条：**推演要给"有多不确定"，而不只是一个数**。
 *
 *   蒙特卡洛只给期望，不给 P(达标)        → 期望达标但实际一半概率翻车
 *   情景推演只给中性，不给上下界          → 未来看起来是一条确定的线
 *   压力测试全部存活                      → 说明压得不够，没测出边界
 *   反向求解不给「无解」                  → 给一个够不着的输入让人白努力
 *   盈亏平衡不给「永远平衡不了」          → 单价低于变动成本时临界点是无穷大
 *
 *   **只给一个数的推演，会把不确定伪装成确定。**
 *   而要命的是——推演出来的东西用户没法用事实校验，
 *   因为它本来就是"还没发生的事"。**错的推演比没有推演更危险。**
 *
 * 校验因此换了第九档。前八档拦的是
 *   "画不出来" → "统计前提不成立" → "区间前提不成立" →
 *   "因果前提无从判断" → "看起来确定了其实没那么确定" →
 *   "状态摆出来了但推不出该做什么" → "记下来了但下次用不上" →
 *   "数字都在但落不到谁该做什么"，
 * 这批拦的是
 *   **"推演出来了，但推演本身不可信"**：
 *
 *   montecarlo    试验次数 <200（分位数不稳）/ sd<0（分布非法）
 *   scenario      情景序列长度不齐（画不出带）/ 悲观值优于乐观值（上下界反了）
 *   tornado       影响值全为 0（排不出序）/ 因素名重复
 *   breakeven     单价 ≤ 变动成本（**永远盈亏平衡不了**，临界点是无穷大）
 *   whatif        链路缺节点 / 传播系数未定义
 *   systemdyn     流入导致存量变负（物理上不可能）
 *   markov        非方阵 / 每行和不为 1（不是合法转移矩阵）
 *   payoff        非 2x2 / 双方支付数不齐
 *   optpath       起点终点不存在 / 图不连通（无路径）
 *   reversesolve  目标不在值域内（**无解**，且必须明说）
 *   stress        全部存活（**压得不够，没测出边界**）
 *   realoption    波动率非正（期权价值算不出来）
 */
object ZxRender31 {

    private val PAL = listOf(
        Color(0xFF5046E4), Color(0xFF3FBF7F), Color(0xFFE8A33D),
        Color(0xFFE5544B), Color(0xFF4A9BE8), Color(0xFF8E86FF)
    )

    /* 确定性随机源：同一 seed 必然得到同一序列。
       不直接用 java.util.Random——不同实现/版本的序列可能不同，
       存了 seed 也复现不了，那情景快照就没意义了。 */
    class Lcg(private var s: Long) {
        fun next(): Double {
            s = (s * 6364136223846793005L + 1442695040888963407L) and 0x7FFFFFFFFFFFFFFFL
            return s.toDouble() / 0x7FFFFFFFFFFFFFFFL
        }
        /** Box-Muller，一次生成两个标准正态 */
        fun norm(): Double {
            val u1 = max(next(), 1e-12); val u2 = next()
            return sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2.0 * kotlin.math.PI * u2)
        }
    }

    private fun jarr(o: JSONObject, k: String): List<JSONObject> {
        val a = o.optJSONArray(k) ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }
    private fun jdarr(o: JSONObject, k: String): List<Double> {
        val a = o.optJSONArray(k) ?: return emptyList()
        return (0 until a.length()).map { a.optDouble(it, Double.NaN) }
    }

    /* ══════════ zx_scenario 情景推演 ══════════ */
    @Composable
    fun scenario(o: JSONObject, ctx: RenderContext) {
        val scs = jarr(o, "scenarios")
        var sel by remember { mutableIntStateOf(-1) }
        val names = scs.map { it.optString("n", "情景") }
        val series = scs.map { jdarr(it, "v") }
        val len = series.firstOrNull()?.size ?: 0
        val lo = (0 until len).map { i -> series.minOf { it.getOrElse(i) { 0.0 } } }
        val hi = (0 until len).map { i -> series.maxOf { it.getOrElse(i) { 0.0 } } }
        val all = series.flatten() + lo + hi
        val mn = all.minOrNull() ?: 0.0
        val mx = all.maxOrNull()?.let { if (it == mn) mn + 1 else it } ?: 1.0
        val base = scs.indexOfFirst { it.optString("n").contains("中性") }
            .let { if (it < 0) (scs.size / 2) else it }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "情景推演"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("${scs.size} 情景 / $len 期", fontSize = 9.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(78.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height
                    fun px(i: Int) = if (len <= 1) 0f else i.toFloat() / (len - 1) * W
                    fun py(v: Double) = H - ((v - mn) / (mx - mn) * (H - 6f)).toFloat()
                    // 色带：悲观-乐观之间
                    drawPath(Path().apply {
                        moveTo(px(0), py(hi[0]))
                        for (i in 1 until len) lineTo(px(i), py(hi[i]))
                        for (i in len - 1 downTo 0) lineTo(px(i), py(lo[i]))
                        close()
                    }, PAL[0].copy(alpha = 0.14f))
                    series.forEachIndexed { k, s ->
                        drawPath(Path().apply {
                            s.forEachIndexed { i, v ->
                                val x = px(i); val y = py(v)
                                if (i == 0) moveTo(x, y) else lineTo(x, y)
                            }
                        }, PAL[k % PAL.size],
                            style = Stroke(width = if (k == base) 2.1f else 1.3f))
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            series.forEachIndexed { k, s ->
                val end = s.lastOrNull() ?: 0.0
                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (sel == k) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_select", mapOf(
                            "scenario" to names[k], "end" to "%.1f".format(end),
                            "source" to "zx_scenario"))
                    }.padding(5.dp, 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp))
                        .background(PAL[k % PAL.size]))
                    Spacer(Modifier.width(5.dp))
                    Text(names[k], fontSize = 9.sp, color = ZxT.Text2,
                         modifier = Modifier.weight(1f), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    if (k == base) Text("基准", fontSize = 7.5.sp, color = ZxT.Text3)
                    Text("%.1f".format(end), fontSize = 9.sp, color = ZxT.Text,
                         modifier = Modifier.width(40.dp), textAlign = TextAlign.End,
                         fontFamily = ZxT.Mono)
                }
            }
            val spread = if (len > 0) hi[len - 1] - lo[len - 1] else 0.0
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (spread > (mx - mn) * 0.6) ZxT.Warn.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (spread > (mx - mn) * 0.6) "!" else "✓", fontSize = 11.sp,
                     color = if (spread > (mx - mn) * 0.6) ZxT.Warn else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text("末期区间 ${"%.1f".format(lo.lastOrNull() ?: 0.0)} ~ " +
                        "${"%.1f".format(hi.lastOrNull() ?: 0.0)}，跨度 ${"%.1f".format(spread)}",
                        fontSize = 9.sp, color = ZxT.Text2)
                    Text(if (spread > (mx - mn) * 0.6)
                            "**跨度超过全量程 60%** —— 这个预测基本没有分辨力，" +
                            "按乐观和按悲观做的准备会差很多"
                         else "区间收得还算窄，可以按中性情景做准备并留余量",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 forecast 给<b>一条预测线加区间</b>，" +
                 "这个并列<b>几个完整的情景</b>。" +
                 "区别是：区间回答「会波动多少」，情景回答「<b>如果事情朝不同方向发展</b>" +
                 "会怎样」——后者能承载「政策变了/对手降价了」这种结构性差异，区间不能。" +
                 "<b>序列长度不齐会被拦</b>（画不出带）；" +
                 "<b>悲观值优于乐观值会被拦</b>（上下界反了）。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_montecarlo 蒙特卡洛 ══════════ */
    @Composable
    fun montecarlo(o: JSONObject, ctx: RenderContext) {
        val n = o.optInt("n", 2000)
        val mean = o.optDouble("mean", 0.0)
        val sd = o.optDouble("sd", 1.0)
        val target = o.optDouble("target", Double.NaN)
        val seed = o.optLong("seed", 20250101L)
        val bins = 22
        val rnd = remember(seed, n, mean, sd) {
            val r = Lcg(seed)
            val xs = (0 until n).map { mean + sd * r.norm() }.sorted()
            val mn = xs.first(); val mx = xs.last()
            val w = if (mx - mn < 1e-9) 1.0 else mx - mn
            val h = IntArray(bins)
            xs.forEach { v ->
                val b = (((v - mn) / w * (bins - 1)).toInt()).coerceIn(0, bins - 1); h[b]++
            }
            object {
                val samples = xs; val hist = h; val lo = mn; val hi = mx
                val p05 = xs[(0.05 * n).toInt().coerceIn(0, n - 1)]
                val p50 = xs[(0.50 * n).toInt().coerceIn(0, n - 1)]
                val p95 = xs[(0.95 * n).toInt().coerceIn(0, n - 1)]
                val hit = if (target.isNaN()) -1.0 else xs.count { it >= target }.toDouble() / n
            }
        }
        val maxH = rnd.hist.maxOrNull()?.coerceAtLeast(1) ?: 1

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "蒙特卡洛"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("$n 次 · seed=$seed", fontSize = 9.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(66.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height
                    val bw = W / bins
                    rnd.hist.forEachIndexed { i, c ->
                        val h = c.toFloat() / maxH * (H - 4f)
                        val v = rnd.lo + (i.toDouble() / (bins - 1)) * (rnd.hi - rnd.lo)
                        val col = if (target.isNaN()) PAL[0]
                                  else if (v >= target) ZxT.Ok else PAL[4]
                        drawRect(col, Offset(i * bw, H - h), androidx.compose.ui.geometry.Size(bw - 1f, h))
                    }
                    // 目标线
                    if (!target.isNaN() && target >= rnd.lo && target <= rnd.hi) {
                        val x = ((target - rnd.lo) / (rnd.hi - rnd.lo) * W).toFloat()
                        drawLine(ZxT.Err, Offset(x, 0f), Offset(x, H), 1.3f)
                    }
                    // 中位数
                    val x50 = ((rnd.p50 - rnd.lo) / (rnd.hi - rnd.lo) * W).toFloat()
                    drawLine(ZxT.Warn, Offset(x50, 0f), Offset(x50, H), 1.1f)
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth()) {
                listOf("P05" to rnd.p05, "中位" to rnd.p50, "P95" to rnd.p95)
                    .forEach { (k, v) ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("%.1f".format(v), fontSize = 9.5.sp, color = ZxT.Text,
                             fontFamily = ZxT.Mono)
                        Text(k, fontSize = 7.5.sp, color = ZxT.Text3)
                    }
                }
                if (!target.isNaN())
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("%.0f%%".format(rnd.hit * 100), fontSize = 9.5.sp,
                             color = if (rnd.hit >= 0.8) ZxT.Ok else if (rnd.hit >= 0.5) ZxT.Warn else ZxT.Err,
                             fontFamily = ZxT.Mono)
                        Text("达标率", fontSize = 7.5.sp, color = ZxT.Text3)
                    }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (!target.isNaN() && rnd.hit < 0.5) ZxT.Err.copy(.10f)
                            else if (!target.isNaN() && rnd.hit < 0.8) ZxT.Warn.copy(.10f)
                            else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (!target.isNaN() && rnd.hit < 0.5) "✗"
                     else if (!target.isNaN() && rnd.hit < 0.8) "!" else "✓",
                     fontSize = 11.sp,
                     color = if (!target.isNaN() && rnd.hit < 0.5) ZxT.Err
                             else if (!target.isNaN() && rnd.hit < 0.8) ZxT.Warn else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (target.isNaN()) "没给 target——**只给期望不给 P(达标)，" +
                            "决策者会把「期望达标」当成「大概率达标」**"
                         else if (rnd.hit < 0.5) "**达标率只有 ${"%.0f%%".format(rnd.hit * 100)}**——" +
                            "均值可能达标，但一半以上的情况会翻车"
                         else if (rnd.hit < 0.8) "达标率 ${"%.0f%%".format(rnd.hit * 100)}，" +
                            "五次里有一次不达标——**能不能承受那一次**"
                         else "达标率 ${"%.0f%%".format(rnd.hit * 100)}，比较稳",
                        fontSize = 9.sp,
                        color = if (target.isNaN()) ZxT.Text2
                                else if (rnd.hit < 0.5) ZxT.Err
                                else if (rnd.hit < 0.8) ZxT.Warn else ZxT.Ok)
                    Text("**期望达标 ≠ 大概率达标**。分布右偏时中位数远低于均值，只看均值会系统性乐观",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 boot 做<b>对已有样本的重采样</b>，" +
                 "这个做的是<b>对假设模型的随机抽样</b>。" +
                 "区别是：boot 问「这个估计有多稳」，蒙特卡洛问「<b>如果世界是这样运转的，" +
                 "结果会分布成什么样</b>」。关键是<b>必须存 seed</b>——" +
                 "不存种子，再跑一次结果就变了，那这个推演没法复核。" +
                 "<b>次数 <200 会被拦</b>（分位数不稳）；<b>sd<0 会被拦</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_tornado 龙卷风图 ══════════ */
    @Composable
    fun tornado(o: JSONObject, ctx: RenderContext) {
        val items = jarr(o, "items").map {
            Triple(it.optString("n", "因素"), it.optDouble("lo", 0.0), it.optDouble("hi", 0.0))
        }.sortedByDescending { abs(it.third - it.second) }
        var sel by remember { mutableIntStateOf(-1) }
        val mx = items.maxOf { max(abs(it.second), abs(it.third)) }.let { if (it < 1e-9) 1.0 else it }
        val top = items.firstOrNull()

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "敏感性分析"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("${items.size} 个因素", fontSize = 9.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(6.dp))
            items.forEachIndexed { k, (nm, lo, hi) ->
                val w = abs(hi - lo)
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (sel == k) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_select", mapOf(
                            "factor" to nm, "lo" to "%.2f".format(lo), "hi" to "%.2f".format(hi),
                            "swing" to "%.2f".format(w), "source" to "zx_tornado"))
                    }.padding(5.dp, 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(nm, fontSize = 9.sp, color = ZxT.Text2,
                         modifier = Modifier.width(58.dp), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    // 以 0 为中心向两侧
                    Box(Modifier.weight(1f).height(9.dp)) {
                        Box(Modifier.fillMaxWidth().fillMaxHeight()
                            .clip(RoundedCornerShape(3.dp)).background(ZxT.Surface2))
                        val half = (mx).toFloat()
                        Row(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f).fillMaxHeight())
                            Box(Modifier.fillMaxWidth(0.5f).fillMaxHeight())
                        }
                        Box(Modifier.fillMaxSize()) {
                            val lp = (min(lo, hi) / half).coerceIn(-1.0, 0.0)
                            val rp = (max(lo, hi) / half).coerceIn(0.0, 1.0)
                            Row(Modifier.fillMaxSize()) {
                                Box(Modifier.weight(0.5f).fillMaxHeight())
                                Box(Modifier.weight(1f).fillMaxHeight()
                                    .background(Color.Transparent))
                            }
                            Canvas(Modifier.fillMaxSize()) {
                                val W = size.width; val H = size.height
                                val cx = W / 2
                                drawRect(if (k == 0) ZxT.Err else PAL[k % PAL.size],
                                    Offset(cx + (lp * W / 2).toFloat(), 0f),
                                    androidx.compose.ui.geometry.Size(
                                        ((rp - lp) * W / 2).toFloat(), H))
                            }
                        }
                    }
                    Text("%.1f".format(w), fontSize = 8.5.sp, color = ZxT.Text,
                         modifier = Modifier.width(32.dp), textAlign = TextAlign.End,
                         fontFamily = ZxT.Mono)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(ZxT.Warn.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("!", fontSize = 11.sp, color = ZxT.Warn)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text("最敏感的是「${top?.first ?: "—"}」" +
                            (if (top != null) "，摆动 ${"%.1f".format(abs(top.third - top.second))}" else ""),
                        fontSize = 9.sp, color = ZxT.Warn)
                    Text("**资源应该花在缩小这一项的不确定性上**，而不是把下面那些测得更准",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 zx_sens 做<b>因果结论的敏感性</b>（结论多容易被推翻），" +
                 "这个排序的是<b>哪个输入最影响输出</b>。" +
                 "区别是：前者问「我的结论稳不稳」，后者问「<b>我该先搞清楚哪个数</b>」。" +
                 "关键是<b>要排序</b>——不排序的敏感性分析，" +
                 "用户看不出该先去核实哪一个。" +
                 "<b>摆动值全为 0 会被拦</b>（排不出序）。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_breakeven 盈亏平衡 ══════════ */
    @Composable
    fun breakeven(o: JSONObject, ctx: RenderContext) {
        val fixed = o.optDouble("fixed", Double.NaN)
        var price by remember { mutableDoubleStateOf(o.optDouble("price", 0.0)) }
        val varCost = o.optDouble("vc", Double.NaN)
        val cm = price - varCost
        val be = if (cm > 0 && fixed > 0) fixed / cm else Double.NaN
        val maxQ = o.optDouble("maxq", 0.0).let {
            if (it <= 0) (if (be.isNaN()) 100.0 else be * 2) else it }
        var cur by remember { mutableDoubleStateOf(o.optDouble("q", 0.0)) }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "盈亏平衡"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (cm <= 0) "**永远平衡不了**" else if (be.isNaN()) "—"
                     else "临界 ${"%.0f".format(be)}",
                    fontSize = 9.sp, color = if (cm <= 0) ZxT.Err else ZxT.Ok)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(66.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height
                    fun py(v: Double) = H - ((v / (maxQ * cm.coerceAtLeast(1.0)))
                        .coerceIn(-0.2, 1.2) * (H - 6f) * 0.85f + 0.1f * (H - 6f)).toFloat()
                    // 固定成本线
                    drawLine(ZxT.Err, Offset(0f, py(fixed)), Offset(W, py(fixed)), 1.1f)
                    // 收入线
                    drawLine(PAL[0], Offset(0f, py(0.0)), Offset(W, py(maxQ * cm)), 1.8f)
                    // 总成本线
                    drawLine(ZxT.Warn, Offset(0f, py(fixed)), Offset(W, py(fixed + maxQ * varCost)), 1.4f)
                    // 临界点
                    if (!be.isNaN() && be <= maxQ) {
                        val x = (be / maxQ * W).toFloat()
                        drawLine(ZxT.Ok, Offset(x, 0f), Offset(x, H), 1.1f)
                        drawCircle(Color.White, 3.5f, Offset(x, py(fixed)))
                    }
                    // 当前量
                    if (cur > 0) {
                        val x = (cur / maxQ * W).toFloat()
                        drawLine(PAL[4], Offset(x, 0f), Offset(x, H), 1.0f)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Text("青线收入 / 黄线总成本 / 红线固定成本", fontSize = 7.5.sp,
                     color = ZxT.Text3, modifier = Modifier.weight(1f))
                Text("绿线临界", fontSize = 7.5.sp, color = ZxT.Ok)
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("单价", fontSize = 8.5.sp, color = ZxT.Text3,
                     modifier = Modifier.width(34.dp))
                SliderBar(value = price, min = (varCost * 0.6).coerceAtLeast(0.1),
                          max = varCost * 2.2, onValue = { price = it })
                Text("%.0f".format(price), fontSize = 9.sp, color = ZxT.Text,
                     modifier = Modifier.width(30.dp), textAlign = TextAlign.End,
                     fontFamily = ZxT.Mono)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("销量", fontSize = 8.5.sp, color = ZxT.Text3,
                     modifier = Modifier.width(34.dp))
                SliderBar(value = cur, min = 0.0, max = maxQ, onValue = { cur = it })
                Text("%.0f".format(cur), fontSize = 9.sp, color = ZxT.Text,
                     modifier = Modifier.width(30.dp), textAlign = TextAlign.End,
                     fontFamily = ZxT.Mono)
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                listOf("固定成本" to (if (fixed.isNaN()) "—" else "%.0f".format(fixed)),
                       "单位贡献" to (if (cm.isNaN()) "—" else "%.1f".format(cm)),
                       "当前盈亏" to (if (cm.isNaN()) "—" else "%.0f".format(cur * cm - fixed)))
                    .forEach { (k, v) ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(v, fontSize = 9.5.sp,
                             color = if (k == "当前盈亏" && !cm.isNaN())
                                 (if (cur * cm - fixed >= 0) ZxT.Ok else ZxT.Err) else ZxT.Text,
                             fontFamily = ZxT.Mono)
                        Text(k, fontSize = 7.5.sp, color = ZxT.Text3)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (cm <= 0) ZxT.Err.copy(.10f)
                            else if (!be.isNaN() && be > maxQ) ZxT.Warn.copy(.10f)
                            else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (cm <= 0) "✗" else if (!be.isNaN() && be > maxQ) "!" else "✓",
                     fontSize = 11.sp,
                     color = if (cm <= 0) ZxT.Err else if (!be.isNaN() && be > maxQ) ZxT.Warn else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (cm <= 0) "**单价 ${"%.0f".format(price)} ≤ 变动成本 ${"%.0f".format(varCost)}**" +
                            "——**永远盈亏平衡不了**，卖一件亏一件，卖得越多亏得越多"
                         else if (!be.isNaN() && be > maxQ) "临界点 ${"%.0f".format(be)} 超过市场容量 " +
                            "${"%.0f".format(maxQ)}——**这个生意在当前规模下不成立**"
                         else "临界 ${"%.0f".format(be)}，当前 ${"%.0f".format(cur)} " +
                            (if (cur >= be) "已过临界" else "还差 ${"%.0f".format(be - cur)}"),
                        fontSize = 9.sp,
                        color = if (cm <= 0) ZxT.Err else if (!be.isNaN() && be > maxQ) ZxT.Warn else ZxT.Ok)
                    Text("拖单价看临界点怎么移动——**这比看一个静态的盈亏平衡点有用得多**",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 unitecon 算<b>单客回本</b>、budget 给<b>分档条</b>，" +
                 "这个找的是<b>销量意义上的临界点</b>，且<b>临界点要能跟着参数移动</b>。" +
                 "关键是<b>要识别「永远平衡不了」</b>：单价低于变动成本时临界点是无穷大，" +
                 "只显示一个空值，用户会以为是自己没调好。" +
                 "<b>price ≤ vc 会被拦</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_whatif 假设推演 ══════════ */
    @Composable
    fun whatif(o: JSONObject, ctx: RenderContext) {
        val nodes = jarr(o, "nodes")
        var changed by remember { mutableIntStateOf(-1) }
        var delta by remember { mutableFloatStateOf(0f) }
        val baseV = nodes.map { it.optDouble("v", 0.0) }
        val coef = nodes.map { it.optDouble("k", 1.0) }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "假设推演"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (changed < 0) "未改动" else "+${"%.0f%%".format(delta * 100)}",
                    fontSize = 9.sp, color = if (changed < 0) ZxT.Text3 else ZxT.Warn)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("输入", fontSize = 8.5.sp, color = ZxT.Text3,
                     modifier = Modifier.width(34.dp))
                SliderBar(value = delta.toDouble(), min = -0.5, max = 0.5,
                          onValue = { delta = it.toFloat() })
                Text("${if (delta >= 0) "+" else ""}${"%.0f%%".format(delta * 100)}",
                     fontSize = 9.sp, color = ZxT.Text, modifier = Modifier.width(34.dp),
                     textAlign = TextAlign.End, fontFamily = ZxT.Mono)
            }
            Spacer(Modifier.height(3.dp))
            nodes.forEachIndexed { k, nd ->
                val nm = nd.optString("n", "节点${k + 1}")
                // 传播：上游改动沿链累积，每个节点有自己的放大系数
                val up = if (k == 0) delta.toDouble()
                         else (0 until k).fold(delta.toDouble()) { acc, j -> acc * coef[j] }
                val nv = baseV[k] * (1 + up)
                val dv = nv - baseV[k]
                val c = if (k == 0) PAL[0] else if (abs(coef[k]) > 1.2) ZxT.Err else PAL[4]
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (changed == k) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        changed = k
                        ctx.callTool("zx_select", mapOf(
                            "node" to nm, "from" to "%.1f".format(baseV[k]),
                            "to" to "%.1f".format(nv), "coef" to "%.2f".format(coef[k]),
                            "source" to "zx_whatif"))
                    }.padding(5.dp, 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(nm, fontSize = 9.sp, color = ZxT.Text2,
                         modifier = Modifier.width(56.dp), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    Text("×${"%.2f".format(coef[k])}", fontSize = 7.5.sp,
                         color = if (abs(coef[k]) > 1.2) ZxT.Err else ZxT.Text3,
                         modifier = Modifier.width(30.dp))
                    Box(Modifier.weight(1f).height(8.dp)
                        .clip(RoundedCornerShape(3.dp)).background(ZxT.Surface2)) {
                        Box(Modifier.fillMaxWidth((nv / baseV.maxOrNull()!!.coerceAtLeast(1.0))
                            .coerceIn(0.02, 1.5).toFloat()).fillMaxHeight().background(c))
                    }
                    Text("%.1f".format(nv), fontSize = 8.5.sp, color = ZxT.Text,
                         modifier = Modifier.width(38.dp), textAlign = TextAlign.End,
                         fontFamily = ZxT.Mono)
                    Text("${if (dv >= 0) "+" else ""}${"%.1f".format(dv)}", fontSize = 8.sp,
                         color = if (dv >= 0) ZxT.Ok else ZxT.Err,
                         modifier = Modifier.width(32.dp), textAlign = TextAlign.End)
                }
            }
            val amp = coef.fold(1.0) { a, b -> a * b }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (amp > 2.0) ZxT.Err.copy(.10f)
                            else if (amp > 1.3) ZxT.Warn.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (amp > 2.0) "✗" else if (amp > 1.3) "!" else "✓", fontSize = 11.sp,
                     color = if (amp > 2.0) ZxT.Err else if (amp > 1.3) ZxT.Warn else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text("全链路放大 ${"%.2f".format(amp)}×——输入动 ${"%.0f%%".format(delta * 100)}，" +
                            "末端动 ${"%.0f%%".format((amp - 1) * delta * 100)}",
                        fontSize = 9.sp,
                        color = if (amp > 2.0) ZxT.Err else if (amp > 1.3) ZxT.Warn else ZxT.Ok)
                    Text(if (amp > 2.0) "**每一级都在放大**——上游一个 10% 的调整，" +
                        "到末端已经不是 10% 了。这种链路要特别小心调上游"
                        else "放大不算离谱，可以按线性直觉估",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 linkage 做<b>卡片之间的联动</b>、" +
                 "topology 看<b>服务影响面</b>，" +
                 "这个做的是<b>一条数值链路上的假设推演</b>。" +
                 "关键是<b>要给出「全链路放大倍数」</b>——" +
                 "逐节点看变化，用户会以为 10% 就是 10%，" +
                 "而实际上每一级都在乘。" +
                 "<b>链路缺节点会被拦</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_systemdyn 系统动力学 ══════════ */
    @Composable
    fun systemdyn(o: JSONObject, ctx: RenderContext) {
        val s0 = o.optDouble("s0", 0.0)
        val inflow = o.optDouble("in", 0.0)
        val outflow = o.optDouble("out", 0.0)
        val steps = o.optInt("steps", 40)
        val cap = o.optDouble("cap", Double.NaN)
        val series = remember(s0, inflow, outflow, steps, cap) {
            val xs = ArrayList<Double>(); var s = s0
            for (t in 0 until steps) {
                // 流出按占比（一阶负反馈）；有 cap 时流入随接近上限而衰减
                val inf = if (cap.isNaN()) inflow else inflow * (1.0 - (s / cap).coerceIn(0.0, 1.0))
                s = (s + inf - s * outflow).coerceAtLeast(0.0)
                xs.add(s)
            }
            xs
        }
        val mx = series.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
        val steady = if (outflow > 0 && cap.isNaN()) inflow / outflow else Double.NaN

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "系统动力学"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (!steady.isNaN()) "稳态 ${"%.0f".format(steady)}" else "有上限约束",
                    fontSize = 9.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(62.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height
                    drawPath(Path().apply {
                        series.forEachIndexed { i, v ->
                            val x = i.toFloat() / (series.size - 1) * W
                            val y = H - (v / mx * (H - 5f)).toFloat()
                            if (i == 0) moveTo(x, y) else lineTo(x, y)
                        }
                    }, PAL[0], style = Stroke(2f))
                    if (!steady.isNaN() && steady <= mx) {
                        val y = H - (steady / mx * (H - 5f)).toFloat()
                        drawLine(ZxT.Ok, Offset(0f, y), Offset(W, y), 1.1f)
                    }
                    if (!cap.isNaN() && cap <= mx) {
                        val y = H - (cap / mx * (H - 5f)).toFloat()
                        drawLine(ZxT.Err, Offset(0f, y), Offset(W, y), 1.1f)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Text("存量随时间演化", fontSize = 7.5.sp, color = ZxT.Text3,
                     modifier = Modifier.weight(1f))
                if (!steady.isNaN()) Text("绿线 = 稳态", fontSize = 7.5.sp, color = ZxT.Ok)
                else if (!cap.isNaN()) Text("红线 = 上限", fontSize = 7.5.sp, color = ZxT.Err)
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth()) {
                listOf("初始" to "%.0f".format(s0), "流入" to "%.1f".format(inflow),
                       "流出率" to "%.3f".format(outflow),
                       "末期" to "%.0f".format(series.lastOrNull() ?: 0.0))
                    .forEach { (k, v) ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(v, fontSize = 9.5.sp, color = ZxT.Text, fontFamily = ZxT.Mono)
                        Text(k, fontSize = 7.5.sp, color = ZxT.Text3)
                    }
                }
            }
            val rose = (series.lastOrNull() ?: 0.0) > s0
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (!cap.isNaN() && series.any { it >= cap * 0.98 }) ZxT.Warn.copy(.10f)
                            else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (!cap.isNaN() && series.any { it >= cap * 0.98 }) "!" else "✓",
                     fontSize = 11.sp,
                     color = if (!cap.isNaN() && series.any { it >= cap * 0.98 }) ZxT.Warn else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (!cap.isNaN() && series.any { it >= cap * 0.98 })
                            "会撞到上限 ${"%.0f".format(cap)}——**之后流入被压制，系统停在边界而不是停在稳态**"
                         else if (rose) "存量在上升，${steps} 步后到 ${"%.0f".format(series.last())}" +
                            (if (!steady.isNaN()) "，正在趋向稳态 ${"%.0f".format(steady)}" else "")
                         else "存量在下降",
                        fontSize = 9.sp,
                        color = if (!cap.isNaN() && series.any { it >= cap * 0.98 }) ZxT.Warn else ZxT.Ok)
                    Text("系统动力学看的是**长期行为**，不是某一期的数值——" +
                        "短期在涨不代表稳态也在涨",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 forecast 预测<b>未来值</b>、trend 看<b>走向</b>，" +
                 "这个模拟的是<b>存量-流量结构的长期行为</b>。" +
                 "区别是：预测是从数据外推，系统动力学是<b>从机制推演</b>——" +
                 "即使没有历史数据，只要机制对就能推。" +
                 "关键是<b>要给稳态或上限</b>，否则看不出系统会停在哪。" +
                 "<b>流出率导致存量变负会被拦</b>（物理上不可能）。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_markov 马尔可夫链 ══════════ */
    @Composable
    fun markov(o: JSONObject, ctx: RenderContext) {
        val states = o.optJSONArray("states")?.let { a ->
            (0 until a.length()).map { a.optString(it, "状态${it + 1}") } } ?: emptyList()
        val n = states.size
        val rows = jarr(o, "p").map { jdarr(it, "r") }
        var stepN by remember { mutableIntStateOf(o.optInt("at", 1)) }
        var start by remember { mutableIntStateOf(0) }
        fun pow(v: List<Double>, k: Int): List<Double> {
            var cur = v
            repeat(k) {
                cur = (0 until n).map { i ->
                    (0 until n).sumOf { j -> cur[j] * (rows[j].getOrElse(i) { 0.0 }) } }
            }
            return cur
        }
        val init = (0 until n).map { if (it == start) 1.0 else 0.0 }
        val cur = remember(stepN, start) { pow(init, stepN) }
        val steady = remember {
            var v = init; repeat(200) {
                v = (0 until n).map { i ->
                    (0 until n).sumOf { j -> v[j] * (rows[j].getOrElse(i) { 0.0 }) } } }
            v
        }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "马尔可夫链"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("第 $stepN 步", fontSize = 9.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(5.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("步数", fontSize = 8.5.sp, color = ZxT.Text3,
                     modifier = Modifier.width(30.dp))
                SliderBar(value = stepN.toDouble(), min = 0.0, max = 24.0,
                          onValue = { stepN = it.toInt() })
                Text(stepN.toString(), fontSize = 9.sp, color = ZxT.Text,
                     modifier = Modifier.width(24.dp), textAlign = TextAlign.End,
                     fontFamily = ZxT.Mono)
            }
            Spacer(Modifier.height(4.dp))
            cur.forEachIndexed { k, v ->
                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)
                    .clickable {
                        start = k
                        ctx.callTool("zx_select", mapOf(
                            "from" to states[k], "step" to stepN.toString(),
                            "to_dist" to cur.mapIndexed { i, x ->
                                "${states[i]}=${"%.3f".format(x)}" }.joinToString(","),
                            "source" to "zx_markov"))
                    }.padding(4.dp, 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(states[k], fontSize = 9.sp, color = ZxT.Text2,
                         modifier = Modifier.width(52.dp), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    Box(Modifier.weight(1f).height(8.dp)
                        .clip(RoundedCornerShape(3.dp)).background(ZxT.Surface2)) {
                        Box(Modifier.fillMaxWidth(v.toFloat().coerceIn(0f, 1f)).fillMaxHeight()
                            .background(PAL[k % PAL.size]))
                    }
                    Text("%.1f%%".format(v * 100), fontSize = 8.5.sp, color = ZxT.Text,
                         modifier = Modifier.width(40.dp), textAlign = TextAlign.End,
                         fontFamily = ZxT.Mono)
                    Text("稳 ${"%.0f%%".format(steady[k] * 100)}", fontSize = 7.5.sp,
                         color = ZxT.Text3, modifier = Modifier.width(38.dp),
                         textAlign = TextAlign.End)
                }
            }
            val conv = cur.zip(steady).sumOf { abs(it.first - it.second) } < 0.02
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (conv) ZxT.Ok.copy(.10f) else ZxT.Warn.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (conv) "✓" else "!", fontSize = 11.sp,
                     color = if (conv) ZxT.Ok else ZxT.Warn)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (conv) "第 $stepN 步已收敛到稳态——**之后不管再走多少步，" +
                            "分布都不变了**"
                         else "还没收敛，稳态是 ${steady.mapIndexed { i, v ->"${states[i]} ${"%.0f%%".format(v * 100)}" }.joinToString(" / ")}",
                        fontSize = 9.sp, color = if (conv) ZxT.Ok else ZxT.Warn)
                    Text("**稳态比当前分布更重要**——它告诉你系统最终会停在什么状态，" +
                        "短期波动迟早会被它拉回去",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 statelist 列<b>状态清单</b>、flow 画<b>路径</b>，" +
                 "这个算的是<b>带概率的状态转移与稳态</b>。" +
                 "区别是：flow 只有「能走到哪」，这个还有<b>走到的概率和最终停在哪</b>。" +
                 "关键是<b>稳态要和当前分布一起给</b>——只给当前，" +
                 "看不出系统正在往哪走。" +
                 "<b>每行和不为 1 会被拦</b>（不是合法转移矩阵）。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_payoff 博弈支付矩阵 ══════════ */
    @Composable
    fun payoff(o: JSONObject, ctx: RenderContext) {
        val rows = o.optJSONArray("rows")?.let { a ->
            (0 until a.length()).map { a.optString(it, "策略${it + 1}") } } ?: emptyList()
        val cols = o.optJSONArray("cols")?.let { a ->
            (0 until a.length()).map { a.optString(it, "策略${it + 1}") } } ?: emptyList()
        val cells = jarr(o, "cells").map { it.optDouble("a", 0.0) to it.optDouble("b", 0.0) }
        var sel by remember { mutableIntStateOf(-1) }
        // 纳什均衡：每个格子看双方是否有单方改进动机
        val eq = (0 until cells.size).filter { idx ->
            val r = idx / cols.size; val c = idx % cols.size
            val (a, b) = cells[idx]
            val aBest = (0 until rows.size).all { rr ->
                cells[rr * cols.size + c].first <= a }
            val bBest = (0 until cols.size).all { cc ->
                cells[r * cols.size + cc].second <= b }
            aBest && bBest
        }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "博弈支付矩阵"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (eq.isEmpty()) "无纯策略均衡" else "${eq.size} 个均衡",
                    fontSize = 9.sp, color = if (eq.isEmpty()) ZxT.Warn else ZxT.Ok)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.width(54.dp))
                cols.forEach { c ->
                    Text(c, fontSize = 8.sp, color = ZxT.Text3,
                         modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                         maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            rows.forEachIndexed { r, rn ->
                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(rn, fontSize = 8.5.sp, color = ZxT.Text2,
                         modifier = Modifier.width(54.dp), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    cols.forEachIndexed { c, _ ->
                        val idx = r * cols.size + c
                        val (a, b) = cells.getOrElse(idx) { 0.0 to 0.0 }
                        val isEq = idx in eq
                        Box(Modifier.weight(1f).padding(horizontal = 1.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isEq) ZxT.Ok.copy(.14f)
                                        else if (sel == idx) ZxT.Surface2 else ZxT.Surface3)
                            .clickable {
                                sel = idx
                                ctx.callTool("zx_select", mapOf(
                                    "row" to rn, "col" to cols[c],
                                    "payoff" to "%.0f / %.0f".format(a, b),
                                    "equilibrium" to (if (isEq) "yes" else "no"),
                                    "source" to "zx_payoff"))
                            }.padding(3.dp, 4.dp)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally,
                                   modifier = Modifier.fillMaxWidth()) {
                                Text("%.0f".format(a), fontSize = 9.sp, color = PAL[0])
                                Text("%.0f".format(b), fontSize = 9.sp, color = PAL[1])
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (eq.isEmpty()) ZxT.Warn.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (eq.isEmpty()) "!" else "✓", fontSize = 11.sp,
                     color = if (eq.isEmpty()) ZxT.Warn else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (eq.isEmpty()) "**没有纯策略均衡**——任何一方都能单方面改得更好，" +
                            "所以会一直变。这种情况要考虑混合策略，或者说明规则本身不稳定"
                         else "均衡在 ${eq.joinToString(" / ") {"${rows[it / cols.size]} × ${cols[it % cols.size]}" }}" +
                            "——**双方都没有单独改变的动机，会停在这里**",
                        fontSize = 9.sp, color = if (eq.isEmpty()) ZxT.Warn else ZxT.Ok)
                    Text("博弈矩阵要看的不是「最大收益」，是<b>双方都动不了的那个格子</b>——" +
                        "收益最高的格子往往到不了，因为对方会让步",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 decision 做<b>单人多方案打分</b>、" +
                 "zx_ab 看<b>两个方案谁好</b>，" +
                 "这个处理的是<b>结果取决于双方选择</b>的情形。" +
                 "区别是：decision 里「我选 A」的结果是固定的，这里「我选 A」的结果" +
                 "<b>还取决于对方选什么</b>。" +
                 "关键是<b>要标出均衡而不是最大收益</b>——最大收益往往拿不到。" +
                 "<b>非 2x2 会被拦</b>；<b>支付数不齐会被拦</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_reversesolve 反向求解 ══════════ */
    @Composable
    fun reversesolve(o: JSONObject, ctx: RenderContext) {
        val target = o.optDouble("target", Double.NaN)
        val a = o.optDouble("a", 1.0)      // 斜率
        val b = o.optDouble("b", 0.0)      // 截距
        val lo = o.optDouble("lo", 0.0)
        val hi = o.optDouble("hi", 100.0)
        val x = (target - b) / a
        val reachable = x >= lo && x <= hi
        val clamped = x.coerceIn(lo, hi)
        val yAtHi = a * hi + b
        val mx = max(abs(yAtHi), abs(target)).coerceAtLeast(1.0)
        val mn = min(a * lo + b, 0.0)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "反向求解"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (reachable) "需 ${"%.1f".format(x)}" else "**够不着**",
                    fontSize = 9.sp, color = if (reachable) ZxT.Ok else ZxT.Err)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(62.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height
                    fun px(v: Double) = ((v - lo) / (hi - lo) * W).toFloat()
                    fun py(v: Double) = H - (((v - mn) / (mx - mn)) * (H - 6f)).toFloat()
                    // 可行域
                    drawRect(PAL[0].copy(alpha = .10f), Offset(px(lo), 0f),
                        androidx.compose.ui.geometry.Size(px(hi) - px(lo), H))
                    // 函数线（只画可行域内）
                    drawLine(PAL[0], Offset(px(lo), py(a * lo + b)),
                        Offset(px(hi), py(a * hi + b)), 2f)
                    // 目标线
                    drawLine(ZxT.Err, Offset(0f, py(target)), Offset(W, py(target)), 1.3f)
                    // 解
                    if (reachable) {
                        drawCircle(Color.White, 3.5f, Offset(px(x), py(target)))
                        drawLine(ZxT.Ok, Offset(px(x), 0f), Offset(px(x), H), 1.1f)
                    } else {
                        // 够不着：标出上限处能到多少
                        drawLine(ZxT.Warn, Offset(px(hi), 0f), Offset(px(hi), H), 1.1f)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Text("蓝线 = 转换关系 / 红线 = 目标", fontSize = 7.5.sp,
                     color = ZxT.Text3, modifier = Modifier.weight(1f))
                Text(if (reachable) "绿线 = 解" else "黄线 = 可行上限", fontSize = 7.5.sp,
                     color = if (reachable) ZxT.Ok else ZxT.Warn)
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth()) {
                listOf("目标" to "%.0f".format(target),
                       "需投入" to (if (reachable) "%.1f".format(x) else "≥${"%.1f".format(x)}"),
                       "可行上限" to "%.1f".format(hi),
                       "上限能到" to "%.0f".format(yAtHi))
                    .forEach { (k, v) ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(v, fontSize = 9.5.sp,
                             color = if (k == "需投入" && !reachable) ZxT.Err else ZxT.Text,
                             fontFamily = ZxT.Mono)
                        Text(k, fontSize = 7.5.sp, color = ZxT.Text3)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (!reachable) ZxT.Err.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (!reachable) "✗" else "✓", fontSize = 11.sp,
                     color = if (!reachable) ZxT.Err else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (!reachable) "**目标 ${"%.0f".format(target)} 够不着**——" +
                            "需要投入 ${"%.1f".format(x)}，但可行上限是 ${"%.1f".format(hi)}，" +
                            "最多只能到 ${"%.0f".format(yAtHi)}（差 ${"%.0f".format(target - yAtHi)}）。" +
                            "**要么降目标，要么先扩大可行域**"
                         else "投入 ${"%.1f".format(x)} 即可达到 ${"%.0f".format(target)}",
                        fontSize = 9.sp, color = if (!reachable) ZxT.Err else ZxT.Ok)
                    Text("反向求解最容易犯的错是<b>不说明无解</b>——" +
                        "给一个够不着的输入让人白努力，比直接说做不到更糟糕",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 optim 做<b>正向分配</b>、capacity 算<b>上限</b>，" +
                 "这个做的是<b>从目标反推输入</b>。" +
                 "区别是：分配问「有这么多资源怎么分」，反向求解问「<b>要拿到这个结果，" +
                 "得先有多少资源</b>」。关键是<b>必须明说无解</b>，" +
                 "并且指出差多少、以及可行上限能到多少。" +
                 "<b>目标不在值域内会被拦</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_stress 压力情景 ══════════ */
    @Composable
    fun stress(o: JSONObject, ctx: RenderContext) {
        val items = jarr(o, "items")
        var sel by remember { mutableIntStateOf(-1) }
        val fail = items.count { it.optString("s") == "fail" }
        val warn = items.count { it.optString("s") == "warn" }
        val firstFail = items.indexOfFirst { it.optString("s") == "fail" }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "压力测试"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (fail == 0) "全部存活" else "$fail 项未通过",
                    fontSize = 9.sp, color = if (fail == 0) ZxT.Warn else ZxT.Err)
            }
            Spacer(Modifier.height(6.dp))
            items.forEachIndexed { k, it ->
                val nm = it.optString("n", "情景${k + 1}")
                val s = it.optString("s", "pass")
                val load = it.optDouble("load", 0.0)
                val c = if (s == "fail") ZxT.Err else if (s == "warn") ZxT.Warn else ZxT.Ok
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (sel == k) ZxT.Surface2
                                else if (s == "fail") ZxT.Err.copy(.07f) else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_select", mapOf(
                            "scenario" to nm, "load" to "%.2f".format(load), "result" to s,
                            "source" to "zx_stress"))
                    }.padding(5.dp, 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(when (s) { "pass" -> "✓"; "warn" -> "!"; else -> "✕" },
                        fontSize = 9.sp, color = c, modifier = Modifier.width(12.dp))
                    Text(nm, fontSize = 9.sp, color = ZxT.Text2,
                         modifier = Modifier.weight(1f), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    Text("${"%.1f".format(load)}×", fontSize = 8.5.sp, color = ZxT.Text,
                         modifier = Modifier.width(30.dp), textAlign = TextAlign.End,
                         fontFamily = ZxT.Mono)
                    Text(when (s) { "pass" -> "存活"; "warn" -> "降级"; else -> "**失败**" },
                        fontSize = 8.sp, color = c,
                        modifier = Modifier.width(36.dp), textAlign = TextAlign.End)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (fail == 0) ZxT.Warn.copy(.10f) else ZxT.Err.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (fail == 0) "!" else "✗", fontSize = 11.sp,
                     color = if (fail == 0) ZxT.Warn else ZxT.Err)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (fail == 0) "**全部存活** —— 这说明<b>压得不够</b>。" +
                            "压力测试的目的是<b>找到边界在哪</b>，全部通过等于没测出边界，" +
                            "不知道系统到底能扛多少"
                         else if (firstFail >= 0) "第一个失败在「${items[firstFail].optString("n")}」" +
                            "（${"%.1f".format(items[firstFail].optDouble("load"))}×）——" +
                            "**这就是边界**，真实容量要按它打个折"
                         else "",
                        fontSize = 9.sp, color = if (fail == 0) ZxT.Warn else ZxT.Err)
                    Text("全部通过的压力测试是最危险的结论——它会让人以为「随便压都行」",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 loadtest 找<b>吞吐-延迟拐点</b>、canary 看<b>灰度健康度</b>，" +
                 "这个做的是<b>多个极端情景并排</b>。" +
                 "区别是：loadtest 连续加压找拐点，这个一次摆出几个" +
                 "离散的极端条件（断电 / 三倍流量 / 依赖全挂）。" +
                 "关键是<b>全通过要报出来</b>——那是「没测出边界」而不是「很强」。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_realoption 实物期权 ══════════ */
    @Composable
    fun realoption(o: JSONObject, ctx: RenderContext) {
        val now = o.optDouble("now", 0.0)      // 现在做的净现值
        val later = o.optDouble("later", 0.0)  // 等一等再做（信息更充分时）
        val sigma = o.optDouble("sigma", 0.3)  // 不确定性
        val waitCost = o.optDouble("cost", 0.0) // 等待的成本
        // 等待的价值 ≈ 不确定性带来的上行空间
        val option = later * sigma * 0.5
        val waitValue = later + option - waitCost
        val chooseWait = waitValue > now
        val gap = abs(waitValue - now)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "实物期权"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (chooseWait) "**建议等**" else "建议现在做", fontSize = 9.sp,
                     color = if (chooseWait) ZxT.Warn else ZxT.Ok)
            }
            Spacer(Modifier.height(6.dp))
            listOf(
                "现在做" to now to PAL[0],
                "等一等" to (later - waitCost) to PAL[4],
                "+ 期权价值" to waitValue to (if (chooseWait) ZxT.Warn else ZxT.Ok)
            ).forEach { e ->
                val nm = e.first.first; val v = e.first.second; val c = e.second
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(nm, fontSize = 9.sp, color = ZxT.Text2,
                         modifier = Modifier.width(64.dp))
                    Box(Modifier.weight(1f).height(9.dp)
                        .clip(RoundedCornerShape(4.dp)).background(ZxT.Surface2)) {
                        Box(Modifier.fillMaxWidth((v / maxOf(now, waitValue, later, 1.0))
                            .toFloat().coerceIn(0.02f, 1f)).fillMaxHeight().background(c))
                    }
                    Text("%.1f".format(v), fontSize = 9.sp, color = ZxT.Text,
                         modifier = Modifier.width(38.dp), textAlign = TextAlign.End,
                         fontFamily = ZxT.Mono)
                }
            }
            Spacer(Modifier.height(3.dp))
            Row(Modifier.fillMaxWidth()) {
                listOf("现在做" to "%.1f".format(now), "等待成本" to "%.1f".format(waitCost),
                       "不确定性 σ" to "%.2f".format(sigma),
                       "期权价值" to "+%.1f".format(option))
                    .forEach { (k, v) ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(v, fontSize = 9.5.sp, color = ZxT.Text, fontFamily = ZxT.Mono)
                        Text(k, fontSize = 7.5.sp, color = ZxT.Text3)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (chooseWait) ZxT.Warn.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (chooseWait) "!" else "✓", fontSize = 11.sp,
                     color = if (chooseWait) ZxT.Warn else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (chooseWait) "等待的期望 ${"%.1f".format(waitValue)} > " +
                            "现在做 ${"%.1f".format(now)}（高 ${"%.1f".format(gap)}）——" +
                            "**不确定性本身就是等待的价值**"
                         else "现在做 ${"%.1f".format(now)} ≥ 等待 ${"%.1f".format(waitValue)}，" +
                            "先动手",
                        fontSize = 9.sp, color = if (chooseWait) ZxT.Warn else ZxT.Ok)
                    Text("传统 NPV 只算「现在做的价值」，会把<b>等待的价值算成 0</b>——" +
                        "于是永远建议立刻行动，而这在高不确定时是错的",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 decision 做<b>方案打分</b>、regret 看<b>事后遗憾</b>，" +
                 "这个处理的是<b>「什么时候做」本身也是个决策</b>。" +
                 "区别是：decision 假设必须现在选，这个把<b>「再等等」也当作一个选项</b>。" +
                 "关键是<b>σ 越大等待越值钱</b>——高不确定时抢跑往往是错的。" +
                 "<b>σ 非正会被拦</b>（期权价值算不出来）。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_optpath 最优路径 ══════════ */
    @Composable
    fun optpath(o: JSONObject, ctx: RenderContext) {
        val nodes = jarr(o, "nodes").map { it.optString("n", "节点") }
        val n = nodes.size
        val edges = jarr(o, "edges").map {
            Triple(it.optInt("f", -1), it.optInt("t", -1), it.optDouble("w", 1.0)) }
        val src = o.optInt("src", 0)
        val dst = o.optInt("dst", n - 1)
        // Dijkstra
        val dist = remember(src, dst, n) {
            val d = DoubleArray(n) { Double.POSITIVE_INFINITY }
            val prev = IntArray(n) { -1 }
            val done = BooleanArray(n)
            d[src] = 0.0
            repeat(n) {
                val u = (0 until n).filter { !done[it] }
                    .minByOrNull { d[it] } ?: return@repeat
                if (d[u].isInfinite()) return@repeat
                done[u] = true
                edges.filter { it.first == u }.forEach { (_, v, w) ->
                    if (v in 0 until n && d[u] + w < d[v]) { d[v] = d[u] + w; prev[v] = u }
                }
            }
            d to prev
        }
        val path = remember(dist) {
            val (_, prev) = dist
            val p = ArrayList<Int>(); var c = dst
            while (c >= 0) { p.add(0, c); if (c == src) break; c = prev[c] }
            if (p.firstOrNull() != src) emptyList<Int>() else p
        }
        val reachable = path.isNotEmpty() && path.last() == dst

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "最优路径"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (!reachable) "**不可达**" else "代价 ${"%.1f".format(dist.first[dst])}",
                    fontSize = 9.sp, color = if (!reachable) ZxT.Err else ZxT.Ok)
            }
            Spacer(Modifier.height(6.dp))
            // 网格布局展示节点
            val cols = 4
            (0 until n step cols).forEach { r0 ->
                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                    (r0 until minOf(r0 + cols, n)).forEach { k ->
                        val onPath = k in path
                        val isEnd = k == src || k == dst
                        Box(Modifier.weight(1f).padding(horizontal = 1.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(when {
                                isEnd -> PAL[0].copy(.30f)
                                onPath -> ZxT.Ok.copy(.16f)
                                else -> ZxT.Surface3 })
                            .clickable {
                                ctx.callTool("zx_select", mapOf(
                                    "node" to nodes[k],
                                    "dist" to (if (dist.first[k].isInfinite()) "∞"
                                               else "%.1f".format(dist.first[k])),
                                    "on_path" to (if (onPath) "yes" else "no"),
                                    "source" to "zx_optpath"))
                            }.padding(3.dp, 4.dp)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally,
                                   modifier = Modifier.fillMaxWidth()) {
                                Text(nodes[k], fontSize = 8.sp, color = ZxT.Text,
                                     maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (dist.first[k].isInfinite()) "∞"
                                     else "%.1f".format(dist.first[k]),
                                    fontSize = 7.5.sp,
                                    color = if (onPath) ZxT.Ok else ZxT.Text3,
                                    fontFamily = ZxT.Mono)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            if (reachable)
                Text("路径：${path.joinToString(" → ") { nodes[it] }}", fontSize = 9.sp,
                     color = ZxT.Text2, lineHeight = 13.sp)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (!reachable) ZxT.Err.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (!reachable) "✗" else "✓", fontSize = 11.sp,
                     color = if (!reachable) ZxT.Err else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (!reachable) "**从 ${nodes[src]} 到 ${nodes[dst]} 不可达**——" +
                            "图不连通。这种情况比「代价很高」严重得多：" +
                            "再怎么优化都没用，得先补一条边"
                         else "最短代价 ${"%.1f".format(dist.first[dst])}，" +
                            "经过 ${path.size} 个节点",
                        fontSize = 9.sp, color = if (!reachable) ZxT.Err else ZxT.Ok)
                    Text("**不可达和代价高是两回事**——不可达要先补边，代价高才是优化问题。" +
                        "混在一起会让人去优化一条根本走不通的路",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 flow 画<b>流程路径</b>、graph 展示<b>依赖关系</b>，" +
                 "这个做的是<b>带代价的最优路径搜索</b>。" +
                 "区别是：flow 只展示「有这条路」，这个回答「<b>哪条最省</b>」。" +
                 "关键是<b>要区分「不可达」和「代价高」</b>——" +
                 "不可达需要先补边，代价高才是优化问题。" +
                 "<b>起点终点不存在会被拦</b>；<b>不连通会被拦</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/**
 * 第三十一批多个组件共用的轻量滑条。
 *
 * 气泡里不用完整 Slider 有两个原因：
 *   一、Slider 默认吃掉横向拖动手势，和外层 LazyColumn 的滚动冲突（见 ZxGesture）
 *   二、Slider 的 thumb 在 330dp 宽的气泡里占的比例太大
 *
 * 所以这里只做「点按即定位」——不做拖动。
 * 这看起来是退步，但在气泡里反而更准：手指落点即取值，不用先按住再拖。
 */
@Composable
internal fun RowScope.SliderBar(
    value: Double, min: Double, max: Double, onValue: (Double) -> Unit
) {
    val span = (max - min).let { if (it <= 0) 1.0 else it }
    BoxWithConstraints(Modifier.weight(1f).height(22.dp)
        .clip(RoundedCornerShape(4.dp))
        .background(ZxT.Surface2)) {
        val wPx = with(androidx.compose.ui.platform.LocalDensity.current) {
            maxWidth.toPx().let { if (it <= 0f) 1f else it } }
        Box(Modifier.fillMaxWidth(((value - min) / span).toFloat().coerceIn(0f, 1f))
            .fillMaxHeight().background(ZxT.Primary.copy(alpha = .30f)))
        Box(Modifier.fillMaxSize()
            .pointerInput(min, max) {
                detectTapGestures { off ->
                    onValue((min + (off.x / wPx).toDouble().coerceIn(0.0, 1.0) * span)
                        .coerceIn(min, max))
                }
            })
    }
}
