package com.ai.assistance.quro.cluster.engine

import com.ai.assistance.quro.cluster.bridge.HostMessage
import com.ai.assistance.quro.cluster.bridge.HostRole
import com.ai.assistance.quro.cluster.bridge.HostToolSchema
import com.ai.assistance.quro.cluster.bridge.LlmResult
import com.ai.assistance.quro.cluster.engine.memory.Blackboard
import com.ai.assistance.quro.cluster.engine.memory.SemanticMemory
import com.ai.assistance.quro.cluster.model.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 一轮执行的上下文 */
data class TurnContext(
    val clusterId: ClusterId,
    val task: Task,
    val node: TaskNode? = null,
    val blackboard: Blackboard,
    val memory: SemanticMemory,
    val intent: TurnIntent,
    val instruction: String,
    val untrusted: String? = null,
    val extraSystem: String = "",
    val jsonMode: Boolean = false,
    /** 主持点名理由，写进提示词让角色知道为什么被叫到 */
    val reason: String? = null
)

/** 执行产生的记录 */
data class RunOutcome(
    val text: String,
    val toolCalls: List<String>,
    val modelId: String,
    val tokens: Int,
    val turnId: TurnId
)

/**
 * 角色运行时 —— 每个角色每次调用都走自己的 binding、自己的上下文、自己的预算。
 * 主持与角色共用这一个实现，只是传入的 AgentConfig 不同。
 */
class AgentRunner(private val env: EngineEnv) {

    private val json = Json { ignoreUnknownKeys = true }

    /** 主持专用的合成配置：身份来自不可变的 HostIdentity，能力部分来自 HostConfig */
    fun hostAgent(host: HostConfig, clusterId: ClusterId): AgentConfig = AgentConfig(
        id = HOST_AGENT_ID,
        clusterId = clusterId,
        identity = Identity(
            name = HostIdentity.HOST_NAME,
            avatarEmoji = HostIdentity.INSTANCE.avatarEmoji,
            selfReference = "本主持"
        ),
        persona = HostIdentity.INSTANCE.oath + "\n风格：" + host.personaLite,
        duties = listOf(
            "理解用户目标，产出可判定的验收标准",
            "把目标拆成有依赖关系的子任务",
            "按相关度点名角色并说明理由",
            "对多份方案做裁决，产出唯一执行计划",
            "对产物做验收，不通过则携带失败原因驱动重规划",
            "全部完成或触发熔断时收敛关闭任务"
        ),
        taboos = listOf(
            "不得替角色执行具体工作",
            "不得在没有验收标准的情况下开工",
            "不得在任务未闭环时结束自己",
            "不得编造角色产出过的内容"
        ),
        modelBinding = host.modelBinding,
        skillIds = host.skillIds,
        toolIds = host.toolIds,
        policy = AgentPolicy(maxTurnsPerTask = Int.MAX_VALUE)
    )

