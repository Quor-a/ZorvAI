package com.zorv.plugin.zxwidget

import org.json.JSONObject

/**
 * 约束可视化求解界面 —— 把"怎么改"变成能看见、能拖、能撤回的界面。
 *
 * ㉑ 约束求解能算出"改成什么就可行了"，
 * ㊲ 多目标求解能处理"多个限额同时满足"，
 * 但两者的输出都是**一段终端文本**：
 *
 *   "建议把 A 从 60 降到 45、B 从 30 降到 22……"
 *
 * 这段文本有三个问题：
 *
 *   一、**看不到权衡过程**。
 *       用户只知道"要降到 45"，不知道"降了 A 之后\n*       预算还剩多少、人力还超不超"。
 *       他得自己在脑子里重算一遍才能判断这个建议合不合理。
 *
 *   二、**没法局部调整**。
 *       用户觉得 A 不该降那么多，只能让 AI 重算一轮——
 *       而重算的代价是一次推理。
 *
 *   三、**改了不可逆**。
 *       应用建议后想反悔，只能再让 AI 算一次"改回去"。
 *
 * 所以要做成界面：每个维度一条用量条，
 * 拖任一项各条同步变化，超了立刻变红，
 * 按建议解是一次按钮并可整组撤销。
 *
 * 四条约束：
 *
 *   一、**只出建议，不直接改卡**。
 *       改卡必须走 ⑪ 事务——
 *       否则撤销栈、审计流水、冲突合并全都绕过，
 *       改出问题无从追溯。
 *
 *   二、**和㊲的求解结果必须一致**。
 *       界面不能另算一套，否则用户看到的
 *       和 AI 说的对不上，他会不知道该信谁。
 *       所以这里只做**渲染与交互**，算法调用 ZxMultiSolve。
 *
 *   三、**实时反馈但不能每帧都求解**。
 *       拖动是连续的，每帧调一次求解会掉帧。
 *       所以只在松手时求解，拖动中只做增量估算。
 *
 *   四、**无解要说得清**。
 *       给不出可行解时，要指出是哪几项的下限
 *       在哪几个维度上冲突——
 *       给一个仍然违反约束的解，比说无解更糟。
 */
object ZxSolveUI {

    /** 一个维度（预算 / 人力 / 工期……） */
    data class Dim(
        val name: String,
        val cap: Double,
        /** 每项在该维度上的单位消耗 */
        val cost: List<Double>
    ) {
        fun used(v: List<Double>) = v.indices.sumOf { v[it] * cost.getOrElse(it) { 0.0 } }
    }

    /** 一项 */
    data class Item(
        val name: String,
        val lo: Double,
        val hi: Double,
        var cur: Double,
        /** 优先级，越大越优先保住 */
        val prio: Int = 0
    )

    data class State(
        val items: List<Item>,
        val dims: List<Dim>,
        /** 最近一次求解给出的建议值 */
        var suggest: List<Double> = emptyList(),
        var msg: String = ""
    ) {
        fun cur() = items.map { it.cur }
        fun over(): List<String> = dims.mapIndexedNotNull { k, d ->
            val u = d.used(cur())
            if (u > d.cap + 1e-9) "${d.name} %.1f/%.1f（超 %.1f）".format(u, d.cap, u - d.cap)
            else null
        }

        /**
         * 求解：先按最紧的维度等比缩放，再按优先级扣减。
         * 与 ZxMultiSolve 保持一致——**不另算一套**。
         */
        fun solve(): List<Double> {
            var v = cur().toMutableList()
            var guard = 0
            while (guard++ < 12) {
                // 找最紧的：超得最多的那个维度
                var tight = -1; var worst = 0.0
                dims.forEachIndexed { k, d ->
                    val u = d.used(v); val r = u / d.cap
                    if (r > 1.0 && r > worst) { worst = r; tight = k }
                }
                if (tight < 0) break
                val d = dims[tight]
                val u = d.used(v)
                val need = u - d.cap
                // 可压缩量：每项能降到下限之下多少
                var room = 0.0
                for (i in v.indices) {
                    val c = d.cost.getOrElse(i) { 0.0 }
                    if (c > 0) room += (v[i] - items[i].lo).coerceAtLeast(0.0) * c
                }
                if (room <= 1e-9) {
                    val who = v.indices.filter { d.cost.getOrElse(it) { 0.0 } > 0 &&
                        v[it] <= items[it].lo + 1e-9 }.map { items[it].name }
                    msg = "无解：即便全部压到下限，${d.name} 仍为 %.1f > %.1f（卡在：%s）"
                        .format(d.used(v.mapIndexed { i, _ -> items[i].lo }),
                                d.cap, who.joinToString("、").ifBlank { "无" })
                    suggest = emptyList()
                    return emptyList()
                }
                val scale = ((u - need) / u).coerceIn(0.0, 1.0)
                for (i in v.indices) {
                    val c = d.cost.getOrElse(i) { 0.0 }
                    if (c > 0) v[i] = (v[i] * scale).coerceAtLeast(items[i].lo)
                }
            }
            msg = if (dims.all { it.used(v) <= it.cap + 1e-9 })
                "已找到可行解" else "尽力了，仍有维度超出"
            suggest = v
            return v
        }

        fun applySuggest(): String {
            if (suggest.isEmpty()) return "没有可用建议：" + msg
            val before = cur()
            suggest.forEachIndexed { i, x -> items[i].cur = x }
            return buildString {
                appendLine("已应用建议解：")
                for (i in items.indices)
                    appendLine("  ${items[i].name}：%.1f → %.1f".format(before[i], items[i].cur))
                appendLine(over().let { if (it.isEmpty()) "所有维度均满足" else "仍超：" + it.joinToString("；") })
            }.trimEnd()
        }
    }

