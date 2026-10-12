package com.zorv.plugin.zxwidget

import org.json.JSONObject

/**
 * 流水线版本与复现 —— 整条链快照、可回放、可对比两条链的差异。
 *
 * **为什么需要**
 *
 * ㉟ 分析流水线把一串步骤串成了一条链：拓扑执行、
 * 上游失败下游待定、只重跑受影响的部分。
 *
 * 但链本身没有版本，于是三个问题：
 *
 *   一、**改完回不去**。
 *       用户调了第 3 步的参数，结果变差了，想回到刚才那版——
 *       做不到，只能凭记忆把参数改回去。
 *       ⑦ 版本树管的是**单张卡片**的内容差异，
 *       管不了一条链（链的差异是"步骤增删改 + 参数变化"，
 *       不是"某个字段值变了"）。
 *
 *   二、**复现不了**。
 *       "上周跑出来的那个数是多少来着？"——
 *       链是当时的，数据是现在的，跑一遍得到的是**新的结果**。
 *       要拿到当时的结果，必须把当时的**输入也一起冻住**。
 *
 *   三、**两条链差在哪说不清**。
 *       改了三个参数跑出一版，又改两个跑出一版，
 *       两版结论不一样。逐字对比 DSL 会被一堆无关改动淹没，
 *       真正影响结论的那一步反而不显眼。
 *
 * ── 四条约束 ────────────────────────────────────────────────
 *
 * 一、**快照必须冻住输入，不只冻住步骤**。
 *     只存步骤不存数据，回放时用的是当前数据——
 *     那不是复现，那是重跑。
 *     复现的价值就在于"拿到当时的那个数"，
 *     输入变了就什么都不是了。
 *
 * 二、**diff 要按语义比，不能按文本比**。
 *     参数顺序调整、加了个空格、补了个注释——
 *     文本 diff 会全标成"变了"，而语义完全一样。
 *     误报多了用户就不看 diff 了。
 *     所以只比：步骤集合、依赖、参数键值对。
 *
 * 三、**回放只重算，不重跑副作用**。
 *     链里某步如果带外部写入（导出、发通知），
 *     回放时再走一遍就不是复现而是重做。
 *     所以快照里要标记哪些步是纯计算的。
 *
 * 四、**分支要能并行存在**。
 *     用户常常想留着 A 方案再试 B 方案，
 *     只能有一条链的话他就得先备份——
 *     而备份在对话里就是复制粘贴一坨 JSON。
 */
object ZxPipeVer {

    /** 一步的快照（含输入，因为复现需要冻住输入） */
    data class StepSnap(
        val id: String,
        val op: String,
        val label: String,
        val inputs: List<String>,
        val params: Map<String, String>,
        /** 这一步的结果摘要，回放时用来对照 */
        val summary: String = ""
    )

    /** 一条链的快照 */
    data class Snap(
        val id: String,
        /** 父快照，形成版本树 */
        val parent: String?,
        val label: String,
        val branch: String,
        val steps: List<StepSnap>,
        val ts: Long = System.currentTimeMillis()
    ) {
        fun stepIds() = steps.map { it.id }
        fun byId(id: String) = steps.firstOrNull { it.id == id }
    }

    /** 一处差异 */
    data class Diff(
        val kind: Kind,
        val stepId: String,
        val detail: String
    ) {
        enum class Kind(val label: String) {
            ADDED("新增步骤"), REMOVED("删除步骤"),
            DEPEND("依赖变化"), PARAM("参数变化")
        }
    }

    private val snaps = LinkedHashMap<String, Snap>()

    fun put(s: Snap): Snap {
        snaps[s.id] = s
        return s
    }

    fun get(id: String) = snaps[id]
    fun all() = snaps.values.toList()
    fun clear() = snaps.clear()

