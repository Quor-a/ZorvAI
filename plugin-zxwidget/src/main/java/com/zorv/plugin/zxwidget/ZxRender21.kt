package com.zorv.plugin.zxwidget

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
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
import kotlin.math.sqrt

/**
 * 第二十一批组件（12 个）—— 对齐 / 差异 / 编排。
 *
 * 前二十批把"看什么、填什么、改什么、凭什么、多密"铺完了。
 * 剩下的是三类**日常用得最多、但一直靠通用组件硬凑**的：
 *
 *   一、**多序列对齐**。已有 line / slope / stream 都假设
 *       各序列**共用同一个横轴**。但"三个版本各跑了一轮压测，\n*       时长不同"这种场景，直接画会失真。
 *       `zx_align`（时间对齐：拉伸到同一轴 + 标出偏差）。
 *
 *   二、**差异可视化**。已有 diff（逐行）、baseline（前后），
 *       但**"两份结构化数据差在哪"**（JSON/配置对比）
 *       和**"谁对谁错"**（逐字段三方比对）没有。
 *
 *   三、**编排**。已有 tabs / accordion / grid / master，
 *       但**"同一块地方按条件摆不同内容"**（条件容器）
 *       和**"上下两段能拖动分配高度"**（可分割面板）没有。
 *
 * 贯穿这批的一条：**先对齐，再比较**。
 * 没对齐的两条曲线放在一起比，比出来的差异是假的——
 * 这是最容易得出错误结论的一类图。
 *
 * 另一个共同点：这批有一半是**容器或半容器**，
 * 所以要处理**子内容的命中区域**——
 * 子内容自己要能点，容器又不能把点击吃掉。
 */
object ZxRender21 {

    private val PAL = listOf(
        Color(0xFF5046E4), Color(0xFF3FBF7F), Color(0xFFE8A33D),
        Color(0xFFE5544B), Color(0xFF4A9BE8), Color(0xFF8E86FF)
    )

