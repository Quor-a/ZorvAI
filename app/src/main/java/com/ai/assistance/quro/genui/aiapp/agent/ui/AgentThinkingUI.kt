package com.ai.assistance.quro.genui.aiapp.agent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.genui.aiapp.agent.AgentThoughtChain
import com.ai.assistance.quro.genui.aiapp.agent.ThoughtStatus
import com.ai.assistance.quro.genui.aiapp.agent.ThoughtStep
import com.ai.assistance.quro.genui.aiapp.agent.displayIcon
import com.ai.assistance.quro.genui.aiapp.agent.displayName
// R 类归属宿主 App 模块（namespace = com.ai.assistance.quro），
// 不是 aiapp 子包；上游的 com.genui.aiapp.R 已随去品牌化一并改到宿主根命名空间。
import com.ai.assistance.quro.R

/**
 * Agent 思考过程面板 — 精致重设计版
 *
 * 设计风格：
 * - 卡片式布局，圆角 + 细边框 + 微妙阴影
 * - 自定义图标替代 emoji
 * - 清晰的步骤状态指示
 * - 克制的展开/收起动画
 */
@Composable
fun AgentThinkingPanel(
    chain: AgentThoughtChain,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(true) }

    Column(modifier = modifier.fillMaxWidth()) {
        // 头部 — 标题 + 展开按钮
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
            shape = RoundedCornerShape(14.dp),
            tonalElevation = 0.dp,
            shadowElevation = 1.dp,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 思考图标（自定义 genui_spark 图标）
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                    modifier = Modifier.size(30.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(id = R.drawable.genui_spark),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))

                // 标题和进度
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "思考过程",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${chain.completedCount}/${chain.steps.size} 步 · ${
                            if (chain.isComplete) "已完成" else "进行中"
                        }",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 展开/收起图标（自定义箭头）
                val rotation by animateFloatAsState(
                    targetValue = if (expanded) 0f else -90f,
                    label = "arrow"
                )
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                    shape = CircleShape,
                    modifier = Modifier.size(24.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(id = R.drawable.genui_clear),
                            contentDescription = if (expanded) "收起" else "展开",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(12.dp)
                                .rotate(rotation + 90f) // 调整方向
                        )
                    }
                }
            }
        }

        // 步骤列表
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                chain.steps.forEach { step ->
                    ThoughtStepItem(step = step)
                }
            }
        }
    }
}

/**
 * 单个思考步骤项 — 精致版
 */
@Composable
private fun ThoughtStepItem(
    step: ThoughtStep,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val hasContent = step.content.isNotBlank()

    Surface(
        color = when (step.status) {
            ThoughtStatus.RUNNING -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            ThoughtStatus.SUCCESS -> MaterialTheme.colorScheme.surface
            ThoughtStatus.FAILED -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
            ThoughtStatus.PENDING -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            ThoughtStatus.SKIPPED -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
        },
        shape = RoundedCornerShape(10.dp),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = modifier
            .fillMaxWidth()
            .then(if (hasContent) Modifier.clickable { expanded = !expanded } else Modifier)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // 状态图标
                StepStatusIcon(status = step.status)

                Spacer(Modifier.width(8.dp))

                // 步骤图标 + 标题
                // 用一个小方块承载 emoji 图标（保持简洁）
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.size(22.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = step.type.displayIcon,
                            fontSize = 12.sp
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = step.title.ifBlank { step.type.displayName },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = when (step.status) {
                        ThoughtStatus.RUNNING -> MaterialTheme.colorScheme.primary
                        ThoughtStatus.FAILED -> MaterialTheme.colorScheme.error
                        ThoughtStatus.SKIPPED -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // 展开箭头
                if (hasContent) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        shape = CircleShape,
                        modifier = Modifier.size(18.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            val rotation by animateFloatAsState(
                                targetValue = if (expanded) 0f else -90f,
                                label = "step_arrow"
                            )
                            Icon(
                                painter = painterResource(id = R.drawable.genui_clear),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .size(10.dp)
                                    .rotate(rotation + 90f)
                            )
                        }
                    }
                }
            }

            // 展开的详细内容
            AnimatedVisibility(
                visible = expanded && hasContent,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.padding(top = 10.dp, start = 24.dp)) {
                    // 内容
                    Text(
                        text = step.content,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )

                    // 工具调用详情
                    if (step.toolCall != null) {
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = "工具: ${step.toolCall.toolName}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                if (step.toolCall.result != null) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = step.toolCall.result.take(200),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 步骤状态图标 — 精致版
 */
@Composable
private fun StepStatusIcon(
    status: ThoughtStatus,
    modifier: Modifier = Modifier
) {
    val size = 18.dp
    when (status) {
        ThoughtStatus.RUNNING -> {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
                modifier = modifier.size(size)
            )
        }
        ThoughtStatus.SUCCESS -> {
            Box(
                modifier = modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "✓",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        ThoughtStatus.FAILED -> {
            Box(
                modifier = modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "×",
                    color = MaterialTheme.colorScheme.onError,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        ThoughtStatus.PENDING -> {
            Box(
                modifier = modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {}
        }
        ThoughtStatus.SKIPPED -> {
            Box(
                modifier = modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outlineVariant),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "→",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * 紧凑版思考指示器（显示在生成中页面顶部）
 */
@Composable
fun ThoughtCompactIndicator(
    chain: AgentThoughtChain,
    modifier: Modifier = Modifier
) {
    val currentStep = chain.currentStep

    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 0.dp,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (currentStep != null) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = currentStep.type.displayIcon + " " + currentStep.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${chain.completedCount}/${chain.steps.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = "准备中...",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
