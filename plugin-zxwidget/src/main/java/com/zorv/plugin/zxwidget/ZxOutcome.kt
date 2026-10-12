package com.zorv.plugin.zxwidget

import org.json.JSONObject

/**
 * 结果回收协议 —— 让 311 个组件都参与闭环。
 *
 * **为什么需要**
 *
 * 第二十八批做完了"行动之后"的十二个组件，但有个更根本的问题：
 *
 *   **那十二个组件是特例，其余 299 个还是开环的。**
 *
 *   zx_forecast 画了预测，之后没人回填实际值
 *   zx_decision 帮人选了方案，之后没人记录选得对不对
 *   zx_alloc 分了预算，之后没人看花得怎么样
 *   zx_drift 说该重训了，之后没人确认重训了没有
 *
 * 每一张卡都停在"发出去的那一刻"。
 *
 * 这不是小事。模型每轮都在**凭记忆推测上一轮发生了什么**：
 *
 *   第 5 轮  模型：预测下周单量 320
 *   第 20 轮 模型：根据上周的数据……
 *   　　　　 （它不知道第 5 轮那个 320 后来对不对）
 *
 * 如果第 5 轮那次预测实际是 380（低估了 18% 而且一直是低估），
 * 这个信息对第 20 轮的预测很重要——但它在界面上从来没被记录过。
 *
 * 所以要有协议：**卡片可以声明"我预期什么"，事后回填"实际是什么"**。
 *
 *   预期：由卡片上某个字段担任（用 ZxSem 的语义名指定，不用记路径）
 *   实际：事后回填一个数值
 *   偏差：自动算，并判断是否**有偏**（不只是误差大）
 *
 * ── 五条约束 ────────────────────────────────────────────────
 *
 * 一、**预期字段用语义名指定，不用路径**。
 *     直接写 `rows[2][1]` 有两个问题：组件一换路径就失效，
 *     而且模型得先知道那张卡长什么样。用语义名（VALUES / CI / SCORE）
 *     就跟具体结构解耦了——这正是 ZxSem 存在的意义。
 *
 * 二、**回填只追加，不覆盖**。
 *     预测可能被修正多次（v1 说 320、v2 说 350），实际值也可能
 *     先记错后更正。覆盖会丢掉历史，而"修正过几次"本身就是信息。
 *     所以要留版本链，最新的一条生效。
 *
 * 三、**回填不算偏差，直到被显式结算**。
 *     填了实际值不等于这次预测已经结束——数据可能还没收全。
 *     自动结算会让用户在数据不全时看到一个偏差数字并信以为真。
 *
 * 四、**有偏和误差大要分开报**。
 *     这是 zx_feedback 那条同理的结论，但它适用于所有组件：
 *     误差大（MAE 高）说明不准；有偏（平均偏差明显 ≠ 0）说明**方向性错误**。
 *     方向性错误更严重，因为它不会互相抵消。
 *
 * 五、**只记录和建议，不改卡片**。
 *     和 ㊱ 求解界面同一条原则：改卡必须走 ⑪ 事务，
 *     否则撤销栈、审计流水、冲突合并全被绕过。
 */
object ZxOutcome {

    /** 一次预期声明 */
    data class Promise(
        val id: String,
        /** 哪张卡 */
        val cardId: String,
        /** 那个组件 */
        val component: String,
        /** 预期什么：语义名 */
        val sem: String,
        /** 第几个（可选） */
        val index: Int = -1,
        /** 预期值（声明时读取） */
        val expected: String,
        /** 人能看懂的一句话 */
        val label: String,
        val ts: Long = System.currentTimeMillis()
    ) {
        fun num() = expected.toDoubleOrNull()
    }

    /** 一次回填 */
    data class Actual(
        val pid: String,
        val value: Double,
        val note: String = "",
        val ts: Long = System.currentTimeMillis()
    )

    /** 结算结果 */
    data class Settlement(
        val p: Promise,
        /** 生效的实际值（最后一条） */
        val actual: Double,
        val gap: Double,
        /** 相对偏差 */
        val rel: Double,
        val versions: Int,
        val settledAt: Long
    )