    suspend fun run(agent: AgentConfig, ctx: TurnContext): Result<RunOutcome> {
        if (!agent.enabled) return Result.failure(IllegalStateException("角色已停用: ${agent.identity.name}"))

        val turnId = TurnId(newId("turn"))
        env.store.appendTurn(
            Turn(turnId, ctx.task.id, ctx.task.turnCount + 1, agent.id, ctx.intent, nodeId = ctx.node?.id)
        )

        // —— 1. 组装该角色专属的上下文 ——
        val skillsL1 = env.skills.l1(agent.skillIds)
        val l2 = env.skills.l2(agent.skillIds, ctx.instruction + ctx.task.goal)
        val skillsL2 = l2.mapNotNull { it.body }
        l2.forEach { snap ->
            env.event.emit(
                ClusterEvent.SkillLoaded(ctx.task.id, agent.id, snap.manifest.id, snap.level.name)
            )
        }

        val bbKeys = agent.modelBinding.context.blackboardKeys
        val bbText = ctx.blackboard.render(minConfidence = 0.5f, keys = bbKeys)
        val rag = if (agent.modelBinding.context.maxSemanticHits > 0)
            ctx.memory.search(ctx.clusterId, ctx.instruction, agent.modelBinding.context.maxSemanticHits)
        else emptyList()

        val history = if (agent.modelBinding.context.includeOtherAgentsUtterances)
            env.store.messages(ctx.task.id, agent.modelBinding.context.historyWindowTurns * 2)
                .filter { it.agentId != agent.id }
                .takeLast(12)
                .map { (env.store.agent(it.agentId)?.identity?.name ?: it.agentId.value) to it.content }
        else emptyList()

        val artifacts = if (agent.modelBinding.context.includeArtifacts)
            env.store.artifacts(ctx.task.id).takeLast(6).map { "${it.title}：${it.content?.take(400) ?: it.uri.orEmpty()}" }
        else emptyList()

        val inputs = PromptAssembler.Inputs(
            agent = agent,
            goal = ctx.task.goal,
            node = ctx.node,
            acceptance = ctx.node?.acceptance ?: ctx.task.acceptance,
            blackboard = bbText,
            ragHits = rag,
            skillsL1 = skillsL1,
            skillsL2 = skillsL2,
            otherUtterances = history,
            artifacts = artifacts,
            instruction = buildInstruction(ctx),
            untrustedContent = ctx.untrusted,
            extraSystem = ctx.extraSystem
        )
        val messages = env.prompts.messages(inputs).toMutableList()

        // —— 2. 工具循环 ——
        val toolSpecs = env.tools.specsFor(agent.id)
            .map { HostToolSchema(it.name, it.description, it.jsonSchema) }
        val executed = ArrayList<String>()
        var finalText = ""
        var usedModel = agent.modelBinding.hostModelId
        var totalTokens = 0

        repeat(agent.policy.maxToolCallsPerTurn + 1) { round ->
            val res: Result<LlmResult> = env.gateway.call(
                agentId = agent.id,
                taskId = ctx.task.id,
                binding = agent.modelBinding,
                messages = messages,
                tools = if (agent.policy.allowToolCalls) toolSpecs else emptyList(),
                jsonMode = ctx.jsonMode,
                traceId = turnId.value
            )

            val r = res.getOrElse {
                env.event.emit(ClusterEvent.ErrorRaised(ctx.task.id, agent.id, it.message ?: "模型调用失败"))
                return Result.failure(it)
            }
            usedModel = r.usedModelId
            totalTokens += r.promptTokens + r.completionTokens

            if (r.toolCalls.isEmpty() || round == agent.policy.maxToolCallsPerTurn) {
                finalText = r.text
                // 🔴 无工具调用的这一轮就是角色的**最终发言**，必须发出去，
                //   否则用户在对话框里永远看不到角色说了什么（只剩结构化摘要）。
                if (r.text.isNotBlank()) emitSpoke(ctx, agent, r.text, usedModel)
                return@repeat
            }

            // 有工具调用：执行后把结果以 TOOL 角色塞回，继续下一轮
            messages += HostMessage(
                HostRole.ASSISTANT, r.text,
                toolCalls = r.toolCalls
            )
            for (call in r.toolCalls) {
                val spec = env.tools.specsFor(agent.id).firstOrNull { it.name == call.name }
                val toolId = spec?.id ?: ToolId(call.name)
                val out = env.tools.invoke(
                    agentId = agent.id,
                    toolId = toolId,
                    argsJson = call.argsJson,
                    ctx = com.ai.assistance.quro.cluster.engine.capability.ToolContext(
                        taskId = ctx.task.id, agentId = agent.id, clusterId = ctx.clusterId,
                        approve = { p, risk -> env.approver.ask(p, risk) }
                    )
                )
                executed += "${call.name}(${out.output.take(80)})"
                // 🔴🔴 先发 AgentSpoke（角色在调工具前说的话），再发 ToolInvoked。
                //   顺序很重要：对话框里应该是「角色说：我去查一下」→ 紧跟工具卡，
                //   而不是凭空冒出一个工具、或工具排在解释前面。
                //
                //   `r.text` 在有工具调用时是「角色这一轮的原始发言」，
                //   过去直接被丢进 messages 当上下文，用户完全看不见 —— 这就是黑盒。
                emitSpoke(ctx, agent, r.text, usedModel)
                env.event.emit(
                    ClusterEvent.ToolInvoked(
                        taskId = ctx.task.id,
                        toolId = toolId,
                        agentId = agent.id,
                        status = if (out.ok) "SUCCESS" else "FAILED",
                        // 🔴 参数/结果必须带上：没有它们，对话框里就只有一句
                        //「调用工具 xxx（SUCCESS）」这种元叙述，用户等于在看黑盒。
                        // 截断到 500/800 字符：够看清「查了什么/回了什么」，
                        // 又不会把事件流和消息存储撑爆。
                        argsJson = call.argsJson.take(500),
                        output = out.output.take(800),
                        error = out.error?.take(300).orEmpty(),
                    )
                )
                messages += HostMessage(
                    HostRole.TOOL,
                    if (out.ok) out.output else "工具执行失败：${out.error}",
                    name = call.name, toolCallId = call.id
                )
            }
        }

        // —— 3. 落库与发事件 ——
        val msg = Message(
            id = MessageId(newId("msg")), turnId = turnId, taskId = ctx.task.id, agentId = agent.id,
            role = MessageRole.ASSISTANT, content = finalText,
            tokensIn = 0, tokensOut = totalTokens, hostModelId = usedModel
        )
        env.store.appendMessage(msg)
        if (finalText.length > 120) {
            ctx.memory.add(ctx.clusterId, finalText.take(1_500), agent.identity.name)
        }
        return Result.success(RunOutcome(finalText, executed, usedModel, totalTokens, turnId))
    }

