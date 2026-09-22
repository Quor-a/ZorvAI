@file:OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalLayoutApi::class,
    ExperimentalFoundationApi::class
)

package com.ai.assistance.quro.genui.sdk.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowColumn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.genui.sdk.dsl.ComponentTypes
import com.ai.assistance.quro.genui.sdk.dsl.ComponentVariant
import com.ai.assistance.quro.genui.sdk.dsl.Dimension
import com.ai.assistance.quro.genui.sdk.dsl.UIComponent
import com.ai.assistance.quro.genui.sdk.render.RenderChildren
import com.ai.assistance.quro.genui.sdk.render.RenderContext
import com.ai.assistance.quro.genui.sdk.render.RenderNode
import com.ai.assistance.quro.genui.sdk.render.StyleResolver
import com.ai.assistance.quro.genui.sdk.style.FontProvider
import java.util.Locale

// ============================================================================
// SHARED HELPER FUNCTIONS
// ============================================================================

/**
 * Typography configuration holding font size and weight for each typography type.
 */
private data class TypographyConfig(
    val fontSize: Float,
    val fontWeight: FontWeight
)

/**
 * Returns the auto-set font size and weight for a typography component type.
 * heading1 = 32sp, heading2 = 28sp, heading3 = 24sp, heading4 = 22sp,
 * heading5 = 20sp, heading6 = 18sp, etc.
 */
private fun typographyConfig(type: String): TypographyConfig {
    return when (type) {
        ComponentTypes.HEADING1 -> TypographyConfig(32f, FontWeight.Bold)
        ComponentTypes.HEADING2 -> TypographyConfig(28f, FontWeight.Bold)
        ComponentTypes.HEADING3 -> TypographyConfig(24f, FontWeight.SemiBold)
        ComponentTypes.HEADING4 -> TypographyConfig(22f, FontWeight.SemiBold)
        ComponentTypes.HEADING5 -> TypographyConfig(20f, FontWeight.Medium)
        ComponentTypes.HEADING6 -> TypographyConfig(18f, FontWeight.Medium)
        ComponentTypes.TITLE -> TypographyConfig(22f, FontWeight.Medium)
        ComponentTypes.SUBTITLE -> TypographyConfig(16f, FontWeight.Normal)
        ComponentTypes.BODY -> TypographyConfig(14f, FontWeight.Normal)
        ComponentTypes.TEXT -> TypographyConfig(14f, FontWeight.Normal)
        ComponentTypes.CAPTION -> TypographyConfig(12f, FontWeight.Normal)
        ComponentTypes.OVERLINE -> TypographyConfig(11f, FontWeight.Medium)
        ComponentTypes.LABEL -> TypographyConfig(14f, FontWeight.Medium)
        ComponentTypes.HEADLINE -> TypographyConfig(28f, FontWeight.Bold)
        ComponentTypes.SUBHEAD -> TypographyConfig(24f, FontWeight.SemiBold)
        ComponentTypes.DISPLAY1 -> TypographyConfig(57f, FontWeight.Normal)
        ComponentTypes.DISPLAY2 -> TypographyConfig(45f, FontWeight.Normal)
        ComponentTypes.DISPLAY3 -> TypographyConfig(36f, FontWeight.Normal)
        else -> TypographyConfig(14f, FontWeight.Normal)
    }
}

/**
 * Resolves a [ComponentVariant.ColorRole] to a theme color.
 */
private fun resolveColorRole(
    role: ComponentVariant.ColorRole,
    ctx: RenderContext
): Color {
    return when (role) {
        ComponentVariant.ColorRole.PRIMARY -> ctx.theme.colorScheme.primary
        ComponentVariant.ColorRole.SECONDARY -> ctx.theme.colorScheme.secondary
        ComponentVariant.ColorRole.TERTIARY -> ctx.theme.colorScheme.tertiary
        ComponentVariant.ColorRole.ERROR -> ctx.theme.colorScheme.error
        ComponentVariant.ColorRole.SURFACE -> ctx.theme.colorScheme.surface
    }
}

/**
 * Resolves a [ComponentVariant.ColorRole] to the "on" color (foreground) for that role.
 */
private fun resolveOnColorRole(
    role: ComponentVariant.ColorRole,
    ctx: RenderContext
): Color {
    return when (role) {
        ComponentVariant.ColorRole.PRIMARY -> ctx.theme.colorScheme.onPrimary
        ComponentVariant.ColorRole.SECONDARY -> ctx.theme.colorScheme.onSecondary
        ComponentVariant.ColorRole.TERTIARY -> ctx.theme.colorScheme.onTertiary
        ComponentVariant.ColorRole.ERROR -> ctx.theme.colorScheme.onError
        ComponentVariant.ColorRole.SURFACE -> ctx.theme.colorScheme.onSurface
    }
}

/**
 * Resolves vertical arrangement from style properties, supporting spacing between items.
 */
private fun ltbVerticalArrangement(value: String?, spacing: Float): Arrangement.Vertical {
    val spacingDp = if (spacing > 0f) spacing.dp else 0.dp
    val lower = value?.lowercase(Locale.ROOT)
    return when (lower) {
        "center" -> if (spacingDp > 0.dp) {
            Arrangement.spacedBy(spacingDp, Alignment.CenterVertically)
        } else {
            Arrangement.Center
        }
        "end", "bottom" -> if (spacingDp > 0.dp) {
            Arrangement.spacedBy(spacingDp, Alignment.Bottom)
        } else {
            Arrangement.Bottom
        }
        "space_between" -> Arrangement.SpaceBetween
        "space_around" -> Arrangement.SpaceAround
        "space_evenly" -> Arrangement.SpaceEvenly
        else -> if (spacingDp > 0.dp) {
            Arrangement.spacedBy(spacingDp)
        } else {
            Arrangement.Top
        }
    }
}

/**
 * Resolves horizontal arrangement from style properties, supporting spacing between items.
 */
private fun ltbHorizontalArrangement(value: String?, spacing: Float): Arrangement.Horizontal {
    val spacingDp = if (spacing > 0f) spacing.dp else 0.dp
    val lower = value?.lowercase(Locale.ROOT)
    return when (lower) {
        "center" -> if (spacingDp > 0.dp) {
            Arrangement.spacedBy(spacingDp, Alignment.CenterHorizontally)
        } else {
            Arrangement.Center
        }
        "end", "right" -> if (spacingDp > 0.dp) {
            Arrangement.spacedBy(spacingDp, Alignment.End)
        } else {
            Arrangement.End
        }
        "space_between" -> Arrangement.SpaceBetween
        "space_around" -> Arrangement.SpaceAround
        "space_evenly" -> Arrangement.SpaceEvenly
        else -> if (spacingDp > 0.dp) {
            Arrangement.spacedBy(spacingDp)
        } else {
            Arrangement.Start
        }
    }
}

/**
 * Resolves horizontal alignment for cross-axis in Column.
 */
private fun ltbHorizontalAlign(value: String?): Alignment.Horizontal {
    val lower = value?.lowercase(Locale.ROOT)
    return when (lower) {
        "end", "right" -> Alignment.End
        "center", "stretch" -> Alignment.CenterHorizontally
        else -> Alignment.Start
    }
}

/**
 * Resolves vertical alignment for cross-axis in Row.
 */
private fun ltbVerticalAlign(value: String?): Alignment.Vertical {
    val lower = value?.lowercase(Locale.ROOT)
    return when (lower) {
        "end", "bottom" -> Alignment.Bottom
        "center", "stretch" -> Alignment.CenterVertically
        else -> Alignment.Top
    }
}

/**
 * Resolves Box alignment from style properties.
 */
private fun ltbBoxAlign(value: String?): Alignment {
    val lower = value?.lowercase(Locale.ROOT)
    return when (lower) {
        "top" -> Alignment.TopCenter
        "bottom" -> Alignment.BottomCenter
        "center" -> Alignment.Center
        "start", "left" -> Alignment.CenterStart
        "end", "right" -> Alignment.CenterEnd
        "top_start", "top_left" -> Alignment.TopStart
        "top_end", "top_right" -> Alignment.TopEnd
        "bottom_start", "bottom_left" -> Alignment.BottomStart
        "bottom_end", "bottom_right" -> Alignment.BottomEnd
        else -> Alignment.Center
    }
}

