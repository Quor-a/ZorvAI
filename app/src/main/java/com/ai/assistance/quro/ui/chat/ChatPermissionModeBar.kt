package com.ai.assistance.quro.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.core.policy.QuroPolicy
import com.ai.assistance.quro.core.policy.QuroPolicyStore
import com.ai.assistance.quro.ui.icons.LucideIcon
import com.ai.assistance.quro.ui.theme.Accent
import com.ai.assistance.quro.ui.theme.AccentPress
import com.ai.assistance.quro.ui.theme.AccentSoft
import com.ai.assistance.quro.ui.theme.Line
import com.ai.assistance.quro.ui.theme.Muted

/** 「允许」态绿色。 */
private val OkGreen = Color(0xFF34C759)

/** 「允许」态绿色文字（深一档，保证在浅底上的对比度）。 */
private val OkGreenText = Color(0xFF1A7A38)

/** 「禁止」态红色。 */
private val DenyRed = Color(0xFFFF3B30)

/** 选中态胶囊描边色。 */
private val ChipActiveBorder = Color(0xFFEAD3C8)

/**
 * 对话内控制条（收起/展开）：深度思考 + 记忆 + 朗读 + 看懂屏幕 + 权限模式（CMS / 特权）。
 * 从 ChatScreen.kt 抽出，对外 API 与行为保持不变。
 *
 * ## 布局塌陷根因与修复（#问题2：全选后收起/展开按钮消失）
 * 与顶栏同源：[Row] 先测量无 weight 的子项并逐个扣减剩余宽度。原实现里摘要 chips 直接铺在
 * 主 Row 中（仅套了一层 `clipToBounds`），开关全开时 chips 把宽度吃光，排在其后的
 * chevron（收起/返回按钮）被测量成 **0 宽**——不是被推出屏幕，而是真的没有宽度，
 * 所以 clip / scroll 都救不回来。
 *
 * 修复：
 * 1. 摘要 chips 区独立成一层 `Modifier.weight(1f)` 的容器，并在容器内 [horizontalScroll]，
 *    chips 溢出时横向滚动而不是挤压邻居；
 * 2. chevron 用固定 [Modifier.size] 且**不参与 weight**，永远在非 weight 阶段先拿到真实宽度；
 * 3. 展开区里的策略 chips（[PolicyChipGroup]）同样用横向滚动容器承载，三个选项在窄屏/大字号下
 *    也不会互相压扁。
 */