    /* ══════════ zx_align 多序列时间对齐 ══════════ */
    @Composable
    fun align(o: JSONObject, ctx: RenderContext) {
        val series = o.optJSONArray("series") ?: return
        val n = minOf(series.length(), 4)
        var sel by remember { mutableIntStateOf(-1) }
        var mode by remember { mutableStateOf(o.optString("mode", "stretch")) }
        val lens = (0 until n).map { series.optJSONObject(it)?.optJSONArray("v")?.length() ?: 0 }
        val maxLen = lens.maxOrNull()?.coerceAtLeast(2) ?: 2
        val mn = (0 until n).flatMap { i ->
            val arr = series.optJSONObject(i)?.optJSONArray("v")
            (0 until (arr?.length() ?: 0)).map { arr?.optDouble(it, 0.0) ?: 0.0 }
        }.minOrNull() ?: 0.0
        val mx = (0 until n).flatMap { i ->
            val arr = series.optJSONObject(i)?.optJSONArray("v")
            (0 until (arr?.length() ?: 0)).map { arr?.optDouble(it, 0.0) ?: 0.0 }
        }.maxOrNull() ?: 1.0
        val span = (mx - mn).coerceAtLeast(1e-9)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "序列对齐"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                // 长度不同必须提示，否则比的差异是假的
                if (lens.distinct().size > 1)
                    Text("长度不一", fontSize = 9.sp, color = ZxT.Warn,
                         modifier = Modifier.clip(RoundedCornerShape(8.dp))
                             .background(ZxT.Warn.copy(.16f)).padding(6.dp, 2.dp))
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(140.dp)) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height
                    drawLine(ZxT.Surface3, Offset(0f, H - 10f), Offset(W, H - 10f), 1f)
                    for (i in 0 until n) {
                        val arr = series.optJSONObject(i)?.optJSONArray("v") ?: continue
                        val len = arr.length()
                        if (len == 0) continue
                        val on = sel == i
                        val path = Path()
                        for (k in 0 until len) {
                            // stretch：拉到同一横轴；raw：按原始长度画
                            val t = if (mode == "stretch")
                                k.toFloat() / (len - 1).coerceAtLeast(1)
                            else k.toFloat() / (maxLen - 1).coerceAtLeast(1)
                            val x = 6f + t * (W - 12f)
                            val v = arr.optDouble(k, 0.0)
                            val y = H - 10f - ((v - mn) / span).toFloat() * (H - 20f)
                            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(path, if (on) ZxT.Text else PAL[i % PAL.size],
                                 style = Stroke(if (on) 2.2f else 1.4f))
                        // 起止点：看得出"谁先结束"
                        val first = arr.optDouble(0, 0.0)
                        val last = arr.optDouble(len - 1, 0.0)
                        val y0 = H - 10f - ((first - mn) / span).toFloat() * (H - 20f)
                        val y1 = H - 10f - ((last - mn) / span).toFloat() * (H - 20f)
                        val xEnd = if (mode == "stretch") W - 6f
                        else 6f + (len - 1f) / (maxLen - 1).coerceAtLeast(1) * (W - 12f)
                        drawCircle(PAL[i % PAL.size], 3f, Offset(6f, y0))
                        drawCircle(PAL[i % PAL.size], 3f, Offset(xEnd, y1))
                    }
                }
                Row(Modifier.fillMaxWidth().height(140.dp)) {
                    (0 until n).forEach { i ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = i
                            ctx.callTool("zx_select", mapOf(
                                "series" to (series.optJSONObject(i)?.optString("n", "$i") ?: ""),
                                "len" to lens[i].toString(),
                                "source" to "zx_align"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf("stretch" to "拉伸对齐", "raw" to "原始长度").forEach { (m, lab) ->
                    val on = mode == m
                    Text(lab, fontSize = 9.5.sp,
                         color = if (on) Color.White else ZxT.Text3,
                         modifier = Modifier.clip(RoundedCornerShape(9.dp))
                             .background(if (on) PAL[0] else ZxT.Surface2)
                             .clickable { mode = m }.padding(9.dp, 3.dp))
                }
                Spacer(Modifier.weight(1f))
                Text("长度 ${lens.joinToString("/")}", fontSize = 9.sp, color = ZxT.Text3,
                     fontFamily = ZxT.Mono)
            }
            Text("已有 line / slope 都假设各序列共用同一横轴。" +
                 "这个显式处理长度不一——**没对齐就比，比出来的差异是假的**。" +
                 "raw 模式下能看出谁先结束",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_jsondiff 结构化差异 ══════════ */
    @Composable
    fun jsondiff(o: JSONObject, ctx: RenderContext) {
        val rows = o.optJSONArray("rows") ?: return
        val n = minOf(rows.length(), 14)
        var sel by remember { mutableIntStateOf(-1) }
        var only by remember { mutableStateOf(o.optString("only", "all")) }
        val kinds = mapOf("add" to ZxT.Ok, "del" to ZxT.Err, "mod" to ZxT.Warn,
                          "same" to ZxT.Text3)
        val labels = mapOf("add" to "新增", "del" to "删除", "mod" to "修改",
                           "same" to "相同")
        val cnt = (0 until n).map { rows.optJSONObject(it)?.optString("k", "same") ?: "same" }
            .let { list ->
                mapOf("add" to list.count { it == "add" }, "del" to list.count { it == "del" },
                      "mod" to list.count { it == "mod" })
            }
        val shown = (0 until n).filter { only == "all" || cnt[only]?.let {
            rows.optJSONObject(it)?.optString("k") == only } == true }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "配置对比"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("all" to "全部", "mod" to "改 ${cnt["mod"]}",
                           "add" to "增 ${cnt["add"]}", "del" to "删 ${cnt["del"]}")
                        .forEach { (m, lab) ->
                            val on = only == m
                            Text(lab, fontSize = 9.sp,
                                 color = if (on) Color.White else ZxT.Text3,
                                 modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                     .background(if (on) PAL[0] else ZxT.Surface2)
                                     .clickable { only = m }.padding(7.dp, 2.dp))
                        }
                }
            }
            Spacer(Modifier.height(6.dp))
            if (shown.isEmpty())
                Text("没有该类型的变更", fontSize = 10.sp, color = ZxT.Text3,
                     modifier = Modifier.padding(vertical = 10.dp))
            shown.forEach { i ->
                val r = rows.optJSONObject(i) ?: return@forEach
                val k = r.optString("k", "same")
                val col = kinds[k] ?: ZxT.Text3
                val on = sel == i
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (on) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        sel = i
                        ctx.callTool("zx_select", mapOf(
                            "path" to r.optString("p", ""), "kind" to k,
                            "source" to "zx_jsondiff"))
                    }.padding(6.dp, 4.dp)) {
                    Text(labels[k] ?: k, fontSize = 8.sp, color = col,
                         modifier = Modifier.width(26.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.optString("p", ""), fontSize = 9.5.sp, color = ZxT.Text2,
                             fontFamily = ZxT.Mono, maxLines = 1,
                             overflow = TextOverflow.Ellipsis)
                        if (k == "mod") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(r.optString("a", ""), fontSize = 9.sp,
                                     color = ZxT.Err, fontFamily = ZxT.Mono,
                                     modifier = Modifier.weight(1f), maxLines = 1,
                                     overflow = TextOverflow.Ellipsis)
                                Text("→", fontSize = 9.sp, color = ZxT.Text3,
                                     modifier = Modifier.padding(horizontal = 4.dp))
                                Text(r.optString("b", ""), fontSize = 9.sp,
                                     color = ZxT.Ok, fontFamily = ZxT.Mono,
                                     modifier = Modifier.weight(1f), maxLines = 1,
                                     overflow = TextOverflow.Ellipsis)
                            }
                        } else {
                            Text(if (k == "add") r.optString("b", "") else r.optString("a", ""),
                                 fontSize = 9.sp, color = col, fontFamily = ZxT.Mono,
                                 maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Text("已有 diff 是逐**行**比文本、baseline 是前后比数值，" +
                 "这个比**结构化路径**——改的是哪个字段、从什么值改到什么值。" +
                 "逐行 diff 在配置里会因缩进变化报一堆无关差异",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_threeway 三方比对 ══════════ */
    @Composable
    fun threeway(o: JSONObject, ctx: RenderContext) {
        val rows = o.optJSONArray("rows") ?: return
        val n = minOf(rows.length(), 12)
        var sel by remember { mutableIntStateOf(-1) }
        val sides = listOf(o.optString("a", "A"), o.optString("b", "B"),
                           o.optString("base", "基准"))
        // 冲突判定：三者都不同 = 真冲突，无法自动合并
        val conflict = (0 until n).map { i ->
            val r = rows.optJSONObject(i) ?: return@map false
            val a = r.optString("a"); val b = r.optString("b"); val base = r.optString("base")
            a != b && a != base && b != base
        }
        val nConflict = conflict.count { it }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "三方比对"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text(if (nConflict > 0) "$nConflict 处冲突" else "可自动合并",
                     fontSize = 9.5.sp,
                     color = if (nConflict > 0) ZxT.Err else ZxT.Ok)
            }
            Spacer(Modifier.height(5.dp))
            Row(Modifier.fillMaxWidth().padding(bottom = 3.dp)) {
                Text("字段", fontSize = 8.sp, color = ZxT.Text3,
                     modifier = Modifier.width(58.dp))
                listOf(sides[2], sides[0], sides[1]).forEach {
                    Text(it, fontSize = 8.sp, color = ZxT.Text3,
                         modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                         maxLines = 1, overflow = TextOverflow.Clip)
                }
            }
            for (i in 0 until n) {
                val r = rows.optJSONObject(i) ?: continue
                val on = sel == i
                val cf = conflict[i]
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (on) ZxT.Surface2 else
                        (if (cf) ZxT.Err.copy(.09f) else Color.Transparent))
                    .clickable {
                        sel = i
                        ctx.callTool("zx_select", mapOf(
                            "field" to r.optString("f", ""),
                            "conflict" to cf.toString(), "source" to "zx_threeway"))
                    }.padding(5.dp, 3.dp)) {
                    Text(r.optString("f", ""), fontSize = 9.sp, color = ZxT.Text2,
                         modifier = Modifier.width(58.dp), maxLines = 1,
                         overflow = TextOverflow.Clip)
                    // 基准 / A / B 三列：改过的标色
                    val base = r.optString("base"); val a = r.optString("a"); val b = r.optString("b")
                    listOf(
                        base to (base == base),
                        a to (a != base),
                        b to (b != base)
                    ).forEachIndexed { idx, (v, changed) ->
                        val col = when {
                            idx == 0 -> ZxT.Text3
                            conflict[i] && changed -> ZxT.Err
                            changed -> if (idx == 1) ZxT.PriLite else ZxT.Ok
                            else -> ZxT.Text3
                        }
                        Text(v, fontSize = 9.sp, color = col,
                             modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                             fontFamily = ZxT.Mono, maxLines = 1,
                             overflow = TextOverflow.Clip)
                    }
                }
            }
            if (sel >= 0 && conflict[sel])
                Text("这一行三者都不同——自动合并会静默丢掉一边的改动，必须让人判。",
                     fontSize = 9.5.sp, color = ZxT.Err, lineHeight = 13.sp,
                     modifier = Modifier.padding(top = 4.dp))
            Text("已有 confront（两条证据对质）是给人判主张，" +
                 "这个是给**合并**用：基准 + 两边。三者都不同才是真冲突——" +
                 "只跟基准不同、两边相同，是可以自动合并的",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_sankey2 能量桑基（带损耗） ══════════ */
    @Composable
    fun sankey2(o: JSONObject, ctx: RenderContext) {
        val nodes = o.optJSONArray("nodes") ?: return
        val nn = minOf(nodes.length(), 6)
        val flows = o.optJSONArray("flows") ?: return
        val nf = minOf(flows.length(), 8)
        var sel by remember { mutableIntStateOf(-1) }
        // 每层节点纵向均分
        val layers = (0 until nn).map { nodes.optJSONObject(it)?.optInt("l", 0) ?: 0 }
        val maxLayer = layers.maxOrNull() ?: 0
        val perLayer = (0..maxLayer).map { L -> (0 until nn).filter { layers[it] == L } }
        val total = (0 until nf).sumOf { flows.optJSONObject(it)?.optDouble("v", 0.0) ?: 0.0 }
            .coerceAtLeast(1e-9)
        val loss = o.optDouble("loss", 0.0)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "能量流"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                if (loss > 0)
                    Text("损耗 %.0f".format(loss), fontSize = 9.5.sp, color = ZxT.Err)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(150.dp)) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height
                    fun nodeY(i: Int): Float {
                        val L = layers[i]
                        val arr = perLayer[L]
                        val idx = arr.indexOf(i)
                        val cnt = arr.size
                        val h = H / cnt
                        return h * (idx + .5f)
                    }
                    fun nodeX(L: Int) = 30f + (W - 60f) * L / maxLayer.coerceAtLeast(1)
                    // 流量带
                    for (k in 0 until nf) {
                        val f = flows.optJSONObject(k) ?: continue
                        val s = f.optInt("f", -1); val t = f.optInt("t", -1)
                        if (s !in 0 until nn || t !in 0 until nn) continue
                        val v = f.optDouble("v", 0.0)
                        val w = (v / total).toFloat() * (H * .5f)
                        val y0 = nodeY(s); val y1 = nodeY(t)
                        val x0 = nodeX(layers[s]); val x1 = nodeX(layers[t])
                        val on = sel == k
                        val col = if (f.optBoolean("loss", false)) ZxT.Err
                        else PAL[k % PAL.size]
                        drawPath(Path().apply {
                            moveTo(x0, y0 - w / 2); lineTo(x1, y1 - w / 2)
                            lineTo(x1, y1 + w / 2); lineTo(x0, y0 + w / 2); close() },
                            col.copy(if (on) .78f else .34f))
                    }
                    // 节点
                    for (i in 0 until nn) {
                        val x = nodeX(layers[i]); val y = nodeY(i)
                        val arr = perLayer[layers[i]]
                        val h = H / arr.size
                        drawRect(PAL[i % PAL.size], Offset(x - 4f, y - h * .34f),
                                 Size(8f, h * .68f))
                    }
                }
                Row(Modifier.fillMaxWidth().height(150.dp)) {
                    (0 until nf).forEach { k ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = k
                            ctx.callTool("zx_select", mapOf(
                                "flow" to (flows.optJSONObject(k)?.optString("n", "") ?: ""),
                                "v" to (flows.optJSONObject(k)?.optDouble("v", 0.0)
                                    ?: 0.0).toString(),
                                "source" to "zx_sankey2"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                perLayer.forEachIndexed { L, arr ->
                    Column(Modifier.weight(1f)) {
                        arr.forEach { i ->
                            Text(nodes.optJSONObject(i)?.optString("n", "") ?: "",
                                 fontSize = 8.sp, color = ZxT.Text3, maxLines = 1,
                                 overflow = TextOverflow.Clip)
                        }
                    }
                }
            }
            Text("已有 sankey 只画分流，这个**显式表达损耗**——" +
                 "红色带是丢掉的部分。不画出来，用户会以为进出相等",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_split 可分割面板 ══════════ */
    @Composable
    fun split(o: JSONObject, ctx: RenderContext) {
        val dir = o.optString("dir", "v")
        var ratio by remember { mutableFloatStateOf(o.optDouble("ratio", .5).toFloat()) }
        val minR = o.optDouble("min", .2).toFloat()
        val maxR = o.optDouble("max", .8).toFloat()
        val top = o.optJSONObject("a") ?: JSONObject()
        val bottom = o.optJSONObject("b") ?: JSONObject()
        var dragging by remember { mutableStateOf(false) }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "分割面板"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("%.0f%%".format(ratio * 100), fontSize = 9.5.sp, color = ZxT.Text3,
                     fontFamily = ZxT.Mono)
            }
            Spacer(Modifier.height(6.dp))
            val isV = dir == "v"
            val main: @Composable () -> Unit = {
                if (isV) Column(Modifier.fillMaxWidth().height(170.dp)) {
                    Box(Modifier.fillMaxWidth().weight(ratio)) {
                        ZxNest.render(top, ctx, 0)
                    }
                    // 拖动手柄
                    Box(Modifier.fillMaxWidth().height(14.dp)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { dragging = true },
                                onDragEnd = { dragging = false },
                                onDragCancel = { dragging = false }
                            ) { change, drag ->
                                change.consume()
                                ratio = (ratio + drag.y / 170f).coerceIn(minR, maxR)
                            }
                        }) {
                        Box(Modifier.align(Alignment.Center).width(28.dp).height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (dragging) ZxT.PriLite else ZxT.Text3))
                    }
                    Box(Modifier.fillMaxWidth().weight(1f - ratio)) {
                        ZxNest.render(bottom, ctx, 0)
                    }
                } else Row(Modifier.fillMaxWidth().height(170.dp)) {
                    Box(Modifier.weight(ratio).fillMaxHeight()) {
                        ZxNest.render(top, ctx, 0)
                    }
                    Box(Modifier.width(14.dp).fillMaxHeight()
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { dragging = true },
                                onDragEnd = { dragging = false },
                                onDragCancel = { dragging = false }
                            ) { change, drag ->
                                change.consume()
                                ratio = (ratio + drag.x / 330f).coerceIn(minR, maxR)
                            }
                        }) {
                        Box(Modifier.align(Alignment.Center).width(3.dp).height(28.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (dragging) ZxT.PriLite else ZxT.Text3))
                    }
                    Box(Modifier.weight(1f - ratio).fillMaxHeight()) {
                        ZxNest.render(bottom, ctx, 0)
                    }
                }
            }
            main()
            Row(Modifier.fillMaxWidth().padding(top = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf("v" to "上下", "h" to "左右").forEach { (d, lab) ->
                    val on = dir == d
                    Text(lab, fontSize = 9.5.sp,
                         color = if (on) Color.White else ZxT.Text3,
                         modifier = Modifier.clip(RoundedCornerShape(9.dp))
                             .background(if (on) PAL[0] else ZxT.Surface2)
                             .clickable {
                                 ctx.callTool("zx_select", mapOf("dir" to d,
                                                                 "source" to "zx_split"))
                             }.padding(9.dp, 3.dp))
                }
                Spacer(Modifier.weight(1f))
                Text("可拖分隔条，范围 ${(minR * 100).toInt()}–${(maxR * 100).toInt()}%",
                     fontSize = 8.5.sp, color = ZxT.Text3)
            }
            Text("已有 split 容器是**静态分栏**（AI 定比例），" +
                 "这个让用户自己拖——需要同时看两份内容时，" +
                 "合适的比例只有用户知道",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_switch 条件容器 ══════════ */
    @Composable
    fun switch(o: JSONObject, ctx: RenderContext) {
        val cases = o.optJSONArray("cases") ?: return
        val n = minOf(cases.length(), 5)
        var cur by remember { mutableIntStateOf(o.optInt("cur", 0).coerceIn(0, n - 1)) }
        // 条件求值：只支持简单的字段比较，不做表达式解析
        val conds = (0 until n).map { cases.optJSONObject(it)?.optString("when", "") ?: "" }
        val matched = conds.mapIndexed { i, c -> i to (c.isBlank() || c == "default") }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "条件分支"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("$n 个分支", fontSize = 9.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                for (i in 0 until n) {
                    val c = cases.optJSONObject(i) ?: continue
                    val on = cur == i
                    Text(c.optString("label", conds[i].ifBlank { "默认" }),
                         fontSize = 9.5.sp, color = if (on) Color.White else ZxT.Text3,
                         modifier = Modifier.clip(RoundedCornerShape(9.dp))
                             .background(if (on) PAL[0] else ZxT.Surface2)
                             .clickable {
                                 cur = i
                                 ctx.callTool("zx_select", mapOf(
                                     "branch" to c.optString("label", ""),
                                     "when" to conds[i], "source" to "zx_switch"))
                             }.padding(9.dp, 3.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(ZxT.Surface2).padding(8.dp)) {
                val body = cases.optJSONObject(cur)?.optJSONObject("body")
                if (body != null) ZxNest.render(body, ctx, 0)
                else Text("（该分支没有内容）", fontSize = 10.sp, color = ZxT.Text3)
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)) {
                Text("当前：${conds[cur].ifBlank { "默认" }}", fontSize = 9.sp,
                     color = ZxT.Text3, fontFamily = ZxT.Mono,
                     modifier = Modifier.weight(1f))
                if (matched.any { !it.second })
                    Text("条件由宿主求值", fontSize = 8.5.sp, color = ZxT.Text3)
            }
            Text("已有 tabs 是**用户切换**，这个由**条件决定**显示哪个分支——" +
                 "同一块地方在不同状态下摆不同内容。" +
                 "条件字符串交给宿主求值，插件不做表达式解析（那是注入面）",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_lane 泳道时间线 ══════════ */
    @Composable
    fun lane(o: JSONObject, ctx: RenderContext) {
        val lanes = o.optJSONArray("lanes") ?: return
        val nl = minOf(lanes.length(), 5)
        val slots = o.optInt("slots", 12).coerceIn(2, 24)
        var sel by remember { mutableStateOf<Pair<Int, Int>?>(null) }
        var cursor by remember { mutableIntStateOf(o.optInt("cur", -1)) }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "排期"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("$nl 道 × $slots 格", fontSize = 9.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(6.dp))
            for (i in 0 until nl) {
                val lane = lanes.optJSONObject(i) ?: continue
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(lane.optString("n", ""), fontSize = 8.5.sp, color = ZxT.Text3,
                         modifier = Modifier.width(44.dp), maxLines = 1,
                         overflow = TextOverflow.Clip)
                    Row(Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(1.5.dp)) {
                        for (k in 0 until slots) {
                            val st = lane.optJSONArray("v")?.optInt(k, 0) ?: 0
                            val on = sel?.first == i && sel?.second == k
                            val cur = cursor == k
                            // 0 空闲 / 1 占用 / 2 冲突 / 3 不可用
                            val col = when (st) {
                                1 -> PAL[i % PAL.size].copy(.72f)
                                2 -> ZxT.Err
                                3 -> ZxT.Surface3
                                else -> ZxT.Surface2
                            }
                            Box(Modifier.weight(1f).height(20.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(col)
                                .border(if (cur) 1.dp else 0.dp,
                                    if (cur) ZxT.Text else Color.Transparent,
                                    RoundedCornerShape(3.dp))
                                .clickable {
                                    sel = i to k
                                    cursor = k
                                    ctx.callTool("zx_select", mapOf(
                                        "lane" to lane.optString("n", ""),
                                        "slot" to k.toString(), "state" to st.toString(),
                                        "source" to "zx_lane"))
                                })
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 44.dp, top = 4.dp)) {
                Text("第 ${if (cursor >= 0) cursor + 1 else 1} 格", fontSize = 8.5.sp,
                     color = ZxT.Text3, modifier = Modifier.weight(1f))
                listOf("空闲" to ZxT.Surface2, "占用" to PAL[0].copy(.72f),
                       "冲突" to ZxT.Err, "不可用" to ZxT.Surface3).forEach { (lab, c) ->
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 7.dp)) {
                        Box(Modifier.size(6.dp).clip(RoundedCornerShape(1.dp))
                            .background(c))
                        Spacer(Modifier.width(3.dp))
                        Text(lab, fontSize = 8.sp, color = ZxT.Text3)
                    }
                }
            }
            Text("已有 gantt 画的是**跨度**、swimlane（若有）画的是流程，" +
                 "这个是**离散格子**——排班、排期这种「一格一个状态」的场景。" +
                 "冲突格标红，不然用户要自己比对",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_bullet2 子弹图组 ══════════ */
    @Composable
    fun bullet2(o: JSONObject, ctx: RenderContext) {
        val items = o.optJSONArray("items") ?: return
        val n = minOf(items.length(), 8)
        var sel by remember { mutableIntStateOf(-1) }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "达成情况"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("$n 项", fontSize = 9.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(6.dp))
            for (i in 0 until n) {
                val it = items.optJSONObject(i) ?: continue
                val act = it.optDouble("act", 0.0)
                val tgt = it.optDouble("tgt", 0.0)
                val max = it.optDouble("max", maxOf(act, tgt, 1.0)).coerceAtLeast(1e-9)
                val bands = it.optJSONArray("bands")
                val on = sel == i
                val ok = act >= tgt
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (on) ZxT.Surface2 else Color.Transparent)
                    .clickable {
                        sel = i
                        ctx.callTool("zx_select", mapOf(
                            "item" to it.optString("n", ""), "act" to act.toString(),
                            "tgt" to tgt.toString(), "ok" to ok.toString(),
                            "source" to "zx_bullet2"))
                    }.padding(5.dp, 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(it.optString("n", ""), fontSize = 9.sp, color = ZxT.Text2,
                         modifier = Modifier.width(52.dp), maxLines = 1,
                         overflow = TextOverflow.Clip)
                    Box(Modifier.weight(1f).height(16.dp)) {
                        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                            val W = size.width; val H = size.height
                            // 分段背景（差 / 良 / 优）
                            val bs = (0 until (bands?.length() ?: 0)).map {
                                bands!!.optDouble(it, 0.0) }
                            val cols = listOf(ZxT.Surface3, ZxT.Surface3.copy(.6f),
                                              ZxT.Surface3.copy(.35f))
                            var prev = 0f
                            bs.forEachIndexed { bi, b ->
                                val w = (b / max).toFloat().coerceIn(0f, 1f)
                                drawRect(cols[bi % cols.size], Offset(prev * W, 0f),
                                         Size((w - prev).coerceAtLeast(0f) * W, H))
                                prev = w
                            }
                            if (bs.isEmpty())
                                drawRect(ZxT.Surface3, Offset(0f, 0f), Size(W, H))
                            // 实际值条
                            val aw = (act / max).toFloat().coerceIn(0f, 1f)
                            drawRect(if (ok) PAL[1] else ZxT.Warn, Offset(0f, H * .3f),
                                     Size(aw * W, H * .4f))
                            // 目标标记线
                            val tw = (tgt / max).toFloat().coerceIn(0f, 1f)
                            drawRect(ZxT.Text, Offset(tw * W - 1f, H * .12f),
                                     Size(2.2f, H * .76f))
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    Text("%.0f".format(act), fontSize = 9.5.sp,
                         color = if (ok) ZxT.Ok else ZxT.Warn,
                         fontFamily = ZxT.Mono, modifier = Modifier.width(28.dp),
                         textAlign = TextAlign.End)
                    Text("/%.0f".format(tgt), fontSize = 8.5.sp, color = ZxT.Text3,
                         fontFamily = ZxT.Mono, modifier = Modifier.width(26.dp))
                }
            }
            Text("已有 bullet（子弹图）是单条，这个是**一组可对比的达成条**——" +
                 "一眼扫出哪些没达标。目标线用实心竖线，不和实际值条混色",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_ring2 同心环占比 ══════════ */
    @Composable
    fun ring2(o: JSONObject, ctx: RenderContext) {
        val rings = o.optJSONArray("rings") ?: return
        val n = minOf(rings.length(), 4)
        var sel by remember { mutableStateOf<Pair<Int, Int>?>(null) }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "层级占比"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("$n 环", fontSize = 9.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(150.dp)) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val cx = size.width / 2; val cy = size.height / 2
                    val maxR = minOf(cx, cy) * .92f
                    for (i in 0 until n) {
                        val ring = rings.optJSONObject(i) ?: continue
                        val segs = ring.optJSONArray("segs") ?: continue
                        val m = minOf(segs.length(), 8)
                        val tot = (0 until m).sumOf { segs.optJSONObject(it)?.optDouble("v", 0.0)
                            ?: 0.0 }.coerceAtLeast(1e-9)
                        val rOut = maxR * (1f - i.toFloat() / n) - i * 2f
                        val rIn = rOut - (maxR / n) * .68f
                        var start = -90f
                        for (k in 0 until m) {
                            val s = segs.optJSONObject(k) ?: continue
                            val v = s.optDouble("v", 0.0)
                            val sweep = (v / tot).toFloat() * 360f
                            val on = sel?.first == i && sel?.second == k
                            val col = PAL[(i * 2 + k) % PAL.size]
                            drawArc(col.copy(if (on) 1f else .78f), start, sweep - 1.2f,
                                    true, topLeft = Offset(cx - rOut, cy - rOut),
                                    size = Size(rOut * 2, rOut * 2))
                            // 挖空内圈：用背景色盖，省一层 Path 运算
                            drawArc(ZxT.Surface, start, sweep - 1.2f, true,
                                    topLeft = Offset(cx - rIn, cy - rIn),
                                    size = Size(rIn * 2, rIn * 2))
                            start += sweep
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().height(150.dp)) {
                    (0 until n).forEach { i ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = i to 0
                            ctx.callTool("zx_select", mapOf(
                                "ring" to (rings.optJSONObject(i)?.optString("n", "") ?: ""),
                                "source" to "zx_ring2"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)
                .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (i in 0 until n) {
                    val ring = rings.optJSONObject(i) ?: continue
                    val segs = ring.optJSONArray("segs")
                    for (k in 0 until minOf(segs?.length() ?: 0, 8)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(7.dp).clip(CircleShape)
                                .background(PAL[(i * 2 + k) % PAL.size]))
                            Spacer(Modifier.width(3.dp))
                            Text(segs?.optJSONObject(k)?.optString("n", "") ?: "",
                                 fontSize = 8.sp, color = ZxT.Text3, maxLines = 1)
                        }
                    }
                }
            }
            Text("已有 donut 是单环、sunburst 是树状下钻，" +
                 "这个是**多层独立环**——每一环是一个维度（年份 / 渠道 / 品类），" +
                 "一眼看出各层的构成差异",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_stepflow 分步流程（带状态） ══════════ */
    @Composable
    fun stepflow(o: JSONObject, ctx: RenderContext) {
        val steps = o.optJSONArray("steps") ?: return
        val n = minOf(steps.length(), 8)
        var sel by remember { mutableIntStateOf(-1) }
        // 0 待办 / 1 进行中 / 2 完成 / 3 失败 / 4 跳过
        val cols = listOf(ZxT.Text3, ZxT.PriLite, ZxT.Ok, ZxT.Err, ZxT.Surface3)
        val labs = listOf("待办", "进行中", "完成", "失败", "跳过")
        val done = (0 until n).count { steps.optJSONObject(it)?.optInt("s", 0) == 2 }
        val failed = (0 until n).count { steps.optJSONObject(it)?.optInt("s", 0) == 3 }
        val running = (0 until n).indexOfFirst { steps.optJSONObject(it)?.optInt("s", 0) == 1 }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "执行进度"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                if (failed > 0)
                    Text("$failed 步失败", fontSize = 9.5.sp, color = ZxT.Err)
                else
                    Text("$done/$n", fontSize = 9.5.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                for (i in 0 until n) {
                    val st = steps.optJSONObject(i) ?: continue
                    val s = st.optInt("s", 0)
                    val on = sel == i
                    val col = cols[s.coerceIn(0, 4)]
                    Column(Modifier.weight(1f),
                           horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(22.dp).clip(CircleShape)
                            .background(if (s == 0) ZxT.Surface2 else col.copy(.22f))
                            .border(if (on) 1.6.dp else 1.dp,
                                if (s == 0) ZxT.Surface3 else col, CircleShape)
                            .clickable {
                                sel = i
                                ctx.callTool("zx_select", mapOf(
                                    "step" to st.optString("n", ""),
                                    "state" to labs[s.coerceIn(0, 4)],
                                    "source" to "zx_stepflow"))
                            }, contentAlignment = Alignment.Center) {
                            Text(if (s == 2) "✓" else if (s == 3) "✕" else "${i + 1}",
                                 fontSize = 10.sp, color = if (s == 0) ZxT.Text3 else col)
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(st.optString("n", ""), fontSize = 8.sp,
                             color = if (on) ZxT.Text else ZxT.Text3,
                             maxLines = 1, overflow = TextOverflow.Clip,
                             textAlign = TextAlign.Center)
                    }
                    if (i < n - 1) {
                        // 连接线：只有前一步完成才点亮
                        val lit = s == 2
                        Box(Modifier.width(10.dp).height(1.5.dp)
                            .background(if (lit) ZxT.Ok else ZxT.Surface3))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp)
                .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                labs.forEachIndexed { i, lab ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(cols[i]))
                        Spacer(Modifier.width(3.dp))
                        Text(lab, fontSize = 8.sp, color = ZxT.Text3)
                    }
                }
            }
            if (sel >= 0) {
                val st = steps.optJSONObject(sel)
                val s = st?.optInt("s", 0) ?: 0
                Text("第 ${sel + 1} 步「${st?.optString("n", "")}」：${labs[s.coerceIn(0, 4)]}" +
                     (st?.optString("d")?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                     fontSize = 9.5.sp, color = ZxT.PriLite, lineHeight = 13.sp,
                     modifier = Modifier.padding(top = 4.dp))
            }
            Text("已有 wizard（向导）是引导用户填、statelist 是状态列表，" +
                 "这个是**AI 执行多步任务时给用户看进度**——" +
                 "失败步标红且后续连接线不点亮，卡在哪一眼看出",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_annotate 自由标注 ══════════ */
    @Composable
    fun annotate(o: JSONObject, ctx: RenderContext) {
        val marks = o.optJSONArray("marks") ?: return
        val n = minOf(marks.length(), 10)
        var sel by remember { mutableIntStateOf(-1) }
        val bg = o.optString("bg", "")

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "标注"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("$n 处", fontSize = 9.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(150.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (bg.isNotBlank()) ZxT.Surface2 else Color.Transparent)) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val W = size.width; val H = size.height
                    for (i in 0 until n) {
                        val m = marks.optJSONObject(i) ?: continue
                        val x = (m.optDouble("x", .5) * W).toFloat()
                        val y = (m.optDouble("y", .5) * H).toFloat()
                        val on = sel == i
                        val col = PAL[i % PAL.size]
                        when (m.optString("t", "dot")) {
                            "box" -> {
                                val w = (m.optDouble("w", .2) * W).toFloat()
                                val h = (m.optDouble("h", .15) * H).toFloat()
                                drawRect(col, Offset(x, y), Size(w, h),
                                         style = Stroke(if (on) 2.2f else 1.4f))
                            }
                            "arrow" -> {
                                drawLine(col, Offset(x, y),
                                         Offset(x + 26f, y - 18f), if (on) 2.2f else 1.4f)
                                drawCircle(col, 3f, Offset(x + 26f, y - 18f))
                            }
                            "line" -> drawLine(col, Offset(x, y),
                                               Offset(x + 40f, y), if (on) 2.2f else 1.4f)
                            else -> {
                                drawCircle(col.copy(.28f), if (on) 11f else 8f, Offset(x, y))
                                drawCircle(col, 3.5f, Offset(x, y))
                            }
                        }
                        // 序号：标注多了没有序号就对不上文字说明
                        if (on)
                            drawCircle(ZxT.Text, 7f, Offset(x, y - 14f))
                    }
                }
                Row(Modifier.fillMaxWidth().height(150.dp)) {
                    (0 until n).forEach { i ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable {
                            sel = i
                            ctx.callTool("zx_select", mapOf(
                                "mark" to (marks.optJSONObject(i)?.optString("n", "") ?: ""),
                                "type" to (marks.optJSONObject(i)?.optString("t", "dot") ?: ""),
                                "source" to "zx_annotate"))
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                marks.optJSONObject(sel)?.optString("n")?.let {
                    Text("「$it」", fontSize = 10.sp, color = ZxT.PriLite,
                         modifier = Modifier.weight(1f), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                } ?: Text("点标注看说明", fontSize = 9.sp, color = ZxT.Text3,
                          modifier = Modifier.weight(1f))
                Text("${marks.optJSONObject(sel)?.optString("t", "—") ?: "—"}",
                     fontSize = 8.5.sp, color = ZxT.Text3, fontFamily = ZxT.Mono)
            }
            Text("已有 hotspot（图片热点）只支持图片上的点、" +
                 "comment（批注）只挂文本，这个是**任意画布上的自由标注**——" +
                 "框 / 箭头 / 线 / 点四类，AI 指哪儿用哪儿",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }

    /* ══════════ zx_dial3 多表盘组 ══════════ */
    @Composable
    fun dial3(o: JSONObject, ctx: RenderContext) {
        val dials = o.optJSONArray("dials") ?: return
        val n = minOf(dials.length(), 4)
        var sel by remember { mutableIntStateOf(-1) }
        val cols = minOf(n, 4)

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("n", "仪表组"), fontSize = 11.sp, color = ZxT.Text,
                     modifier = Modifier.weight(1f))
                Text("$n 个", fontSize = 9.sp, color = ZxT.Text3)
            }
            Spacer(Modifier.height(6.dp))
            val rowsN = (n + cols - 1) / cols
            Column {
                for (r in 0 until rowsN) {
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (c in 0 until cols) {
                            val i = r * cols + c
                            if (i >= n) { Spacer(Modifier.weight(1f)); continue }
                            val d = dials.optJSONObject(i) ?: continue
                            val v = d.optDouble("v", 0.0)
                            val max = d.optDouble("max", 100.0).coerceAtLeast(1e-9)
                            val warn = d.optDouble("warn", max * .75)
                            val t = (v / max).toFloat().coerceIn(0f, 1f)
                            val col = if (v >= d.optDouble("bad", max * .9)) ZxT.Err
                            else if (v >= warn) ZxT.Warn else PAL[1]
                            val on = sel == i
                            Box(Modifier.weight(1f).aspectRatio(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (on) ZxT.Surface2 else Color.Transparent)
                                .clickable {
                                    sel = i
                                    ctx.callTool("zx_select", mapOf(
                                        "dial" to d.optString("n", ""),
                                        "v" to v.toString(), "source" to "zx_dial3"))
                                }) {
                                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                                    val cx = size.width / 2; val cy = size.height / 2
                                    val R = minOf(cx, cy) * .78f
                                    // 背景弧 + 值弧，从 -210° 扫 240°
                                    drawArc(ZxT.Surface3, -210f, 240f, false,
                                            topLeft = Offset(cx - R, cy - R),
                                            size = Size(R * 2, R * 2),
                                            style = Stroke(5f))
                                    drawArc(col, -210f, 240f * t, false,
                                            topLeft = Offset(cx - R, cy - R),
                                            size = Size(R * 2, R * 2),
                                            style = Stroke(5f))
                                    // 警戒刻度
                                    val wa = -210f + 240f * (warn / max).toFloat()
                                    drawArc(ZxT.Warn, wa - .8f, 1.6f, false,
                                            topLeft = Offset(cx - R, cy - R),
                                            size = Size(R * 2, R * 2),
                                            style = Stroke(8f))
                                }
                                Column(Modifier.align(Alignment.Center),
                                       horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("%.0f".format(v), fontSize = 12.sp, color = col,
                                         fontFamily = ZxT.Mono)
                                    Text(d.optString("u", ""), fontSize = 7.5.sp,
                                         color = ZxT.Text3, maxLines = 1)
                                }
                            }
                        }
                    }
                    if (r < rowsN - 1) Spacer(Modifier.height(4.dp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                for (i in 0 until n)
                    Text(dials.optJSONObject(i)?.optString("n", "") ?: "",
                         fontSize = 8.sp, color = if (sel == i) ZxT.Text else ZxT.Text3,
                         modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                         maxLines = 1, overflow = TextOverflow.Clip)
            }
            Text("已有 gauge / dial 是单个，这个是**一组小表盘**——" +
                 "监控面板场景要同时看四五个指标。" +
                 "警戒刻度必须画出来，否则「多少算高」全靠猜",
                 fontSize = 10.sp, color = ZxT.Text3, lineHeight = 14.sp,
                 modifier = Modifier.padding(top = 6.dp))
        }
    }
}