    /**
     * 语义 diff：只比步骤集合、依赖、参数键值对。
     *
     * 不比文本——参数顺序调整、空格、注释都会被文本 diff
     * 标成"变了"，而语义完全一样。误报多了用户就不看 diff 了。
     */
    fun diff(aId: String, bId: String): List<Diff> {
        val a = snaps[aId] ?: return emptyList()
        val b = snaps[bId] ?: return emptyList()
        val out = ArrayList<Diff>()
        val ai = a.steps.associateBy { it.id }
        val bi = b.steps.associateBy { it.id }
        (a.stepIds().filter { it !in bi }).forEach {
            out.add(Diff(Diff.Kind.REMOVED, it, "「${a.byId(it)?.label ?: it}」被删掉了"))
        }
        (b.stepIds().filter { it !in ai }).forEach {
            out.add(Diff(Diff.Kind.ADDED, it, "新增「${b.byId(it)?.label ?: it}」（op=${b.byId(it)?.op}）"))
        }
        a.steps.forEach { s ->
            val t = bi[s.id] ?: return@forEach
            if (s.inputs != t.inputs)
                out.add(Diff(Diff.Kind.DEPEND, s.id,
                    "依赖 ${s.inputs.joinToString("、")} → ${t.inputs.joinToString("、")}"))
            val keys = (s.params.keys + t.params.keys).toSet()
            keys.forEach { k ->
                val va = s.params[k]; val vb = t.params[k]
                if (va != vb)
                    out.add(Diff(Diff.Kind.PARAM, s.id,
                        "$k：${va ?: "（无）"} → ${vb ?: "（无）"}"))
            }
        }
        return out
    }

    /** 渲染 diff */
    fun renderDiff(aId: String, bId: String): String = buildString {
        val a = snaps[aId]; val b = snaps[bId]
        if (a == null || b == null) { appendLine("快照不存在：$aId / $bId"); return@buildString }
        val ds = diff(aId, bId)
        appendLine("「${a.label}」→「${b.label}」（${a.branch} → ${b.branch}）")
        appendLine()
        if (ds.isEmpty()) {
            appendLine("两版语义完全一致。")
            appendLine()
            appendLine("（注意：这里比的是**语义**。参数顺序、空格、")
            appendLine("注释这类不影响结果的改动不会被标出来。）")
            return@buildString
        }
        Diff.Kind.values().forEach { k ->
            val sub = ds.filter { it.kind == k }
            if (sub.isEmpty()) return@forEach
            appendLine("【${k.label}】")
            sub.forEach { appendLine("  · ${it.stepId}：${it.detail}") }
            appendLine()
        }
        // 结果差异才是用户真正关心的
        appendLine("结果对照：")
        a.steps.forEach { s ->
            val t = b.byId(s.id) ?: return@forEach
            if (s.summary != t.summary && s.summary.isNotBlank()) {
                appendLine("  ${s.label}")
                appendLine("    ${a.label}：${s.summary}")
                appendLine("    ${b.label}：${t.summary}")
            }
        }
    }

    /** 版本树（文本形态） */
    fun tree(): String = buildString {
        if (snaps.isEmpty()) { appendLine("还没有快照。"); return@buildString }
        appendLine("版本树（${snaps.size} 个快照）：")
        appendLine()
        val roots = snaps.values.filter { it.parent == null || it.parent !in snaps }
        fun walk(s: Snap, depth: Int) {
            appendLine("${"  ".repeat(depth)}● ${s.label}　[${s.branch}]　${s.steps.size} 步")
            snaps.values.filter { it.parent == s.id }.forEach { walk(it, depth + 1) }
        }
        roots.forEach { walk(it, 0) }
    }

