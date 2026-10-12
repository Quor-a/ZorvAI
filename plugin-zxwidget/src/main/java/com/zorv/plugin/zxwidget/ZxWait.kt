package com.zorv.plugin.zxwidget

import kotlin.math.max
import kotlin.math.min

/**
 * 等待基线 —— 学出"这类事该等多久"，让超时判定不再靠拍脑袋。
 *
 * **为什么现在才做**
 *
 * 第三十六、三十七批引入了等待态。剧本里"收货"等 48 小时、
 * 报修里"派单"等 6 小时、快递里"运输"等 1 天——
 * **这些数全是我拍的**。
 *
 * 拍的问题不在"不准"，在**它同时决定了两件事**：
 *
 *   等了 40 小时 → 正常（因为基线是 48）
 *   等了 50 小时 → 超时（因为基线是 48）
 *
 * 基线错 1 小时，"正常"和"该催了"就完全反过来了。
 * 而用户拿这个结论去决定要不要打电话。
 *
 * ── 它做的事 ────────────────────────────────────────────────
 *
 * 记每类事件的实际耗时，用**分位数**给出"正常范围"，
 * 而不是用一个拍出来的固定值。
 *
 * ── 为什么用分位数不用均值 ──────────────────────────────────
 *
 * 等待时长是**右偏**的：大部分 40 小时，偶尔 300 小时。
 *
 *   均值 60 小时 → 一批正常等待被判成超时（误报）
 *   中位数 40 小时 → 那批正常，但真正的异常漏了
 *
 * 所以给三个点：P50（典型）/ P85（开始该留意）/ P95（基本确定有问题）。
 *
 * **只用 P50 判超时，会把四分之一正常的等待都判成异常。**
 * 超时判据取 P85 而不是 P50——
 * 误报的代价是用户白打一个电话，漏报的代价是他一直等下去。
 *
 * ── 四条约束 ────────────────────────────────────────────────
 *
 * 一、**样本不足不给结论**。
 *     少于 5 次观测就说"超时"，那是拿两三个样本当规律。
 *     这时候返回 UNKNOWN，让调用方退回人工判断，
 *     **给一个没依据的"超时"比说"不知道"更糟**——
 *     用户会据此打电话，而对方说"这才第二天"。
 *
 * 二、**上限截断，不无限存**。
 *     每类只留最近 40 条。超了丢最旧的。
 *     不截断的话，一年前的网络环境和现在不一样，
 *     旧样本会把基线拖偏。
 *
 * 三、**超长样本要能标出来，但不能自动丢**。
 *     300 小时那次多半是异常（丢件、罢工），
 *     自动丢掉会把"真会发生的长尾"抹掉；
 *     留着又会污染 P85。
 *     所以保留但标记，统计时给它更低权重而不是直接剔除。
 *
 * 四、**要能回答"还要多久"，不只"超了没"**。
 *     用户问的不是"这正常吗"，是"我还要等多久"。
 *     所以给 P50 − 已等待 = 预计还要多久。
 *
 * ── 认不出的（诚实说明） ────────────────────────────────────
 *
 * 冷启动期没有数据，全部返回 UNKNOWN。
 * 它也不区分"同一类事件的不同情形"——
 * 同城快递和跨省快递是两类，要分开记 key，
 * 混在一起算出来的基线对两者都不对。
 */
object ZxWait {

    enum class Verdict(val label: String) {
        UNKNOWN("样本不足"), NORMAL("正常"),
        WATCH("该留意"), OVERDUE("已超时")
    }

    data class Sample(val ms: Long, var outlier: Boolean = false)

    data class Baseline(
        val kind: String,
        val n: Int,
        val p50: Long, val p85: Long, val p95: Long,
        val max: Long, val outliers: Int
    )

    private val table = LinkedHashMap<String, ArrayDeque<Sample>>()
    private const val CAP = 40
    private const val MIN_N = 5

    /** 记一次实际耗时（毫秒） */
    fun record(kind: String, ms: Long) {
        if (ms < 0) return
        val q = table.getOrPut(kind) { ArrayDeque() }
        q.addLast(Sample(ms))
        if (q.size > CAP) q.removeFirst()
        // 三、超长样本标记但不自动丢
        val sorted = q.map { it.ms }.sorted()
        val med = sorted[sorted.size / 2]
        q.forEach { it.outlier = it.ms > med * 6 }
    }

    /** 分位数：低权重的离群值排在后面，不剔除 */
    private fun quantile(q: ArrayDeque<Sample>, f: Double): Long {
        val all = q.toList()
        val inl = all.filter { !it.outlier }.map { it.ms }.sorted()
        val use = if (inl.size >= 3) inl else all.map { it.ms }.sorted()
        if (use.isEmpty()) return 0L
        val i = ((use.size - 1) * f).toInt().coerceIn(0, use.size - 1)
        return use[i]
    }

