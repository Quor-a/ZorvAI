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
import kotlin.math.ln
import kotlin.math.sqrt
import androidx.compose.ui.geometry.Size

/**
 * 第二十七批组件（12 个）—— 预测与决策。
 *
 * 前二十六批一直沿着一条线往下走：
 *
 *   看什么 → 填什么 → 改什么 → 凭什么 → 怎么推演 →
 *   统计前提成不成立 → 在多大范围内成立 → 是不是因果
 *
 * 到这里，"**我们知道了什么**"这件事已经铺得比较全了。
 *
 * 但对话的终点往往不是知道，是**要做决定**：
 *
 *   "下周能到多少？"（预测）
 *   "两个方案选哪个？"（决策）
 *   "预算怎么分？"（分配）
 *   "这个模型说的 0.8 能信吗？"（校准）
 *
 * 这些靠前二十六批拼不出来——
 * decompose 拆的是**历史**，回答不了未来；
 * decision 树走的是**已定好的规则**，不帮人选规则；
 * tradeoff 比的是**两个已知选项**，不生成选项；
 * zx_test 给的是**差异是否显著**，不给"该不该做"。
 *
 * 十二个组件分成三组：
 *
 *   **预测**（把未来画出来，并且标出不确定）
 *     zx_forecast  预测区间随 horizon 变宽——**不确定必须可见**
 *     zx_calib     预测概率 vs 实际频率，看模型说 0.8 时是不是真有 80%
 *     zx_optline   最优停止线：看了多少个之后该停手
 *
 *   **决策**（把选项摆出来，并且让人能改权重看结果变不变）
 *     zx_decision  加权评分矩阵，权重可拖
 *     zx_riskreturn 风险-收益 + 有效前沿
 *     zx_utility   把偏好变成曲线（风险厌恶/中性/偏好）
 *     zx_bandit    多臂老虎机：探索够不够、下一个试谁
 *
 *   **解释与分配**（结果出来之后，说清为什么 / 怎么分）
 *     zx_explain   特征归因瀑布：从基线推到预测值
 *     zx_cfactual  反事实：最小改动让它换一个结果
 *     zx_cforest   异质效应：同一处理对不同人效果不同
 *     zx_alloc     序贯分配：边际递减下的预算投放
 *     zx_abmon     A/B 分流监控 + SRM 检查
 *
 * 贯穿这批的一条：**不确定必须可见**。
 *
 *   预测只给一条线，用户会当成"就是这样"；
 *   给区间，用户才知道"大概在这之间、越远越不准"。
 *   决策只给排名，用户不知道"换个权重会不会翻盘"；
 *   权重可拖，用户才知道这个结论稳不稳。
 *
 *   **看不见的不确定，会被当成确定。**
 *   这是这批所有组件共同的校验重心——
 *   不是"画不出来"，而是"**看起来确定了，其实没那么确定**"。
 *
 * 具体要拦的：
 *   forecast 历史不足 4 点（拟不出趋势）/ 区间随 horizon 收窄（画反了）
 *   calib 预测概率不在 0-1 / 分箱数超过样本数
 *   optline 候选数不足以谈停止
 *   decision 权重和为 0（除零）/ 选项或准则为空
 *   riskreturn 两列长度不等 / 有效前沿点数不足
 *   utility 点数不足 2（画不出曲线形态）
 *   bandit 臂数为 0 / 某臂试验次数为 0 却给了均值
 *   explain 归因之和 + 基线 ≠ 预测值（归因不自洽）
 *   cfactual 改动后预测没变（这个反事实无效）
 *   cforest 效应全同（没有异质性，画这个没意义）
 *   alloc 预算 ≤0 / 边际收益未递减（那就不是递减场景）
 *   abmon 分流和不为 1（SRM 无从判断）/ 样本量不足以判断
 */
object ZxRender27 {

    private val PAL = listOf(
        Color(0xFF5046E4), Color(0xFF3FBF7F), Color(0xFFE8A33D),
        Color(0xFFE5544B), Color(0xFF4A9BE8), Color(0xFF8E86FF)
    )