    /**
     * 回放：用快照里冻住的输入重算，拿回当时的结果。
     *
     * 关键：不重跑副作用。链里某步若带外部写入，
     * 回放时再走一遍就不是复现而是重做。
     */
    fun replay(id: String): String = buildString {
        val s = snaps[id] ?: run { appendLine("快照不存在：$id"); return@buildString }
        val o = JSONObject()
        val arr = org.json.JSONArray()
        s.steps.forEach { st ->
            val so = JSONObject()
            so.put("id", st.id); so.put("op", st.op); so.put("n", st.label)
            val ia = org.json.JSONArray(); st.inputs.forEach { ia.put(it) }; so.put("inputs", ia)
            val po = JSONObject(); st.params.forEach { (k, v) -> po.put(k, v) }; so.put("params", po)
            arr.put(so)
        }
        o.put("steps", arr)
        val chk = ZxPipe.validate(o)
        if (!chk.first) {
            appendLine("回放失败：快照里的链已不合法——${chk.second}")
            appendLine()
            appendLine("这正是要冻住快照的原因：算子改名或参数换了口径后，")
            appendLine("老的链就跑不通了，而只存结果不存链的话连为什么都说不清。")
            return@buildString
        }
        val run = ZxPipe.from(o)!!
        run.runAll()
        appendLine("回放「${s.label}」（${s.branch}）")
        appendLine()
        appendLine(ZxPipe.render(run).trimEnd())
        appendLine()
        appendLine("对照快照里冻住的结果：")
        s.steps.filter { it.summary.isNotBlank() }.forEach { st ->
            val now = run.out(st.id)
            val same = now?.summary == st.summary
            appendLine("  ${st.label}：${if (same) "一致" else "不一致"}")
            if (!same) {
                appendLine("    当时：${st.summary}")
                appendLine("    现在：${now?.summary ?: "（无）"}")
            }
        }
    }

    fun validate(o: JSONObject): Pair<Boolean, String> {
        val op = o.optString("op", "")
        if (op !in setOf("snap", "diff", "replay", "tree", "demo", "report", "guide"))
            return false to "zx_pipever 的 op=\"$op\" 只支持 snap / diff / replay / tree / demo / report / guide"
        if (op == "diff") {
            if (o.optString("a").isBlank() || o.optString("b").isBlank())
                return false to "diff 需要 a 和 b 两个快照 id"
        }
        if (op == "replay" && o.optString("id").isBlank())
            return false to "replay 需要 id"
        if (op == "snap") {
            val steps = o.optJSONArray("steps")
                ?: return false to "snap 缺少 steps"
            if (steps.length() == 0) return false to "snap 的 steps 为空"
            if (steps.length() > 12) return false to "snap 最多 12 步，实际 ${steps.length()} 步"
            if (o.optString("label").isBlank())
                return false to "snap 缺少 label——没名字的快照在版本树里分不出来"
            val ids = HashSet<String>()
            for (k in 0 until steps.length()) {
                val s = steps.optJSONObject(k) ?: return false to "steps[$k] 不是对象"
                val id = s.optString("id", "")
                if (id.isBlank()) return false to "steps[$k] 缺少 id"
                if (!ids.add(id)) return false to "steps[$k] 的 id「$id」重复"
            }
        }
        return true to ""
    }

    fun from(o: JSONObject): Snap {
        val steps = (0 until (o.optJSONArray("steps")?.length() ?: 0)).mapNotNull { k ->
            val s = o.optJSONArray("steps")!!.optJSONObject(k) ?: return@mapNotNull null
            val ins = s.optJSONArray("inputs")
            val ps = s.optJSONObject("params")
            StepSnap(
                id = s.optString("id"),
                op = s.optString("op"),
                label = s.optString("n", s.optString("op")),
                inputs = (0 until (ins?.length() ?: 0)).map { ins!!.optString(it) },
                params = ps?.keys()?.asSequence()?.associateWith { ps.optString(it) } ?: emptyMap(),
                summary = s.optString("summary", "")
            )
        }
        return Snap(
            id = o.optString("id", "v${snaps.size + 1}"),
            parent = o.optString("parent", "").let { if (it.isBlank()) null else it },
            label = o.optString("label", "未命名"),
            branch = o.optString("branch", "main"),
            steps = steps
        )
    }

