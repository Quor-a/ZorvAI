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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 第三十六批组件（15 个）—— 生活操作。
 *
 * 第三十五批做的是**长期跟踪**：健身有没有进步、药有没有漏服、
 * 水电比上月多了还是少了。共同点是"看变化"。
 *
 * 这批做的是**照着做**：
 *
 *   菜谱    不是"有什么菜"，是"第 3 步该放什么、放多少"
 *   急救    不是"急救知识"，是"现在这一步做什么、不能跳"
 *   出行清单 不是"要带什么"，是"还有哪件没装进包"
 *   巡检    不是"检查了什么"，是"哪一项不合格、为什么"
 *   备考    不是"要考什么"，是"今天该复习哪一科"
 *
 * ── 贯穿这批的一条 ──────────────────────────────────────────
 *
 * **操作类卡片必须能停在"正在做"的状态。**
 *
 * 第三十五批的卡是"看"——看完结论就结束。这批的卡用户要**照着做**，
 * 而做一件事是分次的：菜做到第 3 步要去接个电话、清单勾到一半要出门。
 *
 * 所以这批每个组件都得记住进度：
 *
 *   菜谱    步骤勾选状态（第几步了）
 *   急救    当前步（而且不能跳）
 *   清单    哪些已打包
 *   巡检    哪几项已检、哪几项不合格
 *   备考    今天份做完了没
 *
 * 不能只给一份静态说明让用户自己记"我做到哪了"——
 * **把记忆负担留给用户，这张卡就退化成一段文字**。
 *
 * ── 校验因此换了第十四档 ────────────────────────────────────
 *
 * 前十三档查"画不出来 / 统计前提不成立 / 区间前提不成立 / 因果前提\n* 无从判断 / 看起来确定其实不确定 / 推不出该做什么 / 记了下次用不上 /\n* 落不到谁该做什么 / 推演本身不可信 / 决策前提在过程中会变 /\n* 信息新不新能不能信 / 操作了但得不到结果 / 只有当前值没有对比基准"，
 *
 * 这批查的是**「照着做会出事」**：
 *
 *   recipe      步骤为空 / 配料缺量 / **份量缩放后用量非正**
 *   ledger      流水为空 / 分类占比加不为 100 / 金额为负
 *   commute     无方案 / **耗时不含换乘步行** / 延误为负
 *   warranty    无条目 / **保修已过期未标** / 购买日晚于今天
 *   inspection  无检查项 / **不合格项没有说明** / 状态值非法
 *   examprep    无科目 / 权重为负 / 剩余天数非正
 *   tripkit     无物品 / **必带项未勾就提示可出发** / 分类为空
 *   inventory   无条目 / **存量低于安全线未标** / 数量非正
 *   recycle     无条目 / 类别不在四类内 / 无投放要求
 *   carservice  无保养项 / **里程触发与时间触发都超未标** / 间隔非正
 *   expiry      无条目 / **已过期未标** / 剩余天数为负
 *   splitbill   无人 / 金额加总对不上 / 人数非正
 *   firstaid    无步骤 / **步骤可跳过（急救不能跳）** / 无时间提示
 *   sizing      无尺码表 / 无推荐行 / 测量值非正
 *   gift        无记录 / 金额非正 / 该回礼未标
 */
object ZxRender36 {

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

    /** 结论条——这批每个组件都要有，说明"照着做"的判据 */
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