    private val promises = LinkedHashMap<String, Promise>()
    private val actuals = LinkedHashMap<String, ArrayList<Actual>>()
    private val settled = LinkedHashMap<String, Settlement>()
    private var seq = 0

    fun promise(p: Promise): Promise {
        promises[p.id] = p
        return p
    }

    fun put(o: JSONObject): Pair<Boolean, String> {
        val card = o.optString("card")
        if (card.isBlank()) return false to "promise 需要 card（卡片 id）"
        val comp = o.optString("comp")
        if (comp.isBlank()) return false to "promise 需要 comp（组件名）"
        if (!ZxRegistry.has(comp)) return false to "comp=\"$comp\" 不是已注册组件"
        val sem = o.optString("sem")
        if (sem.isBlank()) return false to "promise 需要 sem（语义名）"
        if (ZxSem.Sem.values().none { it.id() == sem })
            return false to "sem=\"$sem\" 不在语义集合内"
        // 预期值必须当场读出来——不读的话，事后卡片变了就对不上了
        val exp = o.optString("exp")
        if (exp.isBlank())
            return false to "promise 需要 exp（预期值）——**不记下预期值，事后无从比较**"
        val id = "p${++seq}"
        promise(Promise(id, card, comp, sem, o.optInt("i", -1), exp,
            o.optString("label", "$comp.$sem 预期 $exp")))
        return true to "已声明 $id：$comp 的「${sem}」预期 $exp"
    }

    /** 回填：追加不覆盖 */
    fun actual(o: JSONObject): Pair<Boolean, String> {
        val pid = o.optString("p")
        val p = promises[pid]
            ?: return false to "找不到预期 $pid——是不是还没声明过？"
        val v = o.optDouble("v", Double.NaN)
        if (v.isNaN()) return false to "实际值 v 不是数值"
        val list = actuals.getOrPut(pid) { ArrayList() }
        list.add(Actual(pid, v, o.optString("note", "")))
        return true to "已回填 $pid 实际 $v（第 ${list.size} 次" +
                if (list.size > 1) "，之前那次是 ${list[list.size - 2].value}）" else "）"
    }

    /** 结算：只有显式调用才算，因为数据可能还没收全 */
    fun settle(pid: String): Pair<Boolean, String> {
        val p = promises[pid] ?: return false to "找不到预期 $pid"
        val list = actuals[pid]
            ?: return false to "$pid 还没回填实际值——**填了才结算，不要提前**"
        val a = list.last()
        val e = p.num() ?: return false to "${p.label} 的预期值「${p.expected}」不是数值，算不了偏差"
        val gap = a.value - e
        val rel = gap / kotlin.math.abs(e).coerceAtLeast(1e-9)
        settled[pid] = Settlement(p, a.value, gap, rel, list.size, System.currentTimeMillis())
        return true to "结算 $pid：预期 $e，实际 ${a.value}，偏差 ${"%+.2f".format(gap)}" +
                "（${"%+.1f".format(rel * 100)}%）"
    }

    /** 还没回填的 */
    fun pending() = promises.values.filter { it.id !in settled }

    /** 已结算的 */
    fun done() = settled.values.toList()

