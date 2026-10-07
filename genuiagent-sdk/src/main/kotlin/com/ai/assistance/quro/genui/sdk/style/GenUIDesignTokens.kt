package com.ai.assistance.quro.genui.sdk.style

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * GenUI 设计 token —— 全仓间距/圆角/字号的**唯一档位来源**。
 *
 * ## 为什么需要这一层（2026-10-06 排查「组件虽然多但是不齐」）
 *
 * 修完对齐与 margin 之后做了一次量化统计，结果触目惊心：
 *
 * ```
 * 58 处  .padding(12.dp)     ← 8pt 网格上
 * 41 处  .padding(16.dp)     ← 网格上
 * 29 处  .padding(10.dp)     ← 不在网格上
 * 22 处  .padding(8.dp)      ← 网格上
 * 18 处  .padding(14.dp)     ← 不在网格上
 *  7 处  .padding(20.dp)     ← 网格上
 *  4 处  .padding(6.dp)      ← 半格，允许
 *  1 处  .padding(7.dp)      ← 不在网格上
 *  1 处  .padding(3.dp)      ← 不在网格上
 * ```
 *
 * 也就是说 **220 处内边距里有 49 处（22%）落在 8pt 网格之外**。
 * 同一屏里一张卡 12dp、旁边一张 10dp、再旁边 14dp —— 肉眼说不出哪里怪，
 * 但就是觉得「不齐」。Apple HIG 的 8pt 网格存在的意义就是消除这种不可名状的抖动。
 *
 * 另外容器 `spacing` 的默认值曾有 `0f` 与 `8f` 两套（同一语义两个值），
 * 也已统一到 [GenUIDesignTokens.spacingDefault]。
 *
 * ## 网格约定（Apple HIG）
 *
 * 4 / 8 / 12 / 16 / 24 / 32 是全部合法档位。
 * 6 / 10 / 14 这类半格值**不是不能用**（紧凑场景确实需要），
 * 但必须是**有意选择**并集中在此处，而不是散落成一堆魔法数。
 */
object GenUIDesignTokens {

    // ══════════════ 间距（8pt 网格）══════════════

    /** 极紧：图标与文字之间。同行元素。 */
    val space2: Dp = 2.dp

    /** 紧：标签与数值、chip 之间。 */
    val space4: Dp = 4.dp

    /** 紧凑：卡片内元素之间。**最常用的元素间距**。 */
    val space8: Dp = 8.dp

    /** 标准：卡片内边距的默认值。 */
    val space12: Dp = 12.dp

    /** 宽松：卡片内边距、控件高度。 */
    val space16: Dp = 16.dp

    /** 分区：区块之间。 */
    val space24: Dp = 24.dp

    /** 大分区：页面级区块。 */
    val space32: Dp = 32.dp

    /**
     * 容器子元素默认间距。
     *
     * 🔴 必须是 8 而不是 0：模型经常忘记写 `spacing`，
     * 0 会让所有元素糊成一片（用户反馈的「组件虽然多但是不齐」）。
     * 而 8 恰好也是 8pt 网格的第一档，不会引入新的不齐。
     */
    val spacingDefault: Float = 8f

    /** 全部合法间距档位，供校验与文档使用。 */
    val spacingScale: List<Dp> = listOf(space2, space4, space8, space12, space16, space24, space32)

    // ══════════════ 圆角（Material 3 刻度）══════════════

    /** M3 extra-small：徽章、tag。 */
    val radiusXs: Dp = 4.dp

    /** M3 small：chip、输入框。 */
    val radiusSm: Dp = 8.dp

    /** M3 medium：**卡片默认圆角**。 */
    val radiusMd: Dp = 12.dp

    /** M3 large：浮层、对话框。 */
    val radiusLg: Dp = 16.dp

    /** M3 extra-large：hero 卡、横幅。 */
    val radiusXl: Dp = 28.dp

    /** 胶囊/圆形：切到高度一半。 */
    val radiusPill: Dp = 100.dp

    // ══════════════ 字号（Material 3 Type Scale）══════════════

    /** label-small：角标、图注。 */
    val fontLabelSmall: Float = 11f

    /** body-small：辅助说明。 */
    val fontBodySmall: Float = 12f

    /** body-medium：**正文默认值**。 */
    val fontBodyMedium: Float = 14f

    /** body-large：强调正文。 */
    val fontBodyLarge: Float = 16f

    /** title-medium：卡片标题。 */
    val fontTitleMedium: Float = 16f

    /** title-large：区块标题。 */
    val fontTitleLarge: Float = 22f

    /** headline-small：页面主标题。 */
    val fontHeadlineSmall: Float = 24f

    /** display-small：数字展示。 */
    val fontDisplaySmall: Float = 36f

    // ══════════════ 组件尺寸（Material 3）══════════════

    /** 最小可点区域（无障碍下限）。 */
    val minTouchTarget: Dp = 48.dp

    /** 按钮/输入框标准高度。 */
    val controlHeight: Dp = 40.dp

    /** 标准高度按钮。 */
    val controlHeightLarge: Dp = 48.dp

    /** 图标按钮边长。 */
    val iconButtonSize: Dp = 40.dp

    /** 列表行最小高度。 */
    val listItemMinHeight: Dp = 56.dp

    /** 页面水平边距。 */
    val pagePaddingH: Dp = 16.dp

    /** 页面顶部边距。 */
    val pagePaddingTop: Dp = 16.dp

    /** 页面底部边距。 */
    val pagePaddingBottom: Dp = 24.dp
}
