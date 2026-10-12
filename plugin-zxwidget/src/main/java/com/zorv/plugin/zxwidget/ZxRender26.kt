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
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 第二十六批组件（12 个）—— 因果与可信度。
 *
 * 前二十五批铺完了"看什么、填什么、改什么、凭什么、\n* 怎么推演、统计前提成不成立、在多大范围内成立"。
 *
 * 但有一个贯穿始终、一直没正面处理的问题：
 *
 *   **相关不等于因果。**
 *
 * 前面的 corr 给相关系数、scatter 画散点、regress 给回归线、
 * zx_test 给 p 值——它们全都在回答"**有没有关系**"，
 * 没有一个回答"**是不是它导致的**"。
 *
 * 而 AI 在对话里最常干的恰恰是后者：
 *
 *   "留存下降了，是不是因为改了首页？"
 *   "用了这个功能的用户付费更高，要不要全量？"
 *   "这批用户投诉多，是不是因为那次发布？"
 *
 * 如果只有相关性的工具，模型只能给出一个相关系数，
 * 然后**用文字去暗示因果**——而文字暗示没有前提、不可检验、
 * 也没法被用户反驳。
 *
 * 十二个组件分成三组：
 *
 *   **因果结构**（把"为什么"摆出来）
 *     zx_causal    因果图：暴露混杂 / 对撞 / 中介，点节点看后门路径
 *     zx_did       双重差分：平行趋势是前提，画出来才知道成不成立
 *     zx_rdd       断点回归：断点两侧各拟合一条，看跳跃
 *     zx_iv        工具变量：两阶段最小二乘，给一阶段 F 值
 *     zx_sense     敏感性分析：未测混杂要多强才能推翻结论
 *
 *   **可比性**（两组能不能比）
 *     zx_psm       倾向得分匹配：匹配前后平衡对比
 *     zx_balance   协变量平衡：SMD，匹配后应落到 ±0.1
 *
 *   **可信度**（这个结论值不值得信）
 *     zx_missing   缺失值模式：缺的是不是随机的
 *     zx_seqtest   序贯检验：边看边下结论会放大假阳性，要花 α
 *     zx_bayes     贝叶斯更新：先验 → 后验，看得见信念怎么变
 *     zx_anomaly   异常检测：三种判据并列，因为给的答案不一样
 *     zx_power     功效分析：不显著是"真没差"还是"没看出来"
 *
 * 贯穿这批的一条：**把前提从文字里拿出来，变成界面上的东西**。
 *
 *   以前"平行趋势假设"是一句没人验证的话，
 *   现在它是两条画出来的线，用户能直接看平行不平行。
 *   以前"可能有未测混杂"是一句免责声明，
 *   现在它是一个可拖的强度，用户能看到结论在多大强度下崩掉。
 *
 *   **不能验证的前提，等于没有前提。**
 *
 * 校验重心：这批查的是**"前提无从判断"**——
 *   因果图有环（不是 DAG，后门路径无定义）
 *   因果图边指向不存在的节点
 *   DID 缺干预前数据或两组期数不等（算不出平行趋势）
 *   RDD 断点两侧样本都不足（跳观测不出来）
 *   RDD 断点落在数据范围外
 *   IV 缺工具变量值（一阶段无从估计）
 *   PSM 缺协变量或两组都为空
 *   平衡图某协变量缺两组值
 *   缺失值矩阵行宽不齐
 *   序贯检验次数为 0 或 spending 函数非法
 *   贝叶斯先验概率不在 0-1 或似然缺失
 *   异常检测全常量（任何判据都判不出异常）
 *   功效分析效应量为 0（样本量无穷大）
 *
 * 这些都不崩，只是让用户面对一个**画得出来、
 * 但因果结论站不住**的界面——比崩溃更糟，
 * 因为模型会以为下发成功了。
 */
object ZxRender26 {

    private val PAL = listOf(
        Color(0xFF5046E4), Color(0xFF3FBF7F), Color(0xFFE8A33D),
        Color(0xFFE5544B), Color(0xFF4A9BE8), Color(0xFF8E86FF)
    )

