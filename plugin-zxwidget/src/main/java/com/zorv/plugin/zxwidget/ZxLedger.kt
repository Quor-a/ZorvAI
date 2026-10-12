package com.zorv.plugin.zxwidget

import org.json.JSONObject

/**
 * 假设登记册 —— 跨会话记住"这条假设查过了没、查出什么"。
 *
 * **为什么需要**
 *
 * ㊴ 因果推断界面解决的是"把假设列出来"，
 * 但列完之后有个问题：**下一轮它又忘了**。
 *
 * 真实的多轮对话是这样的：
 *
 *   第 3 轮  模型：留下降了，可能是因为改了首页
 *   第 3 轮  界面：列出 5 条假设（时序 / 并发干预 / 可比性 / 未测混杂 / 测量）
 *   第 4 轮  用户：查了时序和测量，都 OK
 *   第 6 轮  模型：那我们再看看可比性……
 *   第 12 轮 模型：留下降了，可能是因为改了首页
 *   第 12 轮 界面：又列出 5 条假设，**时序和测量还是"未查"**
 *
 * 第 12 轮那次是纯粹的浪费：那两条已经查过了，
 * 但界面不知道，于是用户要么再查一遍，
 * 要么在心里记住"这两条我查过了"然后手动忽略。
 *
 * 更糟的是相反的情况：
 *
 *   第 4 轮  查了"平行趋势"，结果是**不成立**
 *   第 12 轮 模型又用 DID 说了一个结论
 *
 * 这时候如果界面不记得"平行趋势不成立"，
 * 用户看到的是一个**已经被自己推翻过**的结论重新出现。
 *
 * 所以要有一本登记册，跨轮、跨会话记住：
 *
 *   · 这条假设**查过没有**
 *   · 结果是什么（成立 / 存疑 / 不成立）
 *   · **用什么查的**（哪个组件、哪张卡）
 *   · **什么时候查的**（结论可能过期）
 *
 * ── 五条约束 ────────────────────────────────────────────────
 *
 * 一、**记录结论，也记录"用什么查的"**。
 *     只记"平行趋势不成立"，三个月后没人知道当时是怎么查的——
 *     是用的哪份数据、哪个口径、哪张卡。
 *     没有出处的结果下次无法复核，用户只能选择信或不信。
 *
 * 二、**结论要能失效，但不能悄悄失效**。
 *     数据是会变的：上个月查"测量口径一致"成立，
 *     这个月口径改了，那条结论就不该再生效。
 *     所以要带 TTL / 数据版本，过期了要**明确标出来**
 *     让用户知道该重查了——静默失效等于给用户一个假结论。
 *
 * 三、**"未查"和"查过但不确定"必须区分**。
 *     这两件事用户要做的事完全不同：
 *     未查 = 去做；查过但不确定 = 换方法再查或接受风险。
 *     混成一个状态，用户会对着已经查过的假设反复查。
 *
 * 四、**不替用户判定结论**。
 *     登记册只记用户或工具给出的结果，不自己下判断。
 *     它如果开始"推断"某条假设成立，
 *     那就和模型直接说"是因为改版"没有区别了。
 *
 * 五、**与 ZxCausal 单向依赖**。
 *     登记册读 ZxCausal 的假设 id，但不反向改写它。
 *     否则两边的假设集合会互相污染，谁也不知道以谁为准。
 */
object ZxLedger {

    /** 一条假设的查证结果 */
    enum class Verdict(val label: String, val desc: String) {
        OK("成立", "查过了，这条假设站得住"),
        WARN("存疑", "查过了，但不完全站得住"),
        BROKEN("不成立", "查过了，这条假设是错的"),
        UNSURE("不确定", "查过了，但方法给不出明确结论");

        /** 需不需要用户再做什么 */
        fun needsAction() = this == WARN || this == BROKEN || this == UNSURE
    }