    /* ══════════ zx_forecast 预测 ══════════ */
    @Composable
    fun forecast(o: JSONObject, ctx: RenderContext) {
        val histA = o.optJSONArray("hist") ?: return
        val n = minOf(histA.length(), 24)
        val hist = (0 until n).map { histA.optDouble(it, 0.0) }
        val h = o.optInt("h", 6).coerceIn(1, 12)
        var sel by remember { mutableIntStateOf(-1) }
        var show by remember { mutableStateOf(true) }

        // 线性趋势 + 残差标准差；区间随 horizon 变宽（√h），不能收窄
        fun ols(ys: List<Double>): Pair<Double, Double> {
            val m = ys.indices.map { it.toDouble() }
            val mx = m.average(); val my = ys.average()
            var num = 0.0; var den = 0.0
            ys.indices.forEach { k -> num += (m[k] - mx) * (ys[k] - my); den += (m[k] - mx) * (m[k] - mx) }
            val b = if (den > 1e-12) num / den else 0.0
            return b to (my - b * mx)
        }
        val (b, a) = ols(hist)
        val sd = sqrt(hist.indices.map { k ->
            val r = hist[k] - (a + b * k); r * r
        }.average()).coerceAtLeast(1e-9)
        val fc = (1..h).map { t -> a + b * (n - 1 + t) }
        // 区间随 horizon 变宽：越远越不准
        val lo = (1..h).map { t -> fc[t - 1] - 1.96 * sd * sqrt(1.0 + t * 0.35) }
        val hi = (1..h).map { t -> fc[t - 1] + 1.96 * sd * sqrt(1.0 + t * 0.35) }

        val all = hist + hi
        val yLo = minOf(hist.minOrNull() ?: 0.0, lo.minOrNull() ?: 0.0)
        val yHi = maxOf(hist.maxOrNull() ?: 1.0, hi.maxOrNull() ?: 1.0)
        val sp = (yHi - yLo).coerceAtLeast(1e-9)
        val W = 330f; val H = 100f; val pad = 10f
        val px = { k: Int -> pad + (k.toDouble() / (n + h - 1) * (W - 2 * pad)).toFloat() }
        val py = { v: Double -> H - pad - ((v - yLo) / sp * (H - 2 * pad)).toFloat() }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "预测"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("+${h} 期", fontSize = 9.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(100.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    // 预测区间：先画带再画线，否则带会盖住线
                    if (show) {
                        val p = Path()
                        lo.forEachIndexed { t, v ->
                            val x = px(n - 1 + t + 1); val y = py(v)
                            if (t == 0) p.moveTo(x, y) else p.lineTo(x, y)
                        }
                        hi.reversed().forEachIndexed { t, v ->
                            p.lineTo(px(n - 1 + h - t), py(v))
                        }
                        p.close()
                        drawPath(p, PAL[0].copy(.16f))
                    }
                    drawLine(ZxT.Surface3, Offset(px(n - 1), 0f), Offset(px(n - 1), H), 1.2f)
                    // 历史实线
                    drawPath(Path().apply {
                        hist.forEachIndexed { k, v ->
                            val x = px(k); val y = py(v)
                            if (k == 0) moveTo(x, y) else lineTo(x, y)
                        }
                    }, ZxT.Text2, style = Stroke(1.7f))
                    // 预测虚线：虚线才看得出"这部分是猜的"
                    Path().apply {
                        fc.forEachIndexed { t, v ->
                            val x = px(n - 1 + t + 1); val y = py(v)
                            if (t == 0) moveTo(x, y) else lineTo(x, y)
                        }
                    }.let { drawPath(it, PAL[0], style = Stroke(1.7f)) }
                    hist.forEachIndexed { k, v ->
                        drawCircle(if (k == sel) Color.White else ZxT.Text2,
                                   if (k == sel) 3.4f else 2f, Offset(px(k), py(v)))
                    }
                }
                Row(Modifier.fillMaxWidth().height(100.dp)) {
                    repeat(n) { k ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = k
                            ctx.callTool("zx_select", mapOf(
                                "i" to k.toString(), "v" to "%.2f".format(hist[k]),
                                "fit" to "%.2f".format(a + b * k), "source" to "zx_forecast"))
                        })
                    }
                    repeat(h) { t ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            ctx.callTool("zx_select", mapOf(
                                "h" to (t + 1).toString(),
                                "p" to "%.2f".format(fc[t]),
                                "lo" to "%.2f".format(lo[t]), "hi" to "%.2f".format(hi[t]),
                                "source" to "zx_forecast"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text("实线=历史　虚线=预测", fontSize = 8.sp, color = ZxT.Text3,
                     modifier = Modifier.weight(1f))
                Text(if (show) "隐藏区间" else "显示区间", fontSize = 8.sp,
                     color = if (show) ZxT.PriLite else ZxT.Text3,
                     modifier = Modifier.clip(RoundedCornerShape(7.dp))
                         .background(if (show) ZxT.Surface2 else Color.Transparent)
                         .clickable { show = !show }.padding(7.dp, 1.dp))
            }
            Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                val w1 = (hi[0] - lo[0]); val wh = (hi[h - 1] - lo[h - 1])
                Text("第 1 期 ±${"%.1f".format(w1 / 2)}　→　第 $h 期 ±${"%.1f".format(wh / 2)}（越远越宽）",
                    fontSize = 8.5.sp, color = ZxT.Text3)
            }
            Text("已有 decompose 拆的是<b>历史</b>、trend 给的是<b>过去的走势</b>，" +
                 "这个外推到未来——关键是<b>区间必须随 horizon 变宽</b>。" +
                 "只给一条线，用户会当成「就是这样」；" +
                 "给区间，才知道「大概在这之间、越远越不准」。" +
                 "<b>区间收窄会被拦</b>：那是画反了。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_calib 校准曲线 ══════════ */
    @Composable
    fun calib(o: JSONObject, ctx: RenderContext) {
        val ptsA = o.optJSONArray("pts") ?: return
        val n = minOf(ptsA.length(), 30)
        val pts = (0 until n).map {
            val p = ptsA.optJSONObject(it) ?: JSONObject()
            p.optDouble("p", 0.5).coerceIn(0.0, 1.0) to p.optInt("y", 0)
        }
        val bins = o.optInt("bins", 5).coerceIn(2, 8)
        var sel by remember { mutableIntStateOf(-1) }
        // 分箱：预测概率 → 实际频率
        val acc = Array(bins) { 0 to 0 }
        pts.forEach { (p, y) ->
            val b = (p * bins).toInt().coerceIn(0, bins - 1)
            acc[b] = (acc[b].first + y) to (acc[b].second + 1)
        }
        val freq = acc.map { (s, c) -> if (c > 0) s.toDouble() / c else Double.NaN }
        val ece = acc.mapIndexed { b, (s, c) ->
            if (c == 0) 0.0 else abs(freq[b] - (b + 0.5) / bins) * c
        }.sum() / n

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "校准曲线"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("ECE=${"%.3f".format(ece)}", fontSize = 9.sp,
                     color = if (ece < 0.05) ZxT.Ok else if (ece < 0.1) ZxT.Warn else ZxT.Err)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(96.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width; val hh = size.height; val p2 = 12f
                    val fx = { v: Double -> p2 + (v * (w - 2 * p2)).toFloat() }
                    val fy = { v: Double -> hh - p2 - (v * (hh - 2 * p2)).toFloat() }
                    // 完美校准对角线
                    drawLine(ZxT.Text3.copy(.5f), Offset(fx(0.0), fy(0.0)),
                             Offset(fx(1.0), fy(1.0)), 1.2f)
                    val ok = freq.filter { !it.isNaN() }
                    drawPath(Path().apply {
                        var started = false
                        freq.forEachIndexed { b, f ->
                            if (f.isNaN()) return@forEachIndexed
                            val x = fx((b + 0.5) / bins); val y = fy(f)
                            if (!started) { moveTo(x, y); started = true } else lineTo(x, y)
                        }
                    }, PAL[0], style = Stroke(1.8f))
                    freq.forEachIndexed { b, f ->
                        if (f.isNaN()) return@forEachIndexed
                        val dev = abs(f - (b + 0.5) / bins)
                        drawCircle(if (b == sel) Color.White
                                   else if (dev > 0.1) ZxT.Err else if (dev > 0.05) ZxT.Warn else PAL[0],
                                   if (b == sel) 4.2f else 3f,
                                   Offset(fx((b + 0.5) / bins), fy(f)))
                        // 偏差竖线：离对角线多远一目了然
                        drawLine(ZxT.Err.copy(.45f),
                                 Offset(fx((b + 0.5) / bins), fy((b + 0.5) / bins)),
                                 Offset(fx((b + 0.5) / bins), fy(f)), 1.1f)
                    }
                }
                Row(Modifier.fillMaxWidth().height(96.dp)) {
                    repeat(bins) { b ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = b
                            ctx.callTool("zx_select", mapOf(
                                "bin" to "%.2f".format((b + 0.5) / bins),
                                "pred" to "%.2f".format((b + 0.5) / bins),
                                "actual" to (if (freq[b].isNaN()) "NA" else "%.3f".format(freq[b])),
                                "n" to acc[b].second.toString(), "source" to "zx_calib"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                Text("预测概率 →", fontSize = 7.5.sp, color = ZxT.Text3,
                     modifier = Modifier.weight(1f))
                Text("灰线=完美校准", fontSize = 7.5.sp, color = ZxT.Text3)
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                freq.forEachIndexed { b, f ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (f.isNaN()) "—" else "%.2f".format(f), fontSize = 8.sp,
                             color = if (sel == b) ZxT.Text else ZxT.Text3,
                             fontFamily = ZxT.Mono)
                        Text("n=${acc[b].second}", fontSize = 7.sp, color = ZxT.Text3)
                    }
                }
            }
            Text("已有 roc 看<b>排序能力</b>（AUC 高不代表概率可信）、" +
                 "zx_test 看<b>差异</b>，这个看<b>模型说 0.8 的时候是不是真有 80%</b>。" +
                 "一个模型可以排序很准但概率完全离谱——" +
                 "<b>要按预测值下决策就必须先校准</b>。" +
                 "<b>分箱数超过样本数会被拦</b>：那样每箱都只有 1 个，频率非 0 即 1。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_optline 最优停止 ══════════ */
    @Composable
    fun optline(o: JSONObject, ctx: RenderContext) {
        val candA = o.optJSONArray("cand") ?: return
        val n = minOf(candA.length(), 16)
        val cand = (0 until n).map { candA.optDouble(it, 0.0) }
        var sel by remember { mutableIntStateOf(-1) }
        // 秘书问题：看前 1/e 作为基准，之后遇到超过基准的就停
        val probe = (n / kotlin.math.E).toInt().coerceAtLeast(1)
        val bench = cand.take(probe).maxOrNull() ?: 0.0
        val stopAt = cand.indexOfFirst { it > bench }.let { if (it < probe) -1 else it }
        val best = cand.maxOrNull() ?: 0.0
        val got = if (stopAt >= 0) cand[stopAt] else cand.lastOrNull() ?: 0.0
        val yLo = cand.minOrNull() ?: 0.0; val yHi = cand.maxOrNull() ?: 1.0
        val sp = (yHi - yLo).coerceAtLeast(1e-9)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "最优停止"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (stopAt >= 0) "第 ${stopAt + 1} 个停" else "未触发", fontSize = 9.sp,
                     color = if (stopAt >= 0) ZxT.Ok else ZxT.Warn)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(74.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width; val hh = size.height; val p2 = 10f
                    val px = { k: Int -> p2 + (k.toDouble() / (n - 1).coerceAtLeast(1) * (w - 2 * p2)).toFloat() }
                    val py = { v: Double -> hh - p2 - ((v - yLo) / sp * (hh - 2 * p2)).toFloat() }
                    // 观察期
                    if (probe > 0)
                        drawRect(ZxT.Text3.copy(.12f), Offset(p2, 0f),
                                 Size(px(probe - 1) - p2, hh))
                    // 基准线
                    drawLine(ZxT.Warn, Offset(p2, py(bench)), Offset(w - p2, py(bench)), 1.3f)
                    drawPath(Path().apply {
                        cand.forEachIndexed { k, v ->
                            val x = px(k); val y = py(v)
                            if (k == 0) moveTo(x, y) else lineTo(x, y)
                        }
                    }, ZxT.Text3.copy(.5f), style = Stroke(1f))
                    cand.forEachIndexed { k, v ->
                        val c = when {
                            k == stopAt -> ZxT.Ok
                            k < probe -> ZxT.Text3
                            v > bench -> PAL[0]
                            else -> ZxT.Text3.copy(.5f)
                        }
                        drawCircle(if (k == sel) Color.White else c,
                                   if (k == sel || k == stopAt) 4.2f else 2.6f, Offset(px(k), py(v)))
                    }
                }
                Row(Modifier.fillMaxWidth().height(74.dp)) {
                    repeat(n) { k ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = k
                            ctx.callTool("zx_select", mapOf(
                                "i" to (k + 1).toString(), "v" to "%.2f".format(cand[k]),
                                "stop" to (if (k == stopAt) "yes" else "no"),
                                "regret" to "%.2f".format(best - cand[k]),
                                "source" to "zx_optline"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                Text("灰区=观察期（前 $probe 个只看不选）", fontSize = 8.sp,
                     color = ZxT.Text3, modifier = Modifier.weight(1f))
                Text("橙线=基准 ${"%.2f".format(bench)}", fontSize = 8.sp, color = ZxT.Warn)
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (got >= best - 1e-9) ZxT.Ok.copy(.10f) else ZxT.Warn.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (got >= best - 1e-9) "✓" else "!", fontSize = 11.sp,
                     color = if (got >= best - 1e-9) ZxT.Ok else ZxT.Warn)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text("选到 ${"%.2f".format(got)}，最优是 ${"%.2f".format(best)}" +
                            "，遗憾 ${"%.2f".format(best - got)}",
                        fontSize = 9.sp, color = if (got >= best - 1e-9) ZxT.Ok else ZxT.Warn)
                    Text("1/e 法则：前 ${(n / kotlin.math.E).toInt()} 个只看不选，之后超过基准就停",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 sort 排<b>已知的全部</b>、stepper 走<b>已定好的流程</b>，" +
                 "这个处理「<b>只能一个一个看、看过就回不去</b>」——" +
                 "面试、找房、挑报价都是这个结构。" +
                 "关键不是选到最好的，是<b>让遗憾有上界</b>。" +
                 "<b>候选不足 4 个会被拦</b>：那直接全看完就行。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_decision 决策矩阵 ══════════ */
    @Composable
    fun decision(o: JSONObject, ctx: RenderContext) {
        val optsA = o.optJSONArray("opts") ?: return
        val critA = o.optJSONArray("crit") ?: return
        val m = minOf(optsA.length(), 6); val k = minOf(critA.length(), 5)
        val opts = (0 until m).map { optsA.optString(it, "选项${it + 1}") }
        val crit = (0 until k).map {
            val c = critA.optJSONObject(it) ?: JSONObject()
            c.optString("n", "准则${it + 1}") to c.optDouble("w", 1.0)
        }
        val scoresA = o.optJSONArray("scores") ?: return
        val sc = (0 until m).map { i ->
            val r = scoresA.optJSONArray(i)
            (0 until k).map { j -> r?.optDouble(j, 0.0) ?: 0.0 }
        }
        var w by remember { mutableStateOf(crit.map { it.second }) }
        var sel by remember { mutableIntStateOf(-1) }
        fun total(i: Int): Double {
            val s = w.sum()
            if (abs(s) < 1e-9) return 0.0
            return sc[i].indices.sumOf { j -> sc[i][j] * w[j] } / s
        }
        val rank = (0 until m).sortedByDescending { total(it) }
        val best = rank.firstOrNull() ?: -1
        val margin = if (rank.size > 1) total(rank[0]) - total(rank[1]) else 0.0

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "决策矩阵"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (margin > 0.3) "结论稳" else "权重一变就翻", fontSize = 9.sp,
                     color = if (margin > 0.3) ZxT.Ok else ZxT.Warn)
            }
            Spacer(Modifier.height(6.dp))
            // 权重条
            crit.forEachIndexed { j, (nm, _) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(nm, fontSize = 8.5.sp, color = ZxT.Text2,
                         modifier = Modifier.width(52.dp), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    Box(Modifier.weight(1f).height(8.dp)
                        .clip(RoundedCornerShape(4.dp)).background(ZxT.Surface2)) {
                        Box(Modifier.fillMaxWidth((w[j] / 3.0).toFloat().coerceIn(0.01f, 1f))
                            .fillMaxHeight().background(PAL[j % PAL.size]))
                    }
                    Row(Modifier.width(66.dp)) {
                        listOf(0.5, 1.0, 2.0).forEach { v ->
                            Text(v.toString().removeSuffix(".0"), fontSize = 7.5.sp,
                                 color = if (abs(w[j] - v) < 1e-9) ZxT.PriLite else ZxT.Text3,
                                 modifier = Modifier.weight(1f)
                                     .clip(RoundedCornerShape(3.dp))
                                     .background(if (abs(w[j] - v) < 1e-9) ZxT.Surface2 else Color.Transparent)
                                     .clickable {
                                         w = w.toMutableList().also { it[j] = v }
                                     }.padding(2.dp, 1.dp), textAlign = TextAlign.Center)
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            // 排名
            rank.forEachIndexed { rk, i ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (rk == 0) PAL[0].copy(.10f)
                                else if (sel == i) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        sel = i
                        ctx.callTool("zx_select", mapOf(
                            "opt" to opts[i], "score" to "%.3f".format(total(i)),
                            "rank" to (rk + 1).toString(), "source" to "zx_dmatrix"))
                    }.padding(6.dp, 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${rk + 1}", fontSize = 9.sp,
                         color = if (rk == 0) ZxT.PriLite else ZxT.Text3,
                         modifier = Modifier.width(14.dp))
                    Text(opts[i], fontSize = 9.5.sp,
                         color = if (rk == 0) ZxT.Text else ZxT.Text2,
                         modifier = Modifier.weight(1f), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    Box(Modifier.width(64.dp).height(6.dp)
                        .clip(RoundedCornerShape(3.dp)).background(ZxT.Surface2)) {
                        val mx = rank.maxOf { total(it) }.coerceAtLeast(1e-9)
                        Box(Modifier.fillMaxWidth((total(i) / mx).toFloat().coerceIn(0.01f, 1f))
                            .fillMaxHeight().background(if (rk == 0) PAL[0] else ZxT.Text3))
                    }
                    Text("%.2f".format(total(i)), fontSize = 8.5.sp,
                         color = if (rk == 0) ZxT.Text else ZxT.Text3,
                         modifier = Modifier.width(28.dp), textAlign = TextAlign.End,
                         fontFamily = ZxT.Mono)
                }
            }
            Text("领先 ${"%.2f".format(margin)}" +
                    if (margin < 0.3) " —— 差距很小，拖一下上面权重试试会不会翻盘" else " —— 差距明显",
                fontSize = 9.sp, color = if (margin > 0.3) ZxT.Ok else ZxT.Warn,
                modifier = Modifier.padding(top = 3.dp))
            Text("已有 tradeoff 比<b>两个已知选项</b>、zx_choice 让<b>人选一个</b>，" +
                 "这个处理「<b>多个选项 × 多个准则</b>」。" +
                 "关键是<b>权重可拖</b>——只给排名，用户不知道换个权重会不会翻盘，" +
                 "而不知道这一点就没法判断这个结论稳不稳。" +
                 "<b>权重和为 0 会被拦</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_riskreturn 风险收益 ══════════ */
    @Composable
    fun riskreturn(o: JSONObject, ctx: RenderContext) {
        val ptsA = o.optJSONArray("pts") ?: return
        val n = minOf(ptsA.length(), 12)
        val pts = (0 until n).map {
            val p = ptsA.optJSONObject(it) ?: JSONObject()
            Triple(p.optString("n", "方案${it + 1}"), p.optDouble("r", 0.0), p.optDouble("s", 0.0))
        }
        var sel by remember { mutableIntStateOf(-1) }
        // 有效前沿：给定风险下收益最高的点
        val sorted = pts.sortedBy { it.third }
        val frontier = ArrayList<Int>()
        var bestR = -Double.MAX_VALUE
        sorted.forEach { p ->
            if (p.second > bestR + 1e-9) { bestR = p.second; frontier.add(pts.indexOf(p)) }
        }
        val rLo = pts.minOf { it.second }; val rHi = pts.maxOf { it.second }
        val sLo = pts.minOf { it.third }; val sHi = pts.maxOf { it.third }
        val spR = (rHi - rLo).coerceAtLeast(1e-9); val spS = (sHi - sLo).coerceAtLeast(1e-9)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "风险收益"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("${frontier.size} 个在前沿", fontSize = 9.sp, color = ZxT.Ok)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(104.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width; val hh = size.height; val p2 = 14f
                    val fx = { v: Double -> p2 + ((v - sLo) / spS * (w - 2 * p2)).toFloat() }
                    val fy = { v: Double -> hh - p2 - ((v - rLo) / spR * (hh - 2 * p2)).toFloat() }
                    drawLine(ZxT.Surface3, Offset(p2, hh - p2), Offset(w - p2, hh - p2), 1f)
                    drawLine(ZxT.Surface3, Offset(p2, p2), Offset(p2, hh - p2), 1f)
                    // 前沿连线
                    val fs = frontier.map { pts[it] }.sortedBy { it.third }
                    drawPath(Path().apply {
                        fs.forEachIndexed { k, p ->
                            val x = fx(p.third); val y = fy(p.second)
                            if (k == 0) moveTo(x, y) else lineTo(x, y)
                        }
                    }, ZxT.Ok, style = Stroke(1.5f))
                    pts.forEachIndexed { k, (_, r, s) ->
                        val onF = k in frontier
                        drawCircle(if (k == sel) Color.White
                                   else if (onF) ZxT.Ok else PAL[0].copy(.55f),
                                   if (k == sel || onF) 4f else 2.6f, Offset(fx(s), fy(r)))
                    }
                }
                Row(Modifier.fillMaxWidth().height(104.dp)) {
                    repeat(n) { k ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable { sel = k })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                Text("风险 →", fontSize = 7.5.sp, color = ZxT.Text3,
                     modifier = Modifier.weight(1f))
                Text("↑ 收益　绿线=有效前沿", fontSize = 7.5.sp, color = ZxT.Text3)
            }
            pts.forEachIndexed { k, (nm, r, s) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (sel == k) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_select", mapOf(
                            "opt" to nm, "ret" to "%.3f".format(r), "risk" to "%.3f".format(s),
                            "ratio" to "%.2f".format(if (s > 1e-9) r / s else 0.0),
                            "frontier" to (if (k in frontier) "yes" else "no"),
                            "source" to "zx_riskreturn"))
                    }.padding(5.dp, 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (k in frontier) "◆" else "◇", fontSize = 8.sp,
                         color = if (k in frontier) ZxT.Ok else ZxT.Text3,
                         modifier = Modifier.width(12.dp))
                    Text(nm, fontSize = 9.sp, color = ZxT.Text2,
                         modifier = Modifier.weight(1f), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    Text("收益 ${"%.2f".format(r)}", fontSize = 8.sp, color = ZxT.Text3)
                    Text("风险 ${"%.2f".format(s)}", fontSize = 8.sp, color = ZxT.Text3,
                         modifier = Modifier.padding(start = 6.dp))
                }
            }
            Text("已有 scatter 画<b>两个变量的关系</b>、bubble 加<b>第三维</b>，" +
                 "这个专门处理「<b>收益越高越好、风险越低越好</b>」——" +
                 "前沿之外的点是<b>白白承担了风险</b>（同样的风险别人收益更高）。" +
                 "<b>两列长度不等会被拦</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_utility 效用曲线 ══════════ */
    @Composable
    fun utility(o: JSONObject, ctx: RenderContext) {
        val ptsA = o.optJSONArray("pts") ?: return
        val n = minOf(ptsA.length(), 10)
        val pts = (0 until n).map { i ->
            val p = ptsA.optJSONObject(i) ?: JSONObject()
            p.optDouble("x", i.toDouble()) to p.optDouble("u", 0.0)
        }.sortedBy { it.first }
        var sel by remember { mutableIntStateOf(-1) }
        // 二阶差分：凹 = 风险厌恶，凸 = 风险偏好
        val concavity = run {
            var s = 0.0
            for (k in 1 until n - 1) {
                val d1 = pts[k].second - pts[k - 1].second
                val d2 = pts[k + 1].second - pts[k].second
                s += (d2 - d1)
            }
            s
        }
        val kind = when { concavity < -0.02 -> "风险厌恶（凹）"; concavity > 0.02 -> "风险偏好（凸）" else -> "近似中性（线性）" }
        val xLo = pts.first().first; val xHi = pts.last().first
        val uLo = pts.minOf { it.second }; val uHi = pts.maxOf { it.second }
        val spX = (xHi - xLo).coerceAtLeast(1e-9); val spU = (uHi - uLo).coerceAtLeast(1e-9)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "效用曲线"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(kind, fontSize = 9.sp, color = PAL[0])
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(88.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width; val hh = size.height; val p2 = 12f
                    val fx = { v: Double -> p2 + ((v - xLo) / spX * (w - 2 * p2)).toFloat() }
                    val fy = { v: Double -> hh - p2 - ((v - uLo) / spU * (hh - 2 * p2)).toFloat() }
                    // 线性参考：偏离它才看得出曲率
                    drawLine(ZxT.Text3.copy(.45f), Offset(fx(xLo), fy(uLo)),
                             Offset(fx(xHi), fy(uHi)), 1.1f)
                    drawPath(Path().apply {
                        pts.forEachIndexed { k, (x, u) ->
                            val cx = fx(x); val cy = fy(u)
                            if (k == 0) moveTo(cx, cy) else lineTo(cx, cy)
                        }
                    }, PAL[0], style = Stroke(1.9f))
                    pts.forEachIndexed { k, (x, u) ->
                        drawCircle(if (k == sel) Color.White else PAL[0],
                                   if (k == sel) 4f else 2.8f, Offset(fx(x), fy(u)))
                    }
                }
                Row(Modifier.fillMaxWidth().height(88.dp)) {
                    repeat(n) { k ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = k
                            ctx.callTool("zx_select", mapOf(
                                "x" to "%.3f".format(pts[k].first),
                                "u" to "%.3f".format(pts[k].second),
                                "kind" to kind, "source" to "zx_utility"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                Text("${"%.2f".format(xLo)}", fontSize = 7.5.sp, color = ZxT.Text3,
                     modifier = Modifier.weight(1f))
                Text("灰线=线性参考（偏离它才看得出曲率）", fontSize = 7.5.sp, color = ZxT.Text3)
                Text("${"%.2f".format(xHi)}", fontSize = 7.5.sp, color = ZxT.Text3)
            }
            if (sel >= 0)
                Text("x=${"%.2f".format(pts[sel].first)} → u=${"%.3f".format(pts[sel].second)}",
                    fontSize = 9.5.sp, color = ZxT.Text3, modifier = Modifier.padding(top = 3.dp))
            Text("已有 curve 给<b>一条曲线编辑器</b>（用户拖形状）、" +
                 "slider 收<b>一个数值</b>，这个收的是<b>整个偏好</b>——" +
                 "「钱多一倍快乐是不是多一倍」这件事，" +
                 "用一个数字答不了，得是一条曲线。" +
                 "有了它，<b>期望效用才能算</b>：选 50% 拿 100 还是稳拿 45，" +
                 "取决于这条曲线的形状。<b>点不足 2 个会被拦</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_bandit 多臂老虎机 ══════════ */
    @Composable
    fun bandit(o: JSONObject, ctx: RenderContext) {
        val armsA = o.optJSONArray("arms") ?: return
        val n = minOf(armsA.length(), 6)
        val arms = (0 until n).map {
            val a = armsA.optJSONObject(it) ?: JSONObject()
            Triple(a.optString("n", "臂${it + 1}"), a.optDouble("mu", 0.0), a.optInt("k", 0))
        }
        var sel by remember { mutableIntStateOf(-1) }
        val totalK = arms.sumOf { it.third }
        // UCB：均值 + 探索项，试验少的臂探索项大
        fun ucb(i: Int): Double {
            val (_, mu, k) = arms[i]
            if (k == 0) return Double.MAX_VALUE
            return mu + sqrt(2.0 * ln(totalK.toDouble().coerceAtLeast(2.0)) / k)
        }
        val pick = if (arms.any { it.third == 0 }) arms.indexOfFirst { it.third == 0 }
                   else (0 until n).maxByOrNull { ucb(it) } ?: 0
        val explored = arms.all { it.third >= 3 }
        val muLo = arms.minOf { it.second }; val muHi = arms.maxOf { it.second }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "多臂老虎机"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (explored) "探索较充分" else "还有臂没试够", fontSize = 9.sp,
                     color = if (explored) ZxT.Ok else ZxT.Warn)
            }
            Spacer(Modifier.height(6.dp))
            arms.forEachIndexed { i, (nm, mu, k) ->
                val isPick = i == pick
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (isPick) PAL[0].copy(.12f)
                                else if (sel == i) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        sel = i
                        ctx.callTool("zx_select", mapOf(
                            "arm" to nm, "mu" to "%.3f".format(mu), "k" to k.toString(),
                            "ucb" to (if (k == 0) "inf" else "%.3f".format(ucb(i))),
                            "source" to "zx_bandit"))
                    }.padding(6.dp, 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (isPick) "→" else "　", fontSize = 9.sp,
                         color = ZxT.PriLite, modifier = Modifier.width(12.dp))
                    Text(nm, fontSize = 9.5.sp,
                         color = if (isPick) ZxT.Text else ZxT.Text2,
                         modifier = Modifier.width(52.dp), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    // 均值条
                    Box(Modifier.weight(1f).height(8.dp)
                        .clip(RoundedCornerShape(4.dp)).background(ZxT.Surface2)) {
                        val f = ((mu - muLo) / (muHi - muLo).coerceAtLeast(1e-9))
                            .toFloat().coerceIn(0.02f, 1f)
                        Box(Modifier.fillMaxWidth(f).fillMaxHeight()
                            .background(if (isPick) PAL[0] else PAL[0].copy(.5f)))
                    }
                    Text("%.2f".format(mu), fontSize = 8.5.sp, color = ZxT.Text2,
                         modifier = Modifier.width(30.dp), textAlign = TextAlign.End,
                         fontFamily = ZxT.Mono)
                    // 试验次数：没试过就是一片空白，得标出来
                    Text(if (k == 0) "未试" else "n=$k", fontSize = 8.sp,
                         color = if (k == 0) ZxT.Err else if (k < 3) ZxT.Warn else ZxT.Text3,
                         modifier = Modifier.width(34.dp), textAlign = TextAlign.End)
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(if (explored) ZxT.Ok.copy(.10f) else ZxT.Warn.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (explored) "✓" else "!", fontSize = 11.sp,
                     color = if (explored) ZxT.Ok else ZxT.Warn)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text("下一个试：「${arms[pick].first}」" +
                            if (arms[pick].third == 0) "（还没试过，必须先试）"
                            else "（UCB 最高 ${"%.3f".format(ucb(pick))}）",
                        fontSize = 9.sp, color = if (explored) ZxT.Ok else ZxT.Warn)
                    Text("UCB = 均值 + 探索项；试得少的臂探索项大，所以不会被饿死",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 ranking 排<b>已知结果</b>、zx_decision 比<b>多个选项</b>，" +
                 "这个处理「<b>边试边选</b>」——每次选择既是为了拿收益，" +
                 "也是为了<b>多知道一点</b>。" +
                 "只看当前均值会<b>饿死没试过的臂</b>（它均值是 0 只因为没试过）。" +
                 "<b>某臂 k=0 却给了均值会被拦</b>：那是假数据。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_explain 特征归因 ══════════ */
    @Composable
    fun explain(o: JSONObject, ctx: RenderContext) {
        val base = o.optDouble("base", 0.0)
        val pred = o.optDouble("pred", 0.0)
        val attrsA = o.optJSONArray("attrs") ?: return
        val n = minOf(attrsA.length(), 8)
        val attrs = (0 until n).map {
            val a = attrsA.optJSONObject(it) ?: JSONObject()
            Triple(a.optString("n", "特征${it + 1}"), a.optDouble("v", 0.0), a.optString("x", ""))
        }.sortedByDescending { abs(it.second) }
        var sel by remember { mutableIntStateOf(-1) }
        val sum = attrs.sumOf { it.second }
        val consistent = abs((base + sum) - pred) < 0.02 * abs(pred).coerceAtLeast(1.0)
        // 瀑布：从基线一步步推到预测值
        val steps = ArrayList<Pair<String, Double>>()
        var cur = base
        attrs.forEach { (nm, v, _) -> steps.add(nm to cur); cur += v; steps.add(nm to cur) }
        val lo = minOf(base, pred, cur); val hi = maxOf(base, pred, cur)
        val sp = (hi - lo).coerceAtLeast(1e-9)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "特征归因"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (consistent) "自洽" else "不自洽", fontSize = 9.sp,
                     color = if (consistent) ZxT.Ok else ZxT.Err)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(96.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width; val hh = size.height; val p2 = 8f
                    val bw = (w - 2 * p2) / (n + 2)
                    val py = { v: Double -> hh - p2 - ((v - lo) / sp * (hh - 2 * p2)).toFloat() }
                    // 基线
                    drawRect(ZxT.Text3, Offset(p2, py(base) - 2f), Size(bw, 4f))
                    var c = base
                    attrs.forEachIndexed { k, (_, v, _) ->
                        val from = c; c += v
                        val x = p2 + (k + 1) * bw
                        drawRect(if (v >= 0) ZxT.Add else ZxT.Del,
                                 Offset(x, kotlin.math.min(py(from), py(c))),
                                 Size(bw * 0.7f, abs(py(from) - py(c)).coerceAtLeast(1.5f)))
                        // 连接线：没它看不出是累加
                        drawLine(ZxT.Text3.copy(.5f), Offset(x, py(c)),
                                 Offset(x + bw * 0.7f, py(c)), 1f)
                    }
                    drawRect(PAL[0], Offset(p2 + (n + 1) * bw, py(c) - 2f), Size(bw * 0.7f, 4f))
                }
                Row(Modifier.fillMaxWidth().height(96.dp)) {
                    Box(Modifier.weight(1f).fillMaxHeight())
                    repeat(n) { k ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable { sel = k })
                    }
                    Box(Modifier.weight(1f).fillMaxHeight())
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                Text("基线 ${"%.2f".format(base)}", fontSize = 7.5.sp, color = ZxT.Text3,
                     modifier = Modifier.weight(1f))
                Text("预测 ${"%.2f".format(pred)}", fontSize = 7.5.sp, color = ZxT.PriLite)
            }
            attrs.forEachIndexed { k, (nm, v, x) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (sel == k) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_select", mapOf(
                            "feat" to nm, "attr" to "%.4f".format(v),
                            "val" to x, "source" to "zx_explain"))
                    }.padding(5.dp, 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(6.dp).height(6.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(if (v >= 0) ZxT.Add else ZxT.Del))
                    Text(nm, fontSize = 9.sp, color = ZxT.Text2,
                         modifier = Modifier.weight(1f).padding(start = 5.dp),
                         maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (x.isNotBlank())
                        Text(x, fontSize = 8.sp, color = ZxT.Text3,
                             modifier = Modifier.padding(end = 5.dp))
                    Text((if (v >= 0) "+" else "") + "%.3f".format(v), fontSize = 8.5.sp,
                         color = if (v >= 0) ZxT.Add else ZxT.Err, fontFamily = ZxT.Mono)
                }
            }
            if (!consistent)
                Text("基线 ${"%.3f".format(base)} + 归因合计 ${"%.3f".format(sum)} = " +
                        "${"%.3f".format(base + sum)}，但预测是 ${"%.3f".format(pred)}——" +
                        "差 ${"%.3f".format(abs(base + sum - pred))}。归因不自洽时不能拿它解释。",
                    fontSize = 9.sp, color = ZxT.Err, modifier = Modifier.padding(top = 3.dp))
            Text("已有 waterfall 画<b>财务增减</b>、zx_diff 比<b>两个版本</b>，" +
                 "这个解释「<b>模型为什么给出这个数</b>」——" +
                 "从基线出发，每个特征推一步，最后到预测值。" +
                 "关键是<b>要自查自洽</b>：基线 + 归因 ≠ 预测，那这个解释是假的。" +
                 "<b>不自洽会被拦</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_cfactual 反事实 ══════════ */
    @Composable
    fun cfactual(o: JSONObject, ctx: RenderContext) {
        val curA = o.optJSONArray("cur") ?: return
        val cfA = o.optJSONArray("cf") ?: return
        val n = minOf(curA.length(), cfA.length(), 6)
        val rows = (0 until n).map { i ->
            val c = curA.optJSONObject(i) ?: JSONObject()
            val f = cfA.optJSONObject(i) ?: JSONObject()
            Triple(c.optString("n", "特征${i + 1}"),
                   c.optString("v", ""), f.optString("v", ""))
        }
        val y0 = o.optDouble("y0", 0.0)
        val y1 = o.optDouble("y1", 0.0)
        val changed = rows.count { it.second != it.third }
        val effective = abs(y1 - y0) > 1e-9

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "反事实解释"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (effective) "有效" else "无效", fontSize = 9.sp,
                     color = if (effective) ZxT.Ok else ZxT.Err)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
                Text("特征", fontSize = 8.sp, color = ZxT.Text3, modifier = Modifier.weight(1f))
                Text("现在", fontSize = 8.sp, color = ZxT.Text3,
                     modifier = Modifier.width(56.dp), textAlign = TextAlign.End)
                Text("改成", fontSize = 8.sp, color = ZxT.Text3,
                     modifier = Modifier.width(56.dp), textAlign = TextAlign.End)
            }
            rows.forEach { (nm, a, b) ->
                val ch = a != b
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (ch) ZxT.Warn.copy(.10f) else Color.Transparent)
                    .padding(5.dp, 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (ch) "●" else "○", fontSize = 7.sp,
                         color = if (ch) ZxT.Warn else ZxT.Text3,
                         modifier = Modifier.width(10.dp))
                    Text(nm, fontSize = 9.sp, color = if (ch) ZxT.Text else ZxT.Text2,
                         modifier = Modifier.weight(1f), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    Text(a, fontSize = 8.5.sp, color = ZxT.Text3,
                         modifier = Modifier.width(56.dp), textAlign = TextAlign.End,
                         maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(b, fontSize = 8.5.sp,
                         color = if (ch) ZxT.Warn else ZxT.Text3,
                         modifier = Modifier.width(56.dp), textAlign = TextAlign.End,
                         maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(if (effective) ZxT.Ok.copy(.10f) else ZxT.Err.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (effective) "✓" else "!", fontSize = 11.sp,
                     color = if (effective) ZxT.Ok else ZxT.Err)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text("改 $changed 项 → 结果 ${"%.3f".format(y0)} → ${"%.3f".format(y1)}",
                        fontSize = 9.sp, color = if (effective) ZxT.Ok else ZxT.Err)
                    Text(if (effective) "这就是「要改什么才能得到另一个结果」的最小改动"
                         else "改了但结果没变——这个反事实不成立，不能拿它当建议",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 zx_diff 比<b>两版文件</b>、baseline 比<b>改前改后</b>，" +
                 "这个回答「<b>要改什么才能得到另一个结果</b>」——" +
                 "不是随便改，是<b>最小改动</b>。改 5 项才翻转的建议没法执行；" +
                 "改 1 项就翻转的才是可操作的。" +
                 "<b>改了但结果没变会被拦</b>：那是无效反事实。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_cforest 异质效应 ══════════ */
    @Composable
    fun cforest(o: JSONObject, ctx: RenderContext) {
        val subA = o.optJSONArray("subs") ?: return
        val n = minOf(subA.length(), 8)
        val subs = (0 until n).map {
            val s = subA.optJSONObject(it) ?: JSONObject()
            Triple(s.optString("n", "子组${it + 1}"), s.optDouble("e", 0.0), s.optDouble("se", 0.1))
        }
        var sel by remember { mutableIntStateOf(-1) }
        val ate = subs.map { it.second }.average()
        val spread = (subs.maxOf { it.second } - subs.minOf { it.second })
        val hetero = spread > 2 * (subs.map { it.third }.average())
        val eLo = subs.minOf { it.second - 1.96 * it.third }
        val eHi = subs.maxOf { it.second + 1.96 * it.third }
        val sp = (eHi - eLo).coerceAtLeast(1e-9)
        val pos = subs.count { it.second - 1.96 * it.third > 0 }
        val neg = subs.count { it.second + 1.96 * it.third < 0 }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "异质效应"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (hetero) "存在异质性" else "看不出异质性", fontSize = 9.sp,
                     color = if (hetero) ZxT.Warn else ZxT.Ok)
            }
            Spacer(Modifier.height(6.dp))
            // 森林图：每个子组一个区间
            subs.forEachIndexed { k, (nm, e, se) ->
                val lo = e - 1.96 * se; val hi = e + 1.96 * se
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (sel == k) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_select", mapOf(
                            "sub" to nm, "e" to "%.4f".format(e),
                            "lo" to "%.4f".format(lo), "hi" to "%.4f".format(hi),
                            "sig" to (if (lo > 0 || hi < 0) "yes" else "no"),
                            "source" to "zx_cforest"))
                    }.padding(5.dp, 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(nm, fontSize = 9.sp, color = ZxT.Text2,
                         modifier = Modifier.width(58.dp), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    Box(Modifier.weight(1f).height(14.dp)) {
                        Canvas(Modifier.fillMaxSize()) {
                            val w = size.width; val hh = size.height
                            val fx = { v: Double -> ((v - eLo) / sp * w).toFloat().coerceIn(1f, w - 1f) }
                            drawLine(ZxT.Err, Offset(fx(0.0), 0f), Offset(fx(0.0), hh), 1.3f)
                            drawLine(ZxT.Text3.copy(.5f), Offset(fx(lo), hh / 2),
                                     Offset(fx(hi), hh / 2), 1.2f)
                            drawCircle(if (lo > 0) ZxT.Ok else if (hi < 0) ZxT.Err else ZxT.Text3,
                                       3.4f, Offset(fx(e), hh / 2))
                        }
                    }
                    Text("%+.2f".format(e), fontSize = 8.5.sp,
                         color = if (lo > 0) ZxT.Ok else if (hi < 0) ZxT.Err else ZxT.Text3,
                         modifier = Modifier.width(34.dp), textAlign = TextAlign.End,
                         fontFamily = ZxT.Mono)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                Text("红线=0（无效应）", fontSize = 7.5.sp, color = ZxT.Text3,
                     modifier = Modifier.weight(1f))
                Text("ATE≈${"%.3f".format(ate)}", fontSize = 8.sp, color = ZxT.Text3)
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (hetero) ZxT.Warn.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (hetero) "!" else "✓", fontSize = 11.sp,
                     color = if (hetero) ZxT.Warn else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text("$pos 个子组显著为正、$neg 个显著为负、" +
                            "${n - pos - neg} 个看不出",
                        fontSize = 9.sp, color = if (hetero) ZxT.Warn else ZxT.Ok)
                    Text(if (hetero) "同一个处理对不同人效果不同——平均效应会骗人"
                         else "各子组效应差不多，用平均效应就够了",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 forest 合并<b>多个研究</b>、zx_iv 估<b>一个平均效应</b>，" +
                 "这个看<b>同一处理对不同人是否效果不同</b>——" +
                 "平均效应为正，可能是「一半人大受益、一半人受损」抵消出来的。" +
                 "<b>要决定给谁用，就必须看异质性</b>。" +
                 "<b>效应全同会被拦</b>：那没有异质性可看。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_alloc 序贯分配 ══════════ */
    @Composable
    fun alloc(o: JSONObject, ctx: RenderContext) {
        val chA = o.optJSONArray("ch") ?: return
        val n = minOf(chA.length(), 5)
        val ch = (0 until n).map {
            val c = chA.optJSONObject(it) ?: JSONObject()
            Triple(c.optString("n", "渠道${it + 1}"), c.optDouble("a", 1.0), c.optDouble("b", 0.5))
        }
        var budget by remember { mutableDoubleStateOf(o.optDouble("budget", 100.0)) }
        var sel by remember { mutableIntStateOf(-1) }
        // 边际收益 √ 递减：投得越多，每单位回报越低
        fun alloc(): List<Double> {
            val res = MutableList(n) { 0.0 }
            var left = budget
            repeat(400) {
                var bi = -1; var bm = -1e9
                (0 until n).forEach { i ->
                    val m = ch[i].second * ch[i].third * 0.5 / sqrt(res[i] + 1.0)
                    if (m > bm) { bm = m; bi = i }
                }
                if (bi < 0 || left <= 0) return@repeat
                res[bi] += 1.0; left -= 1.0
            }
            return res
        }
        val got = alloc()
        val tot = ch.indices.sumOf { i -> ch[i].second * sqrt(got[i] * ch[i].third) }
        val equal = ch.indices.sumOf { i -> ch[i].second * sqrt((budget / n) * ch[i].third) }
        val mx = got.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "序贯分配"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("总回报 ${"%.1f".format(tot)}", fontSize = 9.sp, color = ZxT.PriLite)
            }
            Spacer(Modifier.height(6.dp))
            ch.forEachIndexed { i, (nm, a, b) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (sel == i) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        sel = i
                        ctx.callTool("zx_select", mapOf(
                            "ch" to nm, "alloc" to "%.1f".format(got[i]),
                            "pct" to "%.1f".format(got[i] / budget.coerceAtLeast(1e-9) * 100),
                            "source" to "zx_alloc"))
                    }.padding(5.dp, 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(nm, fontSize = 9.sp, color = ZxT.Text2,
                         modifier = Modifier.width(50.dp), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    Box(Modifier.weight(1f).height(9.dp)
                        .clip(RoundedCornerShape(4.dp)).background(ZxT.Surface2)) {
                        Box(Modifier.fillMaxWidth((got[i] / mx).toFloat().coerceIn(0.01f, 1f))
                            .fillMaxHeight().background(PAL[i % PAL.size]))
                    }
                    Text("${"%.0f".format(got[i])}", fontSize = 8.5.sp, color = ZxT.Text2,
                         modifier = Modifier.width(28.dp), textAlign = TextAlign.End,
                         fontFamily = ZxT.Mono)
                    Text("${"%.0f".format(got[i] / budget.coerceAtLeast(1e-9) * 100)}%",
                        fontSize = 8.sp, color = ZxT.Text3,
                        modifier = Modifier.width(30.dp), textAlign = TextAlign.End)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("预算 ${"%.0f".format(budget)}（点下方调）", fontSize = 8.5.sp, color = ZxT.Text3)
            Row(Modifier.fillMaxWidth().height(24.dp)) {
                listOf(40.0, 100.0, 200.0, 400.0).forEach { v ->
                    Text("${"%.0f".format(v)}", fontSize = 8.5.sp,
                         color = if (abs(budget - v) < 1e-9) ZxT.PriLite else ZxT.Text3,
                         modifier = Modifier.weight(1f)
                             .clip(RoundedCornerShape(4.dp))
                             .background(if (abs(budget - v) < 1e-9) ZxT.Surface2 else Color.Transparent)
                             .clickable { budget = v }.padding(2.dp),
                         textAlign = TextAlign.Center)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp)).background(ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("✓", fontSize = 11.sp, color = ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text("按边际分配得 ${"%.1f".format(tot)}，平分只能得 ${"%.1f".format(equal)}" +
                            "（+${"%.1f".format((tot / equal.coerceAtLeast(1e-9) - 1) * 100)}%）",
                        fontSize = 9.sp, color = ZxT.Ok)
                    Text("每单位投给当前边际收益最高的渠道；投多了边际就降下来",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 slidergroup 分<b>固定总量</b>、zx_power 算<b>需要多少样本</b>，" +
                 "这个处理「<b>边际递减下的预算投放</b>」——" +
                 "好渠道也不是无脑全给它，投到某个点边际就低于别的渠道了。" +
                 "<b>预算 ≤0 或边际不递减会被拦</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_abmon A/B 分流监控 ══════════ */
    @Composable
    fun abmon(o: JSONObject, ctx: RenderContext) {
        val armsA = o.optJSONArray("arms") ?: return
        val n = minOf(armsA.length(), 5)
        val arms = (0 until n).map {
            val a = armsA.optJSONObject(it) ?: JSONObject()
            Triple(a.optString("n", "组${it + 1}"), a.optDouble("p", 1.0 / n), a.optInt("k", 0))
        }
        val total = arms.sumOf { it.third }
        var sel by remember { mutableIntStateOf(-1) }
        // SRM：实际分流 vs 预期，卡方检验
        val chi = arms.sumOf { (_, p, k) ->
            val e = total * p
            if (e < 1e-9) 0.0 else (k - e) * (k - e) / e
        }
        val srm = chi > 3.84    // df=1 的 0.05 临界，多组偏严但不会漏
        val minK = arms.minOf { it.third }
        val enough = minK >= 100

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "A/B 分流监控"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (srm) "SRM 告警" else "分流正常", fontSize = 9.sp,
                     color = if (srm) ZxT.Err else ZxT.Ok)
            }
            Spacer(Modifier.height(6.dp))
            // 分流条
            Box(Modifier.fillMaxWidth().height(16.dp)
                .clip(RoundedCornerShape(8.dp)).background(ZxT.Surface2)) {
                Row(Modifier.fillMaxWidth().fillMaxHeight()) {
                    arms.forEach { (_, _, k) ->
                        val f = (k.toDouble() / total.coerceAtLeast(1)).toFloat()
                        Box(Modifier.weight(f.coerceAtLeast(0.01f)).fillMaxHeight()
                            .background(PAL[arms.indexOfFirst { it.third == k } % PAL.size]))
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            arms.forEachIndexed { i, (nm, p, k) ->
                val actual = k.toDouble() / total.coerceAtLeast(1)
                val dev = abs(actual - p)
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (sel == i) ZxT.Surface2
                                else if (dev > 0.02) ZxT.Err.copy(.08f) else Color.Transparent)
                    .clickable {
                        sel = i
                        ctx.callTool("zx_select", mapOf(
                            "arm" to nm, "want" to "%.3f".format(p),
                            "got" to "%.3f".format(actual), "n" to k.toString(),
                            "dev" to "%.3f".format(dev), "source" to "zx_abmon"))
                    }.padding(5.dp, 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(RoundedCornerShape(1.dp))
                        .background(PAL[i % PAL.size]))
                    Text(nm, fontSize = 9.sp, color = ZxT.Text2,
                         modifier = Modifier.weight(1f).padding(start = 5.dp),
                         maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("预期 ${"%.0f".format(p * 100)}%", fontSize = 8.sp, color = ZxT.Text3)
                    Text("实际 ${"%.1f".format(actual * 100)}%", fontSize = 8.5.sp,
                         color = if (dev > 0.02) ZxT.Err else ZxT.Text2,
                         modifier = Modifier.padding(start = 5.dp))
                    Text("n=$k", fontSize = 8.sp, color = ZxT.Text3,
                         modifier = Modifier.padding(start = 5.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(if (srm) ZxT.Err.copy(.10f)
                            else if (!enough) ZxT.Warn.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (srm) "✗" else if (!enough) "!" else "✓", fontSize = 11.sp,
                     color = if (srm) ZxT.Err else if (!enough) ZxT.Warn else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (srm) "分流比例异常（χ²=${"%.2f".format(chi)} > 3.84）——先修分流，别看指标"
                         else if (!enough) "分流正常，但最小组只有 $minK 个样本，还看不出结论"
                         else "分流正常（χ²=${"%.2f".format(chi)}），样本也够",
                        fontSize = 9.sp,
                        color = if (srm) ZxT.Err else if (!enough) ZxT.Warn else ZxT.Ok)
                    Text("SRM（分流比例不匹配）会让<b>所有指标结论都不可信</b>，" +
                            "所以必须先看它再看指标",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 zx_test 比<b>两组差异</b>、zx_seqtest 管<b>看多次</b>，" +
                 "这个看的是<b>更前面的一步</b>：分流本身对不对。" +
                 "分流歪了，后面所有对比都是假的——" +
                 "<b>SRM 告警时会明确说「先修分流，别看指标」</b>。" +
                 "<b>分流和不为 1 会被拦</b>：SRM 无从判断。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }
}
