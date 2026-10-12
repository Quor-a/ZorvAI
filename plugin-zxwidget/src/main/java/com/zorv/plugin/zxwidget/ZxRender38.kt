package com.zorv.plugin.zxwidget

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * 第三十八批组件（12 个）—— 出事了之后怎么办。
 *
 * ── 它和第三十七批的差别 ────────────────────────────────────
 *
 * 第三十七批做的是**事情会正常推进**的那部分：
 * 装修按阶段走、就医排队等、报修派单上门、签证递交审核。
 * 那批的核心是"现在卡在谁手上、能不能催"——
 * **它假设事情最终会往前走**。
 *
 * 这批是另一半：**事情不会正常推进**。
 *
 *   快递丢了 / 包裹破了    → 要索赔，而索赔要证据
 *   搬家磕坏了家具        → 要登记，而登记有时限
 *   装修超支了            → 要止损，而止损要先知道钱花在哪
 *   报销被退回了          → 要重报，而重报有窗口
 *   证件丢了              → 要先挂失才能补办
 *   会议室被抢了          → 要协商，而协商要拿出依据
 *
 * ── 贯穿这批的一条 ──────────────────────────────────────────
 *
 * **出事之后最关键的是"证据留没留下"，而证据有时间窗口。**
 *
 * 第三十七批的"等待态"是被动的——等就完了。
 * 这批不一样：**等着等着，能补救的手段就消失了。**
 *
 *   快递签收 7 天内要拍照，过了就认不了损
 *   搬家当场登记才认，事后说不清是谁磕的
 *   报销退回 30 天内要重交，超期作废
 *   证件被盗和丢失是两套流程，走错了补办会被拒
 *
 * **窗口过期不是"晚了"，是"没了"。**
 * 而大多数卡片只写"请尽快处理"——
 * 用户不知道尽快是多久，也不知道过了会怎样。
 *
 * 所以这批每个组件都要有三样东西：
 *
 *   1. **证据清单**（要留什么）
 *   2. **时限**（还剩多久，不是"尽快"）
 *   3. **现在还能不能补**（过期了就明说没了，别再让人白跑）
 *
 * ── 校验因此换了第十六档 ────────────────────────────────────
 *
 * 前十五档一路查到"说不清现在轮到谁"，
 * 这批查的是**「证据窗口过期了却没提示」**：
 *
 *   escort     缺就诊记录 / 医嘱与记录对不上 / 复诊已过未标
 *   lease      缺关键条款 / 金额对不上 / 缺到期与解约条件
 *   overrun    分项之和 ≠ 总额 / 超支项未标原因 / 无止损建议
 *   vet        缺体重（用药按体重）/ 疫苗已过期未标 / 缺下次时间
 *   photospec  尺寸与像素对不上 / 缺底色 / 缺有效期
 *   claim      **缺证据清单** / **证据已过期未标** / 缺时限
 *   ivreview   轮次与结论不匹配 / 缺可改进项 / 无结论
 *   claimprog **缺材料截止** / 材料状态非法 / 进度倒退
 *   refile     **退回缺少原因** / **缺重报截止** / **已超期未标**
 *   damage     **缺现场照片要求** / 损坏未定级 / 缺责任方
 *   slotconflict 冲突时段未标 / 缺双方依据 / 无建议方案
 *   lostdoc    **缺挂失与补办的顺序** / 缺受理点 / 已过期未标
 */
object ZxRender38 {

    private val PAL = listOf(
        Color(0xFF5046E4), Color(0xFF3FBF7F), Color(0xFFE8A33D),
        Color(0xFFE5544B), Color(0xFF4A9BE8), Color(0xFF8E86FF)
    )

    private fun jarr(o: JSONObject, k: String): List<JSONObject> {
        val a = o.optJSONArray(k) ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }

    /**
     * 证据条——这批每个组件都要有。
     * 它回答"要留什么 / 还剩多久 / 现在还能不能补"三件事。
     */
    @Composable
    private fun evidence(what: String, leftH: Int, canFix: Boolean, gone: String) {
        val ok = leftH > 0
        val col = if (!ok) ZxT.Err else if (leftH <= 24) ZxT.Warn else ZxT.Ok
        Row(Modifier.fillMaxWidth().padding(top = 5.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(col.copy(alpha = if (!ok) .12f else .08f))
            .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(what, fontSize = 10.sp, color = ZxT.Text, lineHeight = 13.sp)
                Text(if (ok) "还剩 ${if (leftH >= 24) "${leftH / 24} 天" else "$leftH 小时"}"
                    else "**已过期**",
                    fontSize = 8.sp, color = col)
                if (!ok) Text(gone, fontSize = 8.5.sp, color = ZxT.Err, lineHeight = 12.sp)
            }
            Text(if (!ok) "没了" else if (canFix) "还能补" else "补不了",
                fontSize = 9.sp, color = col)
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

    @Composable
    private fun chip(l: String, c: Color, sel: Boolean, on: () -> Unit) {
        Box(Modifier.padding(end = 4.dp).clip(RoundedCornerShape(4.dp))
            .background(if (sel) c else ZxT.Surface2)
            .clickable(onClick = on).padding(7.dp, 3.dp)) {
            Text(l, fontSize = 8.sp, color = if (sel) Color.White else ZxT.Text2)
        }
    }

    /* ══════════ zx_escort 陪诊记录 ══════════ */
    @Composable
    fun escort(o: JSONObject, ctx: RenderContext) {
        // recs:{n:环节, t:时间, dr:医生, m:记录}  advice:{m:医嘱, done:是否做到}
        val recs = jarr(o, "recs")
        val adv = jarr(o, "advice")
        val advDone = remember { mutableStateListOf(*Array(adv.size) { it < adv.size }) }
        var sel by remember { mutableIntStateOf(-1) }
        val undone = advDone.count { !it }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "陪诊"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(if (undone > 0) "**$undone 条医嘱没做到**" else "医嘱都记下了",
                    fontSize = 8.5.sp,
                    color = if (undone > 0) ZxT.Warn else ZxT.Ok)
            }
            Spacer(Modifier.height(5.dp))
            recs.forEachIndexed { k, r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_escort_rec", mapOf(
                            "step" to r.optString("n", ""),
                            "doctor" to r.optString("dr", ""),
                            "note" to r.optString("m", "")))
                    }.padding(6.dp, 4.dp)) {
                    Text(r.optString("t", ""), fontSize = 8.sp, color = ZxT.Text3,
                        modifier = Modifier.width(38.dp), fontFamily = ZxT.Mono)
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(r.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text,
                                modifier = Modifier.weight(1f), maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text(r.optString("dr", ""), fontSize = 7.5.sp, color = ZxT.Text3)
                        }
                        Text(r.optString("m", ""), fontSize = 9.sp, color = ZxT.Text2,
                            lineHeight = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("医嘱", fontSize = 9.sp, color = ZxT.Text3)
            adv.forEachIndexed { k, a ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (!advDone[k]) ZxT.Warn.copy(.07f) else Color.Transparent)
                    .clickable {
                        advDone[k] = !advDone[k]
                        ctx.callTool("zx_escort_advice", mapOf(
                            "advice" to a.optString("m", ""), "done" to advDone[k]))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp))
                        .background(if (advDone[k]) ZxT.Ok else ZxT.Warn))
                    Spacer(Modifier.width(6.dp))
                    Text(a.optString("m", ""), fontSize = 9.5.sp,
                        color = if (advDone[k]) ZxT.Text2 else ZxT.Text,
                        modifier = Modifier.weight(1f), lineHeight = 12.sp)
                }
            }
            evidence("就诊记录 + 医嘱（**复诊时要带上**）", 24 * 30, true,
                "**丢了就得重新检查**")
            verdict(undone == 0, warn = undone > 0,
                title = if (undone > 0) "**$undone 条医嘱没做到**——" +
                    "**医嘱是要执行的，不是要记住的**"
                    else "${adv.size} 条医嘱都记下了",
                why = "**陪诊记录要能对照医嘱查执行**——" +
                    "只记医生说了什么，下次复诊说不清做到没做到")
            note("已有 clinic 是<b>就医流程</b>，这个是<b>陪诊记录</b>：" +
                "就诊环节 + <b>医嘱执行勾选</b>。" +
                "<b>缺就诊记录会被拦</b>；<b>医嘱为空会被拦</b>。")
        }
    }

    /* ══════════ zx_lease 租房合同要点 ══════════ */
    @Composable
    fun lease(o: JSONObject, ctx: RenderContext) {
        // terms:{n:条款, v:内容, k:是否关键}  rent:月租 dep:押金 months:租期
        val terms = jarr(o, "terms")
        var sel by remember { mutableIntStateOf(-1) }
        val key = terms.count { it.optBoolean("k", false) }
        val rent = o.optDouble("rent", 0.0)
        val dep = o.optDouble("dep", 0.0)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "合同要点"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text("$key 条关键", fontSize = 8.5.sp, color = ZxT.Warn)
            }
            Spacer(Modifier.height(4.dp))
            Row {
                listOf(Triple("月租", "%.0f".format(rent), ZxT.Text),
                    Triple("押金", "%.0f".format(dep), ZxT.Text),
                    Triple("租期", "${o.optInt("months", 0)} 月", ZxT.Text2)
                ).forEach { (l, v, c) ->
                    Column(Modifier.weight(1f).padding(end = 3.dp)
                        .clip(RoundedCornerShape(5.dp)).background(ZxT.Surface2)
                        .padding(5.dp, 4.dp)) {
                        Text(v, fontSize = 12.sp, color = c, fontFamily = ZxT.Mono)
                        Text(l, fontSize = 7.5.sp, color = ZxT.Text3)
                    }
                }
            }
            Spacer(Modifier.height(5.dp))
            terms.forEachIndexed { k, t ->
                val isK = t.optBoolean("k", false)
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2
                        else if (isK) ZxT.Warn.copy(.07f) else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_lease_term", mapOf(
                            "term" to t.optString("n", ""),
                            "value" to t.optString("v", ""), "key" to isK))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (isK) Box(Modifier.size(5.dp).clip(CircleShape)
                        .background(ZxT.Warn))
                    Spacer(Modifier.width(5.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.optString("n", ""), fontSize = 9.5.sp,
                            color = if (isK) ZxT.Text else ZxT.Text2)
                        Text(t.optString("v", ""), fontSize = 8.5.sp, color = ZxT.Text3,
                            lineHeight = 12.sp)
                    }
                }
            }
            evidence("签约时的房屋现状照片 + 合同原件", 24 * 365, true,
                "**退租时押金就退不回来了**")
            verdict(key > 0,
                title = "$key 条关键条款——**违约条件、押金退还、提前解约**要单看",
                why = "**合同要点要标出哪条是关键**——" +
                    "十几条平铺列出，用户不知道违约时哪条会咬人")
            note("已有 rental 是<b>租房比选</b>、contract 是<b>通用合同</b>，" +
                "这个是<b>租房合同要点</b>：关键条款标红 + 金额对账。" +
                "<b>缺关键条款会被拦</b>；<b>缺到期与解约条件会被拦</b>。")
        }
    }

    /* ══════════ zx_overrun 装修超支 ══════════ */
    @Composable
    fun overrun(o: JSONObject, ctx: RenderContext) {
        // items:{n:项目, plan:预算, act:实际, why:超支原因}
        val items = jarr(o, "items")
        var sel by remember { mutableIntStateOf(-1) }
        val plan = items.sumOf { it.optDouble("plan", 0.0) }
        val act = items.sumOf { it.optDouble("act", 0.0) }
        val over = items.filter { it.optDouble("act", 0.0) > it.optDouble("plan", 0.0) }
        val noWhy = over.count { it.optString("why", "").isBlank() }
        val mx = items.maxOf { max(it.optDouble("plan", 0.0), it.optDouble("act", 0.0)) }
            .coerceAtLeast(1.0)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "装修预算"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(if (act > plan) "**超 ${"%.0f".format(act - plan)}**" else "在预算内",
                    fontSize = 8.5.sp,
                    color = if (act > plan) ZxT.Err else ZxT.Ok)
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${"%.0f".format(act)}", fontSize = 20.sp,
                    color = if (act > plan) ZxT.Err else ZxT.Text)
                Text("　实际 / 预算 ${"%.0f".format(plan)}", fontSize = 8.sp,
                    color = ZxT.Text3, modifier = Modifier.padding(bottom = 3.dp))
                Spacer(Modifier.weight(1f))
                Text("${"%.0f".format((act / plan.coerceAtLeast(1.0) - 1) * 100)}%",
                    fontSize = 12.sp,
                    color = if (act > plan) ZxT.Err else ZxT.Ok, fontFamily = ZxT.Mono)
            }
            Spacer(Modifier.height(5.dp))
            items.forEachIndexed { k, it ->
                val p = it.optDouble("plan", 0.0)
                val a = it.optDouble("act", 0.0)
                val isOver = a > p
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2
                        else if (isOver) ZxT.Err.copy(.06f) else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_overrun_item", mapOf(
                            "item" to it.optString("n", ""),
                            "plan" to "%.0f".format(p), "act" to "%.0f".format(a),
                            "reason" to it.optString("why", "")))
                    }.padding(6.dp, 4.dp)) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(it.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text,
                                modifier = Modifier.weight(1f), maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text("${"%.0f".format(p)} → ${"%.0f".format(a)}",
                                fontSize = 9.sp,
                                color = if (isOver) ZxT.Err else ZxT.Text2,
                                fontFamily = ZxT.Mono)
                        }
                        Spacer(Modifier.height(2.dp))
                        // 双条：预算 vs 实际
                        Box(Modifier.fillMaxWidth().height(7.dp)) {
                            Box(Modifier.fillMaxWidth((p / mx).toFloat())
                                .height(3.dp).clip(RoundedCornerShape(2.dp))
                                .background(ZxT.Surface3))
                            Box(Modifier.fillMaxWidth((a / mx).toFloat())
                                .height(3.dp).clip(RoundedCornerShape(2.dp))
                                .background(if (isOver) ZxT.Err else ZxT.Ok)
                                .offset(y = 4.dp))
                        }
                        if (isOver)
                            Text("超 ${"%.0f".format(a - p)}：${it.optString("why", "—")}",
                                fontSize = 8.sp, color = ZxT.Err, lineHeight = 11.sp)
                    }
                }
            }
            evidence("增项单 + 变更签证（**没签字后期不认**）", 24 * 14, true,
                "**没签证的增项，结算时对方可以不认**")
            verdict(noWhy == 0 && act <= plan, warn = noWhy > 0 || act > plan,
                title = when {
                    noWhy > 0 -> "**${noWhy} 项超支没写原因**——" +
                        "**不知道钱花在哪，就没法止损**"
                    act > plan -> "超 ${"%.0f".format(act - plan)}（" +
                        "${"%.0f".format((act / plan.coerceAtLeast(1.0) - 1) * 100)}%）"
                    else -> "在预算内"
                },
                why = "**超支要按项标原因**——" +
                    "只给一个总数，用户不知道该砍哪一项")
            note("已有 budget 是<b>预算</b>、cost 是<b>成本</b>，" +
                "这个是<b>装修超支</b>：预算 vs 实际双条 + <b>超支原因</b>。" +
                "<b>分项之和与总额对不上会被拦</b>；<b>超支缺原因会被拦</b>。")
        }
    }

    /* ══════════ zx_vet 宠物医疗 ══════════ */
    @Composable
    fun vet(o: JSONObject, ctx: RenderContext) {
        // recs:{n:项目, t:时间, dose:剂量, next:下次}  weight:体重
        val recs = jarr(o, "recs")
        var sel by remember { mutableIntStateOf(-1) }
        val w = o.optDouble("weight", 0.0)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "宠物"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text("${"%.1f".format(w)} kg", fontSize = 9.sp, color = ZxT.PriLite,
                    fontFamily = ZxT.Mono)
            }
            Text("**用药按体重算**——体重不对，剂量就是错的", fontSize = 8.sp,
                color = ZxT.Text3, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.height(5.dp))
            recs.forEachIndexed { k, r ->
                val overdue = r.optInt("next", 999) <= 0
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2
                        else if (overdue) ZxT.Err.copy(.07f) else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_vet_rec", mapOf(
                            "item" to r.optString("n", ""),
                            "dose" to r.optString("dose", ""),
                            "nextDays" to r.optInt("next", -1)))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(r.optString("t", ""), fontSize = 8.sp, color = ZxT.Text3,
                        modifier = Modifier.width(36.dp), fontFamily = ZxT.Mono)
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(r.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text,
                                modifier = Modifier.weight(1f), maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text(r.optString("dose", ""), fontSize = 8.sp,
                                color = ZxT.Text2, fontFamily = ZxT.Mono)
                        }
                        val nx = r.optInt("next", -1)
                        Text(when {
                            nx < 0 -> "—"
                            nx == 0 -> "**今天该做了**"
                            overdue -> "**已过期 ${-nx} 天**"
                            else -> "下次 ${nx} 天后"
                        }, fontSize = 8.sp,
                            color = if (nx >= 0 && nx <= 7) ZxT.Warn else ZxT.Text3)
                    }
                }
            }
            evidence("疫苗本 + 用药记录（**换医院要看**）", 24 * 90, true,
                "**没有记录，新医院会重做一遍检查**")
            verdict(true, warn = recs.any { it.optInt("next", 999) in 0..7 },
                title = "体重 ${"%.1f".format(w)} kg 是剂量依据——**记错体重比记错日期严重**",
                why = "**宠物医疗要把体重放在最显眼处**——" +
                    "用药剂量按体重算，缺了它其他记录都用不了")
            note("已有 pet 是<b>宠物养护</b>、credential 是<b>证件</b>，" +
                "这个是<b>宠物医疗</b>：体重 + 用药剂量 + <b>下次时间倒计时</b>。" +
                "<b>缺体重会被拦</b>；<b>缺下次时间会被拦</b>。")
        }
    }

    /* ══════════ zx_photospec 证件照规格 ══════════ */
    @Composable
    fun photospec(o: JSONObject, ctx: RenderContext) {
        // specs:{n:用途, w:宽mm, h:高mm, px:像素, bg:底色, req:要求}
        val specs = jarr(o, "specs")
        var sel by remember { mutableIntStateOf(0) }
        val s = specs.getOrNull(sel)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "证件照"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text("${specs.size} 种规格", fontSize = 8.5.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(4.dp))
            Row {
                specs.forEachIndexed { k, sp ->
                    chip(sp.optString("n", ""), PAL[k % PAL.size], sel == k) { sel = k }
                }
            }
            if (s != null) {
                Spacer(Modifier.height(5.dp))
                // 比例预览：按实际 mm 比例画
                val w = s.optDouble("w", 0.0)
                val h = s.optDouble("h", 0.0)
                val ratio = (h / w.coerceAtLeast(1.0)).coerceIn(0.5, 2.0)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(52.dp).height((52 * ratio).toInt().dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(when (s.optString("bg", "")) {
                            "白" -> Color(0xFFF5F6F8); "蓝" -> Color(0xFF4A90D9)
                            "红" -> Color(0xFFD9534F); else -> ZxT.Surface3 })
                        .border(1.dp, ZxT.Line, RoundedCornerShape(3.dp)))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${"%.0f".format(w)} × ${"%.0f".format(h)} mm", fontSize = 11.sp,
                            color = ZxT.Text, fontFamily = ZxT.Mono)
                        Text("${s.optString("px", "")} px", fontSize = 9.sp, color = ZxT.Text2,
                            fontFamily = ZxT.Mono)
                        Text("底色：${s.optString("bg", "—")}", fontSize = 8.5.sp,
                            color = ZxT.Text3)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(s.optString("req", ""), fontSize = 9.sp, color = ZxT.Text2,
                    lineHeight = 12.sp)
                Spacer(Modifier.height(4.dp))
                Box(Modifier.clip(RoundedCornerShape(5.dp))
                    .background(ZxT.Primary.copy(.20f))
                    .clickable {
                        ctx.callTool("zx_photo_pick", mapOf(
                            "use" to s.optString("n", ""),
                            "size" to "${"%.0f".format(w)}x${"%.0f".format(h)}mm",
                            "px" to s.optString("px", ""), "bg" to s.optString("bg", "")))
                    }.padding(8.dp, 4.dp)) {
                    Text("按这个规格去拍", fontSize = 8.sp, color = ZxT.PriLite)
                }
            }
            evidence("照片回执 / 电子版（**下次还能用**）", 24 * 180, true,
                "**重拍不贵，但排队很贵**")
            verdict(s != null,
                title = "不同用途规格不同——**护照 33×48、签证 35×45、社保 26×32**",
                why = "**证件照要区分用途**——" +
                    "拿签证照去办社保会被退回，而用户通常不知道尺寸不一样")
            note("已有 credential 是<b>证件</b>、visa 是<b>签证材料</b>，" +
                "这个是<b>证件照规格</b>：按用途切换 + <b>比例预览</b> + 底色。" +
                "<b>尺寸与像素对不上会被拦</b>；<b>缺底色会被拦</b>。")
        }
    }

    /* ══════════ zx_claim 快递索赔 ══════════ */
    @Composable
    fun claim(o: JSONObject, ctx: RenderContext) {
        // ev:{n:证据, got:有/无, must:必需, leftH:剩余小时}
        val ev = jarr(o, "ev")
        val got = remember { mutableStateListOf(*Array(ev.size) { it < ev.size }) }
        var sel by remember { mutableIntStateOf(-1) }
        val mustLeft = ev.mapIndexedNotNull { i, e ->
            if (e.optBoolean("must", false) && !got[i]) i else null }
        val expired = ev.mapIndexedNotNull { i, e ->
            if (e.optInt("leftH", 9999) <= 0) i else null }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "索赔"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(if (expired.isNotEmpty()) "**${expired.size} 项证据已过期**"
                    else if (mustLeft.isEmpty()) "证据齐了" else "缺 ${mustLeft.size} 项",
                    fontSize = 8.5.sp,
                    color = if (expired.isNotEmpty()) ZxT.Err
                    else if (mustLeft.isEmpty()) ZxT.Ok else ZxT.Warn)
            }
            Spacer(Modifier.height(5.dp))
            ev.forEachIndexed { k, e ->
                val lh = e.optInt("leftH", 9999)
                val gone = lh <= 0
                val m = e.optBoolean("must", false)
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2
                        else if (gone) ZxT.Err.copy(.09f)
                        else if (m && !got[k]) ZxT.Warn.copy(.07f) else Color.Transparent)
                    .clickable {
                        if (gone) { sel = k; return@clickable }
                        got[k] = !got[k]; sel = k
                        ctx.callTool("zx_claim_ev", mapOf(
                            "evidence" to e.optString("n", ""),
                            "got" to got[k], "hoursLeft" to lh))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp))
                        .background(if (gone) ZxT.Err
                            else if (got[k]) ZxT.Ok else ZxT.Surface3))
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(e.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text,
                                modifier = Modifier.weight(1f), maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text(if (gone) "**已过期**"
                                else if (lh >= 24) "剩 ${lh / 24} 天" else "剩 ${lh} 小时",
                                fontSize = 8.sp,
                                color = if (gone) ZxT.Err
                                else if (lh <= 24) ZxT.Warn else ZxT.Text3)
                            if (m) Text("　必", fontSize = 7.5.sp, color = ZxT.Err)
                        }
                        if (gone)
                            Text("窗口过了——${e.optString("miss", "这项拿不到了")}",
                                fontSize = 8.sp, color = ZxT.Err, lineHeight = 11.sp)
                    }
                }
            }
            evidence("**签收 7 天内的开箱照片**——这是最容易丢的一项", 24 * 3, true,
                "**过了 7 天，破损就认不了了**")
            verdict(expired.isEmpty() && mustLeft.isEmpty(),
                warn = expired.isEmpty() && mustLeft.isNotEmpty(),
                title = when {
                    expired.isNotEmpty() -> "**${expired.size} 项证据已过期**——" +
                        "**窗口过期不是「晚了」，是「没了」**"
                    mustLeft.isNotEmpty() -> "还缺 ${mustLeft.size} 项必需证据"
                    else -> "证据齐了，可以提交"
                },
                why = "**索赔卡要给每项证据的剩余时间**——" +
                    "只写「请尽快」，用户不知道尽快是多久，也不知道过了会怎样")
            note("已有 zx_ship 是<b>寄件轨迹</b>、refund 是<b>退款</b>，" +
                "这个是<b>快递索赔</b>：证据清单 + <b>每项剩余时间</b> + 过期后果。" +
                "<b>缺证据清单会被拦</b>；<b>证据已过期未标后果会被拦</b>。")
        }
    }

    /* ══════════ zx_ivreview 面试复盘 ══════════ */
    @Composable
    fun ivreview(o: JSONObject, ctx: RenderContext) {
        // qs:{n:问题, s:0答得差 1一般 2答得好, why:为什么, fix:怎么改}
        val qs = jarr(o, "qs")
        val sc = remember { mutableStateListOf(*Array(qs.size) { it % 3 }) }
        var sel by remember { mutableIntStateOf(-1) }
        val bad = sc.count { it == 0 }
        val noFix = qs.mapIndexedNotNull { i, q ->
            if (sc[i] <= 1 && q.optString("fix", "").isBlank()) i else null }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "复盘"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(if (bad > 0) "**$bad 题答得差**" else "都还行", fontSize = 8.5.sp,
                    color = if (bad > 0) ZxT.Err else ZxT.Ok)
            }
            Spacer(Modifier.height(5.dp))
            qs.forEachIndexed { k, q ->
                val v = sc[k]
                val col = when (v) { 2 -> ZxT.Ok; 1 -> ZxT.Warn; else -> ZxT.Err }
                Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2
                        else if (v == 0) ZxT.Err.copy(.06f) else Color.Transparent)
                    .padding(6.dp, 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(q.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text,
                                lineHeight = 12.sp)
                            Text(q.optString("why", ""), fontSize = 8.sp, color = ZxT.Text3,
                                lineHeight = 11.sp)
                        }
                        Spacer(Modifier.width(4.dp))
                        listOf(0 to "差", 1 to "一般", 2 to "好").forEach { (vv, l) ->
                            Box(Modifier.padding(start = 3.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (v == vv) col else ZxT.Surface2)
                                .clickable {
                                    sc[k] = vv; sel = k
                                    ctx.callTool("zx_iv_review", mapOf(
                                        "q" to q.optString("n", ""),
                                        "score" to vv, "fix" to q.optString("fix", "")))
                                }.padding(5.dp, 2.dp)) {
                                Text(l, fontSize = 7.5.sp,
                                    color = if (v == vv) Color.White else ZxT.Text2)
                            }
                        }
                    }
                    if (v <= 1 && q.optString("fix", "").isNotBlank())
                        Text("→ ${q.optString("fix", "")}", fontSize = 8.5.sp,
                            color = ZxT.PriLite, lineHeight = 11.sp,
                            modifier = Modifier.padding(top = 2.dp))
                }
            }
            evidence("面试题 + 自己的答案（**下次同家公司还会问**）", 24 * 30, true,
                "**忘了就白面一次**")
            verdict(noFix.isEmpty() && bad == 0, warn = noFix.isNotEmpty(),
                title = when {
                    noFix.isNotEmpty() -> "**${noFix.size} 题没写怎么改**——" +
                        "**只知道答得差，不知道下次怎么答，等于没复盘**"
                    bad > 0 -> "$bad 题答得差，都写了改进方向"
                    else -> "${qs.size} 题都还行"
                },
                why = "**面试复盘要给「下次怎么答」**——" +
                    "只打分不写改法，下次遇到同样的问题还是答不好")
            note("已有 interview 是<b>面试流程</b>、review 是<b>复盘</b>，" +
                "这个是<b>面试复盘</b>：逐题打分 + <b>改进方向</b>。" +
                "<b>答得差却没写怎么改会被拦</b>；<b>缺问题会被拦</b>。")
        }
    }

    /* ══════════ zx_claimprog 理赔进度 ══════════ */
    @Composable
    fun claimprog(o: JSONObject, ctx: RenderContext) {
        // docs:{n:材料, s:0缺 1已交 2已受理, leftH:剩余小时}
        val docs = jarr(o, "docs")
        val st = remember { mutableStateListOf(*Array(docs.size) { it % 3 }) }
        var sel by remember { mutableIntStateOf(-1) }
        val left0 = docs.count { st[docs.indexOf(it)] == 0 }
        val expired = docs.mapIndexedNotNull { i, d ->
            if (d.optInt("leftH", 9999) <= 0 && st[i] < 2) i else null }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "理赔"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(if (expired.isNotEmpty()) "**${expired.size} 项已过截止**"
                    else "$left0 项待交", fontSize = 8.5.sp,
                    color = if (expired.isNotEmpty()) ZxT.Err
                    else if (left0 > 0) ZxT.Warn else ZxT.Ok)
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth()) {
                listOf(Triple("待交", docs.count { st[docs.indexOf(it)] == 0 }, ZxT.Text3),
                    Triple("已交", docs.count { st[docs.indexOf(it)] == 1 }, ZxT.Warn),
                    Triple("受理", docs.count { st[docs.indexOf(it)] == 2 }, ZxT.Ok)
                ).forEach { (l, n, c) ->
                    Column(Modifier.weight(1f).padding(end = 3.dp)
                        .clip(RoundedCornerShape(5.dp)).background(c.copy(alpha = .16f))
                        .padding(4.dp, 3.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$n", fontSize = 13.sp, color = c)
                        Text(l, fontSize = 7.5.sp, color = ZxT.Text3)
                    }
                }
            }
            Spacer(Modifier.height(5.dp))
            docs.forEachIndexed { k, d ->
                val lh = d.optInt("leftH", 9999)
                val gone = lh <= 0 && st[k] < 2
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2
                        else if (gone) ZxT.Err.copy(.08f) else Color.Transparent)
                    .clickable {
                        val nv = (st[k] + 1) % 3; st[k] = nv; sel = k
                        ctx.callTool("zx_claim_doc", mapOf(
                            "doc" to d.optString("n", ""), "state" to nv,
                            "hoursLeft" to lh))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp))
                        .background(when (st[k]) { 2 -> ZxT.Ok; 1 -> ZxT.Warn
                            else -> ZxT.Surface3 }))
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(d.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text,
                                modifier = Modifier.weight(1f), maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text(if (gone) "**已过截止**"
                                else if (lh >= 24) "剩 ${lh / 24} 天" else "剩 ${lh} 小时",
                                fontSize = 8.sp,
                                color = if (gone) ZxT.Err
                                else if (lh <= 48) ZxT.Warn else ZxT.Text3)
                        }
                        Text(when (st[k]) { 2 -> "已受理"; 1 -> "已交，等受理"
                            else -> "还没交" }, fontSize = 8.sp,
                            color = when (st[k]) { 2 -> ZxT.Ok; 1 -> ZxT.Warn
                                else -> ZxT.Text3 })
                    }
                }
            }
            evidence("**出院小结 / 发票原件**——缺了就没法受理", 24 * 6, true,
                "**原件丢了补办非常麻烦**")
            verdict(expired.isEmpty(),
                warn = expired.isEmpty() && left0 > 0,
                title = when {
                    expired.isNotEmpty() -> "**${expired.size} 项已过材料截止**——" +
                        "**过了截止，理赔会被直接拒**"
                    left0 > 0 -> "$left0 项待交，注意各自的截止"
                    else -> "材料齐全"
                },
                why = "**理赔进度要给每项材料的截止时间**——" +
                    "只写「请尽快提交」，用户不知道哪样先过期")
            note("已有 insurance 是<b>保单</b>、repair 是<b>工单</b>，" +
                "这个是<b>理赔进度</b>：材料三态 + <b>每项截止</b> + 过截止标红。" +
                "<b>缺材料截止会被拦</b>；<b>材料状态非法会被拦</b>。")
        }
    }

    /* ══════════ zx_refile 报销重报 ══════════ */
    @Composable
    fun refile(o: JSONObject, ctx: RenderContext) {
        // why:退回原因  leftH:重报剩余小时  need:{n:要补的东西, got:有/无}
        val need = jarr(o, "need")
        val got = remember { mutableStateListOf(*Array(need.size) { false }) }
        var sel by remember { mutableIntStateOf(-1) }
        val lh = o.optInt("leftH", 0)
        val gone = lh <= 0
        val left = got.count { !it }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "重报"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(if (gone) "**已超期**" else "剩 ${lh / 24} 天", fontSize = 8.5.sp,
                    color = if (gone) ZxT.Err else if (lh <= 72) ZxT.Warn else ZxT.Ok)
            }
            Spacer(Modifier.height(4.dp))
            // 退回原因
            Row(Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(6.dp)).background(ZxT.Err.copy(.09f))
                .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("✗", fontSize = 11.sp, color = ZxT.Err)
                Spacer(Modifier.width(5.dp))
                Column(Modifier.weight(1f)) {
                    Text("退回原因", fontSize = 8.sp, color = ZxT.Text3)
                    Text(o.optString("why", "—"), fontSize = 9.5.sp, color = ZxT.Err,
                        lineHeight = 12.sp)
                }
            }
            Spacer(Modifier.height(5.dp))
            Text("要补的", fontSize = 9.sp, color = ZxT.Text3)
            need.forEachIndexed { k, nd ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2
                        else if (!got[k]) ZxT.Warn.copy(.06f) else Color.Transparent)
                    .clickable {
                        got[k] = !got[k]; sel = k
                        ctx.callTool("zx_refile_item", mapOf(
                            "item" to nd.optString("n", ""), "got" to got[k]))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp))
                        .background(if (got[k]) ZxT.Ok else ZxT.Warn))
                    Spacer(Modifier.width(6.dp))
                    Text(nd.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text,
                        modifier = Modifier.weight(1f), lineHeight = 12.sp)
                    Text(if (got[k]) "已补" else "待补", fontSize = 8.sp,
                        color = if (got[k]) ZxT.Ok else ZxT.Warn)
                }
            }
            evidence(if (gone) "重报窗口" else "重报窗口（**超期作废**）", lh, true,
                "**超期就报不了了——这笔钱要自己承担**")
            verdict(!gone && left == 0, warn = !gone && left > 0,
                title = when {
                    gone -> "**已超期**——**这笔钱报不了了**，" +
                        "现在该问的是能不能走特殊审批"
                    left > 0 -> "还差 ${left} 项，剩 ${lh / 24} 天"
                    else -> "补齐了，可以重交"
                },
                why = "**重报要给截止，且超期要直说没钱了**——" +
                    "只说「请尽快重报」，用户不知道超了会自己承担")
            note("已有 reimburse 是<b>报销单据</b>，" +
                "这个是<b>退回重报</b>：退回原因 + 待补清单 + <b>重报截止</b>。" +
                "<b>缺退回原因会被拦</b>；<b>缺重报截止会被拦</b>；<b>超期未标后果会被拦</b>。")
        }
    }

    /* ══════════ zx_damage 搬家损坏 ══════════ */
    @Composable
    fun damage(o: JSONObject, ctx: RenderContext) {
        // items:{n:物品, lv:1轻 2中 3重, who:责任方, photo:是否有照片}
        val items = jarr(o, "items")
        var sel by remember { mutableIntStateOf(-1) }
        val noPhoto = items.count { !it.optBoolean("photo", false) }
        val heavy = items.count { it.optInt("lv", 1) >= 3 }
        val byWho = items.groupBy { it.optString("who", "—") }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "损坏登记"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(if (heavy > 0) "**$heavy 件严重**" else "${items.size} 件",
                    fontSize = 8.5.sp,
                    color = if (heavy > 0) ZxT.Err else ZxT.Text3)
            }
            Spacer(Modifier.height(5.dp))
            items.forEachIndexed { k, it ->
                val lv = it.optInt("lv", 1)
                val col = when (lv) { 3 -> ZxT.Err; 2 -> ZxT.Warn; else -> ZxT.Text2 }
                val ph = it.optBoolean("photo", false)
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2
                        else if (lv >= 3) ZxT.Err.copy(.06f) else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_damage_item", mapOf(
                            "item" to it.optString("n", ""), "level" to lv,
                            "who" to it.optString("who", ""), "photo" to ph))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp))
                        .background(col))
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(it.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text,
                                modifier = Modifier.weight(1f), maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text(when (lv) { 3 -> "严重"; 2 -> "中等"; else -> "轻微" },
                                fontSize = 8.sp, color = col)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(it.optString("who", "—"), fontSize = 8.sp, color = ZxT.Text3)
                            Spacer(Modifier.width(6.dp))
                            Text(if (ph) "有照片" else "**没照片**", fontSize = 8.sp,
                                color = if (ph) ZxT.Ok else ZxT.Err)
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row {
                byWho.forEach { (w, l) ->
                    chip("$w ${l.size}", PAL[byWho.keys.indexOf(w) % PAL.size], false) { }
                }
            }
            evidence("**当场拍的损坏照片 + 搬家单**", 24 * 2, true,
                "**事后补拍没有用——说不清是谁磕的**")
            verdict(noPhoto == 0, warn = noPhoto > 0,
                title = if (noPhoto > 0) "**${noPhoto} 件没照片**——" +
                    "**搬家损坏必须当场登记，事后说不清**"
                    else "${items.size} 件都有照片",
                why = "**损坏登记要标出有没有照片**——" +
                    "只列物品和程度，理赔时才发现没证据")
            note("已有 relocation 是<b>搬家</b>、claim 是<b>快递索赔</b>，" +
                "这个是<b>搬家损坏</b>：物品 + 定级 + <b>责任方</b> + 有没有照片。" +
                "<b>缺照片标记会被拦</b>；<b>缺责任方会被拦</b>；<b>损坏未定级会被拦</b>。")
        }
    }

    /* ══════════ zx_slotconflict 场地冲突协商 ══════════ */
    @Composable
    fun slotconflict(o: JSONObject, ctx: RenderContext) {
        // slot:冲突时段  sides:{n:一方, why:依据, w:权重}  opts:{n:方案, cost:代价}
        val sides = jarr(o, "sides")
        val opts = jarr(o, "opts")
        var sel by remember { mutableIntStateOf(-1) }
        var pick by remember { mutableIntStateOf(-1) }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "冲突协商"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(o.optString("slot", ""), fontSize = 8.5.sp, color = ZxT.Err)
            }
            Spacer(Modifier.height(5.dp))
            sides.forEachIndexed { k, s ->
                val w = s.optInt("w", 1).coerceIn(1, 5)
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_slot_side", mapOf(
                            "side" to s.optString("n", ""),
                            "reason" to s.optString("why", ""), "weight" to w))
                    }.padding(6.dp, 4.dp)) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(s.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text,
                                modifier = Modifier.weight(1f), maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            // 权重点
                            Row {
                                (1..5).forEach { i ->
                                    Box(Modifier.size(5.dp).padding(end = 2.dp)
                                        .clip(CircleShape)
                                        .background(if (i <= w) PAL[k % PAL.size]
                                            else ZxT.Surface3))
                                }
                            }
                        }
                        Text(s.optString("why", ""), fontSize = 8.5.sp, color = ZxT.Text3,
                            lineHeight = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(5.dp))
            Text("可选方案", fontSize = 9.sp, color = ZxT.Text3)
            opts.forEachIndexed { k, op ->
                val isP = pick == k
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (isP) ZxT.Primary.copy(.16f) else Color.Transparent)
                    .clickable {
                        pick = k
                        ctx.callTool("zx_slot_resolve", mapOf(
                            "option" to op.optString("n", ""),
                            "cost" to op.optString("cost", "")))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(CircleShape)
                        .background(if (isP) ZxT.Primary else ZxT.Surface3))
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text(op.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text)
                        Text("代价：${op.optString("cost", "—")}", fontSize = 8.sp,
                            color = ZxT.Text3)
                    }
                }
            }
            evidence("预订记录 + 双方沟通记录", 24 * 7, true,
                "**没有记录就变成谁嗓门大谁赢**")
            verdict(pick >= 0, warn = pick < 0,
                title = if (pick >= 0) "选了「${opts.getOrNull(pick)?.optString("n", "")}」"
                    else "**两边都有依据时，要给方案而不是让它们自己吵**",
                why = "**冲突协商要把双方依据摆在一起**——" +
                    "只显示「时间冲突了」，用户不知道该让谁")
            note("已有 booking 是<b>场地预订</b>、conflict 是<b>通用冲突</b>，" +
                "这个是<b>场地冲突协商</b>：双方依据 + 权重 + <b>带代价的方案</b>。" +
                "<b>冲突时段未标会被拦</b>；<b>缺一方依据会被拦</b>；<b>无方案会被拦</b>。")
        }
    }

    /* ══════════ zx_lostdoc 证件挂失 ══════════ */
    @Composable
    fun lostdoc(o: JSONObject, ctx: RenderContext) {
        // steps:{n:步骤, s:0未做 1已做, must:必需, place:受理点, fee:费用}
        // lost:丢失方式 0遗失 1被盗
        val steps = jarr(o, "steps")
        val st = remember { mutableStateListOf(*Array(steps.size) { 0 }) }
        var sel by remember { mutableIntStateOf(-1) }
        val lost = o.optInt("lost", 0)
        val mustLeft = steps.mapIndexedNotNull { i, s ->
            if (s.optBoolean("must", false) && st[i] == 0) i else null }
        // 挂失必须在补办之前
        val reportIdx = steps.indexOfFirst { it.optString("n", "").contains("挂失") }
        val reissueIdx = steps.indexOfFirst { it.optString("n", "").contains("补办") }
        val wrongOrder = reportIdx >= 0 && reissueIdx >= 0 &&
            st[reissueIdx] == 1 && st[reportIdx] == 0

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "挂失"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(if (lost == 1) "**被盗**" else "遗失", fontSize = 8.5.sp,
                    color = if (lost == 1) ZxT.Err else ZxT.Text3)
            }
            if (lost == 1)
                Text("**被盗要先报案**——凭报案回执才能挂失，流程不一样", fontSize = 8.5.sp,
                    color = ZxT.Err, lineHeight = 12.sp,
                    modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.height(5.dp))
            steps.forEachIndexed { k, s ->
                val m = s.optBoolean("must", false)
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2
                        else if (st[k] == 1) ZxT.Ok.copy(.06f)
                        else if (m) ZxT.Warn.copy(.06f) else Color.Transparent)
                    .clickable {
                        st[k] = if (st[k] == 1) 0 else 1; sel = k
                        ctx.callTool("zx_lost_step", mapOf(
                            "step" to s.optString("n", ""), "done" to (st[k] == 1),
                            "place" to s.optString("place", "")))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp))
                        .background(if (st[k] == 1) ZxT.Ok else ZxT.Surface3))
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(s.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text,
                                modifier = Modifier.weight(1f), maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text(s.optString("fee", ""), fontSize = 8.sp, color = ZxT.Text3)
                            if (m) Text("　必", fontSize = 7.5.sp, color = ZxT.Err)
                        }
                        Text(s.optString("place", ""), fontSize = 8.sp, color = ZxT.Text3)
                    }
                }
            }
            evidence("**报案回执 / 挂失凭证**——补办时要出示", 24 * 30, true,
                "**没有回执，补办会被拒**")
            verdict(!wrongOrder && mustLeft.isEmpty(),
                warn = wrongOrder || mustLeft.isNotEmpty(),
                title = when {
                    wrongOrder -> "**先补办后挂失了**——" +
                        "**顺序错了，补办会被拒，得重来**"
                    mustLeft.isNotEmpty() -> "还有 ${mustLeft.size} 步必需没做"
                    else -> "流程走完了"
                },
                why = "**挂失要强制顺序**——" +
                    "挂失和补办是两件事，顺序错了白跑一趟")
            note("已有 credential 是<b>证件到期</b>，" +
                "这个是<b>证件挂失</b>：<b>丢失方式不同流程不同</b> + 强制顺序 + 受理点。" +
                "<b>缺挂失或补办步骤会被拦</b>；<b>缺受理点会被拦</b>；<b>顺序错会被拦</b>。")
        }
    }
}