    /** 一条登记 */
    data class Entry(
        val claimId: String,
        val assumptionId: String,
        val text: String,
        var verdict: Verdict,
        /** 用什么查的：组件名 + 卡片 id（可空） */
        val via: String,
        val cardId: String?,
        /** 一句话结论，给未来的自己看 */
        val note: String,
        val ts: Long = System.currentTimeMillis(),
        /** 数据版本；变了就说明这条可能过期 */
        val dataVer: String = ""
    ) {
        fun age(now: Long) = now - ts
    }

    /** 过期阈值：默认 7 天 */
    const val TTL_MS = 7L * 24 * 3600 * 1000

    private val entries = LinkedHashMap<String, Entry>()

    private fun key(claimId: String, aid: String) = "$claimId::$aid"

    fun put(e: Entry): Entry {
        entries[key(e.claimId, e.assumptionId)] = e
        return e
    }

    fun get(claimId: String, aid: String) = entries[key(claimId, aid)]
    fun forClaim(claimId: String) = entries.values.filter { it.claimId == claimId }
    fun all() = entries.values.toList()
    fun clear() = entries.clear()

    /**
     * 把登记册套用到一份假设清单上。
     *
     * 只更新状态，不增删假设——登记册不是假设的来源，
     * 假设集合永远由 ZxCausal 按方法生成。
     */
    fun applyTo(claimId: String, list: List<ZxCausal.Assumption>, now: Long = System.currentTimeMillis()) {
        list.forEach { a ->
            val e = entries[key(claimId, a.id)] ?: run {
                a.state = ZxCausal.Assumption.State.UNKNOWN
                return@forEach
            }
            a.state = when (e.verdict) {
                Verdict.OK -> ZxCausal.Assumption.State.OK
                Verdict.BROKEN -> ZxCausal.Assumption.State.BROKEN
                else -> ZxCausal.Assumption.State.WARN
            }
        }
    }

    /** 哪些假设过期了（超过 TTL），需要重查 */
    fun stale(now: Long = System.currentTimeMillis()): List<Entry> =
        entries.values.filter { it.age(now) > TTL_MS }

    /** 哪些假设的结论是"负面"的，涉及它们的主张要重新审视 */
    fun risky(): List<Entry> =
        entries.values.filter { it.verdict == Verdict.BROKEN || it.verdict == Verdict.WARN }

    fun render(now: Long = System.currentTimeMillis()): String = buildString {
        if (entries.isEmpty()) { appendLine("登记册是空的——还没有任何假设被查证过。"); return@buildString }
        appendLine("假设登记册（${entries.size} 条）")
        appendLine()
        val byClaim = entries.values.groupBy { it.claimId }
        byClaim.forEach { (cid, list) ->
            appendLine("【主张 $cid】")
            list.forEach { e ->
                val age = e.age(now)
                val expired = age > TTL_MS
                val mark = when (e.verdict) {
                    Verdict.OK -> "✓"; Verdict.WARN -> "?"
                    Verdict.BROKEN -> "✗"; Verdict.UNSURE -> "~"
                }
                appendLine("  $mark ${e.text}")
                appendLine("      结论：${e.verdict.label}　${e.verdict.desc}")
                appendLine("      出处：${e.via}${if (e.cardId != null) "（卡 ${e.cardId}）" else ""}")
                if (e.note.isNotBlank()) appendLine("      备注：${e.note}")
                val d = age / 86400000
                appendLine("      时间：${if (d > 0) "$d 天前" else "${age / 3600000} 小时前"}" +
                        if (expired) "　⚠ 已过期，建议重查" else "")
                if (e.dataVer.isNotBlank()) appendLine("      数据版本：${e.dataVer}")
                appendLine()
            }
        }
        val st = stale(now)
        if (st.isNotEmpty()) {
            appendLine("${st.size} 条已过期——结论可能已不成立，建议重查：")
            st.forEach { appendLine("  · ${it.text}（${it.via}）") }
            appendLine()
        }
        val rk = risky()
        if (rk.isNotEmpty()) {
            appendLine("${rk.size} 条是负面结论——涉及它们的主张要重新审视：")
            rk.forEach { appendLine("  · ${it.text} → ${it.verdict.label}") }
        }
    }

