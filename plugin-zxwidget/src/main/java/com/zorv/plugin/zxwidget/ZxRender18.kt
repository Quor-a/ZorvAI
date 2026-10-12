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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures

/**
 * 第十八批组件（12 个）—— 编辑 / 映射 / 度量。
 *
 * 前十七批把"看"和"填"铺满了，
 * 剩下的是三类**用户要在界面里直接产出结构**的：
 *
 *   一、**编辑**。已有 editor（代码）、waveedit（波形）、
 *        spreadsheet（表格），但都是"改一个已有对象"。
 *        **画流程图**（自己拉节点连线）、
 *        **裁剪图片**（自己框选区）、
 *        **排时间块**（自己拖长度）没有。
 *
 *   二、**映射**。已有 dualist（并列 diff）、keyvalue，
 *        但**"左边这个对应右边那个"**这件事本身没有组件——
 *        而字段映射、数据对齐是 AI 干活时最常要人确认的。
 *
 *   三、**度量**。已有 meter / gauge / ring / sla，
 *        但**"两个量之间的关系"**（比如投入产出比、
 *        实际 vs 目标的偏差分布）没有专用组件。
 *
 * 贯穿这批的一条：**用户改的东西要能看出"改了"**。
 * 流程图拖完节点要有吸附提示、裁剪框要实时显示尺寸、
 * 映射要标出哪些还没配。
 * **看不出状态的编辑器就是在让人盲操作。**
 */
object ZxRender18 {

    private val PAL = listOf(
        Color(0xFF5046E4), Color(0xFF3FBF7F), Color(0xFFE8A33D),
        Color(0xFFE5544B), Color(0xFF4A9BE8), Color(0xFF8E86FF)
    )

