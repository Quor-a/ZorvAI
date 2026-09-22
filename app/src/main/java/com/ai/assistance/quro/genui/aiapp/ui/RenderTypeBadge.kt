package com.ai.assistance.quro.genui.aiapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.genui.aiapp.data.RenderChannel

/**
 * 渲染类型徽章 —— 历史记录里标明这条作品当初走的是哪条通道。
 *
 * 四个通道固定配色，全 App 统一：GenUI 主色 / A2UI 蓝 / Markdown 绿 / HTML 橙。
 * 颜色和文字都跟着主题走，浅色深色下都可读。
 */
@Composable
fun RenderTypeBadge(
    channel: RenderChannel,
    dense: Boolean = false,
    modifier: Modifier = Modifier
) {
    val (bg, fg) = badgeColors(channel)
    Row(
        modifier
            .clip(RoundedCornerShape(if (dense) 4.dp else 6.dp))
            .background(bg)
            .padding(horizontal = if (dense) 5.dp else 7.dp, vertical = if (dense) 1.dp else 2.dp)
    ) {
        Text(
            channel.label,
            fontSize = if (dense) 9.sp else 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = fg,
            maxLines = 1
        )
    }
}

/** 通道 → (底色, 字色)。底色用低透明度，深色主题下也不会糊成一块。 */
@Composable
private fun badgeColors(channel: RenderChannel): Pair<Color, Color> {
    val isDark = MaterialTheme.colorScheme.background.luminanceIsDark()
    val alpha = if (isDark) 0.30f else 0.14f
    val base = when (channel) {
        RenderChannel.GENUI -> MaterialTheme.colorScheme.primary
        RenderChannel.A2UI -> Color(0xFF2F6FEB)
        RenderChannel.MARKDOWN -> Color(0xFF2E9E5B)
        RenderChannel.HTML -> Color(0xFFD9791F)
    }
    // 深色主题下底色要更亮一点才看得出，字色也提亮
    val fg = if (isDark) base.lightenForDark() else base
    return base.copy(alpha = alpha) to fg
}

private fun Color.luminanceIsDark(): Boolean =
    (0.299f * red + 0.587f * green + 0.114f * blue) < 0.5f

private fun Color.lightenForDark(): Color = Color(
    red = red + (1f - red) * 0.45f,
    green = green + (1f - green) * 0.45f,
    blue = blue + (1f - blue) * 0.45f,
    alpha = 1f
)
