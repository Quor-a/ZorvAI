package com.ai.assistance.quro.core.ui.dynamicui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

// =============================================================================================
// SurfaceHost：动态 UI 的「surface 根部」封装
// ---------------------------------------------------------------------------------------------
// 把两件事合二为一，作为每个 AI 生成 UI 的唯一根容器：
//   1) [ProvideAutoDensity] 的等比密度映射 —— 360dp 设计稿恰好等于容器可用宽度，数学上不可能横向溢出
//      （手机/平板/折叠/分屏/横竖屏全自动，全屏、分屏都填满，不存在溢出这个概念）。
//   2) 容器宽度档位（类 WindowSizeClass）作为结构信号 —— 供渲染器/未来多 pane 布局切换使用。
//
// 为什么挂在每个 surface 根部、而不是整条聊天列表外：
//   列表外层必须保留系统 density（滚动条/分隔线不能跟着缩放），只有 AI 生成的卡片内部才等比。
//
// 注意：designWidthDp 始终固定为 360（AI 按 360dp 出绝对尺寸这一约定不可破，破了就失去「不溢出」保证）。
// 尺寸档位只作为**结构信号**下发，不改变缩放基准；真正的多 pane 重排需要 DSL 声明 pane 后再接这一信号。
// =============================================================================================

/**
 * 动态 UI surface 的尺寸档位。以「容器可用宽度」(dp) 为判据，不依赖 Activity/屏幕，
 * 因此对嵌入对话框消息列、弹窗、分屏等任意容器都准确（区别于 material3 WindowSizeClass 看整屏）。
 */
enum class SurfaceSizeClass { Compact, Medium, Expanded }

/** 当前 surface 的尺寸档位，供渲染器子树读取，按需在 Compact/Medium/Expanded 间切换结构。 */
val LocalSurfaceSizeClass = compositionLocalOf { SurfaceSizeClass.Compact }

/** 容器宽度(dp) → 尺寸档位。阈值对齐 material3 WindowSizeClass：Compact <600 / Medium 600–839 / Expanded ≥840。 */
fun surfaceSizeClassFromWidth(widthDp: Float): SurfaceSizeClass = when {
    widthDp < 600f -> SurfaceSizeClass.Compact
    widthDp < 840f -> SurfaceSizeClass.Medium
    else -> SurfaceSizeClass.Expanded
}

/**
 * 动态 UI 的 surface 根部。挂在 [QuroUiRenderer] 外层即可。
 *
 * - 等比缩放：[designWidthDp] 恰好映射为子树可用宽度，AI 写的绝对 dp 自动等比放大/缩小。
 * - 结构信号：同时下发 [LocalSurfaceSizeClass]，让卡片内部可按 Compact/Medium/Expanded 切换布局形态
 *   （如宽屏并排、窄屏竖排），而不必改 AI 的设计稿。
 *
 * @param designWidthDp AI 设计稿宽度，默认 360。填 dp 不填 px；固定值，不要随档位改（否则破坏不溢出保证）。
 */
@Composable
fun SurfaceHost(
    designWidthDp: Float = 360f,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().clipToBounds()) {
        val base = LocalDensity.current
        val scaled = Density(
            // 让 designWidthDp 恰好等于当前可用宽度（maxWidth 为当前 density 下的 dp 值）
            density = (maxWidth.value * base.density) / designWidthDp,
            fontScale = base.fontScale,
        )
        val sizeClass = surfaceSizeClassFromWidth(maxWidth.value)
        CompositionLocalProvider(
            LocalDensity provides scaled,
            LocalSurfaceSizeClass provides sizeClass,
        ) {
            content()
        }
    }
}
