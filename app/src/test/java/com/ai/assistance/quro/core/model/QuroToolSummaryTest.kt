package com.ai.assistance.quro.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QuroToolSummary] 单测。
 *
 * 每条用例都对应用户实际抱怨过的一个缺口 —— 这些断言不是形式主义，
 * 一旦回退成「工具名 + 57 字符 JSON」，这里立刻红。
 */
class QuroToolSummaryTest {

    // ───────── 用户原话级痛点 ─────────

    @Test
    fun `run_code 要能看到执行的是什么代码`() {
        val s = QuroToolSummary.of(
            "run_code",
            """{"code":"import numpy as np\nprint(np.arange(3))\nprint('done')","language":"python"}""",
            "输出:\n[0 1 2]\ndone",
            120,
        )
        assertEquals(QuroToolSummary.Family.CODE, s.family)
        assertEquals("python", s.subtitle)
        // 🔴 全文必须能拿到 —— 旧实现只给 57 字符
        assertTrue(s.bodyText.contains("np.arange(3)"))
        assertTrue(s.bodyText.contains("print('done')"))
        assertTrue(s.metrics.any { it.label == "行数" && it.value == "3" })
    }

    @Test
    fun `write_file 要能看到写到哪个路径以及写了多少`() {
        val s = QuroToolSummary.of(
            "write_file",
            """{"path":"/storage/emulated/0/Documents/notes.md","content":"# 标题\n\n正文一行\n正文二行"}""",
            "已写入",
            40,
        )
        assertEquals(QuroToolSummary.Family.FILE_WRITE, s.family)
        assertEquals("…/Documents/notes.md", s.subtitle)
        assertTrue(s.metrics.any { it.label == "行数" && it.value == "4" })
        assertTrue(s.metrics.any { it.label == "字节" })
    }

    @Test
    fun `web_search 要能看到搜的是什么`() {
        val s = QuroToolSummary.of(
            "web_search",
            """{"query":"Kubernetes 调度算法有哪几种"}""",
            "找到 3 条结果",
            800,
        )
        assertEquals(QuroToolSummary.Family.WEB, s.family)
        assertEquals("Kubernetes 调度算法有哪几种", s.subtitle)
    }

    @Test
    fun `quroterm_exec 要能看到执行的是什么命令`() {
        val s = QuroToolSummary.of(
            "quroterm_exec",
            """{"command":"ls -la /data && echo ok"}""",
            "total 8\ndrwxr  ok\nexit_code=0",
            300,
        )
        assertEquals(QuroToolSummary.Family.TERMINAL, s.family)
        assertEquals("ls -la /data && echo ok", s.subtitle)
        assertEquals(0, s.exitCode)
        assertEquals(QuroToolSummary.Status.SUCCESS, s.status)
    }

    @Test
    fun `终端非零退出码判定为失败并显示退出码`() {
        val s = QuroToolSummary.of(
            "terminal_exec",
            """{"command":"cd /nope"}""",
            "cd: /nope: No such file or directory\nexit code: 2",
            50,
        )
        assertEquals(2, s.exitCode)
        assertEquals(QuroToolSummary.Status.ERROR, s.status)
        assertTrue(s.metrics.any { it.label == "退出码" && it.value == "2" })
    }

    // ───────── 执行中状态 ─────────

    @Test
    fun `结果未回填时状态为执行中且不展示正文`() {
        val s = QuroToolSummary.of("run_code", """{"code":"print(1)"}""", null, 0)
        assertEquals(QuroToolSummary.Status.RUNNING, s.status)
        assertEquals(QuroToolSummary.Body.EMPTY, s.body)
        assertEquals("", s.bodyText)
    }

    @Test
    fun `空白结果等同于执行中`() {
        val s = QuroToolSummary.of("web_search", """{"query":"x"}""", "   ", 0)
        assertEquals(QuroToolSummary.Status.RUNNING, s.status)
    }

    // ───────── 族归类 ─────────

    @Test
    fun `族归类覆盖仓库内真实工具名`() {
        val cases = mapOf(
            "run_code" to QuroToolSummary.Family.CODE,
            "python_exec" to QuroToolSummary.Family.CODE,
            "write_file" to QuroToolSummary.Family.FILE_WRITE,
            "delete_file" to QuroToolSummary.Family.FILE_WRITE,
            "read_file" to QuroToolSummary.Family.FILE_READ,
            "list_dir" to QuroToolSummary.Family.FILE_READ,
            "web_search" to QuroToolSummary.Family.WEB,
            "read_url" to QuroToolSummary.Family.WEB,
            "quroterm_exec" to QuroToolSummary.Family.TERMINAL,
            "terminal_exec" to QuroToolSummary.Family.TERMINAL,
            "root_exec" to QuroToolSummary.Family.TERMINAL,
            "tap" to QuroToolSummary.Family.DEVICE,
            "input_text" to QuroToolSummary.Family.DEVICE,
            "shizuku_exec" to QuroToolSummary.Family.SYSTEM,
            "chat_doc" to QuroToolSummary.Family.DOC,
            "image_gen" to QuroToolSummary.Family.MEDIA,
            "tts_speak" to QuroToolSummary.Family.MEDIA,
            "memory_save" to QuroToolSummary.Family.MEMORY,
            "miniapp_run" to QuroToolSummary.Family.UI,
            "ui_open_panel" to QuroToolSummary.Family.UI,
        )
        cases.forEach { (n, f) ->
            assertEquals("$n 应归 $f", f, QuroToolSummary.familyOf(n))
        }
    }

