package com.ai.assistance.quro.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.ui.data.CitationUi
import com.ai.assistance.quro.ui.icons.LucideIcon

/**
 * 引用溯源（搜索来源卡片）：消息底部渲染，形态对齐「工具调用 / 思考」的活动流展示。
 *
 * ## 旧实现的问题（细节不到位）
 * 只有一个孤零零的「N 个来源」胶囊，来源列表藏在底部弹窗里：
 * 用户看不到有哪些来源、哪个域名，必须先点胶囊 → 再弹窗 → 再点条目，三层才能到达一个链接。
 * 与思考折叠行、工具活动流「一眼可见、逐条明细」的展示方式割裂。
 *
 * ## 新形态（对齐 ActivityStreamView / ThinkFoldRow 的视觉语言）
 * ```
 * 🌐 5 个来源 · zhihu.com、wikipedia.org 等        ⌄    ← 汇总行（域名预览，可点展开）
 * ──────────────────────────────────────
 * (1) 标题A · a.com                                      ← 逐条明细（点即打开链接）
 * (2) 标题B · b.org
 * ```
 * - 收起：一行汇总 + 前两个域名预览（不点也能扫到信息量）；
 * - 展开：surfaceVariant 圆角块内逐条列出，编号徽章 + 标题 + 域名，点击直接开链接；
 * - chevron 旋转过渡、条目按压泛色，均与活动流一致。
 */
@Composable
fun CitationCapsules(
    citations: List<CitationUi>,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (citations.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    // 🔴 与 ActivityStream 同款教训：不能用 citations 对象当 remember key——
    //    流式聚合每帧产出新 List，会把用户刚收起的状态重置回展开。
    //    改用条目数：来源数量真变了才重置。
    var open by remember(citations.size) { mutableStateOf(false) }

    // 域名预览：前两个 + 「等」。全部同域时不去重也只会多显示一遍，可接受。
    val domainPreview = remember(citations) {
        val doms = citations.map { it.domain }.filter { it.isNotBlank() }.distinct()
        when {
            doms.isEmpty() -> ""
            doms.size <= 2 -> doms.joinToString("、")
            else -> "${doms.take(2).joinToString("、")} 等"
        }
    }

    Column(modifier.fillMaxWidth()) {
        // ── 汇总行（对齐 ActivityStream 汇总行 / ThinkFoldRow 的轻量行形态）──
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable { open = !open }
                .padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LucideIcon("qic_web", null, Modifier.size(13.dp), tint = cs.primary.copy(alpha = 0.75f))
            Spacer(Modifier.width(7.dp))
            Text(
                buildString {
                    append("${citations.size} 个来源")
                    if (domainPreview.isNotBlank()) append(" · $domainPreview")
                },
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = cs.onSurface.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(4.dp))
            // chevron 旋转过渡（展开朝上、收起回正），与活动流汇总行一致。
            val chevronRot by animateFloatAsState(
                targetValue = if (open) 180f else 0f,
                animationSpec = tween(220),
                label = "citationChevron",
            )
            LucideIcon(
                "chevron_down",
                null,
                Modifier.size(13.dp).rotate(chevronRot),
                tint = cs.onSurface.copy(alpha = 0.5f),
            )
        }

        // ── 逐条明细（对齐 ActivityRow：图标/徽章 + 文案、按压泛色、点击直达）──
        AnimatedVisibility(
            visible = open,
            enter = fadeIn(tween(160)) + expandVertically(tween(200)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(200)),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(cs.surfaceVariant.copy(alpha = 0.5f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                citations.forEachIndexed { idx, c ->
                    if (idx > 0) HorizontalDivider(
                        color = cs.onSurface.copy(alpha = 0.06f),
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                    CitationRow(c) { onOpenUrl(c.url) }
                }
            }
        }
    }
}

/**
 * 单条来源行：编号徽章 + 标题 + 域名，点击直接打开链接。
 * 按压时背景泛一层淡色（与 ActivityRow 同款反馈）。
 */
@Composable
private fun CitationRow(c: CitationUi, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg = if (pressed) cs.onSurface.copy(alpha = 0.06f) else Color.Transparent
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 编号徽章：正文 [n] 角标 / AI 引用序号的可视对应。
        Box(
            Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(cs.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "${c.index}",
                color = cs.primary,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                c.title.ifBlank { c.url },
                fontSize = 13.sp,
                color = cs.onSurface.copy(alpha = 0.86f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (c.domain.isNotBlank()) {
                Text(
                    c.domain,
                    fontSize = 11.sp,
                    color = cs.onSurface.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        LucideIcon("chevron_right", null, Modifier.size(13.dp), tint = cs.onSurface.copy(alpha = 0.35f))
    }
}
