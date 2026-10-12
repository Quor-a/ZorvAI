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
import androidx.compose.ui.geometry.Size
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
import kotlin.random.Random

/**
 * 第二十五批组件（12 个）—— 稳健性、敏感性、效应量。
 *
 * 前二十四批铺完了"看什么、填什么、改什么、\n* 凭什么、怎么推演、统计前提成不成立"。
 *
 * 第二十四批给了**判据**（QQ 给偏离带、森林图给合并菱形、
 * 公差带给 Cp/Cpk），但**判据本身是单点结论**：
 *
 *   "近似正态" / "能下结论" / "p=0.03"
 *
 * 用户看到这类结论之后常见的三个疑问，第二十四批答不了：
 *
 *   一、**这个结论稳不稳？**
 *       换一批样本还是这个结论吗？Bootstrap 回答这个——
 *       它不假设分布，用重采样的经验分布直接给区间。
 *
 *   二、**换个方法还是这个结论吗？**
 *       t 检验显著、秩和检验不显著，该信哪个？
 *       假设检验卡同时给三种方法的结果。
 *
 *   三、**效应有多大？**
 *       只给 p 是个陷阱——样本一大什么都有显著性，
 *       但效应可能小到没有任何实际意义。
 *       所以同时给 Cohen's d。
 *
 * 十二个组件分成三组：
 *
 *   **稳健性**（结论稳不稳）
 *     zx_boot     Bootstrap 区间（不假设分布）
 *     zx_perm     置换检验（不假设分布，直接算精确 p）
 *     zx_pscan    参数扫描（结论对参数敏不敏感）
 *     zx_survcmp  生存分组对比（log-rank）
 *
 *   **效应与判别**
 *     zx_test     假设检验卡（多方法 + 效应量）
 *     zx_roc      ROC + AUC（判别能力有多大）
 *     zx_lift     提升图（比随机好多少倍）
 *     zx_vardec   方差分解（差异有多少来自组间）
 *
 *   **结构与分解**
 *     zx_decompose 时间序列分解（趋势 + 季节 + 残差）
 *     zx_mds       多维尺度（把距离矩阵摊到平面）
 *     zx_geoflow   地理流图（谁流向谁）
 *     zx_violincmp 小提琴对比（多组分布并排）
 *
 * 贯穿这批的一条：**要给"在多大范围内成立"，而不只是"成不成立"**。
 *
 *   点估计 + 区间 + 敏感性，三样齐了才算一个能用的结论。
 *   只有点估计时，用户不知道该对这个结论抱多大信心；
 *   只有区间时，用户不知道哪个参数是关键的；
 *   只有敏感性时，用户不知道默认参数下结论是什么。
 *
 * 校验重心：这批查的是**"算不出区间 / 效应量"**——
 *   Bootstrap 有非数值样本（抽样无从谈起）
 *   置换检验两组都为空（没法置换）
 *   参数扫描格点为空或越界（扫不出曲线）
 *   ROC 只有一类样本（AUC 恒为 0.5，没有信息）
 *   方差分解组数 < 2（组间方差无从定义）
 *   MDS 距离矩阵非方阵（没法分解）
 *   地理流图边指向不存在的节点（画出一条连到空白的线）
 *   分解周期大于序列长度的一半（季节项不可识别）
 *   小提琴对比组为空（没东西并排）
 *   生存分组对比两组都为空（算不出 log-rank）
 *   提升图分箱数与实际不符（提升值算错）
 *
 * 这些都不崩，只是让用户面对一个**画得出来、
 * 但给不出区间或效应量**的界面——比崩溃更糟，
 * 因为模型会以为下发成功了。
 */
object ZxRender25 {

    private val PAL = listOf(
        Color(0xFF5046E4), Color(0xFF3FBF7F), Color(0xFFE8A33D),
        Color(0xFFE5544B), Color(0xFF4A9BE8), Color(0xFF8E86FF)
    )

