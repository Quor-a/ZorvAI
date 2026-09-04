package com.ai.assistance.quro.core.ui.dynamicui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A2UI 解释器端到端集成测试。
 *
 * 覆盖 [A2uiInterpreter.interpret]（App 实际调用的唯一入口）对 ①③④⑤ 层的编排：
 *  - 信封判定（looksLikeEnvelope）
 *  - JSONL 信封 → A2uiSession 增量 apply → 校验 → 指针绑定 → 节点树
 *  - 纯 quro-ui DSL → 解析 + 校验
 *
 * 这些类均为纯 Kotlin（仅依赖 org.json），不触及 android.* / Compose 渲染，
 * 可在 JVM 单元直接跑通，作为解释器层的回归保护，不依赖真机。
 *
 * 注意：JSONL 信封中每条消息必须是「单行」JSON（A2uiMessage.parse 按行解析），
 * 因此下面的 jsonl 字符串每个 {...} 信封都写在同一物理行内。
 */
class QuroUiInterpreterTest {

    // 1) 信封判定：含 createSurface 的 JSONL 应识别为信封；纯 DSL 不应。
    @Test
    fun looksLikeEnvelope_detectsJsonlVsDsl() {
        val envelope = """{"type":"createSurface","surface":"default","root":{"type":"card"}}"""
        val dsl = """{"type":"card","children":[{"type":"text","value":"hi"}]}"""
        assertTrue(A2uiInterpreter.looksLikeEnvelope(envelope))
        assertFalse(A2uiInterpreter.looksLikeEnvelope(dsl))
    }

    // 2) 端到端 JSONL：createSurface + updateDataModel（指针绑定）+ updateComponents（按 id 替换），
    //    且 pane 子树内部的 @/name 与 id=target 都应生效。
    @Test
    fun interpret_jsonlEnvelope_appliesBindingAndComponentUpdateInsidePane() {
        val jsonl = """
            {"type":"createSurface","surface":"default","root":{"type":"card","children":[{"type":"pane","direction":"auto","children":[{"type":"column","children":[{"type":"text","value":"@/name"},{"type":"text_input","id":"name","label":"昵称"},{"type":"text","id":"target","value":"OLD"}]}]}]}}
            {"type":"updateDataModel","surface":"default","data":{"name":"ZORV"}}
            {"type":"updateComponents","surface":"default","components":{"target":{"type":"text","value":"REPLACED"}}}
        """.trimIndent()

        val result = A2uiInterpreter.interpret(jsonl)
        assertTrue("信封应被解释为 Success", result is QuroUiParseResult.Success)
        val root = (result as QuroUiParseResult.Success).root
        assertNotNull(root)

        // 指针绑定：pane 内部 @/name → ZORV
        assertTrue("pane 内的 @/name 应被数据模型替换为 ZORV", findTextValue(root, "ZORV"))
        assertFalse("原始占位符不应残留", findTextValue(root, "@/name"))
        // 按 id 局部更新：pane 内部 target → REPLACED
        assertTrue("pane 内部的 target 节点应被替换为 REPLACED", findTextValue(root, "REPLACED"))
        assertFalse("旧值 OLD 不应残留", findTextValue(root, "OLD"))
    }

    // 3) 纯 DSL 路由：interpret 应走 QuroUiDslParser + Catalog.validate，产出正确根类型。
    @Test
    fun interpret_plainDsl_routesToParserAndValidate() {
        val dsl = """{"type":"card","title":"T","children":[{"type":"button","id":"go","label":"开始","action":{"type":"open_url","url":"https://zorv.ai"}}]}"""

        val result = A2uiInterpreter.interpret(dsl)
        assertTrue(result is QuroUiParseResult.Success)
        val root = (result as QuroUiParseResult.Success).root
        assertTrue(root is QuroCardNode)
        assertEquals("T", (root as QuroCardNode).title)
        val btn = root.children.filterIsInstance<QuroButtonNode>().firstOrNull()
        assertNotNull("DSL 内的 button 应被解析", btn)
    }

    // 4) 加权 pane 经 JSONL createSurface 路径（App 实际下发方式）后权重仍保留。
    @Test
    fun interpret_weightedPane_preservedThroughJsonlCreateSurface() {
        val jsonl = """{"type":"createSurface","surface":"default","root":{"type":"card","children":[{"type":"pane","direction":"row","children":[{"type":"column","weight":1,"children":[{"type":"text","value":"侧栏"}]},{"type":"column","weight":2,"children":[{"type":"text","value":"主区"}]}]}]}}"""

        val result = A2uiInterpreter.interpret(jsonl)
        assertTrue(result is QuroUiParseResult.Success)
        val root = (result as QuroUiParseResult.Success).root
        assertTrue(root is QuroCardNode)
        val pane = (root as QuroCardNode).children.filterIsInstance<QuroPaneNode>().first()
        assertEquals(2, pane.children.size)
        val col1 = pane.children[0] as QuroColumnNode
        val col2 = pane.children[1] as QuroColumnNode
        assertEquals(1f, col1.weight ?: 0f, 0.0001f)
        assertEquals(2f, col2.weight ?: 0f, 0.0001f)
    }

    // 5) 多 surface：primaryRoot 取最后更新的 surface。
    @Test
    fun interpret_multipleSurfaces_primaryIsLastUpdated() {
        val jsonl = """
            {"type":"createSurface","surface":"a","root":{"type":"text","value":"A"}}
            {"type":"createSurface","surface":"b","root":{"type":"text","value":"B"}}
        """.trimIndent()

        val result = A2uiInterpreter.interpret(jsonl)
        assertTrue(result is QuroUiParseResult.Success)
        val root = (result as QuroUiParseResult.Success).root
        assertTrue("primaryRoot 应为最后更新的 surface b", findTextValue(root, "B"))
    }

    // 递归查找某文本值是否出现在子树任意 text 节点上。
    private fun findTextValue(node: QuroUiNode?, target: String): Boolean {
        if (node == null) return false
        if (node is QuroTextNode && node.value == target) return true
        return when (node) {
            is QuroColumnNode -> node.children.any { findTextValue(it, target) }
            is QuroRowNode -> node.children.any { findTextValue(it, target) }
            is QuroBoxNode -> node.children.any { findTextValue(it, target) }
            is QuroCardNode -> node.children.any { findTextValue(it, target) }
            is QuroPaneNode -> node.children.any { findTextValue(it, target) }
            else -> false
        }
    }
}
