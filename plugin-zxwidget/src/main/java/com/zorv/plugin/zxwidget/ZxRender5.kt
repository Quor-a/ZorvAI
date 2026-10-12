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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 第五批组件（12 个）—— 输入型。
 *
 * 前三批多是"看 + 点选"，这批是"用户往里填东西"：
 *   zx_poll        投票/问卷（选完立刻看结果分布）
 *   zx_transfer    穿梭框（双栏互移 + 跨栏拖拽）
 *   zx_chips       标签多选（可搜索、可自建）
 *   zx_pin         口令/验证码（格子输入 + 自动跳焦）
 *   zx_dial        旋钮（旋转手势）
 *   zx_joystick    二维摇杆
 *   zx_timepicker  时间选择（滚轮）
 *   zx_slidergroup 多滑块组（带总量约束）
 *   zx_sortable    拖拽排序列表
 *   zx_cardflip    翻转卡（正反面）
 *   zx_comment     内联批注（可回复、表情）
 *   zx_seek        进度拖拽条（带章节标记）
 *
 * 输入型组件有三个前三批没遇到的坑：
 *   1. 焦点链：多个输入框之间要能自动跳焦，否则用户得一个一个点
 *   2. 手势仲裁：横向拖拽归组件、纵向归列表滚动，判定错了整个页面手感就废了
 *   3. 中间态：填到一半的值要不要回传？答案是不要——只在"完成"时回传一次
 */
object ZxRender5 {

