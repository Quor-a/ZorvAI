package com.zorv.plugin.zxwidget

import org.json.JSONObject

/**
 * 组件族摘要 —— 用收敛结果自动生成 `ui_dsl_spec` 的族级索引。
 *
 * **为什么需要**
 *
 * 组件数到了 347 个。㊳ 自组合解决"往后不手工加"，
 * ㊴ 收敛解决"已加的能不能合并"，但都**没解决查找**：
 *
 *   模型拿到 347 个名字，它怎么知道该用哪个？
 *
 * 现状是 `ui_dsl_spec` 给一个扁平列表。三个问题：
 *
 *   1. **太长**。347 个组件全量下发要吃掉大量上下文，
 *      而模型这一轮真正需要的往往只是"跟分布有关的那些"。
 *   2. **没有层次**。模型看到 `zx_violin` 和 `zx_box` 挨着，
 *      不知道它们都是"看分布"，也不知道该先试哪个。
 *   3. **没有默认**。`zx_bar` `zx_column` `zx_line` 都能画一组数，
 *      模型不知道**挑不出错的选择是哪个**。
 *
 * 所以要有族：**先按族检索，再在族内选**。
 *
 *   family=DIST   → 返回 violin / box / histogram / density / ridge
 *   family=COMPARE → 返回 compare / diff / baseline / slope / dumbbell
 *
 * ── 四条约束 ────────────────────────────────────────────────
 *
 * 一、**族从语义映射自动推，不手工标**。
 *     手工标 347 个组件的族，标到一半就会和语义登记对不上，
 *     而且每加一个新组件都要记得标。这里只认语义集合。
 *
 * 二、**每族要有一个 default**。
 *     族内不知道选哪个时，给一个"挑不出错"的。
 *     挑不出错 = 约束最少、最不容易被校验拦。
 *
 * 三、**族内要给区分点，而不是罗列**。
 *     "violin 和 box 都是看分布"没信息量；
 *     "**violin 能看出双峰、box 不能**"才有。
 *
 * 四、**族是检索索引，不是分类学**。
 *     一个组件可以属于多个族（`zx_chart` 既是 TREND 也是 COMPARE）。
 *     强行让它只属于一族，会导致某些查询路径找不到它。
 */
object ZxFamilies {

    /** 族标识：按"要回答什么问题"分，不按"长什么样"分 */
    enum class F(
        val id: String, val label: String, val question: String,
        val sems: Set<String>, val default: String, val diff: String
    ) {
        TREND("TREND", "趋势", "随时间怎么变？",
            setOf("TIME", "VALUES"), "zx_line",
            "line 看走向；stream 看此消彼长（总量变化）；steparea 看阶梯式跳变"),
        DIST("DIST", "分布", "数据长什么样？",
            setOf("VALUES", "SCORE"), "zx_histogram",
            "**violin / ridge 能看出双峰，box / histogram 不能**；" +
            "蜂群图样本少时最直观；QQ 图专用于判正态"),
        COMPARE("COMPARE", "比较", "谁多谁少？",
            setOf("VALUES", "GROUP", "LABEL"), "zx_bar",
            "bar 比大小；slope 只有两个时间点时最清楚；" +
            "dumbbell 看两端差距；parallel 看多变量"),
        PARTS("PARTS", "构成", "由什么组成？",
            setOf("VALUES", "LABEL"), "zx_pie",
            "pie 看占比；treemap / waffle 看「每一格是一个个体」；sunburst 看嵌套层级；sankey 看流向"),
        RELATE("RELATE", "关系", "两个量什么关系？",
            setOf("VALUES", "SCORE"), "zx_scatter",
            "scatter 看形状；corrmatrix **区分正负相关**（通用热力图区分不出）；" +
            "bland 区分「相关」和「一致」——两法可高度相关却系统性差一个常数"),
        CAUSE("CAUSE", "因果", "是它导致的吗？",
            setOf("SCORE", "PROB"), "zx_causal",
            "causal 显式画假设；did / rdd / iv 各自压着不同假设；" +
            "**sens 回答「结论多容易被推翻」**"),
        DECIDE("DECIDE", "决策", "该选哪个？",
            setOf("VALUES", "LABEL", "SCORE"), "zx_dmatrix",
            "decision 权重可拖（**只给排名，看不出换个权重会不会翻盘**）；" +
            "regret 把事前最优和事后最优分开——当时选对了但结果差，那叫运气不叫错"),
        MONITOR("MONITOR", "监控", "现在还好吗？",
            setOf("STATUS", "PROB", "TIME"), "zx_alert",
            "alert 要**每条都有处置动作**；slo 看可用额度；drift 看要不要重训；" +
            "canary 看局部是否恶化（只看总指标会掩盖）"),
        DIAGNOSE("DIAGNOSE", "诊断", "哪里出问题了？",
            setOf("STATUS", "SCORE"), "zx_incident",
            "incident 看**段间时长**（知道定位慢还是止损慢，改进方向完全不同）；" +
            "rca 一层层往下问（第 1、2 层是技术原因，第 3、4 层才是流程原因）"),
        GOVERN("GOVERN", "治理", "谁能动、能留多久？",
            setOf("LABEL", "STATUS"), "zx_dataclass",
            "dataclass 定敏感级别（**没分级的列按最高敏感处理**）；" +
            "retention 标到期该删的；accessreview 看**多久没审**而不是有什么权限"),
        INPUT("INPUT", "采集", "要用户填什么？",
            setOf("LABEL", "VALUES"), "zx_form",
            "form 通用表单；rate / slider / otp / sign 是专用输入——" +
            "用 form 收集评分，用户看到的是一堆字不是星星"),
        FLOW("FLOW", "流程", "怎么走、卡在哪？",
            setOf("LABEL", "TIME"), "zx_flow",
            "flow 画路径；swimlane 看谁在等谁；" +
            "bottleneck **区分「约束」和「最堵」**——两者经常不是同一个")
    }

