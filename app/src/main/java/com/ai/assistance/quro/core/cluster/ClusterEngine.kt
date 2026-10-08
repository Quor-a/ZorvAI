package com.ai.assistance.quro.core.cluster

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * ★ 集群引擎 —— 主持驱动任务到闭环 ★
 *
 * 这是 Zorv AI 里原本没有的东西：它现在是一个 QuroAssistant 跑一个 ReAct 循环，
 * 单助手单会话。这里在其之上加一层"多角色 + 仲裁"，
 * 但**不接管**它的工具执行与记忆，只是借用 LlmGateway 做角色发言。
 *
 * 主持铁律：
 *   1. 主持身份来自 RoleRegistry.HOST_PERSONA_ID + HOST_OATH，代码级固定；
 *   2. 任务不闭环，drive() 不返回；
 *   3. 下班只有四种：目标达成 / 熔断 / 用户中止 / 不可恢复；
 *   4. 主持不做具体工作，只做：定标准 → 拆解 → 点名 → 裁决 → 验收 → 收敛。
 */
class ClusterEngine(
    private val context: Context,
    private val gateway: LlmGateway,
    private val modelSource: ClusterModelSource
) {

    private val _events = MutableSharedFlow<ClusterEvent>(extraBufferCapacity = 128)
    val events: Flow<ClusterEvent> = _events.asSharedFlow()

    init {
        // #190：把工具执行能力注入网关 —— 这是角色能真正动手的唯一通路。
        // 在这里做而不是让网关自己拿 registry：网关不该知道谁注册了什么、谁有权执行。
        (gateway as? DefaultLlmGateway)?.toolExecutor = { call -> execTool(call) }
    }

    /**
     * 执行一个工具调用，返回给模型看的文本。
     *
     * 复用宿主注册表（QuroToolRegistry.active），所以角色能调的是**ZorvAI 的全量工具** ——
     * 文件、终端、网络、无障碍（操作手机）、UI 卡片、GenUI、插件…都在内，
     * 具体给哪个角色下发哪些，由 ClusterSkillRuntime.toolsFor 按分工裁剪。
     *
     * `skill__xxx` 已在 #200 移除：它回灌的是全局库正文，与集群技能隔离直接冲突。
     * 所有异常都转成失败说明返回 —— 抛出去会中断整个 ReAct 循环，
     * 而模型看到失败原因才有机会换路径。
     */
    private suspend fun execTool(call: com.ai.assistance.quro.core.QuroToolCall): String {
        val name = call.name
        // 🔴 #200：集群角色拿不到 `skill__xxx`（toolsFor 已硬过滤），
        // 且集群技能本来就没有 function-calling 形态。这里不再回灌全局库正文——
        // 那等于绕过集群技能体系直接抽全局规程，隔离就是个空壳。
        // 保留这个分支只为了如实回复：模型若猜测出这个工具名，必须告诉它真实可用能力。
        if (name.startsWith("skill__")) {
            return "集群不支持 skill__ 形式激活技能（那是宿主全局技能库的机制，与集群技能库隔离）。" +
                "你的技能规程正文已经注入系统提示词，直接按它执行即可；" +
                "若提示词里确实没有这门手艺，如实说明缺什么，不要假装已激活。"
        }

        val registry = com.ai.assistance.quro.core.tools.QuroToolRegistry.active
            ?: return "工具注册表未就绪（应用可能刚启动，稍后重试）"
        val tool = registry.get(name) ?: return "未知工具: " + name
        // 危险权限前置申请：与主 Agent 同一套机制（QuroPermissionHolder）
        val perms = tool.requiredPermissions
        if (perms.isNotEmpty() &&
            !com.ai.assistance.quro.core.tools.QuroPermissionHolder.isGranted(context, perms)
        ) {
            val requester = com.ai.assistance.quro.core.tools.QuroPermissionHolder.requester
            if (requester != null) runCatching { requester.ensure(perms) }
            if (!com.ai.assistance.quro.core.tools.QuroPermissionHolder.isGranted(context, perms)) {
                return name + " 需要用户授权但未获批准：" + perms.joinToString(", ")
            }
        }
        // 危险工具硬超时：60s，防止一个工具卡住吃满整个任务预算
        return withTimeoutOrNull(60_000L) {
            tool.run(context, call.arguments)
        } ?: name + " 执行超时（超过 60 秒仍未返回）"
    }

    private val tasks = LinkedHashMap<String, ClusterTask>()
    private val spend = HashMap<String, Int>()          // personaId|taskId → tokens
    private val profiles = HashMap<String, Pair<Int, Int>>()  // personaId → (成功, 失败)

    @Volatile private var paused = false
    @Volatile private var aborted = false

    fun pause() { paused = true }
    fun resume() { paused = false }
    fun abort() { aborted = true }

    fun task(id: String) = tasks[id]

    // ——————————————— 任务入口 ———————————————

    suspend fun submit(cluster: ClusterConfig, goal: String): ClusterTask {
        RoleRegistry.ensureHost(context)
        val t = ClusterTask(
            id = "ct_${System.currentTimeMillis()}",
            clusterId = cluster.id, goal = goal,
            acceptance = cluster.acceptance
        )
        tasks[t.id] = t
        emit(ClusterEvent.Started(t.id, goal))
        return t
    }

    /** 驱动到闭环。阻塞式，宿主在自己的协程里调用。 */
    suspend fun drive(cluster: ClusterConfig, t: ClusterTask): CloseReason {
        emit(ClusterEvent.Started(t.id, t.goal))
        var guard = 0
        while (t.state != ClusterTaskState.CLOSED && t.state != ClusterTaskState.ESCALATED) {
            if (aborted) return close(t, CloseReason.USER_ABORTED, "用户中止")
            while (paused) kotlinx.coroutines.delay(300)

            // 终止检查优先于一切：主持不能无限循环
            checkTermination(cluster, t)?.let { reason ->
                return close(t, reason, "触发熔断：${t.state}")
            }
            if (++guard > 5_000) return close(t, CloseReason.CIRCUIT_BROKEN, "步骤数超限")

            val before = snapshot(t)
            step(cluster, t)
            t.idleStreak = if (snapshot(t) != before) 0 else t.idleStreak + 1
        }
        return t.closeReason ?: CloseReason.USER_ABORTED
    }

    private fun snapshot(t: ClusterTask) =
        t.nodes.joinToString { "${it.id}:${it.state}" } + "|${t.turnCount}"

    private suspend fun step(cluster: ClusterConfig, t: ClusterTask) {
        when (t.state) {
            ClusterTaskState.IDLE -> t.state = ClusterTaskState.INTAKE
            ClusterTaskState.INTAKE -> intake(cluster, t)
            // #192：拆完子任务先核对能力覆盖，缺就去补，补不到如实标记。
            // 这一步夹在 INTAKE 与 DISPATCHING 之间，是"不凭空想象"的机制落点。
            ClusterTaskState.CAPABILITY -> capability(cluster, t)
            ClusterTaskState.DISPATCHING -> dispatching(cluster, t)
            ClusterTaskState.PROPOSING -> proposing(cluster, t)
            ClusterTaskState.ARBITRATING -> arbitrating(cluster, t)
            ClusterTaskState.EXECUTING -> executing(cluster, t)
            ClusterTaskState.VERIFYING -> verifying(cluster, t)
            ClusterTaskState.REPLANNING -> replanning(cluster, t)
            ClusterTaskState.CONVERGING -> converging(cluster, t)
            ClusterTaskState.CLOSED, ClusterTaskState.ESCALATED -> Unit
        }
    }

    // ——————————————— 阶段 ———————————————

    /** INTAKE：主持先定"什么算做完"，没有验收标准不许开工 */
    private suspend fun intake(cluster: ClusterConfig, t: ClusterTask) {
        val prompt = """
用户目标：${t.goal}

请完成三件事：
1. 定义验收标准：3-6 条可判定的条目（不是模糊描述）；
2. 把目标拆成 2~6 个子任务，标明顺序依赖；
3. **为每个子任务声明它需要什么能力**（ability 字段）。

第3 条是硬要求：系统会拿它去核对"到底有没有角色真具备这项能力"，
没有就会先去装技能或造角色。你不声明，系统只能靠标题瞎猜，
就会把活派给不具备能力的角色，让它凭空想象。

ability 怎么写：写**手艺名词**，不要写句子。
好：「视觉设计」「数据统计」「文案写作」「代码审查」「设备操作」
坏：「帮我把页面做得好看一点」（这是句子不是能力，匹配不到任何技能）

只输出 JSON，不要任何额外文字：
{"acceptance":["...","..."],"nodes":[{"id":"n1","title":"...","instruction":"...","ability":"...","dependsOn":[]}]}
""".trimIndent()

        val out = callHost(cluster, t, prompt) ?: run {
            t.state = ClusterTaskState.ESCALATED; return
        }
        val parsed = firstJson(out.text) ?: run {
            // 主持没吐出合法 JSON：退化为单节点，仍要保证任务可推进
            t.acceptance = listOf("完成用户目标：${t.goal}")
            t.nodes += ClusterNode(id = "n1", title = t.goal, instruction = t.goal)
            t.state = ClusterTaskState.CAPABILITY
            return
        }
        t.acceptance = parsed.optJSONArray("acceptance")?.let { a ->
            (0 until a.length()).map { a.optString(it) }
        } ?: listOf("完成用户目标：${t.goal}")

        t.nodes.clear()
        parsed.optJSONArray("nodes")?.let { arr ->
            (0 until arr.length()).mapNotNull { runCatching { ClusterNode.fromJson(arr.getJSONObject(it)) }.getOrNull() }
                .forEach { t.nodes += it }
        }
        if (t.nodes.isEmpty()) t.nodes += ClusterNode(id = "n1", title = t.goal, instruction = t.goal)

        emit(ClusterEvent.AcceptanceDefined(t.id, t.acceptance))
        emit(ClusterEvent.Planned(t.id, t.nodes.size))
        // #192：不再直接派活，先过能力核对
        t.state = ClusterTaskState.CAPABILITY
    }

    /**
     * ★ CAPABILITY 阶段 ★（#192，文档 V2 的核心新增）
     *
     * 用户原话：「没有相关的 skills 能力怎么执行，凭空想象」。
     * 这一步就是那句问题的答案 —— **不是让模型自己想办法，是开派活之前先把账算清��**：
     *
     * ```
     * 1. audit()    —— 每个子任务需要什么能力，现有角色里有没有人真具备
     *                 （只认技能库里真实存在的技能，不认角色自称会什么）
     * 2. remedy()   —— 缺的按三档补：本地技能直接绑 → 开源社区装 → 造新角色
     * 3. 兜底       —— 补不到就把该节点标 SKIPPED 并写明缺什么，
     *                 **绝不装作"有人能做"**
     * ```
     *
     * 为什么补不到要跳过而不是硬派：硬派出去的结果是模型按通用知识编一份东西交上来，
     * 评审还可能看不出来 —— 那正是"凭空想象"的完整闭环。
     * 明确跳过，用户至少知道这件事没做。
     *
     * 为什么用 [ClusterCapability] 而不是让主持自己判断：
     * 主持也是模型，它说"这个角色应该能做"和"这个角色真能做"在文本里长得一样。
     * 只有拿技能库比对出来的结果才是可验证的。
     */
    private suspend fun capability(cluster: ClusterConfig, t: ClusterTask) {
        // 开关关闭 / 没有节点：直接放行，保持旧行为（离线且角色已配好的场景省一轮核对）
        if (!cluster.budget.capabilityCheck || t.nodes.isEmpty()) {
            t.state = ClusterTaskState.DISPATCHING
            return
        }

        val roles = RoleRegistry.experts(context)
        val covs = ClusterCapability.audit(context, t.nodes, roles)
        emit(ClusterEvent.CapabilityAudited(t.id, covs.count { it.covered }, covs.size))
        t.capabilityNotes.clear()
        t.capabilityNotes += ClusterCapability.summary(covs)

        val gaps = ClusterCapability.gaps(covs)
        if (gaps.isEmpty()) {
            t.state = ClusterTaskState.DISPATCHING
            return
        }

        var remedies = 0
        val unresolved = mutableListOf<ClusterCapability.Coverage>()
        for (gap in gaps) {
            if (remedies >= cluster.budget.maxRemedies) {
                // 预算用尽：剩下的如实标记，不静默放过
                unresolved += gap
                continue
            }
            val r = ClusterCapability.remedy(context, gap, RoleRegistry.experts(context), allowNetwork = true)
            remedies++
            emit(ClusterEvent.CapabilityRemedied(t.id, gap.ability, r.remedy.name, r.ok, r.detail))
            if (!r.ok) {
                t.capabilityNotes += "- ⚠ 补救失败（${gap.nodeTitle}）：${r.detail}"
                unresolved += gap
            } else {
                t.capabilityNotes += "- ✓ 已补救（${gap.nodeTitle}）：${r.detail}"
            }
        }

        // 补救过一轮之后重新核对 —— 补上的能力现在是真实覆盖了
        val after = ClusterCapability.audit(context, t.nodes, RoleRegistry.experts(context))
        val stillBad = after.filter { !it.covered }.map { it.nodeId }.toSet()

        // 补不到的节点处理 —— 但**不是**一律 SKIPPED。
        //
        // 🔴 判定器过严 / 角色压根没绑技能包，都会落到 stillBad 里。
        // 这时直接 SKIPPED 就是**误杀**：用户只看到「什么都没发生」，
        // 而实际上有角色声明过擅长、只是没装对应技能包。
        // 所以只有「补救失败 且 没人任何声明」才跳过；
        // 有声明的照常派活，但在 notes 里如实写明这活没有技能包支撑。
        (stillBad + unresolved.map { it.nodeId }).toSet().forEach { nid ->
            t.nodes.firstOrNull { it.id == nid }?.let { node ->
                val cov = after.firstOrNull { it.nodeId == nid }
                val why = cov?.ability ?: unresolved.firstOrNull { it.nodeId == nid }?.ability.orEmpty()
                if ((cov?.declared ?: 0f) > 0f) {
                    t.capabilityNotes += "- ⚠ 无技能包支撑（${node.title}）：" +
                        "已尝试补救未果，仍交由声明擅长该能力的角色执行「$why」"
                } else {
                    node.state = NodeState.SKIPPED
                    node.lastError = "无人具备所需能力「$why」，已跳过（不做凭空执行）"
                    t.capabilityNotes += "- ✗ 跳过（${node.title}）：${node.lastError}"
                }
            }
        }

        emit(ClusterEvent.Planned(t.id, t.nodes.count { it.state == NodeState.PENDING }))
        t.state = ClusterTaskState.DISPATCHING
    }

    /** DISPATCHING：挑一个依赖已满足的节点，并选人 */
    private suspend fun dispatching(cluster: ClusterConfig, t: ClusterTask) {
        val ready = t.ready()
        if (ready.isEmpty()) {
            t.state = if (t.finished()) ClusterTaskState.CONVERGING else ClusterTaskState.REPLANNING
            return
        }
        val node = ready.first()
        node.state = NodeState.READY

        val picks = rankRoles(t, node, cluster.budget.proposeFanout)
        if (picks.isEmpty()) {
            node.lastError = "没有可用角色承接该子任务（模型不可用或预算耗尽）"
            node.state = NodeState.FAILED
            t.state = ClusterTaskState.REPLANNING
            return
        }
        node.assignee = picks.first().personaId
        picks.forEach {
            emit(ClusterEvent.SpeakerSelected(t.id, it.personaId, it.reason))
        }
        t.state = ClusterTaskState.PROPOSING
    }

    /** PROPOSING：被点名的角色各自提方案，互相隔离（看不到彼此方案，避免从众幻觉） */
    private suspend fun proposing(cluster: ClusterConfig, t: ClusterTask) {
        val node = t.nodes.firstOrNull { it.state == NodeState.READY || it.state == NodeState.FAILED }
            ?: run { t.state = ClusterTaskState.DISPATCHING; return }
        node.state = NodeState.PROPOSING

        val picks = rankRoles(t, node, cluster.budget.proposeFanout).ifEmpty {
            listOf(Candidate(node.assignee ?: return@proposing run { t.state = ClusterTaskState.ESCALATED }, 0f, "兜底点名"))
        }

        val proposals = java.util.Collections.synchronizedList(ArrayList<Pair<String, String>>())
        if (cluster.budget.parallelPropose) {
            coroutineScope {
                picks.map { c ->
                    async {
                        proposeOne(cluster, t, node, c)?.let { proposals += it }
                    }
                }.awaitAll()
            }
        } else {
            picks.forEach { c -> proposeOne(cluster, t, node, c)?.let { proposals += it } }
        }

        if (proposals.isEmpty()) {
            node.lastError = "所有被点名角色都未能给出方案"
            node.attempt++
            t.replanCount++
            t.state = if (t.replanCount > cluster.budget.maxReplan) ClusterTaskState.ESCALATED else ClusterTaskState.REPLANNING
            return
        }
        node.artifact = proposals.joinToString("\n---\n") { "【${it.first}】${it.second}" }
        t.state = ClusterTaskState.ARBITRATING
    }

    private suspend fun proposeOne(
        cluster: ClusterConfig, t: ClusterTask, node: ClusterNode, c: Candidate
    ): Pair<String, String>? {
        val role = RoleRegistry.get(context, c.personaId) ?: return null
        val persona = personaOf(c.personaId) ?: return null
        val prompt = """
子任务「${node.title}」：${node.instruction}
${if (node.lastError != null) "⚠ 上一次失败：${node.lastError}，你必须换一种做法。" else ""}

请给出你的执行方案。要求具体、可执行、标明风险。
只输出 JSON：{"summary":"...","steps":["..."],"risks":["..."],"confidence":0.8}
""".trimIndent()

        val out = callRole(cluster, t, role, persona, prompt) ?: return null
        val display = persona.name
        emit(ClusterEvent.RoleUtterance(t.id, c.personaId, out.text))
        return display to out.text
    }

    /** ARBITRATING：主持裁决多份方案 → 唯一执行计划 */
    private suspend fun arbitrating(cluster: ClusterConfig, t: ClusterTask) {
        val node = t.nodes.firstOrNull { it.state == NodeState.PROPOSING }
            ?: run { t.state = ClusterTaskState.DISPATCHING; return }

        val prompt = """
子任务「${node.title}」已收到以下方案：
${node.artifact.orEmpty()}

请裁决：选出或合并为一个执行计划，说明理由。若全部不可行，pass=false 并说明缺什么。
只输出 JSON：{"pass":true,"reason":"...","steps":["..."],"chosen":"角色名"}
""".trimIndent()

        val out = callHost(cluster, t, prompt)
        if (out == null) { t.state = ClusterTaskState.ESCALATED; return }

        // 🔴 裁决走**三态**（见 ClusterVerdict）：裁决输出解析失败**不是**「裁决不通过」。
        // 旧码 `?: false` 让「主持只是 JSON 格式没对齐」被记成「方案不可行」，
        // 于是换打法重提方案 —— 但方案本身没变，重提一百遍还是同样的格式问题。
        // 用户实测的 14 轮 / 20 万 tokens 空转，这条就是主因之一。
        val v = ClusterVerdictProtocol.verdictOf(out.text)
        val o = firstJson(out.text)
        val pass = v == ClusterVerdictProtocol.ClusterVerdict.PASS
        val reason = o?.optString("reason", "").orEmpty()
        val effectiveReason = when (v) {
            ClusterVerdictProtocol.ClusterVerdict.PASS ->
                if (reason.isNotBlank()) reason else "主持裁决通过"
            ClusterVerdictProtocol.ClusterVerdict.FAIL ->
                reason.ifBlank { "主持裁决：方案不可行（未说明原因）" }
            ClusterVerdictProtocol.ClusterVerdict.UNPARSABLE ->
                // 降级：自己挑一份方案当执行计划，别把整个任务打回重做。
                // 多个方案里挑第一份，比让任务在同一个格式问题上死循环划算得多；
                // 真选错了，执行阶段的验收（VERIFYING）还会再兜一道。
                return fallbackArbitrate(cluster, t, node, out.text)
        }
        emit(ClusterEvent.Verdict(t.id, node.id, pass, effectiveReason, "主持"))

        if (!pass) {
            node.lastError = "主持否决：$effectiveReason"
            node.attempt++
            t.replanCount++
            t.state = if (t.replanCount > cluster.budget.maxReplan) ClusterTaskState.ESCALATED else ClusterTaskState.REPLANNING
            return
        }
        val steps = o?.optJSONArray("steps")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        node.artifact = steps.joinToString("\n")
        node.state = NodeState.EXECUTING
        t.state = ClusterTaskState.EXECUTING
    }

    /**
     * 裁决输出无法解析时的降级：直接从已有方案文本里挑一份当执行计划。
     *
     * ## 为什么不重规划
     *
     * 重规划的前提是「方案本身不可行」。而解析失败只说明主持的输出格式没对齐，
     * 方案原文（`node.artifact`）通常**完全可读**。为一次格式问题打回整个任务，
     * 代价是又一轮拆解 + 又一轮方案征集，且新方案还会以同样概率解析失败。
     *
     * 降级安全性：挑的是**已被收到的方案之一**，不是凭空编一个；
     * 若连一份方案都挑不出来（artifact 为空），才老实走 FAILED。
     */
    private suspend fun fallbackArbitrate(
        cluster: ClusterConfig,
        t: ClusterTask,
        node: ClusterNode,
        raw: String,
    ) {
        // 优先从原始输出里捞 JSON 片段（宽松修复后多半能捞到 steps）
        val salvaged = runCatching { firstJson(raw)?.optJSONArray("steps") }.getOrNull()
        val steps = salvaged?.let { a ->
            (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
        }.orEmpty().ifEmpty {
            // 捞不到就退回方案原文（原文是模型刚写的，一定可读）
            node.artifact.orEmpty()
                .lineSequence()
                .map { it.trim().removePrefix("- ").removePrefix("* ").trim() }
                .filter { it.length >= 4 }
                .take(12)
                .toList()
        }
        if (steps.isEmpty()) {
            node.lastError = "主持裁决无法解析，且没有可用的备选方案"
            node.state = NodeState.FAILED
            node.attempt++
            t.replanCount++
            emit(ClusterEvent.Error(t.id, node.assignee, node.lastError!!))
            t.state = if (t.replanCount > cluster.budget.maxReplan) {
                ClusterTaskState.ESCALATED
            } else {
                ClusterTaskState.REPLANNING
            }
            return
        }
        emit(ClusterEvent.Verdict(
            t.id, node.id, true,
            "主持裁决输出无法解析（已尝试修复），已降级采用其中的执行计划。" +
                "这是**降级采用**不是裁决通过，后续验收仍会兜一道。",
            "主持"
        ))
        node.artifact = steps.joinToString("\n")
        node.state = NodeState.EXECUTING
        t.state = ClusterTaskState.EXECUTING
    }

    /** EXECUTING：执行角色按计划产出成果 */
    private suspend fun executing(cluster: ClusterConfig, t: ClusterTask) {
        val node = t.nodes.firstOrNull { it.state == NodeState.EXECUTING }
            ?: run { t.state = ClusterTaskState.DISPATCHING; return }

        val role = RoleRegistry.get(context, node.assignee ?: "")
            ?: rankRoles(t, node, 1).firstOrNull()?.let { RoleRegistry.get(context, it.personaId) }
        if (role == null) { node.lastError = "执行角色缺失"; node.state = NodeState.FAILED; t.state = ClusterTaskState.REPLANNING; return }
        val persona = personaOf(role.personaId) ?: run {
            node.lastError = "人格卡缺失"; node.state = NodeState.FAILED; t.state = ClusterTaskState.REPLANNING; return
        }

        val prompt = """
请按以下执行计划完成子任务「${node.title}」，直接产出成果内容（不要只说"我会怎么做"）。
执行计划：
${node.artifact.orEmpty()}

子任务说明：${node.instruction}
验收标准：
${t.acceptance.joinToString("\n") { "- $it" }}
""".trimIndent()

        val out = callRole(cluster, t, role, persona, prompt)
        if (out == null) {
            node.lastError = "执行失败"
            node.attempt++
            t.replanCount++
            emit(ClusterEvent.Error(t.id, role.personaId, "执行失败"))
            t.state = if (t.replanCount > cluster.budget.maxReplan) ClusterTaskState.ESCALATED else ClusterTaskState.REPLANNING
            return
        }
        node.artifact = out.text
        // 🔴 带上正文：只有标题的话对话框里就是一条空气泡（用户报「输出不是正常文本内容和产物」）
        emit(ClusterEvent.ArtifactProduced(t.id, node.id, role.personaId, node.title, out.text))
        record(role.personaId, true)
        t.state = ClusterTaskState.VERIFYING
    }

    /** VERIFYING：验收。不过 → 携带失败原因回重规划（异常驱动重提方案） */
    private suspend fun verifying(cluster: ClusterConfig, t: ClusterTask) {
        val node = t.nodes.firstOrNull { it.state == NodeState.EXECUTING }
            ?: run { t.state = ClusterTaskState.DISPATCHING; return }

        // 优先挑评审角色；没有 CRITIC 就让主持代验（下面会给主持另一套更严的 prompt）。
        // 排除 node.assignee：执行者审自己的活等于没审。
        val critic = RoleRegistry.roles(context).firstOrNull {
            it.role == RoleKind.CRITIC && it.enabled && it.personaId != node.assignee
        }
        // #190 验收 prompt 重写：
        //  ① 要求**逐条**判定并回填每条结论（原来只给一个总 pass，无法定位）；
        //  ② 明确「缺证据 = 不通过」，堵住模型为了推进而放水；
        //  ③ 要求不通过理由必须可执行（要指出怎么改），否则重规划拿不到有用信息。
        val criticSide = critic != null
        val prompt = buildString {
            appendLine(if (criticSide) "你是验收方。" else "你兼任验收方（集群里没有专职评审）。")
            appendLine()
            appendLine("验收标准（逐条判定，不允许整体泛判）：")
            t.acceptance.forEachIndexed { i, a -> appendLine("  " + (i + 1) + ". " + a) }
            appendLine()
            appendLine("待验收产物：")
            appendLine(node.artifact?.take(8000).orEmpty().ifBlank { "(空)" })
            appendLine()
            appendLine("判定规则：")
            appendLine("- 每条标准单独给结论，任一条不满足 → pass=false。")
            appendLine("- **找不到证据 = 不通过**。不要因为「看起来应该做了」就放行。")
            appendLine("- 不通过时 failed 里逐条写：哪条不满足 + 具体该怎么改（要能直接指导重做）。")
            appendLine()
            appendLine("只输出 JSON，不要任何额外文字：")
            appendLine("{\"pass\":true/false,\"reason\":\"总体结论\",\"checks\":[{\"index\":1,\"pass\":true,\"note\":\"\"}],\"failed\":[\"\"]}")
        }

        val out = if (critic != null) {
            personaOf(critic.personaId)?.let { callRole(cluster, t, critic, it, prompt) }
        } else {
            callHost(cluster, t, prompt)
        }

        val o = out?.text?.let { firstJson(it) }
        // 🔴 判定默认必须是**不通过**（旧码 `?: true` 会让「一个字都没产出的东西」
        // 静默通过一路走到 CLOSED —— 那是把验收闸门拆了，不是容错）。
        //
        // 🔴 但 2026-10-08 把「解析失败」从「不通过」里拆了出来（见 ClusterVerdict）：
        // 验收方输出解析不出 JSON，说明的是**协议没对上**，不是「产物不合格」。
        // 拿它去驱动重做，等于要求执行者重做一个**根本没被判定过的**产物 ——
        // 而产物字节没变，重做 100 次还是同样的解析失败。这就是用户实测里
        // 「⚠ 验收未通过：验收方未给出有效结论」+「❌ 裁决不通过：验收方未给出有效结论」
        // 两条同时出现、14 轮空转的直接原因。
        val v = ClusterVerdictProtocol.verdictOf(out?.text)
        val pass = v == ClusterVerdictProtocol.ClusterVerdict.PASS
        // 验收方身份：评审角色，还是主持代验？（用户直接问过「关键是谁验收？」）
        val verifierName = critic?.let { c ->
            runCatching { personaOf(c.personaId)?.name }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: c.personaId
        } ?: "主持（集群里没有专职评审，主持代验）"
        if (v == ClusterVerdictProtocol.ClusterVerdict.UNPARSABLE) {
            // 协议失败：先换 prompt 重试一次（把格式要求再强调一遍）。
            if (node.attempt < cluster.budget.maxNodeAttempts) {
                node.attempt++
                emit(ClusterEvent.Error(
                    t.id, node.assignee,
                    "验收方（$verifierName）输出无法解析，正在重试第 ${node.attempt} 次"
                ))
                // 不计 replanCount：这不是「方案不对」，重做没有意义。
                t.state = ClusterTaskState.VERIFYING
                return
            }
            // 重试用尽：降级放行，但**如实记账**——
            // 绝不能悄悄当通过（那是编造），也不能因此作废已产出的合格产物。
            emit(ClusterEvent.Verdict(
                t.id, node.id, true,
                "验收方（$verifierName）的输出连续 ${node.attempt} 次无法解析，" +
                    "无法给出有效结论。本次按**未能判定**处理，放行产物由你复核；" +
                    "这不是「验收通过」。",
                verifierName
            ))
            node.state = NodeState.DONE
            t.state = if (t.finished()) ClusterTaskState.CONVERGING else ClusterTaskState.DISPATCHING
            return
        }
        val reason = o?.optString("reason", "").orEmpty()
        val failedItems = o?.optJSONArray("failed")?.let { arr ->
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
        } ?: emptyList()
        // 逐条结论：验收标准里第 k 条没过，要能指出是哪条
        val checkNotes = o?.optJSONArray("checks")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                runCatching {
                    val c = arr.getJSONObject(i)
                    val idx = c.optInt("index", 0)
                    val ok = c.optBoolean("pass", false)
                    val note = c.optString("note", "")
                    (idx to ok) to note
                }.getOrNull()
            }
        } ?: emptyList()
        val unmet = checkNotes.filter { !it.first.second }
        val effectiveReason = buildString {
            if (reason.isNotBlank()) appendLine(reason)
            if (unmet.isNotEmpty()) {
                append("未达标条目：")
                unmet.forEach { (idxOk, note) ->
                    val n = idxOk.first
                    val std = t.acceptance.getOrNull(n - 1) ?: ("第" + n + "条")
                    append("\n· " + std + if (note.isNotBlank()) " → " + note else "")
                }
            }
            if (failedItems.isNotEmpty()) append("\n· " + failedItems.joinToString("\n· "))
        // 🔴 不再拼「验收方未给出有效结论（输出无法解析）」这句——
        // 解析失败已在上游分流到 UNPARSABLE 分支，走不到这里。
            if (isBlank()) append("验收方未给出有效结论")
        }.trim()
        emit(ClusterEvent.Verdict(t.id, node.id, pass, effectiveReason, verifierName))

        if (pass) {
            node.state = NodeState.DONE
            t.state = if (t.finished()) ClusterTaskState.CONVERGING else ClusterTaskState.DISPATCHING
        } else {
            node.lastError = "验收未通过：" + effectiveReason
            node.attempt++
            node.state = NodeState.FAILED
            node.assignee?.let { record(it, false) }
            t.replanCount++
            emit(ClusterEvent.Error(t.id, node.assignee, node.lastError!!))
            t.state = if (t.replanCount > cluster.budget.maxReplan) ClusterTaskState.ESCALATED else ClusterTaskState.REPLANNING
        }
    }

    /** REPLANNING：主持带着失败原因换打法（不是简单重试） */
    private suspend fun replanning(cluster: ClusterConfig, t: ClusterTask) {
        val failed = t.nodes.filter { it.state == NodeState.FAILED }
        failed.forEach { n ->
            if (n.attempt >= cluster.budget.maxNodeAttempts) {
                n.state = NodeState.SKIPPED      // 连败到上限就跳过，防局部死锁拖垮全局
                emit(ClusterEvent.Error(t.id, n.assignee, "节点「${n.title}」连续失败${n.attempt}次，已跳过"))
            } else {
                n.state = NodeState.PENDING
            }
        }
        val stillFailed = failed.filter { it.state == NodeState.FAILED }
        if (stillFailed.isNotEmpty()) {
            val prompt = """
以下子任务失败了，不要重复同样的做法，给出新的打法（换角色 / 拆更细 / 降低粒度）：
${stillFailed.joinToString("\n") { "- ${it.title}：${it.lastError}" }}

只输出 JSON：{"pass":true,"reason":"新方案说明","steps":["..."]}
""".trimIndent()
            val out = callHost(cluster, t, prompt)
            emit(ClusterEvent.Replanned(t.id, t.replanCount, out?.text?.take(200) ?: "主持未能给出新方案"))
        }
        t.state = if (t.finished()) ClusterTaskState.CONVERGING else ClusterTaskState.DISPATCHING
    }

    private suspend fun converging(cluster: ClusterConfig, t: ClusterTask) {
        // 🔴 #198 假闭环拦截（第一道）：一个节点都没真产出时，
        // **不许**去问主持要总结 —— 问了它也会给你一段漂亮的总结，
        // 因为「什么都没干」与「干得很好」在模型嘴里长得一模一样。
        // 这是实测踩过的坑：tokens 只花 384、5 个节点全 SKIPPED，主持却报「圆满达成」。
        if (t.noProgress()) {
            val why = t.nodes.mapNotNull { n ->
                val cause = n.lastError ?: when (n.state) {
                    NodeState.SKIPPED -> "被跳过：无人具备所需能力"
                    NodeState.FAILED -> "失败"
                    else -> null
                }
                cause?.let { "- ${n.title}：$it" }
            }.ifEmpty { listOf("- 未知原因") }
            val summary = buildString {
                appendLine("⚠ 本次任务没有产出任何结果（${t.nodes.size} 个子任务全部未完成）。")
                appendLine()
                appendLine("逐项原因：")
                appendLine(why.joinToString("; "))
                appendLine()
                append("这不是「圆满达成」。请按上面的原因补齐能力/角色后重试。")
            }
            close(t, CloseReason.NO_PROGRESS, summary)
            return
        }

        val prompt = """
有子任务被跳过/失败。请如实总结：哪些真做出来了、哪些没有、没做出来的是什么原因。
不要把跳过的部分说成已完成。
只输出 JSON：{"summary":"..."}
""".trimIndent()
        val out = callHost(cluster, t, prompt)
        val summary = out?.let { firstJson(it.text)?.optString("summary") } ?: "任务完成"

        // 有产出但也有一部分被跳过 —— 如实点名，不许把跳过的算进成果。
        val skippedTitles = t.nodes.filter { it.state == NodeState.SKIPPED }.map { it.title }
        val finalSummary = if (skippedTitles.isEmpty()) summary
        else summary + "\n\n⚠ 以下子任务未完成，不要算作成果：" + skippedTitles.joinToString("、")

        close(t, CloseReason.GOAL_REACHED, finalSummary)
    }

    // ——————————————— 熔断与收尾 ———————————————

    private suspend fun checkTermination(cluster: ClusterConfig, t: ClusterTask): CloseReason? {
        val b = cluster.budget
        return when {
            aborted -> CloseReason.USER_ABORTED
            t.turnCount >= b.maxTurns -> CloseReason.CIRCUIT_BROKEN
            t.replanCount > b.maxReplan -> CloseReason.CIRCUIT_BROKEN
            t.idleStreak >= b.noProgressWindow -> CloseReason.CIRCUIT_BROKEN
            System.currentTimeMillis() - t.createdAt > 30 * 60 * 1000L -> CloseReason.CIRCUIT_BROKEN
            else -> null
        }
    }

    private suspend fun close(t: ClusterTask, reason: CloseReason, summary: String): CloseReason {
        // 🔴 #198 假闭环拦截（第二道 / defense in depth）：
        // 任何地方想报 GOAL_REACHED 时，这里再核一次「真的产出东西了吗」。
        // converging() 已经拦过一次，但那是单一调用点 —— 将来谁在别处
        // 新增一个 close(t, GOAL_REACHED, ...) 就会重犯同一个错。
        // 判定收敛在这里，规则就只有一处。
        val finalReason = if (reason == CloseReason.GOAL_REACHED && t.noProgress()) {
            CloseReason.NO_PROGRESS
        } else {
            reason
        }
        val finalSummary = if (finalReason == CloseReason.NO_PROGRESS && reason == CloseReason.GOAL_REACHED) {
            "本次任务没有产出任何结果：" + t.nodes.joinToString("；") {
                "${it.title}=" + (it.lastError ?: it.state.name)
            }
        } else {
            summary
        }

        t.state = if (finalReason == CloseReason.GOAL_REACHED) ClusterTaskState.CLOSED else ClusterTaskState.ESCALATED
        t.closeReason = finalReason
        t.summary = finalSummary
        emit(ClusterEvent.Closed(t.id, finalReason, finalSummary))
        return finalReason
    }

    // ——————————————— 调用封装 ———————————————

    /** 主持发言：用它自己的模型，便宜快模型即可 */
    private suspend fun callHost(cluster: ClusterConfig, t: ClusterTask, prompt: String): LlmOutcome? {
        val host = RoleRegistry.host(context)
        val model = resolveModel(host, fallbackToAny = true) ?: run {
            emit(ClusterEvent.Error(t.id, RoleRegistry.HOST_PERSONA_ID, "宿主没有任何可用模型"))
            return null
        }
        val persona = personaOf(RoleRegistry.HOST_PERSONA_ID)
        val sys = buildRoleSystem(RoleRegistry.HOST_OATH, host, null)
        val out = runCatching {
            gateway.complete(
                model = model.first,
                systemPrompt = sys,
                userPrompt = prompt,
                temperature = host.context.temperature,
                maxTokens = host.context.maxTokens,
                jsonMode = true
            )
        }.getOrElse {
            emit(ClusterEvent.Error(t.id, RoleRegistry.HOST_PERSONA_ID, it.message ?: "主持调用失败"))
            return null
        }
        t.turnCount++
        t.tokenUsed += out.tokens
        if (model.second) {
            emit(ClusterEvent.ModelSwitched(t.id, RoleRegistry.HOST_PERSONA_ID, host.modelProfileId, out.modelId, "主持模型不可用，已兜底"))
        }
        return out
    }

    /** 角色发言：用它自己绑定的模型 —— 这就是"每个角色一条独立请求"的落点 */
    private suspend fun callRole(
        cluster: ClusterConfig, t: ClusterTask,
        role: RoleProfile, persona: com.ai.assistance.quro.core.QuroPersona,
        prompt: String
    ): LlmOutcome? {
        val used = spend["${role.personaId}|${t.id}"] ?: 0
        if (used >= cluster.budget.maxTokensPerRole) {
            emit(ClusterEvent.Error(t.id, role.personaId, "该角色本任务 token 预算耗尽"))
            return null
        }
        val model = resolveModel(role, fallbackToAny = true) ?: return null
        val skills = ClusterSkillRuntime.resolveSkills(context, role)
        val tools = toolsForRole(role)
        val toolTrace = java.util.Collections.synchronizedList(ArrayList<Pair<String, String>>())
        val sys = buildRoleSystem(persona.roleSetting, role, persona, skills, tools)
        val out = runCatching {
            if (tools.isEmpty()) {
                // 没有工具就不浪费 token 发空 tools 字段（部分代理会因此报错）
                gateway.complete(
                    model = model.first,
                    systemPrompt = sys,
                    userPrompt = prompt,
                    temperature = role.context.temperature,
                    maxTokens = role.context.maxTokens
                )
            } else {
                // #190 关键改动：走 ReAct，角色能真正调工具执行，而不是只输出文本。
                gateway.withTools(
                    model = model.first,
                    systemPrompt = sys,
                    userPrompt = prompt,
                    tools = tools,
                    maxRounds = cluster.budget.reactRounds,
                    temperature = role.context.temperature,
                    maxTokens = role.context.maxTokens,
                    // 🔴 onToolCall 是**非挂起** lambda，不能在里面调挂起的 emit。
                    // 所以先收集轨迹，withTools 返回后在协程里统一发事件 ——
                    // 顺带保证事件顺序与实际执行顺序一致。
                    onToolCall = { toolName, summary -> toolTrace += toolName to summary }
                )
            }
        }.getOrElse {
            emit(ClusterEvent.Error(t.id, role.personaId, it.message ?: "角色调用失败"))
            return null
        }
        // 工具执行轨迹进事件流：用户在对话框能看到「它真的动手了」。
        // 没这层，ReAct 跑完只剩一段文本，"模型说自己做了" 和 "真做了" 无法区分。
        toolTrace.forEach { (toolName, summary) ->
            emit(ClusterEvent.ToolInvoked(t.id, role.personaId, toolName, summary))
        }
        t.turnCount++
        t.tokenUsed += out.tokens
        spend["${role.personaId}|${t.id}"] = used + out.tokens
        if (model.second) {
            emit(ClusterEvent.ModelSwitched(t.id, role.personaId, role.modelProfileId, out.modelId, model.first.displayName))
        }
        return out
    }

    /**
     * 算出该角色这一轮真正能用的工具。
     * 注册表可能还没就绪（应用刚启动），拿不到就返回空 —— 角色降级为纯文本，
     * **绝不**因此让整个任务失败。
     */
    private fun toolsForRole(role: RoleProfile): List<com.ai.assistance.quro.core.QuroToolSpec> {
        val all = runCatching {
            com.ai.assistance.quro.core.tools.QuroToolRegistry.active?.fullSpecs()
        }.getOrNull() ?: return emptyList()
        return runCatching { ClusterSkillRuntime.toolsFor(context, role, all) }
            .getOrElse { emptyList() }
    }

    /** 装配该角色专属的 system prompt —— 每个角色看到的都不一样 */
    private fun buildRoleSystem(
        base: String, role: RoleProfile, persona: com.ai.assistance.quro.core.QuroPersona?,
        skills: ClusterSkillRuntime.ResolvedSkills = ClusterSkillRuntime.ResolvedSkills(emptyList(), emptyList(), emptyList()),
        tools: List<com.ai.assistance.quro.core.QuroToolSpec> = emptyList(),
    ): String = buildString {
        appendLine(base)
        if (persona != null) {
            appendLine()
            appendLine("# 你的身份")
            appendLine("你是「${persona.name}」。${persona.description}")
            if (persona.chatSetting.isNotBlank()) {
                appendLine(); appendLine("# 表达约束"); appendLine(persona.chatSetting)
            }
        }
        if (role.duties.isNotEmpty()) {
            appendLine(); appendLine("# 工作内容")
            role.duties.forEach { appendLine("- $it") }
        }
        if (role.taboos.isNotEmpty()) {
            appendLine(); appendLine("# 禁止事项（违反即失败）")
            role.taboos.forEach { appendLine("- $it") }
        }
        if (role.skills.isNotEmpty()) {
            appendLine(); appendLine("# 你的专业背景")
            role.skills.forEach { appendLine("- $it") }
        }

        // 🔴 #190 技能正文真正注入。
        // 原来这里只有上面那几行「- 懂 HTML」式的标签，模型读完只能按通用知识凭空发挥
        // （用户原话：「没有相关的 skills 能力怎么执行，凭空想象」）。
        // 现在把技能库里的**规则正文**整段灌进来，它才有可依据的手艺。
        skills.bodies.forEach { body ->
            appendLine()
            appendLine("# 技能手册（你必须遵守）")
            appendLine(body)
        }

        appendLine()
        appendLine(ClusterSkillRuntime.capabilityAwareness(context, role, tools, skills.skills))
        appendLine()
        appendLine("# 输出纪律")
        appendLine("- 只做与你职责相关的事，不越权替其他角色做决定。")
        appendLine("- 不确定就说不确定，禁止编造数据、来源、文件名。")
        appendLine("- 需要协作时写清「建议由谁做什么」，由主持调度。")
        // 评审角色的纪律与生产者相反：它必须敢于否决。
        if (role.role.isVerifier) {
            appendLine("- 你是验收方。**不通过就明确说不通过**，不要为了推进而放水。")
            appendLine("- 每条不满足的验收标准都要单独列出，不通过理由必须可执行（指出该怎么改）。")
        } else {
            appendLine("- 动手执行，不要只描述你「将会怎么做」。")
        }
    }.trim()

    /**
     * 解析角色绑定的模型，必要时走降级链。
     * @return (实际模型, 是否发生了切换)
     */
    private fun resolveModel(role: RoleProfile, fallbackToAny: Boolean): Pair<ModelProfile, Boolean>? {
        val chain = listOf(role.modelProfileId) + role.fallbackModelIds
        chain.filter { it.isNotBlank() }.forEachIndexed { i, id ->
            val m = modelSource.get(id)
            if (m != null && m.available) return m to (i > 0)
        }
        if (!fallbackToAny) return null
        return modelSource.listModels().firstOrNull { it.available }?.let { it to true }
    }

    // ——————————————— 选人 ———————————————

    data class Candidate(val personaId: String, val score: Float, val reason: String)

    /**
     * #192：选人打分改成文档 V2 的权重口径
     * **能力覆盖 0.45 + 技能命中 0.25 + 历史成功率 0.20 + 预算余量 0.10**
     *
     * #192 之前这里是「职责关键词重合 0.5 + 技能命中 0.2 + ...」——
     * 差别很实质：旧口径按 duties 文本匹配，而 duties 是**角色自己写的提示词文本**，
     * 谁都能声称"我懂 Java"。新口径的第一权重项直接走 [ClusterCapability.scoreRole]，
     * 只认技能库里**真实存在的技能**（name/description/trigger 命中）。
     *
     * 自动排除：主持（除非只有它）、停用、模型不可用、预算耗尽。
     */
    private fun rankRoles(t: ClusterTask, node: ClusterNode, topK: Int): List<Candidate> {
        val query = (node.title + " " + node.instruction).lowercase()
        val terms = query.split(Regex("[\\s,，。、；;：:（）()\\[\\]]+"))
            .filter { it.length >= 2 }.distinct().take(24)
        // 预算上限与 ClusterBudget.maxTokensPerRole 同值（#192 之前这里是写死的 120_000，
        // 调预算时它不跟着变，导致 callRole 按新预算放行、选人却按旧预算淘汰，两处口径不一致）
        val budget = ClusterBudget().maxTokensPerRole.toFloat()
        val skillLib = runCatching {
            // #196：派单排序也必须读集群库，否则 audit（集群库）与 rankRoles（全局库）
            // 会得出两套互相矛盾的能力结论，表现为「核对说有人能做、派单却派给别人」。
            ClusterSkillStore.load(context).associateBy { it.id }
        }.getOrDefault(emptyMap())
        // 能力需求优先用显式 ability；没有就退回标题+指令（与 audit 同一口径）
        val ability = node.ability.ifBlank { node.title + " " + node.instruction }

        return RoleRegistry.experts(context).asSequence()
            .filter { resolveModel(it, fallbackToAny = false) != null }
            .filter { (spend["${it.personaId}|${t.id}"] ?: 0) < budget }
            .map { r ->
                var s = 0f
                val reasons = ArrayList<String>()

                // ① 能力覆盖 70%（真实技能命中，不是自称）
                val cap = ClusterCapability.scoreRole(r, ability, skillLib)
                s += cap * 0.70f
                if (cap > 0f) {
                    reasons += "能力覆盖${(cap * 100).toInt()}%"
                    if (cap >= ClusterCapability.COVER_THRESHOLD) reasons += "达标"
                }

                // ② 技能命中 20%（职责文本与技能名双向匹配，作为能力分的补充信号）
                val text = (r.duties + r.skills).joinToString(" ").lowercase()
                val skillHit = r.skillIds.isNotEmpty() &&
                    (r.skills.any { sk -> terms.any { sk.contains(it, true) } } ||
                     terms.any { text.contains(it) })
                if (skillHit) { s += 0.20f; reasons += "技能命中" }

                // 🔴 #202 权重重排：与任务**无关**的信号合计从 30% 压到 10%。
                //
                // 实测（第 5 轮「双十一策划案」14 轮 20 万 tokens 空转的真因）：
                // 原权重 ①45% ②25% ③20% ④10%。但 ① 的分母是能力描述片段数，
                // 长指令下人人都是 0.5 上下（#202 已修长串吃短词），
                // 于是 ③ 历史成功率 + ④ 预算余量 这 30% 成了**决定名次**的主力。
                // 而「谁历史成功率高」「谁预算剩得多」跟「这个子任务该谁干」毫无关系——
                // 结果就是派单被这30% 主导，策划单派给了前端角色。
                //
                // 改为：能力覆盖 70%（主）+ 技能命中 20%（辅）= 90% 与任务相关，
                // 历史与预算合计仅作 10% 的**平手打破器**。
                // 让任务相关性成为决定性因素，这是派单该有的优先级。

                // ③ 历史成功率 6% + ④ 预算余量 4% = 10%（仅用于区分度不足时排序）
                val (ok, fail) = profiles[r.personaId] ?: (0 to 0)
                if (ok + fail > 0) {
                    s += (ok.toFloat() / (ok + fail)) * 0.06f
                    reasons += "成功率${(ok * 100 / (ok + fail))}%"
                }

                val left = 1f - ((spend["${r.personaId}|${t.id}"] ?: 0).toFloat() / budget)
                s += left.coerceIn(0f, 1f) * 0.04f

                if (r.personaId == node.assignee) { s += 0.5f; reasons += "节点指定" }
                Candidate(r.personaId, s, reasons.joinToString("，").ifBlank { "综合相关度" })
            }
            .sortedByDescending { it.score }
            .take(topK)
            .toList()
    }

    private fun record(personaId: String, success: Boolean) {
        if (RoleRegistry.isHost(personaId)) return
        val (ok, fail) = profiles[personaId] ?: (0 to 0)
        profiles[personaId] = (ok + if (success) 1 else 0) to (fail + if (success) 0 else 1)
    }

    private fun personaOf(id: String): com.ai.assistance.quro.core.QuroPersona? =
        runCatching { com.ai.assistance.quro.core.QuroPersonaRepository(context).get(id) }.getOrNull()

    private suspend fun emit(e: ClusterEvent) { _events.emit(e) }

    // ——————————————— JSON 兜底解析 ———————————————

    /** 三级兜底：整体解析 → 截取首个 {...} → 放弃。模型多说废话不会导致任务失败。 */
    private fun firstJson(raw: String): JSONObject? {
        val trimmed = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        runCatching { return JSONObject(trimmed) }
        // 🔴 宽松修复（2026-10-08 新增）：模型输出**格式**不合法不等于**内容**不合格。
        // 下面几类是端侧实测反复出现的形态，不是假想：
        //   - 单引号 `{'pass':true}`
        //   - 裸键名 `{pass:true}`（Python 风格）
        //   - 尾随逗号 `{"pass":true,}`
        //   - 全角引号/冒号 `｛"pass"：true｝`
        //   - 结尾缺闭合括号（被 max_tokens 截断）
        // 这些全都只差一层字符转换，重试一次 LLM 的成本远高于就地修好。
        runCatching { return JSONObject(ClusterVerdictProtocol.loosenJson(trimmed)) }
        val s = trimmed.indexOf('{')
        if (s < 0) return null
        var depth = 0
        for (i in s until trimmed.length) {
            when (trimmed[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return runCatching { JSONObject(trimmed.substring(s, i + 1)) }.getOrNull()
                }
            }
        }
        return null
    }

    /**
     * 把「只差几层字符」的 JSON 修成合法 JSON。**只做无损的结构性修复**，
     * 绝不改语义、绝不猜值 —— 修不好就返回原串让上层判失败。
     *
     * ## 为什么要有它（本仓既有铁律的延伸）
     *
     * 模型输出格式不对**不等于**工作没做到。旧实现里格式失败直接落进
     * `pass = false`，于是「模型只是忘了写逗号」被记成「产物不合格」，
     * 触发一整轮重规划 —— 14 轮 / 20 万 tokens 的空转就是这么来的。
     *
     * 修的形态（端侧实测反复出现，逐条都真实存在）：
     * - 全角标点：`｛` `｝` `，` `：` `"` → 半角
     * - 单引号键值 → 双引号
     * - 裸键名 `{pass:true}` → `{"pass":true}`（值侧不加引号，保持原样）
     * - 尾随逗号 `{"a":1,}` → `{"a":1}`
     * - 结尾缺闭合括号 → 逐层补回
     *
     * 🔴 不做的事：不给裸值补引号（`"reason": abc` 补成 `"abc"` 就是**编造**内容，
     * 本仓对幻觉零容忍）。字符串内的内容一字不动。
     */
    /**
     * 判定结果的**三态**，而不是 bool。
     *
     * ## 🔴 为什么要三态（这是 14 轮空转的直接原因）
     *
     * 旧码 `val pass = o?.optBoolean("pass", false) ?: false` 把两种完全不同的情况
     * 压成同一个结果：
     * 1. 验收方真的判定**不通过**（产物确实不合格）→ 应该重做；
     * 2. 验收方的输出**解析失败**（格式不对/超时/胡说）→ 重做**没有任何意义**，
     *    因为待验收的产物一个字都没变，重做一百遍还是同样的产物、同样解析不出结论。
     *
     * 两者都进 `REPLANNING`，于是集群在「产物合格但格式没对齐」上反复空转，
     * 直到 `maxReplan` 熔断 —— 实测 14 轮 / 20 万 tokens。
     *
     * 现在区分开：
     * - [PASS] / [FAIL] 是**业务结论**，照旧驱动重做；
     * - [UNPARSABLE] 是**协议失败**，先换 prompt 重试一次；仍不行则降级放行
     *   （并如实记账），因为让一个**已经产出的合格产物**因为格式问题作废，
     *   代价远大于放行一个未知质量的产物 —— 而 [noProgress] 拦截仍然兜着
     *   「什么都没产出就宣布完成」这条路。
     */
    internal fun verdictOf(
        text: String?,
    ): ClusterVerdictProtocol.ClusterVerdict = ClusterVerdictProtocol.verdictOf(text)

    /** 供 UI 展示：当前所有角色的预算占用 */
    fun spendSnapshot(taskId: String): Map<String, Int> =
        spend.filterKeys { it.endsWith("|$taskId") }.mapKeys { it.key.removeSuffix("|$taskId") }
}
