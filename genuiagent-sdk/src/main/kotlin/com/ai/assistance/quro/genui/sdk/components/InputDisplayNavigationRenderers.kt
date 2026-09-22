@file:OptIn(ExperimentalMaterial3Api::class)

package com.ai.assistance.quro.genui.sdk.components

import androidx.compose.material3.minimumInteractiveComponentSize

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.genui.sdk.dsl.Dimension
import com.ai.assistance.quro.genui.sdk.dsl.UIComponent
import com.ai.assistance.quro.genui.sdk.render.RenderChildren
import com.ai.assistance.quro.genui.sdk.render.RenderContext
import com.ai.assistance.quro.genui.sdk.render.StyleResolver
import kotlin.math.PI
import kotlin.math.sin

/**
 * Input / Display / Navigation renderer collection for the GenUI SDK.
 *
 * This file provides @Composable renderer functions for every component type in the
 * Input (29), Display (29) and Navigation (30) domains.
 *
 * Naming convention: each renderer is named `Render<PascalType>` to remain conflict-free
 * with the existing `*Renderer` functions elsewhere in the components package.
 *
 * Every renderer takes (component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier),
 * reads variant properties from [UIComponent.properties], resolves colors through [StyleResolver]
 * and icons through [IconMapper], and uses Material3 building blocks.
 */

// =====================================================================================
// ============================ SHARED HELPERS =========================================
// =====================================================================================

/** Resolve an accent color (defaults to the theme primary color). */
private fun accentColor(component: UIComponent, ctx: RenderContext): Color =
    StyleResolver.resolveColor(
        component.style.textColor,
        ctx.theme.colorScheme,
        ctx.theme.colorScheme.primary
    )

/** Resolve a container/background color with a fallback. */
private fun containerColor(component: UIComponent, ctx: RenderContext, fallback: Color): Color =
    StyleResolver.resolveColor(
        component.style.backgroundColor,
        ctx.theme.colorScheme,
        fallback
    )

/** Best-effort label/title/text for display components. */
private fun UIComponent.titleOrLabel(ctx: RenderContext): String =
    propStringResolved("title", ctx)
        ?: propStringResolved("label", ctx)
        ?: propStringResolved("text", ctx)
        ?: ""

/** Placeholder / hint text. */
private fun UIComponent.placeholderOrHint(ctx: RenderContext): String? =
    propStringResolved("placeholder", ctx) ?: propStringResolved("hint", ctx)

/** Options/items list coming from properties or child labels. */
private fun UIComponent.optionItems(ctx: RenderContext): List<String> =
    propStringList("options").ifEmpty {
        children.mapNotNull { it.propStringResolved("label", ctx) ?: it.propString("text") }
    }.ifEmpty { listOf("Item") }

/** Fixed-size Dp from style width/height, falling back to a default. */
private fun UIComponent.sizeDp(default: Dp, horizontal: Boolean): Dp {
    val dim = if (horizontal) style.width else style.height
    return (dim as? Dimension.Fixed)?.dp?.dp ?: default
}

/**
 * Shared, stateful text-field renderer used by text_field / text_area / search_field / search_bar.
 */
@Composable
private fun BaseTextFieldRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    masked: Boolean = false,
    leadingIconName: String? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    shapeOverride: Shape? = null
) {
    val valueKey = component.propString("value") ?: ""
    val initial = component.propStringResolved("value", ctx).orEmpty()
    var text by remember { mutableStateOf(initial) }
    val placeholder = component.placeholderOrHint(ctx)
    val label = component.propStringResolved("label", ctx)
    val enabled = component.propBool("enabled", true)
    val readOnly = component.propBool("readOnly", false)
    val variant = component.propString("variant") ?: "filled"

    val containerBg = containerColor(component, ctx, Color.Transparent)
    val textColor = StyleResolver.resolveColor(
        component.style.textColor, ctx.theme.colorScheme, ctx.theme.colorScheme.onSurface
    )
    val shape = shapeOverride ?: StyleResolver.resolveShape(component.style)
    val visualTransform: VisualTransformation =
        if (masked) PasswordVisualTransformation() else VisualTransformation.None

    val onValueChange: (String) -> Unit = { newText ->
        text = newText
        if (valueKey.isNotEmpty()) ctx.state.set(valueKey, newText)
        component.events["onChange"]?.let { event -> ctx.executor.execute(event, component) }
    }

    val leadingIcon: (@Composable () -> Unit)? = leadingIconName?.let { name ->
        { Icon(imageVector = IconMapper.map(name), contentDescription = null) }
    }

    val colors = TextFieldDefaults.colors(
        focusedContainerColor = containerBg,
        unfocusedContainerColor = containerBg,
        focusedTextColor = textColor,
        unfocusedTextColor = textColor
    )

    val labelContent: (@Composable () -> Unit)? = label?.let { str -> { Text(str) } }
    val placeholderContent: (@Composable () -> Unit)? = placeholder?.let { str -> { Text(str) } }

    if (variant == "outlined") {
        OutlinedTextField(
            value = text,
            onValueChange = onValueChange,
            modifier = modifier.fillMaxWidth(),
            label = labelContent,
            placeholder = placeholderContent,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            singleLine = singleLine,
            enabled = enabled,
            readOnly = readOnly,
            shape = shape,
            visualTransformation = visualTransform,
            colors = colors
        )
    } else {
        TextField(
            value = text,
            onValueChange = onValueChange,
            modifier = modifier.fillMaxWidth(),
            label = labelContent,
            placeholder = placeholderContent,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            singleLine = singleLine,
            enabled = enabled,
            readOnly = readOnly,
            shape = shape,
            visualTransformation = visualTransform,
            colors = colors
        )
    }
}

/** A read-only field trigger (used by date/time/datetime pickers and select triggers). */
@Composable
private fun FieldTrigger(
    value: String,
    trailing: ImageVector,
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val shape = StyleResolver.resolveShape(component.style)
    val outline = ctx.theme.colorScheme.outline
    val bg = containerColor(component, ctx, ctx.theme.colorScheme.surface)
    val textColor = StyleResolver.resolveColor(
        component.style.textColor, ctx.theme.colorScheme, ctx.theme.colorScheme.onSurface
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, outline)
            .background(bg)
            .minimumInteractiveComponentSize().clickable { ctx.clickHandler(component)?.invoke() }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = value,
            color = textColor,
            style = ctx.theme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = trailing,
            contentDescription = null,
            tint = ctx.theme.colorScheme.onSurfaceVariant
        )
    }
}

/** Animated star row used by rating / star_rating / rating_input. */
@Composable
private fun StarsRow(
    value: Int,
    max: Int,
    interactive: Boolean,
    onRating: (Int) -> Unit,
    starColor: Color,
    starSize: Dp
) {
    Row {
        repeat(max) { i ->
            val filled = i < value
            Icon(
                imageVector = if (filled) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = null,
                tint = starColor,
                modifier = Modifier
                    .size(starSize)
                    .minimumInteractiveComponentSize().clickable(enabled = interactive) { onRating(i + 1) }
            )
        }
    }
}

/** A single OTP / PIN cell. */
@Composable
private fun OtpCell(initial: String, masked: Boolean, textColor: Color, borderColor: Color, bgColor: Color) {
    var text by remember { mutableStateOf(initial) }
    BasicTextField(
        value = text,
        onValueChange = { v -> text = v.takeLast(1) },
        singleLine = true,
        textStyle = TextStyle(
            color = textColor,
            fontSize = 18.sp,
            textAlign = TextAlign.Center
        ),
        visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(bgColor)
                    .border(1.dp, borderColor),
                contentAlignment = Alignment.Center
            ) { innerTextField() }
        }
    )
}

