package com.ai.assistance.quro.core.ui.dynamicui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通用样式系统（v1.0.83）回归测试：验证 [QuroUiDslParser] 把任意节点上的通用 style 对象
 * 正确收进 [QuroUiStyle]，以及未知节点类型降级为带样式容器。
 *
 * 全部为纯 Kotlin（仅依赖 org.json），不触及 android.* / Compose，可在 JVM 单元直接跑通。
 */
class QuroUiStyleTest {

    private fun parse(dsl: String): QuroUiNode {
        val result = A2uiInterpreter.interpret(dsl)
        assertTrue("DSL 应解析为 Success：$dsl", result is QuroUiParseResult.Success)
        return (result as QuroUiParseResult.Success).root
    }

    // 1) 嵌套 style 对象 → box 的通用样式（背景/圆角/内边距）正确解析。
    @Test
    fun parse_style_nestedObject_appliesToBox() {
        val root = parse(
            """{"type":"box","style":{"backgroundColor":"#fff","borderRadius":12,"padding":8},"children":[{"type":"text","value":"x"}]}"""
        )
        assertTrue(root is QuroBoxNode)
        val style = (root as QuroBoxNode).style
        assertNotNull("box 应挂上通用样式", style)
        assertEquals(QuroUiBackground.Solid("#fff"), style?.background)
        assertEquals(12, style?.borderRadius)
        assertEquals(8, style?.padding?.all)
    }

    // 2) 顶层平铺别名（不包 style 对象）与嵌套对象等价，都收进同一个 QuroUiStyle。
    @Test
    fun parse_style_flatAliases_equivalentToNested() {
        val root = parse(
            """{"type":"box","backgroundColor":"#fff","borderRadius":12,"padding":8,"children":[]}"""
        )
        assertTrue(root is QuroBoxNode)
        val style = (root as QuroBoxNode).style
        assertNotNull("平铺别名也应生成通用样式", style)
        assertEquals(QuroUiBackground.Solid("#fff"), style?.background)
        assertEquals(12, style?.borderRadius)
        assertEquals(8, style?.padding?.all)
    }

    // 3) 文本排版：旧 style 字符串语义落到 typography 字段。
    @Test
    fun parse_text_typography_stringStyle() {
        val root = parse("""{"type":"text","value":"hi","style":"headline"}""")
        assertTrue(root is QuroTextNode)
        assertEquals("headline", (root as QuroTextNode).typography)
    }

    // 4) 文本 style 为对象时，子字段独立提取到 size/bold/color/align，typography 留空（避免冲突）。
    @Test
    fun parse_text_objectStyle_extractsFields() {
        val root = parse(
            """{"type":"text","value":"hi","style":{"color":"#ff0000","fontWeight":"bold","fontSize":18,"align":"center"}}"""
        )
        assertTrue(root is QuroTextNode)
        val t = root as QuroTextNode
        assertNull("对象式 style 不应写 typography", t.typography)
        assertEquals("#ff0000", t.color)
        assertTrue("fontWeight=bold 应解析为 bold=true", t.bold)
        assertEquals(18, t.size)
        assertEquals("center", t.align)
    }

    // 5) 渐变背景：gradient 对象解析为 QuroUiBackground.Gradient。
    @Test
    fun parse_style_gradientBackground() {
        val root = parse(
            """{"type":"box","style":{"gradient":{"colors":["#ff9a9e","#fad0c4"],"direction":"vertical"}},"children":[]}"""
        )
        assertTrue(root is QuroBoxNode)
        val bg = (root as QuroBoxNode).style?.background
        assertTrue("应为渐变背景", bg is QuroUiBackground.Gradient)
        val g = bg as QuroUiBackground.Gradient
        assertEquals(2, g.colors.size)
        assertEquals("vertical", g.direction)
    }

    // 6) visible=false 落到通用样式，渲染器据此整节点不渲染。
    @Test
    fun parse_style_visible_falseFlag() {
        val root = parse("""{"type":"box","style":{"visible":false},"children":[]}""")
        assertTrue(root is QuroBoxNode)
        assertEquals(false, (root as QuroBoxNode).style?.visible)
    }

    // 7) 未知节点类型降级为「带通用样式的竖向容器」，并保留 style 与 children。
    @Test
    fun parse_unknownType_degradesToStyledContainer() {
        val root = parse(
            """{"type":"my_widget","style":{"backgroundColor":"#eee","borderRadius":8},"children":[{"type":"text","value":"y"}]}"""
        )
        // 降级产物是 QuroColumnNode（带样式容器）
        assertTrue("未知类型应降级为 QuroColumnNode", root is QuroColumnNode)
        val col = root as QuroColumnNode
        assertEquals(QuroUiBackground.Solid("#eee"), col.style?.background)
        assertEquals(8, col.style?.borderRadius)
        // 顶部插入降级提示，原 children 保留
        assertTrue("应包含降级提示文本", col.children.any {
            it is QuroTextNode && it.value.contains("未识别")
        })
        assertTrue("原 children 应保留", col.children.any {
            it is QuroTextNode && it.value == "y"
        })
    }

    // 8) 完全缺失 style 的节点，style 应为 null（不挂多余空对象）。
    @Test
    fun parse_noStyle_leavesStyleNull() {
        val root = parse("""{"type":"text","value":"plain"}""")
        assertTrue(root is QuroTextNode)
        assertNull((root as QuroTextNode).style)
    }
}
