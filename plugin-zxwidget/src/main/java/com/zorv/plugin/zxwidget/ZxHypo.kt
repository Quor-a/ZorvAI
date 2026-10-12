package com.zorv.plugin.zxwidget

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 假设检验界面 + 多重比较校正 —— 把"p=0.03"变成能追问的界面。
 *
 * **为什么需要假设检验界面**
 *
 * 第二十五批的 `zx_test` 给了 p 值、效应量、可拖的样本量，
 * 但它仍然是**一张静态卡**：用户看到 p=0.032，
 * 接下来想问的三个问题，卡答不了：
 *
 *   一、"这个 p 稳不稳？"
 *       换一批样本还是这个结论吗？
 *       → 需要看置信区间和重采样。
 *
 *   二、"换个方法还是这个结论吗？"
 *       t 检验显著、秩和不显著，该信哪个？
 *       → 需要同时跑多个方法并排比较。
 *
 *   三、"效应有多大？"
 *       p 显著但 d=0.05，这个结论有意义吗？
 *       → 需要效应量及其解读（而不只是一个数字）。
 *
 * 这三个问题的共同点是：**都是"在多大范围内成立"，
 * 而不是"成不成立"**。所以要做成一个能追问的界面，
 * 而不是一个显示 p 值的文本框。
 *
 * **为什么需要多重比较校正**
 *
 * 这是假设检验最常踩的坑，而它单独靠"小心点"是躲不掉的：
 *
 *   二十个指标逐个检验，α=0.05 下**期望就有一个假阳性**。
 *
 * 不做校正，AI 会从一堆噪声里"发现"一个显著指标，
 * 并且言之凿凿地报告给用户——用户还以为是真的发现。
 * 这不是概率问题，是**必然发生**的问题。
 *
 * 两种校正各有适用场景：
 *
 *   Bonferroni  严格，控制族系错误率（FWER）
 *               代价是很容易一个都不显著
 *   BH (FDR)    不那么严格，控制错误发现率
 *               默认的推荐，因为"发现里有一定比例的假阳性"通常可接受
 *
 * 关键：**不校正要显式标出来**，让用户知道自己看的是裸 p。
 *
 * ── 三条约束 ────────────────────────────────────────────────
 *
 * 一、**同时给 p 和效应量，且要解读**。
 *     只给 p 是个陷阱：样本一大什么都有显著性，
 *     效应却可能小到没有任何实际意义。
 *     只给效应量也不行：不知道这个效应是不是噪声。
 *     两者必须一起看，并且要给出"小 / 中 / 大"的定性解读——
 *     否则用户拿到 d=0.34 也不知道算大算小。
 *
 * 二、**校正方式要显式**。
 *     三种方式（不校正 / Bonferroni / BH）给出的是**不同的结论**，
 *     界面必须说清当前用的是哪一种。
 *     默认给 BH 而不是"不校正"——默认不校正是把坑留给用户。
 *
 * 三、**方法切换不能改变数据，只改变算法**。
 *     切 t / 秩和 / 置换时，用的是同一批样本。
 *     如果每种方法各自读一份数据，切换后结论不一致
 *     就分不清是方法差异还是数据差异了。
 */
object ZxHypo {

    enum class Method(val label: String, val desc: String) {
        T("t 检验", "假设正态，样本足够时最常用"),
        RANK("秩和", "不假设正态，只看次序，抗离群"),
        PERM("置换", "不假设任何分布，直接算精确 p")
    }

    enum class Corr(val label: String, val desc: String) {
        NONE("不校正", "裸 p——20 个指标里期望有 1 个假阳性"),
        BONF("Bonferroni", "α/m，控制族系错误率，严格"),
        BH("BH (FDR)", "控制错误发现率，默认推荐")
    }

