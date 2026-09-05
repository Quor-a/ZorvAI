package com.ai.assistance.quro.core.ui.card.host

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import com.ai.assistance.quro.core.ui.card.registry.CardRegistry
import com.ai.assistance.quro.core.ui.card.registry.CardRenderer
import com.ai.assistance.quro.core.ui.card.registry.CardState
import com.ai.assistance.quro.core.ui.card.render.BackendKind
import com.ai.assistance.quro.core.ui.card.render.CanvasBackend
import com.ai.assistance.quro.core.ui.card.spec.Action
import com.ai.assistance.quro.core.ui.card.spec.CardData
import com.ai.assistance.quro.core.ui.card.spec.CardSpec
import com.ai.assistance.quro.core.ui.card.spec.ColorToken

/**
 * 宿主层：主题 Token · 事件总线 · 生命周期 · 缓存 · 复用。
 *
 * 与对话框消息流对接（RecyclerView / LazyColumn）时最容易翻车的点都在这里统一处理：
 * 复用池、高度缓存、局部刷新、滚动策略、主题解析。
 */

/** 主题令牌解析器：把 [ColorToken] 解析成具体 Color（暗色/字体缩放自动生效）。 */
object StyleTokenResolver {
    lateinit var resolveToken: (ColorToken) -> Color

    fun resolve(token: ColorToken): Color = if (::resolveToken.isInitialized) resolveToken(token) else Color.Gray
}

/** 事件总线：卡片点击 → ActionBus → CardHost → 宿主决策（发消息/调工具/开二级页/本地变更）。 */
object ActionBus {
    /** 宿主注入的处理器；返回 true 表示已消费。 */
    var handler: ((action: Action, card: CardSpec) -> Boolean)? = null

    fun post(action: Action, card: CardSpec) {
        handler?.invoke(action, card)
    }
}

/** 高度缓存：测量完的高度写进来，流式重绘只走 payload 局部刷新，不 notifyDataSetChanged。 */
object HeightCache {
    private val map = LinkedHashMap<String, Int>() // cardId -> heightPx

    fun get(cardId: String): Int? = map[cardId]
    fun put(cardId: String, h: Int) { map[cardId] = h }
    fun clear(cardId: String) = map.remove(cardId)
}

/** 离屏 Bitmap 缓存：按 cardId 存图表位图，滑出即释放。 */
object BitmapCache {
    private val map = LinkedHashMap<String, Bitmap>()

    fun get(cardId: String): Bitmap? = map[cardId]
    fun put(cardId: String, b: Bitmap) { map[cardId] = b }
    fun release(cardId: String) = map.remove(cardId)?.recycle()
}

/** 卡片气泡复用池标识（与消息流 RecycledViewPool 对接时使用）。 */
object CardPool {
    const val VIEW_TYPE = 9001
}

/**
 * Compose 挂载点：把一张 [CardSpec] 渲染进对话框。
 * 上层只传 spec，底层底座（canvas/view/gl）由这里按 renderHint 选，编排层无感。
 *
 * 渲染流程（完全自研，不依赖任何内置/三方成品卡片控件）：
 *   1) 按 type 取白名单渲染器；取不到 → 降级 Markdown 气泡（绝不崩对话框）。
 *   2) 渲染器自写 measure → layout。
 *   3) 用 Compose Canvas + 自写 CanvasBackend 走渲染器的 render()，自己下绘制指令。
 *
 * 注意：本挂载点与已有的 DynamicUiBlock / SurfaceHost 完全独立，是两个功能，不合并。
 */
@Composable
fun CardSurface(
    spec: CardSpec,
    modifier: Modifier = Modifier,
    onAction: (Action, CardSpec) -> Unit = { a, c -> ActionBus.post(a, c) },
) {
    val renderer = remember(spec.type) { CardRegistry.resolveOrNull(spec.type) }
    if (renderer == null) {
        MarkdownFallback(spec, modifier)
        return
    }
    // 受控强转：state 由同一 renderer.createInitialState() 产生，类型一致，安全。
    @Suppress("UNCHECKED_CAST")
    val r = renderer as CardRenderer<CardState>
    val measurer = rememberTextMeasurer()
    val state = remember(spec.id) { r.createInitialState() }

    // 主题令牌：把语义色映射到当前 Material 主题（暗色 / 字体缩放自动生效）。
    // 端上自写绘制，不依赖任何内置/三方成品卡片控件。
    val cs = MaterialTheme.colorScheme
    StyleTokenResolver.resolveToken = { token ->
        when (token) {
            ColorToken.Primary -> cs.primary
            ColorToken.OnPrimary -> cs.onPrimary
            ColorToken.Secondary -> cs.secondary
            ColorToken.OnSecondary -> cs.onSecondary
            ColorToken.Surface -> cs.surface
            ColorToken.OnSurface -> cs.onSurface
            ColorToken.SurfaceVariant -> cs.surfaceVariant
            ColorToken.OnSurfaceVariant -> cs.onSurfaceVariant
            ColorToken.Background -> cs.background
            ColorToken.OnBackground -> cs.onBackground
            ColorToken.Outline -> cs.outline
            ColorToken.Success -> Color(0xFF4CAF50)
            ColorToken.Warning -> Color(0xFFFF9800)
            ColorToken.Danger -> Color(0xFFF44336)
            ColorToken.Info -> cs.primary
        }
    }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        // 预测量：用可用宽度算出卡片高度，否则 Canvas 默认 0 高不显示。
        // 注意本环境 BOM 2026.01.01 的 Dp↔px 扩展（roundToPx/toDp）在此作用域不可见，
        // 直接手算：px = dpValue × density（density 是 Float 密度因子）。
        val densityFactor = LocalDensity.current.density
        val maxWidthPx = (maxWidth.value * densityFactor).coerceAtLeast(1f)
        val measured = r.measure(spec, state, maxWidthPx)
        val layout = r.layout(spec, state, measured)
        val heightDp = Dp(measured.height / densityFactor)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(heightDp)
                .pointerInput(Unit) {
                    // 点击命中：交给渲染器做 hitTest，命中则回传对应 Action（按钮组经 onAction→ActionBus→消息流）。
                    detectTapGestures { offset ->
                        val hit = r.hitTest(offset, layout, state)
                        if (hit != null) {
                            val action = (spec.data as? CardData.Form)?.buttons?.getOrNull(hit.actionIndex)?.action
                                ?: spec.actions.getOrNull(hit.actionIndex)
                            if (action != null) onAction(action, spec)
                        }
                    }
                }
        ) {
            val backend = CanvasBackend(
                scope = this,
                tokenResolver = { StyleTokenResolver.resolve(it) },
                textMeasurer = measurer,
            )
            r.render(backend, spec, layout, state)
        }
    }
}

/** 未注册类型的降级渲染（Markdown 气泡）。 */
@Composable
private fun MarkdownFallback(spec: CardSpec, modifier: Modifier) {
    androidx.compose.material3.Text(
        text = "[不支持的卡片类型：${spec.type}]",
        modifier = modifier,
        color = Color.Gray,
    )
}
