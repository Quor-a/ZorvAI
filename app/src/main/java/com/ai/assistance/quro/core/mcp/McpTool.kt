/*
 * Vendored from droid-mcp (stixez/droid-mcp), Apache License 2.0.
 * https://github.com/stixez/droid-mcp — Copyright 2026 stixez.
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 * Repackaged into Quro AI (com.ai.assistance.quro.core.mcp).
 * HTTP transport (ktor) intentionally omitted; in-process transport retained.
 */
package com.ai.assistance.quro.core.mcp

/** MCP 工具接口（引擎层，源自 droid-mcp，Apache-2.0）。 */
interface McpTool {
    val name: String
    val description: String
    val parameters: List<ToolParameter>
    val annotations: ToolAnnotations get() = ToolAnnotations()
    suspend fun execute(params: Map<String, Any>): ToolResult
}

/** 工具行为提示（仅提示，非强制）。 */
data class ToolAnnotations(
    val readOnlyHint: Boolean = false,
    val destructiveHint: Boolean = false,
    val idempotentHint: Boolean = false,
    val openWorldHint: Boolean = false,
    val title: String? = null,
)
