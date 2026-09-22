package com.ai.assistance.quro.genui.sdk.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ai.assistance.quro.genui.sdk.dsl.ComponentTypes
import com.ai.assistance.quro.genui.sdk.dsl.Dimension
import com.ai.assistance.quro.genui.sdk.dsl.UIComponent
import com.ai.assistance.quro.genui.sdk.render.RenderChildren
import com.ai.assistance.quro.genui.sdk.render.RenderContext
import java.util.Locale

/**
 * 容器组件类型常量
 */
const val COLUMN_TYPE = ComponentTypes.COLUMN
const val ROW_TYPE = ComponentTypes.ROW
const val BOX_TYPE = ComponentTypes.BOX
const val SPACER_TYPE = ComponentTypes.SPACER
const val SCROLL_TYPE = ComponentTypes.SCROLL

/**
 * Container容器组件渲染器
 *
 * 支持多种容器类型：column、row、box、spacer、scroll
 *
 * 支持的属性：
 * - spacing: 子组件间距（仅column/row有效）
 * - direction: 滚动方向（仅scroll有效，vertical/horizontal，默认vertical）
 *
 * 支持的样式：
 * - arrangement: 主轴排列方式
 * - crossAlignment: 交叉轴对齐方式
 * - alignment: Box对齐方式
 * - gravity: Box对齐方式（与alignment二选一，优先gravity）
 * - width: 宽度
 * - height: 高度
 */
@Composable
fun ContainerRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    // 默认间距 8dp：模型漏写 spacing 时避免元素挤成一片或顶死
    val spacing = component.propFloat("spacing", 8f)

    when (component.type) {
        ComponentTypes.COLUMN -> {
            Column(
                modifier = modifier.fillMaxSize(),
                verticalArrangement = verticalArrangement(component.style.arrangement, spacing),
                horizontalAlignment = horizontalAlign(component.style.crossAlignment)
            ) {
                RenderChildren(
                    children = component.children,
                    ctx = ctx
                )
            }
        }
        ComponentTypes.ROW -> {
            Row(
                modifier = modifier.fillMaxSize(),
                horizontalArrangement = horizontalArrangement(component.style.arrangement, spacing),
                verticalAlignment = verticalAlign(component.style.crossAlignment)
            ) {
                RenderChildren(
                    children = component.children,
                    ctx = ctx
                )
            }
        }
        ComponentTypes.BOX -> {
            val gravity = component.style.gravity ?: component.style.alignment
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = boxAlign(gravity)
            ) {
                RenderChildren(
                    children = component.children,
                    ctx = ctx
                )
            }
        }
        ComponentTypes.SPACER -> {
            val width = component.style.width
            val height = component.style.height
            var spacerModifier: Modifier = Modifier
            if (width is Dimension.Fixed) {
                spacerModifier = spacerModifier.width(width.dp.dp)
            }
            if (height is Dimension.Fixed) {
                spacerModifier = spacerModifier.height(height.dp.dp)
            }
            Spacer(modifier = spacerModifier)
        }
        ComponentTypes.SCROLL -> {
            val direction = component.propString("direction") ?: "vertical"
            val scrollState: ScrollState = rememberScrollState()
            val scrollModifier = if (direction == "horizontal") {
                Modifier.horizontalScroll(scrollState)
            } else {
                Modifier.verticalScroll(scrollState)
            }
            // 注意：这里不使用 fillMaxSize()，避免在无限高度父容器中闪退。
            // 尺寸由外层 RenderNode 的 Box（应用了 style 的 width/height）控制。
            Column(
                modifier = modifier
                    .then(scrollModifier),
                verticalArrangement = verticalArrangement(component.style.arrangement, spacing),
                horizontalAlignment = horizontalAlign(component.style.crossAlignment)
            ) {
                RenderChildren(
                    children = component.children,
                    ctx = ctx
                )
            }
        }
    }
}

/**
 * 解析垂直方向排列方式
 */
private fun verticalArrangement(value: String?, spacing: Float): Arrangement.Vertical {
    val spacingDp = if (spacing > 0) spacing.dp else 0.dp
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
 * 解析水平方向排列方式
 */
private fun horizontalArrangement(value: String?, spacing: Float): Arrangement.Horizontal {
    val spacingDp = if (spacing > 0) spacing.dp else 0.dp
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
 * 解析水平对齐方式
 */
private fun horizontalAlign(value: String?): Alignment.Horizontal {
    val lower = value?.lowercase(Locale.ROOT)
    return when (lower) {
        "end", "right" -> Alignment.End
        "center", "stretch" -> Alignment.CenterHorizontally
        else -> Alignment.Start
    }
}

/**
 * 解析垂直对齐方式
 */
private fun verticalAlign(value: String?): Alignment.Vertical {
    val lower = value?.lowercase(Locale.ROOT)
    return when (lower) {
        "end", "bottom" -> Alignment.Bottom
        "center", "stretch" -> Alignment.CenterVertically
        else -> Alignment.Top
    }
}

/**
 * 解析Box对齐方式
 */
private fun boxAlign(value: String?): Alignment {
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
