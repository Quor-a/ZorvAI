# -*- coding: utf-8 -*-
"""#211：评审盲目驳回 -> 逐环节分步引导（讨论/建议/怎么改）。"""
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


# ============================================ 1) ClusterModel.kt: revised
MODEL = "app/src/main/java/com/ai/assistance/quro/core/cluster/ClusterModel.kt"
t, eol, p = read(MODEL)

OLD = r'''    var plan: String? = null,'''
NEW = r'''    var plan: String? = null,
    /**
     * #211：本节点是否已经**带着意见返工过一次**。
     *
     * 为什么不用 [attempt] 判断：attempt 是「重试次数」，
     * 验收方的**协议重试**（判了不合格却没给改法、要求补齐）也会 ++，
     * 于是「到底有没有真正按意见改过一次」就分不清了 ——
     * 本该「改」的一轮会被当成「重做」，闭环又断掉。
     * 返工是**有且只有一次**的动作，用独立标记才说得清。
     */
    var revised: Boolean = false,'''
t = sub(t, OLD, NEW, "ClusterNode 新增 revised")

OLD = r'''        put("plan", plan ?: ""); put("lastArtifact", lastArtifact ?: "")'''
NEW = r'''        put("plan", plan ?: ""); put("lastArtifact", lastArtifact ?: "")
        put("revised", revised)'''
t = sub(t, OLD, NEW, "toJson revised")

OLD = r'''            lastArtifact = o.optString("lastArtifact").takeIf { it.isNotBlank() },'''
NEW = r'''            lastArtifact = o.optString("lastArtifact").takeIf { it.isNotBlank() },
            revised = o.optBoolean("revised", false),'''
t = sub(t, OLD, NEW, "fromJson revised")
write(p, t, eol)

# ============================================ 2) ClusterEngine.kt
ENG = "app/src/main/java/com/ai/assistance/quro/core/cluster/ClusterEngine.kt"
t, eol, p = read(ENG)

# ---- 2a) PROPOSING：先讨论再建议再给计划
OLD = r'''请给出你的执行方案。要求具体、可执行、标明风险。
只输出 JSON：{"summary":"...","steps":["..."],"risks":["..."],"confidence":0.8}'''
NEW = r'''请**按顺序**走完这四步再输出（不许跳过讨论直接给步骤）：
1. 理解：这个子任务真正要交付的东西是什么（一句话）；
2. 讨论：至少两条可选做法，各自的代价与风险（只列一条等于没讨论）；
3. 建议：你推荐哪一条，为什么（要和上面的代价对得上）；
4. 计划：把推荐做法拆成可照做的步骤。

只输出 JSON：{"summary":"...","options":[{"name":"...","pros":"...","cons":"..."}],"recommend":"...","steps":["..."],"risks":["..."],"confidence":0.8}'''
t = sub(t, OLD, NEW, "PROPOSING 分步引导")

# ---- 2b) ARBITRATING：逐份点评 + 取舍理由 + 建议
OLD = r'''请裁决：选出或合并为一个执行计划，说明理由。若全部不可行，pass=false 并说明缺什么。
只输出 JSON：{"pass":true,"reason":"...","steps":["..."],"chosen":"角色名"}'''
NEW = r'''请**按顺序**输出（不许只给结论）：
1. 点评：逐份方案的可取之处与问题（只夸不评等于没审）；
2. 取舍：选哪一份 / 怎么合并，理由是什么；
3. 建议：执行时必须注意的点（写进 advice，执行方会照着做）；
4. 计划：最终执行步骤。

若全部不可行，pass=false，并说明**缺什么**、**补上什么才可行**。
只输出 JSON：{"pass":true,"reason":"...","reviews":[{"who":"...","pro":"...","con":"..."}],"advice":["..."],"steps":["..."],"chosen":"角色名"}'''
t = sub(t, OLD, NEW, "ARBITRATING 分步引导")

# ---- 2c) EXECUTING：先想清楚再产出，产出前自查
OLD = r'''请按以下执行计划完成子任务「${node.title}」，直接产出成果内容（不要只说"我会怎么做"）。
执行计划：
$planText'''
NEW = r'''请按以下执行计划完成子任务「${node.title}」，直接产出成果内容（不要只说"我会怎么做"）。

按顺序走完再输出（思考过程不用写出来，产物里也不要写）：
1. 目标：本子任务要交付什么（先想清楚，一句话）；
2. 对照：验收标准逐条对应到你要产出的内容；
3. 执行：按计划产出**完整**成果；
4. 自查：逐条核对验收标准，发现不满足的**当场补齐**再交付。

执行计划：
$planText'''
t = sub(t, OLD, NEW, "EXECUTING 分步引导")

