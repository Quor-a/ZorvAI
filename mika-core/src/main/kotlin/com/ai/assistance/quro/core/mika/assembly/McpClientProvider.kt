package com.ai.assistance.quro.core.mika.assembly

import com.ai.assistance.quro.core.mika.cap.mcp.McpCapability
import com.ai.assistance.quro.core.mika.cap.mcp.McpConnection
import com.ai.assistance.quro.core.mika.run.RunSpec

internal class McpClientProvider(private val connection: McpConnection) : CapabilityProvider {
    override val id: String = "mcp:${connection.endpoint.serverId}"
    override fun open(spec: RunSpec) = McpCapability(connection, id)
}
