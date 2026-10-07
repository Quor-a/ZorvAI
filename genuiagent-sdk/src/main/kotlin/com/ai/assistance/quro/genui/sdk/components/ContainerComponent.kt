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
import com.ai.assistance.quro.genui.sdk.dsl.UIComponent
import com.ai.assistance.quro.genui.sdk.render.RenderChildren
import com.ai.assistance.quro.genui.sdk.render.GenUILayoutAlign
import com.ai.assistance.quro.genui.sdk.render.GenUILayoutGuard
import com.ai.assistance.quro.genui.sdk.render.RenderContext

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
    val spacing = GenUILayoutGuard.spacing(component.propFloatOrNull("spacing"))

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
            GenUILayoutGuard.fixedDp(width, GenUILayoutGuard.MAX_WIDTH_DP)?.let {
                spacerModifier = spacerModifier.width(it.dp)
            }
            GenUILayoutGuard.fixedDp(height, GenUILayoutGuard.MAX_HEIGHT_DP)?.let {
                spacerModifier = spacerModifier.height(it.dp)
            }
            Spacer(modifier = spacerModifier)
        }
        ComponentTypes.SCROLL -> {
            val isHorizontal = GenUILayoutGuard.isHorizontal(component.propString("direction"))
            val scrollState: ScrollState = rememberScrollState()
            val scrollModifier = if (isHorizontal) {
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

// ==================================================================
// 对齐语义统一委托到 [GenUILayoutAlign]（2026-10-06）
//
// 本文件此前有第二份 XxxAlign 实现，与 LayoutTypographyButtonRenderers.kt 的
// ltbXxx 对同一输入行为不一致（boxAlign 未指定时一份 Center 一份 TopStart）。
// 「组件虽然多但是不齐」有一半出在这份重复实现上。现在只保留委托。
// ==================================================================

private fun verticalArrangement(value: String?, spacing: Float): Arrangement.Vertical =
    GenUILayoutAlign.verticalArrangement(value, GenUILayoutGuard.spacing(spacing).dp)

private fun horizontalArrangement(value: String?, spacing: Float): Arrangement.Horizontal =
    GenUILayoutAlign.horizontalArrangement(value, GenUILayoutGuard.spacing(spacing).dp)

private fun horizontalAlign(value: String?): Alignment.Horizontal =
    GenUILayoutAlign.horizontalAlign(value)

private fun verticalAlign(value: String?): Alignment.Vertical =
    GenUILayoutAlign.verticalAlign(value)

private fun boxAlign(value: String?): Alignment =
    GenUILayoutAlign.boxAlign(value)
