package com.zorv.plugin.zxwidget
import kotlin.math.abs

/**
 * 多目标约束求解 —— 多个限额同时满足时怎么权衡。
 *
 * ㉑ 单限额求解解决"预算 100，三张卡填了 115"。
 * 但真实情况几乎从来不是单限额：
 *
 *   预算 100 万 **且** 人力 8 人天 **且** 工期 30 天
 *
 * 单限额的做法在这里会**顾此失彼**：
 *
 *   按预算缩放 → 缩完了，人力可能还超
 *   按人力缩放 → 缩完了，工期又不够了
 *   轮流来一轮 → 最后哪个都不满足，或者某个被缩了两次
 *
 * 所以要做三件事：
 *
 *   一、**找最紧的约束**（binding constraint）。
 *       哪个维度超得最厉害，就先按它缩放。
 *       按最紧的缩，其他维度**自然跟着降**（因为都是同一批项），
 *       这是"一次缩放同时改善多个维度"的关键——
 *       不是轮流满足，是一次到位。
 *
 *   二、**缩放后必须复查所有维度**。
 *       单限额时"缩完就完事"，多维度时缩完可能
 *       另一个维度还是超的（因为各维度消耗率不同：
 *       有的项费钱不费人，有的费人不费钱）。
 *       所以要迭代，但必须有上限。
 *
 *   三、**无解要说清是哪几项在哪几个维度上冲突**。
 *       多维度无解比单维度难说清：
 *       可能是某一项的下限在两个维度上不可能同时满足。
 *       只说"无解"用户无从下手；
 *       指出"第 2 项至少要 3 人天，但人力只剩 2"才有意义。
 *
 * 关键：**不追求一定能解出来**。
 * 多目标约束本质上可能是矛盾的，
 * 到迭代上限就如实报告，不编一个"看起来满足"的解。
 * 给出一个仍然违反约束的解，比说无解更糟。
 */
object ZxMultiSolve {

    /** 一个受多个维度约束的项 */
    data class Item(
        val cardId: String,
        val path: String,
        /** 各维度的消耗量，key 是维度名 */
        val cost: Map<String, Double>,
        /** 各维度的下限，缺省 0 */
        val floor: Map<String, Double> = emptyMap(),
        val prio: Int = 0
    ) {
        fun c(dim: String) = cost[dim] ?: 0.0
        fun f(dim: String) = floor[dim] ?: 0.0
    }

    /** 一个维度的限额 */
    data class Limit(val dim: String, val cap: Double, val label: String = dim)

    data class Plan(
        /** 各项的缩放系数（0-1），>1 表示放宽 */
        val ratios: List<Double>,
        val changes: List<Triple<String, String, Map<String, Double>>>,
        /** 达成后各维度的总量 */
        val totals: Map<String, Double>,
        /** 哪个维度是最紧的约束 */
        val binding: String,
        /** 迭代了几轮 */
        val passes: Int,
        val note: String
    )

    data class Infeasible(
        val reason: String,
        /** 冲突点：哪一项、哪个维度、差多少 */
        val blockers: List<String>
    )

    private const val MAX_PASSES = 8

