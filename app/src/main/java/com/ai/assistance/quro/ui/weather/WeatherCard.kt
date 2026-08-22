package com.ai.assistance.quro.ui.weather

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 天气预报卡片 · 设计库（Compose 侧）
 *
 * 与 HTML 展示页（design/weather-viz-library/showcase.html）一一对应。
 * 每条 [WeatherCardStyle] 都是一款**独立视觉语言**：背景、形状、字族、布局均不同，
 * 而非同一模板换字。渲染由 [WeatherCard] 按 [WeatherCardStyle.layout] 分流到 4 种布局。
 *
 * 扩展 100 款：在 [WeatherCardPresets] 继续追加独立 style 预设即可，渲染层无需改动。
 */

data class WeatherData(
    val city: String,
    val condition: String,
    val tempC: Int,
    val hi: Int,
    val lo: Int,
    val humidity: Int,
    val icon: String = "☀️",
)

enum class WeatherLayout { Normal, Ring, Split, Dashboard }

data class WeatherCardStyle(
    val id: String,
    val containerColor: Color,
    val contentColor: Color,
    val secondaryColor: Color,
    val shape: RoundedCornerShape,
    val layout: WeatherLayout,
    val fontScale: Float = 1f,
    val brush: Brush? = null,
    val isGlass: Boolean = false,
    val serif: Boolean = false,
    val boldTemp: Boolean = true,
)

object WeatherCardPresets {
    val Aurora = WeatherCardStyle(
        "aurora", Color(0xFF2B5876), Color.White, Color(0xFFBFE3FF),
        RoundedCornerShape(18.dp), WeatherLayout.Normal, isGlass = true,
        brush = Brush.linearGradient(listOf(Color(0xFF2B5876), Color(0xFF4E4376))),
    )
    val Neon = WeatherCardStyle(
        "neon", Color(0xFF0A0E17), Color(0xFF5FF0FF), Color(0xFF7AD7FF),
        RoundedCornerShape(16.dp), WeatherLayout.Normal,
    )
    val Editorial = WeatherCardStyle(
        "edit", Color(0xFFF3EDE2), Color(0xFF211C14), Color(0xFF6B5E45),
        RoundedCornerShape(10.dp), WeatherLayout.Normal, serif = true, boldTemp = false,
    )
    val Mesh = WeatherCardStyle(
        "mesh", Color(0xFF222222), Color.White, Color(0xFFD7FFE6),
        RoundedCornerShape(20.dp), WeatherLayout.Normal,
        brush = Brush.radialGradient(
            listOf(Color(0xFFFF9A9E), Color(0xFFA18CD1), Color(0xFF84FAB0), Color(0xFF222222))
        ),
    )
    val Neumorph = WeatherCardStyle(
        "neu", Color(0xFF2A2F3A), Color(0xFFE7ECF5), Color(0xFFCDD6E6),
        RoundedCornerShape(22.dp), WeatherLayout.Normal,
    )
    val Terminal = WeatherCardStyle(
        "term", Color(0xFF02110A), Color(0xFF39FF14), Color(0xFF39FF14),
        RoundedCornerShape(8.dp), WeatherLayout.Normal,
    )
    val Minimal = WeatherCardStyle(
        "min", Color(0xFF111418), Color(0xFFF5F7FA), Color(0xFF9AA4B2),
        RoundedCornerShape(14.dp), WeatherLayout.Normal, boldTemp = false,
    )
    val Split = WeatherCardStyle(
        "split", Color(0xFF1F2A52), Color(0xFF222222), Color(0xFFCCD8FF),
        RoundedCornerShape(18.dp), WeatherLayout.Split,
    )
    val Ring = WeatherCardStyle(
        "ring", Color(0xFF10243A), Color(0xFFDDFBFF), Color(0xFF37E0C8),
        RoundedCornerShape(20.dp), WeatherLayout.Ring,
    )
    val Dashboard = WeatherCardStyle(
        "dash", Color(0xFF0E1620), Color(0xFFE6EDF6), Color(0xFF8AA9C9),
        RoundedCornerShape(16.dp), WeatherLayout.Dashboard,
    )
    val Watercolor = WeatherCardStyle(
        "wash", Color(0xFFFBFCFF), Color(0xFF33405A), Color(0xFF3A4A6B),
        RoundedCornerShape(18.dp), WeatherLayout.Normal,
        brush = Brush.radialGradient(
            listOf(Color(0xFFCFE3FF), Color(0xFFFFD9EC), Color(0xFFD7FFE6), Color(0xFFFBFCFF))
        ),
    )
    val Isometric = WeatherCardStyle(
        "iso", Color(0xFF1B2230), Color.White, Color(0xFF2E3A4F),
        RoundedCornerShape(14.dp), WeatherLayout.Normal,
    )
    val Pop = WeatherCardStyle(
        "pop", Color(0xFFFF5A5F), Color.White, Color(0xFF3A0E10),
        RoundedCornerShape(16.dp), WeatherLayout.Normal,
    )
    val Pastel = WeatherCardStyle(
        "pastel", Color(0xFFEAF2FF), Color(0xFF3A4A6B), Color(0xFF6E8FD0),
        RoundedCornerShape(26.dp), WeatherLayout.Normal,
    )
    val Monoline = WeatherCardStyle(
        "line", Color.White, Color(0xFF222222), Color(0xFF888888),
        RoundedCornerShape(14.dp), WeatherLayout.Normal,
    )
    val Sunset = WeatherCardStyle(
        "sun", Color(0xFFFF5E62), Color.White, Color(0xFFFFD9B0),
        RoundedCornerShape(20.dp), WeatherLayout.Normal,
        brush = Brush.linearGradient(listOf(Color(0xFFFF9966), Color(0xFFFF5E62), Color(0xFF5B2A86))),
    )

