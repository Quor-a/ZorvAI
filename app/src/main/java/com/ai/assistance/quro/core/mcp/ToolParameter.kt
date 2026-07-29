/*
 * Vendored from droid-mcp (stixez/droid-mcp), Apache License 2.0.
 * https://github.com/stixez/droid-mcp — Copyright 2026 stixez.
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 * Repackaged into Zorv AI (com.ai.assistance.quro.core.mcp).
 */
package com.ai.assistance.quro.core.mcp

/** 参数 JSON 类型（与 JSON Schema 对齐）。 */
enum class ParameterType(val jsonType: String) {
    STRING("string"),
    INTEGER("integer"),
    NUMBER("number"),
    BOOLEAN("boolean"),
    ARRAY("array"),
    OBJECT("object"),
}

/** 单个工具参数定义。 */
data class ToolParameter(
    val name: String,
    val description: String,
    val type: ParameterType,
    val required: Boolean = false,
) {
    fun toJsonSchema(): Map<String, Any> = mapOf("type" to type.jsonType, "description" to description)
}