    fun solve(items: List<Item>, limits: List<Limit>): Pair<Plan?, Infeasible?> {
        if (items.isEmpty()) return null to Infeasible("没有受约束的项", emptyList())
        if (limits.isEmpty()) return null to Infeasible("没有给限额", emptyList())

        // 1) 下限之和已超限额 → 无解，且要指出是哪几项
        val blockers = ArrayList<String>()
        for (l in limits) {
            val floorSum = items.sumOf { it.f(l.dim) }
            if (floorSum > l.cap + 1e-9) {
                val who = items.filter { it.f(l.dim) > 0 }
                    .joinToString("、") { "${it.path}≥${it.f(l.dim)}" }
                blockers.add("${l.label}：下限合计 %.2f > 限额 %.2f（$who）"
                    .format(floorSum, l.cap))
            }
        }
        if (blockers.isNotEmpty())
            return null to Infeasible("各项下限之和已超限额，无解", blockers)

        val ratios = MutableList(items.size) { 1.0 }
        var binding = limits.first().dim
        var pass = 0

        while (pass++ < MAX_PASSES) {
            // 2) 找最紧的约束：超限比例最大的那个维度
            val worst = limits.mapNotNull { l ->
                val cur = items.indices.sumOf { items[it].c(l.dim) * ratios[it] }
                if (cur <= l.cap + 1e-9) null else l to (cur / l.cap)
            }.maxByOrNull { it.second }

            // 都满足了
            if (worst == null) break
            val l = worst.first
            binding = l.dim
            val cur = items.indices.sumOf { items[it].c(l.dim) * ratios[it] }
            var scale = l.cap / cur

            // 3) 收缩，但每项不得穿过自己的下限
            for (i in items.indices) {
                val want = ratios[i] * scale
                val minRatio = limits.mapNotNull { ll ->
                    val c = items[i].c(ll.dim)
                    if (c <= 1e-9) null else items[i].f(ll.dim) / c
                }.maxOrNull() ?: 0.0
                ratios[i] = maxOf(want, minRatio)
            }
            // 夹过之后重算实际达成量，否则下一轮会基于错误的总量判断
            val after = items.indices.sumOf { items[it].c(l.dim) * ratios[it] }
            if (after > l.cap + 1e-9 && scale >= 1.0 - 1e-9) {
                // 已经缩不动了：说明是被下限卡住
                blockers.clear()
                for (ll in limits) {
                    val c2 = items.indices.sumOf { items[it].c(ll.dim) * ratios[it] }
                    if (c2 > ll.cap + 1e-9)
                        blockers.add("${ll.label}：即便缩到下限仍为 %.2f > %.2f"
                            .format(c2, ll.cap))
                }
                return null to Infeasible("缩到下限仍无法满足，无解", blockers)
            }
        }

        // 4) 复查：仍超就是到上限了，如实报告
        val totals = limits.associate { l ->
            l.dim to items.indices.sumOf { items[it].c(l.dim) * ratios[it] }
        }
        val still = limits.filter { (totals[it.dim] ?: 0.0) > it.cap + 1e-6 }
        if (still.isNotEmpty())
            return null to Infeasible(
                "迭代 $MAX_PASSES 轮仍未全部满足，不编造解",
                still.map { "${it.label}：%.2f > %.2f".format(totals[it.dim] ?: 0.0, it.cap) })

        val changes = items.indices.mapNotNull { i ->
            if (abs(ratios[i] - 1.0) < 1e-9) null
            else Triple(items[i].cardId, items[i].path,
                items[i].cost.mapValues { (d, v) -> v * ratios[i] })
        }
        return Plan(ratios, changes, totals, binding, pass,
            "最紧的约束是「${limits.firstOrNull { it.dim == binding }?.label?: binding}」，按它缩放后其余维度一并满足（${pass - 1} 轮）。") to null
    }

    /**
     * 单维度报告：不求解，只告诉用户各维度差多少。
     * 求解失败时这个比"无解"有用得多——
     * 用户知道差多少才知道该砍哪里。
     */
    fun gap(items: List<Item>, limits: List<Limit>): String = buildString {
        appendLine("各维度缺口（正=超，负=有余）")
        for (l in limits) {
            val cur = items.sumOf { it.c(l.dim) }
            val d = cur - l.cap
            appendLine("  ${l.label}：%.2f / %.2f   %s%.2f".format(
                cur, l.cap, if (d > 0) "超 " else "余 ", abs(d)))
        }
    }

    /**
     * 哪一项最该砍：单位消耗上"最占地方"的。
     * 多目标下"砍哪个"不是直觉能定的——
     * 有的项费钱不费人，只按钱排会砍错。
     */
    fun worstOffenders(items: List<Item>, limits: List<Limit>, top: Int = 3): List<Pair<Item, Double>> {
        // 按"在各超限维度上的相对占用"之和排序
        val over = limits.filter { l -> items.sumOf { it.c(l.dim) } > l.cap }
        if (over.isEmpty()) return emptyList()
        return items.map { it to over.sumOf { l -> it.c(l.dim) / l.cap } }
            .sortedByDescending { it.second }.take(top)
    }

