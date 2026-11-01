package com.ai.assistance.quro.core.tools

import com.ai.assistance.quro.core.tools.ToolCapabilityDirectory.ToolCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具分类架构的**全量自检**。
 *
 * ## 这套断言为什么必须存在
 *
 * 改造前分类是「两份手写 when + 靠注释互相提醒同源」：
 * `ToolCapabilityDirectory.inferCategory`（末尾 `else -> BASIC`）与
 * `QuroToolRouter.categorize`（末尾 `else -> null`）。
 * 谁都没有全量覆盖约定，分类质量只能靠人肉盯，结果是：
 *
 *  - 工具分不出类 → `tool_router` 按分类列不出来 → 模型查不到；
 *  - 查不到就只好把工具全塞进 `ALWAYS_ON` 硬撑「至少能看见」；
 *  - 常驻集因此膨胀到 154 个，每轮请求固定多带十几万字符 schema → **响应变慢**。
 *
 * 所以「分类完整性」不是美观问题，它直接决定常驻集能不能小、响应快不快。
 * 这里把约定钉死：**任何注册工具分类失败，测试直接红**，而不是等用户投诉「AI 不会用」。
 */
class ToolTaxonomyTest {

    private fun allSpecs() = buildQuroRegistry(null).specs()

    /** ① 每个注册工具都必须有明确分类，不允许落兜底。新增工具忘了归类，这里会立刻失败。 */
    @Test
    fun 全部工具都必须被明确分类() {
        val specs = allSpecs()
        val unclassified = specs.map { it.name }.filter { !ToolTaxonomy.isClassified(it) }
        assertTrue(
            "有 ${unclassified.size}/${specs.size} 个工具没有分类规则（会从分类检索里消失）：" +
                unclassified.take(60).joinToString(", "),
            unclassified.isEmpty(),
        )
    }

    /** ② 分类结果不能是 null，也不能落回 BASIC 冒充「基础工具」。 */
    @Test
    fun 分类结果必须是明确分类而非兜底() {
        val specs = allSpecs()
        val names = specs.map { it.name }
        val fallback = names.filter { ToolTaxonomy.categoryOf(it) == ToolTaxonomy.UNCLASSIFIED }
        // BASIC 是真实分类（时间/计算/设备信息），这里只允许真正属于它的那几个
        val allowedBasic = setOf(
            "get_current_time", "get_device_info", "calculate", "get_battery",
            "get_wifi_info", "get_network_info", "get_sensors", "vibrate",
            "get_clipboard", "set_clipboard", "get_active_notifications",
            "get_bluetooth_status", "vnc",
        )
        val suspicious = fallback.filter { it !in allowedBasic }
        assertTrue("这些工具靠兜底才拿到分类：${suspicious.joinToString(", ")}", suspicious.isEmpty())
    }

    /** ③ 目录侧与路由侧必须同源（历史上这两份逻辑已经漂移过，cms_* 两边归属不同）。 */
    @Test
    fun 目录侧与路由侧分类必须一致() {
        val specs = allSpecs()
        val router = QuroToolRouter(specs)
        val m = router.javaClass.getDeclaredMethod("categorize", String::class.java)
        m.isAccessible = true
        val diffs = mutableListOf<String>()
        for (s in specs) {
            val viaRouter = m.invoke(router, s.name) as ToolCategory?
            val viaTaxonomy = ToolTaxonomy.categoryOf(s.name)
            if (viaRouter != viaTaxonomy) diffs.add("${s.name}: router=$viaRouter taxonomy=$viaTaxonomy")
        }
        assertTrue("目录侧与路由侧分类不一致（说明又出现两份实现）：\n" + diffs.take(30).joinToString("\n"), diffs.isEmpty())
    }

