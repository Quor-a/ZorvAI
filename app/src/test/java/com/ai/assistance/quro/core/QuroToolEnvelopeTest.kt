package com.ai.assistance.quro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具与环境层「出口」的单测：[QuroToolEnvelope] + [ToolStatus] + [QuroToolResult] 的成败判定。
 *
 * 为什么需要：模型每轮看到的工具返回形状必须**稳定**。改造前 `QuroToolResult` 只有
 * `(name, result)` 两个字符串，成败靠 `name` 里塞的 `"success"` / `"error"` 魔法值，
 * 没有 code、没有异常语义；每个工具返回给模型的文本形态都不一样（有的裸 JSON、有的中文报错、
 * 有的是 stack trace）。形状一变，模型就靠猜字段 → 猜错 → 复读/答非所问。
 *
 * 这里锁死三件事：
 * 1. 成功和失败渲染出来的**形状**不同且都可读；
 * 2. `code` 推不出来时要有确定性的兜底（[ToolStatus.from]），绝不留空；
 * 3. 「工具名被塞进 name 的调用点」（tool_router / 子智能体）不能被判成失败。
 */
class QuroToolEnvelopeTest {

    // ---------- 成功信封 ----------

    @Test
    fun `success envelope is shaped`() {
        val text = QuroToolEnvelope.of("terminal_exec", QuroToolResult.Success("hello"))
        assertTrue(text, text.startsWith("[tool] terminal_exec\n"))
        assertTrue(text, text.contains("status: ok"))
        assertTrue(text, text.contains("hello"))
        assertFalse(text, text.contains("status: error"))
        assertFalse(text, text.contains("hint:"))
        assertFalse(text, text.contains("code:"))
    }

    @Test
    fun `success envelope keeps multi line body`() {
        val body = "line1\nline2\nline3"
        val text = QuroToolEnvelope.of("file_read", QuroToolResult.Success(body))
        assertTrue(text.isNotEmpty())
        assertEquals(body, text.substringAfter("result:\n"))
    }

    @Test
    fun `tool name carrying result is reported as success not error`() {
        // tool_router / 子智能体这类调用点把**工具名**塞进 name，正文才是 payload。
        // 若按「name 不是 success 就算失败」处理，正常的工具目录查询会被渲染成 status: error。
        val text = QuroToolEnvelope.of("tool_router", QuroToolResult("tool_router", "1. terminal_exec"))
        assertTrue(text.isNotEmpty())
        assertTrue(text, text.contains("status: ok"))
        assertFalse(text, text.contains("status: error"))
    }

    // ---------- 失败信封 ----------

    @Test
    fun `failed envelope carries code and message`() {
        val text = QuroToolEnvelope.of(
            "terminal_exec",
            QuroToolResult.Failed("permission_denied", "Permission denied"),
        )
        assertTrue(text.isNotEmpty())
        assertTrue(text, text.startsWith("[tool] terminal_exec\n"))
        assertTrue(text, text.contains("status: error"))
        assertTrue(text, text.contains("code: permission_denied"))
        assertTrue(text, text.contains("message: Permission denied"))
        assertTrue(text, text.contains("result:"))
    }

    @Test
    fun `failed envelope without code infers status from raw text`() {
        val text = QuroToolEnvelope.of("terminal_exec", QuroToolResult.Error("timed out after 30s"))
        assertTrue(text.isNotEmpty())
        assertTrue(text, text.contains("status: error"))
        assertTrue(text, text.contains("code: timeout"))
    }

    @Test
    fun `failed envelope always ships an actionable hint`() {
        val text = QuroToolEnvelope.of("web_search", QuroToolResult.Error("boom"))
        assertTrue(text.isNotEmpty())
        assertTrue(text, text.contains("status: error"))
        assertTrue(text, text.contains("hint:"))
        // 推不出原因时只写能确定的事实，不编一个假 code
        assertTrue(text, text.contains("code: unknown"))
    }

    @Test
    fun `error factory result renders as error`() {
        val text = QuroToolEnvelope.of("calculator", QuroToolResult.Error("division by zero"))
        assertTrue(text.isNotEmpty())
        assertTrue(text, text.contains("status: error"))
        assertFalse(text, text.contains("status: ok\n"))
    }

