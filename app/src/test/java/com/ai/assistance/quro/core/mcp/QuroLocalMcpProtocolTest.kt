package com.ai.assistance.quro.core.mcp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地 MCP `/mcp` 协议面的**契约测试**。
 *
 * ## 为什么必须有
 *
 * harness 侧 `@modelcontextprotocol/sdk` 对 `tools/list` 的返回做严格校验：每条工具必须带
 * `inputSchema`（object）。我们历史上直接把服务端内部定义（`parameters` +
 * `handler_type` / `handler_config`）原样吐出，SDK 于是抛
 * `INVALID_RESULT: path ["tools",0,"inputSchema"] expected object, received undefined`，
 * `connect()` 失败、`connectedAt` 永远 undefined，上层一律报 `server is disconnected`
 * —— 表现是「MCP 通道连不上」，真因却只是一个字段名。排查了三轮才定位，因此钉死在这里。
 *
 * 服务端内部模型继续用 `parameters`（内置插件通道与 `mcp_deploy` 都按它存），
 * 只在**协议出口**映射成 `inputSchema`，两边不互相污染。
 */
class QuroLocalMcpProtocolTest {

    @Test
    fun `parameters 对象原样映射成 inputSchema`() {
        val schema = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().put("q", JSONObject().put("type", "string")))
        val tool = JSONObject()
            .put("name", "web_search")
            .put("description", "搜索")
            .put("parameters", schema)
            .put("handler_type", "quro_tool")
            .put("handler_config", JSONObject().put("tool", "web_search"))

        val mapped = mcpInputSchema(tool)
        assertEquals("object", mapped.optString("type"))
        assertEquals(
            schema.getJSONObject("properties").getJSONObject("q").optString("type"),
            mapped.getJSONObject("properties").getJSONObject("q").optString("type"),
        )
    }

    @Test
    fun `parameters 是字符串时也解得出来`() {
        val tool = JSONObject()
            .put("name", "t")
            .put("parameters", """{"type":"object","properties":{"n":{"type":"number"}}}""")
        val mapped = mcpInputSchema(tool)
        assertEquals("object", mapped.optString("type"))
        assertTrue(mapped.has("properties"))
    }

    @Test
    fun `缺失或空的 parameters 必须给 type object`() {
        val missing = mcpInputSchema(JSONObject().put("name", "t"))
        assertEquals("object", missing.optString("type"))
        assertEquals("空对象也要补 type", "object", mcpInputSchema(JSONObject().put("parameters", JSONObject())).optString("type"))
        assertEquals("空字符串同样兜住", "object", mcpInputSchema(JSONObject().put("parameters", "  ")).optString("type"))
    }

    @Test
    fun `映射不会改动原始工具定义`() {
        val tool = JSONObject().put("name", "t")   // 没有 parameters
        mcpInputSchema(tool)
        assertFalse("不得往共享定义里写键", tool.has("parameters"))
        assertFalse(tool.has("inputSchema"))
    }

    @Test
    fun `tools_list 出口只吐规范字段且用 inputSchema`() {
        val src = source("app/src/main/java/com/ai/assistance/quro/core/mcp/QuroLocalMcpServer.kt")
        // 出口必须写 inputSchema
        assertTrue(
            "tools/list 必须序列化 inputSchema（缺它 harness SDK 直接判 INVALID_RESULT）",
            src.contains("put(\"inputSchema\""),
        )
        // handler_* 是服务端路由信息，不得出现在协议出口
        assertFalse(
            "handler_type 不得出现在协议响应里（规范无此键，属实现泄漏）",
            src.contains("put(\"handler_type\""),
        )
        assertFalse(
            "handler_config 不得出现在协议响应里",
            src.contains("put(\"handler_config\""),
        )
    }

    private fun source(relative: String): String {
        val cands = listOf(
            java.io.File("../$relative"),
            java.io.File(relative),
            java.io.File("D:/Calw OS-project/QuroAI/$relative"),
        )
        return cands.firstOrNull { it.isFile }?.readText()
            ?: error("找不到源文件 $relative")
    }
}
