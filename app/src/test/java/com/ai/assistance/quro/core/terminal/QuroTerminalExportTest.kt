package com.ai.assistance.quro.core.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [QuroTerminalExport] 中**纯逻辑**部分的单元测试（E-10）。
 *
 * 只测 [QuroTerminalExport.fileName] 与 [QuroTerminalExport.buildContent]：
 * `export()` 依赖 `android.os.Environment`，属于设备侧行为，不在 JVM 单测范围内。
 */
class QuroTerminalExportTest {

    private fun at(iso: String): Date =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(iso)!!

    @Test
    fun `文件名格式为 terminal 时间戳 log`() {
        val name = QuroTerminalExport.fileName(at("2026-02-14 09:05:03"))
        assertEquals("terminal_20260214_090503.log", name)
    }

    @Test
    fun `文件名不含空格与冒号`() {
        // Windows / FAT32 外置卡上冒号是非法字符，导出会直接失败
        val name = QuroTerminalExport.fileName(at("2026-12-31 23:59:59"))
        assertTrue(name.none { it == ':' || it == ' ' })
    }

    @Test
    fun `导出内容包含元信息头`() {
        val c = QuroTerminalExport.buildContent(
            lines = listOf("$ ls", "a.txt"),
            mode = ShellMode.DEVICE,
            cwd = "/sdcard",
            at = at("2026-02-14 09:05:03"),
        )
        assertTrue(c.contains("# Zorv AI 终端会话日志"))
        assertTrue(c.contains("# 工作目录: /sdcard"))
        assertTrue(c.contains("# 行数: 2"))
        assertTrue(c.contains("设备 sh"))
    }

    @Test
    fun `Linux 模式在头部如实标注`() {
        val c = QuroTerminalExport.buildContent(
            lines = emptyList(),
            mode = ShellMode.LINUX,
            cwd = "/root",
            at = at("2026-02-14 09:05:03"),
        )
        assertTrue(c.contains("proot/Linux"))
        assertTrue(c.contains("# 行数: 0"))
    }

    @Test
    fun `每一行输出都被完整写出`() {
        val lines = listOf("line1", "", "line3 有中文", "  缩进保留")
        val c = QuroTerminalExport.buildContent(lines, ShellMode.DEVICE, "/", at("2026-01-01 00:00:00"))
        val body = c.lines().dropWhile { it.startsWith("#") }
        // dropLast(1)：buildString 里最后一行 appendLine 会留下一个空尾行
        assertEquals(lines, body.dropLast(1))
    }

    @Test
    fun `空会话也能生成合法内容`() {
        val c = QuroTerminalExport.buildContent(emptyList(), ShellMode.DEVICE, "/sdcard", at("2026-01-01 00:00:00"))
        assertTrue(c.isNotBlank())
        assertTrue(c.trim().endsWith("-".repeat(56)))
    }
}
