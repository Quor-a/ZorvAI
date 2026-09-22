@file:OptIn(ExperimentalMaterial3Api::class)

package com.ai.assistance.quro.genui.sdk.components.patterns

import androidx.compose.material3.minimumInteractiveComponentSize

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.ai.assistance.quro.genui.sdk.components.IconMapper
import com.ai.assistance.quro.genui.sdk.components.propBool
import com.ai.assistance.quro.genui.sdk.components.propFloat
import com.ai.assistance.quro.genui.sdk.components.propInt
import com.ai.assistance.quro.genui.sdk.components.propString
import com.ai.assistance.quro.genui.sdk.components.propStringResolved
import com.ai.assistance.quro.genui.sdk.dsl.UIComponent
import com.ai.assistance.quro.genui.sdk.render.RenderChildren
import com.ai.assistance.quro.genui.sdk.render.RenderContext
import com.ai.assistance.quro.genui.sdk.render.StyleResolver

// ============================================================================
// 卡片模式 Card Patterns
// ============================================================================

/**
 * 信息卡 Info Card
 * properties: title, subtitle, description, icon, actionText
 */
@Composable
fun InfoCardPattern(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val scheme = ctx.theme.colorScheme
    val title = component.propStringResolved("title", ctx) ?: ""
    val subtitle = component.propStringResolved("subtitle", ctx)
    val description = component.propStringResolved("description", ctx)
    val iconName = component.propString("icon")
    val actionText = component.propString("actionText")
    val level = component.propString("level") ?: "primary"

    val accentColor = when (level) {
        "success" -> scheme.primary
        "error" -> scheme.error
        "warning" -> scheme.primary
        "info" -> scheme.info
        else -> scheme.primary
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = scheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // 标题行
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (iconName != null) {
                    Surface(
                        color = accentColor.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            val iconVec = IconMapper.map(iconName)
                            Icon(
                                imageVector = iconVec,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = scheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 描述
            if (description != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    lineHeight = 22.sp
                )
            }

            // 自定义内容
            if (component.children.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                RenderChildren(component.children, ctx)
            }

            // 操作按钮
            if (actionText != null) {
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = actionText,
                        color = accentColor,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Icon(
                        imageVector = Icons.Filled.ArrowForward,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

/**
 * 统计卡 Stat Card
 * properties: title, value, change, changePositive, trendLabel, icon
 */
@Composable
fun StatCardPattern(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val scheme = ctx.theme.colorScheme
    val title = component.propStringResolved("title", ctx) ?: ""
    val value = component.propStringResolved("value", ctx) ?: "0"
    val change = component.propStringResolved("change", ctx)
    val changePositive = component.propBool("changePositive", true)
    val iconName = component.propString("icon")

    // 中国股市约定：涨=红、跌=绿（与欧美相反），因此走 rise/fall 语义色而非 success/error
    val changeColor = if (changePositive) scheme.rise else scheme.fall

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = scheme.surface,
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 标题 + 图标
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (iconName != null) {
                    val iconVec = IconMapper.map(iconName)
                    Icon(
                        imageVector = iconVec,
                        contentDescription = null,
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // 数值
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                color = scheme.onSurface,
                fontWeight = FontWeight.Bold
            )

            // 变化
            if (change != null) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.ArrowForward,
                        contentDescription = null,
                        tint = changeColor,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = change,
                        style = MaterialTheme.typography.bodySmall,
                        color = changeColor,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // 自定义内容（如迷你图）
            if (component.children.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                RenderChildren(component.children, ctx)
            }
        }
    }
}

/**
 * 媒体卡 Media Card
 * properties: title, subtitle, imageUrl, tag, likes, comments
 */
@Composable
fun MediaCardPattern(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val scheme = ctx.theme.colorScheme
    val title = component.propStringResolved("title", ctx) ?: ""
    val subtitle = component.propStringResolved("subtitle", ctx)
    val tag = component.propString("tag")
    val imageUrl = component.propString("imageUrl")
    val aspectRatio = component.propFloat("aspectRatio", 1.5f)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = scheme.surface,
        tonalElevation = 2.dp
    ) {
        Column {
            // 图片区域
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .background(scheme.surfaceVariant.copy(alpha = 0.3f))
            ) {
                // 占位图
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                colors = listOf(
                                    scheme.primaryContainer.copy(alpha = 0.3f),
                                    scheme.primary.copy(alpha = 0.1f)
                                )
                            )
                        )
                )

                // 标签
                if (tag != null) {
                    Surface(
                        color = scheme.primary,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .padding(12.dp)
                            .align(Alignment.TopStart)
                    ) {
                        Text(
                            text = tag,
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // 文字区域
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2
                )
                if (subtitle != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }

                // 自定义内容
                if (component.children.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    RenderChildren(component.children, ctx)
                }
            }
        }
    }
}

/**
 * 列表项模式 List Item Pattern
 * properties: title, subtitle, icon, trailing, showArrow, showDivider
 */
@Composable
fun ListItemPattern(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val scheme = ctx.theme.colorScheme
    val title = component.propStringResolved("title", ctx) ?: ""
    val subtitle = component.propStringResolved("subtitle", ctx)
    val iconName = component.propString("icon")
    val trailing = component.propString("trailing")
    val showArrow = component.propBool("showArrow", true)
    val showDivider = component.propBool("showDivider", true)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧图标
            if (iconName != null) {
                Surface(
                    color = scheme.primaryContainer.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        val iconVec = IconMapper.map(iconName)
                        Icon(
                            imageVector = iconVec,
                            contentDescription = null,
                            tint = scheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
            }

            // 中间文字
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onSurface
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                }
            }

            // 尾部
            if (trailing != null) {
                Text(
                    text = trailing,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant
                )
                if (showArrow) Spacer(Modifier.width(6.dp))
            }
            if (showArrow) {
                Icon(
                    imageVector = Icons.Filled.ArrowForward,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        if (showDivider) {
            HorizontalDivider(
                color = scheme.outlineVariant.copy(alpha = 0.3f),
                thickness = 0.5.dp
            )
        }
    }
}

// ============================================================================
// 布局模式 Layout Patterns
// ============================================================================

/**
 * 双列网格 Two Column Grid
 * properties: columns, spacing
 */
@Composable
fun TwoColumnGridPattern(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val children = component.children
    if (children.isEmpty()) return

    val scheme = ctx.theme.colorScheme
    val columns = component.propInt("columns", 2)

    Column(modifier = modifier.fillMaxWidth()) {
        val rows = children.chunked(columns)
        rows.forEach { rowChildren ->
            Row(modifier = Modifier.fillMaxWidth()) {
                rowChildren.forEachIndexed { index, child ->
                    Box(modifier = Modifier.weight(1f)) {
                        RenderChildren(listOf(child), ctx)
                    }
                    if (index < rowChildren.size - 1) {
                        Spacer(Modifier.width(12.dp))
                    }
                }
                // 补齐空列
                if (rowChildren.size < columns) {
                    repeat(columns - rowChildren.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
            if (rows.last() != rowChildren) {
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

/**
 * 分区 Section
 * properties: title, actionText, showDivider
 */
@Composable
fun SectionPattern(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val scheme = ctx.theme.colorScheme
    val title = component.propStringResolved("title", ctx)
    val actionText = component.propString("actionText")
    val showDivider = component.propBool("showDivider", false)

    Column(modifier = modifier.fillMaxWidth()) {
        // 标题栏
        if (title != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (actionText != null) {
                    Text(
                        text = actionText,
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // 内容
        RenderChildren(component.children, ctx)

        // 分隔线
        if (showDivider) {
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(
                color = scheme.outlineVariant.copy(alpha = 0.3f),
                thickness = 0.5.dp
            )
        }
    }
}

/**
 * 标题栏 Header Bar
 * properties: title, subtitle, leftIcon, rightAction, rightIcon
 */
@Composable
fun HeaderBarPattern(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val scheme = ctx.theme.colorScheme
    val title = component.propStringResolved("title", ctx) ?: ""
    val subtitle = component.propStringResolved("subtitle", ctx)
    val leftIcon = component.propString("leftIcon")
    val rightIcon = component.propString("rightIcon")
    val rightAction = component.propString("rightAction")
    val bgColor = component.propString("backgroundColor")

    val containerColor = if (bgColor != null) {
        StyleResolver.resolveColor(bgColor, scheme, scheme.surface)
    } else {
        scheme.surface
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = containerColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧图标
            if (leftIcon != null) {
                val iconVec = IconMapper.map(leftIcon)
                Icon(
                    imageVector = iconVec,
                    contentDescription = null,
                    tint = scheme.onSurface,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(12.dp))
            }

            // 标题
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = scheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                }
            }

            // 右侧操作
            when {
                rightAction != null -> {
                    Text(
                        text = rightAction,
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
                rightIcon != null -> {
                    val iconVec = IconMapper.map(rightIcon)
                    Icon(
                        imageVector = iconVec,
                        contentDescription = null,
                        tint = scheme.onSurface,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

// ============================================================================
// 状态模式 State Patterns
// ============================================================================

/**
 * 空状态 Empty State
 * properties: title, description, icon, buttonText
 */
@Composable
fun EmptyStatePattern(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val scheme = ctx.theme.colorScheme
    val title = component.propStringResolved("title", ctx) ?: "暂无数据"
    val description = component.propStringResolved("description", ctx)
    val iconName = component.propString("icon") ?: "info"
    val buttonText = component.propString("buttonText")

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 图标
        Surface(
            color = scheme.surfaceVariant.copy(alpha = 0.3f),
            shape = CircleShape,
            modifier = Modifier.size(72.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                val iconVec = IconMapper.map(iconName)
                Icon(
                    imageVector = iconVec,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // 标题
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = scheme.onSurface,
            fontWeight = FontWeight.SemiBold
        )

        // 描述
        if (description != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }

        // 按钮
        if (buttonText != null) {
            Spacer(Modifier.height(20.dp))
            Surface(
                color = scheme.primary,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.minimumInteractiveComponentSize().clickable {
                    ctx.clickHandler(component, "onAction")?.invoke()
                }
            ) {
                Text(
                    text = buttonText,
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onPrimary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // 自定义内容
        if (component.children.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            RenderChildren(component.children, ctx)
        }
    }
}

/**
 * 加载状态 Loading State
 * properties: text, type (spinner/bar/skeleton)
 */
@Composable
fun LoadingStatePattern(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val scheme = ctx.theme.colorScheme
    val text = component.propString("text")
    val type = component.propString("type") ?: "spinner"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (type) {
            "bar" -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(scheme.surfaceVariant)
                ) {
                    Box(
                        modifier = Modifier
                            .width(80.dp)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(scheme.primary)
                    )
                }
            }
            else -> {
                // Spinner 效果（用圆+动画感）
                Box(
                    modifier = Modifier.size(40.dp)
                ) {
                    // 背景圆
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .border(3.dp, scheme.surfaceVariant, CircleShape)
                    )
                    // 前景弧
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .border(3.dp, scheme.primary, CircleShape)
                    )
                }
            }
        }

        if (text != null) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant
            )
        }
    }
}

// ============================================================================
// 标签徽章模式 Chip & Badge Patterns
// ============================================================================

/**
 * 标签行 Chip Row
 * properties: chips (array of strings)
 */
@Composable
fun ChipRowPattern(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val scheme = ctx.theme.colorScheme
    val chipsArray = component.properties["chips"] as? JsonArray
    val chips = chipsArray?.mapNotNull {
        (it as? JsonPrimitive)?.contentOrNull
    } ?: emptyList()

    if (chips.isEmpty()) {
        RenderChildren(component.children, ctx)
        return
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        chips.take(5).forEach { chipText ->
            Surface(
                color = scheme.primaryContainer.copy(alpha = 0.4f),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    text = chipText as String,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.primary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/**
 * 评分行 Rating Row
 * properties: rating (1-5), maxStars, showValue, size
 */
@Composable
fun RatingRowPattern(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val scheme = ctx.theme.colorScheme
    val rating = component.propFloat("rating", 0f)
    val maxStars = component.propInt("maxStars", 5)
    val showValue = component.propBool("showValue", false)
    val size = component.propFloat("size", 16f)

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(maxStars) { index ->
            val filled = (index + 1) <= rating
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = "${index + 1}",
                tint = if (filled) scheme.primary else scheme.outlineVariant,
                modifier = Modifier.size(size.dp)
            )
            if (index < maxStars - 1) {
                Spacer(Modifier.width(2.dp))
            }
        }
        if (showValue) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${rating.toInt()}.0",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// ============================================================================
// 进度模式 Progress Patterns
// ============================================================================

/**
 * 进度条带文字 Progress Bar with Label
 * properties: value, label, showPercent, height
 */
@Composable
fun ProgressLabelPattern(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val scheme = ctx.theme.colorScheme
    val value = component.propFloat("value", 0f).coerceIn(0f, 1f)
    val label = component.propString("label")
    val showPercent = component.propBool("showPercent", false)
    val barHeight = component.propFloat("height", 8f)

    Column(modifier = modifier.fillMaxWidth()) {
        // 标签行
        if (label != null || showPercent) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (label != null) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurface,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (showPercent) {
                    Text(
                        text = "${(value * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        // 进度条
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight.dp)
                .clip(RoundedCornerShape(barHeight.dp / 2))
                .background(scheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(value)
                    .height(barHeight.dp)
                    .clip(RoundedCornerShape(barHeight.dp / 2))
                    .background(scheme.primary)
            )
        }
    }
}

// ============================================================================
// 用户信息模式 User Profile Patterns
// ============================================================================

/**
 * 用户头像行 User Avatar Row
 * properties: name, subtitle, avatarSize, showOnline, online
 */
@Composable
fun UserAvatarRowPattern(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val scheme = ctx.theme.colorScheme
    val name = component.propStringResolved("name", ctx) ?: "用户"
    val subtitle = component.propStringResolved("subtitle", ctx)
    val avatarSize = component.propInt("avatarSize", 40)
    val showOnline = component.propBool("showOnline", false)
    val isOnline = component.propBool("online", false)
    val avatarText = component.propString("avatarText") ?: name.firstOrNull()?.toString() ?: "?"

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 头像
        Box {
            Surface(
                color = scheme.primary,
                shape = CircleShape,
                modifier = Modifier.size(avatarSize.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = avatarText,
                        color = scheme.onPrimary,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            // 在线状态
            if (showOnline) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(if (isOnline) scheme.success else scheme.outlineVariant)
                        .border(2.dp, scheme.surface, CircleShape)
                        .align(Alignment.BottomEnd)
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        // 名字
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }
        }

        // 右侧自定义内容
        if (component.children.isNotEmpty()) {
            RenderChildren(component.children, ctx)
        }
    }
}
