package com.zorv.plugin.zxwidget

/**
 * 轮次边界 —— 撤销到"AI 那一轮之前"，而不是逐步退。
 *
 * ③ 撤销栈解决的是"改错了能退回去"，粒度是**一次交互**。
 * 但真实诉求往往不是"退一步"，而是：
 *
 *   "AI 刚才那一轮把我的表改乱了，回到它动手之前。"
 *
 * 现在用户要按 N 次撤销，而且**他不知道该按几次**——
 * 因为他不知道 AI 那一轮到底动了几下。
 * 按少了没退干净，按多了把自己之前的改动也退没了。
 *
 * **粒度不对的撤销等于没有撤销。**
 * 用户想要的是"回到某个时刻"，不是"倒着走 N 步"。
 *
 * 所以做三件事：
 *
 *   一、**打标记**。AI 每轮开始（emit 第一批卡片）时记一个边界。
 *       边界不是时间戳——按时间切会切在半中间。
 *
 *   二、**按边界撤销**。一次操作退回到指定边界，
 *       不要求用户知道中间有几步。
 *
 *   三、**边界可命名**。用 AI 那一轮的摘要命名
 *       （"生成 Q4 报表"），用户才知道要退到哪。
 *       没有名字的边界用户不认——他分不出两个边界的区别。
 *
 * 关键：这个模块**只记边界，不复制撤销栈**。
 * 撤销栈仍然是唯一的可撤销真相，这里只是给"退到哪"
 * 提供一个用户能理解的坐标。
 */
object ZxTurn {

    data class Mark(
        val id: Int,
        val label: String,
        val at: Long,
        /** 这个边界时的撤销栈深度 */
        val depth: Int
    )

    private val marks = ArrayList<Mark>(40)
    private var seq = 0
    /** 当前撤销栈深度，由撤销栈调用方推进/回退时同步 */
    var depth: Int = 0
        internal set

    fun begin(label: String): Mark {
        val m = Mark(++seq, label.ifBlank { "第 $seq 轮" }, System.currentTimeMillis(), depth)
        marks.add(m)
        if (marks.size > 40) marks.removeAt(0)
        return m
    }

    /** 撤销栈每步推进/回退时同步 */
    fun sync(d: Int) { depth = d }

    /**
     * 回退到某个边界：返回要撤销的步数。
     * 返回值给用户看，实际撤销由撤销栈执行——
     * 这里只回答"要退几步"。
     */
    fun stepsTo(markId: Int): Pair<Boolean, Int> {
        val m = marks.firstOrNull { it.id == markId }
            ?: return false to 0
        val n = depth - m.depth
        if (n <= 0) return false to 0      // 已经在那个边界了或更靠前
        return true to n
    }

    /** 最近一个边界 */
    fun last(): Mark? = marks.lastOrNull()

    /** 上一个边界（不含自己）——用户说"再退一轮"时用 */
    fun prev(): Mark? = if (marks.size >= 2) marks[marks.size - 2] else null

    /** 边界之后的全部操作一次性丢弃（"回退到上一轮"） */
    fun stepsToPrev(): Pair<Boolean, Int> = prev()?.let { stepsTo(it.id) } ?: (false to 0)

    fun remove(id: Int) { marks.removeAll { it.id == id } }
    fun clear() { marks.clear(); depth = 0; seq = 0 }
    fun size() = marks.size

    fun format(): String {
        if (marks.isEmpty()) return "还没有轮次边界。"
        return buildString {
            appendLine("轮次边界（共 ${marks.size} 个，当前栈深 $depth）")
            marks.reversed().take(8).forEach { m ->
                val n = depth - m.depth
                appendLine("  #${m.id} ${m.label}" +
                    if (n > 0) "  （退到这里需 $n 步）" else "  （当前）")
            }
        }
    }

    fun report(): String = buildString {
        appendLine("轮次边界（共 ${marks.size} 个，上限 40）")
        appendLine()
        appendLine("③ 撤销栈解决「改错了能退回去」，粒度是一次交互。")
        appendLine("但真实诉求往往不是「退一步」，而是：")
        appendLine()
        appendLine("  「AI 刚才那一轮把我的表改乱了，回到它动手之前。」")
        appendLine()
        appendLine("现在用户要按 N 次撤销，而且他不知道该按几次——")
        appendLine("因为他不知道 AI 那一轮到底动了几下。")
        appendLine("按少了没退干净，按多了把自己之前的改动也退没了。")
        appendLine()
        appendLine("粒度不对的撤销等于没有撤销。")
        appendLine("用户想要的是「回到某个时刻」，不是「倒着走 N 步」。")
    }

    fun guide(): String = """
        zx_turn(op=begin|to|prev|get|report|guide, label?, id?)

        撤销到「AI 那一轮之前」，而不是逐步退。

        ③ 撤销栈解决「改错了能退回去」，粒度是一次交互。
        但真实诉求往往不是「退一步」，而是：

          「AI 刚才那一轮把我的表改乱了，回到它动手之前。」

        现在用户要按 N 次撤销，而且**他不知道该按几次**——
        因为他不知道 AI 那一轮到底动了几下。
        按少了没退干净，按多了把自己之前的改动也退没了。

        **粒度不对的撤销等于没有撤销。**

        三件事：
          一、打标记——AI 每轮开始记一个边界
          二、按边界撤销——一次操作退回去，不要求知道中间有几步
          三、边界可命名——没有名字用户分不出两个边界的区别
    """.trimIndent()
}
