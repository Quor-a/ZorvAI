package com.ai.assistance.quro.core.ui.card.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ai.assistance.quro.core.ui.card.spec.CardData
import com.ai.assistance.quro.core.ui.card.spec.CardSpec
import com.ai.assistance.quro.core.ui.card.spec.ColorToken

/**
 * 媒体类卡片：`code_block`（代码）、`image_group`（图片组）、`media_card`（时间线 / 列表）。
 *
 * ## 为什么合并进一个渲染器
 * 这三类共用同一个数据壳 [CardData.Media]，按 `mediaType` 分派。分开写的结果是
 * 「同样的数据换个 mediaType 就渲染不出」——因为三个文件各自只认自己那几个字段。
 * 现在：数据壳一致 → 一个渲染器按 mediaType 分派，缺字段时还能退化（图片没 URL 就画占位、
 * 代码没 lang 就不显示语言条），不会出现「整卡空白」。
 *
 * 图片走 Coil（`coil-compose`，工程已依赖）：磁盘缓存 + 失败占位由 Coil 负责，
 * 不再自己写 Bitmap 缓存池（旧版手写 HeightCache/BitmapCache 已随 Canvas 架构一起删除）。
 */
@Composable
fun MediaCardView(
    spec: CardSpec,
    modifier: Modifier = Modifier,
    resolveToken: (ColorToken) -> Color,
) {
    val media = spec.data as? CardData.Media ?: return
    CardShell(spec, resolveToken, modifier) {
        when (media.mediaType.trim().lowercase()) {
            "code", "snippet", "pre" -> CodeBody(media, resolveToken)
            "image_group", "images", "gallery" -> ImageGroupBody(media, resolveToken)
            "timeline" -> TimelineBody(media, resolveToken)
            else -> ListBody(media, resolveToken)
        }
    }
}

@Composable
private fun CodeBody(media: CardData.Media, resolveToken: (ColorToken) -> Color) {
    val code = media.code.orEmpty()
    if (code.isBlank()) return
    Column {
        if (!media.lang.isNullOrBlank()) {
            Text(
                media.lang!!,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = resolveToken(ColorToken.OnSurfaceVariant),
            )
            Spacer(Modifier.height(6.dp))
        }
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(resolveToken(ColorToken.SurfaceVariant))
                // 横向滚动而不是折行：代码折行会破坏缩进，读起来比截断更糟
                .horizontalScroll(rememberScrollState())
                .padding(10.dp),
        ) {
            Text(
                code.trimEnd(),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = resolveToken(ColorToken.OnSurface),
                lineHeight = 17.sp,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun ImageGroupBody(media: CardData.Media, resolveToken: (ColorToken) -> Color) {
    val urls = media.images.filter { it.isNotBlank() }
    if (urls.isEmpty()) {
        // 拿不到 URL（模型常只给描述）时退化成一个提示条，而不是留一片空白
        Text(
            media.code ?: "（无图片地址）",
            fontSize = 12.sp,
            color = resolveToken(ColorToken.OnSurfaceVariant),
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        urls.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { url ->
                    AsyncImage(
                        model = url,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .weight(1f)
                            .height(96.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(resolveToken(ColorToken.SurfaceVariant)),
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TimelineBody(media: CardData.Media, resolveToken: (ColorToken) -> Color) {
    val items = media.items
    if (items.isEmpty()) {
        ListBody(media, resolveToken)
        return
    }
    Column {
        items.forEachIndexed { i, item ->
            Row(Modifier.fillMaxWidth()) {
                // 左轴：点 + 竖线（最后一个不画线，避免尾部悬空）
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(16.dp)) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(
                                if (item.done) resolveToken(ColorToken.Success) else resolveToken(ColorToken.Outline),
                                CircleShape,
                            ),
                    )
                    if (i != items.lastIndex) {
                        Box(
                            Modifier
                                .width(1.5.dp)
                                .height(28.dp)
                                .background(resolveToken(ColorToken.Outline).copy(alpha = 0.6f)),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f).padding(bottom = if (i == items.lastIndex) 0.dp else 10.dp)) {
                    if (item.time.isNotBlank()) {
                        Text(
                            item.time,
                            fontSize = 10.sp,
                            color = resolveToken(ColorToken.OnSurfaceVariant),
                        )
                    }
                    Text(
                        item.text,
                        fontSize = 13.sp,
                        fontWeight = if (item.done) FontWeight.Normal else FontWeight.Medium,
                        color = resolveToken(ColorToken.OnSurface),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ListBody(media: CardData.Media, resolveToken: (ColorToken) -> Color) {
    val rows = if (media.headers.isNotEmpty()) listOf(media.headers) + media.rows else media.rows
    if (rows.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEachIndexed { ri, row ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val bullet: Color = if (ri == 0 && media.headers.isNotEmpty()) {
                    resolveToken(ColorToken.Primary)
                } else {
                    resolveToken(ColorToken.SurfaceVariant)
                }
                Box(Modifier.size(5.dp).background(bullet, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(
                    row.joinToString("  "),
                    fontSize = 13.sp,
                    fontWeight = if (ri == 0 && media.headers.isNotEmpty()) FontWeight.SemiBold else FontWeight.Normal,
                    color = resolveToken(ColorToken.OnSurface),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
