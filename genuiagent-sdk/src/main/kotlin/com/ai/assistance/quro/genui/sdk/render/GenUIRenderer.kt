package com.ai.assistance.quro.genui.sdk.render

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
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
    // 只建一次上下文：此前这里建了两次（渲染树与对话框各一个），
    // 两个 GenUIStateStore / DialogHolder 互不可见 → 表单输入与对话框对不上。
    val ctx = rememberRenderContext(spec, host, theme, registry)

    PageCanvas(theme = theme, rootType = spec.root.type, modifier = modifier) {
        com.ai.assistance.quro.genui.sdk.interaction.FormStateHost(content = {
            RenderNode(spec.root, ctx)
        })
    }

    // 渲染对话框叠加层
    GenUIDialogOverlay(ctx)
}

/**
 * 页面画布 —— 设计契约的**强制层**。
 *
 * 为什么必须有这一层：此前入口只有一个 `Box(fillMaxSize())`，AI 产出的组件直接"悬空"铺在
 * 宿主给的容器里 —— 没有页面底色、没有页边距、没有阅读宽度。模型再怎么写，看起来都是
 * 一堆未归位的方块（用户反馈的「不会选择背景、组件不好看」有一半出在这里，而不是出在组件本身）。
 *
 * 本层兜底三件事，让**任何** AI 产出都至少落在合理的页面上：
 *  1. 页面底色：用主题 background（纸色），而不是透出宿主容器 → 内容与页面有明暗关系；
 *  2. 页边距：水平 16dp（8pt 网格），上下 16/24dp —— 内容永不贴边；
 *  3. 阅读宽度：上限 720dp 且居中，平板/横屏下不会拉成一行超长文本。
 *
 * 滚动也在这里兜：根节点自带 scroll 时不再套外层滚动（避免双重滚动抢手势）。
 */
@Composable
private fun PageCanvas(
    theme: GenUITheme,
    rootType: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val scheme = theme.colorScheme
    val rootSelfScrolls = rootType.lowercase() in setOf("scroll", "list", "lazy_column", "column_scroll", "virtual_list")
    Surface(color = scheme.background, modifier = modifier.fillMaxSize()) {
        val page = Modifier.fillMaxWidth().widthIn(max = 720.dp)
        if (rootSelfScrolls) {
            Box(Modifier.fillMaxSize()) {
                Column(
                    page.align(Alignment.TopCenter)
                        .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp)
                ) { content() }
            }
        } else {
            // 内容可能远高于一屏（AI 常一次产出整页）→ 页面层负责滚动
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Column(
                    page.align(Alignment.TopCenter)
                        .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp)
                ) { content() }
            }
        }
    }
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
 * 自己负责内边距的容器类型：它们的 padding 语义是「容器内边距」而不是「外边距」，
 * 因此不在节点层再叠一层 padding（否则内容贴自己边、容器又被撑窄）。
 */
private val SELF_PADDED_TYPES = setOf(
    "card", "surface", "panel", "bottom_sheet", "app_bar", "dialog",
    "kpi_card", "info_card", "media_card", "list_item", "section"
)

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
        // 自持内边距的容器（card 等）跳过节点级 padding：
        // baseModifier 的 padding 是加在组件**外面**的，而 card 的 padding 语义是"卡片内边距"。
        // 两层都加 → 卡片既被外面撑窄、内容又贴自己边，看起来完全不成卡片。
        // 交给组件自己处理（见 CardComponent 的 innerPad）。
        val nodeStyle = if (component.type.lowercase() in SELF_PADDED_TYPES) {
            variantStyle.copy(padding = com.ai.assistance.quro.genui.sdk.dsl.EdgeInsets(0f, 0f, 0f, 0f))
        } else variantStyle
        var modifier = StyleResolver.baseModifier(nodeStyle, ctx)
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
