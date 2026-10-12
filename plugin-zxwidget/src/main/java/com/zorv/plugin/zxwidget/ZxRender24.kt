package com.zorv.plugin.zxwidget

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 第二十四批组件（12 个）—— 统计推断与一致性。
 *
 * 前二十三批铺完了"看什么、填什么、改什么、凭什么、怎么推演"。
 * 剩下的是一类**一直靠人下结论、界面只给数字**的事：
 *
 *   一、**"这组数据能不能信"**。
 *       QQ 图看是否服从正态（histogram 只给形状，
 *       不回答"偏离多少算偏离"），
 *       森林图把多个研究的效应量合并并给置信区间
 *       （compare 只并列，不合并），
 *       Bland-Altman 看两种测量方法能不能互相替代
 *       （corr 看相关，而**高相关不等于一致**），
 *       公差带看过程能力够不够（control 看是否受控，
 *       不回答"受控但全都超差"这种情形）。
 *
 *   二、**"随时间推移还剩多少"**。
 *       生存曲线（截尾数据）、经验累积分布、
 *       洛伦兹曲线（集中度 + 基尼）。
 *       这三个都不是"某一刻的值"，而是**一条随时间/份额推进的曲线**，
 *       单看一个数字会丢掉整段信息。
 *
 *   三、**"两个东西差在哪"**。
 *       序列片段对比（两段序列逐段对位）、
 *       回归与残差（拟合了但残差有没有结构）、
 *       散点（含趋势与离群）。
 *
 *   四、**载体**。
 *       命令面板（带历史与最近使用）、
 *       区间标注（在轴上标多段并命名）。
 *
 * 贯穿这批的一条：**要给出判据，而不只是图**。
 *
 *   图只能"看起来像"，判据才能"下结论"。
 *   QQ 图要给偏离带（点在带内=可接受），
 *   Bland-Altman 要给 95% 一致性界限（超出即不可互换），
 *   公差带要给 Cp/Cpk（<1.33 即能力不足），
 *   森林图要给合并效应量与是否跨过 0 / 1，
 *   洛伦兹要给基尼系数。
 *
 * 校验重心也跟着变：这批查的是**"算不出判据"**——
 *   森林图有研究缺 ci（合并时权重无从计算）
 *   Bland-Altman 两组长度不等（没法逐对做差）
 *   生存曲线有 t 递减（风险集只增不减）
 *   生存曲线某一时刻存活数超过起点（不可能）
 *   洛伦兹份额未升序（曲线会回头，基尼算错）
 *   QQ 图样本少于 3（分位数无从谈起）
 *   回归 x 全相同（斜率无定义）
 *   公差带 usl ≤ lsl（规格区间为空）
 *   序列片段对比两段都为空（无从对比）
 *   命令面板无命令（面板是空的）
 *   区间标注区间重叠（同一段被标两次，归属不明）
 *
 * 这些都不崩，只是让用户面对一个**画得出来、
 * 但给不出判据**的界面——比崩溃更糟，
 * 因为模型会以为下发成功了。
 */
object ZxRender24 {

    private val PAL = listOf(
        Color(0xFF5046E4), Color(0xFF3FBF7F), Color(0xFFE8A33D),
        Color(0xFFE5544B), Color(0xFF4A9BE8), Color(0xFF8E86FF)
    )

    /** 标准正态分位数近似（Acklam 逆 CDF 的简化形式） */
    private fun zOf(p: Double): Double {
        val q = p.coerceIn(1e-6, 1 - 1e-6)
        // Beasley-Springer-Moro 近似
        val a = doubleArrayOf(2.50662823884, -18.61500062529, 41.39119773534, -25.44106049637)
        val b = doubleArrayOf(-8.47351093090, 23.08336743743, -21.06224101826, 3.13082909833)
        val c = doubleArrayOf(0.3374754822726147, 0.9761690190917186, 0.1607979714918209,
            0.0276438810333863, 0.0038405729373609, 0.0003951896511919,
            0.0000321767881768, 0.0000002888167364, 0.0000003960315187)
        val y = q - 0.5
        return if (abs(y) < 0.42) {
            val r = y * y
            y * (((a[3] * r + a[2]) * r + a[1]) * r + a[0]) /
                ((((b[3] * r + b[2]) * r + b[1]) * r + b[0]) * r + 1.0)
        } else {
            val r = if (y < 0) q else 1.0 - q
            var s = ln(-ln(r))
            var x = c[0]
            var t = 1.0
            for (i in 1 until 9) { t *= s; x += c[i] * t }
            if (y < 0) -x else x
        }
    }