@Composable
internal fun ChatPermissionModeBar(
    deepThink: Boolean = false,
    onToggleThink: () -> Unit = {},
    autoSaveMemory: Boolean = true,
    onToggleAutoSave: () -> Unit = {},
    autoRead: Boolean = false,
    onToggleAutoRead: () -> Unit = {},
    visionEnabled: Boolean = false,
    onToggleVision: () -> Unit = {},
    currentWorkspace: String? = null,
    onOpenWorkspaceSelector: () -> Unit = {},
    onOpenCodeBrowser: () -> Unit = {},
) {
    val ctx = LocalContext.current
    // 惰性初始化只做一次：原实现直接在 composition body 调用（等于每次重组都跑一次 SharedPreferences 访问）
    remember(ctx) { QuroPolicyStore.getCms(ctx) }
    val cmsPolicy by QuroPolicyStore.cmsFlow.collectAsState()
    val privPolicy by QuroPolicyStore.privFlow.collectAsState()
    val cs = MaterialTheme.colorScheme
    // rememberSaveable：旋转 / 进程恢复后保持展开态，避免用户正在调权限时被折叠回去
    var expanded by rememberSaveable { mutableStateOf(false) }
    val summaryScroll = rememberScrollState()

    Surface(
        color = cs.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            // ── 收起状态：一行摘要，点击展开 ──
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(
                        onClickLabel = if (expanded) "收起权限模式" else "展开权限模式",
                        onClick = { expanded = !expanded },
                    )
                    .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 固定标题：无 weight，先被测量
                Text(
                    "权限模式",
                    fontSize = 11.sp,
                    color = Muted,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                Spacer(Modifier.width(8.dp))
                // 弹性摘要区：吃满中间剩余空间，chips 溢出横向滚动（不再挤压后面的 chevron）
                Row(
                    Modifier
                        .weight(1f)
                        .horizontalScroll(summaryScroll),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (deepThink) SummaryTag("深度思考")
                    if (autoSaveMemory) SummaryTag("记忆")
                    if (autoRead) SummaryTag("朗读")
                    if (visionEnabled) SummaryTag("看懂屏幕")
                    val cmsBg = when (cmsPolicy) {
                        QuroPolicy.ALLOW -> OkGreen.copy(alpha = 0.18f)
                        QuroPolicy.DENY -> DenyRed.copy(alpha = 0.18f)
                        else -> cs.primaryContainer
                    }
                    val cmsFg = when (cmsPolicy) {
                        QuroPolicy.ALLOW -> OkGreenText
                        QuroPolicy.DENY -> DenyRed
                        else -> cs.primary
                    }
                    SummaryTag("CMS:${policyLabel(cmsPolicy)}", background = cmsBg, foreground = cmsFg)
                    // 特权策略此前只在展开区可见，收起态无从判断 —— 补齐摘要标签
                    val privBg = when (privPolicy) {
                        QuroPolicy.ALLOW -> OkGreen.copy(alpha = 0.18f)
                        QuroPolicy.DENY -> DenyRed.copy(alpha = 0.18f)
                        else -> cs.primaryContainer
                    }
                    val privFg = when (privPolicy) {
                        QuroPolicy.ALLOW -> OkGreenText
                        QuroPolicy.DENY -> DenyRed
                        else -> cs.primary
                    }
                    SummaryTag("特权:${policyLabel(privPolicy)}", background = privBg, foreground = privFg)
                }
                Spacer(Modifier.width(6.dp))
                // 常驻收起/展开按钮：固定 40dp、不参与 weight —— 无论摘要多长都不会被压成 0 宽
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    LucideIcon(
                        if (expanded) "chevron_up" else "chevron_down",
                        if (expanded) "收起权限模式" else "展开权限模式",
                        Modifier.size(16.dp),
                        tint = Muted,
                    )
                }
            }
            // ── 展开状态：所有选项 ──
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 8.dp)) {
                    HorizontalDivider(color = Line.copy(alpha = 0.3f))
                    Spacer(Modifier.height(6.dp))

                    ModeToggleRow(
                        active = deepThink,
                        title = "深度思考",
                        desc = " — 显示 AI 推理过程",
                        onClick = onToggleThink,
                    )
                    Spacer(Modifier.height(6.dp))
                    ModeToggleRow(
                        active = autoSaveMemory,
                        title = "自动保存记忆",
                        desc = " — AI 自动沉淀长期记忆",
                        onClick = onToggleAutoSave,
                    )
                    Spacer(Modifier.height(6.dp))
                    ModeToggleRow(
                        active = autoRead,
                        title = "自动朗读",
                        desc = " — AI 回复自动朗读",
                        onClick = onToggleAutoRead,
                    )
                    Spacer(Modifier.height(6.dp))
                    ModeToggleRow(
                        active = visionEnabled,
                        title = "看懂屏幕",
                        desc = " — AI 实时理解当前屏幕",
                        onClick = onToggleVision,
                    )
                    Spacer(Modifier.height(6.dp))

                    PolicyChipGroup(
                        label = "CMS",
                        current = cmsPolicy,
                        onSet = { QuroPolicyStore.setCms(ctx, it) },
                    )
                    Spacer(Modifier.height(4.dp))
                    PolicyChipGroup(
                        label = "特权",
                        current = privPolicy,
                        onSet = { QuroPolicyStore.setPriv(ctx, it) },
                    )
                    Spacer(Modifier.height(6.dp))
                    // 工作区选择器
                    ModeToggleRow(
                        active = currentWorkspace != null,
                        title = "工作区",
                        desc = if (currentWorkspace != null) " — ${currentWorkspace.substringAfterLast('/')}" else " — 选择工作目录",
                        onClick = onOpenWorkspaceSelector,
                    )
                    Spacer(Modifier.height(6.dp))
                    // 工作区代码编辑器：写代码 / 浏览代码
                    ModeToggleRow(
                        active = false,
                        title = "代码编辑",
                        desc = " — 写代码 / 浏览工作区文件",
                        onClick = onOpenCodeBrowser,
                    )
                }
            }
        }
    }
}

