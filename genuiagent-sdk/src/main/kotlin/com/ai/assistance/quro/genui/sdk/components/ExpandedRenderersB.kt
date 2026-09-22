package com.ai.assistance.quro.genui.sdk.components

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.genui.sdk.dsl.UIComponent
import com.ai.assistance.quro.genui.sdk.render.RenderContext
import com.ai.assistance.quro.genui.sdk.style.ColorParser
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private fun JsonObject.sB(key: String, def: String? = null): String? = (this[key] as? JsonPrimitive)?.content ?: def
private fun JsonObject.fB(key: String, def: Float = 0f): Float = (this[key] as? JsonPrimitive)?.content?.toFloatOrNull() ?: def
private fun JsonObject.iB(key: String, def: Int = 0): Int = (this[key] as? JsonPrimitive)?.content?.toIntOrNull() ?: def
private fun JsonObject.cB(key: String, def: Color): Color = ColorParser.toColor(sB(key), def)
private fun JsonObject.aB(key: String): List<String> = ((this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }) ?: emptyList()

/** search_bar — 搜索输入框（含放大镜与清空） */
@Composable
fun SearchBarRenderer(c: UIComponent, ctx: RenderContext) {
    Row(
        Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(50.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("🔍", fontSize = 14.sp)
        Text(c.properties.sB("hint", "搜索一下…") ?: "搜索一下…", fontSize = 14.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(start = 8.dp))
        Spacer(Modifier.weight(1f))
        Text("🎤", fontSize = 12.sp)
    }
}

/** search_result_row — 搜索结果行 */
@Composable
fun SearchResultRowRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Text(p.sB("emoji", "🔎") ?: "🔎", fontSize = 16.sp)
        }
        Column(Modifier.padding(start = 10.dp)) {
            Text(p.sB("title", "结果标题") ?: "结果标题", fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(p.sB("snippet", "") ?: "", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** greeting_hero — 时段问候大横幅 */
@Composable
fun GreetingHeroRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    Box(
        Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(ctx.theme.colorScheme.info, ctx.theme.colorScheme.primary))).padding(20.dp)
    ) {
        Column {
            Text("${p.sB("greet", "你好") ?: "你好"}，${p.sB("name", "朋友") ?: "朋友"} 🌟", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
            Text(p.sB("sub", "今天想生成什么界面？") ?: "今天想生成什么界面？", color = ctx.theme.colorScheme.infoContainer, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** welcome_banner — 欢迎条 */
@Composable
fun WelcomeBannerRenderer(c: UIComponent, ctx: RenderContext) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("👋", fontSize = 20.sp)
        Text(c.properties.sB("text", "欢迎回来！") ?: "欢迎回来！", fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 8.dp))
    }
}

/** meal_card — 餐食卡（图片位+热量） */
@Composable
fun MealCardRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(ctx.theme.colorScheme.warning), contentAlignment = Alignment.Center) { Text(p.sB("emoji", "🍜") ?: "🍜", fontSize = 24.sp) }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(p.sB("name", "牛肉面") ?: "牛肉面", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(p.sB("portion", "1 碗") ?: "1 碗", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
        }
        Text("${p.iB("kcal", 450)} kcal", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ctx.theme.colorScheme.primary)
    }
}

/** diet_summary — 饮食日汇总 */
@Composable
fun DietSummaryRenderer(c: UIComponent, ctx: RenderContext) {
    val cur = c.properties.iB("kcal", 0); val goal = c.properties.iB("goal", 2000).coerceAtLeast(1)
    val frac = (cur.toFloat() / goal).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Row { Text("今日摄入", fontSize = 12.sp, fontWeight = FontWeight.Medium); Spacer(Modifier.weight(1f)); Text("$cur / $goal kcal", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline) }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(6.dp)).background(Color.LightGray.copy(alpha = 0.35f))) {
            Box(Modifier.fillMaxWidth(frac).height(8.dp).clip(RoundedCornerShape(6.dp)).background(Brush.horizontalGradient(listOf(ctx.theme.colorScheme.success, ctx.theme.colorScheme.warning))))
        }
    }
}

/** compress_card — 压缩任务卡（进度+比率） */
@Composable
fun CompressCardRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    val frac = p.fB("progress", 0.6f).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🗜️", fontSize = 18.sp)
            Text(p.sB("file", "archive.zip") ?: "archive.zip", fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 8.dp))
            Spacer(Modifier.weight(1f))
            Text("-${p.iB("ratio", 42)}%", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ctx.theme.colorScheme.success)
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(6.dp)).background(Color.LightGray.copy(alpha = 0.35f))) {
            Box(Modifier.fillMaxWidth(frac).height(6.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.primary))
        }
    }
}

