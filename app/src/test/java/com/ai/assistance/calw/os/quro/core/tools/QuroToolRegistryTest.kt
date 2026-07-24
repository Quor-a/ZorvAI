package com.ai.assistance.quro.core.tools

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM 单元测试：验证原创工具注册表（buildQuroRegistry）的装配正确性。
 * - 工具数量达到「最大集」预期下限（25 基础 + 12 新增 = 37）
 * - 名称唯一（LinkedHashMap 静默覆盖，必须显式校验）
 * - 每个工具的 parametersJson 可被适配器正确解析（不抛异常）
 * - specs() 与注册表一一对应
 * 不依赖 Android Context（工具实例构造无需 Context，仅 run() 才需要）。
 */
class QuroToolRegistryTest {

    @Test
    fun registry_hasExpectedToolCount() {
        val r = buildQuroRegistry()
        assertTrue("工具数量应达到最大集下限 37（25 基础 + 12 新增），实际=${r.all().size}", r.all().size >= 37)
    }

    @Test
    fun registry_hasNewWebFileTtsIntentTools() {
        val names = buildQuroRegistry().all().map { it.name }.toSet()
        val expected = setOf(
            "http_request",
            "write_file", "delete_file", "make_directory", "move_file", "copy_file", "find_files", "file_info",
            "speak", "stop_speak",
            "execute_intent", "send_broadcast",
        )
        val missing = expected - names
        assertTrue("缺失新增工具: $missing", missing.isEmpty())
    }

    @Test
    fun registry_namesAreUnique() {
        val r = buildQuroRegistry()
        val names = r.all().map { it.name }
        assertEquals("工具名称必须唯一（发现重复）", names.size, names.toSet().size)
    }

    @Test
    fun everyTool_hasParseableParametersJson() {
        val r = buildQuroRegistry()
        r.all().forEach { tool ->
            val parsed = runCatching { JSONObject(tool.parametersJson) }
            assertTrue("工具 ${tool.name} 的 parametersJson 不是合法 JSON: ${tool.parametersJson}", parsed.isSuccess)
            parsed.getOrNull()?.let { jo ->
                assertTrue("工具 ${tool.name} 的 parametersJson 顶层应为 object", jo.optString("type", "object") == "object")
            }
            // 复用适配器解析，确保与引擎一致且不抛异常
            val params = runCatching { jsonSchemaToToolParameters(tool.parametersJson) }
            assertTrue("工具 ${tool.name} 的参数解析失败: ${params.exceptionOrNull()?.message}", params.isSuccess)
        }
    }

    @Test
    fun specs_matchRegistrySize() {
        val r = buildQuroRegistry()
        val specs = r.specs()
        assertEquals("specs 数量应与注册工具数一致", r.all().size, specs.size)
        assertEquals("specs 名称应与工具名称一致", r.all().map { it.name }.toSet(), specs.map { it.name }.toSet())
    }

    @Test
    fun listToolsMcpJson_isValidJsonArray() {
        val engine = QuroToolEngine(buildQuroRegistry())
        val json = engine.listToolsMcpJson()
        val arr = runCatching { org.json.JSONArray(json) }
        assertTrue("listToolsMcpJson 应输出合法 JSON 数组", arr.isSuccess)
        assertTrue("MCP 工具清单不应为空", arr.getOrNull()?.length() ?: 0 > 0)
    }
}