/** A small navigation item row used by side_menu / nav_drawer / nav_item. */
@Composable
private fun NavItemRow(
    label: String,
    iconName: String?,
    selected: Boolean,
    onClick: () -> Unit,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val primary = ctx.theme.colorScheme.primary
    val labelColor = if (selected) primary else ctx.theme.colorScheme.onSurface
    Row(
        modifier = modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = IconMapper.map(iconName),
            contentDescription = null,
            tint = labelColor
        )
        Spacer(Modifier.width(12.dp))
        Text(text = label, color = labelColor, style = ctx.theme.typography.bodyMedium)
    }
}

/** Builds a list of (label, iconName) pairs for nav-style components. */
private fun UIComponent.navItems(ctx: RenderContext): List<Pair<String, String?>> {
    val labels = propStringList("items").ifEmpty {
        children.mapNotNull { it.propStringResolved("label", ctx) ?: it.propString("text") }
    }
    val icons = propStringList("icons")
    return labels.mapIndexed { i, label -> label to icons.getOrNull(i) }
        .ifEmpty { listOf("Item" to null) }
}

// =====================================================================================
// ============================ INPUT DOMAIN (29) ======================================
// =====================================================================================

/** text_field */
@Composable
fun RenderTextField(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    BaseTextFieldRenderer(
        component = component,
        ctx = ctx,
        modifier = modifier,
        singleLine = true
    )
}

/** text_area */
@Composable
fun RenderTextArea(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val minLines = component.propInt("rows", 3).coerceAtLeast(1)
    BaseTextFieldRenderer(
        component = component,
        ctx = ctx,
        modifier = modifier.height((minLines * 24 + 16).dp),
        singleLine = false
    )
}

/** password_field */
@Composable
fun RenderPasswordField(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val valueKey = component.propString("value") ?: ""
    val initial = component.propStringResolved("value", ctx).orEmpty()
    var text by remember { mutableStateOf(initial) }
    var visible by remember { mutableStateOf(false) }
    val placeholder = component.placeholderOrHint(ctx)
    val label = component.propStringResolved("label", ctx)
    val enabled = component.propBool("enabled", true)
    val containerBg = containerColor(component, ctx, Color.Transparent)
    val textColor = StyleResolver.resolveColor(
        component.style.textColor, ctx.theme.colorScheme, ctx.theme.colorScheme.onSurface
    )
    val shape = StyleResolver.resolveShape(component.style)
    val onValueChange: (String) -> Unit = { newText ->
        text = newText
        if (valueKey.isNotEmpty()) ctx.state.set(valueKey, newText)
        component.events["onChange"]?.let { ctx.executor.execute(it, component) }
    }
    OutlinedTextField(
        value = text,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        enabled = enabled,
        shape = shape,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (visible) "Hide password" else "Show password"
                )
            }
        },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = containerBg,
            unfocusedContainerColor = containerBg,
            focusedTextColor = textColor,
            unfocusedTextColor = textColor
        )
    )
}

/** search_field */
@Composable
fun RenderSearchField(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    BaseTextFieldRenderer(
        component = component,
        ctx = ctx,
        modifier = modifier,
        singleLine = true,
        leadingIconName = "search"
    )
}

/** checkbox */
@Composable
fun RenderCheckbox(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val checked = component.propBool("value", false)
    val enabled = component.propBool("enabled", true)
    val label = component.propStringResolved("label", ctx)
    val color = accentColor(component, ctx)
    var state by remember { mutableStateOf(checked) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize().clickable(enabled = enabled) { state = !state }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = state,
            onCheckedChange = { state = it },
            enabled = enabled,
            colors = CheckboxDefaults.colors(
                checkedColor = color,
                uncheckedColor = ctx.theme.colorScheme.outline
            )
        )
        if (label != null) {
            Spacer(Modifier.width(8.dp))
            Text(text = label, color = ctx.theme.colorScheme.onSurface)
        }
    }
}

/** radio */
@Composable
fun RenderRadio(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val selected = component.propBool("value", false)
    val enabled = component.propBool("enabled", true)
    val label = component.propStringResolved("label", ctx)
    val color = accentColor(component, ctx)
    var state by remember { mutableStateOf(selected) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize().clickable(enabled = enabled) { state = true }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = state,
            onClick = { state = true },
            enabled = enabled,
            colors = RadioButtonDefaults.colors(selectedColor = color)
        )
        if (label != null) {
            Spacer(Modifier.width(8.dp))
            Text(text = label, color = ctx.theme.colorScheme.onSurface)
        }
    }
}

/** radio_group */
@Composable
fun RenderRadioGroup(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val options = component.optionItems(ctx)
    val initial = component.propInt("value", 0).coerceIn(0, options.lastIndex)
    val enabled = component.propBool("enabled", true)
    val color = accentColor(component, ctx)
    var selected by remember { mutableIntStateOf(initial) }
    Column(modifier = modifier.fillMaxWidth()) {
        options.forEachIndexed { i, option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize().clickable(enabled = enabled) { selected = i }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = selected == i,
                    onClick = { selected = i },
                    enabled = enabled,
                    colors = RadioButtonDefaults.colors(selectedColor = color)
                )
                Spacer(Modifier.width(8.dp))
                Text(text = option, color = ctx.theme.colorScheme.onSurface)
            }
        }
    }
}

/** switch */
@Composable
fun RenderSwitch(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val checked = component.propBool("value", false)
    val enabled = component.propBool("enabled", true)
    val label = component.propStringResolved("label", ctx)
    val color = accentColor(component, ctx)
    var state by remember { mutableStateOf(checked) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize().clickable(enabled = enabled) { state = !state }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Switch(
            checked = state,
            onCheckedChange = { state = it },
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = color,
                uncheckedThumbColor = ctx.theme.colorScheme.outline,
                uncheckedTrackColor = ctx.theme.colorScheme.surfaceVariant
            )
        )
        if (label != null) {
            Spacer(Modifier.width(8.dp))
            Text(text = label, color = ctx.theme.colorScheme.onSurface)
        }
    }
}

/** slider */
@Composable
fun RenderSlider(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val value = component.propFloat("value", 0f)
    val min = component.propFloat("min", 0f)
    val max = component.propFloat("max", 1f)
    val steps = component.propInt("steps", 0)
    val enabled = component.propBool("enabled", true)
    val color = accentColor(component, ctx)
    var sliderValue by remember { mutableFloatStateOf(value.coerceIn(min, max)) }
    Slider(
        value = sliderValue,
        onValueChange = { sliderValue = it },
        valueRange = min..max,
        steps = if (steps > 0) steps else 0,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        colors = SliderDefaults.colors(
            thumbColor = color,
            activeTrackColor = color,
            inactiveTrackColor = color.copy(alpha = 0.3f)
        )
    )
}

/** range_slider */
@Composable
fun RenderRangeSlider(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val min = component.propFloat("min", 0f)
    val max = component.propFloat("max", 1f)
    val start = component.propFloat("startValue", min).coerceIn(min, max)
    val end = component.propFloat("endValue", max).coerceIn(min, max)
    val enabled = component.propBool("enabled", true)
    var range by remember { mutableStateOf(start..end) }
    RangeSlider(
        value = range,
        onValueChange = { range = it },
        valueRange = min..max,
        enabled = enabled,
        modifier = modifier.fillMaxWidth()
    )
}

