package com.ai.assistance.quro.genui.sdk.components

import androidx.compose.material3.minimumInteractiveComponentSize

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ai.assistance.quro.genui.sdk.dsl.UIComponent
import com.ai.assistance.quro.genui.sdk.render.RenderChildren
import com.ai.assistance.quro.genui.sdk.render.RenderContext
import com.ai.assistance.quro.genui.sdk.render.StyleResolver
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull

/**
 * Feedback / Data / Media / Surface renderer collection.
 *
 * This file provides @Composable renderer functions for four component domains:
 *  - Feedback (29 types)
 *  - Data (29 types)
 *  - Media (29 types)
 *  - Surface (30 types)
 *
 * Each public renderer follows the signature:
 *   `fun XxxRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier)`
 *
 * NOTE: The `dialog`, `card` and `list` types already have renderers elsewhere in this
 * package (`DialogRenderer`, `CardRenderer`, `ListRenderer`). To keep this file
 * self-contained and avoid redeclaration conflicts, those three are exposed here under
 * the `Gen`-prefixed names: [GenDialogRenderer], [GenCardRenderer], [GenListRenderer].
 */
@Suppress("unused")
object FeedbackDataMediaSurfaceRenderersMarker

// =====================================================================================
// ================================= SHARED HELPERS ====================================
// =====================================================================================

/** Resolve a JsonElement into its raw string content (used for table cells / key-values). */
private fun jsonElementToString(element: JsonElement?): String {
    return (element as? JsonPrimitive)?.contentOrNull ?: element?.toString() ?: ""
}

/** Read a JsonArray of numbers from [key] as a list of Floats (used for charts). */
private fun UIComponent.propFloatList(key: String): List<Float> {
    val value = properties[key]
    val array = value as? JsonArray ?: return emptyList()
    return array.mapNotNull { (it as? JsonPrimitive)?.floatOrNull }
}

/** Small holder for feedback visuals (icon + container/content colors). */
private data class FeedbackVisuals(
    val icon: ImageVector,
    val containerColor: Color,
    val contentColor: Color
)

/** Pick an icon and color pair for a feedback [level]. */
private fun feedbackVisuals(level: String, ctx: RenderContext): FeedbackVisuals {
    val scheme = ctx.theme.colorScheme
    return when (level.lowercase()) {
        "success" -> FeedbackVisuals(
            Icons.Filled.Check,
            scheme.successContainer,
            scheme.success
        )
        "warning" -> FeedbackVisuals(
            Icons.Filled.Warning,
            scheme.warningContainer,
            scheme.primary
        )
        "error", "alert" -> FeedbackVisuals(
            Icons.Filled.Error,
            scheme.error.copy(alpha = 0.16f),
            scheme.error
        )
        "info" -> FeedbackVisuals(
            Icons.Filled.Info,
            scheme.primary.copy(alpha = 0.12f),
            scheme.primary
        )
        else -> FeedbackVisuals(
            Icons.Filled.Info,
            scheme.surfaceVariant,
            scheme.onSurfaceVariant
        )
    }
}

/** A reusable colored message box used by snackbar/toast/banner/alert/etc. */
@Composable
private fun FeedbackMessageRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier,
    level: String = "neutral",
    defaultTitle: String? = null,
    accentBar: Boolean = false,
    compact: Boolean = false
) {
    val visuals = feedbackVisuals(level, ctx)
    val title = component.propStringResolved("title", ctx) ?: defaultTitle
    val message = component.propStringResolved("message", ctx)
        ?: component.propStringResolved("text", ctx)
        ?: component.propStringResolved("body", ctx)
    val dismissible = component.propBool("dismissible", false)
    val pad = if (compact) 8.dp else 12.dp
    val shape = RoundedCornerShape(6.dp)
    val containerColor = visuals.containerColor
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(containerColor, shape)
            .padding(pad),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (accentBar) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(visuals.contentColor)
            )
            Spacer(Modifier.width(pad))
        }
        Icon(
            imageVector = visuals.icon,
            contentDescription = level,
            tint = visuals.contentColor,
            modifier = Modifier.size(if (compact) 18.dp else 24.dp)
        )
        Spacer(Modifier.width(if (compact) 8.dp else 12.dp))
        Column(modifier = Modifier.weight(1f)) {
            if (title != null) {
                Text(
                    text = title,
                    color = visuals.contentColor,
                    style = ctx.theme.typography.titleSmall
                )
            }
            if (message != null) {
                if (title != null) Spacer(Modifier.height(2.dp))
                Text(
                    text = message,
                    color = visuals.contentColor,
                    style = if (compact) ctx.theme.typography.bodySmall else ctx.theme.typography.bodyMedium
                )
            }
            RenderChildren(component.children, ctx)
        }
        if (dismissible) {
            Spacer(Modifier.width(8.dp))
            val dismissHandler = ctx.clickHandler(component, "onDismiss")
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Dismiss",
                tint = visuals.contentColor,
                modifier = Modifier
                    .size(if (compact) 16.dp else 20.dp)
                    .minimumInteractiveComponentSize().clickable { dismissHandler?.invoke() }
            )
        }
    }
}

/** A dialog-shaped Surface (not a window-level Dialog) used by dialog-like types. */
@Composable
private fun DialogSurfaceRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier,
    level: String = "neutral",
    footer: (@Composable () -> Unit)? = null
) {
    val scheme = ctx.theme.colorScheme
    val title = component.propStringResolved("title", ctx)
    val message = component.propStringResolved("message", ctx)
        ?: component.propStringResolved("content", ctx)
    val visuals = feedbackVisuals(level, ctx)
    val showAccent = level != "neutral"
    val shape = StyleResolver.resolveShape(component.style).let { s ->
        if (s == RectangleShape) RoundedCornerShape(20.dp) else s
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
        Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
            if (title != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = visuals.icon,
                        contentDescription = level,
                        tint = if (showAccent) visuals.contentColor else onSurface,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = title,
                        style = ctx.theme.typography.headlineSmall,
                        color = if (showAccent) visuals.contentColor else onSurface
                    )
                }
            }
            if (message != null) {
                if (title != null) Spacer(Modifier.height(12.dp))
                Text(
                    text = message,
                    style = ctx.theme.typography.bodyMedium,
                    color = onSurface
                )
            }
            if (component.children.isNotEmpty()) {
                if (title != null || message != null) Spacer(Modifier.height(16.dp))
                RenderChildren(component.children, ctx)
            }
            if (footer != null) {
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    footer()
                }
            }
        }
    }
}

/** A small Material3 action button used inside dialog footers. */
@Composable
private fun DialogActionButton(
    label: String,
    ctx: RenderContext,
    component: UIComponent,
    eventName: String
) {
    val handler = ctx.clickHandler(component, eventName)
    TextButton(onClick = { handler?.invoke() }) {
        Text(text = label)
    }
}

/** Centered empty-state used by error_state/no_data/no_result. */
@Composable
private fun StateRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier,
    level: String = "neutral",
    defaultTitle: String? = null,
    defaultMessage: String? = null
) {
    val scheme = ctx.theme.colorScheme
    val visuals = feedbackVisuals(level, ctx)
    val title = component.propStringResolved("title", ctx) ?: defaultTitle
    val message = component.propStringResolved("message", ctx)
        ?: component.propStringResolved("description", ctx)
        ?: defaultMessage
    val retryable = component.propBool("retryable", level == "error")
    val retryHandler = ctx.clickHandler(component, "onRetry")
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = visuals.icon,
            contentDescription = level,
            tint = visuals.contentColor,
            modifier = Modifier.size(56.dp)
        )
        if (title != null) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = title,
                style = ctx.theme.typography.titleMedium,
                color = scheme.onSurface
            )
        }
        if (message != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                style = ctx.theme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        if (retryable) {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(visuals.containerColor)
                    .minimumInteractiveComponentSize().clickable { retryHandler?.invoke() }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Retry",
                    tint = visuals.contentColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = component.propStringResolved("retryText", ctx) ?: "Retry",
                    color = visuals.contentColor,
                    style = ctx.theme.typography.labelLarge
                )
            }
        }
        RenderChildren(component.children, ctx)
    }
}

/** A scrim + centered progress indicator used by loading_overlay. */
@Composable
private fun LoadingScrimRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val scheme = ctx.theme.colorScheme
    val message = component.propStringResolved("message", ctx) ?: component.propStringResolved("text", ctx)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(scheme.scrim.copy(alpha = 0.4f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = scheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = scheme.primary)
                if (message != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = message,
                        style = ctx.theme.typography.bodyMedium,
                        color = scheme.onSurface
                    )
                }
            }
        }
    }
}

/** A generic media placeholder box (no real playback). */
@Composable
private fun MediaPlaceholderRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier,
    icon: ImageVector,
    defaultLabel: String,
    height: Dp = 200.dp
) {
    val scheme = ctx.theme.colorScheme
    val bg = StyleResolver.resolveColor(
        component.style.backgroundColor, scheme, scheme.surfaceVariant
    )
    val tint = StyleResolver.resolveColor(
        component.style.textColor, scheme, scheme.onSurfaceVariant
    )
    val label = component.propStringResolved("label", ctx)
        ?: component.propStringResolved("title", ctx)
        ?: defaultLabel
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(6.dp))
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(imageVector = icon, contentDescription = defaultLabel, tint = tint, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(8.dp))
            Text(text = label, color = tint, style = ctx.theme.typography.bodyMedium)
        }
    }
}

