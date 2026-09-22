@file:OptIn(ExperimentalMaterial3Api::class)

package com.ai.assistance.quro.genui.sdk.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.ai.assistance.quro.genui.sdk.dsl.UIComponent
import com.ai.assistance.quro.genui.sdk.render.RenderChildren
import com.ai.assistance.quro.genui.sdk.render.RenderContext
import com.ai.assistance.quro.genui.sdk.render.StyleResolver
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

// ============================================================================
// 对话框尺寸枚举
// ============================================================================
enum class DialogSize(val value: String, val widthFraction: Float, val maxWidthDp: Int) {
    SMALL("small", 0.6f, 280),
    MEDIUM("medium", 0.85f, 400),
    LARGE("large", 0.95f, 560),
    FULLSCREEN("fullscreen", 1f, Int.MAX_VALUE);

    companion object {
        fun from(value: String?): DialogSize {
            return entries.find { it.value == value } ?: MEDIUM
        }
    }
}

// ============================================================================
// 对话框位置枚举
// ============================================================================
enum class DialogPosition(val value: String) {
    CENTER("center"),
    BOTTOM("bottom"),
    TOP("top");

    companion object {
        fun from(value: String?): DialogPosition {
            return entries.find { it.value == value } ?: CENTER
        }
    }
}

// ============================================================================
// 对话框动画枚举
// ============================================================================
enum class DialogAnimation(val value: String) {
    FADE("fade"),
    SCALE("scale"),
    SLIDE_UP("slide_up"),
    SLIDE_DOWN("slide_down"),
    NONE("none");

    companion object {
        fun from(value: String?): DialogAnimation {
            return entries.find { it.value == value } ?: SCALE
        }
    }
}

// ============================================================================
// 对话框按钮配置
// ============================================================================
data class DialogButtonConfig(
    val label: String,
    val variant: String = "text",
    val icon: String? = null,
    val eventName: String,
    val destructive: Boolean = false
)

// ============================================================================
// 对话框工具函数
// ============================================================================

/**
 * 解析对话框按钮配置
 */
private fun parseDialogButtons(component: UIComponent): List<DialogButtonConfig> {
    val buttonsArray = component.properties["buttons"] as? kotlinx.serialization.json.JsonArray
    if (buttonsArray != null) {
        return buttonsArray.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            DialogButtonConfig(
                label = (obj["label"] as? JsonPrimitive)?.contentOrNull ?: "",
                variant = (obj["variant"] as? JsonPrimitive)?.contentOrNull ?: "text",
                icon = (obj["icon"] as? JsonPrimitive)?.contentOrNull,
                eventName = (obj["event"] as? JsonPrimitive)?.contentOrNull
                    ?: (obj["action"] as? JsonPrimitive)?.contentOrNull
                    ?: "onClick",
                destructive = (obj["destructive"] as? JsonPrimitive)?.booleanOrNull ?: false
            )
        }.filter { it.label.isNotBlank() }
    }

    return emptyList()
}

/**
 * 获取反馈等级对应的颜色和图标
 */
private fun getFeedbackVisuals(level: String, ctx: RenderContext): Pair<Color, androidx.compose.ui.graphics.vector.ImageVector> {
    val scheme = ctx.theme.colorScheme
    return when (level) {
        "success" -> scheme.primary to Icons.Filled.Check
        "error" -> scheme.error to Icons.Filled.Close
        "warning" -> Color(0xFFF59E0B) to Icons.Filled.Warning
        "info" -> Color(0xFF3B82F6) to Icons.Filled.Info
        else -> scheme.onSurfaceVariant to Icons.Filled.Info
    }
}

// ============================================================================
// 对话框按钮渲染
// ============================================================================