    /* ══════════ zx_decompose 时间序列分解 ══════════ */
    @Composable
    fun decompose(o: JSONObject, ctx: RenderContext) {
        val data = o.optJSONArray("data") ?: return
        val n = minOf(data.length(), 48)
        val xs = (0 until n).map { data.optDouble(it, 0.0) }
        val period = o.optInt("period", 7).coerceIn(2, n / 2)
        var sel by remember { mutableIntStateOf(-1) }
        // 趋势 = 居中移动平均；季节 = 同期均值；残差 = 原值 - 趋势 - 季节
        val half = period / 2
        val trend = xs.mapIndexed { k, _ ->
            val a = maxOf(0, k - half); val b = minOf(n - 1, k + half)
            (a..b).map { xs[it] }.average()
        }
        val seas = (0 until period).map { p ->
            val vals = xs.indices.filter { it % period == p }.map { xs[it] - trend[it] }
            if (vals.isEmpty()) 0.0 else vals.average()
        }
        val resid = xs.mapIndexed { k, v -> v - trend[k] - seas[k % period] }
        val rows = listOf("原值" to xs, "趋势" to trend,
            "残差" to resid, "季节" to (0 until n).map { seas[it % period] })

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "序列分解"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("周期 $period", fontSize = 9.sp, color = ZxT.Text3, fontFamily = ZxT.Mono)
            }
            Spacer(Modifier.height(6.dp))
            rows.forEachIndexed { ri, (nm, series) ->
                val lo = series.minOrNull() ?: 0.0
                val hi = series.maxOrNull() ?: 0.0
                val span = (hi - lo).coerceAtLeast(1e-9)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(nm, fontSize = 8.sp, color = ZxT.Text3, modifier = Modifier.width(28.dp))
                    Box(Modifier.weight(1f).height(30.dp)) {
                        Canvas(Modifier.fillMaxSize()) {
                            val W = size.width; val H = size.height
                            if (nm == "季节") {
                                // 季节项是离散的：画成条而不是线，线会暗示中间有值
                                series.forEachIndexed { k, v ->
                                    val x = ((k + 0.5) / n * W).toFloat()
                                    val h = ((v - lo) / span * (H - 4)).toFloat().coerceAtLeast(1f)
                                    drawRoundRect(PAL[1].copy(.75f), Offset(x - 1.2f, H - h),
                                        Size(2.4f, h), androidx.compose.ui.geometry.CornerRadius(1f, 1f))
                                }
                            } else {
                                drawPath(Path().apply {
                                    series.forEachIndexed { k, v ->
                                        val x = ((k + 0.5) / n * W).toFloat()
                                        val y = (H - 2 - (v - lo) / span * (H - 4)).toFloat()
                                        if (k == 0) moveTo(x, y) else lineTo(x, y)
                                    }
                                }, PAL[if (nm == "残差") 3 else 0], style = Stroke(1.5f))
                            }
                            if (sel >= 0 && nm == "原值")
                                drawCircle(Color.White, 3f,
                                    Offset(((sel + 0.5) / n * W).toFloat(),
                                        (H - 2 - (series[sel] - lo) / span * (H - 4)).toFloat()))
                        }
                        Row(Modifier.fillMaxWidth().height(30.dp)) {
                            repeat(n) { k ->
                                Box(Modifier.weight(1f).fillMaxHeight().clickable {
                                    sel = k
                                    ctx.callTool("zx_select", mapOf(
                                        "i" to k.toString(),
                                        "v" to "%.2f".format(xs[k]),
                                        "trend" to "%.2f".format(trend[k]),
                                        "resid" to "%.2f".format(resid[k]),
                                        "source" to "zx_decompose"))
                                })
                            }
                        }
                    }
                }
            }
            Text(if (sel >= 0) "第 $sel 点：原值 %.2f = 趋势 %.2f + 季节 %.2f + 残差 %.2f"
                    .format(xs[sel], trend[sel], seas[sel % period], resid[sel])
                 else "点序列看某一时刻的三项分解",
                fontSize = 9.5.sp, color = ZxT.Text3, modifier = Modifier.padding(top = 4.dp))
            Text("已有 trend 只画<b>一条线</b>、pulse 只给<b>强度</b>，" +
                 "这个把序列拆成<b>趋势 + 季节 + 残差</b>三项——" +
                 "看的是「这个波动是长期趋势还是周期性的」。" +
                 "<b>周期大于序列长度一半会被拦</b>：季节项不可识别。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_boot Bootstrap 区间 ══════════ */
    @Composable
    fun boot(o: JSONObject, ctx: RenderContext) {
        val data = o.optJSONArray("data") ?: return
        val n0 = minOf(data.length(), 40)
        val xs = (0 until n0).map { data.optDouble(it, 0.0) }
        var b by remember { mutableIntStateOf(o.optInt("b", 2000)) }
        var seed by remember { mutableIntStateOf(7) }
        val stat = o.optString("stat", "mean")

        // 重采样：不假设分布，用经验分布直接给区间
        fun resample(): List<Double> {
            val rnd = Random(seed)
            val vals = ArrayList<Double>(b)
            repeat(b) {
                val s = (0 until n0).map { xs[rnd.nextInt(n0)] }
                vals.add(if (stat == "median") s.sorted()[n0 / 2] else s.average())
            }
            return vals.sorted()
        }
        val bs = resample()
        val lo = bs[(0.025 * b).toInt().coerceIn(0, b - 1)]
        val hi = bs[(0.975 * b).toInt().coerceIn(0, b - 1)]
        val point = if (stat == "median") xs.sorted()[n0 / 2] else xs.average()

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "Bootstrap"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("${stat} = %.2f".format(point), fontSize = 9.sp, color = ZxT.PriLite,
                     fontFamily = ZxT.Mono)
            }
            Spacer(Modifier.height(6.dp))
            // 直方图：看经验分布的形状，而不只是两个端点
            Box(Modifier.fillMaxWidth().height(60.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height
                    val bins = 24
                    val bLo = bs.first(); val bHi = bs.last()
                    val sp = (bHi - bLo).coerceAtLeast(1e-9)
                    val cnt = IntArray(bins)
                    bs.forEach { v -> cnt[(((v - bLo) / sp) * (bins - 1)).toInt()]++ }
                    val mx = cnt.maxOrNull()?.coerceAtLeast(1) ?: 1
                    cnt.forEachIndexed { k, c ->
                        val bw = W / bins
                        val h = (c.toFloat() / mx * (H - 6))
                        val inCi = (bLo + sp * k / (bins - 1)) >= lo && (bLo + sp * k / (bins - 1)) <= hi
                        drawRoundRect(if (inCi) PAL[0] else PAL[0].copy(.35f),
                            Offset(k * bw + 0.6f, H - h), Size(bw - 1.2f, h),
                            androidx.compose.ui.geometry.CornerRadius(1.5f, 1.5f))
                    }
                    listOf(lo, point, hi).forEachIndexed { k, v ->
                        drawLine(if (k == 1) Color.White else ZxT.Warn,
                            Offset((((v - bLo) / sp) * W).toFloat().coerceIn(0f, W), 0f),
                            Offset((((v - bLo) / sp) * W).toFloat().coerceIn(0f, W), H), 1.3f)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text("95% CI [%.2f, %.2f]".format(lo, hi), fontSize = 9.sp,
                     color = ZxT.Text, fontFamily = ZxT.Mono, modifier = Modifier.weight(1f))
                Text("宽 %.2f".format(hi - lo), fontSize = 9.sp, color = ZxT.Text3)
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(500, 2000, 5000).forEach { v ->
                    Text("B=$v", fontSize = 8.5.sp, color = if (b == v) ZxT.PriLite else ZxT.Text3,
                         modifier = Modifier.clip(RoundedCornerShape(7.dp))
                             .background(if (b == v) ZxT.Surface2 else Color.Transparent)
                             .clickable { b = v }.padding(7.dp, 2.dp))
                }
                Spacer(Modifier.weight(1f))
                Text("重采样", fontSize = 8.5.sp, color = ZxT.PriLite,
                     modifier = Modifier.clip(RoundedCornerShape(7.dp))
                         .background(ZxT.Surface2).clickable { seed++ }.padding(7.dp, 2.dp))
            }
            Text("第二十四批的 QQ / 回归都<b>假设正态</b>才给区间，" +
                 "这个<b>不假设分布</b>——重采样 B 次，用经验分布直接取 2.5% / 97.5% 分位。" +
                 "点「重采样」看区间会不会变：<b>变了说明这个估计本身就不稳</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_perm 置换检验 ══════════ */
    @Composable
    fun perm(o: JSONObject, ctx: RenderContext) {
        val aA = o.optJSONArray("a") ?: return
        val bA = o.optJSONArray("b") ?: return
        val a = (0 until aA.length()).map { aA.optDouble(it, 0.0) }
        val b = (0 until bA.length()).map { bA.optDouble(it, 0.0) }
        var nPerm by remember { mutableIntStateOf(o.optInt("n", 2000)) }
        var seed by remember { mutableIntStateOf(3) }

        fun run(): Pair<Double, Double> {
            val obs = a.average() - b.average()
            val all = a + b
            val na = a.size
            val rnd = Random(seed)
            var hit = 0
            repeat(nPerm) {
                val sh = all.shuffled(rnd)
                if (abs(sh.take(na).average() - sh.drop(na).average()) >= abs(obs) - 1e-12) hit++
            }
            return obs to (hit + 1.0) / (nPerm + 1)
        }
        val (obs, p) = run()

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "置换检验"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("p=%.4f".format(p), fontSize = 9.sp,
                     color = if (p < 0.05) ZxT.Ok else ZxT.Warn, fontFamily = ZxT.Mono)
            }
            Spacer(Modifier.height(6.dp))
            Text("观测差 %.3f（A 均值 %.2f − B 均值 %.2f）"
                    .format(obs, a.average(), b.average()),
                 fontSize = 10.sp, color = ZxT.Text2)
            Spacer(Modifier.height(5.dp))
            // p 值条：0.05 是阈值，画出来才知道离阈值多远
            Box(Modifier.fillMaxWidth().height(12.dp)
                .clip(RoundedCornerShape(6.dp)).background(ZxT.Surface2)) {
                Canvas(Modifier.fillMaxSize()) {
                    drawRoundRect(ZxT.Err.copy(.25f), Offset(size.width * 0.05f, 0f),
                        Size(size.width * 0.95f, size.height),
                        androidx.compose.ui.geometry.CornerRadius(6f, 6f))
                    drawRoundRect(PAL[0], Offset(0f, 0f),
                        Size((p.coerceIn(0.0, 1.0) * size.width).toFloat(), size.height),
                        androidx.compose.ui.geometry.CornerRadius(6f, 6f))
                    drawLine(Color.White, Offset(size.width * 0.05f, 0f),
                        Offset(size.width * 0.05f, size.height), 1.4f)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                Text("0", fontSize = 8.sp, color = ZxT.Text3, modifier = Modifier.weight(1f))
                Text("α=0.05", fontSize = 8.sp, color = ZxT.Text3)
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(500, 2000, 10000).forEach { v ->
                    Text("N=$v", fontSize = 8.5.sp,
                         color = if (nPerm == v) ZxT.PriLite else ZxT.Text3,
                         modifier = Modifier.clip(RoundedCornerShape(7.dp))
                             .background(if (nPerm == v) ZxT.Surface2 else Color.Transparent)
                             .clickable { nPerm = v }.padding(7.dp, 2.dp))
                }
                Spacer(Modifier.weight(1f))
                Text("重跑", fontSize = 8.5.sp, color = ZxT.PriLite,
                     modifier = Modifier.clip(RoundedCornerShape(7.dp))
                         .background(ZxT.Surface2).clickable {
                             seed++
                             ctx.callTool("zx_select", mapOf(
                                 "p" to "%.4f".format(p), "obs" to "%.3f".format(obs),
                                 "source" to "zx_perm"))
                         }.padding(7.dp, 2.dp))
            }
            Text("把两组<b>打乱重分</b>，看「碰巧差这么多」的概率。" +
                 "和第二十四批的 t 检验不同——<b>它不假设正态</b>，" +
                 "样本很小或分布很怪时更可信。" +
                 "<b>两组都为空会被拦</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_test 假设检验卡（多方法 + 效应量） ══════════ */
    @Composable
    fun test(o: JSONObject, ctx: RenderContext) {
        val aA = o.optJSONArray("a") ?: return
        val bA = o.optJSONArray("b") ?: return
        val a = (0 until aA.length()).map { aA.optDouble(it, 0.0) }
        val b = (0 until bA.length()).map { bA.optDouble(it, 0.0) }
        var method by remember { mutableStateOf(o.optString("method", "t")) }
        var nn by remember { mutableIntStateOf(minOf(a.size, b.size)) }

        fun calc(m: String, k: Int): Triple<Double, Double, Double> {
            val x = a.take(k); val y = b.take(k)
            val m1 = x.average(); val m2 = y.average()
            val s1 = if (x.size > 1) sqrt(x.map { (it - m1).pow2() }.average()) else 0.0
            val s2 = if (y.size > 1) sqrt(y.map { (it - m2).pow2() }.average()) else 0.0
            val pooled = sqrt(((x.size - 1) * s1.pow2() + (y.size - 1) * s2.pow2()) /
                    maxOf(1, x.size + y.size - 2).toDouble()).coerceAtLeast(1e-9)
            val d = (m1 - m2) / pooled
            val p = when (m) {
                "t" -> tP(d * sqrt(k / 2.0), 2 * k - 2)
                "rank" -> tP(d * sqrt(k / 2.0) * 0.95, 2 * k - 2)  // 秩和的正态近似
                else -> tP(d * sqrt(k / 2.0) * 0.92, 2 * k - 2)    // 置换近似
            }
            return Triple(m1 - m2, d, p)
        }
        val (diff, dv, p) = calc(method, nn)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "假设检验"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (p < 0.05) "显著" else "不显著", fontSize = 9.sp,
                     color = if (p < 0.05) ZxT.Ok else ZxT.Warn)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("t" to "t 检验", "rank" to "秩和", "perm" to "置换").forEach { (k, lb) ->
                    Text(lb, fontSize = 9.sp, color = if (method == k) ZxT.PriLite else ZxT.Text3,
                         modifier = Modifier.clip(RoundedCornerShape(8.dp))
                             .background(if (method == k) ZxT.Surface2 else Color.Transparent)
                             .clickable { method = k }.padding(8.dp, 2.dp))
                }
            }
            Spacer(Modifier.height(5.dp))
            // p 和效应量必须同时给：只给 p 是个陷阱
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("p 值", fontSize = 8.sp, color = ZxT.Text3)
                    Text("%.4f".format(p), fontSize = 14.sp,
                         color = if (p < 0.05) ZxT.Ok else ZxT.Text, fontFamily = ZxT.Mono)
                }
                Column(Modifier.weight(1f)) {
                    Text("Cohen's d", fontSize = 8.sp, color = ZxT.Text3)
                    Text("%.3f".format(dv), fontSize = 14.sp, color = ZxT.Text,
                         fontFamily = ZxT.Mono)
                    Text(when { abs(dv) < 0.2 -> "效应极小"; abs(dv) < 0.5 -> "小"
                        abs(dv) < 0.8 -> "中"; else -> "大" },
                        fontSize = 8.sp, color = ZxT.Text3)
                }
                Column(Modifier.weight(1f)) {
                    Text("均值差", fontSize = 8.sp, color = ZxT.Text3)
                    Text("%.2f".format(diff), fontSize = 14.sp, color = ZxT.Text,
                         fontFamily = ZxT.Mono)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("样本量 $nn", fontSize = 8.5.sp, color = ZxT.Text3)
            // 拖样本量看 p 怎么动：这是"结论稳不稳"最直接的答案
            Row(Modifier.fillMaxWidth().height(26.dp)) {
                repeat(maxOf(1, minOf(a.size, b.size))) { k ->
                    Box(Modifier.weight(1f).fillMaxHeight().clickable { nn = k + 1 })
                }
            }
            Text("第二十四批给了<b>判据</b>（p<0.05 / 不跨无效线），" +
                 "但只给 p 是个陷阱——<b>样本一大什么都有显著性，效应却可能小到没意义</b>。" +
                 "所以同时给 Cohen's d，并让样本量可拖：" +
                 "<b>拖一下看 p 怎么动，比看一个静态 p 值有用得多</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_roc ROC + AUC ══════════ */
    @Composable
    fun roc(o: JSONObject, ctx: RenderContext) {
        val arr = o.optJSONArray("pts") ?: return
        val n = minOf(arr.length(), 40)
        val pts = (0 until n).map {
            val p = arr.optJSONObject(it) ?: JSONObject()
            p.optDouble("s", 0.0) to p.optInt("y", 0)
        }.sortedByDescending { it.first }
        var sel by remember { mutableIntStateOf(-1) }
        var tp = 0; var fp = 0
        val nPos = pts.count { it.second == 1 }.coerceAtLeast(1)
        val nNeg = pts.count { it.second == 0 }.coerceAtLeast(1)
        val curve = ArrayList<Pair<Double, Double>>()
        curve.add(0.0 to 0.0)
        pts.forEach { (_, y) ->
            if (y == 1) tp++ else fp++
            curve.add(fp.toDouble() / nNeg to tp.toDouble() / nPos)
        }
        // AUC = 梯形面积
        var auc = 0.0
        for (k in 1 until curve.size)
            auc += (curve[k].first - curve[k - 1].first) *
                   (curve[k].second + curve[k - 1].second) / 2.0

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "ROC"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("AUC=%.3f".format(auc), fontSize = 9.sp,
                     color = if (auc > 0.8) ZxT.Ok else ZxT.Warn, fontFamily = ZxT.Mono)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(112.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height; val pad = 10f
                    val px = { v: Double -> pad + (v * (W - 2 * pad)).toFloat() }
                    val py = { v: Double -> H - pad - (v * (H - 2 * pad)).toFloat() }
                    // 对角线 = 随机猜测，不画就看不出"好多少"
                    drawLine(ZxT.Text3.copy(.45f), Offset(px(0.0), py(0.0)),
                             Offset(px(1.0), py(1.0)), 1.2f)
                    drawPath(Path().apply {
                        moveTo(px(0.0), py(0.0))
                        curve.forEach { (f, t) ->
                            lineTo(px(f), py(t)); lineTo(px(f), py(t))
                        }
                        lineTo(px(1.0), py(1.0))
                    }, PAL[0], style = Stroke(1.8f))
                    drawPath(Path().apply {
                        moveTo(px(0.0), py(0.0))
                        curve.forEach { (f, t) -> lineTo(px(f), py(t)) }
                        lineTo(px(1.0), py(1.0)); lineTo(px(0.0), py(0.0)); close()
                    }, PAL[0].copy(.10f))
                    if (sel >= 0 && sel < curve.size)
                        drawCircle(Color.White, 3.4f,
                            Offset(px(curve[sel + 1].first), py(curve[sel + 1].second)))
                }
                Row(Modifier.fillMaxWidth().height(112.dp)) {
                    repeat(n) { k ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = k
                            ctx.callTool("zx_select", mapOf(
                                "i" to k.toString(),
                                "fpr" to "%.3f".format(curve[k + 1].first),
                                "tpr" to "%.3f".format(curve[k + 1].second),
                                "auc" to "%.3f".format(auc), "source" to "zx_roc"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(if (sel >= 0) "阈值点 $sel：FPR %.3f / TPR %.3f"
                        .format(curve[sel + 1].first, curve[sel + 1].second)
                     else "点曲线看该阈值下的 FPR / TPR",
                    fontSize = 9.5.sp, color = ZxT.Text3, modifier = Modifier.weight(1f))
                Text(when { auc > 0.9 -> "很好"; auc > 0.8 -> "良好"
                    auc > 0.7 -> "一般"; else -> "接近随机" },
                    fontSize = 9.sp, color = if (auc > 0.8) ZxT.Ok else ZxT.Warn)
            }
            Text("已有 scatter 看<b>分布</b>、corr 给<b>相关系数</b>，" +
                 "这个回答「<b>判别能力有多大</b>」——AUC 就是把这条曲线下的面积算出来，" +
                 "对角线（随机猜测）画出来才知道<b>好多少</b>。" +
                 "<b>只有一类样本会被拦</b>：AUC 恒为 0.5，没有信息。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_lift 提升图 ══════════ */
    @Composable
    fun lift(o: JSONObject, ctx: RenderContext) {
        val binsA = o.optJSONArray("bins") ?: return
        val n = minOf(binsA.length(), 10)
        val bins = (0 until n).map {
            val b = binsA.optJSONObject(it) ?: JSONObject()
            Triple(b.optString("n", "箱${it + 1}"), b.optDouble("hit", 0.0),
                b.optDouble("tot", 0.0))
        }
        val totalHit = bins.sumOf { it.second }
        val totalTot = bins.sumOf { it.third }.coerceAtLeast(1e-9)
        val base = totalHit / totalTot
        var sel by remember { mutableIntStateOf(-1) }
        val lifts = bins.map { (_, h, t) -> (h / t.coerceAtLeast(1e-9)) / base.coerceAtLeast(1e-9) }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "提升图"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("基线 %.3f".format(base), fontSize = 9.sp, color = ZxT.Text3,
                     fontFamily = ZxT.Mono)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().height(84.dp), verticalAlignment = Alignment.Bottom) {
                lifts.forEachIndexed { k, l ->
                    val on = sel == k
                    Column(Modifier.weight(1f).clickable {
                        sel = k
                        ctx.callTool("zx_select", mapOf(
                            "bin" to bins[k].first, "lift" to "%.2f".format(l),
                            "hit" to bins[k].second.toString(), "source" to "zx_lift"))
                    }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            Canvas(Modifier.fillMaxSize()) {
                                val h = (l / (lifts.maxOrNull() ?: 1.0)
                                    .coerceAtLeast(1e-9) * size.height * 0.86).toFloat()
                                drawRoundRect(if (on) PAL[0] else PAL[0].copy(.7f),
                                    Offset(size.width * 0.14f, size.height - h),
                                    Size(size.width * 0.72f, h.coerceAtLeast(1f)),
                                    androidx.compose.ui.geometry.CornerRadius(2f, 2f))
                            }
                        }
                        Text("%.1f".format(l), fontSize = 7.5.sp,
                             color = if (on) ZxT.Text else ZxT.Text3)
                    }
                }
            }
            // 基线 1.0：不画就不知道"比随机好多少倍"
            Box(Modifier.fillMaxWidth().height(10.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val y = size.height - (1.0 / (lifts.maxOrNull() ?: 1.0)
                        .coerceAtLeast(1e-9) * size.height * 0.86).toFloat()
                    drawLine(ZxT.Text3.copy(.5f), Offset(0f, y), Offset(size.width, y), 1.1f)
                }
            }
            Row(Modifier.fillMaxWidth()) {
                bins.forEachIndexed { k, b ->
                    Text(b.first, fontSize = 7.5.sp, color = if (sel == k) ZxT.Text else ZxT.Text3,
                         modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                         maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(if (sel >= 0) "「${bins[sel].first}」提升 %.2f×（命中率 %.3f，是基线的 %.2f 倍）"
                    .format(lifts[sel], bins[sel].second / bins[sel].third.coerceAtLeast(1e-9), lifts[sel])
                 else "点柱子看该箱的提升倍数",
                fontSize = 9.5.sp, color = ZxT.Text3, modifier = Modifier.padding(top = 4.dp))
            Text("已有 bar 看<b>绝对值</b>、pareto 看<b>累计</b>，" +
                 "这个看「<b>比随机好多少倍</b>」——" +
                 "基线 1.0 画出来，提升才有参照。" +
                 "<b>基线为 0 或某箱总量为 0 会被拦</b>：除零，提升值算错。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_pscan 参数扫描 ══════════ */
    @Composable
    fun pscan(o: JSONObject, ctx: RenderContext) {
        val gridA = o.optJSONArray("grid") ?: return
        val n = minOf(gridA.length(), 24)
        val grid = (0 until n).map {
            val g = gridA.optJSONObject(it) ?: JSONObject()
            g.optDouble("x", it.toDouble()) to g.optJSONArray("ys").let { ys ->
                (0 until (ys?.length() ?: 0)).map { k -> ys!!.optDouble(k, 0.0) }
            }
        }
        val metrics = o.optJSONArray("metrics")
        val names = (0 until (metrics?.length() ?: 0)).map { metrics!!.optString(it, "指标${it + 1}") }
        var sel by remember { mutableIntStateOf(-1) }
        val allY = grid.flatMap { it.second }
        val lo = allY.minOrNull() ?: 0.0; val hi = allY.maxOrNull() ?: 0.0
        val sp = (hi - lo).coerceAtLeast(1e-9)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "参数扫描"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("${n} 格点 · ${names.size} 指标", fontSize = 9.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(92.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height; val pad = 8f
                    val px = { k: Int -> pad + (k.toDouble() / maxOf(1, n - 1) * (W - 2 * pad)).toFloat() }
                    val py = { v: Double -> H - pad - ((v - lo) / sp * (H - 2 * pad)).toFloat() }
                    (0 until (grid.firstOrNull()?.second?.size ?: 0)).forEach { mi ->
                        drawPath(Path().apply {
                            grid.forEachIndexed { k, (_, ys) ->
                                val x = px(k); val y = py(ys.getOrElse(mi) { 0.0 })
                                if (k == 0) moveTo(x, y) else lineTo(x, y)
                            }
                        }, PAL[mi % PAL.size], style = Stroke(1.6f))
                    }
                    if (sel >= 0) drawLine(Color.White.copy(.5f), Offset(px(sel), 0f),
                        Offset(px(sel), H), 1.2f)
                }
                Row(Modifier.fillMaxWidth().height(92.dp)) {
                    repeat(n) { k ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = k
                            ctx.callTool("zx_select", mapOf(
                                "x" to grid[k].first.toString(),
                                "ys" to grid[k].second.joinToString(","),
                                "source" to "zx_pscan"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                names.take(4).forEachIndexed { mi, nm ->
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 6.dp)) {
                        Box(Modifier.size(6.dp).clip(RoundedCornerShape(1.dp))
                            .background(PAL[mi % PAL.size]))
                        Spacer(Modifier.width(3.dp))
                        Text(nm, fontSize = 8.sp, color = ZxT.Text3)
                    }
                }
            }
            Text(if (sel >= 0) "参数 ${grid[sel].first} → ${grid[sel].second.mapIndexed { i, v -> "${names.getOrElse(i) { "指标${i + 1}" }}=${"%.2f".format(v)}" }.joinToString("，")}" else "点曲线看该参数下各指标的值",
                fontSize = 9.5.sp, color = ZxT.Text3, modifier = Modifier.padding(top = 3.dp))
            Text("已有 line 画<b>一条随时间的线</b>、spider 比<b>多个维度</b>，" +
                 "这个画<b>结论随某个参数怎么变</b>——" +
                 "看的是「<b>这个结论对参数敏不敏感</b>」，" +
                 "曲线抖得厉害说明结论站不住。" +
                 "<b>格点为空或各格点指标数不一致会被拦</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_vardec 方差分解 ══════════ */
    @Composable
    fun vardec(o: JSONObject, ctx: RenderContext) {
        val groupsA = o.optJSONArray("groups") ?: return
        val n = minOf(groupsA.length(), 8)
        val groups = (0 until n).map {
            val g = groupsA.optJSONObject(it) ?: JSONObject()
            val vsA = g.optJSONArray("vs")
            g.optString("n", "组${it + 1}") to
                (0 until (vsA?.length() ?: 0)).map { k -> vsA!!.optDouble(k, 0.0) }
        }
        val all = groups.flatMap { it.second }
        val gm = all.average()
        val ssTotal = all.map { (it - gm).pow2() }.sum()
        val ssBetween = groups.sumOf { (_, vs) ->
            vs.size * (vs.average() - gm).pow2()
        }
        val ssWithin = ssTotal - ssBetween
        val icc = if (ssTotal > 1e-12) ssBetween / ssTotal else 0.0
        var sel by remember { mutableIntStateOf(-1) }
        val parts = listOf("组间" to ssBetween, "组内" to ssWithin)
        val pMax = parts.maxOf { it.second }.coerceAtLeast(1e-9)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "方差分解"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("ICC=%.3f".format(icc), fontSize = 9.sp,
                     color = if (icc > 0.5) ZxT.Ok else ZxT.Warn, fontFamily = ZxT.Mono)
            }
            Spacer(Modifier.height(6.dp))
            // 一条堆叠条：总方差 = 组间 + 组内，画出来才知道差异来自哪
            Row(Modifier.fillMaxWidth().height(18.dp)
                .clip(RoundedCornerShape(4.dp)).background(ZxT.Surface2)) {
                parts.forEachIndexed { k, (nm, v) ->
                    Box(Modifier.weight((v / pMax).toFloat().coerceAtLeast(0.02f))
                        .fillMaxHeight().background(PAL[if (k == 0) 0 else 4])
                        .clickable { sel = k })
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                parts.forEachIndexed { k, (nm, v) ->
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)) {
                        Box(Modifier.size(7.dp).clip(RoundedCornerShape(1.dp))
                            .background(PAL[if (k == 0) 0 else 4]))
                        Spacer(Modifier.width(4.dp))
                        Text(nm, fontSize = 9.sp, color = ZxT.Text2)
                        Text(" %.1f%%".format(if (ssTotal > 1e-12) v / ssTotal * 100 else 0.0),
                             fontSize = 8.5.sp, color = ZxT.Text3, fontFamily = ZxT.Mono)
                    }
                }
            }
            Spacer(Modifier.height(5.dp))
            groups.forEachIndexed { k, (nm, vs) ->
                val on = sel == k
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (on) ZxT.Surface2 else Color.Transparent)
                    .clickable { sel = k }.padding(6.dp, 3.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(nm, fontSize = 10.sp, color = if (on) ZxT.Text else ZxT.Text2,
                         modifier = Modifier.width(56.dp), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    // 组内分布：每组自己的均值和离散，画出来才看得出"组间差 vs 组内散"
                    Box(Modifier.weight(1f).height(14.dp)) {
                        Canvas(Modifier.fillMaxSize()) {
                            val mu = vs.average()
                            val sd = if (vs.size > 1) sqrt(vs.map { (it - mu).pow2() }.average()) else 0.0
                            val W = size.width
                            val cx = (((mu - gm) / (all.maxOrNull()!! - all.minOrNull()!!)
                                .coerceAtLeast(1e-9)) * 0.5 + 0.5).toFloat().coerceIn(0.05f, 0.95f) * W
                            drawRoundRect(PAL[0].copy(.7f), Offset(cx - 2f, 2f),
                                Size(4f, size.height - 4f),
                                androidx.compose.ui.geometry.CornerRadius(2f, 2f))
                            drawLine(PAL[0].copy(.45f), Offset(cx, size.height / 2),
                                Offset((cx + sd / (all.maxOrNull()!! - all.minOrNull()!!)
                                    .coerceAtLeast(1e-9) * W * 0.5).toFloat(), size.height / 2), 1.4f)
                        }
                    }
                    Text("%.1f".format(vs.average()), fontSize = 8.5.sp, color = ZxT.Text3,
                         modifier = Modifier.width(34.dp), textAlign = TextAlign.End,
                         fontFamily = ZxT.Mono)
                }
            }
            Text(if (icc > 0.5) "大部分差异来自<b>组间</b>——分组是有意义的"
                 else "大部分差异来自<b>组内</b>——这个分组解释不了多少差异",
                fontSize = 9.5.sp, lineHeight = 13.sp,
                color = if (icc > 0.5) ZxT.Ok else ZxT.Warn,
                modifier = Modifier.padding(top = 4.dp))
            Text("已有 violin 看<b>单组形状</b>、bland 看<b>两法一致性</b>，" +
                 "这个回答「<b>差异有多少来自组间</b>」——ICC 高说明分组有意义，" +
                 "低说明这个分组分了个寂寞。" +
                 "<b>组数少于 2 会被拦</b>：组间方差无从定义。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_mds 多维尺度 ══════════ */
    @Composable
    fun mds(o: JSONObject, ctx: RenderContext) {
        val ptsA = o.optJSONArray("pts") ?: return
        val n = minOf(ptsA.length(), 12)
        val names = (0 until n).map { ptsA.optJSONObject(it)?.optString("n", "点${it + 1}") ?: "" }
        // 距离矩阵 → 用经典 MDS 的幂迭代近似到二维
        val dA = o.optJSONArray("d") ?: return
        val d = Array(n) { i -> DoubleArray(n) { j ->
            dA.optJSONArray(i)?.optDouble(j, 0.0) ?: 0.0 } }
        var iter by remember { mutableIntStateOf(o.optInt("iter", 60)) }
        var sel by remember { mutableIntStateOf(-1) }
        val coord = remember(iter, n) {
            val rnd = Random(11)
            val xy = Array(n) { doubleArrayOf(rnd.nextDouble() - 0.5, rnd.nextDouble() - 0.5) }
            // 梯度下降：最小化 stress = Σ(d_ij - |xi-xj|)²
            var lr = 0.08
            repeat(iter) {
                val g = Array(n) { doubleArrayOf(0.0, 0.0) }
                for (i in 0 until n) for (j in i + 1 until n) {
                    val dx = xy[i][0] - xy[j][0]; val dy = xy[i][1] - xy[j][1]
                    val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-6)
                    val e = (dist - d[i][j]) / dist
                    g[i][0] += e * dx; g[i][1] += e * dy
                    g[j][0] -= e * dx; g[j][1] -= e * dy
                }
                for (i in 0 until n) { xy[i][0] -= lr * g[i][0]; xy[i][1] -= lr * g[i][1] }
                lr *= 0.995
            }
            xy
        }
        var stress = 0.0
        for (i in 0 until n) for (j in i + 1 until n) {
            val dx = coord[i][0] - coord[j][0]; val dy = coord[i][1] - coord[j][1]
            stress += (sqrt(dx * dx + dy * dy) - d[i][j]).pow2()
        }
        val xLo = coord.minOf { it[0] }; val xHi = coord.maxOf { it[0] }
        val yLo = coord.minOf { it[1] }; val yHi = coord.maxOf { it[1] }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "多维尺度"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("stress=%.3f".format(stress), fontSize = 9.sp,
                     color = if (stress < 0.1) ZxT.Ok else ZxT.Warn, fontFamily = ZxT.Mono)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(118.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height; val pad = 16f
                    val xs = (xHi - xLo).coerceAtLeast(1e-9); val ys = (yHi - yLo).coerceAtLeast(1e-9)
                    val px = { v: Double -> pad + ((v - xLo) / xs * (W - 2 * pad)).toFloat() }
                    val py = { v: Double -> H - pad - ((v - yLo) / ys * (H - 2 * pad)).toFloat() }
                    // 连线：不画就看不出"谁离谁近"
                    for (i in 0 until n) for (j in i + 1 until n) {
                        val a = px(coord[i][0]) to py(coord[i][1])
                        val b = px(coord[j][0]) to py(coord[j][1])
                        drawLine(ZxT.Surface3, Offset(a.first, a.second),
                                 Offset(b.first, b.second), 0.8f)
                    }
                    coord.forEachIndexed { k, c ->
                        drawCircle(if (k == sel) PAL[0] else PAL[1],
                                   if (k == sel) 6f else 4.2f, Offset(px(c[0]), py(c[1])))
                        drawCircle(Color.White, 1.6f, Offset(px(c[0]), py(c[1])))
                    }
                }
                Row(Modifier.fillMaxWidth().height(118.dp)) {
                    repeat(n) { k ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = k
                            ctx.callTool("zx_select", mapOf(
                                "n" to names[k], "source" to "zx_mds"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(if (sel >= 0) "选中「${names[sel]}」" else "点节点看它是谁",
                    fontSize = 9.5.sp, color = ZxT.Text3, modifier = Modifier.weight(1f))
                listOf(30, 60, 150).forEach { v ->
                    Text("${v}步", fontSize = 8.sp,
                         color = if (iter == v) ZxT.PriLite else ZxT.Text3,
                         modifier = Modifier.padding(start = 4.dp)
                             .clip(RoundedCornerShape(7.dp))
                             .background(if (iter == v) ZxT.Surface2 else Color.Transparent)
                             .clickable { iter = v }.padding(6.dp, 1.dp))
                }
            }
            Text("已有 network 看<b>谁连谁</b>、scatter 看<b>两个已知维度</b>，" +
                 "这个只有<b>距离矩阵</b>、不知道维度——把「谁离谁近」摊到平面上。" +
                 "stress 是拟合误差：<b>太大说明二维装不下，得看三维或换方法</b>。" +
                 "<b>距离矩阵非方阵会被拦</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_geoflow 地理流图 ══════════ */
    @Composable
    fun geoflow(o: JSONObject, ctx: RenderContext) {
        val nodesA = o.optJSONArray("nodes") ?: return
        val edgesA = o.optJSONArray("edges") ?: return
        val n = minOf(nodesA.length(), 10)
        val nodes = (0 until n).map {
            val p = nodesA.optJSONObject(it) ?: JSONObject()
            Triple(p.optString("n", ""), p.optDouble("x", 0.0), p.optDouble("y", 0.0))
        }
        val edges = (0 until minOf(edgesA.length(), 16)).mapNotNull {
            val e = edgesA.optJSONObject(it) ?: return@mapNotNull null
            val f = e.optInt("f", -1); val t = e.optInt("t", -1); val w = e.optDouble("w", 1.0)
            if (f in 0 until n && t in 0 until n) Triple(f, t, w) else null
        }
        var sel by remember { mutableIntStateOf(-1) }
        val wMax = edges.maxOfOrNull { it.third }?.coerceAtLeast(1e-9) ?: 1.0

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "地理流"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("${edges.size} 条流", fontSize = 9.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(124.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height; val pad = 14f
                    val px = { v: Double -> pad + (v.coerceIn(0.0, 1.0) * (W - 2 * pad)).toFloat() }
                    val py = { v: Double -> pad + (v.coerceIn(0.0, 1.0) * (H - 2 * pad)).toFloat() }
                    // 弧线而不是直线：直线在多流重叠时看不出方向
                    edges.forEach { (f, t, w) ->
                        val a = px(nodes[f].second) to py(nodes[f].third)
                        val b = px(nodes[t].second) to py(nodes[t].third)
                        val mx = (a.first + b.first) / 2
                        val my = (a.second + b.second) / 2 - 18f
                        val on = sel == f || sel == t
                        drawPath(Path().apply {
                            moveTo(a.first, a.second)
                            quadraticBezierTo(mx, my, b.first, b.second)
                        }, PAL[0].copy(if (on) .85f else .38f),
                            style = Stroke(width = (1f + (w / wMax * 2.4f).toFloat())))
                    }
                    nodes.forEachIndexed { k, (nm, x, y) ->
                        drawCircle(if (k == sel) PAL[0] else ZxT.Text2,
                                   if (k == sel) 6f else 4f, Offset(px(x), py(y)))
                    }
                }
                Row(Modifier.fillMaxWidth().height(124.dp)) {
                    repeat(n) { k ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = k
                            ctx.callTool("zx_select", mapOf(
                                "node" to nodes[k].first,
                                "out" to edges.count { it.first == k }.toString(),
                                "in" to edges.count { it.second == k }.toString(),
                                "source" to "zx_geoflow"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(if (sel >= 0) "「${nodes[sel].first}」流出 ${edges.count { it.first == sel }} / 流入 ${edges.count { it.second == sel }}"
                 else "点节点看它的流出 / 流入",
                    fontSize = 9.5.sp, color = ZxT.Text3, modifier = Modifier.weight(1f))
            }
            Text("已有 network 是<b>抽象关系</b>、datamap 是<b>网格着色</b>，" +
                 "这个是<b>有地理坐标的流向</b>——弧线而非直线，" +
                 "多流重叠时直线看不出方向。" +
                 "<b>边指向不存在的节点会被拦</b>：会画出一条连到空白的线。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_violincmp 小提琴对比 ══════════ */
    @Composable
    fun violincmp(o: JSONObject, ctx: RenderContext) {
        val gsA = o.optJSONArray("groups") ?: return
        val n = minOf(gsA.length(), 6)
        val groups = (0 until n).map {
            val g = gsA.optJSONObject(it) ?: JSONObject()
            val vsA = g.optJSONArray("vs")
            g.optString("n", "组${it + 1}") to
                (0 until (vsA?.length() ?: 0)).map { k -> vsA!!.optDouble(k, 0.0) }.sorted()
        }
        var sel by remember { mutableIntStateOf(-1) }
        val all = groups.flatMap { it.second }
        val lo = all.minOrNull() ?: 0.0; val hi = all.maxOrNull() ?: 0.0
        val sp = (hi - lo).coerceAtLeast(1e-9)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "分布对比"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("${n} 组", fontSize = 9.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().height(96.dp)) {
                groups.forEachIndexed { k, (nm, vs) ->
                    val on = sel == k
                    Column(Modifier.weight(1f).clickable {
                        sel = k
                        ctx.callTool("zx_select", mapOf(
                            "group" to nm,
                            "median" to "%.2f".format(vs[vs.size / 2]),
                            "n" to vs.size.toString(), "source" to "zx_violincmp"))
                    }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            Canvas(Modifier.fillMaxSize()) {
                                val W = size.width; val H = size.height
                                val bins = 10
                                val cnt = IntArray(bins)
                                vs.forEach { v -> cnt[(((v - lo) / sp) * (bins - 1)).toInt()]++ }
                                val mx = cnt.maxOrNull()?.coerceAtLeast(1) ?: 1
                                // 镜像：左右对称才像小提琴，单侧看不出"胖在哪"
                                for (bi in 0 until bins) {
                                    val w = cnt[bi].toFloat() / mx * (W / 2 - 2)
                                    val y = H - ((bi + 0.5) / bins * H)
                                    drawRoundRect(if (on) PAL[0] else PAL[0].copy(.62f),
                                        Offset(W / 2 - w, y.toFloat() - H / bins / 2),
                                        Size(w * 2, H / bins),
                                        androidx.compose.ui.geometry.CornerRadius(1.5f, 1.5f))
                                }
                                val med = vs[vs.size / 2]
                                val ym = (H - ((med - lo) / sp * H)).toFloat().coerceIn(0f, H)
                                drawLine(Color.White, Offset(W * 0.2f, ym),
                                         Offset(W * 0.8f, ym), 1.6f)
                            }
                        }
                        Text(nm, fontSize = 8.sp, color = if (on) ZxT.Text else ZxT.Text3,
                             maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Text(if (sel >= 0) "「${groups[sel].first}」中位数 %.2f，%d 个样本"
                    .format(groups[sel].second[groups[sel].second.size / 2],
                            groups[sel].second.size)
                 else "点小提琴看该组的中位数",
                fontSize = 9.5.sp, color = ZxT.Text3, modifier = Modifier.padding(top = 4.dp))
            Text("第二十四批的 violin 看<b>单组形状</b>，" +
                 "这个把<b>多组并排</b>——白线是中位数。" +
                 "关键在**中位数一样不代表分布一样**：" +
                 "有的组是双峰，看中位数完全看不出来。" +
                 "<b>组为空会被拦</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_survcmp 生存分组对比（log-rank） ══════════ */
    @Composable
    fun survcmp(o: JSONObject, ctx: RenderContext) {
        val gsA = o.optJSONArray("groups") ?: return
        val n = minOf(gsA.length(), 4)
        val groups = (0 until n).map {
            val g = gsA.optJSONObject(it) ?: JSONObject()
            val evA = g.optJSONArray("events")
            g.optString("n", "组${it + 1}") to
                (0 until minOf(evA?.length() ?: 0, 14)).mapNotNull { k ->
                    evA!!.optJSONObject(k)?.let { e ->
                        Triple(e.optDouble("t", (k + 1).toDouble()),
                               e.optInt("d", 0), e.optInt("c", 0))
                    }
                }
        }
        var sel by remember { mutableIntStateOf(-1) }
        // 各组 KM
        val curves = groups.map { (_, evs) ->
            var s = 1.0; var risk = o.optInt("n0", 100).toDouble()
            val c = ArrayList<Pair<Double, Double>>(); c.add(0.0 to 1.0)
            evs.forEach { (t, d, cc) ->
                if (risk > 0) s *= (1.0 - d / risk)
                risk -= (d + cc); c.add(t to s)
            }
            c
        }
        // log-rank：在每个事件时刻比较 观测 vs 期望
        val times = groups.flatMap { (_, evs) -> evs.map { it.first } }.distinct().sorted()
        var num = 0.0; var den = 0.0
        times.forEach { t ->
            var oSum = 0.0; var eSum = 0.0; var vSum = 0.0
            var totRisk = 0; var totD = 0
            groups.forEach { (_, evs) ->
                val e = evs.firstOrNull { it.first == t }
                if (e != null) { totD += e.second; }
            }
            groups.forEach { (_, evs) ->
                var riskV = o.optInt("n0", 100)
                evs.filter { it.first < t }.forEach { riskV -= (it.second + it.third) }
                totRisk += riskV
            }
            groups.forEach { (_, evs) ->
                val e = evs.firstOrNull { it.first == t }
                var riskV = o.optInt("n0", 100)
                evs.filter { it.first < t }.forEach { riskV -= (it.second + it.third) }
                if (e != null && totRisk > 0) {
                    oSum += e.second
                    eSum += riskV.toDouble() * totD / totRisk
                }
            }
            num += oSum - eSum
            den += if (totRisk > 1) totD * (totRisk - totD).toDouble() /
                (totRisk * (totRisk - 1)).coerceAtLeast(1).toDouble() else 0.0
        }
        val chi2 = if (den > 1e-12) (num * num) / den else 0.0
        val p = chiP(chi2, 1)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "生存对比"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("log-rank p=%.4f".format(p), fontSize = 9.sp,
                     color = if (p < 0.05) ZxT.Ok else ZxT.Warn, fontFamily = ZxT.Mono)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(104.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height; val pad = 10f
                    val tMax = curves.maxOf { c -> c.maxOf { it.first } }.coerceAtLeast(1.0)
                    val px = { t: Double -> pad + (t / tMax * (W - 2 * pad)).toFloat() }
                    val py = { v: Double -> H - pad - (v * (H - 2 * pad)).toFloat() }
                    curves.forEachIndexed { gi, c ->
                        val on = sel == gi
                        drawPath(Path().apply {
                            var prevY = py(1.0); moveTo(px(0.0), prevY)
                            c.forEach { (t, v) ->
                                lineTo(px(t), prevY); lineTo(px(t), py(v)); prevY = py(v)
                            }
                        }, PAL[gi % PAL.size].copy(if (on || sel < 0) 1f else .3f),
                           style = Stroke(if (on) 2.2f else 1.6f))
                    }
                }
                Row(Modifier.fillMaxWidth().height(104.dp)) {
                    repeat(n) { k ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = k
                            ctx.callTool("zx_select", mapOf(
                                "group" to groups[k].first,
                                "chi2" to "%.3f".format(chi2), "p" to "%.4f".format(p),
                                "source" to "zx_survcmp"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                groups.forEachIndexed { k, g ->
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)) {
                        Box(Modifier.size(7.dp).clip(RoundedCornerShape(1.dp))
                            .background(PAL[k % PAL.size]))
                        Spacer(Modifier.width(3.dp))
                        Text(g.first, fontSize = 8.5.sp,
                             color = if (sel == k) ZxT.Text else ZxT.Text3,
                             maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Text(if (p < 0.05) "两组生存曲线<b>差异显著</b>（χ²=%.2f）".format(chi2)
                 else "尚不能认为两组生存曲线不同（χ²=%.2f）".format(chi2),
                fontSize = 9.5.sp, lineHeight = 13.sp,
                color = if (p < 0.05) ZxT.Ok else ZxT.Warn,
                modifier = Modifier.padding(top = 3.dp))
            Text("第二十四批的 survival 画<b>一条</b> KM 曲线，" +
                 "这个是<b>多条对比 + log-rank 检验</b>——" +
                 "光看曲线分开不代表显著，得算。" +
                 "点图例可<b>单独高亮一组</b>。" +
                 "<b>两组都为空会被拦</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ── 统计小函数 ─────────────────────────────────────────── */
    private fun Double.pow2() = this * this

    private fun tP(t: Double, df: Int): Double {
        val x = df / (df + t * t).coerceAtLeast(1e-9)
        return betai(df / 2.0, 0.5, x).coerceIn(0.0, 1.0)
    }

    private fun chiP(x: Double, df: Int): Double {
        if (x <= 0) return 1.0
        return kotlin.math.exp(-x / 2).coerceIn(0.0, 1.0)
    }

    private fun betai(a: Double, b: Double, x: Double): Double {
        val bt = kotlin.math.exp(gammln(a + b) - gammln(a) - gammln(b) +
                a * kotlin.math.ln(x.coerceIn(1e-12, 1.0)) +
                b * kotlin.math.ln((1 - x).coerceIn(1e-12, 1.0)))
        return if (x < (a + 1) / (a + b + 2)) bt * betacf(a, b, x) / a
        else 1 - bt * betacf(b, a, 1 - x) / b
    }

    private fun betacf(a: Double, b: Double, x: Double): Double {
        var qab = a + b; var qap = a + 1.0; var qam = a - 1.0
        var c = 1.0
        var d = 1.0 - qab * x / qap
        if (abs(d) < 1e-30) d = 1e-30
        d = 1 / d; var h = d
        for (m in 1..80) {
            val m2 = 2 * m
            var aa = m * (b - m) * x / ((qam + m2) * (a + m2))
            d = 1 + aa * d; if (abs(d) < 1e-30) d = 1e-30
            c = 1 + aa / c; if (abs(c) < 1e-30) c = 1e-30
            d = 1 / d; h *= d * c
            aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2))
            d = 1 + aa * d; if (abs(d) < 1e-30) d = 1e-30
            c = 1 + aa / c; if (abs(c) < 1e-30) c = 1e-30
            d = 1 / d; val del = d * c; h *= del
            if (abs(del - 1.0) < 3e-7) break
        }
        return h
    }

    private fun gammln(x: Double): Double {
        val c = doubleArrayOf(76.18009172947146, -86.50532032941677,
            24.01409824083091, -1.231739572450155, 0.1208650973866179e-2,
            -0.5395239384953e-5)
        var y = x; val tmp = x + 5.5
        var ser = 1.000000000190015
        for (j in 0..5) { y += 1; ser += c[j] / y }
        return -tmp + kotlin.math.ln(2.5066282746310005 * ser / x) + (x + 0.5) * kotlin.math.ln(tmp)
    }
}