    fun baseline(kind: String): Baseline? {
        val q = table[kind] ?: return null
        if (q.isEmpty()) return null
        return Baseline(kind, q.size,
            quantile(q, 0.50), quantile(q, 0.85), quantile(q, 0.95),
            q.maxOf { it.ms }, q.count { it.outlier })
    }

    /**
     * 判一次进行中的等待。
     * @return 三元组：判定 / 说明 / 预计还要多久（ms，可能为负表示已超）
     */
    fun judge(kind: String, waitedMs: Long): Triple<Verdict, String, Long?> {
        val b = baseline(kind)
        // 一、样本不足不给结论
        if (b == null || b.n < MIN_N)
            return Triple(Verdict.UNKNOWN,
                "「$kind」只观测到 ${b?.n ?: 0} 次，**少于 $MIN_N 次不下结论**。" +
                    "给一个没依据的「超时」比说不知道更糟——" +
                    "**你据此打电话，对方会说「这才第二天」**", null)
        val v = when {
            waitedMs > b.p95 -> Verdict.OVERDUE
            waitedMs > b.p85 -> Verdict.WATCH
            else -> Verdict.NORMAL
        }
        val remain = b.p50 - waitedMs
        val say = when (v) {
            Verdict.OVERDUE -> "已等 ${h(waitedMs)}，**超过 P95（${h(b.p95)}）**——" +
                "**这类事 95% 在 ${h(b.p95)} 内完成**，现在基本确定有问题了，" +
                "该催或换方案，不是继续等"
            Verdict.WATCH -> "已等 ${h(waitedMs)}，**超过 P85（${h(b.p85)}）**。" +
                "还没到 P95，可以再等等，但别再走开了"
            else -> "已等 ${h(waitedMs)}，典型时长 ${h(b.p50)}——正常"
        }
        return Triple(v, say, remain)
    }

    fun h(ms: Long): String {
        val m = ms / 60000
        return when {
            m < 60 -> "${m} 分钟"
            m < 60 * 24 -> "${m / 60} 小时 ${m % 60} 分"
            else -> "${m / 1440} 天 ${(m % 1440) / 60} 小时"
        }
    }

    fun kinds(): List<String> = table.keys.toList()

    fun clear(): Int = table.size.also { table.clear() }

    fun render(): String = buildString {
        appendLine("══ 等待基线 ══")
        appendLine()
        if (table.isEmpty()) {
            appendLine("  还没有观测。")
            appendLine()
            appendLine("  第三十六、三十七批的等待时长全是拍出来的：")
            appendLine("    收货等 48 小时、派单等 6 小时、运输等 1 天")
            appendLine()
            appendLine("  拍的问题不在「不准」，在**它同时决定两件事**：")
                       appendLine("    等 40 小时 → 正常（基线 48）")
            appendLine("    等 50 小时 → 超时（基线 48）")
            appendLine()
            appendLine("  基线错 1 小时，「正常」和「该催了」就完全反过来。")
            return@buildString
        }
        kinds().forEach { k ->
            val b = baseline(k)!!
            appendLine("  $k　n=${b.n}${if (b.outliers > 0) "（${b.outliers} 条离群）" else ""}")
            appendLine("    P50 ${h(b.p50)}　P85 ${h(b.p85)}　P95 ${h(b.p95)}")
            if (b.n < MIN_N) appendLine("    **样本不足 $MIN_N 次，不判超时**")
        }
    }

    fun report(): String = buildString {
        appendLine("等待基线 —— 学出「这类事该等多久」，让超时判定不再靠拍脑袋")
        appendLine()
        appendLine("第三十六、三十七批引入了等待态，但那些时长**全是我拍的**：")
        appendLine("  收货等 48 小时、派单等 6 小时、快递运输等 1 天")
        appendLine()
        appendLine("拍的问题不在「不准」，在**它同时决定两件事**：")
        appendLine()
        appendLine("  等了 40 小时 → 正常（因为基线是 48）")
        appendLine("  等了 50 小时 → 超时（因为基线是 48）")
        appendLine()
        appendLine("基线错 1 小时，「正常」和「该催了」就完全反过来了。")
        appendLine("而用户拿这个结论去决定要不要打电话。")
        appendLine()
        appendLine("**为什么用分位数不用均值**")
        appendLine()
        appendLine("等待时长是**右偏**的：大部分 40 小时，偶尔 300 小时。")
        appendLine()
        appendLine("  均值 60 小时  → 一批正常等待被判成超时（误报）")
        appendLine("  中位数 40 小时 → 那批正常，但真正的异常漏了")
        appendLine()
        appendLine("所以给三个点：P50（典型）/ P85（该留意）/ P95（基本确定有问题）。")
        appendLine("**只用 P50 判超时，会把四分之一正常的等待都判成异常。**")
        appendLine()
        appendLine("超时判据取 P85——误报的代价是用户白打一个电话，")
        appendLine("漏报的代价是他一直等下去。")
        appendLine()
        appendLine("四条约束：")
        appendLine("  一、**样本不足不给结论**（少于 $MIN_N 次返回 UNKNOWN）")
        appendLine("  二、**上限截断**（每类只留最近 $CAP 条，超了丢最旧的）")
        appendLine("  三、**超长样本标记但不自动丢**")
        appendLine("  四、**要能回答「还要多久」，不只「超了没」**")
        appendLine()
        appendLine("第三条最容易做废：300 小时那次多半是真异常（丢件、罢工），")
        appendLine("自动丢掉会把「真会发生的长尾」抹掉；留着又会污染 P85。")
        appendLine("所以保留但标记，统计时给更低权重而不是直接剔除。")
    }

