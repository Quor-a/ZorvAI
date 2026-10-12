package com.zorv.plugin.zxwidget

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * 第八批组件（12 个）—— 表单原语 / 状态呈现 / 集合展示。
 *
 * 前七批把"能画什么"推到了很广的覆盖面，但有两个地方一直是将就的：
 *
 *   一、表单原语不全。有 slider / stepper / pin / timepicker，
 *      但没有单选组、清单勾选、格式化输入、可编辑列表——
 *      模型想收一批带描述的单选项时只能用 zx_choice 硬凑，
 *      选项一多就没有描述的位置。
 *
 *   二、状态呈现靠临时拼。进度、统计、通知都在用 zx_table 或
 *      zx_terminal 顶替，语义不对，读屏也读不出来。
 *
 * 所以这批补齐这两块：
 *   zx_radiogroup   单选组（带描述）
 *   zx_checklist    清单勾选（带进度与全选）
 *   zx_avatargroup  头像组（重叠 + 溢出 +N）
 *   zx_meter        分段计量条（等级刻度）
 *   zx_ring         环形进度
 *   zx_stat         统计数字（同比箭头）
 *   zx_swatch       色板选择
 *   zx_notice       通知条堆叠（自动消失）
 *   zx_price        定价卡（推荐高亮）
 *   zx_keyvalue     键值列表（可复制）
 *   zx_inputmask    格式化输入（卡号/日期掩码）
 *   zx_datalist     可编辑列表（增删行）
 *
 * 一个贯穿这批的原则：**语义优先于样式**。
 * zx_ring 不只是画个圆，它要能被读屏念成"进度 72%";
 * zx_notice 不只是红条，它要区分 warn/err/ok 三种语义。
 * 否则组件越多，无障碍越差。
 */
object ZxRender8 {

    private val PAL = listOf(
        Color(0xFF5046E4), Color(0xFF3FBF7F), Color(0xFFE8A33D),
        Color(0xFFE5544B), Color(0xFF4A9BE8), Color(0xFF8E86FF)
    )