@Composable
private fun DialogButton(
    config: DialogButtonConfig,
    ctx: RenderContext,
    component: UIComponent
) {
    val handler = ctx.clickHandler(component, config.eventName)
    val scheme = ctx.theme.colorScheme

    val contentColor = when {
        config.destructive -> scheme.error
        config.variant == "filled" || config.variant == "primary" -> scheme.primary
        else -> scheme.primary
    }

    TextButton(onClick = { handler?.invoke() }) {
        if (config.icon != null) {
            val iconVector = IconMapper.map(config.icon)
            if (iconVector != null) {
                Icon(
                    imageVector = iconVector,
                    contentDescription = config.label,
                    modifier = Modifier.size(18.dp),
                    tint = contentColor
                )
                Spacer(Modifier.width(6.dp))
            }
        }
        Text(
            text = config.label,
            color = contentColor,
            fontWeight = if (config.variant == "filled") FontWeight.Medium else FontWeight.Normal
        )
    }
}

// ============================================================================
// 通用对话框内容渲染器
// ============================================================================

/**
 * 通用对话框内容渲染器 — 支持丰富属性
 *
 * 支持的 properties:
 * - title: 标题
 * - message / content: 内容文本
 * - icon: 图标名称
 * - level: "info" | "success" | "warning" | "error" | "neutral"
 * - showClose: 是否显示关闭按钮 (默认 false)
 * - scrollable: 内容是否可滚动 (默认 false)
 * - buttons: 按钮配置数组
 * - buttonAlignment: "end" | "center" | "space_between"
 * - divider: 是否显示标题分隔线
 *
 * @param extraContent 额外的 Composable 内容，渲染在 message 和 children 之后、按钮之前
 */
@Composable
fun DialogContentRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier,
    defaultLevel: String = "neutral",
    defaultButtons: List<DialogButtonConfig> = emptyList(),
    extraContent: @Composable () -> Unit = {}
) {
    val scheme = ctx.theme.colorScheme
    val title = component.propStringResolved("title", ctx)
    val message = component.propStringResolved("message", ctx)
        ?: component.propStringResolved("content", ctx)
    val level = component.propString("level") ?: defaultLevel
    val iconName = component.propString("icon")
    val showClose = component.propBool("showClose", false)
    val scrollable = component.propBool("scrollable", false)
    val showDivider = component.propBool("divider", false)
    val buttonAlignment = component.propString("buttonAlignment") ?: "end"

    val visuals = getFeedbackVisuals(level, ctx)
    val showAccent = level != "neutral"
    val accentColor = visuals.first
    val iconVector = if (iconName != null) IconMapper.map(iconName) else visuals.second

    val shape = StyleResolver.resolveShape(component.style).let { s ->
        if (s == androidx.compose.ui.graphics.RectangleShape) RoundedCornerShape(20.dp) else s
    }
    val containerColor = StyleResolver.resolveColor(
        component.style.backgroundColor, scheme, scheme.surfaceContainer
    )
    val onSurface = scheme.onSurface
    val elevation = component.style.elevation.takeIf { it > 0f }?.dp ?: 8.dp

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = containerColor,
        tonalElevation = elevation,
        shadowElevation = elevation
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 标题区域
            if (title != null || showClose) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 12.dp, top = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (iconVector != null && title != null) {
                        Icon(
                            imageVector = iconVector,
                            contentDescription = level,
                            tint = if (showAccent) accentColor else onSurface,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    if (title != null) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineSmall,
                            color = if (showAccent && level == "error") accentColor else onSurface,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    if (showClose) {
                        IconButton(onClick = { ctx.clickHandler(component, "onDismiss")?.invoke() }) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "关闭",
                                tint = scheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            // 标题分隔线
            if (showDivider && title != null) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 24.dp),
                    color = scheme.outlineVariant.copy(alpha = 0.5f),
                    thickness = 0.5.dp
                )
            }

            // 内容区域
            val contentModifier = if (scrollable) {
                Modifier.verticalScroll(rememberScrollState())
            } else {
                Modifier
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .then(contentModifier)
            ) {
                if (message != null) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = onSurface,
                        lineHeight = 22.sp
                    )
                }
                if (component.children.isNotEmpty()) {
                    if (message != null) Spacer(Modifier.height(12.dp))
                    RenderChildren(component.children, ctx)
                }
                // 额外内容
                extraContent()
            }

            // 按钮区域
            val buttons = parseDialogButtons(component).ifEmpty { defaultButtons }
            if (buttons.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = when (buttonAlignment) {
                        "center" -> Arrangement.Center
                        "space_between" -> Arrangement.SpaceBetween
                        "start" -> Arrangement.Start
                        else -> Arrangement.End
                    },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    buttons.forEach { button ->
                        DialogButton(button, ctx, component)
                    }
                }
            }
        }
    }
}