    /** 一个检验项 */
    data class Item(
        val name: String,
        val a: List<Double>,
        val b: List<Double>
    ) {
        fun diff() = a.average() - b.average()

        fun pooledSd(): Double {
            val m1 = a.average(); val m2 = b.average()
            val s1 = if (a.size > 1) sqrt(a.map { (it - m1) * (it - m1) }.average()) else 0.0
            val s2 = if (b.size > 1) sqrt(b.map { (it - m2) * (it - m2) }.average()) else 0.0
            return sqrt(((a.size - 1) * s1 * s1 + (b.size - 1) * s2 * s2) /
                maxOf(1, a.size + b.size - 2).toDouble()).coerceAtLeast(1e-9)
        }

        /** Cohen's d：效应量，和 p 必须一起看 */
        fun cohensD() = diff() / pooledSd()

        fun rankP(): Pair<Double, Double> {
            // 简化的秩和正态近似：用秩次均值差构造 z
            val all = (a + b).sorted()
            val ra = a.map { v -> all.indexOf(v).toDouble() + 1.0 }
            val n1 = a.size.toDouble(); val n2 = b.size.toDouble()
            val mu = n1 * (n1 + n2 + 1) / 2.0
            val sd = sqrt(n1 * n2 * (n1 + n2 + 1) / 12.0).coerceAtLeast(1e-9)
            val z = (ra.sum() - mu) / sd
            return z to normalP(abs(z))
        }

        fun tP(): Pair<Double, Double> {
            val d = cohensD()
            val t = d * sqrt(a.size * b.size.toDouble() / (a.size + b.size)).coerceAtLeast(1e-9)
            return t to normalP(abs(t))
        }

        fun permP(seed: Int): Pair<Double, Double> {
            val obs = abs(diff())
            val all = a + b
            val rnd = kotlin.random.Random(seed)
            var hit = 0
            val nPerm = 2000
            repeat(nPerm) {
                val sh = all.shuffled(rnd)
                val x = sh.take(a.size).average()
                val y = sh.drop(a.size).average()
                if (abs(x - y) >= obs - 1e-12) hit++
            }
            return obs to (hit + 1.0) / (nPerm + 1)
        }
    }

    /** 单个检验结果 */
    data class Result(
        val name: String,
        val method: Method,
        val stat: Double,
        val pRaw: Double,
        val d: Double,
        val diff: Double
    ) {
        fun dLabel() = when {
            abs(d) < 0.2 -> "效应极小（几乎没差）"
            abs(d) < 0.5 -> "小"
            abs(d) < 0.8 -> "中"
            else -> "大"
        }
        /** 只给 p 是个陷阱，这个把两件事一起说明白 */
        fun verdict(alpha: Double): String {
            val sig = pRaw < alpha
            val big = abs(d) >= 0.2
            return when {
                sig && big -> "显著且效应不小——可以用这个结论"
                sig && !big -> "显著但效应极小——样本量大才会显著，实际没差别"
                !sig && big -> "效应看着不小但不显著——样本量可能不够"
                else -> "不显著，效应也小"
            }
        }
    }

    /** 跑一个检验 */
    fun run(it: Item, m: Method, seed: Int = 7): Result {
        val (st, p) = when (m) {
            Method.T -> it.tP()
            Method.RANK -> it.rankP()
            Method.PERM -> it.permP(seed)
        }
        return Result(it.name, m, st, p, it.cohensD(), it.diff())
    }

    /** 三种方法一并跑：答案不一致本身就是重要信息 */
    fun compare(it: Item, seed: Int = 7): List<Result> =
        Method.values().map { m -> run(it, m, seed) }

    /**
     * 多重比较校正。
     *
     * 返回校正后的阈值：p 小于它才算显著。
     * 给阈值而不是给"第 i 项是否显著"，
     * 是为了让界面能画一条统一的线——
     * 用户看到线在哪，比看一堆 true/false 直观。
     */
    fun correct(ps: List<Double>, c: Corr, q: Double = 0.05): Pair<Double, List<Boolean>> {
        val m = ps.size
        if (m == 0) return q to emptyList()
        return when (c) {
            Corr.NONE -> q to ps.map { it < q }
            Corr.BONF -> {
                val a = q / m
                a to ps.map { it < a }
            }
            Corr.BH -> {
                // 按 p 升序找最大的 k 使 p_k ≤ k/m·q
                val idx = ps.mapIndexed { i, p -> i to p }.sortedBy { it.second }
                var thr = q
                var cut = 0
                idx.forEachIndexed { k, (_, p) ->
                    if (p <= (k + 1.0) / m * q) cut = k + 1
                }
                if (cut > 0) thr = idx[cut - 1].second
                thr to ps.map { it <= thr && it <= q }
            }
        }
    }

    /** 不复校正 vs 校正后差多少 —— 这个对比本身就是给用户看的 */
    fun correctionImpact(ps: List<Double>, q: Double = 0.05): String = buildString {
        appendLine("多重比较校正的影响（共 ${ps.size} 个检验，α=$q）")
        appendLine()
        val raw = ps.count { it < q }
        Corr.values().forEach { c ->
            val (thr, sig) = correct(ps, c, q)
            appendLine("  ${c.label.padEnd(12)} 阈值 ${"%.5f".format(thr)}　" +
                "显著 ${sig.count { it }} 个")
        }
        appendLine()
        appendLine("不校正时 ${raw} 个显著。")
        val bh = correct(ps, Corr.BH, q).second.count { it }
        if (raw > bh)
            appendLine("→ 校正后少了 ${raw - bh} 个：这些很可能是假阳性。")
        else
            appendLine("→ 校正后仍有 ${bh} 个，说明这几个是真的强。")
        if (ps.size >= 20)
            appendLine()
            .also { appendLine("注：${ps.size} 个检验下，不校正时期望有 " +
                "%.1f 个假阳性。".format(ps.size * q)) }
    }

