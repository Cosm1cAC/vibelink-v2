package com.wangzan101.vibelink.core.assembly

import com.wangzan101.vibelink.core.cap.mcp.McpCapability
import com.wangzan101.vibelink.core.cap.mcp.McpConnection
import com.wangzan101.vibelink.core.run.RunSpec

internal class McpClientProvider(private val connection: McpConnection) : CapabilityProvider {
    override val id: String = "mcp:${connection.endpoint.serverId}"
    override fun open(spec: RunSpec) = McpCapability(connection, id)
}
