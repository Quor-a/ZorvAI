package com.ai.assistance.quro.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.R
import com.ai.assistance.quro.core.chat.AllowScope
import com.ai.assistance.quro.core.chat.ApprovalDecision
import com.ai.assistance.quro.core.chat.ApprovalFact
import com.ai.assistance.quro.core.chat.ApprovalRequest
import com.ai.assistance.quro.util.qstr

/**
 * 工具审批卡：危险工具执行前，让用户核对「实际要做什么」并拍板。
 *
 * 1. 卡片顶部是**模型自述的摘要**，但紧接着一行醒目的防注入提示：
 *    「以上是 AI 的自述，请以下方的实际调用为准」——模型可以撒谎，事实项才是真的。
 * 2. 「实际调用」区展示拆解后的事实项（工具名 + 参数），强调项（emphasis）用警示色。
 * 3. 三档允许粒度：仅这一次 / 本会话 / 总是允许。
 * 4. 拒绝时可填审查说明，作为错误回喂给模型。
 */
@Composable
fun ApprovalCard(
    request: ApprovalRequest,
    scaled: (Int) -> androidx.compose.ui.unit.TextUnit = { it.sp },
    onAllow: (ApprovalRequest, AllowScope) -> Unit,
    onDeny: (ApprovalRequest, String?) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val warning = Color(0xFFF59E0B)
    val danger = Color(0xFFEF4444)

    // 是否展开「拒绝 + 审查说明」区
    var denyExpanded by remember(request.id) { mutableStateOf(false) }
    var reviewNote by remember(request.id) { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            // 🔴 不透明背景：旧实现 0.6f 半透明，Dialog 里下面的聊天文字整个透上来，
            //    授权卡上的事实项根本看不清（用户实测：「授权UI背景有点看不清」）。
            .background(cs.surfaceVariant)
            .border(1.dp, warning.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ── 标题行 ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "⚠",
                fontSize = scaled(16),
                color = warning,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = qstr(R.string.qk_04500), // 需要你批准
                fontSize = scaled(15),
                color = cs.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = request.toolLabel,
                fontSize = scaled(12),
                color = cs.onSurfaceVariant,
            )
        }

        // ── 模型自述（不可信）──
        Text(
            text = request.summary,
            fontSize = scaled(14),
            color = cs.onSurface,
        )

        // ── 防注入提示（关键设计）──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(warning.copy(alpha = 0.12f))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("ℹ", fontSize = scaled(13), color = warning)
            Spacer(Modifier.width(8.dp))
            Text(
                text = qstr(R.string.qk_04509), // 以上是 AI 的自述，请以下方的实际调用为准
                fontSize = scaled(12),
                color = warning,
                fontWeight = FontWeight.Medium,
            )
        }

        // ── 风险提示（可选）──
        if (!request.risk.isNullOrBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("▲", fontSize = scaled(13), color = danger)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${qstr(R.string.qk_04514)}：${request.risk}",
                    fontSize = scaled(12),
                    color = danger,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        // ── 实际调用（可核对的事实项）──
        Text(
            text = qstr(R.string.qk_04512), // 实际调用
            fontSize = scaled(12),
            color = cs.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        request.facts.forEach { fact ->
            FactRow(fact, scaled, cs.onSurface, warning, cs.onSurfaceVariant)
        }
        if (request.omittedArguments > 0) {
            Text(
                text = qstr(R.string.qk_04510, request.omittedArguments), // …还有 N 个参数
                fontSize = scaled(12),
                color = cs.onSurfaceVariant,
            )
        }

        // ── 拒绝展开区 ──
        if (denyExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = qstr(R.string.qk_04511), // 审查说明（可选）
                    fontSize = scaled(12),
                    color = cs.onSurfaceVariant,
                )
                // 简单文本框（审查说明）
                androidx.compose.foundation.text.BasicTextField(
                    value = reviewNote,
                    onValueChange = { reviewNote = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(cs.surface)
                        .border(1.dp, cs.outlineVariant, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = scaled(13),
                        color = cs.onSurface,
                    ),
                    maxLines = 3,
                )
            }
        }

        // ── 操作按钮区 ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 拒绝
            ActionButton(
                label = qstr(R.string.qk_04507), // 拒绝
                color = danger,
                scaled = scaled,
                onClick = {
                    if (denyExpanded) {
                        onDeny(request, reviewNote.takeIf { it.isNotBlank() })
                    } else {
                        denyExpanded = true
                    }
                },
            )
            Spacer(Modifier.weight(1f))
            // 三档允许
            ActionButton(
                label = qstr(R.string.qk_04504), // 仅这一次
                color = cs.primary,
                filled = true,
                scaled = scaled,
                onClick = { onAllow(request, AllowScope.ONCE) },
            )
            ActionButton(
                label = qstr(R.string.qk_04505), // 本会话允许
                color = cs.primary,
                scaled = scaled,
                onClick = { onAllow(request, AllowScope.SESSION) },
            )
            ActionButton(
                label = qstr(R.string.qk_04506), // 总是允许
                color = cs.primary,
                scaled = scaled,
                onClick = { onAllow(request, AllowScope.ALWAYS) },
            )
        }
    }
}

/** 单条事实项。 */
@Composable
private fun FactRow(
    fact: ApprovalFact,
    scaled: (Int) -> androidx.compose.ui.unit.TextUnit,
    normalColor: Color,
    emphasisColor: Color,
    mutedColor: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = fact.label,
            fontSize = scaled(12),
            color = mutedColor,
            modifier = Modifier.width(72.dp),
        )
        Text(
            text = fact.value,
            fontSize = scaled(13),
            color = if (fact.emphasis) emphasisColor else normalColor,
            fontWeight = if (fact.emphasis) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 审批操作按钮（胶囊）。 */
@Composable
private fun ActionButton(
    label: String,
    color: Color,
    scaled: (Int) -> androidx.compose.ui.unit.TextUnit,
    filled: Boolean = false,
    onClick: () -> Unit,
) {
    androidx.compose.material3.Text(
        text = label,
        fontSize = scaled(12),
        color = if (filled) androidx.compose.material3.MaterialTheme.colorScheme.onPrimary else color,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (filled) color else color.copy(alpha = 0.12f))
            .border(
                1.dp,
                if (filled) Color.Transparent else color.copy(alpha = 0.4f),
                RoundedCornerShape(18.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}