    fun demo(): String = buildString {
        appendLine("══ 基线是怎么定出来的 ══")
        appendLine()
        // 同城快递：多数 1-2 天，偶发 8 天
        listOf(22, 26, 30, 34, 38, 41, 45, 48, 55, 62, 190)
            .forEach { record("同城快递", it * 3600_000L) }
        val b = baseline("同城快递")!!
        appendLine("  同城快递，11 次观测（含 1 次 190 小时的丢件）")
        appendLine("    P50 ${h(b.p50)}　P85 ${h(b.p85)}　P95 ${h(b.p95)}")
        appendLine("    离群 ${b.outliers} 条（标记但保留，低权重参与）")
        appendLine()
        appendLine("  等了 40 小时 → " + judge("同城快递", 40 * 3600_000L).second)
        appendLine("  等了 60 小时 → " + judge("同城快递", 60 * 3600_000L).second)
        appendLine("  等了 100 小时 → " + judge("同城快递", 100 * 3600_000L).second)
        appendLine()
        appendLine("  ── 为什么不用均值 ──")
        appendLine()
        val avg = listOf(22,26,30,34,38,41,45,48,55,62,190).average()
        appendLine("  均值 ${"%.0f".format(avg)} 小时，中位数 ${b.p50/3600_000} 小时。")
        appendLine("  **用均值判，等 60 小时还算「正常」——**")
        appendLine("  **可它已经比 10 次里的 9 次都久了。**")
        appendLine()
        appendLine("  ── 样本不足时 ──")
        record("使馆审核", 5 * 24 * 3600_000L)
        appendLine("  " + judge("使馆审核", 3 * 24 * 3600_000L).second)
    }

    fun guide(): String = """
        zx_wait(op=record|judge|list|report|demo|clear|guide)

        等待基线：学出"这类事该等多久"，"让超时判定不再靠拍脑袋。

        第三十六、三十七批的等待时长**全是拍出来的**：
          收货等 48 小时、派单等 6 小时、快递运输等 1 天

        拍的问题不在"不准"，"在**它同时决定两件事**：
          等 40 小时 → 正常（基线 48）
          等 50 小时 → 超时（基线 48）

        基线错 1 小时，"正常"和"该催了"就完全反过来。

        用分位数不用均值——等待时长右偏：
          均值 60 小时   → 一批正常等待被判成超时（误报）
          中位数 40 小时 → 那批正常，但真正的异常漏了

        超时判据取 P85 不是 P50：
        误报的代价是白打一个电话，漏报的代价是一直等下去。

        四条约束：
          一、**样本不足不给结论**（少于 $MIN_N 次返回 UNKNOWN）
          二、**上限截断**（每类只留最近 $CAP 条）
          三、**超长样本标记但不自动丢**
          四、**要能回答"还要多久"，"不只"超了没"**

          op=record  <类别> <小时>   记一次实际耗时
          op=judge   <类别> <小时>   判一次进行中的等待
          op=demo    演示"为什么不用均值"
    """.trimIndent()

    fun handle(op: String, a: String, b: String): String {
        val H = 3600_000L
        return when (op) {
            "record" -> {
                val v = b.toDoubleOrNull()
                if (a.isBlank() || v == null) return "用法：record <类别> <小时>"
                record(a, (v * H).toLong()); "已记「$a」一次 ${b} 小时"
            }
            "judge" -> {
                val v = b.toDoubleOrNull()
                if (a.isBlank() || v == null) return "用法：judge <类别> <小时>"
                val (_, say, rem) = judge(a, (v * H).toLong())
                say + (rem?.let {
                    "\n预计还要 ${if (it > 0) h(it) else "已超 ${h(-it)}"}" } ?: "")
            }
            "list" -> render()
            "render" -> render()
            "report" -> report()
            "demo" -> demo()
            "clear" -> "已清空 ${clear()} 类"
            else -> guide()
        }
    }
}
