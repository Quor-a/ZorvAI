package com.ai.assistance.quro.genui.sdk.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ai.assistance.quro.genui.sdk.dsl.ComponentTypes
import com.ai.assistance.quro.genui.sdk.dsl.Dimension
import com.ai.assistance.quro.genui.sdk.dsl.UIComponent
import com.ai.assistance.quro.genui.sdk.render.RenderChildren
import com.ai.assistance.quro.genui.sdk.render.RenderNode
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
            // 🔴 2026-10-26「GenUI 排版组件乱七八糟」真元（用户截图的天气卡）：
            //   旧实现直接把 Row 的子节点平铺给 RenderChildren，而 RenderNode 会给每个节点套一层
            //   baseModifier。模型写 `width: {type:"weight", fraction:1}` 的列，
            //   baseModifier 在 Row 上下文里仍按 `fillMaxWidth(fraction)` 处理 ——
            //   那乘的是**整行宽度**，于是第一个 weight=1 的子节点把整行吃干净，
            //   后面的兄弟只剩 0 宽，里面的 Text 被压成「一个字一行」的竖排
            //   （截图里右侧「最低 / 晴间多云 / °C」竖着的那一坨）。
            //
            //   正确语义：Row 下的 weight 必须交给 RowScope.weight 分配剩余空间。
            //   这里自己接管子节点：带 weight 的包一层 Box(Modifier.weight(f))，
            //   并把它的 width 清空（避免 baseModifier 在窄盒子里再乘一次分数，越缩越窄）；
            //   不带 weight 的照旧按内容测量（intrinsic，绝不会去抢别人的空间）。
            val kids = component.children
            val weights = kids.map { it.style.width as? Dimension.Weight }
            val hasWeight = weights.any { it != null }
            Row(
                // 旧实现 fillMaxSize() 会把高度也撑满：放进纵向滚动容器时高度约束无界，
                // 行高被拉成整屏（下方元素被顶出视野）。行只该吃满宽度。
                modifier = modifier.fillMaxWidth(),
                horizontalArrangement = horizontalArrangement(component.style.arrangement, spacing),
                verticalAlignment = verticalAlign(component.style.crossAlignment)
            ) {
                if (!hasWeight) {
                    RenderChildren(
                        children = kids,
                        ctx = ctx
                    )
                } else {
                    kids.forEachIndexed { index, child ->
                        val w = weights[index]
                        key(child.id ?: "${child.type}_${child.hashCode()}") {
                            if (w == null) {
                                RenderNode(child, ctx)
                            } else {
                                Box(Modifier.weight(w.fraction.coerceIn(0.05f, 1f))) {
                                    RenderNode(
                                        child.copy(style = child.style.copy(width = null)),
                                        ctx
                                    )
                                }
                            }
                        }
                    }
                }
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