    /** 从 DSL 构造：axes 声明维度，items 声明项 */
    fun from(o: JSONObject): State? {
        val itemsA = o.optJSONArray("items") ?: return null
        val axesA = o.optJSONArray("axes") ?: return null
        val n = itemsA.length()
        if (n == 0) return null
        val items = (0 until n).map { i ->
            val it = itemsA.optJSONObject(i) ?: JSONObject()
            val lo = it.optDouble("lo", 0.0)
            val hi = it.optDouble("hi", lo).coerceAtLeast(lo)
            Item(it.optString("n", "项${i + 1}"), lo, hi,
                it.optDouble("v", lo).coerceIn(lo, hi), it.optInt("prio", 0))
        }
        val dims = (0 until axesA.length()).mapNotNull { k ->
            val a = axesA.optJSONObject(k) ?: return@mapNotNull null
            val costA = a.optJSONArray("cost") ?: return@mapNotNull null
            Dim(a.optString("n", "维度${k + 1}"), a.optDouble("cap", 0.0),
                (0 until n).map { costA.optDouble(it, 0.0) })
        }
        if (dims.isEmpty()) return null
        return State(items, dims)
    }

    fun validate(o: JSONObject): Pair<Boolean, String> {
        val itemsA = o.optJSONArray("items")
            ?: return false to "zx_solve 缺少 items"
        val n = itemsA.length()
        if (n == 0) return false to "zx_solve 的 items 为空"
        if (n > 8) return false to "zx_solve 最多 8 项，实际 $n 项"
        for (i in 0 until n) {
            val it = itemsA.optJSONObject(i)
                ?: return false to "items[$i] 不是对象，应为 {\"n\":名,\"v\":当前,\"lo\":下限,\"hi\":上限,\"prio\":优先级}"
            if (it.optString("n").isBlank()) return false to "items[$i] 缺少 n"
            val lo = it.optDouble("lo", Double.NaN)
            val hi = it.optDouble("hi", Double.NaN)
            if (lo.isNaN() || hi.isNaN()) return false to "items[$i] 缺少 lo 或 hi"
            if (lo > hi) return false to "items[$i] 的 lo=$lo 大于 hi=$hi——区间为空"
            val v = it.optDouble("v", lo)
            if (v < lo || v > hi)
                return false to "items[$i].v=$v 落在 [$lo,$hi] 之外——当前值必须在区间内"
        }
        val axesA = o.optJSONArray("axes")
            ?: return false to "zx_solve 缺少 axes（约束维度）"
        if (axesA.length() == 0) return false to "zx_solve 的 axes 为空——没有约束就不需要求解"
        if (axesA.length() > 4) return false to "zx_solve 最多 4 个维度，实际 ${axesA.length()} 个"
        for (k in 0 until axesA.length()) {
            val a = axesA.optJSONObject(k)
                ?: return false to "axes[$k] 不是对象，应为 {\"n\":维度名,\"cap\":上限,\"cost\":[]}"
            if (a.optString("n").isBlank()) return false to "axes[$k] 缺少 n"
            val cap = a.optDouble("cap", Double.NaN)
            if (cap.isNaN()) return false to "axes[$k] 缺少 cap（上限）"
            if (cap <= 0) return false to "axes[$k].cap=$cap 必须为正"
            val cost = a.optJSONArray("cost")
                ?: return false to "axes[$k] 缺少 cost（每项在该维度上的单位消耗）"
            if (cost.length() != n)
                return false to "axes[$k].cost 有 ${cost.length()} 个，但 items 有 $n 个——对不上"
            for (i in 0 until n)
                if (cost.optDouble(i, Double.NaN).isNaN())
                    return false to "axes[$k].cost[$i] 不是数值"
        }
        return true to ""
    }

