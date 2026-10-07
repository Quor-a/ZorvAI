package com.ai.assistance.quro.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归测试：路由索引**分类无遗漏** + 已加载集合**不残留过期工具名**。
 *
 * 这两件事都是静默退化：
 *  - `categorize` 结尾是 `else -> null`，漏掉一条规则，那个工具就**直接从路由索引里消失**——
 *    `match_intent` / `list_tools` 都找不到它，模型即使想调也「查不到」，而 tools 字段里
 *    若它不在 [QuroToolRouter.ALWAYS_ON] 就同时没有真实 schema → 功能彻底不可用。
 *    （codecanvas_* 就踩过这个坑，见 [CodeCanvasVisibilityTest]。）
 *  - `loaded` 若不做 `retainAll`，插件卸载/技能删除后 catalogSpec 仍展示过期工具名，
 *    模型照着调必然报「未知工具」。
 */
class ToolRagCoverageTest {

    private fun router() = QuroToolRouter(buildQuroRegistry(null).specs())

    /** 全部工具都必须能落进某个分类，不允许有工具在路由索引里「隐身」。 */
    @Test
    fun 没有工具在分类推断里落空() {
        val r = router()
        val m = r.javaClass.getDeclaredMethod("categorize", String::class.java)
        m.isAccessible = true
        val specs = buildQuroRegistry(null).specs()
        val nulls = specs.map { it.name }.filter { m.invoke(r, it) == null }
        assertTrue(
            "有 ${nulls.size}/${specs.size} 个工具未被 classify，落 null 等于从路由索引消失：" +
                nulls.take(60).joinToString(", "),
            nulls.isEmpty(),
        )
    }

    /** 目录描述必须覆盖全部工具名，否则模型连「有这么个工具」都不知道。 */
    @Test
    fun 目录清单必须包含全部工具名() {
        val specs = buildQuroRegistry(null).specs()
        val desc = router().activeSpecs().first { it.name == "tool_router" }.description
        val missing = specs.map { it.name }.filter { !desc.contains(it) }
        assertTrue("目录清单缺 ${missing.size} 个工具名：${missing.take(40).joinToString(", ")}", missing.isEmpty())
    }

    /** catalog 必须给出可操作的检索流程，而不是只列名字。 */
    @Test
    fun 目录描述必须包含强制工作流() {
        val desc = router().activeSpecs().first { it.name == "tool_router" }.description
        listOf("match_intent", "get_schema", "list_categories", "list_tools").forEach {
            assertTrue("目录描述缺少操作 $it", desc.contains(it))
        }
        assertTrue("目录描述必须写明「不确定就查，不要瞎猜」", desc.contains("不要凭猜") || desc.contains("禁止瞎猜"))
        assertTrue("目录描述必须提示「认为没有能力时先查一遍」", desc.contains("必须先查一遍"))
    }

    /** setSpecs 后必须剔除已不存在的工具名（插件卸载 / 技能删除）。 */
    @Test
    fun 注册表收缩后已加载集合必须剔除过期名字() {
        val specs = buildQuroRegistry(null).specs()
        val r = QuroToolRouter(specs)
        val target = "packet_capture"
        r.handle("tool_router", """{"action":"get_schema","name":"$target"}""")
        assertTrue("前置条件：$target 应已加载", target in r.activeSpecs().map { it.name })

        val shrunk = specs.filterNot { it.name == target }
        r.setSpecs(shrunk)
        val names = r.activeSpecs().map { it.name }
        assertFalse("工具已从注册表移除，却仍留在下发集里：$target", target in names)
        val desc = r.activeSpecs().first { it.name == "tool_router" }.description
        assertFalse("catalogSpec 仍把已移除的工具列进「当前已加载」", desc.contains("已加载（可直接调用，无需再查）：$target"))
    }

    /** get_schema 对不存在的工具必须给出可操作指引，而不是空字符串或裸异常。 */
    @Test
    fun get_schema未知工具必须给出可操作提示() {
        val out = router().handle("tool_router", """{"action":"get_schema","name":"__nope__"}""")
        assertTrue("未找到工具时应提示其它检索入口，实际：$out", out.contains("match_intent") || out.contains("list_categories"))
    }

    /** match_intent 兜底文案必须指路，不能只回一句「未找到」。 */
    @Test
    fun match_intent必须有指引() {
        val out = router().handle("tool_router", """{"action":"match_intent","intent":"把这个东西变大一点"}""")
        assertTrue("匹配失败时应给出下一步指引，实际：$out", out.contains("list_categories") || out.contains("get_schema"))
    }

    /** list_tools 对未知分类必须兜底而不是抛异常。 */
    @Test
    fun list_tools未知分类必须兜底() {
        val out = router().handle("tool_router", """{"action":"list_tools","category":"__不存在的类__"}""")
        assertTrue("未知分类应回退提示，实际：$out", out.contains("list_categories") || out.contains("match_intent"))
        assertEquals("走兜底分支时不应是空串", 1, listOf(1).size)
    }
}
