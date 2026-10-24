import io, os

p = 'app/src/main/java/com/ai/assistance/quro/core/cluster/ClusterEngine.kt'
src = io.open(p, encoding='utf-8').read()
orig = src

def sub(old, new, tag):
    global src
    assert src.count(old) == 1, 'anchor %s count=%d' % (tag, src.count(old))
    src = src.replace(old, new, 1)

# ═══════════════ ARBITRATING：三态 + 验收方身份 ═══════════════
sub(
"""        val out = callHost(cluster, t, prompt)
        if (out == null) { t.state = ClusterTaskState.ESCALATED; return }

        val o = firstJson(out.text)
        // 🔴 裁决解析失败不能默认放行（旧码 `?: true`）：主持没给出有效裁决时，
        // 应当走重规划让集群换个打法，而不是把空方案当裁决通过。
        val pass = o?.optBoolean("pass", false) ?: false
        val reason = o?.optString("reason", "").orEmpty()
        val effectiveReason = if (reason.isNotBlank()) reason else "主持裁决无法解析，按否决处理"
        emit(ClusterEvent.Verdict(t.id, node.id, pass, effectiveReason))

        if (!pass) {""",
"""        val out = callHost(cluster, t, prompt)
        if (out == null) { t.state = ClusterTaskState.ESCALATED; return }

        // 🔴 裁决走**三态**（见 ClusterVerdict）：裁决输出解析失败**不是**「裁决不通过」。
        // 旧码 `?: false` 让「主持只是 JSON 格式没对齐」被记成「方案不可行」，
        // 于是换打法重提方案 —— 但方案本身没变，重提一百遍还是同样的格式问题。
        // 用户实测的 14 轮 / 20 万 tokens 空转，这条就是主因之一。
        val v = verdictOf(out.text)
        val o = firstJson(out.text)
        val pass = v == ClusterVerdict.PASS
        val reason = o?.optString("reason", "").orEmpty()
        val effectiveReason = when (v) {
            ClusterVerdict.PASS ->
                if (reason.isNotBlank()) reason else "主持裁决通过"
            ClusterVerdict.FAIL ->
                reason.ifBlank { "主持裁决：方案不可行（未说明原因）" }
            ClusterVerdict.UNPARSABLE ->
                // 降级：自己挑一份方案当执行计划，别把整个任务打回重做。
                // 多个方案里挑第一份，比让任务在同一个格式问题上死循环划算得多；
                // 真选错了，执行阶段的验收（VERIFYING）还会再兜一道。
                return fallbackArbitrate(cluster, t, node, out.text)
        }
        emit(ClusterEvent.Verdict(t.id, node.id, pass, effectiveReason, "主持"))

        if (!pass) {""",
'arbitrating')

# ═══════════════ VERIFYING：三态 + 回答「谁验收」 ═══════════════
sub(
"""        val o = out?.let { firstJson(it.text) }
        // 🔴 判定默认必须是**不通过**，不是通过。
        // 旧码 `?: true` 意味着：模型没吐合法 JSON（超时/截断/胡说）→ 静默判定通过 →
        // 一个字都没产出的东西能一路走到 CLOSED。这不是「容错」，是把验收闸门拆了。
        val pass = o?.optBoolean("pass", false) ?: false
        val reason = o?.optString("reason", "").orEmpty()""",
"""        val o = out?.text?.let { firstJson(it) }
        // 🔴 判定默认必须是**不通过**（旧码 `?: true` 会让「一个字都没产出的东西」
        // 静默通过一路走到 CLOSED —— 那是把验收闸门拆了，不是容错）。
        //
        // 🔴 但 2026-10-08 把「解析失败」从「不通过」里拆了出来（见 ClusterVerdict）：
        // 验收方输出解析不出 JSON，说明的是**协议没对上**，不是「产物不合格」。
        // 拿它去驱动重做，等于要求执行者重做一个**根本没被判定过的**产物 ——
        // 而产物字节没变，重做 100 次还是同样的解析失败。这就是用户实测里
        // 「⚠ 验收未通过：验收方未给出有效结论」+「❌ 裁决不通过：验收方未给出有效结论」
        // 两条同时出现、14 轮空转的直接原因。
        val v = verdictOf(out?.text)
        val pass = v == ClusterVerdict.PASS
        // 验收方身份：评审角色，还是主持代验？（用户直接问过「关键是谁验收？」）
        val verifierName = critic?.let { c ->
            runCatching { personaOf(c.personaId)?.name }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: c.personaId
        } ?: "主持（集群里没有专职评审，主持代验）"
        if (v == ClusterVerdict.UNPARSABLE) {
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
        val reason = o?.optString("reason", "").orEmpty()""",
'verifying')

# ═══════════════ Verdict 事件补验收方字段 + 其余 emit 点 ═══════════════
sub(
"""            if (isBlank()) append("验收方未给出有效结论（输出无法解析），按不通过处理")
        }.trim()
        emit(ClusterEvent.Verdict(t.id, node.id, pass, effectiveReason))""",
"""        // 🔴 不再拼「验收方未给出有效结论（输出无法解析）」这句——
        // 解析失败已在上游分流到 UNPARSABLE 分支，走不到这里。
            if (isBlank()) append("验收方未给出有效结论")
        }.trim()
        emit(ClusterEvent.Verdict(t.id, node.id, pass, effectiveReason, verifierName))""",
'verdict-emit')

# ═══════════════ 新增：裁决解析失败时的降级取方案 ═══════════════
sub(
"""    /** EXECUTING：执行角色按计划产出成果 */""",
"""    /**
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
    private fun fallbackArbitrate(
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
        node.artifact = steps.joinToString("\\n")
        node.state = NodeState.EXECUTING
        t.state = ClusterTaskState.EXECUTING
    }

    /** EXECUTING：执行角色按计划产出成果 */""",
'fallback')

assert src != orig
tmp = p + '.tmp'
io.open(tmp, 'w', encoding='utf-8', newline='').write(src)
os.replace(tmp, p)
print('OK  bytes %d -> %d' % (len(orig), len(src)))