/** dropdown */
@Composable
fun RenderDropdown(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val options = component.optionItems(ctx)
    val initial = component.propInt("value", 0).coerceIn(0, options.lastIndex)
    var selected by remember { mutableIntStateOf(initial) }
    var expanded by remember { mutableStateOf(false) }
    val color = accentColor(component, ctx)
    val shape = StyleResolver.resolveShape(component.style)
    val bg = containerColor(component, ctx, ctx.theme.colorScheme.surfaceContainer)
    Box {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .clip(shape)
                .background(bg)
                .minimumInteractiveComponentSize().clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = options.getOrElse(selected) { "Select" },
                color = ctx.theme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Icon(imageVector = Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = color)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { i, option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = { selected = i; expanded = false }
                )
            }
        }
    }
}

/** select */
@Composable
fun RenderSelect(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val options = component.optionItems(ctx)
    val initial = component.propInt("value", 0).coerceIn(0, options.lastIndex)
    var selected by remember { mutableIntStateOf(initial) }
    var expanded by remember { mutableStateOf(false) }
    val outline = ctx.theme.colorScheme.outline
    val shape = StyleResolver.resolveShape(component.style)
    val bg = containerColor(component, ctx, ctx.theme.colorScheme.surface)
    Box {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .clip(shape)
                .border(1.dp, outline)
                .background(bg)
                .minimumInteractiveComponentSize().clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = options.getOrElse(selected) { "Select" },
                color = ctx.theme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = ctx.theme.colorScheme.onSurfaceVariant
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { i, option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = { selected = i; expanded = false }
                )
            }
        }
    }
}

/** multi_select */
@Composable
fun RenderMultiSelect(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val options = component.optionItems(ctx)
    val initialSelected = component.propInt("value", 0)
    var selected by remember { mutableStateOf(setOf(initialSelected.coerceIn(0, options.lastIndex))) }
    var expanded by remember { mutableStateOf(false) }
    val outline = ctx.theme.colorScheme.outline
    val shape = StyleResolver.resolveShape(component.style)
    val bg = containerColor(component, ctx, ctx.theme.colorScheme.surface)
    Box {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .clip(shape)
                .border(1.dp, outline)
                .background(bg)
                .minimumInteractiveComponentSize().clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (selected.isEmpty()) "Select" else "${selected.size} selected",
                color = ctx.theme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = ctx.theme.colorScheme.onSurfaceVariant
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { i, option ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = i in selected,
                                onCheckedChange = { isChecked ->
                                    selected = if (isChecked) selected + i else selected - i
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(option)
                        }
                    },
                    onClick = {
                        selected = if (i in selected) selected - i else selected + i
                    }
                )
            }
        }
    }
}

/** date_picker */
@Composable
fun RenderDatePicker(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val value = component.propStringResolved("value", ctx)
        ?: component.placeholderOrHint(ctx)
        ?: "Select date"
    FieldTrigger(value, Icons.Filled.CalendarToday, component, ctx, modifier)
}

/** time_picker */
@Composable
fun RenderTimePicker(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val value = component.propStringResolved("value", ctx)
        ?: component.placeholderOrHint(ctx)
        ?: "Select time"
    FieldTrigger(value, Icons.Filled.Schedule, component, ctx, modifier)
}

/** date_time_picker */
@Composable
fun RenderDateTimePicker(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val value = component.propStringResolved("value", ctx)
        ?: component.placeholderOrHint(ctx)
        ?: "Select date & time"
    FieldTrigger(value, Icons.Filled.CalendarToday, component, ctx, modifier)
}

/** color_picker */
@Composable
fun RenderColorPicker(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val names = component.propStringList("colors")
        .ifEmpty { listOf("primary", "secondary", "tertiary", "error") }
    val colors = names.map {
        StyleResolver.resolveColor(it, ctx.theme.colorScheme, ctx.theme.colorScheme.primary)
    }
    var selected by remember { mutableIntStateOf(component.propInt("value", 0).coerceIn(0, colors.lastIndex)) }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        colors.forEachIndexed { i, color ->
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(
                        width = if (i == selected) 3.dp else 1.dp,
                        color = if (i == selected) ctx.theme.colorScheme.onSurface else Color.Transparent
                    )
                    .minimumInteractiveComponentSize().clickable { selected = i }
            )
        }
    }
}

/** file_picker */
@Composable
fun RenderFilePicker(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val label = component.propStringResolved("label", ctx) ?: "Choose File"
    OutlinedButton(
        onClick = ctx.clickHandler(component) ?: {},
        modifier = modifier.fillMaxWidth()
    ) {
        Icon(imageVector = IconMapper.map("add"), contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(text = label)
    }
}

/** autocomplete */
@Composable
fun RenderAutocomplete(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val options = component.optionItems(ctx)
    val initial = component.propStringResolved("value", ctx).orEmpty()
    var text by remember { mutableStateOf(initial) }
    var expanded by remember { mutableStateOf(false) }
    val filtered = options.filter { it.contains(text, ignoreCase = true) }
    val color = accentColor(component, ctx)
    Box {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                expanded = it.isNotEmpty() && filtered.isNotEmpty()
            },
            modifier = modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(imageVector = Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (text.isNotEmpty()) {
                    IconButton(onClick = {
                        text = ""
                        expanded = false
                    }) {
                        Icon(imageVector = Icons.Filled.Clear, contentDescription = "Clear")
                    }
                }
            },
            colors = TextFieldDefaults.colors(
                focusedTextColor = ctx.theme.colorScheme.onSurface,
                unfocusedTextColor = ctx.theme.colorScheme.onSurface,
                focusedIndicatorColor = color,
                cursorColor = color
            )
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            filtered.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = { text = option; expanded = false }
                )
            }
        }
    }
}

/** otp_input */
@Composable
fun RenderOtpInput(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val length = component.propInt("length", 6).coerceAtLeast(1)
    val initial = component.propStringResolved("value", ctx).orEmpty()
    val textColor = ctx.theme.colorScheme.onSurface
    val borderColor = ctx.theme.colorScheme.outline
    val bgColor = ctx.theme.colorScheme.surfaceVariant
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        repeat(length) { i ->
            OtpCell(
                initial = initial.getOrNull(i)?.toString() ?: "",
                masked = false,
                textColor = textColor,
                borderColor = borderColor,
                bgColor = bgColor
            )
        }
    }
}

/** pin_input */
@Composable
fun RenderPinInput(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val length = component.propInt("length", 4).coerceAtLeast(1)
    val initial = component.propStringResolved("value", ctx).orEmpty()
    val textColor = ctx.theme.colorScheme.onSurface
    val borderColor = ctx.theme.colorScheme.outline
    val bgColor = ctx.theme.colorScheme.surfaceVariant
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        repeat(length) { i ->
            OtpCell(
                initial = initial.getOrNull(i)?.toString() ?: "",
                masked = true,
                textColor = textColor,
                borderColor = borderColor,
                bgColor = bgColor
            )
        }
    }
}

