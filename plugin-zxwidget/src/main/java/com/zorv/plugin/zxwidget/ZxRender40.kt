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

/**
 * 第四十批组件（12 个）—— 交接完成 ≠ 责任结束。
 *
 * ── 它和第三十九批的差别 ────────────────────────────────────
 *
 * 第三十九批解决的是**交接那一刻**：
 *
 *   交接要留下双方都认的快照——只打勾没人签字，事后就是互相甩锅。
 *
 * 它把"转移的瞬间"钉死了：状态值、双方签字、异议。
 *
 * 但这批要处理的是**签完字之后**：
 *
 *   猫接回来了，三天后拉稀——是寄养期间吃的还是接回来吃的？
 *   装修尾款付了，一个月后墙裂了——还能不能找他？
 *   员工交接完了，两周后发现他经手的账有问题——还能不能问他？
 *   账号权限回收了，但密钥还在他手里——那算回收了吗？
 *
 * ── 贯穿这批的一条 ──────────────────────────────────────────
 *
 * **交接签字之后还有一段「责任还没真正结束」的时间，
 *   这段有多长、什么时候真正结束，必须明说。**
 *
 * 签字那一刻看起来是终点，实际往往只是中点。
 * 不说清后面那一段有多长，会出现两种错误：
 *
 *   以为已经结束了 → 出问题找不到人
 *   以为还没结束   → 一直被追着问，或者不敢动
 *
 * 所以这批每个组件都要给出**三样东西**：
 *
 *   1. **交接完成的时间点**（什么时候签的字）
 *   2. **残留期还有多久**（质保 / 追溯 / 过渡 / 竞业…）
 *   3. **残留的是什么**（不只是"还有责任"，要具体到哪一类）
 *
 * ── 校验因此换了第十八档 ────────────────────────────────────
 *
 * 前十七档一路查到"只记了清单，没有双方确认"，
 * 这批查的是**「签了字就说完了，没说后面那段」**：
 *
 *   housekeep    缺累计评价 / 缺服务周期 / 缺本次验收
 *   finalpay     缺质保期 / 缺尾款比例 / 缺扣留事由
 *   fosterdaily  缺每日状态 / 缺追溯期 / 日期有缺漏
 *   wardhand     缺观察期 / 缺交接时的生命体征 / 缺转入转出
 *   schoolnote   缺沟通流 / 缺回复期限 / 缺待办
 *   sublet       缺原租客责任终止点 / 缺押金转手 / 缺房东同意
 *   inspecthand  缺上次巡检 / 缺周期 / 缺本周期发现项
 *   projhandover 缺过渡期 / 缺随叫随到的时限 / 缺未结事项
 *   stocktake    缺上次盘点 / 缺盈亏 / 缺差异原因
 *   offboard     缺追责期 / 缺交接清单完成度 / 缺权限回收
 *   revoke       缺残留权限（令牌/密钥/物理钥匙）/ 缺生效时间
 *   warrtrack    缺保修截止 / 缺问题登记 / 缺是否已通知
 */
object ZxRender40 {

    private val PAL = listOf(
        Color(0xFF5046E4), Color(0xFF3FBF7F), Color(0xFFE8A33D),
        Color(0xFFE5544B), Color(0xFF4A9BE8), Color(0xFF8E86FF)
    )

    private fun jarr(o: JSONObject, k: String): List<JSONObject> {
        val a = o.optJSONArray(k) ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }

    /**
     * 残留期条——这批每个组件都要有。
     * 它回答"还剩多久 / 残留的是什么 / 什么时候真正结束"。
     */
    @Composable
    private fun residual(label: String, hours: Int, what: String, done: Boolean) {
        val col = if (done) ZxT.Text3 else if (hours <= 0) ZxT.Err
            else if (hours <= 24 * 7) ZxT.Warn else ZxT.Ok
        val txt = if (done) "已结束"
            else if (hours <= 0) "**已过期**"
            else if (hours >= 24) "剩 ${hours / 24} 天"
            else "剩 ${hours} 小时"
        Row(Modifier.fillMaxWidth().padding(top = 5.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(col.copy(alpha = .10f))
            .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 8.5.sp, color = ZxT.Text3,
                modifier = Modifier.width(46.dp))
            Column(Modifier.weight(1f)) {
                Text(what, fontSize = 9.sp, color = col, lineHeight = 12.sp)
                Text("**交接完成 ≠ 责任结束**——这段没说清，出了问题找不到人",
                    fontSize = 8.sp, color = ZxT.Text3, lineHeight = 11.sp)
            }
            Text(txt, fontSize = 9.sp, color = col, fontFamily = ZxT.Mono)
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

    /* ══════════ zx_housekeep 家政服务验收 ══════════ */
    @Composable
    fun housekeep(o: JSONObject, ctx: RenderContext) {
        // visits:[{d:日期, who:阿姨, ok:本次验收}]  cyc:周期天  leftH:本次到下次
        val visits = jarr(o, "visits")
        val ok = remember { mutableStateListOf(*Array(visits.size) { it < visits.size - 1 }) }
        var sel by remember { mutableIntStateOf(-1) }
        val cyc = o.optInt("cyc", 0)
        val leftH = o.optInt("leftH", 0)
        val bad = ok.count { !it }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "家政"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(if (bad > 0) "**$bad 次没过**" else "${visits.size} 次都过了",
                    fontSize = 8.5.sp, color = if (bad > 0) ZxT.Warn else ZxT.Ok)
            }
            Text("每 ${cyc} 天一次", fontSize = 8.sp, color = ZxT.Text3,
                modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.height(5.dp))
            visits.forEachIndexed { k, v ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2
                        else if (!ok[k]) ZxT.Warn.copy(.07f) else Color.Transparent)
                    .clickable {
                        ok[k] = !ok[k]; sel = k
                        ctx.callTool("zx_housekeep_visit", mapOf(
                            "day" to v.optString("d", ""),
                            "who" to v.optString("who", ""), "pass" to ok[k]))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp))
                        .background(if (ok[k]) ZxT.Ok else ZxT.Warn))
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(v.optString("d", ""), fontSize = 9.sp, color = ZxT.Text2,
                                fontFamily = ZxT.Mono, modifier = Modifier.width(46.dp))
                            Text(v.optString("who", ""), fontSize = 9.5.sp, color = ZxT.Text)
                        }
                        Text(if (ok[k]) "验收通过" else "**没过**", fontSize = 8.sp,
                            color = if (ok[k]) ZxT.Ok else ZxT.Warn)
                    }
                }
            }
            residual("服务期", leftH, "本次验收到下次上门之间仍可追责", leftH <= 0)
            verdict(bad == 0, warn = bad > 0,
                title = if (bad > 0) "**$bad 次没过**——累计评价才是换不换人的依据"
                    else "${visits.size} 次都通过了",
                why = "**家政是周期性的，单次验收说明不了什么**——" +
                    "一次没过是偶然，三次没过是这个人不行")
            note("已有 handoff 是<b>一次性交接</b>，" +
                "这个是<b>周期性服务验收</b>：<b>每次都要验收</b> + <b>累计评价</b> + 追责期。" +
                "<b>缺服务周期会被拦</b>；<b>缺本次到下次的追责期会被拦</b>。")
        }
    }

    /* ══════════ zx_finalpay 装修尾款 ══════════ */
    @Composable
    fun finalpay(o: JSONObject, ctx: RenderContext) {
        // total:总价  holdPct:尾款比例  warrH:质保期小时  issues:[{n:问题, h:发现距完工小时, fix:是否已修}]
        val total = o.optDouble("total", 0.0)
        val holdPct = o.optDouble("holdPct", 0.0)
        val hold = total * holdPct
        val warrH = o.optInt("warrH", 0)
        val issues = jarr(o, "issues")
        var sel by remember { mutableIntStateOf(-1) }
        val fixed = remember { mutableStateListOf(*Array(issues.size) { it == 0 }) }
        val unfixed = fixed.count { !it }
        val inWarr = warrH > 0

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "尾款"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(if (unfixed > 0) "**$unfixed 项未修**" else "可付",
                    fontSize = 8.5.sp, color = if (unfixed > 0) ZxT.Warn else ZxT.Ok)
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth()) {
                listOf(Triple("总价", "${"%.0f".format(total)}", ZxT.Text),
                    Triple("尾款 ${"%.0f".format(holdPct * 100)}%", "${"%.0f".format(hold)}", ZxT.Warn),
                    Triple(if (inWarr) "质保中" else "**已过保**",
                        if (warrH >= 24) "${warrH / 24} 天" else "${warrH}h",
                        if (inWarr) ZxT.Ok else ZxT.Err)
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
            issues.forEachIndexed { k, it ->
                val h = it.optInt("h", 0)
                val over = h > warrH && warrH > 0
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2
                        else if (!fixed[k]) ZxT.Warn.copy(.07f) else Color.Transparent)
                    .clickable {
                        fixed[k] = !fixed[k]; sel = k
                        ctx.callTool("zx_finalpay_issue", mapOf(
                            "issue" to it.optString("n", ""),
                            "hoursAfter" to h, "fixed" to fixed[k]))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp))
                        .background(if (fixed[k]) ZxT.Ok else ZxT.Warn))
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text(it.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text,
                            lineHeight = 12.sp)
                        Text("完工后 ${h / 24} 天发现" +
                            if (over) "　**已过质保**" else "",
                            fontSize = 8.sp, color = if (over) ZxT.Err else ZxT.Text3)
                    }
                    Text(if (fixed[k]) "已修" else "**未修**", fontSize = 8.sp,
                        color = if (fixed[k]) ZxT.Ok else ZxT.Warn)
                }
            }
            residual("质保期", warrH, "尾款压着，墙裂了还能找他", !inWarr)
            verdict(inWarr && unfixed == 0, warn = inWarr && unfixed > 0,
                title = when {
                    !inWarr && unfixed > 0 -> "**已过质保且 $unfixed 项未修**——" +
                        "**这时候再找他，他可以不认**"
                    !inWarr -> "**已过质保**，尾款该付了"
                    unfixed > 0 -> "**$unfixed 项未修，尾款先别付**"
                    else -> "质保还剩 ${warrH / 24} 天，尾款可以付"
                },
                why = "**尾款的意义就是压住质保期**——" +
                    "付完了才发现墙裂，就没有筹码了")
            note("已有 moveout 是<b>退租结算</b>，" +
                "这个是<b>装修尾款</b>：<b>尾款比例</b> + <b>质保期</b> + 质保内问题登记。" +
                "<b>缺质保期会被拦</b>；<b>缺尾款比例会被拦</b>；<b>未修项数超过 0 时付全款会被拦</b>。")
        }
    }

    /* ══════════ zx_fosterdaily 寄养日报 ══════════ */
    @Composable
    fun fosterdaily(o: JSONObject, ctx: RenderContext) {
        // days:[{d:日期, w:体重, eat:进食, mood:精神}]  traceH:追溯期小时
        val days = jarr(o, "days")
        var sel by remember { mutableIntStateOf(-1) }
        val traceH = o.optInt("traceH", 0)
        val w0 = days.firstOrNull()?.optDouble("w", 0.0) ?: 0.0
        val w1 = days.lastOrNull()?.optDouble("w", 0.0) ?: 0.0
        val drop = w0 - w1
        val lowEat = days.count { it.optString("eat", "").contains("少") }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "寄养日报"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(if (drop > 0.15) "**掉 ${"%.1f".format(drop)} kg**" else "体重稳定",
                    fontSize = 8.5.sp, color = if (drop > 0.15) ZxT.Err else ZxT.Ok)
            }
            Spacer(Modifier.height(5.dp))
            days.forEachIndexed { k, d ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_foster_day", mapOf(
                            "day" to d.optString("d", ""),
                            "weight" to d.optDouble("w", 0.0),
                            "eat" to d.optString("eat", "")))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(d.optString("d", ""), fontSize = 9.sp, color = ZxT.Text2,
                        fontFamily = ZxT.Mono, modifier = Modifier.width(46.dp))
                    Column(Modifier.weight(1f)) {
                        Row {
                            Text("${"%.1f".format(d.optDouble("w", 0.0))} kg",
                                fontSize = 9.5.sp, color = ZxT.Text,
                                modifier = Modifier.width(52.dp))
                            Text(d.optString("eat", ""), fontSize = 8.5.sp,
                                color = if (d.optString("eat", "").contains("少")) ZxT.Warn
                                else ZxT.Text2)
                        }
                        Text(d.optString("mood", ""), fontSize = 8.sp, color = ZxT.Text3)
                    }
                }
            }
            residual("追溯期", traceH, "接回来之后这段时间内出问题仍算寄养方的", traceH <= 0)
            verdict(drop <= 0.15,
                title = when {
                    drop > 0.15 -> "**掉了 ${"%.1f".format(drop)} kg**——" +
                        "**$lowEat 天进食少**，接回来三天内出问题要往回追"
                    lowEat > 0 -> "体重稳，但有 $lowEat 天进食少"
                    else -> "体重稳定，${days.size} 天都正常"
                },
                why = "**寄养的问题往往在接回来之后才显现**——" +
                    "接的那天看着好好的，不代表寄养期间没问题")
            note("已有 handoff 是<b>交接那一刻</b>，" +
                "这个是<b>持有期日报</b>：<b>每天状态</b> + <b>追溯期</b>。" +
                "<b>缺每日体重会被拦</b>；<b>缺追溯期会被拦</b>；<b>日期有缺漏会被拦</b>。")
        }
    }

    /* ══════════ zx_wardhand 转科交接 ══════════ */
    @Composable
    fun wardhand(o: JSONObject, ctx: RenderContext) {
        // from:转出科  to:转入科  obsH:观察期小时  vitals:[{n:项目, v:值}]  todo:[{n:事项, done:是否完成}]
        val vitals = jarr(o, "vitals")
        val todo = jarr(o, "todo")
        val done = remember { mutableStateListOf(*Array(todo.size) { it < todo.size - 1 }) }
        var sel by remember { mutableIntStateOf(-1) }
        val obsH = o.optInt("obsH", 0)
        val left = done.count { !it }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "转科"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(if (obsH > 0) "观察 ${obsH} 小时" else "**没定观察期**",
                    fontSize = 8.5.sp, color = if (obsH > 0) ZxT.Warn else ZxT.Err)
            }
            Row(Modifier.fillMaxWidth().padding(top = 3.dp)
                .clip(RoundedCornerShape(5.dp)).background(ZxT.Surface2)
                .padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("from", "—"), fontSize = 9.5.sp, color = ZxT.Text2)
                Text(" → ", fontSize = 9.sp, color = ZxT.Text3)
                Text(o.optString("to", "—"), fontSize = 9.5.sp, color = ZxT.Text)
            }
            Spacer(Modifier.height(5.dp))
            Text("交接时生命体征", fontSize = 9.sp, color = ZxT.Text3)
            vitals.forEachIndexed { k, v ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_ward_vital", mapOf(
                            "item" to v.optString("n", ""), "value" to v.optString("v", "")))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(v.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text,
                        modifier = Modifier.weight(1f))
                    Text(v.optString("v", ""), fontSize = 9.5.sp, color = ZxT.Text2,
                        fontFamily = ZxT.Mono)
                }
            }
            Spacer(Modifier.height(4.dp))
            todo.forEachIndexed { k, t ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == 100 + k) ZxT.Surface2
                        else if (!done[k]) ZxT.Warn.copy(.06f) else Color.Transparent)
                    .clickable {
                        done[k] = !done[k]; sel = 100 + k
                        ctx.callTool("zx_ward_todo", mapOf(
                            "item" to t.optString("n", ""), "done" to done[k]))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp))
                        .background(if (done[k]) ZxT.Ok else ZxT.Surface3))
                    Spacer(Modifier.width(6.dp))
                    Text(t.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text,
                        modifier = Modifier.weight(1f), lineHeight = 12.sp)
                    Text(if (done[k]) "已完成" else "待办", fontSize = 8.sp,
                        color = if (done[k]) ZxT.Ok else ZxT.Warn)
                }
            }
            residual("观察期", obsH, "转科后这段时间内的变化仍要往回追", obsH <= 0)
            verdict(obsH > 0 && left == 0, warn = obsH > 0 && left > 0,
                title = when {
                    obsH <= 0 -> "**没定观察期**——" +
                        "**转科后出了问题，两边都说不是自己的**"
                    left > 0 -> "**$left 项待办**在 ${obsH} 小时观察期内"
                    else -> "观察期 ${obsH} 小时，待办都清了"
                },
                why = "**转科不是把人送过去就完了**——" +
                    "交接后那几个小时是最容易出事的，也是最容易被两边推的")
            note("已有 handoff 是<b>物品交接</b>，" +
                "这个是<b>病患转科</b>：<b>生命体征快照</b> + <b>观察期</b> + 待办。" +
                "<b>缺观察期会被拦</b>；<b>缺生命体征会被拦</b>；<b>缺转出/转入会被拦</b>。")
        }
    }

    /* ══════════ zx_schoolnote 家校沟通 ══════════ */
    @Composable
    fun schoolnote(o: JSONObject, ctx: RenderContext) {
        // notes:[{n:事项, who:发起方, need:需要回复, h:距回复期限小时, done:已回复}]
        val notes = jarr(o, "notes")
        val done = remember { mutableStateListOf(*Array(notes.size) { it < notes.size - 1 }) }
        var sel by remember { mutableIntStateOf(-1) }
        val overdue = notes.mapIndexedNotNull { i, n ->
            if (!done[i] && n.optInt("h", 9999) <= 0) i else null }
        val pending = done.count { !it }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "家校沟通"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(if (overdue.isNotEmpty()) "**${overdue.size} 条超期**"
                    else "$pending 条待回复", fontSize = 8.5.sp,
                    color = if (overdue.isNotEmpty()) ZxT.Err else ZxT.Text3)
            }
            Spacer(Modifier.height(5.dp))
            notes.forEachIndexed { k, nt ->
                val h = nt.optInt("h", 9999)
                val over = !done[k] && h <= 0
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2
                        else if (over) ZxT.Err.copy(.07f) else Color.Transparent)
                    .clickable {
                        done[k] = !done[k]; sel = k
                        ctx.callTool("zx_schoolnote_reply", mapOf(
                            "topic" to nt.optString("n", ""),
                            "from" to nt.optString("who", ""), "replied" to done[k]))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp))
                        .background(if (done[k]) ZxT.Ok else if (over) ZxT.Err else ZxT.Warn))
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(nt.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text,
                                modifier = Modifier.weight(1f), lineHeight = 12.sp)
                            Box(Modifier.clip(RoundedCornerShape(3.dp))
                                .background(ZxT.Surface2).padding(4.dp, 1.dp)) {
                                Text(nt.optString("who", ""), fontSize = 7.5.sp,
                                    color = ZxT.Text2)
                            }
                        }
                        Text(when {
                            done[k] -> "已回复"
                            over -> "**已超期 ${-h} 小时**"
                            else -> "需回复，剩 ${h} 小时"
                        }, fontSize = 8.sp,
                            color = if (done[k]) ZxT.Ok else if (over) ZxT.Err else ZxT.Text3)
                    }
                }
            }
            residual("在学期", o.optInt("termH", 0),
                "学期内老师仍对该生负责，假期不同", o.optInt("termH", 0) <= 0)
            verdict(overdue.isEmpty(),
                warn = overdue.isNotEmpty(),
                title = when {
                    overdue.isNotEmpty() -> "**${overdue.size} 条已超期未回复**——" +
                        "**家校沟通断了，最后吃亏的是孩子**"
                    pending > 0 -> "$pending 条待回复，都没超期"
                    else -> "都回复了"
                },
                why = "**家校沟通每条都要有回复期限**——" +
                    "只发通知不设期限，双方都以为是对方的事")
            note("已有 schoolup 是<b>升学流程</b>，" +
                "这个是<b>家校沟通流</b>：<b>每条带回复期限</b> + 超期检测。" +
                "<b>缺回复期限会被拦</b>；<b>缺发起方会被拦</b>。")
        }
    }

    /* ══════════ zx_sublet 转租 ══════════ */
    @Composable
    fun sublet(o: JSONObject, ctx: RenderContext) {
        // old:原租客  new:新租客  endH:原租客责任还剩多久  dep:押金转手  ok:房东是否同意
        val endH = o.optInt("endH", 0)
        val dep = o.optDouble("dep", 0.0)
        var aOk by remember { mutableStateOf(false) }
        var bOk by remember { mutableStateOf(false) }
        var cOk by remember { mutableStateOf(false) }
        val items = jarr(o, "items")
        val done = remember { mutableStateListOf(*Array(items.size) { it != items.size - 1 }) }
        var sel by remember { mutableIntStateOf(-1) }
        val left = done.count { !it }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "转租"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(if (endH > 0) "原租客还挂着 ${endH / 24} 天" else "责任已清",
                    fontSize = 8.5.sp, color = if (endH > 0) ZxT.Warn else ZxT.Ok)
            }
            Row(Modifier.fillMaxWidth().padding(top = 3.dp)
                .clip(RoundedCornerShape(5.dp)).background(ZxT.Surface2)
                .padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("old", "—"), fontSize = 9.5.sp, color = ZxT.Text2)
                Text(" → ", fontSize = 9.sp, color = ZxT.Text3)
                Text(o.optString("new", "—"), fontSize = 9.5.sp, color = ZxT.Text)
                Spacer(Modifier.width(6.dp))
                Text("押金 ${"%.0f".format(dep)}", fontSize = 8.5.sp, color = ZxT.Text3,
                    fontFamily = ZxT.Mono)
            }
            Spacer(Modifier.height(5.dp))
            items.forEachIndexed { k, it ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2
                        else if (!done[k]) ZxT.Warn.copy(.07f) else Color.Transparent)
                    .clickable {
                        done[k] = !done[k]; sel = k
                        ctx.callTool("zx_sublet_item", mapOf(
                            "item" to it.optString("n", ""), "done" to done[k]))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp))
                        .background(if (done[k]) ZxT.Ok else ZxT.Warn))
                    Spacer(Modifier.width(6.dp))
                    Text(it.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text,
                        modifier = Modifier.weight(1f), lineHeight = 12.sp)
                    Text(if (done[k]) "已办" else "待办", fontSize = 8.sp,
                        color = if (done[k]) ZxT.Ok else ZxT.Warn)
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth()) {
                listOf(Triple(o.optString("old", "原租客"), aOk, { aOk = !aOk }),
                    Triple(o.optString("new", "新租客"), bOk, { bOk = !bOk }),
                    Triple("房东", cOk, { cOk = !cOk })
                ).forEach { (lbl, stt, act) ->
                    Box(Modifier.weight(1f).padding(end = 3.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (stt) ZxT.Ok.copy(.20f) else ZxT.Surface2)
                        .clickable(onClick = act).padding(6.dp, 4.dp)) {
                        Column {
                            Text(lbl, fontSize = 9.sp, color = ZxT.Text, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text(if (stt) "已签字 ✓" else "待签字", fontSize = 8.sp,
                                color = if (stt) ZxT.Ok else ZxT.Text3)
                        }
                    }
                }
            }
            residual("原租客责任", endH,
                "转租后这段时间内房子出问题，房东仍先找原租客", endH <= 0)
            verdict(left == 0 && aOk && bOk && cOk,
                warn = left == 0 && !(aOk && bOk && cOk),
                title = when {
                    left > 0 -> "**$left 项待办**没办完，原租客责任还不能清"
                    !(aOk && bOk && cOk) -> "**还差一方签字**——" +
                        "**转租三方缺一，房东可以不认**"
                    else -> "三方都签了，原租客还剩 ${endH / 24} 天责任"
                },
                why = "**转租不是原租客把钥匙给新租客就完了**——" +
                    "没经房东同意，原租客的责任一直挂着")
            note("已有 lease 是<b>租房合同</b>，" +
                "这个是<b>转租</b>：<b>三方签字</b> + <b>原租客责任终止点</b> + 押金转手。" +
                "<b>缺房东同意会被拦</b>；<b>缺原租客责任终止点会被拦</b>。")
        }
    }

    /* ══════════ zx_inspecthand 周期巡检 ══════════ */
    @Composable
    fun inspecthand(o: JSONObject, ctx: RenderContext) {
        // last:上次巡检日期  cycD:周期天  find:[{n:发现项, lv:级别, fix:已处理}]
        val find = jarr(o, "find")
        val fix = remember { mutableStateListOf(*Array(find.size) { it == 0 }) }
        var sel by remember { mutableIntStateOf(-1) }
        val cycD = o.optInt("cycD", 0)
        val sinceD = o.optInt("sinceD", 0)
        val overdue = sinceD > cycD && cycD > 0
        val un = fix.count { !it }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "巡检"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(if (overdue) "**超期 ${sinceD - cycD} 天**" else "距上次 ${sinceD} 天",
                    fontSize = 8.5.sp, color = if (overdue) ZxT.Err else ZxT.Text3)
            }
            Row(Modifier.fillMaxWidth().padding(top = 3.dp)
                .clip(RoundedCornerShape(5.dp)).background(ZxT.Surface2)
                .padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("上次", fontSize = 8.sp, color = ZxT.Text3)
                Spacer(Modifier.width(5.dp))
                Text(o.optString("last", "—"), fontSize = 9.sp, color = ZxT.Text)
                Spacer(Modifier.width(8.dp))
                Text("周期", fontSize = 8.sp, color = ZxT.Text3)
                Spacer(Modifier.width(5.dp))
                Text("$cycD 天", fontSize = 9.sp, color = ZxT.Text2)
            }
            Spacer(Modifier.height(5.dp))
            find.forEachIndexed { k, f ->
                val lv = f.optString("lv", "一般")
                val col = when (lv) { "严重" -> ZxT.Err; "注意" -> ZxT.Warn; else -> ZxT.Text2 }
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2
                        else if (!fix[k]) col.copy(.07f) else Color.Transparent)
                    .clickable {
                        fix[k] = !fix[k]; sel = k
                        ctx.callTool("zx_inspect_item", mapOf(
                            "item" to f.optString("n", ""),
                            "level" to lv, "fixed" to fix[k]))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp))
                        .background(if (fix[k]) ZxT.Ok else col))
                    Spacer(Modifier.width(6.dp))
                    Text(f.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text,
                        modifier = Modifier.weight(1f), lineHeight = 12.sp)
                    Text(lv, fontSize = 8.sp, color = col)
                    Spacer(Modifier.width(5.dp))
                    Text(if (fix[k]) "已处理" else "**未处理**", fontSize = 8.sp,
                        color = if (fix[k]) ZxT.Ok else ZxT.Warn)
                }
            }
            residual("本轮", (cycD - sinceD) * 24,
                "上次巡检到本次之间出的问题，算上一轮没查出来",
                cycD - sinceD <= 0)
            verdict(!overdue && un == 0, warn = !overdue && un > 0,
                title = when {
                    overdue -> "**已超期 ${sinceD - cycD} 天**——" +
                        "**这段时间的空档没人负责**"
                    un > 0 -> "**$un 项未处理**"
                    else -> "在周期内，发现项都处理了"
                },
                why = "**巡检的价值在两次之间**——" +
                    "只记本次发现什么，不记上次到这次隔了多久，空档就没人管")
            note("已有 checklist 是<b>一次性检查</b>，" +
                "这个是<b>周期巡检</b>：<b>上次日期 + 周期</b> + 超期检测 + 发现项分级。" +
                "<b>缺上次巡检会被拦</b>；<b>缺周期会被拦</b>；<b>超期不标会被拦</b>。")
        }
    }

    /* ══════════ zx_projhandover 项目移交 ══════════ */
    @Composable
    fun projhandover(o: JSONObject, ctx: RenderContext) {
        // old:原负责人  new:新负责人  transH:过渡期小时  oncallH:随叫随到时限  open:[{n:未结事项, who:归谁}]
        val open = jarr(o, "open")
        var sel by remember { mutableIntStateOf(-1) }
        val transH = o.optInt("transH", 0)
        val oncallH = o.optInt("oncallH", 0)
        val mine = open.count { it.optString("who", "") == o.optString("old", "") }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "项目移交"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(if (transH > 0) "过渡 ${transH / 24} 天" else "**没定过渡期**",
                    fontSize = 8.5.sp, color = if (transH > 0) ZxT.Warn else ZxT.Err)
            }
            Row(Modifier.fillMaxWidth().padding(top = 3.dp)
                .clip(RoundedCornerShape(5.dp)).background(ZxT.Surface2)
                .padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("old", "—"), fontSize = 9.5.sp, color = ZxT.Text2)
                Text(" → ", fontSize = 9.sp, color = ZxT.Text3)
                Text(o.optString("new", "—"), fontSize = 9.5.sp, color = ZxT.Text)
            }
            Spacer(Modifier.height(5.dp))
            open.forEachIndexed { k, it ->
                val who = it.optString("who", "")
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_projhand_item", mapOf(
                            "item" to it.optString("n", ""), "owner" to who))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(CircleShape)
                        .background(if (who == o.optString("old", "")) ZxT.Warn else ZxT.Ok))
                    Spacer(Modifier.width(6.dp))
                    Text(it.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text,
                        modifier = Modifier.weight(1f), lineHeight = 12.sp)
                    Text(who.ifBlank { "**没定**" }, fontSize = 8.sp,
                        color = if (who.isBlank()) ZxT.Err else ZxT.Text3)
                }
            }
            residual("过渡期", transH,
                "过渡期内原负责人仍要随叫随到（$oncallH 小时内响应）", transH <= 0)
            verdict(transH > 0 && mine == 0,
                warn = transH > 0 && mine > 0,
                title = when {
                    transH <= 0 -> "**没定过渡期**——" +
                        "**移交完原负责人就不接电话了，新负责人接不住**"
                    mine > 0 -> "**$mine 项还挂在原负责人身上**，过渡期 ${transH / 24} 天"
                    else -> "过渡期 ${transH / 24} 天，未结事项都归新负责人了"
                },
                why = "**移交不等于接住**——" +
                    "文档交了不等于新负责人能上手，中间必须有过渡期")
            note("已有 handoff 是<b>一次性交接</b>，" +
                "这个是<b>项目移交</b>：<b>过渡期</b> + <b>随叫随到时限</b> + 未结事项归属。" +
                "<b>缺过渡期会被拦</b>；<b>缺响应时限会被拦</b>；<b>未结事项没定归属会被拦</b>。")
        }
    }

    /* ══════════ zx_stocktake 盘点 ══════════ */
    @Composable
    fun stocktake(o: JSONObject, ctx: RenderContext) {
        // last:上次盘点  cycD:周期  items:[{n:品名, book:账面, real:实盘, why:差异原因}]
        val items = jarr(o, "items")
        var sel by remember { mutableIntStateOf(-1) }
        val cycD = o.optInt("cycD", 0)
        val sinceD = o.optInt("sinceD", 0)
        val gap = items.map { it.optInt("book", 0) - it.optInt("real", 0) }
        val loss = gap.mapIndexedNotNull { i, g -> if (g > 0) i else -1 }.filter { it >= 0 }
        val gain = gap.mapIndexedNotNull { i, g -> if (g < 0) i else -1 }.filter { it >= 0 }
        val noWhy = items.mapIndexedNotNull { i, it ->
            if (gap[i] != 0 && it.optString("why", "").isBlank()) i else null }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "盘点"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(if (loss.isNotEmpty()) "**${loss.size} 项盘亏**" else "账实相符",
                    fontSize = 8.5.sp, color = if (loss.isNotEmpty()) ZxT.Err else ZxT.Ok)
            }
            Row(Modifier.fillMaxWidth().padding(top = 3.dp)
                .clip(RoundedCornerShape(5.dp)).background(ZxT.Surface2)
                .padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("上次", fontSize = 8.sp, color = ZxT.Text3)
                Spacer(Modifier.width(5.dp))
                Text(o.optString("last", "—"), fontSize = 9.sp, color = ZxT.Text)
                Spacer(Modifier.width(8.dp))
                Text("隔", fontSize = 8.sp, color = ZxT.Text3)
                Spacer(Modifier.width(5.dp))
                Text("$sinceD 天", fontSize = 9.sp,
                    color = if (cycD > 0 && sinceD > cycD) ZxT.Err else ZxT.Text2)
            }
            Spacer(Modifier.height(5.dp))
            items.forEachIndexed { k, it ->
                val g = gap[k]
                val col = if (g > 0) ZxT.Err else if (g < 0) ZxT.Warn else ZxT.Ok
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2
                        else if (g != 0) col.copy(.07f) else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_stock_item", mapOf(
                            "item" to it.optString("n", ""), "book" to it.optInt("book", 0),
                            "real" to it.optInt("real", 0), "gap" to g))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(it.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text)
                        Row {
                            Text("账 ${it.optInt("book", 0)}", fontSize = 8.sp,
                                color = ZxT.Text3, fontFamily = ZxT.Mono)
                            Spacer(Modifier.width(6.dp))
                            Text("实 ${it.optInt("real", 0)}", fontSize = 8.sp,
                                color = ZxT.Text3, fontFamily = ZxT.Mono)
                            if (g != 0) {
                                Spacer(Modifier.width(6.dp))
                                Text(if (g > 0) "盘亏 $g" else "盘盈 ${-g}",
                                    fontSize = 8.sp, color = col)
                            }
                        }
                        if (g != 0)
                            Text(if (it.optString("why", "").isBlank()) "**没写原因**"
                                else it.optString("why", ""),
                                fontSize = 8.sp,
                                color = if (it.optString("why", "").isBlank()) ZxT.Err
                                else ZxT.Text3, lineHeight = 11.sp)
                    }
                }
            }
            residual("追溯窗", (cycD.coerceAtMost(sinceD)) * 24,
                "上次到这次之间，只能追到这么远", sinceD <= 0)
            verdict(noWhy.isEmpty(),
                warn = noWhy.isNotEmpty(),
                title = when {
                    noWhy.isNotEmpty() -> "**${noWhy.size} 项有差异但没写原因**——" +
                        "**不写原因就不知道是丢了、记错了还是被拿了**"
                    loss.isNotEmpty() -> "${loss.size} 项盘亏、${gain.size} 项盘盈，都写了原因"
                    else -> "账实相符"
                },
                why = "**盘点只给差异数不给原因，等于没盘**——" +
                    "盘亏 3 个，可能是丢了、记错了、被拿了，三种处置完全不同")
            note("已有 inventory 是<b>库存清单</b>，" +
                "这个是<b>盘点</b>：<b>账面 vs 实盘</b> + <b>差异必须写原因</b> + 追溯窗。" +
                "<b>缺上次盘点会被拦</b>；<b>有差异缺原因会被拦</b>。")
        }
    }

    /* ══════════ zx_offboard 离职交接 ══════════ */
    @Composable
    fun offboard(o: JSONObject, ctx: RenderContext) {
        // who:离职人  items:[{n:交接项, done:是否完成}]  clawH:追责期小时  revoke:权限是否已回收
        val items = jarr(o, "items")
        val done = remember { mutableStateListOf(*Array(items.size) { it != items.size - 1 }) }
        var sel by remember { mutableIntStateOf(-1) }
        var revoked by remember { mutableStateOf(false) }
        val clawH = o.optInt("clawH", 0)
        val left = done.count { !it }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "离职交接"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(if (left > 0) "**$left 项没交**" else "都交了",
                    fontSize = 8.5.sp, color = if (left > 0) ZxT.Warn else ZxT.Ok)
            }
            Spacer(Modifier.height(5.dp))
            items.forEachIndexed { k, it ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2
                        else if (!done[k]) ZxT.Warn.copy(.07f) else Color.Transparent)
                    .clickable {
                        done[k] = !done[k]; sel = k
                        ctx.callTool("zx_offboard_item", mapOf(
                            "item" to it.optString("n", ""), "done" to done[k]))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp))
                        .background(if (done[k]) ZxT.Ok else ZxT.Warn))
                    Spacer(Modifier.width(6.dp))
                    Text(it.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text,
                        modifier = Modifier.weight(1f), lineHeight = 12.sp)
                    Text(if (done[k]) "已交" else "**没交**", fontSize = 8.sp,
                        color = if (done[k]) ZxT.Ok else ZxT.Warn)
                }
            }
            Spacer(Modifier.height(5.dp))
            Row(Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(if (revoked) ZxT.Ok.copy(.10f) else ZxT.Err.copy(.10f))
                .clickable { revoked = !revoked }.padding(7.dp, 5.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("权限回收", fontSize = 9.sp, color = ZxT.Text3,
                    modifier = Modifier.weight(1f))
                Text(if (revoked) "已回收 ✓" else "**未回收**", fontSize = 9.sp,
                    color = if (revoked) ZxT.Ok else ZxT.Err)
            }
            residual("追责期", clawH,
                "离职后这段时间内发现他在职时的问题，仍能追", clawH <= 0)
            verdict(left == 0 && revoked,
                warn = left == 0 && !revoked,
                title = when {
                    left > 0 -> "**$left 项没交接**——**东西没拿回来，人走了就说不清**"
                    !revoked -> "**权限还没回收**——" +
                        "**交接完了但账号还开着，这不叫离职**"
                    else -> "都交了，权限也回收了，追责期 ${clawH / 24} 天"
                },
                why = "**离职交接有两件独立的事：东西交回来、权限收回去**——" +
                    "只做一件都不叫交接完成")
            note("已有 handoff 是<b>物品交接</b>，" +
                "这个是<b>离职交接</b>：交接项 + <b>权限回收</b> + <b>追责期</b>。" +
                "<b>缺追责期会被拦</b>；<b>未回收权限就判完成会被拦</b>。")
        }
    }

    /* ══════════ zx_revoke 权限回收 ══════════ */
    @Composable
    fun revoke(o: JSONObject, ctx: RenderContext) {
        // who:被回收人  items:[{n:权限, t:类型 系统/令牌/密钥/物理, off:是否已关, lagH:生效滞后小时}]
        val items = jarr(o, "items")
        val off = remember { mutableStateListOf(*Array(items.size) { it < items.size - 2 }) }
        var sel by remember { mutableIntStateOf(-1) }
        val residualItems = items.mapIndexedNotNull { i, it ->
            if (!off[i]) i else null }
        val phys = items.mapIndexedNotNull { i, it ->
            if (it.optString("t", "") == "物理") i else null }
        val maxLag = items.maxOfOrNull { it.optInt("lagH", 0) } ?: 0

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "权限回收"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(if (residualItems.isNotEmpty()) "**${residualItems.size} 项还开着**"
                    else "都关了", fontSize = 8.5.sp,
                    color = if (residualItems.isNotEmpty()) ZxT.Err else ZxT.Ok)
            }
            Text(o.optString("who", ""), fontSize = 8.5.sp, color = ZxT.Text2,
                modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.height(5.dp))
            items.forEachIndexed { k, it ->
                val t = it.optString("t", "系统")
                val lag = it.optInt("lagH", 0)
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2
                        else if (!off[k]) ZxT.Err.copy(.07f) else Color.Transparent)
                    .clickable {
                        off[k] = !off[k]; sel = k
                        ctx.callTool("zx_revoke_item", mapOf(
                            "item" to it.optString("n", ""), "type" to t, "off" to off[k]))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp))
                        .background(if (off[k]) ZxT.Ok else ZxT.Err))
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(it.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text,
                                modifier = Modifier.weight(1f), maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Box(Modifier.clip(RoundedCornerShape(3.dp))
                                .background(if (t == "物理") ZxT.Warn.copy(.18f)
                                else ZxT.Surface2).padding(4.dp, 1.dp)) {
                                Text(t, fontSize = 7.5.sp,
                                    color = if (t == "物理") ZxT.Warn else ZxT.Text3)
                            }
                        }
                        Text(if (off[k]) "已关闭" +
                            if (lag > 0) "（滞后 ${lag}h 才真正失效）" else ""
                            else "**还开着**", fontSize = 8.sp,
                            color = if (off[k]) if (lag > 0) ZxT.Warn else ZxT.Ok else ZxT.Err)
                    }
                }
            }
            residual("生效滞后", maxLag,
                "点了关闭不等于立刻失效，令牌要等过期", maxLag <= 0)
            verdict(residualItems.isEmpty(),
                title = when {
                    residualItems.isNotEmpty() -> "**${residualItems.size} 项还开着**——" +
                        "**系统权限关了但密钥还在他手里，那不算回收**"
                    phys.isNotEmpty() && maxLag > 0 -> "系统权限都关了，但" +
                        "**${phys.size} 项物理钥匙 + 令牌滞后 ${maxLag}h**"
                    else -> "都关了且已生效"
                },
                why = "**回收要区分系统权限、令牌、密钥、物理钥匙**——" +
                    "四类的失效时点完全不同，混在一起标「已回收」是假的")
            note("已有 annualaudit 是<b>证照年审</b>，" +
                "这个是<b>权限回收</b>：<b>四类分开</b> + <b>生效滞后</b> + 残留项检测。" +
                "<b>缺权限类型会被拦</b>；<b>还有开着就判完成会被拦</b>。")
        }
    }

    /* ══════════ zx_warrtrack 质保追踪 ══════════ */
    @Composable
    fun warrtrack(o: JSONObject, ctx: RenderContext) {
        // item:物品  endH:保修截止小时  issues:[{n:问题, h:距截止小时, told:是否已报, fixed:是否已修}]
        val issues = jarr(o, "issues")
        val told = remember { mutableStateListOf(*Array(issues.size) { it == 0 }) }
        val fixed = remember { mutableStateListOf(*Array(issues.size) { false }) }
        var sel by remember { mutableIntStateOf(-1) }
        val endH = o.optInt("endH", 0)
        val inWarr = endH > 0
        val untold = issues.mapIndexedNotNull { i, it ->
            if (!told[i]) i else null }
        val expiring = issues.mapIndexedNotNull { i, it ->
            val margin = endH - it.optInt("h", 0)
            if (inWarr && margin >= 0 && margin <= 24 * 7) i else null }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "质保追踪"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(if (inWarr) "保内 ${endH / 24} 天" else "**已过保**",
                    fontSize = 8.5.sp, color = if (inWarr) ZxT.Ok else ZxT.Err)
            }
            Text(o.optString("item", ""), fontSize = 8.5.sp, color = ZxT.Text2,
                modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.height(5.dp))
            issues.forEachIndexed { k, it ->
                val h = it.optInt("h", 0)
                val margin = endH - h
                val near = inWarr && margin in 0..(24 * 7)
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2
                        else if (near) ZxT.Warn.copy(.07f) else Color.Transparent)
                    .clickable {
                        told[k] = !told[k]; sel = k
                        ctx.callTool("zx_warr_issue", mapOf(
                            "issue" to it.optString("n", ""),
                            "hoursToEnd" to margin, "told" to told[k]))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp))
                        .background(if (told[k]) ZxT.Ok else ZxT.Warn))
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text(it.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text,
                            lineHeight = 12.sp)
                        Text(when {
                            !inWarr -> "**已过保**，只能自费"
                            near -> "**距过保仅 ${margin / 24} 天**"
                            else -> "距过保 ${margin / 24} 天"
                        }, fontSize = 8.sp,
                            color = if (!inWarr) ZxT.Err else if (near) ZxT.Warn else ZxT.Text3)
                    }
                    Text(if (told[k]) "已报" else "**未报**", fontSize = 8.sp,
                        color = if (told[k]) ZxT.Ok else ZxT.Warn)
                }
            }
            residual("保修期", endH, "这段时间内报修免费，过了就自费", !inWarr)
            verdict(inWarr && untold.isEmpty(),
                warn = inWarr && untold.isNotEmpty(),
                title = when {
                    !inWarr -> "**已过保**——**这时候报修只能自费**"
                    untold.isNotEmpty() -> "**${untold.size} 个问题还没报**，" +
                        "保内只剩 ${endH / 24} 天"
                    expiring.isNotEmpty() -> "${expiring.size} 个问题快过保了，先报再说"
                    else -> "保内 ${endH / 24} 天，问题都报了"
                },
                why = "**在保 ≠ 修了**——" +
                    "发现了不报，过保那天它就变成自费了")
            note("已有 warranty 是<b>保修条款</b>，" +
                "这个是<b>质保追踪</b>：<b>距过保倒计时</b> + <b>问题是否已报</b> + 临界预警。" +
                "<b>缺保修截止会被拦</b>；<b>有未报问题会被拦</b>。")
        }
    }
}