    /* ══════════ zx_causal 因果图 ══════════ */
    @Composable
    fun causal(o: JSONObject, ctx: RenderContext) {
        val nodesA = o.optJSONArray("nodes") ?: return
        val n = minOf(nodesA.length(), 8)
        val nodes = (0 until n).map {
            val p = nodesA.optJSONObject(it) ?: JSONObject()
            p.optString("n", "节点${it + 1}") to p.optString("role", "")
        }
        val edgesA = o.optJSONArray("edges") ?: return
        val edges = (0 until minOf(edgesA.length(), 14)).mapNotNull {
            val e = edgesA.optJSONObject(it) ?: return@mapNotNull null
            val f = e.optInt("f", -1); val t = e.optInt("t", -1)
            if (f in 0 until n && t in 0 until n && f != t) f to t else null
        }
        val treat = o.optInt("t", -1)
        val out = o.optInt("y", -1)
        var sel by remember { mutableIntStateOf(-1) }

        // 分层：入度为 0 的在前，保证箭头基本朝一个方向
        val indeg = IntArray(n)
        edges.forEach { (_, t) -> indeg[t]++ }
        val layer = IntArray(n)
        var chg = true
        while (chg) {
            chg = false
            edges.forEach { (f, t) ->
                if (layer[t] < layer[f] + 1) { layer[t] = layer[f] + 1; chg = true }
            }
        }
        val maxL = layer.maxOrNull() ?: 0
        val buckets = (0..maxL).map { l -> (0 until n).filter { layer[it] == l } }

        /** 后门路径：从 treat 出发、经过非后代、到达 out 的开放路径 */
        fun backdoor(): List<List<Int>> {
            if (treat !in 0 until n || out !in 0 until n) return emptyList()
            val res = ArrayList<List<Int>>()
            fun dfs(cur: Int, path: MutableList<Int>) {
                if (path.size > 5) return
                if (cur == out && path.size > 1) { res.add(path.toList()); return }
                // 沿反向边走：后门路径是"从处理出发倒着进来的路"
                edges.filter { (_, t) -> t == cur }.map { it.first }.forEach { p ->
                    if (p !in path) { path.add(p); dfs(p, path); path.removeAt(path.size - 1) }
                }
            }
            dfs(treat, mutableListOf(treat))
            return res
        }
        val bd = backdoor()

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "因果图"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (bd.isEmpty()) "无后门" else "${bd.size} 条后门", fontSize = 9.sp,
                     color = if (bd.isEmpty()) ZxT.Ok else ZxT.Warn)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(112.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height; val pad = 18f
                    val pos = HashMap<Int, Offset>()
                    buckets.forEachIndexed { li, ids ->
                        ids.forEachIndexed { k, id ->
                            pos[id] = Offset(
                                (li + 0.5f) / (maxL + 1) * W,
                                pad + (k + 0.5f) / ids.size * (H - 2 * pad)
                            )
                        }
                    }
                    edges.forEach { (f, t) ->
                        val a = pos[f]; val b = pos[t]
                        if (a == null || b == null) return@forEach
                        drawLine(ZxT.Text3, a, b, 1.2f)
                        // 箭头：没箭头就看不出方向，方向是因果图的核心
                        val dx = b.x - a.x; val dy = b.y - a.y
                        val len = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-6f)
                        val ux = dx / len; val uy = dy / len
                        val bx = b.x - ux * 9f; val by = b.y - uy * 9f
                        drawPath(Path().apply {
                            moveTo(bx, by)
                            lineTo(bx - ux * 5f - uy * 3f, by - uy * 5f + ux * 3f)
                            lineTo(bx - ux * 5f + uy * 3f, by - uy * 5f - ux * 3f)
                            close()
                        }, ZxT.Text3)
                    }
                    (0 until n).forEach { k ->
                        val p = pos[k] ?: return@forEach
                        val role = nodes[k].second
                        val c = when (role) {
                            "confounder" -> ZxT.Warn     // 混杂：必须控制
                            "collider"   -> ZxT.Err      // 对撞：控制了反而引入偏倚
                            "mediator"   -> PAL[1]       // 中介：控制了就看不到总效应
                            else -> if (k == treat) PAL[0] else if (k == out) PAL[1] else ZxT.Text2
                        }
                        drawCircle(if (k == sel) c else c.copy(.72f),
                                   if (k == sel) 8f else 6f, p)
                        drawCircle(Color.White, 1.6f, p)
                    }
                }
                Row(Modifier.fillMaxWidth().height(112.dp)) {
                    repeat(n) { k ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = k
                            ctx.callTool("zx_select", mapOf(
                                "node" to nodes[k].first, "role" to nodes[k].second,
                                "indeg" to indeg[k].toString(),
                                "outdeg" to edges.count { it.first == k }.toString(),
                                "source" to "zx_causal"))
                        })
                    }
                }
            }
            // 节点名：气泡里画不进 Canvas，用 Row 排
            Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                nodes.forEachIndexed { k, nd ->
                    val role = when (nd.second) {
                        "confounder" -> "混杂"; "collider" -> "对撞"
                        "mediator" -> "中介"; else -> ""
                    }
                    Column(Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(nd.first, fontSize = 7.5.sp,
                             color = if (sel == k) ZxT.Text else ZxT.Text3,
                             maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (role.isNotEmpty())
                            Text(role, fontSize = 7.sp,
                                 color = when (nd.second) {
                                     "confounder" -> ZxT.Warn
                                     "collider" -> ZxT.Err
                                     else -> ZxT.Ok })
                    }
                }
            }
            if (bd.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                bd.take(3).forEach { p ->
                    Text("后门：${p.joinToString(" ← ") { nodes[it].first }}　（需控制中间节点）",
                        fontSize = 8.5.sp, color = ZxT.Warn)
                }
            }
            Text("已有 network 看<b>谁连谁</b>、depgraph 看<b>依赖</b>，" +
                 "这个看<b>谁导致谁</b>——箭头方向是核心，所以每个箭头都画。" +
                 "黄=混杂（不控制就偏）、红=对撞（<b>控制了反而偏</b>）、" +
                 "绿=中介（控制了只剩直接效应）。<b>有环会被拦</b>：后门路径无定义。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_did 双重差分 ══════════ */
    @Composable
    fun did(o: JSONObject, ctx: RenderContext) {
        val aA = o.optJSONArray("treat") ?: return
        val bA = o.optJSONArray("ctrl") ?: return
        val n = minOf(aA.length(), bA.length(), 12)
        val a = (0 until n).map { aA.optDouble(it, 0.0) }
        val b = (0 until n).map { bA.optDouble(it, 0.0) }
        val at = o.optInt("at", n / 2).coerceIn(1, n - 1)
        var sel by remember { mutableIntStateOf(-1) }
        // 干预前差值：平行趋势的量化
        val preGap = (0 until at).map { a[it] - b[it] }
        val preMean = preGap.average()
        val preSd = if (preGap.size > 1) sqrt(preGap.map { (it - preMean) * (it - preMean) }.average()) else 0.0
        // DID = (干预后差 - 干预前差)
        val postGap = (at until n).map { a[it] - b[it] }.average()
        val didEst = postGap - preMean
        val parallel = preSd < abs(preMean).coerceAtLeast(1.0) * 0.15
        val all = a + b
        val lo = all.minOrNull() ?: 0.0; val hi = all.maxOrNull() ?: 0.0
        val sp = (hi - lo).coerceAtLeast(1e-9)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "双重差分"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("DID=%.2f".format(didEst), fontSize = 9.sp, color = ZxT.PriLite,
                     fontFamily = ZxT.Mono)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(96.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height; val pad = 10f
                    val px = { k: Int -> pad + (k.toDouble() / (n - 1) * (W - 2 * pad)).toFloat() }
                    val py = { v: Double -> H - pad - ((v - lo) / sp * (H - 2 * pad)).toFloat() }
                    // 干预分界线
                    drawLine(ZxT.Warn.copy(.55f), Offset(px(at), 0f), Offset(px(at), H), 1.3f)
                    listOf(a to PAL[0], b to ZxT.Text3).forEach { (ser, col) ->
                        drawPath(Path().apply {
                            ser.forEachIndexed { k, v ->
                                val x = px(k); val y = py(v)
                                if (k == 0) moveTo(x, y) else lineTo(x, y)
                            }
                        }, col, style = Stroke(1.7f))
                        ser.forEachIndexed { k, v ->
                            drawCircle(if (k == sel) Color.White else col,
                                       if (k == sel) 3.4f else 2.2f, Offset(px(k), py(v)))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().height(96.dp)) {
                    repeat(n) { k ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = k
                            ctx.callTool("zx_select", mapOf(
                                "i" to k.toString(),
                                "treat" to "%.2f".format(a[k]), "ctrl" to "%.2f".format(b[k]),
                                "gap" to "%.2f".format(a[k] - b[k]),
                                "did" to "%.2f".format(didEst), "source" to "zx_did"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                listOf(PAL[0] to "处理组", ZxT.Text3 to "对照组").forEach { (c, nm) ->
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 8.dp)) {
                        Box(Modifier.size(7.dp).clip(RoundedCornerShape(1.dp)).background(c))
                        Spacer(Modifier.width(3.dp))
                        Text(nm, fontSize = 8.5.sp, color = ZxT.Text3)
                    }
                }
                Spacer(Modifier.weight(1f))
                if (sel >= 0) Text("第 $sel 期差 ${"%.2f".format(a[sel] - b[sel])}",
                    fontSize = 8.5.sp, color = ZxT.Text3, fontFamily = ZxT.Mono)
            }
            // 平行趋势：不画出来它就只是一句没人验证的话
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (parallel) ZxT.Ok.copy(.10f) else ZxT.Warn.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (parallel) "✓" else "!", fontSize = 11.sp,
                     color = if (parallel) ZxT.Ok else ZxT.Warn)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (parallel) "干预前两组走势平行（差 ${"%.2f".format(preMean)} ± ${"%.2f".format(preSd)}）"
                         else "干预前两组走势不太平行（波动 ${"%.2f".format(preSd)} 相对差值偏大）",
                        fontSize = 9.sp, color = if (parallel) ZxT.Ok else ZxT.Warn)
                    Text("DID 的前提就是平行趋势——不成立的话这个 ${"%.2f".format(didEst)} 不可信",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 slope 比<b>两个时间点</b>、baseline 比<b>改前改后</b>，" +
                 "这个两个都要——减去<b>两组本来就有的差距</b>。" +
                 "黄线是干预时点。<b>缺干预前数据会被拦</b>：算不出平行趋势，" +
                 "而平行趋势是 DID 唯一的前提。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_rdd 断点回归 ══════════ */
    @Composable
    fun rdd(o: JSONObject, ctx: RenderContext) {
        val ptsA = o.optJSONArray("pts") ?: return
        val n = minOf(ptsA.length(), 40)
        val pts = (0 until n).map {
            val p = ptsA.optJSONObject(it) ?: JSONObject()
            p.optDouble("x", 0.0) to p.optDouble("y", 0.0)
        }.sortedBy { it.first }
        val cut = o.optDouble("cut", 0.0)
        var bw by remember { mutableDoubleStateOf(o.optDouble("bw", 0.0).let { if (it <= 0) 0.5 else it }) }
        var sel by remember { mutableIntStateOf(-1) }

        fun fit(side: Int): Pair<Double, Double> {
            val sub = pts.filter { (x, _) -> if (side < 0) x < cut else x >= cut }
                .filter { (x, _) -> abs(x - cut) <= bw }
            if (sub.size < 2) return 0.0 to (if (side < 0) pts.firstOrNull()?.second
                ?: 0.0 else pts.lastOrNull()?.second ?: 0.0)
            val mx = sub.map { it.first }.average()
            val my = sub.map { it.second }.average()
            var num = 0.0; var den = 0.0
            sub.forEach { (x, y) -> num += (x - mx) * (y - my); den += (x - mx) * (x - mx) }
            val b = if (den > 1e-12) num / den else 0.0
            return b to (my - b * mx)
        }
        val (bl, al) = fit(-1)
        val (br, ar) = fit(1)
        val jump = (ar + br * cut) - (al + bl * cut)
        val nL = pts.count { it.first < cut && abs(it.first - cut) <= bw }
        val nR = pts.count { it.first >= cut && abs(it.first - cut) <= bw }
        val xs = pts.map { it.first }
        val xLo = xs.minOrNull() ?: 0.0; val xHi = xs.maxOrNull() ?: 1.0
        val ys = pts.map { it.second }
        val yLo = ys.minOrNull() ?: 0.0; val yHi = ys.maxOrNull() ?: 1.0

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "断点回归"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("跳跃=%.2f".format(jump), fontSize = 9.sp,
                     color = if (nL >= 2 && nR >= 2) ZxT.PriLite else ZxT.Err)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(104.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height; val pad = 10f
                    val xs2 = (xHi - xLo).coerceAtLeast(1e-9); val ys2 = (yHi - yLo).coerceAtLeast(1e-9)
                    val px = { v: Double -> pad + ((v - xLo) / xs2 * (W - 2 * pad)).toFloat() }
                    val py = { v: Double -> H - pad - ((v - yLo) / ys2 * (H - 2 * pad)).toFloat() }
                    // 带宽：不画出来用户不知道拟合用了哪些点
                    drawRect(ZxT.PriLite.copy(.07f),
                        Offset(px(cut - bw), 0f), Size((px(cut + bw) - px(cut - bw)), H))
                    pts.forEachIndexed { k, (x, y) ->
                        drawCircle(if (k == sel) Color.White else PAL[0].copy(.55f),
                                   if (k == sel) 3.4f else 2.2f, Offset(px(x), py(y)))
                    }
                    listOf(-1 to Triple(bl, al, xLo), 1 to Triple(br, ar, cut)).forEach { (sd, t) ->
                        val (bb, aa, from) = t
                        val to = if (sd < 0) cut else xHi
                        drawLine(PAL[1], Offset(px(from), py(aa + bb * from)),
                                 Offset(px(to), py(aa + bb * to)), 1.8f)
                    }
                    drawLine(ZxT.Err, Offset(px(cut), 0f), Offset(px(cut), H), 1.5f)
                    // 跳跃：两条拟合线在断点处的垂直距离，就是因果效应
                    drawLine(PAL[1], Offset(px(cut), py(al + bl * cut)),
                             Offset(px(cut), py(ar + br * cut)), 2.4f)
                }
                Row(Modifier.fillMaxWidth().height(104.dp)) {
                    repeat(n) { k ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = k
                            ctx.callTool("zx_select", mapOf(
                                "x" to "%.3f".format(pts[k].first),
                                "y" to "%.3f".format(pts[k].second),
                                "jump" to "%.3f".format(jump), "source" to "zx_rdd"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text("带宽 ±${"%.2f".format(bw)}　左 $nL 点 / 右 $nR 点",
                    fontSize = 8.5.sp, color = ZxT.Text3, modifier = Modifier.weight(1f))
                listOf(0.25, 0.5, 1.0).forEach { v ->
                    Text("±$v", fontSize = 8.sp,
                         color = if (abs(bw - v) < 1e-9) ZxT.PriLite else ZxT.Text3,
                         modifier = Modifier.padding(start = 4.dp)
                             .clip(RoundedCornerShape(7.dp))
                             .background(if (abs(bw - v) < 1e-9) ZxT.Surface2 else Color.Transparent)
                             .clickable { bw = v }.padding(6.dp, 1.dp))
                }
            }
            if (nL < 2 || nR < 2)
                Text("带宽内一侧样本少于 2 个——跳跃估不出来", fontSize = 9.sp,
                     color = ZxT.Err, modifier = Modifier.padding(top = 3.dp))
            Text("已有 scatter 看<b>相关性</b>、regress 给<b>一条线</b>，" +
                 "这个在断点两侧<b>各拟合一条</b>——两条线在断点处的落差就是效应。" +
                 "关键是<b>断点附近的人是可比的</b>（差一点就过线、差一点就没过），" +
                 "所以不需要假设两组本来就一样。" +
                 "<b>断点落在数据范围外会被拦</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_iv 工具变量 ══════════ */
    @Composable
    fun iv(o: JSONObject, ctx: RenderContext) {
        val ptsA = o.optJSONArray("pts") ?: return
        val n = minOf(ptsA.length(), 30)
        val zs = (0 until n).map { ptsA.optJSONObject(it)?.optDouble("z", 0.0) ?: 0.0 }
        val xs = (0 until n).map { ptsA.optJSONObject(it)?.optDouble("x", 0.0) ?: 0.0 }
        val ys = (0 until n).map { ptsA.optJSONObject(it)?.optDouble("y", 0.0) ?: 0.0 }
        var stage by remember { mutableIntStateOf(0) }
        var sel by remember { mutableIntStateOf(-1) }

        fun ols(a: List<Double>, b: List<Double>): Pair<Double, Double> {
            val ma = a.average(); val mb = b.average()
            var num = 0.0; var den = 0.0
            a.indices.forEach { k -> num += (a[k] - ma) * (b[k] - mb); den += (a[k] - ma) * (a[k] - ma) }
            val bb = if (den > 1e-12) num / den else 0.0
            return bb to (mb - bb * ma)
        }
        val (b1, a1) = ols(zs, xs)             // 一阶段：Z → X
        val fitted = zs.map { a1 + b1 * it }
        val (b2, _) = ols(fitted, ys)          // 二阶段：X̂ → Y
        val (bNaive, aNaive) = ols(xs, ys)     // 朴素 OLS：X → Y
        // 一阶段 F：弱工具变量会让 IV 估计严重偏
        val res = xs.indices.map { xs[it] - fitted[it] }
        val ess = res.sumOf { it * it }
        val tss = xs.map { (it - xs.average()) * (it - xs.average()) }.sum()
        val r2 = if (tss > 1e-12) 1 - ess / tss else 0.0
        val f = if (n > 2 && r2 < 1) (r2 / (1 - r2)) * (n - 2) else 99.0
        val weak = f < 10

        val px = when (stage) { 0 -> zs to xs; 1 -> fitted to ys; else -> xs to ys }
        val loA = px.first.minOrNull() ?: 0.0; val hiA = px.first.maxOrNull() ?: 1.0
        val loB = px.second.minOrNull() ?: 0.0; val hiB = px.second.maxOrNull() ?: 1.0

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "工具变量"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("IV=%.2f".format(b2), fontSize = 9.sp, color = ZxT.PriLite,
                     fontFamily = ZxT.Mono)
            }
            Spacer(Modifier.height(5.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("一阶段 Z→X", "二阶段 X̂→Y", "朴素 OLS").forEachIndexed { k, lb ->
                    Text(lb, fontSize = 8.5.sp,
                         color = if (stage == k) ZxT.PriLite else ZxT.Text3,
                         modifier = Modifier.clip(RoundedCornerShape(7.dp))
                             .background(if (stage == k) ZxT.Surface2 else Color.Transparent)
                             .clickable { stage = k }.padding(7.dp, 2.dp))
                }
            }
            Spacer(Modifier.height(5.dp))
            Box(Modifier.fillMaxWidth().height(92.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height; val pad = 10f
                    val sa = (hiA - loA).coerceAtLeast(1e-9); val sb = (hiB - loB).coerceAtLeast(1e-9)
                    val fx = { v: Double -> pad + ((v - loA) / sa * (W - 2 * pad)).toFloat() }
                    val fy = { v: Double -> H - pad - ((v - loB) / sb * (H - 2 * pad)).toFloat() }
                    val (a, b) = px
                    a.indices.forEach { k ->
                        drawCircle(if (k == sel) Color.White else PAL[0].copy(.5f),
                                   if (k == sel) 3.4f else 2.2f, Offset(fx(a[k]), fy(b[k])))
                    }
                    // 二阶段用的是 X̂，截距在 X̂ 尺度上另算，这里画斜率
                    val (fitB, fitA) = when (stage) {
                        0 -> b1 to a1
                        1 -> b2 to (ys.average() - b2 * fitted.average())
                        else -> bNaive to aNaive
                    }
                    drawLine(PAL[1], Offset(fx(loA), fy(fitA + fitB * loA)),
                             Offset(fx(hiA), fy(fitA + fitB * hiA)), 1.8f)
                }
                Row(Modifier.fillMaxWidth().height(92.dp)) {
                    repeat(n) { k ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable { sel = k })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(when (stage) { 0 -> "X = ${"%.2f".format(a1)} + ${"%.2f".format(b1)}·Z"
                    1 -> "Y = ${"%.2f".format(b2)}·X̂" else -> "Y = ${"%.2f".format(aNaive)} + ${"%.2f".format(bNaive)}·X" },
                    fontSize = 9.sp, color = ZxT.Text, fontFamily = ZxT.Mono,
                    modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (weak) ZxT.Err.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (weak) "!" else "✓", fontSize = 11.sp,
                     color = if (weak) ZxT.Err else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text("一阶段 F=${"%.1f".format(f)}（R²=${"%.3f".format(r2)}）" +
                            if (weak) " —— 弱工具变量" else " —— 工具变量够强",
                        fontSize = 9.sp, color = if (weak) ZxT.Err else ZxT.Ok)
                    Text("朴素 OLS 给 ${"%.2f".format(bNaive)}，IV 给 ${"%.2f".format(b2)}——" +
                            "差得越多说明内生性越强",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 regress 给<b>一条回归线</b>" +
                 "（X 与误差相关时那条线就是偏的），" +
                 "这个找一个<b>只通过 X 影响 Y</b> 的 Z：" +
                 "先用 Z 预测 X，再用预测值回归 Y。" +
                 "<b>F&lt;10 是弱工具变量</b>——这时 IV 估计比朴素 OLS 还差，必须提示。" +
                 "<b>缺 z 会被拦</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_sense 敏感性分析 ══════════ */
    @Composable
    fun sense(o: JSONObject, ctx: RenderContext) {
        val est = o.optDouble("est", 0.0)
        val se = o.optDouble("se", 1.0).coerceAtLeast(1e-9)
        var gamma by remember { mutableFloatStateOf(o.optDouble("g", 1.0).toFloat()) }
        // 未测混杂强度 Γ：结论在多大强度下崩掉
        val adj = est / gamma
        val lo = adj - 1.96 * se * gamma
        val hi = adj + 1.96 * se * gamma
        val flip = lo <= 0 && hi >= 0
        val crit = run {
            var g = 1.0
            while (g < 12.0) {
                val a = est / g
                if ((a - 1.96 * se * g) <= 0 && (a + 1.96 * se * g) >= 0) break
                g += 0.05
            }
            g
        }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "敏感性分析"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (flip) "结论站不住" else "结论仍成立", fontSize = 9.sp,
                     color = if (flip) ZxT.Err else ZxT.Ok)
            }
            Spacer(Modifier.height(6.dp))
            // 区间条：跨过 0 就是结论翻转
            Box(Modifier.fillMaxWidth().height(38.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height
                    val range = 3.0 * se * 3.0
                    val px = { v: Double -> ((v + range) / (2 * range) * W).toFloat().coerceIn(0f, W) }
                    drawLine(ZxT.Surface3, Offset(0f, H / 2), Offset(W, H / 2), 1f)
                    drawLine(ZxT.Err, Offset(px(0.0), 4f), Offset(px(0.0), H - 4f), 1.6f)
                    drawRoundRect(if (flip) ZxT.Err.copy(.6f) else PAL[0].copy(.7f),
                        Offset(px(lo), H / 2 - 7f),
                        Size((px(hi) - px(lo)).coerceAtLeast(2f), 14f),
                        androidx.compose.ui.geometry.CornerRadius(3f, 3f))
                    drawCircle(Color.White, 3.4f, Offset(px(adj), H / 2))
                }
            }
            Row(Modifier.fillMaxWidth()) {
                Text("${"%.2f".format(lo)}", fontSize = 8.sp, color = ZxT.Text3,
                     modifier = Modifier.weight(1f))
                Text("调整后估计 ${"%.2f".format(adj)}", fontSize = 8.5.sp, color = ZxT.Text)
                Text("${"%.2f".format(hi)}", fontSize = 8.sp, color = ZxT.Text3,
                     modifier = Modifier.weight(1f), textAlign = TextAlign.End)
            }
            Spacer(Modifier.height(5.dp))
            Text("未测混杂强度 Γ = ${"%.2f".format(gamma)}（拖下方调）",
                fontSize = 8.5.sp, color = ZxT.Text3)
            Row(Modifier.fillMaxWidth().height(26.dp)) {
                repeat(24) { k ->
                    val g = 1.0 + k * 0.25
                    Box(Modifier.weight(1f).fillMaxHeight().clickable { gamma = g.toFloat() }
                        .background(if (abs(gamma - g) < 0.13) ZxT.PriLite else ZxT.Surface3))
                }
            }
            Row(Modifier.fillMaxWidth()) {
                Text("Γ=1（无混杂）", fontSize = 7.5.sp, color = ZxT.Text3,
                     modifier = Modifier.weight(1f))
                Text("Γ=6.75（很强）", fontSize = 7.5.sp, color = ZxT.Text3)
            }
            Text("临界 Γ ≈ ${"%.2f".format(crit)}——未测混杂强到这个程度，结论就翻转",
                fontSize = 9.sp, color = if (crit < 1.5) ZxT.Err else if (crit < 3) ZxT.Warn else ZxT.Ok,
                modifier = Modifier.padding(top = 4.dp))
            Text("已有 forest 合并<b>多个研究</b>、zx_test 给<b>一个 p</b>，" +
                 "这个回答「<b>未测的混杂要多强才能推翻这个结论</b>」——" +
                 "以前「可能有未测混杂」是一句免责声明，" +
                 "现在它是<b>一个可拖的强度</b>，用户能看到结论在哪崩。" +
                 "临界 Γ&lt;1.5 说明结论很脆弱。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_psm 倾向得分匹配 ══════════ */
    @Composable
    fun psm(o: JSONObject, ctx: RenderContext) {
        val unitsA = o.optJSONArray("units") ?: return
        val n = minOf(unitsA.length(), 24)
        val units = (0 until n).mapNotNull {
            val u = unitsA.optJSONObject(it) ?: return@mapNotNull null
            val csA = u.optJSONArray("cs")
            Triple(u.optInt("t", 0), u.optDouble("p", 0.5),
                (0 until (csA?.length() ?: 0)).map { k -> csA!!.optDouble(k, 0.0) })
        }
        var matched by remember { mutableStateOf(true) }
        val nCov = units.firstOrNull()?.third?.size ?: 0
        // 最近邻匹配：按倾向得分找异组最近的一个
        val pairs = remember(units) {
            val t = units.withIndex().filter { it.value.first == 1 }
            val c = units.withIndex().filter { it.value.first == 0 }
            t.mapNotNull { ti ->
                val best = c.minByOrNull { abs(it.value.second - ti.value.second) }
                if (best == null) null else ti.index to best.index
            }
        }
        val use = if (matched) pairs.flatMap { listOf(it.first, it.second) }.distinct() else units.indices.toList()
        fun smd(k: Int): Double {
            val ta = use.filter { units[it].first == 1 }.mapNotNull { units[it].third.getOrNull(k) }
            val ca = use.filter { units[it].first == 0 }.mapNotNull { units[it].third.getOrNull(k) }
            if (ta.isEmpty() || ca.isEmpty()) return 0.0
            val m1 = ta.average(); val m2 = ca.average()
            val s1 = if (ta.size > 1) sqrt(ta.map { (it - m1) * (it - m1) }.average()) else 0.0
            val s2 = if (ca.size > 1) sqrt(ca.map { (it - m2) * (it - m2) }.average()) else 0.0
            val pool = sqrt((s1 * s1 + s2 * s2) / 2).coerceAtLeast(1e-9)
            return (m1 - m2) / pool
        }
        var sel by remember { mutableIntStateOf(-1) }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "倾向得分匹配"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (matched) "${pairs.size} 对" else "未匹配", fontSize = 9.sp,
                     color = if (matched) ZxT.Ok else ZxT.Text3)
            }
            Spacer(Modifier.height(5.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(true to "匹配后", false to "匹配前").forEach { (v, lb) ->
                    Text(lb, fontSize = 8.5.sp,
                         color = if (matched == v) ZxT.PriLite else ZxT.Text3,
                         modifier = Modifier.clip(RoundedCornerShape(7.dp))
                             .background(if (matched == v) ZxT.Surface2 else Color.Transparent)
                             .clickable { matched = v }.padding(7.dp, 2.dp))
                }
            }
            Spacer(Modifier.height(5.dp))
            // 倾向得分分布：两组重叠才匹配得上
            Box(Modifier.fillMaxWidth().height(46.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height
                    val bins = 12
                    val ct = IntArray(bins); val cc = IntArray(bins)
                    units.forEach { (t, p, _) ->
                        val b = (p.coerceIn(0.0, 0.999) * bins).toInt()
                        if (t == 1) ct[b]++ else cc[b]++
                    }
                    val mx = maxOf(ct.maxOrNull() ?: 1, cc.maxOrNull() ?: 1, 1)
                    val bw = W / bins
                    (0 until bins).forEach { k ->
                        val ht = ct[k].toFloat() / mx * (H / 2 - 2)
                        val hc = cc[k].toFloat() / mx * (H / 2 - 2)
                        drawRoundRect(PAL[0].copy(.7f), Offset(k * bw + 1f, H / 2 - ht),
                            Size(bw - 2f, ht.coerceAtLeast(0.5f)),
                            androidx.compose.ui.geometry.CornerRadius(1.5f, 1.5f))
                        drawRoundRect(ZxT.Text3.copy(.6f), Offset(k * bw + 1f, H / 2 + 2),
                            Size(bw - 2f, hc.coerceAtLeast(0.5f)),
                            androidx.compose.ui.geometry.CornerRadius(1.5f, 1.5f))
                    }
                    drawLine(ZxT.Surface3, Offset(0f, H / 2), Offset(W, H / 2), 1f)
                }
            }
            Row(Modifier.fillMaxWidth()) {
                Text("倾向得分分布（上=处理组，下=对照组）",
                    fontSize = 8.sp, color = ZxT.Text3, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(4.dp))
            // SMD：匹配后应落进 ±0.1
            (0 until nCov).forEach { k ->
                val v = smd(k)
                val ok = abs(v) <= 0.1
                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (sel == k) ZxT.Surface2 else Color.Transparent)
                    .clickable { sel = k }.padding(5.dp, 2.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(o.optJSONArray("covs")?.optString(k, "协变量${k + 1}") ?: "协变量${k + 1}",
                        fontSize = 9.sp, color = ZxT.Text2, modifier = Modifier.width(58.dp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Box(Modifier.weight(1f).height(10.dp)) {
                        Canvas(Modifier.fillMaxSize()) {
                            val W = size.width; val H = size.height
                            drawLine(ZxT.Surface3, Offset(0f, H / 2), Offset(W, H / 2), 1f)
                            // ±0.1 带
                            drawRect(ZxT.Ok.copy(.12f), Offset(W * 0.4f, 0f), Size(W * 0.2f, H))
                            val px = (0.5 + v.coerceIn(-0.6, 0.6) / 1.2 * 1.0).toFloat().coerceIn(0.02f, 0.98f) * W
                            drawCircle(if (ok) ZxT.Ok else ZxT.Warn, 3.2f, Offset(px, H / 2))
                        }
                    }
                    Text("%.2f".format(v), fontSize = 8.5.sp,
                         color = if (ok) ZxT.Ok else ZxT.Warn, modifier = Modifier.width(30.dp),
                         textAlign = TextAlign.End, fontFamily = ZxT.Mono)
                }
            }
            Text("已有 balance 看<b>协变量平衡</b>、dualist 做<b>配对</b>，" +
                 "这个按倾向得分<b>自动找最近的异组对象</b>配对——" +
                 "两组得分分布不重叠的地方<b>根本配不上</b>（common support），" +
                 "硬配会得到很差的匹配。SMD 绝对值 ≤0.1 才算平衡。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_balance 协变量平衡 ══════════ */
    @Composable
    fun balance(o: JSONObject, ctx: RenderContext) {
        val covsA = o.optJSONArray("covs") ?: return
        val n = minOf(covsA.length(), 8)
        val covs = (0 until n).mapNotNull {
            val c = covsA.optJSONObject(it) ?: return@mapNotNull null
            val aA = c.optJSONArray("a"); val bA = c.optJSONArray("b")
            Triple(c.optString("n", "协变量${it + 1}"),
                (0 until (aA?.length() ?: 0)).map { k -> aA!!.optDouble(k, 0.0) },
                (0 until (bA?.length() ?: 0)).map { k -> bA!!.optDouble(k, 0.0) })
        }
        var sel by remember { mutableIntStateOf(-1) }
        fun smd(a: List<Double>, b: List<Double>): Double {
            val m1 = a.average(); val m2 = b.average()
            val s1 = if (a.size > 1) sqrt(a.map { (it - m1) * (it - m1) }.average()) else 0.0
            val s2 = if (b.size > 1) sqrt(b.map { (it - m2) * (it - m2) }.average()) else 0.0
            return (m1 - m2) / sqrt((s1 * s1 + s2 * s2) / 2).coerceAtLeast(1e-9)
        }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "协变量平衡"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                val worst = covs.maxOfOrNull { abs(smd(it.second, it.third)) } ?: 0.0
                Text(if (worst <= 0.1) "已平衡" else "未平衡", fontSize = 9.sp,
                     color = if (worst <= 0.1) ZxT.Ok else ZxT.Warn)
            }
            Spacer(Modifier.height(6.dp))
            // Love plot：SMD 横轴，±0.1 是公认阈值带
            Box(Modifier.fillMaxWidth()) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
                        Text("协变量", fontSize = 8.sp, color = ZxT.Text3,
                             modifier = Modifier.width(58.dp))
                        Box(Modifier.weight(1f).height(10.dp)) {
                            Canvas(Modifier.fillMaxSize()) {
                                drawRect(ZxT.Ok.copy(.12f), Offset(size.width * 0.4f, 0f),
                                    Size(size.width * 0.2f, size.height))
                            }
                        }
                        Text("SMD", fontSize = 8.sp, color = ZxT.Text3,
                             modifier = Modifier.width(30.dp), textAlign = TextAlign.End)
                    }
                    covs.forEachIndexed { k, (nm, a, b) ->
                        val v = smd(a, b)
                        val ok = abs(v) <= 0.1
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (sel == k) ZxT.Surface2 else Color.Transparent)
                            .clickable {
                                sel = k
                                ctx.callTool("zx_select", mapOf(
                                    "cov" to nm, "smd" to "%.3f".format(v),
                                    "ma" to "%.2f".format(a.average()),
                                    "mb" to "%.2f".format(b.average()),
                                    "balanced" to (if (ok) "yes" else "no"),
                                    "source" to "zx_balance"))
                            }.padding(5.dp, 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(nm, fontSize = 9.sp,
                                 color = if (sel == k) ZxT.Text else ZxT.Text2,
                                 modifier = Modifier.width(58.dp), maxLines = 1,
                                 overflow = TextOverflow.Ellipsis)
                            Box(Modifier.weight(1f).height(12.dp)) {
                                Canvas(Modifier.fillMaxSize()) {
                                    val W = size.width; val H = size.height
                                    drawLine(ZxT.Surface3, Offset(0f, H / 2), Offset(W, H / 2), 1f)
                                    drawRect(ZxT.Ok.copy(.12f), Offset(W * 0.4f, 0f),
                                        Size(W * 0.2f, H))
                                    drawLine(ZxT.Text3, Offset(W * 0.5f, 0f), Offset(W * 0.5f, H), 1.1f)
                                    val px = ((0.5 + v.coerceIn(-0.8, 0.8) / 1.6) * W)
                                        .toFloat().coerceIn(0.03f, 0.97f)
                                    drawCircle(if (ok) ZxT.Ok else ZxT.Warn, 3.4f, Offset(px, H / 2))
                                }
                            }
                            Text("%.2f".format(v), fontSize = 8.5.sp,
                                 color = if (ok) ZxT.Ok else ZxT.Warn,
                                 modifier = Modifier.width(30.dp), textAlign = TextAlign.End,
                                 fontFamily = ZxT.Mono)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                Text("−0.8", fontSize = 7.5.sp, color = ZxT.Text3,
                     modifier = Modifier.weight(1f).padding(start = 58.dp))
                Text("0（±0.1 绿色带内为平衡）", fontSize = 7.5.sp, color = ZxT.Text3)
                Text("+0.8", fontSize = 7.5.sp, color = ZxT.Text3,
                     modifier = Modifier.width(30.dp), textAlign = TextAlign.End)
            }
            Text(if (sel >= 0) "「${covs[sel].first}」处理组均值 ${"%.2f".format(covs[sel].second.average())}，" +
                    "对照组 ${"%.2f".format(covs[sel].third.average())}"
                 else "点行看两组均值",
                fontSize = 9.5.sp, color = ZxT.Text3, modifier = Modifier.padding(top = 3.dp))
            Text("已有 dualist 做<b>配对</b>、zx_test 比<b>两组差异</b>，" +
                 "这个回答「<b>这两组除了处理之外像不像</b>」——" +
                 "这是可比性的前提，两组本来就差很远的话，" +
                 "比出来的差异不是处理造成的。" +
                 "用 SMD 而不是 p 值：<b>p 值随样本量变，SMD 不随</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_missing 缺失值模式 ══════════ */
    @Composable
    fun missing(o: JSONObject, ctx: RenderContext) {
        val cols = o.optJSONArray("cols") ?: return
        val nc = minOf(cols.length(), 8)
        val names = (0 until nc).map { cols.optString(it, "列${it + 1}") }
        val rowsA = o.optJSONArray("rows") ?: return
        val nr = minOf(rowsA.length(), 14)
        val grid = (0 until nr).map { i ->
            val r = rowsA.optJSONArray(i)
            (0 until nc).map { j -> r?.optInt(j, 0) ?: 0 }
        }
        var sel by remember { mutableIntStateOf(-1) }
        val colMiss = (0 until nc).map { j -> grid.count { it[j] == 1 } }
        // 成对缺失相关：某两列是否总是一起缺（MAR/MNAR 的线索）
        fun pair(j1: Int, j2: Int): Double {
            val both = grid.count { it[j1] == 1 && it[j2] == 1 }
            val e1 = grid.count { it[j1] == 1 }; val e2 = grid.count { it[j2] == 1 }
            if (e1 == 0 || e2 == 0) return 0.0
            return both.toDouble() / minOf(e1, e2).coerceAtLeast(1)
        }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "缺失值模式"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                val total = grid.flatten().count { it == 1 }
                Text("缺失 ${"%.1f".format(total.toDouble() / (nr * nc).coerceAtLeast(1) * 100)}%",
                    fontSize = 9.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(6.dp))
            // 矩阵：深色 = 缺失。看有没有"一条竖线"或"一整块"
            Box(Modifier.fillMaxWidth()) {
                Canvas(Modifier.fillMaxSize().height((nr * 9 + 2).dp)) {
                    val W = size.width; val H = size.height
                    val cw = W / nc; val ch = H / nr
                    grid.forEachIndexed { i, r ->
                        r.forEachIndexed { j, v ->
                            drawRect(if (v == 1) ZxT.Err.copy(.75f) else ZxT.Surface2,
                                Offset(j.toFloat() * cw + 0.5f, i.toFloat() * ch + 0.5f),
                                Size(cw - 1f, ch - 1f))
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().height((nr * 9 + 2).dp)) {
                    repeat(nr) { i ->
                        Row(Modifier.fillMaxWidth().weight(1f)) {
                            repeat(nc) { j ->
                                Box(Modifier.weight(1f).fillMaxHeight().clickable { sel = j })
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                names.forEachIndexed { j, nm ->
                    Text(nm, fontSize = 7.sp, color = if (sel == j) ZxT.Text else ZxT.Text3,
                         modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                         maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(4.dp))
            colMiss.forEachIndexed { j, c ->
                val pct = c.toDouble() / nr.coerceAtLeast(1)
                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(names[j], fontSize = 8.5.sp, color = ZxT.Text2,
                         modifier = Modifier.width(58.dp), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    Box(Modifier.weight(1f).height(7.dp)
                        .clip(RoundedCornerShape(3.dp)).background(ZxT.Surface2)) {
                        Box(Modifier.fillMaxWidth(pct.toFloat().coerceAtLeast(0.01f))
                            .fillMaxHeight()
                            .background(if (pct > 0.4) ZxT.Err else if (pct > 0.15) ZxT.Warn else ZxT.Ok))
                    }
                    Text("${"%.0f".format(pct * 100)}%", fontSize = 8.sp, color = ZxT.Text3,
                         modifier = Modifier.width(30.dp), textAlign = TextAlign.End)
                }
            }
            if (sel >= 0) {
                val top = (0 until nc).filter { it != sel }
                    .map { names[it] to pair(sel, it) }.filter { it.second > 0.35 }
                    .sortedByDescending { it.second }.take(2)
                if (top.isNotEmpty())
                    Text("「${names[sel]}」缺失时，${top.joinToString("、") { "${it.first} 也缺 ${"%.0f".format(it.second * 100)}%" }}——" +
                            "一起缺说明不是随机缺失",
                        fontSize = 9.sp, color = ZxT.Warn, modifier = Modifier.padding(top = 4.dp))
            }
            Text("已有 heat 看<b>数值大小</b>、matrix 看<b>关系强度</b>，" +
                 "这个只看<b>缺没缺</b>。要看的是<b>缺失有没有结构</b>：" +
                 "随机散点（MCAR，还比较好办）、" +
                 "<b>整列一起缺或两列一起缺</b>（MAR/MNAR，直接删会偏）。" +
                 "<b>行宽不齐会被拦</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_seqtest 序贯检验 ══════════ */
    @Composable
    fun seqtest(o: JSONObject, ctx: RenderContext) {
        val looksA = o.optJSONArray("looks") ?: return
        val k = minOf(looksA.length(), 10)
        val looks = (0 until k).map {
            val l = looksA.optJSONObject(it) ?: JSONObject()
            l.optDouble("z", 0.0) to l.optString("n", "第${it + 1}次")
        }
        val alpha = o.optDouble("alpha", 0.05)
        var fn by remember { mutableStateOf(o.optString("fn", "obrien")) }
        var sel by remember { mutableIntStateOf(-1) }
        // α 消耗函数：看 K 次就要把 α 分着花，不然假阳性会涨到 3 倍
        fun bound(i: Int): Double {
            val frac = (i + 1.0) / k
            return when (fn) {
                "pocock" -> alpha * (1.0 - 0.0) * (1.0 / k) * 2.2
                "obrien" -> {
                    // O'Brien-Fleming：z 边界 ∝ 1/√t，早期极严
                    val zb = 4.0 / sqrt(frac)
                    (1 - kotlin.math.min(0.9999, 0.5 * (1 + erf(zb / sqrt(2.0))))) * 2
                }
                else -> alpha
            }
        }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "序贯检验"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("${k} 次看", fontSize = 9.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(5.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("obrien" to "O'Brien", "pocock" to "Pocock", "none" to "不校正").forEach { (v, lb) ->
                    Text(lb, fontSize = 8.5.sp,
                         color = if (fn == v) ZxT.PriLite else ZxT.Text3,
                         modifier = Modifier.clip(RoundedCornerShape(7.dp))
                             .background(if (fn == v) ZxT.Surface2 else Color.Transparent)
                             .clickable { fn = v }.padding(7.dp, 2.dp))
                }
            }
            Spacer(Modifier.height(5.dp))
            Box(Modifier.fillMaxWidth().height(88.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height; val pad = 8f
                    val maxZ = maxOf(4.0, looks.maxOf { abs(it.first) } + 0.5)
                    val px = { i: Int -> pad + (i.toDouble() / (k - 1).coerceAtLeast(1) * (W - 2 * pad)).toFloat() }
                    val py = { v: Double -> H / 2 - (v / maxZ * (H / 2 - pad)).toFloat() }
                    drawLine(ZxT.Surface3, Offset(0f, H / 2), Offset(W, H / 2), 1f)
                    // 边界
                    (0 until k).forEach { i ->
                        val b = bound(i)
                        val zb = zFromP(b / 2)
                        drawCircle(ZxT.Warn, 2.6f, Offset(px(i), py(zb)))
                        drawCircle(ZxT.Warn, 2.6f, Offset(px(i), py(-zb)))
                    }
                    drawPath(Path().apply {
                        (0 until k).forEach { i ->
                            val zb = zFromP(bound(i) / 2)
                            val x = px(i); val y = py(zb)
                            if (i == 0) moveTo(x, y) else lineTo(x, y)
                        }
                    }, ZxT.Warn.copy(.6f), style = Stroke(1.2f))
                    // 实际 z 轨迹
                    drawPath(Path().apply {
                        looks.forEachIndexed { i, (z, _) ->
                            val x = px(i); val y = py(z)
                            if (i == 0) moveTo(x, y) else lineTo(x, y)
                        }
                    }, PAL[0], style = Stroke(1.9f))
                    looks.forEachIndexed { i, (z, _) ->
                        val b = bound(i)
                        val zb = zFromP(b / 2)
                        val cross = abs(z) >= zb
                        drawCircle(if (cross) ZxT.Err else if (i == sel) Color.White else PAL[0],
                                   if (i == sel || cross) 4.2f else 2.8f, Offset(px(i), py(z)))
                    }
                }
                Row(Modifier.fillMaxWidth().height(88.dp)) {
                    repeat(k) { i ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = i
                            ctx.callTool("zx_select", mapOf(
                                "look" to looks[i].second, "z" to "%.3f".format(looks[i].first),
                                "bound" to "%.4f".format(bound(i)),
                                "cross" to (if (abs(looks[i].first) >= zFromP(bound(i) / 2)) "yes" else "no"),
                                "source" to "zx_seqtest"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                looks.forEachIndexed { i, l ->
                    Text(l.second, fontSize = 7.sp,
                         color = if (sel == i) ZxT.Text else ZxT.Text3,
                         modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                         maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            val firstCross = looks.indices.firstOrNull { abs(looks[it].first) >= zFromP(bound(it) / 2) }
            Text(if (firstCross != null) "第 ${firstCross + 1} 次（${looks[firstCross].second}）越界，可提前停止"
                 else "全程未越界——尚不能下结论",
                fontSize = 9.sp, color = if (firstCross != null) ZxT.Ok else ZxT.Warn,
                modifier = Modifier.padding(top = 3.dp))
            Text("已有 zx_test 给<b>一次</b>检验的 p、" +
                 "zx_hypo 校<b>多个指标</b>，" +
                 "这个是<b>同一批数据看多次</b>——" +
                 "每多看一次就多一次犯错机会，看 5 次假阳性会涨到约 14%。" +
                 "所以边界要<b>随次数收紧</b>：O'Brien 早期极严、后期放松。" +
                 "<b>切到「不校正」看边界变平</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_bayes 贝叶斯更新 ══════════ */
    @Composable
    fun bayes(o: JSONObject, ctx: RenderContext) {
        val prior = o.optDouble("prior", 0.5).coerceIn(0.001, 0.999)
        val lrPos = o.optDouble("lp", 2.0)
        val lrNeg = o.optDouble("ln", 0.5)
        var nPos by remember { mutableIntStateOf(o.optInt("np", 1)) }
        var nNeg by remember { mutableIntStateOf(o.optInt("nn", 0)) }
        var sel by remember { mutableIntStateOf(-1) }

        fun odds(p: Double) = p / (1 - p).coerceAtLeast(1e-9)
        fun fromOdds(o2: Double) = o2 / (1 + o2)
        var cur = odds(prior)
        repeat(nPos) { cur *= lrPos }
        repeat(nNeg) { cur *= lrNeg }
        val post = fromOdds(cur)
        val curve = (0..10).map { t ->
            val c = odds(prior) * Math.pow(lrPos, t.toDouble())
            fromOdds(c)
        }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "贝叶斯更新"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("后验 ${"%.1f".format(post * 100)}%", fontSize = 9.sp, color = ZxT.PriLite,
                     fontFamily = ZxT.Mono)
            }
            Spacer(Modifier.height(6.dp))
            // 先验 → 后验的推进条
            Row(Modifier.fillMaxWidth().height(14.dp)
                .clip(RoundedCornerShape(7.dp)).background(ZxT.Surface2)) {
                Box(Modifier.weight(prior.toFloat().coerceAtLeast(0.01f)).fillMaxHeight()
                    .background(ZxT.Text3))
                Box(Modifier.weight((post - prior).coerceAtLeast(0.0).toFloat().coerceAtLeast(0.005f))
                    .fillMaxHeight().background(PAL[0]))
                Box(Modifier.weight((1 - post).coerceAtLeast(0.0).toFloat().coerceAtLeast(0.005f))
                    .fillMaxHeight())
            }
            Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                Text("先验 ${"%.1f".format(prior * 100)}%", fontSize = 8.5.sp, color = ZxT.Text3,
                     modifier = Modifier.weight(1f))
                Text("→ 后验 ${"%.1f".format(post * 100)}%", fontSize = 8.5.sp, color = ZxT.Text)
                Text("变化 ${"%+.1f".format((post - prior) * 100)}pp", fontSize = 8.5.sp,
                     color = if (post > prior) ZxT.Ok else ZxT.Err)
            }
            Spacer(Modifier.height(5.dp))
            // 证据累积曲线
            Box(Modifier.fillMaxWidth().height(58.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height; val pad = 8f
                    val px = { t: Int -> pad + (t.toDouble() / 10 * (W - 2 * pad)).toFloat() }
                    val py = { v: Double -> H - pad - (v * (H - 2 * pad)).toFloat() }
                    drawPath(Path().apply {
                        curve.forEachIndexed { t, v ->
                            val x = px(t); val y = py(v)
                            if (t == 0) moveTo(x, y) else lineTo(x, y)
                        }
                    }, PAL[0], style = Stroke(1.8f))
                    curve.forEachIndexed { t, v ->
                        drawCircle(if (t == nPos) Color.White else PAL[0].copy(.6f),
                                   if (t == nPos) 4f else 2.4f, Offset(px(t), py(v)))
                    }
                }
                Row(Modifier.fillMaxWidth().height(58.dp)) {
                    repeat(11) { t ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            nPos = t; sel = t
                            ctx.callTool("zx_select", mapOf(
                                "npos" to t.toString(),
                                "post" to "%.4f".format(curve[t]),
                                "source" to "zx_bayes"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                Text("每多一条正向证据，信念往上走", fontSize = 8.sp, color = ZxT.Text3,
                     modifier = Modifier.weight(1f))
                Text("似然比 +$lrPos / ×$lrNeg", fontSize = 8.sp, color = ZxT.Text3)
            }
            Text("先验 ${"%.1f".format(prior * 100)}% → 后验 ${"%.1f".format(post * 100)}%" +
                    "（${nPos} 条正向${if (nNeg > 0) "、${nNeg} 条反向" else ""}）：" +
                    "比 ${odds(prior).toFloat()} → ${"%.2f".format(odds(post))}",
                fontSize = 9.sp, color = ZxT.Text3, modifier = Modifier.padding(top = 3.dp))
            Text("已有 zx_test 给<b>频率派的 p</b>（在假设为真的前提下看数据多极端），" +
                 "这个反过来：<b>在数据已看到之后更新信念</b>。" +
                 "关键是<b>先验要显式写出来</b>——" +
                 "点曲线看信念怎么一步一步走，" +
                 "同一个似然比下先验不同结论会差很远。" +
                 "<b>先验不在 0-1 内会被拦</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_anomaly 异常检测 ══════════ */
    @Composable
    fun anomaly(o: JSONObject, ctx: RenderContext) {
        val dataA = o.optJSONArray("data") ?: return
        val n = minOf(dataA.length(), 32)
        val xs = (0 until n).map { dataA.optDouble(it, 0.0) }
        var method by remember { mutableStateOf(o.optString("method", "z")) }
        var sel by remember { mutableIntStateOf(-1) }
        val mu = xs.average()
        val sd = sqrt(xs.map { (it - mu) * (it - mu) }.average()).coerceAtLeast(1e-9)
        val sorted = xs.sorted()
        val q1 = sorted[n / 4]; val q3 = sorted[3 * n / 4]
        val iqr = (q3 - q1).coerceAtLeast(1e-9)
        val med = sorted[n / 2]
        val flags = xs.map { v ->
            when (method) {
                "z" -> abs((v - mu) / sd) > 3
                "iqr" -> v < q1 - 1.5 * iqr || v > q3 + 1.5 * iqr
                else -> abs(v - med) / iqr > 3      // 中位数绝对偏差，抗自身含异常
            }
        }
        val lo = xs.minOrNull() ?: 0.0; val hi = xs.maxOrNull() ?: 1.0
        val sp = (hi - lo).coerceAtLeast(1e-9)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "异常检测"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("${flags.count { it }} 个异常", fontSize = 9.sp,
                     color = if (flags.any { it }) ZxT.Warn else ZxT.Ok)
            }
            Spacer(Modifier.height(5.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("z" to "3σ", "iqr" to "1.5×IQR", "mad" to "MAD").forEach { (k, lb) ->
                    Text(lb, fontSize = 8.5.sp,
                         color = if (method == k) ZxT.PriLite else ZxT.Text3,
                         modifier = Modifier.clip(RoundedCornerShape(7.dp))
                             .background(if (method == k) ZxT.Surface2 else Color.Transparent)
                             .clickable { method = k }.padding(7.dp, 2.dp))
                }
            }
            Spacer(Modifier.height(5.dp))
            Box(Modifier.fillMaxWidth().height(58.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height; val pad = 6f
                    val px = { k: Int -> pad + (k.toDouble() / (n - 1).coerceAtLeast(1) * (W - 2 * pad)).toFloat() }
                    val py = { v: Double -> H - pad - ((v - lo) / sp * (H - 2 * pad)).toFloat() }
                    when (method) {
                        "z" -> {
                            drawLine(ZxT.Surface3, Offset(0f, py(mu)), Offset(W, py(mu)), 1f)
                            drawRect(PAL[0].copy(.08f), Offset(0f, py(mu + 3 * sd)),
                                Size(W, (py(mu - 3 * sd) - py(mu + 3 * sd))))
                        }
                        "iqr" -> drawRect(PAL[0].copy(.08f), Offset(0f, py(q1 - 1.5 * iqr)),
                            Size(W, (py(q3 + 1.5 * iqr) - py(q1 - 1.5 * iqr)).coerceAtLeast(1f)))
                        else -> drawRect(PAL[0].copy(.08f), Offset(0f, py(med - 3 * iqr)),
                            Size(W, (py(med + 3 * iqr) - py(med - 3 * iqr)).coerceAtLeast(1f)))
                    }
                    drawPath(Path().apply {
                        xs.forEachIndexed { k, v ->
                            val x = px(k); val y = py(v)
                            if (k == 0) moveTo(x, y) else lineTo(x, y)
                        }
                    }, ZxT.Text3.copy(.55f), style = Stroke(1f))
                    xs.forEachIndexed { k, v ->
                        val on = flags[k]
                        drawCircle(if (k == sel) Color.White else if (on) ZxT.Err else PAL[0],
                                   if (k == sel || on) 3.6f else 2.2f, Offset(px(k), py(v)))
                    }
                }
                Row(Modifier.fillMaxWidth().height(58.dp)) {
                    repeat(n) { k ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = k
                            ctx.callTool("zx_select", mapOf(
                                "i" to k.toString(), "v" to "%.3f".format(xs[k]),
                                "z" to "%.2f".format((xs[k] - mu) / sd),
                                "flag" to (if (flags[k]) "yes" else "no"),
                                "method" to method, "source" to "zx_anomaly"))
                        })
                    }
                }
            }
            Text(if (sel >= 0) "第 $sel 点 = ${"%.2f".format(xs[sel])}　z=${"%.2f".format((xs[sel] - mu) / sd)}　" +
                    if (flags[sel]) "判为异常" else "正常"
                 else "点数据点看它的 z 值",
                fontSize = 9.5.sp, color = ZxT.Text3, modifier = Modifier.padding(top = 3.dp))
            Text("已有 box 画<b>箱体</b>、histogram 看<b>分布</b>，" +
                 "这个回答「<b>哪几个点是异常的</b>」。" +
                 "三种判据并列是因为<b>它们给的答案不一样</b>：" +
                 "3σ 用均值标准差（<b>异常本身会把 σ 拉大，掩蔽自己</b>）、" +
                 "MAD 用中位数（抗掩蔽）。<b>切方法看红点数变化</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_power 功效分析 ══════════ */
    @Composable
    fun power(o: JSONObject, ctx: RenderContext) {
        val d = o.optDouble("d", 0.5).coerceAtLeast(0.01)
        val alpha = o.optDouble("alpha", 0.05)
        var n by remember { mutableIntStateOf(o.optInt("n", 30)) }
        var sel by remember { mutableIntStateOf(-1) }
        val zA = 1.96
        // 功效：n 每格时正确检出效应 d 的概率
        fun pow(nn: Int): Double {
            val ncp = d * sqrt(nn / 2.0)
            return kotlin.math.min(0.9999, kotlin.math.max(0.0001,
                0.5 * (1 + erf((ncp - zA) / sqrt(2.0)))))
        }
        // 需要的样本量
        val need = run {
            var v = 4
            while (v < 4000 && pow(v) < 0.8) v += 2
            v
        }
        val curve = (0..24).map { t -> val nn = 10 + t * 10; nn to pow(nn) }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "功效分析"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("功效 ${"%.0f".format(pow(n) * 100)}%", fontSize = 9.sp,
                     color = if (pow(n) >= 0.8) ZxT.Ok else ZxT.Warn, fontFamily = ZxT.Mono)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(76.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height; val pad = 8f
                    val maxN = 250.0
                    val px = { v: Double -> pad + (v / maxN * (W - 2 * pad)).toFloat() }
                    val py = { v: Double -> H - pad - (v * (H - 2 * pad)).toFloat() }
                    // 0.8 线：功效的标准门槛
                    drawLine(ZxT.Ok.copy(.7f), Offset(0f, py(0.8)), Offset(W, py(0.8)), 1.2f)
                    drawPath(Path().apply {
                        curve.forEachIndexed { t, (nn, p) ->
                            val x = px(nn.toDouble()); val y = py(p)
                            if (t == 0) moveTo(x, y) else lineTo(x, y)
                        }
                    }, PAL[0], style = Stroke(1.8f))
                    curve.forEach { (nn, p) ->
                        val on = abs(nn - n) < 5
                        drawCircle(if (on) Color.White else PAL[0].copy(.55f),
                                   if (on) 4f else 2.2f, Offset(px(nn.toDouble()), py(p)))
                    }
                }
                Row(Modifier.fillMaxWidth().height(76.dp)) {
                    repeat(25) { t ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            n = 10 + t * 10; sel = t
                            ctx.callTool("zx_select", mapOf(
                                "n" to n.toString(),
                                "power" to "%.4f".format(pow(n)),
                                "need80" to need.toString(), "source" to "zx_power"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                Text("n=10", fontSize = 7.5.sp, color = ZxT.Text3, modifier = Modifier.weight(1f))
                Text("样本量 →", fontSize = 7.5.sp, color = ZxT.Text3)
                Text("n=250", fontSize = 7.5.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(4.dp))
            // 功效条
            Box(Modifier.fillMaxWidth().height(12.dp)
                .clip(RoundedCornerShape(6.dp)).background(ZxT.Surface2)) {
                Box(Modifier.fillMaxWidth(pow(n).toFloat()).fillMaxHeight()
                    .background(if (pow(n) >= 0.8) ZxT.Ok else ZxT.Warn))
                Box(Modifier.fillMaxSize()) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawLine(Color.White, Offset(size.width * 0.8f, 0f),
                                 Offset(size.width * 0.8f, size.height), 1.4f)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                Text("n=$n，效应量 d=$d，α=$alpha", fontSize = 8.5.sp, color = ZxT.Text3,
                     modifier = Modifier.weight(1f))
                Text("需 n≥$need", fontSize = 8.5.sp,
                     color = if (n >= need) ZxT.Ok else ZxT.Warn)
            }
            Text("已有 zx_test 给<b>是否显著</b>、zx_hypo 校<b>多重</b>，" +
                 "这个回答「<b>不显著是真没差还是没看出来</b>」——" +
                 "功效 80% 意味着：真有这个效应时，仍有 20% 的概率**看不出来**。" +
                 "<b>不显著 + 低功效 = 什么都没说明</b>，这一点必须让用户看见。" +
                 "<b>效应量为 0 会被拦</b>：样本量无穷大。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ── 小函数 ─────────────────────────────────────────────── */
    private fun erf(x: Double): Double {
        val s = if (x < 0) -1.0 else 1.0
        val ax = abs(x)
        val t = 1.0 / (1.0 + 0.3275911 * ax)
        val y = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t
                - 0.284496736) * t + 0.254829592) * t * exp(-ax * ax)
        return s * y
    }

    /** 由双尾 p 反解 z */
    private fun zFromP(p: Double): Double {
        val q = (p / 2).coerceIn(1e-6, 0.4999999)
        var lo = 0.0; var hi = 6.0
        repeat(40) {
            val mid = (lo + hi) / 2
            if ((1 - 0.5 * (1 + erf(mid / sqrt(2.0)))) > q) lo = mid else hi = mid
        }
        return (lo + hi) / 2
    }
}
