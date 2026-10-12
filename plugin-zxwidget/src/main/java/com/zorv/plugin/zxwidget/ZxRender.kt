package com.zorv.plugin.zxwidget

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/**
 * 气泡内组件的 Compose 渲染器。
 *
 * 三条硬约束：
 * 1. 不自己起 Activity —— 卡片长在聊天气泡里，宿主提供组合环境
 * 2. 不阻塞主线程 —— 交互回传走 callback / tool_call，由宿主 ReAct 循环接管
 * 3. 颜色只用 QuroUiColorToken —— 自动跟随宿主深色模式反转
 */

// ── Token：与宿主 quro-ui 对齐，不要自造颜色 ──────────────────────
private object ZxToken {
    val Surface   = Color(0xFF181B21)
    val Surface2  = Color(0xFF21252D)
    val Surface3  = Color(0xFF2B303A)
    val Line      = Color(0xFF262A33)
    val Text      = Color(0xFFEDEFF4)
    val Text2     = Color(0xFFA2A9B8)
    val Text3     = Color(0xFF6B7280)
    val Primary   = Color(0xFF5046E4)   // ZorvAI primary 靛蓝
    val Ok        = Color(0xFF3FBF7F)
    val Warn      = Color(0xFFE8A33D)
    val Radius    = 16.dp
}

/** 渲染层出口：ChatCardExtension 回调进这里 */
object ZxRender {