/** 策略枚举 → 中文标签。 */
private fun policyLabel(p: QuroPolicy): String = when (p) {
    QuroPolicy.ALLOW -> "允许"
    QuroPolicy.DENY -> "禁止"
    QuroPolicy.ASK -> "询问"
}

/** 摘要行里的状态小标签（横向滚动容器内，永远单行不换行）。 */
@Composable
private fun SummaryTag(
    text: String,
    background: Color = AccentSoft.copy(alpha = 0.7f),
    foreground: Color = AccentPress,
) {
    Surface(color = background, shape = RoundedCornerShape(999.dp)) {
        Text(
            text,
            fontSize = 10.sp,
            color = foreground,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * 展开区的开关胶囊：圆点 + 标题 + 说明。
 *
 * 说明文字带 `weight(1f, fill = false)` + 省略号：大字号 / 窄屏下只截断自己，
 * 不会把前面的圆点与标题挤变形（原实现四段重复代码里说明文字无任何宽度约束）。
 */
@Composable
private fun ModeToggleRow(
    active: Boolean,
    title: String,
    desc: String,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(999.dp))
            .border(1.dp, if (active) ChipActiveBorder else Line, RoundedCornerShape(999.dp))
            .background(if (active) AccentSoft else cs.surface)
            .clickable(onClickLabel = if (active) "关闭$title" else "开启$title", onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (active) Accent else Muted))
        Spacer(Modifier.width(6.dp))
        Text(
            title,
            fontSize = 13.sp,
            color = if (active) AccentPress else Muted,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
        Text(
            desc,
            fontSize = 11.sp,
            color = Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/**
 * 三态策略选择器（允许 / 禁止 / 询问）。
 * label 固定在左，chips 放进横向滚动容器，窄屏或大字号下不会被压扁。
 */
@Composable
private fun PolicyChipGroup(
    label: String,
    current: QuroPolicy,
    onSet: (QuroPolicy) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val options = remember {
        listOf(
            QuroPolicy.ALLOW to "允许",
            QuroPolicy.DENY to "禁止",
            QuroPolicy.ASK to "询问",
        )
    }
    val scroll = rememberScrollState()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 11.sp, color = Muted, maxLines = 1)
        Spacer(Modifier.width(4.dp))
        Row(
            Modifier
                .weight(1f)
                .horizontalScroll(scroll),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEach { (policy, text) ->
                val selected = current == policy
                FilterChip(
                    selected = selected,
                    onClick = { onSet(policy) },
                    label = { Text(text, fontSize = 11.sp, maxLines = 1, softWrap = false) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = when (policy) {
                            QuroPolicy.DENY -> DenyRed.copy(alpha = 0.18f)
                            QuroPolicy.ALLOW -> OkGreen.copy(alpha = 0.18f)
                            QuroPolicy.ASK -> cs.primaryContainer
                        },
                        selectedLabelColor = when (policy) {
                            QuroPolicy.DENY -> DenyRed
                            QuroPolicy.ALLOW -> OkGreenText
                            QuroPolicy.ASK -> cs.primary
                        },
                    ),
                    border = null,
                    modifier = Modifier.height(32.dp),
                )
            }
        }
    }
}
