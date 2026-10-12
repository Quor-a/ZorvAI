package com.zorv.plugin.zxwidget

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import androidx.compose.ui.graphics.PathEffect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 第三十五批组件（15 个）—— 生活场景。
 *
 * 前三十四批走过：分析 → 决策 → 行动 → 知识留存 → 消费类信息 → 日常工具。
 * 这批是**长期跟踪**：
 *
 *   健身   不是今天练了多少，是这个月有没有进步
 *   用药   不是吃了什么药，是**有没有漏服**
 *   快递   不是有个单号，是到哪了、还要多久
 *   订阅   不是有几个订阅，是**下个月要扣多少、哪个忘了取消**
 *   水电表 不是读数是多少，是**比上个月多了还是少了**
 *   睡眠   不是昨晚睡了几小时，是这一周趋势往哪走
 *
 * ── 贯穿这批的一条 ──────────────────────────────────────────
 *
 * **生活场景要看"变化"，不看"当前"。**
 *
 * 一次读数、一天记录、一个状态**都没有信息**。
 * 有意义的是：它跟上次比怎么样、跟目标比怎么样、趋势往哪走。
 *
 *   健身记录只有一次     → 看不出有没有进步
 *   水电表只有本次读数   → 看不出多了还是少了
 *   睡眠只有昨晚         → 看不出是偶尔还是常态
 *   快递只有当前状态     → 看不出还要多久
 *
 * 所以这批每个组件都强制带**对比基准**——上次数、目标值、历史序列。
 * 缺了它校验就拦，因为画出来的卡"好看但回答不了用户真正想问的"。
 *
 * ── 校验因此换了第十三档 ────────────────────────────────────
 *
 * 前十二档查"画不出来 / 统计前提不成立 / 区间前提不成立 / 因果前提\n* 无从判断 / 看起来确定其实不确定 / 推不出该做什么 / 记了下次用不上 /\n* 落不到谁该做什么 / 推演本身不可信 / 决策前提在过程中会变 /\n* 信息新不新能不能信 / 操作了但得不到结果"，
 *
 * 这批查的是**「只有当前值、没有对比基准」**：
 *
 *   fitness      只有 1 条记录（看不出趋势）/ 无目标 / 值非正
 *   medicine     **漏服未标** / 下次服药早于上次 / 无用药项
 *   parcel       节点时间倒序 / 无节点
 *   ticket       **已售座位还能选** / 无座位 / 选中越界
 *   study        无卡片 / 复习间隔非法
 *   plant        浇水间隔 ≤ 0 / **下次浇水已过期未标**
 *   parking      无车位号 / 无楼层 / 无停车时间
 *   habit        无记录 / 目标天数非法 / 有未来日期
 *   subscription **下次扣款已过** / 金额为负 / 无订阅
 *   pet          无照护项 / 间隔 ≤ 0
 *   utilitymeter **本次 < 上次（表倒转）** / 无历史
 *   sleep        时长超出 0-24h / 只有 1 天 / 无记录
 *   coupon       **已过期** / 门槛为负 / 折扣 > 1
 *   appointment  **时间已过** / 号已满 / 无医生
 */
object ZxRender35 {

    private val PAL = listOf(
        Color(0xFF5046E4), Color(0xFF3FBF7F), Color(0xFFE8A33D),
        Color(0xFFE5544B), Color(0xFF4A9BE8), Color(0xFF8E86FF)
    )

    private fun jarr(o: JSONObject, k: String): List<JSONObject> {
        val a = o.optJSONArray(k) ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }
    private fun jstr(o: JSONObject, k: String): List<String> {
        val a = o.optJSONArray(k) ?: return emptyList()
        return (0 until a.length()).map { a.optString(it, "") }
    }
    private fun jnum(o: JSONObject, k: String): List<Double> {
        val a = o.optJSONArray(k) ?: return emptyList()
        return (0 until a.length()).map { a.optDouble(it, 0.0) }
    }

