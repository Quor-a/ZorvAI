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

/**
 * 第三十二批组件（12 个）—— 议价、竞标与序贯决策。
 *
 * 前三十一批有一条共同的假设：**世界是固定的，我要算出最优**。
 *
 *   看什么 / 填什么 / 凭什么 / 是不是因果     → 已经有数据
 *   接下来 / 做完了 / 治理                    → 基于已有数据行动
 *   模拟与推演                                → 还没发生，但机制已知
 *
 * 第三十一批已经把"没有数据"这个口子撕开了，但它还有一条残留假设：
 * **推演是一次性的**——我算出一个结果，然后照着做。
 *
 * 真实决策不是这样。真实决策是**在过程中还能改**：
 *
 *   谈判桌上可以还价
 *   拍卖里可以加价
 *   可以等下一个信息来了再定
 *   可以先做一半看反应再决定剩下那一半
 *
 * 十二个组件分成四组：
 *
 *   **议价**（双方都在争取同一块剩余）
 *     zx_bargain       议价区间：ZOPA 在哪，剩余怎么分
 *     zx_auction       竞标：中标者与**赢者诅咒**
 *     zx_multiissue    多议题交换：**价值在交换不在让步**
 *
 *   **组合与前沿**（没有"最优"，只有"你愿意换什么"）
 *     zx_pareto        Pareto 前沿：标出被支配的点
 *     zx_portfolio     组合优化：权重可拖，看风险收益怎么动
 *     zx_bidcurve      出价曲线：供需找均衡价
 *
 *   **序贯**（信息会逐步揭示）
 *     zx_sequential    序贯决策：**信息的价值 vs 等待的代价**
 *     zx_stoprule      最优停止：37% 规则
 *     zx_hedge         对冲：把风险换成**你能承受的形态**
 *
 *   **过程结构**（在过程中怎么改）
 *     zx_contingent    应急计划：情景 → 动作
 *     zx_escalate      升级决策：什么时候该叫人
 *     zx_commit        承诺可信度：**无代价的承诺不可信**
 *
 * 贯穿这批的一条：**决策的价值不只在选择，还在"什么时候定、留多少余地"**。
 *
 *   ZOPA 存在不代表能成交       → 双方都想要全部剩余
 *   中标即亏损                  → 赢者诅咒，中标说明你估得最乐观
 *   让步换不来价值，交换才能     → 各自在不在乎的议题上让步
 *   前沿上没有最优              → 只有"你愿意在哪个维度上换"
 *   等到的信息不值等待的代价     → 那就不该等
 *   对冲成本超过敞口            → 对冲比不对冲还亏
 *   承诺没有代价                → 对方不信，等于没说
 *
 *   **只算"选哪个"，不算"什么时候定"，会把灵活性当成免费的。**
 *   而灵活性恰恰是最贵的——保留选择权就要放弃确定性，
 *   放弃确定性就要承受别人先动的风险。
 *
 * 校验因此换了第十档。前九档拦的是
 *   "画不出来" → "统计前提不成立" → "区间前提不成立" →
 *   "因果前提无从判断" → "看起来确定了其实没那么确定" →
 *   "状态摆出来了但推不出该做什么" → "记下来了但下次用不上" →
 *   "数字都在但落不到谁该做什么" → "推演本身不可信"，
 * 这批拦的是
 *   **"决策做出来了，但它的前提在过程中会变"**：
 *
 *   bargain      保留价格**不重叠**（无 ZOPA，必然谈不成，必须明说）
 *   auction      出价 < 保留价 / 提示赢者诅咒
 *   multiissue   双方权重**完全相同**（没有交换空间，退化成零和）
 *   pareto       只有一个目标（不是多目标）/ 点全在前沿（没有可剔除的）
 *   portfolio    权重和不为 1
 *   sequential   信息成本 > 信息价值（**不该等**）
 *   stoprule     n < 3（37% 规则不成立）
 *   hedge        对冲成本 ≥ 敞口（**对冲比不对冲还亏**）
 *   contingent   触发条件重叠 / 永远不成立
 *   escalate     升级成本 > 问题本身损失（**永远不该升级**）
 *   commit       承诺无代价（**不可信**）
 *   bidcurve     供需无交点（**无均衡价**）
 */
object ZxRender32 {

    private val PAL = listOf(
        Color(0xFF5046E4), Color(0xFF3FBF7F), Color(0xFFE8A33D),
        Color(0xFFE5544B), Color(0xFF4A9BE8), Color(0xFF8E86FF)
    )

    private fun jarr(o: JSONObject, k: String): List<JSONObject> {
        val a = o.optJSONArray(k) ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }
    private fun jdarr(o: JSONObject, k: String): List<Double> {
        val a = o.optJSONArray(k) ?: return emptyList()
        return (0 until a.length()).map { a.optDouble(it, Double.NaN) }
    }
    private fun jstr(o: JSONObject, k: String): List<String> {
        val a = o.optJSONArray(k) ?: return emptyList()
        return (0 until a.length()).map { a.optString(it, "") }
    }