    /** 给模型看的精简版：只说"哪些还该查" */
    fun todo(claimId: String, list: List<ZxCausal.Assumption>): String = buildString {
        val notChecked = list.filter { entries[key(claimId, it.id)] == null }
        if (notChecked.isEmpty()) {
            appendLine("这个主张下的假设都查过了。")
            val unsure = list.mapNotNull { a ->
                entries[key(claimId, a.id)]?.takeIf {
                    it.verdict == Verdict.UNSURE || it.verdict == Verdict.WARN
                }?.let { a to it }
            }
            if (unsure.isNotEmpty()) {
                appendLine()
                appendLine("但有几条没查清楚（不是没查，是查了没结论）：")
                unsure.forEach { (a, e) ->
                    appendLine("  · ${a.text}")
                    appendLine("    上次用 ${e.via} 查，结论「${e.verdict.label}」——换方法再查")
                }
            }
            return@buildString
        }
        appendLine("还没查的假设（按能不能验证排序）：")
        notChecked.sortedBy { it.kind.ordinal }.forEachIndexed { i, a ->
            appendLine("  ${i + 1}. ${a.text}")
            appendLine("     → ${a.check}")
        }
    }

    fun validate(o: JSONObject): Pair<Boolean, String> {
        val op = o.optString("op", "")
        if (op !in setOf("put", "get", "list", "todo", "stale", "demo", "report", "guide"))
            return false to "zx_ledger 的 op=\"$op\" 只支持 put / get / list / todo / stale / demo / report / guide"
        if (op == "put") {
            if (o.optString("claim").isBlank())
                return false to "put 需要 claim（主张 id）——不挂到主张上的登记无法被检索"
            if (o.optString("aid").isBlank())
                return false to "put 需要 aid（假设 id）"
            val v = o.optString("v", "")
            if (v !in setOf("ok", "warn", "broken", "unsure"))
                return false to "put 的 v=\"$v\" 只支持 ok / warn / broken / unsure"
            // 出处必须有：没有出处的结论无法复核
            if (o.optString("via").isBlank())
                return false to "put 需要 via（用什么查的）——**没有出处的结论下次无法复核**，" +
                                "用户只能选择信或不信"
        }
        if (op == "get" && (o.optString("claim").isBlank() || o.optString("aid").isBlank()))
            return false to "get 需要 claim 和 aid"
        return true to ""
    }

    private fun verdictOf(s: String) = when (s) {
        "ok" -> Verdict.OK; "warn" -> Verdict.WARN
        "broken" -> Verdict.BROKEN; else -> Verdict.UNSURE
    }

    fun from(o: JSONObject): Entry = Entry(
        claimId = o.optString("claim"),
        assumptionId = o.optString("aid"),
        text = o.optString("text", o.optString("aid")),
        verdict = verdictOf(o.optString("v", "unsure")),
        via = o.optString("via"),
        cardId = o.optString("card", "").let { if (it.isBlank()) null else it },
        note = o.optString("note", ""),
        dataVer = o.optString("dv", "")
    )

