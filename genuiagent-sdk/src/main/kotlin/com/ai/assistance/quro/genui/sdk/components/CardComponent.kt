package com.ai.assistance.quro.genui.sdk.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ai.assistance.quro.genui.sdk.dsl.ComponentTypes
import com.ai.assistance.quro.genui.sdk.dsl.UIComponent
import com.ai.assistance.quro.genui.sdk.render.RenderChildren
import com.ai.assistance.quro.genui.sdk.render.RenderContext
import com.ai.assistance.quro.genui.sdk.render.StyleResolver

/**
 * 卡片组件类型常量
 */
const val CARD_TYPE = ComponentTypes.CARD

/**
 * Card卡片组件渲染器
 *
 * 支持的属性：
 * - title: 标题文本（支持数据绑定）
 * - subtitle: 副标题文本（支持数据绑定）
 * - overline: 上标文本（支持数据绑定）
 *
 * 支持的样式：
 * - backgroundColor: 背景颜色
 * - cornerRadius: 圆角
 * - elevation: 阴影高度
 * - padding: 内边距
 *
 * 支持子组件，子组件将在标题/副标题下方渲染
 */
@Composable
fun CardRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val title = component.propStringResolved("title", ctx)
    val subtitle = component.propStringResolved("subtitle", ctx)
    val overline = component.propStringResolved("overline", ctx)

    val shape = StyleResolver.resolveShape(component.style)
    val containerColor = StyleResolver.resolveColor(
        component.style.backgroundColor,
        ctx.theme.colorScheme,
        ctx.theme.colorScheme.surface
    )
    val elevation = component.style.elevation.dp

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = containerColor
        ),
        elevation = androidx.compose.material3.CardDefaults.cardElevation(
            defaultElevation = elevation
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Top,
            horizontalAlignment = Alignment.Start
        ) {
            if (overline != null) {
                Text(
                    text = overline,
                    style = StyleResolver.buildTextStyle(
                        component.style,
                        ctx,
                        ctx.theme.typography.labelMedium
                    )
                )
                Spacer(modifier = Modifier.height(2.dp))
            }

            if (title != null) {
                Text(
                    text = title,
                    style = StyleResolver.buildTextStyle(
                        component.style,
                        ctx,
                        ctx.theme.typography.titleMedium
                    )
                )
            }

            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = StyleResolver.buildTextStyle(
                        component.style,
                        ctx,
                        ctx.theme.typography.bodySmall
                    ).copy(
                        color = StyleResolver.resolveColor(
                            null,
                            ctx.theme.colorScheme,
                            ctx.theme.colorScheme.onSurfaceVariant
                        )
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            RenderChildren(
                children = component.children,
                ctx = ctx
            )
        }
    }
}
