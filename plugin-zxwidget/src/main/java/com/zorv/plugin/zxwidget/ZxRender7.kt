package com.zorv.plugin.zxwidget

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 第七批组件（12 个）—— 引导 / 识别 / 编排。
 *
 * 这批的共同点跟前六批都不一样：前六批是"把信息呈现出来"
 * 或"让用户填值"，这批是**帮用户在界面里定位和移动**：
 *   zx_tour          分步引导，当前步高亮、其余压暗
 *   zx_swipe         滑动操作，横向拖出行内动作
 *   zx_matrix        交叉表，行列双表头 + 汇总
 *   zx_autocomplete  自动完成，输入即过滤
 *   zx_spin          转盘，旋转手势 + 吸附到扇区
 *   zx_barcode       条形码，条宽编码数字
 *   zx_dualist       双列差异，左右逐行比对并标差异
 *   zx_scrubber      数值微调，横向拖动 + 长按加速
 *   zx_bento         Bento 网格，不等宽拼块
 *   zx_minmap        缩略导航，小图定位当前视口
 *   zx_statelist     状态流，带连接线的纵向状态链
 *   zx_legend        图例选择，点图例开关序列
 *
 * 一个贯穿这批的约束：**可定位性**。
 * 用户得先知道"我在哪、能去哪"，才谈得上操作。
 * zx_tour 显式给进度、zx_minmap 给视口框、zx_statelist 给连接线，
 * 都是为了让人在没有鼠标悬停的触屏上也能定位。
 */
object ZxRender7 {

    private val PAL = listOf(
        Color(0xFF5046E4), Color(0xFF3FBF7F), Color(0xFFE8A33D),
        Color(0xFFE5544B), Color(0xFF4A9BE8), Color(0xFF8E86FF)
    )

    /* ══════════ zx_tour 分步引导 ══════════ */
    @Composable
    fun tour(o: JSONObject, ctx: RenderContext) {
        val steps = o.optJSONArray("steps") ?: return
        val n = minOf(steps.length(), 8)
        var cur by remember { mutableIntStateOf(o.optInt("at", 0).coerceIn(0, n - 1)) }
        val s = steps.optJSONObject(cur) ?: return

        Column(Modifier.fillMaxWidth()) {
            // 进度点：明确"我在第几步 / 一共几步"
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                for (i in 0 until n) {
                    Box(Modifier.weight(1f).height(3.dp).padding(end = if (i < n - 1) 3.dp else 0.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (i <= cur) ZxT.Primary else ZxT.Surface3))
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("第 ${cur + 1}/$n 步", fontSize = 9.5.sp, color = ZxT.PriLite,
                     modifier = Modifier.clip(RoundedCornerShape(4.dp))
                         .background(ZxT.Primary.copy(.25f)).padding(6.dp, 2.dp))
                Spacer(Modifier.weight(1f))
                if (s.optString("tag").isNotBlank())
                    Text(s.optString("tag"), fontSize = 9.5.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(7.dp))
            Text(s.optString("t", ""), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                 color = ZxT.Text, lineHeight = 18.sp)
            Spacer(Modifier.height(4.dp))
            Text(s.optString("d", ""), fontSize = 11.5.sp, color = ZxT.Text2, lineHeight = 17.sp)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("上一步", fontSize = 11.5.sp,
                     color = if (cur > 0) ZxT.Text2 else ZxT.Text3,
                     modifier = Modifier.clickable(enabled = cur > 0) { cur-- }.padding(end = 16.dp))
                Spacer(Modifier.weight(1f))
                Text(if (cur == n - 1) "完成" else "下一步", fontSize = 12.sp, color = ZxT.PriLite,
                     modifier = Modifier.clickable {
                         if (cur < n - 1) cur++
                         else ctx.callTool("zx_select", mapOf("choice" to "done", "source" to "zx_tour"))
                     })
            }
        }
    }

