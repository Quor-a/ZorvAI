package com.ai.assistance.quro.genui.sdk.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ai.assistance.quro.genui.sdk.dsl.ComponentTypes
import com.ai.assistance.quro.genui.sdk.dsl.UIComponent
import com.ai.assistance.quro.genui.sdk.render.RenderChildren
import com.ai.assistance.quro.genui.sdk.render.RenderContext
import com.ai.assistance.quro.genui.sdk.render.StyleResolver

/**
 * 对话框组件类型常量
 */
const val DIALOG_TYPE = ComponentTypes.DIALOG

/**
 * Dialog对话框组件渲染器
 *
 * 支持的属性：
 * - visible: 是否显示（支持数据绑定）
 * - dismissOnBackPress: 按返回键是否关闭
 * - dismissOnClickOutside: 点击外部是否关闭
 *
 * 支持的样式：
 * - backgroundColor: 背景颜色
 * - cornerRadius: 圆角
 * - elevation: 阴影高度
 * - padding: 内边距
 *
 * 支持子组件，子组件将在对话框内容区域渲染
 */
@Composable
fun DialogRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val visible = component.propBool("visible", true)
    if (!visible) return

    val dismissOnBackPress = component.propBool("dismissOnBackPress", true)
    val dismissOnClickOutside = component.propBool("dismissOnClickOutside", true)

    val shape = StyleResolver.resolveShape(component.style)
    val containerColor = StyleResolver.resolveColor(
        component.style.backgroundColor,
        ctx.theme.colorScheme,
        ctx.theme.colorScheme.surfaceContainer
    )
    val padding = StyleResolver.resolvePadding(component.style.padding)
    val elevation = component.style.elevation.takeIf { it > 0f }?.dp ?: 6.dp

    Dialog(
        onDismissRequest = {
            component.events["onDismiss"]?.let { event ->
                ctx.executor.execute(event, component)
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = dismissOnBackPress,
            dismissOnClickOutside = dismissOnClickOutside
        )
    ) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            color = containerColor,
            tonalElevation = elevation,
            shadowElevation = elevation
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(padding),
                verticalArrangement = Arrangement.Top,
                horizontalAlignment = Alignment.Start
            ) {
                RenderChildren(
                        children = component.children,
                        ctx = ctx
                    )
            }
        }
    }
}
