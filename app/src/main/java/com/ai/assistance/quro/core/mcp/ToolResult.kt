/*
 * Vendored from droid-mcp (stixez/droid-mcp), Apache License 2.0.
 * https://github.com/stixez/droid-mcp — Copyright 2026 stixez.
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 * Repackaged into Quro AI (com.ai.assistance.quro.core.mcp).
 */
package com.ai.assistance.quro.core.mcp

/** 工具执行结果（引擎层，源自 droid-mcp，Apache-2.0）。 */
data class ToolResult(
    val isSuccess: Boolean,
    val data: Map<String, Any?>?,
    val errorMessage: String?,
) {
    companion object {
        fun success(data: Map<String, Any?>) = ToolResult(isSuccess = true, data = data, errorMessage = null)
        fun error(message: String) = ToolResult(isSuccess = false, data = null, errorMessage = message)
    }
}
