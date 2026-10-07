package com.ai.assistance.quro.core.tools

import com.ai.assistance.quro.core.QuroToolSpec
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归测试：渐进式工具披露（RAG）**真的省 token**，且不靠把全量清单换个地方塞进来。
 *
 * ## 这条测试存在的唯一原因
 * 第一版改造做完，一测体积发现问题：RAG 模式 140,130 字符 vs 全量 145,472 —— **只省 3.7%**，
 * 等于白改。根因是 [QuroToolRouter.catalogSpec] 把 `buildCompactIndex()`（全部 265 个工具的
 * name + description = **66,838 字符**）塞进了 tool_router 的 description，
 * 占整个 tools 字段的 46%。把清单从 `tools[]` 挪到某一个 description 里，并不让它变短。
 *
 * 改成 [QuroToolRouter.buildCatalogBrief]（只给「分类 + 工具名逗号列表」）后：
 * catalog 描述 66,838 → 4,758 字符，RAG 模式 140,130 → **78,050**（省 46%）。
 *
 * 这个退化同样是静默的：编译照过、既有用例照过，只有真机账单变贵。
 * 所以把体积上限钉成断言，防止有人又把全量索引塞回 description。
 */
class ToolRagSizeTest {

    private fun chars(s: QuroToolSpec) = s.name.length + s.description.length + s.parametersJson.length

    @Test
    fun RAG模式必须真的比全量省() {
        val specs = buildQuroRegistry(null).specs()
        val router = QuroToolRouter(specs)
        val full = specs.sumOf { chars(it) }
        val rag = router.activeSpecs().sumOf { chars(it) }

        // 70% 是留了余量的红线；当前实测 78,050 / 145,472 ≈ 54%。
        assertTrue(
            "RAG 模式体积 $rag / 全量 $full = ${ratio(rag, full)}，超过 70% 红线，渐进式披露等于白做",
            rag < (full * 0.70),
        )
        // 也必须是「真的少发」，而不只是描述变短。
        assertTrue("RAG 模式工具数 ${router.activeSpecs().size} 未少于全量 ${specs.size}",
            router.activeSpecs().size < specs.size)
    }

    /**
     * 🔴 核心红线：`tool_router` 的 description **绝不能**再塞全量工具索引。
     * 当前 4,758 字符；历史值 66,838。留到 10,000 是为了给「加几行工作流说明」留空间，
     * 同时一旦有人把 buildCompactIndex 塞回来就会立刻爆掉。
     */
    @Test
    fun 路由目录描述必须保持短小() {
        val router = QuroToolRouter(buildQuroRegistry(null).specs())
        val desc = router.activeSpecs().first { it.name == "tool_router" }.description
        assertTrue(
            "tool_router 描述 ${desc.length} 字符，超过 10,000 红线。" +
                "极可能又把 buildCompactIndex() 全量索引塞回 description 了（历史值 66,838）。",
            desc.length < 10_000,
        )
    }

    /** 加载一批工具后体积不得失控（每加一个工具都要下发它的完整 schema）。 */
    @Test
    fun 加载常用工具后体积仍可控() {
        val router = QuroToolRouter(buildQuroRegistry(null).specs())
        val base = router.activeSpecs().sumOf { chars(it) }
        listOf(
            "web_crawler", "export_apk", "packet_capture", "lsposed", "screenshot",
            "terminal", "translate", "calculator", "video_gen", "image_gen",
        ).forEach {
            router.handle("tool_router", """{"action":"get_schema","name":"$it"}""")
        }
        val after = router.activeSpecs().sumOf { chars(it) }
        val specs = buildQuroRegistry(null).specs()
        val full = specs.sumOf { chars(it) }
        // 实测 base≈78,050 → after≈84,453（全量 145,472）。留一倍余量红线。
        assertTrue(
            "加载 10 个工具后体积 $after（起始 $base，全量 $full），超过全量的 100% 红线",
            after < full,
        )
    }

    /** 加载必须真的生效：get_schema 之后该工具才进入 tools 字段，否则「加载」是空动作。 */
    @Test
    fun get_schema必须让工具真正进入下发集() {
        val router = QuroToolRouter(buildQuroRegistry(null).specs())
        val target = "packet_capture"
        val before = router.activeSpecs().map { it.name }
        assertTrue("前置条件：$target 应一开始不在下发集里", target !in before)

        router.handle("tool_router", """{"action":"get_schema","name":"$target"}""")
        val after = router.activeSpecs().map { it.name }
        assertTrue("get_schema 后 $target 仍未进入下发集", target in after)
    }

    /** reset 必须真的清空已加载，否则用户「清上下文」后工具还会白占体积。 */
    @Test
    fun reset必须清空已加载集合() {
        val router = QuroToolRouter(buildQuroRegistry(null).specs())
        router.handle("tool_router", """{"action":"get_schema","name":"packet_capture"}""")
        assertTrue("packet_capture" in router.activeSpecs().map { it.name })

        router.reset()
        assertTrue("reset 后 packet_capture 仍留在下发集里", "packet_capture" !in router.activeSpecs().map { it.name })
    }

    /** 渐进式披露总开关必须默认开启——这是本轮改造的交付点，误关等于退回全量下发。 */
    @Test
    fun 渐进式披露总开关必须默认开启() {
        val prev = QuroToolRouter.PROGRESSIVE
        try {
            assertTrue("QuroToolRouter.PROGRESSIVE 必须默认 true（RAG 式按需检索）", prev)
        } finally {
            QuroToolRouter.PROGRESSIVE = prev
        }
    }

    private fun ratio(a: Int, b: Int) = String.format("%.1f%%", a * 100.0 / b)
}