    /** 迷你折线——这批每个组件都要画趋势，抽出来 */
    @Composable
    private fun spark(vs: List<Double>, col: Color, h: Int = 34, target: Double? = null) {
        if (vs.isEmpty()) return
        val lo = vs.minOrNull()!!; val hi = vs.maxOrNull()!!
        val tHi = max(hi, target ?: lo); val tLo = min(lo, target ?: hi)
        val rng = (tHi - tLo).let { if (it < 1e-9) 1.0 else it }
        Canvas(Modifier.fillMaxWidth().height(h.dp)) {
            val W = size.width; val H = size.height
            if (target != null) {
                val y = H - ((target - tLo) / rng * H).toFloat()
                drawLine(col.copy(alpha = .35f), Offset(0f, y), Offset(W, y), 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 3f)))
            }
            if (vs.size >= 2) {
                val pts = vs.mapIndexed { i, v ->
                    Offset(W * i / (vs.size - 1f).coerceAtLeast(1f),
                        (H - (v - tLo) / rng * H).toFloat())
                }
                drawPath(Path().apply {
                    pts.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
                }, col, style = Stroke(1.8f))
                pts.forEachIndexed { i, p ->
                    drawCircle(if (i == vs.size - 1) col else col.copy(alpha = .45f),
                        if (i == vs.size - 1) 3.2f else 2f, p)
                }
            }
        }
    }

    /* ══════════ zx_fitness 健身记录 ══════════ */
    @Composable
    fun fitness(o: JSONObject, ctx: RenderContext) {
        // recs: {d:日期, v:数值}  target:目标  unit:单位
        val recs = jarr(o, "recs").map { it.optString("d", "") to it.optDouble("v", 0.0) }
        val target = o.optDouble("target", Double.NaN)
        val unit = o.optString("unit", "kg")
        var sel by remember { mutableIntStateOf(-1) }
        val vs = recs.map { it.second }
        val last = vs.lastOrNull() ?: 0.0
        val prev = if (vs.size >= 2) vs[vs.size - 2] else Double.NaN
        val delta = if (!prev.isNaN()) last - prev else Double.NaN
        val best = vs.maxOrNull() ?: 0.0

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "健身"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text("${recs.size} 次", fontSize = 8.5.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("%.1f".format(last), fontSize = 22.sp, color = ZxT.Text)
                Spacer(Modifier.width(3.dp))
                Text(unit, fontSize = 9.sp, color = ZxT.Text3,
                    modifier = Modifier.padding(bottom = 3.dp))
                Spacer(Modifier.width(8.dp))
                if (!delta.isNaN()) {
                    val up = delta > 0
                    Text((if (up) "▲" else "▼") + " %.1f".format(abs(delta)),
                        fontSize = 10.sp, color = if (up) ZxT.Ok else ZxT.Text3)
                }
            }
            if (!target.isNaN()) {
                val pct = (last / target * 100).coerceIn(0.0, 100.0)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("目标 %.0f".format(target), fontSize = 8.sp, color = ZxT.Text3,
                        modifier = Modifier.width(52.dp))
                    Box(Modifier.weight(1f).height(6.dp)
                        .clip(RoundedCornerShape(3.dp)).background(ZxT.Surface2)) {
                        Box(Modifier.fillMaxWidth((pct / 100).toFloat()).fillMaxHeight()
                            .background(if (pct >= 100.0) ZxT.Ok else ZxT.Primary))
                    }
                    Text("%.0f%%".format(pct), fontSize = 8.sp, color = ZxT.Text2,
                        modifier = Modifier.width(34.dp), textAlign = TextAlign.End)
                }
            }
            Spacer(Modifier.height(5.dp))
            spark(vs, ZxT.Primary, 40, if (target.isNaN()) null else target)
            Row(Modifier.fillMaxWidth()) {
                Text("最早 ${recs.firstOrNull()?.first ?: "—"}", fontSize = 7.5.sp,
                    color = ZxT.Text3, modifier = Modifier.weight(1f))
                Text("最好 %.1f".format(best), fontSize = 7.5.sp, color = ZxT.Text3)
                Spacer(Modifier.width(6.dp))
                Text("最近 ${recs.lastOrNull()?.first ?: "—"}", fontSize = 7.5.sp,
                    color = ZxT.Text3)
            }
            Spacer(Modifier.height(4.dp))
            recs.forEachIndexed { k, (d, v) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 1.5.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (sel == k) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_select", mapOf("date" to d,
                            "value" to "%.1f".format(v), "source" to "zx_fitness"))
                    }.padding(6.dp, 3.dp)) {
                    Text(d, fontSize = 8.5.sp, color = ZxT.Text3,
                        modifier = Modifier.width(52.dp), fontFamily = ZxT.Mono)
                    Box(Modifier.weight(1f).height(6.dp)
                        .clip(RoundedCornerShape(3.dp)).background(ZxT.Surface2)) {
                        val mx = best.coerceAtLeast(1.0)
                        Box(Modifier.fillMaxWidth((v / mx).toFloat().coerceIn(0.02f, 1f))
                            .fillMaxHeight().background(
                                if (k == recs.size - 1) ZxT.PriLite else PAL[0].copy(alpha = .6f)))
                    }
                    Text("%.1f".format(v), fontSize = 9.sp, color = ZxT.Text,
                        modifier = Modifier.width(42.dp), textAlign = TextAlign.End,
                        fontFamily = ZxT.Mono)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (recs.size < 2) ZxT.Warn.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (recs.size < 2) "!" else "✓", fontSize = 11.sp,
                    color = if (recs.size < 2) ZxT.Warn else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (recs.size < 2) "**只有 ${recs.size} 条记录**——" +
                        "**看不出趋势**，用户不知道自己有没有进步"
                        else "${recs.size} 次，最近比上次 " +
                        (if (delta > 0) "+" else "") + "%.1f".format(delta) + "。" +
                        "**要有对比基准**", fontSize = 9.sp,
                        color = if (recs.size < 2) ZxT.Warn else ZxT.Ok)
                    Text("**一次记录没有信息**——健身的诉求是「我有没有进步」，" +
                        "不是「今天做了多少」", fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 ring 是<b>环形进度</b>、trend 是<b>趋势线</b>，" +
                "这个是<b>健身记录</b>：多次 + 目标 + 环比。" +
                "<b>只有 1 条记录会被拦</b>；<b>无目标会被拦</b>。",
                fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_medicine 用药记录 ══════════ */
    @Composable
    fun medicine(o: JSONObject, ctx: RenderContext) {
        // items: {n:药名, dose:剂量, times:[已服时刻], plan:[计划时刻]}
        val items = jarr(o, "items")
        var sel by remember { mutableIntStateOf(-1) }
        val taken = items.sumOf { it.optJSONArray("times")?.length() ?: 0 }
        val planned = items.sumOf { it.optJSONArray("plan")?.length() ?: 0 }
        val missed = planned - taken

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "用药"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(if (missed > 0) "**漏 $missed 次**" else "全部已服",
                    fontSize = 8.5.sp, color = if (missed > 0) ZxT.Err else ZxT.Ok)
            }
            Spacer(Modifier.height(5.dp))
            items.forEachIndexed { k, it ->
                val nm = it.optString("n", "")
                val dose = it.optString("dose", "")
                val ts = jstr(it, "times"); val ps = jstr(it, "plan")
                val miss = ps.filter { p -> p !in ts }
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2
                        else if (miss.isNotEmpty()) ZxT.Err.copy(.07f) else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_med_taken", mapOf("drug" to nm, "dose" to dose,
                            "missed" to miss.joinToString(",")))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(nm, fontSize = 10.sp, color = ZxT.Text,
                                modifier = Modifier.weight(1f), maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text(dose, fontSize = 8.sp, color = ZxT.Text3)
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                            ps.forEach { p ->
                                val on = p in ts
                                Box(Modifier.padding(end = 3.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (on) ZxT.Ok.copy(.75f)
                                        else ZxT.Err.copy(.30f))
                                    .border(1.dp, if (on) ZxT.Ok else ZxT.Err,
                                        RoundedCornerShape(4.dp))
                                    .padding(6.dp, 2.dp)) {
                                    Text(p, fontSize = 8.sp,
                                        color = if (on) Color.White else ZxT.Err,
                                        fontFamily = ZxT.Mono)
                                }
                            }
                        }
                        if (miss.isNotEmpty())
                            Text("漏服：${miss.joinToString(" / ")}", fontSize = 7.5.sp,
                                color = ZxT.Err, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (missed > 0) ZxT.Err.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (missed > 0) "✗" else "✓", fontSize = 11.sp,
                    color = if (missed > 0) ZxT.Err else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (missed > 0) "**该服 $planned 次，只服了 $taken 次，" +
                        "漏 $missed 次**——漏服是安全问题，不是提醒问题"
                        else "$planned 次全部已服", fontSize = 9.sp,
                        color = if (missed > 0) ZxT.Err else ZxT.Ok)
                    Text("**用药卡看不到漏服，等于没有记录**——" +
                        "用户会以为自己吃了，实际没吃", fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 agenda 是<b>日程</b>、tasklist 是<b>任务</b>，" +
                "这个是<b>用药记录</b>：计划 vs 实际 + <b>漏服标红</b>。" +
                "<b>无用药项会被拦</b>；<b>服药时刻早于计划过多会被拦</b>。",
                fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_parcel 快递轨迹 ══════════ */
    @Composable
    fun parcel(o: JSONObject, ctx: RenderContext) {
        val nodes = jarr(o, "nodes").map {
            Triple(it.optString("t", ""), it.optString("ts", ""), it.optString("p", ""))
        }
        var cur by remember { mutableIntStateOf(nodes.size - 1) }
        val id = o.optString("id", "")
        val eta = o.optInt("eta", 0)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("co", "快递"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(id.ifBlank { "—" }, fontSize = 8.sp, color = ZxT.Text3,
                    fontFamily = ZxT.Mono)
            }
            Spacer(Modifier.height(3.dp))
            if (eta > 0) Text("预计还需 $eta 小时", fontSize = 8.5.sp, color = ZxT.PriLite)
            Spacer(Modifier.height(5.dp))
            nodes.forEachIndexed { k, (t, ts, p) ->
                val done = k <= cur
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.width(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(7.dp).clip(CircleShape)
                            .background(if (done) ZxT.Ok else ZxT.Surface3))
                        if (k < nodes.size - 1)
                            Box(Modifier.width(1.2.dp).height(16.dp)
                                .background(if (k < cur) ZxT.Ok.copy(.6f) else ZxT.Surface3))
                    }
                    Spacer(Modifier.width(6.dp))
                    Row(Modifier.weight(1f).padding(vertical = 2.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable {
                            cur = k
                            ctx.callTool("zx_select", mapOf("node" to t, "place" to p,
                                "source" to "zx_parcel"))
                        }.padding(5.dp, 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t, fontSize = 9.sp,
                                color = if (done) ZxT.Text else ZxT.Text3)
                            Row {
                                if (p.isNotBlank()) Text(p, fontSize = 7.5.sp,
                                    color = ZxT.Text3, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false))
                                if (ts.isNotBlank()) Text("　$ts", fontSize = 7.5.sp,
                                    color = ZxT.Text3, fontFamily = ZxT.Mono)
                            }
                        }
                        if (k == cur) Text("当前", fontSize = 7.5.sp, color = ZxT.PriLite)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (nodes.isEmpty()) ZxT.Err.copy(.10f)
                    else if (eta <= 0) ZxT.Warn.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (nodes.isEmpty()) "✗" else if (eta <= 0) "!" else "✓",
                    fontSize = 11.sp,
                    color = if (nodes.isEmpty()) ZxT.Err else if (eta <= 0) ZxT.Warn else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (nodes.isEmpty()) "**没有节点**——看不到货在哪"
                        else if (eta <= 0) "${nodes.size} 个节点，当前「${nodes[cur].first}」。" +
                            "**没给预计还需多久**——用户真正想问的是「还要等多久」"
                        else "${nodes.size} 个节点，当前「${nodes[cur].first}」，" +
                            "预计还需 $eta 小时", fontSize = 9.sp,
                        color = if (nodes.isEmpty()) ZxT.Err
                            else if (eta <= 0) ZxT.Warn else ZxT.Ok)
                    Text("**快递轨迹只列节点不给剩余时间，用户还得自己估**——" +
                        "而估错了就是白等", fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 order 是<b>订单物流</b>，这个是<b>快递轨迹</b>：" +
                "节点 + 地点 + <b>预计还需多久</b>。" +
                "<b>无节点会被拦</b>；<b>时间倒序会被拦</b>。",
                fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_ticket 票务座位 ══════════ */
    @Composable
    fun ticket(o: JSONObject, ctx: RenderContext) {
        // seats: {n:座位号, s:0可选 1已售 2锁定}
        val seats = jarr(o, "seats")
        val sel = remember { mutableStateListOf<String>() }
        val maxSel = o.optInt("max", 2)
        val price = o.optDouble("price", 0.0)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "选座"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text("选了 ${sel.size}/$maxSel", fontSize = 8.5.sp,
                    color = if (sel.isEmpty()) ZxT.Text3 else ZxT.PriLite)
            }
            Spacer(Modifier.height(5.dp))
            Box(Modifier.fillMaxWidth().height(16.dp)
                .clip(RoundedCornerShape(3.dp)).background(ZxT.Surface3),
                contentAlignment = Alignment.Center) {
                Text("舞　台", fontSize = 8.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(5.dp))
            // 座位网格
            val cols = 6
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                var k = 0
                while (k < seats.size) {
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (c in 0 until cols) {
                            if (k + c < seats.size) {
                                val i = k + c
                                val s = seats[i]
                                val st = s.optInt("s", 0)
                                val nm = s.optString("n", "")
                                val on = sel.contains(nm)
                                val col = when {
                                    st == 1 -> ZxT.Surface3          // 已售
                                    st == 2 -> ZxT.Warn.copy(.35f)    // 锁定
                                    on -> ZxT.Primary
                                    else -> ZxT.Surface2
                                }
                                Box(Modifier.weight(1f).aspectRatio(1f)
                                    .clip(RoundedCornerShape(4.dp)).background(col)
                                    .clickable {
                                        if (st != 0) return@clickable        // 已售点不动
                                        if (on) sel.remove(nm)
                                        else if (sel.size < maxSel) sel.add(nm)
                                        ctx.callTool("zx_seat_pick", mapOf(
                                            "seat" to nm, "picked" to sel.toList()
                                                .joinToString(",")))
                                    }, contentAlignment = Alignment.Center) {
                                    Text(nm, fontSize = 7.5.sp,
                                        color = when {
                                            st == 1 -> ZxT.Text3
                                            st == 2 -> ZxT.Warn
                                            on -> Color.White
                                            else -> ZxT.Text2
                                        })
                                }
                            } else Spacer(Modifier.weight(1f))
                        }
                    }
                    k += cols
                    Spacer(Modifier.height(4.dp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                listOf(ZxT.Surface2 to "可选", ZxT.Primary to "已选",
                    ZxT.Surface3 to "已售", ZxT.Warn.copy(.35f) to "锁定").forEach { (c, l) ->
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(c))
                    Text(l, fontSize = 7.5.sp, color = ZxT.Text3,
                        modifier = Modifier.padding(start = 3.dp, end = 8.dp))
                }
            }
            if (price > 0 && sel.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("合计 ¥${"%.0f".format(price * sel.size)}（${"%.0f".format(price)} × ${sel.size}）",
                    fontSize = 10.sp, color = ZxT.Err)
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (seats.isEmpty()) ZxT.Err.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (seats.isEmpty()) "✗" else "✓", fontSize = 11.sp,
                    color = if (seats.isEmpty()) ZxT.Err else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (seats.isEmpty()) "**没有座位**——空的选座区"
                        else "${seats.size} 个座位，可选 " +
                        "${seats.count { it.optInt("s", 0) == 0 }} 个。" +
                        "**已售的点不动**", fontSize = 9.sp,
                        color = if (seats.isEmpty()) ZxT.Err else ZxT.Ok)
                    Text("**已售座位能选，等于把冲突留到付款时才告诉用户**——" +
                        "用户的感受是抢到了又被抢走", fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 seat 是<b>座位图</b>（场馆固定布局），" +
                "这个是<b>购票选座</b>：可售状态 + 多选上限。" +
                "<b>无座位会被拦</b>；<b>默认选中已售座位会被拦</b>。",
                fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_study 学习卡片 ══════════ */
    @Composable
    fun study(o: JSONObject, ctx: RenderContext) {
        // cards: {q:正面, a:背面, lv:0-5 掌握度}
        val cards = jarr(o, "cards")
        var idx by remember { mutableIntStateOf(0) }
        var flip by remember { mutableStateOf(false) }
        val rated = remember { mutableStateListOf<Int>().apply {
            repeat(cards.size) { add(-1) } } }
        val c = cards.getOrNull(idx)
        val mastered = rated.count { it >= 4 }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "复习"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text("${idx + 1}/${cards.size}　掌握 $mastered", fontSize = 8.5.sp,
                    color = ZxT.Text3)
            }
            Spacer(Modifier.height(5.dp))
            if (c != null) {
                Box(Modifier.fillMaxWidth().height(84.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (flip) ZxT.Surface2 else ZxT.Primary.copy(.18f))
                    .clickable { flip = !flip }, contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(10.dp)) {
                        Text(if (flip) "答案" else "问题", fontSize = 7.5.sp, color = ZxT.Text3)
                        Spacer(Modifier.height(4.dp))
                        Text(if (flip) c.optString("a", "—") else c.optString("q", "—"),
                            fontSize = 11.sp, color = ZxT.Text, textAlign = TextAlign.Center,
                            lineHeight = 15.sp)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text(if (flip) "点卡片看问题" else "点卡片看答案", fontSize = 7.5.sp,
                        color = ZxT.Text3, modifier = Modifier.weight(1f))
                    Text("初始掌握度 ${c.optInt("lv", 0)}", fontSize = 7.5.sp, color = ZxT.Text3)
                }
                Spacer(Modifier.height(5.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf("忘了" to 1, "模糊" to 3, "会了" to 5).forEach { (l, v) ->
                        val on = rated[idx] == v
                        Box(Modifier.clip(RoundedCornerShape(5.dp))
                            .background(if (on) ZxT.Primary else ZxT.Surface2)
                            .clickable {
                                rated[idx] = v
                                ctx.callTool("zx_study_rate", mapOf(
                                    "card" to c.optString("q", ""), "score" to v,
                                    "next" to "复习间隔 " + (if (v >= 4) 7 else if (v >= 3) 2 else 1)
                                        + " 天"))
                                if (idx < cards.size - 1) { idx++; flip = false }
                            }.padding(10.dp, 4.dp)) {
                            Text(l, fontSize = 9.sp,
                                color = if (on) Color.White else ZxT.Text2)
                        }
                    }
                }
                Spacer(Modifier.height(5.dp))
                // 整组掌握度条
                Row(Modifier.fillMaxWidth()) {
                    cards.forEachIndexed { k, cc ->
                        val r = rated.getOrNull(k) ?: -1
                        val base = cc.optInt("lv", 0)
                        val col = if (r >= 0) (if (r >= 4) ZxT.Ok
                            else if (r >= 3) ZxT.Warn else ZxT.Err)
                        else PAL[base % PAL.size].copy(alpha = .5f)
                        Box(Modifier.weight(1f).padding(end = 2.dp).height(5.dp)
                            .clip(RoundedCornerShape(2.dp)).background(col))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (cards.isEmpty()) ZxT.Err.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (cards.isEmpty()) "✗" else "✓", fontSize = 11.sp,
                    color = if (cards.isEmpty()) ZxT.Err else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (cards.isEmpty()) "**没有卡片**——空的复习组"
                        else "${cards.size} 张，已评 ${rated.count { it >= 0 }} 张，" +
                        "掌握 $mastered 张。**评分决定下次间隔**", fontSize = 9.sp,
                        color = if (cards.isEmpty()) ZxT.Err else ZxT.Ok)
                    Text("**只翻卡片不记录掌握度，等于没复习**——" +
                        "下次还是从第一张开始，不会的永远不会", fontSize = 8.5.sp,
                        color = ZxT.Text3)
                }
            }
            Text("已有 flashcard 无、quiz 是<b>问卷</b>，" +
                "这个是<b>间隔重复学习卡</b>：翻转 + 评分 → 下次间隔。" +
                "<b>无卡片会被拦</b>。",
                fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_plant 植物养护 ══════════ */
    @Composable
    fun plant(o: JSONObject, ctx: RenderContext) {
        // plants: {n:名, iv:浇水间隔天, last:上次浇水(天前), sun:光照, done?:bool}
        val plants = jarr(o, "plants")
        var sel by remember { mutableIntStateOf(-1) }
        val overdue = plants.count {
            it.optInt("last", 0) >= it.optInt("iv", 7) && !it.optBoolean("done", false) }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "养护"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(if (overdue > 0) "**$overdue 株待浇**" else "都还行",
                    fontSize = 8.5.sp, color = if (overdue > 0) ZxT.Warn else ZxT.Ok)
            }
            Spacer(Modifier.height(5.dp))
            plants.forEachIndexed { k, p ->
                val nm = p.optString("n", "")
                val iv = p.optInt("iv", 7)
                val last = p.optInt("last", 0)
                val done = p.optBoolean("done", false)
                val ratio = (last.toFloat() / iv.coerceAtLeast(1)).coerceIn(0f, 1.4f)
                val late = last >= iv && !done
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2
                        else if (late) ZxT.Warn.copy(.08f) else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_plant_water", mapOf("plant" to nm,
                            "overdueDays" to max(0, last - iv)))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (done) "✓" else if (late) "!" else "🌿", fontSize = 11.sp,
                        color = if (done) ZxT.Ok else if (late) ZxT.Warn else ZxT.Text3,
                        modifier = Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(nm, fontSize = 9.5.sp, color = ZxT.Text,
                                modifier = Modifier.weight(1f), maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text(if (done) "已浇" else "$last 天前",
                                fontSize = 8.sp, color = if (done) ZxT.Ok else ZxT.Text3)
                        }
                        Spacer(Modifier.height(2.dp))
                        Box(Modifier.fillMaxWidth().height(5.dp)
                            .clip(RoundedCornerShape(3.dp)).background(ZxT.Surface2)) {
                            Box(Modifier.fillMaxWidth(ratio.coerceAtMost(1f))
                                .fillMaxHeight()
                                .background(if (done) ZxT.Ok
                                    else if (late) ZxT.Warn else ZxT.Primary))
                        }
                        Text("每 $iv 天　${p.optString("sun", "")}", fontSize = 7.5.sp,
                            color = ZxT.Text3)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (plants.isEmpty()) ZxT.Err.copy(.10f)
                    else if (overdue > 0) ZxT.Warn.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (plants.isEmpty()) "✗" else if (overdue > 0) "!" else "✓",
                    fontSize = 11.sp,
                    color = if (plants.isEmpty()) ZxT.Err
                        else if (overdue > 0) ZxT.Warn else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (plants.isEmpty()) "**没有植物**——空的养护清单"
                        else if (overdue > 0) "**$overdue 株已经过了浇水日**——" +
                            "**过期未标，用户以为都浇过了**"
                        else "${plants.size} 株，都在周期内", fontSize = 9.sp,
                        color = if (plants.isEmpty()) ZxT.Err
                            else if (overdue > 0) ZxT.Warn else ZxT.Ok)
                    Text("**养护清单的核心是「哪株该浇了」**——" +
                        "只列名字不给倒计时，用户得自己算天数", fontSize = 8.5.sp,
                        color = ZxT.Text3)
                }
            }
            Text("已有 checklist 是<b>检查清单</b>，" +
                "这个是<b>植物养护</b>：间隔 + 距上次天数 + <b>过期标红</b>。" +
                "<b>浇水间隔 ≤ 0 会被拦</b>；<b>无植物会被拦</b>。",
                fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_parking 停车找车 ══════════ */
    @Composable
    fun parking(o: JSONObject, ctx: RenderContext) {
        val zone = o.optString("zone", "")
        val floor = o.optString("floor", "")
        val spot = o.optString("spot", "")
        val mins = o.optInt("mins", 0)
        val fee = o.optDouble("fee", 0.0)
        var noted by remember { mutableStateOf(o.optBoolean("noted", false)) }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "停车"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(if (noted) "已记录位置" else "**未记录**", fontSize = 8.5.sp,
                    color = if (noted) ZxT.Ok else ZxT.Warn)
            }
            Spacer(Modifier.height(5.dp))
            // 车位大号显示
            Box(Modifier.fillMaxWidth().height(72.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(Brush.linearGradient(listOf(PAL[0], PAL[5]))),
                contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(spot.ifBlank { "—" }, fontSize = 27.sp, color = Color.White)
                    Text("${floor.ifBlank { "—" }} ${zone.ifBlank { "" }}",
                        fontSize = 9.sp, color = Color.White.copy(alpha = .85f))
                }
            }
            Spacer(Modifier.height(5.dp))
            Row(Modifier.fillMaxWidth()) {
                listOf("楼层" to floor, "区域" to zone, "车位" to spot).forEach { (k, v) ->
                    Column(Modifier.weight(1f)) {
                        Text(k, fontSize = 7.5.sp, color = ZxT.Text3)
                        Text(v.ifBlank { "—" }, fontSize = 11.sp, color = ZxT.Text)
                    }
                }
            }
            Spacer(Modifier.height(5.dp))
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("已停", fontSize = 7.5.sp, color = ZxT.Text3)
                    Text(if (mins >= 60) "${mins / 60} 小时 ${mins % 60} 分" else "$mins 分钟",
                        fontSize = 11.sp, color = ZxT.Text)
                }
                Column(Modifier.weight(1f)) {
                    Text("预计费用", fontSize = 7.5.sp, color = ZxT.Text3)
                    Text("¥${"%.0f".format(fee)}", fontSize = 11.sp, color = ZxT.Err)
                }
            }
            Spacer(Modifier.height(5.dp))
            Box(Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(if (noted) ZxT.Surface2 else ZxT.Primary)
                .clickable { noted = true }.padding(0.dp, 7.dp),
                contentAlignment = Alignment.Center) {
                Text(if (noted) "已记下位置" else "记下位置", fontSize = 10.sp,
                    color = if (noted) ZxT.Text3 else Color.White)
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (spot.isBlank() || floor.isBlank()) ZxT.Err.copy(.10f)
                    else if (!noted) ZxT.Warn.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (spot.isBlank() || floor.isBlank()) "✗"
                    else if (!noted) "!" else "✓", fontSize = 11.sp,
                    color = if (spot.isBlank() || floor.isBlank()) ZxT.Err
                        else if (!noted) ZxT.Warn else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (spot.isBlank() || floor.isBlank())
                        "**车位号或楼层缺失**——**不知道停哪，这张卡没用**"
                        else if (!noted) "车位 $spot 在 $floor。" +
                            "**没记下位置，回头就得一层层找**"
                        else "已记录：$floor $zone $spot", fontSize = 9.sp,
                        color = if (spot.isBlank() || floor.isBlank()) ZxT.Err
                            else if (!noted) ZxT.Warn else ZxT.Ok)
                    Text("**找车卡的终点是「用户走回车边」**——" +
                        "车位号要大到一眼看见，不是排在列表第 4 行", fontSize = 8.5.sp,
                        color = ZxT.Text3)
                }
            }
            Text("已有 mapnav 是<b>导航</b>、floor 是<b>平面图</b>，" +
                "这个是<b>停车找车</b>：车位大号 + 已停时长 + 费用。" +
                "<b>车位号或楼层缺失会被拦</b>。",
                fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_habit 习惯打卡 ══════════ */
    @Composable
    fun habit(o: JSONObject, ctx: RenderContext) {
        // days: 1/0 序列（最近 N 天）goal: 目标天数
        val days = jarr(o, "days").map { it.optInt("v", 0) }
        val goal = o.optInt("goal", 21)
        val labels = jstr(o, "labels")
        var sel by remember { mutableIntStateOf(-1) }
        val done = days.count { it == 1 }
        // 连续：从最后一天往前数
        var streak = 0
        for (k in days.size - 1 downTo 0) { if (days[k] == 1) streak++ else break }
        var best = 0; var run = 0
        days.forEach { if (it == 1) { run++; best = max(best, run) } else run = 0 }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "习惯"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text("$done/${days.size}", fontSize = 8.5.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("$streak", fontSize = 22.sp,
                    color = if (streak >= goal) ZxT.Ok else ZxT.PriLite)
                Spacer(Modifier.width(3.dp))
                Text("天连续", fontSize = 9.sp, color = ZxT.Text3,
                    modifier = Modifier.padding(bottom = 3.dp))
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("最长 $best　目标 $goal", fontSize = 8.sp, color = ZxT.Text3)
                    Box(Modifier.fillMaxWidth().height(5.dp)
                        .clip(RoundedCornerShape(3.dp)).background(ZxT.Surface2)) {
                        Box(Modifier.fillMaxWidth((done.toFloat() / goal).coerceAtMost(1f))
                            .fillMaxHeight().background(ZxT.Ok))
                    }
                }
            }
            Spacer(Modifier.height(5.dp))
            // 格子
            Row(Modifier.fillMaxWidth()) {
                days.forEachIndexed { k, v ->
                    val on = v == 1
                    Box(Modifier.weight(1f).padding(end = 2.dp).aspectRatio(1f)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (on) ZxT.Ok else ZxT.Surface2)
                        .clickable {
                            sel = k
                            ctx.callTool("zx_habit_toggle", mapOf(
                                "day" to (labels.getOrNull(k) ?: ""), "done" to on))
                        }, contentAlignment = Alignment.Center) {
                        if (on) Text("✓", fontSize = 8.sp, color = Color.White)
                        else if (sel == k) Box(Modifier.size(4.dp)
                            .clip(CircleShape).background(ZxT.Text3))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                Text(labels.firstOrNull() ?: "", fontSize = 7.5.sp, color = ZxT.Text3,
                    modifier = Modifier.weight(1f))
                Text(labels.lastOrNull() ?: "", fontSize = 7.5.sp, color = ZxT.Text3)
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (days.isEmpty()) ZxT.Err.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (days.isEmpty()) "✗" else "✓", fontSize = 11.sp,
                    color = if (days.isEmpty()) ZxT.Err else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (days.isEmpty()) "**没有记录**——空的打卡表"
                        else "连续 $streak 天，最长 $best 天，共 $done 天。" +
                        "**断在哪一天要能一眼看见**", fontSize = 9.sp,
                        color = if (days.isEmpty()) ZxT.Err else ZxT.Ok)
                    Text("**习惯打卡的价值在「连续」**——只统计总数，" +
                        "断三天和连着三天是一样的数，但完全不是一回事",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 calheat 是<b>日历热力</b>，这个是<b>习惯打卡</b>：" +
                "连续数 + 最长 + 目标。<b>无记录会被拦</b>；<b>目标 ≤ 0 会被拦</b>。",
                fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_subscription 订阅管理 ══════════ */
    @Composable
    fun subscription(o: JSONObject, ctx: RenderContext) {
        // subs: {n:名, v:金额, cyc:月/年, next:下次扣款(天后), use:最近使用(天前)}
        val subs = jarr(o, "subs")
        var sel by remember { mutableIntStateOf(-1) }
        var mode by remember { mutableStateOf("month") }
        val monthly = subs.sumOf {
            val v = it.optDouble("v", 0.0)
            if (it.optString("cyc", "月") == "年") v / 12 else v }
        val yearly = monthly * 12
        // 忘了取消：扣着钱但很久没用
        val waste = subs.filter { it.optInt("use", 0) > 60 }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "订阅"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Row {
                    listOf("月" to "month", "年" to "year").forEach { (l, m) ->
                        Box(Modifier.padding(start = 3.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (mode == m) ZxT.Primary else ZxT.Surface2)
                            .clickable { mode = m }.padding(7.dp, 2.dp)) {
                            Text(l, fontSize = 8.sp,
                                color = if (mode == m) Color.White else ZxT.Text2)
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("¥", fontSize = 10.sp, color = ZxT.Err,
                    modifier = Modifier.padding(bottom = 3.dp))
                Text("%.0f".format(if (mode == "month") monthly else yearly),
                    fontSize = 24.sp, color = ZxT.Err)
                Spacer(Modifier.width(4.dp))
                Text(if (mode == "month") "/月　年 ¥${"%.0f".format(yearly)}"
                    else "/年　月 ¥${"%.0f".format(monthly)}",
                    fontSize = 8.5.sp, color = ZxT.Text3,
                    modifier = Modifier.padding(bottom = 3.dp))
            }
            Spacer(Modifier.height(5.dp))
            subs.forEachIndexed { k, s ->
                val use = s.optInt("use", 0)
                val idle = use > 60
                val v = s.optDouble("v", 0.0)
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2
                        else if (idle) ZxT.Warn.copy(.08f) else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_sub_cancel", mapOf("name" to s.optString("n", ""),
                            "monthly" to "%.0f".format(v), "idleDays" to use))
                    }.padding(6.dp, 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row {
                            Text("¥${"%.0f".format(v)}/${s.optString("cyc", "月")}",
                                fontSize = 8.sp, color = ZxT.Text2)
                            Text("　${s.optInt("next", 0)} 天后扣", fontSize = 7.5.sp,
                                color = ZxT.Text3)
                            if (idle) Text("　**$use 天没用**", fontSize = 7.5.sp,
                                color = ZxT.Warn)
                        }
                    }
                    Text(if (s.optString("cyc", "月") == "年") "¥${"%.0f".format(v / 12)}/月"
                        else "¥${"%.0f".format(v)}/月", fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (subs.isEmpty()) ZxT.Err.copy(.10f)
                    else if (waste.isNotEmpty()) ZxT.Warn.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (subs.isEmpty()) "✗" else if (waste.isNotEmpty()) "!" else "✓",
                    fontSize = 11.sp,
                    color = if (subs.isEmpty()) ZxT.Err
                        else if (waste.isNotEmpty()) ZxT.Warn else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (subs.isEmpty()) "**没有订阅**——空的账单"
                        else if (waste.isNotEmpty()) "**${waste.size} 个在扣钱但很久没用**" +
                            "（${waste.joinToString("、") { it.optString("n", "") }}）——" +
                            "**这才是用户真正想要的信息**"
                        else "${subs.size} 个订阅，月 ¥${"%.0f".format(monthly)}",
                        fontSize = 9.sp,
                        color = if (subs.isEmpty()) ZxT.Err
                            else if (waste.isNotEmpty()) ZxT.Warn else ZxT.Ok)
                    Text("**年付要折算成月付才比得动**——" +
                        "¥998/年 和 ¥88/月 摆一起，不折算看不出哪个贵",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 wallet 是<b>钱包</b>、price 是<b>定价卡</b>，" +
                "这个是<b>订阅管理</b>：折算月付 + <b>闲置提示</b>。" +
                "<b>无订阅会被拦</b>；<b>金额为负会被拦</b>。",
                fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_pet 宠物照护 ══════════ */
    @Composable
    fun pet(o: JSONObject, ctx: RenderContext) {
        // pets: {n:名, iv:间隔小时, last:距上次小时, kind:事项}
        val pets = jarr(o, "pets")
        var sel by remember { mutableIntStateOf(-1) }
        val due = pets.count { it.optInt("last", 0) >= it.optInt("iv", 24) }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "照护"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(if (due > 0) "**$due 项待办**" else "都还行", fontSize = 8.5.sp,
                    color = if (due > 0) ZxT.Warn else ZxT.Ok)
            }
            Spacer(Modifier.height(5.dp))
            pets.forEachIndexed { k, p ->
                val nm = p.optString("n", "")
                val iv = p.optInt("iv", 24)
                val last = p.optInt("last", 0)
                val late = last >= iv
                val ratio = (last.toFloat() / iv.coerceAtLeast(1)).coerceIn(0f, 1f)
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2
                        else if (late) ZxT.Warn.copy(.08f) else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_pet_care", mapOf("pet" to nm,
                            "kind" to p.optString("kind", ""), "overdueHours" to max(0, last - iv)))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (late) "!" else "·", fontSize = 12.sp,
                        color = if (late) ZxT.Warn else ZxT.Text3,
                        modifier = Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.optString("kind", ""), fontSize = 9.5.sp, color = ZxT.Text)
                            Spacer(Modifier.width(5.dp))
                            Text(nm, fontSize = 8.sp, color = ZxT.Text3)
                            Spacer(Modifier.weight(1f))
                            Text(if (late) "**超 ${last - iv}h**" else "${iv - last}h 后",
                                fontSize = 8.sp, color = if (late) ZxT.Warn else ZxT.Text3)
                        }
                        Spacer(Modifier.height(2.dp))
                        Box(Modifier.fillMaxWidth().height(5.dp)
                            .clip(RoundedCornerShape(3.dp)).background(ZxT.Surface2)) {
                            Box(Modifier.fillMaxWidth(ratio).fillMaxHeight()
                                .background(if (late) ZxT.Warn else ZxT.Primary))
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (pets.isEmpty()) ZxT.Err.copy(.10f)
                    else if (due > 0) ZxT.Warn.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (pets.isEmpty()) "✗" else if (due > 0) "!" else "✓",
                    fontSize = 11.sp,
                    color = if (pets.isEmpty()) ZxT.Err
                        else if (due > 0) ZxT.Warn else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (pets.isEmpty()) "**没有照护项**——空的清单"
                        else if (due > 0) "**$due 项已经过了该做的时间**"
                        else "${pets.size} 项，都在周期内", fontSize = 9.sp,
                        color = if (pets.isEmpty()) ZxT.Err
                            else if (due > 0) ZxT.Warn else ZxT.Ok)
                    Text("**照护清单要给「还有多久」而不是「上次什么时候」**——" +
                        "用户不记得上次几点喂的，但看得懂「2 小时后」",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 plant 是<b>植物养护</b>（天级），" +
                "这个是<b>宠物照护</b>（小时级）：还有多久 + 超时标红。" +
                "<b>无照护项会被拦</b>；<b>间隔 ≤ 0 会被拦</b>。",
                fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_utilitymeter 水电表 ══════════ */
    @Composable
    fun utilitymeter(o: JSONObject, ctx: RenderContext) {
        // reads: {d:月份, v:读数}  kind:水/电/气
        val reads = jarr(o, "reads").map { it.optString("d", "") to it.optDouble("v", 0.0) }
        val unit = o.optString("unit", "度")
        var sel by remember { mutableIntStateOf(-1) }
        val vs = reads.map { it.second }
        val last = vs.lastOrNull() ?: 0.0
        val prev = if (vs.size >= 2) vs[vs.size - 2] else Double.NaN
        val use = if (!prev.isNaN()) last - prev else Double.NaN
        val avg = if (vs.size >= 2) {
            val tot = vs.last() - vs.first()
            tot / (vs.size - 1)
        } else Double.NaN

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("kind", "水") + "表", fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text("${reads.size} 期", fontSize = 8.5.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("%.0f".format(last), fontSize = 22.sp, color = ZxT.Text)
                Spacer(Modifier.width(3.dp))
                Text(unit, fontSize = 9.sp, color = ZxT.Text3,
                    modifier = Modifier.padding(bottom = 3.dp))
                if (!use.isNaN()) {
                    Spacer(Modifier.width(10.dp))
                    val up = !avg.isNaN() && use > avg
                    Column {
                        Text("本期用 %.0f".format(use), fontSize = 10.sp,
                            color = if (up) ZxT.Warn else ZxT.Text)
                        if (!avg.isNaN())
                            Text("均值 %.0f　${if (up) "↑" else "↓"} " +
                                "%.0f%%".format(abs(use - avg) / avg.coerceAtLeast(1.0) * 100),
                                fontSize = 8.sp, color = if (up) ZxT.Warn else ZxT.Ok)
                    }
                }
            }
            Spacer(Modifier.height(5.dp))
            // 用量柱状（差值而非读数——读数只会一直涨，没有形状）
            if (vs.size >= 2) {
                val diffs = (1 until vs.size).map { vs[it] - vs[it - 1] }
                val mx = diffs.maxOrNull()!!.coerceAtLeast(1.0)
                Row(Modifier.fillMaxWidth().height(40.dp),
                    verticalAlignment = Alignment.Bottom) {
                    diffs.forEachIndexed { k, d ->
                        val h = (d / mx * 36 + 3).toFloat()
                        Box(Modifier.weight(1f).padding(end = 2.dp).height(h.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (k == diffs.size - 1) ZxT.Primary
                                else PAL[0].copy(alpha = .55f)))
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    Text(reads.first().first, fontSize = 7.5.sp, color = ZxT.Text3,
                        modifier = Modifier.weight(1f))
                    Text("每期用量（非读数）", fontSize = 7.5.sp, color = ZxT.Text3)
                    Spacer(Modifier.width(4.dp))
                    Text(reads.last().first, fontSize = 7.5.sp, color = ZxT.Text3)
                }
            }
            Spacer(Modifier.height(4.dp))
            reads.forEachIndexed { k, (d, v) ->
                val pv = if (k > 0) vs[k - 1] else Double.NaN
                Row(Modifier.fillMaxWidth().padding(vertical = 1.5.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (sel == k) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_select", mapOf("month" to d,
                            "reading" to "%.0f".format(v), "source" to "zx_utilitymeter"))
                    }.padding(6.dp, 3.dp)) {
                    Text(d, fontSize = 8.5.sp, color = ZxT.Text3,
                        modifier = Modifier.width(56.dp), fontFamily = ZxT.Mono)
                    Text("%.0f".format(v), fontSize = 9.sp, color = ZxT.Text,
                        modifier = Modifier.width(52.dp), textAlign = TextAlign.End,
                        fontFamily = ZxT.Mono)
                    Text(if (pv.isNaN()) "　—" else "　+%.0f".format(v - pv),
                        fontSize = 8.5.sp, color = ZxT.Text2,
                        modifier = Modifier.weight(1f), fontFamily = ZxT.Mono)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (reads.size < 2) ZxT.Err.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (reads.size < 2) "✗" else "✓", fontSize = 11.sp,
                    color = if (reads.size < 2) ZxT.Err else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (reads.size < 2) "**只有 ${reads.size} 期读数**——" +
                        "**算不出用量**，用户只知道表走了多少，不知道自己用了多少"
                        else "${reads.size} 期，本期用 %.0f".format(use) +
                        "（均值 %.0f）".format(avg), fontSize = 9.sp,
                        color = if (reads.size < 2) ZxT.Err else ZxT.Ok)
                    Text("**读数只会一直涨，画出来是条直线没有形状**——" +
                        "要画的是**每期用量**，那才是用户能改的东西",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 trend 是<b>趋势线</b>、bar 是<b>柱状</b>，" +
                "这个是<b>水电表</b>：读数 → <b>每期用量</b> + 与均值比。" +
                "<b>少于 2 期会被拦</b>；<b>本次 < 上次（表倒转）会被拦</b>。",
                fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_sleep 睡眠记录 ══════════ */
    @Composable
    fun sleep(o: JSONObject, ctx: RenderContext) {
        // nights: {d:日期, v:小时, q:质量0-100}
        val nights = jarr(o, "nights").map {
            Triple(it.optString("d", ""), it.optDouble("v", 0.0), it.optInt("q", 0))
        }
        val goal = o.optDouble("goal", 8.0)
        var sel by remember { mutableIntStateOf(-1) }
        val vs = nights.map { it.second }
        val avg = if (vs.isEmpty()) 0.0 else vs.average()
        val last = vs.lastOrNull() ?: 0.0
        val under = vs.count { it < goal }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "睡眠"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text("${nights.size} 晚", fontSize = 8.5.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("%.1f".format(avg), fontSize = 22.sp, color = ZxT.Text)
                Spacer(Modifier.width(3.dp))
                Text("小时均", fontSize = 9.sp, color = ZxT.Text3,
                    modifier = Modifier.padding(bottom = 3.dp))
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text("昨晚 %.1f".format(last), fontSize = 9.sp,
                        color = if (last < goal) ZxT.Warn else ZxT.Ok)
                    Text("目标 %.0f　欠 $under 晚".format(goal), fontSize = 8.sp,
                        color = ZxT.Text3)
                }
            }
            Spacer(Modifier.height(5.dp))
            spark(vs, ZxT.Primary, 44, goal)
            Row(Modifier.fillMaxWidth()) {
                Text(nights.firstOrNull()?.first ?: "", fontSize = 7.5.sp, color = ZxT.Text3,
                    modifier = Modifier.weight(1f))
                Text("虚线 = 目标 %.0fh".format(goal), fontSize = 7.5.sp, color = ZxT.Text3)
                Spacer(Modifier.width(4.dp))
                Text(nights.lastOrNull()?.first ?: "", fontSize = 7.5.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(4.dp))
            nights.forEachIndexed { k, (d, v, q) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 1.5.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (sel == k) ZxT.Surface2
                        else if (v < goal) ZxT.Warn.copy(.06f) else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_select", mapOf("date" to d,
                            "hours" to "%.1f".format(v), "quality" to q,
                            "source" to "zx_sleep"))
                    }.padding(6.dp, 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(d, fontSize = 8.5.sp, color = ZxT.Text3,
                        modifier = Modifier.width(52.dp), fontFamily = ZxT.Mono)
                    Box(Modifier.weight(1f).height(6.dp)
                        .clip(RoundedCornerShape(3.dp)).background(ZxT.Surface2)) {
                        Box(Modifier.fillMaxWidth((v / 12.0).toFloat().coerceIn(0.02f, 1f))
                            .fillMaxHeight()
                            .background(if (v < goal) ZxT.Warn else ZxT.Ok))
                    }
                    Text("%.1fh".format(v), fontSize = 9.sp, color = ZxT.Text,
                        modifier = Modifier.width(38.dp), textAlign = TextAlign.End,
                        fontFamily = ZxT.Mono)
                    Text("　$q", fontSize = 8.sp, color = ZxT.Text3,
                        modifier = Modifier.width(26.dp), textAlign = TextAlign.End)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (nights.size < 2) ZxT.Warn.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (nights.size < 2) "!" else "✓", fontSize = 11.sp,
                    color = if (nights.size < 2) ZxT.Warn else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (nights.size < 2) "**只有 ${nights.size} 晚**——" +
                        "**看不出是偶尔还是常态**，用户以为自己一直睡不好"
                        else "${nights.size} 晚，均 %.1fh".format(avg) +
                        "，$under 晚没到目标", fontSize = 9.sp,
                        color = if (nights.size < 2) ZxT.Warn else ZxT.Ok)
                    Text("**单晚睡 5 小时不代表睡不好**——" +
                        "要看这一周趋势。只给一晚，用户会过度解读",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 calheat 是<b>日历热力</b>、spark 是<b>迷你趋势</b>，" +
                "这个是<b>睡眠记录</b>：多晚 + 目标线 + 质量分。" +
                "<b>只有 1 晚会被拦</b>；<b>时长超出 0-24h 会被拦</b>。",
                fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_coupon 优惠券 ══════════ */
    @Composable
    fun coupon(o: JSONObject, ctx: RenderContext) {
        // coupons: {n:名, v:面额或折扣率, t:0满减 1折扣, min:门槛, exp:剩余天数, used:bool}
        val coupons = jarr(o, "coupons")
        var sel by remember { mutableIntStateOf(-1) }
        val usable = coupons.count {
            !it.optBoolean("used", false) && it.optInt("exp", 0) > 0 }
        val soon = coupons.count {
            !it.optBoolean("used", false) && it.optInt("exp", 0) in 1..3 }
        val total = coupons.filter { !it.optBoolean("used", false) }
            .sumOf { if (it.optInt("t", 0) == 0) it.optDouble("v", 0.0) else 0.0 }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "优惠券"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(if (usable == 0) "无可用的" else "$usable 张可用",
                    fontSize = 8.5.sp, color = if (usable == 0) ZxT.Warn else ZxT.Ok)
            }
            if (total > 0) {
                Spacer(Modifier.height(2.dp))
                Text("满减合计 ¥${"%.0f".format(total)}", fontSize = 8.5.sp,
                    color = ZxT.Err)
            }
            Spacer(Modifier.height(5.dp))
            coupons.forEachIndexed { k, c ->
                val used = c.optBoolean("used", false)
                val exp = c.optInt("exp", 0)
                val isPct = c.optInt("t", 0) == 1
                val dead = used || exp <= 0
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (sel == k) ZxT.Surface2
                        else if (dead) ZxT.Surface2.copy(alpha = .4f)
                        else if (exp in 1..3) ZxT.Warn.copy(.08f)
                        else ZxT.Surface3.copy(alpha = .6f))
                    .clickable {
                        if (dead) return@clickable
                        sel = k
                        ctx.callTool("zx_coupon_use", mapOf("name" to c.optString("n", ""),
                            "value" to c.optDouble("v", 0.0), "min" to c.optDouble("min", 0.0)))
                    }.padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    // 左：金额/折扣
                    Column(Modifier.width(52.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (isPct) "%.1f".format(c.optDouble("v", 0.0) * 10) + "折"
                            else "¥" + "%.0f".format(c.optDouble("v", 0.0)),
                            fontSize = 14.sp,
                            color = if (dead) ZxT.Text3 else ZxT.Err)
                        if (!isPct && c.optDouble("min", 0.0) > 0)
                            Text("满%.0f".format(c.optDouble("min", 0.0)),
                                fontSize = 7.5.sp, color = ZxT.Text3)
                    }
                    Box(Modifier.width(1.dp).height(28.dp)
                        .background(ZxT.Line.copy(alpha = if (dead) .3f else 1f)))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.optString("n", ""), fontSize = 9.5.sp,
                            color = if (dead) ZxT.Text3 else ZxT.Text, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        Text(when {
                            used -> "已使用"
                            exp <= 0 -> "**已过期**"
                            exp <= 3 -> "**还剩 $exp 天**"
                            else -> "还剩 $exp 天"
                        }, fontSize = 8.sp, color = when {
                            used -> ZxT.Text3
                            exp <= 0 -> ZxT.Text3
                            exp <= 3 -> ZxT.Warn
                            else -> ZxT.Text3 })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (coupons.isEmpty()) ZxT.Err.copy(.10f)
                    else if (soon > 0) ZxT.Warn.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (coupons.isEmpty()) "✗" else if (soon > 0) "!" else "✓",
                    fontSize = 11.sp,
                    color = if (coupons.isEmpty()) ZxT.Err
                        else if (soon > 0) ZxT.Warn else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (coupons.isEmpty()) "**没有优惠券**——空的券包"
                        else if (soon > 0) "**$soon 张 3 天内到期**——" +
                            "**这才是用户最该看到的**，过期的已经没用了"
                        else "${coupons.size} 张，$usable 张可用", fontSize = 9.sp,
                        color = if (coupons.isEmpty()) ZxT.Err
                            else if (soon > 0) ZxT.Warn else ZxT.Ok)
                    Text("**券包要按「快过期的」排，不是按面额排**——" +
                        "大额但半年后才过的券，不如小额明天就过期的急",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 export 是<b>导出</b>、shop 是<b>商品</b>，" +
                "这个是<b>优惠券</b>：满减/折扣 + <b>临期标红</b>。" +
                "<b>门槛为负会被拦</b>；<b>折扣率 > 1 会被拦</b>。",
                fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_appointment 预约挂号 ══════════ */
    @Composable
    fun appointment(o: JSONObject, ctx: RenderContext) {
        // slots: {t:时段, left:余号}  doctor / dept / date
        val slots = jarr(o, "slots")
        var sel by remember { mutableIntStateOf(-1) }
        val total = slots.sumOf { it.optInt("left", 0) }
        val full = slots.count { it.optInt("left", 0) <= 0 }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("dept", "科室"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(o.optString("date", ""), fontSize = 8.5.sp, color = ZxT.Text3)
            }
            if (o.optString("doctor").isNotBlank())
                Text(o.optString("doctor"), fontSize = 8.5.sp, color = ZxT.Text2)
            Spacer(Modifier.height(5.dp))
            Row(Modifier.fillMaxWidth()) {
                Text("${slots.size} 个时段", fontSize = 8.sp, color = ZxT.Text3,
                    modifier = Modifier.weight(1f))
                Text(if (total == 0) "**已满**" else "余 $total 个",
                    fontSize = 8.5.sp, color = if (total == 0) ZxT.Err else ZxT.Ok)
            }
            Spacer(Modifier.height(4.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                var k = 0
                while (k < slots.size) {
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (c in 0 until 3) {
                            if (k + c < slots.size) {
                                val i = k + c
                                val s = slots[i]
                                val left = s.optInt("left", 0)
                                val canPick = left > 0
                                Box(Modifier.weight(1f)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(if (sel == i) ZxT.Primary
                                        else if (!canPick) ZxT.Surface2.copy(alpha = .4f)
                                        else ZxT.Surface2)
                                    .clickable {
                                        if (!canPick) return@clickable
                                        sel = i
                                        ctx.callTool("zx_book_slot", mapOf(
                                            "time" to s.optString("t", ""),
                                            "dept" to o.optString("dept", ""),
                                            "doctor" to o.optString("doctor", "")))
                                    }.padding(5.dp, 4.dp),
                                    contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(s.optString("t", ""), fontSize = 9.sp,
                                            color = if (sel == i) Color.White
                                                else if (!canPick) ZxT.Text3 else ZxT.Text)
                                        Text(if (left <= 0) "满" else "余 $left",
                                            fontSize = 7.5.sp,
                                            color = if (sel == i) Color.White.copy(alpha = .8f)
                                                else if (left <= 0) ZxT.Text3 else ZxT.Ok)
                                    }
                                }
                            } else Spacer(Modifier.weight(1f))
                        }
                    }
                    k += 3
                    Spacer(Modifier.height(4.dp))
                }
            }
            if (sel >= 0)
                Text("已选 ${slots[sel].optString("t", "")}", fontSize = 9.sp,
                    color = ZxT.PriLite, modifier = Modifier.padding(top = 3.dp))
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (slots.isEmpty()) ZxT.Err.copy(.10f)
                    else if (total == 0) ZxT.Warn.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (slots.isEmpty()) "✗" else if (total == 0) "!" else "✓",
                    fontSize = 11.sp,
                    color = if (slots.isEmpty()) ZxT.Err
                        else if (total == 0) ZxT.Warn else ZxT.Ok)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (slots.isEmpty()) "**没有时段**——空的号源表"
                        else if (total == 0) "**全天已满**——" +
                            "**这时候该直接说「没号了」，而不是让用户一个个点**"
                        else "${slots.size} 个时段，$full 个已满，余 $total 个",
                        fontSize = 9.sp,
                        color = if (slots.isEmpty()) ZxT.Err
                            else if (total == 0) ZxT.Warn else ZxT.Ok)
                    Text("**满的时段要能一眼看出且点不动**——" +
                        "能点但预约失败，用户会以为是自己手慢",
                        fontSize = 8.5.sp, color = ZxT.Text3)
                }
            }
            Text("已有 agenda 是<b>日程</b>、seat 是<b>座位图</b>，" +
                "这个是<b>预约挂号</b>：时段 + 余号 + <b>满的不可选</b>。" +
                "<b>无时段会被拦</b>；<b>余号为负会被拦</b>。",
                fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
    }
}
