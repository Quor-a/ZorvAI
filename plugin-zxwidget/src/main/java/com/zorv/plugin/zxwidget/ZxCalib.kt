package com.zorv.plugin.zxwidget

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 推演校准 —— 拿真实结果回填，看当初的推演偏了多少、往哪偏。
 *
 * **为什么需要**
 *
 * 第三十一批做了 12 个模拟与推演组件，第三十二批又加了 12 个决策组件。
 * 然后一个根本问题浮出来：
 *
 *   **推演到底准不准？没人知道。**
 *
 *   蒙特卡洛给出"达标率 66%"，那 66% 是可信的吗？
 *   预测区间 [112, 242]，它真的覆盖 80% 的情况吗？
 *
 * ㉞ 情景快照解决了"同输入能不能得到同输出"（可重复性），
 * 但**可重复不等于准确**——一个系统性偏高的模型，
 * 每次都能复现，每次都是错的。
 *
 * 所以要有校准。四件事：
 *
 *   一、**偏差**（bias）：预测值 - 实际值，看系统性偏向哪边
 *   二、**覆盖率**（coverage）：区间实际覆盖了多少次 vs 名义水平
 *   三、**PIT**：实际值落在预测分布的哪个分位，应该均匀分布
 *   四、**Brier**：概率预测的校准度
 *
 * ── 四条约束 ────────────────────────────────────────────────
 *
 * 一、**回填必须能关联回原预测**。
 *     只存"预测值 + 实际值"的一对数字，看不出是哪次推演、
 *     基于什么参数。这里每次预测给 id，回填用 id 关联。
 *
 * 二、**覆盖率要跟名义水平比，不能跟 100% 比**。
 *     这是最容易做错的一条。80% 区间覆盖了 60% —— 有人会说
 *     "还不错，覆盖了六成"，但正确答案是**过度自信**：
 *     它自称 80% 却只做到 60%。见 coverage()。
 *
 * 三、**过度自信和保守要分开说**。
 *     都输出成"不准"就没用了——这两种的修法完全相反：
 *     过度自信要放宽区间，保守要收窄。
 *
 * 四、**样本不足不下结论**。
 *     3 次覆盖 2 次就说"覆盖率 67%"，那个数字没有任何意义。
 *     最少 10 条才有统计量，且要给出置信区间。
 */
object ZxCalib {

    data class Pred(
        val id: String,
        val name: String,
        val comp: String,
        val point: Double,
        val lo: Double,
        val hi: Double,
        /** 名义覆盖水平，如 0.8 表示"这是 80% 区间" */
        val nominal: Double = 0.8,
        val ts: Long = System.currentTimeMillis()
    )

    data class Actual(val predId: String, val value: Double,
                      val ts: Long = System.currentTimeMillis())

    private val preds = LinkedHashMap<String, Pred>()
    private val actuals = LinkedHashMap<String, Actual>()
    private var seq = 0

    fun add(name: String, comp: String, point: Double, lo: Double, hi: Double,
            nominal: Double = 0.8): String {
        val id = "p${++seq}"
        preds[id] = Pred(id, name, comp, point, lo, hi, nominal.coerceIn(0.1, 0.99))
        return id
    }

    fun resolve(id: String, value: Double): String {
        val p = preds[id] ?: return "找不到预测 $id——**回填必须能关联回原预测**，" +
            "直接存一个实际值看不出它是哪次推演的结果"
        if (actuals.containsKey(id))
            return "预测「${p.name}」已回填过（${"%.2f".format(actuals[id]!!.value)}），" +
                "重复回填会污染统计量。要改请先 del"
        actuals[id] = Actual(id, value)
        val d = value - p.point
        val inside = value >= p.lo && value <= p.hi
        return "已回填「${p.name}」：实际 $value，预测 ${"%.2f".format(p.point)}" +
            "（偏 ${if (d >= 0) "+" else ""}${"%.2f".format(d)}）" +
            if (inside) "，落在区间内 ✓" else "，**落在区间外**"
    }

    fun del(id: String): Boolean = preds.remove(id) != null.also { actuals.remove(id) }

    fun pairs(): List<Pair<Pred, Actual>> =
        actuals.values.mapNotNull { a -> preds[a.predId]?.let { it to a } }