/** stepper_input */
@Composable
fun RenderStepperInput(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val min = component.propFloat("min", 0f)
    val max = component.propFloat("max", 100f)
    val step = component.propFloat("step", 1f)
    val initial = component.propFloat("value", min).coerceIn(min, max)
    var value by remember { mutableFloatStateOf(initial) }
    val color = accentColor(component, ctx)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        IconButton(onClick = { value = (value - step).coerceAtLeast(min) }) {
            Icon(imageVector = Icons.Filled.Remove, contentDescription = "Decrease", tint = color)
        }
        Text(
            text = value.toInt().toString(),
            color = ctx.theme.colorScheme.onSurface,
            style = ctx.theme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(48.dp)
        )
        IconButton(onClick = { value = (value + step).coerceAtMost(max) }) {
            Icon(imageVector = Icons.Filled.Add, contentDescription = "Increase", tint = color)
        }
    }
}

/** form */
@Composable
fun RenderForm(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val spacing = component.propFloat("spacing", 8f)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.dp)
    ) {
        RenderChildren(component.children, ctx)
    }
}

/** form_field */
@Composable
fun RenderFormField(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val label = component.propStringResolved("label", ctx)
    val helper = component.propStringResolved("helper", ctx)
    val error = component.propStringResolved("error", ctx)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (label != null) {
            Text(text = label, style = ctx.theme.typography.labelMedium)
        }
        RenderChildren(component.children, ctx)
        if (error != null) {
            Text(text = error, color = ctx.theme.colorScheme.error, style = ctx.theme.typography.bodySmall)
        } else if (helper != null) {
            Text(
                text = helper,
                color = ctx.theme.colorScheme.onSurfaceVariant,
                style = ctx.theme.typography.bodySmall
            )
        }
    }
}

/** form_group */
@Composable
fun RenderFormGroup(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val title = component.propStringResolved("title", ctx) ?: component.propStringResolved("label", ctx)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (title != null) {
            Text(text = title, style = ctx.theme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        RenderChildren(component.children, ctx)
    }
}

/** toggle_group */
@Composable
fun RenderToggleGroup(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val options = component.optionItems(ctx)
    val multiple = component.propBool("multiple", false)
    var single by remember { mutableIntStateOf(0) }
    var multi by remember { mutableStateOf(setOf(0)) }
    val color = accentColor(component, ctx)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEachIndexed { i, option ->
            val isSelected = if (multiple) i in multi else single == i
            FilterChip(
                selected = isSelected,
                onClick = {
                    if (multiple) multi = if (i in multi) multi - i else multi + i
                    else single = i
                },
                label = { Text(option) },
                colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                    selectedContainerColor = color.copy(alpha = 0.2f),
                    selectedLabelColor = color
                )
            )
        }
    }
}

/** segmented_control */
@Composable
fun RenderSegmentedControl(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val options = component.optionItems(ctx)
    var selected by remember { mutableIntStateOf(0) }
    val shape = StyleResolver.resolveShape(component.style)
    val color = accentColor(component, ctx)
    val outline = ctx.theme.colorScheme.outline
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, outline)
            .background(containerColor(component, ctx, ctx.theme.colorScheme.surfaceVariant))
    ) {
        options.forEachIndexed { i, option ->
            val isSelected = selected == i
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(if (isSelected) color else Color.Transparent)
                    .minimumInteractiveComponentSize().clickable { selected = i }
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = option,
                    color = if (isSelected) ctx.theme.colorScheme.onPrimary else ctx.theme.colorScheme.onSurfaceVariant,
                    style = ctx.theme.typography.labelMedium
                )
            }
        }
    }
}

/** search_bar */
@Composable
fun RenderSearchBar(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    BaseTextFieldRenderer(
        component = component,
        ctx = ctx,
        modifier = modifier,
        singleLine = true,
        leadingIconName = "search",
        shapeOverride = RoundedCornerShape(50)
    )
}

/** rating_input */
@Composable
fun RenderRatingInput(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val max = component.propInt("max", 5).coerceAtLeast(1)
    val initial = component.propInt("value", 0).coerceIn(0, max)
    var rating by remember { mutableIntStateOf(initial) }
    val starColor = accentColor(component, ctx)
    val starSize = component.style.textSize?.let { it.toInt().dp } ?: 32.dp
    Row(modifier = modifier) {
        StarsRow(
            value = rating,
            max = max,
            interactive = true,
            onRating = { rating = it },
            starColor = starColor,
            starSize = starSize
        )
    }
}

// =====================================================================================
// ============================ DISPLAY DOMAIN (29) ====================================
// =====================================================================================

/** image */
@Composable
fun RenderImage(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val bg = containerColor(component, ctx, ctx.theme.colorScheme.surfaceVariant)
    val shape = StyleResolver.resolveShape(component.style)
    val widthDp = component.sizeDp(96.dp, horizontal = true)
    val heightDp = component.sizeDp(96.dp, horizontal = false)
    Box(
        modifier = modifier
            .width(widthDp)
            .height(heightDp)
            .clip(shape)
            .background(bg)
    ) {
        val content = component.style.contentDescription
        if (content != null) {
            Text(
                text = content,
                color = ctx.theme.colorScheme.onSurfaceVariant,
                style = ctx.theme.typography.labelSmall,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

/** icon */
@Composable
fun RenderIcon(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val name = component.propString("name") ?: "info"
    val size = component.style.textSize?.let { it.toInt().dp } ?: 24.dp
    val tint = StyleResolver.resolveColor(
        component.style.textColor, ctx.theme.colorScheme, ctx.theme.colorScheme.onSurface
    )
    Icon(
        imageVector = IconMapper.map(name),
        contentDescription = component.style.contentDescription,
        modifier = modifier.size(size),
        tint = tint
    )
}

/** avatar */
@Composable
fun RenderAvatar(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val name = component.propStringResolved("name", ctx) ?: component.propStringResolved("label", ctx)
    val initials = name?.takeIf { it.isNotBlank() }
        ?.split(" ")
        ?.mapNotNull { it.firstOrNull() }
        ?.take(2)
        ?.joinToString("")
        ?: "?"
    val sizeDp = component.sizeDp(40.dp, horizontal = true)
    val bg = accentColor(component, ctx)
    Box(
        modifier = modifier.size(sizeDp).clip(CircleShape).background(bg),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initials.uppercase(),
            color = ctx.theme.colorScheme.onPrimary,
            style = ctx.theme.typography.titleMedium
        )
    }
}

/** badge */
@Composable
fun RenderBadge(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val text = component.propStringResolved("text", ctx) ?: ""
    val bg = containerColor(component, ctx, ctx.theme.colorScheme.error)
    val textColor = StyleResolver.resolveColor(
        component.style.textColor, ctx.theme.colorScheme, Color.White
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = textColor, style = ctx.theme.typography.labelSmall)
    }
}

/** chip */
@Composable
fun RenderChip(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val label = component.titleOrLabel(ctx)
    val bg = containerColor(component, ctx, ctx.theme.colorScheme.surfaceVariant)
    val textColor = StyleResolver.resolveColor(
        component.style.textColor, ctx.theme.colorScheme, ctx.theme.colorScheme.onSurfaceVariant
    )
    AssistChip(
        onClick = { ctx.clickHandler(component)?.invoke() },
        label = { Text(text = label, color = textColor) },
        modifier = modifier,
        colors = AssistChipDefaults.assistChipColors(containerColor = bg, labelColor = textColor)
    )
}

/** tag */
@Composable
fun RenderTag(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val label = component.titleOrLabel(ctx)
    val bg = containerColor(component, ctx, ctx.theme.colorScheme.surfaceVariant)
    val textColor = StyleResolver.resolveColor(
        component.style.textColor, ctx.theme.colorScheme, ctx.theme.colorScheme.onSurfaceVariant
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text = label, color = textColor, style = ctx.theme.typography.labelSmall)
    }
}

/** status_indicator */
@Composable
fun RenderStatusIndicator(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val colorName = component.propString("color")
    val color = StyleResolver.resolveColor(
        colorName, ctx.theme.colorScheme, accentColor(component, ctx)
    )
    val sizeDp = component.sizeDp(10.dp, horizontal = true)
    Box(modifier = modifier.size(sizeDp).clip(CircleShape).background(color))
}

/** progress */
@Composable
fun RenderProgress(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val progress = component.propFloat("value", -1f)
    val isCircular = component.propString("type") == "circular"
    val color = accentColor(component, ctx)
    val trackColor = containerColor(component, ctx, ctx.theme.colorScheme.surfaceVariant)
    if (isCircular) {
        val sizeDp = component.style.textSize?.let { it.toInt().dp } ?: 36.dp
        if (progress < 0f) {
            CircularProgressIndicator(modifier = modifier.size(sizeDp), color = color, strokeWidth = 3.dp)
        } else {
            CircularProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = modifier.size(sizeDp),
                color = color,
                strokeWidth = 3.dp,
                trackColor = trackColor
            )
        }
    } else {
        if (progress < 0f) {
            LinearProgressIndicator(modifier = modifier.fillMaxWidth(), color = color, trackColor = trackColor)
        } else {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = modifier.fillMaxWidth(),
                color = color,
                trackColor = trackColor
            )
        }
    }
}

