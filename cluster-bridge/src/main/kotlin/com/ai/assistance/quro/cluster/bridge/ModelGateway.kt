package com.ai.assistance.quro.cluster.bridge

import com.ai.assistance.quro.cluster.model.AgentError
import com.ai.assistance.quro.cluster.model.AgentId
import com.ai.assistance.quro.cluster.model.ModelBinding
import com.ai.assistance.quro.cluster.model.ModelCapabilities
import com.ai.assistance.quro.cluster.model.OutputProtocol
import com.ai.assistance.quro.cluster.model.TaskId
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlin.math.min

/** 单次调用结果 */
data class LlmResult(
    val text: String,
    val toolCalls: List<HostToolCall>,
    val promptTokens: Int,
    val completionTokens: Int,
    /** 真正跑通的宿主模型 ID（可能已降级，与 binding.hostModelId 不同） */
    val usedModelId: String
)

/**
 * 模型网关 —— SDK 里唯一会跟宿主模型层打交道的地方。
 *
 * 它承担六件事，全部对上层透明：
 *  1. 模型清单缓存与能力查询
 *  2. 按 hostModelId 的并发闸门（本地模型填 1 就天然串行）
 *  3. 降级链：失败/超时/模型不可用时依次切换，并上报 ModelSwitched 事件
 *  4. token 预算记账与熔断（只熔断这一个角色，不连坐）
 *  5. 输出协议适配与结构化抽取
 *  6. 超时保护
 */