/**
 * Resolves button enabled and loading state from component properties and variant state.
 */
private data class ButtonState(
    val enabled: Boolean,
    val loading: Boolean
)

private fun resolveButtonState(component: UIComponent): ButtonState {
    val stateVariant = ComponentVariant.State.from(component.propString("state"))
    val enabledProp = component.propBool("enabled", true)
    val isEnabled = enabledProp && stateVariant != ComponentVariant.State.DISABLED
    val isLoading = stateVariant == ComponentVariant.State.LOADING || component.propBool("loading", false)
    return ButtonState(enabled = isEnabled, loading = isLoading)
}

/**
 * Determines which Material3 button style to use based on component type and style property.
 */
private fun resolveButtonStyle(component: UIComponent): ComponentVariant.Style {
    val styleFromType = when (component.type) {
        ComponentTypes.TEXT_BUTTON -> ComponentVariant.Style.TEXT
        ComponentTypes.OUTLINED_BUTTON -> ComponentVariant.Style.OUTLINED
        ComponentTypes.ELEVATED_BUTTON -> ComponentVariant.Style.ELEVATED
        ComponentTypes.TONAL_BUTTON -> ComponentVariant.Style.TONAL
        ComponentTypes.FILLED_BUTTON -> ComponentVariant.Style.FILLED
        ComponentTypes.BUTTON -> ComponentVariant.Style.FILLED
        else -> null
    }
    if (styleFromType != null) return styleFromType
    return ComponentVariant.Style.from(component.propString("style"))
}

/**
 * Configuration for action-specific buttons (play, pause, add, delete, etc.).
 */
private data class ActionButtonConfig(
    val icon: ImageVector,
    val defaultText: String,
    val colorRole: ComponentVariant.ColorRole
)

private fun actionButtonConfig(type: String): ActionButtonConfig {
    return when (type) {
        ComponentTypes.SUBMIT_BUTTON -> ActionButtonConfig(Icons.Filled.Check, "Submit", ComponentVariant.ColorRole.PRIMARY)
        ComponentTypes.CANCEL_BUTTON -> ActionButtonConfig(Icons.Filled.Close, "Cancel", ComponentVariant.ColorRole.SURFACE)
        ComponentTypes.CONFIRM_BUTTON -> ActionButtonConfig(Icons.Filled.Check, "Confirm", ComponentVariant.ColorRole.PRIMARY)
        ComponentTypes.LINK_BUTTON -> ActionButtonConfig(Icons.Filled.Link, "Link", ComponentVariant.ColorRole.PRIMARY)
        ComponentTypes.SHARE_BUTTON -> ActionButtonConfig(Icons.Filled.Share, "Share", ComponentVariant.ColorRole.SURFACE)
        ComponentTypes.DOWNLOAD_BUTTON -> ActionButtonConfig(Icons.Filled.Download, "Download", ComponentVariant.ColorRole.SURFACE)
        ComponentTypes.UPLOAD_BUTTON -> ActionButtonConfig(Icons.Filled.Upload, "Upload", ComponentVariant.ColorRole.SURFACE)
        ComponentTypes.PLAY_BUTTON -> ActionButtonConfig(Icons.Filled.PlayArrow, "Play", ComponentVariant.ColorRole.PRIMARY)
        ComponentTypes.PAUSE_BUTTON -> ActionButtonConfig(Icons.Filled.Pause, "Pause", ComponentVariant.ColorRole.SURFACE)
        ComponentTypes.STOP_BUTTON -> ActionButtonConfig(Icons.Filled.Stop, "Stop", ComponentVariant.ColorRole.ERROR)
        ComponentTypes.RELOAD_BUTTON -> ActionButtonConfig(Icons.Filled.Refresh, "Reload", ComponentVariant.ColorRole.SURFACE)
        ComponentTypes.ADD_BUTTON -> ActionButtonConfig(Icons.Filled.Add, "Add", ComponentVariant.ColorRole.PRIMARY)
        ComponentTypes.DELETE_BUTTON -> ActionButtonConfig(Icons.Filled.Delete, "Delete", ComponentVariant.ColorRole.ERROR)
        else -> ActionButtonConfig(Icons.Filled.Check, "Action", ComponentVariant.ColorRole.PRIMARY)
    }
}

/**
 * Renders button content (icon + text) in the correct order based on iconPosition.
 */
@Composable
private fun ButtonContent(
    component: UIComponent,
    ctx: RenderContext,
    text: String,
    iconName: String?,
    iconPosition: String,
    contentColor: Color
) {
    val hasIcon = iconName != null
    val hasText = text.isNotEmpty()

    if (hasIcon && iconPosition == "left") {
        Icon(
            imageVector = IconMapper.map(iconName),
            contentDescription = iconName,
            modifier = Modifier.size(20.dp),
            tint = contentColor
        )
    }
    if (hasText) {
        Text(text = text)
    }
    if (hasIcon && iconPosition == "right") {
        Icon(
            imageVector = IconMapper.map(iconName),
            contentDescription = iconName,
            modifier = Modifier.size(20.dp),
            tint = contentColor
        )
    }
}

/**
 * Parses simple markdown text into an AnnotatedString.
 * Supports: # ## ### headings, **bold**, *italic*, `code`, - list items.
 */
private fun parseMarkdown(text: String): AnnotatedString {
    return buildAnnotatedString {
        val lines = text.split("\n")
        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("### ") -> {
                    withStyle(SpanStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold)) {
                        append(trimmed.removePrefix("### "))
                    }
                }
                trimmed.startsWith("## ") -> {
                    withStyle(SpanStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold)) {
                        append(trimmed.removePrefix("## "))
                    }
                }
                trimmed.startsWith("# ") -> {
                    withStyle(SpanStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold)) {
                        append(trimmed.removePrefix("# "))
                    }
                }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    append("\u2022 ")
                    appendInline(trimmed.substring(2))
                }
                else -> appendInline(trimmed)
            }
            if (index < lines.size - 1) {
                append("\n")
            }
        }
    }
}

/**
 * Appends inline markdown (bold, italic, code) to an AnnotatedString builder.
 */
private fun AnnotatedString.Builder.appendInline(text: String) {
    var i = 0
    while (i < text.length) {
        if (i + 1 < text.length && text[i] == '*' && text[i + 1] == '*') {
            val end = text.indexOf("**", i + 2)
            if (end != -1) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(text.substring(i + 2, end))
                }
                i = end + 2
                continue
            }
        }
        if (text[i] == '*' && (i == 0 || text[i - 1] != '*')) {
            val end = text.indexOf('*', i + 1)
            if (end != -1 && text[end + 1] != '*') {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(text.substring(i + 1, end))
                }
                i = end + 1
                continue
            }
        }
        if (text[i] == '`') {
            val end = text.indexOf('`', i + 1)
            if (end != -1) {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                    append(text.substring(i + 1, end))
                }
                i = end + 1
                continue
            }
        }
        append(text[i])
        i++
    }
}

// ============================================================================
// LAYOUT DOMAIN RENDERERS (30 types)
// ============================================================================

// ------------------- Linear Containers: column, row, flex_row, flex_column -------------------

/**
 * Renderer for linear layout containers: column, row, flex_row, flex_column.
 * Uses Column/Row with alignment and arrangement from style properties.
 *
 * Supported properties:
 * - spacing: gap between children
 * - direction: orientation override (horizontal/vertical)
 *
 * Supported styles:
 * - arrangement: main-axis arrangement (start, center, end, space_between, etc.)
 * - crossAlignment: cross-axis alignment
 */
@Composable
fun ColumnRowRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val spacing = component.propFloat("spacing", 0f)
    val isHorizontal = when (component.type) {
        ComponentTypes.ROW, ComponentTypes.FLEX_ROW -> true
        ComponentTypes.COLUMN, ComponentTypes.FLEX_COLUMN -> false
        else -> component.propString("direction")?.lowercase(Locale.ROOT) == "horizontal"
    }

    if (isHorizontal) {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = ltbHorizontalArrangement(component.style.arrangement, spacing),
            verticalAlignment = ltbVerticalAlign(component.style.crossAlignment)
        ) {
            RenderChildren(children = component.children, ctx = ctx)
        }
    } else {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = ltbVerticalArrangement(component.style.arrangement, spacing),
            horizontalAlignment = ltbHorizontalAlign(component.style.crossAlignment)
        ) {
            RenderChildren(children = component.children, ctx = ctx)
        }
    }
}

