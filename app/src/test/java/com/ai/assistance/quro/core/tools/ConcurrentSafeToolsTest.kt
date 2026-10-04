package com.ai.assistance.quro.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具与环境层：**只读并发**的安全不变量。
 *
 * ## 为什么这组测试只看白名单不看真跑
 * 真跑并发要构造 [android.content.Context] 与 14 个真实工具的运行环境，
 * 那是仪器测试的事；这里守的是**决策逻辑**本身的不变量 ——
 * 恰恰是「改成并发」最容易悄悄破坏的那几条。
 *
 * ## 三条铁律
 *  1. **顺序不能变**：assistant[tool_calls] 与 tool[] 靠下标配对，并发后必须按原序归位。
 *  2. **有副作用的绝不并发**：漏并发的代价只是慢，错并发的代价是数据丢失。
 *  3. **双重判据**：白名单 ∩ 实现自述，两者都成立才并发。
 */
class ConcurrentSafeToolsTest {

    @Test
    fun whitelist_contains_no_known_mutating_tools() {
        // 🔴 回归防护：白名单里绝不能出现这些写/发/启停类工具。
        // 任何一个进来都可能导致重复发送、竞态删除、双重授权弹窗。
        val forbidden = listOf(
            "send_sms", "read_sms", "write_file", "delete_file", "make_directory",
            "move_file", "copy_file", "set_clipboard", "vibrate", "launch_app",
            "search_and_launch_app", "install_app", "freeze_app",
            "terminal_run", "terminal_exec", "terminal_write", "terminal_kill",
            "root_exec", "shizuku_exec", "shizuku_root_exec", "linux_run", "linux_install",
            "execute_intent", "send_broadcast", "write_calendar", "http_request",
            "open_web", "ai_browser", "browser_act", "apk_plugin", "speak",
        )
        val leaked = QuroToolEngine.CONCURRENT_SAFE intersect forbidden.toSet()
        assertTrue("白名单混入了有副作用的工具：$leaked", leaked.isEmpty())
    }

    @Test
    fun whitelist_excludes_network_tools_on_purpose() {
        // 网络类并发易触发上游限流，刻意不收录（KDoc 已写明理由）
        val net = listOf("web_search", "http_request", "read_url")
        for (n in net) {
            assertFalse("$n 不应并发", n in QuroToolEngine.CONCURRENT_SAFE)
        }
    }

    @Test
    fun whitelist_excludes_shared_state_reads_on_purpose() {
        // terminal_status 读的是被其他工具写的共享会话状态，并发会读到撕裂值
        assertFalse("terminal_status 不应并发", "terminal_status" in QuroToolEngine.CONCURRENT_SAFE)
        // browse_files 大目录遍历抢 IO，会拖慢同批其他读
        assertFalse("browse_files 不应并发", "browse_files" in QuroToolEngine.CONCURRENT_SAFE)
    }

    @Test
    fun whitelist_only_contains_names_we_actually_tagged() {
        // 白名单与 readOnly 标记必须一一对应：
        // 白名单里有但没打标 = 永远不会并发（死条目，会误导后来人以为已经并发了）
        val tagged = setOf(
            "get_current_time", "get_device_info", "calculate",
            "get_battery", "get_wifi_info", "get_network_info", "get_sensors",
            "get_package_name", "get_active_notifications", "get_bluetooth_status",
            "file_info", "find_files", "root_status", "shizuku_status",
        )
        val untagged = QuroToolEngine.CONCURRENT_SAFE - tagged
        assertTrue("白名单里这些没打 readOnly 标，永远不会并发：$untagged", untagged.isEmpty())
    }

    @Test
    fun default_read_only_is_false_for_undeclared_tools() {
        // 默认必须是有副作用（保守）。若默认改成 true，未声明的工具会全部被并发 ——
        // 那等于把 200+ 个工具一次性置于竞态风险下。
        val anonymous = object : QuroTool {
            override val name = "some_undeclared_tool"
            override val description = "d"
            override val parametersJson = """{"type":"object","properties":{}}"""
            override fun run(context: android.content.Context, arguments: String): String = ""
        }
        assertFalse("未声明的工具必须默认非只读", anonymous.readOnly)
    }

    @Test
    fun read_only_tool_can_opt_in() {
        val optIn = object : QuroTool {
            override val name = "my_reader"
            override val description = "d"
            override val parametersJson = """{"type":"object","properties":{}}"""
            override val readOnly = true
            override fun run(context: android.content.Context, arguments: String): String = "ok"
        }
        assertTrue(optIn.readOnly)
    }

    @Test
    fun whitelist_is_not_empty_and_stays_conservative() {
        // 反向约束：别有人为了「并发更快」把白名单清空或塞满
        val n = QuroToolEngine.CONCURRENT_SAFE.size
        assertTrue("白名单不应为空（会退化成全串行，等于没做）", n > 0)
        assertTrue("白名单不应超过 30 个（235 工具里放太多等于没设防）", n <= 30)
    }
}
