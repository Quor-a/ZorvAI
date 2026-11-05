package com.ai.assistance.quro.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.R
import com.ai.assistance.quro.core.chat.TurnOutcome
import com.ai.assistance.quro.ui.icons.LucideIcon
import com.ai.assistance.quro.util.qstr

/**
 * 一轮结束后的**状态条**：告诉用户「这一轮为什么是这个结果」「下一步能做什么」。
 *
 * ## 为什么不用一条消息
 *
 * 归因不是对话内容。把它写成 assistant 消息会带来三个副作用：
 * 1. 污染历史（下次回放/导出时它看起来像 AI 说的话）；
 * 2. 被喂回模型（白白占上下文，还可能被模型当成「我上一轮确实说过这句」）；
 * 3. 无法随重试消失（消息一旦落盘就得再删一次）。
 *
 * 所以做成**按会话、按轮次的瞬时状态**：重试即清、切会话保留、不落盘。
 *
 * ## 显示策略
 *
 * 只显示「用户读不到有效答复」且**现有红字没说清楚**的情况：
 * - [TurnOutcome.FAILED]：已经有「⚠️ 回复生成失败：…」气泡，这里不再叠一层；
 * - [TurnOutcome.OK] / [TurnOutcome.STOPPED_BY_USER] / [TurnOutcome.SUPERSEDED]：不是故障，不显示。
 */
@Composable
fun TurnOutcomeBar(
    outcome: TurnOutcome,
    onRetry: () -> Unit,
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val textRes = messageRes(outcome) ?: return
    val cs = MaterialTheme.colorScheme
    // 颜色按「能不能自救」分级：能自己接着处理的（截断/空回复）用中性色，
    // 需要换模型的（风控/畸形调用）用警示色，避免所有提示都长一个样。
    val tone = when (outcome) {
        TurnOutcome.CONTENT_FILTERED,
        TurnOutcome.MALFORMED_TOOL_CALL -> Color(0xFFF59E0B)

        TurnOutcome.OUTPUT_TRUNCATED,
        TurnOutcome.EMPTY_RESPONSE,
        TurnOutcome.EMPTY_AFTER_TOOLS -> Color(0xFF3B82F6)

        else -> Color(0xFF94A3B8)
    }

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(tone.copy(alpha = 0.07f))
            .border(0.5.dp, tone.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .padding(horizontal = 11.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LucideIcon("qic_warn", null, Modifier.size(13.dp), tint = tone)
            Spacer(Modifier.width(7.dp))
            Text(
                qstr(R.string.qk_04440),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = tone,
            )
        }
        Spacer(Modifier.width(0.dp))
        Text(
            qstr(textRes),
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = cs.onSurface.copy(alpha = 0.82f),
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (canContinue(outcome)) {
                TextButton(onClick = onContinue) {
                    Text(qstr(R.string.qk_04449), fontSize = 11.sp, color = tone)
                }
            }
            if (canRetry(outcome)) {
                TextButton(onClick = onRetry) {
                    Text(qstr(R.string.qk_04448), fontSize = 11.sp, color = tone)
                }
            }
            TextButton(onClick = onDismiss) {
                Text(qstr(R.string.qk_04450), fontSize = 11.sp, color = cs.onSurface.copy(alpha = 0.55f))
            }
        }
    }
}

/** 归因 → 说明文案；返回 null 表示这种结论不需要额外解释。 */
private fun messageRes(o: TurnOutcome): Int? = when (o) {
    TurnOutcome.CONTENT_FILTERED -> R.string.qk_04441
    TurnOutcome.OUTPUT_TRUNCATED -> R.string.qk_04442
    TurnOutcome.REASONING_ONLY -> R.string.qk_04443
    TurnOutcome.EMPTY_AFTER_TOOLS -> R.string.qk_04444
    TurnOutcome.UNEXECUTABLE_TOOL_CALL -> R.string.qk_04445
    TurnOutcome.EMPTY_RESPONSE -> R.string.qk_04446
    TurnOutcome.MALFORMED_TOOL_CALL -> R.string.qk_04447
    TurnOutcome.OK,
    TurnOutcome.FAILED,
    TurnOutcome.STOPPED_BY_USER,
    TurnOutcome.SUPERSEDED -> null
}

/** 「继续」只对「模型话没说完 / 没接上」有意义；风控与畸形调用继续也没用。 */
private fun canContinue(o: TurnOutcome): Boolean = when (o) {
    TurnOutcome.OUTPUT_TRUNCATED,
    TurnOutcome.EMPTY_AFTER_TOOLS -> true

    else -> false
}

/** 「重试」对所有非正常收尾都说得通，唯独风控重复请求仍会被拦。 */
private fun canRetry(o: TurnOutcome): Boolean = o != TurnOutcome.CONTENT_FILTERED