/** circular_progress */
@Composable
fun RenderCircularProgress(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val progress = component.propFloat("value", -1f)
    val color = accentColor(component, ctx)
    val trackColor = containerColor(component, ctx, ctx.theme.colorScheme.surfaceVariant)
    val sizeDp = component.style.textSize?.let { it.toInt().dp } ?: 36.dp
    if (progress < 0f) {
        CircularProgressIndicator(modifier = modifier.size(sizeDp), color = color, strokeWidth = 3.dp)
    } else {
        CircularProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = modifier.size(sizeDp),
            color = color,
            strokeWidth = 3.dp,
            trackColor = trackColor
        )
    }
}

/** linear_progress */
@Composable
fun RenderLinearProgress(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val progress = component.propFloat("value", -1f)
    val color = accentColor(component, ctx)
    val trackColor = containerColor(component, ctx, ctx.theme.colorScheme.surfaceVariant)
    if (progress < 0f) {
        LinearProgressIndicator(modifier = modifier.fillMaxWidth(), color = color, trackColor = trackColor)
    } else {
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = modifier.fillMaxWidth(),
            color = color,
            trackColor = trackColor
        )
    }
}

/** skeleton */
@Composable
fun RenderSkeleton(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val baseColor = containerColor(component, ctx, ctx.theme.colorScheme.surfaceVariant)
    val shape = StyleResolver.resolveShape(component.style)
    val widthDp = component.sizeDp(120.dp, horizontal = true)
    val heightDp = component.sizeDp(16.dp, horizontal = false)
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "skeletonAlpha"
    )
    Box(
        modifier = modifier
            .width(widthDp)
            .height(heightDp)
            .clip(shape)
            .background(baseColor.copy(alpha = alpha))
    )
}

/** shimmer */
@Composable
fun RenderShimmer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val baseColor = containerColor(component, ctx, ctx.theme.colorScheme.surfaceVariant)
    val highlight = ctx.theme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
    val shape = StyleResolver.resolveShape(component.style)
    val widthDp = component.sizeDp(120.dp, horizontal = true)
    val heightDp = component.sizeDp(16.dp, horizontal = false)
    val transition = rememberInfiniteTransition(label = "shimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmerAlpha"
    )
    Box(
        modifier = modifier
            .width(widthDp)
            .height(heightDp)
            .clip(shape)
            .background(
                Brush.linearGradient(listOf(baseColor, highlight, baseColor))
            )
            .alpha(alpha)
    )
}

/** placeholder */
@Composable
fun RenderPlaceholder(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val text = component.propStringResolved("label", ctx) ?: "Placeholder"
    val shape = StyleResolver.resolveShape(component.style)
    val widthDp = component.sizeDp(120.dp, horizontal = true)
    val heightDp = component.sizeDp(48.dp, horizontal = false)
    Box(
        modifier = modifier
            .width(widthDp)
            .height(heightDp)
            .clip(shape)
            .border(1.dp, ctx.theme.colorScheme.outlineVariant)
            .background(containerColor(component, ctx, ctx.theme.colorScheme.surface)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = ctx.theme.colorScheme.onSurfaceVariant,
            style = ctx.theme.typography.bodySmall
        )
    }
}