    val Holographic = WeatherCardStyle("holo", Color(0xFF1A1A2A), Color(0xFF1A1A2A), Color(0xFFBFE3FF),
        RoundedCornerShape(20.dp), WeatherLayout.Normal, isGlass = true,
        brush = Brush.linearGradient(listOf(Color(0xFFFF9A9E), Color(0xFFA18CD1), Color(0xFF84FAB0), Color(0xFF6EA8FE))))
    val Brutalist = WeatherCardStyle("brut", Color(0xFF101010), Color.White, Color(0xFFFF5A5F),
        RoundedCornerShape(0.dp), WeatherLayout.Normal)
    val Clay = WeatherCardStyle("clay", Color(0xFFF6D9C0), Color(0xFF5A3A2A), Color(0xFFC98A5A),
        RoundedCornerShape(28.dp), WeatherLayout.Normal, boldTemp = false)
    val Vapor = WeatherCardStyle("vapor", Color(0xFF2A1A4A), Color(0xFFF5A6FF), Color(0xFF6EF0FF),
        RoundedCornerShape(18.dp), WeatherLayout.Normal,
        brush = Brush.linearGradient(listOf(Color(0xFF2A1A4A), Color(0xFF5A2A6A))))
    val Blueprint = WeatherCardStyle("blue", Color(0xFF0A2A5A), Color(0xFFBFE0FF), Color(0xFF7FB0FF),
        RoundedCornerShape(4.dp), WeatherLayout.Normal, serif = true)
    val Newspaper = WeatherCardStyle("news", Color(0xFFF4F1EA), Color(0xFF1A1A1A), Color(0xFF666666),
        RoundedCornerShape(2.dp), WeatherLayout.Normal, serif = true, boldTemp = false)
    val Oil = WeatherCardStyle("oil", Color(0xFF0C2A3A), Color.White, Color(0xFFF0D090),
        RoundedCornerShape(16.dp), WeatherLayout.Normal,
        brush = Brush.radialGradient(listOf(Color(0xFF1D6E6E), Color(0xFF0C2A3A), Color(0xFF7A5A1E))))
    val Origami = WeatherCardStyle("orig", Color(0xFFE8E2D0), Color(0xFF333333), Color(0xFFB06030),
        RoundedCornerShape(0.dp), WeatherLayout.Normal)
    val Bento = WeatherCardStyle("bento", Color(0xFF1B1B22), Color.White, Color(0xFF9AA4B2),
        RoundedCornerShape(18.dp), WeatherLayout.Dashboard)
    val Film = WeatherCardStyle("film", Color(0xFF2B2620), Color(0xFFE8D8C0), Color(0xFFB0A080),
        RoundedCornerShape(6.dp), WeatherLayout.Normal, serif = true)
    val Duotone = WeatherCardStyle("duo", Color(0xFF10243A), Color(0xFF37E0C8), Color(0xFF1B6FA8),
        RoundedCornerShape(14.dp), WeatherLayout.Normal)
    val Marble = WeatherCardStyle("marble", Color(0xFFF7F7F9), Color(0xFF3A3A44), Color(0xFF9A9AA8),
        RoundedCornerShape(20.dp), WeatherLayout.Normal,
        brush = Brush.radialGradient(listOf(Color(0xFFF7F7F9), Color(0xFFE2E2EA), Color(0xFFF7F7F9))))
    val Sticker = WeatherCardStyle("sticker", Color(0xFF6EA8FE), Color.White, Color(0xFFDDEBFF),
        RoundedCornerShape(18.dp), WeatherLayout.Normal, isGlass = true, boldTemp = true)
    val Pixel = WeatherCardStyle("pixel", Color(0xFF121212), Color(0xFF39FF14), Color(0xFF2AA00F),
        RoundedCornerShape(0.dp), WeatherLayout.Normal, boldTemp = true, fontScale = 0.9f)
    val Sketch = WeatherCardStyle("sketch", Color(0xFFFFFBF0), Color(0xFF222222), Color(0xFF888888),
        RoundedCornerShape(10.dp), WeatherLayout.Normal, boldTemp = false)
    val AuroraDark = WeatherCardStyle("aurorad", Color(0xFF0B1020), Color(0xFFBFD0FF), Color(0xFF7AA2FF),
        RoundedCornerShape(20.dp), WeatherLayout.Normal,
        brush = Brush.linearGradient(listOf(Color(0xFF0B1020), Color(0xFF1A1040))))