    /**
     * 有偏检测：不只是误差大，而是**方向一致**。
     *
     * 这是整个协议最该输出的一条结论。
     */
    fun biasReport(): String = buildString {
        val ds = done()
        if (ds.isEmpty()) { appendLine("还没有任何结算——闭环一圈都没走完。"); return@buildString }
        val gaps = ds.map { it.rel }
        val mean = gaps.average()
        val mae = gaps.map { kotlin.math.abs(it) }.average()
        // 符号一致性：全都同向才算有偏
        val pos = gaps.count { it > 0 }; val neg = gaps.count { it < 0 }
        val same = maxOf(pos, neg).toDouble() / gaps.size
        appendLine("结果回收（${ds.size} 次结算）")
        appendLine()
        appendLine("  平均偏差　${"%+.1f".format(mean * 100)}%")
        appendLine("  平均绝对误差　${"%.1f".format(mae * 100)}%")
        appendLine("  同向比例　${"%.0f".format(same * 100)}%（$pos 次高估 / $neg 次低估）")
        appendLine()
        when {
            mae < 0.05 -> appendLine("  误差很小，预测可信。")
            // 同向本身就是系统性信号——不要求幅度大
            // 幅度只决定严重性，不决定"是不是有偏"
            same >= 0.8 ->
                appendLine("  **有偏**：${maxOf(pos, neg)}/${gaps.size} 次都往" +
                        "${if (mean > 0) "高" else "低"}的方向偏，" +
                        "平均 ${"%+.1f".format(mean * 100)}%。\n" +
                        "  幅度只有 ${"%.1f".format(kotlin.math.abs(mean) * 100)}%，" +
                        "但**方向一致就不是运气**——它会一直往同一个方向错。")
            else -> appendLine("  误差偏大但方向不一致——是随机误差，不是系统性偏差。")
        }
        appendLine()
        appendLine("**有偏比误差大更严重**：误差大会互相抵消，")
        appendLine("有偏会一直往同一个方向错，而且越积越多。")
    }

    fun render(): String = buildString {
        if (promises.isEmpty()) { appendLine("还没有任何预期声明。"); return@buildString }
        appendLine("结果回收（${promises.size} 条声明，${settled.size} 条已结算）")
        appendLine()
        promises.values.forEach { p ->
            val s = settled[p.id]
            val list = actuals[p.id]
            val mark = when {
                s != null -> if (kotlin.math.abs(s.rel) < 0.05) "✓" else "!"
                list != null -> "~"
                else -> "·"
            }
            appendLine("  $mark ${p.label}")
            if (s != null) {
                appendLine("      预期 ${p.expected}　实际 ${s.actual}")
                appendLine("      偏差 ${"%+.2f".format(s.gap)}（${"%+.1f".format(s.rel * 100)}%）" +
                        if (list!!.size > 1) "　回填过 ${list.size} 次" else "")
            } else if (list != null) {
                appendLine("      已回填 ${list.last().value}（第 ${list.size} 次）——**还没结算**")
            } else {
                appendLine("      待回填")
            }
        }
        appendLine()
        val pd = pending()
        if (pd.isNotEmpty()) {
            appendLine("待处理 ${pd.size} 条：")
            pd.forEach { appendLine("  · ${it.label}") }
        }
    }

    fun validate(o: JSONObject): Pair<Boolean, String> {
        val op = o.optString("op", "")
        if (op !in setOf("promise", "actual", "settle", "list", "bias", "demo", "report", "guide"))
            return false to "zx_outcome 的 op=\"$op\" 只支持 promise / actual / settle / list / bias / demo / report / guide"
        if (op == "promise") {
            if (o.optString("card").isBlank()) return false to "promise 需要 card（卡片 id）"
            if (o.optString("comp").isBlank()) return false to "promise 需要 comp（组件名）"
            if (o.optString("sem").isBlank()) return false to "promise 需要 sem（语义名）"
            // 不记预期值，事后无从比较
            if (o.optString("exp").isBlank())
                return false to "promise 需要 exp（预期值）——**不记下预期值，" +
                                "事后卡片变了就永远对不上了**"
        }
        if (op == "actual") {
            if (o.optString("p").isBlank()) return false to "actual 需要 p（预期 id）"
            if (o.optDouble("v", Double.NaN).isNaN())
                return false to "actual 需要数值 v——**要留空就别调**，调了就必须是有效数值"
        }
        if (op == "settle" && o.optString("p").isBlank()) return false to "settle 需要 p"
        return true to ""
    }

