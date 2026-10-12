package com.zorv.plugin.zxwidget

import org.json.JSONObject

/**
 * 语义字典 —— 同一个概念，在 299 个组件里叫什么。
 *
 * **为什么现在才需要**
 *
 * 组件少的时候这不是问题：模型记得住 `zx_chart` 用 `labels` 和 `points`。
 *
 * 到了 299 个，同一个概念在各组件里叫法开始分叉：
 *
 *   置信区间   boot: [lo, hi]     forest: ci:[a,b]     bayes: 后验区间
 *   分组标签   bar: labels         box: n               pie: names
 *   数值       chart: points       pscan: vs            table: rows[i][j]
 *   权重       decision: crit[].w  rollup: w            psm: cs[]
 *
 * 于是出现了一种新的失败：
 *
 *   模型要"把 boot 算出的区间填进 forest 的图里"，
 *   它得先知道 boot 的区间在哪个字段、forest 又期望哪个字段。
 *   它只能靠猜，猜错一次校验会拦，但**它不知道为什么错**——
 *   校验说的是"ci[0] 不是数值"，而不是"你该用 lo/hi"。
 *
 * 更常见的是跨卡引用：
 *
 *   {{ref.A.rows[0][1]}}   表格 A 的第 0 行第 1 列
 *   {{ref.B.data[3]}}      图表 B 的第 4 个点
 *
 * 这两个都是"某个数"，但路径完全不同。模型要么把整张卡读进来找
 * （费 token），要么猜（猜错就白跑一轮）。
 *
 * 语义字典给的是第三条路：**用统一的名字问**。
 *
 *   sem.get("cardA", "ci")   →  不管那张卡是 boot 还是 forest，都返回区间
 *   sem.get("cardB", "value", 3)  →  第 4 个数值，不管它是 points 还是 vs
 *
 * ── 四条约束 ────────────────────────────────────────────────
 *
 * 一、**语义名必须有限**。
 *     如果允许任意字符串当语义名，它就成了第二个字段名，
 *     那分叉问题原样重演一遍，只是多了一层。
 *     所以只有一张固定的表，加新语义名要显式登记。
 *
 * 二、**不认识的组件要明说"不认识"，不许猜**。
 *     看到一个没登记的组件，猜一个"看起来像"的字段路径
 *     是最危险的做法——它会返回一个错的值，
 *     而下游会当成对的用。**返回 null 比返回错的值好**。
 *
 * 三、**读取是主线，写入要显式且必须重新校验**。
 *     写入改变了卡片内容，所以要走和 ⑨ 补丁协议一样的规则：
 *     写完全量重新校验，失败整体回滚。
 *
 * 四、**只做字段映射，不做单位换算和语义转换**。
 *     字典知道"区间在 lo/hi"，但不管"一个是百分比一个是小数"。
 *     单位的事要显式声明（`unit` 字段），不能让字典悄悄换算——
 *     悄悄换算出来的值，用户不知道它是怎么来的。
 */
object ZxSem {

    /** 语义名（固定集合，不许动态扩） */
    enum class Sem(val label: String, val desc: String) {
        LABELS("分组标签", "每组的名字"),
        LABEL("标签", "单个标签/名字"),
        VALUES("数值序列", "主要的那一列数"),
        CI("区间", "下界与上界"),
        WEIGHT("权重", "各维度的重要性"),
        GROUP("分组", "每组的成员值"),
        PROB("概率", "0-1 之间的概率"),
        TIME("时间", "时间点或期数"),
        STATUS("状态", "每项的当前状态"),
        SCORE("评分", "每项的得分");

        fun id() = name.lowercase()
    }

    /** 一个组件在某个语义下的字段路径 */
    data class Mapping(
        val component: String,
        val sem: Sem,
        /** 字段路径；取值时按 . 逐层下钻，[i] 取数组下标 */
        val path: String,
        val unit: String = "",
        val note: String = ""
    )

    /** 登记表：component -> sem -> path */
    private val table = HashMap<String, HashMap<String, Mapping>>()

    /** 冲突记录：同一个组件同一语义被登记两次 */
    private val conflicts = ArrayList<String>()

    fun reg(m: Mapping) {
        val by = table.getOrPut(m.component) { HashMap() }
        val old = by[m.sem.id()]
        if (old != null && old.path != m.path)
            conflicts.add("${m.component}.${m.sem.id()}：${old.path} → ${m.path}")
        by[m.sem.id()] = m
    }

