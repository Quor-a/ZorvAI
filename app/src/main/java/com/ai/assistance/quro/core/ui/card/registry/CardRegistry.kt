package com.ai.assistance.quro.core.ui.card.registry

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.ai.assistance.quro.core.ui.card.render.RenderBackend
import com.ai.assistance.quro.core.ui.card.spec.CardSpec

/**
 * 编排层：注册表 + 自写渲染器。
 *
 * 每个卡片类型一组 [CardRenderer] 实现，全部自写测量/排版/绘制/命中测试，
 * 不依赖任何内置/三方成品卡片控件。
 */
interface CardRenderer<S : CardState> {
    /** 创建该渲染器的初始状态（供宿主持有）。 */
    fun createInitialState(): S

    /** 自写测量：给定约束返回期望尺寸（px）。`density` 是 sp→px 比率（约 2.75），
     *  measure 必须用它把 sp 字号换算成真实占的 px 行高，否则会被 Canvas 裁切（v1.0.82 cardfix6）。 */
    fun measure(spec: CardSpec, state: S, maxWidthPx: Float, density: Float): Size

    /** 自写排版：把子节点摆到坐标（px）。`density` 用于按钮/控件类按字号算 box 高度。 */
    fun layout(spec: CardSpec, state: S, size: Size, density: Float): LayoutResult

    /** 自写绘制：用 RenderBackend 自己下绘制指令。 */
    fun render(backend: RenderBackend, spec: CardSpec, result: LayoutResult, state: S)

    /** 自写命中测试：返回命中的可交互目标（按钮/链接等）。 */
    fun hitTest(p: Offset, result: LayoutResult, state: S): HitTarget?
}

/** 渲染器自身持有的可变状态（如流式进度、动画相位）。 */
interface CardState {
    /** 同一 id 的多次 patch 是否可增量合并。 */
    fun canMerge(other: CardState): Boolean = false
}

/**
 * 入场动画感知：实现此接口的渲染器状态由 [com.ai.assistance.quro.core.ui.card.host.CardSurface]
 * 逐帧驱动 0→1（约 1.4s），渲染器按相位画数字滚动/进度生长等入场动效。
 * progress 必须用 mutableStateOf 存储，Canvas 绘制读取后自动逐帧重绘。
 */
interface AnimateAware {
    fun onFrame(progress: Float)
}

/** 排版结果：记录每个可交互/可绘制元素的最终坐标框。 */
data class LayoutResult(
    val width: Float,
    val height: Float,
    val boxes: List<HitBox> = emptyList(),
    val extra: Map<String, Any?> = emptyMap(),
)

data class HitBox(
    val id: String,
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    val actionIndex: Int = -1,
)

data class HitTarget(
    val box: HitBox,
    val actionIndex: Int,
)

/**
 * type → CardRenderer 注册表 + 白名单 + 降级。
 *
 * - 未注册类型：降级为 Markdown 气泡，绝不崩对话框。
 * - version 不匹配：先过 [com.ai.assistance.quro.core.ui.card.spec.CardMigrator] 再渲染。
 */
object CardRegistry {
    private val renderers = LinkedHashMap<String, CardRenderer<out CardState>>()
    private val whitelist = LinkedHashSet<String>()

    fun register(type: String, renderer: CardRenderer<out CardState>) {
        renderers[type] = renderer
        whitelist.add(type)
    }

    fun isWhitelisted(type: String) = type in whitelist

    @Suppress("UNCHECKED_CAST")
    fun <S : CardState> get(type: String): CardRenderer<S>? =
        renderers[type] as? CardRenderer<S>

    /** 取渲染器；拿不到说明不在白名单，调用方应降级为 Markdown。 */
    fun resolveOrNull(type: String): CardRenderer<out CardState>? = renderers[type]

    fun registeredTypes(): Set<String> = LinkedHashSet(whitelist)
}
