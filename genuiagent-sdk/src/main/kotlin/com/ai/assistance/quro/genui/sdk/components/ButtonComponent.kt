package com.ai.assistance.quro.genui.sdk.components

import androidx.compose.foundation.layout.heightIn
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
        // 触控目标 ≥44dp：M3 Button 默认只有 40dp，低于 Apple HIG / 无障碍 44dp 底线，
        // 手指稍粗就点不中。
        modifier = modifier.heightIn(min = 44.dp),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        shape = shape,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 20.dp, vertical = 10.dp
        )
    ) {
        val hasIcon = iconName != null
        val hasText = !text.isNullOrEmpty()

        if (hasIcon && iconPosition == "left") {
            IconRenderer(component, ctx)
        }
        if (hasIcon && hasText) {
            androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
        }
        if (hasText) {
            // 文字跟随组件字号（此前吃 M3 Button 默认 labelLarge，
            // 模型写 textSize 时按钮文字纹丝不动，与卡片字号对不上）
            Text(
                text = text,
                style = com.ai.assistance.quro.genui.sdk.render.StyleResolver.buildTextStyle(
                    component.style, ctx, androidx.compose.material3.MaterialTheme.typography.labelLarge
                ),
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
            )
        }
        if (hasIcon && iconPosition == "right") {
            if (hasText) androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
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
        modifier = Modifier.size(18.dp),
        tint = tint
    )
}