    /**
     * 覆盖率 —— 跟**名义水平**比，不是跟 100% 比。
     *
     * 这是整个模块最容易做错的一处：
     *
     *   80% 区间覆盖 6/10 次
     *     ✗ "还不错，覆盖了六成"      ← 错误
     *     ✓ "**过度自信**：自称 80% 只做到 60%"  ← 正确
     */
    fun coverage(): String {
        val ps = pairs()
        val sb = StringBuilder()
        sb.appendLine("覆盖率（与名义水平比）")
        sb.appendLine()
        if (ps.isEmpty()) { sb.appendLine("还没有回填过任何预测。"); return sb.toString() }
        if (ps.size < 10) {
            sb.appendLine("  **只有 ${ps.size} 条**（< 10），覆盖率没有统计量。")
            sb.appendLine("  3 次里覆盖 2 次就说「覆盖率 67%」，那个数字没有任何意义。")
            sb.appendLine("  先攒够 10 条再看。")
            return sb.toString()
        }
        // 按名义水平分组
        val byNom = ps.groupBy { (it.first.nominal * 100).toInt() }
        byNom.forEach { (nomInt, group) ->
            val nom = nomInt / 100.0
            val hit = group.count { (p, a) -> a.value >= p.lo && a.value <= p.hi }
            val rate = hit.toDouble() / group.size
            val diff = rate - nom
            // Wilson 区间（小样本比正态近似稳）
            val z = 1.96
            val n = group.size.toDouble()
            val denom = 1 + z * z / n
            val centre = (rate + z * z / (2 * n)) / denom
            val half = z * sqrt(rate * (1 - rate) / n + z * z / (4 * n * n)) / denom
            val lo = (centre - half).coerceIn(0.0, 1.0)
            val hi = (centre + half).coerceIn(0.0, 1.0)
            sb.appendLine("  名义 ${"%.0f%%".format(nom * 100)}（${group.size} 条）")
            sb.appendLine("    实际 ${hit}/${group.size} = ${"%.0f%%".format(rate * 100)}")
            sb.appendLine("    95% 区间 [${"%.0f%%".format(lo * 100)}, ${"%.0f%%".format(hi * 100)}]")
            when {
                hi < nom -> sb.appendLine("    → **过度自信**：连置信上界都够不到名义水平，" +
                    "**区间画窄了**，要放宽")
                lo > nom -> sb.appendLine("    → **保守**：连置信下界都超过名义水平，" +
                    "**区间画宽了**，可以收窄换取精度")
                else -> sb.appendLine("    → 校准良好，实际与名义无显著差异")
            }
            sb.appendLine()
        }
        sb.appendLine("**过度自信和保守的修法相反**——都输出成「不准」等于没说。")
        return sb.toString()
    }

    /**
     * 偏差 —— 看系统性偏向哪边。
     *
     * 关键约束：**同向本身就是系统性信号**。
     * 10 次全部低估、平均只差 3% —— 按幅度看"很小"，
     * 但 10/10 同向说明有系统性偏差，不是随机误差。
     */
    fun bias(): String {
        val ps = pairs()
        val sb = StringBuilder()
        sb.appendLine("偏差（系统性偏向哪边）")
        sb.appendLine()
        if (ps.isEmpty()) { sb.appendLine("还没有回填过任何预测。"); return sb.toString() }
        val ds = ps.map { (p, a) -> a.value - p.point }
        val mean = ds.average()
        val sd = if (ds.size > 1) sqrt(ds.map { (it - mean) * (it - mean) }.average()) else 0.0
        val se = if (ds.size > 0) sd / sqrt(ds.size.toDouble()) else 0.0
        val pos = ds.count { it > 0 }
        val neg = ds.count { it < 0 }
        sb.appendLine("  样本 ${ds.size} 条")
        sb.appendLine("  平均偏差 ${if (mean >= 0) "+" else ""}${"%.3f".format(mean)}" +
            "　标准差 ${"%.3f".format(sd)}")
        if (ds.size >= 3 && se > 0)
            sb.appendLine("  t = ${"%.2f".format(mean / se)}" +
                if (abs(mean / se) > 2.0) "　**显著不为 0**" else "　不显著")
        sb.appendLine("  方向：高估 $pos 次（实际 > 预测）/ 低估 $neg 次")
        sb.appendLine()
        // 同向检验：二项分布
        val same = maxOf(pos, neg)
        val sb2 = StringBuilder()
        if (ds.size >= 6 && same.toDouble() / ds.size >= 0.8) {
            sb2.appendLine("  **${same}/${ds.size} 次同向** —— ")
            if (pos > neg) sb2.appendLine("  系统性**低估**。")
            else sb2.appendLine("  系统性**高估**。")
            sb2.appendLine()
            sb2.appendLine("  注意：**同向本身就是系统性信号**，幅度只决定严重性。")
            sb2.appendLine("  10 次全部低估、平均只差 3% —— 按幅度看「很小」，")
            sb2.appendLine("  但 10/10 同向说明有偏，不是随机误差。")
        } else if (ds.size >= 6) {
            sb2.appendLine("  方向没有明显偏向（${same}/${ds.size}），偏差更像随机误差。")
        } else {
            sb2.appendLine("  样本少于 6 条，方向性判不出来。")
        }
        sb.append(sb2)
        return sb.toString()
    }