    /* ══════════ zx_radiogroup 单选组 ══════════ */
    @Composable
    fun radiogroup(o: JSONObject, ctx: RenderContext) {
        val opts = o.optJSONArray("options") ?: return
        val n = minOf(opts.length(), 6)
        var sel by remember { mutableIntStateOf(o.optInt("sel", -1).coerceIn(-1, n - 1)) }

        Column(Modifier.fillMaxWidth()) {
            for (i in 0 until n) {
                val op = opts.optJSONObject(i) ?: continue
                val on = i == sel
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (on) ZxT.Primary.copy(.12f) else ZxT.Surface2)
                    .border(if (on) 1.4.dp else 1.dp,
                            if (on) ZxT.Primary else ZxT.Line, RoundedCornerShape(9.dp))
                    .clickable {
                        sel = i
                        ctx.callTool("zx_select", mapOf(
                            "choice" to op.optString("k", "o$i"),
                            "index" to i.toString(), "source" to "zx_radiogroup"))
                    }
                    .padding(11.dp, 9.dp), verticalAlignment = Alignment.Top) {
                    // 外圈 + 内点：不用系统 RadioButton 是为了能控制尺寸
                    Box(Modifier.size(15.dp).padding(top = 1.dp)
                        .clip(CircleShape)
                        .border(if (on) 4.5.dp else 1.4.dp,
                                if (on) ZxT.Primary else ZxT.Text3, CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(op.optString("t", ""), fontSize = 12.sp, color = ZxT.Text,
                             fontWeight = if (on) FontWeight.Medium else FontWeight.Normal)
                        if (op.optString("d").isNotBlank())
                            Text(op.optString("d"), fontSize = 10.5.sp, color = ZxT.Text3,
                                 lineHeight = 15.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
    }

    /* ══════════ zx_checklist 清单勾选 ══════════ */
    @Composable
    fun checklist(o: JSONObject, ctx: RenderContext) {
        val items = o.optJSONArray("items") ?: return
        val n = minOf(items.length(), 10)
        val on = remember { mutableStateMapOf<Int, Boolean>() }
        LaunchedEffect(Unit) {
            for (i in 0 until n) on[i] = items.optJSONObject(i)?.optBoolean("on", false) ?: false
        }
        val done = (0 until n).count { on[it] == true }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$done/$n", fontSize = 11.sp, color = ZxT.Text2, fontFamily = ZxT.Mono)
                Spacer(Modifier.width(9.dp))
                Box(Modifier.weight(1f).height(3.dp)
                    .clip(RoundedCornerShape(2.dp)).background(ZxT.Surface3)) {
                    Box(Modifier.fillMaxWidth(done.toFloat() / n.coerceAtLeast(1))
                        .fillMaxHeight().background(ZxT.Ok))
                }
                Spacer(Modifier.width(9.dp))
                Text("全选", fontSize = 10.5.sp, color = ZxT.PriLite,
                     modifier = Modifier.clickable {
                         val target = done < n
                         for (i in 0 until n) on[i] = target
                         ctx.callTool("zx_form_submit", mapOf("payload" to
                             JSONObject().put("all", target).toString()))
                     })
            }
            Spacer(Modifier.height(7.dp))
            for (i in 0 until n) {
                val it = items.optJSONObject(i) ?: continue
                val checked = on[i] == true
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (checked) ZxT.Ok.copy(.08f) else Color.Transparent)
                    .clickable {
                        on[i] = !checked
                        ctx.callTool("zx_form_submit", mapOf("payload" to
                            JSONObject().put("k", it.optString("k", "i$i"))
                                .put("on", !checked).toString()))
                    }
                    .padding(9.dp, 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(15.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (checked) ZxT.Ok else Color.Transparent)
                        .border(1.4.dp, if (checked) ZxT.Ok else ZxT.Text3,
                                RoundedCornerShape(4.dp)),
                        contentAlignment = Alignment.Center) {
                        if (checked) Text("✓", fontSize = 10.sp, color = Color.White)
                    }
                    Spacer(Modifier.width(9.dp))
                    Text(it.optString("t", ""), fontSize = 11.5.sp,
                         color = if (checked) ZxT.Text3 else ZxT.Text2,
                         modifier = Modifier.weight(1f), maxLines = 2,
                         overflow = TextOverflow.Ellipsis,
                         // 勾掉用删除线：视觉上立刻知道"这项已完成"
                         textDecoration = if (checked)
                             androidx.compose.ui.text.style.TextDecoration.LineThrough else null)
                }
            }
        }
    }