    /**
     * 发一条 [ClusterEvent.AgentSpoke] —— 让角色在对话框里「像正常 AI 一样说话」的**唯一出口**。
     *
     * 🔴 口径（每一条都不能省，否则又变回黑盒）：
     *  - **空白正文不发**：空消息在对话框里就是一个空气泡，比不发更糟。
     *  - **原文透传**：intent 为 PROPOSE/PLAN 时正文是结构化 JSON，
     *    这里不剥不改，UI 侧才有机会把它渲染成结构化卡片。
     *  - **emit 失败不外抛**：可观测性是旁路，绝不能因为它把角色执行搞挂。
     */
    private suspend fun emitSpoke(
        ctx: TurnContext,
        agent: AgentConfig,
        text: String,
        modelId: String,
    ) {
        if (text.isBlank()) return
        runCatching {
            env.event.emit(
                ClusterEvent.AgentSpoke(
                    taskId = ctx.task.id,
                    agentId = agent.id,
                    nodeId = ctx.node?.id,
                    text = text,
                    intent = ctx.intent.name,
                    modelId = modelId,
                )
            )
        }
    }

    private fun buildInstruction(ctx: TurnContext): String = buildString {
        ctx.reason?.let { appendLine("主持点名你的理由：$it"); appendLine() }
        appendLine(ctx.instruction)
        when (ctx.intent) {
            TurnIntent.PROPOSE -> appendLine("\n请输出 JSON：{\"summary\":\"…\",\"steps\":[\"…\"],\"risks\":[\"…\"],\"selfConfidence\":0.8}")
            TurnIntent.VERIFY -> appendLine("\n请输出 JSON：{\"pass\":true/false,\"reason\":\"…\",\"failedChecks\":[\"…\"]}")
            TurnIntent.ARBITRATE -> appendLine("\n请输出 JSON：{\"pass\":true,\"reason\":\"…\",\"chosenAgent\":\"agentId\",\"mergedSteps\":[\"…\"]}")
            TurnIntent.PLAN -> appendLine("\n请输出 JSON：{\"acceptance\":{\"description\":\"…\",\"checkList\":[\"…\"]},\"nodes\":[{\"id\":\"n1\",\"title\":\"…\",\"type\":\"WRITE\",\"instruction\":\"…\",\"dependsOn\":[]}]}")
            TurnIntent.CLOSE -> appendLine("\n请输出 JSON：{\"summary\":\"…\"}")
            else -> Unit
        }
    }