// ============================================================================
// 底部弹窗 Bottom Sheet 渲染器
// ============================================================================

/**
 * 底部弹窗渲染器
 *
 * properties:
 * - title: 标题
 * - message: 内容
 * - showHandle: 是否显示顶部拖拽条 (默认 true)
 * - showClose: 是否显示关闭按钮
 * - scrollable: 内容是否可滚动
 * - buttons: 底部按钮
 * - fullHeight: 是否全屏高度 (默认 false)
 */
@Composable
fun BottomSheetDialogRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val scheme = ctx.theme.colorScheme
    val showHandle = component.propBool("showHandle", true)
    val fullHeight = component.propBool("fullHeight", false)
    val backgroundColor = StyleResolver.resolveColor(
        component.style.backgroundColor, scheme, scheme.surfaceContainer
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (fullHeight) Modifier.fillMaxSize() else Modifier.wrapContentHeight())
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(backgroundColor)
    ) {
        // 顶部拖拽条
        if (showHandle) {
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(scheme.outlineVariant.copy(alpha = 0.4f))
                    .align(Alignment.CenterHorizontally)
            )
        }

        // 内容
        DialogContentRenderer(
            component = component,
            ctx = ctx,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ============================================================================
// 输入对话框 Input Dialog 渲染器
// ============================================================================

/**
 * 输入对话框渲染器
 *
 * properties:
 * - title: 标题
 * - message: 提示文本
 * - placeholder: 输入框占位符
 * - label: 输入框标签
 * - value: 默认值
 * - inputType: "text" | "password" | "number" | "email"
 * - maxLines: 最大行数
 * - confirmText: 确认按钮文字
 * - cancelText: 取消按钮文字
 * - hint: 帮助文字
 */
@Composable
fun InputDialogRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val scheme = ctx.theme.colorScheme
    var inputValue by remember { mutableStateOf(component.propString("value") ?: "") }

    DialogContentRenderer(
        component = component,
        ctx = ctx,
        modifier = modifier,
        defaultButtons = listOf(
            DialogButtonConfig(
                label = component.propString("cancelText") ?: "取消",
                variant = "text",
                eventName = "onCancel"
            ),
            DialogButtonConfig(
                label = component.propString("confirmText") ?: "确定",
                variant = "filled",
                eventName = "onConfirm"
            )
        )
    ) {
        // 输入框
        val placeholder = component.propString("placeholder") ?: ""
        val label = component.propString("label")
        val maxLines = component.propInt("maxLines", 1)
        val hint = component.propString("hint")

        Spacer(Modifier.height(8.dp))

        Column(modifier = Modifier.fillMaxWidth()) {
            if (label != null) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = scheme.outline,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(12.dp)
            ) {
                if (inputValue.isEmpty()) {
                    Text(
                        text = placeholder,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                BasicTextField(
                    value = inputValue,
                    onValueChange = { inputValue = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
                    maxLines = maxLines
                )
            }

            if (hint != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }
        }
    }
}

// ============================================================================
// 列表选择对话框 List Dialog 渲染器
// ============================================================================

/**
 * 列表选择对话框渲染器
 *
 * properties:
 * - title: 标题
 * - items: 选项数组 [{label, subtitle, icon, value}]
 * - selectedValue: 当前选中值
 * - multiSelect: 是否多选
 * - showIcons: 是否显示图标
 */
@Composable
fun ListDialogRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val scheme = ctx.theme.colorScheme
    val itemsArray = component.properties["items"] as? kotlinx.serialization.json.JsonArray
    val multiSelect = component.propBool("multiSelect", false)
    val showIcons = component.propBool("showIcons", true)

    val items = itemsArray?.mapIndexed { index, element ->
        val obj = element as? JsonObject
        val label = (obj?.get("label") as? JsonPrimitive)?.contentOrNull ?: "选项${index + 1}"
        val subtitle = (obj?.get("subtitle") as? JsonPrimitive)?.contentOrNull
        val icon = (obj?.get("icon") as? JsonPrimitive)?.contentOrNull
        val value = (obj?.get("value") as? JsonPrimitive)?.contentOrNull ?: label
        Triple(label, subtitle, icon to value)
    } ?: emptyList()

    var selected by remember { mutableStateOf(component.propString("selectedValue")) }
    var selectedSet by remember {
        mutableStateOf(
            (component.properties["selectedValues"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.toSet() ?: emptySet()
        )
    }

    DialogContentRenderer(
        component = component,
        ctx = ctx,
        modifier = modifier,
        defaultButtons = listOf(
            DialogButtonConfig(
                label = component.propString("cancelText") ?: "取消",
                variant = "text",
                eventName = "onCancel"
            )
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            items.forEach { (label, subtitle, iconValue) ->
                val (icon, value) = iconValue
                val isSelected = if (multiSelect) value in selectedSet else value == selected

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            if (multiSelect) {
                                selectedSet = if (isSelected) {
                                    selectedSet.minus(element = value)
                                } else {
                                    selectedSet.plus(element = value)
                                }
                            } else {
                                selected = value
                                ctx.clickHandler(component, "onSelect")?.invoke()
                            }
                        }
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (showIcons && icon != null) {
                        val iconVec = IconMapper.map(icon)
                        if (iconVec != null) {
                            Icon(
                                imageVector = iconVec,
                                contentDescription = null,
                                tint = if (isSelected) scheme.primary else scheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                        }
                    }

                    // 选择指示器
                    if (multiSelect) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    if (isSelected) scheme.primary else Color.Transparent
                                )
                                .border(
                                    width = 1.5.dp,
                                    color = if (isSelected) scheme.primary else scheme.outline,
                                    shape = RoundedCornerShape(4.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = scheme.onPrimary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                    } else {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .border(
                                    width = 2.dp,
                                    color = if (isSelected) scheme.primary else scheme.outline,
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(scheme.primary)
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isSelected) scheme.primary else scheme.onSurface
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
            }
        }
    }
}

// ============================================================================
// 全屏对话框 Fullscreen Dialog 渲染器
// ============================================================================

/**
 * 全屏对话框渲染器
 *
 * properties:
 * - title: 标题
 * - showBack: 是否显示返回按钮
 * - showClose: 是否显示关闭按钮
 * - actionText: 右上角操作按钮文字
 */
@Composable
fun FullscreenDialogRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val scheme = ctx.theme.colorScheme
    val title = component.propStringResolved("title", ctx)
    val showBack = component.propBool("showBack", true)
    val showClose = component.propBool("showClose", false)
    val actionText = component.propString("actionText")
    val backgroundColor = StyleResolver.resolveColor(
        component.style.backgroundColor, scheme, scheme.surface
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        // 顶部 App Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showBack) {
                IconButton(onClick = { ctx.clickHandler(component, "onDismiss")?.invoke() }) {
                    Icon(
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = scheme.onSurface
                    )
                }
            }

            if (title != null) {
                Spacer(Modifier.width(if (showBack) 4.dp else 12.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = scheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Spacer(Modifier.weight(1f))
            }

            if (actionText != null) {
                TextButton(onClick = { ctx.clickHandler(component, "onAction")?.invoke() }) {
                    Text(
                        text = actionText,
                        color = scheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
            } else if (showClose) {
                IconButton(onClick = { ctx.clickHandler(component, "onDismiss")?.invoke() }) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "关闭",
                        tint = scheme.onSurface
                    )
                }
            }
        }

        HorizontalDivider(
            color = scheme.outlineVariant.copy(alpha = 0.3f),
            thickness = 0.5.dp
        )

        // 内容区域
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            RenderChildren(component.children, ctx)
        }
    }
}

// ============================================================================
// 评分对话框 Rating Dialog 渲染器
// ============================================================================

/**
 * 评分对话框渲染器
 *
 * properties:
 * - title: 标题
 * - message: 提示文本
 * - maxStars: 最大星级 (默认 5)
 * - initialRating: 初始评分
 * - showComment: 是否显示评论输入框
 * - commentPlaceholder: 评论占位符
 */
@Composable
fun RatingDialogRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val maxStars = component.propInt("maxStars", 5)
    val initial = component.propFloat("initialRating", 0f)
    var rating by remember { mutableStateOf(initial) }
    val showComment = component.propBool("showComment", false)
    var comment by remember { mutableStateOf("") }
    val scheme = ctx.theme.colorScheme

    DialogContentRenderer(
        component = component,
        ctx = ctx,
        modifier = modifier,
        defaultButtons = listOf(
            DialogButtonConfig(
                label = component.propString("cancelText") ?: "稍后",
                variant = "text",
                eventName = "onCancel"
            ),
            DialogButtonConfig(
                label = component.propString("submitText") ?: "提交",
                variant = "filled",
                eventName = "onSubmit"
            )
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 星级评分
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(maxStars) { index ->
                    val starFilled = (index + 1) <= rating
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = "${index + 1} 星",
                        tint = if (starFilled) Color(0xFFFFB300) else scheme.outlineVariant,
                        modifier = Modifier
                            .size(36.dp)
                            .clickable { rating = (index + 1).toFloat() }
                    )
                    if (index < maxStars - 1) Spacer(Modifier.width(8.dp))
                }
            }

            if (rating > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = when {
                        rating >= 5 -> "非常满意！"
                        rating >= 4 -> "比较满意"
                        rating >= 3 -> "一般"
                        rating >= 2 -> "不太满意"
                        else -> "很不满意"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }

            // 评论输入
            if (showComment) {
                Spacer(Modifier.height(16.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .border(
                            width = 1.dp,
                            color = scheme.outline,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(12.dp)
                ) {
                    if (comment.isEmpty()) {
                        Text(
                            text = component.propString("commentPlaceholder") ?: "说说你的看法...",
                            color = scheme.onSurfaceVariant.copy(alpha = 0.5f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    BasicTextField(
                        value = comment,
                        onValueChange = { comment = it },
                        modifier = Modifier.fillMaxSize(),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
                        maxLines = 3
                    )
                }
            }
        }
    }
}

// ============================================================================
// 日期选择对话框 Date Picker Dialog 渲染器
// ============================================================================

/**
 * 日期选择对话框渲染器
 *
 * properties:
 * - title: 标题
 * - initialDate: 初始日期 "YYYY-MM-DD"
 * - minDate: 最小日期
 * - maxDate: 最大日期
 * - mode: "calendar" | "spinner"
 */
@Composable
fun DatePickerDialogRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val scheme = ctx.theme.colorScheme
    val initialDate = component.propString("initialDate")
    val daysInMonth = 30
    val currentMonth = initialDate?.split("-")?.get(1)?.toIntOrNull() ?: 9
    val currentYear = initialDate?.split("-")?.get(0)?.toIntOrNull() ?: 2024
    val selectedDay = initialDate?.split("-")?.get(2)?.toIntOrNull() ?: 1

    var selected by remember { mutableStateOf(selectedDay) }

    val monthNames = listOf("一月", "二月", "三月", "四月", "五月", "六月", "七月", "八月", "九月", "十月", "十一月", "十二月")
    val dayNames = listOf("日", "一", "二", "三", "四", "五", "六")

    DialogContentRenderer(
        component = component,
        ctx = ctx,
        modifier = modifier,
        defaultButtons = listOf(
            DialogButtonConfig(
                label = component.propString("cancelText") ?: "取消",
                variant = "text",
                eventName = "onCancel"
            ),
            DialogButtonConfig(
                label = component.propString("confirmText") ?: "确定",
                variant = "filled",
                eventName = "onConfirm"
            )
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 年月标题
            Text(
                text = "$currentYear 年 ${monthNames[currentMonth - 1]}",
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(Modifier.height(12.dp))

            // 星期标题行
            Row(modifier = Modifier.fillMaxWidth()) {
                dayNames.forEach { day ->
                    Text(
                        text = day,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // 日期网格（简化版）
            val startDayOfWeek = 2
            Column(modifier = Modifier.fillMaxWidth()) {
                var day = 1
                for (week in 0..4) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        for (dow in 0..6) {
                            val showDay = (week == 0 && dow >= startDayOfWeek && day <= daysInMonth) || (week > 0 && day <= daysInMonth)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .size(36.dp)
                                    .then(
                                        if (showDay && day == selected) {
                                            Modifier
                                                .clip(CircleShape)
                                                .background(scheme.primary)
                                        } else Modifier
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (showDay) {
                                    Text(
                                        text = day.toString(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (day == selected) scheme.onPrimary else scheme.onSurface,
                                        fontWeight = if (day == selected) FontWeight.Medium else FontWeight.Normal
                                    )
                                    day++
                                }
                            }
                        }
                    }
                    if (day > daysInMonth) break
                }
            }
        }
    }
}

// ============================================================================
// 简单对话框 Simple Dialog 渲染器
// ============================================================================

/**
 * 简单对话框渲染器 — 无按钮，纯内容展示
 */
@Composable
fun SimpleDialogRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    DialogContentRenderer(
        component = component,
        ctx = ctx,
        modifier = modifier
    )
}

// ============================================================================
// 权限请求对话框 Permission Dialog 渲染器
// ============================================================================

/**
 * 权限请求对话框渲染器
 *
 * properties:
 * - title: 标题
 * - message: 权限说明
 * - permission: 权限名称
 * - rationale: 为什么需要这个权限
 * - icon: 图标
 */
@Composable
fun PermissionDialogRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val rationale = component.propString("rationale")

    DialogContentRenderer(
        component = component,
        ctx = ctx,
        modifier = modifier,
        defaultLevel = "info",
        defaultButtons = listOf(
            DialogButtonConfig(
                label = component.propString("denyText") ?: "暂不允许",
                variant = "text",
                eventName = "onDeny"
            ),
            DialogButtonConfig(
                label = component.propString("allowText") ?: "允许",
                variant = "filled",
                eventName = "onAllow"
            )
        )
    ) {
        if (rationale != null) {
            Spacer(Modifier.height(8.dp))
            Surface(
                color = ctx.theme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = rationale,
                    style = MaterialTheme.typography.bodySmall,
                    color = ctx.theme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

// ============================================================================
// 删除确认对话框 Delete Confirm Dialog 渲染器
// ============================================================================

/**
 * 删除确认对话框渲染器
 *
 * properties:
 * - title: 标题
 * - message: 确认信息
 * - itemName: 要删除的项目名称
 * - destructive: 是否危险操作 (默认 true)
 */
@Composable
fun DeleteConfirmDialogRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val itemName = component.propString("itemName")
    val message = component.propStringResolved("message", ctx)
        ?: if (itemName != null) "确定要删除「$itemName」吗？此操作不可撤销。"
        else "确定要删除吗？此操作不可撤销。"

    // 构造带 message 的组件
    val propsWithMessage = buildJsonObject {
        component.properties.forEach { (k, v) -> put(k, v) }
        if (component.properties["message"] == null) {
            put("message", JsonPrimitive(message))
        }
    }
    val updatedComponent = component.copy(properties = propsWithMessage)

    DialogContentRenderer(
        component = updatedComponent,
        ctx = ctx,
        modifier = modifier,
        defaultLevel = "error",
        defaultButtons = listOf(
            DialogButtonConfig(
                label = component.propString("cancelText") ?: "取消",
                variant = "text",
                eventName = "onCancel"
            ),
            DialogButtonConfig(
                label = component.propString("deleteText") ?: "删除",
                variant = "filled",
                eventName = "onDelete",
                destructive = true
            )
        )
    )
}

// ============================================================================
// 关于对话框 About Dialog 渲染器
// ============================================================================

/**
 * 关于对话框渲染器
 *
 * properties:
 * - appName: 应用名称
 * - version: 版本号
 * - description: 描述
 * - logoIcon: Logo 图标名称
 */
@Composable
fun AboutDialogRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val scheme = ctx.theme.colorScheme
    val appName = component.propString("appName") ?: "App"
    val version = component.propString("version") ?: "1.0.0"
    val description = component.propString("description")
    val logoIcon = component.propString("logoIcon") ?: "info"

    DialogContentRenderer(
        component = component,
        ctx = ctx,
        modifier = modifier,
        defaultButtons = listOf(
            DialogButtonConfig(
                label = component.propString("closeText") ?: "关闭",
                variant = "text",
                eventName = "onDismiss"
            )
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Logo
            Surface(
                color = scheme.primary,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.size(64.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    val iconVec = IconMapper.map(logoIcon)
                    if (iconVec != null) {
                        Icon(
                            imageVector = iconVec,
                            contentDescription = null,
                            tint = scheme.onPrimary,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // 应用名称
            Text(
                text = appName,
                style = MaterialTheme.typography.titleLarge,
                color = scheme.onSurface,
                fontWeight = FontWeight.Bold
            )

            // 版本号
            Text(
                text = "版本 $version",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant
            )

            if (description != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

// ============================================================================
// 分享对话框 Share Dialog 渲染器
// ============================================================================

/**
 * 分享对话框渲染器
 *
 * properties:
 * - title: 标题
 * - shareText: 分享文本
 * - shareUrl: 分享链接
 * - platforms: 分享平台列表 ["wechat", "moments", "qq", "weibo", "copy", "more"]
 */
@Composable
fun ShareDialogRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val scheme = ctx.theme.colorScheme
    val defaultPlatforms = listOf(
        "wechat" to "微信",
        "moments" to "朋友圈",
        "qq" to "QQ",
        "weibo" to "微博"
    )

    val platformsProp = component.propStringList("platforms")
    val platforms = if (platformsProp.isNotEmpty()) {
        platformsProp.map { it to (platformNames[it] ?: it) }
    } else {
        defaultPlatforms
    }

    DialogContentRenderer(
        component = component,
        ctx = ctx,
        modifier = modifier,
        defaultButtons = listOf(
            DialogButtonConfig(
                label = component.propString("cancelText") ?: "取消",
                variant = "text",
                eventName = "onCancel"
            )
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 分享平台网格
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                platforms.take(4).forEach { (platform, label) ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable {
                            ctx.clickHandler(component, "onShare")?.invoke()
                        }
                    ) {
                        Surface(
                            color = platformColors[platform] ?: scheme.primaryContainer,
                            shape = CircleShape,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = label.first().toString(),
                                    color = androidx.compose.ui.graphics.Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private val platformNames = mapOf(
    "wechat" to "微信",
    "moments" to "朋友圈",
    "qq" to "QQ",
    "weibo" to "微博",
    "copy" to "复制链接",
    "more" to "更多"
)

private val platformColors = mapOf(
    "wechat" to Color(0xFF07C160),
    "moments" to Color(0xFF07C160),
    "qq" to Color(0xFF12B7F5),
    "weibo" to Color(0xFFE6162D),
    "copy" to Color(0xFF6B7280),
    "more" to Color(0xFF6B7280)
)
