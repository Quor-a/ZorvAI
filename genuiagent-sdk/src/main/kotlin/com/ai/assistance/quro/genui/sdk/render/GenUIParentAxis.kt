package com.ai.assistance.quro.genui.sdk.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf

/**
 * GenUI 渲染期的**父级轴向上下文**。
 *
 * ## 为什么需要它（用户截图里的「一个字体一行」竖排）
 *
 * [StyleResolver.baseModifier] 给每个节点包一层 `Box(modifier)`，而 `width: match`
 * 会被翻译成 `Modifier.fillMaxWidth()`。这件事在 **Column** 里是对的（占满一整行），
 * 但在 **Row** 里是灾难：
 *
 * - Compose 测量 Row 的非 weight 子节点时，给的是「**剩余宽度**」；
 * - 第一个带 `match` 的子节点用 `fillMaxWidth` 直接把剩余宽度吃干；
 * - 后面的兄弟节点拿到 0 宽 → 里面的 `Text` 每个汉字只能单独占一行 → 竖排。
 *
 * 已经在 `ColumnRowRenderer` 里做过一层「`match` → `weight(1f)`」的修复，
 * 但那只覆盖了**直接子节点**：模型经常把内容包在 `box` / `container` / `card` 里再放进 Row，
 * 那层包裹容器走的是 [StyleResolver] 的 `fillMaxWidth`，绕过了上一道修复。
 *
 * ## 本类的作用
 *
 * 把「我现在是不是在 Row 里」这件事变成**渲染期可读的状态**，
 * 让 [StyleResolver.baseModifier] 无需知道父节点是谁就能做出正确判断。
 *
 * 用 CompositionLocal 而不是参数传递，是因为渲染树中间隔着组件注册表
 * （`registry.resolve(type).invoke(component, ctx)`），参数传不进去。
 */
object GenUIParentAxis {

    /**
     * 当前 Row 嵌套深度。
     *
     * - `0`：不在任何 Row 里（页面根、Column 内）；
     * - `>0`：在 Row 里；嵌套 Row 时继续累加（内层同样不能吃满整行）。
     *
     * 用**嵌套深度**而不是 Boolean，是为了让嵌套 Row 也能被正确识别
     * —— 只记 Boolean 会在进入内层 Row 时被外层值覆盖掉。
     */
    val rowDepth: ProvidableCompositionLocal<Int> = compositionLocalOf { 0 }

    /** 当前是否处于 Row 上下文（含任意层嵌套）。 */
    val inRow: Boolean
        @Composable get() = rowDepth.current > 0

    /**
     * 在 Row 作用域内执行 [content]，期间 [rowDepth] = 当前深度 + 1。
     *
     * 由 [ColumnRowRenderer] 在进入横向分支时调用。
     */
    @Composable
    fun InRow(content: @Composable () -> Unit) {
        CompositionLocalProvider(rowDepth provides rowDepth.current + 1, content = content)
    }
}
