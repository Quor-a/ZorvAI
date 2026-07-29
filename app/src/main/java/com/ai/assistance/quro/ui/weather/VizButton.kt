package com.ai.assistance.quro.ui.weather

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 可视化按钮 · 设计库（Compose 侧）
 * 与 HTML 展示页一一对应。每款 [VizButtonStyle] 是独立视觉语言。
 */
enum class VizButtonStyle {
    Glass, Gradient, Ghost, Icon, Toggle, Neon, ThreeD, Link,
    Segmented, Fab, Square, Animated, Chip, Split, Dashed, Soft,
    Holographic, Brutalist, Clay, Vapor, Blueprint, Newspaper, Duotone, Marble,
    Sticker, Pixel, Sketch, Glow, Outline, Hatch, Emboss, Sweep,
}

@Composable
fun VizButton(
    text: String,
    style: VizButtonStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color(0xFF6EA8FE),
) {
    when (style) {
        VizButtonStyle.Glass -> Box(modifier.wrapContentSize().clip(RoundedCornerShape(999.dp))
            .background(tint.copy(alpha = 0.12f)).border(1.dp, tint.copy(alpha = 0.3f), RoundedCornerShape(999.dp))
            .clickable(onClick = onClick).padding(10.dp, 8.dp), contentAlignment = Alignment.Center) {
            Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        VizButtonStyle.Gradient -> Box(modifier.wrapContentSize().clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF6EA8FE), Color(0xFFA18CD1))))
            .clickable(onClick = onClick).padding(22.dp, 11.dp), contentAlignment = Alignment.Center) {
            Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        VizButtonStyle.Ghost -> Box(modifier.wrapContentSize().clip(RoundedCornerShape(10.dp))
            .border(1.5.dp, tint, RoundedCornerShape(10.dp)).clickable(onClick = onClick)
            .padding(20.dp, 9.dp), contentAlignment = Alignment.Center) {
            Text(text, color = tint, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        VizButtonStyle.Icon -> Box(modifier.wrapContentSize().clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1F6FEB)).clickable(onClick = onClick).padding(18.dp, 10.dp),
            contentAlignment = Alignment.Center) {
            Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        VizButtonStyle.Toggle -> {
            val on = text.contains("开") || text.contains("On")
            Box(modifier.wrapContentSize().clip(RoundedCornerShape(999.dp)).background(Color(0xFF2A2F3A))
                .clickable(onClick = onClick).padding(8.dp, 8.dp), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp, 20.dp).clip(CircleShape)
                        .background(if (on) Color(0xFF39D98A) else Color.Gray), contentAlignment = Alignment.CenterEnd) {
                        Box(Modifier.size(16.dp).padding(end = 2.dp).clip(CircleShape).background(Color.White))
                    }
                    Text(if (on) "  已开启" else "  已关闭", color = Color.White, fontSize = 14.sp,
                        modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        VizButtonStyle.Neon -> Box(modifier.wrapContentSize().clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF04130A)).border(1.dp, Color(0xFF39FF14), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick).padding(22.dp, 11.dp), contentAlignment = Alignment.Center) {
            Text(text, color = Color(0xFF39FF14), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        VizButtonStyle.ThreeD -> Box(modifier.wrapContentSize().clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFFFB020)).shadow(4.dp, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(22.dp, 11.dp), contentAlignment = Alignment.Center) {
            Text(text, color = Color(0xFF3A2A00), fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        VizButtonStyle.Link -> Box(modifier.wrapContentSize().clickable(onClick = onClick)
            .padding(4.dp), contentAlignment = Alignment.Center) {
            Text(text, color = tint, fontSize = 14.sp,
                modifier = Modifier.border(0.dp, Color.Transparent).padding(0.dp))
        }
        VizButtonStyle.Segmented -> {
            val items = text.split("|")
            Box(modifier.wrapContentSize().clip(RoundedCornerShape(10.dp)).background(Color(0xFF1A1F29))
                .clickable(onClick = onClick).padding(0.dp)) {
                Row {
                    items.forEachIndexed { i, it ->
                        Box(Modifier.padding(9.dp, 8.dp).then(
                            if (i == 0) Modifier.background(tint).padding(7.dp, 8.dp)
                            else Modifier.padding(7.dp, 8.dp)), contentAlignment = Alignment.Center) {
                            Text(it, color = if (i == 0) Color(0xFF06122A) else Color.White, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
        VizButtonStyle.Fab -> Box(modifier.size(54.dp).clip(CircleShape).background(Color(0xFFFF5A5F))
            .shadow(6.dp, CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Text(text, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        VizButtonStyle.Square -> Box(modifier.wrapContentSize().clip(RoundedCornerShape(4.dp))
            .background(Color.White).border(1.5.dp, Color(0xFF222222), RoundedCornerShape(4.dp))
            .clickable(onClick = onClick).padding(18.dp, 10.dp), contentAlignment = Alignment.Center) {
            Text(text, color = Color(0xFF222222), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        VizButtonStyle.Animated -> Box(modifier.wrapContentSize().clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(listOf(Color(0xFFFF6B9D), Color(0xFF6EA8FE), Color(0xFF39D98A))))
            .clickable(onClick = onClick).padding(22.dp, 11.dp), contentAlignment = Alignment.Center) {
            Text(text, color = Color(0xFF06122A), fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        VizButtonStyle.Chip -> Box(modifier.wrapContentSize().clip(RoundedCornerShape(999.dp))
            .background(Color(0xFFEEF2FF)).clickable(onClick = onClick).padding(16.dp, 7.dp),
            contentAlignment = Alignment.Center) {
            Text(text, color = Color(0xFF3A4A6B), fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        VizButtonStyle.Split -> {
            val parts = text.split(":")
            Box(modifier.wrapContentSize().clip(RoundedCornerShape(12.dp)).background(Color(0xFF1F6FEB))
                .clickable(onClick = onClick)) {
                Row {
                    Box(Modifier.padding(11.dp, 18.dp).clickable(onClick = onClick),
                        contentAlignment = Alignment.Center) {
                        Text(parts.getOrNull(0) ?: text, color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                    Box(Modifier.background(Color.Black.copy(alpha = 0.18f)).padding(12.dp, 18.dp),
                        contentAlignment = Alignment.Center) {
                        Text(parts.getOrNull(1) ?: "⌄", color = Color.White)
                    }
                }
            }
        }
        VizButtonStyle.Dashed -> Box(modifier.wrapContentSize().clip(RoundedCornerShape(10.dp))
            .background(Color.Transparent).border(1.5.dp, tint, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick).padding(18.dp, 9.dp), contentAlignment = Alignment.Center) {
            Text(text, color = tint, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        VizButtonStyle.Soft -> Box(modifier.wrapContentSize().clip(RoundedCornerShape(14.dp))
            .background(Color(0xFFE7F0FF)).shadow(4.dp, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).padding(22.dp, 11.dp), contentAlignment = Alignment.Center) {
            Text(text, color = Color(0xFF2A5BD7), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        VizButtonStyle.Holographic -> Box(modifier.wrapContentSize().clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(listOf(Color(0xFFFF9A9E), Color(0xFFA18CD1), Color(0xFF84FAB0), Color(0xFF6EA8FE))))
            .clickable(onClick = onClick).padding(22.dp, 11.dp), contentAlignment = Alignment.Center) {
            Text(text, color = Color(0xFF1A1A2A), fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        VizButtonStyle.Brutalist -> Box(modifier.wrapContentSize().clip(RoundedCornerShape(0.dp))
            .background(Color(0xFF101010)).clickable(onClick = onClick).padding(22.dp, 11.dp),
            contentAlignment = Alignment.Center) {
            Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        VizButtonStyle.Clay -> Box(modifier.wrapContentSize().clip(RoundedCornerShape(24.dp))
            .background(Color(0xFFF6D9C0)).clickable(onClick = onClick).padding(22.dp, 11.dp),
            contentAlignment = Alignment.Center) {
            Text(text, color = Color(0xFF5A3A2A), fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        VizButtonStyle.Vapor -> Box(modifier.wrapContentSize().clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF2A1A4A), Color(0xFF5A2A6A))))
            .border(2.dp, Color(0xFFFF6AD5), RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(20.dp, 9.dp),
            contentAlignment = Alignment.Center) {
            Text(text, color = Color(0xFFF5A6FF), fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        VizButtonStyle.Blueprint -> Box(modifier.wrapContentSize().clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF0A2A5A)).border(1.dp, Color(0xFF4F8FD0), RoundedCornerShape(4.dp))
            .clickable(onClick = onClick).padding(20.dp, 9.dp), contentAlignment = Alignment.Center) {
            Text(text, color = Color(0xFFBFE0FF), fontWeight = FontWeight.Medium, fontSize = 14.sp,
                fontFamily = FontFamily.Monospace)
        }
        VizButtonStyle.Newspaper -> Box(modifier.wrapContentSize().clip(RoundedCornerShape(2.dp))
            .background(Color(0xFFF4F1EA)).border(1.dp, Color(0xFFCCCCCC), RoundedCornerShape(2.dp))
            .clickable(onClick = onClick).padding(20.dp, 9.dp), contentAlignment = Alignment.Center) {
            Text(text, color = Color(0xFF1A1A1A), fontWeight = FontWeight.Medium, fontSize = 14.sp,
                fontFamily = FontFamily.Serif)
        }
        VizButtonStyle.Duotone -> Box(modifier.wrapContentSize().clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF10243A)).border(1.dp, Color(0xFF1B6FA8), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(22.dp, 11.dp), contentAlignment = Alignment.Center) {
            Text(text, color = Color(0xFF37E0C8), fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        VizButtonStyle.Marble -> Box(modifier.wrapContentSize().clip(RoundedCornerShape(14.dp))
            .background(Color(0xFFF7F7F9)).shadow(4.dp, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).padding(22.dp, 11.dp), contentAlignment = Alignment.Center) {
            Text(text, color = Color(0xFF3A3A44), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        VizButtonStyle.Sticker -> Box(modifier.wrapContentSize().clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF6EA8FE)).border(4.dp, Color.White, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick).padding(18.dp, 7.dp), contentAlignment = Alignment.Center) {
            Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        VizButtonStyle.Pixel -> Box(modifier.wrapContentSize().clip(RoundedCornerShape(0.dp))
            .background(Color(0xFF121212)).border(2.dp, Color(0xFF2AA00F), RoundedCornerShape(0.dp))
            .clickable(onClick = onClick).padding(22.dp, 11.dp), contentAlignment = Alignment.Center) {
            Text(text, color = Color(0xFF39FF14), fontWeight = FontWeight.Bold, fontSize = 14.sp,
                fontFamily = FontFamily.Monospace)
        }
        VizButtonStyle.Sketch -> Box(modifier.wrapContentSize().clip(RoundedCornerShape(10.dp))
            .background(Color(0xFFFFFBF0)).border(2.dp, Color(0xFF222222), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick).padding(20.dp, 9.dp), contentAlignment = Alignment.Center) {
            Text(text, color = Color(0xFF222222), fontWeight = FontWeight.Medium, fontSize = 14.sp)
        }
        VizButtonStyle.Glow -> Box(modifier.wrapContentSize().clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF6EA8FE)).shadow(8.dp, RoundedCornerShape(12.dp), spotColor = Color(0xFF6EA8FE))
            .clickable(onClick = onClick).padding(22.dp, 11.dp), contentAlignment = Alignment.Center) {
            Text(text, color = Color(0xFF06122A), fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        VizButtonStyle.Outline -> Box(modifier.wrapContentSize().clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF6EA8FE).copy(alpha = 0.15f)).border(2.dp, Color(0xFF6EA8FE), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(20.dp, 9.dp), contentAlignment = Alignment.Center) {
            Text(text, color = Color(0xFF6EA8FE), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        VizButtonStyle.Hatch -> Box(modifier.wrapContentSize().clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFFF8A5B)).clickable(onClick = onClick).padding(22.dp, 11.dp),
            contentAlignment = Alignment.Center) {
            Text(text, color = Color(0xFFA33A1A), fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        VizButtonStyle.Emboss -> Box(modifier.wrapContentSize().clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFCFD6E4)).clickable(onClick = onClick).padding(22.dp, 11.dp),
            contentAlignment = Alignment.Center) {
            Text(text, color = Color(0xFF3A3A44), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        VizButtonStyle.Sweep -> Box(modifier.wrapContentSize().clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF6EA8FE), Color(0xFFA18CD1), Color(0xFF39D98A), Color(0xFFFF6B9D))))
            .clickable(onClick = onClick).padding(22.dp, 11.dp), contentAlignment = Alignment.Center) {
            Text(text, color = Color(0xFF06122A), fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}