/** A grid of media placeholder cells. */
@Composable
private fun MediaGridBaseRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier,
    icon: ImageVector,
    defaultLabel: String,
    cellHeight: Dp = 120.dp
) {
    val scheme = ctx.theme.colorScheme
    val count = component.propInt("count", 6).coerceAtLeast(1)
    val columns = component.propInt("columns", 3).coerceIn(1, 8)
    val bg = StyleResolver.resolveColor(
        component.style.backgroundColor, scheme, scheme.surfaceVariant
    )
    val tint = StyleResolver.resolveColor(
        component.style.textColor, scheme, scheme.onSurfaceVariant
    )
    val rows = (1..count).toList().chunked(columns)
    Column(modifier.fillMaxWidth()) {
        rows.forEachIndexed { index, rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowItems.forEach {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(cellHeight)
                            .clip(RoundedCornerShape(6.dp))
                            .background(bg),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(imageVector = icon, contentDescription = defaultLabel, tint = tint, modifier = Modifier.size(28.dp))
                            Spacer(Modifier.height(4.dp))
                            Text(text = defaultLabel, color = tint, style = ctx.theme.typography.labelSmall)
                        }
                    }
                }
                repeat(columns - rowItems.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
            if (index < rows.lastIndex) {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

/** A vertical list of media rows (icon thumbnail + title + subtitle). */
@Composable
private fun MediaListBaseRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier,
    icon: ImageVector,
    defaultLabel: String
) {
    val scheme = ctx.theme.colorScheme
    val count = component.propInt("count", 4).coerceAtLeast(1)
    val bg = scheme.surfaceVariant
    val tint = scheme.onSurfaceVariant
    Column(modifier.fillMaxWidth()) {
        repeat(count) { i ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(bg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = component.propStringResolved("title", ctx) ?: "$defaultLabel ${i + 1}",
                        style = ctx.theme.typography.titleSmall,
                        color = scheme.onSurface
                    )
                    Text(
                        text = component.propStringResolved("subtitle", ctx) ?: "00:00",
                        style = ctx.theme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                }
            }
            if (i < count - 1) {
                HorizontalDivider(color = scheme.outlineVariant)
            }
        }
    }
}

// =====================================================================================
// =============================== CHART CANVAS HELPERS ================================
// =====================================================================================

private fun chartPalette(ctx: RenderContext): List<Color> {
    val s = ctx.theme.colorScheme
    return listOf(s.primary, s.secondary, s.tertiary, s.error, s.primary.copy(alpha = 0.5f), s.secondary.copy(alpha = 0.5f))
}

@Composable
private fun BarChartCanvas(
    data: List<Float>,
    modifier: Modifier,
    barColor: Color,
    trackColor: Color
) {
    Canvas(modifier) {
        if (data.isEmpty()) return@Canvas
        val max = data.maxOrNull()?.takeIf { it > 0f } ?: 1f
        val n = data.size
        val slot = size.width / n
        val barW = slot * 0.6f
        val gap = slot * 0.4f
        drawLine(
            color = trackColor,
            start = Offset(0f, size.height),
            end = Offset(size.width, size.height),
            strokeWidth = 2f
        )
        data.forEachIndexed { i, v ->
            val ratio = (v / max).coerceIn(0f, 1f)
            val h = ratio * size.height
            drawRect(
                color = if (v <= 0f) trackColor else barColor,
                topLeft = Offset(i * slot + gap / 2f, size.height - h),
                size = Size(barW, h)
            )
        }
    }
}

@Composable
private fun LineChartCanvas(
    data: List<Float>,
    modifier: Modifier,
    lineColor: Color,
    trackColor: Color
) {
    Canvas(modifier) {
        val n = data.size
        if (n < 1) return@Canvas
        val max = data.maxOrNull() ?: 0f
        val min = data.minOrNull() ?: 0f
        val range = (max - min).let { if (it > 0.001f) it else 1f }
        val stepX = if (n > 1) size.width / (n - 1) else 0f
        fun pointY(v: Float): Float = size.height - ((v - min) / range) * size.height
        drawLine(trackColor, Offset(0f, size.height), Offset(size.width, size.height), 2f)
        if (n == 1) {
            drawCircle(lineColor, 5f, Offset(0f, pointY(data[0])))
            return@Canvas
        }
        for (i in 1 until n) {
            drawLine(
                color = lineColor,
                start = Offset((i - 1) * stepX, pointY(data[i - 1])),
                end = Offset(i * stepX, pointY(data[i])),
                strokeWidth = 4f
            )
        }
        data.forEachIndexed { i, v ->
            drawCircle(lineColor, 4f, Offset(i * stepX, pointY(v)))
        }
    }
}

@Composable
private fun PieChartCanvas(
    data: List<Float>,
    modifier: Modifier,
    colors: List<Color>,
    trackColor: Color
) {
    Canvas(modifier) {
        val total = data.sum()
        val side = size.minDimension
        val topLeft = Offset((size.width - side) / 2f, (size.height - side) / 2f)
        if (total <= 0f) {
            drawCircle(trackColor, side / 2f, center = topLeft + Offset(side / 2f, side / 2f))
            return@Canvas
        }
        var start = -90f
        data.forEachIndexed { i, v ->
            val sweep = (v / total) * 360f
            drawArc(
                color = colors[i % colors.size],
                startAngle = start,
                sweepAngle = sweep,
                useCenter = true,
                topLeft = topLeft,
                size = Size(side, side)
            )
            start += sweep
        }
    }
}

// =====================================================================================
// ================================== FEEDBACK DOMAIN ==================================
// =====================================================================================

/** dialog (renamed to avoid clashing with the existing [DialogRenderer]). */
@Composable
fun GenDialogRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val level = component.propString("level") ?: "neutral"
    DialogSurfaceRenderer(component, ctx, modifier, level = level)
}

@Composable
fun AlertDialogRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    DialogSurfaceRenderer(component, ctx, modifier, level = "error") {
        DialogActionButton("Dismiss", ctx, component, "onDismiss")
    }
}

@Composable
fun ModalRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val scheme = ctx.theme.colorScheme
    Box(
        modifier = modifier.fillMaxWidth().background(scheme.scrim.copy(alpha = 0.5f)).padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        DialogSurfaceRenderer(component, ctx, Modifier, level = "neutral")
    }
}

@Composable
fun SnackbarRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val level = component.propString("level") ?: "neutral"
    FeedbackMessageRenderer(component, ctx, modifier, level = level, compact = false)
}

@Composable
fun ToastRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val level = component.propString("level") ?: "info"
    FeedbackMessageRenderer(component, ctx, modifier, level = level, compact = true)
}

@Composable
fun BannerRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val level = component.propString("level") ?: "info"
    FeedbackMessageRenderer(component, ctx, modifier, level = level, accentBar = true)
}

@Composable
fun AlertRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    FeedbackMessageRenderer(component, ctx, modifier, level = "error", accentBar = true)
}

@Composable
fun WarningRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    FeedbackMessageRenderer(component, ctx, modifier, level = "warning", accentBar = true)
}

@Composable
fun ErrorDisplayRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    FeedbackMessageRenderer(component, ctx, modifier, level = "error")
}

@Composable
fun SuccessMessageRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    FeedbackMessageRenderer(component, ctx, modifier, level = "success")
}

@Composable
fun InfoBannerRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    FeedbackMessageRenderer(component, ctx, modifier, level = "info", accentBar = true)
}

@Composable
fun ProgressDialogRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val scheme = ctx.theme.colorScheme
    val title = component.propStringResolved("title", ctx) ?: "Loading"
    val progress = component.propFloat("progress", -1f)
    val shape = RoundedCornerShape(14.dp)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = scheme.surfaceContainer,
        tonalElevation = 8.dp,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (progress < 0f) {
                CircularProgressIndicator(color = scheme.primary)
            } else {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = scheme.primary,
                    trackColor = scheme.surfaceVariant
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(text = title, style = ctx.theme.typography.titleSmall, color = scheme.onSurface)
            RenderChildren(component.children, ctx)
        }
    }
}

@Composable
fun LoadingOverlayRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    LoadingScrimRenderer(component, ctx, modifier)
}

@Composable
fun ErrorStateRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    StateRenderer(
        component, ctx, modifier,
        level = "error",
        defaultTitle = "Something went wrong",
        defaultMessage = "An unexpected error occurred."
    )
}

@Composable
fun NoDataRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    StateRenderer(
        component, ctx, modifier,
        level = "neutral",
        defaultTitle = "No data",
        defaultMessage = "There is nothing to show yet."
    )
}

@Composable
fun NoResultRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    StateRenderer(
        component, ctx, modifier,
        level = "info",
        defaultTitle = "No results found",
        defaultMessage = "Try adjusting your search or filters."
    )
}

@Composable
fun ConfirmationDialogRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    DialogSurfaceRenderer(component, ctx, modifier, level = "neutral") {
        DialogActionButton("Cancel", ctx, component, "onCancel")
        Spacer(Modifier.width(8.dp))
        DialogActionButton("Confirm", ctx, component, "onConfirm")
    }
}

@Composable
fun ActionDialogRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val actions = component.propStringList("actions")
    DialogSurfaceRenderer(component, ctx, modifier, level = "neutral") {
        if (actions.isEmpty()) {
            DialogActionButton("OK", ctx, component, "onConfirm")
        } else {
            actions.forEachIndexed { index, label ->
                if (index > 0) Spacer(Modifier.width(8.dp))
                DialogActionButton(label, ctx, component, "onClick")
            }
        }
    }
}

@Composable
fun InfoDialogRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    DialogSurfaceRenderer(component, ctx, modifier, level = "info") {
        DialogActionButton("OK", ctx, component, "onConfirm")
    }
}

@Composable
fun WarningDialogRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    DialogSurfaceRenderer(component, ctx, modifier, level = "warning") {
        DialogActionButton("Dismiss", ctx, component, "onDismiss")
    }
}

@Composable
fun ErrorDialogRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    DialogSurfaceRenderer(component, ctx, modifier, level = "error") {
        DialogActionButton("Retry", ctx, component, "onRetry")
        Spacer(Modifier.width(8.dp))
        DialogActionButton("Dismiss", ctx, component, "onDismiss")
    }
}

@Composable
fun TipRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    FeedbackMessageRenderer(component, ctx, modifier, level = "info", defaultTitle = "Tip", compact = true)
}

@Composable
fun HintRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    FeedbackMessageRenderer(component, ctx, modifier, level = "info", defaultTitle = "Hint", compact = true)
}

