package com.zorv.plugin.zxwidget
import kotlin.math.abs

/**
 * 跨卡约束求解 —— 多张卡的约束互相打架时，给出可行解。
 *
 * ① 引用传播让多张卡联动，㉘ 跨卡约束让一张卡的填写
 * 约束另一张的取值。但两者都只做**检查**：
 * 违反了就报错，不告诉用户改成什么就行了。
 *
 *   预算 100，三张卡分别填 60 / 30 / 25 → 超了 15
 *   现在的提示是"总和超预算"，用户得自己试怎么分配。
 *
 * 三张卡还能凑，十张卡就没法手算了。
 * 而这类约束在真实场景里非常常见：预算分配、
 * 资源限额、时间排期、人员排班。
 *
 * **只报错不给解的检查，把求解成本转嫁给了用户。**
 *
 * 这个模块做两件事：
 *
 *   一、**按比例缩放**。这是最符合直觉的解法——
 *       各项等比缩到刚好塞进限额，保持原有比例关系。
 *       用户填 60/30/25 意味着他认为该是这个比例，
 *       缩放保留了意图，只是整体收一点。
 *
 *   二、**按优先级扣减**。有优先级时不能等比缩——
 *       高优先级要保住。从最低优先级开始扣，
 *       扣到够为止。
 *
 * 关键：给出的是**建议解，不直接改卡**。
 * 改卡要走事务（⑪），让用户能一次性看到
 * "这次要改哪几张卡的哪几格"，而不是界面自己悄悄变了。
 *
 * 另一个关键：**无解要明说**。
 * 有下限约束时可能根本无解（三项下限之和已超预算），
 * 这时候必须报"无解"并指出是哪几条下限造成的，
 * 而不是给一个仍然违反约束的"解"——
 * 那比不给更糟，用户会以为拿到了可行方案。
 */
object ZxSolve {

    /** 一个受约束的项 */
    data class Item(
        val cardId: String,
        val path: String,
        val value: Double,
        /** 缩放/扣减的下限，null 表示不设下限 */
        val min: Double? = null,
        /** 优先级，数字越大越优先保留；缺省按出现顺序 */
        val prio: Int = 0
    )

    /** 求解结果 */
    data class Plan(
        /** 各项的新值（cardId|path → 新值） */
        val changes: List<Triple<String, String, Double>>,
        /** 是否真的需要改 */
        val changed: Boolean,
        /** 达成后的总和 */
        val total: Double,
        val note: String
    )

    /** 失败：无解 */
    data class Infeasible(val reason: String, val blockers: List<String>)

    /**
     * 等比缩放：保持各项比例，缩放到刚好等于 limit。
     * 这是"用户填的比例就是他的意图"的默认解法。
     */
    fun scale(items: List<Item>, limit: Double): Pair<Plan?, Infeasible?> {
        val cur = items.sumOf { it.value }
        if (cur <= limit + 1e-9) {
            return Plan(emptyList(), false, cur,
                "当前总和 %.2f 未超限额 %.2f，不需要调整。".format(cur, limit)) to null
        }
        // 下限之和已超限额 → 无解
        val floorSum = items.sumOf { it.min ?: 0.0 }
        if (floorSum > limit + 1e-9) {
            val blockers = items.filter { (it.min ?: 0.0) > 0 }
                .map { "${it.cardId} 的 ${it.path} 下限 ${it.min}" }
            return null to Infeasible(
                "无解：各项下限之和 %.2f 已超过限额 %.2f。".format(floorSum, limit),
                blockers)
        }
        val ratio = limit / cur
        var acc = 0.0
        val out = mutableListOf<Triple<String, String, Double>>()
        // 第一轮：等比缩放并夹到下限
        val vals = items.map { it.value }.toMutableList()
        items.forEachIndexed { idx, it ->
            vals[idx] = maxOf(it.value * ratio, it.min ?: 0.0)
        }
        // 夹到下限后可能又超了：把超出部分从"还有空间"的项里按比例再扣
        var guard = 0
        while (guard++ < 24) {
            val total = vals.sum()
            val over = total - limit
            if (over <= 1e-9) break
            val room = items.mapIndexedNotNull { idx, it ->
                val spare = vals[idx] - (it.min ?: 0.0)
                if (spare > 1e-9) idx to spare else null
            }
            val roomSum = room.sumOf { it.second }
            if (roomSum <= 1e-9) break
            val take = minOf(over, roomSum)
            room.forEach { (idx, spare) -> vals[idx] -= take * (spare / roomSum) }
        }
        items.forEachIndexed { idx, it ->
            acc += vals[idx]
            if (abs(vals[idx] - it.value) > 1e-9)
                out.add(Triple(it.cardId, it.path, vals[idx]))
        }
        return Plan(out, true, acc,
            "等比缩放到 %.2f（原 %.2f，限额 %.2f），共改 %d 项。".format(
                acc, cur, limit, out.size)) to null
    }