/** empty_state */
@Composable
fun RenderEmptyState(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val title = component.propStringResolved("title", ctx) ?: "Nothing here"
    val message = component.propStringResolved("message", ctx) ?: component.propStringResolved("subtitle", ctx)
    val iconName = component.propString("icon") ?: "info"
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = IconMapper.map(iconName),
            contentDescription = null,
            tint = ctx.theme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp)
        )
        Text(text = title, style = ctx.theme.typography.titleMedium, color = ctx.theme.colorScheme.onSurface)
        if (message != null) {
            Text(
                text = message,
                style = ctx.theme.typography.bodyMedium,
                color = ctx.theme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** tooltip */
@Composable
fun RenderTooltip(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val text = component.titleOrLabel(ctx)
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = ctx.theme.colorScheme.surfaceContainer,
        shadowElevation = 2.dp,
        modifier = modifier
    ) {
        Text(
            text = text,
            color = ctx.theme.colorScheme.onSurface,
            style = ctx.theme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

/** rating */
@Composable
fun RenderRating(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val max = component.propInt("max", 5).coerceAtLeast(1)
    val value = component.propInt("value", 0).coerceIn(0, max)
    val starColor = accentColor(component, ctx)
    val starSize = component.style.textSize?.let { it.toInt().dp } ?: 24.dp
    Row(modifier = modifier) {
        StarsRow(value, max, interactive = false, onRating = {}, starColor, starSize)
    }
}

/** star_rating */
@Composable
fun RenderStarRating(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val max = component.propInt("max", 5).coerceAtLeast(1)
    val value = component.propFloat("value", 0f)
    val fullStars = value.toInt().coerceIn(0, max)
    val starColor = accentColor(component, ctx)
    val starSize = component.style.textSize?.let { it.toInt().dp } ?: 20.dp
    Row(modifier = modifier) {
        StarsRow(fullStars, max, interactive = false, onRating = {}, starColor, starSize)
    }
}

/** counter */
@Composable
fun RenderCounter(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val value = component.propStringResolved("value", ctx) ?: "0"
    val textColor = StyleResolver.resolveColor(
        component.style.textColor, ctx.theme.colorScheme, ctx.theme.colorScheme.onSurface
    )
    Text(
        text = value,
        color = textColor,
        style = ctx.theme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        modifier = modifier
    )
}

/** badge_count */
@Composable
fun RenderBadgeCount(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val count = component.propStringResolved("value", ctx)
        ?: component.propStringResolved("count", ctx)
        ?: "0"
    val bg = containerColor(component, ctx, ctx.theme.colorScheme.error)
    val textColor = StyleResolver.resolveColor(
        component.style.textColor, ctx.theme.colorScheme, Color.White
    )
    Box(
        modifier = modifier.size(20.dp).clip(CircleShape).background(bg),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = count,
            color = textColor,
            style = ctx.theme.typography.labelSmall,
            maxLines = 1
        )
    }
}

/** notification_badge */
@Composable
fun RenderNotificationBadge(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val iconName = component.propString("icon") ?: "notifications"
    val count = component.propStringResolved("count", ctx)
    val iconColor = StyleResolver.resolveColor(
        component.style.textColor, ctx.theme.colorScheme, ctx.theme.colorScheme.onSurfaceVariant
    )
    BadgedBox(
        badge = {
            if (count != null) {
                Badge { Text(text = count) }
            } else {
                Badge()
            }
        },
        modifier = modifier
    ) {
        Icon(imageVector = IconMapper.map(iconName), contentDescription = null, tint = iconColor)
    }
}

/** hero_image */
@Composable
fun RenderHeroImage(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val title = component.propStringResolved("title", ctx)
    val names = component.propStringList("colors")
    val gradientColors = names.ifEmpty { listOf("primary", "primaryContainer") }
        .map { StyleResolver.resolveColor(it, ctx.theme.colorScheme, ctx.theme.colorScheme.primary) }
    val heightDp = component.sizeDp(200.dp, horizontal = false)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(heightDp)
            .background(Brush.horizontalGradient(gradientColors)),
        contentAlignment = Alignment.BottomStart
    ) {
        if (title != null) {
            Text(
                text = title,
                color = ctx.theme.colorScheme.onPrimary,
                style = ctx.theme.typography.headlineSmall,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

/** thumbnail */
@Composable
fun RenderThumbnail(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val bg = containerColor(component, ctx, ctx.theme.colorScheme.surfaceVariant)
    val shape = StyleResolver.resolveShape(component.style)
    val sizeDp = component.sizeDp(56.dp, horizontal = true)
    Box(modifier = modifier.size(sizeDp).clip(shape).background(bg))
}

/** logo */
@Composable
fun RenderLogo(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val label = component.propStringResolved("label", ctx) ?: component.propStringResolved("text", ctx)
    val iconName = component.propString("icon")
    val bg = containerColor(component, ctx, ctx.theme.colorScheme.primary)
    val textColor = ctx.theme.colorScheme.onPrimary
    val sizeDp = component.sizeDp(40.dp, horizontal = true)
    Box(
        modifier = modifier.size(sizeDp).clip(RoundedCornerShape(6.dp)).background(bg),
        contentAlignment = Alignment.Center
    ) {
        if (label != null) {
            Text(text = label, color = textColor, style = ctx.theme.typography.titleMedium)
        } else {
            Icon(imageVector = IconMapper.map(iconName), contentDescription = null, tint = textColor)
        }
    }
}

/** illustration */
@Composable
fun RenderIllustration(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val bg = containerColor(component, ctx, ctx.theme.colorScheme.surfaceVariant)
    val shape = StyleResolver.resolveShape(component.style)
    val widthDp = component.sizeDp(120.dp, horizontal = true)
    val heightDp = component.sizeDp(120.dp, horizontal = false)
    Box(
        modifier = modifier.width(widthDp).height(heightDp).clip(shape).background(bg),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = IconMapper.map(component.propString("icon")),
            contentDescription = null,
            tint = ctx.theme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp)
        )
    }
}

/** gradient_box */
@Composable
fun RenderGradientBox(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val names = component.propStringList("colors")
    val gradientColors = names.ifEmpty { listOf("primary", "secondary") }
        .map { StyleResolver.resolveColor(it, ctx.theme.colorScheme, ctx.theme.colorScheme.primary) }
    val shape = StyleResolver.resolveShape(component.style)
    val widthDp = component.sizeDp(120.dp, horizontal = true)
    val heightDp = component.sizeDp(80.dp, horizontal = false)
    Box(
        modifier = modifier
            .width(widthDp)
            .height(heightDp)
            .clip(shape)
            .background(Brush.horizontalGradient(gradientColors))
    )
}

/** animated_box */
@Composable
fun RenderAnimatedBox(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val colorA = containerColor(component, ctx, ctx.theme.colorScheme.surfaceVariant)
    val colorB = accentColor(component, ctx)
    val shape = StyleResolver.resolveShape(component.style)
    val widthDp = component.sizeDp(120.dp, horizontal = true)
    val heightDp = component.sizeDp(80.dp, horizontal = false)
    val transition = rememberInfiniteTransition(label = "animatedBox")
    val fraction by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "animatedFraction"
    )
    Box(
        modifier = modifier
            .width(widthDp)
            .height(heightDp)
            .clip(shape)
            .background(lerp(colorA, colorB, fraction))
    )
}

/** particle_effect */
@Composable
fun RenderParticleEffect(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val color = accentColor(component, ctx)
    val transition = rememberInfiniteTransition(label = "particle")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "particlePhase"
    )
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(5) { i ->
            val alphaValue = (sin(phase + i * 1.2f) * 0.5f + 0.5f)
            Box(
                Modifier
                    .size(6.dp)
                    .alpha(alphaValue)
                    .background(color, CircleShape)
            )
        }
    }
}

/** pulse_indicator */
@Composable
fun RenderPulseIndicator(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val color = accentColor(component, ctx)
    val transition = rememberInfiniteTransition(label = "pulse")
    val scaleValue by transition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val alphaValue by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    Box(
        modifier = modifier
            .size(12.dp)
            .scale(scaleValue)
            .alpha(alphaValue)
            .background(color, CircleShape)
    )
}

/** wave_indicator */
@Composable
fun RenderWaveIndicator(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val color = accentColor(component, ctx)
    val count = component.propInt("bars", 4).coerceIn(2, 8)
    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhase"
    )
    Row(
        modifier = modifier.height(24.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(count) { i ->
            val fraction = (sin(phase + i * 0.8f) * 0.5f + 0.5f)
            Box(
                Modifier
                    .width(3.dp)
                    .height((6 + 16 * fraction).dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

// =====================================================================================
// ============================ NAVIGATION DOMAIN (30) ================================
// =====================================================================================

/** tabs */
@Composable
fun RenderTabs(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val tabs = component.optionItems(ctx)
    var selected by remember { mutableIntStateOf(component.propInt("selected", 0).coerceIn(0, tabs.lastIndex)) }
    val color = accentColor(component, ctx)
    TabRow(
        selectedTabIndex = selected.coerceIn(0, tabs.lastIndex),
        modifier = modifier,
        containerColor = containerColor(component, ctx, ctx.theme.colorScheme.surface),
        contentColor = color
    ) {
        tabs.forEachIndexed { i, title ->
            Tab(
                selected = selected == i,
                onClick = { selected = i },
                text = { Text(title) }
            )
        }
    }
}

/** tab_bar */
@Composable
fun RenderTabBar(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val tabs = component.optionItems(ctx)
    var selected by remember { mutableIntStateOf(component.propInt("selected", 0).coerceIn(0, tabs.lastIndex)) }
    val color = accentColor(component, ctx)
    ScrollableTabRow(
        selectedTabIndex = selected.coerceIn(0, tabs.lastIndex),
        modifier = modifier,
        containerColor = containerColor(component, ctx, ctx.theme.colorScheme.surface),
        contentColor = color
    ) {
        tabs.forEachIndexed { i, title ->
            Tab(
                selected = selected == i,
                onClick = { selected = i },
                text = { Text(title) }
            )
        }
    }
}

/** tab_item */
@Composable
fun RenderTabItem(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val label = component.titleOrLabel(ctx)
    val selected = component.propBool("selected", false)
    val color = accentColor(component, ctx)
    Tab(
        selected = selected,
        onClick = { ctx.clickHandler(component)?.invoke() },
        modifier = modifier,
        text = { Text(label) },
        selectedContentColor = color,
        unselectedContentColor = ctx.theme.colorScheme.onSurfaceVariant
    )
}

/** nav_bar */
@Composable
fun RenderNavBar(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val items = component.navItems(ctx)
    var selected by remember { mutableIntStateOf(component.propInt("selected", 0).coerceIn(0, items.lastIndex)) }
    val color = accentColor(component, ctx)
    NavigationBar(
        modifier = modifier,
        containerColor = containerColor(component, ctx, ctx.theme.colorScheme.surface),
        contentColor = color
    ) {
        items.forEachIndexed { i, (label, icon) ->
            NavigationBarItem(
                selected = selected == i,
                onClick = { selected = i },
                icon = { Icon(IconMapper.map(icon), contentDescription = label) },
                label = { Text(label) }
            )
        }
    }
}

/** nav_rail */
@Composable
fun RenderNavRail(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val items = component.navItems(ctx)
    var selected by remember { mutableIntStateOf(component.propInt("selected", 0).coerceIn(0, items.lastIndex)) }
    val color = accentColor(component, ctx)
    NavigationRail(
        modifier = modifier,
        containerColor = containerColor(component, ctx, ctx.theme.colorScheme.surface),
        contentColor = color
    ) {
        items.forEachIndexed { i, (label, icon) ->
            NavigationRailItem(
                selected = selected == i,
                onClick = { selected = i },
                icon = { Icon(IconMapper.map(icon), contentDescription = label) },
                label = { Text(label) }
            )
        }
    }
}

/** nav_drawer */
@Composable
fun RenderNavDrawer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val items = component.navItems(ctx)
    var selected by remember { mutableIntStateOf(component.propInt("selected", 0).coerceIn(0, items.lastIndex)) }
    val header = component.propStringResolved("header", ctx)
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(260.dp)
            .background(containerColor(component, ctx, ctx.theme.colorScheme.surface))
            .verticalScroll(rememberScrollState())
    ) {
        if (header != null) {
            Text(
                text = header,
                style = ctx.theme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(16.dp)
            )
            HorizontalDivider()
        }
        items.forEachIndexed { i, (label, icon) ->
            NavItemRow(
                label = label,
                iconName = icon,
                selected = selected == i,
                onClick = { selected = i },
                ctx = ctx
            )
        }
    }
}

/** bottom_nav */
@Composable
fun RenderBottomNav(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val items = component.navItems(ctx)
    var selected by remember { mutableIntStateOf(component.propInt("selected", 0).coerceIn(0, items.lastIndex)) }
    val color = accentColor(component, ctx)
    NavigationBar(
        modifier = modifier,
        containerColor = containerColor(component, ctx, ctx.theme.colorScheme.surface),
        contentColor = color
    ) {
        items.forEachIndexed { i, (label, icon) ->
            NavigationBarItem(
                selected = selected == i,
                onClick = { selected = i },
                icon = { Icon(IconMapper.map(icon), contentDescription = label) },
                label = { Text(label) }
            )
        }
    }
}

/** breadcrumb */
@Composable
fun RenderBreadcrumb(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val items = component.optionItems(ctx)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEachIndexed { i, item ->
            val isLast = i == items.lastIndex
            Text(
                text = item,
                color = if (isLast) ctx.theme.colorScheme.onSurface else accentColor(component, ctx),
                style = ctx.theme.typography.bodyMedium
            )
            if (!isLast) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = ctx.theme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** pagination */
@Composable
fun RenderPagination(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val total = component.propInt("total", 5).coerceAtLeast(1)
    var current by remember { mutableIntStateOf(component.propInt("current", 1).coerceIn(1, total)) }
    val color = accentColor(component, ctx)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = { if (current > 1) current-- }, enabled = current > 1) {
            Icon(imageVector = Icons.Filled.ArrowBack, contentDescription = "Previous", tint = color)
        }
        (1..total).forEach { page ->
            val isSelected = page == current
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) color else ctx.theme.colorScheme.surfaceVariant)
                    .minimumInteractiveComponentSize().clickable { current = page },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = page.toString(),
                    color = if (isSelected) ctx.theme.colorScheme.onPrimary else ctx.theme.colorScheme.onSurfaceVariant,
                    style = ctx.theme.typography.labelMedium
                )
            }
        }
        IconButton(onClick = { if (current < total) current++ }, enabled = current < total) {
            Icon(imageVector = Icons.Filled.ArrowForward, contentDescription = "Next", tint = color)
        }
    }
}

