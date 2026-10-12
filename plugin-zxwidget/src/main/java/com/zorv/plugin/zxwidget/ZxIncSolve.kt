package com.zorv.plugin.zxwidget

/**
 * 增量求解 —— 只重算受影响的部分。
 *
 * ㉑ 跨卡约束求解解决"多张卡打架时给可行解"。
 * 但它每次都**全量重算**：十项里改了其中一项的值，
 * 也要把十项全部重跑一遍缩放。
 *
 *   十项：无所谓，一次算完不到 1ms
 *   一百项、而且要跟着用户输入**实时**算：就开始掉帧了
 *
 * 真实场景是后者——用户在滑块上拖，约束提示要跟着变。
 * 每次拖动都全量重算，手感就废了。
 *
 * 所以做增量：只重算**受影响的项**。
 * 三件事要想清楚，否则增量会算出和全量不一样的结果：
 *
 *   一、**哪些项受影响**。等比缩放下，改任何一项都会
 *       改变总和，从而改变所有项的比例——
 *       看起来是"全部受影响"。
 *       但实际上**没被夹到下限的项**，它们的相对关系不变，
 *       只需乘一个新比例即可。真正要单独处理的
 *       只有被夹住的那些。
 *
 *   二、**增量和全量必须一致**。这是个正确性约束：
 *       增量算出来的解，必须和全量重算的解相同。
 *       不一致的话，用户看到的建议解会随操作历史漂移，
 *       这比慢更糟——因为结果是错的。
 *
 *   三、**要有收敛上限**。夹下限→腾空间→再夹，
 *       可能反复。必须给一个迭代上限，
 *       到上限就退回全量重算——
 *       与其给一个不确定的结果，不如花一次全量的代价。
 *
 * 关键：**不追求永远走增量**。增量是优化，
 * 一旦有不确定性就退回全量。正确优先于快。
 */
object ZxIncSolve {

    /** 一次增量求解的输入 */
    data class Item(
        val cardId: String,
        val path: String,
        val value: Double,
        val min: Double = 0.0,
        val prio: Int = 0
    ) {
        companion object {
            fun from(s: ZxSolve.Item) =
                Item(s.cardId, s.path, s.value, s.min ?: 0.0, s.prio)
        }
    }

    data class Result(
        /** 本次真正变化的项（增量输出） */
        val changed: List<Triple<String, String, Double>>,
        /** 是否走了增量路径 */
        val incremental: Boolean,
        /** 重算了几项（增量才有意义） */
        val touched: Int,
        val total: Double,
        val note: String
    )

    /** 与全量一致的迭代上限；到了就退回全量 */
    private const val MAX_PASSES = 6

    /**
     * 增量等比缩放。
     *
     * changedIdx：本次被用户改动的项下标。
     * 其余项沿用上次解出的值（prev），只按新比例调整。
     */
    fun scaleIncremental(
        items: List<Item>,
        prev: List<Double>,
        changedIdx: Int,
        limit: Double
    ): Result {
        if (items.size != prev.size)
            return fallback(items, limit, "项数与上次解不一致")
        val cur = items.sumOf { it.value }
        if (cur <= limit + 1e-9)
            return Result(emptyList(), true, 0, cur,
                "当前总和 %.2f 未超限额 %.2f。".format(cur, limit))

        val floorSum = items.sumOf { it.min }
        if (floorSum > limit + 1e-9)
            return fallback(items, limit,
                "下限之和 %.2f 已超限额，交给全量判定无解".format(floorSum))

        val ratio = limit / cur
        val vals = prev.toMutableList()
        // 1) 先按比例整体调；未被夹住的项只需这一步
        var touched = 0
        items.forEachIndexed { i, it ->
            val want = it.value * ratio
            if (want >= it.min - 1e-9) {
                if (kotlin.math.abs(want - vals[i]) > 1e-9) touched++
                vals[i] = want
            }
        }
        // 2) 被夹住的项单独处理：夹到下限，把多出的空间让给别的项
        val clamped = (0 until items.size).filter { items[it].value * ratio < items[it].min - 1e-9 }
        if (clamped.isNotEmpty()) {
            clamped.forEach { i ->
                if (kotlin.math.abs(items[i].min - vals[i]) > 1e-9) touched++
                vals[i] = items[i].min
            }
            // 3) 收敛：把超出部分从有空间的项里按比例再扣
            var pass = 0
            while (pass++ < MAX_PASSES) {
                val over = vals.sum() - limit
                if (over <= 1e-9) break
                val room = (0 until items.size).mapNotNull { i ->
                    val spare = vals[i] - items[i].min
                    if (spare > 1e-9) i to spare else null
                }
                val roomSum = room.sumOf { it.second }
                if (roomSum <= 1e-9) break
                val take = minOf(over, roomSum)
                room.forEach { (i, spare) ->
                    vals[i] -= take * (spare / roomSum); touched++
                }
            }
            // 到上限还没收敛：不猜，退回全量
            if (vals.sum() - limit > 1e-9)
                return fallback(items, limit, "增量 ${pass - 1} 轮未收敛，退回全量")
        }

        // 一致性校验：增量结果不得违反约束
        if (vals.sum() - limit > 1e-6)
            return fallback(items, limit, "增量结果仍超限额，退回全量")
        for (i in items.indices)
            if (vals[i] < items[i].min - 1e-6)
                return fallback(items, limit, "增量结果低于下限，退回全量")

        val out = items.mapIndexedNotNull { i, it ->
            if (kotlin.math.abs(vals[i] - it.value) > 1e-9)
                Triple(it.cardId, it.path, vals[i]) else null
        }
        return Result(out, true, touched, vals.sum(),
            "增量重算 %d 项（共 %d 项），改动来自第 %d 项。".format(
                touched, items.size, changedIdx + 1))
    }