    /** 一个组件属于哪些族（按语义集合匹配） */
    fun familiesOf(name: String): List<F> {
        val sems = ZxSem.Sem.values().filter { ZxSem.has(name, it) }
            .map { it.id() }.toSet()
        if (sems.isEmpty()) return emptyList()
        return F.values().filter { f ->
            // 族要求的语义里，组件至少具备一半以上
            val hit = f.sems.count { it in sems }
            hit >= 2 || (f.sems.size == 1 && f.sems.first() in sems)
        }
    }

    /** 族内成员 */
    fun membersOf(f: F): List<String> =
        ZxRegistry.all().map { it.name }.filter { f in familiesOf(it) }

    /**
     * 族摘要：给模型看的紧凑索引。
     *
     * 关键是**每族要有 default**——模型挑不出错的时候有兜底。
     */
    fun brief(f: F? = null): String = buildString {
        val fs = f?.let { listOf(it) } ?: F.values().toList()
        appendLine("组件族摘要（先按族检索，再在族内选）")
        appendLine()
        fs.forEach { x ->
            val ms = membersOf(x)
            appendLine("── ${x.id}　${x.label}　「${x.question}」")
            appendLine("   默认：${x.default}　（共 ${ms.size} 个）")
            appendLine("   区分：${x.diff}")
            val top = ms.filter { it != x.default }.take(8)
            if (top.isNotEmpty()) appendLine("   其余：${top.joinToString(" / ")}" +
                if (ms.size - 9 > 0) " …等 ${ms.size - 9} 个" else "")
            appendLine()
        }
    }

    /**
     * 族摘要 2.0：按使用频率排序族内成员。
     *
     * **为什么需要**
     *
     * 1.0 给了族索引和 default，但族内顺序是**固定的**（按注册顺序）。
     * 359 个组件之后，有些族已经很大了——DIST 族就有十几个成员，
     * 模型拿到一长串还是要从头扫一遍。
     *
     * 而实际使用是**高度倾斜的**：少数几个组件被反复用，
     * 大多数很少被用。把常用的排前面，模型扫两三个就能命中。
     *
     * ── 四条约束 ──────────────────────────────────────────────
     *
     * 一、**频率要衰减**。
     *     上个月用过 20 次、这个月一次没用，不该还排第一。
     *     半衰期 21 天（比反馈学习的 30 天短——组件偏好变得更快）。
     *
     * 二、**default 永远排第一，不受频率影响**。
     *     default 的意义就是"挑不出错"。如果某个高频组件把它挤下去，
     *     模型在没有历史的新场景下就会拿到一个次优的兜底。
     *
     * 三、**新组件要有冷启动曝光**。
     *     没用过的组件永远排最后，就永远不会被用——这是个死循环。
     *     给每个未使用组件一个基础分，保证它至少出现在列表里。
     *
     * 四、**样本不足不改顺序**。
     *     只用过 2 次的组件不能靠这 2 次冲到前面。
     *     次数 < 3 时一律按基础分排（即维持注册顺序）。
     */
    private val usage = LinkedHashMap<String, Long>()   // 组件名 -> 上次使用时间
    private val hits = LinkedHashMap<String, Int>()     // 组件名 -> 累计次数