    fun validate(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items")
            ?: return false to "zx_hypo 缺少 items"
        val n = items.length()
        if (n == 0) return false to "zx_hypo 的 items 为空"
        if (n > 24) return false to "zx_hypo 最多 24 个检验项，实际 $n 个"
        val seen = HashSet<String>()
        for (k in 0 until n) {
            val it = items.optJSONObject(k)
                ?: return false to "items[$k] 不是对象，应为 {\"n\":名,\"a\":[],\"b\":[]}"
            val nm = it.optString("n", "")
            if (nm.isBlank()) return false to "items[$k] 缺少 n"
            if (!seen.add(nm)) return false to "检验项「$nm」重复——同名项无法区分"
            val a = it.optJSONArray("a") ?: return false to "items[$k] 缺少 a"
            val b = it.optJSONArray("b") ?: return false to "items[$k] 缺少 b"
            // 两组都为空：什么都算不出
            if (a.length() < 2 || b.length() < 2)
                return false to "items[$k] 的 a / b 至少各 2 个，实际 ${a.length()} / ${b.length()}——" +
                                "样本太少算不出标准差"
            if (a.length() > 40 || b.length() > 40)
                return false to "items[$k] 的 a / b 最多各 40 个"
            for (j in 0 until a.length())
                if (a.optDouble(j, Double.NaN).isNaN()) return false to "items[$k].a[$j] 不是数值"
            for (j in 0 until b.length())
                if (b.optDouble(j, Double.NaN).isNaN()) return false to "items[$k].b[$j] 不是数值"
        }
        val q = o.optDouble("q", 0.05)
        if (q <= 0 || q >= 1)
            return false to "zx_hypo 的 q=$q 超出 0-1（显著性水平）"
        val m = o.optString("method", "t")
        if (m !in setOf("t", "rank", "perm"))
            return false to "zx_hypo 的 method=\"$m\" 只支持 t / rank / perm"
        val c = o.optString("corr", "bh")
        if (c !in setOf("none", "bonf", "bh"))
            return false to "zx_hypo 的 corr=\"$c\" 只支持 none / bonf / bh"
        return true to ""
    }

    fun from(o: JSONObject, seed: Int = 7): List<Item> {
        val items = o.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { k ->
            val it = items.optJSONObject(k) ?: return@mapNotNull null
            val a = it.optJSONArray("a") ?: return@mapNotNull null
            val b = it.optJSONArray("b") ?: return@mapNotNull null
            Item(it.optString("n", "项${k + 1}"),
                (0 until a.length()).map { a.optDouble(it, 0.0) },
                (0 until b.length()).map { b.optDouble(it, 0.0) })
        }
    }

    fun render(items: List<Item>, m: Method, c: Corr, q: Double = 0.05, seed: Int = 7): String =
        buildString {
            val rs = items.map { run(it, m, seed) }
            val (thr, sig) = correct(rs.map { it.pRaw }, c, q)
            appendLine("假设检验（${m.label} · ${c.label} · α=$q）")
            appendLine()
            appendLine("  检验项".padEnd(14) + "p 值".padEnd(10) +
                "Cohen's d".padEnd(11) + "显著")
            rs.forEachIndexed { i, r ->
                appendLine("  ${r.name.padEnd(12)} ${"%.4f".format(r.pRaw).padEnd(10)} " +
                    "${"%.3f".format(r.d).padEnd(10)} ${if (sig[i]) "✓" else "－"}")
            }
            appendLine()
            appendLine("校正后阈值 ${"%.5f".format(thr)}（p 小于它才算显著）")
            appendLine()
            // 三个必须回答的疑问
            val top = rs.minByOrNull { it.pRaw }
            if (top != null) {
                appendLine("最显著的一项：${top.name}")
                appendLine("  一、稳不稳　　：切到「置换」重跑看 p 变不变")
                appendLine("  二、换方法　　：${Method.values().joinToString(" / ") { it.label }} 三选一")
                appendLine("  三、效应多大　：d=${"%.3f".format(top.d)}（${top.dLabel()}）")
                appendLine("  结论　　　　　：${top.verdict(thr)}")
            }
        }

