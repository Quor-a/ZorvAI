package com.ai.assistance.quro.genui.app.ui.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.genui.app.ui.theme.GenTheme
import org.json.JSONArray
import org.json.JSONObject

/**
 * 原生组件渲染器 —— A2UI 的"原生桥"（参考 ZorvAI 的 quro-ui / ui_widget 思路）。
 *
 * AI 的 HTML 页面通过 MoBridge.ui.widget(kind, data) 唤起，
 * 这里用 Compose BottomSheet 渲染【真实原生控件】（不是 HTML）：
 *   stat / bar / line / progress / list / form / slider / timeline
 * 表单/滑杆/清单的提交结果通过 onResult 回传（由调用方经 evaluateJavascript
 * 派发 window 事件 mo:widget），形成"原生控件 ↔ AI 界面"双向联动。
 *
 * ⚠ 数据协议必须与 Prompts.kt 里教给模型的一致。历史上这里读 items/points，
 * 而提示词教模型传 data/trend，导致除 form/slider 外全部渲染空白。
 * 现在两种都读（[NativeWidgetSchema] 做归一化）。
 */
object NativeWidgetSchema {
    const val KINDS = "stat / bar / line / progress / list / form / slider / timeline"

    /** 取数组：兼容 data / items / points 三种键名 */
    fun arr(o: JSONObject, vararg keys: String): JSONArray? {
        for (k in keys) o.optJSONArray(k)?.let { return it }
        return null
    }

