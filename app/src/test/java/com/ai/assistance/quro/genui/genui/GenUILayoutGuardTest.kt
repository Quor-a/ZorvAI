package com.ai.assistance.quro.genui.genui

import com.ai.assistance.quro.genui.sdk.dsl.Dimension
import com.ai.assistance.quro.genui.sdk.dsl.EdgeInsets
import com.ai.assistance.quro.genui.sdk.dsl.UIStyle
import com.ai.assistance.quro.genui.sdk.render.GenUILayoutAlign
import com.ai.assistance.quro.genui.sdk.render.GenUILayoutGuard
import com.ai.assistance.quro.genui.sdk.render.StyleResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GenUI 排版守卫回归测试（2026-10-06）。
 *
 * 钉死用户反馈的三类排版问题的修复：
 *  1. 排版溢出屏幕 → [GenUILayoutGuard] 的宽/高/字号/内边距钳制
 *  2. 排版方向不对 → [GenUILayoutGuard.isHorizontal] / [isVertical] 的别名容错
 *  3. 排的位置不对 / 组件不齐 → [GenUILayoutAlign] 的默认对齐 + margin 消费 + spacing 默认值
 *
 * 本工程的 genuiagent-sdk 模块没有 test 源集（纯 Compose 库），
 * 所以测试放在 app 模块 —— 与既有的 GenUiRegistryAuditTest 同一套路。
 */
class GenUILayoutGuardTest {

    // ══════════════════ 1. 溢出屏幕：宽度钳制 ══════════════════

    @Test
    fun 宽度超出上限时被钳制不会推出屏幕() {
        // 模型把像素当 dp：1080px 在 360dp 宽的屏上会横向截掉三分之二
        assertEquals(GenUILayoutGuard.MAX_WIDTH_DP, GenUILayoutGuard.width(1080f)!!, 0.01f)
        assertEquals(GenUILayoutGuard.MAX_WIDTH_DP, GenUILayoutGuard.width(2400f)!!, 0.01f)
    }

    @Test
    fun 宽度在合理范围内时原样保留() {
        assertEquals(120f, GenUILayoutGuard.width(120f)!!, 0.01f)
        assertEquals(360f, GenUILayoutGuard.width(360f)!!, 0.01f)
    }

    @Test
    fun 负宽度与NaN被收敛而不是照搬() {
        assertEquals(0f, GenUILayoutGuard.width(-50f)!!, 0.01f)
        assertNull(GenUILayoutGuard.width(Float.NaN))
        assertNull(GenUILayoutGuard.width(null))
    }

    // ══════════════════ 2. 溢出屏幕：高度独立上限 ══════════════════

    @Test
    fun 高度有独立于宽度的上限() {
        // 关键：宽度与高度不能共用同一个 limit（原先都是 1200f）
        assertEquals(GenUILayoutGuard.MAX_HEIGHT_DP, GenUILayoutGuard.height(5000f)!!, 0.01f)
        assertTrue(
            "高度上限必须大于宽度上限，否则竖向内容会被过度裁剪",
            GenUILayoutGuard.MAX_HEIGHT_DP > GenUILayoutGuard.MAX_WIDTH_DP
        )
    }

    @Test
    fun resolveDp按调用点语义区分宽高上限() {
        val wide = StyleResolver.resolveDp(Dimension.Fixed(2000f), GenUILayoutGuard.MAX_WIDTH_DP)
        assertNotNull(wide)
        assertEquals(GenUILayoutGuard.MAX_WIDTH_DP, wide!!.value, 0.01f)

        val tall = StyleResolver.resolveDp(Dimension.Fixed(2000f), GenUILayoutGuard.MAX_HEIGHT_DP)
        assertNotNull(tall)
        assertEquals(GenUILayoutGuard.MAX_HEIGHT_DP, tall!!.value, 0.01f)
    }

