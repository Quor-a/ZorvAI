package com.ai.assistance.quro.core.ui.card.compose

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ai.assistance.quro.core.ui.card.spec.CardSpec
import com.ai.assistance.quro.core.ui.card.spec.ColorToken

/**
 * 骨架卡（`skeleton`）：流式占位。
 *
 * 三处细节是「看起来不卡住」的关键：
 *  1. **固定高度**：占位条高度写死，数据到达后卡片高度不跳（否则整条消息流会抖一下）；
 *  2. **呼吸透明度**：`rememberInfiniteTransition` 让占位条在 0.35↔0.7 之间渐变 ——
 *     静态灰条在慢模型上看起来就像界面死了；
 *  3. **末行短一截**：模拟真实文本段落，比三条等长更像「正在写字」。
 */
@Composable
fun SkeletonCardView(
    spec: CardSpec,
    modifier: Modifier = Modifier,
    resolveToken: (ColorToken) -> Color,
) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "skeletonAlpha",
    )
    val base: Color = resolveToken(ColorToken.SurfaceVariant).copy(alpha = alpha)

    Column(modifier.fillMaxWidth()) {
        repeat(3) { i ->
            Box(
                Modifier
                    .fillMaxWidth(if (i == 2) 0.6f else 1f)
                    .height(12.dp)
                    .background(base, RoundedCornerShape(6.dp)),
            )
            if (i < 2) Spacer(Modifier.height(10.dp))
        }
    }
}