    fun mark(name: String) {
        usage[name] = System.currentTimeMillis()
        hits[name] = (hits[name] ?: 0) + 1
    }

    /** 衰减权重：半衰期 21 天 */
    private fun decay(ts: Long): Double {
        val days = (System.currentTimeMillis() - ts) / 86_400_000.0
        return Math.pow(0.5, days / 21.0)
    }

    /** 冷启动基础分：保证没用过的组件也出现在列表里 */
    private fun scoreOf(name: String): Double {
        val n = hits[name] ?: 0
        if (n < 3) return 0.30 + n * 0.05      // 冷启动曝光 + 微弱区分
        return 0.30 + (hits[name] ?: 0) * 0.10 * decay(usage[name] ?: 0L) + 0.20
    }

    /**
     * 族内按频率排序。default 强制第一。
     */
    fun ranked(f: F): List<String> {
        val ms = membersOf(f)
        val rest = ms.filter { it != f.default }
            .sortedWith(compareByDescending<String> { scoreOf(it) }
                .thenBy { ms.indexOf(it) })   // 同分按注册顺序，保证稳定
        return listOf(f.default) + rest
    }

    fun rankReport(f: F? = null): String = buildString {
        val fs = f?.let { listOf(it) } ?: F.values().toList()
        appendLine("族内成员按使用频率排序（default 恒第一）")
        appendLine()
        fs.forEach { x ->
            val r = ranked(x)
            appendLine("── ${x.id}　${x.label}（${r.size} 个）")
            r.take(6).forEachIndexed { i, m ->
                val n = hits[m] ?: 0
                appendLine("   ${i + 1}. ${m.padEnd(18)}" +
                    (if (n == 0) "未用过（冷启动）" else "用过 $n 次") +
                    if (m == x.default) "　[default]" else "")
            }
            if (r.size > 6) appendLine("   …等 ${r.size - 6} 个")
            appendLine()
        }
        appendLine("**新组件要有冷启动曝光**——没用过的永远排最后就永远不会被用，")
        appendLine("这是个死循环。所以给未使用组件 0.30 的基础分。")
    }

    /** 按问题反查族：模型说"我想看分布"就能拿到族 */
    fun byQuestion(q: String): List<F> {
        val kw = q.lowercase()
        return F.values().filter {
            kw.contains(it.label) || kw.contains(it.id.lowercase()) ||
            kw.contains(it.question.replace("？", "")) ||
            it.question.any { c -> kw.contains(c.toString()) && kw.length <= 6 }
        }
    }

    /**
     * 给模型的族级 spec。
     *
     * 比全量 spec 短一个数量级：先给族，模型选了族再展开族内。
     */
    fun specFor(f: F): String {
        val ms = membersOf(f)
        return buildString {
            appendLine("族 ${f.id}（${f.label}）—— ${f.question}")
            appendLine("默认用 ${f.default}；挑不出错。")
            appendLine()
            appendLine(f.diff)
            appendLine()
            appendLine("族内 ${ms.size} 个：")
            ms.take(12).forEach { n ->
                val m = ZxRegistry.get(n)
                appendLine("  $n　${m?.label ?: ""}　${ZxDsl.spec(n).take(52)}")
            }
            if (ms.size > 12) appendLine("  …还有 ${ms.size - 12} 个")
        }
    }

    fun render(): String = buildString {
        appendLine("组件族摘要（${F.values().size} 个族 / ${ZxRegistry.all().size} 个组件）")
        appendLine()
        F.values().forEach { f ->
            appendLine("  ${f.id.padEnd(10)} ${f.label}　默认 ${f.default}")
        }
        appendLine()
        appendLine("之前 `ui_dsl_spec` 是一个扁平列表，三个问题：")
        appendLine("  1. **太长**——全量下发吃掉大量上下文")
        appendLine("  2. **没有层次**——不知道 violin 和 box 都是「看分布」")
        appendLine("  3. **没有默认**——不知道挑不出错的选择是哪个")
    }

