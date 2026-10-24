# -*- coding: utf-8 -*-
"""#212：工具全开 + 空产物不许送验收 + 工具痕迹可交付。"""
import io, os, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
J = os.path.join


def read(rel):
    p = J(ROOT, rel.replace("/", os.sep))
    raw = io.open(p, "rb").read()
    crlf = raw.count(b"\r\n")
    lf = raw.count(b"\n") - crlf
    eol = "\r\n" if crlf and crlf >= lf else "\n"
    return io.open(p, "r", encoding="utf-8", newline="").read().replace("\r\n", "\n"), eol, p


def write(p, text, eol):
    data = text.replace("\n", eol).encode("utf-8")
    tmp = p + ".tmp"
    with open(tmp, "wb") as f:
        f.write(data)
    os.replace(tmp, p)
    print("  written:", os.path.basename(p), len(data), "bytes")


def sub(text, old, new, name):
    if old not in text:
        print("  !! ANCHOR MISS:", name)
        sys.exit(1)
    if text.count(old) != 1:
        print("  !! ANCHOR NOT UNIQUE (%d):" % text.count(old), name)
        sys.exit(1)
    print("  ok:", name)
    return text.replace(old, new)


# ==================================== 1) ClusterSkillRuntime.kt：工具默认全开
RT = "app/src/main/java/com/ai/assistance/quro/core/cluster/ClusterSkillRuntime.kt"
t, eol, p = read(RT)

OLD = r'''    const val MAX_TOOLS_PER_ROLE = 20'''
NEW = r'''    const val MAX_TOOLS_PER_ROLE = 20

    /**
     * #212：工具**全开**的上限。工具总数不超过它就一股脑全给，
     * 不再按「分工需要」裁剪（见 [toolsFor] 的注释）。
     *
     * 只有当工具多到会挤爆上下文时才退化成按需裁剪，
     * 这个数设得很宽松，正常情况永远走全开。
     */
    const val TOOL_FULL_OPEN_LIMIT = 80'''
t = sub(t, OLD, NEW, "TOOL_FULL_OPEN_LIMIT 常量")

OLD = r'''        val picked: List<QuroToolSpec> = if (whitelist.isNotEmpty()) {
            // 显式白名单：按用户写的顺序保留，未知工具名静默丢弃（不报错，工具可能已下线）
            allTools.filter { it.name in whitelist }.take(MAX_TOOLS_PER_ROLE)
        } else {
            // 分类查询走 ToolCapabilityDirectory.getToolInfo(name)（目录是 install 时的快照，
            // 未 install 的工具返回 null → 记 0 分，靠关键词兜底，不会因此漏掉新工具）。
            val keywords = kindKeywords(role.role)
            val scored = allTools.map { spec ->
                var score = 0
                preferredCategories(role.role).forEachIndexed { idx, cat ->
                    if (categoryOf(spec.name) == cat) {
                        // 越靠前的类别权重越高
                        score += (preferredCategories(role.role).size - idx) * 10
                    }
                }
                val lower = spec.name.lowercase()
                keywords.forEach { kw ->
                    if (lower.contains(kw)) score += 6
                }
                spec to score
            }
            scored.filter { it.second > 0 }
                .sortedByDescending { it.second }
                .take(MAX_TOOLS_PER_ROLE)
                .map { it.first }
        }'''
NEW = r'''        val picked: List<QuroToolSpec> = if (whitelist.isNotEmpty()) {
            // 显式白名单：按用户写的顺序保留，未知工具名静默丢弃（不报错，工具可能已下线）
            allTools.filter { it.name in whitelist }.take(MAX_TOOLS_PER_ROLE)
        } else if (allTools.size > TOOL_FULL_OPEN_LIMIT) {
            // 工具多到会挤爆上下文时才退化成按需裁剪
            pickByRelevance(role, allTools)
        } else {
            // 🔴 #212：**默认全开**。
            //
            // 原来这里按「分工需要」打分裁剪，最多只给 MAX_TOOLS_PER_ROLE(20) 个。
            // 实测后果：执行角色需要的 write_file / shell / browser 之类
            // 一旦没被关键词或类别命中就被砍掉，它**想干活却没有工具** ——
            // 于是只能输出一段文字说明，甚至什么都产不出来，
            // 验收方看到的就是「待验收产物为空」。用户原话：
            // 「所有成员开开放所有工具能力而不是只是给需要的」。
            //
            // 「给需要的」听起来合理，但**需要什么只有模型执行到那一步才知道**，
            // 提前替它猜，猜错就是整个子任务空转。
            // 真要限，用 toolWhitelist 显式圈定 —— 那是明确意图，不是猜测。
            allTools
        }'''