    val all: List<WeatherCardStyle> get() = listOf(
        Aurora, Neon, Editorial, Mesh, Neumorph, Terminal, Minimal, Split,
        Ring, Dashboard, Watercolor, Isometric, Pop, Pastel, Monoline, Sunset,
        Holographic, Brutalist, Clay, Vapor, Blueprint, Newspaper, Oil, Origami,
        Bento, Film, Duotone, Marble, Sticker, Pixel, Sketch, AuroraDark,
    )
}

@Composable
fun WeatherCard(
    data: WeatherData,
    style: WeatherCardStyle,
    modifier: Modifier = Modifier,
) {
    val family = if (style.serif) FontFamily.Serif else FontFamily.Default
    val base = if (style.brush != null) {
        Modifier.background(style.brush, style.shape)
    } else {
        Modifier.background(style.containerColor, style.shape)
    }
    val frame = if (style.isGlass) {
        base.border(1.dp, Color.White.copy(alpha = 0.18f), style.shape)
    } else {
        base
    }
    val iso = if (style.id == "iso") Modifier.graphicsLayer {
        rotationX = 8f; rotationY = -10f
    } else Modifier

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(style.shape)
            .then(frame)
            .then(iso)
            .padding(16.dp)
    ) {
        when (style.layout) {
            WeatherLayout.Ring -> RingLayout(data, style, family)
            WeatherLayout.Split -> SplitLayout(data, style, family)
            WeatherLayout.Dashboard -> DashboardLayout(data, style, family)
            WeatherLayout.Normal -> NormalLayout(data, style, family)
        }
    }
}

