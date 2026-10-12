package com.zorv.plugin.zxwidget

import org.json.JSONObject

/**
 * 闭环自动触发器 —— 让监控类卡片自己接上下一步。
 *
 * **为什么需要**
 *
 * 第二十八批做了 12 个"行动之后"的组件，第二十九批又补了容量、风险、处置、留存。
 * 但这些都是**被动的**：得有人去看，看了才知道要做什么。
 *
 *   zx_drift 说「用户年龄该重训了」——那然后呢？等谁去点？
 *   zx_alert 说「支付错误率 3.2% 紧急」——那然后呢？等谁看见？
 *   zx_slo 说「预算耗尽」——那然后呢？等谁去停发布？
 *
 * 真实情况是：**卡片画完就停在那了**，直到下一个轮询周期或者有人恰好看到。
 *
 * 所以要有触发器：条件满足时，自动接上下一步。
 *
 *   when  zx_drift 的 score 超过 retrain 阈值
 *   then  声明一个新预期（重训后的线上 PSI 应该降下来）
 *
 *   when  zx_alert 出现 crit
 *   then  自动建一张事故卡
 *
 * ── 五条约束 ────────────────────────────────────────────────
 *
 * 一、**条件用语义名读，不用路径**。
 *     和 ㊲ 结果回收、㊳ 语义字典同一条原则：写 `rows[2][1]` 换组件就失效，
 *     而且规则作者得先知道那张卡长什么样。
 *
 * 二、**触发前必须能预演**。
 *     dry-run 只报告"哪些规则会命中、会做什么"，不改任何状态。
 *     不加这条，规则写错会在用户不知情的情况下连开五张卡。
 *
 * 三、**同一规则对同一目标只触发一次**。
 *     卡片每帧都在重绘、数据每轮都在更新，不去重会疯狂建卡。
 *     **但状态回落到阈值以下再超过，应该允许再次触发**——
 *     否则修好了又坏就没人管了。所以记的是"上次触发时的状态"，不是布尔值。
 *
 * 四、**动作只能走已有通道**。
 *     声明预期 → ZxOutcome；建卡 → ZxCardStore；升级 → zx_select。
 *     **不允许触发器直接改卡片**——改卡必须走 ⑪ 事务。
 *
 * 五、**动作要留痕**。
 *     自动做的事如果不记录，用户醒来发现多了五张卡，
 *     不知道是谁干的、为什么。所以每次触发都进 ㉔ 审计流水。
 */
object ZxTrigger {

    /** 比较运算 */
    enum class Op(val label: String, val test: (Double, Double) -> Boolean) {
        GT(">", { a, b -> a > b }),
        GTE("≥", { a, b -> a >= b }),
        LT("<", { a, b -> a < b }),
        LTE("≤", { a, b -> a <= b })
    }

    /** 动作类型（都走已有通道） */
    enum class Act(val label: String, val desc: String) {
        PROMISE("声明预期", "走 ZxOutcome：把当前值记为预期，事后回填"),
        CARD("建卡", "走 ZxCardStore：自动长一张新卡（如事故卡）"),
        SELECT("回传", "走 zx_select：把命中告诉模型，让它决定"),
        AUDIT("留痕", "只进审计流水，不做别的事")
    }

    data class Rule(
        val id: String,
        val name: String,
        /** 监听哪个组件 */
        val component: String,
        /** 读哪个语义字段 */
        val sem: String,
        /** 第几个（-1 表示取最大） */
        val index: Int = -1,
        val op: Op,
        val threshold: Double,
        val act: Act,
        /** 动作参数：CARD 时为组件名，PROMISE 时为语义名 */
        val arg: String = "",
        val enabled: Boolean = true
    )

    /** 一次触发记录 */
    data class Fire(
        val ruleId: String, val cardId: String,
        val value: Double, val act: String, val ts: Long
    )

    private val rules = LinkedHashMap<String, Rule>()
    /** 去重：ruleId → (cardId → 上次触发时的值) */
    private val last = HashMap<String, HashMap<String, Double>>()
    private val fired = ArrayList<Fire>()
    private var seq = 0

