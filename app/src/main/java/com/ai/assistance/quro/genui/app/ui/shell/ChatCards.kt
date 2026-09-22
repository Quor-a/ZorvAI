package com.ai.assistance.quro.genui.app.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.genui.app.ui.theme.GenTheme
import org.json.JSONObject

/**
 * 对话内联数据卡（移植自 ZorvAI 的 ```quro-card 设计，Compose 原生实现）。
 * AI 在对话回复里嵌入 ```card 围栏（JSON），气泡下方全宽渲染真实数据卡。
 * 类型：stat 指标 / progress 进度 / list 清单 / bar 条形对比 / error 报错卡。
 */
object ChatCards {
    private val FENCE = Regex("(?s)```card\\s*\\n(.*?)```")

    /** 把回复切成 (是否卡片, 内容) 段序列 */
    fun split(text: String): List<Pair<Boolean, String>> {
        val out = mutableListOf<Pair<Boolean, String>>()
        var last = 0
        FENCE.findAll(text).forEach { m ->
            if (m.range.first > last) out.add(false to text.substring(last, m.range.first))
            out.add(true to m.groupValues[1].trim())
            last = m.range.last + 1
        }
        if (last < text.length) out.add(false to text.substring(last))
        return out
    }
}

@Composable
fun CardInline(json: String, onAction: (String) -> Unit = {}) {
    val o = remember(json) { runCatching { JSONObject(json) }.getOrNull() }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(GenTheme.Panel).border(0.5.dp, GenTheme.Line, RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        if (o == null) {
            Text("卡片数据无效", color = GenTheme.Red, fontSize = 11.sp)
            return@Column
        }
        // AI 自写自注册的动态可视化卡：{"use":"组件名", ...props}
        if (o.has("use")) {
            val name = o.optString("use")
            val props = JSONObject()
            for (k in o.keys()) if (k != "use") props.put(k, o.get(k))
            val expanded = com.ai.assistance.quro.genui.app.render.DynamicComponents.resolve(name, props)
            if (expanded != null) {
                com.ai.assistance.quro.genui.app.render.ComposeDescRenderer.Render(expanded, onAction = onAction)
            } else {
                Text(
                    "未注册的可视化组件：$name（先用 MoBridge.ui.component 注册）",
                    color = GenTheme.Red, fontSize = 11.sp,
                )
            }
            return@Column
        }
        when (o.optString("type")) {
            "stat" -> {
                Text(o.optString("title"), color = GenTheme.Dim, fontSize = 11.sp)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(o.optString("value"), color = GenTheme.Amber, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    val d = o.optString("delta")
                    if (d.isNotBlank()) Text(
                        "  $d",
                        color = if (d.startsWith("-")) GenTheme.Red else GenTheme.Green,
                        fontSize = 12.sp,
                    )
                }
            }
            "progress" -> {
                Text(o.optString("title"), color = GenTheme.Text, fontSize = 13.sp)
                val pct = o.optDouble("percent").coerceIn(0.0, 100.0)
                Box(
                    Modifier.fillMaxWidth().height(8.dp)
                        .clip(RoundedCornerShape(4.dp)).background(GenTheme.Screen)
                ) {
                    Box(
                        Modifier.fillMaxWidth((pct / 100.0).toFloat())
                            .fillMaxHeight().background(GenTheme.Amber)
                    )
                }
                Text("${pct.toInt()}%", color = GenTheme.Dim, fontSize = 10.sp)
            }
            "list" -> {
                Text(o.optString("title"), color = GenTheme.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                val items = o.optJSONArray("items")
                if (items != null) for (i in 0 until items.length()) {
                    val it = items.optJSONObject(i)
                    Text(
                        "• " + (it?.optString("text") ?: items.optString(i)),
                        color = GenTheme.Text, fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
            }
            "bar" -> {
                Text(o.optString("title"), color = GenTheme.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                val data = o.optJSONArray("data")
                if (data != null && data.length() > 0) {
                    val max = (0 until data.length()).maxOf { data.optJSONObject(it)?.optDouble("value") ?: 0.0 }
                    for (i in 0 until data.length()) {
                        val it = data.optJSONObject(i) ?: continue
                        val v = it.optDouble("value", 0.0)
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                it.optString("label"), color = GenTheme.Dim, fontSize = 11.sp,
                                modifier = Modifier.width(64.dp),
                            )
                            Box(
                                Modifier.weight(1f).height(10.dp)
                                    .clip(RoundedCornerShape(5.dp)).background(GenTheme.Screen)
                            ) {
                                Box(
                                    Modifier.fillMaxWidth(
                                        (if (max > 0) v / max else 0.0).toFloat().coerceIn(0f, 1f)
                                    ).fillMaxHeight().background(GenTheme.Amber)
                                )
                            }
                            Text(
                                it.optString("value"), color = GenTheme.Text, fontSize = 11.sp,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                }
            }
            "gauge" -> {
                val pct = o.optDouble("percent").coerceIn(0.0, 100.0).toFloat()
                Text(o.optString("title"), color = GenTheme.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Box(Modifier.size(96.dp).align(Alignment.CenterHorizontally)) {
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                        val sw = 10.dp.toPx()
                        drawArc(
                            color = GenTheme.Screen, startAngle = -90f, sweepAngle = 360f,
                            useCenter = false, style = androidx.compose.ui.graphics.drawscope.Stroke(sw),
                        )
                        drawArc(
                            color = GenTheme.Amber, startAngle = -90f, sweepAngle = 360f * pct / 100f,
                            useCenter = false, style = androidx.compose.ui.graphics.drawscope.Stroke(sw),
                        )
                    }
                    Text(
                        "${pct.toInt()}%", color = GenTheme.Amber, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
            "line" -> {
                Text(o.optString("title"), color = GenTheme.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                val pts = o.optJSONArray("data")
                    ?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optDouble("value") } }
                    ?: emptyList()
                if (pts.size >= 2) {
                    val lo = pts.min(); val hi = pts.max(); val span = (hi - lo).takeIf { it > 0 } ?: 1.0
                    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(64.dp)) {
                        val w = size.width; val h = size.height
                        val path = androidx.compose.ui.graphics.Path()
                        pts.forEachIndexed { idx, v ->
                            val x = w * idx / (pts.size - 1)
                            val y = (h * (1.0 - (v - lo) / span)).toFloat().coerceIn(0f, h)
                            if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(path, color = GenTheme.Amber, style = androidx.compose.ui.graphics.drawscope.Stroke(2.5.dp.toPx()))
                    }
                    Row(Modifier.fillMaxWidth()) {
                        Text("低 $lo", color = GenTheme.Dim, fontSize = 10.sp)
                        Spacer(Modifier.weight(1f))
                        Text("高 $hi", color = GenTheme.Dim, fontSize = 10.sp)
                    }
                }
            }
            "kv" -> {
                Text(o.optString("title"), color = GenTheme.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                val items = o.optJSONArray("items")
                if (items != null) for (i in 0 until items.length()) {
                    val it = items.optJSONObject(i) ?: continue
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(it.optString("k"), color = GenTheme.Dim, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text(it.optString("v"), color = GenTheme.Text, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            "error" -> Text(
                "⚠ " + o.optString("title") + " " + o.optString("reason"),
                color = GenTheme.Red, fontSize = 12.sp,
            )
            else -> Text(o.toString().take(200), color = GenTheme.Dim, fontSize = 11.sp)
        }
    }
}