@Composable
fun NotificationRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val level = component.propString("level") ?: "info"
    val visuals = feedbackVisuals(level, ctx)
    val scheme = ctx.theme.colorScheme
    val title = component.propStringResolved("title", ctx) ?: "Notification"
    val message = component.propStringResolved("message", ctx) ?: component.propStringResolved("text", ctx)
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier = modifier.fillMaxWidth().clip(shape).background(visuals.containerColor, shape).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = IconMapper.map("notifications"), contentDescription = null, tint = visuals.contentColor, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = visuals.contentColor, style = ctx.theme.typography.titleSmall)
            if (message != null) {
                Text(text = message, color = visuals.contentColor, style = ctx.theme.typography.bodySmall)
            }
        }
        RenderChildren(component.children, ctx)
    }
}

@Composable
fun InlineMessageRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val level = component.propString("level") ?: "neutral"
    FeedbackMessageRenderer(component, ctx, modifier, level = level, compact = true)
}

@Composable
fun SystemMessageRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val scheme = ctx.theme.colorScheme
    val message = component.propStringResolved("message", ctx)
        ?: component.propStringResolved("text", ctx)
        ?: "System message"
    Box(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            color = scheme.onSurfaceVariant,
            style = ctx.theme.typography.labelMedium,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun StatusBarRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val scheme = ctx.theme.colorScheme
    val level = component.propString("level") ?: "neutral"
    val visuals = feedbackVisuals(level, ctx)
    val text = component.propStringResolved("text", ctx) ?: component.propStringResolved("status", ctx) ?: ""
    val progress = component.propFloat("progress", -1f)
    Row(
        modifier = modifier.fillMaxWidth().height(28.dp).background(visuals.containerColor).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(visuals.contentColor))
        Spacer(Modifier.width(8.dp))
        Text(text = text, color = visuals.contentColor, style = ctx.theme.typography.labelSmall, modifier = Modifier.weight(1f))
        if (progress in 0f..1f) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.width(64.dp),
                color = visuals.contentColor,
                trackColor = visuals.contentColor.copy(alpha = 0.3f)
            )
        }
    }
}

@Composable
fun ReviewPromptRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val scheme = ctx.theme.colorScheme
    val title = component.propStringResolved("title", ctx) ?: "Enjoying the app?"
    val message = component.propStringResolved("message", ctx) ?: "Please take a moment to rate us."
    var rating by remember { mutableStateOf(component.propInt("rating", 0)) }
    val container = StyleResolver.resolveColor(component.style.backgroundColor, scheme, scheme.surface)
    val shape = StyleResolver.resolveShape(component.style).let { if (it == RectangleShape) RoundedCornerShape(10.dp) else it }
    val elev = component.style.elevation.takeIf { it > 0f }?.dp ?: 2.dp
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(defaultElevation = elev)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = title, style = ctx.theme.typography.titleMedium, color = scheme.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(text = message, style = ctx.theme.typography.bodySmall, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (i in 1..5) {
                    val filled = i <= rating
                    Icon(
                        imageVector = if (filled) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = "Star $i",
                        tint = if (filled) scheme.warning else scheme.outline,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .minimumInteractiveComponentSize().clickable { rating = i }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row {
                TextButton(onClick = { ctx.clickHandler(component, "onLater")?.invoke() }) {
                    Text(text = component.propStringResolved("laterText", ctx) ?: "Later")
                }
                TextButton(onClick = { ctx.clickHandler(component, "onSubmit")?.invoke() }) {
                    Text(text = component.propStringResolved("submitText", ctx) ?: "Submit")
                }
            }
            RenderChildren(component.children, ctx)
        }
    }
}

@Composable
fun FeedbackFormRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val scheme = ctx.theme.colorScheme
    val title = component.propStringResolved("title", ctx) ?: "Feedback"
    val subtitle = component.propStringResolved("subtitle", ctx) ?: "Tell us what you think."
    val container = StyleResolver.resolveColor(component.style.backgroundColor, scheme, scheme.surface)
    val shape = StyleResolver.resolveShape(component.style).let { if (it == RectangleShape) RoundedCornerShape(10.dp) else it }
    val elev = component.style.elevation.takeIf { it > 0f }?.dp ?: 1.dp
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(defaultElevation = elev)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(text = title, style = ctx.theme.typography.titleMedium, color = scheme.onSurface)
            Text(text = subtitle, style = ctx.theme.typography.bodySmall, color = scheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            RenderChildren(component.children, ctx)
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { ctx.clickHandler(component, "onSubmit")?.invoke() }) {
                    Text(text = component.propStringResolved("submitText", ctx) ?: "Submit Feedback")
                }
            }
        }
    }
}

// =====================================================================================
// ==================================== DATA DOMAIN ====================================
// =====================================================================================

/** Shared table renderer for table / data_table / comparison_table. */
@Composable
private fun TableBaseRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier,
    firstColAsLabel: Boolean = false
) {
    val scheme = ctx.theme.colorScheme
    val headers = component.propStringList("headers").ifEmpty { component.propStringList("columns") }
    val rowsArray = component.properties["rows"] as? JsonArray ?: JsonArray(emptyList())
    val rows: List<List<String>> = rowsArray.map { rowEl ->
        when (rowEl) {
            is JsonArray -> rowEl.map { jsonElementToString(it) }
            is JsonObject -> if (headers.isNotEmpty()) headers.map { h -> jsonElementToString(rowEl[h]) } else rowEl.values.map { jsonElementToString(it) }
            else -> listOf(jsonElementToString(rowEl))
        }
    }
    val headerBg = scheme.surfaceVariant
    val rowBg = scheme.surface
    val altBg = scheme.surfaceVariant.copy(alpha = 0.3f)
    val borderColor = scheme.outlineVariant
    val onHeader = scheme.onSurfaceVariant
    val onRow = scheme.onSurface
    val shape = RoundedCornerShape(6.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, borderColor, shape)
    ) {
        if (headers.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth().background(headerBg)) {
                headers.forEach { h ->
                    Text(
                        text = h,
                        modifier = Modifier.weight(1f).padding(12.dp),
                        style = ctx.theme.typography.labelLarge,
                        color = onHeader,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            HorizontalDivider(color = borderColor)
        }
        if (rows.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(text = "No data", style = ctx.theme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
        } else {
            rows.forEachIndexed { index, row ->
                Row(modifier = Modifier.fillMaxWidth().background(if (index % 2 == 0) rowBg else altBg)) {
                    row.forEachIndexed { ci, cell ->
                        val isLabel = firstColAsLabel && ci == 0
                        Text(
                            text = cell,
                            modifier = Modifier.weight(1f).padding(12.dp),
                            style = ctx.theme.typography.bodyMedium,
                            color = if (isLabel) onHeader else onRow,
                            fontWeight = if (isLabel) FontWeight.Medium else FontWeight.Normal
                        )
                    }
                }
                if (index < rows.lastIndex) {
                    HorizontalDivider(color = borderColor)
                }
            }
        }
        RenderChildren(component.children, ctx)
    }
}

/** Shared stat-card renderer for data_card / stat_card / metric_card / kpi_card. */
@Composable
private fun StatCardBaseRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier,
    valueStyle: TextStyle,
    emphasize: Boolean = false
) {
    val scheme = ctx.theme.colorScheme
    val title = component.propStringResolved("title", ctx) ?: component.propStringResolved("label", ctx)
    val value = component.propStringResolved("value", ctx)
        ?: component.propStringResolved("metric", ctx)
        ?: component.propStringResolved("amount", ctx)
    val unit = component.propStringResolved("unit", ctx)
    val trend = component.propStringResolved("trend", ctx)
    val caption = component.propStringResolved("caption", ctx)
        ?: component.propStringResolved("subtitle", ctx)
        ?: component.propStringResolved("description", ctx)
    val container = StyleResolver.resolveColor(component.style.backgroundColor, scheme, scheme.surface)
    val onSurface = scheme.onSurface
    val onSurfaceVariant = scheme.onSurfaceVariant
    val accent = StyleResolver.resolveColor(component.style.textColor, scheme, scheme.primary)
    val shape = StyleResolver.resolveShape(component.style).let { if (it == RectangleShape) RoundedCornerShape(10.dp) else it }
    val elev = component.style.elevation.takeIf { it > 0f }?.dp ?: (if (emphasize) 2.dp else 1.dp)
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(defaultElevation = elev)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            if (title != null) {
                Text(text = title, style = ctx.theme.typography.labelMedium, color = onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value ?: "--",
                    style = StyleResolver.buildTextStyle(component.style, ctx, valueStyle)
                        .copy(color = onSurface, fontWeight = FontWeight.Bold)
                )
                if (unit != null) {
                    Spacer(Modifier.width(4.dp))
                    Text(text = unit, style = ctx.theme.typography.titleSmall, color = onSurfaceVariant)
                }
            }
            if (trend != null) {
                Spacer(Modifier.height(4.dp))
                Text(text = trend, style = ctx.theme.typography.labelSmall, color = accent)
            }
            if (caption != null) {
                Spacer(Modifier.height(4.dp))
                Text(text = caption, style = ctx.theme.typography.bodySmall, color = onSurfaceVariant)
            }
            RenderChildren(component.children, ctx)
        }
    }
}

