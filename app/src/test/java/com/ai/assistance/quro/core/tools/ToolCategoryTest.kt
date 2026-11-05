package com.ai.assistance.quro.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCategoryTest {

    @Test
    fun `精确名映射到正确类别`() {
        assertEquals(ToolCategory.SYSTEM, ToolCategoryRegistry.categoryOf("get_battery"))
        assertEquals(ToolCategory.FILES, ToolCategoryRegistry.categoryOf("write_file"))
        assertEquals(ToolCategory.WEB, ToolCategoryRegistry.categoryOf("web_search"))
        assertEquals(ToolCategory.CODE, ToolCategoryRegistry.categoryOf("terminal"))
        assertEquals(ToolCategory.MEMORY, ToolCategoryRegistry.categoryOf("memory_save"))
        assertEquals(ToolCategory.MEDIA, ToolCategoryRegistry.categoryOf("speak"))
        assertEquals(ToolCategory.DOCUMENT, ToolCategoryRegistry.categoryOf("aiwps_create"))
        assertEquals(ToolCategory.APPS, ToolCategoryRegistry.categoryOf("launch_app"))
        assertEquals(ToolCategory.COMMUNICATION, ToolCategoryRegistry.categoryOf("send_sms"))
        assertEquals(ToolCategory.CALENDAR_LOCATION, ToolCategoryRegistry.categoryOf("set_alarm"))
    }

    @Test
    fun `前缀映射`() {
        assertEquals(ToolCategory.MEMORY, ToolCategoryRegistry.categoryOf("skill__my_skill"))
        assertEquals(ToolCategory.BRIDGE, ToolCategoryRegistry.categoryOf("mcp__some_tool"))
        assertEquals(ToolCategory.CONTROL, ToolCategoryRegistry.categoryOf("adb_shell"))
        assertEquals(ToolCategory.CONTROL, ToolCategoryRegistry.categoryOf("shizuku_exec"))
        assertEquals(ToolCategory.UI, ToolCategoryRegistry.categoryOf("ui_dsl_something"))
        assertEquals(ToolCategory.WEB, ToolCategoryRegistry.categoryOf("browser_act"))
    }

    @Test
    fun `未知工具回落 general`() {
        assertEquals(ToolCategory.GENERAL, ToolCategoryRegistry.categoryOf("some_unknown_tool"))
        assertEquals(ToolCategory.GENERAL, ToolCategoryRegistry.categoryOf("calculate"))
    }

    @Test
    fun `空白名单返回全部`() {
        val names = listOf("get_battery", "write_file", "web_search", "unknown_tool")
        assertEquals(names, ToolCategoryRegistry.filter(names, emptyList()))
    }

    @Test
    fun `非空白名单只保留命中类别`() {
        val names = listOf("get_battery", "write_file", "web_search", "speak")
        val result = ToolCategoryRegistry.filter(names, listOf(ToolCategory.SYSTEM, ToolCategory.WEB))
        assertEquals(listOf("get_battery", "web_search"), result)
    }

    @Test
    fun `白名单含 general 时保留未分类工具`() {
        val names = listOf("calculate", "get_battery")
        val result = ToolCategoryRegistry.filter(names, listOf(ToolCategory.GENERAL))
        assertEquals(listOf("calculate"), result)
    }

    @Test
    fun `label 覆盖所有类别`() {
        ToolCategory.ALL.forEach { assertTrue(ToolCategory.label(it).isNotBlank()) }
    }
}
