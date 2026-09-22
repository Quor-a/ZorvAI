package com.ai.assistance.quro.genui.sdk.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.ai.assistance.quro.genui.sdk.dsl.ComponentTypes
import com.ai.assistance.quro.genui.sdk.dsl.UIComponent
import com.ai.assistance.quro.genui.sdk.render.RenderContext
import com.ai.assistance.quro.genui.sdk.render.StyleResolver

/**
 * 文本组件类型常量
 */
const val TEXT_TYPE = ComponentTypes.TEXT

/**
 * Text文本组件渲染器
 *
 * 支持的属性：
 * - text: 文本内容（支持数据绑定）
 *
 * 支持的样式：
 * - textColor: 文字颜色
 * - textSize: 文字大小
 * - fontWeight: 字体粗细
 * - fontFamily: 字体
 * - textAlign: 文字对齐方式
 * - letterSpacing: 字间距
 * - lineHeight: 行高
 * - maxLines: 最大行数
 * - overflow: 文字溢出处理
 * - textStyle: 文字样式类型（对应Material3 Typography）
 */
@Composable
fun TextRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val text = component.propStringResolved("text", ctx).orEmpty()
    val style = StyleResolver.buildTextStyle(component.style, ctx, ctx.theme.typography.bodyMedium)
    val maxLines = component.style.maxLines ?: Int.MAX_VALUE
    val overflow = when (component.style.overflow) {
        "ellipsis" -> TextOverflow.Ellipsis
        "clip" -> TextOverflow.Clip
        else -> TextOverflow.Visible
    }

    Text(
        text = text,
        modifier = modifier,
        style = style,
        maxLines = maxLines,
        overflow = overflow
    )
}
