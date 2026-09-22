package com.ai.assistance.quro.genui.app.render

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.genui.app.ui.theme.GenTheme
import org.json.JSONArray
import org.json.JSONObject

/**
 * Compose 描述渲染器 —— 把 AI 写的 JSON 组件树映射为**真实的 Compose 组件**。
 *
 * 设计取舍（为什么是 JSON 而不是让 AI 写 Kotlin 源码）：
 * 端上无法编译 AI 现写的 Kotlin（需要 50MB+ 编译器，且 Android 禁止运行时加载新 dex）。
 * 但"声明式 UI"的本质是**组件树 + 属性**，这完全可以数据化：
 * AI 描述 {type:"Column", children:[{type:"Text", text:"标题"}]}，
 * 端上用 Compose 把同一棵树**真渲染**出来——用户看到的就是货真价实的 Compose 界面
 * （Material 3 主题、真实的水波纹、真实的布局语义），而不是网页仿真。
 *
 * 组件与属性见 [componentHelp]，该文本同时注入提示词——两处一处生成，不会脱节。
 */
object ComposeDescRenderer {

    /** 支持的组件与其属性说明（提示词由它派生） */
    val componentHelp: String = """
Column / Row / Box  布局容器：children[] 子组件；属性 gap(间距dp) / padding /
                    fillMaxWidth(bool) / background(#hex) / align(center|start|end) /
                    scroll(bool，纵向滚动) / horizontalScroll(bool)
                    Column/Row 特有：arrangement(spaceBetween|center|end)
LazyColumn          长列表：items[] 每项是组件树；属性 gap / padding
Text                文本：text，style(headlineLarge|headlineMedium|titleLarge|titleMedium|
                    bodyLarge|bodyMedium|labelLarge)，color(#hex)，align(center|start|end)，
                    weight(bold|medium)，maxLines(int)
Button              按钮：text，action(点击事件名，回传给你)，color(#hex)，outlined(bool)
Card                卡片：children[]；属性 padding / background / radius(dp) / outlined(bool)
Divider             分割线：无属性
Spacer              间距：属性 size(dp) 或 height/width(dp)
Icon                图标：name(star|home|search|settings|add|delete|favorite|check|close|
                    arrow_back|arrow_forward|more|menu|person|notifications|edit|share|info|warning)
Badge               徽标：text / color
ProgressBar         进度：value(0..1)，或 indeterminate(true)
TextField           输入框：label，value(初值)，action(提交事件名)，placeholder
Switch              开关：checked(bool)，label，action
Slider              滑杆：min / max / value / action
Chip                标签：text / selected(bool) / action
""".trimIndent()

    /**
     * 渲染整棵组件树。
     * @param onAction 组件事件回调（action 名 → 回传给 AI 页面）
     */
    @Composable
    fun Render(
        desc: String,
        onAction: (name: String) -> Unit = {}
    ) {
        val tree = remember(desc) {
            runCatching { JSONObject(desc) }.getOrNull()
        }
        if (tree == null) {
            Text(
                "⚠️ Compose 描述解析失败",
                color = GenTheme.Red, fontSize = 13.sp,
                modifier = Modifier.padding(16.dp)
            )
            return
        }
        Node(tree, onAction)
    }