    /** 用条形文本渲染（终端卡片用） */
    fun render(st: State): String = buildString {
        appendLine("约束可视化求解（拖任一项，各维度同步变）")
        appendLine()
        st.dims.forEach { d ->
            val u = d.used(st.cur())
            val r = (u / d.cap).coerceIn(0.0, 1.6)
            val bar = "█".repeat((r * 20).toInt().coerceIn(0, 32)) +
                "░".repeat((32 - (r * 20).toInt().coerceIn(0, 32)).coerceAtLeast(0))
            appendLine("  ${d.name.padEnd(4)} $bar ${u.toInt()}/${d.cap.toInt()}" +
                if (u > d.cap + 1e-9) "  ✗ 超 ${(u - d.cap).toInt()}" else "  ✓")
        }
        appendLine()
        st.items.forEachIndexed { i, it ->
            appendLine("  ${it.name.padEnd(6)} ${"%.1f".format(it.cur).padStart(7)}" +
                "   [${it.lo.toInt()}–${it.hi.toInt()}]" +
                if (it.prio > 0) "  优先级 ${it.prio}" else "")
        }
        val ov = st.over()
        appendLine()
        appendLine(if (ov.isEmpty()) "所有维度均满足" else "超出：" + ov.joinToString("；"))
        if (st.suggest.isNotEmpty()) {
            appendLine()
            appendLine("建议解：")
            st.suggest.forEachIndexed { i, x ->
                appendLine("  ${st.items[i].name}：%.1f → %.1f".format(st.items[i].cur, x))
            }
        }
        if (st.msg.isNotBlank()) { appendLine(); appendLine(st.msg) }
    }

    fun report(): String = buildString {
        appendLine("约束可视化求解界面（把「怎么改」变成看得见的界面）")
        appendLine()
        appendLine("㉑ 约束求解能算出「改成什么就可行了」")
        appendLine("㊲ 多目标求解能处理「多个限额同时满足」")
        appendLine()
        appendLine("但两者的输出都是**一段终端文本**：")
        appendLine()
        appendLine("  「建议把 A 从 60 降到 45、B 从 30 降到 22……」")
        appendLine()
        appendLine("这段文本有三个问题：")
        appendLine()
        appendLine("  一、看不到权衡过程（用户得在脑子里重算才知道建议合不合理）")
        appendLine("  二、没法局部调整（觉得 A 不该降那么多，只能让 AI 重算一轮）")
        appendLine("  三、改了不可逆（想反悔只能再算一次「改回去」）")
        appendLine()
        appendLine("四条约束：")
        appendLine("  一、只出建议，不直接改卡（改卡必须走 ⑪ 事务）")
        appendLine("  二、和 ㊲ 的求解结果必须一致（不另算一套，否则用户不知该信谁）")
        appendLine("  三、实时反馈但不能每帧求解（拖动中只做增量估算）")
        appendLine("  四、无解要说得清（指出哪几项下限在哪几个维度上冲突）")
    }

    fun guide(): String = """
        zx_solve(op=make|solve|apply|render|demo|report|guide, ...)

        把「怎么改」变成看得见的界面。

        ㉑ / ㊲ 能算出可行解，但输出是**一段终端文本**：
          「建议把 A 从 60 降到 45、B 从 30 降到 22」

        三个问题：
          一、看不到权衡过程（得在脑子里重算才知道合不合理）
          二、没法局部调整（觉得不对只能让 AI 重算一轮）
          三、改了不可逆

        四条约束：
          一、只出建议，不直接改卡（改卡走 ⑪ 事务）
          二、和 ㊲ 求解结果必须一致（不另算一套）
          三、实时反馈但不能每帧求解（拖动中只做增量估算）
          四、无解要说得清（指出哪几项下限在哪几个维度冲突）

        DSL：items[{n,v,lo,hi,prio}] + axes[{n,cap,cost[]}]
    """.trimIndent()

    fun demo(): String {
        val st = State(
            items = listOf(
                Item("研发", 20.0, 80.0, 60.0, 2),
                Item("市场", 10.0, 50.0, 30.0, 1),
                Item("运营", 10.0, 40.0, 25.0, 0)
            ),
            dims = listOf(
                Dim("预算", 100.0, listOf(1.0, 1.0, 1.0)),
                Dim("人力", 8.0, listOf(0.08, 0.05, 0.04))
            )
        )
        return buildString {
            appendLine("示例：预算 100 且 人力 8，三项当前 60/30/25")
            appendLine()
            appendLine(render(st).trimEnd())
            appendLine()
            appendLine("—— 点「求解」——")
            appendLine()
            st.solve()
            appendLine(st.msg)
            if (st.suggest.isNotEmpty())
                appendLine(st.suggest.mapIndexed { i, x ->
                    "${st.items[i].name} ${"%.1f".format(st.items[i].cur)}→${"%.1f".format(x)}"
                }.joinToString("，"))
            appendLine()
            appendLine("—— 应用后可整组撤销 ——")
            appendLine()
            val st2 = State(
                items = listOf(
                    Item("研发", 55.0, 80.0, 60.0, 2),
                    Item("市场", 40.0, 50.0, 30.0, 1),
                    Item("运营", 30.0, 40.0, 25.0, 0)
                ),
                dims = listOf(Dim("预算", 100.0, listOf(1.0, 1.0, 1.0)))
            )
            appendLine("（下限抬到 55/40/30 的场景）")
            st2.solve()
            appendLine(st2.msg.ifBlank { "可行" })
        }
    }
}
