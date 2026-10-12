package com.zorv.plugin.zxwidget

/**
 * 意图级重放 —— 回退到某轮之后，还能"重做"回来。
 *
 * ⑦ 轮次边界解决的是"退回到 AI 那一轮之前"。
 * 但它有个副作用：**退回去之后，后面做的事就丢了**。
 *
 *   用户退到 #2 看了一眼，发现还是原来那版好，
 *   想回到最新状态——做不到，只能让 AI 重做一遍。
 *
 * 这不是小概率操作。人退回去看，本来就是为了比较；
 * 比较完再回来是常态。只能单向的回退，
 * 会让用户**不敢退**——因为退了就回不来了。
 *
 * **不敢用的功能等于没有。**
 *
 * 所以做三件事：
 *
 *   一、**回退不丢弃，压入重做栈**。撤销栈和重做栈
 *       是标准的做法，但这里的关键是**边界也要跟着走**——
 *       退到 #2 之后，#3 的边界不能消失，
 *       否则重做回来时边界就错乱了。
 *
 *   二、**前进到某个边界**。和退回对称：
 *       用户说"回到我做那次修改之后"，同样不该要求他知道几步。
 *
 *   三、**分叉要处理**。退回去之后如果发生了新的操作，
 *       原来的"未来"就作废了——这是所有重做栈都要面对的问题。
 *       做法是**清空重做栈**，和主流编辑器一致；
 *       但要在重做栈非空时才清，否则等于误删。
 *
 * 关键：这个模块**不存快照，只存步数差**。
 * 快照要复制整张卡的 DSL，联动卡片多的时候开销很大；
 * 而步数差只是一个整数，实际内容由撤销栈自己维护。
 */
object ZxReplay {

    /** 一次前进/后退的记录 */
    data class Hop(
        /** 目标边界 id */
        val markId: Int,
        /** 走了几步 */
        val steps: Int,
        val label: String
    )

    private val undoHops = ArrayList<Hop>(40)
    private val redoHops = ArrayList<Hop>(40)

    /**
     * 记录一次退回。返回 false 表示这次退回无需记录
     * （步数为 0，即没真的动）。
     */
    fun recordUndo(markId: Int, steps: Int, label: String): Boolean {
        if (steps <= 0) return false
        undoHops.add(Hop(markId, steps, label))
        if (undoHops.size > 40) undoHops.removeAt(0)
        return true
    }

    /**
     * 重做：弹出最近一次退回。
     * 返回要前进的步数，由调用方交给撤销栈执行。
     */
    fun redo(): Hop? = undoHops.removeLastOrNull()?.also {
        redoHops.add(it)
        if (redoHops.size > 40) redoHops.removeAt(0)
    }

    /** 撤销一次重做 */
    fun undoRedo(): Hop? = redoHops.removeLastOrNull()?.also {
        undoHops.add(it)
    }

    /**
     * 发生了新操作：作废原来的未来。
     * 只在重做栈非空时清——否则等于误删还没发生的事。
     */
    fun invalidateFuture(): Boolean {
        if (redoHops.isEmpty()) return false
        redoHops.clear()
        return true
    }

    fun canRedo() = undoHops.isNotEmpty()
    fun canUndoRedo() = redoHops.isNotEmpty()

    /**
     * 前进到某个边界：从当前位置往后数有几步。
     * 这是和退回对称的能力——用户说"回到那次修改之后"，
     * 不该要求他知道中间有几步。
     */
    fun stepsForwardTo(markId: Int, currentDepth: Int, marks: List<ZxTurn.Mark>): Pair<Boolean, Int> {
        val m = marks.firstOrNull { it.id == markId }
            ?: return false to 0
        val n = m.depth - currentDepth
        if (n <= 0) return false to 0
        return true to n
    }

    fun clear() { undoHops.clear(); redoHops.clear() }
    fun undoSize() = undoHops.size
    fun redoSize() = redoHops.size

