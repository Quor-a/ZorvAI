/*
 * Vendored from droid-mcp (stixez/droid-mcp), Apache License 2.0.
 * https://github.com/stixez/droid-mcp — Copyright 2026 stixez.
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 * Repackaged into Zorv AI (com.ai.assistance.quro.core.mcp).
 * HTTP server transport (ktor) intentionally omitted for zero-dependency on-device use.
 */
package com.ai.assistance.quro.core.mcp

import com.ai.assistance.quro.core.mcp.transport.InProcessTransport

/**
 * 进程内 MCP 引擎门面（源自 droid-mcp，Apache-2.0）。
 * - [listToolsJson] / [listTools]：导出工具清单（供协议/桌面客户端）
 * - [callTool]：按 LLM 选择的工具名 + 参数执行（端侧直接调用，无需 server）
 */
class DroidMcp private constructor(
    private val registry: ToolRegistry,
    private val inProcessTransport: InProcessTransport,
) {
    fun listTools(): List<McpTool> = inProcessTransport.listTools()

    fun listToolsJson(): String = inProcessTransport.listToolsJson()

    suspend fun callTool(name: String, params: Map<String, Any>): ToolResult =
        inProcessTransport.callTool(name, params)

    class Builder {
        private val tools = mutableListOf<McpTool>()

        fun addTool(tool: McpTool) = apply { tools.add(tool) }

        fun addTools(toolList: List<McpTool>) = apply { tools.addAll(toolList) }

        fun build(): DroidMcp {
            val registry = ToolRegistry()
            registry.registerAll(tools)
            return DroidMcp(registry, InProcessTransport(registry))
        }
    }

    companion object {
        fun builder() = Builder()
    }
}