    /**
     * PIT —— 实际值落在预测分布的哪个分位。
     *
     * 校准良好的预测，PIT 应该**均匀分布**。
     *   集中在两端 → 区间太窄（实际总在外面）
     *   集中在中间 → 区间太宽（实际总在最中心）
     */
    fun pit(): String {
        val ps = pairs()
        val sb = StringBuilder()
        sb.appendLine("PIT（实际值在预测区间中的相对位置）")
        sb.appendLine()
        if (ps.isEmpty()) { sb.appendLine("还没有回填过任何预测。"); return sb.toString() }
        val us = ps.map { (p, a) ->
            val span = p.hi - p.lo
            if (span <= 0) 0.5 else ((a.value - p.lo) / span).coerceIn(0.0, 1.0)
        }.sorted()
        val n = us.size
        val bins = 5
        val h = IntArray(bins)
        us.forEach { h[(it * bins).toInt().coerceIn(0, bins - 1)]++ }
        val mx = h.maxOrNull()?.coerceAtLeast(1) ?: 1
        sb.appendLine("  直方图（0 = 落在下界，1 = 落在上界）：")
        for (b in 0 until bins) {
            val cnt = h[b]
            sb.appendLine("    [${"%.1f".format(b.toDouble() / bins)}-" +
                "${"%.1f".format((b + 1).toDouble() / bins)}] " +
                "█".repeat(cnt * 20 / mx).padEnd(22) + " $cnt")
        }
        sb.appendLine()
        // 均匀性：卡方
        val exp = n.toDouble() / bins
        val chi = if (exp > 0) h.sumOf { (it - exp) * (it - exp) / exp } else 0.0
        sb.appendLine("  卡方 = ${"%.2f".format(chi)}（自由度 ${bins - 1}，" +
            "临界值 9.49 @ α=0.05）")
        val edge = (h[0] + h[bins - 1]).toDouble() / n
        when {
            n < 10 -> sb.appendLine("  样本 ${n} < 10，判不出形状。")
            chi > 9.49 && edge > 0.5 -> sb.appendLine("  → **集中在两端**：实际总在区间外或边缘，" +
                "**区间太窄**")
            chi > 9.49 && us.average() > 0.35 && us.average() < 0.65 ->
                sb.appendLine("  → **集中在中间**：实际总在最中心，" +
                    "**区间太宽**，可以收窄")
            else -> sb.appendLine("  → 分布还算均匀，校准良好。")
        }
        return sb.toString()
    }

    /** 清单 */
    fun render(): String = buildString {
        appendLine("推演校准（${preds.size} 条预测 / ${actuals.size} 条已回填）")
        appendLine()
        if (preds.isEmpty()) { appendLine("（还没有记录过任何预测）"); return@buildString }
        preds.values.forEach { p ->
            val a = actuals[p.id]
            appendLine("  ${p.id}　${p.name}　${p.comp}")
            appendLine("     预测 ${"%.2f".format(p.point)}　" +
                "区间 [${"%.2f".format(p.lo)}, ${"%.2f".format(p.hi)}] " +
                "名义 ${"%.0f%%".format(p.nominal * 100)}")
            if (a != null) {
                val inside = a.value >= p.lo && a.value <= p.hi
                appendLine("     实际 ${"%.2f".format(a.value)}" +
                    "　${if (inside) "✓ 落在区间内" else "✗ **落在区间外**"}")
            } else appendLine("     实际 —— （待回填）")
        }
    }

    fun report(): String = buildString {
        appendLine("推演校准 —— 拿真实结果回填，看当初的推演偏了多少、往哪偏")
        appendLine()
        appendLine("第三十一批做了 12 个模拟组件，第三十二批又加了 12 个决策组件。")
        appendLine("然后一个根本问题浮出来：")
        appendLine()
        appendLine("  **推演到底准不准？没人知道。**")
        appendLine()
        appendLine("蒙特卡洛给出「达标率 66%」，那 66% 是可信的吗？")
        appendLine("预测区间 [112, 242]，它真的覆盖 80% 的情况吗？")
        appendLine()
        appendLine("㉞ 情景快照解决了「同输入能不能得到同输出」（可重复性），")
        appendLine("但**可重复不等于准确**——一个系统性偏高的模型，")
        appendLine("每次都能复现，每次都是错的。")
        appendLine()
        appendLine("四条约束：")
        appendLine("  一、**回填必须能关联回原预测**（用 id，不是只存一对数字）")
        appendLine("  二、**覆盖率要跟名义水平比，不能跟 100% 比**")
        appendLine("  三、**过度自信和保守要分开说**（两者修法相反）")
        appendLine("  四、**样本不足不下结论**（最少 10 条）")
        appendLine()
        appendLine("第二条最容易被忽略，也最致命。见 coverage()。")
    }

