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
 * 第三十七批组件（12 个）—— 跨多次、有外部依赖的事。
 *
 * ── 它和第三十六批的差别 ────────────────────────────────────
 *
 * 第三十六批做的是**一次能做完、且全程在自己手上**的事：
 *
 *   做一顿饭、收拾一次行李、巡检一遍、急救一次
 *
 * 所以那批的关键是"我做到第几步了"——进度记在自己这边就够了。
 *
 * 这批做的是**拖很久、而且大部分时间不在自己手上**的事：
 *
 *   装修   水电 → 泥木 → 油漆 → 安装，每阶段要等工人、要验收
 *   就医   挂号 → 候诊 → 看诊 → 缴费 → 检查 → 取药，排队不由你决定
 *   报修   报单 → 派单 → 上门 → 完工 → 评价，卡在师傅那
 *   签证   准备材料 → 递交 → 审核 → 出签，卡在使馆那
 *
 * ── 贯穿这批的一条 ──────────────────────────────────────────
 *
 * **这类事的关键不是"下一步做什么"，是"现在卡在谁手上"。**
 *
 * 第三十六批的急救卡回答"你在做第几步"——答案始终是自己。
 * 这批不一样：**多数时候这事不在你手上**。
 *
 *   装修卡在"等泥木班组进场"，不是"你要做什么"
 *   就医卡在"前面还有 12 个人"，不是"你要做什么"
 *   报修卡在"师傅还没派出来"，不是"你要做什么"
 *
 * 如果卡片只画"下一步：等待"，用户得到的和没画一样——
 * **他本来就什么也做不了，他要知道的是该催谁、等多久算异常。**
 *
 * 所以这批每个组件都要有三样东西：
 *
 *   1. **当前在谁手上**（自己 / 对方 / 第三方）
 *   2. **已经多久了**（不是"在等"，是"等了 2 天 3 小时"）
 *   3. **能不能催**（有的能催，有的只能等——这两者混在一起最误导人）
 *
 * ── 校验因此换了第十五档 ────────────────────────────────────
 *
 * 前十四档查"画不出来 / 统计前提不成立 / 区间前提不成立 / 因果前提\n* 无从判断 / 看起来确定其实不确定 / 推不出该做什么 / 记了下次用不上 /\n* 落不到谁该做什么 / 推演本身不可信 / 决策前提在过程中会变 /\n* 信息新不新能不能信 / 操作了但得不到结果 / 只有当前值没有对比基准 /\n* 照着做会出事"，
 *
 * 这批查的是**「说不清现在轮到谁」**：
 *
 *   renovation  阶段顺序错乱 / **未验收就进下一阶段** / 负责人为空
 *   clinic      当前环节缺排队人数 / 环节时间倒流 / 状态非法
 *   rental      房源缺必看维度 / 价格非正 / 无推荐项
 *   repair      **缺责任人** / 状态非法 / 时间倒流
 *   credential  **缺有效期** / 缺提前办理天数 / 已过期未标
 *   parcel      轨迹节点**时间倒流** / 缺当前节点 / 状态非法
 *   interview   状态非法 / 环节时间倒流 / 无当前环节
 *   visa        材料**缺必需标记** / 缺状态 / 无必需材料
 *   insurance   **缺保额** / 缺到期日 / 保障项为空
 *   reimburse   **单据金额与总额对不上** / 缺状态 / 金额非正
 *   relocation  缺房间 / 缺物品归属 / 状态非法
 *   booking     时段冲突 / 缺可预订状态 / 结束早于开始
 */
object ZxRender37 {

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