// ------------------- Box-like Containers: box, container, stack, center, align -------------------

/**
 * Renderer for box-like containers: box, container, stack, center, align.
 * Uses Box with background, padding, and alignment from style properties.
 */
@Composable
fun BoxContainerRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val gravity = when (component.type) {
        ComponentTypes.CENTER -> "center"
        ComponentTypes.ALIGN -> component.style.alignment ?: component.style.gravity ?: "center"
        else -> component.style.gravity ?: component.style.alignment
    }

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = ltbBoxAlign(gravity)
    ) {
        RenderChildren(children = component.children, ctx = ctx)
    }
}

// ------------------- Spacer -------------------

/**
 * Renderer for spacer component.
 * Uses Spacer with width/height from style.
 */
@Composable
fun SpacerRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val width = component.style.width
    val height = component.style.height
    var spacerModifier = modifier
    if (width is Dimension.Fixed) {
        spacerModifier = spacerModifier.width(width.dp.dp)
    }
    if (height is Dimension.Fixed) {
        spacerModifier = spacerModifier.height(height.dp.dp)
    }
    Spacer(modifier = spacerModifier)
}

// ------------------- Divider -------------------

/**
 * Renderer for divider component.
 * Uses HorizontalDivider with color and thickness from style.
 */
@Composable
fun LayoutDividerRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val color = StyleResolver.resolveColor(
        component.style.backgroundColor,
        ctx.theme.colorScheme,
        ctx.theme.colorScheme.outlineVariant
    )
    val thickness = (component.style.height as? Dimension.Fixed)?.dp?.dp ?: 1.dp
    HorizontalDivider(
        modifier = modifier.fillMaxWidth(),
        thickness = thickness,
        color = color
    )
}

// ------------------- Scroll Containers: scroll, nested_scroll, custom_scroll -------------------

/**
 * Renderer for scrollable containers: scroll, nested_scroll, custom_scroll.
 * Uses verticalScroll or horizontalScroll based on the direction property.
 */
@Composable
fun ScrollRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val direction = component.propString("direction") ?: "vertical"
    val spacing = component.propFloat("spacing", 0f)
    val scrollState = rememberScrollState()
    val scrollModifier = if (direction.lowercase(Locale.ROOT) == "horizontal") {
        Modifier.horizontalScroll(scrollState)
    } else {
        Modifier.verticalScroll(scrollState)
    }

    // 注意：这里不使用 fillMaxSize()，避免在无限高度父容器中闪退。
    // 尺寸由外层 RenderNode 的 Box（应用了 style 的 width/height）控制。
    // 如果 style 中设置了 height: "100%" 或具体高度，RenderNode 的 Box 会提供确定的高度约束。
    Column(
        modifier = modifier
            .then(scrollModifier),
        verticalArrangement = ltbVerticalArrangement(component.style.arrangement, spacing),
        horizontalAlignment = ltbHorizontalAlign(component.style.crossAlignment)
    ) {
        RenderChildren(children = component.children, ctx = ctx)
    }
}

// ------------------- Grid -------------------

/**
 * Renderer for grid component.
 * Uses LazyVerticalGrid with column count from properties.
 *
 * Supported properties:
 * - columns: number of columns (default 2)
 * - spacing: spacing between grid items
 */
@Composable
fun GridRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val columns = component.propInt("columns", 2).coerceAtLeast(1)
    val spacing = component.propFloat("spacing", 0f)
    val spacingDp = if (spacing > 0f) spacing.dp else 0.dp

    // 非 Lazy 网格：LazyVerticalGrid 在可滚动 Column（无限高度）中会直接崩溃。
    // GenUI 的 children 数量通常很小，分块 Column/Row 完全够用且对约束免疫。
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = if (spacingDp > 0.dp) Arrangement.spacedBy(spacingDp) else Arrangement.Top
    ) {
        component.children.chunked(columns).forEach { rowChildren ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (spacingDp > 0.dp) Arrangement.spacedBy(spacingDp) else Arrangement.Start
            ) {
                rowChildren.forEach { child ->
                    Box(Modifier.weight(1f)) { RenderNode(child, ctx) }
                }
                repeat(columns - rowChildren.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

// ------------------- Flow and Wrap: flow, wrap -------------------

/**
 * Renderer for flow and wrap containers.
 * Uses FlowRow (or FlowColumn for vertical direction) to wrap children.
 *
 * Supported properties:
 * - direction: horizontal (default) or vertical
 * - maxItemsInRow: maximum items per row
 */
@Composable
fun FlowRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val direction = component.propString("direction") ?: "horizontal"
    val maxItems = component.propInt("maxItemsInRow", Int.MAX_VALUE)

    if (direction.lowercase(Locale.ROOT) == "vertical") {
        FlowColumn(
            modifier = modifier.fillMaxWidth(),
            maxItemsInEachColumn = maxItems,
            verticalArrangement = ltbVerticalArrangement(component.style.arrangement, 0f),
            horizontalArrangement = ltbHorizontalAlign(component.style.crossAlignment).let {
                when (it) {
                    Alignment.Start -> Arrangement.Start
                    Alignment.End -> Arrangement.End
                    else -> Arrangement.Center
                }
            }
        ) {
            RenderChildren(children = component.children, ctx = ctx)
        }
    } else {
        FlowRow(
            modifier = modifier.fillMaxWidth(),
            maxItemsInEachRow = maxItems,
            horizontalArrangement = ltbHorizontalArrangement(component.style.arrangement, 0f),
            verticalArrangement = ltbVerticalAlign(component.style.crossAlignment).let {
                when (it) {
                    Alignment.Top -> Arrangement.Top
                    Alignment.Bottom -> Arrangement.Bottom
                    else -> Arrangement.Center
                }
            }
        ) {
            RenderChildren(children = component.children, ctx = ctx)
        }
    }
}

// ------------------- Size Modifiers: expanded, flexible, constrained_box, sized_box, aspect_ratio -------------------

/**
 * Renderer for size-based containers: expanded, flexible, constrained_box, sized_box, aspect_ratio.
 * Uses Box with appropriate size modifiers.
 */
@Composable
fun SizedBoxRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    var boxModifier = modifier

    when (component.type) {
        ComponentTypes.EXPANDED -> {
            boxModifier = boxModifier.fillMaxSize()
        }
        ComponentTypes.FLEXIBLE -> {
            val flex = component.propFloat("flex", 1f)
            boxModifier = when (component.propString("direction")?.lowercase(Locale.ROOT)) {
                "horizontal" -> boxModifier.fillMaxWidth(flex)
                else -> boxModifier.fillMaxHeight(flex)
            }
        }
        ComponentTypes.CONSTRAINED_BOX -> {
            val minWidth = component.propFloat("minWidth", 0f)
            val maxWidth = component.propFloat("maxWidth", 0f)
            val minHeight = component.propFloat("minHeight", 0f)
            val maxHeight = component.propFloat("maxHeight", 0f)
            boxModifier = boxModifier.sizeIn(
                minWidth = if (minWidth > 0f) minWidth.dp else Dp.Unspecified,
                maxWidth = if (maxWidth > 0f) maxWidth.dp else Dp.Unspecified,
                minHeight = if (minHeight > 0f) minHeight.dp else Dp.Unspecified,
                maxHeight = if (maxHeight > 0f) maxHeight.dp else Dp.Unspecified
            )
        }
        ComponentTypes.SIZED_BOX -> {
            val width = component.style.width
            val height = component.style.height
            val widthDp = StyleResolver.resolveDp(width)
            val heightDp = StyleResolver.resolveDp(height)
            if (widthDp != null && heightDp != null) {
                boxModifier = boxModifier.requiredSize(widthDp, heightDp)
            } else if (widthDp != null) {
                boxModifier = boxModifier.width(widthDp)
            } else if (heightDp != null) {
                boxModifier = boxModifier.height(heightDp)
            }
        }
        ComponentTypes.ASPECT_RATIO -> {
            val ratio = component.propFloat("ratio", 1f)
            boxModifier = boxModifier.aspectRatio(ratio)
        }
    }

    Box(modifier = boxModifier, contentAlignment = ltbBoxAlign(component.style.alignment)) {
        RenderChildren(children = component.children, ctx = ctx)
    }
}

// ------------------- Padding and Margin Containers: padding_container, margin_container -------------------

/**
 * Renderer for padding and margin containers.
 * Applies padding or margin to a Box wrapping children.
 */
@Composable
fun PaddingContainerRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val isMargin = component.type == ComponentTypes.MARGIN_CONTAINER
    val edge = if (isMargin) component.style.margin else component.style.padding
    val paddingValues = StyleResolver.resolvePadding(edge)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(paddingValues),
        contentAlignment = ltbBoxAlign(component.style.alignment)
    ) {
        RenderChildren(children = component.children, ctx = ctx)
    }
}