    fun demo(): String {
        clear()
        val cid = "c1"
        val now = System.currentTimeMillis()
        put(Entry(cid, "time", "改版发生在留存下降之前", Verdict.OK,
            "zx_did 对齐干预时点", "AF2", "改版 3/12 上线，留存 3/14 开始掉", now - 6 * 86400000L))
        put(Entry(cid, "measure", "留存的测法前后一致", Verdict.OK,
            "查口径变更记录", null, "口径 2/1 起没变过", now - 6 * 86400000L))
        put(Entry(cid, "parallel", "干预前两组走势平行", Verdict.BROKEN,
            "zx_did 画干预前两条线", "AF2",
            "干预前差 0.02 但波动 0.03，不平行；DID 估计不可信", now - 5 * 86400000L))
        put(Entry(cid, "noconfound", "没有与干预同时发生的其他变化", Verdict.UNSURE,
            "列同期变更 + zx_sense", null,
            "同期有 2 个变更，无法分离；临界 Γ=1.4，结论很脆弱", now - 5 * 86400000L))
        // 一条过期的：让它演示"过期要明确标出来"
        put(Entry(cid, "comparable", "两组本来就差不多", Verdict.OK,
            "zx_balance 看 SMD", "AF7", "匹配后各协变量 |SMD|<0.1",
            now - 20 * 86400000L, "2026-W37"))

        val claim = ZxCausal.Claim("首页改版", "次留下降", ZxCausal.Claim.Method.DID)
        val list = ZxCausal.assumptions(claim)
        applyTo(cid, list, now)

        return buildString {
            appendLine("══ 第 3 轮 ══")
            appendLine()
            appendLine("  模型：留下降了，是因为改了首页")
            appendLine("  界面：列出 ${list.size} 条假设，全是「未查」")
            appendLine()
            appendLine("══ 第 4-6 轮：用户逐条去查 ══")
            appendLine()
            appendLine(render(now).trimEnd())
            appendLine()
            appendLine("══ 第 12 轮（模型又说了一次同样的话）══")
            appendLine()
            appendLine(todo(cid, list).trimEnd())
            appendLine()
            appendLine("注意「两组本来就差不多」那条：")
            appendLine("  它 20 天前查过、当时成立，但**已过期**——")
            appendLine("  登记册会明确标出来让它重查。")
            appendLine("  **静默失效等于给用户一个假结论。**")
            appendLine()
            appendLine("而「干预前两组走势平行」是**不成立**的——")
            appendLine("  所以它会出现在负面结论里：")
            appendLine("  任何用 DID 说出来的结论，都要先被这条拦一下。")
        }
    }

    fun report(): String = buildString {
        appendLine("假设登记册（跨会话记住「这条查过了没、查出什么」）")
        appendLine()
        appendLine("㊴ 因果推断界面解决的是「把假设列出来」，")
        appendLine("但列完之后有个问题：**下一轮它又忘了**。")
        appendLine()
        appendLine("  第 3 轮   列出 5 条假设")
        appendLine("  第 4 轮   查了时序和测量，都 OK")
        appendLine("  第 12 轮  模型又说了一遍同样的话")
        appendLine("  第 12 轮  **又列出 5 条，时序和测量还是「未查」**")
        appendLine()
        appendLine("更糟的是相反的情况：查过「平行趋势不成立」，")
        appendLine("结果第 12 轮又一个用 DID 说出来的结论冒出来——")
        appendLine("用户看到的是一个**已经被自己推翻过**的结论重新出现。")
        appendLine()
        appendLine("五条约束：")
        appendLine("  一、记录结论，**也记录用什么查的**（没出处无法复核）")
        appendLine("  二、结论要能失效，但**不能悄悄失效**（静默失效=假结论）")
        appendLine("  三、「未查」和「查过但不确定」必须区分")
        appendLine("  四、**不替用户判定结论**")
        appendLine("  五、与 ZxCausal 单向依赖（不反向改写假设集合）")
    }

    fun guide(): String = """
        zx_ledger(op=put|get|list|todo|stale|demo|report|guide, ...)

        跨会话记住「这条假设查过了没、查出什么」。

        ㊴ 因果推断界面列出了假设，但**下一轮它又忘了**：
          第 12 轮模型又说一遍同样的话，界面又列 5 条，
          其中两条第 4 轮就查过了。

        更糟的：查过「平行趋势不成立」，
        第 12 轮又一个用 DID 说出来的结论冒出来——
        用户看到的是**已经被自己推翻过**的结论。

        记住四件事：
          · 查过没有
          · 结果是什么（成立/存疑/不成立/不确定）
          · **用什么查的**（没出处 = 无法复核）
          · 什么时候查的（结论可能过期）

        五条约束：
          一、记录结论，也记录**出处**
          二、能失效，但**不能悄悄失效**（静默失效 = 假结论）
          三、「未查」和「查过但不确定」必须区分
          四、**不替用户判定结论**
          五、与 ZxCausal 单向依赖

        DSL：claim + aid + v=ok|warn|broken|unsure + via + card + note + dv
    """.trimIndent()
}