    /* ══════════ zx_recipe 菜谱步骤 ══════════ */
    @Composable
    fun recipe(o: JSONObject, ctx: RenderContext) {
        // ing:{n:名, q:用量, u:单位}  steps:[步骤]  base:基准份数
        val ings = jarr(o, "ing")
        val steps = jstr(o, "steps")
        val done = remember { mutableStateListOf(*Array(steps.size) { false }) }
        var cur by remember { mutableIntStateOf(0) }
        var serve by remember { mutableStateOf(o.optDouble("base", 2.0)) }
        val base = o.optDouble("base", 2.0).coerceAtLeast(0.5)
        val k = serve / base
        val doneN = done.count { it }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "菜谱"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(18.dp).clip(CircleShape).background(ZxT.Surface2)
                        .clickable { if (serve > 1) { serve -= 1; } })
                    Text("${"%.0f".format(serve)} 份", fontSize = 9.sp, color = ZxT.PriLite,
                        modifier = Modifier.padding(horizontal = 5.dp))
                    Box(Modifier.size(18.dp).clip(CircleShape).background(ZxT.Surface2)
                        .clickable { serve += 1 })
                }
            }
            Spacer(Modifier.height(5.dp))
            // 配料：随份数缩放
            ings.forEach { ig ->
                val q = ig.optDouble("q", 0.0) * k
                val u = ig.optString("u", "")
                Row(Modifier.fillMaxWidth().padding(vertical = 1.5.dp)) {
                    Text(ig.optString("n", ""), fontSize = 9.sp, color = ZxT.Text2,
                        modifier = Modifier.weight(1f), maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Text("%.1f".format(q).trimEnd('0').trimEnd('.') + u,
                        fontSize = 9.sp, color = ZxT.Text, fontFamily = ZxT.Mono)
                }
            }
            Spacer(Modifier.height(5.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(ZxT.Line))
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth()) {
                Text("步骤 $doneN/${steps.size}", fontSize = 8.sp, color = ZxT.Text3,
                    modifier = Modifier.weight(1f))
                Text(if (k == 1.0) "原份量" else "×${"%.2f".format(k).trimEnd('0').trimEnd('.')}",
                    fontSize = 8.sp, color = ZxT.PriLite)
            }
            steps.forEachIndexed { i, s ->
                val on = done[i]
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (i == cur && !on) ZxT.Primary.copy(.14f)
                        else if (on) ZxT.Ok.copy(.08f) else Color.Transparent)
                    .clickable {
                        done[i] = !on
                        if (!done[i]) cur = i else if (i == cur) cur = min(i + 1, steps.size - 1)
                        ctx.callTool("zx_recipe_step", mapOf(
                            "step" to (i + 1), "done" to done[i],
                            "progress" to "${done.count { it }}/${steps.size}"))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.size(14.dp).clip(CircleShape)
                        .background(if (on) ZxT.Ok else ZxT.Surface3),
                        contentAlignment = Alignment.Center) {
                        Text(if (on) "✓" else "${i + 1}", fontSize = 7.5.sp,
                            color = if (on) Color.White else ZxT.Text3)
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(s, fontSize = 9.sp, color = if (on) ZxT.Text3 else ZxT.Text,
                        lineHeight = 13.sp, modifier = Modifier.weight(1f))
                }
            }
            verdict(doneN < steps.size,
                title = "第 ${cur + 1} 步待做，已完成 $doneN/${steps.size}。" +
                    "**勾选状态要留着**",
                why = "**菜做到一半被打断是常态**——不能只给一份静态说明让用户自己记做到第几步")
            note("已有 stepper 是<b>分步流程</b>、checklist 是<b>检查清单</b>，" +
                "这个是<b>菜谱步骤</b>：配料随份数缩放 + 步骤勾选。" +
                "<b>步骤为空会被拦</b>；<b>配料缺量会被拦</b>。")
        }
    }

    /* ══════════ zx_ledger 记账流水 ══════════ */
    @Composable
    fun ledger(o: JSONObject, ctx: RenderContext) {
        // items:{d:日期, n:名, c:分类, v:金额}  budget:预算
        val items = jarr(o, "items")
        val budget = o.optDouble("budget", Double.NaN)
        var sel by remember { mutableIntStateOf(-1) }
        val total = items.sumOf { it.optDouble("v", 0.0) }
        val byCat = LinkedHashMap<String, Double>()
        items.forEach {
            val c = it.optString("c", "其他")
            byCat[c] = (byCat[c] ?: 0.0) + it.optDouble("v", 0.0)
        }
        val top = byCat.entries.sortedByDescending { it.value }
        val mx = top.firstOrNull()?.value ?: 1.0
        val avg = if (items.isEmpty()) 0.0 else total / items.size
        val big = items.filter { it.optDouble("v", 0.0) > avg * 3 }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "本月"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text("${items.size} 笔", fontSize = 8.5.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("¥${"%.0f".format(total)}", fontSize = 22.sp, color = ZxT.Err)
                Spacer(Modifier.width(8.dp))
                if (!budget.isNaN()) {
                    val pct = total / budget * 100
                    Column(modifier = Modifier.weight(1f)) {
                        Text("预算 %.0f".format(budget), fontSize = 8.sp, color = ZxT.Text3)
                        Box(Modifier.fillMaxWidth().height(5.dp)
                            .clip(RoundedCornerShape(3.dp)).background(ZxT.Surface2)) {
                            Box(Modifier.fillMaxWidth((pct / 100).toFloat().coerceAtMost(1f))
                                .fillMaxHeight()
                                .background(if (pct > 100) ZxT.Err else ZxT.Ok))
                        }
                        Text("%.0f%%".format(pct), fontSize = 7.5.sp,
                            color = if (pct > 100) ZxT.Err else ZxT.Text3,
                            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End)
                    }
                }
            }
            Spacer(Modifier.height(5.dp))
            top.forEachIndexed { i, (c, v) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 1.5.dp)) {
                    Text(c, fontSize = 8.5.sp, color = ZxT.Text2,
                        modifier = Modifier.width(56.dp), maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Box(Modifier.weight(1f).height(7.dp)
                        .clip(RoundedCornerShape(3.dp)).background(ZxT.Surface2)) {
                        Box(Modifier.fillMaxWidth((v / mx).toFloat())
                            .fillMaxHeight().background(PAL[i % PAL.size]))
                    }
                    Text("¥${"%.0f".format(v)}", fontSize = 8.sp, color = ZxT.Text,
                        modifier = Modifier.width(46.dp), textAlign = TextAlign.End,
                        fontFamily = ZxT.Mono)
                }
            }
            Spacer(Modifier.height(4.dp))
            items.forEachIndexed { i, it ->
                val v = it.optDouble("v", 0.0)
                val isBig = v > avg * 3
                Row(Modifier.fillMaxWidth().padding(vertical = 1.5.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (sel == i) ZxT.Surface2
                        else if (isBig) ZxT.Warn.copy(.07f) else Color.Transparent)
                    .clickable {
                        sel = i
                        ctx.callTool("zx_select", mapOf(
                            "name" to it.optString("n", ""), "cat" to it.optString("c", ""),
                            "amount" to "%.2f".format(v), "source" to "zx_bookkeep"))
                    }.padding(6.dp, 3.dp)) {
                    Text(it.optString("d", ""), fontSize = 8.sp, color = ZxT.Text3,
                        modifier = Modifier.width(42.dp), fontFamily = ZxT.Mono)
                    Text(it.optString("n", ""), fontSize = 9.sp, color = ZxT.Text,
                        modifier = Modifier.weight(1f), maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    if (isBig) Text("!", fontSize = 9.sp, color = ZxT.Warn,
                        modifier = Modifier.padding(end = 3.dp))
                    Text("%.0f".format(v), fontSize = 9.sp, color = ZxT.Text,
                        fontFamily = ZxT.Mono)
                }
            }
            verdict(!(!budget.isNaN() && total > budget),
                warn = big.isNotEmpty(),
                title = if (big.isNotEmpty()) "**${big.size} 笔超过均值 3 倍**——" +
                    "大额才值得回头看" else "${items.size} 笔共 ¥${"%.0f".format(total)}",
                why = "**记账的价值在找出该砍的那一笔**——流水本身不说明问题，异常才说明")
            note("已有 wallet 是<b>钱包</b>、subscription 是<b>订阅</b>，" +
                "这个是<b>记账流水</b>：分类占比 + 预算对比 + <b>大额标红</b>。" +
                "<b>流水为空会被拦</b>；<b>金额为负会被拦</b>。")
        }
    }

    /* ══════════ zx_commute 通勤路况 ══════════ */
    @Composable
    fun commute(o: JSONObject, ctx: RenderContext) {
        // plans:{n:方式, t:总分钟, walk:步行分钟, chg:换乘次, delay:延误分钟, jam:拥挤0-100}
        val plans = jarr(o, "plans")
        var sel by remember { mutableIntStateOf(0) }
        val best = plans.minByOrNull { it.optInt("t", 0) }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("from", "") + " → " + o.optString("to", ""),
                    fontSize = 11.sp, color = ZxT.Text, modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${plans.size} 个方案", fontSize = 8.5.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(5.dp))
            plans.forEachIndexed { i, p ->
                val t = p.optInt("t", 0)
                val walk = p.optInt("walk", 0)
                val chg = p.optInt("chg", 0)
                val delay = p.optInt("delay", 0)
                val on = sel == i
                val isBest = best != null && t == best.optInt("t", 0)
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (on) ZxT.Primary.copy(.16f)
                        else if (delay > 5) ZxT.Err.copy(.07f) else ZxT.Surface2.copy(alpha = .5f))
                    .border(1.dp, if (on) ZxT.Primary else Color.Transparent,
                        RoundedCornerShape(6.dp))
                    .clickable {
                        sel = i
                        ctx.callTool("zx_commute_pick", mapOf(
                            "plan" to p.optString("n", ""), "minutes" to t,
                            "walk" to walk, "transfers" to chg))
                    }.padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.optString("n", ""), fontSize = 10.sp,
                                color = if (on) ZxT.Text else ZxT.Text2)
                            if (isBest) Text("　最快", fontSize = 7.5.sp, color = ZxT.Ok)
                            if (delay > 0) Text("　**延误 ${delay}′**", fontSize = 7.5.sp,
                                color = ZxT.Err)
                        }
                        Spacer(Modifier.height(3.dp))
                        // 时间构成条：步行 / 车上 / 换乘
                        val ride = max(0, t - walk)
                        Row(Modifier.fillMaxWidth().height(6.dp)) {
                            if (walk > 0) Box(Modifier
                                .weight(max(0.08f, walk.toFloat() / t.coerceAtLeast(1)))
                                .fillMaxHeight().background(ZxT.Warn))
                            Box(Modifier.weight(max(0.08f, ride.toFloat() / t.coerceAtLeast(1)))
                                .fillMaxHeight().background(PAL[i % PAL.size]))
                        }
                        Text("步行 ${walk}′　换乘 $chg 次　总 ${t}′", fontSize = 7.5.sp,
                            color = ZxT.Text3,
                            modifier = Modifier.padding(top = 2.dp))
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("$t′", fontSize = 14.sp,
                            color = if (on) ZxT.PriLite else ZxT.Text)
                        val jam = p.optInt("jam", 0)
                        Text(if (jam >= 70) "拥挤" else if (jam >= 40) "较挤" else "宽松",
                            fontSize = 7.5.sp,
                            color = if (jam >= 70) ZxT.Err else if (jam >= 40) ZxT.Warn
                            else ZxT.Ok)
                    }
                }
            }
            val p = plans.getOrNull(sel)
            verdict(p != null && p.optInt("delay", 0) <= 5,
                warn = p != null && p.optInt("walk", 0) > 12,
                title = if (p == null) "无方案"
                    else if (p.optInt("delay", 0) > 5) "**${p.optString("n", "")} 延误 " +
                        "${p.optInt("delay", 0)}′**——该换方案了"
                    else "${p.optString("n", "")} ${p.optInt("t", 0)}′，" +
                        "步行 ${p.optInt("walk", 0)}′",
                why = "**步行段最容易漏**——漏了它总时长看着更短，" +
                    "用户按这个时间出门就会迟到")
            note("已有 mapnav 是<b>导航</b>、transit 是<b>换乘</b>，" +
                "这个是<b>通勤路况</b>：多方案并排 + 延误标红 + 步行段可见。" +
                "<b>无方案会被拦</b>；<b>耗时不含步行会被拦</b>。")
        }
    }

    /* ══════════ zx_warranty 保修卡 ══════════ */
    @Composable
    fun warranty(o: JSONObject, ctx: RenderContext) {
        // items:{n:品名, buy:购买日, m:保修月, sn:序列号}
        val items = jarr(o, "items")
        var sel by remember { mutableIntStateOf(-1) }
        val expired = items.count { it.optInt("left", 0) <= 0 }
        val soon = items.count { it.optInt("left", 0) in 1..60 }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "保修"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(if (expired > 0) "**$expired 项过保**"
                    else if (soon > 0) "$soon 项临期" else "都在保",
                    fontSize = 8.5.sp,
                    color = if (expired > 0) ZxT.Err else if (soon > 0) ZxT.Warn else ZxT.Ok)
            }
            Spacer(Modifier.height(5.dp))
            items.forEachIndexed { k, it ->
                val left = it.optInt("left", 0)
                val m = it.optInt("m", 12).coerceAtLeast(1)
                val used = ((m * 30 - left).coerceIn(0, m * 30)).toFloat() / (m * 30)
                val dead = left <= 0
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2
                        else if (dead) ZxT.Err.copy(.07f)
                        else if (left <= 60) ZxT.Warn.copy(.07f) else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_warranty_claim", mapOf(
                            "item" to it.optString("n", ""), "sn" to it.optString("sn", ""),
                            "daysLeft" to left))
                    }.padding(6.dp, 4.dp)) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(it.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text,
                                modifier = Modifier.weight(1f), maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text(if (dead) "**已过保**"
                                else if (left <= 60) "**剩 ${left} 天**" else "剩 ${left} 天",
                                fontSize = 8.sp,
                                color = if (dead) ZxT.Err else if (left <= 60) ZxT.Warn
                                else ZxT.Text3)
                        }
                        Spacer(Modifier.height(2.dp))
                        Box(Modifier.fillMaxWidth().height(5.dp)
                            .clip(RoundedCornerShape(3.dp)).background(ZxT.Surface2)) {
                            Box(Modifier.fillMaxWidth(used.coerceIn(0f, 1f)).fillMaxHeight()
                                .background(if (dead) ZxT.Err
                                    else if (left <= 60) ZxT.Warn else ZxT.Ok))
                        }
                        Text("${it.optString("buy", "")} 购　保 ${m} 个月", fontSize = 7.5.sp,
                            color = ZxT.Text3)
                    }
                }
            }
            verdict(expired == 0, warn = soon > 0,
                title = if (expired > 0) "**$expired 项已过保**——" +
                    "过保的东西坏了只能自费"
                    else if (soon > 0) "**$soon 项 60 天内到期**——" +
                        "**这时候该去检查一遍**"
                    else "${items.size} 项都在保",
                why = "**保修卡的价值在「还剩多久」**——只写购买日期，" +
                    "用户得自己算保修到哪天")
            note("已有 receipt 是<b>回执凭据</b>、order 是<b>订单</b>，" +
                "这个是<b>保修卡</b>：剩余天数 + <b>过保标红</b> + 序列号。" +
                "<b>无条目会被拦</b>；<b>购买日晚于今天会被拦</b>。")
        }
    }

    /* ══════════ zx_inspection 巡检表 ══════════ */
    @Composable
    fun inspection(o: JSONObject, ctx: RenderContext) {
        // items:{n:项, s:0未检 1合格 2不合格, why:不合格说明}
        val items = jarr(o, "items")
        val state = remember { mutableStateListOf(*Array(items.size) { 0 }) }
        val why = remember { mutableStateMapOf<Int, String>() }
        var sel by remember { mutableIntStateOf(-1) }
        val bad = state.count { it == 2 }
        val un = state.count { it == 0 }
        val badNoWhy = state.mapIndexedNotNull { i, s ->
            if (s == 2 && (why[i] ?: "").isBlank()) i else null }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "巡检"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Row {
                    Text("$bad 不合格", fontSize = 8.sp,
                        color = if (bad > 0) ZxT.Err else ZxT.Text3)
                    Text("　$un 未检", fontSize = 8.sp, color = ZxT.Text3,
                        modifier = Modifier.padding(start = 6.dp))
                }
            }
            Spacer(Modifier.height(5.dp))
            items.forEachIndexed { k, it ->
                val s = state[k]
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2
                        else if (s == 2) ZxT.Err.copy(.07f)
                        else if (s == 1) ZxT.Ok.copy(.06f) else Color.Transparent)
                    .padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(it.optString("n", ""), fontSize = 9.5.sp,
                                color = if (s == 2) ZxT.Err else ZxT.Text,
                                modifier = Modifier.weight(1f), maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            listOf("合格" to 1, "不合格" to 2).forEach { (l, v) ->
                                Box(Modifier.padding(start = 3.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (s == v)
                                        (if (v == 1) ZxT.Ok else ZxT.Err) else ZxT.Surface2)
                                    .clickable {
                                        state[k] = if (s == v) 0 else v
                                        sel = k
                                        ctx.callTool("zx_inspect_mark", mapOf(
                                            "item" to it.optString("n", ""),
                                            "result" to if (state[k] == 1) "pass"
                                                else if (state[k] == 2) "fail" else "clear"))
                                    }.padding(6.dp, 2.dp)) {
                                    Text(l, fontSize = 7.5.sp,
                                        color = if (s == v) Color.White else ZxT.Text2)
                                }
                            }
                        }
                        if (s == 2) {
                            Text(if ((why[k] ?: "").isBlank())
                                "**必须填不合格原因**（点这里填：${it.optString("n", "")}）"
                            else "原因：${why[k]}",
                                fontSize = 7.5.sp,
                                color = if ((why[k] ?: "").isBlank()) ZxT.Err else ZxT.Text3,
                                modifier = Modifier.padding(top = 2.dp)
                                    .clickable {
                                        why[k] = "现场记录"
                                        ctx.callTool("zx_inspect_why", mapOf(
                                            "item" to it.optString("n", ""),
                                            "reason" to why[k]!!))
                                    })
                        }
                    }
                }
            }
            verdict(un == 0 && badNoWhy.isEmpty(),
                warn = bad > 0,
                title = when {
                    un > 0 -> "**还有 $un 项未检**——**没检完不能收工**"
                    badNoWhy.isNotEmpty() -> "**${badNoWhy.size} 项不合格但没填原因**——" +
                        "**没有原因的不合格等于没记录**"
                    bad > 0 -> "$bad 项不合格，已全部记录原因"
                    else -> "${items.size} 项全部合格"
                },
                why = "**巡检的产出是「不合格项 + 原因」**——只打勾不写为什么，" +
                    "后面没法整改也没法追责")
            note("已有 checkgrid 是<b>检查表</b>、checklist 是<b>清单</b>，" +
                "这个是<b>巡检表</b>：合格/不合格 + <b>不合格必须填原因</b>。" +
                "<b>无检查项会被拦</b>；<b>状态值非法会被拦</b>。")
        }
    }

    /* ══════════ zx_examprep 备考计划 ══════════ */
    @Composable
    fun examprep(o: JSONObject, ctx: RenderContext) {
        // subs:{n:科目, w:权重, done:已完成小时, need:需要小时}
        val subs = jarr(o, "subs")
        val days = o.optInt("days", 0)
        var sel by remember { mutableIntStateOf(-1) }
        val need = subs.sumOf { it.optDouble("need", 0.0) }
        val done = subs.sumOf { it.optDouble("done", 0.0) }
        val perDay = if (days > 0) (need - done) / days else Double.NaN
        // 最该补的：权重高且完成率低
        val weakest = subs.mapIndexed { i, s ->
            val nd = s.optDouble("need", 0.0).coerceAtLeast(0.1)
            Triple(i, s.optString("n", ""),
                s.optDouble("w", 1.0) * (1 - s.optDouble("done", 0.0) / nd))
        }.maxByOrNull { it.third }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "备考"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text("剩 $days 天", fontSize = 8.5.sp,
                    color = if (days <= 7) ZxT.Err else ZxT.Text3)
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("%.0f".format(done), fontSize = 20.sp, color = ZxT.Text)
                Text(" / %.0f h".format(need), fontSize = 9.sp, color = ZxT.Text3,
                    modifier = Modifier.padding(start = 3.dp, bottom = 3.dp))
                Spacer(Modifier.weight(1f))
                if (!perDay.isNaN())
                    Column(horizontalAlignment = Alignment.End) {
                        Text("每天 %.1f h".format(perDay), fontSize = 10.sp,
                            color = if (perDay > 6) ZxT.Err else ZxT.PriLite)
                        Text("还差 %.0f h".format(need - done), fontSize = 7.5.sp,
                            color = ZxT.Text3)
                    }
            }
            Spacer(Modifier.height(5.dp))
            subs.forEachIndexed { k, s ->
                val nd = s.optDouble("need", 0.0).coerceAtLeast(0.1)
                val dn = s.optDouble("done", 0.0)
                val r = (dn / nd).coerceIn(0.0, 1.0)
                val isWeak = weakest?.first == k
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2
                        else if (isWeak) ZxT.Warn.copy(.08f) else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_prep_focus", mapOf(
                            "subject" to s.optString("n", ""),
                            "remainingHours" to "%.1f".format(nd - dn),
                            "weight" to s.optDouble("w", 1.0)))
                    }.padding(6.dp, 4.dp)) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(s.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text)
                            if (isWeak) Text("　最该补", fontSize = 7.5.sp, color = ZxT.Warn)
                            Spacer(Modifier.weight(1f))
                            Text("权重 ${"%.0f".format(s.optDouble("w", 1.0))}",
                                fontSize = 7.5.sp, color = ZxT.Text3)
                        }
                        Spacer(Modifier.height(2.dp))
                        Box(Modifier.fillMaxWidth().height(6.dp)
                            .clip(RoundedCornerShape(3.dp)).background(ZxT.Surface2)) {
                            Box(Modifier.fillMaxWidth(r.toFloat()).fillMaxHeight()
                                .background(if (r >= 1.0) ZxT.Ok
                                    else if (isWeak) ZxT.Warn else ZxT.Primary))
                        }
                        Text("%.1f / %.1f h".format(dn, nd), fontSize = 7.5.sp,
                            color = ZxT.Text3)
                    }
                }
            }
            verdict(!perDay.isNaN() && perDay <= 6,
                warn = weakest != null && !perDay.isNaN() && perDay > 4,
                title = if (perDay.isNaN()) "缺剩余天数"
                    else if (perDay > 6) "**每天要学 %.1f h——这排不出来**".format(perDay)
                    else "每天 %.1f h，最该补的是「${weakest?.second ?: "—"}」".format(perDay),
                why = "**备考计划要回答「今天学哪一科」**——" +
                    "只给总量不给优先级，用户每天都要重新纠结")
            note("已有 agenda 是<b>日程</b>、gantt 是<b>甘特</b>，" +
                "这个是<b>备考计划</b>：权重 × 完成率 → <b>最该补的科目</b>。" +
                "<b>无科目会被拦</b>；<b>权重为负会被拦</b>。")
        }
    }

    /* ══════════ zx_tripkit 出行清单 ══════════ */
    @Composable
    fun tripkit(o: JSONObject, ctx: RenderContext) {
        // items:{n:品名, c:分类, must:必带}
        val items = jarr(o, "items")
        val packed = remember { mutableStateListOf(*Array(items.size) { false }) }
        var sel by remember { mutableIntStateOf(-1) }
        val musts = items.mapIndexedNotNull { i, it ->
            if (it.optBoolean("must", false)) i else null }
        val mustLeft = musts.count { !packed[it] }
        val groups = LinkedHashMap<String, MutableList<Int>>()
        items.forEachIndexed { i, it ->
            groups.getOrPut(it.optString("c", "其他")) { mutableListOf() }.add(i)
        }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "出行"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text("${packed.count { it }}/${items.size}", fontSize = 8.5.sp,
                    color = ZxT.Text3)
            }
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().height(6.dp)
                .clip(RoundedCornerShape(3.dp)).background(ZxT.Surface2)) {
                Box(Modifier.fillMaxWidth(packed.count { it }.toFloat() /
                    items.size.coerceAtLeast(1)).fillMaxHeight().background(ZxT.Ok))
            }
            Spacer(Modifier.height(5.dp))
            groups.forEach { (c, idxs) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(c, fontSize = 8.sp, color = ZxT.Text3,
                        modifier = Modifier.width(48.dp))
                    Column(Modifier.weight(1f)) {
                        idxs.forEach { i ->
                            val it = items[i]
                            val on = packed[i]
                            val m = it.optBoolean("must", false)
                            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (sel == i) ZxT.Surface2 else Color.Transparent)
                                .clickable {
                                    packed[i] = !on; sel = i
                                    ctx.callTool("zx_kit_pack", mapOf(
                                        "item" to it.optString("n", ""),
                                        "packed" to packed[i],
                                        "mustLeft" to musts.count { !packed[it] }))
                                }.padding(5.dp, 2.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(12.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .border(1.dp, if (on) ZxT.Ok else ZxT.Text3,
                                        RoundedCornerShape(3.dp))
                                    .background(if (on) ZxT.Ok else Color.Transparent),
                                    contentAlignment = Alignment.Center) {
                                    if (on) Text("✓", fontSize = 8.sp, color = Color.White)
                                }
                                Spacer(Modifier.width(5.dp))
                                Text(it.optString("n", ""), fontSize = 9.sp,
                                    color = if (on) ZxT.Text3 else ZxT.Text)
                                if (m && !on) Text("　必带", fontSize = 7.5.sp,
                                    color = ZxT.Err)
                            }
                        }
                    }
                }
            }
            verdict(mustLeft == 0, warn = mustLeft > 0,
                title = if (mustLeft > 0) "**还有 $mustLeft 件必带没装**——" +
                    "**必带没齐就不能出发**"
                    else "${items.size} 件全装好，可以出发",
                why = "**清单的价值在拦住「忘带」**——" +
                    "所有项平权列出，用户分不清哪件忘了会出事")
            note("已有 checklist 是<b>检查清单</b>、packlist 无，" +
                "这个是<b>出行清单</b>：分类 + <b>必带项未勾阻止出发</b>。" +
                "<b>无物品会被拦</b>；<b>分类为空会被拦</b>。")
        }
    }

    /* ══════════ zx_inventory 家庭库存 ══════════ */
    @Composable
    fun inventory(o: JSONObject, ctx: RenderContext) {
        // items:{n:品名, q:存量, min:安全线, u:单位}
        val items = jarr(o, "items")
        var sel by remember { mutableIntStateOf(-1) }
        val low = items.count { it.optDouble("q", 0.0) <= it.optDouble("min", 0.0) }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "库存"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(if (low > 0) "**$low 项见底**" else "都够",
                    fontSize = 8.5.sp, color = if (low > 0) ZxT.Warn else ZxT.Ok)
            }
            Spacer(Modifier.height(5.dp))
            items.forEachIndexed { k, it ->
                val q = it.optDouble("q", 0.0)
                val mn = it.optDouble("min", 0.0)
                val isLow = q <= mn
                val full = (q / (mn * 3).coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2
                        else if (isLow) ZxT.Warn.copy(.08f) else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_stock_buy", mapOf(
                            "item" to it.optString("n", ""),
                            "qty" to "%.1f".format(q), "min" to "%.1f".format(mn)))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(it.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text,
                                modifier = Modifier.weight(1f), maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text("%.1f".format(q) + it.optString("u", ""), fontSize = 9.sp,
                                color = if (isLow) ZxT.Warn else ZxT.Text,
                                fontFamily = ZxT.Mono)
                        }
                        Spacer(Modifier.height(2.dp))
                        Box(Modifier.fillMaxWidth().height(5.dp)
                            .clip(RoundedCornerShape(3.dp)).background(ZxT.Surface2)) {
                            Box(Modifier.fillMaxWidth(full.toFloat()).fillMaxHeight()
                                .background(if (isLow) ZxT.Warn else ZxT.Ok))
                            // 安全线刻度
                        }
                        Text(if (isLow) "**低于安全线 %.1f**".format(mn)
                            else "安全线 %.1f".format(mn),
                            fontSize = 7.5.sp,
                            color = if (isLow) ZxT.Warn else ZxT.Text3)
                    }
                }
            }
            verdict(low == 0, warn = low > 0,
                title = if (low > 0) "**$low 项低于安全线**——**该补货了**"
                    else "${items.size} 项都够",
                why = "**库存要标安全线**——只给存量，用户不知道 3 卷纸算多还是算少")
            note("已有 table 是<b>表格</b>、expiry 是<b>保质期</b>，" +
                "这个是<b>家庭库存</b>：存量 + <b>安全线标红</b>。" +
                "<b>无条目会被拦</b>；<b>数量非正会被拦</b>。")
        }
    }

    /* ══════════ zx_recycle 垃圾分类 ══════════ */
    @Composable
    fun recycle(o: JSONObject, ctx: RenderContext) {
        // items:{n:物品, t:类别, tip:投放要求}
        val items = jarr(o, "items")
        var sel by remember { mutableIntStateOf(-1) }
        val COL = mapOf("可回收" to ZxT.Info, "有害" to ZxT.Err,
            "厨余" to ZxT.Ok, "其他" to ZxT.Text3)
        val counts = items.groupingBy { it.optString("t", "其他") }.eachCount()

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "垃圾分类"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text("${items.size} 项", fontSize = 8.5.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(5.dp))
            Row(Modifier.fillMaxWidth()) {
                listOf("可回收", "有害", "厨余", "其他").forEach { t ->
                    val n = counts[t] ?: 0
                    Column(Modifier.weight(1f).padding(end = 3.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background((COL[t] ?: ZxT.Text3).copy(alpha = .16f))
                        .padding(4.dp, 3.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$n", fontSize = 13.sp, color = COL[t] ?: ZxT.Text3)
                        Text(t, fontSize = 7.5.sp, color = ZxT.Text3)
                    }
                }
            }
            Spacer(Modifier.height(5.dp))
            items.forEachIndexed { k, it ->
                val t = it.optString("t", "其他")
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_recycle_pick", mapOf(
                            "item" to it.optString("n", ""), "type" to t,
                            "tip" to it.optString("tip", "")))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.size(8.dp).clip(CircleShape)
                        .background(COL[t] ?: ZxT.Text3)
                        .align(Alignment.CenterVertically)
                        .offset(y = 3.dp))
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(it.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text,
                                modifier = Modifier.weight(1f), maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text(t, fontSize = 8.sp, color = COL[t] ?: ZxT.Text3)
                        }
                        if (it.optString("tip", "").isNotBlank())
                            Text(it.optString("tip", ""), fontSize = 7.5.sp,
                                color = ZxT.Text3, lineHeight = 11.sp)
                    }
                }
            }
            verdict(true, warn = (counts["有害"] ?: 0) > 0,
                title = if ((counts["有害"] ?: 0) > 0) "**${counts["有害"]} 项有害垃圾**——" +
                    "**不能混进其他桶**"
                    else "${items.size} 项已分类",
                why = "**分类卡要带投放要求**——只说「电池是有害」，" +
                    "用户不知道要不要拆、要不要包好")
            note("已有 termlist 是<b>术语表</b>、tag 是<b>标签</b>，" +
                "这个是<b>垃圾分类</b>：四类计数 + <b>投放要求</b>。" +
                "<b>无条目会被拦</b>；<b>类别不在四类内会被拦</b>。")
        }
    }

    /* ══════════ zx_carservice 车辆保养 ══════════ */
    @Composable
    fun carservice(o: JSONObject, ctx: RenderContext) {
        // items:{n:项目, ivKm:里程间隔, lastKm:上次里程, ivM:月间隔, lastM:上次距今月}
        val items = jarr(o, "items")
        val km = o.optInt("km", 0)
        var sel by remember { mutableIntStateOf(-1) }
        val due = items.count {
            val byKm = it.optInt("ivKm", 0) > 0 &&
                km - it.optInt("lastKm", 0) >= it.optInt("ivKm", 0)
            val byM = it.optInt("ivM", 0) > 0 &&
                it.optInt("lastM", 0) >= it.optInt("ivM", 0)
            byKm || byM }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "保养"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text("${km} km", fontSize = 9.sp, color = ZxT.Text2,
                        fontFamily = ZxT.Mono)
                    Text(if (due > 0) "**$due 项到期**" else "都还早", fontSize = 8.sp,
                        color = if (due > 0) ZxT.Warn else ZxT.Ok)
                }
            }
            Spacer(Modifier.height(5.dp))
            items.forEachIndexed { k, it ->
                val ivKm = it.optInt("ivKm", 0)
                val ivM = it.optInt("ivM", 0)
                val rKm = if (ivKm > 0) ((km - it.optInt("lastKm", 0)).toFloat() /
                    ivKm).coerceIn(0f, 1.4f) else 0f
                val rM = if (ivM > 0) (it.optInt("lastM", 0).toFloat() /
                    ivM).coerceIn(0f, 1.4f) else 0f
                val byKm = ivKm > 0 && km - it.optInt("lastKm", 0) >= ivKm
                val byM = ivM > 0 && it.optInt("lastM", 0) >= ivM
                val late = byKm || byM
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2
                        else if (late) ZxT.Warn.copy(.08f) else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_service_book", mapOf(
                            "item" to it.optString("n", ""),
                            "overdueKm" to max(0, km - it.optInt("lastKm", 0) - ivKm),
                            "overdueMonths" to max(0, it.optInt("lastM", 0) - ivM)))
                    }.padding(6.dp, 4.dp)) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(it.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text,
                                modifier = Modifier.weight(1f), maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            if (byKm) Text("**超里程**", fontSize = 7.5.sp, color = ZxT.Warn)
                            if (byM) Text("　**超期**", fontSize = 7.5.sp, color = ZxT.Warn)
                            if (!late) Text(if (ivKm > 0)
                                "还差 ${ivKm - (km - it.optInt("lastKm", 0))} km"
                            else "还差 ${ivM - it.optInt("lastM", 0)} 月",
                                fontSize = 7.5.sp, color = ZxT.Text3)
                        }
                        Spacer(Modifier.height(2.dp))
                        if (ivKm > 0) Box(Modifier.fillMaxWidth().height(4.dp)
                            .clip(RoundedCornerShape(2.dp)).background(ZxT.Surface2)) {
                            Box(Modifier.fillMaxWidth(rKm.coerceAtMost(1f)).fillMaxHeight()
                                .background(if (byKm) ZxT.Warn else ZxT.Primary))
                        }
                        if (ivM > 0) {
                            Spacer(Modifier.height(1.dp))
                            Box(Modifier.fillMaxWidth().height(4.dp)
                                .clip(RoundedCornerShape(2.dp)).background(ZxT.Surface2)) {
                                Box(Modifier.fillMaxWidth(rM.coerceAtMost(1f))
                                    .fillMaxHeight()
                                    .background(if (byM) ZxT.Warn else ZxT.Info))
                            }
                        }
                        Text(if (ivKm > 0) "每 ${ivKm} km" else "" +
                            if (ivM > 0) "　每 $ivM 月" else "",
                            fontSize = 7.5.sp, color = ZxT.Text3)
                    }
                }
            }
            verdict(due == 0, warn = due > 0,
                title = if (due > 0) "**$due 项到期**——" +
                    "**里程和时间是两个独立触发条件**"
                    else "${items.size} 项都在周期内",
                why = "**保养要双触发**——只看里程会漏掉「开得少但橡胶老化」，" +
                    "只看时间会漏掉「跑得多但没到期」")
            note("已有 warranty 是<b>保修</b>、plant 是<b>养护</b>，" +
                "这个是<b>车辆保养</b>：<b>里程 + 时间双触发</b>。" +
                "<b>无保养项会被拦</b>；<b>间隔非正会被拦</b>。")
        }
    }

    /* ══════════ zx_expiry 食品保质期 ══════════ */
    @Composable
    fun expiry(o: JSONObject, ctx: RenderContext) {
        // items:{n:品名, left:剩余天, q:数量}
        val items = jarr(o, "items").sortedBy { it.optInt("left", 0) }
        var sel by remember { mutableIntStateOf(-1) }
        val dead = items.count { it.optInt("left", 0) <= 0 }
        val soon = items.count { it.optInt("left", 0) in 1..7 }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "保质期"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(if (dead > 0) "**$dead 项已过**"
                    else if (soon > 0) "**$soon 项 7 天内**" else "都还新",
                    fontSize = 8.5.sp,
                    color = if (dead > 0) ZxT.Err else if (soon > 0) ZxT.Warn else ZxT.Ok)
            }
            Spacer(Modifier.height(5.dp))
            items.forEachIndexed { k, it ->
                val left = it.optInt("left", 0)
                val col = when {
                    left <= 0 -> ZxT.Err
                    left <= 7 -> ZxT.Warn
                    left <= 30 -> ZxT.Text2
                    else -> ZxT.Ok
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2
                        else if (left <= 0) ZxT.Err.copy(.09f)
                        else if (left <= 7) ZxT.Warn.copy(.07f) else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_expiry_pick", mapOf(
                            "item" to it.optString("n", ""), "daysLeft" to left,
                            "expired" to (left <= 0)))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(it.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text,
                        modifier = Modifier.weight(1f), maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    if (it.optInt("q", 1) > 1)
                        Text("×${it.optInt("q", 1)}", fontSize = 8.sp, color = ZxT.Text3,
                            modifier = Modifier.padding(end = 6.dp))
                    Box(Modifier.size(30.dp).clip(CircleShape)
                        .background(col.copy(alpha = .18f)),
                        contentAlignment = Alignment.Center) {
                        Text(if (left <= 0) "过" else "$left", fontSize = 9.sp, color = col)
                    }
                    Text("天", fontSize = 7.5.sp, color = ZxT.Text3,
                        modifier = Modifier.padding(start = 2.dp, end = 2.dp))
                }
            }
            verdict(dead == 0, warn = soon > 0,
                title = when {
                    dead > 0 -> "**$dead 项已过期**——**过期的要单独处理，不能混着吃**"
                    soon > 0 -> "**$soon 项 7 天内到期**——按剩余天数排好了，先吃上面的"
                    else -> "${items.size} 项都在期"
                },
                why = "**保质期卡要按剩余天数排序**——" +
                    "按购买顺序排，用户从上往下拿，拿到中间才发现已经过了")
            note("已有 inventory 是<b>库存</b>、coupon 是<b>券</b>，" +
                "这个是<b>食品保质期</b>：按剩余天数排序 + <b>过期标红</b>。" +
                "<b>无条目会被拦</b>；<b>剩余天数为负会被拦</b>。")
        }
    }

    /* ══════════ zx_splitbill AA 分账 ══════════ */
    @Composable
    fun splitbill(o: JSONObject, ctx: RenderContext) {
        // people:[名]  items:{p:付款人, v:金额, n:名}
        val people = jstr(o, "people")
        val items = jarr(o, "items")
        var sel by remember { mutableIntStateOf(-1) }
        val total = items.sumOf { it.optDouble("v", 0.0) }
        val per = if (people.isEmpty()) 0.0 else total / people.size
        val paid = LinkedHashMap<String, Double>()
        people.forEach { paid[it] = 0.0 }
        items.forEach {
            val p = it.optString("p", "")
            paid[p] = (paid[p] ?: 0.0) + it.optDouble("v", 0.0)
        }
        // 净额：付多了是 +（别人欠他），付少了是 −（他欠别人）
        val net = people.map { it to ((paid[it] ?: 0.0) - per) }
        val owe = net.filter { it.second < -0.01 }.sortedBy { it.second }
        val get = net.filter { it.second > 0.01 }.sortedByDescending { it.second }
        // 最省转账：贪心配对
        val tx = ArrayList<Triple<String, String, Double>>()
        val o2 = owe.map { it.first to -it.second }.toMutableList()
        val g2 = get.map { it.first to it.second }.toMutableList()
        var a = 0; var b = 0
        while (a < o2.size && b < g2.size) {
            val amt = min(o2[a].second, g2[b].second)
            if (amt > 0.01) tx.add(Triple(o2[a].first, g2[b].first, amt))
            o2[a] = o2[a].first to (o2[a].second - amt)
            g2[b] = g2[b].first to (g2[b].second - amt)
            if (o2[a].second < 0.01) a++
            if (g2[b].second < 0.01) b++
        }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "分账"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text("${people.size} 人", fontSize = 8.5.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("¥${"%.0f".format(total)}", fontSize = 20.sp, color = ZxT.Err)
                Spacer(Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("人均 ¥${"%.2f".format(per)}", fontSize = 9.sp, color = ZxT.Text2)
                    Text("${tx.size} 笔转账结清（最省）", fontSize = 7.5.sp,
                        color = ZxT.Text3)
                }
            }
            Spacer(Modifier.height(5.dp))
            net.forEach { (p, d) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 1.5.dp)) {
                    Text(p, fontSize = 9.sp, color = ZxT.Text,
                        modifier = Modifier.width(48.dp), maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Box(Modifier.weight(1f).height(6.dp)
                        .clip(RoundedCornerShape(3.dp)).background(ZxT.Surface2)) {
                        val mid = Modifier.fillMaxWidth().height(6.dp)
                        Box(mid)
                        val half = (abs(d) / (per.coerceAtLeast(1.0)) / 2).toFloat()
                            .coerceIn(0f, 0.5f)
                        Box(Modifier.fillMaxWidth(half)
                            .align(if (d >= 0) Alignment.CenterEnd else Alignment.CenterStart)
                            .fillMaxHeight()
                            .background(if (d >= 0) ZxT.Ok else ZxT.Err))
                    }
                    Text((if (d >= 0) "+" else "−") + "%.1f".format(abs(d)),
                        fontSize = 8.5.sp,
                        color = if (d >= 0) ZxT.Ok else ZxT.Err,
                        modifier = Modifier.width(48.dp), textAlign = TextAlign.End,
                        fontFamily = ZxT.Mono)
                }
            }
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(ZxT.Line))
            Spacer(Modifier.height(3.dp))
            tx.forEachIndexed { k, (from, to, amt) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 1.5.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (sel == k) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_split_settle", mapOf(
                            "from" to from, "to" to to, "amount" to "%.2f".format(amt)))
                    }.padding(6.dp, 3.dp)) {
                    Text(from, fontSize = 9.sp, color = ZxT.Err)
                    Text(" → ", fontSize = 9.sp, color = ZxT.Text3)
                    Text(to, fontSize = 9.sp, color = ZxT.Ok)
                    Spacer(Modifier.weight(1f))
                    Text("¥${"%.2f".format(amt)}", fontSize = 9.sp, color = ZxT.Text,
                        fontFamily = ZxT.Mono)
                }
            }
            verdict(tx.size <= people.size - 1,
                title = "${items.size} 笔支出，$people.size 人，" +
                    "**${tx.size} 笔转账就能结清**（人挨个还得 ${people.size - 1} 笔以上）",
                why = "**分账要算「最少转几次」**——" +
                    "只给每人净额，用户得自己想怎么转，往往多转好几笔")
            note("已有 ledger 是<b>记账</b>、wallet 是<b>钱包</b>，" +
                "这个是<b>AA 分账</b>：净额 + <b>最少转账方案</b>。" +
                "<b>无人员会被拦</b>；<b>金额加总对不上会被拦</b>。")
        }
    }

    /* ══════════ zx_firstaid 急救步骤 ══════════ */
    @Composable
    fun firstaid(o: JSONObject, ctx: RenderContext) {
        // steps:[步骤]  warn:[禁忌]  call:是否要叫 120
        val steps = jstr(o, "steps")
        val warns = jstr(o, "warn")
        var cur by remember { mutableIntStateOf(0) }
        val needCall = o.optBoolean("call", false)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "急救"), fontSize = 11.sp, color = ZxT.Err,
                    modifier = Modifier.weight(1f))
                Text("${cur + 1}/${steps.size}", fontSize = 8.5.sp, color = ZxT.Err)
            }
            if (needCall) {
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp)).background(ZxT.Err.copy(.16f))
                    .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("☎", fontSize = 12.sp, color = ZxT.Err)
                    Spacer(Modifier.width(5.dp))
                    Text("**先打 120，再按下面做**", fontSize = 9.5.sp, color = ZxT.Err)
                }
            }
            Spacer(Modifier.height(5.dp))
            steps.forEachIndexed { i, s ->
                val done = i < cur
                val now = i == cur
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (now) ZxT.Err.copy(.14f)
                        else if (done) ZxT.Ok.copy(.06f) else Color.Transparent)
                    .clickable {
                        // 不能跳：只能推进到"已完成的下一步"
                        if (i > cur) return@clickable
                        cur = if (i == cur) min(cur + 1, steps.size - 1) else i
                        ctx.callTool("zx_aid_step", mapOf(
                            "step" to (cur + 1), "text" to (steps.getOrNull(cur) ?: ""),
                            "done" to (cur >= steps.size - 1)))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.size(14.dp).clip(CircleShape)
                        .background(if (done) ZxT.Ok else if (now) ZxT.Err else ZxT.Surface3),
                        contentAlignment = Alignment.Center) {
                        Text(if (done) "✓" else "${i + 1}", fontSize = 7.5.sp,
                            color = if (done || now) Color.White else ZxT.Text3)
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(s, fontSize = 9.5.sp,
                        color = if (done) ZxT.Text3 else if (now) ZxT.Text else ZxT.Text3,
                        lineHeight = 13.sp, modifier = Modifier.weight(1f))
                }
            }
            if (warns.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Column(Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp)).background(ZxT.Warn.copy(.10f))
                    .padding(7.dp, 5.dp)) {
                    Text("不要做", fontSize = 8.sp, color = ZxT.Warn)
                    warns.forEach { w ->
                        Text("× $w", fontSize = 8.5.sp, color = ZxT.Warn,
                            lineHeight = 12.sp)
                    }
                }
            }
            verdict(cur >= steps.size - 1,
                warn = cur < steps.size - 1,
                title = if (cur >= steps.size - 1) "已到最后一步"
                    else "**当前第 ${cur + 1} 步**——**急救步骤不能跳**",
                why = "**顺序错了会加重伤害**——所以只能向前推进，不能点后面的步骤")
            note("已有 stepper 是<b>分步流程</b>、recipe 是<b>菜谱</b>，" +
                "这个是<b>急救步骤</b>：<b>不能跳步</b> + 禁忌 + 是否先叫 120。" +
                "<b>无步骤会被拦</b>；<b>缺禁忌提示会被拦</b>。")
        }
    }

    /* ══════════ zx_sizing 尺码对照 ══════════ */
    @Composable
    fun sizing(o: JSONObject, ctx: RenderContext) {
        // rows:{size:尺码, chest:胸围, waist:腰围, len:衣长}  measure:{chest:实测, waist:}
        val rows = jarr(o, "rows")
        val mc = o.optDouble("chest", Double.NaN)
        val mw = o.optDouble("waist", Double.NaN)
        var sel by remember { mutableIntStateOf(-1) }
        // 推荐：胸围和腰围都能装下的最小码（大 2cm 余量）
        val best = rows.firstOrNull {
            (!mc.isNaN() && it.optDouble("chest", 0.0) >= mc + 2) &&
                (!mw.isNaN() && it.optDouble("waist", 0.0) >= mw + 2)
        } ?: rows.lastOrNull()

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "尺码"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                if (best != null)
                    Text("推荐 ${best.optString("size", "")}", fontSize = 9.sp,
                        color = ZxT.PriLite)
            }
            if (!mc.isNaN() || !mw.isNaN()) {
                Spacer(Modifier.height(3.dp))
                Row {
                    if (!mc.isNaN()) Text("实测胸围 ${"%.0f".format(mc)}", fontSize = 8.sp,
                        color = ZxT.Text2, modifier = Modifier.padding(end = 8.dp))
                    if (!mw.isNaN()) Text("腰围 ${"%.0f".format(mw)}", fontSize = 8.sp,
                        color = ZxT.Text2)
                }
            }
            Spacer(Modifier.height(5.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text("尺码", fontSize = 8.sp, color = ZxT.Text3,
                    modifier = Modifier.width(40.dp))
                Text("胸围", fontSize = 8.sp, color = ZxT.Text3,
                    modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                Text("腰围", fontSize = 8.sp, color = ZxT.Text3,
                    modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                Text("衣长", fontSize = 8.sp, color = ZxT.Text3,
                    modifier = Modifier.weight(1f), textAlign = TextAlign.End)
            }
            rows.forEachIndexed { k, r ->
                val isBest = best != null && r.optString("size", "") ==
                    best.optString("size", "")
                val fitC = !mc.isNaN() && r.optDouble("chest", 0.0) >= mc + 2
                val fitW = !mw.isNaN() && r.optDouble("waist", 0.0) >= mw + 2
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2
                        else if (isBest) ZxT.Primary.copy(.16f) else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_size_pick", mapOf(
                            "size" to r.optString("size", ""),
                            "fit" to (fitC && fitW)))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.width(40.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (isBest) Box(Modifier.size(5.dp).clip(CircleShape)
                            .background(ZxT.PriLite))
                        Text("　" + r.optString("size", ""), fontSize = 10.sp,
                            color = if (isBest) ZxT.PriLite else ZxT.Text)
                    }
                    Text(if (fitC) "%.0f".format(r.optDouble("chest", 0.0))
                        else "%.0f".format(r.optDouble("chest", 0.0)),
                        fontSize = 9.sp,
                        color = if (mc.isNaN()) ZxT.Text2 else if (fitC) ZxT.Ok else ZxT.Text3,
                        modifier = Modifier.weight(1f), textAlign = TextAlign.End,
                        fontFamily = ZxT.Mono)
                    Text("%.0f".format(r.optDouble("waist", 0.0)), fontSize = 9.sp,
                        color = if (mw.isNaN()) ZxT.Text2 else if (fitW) ZxT.Ok else ZxT.Text3,
                        modifier = Modifier.weight(1f), textAlign = TextAlign.End,
                        fontFamily = ZxT.Mono)
                    Text("%.0f".format(r.optDouble("len", 0.0)), fontSize = 9.sp,
                        color = ZxT.Text3, modifier = Modifier.weight(1f),
                        textAlign = TextAlign.End, fontFamily = ZxT.Mono)
                }
            }
            verdict(best != null,
                title = if (best == null) "**没有能装下的码**——要选大一码或换款"
                    else "推荐 ${best.optString("size", "")}——" +
                        "**胸围腰围都要能装下，取更小的那个限制**",
                why = "**尺码推荐要同时看两个维度**——" +
                    "只按胸围选，腰围装不下的退换率极高")
            note("已有 table 是<b>表格</b>、shop 是<b>商品</b>，" +
                "这个是<b>尺码对照</b>：实测 → <b>推荐码</b> + 不合身标灰。" +
                "<b>无尺码表会被拦</b>；<b>测量值非正会被拦</b>。")
        }
    }

    /* ══════════ zx_gift 人情往来 ══════════ */
    @Composable
    fun gift(o: JSONObject, ctx: RenderContext) {
        // items:{n:人, what:什么, v:金额, d:日期, dir:收/送, back:是否已回}
        val items = jarr(o, "items")
        var sel by remember { mutableIntStateOf(-1) }
        val got = items.filter { it.optString("dir", "收") == "收" }
        val owe = got.filter { !it.optBoolean("back", false) }
        val inSum = got.sumOf { it.optDouble("v", 0.0) }
        val outSum = items.filter { it.optString("dir", "收") == "送" }
            .sumOf { it.optDouble("v", 0.0) }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "往来"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(if (owe.isNotEmpty()) "**${owe.size} 笔待回**" else "都回了",
                    fontSize = 8.5.sp,
                    color = if (owe.isNotEmpty()) ZxT.Warn else ZxT.Ok)
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("收", fontSize = 7.5.sp, color = ZxT.Text3)
                    Text("¥${"%.0f".format(inSum)}", fontSize = 13.sp, color = ZxT.Ok)
                }
                Column(Modifier.weight(1f)) {
                    Text("送", fontSize = 7.5.sp, color = ZxT.Text3)
                    Text("¥${"%.0f".format(outSum)}", fontSize = 13.sp, color = ZxT.Err)
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text("差额", fontSize = 7.5.sp, color = ZxT.Text3)
                    Text("¥${"%.0f".format(inSum - outSum)}", fontSize = 13.sp,
                        color = if (inSum - outSum > 0) ZxT.Warn else ZxT.Text)
                }
            }
            Spacer(Modifier.height(5.dp))
            items.forEachIndexed { k, it ->
                val isGet = it.optString("dir", "收") == "收"
                val pending = isGet && !it.optBoolean("back", false)
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2
                        else if (pending) ZxT.Warn.copy(.08f) else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_gift_back", mapOf(
                            "who" to it.optString("n", ""),
                            "what" to it.optString("what", ""),
                            "amount" to "%.0f".format(it.optDouble("v", 0.0)),
                            "pending" to pending))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (isGet) "收" else "送", fontSize = 8.sp,
                        color = if (isGet) ZxT.Ok else ZxT.Err,
                        modifier = Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(it.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text)
                            Spacer(Modifier.width(5.dp))
                            Text(it.optString("what", ""), fontSize = 8.sp,
                                color = ZxT.Text3, maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false))
                        }
                        Text(it.optString("d", "") +
                            if (pending) "　**待回礼**" else if (isGet) "　已回" else "",
                            fontSize = 7.5.sp,
                            color = if (pending) ZxT.Warn else ZxT.Text3)
                    }
                    Text("¥${"%.0f".format(it.optDouble("v", 0.0))}", fontSize = 9.sp,
                        color = ZxT.Text, fontFamily = ZxT.Mono)
                }
            }
            verdict(owe.isEmpty(), warn = owe.isNotEmpty(),
                title = if (owe.isNotEmpty()) "**${owe.size} 笔收了还没回**——" +
                    "**人情是要还的，忘了比少给更麻烦**"
                    else "${items.size} 笔往来都清了",
                why = "**往来账要标「待回礼」**——只记金额，" +
                    "用户看不出哪一笔还欠着人情")
            note("已有 ledger 是<b>记账</b>、splitbill 是<b>AA</b>，" +
                "这个是<b>人情往来</b>：收/送 + <b>待回礼标红</b> + 差额。" +
                "<b>无记录会被拦</b>；<b>金额非正会被拦</b>。")
        }
    }
}