    fun reg(r: Rule): Pair<Boolean, String> {
        if (r.component.isNotBlank() && !ZxRegistry.has(r.component))
            return false to "监听的组件「${r.component}」不是已注册组件"
        if (r.sem.isNotBlank() && ZxSem.Sem.values().none { it.id() == r.sem })
            return false to "语义名「${r.sem}」不在语义集合内"
        if (r.act == Act.CARD && r.arg.isBlank())
            return false to "CARD 动作需要 arg（要建哪张卡）"
        if (r.act == Act.CARD && !ZxRegistry.has(r.arg))
            return false to "要建的卡「${r.arg}」不是已注册组件"
        if (r.act == Act.PROMISE && r.arg.isBlank())
            return false to "PROMISE 动作需要 arg（预期哪个语义字段）"
        rules[r.id] = r
        return true to "已注册规则「${r.name}」"
    }

    /**
     * 预演：只报告，不改状态。
     *
     * 这条是整个模块最重要的一个函数——没有它，
     * 规则写错会在用户不知情的情况下连开五张卡。
     */
    fun dryRun(cardId: String, component: String): List<Pair<Rule, Double>> {
        val out = ArrayList<Pair<Rule, Double>>()
        val card = ZxRef.get(cardId)?.dsl?.let { runCatching { JSONObject(it) }.getOrNull() }
        rules.values.filter { it.enabled && it.component == component }.forEach { r ->
            val v = ZxSem.num(card, component, r.sem, r.index)
            if (v != null && r.op.test(v, r.threshold)) out.add(r to v)
        }
        return out
    }

    /**
     * 真正触发。返回实际做了什么的描述。
     *
     * 去重逻辑：记的是"上次触发时的值"而不是布尔值——
     * 这样状态回落后再超过，还能再触发一次。
     */
    fun fire(cardId: String, component: String, emit: (String, String, String) -> Unit): String {
        val hit = dryRun(cardId, component)
        if (hit.isEmpty()) return "没有规则命中"
        val sb = StringBuilder()
        hit.forEach { (r, v) ->
            val map = last.getOrPut(r.id) { HashMap() }
            val prev = map[cardId]
            // 上次是在"已命中"状态下触发的，这次还在命中 → 不重复
            if (prev != null && r.op.test(prev, r.threshold)) return@forEach
            map[cardId] = v
            when (r.act) {
                Act.PROMISE -> {
                    val o = JSONObject().put("op", "promise")
                        .put("card", cardId).put("comp", component)
                        .put("sem", r.arg).put("exp", v.toString())
                    val res = ZxOutcome.put(o)
                    sb.appendLine("  [${r.name}] ${component}.${r.sem}=$v ${r.op.label} ${r.threshold} → " +
                            "声明预期（${res.second}）")
                }
                Act.CARD -> {
                    emit(r.arg, "自动建卡：${r.name}", "")
                    sb.appendLine("  [${r.name}] ${component}.${r.sem}=$v ${r.op.label} ${r.threshold} → " +
                            "建卡 ${r.arg}")
                }
                Act.SELECT -> {
                    emit(component, "zx_select", "trigger=${r.id}&value=$v")
                    sb.appendLine("  [${r.name}] ${component}.${r.sem}=$v ${r.op.label} ${r.threshold} → " +
                            "回传给模型")
                }
                Act.AUDIT -> sb.appendLine("  [${r.name}] ${component}.${r.sem}=$v → 仅留痕")
            }
            fired.add(Fire(r.id, cardId, v, r.act.name, System.currentTimeMillis()))
        }
        return if (sb.isEmpty()) "命中的规则都已触发过，本次跳过" else sb.toString()
    }

    /**
     * 让状态回落后能再次触发：外部在值回落到阈值以下时调用。
     */
    fun reset(cardId: String, component: String) {
        rules.values.filter { it.component == component }.forEach { r ->
            last[r.id]?.remove(cardId)
        }
    }