// ------------------- Animated Container -------------------

/**
 * Renderer for animated_container.
 * Uses AnimatedVisibility to animate children in/out based on the visible property.
 */
@Composable
fun AnimatedContainerRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val visible = component.propBool("visible", true)

    AnimatedVisibility(
        visible = visible,
        modifier = modifier.fillMaxWidth()
    ) {
        Box(
            contentAlignment = ltbBoxAlign(component.style.alignment)
        ) {
            RenderChildren(children = component.children, ctx = ctx)
        }
    }
}

// ------------------- Clip -------------------

/**
 * Renderer for clip container.
 * Applies a clip modifier with the resolved shape to a Box wrapping children.
 */
@Composable
fun ClipRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val shape = StyleResolver.resolveShape(component.style)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
    ) {
        RenderChildren(children = component.children, ctx = ctx)
    }
}

// ------------------- Transform -------------------

/**
 * Renderer for transform container.
 * Applies graphicsLayer transform (rotation, scale, translation) to a Box wrapping children.
 *
 * Supported properties:
 * - rotation: rotation in degrees
 * - scaleX: horizontal scale factor
 * - scaleY: vertical scale factor
 * - translateX: horizontal translation in dp
 * - translateY: vertical translation in dp
 */
@Composable
fun TransformRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val rotation = component.propFloat("rotation", 0f)
    val scaleX = component.propFloat("scaleX", 1f)
    val scaleY = component.propFloat("scaleY", 1f)
    val translateX = component.propFloat("translateX", 0f)
    val translateY = component.propFloat("translateY", 0f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer(
                rotationZ = rotation,
                scaleX = scaleX,
                scaleY = scaleY,
                translationX = translateX,
                translationY = translateY
            )
    ) {
        RenderChildren(children = component.children, ctx = ctx)
    }
}

// ------------------- Opacity -------------------

/**
 * Renderer for opacity container.
 * Applies an alpha modifier to a Box wrapping children.
 *
 * Supported styles:
 * - opacity: alpha value (0.0 to 1.0)
 */
@Composable
fun OpacityRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val alpha = if (component.style.opacity < 1f) component.style.opacity else component.propFloat("opacity", 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .alpha(alpha.coerceIn(0f, 1f))
    ) {
        RenderChildren(children = component.children, ctx = ctx)
    }
}

// ------------------- Visibility -------------------

/**
 * Renderer for visibility container.
 * Uses AnimatedVisibility to show/hide children based on the visible property.
 */
@Composable
fun VisibilityRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val visible = component.propBool("visible", component.visible)

    AnimatedVisibility(
        visible = visible,
        modifier = modifier.fillMaxWidth()
    ) {
        RenderChildren(children = component.children, ctx = ctx)
    }
}

// ------------------- Positioned -------------------

/**
 * Renderer for positioned container.
 * Applies an offset (x, y) to a Box wrapping children.
 *
 * Supported properties:
 * - x: horizontal offset in dp
 * - y: vertical offset in dp
 */
@Composable
fun PositionedRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val x = component.propFloat("x", 0f)
    val y = component.propFloat("y", 0f)

    Box(
        modifier = modifier
            .offset(x = x.dp, y = y.dp)
    ) {
        RenderChildren(children = component.children, ctx = ctx)
    }
}

// ============================================================================
// TYPOGRAPHY DOMAIN RENDERERS (29 types)
// ====================================================================

// ------------------- Headings: heading1-6 -------------------

/**
 * Renderer for heading text components: heading1 through heading6.
 * Auto-sets font size based on the heading level:
 * heading1=32sp, heading2=28sp, heading3=24sp, heading4=22sp, heading5=20sp, heading6=18sp.
 */
@Composable
fun HeadingRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val text = component.propStringResolved("text", ctx).orEmpty()
    val config = typographyConfig(component.type)
    val baseStyle = TextStyle(
        fontSize = config.fontSize.sp,
        fontWeight = config.fontWeight,
        fontFamily = FontFamily.Default
    )
    val style = StyleResolver.buildTextStyle(component.style, ctx, baseStyle)
    val maxLines = component.style.maxLines ?: Int.MAX_VALUE
    val overflow = StyleResolver.resolveOverflow(component.style.overflow)

    Text(
        text = text,
        modifier = modifier,
        style = style,
        maxLines = maxLines,
        overflow = overflow
    )
}

// ------------------- Title and Subtitle: title, subtitle -------------------

/**
 * Renderer for title and subtitle text components.
 * title=22sp Medium, subtitle=16sp Normal.
 */
@Composable
fun TitleSubtitleRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val text = component.propStringResolved("text", ctx).orEmpty()
    val config = typographyConfig(component.type)
    val baseStyle = TextStyle(
        fontSize = config.fontSize.sp,
        fontWeight = config.fontWeight,
        fontFamily = FontFamily.Default
    )
    val style = StyleResolver.buildTextStyle(component.style, ctx, baseStyle)
    val maxLines = component.style.maxLines ?: Int.MAX_VALUE
    val overflow = StyleResolver.resolveOverflow(component.style.overflow)

    Text(
        text = text,
        modifier = modifier,
        style = style,
        maxLines = maxLines,
        overflow = overflow
    )
}

// ------------------- Body Text: text, body, caption, overline, label -------------------

/**
 * Renderer for body-level text components: text, body, caption, overline, label.
 * Maps each type to the appropriate auto-set font size and weight.
 */
@Composable
fun BodyTextRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val text = component.propStringResolved("text", ctx).orEmpty()
    val config = typographyConfig(component.type)
    val baseStyle = TextStyle(
        fontSize = config.fontSize.sp,
        fontWeight = config.fontWeight,
        fontFamily = FontFamily.Default
    )
    val style = StyleResolver.buildTextStyle(component.style, ctx, baseStyle)
    val maxLines = component.style.maxLines ?: Int.MAX_VALUE
    val overflow = StyleResolver.resolveOverflow(component.style.overflow)

    Text(
        text = text,
        modifier = modifier,
        style = style,
        maxLines = maxLines,
        overflow = overflow
    )
}

// ------------------- Display Text: display1-3 -------------------

/**
 * Renderer for display text components: display1, display2, display3.
 * display1=57sp, display2=45sp, display3=36sp.
 */
@Composable
fun DisplayRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val text = component.propStringResolved("text", ctx).orEmpty()
    val config = typographyConfig(component.type)
    val baseStyle = TextStyle(
        fontSize = config.fontSize.sp,
        fontWeight = config.fontWeight,
        fontFamily = FontFamily.Default
    )
    val style = StyleResolver.buildTextStyle(component.style, ctx, baseStyle)
    val maxLines = component.style.maxLines ?: Int.MAX_VALUE
    val overflow = StyleResolver.resolveOverflow(component.style.overflow)

    Text(
        text = text,
        modifier = modifier,
        style = style,
        maxLines = maxLines,
        overflow = overflow
    )
}

// ------------------- Headline and Subhead: headline, subhead -------------------

/**
 * Renderer for headline and subhead text components.
 * headline=28sp Bold, subhead=24sp SemiBold.
 */
@Composable
fun HeadlineSubheadRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val text = component.propStringResolved("text", ctx).orEmpty()
    val config = typographyConfig(component.type)
    val baseStyle = TextStyle(
        fontSize = config.fontSize.sp,
        fontWeight = config.fontWeight,
        fontFamily = FontFamily.Default
    )
    val style = StyleResolver.buildTextStyle(component.style, ctx, baseStyle)
    val maxLines = component.style.maxLines ?: Int.MAX_VALUE
    val overflow = StyleResolver.resolveOverflow(component.style.overflow)

    Text(
        text = text,
        modifier = modifier,
        style = style,
        maxLines = maxLines,
        overflow = overflow
    )
}

