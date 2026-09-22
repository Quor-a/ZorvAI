package com.ai.assistance.quro.genui.sdk.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ai.assistance.quro.genui.sdk.dsl.ComponentTypes
import com.ai.assistance.quro.genui.sdk.dsl.UIComponent
import com.ai.assistance.quro.genui.sdk.render.RenderContext
import com.ai.assistance.quro.genui.sdk.render.StyleResolver

/**
 * 按钮组件类型常量
 */
const val BUTTON_TYPE = ComponentTypes.BUTTON

/**
 * Button按钮组件渲染器
 *
 * 支持的属性：
 * - text: 按钮文本（支持数据绑定）
 * - icon: 按钮图标名称
 * - iconPosition: 图标位置（left/right，默认left）
 * - enabled: 是否启用
 *
 * 支持的样式：
 * - backgroundColor: 背景颜色
 * - textColor: 文字颜色
 * - cornerRadius: 圆角
 * - padding: 内边距
 */
@Composable
fun ButtonRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val text = component.propStringResolved("text", ctx) ?: ""
    val iconName = component.propString("icon")
    val iconPosition = component.propString("iconPosition") ?: "left"
    val enabled = component.propBool("enabled", true)

    val containerColor = StyleResolver.resolveColor(
        component.style.backgroundColor,
        ctx.theme.colorScheme,
        ctx.theme.colorScheme.primary
    )
    val contentColor = StyleResolver.resolveColor(
        component.style.textColor,
        ctx.theme.colorScheme,
        ctx.theme.colorScheme.onPrimary
    )

    val shape = StyleResolver.resolveShape(component.style)

    Button(
        onClick = {
            component.events["onClick"]?.let { event ->
                ctx.executor.execute(event, component)
            }
        },
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        shape = shape
    ) {
        val hasIcon = iconName != null
        val hasText = !text.isNullOrEmpty()

        if (hasIcon && iconPosition == "left") {
            IconRenderer(component, ctx)
        }
        if (hasText) {
            Text(text = text)
        }
        if (hasIcon && iconPosition == "right") {
            IconRenderer(component, ctx)
        }
    }
}

/**
 * 内部图标渲染辅助函数
 */
@Composable
private fun IconRenderer(
    component: UIComponent,
    ctx: RenderContext
) {
    val tint = StyleResolver.resolveColor(
        component.style.textColor,
        ctx.theme.colorScheme,
        ctx.theme.colorScheme.primary
    )
    val iconName = component.propString("icon")
    Icon(
        imageVector = IconMapper.map(iconName),
        contentDescription = iconName,
        modifier = Modifier.size(24.dp),
        tint = tint
    )
}