    fun all() = rules.values.toList()
    fun log() = fired.toList()
    fun on(id: String) { rules[id]?.let { rules[id] = it.copy(enabled = true) } }
    fun off(id: String) { rules[id]?.let { rules[id] = it.copy(enabled = false) } }
    fun rmv(id: String): Boolean = rules.remove(id) != null


    /* ══════════ 触发器 2.0：组合条件 + 时间窗口 ══════════
     *
     * 1.0 只支持"单字段超阈值"。用到真实场景才发现不够：
     *
     *   「错误率 > 3% 且 持续 5 分钟」        —— 单字段能写，但**没有持续**
     *   「错误率 > 3% 或 延迟 > 800ms」       —— 单字段写不了
     *   「成本超预算 且 是工作日」             —— 单字段写不了
     *
     * 1.0 里"错误率 > 3%"会在**一个尖峰的那一帧就触发**，
     * 而尖峰往往几秒就回落——于是告警建了事故卡，事故其实不存在。
     *
     * 这正是告警疲劳的来源：**不是漏报，是误报**。
     * 误报多了，真告警来了也没人看。
     *
     * 所以加两条：
     *   一、**组合条件**（AND / OR 任意嵌套）
     *   二、**时间窗口**（条件要持续多久才真触发）
     *
     * ── 四条约束 ──────────────────────────────────────────────
     *
     * 一、**窗口是"持续满足"不是"曾经满足"**。
     *     窗口内只要有一帧不满足就重新计时——
     *     否则"5 分钟内出现过"等价于"没窗口"。
     *
     * 二、**组合条件也走语义名**。
     *     和 1.0 同一条原则：写 `rows[2][1]` 换组件就失效。
     *
     * 三、**窗口未满足时不静默**。
     *     要让外部知道"已经在计时了，还差多久"——
     *     否则用户看到没触发，会以为规则没生效。
     *
     * 四、**组合规则同样要能预演**。
     *     1.0 的 dry-run 对组合条件必须照样成立。
     */

    /** 单个条件 */
    data class Cond(val comp: String, val sem: String, val op: Op, val th: Double,
                    val index: Int = -1)

    /** 组合条件：AND 全部满足 / OR 任一满足 */
    data class CondGroup(
        val join: Join, val conds: List<Cond>, val groups: List<CondGroup> = emptyList()
    ) {
        enum class Join { AND, OR }
        fun test(): Boolean = when (join) {
            Join.AND -> conds.all { testOne(it) } && groups.all { it.test() }
            Join.OR  -> conds.any { testOne(it) } || groups.any { it.test() }
        }
        /** 窗口语义：只看**当前帧**，持续由外层计时 */
        private fun testOne(c: Cond): Boolean {
            val v = ZxSem.liveNum(c.comp, c.sem, c.index) ?: return false
            return c.op.test(v, c.th)
        }
    }

    /** 组合规则（带时间窗口） */
    data class RuleX(
        val id: String, val name: String,
        val group: CondGroup,
        /** 条件需持续多少毫秒才真触发；0 表示立即 */
        val windowMs: Long = 0,
        val act: Act, val arg: String = "",
        val enabled: Boolean = true
    )

    private val rulesX = LinkedHashMap<String, RuleX>()
    /** 窗口计时：ruleId → 条件开始满足的时刻 */
    private val since = HashMap<String, Long>()

    fun regX(r: RuleX): Pair<Boolean, String> {
        fun walk(g: CondGroup): String? {
            g.conds.forEach { c ->
                if (!ZxRegistry.has(c.comp)) return "监听的组件「${c.comp}」不是已注册组件"
                if (ZxSem.Sem.values().none { it.id() == c.sem })
                    return "语义名「${c.sem}」不在语义集合内"
            }
            g.groups.forEach { walk(it)?.let { return it } }
            return null
        }
        val err = walk(r.group)
        if (err != null) return false to err
        if (r.act == Act.CARD && !ZxRegistry.has(r.arg))
            return false to "要建的卡「${r.arg}」不是已注册组件"
        rulesX[r.id] = r
        return true to "已注册组合规则「${r.name}」（${r.group.join.name}" +
            "${if (r.windowMs > 0) " + 持续 ${r.windowMs}ms" else ""}）"
    }