class ModelGateway(
    private val bridge: HostModelBridge,
    private val spend: suspend (agentId: AgentId, taskId: TaskId?, modelId: String, tokens: Int) -> Unit = { _, _, _, _ -> },
    private val onSwitch: (AgentId, from: String, to: String, reason: String) -> Unit = { _, _, _, _ -> },
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val cache = LinkedHashMap<String, HostModelInfo>()
    private val gates = HashMap<String, Semaphore>()
    private val spentTask = HashMap<String, Long>()   // key=agentId|taskId|modelId
    private val spentDay = HashMap<String, Long>()
    @Volatile private var lastRefresh = 0L
    private val refreshTtlMs = 30_000L

    /** 拉取宿主模型列表（带 30s 缓存） */
    suspend fun models(force: Boolean = false): List<HostModelInfo> {
        val now = clock()
        if (force || cache.isEmpty() || now - lastRefresh > refreshTtlMs) {
            bridge.listModels().forEach { cache[it.id] = it }
            lastRefresh = now
        }
        return cache.values.toList()
    }

    suspend fun info(id: String): HostModelInfo? = models().firstOrNull { it.id == id }
        ?: models(force = true).firstOrNull { it.id == id }

    suspend fun capabilities(id: String): ModelCapabilities =
        info(id)?.capabilities ?: ModelCapabilities()

    fun invalidate() { cache.clear(); lastRefresh = 0L }

    private fun gateFor(modelId: String, binding: ModelBinding): Semaphore {
        val hostMax = cache[modelId]?.maxConcurrency ?: 1
        val want = min(hostMax, binding.maxConcurrency ?: hostMax).coerceAtLeast(1)
        return gates.getOrPut(modelId) { Semaphore(want) }
    }

    /**
     * 发起一次角色专属的模型请求。
     * 每个角色调这个方法时传的都是自己的 binding —— 这就是"每个角色一条独立请求"的落点。
     */
    suspend fun call(
        agentId: AgentId,
        taskId: TaskId?,
        binding: ModelBinding,
        messages: List<HostMessage>,
        tools: List<HostToolSchema> = emptyList(),
        jsonMode: Boolean = false,
        timeoutMs: Long = 120_000L,
        traceId: String? = null
    ): Result<LlmResult> {
        val chain = listOf(binding.hostModelId) + binding.fallbackChain
        var lastError: String? = null

        for ((index, modelId) in chain.withIndex()) {
            if (modelId.isBlank()) continue

            // 预算检查：超了直接熔断本角色，不再尝试降级（降级也是花钱）
            if (overBudget(agentId, taskId, modelId, binding)) {
                return Result.failure(AgentError.BudgetExceeded("agent=${agentId.value}"))
            }

            val caps = capabilities(modelId)
            if (!caps.supportsTools && tools.isNotEmpty() && index == 0) {
                // 首选项不支持工具，但请求需要工具 → 直接跳到降级链，省一次无用调用
                lastError = "模型 $modelId 不支持工具调用"
                continue
            }

            val protocol = ProtocolAdapter.resolve(binding.protocol, caps)
            val render = ProtocolAdapter.renderTools(protocol, tools)
            val finalSystem = render.systemSuffix

            val request = HostChatRequest(
                modelId = modelId,
                messages = messages.map {
                    if (it.role == HostRole.SYSTEM && finalSystem.isNotBlank() && it == messages.first())
                        it.copy(content = it.content + finalSystem)
                    else it
                },
                temperature = binding.params.temperature,
                topP = binding.params.topP,
                maxOutputTokens = binding.params.maxOutputTokens,
                stop = binding.params.stop,
                tools = render.native ?: emptyList(),
                jsonMode = jsonMode || protocol == OutputProtocol.JSON_MODE,
                traceId = traceId
            )

            val out = runCatching {
                withTimeout(timeoutMs) {
                    gateFor(modelId, binding).withPermit { execute(request) }
                }
            }

            val result = out.getOrNull()
            if (result != null) {
                val total = result.promptTokens + result.completionTokens
                if (index > 0) onSwitch(agentId, binding.hostModelId, modelId, lastError ?: "降级")
                accumulate(agentId, taskId, modelId, total)          // 进程内快速判断
                spend(agentId, taskId, modelId, total)               // 落持久化账本
                return Result.success(result)
            }

            val err = out.exceptionOrNull()
            lastError = when (err) {
                is TimeoutCancellationException -> "超时 ${timeoutMs}ms"
                else -> err?.message ?: "未知错误"
            }
            // 不可重试的错误（如参数非法）直接放弃
            if (lastError!!.contains("invalid", true) && lastError!!.contains("request", true)) break
            delay(300L * (index + 1))  // 退避，避免把宿主接口打爆
        }

        return Result.failure(
            AgentError.ModelFailure(binding.hostModelId, lastError ?: "无可用的宿主模型")
        )
    }

    /** 收集流，拼成完整结果 */
    private suspend fun execute(req: HostChatRequest): LlmResult {
        val sb = StringBuilder()
        val calls = ArrayList<HostToolCall>()
        var pTokens = 0
        var cTokens = 0
        var failed: String? = null

        bridge.chat(req)
            .onEach { chunk ->
                when (chunk) {
                    is HostChatChunk.Delta -> sb.append(chunk.text)
                    is HostChatChunk.ToolCall -> calls += chunk.call
                    is HostChatChunk.Usage -> { pTokens += chunk.usage.promptTokens; cTokens += chunk.usage.completionTokens }
                    is HostChatChunk.Error -> failed = chunk.message
                    is HostChatChunk.Done -> Unit
                }
            }
            .catch { failed = it.message ?: "流异常" }
            .collect()

        if (failed != null) throw IllegalStateException(failed)
        if (pTokens == 0) pTokens = sb.length / 4
        if (cTokens == 0) cTokens = sb.length / 4

        val raw = sb.toString()
        val protocol = ProtocolAdapter.resolve(OutputProtocol.AUTO, capabilities(req.modelId))
        val ex = ProtocolAdapter.extract(raw, protocol)

        return LlmResult(
            text = ex.text ?: if (ex.toolCalls.isEmpty()) raw else "",
            toolCalls = calls + ex.toolCalls,
            promptTokens = pTokens,
            completionTokens = cTokens,
            usedModelId = req.modelId
        )
    }

    private fun overBudget(a: AgentId, t: TaskId?, m: String, b: ModelBinding): Boolean {
        val taskKey = "${a.value}|${t?.value}|$m"
        val dayKey = "${a.value}|day|$m"
        return (spentTask[taskKey] ?: 0) >= b.maxTokensPerTask ||
            (spentDay[dayKey] ?: 0) >= b.maxTokensPerDay
    }

    private fun accumulate(agentId: AgentId, taskId: TaskId?, modelId: String, tokens: Int) {
        spentTask["${agentId.value}|${taskId?.value}|$modelId"] =
            (spentTask["${agentId.value}|${taskId?.value}|$modelId"] ?: 0) + tokens
        spentDay["${agentId.value}|day|$modelId"] =
            (spentDay["${agentId.value}|day|$modelId"] ?: 0) + tokens
    }

    /** 任务结束或跨天时清理进程内计数，避免无限增长 */
    fun resetCounters(taskScopeOnly: Boolean = false) {
        spentTask.clear()
        if (!taskScopeOnly) spentDay.clear()
    }
}
