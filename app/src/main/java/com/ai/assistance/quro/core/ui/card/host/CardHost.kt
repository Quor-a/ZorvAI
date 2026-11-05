package com.ai.assistance.quro.core.ui.card.host

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.ai.assistance.quro.core.ui.card.compose.CardExtractors
import com.ai.assistance.quro.core.ui.card.registry.CardRegistry
import com.ai.assistance.quro.core.ui.card.spec.Action
import com.ai.assistance.quro.core.ui.card.spec.CardSpec
import com.ai.assistance.quro.core.ui.card.spec.ColorToken

/**
 * 宿主层：主题令牌解析 · 事件总线。
 *
 * 与对话框消息流对接（RecyclerView / LazyColumn）时最容易翻车的点——复用池、高度缓存、
 * 局部刷新、滚动策略、主题解析——在 Compose 世界里由布局引擎与 MaterialTheme 自动处理，
 * 无需再手写 HeightCache / BitmapCache / CardPool。
 */

/** 事件总线：卡片点击 → ActionBus → 宿主决策（发消息 / 调工具 / 开二级页 / 本地变更）。 */
object ActionBus {
    /** 宿主注入的处理器；返回 true 表示已消费。 */
    var handler: ((action: Action, card: CardSpec) -> Boolean)? = null

    fun post(action: Action, card: CardSpec) {
        handler?.invoke(action, card)
    }
}

/** 纯函数：ColorToken → 当前主题下的具体 Color（暗色 / 字体缩放自动生效）。 */
fun resolveToken(token: ColorToken, cs: ColorScheme): Color = when (token) {
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

/**
 * Compose 挂载点：把一张 [CardSpec] 渲染进对话框。
 * 上层只传 spec，底层渲染器（Compose 原语 + 局部 Canvas 几何）由注册表分发，编排层无感。
 *
 * 渲染流程（Server-Driven UI 标准范式，不再有任何 Canvas 像素排版）：
 *   1) 按 type 取白名单渲染器；取不到 → 降级 Markdown 气泡（绝不崩对话框）。
 *   2) 渲染器内部用 Compose 原语自动排版，仅连续几何走局部 Canvas。
 *   3) 主题令牌由 [resolveToken] 基于当前 MaterialTheme 实时解析，暗色 / 字体缩放自动生效。
 *
 * 注意：本挂载点与已有的 DynamicUiBlock / SurfaceHost 完全独立，是两个功能，不合并。
 */
@Composable
fun CardSurface(
    spec: CardSpec,
    modifier: Modifier = Modifier,
    onAction: (Action, CardSpec) -> Unit = { a, c -> ActionBus.post(a, c) },
) {
    // 三级解析：type → 别名 → 按 data 形状推断（见 CardRegistry.resolveOrInfer）。
    // key 里带上 data/layout，避免同一个卡片 id 换了内容后仍复用旧渲染器。
    val resolved = remember(spec.type, spec.data, spec.layout) { CardRegistry.resolveOrInfer(spec) }
    if (resolved == null) {
        MarkdownFallback(spec, modifier)
        return
    }
    val renderer = resolved.second
    val cs = MaterialTheme.colorScheme
    // 主题令牌解析器：随主题色板变化重建（纯函数，无 lateinit 全局状态）。
    val resolver: (ColorToken) -> Color = remember(cs) { { resolveToken(it, cs) } }
    val fire: (Action) -> Unit = { action -> onAction(action, spec) }
    renderer(spec, modifier, fire, resolver)
}

/**
 * 连「按 data 推断」都失败的降级渲染。
 *
 * 绝不吐「[不支持的卡片类型：X]」这种协议报错样式（2026-10-30 用户截图实证：
 * 对话框里出现协议原文，用户会认为「小卡片彻底坏了」）。这里改为**把能读到的内容原样列出来**：
 * 标题、布局树里的所有文字、表格首列 —— 至少信息不丢，只是没有排版。
 */
@Composable
private fun MarkdownFallback(spec: CardSpec, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val resolver = remember(cs) { { t: ColorToken -> resolveToken(t, cs) } }
    val texts = buildList {
        if (spec.title.isNotBlank()) add(spec.title)
        if (spec.subtitle.isNotBlank()) add(spec.subtitle)
        addAll(CardExtractors.collectTexts(spec.layout))
    }
    androidx.compose.foundation.layout.Column(modifier.fillMaxWidth()) {
        if (texts.isEmpty()) {
            androidx.compose.material3.Text(
                text = "（这张小卡片的内容没能解析出来）",
                color = resolver(ColorToken.OnSurfaceVariant),
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            texts.distinct().take(12).forEach { t ->
                androidx.compose.material3.Text(
                    text = t,
                    color = resolver(ColorToken.OnSurface),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