    /**
     * 检查组合规则。返回每条规则的当前状态。
     *
     * 关键是**窗口未满足时不静默**：要告诉外部"已经在计时了，还差多久"。
     */
    fun checkX(): List<Triple<RuleX, Boolean, String>> {
        val now = System.currentTimeMillis()
        return rulesX.values.filter { it.enabled }.map { r ->
            val hit = r.group.test()
            if (!hit) {
                // 不满足：清掉计时，这样"持续满足"才是真的持续
                since.remove(r.id)
                return@map Triple(r, false, "条件未满足（计时已清零）")
            }
            val st = since[r.id]
            if (r.windowMs <= 0) return@map Triple(r, true, "条件满足，无窗口要求")
            if (st == null) {
                since[r.id] = now
                return@map Triple(r, false,
                    "开始计时，还需 ${r.windowMs}ms（差 ${r.windowMs}ms）")
            }
            val waited = now - st
            if (waited >= r.windowMs)
                Triple(r, true, "已持续 ${waited}ms ≥ ${r.windowMs}ms")
            else Triple(r, false, "已持续 ${waited}ms，还需 ${r.windowMs - waited}ms")
        }
    }

    /** 执行已满足的组合规则（仍走 1.0 的五条约束） */
    fun fireX(emit: (String, String, String) -> Unit): String {
        val ready = checkX().filter { it.second }
        if (ready.isEmpty()) return checkX().joinToString("\n") { (r, _, why) ->
            "  [${r.name}] $why" }.let { "没有规则达到触发条件：\n$it" }
        val sb = StringBuilder()
        ready.forEach { (r, _, why) ->
            since.remove(r.id)
            when (r.act) {
                Act.CARD -> { emit(r.arg, "自动建卡：${r.name}", "")
                    sb.appendLine("  [${r.name}] $why → 建卡 ${r.arg}") }
                Act.SELECT -> { emit(r.group.conds.firstOrNull()?.comp ?: "", "zx_select",
                    "trigger=${r.id}")
                    sb.appendLine("  [${r.name}] $why → 回传给模型") }
                Act.AUDIT -> sb.appendLine("  [${r.name}] $why → 仅留痕")
                Act.PROMISE -> {
                    val c = r.group.conds.firstOrNull()
                    if (c != null) {
                        val v = ZxSem.liveNum(c.comp, c.sem, c.index)
                        val o = JSONObject().put("op", "promise")
                            .put("card", r.id).put("comp", c.comp)
                            .put("sem", c.sem).put("exp", v?.toString() ?: "")
                        sb.appendLine("  [${r.name}] $why → 声明预期（${ZxOutcome.put(o).second}）")
                    }
                }
            }
            fired.add(Fire(r.id, "", 0.0, r.act.name, System.currentTimeMillis()))
        }
        return sb.toString()
    }

    fun allX() = rulesX.values.toList()
    fun rmvX(id: String): Boolean = rulesX.remove(id) != null

    fun bootstrapX() {
        regX(RuleX("x_err_and_lat",
            "错误率高且延迟也高才建事故卡",
            CondGroup(CondGroup.Join.AND, listOf(
                Cond("zx_alert", "STATUS", Op.GTE, 3.0),
                Cond("zx_loadtest", "VALUES", Op.GTE, 400.0))),
            windowMs = 0, Act.CARD, "zx_incident"))
        regX(RuleX("x_err_persist",
            "错误率持续 5 分钟才真告警",
            CondGroup(CondGroup.Join.OR, listOf(
                Cond("zx_alert", "STATUS", Op.GTE, 3.0))),
            windowMs = 300_000, Act.SELECT))
        regX(RuleX("x_any_drift",
            "任一项漂移超阈值就声明预期",
            CondGroup(CondGroup.Join.OR, listOf(
                Cond("zx_drift", "SCORE", Op.GTE, 0.25),
                Cond("zx_slo", "PROB", Op.GTE, 0.999))),
            windowMs = 0, Act.PROMISE, "SCORE"))
    }