    // ---------- 超长结果 ----------

    @Test
    fun `oversized result is labelled truncated`() {
        val big = "x".repeat(QuroToolEnvelope.MAX_TEXT + 500)
        val text = QuroToolEnvelope.of("terminal_exec", QuroToolResult.Success(big))
        assertTrue(text.isNotEmpty())
        assertTrue(text, text.contains("... [truncated: original "))
        assertTrue(text, text.contains("]"))
        // 截断后的正文长度符合上限
        val body = text.substringAfter("result:\n")
        assertTrue("body=${body.length}", body.length < QuroToolEnvelope.MAX_TEXT + 200)
    }

    @Test
    fun `result exactly at cap is not truncated`() {
        val exact = "y".repeat(QuroToolEnvelope.MAX_TEXT)
        val text = QuroToolEnvelope.of("terminal_exec", QuroToolResult.Success(exact))
        assertFalse(text, text.contains("truncated"))
    }

    // ---------- ToolStatus 归一化 ----------

    @Test
    fun `tool status is normalized from free text`() {
        assertEquals(ToolStatus.PERMISSION_DENIED, ToolStatus.from("Permission denied"))
        assertEquals(ToolStatus.PERMISSION_DENIED, ToolStatus.from("没有权限写入"))
        assertEquals(ToolStatus.TIMEOUT, ToolStatus.from("Command timed out"))
        assertEquals(ToolStatus.NOT_FOUND, ToolStatus.from("no such file or directory"))
        assertEquals(ToolStatus.NOT_FOUND, ToolStatus.from("找不到该文件"))
        assertEquals(ToolStatus.RESOURCE_EXHAUSTED, ToolStatus.from("out of memory"))
        assertEquals(ToolStatus.RESOURCE_EXHAUSTED, ToolStatus.from("进程被 killed"))
        assertEquals(ToolStatus.INVALID_INPUT, ToolStatus.from("invalid argument: missing argument"))
        assertEquals(ToolStatus.EXIT_NON_ZERO, ToolStatus.from("exit status 1"))
    }

    @Test
    fun `tool status does not guess`() {
        assertEquals(ToolStatus.UNKNOWN, ToolStatus.from(""))
        assertEquals(ToolStatus.UNKNOWN, ToolStatus.from("   "))
        assertEquals(ToolStatus.UNKNOWN, ToolStatus.from("这个文件大概是他写的"))
        // 回归：短词误判。「boom」里带着 "oom"，早先 RESOURCE_EXHAUSTED 的关键字表里
        // 有裸 "oom"，于是任何一句普通报错都会被归一化成「内存不足」，code 反而误导模型。
        assertEquals(ToolStatus.UNKNOWN, ToolStatus.from("boom"))
        assertEquals(ToolStatus.UNKNOWN, ToolStatus.from("room is not writable"))
    }

    @Test
    fun `tool result status prefers explicit code`() {
        // 显式 code 优先：正文里出现别的线索词也认显式 code
        assertEquals(
            ToolStatus.EXIT_NON_ZERO,
            QuroToolResult.Failed("exit code", " failed anyway").status(),
        )
        // 没有 code 时才从正文反推
        assertEquals(ToolStatus.INVALID_INPUT, QuroToolResult.Error("invalid argument").status())
        // 反推不出就是 UNKNOWN，不臆测
        assertEquals(ToolStatus.UNKNOWN, QuroToolResult.Error("boom").status())
        assertEquals(ToolStatus.UNKNOWN, QuroToolResult.Success("all good").status())
    }

    // ---------- 成败判定 ----------

    @Test
    fun `ok is structural not literal`() {
        assertTrue(QuroToolResult.Success("a").ok)
        assertTrue(QuroToolResult("terminal_exec", "output").ok)
        assertFalse(QuroToolResult.Error("bad").ok)
        assertFalse(QuroToolResult.Failed("tool_error", "bad").ok)
    }

    @Test
    fun `success and error factories keep backward signature`() {
        val s = QuroToolResult.Success("ok")
        assertEquals("success", s.name)
        assertEquals("ok", s.result)
        assertTrue(s.ok)

        val e = QuroToolResult.Error("nope")
        assertEquals("error", e.name)
        assertEquals("nope", e.result)
        assertFalse(e.ok)
    }
}