    fun demo(): String = buildString {
        // 造一组数据演示覆盖率判定
        appendLine("══ 覆盖率：跟什么比 ══")
        appendLine()
        val cases = listOf(
            "80% 区间覆盖 6/10" to Triple(6, 10, 0.8),
            "80% 区间覆盖 9/10" to Triple(9, 10, 0.8),
            "80% 区间覆盖 8/10" to Triple(8, 10, 0.8)
        )
        cases.forEach { (desc, t) ->
            val (hit, n, nom) = t
            val rate = hit.toDouble() / n
            val diff = rate - nom
            appendLine("  $desc")
            appendLine("    实际 ${"%.0f%%".format(rate * 100)}　" +
                "名义 ${"%.0f%%".format(nom * 100)}　" +
                "差 ${if (diff >= 0) "+" else ""}${"%.0f%%".format(diff * 100)}")
            appendLine("    " + when {
                rate < nom - 0.15 -> "✗ **过度自信**——自称 " +
                    "${"%.0f%%".format(nom * 100)} 只做到 ${"%.0f%%".format(rate * 100)}"
                rate > nom + 0.15 -> "! **保守**——可以收窄换取精度"
                else -> "✓ 校准良好"
            })
            appendLine()
        }
        appendLine("  最容易犯的错：看到 6/10 说「还不错，覆盖了六成」。")
        appendLine("  正确答案是**过度自信**——它自称 80% 却只做到 60%。")
        appendLine()
        appendLine("══ 偏差：同向本身就是信号 ══")
        appendLine()
        appendLine("  10 次全部低估，平均只差 3%")
        appendLine("    按幅度看 → 「很小」")
        appendLine("    按方向看 → **10/10 同向，有系统性偏差**")
        appendLine()
        appendLine("  **幅度只决定严重性，方向一致才决定是不是有偏。**")
    }

    fun guide(): String = """
        zx_calib(op=add|resolve|del|list|coverage|bias|pit|demo|report|guide, ...)

        拿真实结果回填，看当初的推演偏了多少、往哪偏。

        第三十一批做了 12 个模拟组件，第三十二批又加了 12 个决策组件。
        然后一个根本问题浮出来：

          **推演到底准不准？没人知道。**

        蒙特卡洛给出「达标率 66%」，那 66% 是可信的吗？
        预测区间 [112, 242]，它真的覆盖 80% 的情况吗？

        ㉞ 情景快照解决了「同输入能不能得到同输出」（可重复性），
        但**可重复不等于准确**——一个系统性偏高的模型，
        每次都能复现，每次都是错的。

        四条约束：
          一、**回填必须能关联回原预测**（用 id）
          二、**覆盖率要跟名义水平比，不能跟 100% 比**
          三、**过度自信和保守要分开说**（两者修法相反）
          四、**样本不足不下结论**（最少 10 条）

          第二条最容易被忽略，也最致命：
            80% 区间覆盖 6/10
              ✗ "还不错，覆盖了六成"
              ✓ "**过度自信**：自称 80% 只做到 60%"

          op=add      记录一次预测（point + lo/hi + nominal）
          op=resolve  回填实际值
          op=coverage 覆盖率（与名义水平比）
          op=bias     偏差（同向检验）
          op=pit      PIT（应均匀分布）
    """.trimIndent()

    fun validate(o: JSONObject): Pair<Boolean, String> {
        val op = o.optString("op", "")
        val ops = setOf("add", "resolve", "del", "list", "coverage", "bias", "pit",
            "demo", "report", "guide")
        if (op !in ops)
            return false to "zx_calib 的 op=\"$op\" 只支持 ${ops.joinToString(" / ")}"
        if (op == "add") {
            val lo = o.optDouble("lo", Double.NaN)
            val hi = o.optDouble("hi", Double.NaN)
            if (lo.isNaN() || hi.isNaN()) return false to "add 需要 lo 和 hi（预测区间）"
            if (hi <= lo) return false to "add 的 hi=$hi 必须大于 lo=$lo——区间反了"
            if (o.optDouble("point", Double.NaN).isNaN())
                return false to "add 需要 point（点估计）"
            val nom = o.optDouble("nominal", 0.8)
            if (nom <= 0 || nom >= 1)
                return false to "add 的 nominal=$nom 必须在 (0,1)——**名义水平非法，覆盖率无从比对**"
        }
        if (op == "resolve") {
            val id = o.optString("id")
            if (id.isBlank()) return false to "resolve 需要 id——**回填必须能关联回原预测**，" +
                "直接存一个实际值看不出它是哪次推演的结果"
            if (preds[id] == null) return false to "找不到预测 $id"
            if (o.optDouble("value", Double.NaN).isNaN())
                return false to "resolve 需要 value（实际值）"
        }
        return true to ""
    }
}
