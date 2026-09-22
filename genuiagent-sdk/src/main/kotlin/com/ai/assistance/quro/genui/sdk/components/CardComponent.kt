package com.ai.assistance.quro.genui.sdk.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
    val scheme = ctx.theme.colorScheme
    val containerColor = StyleResolver.resolveColor(
        component.style.backgroundColor,
        scheme,
        scheme.surface
    )
    val elevation = component.style.elevation.dp

    // ── 层次感：卡片必须能从页面底色里"浮"出来 ──
    // 此前卡片是 surface 白底 + elevation=0 + 无描边，铺在纸色页面上完全看不出边界，
    // 一屏卡片糊成一片（用户反馈的「层次感也没有」）。没有阴影时补一根细描边兜底，
    // 靠「底色差 + 一根线」分层，而不是靠厚阴影。
    val border = if (component.style.elevation <= 0.5f) {
        androidx.compose.foundation.BorderStroke(1.dp, scheme.outlineVariant)
    } else null

    // ── 内边距：卡片自己负责，默认 16dp（8pt 网格）──
    // 注意节点级 padding 是加在卡片**外面**的（见 StyleResolver.baseModifier），
    // 所以卡片内必须另给一份，否则标题/正文直接贴卡片边，视觉上根本不成卡片。
    // （RenderNode 对 card 已跳过外层 padding，避免两份叠加。）
    val inner = component.style.padding
    val innerPad = if (inner.start > 0f || inner.top > 0f || inner.end > 0f || inner.bottom > 0f) {
        Modifier.padding(
            start = inner.start.dp, top = inner.top.dp,
            end = inner.end.dp, bottom = inner.bottom.dp
        )
    } else Modifier.padding(16.dp)

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = containerColor
        ),
        elevation = androidx.compose.material3.CardDefaults.cardElevation(
            defaultElevation = elevation
        ),
        border = border
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().then(innerPad),
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
                    ).copy(color = scheme.semantic("muted") ?: scheme.onSurfaceVariant)
                )
                Spacer(modifier = Modifier.height(4.dp))
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
                Spacer(modifier = Modifier.height(if (subtitle != null) 6.dp else 12.dp))
            }

            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = StyleResolver.buildTextStyle(
                        component.style,
                        ctx,
                        ctx.theme.typography.bodySmall
                    ).copy(color = scheme.onSurfaceVariant)
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            RenderChildren(
                children = component.children,
                ctx = ctx
            )
        }
    }
}