    fun has(component: String, sem: Sem) = table[component]?.containsKey(sem.id()) ?: false

    /** 这个组件登记了哪些语义 */
    fun known(component: String): List<Sem> =
        table[component]?.keys?.mapNotNull { k -> Sem.values().firstOrNull { it.id() == k } } ?: emptyList()

    /**
     * 按语义取值。
     *
     * 关键：不认识的组件返回 null 并且**给出原因**，
     * 不猜一个看起来像的字段——返回错的值比返回 null 危险得多。
     */
    fun get(card: JSONObject, component: String, sem: Sem, index: Int = -1): SemResult {
        val m = table[component]?.get(sem.id())
            ?: return SemResult(false, null,
                "「$component」没有登记「${sem.label}」这个语义。" +
                if (known(component).isEmpty()) "（这个组件一个语义都没登记）"
                else "（它登记了：${known(component).joinToString("、") { it.label }}）")
        val v = walk(card, m.path, index)
            ?: return SemResult(false, null,
                "「$component」登记的路径 ${m.path} 在这张卡上取不到值——" +
                "不是登记错了，就是这张卡没填这个字段")
        return SemResult(true, v, "", m.unit)
    }

    /**
     * 按语义取**数值**（触发器/规则用）。
     *
     * 接受语义名字符串而不是 Sem 枚举——调用方（规则 DSL）手里只有字符串。
     * 取不到、语义名不认识、值不是数字，一律返回 null：
     * **触发器绝不能猜一个值**，猜出来的命中比不命中危险得多。
     */
    fun num(card: JSONObject?, component: String, sem: String, index: Int = -1): Double? {
        if (card == null) return null
        val e = Sem.values().firstOrNull { it.id() == sem } ?: return null
        return get(card, component, e, index).value?.toDoubleOrNull()
    }

    /**
     * 不指定卡片时，取该组件**最新登记的那张卡**作为数据源。
     *
     * 触发器监听的是「组件」而不是「某一张卡」——同一个组件可能被渲染过多张。
     * 约定读最新登记的那张，这样「组件当前值」有确定含义。
     * 一张都没登记时返回 null，条件按未满足处理（宁可漏触发，不可误触发）。
     */
    fun liveCard(component: String): JSONObject? =
        ZxRef.graph.values.lastOrNull { it.component == component }
            ?.dsl?.let { runCatching { JSONObject(it) }.getOrNull() }

    /** liveCard + num：按组件取当前数值 */
    fun liveNum(component: String, sem: String, index: Int = -1): Double? =
        num(liveCard(component), component, sem, index)

