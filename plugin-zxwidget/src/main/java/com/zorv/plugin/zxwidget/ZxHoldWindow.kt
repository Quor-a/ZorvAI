package com.zorv.plugin.zxwidget

/**
 * 持有期窗口 —— 往回追溯：这个症状落在谁的持有期内。
 *
 * **它和 ㊹ 确认登记、㊺ 监管链有什么不同**
 *
 * 监管链（㊺）解决的是：**能定位到断裂点的情况**。
 * 每次交接都记状态，"完好 → 完好 → 有划痕"，
 * 一比就知道是第 2 段。
 *
 * 但这有个前提：**状态变化能被记录到**。
 *
 * 实际上很多问题**没有明确的断裂点**：
 *
 *   猫接回来三天后拉稀 → 没有任何一次记录写着"此刻开始拉稀"
 *   员工离职两周后发现账目问题 → 没有任何一天标记"这天开始出错"
 *   房子退租一个月后墙裂 → 裂缝不是一天出现的
 *
 * 这时候监管链没用——每一环都写着"正常"。
 * 只能按**时间窗**往回推。
 *
 * ── 贯穿的一条 ──────────────────────────────────────────────
 *
 * **没有断裂点时，只能按潜伏期反推窗口——
 *   而窗口往往不止一个，要给出全部嫌疑，不能只给一个。**
 *
 * 之所以必须给全部：
 *
 *   只给最近的一个 → 冤枉最后接手的人（他最无辜）
 *   只给最可能的一个 → 模型在猜，而猜错了用户会当真
 *   给全部嫌疑 + 各自的依据 → 用户结合其他信息自己判
 *
 * 这正是它和监管链的核心差别：
 *
 *   监管链：有断裂点 → 给出**唯一**答案
 *   持有期：没断裂点 → 给出**全部嫌疑 + 可信度**
 *
 * ── 四条约束 ────────────────────────────────────────────────
 *
 * 一、**必须给潜伏期范围，不能只给一个值**。
 *     "潜伏 3 天"是个点估计，实际是一个区间（比如 1-7 天）。
 *     只给点估计会把边缘窗口排掉——
 *     而那个被排掉的往往正是真正的窗口。
 *
 * 二、**窗口要按时间倒序排，并标出可信度**。
 *     越近的窗口越可疑，但这不是铁律——
 *     长潜伏期的病，早的窗口反而更可疑。
 *     所以要**按潜伏期匹配度排**，不是按时间排。
 *
 * 三、**持有期有缺口要明说**。
 *     两个持有期之间如果有空档（没人持有），
 *     不能把前后接起来当成连续——
 *     **而空档恰恰是最可疑的地方**（没人看管）。
 *
 * 四、**不认的窗口要单独列出，不能只列嫌疑**。
 *     排除了哪些、为什么排除，和保留了哪些同样重要。
 *     只给结论不给排除理由，用户没法复核。
 *
 * ── 认不出的（诚实说明） ────────────────────────────────────
 *
 * 它认不出"症状其实与持有期无关"（比如是先天问题）。
 * 它只能在给定的持有期里挑，不能判断"都不对"。
 */
object ZxHoldWindow {

    data class Hold(
        val seq: Int,
        val who: String,
        val fromH: Int,   // 距离现在多少小时开始持有
        val toH: Int      // 距离现在多少小时结束持有（0 = 到现在还在持有）
    )

    data class Suspect(
        val hold: Hold,
        val score: Double,
        val why: String
    )

    private val holds = ArrayList<Hold>()
    private var seq = 0

    fun hold(who: String, fromH: Int, toH: Int): Pair<Boolean, String> {
        if (who.isBlank()) return false to "**缺 who（谁持有的）**"
        if (fromH < 0 || toH < 0)
            return false to "**时间不能为负**——用「距今多少小时」表示"
        if (toH != 0 && toH >= fromH)
            return false to "**toH 必须小于 fromH**（时间是往回数的，越早越大），" +
                "或者用 0 表示持有到现在"
        holds.add(Hold(++seq, who, fromH, toH))
        holds.sortByDescending { it.fromH }
        return true to "第 $seq 段：$who　距今 ${fromH}h → ${if (toH == 0) "现在" else toH.toString() + "h"}"
    }

    /** 三、持有期有缺口要明说 */
    fun gaps(): List<Pair<Int, Int>> {
        val g = ArrayList<Pair<Int, Int>>()
        val sorted = holds.sortedByDescending { it.fromH }
        for (i in 0 until sorted.size - 1) {
            val cur = sorted[i]        // 较早的一段
            val next = sorted[i + 1]   // 较晚的一段
            val curEnd = cur.toH       // 较早段的结束（数字大）
            val nextStart = next.fromH // 较晚段的开始（数字小）
            if (curEnd > nextStart) g.add(curEnd to nextStart)
        }
        return g
    }