t = sub(t, OLD, NEW, "toolsFor 默认全开")

OLD = r'''    /** 查工具所属分类；目录未收录时返回 null（不抛异常）。 */'''
NEW = r'''    /**
     * #212：按需裁剪 —— **只在工具总数超过 [TOOL_FULL_OPEN_LIMIT] 时**才用。
     *
     * 分类 + 关键词打分，取前 [MAX_TOOLS_PER_ROLE] 个。
     * 保留它不是因为默认需要，而是给「工具真的多到失控」留一条退路。
     */
    private fun pickByRelevance(role: RoleProfile, allTools: List<QuroToolSpec>): List<QuroToolSpec> {
        val cats = preferredCategories(role.role)
        val keywords = kindKeywords(role.role)
        val scored = allTools.map { spec ->
            var score = 0
            cats.forEachIndexed { idx, cat ->
                if (categoryOf(spec.name) == cat) {
                    // 越靠前的类别权重越高
                    score += (cats.size - idx) * 10
                }
            }
            val lower = spec.name.lowercase()
            keywords.forEach { kw -> if (lower.contains(kw)) score += 6 }
            spec to score
        }
        return scored.filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(MAX_TOOLS_PER_ROLE)
            .map { it.first }
    }

    /** 查工具所属分类；目录未收录时返回 null（不抛异常）。 */'''
t = sub(t, OLD, NEW, "pickByRelevance 抽取")
write(p, t, eol)

# ==================================== 2) ClusterEngine.kt
ENG = "app/src/main/java/com/ai/assistance/quro/core/cluster/ClusterEngine.kt"
t, eol, p = read(ENG)

# ---- 2a) RoleReply：把工具痕迹带回调用方
OLD = r'''    /** 角色发言：用它自己绑定的模型 —— 这就是"每个角色一条独立请求"的落点 */
    private suspend fun callRole('''
NEW = r'''    /**
     * #212：角色回复 = 模型输出 + **工具执行痕迹**。
     *
     * 为什么要把痕迹一起带出来：只有文本的话，「模型说自己做了」和「真做了」
     * 在调用方看起来一模一样。执行阶段尤其致命 —— 模型调了工具写了文件，
     * 却没在正文中复述，产物就是空的，验收只会看到「待验收产物为空」。
     * 痕迹是用来兜住这种「干了活但没说」的情况的。
     */
    private data class RoleReply(
        val outcome: LlmOutcome,
        val trace: List<Pair<String, String>>,
    )

    /** 角色发言：用它自己绑定的模型 —— 这就是"每个角色一条独立请求"的落点 */
    private suspend fun callRole('''
t = sub(t, OLD, NEW, "RoleReply 数据类")

OLD = r'''        if (model.second) {
            emit(ClusterEvent.ModelSwitched(t.id, role.personaId, role.modelProfileId, out.modelId, model.first.displayName))
        }
        return out
    }'''
NEW = r'''        if (model.second) {
            emit(ClusterEvent.ModelSwitched(t.id, role.personaId, role.modelProfileId, out.modelId, model.first.displayName))
        }
        return RoleReply(out, toolTrace.toList())
    }'''
t = sub(t, OLD, NEW, "callRole 返回 RoleReply")

# ---- 2b) proposeOne 适配
OLD = r'''        val out = callRole(cluster, t, role, persona, prompt) ?: return null'''
NEW = r'''        val out = callRole(cluster, t, role, persona, prompt)?.outcome ?: return null'''
t = sub(t, OLD, NEW, "proposeOne 适配 RoleReply")

# ---- 2c) executing：空产物不许送验收，痕迹可当产物
OLD = r'''        val out = callRole(cluster, t, role, persona, prompt)
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
        t.state = ClusterTaskState.VERIFYING'''