    /** 语义写入：写完必须重新校验 */
    fun set(card: JSONObject, component: String, sem: Sem, raw: String): Pair<Boolean, String> {
        val m = table[component]?.get(sem.id())
            ?: return false to "「$component」没有登记「${sem.label}」，无法按语义写入"
        val trial = JSONObject(card.toString())
        if (!assign(trial, m.path, raw))
            return false to "按路径 ${m.path} 写入失败——路径在这张卡上不存在"
        // 写入改变了内容，所以必须整体重新校验（与 ⑨ 补丁协议同源）
        val chk = ZxDsl.validateAny(component, trial)
        if (!chk.ok)
            return false to "写入后校验不通过：${chk.reason}——本次写入未生效"
        // 校验通过才真正改
        val merged = JSONObject(trial.toString())
        val keys = merged.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            card.put(k, merged.opt(k))
        }
        return true to "已按「${sem.label}」写入 ${m.path} = $raw，校验通过"
    }

    /* ── 路径下钻 ─────────────────────────────────────────────── */
    private fun walk(o: JSONObject, path: String, index: Int): String? {
        var cur: Any? = o
        var pendingIdx = index
        val parts = path.split(".")
        for (p in parts) {
            if (cur == null) return null
            // 处理 data[3] 这种下标
            val bi = p.indexOf('[')
            val name = if (bi >= 0) p.substring(0, bi) else p
            if (name.isNotEmpty()) {
                cur = when (cur) {
                    is JSONObject -> cur.opt(name)
                    else -> return null
                }
            }
            var idx = -1
            if (bi >= 0)
                idx = p.substring(bi + 1, p.length - 1).toIntOrNull() ?: return null
            if (idx < 0 && pendingIdx >= 0) { idx = pendingIdx; pendingIdx = -1 }
            if (idx >= 0) {
                cur = when (cur) {
                    is org.json.JSONArray -> if (idx < cur.length()) cur.opt(idx) else null
                    else -> return null
                }
            }
        }
        return when (cur) {
            null -> null
            is org.json.JSONArray -> (0 until cur.length()).joinToString(",") { cur.optString(it) }
            else -> cur.toString()
        }
    }

    private fun assign(o: JSONObject, path: String, raw: String): Boolean {
        val parts = path.split(".")
        var cur: JSONObject = o
        for (i in 0 until parts.size - 1) {
            val p = parts[i]
            val bi = p.indexOf('[')
            if (bi >= 0) return false      // 写入不支持路径中间带数组下标
            var nxt = cur.optJSONObject(p)
            if (nxt == null) {
                nxt = JSONObject()
                cur.put(p, nxt)
            }
            cur = nxt
        }
        val last = parts.last()
        val bi = last.indexOf('[')
        if (bi < 0) { cur.put(last, coerce(raw)); return true }
        val name = last.substring(0, bi)
        val idx = last.substring(bi + 1, last.length - 1).toIntOrNull() ?: return false
        var arr = cur.optJSONArray(name)
        if (arr == null) {
            arr = org.json.JSONArray()
            cur.put(name, arr)
        }
        while (arr.length() <= idx) arr.put(0)
        arr.put(idx, coerce(raw))
        return true
    }

    /** 写入时尽量还原类型：数字就存数字，别把 0.5 存成 "0.5" */
    private fun coerce(raw: String): Any {
        raw.trim().let { t ->
            t.toLongOrNull()?.let { return it }
            t.toDoubleOrNull()?.let { return it }
        }
        if (raw.trim().startsWith("[") || raw.trim().startsWith("{")) {
            runCatching { return org.json.JSONArray(raw) }
            runCatching { return JSONObject(raw) }
        }
        return raw
    }

    /** 取值结果 */
    data class SemResult(
        val ok: Boolean,
        val value: String?,
        val reason: String = "",
        val unit: String = ""
    )

    fun audit(): String = buildString {
        appendLine("语义字典自检")
        appendLine()
        appendLine("已登记组件 ${table.size} 个，语义 ${Sem.values().size} 种")
        appendLine()
        if (conflicts.isEmpty()) appendLine("无登记冲突。")
        else {
            appendLine("登记冲突 ${conflicts.size} 处（后者覆盖前者）：")
            conflicts.forEach { appendLine("  · $it") }
        }
        appendLine()
        appendLine("未登记任何语义的组件：")
        val known = table.keys
        val miss = ZxRegistry.all().map { it.name }.filter { it !in known }
        if (miss.isEmpty()) appendLine("  （无）")
        else {
            appendLine("  共 ${miss.size} 个。这些组件**不是坏了**，")
            appendLine("  只是不能用语义名访问——按语义问会明确说「没登记」，")
            appendLine("  不会猜一个看起来像的字段。")
            appendLine("  前 12 个：${miss.take(12).joinToString("、")}")
        }
    }

    fun render(): String = buildString {
        appendLine("语义字典（同一个概念在 299 个组件里叫什么）")
        appendLine()
        Sem.values().forEach { s ->
            val rows = table.entries.filter { it.value.containsKey(s.id()) }
            if (rows.isEmpty()) return@forEach
            appendLine("【${s.label}】${s.desc}　（${rows.size} 个组件）")
            rows.take(8).forEach { (c, by) ->
                val m = by[s.id()]!!
                appendLine("  $c　→ ${m.path}${if (m.unit.isNotBlank()) "（${m.unit}）" else ""}")
            }
            if (rows.size > 8) appendLine("  …还有 ${rows.size - 8} 个")
            appendLine()
        }
    }

    fun validate(o: JSONObject): Pair<Boolean, String> {
        val op = o.optString("op", "")
        if (op !in setOf("get", "set", "list", "audit", "demo", "report", "guide"))
            return false to "zx_sem 的 op=\"$op\" 只支持 get / set / list / audit / demo / report / guide"
        val comp = o.optString("comp", "")
        if (comp.isNotBlank() && !ZxRegistry.has(comp))
            return false to "comp=\"$comp\" 不是已注册组件"
        val sem = o.optString("sem", "")
        if (sem.isNotBlank() && Sem.values().none { it.id() == sem })
            return false to "sem=\"$sem\" 不在语义集合内。可用：${Sem.values().joinToString(" / ") { it.id() }}"
        if ((op == "get" || op == "set") && comp.isBlank())
            return false to "$op 需要 comp"
        if ((op == "get" || op == "set") && sem.isBlank())
            return false to "$op 需要 sem"
        return true to ""
    }

    fun demo(): String = buildString {
        appendLine("══ 问题：同一个概念，叫法分叉 ══")
        appendLine()
        appendLine("  boot    的区间在  lo / hi")
        appendLine("  forest  的区间在  ci[0] / ci[1]")
        appendLine("  bayes   的区间要自己算")
        appendLine()
        appendLine("模型要「把 boot 算出的区间填进 forest」：")
        appendLine("  它得先知道两边各是哪个字段，只能靠猜。")
        appendLine("  猜错时校验说的是「ci[0] 不是数值」，")
        appendLine("  而不是「你该用 lo/hi」——**它不知道为什么错**。")
        appendLine()
        appendLine("══ 用语义名问 ══")
        appendLine()
        val boot = JSONObject("""{"type":"zx_boot","n":"中位数区间","lo":953.1,"hi":1151.9}""")
        val forest = JSONObject("""{"type":"zx_forest","n":"合并效应","ci":[0.31,0.58]}""")
        val r1 = get(boot, "zx_boot", Sem.CI)
        val r2 = get(forest, "zx_forest", Sem.CI)
        appendLine("  sem.get(boot,   CI) → ${if (r1.ok) r1.value else "取不到"}　（路径 lo/hi）")
        appendLine("  sem.get(forest, CI) → ${if (r2.ok) r2.value else "取不到"}　（路径 ci[0]/ci[1]）")
        appendLine()
        appendLine("  同一个名字，两条不同的路径——模型不用知道路径。")
        appendLine()
        appendLine("══ 不认识的组件：明说不认识，不许猜 ══")
        appendLine()
        val r3 = get(JSONObject("""{"type":"zx_chart","labels":["a","b"],"points":[1,2]}"""),
            "zx_chart", Sem.CI)
        appendLine("  sem.get(zx_chart, CI)")
        appendLine("  → ${if (r3.ok) r3.value else r3.reason}")
        appendLine()
        appendLine("  **返回 null 比返回错的值好**——")
        appendLine("  猜一个「看起来像」的字段会返回错的值，")
        appendLine("  而下游会当成对的用。")
    }

    fun report(): String = buildString {
        appendLine("语义字典（同一个概念在 299 个组件里叫什么）")
        appendLine()
        appendLine("组件少的时候这不是问题。到了 299 个，叫法开始分叉：")
        appendLine()
        appendLine("  置信区间   boot:[lo,hi]   forest:ci:[a,b]   bayes:后验区间")
        appendLine("  分组标签   bar:labels     box:n             pie:names")
        appendLine("  数值       chart:points   pscan:vs          table:rows[i][j]")
        appendLine()
        appendLine("于是模型跨组件取数只能靠猜。猜错时校验说的是")
        appendLine("「ci[0] 不是数值」，而不是「你该用 lo/hi」——")
        appendLine("**它不知道为什么错**，于是再猜一次。")
        appendLine()
        appendLine("四条约束：")
        appendLine("  一、语义名必须**有限**（否则分叉原样重演一遍）")
        appendLine("  二、不认识的组件**明说不认识，不许猜**")
        appendLine("     （返回错的值比返回 null 危险得多）")
        appendLine("  三、读取是主线，写入要**显式且重新校验**")
        appendLine("  四、只做字段映射，**不做单位换算**")
        appendLine("     （悄悄换算出来的值，用户不知道它怎么来的）")
    }

    fun guide(): String = """
        zx_sem(op=get|set|list|audit|demo|report|guide, comp, sem, ...)

        同一个概念，在 299 个组件里叫什么。

        置信区间：boot:[lo,hi] / forest:ci:[a,b] / bayes:后验区间
        分组标签：bar:labels / box:n / pie:names
        数值　　：chart:points / pscan:vs / table:rows[i][j]

        模型跨组件取数只能靠猜。猜错时校验说
        「ci[0] 不是数值」，而不是「你该用 lo/hi」——
        **它不知道为什么错**。

        用统一语义名问：
          sem.get(card, CI)    不管它是 boot 还是 forest
          sem.get(card, VALUES, 3)  第 4 个数，不管叫 points 还是 vs

        四条约束：
          一、语义名必须**有限**（否则分叉原样重演一遍）
          二、不认识就**明说不认识，不许猜**
             （返回错的值比返回 null 危险得多）
          三、读取是主线，写入要**显式且重新校验**
          四、只做字段映射，**不做单位换算**

        语义集合：labels / values / ci / weight / group / prob / time / status / score
    """.trimIndent()

    /* ── 登记：把已有组件映射到语义 ──────────────────────────── */
    fun bootstrap() {
        // 分组标签
        reg(Mapping("zx_chart", Sem.LABELS, "labels"))
        reg(Mapping("zx_chart", Sem.VALUES, "points"))
        reg(Mapping("zx_pie", Sem.LABELS, "names"))
        reg(Mapping("zx_pie", Sem.VALUES, "v"))
        reg(Mapping("zx_bar", Sem.LABELS, "labels"))
        reg(Mapping("zx_bar", Sem.VALUES, "v"))
        reg(Mapping("zx_histogram", Sem.VALUES, "data"))
        reg(Mapping("zx_box", Sem.LABELS, "n"))
        reg(Mapping("zx_box", Sem.VALUES, "v"))
        reg(Mapping("zx_line", Sem.VALUES, "pts"))
        reg(Mapping("zx_scatter", Sem.VALUES, "pts.[0].y"))

        // 区间
        reg(Mapping("zx_boot", Sem.CI, "lo"))
        reg(Mapping("zx_forest", Sem.CI, "ci[0]"))
        reg(Mapping("zx_bayes", Sem.PROB, "prior"))
        reg(Mapping("zx_sense", Sem.CI, "se", "标准误"))

        // 权重 / 评分
        reg(Mapping("zx_dmatrix", Sem.WEIGHT, "crit[0].w"))
        reg(Mapping("zx_dmatrix", Sem.SCORE, "scores[0][0]"))
        reg(Mapping("zx_psm", Sem.WEIGHT, "units[0].cs[0]"))
        reg(Mapping("zx_psm", Sem.PROB, "units[0].p"))
        reg(Mapping("zx_alloc", Sem.WEIGHT, "ch[0].a"))

        // 时间 / 状态
        reg(Mapping("zx_timeline", Sem.TIME, "steps[0].t"))
        reg(Mapping("zx_timeline", Sem.STATUS, "steps[0].s"))
        reg(Mapping("zx_did", Sem.TIME, "at"))
        reg(Mapping("zx_seqtest", Sem.TIME, "looks[0].n"))
        reg(Mapping("zx_gantt", Sem.TIME, "tasks[0].s"))
        reg(Mapping("zx_gantt", Sem.STATUS, "tasks[0].st"))

        // 分组
        reg(Mapping("zx_balance", Sem.GROUP, "covs[0].a"))
        reg(Mapping("zx_cforest", Sem.GROUP, "subs[0].e"))
        reg(Mapping("zx_anomaly", Sem.VALUES, "data"))
        reg(Mapping("zx_forecast", Sem.VALUES, "hist"))
        reg(Mapping("zx_calib", Sem.PROB, "pts[0].p"))
        reg(Mapping("zx_calib", Sem.STATUS, "pts[0].y"))
        reg(Mapping("zx_abmon", Sem.PROB, "arms[0].p"))
        reg(Mapping("zx_explain", Sem.SCORE, "attrs[0].v"))
        // 第二十八批
        reg(Mapping("zx_forecast", Sem.VALUES, "hist"))
        reg(Mapping("zx_forecast", Sem.TIME, "h", "期数"))
        reg(Mapping("zx_rollout", Sem.STATUS, "stages[0].s"))
        reg(Mapping("zx_rollout", Sem.PROB, "stages[0].p", "百分比/100"))
        reg(Mapping("zx_canary", Sem.SCORE, "metrics[0].b"))
        reg(Mapping("zx_slo", Sem.PROB, "target"))
        reg(Mapping("zx_slo", Sem.VALUES, "days"))
        reg(Mapping("zx_healthcheck", Sem.STATUS, "items[0].s"))
        reg(Mapping("zx_incident", Sem.TIME, "ev[0].t", "分钟"))
        reg(Mapping("zx_incident", Sem.STATUS, "ev[0].k"))
        reg(Mapping("zx_rca", Sem.SCORE, "why[0].a"))
        reg(Mapping("zx_regret", Sem.SCORE, "alts[0].v"))
        reg(Mapping("zx_regret", Sem.PROB, "alts[0].p"))
        reg(Mapping("zx_backtest", Sem.VALUES, "eq"))
        reg(Mapping("zx_drift", Sem.SCORE, "cols[0].psi"))
        reg(Mapping("zx_alert", Sem.STATUS, "alerts[0].lv"))
        reg(Mapping("zx_costtrack", Sem.VALUES, "pts[0].v"))
        reg(Mapping("zx_costtrack", Sem.TIME, "pts[0].d", "天"))
        reg(Mapping("zx_feedback", Sem.SCORE, "items[0].p"))
        reg(Mapping("zx_feedback", Sem.GROUP, "items[0].a"))
        // 第二十九批
        reg(Mapping("zx_capacity", Sem.PROB, "lambda"))
        reg(Mapping("zx_capacity", Sem.SCORE, "mu"))
        reg(Mapping("zx_queue", Sem.PROB, "lambda"))
        reg(Mapping("zx_queue", Sem.SCORE, "mu"))
        reg(Mapping("zx_loadtest", Sem.VALUES, "pts"))
        reg(Mapping("zx_topology", Sem.STATUS, "nodes[0].s"))
        reg(Mapping("zx_changerisk", Sem.SCORE, "factors[0].w"))
        reg(Mapping("zx_playbook", Sem.LABEL, "steps[0].n"))
        reg(Mapping("zx_escalation", Sem.LABEL, "levels[0].who"))
        reg(Mapping("zx_postmortem", Sem.LABEL, "summary"))
        reg(Mapping("zx_knowledge", Sem.LABEL, "items[0].n"))
        reg(Mapping("zx_decisionlog", Sem.LABEL, "items[0].n"))
        reg(Mapping("zx_debt", Sem.SCORE, "items[0].int"))
        reg(Mapping("zx_checkpoint", Sem.PROB, "pts[0].p"))
        // 第三十批
        reg(Mapping("zx_unitecon", Sem.SCORE, "cac"))
        reg(Mapping("zx_unitecon", Sem.PROB, "churn"))
        reg(Mapping("zx_costattrib", Sem.VALUES, "items"))
        reg(Mapping("zx_lineage", Sem.LABEL, "nodes[0].n"))
        reg(Mapping("zx_impact", Sem.STATUS, "items[0].sev"))
        reg(Mapping("zx_dataclass", Sem.LABEL, "cols[0].n"))
        reg(Mapping("zx_retention", Sem.PROB, "items[0].keep"))
        reg(Mapping("zx_workload", Sem.SCORE, "people[0].todo"))
        reg(Mapping("zx_bottleneck", Sem.VALUES, "stages"))
        reg(Mapping("zx_expresult", Sem.PROB, "metrics[0].p"))
        reg(Mapping("zx_compliance", Sem.STATUS, "items[0].s"))
        reg(Mapping("zx_audittrail", Sem.LABEL, "entries[0].who"))
        reg(Mapping("zx_accessreview", Sem.PROB, "items[0].age"))
        // 第三十一批
        reg(Mapping("zx_scenario", Sem.VALUES, "scenarios[0].v"))
        reg(Mapping("zx_montecarlo", Sem.PROB, "sd"))
        reg(Mapping("zx_stress", Sem.STATUS, "items[0].s"))
        reg(Mapping("zx_tornado", Sem.VALUES, "items"))
        reg(Mapping("zx_whatif", Sem.VALUES, "nodes"))
        reg(Mapping("zx_breakeven", Sem.SCORE, "price"))
        reg(Mapping("zx_systemdyn", Sem.VALUES, "s0"))
        reg(Mapping("zx_markov", Sem.PROB, "p[0].r[0]"))
        reg(Mapping("zx_optpath", Sem.VALUES, "edges"))
        reg(Mapping("zx_payoff", Sem.SCORE, "cells[0].a"))
        reg(Mapping("zx_reversesolve", Sem.SCORE, "target"))
        reg(Mapping("zx_realoption", Sem.PROB, "sigma"))
        // 第三十二批
        reg(Mapping("zx_bargain", Sem.SCORE, "mymax"))
        reg(Mapping("zx_auction", Sem.SCORE, "bids[0].v"))
        reg(Mapping("zx_multiissue", Sem.WEIGHT, "issues[0].a"))
        reg(Mapping("zx_pfront", Sem.VALUES, "pts"))
        reg(Mapping("zx_portfolio", Sem.WEIGHT, "items"))
        reg(Mapping("zx_bidcurve", Sem.VALUES, "demand"))
        reg(Mapping("zx_sequential", Sem.SCORE, "now"))
        reg(Mapping("zx_stoprule", Sem.SCORE, "n"))
        reg(Mapping("zx_hedge", Sem.PROB, "p"))
        reg(Mapping("zx_contingent", Sem.STATUS, "plans[0].s"))
        reg(Mapping("zx_escalate", Sem.PROB, "p"))
        reg(Mapping("zx_commit", Sem.SCORE, "cost"))
        // 第三十三批
        reg(Mapping("zx_weather", Sem.TIME, "days[0].d"))
        reg(Mapping("zx_wxradar", Sem.VALUES, "cells"))
        reg(Mapping("zx_aqi", Sem.SCORE, "v"))
        reg(Mapping("zx_news", Sem.TIME, "items[0].ts"))
        reg(Mapping("zx_headline", Sem.LABELS, "pts"))
        reg(Mapping("zx_feed", Sem.STATUS, "items[0].r"))
        reg(Mapping("zx_code", Sem.LABELS, "src"))
        reg(Mapping("zx_codetheme", Sem.LABELS, "themes"))
        reg(Mapping("zx_shell", Sem.LABELS, "hist"))
        reg(Mapping("zx_tty", Sem.STATUS, "tabs[0].st"))
        reg(Mapping("zx_toolcall", Sem.STATUS, "st"))
        reg(Mapping("zx_toolchain", Sem.STATUS, "steps[0].st"))
        reg(Mapping("zx_toolargs", Sem.LABELS, "fields"))
        reg(Mapping("zx_2048", Sem.VALUES, "init"))
        reg(Mapping("zx_tictactoe", Sem.VALUES, "board"))
        // 第三十四批
        reg(Mapping("zx_mapnav", Sem.VALUES, "steps"))
        reg(Mapping("zx_transit", Sem.TIME, "legs[0].dep"))
        reg(Mapping("zx_music", Sem.LABELS, "tracks"))
        reg(Mapping("zx_album", Sem.LABELS, "imgs"))
        reg(Mapping("zx_chatlog", Sem.TIME, "msgs[0].ts"))
        reg(Mapping("zx_contact", Sem.LABELS, "ways"))
        reg(Mapping("zx_agenda", Sem.TIME, "blocks[0].s"))
        reg(Mapping("zx_filemgr", Sem.LABELS, "nodes"))
        reg(Mapping("zx_setup", Sem.STATUS, "steps"))
        reg(Mapping("zx_shop", Sem.SCORE, "price"))
        reg(Mapping("zx_order", Sem.STATUS, "nodes[0].t"))
        reg(Mapping("zx_wallet", Sem.VALUES, "items"))
        reg(Mapping("zx_remote", Sem.LABELS, "keys"))
        reg(Mapping("zx_dialpad", Sem.LABELS, "num"))
        reg(Mapping("zx_tasklist", Sem.STATUS, "tasks"))
        // 第三十五批
        reg(Mapping("zx_fitness", Sem.VALUES, "recs[0].v"))
        reg(Mapping("zx_medicine", Sem.STATUS, "items[0].n"))
        reg(Mapping("zx_parcel", Sem.TIME, "nodes[0].ts"))
        reg(Mapping("zx_ticket", Sem.LABELS, "seats"))
        reg(Mapping("zx_study", Sem.LABELS, "cards"))
        reg(Mapping("zx_plant", Sem.TIME, "plants[0].last"))
        reg(Mapping("zx_parking", Sem.LABELS, "spot"))
        reg(Mapping("zx_habit", Sem.STATUS, "days"))
        reg(Mapping("zx_subscription", Sem.VALUES, "subs"))
        reg(Mapping("zx_pet", Sem.TIME, "pets[0].last"))
        reg(Mapping("zx_utilitymeter", Sem.VALUES, "reads"))
        reg(Mapping("zx_sleep", Sem.VALUES, "nights"))
        reg(Mapping("zx_coupon", Sem.VALUES, "coupons"))
        reg(Mapping("zx_appointment", Sem.TIME, "slots[0].t"))
        // 第三十六批
        reg(Mapping("zx_recipe", Sem.STATUS, "steps"))
        reg(Mapping("zx_bookkeep", Sem.VALUES, "items"))
        reg(Mapping("zx_commute", Sem.TIME, "plans[0].t"))
        reg(Mapping("zx_warranty", Sem.TIME, "items[0].left"))
        reg(Mapping("zx_inspection", Sem.STATUS, "items"))
        reg(Mapping("zx_examprep", Sem.VALUES, "subs"))
        reg(Mapping("zx_tripkit", Sem.STATUS, "items"))
        reg(Mapping("zx_inventory", Sem.VALUES, "items"))
        reg(Mapping("zx_recycle", Sem.LABELS, "items"))
        reg(Mapping("zx_carservice", Sem.VALUES, "items"))
        reg(Mapping("zx_expiry", Sem.TIME, "items[0].left"))
        reg(Mapping("zx_splitbill", Sem.VALUES, "items"))
        reg(Mapping("zx_firstaid", Sem.STATUS, "steps"))
        reg(Mapping("zx_sizing", Sem.LABELS, "rows"))
        reg(Mapping("zx_gift", Sem.VALUES, "items"))
        // 第三十七批
        reg(Mapping("zx_renovation", Sem.STATUS, "stages"))
        reg(Mapping("zx_clinic", Sem.STATUS, "steps"))
        reg(Mapping("zx_rental", Sem.VALUES, "houses"))
        reg(Mapping("zx_repair", Sem.STATUS, "logs"))
        reg(Mapping("zx_credential", Sem.TIME, "items[0].left"))
        reg(Mapping("zx_ship", Sem.STATUS, "nodes"))
        reg(Mapping("zx_interview", Sem.STATUS, "rounds"))
        reg(Mapping("zx_visa", Sem.STATUS, "docs"))
        reg(Mapping("zx_insurance", Sem.VALUES, "covers"))
        reg(Mapping("zx_reimburse", Sem.VALUES, "items"))
        reg(Mapping("zx_relocation", Sem.LABELS, "rooms"))
        reg(Mapping("zx_booking", Sem.STATUS, "slots"))
        // 第三十八批
        reg(Mapping("zx_escort", Sem.LABEL, "recs[0].n"))
        reg(Mapping("zx_lease", Sem.VALUES, "terms"))
        reg(Mapping("zx_overrun", Sem.VALUES, "items"))
        reg(Mapping("zx_vet", Sem.TIME, "recs[0].next"))
        reg(Mapping("zx_photospec", Sem.LABEL, "specs[0].n"))
        reg(Mapping("zx_claim", Sem.STATUS, "ev"))
        reg(Mapping("zx_ivreview", Sem.SCORE, "qs[0].s"))
        reg(Mapping("zx_claimprog", Sem.STATUS, "docs"))
        reg(Mapping("zx_refile", Sem.STATUS, "need"))
        reg(Mapping("zx_damage", Sem.VALUES, "items"))
        reg(Mapping("zx_slotconflict", Sem.WEIGHT, "sides[0].w"))
        reg(Mapping("zx_lostdoc", Sem.STATUS, "steps"))
        // 第三十九批
        reg(Mapping("zx_handoff", Sem.STATUS, "items"))
        reg(Mapping("zx_moveout", Sem.VALUES, "ded"))
        reg(Mapping("zx_vehiclecare", Sem.STATUS, "items"))
        reg(Mapping("zx_medremote", Sem.PROB, "rate"))
        reg(Mapping("zx_housedeal", Sem.VALUES, "nodes"))
        reg(Mapping("zx_schoolup", Sem.STATUS, "need"))
        reg(Mapping("zx_carerota", Sem.GROUP, "slots"))
        reg(Mapping("zx_taxxborder", Sem.VALUES, "items"))
        reg(Mapping("zx_rentreturn", Sem.STATUS, "items"))
        reg(Mapping("zx_dutyrota", Sem.GROUP, "slots"))
        reg(Mapping("zx_annualaudit", Sem.TIME, "items[0].leftH"))
        reg(Mapping("zx_liability", Sem.WEIGHT, "parts[0].pct"))
        // 第四十批
        reg(Mapping("zx_housekeep", Sem.STATUS, "visits"))
        reg(Mapping("zx_finalpay", Sem.VALUES, "issues"))
        reg(Mapping("zx_fosterdaily", Sem.TIME, "days[0].d"))
        reg(Mapping("zx_wardhand", Sem.STATUS, "todo"))
        reg(Mapping("zx_schoolnote", Sem.STATUS, "notes"))
        reg(Mapping("zx_sublet", Sem.GROUP, "items"))
        reg(Mapping("zx_inspecthand", Sem.STATUS, "find"))
        reg(Mapping("zx_projhandover", Sem.GROUP, "open"))
        reg(Mapping("zx_stocktake", Sem.VALUES, "items"))
        reg(Mapping("zx_offboard", Sem.STATUS, "items"))
        reg(Mapping("zx_revoke", Sem.STATUS, "items"))
        reg(Mapping("zx_warrtrack", Sem.TIME, "endH"))
        reg(Mapping("zx_utility", Sem.SCORE, "pts[0].u"))
    }
}