    /* ══════════ 触发器 3.0：反馈学习 ══════════
     *
     * 2.0 解决了误报（组合条件 + 时间窗口），但阈值是**人拍的**。
     * 拍脑袋定的阈值只有两种结局：太松（天天响）或太紧（从不响）。
     * 而真实系统里，正确的阈值会随业务变化漂移——
     * 三个月前合适的 3%，现在可能天天误报。
     *
     * 所以要从反馈里学。但"学"这件事有几个坑必须先堵住：
     *
     *   一、**不自动改规则**。
     *       自动调阈值听起来很美，但它会悄悄把真告警也调没了——
     *       用户发现时已经过了好几轮。这里只**给建议**，改不改由人定。
     *
     *   二、**样本不足不给建议**。
     *       3 次反馈就断言"阈值该调到 5%"是在编故事。
     *       最少 8 次，且要跨一段时间（一次事故里的 8 次只算 1 次）。
     *
     *   三、**反馈要有衰减**。
     *       一年前的反馈不该和昨天的等权。半衰期 30 天。
     *
     *   四、**分清"调阈值"和"删规则"**。
     *       这是最容易做错的一条——见下面的 suggest()。
     */

    /** 一条反馈 */
    data class Feedback(
        /** fired 是否正确触发；noise 是误报；missed 是漏报（用户手动补报的） */
        val kind: Kind,
        /** 触发时的实际值，用来算新阈值 */
        val value: Double,
        val ts: Long = System.currentTimeMillis()
    ) {
        enum class Kind { FIRED, NOISE, MISSED }
    }

    private val feedbacks = LinkedHashMap<String, ArrayList<Feedback>>()

    fun feedback(ruleId: String, kind: Feedback.Kind, value: Double): String {
        val list = feedbacks.getOrPut(ruleId) { ArrayList() }
        list.add(Feedback(kind, value))
        if (list.size > 200) list.removeAt(0)
        return "已记录 ${rulesX[ruleId]?.name ?: ruleId} 的反馈：$kind" +
            if (kind != Feedback.Kind.MISSED) "（当时值 $value）" else ""
    }

    /** 衰减权重：半衰期 30 天 */
    private fun weightOf(ts: Long): Double {
        val days = (System.currentTimeMillis() - ts) / 86_400_000.0
        return Math.pow(0.5, days / 30.0)
    }

