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
import kotlin.math.sqrt
import androidx.compose.ui.geometry.Size

/**
 * 第二十八批组件（12 个）—— 行动之后。
 *
 * 前二十七批一直在往"**决定的那一刻**"推进：
 *
 *   看什么 → 填什么 → 改什么 → 凭什么 → 是不是因果 →
 *   在多大范围内成立 → 接下来该做什么
 *
 * 到第二十七批，decision / bandit / alloc 已经能帮人**做出选择了**。
 *
 * 但决定做出来之后，界面就不管了。
 *
 * 真实情况是这个决定要被执行、要出问题、要被修正：
 *
 *   "灰度放到 20% 了，要不要继续放到 50%？"（rollout）
 *   "新版本比旧版本差吗，差在哪？"（canary）
 *   "这个月的错误预算还剩多少？"（slo）
 *   "凌晨那次挂了，到底是因为什么？"（incident / rca）
 *   "上周选了 A 方案，现在看是不是选错了？"（regret）
 *   "这个策略在历史上真的有效吗？"（backtest）
 *   "线上数据和训练数据是不是已经不一样了？"（drift）
 *   "这个告警要不要处理、谁来处理？"（alert）
 *   "这个月花了多少，会不会超？"（costtrack）
 *   "上次 AI 说的那个数，后来对了吗？"（feedback）
 *
 * 这些全都没有组件。用现有的凑：
 *   rollout 用 progress 顶替 —— 看不出每批的健康度，**放量是拍脑袋的**
 *   incident 用 timeline 顶替 —— 只有时间，**没有"从发现到恢复"的结构**
 *   rca 用 depgraph 顶替 —— 那是依赖关系，**不是因果链**
 *   drift 用 distcmp 顶替 —— 只比形状，**不给"该不该重训"的判据**
 *
 * 十二个组件分成三组：
 *
 *   **执行**（决定之后，正在发生）
 *     zx_rollout     分批放量，每批带健康度闸门
 *     zx_canary      新旧并跑对比，逐指标判胜负
 *     zx_slo         错误预算燃尽，预算没了就停手
 *     zx_healthcheck 多项检查的状态网格
 *
 *   **复盘**（出了问题，为什么）
 *     zx_incident    事故时间线：发现/定位/止损/恢复四段
 *     zx_rca         5Why 根因链，一层层往下问
 *     zx_regret      选了 A，看看选 B 会怎样
 *     zx_backtest    策略在历史上的真实表现
 *
 *   **闭环**（长期看，是不是还成立）
 *     zx_drift       分布漂移，给"该不该重训"的判据
 *     zx_alert       告警聚合 + 处置动作
 *     zx_costtrack   预算 vs 实际 + 会不会超
 *     zx_feedback    预测 vs 实际，回填偏差
 *
 * 贯穿这批的一条：**每个组件都要回答"然后呢"**。
 *
 *   rollout 不给"能不能继续放量"，用户只能自己看数字猜
 *   slo 不给"预算还剩多少"，用户不知道该不该停手
 *   drift 不给"该不该重训"，用户只能自己定阈值
 *   alert 不给处置动作，用户看完不知道下一步是什么
 *
 *   **只有状态没有动作的监控，等于让人盯着屏幕。**
 *
 * 校验重心也跟着换了第六档。前五档拦的是
 *   "画不出来" → "统计前提不成立" → "区间前提不成立" →
 *   "因果前提无从判断" → "看起来确定了其实没那么确定"，
 * 这批拦的是
 *   **"状态摆出来了，但推不出该做什么"**：
 *
 *   rollout 批次比例未升序（放量倒着走，不是灰度）
 *   slo 目标不在 0-1 / 已用预算超过 100% 却标健康
 *   healthcheck 检查项状态值非法（会被静默当通过）
 *   incident 四段缺一段（说不清从发现到恢复多久）
 *   rca 链断了（只问了 2 层就说找到根因）
 *   regret 反事实结果缺失（无从比较）
 *   backtest 没有基准（跑得好也可能不如不跑）
 *   drift 两列长度不等 / 没给阈值（判据无从判断）
 *   alert 没有动作（看完不知道下一步）
 *   costtrack 预算 ≤0 / 时间点未升序
 *   feedback 预测值和实际值数量不等（对不上，偏差算错）
 */
object ZxRender28 {

    private val PAL = listOf(
        Color(0xFF5046E4), Color(0xFF3FBF7F), Color(0xFFE8A33D),
        Color(0xFFE5544B), Color(0xFF4A9BE8), Color(0xFF8E86FF)
    )
    private val STC = mapOf(
        "ok" to Color(0xFF3FBF7F), "warn" to Color(0xFFE8A33D),
        "err" to Color(0xFFE5544B), "idle" to Color(0xFF6B7280),
        "run" to Color(0xFF4A9BE8)
    )
    private fun sc(s: String) = STC[s] ?: ZxT.Text3