/** Shared expandable renderer for accordion / expansion_tile / collapse. */
@Composable
private fun ExpandableRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val scheme = ctx.theme.colorScheme
    var expanded by remember { mutableStateOf(component.propBool("expanded", false)) }
    val title = component.propStringResolved("title", ctx) ?: component.propStringResolved("header", ctx)
    val subtitle = component.propStringResolved("subtitle", ctx)
    val container = StyleResolver.resolveColor(component.style.backgroundColor, scheme, scheme.surface)
    val onSurface = scheme.onSurface
    val onSurfaceVariant = scheme.onSurfaceVariant
    val shape = StyleResolver.resolveShape(component.style).let { if (it == RectangleShape) RoundedCornerShape(6.dp) else it }
    val elev = component.style.elevation.takeIf { it > 0f }?.dp ?: 1.dp
    val toggleHandler = ctx.clickHandler(component, "onToggle")
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = container,
        tonalElevation = elev,
        shadowElevation = elev
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize().clickable {
                        expanded = !expanded
                        toggleHandler?.invoke()
                    }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    if (title != null) {
                        Text(text = title, style = ctx.theme.typography.titleMedium, color = onSurface)
                    }
                    if (subtitle != null) {
                        Text(text = subtitle, style = ctx.theme.typography.bodySmall, color = onSurfaceVariant)
                    }
                }
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = onSurfaceVariant,
                    modifier = Modifier
                        .size(24.dp)
                        .rotate(if (expanded) 180f else 0f)
                )
            }
            if (expanded) {
                HorizontalDivider(color = scheme.outlineVariant)
                Column(modifier = Modifier.padding(16.dp)) {
                    RenderChildren(component.children, ctx)
                }
            }
        }
    }
}

/** Shared key-value list renderer for fact_set / key_value / description_list. */
@Composable
private fun KeyValuePairsRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier,
    termStyle: TextStyle = TextStyle(),
    descStyle: TextStyle = TextStyle()
) {
    val scheme = ctx.theme.colorScheme
    val pairs: List<Pair<String, String>> = run {
        val itemsArr = component.properties["items"] as? JsonArray
        when {
            itemsArr != null -> itemsArr.map { el ->
                when (el) {
                    is JsonObject -> jsonElementToString(el["label"] ?: el["key"] ?: el["term"]) to
                        jsonElementToString(el["value"] ?: el["description"] ?: el["val"])
                    is JsonArray -> jsonElementToString(el.getOrNull(0)) to jsonElementToString(el.getOrNull(1))
                    else -> jsonElementToString(el) to ""
                }
            }
            else -> {
                val dataObj = component.properties["data"] as? JsonObject
                if (dataObj != null) {
                    dataObj.entries.map { e -> e.key to jsonElementToString(e.value) }
                } else {
                    val k = component.propStringResolved("label", ctx) ?: component.propStringResolved("key", ctx) ?: ""
                    val v = component.propStringResolved("value", ctx) ?: component.propStringResolved("description", ctx) ?: ""
                    if (k.isBlank() && v.isBlank()) emptyList() else listOf(k to v)
                }
            }
        }
    }
    val termResolved = if (termStyle === TextStyle()) ctx.theme.typography.labelLarge else termStyle
    val descResolved = if (descStyle === TextStyle()) ctx.theme.typography.bodyMedium else descStyle
    Column(modifier.fillMaxWidth()) {
        if (pairs.isEmpty()) {
            Text(text = "No entries", style = descResolved, color = scheme.onSurfaceVariant)
        } else {
            pairs.forEachIndexed { index, (k, v) ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = k,
                        modifier = Modifier.weight(1f).padding(end = 16.dp),
                        style = termResolved,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = v,
                        style = descResolved,
                        color = scheme.onSurface,
                        textAlign = TextAlign.End,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (index < pairs.lastIndex) {
                    HorizontalDivider(color = scheme.outlineVariant)
                }
            }
        }
        RenderChildren(component.children, ctx)
    }
}

@Composable
fun TableRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    TableBaseRenderer(component, ctx, modifier, firstColAsLabel = false)
}

@Composable
fun DataTableRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    TableBaseRenderer(component, ctx, modifier, firstColAsLabel = false)
}

/** list (renamed to avoid clashing with the existing [ListRenderer]). Uses a Column with dividers. */
@Composable
fun GenListRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val scheme = ctx.theme.colorScheme
    Column(modifier.fillMaxWidth()) {
        component.children.forEachIndexed { index, child ->
            RenderChildren(listOf(child), ctx)
            if (index < component.children.lastIndex) {
                HorizontalDivider(color = scheme.outlineVariant)
            }
        }
    }
}

@Composable
fun ListItemRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val scheme = ctx.theme.colorScheme
    val title = component.propStringResolved("title", ctx)
    val subtitle = component.propStringResolved("subtitle", ctx) ?: component.propStringResolved("description", ctx)
    val iconName = component.propString("icon") ?: component.propString("leading")
    val trailing = component.propStringResolved("trailing", ctx) ?: component.propStringResolved("action", ctx)
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (iconName != null) {
            Icon(imageVector = IconMapper.map(iconName), contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            if (title != null) {
                Text(text = title, style = ctx.theme.typography.titleMedium, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (subtitle != null) {
                if (title != null) Spacer(Modifier.height(2.dp))
                Text(text = subtitle, style = ctx.theme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            RenderChildren(component.children, ctx)
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            Text(text = trailing, style = ctx.theme.typography.labelSmall, color = scheme.primary)
        }
    }
}

@Composable
fun ListSectionRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val scheme = ctx.theme.colorScheme
    val title = component.propStringResolved("title", ctx) ?: component.propStringResolved("header", ctx)
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                text = title,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                style = ctx.theme.typography.labelLarge,
                color = scheme.onSurfaceVariant
            )
        }
        HorizontalDivider(color = scheme.outlineVariant)
        RenderChildren(component.children, ctx)
    }
}

@Composable
fun GridViewRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val scheme = ctx.theme.colorScheme
    val columns = component.propInt("columns", 2).coerceIn(1, 8)
    val cols = columns.coerceIn(1, 12)
    val rows = component.children.chunked(cols)
    Column(modifier.fillMaxWidth()) {
        rows.forEachIndexed { index, rowChildren ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowChildren.forEach { child ->
                    Box(modifier = Modifier.weight(1f)) {
                        RenderChildren(listOf(child), ctx)
                    }
                }
                repeat(cols - rowChildren.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
            if (index < rows.lastIndex) {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
        if (component.children.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(text = "Empty grid", style = ctx.theme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TreeNodeRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier, depth: Int) {
    val scheme = ctx.theme.colorScheme
    val title = component.propStringResolved("title", ctx) ?: component.propStringResolved("label", ctx)
    var expanded by remember { mutableStateOf(component.propBool("expanded", true)) }
    val hasChildren = component.children.isNotEmpty()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = (depth * 16).dp, top = 6.dp, bottom = 6.dp, end = 8.dp)
            .minimumInteractiveComponentSize().clickable { if (hasChildren) expanded = !expanded },
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (hasChildren) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier
                    .size(20.dp)
                    .rotate(if (expanded) 90f else 0f)
            )
        } else {
            Spacer(modifier = Modifier.width(20.dp))
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = title ?: component.type,
            style = ctx.theme.typography.bodyMedium,
            color = scheme.onSurface
        )
    }
    if (hasChildren && expanded) {
        component.children.forEach { child ->
            TreeNodeRenderer(child, ctx, Modifier, depth + 1)
        }
    }
}

@Composable
fun TreeViewRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val scheme = ctx.theme.colorScheme
    Column(modifier.fillMaxWidth().padding(8.dp)) {
        if (component.children.isEmpty()) {
            TreeNodeRenderer(component, ctx, Modifier, 0)
        } else {
            component.children.forEach { child ->
                TreeNodeRenderer(child, ctx, Modifier, 0)
            }
        }
    }
}

@Composable
fun AccordionRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    ExpandableRenderer(component, ctx, modifier)
}

@Composable
fun ExpansionTileRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    ExpandableRenderer(component, ctx, modifier)
}

@Composable
fun CollapseRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    ExpandableRenderer(component, ctx, modifier)
}

@Composable
fun FactSetRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    KeyValuePairsRenderer(component, ctx, modifier)
}

@Composable
fun KeyValueRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    KeyValuePairsRenderer(component, ctx, modifier)
}

@Composable
fun DescriptionListRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val ctx0 = ctx
    KeyValuePairsRenderer(
        component, ctx0, modifier,
        termStyle = ctx0.theme.typography.titleSmall,
        descStyle = ctx0.theme.typography.bodyMedium
    )
}

@Composable
fun DataCardRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    StatCardBaseRenderer(component, ctx, modifier, valueStyle = ctx.theme.typography.titleMedium, emphasize = false)
}

@Composable
fun StatCardRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    StatCardBaseRenderer(component, ctx, modifier, valueStyle = ctx.theme.typography.headlineMedium, emphasize = true)
}

@Composable
fun MetricCardRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    StatCardBaseRenderer(component, ctx, modifier, valueStyle = ctx.theme.typography.headlineLarge, emphasize = true)
}

@Composable
fun KpiCardRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    StatCardBaseRenderer(component, ctx, modifier, valueStyle = ctx.theme.typography.displaySmall, emphasize = true)
}

@Composable
fun ChartRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val scheme = ctx.theme.colorScheme
    val type = component.propString("type") ?: "bar"
    val data = component.propFloatList("data").ifEmpty { listOf(0.4f, 0.7f, 0.5f, 0.9f, 0.6f, 1.0f, 0.75f) }
    val color = StyleResolver.resolveColor(component.style.textColor, scheme, scheme.primary)
    val trackColor = scheme.surfaceVariant
    val title = component.propStringResolved("title", ctx)
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Text(text = title, style = ctx.theme.typography.titleMedium, color = scheme.onSurface)
            Spacer(Modifier.height(8.dp))
        }
        when (type.lowercase()) {
            "line" -> LineChartCanvas(data, Modifier.fillMaxWidth().height(180.dp), color, trackColor)
            "pie" -> PieChartCanvas(data, Modifier.fillMaxWidth().height(180.dp), chartPalette(ctx), trackColor)
            else -> BarChartCanvas(data, Modifier.fillMaxWidth().height(180.dp), color, trackColor)
        }
    }
}

@Composable
fun BarChartRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val scheme = ctx.theme.colorScheme
    val data = component.propFloatList("data").ifEmpty { listOf(0.4f, 0.7f, 0.5f, 0.9f, 0.6f, 1.0f) }
    val color = StyleResolver.resolveColor(component.style.textColor, scheme, scheme.primary)
    val title = component.propStringResolved("title", ctx)
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Text(text = title, style = ctx.theme.typography.titleMedium, color = scheme.onSurface)
            Spacer(Modifier.height(8.dp))
        }
        BarChartCanvas(data, Modifier.fillMaxWidth().height(180.dp), color, scheme.surfaceVariant)
    }
}

