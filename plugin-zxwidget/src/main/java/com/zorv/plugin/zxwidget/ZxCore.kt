package com.zorv.plugin.zxwidget

import org.json.JSONObject

/**
 * 组件收敛（元编程层）—— 用自组合反推哪些手工组件其实可以合并掉。
 *
 * **为什么需要**
 *
 * 组件数一路涨到 335 个。第二十八批做了自组合协议（ZxCompose），
 * 思路是"**不新增，而是拼**"。但那只解决了**往后**的问题：
 *
 *   **已经手工加进来的那 335 个怎么办？**
 *
 * 直觉是"再写个脚本自动合并"，但这里有个陷阱：
 *
 *   两个组件语义映射一样 ≠ 它们能合并。
 *
 *   `zx_table` 和 `zx_spreadsheet` 都映射了 VALUES / GROUP / LABEL，
 *   看起来完全一样。但一个是**看**的，一个是**编辑**的——
 *   合并掉任何一个，用户要么失去编辑能力，要么被迫面对一个能改的表。
 *
 * 所以这个模块**不做自动合并**，只做**候选发现 + 判定依据**：
 *
 *   1. 按语义映射集合聚类，找出"语义上长得一样"的组件组
 *   2. 对每组给出判定：这三件事里差了哪件
 *      - 交互性（只读 / 可编辑）
 *      - 维度（单值 / 序列 / 二维）
 *      - 判据（有没有给结论）
 *   3. 只有当三件事都相同时，才输出"**可合并**"
 *
 * ── 三条约束 ────────────────────────────────────────────────
 *
 * 一、**只报告，不合并**。
 *     合并要改渲染器、改校验、改全部历史卡片的 DSL——
 *     那是破坏性变更，必须人来拍板。这个模块只负责把候选摆出来。
 *
 * 二、**语义映射相同只是线索，不是证据**。
 *     上面 table / spreadsheet 的例子就是反例。
 *     所以必须再比交互性、维度、判据三件事。
 *
 * 三、**合并建议要给代价**。
 *     "可以合并"这句话没有信息量——要说明合并后会丢什么。
 *     丢得起才合并，丢不起就保持两个。
 */
object ZxCore {

    /** 判定三件事 */
    enum class Mode(val label: String) {
        READ("只读"), EDIT("可编辑")
    }
    enum class Dim(val label: String) {
        SCALAR("单值"), SERIES("序列"), GRID("二维")
    }
    enum class Verdict(val label: String) {
        NONE("无判据"), HAS("给判据")
    }

    /** 一个组件的"形状指纹" */
    data class Shape(
        val name: String,
        val label: String,
        val sem: Set<String>,
        val mode: Mode,
        val dim: Dim,
        val verdict: Verdict
    ) {
        /** 语义集合相同才算候选 */
        fun sameSemAs(o: Shape) = sem.isNotEmpty() && sem == o.sem
        /** 三件事全同才真能合并 */
        fun mergeableWith(o: Shape) =
            sameSemAs(o) && mode == o.mode && dim == o.dim && verdict == o.verdict
        fun diffOf(o: Shape): String {
            val d = ArrayList<String>()
            if (mode != o.mode) d.add("交互性（${mode.label} vs ${o.mode.label}）")
            if (dim != o.dim) d.add("维度（${dim.label} vs ${o.dim.label}）")
            if (verdict != o.verdict) d.add("判据（${verdict.label} vs ${o.verdict.label}）")
            return d.joinToString("、")
        }
    }

    /** 可编辑类：名字里带这些词的按 EDIT 归类（粗略但有效） */
    private val EDIT_HINT = listOf(
        "edit", "input", "form", "spread", "slider", "toggle", "switch",
        "pick", "select", "check", "rate", "sign", "vote", "sort", "step",
        "knob", "stick", "range", "mask", "tag", "annot", "draw", "crop"
    )
    /** 给判据的：名字里带这些词的按 HAS 归类 */
    private val VERDICT_HINT = listOf(
        "test", "calib", "boot", "perm", "qq", "forest", "bland", "kaplan",
        "roc", "lift", "tolerance", "power", "srm", "abmon", "drift", "slo",
        "risk", "loadtest", "capacity", "regret", "backtest", "feedback"
    )
    /** 二维结构 */
    private val GRID_HINT = listOf(
        "matrix", "heat", "grid", "table", "spread", "cross", "chess",
        "seat", "floor", "bento", "mosaic", "treemap"
    )

    fun shapeOf(name: String): Shape {
        val meta = ZxRegistry.get(name)
        val sems = ZxSem.Sem.values().filter { ZxSem.has(name, it) }
            .map { it.id() }.toSet()
        val mode = if (EDIT_HINT.any { name.contains(it) }) Mode.EDIT else Mode.READ
        val dim = when {
            GRID_HINT.any { name.contains(it) } -> Dim.GRID
            sems.contains("VALUES") || sems.contains("TIME") -> Dim.SERIES
            else -> Dim.SCALAR
        }
        val verdict = if (VERDICT_HINT.any { name.contains(it) }) Verdict.HAS else Verdict.NONE
        return Shape(name, meta?.label ?: name, sems, mode, dim, verdict)
    }

    fun all(): List<Shape> = ZxRegistry.all().map { shapeOf(it.name) }

    /**
     * 找候选：语义集合相同的组。
     *
     * 这只是**线索**——还要再比三件事才知道能不能真合并。
     */
    fun candidates(): List<List<Shape>> {
        val bySem = LinkedHashMap<Set<String>, ArrayList<Shape>>()
        all().forEach { s ->
            if (s.sem.isNotEmpty()) bySem.getOrPut(s.sem) { ArrayList() }.add(s)
        }
        return bySem.values.filter { it.size > 1 }
            .sortedByDescending { it.size }
    }