    /* ══════════ zx_bargain 议价区间 ══════════ */
    @Composable
    fun bargain(o: JSONObject, ctx: RenderContext) {
        // 我方（买方）最高可接受 vs 对方（卖方）最低可接受
        val myMax = o.optDouble("mymax", 0.0)
        val hisMin = o.optDouble("hismin", 0.0)
        val lo = o.optDouble("lo", 0.0)
        val hi = o.optDouble("hi", max(myMax, hisMin) * 1.4)
        var offer by remember { mutableDoubleStateOf(o.optDouble("offer", (myMax + hisMin) / 2)) }
        val hasZopa = hisMin <= myMax
        val zopa = if (hasZopa) myMax - hisMin else Double.NaN
        // 成交价落在 ZOPA 里的位置 = 我方拿到的剩余比例
        val myShare = if (hasZopa && zopa > 0) (myMax - offer) / zopa else Double.NaN

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "议价区间"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (!hasZopa) "**无成交区**" else "ZOPA ${"%.0f".format(zopa)}",
                    fontSize = 9.sp, color = if (!hasZopa) ZxT.Err else ZxT.Ok)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(46.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height
                    fun px(v: Double) = ((v - lo) / (hi - lo) * W).toFloat().coerceIn(0f, W)
                    // ZOPA 区
                    if (hasZopa)
                        drawRect(ZxT.Ok.copy(alpha = .18f), Offset(px(hisMin), 0f),
                            Size(px(myMax) - px(hisMin), H))
                    // 整体量程
                    drawRect(ZxT.Surface3, Offset(px(lo), H / 2 - 1f), Size(px(hi) - px(lo), 2f))
                    drawLine(ZxT.Err, Offset(px(myMax), 0f), Offset(px(myMax), H), 1.4f)
                    drawLine(PAL[4], Offset(px(hisMin), 0f), Offset(px(hisMin), H), 1.4f)
                    // 当前报价
                    drawCircle(Color.White, 4f, Offset(px(offer), H / 2))
                    drawLine(ZxT.Primary, Offset(px(offer), 0f), Offset(px(offer), H), 1.2f)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Text("红线我方上限 / 蓝线对方下限", fontSize = 7.5.sp, color = ZxT.Text3,
                     modifier = Modifier.weight(1f))
                Text(if (hasZopa) "绿区 = 可成交" else "**没有绿区**", fontSize = 7.5.sp,
                     color = if (hasZopa) ZxT.Ok else ZxT.Err)
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("报价", fontSize = 8.5.sp, color = ZxT.Text3,
                     modifier = Modifier.width(34.dp))
                SliderBar(value = offer, min = lo, max = hi, onValue = { offer = it })
                Text("%.0f".format(offer), fontSize = 9.sp, color = ZxT.Text,
                     modifier = Modifier.width(34.dp), textAlign = TextAlign.End,
                     fontFamily = ZxT.Mono)
            }
            Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                listOf("我方上限" to "%.0f".format(myMax), "对方下限" to "%.0f".format(hisMin),
                       "我方剩余" to (if (myShare.isNaN()) "—" else "%.0f%%".format(myShare * 100)))
                    .forEach { (k, v) ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(v, fontSize = 9.5.sp, color = ZxT.Text, fontFamily = ZxT.Mono)
                        Text(k, fontSize = 7.5.sp, color = ZxT.Text3)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (!hasZopa) ZxT.Err.copy(.10f)
                            else if (offer > myMax || offer < hisMin) ZxT.Warn.copy(.10f)
                            else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (!hasZopa) "✗" else if (offer > myMax || offer < hisMin) "!" else "✓",
                     fontSize = 11.sp,
                     color = if (!hasZopa) ZxT.Err
                             else if (offer > myMax || offer < hisMin) ZxT.Warn else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (!hasZopa) "**对方下限 ${"%.0f".format(hisMin)} > 我方上限 " +
                            "${"%.0f".format(myMax)}**——**没有成交区间**。" +
                            "再怎么谈也不会成，要么改预算，要么换一家"
                         else if (offer > myMax) "报价 ${"%.0f".format(offer)} 超过我方上限——" +
                            "**成交了也是亏的**"
                         else if (offer < hisMin) "报价 ${"%.0f".format(offer)} 低于对方下限——" +
                            "对方不会接受"
                         else "成交价 ${"%.0f".format(offer)}，我方拿到 " +
                            "${"%.0f%%".format(myShare * 100)} 的剩余",
                        fontSize = 9.sp,
                        color = if (!hasZopa) ZxT.Err
                                else if (offer > myMax || offer < hisMin) ZxT.Warn else ZxT.Ok)
                    Text("**ZOPA 存在只是必要条件**——双方都想要全部剩余，" +
                        "能不能成还取决于谁更不着急",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 payoff 处理<b>结果取决于双方选择</b>，" +
                 "这个处理的是<b>双方在争同一块剩余</b>。" +
                 "区别是：payoff 里收益是外生给定的矩阵，" +
                 "这里<b>剩余总量由成交价决定</b>，你多拿的就是我少拿的。" +
                 "关键是<b>无 ZOPA 要明说</b>——拖着谈只会浪费双方时间。" +
                 "<b>保留价格不重叠会被拦</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_auction 竞标 ══════════ */
    @Composable
    fun auction(o: JSONObject, ctx: RenderContext) {
        val bids = jarr(o, "bids").map {
            Triple(it.optString("n", "竞标方"), it.optDouble("v", 0.0), it.optDouble("e", 0.0))
        }.sortedByDescending { it.second }   // e = 该方对标的的真实估值
        var sel by remember { mutableIntStateOf(-1) }
        val reserve = o.optDouble("reserve", 0.0)
        val winner = bids.firstOrNull { it.second >= reserve }
        // 赢者诅咒：中标者的出价超过自己对标的的估值
        val cursed = winner != null && winner.second > winner.third
        val mx = max(bids.maxOf { it.second }, reserve).coerceAtLeast(1.0)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "竞标"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (winner == null) "**流标**" else if (cursed) "**赢者诅咒**" else "成交",
                    fontSize = 9.sp,
                    color = if (winner == null) ZxT.Warn else if (cursed) ZxT.Err else ZxT.Ok)
            }
            Spacer(Modifier.height(6.dp))
            bids.forEachIndexed { k, (nm, v, e) ->
                val isWin = k == 0 && v >= reserve
                val over = v > e
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(when {
                        isWin && over -> ZxT.Err.copy(.10f)
                        isWin -> ZxT.Ok.copy(.12f)
                        sel == k -> ZxT.Surface2
                        else -> Color.Transparent })
                    .clickable {
                        sel = k
                        ctx.callTool("zx_select", mapOf(
                            "bidder" to nm, "bid" to "%.0f".format(v),
                            "value" to "%.0f".format(e),
                            "overpay" to (if (over) "%.0f".format(v - e) else "0"),
                            "source" to "zx_auction"))
                    }.padding(5.dp, 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (isWin) "★" else "　", fontSize = 8.sp,
                         color = if (over) ZxT.Err else ZxT.Ok,
                         modifier = Modifier.width(12.dp))
                    Text(nm, fontSize = 9.sp, color = ZxT.Text2,
                         modifier = Modifier.width(52.dp), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    Box(Modifier.weight(1f).height(8.dp)
                        .clip(RoundedCornerShape(3.dp)).background(ZxT.Surface2)) {
                        Box(Modifier.fillMaxWidth((v / mx).toFloat().coerceIn(0.02f, 1f))
                            .fillMaxHeight()
                            .background(if (isWin && over) ZxT.Err else PAL[k % PAL.size]))
                        // 估值标记
                        Box(Modifier.fillMaxSize()) {
                            Canvas(Modifier.fillMaxSize()) {
                                drawLine(Color.White,
                                    Offset(size.width * (e / mx).toFloat(), 0f),
                                    Offset(size.width * (e / mx).toFloat(), size.height), 1.1f)
                            }
                        }
                    }
                    Text("%.0f".format(v), fontSize = 8.5.sp, color = ZxT.Text,
                         modifier = Modifier.width(32.dp), textAlign = TextAlign.End,
                         fontFamily = ZxT.Mono)
                    Text(if (over) "超 ${"%.0f".format(v - e)}" else "", fontSize = 7.5.sp,
                         color = ZxT.Err, modifier = Modifier.width(34.dp),
                         textAlign = TextAlign.End)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Text("白竖线 = 该方对标的的真实估值", fontSize = 7.5.sp, color = ZxT.Text3,
                     modifier = Modifier.weight(1f))
                Text("保留价 ${"%.0f".format(reserve)}", fontSize = 7.5.sp, color = ZxT.Text3)
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (winner == null) ZxT.Warn.copy(.10f)
                            else if (cursed) ZxT.Err.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (winner == null) "!" else if (cursed) "✗" else "✓", fontSize = 11.sp,
                     color = if (winner == null) ZxT.Warn else if (cursed) ZxT.Err else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (winner == null) "**流标**——最高出价 ${"%.0f".format(bids.first().second)} " +
                            "低于保留价 ${"%.0f".format(reserve)}"
                         else if (cursed) "**${winner.first} 中标，但出价 ${"%.0f".format(winner.second)} " +
                            "超过自己的估值 ${"%.0f".format(winner.third)}**——" +
                            "**中标即亏损 ${"%.0f".format(winner.second - winner.third)}**。" +
                            "在估值不确定的竞标里，中标往往意味着你估得最乐观"
                         else "${winner.first} 中标，出价 ${"%.0f".format(winner.second)}，" +
                            "估值 ${"%.0f".format(winner.third)}，赚 " +
                            "${"%.0f".format(winner.third - winner.second)}",
                        fontSize = 9.sp,
                        color = if (winner == null) ZxT.Warn else if (cursed) ZxT.Err else ZxT.Ok)
                    Text("**赢者诅咒**：出价最高的人，往往是估值最乐观的人。" +
                        "中标这个事实本身就是一条负面信息——说明别人都觉得不值",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 payoff 是<b>收益矩阵</b>、bargain 是<b>一对一议价</b>，" +
                 "这个处理的是<b>多方竞争、价高者得</b>。" +
                 "区别是：bargain 里只有一个对手，这里有多个且<b>别人的出价本身就是信息</b>。" +
                 "关键是<b>要标出超估值的出价</b>——那不是勇猛，是亏损。" +
                 "<b>出价低于保留价会被拦</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_multiissue 多议题交换 ══════════ */
    @Composable
    fun multiissue(o: JSONObject, ctx: RenderContext) {
        val issues = jarr(o, "issues").map {
            it.optString("n", "议题") to (it.optDouble("a", 1.0) to it.optDouble("b", 1.0))
        }
        // 双方对各议题的权重不同 → 交换空间
        val n = issues.size
        var alloc by remember { mutableStateOf(List(n) { 0.5 }) }  // 0 = 全给 B，1 = 全给 A
        fun score(w: List<Pair<Double, Double>>, f: (Int) -> Double): String {
            val a = issues.indices.sumOf { i -> w[i].first * f(i) }
            val b = issues.indices.sumOf { i -> w[i].second * (1 - f(i)) }
            return "%.1f / %.1f".format(a, b)
        }
        val cur = score(issues.map { it.second }) { alloc[it] }
        val half = score(issues.map { it.second }) { 0.5 }
        // 最优：按「A 权重 / B 权重」比排序，比值高的全给 A
        val order = issues.indices.sortedByDescending { i ->
            issues[i].second.first / issues[i].second.second.coerceAtLeast(0.01) }
        var cut by remember { mutableIntStateOf(n / 2) }
        val best = buildString {
            val a = order.take(cut).sumOf { i -> issues[i].second.first }
            val b = order.drop(cut).sumOf { i -> issues[i].second.second }
            append("%.1f / %.1f".format(a, b))
        }
        val totalW = issues.sumOf { it.second.first + it.second.second }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "多议题谈判"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("联合收益 $cur", fontSize = 9.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(6.dp))
            issues.forEachIndexed { k, (nm, w) ->
                val ratio = w.first / w.second.coerceAtLeast(0.01)
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clickable {
                        alloc = alloc.mapIndexed { i, v -> if (i == k) 1 - v else v }
                        ctx.callTool("zx_select", mapOf(
                            "issue" to nm, "to" to (if (alloc[k] > 0.5) "A" else "B"),
                            "source" to "zx_multiissue"))
                    }.padding(5.dp, 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(nm, fontSize = 9.sp, color = ZxT.Text2,
                         modifier = Modifier.width(54.dp), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    // 双方权重对比
                    Box(Modifier.width(56.dp).height(8.dp)
                        .clip(RoundedCornerShape(3.dp)).background(ZxT.Surface2)) {
                        Box(Modifier.fillMaxWidth((w.first / max(w.first, w.second)).toFloat()
                            .coerceIn(0.05f, 1f)).fillMaxHeight().background(PAL[0]))
                    }
                    Text("${"%.1f".format(w.first)}:${"%.1f".format(w.second)}",
                        fontSize = 7.5.sp, color = ZxT.Text3,
                        modifier = Modifier.width(38.dp))
                    Box(Modifier.weight(1f).height(8.dp)
                        .clip(RoundedCornerShape(3.dp)).background(ZxT.Surface2)) {
                        Box(Modifier.fillMaxWidth(alloc[k].toFloat()).fillMaxHeight()
                            .background(if (ratio > 1.2) PAL[0] else if (ratio < 0.83) PAL[4]
                                        else ZxT.Text3))
                    }
                    Text(if (alloc[k] > 0.5) "A" else "B", fontSize = 8.sp, color = ZxT.Text,
                         modifier = Modifier.width(10.dp), textAlign = TextAlign.End)
                }
            }
            Spacer(Modifier.height(3.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("交换", fontSize = 8.5.sp, color = ZxT.Text3,
                     modifier = Modifier.width(34.dp))
                SliderBar(value = cut.toDouble(), min = 0.0, max = n.toDouble(),
                          onValue = { cut = it.toInt().coerceIn(0, n) })
                Text(cut.toString(), fontSize = 9.sp, color = ZxT.Text,
                     modifier = Modifier.width(24.dp), textAlign = TextAlign.End,
                     fontFamily = ZxT.Mono)
            }
            Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                listOf("各让一半" to half, "最优交换" to best,
                       "当前" to cur).forEach { (k, v) ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(v, fontSize = 9.sp, color = if (k == "最优交换") ZxT.Ok else ZxT.Text,
                             fontFamily = ZxT.Mono)
                        Text(k, fontSize = 7.5.sp, color = ZxT.Text3)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("✓", fontSize = 11.sp, color = ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    val gain = best.split(" / ")[0].toDouble() + best.split(" / ")[1].toDouble() -
                        half.split(" / ")[0].toDouble() - half.split(" / ")[1].toDouble()
                    Text("最优交换 $best vs 各让一半 $half——**交换能创造 " +
                            "${"%.1f".format(gain)} 的额外联合收益**",
                        fontSize = 9.sp, color = ZxT.Ok)
                    Text("**价值在交换不在让步**。按「A 的权重 ÷ B 的权重」排序，" +
                        "比值高的给 A、低的给 B——双方各自在自己不在乎的议题上让步",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 bargain 只能谈<b>一个价格</b>，" +
                 "这个处理的是<b>多个议题可以同时谈</b>。" +
                 "区别是：单议题是零和的（你多拿我就少拿），" +
                 "多议题<b>可以做到双方都变好</b>——因为权重不同。" +
                 "关键是<b>要按权重比排序</b>，不是各让一半。" +
                 "<b>双方权重完全相同会被拦</b>（没有交换空间，退化成零和）。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_pareto Pareto 前沿 ══════════ */
    @Composable
    fun pareto(o: JSONObject, ctx: RenderContext) {
        val pts = jarr(o, "pts").map {
            Triple(it.optString("n", "方案"), it.optDouble("x", 0.0), it.optDouble("y", 0.0))
        }
        var sel by remember { mutableIntStateOf(-1) }
        // 支配：x 和 y 都不差且至少一项更好（两个都取大越好）
        fun dominated(i: Int): Boolean = pts.indices.any { j ->
            j != i && pts[j].second >= pts[i].second && pts[j].third >= pts[i].third &&
            (pts[j].second > pts[i].second || pts[j].third > pts[i].third) }
        val front = pts.indices.filter { !dominated(it) }.sortedBy { pts[it].second }
        val mxX = pts.maxOf { it.second }.coerceAtLeast(1.0)
        val mxY = pts.maxOf { it.third }.coerceAtLeast(1.0)
        val xLabel = o.optString("xname", "维度一")
        val yLabel = o.optString("yname", "维度二")

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "Pareto 前沿"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("${front.size}/${pts.size} 在前沿", fontSize = 9.sp, color = ZxT.Ok)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(84.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height
                    fun px(v: Double) = (v / mxX * (W - 16f) + 12f).toFloat()
                    fun py(v: Double) = (H - 10f - v / mxY * (H - 22f)).toFloat()
                    // 前沿折线
                    if (front.size >= 2)
                        drawPath(Path().apply {
                            front.forEachIndexed { k, i ->
                                val x = px(pts[i].second); val y = py(pts[i].third)
                                if (k == 0) moveTo(x, y) else lineTo(x, y)
                            }
                        }, ZxT.Ok, style = Stroke(1.6f))
                    pts.forEachIndexed { k, (_, x, y) ->
                        val d = dominated(k)
                        drawCircle(if (d) ZxT.Text3.copy(alpha = .55f)
                                   else if (sel == k) Color.White else PAL[k % PAL.size],
                            if (sel == k) 4.5f else 3.4f, Offset(px(x), py(y)))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Text("$xLabel → / $yLabel ↑，都是越大越好", fontSize = 7.5.sp,
                     color = ZxT.Text3, modifier = Modifier.weight(1f))
                Text("灰点 = 被支配", fontSize = 7.5.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(4.dp))
            pts.forEachIndexed { k, (nm, x, y) ->
                val d = dominated(k)
                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (sel == k) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_select", mapOf(
                            "option" to nm, "x" to "%.1f".format(x), "y" to "%.1f".format(y),
                            "dominated" to (if (d) "yes" else "no"),
                            "source" to "zx_pfront"))
                    }.padding(5.dp, 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(RoundedCornerShape(2.dp))
                        .background(if (d) ZxT.Text3 else PAL[k % PAL.size]))
                    Spacer(Modifier.width(5.dp))
                    Text(nm, fontSize = 9.sp,
                         color = if (d) ZxT.Text3 else ZxT.Text2,
                         modifier = Modifier.weight(1f), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    Text("%.1f / %.1f".format(x, y), fontSize = 8.sp, color = ZxT.Text,
                         fontFamily = ZxT.Mono)
                    Text(if (d) "被支配" else "前沿", fontSize = 7.5.sp,
                         color = if (d) ZxT.Text3 else ZxT.Ok,
                         modifier = Modifier.width(34.dp), textAlign = TextAlign.End)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(ZxT.Warn.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("!", fontSize = 11.sp, color = ZxT.Warn)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text("${pts.size - front.size} 个点被支配，可以直接剔除——" +
                            "**它们在任何维度上都不比别人好**",
                        fontSize = 9.sp, color = ZxT.Warn)
                    Text("**前沿上没有「最优」，只有「你愿意在哪个维度上换」**。" +
                        "选前沿上的哪一点是偏好问题，不是计算问题——计算只能帮你剔除被支配的",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 decision 做<b>加权打分</b>（把多目标压成一个分）、" +
                 "scatter 画<b>两个变量的散点</b>，" +
                 "这个做的是<b>不加权、直接标出支配关系</b>。" +
                 "区别是：加权要先知道权重，而权重往往正是谈不拢的东西。" +
                 "关键是<b>先剔除被支配的，再谈偏好</b>——顺序反了会浪费时间在坏选项上。" +
                 "<b>只有一个目标会被拦</b>；<b>点全在前沿会被拦</b>（没有可剔除的）。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_portfolio 组合优化 ══════════ */
    @Composable
    fun portfolio(o: JSONObject, ctx: RenderContext) {
        val items = jarr(o, "items").map {
            Triple(it.optString("n", "资产"), it.optDouble("r", 0.0), it.optDouble("s", 0.0))
        }   // r = 预期收益(%)，s = 波动率(%)
        val n = items.size
        var w by remember { mutableStateOf(List(n) { 1.0 / n }) }
        var drag by remember { mutableIntStateOf(-1) }
        // 简化：假设两两相关系数固定 rho
        val rho = o.optDouble("rho", 0.3)
        fun ret() = items.indices.sumOf { w[it] * items[it].second }
        fun risk(): Double {
            var v = 0.0
            for (i in 0 until n) for (j in 0 until n) {
                val cov = if (i == j) items[i].third * items[i].third
                          else rho * items[i].third * items[j].third
                v += w[i] * w[j] * cov
            }
            return sqrt(max(v, 0.0))
        }
        val r = ret(); val s = risk()
        val sharpe = if (s > 0.01) r / s else 0.0
        val sumW = w.sum()

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "组合优化"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("夏普 ${"%.2f".format(sharpe)}", fontSize = 9.sp,
                     color = if (sharpe > 0.5) ZxT.Ok else ZxT.Warn)
            }
            Spacer(Modifier.height(6.dp))
            items.forEachIndexed { k, (nm, rr, ss) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(nm, fontSize = 9.sp, color = ZxT.Text2,
                         modifier = Modifier.width(46.dp), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    Text("${"%.0f".format(rr)}%/${"%.0f".format(ss)}%", fontSize = 7.5.sp,
                         color = ZxT.Text3, modifier = Modifier.width(42.dp))
                    SliderBar(value = w[k], min = 0.0, max = 1.0) { nv ->
                        // 只有一个能自由变，其余按比例摊回，保证和为 1
                        val rest = 1.0 - nv
                        val otherSum = w.filterIndexed { i, _ -> i != k }.sum()
                        w = if (otherSum <= 1e-9) List(n) { if (it == k) nv else rest / (n - 1) }
                            else w.mapIndexed { i, v ->
                                if (i == k) nv else v / otherSum * rest }
                        drag = k
                    }
                    Text("%.0f%%".format(w[k] * 100), fontSize = 8.5.sp, color = ZxT.Text,
                         modifier = Modifier.width(34.dp), textAlign = TextAlign.End,
                         fontFamily = ZxT.Mono)
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth()) {
                listOf("预期收益" to "%.1f%%".format(r), "组合风险" to "%.1f%%".format(s),
                       "权重和" to "%.2f".format(sumW)) .forEach { (k, v) ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(v, fontSize = 9.5.sp,
                             color = if (k == "权重和" && abs(sumW - 1.0) > 0.01) ZxT.Err
                                     else ZxT.Text,
                             fontFamily = ZxT.Mono)
                        Text(k, fontSize = 7.5.sp, color = ZxT.Text3)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (sharpe > 0.5) ZxT.Ok.copy(.10f) else ZxT.Warn.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (sharpe > 0.5) "✓" else "!", fontSize = 11.sp,
                     color = if (sharpe > 0.5) ZxT.Ok else ZxT.Warn)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text("预期 ${"%.1f%%".format(r)}，风险 ${"%.1f%%".format(s)}——" +
                            "**分散化让组合风险 ${"%.1f%%".format(s)} 低于" +
                            "加权平均的 ${"%.1f%%".format(items.indices.sumOf { w[it] * items[it].third })}**",
                        fontSize = 9.sp, color = if (sharpe > 0.5) ZxT.Ok else ZxT.Warn)
                    Text("**分散化是唯一免费的午餐**——只要不是完全正相关，" +
                        "组合风险就低于各部分风险的加权平均（这里 ρ=${"%.2f".format(rho)}）",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 optim 做<b>单约束分配</b>、tornado 看<b>单因素敏感</b>，" +
                 "这个处理的是<b>收益与风险两个目标同时权衡</b>。" +
                 "区别是：optim 里目标是确定的，这里<b>收益本身就是不确定的</b>，" +
                 "而且要利用相关性。" +
                 "关键是<b>拖权重时其余要按比例摊回</b>——不然和不为 1，组合就无意义了。" +
                 "<b>权重和不为 1 会被拦</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_bidcurve 出价曲线 ══════════ */
    @Composable
    fun bidcurve(o: JSONObject, ctx: RenderContext) {
        val demand = jdarr(o, "demand")   // 各价格下的需求量（降序价格）
        val supply = jdarr(o, "supply")   // 各价格下的供给量
        val prices = jdarr(o, "prices")
        val n = minOf(demand.size, supply.size, prices.size)
        var eqIdx = -1
        for (i in 0 until n) if (supply[i] >= demand[i]) { eqIdx = i; break }
        val mx = max(demand.maxOrNull() ?: 1.0, supply.maxOrNull() ?: 1.0).coerceAtLeast(1.0)
        val mnP = prices.minOrNull() ?: 0.0
        val mxP = prices.maxOrNull()?.let { if (it == mnP) mnP + 1 else it } ?: 1.0
        var sel by remember { mutableIntStateOf(eqIdx) }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "出价曲线"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (eqIdx < 0) "**无均衡价**" else "均衡 ${"%.0f".format(prices[eqIdx])}",
                    fontSize = 9.sp, color = if (eqIdx < 0) ZxT.Err else ZxT.Ok)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(78.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height
                    fun px(v: Double) = (v / mx * (W - 10f) + 5f).toFloat()
                    fun py(p: Double) = (H - 6f - (p - mnP) / (mxP - mnP) * (H - 12f)).toFloat()
                    fun path(d: List<Double>, c: Color) = drawPath(Path().apply {
                        d.take(n).forEachIndexed { i, v ->
                            val x = px(v); val y = py(prices[i])
                            if (i == 0) moveTo(x, y) else lineTo(x, y)
                        }
                    }, c, style = Stroke(1.8f))
                    path(demand, PAL[0]); path(supply, PAL[2])
                    if (eqIdx >= 0) {
                        drawLine(ZxT.Ok, Offset(px(demand[eqIdx]), 0f),
                            Offset(px(demand[eqIdx]), H), 1.1f)
                        drawCircle(Color.White, 3.5f,
                            Offset(px(demand[eqIdx]), py(prices[eqIdx])))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Text("蓝线需求 / 黄线供给", fontSize = 7.5.sp, color = ZxT.Text3,
                     modifier = Modifier.weight(1f))
                Text(if (eqIdx >= 0) "绿线 = 均衡量" else "**两线不相交**",
                    fontSize = 7.5.sp, color = if (eqIdx >= 0) ZxT.Ok else ZxT.Err)
            }
            Spacer(Modifier.height(4.dp))
            (0 until n).forEach { i ->
                val gap = supply[i] - demand[i]
                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (sel == i) ZxT.Surface2
                                else if (i == eqIdx) ZxT.Ok.copy(.10f) else Color.Transparent)
                    .clickable {
                        sel = i
                        ctx.callTool("zx_select", mapOf(
                            "price" to "%.0f".format(prices[i]),
                            "demand" to "%.0f".format(demand[i]),
                            "supply" to "%.0f".format(supply[i]),
                            "gap" to "%.0f".format(gap), "source" to "zx_bidcurve"))
                    }.padding(5.dp, 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("%.0f".format(prices[i]), fontSize = 9.sp, color = ZxT.Text,
                         modifier = Modifier.width(34.dp), fontFamily = ZxT.Mono)
                    Text("需 ${"%.0f".format(demand[i])}", fontSize = 8.sp, color = PAL[0],
                         modifier = Modifier.width(46.dp))
                    Text("供 ${"%.0f".format(supply[i])}", fontSize = 8.sp, color = PAL[2],
                         modifier = Modifier.width(46.dp))
                    Text(if (gap >= 0) "过剩 ${"%.0f".format(gap)}" else "短缺 ${"%.0f".format(-gap)}",
                        fontSize = 8.sp, color = if (gap >= 0) ZxT.Warn else ZxT.Err,
                        modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (eqIdx < 0) ZxT.Err.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (eqIdx < 0) "✗" else "✓", fontSize = 11.sp,
                     color = if (eqIdx < 0) ZxT.Err else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (eqIdx < 0) "**在任何价位供给都小于需求**——" +
                            "这个市场在当前条件下没有均衡价，不是「价格不够高」的问题"
                         else "均衡价 ${"%.0f".format(prices[eqIdx])}，" +
                            "成交 ${"%.0f".format(demand[eqIdx])} 单位",
                        fontSize = 9.sp, color = if (eqIdx < 0) ZxT.Err else ZxT.Ok)
                    Text("**均衡不是「价格合适」，是供需相等**。" +
                        "低于均衡价会短缺、高于会过剩，两种都不是稳态",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 breakeven 找<b>厂商的临界销量</b>、" +
                 "line 画<b>趋势</b>，" +
                 "这个找的是<b>市场的均衡价格</b>。" +
                 "区别是：临界销量是单方的（我卖多少才回本），" +
                 "均衡价是<b>双方互动的结果</b>——我定价高买家就不买。" +
                 "关键是<b>无交点要明说</b>——那是市场不成立，不是定价不对。" +
                 "<b>供需无交点会被拦</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_sequential 序贯决策 ══════════ */
    @Composable
    fun sequential(o: JSONObject, ctx: RenderContext) {
        val nowVal = o.optDouble("now", 0.0)      // 现在就决定的期望价值
        val infoCost = o.optDouble("cost", 0.0)   // 等待的成本
        val infoGain = o.optDouble("gain", 0.0)   // 等到信息后的价值增量
        val probGood = o.optDouble("p", 0.5)      // 信息有利的概率
        val waitVal = nowVal + probGood * infoGain - infoCost
        val worth = waitVal > nowVal
        val gap = abs(waitVal - nowVal)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "序贯决策"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (worth) "**值得等**" else "现在定", fontSize = 9.sp,
                     color = if (worth) ZxT.Warn else ZxT.Ok)
            }
            Spacer(Modifier.height(6.dp))
            // 决策树式的两分支
            Row(Modifier.fillMaxWidth().height(46.dp)) {
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("现在定", fontSize = 8.sp, color = ZxT.Text3)
                        Text("%.1f".format(nowVal), fontSize = 11.sp, color = PAL[0],
                             fontFamily = ZxT.Mono)
                    }
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(ZxT.Surface3))
                Box(Modifier.weight(1.6f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("等一等", fontSize = 8.sp, color = ZxT.Text3)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("好消息 ${"%.0f%%".format(probGood * 100)}",
                                    fontSize = 7.5.sp, color = ZxT.Ok)
                                Text("%.1f".format(nowVal + infoGain - infoCost),
                                    fontSize = 9.sp, color = ZxT.Ok, fontFamily = ZxT.Mono)
                            }
                            Text("　", fontSize = 8.sp)
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("坏消息 ${"%.0f%%".format((1 - probGood) * 100)}",
                                    fontSize = 7.5.sp, color = ZxT.Err)
                                Text("%.1f".format(nowVal - infoCost), fontSize = 9.sp,
                                     color = ZxT.Err, fontFamily = ZxT.Mono)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth()) {
                listOf("现在定" to "%.1f".format(nowVal), "信息成本" to "%.1f".format(infoCost),
                       "有利概率" to "%.0f%%".format(probGood * 100),
                       "等待期望" to "%.1f".format(waitVal)).forEach { (k, v) ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(v, fontSize = 9.5.sp,
                             color = if (k == "等待期望") (if (worth) ZxT.Ok else ZxT.Err)
                                     else ZxT.Text,
                             fontFamily = ZxT.Mono)
                        Text(k, fontSize = 7.5.sp, color = ZxT.Text3)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (worth) ZxT.Warn.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (worth) "!" else "✓", fontSize = 11.sp,
                     color = if (worth) ZxT.Warn else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (worth) "等待期望 ${"%.1f".format(waitVal)} > 现在 " +
                            "${"%.1f".format(nowVal)}（高 ${"%.1f".format(gap)}）——" +
                            "**信息的价值 " +
                            "${"%.1f".format(probGood * infoGain)} 超过了等待成本 " +
                            "${"%.1f".format(infoCost)}**"
                         else "等待期望 ${"%.1f".format(waitVal)} ≤ 现在 " +
                            "${"%.1f".format(nowVal)}——**信息成本 " +
                            "${"%.1f".format(infoCost)} 超过了期望收益 " +
                            "${"%.1f".format(probGood * infoGain)}，等是亏的**",
                        fontSize = 9.sp, color = if (worth) ZxT.Warn else ZxT.Ok)
                    Text("**信息的价值 = 它会改变你的决定的概率 × 改变带来的收益**。" +
                        "如果不管信息说什么你都做同一件事，那信息一文不值",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 decision 假设<b>必须现在选</b>、" +
                 "realoption 把<b>等待本身当选项</b>，" +
                 "这个进一步算<b>等待能不能真的拿到信息</b>。" +
                 "区别是：realoption 里的等待价值来自不确定性，" +
                 "这里的价值来自<b>信息会揭示什么</b>——是有代价的主动获取。" +
                 "关键是<b>信息成本要和价值对比</b>，不是「有信息总比没有好」。" +
                 "<b>信息成本 > 价值会被拦</b>（不该等）。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_stoprule 最优停止 ══════════ */
    @Composable
    fun stoprule(o: JSONObject, ctx: RenderContext) {
        val n = o.optInt("n", 20)
        var seen by remember { mutableIntStateOf(o.optInt("at", 0)) }
        var bestSoFar by remember { mutableDoubleStateOf(o.optDouble("best", 0.0)) }
        // 37% 规则
        val cutoff = (n / kotlin.math.E).toInt()
        val inPhase = seen < cutoff
        val explore = o.optDouble("explore", 0.0)
        val mx = maxOf(bestSoFar, explore, 1.0)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "最优停止"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("第 $seen / $n", fontSize = 9.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(26.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height
                    // 观察期（前 37%）
                    drawRect(ZxT.Text3.copy(alpha = .30f), Offset(0f, 0f),
                        Size(W * cutoff.toFloat() / n, H))
                    // 决策期
                    drawRect(PAL[0].copy(alpha = .18f), Offset(W * cutoff.toFloat() / n, 0f),
                        Size(W * (1 - cutoff.toFloat() / n), H))
                    // 当前位置
                    val x = W * seen.toFloat() / n
                    drawLine(Color.White, Offset(x, 0f), Offset(x, H), 1.6f)
                    // 当前最优
                    val y = H - (bestSoFar / mx * H).toFloat()
                    drawLine(ZxT.Ok, Offset(0f, y), Offset(W * seen.toFloat() / n, y), 1.2f)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Text("灰区观察（不选）/ 蓝区可出手", fontSize = 7.5.sp, color = ZxT.Text3,
                     modifier = Modifier.weight(1f))
                Text("分界第 $cutoff 个", fontSize = 7.5.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("已看", fontSize = 8.5.sp, color = ZxT.Text3,
                     modifier = Modifier.width(34.dp))
                SliderBar(value = seen.toDouble(), min = 0.0, max = n.toDouble()) {
                    seen = it.toInt().coerceIn(0, n)
                    // 简单模拟：观察期内更新最优，观察期后不动
                    if (seen < cutoff) bestSoFar = max(bestSoFar, explore * seen / n)
                }
                Text(seen.toString(), fontSize = 9.sp, color = ZxT.Text,
                     modifier = Modifier.width(24.dp), textAlign = TextAlign.End,
                     fontFamily = ZxT.Mono)
            }
            Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                listOf("总数" to n.toString(), "观察期" to "$cutoff（${"%.0f%%".format(37.0)}）",
                       "当前最优" to "%.1f".format(bestSoFar),
                       "本轮" to "%.1f".format(explore)).forEach { (k, v) ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(v, fontSize = 9.5.sp, color = ZxT.Text, fontFamily = ZxT.Mono)
                        Text(k, fontSize = 7.5.sp, color = ZxT.Text3)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (inPhase) ZxT.Warn.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (inPhase) "!" else "✓", fontSize = 11.sp,
                     color = if (inPhase) ZxT.Warn else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (inPhase) "**还在观察期**——前 $cutoff 个只看不选，" +
                            "用来标定「什么叫好」。现在出手没有参照系"
                         else "已进入决策期——**遇到比 ${"%.1f".format(bestSoFar)} " +
                            "更好的就立刻出手**，不要想着「后面可能还有更好的」",
                        fontSize = 9.sp, color = if (inPhase) ZxT.Warn else ZxT.Ok)
                    Text("**37% 规则**：看前 1/e 建立标准，之后遇到超过标准的就选。" +
                        "这是能拿到最优解的概率上限（约 37%）",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 markov 算<b>状态转移与稳态</b>、" +
                 "sequential 算<b>要不要等信息</b>，" +
                 "这个处理的是<b>候选逐个出现、过了不能回头</b>。" +
                 "区别是：sequential 里决策可以一直推迟，" +
                 "这里<b>每看一个就永久失去前一个</b>。" +
                 "关键是<b>要给出观察期和决策期的分界</b>——" +
                 "只说「看好了就出手」等于没说。" +
                 "<b>n &lt; 3 会被拦</b>（37% 规则不成立）。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_hedge 对冲 ══════════ */
    @Composable
    fun hedge(o: JSONObject, ctx: RenderContext) {
        val exposure = o.optDouble("exposure", 0.0)  // 敞口
        val prob = o.optDouble("p", 0.2)             // 不利发生的概率
        val loss = o.optDouble("loss", 0.0)          // 发生时的损失
        val cost = o.optDouble("cost", 0.0)          // 对冲成本
        val ratioRaw = o.optDouble("ratio", 1.0)     // 对冲比例
        var ratio by remember { mutableFloatStateOf(ratioRaw.toFloat()) }
        val expectedLoss = prob * loss
        val hedgedLoss = expectedLoss * (1 - ratio)
        val net = expectedLoss - hedgedLoss - cost * ratio
        val worth = net > 0
        val overHedge = cost * ratio > expectedLoss

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "对冲"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (overHedge) "**对冲过度**" else if (worth) "值得" else "不划算",
                    fontSize = 9.sp, color = if (overHedge || !worth) ZxT.Err else ZxT.Ok)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(34.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height
                    // 敞口
                    drawRect(ZxT.Err.copy(alpha = .30f), Offset(0f, H * .18f),
                        Size(W * ratio.toFloat(), H * .64f))
                    // 已对冲部分
                    drawRect(ZxT.Ok.copy(alpha = .35f), Offset(W * ratio.toFloat(), H * .18f),
                        Size(W * (1 - ratio.toFloat()), H * .64f))
                    drawLine(Color.White, Offset(W * ratio.toFloat(), 0f),
                        Offset(W * ratio.toFloat(), H), 1.4f)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Text("红 = 仍暴露 / 绿 = 已对冲", fontSize = 7.5.sp, color = ZxT.Text3,
                     modifier = Modifier.weight(1f))
                Text("比例 ${"%.0f%%".format(ratio * 100)}", fontSize = 7.5.sp, color = ZxT.Text)
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("比例", fontSize = 8.5.sp, color = ZxT.Text3,
                     modifier = Modifier.width(34.dp))
                SliderBar(value = ratio.toDouble(), min = 0.0, max = 1.0) {
                    ratio = it.toFloat().coerceIn(0f, 1f) }
                Text("%.0f%%".format(ratio * 100), fontSize = 9.sp, color = ZxT.Text,
                     modifier = Modifier.width(34.dp), textAlign = TextAlign.End,
                     fontFamily = ZxT.Mono)
            }
            Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                listOf("敞口" to "%.0f".format(exposure), "期望损失" to "%.1f".format(expectedLoss),
                       "对冲成本" to "%.1f".format(cost * ratio),
                       "净收益" to "%.1f".format(net)).forEach { (k, v) ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(v, fontSize = 9.5.sp,
                             color = if (k == "净收益") (if (net > 0) ZxT.Ok else ZxT.Err)
                                     else ZxT.Text,
                             fontFamily = ZxT.Mono)
                        Text(k, fontSize = 7.5.sp, color = ZxT.Text3)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (overHedge) ZxT.Err.copy(.10f)
                            else if (worth) ZxT.Ok.copy(.10f) else ZxT.Warn.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (overHedge) "✗" else if (worth) "✓" else "!", fontSize = 11.sp,
                     color = if (overHedge) ZxT.Err else if (worth) ZxT.Ok else ZxT.Warn)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (overHedge) "**对冲成本 ${"%.1f".format(cost * ratio)} ≥ 期望损失 " +
                            "${"%.1f".format(expectedLoss)}**——**对冲比不对冲还亏**。" +
                            "降低比例或干脆不对冲"
                         else if (worth) "净收益 ${"%.1f".format(net)}——值得对冲"
                         else "净收益 ${"%.1f".format(net)}，当前比例下不划算",
                        fontSize = 9.sp,
                        color = if (overHedge) ZxT.Err else if (worth) ZxT.Ok else ZxT.Warn)
                    Text("**对冲不是消除风险，是把风险换成你能承受的形态**——" +
                        "代价是确定的成本换不确定的损失。成本超过期望损失就是亏的",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 montecarlo 给<b>损失分布</b>、" +
                 "realoption 把<b>等待当选项</b>，" +
                 "这个处理的是<b>主动花钱降低不确定性</b>。" +
                 "区别是：等待是免费的（只损失时间），对冲是<b>要付确定成本</b>的。" +
                 "关键是<b>成本必须和期望损失比</b>——" +
                 "「买个安心」不是理由，那是有价格的。" +
                 "<b>成本 ≥ 期望损失会被拦</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_contingent 应急计划 ══════════ */
    @Composable
    fun contingent(o: JSONObject, ctx: RenderContext) {
        val plans = jarr(o, "plans").map {
            it.optString("if", "") to (it.optString("then", "") to it.optDouble("p", 0.0))
        }
        var sel by remember { mutableIntStateOf(-1) }
        val totalP = plans.sumOf { it.second.second }
        val covered = plans.count { it.first.isNotBlank() && it.second.first.isNotBlank() }
        val uncovered = 1.0 - totalP

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "应急计划"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("覆盖 ${"%.0f%%".format(min(totalP, 1.0) * 100)}", fontSize = 9.sp,
                     color = if (totalP >= 0.85) ZxT.Ok else ZxT.Warn)
            }
            Spacer(Modifier.height(6.dp))
            plans.forEachIndexed { k, (cond, act) ->
                val p = act.second
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (sel == k) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_select", mapOf(
                            "if" to cond, "then" to act.first,
                            "prob" to "%.2f".format(p), "source" to "zx_contingent"))
                    }.padding(5.dp, 3.dp)) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("若", fontSize = 8.sp, color = ZxT.Text3)
                            Text(cond, fontSize = 9.sp, color = PAL[0],
                                 modifier = Modifier.weight(1f).padding(start = 3.dp),
                                 maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("%.0f%%".format(p * 100), fontSize = 8.sp, color = ZxT.Text,
                                 fontFamily = ZxT.Mono)
                        }
                        Row(Modifier.padding(top = 1.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("则", fontSize = 8.sp, color = ZxT.Text3)
                            Text(act.first, fontSize = 9.sp, color = ZxT.Text2,
                                 modifier = Modifier.weight(1f).padding(start = 3.dp),
                                 maxLines = 2, overflow = TextOverflow.Ellipsis,
                                 lineHeight = 12.sp)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp).height(12.dp)) {
                plans.forEach { (_, a) ->
                    Box(Modifier.weight(a.second.toFloat().coerceIn(0.01f, 1f))
                        .fillMaxHeight().background(PAL[plans.indexOfFirst { it.second.first ==
                            a.first } % PAL.size]))
                }
                if (uncovered > 0.001)
                    Box(Modifier.weight(uncovered.toFloat()).fillMaxHeight()
                        .background(ZxT.Err.copy(alpha = .45f)))
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Text("色条 = 各情景概率", fontSize = 7.5.sp, color = ZxT.Text3,
                     modifier = Modifier.weight(1f))
                Text(if (uncovered > 0.001) "**红 = 未覆盖 ${"%.0f%%".format(uncovered * 100)}**"
                     else "已全覆盖", fontSize = 7.5.sp,
                     color = if (uncovered > 0.001) ZxT.Err else ZxT.Ok)
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (uncovered > 0.15) ZxT.Err.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (uncovered > 0.15) "✗" else "✓", fontSize = 11.sp,
                     color = if (uncovered > 0.15) ZxT.Err else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (uncovered > 0.15) "**还有 ${"%.0f%%".format(uncovered * 100)} " +
                            "的可能没有任何预案**——那部分正是最危险的，" +
                            "事到临头要临时想办法"
                         else "覆盖 ${"%.0f%%".format(min(totalP, 1.0) * 100)}，比较完整",
                        fontSize = 9.sp,
                        color = if (uncovered > 0.15) ZxT.Err else ZxT.Ok)
                    Text("应急计划的价值不在「有」，在<b>提前想好</b>——" +
                        "事发时临时决策的质量会急剧下降",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 runbook 给<b>处置步骤</b>、" +
                 "scenario 并列<b>几个情景</b>，" +
                 "这个把两者绑起来：<b>情景 → 动作</b>。" +
                 "区别是：scenario 只说「可能会这样」，这个还要说「那我就这样做」。" +
                 "关键是<b>要标出未覆盖的概率</b>——那是预案的空缺，不是「剩下的都不重要」。" +
                 "<b>条件重叠会被拦</b>；<b>概率和 >1 会被拦</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_escalate 升级决策 ══════════ */
    @Composable
    fun escalate(o: JSONObject, ctx: RenderContext) {
        val loss = o.optDouble("loss", 0.0)        // 不升级时每小时损失
        val escalateCost = o.optDouble("cost", 0.0) // 升级的成本（打扰/面子/流程）
        val pSelf = o.optDouble("p", 0.5)          // 自己能解决的概率
        var elapsed by remember { mutableFloatStateOf(o.optDouble("at", 0.0).toFloat()) }
        val horizon = o.optDouble("horizon", 8.0)
        // 自己继续的期望损失 = 已花时间×损失 + 剩余失败概率×剩余×损失
        val selfCost = elapsed * loss + (1 - pSelf) * (horizon - elapsed) * loss
        val escCost = elapsed * loss + escalateCost + (horizon - elapsed) * loss * 0.1
        val shouldEsc = escCost < selfCost
        val gap = abs(selfCost - escCost)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "升级决策"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (shouldEsc) "**该叫人了**" else "还能自己扛", fontSize = 9.sp,
                     color = if (shouldEsc) ZxT.Err else ZxT.Ok)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(48.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height
                    val mx = maxOf(selfCost, escCost, 1.0)
                    fun py(v: Double) = H - (v / mx * (H - 5f)).toFloat()
                    fun px(t: Double) = (t / horizon * W).toFloat()
                    // 自己扛：随时间线性上升
                    drawLine(ZxT.Err, Offset(0f, py(0.0)), Offset(W, py(horizon * loss)), 1.6f)
                    // 升级：有台阶
                    drawLine(ZxT.Ok, Offset(0f, py(escalateCost)), Offset(W, py(escalateCost)), 1.4f)
                    // 当前
                    drawLine(Color.White, Offset(px(elapsed.toDouble()), 0f),
                        Offset(px(elapsed.toDouble()), H), 1.3f)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Text("红线自己扛 / 绿线升级（有固定成本）", fontSize = 7.5.sp,
                     color = ZxT.Text3, modifier = Modifier.weight(1f))
                Text("已过 ${"%.1f".format(elapsed)}h", fontSize = 7.5.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("已耗时", fontSize = 8.5.sp, color = ZxT.Text3,
                     modifier = Modifier.width(34.dp))
                SliderBar(value = elapsed.toDouble(), min = 0.0, max = horizon) {
                    elapsed = it.toFloat().coerceIn(0f, horizon.toFloat()) }
                Text("%.1f".format(elapsed), fontSize = 9.sp, color = ZxT.Text,
                     modifier = Modifier.width(30.dp), textAlign = TextAlign.End,
                     fontFamily = ZxT.Mono)
            }
            Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                listOf("继续自扛" to "%.0f".format(selfCost), "现在升级" to "%.0f".format(escCost),
                       "差额" to "%.0f".format(gap),
                       "自解概率" to "%.0f%%".format(pSelf * 100)).forEach { (k, v) ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(v, fontSize = 9.5.sp, color = ZxT.Text, fontFamily = ZxT.Mono)
                        Text(k, fontSize = 7.5.sp, color = ZxT.Text3)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (escalateCost > horizon * loss) ZxT.Warn.copy(.10f)
                            else if (shouldEsc) ZxT.Err.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (escalateCost > horizon * loss) "!" else if (shouldEsc) "✗" else "✓",
                     fontSize = 11.sp,
                     color = if (escalateCost > horizon * loss) ZxT.Warn
                             else if (shouldEsc) ZxT.Err else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (escalateCost > horizon * loss) "**升级成本 ${"%.0f".format(escalateCost)} " +
                            "超过问题本身全部损失 ${"%.0f".format(horizon * loss)}**——" +
                            "**这种事永远不该升级**，忍过去更划算"
                         else if (shouldEsc) "**该叫人了**：继续自扛 ${"%.0f".format(selfCost)} > " +
                            "升级 ${"%.0f".format(escCost)}（差 ${"%.0f".format(gap)}）"
                         else "还能自己扛：自扛 ${"%.0f".format(selfCost)} < 升级 " +
                            "${"%.0f".format(escCost)}",
                        fontSize = 9.sp,
                        color = if (escalateCost > horizon * loss) ZxT.Warn
                                else if (shouldEsc) ZxT.Err else ZxT.Ok)
                    Text("**升级有固定成本**（打扰别人、面子、流程）——" +
                        "正因为有成本，才需要一个明确的阈值而不是「实在不行再说」",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 incident 记录<b>事故时间线</b>、" +
                 "runbook 给<b>处置步骤</b>，" +
                 "这个回答的是<b>什么时候该叫人</b>。" +
                 "区别是：runbook 假设已经在处置了，" +
                 "这个问的是<b>该不该由你来处置</b>。" +
                 "关键是<b>升级成本要和问题损失比</b>——" +
                 "成本超过损失的事不该升级。" +
                 "<b>升级成本 > 全部损失会被拦</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_commit 承诺可信度 ══════════ */
    @Composable
    fun commit(o: JSONObject, ctx: RenderContext) {
        val claim = o.optString("claim", "我不会让步")
        val benefit = o.optDouble("benefit", 0.0)  // 对方让步给我带来的收益
        val cost = o.optDouble("cost", 0.0)        // 我坚持承诺要付的代价
        val hisCost = o.optDouble("hiscost", 0.0)  // 对方不让步的代价
        // 承诺可信 ⟺ 违约对我的损失 > 坚持的代价
        val credible = cost > 0 && cost >= benefit * 0.5
        val fullyCredible = cost >= benefit
        var sel by remember { mutableIntStateOf(-1) }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "承诺可信度"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (fullyCredible) "完全可信" else if (credible) "部分可信" else "**不可信**",
                    fontSize = 9.sp,
                    color = if (fullyCredible) ZxT.Ok else if (credible) ZxT.Warn else ZxT.Err)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().padding(6.dp, 5.dp)
                .clip(RoundedCornerShape(6.dp)).background(ZxT.Surface3)) {
                Text("「$claim」", fontSize = 10.sp, color = ZxT.Text, lineHeight = 14.sp)
            }
            Spacer(Modifier.height(4.dp))
            listOf(
                "坚持承诺的代价" to cost to PAL[0],
                "违约省下的" to benefit to ZxT.Err,
                "对方不让步的代价" to hisCost to PAL[4]
            ).forEach { e ->
                val nm = e.first.first; val v = e.first.second; val c = e.second
                val mx = maxOf(cost, benefit, hisCost, 1.0)
                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(nm, fontSize = 8.5.sp, color = ZxT.Text3,
                         modifier = Modifier.width(88.dp))
                    Box(Modifier.weight(1f).height(8.dp)
                        .clip(RoundedCornerShape(3.dp)).background(ZxT.Surface2)) {
                        Box(Modifier.fillMaxWidth((v / mx).toFloat().coerceIn(0.02f, 1f))
                            .fillMaxHeight().background(c))
                    }
                    Text("%.0f".format(v), fontSize = 8.5.sp, color = ZxT.Text,
                         modifier = Modifier.width(28.dp), textAlign = TextAlign.End,
                         fontFamily = ZxT.Mono)
                }
            }
            Spacer(Modifier.height(3.dp))
            Row(Modifier.fillMaxWidth()) {
                listOf("代价/收益" to (if (benefit <= 0) "—" else "%.2f".format(cost / benefit)),
                       "可信度" to (if (benefit <= 0) "—"
                                   else "%.0f%%".format(min(cost / benefit, 1.0) * 100)))
                    .forEach { (k, v) ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(v, fontSize = 9.5.sp,
                             color = if (k == "可信度" && !fullyCredible) ZxT.Err else ZxT.Text,
                             fontFamily = ZxT.Mono)
                        Text(k, fontSize = 7.5.sp, color = ZxT.Text3)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (fullyCredible) ZxT.Ok.copy(.10f)
                            else if (credible) ZxT.Warn.copy(.10f) else ZxT.Err.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (fullyCredible) "✓" else if (credible) "!" else "✗", fontSize = 11.sp,
                     color = if (fullyCredible) ZxT.Ok else if (credible) ZxT.Warn else ZxT.Err)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (fullyCredible) "**代价 ${"%.0f".format(cost)} ≥ 违约省下的 " +
                            "${"%.0f".format(benefit)}**——违约不划算，所以承诺可信"
                         else if (credible) "代价 ${"%.0f".format(cost)} 只是收益的一半左右，" +
                            "**对方有理由赌你会违约**"
                         else "**代价 ${"%.0f".format(cost)} 远小于违约省下的 " +
                            "${"%.0f".format(benefit)}**——" +
                            "**纯口头承诺，对方不会信**",
                        fontSize = 9.sp,
                        color = if (fullyCredible) ZxT.Ok else if (credible) ZxT.Warn else ZxT.Err)
                    Text("**承诺要可信必须有代价**。烧掉退路、公开表态、付定金——" +
                        "都是在人为制造违约成本。**免费的承诺等于没说**",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 payoff 算<b>均衡</b>、bargain 看<b>ZOPA</b>，" +
                 "这个处理的是<b>怎么让对方相信你</b>。" +
                 "区别是：payoff 假设双方都知道收益矩阵，" +
                 "这里<b>你的话本身可以是假的</b>。" +
                 "关键是<b>代价要 ≥ 违约的收益</b>——" +
                 "低于这个线，承诺只是噪音。" +
                 "<b>零代价会被拦</b>（不可信）。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }
}
