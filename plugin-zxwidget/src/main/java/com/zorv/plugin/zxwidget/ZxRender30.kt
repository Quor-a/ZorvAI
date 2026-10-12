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
import kotlin.math.roundToInt

/**
 * 第三十批组件（12 个）—— 组织与治理。
 *
 * 前二十九批一路走的是**个人做判断**这条线：
 *
 *   看什么 → 填什么 → 改什么 → 凭什么 → 是不是因果 →
 *   在多大范围内成立 → 接下来该做什么 → 做完了怎么样 →
 *   能不能下次还用上
 *
 * 但真实工作里，**很多决定不是一个人做的，也不是为自己做的**：
 *
 *   这个客户值不值得继续投？     → 只有总营收，没有单客经济
 *   这笔钱花在哪了？             → 只有总额，拆不到维度
 *   实验做完，然后呢？           → 只有显著/不显著，没有"所以要不要上"
 *   改这张表会影响谁？           → 只有血缘图，没有"影响面有多大"
 *   谁在超负荷？                → 只有任务数，没有"卡在哪一环"
 *   这个数据能给外部吗？         → 只有字段名，没有敏感级别
 *   谁有权看这个？多久没审了？   → 只有权限列表，没有"过期了没有"
 *
 * 十二个组件分成四组：
 *
 *   **经济**（钱花在哪、值不值）
 *     zx_unitecon    单客经济：CAC / LTV / 回本月数 / 比值
 *     zx_costattrib  成本归因：按维度拆，且**必须能加起来等于总额**
 *
 *   **数据治理**（改了会怎样、能不能给）
 *     zx_lineage     数据血缘，**标出影响面与深度**
 *     zx_impact      影响分析：改这个字段，波及哪些报表
 *     zx_dataclass   数据分级：每列标敏感级别
 *     zx_retention   保留策略：到期该删的标出来
 *
 *   **组织**（谁来干、卡在哪）
 *     zx_workload    团队负载：**区分"忙"和"堵"**
 *     zx_bottleneck  瓶颈识别：排队论看哪一环是约束
 *     zx_accessreview 权限评审：**过期未审的标红**
 *
 *   **实验与合规**
 *     zx_expresult   实验结果：**显著 ≠ 该上**，要给实际影响
 *     zx_compliance  合规检查：每条要能落到具体项
 *     zx_audittrail  审计追踪：谁在什么时候改了什么
 *
 * 贯穿这批的一条：**要能落到"谁该做什么"**。
 *
 *   单客经济只给 LTV/CAC 比值，不给"回本要几个月"  → 不知道能不能承受
 *   成本归因拆完加不回总额                          → 那这个拆分是编的
 *   血缘图只画箭头不给深度                          → 不知道影响有多大
 *   负载只给任务数不给"堵在哪"                      → 加人也解决不了
 *   权限列表不标"多久没审"                          → 离职的人可能还开着
 *   实验只说"显著"不给实际提升                      → 显著但只涨 0.1%，不值得上
 *
 *   **只有数字、落不到动作的治理，等于没治理。**
 *
 * 校验因此换了第八档。前七档拦的是
 *   "画不出来" → "统计前提不成立" → "区间前提不成立" →
 *   "因果前提无从判断" → "看起来确定了其实没那么确定" →
 *   "状态摆出来了但推不出该做什么" → "记下来了但下次用不上"，
 * 这批拦的是
 *   **"数字都在，但落不到谁该做什么"**：
 *
 *   unitecon     CAC 非正（除零）/ churn 不在 0-1（LTV 算错）
 *   costattrib   分项之和 ≠ 总额（拆分是编的）
 *   lineage      有环（血缘无限） / 边指向不存在的节点
 *   impact       影响项缺目标（说不清波及了谁）
 *   dataclass    列无级别（等于没分级）
 *   retention    保留天数非正（到期判断失效）
 *   workload     capacity 非正（负载率除零）
 *   bottleneck   吞吐非正（约束无从判断）
 *   expresult    给了 p 却没给实际提升（显著 ≠ 该上）
 *   compliance   不合格项缺整改人（没人改就永远不合格）
 *   audittrail   条目缺操作者（追不到人）
 *   accessreview 全都没审过时间（判断不了过期）
 */
object ZxRender30 {

    private val PAL = listOf(
        Color(0xFF5046E4), Color(0xFF3FBF7F), Color(0xFFE8A33D),
        Color(0xFFE5544B), Color(0xFF4A9BE8), Color(0xFF8E86FF)
    )
    private fun sc(s: String) = when (s) {
        "ok", "pass", "low", "public" -> ZxT.Ok
        "warn", "med", "internal" -> ZxT.Warn
        "err", "fail", "high", "crit", "secret" -> ZxT.Err
        else -> ZxT.Text3
    }
    private fun scC(s: String) = when (s) {
        "ok", "pass", "low", "public" -> ZxT.Ok
        "warn", "med", "internal" -> ZxT.Warn
        "err", "fail", "high", "crit", "secret" -> ZxT.Err
        else -> PAL[4]
    }