// ------------------- Monospace and Code Block: monospace, code_block -------------------

/**
 * Renderer for monospace and code_block components.
 * Uses FontFamily.Monospace and a background for code blocks.
 */
@Composable
fun MonospaceRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val text = component.propStringResolved("text", ctx).orEmpty()
    val config = typographyConfig(component.type)
    val baseStyle = TextStyle(
        fontSize = config.fontSize.sp,
        fontWeight = config.fontWeight,
        fontFamily = FontFamily.Monospace
    )
    val style = StyleResolver.buildTextStyle(component.style, ctx, baseStyle)
    val maxLines = component.style.maxLines ?: Int.MAX_VALUE
    val overflow = StyleResolver.resolveOverflow(component.style.overflow)

    if (component.type == ComponentTypes.CODE_BLOCK) {
        // v2：语法高亮 + 语言标签 + 复制 + 行号（零依赖引擎）
        val language = component.propString("language") ?: component.propString("lang")
        val showLineNums = component.propBool("lineNumbers", true)
        com.ai.assistance.quro.genui.sdk.components.CodeBlockV2(
            code = text,
            language = language,
            modifier = modifier,
            showLineNumbers = showLineNums
        )
    } else {
        Text(
            text = text,
            modifier = modifier,
            style = style,
            maxLines = maxLines,
            overflow = overflow
        )
    }
}

// ------------------- Markdown -------------------

/**
 * Renderer for markdown text component.
 * Parses simple markdown (# headings, **bold**, *italic*, `code`, - lists) into AnnotatedString.
 */
@Composable
fun MarkdownRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val text = component.propStringResolved("text", ctx).orEmpty()
    // v2：增强 GFM（任务列表/删除线/链接/引用/表格/Alerts/自动链接，流式安全）。
    // 组件声明 legacyInline:true 时回退旧单段 AnnotatedString。
    if (component.propBool("legacyInline", false)) {
        val annotated = parseMarkdown(text)
        val baseStyle = ctx.theme.typography.bodyMedium
        val style = StyleResolver.buildTextStyle(component.style, ctx, baseStyle)
        Text(
            text = annotated,
            modifier = modifier,
            style = style,
            maxLines = component.style.maxLines ?: Int.MAX_VALUE,
            overflow = StyleResolver.resolveOverflow(component.style.overflow)
        )
    } else {
        MarkdownPlusView(
            text = text,
            modifier = modifier,
            textColor = StyleResolver.resolveColor(component.style.textColor, ctx.theme.colorScheme, Color.Unspecified)
        )
    }
}

// ------------------- Rich Text and Text Span: rich_text, text_span -------------------

/**
 * Renderer for rich_text and text_span components.
 * Builds an AnnotatedString from child components or parses markdown from the text property.
 */
@Composable
fun RichTextRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val annotatedString = if (component.children.isNotEmpty()) {
        buildAnnotatedString {
            component.children.forEach { child ->
                val childText = child.propStringResolved("text", ctx) ?: ""
                val spanColor = StyleResolver.resolveTextColor(child.style, ctx, Color.Unspecified)
                val spanWeight = StyleResolver.resolveFontWeight(child.style.fontWeight)
                val spanStyle = SpanStyle(
                    color = if (spanColor != Color.Unspecified) spanColor else Color.Unspecified,
                    fontWeight = spanWeight,
                    fontStyle = if (child.style.textStyle?.lowercase(Locale.ROOT) == "italic") {
                        FontStyle.Italic
                    } else {
                        FontStyle.Normal
                    },
                    fontSize = child.style.textSize?.sp ?: TextUnit.Unspecified,
                    fontFamily = child.style.fontFamily?.let { FontProvider.resolve(it) },
                    textDecoration = when (child.type) {
                        ComponentTypes.STRIKETHROUGH -> TextDecoration.LineThrough
                        ComponentTypes.UNDERLINE -> TextDecoration.Underline
                        ComponentTypes.LINK_TEXT -> TextDecoration.Underline
                        else -> null
                    }
                )
                withStyle(spanStyle) {
                    append(childText)
                }
            }
        }
    } else {
        val text = component.propStringResolved("text", ctx).orEmpty()
        parseMarkdown(text)
    }

    val baseStyle = ctx.theme.typography.bodyMedium
    val style = StyleResolver.buildTextStyle(component.style, ctx, baseStyle)
    val maxLines = component.style.maxLines ?: Int.MAX_VALUE
    val overflow = StyleResolver.resolveOverflow(component.style.overflow)

    Text(
        text = annotatedString,
        modifier = modifier,
        style = style,
        maxLines = maxLines,
        overflow = overflow
    )
}

// ------------------- Selectable Text -------------------

/**
 * Renderer for selectable_text component.
 * Wraps a Text in a SelectionContainer to enable text selection.
 */
@Composable
fun SelectableTextRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val text = component.propStringResolved("text", ctx).orEmpty()
    val config = typographyConfig(component.type)
    val baseStyle = TextStyle(
        fontSize = config.fontSize.sp,
        fontWeight = config.fontWeight,
        fontFamily = FontFamily.Default
    )
    val style = StyleResolver.buildTextStyle(component.style, ctx, baseStyle)
    val maxLines = component.style.maxLines ?: Int.MAX_VALUE
    val overflow = StyleResolver.resolveOverflow(component.style.overflow)

    SelectionContainer {
        Text(
            text = text,
            modifier = modifier,
            style = style,
            maxLines = maxLines,
            overflow = overflow
        )
    }
}

// ------------------- Link Text -------------------

/**
 * Renderer for link_text component.
 * Renders text with underline decoration and a clickable modifier for link behavior.
 */
@Composable
fun LinkTextRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val text = component.propStringResolved("text", ctx).orEmpty()
    val url = component.propString("url")
    val config = typographyConfig(component.type)
    val linkColor = StyleResolver.resolveColor(
        component.style.textColor,
        ctx.theme.colorScheme,
        ctx.theme.colorScheme.primary
    )
    val baseStyle = TextStyle(
        fontSize = config.fontSize.sp,
        fontWeight = config.fontWeight,
        fontFamily = FontFamily.Default,
        color = linkColor,
        textDecoration = TextDecoration.Underline
    )
    val style = StyleResolver.buildTextStyle(component.style, ctx, baseStyle)
    val clickHandler = ctx.clickHandler(component)

    var linkModifier = modifier
    if (clickHandler != null) {
        linkModifier = linkModifier.clickable(onClick = clickHandler)
    }

    Text(
        text = text,
        modifier = linkModifier,
        style = style
    )
}

// ------------------- Quote -------------------

/**
 * Renderer for quote component.
 * Renders text with a left border bar and padding to create a blockquote effect.
 */
@Composable
fun QuoteRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val text = component.propStringResolved("text", ctx).orEmpty()
    val barColor = StyleResolver.resolveColor(
        component.style.borderColor,
        ctx.theme.colorScheme,
        ctx.theme.colorScheme.primary
    )
    val textColor = StyleResolver.resolveTextColor(
        component.style,
        ctx,
        ctx.theme.colorScheme.onSurfaceVariant
    )
    val style = StyleResolver.buildTextStyle(
        component.style,
        ctx,
        ctx.theme.typography.bodyMedium.copy(color = textColor)
    )

    Row(
        modifier = modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(24.dp)
                .background(barColor)
        )
        Text(
            text = text,
            modifier = Modifier
                .padding(start = 12.dp)
                .weight(1f),
            style = style
        )
    }
}

// ------------------- Emphasis -------------------

/**
 * Renderer for emphasis text component.
 * Renders text in italic style to convey emphasis.
 */
@Composable
fun EmphasisRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val text = component.propStringResolved("text", ctx).orEmpty()
    val config = typographyConfig(component.type)
    val baseStyle = TextStyle(
        fontSize = config.fontSize.sp,
        fontWeight = config.fontWeight,
        fontFamily = FontFamily.Default,
        fontStyle = FontStyle.Italic
    )
    val style = StyleResolver.buildTextStyle(component.style, ctx, baseStyle)
    val maxLines = component.style.maxLines ?: Int.MAX_VALUE
    val overflow = StyleResolver.resolveOverflow(component.style.overflow)

    Text(
        text = text,
        modifier = modifier,
        style = style,
        maxLines = maxLines,
        overflow = overflow
    )
}

