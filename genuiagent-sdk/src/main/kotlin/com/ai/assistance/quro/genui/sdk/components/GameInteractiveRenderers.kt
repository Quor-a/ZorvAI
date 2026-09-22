package com.ai.assistance.quro.genui.sdk.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.genui.sdk.dsl.UIComponent
import com.ai.assistance.quro.genui.sdk.render.RenderContext

/**
 * 动态游戏互动组件（对话式游戏架构）：
 * 界面即游戏板 —— 交互元素用 send_message 回传操作，AI 重绘下一状态。
 */

/** dice_display — 骰子（props: value:1-6, label） */
@Composable
fun DiceDisplayRenderer(c: UIComponent, ctx: RenderContext) {
    val v = (c.properties["value"]?.toString()?.filter { it.isDigit() }?.toIntOrNull() ?: 1).coerceIn(1, 6)
    val pips = mapOf(
        1 to listOf(4), 2 to listOf(0, 8), 3 to listOf(0, 4, 8),
        4 to listOf(0, 2, 6, 8), 5 to listOf(0, 2, 4, 6, 8), 6 to listOf(0, 2, 3, 5, 6, 8)
    )[v] ?: listOf(4)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
        Box(
            Modifier.size(64.dp).clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(listOf(Color.White, ctx.theme.colorScheme.infoContainer)))
                .padding(10.dp)
        ) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
                repeat(3) { r ->
                    Row(Modifier.fillMaxSize().weight(1f)) {
                        repeat(3) { cc ->
                            Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                                if ((r * 3 + cc) in pips) Box(Modifier.size(9.dp).clip(CircleShape).background(ctx.theme.colorScheme.onSurface))
                            }
                        }
                    }
                }
            }
        }
        Text(c.properties["label"]?.toString() ?: "掷出了 $v 点", fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
}
