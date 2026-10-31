package com.ai.assistance.quro.core.mika.mcp

import com.ai.assistance.quro.core.mika.device.DeviceAccess
import com.ai.assistance.quro.core.mika.device.toJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 主屏准备是独立的确定性流程。工具不接收凭据,凭据只能在手机设置。沿用 MCP 的认证门口。 */
internal class McpDeviceTools(private val device: DeviceAccess) {
    private val schema = buildJsonObject {
        put("type", "object"); put("properties", JsonObject(emptyMap()))
        put("required", buildJsonArray {}); put("additionalProperties", false)
    }
    val tools = listOf(
        McpTaskTools.Tool(STATE, "Read phone screen/lock state and whether local remote-unlock permission is enabled. No credentials are returned.", schema, true),
        McpTaskTools.Tool(WAKE, "Wake the main phone screen. Requires the phone's remote wake/unlock setting. Does not unlock it.", schema, false),
        McpTaskTools.Tool(UNLOCK, "Wake and unlock the phone using only permission and credentials configured locally on the phone. " +
            "Call before quro_run_task, or when a task is paused because the phone is locked. A failed credential attempt blocks further attempts until reconfigured on the phone. " +
            "Cannot approve payments or bypass authentication.", schema, false),
    )

    suspend fun call(name: String, args: JsonObject): JsonObject {
        if (args.isNotEmpty()) throw McpTaskTools.ToolError("Device tools accept no arguments; configure credentials on the phone.")
        val r = when (name) {
            STATE -> device.state()
            WAKE -> device.wake()
            UNLOCK -> device.unlock()
            else -> throw McpTaskTools.ToolError("Unknown tool: $name")
        }
        return r.toJson()
    }

    companion object {
        const val STATE = "quro_device_state"
        const val WAKE = "quro_wake_device"
        const val UNLOCK = "quro_unlock_device"
    }
}