    @Composable
    fun compare(data: JSONObject, ctx: RenderContext): RenderedCard {
        val cols = ZxDsl.columns(data)
        val rows = ZxDsl.flattenRows(data)
        var dim by remember { mutableIntStateOf(0) }
        var picked by remember { mutableIntStateOf(-1) }

        CardShell(title = data.optString("title", "对比"), sub = "zx_compare") {
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    cols.forEachIndexed { i, c ->
                        SegChip(c, i == dim) { dim = i; }
                    }
                }
                Spacer(Modifier.height(10.dp))
                rows.forEach { (k, vs) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                        Text(k, fontSize = 11.5.sp, color = ZxToken.Text3,
                             modifier = Modifier.width(62.dp))
                        vs.forEach { v ->
                            Text(v, fontSize = 12.5.sp, color = ZxToken.Text,
                                 modifier = Modifier.weight(1f))
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text("选定", fontSize = 11.5.sp, color = ZxToken.Text3,
                         modifier = Modifier.width(62.dp))
                    cols.forEachIndexed { i, _ ->
                        Box(
                            Modifier.weight(1f).size(26.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.5.dp, ZxToken.Line, RoundedCornerShape(8.dp))
                                .background(if (picked == i) ZxToken.Ok else Color.Transparent)
                                .clickable {
                                    picked = i
                                    // 回传：走宿主 tool_call，模型下一轮直接拿到结果
                                    ctx.callTool("zx_select", mapOf(
                                        "choice" to cols[i], "source" to "zx_compare"
                                    ))
                                },
                            contentAlignment = Alignment.Center
                        ) { if (picked == i) Text("✓", fontSize = 11.sp, color = Color.Black) }
                    }
                }
            }
        }
        return RenderedCard(title = data.optString("title", "对比"), body = data.toString())
    }

    @Composable
    fun form(data: JSONObject, ctx: RenderContext): RenderedCard {
        val fields = data.optJSONArray("fields") ?: return RenderedCard("表单", "")
        // {{id}} 同源取值：所有字段的状态集中在这里，提交时一次性打包
        val state = remember { mutableStateMapOf<String, String>() }
        var valid by remember { mutableStateOf(false) }

        CardShell(title = data.optString("title", "补充参数"), sub = "zx_form") {
            Column {
                for (i in 0 until fields.length()) {
                    val f = fields.optJSONObject(i) ?: continue
                    val id = f.optString("id")
                    FieldLabel(f.optString("id"))
                    when (f.optString("kind")) {
                        "segment" -> {
                            val opts = f.optJSONArray("options") ?: continue
                            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(6.dp)) {
                                for (j in 0 until opts.length()) {
                                    val o = opts.optString(j)
                                    SegChip(o, state[id] == o, Modifier.weight(1f)) {
                                        state[id] = o; valid = true
                                    }
                                }
                            }
                        }
                        "slider" -> {
                            val min = f.optDouble("min", 0.0).toFloat()
                            val max = f.optDouble("max", 100.0).toFloat()
                            var v by remember { mutableFloatStateOf((min + max) / 2) }
                            // Compose 侧不引 Slider 依赖，用自绘轨道保持插件轻
                            TrackSlider(
                                value = v, min = min, max = max,
                                onValue = { v = it; state[id] = it.toInt().toString() }
                            )
                            Text("¥${v.toInt()}", fontSize = 12.sp, color = ZxToken.Text)
                        }
                        "text" -> {
                            var t by remember { mutableStateOf(state[id] ?: "") }
                            PlainInput(t) { t = it; state[id] = it; valid = it.isNotBlank() }
                        }
                        "check" -> {
                            val opts = f.optJSONArray("options") ?: continue
                            val sel = remember { mutableStateListOf<String>() }
                            Column {
                                for (j in 0 until opts.length()) {
                                    val o = opts.optString(j)
                                    Row(Modifier.fillMaxWidth().clickable {
                                        if (sel.contains(o)) sel.remove(o) else sel.add(o)
                                        state[id] = sel.joinToString(",")
                                    }.padding(vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        CheckBox(sel.contains(o))
                                        Spacer(Modifier.width(9.dp))
                                        Text(o, fontSize = 12.5.sp, color = ZxToken.Text2)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                // 提交：打包成 payload → tool_call，模型拿到的是可直接用的参数集
                Box(
                    Modifier.fillMaxWidth().height(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (valid) ZxToken.Primary else ZxToken.Surface3)
                        .clickable(enabled = valid) {
                            ctx.callTool("zx_form_submit", mapOf(
                                "payload" to JSONObject(state.toMap()).toString()
                            ))
                        },
                    contentAlignment = Alignment.Center
                ) { Text("提交给 AI", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Color.White) }
            }
        }
        return RenderedCard(title = data.optString("title", "表单"), body = data.toString())
    }

    @Composable
    fun timeline(data: JSONObject, ctx: RenderContext): RenderedCard {
        // bind=agent.run_state：由宿主执行器推送进度，这里只做状态映射
        val steps = data.optJSONArray("steps") ?: return RenderedCard("执行中", "")
        val doneN = (0 until steps.length()).count { i ->
            steps.optJSONObject(i)?.optString("st", "") in setOf("done", "ok", "完成") }

        CardShell(title = "执行中", sub = "zx_timeline", right = "$doneN/${steps.length()}") {
            Column {
                for (i in 0 until steps.length()) {
                    val s = steps.optJSONObject(i) ?: continue
                    val st = when { i < doneN -> StepState.DONE; i == doneN -> StepState.RUN; else -> StepState.IDLE }
                    RunRow(s.optString("n"), s.optString("st"), st)
                }
                val pct by animateFloatAsState(
                    targetValue = doneN.toFloat() / steps.length().coerceAtLeast(1),
                    animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessLow),
                    label = "pct"
                )
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                    .background(ZxToken.Surface3)) {
                    Box(Modifier.fillMaxWidth(pct).fillMaxHeight()
                        .background(Brush.horizontalGradient(listOf(ZxToken.Primary, Color(0xFF8E86FF)))))
                }
            }
        }
        return RenderedCard(title = "执行中", body = data.toString())
    }

    @Composable
    fun choice(data: JSONObject, ctx: RenderContext): RenderedCard {
        val opts = data.optJSONArray("options") ?: return RenderedCard("建议", "")
        var sel by remember { mutableStateOf<String?>(null) }

        CardShell(title = data.optString("title", "我的建议"), sub = "zx_choice") {
            Column {
                for (i in 0 until opts.length()) {
                    val o = opts.optJSONObject(i) ?: continue
                    val k = o.optString("key")
                    val on = sel == k
                    Column(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, if (on) ZxToken.Primary else ZxToken.Line,
                                    RoundedCornerShape(12.dp))
                            .background(if (on) ZxToken.Primary.copy(.16f) else Color.Transparent)
                            .clickable {
                                sel = k
                                ctx.callTool("zx_select", mapOf("choice" to k, "source" to "zx_choice"))
                            }
                            .padding(11.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(o.optString("t"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                 color = ZxToken.Text)
                            if (o.optBoolean("rec")) {
                                Spacer(Modifier.width(7.dp))
                                Text("推荐", fontSize = 10.sp, color = ZxToken.Ok,
                                     modifier = Modifier.clip(RoundedCornerShape(5.dp))
                                         .background(ZxToken.Ok.copy(.15f)).padding(2.dp, 5.dp))
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(o.optString("d"), fontSize = 11.5.sp, color = ZxToken.Text3, lineHeight = 16.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        return RenderedCard(title = data.optString("title", "建议"), body = data.toString())
    }

    /* ── UI_WIDGET：更细粒度的内联控件 ─────────────────────────── */
    @Composable
    fun pickButton(data: JSONObject, ctx: RenderContext): RenderedCard {
        var on by remember { mutableStateOf(false) }
        Box(Modifier.size(26.dp).clip(RoundedCornerShape(8.dp))
            .border(1.5.dp, ZxToken.Line, RoundedCornerShape(8.dp))
            .background(if (on) ZxToken.Ok else Color.Transparent)
            .clickable {
                on = !on
                ctx.callTool("zx_select", mapOf("choice" to data.optString("key"), "source" to "zx_widget"))
            }, contentAlignment = Alignment.Center) {
            if (on) Text("✓", fontSize = 11.sp, color = Color.Black)
        }
        return RenderedCard("", "")
    }

    @Composable
    fun slider(data: JSONObject, ctx: RenderContext): RenderedCard {
        var v by remember { mutableFloatStateOf(data.optDouble("value", 0.0).toFloat()) }
        TrackSlider(v, data.optDouble("min", 0.0).toFloat(), data.optDouble("max", 100.0).toFloat()) {
            v = it
            ctx.callTool("zx_form_submit", mapOf("payload" to """{"${data.optString("id")}":$it}"""))
        }
        return RenderedCard("", "")
    }
}

/* ── 卡片外壳：统一的头 / 体 / 状态行 ─────────────────────────── */
@Composable
private fun CardShell(
    title: String, sub: String, right: String = "",
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        color = ZxToken.Surface,
        shape = RoundedCornerShape(ZxToken.Radius),
        modifier = Modifier.fillMaxWidth().border(1.dp, ZxToken.Line, RoundedCornerShape(ZxToken.Radius))
    ) {
        Column {
            Row(Modifier.fillMaxWidth().padding(10.dp, 13.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(24.dp).clip(RoundedCornerShape(8.dp))
                    .background(ZxToken.Primary.copy(.16f)), contentAlignment = Alignment.Center) {
                    Text("◆", fontSize = 10.sp, color = Color(0xFF8E86FF))
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = ZxToken.Text)
                    Text(sub, fontSize = 11.sp, color = ZxToken.Text3)
                }
                if (right.isNotBlank())
                    Text(right, fontSize = 11.sp, color = ZxToken.Text3)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(ZxToken.Line))
            Column(Modifier.padding(12.dp, 13.dp), content = content)
        }
    }
}

/* ── 控件原子 ───────────────────────────────────────────────── */
@Composable
private fun SegChip(label: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.height(30.dp).clip(RoundedCornerShape(9.dp))
        .border(1.dp, if (on) ZxToken.Primary else ZxToken.Line, RoundedCornerShape(9.dp))
        .background(if (on) ZxToken.Primary else Color.Transparent)
        .clickable(onClick = onClick).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center) {
        Text(label, fontSize = 11.5.sp,
             color = if (on) Color.White else ZxToken.Text2,
             fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun FieldLabel(id: String) {
    Text(id, fontSize = 12.sp, color = ZxToken.Text2, modifier = Modifier.padding(bottom = 7.dp))
}

@Composable
private fun CheckBox(on: Boolean) {
    Box(Modifier.size(19.dp).clip(RoundedCornerShape(6.dp))
        .border(1.5.dp, ZxToken.Line, RoundedCornerShape(6.dp))
        .background(if (on) ZxToken.Primary else Color.Transparent),
        contentAlignment = Alignment.Center) {
        if (on) Text("✓", fontSize = 11.sp, color = Color.White)
    }
}

/** 自绘轨道滑块：避免在插件里拖入完整 Slider 依赖 */
@Composable
private fun TrackSlider(value: Float, min: Float, max: Float, onValue: (Float) -> Unit) {
    val pct = ((value - min) / (max - min).coerceAtLeast(1f)).coerceIn(0f, 1f)
    Box(Modifier.fillMaxWidth().height(20.dp), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(ZxToken.Surface3))
        Box(Modifier.fillMaxWidth(pct).height(4.dp).clip(RoundedCornerShape(2.dp)).background(ZxToken.Primary))
        Box(Modifier.offset(x = (pct * 340).dp).size(18.dp).clip(RoundedCornerShape(9.dp))
            .background(ZxToken.Primary).border(3.dp, ZxToken.Surface, RoundedCornerShape(9.dp)))
    }
}

@Composable
private fun PlainInput(value: String, onChange: (String) -> Unit) {
    // 宿主已提供输入实现时替换为 QuroTextField，这里保持最小可用
    Box(Modifier.fillMaxWidth().height(38.dp).clip(RoundedCornerShape(11.dp))
        .border(1.dp, ZxToken.Line, RoundedCornerShape(11.dp)).background(ZxToken.Surface2)
        .padding(horizontal = 11.dp), contentAlignment = Alignment.CenterStart) {
        Text(value.ifBlank { "请输入" }, fontSize = 13.sp,
             color = if (value.isBlank()) ZxToken.Text3 else ZxToken.Text)
    }
}

private enum class StepState { IDLE, RUN, DONE }

@Composable
private fun RunRow(name: String, sub: String, state: StepState) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        val (bg, fg) = when (state) {
            StepState.DONE -> ZxToken.Ok.copy(.16f) to ZxToken.Ok
            StepState.RUN  -> ZxToken.Primary.copy(.16f) to Color(0xFF8E86FF)
            else           -> ZxToken.Surface3 to ZxToken.Text3
        }
        Box(Modifier.size(22.dp).clip(RoundedCornerShape(11.dp)).background(bg),
            contentAlignment = Alignment.Center) {
            Text(if (state == StepState.DONE) "✓" else "·", fontSize = 11.sp, color = fg)
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(name, fontSize = 12.5.sp,
                 color = if (state == StepState.DONE) ZxToken.Text2 else ZxToken.Text)
            if (sub.isNotBlank()) Text(sub, fontSize = 11.sp, color = ZxToken.Text3)
        }
    }
}