@Composable
private fun NormalLayout(data: WeatherData, style: WeatherCardStyle, family: FontFamily) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(data.city, color = style.contentColor, fontWeight = FontWeight.Bold,
                fontSize = (15 * style.fontScale).sp, fontFamily = family)
            Text(data.condition, color = style.secondaryColor, fontSize = 12.sp, fontFamily = family)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(data.icon, fontSize = 30.sp)
            Text("${data.tempC}°", color = style.contentColor,
                fontWeight = if (style.boldTemp) FontWeight.ExtraBold else FontWeight.Light,
                fontSize = (46 * style.fontScale).sp, fontFamily = family)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Foot(data, style)
        }
    }
}

@Composable
private fun RingLayout(data: WeatherData, style: WeatherCardStyle, family: FontFamily) {
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(data.city, color = style.contentColor, fontWeight = FontWeight.Bold,
            fontSize = 15.sp, fontFamily = family)
        Text(data.condition, color = style.secondaryColor, fontSize = 12.sp, fontFamily = family)
        Box(contentAlignment = Alignment.Center,
            modifier = Modifier.size(90.dp)
                .background(
                    Brush.sweepGradient(
                        listOf(style.secondaryColor, style.secondaryColor,
                            Color.White.copy(alpha = 0.12f), Color.White.copy(alpha = 0.12f),
                            style.secondaryColor)
                    ), RoundedCornerShape(50)
                )
                .padding(8.dp)
                .background(style.containerColor, RoundedCornerShape(50))
        ) {
            Text("${data.tempC}°", color = style.contentColor, fontWeight = FontWeight.ExtraBold,
                fontSize = 24.sp, fontFamily = family)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Foot(data, style) }
    }
}

@Composable
private fun SplitLayout(data: WeatherData, style: WeatherCardStyle, family: FontFamily) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(data.city, color = style.contentColor, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(data.condition, color = style.secondaryColor, fontSize = 12.sp)
            }
            Text(data.icon, fontSize = 28.sp)
        }
        Text("${data.tempC}°", color = style.contentColor, fontWeight = FontWeight.ExtraBold,
            fontSize = 46.sp, fontFamily = family)
        Box(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.18f),
            RoundedCornerShape(8.dp)).padding(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Foot(data, style) }
        }
    }
}

@Composable
private fun DashboardLayout(data: WeatherData, style: WeatherCardStyle, family: FontFamily) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(data.city, color = style.contentColor, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(data.condition, color = style.secondaryColor, fontSize = 12.sp)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(data.icon, fontSize = 26.sp)
            Text("${data.tempC}°", color = style.contentColor, fontWeight = FontWeight.ExtraBold,
                fontSize = 40.sp, fontFamily = family)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Stat("${data.hi}°", "Hi", style)
            Stat("${data.lo}°", "Lo", style)
            Stat("${data.humidity}%", "Hum", style)
            Stat("12km", "Vis", style)
            Stat("1012", "Pres", style)
            Stat("3", "Wind", style)
        }
    }
}

@Composable
private fun Stat(value: String, label: String, style: WeatherCardStyle) {
    Column(Modifier
        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
        .padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = style.contentColor, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(label, color = style.secondaryColor, fontSize = 9.sp)
    }
}

@Composable
private fun RowScope.Foot(data: WeatherData, style: WeatherCardStyle) {
    Text("H ${data.hi}°", color = style.secondaryColor, fontSize = 11.sp)
    Text("L ${data.lo}°", color = style.secondaryColor, fontSize = 11.sp)
    Text("Hum ${data.humidity}%", color = style.secondaryColor, fontSize = 11.sp)
}