    /** 递归渲染一个组件节点 */
    @Composable
    private fun Node(o: JSONObject, onAction: (String) -> Unit) {
        val type = o.optString("type").ifBlank { "Column" }
        when (type) {
            "Column" -> Container(o, onAction, vertical = true)
            "Row" -> Container(o, onAction, vertical = false)
            "Box" -> BoxContainer(o, onAction)
            "LazyColumn" -> LazyContainer(o, onAction)
            "Text" -> TextNode(o)
            "Button" -> ButtonNode(o, onAction)
            "Card" -> CardNode(o, onAction)
            "Divider" -> HorizontalDivider(color = GenTheme.Line, modifier = Modifier.padding(vertical = 6.dp))
            "Spacer" -> SpacerNode(o)
            "Icon" -> IconNode(o)
            "Badge" -> BadgeNode(o)
            "ProgressBar" -> ProgressNode(o)
            "TextField" -> TextFieldNode(o, onAction)
            "Switch" -> SwitchNode(o, onAction)
            "Slider" -> SliderNode(o, onAction)
            "Chip" -> ChipNode(o, onAction)
            // —— 未知组件：尽力而为 ——
            // AI 可以自由发明组件名。端上不拒绝，而是按"有没有 children"猜它是
            // 容器还是文本：有 children 就当 Column 铺开，没有就当文本显示它的 text。
            // 这样 AI 的想象力不被端上限制，同时也不会渲染出一片空白。
            else -> {
                val kids = o.optJSONArray("children")
                if (kids != null && kids.length() > 0) {
                    Container(o, onAction, vertical = true)
                } else {
                    val label = o.optString("text").ifBlank { o.optString("label") }
                    if (label.isNotBlank()) TextNode(o) else Text(
                        type, color = GenTheme.Dim, fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
    }

    // ---------- 容器 ----------

    @Composable
    private fun ColumnScopeOrRow(
        o: JSONObject,
        onAction: (String) -> Unit,
        vertical: Boolean
    ) {
        val children = o.optJSONArray("children") ?: JSONArray()
        for (i in 0 until children.length()) {
            val c = children.optJSONObject(i) ?: continue
            val gap = c.optInt("gapTop", 0)
            if (gap > 0) Spacer(Modifier.height(gap.dp))
            Node(c, onAction)
        }
    }

    @Composable
    private fun Container(o: JSONObject, onAction: (String) -> Unit, vertical: Boolean) {
        val gap = o.optInt("gap", 10)
        val padding = o.optInt("padding", 0)
        val mode = o.optString("arrangement")
        val hAlign = horizontalOf(o.optString("align"))
        val vAlign = verticalOf(o.optString("align"))

        // 注意类型：Modifier 的扩展链返回 Modifier，必须显式声明为 Modifier
        // （写成 `var m = Modifier` 会被推断为 Modifier.Companion，再 reassign 就报类型不符）
        var m: Modifier = Modifier
        if (padding > 0) m = m.padding(padding.dp)
        if (o.optBoolean("fillMaxWidth")) m = m.fillMaxWidth()
        if (o.optBoolean("scroll") && vertical) m = m.verticalScroll(rememberScrollState())
        if (o.optBoolean("horizontalScroll") && !vertical) m = m.horizontalScroll(rememberScrollState())
        bgModifier(o)?.let { m = m.then(it) }

        // Column 与 Row 的 arrangement 是不同类型，必须分别构造。
        // API 细节：Arrangement.Center / SpaceBetween 的静态类型是 HorizontalOrVertical，
        // 两者通用；而 spacedBy(d) 返回横向版，纵向要传 spacedBy(d, Alignment.Top)。
        if (vertical) {
            val vArr: Arrangement.Vertical = when (mode) {
                "center" -> Arrangement.Center
                "spaceBetween" -> Arrangement.SpaceBetween
                "end" -> Arrangement.Bottom
                else -> Arrangement.spacedBy(gap.dp, Alignment.Top)
            }
            Column(m, horizontalAlignment = hAlign, verticalArrangement = vArr) {
                ColumnScopeOrRow(o, onAction, true)
            }
        } else {
            val hArr: Arrangement.Horizontal = when (mode) {
                "center" -> Arrangement.Center
                "spaceBetween" -> Arrangement.SpaceBetween
                "end" -> Arrangement.End
                else -> Arrangement.spacedBy(gap.dp, Alignment.Start)
            }
            Row(m, verticalAlignment = vAlign, horizontalArrangement = hArr) {
                ColumnScopeOrRow(o, onAction, false)
            }
        }
    }

    @Composable
    private fun BoxContainer(o: JSONObject, onAction: (String) -> Unit) {
        var m: Modifier = Modifier
        val padding = o.optInt("padding", 0)
        if (padding > 0) m = m.padding(padding.dp)
        if (o.optBoolean("fillMaxWidth")) m = m.fillMaxWidth()
        bgModifier(o)?.let { m = m.then(it) }
        // Box 的 contentAlignment 是双向的；"center" 居中，其余默认左上
        val boxAlign = if (o.optString("align") == "center") Alignment.Center else Alignment.TopStart
        Box(m, contentAlignment = boxAlign) {
            val children = o.optJSONArray("children") ?: JSONArray()
            for (i in 0 until children.length()) {
                children.optJSONObject(i)?.let { Node(it, onAction) }
            }
        }
    }

    @Composable
    private fun LazyContainer(o: JSONObject, onAction: (String) -> Unit) {
        val items = o.optJSONArray("items") ?: JSONArray()
        val data = remember(items.toString()) {
            (0 until items.length()).mapNotNull { items.optJSONObject(it) }
        }
        val padding = o.optInt("padding", 0)
        val gap = o.optInt("gap", 8)
        LazyColumn(
            modifier = if (padding > 0) Modifier.padding(padding.dp) else Modifier,
            verticalArrangement = Arrangement.spacedBy(gap.dp)
        ) {
            items(data) { item -> Node(item, onAction) }
        }
    }

    // ---------- 叶子组件 ----------

    @Composable
    private fun TextNode(o: JSONObject) {
        val style = o.optString("style", "bodyMedium")
        val (size, weight) = when (style) {
            "headlineLarge" -> 30.sp to FontWeight.Bold
            "headlineMedium" -> 24.sp to FontWeight.Bold
            "titleLarge" -> 20.sp to FontWeight.SemiBold
            "titleMedium" -> 16.sp to FontWeight.SemiBold
            "labelLarge" -> 13.sp to FontWeight.Medium
            else -> 14.sp to FontWeight.Normal
        }
        val w = when (o.optString("weight")) {
            "bold" -> FontWeight.Bold
            "medium" -> FontWeight.Medium
            else -> weight
        }
        var m: Modifier = Modifier
        val align = o.optString("align")
        if (o.optBoolean("fillMaxWidth") || align.isNotBlank()) m = m.fillMaxWidth()
        if (o.optInt("padding", 0) > 0) m = m.padding(o.optInt("padding").dp)

        Text(
            text = o.optString("text"),
            modifier = m,
            color = o.optString("color").takeIf { it.startsWith("#") }?.let { colorOf(it) } ?: GenTheme.Text,
            fontSize = size,
            fontWeight = w,
            maxLines = o.optInt("maxLines", Int.MAX_VALUE),
            textAlign = when (align) {
                "center" -> TextAlign.Center
                "end" -> TextAlign.End
                else -> TextAlign.Start
            }
        )
    }

    @Composable
    private fun ButtonNode(o: JSONObject, onAction: (String) -> Unit) {
        val action = o.optString("action")
        val color = o.optString("color").takeIf { it.startsWith("#") }?.let { colorOf(it) } ?: GenTheme.Amber
        val m = Modifier.let { if (o.optBoolean("fillMaxWidth")) it.fillMaxWidth() else it }
        if (o.optBoolean("outlined")) {
            OutlinedButton(
                onClick = { if (action.isNotBlank()) onAction(action) },
                modifier = m,
                shape = RoundedCornerShape(12.dp)
            ) { Text(o.optString("text", "按钮"), color = color, fontSize = 14.sp) }
        } else {
            Button(
                onClick = { if (action.isNotBlank()) onAction(action) },
                modifier = m,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = color)
            ) {
                Text(
                    o.optString("text", "按钮"),
                    color = Color(0xFF0F1115), fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                )
            }
        }
    }

    @Composable
    private fun CardNode(o: JSONObject, onAction: (String) -> Unit) {
        val bg = o.optString("background").takeIf { it.startsWith("#") }?.let { colorOf(it) } ?: GenTheme.Panel
        var m: Modifier = Modifier.fillMaxWidth()
        if (o.optInt("padding", 0) > 0) m = m.padding(o.optInt("padding").dp)
        Surface(
            modifier = m,
            shape = RoundedCornerShape(o.optInt("radius", 16).dp),
            color = bg,
            border = if (o.optBoolean("outlined")) androidx.compose.foundation.BorderStroke(1.dp, GenTheme.Line) else null
        ) {
            Column(Modifier.padding(14.dp)) {
                val children = o.optJSONArray("children") ?: JSONArray()
                for (i in 0 until children.length()) {
                    val c = children.optJSONObject(i) ?: continue
                    if (i > 0) Spacer(Modifier.height(8.dp))
                    Node(c, onAction)
                }
            }
        }
    }

    @Composable
    private fun SpacerNode(o: JSONObject) {
        val s = o.optInt("size", 0)
        val h = o.optInt("height", s)
        val w = o.optInt("width", s)
        Spacer(Modifier.height(h.dp).width(w.dp))
    }

    @Composable
    private fun IconNode(o: JSONObject) {
        // 自绘图标：不依赖 material-icons-extended（那是数百 KB 的额外依赖），
        // 用 Unicode 符号 + 主题色表达，视觉一致且零成本。
        val glyph = when (o.optString("name")) {
            "star" -> "★"; "home" -> "⌂"; "search" -> "⌕"; "settings" -> "⚙"
            "add" -> "＋"; "delete" -> "🗑"; "favorite" -> "♥"; "check" -> "✓"
            "close" -> "✕"; "arrow_back" -> "←"; "arrow_forward" -> "→"
            "more" -> "⋯"; "menu" -> "≡"; "person" -> "☺"; "notifications" -> "🔔"
            "edit" -> "✎"; "share" -> "↗"; "info" -> "ⓘ"; "warning" -> "⚠"
            else -> "•"
        }
        Text(
            glyph,
            color = o.optString("color").takeIf { it.startsWith("#") }?.let { colorOf(it) } ?: GenTheme.Amber,
            fontSize = o.optInt("size", 20).sp
        )
    }

    @Composable
    private fun BadgeNode(o: JSONObject) {
        val c = o.optString("color").takeIf { it.startsWith("#") }?.let { colorOf(it) } ?: GenTheme.Amber
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = c.copy(alpha = 0.15f)
        ) {
            Text(
                o.optString("text", "标签"),
                color = c, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }

    @Composable
    private fun ProgressNode(o: JSONObject) {
        if (o.optBoolean("indeterminate")) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = GenTheme.Amber,
                trackColor = GenTheme.Panel
            )
        } else {
            LinearProgressIndicator(
                progress = { o.optDouble("value", 0.0).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = GenTheme.Amber,
                trackColor = GenTheme.Panel
            )
        }
    }

    @Composable
    private fun TextFieldNode(o: JSONObject, onAction: (String) -> Unit) {
        val action = o.optString("action")
        var v by remember { mutableStateOf(o.optString("value")) }
        Column {
            if (o.optString("label").isNotBlank()) {
                Text(o.optString("label"), color = GenTheme.Dim, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
            }
            OutlinedTextField(
                value = v,
                onValueChange = { v = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(o.optString("placeholder"), color = GenTheme.Dim, fontSize = 13.sp) },
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = GenTheme.Text, unfocusedTextColor = GenTheme.Text,
                    focusedBorderColor = GenTheme.Amber, unfocusedBorderColor = GenTheme.Line
                )
            )
            // 有 action 才给提交按钮：提交时把当前值一并回传（action:value）
            if (action.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { onAction("$action:$v") }, shape = RoundedCornerShape(10.dp)) {
                    Text("提交", color = GenTheme.Amber, fontSize = 13.sp)
                }
            }
        }
    }

    @Composable
    private fun SwitchNode(o: JSONObject, onAction: (String) -> Unit) {
        val action = o.optString("action")
        var checked by remember { mutableStateOf(o.optBoolean("checked")) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (o.optString("label").isNotBlank()) {
                Text(o.optString("label"), color = GenTheme.Text, fontSize = 14.sp,
                    modifier = Modifier.weight(1f))
            }
            Switch(
                checked = checked,
                onCheckedChange = {
                    checked = it
                    if (action.isNotBlank()) onAction("$action:$it")
                },
                colors = SwitchDefaults.colors(checkedTrackColor = GenTheme.Amber)
            )
        }
    }

    @Composable
    private fun SliderNode(o: JSONObject, onAction: (String) -> Unit) {
        val action = o.optString("action")
        val min = o.optDouble("min", 0.0).toFloat()
        val max = o.optDouble("max", 100.0).toFloat()
        var v by remember { mutableStateOf(o.optDouble("value", min.toDouble()).toFloat()) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = v, onValueChange = { v = it },
                valueRange = min..max,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = GenTheme.Amber, activeTrackColor = GenTheme.Amber)
            )
            Text("${v.toInt()}", color = GenTheme.Dim, fontSize = 12.sp)
        }
        if (action.isNotBlank()) {
            OutlinedButton(onClick = { onAction("$action:${v.toInt()}") }, shape = RoundedCornerShape(10.dp)) {
                Text("应用", color = GenTheme.Amber, fontSize = 13.sp)
            }
        }
    }