// ------------------- Strikethrough -------------------

/**
 * Renderer for strikethrough text component.
 * Renders text with a line-through decoration.
 */
@Composable
fun StrikethroughRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val text = component.propStringResolved("text", ctx).orEmpty()
    val config = typographyConfig(component.type)
    val baseStyle = TextStyle(
        fontSize = config.fontSize.sp,
        fontWeight = config.fontWeight,
        fontFamily = FontFamily.Default,
        textDecoration = TextDecoration.LineThrough
    )
    val style = StyleResolver.buildTextStyle(component.style, ctx, baseStyle)
    val maxLines = component.style.maxLines ?: Int.MAX_VALUE
    val overflow = StyleResolver.resolveOverflow(component.style.overflow)

    Text(
        text = text,
        modifier = modifier,
        style = style,
        maxLines = maxLines,
        overflow = overflow
    )
}

// ------------------- Underline -------------------

/**
 * Renderer for underline text component.
 * Renders text with an underline decoration.
 */
@Composable
fun UnderlineRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val text = component.propStringResolved("text", ctx).orEmpty()
    val config = typographyConfig(component.type)
    val baseStyle = TextStyle(
        fontSize = config.fontSize.sp,
        fontWeight = config.fontWeight,
        fontFamily = FontFamily.Default,
        textDecoration = TextDecoration.Underline
    )
    val style = StyleResolver.buildTextStyle(component.style, ctx, baseStyle)
    val maxLines = component.style.maxLines ?: Int.MAX_VALUE
    val overflow = StyleResolver.resolveOverflow(component.style.overflow)

    Text(
        text = text,
        modifier = modifier,
        style = style,
        maxLines = maxLines,
        overflow = overflow
    )
}

// ============================================================================
// BUTTON DOMAIN RENDERERS (30 types)
// ====================================================================

// ------------------- Material3 Button Variants: button, filled_button, elevated_button, tonal_button, outlined_button, text_button -------------------

/**
 * Renderer for standard Material3 button variants: button, filled_button, elevated_button,
 * tonal_button, outlined_button, text_button.
 * Maps each variant to the corresponding Material3 button component.
 *
 * Supported properties:
 * - text: button label (supports data binding)
 * - icon: icon name
 * - iconPosition: left (default) or right
 * - enabled: whether the button is enabled
 * - state: variant state (default, disabled, loading)
 * - style: variant style (filled, elevated, tonal, outlined, text)
 * - color: color role (primary, secondary, tertiary, error, surface)
 */
@Composable
fun MaterialButtonRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val text = component.propStringResolved("text", ctx) ?: ""
    val iconName = component.propString("icon")
    val iconPosition = component.propString("iconPosition") ?: "left"
    val buttonState = resolveButtonState(component)
    val styleVariant = resolveButtonStyle(component)
    val colorRole = ComponentVariant.ColorRole.from(component.propString("color"))

    val containerColor = StyleResolver.resolveColor(
        component.style.backgroundColor,
        ctx.theme.colorScheme,
        resolveColorRole(colorRole, ctx)
    )
    val contentColor = StyleResolver.resolveColor(
        component.style.textColor,
        ctx.theme.colorScheme,
        resolveOnColorRole(colorRole, ctx)
    )
    val shape = StyleResolver.resolveShape(component.style)

    val onClick: () -> Unit = {
        component.events["onClick"]?.let { event ->
            ctx.executor.execute(event, component)
        }
    }

    when (styleVariant) {
        ComponentVariant.Style.TEXT -> TextButton(
            onClick = onClick,
            modifier = modifier,
            enabled = buttonState.enabled,
            shape = shape,
            colors = ButtonDefaults.textButtonColors(
                containerColor = containerColor,
                contentColor = contentColor
            )
        ) {
            if (buttonState.loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = contentColor
                )
            } else {
                ButtonContent(component, ctx, text, iconName, iconPosition, contentColor)
            }
        }

        ComponentVariant.Style.OUTLINED -> OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            enabled = buttonState.enabled,
            shape = shape,
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = containerColor,
                contentColor = contentColor
            )
        ) {
            if (buttonState.loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = contentColor
                )
            } else {
                ButtonContent(component, ctx, text, iconName, iconPosition, contentColor)
            }
        }

        ComponentVariant.Style.ELEVATED -> ElevatedButton(
            onClick = onClick,
            modifier = modifier,
            enabled = buttonState.enabled,
            shape = shape,
            colors = ButtonDefaults.elevatedButtonColors(
                containerColor = containerColor,
                contentColor = contentColor
            )
        ) {
            if (buttonState.loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = contentColor
                )
            } else {
                ButtonContent(component, ctx, text, iconName, iconPosition, contentColor)
            }
        }

        ComponentVariant.Style.TONAL -> FilledTonalButton(
            onClick = onClick,
            modifier = modifier,
            enabled = buttonState.enabled,
            shape = shape,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = containerColor,
                contentColor = contentColor
            )
        ) {
            if (buttonState.loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = contentColor
                )
            } else {
                ButtonContent(component, ctx, text, iconName, iconPosition, contentColor)
            }
        }

        ComponentVariant.Style.FILLED -> Button(
            onClick = onClick,
            modifier = modifier,
            enabled = buttonState.enabled,
            shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = containerColor,
                contentColor = contentColor
            )
        ) {
            if (buttonState.loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = contentColor
                )
            } else {
                ButtonContent(component, ctx, text, iconName, iconPosition, contentColor)
            }
        }
    }
}

// ------------------- Icon Button -------------------

/**
 * Renderer for icon_button component.
 * Uses Material3 IconButton with an icon from properties or IconMapper.
 *
 * Supported properties:
 * - icon: icon name (mapped via IconMapper)
 * - name: alternative icon name property
 * - enabled: whether the button is enabled
 */
@Composable
fun IconButtonRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val iconName = component.propString("icon") ?: component.propString("name") ?: "info"
    val buttonState = resolveButtonState(component)
    val tint = StyleResolver.resolveColor(
        component.style.textColor,
        ctx.theme.colorScheme,
        ctx.theme.colorScheme.onSurface
    )
    val iconSize = component.style.textSize?.let { it.toInt() }?.dp ?: 24.dp

    IconButton(
        onClick = {
            component.events["onClick"]?.let { event ->
                ctx.executor.execute(event, component)
            }
        },
        modifier = modifier,
        enabled = buttonState.enabled
    ) {
        Icon(
            imageVector = IconMapper.map(iconName),
            contentDescription = component.propString("contentDescription") ?: iconName,
            modifier = Modifier.size(iconSize),
            tint = tint
        )
    }
}

// ------------------- Floating Action Buttons: fab, extended_fab, floating_button -------------------

/**
 * Renderer for floating action button components: fab, extended_fab, floating_button.
 * Uses Material3 FloatingActionButton or ExtendedFloatingActionButton.
 *
 * Supported properties:
 * - icon: icon name for the FAB
 * - text: label text (required for extended_fab)
 * - color: color role
 */
@Composable
fun FabRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val iconName = component.propString("icon") ?: "add"
    val text = component.propStringResolved("text", ctx)
    val buttonState = resolveButtonState(component)
    val colorRole = ComponentVariant.ColorRole.from(component.propString("color"))
    val containerColor = StyleResolver.resolveColor(
        component.style.backgroundColor,
        ctx.theme.colorScheme,
        resolveColorRole(colorRole, ctx)
    )
    val contentColor = StyleResolver.resolveColor(
        component.style.textColor,
        ctx.theme.colorScheme,
        resolveOnColorRole(colorRole, ctx)
    )
    val shape = StyleResolver.resolveShape(component.style)

    val onClick: () -> Unit = {
        component.events["onClick"]?.let { event ->
            ctx.executor.execute(event, component)
        }
    }

    val isExtended = component.type == ComponentTypes.EXTENDED_FAB ||
        (text != null && text.isNotEmpty())

    if (isExtended) {
        ExtendedFloatingActionButton(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            containerColor = containerColor,
            contentColor = contentColor
        ) {
            Icon(
                imageVector = IconMapper.map(iconName),
                contentDescription = iconName,
                modifier = Modifier.size(24.dp),
                tint = contentColor
            )
            if (text != null && text.isNotEmpty()) {
                Text(text = text)
            }
        }
    } else {
        FloatingActionButton(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            containerColor = containerColor,
            contentColor = contentColor
        ) {
            if (buttonState.loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = contentColor
                )
            } else {
                Icon(
                    imageVector = IconMapper.map(iconName),
                    contentDescription = iconName,
                    modifier = Modifier.size(24.dp),
                    tint = contentColor
                )
            }
        }
    }
}