/** page_indicator */
@Composable
fun RenderPageIndicator(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val count = component.propInt("count", 3).coerceAtLeast(1)
    val current = component.propInt("current", 0).coerceIn(0, count - 1)
    val color = accentColor(component, ctx)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(count) { i ->
            Box(
                modifier = Modifier
                    .size(if (i == current) 8.dp else 6.dp)
                    .clip(CircleShape)
                    .background(if (i == current) color else ctx.theme.colorScheme.outlineVariant)
            )
        }
    }
}

/** stepper_nav */
@Composable
fun RenderStepperNav(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val count = component.propInt("count", component.propInt("steps", 3)).coerceAtLeast(1)
    val current = component.propInt("current", 0).coerceIn(0, count - 1)
    val color = accentColor(component, ctx)
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(count) { i ->
            val isDone = i < current
            val isCurrent = i == current
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(
                        if (isDone || isCurrent) color else ctx.theme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = (i + 1).toString(),
                    color = if (isDone || isCurrent) ctx.theme.colorScheme.onPrimary else ctx.theme.colorScheme.onSurfaceVariant,
                    style = ctx.theme.typography.labelSmall
                )
            }
            if (i < count - 1) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(2.dp)
                        .background(if (i < current) color else ctx.theme.colorScheme.outlineVariant)
                )
            }
        }
    }
}

/** link */
@Composable
fun RenderLink(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val text = component.titleOrLabel(ctx)
    val color = accentColor(component, ctx)
    Text(
        text = text,
        color = color,
        style = ctx.theme.typography.bodyMedium,
        textDecoration = TextDecoration.Underline,
        modifier = modifier.minimumInteractiveComponentSize().clickable { ctx.clickHandler(component)?.invoke() }
    )
}

/** anchor */
@Composable
fun RenderAnchor(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val text = component.titleOrLabel(ctx)
    val color = accentColor(component, ctx)
    Text(
        text = text,
        color = color,
        style = ctx.theme.typography.bodyMedium,
        textDecoration = TextDecoration.Underline,
        modifier = modifier.minimumInteractiveComponentSize().clickable { ctx.clickHandler(component)?.invoke() }
    )
}

/** menu */
@Composable
fun RenderMenu(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val items = component.navItems(ctx)
    Column(
        modifier = modifier
            .background(containerColor(component, ctx, ctx.theme.colorScheme.surface))
            .padding(vertical = 4.dp)
    ) {
        items.forEachIndexed { i, (label, icon) ->
            NavItemRow(
                label = label,
                iconName = icon,
                selected = false,
                onClick = { ctx.clickHandler(component)?.invoke() },
                ctx = ctx
            )
        }
    }
}