    // ══════════════════ 3. 溢出屏幕：字号与内边距 ══════════════════

    @Test
    fun 字号被钳制在可读区间内() {
        // fontSize 96 会让一行标题占满整屏并把兄弟挤出可视区
        assertEquals(GenUILayoutGuard.MAX_FONT_SIZE_SP, GenUILayoutGuard.fontSize(96f)!!, 0.01f)
        assertEquals(GenUILayoutGuard.MAX_FONT_SIZE_SP, GenUILayoutGuard.fontSize(400f)!!, 0.01f)
        assertEquals(16f, GenUILayoutGuard.fontSize(16f)!!, 0.01f)
        assertEquals(GenUILayoutGuard.MIN_FONT_SIZE_SP, GenUILayoutGuard.fontSize(0f)!!, 0.01f)
        assertNull(GenUILayoutGuard.fontSize(Float.NaN))
    }

    @Test
    fun 内边距四边被钳制不会把内容挤没() {
        val p = GenUILayoutGuard.padding(EdgeInsets(200f, 200f, 200f, 200f))
        assertEquals(GenUILayoutGuard.MAX_PADDING_DP, p.start, 0.01f)
        assertEquals(GenUILayoutGuard.MAX_PADDING_DP, p.top, 0.01f)
        assertEquals(GenUILayoutGuard.MAX_PADDING_DP, p.end, 0.01f)
        assertEquals(GenUILayoutGuard.MAX_PADDING_DP, p.bottom, 0.01f)
    }

    @Test
    fun 全零内边距保持零对象不新建() {
        val zero = EdgeInsets(0f, 0f, 0f, 0f)
        assertTrue(GenUILayoutGuard.padding(zero) === zero)
    }

    @Test
    fun 负内边距被收敛到零() {
        val p = GenUILayoutGuard.padding(EdgeInsets(-10f, -10f, -10f, -10f))
        assertEquals(0f, p.start, 0.01f)
        assertEquals(0f, p.bottom, 0.01f)
    }

    // ══════════════════ 4. 溢出屏幕：其它视觉量 ══════════════════

    @Test
    fun 视觉量全部有界且负值收敛() {
        assertEquals(GenUILayoutGuard.MAX_CORNER_RADIUS_DP, GenUILayoutGuard.cornerRadius(9999f), 0.01f)
        assertEquals(0f, GenUILayoutGuard.cornerRadius(-5f), 0.01f)
        assertEquals(GenUILayoutGuard.MAX_BORDER_WIDTH_DP, GenUILayoutGuard.borderWidth(500f), 0.01f)
        assertEquals(GenUILayoutGuard.MAX_ELEVATION_DP, GenUILayoutGuard.elevation(999f), 0.01f)
        assertEquals(1f, GenUILayoutGuard.opacity(5f), 0.001f)
        assertEquals(0f, GenUILayoutGuard.opacity(-1f), 0.001f)
        assertEquals(1f, GenUILayoutGuard.opacity(Float.NaN), 0.001f)
        assertEquals(GenUILayoutGuard.MAX_ROTATION_DEG, GenUILayoutGuard.rotation(9999f), 0.01f)
        assertEquals(-GenUILayoutGuard.MAX_ROTATION_DEG, GenUILayoutGuard.rotation(-9999f), 0.01f)
    }

    // ══════════════════ 5. 排版方向：别名容错 ══════════════════

    @Test
    fun 横向别名全部识别包括大小写与简写() {
        // 原实现只认精确 "horizontal"，模型写 "Horizontal"/"row"/"h" 会静默落回竖排
        for (alias in listOf(
            "horizontal", "Horizontal", "HORIZONTAL", " row ", "row", "h", "x",
            "sideway", "inline", "left_to_right", "ltr",
        )) {
            assertTrue("别名 [$alias] 应识别为横向", GenUILayoutGuard.isHorizontal(alias))
        }
    }

