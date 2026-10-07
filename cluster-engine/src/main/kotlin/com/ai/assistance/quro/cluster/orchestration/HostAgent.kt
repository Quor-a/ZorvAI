package com.ai.assistance.quro.cluster.engine.orchestration

import com.ai.assistance.quro.cluster.engine.EngineEnv
import com.ai.assistance.quro.cluster.engine.RunOutcome
import com.ai.assistance.quro.cluster.engine.TurnContext
import com.ai.assistance.quro.cluster.engine.memory.AgentProfile
import com.ai.assistance.quro.cluster.engine.memory.Blackboard
import com.ai.assistance.quro.cluster.engine.memory.SemanticMemory
import com.ai.assistance.quro.cluster.model.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * ★ 主持（Host）★ —— 集群里唯一的调度者与唯一的责任主体。
 *
 * ## 骨架（v2 重写：线性、可端到端验证）
 *
 * 旧版是一个 11 态状态机（INTAKE→PLANNING→DISPATCHING→PROPOSING→REVIEWING→
 * EXECUTING→VERIFYING→CONVERGING→REPLANNING→CLOSING）。它**从未被任何单元测试覆盖**
 * （9 个集群测试全是投影器级/源码级），真机上反复"一个字都没有"——
 * 任一状态静默不产出事件，整条链路就黑盒。
 *
 * 新骨架只做四件事，且每一步都**必然产生可见事件**：
 *  1. 拆子任务（主持一次调用，发 AcceptanceDefined + PlanProposed）；
 *  2. 逐个子任务：选人 → 角色执行（AgentRunner 内部已 emit AgentSpoke + ToolInvoked）；
 *  3. 主持综合最终答案（一次调用，发最终 AgentSpoke）；
 *  4. 关闭（发 TaskClosed）。
 *
 * 没有多轮提案/裁决/验收/重规划——这些只是把"角色说一句话"拆成十几次模型调用，
 * 既慢又容易在某步静默失败。真命题/验收由角色在自己执行时一次完成。
 *
 * 🔴 健壮性铁律：任何一步模型调用失败都**不阻断整体**，记错误、继续下一个子任务，
 * 最后由主持综合给出"已完成 X/N"的结论，绝不空转、绝不静默黑盒。
 */
