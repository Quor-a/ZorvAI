package com.ai.assistance.quro.genui.sdk.render

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 对齐与排列解析 —— GenUI 的**唯一**对齐语义出口。
 *
 * ## 为什么抽这一层（2026-10-06）
 *
 * 排查「排版方向不对 / 位置不对」时发现，同一套对齐语义在仓库里有**两份独立实现**：
 *  - `LayoutTypographyButtonRenderers.kt` 里的 `ltbXxx` 系列（实际生效的那份）
 *  - `ContainerComponent.kt` 里的 `XxxAlign` 系列（同名不同行为）
 *
 * 两份实现对同一个输入给出不同结果 —— 比如 `stretch` 在一份里是「撑满」，
 * 在另一份里是「居中」。模型无从得知哪个对，于是同一份提示词产出的卡片，
 * 换个组件类型对齐就变了。这正是用户说的「组件虽然多但是不齐」。
 *
 * 现在两份都委托到这里，语义只有一份。
 *
 * ## 三条核心修复
 *
 * 1. **未指定对齐 → TopStart（不是 Center）**
 *    原 `ltbBoxAlign(null)` 返回 `Alignment.Center`：模型不写 gravity 时，
 *    所有子节点被推到容器正中。这是「位置不对」里最刺眼的一条 ——
 *    一个装着图标+标题+副标题的盒子会三行文字垂直居中挤成一团。
 *    主流 UI 框架（Flexbox / 浏览器 / Compose 默认）无一例外都是 top-start。
 *
 * 2. **stretch ≠ center**
 *    原实现把 `stretch` 映射成 `CenterHorizontally`。stretch 的语义是「撑满」，
 *    模型写它是想要子元素铺满，结果全被推到中间、两侧留白。
 *
 * 3. **主轴排列别名归一化**
 *    原实现只认 `center/end/space_between` 等精确值。模型写 `middle`、`centre`、
 *    `flex-start`、`between` 全部落进 else → 静默变成 Top。
 *    现在统一走 [GenUILayoutGuard.arrangement] 归一化。
 */
object GenUILayoutAlign {

    // ── 主轴排列 ──────────────────────────────────────────────

    /**
     * 纵向容器（Column）的主轴排列。
     * spacing > 0 时一律用 spacedBy，保证元素之间有稳定的 8pt 网格间距。
     */
    fun verticalArrangement(value: String?, spacingDp: Dp): Arrangement.Vertical {
        val spacing = if (spacingDp > 0.dp) spacingDp else 0.dp
        return when (GenUILayoutGuard.arrangement(value)) {
            "center" -> if (spacing > 0.dp) Arrangement.spacedBy(spacing, Alignment.CenterVertically) else Arrangement.Center
            "end" -> if (spacing > 0.dp) Arrangement.spacedBy(spacing, Alignment.Bottom) else Arrangement.Bottom
            "space_between" -> Arrangement.SpaceBetween
            "space_around" -> Arrangement.SpaceAround
            "space_evenly" -> Arrangement.SpaceEvenly
            // 未指定 → Top（这是 Compose / CSS 的默认，绝不是 Center）
            else -> if (spacing > 0.dp) Arrangement.spacedBy(spacing) else Arrangement.Top
        }
    }

    /**
     * 横向容器（Row）的主轴排列。
     */
    fun horizontalArrangement(value: String?, spacingDp: Dp): Arrangement.Horizontal {
        val spacing = if (spacingDp > 0.dp) spacingDp else 0.dp
        return when (GenUILayoutGuard.arrangement(value)) {
            "center" -> if (spacing > 0.dp) Arrangement.spacedBy(spacing, Alignment.CenterHorizontally) else Arrangement.Center
            "end" -> if (spacing > 0.dp) Arrangement.spacedBy(spacing, Alignment.End) else Arrangement.End
            "space_between" -> Arrangement.SpaceBetween
            "space_around" -> Arrangement.SpaceAround
            "space_evenly" -> Arrangement.SpaceEvenly
            else -> if (spacing > 0.dp) Arrangement.spacedBy(spacing) else Arrangement.Start
        }
    }

    // ── 交叉轴对齐 ────────────────────────────────────────────

    /**
     * Column 的水平对齐。
     *
     * 🔴 `stretch` 不再映射成 CenterHorizontally：stretch 是「撑满」，
     * 由调用方通过 [isStretch] 走 fillMaxWidth 语义处理。
     */
    fun horizontalAlign(value: String?): Alignment.Horizontal {
        return when (GenUILayoutGuard.arrangement(value)) {
            "end" -> Alignment.End
            "center" -> Alignment.CenterHorizontally
            else -> Alignment.Start
        }
    }

    /**
     * Row 的垂直对齐。
     */
    fun verticalAlign(value: String?): Alignment.Vertical {
        return when (GenUILayoutGuard.arrangement(value)) {
            "end" -> Alignment.Bottom
            "center" -> Alignment.CenterVertically
            else -> Alignment.Top
        }
    }

    /**
     * 交叉轴是否为「撑满」语义。调用方据此对子节点套 fillMaxWidth/fillMaxHeight。
     */
    fun isStretch(value: String?): Boolean = GenUILayoutGuard.crossAxisStretch(value)

    // ── 盒内对齐（Box / stack）───────────────────────────────

    /**
     * Box / stack 的子节点对齐。
     *
     * 🔴 未指定时返回 [Alignment.TopStart]（原实现是 [Alignment.Center]）。
     * 理由：Box 是「叠放」容器，模型不写对齐时的合理预期是「按文档流从左上排」，
     * 而不是「全部居中挤在一起」。
     */
    fun boxAlign(value: String?): Alignment {
        return when (GenUILayoutGuard.boxAlignment(value)) {
            "top" -> Alignment.TopCenter
            "bottom" -> Alignment.BottomCenter
            "center" -> Alignment.Center
            "start" -> Alignment.CenterStart
            "end" -> Alignment.CenterEnd
            "top_start" -> Alignment.TopStart
            "top_end" -> Alignment.TopEnd
            "bottom_start" -> Alignment.BottomStart
            "bottom_end" -> Alignment.BottomEnd
            // 未指定 → TopStart，对齐主流框架默认行为
            else -> Alignment.TopStart
        }
    }
}