    @Test
    fun 纵向与未指定不走横向() {
        for (alias in listOf("vertical", "Vertical", "column", "v", "y", "stacked", "top_to_bottom")) {
            assertFalse("别名 [$alias] 不应识别为横向", GenUILayoutGuard.isHorizontal(alias))
            assertTrue("别名 [$alias] 应识别为纵向", GenUILayoutGuard.isVertical(alias))
        }
        assertFalse(GenUILayoutGuard.isHorizontal(null))
        assertFalse(GenUILayoutGuard.isVertical(null))
        assertFalse(GenUILayoutGuard.isHorizontal("diagonal"))
    }

    // ══════════════════ 6. 位置不对：默认对齐 ══════════════════

    @Test
    fun 盒内未指定对齐时按左上而非居中() {
        // 这是「位置不对」里最刺眼的一条：原实现返回 Center，
        // 模型不写 gravity 时图标+标题+副标题被挤成一团居中
        assertEquals(
            androidx.compose.ui.Alignment.TopStart,
            GenUILayoutAlign.boxAlign(null)
        )
        assertEquals(
            androidx.compose.ui.Alignment.TopStart,
            GenUILayoutAlign.boxAlign("")
        )
        assertEquals(
            androidx.compose.ui.Alignment.TopStart,
            GenUILayoutAlign.boxAlign("unknown_value")
        )
    }

    @Test
    fun 盒内九宫格对齐全部正确映射() {
        val A = androidx.compose.ui.Alignment
        assertEquals(A.TopCenter, GenUILayoutAlign.boxAlign("top"))
        assertEquals(A.BottomCenter, GenUILayoutAlign.boxAlign("bottom"))
        assertEquals(A.Center, GenUILayoutAlign.boxAlign("center"))
        assertEquals(A.CenterStart, GenUILayoutAlign.boxAlign("start"))
        assertEquals(A.CenterStart, GenUILayoutAlign.boxAlign("left"))
        assertEquals(A.CenterEnd, GenUILayoutAlign.boxAlign("end"))
        assertEquals(A.CenterEnd, GenUILayoutAlign.boxAlign("right"))
        assertEquals(A.TopStart, GenUILayoutAlign.boxAlign("top_start"))
        assertEquals(A.TopStart, GenUILayoutAlign.boxAlign("top_left"))
        assertEquals(A.TopEnd, GenUILayoutAlign.boxAlign("top_end"))
        assertEquals(A.BottomStart, GenUILayoutAlign.boxAlign("bottom_start"))
        assertEquals(A.BottomEnd, GenUILayoutAlign.boxAlign("bottom_end"))
    }

    // ══════════════════ 7. 位置不对：stretch 不再被当居中 ══════════════════

    @Test
    fun stretch被识别为撑满而不是居中() {
        // 原实现把 stretch 映射成 CenterHorizontally —— 语义完全错：
        // 模型写 stretch 想让子元素铺满，结果全被推到中间两侧留白
        for (s in listOf("stretch", "Stretch", "fill", " fill ", "stretch_fill")) {
            assertTrue("[$s] 应识别为 stretch", GenUILayoutAlign.isStretch(s))
        }
        assertFalse(GenUILayoutAlign.isStretch("center"))
        assertFalse(GenUILayoutAlign.isStretch(null))
    }

    @Test
    fun 未指定交叉轴对齐时按起始边() {
        assertEquals(androidx.compose.ui.Alignment.Start, GenUILayoutAlign.horizontalAlign(null))
        assertEquals(androidx.compose.ui.Alignment.Top, GenUILayoutAlign.verticalAlign(null))
        assertEquals(
            androidx.compose.ui.Alignment.CenterHorizontally,
            GenUILayoutAlign.horizontalAlign("center")
        )
        assertEquals(
            androidx.compose.ui.Alignment.Bottom,
            GenUILayoutAlign.verticalAlign("end")
        )
    }

