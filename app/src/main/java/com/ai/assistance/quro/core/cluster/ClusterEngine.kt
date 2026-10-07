package com.ai.assistance.quro.core.cluster

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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

请完成两件事：
1. 定义验收标准：3-6 条可判定的条目（不是模糊描述）；
2. 把目标拆成 2~6 个子任务，标明顺序依赖。

只输出 JSON，不要任何额外文字：
{"acceptance":["...","..."],"nodes":[{"id":"n1","title":"...","instruction":"...","dependsOn":[]}]}
""".trimIndent()

        val out = callHost(cluster, t, prompt) ?: run {
            t.state = ClusterTaskState.ESCALATED; return
        }
        val parsed = firstJson(out.text) ?: run {
            // 主持没吐出合法 JSON：退化为单节点，仍要保证任务可推进
            t.acceptance = listOf("完成用户目标：${t.goal}")
            t.nodes += ClusterNode(id = "n1", title = t.goal, instruction = t.goal)
            t.state = ClusterTaskState.DISPATCHING
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

        val o = firstJson(out.text)
        val pass = o?.optBoolean("pass", true) ?: true
        val reason = o?.optString("reason", "") ?: ""
        emit(ClusterEvent.Verdict(t.id, node.id, pass, reason))

        if (!pass) {
            node.lastError = "主持否决：${reason}"
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
        emit(ClusterEvent.ArtifactProduced(t.id, node.id, role.personaId, node.title))
        record(role.personaId, true)
        t.state = ClusterTaskState.VERIFYING
    }

    /** VERIFYING：验收。不过 → 携带失败原因回重规划（异常驱动重提方案） */
    private suspend fun verifying(cluster: ClusterConfig, t: ClusterTask) {
        val node = t.nodes.firstOrNull { it.state == NodeState.EXECUTING }
            ?: run { t.state = ClusterTaskState.DISPATCHING; return }

        // 优先挑 CRITIC 角色，没有就让主持自己验收
        val critic = RoleRegistry.roles(context).firstOrNull {
            it.role == RoleKind.CRITIC && it.enabled && it.personaId != node.assignee
        }
        val prompt = """
请对照验收标准严格检查以下产物，逐条给结论。任何一条不满足即判不通过。

验收标准：
${t.acceptance.joinToString("\n") { "- $it" }}

产物：
${node.artifact?.take(6000).orEmpty()}

只输出 JSON：{"pass":true/false,"reason":"...","failed":["..."]}
""".trimIndent()

        val out = if (critic != null) {
            personaOf(critic.personaId)?.let { callRole(cluster, t, critic, it, prompt) }
        } else {
            callHost(cluster, t, prompt)
        }

        val o = out?.let { firstJson(it.text) }
        val pass = o?.optBoolean("pass", true) ?: true
        val reason = o?.optString("reason", "") ?: ""
        emit(ClusterEvent.Verdict(t.id, node.id, pass, reason))

        if (pass) {
            node.state = NodeState.DONE
            t.state = if (t.finished()) ClusterTaskState.CONVERGING else ClusterTaskState.DISPATCHING
        } else {
            node.lastError = "验收未通过：${reason} ${o?.optJSONArray("failed")?.let { a -> (0 until a.length()).map { a.optString(it) }.joinToString("；") } ?: ""}"
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
        val prompt = """
全部子任务已完成。请用 3-5 句话总结本次成果。
只输出 JSON：{"summary":"..."}
""".trimIndent()
        val out = callHost(cluster, t, prompt)
        val summary = out?.let { firstJson(it.text)?.optString("summary") } ?: "任务完成"
        close(t, CloseReason.GOAL_REACHED, summary)
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
        t.state = if (reason == CloseReason.GOAL_REACHED) ClusterTaskState.CLOSED else ClusterTaskState.ESCALATED
        t.closeReason = reason
        t.summary = summary
        emit(ClusterEvent.Closed(t.id, reason, summary))
        return reason
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
        val sys = buildRoleSystem(persona.roleSetting, role, persona)
        val out = runCatching {
            gateway.complete(
                model = model.first,
                systemPrompt = sys,
                userPrompt = prompt,
                temperature = role.context.temperature,
                maxTokens = role.context.maxTokens
            )
        }.getOrElse {
            emit(ClusterEvent.Error(t.id, role.personaId, it.message ?: "角色调用失败"))
            return null
        }
        t.turnCount++
        t.tokenUsed += out.tokens
        spend["${role.personaId}|${t.id}"] = used + out.tokens
        if (model.second) {
            emit(ClusterEvent.ModelSwitched(t.id, role.personaId, role.modelProfileId, out.modelId, model.first.displayName))
        }
        return out
    }

    /** 装配该角色专属的 system prompt —— 每个角色看到的都不一样 */
    private fun buildRoleSystem(
        base: String, role: RoleProfile, persona: com.ai.assistance.quro.core.QuroPersona?
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
            appendLine(); appendLine("# 你具备的技能")
            role.skills.forEach { appendLine("- $it") }
        }
        appendLine()
        appendLine("# 输出纪律")
        appendLine("- 只做与你职责相关的事，不越权替其他角色做决定。")
        appendLine("- 不确定就说不确定，禁止编造数据、来源、文件名。")
        appendLine("- 需要协作时写清「建议由谁做什么」，由主持调度。")
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
     * 选人打分 = 职责关键词重合(0.5) + 技能命中(0.2) + 历史成功率(0.2) + 预算余量(0.1)
     * 自动排除：主持（除非只有它）、停用、模型不可用、预算耗尽。
     */
    private fun rankRoles(t: ClusterTask, node: ClusterNode, topK: Int): List<Candidate> {
        val query = (node.title + " " + node.instruction).lowercase()
        val terms = query.split(Regex("[\\s,，。、；;：:（）()\\[\\]]+"))
            .filter { it.length >= 2 }.distinct().take(24)
        val budget = 120_000

        return RoleRegistry.experts(context).asSequence()
            .filter { resolveModel(it, fallbackToAny = false) != null }
            .filter { (spend["${it.personaId}|${t.id}"] ?: 0) < budget }
            .map { r ->
                var s = 0f
                val reasons = ArrayList<String>()
                val text = (r.duties + r.skills).joinToString(" ").lowercase()
                val hit = terms.count { text.contains(it) }
                s += (hit.toFloat() / maxOf(1, terms.size)).coerceAtMost(1f) * 0.5f
                if (hit > 0) reasons += "职责命中${hit}项"
                if (r.skills.any { sk -> terms.any { sk.contains(it, true) } }) { s += 0.2f; reasons += "技能命中" }
                val (ok, fail) = profiles[r.personaId] ?: (0 to 0)
                if (ok + fail > 0) {
                    s += (ok.toFloat() / (ok + fail)) * 0.2f
                    reasons += "成功率${(ok * 100 / (ok + fail))}%"
                }
                val left = 1f - ((spend["${r.personaId}|${t.id}"] ?: 0).toFloat() / budget)
                s += left.coerceIn(0f, 1f) * 0.1f
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

    /** 供 UI 展示：当前所有角色的预算占用 */
    fun spendSnapshot(taskId: String): Map<String, Int> =
        spend.filterKeys { it.endsWith("|$taskId") }.mapKeys { it.key.removeSuffix("|$taskId") }
}