@Composable
fun LineChartRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val scheme = ctx.theme.colorScheme
    val data = component.propFloatList("data").ifEmpty { listOf(0.4f, 0.7f, 0.5f, 0.9f, 0.6f, 1.0f) }
    val color = StyleResolver.resolveColor(component.style.textColor, scheme, scheme.primary)
    val title = component.propStringResolved("title", ctx)
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Text(text = title, style = ctx.theme.typography.titleMedium, color = scheme.onSurface)
            Spacer(Modifier.height(8.dp))
        }
        LineChartCanvas(data, Modifier.fillMaxWidth().height(180.dp), color, scheme.surfaceVariant)
    }
}

@Composable
fun PieChartRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val scheme = ctx.theme.colorScheme
    val data = component.propFloatList("data").ifEmpty { listOf(1f, 2f, 1.5f, 1f) }
    val title = component.propStringResolved("title", ctx)
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Text(text = title, style = ctx.theme.typography.titleMedium, color = scheme.onSurface)
            Spacer(Modifier.height(8.dp))
        }
        PieChartCanvas(data, Modifier.fillMaxWidth().height(180.dp), chartPalette(ctx), scheme.surfaceVariant)
    }
}

@Composable
private fun TimelineRow(
    content: @Composable () -> Unit,
    time: String?,
    title: String?,
    desc: String?,
    isLast: Boolean,
    ctx: RenderContext,
    accent: Color,
    dot: Color,
    line: Color
) {
    val scheme = ctx.theme.colorScheme
    Row(modifier = Modifier.fillMaxWidth()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(end = 16.dp)
        ) {
            Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(dot))
            if (!isLast) {
                Box(modifier = Modifier.width(2.dp).height(40.dp).background(line))
            }
        }
        Column(modifier = Modifier.weight(1f).padding(bottom = 16.dp)) {
            if (time != null) {
                Text(text = time, style = ctx.theme.typography.labelSmall, color = accent)
            }
            if (title != null) {
                Text(text = title, style = ctx.theme.typography.titleSmall, color = scheme.onSurface)
            }
            if (desc != null) {
                Text(text = desc, style = ctx.theme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            content()
        }
    }
}

@Composable
fun TimelineRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val scheme = ctx.theme.colorScheme
    val accent = StyleResolver.resolveColor(component.style.textColor, scheme, scheme.primary)
    val lineColor = scheme.outlineVariant
    val itemsArr = component.properties["items"] as? JsonArray
    Column(modifier.fillMaxWidth()) {
        if (itemsArr != null) {
            itemsArr.forEachIndexed { i, el ->
                val obj = el as? JsonObject
                val time = jsonElementToString(obj?.get("time") ?: obj?.get("date"))
                val title = jsonElementToString(obj?.get("title") ?: obj?.get("label"))
                val desc = jsonElementToString(obj?.get("description") ?: obj?.get("subtitle"))
                TimelineRow({ }, time, title, desc, i == itemsArr.lastIndex, ctx, accent, accent, lineColor)
            }
        } else {
            component.children.forEachIndexed { i, child ->
                TimelineRow(
                    content = { RenderChildren(listOf(child), ctx) },
                    time = null,
                    title = null,
                    desc = null,
                    isLast = i == component.children.lastIndex,
                    ctx = ctx,
                    accent = accent,
                    dot = accent,
                    line = lineColor
                )
            }
        }
    }
}

@Composable
fun CalendarRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val scheme = ctx.theme.colorScheme
    val month = component.propStringResolved("month", ctx) ?: component.propStringResolved("title", ctx) ?: "Calendar"
    val shape = RoundedCornerShape(6.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, scheme.outlineVariant, shape)
            .padding(8.dp)
    ) {
        Text(text = month, modifier = Modifier.fillMaxWidth().padding(8.dp), style = ctx.theme.typography.titleMedium, color = scheme.onSurface, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("S", "M", "T", "W", "T", "F", "S").forEach {
                Text(text = it, modifier = Modifier.weight(1f).padding(4.dp), style = ctx.theme.typography.labelSmall, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
        Spacer(Modifier.height(4.dp))
        val days = (1..30).toList().chunked(7)
        days.forEachIndexed { index, week ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                week.forEach { d ->
                    Box(modifier = Modifier.weight(1f).padding(4.dp), contentAlignment = Alignment.Center) {
                        Text(text = d.toString(), style = ctx.theme.typography.bodyMedium, color = scheme.onSurface)
                    }
                }
                repeat(7 - week.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
            if (index < days.lastIndex) {
                Spacer(modifier = Modifier.height(2.dp))
            }
        }
        RenderChildren(component.children, ctx)
    }
}

@Composable
fun ScheduleRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val scheme = ctx.theme.colorScheme
    val slotsArr = component.properties["slots"] as? JsonArray
    val slots: List<Pair<String, String>> = if (slotsArr != null) {
        slotsArr.map { el ->
            val obj = el as? JsonObject
            (jsonElementToString(obj?.get("time") ?: obj?.get("start"))) to
                (jsonElementToString(obj?.get("title") ?: obj?.get("event") ?: obj?.get("name")))
        }
    } else {
        listOf(
            "09:00" to "Morning briefing",
            "11:30" to "Design review",
            "14:00" to "Project sync",
            "16:30" to "1:1 with manager"
        )
    }
    Column(modifier.fillMaxWidth()) {
        slots.forEachIndexed { index, (time, event) ->
            Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(text = time, modifier = Modifier.width(64.dp), style = ctx.theme.typography.labelLarge, color = scheme.primary)
                Spacer(Modifier.width(12.dp))
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(scheme.primary.copy(alpha = 0.6f)))
                Spacer(Modifier.width(12.dp))
                Text(text = event, modifier = Modifier.weight(1f), style = ctx.theme.typography.bodyMedium, color = scheme.onSurface)
            }
            if (index < slots.lastIndex) {
                HorizontalDivider(color = scheme.outlineVariant)
            }
        }
        RenderChildren(component.children, ctx)
    }
}

@Composable
private fun KanbanColumn(
    title: String,
    cards: List<String>,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val scheme = ctx.theme.colorScheme
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(scheme.surfaceVariant)
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = title, modifier = Modifier.weight(1f), style = ctx.theme.typography.titleSmall, color = scheme.onSurface)
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(scheme.primary.copy(alpha = 0.15f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(text = cards.size.toString(), style = ctx.theme.typography.labelSmall, color = scheme.primary)
            }
        }
        Spacer(Modifier.height(8.dp))
        cards.forEach { card ->
            Surface(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                shape = RoundedCornerShape(6.dp),
                color = scheme.surface,
                tonalElevation = 1.dp
            ) {
                Text(text = card, modifier = Modifier.padding(8.dp), style = ctx.theme.typography.bodySmall, color = scheme.onSurface)
            }
        }
    }
}

@Composable
fun KanbanRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val colsArr = component.properties["columns"] as? JsonArray
    val columns: List<Pair<String, List<String>>> = if (colsArr != null) {
        colsArr.map { el ->
            val obj = el as? JsonObject
            val title = jsonElementToString(obj?.get("title") ?: obj?.get("name"))
            val items = (obj?.get("items") as? JsonArray)?.map { jsonElementToString(it) } ?: emptyList()
            title to items
        }
    } else {
        listOf(
            "To Do" to listOf("Task A", "Task B"),
            "In Progress" to listOf("Task C"),
            "Done" to listOf("Task D", "Task E")
        )
    }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        columns.forEach { (title, cards) ->
            KanbanColumn(title, cards, ctx, Modifier.weight(1f))
        }
    }
}

@Composable
fun DataListRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val scheme = ctx.theme.colorScheme
    Column(modifier.fillMaxWidth()) {
        component.children.forEachIndexed { index, child ->
            RenderChildren(listOf(child), ctx)
            if (index < component.children.lastIndex) {
                HorizontalDivider(color = scheme.outlineVariant)
            }
        }
    }
}

@Composable
fun ComparisonTableRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    TableBaseRenderer(component, ctx, modifier, firstColAsLabel = true)
}

@Composable
fun SummaryCardRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val scheme = ctx.theme.colorScheme
    val title = component.propStringResolved("title", ctx) ?: component.propStringResolved("label", ctx)
    val summary = component.propStringResolved("summary", ctx) ?: component.propStringResolved("body", ctx) ?: component.propStringResolved("description", ctx)
    val container = StyleResolver.resolveColor(component.style.backgroundColor, scheme, scheme.surface)
    val shape = StyleResolver.resolveShape(component.style).let { if (it == RectangleShape) RoundedCornerShape(10.dp) else it }
    val elev = component.style.elevation.takeIf { it > 0f }?.dp ?: 1.dp
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(defaultElevation = elev)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            if (title != null) {
                Text(text = title, style = ctx.theme.typography.titleMedium, color = scheme.onSurface)
            }
            if (summary != null) {
                if (title != null) Spacer(Modifier.height(8.dp))
                Text(text = summary, style = ctx.theme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
            RenderChildren(component.children, ctx)
        }
    }
}

@Composable
fun DetailViewRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val scheme = ctx.theme.colorScheme
    val title = component.propStringResolved("title", ctx) ?: component.propStringResolved("name", ctx)
    val container = StyleResolver.resolveColor(component.style.backgroundColor, scheme, scheme.surface)
    val shape = StyleResolver.resolveShape(component.style).let { if (it == RectangleShape) RoundedCornerShape(10.dp) else it }
    val elev = component.style.elevation.takeIf { it > 0f }?.dp ?: 1.dp
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(defaultElevation = elev)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            if (title != null) {
                Text(text = title, style = ctx.theme.typography.titleLarge, color = scheme.onSurface)
            }
            if (component.properties["data"] is JsonObject || component.properties["items"] is JsonArray) {
                if (title != null) Spacer(Modifier.height(12.dp))
                KeyValuePairsRenderer(component, ctx, Modifier)
            }
            RenderChildren(component.children, ctx)
        }
    }
}