NEW = r'''        val reply = callRole(cluster, t, role, persona, prompt)
        if (reply == null) {
            node.lastError = "执行失败（模型调用未返回）"
            node.attempt++
            t.replanCount++
            emit(ClusterEvent.Error(t.id, role.personaId, "执行失败"))
            t.state = if (t.replanCount > cluster.budget.maxReplan) ClusterTaskState.ESCALATED else ClusterTaskState.REPLANNING
            return
        }
        val body = reply.outcome.text.trim()
        // 🔴 #212：**空产物不许送去验收**。
        //
        // 用户实测：「⚠ 验收未通过：待验收产物为空，无任何源码或文件可供检查」。
        // 拿空东西去问验收方，它只能回一句「产物为空」—— 白烧一轮 token，
        // 而且这条「驳回意见」里**没有任何可执行的修改动作**，
        // 按照 #211 的口径它连有效驳回都算不上。所以这里直接按执行环节失败处理。
        if (body.isBlank() && reply.trace.isEmpty()) {
            node.lastError = "执行未产出任何内容（既没有正文，也没有调用任何工具）"
            node.attempt++
            t.replanCount++
            emit(ClusterEvent.Error(t.id, role.personaId, node.lastError!!))
            t.state = if (t.replanCount > cluster.budget.maxReplan) ClusterTaskState.ESCALATED else ClusterTaskState.REPLANNING
            return
        }
        // 没写正文但**真的调过工具**：把执行痕迹当产物交付。
        // 否则用户看到的是「什么都没做」，而活其实已经干了 ——
        // 这正是「执行可视化 / 产物交付」缺失的那一块。
        node.artifact = if (body.isNotBlank()) {
            body
        } else {
            buildString {
                appendLine("（本轮未产出文字说明，以下为实际执行痕迹）")
                reply.trace.forEach { (name, summary) -> appendLine("- $name：$summary") }
            }
        }
        // 🔴 带上正文：只有标题的话对话框里就是一条空气泡（用户报「输出不是正常文本内容和产物」）
        emit(ClusterEvent.ArtifactProduced(t.id, node.id, role.personaId, node.title, node.artifact!!))
        record(role.personaId, true)
        t.state = ClusterTaskState.VERIFYING'''
t = sub(t, OLD, NEW, "executing 空产物保护 + 痕迹交付")

# ---- 2d) verifying 适配 RoleReply
OLD = r'''        val out = if (critic != null) {
            personaOf(critic.personaId)?.let { callRole(cluster, t, critic, it, prompt) }
        } else {
            callHost(cluster, t, prompt)
        }'''
NEW = r'''        val out = if (critic != null) {
            personaOf(critic.personaId)?.let { callRole(cluster, t, critic, it, prompt)?.outcome }
        } else {
            callHost(cluster, t, prompt)
        }'''
t = sub(t, OLD, NEW, "verifying 适配 RoleReply")

# ---- 2e) verifying 入口：空产物直接判执行失败，不调模型
OLD = r'''        // 优先挑评审角色；没有 CRITIC 就让主持代验（下面会给主持另一套更严的 prompt）。'''
NEW = r'''        // 🔴 #212：产物为空 → **不调验收模型**。
        // 它只会回一句「待验收产物为空」，既浪费一轮 token，
        // 也拿不到任何能指导修改的信息。直接按执行环节没交付处理。
        if (node.artifact.isNullOrBlank()) {
            node.lastError = "执行环节没有交付任何产物，无法验收"
            node.attempt++
            emit(ClusterEvent.Error(t.id, node.assignee, node.lastError!!))
            if (node.attempt >= cluster.budget.maxNodeAttempts) {
                node.releaseUnverified = true
                node.state = NodeState.DONE
                t.state = if (t.finished()) ClusterTaskState.CONVERGING else ClusterTaskState.DISPATCHING
                return
            }
            t.state = ClusterTaskState.EXECUTING
            return
        }

        // 优先挑评审角色；没有 CRITIC 就让主持代验（下面会给主持另一套更严的 prompt）。'''
t = sub(t, OLD, NEW, "verifying 空产物兜底")
write(p, t, eol)

print("done")