    /**
     * 按优先级扣减：从最低优先级开始扣到够为止。
     * 有优先级时不能等比缩——高优先级要保住。
     */
    fun byPriority(items: List<Item>, limit: Double): Pair<Plan?, Infeasible?> {
        val cur = items.sumOf { it.value }
        if (cur <= limit + 1e-9)
            return Plan(emptyList(), false, cur,
                "当前总和 %.2f 未超限额 %.2f，不需要调整。".format(cur, limit)) to null
        val floorSum = items.sumOf { it.min ?: 0.0 }
        if (floorSum > limit + 1e-9) {
            val blockers = items.filter { (it.min ?: 0.0) > 0 }
                .map { "${it.cardId} 的 ${it.path} 下限 ${it.min}" }
            return null to Infeasible(
                "无解：各项下限之和 %.2f 已超过限额 %.2f。".format(floorSum, limit), blockers)
        }
        // 按优先级升序（低的先扣），同级按索引
        val order = items.mapIndexed { idx, it -> idx to (it.prio to it.value) }
            .sortedWith(compareBy<Pair<Int, Pair<Int, Double>>> { it.second.first }
                .thenByDescending { it.second.second })
            .map { it.first }
        val vals = items.map { it.value }.toMutableList()
        var need = cur - limit
        val out = mutableListOf<Triple<String, String, Double>>()
        for (idx in order) {
            if (need <= 1e-9) break
            val lo = items[idx].min ?: 0.0
            val spare = (vals[idx] - lo).coerceAtLeast(0.0)
            val take = minOf(need, spare)
            vals[idx] -= take
            need -= take
        }
        var acc = 0.0
        items.forEachIndexed { idx, it ->
            acc += vals[idx]
            if (abs(vals[idx] - it.value) > 1e-9)
                out.add(Triple(it.cardId, it.path, vals[idx]))
        }
        return Plan(out, true, acc,
            "按优先级扣减到 %.2f（原 %.2f，限额 %.2f），共改 %d 项；" +
            "优先保住高优先级项。".format(acc, cur, limit, out.size)) to null
    }

    fun format(p: Plan): String = buildString {
        appendLine(if (p.changed) "建议解（达成总和 %.2f）".format(p.total) else "无需调整")
        if (p.changed) p.changes.forEach { (c, path, v) ->
            appendLine("  $c 的 $path → %.2f".format(v))
        }
        appendLine()
        appendLine(p.note)
        appendLine()
        appendLine("这是建议解，不直接改卡。改卡要走事务（⑪），")
        appendLine("让用户一次性看到「这次要改哪几格」。")
    }

    fun report(): String = buildString {
        appendLine("跨卡约束求解（等比缩放 / 按优先级扣减）")
        appendLine()
        appendLine("① 引用传播让多张卡联动，㉘ 跨卡约束让一张卡的")
        appendLine("填写约束另一张的取值。但两者都只做**检查**：")
        appendLine("违反了就报错，不告诉用户改成什么就行了。")
        appendLine()
        appendLine("  预算 100，三张卡分别填 60 / 30 / 25 → 超了 15")
        appendLine("  现在的提示是「总和超预算」，用户得自己试怎么分配")
        appendLine()
        appendLine("三张卡还能凑，十张卡就没法手算了。")
        appendLine()
        appendLine("只报错不给解的检查，把求解成本转嫁给了用户。")
        appendLine()
        appendLine("无解要明说——给一个仍然违反约束的「解」")
        appendLine("比不给更糟，用户会以为拿到了可行方案。")
    }

    fun demo(): String {
        val items = listOf(
            Item("c_budget", "rows[0].v[1]", 60.0, min = 20.0, prio = 2),
            Item("c_budget", "rows[1].v[1]", 30.0, min = 10.0, prio = 1),
            Item("c_budget", "rows[2].v[1]", 25.0, min = 5.0, prio = 0))
        val infeas = listOf(
            Item("c_budget", "rows[0].v[1]", 60.0, min = 50.0, prio = 2),
            Item("c_budget", "rows[1].v[1]", 30.0, min = 40.0, prio = 1),
            Item("c_budget", "rows[2].v[1]", 25.0, min = 30.0, prio = 0))
        return buildString {
            appendLine("示例：预算 100，三项填 60 / 30 / 25")
            appendLine()
            appendLine("【等比缩放】")
            scale(items, 100.0).let { (p, e) ->
                if (p != null) append(format(p).trimEnd()) else appendLine(e?.reason)
            }
            appendLine()
            appendLine("【按优先级扣减】")
            byPriority(items, 100.0).let { (p, e) ->
                if (p != null) append(format(p).trimEnd()) else appendLine(e?.reason)
            }
            appendLine()
            appendLine("【无解的情况】下限 50 / 40 / 30，预算仍是 100")
            byPriority(infeas, 100.0).let { (p, e) ->
                if (p != null) append(format(p).trimEnd())
                else {
                    appendLine(e?.reason)
                    e?.blockers?.forEach { appendLine("  · $it") }
                }
            }
        }
    }

    fun guide(): String = """
        zx_solve(op=scale|prio|demo|report|guide, items?, limit?)

        多张卡的约束互相打架时，给出可行解。

        ① 引用传播让多张卡联动，㉘ 跨卡约束让一张卡的
        填写约束另一张的取值。但两者都只做**检查**：
        违反了就报错，不告诉用户改成什么就行了。

          预算 100，三张卡分别填 60 / 30 / 25 → 超了 15
          现在的提示是「总和超预算」，用户得自己试怎么分配

        三张卡还能凑，十张卡就没法手算了。
        **只报错不给解的检查，把求解成本转嫁给了用户。**

        两种解法：
          scale —— 等比缩放，保持用户填的比例（默认）
          prio  —— 按优先级扣减，保住高优先级项

        items 格式：cardId|path=value[:min][:prio]，分号分隔
        示例：c_budget|rows[0].v[1]=60:20:2

        无解要明说并指出是哪几条下限造成的——
        给一个仍然违反约束的「解」比不给更糟。
    """.trimIndent()
}