    fun demo(): String {
        promises.clear(); actuals.clear(); settled.clear(); seq = 0
        // 6 次预测，全部低估 → 有偏
        val data = listOf(210 to 228, 235 to 244, 260 to 273, 288 to 305, 312 to 331, 340 to 362)
        val ids = ArrayList<String>()
        data.forEachIndexed { k, (e, a) ->
            val id = "p${++seq}"
            ids.add(id)
            promise(Promise(id, "card_fc_$k", "zx_forecast", "values", k, e.toString(),
                "第 ${k + 1} 月单量预测 $e"))
            actuals.getOrPut(id) { ArrayList() }.add(Actual(id, a.toDouble()))
        }
        // 第 2 次回填过两版（先记错了，后更正）
        actuals[ids[1]]!!.add(Actual(ids[1], 241.0, "第一次记错，实际是 244"))
        ids.forEach { settle(it) }

        return buildString {
            appendLine("══ 六次预测，全部低估 ══")
            appendLine()
            appendLine(render().trimEnd())
            appendLine()
            appendLine("══ 有偏检测：最该看的一条 ══")
            appendLine()
            appendLine(biasReport().trimEnd())
            appendLine()
            appendLine("注意第 2 条：回填过 2 次（241 后来更正为 244）。")
            appendLine("**回填只追加不覆盖**——覆盖会丢掉「修正过几次」这个信息，")
            appendLine("而经常修正本身就是数据质量问题的信号。")
        }
    }

    fun report(): String = buildString {
        appendLine("结果回收协议（让 311 个组件都参与闭环）")
        appendLine()
        appendLine("第二十八批做完了 12 个「行动之后」的组件，")
        appendLine("但有个更根本的问题：**那 12 个是特例，其余 299 个还是开环的**。")
        appendLine()
        appendLine("  zx_forecast 画了预测，之后没人回填实际值")
        appendLine("  zx_decision 帮人选了方案，之后没人记录选得对不对")
        appendLine("  zx_alloc 分了预算，之后没人看花得怎么样")
        appendLine()
        appendLine("于是模型每轮都在**凭记忆推测上一轮发生了什么**：")
        appendLine("  第 5 轮   预测下周单量 320")
        appendLine("  第 20 轮  根据上周的数据……（它不知道那个 320 后来对不对）")
        appendLine()
        appendLine("如果那次实际是 380 而且**一直是低估**，这个信息")
        appendLine("对第 20 轮很重要——但界面上从来没记录过。")
        appendLine()
        appendLine("五条约束：")
        appendLine("  一、预期字段用**语义名**指定，不用路径")
        appendLine("  二、回填**只追加不覆盖**（修正次数本身是信息）")
        appendLine("  三、**回填不算偏差，直到显式结算**（数据可能没收全）")
        appendLine("  四、**有偏和误差大要分开报**（方向性错误更严重）")
        appendLine("  五、**只记录和建议，不改卡片**（改卡走事务）")
    }

    fun guide(): String = """
        zx_outcome(op=promise|actual|settle|list|bias|demo|report|guide, ...)

        让 311 个组件都参与闭环：声明预期 → 回填实际 → 算偏差。

        第二十八批做完 12 个「行动之后」的组件，
        但**那 12 个是特例，其余 299 个还是开环的**：

          zx_forecast 画了预测，之后没人回填实际值
          zx_decision 帮人选了方案，之后没人记录选得对不对

        于是模型每轮都在**凭记忆推测上一轮发生了什么**。
        如果那次实际是 380 而且**一直是低估**，
        这个信息对后续很重要——但界面上从来没记录过。

        三条动作：
          promise  声明：card + comp + sem + exp（**必须当场记下预期值**）
          actual   回填：p + v（**只追加不覆盖**）
          settle   结算：p（**填了才结算，不要提前**）

        五条约束：
          一、预期字段用**语义名**指定，不用路径
          二、回填**只追加不覆盖**（修正次数本身是信息）
          三、**回填不算偏差，直到显式结算**
          四、**有偏和误差大要分开报**（方向性错误更严重）
          五、**只记录和建议，不改卡片**（改卡走事务）
    """.trimIndent()
}