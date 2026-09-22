package com.ai.assistance.quro.genui.sdk.render

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ai.assistance.quro.genui.sdk.animation.AnimatedEntrance
import com.ai.assistance.quro.genui.sdk.components.BuiltinComponents
import com.ai.assistance.quro.genui.sdk.dsl.UIComponent
import com.ai.assistance.quro.genui.sdk.dsl.UISpec
import com.ai.assistance.quro.genui.sdk.interaction.ActionHost
import com.ai.assistance.quro.genui.sdk.style.GenUITheme

/**
 * GenUI 渲染入口 - 顶层 Composable
 * 接收 UISpec 并渲染整个界面
 *
 * @param spec UI 规范描述
 * @param modifier 外层 Modifier
 * @param host Action 宿主，用于处理交互事件
 * @param theme GenUI 主题
 * @param registry 组件注册表
 */
@Composable
fun GenUIRenderer(
    spec: UISpec,
    modifier: Modifier = Modifier,
    host: ActionHost,
    theme: GenUITheme = GenUITheme.Light,
    registry: ComponentRegistry = BuiltinComponents.sharedRegistry()
) {
    // 注意：这里是入口函数，完整实现需要结合 ActionExecutor、GenUIStateStore、
    // DialogHolder、FontProvider 等创建 RenderContext
    // 为保持 render 模块独立性，此处提供基础入口
    // 实际使用时建议通过 GenUI 入口类创建完整的渲染环境

    // 渲染根组件 — 外层 Box 提供确定的尺寸约束，
    // 防止内部 scroll 组件在无限高度下测量崩溃
    com.ai.assistance.quro.genui.sdk.interaction.FormStateHost(content = {
        Box(modifier = modifier.fillMaxSize()) {
            RenderNode(spec.root, rememberRenderContext(spec, host, theme, registry))
        }
    })

    // 渲染对话框叠加层
    GenUIDialogOverlay(rememberRenderContext(spec, host, theme, registry))
}

/**
 * 创建并缓存 RenderContext
 * 实际项目中应使用 remember + compositionLocal 等方式管理
 */
@Composable
private fun rememberRenderContext(
    spec: UISpec,
    host: ActionHost,
    theme: GenUITheme,
    registry: ComponentRegistry
): RenderContext {
    // 简化实现 - 实际项目中应使用 remember + derived state
    // 这里为了模块独立性，使用即时创建
    val stateStore = com.ai.assistance.quro.genui.sdk.state.GenUIStateStore(spec.state)
    val dialogHolder = com.ai.assistance.quro.genui.sdk.state.DialogHolder()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val executor = com.ai.assistance.quro.genui.sdk.interaction.ActionExecutor(host, stateStore, dialogHolder, scope)

    return RenderContext(
        theme = theme,
        state = stateStore,
        executor = executor,
        dialogHolder = dialogHolder,
        fontProvider = com.ai.assistance.quro.genui.sdk.style.FontProvider,
        registry = registry,
        scope = scope
    )
}

/**
 * 渲染单个组件节点
 * 处理可见性、点击事件、样式、动画等通用逻辑
 */
@Composable
fun RenderNode(component: UIComponent, ctx: RenderContext) {
    if (!component.visible) return

    val clickHandler = ctx.clickHandler(component)

    val content: @Composable () -> Unit = {
        ctx.registry.resolve(component.type).invoke(component, ctx)
    }

    // 变体裂变：palette/shape/density/mood → 样式覆盖；animate → 入场动效
    val variantStyle = com.ai.assistance.quro.genui.sdk.components.ComponentVariants.applyToStyle(component.style, component.properties)
    val variantAnimation = com.ai.assistance.quro.genui.sdk.components.ComponentVariants.resolveAnimation(component.properties, component.animation)

    val wrapped: @Composable () -> Unit = {
        var modifier = StyleResolver.baseModifier(variantStyle, ctx)
            .then(StyleResolver.resolveSemantics(component.style))

        if (clickHandler != null) {
            modifier = modifier.then(Modifier.clickable(onClick = clickHandler))
        }

        Box(modifier = modifier, contentAlignment = Alignment.TopStart) {
            content()
        }
    }

    // 动画（组件声明优先，变体声明兜底）
    if (variantAnimation != null) {
        AnimatedEntrance(animation = variantAnimation) {
            wrapped()
        }
    } else {
        wrapped()
    }
}

/**
 * 渲染子组件列表
 * 使用 movableGroup 管理每个子组件的身份
 */
@Composable
fun RenderChildren(children: List<UIComponent>, ctx: RenderContext) {
    children.forEach { child ->
        val key = child.id ?: "${child.type}_${child.hashCode()}"
        androidx.compose.runtime.key(key) {
            RenderNode(child, ctx)
        }
    }
}

/**
 * 对话框叠加层
 * 监听 DialogHolder 中的对话框列表，渲染最顶层的对话框
 */
@Composable
fun GenUIDialogOverlay(ctx: RenderContext) {
    val dialogs by ctx.dialogHolder.dialogs.collectAsState()
    val currentDialog = dialogs.lastOrNull() ?: return

    val onDismiss: () -> Unit = {
        ctx.executor.execute(currentDialog.events["onDismiss"], currentDialog)
        ctx.dialogHolder.dismiss()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        val shape = StyleResolver.resolveShape(currentDialog.style)
        val bgColor = StyleResolver.resolveColor(
            currentDialog.style.backgroundColor,
            ctx.theme.colorScheme,
            ctx.theme.colorScheme.surfaceContainer
        )

        Surface(
            shape = shape,
            color = bgColor,
            tonalElevation = androidx.compose.ui.unit.Dp(6f),
            shadowElevation = androidx.compose.ui.unit.Dp(0f)
        ) {
            val padding = StyleResolver.resolvePadding(currentDialog.style.padding)
            Box(modifier = Modifier.padding(padding)) {
                RenderChildren(currentDialog.children, ctx)
            }
        }
    }
}