# ---- 2d) 返工提示：强调逐条照改
OLD = r'''            if (!note.isNullOrBlank()) appendLine("驳回意见：\n$note")'''
NEW = r'''            if (!note.isNullOrBlank()) appendLine("驳回意见（逐条照着改，改完再自查一遍）：\n$note")'''
t = sub(t, OLD, NEW, "返工提示强调逐条改")

# ---- 2e) VERIFYING prompt：分步 + issues 必须带 fix
OLD = r'''            appendLine(if (criticSide) "你是验收方。" else "你兼任验收方（集群里没有专职评审）。")
            appendLine()
            appendLine("验收标准（逐条判定，不允许整体泛判）：")'''
NEW = r'''            appendLine(if (criticSide) "你是验收方。" else "你兼任验收方（集群里没有专职评审）。")
            appendLine()
            // 🔴 #211：**逐步引导**，不许跳步直接甩一个 pass=false。
            // 用户实测的原话：评审「只驳回」，既不说出了什么问题，也不说怎么改 ——
            // 执行方拿到一句「不合格」只能重新想象一份，于是每轮都能被挑出新毛病，
            // 产物永远收敛不了。所以这里强制它先把「复述 → 核对 → 定问题 → 给改法 → 判定」
            // 五步走完，且第 4 步必须产出可直接照做的动作。
            appendLine("请**严格按步骤**走，不要跳步直接给结论：")
            appendLine("第1步 复述：这份产物实际交付了什么（只描述，不评价）。")
            appendLine("第2步 核对：逐条比对验收标准，给出达标/不达标，并引用产物里的证据。")
            appendLine("第3步 定问题：不达标的写清「问题是什么」「为什么不合格」。")
            appendLine("第4步 给建议：每条问题都要给出**能直接照做**的修改动作；")
            appendLine("       另外可给不阻断的改进意见（写进 suggestions）。")
            appendLine("第5步 判定：综合给出 pass。")
            appendLine()
            appendLine("验收标准（逐条判定，不允许整体泛判）：")'''
t = sub(t, OLD, NEW, "VERIFYING 分步引导")

OLD = r'''            appendLine("- 不通过时 failed 里逐条写：哪条不满足 + 具体该怎么改（要能直接指导重做）。")
            appendLine()
            appendLine("只输出 JSON，不要任何额外文字：")
            appendLine("{\"pass\":true/false,\"reason\":\"总体结论\",\"checks\":[{\"index\":1,\"pass\":true,\"note\":\"\"}],\"failed\":[\"\"]}")'''
NEW = r'''            appendLine("- 不达标的问题写进 issues，每条都要有：")
            appendLine("  problem（问题是什么）、why（为什么不合格）、")
            appendLine("  fix（**具体怎么改，要能直接照着做**，不许写「重新考虑一下」这种空话）。")
            appendLine("- 🔴 **没有 fix 的驳回是无效驳回**：只判不合格却不给改法，")
            appendLine("  本次验收会被退回要求补齐，不会拿它去驱动返工。")
            appendLine("- 不影响达标的改进意见写进 suggestions。")
            appendLine()
            appendLine("只输出 JSON，不要任何额外文字：")
            appendLine("{\"restatement\":\"产物实际交付了什么\",\"pass\":true/false,\"reason\":\"总体结论\",")
            appendLine(" \"checks\":[{\"index\":1,\"pass\":true,\"evidence\":\"...\",\"note\":\"\"}],")
            appendLine(" \"issues\":[{\"index\":1,\"problem\":\"...\",\"why\":\"...\",\"fix\":\"具体怎么改\",\"severity\":\"blocker|major|minor\"}],")
            appendLine(" \"suggestions\":[\"...\"]}")'''
t = sub(t, OLD, NEW, "VERIFYING issues 协议")

# ---- 2f) VerdictIssue 数据类 + 解析
OLD = r'''    /** VERIFYING：验收。不过 → 携带失败原因回重规划（异常驱动重提方案） */'''
NEW = r'''    /**
     * #211：验收方给出的**一条具体问题**（含怎么改）。
     *
     * 只有 problem 没有 [fix] 的意见不算数 —— 它没法指导修改，
     * 见 [verifying] 里的「无效驳回」判定。
     */
    private data class VerdictIssue(
        val index: Int,
        val problem: String,
        val why: String,
        val fix: String,
        val severity: String,
    )

    /** VERIFYING：验收。不过 → 携带失败原因回重规划（异常驱动重提方案） */'''