    fun format(): String = buildString {
        appendLine("重放栈（退回 ${undoHops.size} 条 / 重做 ${redoHops.size} 条，上限各 40）")
        if (undoHops.isNotEmpty()) {
            appendLine("可重做（回到未来）：")
            undoHops.reversed().take(5).forEach {
                appendLine("  → #${it.markId} ${it.label}（前进 ${it.steps} 步）")
            }
        }
        if (redoHops.isNotEmpty()) {
            appendLine("已重做（可再退回）：")
            redoHops.reversed().take(5).forEach {
                appendLine("  ← #${it.markId} ${it.label}（后退 ${it.steps} 步）")
            }
        }
        if (undoHops.isEmpty() && redoHops.isEmpty())
            appendLine("  还没有发生过退回。")
    }

    fun report(): String = buildString {
        appendLine("意图级重放（回退后还能回来）")
        appendLine()
        appendLine("⑦ 轮次边界解决「退回到 AI 那一轮之前」，")
        appendLine("但它有个副作用：**退回去之后，后面做的事就丢了**。")
        appendLine()
        appendLine("  用户退到 #2 看了一眼，发现还是原来那版好，")
        appendLine("  想回到最新状态——做不到，只能让 AI 重做一遍。")
        appendLine()
        appendLine("这不是小概率操作。人退回去看，本来就是为了比较；")
        appendLine("比较完再回来是常态。只能单向的回退，")
        appendLine("会让用户**不敢退**——因为退了就回不来了。")
        appendLine()
        appendLine("**不敢用的功能等于没有。**")
        appendLine()
        appendLine("三件事：")
        appendLine("  一、回退不丢弃，压入重做栈（边界要跟着走）")
        appendLine("  二、前进到某个边界——和退回对称")
        appendLine("  三、分叉处理——退回去后发生新操作则作废原未来")
        appendLine()
        appendLine("关键：不存快照，只存步数差。")
        appendLine("快照要复制整张卡的 DSL，联动卡片多时开销很大。")
    }

    fun demo(): String = buildString {
        appendLine("示例：三次退回，然后重做回来")
        appendLine()
        clear()
        appendLine("1) 退到 #2「补充图表」（3 步）")
        appendLine("   记录：" + recordUndo(2, 3, "补充图表"))
        appendLine("2) 退到 #1「生成 Q4 报表」（3 步）")
        appendLine("   记录：" + recordUndo(1, 3, "生成 Q4 报表"))
        appendLine("3) 同位置再退（0 步）")
        appendLine("   记录：" + recordUndo(1, 0, "生成 Q4 报表") + "（步数为 0，不记）")
        appendLine()
        appendLine("可重做：" + canRedo())
        val h = redo()
        appendLine("重做一次 → 回到 #${h?.markId} ${h?.label}（前进 ${h?.steps} 步）")
        appendLine()
        appendLine("退回去后发生了新操作：")
        appendLine("   作废未来：" + invalidateFuture())
        appendLine("   再次作废（已空）：" + invalidateFuture())
        appendLine()
        append(format().trimEnd())
    }

    fun guide(): String = """
        zx_replay(op=redo|undo|get|demo|report|guide)

        回退到某轮之后，还能「重做」回来。

        ⑦ 轮次边界解决「退回到 AI 那一轮之前」，
        但它有个副作用：**退回去之后，后面做的事就丢了**。

          用户退到 #2 看了一眼，发现还是原来那版好，
          想回到最新状态——做不到，只能让 AI 重做一遍。

        这不是小概率操作。人退回去看，本来就是为了比较；
        比较完再回来是常态。只能单向的回退，
        会让用户**不敢退**——因为退了就回不来了。

        **不敢用的功能等于没有。**

        三件事：
          一、回退不丢弃，压入重做栈（边界要跟着走）
          二、前进到某个边界——和退回对称
          三、分叉处理——退回去后发生新操作则作废原未来

        关键：不存快照，只存步数差。
    """.trimIndent()
}