    fun demo(): String {
        val items = listOf(
            Item("响应耗时", (1..10).map { 800.0 + it * 31 }, (1..10).map { 840.0 + it * 29 }),
            Item("转化率", (1..10).map { 0.20 + it * 0.011 }, (1..10).map { 0.19 + it * 0.009 }),
            Item("崩溃率", (1..10).map { 0.011 + it * 0.0004 }, (1..10).map { 0.010 + it * 0.0003 }),
            Item("留存", (1..10).map { 0.51 + it * 0.008 }, (1..10).map { 0.50 + it * 0.007 })
        )
        return buildString {
            appendLine("══ 三个疑问 ══")
            appendLine()
            val one = items[0]
            appendLine("「${one.name}」三种方法：")
            compare(one).forEach { r ->
                appendLine("  ${r.method.label.padEnd(8)} p=${"%.4f".format(r.pRaw)}" +
                    "　d=${"%.3f".format(r.d)}　${r.dLabel()}")
            }
            appendLine()
            appendLine("三种方法给出不同 p 时，说明**结论依赖方法假设**——")
            appendLine("这本身就是重要信息，不该只报一个 p。")
            appendLine()
            appendLine("══ 多重比较 ══")
            appendLine()
            val ps = items.map { run(it, Method.T).pRaw } +
                listOf(0.31, 0.62, 0.08, 0.44, 0.51, 0.72, 0.13, 0.88, 0.29, 0.66,
                       0.03, 0.57, 0.41, 0.79, 0.18, 0.92)
            appendLine(correctionImpact(ps))
            appendLine()
            appendLine("20 个检验、α=0.05 下，不校正时期望有 1.0 个假阳性。")
            appendLine("这不是「可能会出错」，是**必然会有**。")
        }
    }

    fun report(): String = buildString {
        appendLine("假设检验界面 + 多重比较校正（把「p=0.03」变成能追问的界面）")
        appendLine()
        appendLine("zx_test 给了 p 值、效应量、可拖的样本量，")
        appendLine("但它仍是一张**静态卡**。用户看到 p=0.032，")
        appendLine("接下来想问的三个问题，卡答不了：")
        appendLine()
        appendLine("  一、这个 p 稳不稳？（换一批样本还是这个结论吗）")
        appendLine("  二、换个方法还是这个结论吗？（t 显著、秩和不显著，信哪个）")
        appendLine("  三、效应有多大？（p 显著但 d=0.05，有意义吗）")
        appendLine()
        appendLine("三个问题的共同点是：**都是「在多大范围内成立」，")
        appendLine("而不是「成不成立」**。")
        appendLine()
        appendLine("多重比较是最常踩的坑，靠「小心点」躲不掉：")
        appendLine()
        appendLine("  二十个指标逐个检验，α=0.05 下期望就有一个假阳性。")
        appendLine()
        appendLine("不做校正，AI 会从噪声里「发现」一个显著指标，")
        appendLine("并且言之凿凿地报告给用户。")
        appendLine("这不是概率问题，是**必然发生**的问题。")
        appendLine()
        appendLine("三条约束：")
        appendLine("  一、同时给 p 和效应量，且要解读（只给 p 是陷阱）")
        appendLine("  二、校正方式要显式（默认 BH 而不是「不校正」）")
        appendLine("  三、方法切换不改变数据，只改变算法")
    }

    fun guide(): String = """
        zx_hypo(op=run|compare|correct|demo|report|guide, ...)

        把「p=0.03」变成能追问的界面。

        zx_test 仍是静态卡，三个问题答不了：
          一、这个 p 稳不稳？
          二、换个方法还是这个结论吗？
          三、效应有多大？

        共同点：都是「在多大范围内成立」，不是「成不成立」。

        多重比较靠「小心点」躲不掉：
          二十个指标逐个检验，α=0.05 下期望就有一个假阳性
          这不是「可能会出错」，是**必然会有**

        三种校正：
          none  裸 p（20 个里期望 1 个假阳性）
          bonf  α/m，控制族系错误率，严格
          bh    控制错误发现率，默认推荐

        三条约束：
          一、同时给 p 和效应量，且要解读
          二、校正方式要显式，默认 BH 而非「不校正」
          三、方法切换不改变数据，只改变算法

        DSL：items[{n,a[],b[]}] + method + corr + q
    """.trimIndent()

    private fun normalP(z: Double): Double {
        // 标准正态双尾 p 的近似（Abramowitz-Stegun 7.1.26）
        val x = abs(z)
        val t = 1.0 / (1.0 + 0.2316419 * x)
        val d = 0.3989422804014327 * kotlin.math.exp(-x * x / 2.0)
        val p = d * t * (0.319381530 + t * (-0.356563782 +
            t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))))
        return (2 * p).coerceIn(0.0, 1.0)
    }
}