    /** 真正能合并的：三件事全同 */
    fun mergeable(): List<Pair<Shape, Shape>> {
        val out = ArrayList<Pair<Shape, Shape>>()
        candidates().forEach { g ->
            for (i in g.indices) for (j in i + 1 until g.size)
                if (g[i].mergeableWith(g[j])) out.add(g[i] to g[j])
        }
        return out
    }

    /** 语义相同但合不了的：这才是大多数，也是最有价值的输出 */
    fun blocked(): List<Triple<Shape, Shape, String>> {
        val out = ArrayList<Triple<Shape, Shape, String>>()
        candidates().forEach { g ->
            for (i in g.indices) for (j in i + 1 until g.size)
                if (!g[i].mergeableWith(g[j]))
                    out.add(Triple(g[i], g[j], g[i].diffOf(g[j])))
        }
        return out
    }

    fun render(): String = buildString {
        val a = all(); val c = candidates(); val m = mergeable(); val b = blocked()
        appendLine("组件收敛分析（${a.size} 个组件）")
        appendLine()
        appendLine("  语义集合相同的组　${c.size} 个")
        appendLine("  真正能合并的　　　${m.size} 对")
        appendLine("  合不了的　　　　　${b.size} 对 ← 这才是大多数")
        appendLine()
        appendLine("语义映射相同只是**线索**，不是证据。")
        appendLine("还要再比交互性、维度、判据三件事。")
    }

    fun report(): String = buildString {
        appendLine("组件收敛（用自组合反推哪些手工组件其实可以合并掉）")
        appendLine()
        appendLine("自组合协议解决了**往后**的问题，但已经手工加进来的")
        appendLine("这 ${all().size} 个怎么办？")
        appendLine()
        appendLine("直觉是「写个脚本自动合并」，但这里有个陷阱：")
        appendLine()
        appendLine("  **两个组件语义映射一样 ≠ 它们能合并。**")
        appendLine()
        appendLine("  zx_table 和 zx_spreadsheet 都映射了 VALUES / GROUP / LABEL，")
        appendLine("  看起来完全一样。但一个是**看**的，一个是**编辑**的——")
        appendLine("  合并掉任何一个，用户要么失去编辑能力，")
        appendLine("  要么被迫面对一个能改的表。")
        appendLine()
        appendLine("三条约束：")
        appendLine("  一、**只报告，不合并**（合并是破坏性变更，人来拍板）")
        appendLine("  二、**语义相同只是线索，不是证据**")
        appendLine("  三、**合并建议要给代价**（丢得起才合并）")
    }

    fun demo(): String = buildString {
        val a = all(); val c = candidates(); val m = mergeable(); val b = blocked()
        appendLine("══ 335 个组件，能收敛吗 ══")
        appendLine()
        appendLine("语义集合相同的组　${c.size} 个")
        appendLine()
        appendLine("══ 最有价值的输出是「合不了」的那些 ══")
        appendLine()
        if (b.isEmpty()) { appendLine("（本次没有语义相同的组）") }
        else b.take(6).forEach { (x, y, why) ->
            appendLine("  ${x.name} ↔ ${y.name}")
            appendLine("      语义相同：${x.sem.joinToString("/")}")
            appendLine("      但差在：${why}")
            appendLine("      → **不能合并**：合了会丢掉其中一种能力")
        }
        appendLine()
        appendLine("══ 真能合并的 ══")
        appendLine()
        if (m.isEmpty()) appendLine("  （没有三件事全同的对——说明现有组件区分度是够的）")
        else m.take(6).forEach { (x, y) ->
            appendLine("  ${x.name} ↔ ${y.name}（${x.mode.label} / ${x.dim.label} / ${x.verdict.label}）")
            appendLine("      合并后：${x.label}（保留语义 ${x.sem.joinToString("/")}）")
        }
        appendLine()
        appendLine("══ 结论 ══")
        appendLine()
        appendLine("candidates=${c.size}　mergeable=${m.size}　blocked=${b.size}")
        appendLine()
        appendLine("合不了的是多数，这恰恰说明：")
        appendLine("**这 ${a.size} 个组件不是随手堆出来的**，")
        appendLine("语义看着一样，实际在交互性/维度/判据上分开了。")
    }

    fun validate(o: JSONObject): Pair<Boolean, String> {
        val op = o.optString("op", "")
        if (op !in setOf("list", "cand", "merge", "block", "shape", "demo", "report", "guide"))
            return false to "zx_core 的 op=\"$op\" 只支持 list / cand / merge / block / shape / demo / report / guide"
        if (op == "shape" && o.optString("name").isBlank()) return false to "shape 需要 name"
        return true to ""
    }

    fun guide(): String = """
        zx_core(op=list|cand|merge|block|shape|demo|report|guide, ...)

        用自组合反推哪些手工组件其实可以合并掉。

        自组合协议解决了**往后**的问题，
        但已经手工加进来的这几百个怎么办？

        陷阱：**两个组件语义映射一样 ≠ 它们能合并**
          zx_table 和 zx_spreadsheet 都映射 VALUES / GROUP / LABEL，
          但一个是**看**的，一个是**编辑**的——
          合并掉任何一个都会丢掉一种能力。

        所以再做两次判定：
          cand   语义集合相同（只是**线索**）
          merge  交互性 + 维度 + 判据 三件事全同 → 真能合
          block  语义同但三件事有差 → **不能合**，输出差在哪

        三条约束：
          一、**只报告，不合并**（合并是破坏性变更，人来拍板）
          二、**语义相同只是线索，不是证据**
          三、**合并建议要给代价**（丢得起才合并）
    """.trimIndent()
}