    @Test
    fun `未知工具归 OTHER 但仍要给出可读副标题`() {
        val s = QuroToolSummary.of(
            "some_brand_new_tool",
            """{"alpha":"1","targetFile":"/a/b/c.txt","count":7}""",
            "ok",
            0,
        )
        assertEquals(QuroToolSummary.Family.OTHER, s.family)
        // 🔴 副标题不能空 —— 空了用户就完全不知道干了什么
        assertTrue(s.subtitle.isNotBlank())
        assertEquals("/a/b/c.txt", s.subtitle)
    }

    @Test
    fun `族归类先匹配具体族不会被宽泛规则抢走`() {
        // web_search 里含 "search"，不能被 FILE_READ 的 grep/search_file 抢走
        assertEquals(QuroToolSummary.Family.WEB, QuroToolSummary.familyOf("web_search"))
        // write_file 含 "file"，必须归写而不是读
        assertEquals(QuroToolSummary.Family.FILE_WRITE, QuroToolSummary.familyOf("write_file"))
    }

    // ───────── 参数容错 ─────────

    @Test
    fun `参数非法 JSON 时不崩溃且降级到结果首行`() {
        val s = QuroToolSummary.of("run_code", "这不是 JSON {{{", "SyntaxError: unexpected token", 10)
        assertNotNull(s)
        assertEquals(QuroToolSummary.Status.ERROR, s.status)
        assertTrue(s.subtitle.isNotBlank())
    }

    @Test
    fun `参数嵌套在 input 里也能取出`() {
        val s = QuroToolSummary.of(
            "quroterm_exec",
            """{"input":{"command":"pwd"}}""",
            "/root\nexit_code=0",
            5,
        )
        assertEquals("pwd", s.subtitle)
    }

    @Test
    fun `代码语言缺省时按内容猜`() {
        assertEquals("python", QuroToolSummary.guessLanguage("def f():\n    print(1)"))
        assertEquals("shell", QuroToolSummary.guessLanguage("#!/bin/bash\necho hi"))
        assertEquals("html", QuroToolSummary.guessLanguage("<!DOCTYPE html><html>"))
        assertEquals("", QuroToolSummary.guessLanguage("随便一段话"))
    }

    @Test
    fun `run_code 未标语言也能从内容猜出来`() {
        val s = QuroToolSummary.of("run_code", """{"code":"def f():\n    return 1"}""", "1", 5)
        assertEquals("python", s.subtitle)
    }

    // ───────── 退出码 ─────────

    @Test
    fun `退出码识别中英文两种写法`() {
        assertEquals(0, QuroToolSummary.detectExitCode("done\nexit_code=0"))
        assertEquals(1, QuroToolSummary.detectExitCode("失败\nexit code: 1"))
        assertEquals(137, QuroToolSummary.detectExitCode("退出码：137"))
        assertEquals(2, QuroToolSummary.detectExitCode("Exited with code 2"))
    }

    @Test
    fun `没有退出码信息时返回 null 而不是瞎猜`() {
        assertNull(QuroToolSummary.detectExitCode("普通输出 12345 行"))
        assertNull(QuroToolSummary.detectExitCode(""))
        assertNull(QuroToolSummary.detectExitCode(null))
    }

    // ───────── 路径与指标 ─────────

    @Test
    fun `深路径只保留末两段`() {
        assertEquals("…/Documents/notes.md", QuroToolSummary.shortPath("/storage/emulated/0/Documents/notes.md"))
        assertEquals("a/b", QuroToolSummary.shortPath("a/b"))
        assertEquals("", QuroToolSummary.shortPath(""))
    }

    @Test
    fun `空内容行数显示为横线而不是 0`() {
        assertEquals("—", QuroToolSummary.countLines(""))
        assertEquals("1", QuroToolSummary.countLines("一行"))
        assertEquals("2", QuroToolSummary.countLines("a\nb"))
    }

    @Test
    fun `耗时原样透传供渲染层显示`() {
        assertEquals(1500L, QuroToolSummary.of("tap", "{}", "ok", 1500L).durationMs)
    }

    @Test
    fun `耗时零值不产生空指标`() {
        val s = QuroToolSummary.of("run_code", """{"code":"print(1)"}""", "ok", 0)
        assertTrue(s.metrics.none { it.value == "0" && it.label.isBlank() })
    }

    // ───────── 状态判定 ─────────

    @Test
    fun `成功标志与失败标志都识别`() {
        assertEquals(QuroToolSummary.Status.SUCCESS, QuroToolSummary.statusOf("✅ 完成", QuroToolSummary.Family.CODE))
        assertEquals(QuroToolSummary.Status.ERROR, QuroToolSummary.statusOf("❌ 失败", QuroToolSummary.Family.CODE))
        assertEquals(
            QuroToolSummary.Status.ERROR,
            QuroToolSummary.statusOf("Traceback (most recent call last)", QuroToolSummary.Family.CODE),
        )
        assertEquals(QuroToolSummary.Status.WARNING, QuroToolSummary.statusOf("⚠️ 注意", QuroToolSummary.Family.CODE))
    }

    @Test
    fun `正文深处出现的失败字样不误标整条为失败`() {
        // 只扫前 200 字符，避免「本操作不会失败」被误判
        val long = "成功返回结果。" + "x".repeat(300) + " 失败率 0%"
        assertEquals(QuroToolSummary.Status.SUCCESS, QuroToolSummary.statusOf(long, QuroToolSummary.Family.CODE))
    }
}
