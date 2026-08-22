/*
 * Vendored from droid-mcp (stixez/droid-mcp), Apache License 2.0.
 * https://github.com/stixez/droid-mcp — Copyright 2026 stixez.
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 * Repackaged into Zorv AI (com.ai.assistance.quro.core.mcp).
 * Rewritten to use org.json (already present in Zorv AI) instead of kotlinx.serialization.
 */
package com.ai.assistance.quro.core.mcp.transport

import com.ai.assistance.quro.core.mcp.McpTool
import com.ai.assistance.quro.core.mcp.ToolRegistry
import com.ai.assistance.quro.core.mcp.ToolResult
import org.json.JSONArray
import org.json.JSONObject

/** 进程内传输层：直接在同一进程调用工具，无需网络/server。 */
class InProcessTransport(private val registry: ToolRegistry) {

    fun listTools(): List<McpTool> = registry.listTools()

    /** 以 MCP 工具清单格式输出（供协议/桌面客户端使用）。 */
    fun listToolsJson(): String {
        val arr = JSONArray()
        registry.listTools().forEach { tool ->
            val props = JSONObject()
            tool.parameters.forEach { p ->
                props.put(
                    p.name,
                    JSONObject().apply {
                        put("type", p.type.jsonType)
                        put("description", p.description)
                    },
                )
            }
            val required = JSONArray()
            tool.parameters.filter { it.required }.forEach { required.put(it.name) }
            val params = JSONObject().apply {
                put("type", "object")
                put("properties", props)
                put("required", required)
            }
            arr.put(
                JSONObject().apply {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", params)
                },
            )
        }
        return arr.toString()
    }

    suspend fun callTool(name: String, params: Map<String, Any>): ToolResult =
        registry.executeTool(name, params)
}