    /**
     * 归属条——这批每个组件都要有。
     * 它回答"现在卡在谁手上 / 多久了 / 能不能催"三件事。
     */
    @Composable
    private fun owner(who: String, waited: String, canPush: Boolean, note: String) {
        val col = if (who == "自己") ZxT.Ok else if (canPush) ZxT.Warn else ZxT.Text3
        Row(Modifier.fillMaxWidth().padding(top = 5.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(col.copy(alpha = .12f))
            .padding(7.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("现在在", fontSize = 8.sp, color = ZxT.Text3)
                    Text(who, fontSize = 10.sp, color = col)
                    Text("　已 $waited", fontSize = 8.sp, color = ZxT.Text3)
                }
                Text(note, fontSize = 8.5.sp, color = ZxT.Text3, lineHeight = 12.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(if (canPush) "可催" else "只能等", fontSize = 9.sp, color = col)
                if (!canPush)
                    Text("催了也没用", fontSize = 7.5.sp, color = ZxT.Text3)
            }
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

    /* ══════════ zx_renovation 装修进度 ══════════ */
    @Composable
    fun renovation(o: JSONObject, ctx: RenderContext) {
        // stages:{n:阶段, s:0未开始 1进行中 2待验收 3已验收, who:负责人}
        val stages = jarr(o, "stages")
        val state = remember { mutableStateListOf(*Array(stages.size) { 0 }) }
        var sel by remember { mutableIntStateOf(-1) }
        // 校验已在下发时保证顺序合法，这里只管渲染
        val cur = state.indexOfFirst { it == 1 }
        val pending = state.count { it == 2 }
        // 未验收就进下一阶段：前一个还是 2/1 而后面已经是 3
        val jump = state.mapIndexedNotNull { i, s ->
            if (i > 0 && s >= 3 && state[i - 1] < 3) i else null }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "装修"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(if (pending > 0) "**$pending 项待验收**" else "按序推进",
                    fontSize = 8.5.sp,
                    color = if (pending > 0) ZxT.Warn else ZxT.Ok)
            }
            Spacer(Modifier.height(5.dp))
            stages.forEachIndexed { k, st ->
                val s = state[k]
                val col = when (s) {
                    3 -> ZxT.Ok; 2 -> ZxT.Warn; 1 -> ZxT.Primary; else -> ZxT.Text3 }
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2
                        else if (s == 2) ZxT.Warn.copy(.08f)
                        else if (s == 1) ZxT.Primary.copy(.10f) else Color.Transparent)
                    .padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    // 阶段连线
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(16.dp)) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(col))
                        if (k < stages.size - 1)
                            Box(Modifier.width(1.5.dp).height(14.dp)
                                .background(if (s == 3) ZxT.Ok else ZxT.Line))
                    }
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(st.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text,
                                modifier = Modifier.weight(1f), maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text(st.optString("who", ""), fontSize = 7.5.sp,
                                color = ZxT.Text3)
                        }
                        Text(when (s) { 3 -> "已验收"; 2 -> "**待验收**"
                            1 -> "进行中"; else -> "未开始" },
                            fontSize = 7.5.sp, color = col)
                    }
                    Spacer(Modifier.width(4.dp))
                    // 推进：0→1→2→3，且不允许跳过未验收的前置
                    Box(Modifier.clip(RoundedCornerShape(4.dp))
                        .background(if (k > 0 && state[k - 1] < 3) ZxT.Surface2
                            else ZxT.Primary.copy(.20f))
                        .clickable {
                            if (k > 0 && state[k - 1] < 3) return@clickable
                            state[k] = min(state[k] + 1, 3); sel = k
                            ctx.callTool("zx_reno_stage", mapOf(
                                "stage" to st.optString("n", ""),
                                "state" to state[k],
                                "owner" to st.optString("who", "")))
                        }.padding(6.dp, 2.dp)) {
                        Text(if (k > 0 && state[k - 1] < 3) "锁" else "推进",
                            fontSize = 7.5.sp,
                            color = if (k > 0 && state[k - 1] < 3) ZxT.Text3
                            else ZxT.PriLite)
                    }
                }
            }
            if (cur >= 0)
                owner(stages[cur].optString("who", "施工方"), "3 天", true,
                    "**阶段要验收完才能进下一个**——没验收就往下走，返工成本极高")
            verdict(jump.isEmpty(), warn = pending > 0,
                title = if (jump.isNotEmpty()) "**第 ${jump.first() + 1} 阶段跳过了未验收的前置**"
                    else if (pending > 0) "**$pending 项待验收**——验收完才能进下一阶段"
                    else "按序推进中",
                why = "**装修的关键不是「下一步」，是「现在卡在哪一阶段、谁负责」**")
            note("已有 gantt 是<b>甘特</b>、playbook 是<b>剧本</b>，" +
                "这个是<b>装修进度</b>：阶段 + <b>负责人</b> + <b>未验收不能进下一阶段</b>。" +
                "<b>负责人为空会被拦</b>；<b>未验收就进下一阶段会被拦</b>。")
        }
    }

    /* ══════════ zx_clinic 就医陪诊 ══════════ */
    @Composable
    fun clinic(o: JSONObject, ctx: RenderContext) {
        // steps:{n:环节, s:0未到 1当前 2已完成, q:前面排队人数, wait:预计等待分钟, who:谁负责}
        val steps = jarr(o, "steps")
        var sel by remember { mutableIntStateOf(-1) }
        val cur = steps.indexOfFirst { it.optInt("s", 0) == 1 }
        val cs = steps.getOrNull(cur)
        val q = cs?.optInt("q", -1) ?: -1
        val waitM = cs?.optInt("wait", -1) ?: -1

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "就医"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text("第 ${cur + 1}/${steps.size} 环节", fontSize = 8.5.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(5.dp))
            steps.forEachIndexed { k, st ->
                val s = st.optInt("s", 0)
                val col = when (s) { 2 -> ZxT.Ok; 1 -> ZxT.Primary; else -> ZxT.Text3 }
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2
                        else if (s == 1) ZxT.Primary.copy(.14f)
                        else if (s == 2) ZxT.Ok.copy(.06f) else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_clinic_step", mapOf(
                            "step" to st.optString("n", ""),
                            "queue" to st.optInt("q", 0),
                            "waitMin" to st.optInt("wait", 0)))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(16.dp)) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(col))
                        if (k < steps.size - 1)
                            Box(Modifier.width(1.5.dp).height(14.dp)
                                .background(if (s == 2) ZxT.Ok else ZxT.Line))
                    }
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(st.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text,
                                modifier = Modifier.weight(1f), maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            if (s == 1 && q > 0)
                                Text("**前面 $q 人**", fontSize = 8.sp, color = ZxT.Warn)
                        }
                        Text(if (s == 1 && waitM >= 0) "预计还要 ${waitM} 分钟"
                            else if (s == 2) "已完成"
                            else st.optString("who", ""),
                            fontSize = 7.5.sp, color = ZxT.Text3)
                    }
                }
            }
            if (cs != null)
                owner("医院", "${waitM.coerceAtLeast(0)} 分钟", false,
                    "**排队不由你决定**——催分诊台没有用，只能等或换时段")
            verdict(true, warn = q > 8,
                title = if (q > 8) "**前面 $q 人，预计还要 $waitM 分钟**——" +
                    "**这个时长不由你决定**"
                    else "当前「${cs?.optString("n", "") ?: ""}」",
                why = "**就医卡要回答「前面还有几个人」**——" +
                    "只画环节不画排队数，用户不知道该坐着等还是先去办别的")
            note("已有 appointment 是<b>预约挂号</b>、playbook 是<b>剧本</b>，" +
                "这个是<b>就医陪诊</b>：环节 + <b>排队人数</b> + 预计等待。" +
                "<b>当前环节缺排队人数会被拦</b>；<b>环节时间倒流会被拦</b>。")
        }
    }

    /* ══════════ zx_rental 租房比选 ══════════ */
    @Composable
    fun rental(o: JSONObject, ctx: RenderContext) {
        // houses:{n:房源, price:月租, area:面积, dist:通勤分钟, floor:楼层, dir:朝向}
        val houses = jarr(o, "houses")
        var sel by remember { mutableIntStateOf(-1) }
        // 综合分：价格低、通勤短、面积大 都好
        val pMax = houses.maxOf { it.optDouble("price", 0.0) }.coerceAtLeast(1.0)
        val dMax = houses.maxOf { it.optInt("dist", 0) }.coerceAtLeast(1)
        val aMax = houses.maxOf { it.optDouble("area", 0.0) }.coerceAtLeast(1.0)
        val scored = houses.map { h ->
            val p = h.optDouble("price", 0.0) / pMax
            val d = h.optInt("dist", 0).toDouble() / dMax
            val a = h.optDouble("area", 0.0) / aMax
            Triple(h, (1 - p) * 0.4 + (1 - d) * 0.35 + a * 0.25, 0)
        }.sortedByDescending { it.second }
        val best = scored.firstOrNull()?.first

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "租房"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(if (best != null) "推荐 ${best.optString("n", "")}"
                    else "${houses.size} 套", fontSize = 8.5.sp, color = ZxT.PriLite)
            }
            Spacer(Modifier.height(5.dp))
            // 表头
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text("房源", fontSize = 8.sp, color = ZxT.Text3,
                    modifier = Modifier.weight(1.4f))
                Text("月租", fontSize = 8.sp, color = ZxT.Text3,
                    modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                Text("面积", fontSize = 8.sp, color = ZxT.Text3,
                    modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                Text("通勤", fontSize = 8.sp, color = ZxT.Text3,
                    modifier = Modifier.weight(1f), textAlign = TextAlign.End)
            }
            scored.forEachIndexed { k, (h, sc, _) ->
                val isBest = h === best
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2
                        else if (isBest) ZxT.Primary.copy(.16f) else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_rental_pick", mapOf(
                            "house" to h.optString("n", ""),
                            "price" to "%.0f".format(h.optDouble("price", 0.0)),
                            "dist" to h.optInt("dist", 0),
                            "score" to "%.2f".format(sc)))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1.4f), verticalAlignment = Alignment.CenterVertically) {
                        if (isBest) Box(Modifier.size(5.dp).clip(CircleShape)
                            .background(ZxT.PriLite))
                        Spacer(Modifier.width(4.dp))
                        Column(Modifier.weight(1f)) {
                            Text(h.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${h.optString("floor", "")}　${h.optString("dir", "")}",
                                fontSize = 7.5.sp, color = ZxT.Text3)
                        }
                    }
                    Text("%.0f".format(h.optDouble("price", 0.0)), fontSize = 9.sp,
                        color = ZxT.Text, modifier = Modifier.weight(1f),
                        textAlign = TextAlign.End, fontFamily = ZxT.Mono)
                    Text("%.0f".format(h.optDouble("area", 0.0)), fontSize = 9.sp,
                        color = ZxT.Text2, modifier = Modifier.weight(1f),
                        textAlign = TextAlign.End, fontFamily = ZxT.Mono)
                    val d = h.optInt("dist", 0)
                    Text("${d}′", fontSize = 9.sp,
                        color = if (d <= 30) ZxT.Ok else if (d <= 50) ZxT.Text
                        else ZxT.Err,
                        modifier = Modifier.weight(1f), textAlign = TextAlign.End,
                        fontFamily = ZxT.Mono)
                }
            }
            val cheapest = houses.minByOrNull { it.optDouble("price", 0.0) }
            verdict(best != null,
                title = "推荐「${best?.optString("n", "") ?: ""}」——" +
                    "**不是最便宜的**（最便宜是 ${cheapest?.optString("n", "") ?: "—"}）",
                why = "**租房比选要同时看价格、通勤、面积**——" +
                    "只看单价会选到通勤 70 分钟的")
            note("已有 compare 是<b>对比卡</b>、shop 是<b>商品</b>，" +
                "这个是<b>租房比选</b>：多房源 + <b>三维度综合分</b>。" +
                "<b>缺必看维度（价格/面积/通勤）会被拦</b>；<b>价格非正会被拦</b>。")
        }
    }

    /* ══════════ zx_repair 报修工单 ══════════ */
    @Composable
    fun repair(o: JSONObject, ctx: RenderContext) {
        // s:状态 0已报 1已派单 2上门中 3已完工 4已评价
        // who:当前责任人  logs:{t:时间, w:谁, m:内容}
        val logs = jarr(o, "logs")
        var s by remember { mutableIntStateOf(o.optInt("s", 0)) }
        val ST = listOf("已报修", "已派单", "上门中", "已完工", "已评价")
        val owners = listOf("物业前台", "维修班组", "师傅本人", "你（验收）", "完成")
        val pushable = listOf(true, true, false, false, false)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "报修"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(o.optString("no", ""), fontSize = 8.sp, color = ZxT.Text3,
                    fontFamily = ZxT.Mono)
            }
            Spacer(Modifier.height(5.dp))
            // 状态条
            Row(Modifier.fillMaxWidth()) {
                ST.forEachIndexed { k, l ->
                    val done = k <= s
                    Column(Modifier.weight(1f).padding(end = if (k < 4) 3.dp else 0.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.fillMaxWidth().height(5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (done) ZxT.Primary else ZxT.Surface2))
                        Text(l, fontSize = 7.sp,
                            color = if (k == s) ZxT.PriLite else if (done) ZxT.Text2
                            else ZxT.Text3,
                            modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
            Spacer(Modifier.height(5.dp))
            owner(owners[s], "6 小时", pushable[s],
                if (pushable[s]) "**能催**——${owners[s]}，超过半天没动静就打过去"
                else "**只能等**——师傅在路上，催了也不会更快")
            Spacer(Modifier.height(5.dp))
            logs.forEach { l ->
                Row(Modifier.fillMaxWidth().padding(vertical = 1.5.dp)) {
                    Text(l.optString("t", ""), fontSize = 8.sp, color = ZxT.Text3,
                        modifier = Modifier.width(40.dp), fontFamily = ZxT.Mono)
                    Text(l.optString("w", ""), fontSize = 8.sp, color = ZxT.Text2,
                        modifier = Modifier.width(52.dp), maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Text(l.optString("m", ""), fontSize = 9.sp, color = ZxT.Text,
                        modifier = Modifier.weight(1f), lineHeight = 12.sp)
                }
            }
            Spacer(Modifier.height(4.dp))
            Row {
                Box(Modifier.clip(RoundedCornerShape(5.dp))
                    .background(ZxT.Primary.copy(.20f))
                    .clickable {
                        if (s < 4) { s++; ctx.callTool("zx_repair_push", mapOf(
                            "state" to ST[s], "owner" to owners[s],
                            "pushable" to pushable[s])) }
                    }.padding(8.dp, 4.dp)) {
                    Text("推进状态", fontSize = 8.sp, color = ZxT.PriLite)
                }
                Spacer(Modifier.width(6.dp))
                Box(Modifier.clip(RoundedCornerShape(5.dp))
                    .background(if (pushable[s]) ZxT.Warn.copy(.20f) else ZxT.Surface2)
                    .clickable {
                        ctx.callTool("zx_repair_urge", mapOf(
                            "owner" to owners[s], "pushable" to pushable[s]))
                    }.padding(8.dp, 4.dp)) {
                    Text(if (pushable[s]) "催一下" else "催不了", fontSize = 8.sp,
                        color = if (pushable[s]) ZxT.Warn else ZxT.Text3)
                }
            }
            verdict(s < 4, warn = s in 1..2,
                title = "现在卡在「${owners[s]}」——" +
                    "**已 ${if (s <= 1) "6 小时" else "2 天"}**",
                why = "**报修的关键不是「下一步」，是「现在在谁手上、能不能催」**")
            note("已有 playbook 是<b>剧本</b>、warranty 是<b>保修</b>，" +
                "这个是<b>报修工单</b>：状态条 + <b>当前责任人</b> + <b>能不能催</b>。" +
                "<b>缺责任人会被拦</b>；<b>状态非法会被拦</b>；<b>日志时间倒流会被拦</b>。")
        }
    }

    /* ══════════ zx_credential 证件到期 ══════════ */
    @Composable
    fun credential(o: JSONObject, ctx: RenderContext) {
        // items:{n:证件, exp:到期日, left:剩余天, need:提前办理天数, org:办理机构}
        val items = jarr(o, "items").sortedBy { it.optInt("left", 0) }
        var sel by remember { mutableIntStateOf(-1) }
        val dead = items.count { it.optInt("left", 0) <= 0 }
        // 到了该办的时点：剩余天数 <= 提前办理天数
        val dueNow = items.count { it.optInt("left", 0) in 1..it.optInt("need", 30) }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "证件"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(if (dead > 0) "**$dead 项已过期**"
                    else if (dueNow > 0) "**$dueNow 项该办了**" else "都还早",
                    fontSize = 8.5.sp,
                    color = if (dead > 0) ZxT.Err else if (dueNow > 0) ZxT.Warn else ZxT.Ok)
            }
            Spacer(Modifier.height(5.dp))
            items.forEachIndexed { k, it ->
                val left = it.optInt("left", 0)
                val need = it.optInt("need", 30)
                val col = when {
                    left <= 0 -> ZxT.Err
                    left <= need -> ZxT.Warn
                    left <= need * 2 -> ZxT.Text2
                    else -> ZxT.Ok
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2
                        else if (left <= 0) ZxT.Err.copy(.09f)
                        else if (left <= need) ZxT.Warn.copy(.07f) else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_cred_renew", mapOf(
                            "doc" to it.optString("n", ""), "daysLeft" to left,
                            "leadDays" to need, "org" to it.optString("org", "")))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(it.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text,
                                modifier = Modifier.weight(1f), maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text(if (left <= 0) "**已过期**"
                                else if (left <= need) "**该办了**" else "剩 ${left} 天",
                                fontSize = 8.sp, color = col)
                        }
                        Spacer(Modifier.height(2.dp))
                        // 提前量条：剩多少 / 需要多少
                        val full = (left.toFloat() / (need * 3).coerceAtLeast(1))
                            .coerceIn(0f, 1f)
                        Box(Modifier.fillMaxWidth().height(5.dp)
                            .clip(RoundedCornerShape(3.dp)).background(ZxT.Surface2)) {
                            Box(Modifier.fillMaxWidth(full).fillMaxHeight()
                                .background(col))
                        }
                        Text("${it.optString("exp", "")} 到期　需提前 ${need} 天　" +
                            it.optString("org", ""), fontSize = 7.5.sp, color = ZxT.Text3)
                    }
                }
            }
            verdict(dead == 0, warn = dueNow > 0,
                title = when {
                    dead > 0 -> "**$dead 项已过期**——**过期的证件办什么都办不了**"
                    dueNow > 0 -> "**$dueNow 项进入办理窗口**——" +
                        "**提前量是按办理周期算的，不是随便定的**"
                    else -> "${items.size} 项都还早"
                },
                why = "**证件卡要给「该办了」的时点**——" +
                    "只给到期日，用户不知道提前多久动手（有的要办两周）")
            note("已有 expiry 是<b>食品保质期</b>、warranty 是<b>保修</b>，" +
                "这个是<b>证件到期</b>：剩余天 + <b>提前办理窗口</b> + 办理机构。" +
                "<b>缺有效期会被拦</b>；<b>缺提前办理天数会被拦</b>。")
        }
    }

    /* ══════════ zx_parcel 快递寄件 ══════════ */
    @Composable
    fun parcel(o: JSONObject, ctx: RenderContext) {
        // nodes:{n:节点, t:时间, s:0未到 1当前 2已过}
        val nodes = jarr(o, "nodes")
        var sel by remember { mutableIntStateOf(-1) }
        val cur = nodes.indexOfFirst { it.optInt("s", 0) == 1 }
        val cn = nodes.getOrNull(cur)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "寄件"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(o.optString("no", ""), fontSize = 8.sp, color = ZxT.Text3,
                    fontFamily = ZxT.Mono)
            }
            Spacer(Modifier.height(5.dp))
            nodes.forEachIndexed { k, nd ->
                val s = nd.optInt("s", 0)
                val col = when (s) { 2 -> ZxT.Ok; 1 -> ZxT.Primary; else -> ZxT.Text3 }
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2
                        else if (s == 1) ZxT.Primary.copy(.14f) else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_parcel_node", mapOf(
                            "node" to nd.optString("n", ""),
                            "time" to nd.optString("t", ""), "state" to s))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(16.dp)) {
                        Box(Modifier.size(if (s == 1) 11.dp else 9.dp)
                            .clip(CircleShape).background(col))
                        if (k < nodes.size - 1)
                            Box(Modifier.width(1.5.dp).height(14.dp)
                                .background(if (s == 2) ZxT.Ok else ZxT.Line))
                    }
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text(nd.optString("n", ""), fontSize = 9.5.sp,
                            color = if (s == 1) ZxT.Text else ZxT.Text2)
                        if (nd.optString("t", "").isNotBlank())
                            Text(nd.optString("t", ""), fontSize = 7.5.sp,
                                color = ZxT.Text3, fontFamily = ZxT.Mono)
                    }
                    if (s == 1) Text("**当前**", fontSize = 7.5.sp, color = ZxT.PriLite)
                }
            }
            if (cn != null)
                owner("快递公司", "1 天 4 小时", true,
                    "**运输途中催了通常没用，但超时可以索赔**")
            verdict(true, warn = cur >= 0 && cur < nodes.size - 1,
                title = "当前在「${cn?.optString("n", "") ?: "已签收"}」——" +
                    "**这类事大部分时间不在你手上**",
                why = "**寄件卡要给轨迹节点的时间**——" +
                    "只有节点没时间，用户看不出是正常还是已经滞留")
            note("已有 order 是<b>订单</b>、playbook 是<b>剧本</b>，" +
                "这个是<b>快递寄件</b>：轨迹节点 + 时间 + 当前在谁手上。" +
                "<b>节点时间倒流会被拦</b>；<b>缺当前节点会被拦</b>。")
        }
    }

    /* ══════════ zx_interview 面试流程 ══════════ */
    @Composable
    fun interview(o: JSONObject, ctx: RenderContext) {
        // rounds:{n:轮次, s:0未开始 1进行中 2通过 3未通过, t:时间}
        val rounds = jarr(o, "rounds")
        var sel by remember { mutableIntStateOf(-1) }
        val cur = rounds.indexOfFirst { it.optInt("s", 0) == 1 }
        val STC = listOf(ZxT.Text3, ZxT.Primary, ZxT.Ok, ZxT.Err)
        val STL = listOf("未开始", "进行中", "通过", "未通过")

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "面试"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(o.optString("co", ""), fontSize = 8.5.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(5.dp))
            rounds.forEachIndexed { k, r ->
                val s = r.optInt("s", 0)
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2
                        else if (s == 1) ZxT.Primary.copy(.14f)
                        else if (s == 2) ZxT.Ok.copy(.06f)
                        else if (s == 3) ZxT.Err.copy(.06f) else Color.Transparent)
                    .padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(16.dp)) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(STC[s]))
                        if (k < rounds.size - 1)
                            Box(Modifier.width(1.5.dp).height(14.dp)
                                .background(if (s == 2) ZxT.Ok else ZxT.Line))
                    }
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(r.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text,
                                modifier = Modifier.weight(1f), maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text(STL[s], fontSize = 8.sp, color = STC[s])
                        }
                        if (r.optString("t", "").isNotBlank())
                            Text(r.optString("t", ""), fontSize = 7.5.sp,
                                color = ZxT.Text3, fontFamily = ZxT.Mono)
                    }
                    Spacer(Modifier.width(4.dp))
                    Row {
                        listOf(2 to "过", 3 to "挂").forEach { (v, l) ->
                            Box(Modifier.padding(start = 3.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (s == v)
                                    (if (v == 2) ZxT.Ok else ZxT.Err) else ZxT.Surface2)
                                .clickable {
                                    sel = k
                                    ctx.callTool("zx_interview_result", mapOf(
                                        "round" to r.optString("n", ""),
                                        "result" to if (v == 2) "pass" else "fail"))
                                }.padding(6.dp, 2.dp)) {
                                Text(l, fontSize = 7.5.sp,
                                    color = if (s == v) Color.White else ZxT.Text2)
                            }
                        }
                    }
                }
            }
            if (cur >= 0)
                owner("对方 HR", "3 天", true,
                    "**面试结果不由你决定**——超过一周没回音可以主动问")
            verdict(cur >= 0, warn = cur >= 0,
                title = if (cur >= 0) "卡在「${rounds.getOrNull(cur)?.optString("n", "")}」" +
                    "——**结果在对方手上**"
                    else "流程已结束",
                why = "**面试卡要给每轮的时间**——" +
                    "只有轮次没时间，看不出是不是已经被默拒了")
            note("已有 examprep 是<b>备考</b>、playbook 是<b>剧本</b>，" +
                "这个是<b>面试流程</b>：轮次 + 结果 + 时间。" +
                "<b>状态非法会被拦</b>；<b>轮次时间倒流会被拦</b>。")
        }
    }

    /* ══════════ zx_visa 签证材料 ══════════ */
    @Composable
    fun visa(o: JSONObject, ctx: RenderContext) {
        // docs:{n:材料, must:是否必需, s:0缺 1准备中 2已齐}
        val docs = jarr(o, "docs")
        val state = remember { mutableStateListOf(*Array(docs.size) { 0 }) }
        var sel by remember { mutableIntStateOf(-1) }
        val musts = docs.mapIndexedNotNull { i, d ->
            if (d.optBoolean("must", false)) i else null }
        val mustLeft = musts.count { state[it] < 2 }
        val done = state.count { it == 2 }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "签证"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text("$done/${docs.size} 已齐", fontSize = 8.5.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().height(6.dp)
                .clip(RoundedCornerShape(3.dp)).background(ZxT.Surface2)) {
                Box(Modifier.fillMaxWidth(done.toFloat() / docs.size.coerceAtLeast(1))
                    .fillMaxHeight().background(if (mustLeft == 0) ZxT.Ok else ZxT.Primary))
            }
            Spacer(Modifier.height(5.dp))
            docs.forEachIndexed { k, d ->
                val s = state[k]
                val m = d.optBoolean("must", false)
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2
                        else if (s == 2) ZxT.Ok.copy(.06f)
                        else if (m && s == 0) ZxT.Err.copy(.07f) else Color.Transparent)
                    .clickable {
                        state[k] = (s + 1) % 3; sel = k
                        ctx.callTool("zx_visa_doc", mapOf(
                            "doc" to d.optString("n", ""), "must" to m,
                            "state" to state[k]))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp))
                        .background(when (s) { 2 -> ZxT.Ok; 1 -> ZxT.Warn; else -> ZxT.Surface3 }))
                    Spacer(Modifier.width(6.dp))
                    Text(d.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text,
                        modifier = Modifier.weight(1f), maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Text(when (s) { 2 -> "已齐"; 1 -> "准备中"; else -> "缺" },
                        fontSize = 8.sp,
                        color = when (s) { 2 -> ZxT.Ok; 1 -> ZxT.Warn; else -> ZxT.Text3 })
                    if (m) Text("　必", fontSize = 7.5.sp, color = ZxT.Err)
                }
            }
            owner("使馆", "递交后 5 天", false,
                "**审核时长不由你决定**——这段时间催签反而可能坏事")
            verdict(mustLeft == 0, warn = mustLeft > 0,
                title = if (mustLeft > 0) "**$mustLeft 项必需材料还没齐**——" +
                    "**缺必需材料递交了也会被退回**"
                    else "必需材料已齐，可递交",
                why = "**签证材料要区分必需和可选**——" +
                    "混在一起列出，用户不知道缺哪样会直接被拒")
            note("已有 tripkit 是<b>出行清单</b>、credential 是<b>证件</b>，" +
                "这个是<b>签证材料</b>：必需/可选 + 三态 + 递交后归属。" +
                "<b>缺必需标记会被拦</b>；<b>没有任何必需材料会被拦</b>。")
        }
    }

    /* ══════════ zx_insurance 保单 ══════════ */
    @Composable
    fun insurance(o: JSONObject, ctx: RenderContext) {
        // covers:{n:保障项, amt:保额, used:已用}  exp:到期日 left:剩余天
        val covers = jarr(o, "covers")
        var sel by remember { mutableIntStateOf(-1) }
        val left = o.optInt("left", 0)
        val total = covers.sumOf { it.optDouble("amt", 0.0) }
        val mx = covers.maxOf { it.optDouble("amt", 0.0) }.coerceAtLeast(1.0)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "保单"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text(if (left <= 0) "**已过期**"
                    else if (left <= 30) "**剩 ${left} 天**" else "剩 ${left} 天",
                    fontSize = 8.5.sp,
                    color = if (left <= 0) ZxT.Err else if (left <= 30) ZxT.Warn
                    else ZxT.Text3)
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("¥${"%.0f".format(total)}", fontSize = 20.sp, color = ZxT.Text)
                Text("　总保额", fontSize = 8.sp, color = ZxT.Text3,
                    modifier = Modifier.padding(bottom = 3.dp))
                Spacer(Modifier.weight(1f))
                Text(o.optString("exp", ""), fontSize = 8.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(5.dp))
            covers.forEachIndexed { k, c ->
                val amt = c.optDouble("amt", 0.0)
                val used = c.optDouble("used", 0.0)
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        sel = k
                        ctx.callTool("zx_ins_claim", mapOf(
                            "cover" to c.optString("n", ""),
                            "amount" to "%.0f".format(amt),
                            "used" to "%.0f".format(used)))
                    }.padding(6.dp, 4.dp)) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(c.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text,
                                modifier = Modifier.weight(1f), maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text("¥${"%.0f".format(amt)}", fontSize = 9.sp, color = ZxT.Text,
                                fontFamily = ZxT.Mono)
                        }
                        Spacer(Modifier.height(2.dp))
                        Box(Modifier.fillMaxWidth().height(5.dp)
                            .clip(RoundedCornerShape(3.dp)).background(ZxT.Surface2)) {
                            Box(Modifier.fillMaxWidth((amt / mx).toFloat())
                                .fillMaxHeight().background(PAL[k % PAL.size]))
                            if (used > 0)
                                Box(Modifier.fillMaxWidth((used / mx).toFloat())
                                    .fillMaxHeight().background(ZxT.Err.copy(.55f)))
                        }
                        if (used > 0)
                            Text("已理赔 ¥${"%.0f".format(used)}", fontSize = 7.5.sp,
                                color = ZxT.Err)
                    }
                }
            }
            verdict(left > 30,
                warn = left in 1..30,
                title = when {
                    left <= 0 -> "**已过期**——**过期的保单等于没买**"
                    left <= 30 -> "**剩 ${left} 天**——**该续保了，断保再买要重新核保**"
                    else -> "${covers.size} 项保障，总保额 ¥${"%.0f".format(total)}"
                },
                why = "**保单卡要给保额和到期日**——" +
                    "只写「有保险」，出事时才知道赔多少、还能不能赔")
            note("已有 warranty 是<b>保修</b>、credential 是<b>证件</b>，" +
                "这个是<b>保单</b>：分项保额 + 已理赔 + <b>到期标红</b>。" +
                "<b>缺保额会被拦</b>；<b>缺到期日会被拦</b>；<b>保障项为空会被拦</b>。")
        }
    }

    /* ══════════ zx_reimburse 报销单据 ══════════ */
    @Composable
    fun reimburse(o: JSONObject, ctx: RenderContext) {
        // items:{n:单据, v:金额, s:0待审 1已审 2驳回, why:驳回原因}  total:申报总额
        val items = jarr(o, "items")
        var sel by remember { mutableIntStateOf(-1) }
        val total = o.optDouble("total", Double.NaN)
        val sum = items.sumOf { it.optDouble("v", 0.0) }
        val ok = items.filter { it.optInt("s", 0) == 1 }
        val bad = items.filter { it.optInt("s", 0) == 2 }
        val pend = items.filter { it.optInt("s", 0) == 0 }
        val okSum = ok.sumOf { it.optDouble("v", 0.0) }
        val badNoWhy = bad.count { it.optString("why", "").isBlank() }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "报销"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text("${items.size} 张", fontSize = 8.5.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("¥${"%.0f".format(sum)}", fontSize = 20.sp, color = ZxT.Text)
                if (!total.isNaN()) {
                    Spacer(Modifier.width(6.dp))
                    val diff = sum - total
                    Column(modifier = Modifier.weight(1f)) {
                        Text("申报 ¥${"%.0f".format(total)}", fontSize = 8.sp,
                            color = ZxT.Text3)
                        Text(if (Math.abs(diff) < 0.01) "**对得上**"
                            else "**差 ${if (diff > 0) "+" else "−"}¥" +
                                "%.0f".format(Math.abs(diff)) + "**",
                            fontSize = 9.sp,
                            color = if (Math.abs(diff) < 0.01) ZxT.Ok else ZxT.Err)
                    }
                }
            }
            Spacer(Modifier.height(5.dp))
            Row(Modifier.fillMaxWidth()) {
                listOf(Triple("待审", pend.size, ZxT.Text3),
                    Triple("已审", ok.size, ZxT.Ok),
                    Triple("驳回", bad.size, ZxT.Err)).forEach { (l, n, c) ->
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
            items.forEachIndexed { k, it ->
                val s = it.optInt("s", 0)
                val c = when (s) { 1 -> ZxT.Ok; 2 -> ZxT.Err; else -> ZxT.Text3 }
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2
                        else if (s == 2) ZxT.Err.copy(.07f)
                        else if (s == 1) ZxT.Ok.copy(.06f) else Color.Transparent)
                    .padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(it.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text,
                                modifier = Modifier.weight(1f), maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text("¥${"%.0f".format(it.optDouble("v", 0.0))}",
                                fontSize = 9.sp, color = ZxT.Text, fontFamily = ZxT.Mono)
                        }
                        Text(when (s) { 1 -> "已审"; 2 -> "驳回：${it.optString("why", "—")}"
                            else -> "待审" }, fontSize = 7.5.sp, color = c)
                    }
                    Spacer(Modifier.width(4.dp))
                    listOf(1 to "过", 2 to "驳").forEach { (v, l) ->
                        Box(Modifier.padding(start = 3.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (s == v)
                                (if (v == 1) ZxT.Ok else ZxT.Err) else ZxT.Surface2)
                            .clickable {
                                sel = k
                                ctx.callTool("zx_reimb_audit", mapOf(
                                    "bill" to it.optString("n", ""),
                                    "result" to if (v == 1) "pass" else "reject",
                                    "amount" to "%.2f".format(it.optDouble("v", 0.0))))
                            }.padding(6.dp, 2.dp)) {
                            Text(l, fontSize = 7.5.sp,
                                color = if (s == v) Color.White else ZxT.Text2)
                        }
                    }
                }
            }
            owner("财务", "2 天", true,
                "**报销卡在财务那**——超过一周没动静可以问，但不影响结果")
            verdict(badNoWhy == 0 && (total.isNaN() || Math.abs(sum - total) < 0.01),
                warn = pend.isNotEmpty(),
                title = when {
                    !total.isNaN() && Math.abs(sum - total) >= 0.01 ->
                        "**单据合计 ¥${"%.0f".format(sum)} 与申报 ¥${"%.0f".format(total)} 对不上**"
                    badNoWhy > 0 -> "**${badNoWhy} 张驳回但没写原因**——**没有原因没法重报**"
                    pend.isNotEmpty() -> "${pend.size} 张待审，已核准 ¥${"%.0f".format(okSum)}"
                    else -> "已审 ¥${"%.0f".format(okSum)}，驳回 ${bad.size} 张"
                },
                why = "**报销要先对账再走流程**——" +
                    "合计对不上就进审核，退回来的成本比先核对高得多")
            note("已有 ledger 是<b>记账</b>、splitbill 是<b>AA</b>，" +
                "这个是<b>报销单据</b>：合计对账 + 逐张审驳 + <b>驳回要写原因</b>。" +
                "<b>合计与申报对不上会被拦</b>；<b>金额非正会被拦</b>。")
        }
    }

    /* ══════════ zx_relocation 搬家迁入 ══════════ */
    @Composable
    fun relocation(o: JSONObject, ctx: RenderContext) {
        // rooms:{n:房间, box:箱数, s:0未打包 1已打包 2已到位}
        val rooms = jarr(o, "rooms")
        val state = remember { mutableStateListOf(*Array(rooms.size) { 0 }) }
        var sel by remember { mutableIntStateOf(-1) }
        val boxes = rooms.sumOf { it.optInt("box", 0) }
        val placed = state.mapIndexedNotNull { i, s ->
            if (s == 2) rooms[i].optInt("box", 0) else null }.sum()
        val packed = state.count { it >= 1 }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "搬家"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Text("$placed/$boxes 箱到位", fontSize = 8.5.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().height(6.dp)
                .clip(RoundedCornerShape(3.dp)).background(ZxT.Surface2)) {
                Box(Modifier.fillMaxWidth(placed.toFloat() / boxes.coerceAtLeast(1))
                    .fillMaxHeight().background(ZxT.Ok))
            }
            Spacer(Modifier.height(5.dp))
            rooms.forEachIndexed { k, r ->
                val s = state[k]
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == k) ZxT.Surface2
                        else if (s == 2) ZxT.Ok.copy(.06f)
                        else if (s == 1) ZxT.Primary.copy(.08f) else Color.Transparent)
                    .clickable {
                        state[k] = (s + 1) % 3; sel = k
                        ctx.callTool("zx_relo_room", mapOf(
                            "room" to r.optString("n", ""),
                            "state" to state[k], "boxes" to r.optInt("box", 0)))
                    }.padding(6.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp))
                        .background(when (s) { 2 -> ZxT.Ok; 1 -> ZxT.Primary
                            else -> ZxT.Surface3 }))
                    Spacer(Modifier.width(6.dp))
                    Text(r.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text,
                        modifier = Modifier.weight(1f), maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Text("${r.optInt("box", 0)} 箱", fontSize = 8.sp, color = ZxT.Text3,
                        fontFamily = ZxT.Mono)
                    Spacer(Modifier.width(5.dp))
                    Text(when (s) { 2 -> "已到位"; 1 -> "已打包"; else -> "未打包" },
                        fontSize = 8.sp,
                        color = when (s) { 2 -> ZxT.Ok; 1 -> ZxT.PriLite; else -> ZxT.Text3 })
                }
            }
            owner("搬家公司", "当天", true,
                "**搬运当天在对方手上**——易碎品要自己盯着，别的可以催")
            verdict(placed >= boxes, warn = placed < boxes && packed >= rooms.size,
                title = if (placed >= boxes) "$boxes 箱全部到位"
                    else if (packed >= rooms.size) "**全部打包完，等搬运**——" +
                        "**这一步在搬家公司手上**"
                    else "还有 ${rooms.size - packed} 个房间没打包",
                why = "**搬家要按房间管，不能按箱管**——" +
                    "只数箱数，到了新家才知道哪个房间的东西散在哪")
            note("已有 tripkit 是<b>出行清单</b>、inventory 是<b>库存</b>，" +
                "这个是<b>搬家迁入</b>：按房间 + 箱数 + 三态（未打包/已打包/已到位）。" +
                "<b>缺房间会被拦</b>；<b>箱数非正会被拦</b>。")
        }
    }

    /* ══════════ zx_booking 场地预订 ══════════ */
    @Composable
    fun booking(o: JSONObject, ctx: RenderContext) {
        // slots:{d:日期, t:时段, s:0可订 1已订 2不可订, by:预订人}
        val slots = jarr(o, "slots")
        var sel by remember { mutableIntStateOf(-1) }
        val free = slots.count { it.optInt("s", 0) == 0 }
        val taken = slots.count { it.optInt("s", 0) == 1 }
        val blocked = slots.count { it.optInt("s", 0) == 2 }
        val days = LinkedHashMap<String, MutableList<Int>>()
        slots.forEachIndexed { i, s ->
            days.getOrPut(s.optString("d", "")) { mutableListOf() }.add(i)
        }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "场地"), fontSize = 11.sp, color = ZxT.Text,
                    modifier = Modifier.weight(1f))
                Row {
                    Text("$free 可订", fontSize = 8.sp, color = ZxT.Ok)
                    Text("　$taken 已订", fontSize = 8.sp, color = ZxT.Warn,
                        modifier = Modifier.padding(start = 6.dp))
                    Text("　$blocked 不可订", fontSize = 8.sp, color = ZxT.Text3,
                        modifier = Modifier.padding(start = 6.dp))
                }
            }
            Spacer(Modifier.height(5.dp))
            days.forEach { (d, idxs) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(d, fontSize = 8.sp, color = ZxT.Text3,
                        modifier = Modifier.width(40.dp), fontFamily = ZxT.Mono)
                    Row(Modifier.weight(1f)) {
                        idxs.forEach { i ->
                            val s = slots[i]
                            val st = s.optInt("s", 0)
                            val col = when (st) { 0 -> ZxT.Ok; 1 -> ZxT.Warn
                                else -> ZxT.Surface2 }
                            Box(Modifier.weight(1f).padding(end = 2.dp).height(26.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(col.copy(alpha = if (st == 0) .20f else .35f))
                                .border(1.dp,
                                    if (sel == i) ZxT.PriLite else Color.Transparent,
                                    RoundedCornerShape(4.dp))
                                .clickable {
                                    if (st != 2) { sel = i
                                        ctx.callTool("zx_book_pick", mapOf(
                                            "date" to d, "slot" to s.optString("t", ""),
                                            "state" to st,
                                            "by" to s.optString("by", ""))) }
                                }, contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(s.optString("t", ""), fontSize = 7.5.sp,
                                        color = if (st == 2) ZxT.Text3 else ZxT.Text)
                                    if (st == 1)
                                        Text(s.optString("by", ""), fontSize = 6.5.sp,
                                            color = ZxT.Warn, maxLines = 1)
                                    if (st == 2)
                                        Text("—", fontSize = 7.sp, color = ZxT.Text3)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row {
                listOf(Triple("可订", ZxT.Ok, 0), Triple("已订", ZxT.Warn, 1),
                    Triple("不可订", ZxT.Text3, 2)).forEach { (l, c, _) ->
                    Row(Modifier.padding(end = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp))
                            .background(c.copy(alpha = .35f)))
                        Text("　$l", fontSize = 7.5.sp, color = ZxT.Text3)
                    }
                }
            }
            verdict(free > 0, warn = free <= 2,
                title = if (free == 0) "**没有可订时段**——**要换日期或换场地**"
                    else "$free 个时段可订" +
                        if (free <= 2) "——**快满了，该定了**" else "",
                why = "**预订卡要把「不可订」和「已订」分开**——" +
                    "都画成灰色，用户会以为已订的还能抢")
            note("已有 calendar 是<b>日历</b>、agenda 是<b>日程</b>，" +
                "这个是<b>场地预订</b>：时段网格 + <b>可订/已订/不可订三态</b>。" +
                "<b>时段冲突会被拦</b>；<b>结束早于开始会被拦</b>。")
        }
    }
}
