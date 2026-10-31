package com.ai.assistance.quro.core.mika.cap.mcp

import com.ai.assistance.quro.core.mika.action.ActionRisk
import com.ai.assistance.quro.core.mika.action.Risk as ApprovalRisk
import com.ai.assistance.quro.core.mika.capability.Capability
import com.ai.assistance.quro.core.mika.capability.CapabilityStatus
import com.ai.assistance.quro.core.mika.capability.PromptEnv
import com.ai.assistance.quro.core.mika.observation.ObsKind
import com.ai.assistance.quro.core.mika.observation.Observation
import com.ai.assistance.quro.core.mika.observation.Trust
import com.ai.assistance.quro.core.mika.observation.Untrusted
import com.ai.assistance.quro.core.mika.tool.AgentTool
import com.ai.assistance.quro.core.mika.tool.Args
import com.ai.assistance.quro.core.mika.tool.Risk
import com.ai.assistance.quro.core.mika.tool.ToolContext
import com.ai.assistance.quro.core.mika.tool.ToolDef
import com.ai.assistance.quro.core.mika.tool.ToolExecution
import com.ai.assistance.quro.core.mika.tool.Verb
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 传输层只须实现这两个操作；每个 Run 开局调用一次 tools，之后目录不变。 */
interface McpEndpoint {
    val serverId: String
    fun tools(): List<McpToolDescriptor>
    suspend fun call(name: String, arguments: JsonObject): McpResult
}

data class McpAnnotations(
    val readOnlyHint: Boolean? = null,
    val destructiveHint: Boolean? = null,
    val openWorldHint: Boolean? = null,
)

data class McpToolDescriptor(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val annotations: McpAnnotations = McpAnnotations(),
    val estimatedCostUnits: Long = 0,
)

data class McpResult(val text: String, val isError: Boolean = false, val costUnits: Long = 0)

/** 每个服务独立配置；Restricted 不采信服务自称的只读注解。 */
enum class McpTrust { Restricted, TrustAnnotations }

/** 长结果的完整文本由宿主保存；返回的引用只含文件路径或资源 ID。 */
fun interface McpResultStore { fun save(serverId: String, toolName: String, content: String): String }

data class McpConnection(
    val endpoint: McpEndpoint,
    val resultStore: McpResultStore? = null,
    val maxCostUnits: Long? = null,
    val previewChars: Int = 4000,
    val trust: McpTrust = McpTrust.Restricted,
) {
    init { require(previewChars > 0 && (maxCostUnits == null || maxCostUnits >= 0)) }
}

/** 没有只读注解就按可写处理；破坏性与开放世界操作记成不可逆、对外(审批记录据此知道任务做过对外的事)。 */
fun mcpApprovalRisk(a: McpAnnotations, trust: McpTrust = McpTrust.TrustAnnotations): ApprovalRisk = when {
    a.destructiveHint == true -> ApprovalRisk(ActionRisk.Irreversible)
    a.openWorldHint == true -> ApprovalRisk(ActionRisk.External)
    a.readOnlyHint == true && trust == McpTrust.TrustAnnotations -> ApprovalRisk(ActionRisk.Read)
    else -> ApprovalRisk(ActionRisk.Change)
}

internal class McpCapability(connection: McpConnection, override val id: String) : Capability {
    private val endpoint = connection.endpoint
    private val budget = McpBudget(connection.maxCostUnits)
    private val tools = endpoint.tools().toList().map { McpClientTool(id, endpoint, it, budget, connection.resultStore, connection.previewChars, connection.trust) }
    override fun status(): CapabilityStatus = CapabilityStatus.Available
    override fun tools(): List<AgentTool> = tools
    override fun sections(env: PromptEnv): List<String> = emptyList()
}

private class McpBudget(private val limit: Long?) {
    private var used = 0L
    @Synchronized fun charge(units: Long): Boolean {
        if (units < 0 || limit != null && units > limit - used) return false
        used += units
        return true
    }
}

private class McpClientTool(
    override val capability: String,
    private val endpoint: McpEndpoint,
    private val descriptor: McpToolDescriptor,
    private val budget: McpBudget,
    private val resultStore: McpResultStore?,
    private val previewChars: Int,
    trust: McpTrust,
) : AgentTool {
    private val schema = descriptor.inputSchema
    private val approval = mcpApprovalRisk(descriptor.annotations, trust)
    override val def: ToolDef = ToolDef(
        "mcp_${endpoint.serverId.replace(Regex("[^A-Za-z0-9_]"), "_")}__${descriptor.name.replace(Regex("[^A-Za-z0-9_]"), "_")}",
        descriptor.description,
        schema["properties"]?.jsonObject.orEmpty().mapValues { it.value.jsonObject },
        schema["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
    )
    override val verb = Verb.Interact
    override val risk = when (approval.level) {
        ActionRisk.Read -> Risk.Read
        ActionRisk.Change -> Risk.Write
        else -> Risk.External
    }
    override val approvalRisk: ApprovalRisk = approval
    override val offScreen = true
    override val logged = risk != Risk.Read

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        if (!budget.charge(descriptor.estimatedCostUnits)) return ToolExecution(buildJsonObject {
            put("ok", false); put("error", "mcp_cost_limit")
        })
        val result = endpoint.call(descriptor.name, a.o)
        val long = result.text.length > previewChars
        val ref = if (long) resultStore?.save(endpoint.serverId, descriptor.name, result.text) else null
        if (long && ref == null) return ToolExecution(buildJsonObject {
            put("ok", false); put("error", "mcp_result_too_large")
        })
        val preview = if (long) result.text.take(previewChars) else result.text
        return ToolExecution(buildJsonObject {
            put("ok", !result.isError)
            put("text", Untrusted.wrap(preview))
            ref?.let { put("fullResult", it) }
            if (long) put("truncated", true)
        }, observations = listOf(Observation(ObsKind.Output, preview, source = capability, trust = Trust.Untrusted)))
    }
}