// ------------------- Split Button -------------------

/**
 * Renderer for split_button component.
 * Renders a Row with a main Button and a dropdown IconButton.
 */
@Composable
fun SplitButtonRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val text = component.propStringResolved("text", ctx) ?: "Split"
    val buttonState = resolveButtonState(component)
    val colorRole = ComponentVariant.ColorRole.from(component.propString("color"))
    val containerColor = StyleResolver.resolveColor(
        component.style.backgroundColor,
        ctx.theme.colorScheme,
        resolveColorRole(colorRole, ctx)
    )
    val contentColor = StyleResolver.resolveColor(
        component.style.textColor,
        ctx.theme.colorScheme,
        resolveOnColorRole(colorRole, ctx)
    )
    val shape = StyleResolver.resolveShape(component.style)

    val onClick: () -> Unit = {
        component.events["onClick"]?.let { event ->
            ctx.executor.execute(event, component)
        }
    }

    Row(modifier = modifier) {
        Button(
            onClick = onClick,
            enabled = buttonState.enabled,
            shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = containerColor,
                contentColor = contentColor
            )
        ) {
            Text(text = text)
        }
        IconButton(
            onClick = onClick,
            enabled = buttonState.enabled
        ) {
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = "More options",
                tint = contentColor
            )
        }
    }
}

// ------------------- Toggle Button -------------------

/**
 * Renderer for toggle_button component.
 * Uses a stateful approach with remember to toggle between selected and unselected states.
 *
 * Supported properties:
 * - text: button label
 * - selected: initial selected state
 * - icon: optional icon name
 */
@Composable
fun ToggleButtonRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val text = component.propStringResolved("text", ctx) ?: ""
    val iconName = component.propString("icon")
    val initialSelected = component.propBool("selected", false)
    val buttonState = resolveButtonState(component)
    val colorRole = ComponentVariant.ColorRole.from(component.propString("color"))

    var selected by remember { mutableStateOf(initialSelected) }

    val containerColor = if (selected) {
        StyleResolver.resolveColor(
            component.style.backgroundColor,
            ctx.theme.colorScheme,
            resolveColorRole(colorRole, ctx)
        )
    } else {
        StyleResolver.resolveColor(
            component.style.backgroundColor,
            ctx.theme.colorScheme,
            ctx.theme.colorScheme.surfaceVariant
        )
    }
    val contentColor = if (selected) {
        StyleResolver.resolveColor(
            component.style.textColor,
            ctx.theme.colorScheme,
            resolveOnColorRole(colorRole, ctx)
        )
    } else {
        StyleResolver.resolveColor(
            component.style.textColor,
            ctx.theme.colorScheme,
            ctx.theme.colorScheme.onSurfaceVariant
        )
    }
    val shape = StyleResolver.resolveShape(component.style)

    val onClick: () -> Unit = {
        selected = !selected
        component.events["onClick"]?.let { event ->
            ctx.executor.execute(event, component)
        }
    }

    Surface(
        modifier = modifier,
        shape = shape,
        color = containerColor,
        contentColor = contentColor
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (iconName != null) {
                Icon(
                    imageVector = IconMapper.map(iconName),
                    contentDescription = iconName,
                    modifier = Modifier.size(20.dp),
                    tint = contentColor
                )
            }
            if (text.isNotEmpty()) {
                Text(text = text, color = contentColor)
            }
        }
    }
}

// ------------------- Segmented Button -------------------

/**
 * Renderer for segmented_button component.
 * Renders a Row of toggle-style buttons as segments. Each child component represents one segment.
 *
 * Supported properties:
 * - selectedIndex: initially selected segment index
 */
@Composable
fun SegmentedButtonRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val selectedIndex = component.propInt("selectedIndex", 0)
    var selected by remember { mutableStateOf(selectedIndex) }
    val colorRole = ComponentVariant.ColorRole.from(component.propString("color"))
    val selectedColor = resolveColorRole(colorRole, ctx)
    val unselectedColor = ctx.theme.colorScheme.surfaceVariant
    val selectedContentColor = resolveOnColorRole(colorRole, ctx)
    val unselectedContentColor = ctx.theme.colorScheme.onSurfaceVariant
    val shape = StyleResolver.resolveShape(component.style)

    val segments = component.children

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
    ) {
        segments.forEachIndexed { index, segment ->
            val isSelected = index == selected
            val segmentColor = if (isSelected) selectedColor else unselectedColor
            val segmentContentColor = if (isSelected) selectedContentColor else unselectedContentColor
            val segmentText = segment.propStringResolved("text", ctx) ?: segment.propString("text") ?: ""
            val segmentIcon = segment.propString("icon")

            Surface(
                modifier = Modifier.weight(1f),
                color = segmentColor,
                shape = if (index == 0 || index == segments.lastIndex) shape else RectangleShape
            ) {
                Row(
                    modifier = Modifier
                        .clickable {
                            selected = index
                            component.events["onChange"]?.let { event ->
                                ctx.executor.execute(event, component)
                            }
                        }
                        .padding(vertical = 8.dp, horizontal = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    content = {
                        if (segmentIcon != null) {
                            Icon(
                                imageVector = IconMapper.map(segmentIcon),
                                contentDescription = segmentIcon,
                                modifier = Modifier.size(18.dp),
                                tint = segmentContentColor
                            )
                        }
                        if (segmentText.isNotEmpty()) {
                            Text(
                                text = segmentText,
                                color = segmentContentColor,
                                style = ctx.theme.typography.labelLarge
                            )
                        }
                    }
                )
            }
        }
    }
}

// ------------------- Button Group -------------------

/**
 * Renderer for button_group component.
 * Renders children buttons in a Row or Column based on the direction property.
 *
 * Supported properties:
 * - direction: horizontal (default) or vertical
 * - spacing: gap between buttons
 */
@Composable
fun ButtonGroupRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val direction = component.propString("direction") ?: "horizontal"
    val spacing = component.propFloat("spacing", 8f)
    val spacingDp = if (spacing > 0f) spacing.dp else 8.dp

    if (direction.lowercase(Locale.ROOT) == "vertical") {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacingDp)
        ) {
            RenderChildren(children = component.children, ctx = ctx)
        }
    } else {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacingDp)
        ) {
            RenderChildren(children = component.children, ctx = ctx)
        }
    }
}

// ------------------- Dropdown Button -------------------

/**
 * Renderer for dropdown_button component.
 * Renders a Button with a trailing dropdown arrow icon.
 *
 * Supported properties:
 * - text: button label
 * - items: list of dropdown item labels
 * - enabled: whether the button is enabled
 */
@Composable
fun DropdownButtonRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val text = component.propStringResolved("text", ctx) ?: "Dropdown"
    val buttonState = resolveButtonState(component)
    val colorRole = ComponentVariant.ColorRole.from(component.propString("color"))
    val containerColor = StyleResolver.resolveColor(
        component.style.backgroundColor,
        ctx.theme.colorScheme,
        resolveColorRole(colorRole, ctx)
    )
    val contentColor = StyleResolver.resolveColor(
        component.style.textColor,
        ctx.theme.colorScheme,
        resolveOnColorRole(colorRole, ctx)
    )
    val shape = StyleResolver.resolveShape(component.style)

    Button(
        onClick = {
            component.events["onClick"]?.let { event ->
                ctx.executor.execute(event, component)
            }
        },
        modifier = modifier,
        enabled = buttonState.enabled,
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor
        )
    ) {
        Text(text = text)
        Icon(
            imageVector = Icons.Filled.ArrowDropDown,
            contentDescription = "Dropdown",
            modifier = Modifier.size(20.dp),
            tint = contentColor
        )
    }
}

// ------------------- Action Chip -------------------

/**
 * Renderer for action_chip component.
 * Uses Material3 AssistChip with label and optional icon.
 *
 * Supported properties:
 * - text / label: chip label text
 * - icon: optional leading icon name
 * - enabled: whether the chip is enabled
 */