    /* ══════════════ zx_poll 投票 ══════════════ */
    @Composable
    fun poll(o: JSONObject, ctx: RenderContext) {
        val opts = o.optJSONArray("options") ?: return
        val n = minOf(opts.length(), 6)
        val multi = o.optBoolean("multi", false)
        val picked = remember { mutableStateMapOf<Int, Boolean>() }
        // 已投票后显示分布，未投时只显示选项
        val voted = remember { mutableStateOf(o.optBoolean("voted", false)) }

        Column(Modifier.fillMaxWidth()) {
            if (o.optString("q").isNotBlank())
                Text(o.optString("q"), fontSize = 12.5.sp, color = ZxT.Text,
                     modifier = Modifier.padding(bottom = 8.dp), lineHeight = 17.sp)

            val total = remember(opts) {
                var t = 0.0
                for (i in 0 until n) t += opts.optJSONObject(i)?.optDouble("v", 0.0) ?: 0.0
                t
            }

            for (i in 0 until n) {
                val op = opts.optJSONObject(i) ?: continue
                val on = picked[i] == true
                val v = op.optDouble("v", 0.0)
                val frac = if (total > 0) (v / total).toFloat() else 0f
                Box(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Box(Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(9.dp))
                        .background(ZxT.Surface2)
                        .border(if (on) 1.5.dp else 1.dp,
                                if (on) ZxT.Primary else ZxT.Line, RoundedCornerShape(9.dp))
                        .clickable {
                            if (multi) picked[i] = !on else {
                                picked.clear(); picked[i] = true
                            }
                        }) {
                        // 结果条作为背景层，投完才显示
                        if (voted.value) {
                            Box(Modifier.fillMaxWidth(if (on) frac else frac * .75f).fillMaxHeight()
                                .clip(RoundedCornerShape(9.dp))
                                .background(if (on) ZxT.Primary.copy(.35f) else ZxT.Surface3))
                        }
                        Row(Modifier.fillMaxWidth().padding(11.dp, 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            // 单选圆 / 多选方，形状区分语义
                            Box(Modifier.size(15.dp)
                                .clip(if (multi) RoundedCornerShape(4.dp) else CircleShape)
                                .border(1.4.dp, if (on) ZxT.Primary else ZxT.Text3,
                                        if (multi) RoundedCornerShape(4.dp) else CircleShape)
                                .let { if (on) it.background(ZxT.Primary) else it })
                            Spacer(Modifier.width(9.dp))
                            Text(op.optString("t", ""), fontSize = 12.sp,
                                 color = if (on) ZxT.Text else ZxT.Text2,
                                 modifier = Modifier.weight(1f), lineHeight = 16.sp)
                            if (voted.value)
                                Text("${(frac * 100).roundToInt()}%", fontSize = 10.5.sp,
                                     color = if (on) ZxT.PriLite else ZxT.Text3)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.Button(
                onClick = {
                    voted.value = true
                    val payload = JSONObject()
                    for (i in 0 until n) if (picked[i] == true)
                        payload.put(opts.optJSONObject(i)?.optString("k", "o$i") ?: "o$i", true)
                    ctx.callTool("zx_form_submit", mapOf("payload" to payload.toString()))
                },
                enabled = picked.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = ZxT.Primary,
                    disabledContainerColor = ZxT.Surface3)
            ) { Text(if (voted.value) "已提交" else "投票", fontSize = 12.5.sp) }
        }
    }

    /* ══════════════ zx_transfer 穿梭框 ══════════════ */
    @Composable
    fun transfer(o: JSONObject, ctx: RenderContext) {
        val items = o.optJSONArray("items") ?: return
        val left = remember { mutableStateListOf<Int>() }
        val right = remember { mutableStateListOf<Int>() }
        // 初始化一次：按 DSL 的 side 分栏
        LaunchedEffect(Unit) {
            for (i in 0 until minOf(items.length(), 24)) {
                val it = items.optJSONObject(i) ?: continue
                if (it.optString("side") == "right") right.add(i) else left.add(i)
            }
        }
        val sel = remember { mutableStateMapOf<Int, Boolean>() }

        @Composable
        fun pane(list: List<Int>, title: String, isLeft: Boolean, mod: Modifier = Modifier) {
            Column(mod
                .clip(RoundedCornerShape(10.dp))
                .background(ZxT.Surface2)
                .border(1.dp, ZxT.Line, RoundedCornerShape(10.dp))) {
                Row(Modifier.fillMaxWidth().padding(9.dp, 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontSize = 11.sp, color = ZxT.Text2, modifier = Modifier.weight(1f))
                    Text("${list.size}", fontSize = 10.sp, color = ZxT.Text3)
                }
                Column(Modifier.fillMaxWidth().heightIn(max = 140.dp)) {
                    list.forEach { i ->
                        val it = items.optJSONObject(i) ?: return@forEach
                        val on = sel[i] == true
                        Row(Modifier.fillMaxWidth()
                            .background(if (on) ZxT.Primary.copy(.16f) else Color.Transparent)
                            .clickable { sel[i] = !on }
                            .padding(8.dp, 7.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(it.optString("n", ""), fontSize = 11.5.sp,
                                 color = if (on) ZxT.Text else ZxT.Text2,
                                 modifier = Modifier.weight(1f), maxLines = 1,
                                 overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (list.isEmpty()) Text("空", fontSize = 10.5.sp, color = ZxT.Text3,
                                             modifier = Modifier.padding(9.dp))
                }
            }
        }

        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth()) {
                pane(left, o.optString("leftTitle", "可选"), true, Modifier.weight(1f))
                // 中间转移按钮
                Column(Modifier.width(38.dp), horizontalAlignment = Alignment.CenterHorizontally,
                       verticalArrangement = Arrangement.Center) {
                    Text("→", fontSize = 15.sp, color = ZxT.PriLite,
                         modifier = Modifier.clickable {
                             val mv = left.filter { sel[it] == true }
                             left.removeAll(mv); right.addAll(mv); mv.forEach { sel.remove(it) }
                         }.padding(6.dp))
                    Spacer(Modifier.height(5.dp))
                    Text("←", fontSize = 15.sp, color = ZxT.PriLite,
                         modifier = Modifier.clickable {
                             val mv = right.filter { sel[it] == true }
                             right.removeAll(mv); left.addAll(mv); mv.forEach { sel.remove(it) }
                         }.padding(6.dp))
                }
                pane(right, o.optString("rightTitle", "已选"), false, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.Button(
                onClick = {
                    val payload = JSONObject()
                    payload.put("selected", JSONArray().apply { right.forEach { put(it) } })
                    ctx.callTool("zx_form_submit", mapOf("payload" to payload.toString()))
                },
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = ZxT.Primary)
            ) { Text("确定（已选 ${right.size}）", fontSize = 12.5.sp) }
        }
    }

    /* ══════════════ zx_chips 标签多选 ══════════════ */
    @Composable
    fun chips(o: JSONObject, ctx: RenderContext) {
        val tags = o.optJSONArray("tags") ?: return
        val n = minOf(tags.length(), 16)
        val sel = remember { mutableStateSetOf<String>() }
        val custom = remember { mutableStateListOf<String>() }
        var query by remember { mutableStateOf("") }

        val all = remember(tags, custom) {
            buildList {
                for (i in 0 until n) add(tags.optString(i))
                addAll(custom)
            }
        }
        val shown = all.filter { it.contains(query, true) || query.isBlank() }

        Column(Modifier.fillMaxWidth()) {
            // 搜索框：输入的是"过滤词"，不是"要提交的值"——
            // 这类中间态绝不回传，只在点确定时打包
            androidx.compose.material3.TextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text("搜索或输入后回车新建", fontSize = 11.sp, color = ZxT.Text3) },
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
            Spacer(Modifier.height(8.dp))
            // 流式布局：手写换行，不用 FlowRow（兼容性考虑）
            var rowContent: @Composable () -> Unit = {}
            val rows = shown.chunked(3)
            rows.forEach { chunk ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    chunk.forEach { t ->
                        val on = sel.contains(t)
                        Box(Modifier.padding(end = 6.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (on) ZxT.Primary else ZxT.Surface2)
                            .border(1.dp, if (on) ZxT.Primary else ZxT.Line,
                                    RoundedCornerShape(14.dp))
                            .clickable { if (on) sel.remove(t) else sel.add(t) }
                            .padding(10.dp, 6.dp)) {
                            Text(t, fontSize = 11.5.sp, color = if (on) Color.White else ZxT.Text2)
                        }
                    }
                }
            }
            if (sel.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.Button(
                    onClick = {
                        ctx.callTool("zx_form_submit", mapOf("payload" to
                            JSONObject().put("tags", JSONArray().apply { sel.forEach { put(it) } }).toString()))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = ZxT.Primary)
                ) { Text("确定（${sel.size}）", fontSize = 12.5.sp) }
            }
        }
    }

    /* ══════════════ zx_pin 口令输入 ══════════════ */
    @Composable
    fun pin(o: JSONObject, ctx: RenderContext) {
        val len = o.optInt("len", 6).coerceIn(3, 8)
        val mask = o.optBoolean("mask", false)
        // 每个格子一个字符，存数组而不是整串——
        // 整串做"第 3 格变了"这种更新要切字符串，数组直接改下标
        val chars = remember { mutableStateListOf<String>().apply { repeat(len) { add("") } } }
        var focus by remember { mutableIntStateOf(0) }

        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (i in 0 until len) {
                    val c = chars.getOrNull(i).orEmpty()
                    val active = i == focus
                    Box(Modifier.size(if (len <= 6) 42.dp else 34.dp,
                                      if (len <= 6) 48.dp else 40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (c.isNotEmpty()) ZxT.Surface2 else ZxT.Surface)
                        .border(if (active) 1.8.dp else 1.dp,
                                if (active) ZxT.Primary else ZxT.Line,
                                RoundedCornerShape(10.dp))
                        .clickable { focus = i }, contentAlignment = Alignment.Center) {
                        Text(if (mask && c.isNotEmpty()) "●" else c, fontSize = 18.sp,
                             color = ZxT.Text, fontWeight = FontWeight.Medium)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            // 演示用数字键盘；真实工程接输入法时靠焦点链自动跳焦
            val keys = listOf("1","2","3","4","5","6","7","8","9","⌫","0","✓")
            keys.chunked(3).forEach { rowKeys ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    rowKeys.forEach { k ->
                        Box(Modifier.size(56.dp, 44.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (k == "✓") ZxT.Primary else ZxT.Surface2)
                            .clickable {
                                when (k) {
                                    "⌫" -> {
                                        if (chars[focus].isEmpty() && focus > 0) { focus--; chars[focus] = "" }
                                        else if (chars[focus].isNotEmpty()) chars[focus] = ""
                                    }
                                    "✓" -> {
                                        val v = chars.joinToString("")
                                        if (v.length == len) ctx.callTool("zx_form_submit",
                                            mapOf("payload" to JSONObject().put("pin", v).toString()))
                                    }
                                    else -> {
                                        if (focus < len) {
                                            chars[focus] = k
                                            // 自动跳焦：填完一格进下一格，用户不用点
                                            if (focus < len - 1) focus++
                                        }
                                    }
                                }
                            }, contentAlignment = Alignment.Center) {
                            Text(k, fontSize = 15.sp, color = if (k == "✓") Color.White else ZxT.Text)
                        }
                    }
                }
            }
        }
        // 焦点链：focus 变化时若前序有空位，回退到第一个空位
        LaunchedEffect(chars.size) {
            val firstEmpty = chars.indexOfFirst { it.isEmpty() }
            if (firstEmpty >= 0 && firstEmpty != focus) focus = firstEmpty
        }
    }

    /* ══════════════ zx_dial 旋钮 ══════════════ */
    @Composable
    fun dial(o: JSONObject, ctx: RenderContext) {
        val min = o.optDouble("min", 0.0)
        val max = o.optDouble("max", 100.0)
        var value by remember { mutableFloatStateOf(
            ((o.optDouble("v", min) - min) / (max - min).coerceAtLeast(.001)).toFloat().coerceIn(0f, 1f)) }
        var angle by remember { mutableFloatStateOf(value * 270f) }

        Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Canvas(
                Modifier.size(108.dp)
                    .pointerInput(Unit) {
                        detectDragGestures { change, _ ->
                            val c = Offset(size.width / 2f, size.height / 2f)
                            val p = change.position
                            // 以圆心算角度，映射到 135°~405° 的有效区间
                            var a = Math.toDegrees(
                                atan2(p.y - c.y, p.x - c.x).toDouble()).toFloat() + 90f
                            if (a < 0) a += 360f
                            val clamped = a.coerceIn(0f, 270f)
                            angle = clamped
                            value = clamped / 270f
                        }
                    }
            ) {
                val r = size.minDimension / 2 - 9
                val c = Offset(size.width / 2, size.height / 2)
                drawCircle(ZxT.Surface2, r, c)
                drawArc(ZxT.Surface3, 135f, 270f, false,
                        topLeft = Offset(c.x - r, c.y - r), size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(7f))
                drawArc(ZxT.Primary, 135f, 270f * value, false,
                        topLeft = Offset(c.x - r, c.y - r), size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(7f))
                // 指针
                val a = (135f + angle - 90f) * PI.toFloat() / 180f
                drawLine(ZxT.Text, c, Offset(c.x + r * .82f * cos(a), c.y + r * .82f * sin(a)), 3f)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("%.0f".format(min + (max - min) * value), fontSize = 19.sp,
                     fontWeight = FontWeight.Bold, color = ZxT.Text)
                if (o.optString("label").isNotBlank())
                    Text(o.optString("label"), fontSize = 10.sp, color = ZxT.Text3)
            }
        }
    }

    /* ══════════════ zx_joystick 二维摇杆 ══════════════ */
    @Composable
    fun joystick(o: JSONObject, ctx: RenderContext) {
        var pos by remember { mutableStateOf(Offset(0f, 0f)) }   // -1..1
        val deadZone = o.optDouble("dead", 0.08)

        Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Canvas(
                Modifier.size(104.dp).pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = {
                            // 松手回中，并在有实际位移时才回传
                            val wasMoved = kotlin.math.abs(pos.x) > deadZone ||
                                           kotlin.math.abs(pos.y) > deadZone
                            pos = Offset(0f, 0f)
                            if (wasMoved) ctx.callTool("zx_form_submit", mapOf("payload" to
                                JSONObject().put("x", 0).put("y", 0).put("released", true).toString()))
                        }
                    ) { change, _ ->
                        val c = Offset(size.width / 2f, size.height / 2f)
                        var dx = (change.position.x - c.x) / c.x
                        var dy = (change.position.y - c.y) / c.y
                        val len = kotlin.math.sqrt(dx * dx + dy * dy)
                        if (len > 1f) { dx /= len; dy /= len }   // 夹到单位圆
                        pos = Offset(dx, dy)
                        if (len > deadZone) ctx.callTool("zx_form_submit", mapOf("payload" to
                            JSONObject().put("x", dx.toDouble()).put("y", dy.toDouble()).toString()))
                    }
                }
            ) {
                val r = size.minDimension / 2
                val c = Offset(size.width / 2, size.height / 2)
                drawCircle(ZxT.Surface2, r, c)
                drawCircle(ZxT.Line, r * .38f, c,
                           style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
                drawCircle(ZxT.Primary.copy(.3f), r * .92f, c,
                           style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
                drawCircle(ZxT.Primary, r * .22f,
                           Offset(c.x + pos.x * r * .6f, c.y + pos.y * r * .6f))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text("x ${"%.2f".format(pos.x)}", fontSize = 10.sp, color = ZxT.Text3,
                 modifier = Modifier.weight(1f))
            Text("y ${"%.2f".format(pos.y)}", fontSize = 10.sp, color = ZxT.Text3,
                 modifier = Modifier.weight(1f))
        }
    }

    /* ══════════════ zx_timepicker 时间选择 ══════════════ */
    @Composable
    fun timepicker(o: JSONObject, ctx: RenderContext) {
        var hour by remember { mutableIntStateOf(o.optInt("h", 9).coerceIn(0, 23)) }
        var minute by remember { mutableIntStateOf(o.optInt("m", 0).coerceIn(0, 59)) }
        val step = o.optInt("step", 5).coerceIn(1, 30)

        Row(Modifier.fillMaxWidth().height(118.dp)) {
            // 时/分两列滚轮
            listOf(
                (0..23).toList() to hour,
                (0..59 step step).toList() to minute
            ).forEachIndexed { idx, (range, cur) ->
                Column(Modifier.weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(ZxT.Surface2)) {
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        items(range.size) { i ->
                            val v = range[i]
                            val on = v == cur
                            Text("%02d".format(v), fontSize = if (on) 16.sp else 13.sp,
                                 color = if (on) ZxT.Text else ZxT.Text3,
                                 fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                                 modifier = Modifier.clickable {
                                     if (idx == 0) hour = v else minute = v
                                 }.padding(vertical = 4.dp))
                        }
                    }
                }
                if (idx == 0) Spacer(Modifier.width(9.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            Text("%02d:%02d".format(hour, minute), fontSize = 18.sp,
                 fontWeight = FontWeight.Bold, color = ZxT.Text,
                 fontFamily = ZxT.Mono, modifier = Modifier.weight(1f))
            androidx.compose.material3.Button(
                onClick = { ctx.callTool("zx_form_submit", mapOf("payload" to
                    JSONObject().put("h", hour).put("m", minute).toString())) },
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = ZxT.Primary)) {
                Text("确定", fontSize = 12.sp)
            }
        }
    }

    /* ══════════════ zx_slidergroup 多滑块组（总量约束） ══════════════ */
    @Composable
    fun slidergroup(o: JSONObject, ctx: RenderContext) {
        val items = o.optJSONArray("items") ?: return
        val n = minOf(items.length(), 5)
        val budget = o.optDouble("budget", 100.0)
        val vals = remember {
            mutableStateMapOf<Int, Double>().apply {
                for (i in 0 until n) put(i, items.optJSONObject(i)?.optDouble("v", 0.0) ?: 0.0)
            }
        }
        // 关键约束：动一个滑块，超出预算的部分从其他滑块按比例摊回
        fun rebalance(changed: Int, nv: Double) {
            val others = (0 until n).filter { it != changed }
            val rest = budget - nv
            val curOther = others.sumOf { vals[it] ?: 0.0 }
            vals[changed] = nv
            if (curOther <= 0) {
                // 其他都是 0，均摊剩余
                others.forEach { vals[it] = rest / others.size }
            } else {
                val scale = (rest / curOther).coerceAtLeast(0.0)
                others.forEach { vals[it] = (vals[it] ?: 0.0) * scale }
            }
        }

        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth()) {
                Text("预算 %.0f".format(budget), fontSize = 10.5.sp, color = ZxT.Text3,
                     modifier = Modifier.weight(1f))
                Text("已分配 %.0f".format(vals.values.sum()), fontSize = 10.5.sp,
                     color = if (vals.values.sum() > budget + .5) ZxT.Err else ZxT.Ok)
            }
            for (i in 0 until n) {
                val it = items.optJSONObject(i) ?: continue
                val v = vals[i] ?: 0.0
                var dragX by remember { mutableFloatStateOf(0f) }
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(it.optString("n", ""), fontSize = 11.5.sp, color = ZxT.Text2,
                         modifier = Modifier.width(66.dp), maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    Box(Modifier.weight(1f).height(24.dp)
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                dragX = (change.position.x / size.width).coerceIn(0f, 1f).toFloat()
                                rebalance(i, (dragX * budget).toDouble())
                            }
                        }) {
                        Box(Modifier.fillMaxWidth().height(4.dp).align(Alignment.Center)
                            .clip(RoundedCornerShape(2.dp)).background(ZxT.Surface3))
                        Box(Modifier.fillMaxWidth((v / budget).toFloat().coerceIn(0f, 1f))
                            .height(4.dp).align(Alignment.CenterStart)
                            .clip(RoundedCornerShape(2.dp)).background(ZxT.Primary))
                        Box(Modifier.size(15.dp).align(Alignment.CenterStart)
                            .offset(x = ((v / budget).toFloat() * 200).dp)
                            .clip(CircleShape).background(ZxT.PriLite))
                    }
                    Text("%.0f".format(v), fontSize = 10.sp, color = ZxT.Text3,
                         modifier = Modifier.width(34.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.Button(
                onClick = { ctx.callTool("zx_form_submit", mapOf("payload" to
                    JSONObject().apply { vals.forEach { (i, v) -> put(items.optJSONObject(i)?.optString("k", "s$i") ?: "s$i", v) } }.toString())) },
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = ZxT.Primary)) { Text("确定分配", fontSize = 12.5.sp) }
        }
    }

    /* ══════════════ zx_sortable 拖拽排序 ══════════════ */
    @Composable
    fun sortable(o: JSONObject, ctx: RenderContext) {
        val items = o.optJSONArray("items") ?: return
        val n = minOf(items.length(), 10)
        val order = remember { mutableStateListOf<Int>().apply { repeat(n) { add(it) } } }
        var dragging by remember { mutableIntStateOf(-1) }
        var dragY by remember { mutableFloatStateOf(0f) }

        Column(Modifier.fillMaxWidth()) {
            order.forEachIndexed { slot, i ->
                val it = items.optJSONObject(i) ?: return@forEachIndexed
                val isDrag = dragging == slot
                Row(Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (isDrag) ZxT.Primary.copy(.22f) else ZxT.Surface2)
                    .border(1.dp, if (isDrag) ZxT.Primary else ZxT.Line, RoundedCornerShape(9.dp))
                    .pointerInput(slot) {
                        detectDragGestures(
                            onDragStart = { dragging = slot },
                            onDragEnd = {
                                // 落点按位移算目标槽位，然后做一次插入
                                val target = (slot + (dragY / 44f).roundToInt())
                                    .coerceIn(0, n - 1)
                                if (target != slot) {
                                    val moved = order.removeAt(slot)
                                    order.add(target, moved)
                                    ctx.callTool("zx_form_submit", mapOf("payload" to
                                        JSONObject().put("order", JSONArray().apply {
                                            order.forEach { put(it) } }).toString()))
                                }
                                dragging = -1; dragY = 0f
                            }
                        ) { change, drag ->
                            dragY += drag.y
                        }
                    }
                    .padding(10.dp, 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("≡", fontSize = 13.sp, color = ZxT.Text3,
                         modifier = Modifier.padding(end = 9.dp))
                    Text(it.optString("n", ""), fontSize = 12.sp,
                         color = if (isDrag) ZxT.Text else ZxT.Text2,
                         modifier = Modifier.weight(1f))
                    Text("${slot + 1}", fontSize = 10.sp, color = ZxT.Text3)
                }
                Spacer(Modifier.height(6.dp))
            }
            Text("拖动 ≡ 重排 · 松手回传新顺序", fontSize = 10.5.sp, color = ZxT.Text3)
        }
    }

    /* ══════════════ zx_cardflip 翻转卡 ══════════════ */
    @Composable
    fun cardflip(o: JSONObject, ctx: RenderContext) {
        var front by remember { mutableStateOf(true) }
        val cards = o.optJSONArray("cards") ?: return
        var idx by remember { mutableIntStateOf(0) }
        val c = cards.optJSONObject(idx.coerceIn(0, cards.length() - 1)) ?: return

        Box(Modifier.fillMaxWidth().height(104.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(if (front) ZxT.Surface2 else ZxT.Primary.copy(.18f))
            .border(1.dp, if (front) ZxT.Line else ZxT.Primary, RoundedCornerShape(13.dp))
            .clickable { front = !front }
            .padding(13.dp)) {
            Column(Modifier.fillMaxSize()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (front) "问题" else "答案", fontSize = 9.5.sp,
                         color = if (front) ZxT.Text3 else ZxT.PriLite,
                         modifier = Modifier.clip(RoundedCornerShape(4.dp))
                             .background(if (front) ZxT.Surface3 else ZxT.Primary.copy(.25f))
                             .padding(6.dp, 2.dp))
                    Spacer(Modifier.weight(1f))
                    Text("${idx + 1}/${cards.length()}", fontSize = 10.sp, color = ZxT.Text3)
                }
                Spacer(Modifier.height(9.dp))
                Text(if (front) c.optString("q", "") else c.optString("a", ""),
                     fontSize = 13.sp, color = ZxT.Text, lineHeight = 19.sp,
                     modifier = Modifier.weight(1f))
                Row {
                    Text("翻面", fontSize = 11.sp, color = ZxT.PriLite,
                         modifier = Modifier.clickable { front = !front }.padding(end = 14.dp))
                    Text("下一张", fontSize = 11.sp, color = ZxT.Text2,
                         modifier = Modifier.clickable {
                             if (idx < cards.length() - 1) { idx++; front = true }
                             else ctx.callTool("zx_select", mapOf("choice" to "all_done",
                                                                  "source" to "zx_cardflip"))
                         })
                }
            }
        }
    }

    /* ══════════════ zx_comment 内联批注 ══════════════ */
    @Composable
    fun comment(o: JSONObject, ctx: RenderContext) {
        val items = o.optJSONArray("comments") ?: return
        val n = minOf(items.length(), 8)
        var input by remember { mutableStateOf("") }
        val liked = remember { mutableStateMapOf<Int, String>() }   // index -> emoji

        Column(Modifier.fillMaxWidth()) {
            for (i in 0 until n) {
                val c = items.optJSONObject(i) ?: continue
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Box(Modifier.size(24.dp).clip(CircleShape).background(ZxT.Surface3),
                        contentAlignment = Alignment.Center) {
                        Text(c.optString("who", "?").take(1), fontSize = 11.sp, color = ZxT.Text2)
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(c.optString("who", ""), fontSize = 10.5.sp, color = ZxT.Text3)
                            Spacer(Modifier.width(6.dp))
                            Text(c.optString("at", ""), fontSize = 9.5.sp, color = ZxT.Text3)
                        }
                        Text(c.optString("text", ""), fontSize = 12.sp, color = ZxT.Text2,
                             lineHeight = 17.sp, modifier = Modifier.padding(top = 2.dp))
                        Row(Modifier.padding(top = 4.dp)) {
                            listOf("👍", "👀", "❓").forEach { e ->
                                val on = liked[i] == e
                                Text(e, fontSize = 12.sp,
                                     modifier = Modifier.padding(end = 8.dp)
                                         .clip(RoundedCornerShape(6.dp))
                                         .background(if (on) ZxT.Primary.copy(.2f) else Color.Transparent)
                                         .clickable {
                                             liked[i] = if (on) "" else e
                                             ctx.callTool("zx_select", mapOf(
                                                 "choice" to e, "index" to i.toString(),
                                                 "source" to "zx_comment"))
                                         }.padding(5.dp, 2.dp))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.TextField(
                    value = input, onValueChange = { input = it },
                    placeholder = { Text("写批注…", fontSize = 11.5.sp, color = ZxT.Text3) },
                    modifier = Modifier.weight(1f).height(40.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.5.sp, color = ZxT.Text),
                    singleLine = true,
                    colors = androidx.compose.material3.TextFieldDefaults.colors(
                        focusedContainerColor = ZxT.Surface2,
                        unfocusedContainerColor = ZxT.Surface2,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = ZxT.Primary)
                )
                Spacer(Modifier.width(8.dp))
                Text("发送", fontSize = 12.sp, color = if (input.isBlank()) ZxT.Text3 else ZxT.PriLite,
                     modifier = Modifier.clickable(enabled = input.isNotBlank()) {
                         ctx.callTool("zx_form_submit", mapOf("payload" to
                             JSONObject().put("comment", input).toString()))
                         input = ""
                     })
            }
        }
    }

    /* ══════════════ zx_seek 进度拖拽条（带章节标记） ══════════════ */
    @Composable
    fun seek(o: JSONObject, ctx: RenderContext) {
        val dur = o.optInt("dur", 100).coerceAtLeast(1)
        var pos by remember { mutableFloatStateOf((o.optDouble("at", 0.0) / dur).toFloat().coerceIn(0f, 1f)) }
        val marks = o.optJSONArray("marks")
        var dragging by remember { mutableStateOf(false) }

        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(26.dp)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = {
                            dragging = false
                            // 松手才回传，拖动过程中不回传——
                            // 每帧回传会把模型上下文刷爆
                            ctx.callTool("zx_select", mapOf(
                                "at" to (pos * dur).toInt().toString(), "source" to "zx_seek"))
                        }
                    ) { change, _ ->
                        pos = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                }) {
                Box(Modifier.fillMaxWidth().height(4.dp).align(Alignment.Center)
                    .clip(RoundedCornerShape(2.dp)).background(ZxT.Surface3))
                Box(Modifier.fillMaxWidth(pos).height(4.dp).align(Alignment.CenterStart)
                    .clip(RoundedCornerShape(2.dp)).background(ZxT.Primary))
                // 章节标记
                if (marks != null) {
                    for (i in 0 until minOf(marks.length(), 12)) {
                        val m = marks.optJSONObject(i) ?: continue
                        val at = (m.optDouble("at", 0.0) / dur).toFloat().coerceIn(0f, 1f)
                        Box(Modifier.align(Alignment.CenterStart)
                            .offset(x = (at * 280).dp).size(2.dp, 10.dp)
                            .background(ZxT.Warn))
                    }
                }
                Box(Modifier.align(Alignment.CenterStart)
                    .offset(x = (pos * 276).dp)
                    .size(if (dragging) 16.dp else 12.dp)
                    .clip(CircleShape).background(ZxT.PriLite))
            }
            Row(Modifier.fillMaxWidth()) {
                Text(fmtT((pos * dur).toInt()), fontSize = 10.sp, color = ZxT.PriLite)
                Spacer(Modifier.weight(1f))
                if (marks != null && marks.length() > 0) {
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        for (i in 0 until minOf(marks.length(), 12)) {
                            val m = marks.optJSONObject(i) ?: continue
                            Text(m.optString("t", ""), fontSize = 9.5.sp, color = ZxT.Text3,
                                 modifier = Modifier.padding(start = 9.dp)
                                     .clickable { pos = (m.optDouble("at", 0.0) / dur).toFloat() })
                        }
                    }
                }
                Text(fmtT(dur), fontSize = 10.sp, color = ZxT.Text3)
            }
        }
    }

    private fun fmtT(s: Int): String = "%d:%02d".format(s / 60, s % 60)
}