    /* ══════════ zx_qq QQ 图（正态性） ══════════ */
    @Composable
    fun qq(o: JSONObject, ctx: RenderContext) {
        val data = o.optJSONArray("data") ?: return
        val n = minOf(data.length(), 40)
        val xs = (0 until n).map { data.optDouble(it, 0.0) }.sorted()
        val mu = xs.average()
        val sd = sqrt(xs.map { (it - mu).pow(2) }.average()).coerceAtLeast(1e-9)
        var sel by remember { mutableIntStateOf(-1) }
        // 分位数：理论值 vs 实际值，偏离带 = ±1.36/√n（近似 95%）
        val pts = xs.mapIndexed { k, v ->
            val p = (k + 0.5) / n
            zOf(p) to v
        }
        val band = 1.36 / sqrt(n.toDouble())

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "QQ 图"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("n=$n", fontSize = 9.sp, color = ZxT.Text3, fontFamily = ZxT.Mono)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(118.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height
                    val pad = 10f
                    val zMin = pts.minOf { it.first }; val zMax = pts.maxOf { it.first }
                    val vMin = pts.minOf { it.second }; val vMax = pts.maxOf { it.second }
                    val zs = (zMax - zMin).coerceAtLeast(1e-9); val vs = (vMax - vMin).coerceAtLeast(1e-9)
                    val px = { z: Double -> pad + ((z - zMin) / zs * (W - 2 * pad)).toFloat() }
                    val py = { v: Double -> H - pad - ((v - vMin) / vs * (H - 2 * pad)).toFloat() }
                    // 参考线 y = mu + sd * z：点在带内即可接受
                    drawLine(PAL[0].copy(.5f), Offset(px(zMin), py(mu + sd * zMin)),
                             Offset(px(zMax), py(mu + sd * zMax)), 1.4f)
                    // 偏离带：不画就不知道"偏离多少算偏离"
                    drawPath(Path().apply {
                        moveTo(px(zMin), py(mu + sd * zMin + band * sd))
                        lineTo(px(zMax), py(mu + sd * zMax + band * sd))
                        lineTo(px(zMax), py(mu + sd * zMax - band * sd))
                        lineTo(px(zMin), py(mu + sd * zMin - band * sd))
                        close()
                    }, PAL[0].copy(.10f))
                    pts.forEachIndexed { k, (z, v) ->
                        val out = abs(v - (mu + sd * z)) > band * sd
                        drawCircle(if (out) ZxT.Err else (if (k == sel) PAL[0] else ZxT.Text3),
                                   if (k == sel) 4f else 2.6f, Offset(px(z), py(v)))
                    }
                }
                Row(Modifier.fillMaxWidth().height(118.dp)) {
                    repeat(n) { k ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = k
                            ctx.callTool("zx_select", mapOf(
                                "i" to k.toString(), "z" to "%.3f".format(pts[k].first),
                                "v" to "%.2f".format(pts[k].second), "source" to "zx_qq"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(pts.count { abs(it.second - (mu + sd * it.first)) > band * sd }
                    .let { if (it == 0) "全部落在 95% 带内，可认为近似正态"
                           else "$it 个点落在带外" },
                    fontSize = 9.5.sp,
                    color = pts.count { abs(it.second - (mu + sd * it.first)) > band * sd }
                        .let { if (it == 0) ZxT.Ok else ZxT.Warn },
                    modifier = Modifier.weight(1f))
                Text("μ=%.1f σ=%.1f".format(mu, sd), fontSize = 8.5.sp,
                     color = ZxT.Text3, fontFamily = ZxT.Mono)
            }
            Text("已有 histogram 只给形状、boxplot 给四分位，" +
                 "都不回答「<b>偏离多少算偏离</b>」——这个画出 95% 偏离带，" +
                 "带外的点标红。<b>样本少于 3 会被拦</b>：分位数无从谈起。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_forest 森林图（效应量合并） ══════════ */
    @Composable
    fun forest(o: JSONObject, ctx: RenderContext) {
        val studies = o.optJSONArray("studies") ?: return
        val n = minOf(studies.length(), 8)
        var sel by remember { mutableIntStateOf(-1) }
        // 逆方差加权合并：w = 1/se^2
        val rows = (0 until n).map {
            val s = studies.optJSONObject(it) ?: JSONObject()
            val e = s.optDouble("e", 0.0)
            val lo = s.optDouble("lo", e); val hi = s.optDouble("hi", e)
            val se = ((hi - lo) / 3.92).coerceAtLeast(1e-6)   // 95%CI → se
            Triple(s.optString("n", ""), e, lo to hi) to (1.0 / (se * se))
        }
        val sw = rows.sumOf { it.second }
        val pooled = if (sw > 0) rows.sumOf { it.first.second * it.second } / sw else 0.0
        val seP = if (sw > 0) sqrt(1.0 / sw) else 0.0
        val axisLo = minOf(rows.minOf { it.first.third.first }, pooled - 1.96 * seP)
        val axisHi = maxOf(rows.maxOf { it.first.third.second }, pooled + 1.96 * seP)
        val nullLine = o.optDouble("null", 0.0)
        val span = (axisHi - axisLo).coerceAtLeast(1e-9)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("title", "森林图"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("合并 %.3f".format(pooled), fontSize = 9.sp, color = ZxT.PriLite,
                     fontFamily = ZxT.Mono)
            }
            Spacer(Modifier.height(6.dp))
            // 无效线：跨过它就不显著
            Box(Modifier.fillMaxWidth().height(14.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val x = ((nullLine - axisLo) / span * size.width).toFloat()
                    drawLine(ZxT.Err.copy(.55f), Offset(x, 0f), Offset(x, size.height), 1.2f)
                }
            }
            for (i in 0 until n) {
                val (tri, _) = rows[i]
                val (nm, e, ci) = tri
                val on = sel == i
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (on) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        sel = i
                        ctx.callTool("zx_select", mapOf(
                            "study" to nm, "e" to e.toString(),
                            "ci" to "%.3f~%.3f".format(ci.first, ci.second),
                            "source" to "zx_forest"))
                    }.padding(5.dp, 3.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(nm, fontSize = 9.sp, color = if (on) ZxT.Text else ZxT.Text2,
                         modifier = Modifier.width(58.dp), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    Box(Modifier.weight(1f).height(16.dp)) {
                        Canvas(Modifier.fillMaxSize()) {
                            val W = size.width
                            val x0 = ((ci.first - axisLo) / span * W).toFloat()
                            val x1 = ((ci.second - axisLo) / span * W).toFloat()
                            val xc = ((e - axisLo) / span * W).toFloat()
                            drawLine(PAL[0], Offset(x0, size.height / 2),
                                     Offset(x1, size.height / 2), 1.6f)
                            drawCircle(PAL[0], 3.4f, Offset(xc, size.height / 2))
                        }
                    }
                    Text("%.2f".format(e), fontSize = 8.sp, color = ZxT.Text3,
                         modifier = Modifier.width(30.dp), textAlign = TextAlign.End,
                         fontFamily = ZxT.Mono)
                }
            }
            // 合并菱形：不画就只看到各研究、看不到结论
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(5.dp)).background(ZxT.Surface2).padding(5.dp, 3.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("合并", fontSize = 9.sp, color = ZxT.Text,
                     modifier = Modifier.width(58.dp))
                Box(Modifier.weight(1f).height(18.dp)) {
                    Canvas(Modifier.fillMaxSize()) {
                        val W = size.width; val cy = size.height / 2
                        val x0 = ((pooled - 1.96 * seP - axisLo) / span * W).toFloat()
                        val x1 = ((pooled + 1.96 * seP - axisLo) / span * W).toFloat()
                        val xc = ((pooled - axisLo) / span * W).toFloat()
                        drawPath(Path().apply {
                            moveTo(x0, cy); lineTo(xc, cy - 5f)
                            lineTo(x1, cy); lineTo(xc, cy + 5f); close()
                        }, PAL[0])
                    }
                }
                Text("%.2f".format(pooled), fontSize = 8.sp, color = ZxT.PriLite,
                     modifier = Modifier.width(30.dp), textAlign = TextAlign.End,
                     fontFamily = ZxT.Mono)
            }
            Text(if ((pooled - 1.96 * seP) > nullLine || (pooled + 1.96 * seP) < nullLine)
                 "合并效应量的置信区间不跨无效线（$nullLine），可以下结论"
            else "置信区间跨过无效线（$nullLine）——还不能下结论",
                fontSize = 9.5.sp,
                color = if ((pooled - 1.96 * seP) > nullLine ||
                    (pooled + 1.96 * seP) < nullLine) ZxT.Ok else ZxT.Warn,
                modifier = Modifier.padding(top = 4.dp))
            Text("已有 compare 只是并列、baseline 是两版对比，" +
                 "这个是<b>把多个研究的效应量合并</b>（逆方差加权）并给合并菱形。" +
                 "<b>有研究缺 ci 会被拦</b>：权重无从计算。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_bland Bland-Altman（一致性） ══════════ */
    @Composable
    fun bland(o: JSONObject, ctx: RenderContext) {
        val a = o.optJSONArray("a") ?: return
        val b = o.optJSONArray("b") ?: return
        val n = minOf(a.length(), b.length(), 30)
        val pairs = (0 until n).map { a.optDouble(it, 0.0) to b.optDouble(it, 0.0) }
        // 逐对：均值 vs 差值
        val pts = pairs.map { (x, y) -> (x + y) / 2.0 to (x - y) }
        val md = pts.map { it.second }.average()
        val sd = sqrt(pts.map { (it.second - md).pow(2) }.average()).coerceAtLeast(1e-9)
        val loA = md - 1.96 * sd; val hiA = md + 1.96 * sd
        val outN = pts.count { it.second < loA || it.second > hiA }
        var sel by remember { mutableIntStateOf(-1) }
        val mx = pts.maxOf { it.first }; val mn = pts.minOf { it.first }
        val dMx = maxOf(hiA, pts.maxOf { it.second })
        val dMn = minOf(loA, pts.minOf { it.second })

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "一致性"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (outN * 1.0 / n > 0.05) "不一致" else "一致",
                     fontSize = 9.sp,
                     color = if (outN * 1.0 / n > 0.05) ZxT.Err else ZxT.Ok)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(108.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height; val pad = 8f
                    val xs = (mx - mn).coerceAtLeast(1e-9); val ds = (dMx - dMn).coerceAtLeast(1e-9)
                    val px = { v: Double -> pad + ((v - mn) / xs * (W - 2 * pad)).toFloat() }
                    val py = { v: Double -> H - pad - ((v - dMn) / ds * (H - 2 * pad)).toFloat() }
                    // 95% 一致性界限：超出即不可互换
                    listOf(md to PAL[0].copy(.55f), loA to ZxT.Err.copy(.5f),
                           hiA to ZxT.Err.copy(.5f)).forEach { (v, c) ->
                        drawLine(c, Offset(pad, py(v)), Offset(W - pad, py(v)), 1.3f)
                    }
                    pts.forEachIndexed { k, (m, d) ->
                        val out = d < loA || d > hiA
                        drawCircle(if (out) ZxT.Err else (if (k == sel) PAL[0] else ZxT.Text3),
                                   if (k == sel) 4f else 2.6f, Offset(px(m), py(d)))
                    }
                }
                Row(Modifier.fillMaxWidth().height(108.dp)) {
                    repeat(n) { k ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = k
                            ctx.callTool("zx_select", mapOf(
                                "i" to k.toString(),
                                "mean" to "%.2f".format(pts[k].first),
                                "diff" to "%.2f".format(pts[k].second),
                                "source" to "zx_bland"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text("95% 界限 %.2f ~ %.2f".format(loA, hiA), fontSize = 9.sp,
                     color = ZxT.Text3, fontFamily = ZxT.Mono, modifier = Modifier.weight(1f))
                Text("$outN/$n 超出", fontSize = 9.sp,
                     color = if (outN * 1.0 / n > 0.05) ZxT.Err else ZxT.Text3)
            }
            Text("已有 corr 看<b>相关</b>、scatter 看<b>分布</b>，" +
                 "但<b>高相关不等于一致</b>——两法可以高度相关却系统性差一个常数。" +
                 "这个看差值是否落在 95% 界限内。<b>两组长度不等会被拦</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_survival Kaplan-Meier ══════════ */
    @Composable
    fun survival(o: JSONObject, ctx: RenderContext) {
        val events = o.optJSONArray("events") ?: return
        val n = minOf(events.length(), 14)
        val start = o.optInt("n0", 100).coerceAtLeast(1)
        var sel by remember { mutableIntStateOf(-1) }
        // KM：S(t) = ∏ (1 - d_i / n_i)
        var s = 1.0; var atRisk = start.toDouble()
        val curve = ArrayList<Pair<Double, Double>>()
        curve.add(0.0 to 1.0)
        for (i in 0 until n) {
            val e = events.optJSONObject(i) ?: continue
            val t = e.optDouble("t", (i + 1).toDouble())
            val d = e.optInt("d", 0)          // 事件数
            val c = e.optInt("c", 0)          // 截尾数
            if (atRisk > 0) s *= (1.0 - d / atRisk)
            atRisk -= (d + c)
            curve.add(t to s)
        }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("title", "生存曲线"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("剩余 %.1f%%".format(s * 100), fontSize = 9.sp, color = ZxT.PriLite,
                     fontFamily = ZxT.Mono)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(96.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height; val pad = 8f
                    val tMax = curve.maxOf { it.first }.coerceAtLeast(1.0)
                    val px = { t: Double -> pad + (t / tMax * (W - 2 * pad)).toFloat() }
                    val py = { v: Double -> H - pad - (v * (H - 2 * pad)).toFloat() }
                    // 阶梯：KM 是阶梯函数，不是折线（生存率只在事件时刻变）
                    drawPath(Path().apply {
                        var prevY = py(1.0)
                        moveTo(px(0.0), prevY)
                        curve.forEach { (t, v) ->
                            lineTo(px(t), prevY)      // 先水平
                            lineTo(px(t), py(v))      // 再竖直
                            prevY = py(v)
                        }
                        lineTo(px(tMax), prevY)
                    }, PAL[0], style = Stroke(1.8f))
                    // 截尾标记：不画就不知道哪里数据被截断
                    for (i in 0 until n) {
                        val e = events.optJSONObject(i) ?: continue
                        if (e.optInt("c", 0) > 0) {
                            val t = e.optDouble("t", (i + 1).toDouble())
                            drawLine(ZxT.Warn, Offset(px(t), py(0.0) - 4f),
                                     Offset(px(t), py(0.0) + 4f), 1.4f)
                        }
                    }
                    if (sel >= 0 && sel < curve.size) {
                        val (t, v) = curve[sel + 1]
                        drawCircle(Color.White, 3.2f, Offset(px(t), py(v)))
                    }
                }
                Row(Modifier.fillMaxWidth().height(96.dp)) {
                    repeat(n + 1) { k ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = k
                            if (k < curve.size) ctx.callTool("zx_select", mapOf(
                                "t" to "%.1f".format(curve[k].first),
                                "s" to "%.3f".format(curve[k].second),
                                "source" to "zx_survival"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(if (sel >= 0 && sel < curve.size)
                     "t=%.1f 时 S=%.3f".format(curve[sel].first, curve[sel].second)
                 else "点曲线看某一时刻的生存率",
                    fontSize = 9.5.sp, color = ZxT.Text3, modifier = Modifier.weight(1f))
                Text("n₀=$start", fontSize = 8.5.sp, color = ZxT.Text3, fontFamily = ZxT.Mono)
            }
            Text("已有 cohort 是<b>留存矩阵</b>、pulse 是<b>强度</b>，" +
                 "都不处理<b>截尾</b>——这个用 Kaplan-Meier，" +
                 "曲线是<b>阶梯</b>不是折线（生存率只在事件时刻变），截尾处标竖线。" +
                 "<b>t 递减或存活数超过起点会被拦</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_ecdf 经验累积分布 ══════════ */
    @Composable
    fun ecdf(o: JSONObject, ctx: RenderContext) {
        val data = o.optJSONArray("data") ?: return
        val n = minOf(data.length(), 40)
        val xs = (0 until n).map { data.optDouble(it, 0.0) }.sorted()
        var sel by remember { mutableIntStateOf(-1) }
        val lo = xs.first(); val hi = xs.last(); val span = (hi - lo).coerceAtLeast(1e-9)
        val marks = o.optJSONArray("marks")

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "累积分布"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("n=$n", fontSize = 9.sp, color = ZxT.Text3, fontFamily = ZxT.Mono)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(104.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height; val pad = 8f
                    val px = { v: Double -> pad + ((v - lo) / span * (W - 2 * pad)).toFloat() }
                    val py = { p: Double -> H - pad - (p * (H - 2 * pad)).toFloat() }
                    drawPath(Path().apply {
                        moveTo(px(lo), py(0.0))
                        xs.forEachIndexed { k, v ->
                            lineTo(px(v), py(k.toDouble() / n))
                            lineTo(px(v), py((k + 1).toDouble() / n))
                        }
                    }, PAL[0], style = Stroke(1.8f))
                    // 分位线：不画就只知道形状，读不出"多少比例低于 X"
                    (marks ?: org.json.JSONArray().put(0.5)).let { ms ->
                        for (k in 0 until ms.length()) {
                            val q = ms.optDouble(k, 0.5)
                            val idx = (q * n).toInt().coerceIn(0, n - 1)
                            drawLine(ZxT.Warn.copy(.6f), Offset(pad, py(q)),
                                     Offset(W - pad, py(q)), 1.1f)
                            drawLine(ZxT.Warn, Offset(px(xs[idx]), py(q)),
                                     Offset(px(xs[idx]), H - pad), 1.1f)
                        }
                    }
                    if (sel >= 0) drawCircle(Color.White, 3.2f,
                        Offset(px(xs[sel]), py((sel + 1).toDouble() / n)))
                }
                Row(Modifier.fillMaxWidth().height(104.dp)) {
                    repeat(n) { k ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = k
                            ctx.callTool("zx_select", mapOf(
                                "v" to "%.2f".format(xs[k]),
                                "p" to "%.3f".format((k + 1).toDouble() / n),
                                "source" to "zx_ecdf"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(if (sel >= 0) "%.2f → 累计 %.1f%%".format(xs[sel], (sel + 1.0) / n * 100)
                     else "点曲线看某个值对应的累计比例",
                    fontSize = 9.5.sp, color = ZxT.Text3, modifier = Modifier.weight(1f))
                Text("中位 %.1f".format(xs[n / 2]), fontSize = 8.5.sp, color = ZxT.Text3,
                     fontFamily = ZxT.Mono)
            }
            Text("已有 histogram 给<b>密度</b>、boxplot 给<b>四分位</b>，" +
                 "这个是<b>累积</b>——直接读出「多少比例低于 X」。" +
                 "分位线让这个读数不用靠猜。<b>数据为空会被拦</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_lorenz 洛伦兹 / 基尼 ══════════ */
    @Composable
    fun lorenz(o: JSONObject, ctx: RenderContext) {
        val shares = o.optJSONArray("shares") ?: return
        val n = minOf(shares.length(), 12)
        val xs = (0 until n).map { shares.optDouble(it, 0.0) }.sorted()
        val total = xs.sum().coerceAtLeast(1e-9)
        // 基尼 = 1 - 2 * 洛伦兹曲线下面积
        var cum = 0.0; var area = 0.0
        val curve = ArrayList<Pair<Double, Double>>()
        curve.add(0.0 to 0.0)
        xs.forEachIndexed { k, v ->
            val prev = cum
            cum += v / total
            area += (prev + cum) / 2.0 / n
            curve.add((k + 1).toDouble() / n to cum)
        }
        val gini = (1.0 - 2 * area).coerceIn(0.0, 1.0)
        var sel by remember { mutableIntStateOf(-1) }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "集中度"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("基尼 %.3f".format(gini), fontSize = 9.sp,
                     color = if (gini > 0.4) ZxT.Warn else ZxT.Text3, fontFamily = ZxT.Mono)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(104.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height; val pad = 10f
                    val px = { v: Double -> pad + (v * (W - 2 * pad)).toFloat() }
                    val py = { v: Double -> H - pad - (v * (H - 2 * pad)).toFloat() }
                    // 绝对平等线
                    drawLine(ZxT.Text3.copy(.45f), Offset(px(0.0), py(0.0)),
                             Offset(px(1.0), py(1.0)), 1.2f)
                    drawPath(Path().apply {
                        moveTo(px(0.0), py(0.0))
                        curve.forEach { (a, b) -> lineTo(px(a), py(b)) }
                    }, PAL[0], style = Stroke(1.8f))
                    // 曲线与平等线之间的面积就是基尼：画出来才看得见"不平等在哪"
                    drawPath(Path().apply {
                        moveTo(px(0.0), py(0.0))
                        curve.forEach { (a, b) -> lineTo(px(a), py(b)) }
                        lineTo(px(1.0), py(1.0)); close()
                    }, PAL[0].copy(.13f))
                    if (sel >= 0 && sel < curve.size)
                        drawCircle(Color.White, 3.2f,
                            Offset(px(curve[sel].first), py(curve[sel].second)))
                }
                Row(Modifier.fillMaxWidth().height(104.dp)) {
                    repeat(n + 1) { k ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = k
                            if (k < curve.size) ctx.callTool("zx_select", mapOf(
                                "top" to "%.0f%%".format(curve[k].first * 100),
                                "share" to "%.3f".format(curve[k].second),
                                "gini" to "%.3f".format(gini), "source" to "zx_lorenz"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(if (sel > 0) "前 %.0f%% 占 %.1f%%".format(
                        curve[sel].first * 100, curve[sel].second * 100)
                     else "点曲线看「前百分之多少占多少」",
                    fontSize = 9.5.sp, color = ZxT.Text3, modifier = Modifier.weight(1f))
                Text(if (gini > 0.4) "集中度高" else "较均衡", fontSize = 8.5.sp,
                     color = if (gini > 0.4) ZxT.Warn else ZxT.Ok)
            }
            Text("已有 pareto 是<b>降序累计</b>、treemap 是<b>占比面积</b>，" +
                 "这个把<b>不平等本身</b>量化成基尼系数，并画出曲线与平等线之间的面积。" +
                 "<b>份额未升序会被拦</b>：曲线会回头，基尼算错。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_regress 回归与残差 ══════════ */
    @Composable
    fun regress(o: JSONObject, ctx: RenderContext) {
        val ptsA = o.optJSONArray("pts") ?: return
        val n = minOf(ptsA.length(), 24)
        val pts = (0 until n).map {
            val p = ptsA.optJSONObject(it) ?: JSONObject()
            p.optDouble("x", 0.0) to p.optDouble("y", 0.0)
        }
        val mx = pts.map { it.first }.average()
        val my = pts.map { it.second }.average()
        val sxx = pts.map { (it.first - mx).pow(2) }.sum()
        val slope = if (sxx > 1e-12) pts.map { (it.first - mx) * (it.second - my) }.sum() / sxx else 0.0
        val icpt = my - slope * mx
        val fit = pts.map { slope * it.first + icpt }
        val res = pts.mapIndexed { k, p -> p.second - fit[k] }
        val ssT = pts.map { (it.second - my).pow(2) }.sum()
        val ssR = res.map { it.pow(2) }.sum()
        val r2 = if (ssT > 1e-12) 1 - ssR / ssT else 0.0
        var sel by remember { mutableIntStateOf(-1) }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "回归"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("R²=%.3f".format(r2), fontSize = 9.sp,
                     color = if (r2 > 0.7) ZxT.Ok else ZxT.Warn, fontFamily = ZxT.Mono)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(84.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height; val pad = 8f
                    val xLo = pts.minOf { it.first }; val xHi = pts.maxOf { it.first }
                    val yLo = minOf(pts.minOf { it.second }, fit.minOrNull() ?: 0.0)
                    val yHi = maxOf(pts.maxOf { it.second }, fit.maxOrNull() ?: 0.0)
                    val xs = (xHi - xLo).coerceAtLeast(1e-9); val ys = (yHi - yLo).coerceAtLeast(1e-9)
                    val px = { v: Double -> pad + ((v - xLo) / xs * (W - 2 * pad)).toFloat() }
                    val py = { v: Double -> H - pad - ((v - yLo) / ys * (H - 2 * pad)).toFloat() }
                    drawLine(PAL[0], Offset(px(xLo), py(slope * xLo + icpt)),
                             Offset(px(xHi), py(slope * xHi + icpt)), 1.6f)
                    pts.forEachIndexed { k, p ->
                        drawCircle(if (k == sel) PAL[0] else ZxT.Text3,
                                   if (k == sel) 4f else 2.6f, Offset(px(p.first), py(p.second)))
                        if (k == sel) drawLine(ZxT.Err, Offset(px(p.first), py(p.second)),
                            Offset(px(p.first), py(fit[k])), 1.3f)
                    }
                }
                Row(Modifier.fillMaxWidth().height(84.dp)) {
                    repeat(n) { k ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = k
                            ctx.callTool("zx_select", mapOf(
                                "x" to pts[k].first.toString(), "y" to pts[k].second.toString(),
                                "resid" to "%.3f".format(res[k]), "source" to "zx_regress"))
                        })
                    }
                }
            }
            Text("ŷ = %.3f·x + %.3f".format(slope, icpt), fontSize = 9.sp,
                 color = ZxT.Text3, fontFamily = ZxT.Mono,
                 modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(4.dp))
            // 残差图：拟合得好不好看 R²，残差有没有结构要看这张
            Text("残差", fontSize = 9.sp, color = ZxT.Text3)
            Box(Modifier.fillMaxWidth().height(42.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height
                    val m = res.map { abs(it) }.maxOrNull()?.coerceAtLeast(1e-9) ?: 1.0
                    drawLine(ZxT.Surface3, Offset(0f, H / 2), Offset(W, H / 2), 1f)
                    res.forEachIndexed { k, r ->
                        val x = ((k + 0.5) / n * W).toFloat()
                        drawRoundRect(if (k == sel) ZxT.Err else PAL[0].copy(.7f),
                            Offset(x - 2f, if (r >= 0) H / 2 - (r / m * H / 2).toFloat() else H / 2),
                            Size(4f, abs(r / m * H / 2).toFloat().coerceAtLeast(1f)),
                            androidx.compose.ui.geometry.CornerRadius(1f, 1f))
                    }
                }
            }
            Text(if (sel >= 0) "残差 %.3f（越大说明这个点拟合得越差）".format(res[sel])
                 else "点散点看该点的残差；残差图有规律说明模型漏了东西",
                fontSize = 9.sp, color = ZxT.Text3,
                modifier = Modifier.padding(top = 3.dp))
            Text("已有 trend 只画<b>趋势线</b>、corr 只给<b>相关系数</b>，" +
                 "这个给斜率截距 + R² + <b>残差图</b>。" +
                 "<b>R² 高不代表模型对</b>——残差有结构就说明漏了东西。" +
                 "<b>x 全相同会被拦</b>：斜率无定义。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_scatter 散点（趋势与离群） ══════════ */
    @Composable
    fun scatter(o: JSONObject, ctx: RenderContext) {
        val arr = o.optJSONArray("pts") ?: return
        val n = minOf(arr.length(), 40)
        val pts = (0 until n).map {
            val p = arr.optJSONObject(it) ?: JSONObject()
            Triple(p.optDouble("x", 0.0), p.optDouble("y", 0.0), p.optString("n", ""))
        }
        val groups = pts.map { it.third }.distinct()
        var sel by remember { mutableIntStateOf(-1) }
        var showTrend by remember { mutableStateOf(o.optBoolean("trend", true)) }
        val mx = pts.map { it.first }.average(); val my = pts.map { it.second }.average()
        val sxx = pts.map { (it.first - mx).pow(2) }.sum()
        val sl = if (sxx > 1e-12) pts.map { (it.first - mx) * (it.second - my) }.sum() / sxx else 0.0
        val ic = my - sl * mx
        val xLo = pts.minOf { it.first }; val xHi = pts.maxOf { it.first }
        val yLo = pts.minOf { it.second }; val yHi = pts.maxOf { it.second }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("title", "散点"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("${n} 点 · ${groups.size} 组", fontSize = 9.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(112.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height; val pad = 10f
                    val xs = (xHi - xLo).coerceAtLeast(1e-9); val ys = (yHi - yLo).coerceAtLeast(1e-9)
                    val px = { v: Double -> pad + ((v - xLo) / xs * (W - 2 * pad)).toFloat() }
                    val py = { v: Double -> H - pad - ((v - yLo) / ys * (H - 2 * pad)).toFloat() }
                    if (showTrend) drawLine(PAL[0].copy(.5f),
                        Offset(px(xLo), py(sl * xLo + ic)), Offset(px(xHi), py(sl * xHi + ic)), 1.5f)
                    pts.forEachIndexed { k, (x, y, g) ->
                        val gi = (groups.indexOf(g) % PAL.size).coerceAtLeast(0)
                        drawCircle(PAL[gi].copy(if (k == sel) 1f else .78f),
                                   if (k == sel) 5f else 3.2f, Offset(px(x), py(y)))
                    }
                }
                Row(Modifier.fillMaxWidth().height(112.dp)) {
                    repeat(n) { k ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = k
                            ctx.callTool("zx_select", mapOf(
                                "n" to pts[k].third, "x" to pts[k].first.toString(),
                                "y" to pts[k].second.toString(), "source" to "zx_scatter"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (showTrend) "隐藏趋势" else "显示趋势", fontSize = 9.sp,
                     color = if (showTrend) ZxT.PriLite else ZxT.Text3,
                     modifier = Modifier.clip(RoundedCornerShape(8.dp))
                         .background(if (showTrend) ZxT.Surface2 else Color.Transparent)
                         .clickable { showTrend = !showTrend }.padding(8.dp, 2.dp))
                if (groups.size > 1) groups.take(4).forEachIndexed { gi, g ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).clip(androidx.compose.foundation.shape.CircleShape)
                            .background(PAL[gi % PAL.size]))
                        Spacer(Modifier.width(3.dp))
                        Text(g, fontSize = 8.sp, color = ZxT.Text3)
                    }
                }
            }
            Text(if (sel >= 0) "「${pts[sel].third}」x=%.1f y=%.1f".format(pts[sel].first, pts[sel].second)
                 else "点任意一点看它的具体值",
                fontSize = 9.5.sp, color = ZxT.Text3, modifier = Modifier.padding(top = 4.dp))
            Text("已有 bubble 用<b>面积编码第三维</b>、corr 是<b>矩阵</b>，" +
                 "这个就是纯粹的 x-y-<b>分组着色</b>，用来看趋势与离群。" +
                 "<b>点少于 2 会被拦</b>：趋势无从谈起。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_seqdiff 序列片段对比 ══════════ */
    @Composable
    fun seqdiff(o: JSONObject, ctx: RenderContext) {
        val a = o.optJSONArray("a") ?: return
        val b = o.optJSONArray("b") ?: return
        val n = maxOf(a.length(), b.length())
        var onlyDiff by remember { mutableStateOf(o.optBoolean("onlyDiff", false)) }
        var sel by remember { mutableIntStateOf(-1) }
        val rows = (0 until n).map { i ->
            val va = if (i < a.length()) a.optString(i, "") else "—"
            val vb = if (i < b.length()) b.optString(i, "") else "—"
            Triple(i, va, vb)
        }
        val shown = if (onlyDiff) rows.filter { it.second != it.third } else rows

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "序列对比"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("${rows.count { it.second != it.third }} 处不同", fontSize = 9.sp,
                     color = if (rows.any { it.second != it.third }) ZxT.Warn else ZxT.Text3)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                .background(ZxT.Surface2).padding(5.dp, 3.dp)) {
                Text("#", fontSize = 8.sp, color = ZxT.Text3, modifier = Modifier.width(24.dp))
                Text(o.optString("an", "A"), fontSize = 8.5.sp, color = ZxT.Text3,
                     modifier = Modifier.weight(1f))
                Text(o.optString("bn", "B"), fontSize = 8.5.sp, color = ZxT.Text3,
                     modifier = Modifier.weight(1f))
            }
            if (shown.isEmpty())
                Text("两段完全相同", fontSize = 10.sp, color = ZxT.Text3,
                     modifier = Modifier.padding(vertical = 10.dp))
            shown.forEach { (i, va, vb) ->
                val diff = va != vb
                val on = sel == i
                Row(Modifier.fillMaxWidth().padding(vertical = 1.5.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (on) ZxT.Surface2 else
                        (if (diff) ZxT.Warn.copy(.10f) else Color.Transparent))
                    .clickable {
                        sel = i
                        ctx.callTool("zx_select", mapOf(
                            "i" to i.toString(), "a" to va, "b" to vb,
                            "diff" to diff.toString(), "source" to "zx_seqdiff"))
                    }.padding(5.dp, 3.dp)) {
                    Text(i.toString(), fontSize = 8.sp,
                         color = if (diff) ZxT.Warn else ZxT.Text3,
                         modifier = Modifier.width(24.dp), fontFamily = ZxT.Mono)
                    Text(va, fontSize = 9.sp, color = if (diff) ZxT.Text else ZxT.Text3,
                         modifier = Modifier.weight(1f), maxLines = 1,
                         overflow = TextOverflow.Ellipsis, fontFamily = ZxT.Mono)
                    Text(vb, fontSize = 9.sp, color = if (diff) ZxT.Text else ZxT.Text3,
                         modifier = Modifier.weight(1f), maxLines = 1,
                         overflow = TextOverflow.Ellipsis, fontFamily = ZxT.Mono)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)) {
                Text(if (onlyDiff) "看全部" else "只看不同", fontSize = 9.sp,
                     color = if (onlyDiff) ZxT.PriLite else ZxT.Text3,
                     modifier = Modifier.clip(RoundedCornerShape(8.dp))
                         .background(if (onlyDiff) ZxT.Surface2 else Color.Transparent)
                         .clickable { onlyDiff = !onlyDiff }.padding(8.dp, 2.dp))
            }
            Text("已有 diff 是<b>逐行文本差异</b>、threeway 是<b>三方</b>，" +
                 "这个是<b>两段序列逐位对位</b>——长度不等时缺的一侧显示「—」而不是直接报错。" +
                 "<b>两段都为空会被拦</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_cmdpal 命令面板 ══════════ */
    @Composable
    fun cmdpal(o: JSONObject, ctx: RenderContext) {
        val cmds = o.optJSONArray("cmds") ?: return
        val n = minOf(cmds.length(), 12)
        var q by remember { mutableStateOf("") }
        var sel by remember { mutableIntStateOf(0) }
        var recent by remember { mutableStateOf(listOf<Int>()) }
        val items = (0 until n).map {
            val c = cmds.optJSONObject(it) ?: JSONObject()
            Triple(c.optString("n", ""), c.optString("k", ""), c.optInt("used", 0))
        }
        // 空查询时按「最近使用 + 频次」排，有查询时才过滤
        val shown = (0 until n).filter { q.isBlank() || items[it].first.contains(q, true) }
            .sortedWith(compareByDescending<Int> { recent.indexOf(it) >= 0 }
                .thenByDescending { items[it].third })

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "命令面板"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("${shown.size} 条", fontSize = 9.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(6.dp))
            Text(if (q.isBlank()) "输入过滤…" else "「$q」", fontSize = 10.sp,
                 color = if (q.isBlank()) ZxT.Text3 else ZxT.Text,
                 modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                     .background(ZxT.Surface2).padding(7.dp, 5.dp))
            Spacer(Modifier.height(4.dp))
            if (shown.isEmpty())
                Text("没有匹配的命令", fontSize = 10.sp, color = ZxT.Text3,
                     modifier = Modifier.padding(vertical = 8.dp))
            shown.forEach { i ->
                val (nm, kbd, used) = items[i]
                val on = shown.indexOf(i) == sel.coerceIn(0, (shown.size - 1).coerceAtLeast(0))
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (on) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        sel = shown.indexOf(i)
                        recent = (listOf(i) + recent).distinct().take(3)
                        ctx.callTool("zx_select", mapOf(
                            "cmd" to nm, "source" to "zx_cmdpal"))
                    }.padding(6.dp, 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (recent.indexOf(i) == 0)
                        Text("最近", fontSize = 7.5.sp, color = ZxT.PriLite,
                             modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                 .background(ZxT.PriLite.copy(.16f)).padding(5.dp, 1.dp))
                    Text(nm, fontSize = 10.sp,
                         color = if (on) ZxT.Text else ZxT.Text2,
                         modifier = Modifier.weight(1f).padding(start = 4.dp),
                         maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (used > 0)
                        Text("${used}×", fontSize = 8.sp, color = ZxT.Text3,
                             fontFamily = ZxT.Mono)
                    if (kbd.isNotBlank())
                        Text(kbd, fontSize = 8.sp, color = ZxT.Text3,
                             modifier = Modifier.padding(start = 5.dp)
                                 .clip(RoundedCornerShape(4.dp)).background(ZxT.Surface3)
                                 .padding(5.dp, 1.dp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("发布", "回滚", "").forEach { s ->
                    if (s.isNotBlank()) Text(s, fontSize = 9.sp, color = ZxT.Text3,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                            .background(ZxT.Surface2)
                            .clickable { q = s }.padding(8.dp, 2.dp))
                }
                Spacer(Modifier.weight(1f))
                Text("清空", fontSize = 9.sp, color = ZxT.Text3,
                     modifier = Modifier.clip(RoundedCornerShape(8.dp))
                         .background(ZxT.Surface2).clickable { q = "" }.padding(8.dp, 2.dp))
            }
            Text("已有 command 是<b>单条命令入口</b>、cmdhist 是<b>跑过的记录</b>，" +
                 "这个是<b>带过滤和最近使用的面板</b>——空查询时按最近使用 + 频次排，" +
                 "不是按字母序（字母序把常用项埋在下面）。<b>无命令会被拦</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_span 区间标注 ══════════ */
    @Composable
    fun span(o: JSONObject, ctx: RenderContext) {
        val total = o.optDouble("total", 100.0).coerceAtLeast(0.1)
        val spans = o.optJSONArray("spans") ?: return
        val n = minOf(spans.length(), 8)
        var sel by remember { mutableIntStateOf(-1) }
        val items = (0 until n).map {
            val s = spans.optJSONObject(it) ?: JSONObject()
            Triple(s.optString("n", ""), s.optDouble("s", 0.0), s.optDouble("e", 0.0))
        }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("title", "区间标注"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("${n} 段 · 总长 ${"%.0f".format(total)}", fontSize = 9.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(30.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height
                    drawRoundRect(ZxT.Surface2, Offset(0f, H / 2 - 5f), Size(W, 10f),
                        androidx.compose.ui.geometry.CornerRadius(5f, 5f))
                    items.forEachIndexed { k, (_, s, e) ->
                        val x0 = (s / total * W).toFloat(); val x1 = (e / total * W).toFloat()
                        val on = sel == k
                        drawRoundRect(PAL[k % PAL.size].copy(if (on) 1f else .72f),
                            Offset(x0, if (on) 2f else H / 2 - 8f),
                            Size((x1 - x0).coerceAtLeast(2f), if (on) H - 4f else 16f),
                            androidx.compose.ui.geometry.CornerRadius(3f, 3f))
                    }
                }
                Row(Modifier.fillMaxWidth().height(30.dp)) {
                    items.forEachIndexed { k, (_, s, e) ->
                        Box(Modifier.weight(((e - s) / total).toFloat().coerceAtLeast(0.01f))
                            .fillMaxHeight().clickable {
                                sel = k
                                ctx.callTool("zx_select", mapOf(
                                    "span" to items[k].first,
                                    "range" to "%.1f~%.1f".format(s, e),
                                    "len" to "%.1f".format(e - s), "source" to "zx_span"))
                            })
                        if (k < items.size - 1) {
                            val gap = items[k].third
                            val next = items[k + 1].second
                            Box(Modifier.weight(((next - gap) / total).toFloat()
                                .coerceAtLeast(0.001f)).fillMaxHeight())
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            items.forEachIndexed { k, (nm, s, e) ->
                val on = sel == k
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (on) ZxT.Surface2 else Color.Transparent)
                    .clickable { sel = k }.padding(6.dp, 3.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(RoundedCornerShape(2.dp))
                        .background(PAL[k % PAL.size]))
                    Spacer(Modifier.width(6.dp))
                    Text(nm, fontSize = 10.sp, color = if (on) ZxT.Text else ZxT.Text2,
                         modifier = Modifier.weight(1f), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    Text("%.0f–%.0f".format(s, e), fontSize = 8.5.sp, color = ZxT.Text3,
                         fontFamily = ZxT.Mono)
                    Text("  %.0f".format(e - s), fontSize = 8.5.sp, color = ZxT.Text3,
                         fontFamily = ZxT.Mono)
                }
            }
            Text("已有 timerange 是<b>选一个区间</b>、gantt 是<b>任务排期</b>，" +
                 "这个是<b>在同一根轴上标多段并命名</b>——看的是各段占多少、留了多少空。" +
                 "<b>区间重叠会被拦</b>：同一段被标两次，归属不明。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_tolerance 公差带 / 过程能力 ══════════ */
    @Composable
    fun tolerance(o: JSONObject, ctx: RenderContext) {
        val data = o.optJSONArray("data") ?: return
        val n = minOf(data.length(), 36)
        val xs = (0 until n).map { data.optDouble(it, 0.0) }
        val lsl = o.optDouble("lsl", 0.0)
        val usl = o.optDouble("usl", 100.0)
        val target = o.optDouble("target", (lsl + usl) / 2)
        val mu = xs.average()
        val sd = sqrt(xs.map { (it - mu).pow(2) }.average()).coerceAtLeast(1e-9)
        // Cp = 规格宽度 / 6σ；Cpk = 考虑偏移后的单侧能力
        val cp = (usl - lsl) / (6 * sd)
        val cpk = minOf((usl - mu), (mu - lsl)) / (3 * sd)
        val outN = xs.count { it < lsl || it > usl }
        var sel by remember { mutableIntStateOf(-1) }
        val vLo = minOf(lsl, xs.minOrNull() ?: lsl)
        val vHi = maxOf(usl, xs.maxOrNull() ?: usl)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "过程能力"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("Cpk=%.2f".format(cpk), fontSize = 9.sp,
                     color = if (cpk >= 1.33) ZxT.Ok else ZxT.Err, fontFamily = ZxT.Mono)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(72.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height; val pad = 8f
                    val span = (vHi - vLo).coerceAtLeast(1e-9)
                    val px = { v: Double -> pad + ((v - vLo) / span * (W - 2 * pad)).toFloat() }
                    // 规格带：过程可以在带内（受控）但贴边（不合格风险）
                    drawRoundRect(ZxT.Ok.copy(.13f), Offset(px(lsl), 6f),
                        Size(px(usl) - px(lsl), H - 12f),
                        androidx.compose.ui.geometry.CornerRadius(3f, 3f))
                    listOf(lsl to ZxT.Err, usl to ZxT.Err, target to ZxT.Ok, mu to PAL[0])
                        .forEach { (v, c) ->
                            drawLine(c, Offset(px(v), 2f), Offset(px(v), H - 2f), 1.3f)
                        }
                    xs.forEachIndexed { k, v ->
                        val out = v < lsl || v > usl
                        drawCircle(if (out) ZxT.Err else (if (k == sel) PAL[0] else ZxT.Text3),
                                   if (k == sel) 4f else 2.6f,
                                   Offset(px(v), H * (0.32f + 0.36f * ((k % 7) / 6f))))
                    }
                }
                Row(Modifier.fillMaxWidth().height(72.dp)) {
                    repeat(n) { k ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = k
                            ctx.callTool("zx_select", mapOf(
                                "v" to "%.2f".format(xs[k]),
                                "out" to (xs[k] < lsl || xs[k] > usl).toString(),
                                "cpk" to "%.2f".format(cpk), "source" to "zx_tolerance"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text("Cp=%.2f　规格 %.1f–%.1f".format(cp, lsl, usl), fontSize = 8.5.sp,
                     color = ZxT.Text3, fontFamily = ZxT.Mono, modifier = Modifier.weight(1f))
                Text("$outN/$n 超差", fontSize = 9.sp,
                     color = if (outN > 0) ZxT.Err else ZxT.Ok)
            }
            Text(if (cpk >= 1.33) "Cpk ≥ 1.33，能力充足"
            else if (cp >= 1.33) "Cp 够但 Cpk 不够——<b>受控但偏了</b>，需要把均值拉回中心"
            else "能力不足，需要收窄波动或放宽规格",
                fontSize = 9.5.sp, lineHeight = 13.sp,
                color = if (cpk >= 1.33) ZxT.Ok else ZxT.Warn,
                modifier = Modifier.padding(top = 3.dp))
            Text("已有 control 看<b>是否受控</b>、histogram 看<b>分布</b>，" +
                 "这个回答「<b>受控但全都超差</b>」这种情形——Cp 够而 Cpk 不够就是均值偏了。" +
                 "<b>usl ≤ lsl 会被拦</b>：规格区间为空。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }
}