    /** 退回全量：正确优先于快 */
    private fun fallback(items: List<Item>, limit: Double, why: String): Result {
        val (p, e) = ZxSolve.scale(items.map { it.toSolve() }, limit)
        return if (p != null)
            Result(p.changes, false, items.size, p.total, "$why；已改用全量：${p.note}")
        else
            Result(emptyList(), false, items.size, items.sumOf { it.value },
                "$why；全量判定无解：${e?.reason ?: ""}")
    }

    private fun Item.toSolve() = ZxSolve.Item(cardId, path, value, min, prio)

    /**
     * 一致性比对：增量解与全量解不得超过 eps。
     * 这是给回归测试用的——增量最容易出的 bug 就是
     * "和全量算出来不一样"，而这种 bug 只在特定数据下出现。
     */
    fun consistent(items: List<Item>, prev: List<Double>, changedIdx: Int,
                   limit: Double, eps: Double = 1e-6): Boolean {
        val inc = scaleIncremental(items, prev, changedIdx, limit)
        if (!inc.incremental) return false
        val full = ZxSolve.scale(items.map { it.toSolve() }, limit).first
            ?: return false
        if (kotlin.math.abs(inc.total - full.total) > eps) return false
        if (inc.changed.size != full.changes.size) return false
        for (i in inc.changed.indices) {
            if (inc.changed[i].first != full.changes[i].first) return false
            if (kotlin.math.abs(inc.changed[i].third - full.changes[i].third) > eps) return false
        }
        return true
    }

    fun demo(): String {
        val items = listOf(
            Item("c", "a", 60.0, min = 20.0),
            Item("c", "b", 30.0, min = 10.0),
            Item("c", "c", 25.0, min = 5.0),
            Item("c", "d", 18.0, min = 2.0),
            Item("c", "e", 12.0, min = 1.0))
        val prev = items.map { it.value }
        return buildString {
            appendLine("示例：5 项，限额 100，改第 3 项 25 → 40")
            appendLine()
            val changed = items.toMutableList().also { it[2] = it[2].copy(value = 40.0) }
            val r = scaleIncremental(changed, prev, 2, 100.0)
            appendLine("增量路径：" + r.incremental)
            appendLine("重算项数：" + r.touched + " / " + changed.size)
            appendLine("达成总和：%.2f".format(r.total))
            r.changed.forEach { (c, p, v) -> appendLine("  $p → %.2f".format(v)) }
            appendLine()
            appendLine(r.note)
            appendLine()
            appendLine("与全量一致：" + consistent(changed, prev, 2, 100.0))
            appendLine()
            // 触发退回全量的场景：项数不匹配
            val bad = scaleIncremental(changed, prev.subList(0, 3), 2, 100.0)
            appendLine("项数不匹配时：" + bad.note)
        }
    }

    fun report(): String = buildString {
        appendLine("增量求解（只重算受影响的部分）")
        appendLine()
        appendLine("㉑ 跨卡约束求解解决「多张卡打架时给可行解」，")
        appendLine("但它每次都**全量重算**：十项里改了一项，")
        appendLine("也要把十项全部重跑一遍。")
        appendLine()
        appendLine("  十项：无所谓，一次算完不到 1ms")
        appendLine("  一百项、而且要跟着用户输入**实时**算：掉帧")
        appendLine()
        appendLine("真实场景是后者——用户在滑块上拖，约束提示要跟着变。")
        appendLine("每次拖动都全量重算，手感就废了。")
        appendLine()
        appendLine("三件事要想清楚，否则增量会算出和全量不一样的结果：")
        appendLine("  一、哪些项受影响（没被夹到下限的只乘新比例）")
        appendLine("  二、增量和全量必须一致（不一致比慢更糟）")
        appendLine("  三、要有收敛上限（到上限就退回全量）")
        appendLine()
        appendLine("关键：**不追求永远走增量**。")
        appendLine("一旦有不确定性就退回全量。正确优先于快。")
    }

    fun guide(): String = """
        zx_incsolve(op=scale|consistent|demo|report|guide, items?, limit?)

        只重算受影响的部分。

        ㉑ 跨卡约束求解每次都全量重算：
        十项里改了一项，也要把十项全部重跑一遍。

          十项：无所谓，一次算完不到 1ms
          一百项、而且要跟着用户输入实时算：掉帧

        真实场景是后者——用户在滑块上拖，约束提示要跟着变。

        三件事要想清楚，否则增量会算出和全量不一样的结果：
          一、哪些项受影响（没被夹到下限的只乘新比例）
          二、增量和全量必须一致（不一致比慢更糟）
          三、要有收敛上限（到上限就退回全量）

        关键：不追求永远走增量。
        一旦有不确定性就退回全量。正确优先于快。
    """.trimIndent()
}