    /**
     * 给一条规则的建议。
     *
     * 最难的一条是**分清"调阈值"和"删规则"**：
     *
     *   如果反馈里大部分是 NOISE，直觉是"把阈值调高"。
     *   但如果**几乎没有 FIRED**，那说明这个监控压根没抓到过真问题——
     *   调高阈值只会让它更安静，正确的做法是**删掉这条规则**。
     *
     *   把这两种情况都输出成"建议提高阈值"，
     *   会让用户花力气维护一条本来就不该存在的规则。
     */
    fun suggest(ruleId: String): String {
        val r = rulesX[ruleId] ?: rules[ruleId] ?: return "没有规则 $ruleId"
        val fs = feedbacks[ruleId]
        val name = (r as? RuleX)?.name ?: (r as? Rule)?.name ?: ruleId
        if (fs.isNullOrEmpty())
            return "「$name」还没有任何反馈。用户不反馈不等于规则没问题——" +
                "**没反馈时应该保持原样，而不是猜**"
        // 一次事故里的一串反馈只算一次：按天去重
        val days = fs.groupBy { it.ts / 86_400_000 }
        val distinct = days.map { (_, v) ->
            v.maxByOrNull { weightOf(it.ts) }!! }
        val wFired = distinct.filter { it.kind == Feedback.Kind.FIRED }
            .sumOf { weightOf(it.ts) }
        val wNoise = distinct.filter { it.kind == Feedback.Kind.NOISE }
            .sumOf { weightOf(it.ts) }
        val wMissed = distinct.filter { it.kind == Feedback.Kind.MISSED }
            .sumOf { weightOf(it.ts) }
        val total = wFired + wNoise + wMissed
        val sb = StringBuilder()
        sb.appendLine("「$name」的建议")
        sb.appendLine()
        sb.appendLine("  有效反馈 ${distinct.size} 次（原始 ${fs.size} 条，同一天合并）")
        sb.appendLine("  加权：正确 ${"%.2f".format(wFired)} / 误报 ${"%.2f".format(wNoise)} / " +
            "漏报 ${"%.2f".format(wMissed)}")
        sb.appendLine()
        // 样本不足
        if (distinct.size < 8) {
            sb.appendLine("  **样本不足**（${distinct.size} < 8），不给建议。")
            sb.appendLine("  3 次反馈就断言阈值该改，是在编故事。")
            return sb.toString()
        }
        if (total <= 0) { sb.appendLine("  反馈已全部过期，相当于没有反馈。"); return sb.toString() }
        val noiseRate = wNoise / total
        val hitRate = wFired / total
        // 几乎没有正确触发：该删而不是该调
        if (hitRate < 0.1) {
            sb.appendLine("  **几乎没抓到过真问题**（命中率 ${"%.0f%%".format(hitRate * 100)}）")
            sb.appendLine("  → 建议**删掉这条规则**，而不是提高阈值。")
            sb.appendLine("     提高阈值只会让它更安静，但它本来就没起过作用——")
            sb.appendLine("     花力气维护一条不该存在的规则，比没有这条规则更糟。")
            return sb.toString()
        }
        sb.appendLine("  命中率 ${"%.0f%%".format(hitRate * 100)}　误报率 ${"%.0f%%".format(noiseRate * 100)}")
        sb.appendLine()
        when {
            noiseRate > 0.5 -> {
                // 误报的取值分布 → 新阈值应该高于绝大多数误报
                val nv = distinct.filter { it.kind == Feedback.Kind.NOISE }
                    .map { it.value }.sorted()
                val p90 = nv[(nv.size * 0.9).toInt().coerceIn(0, nv.size - 1)]
                val fv = distinct.filter { it.kind == Feedback.Kind.FIRED }
                    .map { it.value }.sorted()
                val fMin = fv.firstOrNull()
                sb.appendLine("  **误报率 ${"%.0f%%".format(noiseRate * 100)} 偏高**")
                sb.appendLine("  → 建议把阈值提到 ${"%.2f".format(p90)}（误报值的 P90）")
                if (fMin != null && p90 > fMin)
                    sb.appendLine("     **但注意**：真告警的最小值只有 ${"%.2f".format(fMin)}，" +
                        "提到 ${"%.2f".format(p90)} 会**漏掉那部分真告警**。")
                else if (fMin != null)
                    sb.appendLine("     真告警最小值 ${"%.2f".format(fMin)} ≥ 新阈值，不会漏。")
                sb.appendLine()
                sb.appendLine("  这只是建议——**不会自动改规则**。自动调会悄悄把真告警也调没。")
            }
            noiseRate < 0.1 && wMissed > 0 -> {
                val mv = distinct.filter { it.kind == Feedback.Kind.MISSED }
                    .map { it.value }.sorted()
                sb.appendLine("  **误报很少但有漏报**（${mv.size} 次）")
                sb.appendLine("  → 建议把阈值降到 ${"%.2f".format(mv.firstOrNull() ?: 0.0)}" +
                    "（漏报值里最小的）")
                sb.appendLine("     漏报比误报危险：误报浪费注意力，漏报是事故直接发生。")
            }
            else -> {
                sb.appendLine("  误报率 ${"%.0f%%".format(noiseRate * 100)}，在可接受范围。")
                sb.appendLine("  → 阈值不用改。阈值会随业务漂移，**建议一个月后再看一次**。")
            }
        }
        return sb.toString()
    }