// =====================================================================================
// ==================================== MEDIA DOMAIN ===================================
// =====================================================================================

@Composable
fun VideoPlayerRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val scheme = ctx.theme.colorScheme
    val bg = StyleResolver.resolveColor(component.style.backgroundColor, scheme, scheme.onSurface)
    val tint = StyleResolver.resolveColor(component.style.textColor, scheme, Color.White)
    val label = component.propStringResolved("title", ctx) ?: "Video Player"
    Box(
        modifier = modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)).background(bg),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = "Play", tint = tint, modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(text = label, color = tint, style = ctx.theme.typography.bodyMedium)
        }
    }
}

@Composable
fun AudioPlayerRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val scheme = ctx.theme.colorScheme
    val title = component.propStringResolved("title", ctx) ?: "Audio Track"
    val container = StyleResolver.resolveColor(component.style.backgroundColor, scheme, scheme.surface)
    val accent = StyleResolver.resolveColor(component.style.textColor, scheme, scheme.primary)
    val shape = RoundedCornerShape(6.dp)
    Surface(modifier = modifier.fillMaxWidth(), shape = shape, color = container, tonalElevation = 1.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(40.dp).clip(CircleShape).background(accent.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(imageVector = Icons.Filled.MusicNote, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text(text = title, modifier = Modifier.weight(1f), style = ctx.theme.typography.titleSmall, color = scheme.onSurface)
                Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = "Play", tint = accent, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(text = "0:00", style = ctx.theme.typography.labelSmall, color = scheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Box(modifier = Modifier.weight(1f).height(4.dp).clip(CircleShape).background(scheme.surfaceVariant))
                Spacer(Modifier.width(8.dp))
                Text(text = component.propStringResolved("duration", ctx) ?: "0:00", style = ctx.theme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun GalleryRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    MediaGridBaseRenderer(component, ctx, modifier, Icons.Filled.PhotoLibrary, "Gallery")
}

@Composable
fun ImageGridRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    MediaGridBaseRenderer(component, ctx, modifier, Icons.Filled.Image, "Image")
}

@Composable
fun ImageCarouselRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val scheme = ctx.theme.colorScheme
    val count = component.propInt("count", 3).coerceAtLeast(1)
    val bg = scheme.surfaceVariant
    val tint = scheme.onSurfaceVariant
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(count.coerceAtMost(5)) { _ ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(140.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(bg),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(imageVector = Icons.Filled.Image, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.height(4.dp))
                    Text(text = "Photo", color = tint, style = ctx.theme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
fun VideoThumbnailRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val scheme = ctx.theme.colorScheme
    val bg = StyleResolver.resolveColor(component.style.backgroundColor, scheme, scheme.onSurface)
    val tint = StyleResolver.resolveColor(component.style.textColor, scheme, Color.White)
    val label = component.propStringResolved("title", ctx) ?: "Video"
    Box(
        modifier = modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(6.dp)).background(bg),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = "Play", tint = tint, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.height(4.dp))
            Text(text = label, color = tint, style = ctx.theme.typography.labelSmall)
        }
    }
}

@Composable
fun AudioWaveRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val scheme = ctx.theme.colorScheme
    val bars = component.propFloatList("bars")
    val color = StyleResolver.resolveColor(component.style.textColor, scheme, scheme.primary)
    val barCount = component.propInt("bars", 40).coerceIn(8, 80)
    Canvas(modifier.fillMaxWidth().height(64.dp)) {
        val n = if (bars.isNotEmpty()) bars.size else barCount
        val slot = size.width / n
        val barW = slot * 0.5f
        for (i in 0 until n) {
            val ratio = if (bars.isNotEmpty()) {
                bars[i].coerceIn(0f, 1f)
            } else {
                0.2f + 0.8f * (((i * 37) % 100) / 100f)
            }
            val h = ratio * size.height
            drawRect(
                color = color,
                topLeft = Offset(i * slot + (slot - barW) / 2f, (size.height - h) / 2f),
                size = Size(barW, h)
            )
        }
    }
}

@Composable
fun MediaCardRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val scheme = ctx.theme.colorScheme
    val title = component.propStringResolved("title", ctx) ?: "Media"
    val subtitle = component.propStringResolved("subtitle", ctx) ?: component.propStringResolved("duration", ctx)
    val container = StyleResolver.resolveColor(component.style.backgroundColor, scheme, scheme.surface)
    val shape = StyleResolver.resolveShape(component.style).let { if (it == RectangleShape) RoundedCornerShape(10.dp) else it }
    val elev = component.style.elevation.takeIf { it > 0f }?.dp ?: 1.dp
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(defaultElevation = elev)
    ) {
        Column {
            Box(
                modifier = Modifier.fillMaxWidth().height(140.dp).background(scheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = IconMapper.map(component.propString("icon")), contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
            }
            Column(modifier = Modifier.padding(12.dp)) {
                Text(text = title, style = ctx.theme.typography.titleSmall, color = scheme.onSurface)
                if (subtitle != null) {
                    Text(text = subtitle, style = ctx.theme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun MediaGridRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    MediaGridBaseRenderer(component, ctx, modifier, Icons.Filled.Image, "Media")
}

@Composable
fun MediaListRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    MediaListBaseRenderer(component, ctx, modifier, Icons.Filled.Image, "Media")
}

@Composable
fun MediaPreviewRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    MediaPlaceholderRenderer(component, ctx, modifier, Icons.Filled.Image, "Preview", height = 100.dp)
}

@Composable
fun ThumbnailGridRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    MediaGridBaseRenderer(component, ctx, modifier, Icons.Filled.Image, "Thumb", cellHeight = 80.dp)
}

@Composable
fun AudioThumbnailRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    MediaPlaceholderRenderer(component, ctx, modifier, Icons.Filled.MusicNote, "Audio", height = 80.dp)
}

@Composable
fun FilePreviewRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val scheme = ctx.theme.colorScheme
    val fileName = component.propStringResolved("name", ctx) ?: component.propStringResolved("title", ctx) ?: "File"
    val fileSize = component.propStringResolved("size", ctx) ?: component.propStringResolved("subtitle", ctx)
    val container = StyleResolver.resolveColor(component.style.backgroundColor, scheme, scheme.surface)
    val accent = StyleResolver.resolveColor(component.style.textColor, scheme, scheme.primary)
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(6.dp), color = container, tonalElevation = 1.dp) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).background(accent.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(imageVector = Icons.Filled.InsertDriveFile, contentDescription = null, tint = accent, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = fileName, style = ctx.theme.typography.titleSmall, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (fileSize != null) {
                    Text(text = fileSize, style = ctx.theme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun DocumentViewerRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val scheme = ctx.theme.colorScheme
    val title = component.propStringResolved("title", ctx) ?: "Document"
    val pages = component.propInt("pages", 3).coerceAtLeast(1)
    Column(modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = Icons.Filled.PictureAsPdf, contentDescription = null, tint = scheme.error, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(text = title, style = ctx.theme.typography.titleSmall, color = scheme.onSurface, modifier = Modifier.weight(1f))
            Text(text = "$pages pages", style = ctx.theme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(pages.coerceAtMost(4)) { i ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(scheme.surfaceVariant)
                        .padding(16.dp),
                    contentAlignment = Alignment.TopStart
                ) {
                    Column {
                        repeat((2 + i % 3)) {
                            Box(modifier = Modifier.fillMaxWidth(0.8f - (i * 0.05f)).height(8.dp).background(scheme.outline.copy(alpha = 0.5f)))
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun ImageViewerRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    MediaPlaceholderRenderer(component, ctx, modifier, Icons.Filled.Image, "Image Viewer", height = 240.dp)
}

@Composable
fun VideoViewerRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    MediaPlaceholderRenderer(component, ctx, modifier, Icons.Filled.PlayArrow, "Video Viewer", height = 240.dp)
}

@Composable
fun PhotoGridRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    MediaGridBaseRenderer(component, ctx, modifier, Icons.Filled.Image, "Photo")
}

@Composable
fun VideoGridRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    MediaGridBaseRenderer(component, ctx, modifier, Icons.Filled.VideoLibrary, "Video")
}

@Composable
fun AudioListRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    MediaListBaseRenderer(component, ctx, modifier, Icons.Filled.MusicNote, "Track")
}

@Composable
fun PlaylistRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val scheme = ctx.theme.colorScheme
    val count = component.propInt("count", 4).coerceAtLeast(1)
    val title = component.propStringResolved("title", ctx) ?: "Playlist"
    Column(modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).background(scheme.primary.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(imageVector = Icons.Filled.MusicNote, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(text = title, style = ctx.theme.typography.titleMedium, color = scheme.onSurface, modifier = Modifier.weight(1f))
        }
        HorizontalDivider(color = scheme.outlineVariant)
        repeat(count) { i ->
            Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(text = "${i + 1}", modifier = Modifier.width(28.dp), style = ctx.theme.typography.labelLarge, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(text = component.propStringResolved("title", ctx) ?: "Track ${i + 1}", modifier = Modifier.weight(1f), style = ctx.theme.typography.bodyMedium, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(text = "3:2${i % 10}", style = ctx.theme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
            if (i < count - 1) {
                HorizontalDivider(color = scheme.outlineVariant)
            }
        }
    }
}

@Composable
fun MediaBrowserRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val scheme = ctx.theme.colorScheme
    val title = component.propStringResolved("title", ctx) ?: "Media Browser"
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .clip(RoundedCornerShape(50))
                .background(scheme.surfaceVariant)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = Icons.Filled.Search, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(text = title, modifier = Modifier.weight(1f), style = ctx.theme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        MediaGridBaseRenderer(component, ctx, Modifier, Icons.Filled.PhotoLibrary, "Media")
    }
}

@Composable
fun MediaPickerRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val scheme = ctx.theme.colorScheme
    val count = component.propInt("count", 6).coerceAtLeast(1)
    val columns = component.propInt("columns", 3).coerceIn(1, 8)
    val bg = scheme.surfaceVariant
    val selected = component.propBool("selectable", true)
    val rows = (1..count).toList().chunked(columns)
    Column(modifier.fillMaxWidth()) {
        rows.forEachIndexed { index, rowItems ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowItems.forEach { idx ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(100.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(bg),
                        contentAlignment = if (selected && idx % 2 == 0) Alignment.TopEnd else Alignment.Center
                    ) {
                        if (selected && idx % 2 == 0) {
                            Box(modifier = Modifier.padding(6.dp)) {
                                Box(modifier = Modifier.size(20.dp).clip(CircleShape).background(scheme.primary), contentAlignment = Alignment.Center) {
                                    Icon(imageVector = Icons.Filled.Check, contentDescription = "Selected", tint = Color.White, modifier = Modifier.size(14.dp))
                                }
                            }
                        } else {
                            Icon(imageVector = Icons.Filled.Image, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
                        }
                    }
                }
                repeat(columns - rowItems.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
            if (index < rows.lastIndex) {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
fun CameraPreviewRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    MediaPlaceholderRenderer(component, ctx, modifier, Icons.Filled.CameraAlt, "Camera", height = 240.dp)
}

@Composable
fun ScreenCaptureRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    MediaPlaceholderRenderer(component, ctx, modifier, Icons.Filled.Image, "Screen Capture", height = 200.dp)
}

@Composable
fun MediaStreamRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val scheme = ctx.theme.colorScheme
    val label = component.propStringResolved("title", ctx) ?: "Live Stream"
    val bg = StyleResolver.resolveColor(component.style.backgroundColor, scheme, scheme.onSurface)
    val tint = StyleResolver.resolveColor(component.style.textColor, scheme, Color.White)
    Box(
        modifier = modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(6.dp)).background(bg),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null, tint = tint, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(8.dp))
            Text(text = label, color = tint, style = ctx.theme.typography.bodyMedium)
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(scheme.error)
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(text = "LIVE", color = Color.White, style = ctx.theme.typography.labelSmall)
        }
    }
}

@Composable
fun EmbedRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val scheme = ctx.theme.colorScheme
    val url = component.propStringResolved("url", ctx) ?: component.propStringResolved("src", ctx) ?: "embedded://content"
    val shape = RoundedCornerShape(6.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(shape)
            .border(1.dp, scheme.outlineVariant, shape)
    ) {
        Row(modifier = Modifier.fillMaxWidth().background(scheme.surfaceVariant).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text = "Embed", style = ctx.theme.typography.labelSmall, color = scheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(text = url, style = ctx.theme.typography.labelSmall, color = scheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f).background(scheme.surface), contentAlignment = Alignment.Center) {
            Text(text = "Embedded Content", style = ctx.theme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
    }
}

@Composable
fun IframeRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val scheme = ctx.theme.colorScheme
    val url = component.propStringResolved("src", ctx) ?: component.propStringResolved("url", ctx) ?: "about:blank"
    val shape = RoundedCornerShape(6.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(shape)
            .border(1.dp, scheme.outlineVariant, shape)
    ) {
        Row(modifier = Modifier.fillMaxWidth().background(scheme.surfaceVariant).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(3) {
                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(scheme.outline.copy(alpha = 0.5f)))
                Spacer(Modifier.width(6.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(text = url, style = ctx.theme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f).background(scheme.surface), contentAlignment = Alignment.Center) {
            Text(text = "iframe content", style = ctx.theme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
    }
}

@Composable
fun WebViewRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val scheme = ctx.theme.colorScheme
    val url = component.propStringResolved("url", ctx) ?: component.propStringResolved("src", ctx) ?: "https://example.com"
    val shape = RoundedCornerShape(6.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(260.dp)
            .clip(shape)
            .border(1.dp, scheme.outlineVariant, shape)
    ) {
        Row(modifier = Modifier.fillMaxWidth().background(scheme.surfaceVariant).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = Icons.Filled.ArrowBack, contentDescription = "Back", tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(scheme.surface)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(text = url, style = ctx.theme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(12.dp))
            Icon(imageVector = Icons.Filled.Refresh, contentDescription = "Reload", tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f).background(scheme.surface), contentAlignment = Alignment.Center) {
            Text(text = "Web content", style = ctx.theme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
    }
}

// =====================================================================================
// =================================== SURFACE DOMAIN ==================================
// =====================================================================================

private enum class SheetSide { TOP, BOTTOM, START, END }

/** Generic Surface container used by many surface types. */
@Composable
private fun SurfaceContainerRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier,
    defaultElevation: Float = 0f,
    fallbackColor: Color? = null,
    shape: Shape? = null,
    outline: Boolean = false
) {
    val scheme = ctx.theme.colorScheme
    val resolvedShape = shape ?: StyleResolver.resolveShape(component.style).let { s ->
        if (s == RectangleShape) RoundedCornerShape(10.dp) else s
    }
    val containerColor = StyleResolver.resolveColor(
        component.style.backgroundColor, scheme, fallbackColor ?: scheme.surface
    )
    val elev = component.style.elevation.takeIf { it > 0f } ?: defaultElevation
    val border = if (outline) {
        val bw = component.style.borderWidth.takeIf { it > 0f }?.dp ?: 1.dp
        val bc = StyleResolver.resolveColor(component.style.borderColor, scheme, scheme.outline)
        androidx.compose.foundation.BorderStroke(bw, bc)
    } else null
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = resolvedShape,
        color = containerColor,
        tonalElevation = elev.dp,
        shadowElevation = elev.dp,
        border = border
    ) {
        Box(modifier = Modifier.padding(StyleResolver.resolvePadding(component.style.padding))) {
            RenderChildren(component.children, ctx)
        }
    }
}

/** Horizontal bar surface used by app_bar / top_bar / header / footer / toolbar. */
@Composable
private fun BarBaseRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier,
    defaultTitle: String? = null,
    showBack: Boolean = false,
    showActions: Boolean = true
) {
    val scheme = ctx.theme.colorScheme
    val title = component.propStringResolved("title", ctx) ?: defaultTitle
    val container = StyleResolver.resolveColor(component.style.backgroundColor, scheme, scheme.surface)
    val onContainer = StyleResolver.resolveColor(component.style.textColor, scheme, scheme.onSurface)
    val backHandler = ctx.clickHandler(component, "onBack")
    Surface(modifier = modifier.fillMaxWidth(), color = container, tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showBack) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .minimumInteractiveComponentSize().clickable { backHandler?.invoke() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(imageVector = Icons.Filled.ArrowBack, contentDescription = "Back", tint = onContainer, modifier = Modifier.size(24.dp))
                }
            }
            if (title != null) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                    style = ctx.theme.typography.titleMedium,
                    color = onContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
            if (showActions) {
                RenderChildren(component.children, ctx)
            }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .minimumInteractiveComponentSize().clickable { ctx.clickHandler(component, "onMenu")?.invoke() },
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = Icons.Filled.MoreVert, contentDescription = "More", tint = onContainer, modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun SheetBaseRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier,
    side: SheetSide = SheetSide.BOTTOM
) {
    val scheme = ctx.theme.colorScheme
    val container = StyleResolver.resolveColor(component.style.backgroundColor, scheme, scheme.surfaceContainer)
    val elev = component.style.elevation.takeIf { it > 0f }?.dp ?: 6.dp
    val shape = when (side) {
        SheetSide.BOTTOM -> RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        SheetSide.TOP -> RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp)
        SheetSide.START -> RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp)
        SheetSide.END -> RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp)
    }
    val sizing = when (side) {
        SheetSide.START, SheetSide.END -> Modifier.width(280.dp).fillMaxHeight()
        SheetSide.TOP, SheetSide.BOTTOM -> Modifier.fillMaxWidth()
    }
    Surface(
        modifier = modifier.then(sizing),
        shape = shape,
        color = container,
        tonalElevation = elev,
        shadowElevation = elev
    ) {
        Column {
            if (side == SheetSide.BOTTOM || side == SheetSide.TOP) {
                Box(modifier = Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                    Box(modifier = Modifier.width(32.dp).height(4.dp).clip(RoundedCornerShape(6.dp)).background(scheme.outline))
                }
            }
            Column(modifier = Modifier.padding(16.dp)) {
                RenderChildren(component.children, ctx)
            }
        }
    }
}

/** card (renamed to avoid clashing with the existing [CardRenderer]). */
@Composable
fun GenCardRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val scheme = ctx.theme.colorScheme
    val title = component.propStringResolved("title", ctx)
    val subtitle = component.propStringResolved("subtitle", ctx)
    val overline = component.propStringResolved("overline", ctx)
    val container = StyleResolver.resolveColor(component.style.backgroundColor, scheme, scheme.surface)
    val shape = StyleResolver.resolveShape(component.style).let { if (it == RectangleShape) RoundedCornerShape(10.dp) else it }
    val elev = component.style.elevation.takeIf { it > 0f }?.dp ?: 1.dp
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(defaultElevation = elev)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            if (overline != null) {
                Text(text = overline, style = ctx.theme.typography.labelMedium, color = scheme.primary)
                Spacer(Modifier.height(2.dp))
            }
            if (title != null) {
                Text(text = title, style = ctx.theme.typography.titleMedium, color = scheme.onSurface)
            }
            if (subtitle != null) {
                Text(text = subtitle, style = ctx.theme.typography.bodySmall, color = scheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
            }
            RenderChildren(component.children, ctx)
        }
    }
}