    /* ══════════ zx_rollout 灰度发布 ══════════ */
    @Composable
    fun rollout(o: JSONObject, ctx: RenderContext) {
        val stA = o.optJSONArray("stages") ?: return
        val n = minOf(stA.length(), 6)
        val st = (0 until n).map {
            val s = stA.optJSONObject(it) ?: JSONObject()
            s.optInt("p", 0) to s.optString("s", "idle")
        }
        val cur = st.indexOfLast { it.second == "run" }.let { if (it < 0) st.indexOfLast { it.second == "ok" } else it }
        var sel by remember { mutableIntStateOf(-1) }
        val blocked = st.getOrNull(cur)?.second == "err"
        val nextP = st.getOrNull(cur + 1)?.first ?: 100
        val mx = st.maxOf { it.first }.coerceAtLeast(1)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "灰度发布"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (blocked) "已阻断" else "可继续", fontSize = 9.sp,
                     color = if (blocked) ZxT.Err else ZxT.Ok)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().height(14.dp)
                .clip(RoundedCornerShape(7.dp)).background(ZxT.Surface2)) {
                st.forEachIndexed { k, (p, _) ->
                    Box(Modifier.weight((p.toDouble() / 100 * n).toFloat().coerceAtLeast(0.02f))
                        .fillMaxHeight()
                        .background(if (k <= cur) sc(st[k].second) else ZxT.Surface3))
                }
                Box(Modifier.weight(((100 - st.last().first) / 100.0 * n).toFloat().coerceAtLeast(0.01f))
                    .fillMaxHeight())
            }
            Spacer(Modifier.height(4.dp))
            st.forEachIndexed { k, (p, s) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (sel == k) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_select", mapOf(
                            "stage" to (k + 1).toString(), "pct" to p.toString(),
                            "state" to s, "source" to "zx_rollout"))
                    }.padding(5.dp, 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(RoundedCornerShape(1.dp)).background(sc(s)))
                    Text("第 ${k + 1} 批", fontSize = 9.sp, color = ZxT.Text2,
                         modifier = Modifier.weight(1f).padding(start = 5.dp))
                    Text("$p%", fontSize = 8.5.sp, color = ZxT.Text2,
                         fontFamily = ZxT.Mono)
                    Text(when (s) { "ok" -> "通过"; "run" -> "进行中"; "err" -> "异常"
                                    "warn" -> "有波动"; else -> "未开始" },
                        fontSize = 8.sp, color = sc(s),
                        modifier = Modifier.width(42.dp).padding(start = 5.dp),
                        textAlign = TextAlign.End)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (blocked) ZxT.Err.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (blocked) "✗" else "✓", fontSize = 11.sp,
                     color = if (blocked) ZxT.Err else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (blocked) "当前批次异常——**不要继续放量**，先回滚或修复"
                         else "可继续放量到 ${nextP}%",
                        fontSize = 9.sp, color = if (blocked) ZxT.Err else ZxT.Ok)
                    Text("每批都有健康度闸门；异常就停在这里，而不是靠人看着数字决定",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 progress 只给<b>一个百分比</b>、stepper 走<b>已定好的流程</b>，" +
                 "这个处理「<b>分批放量 + 每批闸门</b>」。" +
                 "关键是<b>要回答能不能继续</b>——只给百分比，" +
                 "用户只能自己看健康度数字猜该不该往下放。" +
                 "<b>批次比例未升序会被拦</b>：那不是灰度，是来回横跳。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_canary 金丝雀对比 ══════════ */
    @Composable
    fun canary(o: JSONObject, ctx: RenderContext) {
        val mA = o.optJSONArray("metrics") ?: return
        val n = minOf(mA.length(), 8)
        val ms = (0 until n).map {
            val m = mA.optJSONObject(it) ?: JSONObject()
            Triple(m.optString("n", "指标${it + 1}"),
                   m.optDouble("a", 0.0), m.optDouble("b", 0.0))
        }
        var sel by remember { mutableIntStateOf(-1) }
        val tol = o.optDouble("tol", 0.02)
        val worse = ms.count { (_, a, b) -> abs(b - a) > tol * abs(a).coerceAtLeast(1.0) && b > a }
        val better = ms.count { (_, a, b) -> abs(b - a) > tol * abs(a).coerceAtLeast(1.0) && b < a }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "金丝雀对比"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (worse > 0) "$worse 项变差" else "无恶化", fontSize = 9.sp,
                     color = if (worse > 0) ZxT.Err else ZxT.Ok)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
                Text("指标", fontSize = 8.sp, color = ZxT.Text3, modifier = Modifier.weight(1f))
                Text("基线", fontSize = 8.sp, color = ZxT.Text3,
                     modifier = Modifier.width(40.dp), textAlign = TextAlign.End)
                Text("新版本", fontSize = 8.sp, color = ZxT.Text3,
                     modifier = Modifier.width(44.dp), textAlign = TextAlign.End)
                Text("判定", fontSize = 8.sp, color = ZxT.Text3,
                     modifier = Modifier.width(30.dp), textAlign = TextAlign.End)
            }
            ms.forEachIndexed { k, (nm, a, b) ->
                val d = b - a
                val rel = d / abs(a).coerceAtLeast(1e-9)
                val flat = abs(rel) <= tol
                val c = if (flat) ZxT.Text3 else if (rel > 0) ZxT.Err else ZxT.Ok
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (sel == k) ZxT.Surface2
                                else if (!flat && rel > 0) ZxT.Err.copy(.07f) else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_select", mapOf(
                            "metric" to nm, "base" to "%.4f".format(a), "canary" to "%.4f".format(b),
                            "delta" to "%+.2f%%".format(rel * 100), "source" to "zx_canary"))
                    }.padding(5.dp, 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(nm, fontSize = 9.sp, color = ZxT.Text2,
                         modifier = Modifier.weight(1f), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    Text("%.3f".format(a), fontSize = 8.5.sp, color = ZxT.Text3,
                         modifier = Modifier.width(40.dp), textAlign = TextAlign.End,
                         fontFamily = ZxT.Mono)
                    Text("%.3f".format(b), fontSize = 8.5.sp, color = ZxT.Text2,
                         modifier = Modifier.width(44.dp), textAlign = TextAlign.End,
                         fontFamily = ZxT.Mono)
                    Text(if (flat) "持平" else "%+.1f%%".format(rel * 100), fontSize = 8.sp,
                         color = c, modifier = Modifier.width(30.dp), textAlign = TextAlign.End)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (worse > 0) ZxT.Err.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (worse > 0) "✗" else "✓", fontSize = 11.sp,
                     color = if (worse > 0) ZxT.Err else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (worse > 0) "$worse 项超出容差 ${"%.1f".format(tol * 100)}%——**先别放量**"
                         else "没有指标恶化${if (better > 0) "（$better 项变好）" else ""}，可以继续",
                        fontSize = 9.sp, color = if (worse > 0) ZxT.Err else ZxT.Ok)
                    Text("逐指标判胜负，而不是只看一个总指标——总指标会掩盖局部恶化",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 compare 比<b>两个方案</b>、zx_diff 比<b>两版文件</b>，" +
                 "这个比的是<b>同一时刻新旧两个版本在跑</b>。" +
                 "关键是<b>逐指标判</b>：只看一个总指标，" +
                 "「转化率持平」会掩盖「响应变慢 40%」。" +
                 "<b>两列长度不等会被拦</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_slo 错误预算 ══════════ */
    @Composable
    fun slo(o: JSONObject, ctx: RenderContext) {
        val target = o.optDouble("target", 0.999)
        val used = o.optDouble("used", 0.0)
        val daysA = o.optJSONArray("days") ?: return
        val n = minOf(daysA.length(), 30)
        val days = (0 until n).map { daysA.optDouble(it, 0.0) }
        var sel by remember { mutableIntStateOf(-1) }
        val budget = (1.0 - target) * 100     // 百分比
        val burn = used * 100
        val left = budget - burn
        val exhausted = left <= 0
        // 按最近消耗速率推算还能撑几天
        val recent = days.takeLast(7).average()
        val eta = if (recent > 1e-9 && !exhausted) (left / recent) else Double.NaN

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "错误预算"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (exhausted) "预算耗尽" else "剩 ${"%.2f".format(left)}%",
                     fontSize = 9.sp, color = if (exhausted) ZxT.Err
                     else if (left < budget * 0.3) ZxT.Warn else ZxT.Ok)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(12.dp)
                .clip(RoundedCornerShape(6.dp)).background(ZxT.Surface2)) {
                Box(Modifier.fillMaxWidth((burn / budget).toFloat().coerceIn(0.01f, 1f))
                    .fillMaxHeight().background(if (exhausted) ZxT.Err
                        else if (burn / budget > 0.7) ZxT.Warn else PAL[0]))
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Text("已用 ${"%.2f".format(burn)}% / 预算 ${"%.2f".format(budget)}%",
                    fontSize = 8.sp, color = ZxT.Text3, modifier = Modifier.weight(1f))
                Text("SLO ${"%.2f".format(target * 100)}%", fontSize = 8.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().height(52.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width; val hh = size.height
                    val mx = days.maxOrNull()?.coerceAtLeast(1e-9) ?: 1.0
                    val bw = w / n
                    days.forEachIndexed { k, v ->
                        val h = (v / mx * (hh - 4f)).toFloat().coerceAtLeast(1f)
                        drawRect(if (k == sel) ZxT.Text else if (v > mx * 0.6) ZxT.Err else PAL[0].copy(.65f),
                                 Offset(k * bw, hh - h), Size(bw * 0.7f, h))
                    }
                }
                Row(Modifier.fillMaxWidth().height(52.dp)) {
                    repeat(n) { k ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = k
                            ctx.callTool("zx_select", mapOf(
                                "day" to (k + 1).toString(), "burn" to "%.4f".format(days[k]),
                                "source" to "zx_slo"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (exhausted) ZxT.Err.copy(.10f)
                            else if (left < budget * 0.3) ZxT.Warn.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (exhausted) "✗" else if (left < budget * 0.3) "!" else "✓",
                     fontSize = 11.sp, color = if (exhausted) ZxT.Err
                     else if (left < budget * 0.3) ZxT.Warn else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (exhausted) "预算已耗尽——**停止发布新功能**，把稳定性放第一位"
                         else if (!eta.isNaN()) "按最近 7 天速率，还能撑约 ${"%.0f".format(eta)} 天"
                         else "消耗很慢，看不出什么时候会耗尽",
                        fontSize = 9.sp, color = if (exhausted) ZxT.Err
                        else if (left < budget * 0.3) ZxT.Warn else ZxT.Ok)
                    Text("错误预算不是监控指标，是**发布闸门**：没预算就不发",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 meter 给<b>一个量</b>、budget 给<b>分档条</b>，" +
                 "这个算的是「<b>SLO 允许的失败额度还剩多少</b>」。" +
                 "关键是<b>要回答该不该停手</b>——只给当前成功率，" +
                 "用户不知道「还能错几次」。" +
                 "<b>目标不在 0-1 会被拦</b>；已用超过 100% 必须标耗尽。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_healthcheck 健康检查矩阵 ══════════ */
    @Composable
    fun healthcheck(o: JSONObject, ctx: RenderContext) {
        val itemsA = o.optJSONArray("items") ?: return
        val n = minOf(itemsA.length(), 16)
        val items = (0 until n).map { i ->
            val it = itemsA.optJSONObject(i) ?: JSONObject()
            Triple(it.optString("n", "检查${i + 1}"), it.optString("s", "idle"),
                   it.optString("d", ""))
        }
        var sel by remember { mutableIntStateOf(-1) }
        val cols = if (n > 8) 4 else 3
        val bad = items.count { it.second == "err" }
        val warn = items.count { it.second == "warn" }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "健康检查"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (bad > 0) "$bad 项异常" else if (warn > 0) "$warn 项警告" else "全部通过",
                     fontSize = 9.sp, color = if (bad > 0) ZxT.Err
                     else if (warn > 0) ZxT.Warn else ZxT.Ok)
            }
            Spacer(Modifier.height(6.dp))
            val rows = (n + cols - 1) / cols
            (0 until rows).forEach { r ->
                Row(Modifier.fillMaxWidth()) {
                    (0 until cols).forEach { c ->
                        val i = r * cols + c
                        if (i < n) {
                            val (nm, s, d) = items[i]
                            Box(Modifier.weight(1f).padding(1.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(if (sel == i) ZxT.Surface2
                                            else sc(s).copy(.09f))
                                .clickable {
                                    sel = i
                                    ctx.callTool("zx_select", mapOf(
                                        "item" to nm, "state" to s, "detail" to d,
                                        "source" to "zx_healthcheck"))
                                }.padding(5.dp, 4.dp)) {
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(6.dp).clip(RoundedCornerShape(1.dp))
                                            .background(sc(s)))
                                        Spacer(Modifier.width(3.dp))
                                        Text(nm, fontSize = 8.5.sp, color = ZxT.Text2,
                                             modifier = Modifier.weight(1f), maxLines = 1,
                                             overflow = TextOverflow.Ellipsis)
                                    }
                                    if (d.isNotBlank())
                                        Text(d, fontSize = 7.5.sp, color = ZxT.Text3,
                                             maxLines = 1, overflow = TextOverflow.Ellipsis,
                                             modifier = Modifier.padding(top = 1.dp))
                                }
                            }
                        } else Box(Modifier.weight(1f).padding(1.dp))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (bad > 0) ZxT.Err.copy(.10f)
                            else if (warn > 0) ZxT.Warn.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (bad > 0) "✗" else if (warn > 0) "!" else "✓", fontSize = 11.sp,
                     color = if (bad > 0) ZxT.Err else if (warn > 0) ZxT.Warn else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (bad > 0) "$bad 项异常——先看异常项，通过的不用管"
                         else "没有异常项", fontSize = 9.sp,
                         color = if (bad > 0) ZxT.Err else ZxT.Ok)
                    Text("网格化是为了**一眼扫完**，不是逐行读——16 项用列表要滚很久",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 checklist 是<b>要勾的清单</b>、statusgrid 是<b>状态格</b>，" +
                 "这个把<b>若干检查项压成一屏</b>。" +
                 "关键是<b>异常项要能立刻看出来</b>——16 项里 2 项异常，" +
                 "用列表得逐行找。<b>状态值非法会被拦</b>：" +
                 "拼错会被静默当通过，那是最糟的错法。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_incident 事故时间线 ══════════ */
    @Composable
    fun incident(o: JSONObject, ctx: RenderContext) {
        val evA = o.optJSONArray("ev") ?: return
        val n = minOf(evA.length(), 10)
        val ev = (0 until n).map {
            val e = evA.optJSONObject(it) ?: JSONObject()
            Triple(e.optInt("t", 0), e.optString("k", "other"), e.optString("d", ""))
        }
        var sel by remember { mutableIntStateOf(-1) }
        val kind = mapOf("find" to ("发现" to ZxT.Err), "diag" to ("定位" to ZxT.Warn),
                         "fix" to ("止损" to PAL[0]), "rec" to ("恢复" to ZxT.Ok),
                         "other" to ("其他" to ZxT.Text3))
        val tFind = ev.firstOrNull { it.second == "find" }?.first
        val tRec = ev.firstOrNull { it.second == "rec" }?.first
        val mttr = if (tFind != null && tRec != null && tRec >= tFind) tRec - tFind else null
        val t0 = ev.firstOrNull()?.first ?: 0
        val t1 = ev.lastOrNull()?.first ?: 1
        val sp = (t1 - t0).coerceAtLeast(1)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "事故时间线"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (mttr != null) "MTTR ${mttr} 分钟" else "缺关键节点",
                     fontSize = 9.sp, color = if (mttr != null) ZxT.Warn else ZxT.Err)
            }
            Spacer(Modifier.height(6.dp))
            ev.forEachIndexed { k, (t, kk, d) ->
                val (lb, col) = kind[kk] ?: ("其他" to ZxT.Text3)
                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (sel == k) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_select", mapOf(
                            "t" to t.toString(), "kind" to kk, "desc" to d,
                            "source" to "zx_incident"))
                    }.padding(5.dp, 2.dp), verticalAlignment = Alignment.Top) {
                    Text("${t}′", fontSize = 8.sp, color = ZxT.Text3,
                         modifier = Modifier.width(30.dp), fontFamily = ZxT.Mono)
                    Box(Modifier.width(8.dp).padding(top = 3.dp).height(8.dp)
                        .clip(RoundedCornerShape(4.dp)).background(col))
                    Column(Modifier.weight(1f).padding(start = 5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(lb, fontSize = 8.sp, color = col,
                                 modifier = Modifier.width(26.dp))
                            Text(d, fontSize = 9.sp, color = if (sel == k) ZxT.Text else ZxT.Text2,
                                 modifier = Modifier.weight(1f), maxLines = 2,
                                 overflow = TextOverflow.Ellipsis)
                        }
                        // 段间时长：没有它说不清哪一段最久
                        if (k > 0) {
                            val dt = t - ev[k - 1].first
                            if (dt > 0)
                                Text("↑ 距上一步 $dt 分钟", fontSize = 7.5.sp, color = ZxT.Text3)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (mttr != null) ZxT.Warn.copy(.10f) else ZxT.Err.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (mttr != null) "!" else "✗", fontSize = 11.sp,
                     color = if (mttr != null) ZxT.Warn else ZxT.Err)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (mttr != null) "从发现到恢复 ${mttr} 分钟。看「距上一步」最长的那一段——" +
                            "瓶颈通常在那儿，而不是总时长"
                         else "缺少「发现」或「恢复」节点——**算不出 MTTR**，也就无从改进",
                        fontSize = 9.sp, color = if (mttr != null) ZxT.Warn else ZxT.Err)
                    Text("事故复盘要的是「哪一段最久」，不是「总共多久」",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 timeline 只是<b>一串时间点</b>、zx_inc 这类状态卡只看<b>当前</b>，" +
                 "这个按<b>发现/定位/止损/恢复</b>分段。" +
                 "关键是<b>段间时长</b>——只知道「总共 90 分钟」，" +
                 "不知道是「定位花了 60 分钟」还是「止损花了 60 分钟」，" +
                 "而这两件事的改进方向完全不同。" +
                 "<b>缺发现或恢复节点会被拦</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_rca 根因溯源 ══════════ */
    @Composable
    fun rca(o: JSONObject, ctx: RenderContext) {
        val whyA = o.optJSONArray("why") ?: return
        val n = minOf(whyA.length(), 6)
        val why = (0 until n).map {
            val w = whyA.optJSONObject(it) ?: JSONObject()
            w.optString("q", "为什么？") to w.optString("a", "")
        }
        var sel by remember { mutableIntStateOf(-1) }
        val depth = why.count { it.second.isNotBlank() }
        val root = why.lastOrNull { it.second.isNotBlank() }?.second ?: ""

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "根因溯源"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (depth >= 3) "追了 $depth 层" else "只追了 $depth 层",
                     fontSize = 9.sp, color = if (depth >= 3) ZxT.Ok else ZxT.Warn)
            }
            Spacer(Modifier.height(6.dp))
            why.forEachIndexed { k, (q, a) ->
                Row(Modifier.fillMaxWidth()) {
                    // 缩进阶梯：看得出来在下钻
                    Box(Modifier.width((k * 8).dp))
                    Column(Modifier.weight(1f)) {
                        Row(Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (sel == k) ZxT.Surface2 else Color.Transparent)
                            .clickable {
                                sel = k
                                ctx.callTool("zx_select", mapOf(
                                    "level" to (k + 1).toString(), "why" to q, "because" to a,
                                    "source" to "zx_rca"))
                            }.padding(5.dp, 3.dp), verticalAlignment = Alignment.Top) {
                            Text("${k + 1}", fontSize = 8.sp, color = PAL[0],
                                 modifier = Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(q, fontSize = 9.sp, color = ZxT.Text2)
                                if (a.isNotBlank())
                                    Text("→ $a", fontSize = 9.sp,
                                         color = if (k == depth - 1) ZxT.Err else ZxT.Text,
                                         modifier = Modifier.padding(top = 1.dp))
                                else
                                    Text("→ （还没回答）", fontSize = 8.5.sp, color = ZxT.Text3,
                                         modifier = Modifier.padding(top = 1.dp))
                            }
                        }
                        if (k < n - 1)
                            Box(Modifier.padding(start = 12.dp).width(1.dp).height(4.dp)
                                .background(ZxT.Surface3))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (depth >= 3) ZxT.Ok.copy(.10f) else ZxT.Warn.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (depth >= 3) "✓" else "!", fontSize = 11.sp,
                     color = if (depth >= 3) ZxT.Ok else ZxT.Warn)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (depth >= 3) "根因：${root.take(40)}"
                         else "只追到 $depth 层——**通常停在第一层的原因是流程问题**，再往下问两层",
                        fontSize = 9.sp, color = if (depth >= 3) ZxT.Ok else ZxT.Warn)
                    Text("5Why 的价值在于**不停在表象**：改表象会复发，改根因才不会",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 depgraph 画<b>依赖关系</b>、causal 画<b>因果图</b>，" +
                 "这两个都是<b>已知结构</b>；这个是从<b>现象往下问</b>。" +
                 "区别很重要：事故刚发生时没人知道结构，" +
                 "只能一层层问。<b>只问了 1-2 层就说找到根因会被拦</b>——" +
                 "停在表象的修复会复发。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_regret 遗憾分析 ══════════ */
    @Composable
    fun regret(o: JSONObject, ctx: RenderContext) {
        val chosen = o.optString("chosen", "")
        val actual = o.optDouble("actual", Double.NaN)
        val altsA = o.optJSONArray("alts") ?: return
        val n = minOf(altsA.length(), 5)
        val alts = (0 until n).map {
            val a = altsA.optJSONObject(it) ?: JSONObject()
            Triple(a.optString("n", "方案${it + 1}"), a.optDouble("v", 0.0),
                   a.optDouble("p", 0.0))
        }
        var sel by remember { mutableIntStateOf(-1) }
        val bestAlt = alts.maxByOrNull { it.second }
        val regret = if (!actual.isNaN()) bestAlt?.second?.minus(actual) else null
        val expVal = alts.sumOf { it.second * it.third }
        val sorted = alts.sortedByDescending { it.second }
        val mx = sorted.firstOrNull()?.second?.coerceAtLeast(1e-9) ?: 1.0
        val mn = sorted.lastOrNull()?.second ?: 0.0
        val sp = (mx - mn).coerceAtLeast(1e-9)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "遗憾分析"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (regret != null && regret > 1e-9) "遗憾 ${"%.2f".format(regret)}"
                     else if (regret != null) "选对了" else "缺实际值",
                     fontSize = 9.sp, color = if (regret == null) ZxT.Err
                     else if (regret > 1e-9) ZxT.Warn else ZxT.Ok)
            }
            Spacer(Modifier.height(6.dp))
            if (chosen.isNotBlank())
                Text("当时选了「$chosen」，实际得到 ${if (actual.isNaN()) "（未回填）" else "%.2f".format(actual)}",
                    fontSize = 9.sp, color = ZxT.Text2, modifier = Modifier.padding(bottom = 4.dp))
            sorted.forEachIndexed { k, (nm, v, p) ->
                val isChosen = nm == chosen
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (isChosen) PAL[0].copy(.12f)
                                else if (sel == k) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_select", mapOf(
                            "alt" to nm, "cf" to "%.3f".format(v), "prob" to "%.2f".format(p),
                            "chosen" to (if (isChosen) "yes" else "no"), "source" to "zx_regret"))
                    }.padding(5.dp, 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (isChosen) "●" else "○", fontSize = 8.sp,
                         color = if (isChosen) ZxT.PriLite else ZxT.Text3,
                         modifier = Modifier.width(10.dp))
                    Text(nm, fontSize = 9.sp, color = if (isChosen) ZxT.Text else ZxT.Text2,
                         modifier = Modifier.width(52.dp), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    Box(Modifier.weight(1f).height(8.dp)
                        .clip(RoundedCornerShape(4.dp)).background(ZxT.Surface2)) {
                        Box(Modifier.fillMaxWidth(((v - mn) / sp).toFloat().coerceIn(0.02f, 1f))
                            .fillMaxHeight().background(if (isChosen) PAL[0] else ZxT.Text3.copy(.6f)))
                    }
                    Text("%.2f".format(v), fontSize = 8.5.sp, color = ZxT.Text2,
                         modifier = Modifier.width(30.dp), textAlign = TextAlign.End,
                         fontFamily = ZxT.Mono)
                    Text(if (p > 0) "${"%.0f".format(p * 100)}%" else "—", fontSize = 8.sp,
                         color = ZxT.Text3, modifier = Modifier.width(26.dp),
                         textAlign = TextAlign.End)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (regret == null) ZxT.Err.copy(.10f)
                            else if (regret > 1e-9) ZxT.Warn.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (regret == null) "✗" else if (regret > 1e-9) "!" else "✓",
                     fontSize = 11.sp, color = if (regret == null) ZxT.Err
                     else if (regret > 1e-9) ZxT.Warn else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (regret == null) "没有回填实际值——**无从判断选得对不对**，" +
                            "这个分析是不完整的"
                         else if (regret > 1e-9) "选了「$chosen」得到 ${"%.2f".format(actual)}，" +
                            "但「${bestAlt?.first}」本可以给 ${"%.2f".format(bestAlt?.second ?: 0.0)}——" +
                            "差 ${"%.2f".format(regret)}"
                         else "选的正是事后看最好的那个",
                        fontSize = 9.sp, color = if (regret == null) ZxT.Err
                        else if (regret > 1e-9) ZxT.Warn else ZxT.Ok)
                    Text("事前期望值 ${"%.2f".format(expVal)}（按当时概率加权）——" +
                        "**事前最优 ≠ 事后最优**，这两件事要分开看",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 decision 在<b>做选择之前</b>、optline 看<b>选的那一刻</b>，" +
                 "这个是<b>做完了回头看</b>。" +
                 "关键是把<b>事前最优和事后最优分开</b>：" +
                 "当时按概率算该选 A，结果 B 更好——" +
                 "那不叫选错，那叫运气；只有事前就算错才叫选错。" +
                 "<b>缺实际值会被拦</b>：没有回填就没有遗憾可言。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_backtest 回测 ══════════ */
    @Composable
    fun backtest(o: JSONObject, ctx: RenderContext) {
        val eqA = o.optJSONArray("eq") ?: return
        val n = minOf(eqA.length(), 60)
        val eq = (0 until n).map { eqA.optDouble(it, 0.0) }
        var showBench by remember { mutableStateOf(true) }
        var sel by remember { mutableIntStateOf(-1) }
        val bmA = o.optJSONArray("bench")
        val bm = if (bmA != null) (0 until n).map { bmA.optDouble(it, 0.0) } else null
        val tot = (eq.lastOrNull() ?: 0.0) - (eq.firstOrNull() ?: 0.0)
        val bmTot = bm?.let { (it.lastOrNull() ?: 0.0) - (it.firstOrNull() ?: 0.0) }
        // 最大回撤
        var peak = -Double.MAX_VALUE; var mdd = 0.0
        eq.forEach { v -> if (v > peak) peak = v; mdd = maxOf(mdd, peak - v) }
        val lo = minOf(eq.minOrNull() ?: 0.0, bm?.minOrNull() ?: Double.MAX_VALUE)
        val hi = maxOf(eq.maxOrNull() ?: 1.0, bm?.maxOrNull() ?: -Double.MAX_VALUE)
        val sp = (hi - lo).coerceAtLeast(1e-9)
        val W = 330f; val H = 84f; val pad = 8f
        val px = { k: Int -> pad + (k.toDouble() / (n - 1).coerceAtLeast(1) * (W - 2 * pad)).toFloat() }
        val py = { v: Double -> H - pad - ((v - lo) / sp * (H - 2 * pad)).toFloat() }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "策略回测"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (bmTot != null && tot > bmTot) "跑赢基准" else if (bmTot != null) "没跑赢" else "无基准",
                     fontSize = 9.sp, color = if (bmTot == null) ZxT.Warn
                     else if (tot > bmTot) ZxT.Ok else ZxT.Err)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(84.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    if (showBench && bm != null)
                        drawPath(Path().apply {
                            bm.forEachIndexed { k, v ->
                                val x = px(k); val y = py(v)
                                if (k == 0) moveTo(x, y) else lineTo(x, y)
                            }
                        }, ZxT.Text3, style = Stroke(1.2f))
                    drawPath(Path().apply {
                        eq.forEachIndexed { k, v ->
                            val x = px(k); val y = py(v)
                            if (k == 0) moveTo(x, y) else lineTo(x, y)
                        }
                    }, if (tot >= 0) ZxT.Ok else ZxT.Err, style = Stroke(1.7f))
                    if (sel >= 0)
                        drawCircle(Color.White, 3.2f, Offset(px(sel), py(eq[sel])))
                }
                Row(Modifier.fillMaxWidth().height(84.dp)) {
                    repeat(n) { k ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = k
                            ctx.callTool("zx_select", mapOf(
                                "i" to k.toString(), "eq" to "%.3f".format(eq[k]),
                                "bench" to (bm?.let { "%.3f".format(it[k]) } ?: "NA"),
                                "source" to "zx_backtest"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                listOf("收益" to "%+.2f".format(tot),
                       "最大回撤" to "-%.2f".format(mdd),
                       "基准" to (bmTot?.let { "%+.2f".format(it) } ?: "—")).forEach { (k, v) ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(v, fontSize = 9.5.sp, color = ZxT.Text, fontFamily = ZxT.Mono)
                        Text(k, fontSize = 7.5.sp, color = ZxT.Text3)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(if (bm != null) "灰线=基准（不操作）" else "没有基准", fontSize = 8.sp,
                     color = ZxT.Text3, modifier = Modifier.weight(1f))
                if (bm != null)
                    Text(if (showBench) "隐藏基准" else "显示基准", fontSize = 8.sp,
                         color = if (showBench) ZxT.PriLite else ZxT.Text3,
                         modifier = Modifier.clip(RoundedCornerShape(7.dp))
                             .background(if (showBench) ZxT.Surface2 else Color.Transparent)
                             .clickable { showBench = !showBench }.padding(7.dp, 1.dp))
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (bm == null) ZxT.Warn.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (bm == null) "!" else "✓", fontSize = 11.sp,
                     color = if (bm == null) ZxT.Warn else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (bm == null) "没有基准——**赚了也可能不如什么都不做**，这个回测说明不了问题"
                         else "收益 ${"%+.2f".format(tot)} vs 基准 ${"%+.2f".format(bmTot!!)}" +
                            "，最大回撤 ${"%.2f".format(mdd)}",
                        fontSize = 9.sp, color = if (bm == null) ZxT.Warn else ZxT.Ok)
                    Text("回撤比收益更重要：**赚 20% 但中间亏过 35%，大多数人拿不住**",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 line 画<b>一条序列</b>、zx_chart 画<b>多组对比</b>，" +
                 "这个算的是「<b>这个策略历史上真的有效吗</b>」。" +
                 "关键是<b>必须有基准</b>：涨了 20% 看着不错，" +
                 "但同期大盘涨了 30%，这个策略实际是跑输的。" +
                 "<b>没有基准会被拦</b>——不是不能画，是画了说明不了问题。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_drift 数据漂移 ══════════ */
    @Composable
    fun drift(o: JSONObject, ctx: RenderContext) {
        val colsA = o.optJSONArray("cols") ?: return
        val n = minOf(colsA.length(), 8)
        val cols = (0 until n).map {
            val c = colsA.optJSONObject(it) ?: JSONObject()
            Triple(c.optString("n", "特征${it + 1}"), c.optDouble("psi", 0.0),
                   c.optString("act", ""))
        }.sortedByDescending { it.second }
        var sel by remember { mutableIntStateOf(-1) }
        val warnAt = o.optDouble("warn", 0.1)
        val retrainAt = o.optDouble("retrain", 0.25)
        val mx = cols.maxOf { it.second }.coerceAtLeast(retrainAt)
        val bad = cols.count { it.second >= retrainAt }
        val warnN = cols.count { it.second >= warnAt && it.second < retrainAt }
        val top = cols.firstOrNull()

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "数据漂移"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (bad > 0) "$bad 项需重训" else if (warnN > 0) "$warnN 项预警" else "无漂移",
                     fontSize = 9.sp, color = if (bad > 0) ZxT.Err
                     else if (warnN > 0) ZxT.Warn else ZxT.Ok)
            }
            Spacer(Modifier.height(6.dp))
            cols.forEachIndexed { k, (nm, psi, act) ->
                val c = if (psi >= retrainAt) ZxT.Err else if (psi >= warnAt) ZxT.Warn else ZxT.Ok
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (sel == k) ZxT.Surface2
                                else if (psi >= warnAt) c.copy(.07f) else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_select", mapOf(
                            "col" to nm, "psi" to "%.4f".format(psi),
                            "action" to act, "need_retrain" to (if (psi >= retrainAt) "yes" else "no"),
                            "source" to "zx_drift"))
                    }.padding(5.dp, 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(nm, fontSize = 9.sp, color = ZxT.Text2,
                         modifier = Modifier.width(56.dp), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    Box(Modifier.weight(1f).height(8.dp)
                        .clip(RoundedCornerShape(4.dp)).background(ZxT.Surface2)) {
                        Box(Modifier.fillMaxWidth((psi / mx).toFloat().coerceIn(0.02f, 1f))
                            .fillMaxHeight().background(c))
                        // 两条阈值刻度：没有它看不出"多少算严重"
                        Box(Modifier.fillMaxWidth((warnAt / mx).toFloat().coerceIn(0f, 1f))
                            .fillMaxHeight())
                    }
                    Text("%.3f".format(psi), fontSize = 8.5.sp, color = c,
                         modifier = Modifier.width(32.dp), textAlign = TextAlign.End,
                         fontFamily = ZxT.Mono)
                    Text(if (psi >= retrainAt) "重训" else if (psi >= warnAt) "观察" else "正常",
                        fontSize = 8.sp, color = c,
                        modifier = Modifier.width(28.dp), textAlign = TextAlign.End)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                Text("阈值：≥${"%.2f".format(warnAt)} 观察　≥${"%.2f".format(retrainAt)} 重训",
                    fontSize = 7.5.sp, color = ZxT.Text3, modifier = Modifier.weight(1f))
                Text("PSI 越大漂得越远", fontSize = 7.5.sp, color = ZxT.Text3)
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (bad > 0) ZxT.Err.copy(.10f)
                            else if (warnN > 0) ZxT.Warn.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (bad > 0) "✗" else if (warnN > 0) "!" else "✓", fontSize = 11.sp,
                     color = if (bad > 0) ZxT.Err else if (warnN > 0) ZxT.Warn else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (bad > 0) "漂得最厉害的是「${top?.first}」PSI=${"%.3f".format(top?.second ?: 0.0)}" +
                            "——**该重训了**：${if (top?.third.isNullOrBlank()) act(top) else top?.third}"
                         else if (warnN > 0) "$warnN 项到了观察线，还没到重训线——继续盯着"
                         else "各特征都在观察线以下，模型还能用",
                        fontSize = 9.sp, color = if (bad > 0) ZxT.Err
                        else if (warnN > 0) ZxT.Warn else ZxT.Ok)
                    Text("漂移本身不是问题，**漂移了还不知道才是问题**——所以必须给阈值",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 distcmp 比<b>两个分布的形状</b>、histogram 看<b>单个分布</b>，" +
                 "这个回答「<b>线上数据和训练数据是不是已经不一样了</b>」。" +
                 "关键是<b>要给阈值</b>：只说「PSI=0.31」，" +
                 "用户不知道这算严重还是正常。<b>不给阈值会被拦</b>——" +
                 "没有判据的监控等于让人盯着屏幕。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    private fun act(top: Triple<String, Double, String>?) = "优先排查这个特征的上游"

    /* ══════════ zx_alert 告警处置 ══════════ */
    @Composable
    fun alert(o: JSONObject, ctx: RenderContext) {
        val alA = o.optJSONArray("alerts") ?: return
        val n = minOf(alA.length(), 5)
        val al = (0 until n).map {
            val a = alA.optJSONObject(it) ?: JSONObject()
            Triple(a.optString("n", "告警${it + 1}"), a.optString("lv", "warn"),
                   a.optString("act", ""))
        }
        var sel by remember { mutableIntStateOf(-1) }
        val crit = al.count { it.second == "crit" }
        val noAct = al.count { it.third.isBlank() }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "告警"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (crit > 0) "$crit 条紧急" else "无紧急", fontSize = 9.sp,
                     color = if (crit > 0) ZxT.Err else ZxT.Ok)
            }
            Spacer(Modifier.height(6.dp))
            al.forEachIndexed { k, (nm, lv, a) ->
                val c = if (lv == "crit") ZxT.Err else if (lv == "warn") ZxT.Warn else ZxT.Info
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2 else c.copy(.07f))
                    .padding(6.dp, 4.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.width(3.dp).height(28.dp)
                        .clip(RoundedCornerShape(2.dp)).background(c))
                    Column(Modifier.weight(1f).padding(start = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (lv == "crit") "紧急" else if (lv == "warn") "警告" else "提示",
                                fontSize = 7.5.sp, color = c,
                                modifier = Modifier.clip(RoundedCornerShape(3.dp))
                                    .background(c.copy(.15f)).padding(4.dp, 1.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(nm, fontSize = 9.5.sp, color = ZxT.Text,
                                 modifier = Modifier.weight(1f), maxLines = 1,
                                 overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.height(3.dp))
                        Row {
                            if (a.isBlank())
                                Text("没有写处置动作 —— 看完不知道下一步是什么", fontSize = 8.sp,
                                     color = ZxT.Err)
                            else
                                Text(a, fontSize = 8.5.sp, color = ZxT.Text3,
                                     modifier = Modifier.weight(1f), maxLines = 2,
                                     overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.width(4.dp))
                            Text("处置", fontSize = 8.sp, color = ZxT.PriLite,
                                 modifier = Modifier.clip(RoundedCornerShape(4.dp))
                                     .background(ZxT.Surface3)
                                     .clickable {
                                         sel = k
                                         ctx.callTool("zx_select", mapOf(
                                             "alert" to nm, "level" to lv, "action" to a,
                                             "source" to "zx_alert"))
                                     }.padding(6.dp, 2.dp))
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (noAct > 0) ZxT.Warn.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (noAct > 0) "!" else "✓", fontSize = 11.sp,
                     color = if (noAct > 0) ZxT.Warn else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (noAct > 0) "$noAct 条没写处置动作——**告警的意义是触发行动**，" +
                            "没动作的告警只会让人学会忽略它"
                         else "每条都带了处置动作", fontSize = 9.sp,
                         color = if (noAct > 0) ZxT.Warn else ZxT.Ok)
                    Text("告警疲劳是个真问题：**没动作的告警多了，真告警也会被一起忽略**",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 notice 是<b>一条提示条</b>、statuslist 是<b>状态列表</b>，" +
                 "这个把<b>多条告警聚在一起并带处置动作</b>。" +
                 "关键是<b>每条都要有动作</b>——" +
                 "「CPU 85%」这条告警发给谁、要做什么，不写清楚，" +
                 "收到的人只会关掉它。<b>没有动作的告警会被标出来</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_costtrack 成本追踪 ══════════ */
    @Composable
    fun costtrack(o: JSONObject, ctx: RenderContext) {
        val budget = o.optDouble("budget", 100.0)
        val ptsA = o.optJSONArray("pts") ?: return
        val n = minOf(ptsA.length(), 30)
        val pts = (0 until n).map {
            val p = ptsA.optJSONObject(it) ?: JSONObject()
            p.optInt("d", it + 1) to p.optDouble("v", 0.0)
        }
        var sel by remember { mutableIntStateOf(-1) }
        val cur = pts.lastOrNull()?.second ?: 0.0
        val day = pts.lastOrNull()?.first ?: 1
        val total = o.optInt("days", 30)
        val pace = budget * day / total.coerceAtLeast(1)
        val proj = if (day > 0) cur * total / day else 0.0
        val over = proj > budget
        val mx = maxOf(cur, proj, budget) * 1.05
        val W = 330f; val H = 72f; val pad = 6f
        val px = { k: Int -> pad + (k.toDouble() / (n - 1).coerceAtLeast(1) * (W - 2 * pad)).toFloat() }
        val py = { v: Double -> H - pad - (v / mx.coerceAtLeast(1e-9) * (H - 2 * pad)).toFloat() }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "成本追踪"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (over) "会超 ${"%.0f".format(proj - budget)}" else "按进度",
                     fontSize = 9.sp, color = if (over) ZxT.Err else ZxT.Ok)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(72.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    // 预算线
                    drawLine(ZxT.Err, Offset(pad, py(budget)), Offset(W - pad, py(budget)), 1.2f)
                    // 匀速参考：偏离它才看得出超前还是滞后
                    drawLine(ZxT.Text3.copy(.45f), Offset(pad, py(0.0)),
                             Offset(W - pad, py(budget)), 1.1f)
                    drawPath(Path().apply {
                        pts.forEachIndexed { k, (_, v) ->
                            val x = px(k); val y = py(v)
                            if (k == 0) moveTo(x, y) else lineTo(x, y)
                        }
                    }, if (over) ZxT.Err else PAL[0], style = Stroke(1.7f))
                    // 外推虚线：没有它用户不知道月底会到哪
                    drawLine(if (over) ZxT.Err else PAL[0], Offset(px(n - 1), py(cur)),
                             Offset(W - pad, py(proj)), 1.2f)
                    if (sel >= 0)
                        drawCircle(Color.White, 3.2f, Offset(px(sel), py(pts[sel].second)))
                }
                Row(Modifier.fillMaxWidth().height(72.dp)) {
                    repeat(n) { k ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = k
                            ctx.callTool("zx_select", mapOf(
                                "day" to pts[k].first.toString(), "spent" to "%.2f".format(pts[k].second),
                                "pace" to "%.2f".format(budget * pts[k].first / total.coerceAtLeast(1)),
                                "source" to "zx_costtrack"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                listOf("已花" to "%.1f".format(cur),
                       "匀速应花" to "%.1f".format(pace),
                       "月底预测" to "%.1f".format(proj)).forEach { (k, v) ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(v, fontSize = 9.5.sp, color = ZxT.Text, fontFamily = ZxT.Mono)
                        Text(k, fontSize = 7.5.sp, color = ZxT.Text3)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (over) ZxT.Err.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (over) "✗" else "✓", fontSize = 11.sp,
                     color = if (over) ZxT.Err else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (over) "第 $day 天已花 ${"%.1f".format(cur)}，" +
                            "匀速到月底会是 ${"%.1f".format(proj)}，超预算 ${"%.1f".format(proj - budget)}" +
                            "——**现在就得减，月底再说就来不及了**"
                         else "第 $day 天已花 ${"%.1f".format(cur)}，" +
                            "匀速应花 ${"%.1f".format(pace)}，还在预算内",
                        fontSize = 9.sp, color = if (over) ZxT.Err else ZxT.Ok)
                    Text("月底才发现超支已经晚了——**预算追踪的价值在月中预警**",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 budget 给<b>分档条</b>、meter 给<b>一个量</b>，" +
                 "这个算的是「<b>照这个速度月底会不会超</b>」。" +
                 "关键是<b>要外推</b>：只给「已花 60 / 预算 100」，" +
                 "用户不知道这算多还是少——第 10 天花 60 和第 25 天花 60 " +
                 "是完全两回事。<b>预算 ≤0 或天数未升序会被拦</b>。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_feedback 结果回收 ══════════ */
    @Composable
    fun feedback(o: JSONObject, ctx: RenderContext) {
        val itemsA = o.optJSONArray("items") ?: return
        val n = minOf(itemsA.length(), 8)
        val items = (0 until n).map { i ->
            val it = itemsA.optJSONObject(i) ?: JSONObject()
            Triple(it.optString("n", "预测${i + 1}"), it.optDouble("p", 0.0),
                   it.optDouble("a", Double.NaN))
        }
        var sel by remember { mutableIntStateOf(-1) }
        val filled = items.filter { !it.third.isNaN() }
        val gaps = filled.map { it.third - it.second }
        val bias = if (gaps.isNotEmpty()) gaps.average() else Double.NaN
        val mae = if (gaps.isNotEmpty()) gaps.map { abs(it) }.average() else Double.NaN
        val mx = maxOf(items.maxOf { it.second }, filled.maxOfOrNull { it.third } ?: 0.0,
                       1.0)
        val W = 330f; val H = 76f; val pad = 8f
        val px = { k: Int -> pad + (k.toDouble() / (n - 1).coerceAtLeast(1) * (W - 2 * pad)).toFloat() }
        val py = { v: Double -> H - pad - (v / mx * (H - 2 * pad)).toFloat() }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "结果回收"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (filled.size < n) "${n - filled.size} 项待回填" else "已全部回填",
                     fontSize = 9.sp, color = if (filled.size < n) ZxT.Warn else ZxT.Ok)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(76.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    // 完美线 y=x 的对角参考
                    drawLine(ZxT.Text3.copy(.4f), Offset(pad, py(0.0)),
                             Offset(W - pad, py(mx)), 1.1f)
                    // 预测：空心
                    items.forEachIndexed { k, (_, p, _) ->
                        drawCircle(PAL[0], if (sel == k) 4f else 3f, Offset(px(k), py(p)),
                                   style = Stroke(1.4f))
                    }
                    // 实际：实心，未回填就断开
                    val path = Path(); var started = false
                    items.forEachIndexed { k, (_, _, a) ->
                        if (a.isNaN()) { started = false; return@forEachIndexed }
                        val x = px(k); val y = py(a)
                        if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
                    }
                    drawPath(path, ZxT.Ok, style = Stroke(1.6f))
                    items.forEachIndexed { k, (_, _, a) ->
                        if (a.isNaN()) return@forEachIndexed
                        drawCircle(if (sel == k) Color.White else ZxT.Ok,
                                   if (sel == k) 4f else 3f, Offset(px(k), py(a)))
                        // 偏差竖线
                        drawLine(ZxT.Err.copy(.4f), Offset(px(k), py(items[k].second)),
                                 Offset(px(k), py(a)), 1.1f)
                    }
                }
                Row(Modifier.fillMaxWidth().height(76.dp)) {
                    repeat(n) { k ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = k
                            ctx.callTool("zx_select", mapOf(
                                "item" to items[k].first, "pred" to "%.3f".format(items[k].second),
                                "actual" to (if (items[k].third.isNaN()) "NA"
                                             else "%.3f".format(items[k].third)),
                                "source" to "zx_feedback"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                Text("○ 预测　● 实际　红线=偏差", fontSize = 7.5.sp, color = ZxT.Text3,
                     modifier = Modifier.weight(1f))
                Text(if (mae.isNaN()) "还没回填" else "MAE ${"%.2f".format(mae)}",
                    fontSize = 8.sp, color = ZxT.Text3)
            }
            items.forEachIndexed { k, (nm, p, a) ->
                if (sel != k) return@forEachIndexed
                Row(Modifier.fillMaxWidth().padding(top = 3.dp)
                    .clip(RoundedCornerShape(5.dp)).background(ZxT.Surface2)
                    .padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(nm, fontSize = 9.sp, color = ZxT.Text2,
                         modifier = Modifier.weight(1f), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    Text("预测 %.2f".format(p), fontSize = 8.5.sp, color = PAL[0])
                    Text(if (a.isNaN()) "实际 ——" else "实际 %.2f".format(a),
                        fontSize = 8.5.sp,
                        color = if (a.isNaN()) ZxT.Text3 else ZxT.Ok,
                        modifier = Modifier.padding(start = 6.dp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (filled.isEmpty()) ZxT.Warn.copy(.10f)
                            else if (!bias.isNaN() && abs(bias) > 0.15 * mx) ZxT.Err.copy(.10f)
                            else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (filled.isEmpty()) "!" else if (!bias.isNaN() && abs(bias) > 0.15 * mx) "✗" else "✓",
                     fontSize = 11.sp, color = if (filled.isEmpty()) ZxT.Warn
                     else if (!bias.isNaN() && abs(bias) > 0.15 * mx) ZxT.Err else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (filled.isEmpty()) "一项都还没回填——**预测发出去就没人再看了**，" +
                            "这是闭环断掉最常见的地方"
                         else if (abs(bias) > 0.15 * mx)
                             "系统性${if (bias > 0) "低估" else "高估"} ${"%.2f".format(abs(bias))}" +
                            "——不是随机误差，是**有偏**，要改模型或改口径"
                         else "预测与实际基本吻合（偏差 ${"%.2f".format(bias)}）",
                        fontSize = 9.sp, color = if (filled.isEmpty()) ZxT.Warn
                        else if (!bias.isNaN() && abs(bias) > 0.15 * mx) ZxT.Err else ZxT.Ok)
                    Text("MAE 看误差大小，偏差看方向：**有偏比误差大更严重**，" +
                        "因为误差大会互相抵消，有偏会一直往一个方向错",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 calib 看<b>概率准不准</b>、zx_diff 比<b>两版差异</b>，" +
                 "这个对比的是<b>当初说的数和后来实际发生的数</b>。" +
                 "关键是<b>要分开看偏差和误差</b>：偏差是方向（系统性高估/低估），" +
                 "误差是大小（MAE）。<b>预测数和实际数数量不等会被拦</b>——" +
                 "对不上就没法逐项比。",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }
}