/** dropdown_menu */
@Composable
fun RenderDropdownMenu(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val items = component.navItems(ctx)
    val label = component.propStringResolved("label", ctx) ?: "Menu"
    var expanded by remember { mutableStateOf(false) }
    val color = accentColor(component, ctx)
    Box {
        Row(
            modifier = modifier
                .minimumInteractiveComponentSize().clickable { expanded = true }
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = label, color = ctx.theme.colorScheme.onSurface)
            Icon(imageVector = Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = color)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEach { (itemLabel, icon) ->
                DropdownMenuItem(
                    text = { Text(itemLabel) },
                    onClick = { expanded = false; ctx.clickHandler(component)?.invoke() },
                    leadingIcon = icon?.let { { Icon(IconMapper.map(it), contentDescription = null) } }
                )
            }
        }
    }
}

/** context_menu */
@Composable
fun RenderContextMenu(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val items = component.optionItems(ctx)
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = ctx.theme.colorScheme.surfaceContainer,
        shadowElevation = 4.dp,
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            items.forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize().clickable { ctx.clickHandler(component)?.invoke() }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = item, color = ctx.theme.colorScheme.onSurface)
                }
            }
        }
    }
}

/** popup_menu */
@Composable
fun RenderPopupMenu(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val items = component.navItems(ctx)
    val label = component.propStringResolved("label", ctx) ?: "Options"
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = modifier) {
            Icon(imageVector = Icons.Filled.MoreVert, contentDescription = label)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEach { (itemLabel, icon) ->
                DropdownMenuItem(
                    text = { Text(itemLabel) },
                    onClick = { expanded = false; ctx.clickHandler(component)?.invoke() },
                    leadingIcon = icon?.let { { Icon(IconMapper.map(it), contentDescription = null) } }
                )
            }
        }
    }
}

/** side_menu */
@Composable
fun RenderSideMenu(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val items = component.navItems(ctx)
    var selected by remember { mutableIntStateOf(0) }
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(240.dp)
            .background(containerColor(component, ctx, ctx.theme.colorScheme.surface))
            .verticalScroll(rememberScrollState())
    ) {
        items.forEachIndexed { i, (label, icon) ->
            NavItemRow(
                label = label,
                iconName = icon,
                selected = selected == i,
                onClick = { selected = i },
                ctx = ctx
            )
        }
    }
}

/** hamburger_menu */
@Composable
fun RenderHamburgerMenu(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    IconButton(onClick = ctx.clickHandler(component) ?: {}, modifier = modifier) {
        Icon(imageVector = Icons.Filled.Menu, contentDescription = "Menu")
    }
}

/** tab_view */
@Composable
fun RenderTabView(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val tabs = component.children.map { it.titleOrLabel(ctx) }.ifEmpty { component.optionItems(ctx) }
    var selected by remember { mutableIntStateOf(0) }
    val color = accentColor(component, ctx)
    Column(modifier = modifier.fillMaxWidth()) {
        TabRow(
            selectedTabIndex = selected.coerceIn(0, tabs.lastIndex),
            contentColor = color
        ) {
            tabs.forEachIndexed { i, title ->
                Tab(
                    selected = selected == i,
                    onClick = { selected = i },
                    text = { Text(title) }
                )
            }
        }
        if (component.children.isNotEmpty()) {
            val active = component.children.getOrNull(selected.coerceIn(0, component.children.lastIndex))
            if (active != null) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    RenderChildren(listOf(active), ctx)
                }
            }
        }
    }
}

/** page_view */
@Composable
fun RenderPageView(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    var current by remember { mutableIntStateOf(component.propInt("current", 0)) }
    val pages = component.children
    val active = pages.getOrNull(current.coerceIn(0, pages.lastIndex))
    Box(modifier = modifier.fillMaxWidth()) {
        if (active != null) {
            RenderChildren(listOf(active), ctx)
        }
    }
}

/** carousel_nav */
@Composable
fun RenderCarouselNav(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val count = component.propInt("count", 3).coerceAtLeast(1)
    var current by remember { mutableIntStateOf(component.propInt("current", 0).coerceIn(0, count - 1)) }
    val color = accentColor(component, ctx)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = { if (current > 0) current-- }, enabled = current > 0) {
            Icon(imageVector = Icons.Filled.ArrowBack, contentDescription = "Previous", tint = color)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(count) { i ->
                Box(
                    modifier = Modifier
                        .size(if (i == current) 8.dp else 6.dp)
                        .clip(CircleShape)
                        .background(if (i == current) color else ctx.theme.colorScheme.outlineVariant)
                )
            }
        }
        IconButton(onClick = { if (current < count - 1) current++ }, enabled = current < count - 1) {
            Icon(imageVector = Icons.Filled.ArrowForward, contentDescription = "Next", tint = color)
        }
    }
}

/** arrow_nav */
@Composable
fun RenderArrowNav(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val color = accentColor(component, ctx)
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { ctx.clickHandler(component)?.invoke() }) {
            Icon(imageVector = Icons.Filled.ArrowBack, contentDescription = "Back", tint = color)
        }
        IconButton(onClick = { ctx.clickHandler(component)?.invoke() }) {
            Icon(imageVector = Icons.Filled.ArrowForward, contentDescription = "Forward", tint = color)
        }
    }
}

/** back_button */
@Composable
fun RenderBackButton(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    IconButton(onClick = ctx.clickHandler(component) ?: {}, modifier = modifier) {
        Icon(imageVector = Icons.Filled.ArrowBack, contentDescription = "Back")
    }
}

/** forward_button */
@Composable
fun RenderForwardButton(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    IconButton(onClick = ctx.clickHandler(component) ?: {}, modifier = modifier) {
        Icon(imageVector = Icons.Filled.ArrowForward, contentDescription = "Forward")
    }
}

/** up_button */
@Composable
fun RenderUpButton(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    IconButton(onClick = ctx.clickHandler(component) ?: {}, modifier = modifier) {
        Icon(imageVector = Icons.Filled.ArrowUpward, contentDescription = "Up")
    }
}

/** menu_button */
@Composable
fun RenderMenuButton(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    IconButton(onClick = ctx.clickHandler(component) ?: {}, modifier = modifier) {
        Icon(imageVector = Icons.Filled.Menu, contentDescription = "Menu")
    }
}

/** expand_button */
@Composable
fun RenderExpandButton(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    IconButton(onClick = ctx.clickHandler(component) ?: {}, modifier = modifier) {
        Icon(imageVector = Icons.Filled.ExpandMore, contentDescription = "Expand")
    }
}

/** collapse_button */
@Composable
fun RenderCollapseButton(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    IconButton(onClick = ctx.clickHandler(component) ?: {}, modifier = modifier) {
        Icon(imageVector = Icons.Filled.ExpandLess, contentDescription = "Collapse")
    }
}

/** nav_item */
@Composable
fun RenderNavItem(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val label = component.titleOrLabel(ctx)
    val selected = component.propBool("selected", false)
    NavItemRow(
        label = label,
        iconName = component.propString("icon"),
        selected = selected,
        onClick = { ctx.clickHandler(component)?.invoke() },
        ctx = ctx,
        modifier = modifier
    )
}