/** archive_row — 压缩包行 */
@Composable
fun ArchiveRowRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(p.sB("kind", "📦") ?: "📦", fontSize = 16.sp)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(p.sB("name", "backup.tar.gz") ?: "backup.tar.gz", fontSize = 12.sp)
            Text(p.sB("meta", "120MB → 68MB") ?: "", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
        }
        Text("✓", color = ctx.theme.colorScheme.success, fontWeight = FontWeight.Bold)
    }
}

/** bg_mesh — 网格渐变背景可视化 */
@Composable
fun BgMeshRenderer(c: UIComponent, ctx: RenderContext) {
    Canvas(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(14.dp))) {
        drawRect(Brush.linearGradient(listOf(ctx.theme.colorScheme.onSurface, ctx.theme.colorScheme.onSurface)))
        listOf(ctx.theme.colorScheme.info, ctx.theme.colorScheme.primary, ctx.theme.colorScheme.info).forEachIndexed { i, col ->
            drawCircle(col.copy(alpha = 0.3f), size.width * 0.28f, Offset(size.width * (0.2f + 0.3f * i), size.height * (0.3f + 0.2f * ((i + 1) % 2))))
        }
    }
}

/** bg_grid_glow — 发光网格背景 */
@Composable
fun BgGridGlowRenderer(c: UIComponent, ctx: RenderContext) {
    Canvas(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(14.dp))) {
        drawRect(ctx.theme.colorScheme.surfaceContainerHighest)
        val step = 22f; val glow = ctx.theme.colorScheme.info.copy(alpha = 0.25f)
        var x = 0f
        while (x < size.width) { drawLine(glow, Offset(x, 0f), Offset(x, size.height), 1f); x += step }
        var y = 0f
        while (y < size.height) { drawLine(glow, Offset(0f, y), Offset(size.width, y), 1f); y += step }
        drawCircle(ctx.theme.colorScheme.info.copy(alpha = 0.35f), 60f, Offset(size.width * 0.5f, size.height * 0.5f))
    }
}

/** keyboard_input — 键盘输入框（聚焦态） */
@Composable
fun KeyboardInputRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp, vertical = 12.dp)
        ) {
            Row { Text(p.sB("value", "") ?: "", fontSize = 14.sp); Box(Modifier.width(2.dp).height(18.dp).background(MaterialTheme.colorScheme.primary)) }
        }
        Text(p.sB("hint", "正在使用键盘输入…") ?: "正在使用键盘输入…", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 4.dp))
    }
}

/** otp_input — 6 格验证码 */
@Composable
fun OtpInputRenderer(c: UIComponent, ctx: RenderContext) {
    val code = c.properties.sB("code", "") ?: ""
    val filled = code.take(6)
    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
        repeat(6) { i ->
            val ch = filled.getOrNull(i)
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (ch != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) { Text(ch?.toString() ?: "", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

/** pay_sheet — 支付面板 */
@Composable
fun PaySheetRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    Column(Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface).padding(16.dp)) {
        Text("支付金额", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
        Row(verticalAlignment = Alignment.Bottom) {
            Text("¥", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(p.sB("amount", "99.00") ?: "99.00", fontSize = 32.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(10.dp))
        listOf("💳 银行卡" to true, "💰 余额" to false, "📦 货到付款" to false).forEach { (label, sel) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 12.sp); Spacer(Modifier.weight(1f))
                Box(Modifier.size(18.dp).clip(CircleShape).background(if (sel) MaterialTheme.colorScheme.primary else Color.LightGray.copy(alpha = 0.4f))) {
                    if (sel) Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White).align(Alignment.Center))
                }
            }
        }
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(ctx.theme.colorScheme.success).padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
            Text("确认支付", color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}

/** pay_success — 支付成功 */
@Composable
fun PaySuccessRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(ctx.theme.colorScheme.success), contentAlignment = Alignment.Center) { Text("✓", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black) }
        Text("支付成功", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(top = 10.dp))
        Text("¥${p.sB("amount", "0.00") ?: "0.00"}", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 4.dp))
    }
}

/** server_row — 服务器状态行 */
@Composable
fun ServerRowRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    val ok = p.sB("state", "up") == "up"
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (ok) ctx.theme.colorScheme.success else ctx.theme.colorScheme.error))
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(p.sB("name", "srv-01") ?: "srv-01", fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text("CPU ${p.iB("cpu", 32)}% · MEM ${p.iB("mem", 58)}%", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
        }
        Text(if (ok) "运行中" else "离线", fontSize = 11.sp, color = if (ok) ctx.theme.colorScheme.success else ctx.theme.colorScheme.error)
    }
}

/** server_status_pill — 集群状态胶囊 */
@Composable
fun ServerStatusPillRenderer(c: UIComponent, ctx: RenderContext) {
    val p = c.properties
    Row(Modifier.clip(RoundedCornerShape(50.dp)).background(ctx.theme.colorScheme.onSurface).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(ctx.theme.colorScheme.success))
        Text("${p.iB("up", 12)} up / ${p.iB("total", 14)} nodes", color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
    }
}