    // ══════════════════ 8. 组件不齐：主轴排列别名归一化 ══════════════════

    @Test
    fun 主轴排列别名被归一化而不是静默落回Top() {
        // 原实现只认 center/end/space_between 精确值，
        // 模型写 middle / centre / flex-start / between 会静默变成 Top
        assertEquals("center", GenUILayoutGuard.arrangement("middle"))
        assertEquals("center", GenUILayoutGuard.arrangement("centre"))
        assertEquals("center", GenUILayoutGuard.arrangement("CENTER"))
        assertEquals("start", GenUILayoutGuard.arrangement("flex-start"))
        assertEquals("start", GenUILayoutGuard.arrangement("flex_start"))
        assertEquals("start", GenUILayoutGuard.arrangement("top"))
        assertEquals("start", GenUILayoutGuard.arrangement("left"))
        assertEquals("end", GenUILayoutGuard.arrangement("trailing"))
        assertEquals("end", GenUILayoutGuard.arrangement("bottom"))
        assertEquals("space_between", GenUILayoutGuard.arrangement("between"))
        assertEquals("space_between", GenUILayoutGuard.arrangement("space-between"))
        assertEquals("space_around", GenUILayoutGuard.arrangement("around"))
        assertEquals("space_evenly", GenUILayoutGuard.arrangement("evenly"))
        assertNull(GenUILayoutGuard.arrangement("diagonal"))
        assertNull(GenUILayoutGuard.arrangement(null))
    }

    // ══════════════════ 9. 组件不齐：spacing 缺省值 ══════════════════

    @Test
    fun 未指定间距时给八点网格默认值而不是零() {
        // 「组件虽然多但是不齐」：模型漏写 spacing，0 会让元素糊成一片
        assertEquals(GenUILayoutGuard.DEFAULT_SPACING_DP, GenUILayoutGuard.spacing(null), 0.01f)
        assertEquals(8f, GenUILayoutGuard.DEFAULT_SPACING_DP, 0.01f)
    }

    @Test
    fun 显式零间距被尊重() {
        // 模型明确写 spacing: 0 就是想要紧贴，不能被默认值覆盖
        assertEquals(0f, GenUILayoutGuard.spacing(0f), 0.01f)
    }

    @Test
    fun 间距负值与超大值被钳制() {
        assertEquals(0f, GenUILayoutGuard.spacing(-8f), 0.01f)
        assertEquals(GenUILayoutGuard.MAX_SPACING_DP, GenUILayoutGuard.spacing(9999f), 0.01f)
        assertEquals(12f, GenUILayoutGuard.spacing(12f), 0.01f)
        assertEquals(GenUILayoutGuard.DEFAULT_SPACING_DP, GenUILayoutGuard.spacing(Float.NaN), 0.01f)
    }

    // ══════════════════ 10. margin 全链路（DSL → 守卫）══════════════════

    @Test
    fun margin四边被钳制() {
        val m = GenUILayoutGuard.margin(EdgeInsets(500f, 500f, 500f, 500f))
        assertEquals(GenUILayoutGuard.MAX_PADDING_DP, m.start, 0.01f)
        assertEquals(GenUILayoutGuard.MAX_PADDING_DP, m.end, 0.01f)
        val zero = EdgeInsets(0f, 0f, 0f, 0f)
        assertTrue(GenUILayoutGuard.margin(zero) === zero)
    }

    @Test
    fun UIStyle的margin字段确实存在且可被守卫消费() {
        // 回归钉死：曾经 UIStyle 声明了 margin 但渲染层从不读它，
        // 模型按提示词写 margin 完全无效 → 卡片之间没间距
        val style = UIStyle(margin = EdgeInsets(12f, 8f, 12f, 8f))
        val clamped = GenUILayoutGuard.margin(style.margin)
        assertEquals(12f, clamped.start, 0.01f)
        assertEquals(8f, clamped.top, 0.01f)
    }
}