    /** 取数字数组：元素可能是数字，也可能是 {value: n} */
    fun nums(a: JSONArray?): List<Double> {
        if (a == null) return emptyList()
        return (0 until a.length()).map { i ->
            when (val v = a.opt(i)) {
                is Number -> v.toDouble()
                is JSONObject -> v.optDouble("value", 0.0)
                else -> a.optDouble(i, 0.0)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativeWidgetSheet(
    kind: String,
    payload: String,
    onDismiss: () -> Unit,
    onResult: (JSONObject) -> Unit
) {
    val data = runCatching { JSONObject(payload) }.getOrDefault(JSONObject())
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = GenTheme.Panel) {
        Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 26.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(widgetTitle(kind), color = GenTheme.Text, fontSize = 15.sp)
                Spacer(Modifier.weight(1f))
                Text("原生 · Compose", color = GenTheme.Amber, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            }
            // 组件自带的标题（AI 在 data.title 里给）
            val sub = data.optString("title")
            if (sub.isNotBlank() && sub != widgetTitle(kind)) {
                Spacer(Modifier.height(3.dp))
                Text(sub, color = GenTheme.Dim, fontSize = 11.sp)
            }
            Spacer(Modifier.height(14.dp))

            when (kind) {
                "stat" -> StatWidget(data)
                "bar" -> BarWidget(data)
                "line" -> LineWidget(data)
                "progress" -> ProgressWidget(data)
                "list" -> ListWidget(data, onResult = { onResult(it); onDismiss() })
                "form" -> FormWidget(data, onResult = { onResult(it); onDismiss() })
                "slider" -> SliderWidget(data, onResult = { onResult(it); onDismiss() })
                "timeline" -> TimelineWidget(data)
                else -> Text(
                    "未知组件类型：$kind\n可用：${NativeWidgetSchema.KINDS}",
                    color = GenTheme.Dim, fontSize = 12.sp, lineHeight = 18.sp
                )
            }
        }
    }
}

private fun widgetTitle(kind: String): String = when (kind) {
    "stat" -> "统计卡"
    "bar" -> "柱状图"
    "line" -> "趋势图"
    "progress" -> "进度"
    "list" -> "清单"
    "form" -> "表单"
    "slider" -> "调节"
    "timeline" -> "时间线"
    else -> "原生组件"
}

/**
 * stat：单值大数字卡。
 * 协议：{title, value, delta, trend:[n,n,…]}
 * 也兼容 {items:[{label,value}], …} 的多值写法。
 */
@Composable
private fun StatWidget(data: JSONObject) {
    val items = NativeWidgetSchema.arr(data, "items")
    if (items != null) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (i in 0 until items.length()) {
                val it = items.optJSONObject(i) ?: continue
                Row(
                    Modifier.fillMaxWidth()
                        .background(GenTheme.PanelUp, RoundedCornerShape(12.dp)).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(it.optString("label", ""), color = GenTheme.Dim, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text(it.optString("value", ""), color = GenTheme.Amber, fontSize = 22.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
        return
    }

    // 单值主卡
    Column(
        Modifier.fillMaxWidth().background(GenTheme.PanelUp, RoundedCornerShape(14.dp)).padding(18.dp)
    ) {
        Text(data.optString("title", "数值"), color = GenTheme.Dim, fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                data.optString("value", "—"),
                color = GenTheme.Text, fontSize = 32.sp, fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace
            )
            val delta = data.optString("delta")
            if (delta.isNotBlank()) {
                Spacer(Modifier.width(10.dp))
                val up = !delta.trimStart().startsWith("-")
                Text(
                    delta, color = if (up) GenTheme.Green else GenTheme.Red,
                    fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 5.dp)
                )
            }
        }
        // 迷你趋势线
        val trend = NativeWidgetSchema.nums(NativeWidgetSchema.arr(data, "trend", "data", "points"))
        if (trend.size >= 2) {
            Spacer(Modifier.height(12.dp))
            Sparkline(trend)
        }
    }
}

/** 迷你折线（无坐标轴的 sparkline，用于 stat 卡的 trend） */
@Composable
private fun Sparkline(values: List<Double>, height: Int = 44) {
    val maxV = values.maxOrNull() ?: 1.0
    val minV = values.minOrNull() ?: 0.0
    val span = (maxV - minV).takeIf { it > 0 } ?: 1.0
    Canvas(Modifier.fillMaxWidth().height(height.dp)) {
        if (values.size < 2) return@Canvas
        val step = size.width / (values.size - 1)
        fun y(v: Double) = size.height * (1f - ((v - minV) / span).toFloat() * 0.86f - 0.07f)
        val path = Path()
        values.forEachIndexed { i, v -> if (i == 0) path.moveTo(0f, y(v)) else path.lineTo(i * step, y(v)) }
        drawPath(path, color = GenTheme.Amber, style = Stroke(width = 4f))
        drawCircle(color = GenTheme.Amber, radius = 5f, center = Offset((values.size - 1) * step, y(values.last())))
    }
}

/**
 * bar：柱状图。
 * 协议：{title, data:[{label,value}]}（也兼容 items / 裸数字数组）
 */
@Composable
private fun BarWidget(data: JSONObject) {
    val items = NativeWidgetSchema.arr(data, "data", "items")
    if (items == null || items.length() == 0) { EmptyHint("柱状图需要 data:[{label,value}]"); return }
    val labels = (0 until items.length()).map { items.optJSONObject(it)?.optString("label") ?: "${it + 1}" }
    val values = NativeWidgetSchema.nums(items)
    val maxV = (values.maxOrNull() ?: 0.0).takeIf { it > 0 } ?: 1.0
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (i in values.indices) {
            val v = values[i]
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(labels[i], color = GenTheme.Dim, fontSize = 11.sp, modifier = Modifier.width(56.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.weight(1f).height(16.dp).background(GenTheme.PanelUp, RoundedCornerShape(4.dp))) {
                    Box(
                        Modifier.fillMaxHeight()
                            .fillMaxWidth((v / maxV).toFloat().coerceIn(0.02f, 1f))
                            .background(GenTheme.Amber, RoundedCornerShape(4.dp))
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    fmtNum(v),
                    color = GenTheme.Text, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(38.dp)
                )
            }
        }
    }
}

/**
 * line：折线趋势图。
 * 协议：{title, data:[n,n,…]}（兼容 points / [{value}] 形式）
 */
@Composable
private fun LineWidget(data: JSONObject) {
    val values = NativeWidgetSchema.nums(NativeWidgetSchema.arr(data, "data", "points"))
    if (values.size < 2) { EmptyHint("折线图需要至少两个数据点：data:[n,n,…]"); return }
    val maxV = values.maxOrNull() ?: 1.0
    val minV = values.minOrNull() ?: 0.0
    val span = (maxV - minV).takeIf { it > 0 } ?: 1.0
    Column {
        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            val step = size.width / (values.size - 1)
            fun y(v: Double) = size.height * (1f - ((v - minV) / span).toFloat() * 0.85f - 0.07f)
            // 网格基线
            for (g in 0..3) {
                val gy = size.height * (0.07f + 0.85f * g / 3f)
                drawLine(GenTheme.Line, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 2f)
            }
            val path = Path()
            values.forEachIndexed { i, v -> if (i == 0) path.moveTo(0f, y(v)) else path.lineTo(i * step, y(v)) }
            drawPath(path, color = GenTheme.Amber, style = Stroke(width = 6f))
            values.forEachIndexed { i, v ->
                val c = Offset(i * step, y(v))
                drawCircle(color = GenTheme.Panel, radius = 9f, center = c)
                drawCircle(color = GenTheme.Amber, radius = 5f, center = c)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row {
            Text("最低 ${fmtNum(minV)}", color = GenTheme.Dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.weight(1f))
            Text("最高 ${fmtNum(maxV)}", color = GenTheme.Dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

/**
 * progress：进度条。
 * 协议：{title, value:0.72}（0-1 或 0-100 自动识别）/ 或 {items:[{label,value,max}]}
 */
@Composable
private fun ProgressWidget(data: JSONObject) {
    val items = NativeWidgetSchema.arr(data, "items")
    if (items != null && items.length() > 0) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (i in 0 until items.length()) {
                val it = items.optJSONObject(i) ?: continue
                ProgressBar(it.optString("label"), it.optDouble("value", 0.0), it.optDouble("max", 1.0))
            }
        }
        return
    }
    val raw = data.optDouble("value", 0.0)
    // 0-1 与 0-100 两种习惯都接受
    val ratio = if (raw > 1.0) (raw / 100.0) else raw
    ProgressBar(data.optString("title"), ratio, 1.0)
}

@Composable
private fun ProgressBar(label: String, value: Double, max: Double) {
    val pct = ((value / (max.takeIf { it > 0 } ?: 1.0)) * 100).toInt().coerceIn(0, 100)
    Column {
        Row {
            Text(label.ifBlank { "进度" }, color = GenTheme.Text, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("$pct%", color = GenTheme.Amber, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(5.dp))
        LinearProgressIndicator(
            progress = { pct / 100f },
            color = GenTheme.Amber, trackColor = GenTheme.PanelUp,
            modifier = Modifier.fillMaxWidth().height(6.dp),
        )
    }
}

/**
 * list：清单。
 * 协议：{title, data:[{text, done}]}（也兼容 items / {title} 字段名）
 * 支持勾选：点击一行切换 done，并通过 onResult 回传 {key, index, done} 给页面。
 */
@Composable
private fun ListWidget(data: JSONObject, onResult: (JSONObject) -> Unit) {
    val items = NativeWidgetSchema.arr(data, "data", "items")
    if (items == null || items.length() == 0) { EmptyHint("清单需要 data:[{text,done}]"); return }
    val key = data.optString("key", "list")
    // 本地勾选态：以传入的 done 为初值
    val checked = remember(items.length()) {
        mutableStateMapOf<Int, Boolean>().apply {
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                put(i, o.optBoolean("done", false))
            }
        }
    }
    LazyColumn(Modifier.heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val text = o.optString("text").ifBlank { o.optString("title") }
            val isDone = checked[i] ?: false
            item {
                Row(
                    Modifier.fillMaxWidth()
                        .background(GenTheme.PanelUp, RoundedCornerShape(10.dp))
                        .clickable {
                            val next = !isDone
                            checked[i] = next
                            onResult(JSONObject().put("key", key).put("index", i).put("done", next))
                        }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(16.dp)
                            .background(
                                if (isDone) GenTheme.Amber else GenTheme.Line,
                                RoundedCornerShape(4.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isDone) Text("✓", color = GenTheme.Screen, fontSize = 11.sp)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text,
                        color = if (isDone) GenTheme.Dim else GenTheme.Text,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun FormWidget(data: JSONObject, onResult: (JSONObject) -> Unit) {
    val fields = NativeWidgetSchema.arr(data, "fields") ?: run {
        EmptyHint("表单需要 fields:[{key,label,type}]"); return
    }
    val state = remember { mutableStateMapOf<String, String>() }
    // 预填 AI 给的初值
    remember(fields.length()) {
        for (i in 0 until fields.length()) {
            val f = fields.optJSONObject(i) ?: continue
            if (f.has("default") && !f.isNull("default")) {
                val dv: Any = f.opt("default") ?: ""
                state[f.optString("key", "f$i")] =
                    if (dv is Number) fmtNum(dv.toDouble()) else dv.toString()
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (i in 0 until fields.length()) {
            val f = fields.optJSONObject(i) ?: continue
            val key = f.optString("key", "f$i")
            val label = f.optString("label", key)
            val type = f.optString("type", "text")
            val unit = f.optString("unit")
            Text(
                label + (if (type == "number") " · 数字" else "") + (if (unit.isNotBlank()) " ($unit)" else ""),
                color = GenTheme.Dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace
            )
            BasicTextField(
                value = state[key] ?: "",
                onValueChange = { v ->
                    // number 类型只收数字与小数点，避免 AI 侧拿到脏数据
                    state[key] = if (type == "number") v.filter { it.isDigit() || it == '.' || it == '-' } else v
                },
                textStyle = TextStyle(
                    color = GenTheme.Text, fontSize = 13.sp,
                    fontFamily = if (type == "number") FontFamily.Monospace else FontFamily.Default
                ),
                cursorBrush = SolidColor(GenTheme.Amber),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
                    .background(GenTheme.PanelUp, RoundedCornerShape(8.dp)).padding(10.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = {
                val r = JSONObject()
                state.forEach { (k, v) -> r.put(k, v) }
                // 数字字段转成真数字，AI 侧不用再 parse
                for (i in 0 until fields.length()) {
                    val f = fields.optJSONObject(i) ?: continue
                    val k = f.optString("key", "f$i")
                    if (f.optString("type") == "number") r.optString(k).toDoubleOrNull()?.let { r.put(k, it) }
                }
                onResult(JSONObject().put("key", data.optString("key", "form")).put("values", r))
            },
            colors = ButtonDefaults.buttonColors(containerColor = GenTheme.Amber, contentColor = GenTheme.Screen),
            modifier = Modifier.fillMaxWidth()
        ) { Text(data.optString("submit", "提交"), fontSize = 13.sp) }
    }
}

@Composable
private fun SliderWidget(data: JSONObject, onResult: (JSONObject) -> Unit) {
    val min = data.optDouble("min", 0.0)
    val max = data.optDouble("max", 100.0)
    var value by remember { mutableStateOf(data.optDouble("value", (min + max) / 2).coerceIn(min, max)) }
    val key = data.optString("key", "value")
    Column {
        Row {
            Text(
                data.optString("title").ifBlank { data.optString("label", "") },
                color = GenTheme.Text, fontSize = 13.sp, modifier = Modifier.weight(1f)
            )
            Text(
                fmtNum(value) + data.optString("unit"),
                color = GenTheme.Amber, fontSize = 14.sp, fontFamily = FontFamily.Monospace
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { value = it.toDouble() },
            valueRange = min.toFloat()..max.toFloat(),
            colors = SliderDefaults.colors(thumbColor = GenTheme.Amber, activeTrackColor = GenTheme.Amber)
        )
        Button(
            onClick = { onResult(JSONObject().put("key", key).put("value", value)) },
            colors = ButtonDefaults.buttonColors(containerColor = GenTheme.Amber, contentColor = GenTheme.Screen),
            modifier = Modifier.fillMaxWidth()
        ) { Text("确认", fontSize = 13.sp) }
    }
}

/**
 * timeline：时间线。
 * 协议：{title, data:[{time,title,desc}]}（兼容 items / {text} 代替 title）
 */
@Composable
private fun TimelineWidget(data: JSONObject) {
    val items = NativeWidgetSchema.arr(data, "data", "items")
    if (items == null || items.length() == 0) { EmptyHint("时间线需要 data:[{time,title,desc}]"); return }
    Column {
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            Row {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(9.dp).background(GenTheme.Amber, CircleShape))
                    if (i < items.length() - 1)
                        Box(Modifier.width(2.dp).height(34.dp).background(GenTheme.Line))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.padding(bottom = 4.dp)) {
                    Text(it.optString("time"), color = GenTheme.Dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Text(
                        it.optString("title").ifBlank { it.optString("text") },
                        color = GenTheme.Text, fontSize = 13.sp
                    )
                    if (it.optString("desc").isNotBlank())
                        Text(it.optString("desc"), color = GenTheme.Dim, fontSize = 11.sp, lineHeight = 16.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun EmptyHint(msg: String) {
    Text(msg, color = GenTheme.Dim, fontSize = 11.sp, lineHeight = 17.sp, fontFamily = FontFamily.Monospace)
}

private fun fmtNum(v: Double): String =
    if (v % 1.0 == 0.0) v.toInt().toString() else String.format("%.1f", v)
