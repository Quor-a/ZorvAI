package com.ai.assistance.quro.core.ui.card.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.core.ui.card.spec.Action
import com.ai.assistance.quro.core.ui.card.spec.CardData
import com.ai.assistance.quro.core.ui.card.spec.CardSpec
import com.ai.assistance.quro.core.ui.card.spec.ColorToken

/**
 * 按钮组卡（`button_group`）：两列网格按钮，点击回传各自的 [Action]。
 *
 * - 用 `Row + weight` 自动排版，点击命中由 Compose 原生处理（旧版手算坐标 + hitTest 经常点偏）；
 * - 支持 `variant = "outline"`（描边按钮）：一主一次是最常见的确认场景，
 *   全实心会让人分不清哪个是主操作；
 * - 只有 1 个按钮时铺满整行，不硬留半格空白。
 */
@Composable
fun ButtonGroupCardView(
    spec: CardSpec,
    modifier: Modifier = Modifier,
    onAction: (Action) -> Unit,
    resolveToken: (ColorToken) -> Color,
) {
    val form = spec.data as? CardData.Form ?: return
    val buttons = form.buttons
    if (buttons.isEmpty()) return

    CardShell(spec, resolveToken, modifier, padding = 10.dp) {
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val columns = if (buttons.size == 1) 1 else 2
            buttons.chunked(columns).forEach { rowBtns ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    rowBtns.forEach { btn ->
                        val outline = btn.variant.equals("outline", true) ||
                            btn.variant.equals("text", true) ||
                            btn.variant.equals("ghost", true)
                        val mod = Modifier.weight(1f).height(40.dp)
                        if (outline) {
                            OutlinedButton(
                                onClick = { onAction(btn.action) },
                                modifier = mod,
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = resolveToken(ColorToken.Primary),
                                ),
                            ) {
                                Text(btn.label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            }
                        } else {
                            Button(
                                onClick = { onAction(btn.action) },
                                modifier = mod,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = resolveToken(ColorToken.Primary),
                                    contentColor = resolveToken(ColorToken.OnPrimary),
                                ),
                            ) {
                                Text(btn.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    // 奇数行补占位，保持两列对齐
                    if (rowBtns.size == 1 && columns == 2) {
                        Spacer(Modifier.weight(1f).padding(0.dp))
                    }
                }
            }
        }
    }
}