    /* ══════════ zx_unitecon 单客经济 ══════════ */
    @Composable
    fun unitecon(o: JSONObject, ctx: RenderContext) {
        val cac = o.optDouble("cac", Double.NaN)
        val arpu = o.optDouble("arpu", Double.NaN)
        val churn = o.optDouble("churn", Double.NaN)
        val ltv = if (!churn.isNaN() && churn > 0 && !arpu.isNaN()) arpu / churn else Double.NaN
        val ratio = if (!ltv.isNaN() && cac > 0) ltv / cac else Double.NaN
        val payback = if (!cac.isNaN() && cac > 0 && !arpu.isNaN()) cac / arpu else Double.NaN
        val horizon = o.optInt("horizon", 24)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "单客经济"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(when { ratio.isNaN() -> "—"; ratio >= 3 -> "健康"; ratio >= 1 -> "勉强"
                            else -> "亏本" },
                    fontSize = 9.sp,
                    color = when { ratio.isNaN() -> ZxT.Text3; ratio >= 3 -> ZxT.Ok
                                   ratio >= 1 -> ZxT.Warn; else -> ZxT.Err })
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth()) {
                listOf("CAC" to (if (cac.isNaN()) "—" else "%.0f".format(cac)),
                       "月 ARPU" to (if (arpu.isNaN()) "—" else "%.0f".format(arpu)),
                       "月流失" to (if (churn.isNaN()) "—" else "%.1f%%".format(churn * 100)),
                       "LTV" to (if (ltv.isNaN()) "—" else "%.0f".format(ltv))
                ).forEach { (k, v) ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(v, fontSize = 10.sp, color = ZxT.Text, fontFamily = ZxT.Mono)
                        Text(k, fontSize = 7.5.sp, color = ZxT.Text3)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            // 累计曲线：前几个月在补 CAC
            if (!cac.isNaN() && !arpu.isNaN()) {
                val mx = (arpu * horizon).coerceAtLeast(cac)
                Box(Modifier.fillMaxWidth().height(56.dp)) {
                    Canvas(Modifier.fillMaxSize()) {
                        val W = size.width; val H = size.height
                        drawPath(Path().apply {
                            for (m in 0..horizon) {
                                val v = arpu * m
                                val x = m.toFloat() / horizon * W
                                val y = H - (v / mx * (H - 5f)).toFloat()
                                if (m == 0) moveTo(x, y) else lineTo(x, y)
                            }
                        }, PAL[0], style = Stroke(1.8f))
                        // CAC 水平线
                        val yc = H - (cac / mx * (H - 5f)).toFloat()
                        drawLine(ZxT.Err, Offset(0f, yc), Offset(W, yc), 1.1f)
                        // 回本点
                        if (!payback.isNaN() && payback <= horizon) {
                            val x = (payback / horizon * W).toFloat()
                            drawLine(ZxT.Warn, Offset(x, 0f), Offset(x, H), 1.2f)
                            drawCircle(Color.White, 3.5f, Offset(x, yc))
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth()) {
                Text("累计收入 vs CAC（红线）", fontSize = 7.5.sp, color = ZxT.Text3,
                     modifier = Modifier.weight(1f))
                Text("黄线 = 回本", fontSize = 7.5.sp, color = ZxT.Text3)
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                listOf("回本" to (if (payback.isNaN()) "—"
                                 else if (payback > horizon) ">${horizon}月" else "%.1f月".format(payback)),
                       "LTV/CAC" to (if (ratio.isNaN()) "—" else "%.2f".format(ratio)),
                       horizon.toString() + "月累计" to (if (arpu.isNaN()) "—" else "%.0f".format(arpu * horizon))
                ).forEach { (k, v) ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(v, fontSize = 9.5.sp, color = ZxT.Text, fontFamily = ZxT.Mono)
                        Text(k, fontSize = 7.5.sp, color = ZxT.Text3)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(when { ratio.isNaN() -> ZxT.Surface3; ratio >= 3 -> ZxT.Ok.copy(.10f)
                                   ratio >= 1 -> ZxT.Warn.copy(.10f); else -> ZxT.Err.copy(.10f) })
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(when { ratio.isNaN() -> "·"; ratio >= 3 -> "✓"; ratio >= 1 -> "!" else -> "✗" },
                     fontSize = 11.sp,
                     color = when { ratio.isNaN() -> ZxT.Text3; ratio >= 3 -> ZxT.Ok
                                    ratio >= 1 -> ZxT.Warn; else -> ZxT.Err })
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (ratio.isNaN()) "数据不足，算不出 LTV/CAC"
                         else if (ratio < 1) "**LTV < CAC**：每获取一个客户都在亏钱，" +
                            "规模越大亏得越多——先别扩量，先降 CAC 或提留存"
                         else if (ratio < 3) "LTV/CAC=${"%.2f".format(ratio)}，勉强够本，" +
                            "回本要 ${"%.1f".format(payback)} 个月——**现金流能不能扛住这个周期**"
                         else "LTV/CAC=${"%.2f".format(ratio)}，健康；回本 ${"%.1f".format(payback)} 个月",
                        fontSize = 9.sp,
                        color = when { ratio.isNaN() -> ZxT.Text2; ratio >= 3 -> ZxT.Ok
                                       ratio >= 1 -> ZxT.Warn; else -> ZxT.Err })
                    Text("只给 LTV/CAC 比值不够——**回本要几个月**决定了现金流能不能扛住，" +
                        "很多生意是「长期赚钱、短期死在现金流上」",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 kpi 给<b>若干指标</b>、budget 给<b>分档条</b>，" +
                 "这个算的是<b>一个客户值不值得获取</b>。" +
                 "关键是<b>回本周期要算出来</b>：只说「LTV/CAC=4.2 很健康」，" +
                 "如果回本要 30 个月而现金流只够撑 12 个月，照样会死。" +
                 "<b>CAC 非正会被拦</b>（除零）；<b>churn 不在 0-1 会被拦</b>（LTV 算错）。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_costattrib 成本归因 ══════════ */
    @Composable
    fun costattrib(o: JSONObject, ctx: RenderContext) {
        val total = o.optDouble("total", Double.NaN)
        val itemsA = o.optJSONArray("items") ?: return
        val n = minOf(itemsA.length(), 8)
        val items = (0 until n).map { i ->
            val it = itemsA.optJSONObject(i) ?: JSONObject()
            Triple(it.optString("n", "项${i + 1}"), it.optDouble("v", 0.0),
                   it.optDouble("d", 0.0))
        }
        var sel by remember { mutableIntStateOf(-1) }
        val sum = items.sumOf { it.second }
        val gap = if (total.isNaN()) 0.0 else total - sum
        val mx = items.maxOf { it.second }.coerceAtLeast(1.0)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "成本归因"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (total.isNaN()) "无总额" else if (abs(gap) < 0.01) "已对齐" else "差 ${"%.1f".format(gap)}",
                    fontSize = 9.sp, color = if (total.isNaN() || abs(gap) < 0.01) ZxT.Ok else ZxT.Err)
            }
            Spacer(Modifier.height(6.dp))
            items.forEachIndexed { k, (nm, v, d) ->
                val c = PAL[k % PAL.size]
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (sel == k) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_select", mapOf(
                            "item" to nm, "value" to "%.1f".format(v),
                            "delta" to "%.1f".format(d), "source" to "zx_costattrib"))
                    }.padding(5.dp, 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(nm, fontSize = 9.sp, color = ZxT.Text2,
                         modifier = Modifier.width(58.dp), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    Box(Modifier.weight(1f).height(9.dp)
                        .clip(RoundedCornerShape(4.dp)).background(ZxT.Surface2)) {
                        Box(Modifier.fillMaxWidth((v / mx).toFloat().coerceIn(0.02f, 1f))
                            .fillMaxHeight().background(c))
                    }
                    Text("%.0f".format(v), fontSize = 8.5.sp, color = ZxT.Text,
                         modifier = Modifier.width(34.dp), textAlign = TextAlign.End,
                         fontFamily = ZxT.Mono)
                    Text(if (d > 0) "+${"%.0f".format(d)}" else "%.0f".format(d),
                        fontSize = 8.sp,
                        color = if (d > 0) ZxT.Err else if (d < 0) ZxT.Ok else ZxT.Text3,
                        modifier = Modifier.width(30.dp), textAlign = TextAlign.End)
                }
            }
            if (!total.isNaN()) {
                Row(Modifier.fillMaxWidth().padding(top = 3.dp)
                    .clip(RoundedCornerShape(4.dp)).background(ZxT.Surface2)
                    .padding(5.dp, 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("合计", fontSize = 9.sp, color = ZxT.Text,
                         modifier = Modifier.weight(1f))
                    Text("%.1f".format(sum), fontSize = 8.5.sp, color = ZxT.Text,
                         fontFamily = ZxT.Mono)
                    Spacer(Modifier.width(6.dp))
                    Text("总额 %.1f".format(total), fontSize = 8.sp, color = ZxT.Text3)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (total.isNaN()) ZxT.Surface3
                            else if (abs(gap) < 0.01) ZxT.Ok.copy(.10f) else ZxT.Err.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (total.isNaN()) "·" else if (abs(gap) < 0.01) "✓" else "✗",
                     fontSize = 11.sp,
                     color = if (total.isNaN()) ZxT.Text3
                             else if (abs(gap) < 0.01) ZxT.Ok else ZxT.Err)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (total.isNaN()) "没给总额，**无法验证拆分是不是完整**"
                         else if (abs(gap) < 0.01) "分项之和 = 总额 ${"%.1f".format(total)}，" +
                            "拆分是完整的"
                         else "**分项之和 ${"%.1f".format(sum)} ≠ 总额 ${"%.1f".format(total)}**，" +
                            "差 ${"%.1f".format(gap)}——这个拆分漏了东西，按它做决策会错",
                        fontSize = 9.sp,
                        color = if (total.isNaN()) ZxT.Text2
                                else if (abs(gap) < 0.01) ZxT.Ok else ZxT.Err)
                    Text("成本归因最常见的问题是**拆完加不回总额**——" +
                        "漏掉的那部分往往正是最该看的那部分",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 bar 画<b>一组数</b>、waterfall 看<b>从 A 到 B</b>，" +
                 "这个做的是<b>按维度拆、且必须能对上总额</b>。" +
                 "关键是<b>要验证加得回来</b>：拆出五个维度加起来只有总额的 80%，" +
                 "那这个拆分是编的。<b>分项之和 ≠ 总额会被拦</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_lineage 数据血缘 ══════════ */
    @Composable
    fun lineage(o: JSONObject, ctx: RenderContext) {
        val nodesA = o.optJSONArray("nodes") ?: return
        val n = minOf(nodesA.length(), 10)
        val nodes = (0 until n).map {
            val nd = nodesA.optJSONObject(it) ?: JSONObject()
            nd.optString("n", "表${it + 1}") to nd.optString("t", "table")
        }
        val edgesA = o.optJSONArray("edges") ?: return
        val edges = (0 until minOf(edgesA.length(), 16)).map {
            val e = edgesA.optJSONObject(it) ?: JSONObject()
            e.optInt("f", 0) to e.optInt("t", 0)
        }
        var sel by remember { mutableIntStateOf(-1) }
        // BFS 计算深度：从根节点（无入边）出发
        val indeg = IntArray(n)
        edges.forEach { (_, t) -> if (t in 0 until n) indeg[t]++ }
        val roots = (0 until n).filter { indeg[it] == 0 }
        val depth = IntArray(n) { -1 }
        val q = ArrayList<Int>()
        roots.forEach { depth[it] = 0; q.add(it) }
        var guard = 0
        while (q.isNotEmpty() && guard++ < 400) {
            val c = q.removeAt(0)
            edges.filter { it.first == c }.forEach { (_, t) ->
                if (t in 0 until n && depth[t] < 0) { depth[t] = depth[c] + 1; q.add(t) }
            }
        }
        val maxD = depth.maxOrNull() ?: 0
        // 每个节点的下游数
        val downCount = IntArray(n)
        edges.forEach { (f, _) -> if (f in 0 until n) downCount[f]++ }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "数据血缘"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("${roots.size} 个源 / 最深 ${maxD} 层", fontSize = 9.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(6.dp))
            // 分层排列
            val byDepth = (0..maxD).map { d -> (0 until n).filter { depth[it] == d } }
            byDepth.forEachIndexed { d, ids ->
                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("L$d", fontSize = 7.sp, color = ZxT.Text3,
                         modifier = Modifier.width(14.dp))
                    ids.forEach { k ->
                        val c = if (k in roots) PAL[0] else if (downCount[k] == 0) ZxT.Ok else PAL[4]
                        Box(Modifier.weight(1f).padding(horizontal = 1.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (sel == k) ZxT.Surface2 else c.copy(.14f))
                            .clickable {
                                sel = k
                                ctx.callTool("zx_select", mapOf(
                                    "table" to nodes[k].first,
                                    "depth" to (if (depth[k] < 0) "?" else depth[k].toString()),
                                    "downstream" to downCount[k].toString(),
                                    "source" to "zx_lineage"))
                            }.padding(4.dp, 3.dp)) {
                            Text(nodes[k].first, fontSize = 8.sp, color = ZxT.Text,
                                 maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            if (sel >= 0) {
                Row(Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(5.dp)).background(ZxT.Surface2)
                    .padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(nodes[sel].first, fontSize = 9.sp, color = ZxT.Text,
                         modifier = Modifier.weight(1f))
                    Text("深度 ${if (depth[sel] < 0) "?" else depth[sel]}", fontSize = 8.5.sp,
                         color = ZxT.Text3)
                    Text("下游 ${downCount[sel]}", fontSize = 8.5.sp, color = PAL[4],
                         modifier = Modifier.padding(start = 6.dp))
                }
            }
            val widest = (0..maxD).map { d -> (0 until n).count { depth[it] == d } }.maxOrNull() ?: 0
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (maxD >= 4) ZxT.Warn.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (maxD >= 4) "!" else "✓", fontSize = 11.sp,
                     color = if (maxD >= 4) ZxT.Warn else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (maxD >= 4) "**最深 ${maxD} 层**——改最上游的表，要等 ${maxD} 跳才能看到最终结果，" +
                            "出问题时定位会很慢"
                         else "最深 ${maxD} 层，链路还算清楚",
                        fontSize = 9.sp, color = if (maxD >= 4) ZxT.Warn else ZxT.Ok)
                    Text("血缘图只画箭头不够——**深度和影响面才是改动的代价**",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 topology 做<b>服务健康传播</b>、depgraph 画<b>依赖</b>，" +
                 "这个做的是<b>数据表的血缘与深度</b>。" +
                 "关键是<b>要算出深度</b>：只画箭头，看不出「改这张表要跳几层才到报表」。" +
                 "<b>有环会被拦</b>（血缘无限）；<b>边指向不存在的节点会被拦</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_impact 影响分析 ══════════ */
    @Composable
    fun impact(o: JSONObject, ctx: RenderContext) {
        val change = o.optString("change", "")
        val itemsA = o.optJSONArray("items") ?: return
        val n = minOf(itemsA.length(), 8)
        val items = (0 until n).map { i ->
            val it = itemsA.optJSONObject(i) ?: JSONObject()
            Triple(it.optString("t", "目标${i + 1}"), it.optString("sev", "med"),
                   it.optString("fix", ""))
        }
        var sel by remember { mutableIntStateOf(-1) }
        val crit = items.count { it.second == "crit" || it.second == "high" }
        val noFix = items.count { it.third.isBlank() }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "影响分析"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (crit > 0) "$crit 项高危" else "${items.size} 项受影响",
                    fontSize = 9.sp, color = if (crit > 0) ZxT.Err else ZxT.Warn)
            }
            if (change.isNotBlank())
                Text("改动：$change", fontSize = 9.5.sp, color = ZxT.Text2,
                     modifier = Modifier.padding(top = 5.dp), lineHeight = 13.sp)
            Spacer(Modifier.height(5.dp))
            items.forEachIndexed { k, (t, sev, fix) ->
                val c = sc(sev)
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2
                                else if (sev == "crit") ZxT.Err.copy(.09f)
                                else if (fix.isBlank()) ZxT.Warn.copy(.06f) else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_select", mapOf(
                            "target" to t, "severity" to sev, "fix" to fix,
                            "source" to "zx_impact"))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.Top) {
                    Text(when (sev) { "crit" -> "✕"; "high" -> "!"; "med" -> "·"; else -> "◦" },
                        fontSize = 9.sp, color = c, modifier = Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(t, fontSize = 9.5.sp, color = ZxT.Text,
                                 modifier = Modifier.weight(1f), maxLines = 1,
                                 overflow = TextOverflow.Ellipsis)
                            Text(when (sev) { "crit" -> "致命"; "high" -> "高"; "med" -> "中"
                                              else -> "低" }, fontSize = 8.sp, color = c)
                        }
                        Text(if (fix.isBlank()) "**不知道怎么修** —— 只报告影响不给办法，等于把问题丢回去"
                             else fix, fontSize = 8.5.sp,
                             color = if (fix.isBlank()) ZxT.Warn else ZxT.Text3,
                             modifier = Modifier.padding(top = 1.dp), maxLines = 2,
                             overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (crit > 0) ZxT.Err.copy(.10f)
                            else if (noFix > 0) ZxT.Warn.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (crit > 0) "✗" else if (noFix > 0) "!" else "✓", fontSize = 11.sp,
                     color = if (crit > 0) ZxT.Err else if (noFix > 0) ZxT.Warn else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (crit > 0) "**$crit 项高危**：这几项不动，改动就不能上"
                         else if (noFix > 0) "$noFix 项没写怎么修——**只报告影响不给办法，**" +
                            "等于把问题丢回去，用户还是不知道下一步"
                         else "${items.size} 项都标了修复方式",
                        fontSize = 9.sp,
                        color = if (crit > 0) ZxT.Err else if (noFix > 0) ZxT.Warn else ZxT.Ok)
                    Text("影响分析的价值不在「影响了几项」，在**「先修哪个、怎么修」**",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 topology 算<b>服务影响面</b>、lineage 看<b>表血缘</b>，" +
                 "这个回答的是<b>改一个字段，波及哪些下游报表、怎么修</b>。" +
                 "关键是<b>每项都要给修复方式</b>——只列「会影响 3 张报表」，" +
                 "用户还得自己想怎么办。<b>影响项缺目标会被拦</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_dataclass 数据分级 ══════════ */
    @Composable
    fun dataclass(o: JSONObject, ctx: RenderContext) {
        val colsA = o.optJSONArray("cols") ?: return
        val n = minOf(colsA.length(), 10)
        val cols = (0 until n).map {
            val c = colsA.optJSONObject(it) ?: JSONObject()
            Triple(c.optString("n", "列${it + 1}"), c.optString("lv", ""),
                   c.optString("note", ""))
        }
        var sel by remember { mutableIntStateOf(-1) }
        val unclassified = cols.count { it.second.isBlank() }
        val byLv = cols.groupBy { it.second }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "数据分级"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (unclassified > 0) "$unclassified 列未分级" else "全部已分级",
                    fontSize = 9.sp, color = if (unclassified > 0) ZxT.Err else ZxT.Ok)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().padding(bottom = 3.dp)) {
                listOf("public", "internal", "confidential", "secret").forEach { lv ->
                    val c = byLv[lv]?.size ?: 0
                    Box(Modifier.weight(1f).padding(horizontal = 1.dp)
                        .clip(RoundedCornerShape(4.dp)).background(sc(lv).copy(.13f))
                        .padding(3.dp, 2.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                               modifier = Modifier.fillMaxWidth()) {
                            Text(c.toString(), fontSize = 10.sp, color = sc(lv),
                                 fontFamily = ZxT.Mono)
                            Text(when (lv) { "public" -> "公开"; "internal" -> "内部"
                                             "confidential" -> "保密"; else -> "机密" },
                                fontSize = 7.5.sp, color = ZxT.Text3)
                        }
                    }
                }
            }
            cols.forEachIndexed { k, (nm, lv, note) ->
                val c = if (lv.isBlank()) ZxT.Err else sc(lv)
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (sel == k) ZxT.Surface2
                                else if (lv.isBlank()) ZxT.Err.copy(.07f) else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_select", mapOf(
                            "col" to nm, "level" to lv, "note" to note,
                            "source" to "zx_dataclass"))
                    }.padding(5.dp, 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(nm, fontSize = 9.sp, color = ZxT.Text2,
                         modifier = Modifier.weight(1f), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    if (note.isNotBlank())
                        Text(note, fontSize = 7.5.sp, color = ZxT.Text3,
                             modifier = Modifier.width(64.dp).padding(end = 4.dp),
                             maxLines = 1, overflow = TextOverflow.Ellipsis,
                             textAlign = TextAlign.End)
                    Text(if (lv.isBlank()) "**未分级**"
                         else when (lv) { "public" -> "公开"; "internal" -> "内部"
                                          "confidential" -> "保密"; else -> "机密" },
                        fontSize = 8.sp, color = c,
                        modifier = Modifier.width(40.dp), textAlign = TextAlign.End)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (unclassified > 0) ZxT.Err.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (unclassified > 0) "✗" else "✓", fontSize = 11.sp,
                     color = if (unclassified > 0) ZxT.Err else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (unclassified > 0) "**$unclassified 列没标级别**——" +
                            "**没分级的列默认按最高敏感处理**，" +
                            "否则一旦流出，没人知道它本来该受什么保护"
                         else "${cols.size} 列全部标了级别",
                        fontSize = 9.sp, color = if (unclassified > 0) ZxT.Err else ZxT.Ok)
                    Text("分级的意义是**决定谁能看、能留多久**——没级别这两件事都做不了",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 table 列<b>数据行</b>、inspector 查<b>字段详情</b>，" +
                 "这个做的是<b>给每列标敏感级别</b>。" +
                 "关键是<b>不能有未分级的列</b>——漏掉一列，" +
                 "整张表的保护级别就按最高的那一列算。" +
                 "<b>有列未分级会被拦</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_retention 保留策略 ══════════ */
    @Composable
    fun retention(o: JSONObject, ctx: RenderContext) {
        val itemsA = o.optJSONArray("items") ?: return
        val n = minOf(itemsA.length(), 8)
        val items = (0 until n).map { i ->
            val it = itemsA.optJSONObject(i) ?: JSONObject()
            Triple(it.optString("n", "数据${i + 1}"), it.optInt("keep", 0),
                   it.optInt("age", 0))
        }
        var sel by remember { mutableIntStateOf(-1) }
        val expired = items.count { it.second > 0 && it.third > it.second }
        val near = items.count { it.second > 0 && it.third <= it.second &&
                                 it.third > it.second * 0.8 }
        val mx = items.maxOf { maxOf(it.second, it.third) }.coerceAtLeast(1)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "保留策略"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (expired > 0) "$expired 项已到期" else "均在保留期内",
                    fontSize = 9.sp, color = if (expired > 0) ZxT.Err else ZxT.Ok)
            }
            Spacer(Modifier.height(6.dp))
            items.forEachIndexed { k, (nm, keep, age) ->
                val over = keep > 0 && age > keep
                val warn = keep > 0 && !over && age > keep * 0.8
                val pct = (age.toFloat() / mx).coerceIn(0.01f, 1f)
                val kp = (keep.toFloat() / mx).coerceIn(0.01f, 1f)
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (sel == k) ZxT.Surface2
                                else if (over) ZxT.Err.copy(.07f) else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_select", mapOf(
                            "item" to nm, "keep" to keep.toString(), "age" to age.toString(),
                            "expired" to (if (over) "yes" else "no"),
                            "source" to "zx_retention"))
                    }.padding(5.dp, 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(nm, fontSize = 9.sp, color = ZxT.Text2,
                         modifier = Modifier.width(58.dp), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    Box(Modifier.weight(1f).height(9.dp)
                        .clip(RoundedCornerShape(4.dp)).background(ZxT.Surface2)) {
                        Box(Modifier.fillMaxWidth(pct).fillMaxHeight()
                            .background(if (over) ZxT.Err else if (warn) ZxT.Warn else PAL[0]))
                        // 保留期限刻度
                        Box(Modifier.fillMaxWidth(kp).fillMaxHeight()
                            .background(Color.Transparent))
                    }
                    Text("${age}d", fontSize = 8.5.sp,
                         color = if (over) ZxT.Err else ZxT.Text2,
                         modifier = Modifier.width(30.dp), textAlign = TextAlign.End,
                         fontFamily = ZxT.Mono)
                    Text("/${keep}d", fontSize = 8.sp, color = ZxT.Text3,
                         modifier = Modifier.width(28.dp), textAlign = TextAlign.End)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (expired > 0) ZxT.Err.copy(.10f)
                            else if (near > 0) ZxT.Warn.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (expired > 0) "✗" else if (near > 0) "!" else "✓", fontSize = 11.sp,
                     color = if (expired > 0) ZxT.Err else if (near > 0) ZxT.Warn else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (expired > 0) "**$expired 项已超过保留期**——" +
                            "按策略**该删了**。留着不只是占空间，是合规风险"
                         else if (near > 0) "$near 项接近保留期上限（>80%），快到期了"
                         else "全部在保留期内",
                        fontSize = 9.sp,
                        color = if (expired > 0) ZxT.Err else if (near > 0) ZxT.Warn else ZxT.Ok)
                    Text("「数据留着总没坏处」是错的——**超过保留期的数据是负债不是资产**",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 debt 管<b>技术债</b>、checkpoint 记<b>保存点</b>，" +
                 "这个管的是<b>数据该留多久</b>。" +
                 "关键是<b>要标出已到期的</b>：只给「保留 90 天」不给「现在存了多久」，" +
                 "没人知道该删了。<b>保留天数非正会被拦</b>（到期判断失效）。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_workload 团队负载 ══════════ */
    @Composable
    fun workload(o: JSONObject, ctx: RenderContext) {
        val peopleA = o.optJSONArray("people") ?: return
        val n = minOf(peopleA.length(), 8)
        val people = (0 until n).map {
            val p = peopleA.optJSONObject(it) ?: JSONObject()
            Triple(p.optString("n", "成员${it + 1}"), p.optInt("todo", 0),
                   p.optInt("cap", 10))
        }
        var sel by remember { mutableIntStateOf(-1) }
        val rates = people.map { (_, t, c) -> t.toDouble() / c.coerceAtLeast(1) }
        val overloaded = rates.count { it > 1.0 }
        val tight = rates.count { it > 0.85 && it <= 1.0 }
        val idle = rates.count { it < 0.5 }
        val mx = rates.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "团队负载"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (overloaded > 0) "$overloaded 人超载" else if (idle > 0) "$idle 人空闲"
                     else "基本均衡", fontSize = 9.sp,
                     color = if (overloaded > 0) ZxT.Err else if (idle > 0) ZxT.Warn else ZxT.Ok)
            }
            Spacer(Modifier.height(6.dp))
            people.forEachIndexed { k, (nm, todo, cap) ->
                val r = todo.toDouble() / cap.coerceAtLeast(1)
                val c = if (r > 1.0) ZxT.Err else if (r > 0.85) ZxT.Warn else if (r < 0.5) PAL[4] else ZxT.Ok
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (sel == k) ZxT.Surface2
                                else if (r > 1.0) ZxT.Err.copy(.07f) else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_select", mapOf(
                            "person" to nm, "todo" to todo.toString(), "cap" to cap.toString(),
                            "rate" to "%.2f".format(r), "source" to "zx_workload"))
                    }.padding(5.dp, 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(nm, fontSize = 9.sp, color = ZxT.Text2,
                         modifier = Modifier.width(46.dp), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    Box(Modifier.weight(1f).height(9.dp)
                        .clip(RoundedCornerShape(4.dp)).background(ZxT.Surface2)) {
                        Box(Modifier.fillMaxWidth((r / mx).toFloat().coerceIn(0.02f, 1f))
                            .fillMaxHeight().background(c))
                    }
                    Text("$todo/$cap", fontSize = 8.5.sp, color = ZxT.Text3,
                         modifier = Modifier.width(36.dp), textAlign = TextAlign.End,
                         fontFamily = ZxT.Mono)
                    Text("%.0f%%".format(r * 100), fontSize = 8.5.sp, color = c,
                         modifier = Modifier.width(30.dp), textAlign = TextAlign.End)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (overloaded > 0) ZxT.Err.copy(.10f)
                            else if (idle > 0) ZxT.Warn.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (overloaded > 0) "✗" else if (idle > 0) "!" else "✓", fontSize = 11.sp,
                     color = if (overloaded > 0) ZxT.Err else if (idle > 0) ZxT.Warn else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (overloaded > 0) "**$overloaded 人超载（>100%）**——" +
                            "${if (idle > 0) "同时有 $idle 人空闲（<50%）：这不是缺人，是**分配不均**，" +
                                "加人也解决不了" else "先考虑把任务挪给有余量的人，而不是加人"}"
                         else if (idle > 0) "$idle 人空闲（<50%），可以把超载的任务挪过来"
                         else "负载基本均衡",
                        fontSize = 9.sp,
                        color = if (overloaded > 0) ZxT.Err else if (idle > 0) ZxT.Warn else ZxT.Ok)
                    Text("**「忙」和「堵」是两回事**——有人超载同时有人空闲，那是分配问题不是人力问题",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 assign 分<b>责任人</b>、capacity 算<b>系统容量</b>，" +
                 "这个看的是<b>人的负载</b>。" +
                 "关键是<b>要同时看超载和空闲</b>：只看「谁忙」，" +
                 "会得出「需要加人」的错误结论——实际可能是分配不均。" +
                 "<b>capacity 非正会被拦</b>（负载率除零）。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_bottleneck 瓶颈识别 ══════════ */
    @Composable
    fun bottleneck(o: JSONObject, ctx: RenderContext) {
        val stagesA = o.optJSONArray("stages") ?: return
        val n = minOf(stagesA.length(), 8)
        val stages = (0 until n).map {
            val s = stagesA.optJSONObject(it) ?: JSONObject()
            Triple(s.optString("n", "环节${it + 1}"), s.optDouble("thr", 0.0),
                   s.optDouble("wip", 0.0))
        }
        var sel by remember { mutableIntStateOf(-1) }
        // 吞吐最小的是约束；排队最长的是最堵的
        val minThr = stages.minOf { it.second }
        val constraint = stages.indexOfFirst { it.second == minThr }
        val maxWait = stages.maxOfOrNull { if (it.second > 0) it.third / it.second else 0.0 } ?: 0.0
        val queue = stages.indexOfFirst {
            it.second > 0 && (it.third / it.second) == maxWait }
        val mx = stages.maxOf { it.second }.coerceAtLeast(1.0)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "瓶颈识别"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("约束：${stages[constraint].first}", fontSize = 9.sp, color = ZxT.Err)
            }
            Spacer(Modifier.height(6.dp))
            stages.forEachIndexed { k, (nm, thr, wip) ->
                val isConstraint = k == constraint
                val wait = if (thr > 0) wip / thr else Double.NaN
                val c = if (isConstraint) ZxT.Err else if (k == queue) ZxT.Warn else PAL[0]
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (sel == k) ZxT.Surface2
                                else if (isConstraint) ZxT.Err.copy(.08f)
                                else if (k == queue) ZxT.Warn.copy(.06f) else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_select", mapOf(
                            "stage" to nm, "throughput" to "%.1f".format(thr),
                            "wip" to "%.1f".format(wip),
                            "wait" to (if (wait.isNaN()) "—" else "%.2f".format(wait)),
                            "is_constraint" to (if (isConstraint) "yes" else "no"),
                            "source" to "zx_bottleneck"))
                    }.padding(5.dp, 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(nm, fontSize = 9.sp, color = ZxT.Text2,
                         modifier = Modifier.width(50.dp), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    Box(Modifier.weight(1f).height(9.dp)
                        .clip(RoundedCornerShape(4.dp)).background(ZxT.Surface2)) {
                        Box(Modifier.fillMaxWidth((thr / mx).toFloat().coerceIn(0.02f, 1f))
                            .fillMaxHeight().background(c))
                    }
                    Text("%.0f".format(thr), fontSize = 8.5.sp, color = ZxT.Text,
                         modifier = Modifier.width(28.dp), textAlign = TextAlign.End,
                         fontFamily = ZxT.Mono)
                    Text(if (wait.isNaN()) "—" else "%.1f".format(wait), fontSize = 8.5.sp,
                         color = if (wait.isNaN()) ZxT.Text3 else if (wait > maxWait * 0.8) ZxT.Warn else ZxT.Text3,
                         modifier = Modifier.width(26.dp), textAlign = TextAlign.End)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Text("吞吐", fontSize = 7.5.sp, color = ZxT.Text3,
                     modifier = Modifier.weight(1f).padding(start = 50.dp))
                Text("等待时长（WIP/吞吐）", fontSize = 7.5.sp, color = ZxT.Text3)
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(ZxT.Err.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("✗", fontSize = 11.sp, color = ZxT.Err)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text("约束在「${stages[constraint].first}」（吞吐 ${"%.0f".format(minThr)} 最低）。" +
                            "**提升别的环节都没用**——整体产出由它决定",
                        fontSize = 9.sp, color = ZxT.Err)
                    Text(if (queue != constraint) "但最堵的是「${stages[queue].first}」" +
                        "（等待 ${"%.1f".format(maxWait)}）——" +
                        "**约束和最堵的不是同一个**，说明 WIP 堆错了地方"
                        else "它也确实最堵，改它就对了",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 capacity 算<b>单系统容量</b>、checkpoint 记<b>进度</b>，" +
                 "这个找的是<b>多环节流程里的约束</b>。" +
                 "关键是<b>要区分「约束」和「最堵」</b>——" +
                 "吞吐最低的是约束（决定产出），排队最长的是最堵（决定交付感），" +
                 "两者经常不是同一个。<b>吞吐非正会被拦</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_expresult 实验结果 ══════════ */
    @Composable
    fun expresult(o: JSONObject, ctx: RenderContext) {
        val metricsA = o.optJSONArray("metrics") ?: return
        val n = minOf(metricsA.length(), 5)
        val metrics = (0 until n).map {
            val m = metricsA.optJSONObject(it) ?: JSONObject()
            Triple(m.optString("n", "指标${it + 1}"), m.optDouble("lift", 0.0),
                   m.optDouble("p", 1.0))
        }
        var sel by remember { mutableIntStateOf(-1) }
        val decision = o.optString("decision", "")
        val sig = metrics.count { it.third < 0.05 }
        val tiny = metrics.count { it.third < 0.05 && abs(it.second) < 0.01 }
        val mx = metrics.maxOf { abs(it.second) }.coerceAtLeast(0.001)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "实验结果"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (sig == 0) "无显著差异" else "$sig 项显著" +
                        (if (tiny > 0) "（$tiny 项提升极小）" else ""),
                    fontSize = 9.sp,
                    color = if (sig == 0) ZxT.Text3 else if (tiny == sig) ZxT.Warn else ZxT.Ok)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
                Text("指标", fontSize = 8.sp, color = ZxT.Text3, modifier = Modifier.weight(1f))
                Text("提升", fontSize = 8.sp, color = ZxT.Text3,
                     modifier = Modifier.width(52.dp), textAlign = TextAlign.End)
                Text("p 值", fontSize = 8.sp, color = ZxT.Text3,
                     modifier = Modifier.width(38.dp), textAlign = TextAlign.End)
            }
            metrics.forEachIndexed { k, (nm, lift, p) ->
                val isSig = p < 0.05
                val isTiny = isSig && abs(lift) < 0.01
                val c = if (!isSig) ZxT.Text3 else if (isTiny) ZxT.Warn
                        else if (lift > 0) ZxT.Ok else ZxT.Err
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (sel == k) ZxT.Surface2
                                else if (isTiny) ZxT.Warn.copy(.06f) else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_select", mapOf(
                            "metric" to nm, "lift" to "%.4f".format(lift),
                            "p" to "%.4f".format(p),
                            "significant" to (if (isSig) "yes" else "no"),
                            "source" to "zx_expresult"))
                    }.padding(5.dp, 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(nm, fontSize = 9.sp, color = ZxT.Text2,
                         modifier = Modifier.weight(1f), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    // 提升条：以 0 为中心向两侧
                    Box(Modifier.width(52.dp).height(8.dp)) {
                        Box(Modifier.fillMaxWidth().fillMaxHeight()
                            .clip(RoundedCornerShape(3.dp)).background(ZxT.Surface2))
                        Box(Modifier.fillMaxWidth((abs(lift) / mx).toFloat().coerceIn(0.02f, 1f))
                            .fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(c))
                    }
                    Text("${if (lift > 0) "+" else ""}${"%.2f%%".format(lift * 100)}",
                        fontSize = 8.5.sp, color = c,
                        modifier = Modifier.width(52.dp), textAlign = TextAlign.End,
                        fontFamily = ZxT.Mono)
                    Text(if (p < 0.001) "<0.001" else "%.3f".format(p), fontSize = 8.sp,
                         color = if (isSig) ZxT.Text2 else ZxT.Text3,
                         modifier = Modifier.width(38.dp), textAlign = TextAlign.End,
                         fontFamily = ZxT.Mono)
                }
            }
            if (decision.isNotBlank())
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                    .clip(RoundedCornerShape(5.dp)).background(ZxT.Surface2)
                    .padding(6.dp, 4.dp)) {
                    Text("建议：$decision", fontSize = 9.sp, color = ZxT.Text2,
                         lineHeight = 13.sp)
                }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (sig == 0) ZxT.Surface3
                            else if (tiny == sig) ZxT.Warn.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (sig == 0) "·" else if (tiny == sig) "!" else "✓", fontSize = 11.sp,
                     color = if (sig == 0) ZxT.Text3 else if (tiny == sig) ZxT.Warn else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (sig == 0) "没有指标达到显著——**不显著不等于没效果**，" +
                            "也可能是样本不够（看功效分析）"
                         else if (tiny == sig) "**显著但提升极小**（都 <1%）——" +
                            "统计上显著不等于值得上：改动有成本，1% 可能覆盖不了"
                         else "$sig 项显著，且提升幅度可看",
                        fontSize = 9.sp,
                        color = if (sig == 0) ZxT.Text2 else if (tiny == sig) ZxT.Warn else ZxT.Ok)
                    Text("**显著 ≠ 该上**。要看实际提升能不能覆盖改动成本——" +
                        "p 值回答「有没有」，提升幅度回答「值不值」",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 zx_test 给<b>p 值</b>、zx_ab 做<b>分流监控</b>，" +
                 "这个回答的是<b>「所以要不要上」</b>。" +
                 "关键是<b>必须给实际提升</b>——只有 p<0.05 不给提升幅度，" +
                 "会让人把「统计上显著」当成「值得上线」。" +
                 "<b>给了 p 却没给提升会被拦</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_compliance 合规检查 ══════════ */
    @Composable
    fun compliance(o: JSONObject, ctx: RenderContext) {
        val itemsA = o.optJSONArray("items") ?: return
        val n = minOf(itemsA.length(), 8)
        val items = (0 until n).map { i ->
            val it = itemsA.optJSONObject(i) ?: JSONObject()
            Triple(it.optString("n", "检查项${i + 1}"), it.optString("s", "pass"),
                   it.optString("who", ""))
        }
        var sel by remember { mutableIntStateOf(-1) }
        val fail = items.count { it.second == "fail" }
        val warn = items.count { it.second == "warn" }
        val failNoOwner = items.count { it.second == "fail" && it.third.isBlank() }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "合规检查"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (fail > 0) "$fail 项不合格" else if (warn > 0) "$warn 项待确认" else "全部通过",
                    fontSize = 9.sp,
                    color = if (fail > 0) ZxT.Err else if (warn > 0) ZxT.Warn else ZxT.Ok)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().padding(bottom = 3.dp)) {
                listOf("pass" to "通过", "warn" to "待确认", "fail" to "不合格").forEach { (lv, lb) ->
                    val c = items.count { it.second == lv }
                    Box(Modifier.weight(1f).padding(horizontal = 1.dp)
                        .clip(RoundedCornerShape(4.dp)).background(sc(lv).copy(.13f))
                        .padding(3.dp, 2.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                               modifier = Modifier.fillMaxWidth()) {
                            Text(c.toString(), fontSize = 10.sp, color = sc(lv),
                                 fontFamily = ZxT.Mono)
                            Text(lb, fontSize = 7.5.sp, color = ZxT.Text3)
                        }
                    }
                }
            }
            items.forEachIndexed { k, (nm, s, who) ->
                val isFail = s == "fail"
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (sel == k) ZxT.Surface2
                                else if (isFail && who.isBlank()) ZxT.Err.copy(.07f)
                                else if (isFail) ZxT.Err.copy(.05f) else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_select", mapOf(
                            "item" to nm, "status" to s, "owner" to who,
                            "source" to "zx_compliance"))
                    }.padding(5.dp, 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(when (s) { "pass" -> "✓"; "warn" -> "!"; else -> "✕" },
                        fontSize = 9.sp, color = sc(s), modifier = Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(nm, fontSize = 9.5.sp, color = ZxT.Text, maxLines = 1,
                             overflow = TextOverflow.Ellipsis)
                        if (isFail)
                            Text(if (who.isBlank()) "**没有整改人** —— 没人改就永远不合格"
                                 else "整改：$who", fontSize = 8.sp,
                                 color = if (who.isBlank()) ZxT.Err else ZxT.Text3,
                                 modifier = Modifier.padding(top = 1.dp))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (failNoOwner > 0) ZxT.Err.copy(.10f)
                            else if (fail > 0) ZxT.Warn.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (failNoOwner > 0) "✗" else if (fail > 0) "!" else "✓", fontSize = 11.sp,
                     color = if (failNoOwner > 0) ZxT.Err else if (fail > 0) ZxT.Warn else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (failNoOwner > 0) "**$failNoOwner 项不合格且没整改人**——" +
                            "**没人改就永远不合格**，检查就变成了走过场"
                         else if (fail > 0) "$fail 项不合格，都已指定整改人"
                         else "全部通过",
                        fontSize = 9.sp,
                        color = if (failNoOwner > 0) ZxT.Err else if (fail > 0) ZxT.Warn else ZxT.Ok)
                    Text("合规检查最容易变成**「查完记下来，然后没有然后」**——" +
                        "所以不合格项必须落到人",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 checklist 是<b>要勾的清单</b>、healthcheck 看<b>若干检查项</b>，" +
                 "这个做的是<b>有强制性的合规项</b>。" +
                 "关键是<b>不合格项必须落到人</b>——" +
                 "只标「不合规」不给整改人，下次检查还是不合规。" +
                 "<b>有不合格项却没整改人会被拦</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_audittrail 审计追踪 ══════════ */
    @Composable
    fun audittrail(o: JSONObject, ctx: RenderContext) {
        val entriesA = o.optJSONArray("entries") ?: return
        val n = minOf(entriesA.length(), 10)
        val entries = (0 until n).map {
            val e = entriesA.optJSONObject(it) ?: JSONObject()
            listOf(e.optString("who", ""), e.optString("what", ""),
                   e.optString("when", ""), e.optString("kind", "read"))
        }
        var sel by remember { mutableIntStateOf(-1) }
        val noWho = entries.count { it[0].isBlank() }
        val write = entries.count { it[3] in setOf("write", "delete", "grant", "export") }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "审计追踪"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("${entries.size} 条 / $write 条写操作", fontSize = 9.sp,
                     color = if (write > 0) ZxT.Warn else ZxT.Text3)
            }
            Spacer(Modifier.height(6.dp))
            entries.forEachIndexed { k, e ->
                val isWrite = e[3] in setOf("write", "delete", "grant", "export")
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (sel == k) ZxT.Surface2
                                else if (isWrite) ZxT.Warn.copy(.05f) else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_select", mapOf(
                            "who" to e[0], "what" to e[1], "when" to e[2], "kind" to e[3],
                            "source" to "zx_audittrail"))
                    }.padding(5.dp, 3.dp), verticalAlignment = Alignment.Top) {
                    Text(when (e[3]) { "write" -> "∼"; "delete" -> "✕"; "grant" -> "⚿"
                                       "export" -> "⇱"; else -> "◦" },
                        fontSize = 9.sp,
                        color = if (isWrite) ZxT.Warn else ZxT.Text3,
                        modifier = Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (e[0].isBlank()) "**未知操作者**" else e[0],
                                fontSize = 8.5.sp,
                                color = if (e[0].isBlank()) ZxT.Err else ZxT.Text3,
                                modifier = Modifier.width(52.dp), maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text(e[1], fontSize = 9.5.sp, color = ZxT.Text,
                                 modifier = Modifier.weight(1f), maxLines = 1,
                                 overflow = TextOverflow.Ellipsis)
                        }
                        Text(if (e[2].isBlank()) "无时间" else e[2], fontSize = 8.sp,
                             color = if (e[2].isBlank()) ZxT.Text3 else ZxT.Text3,
                             modifier = Modifier.padding(start = 52.dp, top = 1.dp))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (noWho > 0) ZxT.Err.copy(.10f)
                            else if (write > 0) ZxT.Warn.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (noWho > 0) "✗" else if (write > 0) "!" else "✓", fontSize = 11.sp,
                     color = if (noWho > 0) ZxT.Err else if (write > 0) ZxT.Warn else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (noWho > 0) "**$noWho 条没有操作者**——审计追踪的意义就是追到人，" +
                            "追不到人的日志等于没记"
                         else if (write > 0) "$write 条写/删/授权/导出操作——这些是**审计真正关心的**，" +
                            "读操作只是噪音"
                         else "全是读操作",
                        fontSize = 9.sp,
                        color = if (noWho > 0) ZxT.Err else if (write > 0) ZxT.Warn else ZxT.Ok)
                    Text("审计追踪要能回答**「谁在什么时候改了什么」**——" +
                        "三个缺一个就追不到底",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 audit 记<b>系统事件</b>、logstream 看<b>日志</b>，" +
                 "这个记的是<b>人的操作</b>。" +
                 "关键是<b>每条都要有操作者</b>——只有时间和动作、没有谁，" +
                 "出事时追不到人。<b>有条目缺操作者会被拦</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_accessreview 权限评审 ══════════ */
    @Composable
    fun accessreview(o: JSONObject, ctx: RenderContext) {
        val itemsA = o.optJSONArray("items") ?: return
        val n = minOf(itemsA.length(), 8)
        val items = (0 until n).map { i ->
            val it = itemsA.optJSONObject(i) ?: JSONObject()
            Triple(it.optString("who", "用户${i + 1}"), it.optString("perm", ""),
                   it.optInt("age", 0))
        }
        var sel by remember { mutableIntStateOf(-1) }
        val staleAt = o.optInt("stale", 90)
        val stale = items.count { it.third > staleAt }
        val overPriv = items.count { it.second in setOf("admin", "owner", "root") }
        val mx = items.maxOf { it.third }.coerceAtLeast(1)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "权限评审"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (stale > 0) "$stale 项超期未审" else "均在审期内", fontSize = 9.sp,
                     color = if (stale > 0) ZxT.Err else ZxT.Ok)
            }
            Spacer(Modifier.height(6.dp))
            items.forEachIndexed { k, (who, perm, age) ->
                val isStale = age > staleAt
                val isHigh = perm in setOf("admin", "owner", "root")
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (sel == k) ZxT.Surface2
                                else if (isStale && isHigh) ZxT.Err.copy(.09f)
                                else if (isStale) ZxT.Warn.copy(.06f)
                                else if (isHigh) ZxT.Warn.copy(.04f) else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_select", mapOf(
                            "who" to who, "perm" to perm, "age" to age.toString(),
                            "stale" to (if (isStale) "yes" else "no"),
                            "high_priv" to (if (isHigh) "yes" else "no"),
                            "source" to "zx_accessreview"))
                    }.padding(5.dp, 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(who, fontSize = 9.sp, color = ZxT.Text2,
                         modifier = Modifier.width(52.dp), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    Box(Modifier.weight(1f).height(8.dp)
                        .clip(RoundedCornerShape(4.dp)).background(ZxT.Surface2)) {
                        Box(Modifier.fillMaxWidth((age.toFloat() / mx).coerceIn(0.02f, 1f))
                            .fillMaxHeight()
                            .background(if (isStale) ZxT.Err else ZxT.Text3.copy(.5f)))
                    }
                    Text(perm, fontSize = 8.sp, color = if (isHigh) ZxT.Warn else ZxT.Text3,
                         modifier = Modifier.width(44.dp).padding(start = 4.dp),
                         maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${age}d", fontSize = 8.5.sp,
                         color = if (isStale) ZxT.Err else ZxT.Text3,
                         modifier = Modifier.width(28.dp), textAlign = TextAlign.End,
                         fontFamily = ZxT.Mono)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (stale > 0) ZxT.Err.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (stale > 0) "✗" else "✓", fontSize = 11.sp,
                     color = if (stale > 0) ZxT.Err else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (stale > 0) "**$stale 项超过 ${staleAt} 天没审**——" +
                            "${if (overPriv > 0) "其中 $overPriv 项是高权限：离职的人如果还开着，" +
                                "这就是**最典型的漏子**" else "权限会随时间漂移，审一次不代表一直该有"}"
                         else "全部在 ${staleAt} 天审期内",
                        fontSize = 9.sp, color = if (stale > 0) ZxT.Err else ZxT.Ok)
                    Text("权限评审看的不是「有什么权限」，是**「多久没确认还该不该有」**",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 escalation 定<b>升级路径</b>、assign 分<b>责任人</b>，" +
                 "这个管的是<b>权限是否还该有</b>。" +
                 "关键是<b>必须标「多久没审」</b>——只列「张三：管理员」，" +
                 "不知道这个权限是三年前给的、而他可能早就不该有了。" +
                 "<b>全部没审过时间会被拦</b>（判断不了过期）。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }
}