t = sub(t, OLD, NEW, "VerdictIssue 数据类")

OLD = r'''        val reason = o?.optString("reason", "").orEmpty()
        val failedItems = o?.optJSONArray("failed")?.let { arr ->
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
        } ?: emptyList()'''
NEW = r'''        val reason = o?.optString("reason", "").orEmpty()
        val failedItems = o?.optJSONArray("failed")?.let { arr ->
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
        } ?: emptyList()
        // #211：结构化的「问题 + 怎么改」。这才是能驱动返工的东西。
        val issues = o?.optJSONArray("issues")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                runCatching {
                    val c = arr.getJSONObject(i)
                    VerdictIssue(
                        index = c.optInt("index", 0),
                        problem = c.optString("problem"),
                        why = c.optString("why"),
                        fix = c.optString("fix"),
                        severity = c.optString("severity", "major"),
                    )
                }.getOrNull()
            }
        } ?: emptyList()'''
t = sub(t, OLD, NEW, "issues 解析")

# ---- 2g) effectiveReason 带上「怎么改」清单
OLD = r'''        val effectiveReason = buildString {
            if (reason.isNotBlank()) appendLine(reason)
            if (unmet.isNotEmpty()) {'''
NEW = r'''        val effectiveReason = buildString {
            if (reason.isNotBlank()) appendLine(reason)
            // #211：把 issues 摊成「问题 → 怎么改」的清单再交回执行方。
            // 差的一条一条列出来，比一大坨 reason 好用得多：
            // 执行方能逐条勾掉，也能逐条自查。
            if (issues.isNotEmpty()) {
                appendLine("需修改的问题（照着下面的改法逐条改，改完自查）：")
                issues.sortedByDescending {
                    when (it.severity) { "blocker" -> 2; "major" -> 1; else -> 0 }
                }.forEach { isu ->
                    val std = t.acceptance.getOrNull(isu.index - 1)
                    append("- ")
                    if (std != null) append("【标准${isu.index}：$std】")
                    if (isu.problem.isNotBlank()) append("问题：${isu.problem}")
                    if (isu.why.isNotBlank()) append("（${isu.why}）")
                    append("\n  怎么改：${isu.fix}\n")
                }
            }
            if (unmet.isNotEmpty()) {'''
t = sub(t, OLD, NEW, "effectiveReason 含改法清单")

# ---- 2h) 无效驳回：退回补齐，而不是拿空意见驱动返工
OLD = r'''        emit(ClusterEvent.Verdict(t.id, node.id, pass, effectiveReason, verifierName))

        if (pass) {'''
NEW = r'''        // 🔴 #211：**无效驳回**——判了不合格，却一条「怎么改」都没给。
        //
        // 拿这种意见去驱动返工，执行方只能重新想象一份，
        // 于是「每次都能挑出新毛病、产物永远收敛不了」——
        // 用户原话就是「盲目驳回」。所以它不算一次有效验收：
        // 退回补齐，补不出来就如实记账放行，绝不拿它当返工依据。
        val actionable = issues.count { it.fix.isNotBlank() }
        if (!pass && actionable == 0) {
            if (node.attempt < cluster.budget.maxNodeAttempts) {
                node.attempt++
                emit(ClusterEvent.Error(
                    t.id, node.assignee,
                    "验收方（$verifierName）判定不合格，但没给出具体怎么改，" +
                        "已退回要求补齐修改意见（第 ${node.attempt} 次）"
                ))
                t.state = ClusterTaskState.VERIFYING
                return
            }
            emit(ClusterEvent.Verdict(
                t.id, node.id, true,
                "验收方（$verifierName）连续 ${node.attempt} 次判定不合格，" +
                    "但始终没给出可执行的修改意见，本次按**未能判定**处理，" +
                    "产物由你自行复核；这不是「验收通过」。",
                verifierName
            ))
            node.releaseUnverified = true
            node.state = NodeState.DONE
            t.state = if (t.finished()) ClusterTaskState.CONVERGING else ClusterTaskState.DISPATCHING
            return
        }
        emit(ClusterEvent.Verdict(t.id, node.id, pass, effectiveReason, verifierName))

        if (pass) {'''
t = sub(t, OLD, NEW, "无效驳回退回补齐")

# ---- 2i) 返工判定改用 revised
OLD = r'''            if (node.attempt == 1) {
                emit(ClusterEvent.Error('''
NEW = r'''            if (!node.revised) {
                node.revised = true
                emit(ClusterEvent.Error('''
t = sub(t, OLD, NEW, "返工判定改用 revised")
write(p, t, eol)

print("done")