    /** ④ 关键工具的归属必须正确 —— 这几个错了会直接导致「AI 用错工具」。 */
    @Test
    fun 关键工具归属正确() {
        val cases = mapOf(
            // 后台虚拟屏必须独立，混进无障碍会让 AI 去抢用户主屏
            "bg_screen" to ToolCategory.VIRTUAL_SCREEN,
            // 界面导航不能被 ui_ 前缀吞进 UI_CARDS（36 个 UiActionSpec 的归处）
            "ui_open_build" to ToolCategory.APP_NAVIGATION,
            "ui_open_permission" to ToolCategory.APP_NAVIGATION,
            "ui_toggle_deepthink" to ToolCategory.APP_NAVIGATION,
            // 真正的卡片渲染工具仍在 UI_CARDS
            "ui_card" to ToolCategory.UI_CARDS,
            "ui_widget" to ToolCategory.UI_CARDS,
            // 文件操作
            "write_file" to ToolCategory.FILE_OPERATION,
            "read_text_file" to ToolCategory.FILE_OPERATION,
            // 终端系
            "termux" to ToolCategory.TERMUX,
            "terminal" to ToolCategory.TERMINAL_LINUX,
            // ADB 两个方向
            "adb_dev" to ToolCategory.ADB_DEBUG,
            // 通讯/日程
            "send_sms" to ToolCategory.COMMUNICATION,
            "set_alarm" to ToolCategory.COMMUNICATION,
            // 联网
            "http_request" to ToolCategory.NETWORK_WEB,
            "web_search" to ToolCategory.NETWORK_WEB,
        )
        val wrong = cases.filter { (name, want) ->
            val got = ToolTaxonomy.categoryOf(name)
            got != want
        }.map { (name, want) -> "$name 期望 $want 实际 ${ToolTaxonomy.categoryOf(name)}" }
        assertTrue("关键工具分类错误：\n" + wrong.joinToString("\n"), wrong.isEmpty())
    }

    /** ⑤ 常驻核心集里的每个名字都必须真实存在（写错名字等于该工具永远不会下发）。 */
    @Test
    fun 常驻核心集必须全部真实存在() {
        val names = allSpecs().map { it.name }.toSet()
        // spawn_subagent 由 QuroAssistant 在运行时动态注册（见 QuroAssistant.SUBAGENT_TOOL_NAME），
        // buildQuroRegistry 拿不到，故豁免。除此之外任何「幽灵名字」都要报错 ——
        // 原来 ALWAYS_ON 里的 visual_ask 就是拼错的（真实名是 visual_question），从来没生效过。
        val runtimeRegistered = setOf("spawn_subagent")
        val ghosts = QuroToolRouter.ALWAYS_ON.filter { it !in names && it !in runtimeRegistered }
        assertTrue("ALWAYS_ON 里有不存在的工具名（写错了）：${ghosts.joinToString(", ")}", ghosts.isEmpty())
    }

    /**
     * ⑥ 常驻集必须保持精简。
     *
     * 这不是「越少越好」的数字游戏 —— 常驻集大小直接等于每轮固定 token 开销，
     * 一旦有人图省事往里加工具，响应就会悄悄变慢而没人发现。
     * 需要通用能力请去 [ToolTaxonomy] 加分类规则，让检索去解决。
     *
     * 上限 48 的由来：核心 43 个 + 确定性出图三件套（codecanvas_probe/script/markup）
     * + 1 个余量。出图三件套是用户历史上明确反馈过「新加的代码生图 AI 根本查不到」的能力，
     * 先于分类检索保留在常驻里，不拿它冒险。
     */
    @Test
    fun 常驻核心集不得超过48个() {
        assertTrue(
            "ALWAYS_ON 已 ${QuroToolRouter.ALWAYS_ON.size} 个，超过 48 —— 常驻集每多一个，" +
                "每轮请求就多一份 schema。要暴露新工具请先给它加分类规则，让 tool_router 检索到它。",
            QuroToolRouter.ALWAYS_ON.size <= 48,
        )
    }

    /** ⑦ 关键能力必须在常驻集里（缺了模型可能根本发现不了它）。 */
    @Test
    fun 关键能力必须常驻() {
        val must = listOf(
            "bg_screen",      // 后台虚拟屏主力，原来不在常驻 —— AI 因此几乎不会用它
            "termux",         // 用户说「终端」时首选
            "rag_search",     // 检索底座
            "read_screen", "tap_screen", "input_text",  // 操作屏幕的最小闭环
            "ui_widget", "ui_card", "zx_web_card",      // 产出通道
        )
        val missing = must.filter { it !in QuroToolRouter.ALWAYS_ON }
        assertTrue("这些关键能力不在常驻集，模型可能完全发现不了：${missing.joinToString(", ")}", missing.isEmpty())
    }

    /** ⑧ 每个分类下都得真的有工具，不能有空壳分类（空分类会让模型查了也没有结果）。 */
    @Test
    fun 不能存在空分类() {
        val specs = allSpecs()
        val used = specs.map { ToolTaxonomy.categoryOf(it.name) }.toSet()
        // WORKFLOW/APP_NAVIGATION 是本轮新增的两个分类，其余必须有工具
        val empty = ToolCategory.values().filter { it !in used }
        assertTrue("这些分类下一个工具都没有：${empty.joinToString(", ")}", empty.isEmpty())
    }