    /**
     * 一、必须给潜伏期范围；二、按匹配度排；四、排除的要列出
     * @param nowH 症状出现距现在多少小时
     * @param minH 最短潜伏期
     * @param maxH 最长潜伏期
     */
    fun trace(nowH: Int, minH: Int, maxH: Int): String = buildString {
        if (holds.isEmpty()) {
            appendLine("还没有持有期记录。")
            return@buildString
        }
        if (maxH < minH) {
            appendLine("**最长潜伏期不能小于最短**——**给的是区间，不是两个独立的数**")
            return@buildString
        }
        appendLine("══ 持有期追溯 ══")
        appendLine()
        appendLine("症状出现在距今 ${nowH}h")
        appendLine("潜伏期 ${minH}h ~ ${maxH}h → 暴露时刻应在距今 " +
            "${nowH + minH}h ~ ${nowH + maxH}h 之间")
        appendLine()

        val lo = nowH + minH
        val hi = nowH + maxH
        val suspects = ArrayList<Suspect>()
        val ruledOut = ArrayList<Pair<Hold, String>>()

        holds.forEach { h ->
            val hStart = h.fromH          // 远端（较早）
            val hEnd = if (h.toH == 0) 0 else h.toH // 近端（较晚）
            val overlapLo = maxOf(lo, hEnd)
            val overlapHi = minOf(hi, hStart)
            if (overlapLo > overlapHi) {
                ruledOut.add(h to "持有期 [${hEnd}h, ${hStart}h] 与暴露窗口 " +
                    "[${lo}h, ${hi}h] 没有交集")
                return@forEach
            }
            // 二、按匹配度排：重叠占暴露窗口的比例 × 占持有期的比例
            val winLen = (hi - lo).coerceAtLeast(1)
            val holdLen = (hStart - hEnd).coerceAtLeast(1)
            val cover = (overlapHi - overlapLo).toDouble() / winLen
            val specificity = (overlapHi - overlapLo).toDouble() / holdLen
            val score = cover * 0.6 + specificity * 0.4
            suspects.add(Suspect(h, score,
                "重叠 ${overlapLo}h~${overlapHi}h，覆盖暴露窗口 " +
                    "${(cover * 100).toInt()}%、占其持有期 ${(specificity * 100).toInt()}%"))
        }

        suspects.sortByDescending { it.score }
        if (suspects.isEmpty()) {
            appendLine("**所有持有期都被排除了**——" +
                "**这说明问题可能出在没被记录的空档里，或者跟持有期无关**")
        } else {
            appendLine("**嫌疑窗口 ${suspects.size} 个（按匹配度排，不是按时间排）：**")
            appendLine()
            suspects.forEachIndexed { i, s ->
                val tag = if (i == 0) "最可疑" else "可疑"
                appendLine("  ${i + 1}. ${s.hold.who}　[${if (s.hold.toH == 0) "0" else s.hold.toH.toString()}h, ${s.hold.fromH}h]")
                appendLine("     $tag（${"%.2f".format(s.score)}）　${s.why}")
            }
            appendLine()
            appendLine("**为什么给全部而不是只给一个：**")
            appendLine("  只给最近的一个 → 冤枉最后接手的人（他最无辜）")
            appendLine("  只给最可能的一个 → 模型在猜，猜错了用户会当真")
        }
        // 四、排除的要列出
        if (ruledOut.isNotEmpty()) {
            appendLine()
            appendLine("**已排除 ${ruledOut.size} 个（排除了哪些同样重要）：**")
            ruledOut.forEach { (h, w) -> appendLine("  ✗ ${h.who}　$w") }
        }
        // 三、缺口
        val g = gaps()
        if (g.isNotEmpty()) {
            appendLine()
            appendLine("**持有期之间有 ${g.size} 处空档——这是最可疑的地方：**")
            g.forEach { (a, b) -> appendLine("  ⚠ 距今 ${b}h ~ ${a}h **无人持有**") }
            appendLine("  **空档恰恰最可疑**（没人看管），不能把前后接起来当连续")
        }
    }

    fun clear(): Int = holds.size.also { holds.clear(); seq = 0 }

    fun render(): String = buildString {
        appendLine("══ 持有期 ══")
        if (holds.isEmpty()) { appendLine("  还没有记录。"); return@buildString }
        holds.sortedByDescending { it.fromH }.forEach { h ->
            appendLine("  第 ${h.seq} 段　${h.who}　距今 ${h.fromH}h → " +
                if (h.toH == 0) "现在" else "${h.toH}h")
        }
        val g = gaps()
        if (g.isNotEmpty()) {
            appendLine()
            g.forEach { (a, b) -> appendLine("  ⚠ 空档：距今 ${b}h ~ ${a}h 无人持有") }
        }
    }