class HostAgent(
    private val env: EngineEnv,
    private val runner: com.ai.assistance.quro.cluster.engine.AgentRunner,
    private val router: Router,
    private val profiles: MutableMap<AgentId, AgentProfile> = mutableMapOf()
) {
    private val _events = MutableSharedFlow<ClusterEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<ClusterEvent> = _events.asSharedFlow()

    private var paused = false
    private val inbox = Channel<UserCommand>(Channel.UNLIMITED)

    fun submitCommand(cmd: UserCommand) { inbox.trySend(cmd) }

    /**
     * 驱动一个任务到闭环。这是整个 SDK 的心脏。
     * 线性骨架：拆 → 逐子任务执行 → 综合 → 关闭。
     * @return 关闭原因
     */
    suspend fun drive(cluster: Cluster, task: Task, memory: SemanticMemory): CloseReason {
        val bb = Blackboard(task.id) { entry -> env.store.saveBlackboard(task.id, entry) }
        bb.restore(env.store.blackboard(task.id))   // 断点续跑：先恢复已有黑板
        val host = runner.hostAgent(cluster.host, cluster.id)
        emit(ClusterEvent.UserGoal(task.id, task.goal))

        return runCatching { runLinear(cluster, task, host, bb, memory) }
            .getOrElse { t ->
                logE("集群执行异常", t)
                emit(ClusterEvent.ErrorRaised(task.id, HOST_AGENT_ID, "集群执行异常：${t.message}"))
                close(task, host, bb, CloseReason.UNRECOVERABLE, memory)
            }
    }

    /** 线性主流程。任何异常都由 [drive] 兜底，这里只管正常推进。 */
    private suspend fun runLinear(
        cluster: Cluster, task: Task, host: AgentConfig, bb: Blackboard, memory: SemanticMemory
    ): CloseReason {
        task.state = TaskState.EXECUTING

        // —— 1. 拆子任务 ——
        val (acceptance, nodes) = decompose(host, task, bb, memory)
        task.acceptance = acceptance
            ?: AcceptanceCriteria("完成用户目标：${task.goal}", listOf("产出与用户目标一致"))
        task.graph = TaskGraph(
            nodes.mapIndexed { i, n -> if (n.id.value.startsWith("n")) n else n.copy(id = NodeId("n${i + 1}")) }
        )
        emit(ClusterEvent.AcceptanceDefined(task.id, task.acceptance!!.description, task.acceptance!!.checkList))
        emit(ClusterEvent.PlanProposed(task.id, task.graph.nodes.size))

        // —— 2. 逐子任务：选人 → 执行 ——
        val agents = env.store.agents(cluster.id).filter { it.enabled && it.id != HOST_AGENT_ID }
        for (node in task.graph.nodes) {
            drainCommands(task)
            if (paused) waitWhilePaused()
            checkTermination(task)?.let { reason -> return close(task, host, bb, reason, memory) }

            val agent = pickAgent(cluster.id, task.id, agents, node) ?: host
            if (agent.id != HOST_AGENT_ID) node.assignee = agent.id
            emit(ClusterEvent.SpeakerSelected(task.id, agent.id, node.id, "按职责/技能匹配"))

            val out: RunOutcome? = runner.run(
                agent,
                TurnContext(
                    clusterId = cluster.id, task = task, node = node, blackboard = bb, memory = memory,
                    intent = TurnIntent.EXECUTE,
                    instruction = buildNodeInstruction(node),
                )
            ).getOrNull()

            if (out == null) {
                node.lastError = "角色执行失败"
                node.state = NodeState.FAILED
                emit(ClusterEvent.ErrorRaised(task.id, agent.id, "角色 ${agent.identity.name} 执行失败"))
                continue   // 不阻断：继续下一个子任务
            }
            task.turnCount++
            task.tokenUsed += out.tokens
            node.state = NodeState.DONE
            record(agent.id, true)
            env.store.saveTask(task)
        }

        // —— 3. 主持综合最终答案 ——
        synthesize(host, task, bb, memory)

        // —— 4. 关闭 ——
        return close(task, host, bb, CloseReason.GOAL_REACHED, memory)
    }

    /** 拆子任务：主持一次调用，返回（验收标准, 子任务列表）。失败返回（null, 空）。 */
    private suspend fun decompose(
        host: AgentConfig, task: Task, bb: Blackboard, memory: SemanticMemory
    ): Pair<AcceptanceCriteria?, List<TaskNode>> {
        val out = callHost(
            host, task, bb, memory,
            intent = TurnIntent.PLAN,
            instruction = """
用户目标：${task.goal}

请做两件事，只输出 JSON：
1. 定义验收标准（可判定的描述 + 逐条 checklist）；
2. 把目标拆成 2~7 个子任务，标明依赖顺序与类型（RESEARCH/WRITE/CODE/REVIEW/VERIFY/SYNTHESIS）。
只输出 JSON：{"acceptance":{"description":"…","checkList":["…"]},"nodes":[{"id":"n1","title":"…","type":"WRITE","instruction":"…","dependsOn":[]}]}
            """.trimIndent(),
            jsonMode = true
        ) ?: return null to emptyList()
        return runner.parsePlan(out.text)
    }

    /** 主持综合最终答案：一次调用，把子任务成果汇总成自然语言结论。 */
    private suspend fun synthesize(
        host: AgentConfig, task: Task, bb: Blackboard, memory: SemanticMemory
    ) {
        val done = task.graph.progress().first
        val total = task.graph.progress().second
        callHost(
            host, task, bb, memory,
            intent = TurnIntent.CLOSE,
            instruction = """
全部子任务已完成（$done/$total）。请综合各角色的成果，用 3-5 句话总结本次交付，并列出关键产出。
只输出 JSON：{"summary":"…"}
            """.trimIndent(),
            jsonMode = true
        )
        // callHost 内部走 AgentRunner.run，已经把主持的总结作为 AgentSpoke 发到对话流；
        // 若调用失败，AgentRunner 不会 emit，对话里自然少一条，不影响整体闭环。
    }

    /** 选人：优先 Router 打分，无人可派时回退到主持自己推进。 */
    private suspend fun pickAgent(
        clusterId: ClusterId, taskId: TaskId, agents: List<AgentConfig>, node: TaskNode
    ): AgentConfig? {
        if (agents.isEmpty()) return null
        val picks = router.pick(
            clusterId = clusterId, taskId = taskId, node = node,
            candidates = agents, topK = 1, profiles = profiles
        )
        return picks.firstOrNull()?.let { env.store.agent(it.agentId) } ?: agents.firstOrNull()
    }

    /** 子任务给角色的指令：标题 + 说明 + 验收标准。 */
    private fun buildNodeInstruction(node: TaskNode): String = buildString {
        appendLine("子任务：「${node.title}」")
        if (node.instruction.isNotBlank()) appendLine(node.instruction)
        node.acceptance?.let { appendLine("\n验收标准：${it.description}") }
        appendLine("\n请直接产出本子任务的成果内容（不要只说“我会怎么做”）。")
    }

    // —— 暂停等待（被 UserCommand.Pause 置位时挂起，Resume 后继续）——
    private suspend fun waitWhilePaused() { while (paused) delay(300) }

    // ——————————————— 终止与收尾 ———————————————

    /** 返回非空即触发熔断。这是主持唯一的"被迫下班"通道。 */
    private suspend fun checkTermination(task: Task): CloseReason? {
        val p = task.termination
        val elapsed = env.clock() - task.createdAt
        return when {
            task.turnCount >= p.maxTurns -> {
                emit(ClusterEvent.BudgetExceeded(task.id, "turns=${task.turnCount}")); CloseReason.CIRCUIT_BROKEN
            }
            task.tokenUsed >= p.maxTokens -> {
                emit(ClusterEvent.BudgetExceeded(task.id, "tokens=${task.tokenUsed}")); CloseReason.CIRCUIT_BROKEN
            }
            elapsed >= p.maxWallClockMs -> {
                emit(ClusterEvent.BudgetExceeded(task.id, "elapsed=${elapsed}ms")); CloseReason.CIRCUIT_BROKEN
            }
            task.replanCount > p.maxReplan -> {
                emit(ClusterEvent.Escalated(task.id, "重规划次数超限 ${task.replanCount}")); CloseReason.CIRCUIT_BROKEN
            }
            task.idleStreak >= p.noProgressWindow -> {
                emit(ClusterEvent.Escalated(task.id, "连续 ${task.idleStreak} 轮无进展")); CloseReason.CIRCUIT_BROKEN
            }
            else -> null
        }
    }

    private suspend fun close(
        task: Task, host: AgentConfig, bb: Blackboard, reason: CloseReason, memory: SemanticMemory
    ): CloseReason {
        val summary = if (reason == CloseReason.GOAL_REACHED) {
            // synthesize 已把总结发成 AgentSpoke；这里只给一句收尾
            "任务完成（${task.graph.progress().first}/${task.graph.progress().second} 个子任务）。"
        } else {
            "任务中止：$reason（已完成 ${task.graph.progress().first}/${task.graph.progress().second} 个子任务）"
        }
        task.state = if (reason == CloseReason.GOAL_REACHED) TaskState.ARCHIVED else TaskState.ESCALATED
        task.closedAt = env.clock()
        task.closeReason = reason
        task.summary = summary
        env.store.saveTask(task)
        emit(ClusterEvent.TaskClosed(task.id, reason, summary))
        return reason
    }

    // ——————————————— 工具方法 ———————————————

    /** 主持自己发起一次模型调用。主持没配模型时，退到集群里第一个可用模型。 */
    private suspend fun callHost(
        host: AgentConfig, task: Task, bb: Blackboard, memory: SemanticMemory,
        intent: TurnIntent, instruction: String, jsonMode: Boolean
    ): RunOutcome? {
        val binding = host.modelBinding
        val usable = if (binding.hostModelId.isNotBlank()) {
            env.gateway.info(binding.hostModelId)?.available != false
        } else false

        val agent = if (!usable) {
            val fallback = env.gateway.models().firstOrNull { it.available }
                ?: run {
                    emit(ClusterEvent.ErrorRaised(task.id, HOST_AGENT_ID, "宿主没有任何可用模型"))
                    return null
                }
            emit(ClusterEvent.ModelSwitched(task.id, HOST_AGENT_ID, binding.hostModelId, fallback.id, "主持未配置或模型不可用"))
            host.copy(modelBinding = binding.copy(hostModelId = fallback.id))
        } else host

        return runner.run(
            agent,
            TurnContext(
                clusterId = agent.clusterId, task = task, blackboard = bb, memory = memory,
                intent = intent, instruction = instruction, jsonMode = jsonMode
            )
        ).getOrNull()
    }

    private suspend fun drainCommands(task: Task) {
        while (true) {
            when (val c = inbox.tryReceive().getOrNull()) {
                null -> return
                is UserCommand.Pause -> paused = true
                is UserCommand.Resume -> paused = false
                is UserCommand.Terminate -> {
                    task.closeReason = CloseReason.USER_ABORTED
                    task.state = TaskState.ESCALATED
                }
                is UserCommand.Inject -> env.store.appendMessage(
                    Message(MessageId(newId("msg")), TurnId(""), task.id, AgentId("user"),
                        MessageRole.USER, c.text, untrusted = true)
                )
                is UserCommand.Reassign -> task.graph.byId(c.nodeId)?.assignee = c.agentId
                is UserCommand.ForceSpeaker -> {
                    task.graph.byId(task.graph.ready().firstOrNull()?.id ?: return)?.assignee = c.agentId
                    env.store.appendMessage(
                        Message(MessageId(newId("msg")), TurnId(""), task.id, c.agentId, MessageRole.USER, c.text)
                    )
                }
            }
        }
    }

    private suspend fun emit(e: ClusterEvent) {
        env.store.appendEvent(e)
        env.event.emit(e)
        _events.emit(e)
    }

    private fun record(agentId: AgentId, success: Boolean) {
        if (agentId == HOST_AGENT_ID) return
        val old = profiles[agentId] ?: AgentProfile(agentId)
        profiles[agentId] = old.copy(
            successCount = old.successCount + if (success) 1 else 0,
            failCount = old.failCount + if (success) 0 else 1
        )
    }

    private fun logE(msg: String, t: Throwable?) {
        // 轻量日志：不依赖 Android Log，便于 JVM 单测
        println("[HostAgent] $msg ${t?.message ?: ""}")
    }
}