@Composable
fun ModalSheetRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    SheetBaseRenderer(component, ctx, modifier, SheetSide.BOTTOM)
}

@Composable
fun BottomSheetRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    SheetBaseRenderer(component, ctx, modifier, SheetSide.BOTTOM)
}

@Composable
fun SideSheetRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    SheetBaseRenderer(component, ctx, modifier, SheetSide.END)
}

@Composable
fun PopoverRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val scheme = ctx.theme.colorScheme
    val container = StyleResolver.resolveColor(component.style.backgroundColor, scheme, scheme.surfaceContainer)
    val elev = component.style.elevation.takeIf { it > 0f }?.dp ?: 8.dp
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = container,
        tonalElevation = elev,
        shadowElevation = elev
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            RenderChildren(component.children, ctx)
        }
    }
}

@Composable
fun DrawerRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    SheetBaseRenderer(component, ctx, modifier, SheetSide.START)
}

@Composable
fun OverlayRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val scheme = ctx.theme.colorScheme
    Box(
        modifier = modifier.fillMaxSize().background(scheme.surfaceVariant.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center
    ) {
        RenderChildren(component.children, ctx)
    }
}

@Composable
fun DimOverlayRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val scheme = ctx.theme.colorScheme
    val alpha = component.propFloat("opacity", 0.5f).coerceIn(0f, 1f)
    Box(
        modifier = modifier.fillMaxSize().background(scheme.scrim.copy(alpha = alpha)),
        contentAlignment = Alignment.Center
    ) {
        RenderChildren(component.children, ctx)
    }
}

