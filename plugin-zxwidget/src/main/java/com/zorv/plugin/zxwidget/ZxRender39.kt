package com.zorv.plugin.zxwidget

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import kotlin.math.max

/**
 * 第三十九批组件（12 个）—— 责任在两个主体之间转移。
 *
 * ── 它和第三十七、三十八批的差别 ──────────────────────────────
 *
 * 第三十七批做的是**事情会正常推进**的那部分：装修按阶段走、
 * 就医排队等、报修派单上门。它的核心是"现在卡在谁手上、能不能催"。
 *
 * 第三十八批做的是**事情不会正常推进**的那部分：快递丢了、
 * 搬家磕坏了、报销被退了。它的核心是"证据窗口还剩多久"。
 *
 * 这两批有个共同的隐含前提：**责任归属是清楚的**。
 * 三十七批说"卡在物业手上"，三十八批说"要留开箱照片"——
 * 都默认"谁该负责"这件事没有争议。
 *
 * 这批是第三半：**责任本身正在转移，而转移的那一刻最容易出事**。
 *
 *   宠物寄养  → 猫交出去时是好的，接回来时瘦了 0.3kg
 *   退租结算  → 房东说墙面有洞，租客说搬进来就有
 *   设备归还  → 出租方说划伤是新的，承租方说是旧的
 *   值班交接  → 上一班说没异常，这一班一开机就报警
 *
 * ── 贯穿这批的一条 ──────────────────────────────────────────
 *
 * **交接要留下双方都认的快照——只打勾没人签字，事后就是互相甩锅。**
 *
 * 一份清单，打勾完事，和"双方对同一个状态达成共识"是两件事：
 *
 *   打勾：我记着我交了什么       → 对方不认就没有用
 *   签字：双方在同一时刻认同一个状态 → 后来出问题能定位到哪一次
 *
 * 所以这批每个组件都要有三样东西：
 *
 *   1. **交接时的状态快照**（不只是清单，是状态值）
 *   2. **双方确认**（不是一方记完就行）
 *   3. **差异当场记**（接手方发现不符，当场写下来）
 *
 * ── 校验因此换了第十七档 ────────────────────────────────────
 *
 * 前十六档一路查到"证据窗口过期了却没提示"，
 * 这批查的是**「只记了清单，没有双方确认」**：
 *
 *   handoff    缺状态快照（只有清单）/ 缺一方签字 / 缺接手方异议入口
 *   moveout    缺押金抵扣明细 / 缺双方确认 / 扣项无依据
 *   vehiclecare 缺里程或日期 / 项目未分必做与建议 / 缺下次触发
 *   medremote  缺备案有效期 / 缺报销比例变化 / 缺就医地
 *   housedeal  缺资金监管节点 / 节点无金额 / 缺双方确认
 *   schoolup   缺报名窗口 / 缺材料清单 / 窗口已过未标
 *   carerota   缺交接记录 / 排班有空档 / 缺责任人
 *   taxxborder 缺免征额 / 税额算不出 / 缺税率依据
 *   rentreturn 缺归还状态快照 / 缺损坏判定 / 缺双方确认
 *   dutyrota   换班无双方同意 / 排班有空档 / 缺交接班记录
 *   annualaudit 缺年审截止 / 缺材料 / 已过期未标后果
 *   liability  责任比例合计 ≠ 100 / 缺依据 / 缺双方确认
 */
object ZxRender39 {

    private val PAL = listOf(
        Color(0xFF5046E4), Color(0xFF3FBF7F), Color(0xFFE8A33D),
        Color(0xFFE5544B), Color(0xFF4A9BE8), Color(0xFF8E86FF)
    )

    private fun jarr(o: JSONObject, k: String): List<JSONObject> {
        val a = o.optJSONArray(k) ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }

    /**
     * 双方确认条——这批每个组件都要有。
     * 它回答"谁确认了 / 另一方确认了没有 / 差在哪"。
     */
    @Composable
    private fun attest(a: String, b: String, aOk: Boolean, bOk: Boolean,
                       diff: String, onA: () -> Unit, onB: () -> Unit) {
        val both = aOk && bOk
        val col = if (both) ZxT.Ok else if (aOk || bOk) ZxT.Warn else ZxT.Text3
        Column(Modifier.fillMaxWidth().padding(top = 6.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(col.copy(alpha = if (both) .10f else .08f))
            .padding(7.dp, 5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("双方确认", fontSize = 8.sp, color = ZxT.Text3,
                    modifier = Modifier.weight(1f))
                Text(if (both) "已达成共识" else if (aOk || bOk) "**还差一方**" else "都没确认",
                    fontSize = 8.sp, color = col)
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth()) {
                Box(Modifier.weight(1f).padding(end = 3.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (aOk) ZxT.Ok.copy(.20f) else ZxT.Surface2)
                    .clickable(onClick = onA).padding(6.dp, 4.dp)) {
                    Column {
                        Text(a, fontSize = 9.sp, color = ZxT.Text,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (aOk) "已签字 ✓" else "待签字", fontSize = 8.sp,
                            color = if (aOk) ZxT.Ok else ZxT.Text3)
                    }
                }
                Box(Modifier.weight(1f).padding(start = 3.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (bOk) ZxT.Ok.copy(.20f) else ZxT.Surface2)
                    .clickable(onClick = onB).padding(6.dp, 4.dp)) {
                    Column {
                        Text(b, fontSize = 9.sp, color = ZxT.Text,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (bOk) "已签字 ✓" else "待签字", fontSize = 8.sp,
                            color = if (bOk) ZxT.Ok else ZxT.Text3)
                    }
                }
            }
            if (diff.isNotBlank())
                Text("异议：$diff", fontSize = 8.5.sp, color = ZxT.Warn,
                    lineHeight = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }

    @Composable
    private fun verdict(ok: Boolean, warn: Boolean = false,
                        title: String, why: String) {
        val col = if (!ok) ZxT.Err else if (warn) ZxT.Warn else ZxT.Ok
        Row(Modifier.fillMaxWidth().padding(top = 5.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(col.copy(alpha = .10f))
            .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (!ok) "✗" else if (warn) "!" else "✓", fontSize = 11.sp, color = col)
            Spacer(Modifier.width(5.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 9.sp, color = col, lineHeight = 12.sp)
                Text(why, fontSize = 8.5.sp, color = ZxT.Text3, lineHeight = 12.sp)
            }
        }
    }

    @Composable
    private fun note(s: String) {
        Text(s, fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
            modifier = Modifier.padding(top = 6.dp))
    }

    /* ══════════ zx_handoff 交接清单 ══════════ */
    @Composable
    fun handoff(o: JSONObject, ctx: RenderContext) {
        // items:{n:物品, v:交接时状态值, ok:接手方认不认}
        val items = jarr(o, "items")
        val ok = remember { mutableStateListOf(*Array(items.size) { true }) }
        var sel by remember { mutableIntStateOf(-1) }
        var aOk by remember { mutableStateOf(false) }
        var bOk by remember { mutableStateOf(false) }
        val noSnap = items.count { it.optString("v", "").isBlank() }
        val doubt = ok.count { !it }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "交接"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(if (doubt > 0) "**$doubt 项有争议**" else "${items.size} 项",
                    fontSize = 8.5.sp,
                    color = if (doubt > 0) ZxT.Warn else ZxT.Text3)
            }
            Spacer(Modifier.height(5.dp))
            items.forEachIndexed { k, it ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2
                        else if (!ok[k]) ZxT.Warn.copy(.07f) else Color.Transparent)
                    .clickable {
                        ok[k] = !ok[k]; sel = k
                        ctx.callTool("zx_handoff_item", mapOf(
                            "item" to it.optString("n", ""),
                            "state" to it.optString("v", ""), "agree" to ok[k]))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp))
                        .background(if (ok[k]) ZxT.Ok else ZxT.Warn))
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text(it.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text)
                        // 状态快照——不只是名字，是交接那一刻的值
                        Text(if (it.optString("v", "").isBlank()) "**没记状态**"
                            else it.optString("v", ""),
                            fontSize = 8.5.sp,
                            color = if (it.optString("v", "").isBlank()) ZxT.Err else ZxT.Text2)
                    }
                    Text(if (ok[k]) "认" else "**有争议**", fontSize = 8.sp,
                        color = if (ok[k]) ZxT.Ok else ZxT.Warn)
                }
            }
            attest(o.optString("from", "交出方"), o.optString("to", "接手方"),
                aOk, bOk, o.optString("diff", ""),
                { aOk = !aOk }, { bOk = !bOk })
            verdict(noSnap == 0 && aOk && bOk && doubt == 0,
                warn = noSnap == 0 && doubt == 0 && !(aOk && bOk),
                title = when {
                    noSnap > 0 -> "**${noSnap} 项只记了名字没记状态**——" +
                        "**「猫粮」和「猫粮还剩 1.2kg」是两回事**"
                    doubt > 0 -> "**$doubt 项有争议**，当场写下来才有用"
                    !(aOk && bOk) -> "**还差一方签字**——只打勾没人签字，事后就是互相甩锅"
                    else -> "双方已对 ${items.size} 项状态达成共识"
                },
                why = "**交接要留下双方都认的快照**——" +
                    "我记着我交了什么，对方不认就没有用")
            note("已有 checklist 是<b>自查清单</b>、inventory 是<b>库存</b>，" +
                "这个是<b>交接清单</b>：<b>状态快照</b> + <b>双方签字</b> + 异议入口。" +
                "<b>缺状态值会被拦</b>；<b>缺交接双方会被拦</b>。")
        }
    }

    /* ══════════ zx_moveout 退租结算 ══════════ */
    @Composable
    fun moveout(o: JSONObject, ctx: RenderContext) {
        // ded:{n:扣项, amt:金额, why:依据, ok:租客认不认}
        val ded = jarr(o, "ded")
        val ok = remember { mutableStateListOf(*Array(ded.size) { it != 0 }) }
        var sel by remember { mutableIntStateOf(-1) }
        var aOk by remember { mutableStateOf(false) }
        var bOk by remember { mutableStateOf(false) }
        val dep = o.optDouble("dep", 0.0)
        val cut = ded.sumOf { it.optDouble("amt", 0.0) }
        val back = dep - cut
        val noWhy = ded.count { it.optString("why", "").isBlank() }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "退租结算"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(if (back < 0) "**还要补 ${"%.0f".format(-back)}**" else "退 ${"%.0f".format(back)}",
                    fontSize = 8.5.sp, color = if (back < 0) ZxT.Err else ZxT.Ok)
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth()) {
                listOf(Triple("押金", dep, ZxT.Text),
                    Triple("扣款", cut, if (cut > 0) ZxT.Err else ZxT.Text2),
                    Triple("应退", back, if (back < 0) ZxT.Err else ZxT.Ok)
                ).forEach { (l, v, c) ->
                    Column(Modifier.weight(1f).padding(end = 3.dp)
                        .clip(RoundedCornerShape(5.dp)).background(ZxT.Surface2)
                        .padding(5.dp, 4.dp)) {
                        Text("${"%.0f".format(v)}", fontSize = 12.sp, color = c,
                            fontFamily = ZxT.Mono)
                        Text(l, fontSize = 7.5.sp, color = ZxT.Text3)
                    }
                }
            }
            Spacer(Modifier.height(5.dp))
            ded.forEachIndexed { k, d ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2
                        else if (!ok[k]) ZxT.Warn.copy(.08f) else Color.Transparent)
                    .clickable {
                        ok[k] = !ok[k]; sel = k
                        ctx.callTool("zx_moveout_ded", mapOf(
                            "item" to d.optString("n", ""),
                            "amt" to "%.0f".format(d.optDouble("amt", 0.0)),
                            "agree" to ok[k]))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp))
                        .background(if (ok[k]) ZxT.Ok else ZxT.Warn))
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(d.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text,
                                modifier = Modifier.weight(1f), maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text("−${"%.0f".format(d.optDouble("amt", 0.0))}",
                                fontSize = 9.sp, color = ZxT.Err, fontFamily = ZxT.Mono)
                        }
                        Text(if (d.optString("why", "").isBlank()) "**没写依据**"
                            else d.optString("why", ""),
                            fontSize = 8.sp,
                            color = if (d.optString("why", "").isBlank()) ZxT.Err else ZxT.Text3,
                            lineHeight = 11.sp)
                    }
                    Text(if (ok[k]) "认" else "**不认**", fontSize = 8.sp,
                        color = if (ok[k]) ZxT.Ok else ZxT.Warn)
                }
            }
            attest(o.optString("landlord", "房东"), o.optString("tenant", "租客"),
                aOk, bOk, o.optString("diff", ""), { aOk = !aOk }, { bOk = !bOk })
            verdict(noWhy == 0 && aOk && bOk,
                warn = noWhy == 0 && !(aOk && bOk),
                title = when {
                    noWhy > 0 -> "**${noWhy} 项扣款没写依据**——" +
                        "**「墙面有洞」和「墙面有洞，搬进来就有」是两回事**"
                    !(aOk && bOk) -> "**还差一方签字**——押金扣多少，两边得认同一个数"
                    else -> "双方认了：扣 ${"%.0f".format(cut)}，退 ${"%.0f".format(back)}"
                },
                why = "**退租结算的争议几乎都出在「搬进来时什么样」**——" +
                    "没有入住时的快照，房东说什么就是什么")
            note("已有 lease 是<b>合同要点</b>、deposit 是<b>押金</b>，" +
                "这个是<b>退租结算</b>：扣项 + <b>依据</b> + <b>双方签字</b>。" +
                "<b>扣项缺依据会被拦</b>；<b>扣款超过押金会被拦</b>。")
        }
    }

    /* ══════════ zx_vehiclecare 车辆维保 ══════════ */
    @Composable
    fun vehiclecare(o: JSONObject, ctx: RenderContext) {
        // items:{n:项目, km:下次里程, mo:下次月数, need:必做/建议, cur:当前里程}
        val items = jarr(o, "items")
        var sel by remember { mutableIntStateOf(-1) }
        val cur = o.optInt("cur", 0)
        val must = items.count { it.optBoolean("need", false) }
        val due = items.count {
            it.optInt("km", Int.MAX_VALUE) <= cur || it.optInt("mo", 99) <= 0 }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "维保"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(if (due > 0) "**$due 项到期**" else "都还早", fontSize = 8.5.sp,
                    color = if (due > 0) ZxT.Warn else ZxT.Ok)
            }
            Text("当前 ${cur} km　**里程和时间哪个先到算哪个**", fontSize = 8.sp,
                color = ZxT.Text3, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.height(5.dp))
            items.forEachIndexed { k, it ->
                val km = it.optInt("km", Int.MAX_VALUE)
                val mo = it.optInt("mo", 99)
                val dk = km - cur
                val od = dk <= 0 || mo <= 0
                val nd = it.optBoolean("need", false)
                Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2
                        else if (od) ZxT.Warn.copy(.07f) else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_vehicle_item", mapOf(
                            "item" to it.optString("n", ""),
                            "nextKm" to km, "nextMonths" to mo, "must" to nd))
                    }.padding(6.dp, 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(it.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text,
                            modifier = Modifier.weight(1f), maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        Box(Modifier.clip(RoundedCornerShape(3.dp))
                            .background(if (nd) ZxT.Primary.copy(.24f) else ZxT.Surface2)
                            .padding(4.dp, 1.dp)) {
                            Text(if (nd) "必做" else "建议", fontSize = 7.5.sp,
                                color = if (nd) ZxT.PriLite else ZxT.Text3)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (dk <= 0) "**里程已到**" else "还有 ${dk} km",
                            fontSize = 8.sp,
                            color = if (dk <= 0) ZxT.Err else ZxT.Text3)
                        Spacer(Modifier.width(8.dp))
                        Text(if (mo <= 0) "**时间已到**" else "还有 ${mo} 个月",
                            fontSize = 8.sp,
                            color = if (mo <= 0) ZxT.Err else ZxT.Text3)
                    }
                }
            }
            attest(o.optString("shop", "门店"), o.optString("owner", "车主"),
                false, false, "", {}, {})
            verdict(due == 0, warn = due > 0,
                title = if (due > 0) "**$due 项到期**——其中 $must 项必做"
                    else "都在周期内",
                why = "**维保要区分必做和建议**——" +
                    "混在一起，用户要么全做花冤枉钱，要么全不做留下隐患")
            note("已有 maintenance 是<b>保养记录</b>，" +
                "这个是<b>车辆维保</b>：<b>里程和时间双触发</b> + 必做/建议分级。" +
                "<b>缺当前里程会被拦</b>；<b>缺下次触发会被拦</b>。")
        }
    }

    /* ══════════ zx_medremote 异地就医备案 ══════════ */
    @Composable
    fun medremote(o: JSONObject, ctx: RenderContext) {
        // place:就医地  leftH:备案剩余小时  rate:报销比例  local:本地比例
        val leftH = o.optInt("leftH", 0)
        val rate = o.optDouble("rate", 0.0)
        val local = o.optDouble("local", 0.0)
        val gone = leftH <= 0
        var sel by remember { mutableIntStateOf(-1) }
        val need = jarr(o, "need")

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "异地备案"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(if (gone) "**已过期**" else "剩 ${leftH / 24} 天", fontSize = 8.5.sp,
                    color = if (gone) ZxT.Err else if (leftH <= 168) ZxT.Warn else ZxT.Ok)
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth()) {
                listOf(Triple("就医地", o.optString("place", "—"), ZxT.Text),
                    Triple("异地报销", "${"%.0f".format(rate * 100)}%",
                        if (rate < local) ZxT.Warn else ZxT.Ok),
                    Triple("本地报销", "${"%.0f".format(local * 100)}%", ZxT.Text2)
                ).forEach { (l, v, c) ->
                    Column(Modifier.weight(1f).padding(end = 3.dp)
                        .clip(RoundedCornerShape(5.dp)).background(ZxT.Surface2)
                        .padding(5.dp, 4.dp)) {
                        Text(v, fontSize = 12.sp, color = c)
                        Text(l, fontSize = 7.5.sp, color = ZxT.Text3)
                    }
                }
            }
            if (rate < local)
                Text("**异地比本地少报 ${"%.0f".format((local - rate) * 100)} 个百分点**——" +
                    "不是不能报，是报得少", fontSize = 8.sp, color = ZxT.Warn,
                    lineHeight = 11.sp, modifier = Modifier.padding(top = 3.dp))
            Spacer(Modifier.height(5.dp))
            Text("要带的材料", fontSize = 9.sp, color = ZxT.Text3)
            need.forEachIndexed { k, nd ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_medremote_need", mapOf(
                            "item" to nd.optString("n", ""),
                            "must" to nd.optBoolean("must", false)))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp))
                        .background(if (nd.optBoolean("must", false)) ZxT.Warn else ZxT.Surface3))
                    Spacer(Modifier.width(6.dp))
                    Text(nd.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text,
                        modifier = Modifier.weight(1f))
                    if (nd.optBoolean("must", false))
                        Text("必需", fontSize = 8.sp, color = ZxT.Warn)
                }
            }
            attest(o.optString("insurer", "参保地"), o.optString("hospital", "就医地医院"),
                false, false, "", {}, {})
            verdict(!gone,
                title = when {
                    gone -> "**备案已过期**——**过期后要么全自费，" +
                        "要么回去补办再手工报销**"
                    rate < local -> "能报，但比本地少 " +
                        "${"%.0f".format((local - rate) * 100)} 个百分点"
                    else -> "备案有效，报销比例不降"
                },
                why = "**异地备案要说清两件事：有效期 + 报销比例降多少**——" +
                    "只说「已备案」，用户不知道过期会怎样、会少报多少")
            note("已有 escort 是<b>陪诊</b>、insurance 是<b>保单</b>，" +
                "这个是<b>异地就医备案</b>：<b>有效期</b> + <b>报销比例变化</b> + 材料。" +
                "<b>缺备案有效期会被拦</b>；<b>缺报销比例对比会被拦</b>。")
        }
    }

    /* ══════════ zx_housedeal 二手房交易 ══════════ */
    @Composable
    fun housedeal(o: JSONObject, ctx: RenderContext) {
        // nodes:{n:节点, amt:金额, escrow:是否资金监管, s:0未做 1进行 2完成}
        val nodes = jarr(o, "nodes")
        val st = remember { mutableStateListOf(*Array(nodes.size) { 0 }) }
        var sel by remember { mutableIntStateOf(-1) }
        var aOk by remember { mutableStateOf(false) }
        var bOk by remember { mutableStateOf(false) }
        val total = nodes.sumOf { it.optDouble("amt", 0.0) }
        val esc = nodes.count { it.optBoolean("escrow", false) }
        val cur = st.indexOfFirst { it < 2 }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "交易节点"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text("$esc 项走监管", fontSize = 8.5.sp, color = ZxT.Info)
            }
            Spacer(Modifier.height(5.dp))
            nodes.forEachIndexed { k, nd ->
                val c = when (st[k]) { 2 -> ZxT.Ok; 1 -> ZxT.Warn; else -> ZxT.Text3 }
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        st[k] = (st[k] + 1) % 3; sel = k
                        ctx.callTool("zx_housedeal_node", mapOf(
                            "node" to nd.optString("n", ""),
                            "amt" to "%.0f".format(nd.optDouble("amt", 0.0)),
                            "escrow" to nd.optBoolean("escrow", false),
                            "state" to st[k]))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(c))
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(nd.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text,
                                modifier = Modifier.weight(1f), maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text(if (nd.optDouble("amt", 0.0) > 0)
                                "${"%.0f".format(nd.optDouble("amt", 0.0))} 万" else "—",
                                fontSize = 9.sp, color = ZxT.Text2, fontFamily = ZxT.Mono)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(when (st[k]) { 2 -> "已完成"; 1 -> "进行中"; else -> "未开始" },
                                fontSize = 8.sp, color = c)
                            if (nd.optBoolean("escrow", false)) {
                                Spacer(Modifier.width(6.dp))
                                Text("资金监管", fontSize = 8.sp, color = ZxT.Info)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(3.dp))
            Text("合计 ${"%.0f".format(total)} 万", fontSize = 9.sp, color = ZxT.Text3)
            attest(o.optString("buyer", "买方"), o.optString("seller", "卖方"),
                aOk, bOk, o.optString("diff", ""), { aOk = !aOk }, { bOk = !bOk })
            verdict(esc > 0 && aOk && bOk,
                warn = esc > 0 && !(aOk && bOk),
                title = when {
                    esc == 0 -> "**没有任何节点走资金监管**——" +
                        "**首付直接打给卖方，出问题追不回来**"
                    !(aOk && bOk) -> "**还差一方确认**——第 ${cur + 1} 个节点进行中"
                    else -> "$esc 个节点走监管，双方已确认"
                },
                why = "**二手房的资金安全全在「哪一步走监管」**——" +
                    "只列节点不标监管，用户不知道钱在哪一步是裸的")
            note("已有 lease 是<b>租房合同</b>，" +
                "这个是<b>二手房交易</b>：节点 + <b>资金监管标记</b> + 双方确认。" +
                "<b>无任何监管节点会被拦</b>；<b>节点无金额会被拦</b>。")
        }
    }

    /* ══════════ zx_schoolup 升学流程 ══════════ */
    @Composable
    fun schoolup(o: JSONObject, ctx: RenderContext) {
        // win:报名窗口  leftH:剩余小时  need:{n:材料, got:有/无, must:必需}
        val need = jarr(o, "need")
        val got = remember { mutableStateListOf(*Array(need.size) { it < need.size - 1 }) }
        var sel by remember { mutableIntStateOf(-1) }
        val leftH = o.optInt("leftH", 0)
        val gone = leftH <= 0
        val left = got.count { !it }
        val mustLeft = need.mapIndexedNotNull { i, n ->
            if (n.optBoolean("must", false) && !got[i]) i else null }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "升学"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(if (gone) "**窗口已过**" else "剩 ${leftH / 24} 天", fontSize = 8.5.sp,
                    color = if (gone) ZxT.Err else if (leftH <= 168) ZxT.Warn else ZxT.Ok)
            }
            Row(Modifier.fillMaxWidth().padding(top = 3.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (gone) ZxT.Err.copy(.09f) else ZxT.Surface2)
                .padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("窗口", fontSize = 8.sp, color = ZxT.Text3)
                Spacer(Modifier.width(5.dp))
                Text(o.optString("win", "—"), fontSize = 9.sp,
                    color = if (gone) ZxT.Err else ZxT.Text)
            }
            Spacer(Modifier.height(5.dp))
            need.forEachIndexed { k, nd ->
                val m = nd.optBoolean("must", false)
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2
                        else if (m && !got[k]) ZxT.Warn.copy(.07f) else Color.Transparent)
                    .clickable {
                        got[k] = !got[k]; sel = k
                        ctx.callTool("zx_schoolup_need", mapOf(
                            "item" to nd.optString("n", ""),
                            "got" to got[k], "must" to m))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp))
                        .background(if (got[k]) ZxT.Ok else ZxT.Surface3))
                    Spacer(Modifier.width(6.dp))
                    Text(nd.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text,
                        modifier = Modifier.weight(1f), lineHeight = 12.sp)
                    Text(if (got[k]) "有" else "缺", fontSize = 8.sp,
                        color = if (got[k]) ZxT.Ok else ZxT.Warn)
                    if (m) Text("　必", fontSize = 7.5.sp, color = ZxT.Err)
                }
            }
            attest(o.optString("school", "学校"), o.optString("parent", "家长"),
                false, false, "", {}, {})
            verdict(!gone && mustLeft.isEmpty(), warn = !gone && mustLeft.isNotEmpty(),
                title = when {
                    gone -> "**报名窗口已过**——**错过就是晚一年**，" +
                        "现在该问的是有没有补录"
                    mustLeft.isNotEmpty() -> "**还缺 ${mustLeft.size} 项必需材料**，" +
                        "剩 ${leftH / 24} 天"
                    else -> "必需材料齐了，剩 ${leftH / 24} 天"
                },
                why = "**升学要区分必需和可后补**——" +
                    "全部平权列出，用户不知道缺哪一样会直接报不上")
            note("已有 interview 是<b>面试</b>，" +
                "这个是<b>升学流程</b>：<b>报名窗口</b> + 材料 + <b>必需/可后补</b>。" +
                "<b>缺报名窗口会被拦</b>；<b>过期不标后果会被拦</b>；<b>无必需材料会被拦</b>。")
        }
    }

    /* ══════════ zx_carerota 照护排班 ══════════ */
    @Composable
    fun carerota(o: JSONObject, ctx: RenderContext) {
        // slots:{d:日期, who:责任人, hand:交接是否完成}
        val slots = jarr(o, "slots")
        val hand = remember { mutableStateListOf(*Array(slots.size) { it < slots.size - 2 }) }
        var sel by remember { mutableIntStateOf(-1) }
        val gap = slots.count { it.optString("who", "").isBlank() }
        val noHand = hand.count { !it }
        val byWho = slots.map { it.optString("who", "") }.filter { it.isNotBlank() }
            .groupingBy { it }.eachCount()

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "照护排班"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(if (gap > 0) "**$gap 天没人**" else "${slots.size} 天排满",
                    fontSize = 8.5.sp, color = if (gap > 0) ZxT.Err else ZxT.Ok)
            }
            Spacer(Modifier.height(4.dp))
            Row {
                byWho.forEach { (w, c) ->
                    Box(Modifier.padding(end = 4.dp).clip(RoundedCornerShape(4.dp))
                        .background(ZxT.Surface2).padding(6.dp, 3.dp)) {
                        Text("$w $c 天", fontSize = 8.sp, color = ZxT.Text2)
                    }
                }
            }
            Spacer(Modifier.height(5.dp))
            slots.forEachIndexed { k, s ->
                val w = s.optString("who", "")
                val blank = w.isBlank()
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2
                        else if (blank) ZxT.Err.copy(.08f)
                        else if (!hand[k]) ZxT.Warn.copy(.06f) else Color.Transparent)
                    .clickable {
                        if (!blank) hand[k] = !hand[k]
                        sel = k
                        ctx.callTool("zx_carerota_slot", mapOf(
                            "day" to s.optString("d", ""), "who" to w,
                            "handoff" to if (blank) false else hand[k]))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(s.optString("d", ""), fontSize = 9.sp, color = ZxT.Text,
                        modifier = Modifier.width(44.dp), fontFamily = ZxT.Mono)
                    Column(Modifier.weight(1f)) {
                        Text(if (blank) "**没人**" else w, fontSize = 9.5.sp,
                            color = if (blank) ZxT.Err else ZxT.Text)
                        if (!blank)
                            Text(if (hand[k]) "交接完成" else "**还没交接**",
                                fontSize = 8.sp,
                                color = if (hand[k]) ZxT.Ok else ZxT.Warn)
                    }
                }
            }
            attest(o.optString("prevWho", "上一班"), o.optString("nextWho", "下一班"),
                false, false, o.optString("diff", ""), {}, {})
            verdict(gap == 0 && noHand == 0,
                warn = gap == 0 && noHand > 0,
                title = when {
                    gap > 0 -> "**${gap} 天没人排班**——" +
                        "**照护断一天，老人就真的没人管**"
                    noHand > 0 -> "**${noHand} 天还没交接**——" +
                        "**上一班说没异常，这一班一接班就出问题**"
                    else -> "排满且都交接了"
                },
                why = "**照护排班要能看出「谁接谁的班」**——" +
                    "只排人名不记交接，出问题就变成互相甩锅")
            note("已有 dutyrota 是<b>值班表</b>，" +
                "这个是<b>老人照护排班</b>：按天 + <b>交接完成标记</b> + 空档检测。" +
                "<b>有空档会被拦</b>；<b>缺责任人会被拦</b>。")
        }
    }

    /* ══════════ zx_taxxborder 跨境购税务 ══════════ */
    @Composable
    fun taxxborder(o: JSONObject, ctx: RenderContext) {
        // items:{n:商品, price:单价, qty:数量, rate:税率}  free:免征额
        val items = jarr(o, "items")
        var sel by remember { mutableIntStateOf(-1) }
        val total = items.sumOf { it.optDouble("price", 0.0) * it.optInt("qty", 0) }
        val free = o.optDouble("free", 0.0)
        val taxable = max(0.0, total - free)
        val tax = items.sumOf {
            it.optDouble("price", 0.0) * it.optInt("qty", 0) * it.optDouble("rate", 0.0) }
        val over = total > free

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "跨境税"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(if (over) "**超免征额**" else "在免征额内", fontSize = 8.5.sp,
                    color = if (over) ZxT.Warn else ZxT.Ok)
            }
            Spacer(Modifier.height(4.dp))
            // 免征额进度条
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${"%.0f".format(total)}", fontSize = 15.sp, color = ZxT.Text,
                        fontFamily = ZxT.Mono)
                    Text("　/ 免征 ${"%.0f".format(free)}", fontSize = 8.sp,
                        color = ZxT.Text3, modifier = Modifier.padding(bottom = 2.dp))
                    Spacer(Modifier.weight(1f))
                    Text("${"%.0f".format(if (free > 0) total / free * 100 else 0.0)}%",
                        fontSize = 11.sp,
                        color = if (over) ZxT.Err else ZxT.Ok, fontFamily = ZxT.Mono)
                }
                Spacer(Modifier.height(3.dp))
                Box(Modifier.fillMaxWidth().height(6.dp)
                    .clip(RoundedCornerShape(3.dp)).background(ZxT.Surface3)) {
                    Box(Modifier.fillMaxWidth((total / free.coerceAtLeast(1.0))
                        .toFloat().coerceIn(0f, 1f))
                        .height(6.dp).clip(RoundedCornerShape(3.dp))
                        .background(if (over) ZxT.Err else ZxT.Ok))
                }
            }
            Spacer(Modifier.height(5.dp))
            items.forEachIndexed { k, it ->
                val sub = it.optDouble("price", 0.0) * it.optInt("qty", 0)
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_tax_item", mapOf(
                            "item" to it.optString("n", ""),
                            "subtotal" to "%.2f".format(sub),
                            "rate" to it.optDouble("rate", 0.0)))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(it.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${"%.2f".format(it.optDouble("price", 0.0))} × " +
                            "${it.optInt("qty", 0)}　税率 " +
                            "${"%.0f".format(it.optDouble("rate", 0.0) * 100)}%",
                            fontSize = 8.sp, color = ZxT.Text3)
                    }
                    Text("${"%.2f".format(sub)}", fontSize = 9.sp, color = ZxT.Text2,
                        fontFamily = ZxT.Mono)
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                .background(if (over) ZxT.Warn.copy(.10f) else ZxT.Ok.copy(.10f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("应缴", fontSize = 9.sp, color = ZxT.Text3,
                    modifier = Modifier.weight(1f))
                Text(if (over) "**${"%.2f".format(tax)}**" else "0（在免征额内）",
                    fontSize = 11.sp, color = if (over) ZxT.Warn else ZxT.Ok,
                    fontFamily = ZxT.Mono)
            }
            attest(o.optString("buyer", "买家"), o.optString("platform", "平台"),
                false, false, "", {}, {})
            verdict(true, warn = over,
                title = if (over) "超出免征额 ${"%.0f".format(total - free)}，" +
                    "应缴 ${"%.2f".format(tax)}"
                    else "在免征额内，不用交",
                why = "**跨境购要显示免征额用了多少**——" +
                    "只给一个总价，用户不知道再加一件会不会突然要交税")
            note("已有 bill 是<b>账单</b>，" +
                "这个是<b>跨境购税务</b>：<b>免征额进度</b> + 分商品税率 + 应缴。" +
                "<b>缺免征额会被拦</b>；<b>缺税率会被拦</b>；<b>小计与单价×数量对不上会被拦</b>。")
        }
    }

    /* ══════════ zx_rentreturn 租赁设备归还 ══════════ */
    @Composable
    fun rentreturn(o: JSONObject, ctx: RenderContext) {
        // items:{n:设备, out:借出时状态, back:归还时状态, dmg:损坏判定}
        val items = jarr(o, "items")
        val agree = remember { mutableStateListOf(*Array(items.size) { it != items.size - 1 }) }
        var sel by remember { mutableIntStateOf(-1) }
        var aOk by remember { mutableStateOf(false) }
        var bOk by remember { mutableStateOf(false) }
        val noOut = items.count { it.optString("out", "").isBlank() }
        val diff = items.count {
            it.optString("out", "") != it.optString("back", "") &&
                it.optString("dmg", "").isNotBlank() }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "设备归还"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(if (diff > 0) "**$diff 项状态变了**" else "状态一致",
                    fontSize = 8.5.sp, color = if (diff > 0) ZxT.Warn else ZxT.Ok)
            }
            Spacer(Modifier.height(5.dp))
            items.forEachIndexed { k, it ->
                val changed = it.optString("out", "") != it.optString("back", "")
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2
                        else if (!agree[k]) ZxT.Warn.copy(.08f) else Color.Transparent)
                    .clickable {
                        agree[k] = !agree[k]; sel = k
                        ctx.callTool("zx_rentreturn_item", mapOf(
                            "item" to it.optString("n", ""),
                            "outState" to it.optString("out", ""),
                            "backState" to it.optString("back", ""),
                            "agree" to agree[k]))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp))
                        .background(if (agree[k]) ZxT.Ok else ZxT.Warn))
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text(it.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(it.optString("out", "**没记**"), fontSize = 8.sp,
                                color = if (it.optString("out", "").isBlank()) ZxT.Err
                                else ZxT.Text3)
                            Text(" → ", fontSize = 8.sp, color = ZxT.Text3)
                            Text(it.optString("back", ""), fontSize = 8.sp,
                                color = if (changed) ZxT.Warn else ZxT.Text2)
                        }
                        if (changed && it.optString("dmg", "").isNotBlank())
                            Text("判定：${it.optString("dmg", "")}", fontSize = 8.sp,
                                color = ZxT.Warn, lineHeight = 11.sp)
                    }
                    Text(if (agree[k]) "认" else "**不认**", fontSize = 8.sp,
                        color = if (agree[k]) ZxT.Ok else ZxT.Warn)
                }
            }
            attest(o.optString("lessor", "出租方"), o.optString("lessee", "承租方"),
                aOk, bOk, o.optString("diff", ""), { aOk = !aOk }, { bOk = !bOk })
            verdict(noOut == 0 && aOk && bOk,
                warn = noOut == 0 && !(aOk && bOk),
                title = when {
                    noOut > 0 -> "**${noOut} 项没记借出时状态**——" +
                        "**没有借出时的快照，划伤新旧根本说不清**"
                    !(aOk && bOk) -> "**还差一方签字**——" +
                        "$diff 项状态变了，判定得两边都认"
                    else -> "双方认了：$diff 项有变化"
                },
                why = "**设备归还的全部争议都在「划伤是新的还是旧的」**——" +
                    "借出时不拍照，归还时说什么都是你吃亏")
            note("已有 damage 是<b>搬家损坏</b>，" +
                "这个是<b>租赁设备归还</b>：<b>借出 vs 归还状态对比</b> + 损坏判定 + 签字。" +
                "<b>缺借出时状态会被拦</b>；<b>状态变了无判定会被拦</b>。")
        }
    }

    /* ══════════ zx_dutyrota 团队值班 ══════════ */
    @Composable
    fun dutyrota(o: JSONObject, ctx: RenderContext) {
        // slots:{d:日期, who:值班人, alt:替班人, swap:换班是否双方同意}
        val slots = jarr(o, "slots")
        val ok = remember { mutableStateListOf(*Array(slots.size) { it != 1 }) }
        var sel by remember { mutableIntStateOf(-1) }
        val gap = slots.count { it.optString("who", "").isBlank() }
        val swaps = slots.count { it.optString("alt", "").isNotBlank() }
        val noAgree = slots.mapIndexedNotNull { i, s ->
            if (s.optString("alt", "").isNotBlank() && !ok[i]) i else null }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "值班表"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(if (gap > 0) "**$gap 天空档**" else "$swaps 处换班",
                    fontSize = 8.5.sp, color = if (gap > 0) ZxT.Err else ZxT.Text3)
            }
            Spacer(Modifier.height(5.dp))
            slots.forEachIndexed { k, s ->
                val w = s.optString("who", "")
                val alt = s.optString("alt", "")
                val blank = w.isBlank()
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2
                        else if (blank) ZxT.Err.copy(.08f)
                        else if (alt.isNotBlank() && !ok[k]) ZxT.Warn.copy(.07f)
                        else Color.Transparent)
                    .clickable {
                        if (alt.isNotBlank()) ok[k] = !ok[k]
                        sel = k
                        ctx.callTool("zx_dutyrota_slot", mapOf(
                            "day" to s.optString("d", ""), "who" to w,
                            "alt" to alt, "agreed" to if (alt.isBlank()) true else ok[k]))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(s.optString("d", ""), fontSize = 9.sp, color = ZxT.Text,
                        modifier = Modifier.width(46.dp), fontFamily = ZxT.Mono)
                    Column(Modifier.weight(1f)) {
                        Text(if (blank) "**空档**" else w, fontSize = 9.5.sp,
                            color = if (blank) ZxT.Err else ZxT.Text)
                        if (alt.isNotBlank())
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("换班 → $alt", fontSize = 8.sp, color = ZxT.Text2)
                                Spacer(Modifier.width(5.dp))
                                Text(if (ok[k]) "双方同意" else "**还差一方**",
                                    fontSize = 8.sp,
                                    color = if (ok[k]) ZxT.Ok else ZxT.Warn)
                            }
                    }
                }
            }
            attest(o.optString("prevWho", "交班人"), o.optString("nextWho", "接班人"),
                false, false, o.optString("diff", ""), {}, {})
            verdict(gap == 0 && noAgree.isEmpty(),
                warn = gap == 0 && noAgree.isNotEmpty(),
                title = when {
                    gap > 0 -> "**${gap} 天空档**——" +
                        "**值班断一天，线上出问题没人接**"
                    noAgree.isNotEmpty() -> "**${noAgree.size} 处换班还差一方同意**——" +
                        "**一方改了班另一方不知道，那天就没人**"
                    else -> "排满，换班都双方同意了"
                },
                why = "**换班必须双方同意**——" +
                    "只记录谁值哪天，改了班不同步，出事时两个人都不在")
            note("已有 carerota 是<b>照护排班</b>，" +
                "这个是<b>团队值班</b>：换班 + <b>双方同意</b> + 空档检测。" +
                "<b>有空档会被拦</b>；<b>换班缺双方同意会被拦</b>。")
        }
    }

    /* ══════════ zx_annualaudit 证照年审 ══════════ */
    @Composable
    fun annualaudit(o: JSONObject, ctx: RenderContext) {
        // items:{n:证照, leftH:年审剩余小时, need:{n:材料, got:有/无}}
        val items = jarr(o, "items")
        val got = remember { mutableStateListOf(*Array(items.size) { it < items.size - 1 }) }
        var sel by remember { mutableIntStateOf(-1) }
        val expired = items.mapIndexedNotNull { i, it ->
            if (it.optInt("leftH", 9999) <= 0) i else null }
        val soon = items.mapIndexedNotNull { i, it ->
            val lh = it.optInt("leftH", 9999)
            if (lh in 1..(24 * 30)) i else null }
        val left = got.count { !it }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "证照年审"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(if (expired.isNotEmpty()) "**${expired.size} 项已过期**"
                    else if (soon.isNotEmpty()) "${soon.size} 项 30 天内" else "都还早",
                    fontSize = 8.5.sp,
                    color = if (expired.isNotEmpty()) ZxT.Err
                    else if (soon.isNotEmpty()) ZxT.Warn else ZxT.Ok)
            }
            Spacer(Modifier.height(5.dp))
            items.forEachIndexed { k, it ->
                val lh = it.optInt("leftH", 9999)
                val gone = lh <= 0
                val col = if (gone) ZxT.Err
                    else if (lh <= 24 * 30) ZxT.Warn else ZxT.Ok
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2
                        else if (gone) ZxT.Err.copy(.08f)
                        else if (lh <= 24 * 30) ZxT.Warn.copy(.06f) else Color.Transparent)
                    .clickable {
                        got[k] = !got[k]; sel = k
                        ctx.callTool("zx_audit_item", mapOf(
                            "item" to it.optString("n", ""),
                            "hoursLeft" to lh, "ready" to got[k],
                            "miss" to it.optString("miss", "")))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp))
                        .background(if (got[k]) ZxT.Ok else ZxT.Surface3))
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(it.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text,
                                modifier = Modifier.weight(1f), maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text(if (gone) "**已过期**"
                                else if (lh >= 24) "剩 ${lh / 24} 天" else "剩 ${lh} 小时",
                                fontSize = 8.sp, color = col)
                        }
                        Text(if (got[k]) "材料齐了" else "**材料没齐**", fontSize = 8.sp,
                            color = if (got[k]) ZxT.Ok else ZxT.Warn)
                        if (gone)
                            Text(it.optString("miss", "过期了"), fontSize = 8.sp,
                                color = ZxT.Err, lineHeight = 11.sp)
                    }
                }
            }
            attest(o.optString("org", "发证机关"), o.optString("holder", "持证方"),
                false, false, "", {}, {})
            verdict(expired.isEmpty() && left == 0,
                warn = expired.isEmpty() && left > 0,
                title = when {
                    expired.isNotEmpty() -> "**${expired.size} 项已过期**——" +
                        "**证照过期不是补审就行，中间这段是无证状态**"
                    left > 0 -> "$left 项材料没齐，${soon.size} 项 30 天内到期"
                    else -> "都审了"
                },
                why = "**年审要区分「到期」和「材料齐没齐」**——" +
                    "只看日期，临到期才发现材料还得先去别处开")
            note("已有 credential 是<b>证件到期</b>，" +
                "这个是<b>证照年审</b>：<b>到期倒计时</b> + <b>材料齐没齐</b> + 过期后果。" +
                "<b>缺年审截止会被拦</b>；<b>过期不标后果会被拦</b>。")
        }
    }

    /* ══════════ zx_liability 事故责任认定 ══════════ */
    @Composable
    fun liability(o: JSONObject, ctx: RenderContext) {
        // parts:{n:一方, pct:责任比例, why:依据}
        val parts = jarr(o, "parts")
        var sel by remember { mutableIntStateOf(-1) }
        var aOk by remember { mutableStateOf(false) }
        var bOk by remember { mutableStateOf(false) }
        val sum = parts.sumOf { it.optDouble("pct", 0.0) }
        val noWhy = parts.count { it.optString("why", "").isBlank() }
        val mx = parts.maxOf { it.optDouble("pct", 0.0) }.coerceAtLeast(1.0)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "责任认定"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(if (Math.abs(sum - 100.0) < 0.01) "合计 ${"%.0f".format(sum)}%"
                    else "**合计 ${"%.0f".format(sum)}%**",
                    fontSize = 8.5.sp,
                    color = if (Math.abs(sum - 100.0) < 0.01) ZxT.Ok else ZxT.Err)
            }
            Spacer(Modifier.height(5.dp))
            parts.forEachIndexed { k, p ->
                val pct = p.optDouble("pct", 0.0)
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_liability_part", mapOf(
                            "party" to p.optString("n", ""),
                            "pct" to pct, "reason" to p.optString("why", "")))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text,
                                modifier = Modifier.weight(1f), maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text("${"%.0f".format(pct)}%", fontSize = 10.sp,
                                color = PAL[k % PAL.size], fontFamily = ZxT.Mono)
                        }
                        Spacer(Modifier.height(2.dp))
                        Box(Modifier.fillMaxWidth().height(5.dp)
                            .clip(RoundedCornerShape(3.dp)).background(ZxT.Surface3)) {
                            Box(Modifier.fillMaxWidth((pct / mx).toFloat())
                                .height(5.dp).clip(RoundedCornerShape(3.dp))
                                .background(PAL[k % PAL.size]))
                        }
                        Text(if (p.optString("why", "").isBlank()) "**没写依据**"
                            else p.optString("why", ""),
                            fontSize = 8.sp,
                            color = if (p.optString("why", "").isBlank()) ZxT.Err else ZxT.Text3,
                            lineHeight = 11.sp)
                    }
                }
            }
            attest(o.optString("pa", "甲方"), o.optString("pb", "乙方"),
                aOk, bOk, o.optString("diff", ""), { aOk = !aOk }, { bOk = !bOk })
            verdict(noWhy == 0 && Math.abs(sum - 100.0) < 0.01 && aOk && bOk,
                warn = noWhy == 0 && Math.abs(sum - 100.0) < 0.01 && !(aOk && bOk),
                title = when {
                    noWhy > 0 -> "**${noWhy} 方没写依据**——" +
                        "**定比例不写为什么，对方一定不服**"
                    Math.abs(sum - 100.0) >= 0.01 -> "**责任比例合计 " +
                        "${"%.0f".format(sum)}%，不是 100%**"
                    !(aOk && bOk) -> "**还差一方签字**——" +
                        "**认定的比例没人认，等于没认定**"
                    else -> "双方认了：${parts.joinToString(" / ") {"${it.optString("n", "")} ${"%.0f".format(it.optDouble("pct", 0.0))}%" }}"
                },
                why = "**责任认定要比例 + 依据 + 双方签字三件事齐全**——" +
                    "只给比例，不服的一方会一直拖着不赔")
            note("已有 slotconflict 是<b>场地冲突</b>，" +
                "这个是<b>事故责任认定</b>：<b>比例合计必须 100%</b> + 依据 + 签字。" +
                "<b>合计不是 100% 会被拦</b>；<b>缺依据会被拦</b>。")
        }
    }
}
