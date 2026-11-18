package com.ai.assistance.quro.core.tools

import com.ai.assistance.quro.core.rag.RagConcept
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 合体（工具收敛）的**全量契约测试** —— 15 个统一入口逐条验证，不挑单个工具。
 *
 * ## 为什么需要这一层
 *
 * 单测此前只在 aci 一个工具上发现过「schema 里有重复键 → 模型传对象 → 实现当字符串」这个 bug。
 * 那不是 aci 独有的失误，而是**所有合体入口共用一套分派机制**的结构性风险：
 *  - schema 是手写 JSON 字符串，重复键会被 `JSONObject` 静默取后者；
 *  - `routes` 与 action 枚举是两处手写，任何一边改漏就出现「模型看得见但点不动」的 action；
 *  - 被吸收的旧名如果没真正降级，模型会同时看到两个入口，分类构架自相矛盾；
 *  - 能力目录 / 意图表 / 屏幕全家桶里只要残留旧名，就等于把模型往「未知工具」上引。
 *
 * 所以这里不写「aci 应该怎样」，而是**对每一个合体入口做同一套断言**，
 * 新增一族合体工具会自动被这层测试覆盖，不需要再补一批测试。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnifiedToolingContractTest {

    /**
     * 用**带 Context 的真实装配**建注册表：不带 Context 时 `fluid_cloud` / `ui_dsl_spec` /
     * `zx_catalog` 等一批工具不会注册，测试会把它们误判成「幽灵名」。
     * 要校验的就是线上那一份工具集，不是无 Context 的裁剪版。
     */
    private val reg: QuroToolRegistry by lazy { buildQuroRegistry(RuntimeEnvironment.getApplication()) }

    private val visibleNames: Set<String> by lazy { reg.all().map { it.name }.toSet() }

    private fun demo(name: String): QuroUnifiedDispatchTool =
        reg.all().firstOrNull { it.name == name } as? QuroUnifiedDispatchTool
            ?: throw AssertionError("合体入口 $name 没有下发为 QuroUnifiedDispatchTool")

    /** schema 里 `action` 的枚举值。 */
    private fun schemaActions(tool: QuroTool): List<String> {
        val schema = JSONObject(tool.parametersJson)
        val action = schema.optJSONObject("properties")?.optJSONObject("action")
            ?: return emptyList()
        val arr = action.optJSONArray("enum") ?: return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }

    /**
     * 扫描 JSON 文本里 `properties` 这一层对象的**全部键**（含重复）。
     *
     * `JSONObject` 解析时重复键直接覆盖，重复本身不可见 —— 所以必须看点字符级文本。
     */
    private fun propertiesKeys(schema: String): List<String> {
        val propsAt = schema.indexOf("\"properties\"")
        if (propsAt < 0) return emptyList()
        val open = schema.indexOf('{', propsAt)
        if (open < 0) return emptyList()
        val keys = ArrayList<String>()
        var i = open
        var depth = 0
        var expectKey = false
        while (i < schema.length) {
            val c = schema[i]
            when {
                c == '"' -> {
                    val sb = StringBuilder()
                    i++
                    while (i < schema.length && schema[i] != '"') {
                        if (schema[i] == '\\') i++
                        if (i < schema.length) sb.append(schema[i])
                        i++
                    }
                    // 后面紧跟冒号 = 这是一个键
                    var j = i + 1
                    while (j < schema.length && schema[j].isWhitespace()) j++
                    if (j < schema.length && schema[j] == ':' && (depth == 1)) keys.add(sb.toString())
                }
                c == '{' -> { depth++; expectKey = depth == 1 }
                c == '}' -> {
                    depth--
                    if (depth == 0) return keys
                }
                else -> Unit
            }
            i++
        }
        return keys
    }

    // ─────────────────────────── 入口与 action ───────────────────────────

    @Test
    fun 十五个合体入口全部下发给模型() {
        assertEquals(
            "QuroUnifiedToolNames.ALL 必须与真实下发集合一致",
            QuroUnifiedToolNames.ALL, visibleNames.intersect(QuroUnifiedToolNames.ALL)
        )
        assertEquals("合体入口数量应为 15", 15, QuroUnifiedToolNames.ALL.size)
        QuroUnifiedToolNames.ALL.filter { it != "terminal" }.forEach { demo(it) }
    }

    @Test
    fun 每个action都能落到真实注册过的实现() {
        QuroUnifiedToolNames.ALL.filter { it != "terminal" }.forEach { name ->
            val tool = demo(name)
            val actions = schemaActions(tool)
            assertTrue("$name 的 schema 必须声明 action 枚举", actions.isNotEmpty())
            assertEquals(
                "$name：schema 枚举与 routes 键必须一一对应",
                actions.toSet(), tool.routes.keys
            )
            actions.forEach { action ->
                val targets = tool.governedCalls("""{"action":"$action"}""")
                assertTrue("$name(action=$action) 没有任何真实落点", targets.isNotEmpty())
                targets.forEach { (target, _) ->
                    assertNotNull(
                        "$name(action=$action) 指向了不存在的工具「$target」",
                        reg.get(target)
                    )
                }
            }
        }
    }

    @Test
    fun 不带action时默认动作同样能落下() {
        QuroUnifiedToolNames.ALL.filter { it != "terminal" }.forEach { name ->
            val tool = demo(name)
            val targets = tool.governedCalls("{}")
            assertTrue("$name 不带参数时没有默认落点（模型随手调用会直接报错）", targets.isNotEmpty())
            targets.forEach { (target, _) ->
                assertNotNull("$name 的默认动作指向不存在的工具「$target」", reg.get(target))
            }
        }
    }

    @Test
    fun 合体入口不会把调用转回自己() {
        QuroUnifiedToolNames.ALL.filter { it != "terminal" }.forEach { name ->
            val tool = demo(name)
            schemaActions(tool).forEach { action ->
                tool.governedCalls("""{"action":"$action"}""").forEach { (target, _) ->
                    assertTrue("$name 把 $action 转发给了自己，会无限递归", target != name)
                }
            }
        }
    }

    @Test
    fun schema里不允许出现重复键() {
        val bad = ArrayList<String>()
        reg.all().forEach { tool ->
            val keys = propertiesKeys(tool.parametersJson)
            val dup = keys.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            if (dup.isNotEmpty()) bad += "${tool.name}: $dup"
        }
        assertTrue(
            "参数 schema 出现重复键（JSONObject 会静默取后者，模型看到的是错的那个类型）：$bad",
            bad.isEmpty()
        )
    }

    @Test
    fun 描述必须讲清每一个action() {
        val missing = ArrayList<String>()
        QuroUnifiedToolNames.ALL.filter { it != "terminal" }.forEach { name ->
            val tool = demo(name)
            schemaActions(tool).filterNot { tool.description.contains(it) }
                .forEach { missing += "$name.$it" }
        }
        assertTrue("这些 action 在描述里没有出现，模型只能靠猜：$missing", missing.isEmpty())
    }

    // ─────────────────────────── 降级与目录 ───────────────────────────

    @Test
    fun 已降级的旧名一律不再下发给模型() {
        val leaked = reg.legacyNames().intersect(visibleNames)
        assertTrue("这些旧名既在 legacy 又在可见集合里，模型会看到两个入口：$leaked", leaked.isEmpty())
    }

    @Test
    fun 能力目录里不存在死条目() {
        val dead = ToolCapabilityDirectory.handbookToolNames - visibleNames
        assertTrue(
            "能力目录里有 ${dead.size} 条元数据挂在不存在/已降级的工具名下（install 时会被静默丢弃）：$dead",
            dead.isEmpty()
        )
    }

    @Test
    fun 相关工具与屏幕全家桶只能指向真实工具() {
        val dangling = ArrayList<String>()
        visibleNames.forEach { name ->
            val info = ToolCapabilityDirectory.getToolInfo(name) ?: return@forEach
            info.relatedTools.filterNot { it in visibleNames }.forEach { dangling += "$name → $it" }
        }
        ToolCapabilityDirectory.SCREEN_TOOL_FAMILY.filterNot { it in visibleNames }
            .forEach { dangling += "SCREEN_TOOL_FAMILY → $it" }
        assertTrue("这些「相关工具」指向了不存在的工具：$dangling", dangling.isEmpty())
    }

    @Test
    fun 意图表的键不重复且只指向真实工具() {
        val keys = ToolCapabilityDirectory.INTENT_MAPPINGS.map { it.first }
        val dup = keys.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue("意图表出现重复键（mapOf 时代会被静默丢弃）：$dup", dup.isEmpty())

        val stale = ArrayList<String>()
        ToolCapabilityDirectory.INTENT_MAPPINGS.forEach { (intent, tools) ->
            tools.forEach { t ->
                val toolName = t.substringBefore("(").trim()
                if (toolName !in visibleNames) stale += "$intent → $t"
            }
        }
        assertTrue("意图表指向了不存在的工具（会在输出时被静默过滤掉，等于没有推荐）：$stale", stale.isEmpty())
    }

    @Test
    fun RAG概念表覆盖全部合体入口() {
        val uncovered = QuroUnifiedToolNames.ALL.filter { RagConcept.triggersOf(it).isEmpty() }
        assertTrue(
            "这些合体入口在 RagConcept 里没有任何触发词，概念加权恒为 0（模糊口语永远召不回）：$uncovered",
            uncovered.isEmpty()
        )
    }

    @Test
    fun 退役名字指路表只能指向真实工具() {
        // 「旧名 → 现在改叫谁」这张表是直接念给模型听的：指到一个已降级的名字，
        // 模型照着调就是「未知工具」——比不提示更糟。
        val stale = removedToolNotes.filterValues { it !in visibleNames }
        assertTrue("退役名字指路表指向了不存在/已降级的工具：$stale", stale.isEmpty())
    }

    @Test
    fun 组合动作必须把每个子调用都报给治理层() {
        // 「单对单编排」的实际形态：一个 action 串多个旧实现。引擎按 governedCalls 里的
        // 真实目标算审批/超时/预算，漏报等于绕过治理。
        val problems = ArrayList<String>()
        QuroUnifiedToolNames.ALL.filter { it != "terminal" }.forEach { name ->
            val tool = demo(name)
            tool.routes.filterValues { it.size > 1 }.forEach { (action, chain) ->
                val gov = tool.governedCalls("""{"action":"$action"}""").map { it.first }
                if (gov.size < 2) problems += "$name.$action 只报出 ${gov.size} 个目标"
                if (chain.toSet() != gov.toSet()) {
                    problems += "$name.$action 报出的目标 $gov 与路由链 $chain 不一致"
                }
                gov.forEach { if (reg.get(it) == null) problems += "$name.$action → $it 不存在" }
            }
        }
        assertTrue("组合动作的治理声明有问题：$problems", problems.isEmpty())
    }

    @Test
    fun 使用提示只能挂在真实工具上() {
        // 提示集是按工具名查的：挂在已合体/已退役的名字下，这段「用户会怎么说」
        // 就永远不会被注入 → 那类口语依旧映射不到工具（RagConcept 出过同样的洞）。
        val stale = QuroToolUsageHints.TOOL_USAGE_HINTS.keys - visibleNames
        assertTrue("使用提示挂在不存在/已降级的工具名下：$stale", stale.isEmpty())
    }
}
