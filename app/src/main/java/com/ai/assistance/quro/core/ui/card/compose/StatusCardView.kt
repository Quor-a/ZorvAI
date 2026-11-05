package com.ai.assistance.quro.core.ui.card.compose

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.R
import com.ai.assistance.quro.core.ui.card.spec.Action
import com.ai.assistance.quro.core.ui.card.spec.CardData
import com.ai.assistance.quro.core.ui.card.spec.CardSpec
import com.ai.assistance.quro.core.ui.card.spec.ColorToken
import kotlin.math.roundToInt

/**
 * 状态卡（`status`）：progress / error / tool_call / 其它。
 *
 * 用 Compose 的 `LinearProgressIndicator` + `Text` + `clickable`，彻底替换旧版手绘进度条与
 * 手算命中区。相比旧版补了两处：
 *  - 进度百分比数字（只有条没有数字时，用户无法判断「还要多久」）；
 *  - `tool_call` 的呼吸圆点（长时间等待时给出「还在动」的信号，而不是像卡死了）。
 */
@Composable
fun StatusCardView(
    spec: CardSpec,
    modifier: Modifier = Modifier,
    onAction: (Action) -> Unit,
    resolveToken: (ColorToken) -> Color,
) {
    val st = spec.data as? CardData.Status ?: return
    CardShell(spec, resolveToken, modifier, padding = 12.dp) {
        when (st.statusType.trim().lowercase()) {
            "progress" -> {
                val p = st.progress.coerceIn(0f, 1f)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        st.text.ifEmpty { "进度" },
                        fontSize = 13.sp,
                        color = resolveToken(ColorToken.OnSurface),
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${(p * 100).roundToInt()}%",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = resolveToken(ColorToken.Primary),
                    )
                }
                Spacer(Modifier.height(7.dp))
                LinearProgressIndicator(
                    progress = { p },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = resolveToken(ColorToken.Primary),
                    trackColor = resolveToken(ColorToken.SurfaceVariant),
                )
            }
            "error" -> {
                Text(
                    st.text.ifBlank { stringResource(R.string.qk_00255) },
                    fontSize = 13.sp,
                    color = resolveToken(ColorToken.Danger),
                    fontWeight = FontWeight.SemiBold,
                )
                val reason = st.reason.orEmpty()
                if (reason.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (reason.length > 80) reason.take(80) + "…" else reason,
                        fontSize = 12.sp,
                        color = resolveToken(ColorToken.OnSurfaceVariant),
                    )
                }
                if (st.retryable) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "点击重试",
                        fontSize = 13.sp,
                        color = resolveToken(ColorToken.Primary),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { spec.actions.firstOrNull()?.let(onAction) },
                    )
                }
            }
            "tool_call" -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BreathingDot(resolveToken(ColorToken.Primary))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        st.text.ifEmpty { "工具调用中…" },
                        fontSize = 13.sp,
                        color = resolveToken(ColorToken.OnSurface),
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            "skeleton" -> {
                // 走到这里的 skeleton 多半是流式装配早期：给三条占位条而不是一片空白
                Column {
                    repeat(3) { i ->
                        androidx.compose.foundation.layout.Box(
                            Modifier
                                .fillMaxWidth(if (i == 2) 0.55f else 1f)
                                .height(9.dp)
                                .background(resolveToken(ColorToken.SurfaceVariant), CircleShape),
                        )
                        if (i < 2) Spacer(Modifier.height(8.dp))
                    }
                }
            }
            else -> {
                Text(
                    st.text.ifEmpty { st.statusType },
                    fontSize = 13.sp,
                    color = resolveToken(ColorToken.OnSurfaceVariant),
                )
            }
        }
    }
}

/** 呼吸圆点：无限循环缩放 + 透明度，用来表达「还在进行」。 */
@Composable
private fun BreathingDot(color: Color) {
    val transition = rememberInfiniteTransition(label = "breath")
    val scale by transition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "breathScale",
    )
    androidx.compose.foundation.layout.Box(
        Modifier
            .size(7.dp)
            .background(color.copy(alpha = scale), CircleShape)
            .padding(0.dp),
    )
}