    @Composable
    private fun ChipNode(o: JSONObject, onAction: (String) -> Unit) {
        val action = o.optString("action")
        val selected = o.optBoolean("selected")
        val c = if (selected) GenTheme.Amber else GenTheme.Dim
        var m: Modifier = Modifier
        if (action.isNotBlank()) m = m.clickable { onAction(action) }
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = c.copy(alpha = 0.15f),
            modifier = m
        ) {
            Text(
                o.optString("text", "标签"),
                color = c, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }
    }

    /** 背景色修饰：仅当 AI 给了合法 #hex 时生效 */
    private fun bgModifier(o: JSONObject): Modifier? {
        val hex = o.optString("background")
        if (!hex.startsWith("#")) return null
        val c = colorOf(hex) ?: return null
        return Modifier.background(c, RoundedCornerShape(o.optInt("radius", 0).dp))
    }

    private fun horizontalOf(a: String): Alignment.Horizontal = when (a) {
        "center" -> Alignment.CenterHorizontally
        "end" -> Alignment.End
        else -> Alignment.Start
    }

    private fun verticalOf(a: String): Alignment.Vertical = when (a) {
        "center" -> Alignment.CenterVertically
        "bottom" -> Alignment.Bottom
        else -> Alignment.Top
    }

    private fun colorOf(hex: String): Color? = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull()
}
