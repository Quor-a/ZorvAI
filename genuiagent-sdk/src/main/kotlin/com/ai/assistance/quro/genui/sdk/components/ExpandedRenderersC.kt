package com.ai.assistance.quro.genui.sdk.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.genui.sdk.dsl.UIComponent
import com.ai.assistance.quro.genui.sdk.render.RenderChildren
import com.ai.assistance.quro.genui.sdk.render.RenderContext
import com.ai.assistance.quro.genui.sdk.style.ColorParser
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private fun JsonObject.sC(key: String, def: String? = null): String? = (this[key] as? JsonPrimitive)?.content ?: def
private fun JsonObject.iC(key: String, def: Int = 0): Int = (this[key] as? JsonPrimitive)?.content?.toIntOrNull() ?: def
private fun JsonObject.bC(key: String, def: Boolean = false): Boolean = (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: def
private fun JsonObject.cC(key: String, def: Color): Color = ColorParser.toColor(sC(key), def)
private fun JsonObject.aC(key: String): List<String> = ((this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }) ?: emptyList()

/** dev_env_card — 开发环境信息卡 */
@Composable
fun DevEnvCardRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    Column(Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF0F172A)).padding(12.dp)) {
        Text("🛠 ${p.sC("name", "开发环境") ?: "开发环境"}", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold, fontSize = 13.sp)
        listOf("JDK" to p.sC("jdk", "17"), "Gradle" to p.sC("gradle", "8.7"), "AGP" to p.sC("agp", "8.5.2")).forEach { (k, v) ->
            Row(Modifier.padding(top = 4.dp)) {
                Text(k, color = Color(0xFF64748B), fontSize = 12.sp, modifier = Modifier.width(70.dp))
                Text(v ?: "", color = Color(0xFFA5F3FC), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

/** dependency_row — 依赖行 */
@Composable
fun DependencyRowRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(p.sC("kind", "impl") ?: "impl", fontSize = 11.sp, color = Color(0xFFA78BFA), fontFamily = FontFamily.Monospace)
        Text(p.sC("name", "androidx.core:core-ktx") ?: "", fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f).padding(start = 8.dp), maxLines = 1)
        Text(p.sC("version", "1.0") ?: "", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
    }
}

/** runtime_env_card — 运行环境卡 */
@Composable
fun RuntimeEnvCardRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    Row(Modifier.fillMaxWidth().padding(10.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("⚙️", fontSize = 20.sp)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(p.sC("os", "Android 14") ?: "Android 14", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text("SDK ${p.iC("sdk", 34)} · ${p.sC("arch", "arm64") ?: "arm64"}", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
        }
        Box(Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFFDCFCE7)).padding(horizontal = 8.dp, vertical = 3.dp)) { Text("正常", fontSize = 10.sp, color = Color(0xFF16A34A)) }
    }
}

/** device_env_card — 手机环境卡 */
@Composable
fun DeviceEnvCardRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    Row(Modifier.fillMaxWidth().padding(10.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("📱", fontSize = 20.sp)
        listOf("机型" to p.sC("model", "Pixel 8"), "分辨率" to p.sC("res", "1080×2400"), "电量" to "${p.iC("battery", 80)}%").forEach { (k, v) ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(v ?: "", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(k, fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

/** permission_card — 手机权限卡 */
@Composable
fun PermissionCardRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    val granted = p.bC("granted", true)
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(p.sC("icon", "📷") ?: "📷", fontSize = 18.sp)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(p.sC("name", "相机") ?: "相机", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(p.sC("desc", "用于拍摄照片") ?: "", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
        }
        Box(Modifier.clip(RoundedCornerShape(6.dp)).background(if (granted) Color(0xFFDCFCE7) else Color(0xFFFEE2E2)).padding(horizontal = 8.dp, vertical = 3.dp)) {
            Text(if (granted) "已授权" else "未授权", fontSize = 10.sp, color = if (granted) Color(0xFF16A34A) else Color(0xFFDC2626))
        }
    }
}

/** permission_prompt — 授权请求弹层 */
@Composable
fun PermissionPromptRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    Column(Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(p.sC("icon", "📍") ?: "📍", fontSize = 32.sp)
        Text(p.sC("title", "允许获取位置信息？") ?: "", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
        Text(p.sC("desc", "") ?: "", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(vertical = 9.dp), contentAlignment = Alignment.Center) { Text("拒绝", fontSize = 13.sp) }
            Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primary).padding(vertical = 9.dp), contentAlignment = Alignment.Center) { Text("允许", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

/** toggle_group — 开关组 */
@Composable
fun ToggleGroupRenderer(c: UIComponent, ctx: RenderContext) {
    val items = c.properties.aC("items")
    val states = c.properties.aC("states")
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        items.forEachIndexed { i, label ->
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 13.sp); Spacer(Modifier.weight(1f))
                val on = states.getOrNull(i) == "on"
                Box(Modifier.width(40.dp).height(22.dp).clip(RoundedCornerShape(11.dp)).background(if (on) MaterialTheme.colorScheme.primary else Color.LightGray.copy(alpha = 0.5f)), contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
                    Box(Modifier.padding(horizontal = 2.dp).size(18.dp).clip(CircleShape).background(Color.White))
                }
            }
        }
    }
}

/** big_switch — 大开关（智能家居风） */
@Composable
fun BigSwitchRenderer(c: UIComponent, ctx: RenderContext) {
    val on = c.properties.bC("on", true)
    val label = c.properties.sC("label", "客厅灯") ?: "客厅灯"
    Column(Modifier.fillMaxWidth().padding(6.dp).clip(RoundedCornerShape(14.dp)).background(if (on) Color(0xFFFEF3C7) else MaterialTheme.colorScheme.surfaceContainerLow).padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (on) "💡" else "🚫", fontSize = 26.sp)
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
        Text(if (on) "已开启" else "已关闭", fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
    }
}

/** html_tag_view — HTML 标签树行 */
@Composable
fun HtmlTagViewRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    val depth = p.iC("depth", 0)
    Row(Modifier.fillMaxWidth().padding(start = (depth * 16).dp, top = 2.dp, bottom = 2.dp)) {
        Text("<", color = Color(0xFF64748B), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Text(p.sC("tag", "div") ?: "div", color = Color(0xFFFB923C), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        p.sC("cls", "")?.takeIf { it.isNotBlank() }?.let {
            Text(" class=", color = Color(0xFF64748B), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Text("\"$it\"", color = Color(0xFF34D399), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        Text(">", color = Color(0xFF64748B), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

/** web_landing — web 模拟落地页头 */
@Composable
fun WebLandingRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    Column(Modifier.fillMaxWidth().padding(10.dp).clip(RoundedCornerShape(14.dp)).background(Brush.verticalGradient(listOf(Color(0xFF1E293B), Color(0xFF0F172A)))).padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(p.sC("title", "Build something great") ?: "", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Black)
        Text(p.sC("sub", "") ?: "", color = Color(0xFF94A3B8), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(12.dp))
        Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF3B82F6)).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(p.sC("cta", "Get Started") ?: "Get Started", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** web_nav_bar — web 导航条 */
@Composable
fun WebNavBarRenderer(c: UIComponent, ctx: RenderContext) {
    Row(Modifier.fillMaxWidth().padding(8.dp).clip(RoundedCornerShape(999.dp)).background(Color(0xFF0F172A)).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(c.properties.sC("logo", "◆") ?: "◆", color = Color(0xFF38BDF8), fontWeight = FontWeight.Black)
        Spacer(Modifier.width(10.dp))
        c.properties.aC("links").take(4).forEach { Text(it, color = Color(0xFFCBD5E1), fontSize = 11.sp, modifier = Modifier.padding(start = 12.dp)) }
        Spacer(Modifier.weight(1f))
        Box(Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF3B82F6)).padding(horizontal = 10.dp, vertical = 4.dp)) { Text("Sign up", color = Color.White, fontSize = 11.sp) }
    }
}

/** ide_window — IDE 模拟窗口（children=编辑器内容） */
@Composable
fun IdeWindowRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    Column(Modifier.fillMaxWidth().padding(10.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, Color(0xFF334155), RoundedCornerShape(12.dp)).background(Color(0xFF0F172A))) {
        Row(Modifier.fillMaxWidth().background(Color(0xFF1E293B)).padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(Color(0xFFEF4444), Color(0xFFEAB308), Color(0xFF22C55E)).forEach { col -> Box(Modifier.size(9.dp).clip(CircleShape).background(col).padding(start = 3.dp)) }
            Text(p.sC("file", "MainActivity.kt") ?: "MainActivity.kt", color = Color(0xFF94A3B8), fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(start = 8.dp))
        }
        Row(Modifier.fillMaxWidth().height(2.dp).background(Color(0xFF38BDF8).copy(alpha = 0.5f))) {}
        Box(Modifier.fillMaxWidth().padding(10.dp)) { RenderChildren(c.children, ctx) }
    }
}

/** ide_tab_row — IDE 标签行 */
@Composable
fun IdeTabRowRenderer(c: UIComponent, ctx: RenderContext) {
    val tabs = c.properties.aC("tabs")
    val active = c.properties.iC("active", 0)
    Row(Modifier.fillMaxWidth().background(Color(0xFF0F172A)).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        tabs.forEachIndexed { i, t ->
            Text(t, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = if (i == active) Color(0xFF38BDF8) else Color(0xFF64748B),
                modifier = Modifier.clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)).background(if (i == active) Color(0xFF1E293B) else Color.Transparent).padding(horizontal = 10.dp, vertical = 6.dp))
        }
    }
}
