package com.ai.assistance.quro.genui.sdk.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ai.assistance.quro.genui.sdk.dsl.Dimension
import com.ai.assistance.quro.genui.sdk.dsl.UIComponent
import com.ai.assistance.quro.genui.sdk.render.RenderContext
import com.ai.assistance.quro.genui.sdk.render.StyleResolver

// ==================== Divider ====================

@Composable
fun DividerRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
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

// ==================== Icon ====================

@Composable
fun IconRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val iconName = component.propString("name") ?: "info"
    val iconSize = component.style.textSize?.let { it.toInt() }?.dp ?: 24.dp
    val tint = StyleResolver.resolveColor(
        component.style.textColor,
        ctx.theme.colorScheme,
        ctx.theme.colorScheme.onSurface
    )
    val imageVector = IconMapper.map(iconName)
    Icon(
        imageVector = imageVector,
        contentDescription = component.propString("contentDescription"),
        modifier = modifier.size(iconSize),
        tint = tint
    )
}

// ==================== Progress ====================

@Composable
fun ProgressRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    var progress = component.propFloat("value", -1f)
    // 兼容 "75%" 字符串与 0-100 写法（>1 视为百分制，归一到 0-1）
    if (progress < 0f) {
        component.propString("value")?.trim()?.removeSuffix("%")?.toFloatOrNull()?.let { progress = it }
    }
    if (progress > 1f) progress = (progress / 100f).coerceIn(0f, 1f)
    val isCircular = component.propString("type") == "circular"
    val color = StyleResolver.resolveColor(
        component.style.textColor,
        ctx.theme.colorScheme,
        ctx.theme.colorScheme.primary
    )
    val trackColor = StyleResolver.resolveColor(
        component.style.backgroundColor,
        ctx.theme.colorScheme,
        ctx.theme.colorScheme.surfaceVariant
    )

    if (isCircular) {
        val size = component.style.textSize?.let { it.toInt() }?.dp ?: 36.dp
        if (progress < 0f) {
            CircularProgressIndicator(
                modifier = modifier.size(size),
                color = color,
                strokeWidth = 3.dp
            )
        } else {
            CircularProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = modifier.size(size),
                color = color,
                strokeWidth = 3.dp,
                trackColor = trackColor
            )
        }
    } else {
        if (progress < 0f) {
            LinearProgressIndicator(
                modifier = modifier.fillMaxWidth(),
                color = color,
                trackColor = trackColor
            )
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

// ==================== Slider ====================

@Composable
fun SliderRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val value = component.propFloat("value", 0f)
    val min = component.propFloat("min", 0f)
    val max = component.propFloat("max", 1f)
    val steps = component.propInt("steps", 0)
    val enabled = component.propBool("enabled", true)
    val color = StyleResolver.resolveColor(
        component.style.textColor,
        ctx.theme.colorScheme,
        ctx.theme.colorScheme.primary
    )

    var sliderValue by remember { mutableFloatStateOf(value.coerceIn(min, max)) }

    Slider(
        value = sliderValue,
        onValueChange = {
            sliderValue = it
            com.ai.assistance.quro.genui.sdk.interaction.FormStateBus.set(component.id, it.toString())
        },
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

// ==================== Switch ====================

@Composable
fun SwitchRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val checked = component.propBool("value", false)
    LaunchedEffect(component.id) {
        com.ai.assistance.quro.genui.sdk.interaction.FormStateBus.set(component.id, checked.toString())
    }
    val enabled = component.propBool("enabled", true)
    val thumbColor = StyleResolver.resolveColor(
        component.style.textColor,
        ctx.theme.colorScheme,
        ctx.theme.colorScheme.primary
    )

    var state by remember { mutableStateOf(checked) }

    Switch(
        checked = state,
        onCheckedChange = { nv ->
            state = nv
            com.ai.assistance.quro.genui.sdk.interaction.FormStateBus.set(component.id, nv.toString())
        },
        enabled = enabled,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = thumbColor,
            uncheckedThumbColor = ctx.theme.colorScheme.outline,
            uncheckedTrackColor = ctx.theme.colorScheme.surfaceVariant
        )
    )
}

// ==================== Checkbox ====================

@Composable
fun CheckboxRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val checked = component.propBool("value", false)
    val enabled = component.propBool("enabled", true)
    val color = StyleResolver.resolveColor(
        component.style.textColor,
        ctx.theme.colorScheme,
        ctx.theme.colorScheme.primary
    )

    var state by remember { mutableStateOf(checked) }

    Checkbox(
        checked = state,
        onCheckedChange = { state = it },
        enabled = enabled,
        modifier = modifier,
        colors = CheckboxDefaults.colors(
            checkedColor = color,
            uncheckedColor = ctx.theme.colorScheme.outline
        )
    )
}

// ==================== Chip ====================

@Composable
fun ChipRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val label = component.propStringResolved("label", ctx) ?: component.propStringResolved("text", ctx) ?: ""
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
        onClick = {},
        label = { Text(text = label, color = textColor) },
        modifier = modifier,
        colors = AssistChipDefaults.assistChipColors(
            containerColor = backgroundColor,
            labelColor = textColor
        )
    )
}

// ==================== Badge ====================

@Composable
fun BadgeRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val text = component.propStringResolved("text", ctx)
        ?: component.children.firstNotNullOfOrNull { it.propString("text") }
        ?: component.propString("label") ?: ""
    val backgroundColor = StyleResolver.resolveColor(
        component.style.backgroundColor,
        ctx.theme.colorScheme,
        ctx.theme.colorScheme.error
    )
    val textColor = StyleResolver.resolveColor(
        component.style.textColor,
        ctx.theme.colorScheme,
        Color.White
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(backgroundColor)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = textColor,
            style = ctx.theme.typography.labelSmall
        )
    }
}