@Composable
fun ScrimRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val scheme = ctx.theme.colorScheme
    val alpha = component.propFloat("opacity", 0.32f).coerceIn(0f, 1f)
    Box(modifier = modifier.fillMaxSize().background(scheme.scrim.copy(alpha = alpha))) {
        RenderChildren(component.children, ctx)
    }
}

@Composable
fun BannerSurfaceRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val scheme = ctx.theme.colorScheme
    val level = component.propString("level") ?: "info"
    val visuals = feedbackVisuals(level, ctx)
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = visuals.containerColor,
        tonalElevation = 0.dp
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = visuals.icon, contentDescription = level, tint = visuals.contentColor, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                RenderChildren(component.children, ctx)
            }
        }
    }
}

@Composable
fun ToolbarRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val showBack = component.propBool("showBack", false)
    BarBaseRenderer(component, ctx, modifier, defaultTitle = null, showBack = showBack)
}

@Composable
fun AppBarRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    BarBaseRenderer(component, ctx, modifier, defaultTitle = "App", showBack = true)
}

@Composable
fun TopBarRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    BarBaseRenderer(component, ctx, modifier, defaultTitle = null, showBack = false)
}

@Composable
fun HeaderRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    BarBaseRenderer(component, ctx, modifier, defaultTitle = "Header", showBack = false, showActions = false)
}

@Composable
fun FooterRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    BarBaseRenderer(component, ctx, modifier, defaultTitle = null, showBack = false, showActions = false)
}

@Composable
fun SidebarRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val scheme = ctx.theme.colorScheme
    val container = StyleResolver.resolveColor(component.style.backgroundColor, scheme, scheme.surface)
    val onContainer = StyleResolver.resolveColor(component.style.textColor, scheme, scheme.onSurface)
    val width = component.style.width.let { StyleResolver.resolveDp(it) } ?: 240.dp
    Surface(
        modifier = modifier.width(width).fillMaxHeight(),
        color = container,
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.fillMaxHeight().padding(vertical = 8.dp)) {
            RenderChildren(component.children, ctx)
        }
    }
}

@Composable
fun PanelRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    SurfaceContainerRenderer(component, ctx, modifier, defaultElevation = 0f)
}

@Composable
fun ElevatedSurfaceRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    SurfaceContainerRenderer(component, ctx, modifier, defaultElevation = 6f)
}

@Composable
fun FlatSurfaceRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    SurfaceContainerRenderer(component, ctx, modifier, defaultElevation = 0f)
}

@Composable
fun OutlinedSurfaceRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    SurfaceContainerRenderer(component, ctx, modifier, defaultElevation = 0f, outline = true)
}

@Composable
fun GlassCardRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val scheme = ctx.theme.colorScheme
    val shape = StyleResolver.resolveShape(component.style).let { if (it == RectangleShape) RoundedCornerShape(14.dp) else it }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .blur(16.dp)
            .background(Color.White.copy(alpha = 0.15f), shape)
            .border(1.dp, Color.White.copy(alpha = 0.3f), shape)
            .padding(16.dp)
    ) {
        RenderChildren(component.children, ctx)
    }
}

@Composable
fun NeumorphicRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val scheme = ctx.theme.colorScheme
    val base = StyleResolver.resolveColor(component.style.backgroundColor, scheme, scheme.surface)
    val shape = RoundedCornerShape(20.dp)
    val isDark = scheme.background.luminance() < 0.5f
    val lightShadow = if (isDark) Color.White.copy(alpha = 0.08f) else Color.White
    val darkShadow = if (isDark) Color.Black.copy(alpha = 0.5f) else Color.Black.copy(alpha = 0.15f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(base, shape)
            .drawBehind {
                drawRect(lightShadow, topLeft = Offset.Zero, size = Size(size.width, size.height * 0.5f))
            }
            .border(1.dp, darkShadow.copy(alpha = 0.1f), shape)
            .shadow(8.dp, shape, ambientColor = darkShadow, spotColor = lightShadow)
            .padding(16.dp)
    ) {
        RenderChildren(component.children, ctx)
    }
}

@Composable
fun GradientSurfaceRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val scheme = ctx.theme.colorScheme
    val shape = StyleResolver.resolveShape(component.style).let { if (it == RectangleShape) RoundedCornerShape(10.dp) else it }
    val gradient = Brush.verticalGradient(listOf(scheme.primary, scheme.secondary))
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(gradient, shape)
            .padding(16.dp)
    ) {
        RenderChildren(component.children, ctx)
    }
}

@Composable
fun MeshSurfaceRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val scheme = ctx.theme.colorScheme
    val shape = StyleResolver.resolveShape(component.style).let { if (it == RectangleShape) RoundedCornerShape(10.dp) else it }
    val mesh = Brush.linearGradient(
        listOf(scheme.primary, scheme.tertiary, scheme.secondary, scheme.primary.copy(alpha = 0.6f))
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(mesh, shape)
            .padding(16.dp)
    ) {
        RenderChildren(component.children, ctx)
    }
}

@Composable
fun PatternSurfaceRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val scheme = ctx.theme.colorScheme
    val base = StyleResolver.resolveColor(component.style.backgroundColor, scheme, scheme.surfaceVariant)
    val dotColor = scheme.outline.copy(alpha = 0.3f)
    val shape = StyleResolver.resolveShape(component.style).let { if (it == RectangleShape) RoundedCornerShape(10.dp) else it }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(base, shape)
            .drawBehind {
                val step = 24f
                var x = step
                while (x < size.width) {
                    var y = step
                    while (y < size.height) {
                        drawCircle(dotColor, radius = 2f, center = Offset(x, y))
                        y += step
                    }
                    x += step
                }
            }
            .padding(16.dp)
    ) {
        RenderChildren(component.children, ctx)
    }
}

@Composable
fun BlurContainerRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val scheme = ctx.theme.colorScheme
    val base = StyleResolver.resolveColor(component.style.backgroundColor, scheme, scheme.surface.copy(alpha = 0.6f))
    val shape = StyleResolver.resolveShape(component.style).let { if (it == RectangleShape) RoundedCornerShape(10.dp) else it }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .blur(20.dp)
            .background(base, shape)
            .padding(16.dp)
    ) {
        RenderChildren(component.children, ctx)
    }
}

@Composable
fun ShadowBoxRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val scheme = ctx.theme.colorScheme
    val base = StyleResolver.resolveColor(component.style.backgroundColor, scheme, scheme.surface)
    val shape = StyleResolver.resolveShape(component.style).let { if (it == RectangleShape) RoundedCornerShape(6.dp) else it }
    val elev = component.style.elevation.takeIf { it > 0f }?.dp ?: 8.dp
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(base, shape)
            .shadow(elev, shape)
            .padding(16.dp)
    ) {
        RenderChildren(component.children, ctx)
    }
}

@Composable
fun BorderBoxRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val scheme = ctx.theme.colorScheme
    val base = StyleResolver.resolveColor(component.style.backgroundColor, scheme, Color.Transparent)
    val borderColor = StyleResolver.resolveColor(component.style.borderColor, scheme, scheme.outline)
    val bw = component.style.borderWidth.takeIf { it > 0f }?.dp ?: 1.dp
    val shape = StyleResolver.resolveShape(component.style).let { if (it == RectangleShape) RoundedCornerShape(6.dp) else it }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(base, shape)
            .border(bw, borderColor, shape)
            .padding(16.dp)
    ) {
        RenderChildren(component.children, ctx)
    }
}

@Composable
fun RoundedSurfaceRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val scheme = ctx.theme.colorScheme
    val container = StyleResolver.resolveColor(component.style.backgroundColor, scheme, scheme.surface)
    val radius = component.style.cornerRadius.takeIf { it > 0f }?.dp ?: 16.dp
    val elev = component.style.elevation.takeIf { it > 0f }?.dp ?: 1.dp
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(radius),
        color = container,
        tonalElevation = elev,
        shadowElevation = elev
    ) {
        Box(modifier = Modifier.padding(16.dp)) {
            RenderChildren(component.children, ctx)
        }
    }
}

@Composable
fun TonalSurfaceRenderer(component: UIComponent, ctx: RenderContext, modifier: Modifier = Modifier) {
    val scheme = ctx.theme.colorScheme
    val container = StyleResolver.resolveColor(component.style.backgroundColor, scheme, scheme.surfaceVariant)
    val shape = StyleResolver.resolveShape(component.style).let { if (it == RectangleShape) RoundedCornerShape(10.dp) else it }
    val elev = component.style.elevation.takeIf { it > 0f }?.dp ?: 3.dp
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = container,
        tonalElevation = elev
    ) {
        Box(modifier = Modifier.padding(16.dp)) {
            RenderChildren(component.children, ctx)
        }
    }
}