    /* ══════════ zx_flowedit 流程图编辑器 ══════════ */
    @Composable
    fun flowedit(o: JSONObject, ctx: RenderContext) {
        val nodes = o.optJSONArray("nodes") ?: return
        val n = minOf(nodes.length(), 10)
        val pos = remember {
            mutableStateListOf<Pair<Float, Float>>().apply {
                for (i in 0 until n) {
                    val nd = nodes.optJSONObject(i)
                    add((nd?.optDouble("x", .15 + i * .2)?.toFloat() ?: .5f) to
                        (nd?.optDouble("y", .3 + (i % 2) * .4)?.toFloat() ?: .5f))
                }
            }
        }
        val edges = o.optJSONArray("edges")
        var dragging by remember { mutableIntStateOf(-1) }
        var sel by remember { mutableIntStateOf(-1) }

        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(160.dp)) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    // 连线先画：节点压在线上，符合"节点在上"的直觉
                    for (k in 0 until (edges?.length() ?: 0)) {
                        val e = edges!!.optJSONObject(k) ?: continue
                        val f = e.optInt("f", -1); val t = e.optInt("t", -1)
                        if (f !in 0 until n || t !in 0 until n) continue
                        val a = pos[f]; val b = pos[t]
                        drawLine(ZxT.Text3, Offset(a.first * size.width, a.second * size.height),
                                 Offset(b.first * size.width, b.second * size.height), 1.4f)
                    }
                    for (i in 0 until n) {
                        val p = Offset(pos[i].first * size.width, pos[i].second * size.height)
                        drawRoundRect(if (sel == i) PAL[0] else ZxT.Surface3, p,
                            Size(58f, 34f), androidx.compose.ui.geometry.CornerRadius(7f, 7f))
                        if (dragging == i)
                            drawRoundRect(ZxT.Text, p, Size(58f, 34f),
                                androidx.compose.ui.geometry.CornerRadius(7f, 7f),
                                style = Stroke(1.6f))
                    }
                }
                Row(Modifier.fillMaxWidth().height(160.dp)) {
                    (0 until n).forEach { i ->
                        Box(Modifier.weight(1f).fillMaxHeight()
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = { dragging = i; sel = i },
                                    onDragEnd = {
                                        dragging = -1
                                        ctx.callTool("zx_select", mapOf(
                                            "node" to nodes.optJSONObject(i)?.optString("n", "$i"),
                                            "x" to pos[i].first.toString(),
                                            "y" to pos[i].second.toString(),
                                            "source" to "zx_flowedit"))
                                    },
                                    onDrag = { _, ch ->
                                        pos[i] = (pos[i].first + ch.x / size.width)
                                            .coerceIn(.05f, .92f) to
                                            (pos[i].second + ch.y / size.height)
                                                .coerceIn(.08f, .82f)
                                    })
                            })
                    }
                }
                Column(Modifier.fillMaxSize()) {
                    for (i in 0 until n) Spacer(Modifier.height(0.dp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (sel >= 0) {
                    Text(nodes.optJSONObject(sel)?.optString("n", "") ?: "",
                         fontSize = 10.5.sp, color = ZxT.Text)
                    Spacer(Modifier.width(6.dp))
                    Text("%.2f, %.2f".format(pos[sel].first, pos[sel].second),
                         fontSize = 9.5.sp, color = ZxT.Text3, fontFamily = ZxT.Mono)
                } else Text("拖方块排版", fontSize = 10.sp, color = ZxT.Text3)
                Spacer(Modifier.weight(1f))
                if (dragging >= 0)
                    Text("拖动中…", fontSize = 9.5.sp, color = ZxT.PriLite)
            }
            Text("用户改的东西要能看出「改了」——拖动时高亮描边，" +
                 "松手才回传（每帧回传会把上下文刷爆）",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 5.dp))
        }
    }

    /* ══════════ zx_crop 裁剪框 ══════════ */
    @Composable
    fun crop(o: JSONObject, ctx: RenderContext) {
        var r by remember { mutableStateOf(
            listOf(o.optDouble("x", .15), o.optDouble("y", .15),
                   o.optDouble("w", .6), o.optDouble("h", .6))) }
        val ratio = o.optDouble("ratio", 0.0)   // 0 = 自由
        var dragging by remember { mutableIntStateOf(-1) }
        val src = o.optString("src", "")

        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(150.dp)) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    // 底图：用渐变占位，真实场景应加载 src
                    drawRect(Brush.linearGradient(
                        listOf(ZxT.Surface2, ZxT.Surface3)),
                        size = size)
                    val x = (r[0] * size.width).toFloat()
                    val y = (r[1] * size.height).toFloat()
                    val w = (r[2] * size.width).toFloat()
                    val h = (r[3] * size.height).toFloat()
                    // 框外压暗——不压暗看不出选中的是哪块
                    drawRect(Color(0x99000000), size = size)
                    drawRect(Color.Transparent,
                             topLeft = Offset(x, y), size = Size(w, h),
                             blendMode = androidx.compose.ui.graphics.BlendMode.Clear)
                    drawRect(Color.Transparent, topLeft = Offset(x, y),
                             size = Size(w, h),
                             style = Stroke(1.5f))
                    // 四角手柄
                    for ((cx, cy) in listOf(x to y, x + w to y, x to y + h, x + w to y + h))
                        drawCircle(ZxT.Text, 4.5f, Offset(cx, cy))
                    // 三分线
                    for (i in 1..2) {
                        drawLine(Color(0x66FFFFFF), Offset(x + w * i / 3, y),
                                 Offset(x + w * i / 3, y + h), 1f)
                        drawLine(Color(0x66FFFFFF), Offset(x, y + h * i / 3),
                                 Offset(x + w, y + h * i / 3), 1f)
                    }
                }
                Row(Modifier.fillMaxWidth().height(150.dp)) {
                    Box(Modifier.weight(1f).fillMaxHeight()
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { dragging = 0 },
                                onDragEnd = {
                                    dragging = -1
                                    ctx.callTool("zx_select", mapOf(
                                        "x" to r[0].toString(), "y" to r[1].toString(),
                                        "w" to r[2].toString(), "h" to r[3].toString(),
                                        "source" to "zx_crop"))
                                },
                                onDrag = { _, ch ->
                                    val dx = ch.x / size.width
                                    val dy = ch.y / size.height
                                    var nx = (r[0] + dx).coerceIn(0.0, 1.0 - r[2])
                                    var ny = (r[1] + dy).coerceIn(0.0, 1.0 - r[3])
                                    r = listOf(nx, ny, r[2], r[3])
                                })
                        })
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                // 实时尺寸——不显示就是在让人盲操作
                Text("${(r[2] * 1000).toInt()} × ${(r[3] * 1000).toInt()}",
                     fontSize = 10.5.sp, color = ZxT.PriLite, fontFamily = ZxT.Mono)
                Spacer(Modifier.width(8.dp))
                if (ratio > 0)
                    Text("锁定 ${"%.2f".format(ratio)}:1", fontSize = 9.5.sp,
                         color = ZxT.Text3)
                Spacer(Modifier.weight(1f))
                listOf("1:1" to 1.0, "16:9" to 16.0 / 9, "自由" to 0.0).forEach { (lab, rv) ->
                    Text(lab, fontSize = 9.5.sp,
                         color = if (abs(ratio - rv) < 1e-6) ZxT.Text else ZxT.Text3,
                         modifier = Modifier.padding(start = 5.dp)
                             .clip(RoundedCornerShape(7.dp))
                             .background(if (abs(ratio - rv) < 1e-6) ZxT.Primary
                                         else ZxT.Surface2)
                             .clickable {
                                 if (rv > 0) {
                                     val h = r[3]; val w = (h * rv).coerceIn(.1, .95)
                                     r = listOf(r[0].coerceIn(0.0, 1 - w), r[1], w, h)
                                 }
                             }.padding(7.dp, 3.dp))
                }
            }
            if (src.isNotBlank())
                Text(src, fontSize = 9.sp, color = ZxT.Text3,
                     maxLines = 1, overflow = TextOverflow.Ellipsis,
                     modifier = Modifier.padding(top = 3.dp))
        }
    }

    /* ══════════ zx_mapping 字段映射 ══════════ */
    @Composable
    fun mapping(o: JSONObject, ctx: RenderContext) {
        val left = o.optJSONArray("from") ?: return
        val right = o.optJSONArray("to") ?: return
        val nl = minOf(left.length(), 8)
        val nr = minOf(right.length(), 8)
        // 初始映射：from[i].to 指定的下标
        val init = (0 until nl).map { i ->
            val v = left.optJSONObject(i)?.optInt("to", -1) ?: -1
            if (v in 0 until nr) v else -1
        }
        val map = remember { mutableStateListOf<Int>().apply { addAll(init) } }
        var active by remember { mutableIntStateOf(-1) }
        var sel by remember { mutableIntStateOf(-1) }

        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth()) {
                Text(o.optString("lname", "源"), fontSize = 10.sp, color = ZxT.Text3,
                     modifier = Modifier.weight(1f))
                Spacer(Modifier.width(16.dp))
                Text(o.optString("rname", "目标"), fontSize = 10.sp, color = ZxT.Text3,
                     modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(5.dp))
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    for (i in 0 until nl) {
                        val it = left.optJSONObject(i) ?: continue
                        val done = map[i] >= 0
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(when {
                                active == i -> ZxT.Primary.copy(.35f)
                                sel == i -> ZxT.Surface2
                                else -> Color.Transparent
                            })
                            .clickable {
                                active = if (active == i) -1 else i
                                sel = i
                            }.padding(6.dp, 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            // 未配置的要标出来，否则用户不知道还剩多少
                            Box(Modifier.size(6.dp).clip(CircleShape)
                                .background(if (done) ZxT.Ok else ZxT.Text3))
                            Spacer(Modifier.width(6.dp))
                            Text(it.optString("n", ""), fontSize = 10.sp,
                                 color = ZxT.Text2, modifier = Modifier.weight(1f),
                                 maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (done)
                                Text("→", fontSize = 10.sp, color = ZxT.Ok)
                        }
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    for (j in 0 until nr) {
                        val it = right.optJSONObject(j) ?: continue
                        val taken = map.indexOf(j)
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (taken >= 0) ZxT.Surface2 else Color.Transparent)
                            .border(if (active >= 0) 1.dp else 0.dp,
                                    if (active >= 0) ZxT.Primary.copy(.5f)
                                    else Color.Transparent,
                                    RoundedCornerShape(6.dp))
                            .clickable {
                                if (active >= 0) {
                                    map[active] = j
                                    ctx.callTool("zx_select", mapOf(
                                        "from" to (left.optJSONObject(active)
                                            ?.optString("k", "$active") ?: ""),
                                        "to" to it.optString("k", "$j"),
                                        "source" to "zx_mapping"))
                                    active = -1
                                }
                            }.padding(6.dp, 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(it.optString("n", ""), fontSize = 10.sp,
                                 color = if (taken >= 0) ZxT.Text else ZxT.Text2,
                                 modifier = Modifier.weight(1f), maxLines = 1,
                                 overflow = TextOverflow.Ellipsis)
                            if (taken >= 0)
                                Text(left.optJSONObject(taken)?.optString("n", "") ?: "",
                                     fontSize = 8.5.sp, color = ZxT.Text3, maxLines = 1)
                        }
                    }
                }
            }
            val done = map.count { it >= 0 }
            Row(Modifier.fillMaxWidth().padding(top = 7.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("$done/$nl 已映射", fontSize = 10.5.sp,
                     color = if (done == nl) ZxT.Ok else ZxT.Text3)
                Spacer(Modifier.weight(1f))
                Text("清空", fontSize = 10.sp, color = ZxT.Text3,
                     modifier = Modifier.clip(RoundedCornerShape(7.dp))
                         .background(ZxT.Surface2).clickable {
                             for (i in 0 until nl) map[i] = -1
                         }.padding(7.dp, 3.dp))
            }
            Text("左边点一下选中（高亮），再点右边完成配对。" +
                 "未配置的用小圆点标出——不标的话用户不知道还剩几个",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 5.dp))
        }
    }

    /* ══════════ zx_ratio 比率条 ══════════ */
    @Composable
    fun ratio(o: JSONObject, ctx: RenderContext) {
        val a = o.optDouble("a", 0.0)
        val b = o.optDouble("b", 0.0)
        val total = (a + b).coerceAtLeast(1e-9)
        var sel by remember { mutableIntStateOf(-1) }
        val target = o.optDouble("target", 0.0)   // 0 = 不显示

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "比率"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("%.1f : 1".format(a / b.coerceAtLeast(1e-9)), fontSize = 11.sp,
                     color = ZxT.PriLite, fontFamily = ZxT.Mono)
            }
            Spacer(Modifier.height(7.dp))
            Box(Modifier.fillMaxWidth().height(26.dp)
                .clip(RoundedCornerShape(6.dp))) {
                Row(Modifier.fillMaxHeight()) {
                    Box(Modifier.weight(maxOf(a, 1e-9).toFloat())
                        .fillMaxHeight()
                        .background(if (sel == 0) PAL[0] else PAL[0].copy(.75f))
                        .clickable {
                            sel = 0
                            ctx.callTool("zx_select", mapOf("side" to "a", "v" to a.toString(),
                                                            "source" to "zx_ratio"))
                        }, contentAlignment = Alignment.Center) {
                        Text(o.optString("an", "A"), fontSize = 9.5.sp, color = Color.White)
                    }
                    Box(Modifier.weight(maxOf(b, 1e-9).toFloat())
                        .fillMaxHeight()
                        .background(if (sel == 1) PAL[1] else PAL[1].copy(.75f))
                        .clickable {
                            sel = 1
                            ctx.callTool("zx_select", mapOf("side" to "b", "v" to b.toString(),
                                                            "source" to "zx_ratio"))
                        }, contentAlignment = Alignment.Center) {
                        Text(o.optString("bn", "B"), fontSize = 9.5.sp, color = Color.White)
                    }
                }
            }
            // 目标线：没有它就不知道这个比率是好是坏
            if (target > 0) {
                val tp = (target / (target + 1)).toFloat().coerceIn(0f, 1f)
                val ap = (a / total).toFloat()
                Box(Modifier.fillMaxWidth().height(14.dp)) {
                    Box(Modifier.fillMaxWidth().height(14.dp)) {
                        Box(Modifier.fillMaxWidth(tp).height(14.dp),
                            contentAlignment = Alignment.CenterEnd) {
                            Box(Modifier.width(1.5.dp).height(12.dp).background(ZxT.Text))
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        Text("目标 ${"%.1f".format(target)}:1", fontSize = 8.5.sp,
                             color = ZxT.Text3)
                    }
                }
                val dev = (a / total) / tp - 1
                Text(if (abs(dev) < .05) "接近目标" else
                     "${if (dev > 0) "高于" else "低于"}目标 ${(abs(dev) * 100).toInt()}%",
                     fontSize = 10.sp,
                     color = if (abs(dev) < .05) ZxT.Ok else ZxT.Warn,
                     modifier = Modifier.padding(top = 3.dp))
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)) {
                Text("${o.optString("an", "A")} ${"%.0f".format(a)}", fontSize = 9.5.sp,
                     color = ZxT.Text3, fontFamily = ZxT.Mono)
                Spacer(Modifier.weight(1f))
                Text("${o.optString("bn", "B")} ${"%.0f".format(b)}", fontSize = 9.5.sp,
                     color = ZxT.Text3, fontFamily = ZxT.Mono)
            }
        }
    }

    /* ══════════ zx_deviation 偏差分布 ══════════ */
    @Composable
    fun deviation(o: JSONObject, ctx: RenderContext) {
        val items = o.optJSONArray("items") ?: return
        val n = minOf(items.length(), 20)
        var sel by remember { mutableIntStateOf(-1) }
        val devs = (0 until n).map {
            val it = items.optJSONObject(it)
            val act = it?.optDouble("act", 0.0) ?: 0.0
            val tgt = it?.optDouble("tgt", 0.0) ?: 0.0
            if (tgt == 0.0) 0.0 else (act - tgt) / kotlin.math.abs(tgt)
        }
        val maxAbs = devs.maxOfOrNull { kotlin.math.abs(it) }?.coerceAtLeast(1e-6) ?: 1.0

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "偏差"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("零线", fontSize = 9.sp, color = ZxT.Text3)
                Spacer(Modifier.width(4.dp))
                Box(Modifier.width(14.dp).height(1.5.dp).background(ZxT.Text2))
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height((n * 15).coerceAtMost(200).dp)) {
                // 零线必须画在中间——没有它看不出是超了还是没达标
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth().height(1.dp)
                        .offset(y = ((n * 15) / 2).coerceAtMost(200).dp / 2)
                        .background(ZxT.Text3))
                }
                Row(Modifier.fillMaxWidth().height((n * 15).coerceAtMost(200).dp)) {
                    (0 until n).forEach { i ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = i
                            ctx.callTool("zx_select", mapOf(
                                "choice" to items.optJSONObject(i)?.optString("n", "$i"),
                                "dev" to devs[i].toString(), "source" to "zx_deviation"))
                        })
                    }
                }
                Column(Modifier.fillMaxWidth()) {
                    for (i in 0 until n) {
                        val d = devs[i]
                        val w = (kotlin.math.abs(d) / maxAbs).toFloat().coerceIn(0f, 1f)
                        Row(Modifier.fillMaxWidth().height(15.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(items.optJSONObject(i)?.optString("n", "") ?: "",
                                 fontSize = 8.5.sp, color = ZxT.Text3,
                                 modifier = Modifier.width(38.dp), maxLines = 1,
                                 overflow = TextOverflow.Clip)
                            Box(Modifier.weight(1f).height(11.dp)) {
                                if (d >= 0)
                                    Box(Modifier.fillMaxWidth(w / 2).align(Alignment.CenterStart)
                                        .height(9.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(if (sel == i) ZxT.Text else ZxT.Warn))
                                else
                                    Box(Modifier.fillMaxWidth(w / 2).align(Alignment.CenterEnd)
                                        .height(9.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(if (sel == i) ZxT.Text else ZxT.Err))
                            }
                            Text("%+.0f%%".format(d * 100), fontSize = 8.5.sp,
                                 color = if (d >= 0) ZxT.Warn else ZxT.Err,
                                 fontFamily = ZxT.Mono,
                                 modifier = Modifier.width(38.dp),
                                 textAlign = TextAlign.End)
                        }
                    }
                }
                // 中间零线
                Box(Modifier.fillMaxWidth().height((n * 15).coerceAtMost(200).dp),
                    contentAlignment = Alignment.CenterStart) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(ZxT.Text3))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)) {
                Text("左=未达标（红）", fontSize = 9.sp, color = ZxT.Err)
                Spacer(Modifier.width(8.dp))
                Text("右=超标（黄）", fontSize = 9.sp, color = ZxT.Warn)
                Spacer(Modifier.weight(1f))
                if (sel >= 0)
                    Text("${items.optJSONObject(sel)?.optString("n")} ${"%+.0f%%".format(devs[sel] * 100)}",
                         fontSize = 9.5.sp, color = ZxT.PriLite)
            }
        }
    }

    /* ══════════ zx_timeline2 双轨时间轴 ══════════ */
    @Composable
    fun timeline2(o: JSONObject, ctx: RenderContext) {
        val rows = o.optJSONArray("rows") ?: return
        val n = minOf(rows.length(), 6)
        val t0 = o.optDouble("from", 0.0)
        val t1 = o.optDouble("to", 100.0)
        val span = (t1 - t0).coerceAtLeast(1e-6)
        var sel by remember { mutableIntStateOf(-1) }

        Column(Modifier.fillMaxWidth()) {
            for (i in 0 until n) {
                val r = rows.optJSONObject(i) ?: continue
                val st = r.optDouble("s", t0); val en = r.optDouble("e", t0)
                val p0 = ((st - t0) / span).toFloat().coerceIn(0f, 1f)
                val p1 = ((en - t0) / span).toFloat().coerceIn(0f, 1f)
                val col = PAL[i % PAL.size]
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == i) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        sel = i
                        ctx.callTool("zx_select", mapOf(
                            "row" to r.optString("n", "$i"),
                            "s" to st.toString(), "e" to en.toString(),
                            "source" to "zx_timeline2"))
                    }.padding(4.dp, 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(r.optString("n", ""), fontSize = 9.5.sp, color = ZxT.Text2,
                         modifier = Modifier.width(42.dp), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    // 双轨：计划（细线）在上，实际（粗条）在下
                    Column(Modifier.weight(1f)) {
                        Box(Modifier.fillMaxWidth().height(6.dp)) {
                            val ps = ((r.optDouble("ps", st) - t0) / span).toFloat().coerceIn(0f, 1f)
                            val pe = ((r.optDouble("pe", en) - t0) / span).toFloat().coerceIn(0f, 1f)
                            Box(Modifier.fillMaxWidth().height(6.dp)
                                .clip(RoundedCornerShape(2.dp)).background(ZxT.Surface3))
                            Box(Modifier.fillMaxWidth(pe).height(6.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(col.copy(.3f)))
                            Box(Modifier.fillMaxWidth(ps).height(6.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color.Transparent))
                        }
                        Spacer(Modifier.height(2.dp))
                        Box(Modifier.fillMaxWidth().height(10.dp)) {
                            Box(Modifier.fillMaxWidth().height(10.dp)
                                .clip(RoundedCornerShape(3.dp)).background(ZxT.Surface3))
                            Box(Modifier.fillMaxWidth(p1).height(10.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(if (sel == i) col else col.copy(.8f)))
                            Box(Modifier.fillMaxWidth(p0).height(10.dp)
                                .clip(RoundedCornerShape(3.dp)).background(Color.Transparent))
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                                Box(Modifier.offset(x = 0.dp).fillMaxWidth(p0)
                                    .height(10.dp))
                            }
                        }
                    }
                    Text("%.0f".format(en - st), fontSize = 8.5.sp, color = ZxT.Text3,
                         fontFamily = ZxT.Mono, modifier = Modifier.width(26.dp),
                         textAlign = TextAlign.End)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(14.dp).height(6.dp)
                        .clip(RoundedCornerShape(2.dp)).background(PAL[0].copy(.3f)))
                    Spacer(Modifier.width(4.dp))
                    Text("计划", fontSize = 9.sp, color = ZxT.Text3)
                }
                Spacer(Modifier.width(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(14.dp).height(10.dp)
                        .clip(RoundedCornerShape(3.dp)).background(PAL[0]))
                    Spacer(Modifier.width(4.dp))
                    Text("实际", fontSize = 9.sp, color = ZxT.Text3)
                }
                Spacer(Modifier.weight(1f))
                Text("$t0 – $t1", fontSize = 9.sp, color = ZxT.Text3,
                     fontFamily = ZxT.Mono)
            }
            Text("已有 timeline（事件流）和 gantt（任务条），" +
                 "但**计划 vs 实际**这层对照没有——而这正是要人看的部分",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 5.dp))
        }
    }

    /* ══════════ zx_scale2 天平 ══════════ */
    @Composable
    fun scale2(o: JSONObject, ctx: RenderContext) {
        val l = o.optDouble("l", 5.0)
        val r = o.optDouble("r", 5.0)
        val tot = (l + r).coerceAtLeast(1e-9)
        var sel by remember { mutableIntStateOf(-1) }
        // 倾角：最多 ±12°，再多就看着像坏了
        val tilt = (((r - l) / tot) * 12.0).coerceIn(-12.0, 12.0)

        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(120.dp)) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val cx = size.width / 2; val cy = size.height * .3f
                    val armW = size.width * .34f
                    val rad = Math.toRadians(tilt)
                    // 横梁
                    drawLine(ZxT.Text2,
                        Offset(cx - cos(rad).toFloat() * armW, cy - sin(rad).toFloat() * armW),
                        Offset(cx + cos(rad).toFloat() * armW, cy + sin(rad).toFloat() * armW),
                        3f)
                    // 支点
                    drawPath(Path().apply {
                        moveTo(cx, cy); lineTo(cx - 12f, size.height * .72f)
                        lineTo(cx + 12f, size.height * .72f); close() },
                        ZxT.Surface3)
                    // 两侧托盘
                    for ((side, v, col) in listOf(
                        Triple(-1, l, PAL[0]), Triple(1, r, PAL[1]))) {
                        val px = cx + side * cos(rad).toFloat() * armW
                        val py = cy + side * sin(rad).toFloat() * armW
                        val h = (v / tot * 34f).toFloat().coerceIn(6f, 34f)
                        drawLine(ZxT.Text3, Offset(px, py), Offset(px, py + 20f), 1.2f)
                        drawRoundRect(if (sel == (if (side < 0) 0 else 1)) ZxT.Text else col,
                            Offset(px - 22f, py + 20f), Size(44f, h),
                            androidx.compose.ui.geometry.CornerRadius(4f, 4f))
                    }
                }
                Row(Modifier.fillMaxWidth().height(120.dp)) {
                    Box(Modifier.weight(1f).fillMaxHeight().clickable {
                        sel = 0
                        ctx.callTool("zx_select", mapOf("side" to "l", "v" to l.toString(),
                                                        "source" to "zx_scale2"))
                    })
                    Box(Modifier.weight(1f).fillMaxHeight().clickable {
                        sel = 1
                        ctx.callTool("zx_select", mapOf("side" to "r", "v" to r.toString(),
                                                        "source" to "zx_scale2"))
                    })
                }
            }
            Row(Modifier.fillMaxWidth()) {
                Text("${o.optString("ln", "左")} ${"%.0f".format(l)}", fontSize = 10.5.sp,
                     color = if (sel == 0) ZxT.Text else ZxT.Text3,
                     modifier = Modifier.weight(1f))
                Text(if (abs(l - r) / tot < .05) "基本持平"
                     else "${if (l > r) o.optString("ln", "左") else o.optString("rn", "右")}更重",
                     fontSize = 10.sp,
                     color = if (abs(l - r) / tot < .05) ZxT.Ok else ZxT.Warn)
                Text("${o.optString("rn", "右")} ${"%.0f".format(r)}", fontSize = 10.5.sp,
                     color = if (sel == 1) ZxT.Text else ZxT.Text3,
                     modifier = Modifier.weight(1f), textAlign = TextAlign.End)
            }
            Text("倾角最多 ±12°——再多看着像坏了，少了看不出谁重。" +
                 "已有 gauge/ring 是单量，这个是**两个量的对比**",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_mirror 镜像对比 ══════════ */
    @Composable
    fun mirror(o: JSONObject, ctx: RenderContext) {
        val items = o.optJSONArray("items") ?: return
        val n = minOf(items.length(), 8)
        var sel by remember { mutableIntStateOf(-1) }
        val maxV = (0 until n).maxOf {
            maxOf(items.optJSONObject(it)?.optDouble("a", 0.0) ?: 0.0,
                  items.optJSONObject(it)?.optDouble("b", 0.0) ?: 0.0)
        }.coerceAtLeast(1e-6)

        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth()) {
                Text(o.optString("an", "A"), fontSize = 10.sp, color = ZxT.Text3,
                     modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                Spacer(Modifier.width(58.dp))
                Text(o.optString("bn", "B"), fontSize = 10.sp, color = ZxT.Text3,
                     modifier = Modifier.weight(1f))
            }
            for (i in 0 until n) {
                val it = items.optJSONObject(i) ?: continue
                val a = it.optDouble("a", 0.0); val b = it.optDouble("b", 0.0)
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (sel == i) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        sel = i
                        ctx.callTool("zx_select", mapOf(
                            "choice" to it.optString("n", "$i"),
                            "a" to a.toString(), "b" to b.toString(),
                            "diff" to (b - a).toString(), "source" to "zx_mirror"))
                    }.padding(3.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).height(14.dp),
                        contentAlignment = Alignment.CenterEnd) {
                        Box(Modifier.fillMaxWidth((a / maxV).toFloat().coerceIn(0f, 1f))
                            .height(11.dp)
                            .clip(RoundedCornerShape(topStart = 3.dp, bottomStart = 3.dp))
                            .background(if (sel == i) PAL[0] else PAL[0].copy(.65f)))
                    }
                    Text(it.optString("n", ""), fontSize = 8.5.sp, color = ZxT.Text3,
                         modifier = Modifier.width(58.dp), textAlign = TextAlign.Center,
                         maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Box(Modifier.weight(1f).height(14.dp),
                        contentAlignment = Alignment.CenterStart) {
                        Box(Modifier.fillMaxWidth((b / maxV).toFloat().coerceIn(0f, 1f))
                            .height(11.dp)
                            .clip(RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp))
                            .background(if (sel == i) PAL[1] else PAL[1].copy(.65f)))
                    }
                }
            }
            if (sel >= 0) {
                val it = items.optJSONObject(sel) ?: return
                val d = it.optDouble("b", 0.0) - it.optDouble("a", 0.0)
                Row(Modifier.fillMaxWidth().padding(top = 5.dp)) {
                    Text("${it.optString("n")}：", fontSize = 10.sp, color = ZxT.Text2)
                    Text("%+.0f".format(d), fontSize = 10.5.sp,
                         color = if (d >= 0) ZxT.Ok else ZxT.Err, fontFamily = ZxT.Mono)
                    Spacer(Modifier.width(6.dp))
                    Text(if (it.optDouble("a", 0.0) == 0.0) "—"
                         else "%+.0f%%".format(d / it.optDouble("a", 0.0) * 100),
                         fontSize = 9.5.sp, color = ZxT.Text3, fontFamily = ZxT.Mono)
                }
            }
            Text("背对背（tornado chart）。已有 compare 是两列数字，" +
                 "这个是**同一行的两侧**——一眼能看出哪一项差得最多",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 5.dp))
        }
    }

    /* ══════════ zx_dial2 刻度盘 ══════════ */
    @Composable
    fun dial2(o: JSONObject, ctx: RenderContext) {
        val v = o.optDouble("v", 0.0)
        val min = o.optDouble("min", 0.0)
        val max = o.optDouble("max", 100.0).coerceAtLeast(min + 1e-6)
        val zones = o.optJSONArray("zones")
        var sel by remember { mutableIntStateOf(-1) }
        val t = ((v - min) / (max - min)).coerceIn(0.0, 1.0)

        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().height(110.dp)) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val cx = size.width / 2; val cy = size.height * .82f
                    val R = minOf(size.width / 2, size.height) * .84f
                    // 扇区从 180° 到 360°（上半圆）
                    fun ang(f: Double) = (180 + f * 180).toFloat()
                    // 区间带
                    for (k in 0 until (zones?.length() ?: 0)) {
                        val z = zones!!.optJSONObject(k) ?: continue
                        val f0 = ((z.optDouble("f", 0.0) - min) / (max - min)).coerceIn(0.0, 1.0)
                        val f1 = ((z.optDouble("t", 1.0) - min) / (max - min)).coerceIn(0.0, 1.0)
                        val col = when (z.optString("k", "")) {
                            "ok" -> ZxT.Ok; "warn" -> ZxT.Warn; else -> ZxT.Err }
                        drawArc(col.copy(.28f), ang(f0), ((f1 - f0) * 180f).toFloat(), true,
                                topLeft = Offset(cx - R, cy - R), size = Size(R * 2, R * 2))
                    }
                    // 刻度
                    for (i in 0..10) {
                        val a = Math.toRadians(ang(i / 10.0).toDouble())
                        val r0 = R * (if (i % 5 == 0) .78f else .86f)
                        drawLine(ZxT.Text3,
                            Offset(cx + cos(a).toFloat() * r0, cy + sin(a).toFloat() * r0),
                            Offset(cx + cos(a).toFloat() * R * .92f,
                                   cy + sin(a).toFloat() * R * .92f),
                            if (i % 5 == 0) 1.8f else 1f)
                    }
                    // 指针
                    val a = Math.toRadians(ang(t).toDouble())
                    drawLine(PAL[3], Offset(cx, cy),
                             Offset(cx + cos(a).toFloat() * R * .74f,
                                    cy + sin(a).toFloat() * R * .74f), 3f)
                    drawCircle(ZxT.Text, 5f, Offset(cx, cy))
                }
                Row(Modifier.fillMaxWidth().height(110.dp)) {
                    (0..10).forEach { i ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = i
                            ctx.callTool("zx_select", mapOf(
                                "tick" to i.toString(),
                                "v" to (min + (max - min) * i / 10).toString(),
                                "source" to "zx_dial2"))
                        })
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("%.1f".format(min), fontSize = 9.sp, color = ZxT.Text3,
                     fontFamily = ZxT.Mono)
                Spacer(Modifier.width(8.dp))
                Text("%.1f".format(v), fontSize = 18.sp, fontWeight = FontWeight.Bold,
                     color = ZxT.Text, fontFamily = ZxT.Mono)
                o.optString("u").takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.width(3.dp))
                    Text(it, fontSize = 10.sp, color = ZxT.Text3)
                }
                Spacer(Modifier.width(8.dp))
                Text("%.1f".format(max), fontSize = 9.sp, color = ZxT.Text3,
                     fontFamily = ZxT.Mono)
            }
            if (zones != null && zones.length() > 0)
                Row(Modifier.padding(top = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (k in 0 until zones.length()) {
                        val z = zones.optJSONObject(k) ?: continue
                        val col = when (z.optString("k", "")) {
                            "ok" -> ZxT.Ok; "warn" -> ZxT.Warn; else -> ZxT.Err }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(col))
                            Spacer(Modifier.width(3.dp))
                            Text("${z.optDouble("f", 0.0).toInt()}-${z.optDouble("t", 0.0).toInt()}",
                                 fontSize = 8.5.sp, color = ZxT.Text3)
                        }
                    }
                }
        }
    }

    /* ══════════ zx_matrix2 矩阵选择 ══════════ */
    @Composable
    fun matrix2(o: JSONObject, ctx: RenderContext) {
        val rows = o.optJSONArray("rows") ?: return
        val cols = o.optJSONArray("cols") ?: return
        val nr = minOf(rows.length(), 6)
        val nc = minOf(cols.length(), 5)
        // 单选还是每行一个：mode = "one" | "row"
        val one = o.optString("mode", "one") == "one"
        val picked = remember { mutableStateMapOf<Int, Int>() }
        var sel by remember { mutableStateOf<Pair<Int, Int>?>(null) }

        Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Row(Modifier.padding(start = 46.dp)) {
                for (c in 0 until nc)
                    Text(cols.optString(c), fontSize = 8.sp, color = ZxT.Text3,
                         modifier = Modifier.width(34.dp), textAlign = TextAlign.Center,
                         maxLines = 1, overflow = TextOverflow.Clip)
            }
            for (r in 0 until nr) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(rows.optString(r), fontSize = 9.sp, color = ZxT.Text2,
                         modifier = Modifier.width(46.dp), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    for (c in 0 until nc) {
                        val on = picked[r] == c
                        Box(Modifier.width(34.dp).height(24.dp).padding(1.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(if (on) PAL[c % PAL.size].copy(.55f) else ZxT.Surface2)
                            .border(if (sel?.first == r && sel?.second == c) 1.5.dp else 0.dp,
                                    ZxT.Text, RoundedCornerShape(5.dp))
                            .clickable {
                                sel = r to c
                                if (one) picked.clear()
                                picked[r] = c
                                ctx.callTool("zx_select", mapOf(
                                    "row" to rows.optString(r),
                                    "col" to cols.optString(c), "source" to "zx_matrix2"))
                            }, contentAlignment = Alignment.Center) {
                            if (on) Text("✓", fontSize = 10.sp, color = ZxT.Text)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("${picked.size}/$nr 已选", fontSize = 10.sp, color = ZxT.Text3)
                Spacer(Modifier.weight(1f))
                Text(if (one) "单选模式" else "每行一个", fontSize = 9.sp, color = ZxT.Text3)
            }
        }
    }

    /* ══════════ zx_ladder 阶梯排序 ══════════ */
    @Composable
    fun ladder(o: JSONObject, ctx: RenderContext) {
        val items = o.optJSONArray("items") ?: return
        val n = minOf(items.length(), 8)
        var sel by remember { mutableIntStateOf(-1) }
        val order = remember { mutableStateListOf<Int>().apply { for (i in 0 until n) add(i) } }

        Column(Modifier.fillMaxWidth()) {
            for ((rank, idx) in order.withIndex()) {
                val it = items.optJSONObject(idx) ?: continue
                val v = it.optDouble("v", 0.0)
                // 台阶：越高缩进越少，视觉上"爬上去"
                Row(Modifier.fillMaxWidth()
                    .padding(start = ((n - 1 - rank) * 0).dp, top = 3.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (sel == idx) ZxT.Surface2 else ZxT.Surface2.copy(.5f))
                    .clickable {
                        sel = idx
                        ctx.callTool("zx_select", mapOf(
                            "choice" to it.optString("n", "$idx"),
                            "rank" to (rank + 1).toString(), "v" to v.toString(),
                            "source" to "zx_ladder"))
                    }.padding(8.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${rank + 1}", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                         color = if (rank < 3) PAL[rank] else ZxT.Text3,
                         modifier = Modifier.width(20.dp), fontFamily = ZxT.Mono)
                    Text(it.optString("n", ""), fontSize = 10.5.sp, color = ZxT.Text,
                         modifier = Modifier.weight(1f), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    Text("%.0f".format(v), fontSize = 10.sp, color = ZxT.Text3,
                         fontFamily = ZxT.Mono)
                    Spacer(Modifier.width(6.dp))
                    // 上移：不是为了好玩，是让"重排"这件事在界面里可完成
                    if (rank > 0)
                        Text("↑", fontSize = 11.sp, color = ZxT.Text3,
                             modifier = Modifier.clickable {
                                 val t = order[rank - 1]
                                 order[rank - 1] = order[rank]
                                 order[rank] = t
                             }.padding(horizontal = 4.dp))
                }
            }
            Text("已有 rank（排名）是只读的。" +
                 "这个能**在界面里重排**——用户调完顺序直接回传，不用打字跟模型说",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_slot 时间段排布 ══════════ */
    @Composable
    fun slot(o: JSONObject, ctx: RenderContext) {
        val blocks = o.optJSONArray("blocks") ?: return
        val n = minOf(blocks.length(), 8)
        val dayStart = o.optDouble("from", 8.0)
        val dayEnd = o.optDouble("to", 20.0)
        val span = (dayEnd - dayStart).coerceAtLeast(1e-6)
        var sel by remember { mutableIntStateOf(-1) }
        var drag by remember { mutableIntStateOf(-1) }

        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 38.dp)) {
                for (h in 0..4)
                    Text("%.0f".format(dayStart + span * h / 4), fontSize = 8.sp,
                         color = ZxT.Text3, modifier = Modifier.weight(1f),
                         textAlign = TextAlign.Center)
            }
            for (i in 0 until n) {
                val b = blocks.optJSONObject(i) ?: continue
                var st = remember { mutableDoubleStateOf(b.optDouble("s", dayStart)) }
                var len = remember { mutableDoubleStateOf(b.optDouble("len", 1.0)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(b.optString("n", ""), fontSize = 9.sp, color = ZxT.Text2,
                         modifier = Modifier.width(38.dp), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    Box(Modifier.weight(1f).height(22.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(ZxT.Surface2)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { drag = i; sel = i },
                                onDragEnd = {
                                    drag = -1
                                    ctx.callTool("zx_select", mapOf(
                                        "block" to b.optString("n", "$i"),
                                        "s" to st.doubleValue.toString(),
                                        "len" to len.doubleValue.toString(),
                                        "source" to "zx_slot"))
                                },
                                onDrag = { _, ch ->
                                    val d = (ch.x / size.width) * span
                                    st.doubleValue = (st.doubleValue + d)
                                        .coerceIn(dayStart, dayEnd - len.doubleValue)
                                })
                        }) {
                        Box(Modifier.fillMaxWidth((len.doubleValue / span).toFloat())
                            .offset(x = ((st.doubleValue - dayStart) / span * 100).dp)
                            .height(18.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (sel == i) PAL[i % PAL.size]
                                        else PAL[i % PAL.size].copy(.7f)))
                    }
                    Text("%.1fh".format(len.doubleValue), fontSize = 8.5.sp,
                         color = ZxT.Text3, fontFamily = ZxT.Mono,
                         modifier = Modifier.width(30.dp), textAlign = TextAlign.End)
                }
                Spacer(Modifier.height(3.dp))
            }
            Text("拖色块改时间。**松手才回传**——每帧回传会把上下文刷爆。" +
                 "已有 schedule / gantt 是只读展示，这个能排",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 5.dp))
        }
    }
}