    /** ⑨ AF：分类规则本身要覆盖关键前缀（防止后续重构把规则表改残）。 */
    @Test
    fun 规则表必须覆盖主要族系() {
        val families = listOf(
            "zx_" to ToolCategory.ZXWIDGET,
            "genui_" to ToolCategory.GENUI,
            "mcp_" to ToolCategory.NETWORK_WEB,
            "cms_" to ToolCategory.CMS_DEVELOPMENT,
            "memory_" to ToolCategory.KNOWLEDGE_MEMORY,
            "workspace_" to ToolCategory.WORKSPACE,
            "shizuku" to ToolCategory.SECURITY,
            "ui_open_" to ToolCategory.APP_NAVIGATION,
        )
        val broken = families.filter { (prefix, want) ->
            ToolTaxonomy.categoryOf("${prefix}__probe__") != want
        }.map { (p, want) -> "$p* 期望 $want 实际 ${ToolTaxonomy.categoryOf("${p}__probe__")}" }
        assertTrue("规则表族系覆盖有问题：\n" + broken.joinToString("\n"), broken.isEmpty())
    }

    /** ⑩ 兜底分类不能冒充 BASIC：UNCLASSIFIED 必须是独立语义。 */
    @Test
    fun 兜底语义必须是显式的() {
        // UNCLASSIFIED 目前复用 BASIC 枚举值（不新增枚举以免影响 UI 分组），
        // 但 isClassified 必须能把「靠兜底」和「真的属于 BASIC」区分开。
        assertFalse("臆造的工具名不该被判为已分类", ToolTaxonomy.isClassified("totally_made_up_tool_xyz"))
        assertTrue("真实工具应判为已分类", ToolTaxonomy.isClassified("write_file"))
        assertEquals(
            "兜底分类应落在 BASIC 枚举上（UI 分组兼容）",
            ToolCategory.BASIC,
            ToolTaxonomy.categoryOf("totally_made_up_tool_xyz"),
        )
    }

    /**
     * ⑪ 终端族：30 来个工具必须各归其位，且**主入口 termux 单独成类**。
     *
     * 🔴 为什么单独钉这一条：终端相关的工具有 30 多个（termux / terminal 家族 12 个 /
     * linux_* 7 个 / quroterm_exec / run_code 系 4 个 / shizuku_exec / root_exec / tmux / moyu / harness）。
     * 它们分属**三套不同的执行环境**（proot Ubuntu、应用沙盒、自研极轻沙盒），
     * 一旦分类归错或混在一起，模型就会只反复用最眼熟的那一个，
     * 表现为「明明装了 apt 却说找不到命令」「跑个 ls 也要启动整个 Ubuntu」。
     */
    @Test
    fun 终端族必须分层清晰() {
        // 主入口 termux 必须单独一类（提示词靠它做「只认一个入口」）
        assertEquals("termux 必须归 TERMUX 而不是混进 TERMINAL_LINUX",
            ToolCategory.TERMUX, ToolTaxonomy.categoryOf("termux"))

        // proot Ubuntu 的旧入口统一归 TERMINAL_LINUX
        listOf(
            "linux_run", "linux_install", "linux_pkg", "linux_start", "linux_stop",
            "linux_status", "linux_process", "quroterm_exec", "terminal",
        ).forEach {
            assertEquals("$it 应归 TERMINAL_LINUX", ToolCategory.TERMINAL_LINUX, ToolTaxonomy.categoryOf(it))
        }

        // 应用沙盒终端家族同样归 TERMINAL_LINUX（同类，靠 description 区分环境）
        listOf(
            "terminal_run", "terminal_exec", "terminal_write", "terminal_keys",
            "terminal_interrupt", "terminal_kill", "terminal_status",
            "terminal_sessions", "terminal_session_new", "terminal_session_switch",
            "terminal_session_kill",
        ).forEach {
            assertEquals("$it 应归 TERMINAL_LINUX", ToolCategory.TERMINAL_LINUX, ToolTaxonomy.categoryOf(it))
        }

        // 代码执行类也归终端域（都是「跑起来拿结果」）
        listOf("run_code", "python_run", "code_runner", "polyglot_run").forEach {
            assertEquals("$it 应归 TERMINAL_LINUX", ToolCategory.TERMINAL_LINUX, ToolTaxonomy.categoryOf(it))
        }
    }

    /** ⑫ 终端主入口必须常驻，否则模型得先检索才可能发现「终端」这个能力。 */
    @Test
    fun 终端主入口必须常驻() {
        assertTrue(
            "termux 不在常驻集 —— 用户说「终端」时模型可能根本发现不了它",
            "termux" in QuroToolRouter.ALWAYS_ON,
        )
        assertTrue(
            "shizuku_exec 不在常驻集 —— Android 系统命令（am/pm/settings）没有别的通道可走",
            "shizuku_exec" in QuroToolRouter.ALWAYS_ON,
        )
    }
}
