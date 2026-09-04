package com.ai.assistance.quro.core.ui.dynamicui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 pane 多 pane 节点的完整管线：解析 -> 校验 -> 指针绑定 -> 按 id 局部更新。
 *
 * 这些类均为纯 Kotlin（仅依赖 org.json），不触及 android.* / Compose 渲染，
 * 因此可在 JVM 单元直接跑通，作为 pane 能力的回归保护，不依赖真机。
 */
class QuroUiPanePipelineTest {

    private val paneCardJson = """
        {"type":"card","title":"个人资料","children":[
          {"type":"pane","direction":"auto","spacing":12,"padding":8,"children":[
            {"type":"column","children":[
              {"type":"text","value":"left"},
              {"type":"text_input","id":"name","label":"昵称"}
            ]},
            {"type":"column","children":[
              {"type":"text","value":"right"},
              {"type":"markdown","value":"预览"}
            ]}
          ]}
        ]}
    """.trimIndent()

    // 1) 解析：能正确构建出 QuroPaneNode，direction 与 children 都就位。
    @Test
    fun parse_buildsPaneNode() {
        val result = QuroUiDslParser.parseBlock(paneCardJson)
        assertTrue(result is QuroUiParseResult.Success)
        val root = (result as QuroUiParseResult.Success).root
        assertTrue(root is QuroCardNode)

        val pane = (root as QuroCardNode).children.filterIsInstance<QuroPaneNode>().firstOrNull()
        assertNotNull(pane)
        assertEquals("auto", pane!!.direction)
        assertEquals(2, pane.children.size)
        // 子区块自身是 column，且内部子节点被保留（递归解析正确）。
        assertTrue(pane.children.all { it is QuroColumnNode })
        assertEquals("left", (pane.children[0] as QuroColumnNode).children.filterIsInstance<QuroTextNode>().first().value)
    }

    // 2) 校验：递归进入 pane 子树，不丢 children，不产生降级。
    @Test
    fun validate_preservesPaneSubtree() {
        val root = (QuroUiDslParser.parseBlock(paneCardJson) as QuroUiParseResult.Success).root
        val validated = QuroUiCatalog.validate(root)
        assertFalse("pane 子树不应触发降级", validated.degraded)

        val vroot = validated.root
        assertTrue(vroot is QuroCardNode)
        val vpane = (vroot as QuroCardNode).children.filterIsInstance<QuroPaneNode>().first()
        assertEquals(2, vpane.children.size)
        assertTrue(vpane.children.all { it is QuroColumnNode })
    }

    // 3) 指针绑定：pane 内部的 @/name 占位符应被数据模型替换（递归下钻修复）。
    @Test
    fun bindTree_resolvesPointerInsidePane() {
        val json = """
            {"type":"card","children":[
              {"type":"pane","direction":"auto","children":[
                {"type":"column","children":[
                  {"type":"text","value":"@/name"},
                  {"type":"text_input","id":"name","label":"昵称"}
                ]}
              ]}
            ]}
        """.trimIndent()

        val session = A2uiSession()
        session.applyMessage(A2uiMessage.CreateSurface("default", json))
        session.applyMessage(A2uiMessage.UpdateDataModel("default", """{"name":"ZORV"}"""))

        val root = session.rootOf("default")
        assertNotNull(root)
        assertTrue("pane 内的 @/name 应被替换为 ZORV", findTextValue(root, "ZORV"))
        assertFalse("原始占位符不应残留", findTextValue(root, "@/name"))
    }

    // 4) 按 id 局部更新：pane 内部 id=target 的节点应被替换（replaceNodeById 递归下钻修复）。
    @Test
    fun updateComponents_replacesNodeInsidePane() {
        val json = """
            {"type":"card","children":[
              {"type":"pane","direction":"auto","children":[
                {"type":"column","children":[
                  {"type":"text","id":"target","value":"OLD"}
                ]}
              ]}
            ]}
        """.trimIndent()

        val session = A2uiSession()
        session.applyMessage(A2uiMessage.CreateSurface("default", json))
        session.applyMessage(
            A2uiMessage.UpdateComponents(
                "default",
                mapOf("target" to """{"type":"text","value":"REPLACED"}""")
            )
        )

        val root = session.rootOf("default")
        assertNotNull(root)
        assertTrue("pane 内部的 target 节点应被替换为 REPLACED", findTextValue(root, "REPLACED"))
        assertFalse("旧值 OLD 不应残留", findTextValue(root, "OLD"))
    }

    // 5) 带权重的子区块：pane 子 column 的 weight 被解析并保留（支撑「侧栏 1 : 主区 2」非等比双栏）。
    @Test
    fun weightedChild_preservedThroughParseAndValidate() {
        val json = """
            {"type":"card","children":[
              {"type":"pane","direction":"row","children":[
                {"type":"column","weight":1,"children":[{"type":"text","value":"侧栏"}]},
                {"type":"column","weight":2,"children":[{"type":"text","value":"主区"}]}
              ]}
            ]}
        """.trimIndent()

        val root = (QuroUiDslParser.parseBlock(json) as QuroUiParseResult.Success).root
        val validated = QuroUiCatalog.validate(root)
        assertFalse(validated.degraded)

        val pane = (validated.root as QuroCardNode).children.filterIsInstance<QuroPaneNode>().first()
        assertEquals(2, pane.children.size)
        val col1 = pane.children[0] as QuroColumnNode
        val col2 = pane.children[1] as QuroColumnNode
        assertEquals(1f, col1.weight ?: 0f, 0.0001f)
        assertEquals(2f, col2.weight ?: 0f, 0.0001f)
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