    fun demo(): String {
        val items = listOf(
            Item("c", "服务器", mapOf("预算" to 60.0, "人力" to 3.0, "工期" to 10.0),
                mapOf("预算" to 20.0, "人力" to 1.0, "工期" to 4.0)),
            Item("c", "域名", mapOf("预算" to 30.0, "人力" to 1.0, "工期" to 2.0)),
            Item("c", "CDN", mapOf("预算" to 25.0, "人力" to 2.0, "工期" to 5.0)),
            Item("c", "存储", mapOf("预算" to 18.0, "人力" to 2.0, "工期" to 8.0)))
        val limits = listOf(
            Limit("预算", 100.0, "预算（万）"),
            Limit("人力", 8.0, "人力（人天）"),
            Limit("工期", 30.0, "工期（天）"))
        return buildString {
            appendLine("示例：4 项，三个限额（预算 100 / 人力 8 / 工期 30）")
            appendLine()
            appendLine(gap(items, limits).trimEnd())
            appendLine()
            val (p, e) = solve(items, limits)
            if (p != null) {
                appendLine("最紧的约束：" + p.binding)
                appendLine("迭代 ${p.passes - 1} 轮")
                p.changes.forEach { (_, path, cost) ->
                    appendLine("  $path → " + cost.entries.joinToString(" ") {
                        "${it.key}=${"%.1f".format(it.value)}" }) }
                appendLine("达成：" + p.totals.entries.joinToString("  ") {
                    "${it.key}=${"%.1f".format(it.value)}" })
                appendLine()
                appendLine(p.note)
                appendLine()
                appendLine("最该砍的三项：")
                worstOffenders(items, limits).forEach { (it, s) ->
                    appendLine("  ${it.path}（相对占用 %.2f）".format(s)) }
            } else {
                appendLine("无解：" + e?.reason)
                e?.blockers?.forEach { appendLine("  · $it") }
                appendLine()
                // 演示真正的无解：把人力限额压到 3
                val tight = listOf(Limit("预算", 100.0, "预算（万）"),
                    Limit("人力", 3.0, "人力（人天）"), Limit("工期", 30.0, "工期（天）"))
                val (_, e2) = solve(items, tight)
                appendLine("把人力压到 3 人天：")
                appendLine("  " + (e2?.reason ?: ""))
                e2?.blockers?.forEach { appendLine("  · $it") }
            }
        }
    }

    fun report(): String = buildString {
        appendLine("多目标约束求解（多个限额同时满足）")
        appendLine()
        appendLine("㉑ 单限额求解解决「预算 100，三张卡填了 115」。")
        appendLine("但真实情况几乎从来不是单限额：")
        appendLine()
        appendLine("  预算 100 万 且 人力 8 人天 且 工期 30 天")
        appendLine()
        appendLine("单限额的做法在这里会**顾此失彼**：")
        appendLine("  按预算缩放 → 缩完了，人力可能还超")
        appendLine("  按人力缩放 → 缩完了，工期又不够了")
        appendLine("  轮流来一轮 → 最后哪个都不满足，或某个被缩了两次")
        appendLine()
        appendLine("三件事：")
        appendLine("  一、找最紧的约束（binding constraint）")
        appendLine("  二、缩放后必须复查所有维度（各维度消耗率不同）")
        appendLine("  三、无解要说清是哪几项在哪几个维度上冲突")
        appendLine()
        appendLine("关键：**不追求一定能解出来**。")
        appendLine("多目标约束本质上可能是矛盾的，")
        appendLine("到迭代上限就如实报告——")
        appendLine("给出一个仍然违反约束的解，比说无解更糟。")
    }

    fun guide(): String = """
        zx_multisolve(op=solve|gap|worst|demo|report|guide, items?, limits?)

        多个限额同时满足时怎么权衡。

        ㉑ 单限额求解在真实场景会顾此失彼：
          按预算缩放 → 缩完了，人力可能还超
          按人力缩放 → 缩完了，工期又不够了

        三件事：
          一、找最紧的约束（binding constraint）
              按最紧的缩，其他维度因为都是同一批项而自然跟着降
          二、缩放后必须复查所有维度（各维度消耗率不同）
          三、无解要说清是哪几项在哪几个维度上冲突

        items 格式：cardId|path=预算:人力:工期[:下限预算:下限人力:下限工期]
        limits 格式：维度名=限额，分号分隔

        关键：不追求一定能解出来。
        给出一个仍然违反约束的解，比说无解更糟。
    """.trimIndent()
}
