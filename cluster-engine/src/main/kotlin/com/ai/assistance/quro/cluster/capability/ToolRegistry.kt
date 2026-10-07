package com.ai.assistance.quro.cluster.engine.capability

import com.ai.assistance.quro.cluster.model.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

interface Tool {
    val spec: ToolSpec
    suspend fun invoke(argsJson: String, ctx: ToolContext): ToolResult
}

data class ToolContext(
    val taskId: TaskId,
    val agentId: AgentId,
    val clusterId: ClusterId,
    /** 高危操作的确认回调；返回 false 即拒绝执行 */
    val approve: suspend (prompt: String, risk: RiskLevel) -> Boolean
)

/**
 * 工具注册表 + 执行沙箱。
 * 沙箱职责：授权校验 → 高危确认 → 幂等去重 → 并发闸门 → 超时 → 审计，六道关。
 */
class ToolRegistry(private val audit: (ToolCall) -> Unit = {}) {
    private val tools = LinkedHashMap<ToolId, Tool>()
    private val grants = HashMap<AgentId, MutableSet<ToolId>>()
    private val executed = LinkedHashSet<String>()     // 幂等键
    private val gate = Semaphore(8)

    /**
     * 幂等键的容量上限。
     *
     * 🔴 #183：原先 [executed] 是**无上限 HashSet**，而 key = `toolId|argsJson`
     * 里带着**完整参数原文**（宿主工具里可能有几万字符的文档内容）。
     * 本类是引擎级单例、进程活多久它就涨多久 —— 用户ANR 快照里的
     * `full avg10=2.45`（**全系统内存完全耗尽**占比 2.45%）就是这么来的。
     *
     * 取 4096：一次典型任务的工具调用远小于这个数（引擎还有 `guard > 10_000` 熔断），
     * 超出后按**插入顺序**丢最老的 —— 丢最老而不是丢最新，是因为幂等保护的是
     * 「同一轮重放别重复执行」，而重放总是从最近的事件开始。
     */
    private fun rememberExecuted(key: String) {
        if (executed.size >= EXECUTED_KEY_CAPACITY) {
            val it = executed.iterator()
            // 先丢一批（1/4），避免每加一条都要清理
            var drop = EXECUTED_KEY_CAPACITY / 4
            while (drop > 0 && it.hasNext()) {
                it.next()
                it.remove()
                drop--
            }
        }
        executed.add(key)
    }

    fun register(tool: Tool) { tools[tool.spec.id] = tool }
    fun grant(agentId: AgentId, ids: Set<ToolId>) {
        grants.getOrPut(agentId) { mutableSetOf() }.addAll(ids)
    }
    fun revoke(agentId: AgentId, id: ToolId) { grants[agentId]?.remove(id) }

    /**
     * 某角色当前已授权的工具 id。
     *
     * 此前授权**只能加不能读**——`grant` / `revoke` 都有，读取侧只有
     * `specsFor`（返回 ToolSpec 列表，且会丢掉未注册的 id）。
     * 角色编辑器要渲染「哪些已勾选」，需要的是稳定的 id 集合，故补这个口。
     * 返回**拷贝**，避免调用方拿到内部可变集合直接改坏授权表。
     */
    fun grantedIds(agentId: AgentId): Set<ToolId> =
        grants[agentId]?.toSet() ?: emptySet()

    fun specsFor(agentId: AgentId): List<ToolSpec> =
        (grants[agentId] ?: emptySet()).mapNotNull { tools[it]?.spec }

    suspend fun invoke(
        agentId: AgentId,
        toolId: ToolId,
        argsJson: String,
        ctx: ToolContext
    ): ToolResult {
        val key = "$toolId|$argsJson"

        // 1. 幂等：重放任务时不产生二次副作用
        if (executed.contains(key)) return ToolResult(true, "(幂等命中，未重复执行)")
        rememberExecuted(key)

        // 2. 授权
        if (toolId !in (grants[agentId] ?: emptySet()))
            return deny(agentId, toolId, argsJson, "未授权的工具: ${toolId.value}", RiskLevel.MEDIUM)

        val tool = tools[toolId] ?: return deny(agentId, toolId, argsJson, "工具不存在", RiskLevel.LOW)

        // 3. 高危二次确认
        if (tool.spec.risk == RiskLevel.HIGH || tool.spec.risk == RiskLevel.CRITICAL) {
            val ok = ctx.approve(
                "「${tool.spec.name}」属于 ${tool.spec.risk} 风险操作，是否允许 ${agentId.value} 执行？",
                tool.spec.risk
            )
            if (!ok) return deny(agentId, toolId, argsJson, "用户拒绝", tool.spec.risk)
        }

        // 4. 并发 + 超时
        val res = gate.withPermit {
            withTimeoutOrNull(tool.spec.timeoutMs) {
                runCatching { tool.invoke(argsJson, ctx) }
                    .getOrElse { ToolResult(false, "", tool.spec.risk, it.message) }
            } ?: ToolResult(false, "", tool.spec.risk, "执行超时 ${tool.spec.timeoutMs}ms")
        }

        audit(
            ToolCall(
                id = key, messageId = MessageId(""), agentId = agentId, toolId = toolId,
                argsJson = argsJson, resultJson = res.output, risk = tool.spec.risk,
                status = if (res.ok) ToolCallStatus.SUCCESS else ToolCallStatus.FAILED
            )
        )
        return res
    }

    private fun deny(
        agentId: AgentId, toolId: ToolId, argsJson: String, msg: String, risk: RiskLevel
    ): ToolResult {
        audit(
            ToolCall(
                id = "$toolId|denied", messageId = MessageId(""), agentId = agentId,
                toolId = toolId, argsJson = argsJson, status = ToolCallStatus.DENIED, risk = risk
            )
        )
        return ToolResult(false, msg, risk, msg)
    }

    private companion object {
        /** 幂等键容量上限，见 [rememberExecuted] 的说明。 */
        const val EXECUTED_KEY_CAPACITY = 4096
    }
}