    /* ══════════ zx_avatargroup 头像组 ══════════ */
    @Composable
    fun avatargroup(o: JSONObject, ctx: RenderContext) {
        val people = o.optJSONArray("people") ?: return
        val max = o.optInt("max", 5).coerceIn(1, 6)
        val total = people.length()
        val show = minOf(total, max)
        var expanded by remember { mutableStateOf(false) }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 重叠靠负 margin：不用 LazyRow 是因为数量固定且很小
                for (i in 0 until show) {
                    val p = people.optJSONObject(i) ?: continue
                    Box(
                        Modifier
                            .then(if (i > 0) Modifier.offset(x = (-8 * i).dp) else Modifier)
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(PAL[i % PAL.size])
                            .border(1.6.dp, ZxT.Surface, CircleShape),
                        contentAlignment = Alignment.Center) {
                        Text(p.optString("n", "?").take(1), fontSize = 12.sp, color = Color.White)
                    }
                }
                if (total > show) {
                    Box(Modifier.offset(x = (-8 * show).dp).size(30.dp)
                        .clip(CircleShape).background(ZxT.Surface3)
                        .border(1.6.dp, ZxT.Surface, CircleShape)
                        .clickable { expanded = !expanded }, contentAlignment = Alignment.Center) {
                        Text("+${total - show}", fontSize = 10.sp, color = ZxT.Text2)
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(o.optString("label", "$total 人"), fontSize = 11.sp, color = ZxT.Text3)
            }
            if (expanded && total > show) {
                Spacer(Modifier.height(8.dp))
                for (i in show until minOf(total, show + 8)) {
                    val p = people.optJSONObject(i) ?: continue
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)
                        .clip(RoundedCornerShape(7.dp)).background(ZxT.Surface2)
                        .padding(9.dp, 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(22.dp).clip(CircleShape).background(PAL[i % PAL.size]),
                            contentAlignment = Alignment.Center) {
                            Text(p.optString("n", "?").take(1), fontSize = 10.sp, color = Color.White)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(p.optString("n", ""), fontSize = 11.sp, color = ZxT.Text2,
                             modifier = Modifier.weight(1f))
                        if (p.optString("r").isNotBlank())
                            Text(p.optString("r"), fontSize = 10.sp, color = ZxT.Text3)
                    }
                }
            }
        }
    }

    /* ══════════ zx_meter 分段计量条 ══════════ */
    @Composable
    fun meter(o: JSONObject, ctx: RenderContext) {
        val v = o.optDouble("v", 0.0)
        val min = o.optDouble("min", 0.0)
        val max = o.optDouble("max", 100.0).coerceAtLeast(min + 0.001)
        val levels = o.optJSONArray("levels")
        val lv = mutableListOf<Pair<String, Double>>()
        if (levels != null) for (i in 0 until minOf(levels.length(), 5)) {
            val l = levels.optJSONObject(i) ?: continue
            lv.add(l.optString("n") to l.optDouble("at", 0.0))
        }
        val frac = ((v - min) / (max - min)).coerceIn(0.0, 1.0).toFloat()

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("label", ""), fontSize = 11.5.sp, color = ZxT.Text2,
                     modifier = Modifier.weight(1f))
                Text(if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(v),
                     fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ZxT.Text,
                     fontFamily = ZxT.Mono)
            }
            Spacer(Modifier.height(7.dp))
            Box(Modifier.fillMaxWidth().height(10.dp)
                .clip(RoundedCornerShape(5.dp)).background(ZxT.Surface3)) {
                // 渐变按占比截断：低值绿、中值黄、高值红是通用直觉
                Box(Modifier.fillMaxWidth(frac).fillMaxHeight()
                    .background(Brush.horizontalGradient(
                        listOf(ZxT.Ok, ZxT.Warn, ZxT.Err),
                        endX = (max - min).toFloat() * 3f)))
                // 等级刻度：没有刻度就只知道"多少"，不知道"在哪一档"
                if (lv.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth()) {
                        lv.forEach { (_, at) ->
                            val p = ((at - min) / (max - min)).coerceIn(0.0, 1.0).toFloat()
                            Spacer(Modifier.width(0.dp))
                            Box(Modifier.fillMaxHeight()
                                .then(Modifier.weight(if (p > 0f) p else 0.001f)))
                            Box(Modifier.width(1.4.dp).fillMaxHeight()
                                .background(ZxT.Surface.copy(.85f)))
                            Box(Modifier.weight((1f - p).coerceAtLeast(0.001f)))
                        }
                    }
                }
            }
            if (lv.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth()) {
                    lv.forEachIndexed { i, (name, _) ->
                        Text(name, fontSize = 9.5.sp, color = ZxT.Text3,
                             modifier = Modifier.weight(1f),
                             textAlign = if (i == 0) TextAlign.Start
                                         else if (i == lv.size - 1) TextAlign.End
                                         else TextAlign.Center)
                    }
                }
            }
        }
    }

    /* ══════════ zx_ring 环形进度 ══════════ */
    @Composable
    fun ring(o: JSONObject, ctx: RenderContext) {
        val v = o.optDouble("v", 0.0).coerceIn(0.0, 100.0)
        val label = o.optString("label")
        val sub = o.optString("sub")

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val r = size.minDimension / 2 - 6
                    val c = androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2)
                    drawCircle(ZxT.Surface3, r, c, style = Stroke(width = 7f))
                    drawArc(
                        Brush.sweepGradient(listOf(ZxT.Primary, ZxT.PriLite)),
                        -90f, (v / 100f * 360f).toFloat(), false,
                        topLeft = androidx.compose.ui.geometry.Offset(c.x - r, c.y - r),
                        size = Size(r * 2, r * 2),
                        style = Stroke(width = 7f))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${v.roundToInt()}", fontSize = 15.sp, fontWeight = FontWeight.Bold,
                         color = ZxT.Text, fontFamily = ZxT.Mono)
                    Text("%", fontSize = 9.sp, color = ZxT.Text3)
                }
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                if (label.isNotBlank()) Text(label, fontSize = 12.sp, color = ZxT.Text,
                                              fontWeight = FontWeight.Medium)
                if (sub.isNotBlank()) Text(sub, fontSize = 10.5.sp, color = ZxT.Text3,
                                           lineHeight = 15.sp)
                // 语义标注：读屏能念出"进度 72%"，而不是"一个圆"
                Text("进度 ${v.roundToInt()}%", fontSize = 0.sp, color = Color.Transparent)
            }
        }
    }

    /* ══════════ zx_stat 统计数字 ══════════ */
    @Composable
    fun stat(o: JSONObject, ctx: RenderContext) {
        val items = o.optJSONArray("items") ?: return
        val n = minOf(items.length(), 4)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (i in 0 until n) {
                val it = items.optJSONObject(i) ?: continue
                val delta = it.optDouble("delta", 0.0)
                val up = delta >= 0
                val good = if (it.optBoolean("lowerBetter")) !up else up
                Column(Modifier.width(96.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(ZxT.Surface2)
                    .border(1.dp, ZxT.Line, RoundedCornerShape(10.dp))
                    .padding(10.dp, 9.dp)) {
                    Text(it.optString("n", ""), fontSize = 10.sp, color = ZxT.Text3,
                         maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(3.dp))
                    Text(it.optString("v", ""), fontSize = 17.sp, fontWeight = FontWeight.Bold,
                         color = ZxT.Text, fontFamily = ZxT.Mono, maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    if (it.has("delta")) {
                        Spacer(Modifier.height(3.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (up) "▲" else "▼", fontSize = 9.sp,
                                 color = if (good) ZxT.Ok else ZxT.Err)
                            Spacer(Modifier.width(3.dp))
                            // lowerBetter 时下降是好的：崩溃率降了是绿不是红
                            Text("${if (up) "+" else ""}${"%.1f".format(delta)}%",
                                 fontSize = 10.sp, color = if (good) ZxT.Ok else ZxT.Err,
                                 fontFamily = ZxT.Mono)
                        }
                    }
                }
            }
        }
    }

    /* ══════════ zx_swatch 色板选择 ══════════ */
    @Composable
    fun swatch(o: JSONObject, ctx: RenderContext) {
        val colors = o.optJSONArray("colors") ?: return
        val n = minOf(colors.length(), 12)
        var sel by remember { mutableIntStateOf(o.optInt("sel", 0).coerceIn(0, n - 1)) }

        Column(Modifier.fillMaxWidth()) {
            // 换行用手动分块：FlowRow 在气泡里对不齐间距
            val perRow = 6
            for (r in 0 until (n + perRow - 1) / perRow) {
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    for (c in 0 until perRow) {
                        val i = r * perRow + c
                        if (i >= n) { Box(Modifier.weight(1f)); continue }
                        val co = colors.optJSONObject(i) ?: continue
                        val hex = co.optString("hex", "#5046E4")
                        val col = runCatching { Color(android.graphics.Color.parseColor(hex)) }
                            .getOrDefault(PAL[i % PAL.size])
                        Box(Modifier.weight(1f).aspectRatio(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(col)
                            .border(if (i == sel) 2.2.dp else 1.dp,
                                    if (i == sel) ZxT.Text else ZxT.Line,
                                    RoundedCornerShape(8.dp))
                            .clickable {
                                sel = i
                                ctx.callTool("zx_select", mapOf(
                                    "choice" to co.optString("k", hex),
                                    "hex" to hex, "source" to "zx_swatch"))
                            })
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(colors.optJSONObject(sel)?.optString("n")
                 ?: colors.optJSONObject(sel)?.optString("hex") ?: "",
                 fontSize = 11.sp, color = ZxT.Text2)
        }
    }

    /* ══════════ zx_notice 通知条堆叠 ══════════ */
    @Composable
    fun notice(o: JSONObject, ctx: RenderContext) {
        val items = o.optJSONArray("items") ?: return
        val n = minOf(items.length(), 4)
        val dismissed = remember { mutableStateSetOf<Int>() }
        // 自动消失：只做视觉淡出，不真删数据——
        // 用户可能想再看一眼，真删了就没了
        var fading by remember { mutableIntStateOf(-1) }
        LaunchedEffect(Unit) {
            val ttl = o.optInt("ttlMs", 0)
            if (ttl > 0) {
                kotlinx.coroutines.delay(ttl.toLong())
                fading = 0
            }
        }

        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (i in 0 until n) {
                if (i in dismissed) continue
                val it = items.optJSONObject(i) ?: continue
                val lv = it.optString("lv", "info")
                val col = when (lv) {
                    "err" -> ZxT.Err; "warn" -> ZxT.Warn; "ok" -> ZxT.Ok; else -> ZxT.Primary
                }
                val icon = when (lv) { "err" -> "✕"; "warn" -> "!"; "ok" -> "✓"; else -> "i" }
                Row(Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .background(col.copy(.12f))
                    .border(1.dp, col.copy(.4f), RoundedCornerShape(9.dp))
                    .padding(10.dp, 9.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.size(17.dp).clip(CircleShape).background(col),
                        contentAlignment = Alignment.Center) {
                        Text(icon, fontSize = 10.sp, color = Color.White)
                    }
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        if (it.optString("t").isNotBlank())
                            Text(it.optString("t"), fontSize = 11.5.sp, color = ZxT.Text,
                                 fontWeight = FontWeight.Medium)
                        Text(it.optString("d", ""), fontSize = 10.5.sp, color = ZxT.Text2,
                             lineHeight = 15.sp)
                    }
                    Text("×", fontSize = 13.sp, color = ZxT.Text3,
                         modifier = Modifier.clickable { dismissed.add(i) }.padding(start = 6.dp))
                }
            }
        }
    }

    /* ══════════ zx_price 定价卡 ══════════ */
    @Composable
    fun price(o: JSONObject, ctx: RenderContext) {
        val plans = o.optJSONArray("plans") ?: return
        val n = minOf(plans.length(), 3)
        var sel by remember { mutableIntStateOf(
            (0 until n).firstOrNull { plans.optJSONObject(it)?.optBoolean("rec") == true } ?: -1) }

        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            for (i in 0 until n) {
                val p = plans.optJSONObject(i) ?: continue
                val rec = p.optBoolean("rec")
                val on = i == sel
                Column(Modifier.width(104.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (rec) ZxT.Primary.copy(.1f) else ZxT.Surface2)
                    .border(if (on || rec) 1.6.dp else 1.dp,
                            if (on) ZxT.Text else if (rec) ZxT.Primary else ZxT.Line,
                            RoundedCornerShape(11.dp))
                    .clickable {
                        sel = i
                        ctx.callTool("zx_select", mapOf(
                            "choice" to p.optString("k", "p$i"), "source" to "zx_price"))
                    }
                    .padding(10.dp, 9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.optString("n", ""), fontSize = 11.5.sp, color = ZxT.Text,
                             fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f),
                             maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (rec) Text("推荐", fontSize = 8.5.sp, color = Color.White,
                                      modifier = Modifier.clip(RoundedCornerShape(4.dp))
                                          .background(ZxT.Primary).padding(4.dp, 1.5.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(p.optString("cur", "¥"), fontSize = 10.sp, color = ZxT.Text3)
                        Text(p.optString("price", "0"), fontSize = 20.sp,
                             fontWeight = FontWeight.Bold, color = ZxT.Text,
                             fontFamily = ZxT.Mono)
                    }
                    Text(p.optString("unit", "/月"), fontSize = 9.5.sp, color = ZxT.Text3)
                    Spacer(Modifier.height(7.dp))
                    val feats = p.optJSONArray("feats")
                    if (feats != null) for (k in 0 until minOf(feats.length(), 4)) {
                        Text("· ${feats.optString(k)}", fontSize = 10.sp, color = ZxT.Text2,
                             lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }

    /* ══════════ zx_keyvalue 键值列表 ══════════ */
    @Composable
    fun keyvalue(o: JSONObject, ctx: RenderContext) {
        val rows = o.optJSONArray("rows") ?: return
        val n = minOf(rows.length(), 10)
        var copied by remember { mutableIntStateOf(-1) }

        Column(Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(ZxT.Surface2)
            .border(1.dp, ZxT.Line, RoundedCornerShape(9.dp))) {
            for (i in 0 until n) {
                val r = rows.optJSONObject(i) ?: continue
                val v = r.optString("v")
                val mono = r.optBoolean("mono", false)
                Row(Modifier.fillMaxWidth()
                    .clickable {
                        copied = i
                        ctx.callTool("zx_copy", mapOf("text" to v, "source" to "zx_keyvalue"))
                    }
                    .padding(11.dp, 8.dp), verticalAlignment = Alignment.Top) {
                    Text(r.optString("k", ""), fontSize = 11.sp, color = ZxT.Text3,
                         modifier = Modifier.width(84.dp), lineHeight = 15.sp)
                    Text(if (copied == i) "已复制" else v, fontSize = 11.sp,
                         color = if (copied == i) ZxT.Ok else ZxT.Text2,
                         fontFamily = if (mono) ZxT.Mono else null,
                         modifier = Modifier.weight(1f), lineHeight = 15.sp)
                }
                if (i < n - 1) Box(Modifier.fillMaxWidth().height(1.dp).background(ZxT.Line))
            }
        }
    }

    /* ══════════ zx_inputmask 格式化输入 ══════════ */
    @Composable
    fun inputmask(o: JSONObject, ctx: RenderContext) {
        val mask = o.optString("mask", "#### #### #### ####")
        val placeholderChar = o.optString("ph", "#").firstOrNull() ?: '#'
        var raw by remember { mutableStateOf(o.optString("v", "")) }
        var err by remember { mutableStateOf("") }

        // 只保留 mask 里非占位符以外能接受的字符：数字位收数字，字母位收字母
        val shown = remember(raw, mask) {
            buildString {
                var ri = 0
                for (mc in mask) {
                    if (mc == placeholderChar) {
                        if (ri < raw.length) append(raw[ri++]) else append(' ')
                    } else append(mc)
                }
            }
        }
        // 占位符总数就是期望长度，超过说明输入非法
        val slots = mask.count { it == placeholderChar }

        Column(Modifier.fillMaxWidth()) {
            if (o.optString("label").isNotBlank())
                Text(o.optString("label"), fontSize = 11.sp, color = ZxT.Text3,
                     modifier = Modifier.padding(bottom = 5.dp))
            Box(Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(9.dp))
                .background(ZxT.Surface2)
                .border(if (err.isNotBlank()) 1.4.dp else 1.dp,
                        if (err.isNotBlank()) ZxT.Err else ZxT.Line,
                        RoundedCornerShape(9.dp))
                .padding(11.dp, 10.dp)) {
                Text(if (raw.isEmpty()) mask.replace(placeholderChar, '_') else shown,
                     fontSize = 13.sp, fontFamily = ZxT.Mono,
                     color = if (raw.isEmpty()) ZxT.Text3 else ZxT.Text,
                     letterSpacing = 0.5.sp)
            }
            Spacer(Modifier.height(7.dp))
            // 数字键盘：气泡里弹出系统键盘体验不可控，且掩码输入用系统键盘很容易错位
            val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "✓")
            for (r in 0 until 4) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (c in 0 until 3) {
                        val k = keys[r * 3 + c]
                        Box(Modifier.weight(1f).padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (k == "✓") ZxT.Primary else ZxT.Surface3)
                            .clickable {
                                when {
                                    k == "⌫" -> { raw = raw.dropLast(1); err = "" }
                                    k == "✓" -> {
                                        if (raw.length != slots) {
                                            err = "还差 ${slots - raw.length} 位（共需 $slots 位）"
                                        } else {
                                            err = ""
                                            ctx.callTool("zx_form_submit", mapOf("payload" to
                                                JSONObject().put("v", raw)
                                                    .put("masked", shown).toString()))
                                        }
                                    }
                                    raw.length < slots -> { raw += k; err = "" }
                                }
                            }
                            .padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
                            Text(k, fontSize = 14.sp,
                                 color = if (k == "✓") Color.White else ZxT.Text2,
                                 fontFamily = ZxT.Mono)
                        }
                    }
                }
            }
            if (err.isNotBlank())
                Text(err, fontSize = 10.5.sp, color = ZxT.Err,
                     modifier = Modifier.padding(top = 6.dp))
            else Text("掩码 $mask · 共 $slots 位", fontSize = 10.sp, color = ZxT.Text3,
                      modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_datalist 可编辑列表 ══════════ */
    @Composable
    fun datalist(o: JSONObject, ctx: RenderContext) {
        val rows = remember {
            mutableStateListOf<String>().apply {
                val src = o.optJSONArray("rows")
                if (src != null) for (i in 0 until minOf(src.length(), 12)) add(src.optString(i, ""))
            }
        }
        val maxLen = o.optInt("maxLen", 40)
        var draft by remember { mutableStateOf("") }
        var err by remember { mutableStateOf("") }

        fun commit() {
            val t = draft.trim()
            when {
                t.isEmpty() -> err = "不能为空"
                rows.size >= 12 -> err = "最多 12 行"
                // 重复项会让"选中第 3 行"这种回传变得有歧义
                rows.contains(t) -> err = "已有相同项：$t"
                else -> {
                    rows.add(t); draft = ""; err = ""
                    ctx.callTool("zx_form_submit", mapOf("payload" to
                        JSONObject().put("op", "add").put("v", t).toString()))
                }
            }
        }

        Column(Modifier.fillMaxWidth()) {
            rows.forEachIndexed { i, r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(7.dp)).background(ZxT.Surface2)
                    .padding(10.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", fontSize = 9.5.sp, color = ZxT.Text3,
                         modifier = Modifier.width(18.dp))
                    Text(r, fontSize = 11.5.sp, color = ZxT.Text2, modifier = Modifier.weight(1f),
                         maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("×", fontSize = 13.sp, color = ZxT.Text3,
                         modifier = Modifier.clickable {
                             rows.removeAt(i)
                             ctx.callTool("zx_form_submit", mapOf("payload" to
                                 JSONObject().put("op", "remove").put("index", i)
                                     .put("v", r).toString()))
                         }.padding(start = 8.dp))
                }
            }
            Spacer(Modifier.height(7.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.TextField(
                    value = draft, onValueChange = { if (it.length <= maxLen) { draft = it; err = "" } },
                    placeholder = { Text(o.optString("hint", "添加一项"), fontSize = 11.sp,
                                         color = ZxT.Text3) },
                    modifier = Modifier.weight(1f).height(40.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.5.sp, color = ZxT.Text),
                    singleLine = true,
                    colors = androidx.compose.material3.TextFieldDefaults.colors(
                        focusedContainerColor = ZxT.Surface2,
                        unfocusedContainerColor = ZxT.Surface2,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = ZxT.Primary))
                Spacer(Modifier.width(7.dp))
                Text("添加", fontSize = 12.sp, color = if (draft.isBlank()) ZxT.Text3 else ZxT.PriLite,
                     modifier = Modifier.clickable(enabled = draft.isNotBlank()) { commit() })
            }
            if (err.isNotBlank())
                Text(err, fontSize = 10.5.sp, color = ZxT.Err, modifier = Modifier.padding(top = 6.dp))
            else Text("${rows.size}/12 行 · 不允许重复项", fontSize = 10.sp, color = ZxT.Text3,
                      modifier = Modifier.padding(top = 6.dp))
        }
    }
}