@Composable
fun ActionChipRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val label = component.propStringResolved("text", ctx)
        ?: component.propStringResolved("label", ctx) ?: ""
    val iconName = component.propString("icon")
    val enabled = component.propBool("enabled", true)
    val backgroundColor = StyleResolver.resolveColor(
        component.style.backgroundColor,
        ctx.theme.colorScheme,
        ctx.theme.colorScheme.surfaceVariant
    )
    val textColor = StyleResolver.resolveColor(
        component.style.textColor,
        ctx.theme.colorScheme,
        ctx.theme.colorScheme.onSurfaceVariant
    )

    AssistChip(
        onClick = {
            component.events["onClick"]?.let { event ->
                ctx.executor.execute(event, component)
            }
        },
        label = { Text(text = label, color = textColor) },
        modifier = modifier,
        enabled = enabled,
        leadingIcon = if (iconName != null) {
            {
                Icon(
                    imageVector = IconMapper.map(iconName),
                    contentDescription = iconName,
                    modifier = Modifier.size(18.dp),
                    tint = textColor
                )
            }
        } else null,
        colors = AssistChipDefaults.assistChipColors(
            containerColor = backgroundColor,
            labelColor = textColor
        )
    )
}

// ------------------- Speed Dial -------------------

/**
 * Renderer for speed_dial component.
 * Renders a Column of FloatingActionButton items that act as a speed dial menu.
 * The first child is the main FAB; subsequent children are the dial actions.
 *
 * Supported properties:
 * - direction: direction of dial expansion (up, down, left, right)
 */
@Composable
fun SpeedDialRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val direction = component.propString("direction") ?: "up"
    val isVertical = direction.lowercase(Locale.ROOT) in listOf("up", "down")
    val spacing = 8.dp

    if (isVertical) {
        Column(
            modifier = modifier,
            verticalArrangement = if (direction.lowercase(Locale.ROOT) == "down") {
                Arrangement.spacedBy(spacing)
            } else {
                Arrangement.spacedBy(spacing)
            },
            horizontalAlignment = Alignment.End
        ) {
            component.children.reversed().forEach { child ->
                val iconName = child.propString("icon") ?: "add"
                val labelText = child.propStringResolved("text", ctx)
                val colorRole = ComponentVariant.ColorRole.from(child.propString("color"))
                val containerColor = StyleResolver.resolveColor(
                    child.style.backgroundColor,
                    ctx.theme.colorScheme,
                    resolveColorRole(colorRole, ctx)
                )
                val contentColor = StyleResolver.resolveColor(
                    child.style.textColor,
                    ctx.theme.colorScheme,
                    resolveOnColorRole(colorRole, ctx)
                )

                ExtendedFloatingActionButton(
                    onClick = {
                        component.events["onClick"]?.let { event ->
                            ctx.executor.execute(event, child)
                        }
                    },
                    containerColor = containerColor,
                    contentColor = contentColor
                ) {
                    Icon(
                        imageVector = IconMapper.map(iconName),
                        contentDescription = iconName,
                        modifier = Modifier.size(24.dp),
                        tint = contentColor
                    )
                    if (labelText != null && labelText.isNotEmpty()) {
                        Text(text = labelText)
                    }
                }
            }
        }
    } else {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalAlignment = Alignment.CenterVertically
        ) {
            component.children.forEach { child ->
                val iconName = child.propString("icon") ?: "add"
                val colorRole = ComponentVariant.ColorRole.from(child.propString("color"))
                val containerColor = StyleResolver.resolveColor(
                    child.style.backgroundColor,
                    ctx.theme.colorScheme,
                    resolveColorRole(colorRole, ctx)
                )
                val contentColor = StyleResolver.resolveColor(
                    child.style.textColor,
                    ctx.theme.colorScheme,
                    resolveOnColorRole(colorRole, ctx)
                )

                FloatingActionButton(
                    onClick = {
                        component.events["onClick"]?.let { event ->
                            ctx.executor.execute(event, child)
                        }
                    },
                    containerColor = containerColor,
                    contentColor = contentColor
                ) {
                    Icon(
                        imageVector = IconMapper.map(iconName),
                        contentDescription = iconName,
                        modifier = Modifier.size(24.dp),
                        tint = contentColor
                    )
                }
            }
        }
    }
}

// ------------------- Action Buttons: submit, cancel, confirm, link, share, download, upload, play, pause, stop, reload, add, delete -------------------

/**
 * Renderer for action-specific button types: submit_button, cancel_button, confirm_button,
 * link_button, share_button, download_button, upload_button, play_button, pause_button,
 * stop_button, reload_button, add_button, delete_button.
 *
 * Each type maps to a specific icon and default text. The button style is resolved from
 * the style property or defaults to filled. Color roles are determined per action type.
 *
 * Supported properties:
 * - text: button label (overrides default label for the action type)
 * - icon: icon name override (overrides default icon for the action type)
 * - enabled: whether the button is enabled
 * - style: variant style (filled, elevated, tonal, outlined, text)
 * - state: variant state
 */
@Composable
fun ActionButtonRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val config = actionButtonConfig(component.type)
    val text = component.propStringResolved("text", ctx) ?: config.defaultText
    val iconName = component.propString("icon")
    val iconVector = if (iconName != null) IconMapper.map(iconName) else config.icon
    val iconPosition = component.propString("iconPosition") ?: "left"
    val buttonState = resolveButtonState(component)
    val styleVariant = resolveButtonStyle(component)

    val colorRole = ComponentVariant.ColorRole.from(component.propString("color"))
    val resolvedColorRole = if (colorRole == ComponentVariant.ColorRole.PRIMARY && config.colorRole != ComponentVariant.ColorRole.PRIMARY) {
        config.colorRole
    } else {
        colorRole
    }

    val containerColor = StyleResolver.resolveColor(
        component.style.backgroundColor,
        ctx.theme.colorScheme,
        resolveColorRole(resolvedColorRole, ctx)
    )
    val contentColor = StyleResolver.resolveColor(
        component.style.textColor,
        ctx.theme.colorScheme,
        resolveOnColorRole(resolvedColorRole, ctx)
    )
    val shape = StyleResolver.resolveShape(component.style)

    val onClick: () -> Unit = {
        component.events["onClick"]?.let { event ->
            ctx.executor.execute(event, component)
        }
    }

    val content: @Composable RowScope.() -> Unit = {
        if (buttonState.loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = contentColor
            )
        } else {
            if (iconPosition == "left") {
                Icon(
                    imageVector = iconVector,
                    contentDescription = config.defaultText,
                    modifier = Modifier.size(20.dp),
                    tint = contentColor
                )
            }
            Text(text = text)
            if (iconPosition == "right") {
                Icon(
                    imageVector = iconVector,
                    contentDescription = config.defaultText,
                    modifier = Modifier.size(20.dp),
                    tint = contentColor
                )
            }
        }
    }

    when (styleVariant) {
        ComponentVariant.Style.TEXT -> TextButton(
            onClick = onClick,
            modifier = modifier,
            enabled = buttonState.enabled,
            shape = shape,
            colors = ButtonDefaults.textButtonColors(
                containerColor = containerColor,
                contentColor = contentColor
            ),
            content = content
        )

        ComponentVariant.Style.OUTLINED -> OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            enabled = buttonState.enabled,
            shape = shape,
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = containerColor,
                contentColor = contentColor
            ),
            content = content
        )

        ComponentVariant.Style.ELEVATED -> ElevatedButton(
            onClick = onClick,
            modifier = modifier,
            enabled = buttonState.enabled,
            shape = shape,
            colors = ButtonDefaults.elevatedButtonColors(
                containerColor = containerColor,
                contentColor = contentColor
            ),
            content = content
        )

        ComponentVariant.Style.TONAL -> FilledTonalButton(
            onClick = onClick,
            modifier = modifier,
            enabled = buttonState.enabled,
            shape = shape,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = containerColor,
                contentColor = contentColor
            ),
            content = content
        )

        ComponentVariant.Style.FILLED -> Button(
            onClick = onClick,
            modifier = modifier,
            enabled = buttonState.enabled,
            shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = containerColor,
                contentColor = contentColor
            ),
            content = content
        )
    }
}
