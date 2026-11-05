package com.ai.assistance.quro.core.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审批策略判定单测：钉死「哪些工具要审批、哪些不要」的口径，
 * 避免以后调清单时把危险工具漏掉、或把纯读工具误拦。
 */
class ApprovalPolicyTest {

    @Test
    fun `危险工具需要审批`() {
        // 执行类
        assertTrue(ApprovalPolicy.needsApproval("termux_exec", ""))
        assertTrue(ApprovalPolicy.needsApproval("terminal_run", ""))
        assertTrue(ApprovalPolicy.needsApproval("root_exec", ""))
        assertTrue(ApprovalPolicy.needsApproval("shizuku_exec", ""))
        assertTrue(ApprovalPolicy.needsApproval("adb_term", ""))
        assertTrue(ApprovalPolicy.needsApproval("priv_exec", ""))
        // 文件写入/删除
        assertTrue(ApprovalPolicy.needsApproval("write_file", ""))
        assertTrue(ApprovalPolicy.needsApproval("delete_file", ""))
        assertTrue(ApprovalPolicy.needsApproval("move_file", ""))
        assertTrue(ApprovalPolicy.needsApproval("copy_file", ""))
        // 应用/包
        assertTrue(ApprovalPolicy.needsApproval("install_app", ""))
        assertTrue(ApprovalPolicy.needsApproval("uninstall", ""))
        assertTrue(ApprovalPolicy.needsApproval("apk_editor", ""))
        // 手机控制
        assertTrue(ApprovalPolicy.needsApproval("tap_screen", ""))
        assertTrue(ApprovalPolicy.needsApproval("send_sms", ""))
        assertTrue(ApprovalPolicy.needsApproval("screenshot", ""))
        assertTrue(ApprovalPolicy.needsApproval("screen_record", ""))
        // 定时/日历
        assertTrue(ApprovalPolicy.needsApproval("set_alarm", ""))
        assertTrue(ApprovalPolicy.needsApproval("write_calendar", ""))
    }

    @Test
    fun `纯读工具不需要审批`() {
        assertFalse(ApprovalPolicy.needsApproval("read_file", ""))
        assertFalse(ApprovalPolicy.needsApproval("list_files", ""))
        assertFalse(ApprovalPolicy.needsApproval("get_device_info", ""))
        assertFalse(ApprovalPolicy.needsApproval("get_battery", ""))
        assertFalse(ApprovalPolicy.needsApproval("get_clipboard", ""))
        assertFalse(ApprovalPolicy.needsApproval("read_sms", ""))
        assertFalse(ApprovalPolicy.needsApproval("calculate", ""))
        assertFalse(ApprovalPolicy.needsApproval("search_provider", ""))
        assertFalse(ApprovalPolicy.needsApproval("read_calendar", ""))
    }

    @Test
    fun `工具显式声明 requiresApproval 时强制审批`() {
        // 即使名字不在清单里，只要实现声明了 requiresApproval=true 就要问
        assertTrue(ApprovalPolicy.needsApproval("some_custom_tool", "", declared = true))
        // 未声明则按清单
        assertFalse(ApprovalPolicy.needsApproval("some_custom_tool", "", declared = false))
    }

    @Test
    fun `git 只读操作不审批_写操作审批`() {
        // 只读
        assertFalse(ApprovalPolicy.needsApproval("git", "{\"command\":\"status\"}"))
        assertFalse(ApprovalPolicy.needsApproval("git", "{\"command\":\"log\"}"))
        assertFalse(ApprovalPolicy.needsApproval("git", "{\"command\":\"diff\"}"))
        // 写操作
        assertTrue(ApprovalPolicy.needsApproval("git", "{\"command\":\"commit -m x\"}"))
        assertTrue(ApprovalPolicy.needsApproval("git", "{\"command\":\"push origin main\"}"))
        assertTrue(ApprovalPolicy.needsApproval("git", "{\"command\":\"checkout -b new\"}"))
    }

    @Test
    fun `factsFor 能拆出关键参数`() {
        // termux 命令
        val termuxFacts = ApprovalPolicy.factsFor("termux_exec", "{\"command\":\"rm -rf /\"}")
        assertTrue(termuxFacts.any { it.label == "命令" && it.value == "rm -rf /" && it.emphasis })

        // 写文件
        val writeFacts = ApprovalPolicy.factsFor("write_file", "{\"path\":\"/sdcard/x.txt\",\"content\":\"hello\"}")
        assertTrue(writeFacts.any { it.label == "目标文件" && it.value == "/sdcard/x.txt" && it.emphasis })
        assertTrue(writeFacts.any { it.label == "写入内容" })

        // 发短信
        val smsFacts = ApprovalPolicy.factsFor("send_sms", "{\"phone\":\"10086\",\"message\":\"hi\"}")
        assertTrue(smsFacts.any { it.label == "收件人" && it.value == "10086" && it.emphasis })
    }

    @Test
    fun `labelFor 返回人类可读标签`() {
        assertEquals("执行终端命令", ApprovalPolicy.labelFor("termux_exec"))
        assertEquals("写入文件", ApprovalPolicy.labelFor("write_file"))
        assertEquals("发送短信", ApprovalPolicy.labelFor("send_sms"))
        assertEquals("截屏", ApprovalPolicy.labelFor("screenshot"))
        // 未知工具回退到原名
        assertEquals("mystery_tool", ApprovalPolicy.labelFor("mystery_tool"))
    }
}
