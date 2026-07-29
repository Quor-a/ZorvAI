/*
 * Vendored from droid-mcp (stixez/droid-mcp), Apache License 2.0.
 * https://github.com/stixez/droid-mcp — Copyright 2026 stixez.
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 * Repackaged into Zorv AI (com.ai.assistance.quro.core.mcp).
 */
package com.ai.assistance.quro.core.mcp

import java.util.concurrent.ConcurrentHashMap

/** 进程内工具注册表（引擎层，源自 droid-mcp，Apache-2.0）。 */
class ToolRegistry {
    private val tools = ConcurrentHashMap<String, McpTool>()

    fun register(tool: McpTool) {
        tools[tool.name] = tool
    }

    fun registerAll(toolList: List<McpTool>) {
        toolList.forEach { register(it) }
    }

    fun getTool(name: String): McpTool? = tools[name]

    fun listTools(): List<McpTool> = tools.values.toList()

    suspend fun executeTool(name: String, params: Map<String, Any>): ToolResult {
        val tool = tools[name] ?: return ToolResult.error("Unknown tool: $name")
        return try {
            tool.execute(params)
        } catch (e: Exception) {
            ToolResult.error("Tool '$name' failed: ${e.message}")
        }
    }
}