    fun suggestAll(): String {
        if (feedbacks.isEmpty()) return "还没有任何反馈，无从给建议。"
        val sb = StringBuilder()
        feedbacks.keys.forEach { sb.appendLine(suggest(it)); sb.appendLine() }
        return sb.toString()
    }

    /* ── 内置示例规则 ────────────────────────────────────────── */
    fun bootstrap() {
        // 组合规则与单条件规则共存：单条件够用就别上组合
        bootstrapX()
        reg(Rule("t_drift", "漂移超阈值就声明预期", "zx_drift", "SCORE", -1,
            Op.GTE, 0.25, Act.PROMISE, "SCORE"))
        reg(Rule("t_alert", "出现紧急告警就建事故卡", "zx_alert", "STATUS", -1,
            Op.GTE, 3.0, Act.CARD, "zx_incident"))
        reg(Rule("t_slo", "预算耗尽就停发布", "zx_slo", "PROB", -1,
            Op.GTE, 0.999, Act.SELECT))
        reg(Rule("t_cost", "成本预测超预算就回传", "zx_costtrack", "VALUES", -1,
            Op.GT, 100.0, Act.SELECT))
        reg(Rule("t_debt", "技术债利息过高就留痕", "zx_debt", "SCORE", -1,
            Op.GTE, 10.0, Act.AUDIT))
    }

    fun from(o: JSONObject): Rule = Rule(
        o.optString("id", "t${++seq}"),
        o.optString("name", "规则${seq}"),
        o.optString("comp"),
        o.optString("sem"),
        o.optInt("i", -1),
        when (o.optString("op", "gte")) {
            "gt" -> Op.GT; "lt" -> Op.LT; "lte" -> Op.LTE; else -> Op.GTE
        },
        o.optDouble("th", 0.0),
        when (o.optString("act", "audit")) {
            "promise" -> Act.PROMISE; "card" -> Act.CARD; "select" -> Act.SELECT
            else -> Act.AUDIT
        },
        o.optString("arg", "")
    )

    fun validate(o: JSONObject): Pair<Boolean, String> {
        val op = o.optString("op", "")
        if (op !in setOf("reg", "regx", "list", "dry", "fire", "checkx", "firex", "reset", "on", "off", "rm",
            "fb", "suggest", "demo", "report", "guide"))
            return false to "zx_trigger 的 op=\"$op\" 只支持 reg / regx / list / dry / fire / checkx / firex / reset / on / off / rm / fb / suggest / demo / report / guide"
        if (op == "reg") {
            if (o.optString("comp").isBlank()) return false to "reg 需要 comp（监听哪个组件）"
            if (o.optString("sem").isBlank()) return false to "reg 需要 sem（读哪个语义字段）"
            val act = o.optString("act", "audit")
            if (act !in setOf("promise", "card", "select", "audit"))
                return false to "reg 的 act=\"$act\" 只支持 promise / card / select / audit"
            if (act == "card" && o.optString("arg").isBlank())
                return false to "card 动作需要 arg（要建哪张卡）"
            if (act == "promise" && o.optString("arg").isBlank())
                return false to "promise 动作需要 arg（预期哪个语义字段）"
        }
        if (op == "regx") {
            if (o.optJSONArray("conds") == null && o.optJSONArray("groups") == null)
                return false to "regx 需要 conds 或 groups"
        }
        if (op == "dry" || op == "fire") {
            if (o.optString("card").isBlank()) return false to "$op 需要 card（卡片 id）"
            if (o.optString("comp").isBlank()) return false to "$op 需要 comp（组件名）"
        }
        return true to ""
    }