    fun report(): String = buildString {
        appendLine("组件族摘要（用收敛结果自动生成 ui_dsl_spec 的族级索引）")
        appendLine()
        appendLine("自组合解决「往后不手工加」，收敛解决「已加的能不能合并」，")
        appendLine("但都**没解决查找**：模型拿到几百个名字，怎么知道该用哪个？")
        appendLine()
        appendLine("四条约束：")
        appendLine("  一、**族从语义映射自动推，不手工标**")
        appendLine("  二、**每族要有一个 default**（挑不出错 = 约束最少）")
        appendLine("  三、**族内要给区分点，而不是罗列**")
        appendLine("  四、**族是检索索引，不是分类学**（一个组件可属多族）")
        appendLine()
        appendLine("第三条最容易被做成没用的东西：")
        appendLine("  「violin 和 box 都是看分布」——没信息量")
        appendLine("  「**violin 能看出双峰、box 不能**」——这才是区分点")
    }

    fun demo(): String = buildString {
        appendLine("══ 按问题反查族 ══")
        appendLine()
        listOf("看分布", "谁多谁少", "是它导致的吗", "卡在哪").forEach { q ->
            val fs = byQuestion(q)
            appendLine("  「$q」→ ${fs.joinToString(" / ") { it.id + "(" + it.label + ")" }.ifBlank { "（没匹配到，看全部族）" }}")
        }
        appendLine()
        appendLine("══ 两族的区分点 ══")
        appendLine()
        listOf(F.DIST, F.COMPARE).forEach { f ->
            appendLine("── ${f.id}　${f.label}")
            val ms = membersOf(f)
            appendLine("   默认 ${f.default}；族内 ${ms.size} 个")
            appendLine("   ${f.diff}")
            appendLine()
        }
        appendLine("══ 为什么每族要有 default ══")
        appendLine()
        appendLine("模型在族内挑不出来时，需要一个**约束最少、最不容易被校验拦**的。")
        appendLine("不给 default，它会退回猜一个名字——而猜错一次的代价是一次完整推理。")
    }

    fun validate(o: JSONObject): Pair<Boolean, String> {
        val op = o.optString("op", "")
        if (op !in setOf("list", "brief", "spec", "of", "q", "demo", "report", "guide"))
            return false to "zx_families 的 op=\"$op\" 只支持 list / brief / spec / of / q / demo / report / guide"
        if (op == "spec") {
            val f = o.optString("f")
            if (F.values().none { it.id == f })
                return false to "spec 的 f=\"$f\" 不是已知族（${F.values().joinToString("/") { it.id }}）"
        }
        if (op == "of" && !ZxRegistry.has(o.optString("name")))
            return false to "of 的 name=\"${o.optString("name")}\" 不是已注册组件"
        return true to ""
    }

    fun guide(): String = """
        zx_families(op=list|brief|spec|of|q|demo|report|guide, ...)

        用收敛结果自动生成 ui_dsl_spec 的族级索引。

        自组合解决「往后不手工加」，收敛解决「已加的能不能合并」，
        但都**没解决查找**——模型拿到几百个名字，怎么知道该用哪个？

        现状是 ui_dsl_spec 给一个扁平列表，三个问题：
          1. **太长**——全量下发吃掉大量上下文
          2. **没有层次**——不知道 violin 和 box 都是「看分布」
          3. **没有默认**——不知道挑不出错的选择是哪个

        四条约束：
          一、**族从语义映射自动推，不手工标**
          二、**每族要有一个 default**（挑不出错 = 约束最少）
          三、**族内要给区分点，而不是罗列**
          四、**族是检索索引，不是分类学**（一个组件可属多族）

        第三条最容易被做成没用的东西：
          「violin 和 box 都是看分布」——没信息量
          「**violin 能看出双峰、box 不能**」——这才是区分点

          op=q    按问题反查族（「看分布」→ DIST）
          op=spec 只展开一个族，比全量 spec 短一个数量级
    """.trimIndent()
}