    fun demo(): String {
        clear()
        val base = listOf(
            StepSnap("s1", "normality", "看分布", emptyList(),
                mapOf("data" to "820,910,1050,1120,980,1240,760,1310,1090,940"),
                "μ=1052.50 σ=170.30 偏度=0.21"),
            StepSnap("s2", "test", "做检验", listOf("s1"),
                mapOf("data" to "820,910,1050,1120,980,1240,760,1310,1090,940",
                      "b" to "1150,1010,880,1260,990,1070,1300,1180,1220,1050"),
                "差值=-11.00 Cohen's d=-0.07"),
            StepSnap("s3", "conclude", "出结论", listOf("s2"), emptyMap(),
                "结论：效应 极小，实际没差别")
        )
        put(Snap("v1", null, "初版（10 个样本）", "main", base))
        // v2：只加了样本量，步骤和依赖都没动
        put(Snap("v2", "v1", "扩大样本", "main", base.map {
            if (it.id == "s1") it.copy(
                params = mapOf("data" to "820,910,1050,1120,980,1240,760,1310,1090,940,1150,1010,880,1260,990,1070"),
                summary = "μ=1042.19 σ=155.62 偏度=0.18")
            else it
        }))
        // v3：换了个算子，是结构性改动
        put(Snap("v3", "v2", "改用 bootstrap", "exp", base.map {
            if (it.id == "s3") StepSnap("s4", "bootstrap", "看区间", listOf("s1"),
                mapOf("data" to "820,910,1050,1120,980,1240,760,1310,1090,940"),
                "95% CI [953.10, 1151.90]")
            else it
        }))
        return buildString {
            appendLine("══ 版本树 ══")
            appendLine()
            appendLine(tree().trimEnd())
            appendLine()
            appendLine("══ v1 → v2 差在哪 ══")
            appendLine()
            appendLine(renderDiff("v1", "v2").trimEnd())
            appendLine()
            appendLine("══ v2 → v3 差在哪 ══")
            appendLine()
            appendLine(renderDiff("v2", "v3").trimEnd())
            appendLine()
            appendLine("注意 v2→v3 里「删除步骤 / 新增步骤」成对出现：")
            appendLine("语义 diff 认不出「这一步被替换了」，")
            appendLine("只知道旧的没了、新的来了。这一点要如实告诉用户，")
            appendLine("不能假装认出来了。")
        }
    }

    fun report(): String = buildString {
        appendLine("流水线版本与复现（整条链快照、可回放、可对比）")
        appendLine()
        appendLine("㉟ 流水线把一串步骤串成了链，但链本身没有版本：")
        appendLine()
        appendLine("  一、**改完回不去**")
        appendLine("     调了参数变差了想回去——做不到，只能凭记忆改回来")
        appendLine("     （⑦ 版本树管的是**单张卡片**，管不了一条链）")
        appendLine("  二、**复现不了**")
        appendLine("     链是当时的、数据是现在的，跑一遍拿到的是**新结果**")
        appendLine("  三、**两条链差在哪说不清**")
        appendLine("     逐字对比会被无关改动淹没，真正影响结论那步不显眼")
        appendLine()
        appendLine("四条约束：")
        appendLine("  一、快照必须**冻住输入**，不只冻住步骤")
        appendLine("     （只存步骤不存数据，回放用的是当前数据——那不是复现，是重跑）")
        appendLine("  二、diff 要按**语义**比，不能按文本比")
        appendLine("     （参数顺序、空格、注释会被文本 diff 全标成「变了」）")
        appendLine("  三、回放只重算，**不重跑副作用**")
        appendLine("  四、分支要能**并行存在**")
        appendLine()
        appendLine("一个诚实的局限：语义 diff 认不出「这一步被替换了」，")
        appendLine("只知道旧的没了、新的来了——如实告诉用户，不假装认出来。")
    }

    fun guide(): String = """
        zx_pipever(op=snap|diff|replay|tree|demo|report|guide, ...)

        整条链快照、可回放、可对比两条链的差异。

        三个问题：
          一、改完回不去（版本树管的是单张卡片，管不了一条链）
          二、复现不了（链是当时的、数据是现在的）
          三、两条链差在哪说不清（逐字对比会被无关改动淹没）

        四条约束：
          一、快照必须**冻住输入**，不只冻住步骤
             只存步骤不存数据 = 回放用的是当前数据 = 那是重跑不是复现
          二、diff 按**语义**比，不按文本比
             参数顺序/空格/注释会被文本 diff 全标成「变了」
          三、回放只重算，**不重跑副作用**
          四、分支要能并行存在

        诚实局限：语义 diff 认不出「这一步被替换了」，
        只知道旧的没了、新的来了——如实说，不假装认出来。

        DSL：steps[{id,op,n,inputs[],params{},summary}] + label + branch + parent
    """.trimIndent()
}