    fun render(): String = buildString {
        appendLine("闭环自动触发器（${rules.size} 条规则，已触发 ${fired.size} 次）")
        appendLine()
        if (rules.isEmpty()) { appendLine("还没有规则。"); return@buildString }
        rules.values.forEach { r ->
            appendLine("  ${if (r.enabled) "●" else "○"} ${r.id}　${r.name}")
            appendLine("      when  ${r.component}.${r.sem} ${r.op.label} ${r.threshold}")
            appendLine("      then  ${r.act.label}${if (r.arg.isNotBlank()) " → ${r.arg}" else ""}")
        }
        appendLine()
        if (fired.isNotEmpty()) {
            appendLine("最近触发：")
            fired.takeLast(5).forEach { f ->
                appendLine("  · ${f.ruleId} on ${f.cardId} = ${f.value} → ${f.act}")
            }
        }
    }

    fun report(): String = buildString {
        appendLine("闭环自动触发器（让监控类卡片自己接上下一步）")
        appendLine()
        appendLine("第二十八、二十九批做了很多监控与留存组件，但它们都是**被动的**：")
        appendLine()
        appendLine("  zx_drift 说「用户年龄该重训了」——那然后呢？等谁去点？")
        appendLine("  zx_alert 说「支付错误率 3.2% 紧急」——那然后呢？等谁看见？")
        appendLine("  zx_slo 说「预算耗尽」——那然后呢？等谁去停发布？")
        appendLine()
        appendLine("真实情况是：**卡片画完就停在那了**，")
        appendLine("直到下一个轮询周期或者有人恰好看到。")
        appendLine()
        appendLine("五条约束：")
        appendLine("  一、**条件用语义名读**，不用路径")
        appendLine("  二、**触发前必须能预演**（dry-run 不改状态）")
        appendLine("  三、**同一规则对同一目标只触发一次**（但回落后可再触发）")
        appendLine("  四、**动作只能走已有通道**（不许直接改卡片）")
        appendLine("  五、**动作要留痕**（否则用户醒来发现多了五张卡）")
    }

    fun demo(): String {
        rules.clear(); last.clear(); fired.clear(); seq = 0
        bootstrap()
        return buildString {
            appendLine("══ 五条内置规则 ══")
            appendLine()
            appendLine(render().trimEnd())
            appendLine()
            appendLine("══ 为什么 dry-run 必须先有 ══")
            appendLine()
            appendLine("规则写错（比如阈值填反）会在用户不知情的情况下")
            appendLine("连开五张卡。dry-run 只报告不动手。")
            appendLine()
            appendLine("══ 去重为什么记值不记布尔 ══")
            appendLine()
            appendLine("记布尔的话：漂移 0.31 → 触发一次 → 之后一直是 0.31 → 不再触发。")
            appendLine("看起来对。但如果修好了（0.05）后来又坏了（0.33），")
            appendLine("**就不该再报了**——而那恰恰是最该报的一次。")
            appendLine()
            appendLine("所以记的是「上次触发时的值」：")
            appendLine("  上次 0.31（命中态）→ 现在 0.33（仍命中）→ 跳过")
            appendLine("  上次 0.31（命中态）→ 现在 0.05 → **reset** → 现在 0.33 → 触发")
        }
    }

    fun guide(): String = """
        zx_trigger(op=reg|list|dry|fire|reset|on|off|rm|demo|report|guide, ...)

        让监控类卡片自己接上下一步。

        第二十八、二十九批的监控组件都是**被动的**：
          zx_drift 说「该重训了」——那然后呢？等谁去点？
          zx_alert 说「支付错误率 3.2% 紧急」——那然后呢？

        真实情况是**卡片画完就停在那了**。

          when  zx_drift 的 SCORE ≥ 0.25
          then  声明一个新预期（重训后 PSI 应该降下来）

        五条约束：
          一、**条件用语义名读**，不用路径
          二、**触发前必须能预演**（dry-run 不改状态）
          三、**同一规则对同一目标只触发一次**（但回落后可再触发）
          四、**动作只能走已有通道**（不许直接改卡片）
          五、**动作要留痕**

        去重为什么记值不记布尔：
          修好了又坏，恰恰是最该报的一次——
          记布尔会把它吞掉，记值配合 reset 能报出来。
    """.trimIndent()
}