    // —— 结构化解析（带兜底）——

    fun parseProposal(agentId: AgentId, nodeId: NodeId, raw: String): Proposal {
        val o = firstJson(raw)
        return if (o != null) {
            Proposal(
                agentId, nodeId,
                summary = o["summary"]?.jsonPrimitive?.content ?: raw.take(200),
                steps = o["steps"]?.jsonArrayOrNull { it } ?: emptyList(),
                risks = o["risks"]?.jsonArrayOrNull { it } ?: emptyList(),
                selfConfidence = o["selfConfidence"]?.jsonPrimitive?.floatOrNull() ?: 0.7f
            )
        } else Proposal(agentId, nodeId, raw.take(200), listOf(raw), emptyList(), 0.5f)
    }

    fun parseVerdict(raw: String): Verdict {
        val o = firstJson(raw)
        return if (o != null) Verdict(
            pass = o["pass"]?.jsonPrimitive?.booleanOrNull() ?: raw.contains("通过"),
            reason = o["reason"]?.jsonPrimitive?.content ?: "",
            chosenAgent = o["chosenAgent"]?.jsonPrimitive?.content?.let { AgentId(it) },
            mergedSteps = o["mergedSteps"]?.jsonArrayOrNull { it } ?: emptyList()
        ) else Verdict(raw.contains("通过"), raw.take(200))
    }

    fun parseReview(raw: String): ReviewResult {
        val o = firstJson(raw)
        return if (o != null) ReviewResult(
            pass = o["pass"]?.jsonPrimitive?.booleanOrNull() ?: false,
            reason = o["reason"]?.jsonPrimitive?.content ?: "",
            failedChecks = o["failedChecks"]?.jsonArrayOrNull { it } ?: emptyList()
        ) else ReviewResult(raw.contains("通过") && !raw.contains("不通过"), raw.take(200))
    }

    fun parsePlan(raw: String): Pair<AcceptanceCriteria?, List<TaskNode>> {
        val o = firstJson(raw) ?: return null to emptyList()
        val acc = o["acceptance"]?.jsonObject?.let { a ->
            AcceptanceCriteria(
                description = a["description"]?.jsonPrimitive?.content ?: "",
                checkList = a["checkList"]?.jsonArrayOrNull { it } ?: emptyList()
            )
        }
        val nodes = o["nodes"]?.jsonArray?.mapNotNull { n ->
            val e = n.jsonObject
            val id = e["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            TaskNode(
                id = NodeId(id),
                title = e["title"]?.jsonPrimitive?.content ?: id,
                type = runCatching { NodeType.valueOf(e["type"]?.jsonPrimitive?.content ?: "WRITE") }
                    .getOrDefault(NodeType.WRITE),
                instruction = e["instruction"]?.jsonPrimitive?.content ?: "",
                dependsOn = e["dependsOn"]?.jsonArrayOrNull { NodeId(it) } ?: emptyList()
            )
        } ?: emptyList()
        return acc to nodes
    }

    private fun firstJson(raw: String): JsonObject? {
        val s = raw.indexOf('{')
        if (s < 0) return null
        var d = 0
        for (i in s until raw.length) {
            when (raw[i]) {
                '{' -> d++
                '}' -> { d--; if (d == 0) return runCatching {
                    json.parseToJsonElement(raw.substring(s, i + 1)).jsonObject }.getOrNull() }
            }
        }
        return null
    }

    /**
     * 取字符串数组；给定 [wrap] 时再包一层（dependsOn 是 List<NodeId> 而不是 List<String>）。
     * JSON 元素先统一降级成字符串，交给调用方决定包装成什么值对象。
     */
    private fun <T> JsonElement.jsonArrayOrNull(wrap: (String) -> T): List<T>? =
        runCatching {
            (this as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.map(wrap)
        }.getOrNull()

    private fun JsonPrimitive.booleanOrNull() =
        runCatching { content.toBoolean() }.getOrNull() ?: (contentOrNull == "true")

    private fun JsonPrimitive.floatOrNull() =
        runCatching { content.toFloat() }.getOrNull()
}