    fun report(): String = buildString {
        appendLine("持有期窗口 —— 往回追溯：这个症状落在谁的持有期内")
        appendLine()
        appendLine("它和 ㊺ 监管链有什么不同：")
        appendLine()
        appendLine("  监管链解决的是**能定位到断裂点**的情况。")
        appendLine("  每次交接都记状态，一比就知道是第 2 段。")
        appendLine()
        appendLine("  但这有个前提：**状态变化能被记录到**。")
        appendLine()
        appendLine("  实际上很多问题**没有明确的断裂点**：")
        appendLine()
        appendLine("    猫接回来三天后拉稀 → 没有记录写着「此刻开始拉稀」")
        appendLine("    离职两周后发现账目问题 → 没有一天标记「这天开始出错」")
        appendLine("    退租一个月后墙裂 → 裂缝不是一天出现的")
        appendLine()
        appendLine("  这时候监管链没用——每一环都写着「正常」。")
        appendLine("  只能按**时间窗**往回推。")
        appendLine()
        appendLine("**没有断裂点时，只能按潜伏期反推窗口——**")
        appendLine("**而窗口往往不止一个，要给出全部嫌疑，不能只给一个。**")
        appendLine()
        appendLine("  只给最近的一个 → 冤枉最后接手的人（他最无辜）")
        appendLine("  只给最可能的一个 → 模型在猜，而猜错了用户会当真")
        appendLine("  给全部嫌疑 + 各自的依据 → 用户结合其他信息自己判")
        appendLine()
        appendLine("  监管链：有断裂点 → 给出**唯一**答案")
        appendLine("  持有期：没断裂点 → 给出**全部嫌疑 + 可信度**")
        appendLine()
        appendLine("四条约束：")
        appendLine("  一、**必须给潜伏期范围，不能只给一个值**")
        appendLine("  二、**按潜伏期匹配度排，不是按时间排**")
        appendLine("  三、**持有期有缺口要明说**")
        appendLine("  四、**排除的要单独列出**")
        appendLine()
        appendLine("第一条最关键：「潜伏 3 天」是个点估计，实际是区间。")
        appendLine("只给点估计会把边缘窗口排掉——")
        appendLine("**而那个被排掉的往往正是真正的窗口。**")
    }

    fun demo(): String = buildString {
        appendLine("══ 猫接回来三天后拉稀 ══")
        appendLine()
        hold("主人", 72, 0).let { (ok, m) -> appendLine("  ${if (ok) "✓" else "✗"} $m") }
        hold("寄养家庭", 336, 72).let { (ok, m) -> appendLine("  ${if (ok) "✓" else "✗"} $m") }
        hold("主人", 720, 336).let { (ok, m) -> appendLine("  ${if (ok) "✓" else "✗"} $m") }
        appendLine()
        appendLine("  三段都记着「正常」——没有任何一次记录写着" +
            "「此刻开始拉稀」。**监管链在这里没用。**")
        appendLine()
        appendLine("  ── 用潜伏期反推 ──")
        appendLine()
        appendLine("  " + trace(24, 12, 120).replace("\n", "\n  "))
        appendLine()
        appendLine("  ── 一、只给点估计会漏掉边缘窗口 ──")
        appendLine()
        appendLine("  " + trace(24, 96, 96).replace("\n", "\n  "))
        appendLine()
        appendLine("  潜伏期固定 96h 时，只剩 1 个窗口（主人被排除）；")
        appendLine("  给区间 12-120h 时，有 2 个窗口都在。")
        appendLine("  **被点估计排掉的那个，可能正是真的。**")
    }

    fun guide(): String = """
        zx_holdwindow(op=hold|trace|list|report|demo|clear|guide)

        持有期窗口：往回追溯，这个症状落在谁的持有期内。

        它和 ㊺ 监管链的分工：
          监管链：有断裂点 → 给出**唯一**答案
          持有期：没断裂点 → 给出**全部嫌疑 + 可信度**

        很多问题没有明确的断裂点：
          猫接回来三天后拉稀 → 没有记录写着"此刻开始拉稀"
          离职两周后发现账目问题 → 没有一天标记"这天开始出错"

        这时候每一环都写着"正常"，"只能按**时间窗**往回推。

        **窗口往往不止一个，要给出全部嫌疑，不能只给一个。**
        只给最近的一个 → 冤枉最后接手的人（他最无辜）

        四条约束：
          一、**必须给潜伏期范围，不能只给一个值**（点估计会排掉边缘窗口）
          二、**按潜伏期匹配度排，不是按时间排**
          三、**持有期有缺口要明说**（空档恰恰最可疑）
          四、**排除的要单独列出**

          op=hold <谁> <开始距今h> <结束距今h，0=持有到现在>
          op=trace <症状出现距今h> <最短潜伏h> <最长潜伏h>
          op=demo  演示"三段都记着正常，怎么找出是谁"
    """.trimIndent()

    fun handle(op: String, a: String, b: String, c: String, d: String): String = when (op) {
        "hold" -> hold(a, b.toIntOrNull() ?: -1, c.toIntOrNull() ?: -1)
            .let { (ok, m) -> (if (ok) "✓ " else "✗ ") + m }
        "trace" -> trace(a.toIntOrNull() ?: 0, b.toIntOrNull() ?: 0, c.toIntOrNull() ?: 0)
        "list" -> render()
        "render" -> render()
        "report" -> report()
        "demo" -> demo()
        "clear" -> "已清空 ${clear()} 段"
        else -> guide()
    }
}
