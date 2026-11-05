package com.ai.assistance.quro.core.ui.card.compose

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.core.ui.card.spec.CardSpec
import com.ai.assistance.quro.core.ui.card.spec.ColorToken

/**
 * 所有小卡片共用的**外壳**：卡头（标题/副标题）+ 面板（底色/描边/圆角）+ 入场动画 + 无障碍。
 *
 * ## 为什么要有它（架构而非样式问题）
 *
 * 之前 7 个渲染器各写各的容器：metric 用 `SurfaceVariant` 圆角 12、table 用 `Surface` 无圆角、
 * status 干脆没有底色。用户看到的是「同样是小卡片，有的有底有的没底、圆角粗细都不一样」，
 * 而且每次调视觉都要改 7 个文件 —— 典型的表现层重复。
 *
 * 现在统一收口：
 *  - 视觉（底色 / 描边 / 圆角 / 内距 / 卡头字号）只在这里改；
 *  - 各渲染器只管「卡里放什么」；
 *  - 入场动画（淡入 + 8dp 上浮）只做一次，天然对 LazyColumn 复用安全（key 用 spec.id）。
 *
 * 颜色全部走 [ColorToken] + 当前 `MaterialTheme.colorScheme`：暗色主题、动态取色、
 * 字体缩放都由系统接管，卡片不需要自己判断亮暗。
 */
@Composable
fun CardShell(
    spec: CardSpec,
    resolveToken: (ColorToken) -> Color,
    modifier: Modifier = Modifier,
    accent: ColorToken? = null,
    padding: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(spec.style.cornerDp.dp)
    val bg = resolveToken(spec.style.bg)
    val border = resolveToken(ColorToken.Outline).copy(alpha = 0.45f)

    // 入场：0 → 1 只跑一次（spec.id 变化才重播，避免滚动复用卡片时满屏闪烁）
    var entered by remember(spec.id) { mutableStateOf(false) }
    LaunchedEffect(spec.id) { entered = true }
    val appear by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "cardAppear",
    )

    val a11yLabel = spec.a11y.label ?: spec.title
    Column(
        modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = appear
                translationY = (1f - appear) * 8.dp.toPx()
            }
            .clip(shape)
            .background(bg, shape)
            .border(1.dp, border, shape)
            .then(
                if (a11yLabel.isNotBlank()) {
                    Modifier.semantics { contentDescription = a11yLabel }
                } else {
                    Modifier
                },
            )
            .padding(padding),
    ) {
        if (spec.title.isNotBlank() || spec.subtitle.isNotBlank()) {
            CardHeader(spec, resolveToken, accent ?: spec.style.accent)
            Spacer(Modifier.height(10.dp))
        }
        content()
    }
}

/** 卡头：左边一条强调色竖条 + 标题（+ 副标题）。 */
@Composable
private fun CardHeader(spec: CardSpec, resolveToken: (ColorToken) -> Color, accent: ColorToken) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(width = 3.dp, height = 14.dp)
                .background(resolveToken(accent), RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.size(8.dp))
        Column(Modifier.weight(1f)) {
            if (spec.title.isNotBlank()) {
                Text(
                    spec.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = resolveToken(ColorToken.OnSurface),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (spec.subtitle.isNotBlank()) {
                Text(
                    spec.subtitle,
                    fontSize = 11.sp,
                    color = resolveToken(ColorToken.OnSurfaceVariant),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 卡内小节标题（图表上方 / 表格上方用）。
 * 与卡头区分开：卡头是一个卡片的标题，小节标题是卡片内部的分组说明。
 */
@Composable
internal fun SectionLabel(
    text: String,
    resolveToken: (ColorToken) -> Color,
    modifier: Modifier = Modifier,
) {
    if (text.isBlank()) return
    Text(
        text,
        modifier = modifier,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = resolveToken(ColorToken.OnSurfaceVariant),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 小徽标（涨跌、单位、状态）：统一圆角与字重，避免各卡各画。 */
@Composable
internal fun CardPill(
    text: String,
    bg: Color,
    fg: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clip(CircleShape)
            .background(bg)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1)
    }
}

/** 图例（多序列图表）：色点 + 名称，自动换行交给 FlowRow 的原语 —— 这里用 Row 均分。 */
@Composable
internal fun ChartLegend(
    items: List<Pair<String, Color>>,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items.take(4).forEach { (name, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(color, CircleShape))
                Spacer(Modifier.size(4.dp))
                Text(
                    name,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
