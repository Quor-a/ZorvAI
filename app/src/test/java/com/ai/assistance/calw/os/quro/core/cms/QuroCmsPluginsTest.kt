package com.ai.assistance.quro.core.cms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM 单元测试：验证 CMS v2 内置插件（种子模块）与 Runtime Host 路由。
 * - 16 个内置模块齐全，且 10 个被重写为真实原生能力（含自动化浏览器）。
 * - 每个能力的 requiresPermissions 都能在所属模块中找到声明（权限配对正确）。
 * - runOn 宿主推导（APP/TERMINAL）与 effectiveFor 模板切换正确。
 * - CmsHostRouter 显式 / auto / 双宿主解析正确（Context 可为 null，便于 JVM 单测）。
 * 不依赖 Android Context（仅调用 companion builtInModules() 与纯路由逻辑）。
 */
class QuroCmsPluginsTest {

    private val mods = QuroCmsRepository.builtInModules()
    private val byId = mods.associateBy { it.id }

    @Test
    fun builtInModules_has13Modules() {
        assertEquals("内置模块应为 16 个", 16, mods.size)
        // id 唯一
        assertEquals("模块 id 应唯一", 16, mods.map { it.id }.toSet().size)
        mods.forEach { m ->
            assertTrue("模块 ${m.id} 应有名称", m.name.isNotBlank())
            assertTrue("模块 ${m.id} 应至少含 1 项能力", m.capabilities.isNotEmpty())
        }
    }

    @Test
    fun builtInModules_webHasAutomationBrowser() {
        val web = byId["quro.web"] ?: error("缺少 quro.web")
        val auto = web.capabilities.firstOrNull { it.id == "automate_browser" }
        assertNotNull("quro.web 应含 automate_browser（自动化浏览器）", auto)
        auto!!
        assertEquals("automate_browser 应为 api 通道", "api", auto.actionType)
        assertEquals("automate_browser 应接 AiBrowserTool 引擎", "ai.browser.automate", auto.action)
        assertNotNull("quro.web 应含 extract_article（抓取正文）", web.capabilities.firstOrNull { it.id == "extract_article" })
        val ex = web.capabilities.first { it.id == "extract_article" }
        assertEquals("extract_article 应接 ai.browser.read", "ai.browser.read", ex.action)
    }

    @Test
    fun builtInModules_newNativeCapabilitiesPresent() {
        assertNotNull(byId["quro.system"]?.capabilities?.firstOrNull { it.id == "battery_info" })
        assertNotNull(byId["quro.file"]?.capabilities?.firstOrNull { it.id == "write_file" })
        assertNotNull(byId["quro.time"]?.capabilities?.firstOrNull { it.id == "format_time" })
        assertNotNull(byId["quro.workflow"]?.capabilities?.firstOrNull { it.id == "run_sequence" })
    }

    @Test
    fun builtInModules_permissionPairingComplete() {
        mods.forEach { m ->
            m.capabilities.forEach { c ->
                c.requiresPermissions.forEach { pid ->
                    assertTrue(
                        "能力 ${c.id}(模块 ${m.id}) 声明的权限 $pid 必须在模块权限表中存在",
                        m.permissions.any { it.id == pid },
                    )
                }
            }
        }
    }

    @Test
    fun builtInModules_runOnDerivation() {
        // run_code_dual 显式声明双宿主
        val dual = byId["quro.code"]!!.capabilities.first { it.id == "run_code_dual" }
        assertEquals(setOf(RuntimeHost.APP, RuntimeHost.TERMINAL), dual.runOn)
        // intent / api / js 默认仅 APP
        val ws = byId["quro.web"]!!.capabilities.first { it.id == "web_search" }
        assertEquals(setOf(RuntimeHost.APP), ws.runOn)
        // terminal 默认仅 TERMINAL
        val sh = byId["quro.terminal"]!!.capabilities.first { it.id == "run_shell" }
        assertEquals(setOf(RuntimeHost.TERMINAL), sh.runOn)
    }

    @Test
    fun capability_templateSubstitutionAndEffectiveFor() {
        val ws = byId["quro.web"]!!.capabilities.first { it.id == "web_search" }
        val resolved = ws.resolveTemplate(ws.action, mapOf("query" to "kotlin"))
        assertTrue("模板代入后应包含查询词", resolved.contains("q=kotlin"))

        val dual = byId["quro.code"]!!.capabilities.first { it.id == "run_code_dual" }
        val (tplApp, typeApp) = dual.effectiveFor(RuntimeHost.APP)
        assertEquals("js", typeApp)
        assertEquals("\${script}", tplApp)
        val (tplTerm, typeTerm) = dual.effectiveFor(RuntimeHost.TERMINAL)
        assertEquals("terminal", typeTerm)
        assertEquals("node -e \"\${script}\"", tplTerm)
    }

    @Test
    fun invocationTarget_parse() {
        assertEquals(InvocationTarget.APP, InvocationTarget.parse("app"))
        assertEquals(InvocationTarget.TERMINAL, InvocationTarget.parse("terminal"))
        assertEquals(InvocationTarget.AUTO, InvocationTarget.parse("auto"))
        assertEquals(InvocationTarget.AUTO, InvocationTarget.parse(null))
        assertEquals(InvocationTarget.AUTO, InvocationTarget.parse("garbage"))
    }

    @Test
    fun hostRouter_explicitAndAutoResolution() {
        val dual = byId["quro.code"]!!.capabilities.first { it.id == "run_code_dual" }
        // 显式指定且能力支持 → 直接采用
        assertEquals(RuntimeHost.APP, CmsHostRouter.resolve(dual, InvocationTarget.APP, null).host)
        assertEquals(RuntimeHost.TERMINAL, CmsHostRouter.resolve(dual, InvocationTarget.TERMINAL, null).host)
        // auto + 无 Context（JVM）→ 双宿主下退而求其次选 APP（terminalReady=false）
        assertEquals(RuntimeHost.APP, CmsHostRouter.resolve(dual, InvocationTarget.AUTO, null).host)
    }

    @Test
    fun hostRouter_singleCandidateAndFallback() {
        val ws = byId["quro.web"]!!.capabilities.first { it.id == "web_search" }
        // 单候选 → 直接返回
        assertEquals(RuntimeHost.APP, CmsHostRouter.resolve(ws, InvocationTarget.AUTO, null).host)
        // 显式 TERMINAL 不被支持 → 退回唯一候选 APP
        assertEquals(RuntimeHost.APP, CmsHostRouter.resolve(ws, InvocationTarget.TERMINAL, null).host)
    }

    @Test
    fun hostRouter_emptyRunOnProducesGuidance() {
        val empty = QuroCmsCapability(
            id = "x", summary = "x", schema = "{}",
            requiresPermissions = emptyList(), constraints = PermissionConstraints(),
            actionType = "api", action = "y", runOn = emptySet(),
        )
        val r = CmsHostRouter.resolve(empty, InvocationTarget.AUTO, null)
        assertNull("未声明任何宿主时应返回 null 宿主并给出引导", r.host)
        assertNotNull("应给出引导文案", r.guidance)
    }
}