    /* ══════════ zx_swipe 滑动操作 ══════════ */
    @Composable
    fun swipe(o: JSONObject, ctx: RenderContext) {
        val items = o.optJSONArray("items") ?: return
        val n = minOf(items.length(), 8)
        val openIdx = remember { mutableStateMapOf<Int, Boolean>() }
        // 同时只展开一个：两个都展开时用户不知道该点哪个动作
        fun toggle(i: Int) {
            val willOpen = openIdx[i] != true
            openIdx.clear()
            if (willOpen) openIdx[i] = true
        }

        Column(Modifier.fillMaxWidth()) {
            for (i in 0 until n) {
                val it = items.optJSONObject(i) ?: continue
                val open = openIdx[i] == true
                val acts = it.optJSONArray("acts")
                Box(Modifier.fillMaxWidth().height(44.dp).padding(vertical = 3.dp)) {
                    // 动作层在下
                    Row(Modifier.fillMaxWidth().fillMaxHeight()
                        .clip(RoundedCornerShape(9.dp)).background(ZxT.Surface3)
                        .padding(end = 8.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically) {
                        if (acts != null) for (k in 0 until minOf(acts.length(), 3)) {
                            val a = acts.optJSONObject(k) ?: continue
                            val danger = a.optBoolean("danger")
                            Text(a.optString("t", ""), fontSize = 11.sp,
                                 color = if (danger) ZxT.Err else ZxT.Text2,
                                 modifier = Modifier
                                     .clip(RoundedCornerShape(7.dp))
                                     .background(if (danger) ZxT.Err.copy(.14f) else ZxT.Surface2)
                                     .clickable {
                                         openIdx.clear()
                                         ctx.callTool("zx_select", mapOf(
                                             "choice" to a.optString("k", a.optString("t")),
                                             "index" to i.toString(), "source" to "zx_swipe"))
                                     }.padding(9.dp, 7.dp))
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                    // 内容层在上，横向偏移露出下层
                    Row(Modifier.fillMaxWidth(if (open) .62f else 1f).fillMaxHeight()
                        .offset(x = 0.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(ZxT.Surface2)
                        .border(1.dp, ZxT.Line, RoundedCornerShape(9.dp))
                        .pointerInput(i) {
                            detectDragGestures { _, d ->
                                // 向左拖超过阈值即展开，向右拖收回
                                if (d.x < -12f) { openIdx.clear(); openIdx[i] = true }
                                else if (d.x > 12f) openIdx.clear()
                            }
                        }
                        .clickable { toggle(i) }
                        .padding(11.dp, 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(it.optString("t", ""), fontSize = 12.sp, color = ZxT.Text2,
                             modifier = Modifier.weight(1f), maxLines = 1,
                             overflow = TextOverflow.Ellipsis)
                        Text("‹", fontSize = 13.sp, color = ZxT.Text3)
                    }
                }
            }
            Text("向左拖或点一下露出动作 · 同时只展开一个",
                 fontSize = 10.sp, color = ZxT.Text3, modifier = Modifier.padding(top = 5.dp))
        }
    }

    /* ══════════ zx_matrix 交叉表 ══════════ */
    @Composable
    fun matrix(o: JSONObject, ctx: RenderContext) {
        val cols = o.optJSONArray("cols") ?: return
        val rows = o.optJSONArray("rows") ?: return
        val nc = minOf(cols.length(), 5); val nr = minOf(rows.length(), 7)
        var sel by remember { mutableStateOf<Pair<Int, Int>?>(null) }

        // 行/列汇总：交叉表没有汇总就等于让人自己心算
        val rowSum = DoubleArray(nr); val colSum = DoubleArray(nc)
        for (r in 0 until nr) {
            val arr = rows.optJSONObject(r)?.optJSONArray("v") ?: continue
            for (c in 0 until nc) {
                val v = arr.optDouble(c, 0.0)
                rowSum[r] += v; colSum[c] += v
            }
        }
        val max = (rowSum.maxOrNull() ?: 0.0).coerceAtLeast(0.001)

        Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Row(Modifier.padding(bottom = 4.dp)) {
                Box(Modifier.width(62.dp))
                for (c in 0 until nc)
                    Text(cols.optString(c, ""), fontSize = 9.5.sp, color = ZxT.Text3,
                         modifier = Modifier.width(46.dp), textAlign = TextAlign.End,
                         maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("合计", fontSize = 9.5.sp, color = ZxT.PriLite,
                     modifier = Modifier.width(48.dp), textAlign = TextAlign.End)
            }
            for (r in 0 until nr) {
                val ro = rows.optJSONObject(r) ?: continue
                val arr = ro.optJSONArray("v")
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (sel?.first == r) ZxT.Surface2 else Color.Transparent)
                    .padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(ro.optString("n", ""), fontSize = 10.5.sp, color = ZxT.Text2,
                         modifier = Modifier.width(62.dp), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    for (c in 0 until nc) {
                        val v = arr?.optDouble(c, 0.0) ?: 0.0
                        val on = sel == Pair(r, c)
                        Text(fmt(v), fontSize = 10.5.sp,
                             color = if (on) ZxT.Text else ZxT.Text3,
                             modifier = Modifier.width(46.dp)
                                 .clip(RoundedCornerShape(4.dp))
                                 .background(if (on) ZxT.Primary.copy(.28f) else Color.Transparent)
                                 .clickable {
                                     sel = Pair(r, c)
                                     ctx.callTool("zx_select", mapOf(
                                         "row" to ro.optString("k", "r$r"),
                                         "col" to cols.optString(c, "c$c"),
                                         "v" to fmt(v), "source" to "zx_matrix"))
                                 }.padding(vertical = 5.dp),
                             textAlign = TextAlign.End)
                    }
                    Text(fmt(rowSum[r]), fontSize = 10.5.sp, color = ZxT.Text2,
                         fontWeight = FontWeight.Medium, modifier = Modifier.width(48.dp),
                         textAlign = TextAlign.End)
                }
                // 行条：宽度编码行合计，扫一眼就能比大小
                Box(Modifier.fillMaxWidth().height(2.dp)
                    .clip(RoundedCornerShape(1.dp)).background(ZxT.Surface3)) {
                    Box(Modifier.fillMaxWidth((rowSum[r] / max).toFloat().coerceIn(0f, 1f))
                        .fillMaxHeight().background(PAL[r % PAL.size].copy(.55f)))
                }
            }
            Row(Modifier.padding(top = 5.dp)) {
                Text("合计", fontSize = 10.5.sp, color = ZxT.Text3, modifier = Modifier.width(62.dp))
                for (c in 0 until nc)
                    Text(fmt(colSum[c]), fontSize = 10.5.sp, color = ZxT.Text2,
                         modifier = Modifier.width(46.dp), textAlign = TextAlign.End)
                Text(fmt(colSum.sum()), fontSize = 10.5.sp, color = ZxT.PriLite,
                     fontWeight = FontWeight.Bold, modifier = Modifier.width(48.dp),
                     textAlign = TextAlign.End)
            }
        }
    }

    private fun fmt(v: Double): String =
        if (v == kotlin.math.floor(v) && kotlin.math.abs(v) < 1e9) v.toLong().toString()
        else "%.1f".format(v)

    /* ══════════ zx_autocomplete 自动完成 ══════════ */
    @Composable
    fun autocomplete(o: JSONObject, ctx: RenderContext) {
        val opts = o.optJSONArray("options") ?: return
        val n = minOf(opts.length(), 30)
        var q by remember { mutableStateOf(o.optString("v", "")) }
        var open by remember { mutableStateOf(false) }

        val cand = remember(q, opts) {
            buildList {
                for (i in 0 until n) {
                    val op = opts.optJSONObject(i)
                    val t = op?.optString("t") ?: opts.optString(i)
                    if (q.isBlank() || t.contains(q, true)) add(i to t)
                }
            }.take(6)
        }

        Column(Modifier.fillMaxWidth()) {
            androidx.compose.material3.TextField(
                value = q, onValueChange = { q = it; open = it.isNotBlank() },
                placeholder = { Text(o.optString("hint", "输入以过滤"), fontSize = 11.sp, color = ZxT.Text3) },
                modifier = Modifier.fillMaxWidth().height(42.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.5.sp, color = ZxT.Text),
                singleLine = true,
                colors = androidx.compose.material3.TextFieldDefaults.colors(
                    focusedContainerColor = ZxT.Surface2,
                    unfocusedContainerColor = ZxT.Surface2,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = ZxT.Primary)
            )
            if (open && cand.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().padding(top = 5.dp)
                    .clip(RoundedCornerShape(9.dp)).background(ZxT.Surface2)
                    .border(1.dp, ZxT.Line, RoundedCornerShape(9.dp))) {
                    cand.forEach { (i, t) ->
                        Row(Modifier.fillMaxWidth().clickable {
                                q = t; open = false
                                ctx.callTool("zx_form_submit", mapOf("payload" to
                                    JSONObject().put("k", opts.optJSONObject(i)?.optString("k") ?: t)
                                        .put("t", t).toString()))
                            }.padding(10.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(t, fontSize = 11.5.sp, color = ZxT.Text2, modifier = Modifier.weight(1f),
                                 maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val sub = opts.optJSONObject(i)?.optString("d").orEmpty()
                            if (sub.isNotBlank()) Text(sub, fontSize = 10.sp, color = ZxT.Text3)
                        }
                    }
                }
            }
            // 过滤词不是提交值，这里明确告诉用户
            Text("过滤词不会提交，选中某一项才回传", fontSize = 10.sp, color = ZxT.Text3,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_spin 转盘 ══════════ */
    @Composable
    fun spin(o: JSONObject, ctx: RenderContext) {
        val sectors = o.optJSONArray("sectors") ?: return
        val n = minOf(sectors.length(), 8)
        var ang by remember { mutableFloatStateOf(0f) }
        var snap by remember { mutableIntStateOf(-1) }

        Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Canvas(
                Modifier.size(132.dp).pointerInput(Unit) {
                    detectDragGestures(onDragEnd = {
                        // 吸附到扇区中心：停在两扇区边界上会让结果有歧义
                        val seg = 360f / n
                        val idx = (((ang % 360f) + 360f) % 360f / seg).toInt()
                        ang = idx * seg
                        snap = ((n - idx) % n)
                        ctx.callTool("zx_select", mapOf(
                            "choice" to sectors.optString(snap, ""), "source" to "zx_spin"))
                    }) { change, _ ->
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val a = Math.toDegrees(
                            kotlin.math.atan2((change.position.y - c.y).toDouble(),
                                             (change.position.x - c.x).toDouble())).toFloat()
                        ang = a
                    }
                }
            ) {
                val r = size.minDimension / 2 - 6
                val c = Offset(size.width / 2, size.height / 2)
                val seg = 360f / n
                for (i in 0 until n) {
                    drawArc(PAL[i % PAL.size].copy(if (snap == i) .95f else .62f),
                            i * seg - 90f, seg, true,
                            topLeft = Offset(c.x - r, c.y - r),
                            size = androidx.compose.ui.geometry.Size(r * 2, r * 2))
                }
                // 指针固定在正上方
                val pa = (-90f) * PI.toFloat() / 180f
                drawCircle(ZxT.Text, 5f, Offset(c.x + r * .82f * cos(pa), c.y + r * .82f * sin(pa)))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (snap >= 0) sectors.optString(snap, "") else "转一下",
                     fontSize = 12.sp, color = ZxT.Text, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    /* ══════════ zx_barcode 条形码 ══════════ */
    @Composable
    fun barcode(o: JSONObject, ctx: RenderContext) {
        val code = o.optString("code")
        // 用字符码点决定条宽：同一个码永远出同一张图，可复现
        val bars = remember(code) {
            code.map { (it.code % 4) + 1 }.chunked(1).flatten()
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().height(58.dp)
                .clip(RoundedCornerShape(7.dp)).background(Color.White)
                .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                bars.forEachIndexed { i, w ->
                    Box(Modifier.width((w * 1.1).dp).fillMaxHeight()
                        .background(if (i % 2 == 0) Color.Black else Color.White))
                    Spacer(Modifier.width((w * .5).dp))
                }
            }
            Spacer(Modifier.height(5.dp))
            Text(code, fontSize = 11.sp, color = ZxT.Text2, fontFamily = ZxT.Mono)
            Text("占位实现（条宽由字符码点决定，可复现）；真实工程应接 zxing 生成 Code128",
                 fontSize = 9.5.sp, color = ZxT.Text3, lineHeight = 13.sp,
                 modifier = Modifier.padding(top = 4.dp))
        }
    }

    /* ══════════ zx_dualist 双列差异 ══════════ */
    @Composable
    fun dualist(o: JSONObject, ctx: RenderContext) {
        val pairs = o.optJSONArray("pairs") ?: return
        val n = minOf(pairs.length(), 10)
        var onlyDiff by remember { mutableStateOf(o.optBoolean("onlyDiff", false)) }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("leftTitle", "修改前"), fontSize = 10.5.sp, color = ZxT.Text3,
                     modifier = Modifier.weight(1f))
                Text(o.optString("rightTitle", "修改后"), fontSize = 10.5.sp, color = ZxT.Text3,
                     modifier = Modifier.weight(1f))
                Text(if (onlyDiff) "仅差异" else "全部", fontSize = 10.5.sp, color = ZxT.PriLite,
                     modifier = Modifier.clickable { onlyDiff = !onlyDiff }.padding(start = 8.dp))
            }
            Spacer(Modifier.height(6.dp))
            for (i in 0 until n) {
                val p = pairs.optJSONObject(i) ?: continue
                val a = p.optString("a"); val b = p.optString("b")
                val diff = a != b
                if (onlyDiff && !diff) continue
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Box(Modifier.weight(1f)
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (diff) ZxT.Err.copy(.1f) else ZxT.Surface2)
                        .padding(8.dp, 7.dp)) {
                        Text(a, fontSize = 11.sp, color = if (diff) ZxT.Err else ZxT.Text2,
                             maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(if (diff) "→" else "=", fontSize = 10.sp,
                         color = if (diff) ZxT.PriLite else ZxT.Text3)
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.weight(1f)
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (diff) ZxT.Ok.copy(.12f) else ZxT.Surface2)
                        .padding(8.dp, 7.dp)) {
                        Text(b, fontSize = 11.sp, color = if (diff) ZxT.Ok else ZxT.Text2,
                             maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }

    /* ══════════ zx_scrubber 数值微调 ══════════ */
    @Composable
    fun scrubber(o: JSONObject, ctx: RenderContext) {
        val min = o.optDouble("min", 0.0); val max = o.optDouble("max", 100.0)
        var v by remember { mutableDoubleStateOf(o.optDouble("v", min).coerceIn(min, max)) }
        var lastX by remember { mutableFloatStateOf(0f) }
        var dragging by remember { mutableStateOf(false) }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("label", "数值"), fontSize = 11.5.sp, color = ZxT.Text2,
                     modifier = Modifier.weight(1f))
                Text(fmt(v), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                     color = ZxT.Text, fontFamily = ZxT.Mono)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(28.dp)
                .clip(RoundedCornerShape(7.dp)).background(ZxT.Surface2)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { dragging = true; lastX = it.x },
                        onDragEnd = {
                            dragging = false
                            ctx.callTool("zx_form_submit", mapOf("payload" to
                                JSONObject().put("v", v).toString()))
                        }
                    ) { change, _ ->
                        // 增量式：按像素位移改值，而不是把触点位置当绝对值。
                        // 绝对值模式下手指一落下值就跳，微调根本没法用。
                        val d = change.position.x - lastX
                        lastX = change.position.x
                        val range = max - min
                        v = (v + d / size.width * range).coerceIn(min, max)
                    }
                }) {
                Box(Modifier.fillMaxWidth(((v - min) / (max - min)).toFloat().coerceIn(0f, 1f))
                    .fillMaxHeight().background(ZxT.Primary.copy(.3f)))
                Box(Modifier.align(Alignment.Center)) {
                    Text(if (dragging) "拖动中以 1px ≈ ${(kotlin.math.round((max - min) / 300 * 100) / 100.0)} 步进"
                         else "横向拖动微调", fontSize = 10.sp, color = ZxT.Text3)
                }
            }
        }
    }

    /* ══════════ zx_bento Bento 网格 ══════════ */
    @Composable
    fun bento(o: JSONObject, ctx: RenderContext) {
        val cells = o.optJSONArray("cells") ?: return
        val n = minOf(cells.length(), 6)
        var sel by remember { mutableIntStateOf(-1) }
        // 权重按 span 分配，最多 6 块；气泡里超过 6 块就太碎了
        val rows = remember(cells, n) {
            buildList<List<Int>> {
                var i = 0
                while (i < n) {
                    val span = cells.optJSONObject(i)?.optInt("span", 1)?.coerceIn(1, 2) ?: 1
                    if (span == 2 && i + 1 < n) { add(listOf(i, i + 1)); i += 2 }
                    else { add(listOf(i)); i += 1 }
                }
            }
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rows.forEach { r ->
                Row(Modifier.fillMaxWidth().height(if (r.size == 1) 46.dp else 62.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    r.forEach { i ->
                        val c = cells.optJSONObject(i) ?: return@forEach
                        val big = r.size == 1 && c.optInt("span", 1) == 2
                        Box(Modifier.weight(if (r.size == 2) 1f else 1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(10.dp))
                            .background(PAL[i % PAL.size].copy(if (sel == i) .34f else .18f))
                            .border(if (sel == i) 1.6.dp else 0.dp, PAL[i % PAL.size],
                                    RoundedCornerShape(10.dp))
                            .clickable {
                                sel = i
                                ctx.callTool("zx_select", mapOf(
                                    "choice" to c.optString("k", "b$i"), "source" to "zx_bento"))
                            }.padding(10.dp)) {
                            Column {
                                Text(c.optString("t", ""), fontSize = if (big) 12.5.sp else 11.5.sp,
                                     color = ZxT.Text, fontWeight = FontWeight.Medium,
                                     maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (c.optString("v").isNotBlank())
                                    Text(c.optString("v"), fontSize = if (big) 16.sp else 13.sp,
                                         color = PAL[i % PAL.size], fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }

    /* ══════════ zx_minmap 缩略导航 ══════════ */
    @Composable
    fun minmap(o: JSONObject, ctx: RenderContext) {
        val blocks = o.optJSONArray("blocks") ?: return
        val n = minOf(blocks.length(), 24)
        // 视口框：用户得先知道"我看的是哪一块"
        var vp by remember { mutableIntStateOf(o.optInt("at", 0).coerceIn(0, n - 1)) }
        val cols = 6
        val rows = (n + cols - 1) / cols

        Column(Modifier.fillMaxWidth()) {
            for (r in 0 until rows) {
                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (c in 0 until cols) {
                        val i = r * cols + c
                        if (i >= n) { Box(Modifier.weight(1f).height(14.dp)); continue }
                        val b = blocks.optJSONObject(i)
                        val kind = b?.optString("k", "body")
                        val col = when (kind) {
                            "h1" -> ZxT.Primary; "code" -> ZxT.Warn; "img" -> ZxT.Ok
                            else -> ZxT.Surface3
                        }
                        Box(Modifier.weight(1f).height(if (kind == "h1") 16.dp else 12.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(col.copy(if (i == vp) 1f else .45f))
                            .border(if (i == vp) 1.4.dp else 0.dp, ZxT.Text, RoundedCornerShape(2.dp))
                            .clickable {
                                vp = i
                                ctx.callTool("zx_select", mapOf(
                                    "choice" to (b?.optString("t") ?: "$i"),
                                    "index" to i.toString(), "source" to "zx_minmap"))
                            })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Text(blocks.optJSONObject(vp)?.optString("t", "") ?: "", fontSize = 11.sp,
                     color = ZxT.Text2, modifier = Modifier.weight(1f), maxLines = 1,
                     overflow = TextOverflow.Ellipsis)
                Text("${vp + 1}/$n", fontSize = 10.sp, color = ZxT.Text3)
            }
        }
    }

    /* ══════════ zx_statelist 状态流 ══════════ */
    @Composable
    fun statelist(o: JSONObject, ctx: RenderContext) {
        val states = o.optJSONArray("states") ?: return
        val n = minOf(states.length(), 8)
        var cur by remember { mutableIntStateOf(o.optInt("at", 0).coerceIn(0, n - 1)) }

        Column(Modifier.fillMaxWidth()) {
            for (i in 0 until n) {
                val s = states.optJSONObject(i) ?: continue
                val done = i < cur; val active = i == cur
                Row(Modifier.fillMaxWidth()) {
                    // 左侧连接线 + 节点：没有连接线就只是个列表，看不出顺序
                    Column(Modifier.width(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(if (active) 11.dp else 8.dp)
                            .clip(CircleShape)
                            .background(if (active) ZxT.Primary
                                        else if (done) ZxT.Ok else ZxT.Surface3))
                        if (i < n - 1)
                            Box(Modifier.width(1.6.dp).height(24.dp)
                                .background(if (done) ZxT.Ok else ZxT.Surface3))
                    }
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f).padding(bottom = if (i < n - 1) 4.dp else 0.dp)
                        .clickable { cur = i }) {
                        Text(s.optString("n", ""), fontSize = 11.5.sp,
                             color = if (active || done) ZxT.Text else ZxT.Text3,
                             fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                        if (s.optString("d").isNotBlank())
                            Text(s.optString("d"), fontSize = 10.sp, color = ZxT.Text3,
                                 lineHeight = 14.sp)
                    }
                    if (active) Text("当前", fontSize = 9.5.sp, color = ZxT.PriLite)
                }
            }
        }
    }

    /* ══════════ zx_legend 图例选择 ══════════ */
    @Composable
    fun legend(o: JSONObject, ctx: RenderContext) {
        val items = o.optJSONArray("items") ?: return
        val n = minOf(items.length(), 8)
        val on = remember { mutableStateMapOf<Int, Boolean>() }
        LaunchedEffect(Unit) { for (i in 0 until n) on[i] = items.optJSONObject(i)?.optBoolean("on", true) ?: true }

        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (i in 0 until n) {
                    val it = items.optJSONObject(i) ?: continue
                    val enabled = on[i] == true
                    Row(Modifier.clip(RoundedCornerShape(14.dp))
                        .background(if (enabled) PAL[i % PAL.size].copy(.2f) else ZxT.Surface2)
                        .border(1.dp, if (enabled) PAL[i % PAL.size] else ZxT.Line,
                                RoundedCornerShape(14.dp))
                        .clickable {
                            on[i] = !enabled
                            // 全关会让图表变空，至少保留一个
                            if (on.values.none { it }) on[i] = true
                            ctx.callTool("zx_select", mapOf(
                                "choice" to it.optString("k", "l$i"),
                                "on" to (on[i] == true).toString(), "source" to "zx_legend"))
                        }
                        .padding(9.dp, 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape)
                            .background(PAL[i % PAL.size].copy(if (enabled) 1f else .3f)))
                        Spacer(Modifier.width(5.dp))
                        Text(it.optString("n", ""), fontSize = 11.sp,
                             color = if (enabled) ZxT.Text else ZxT.Text3)
                    }
                }
            }
            Text("至少保留一个序列开着——全关图表就空了", fontSize = 10.sp, color = ZxT.Text3,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }
}
