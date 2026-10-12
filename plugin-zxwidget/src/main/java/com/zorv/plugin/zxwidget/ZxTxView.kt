package com.zorv.plugin.zxwidget

/**
 * 事务可视化 —— 让"一组跨卡改动"在界面上成为一个可撤销单元。
 *
 * ⑪ 跨卡事务解决的是"改好几张卡要么全成要么全回滚"。
 * 但它**只在数据层成立，界面上完全看不见**。
 *
 * 后果是：用户点了"提交"，四张卡一起变了，
 * 界面上没有任何东西告诉他"这四张是一起的"。
 * 他想撤销这次提交，得按四次撤销——
 * 而且撤销栈里那四个操作混在其他操作中间，
 * 他根本不知道该撤到第几个。
 *
 * **看不见的事务等于没有事务。**
 * 数据层保证了一致性，但用户感知到的是"界面忽然变了四下"。
 *
 * 这个模块做的是三件事：
 *
 *   一、**命名**。事务要有名字（"提交 Q4 预算"），
 *       没有名字的分组用户不认——他不知道该不该撤。
 *
 *   二、**边界**。界面上要能看出"这几张卡属于同一次提交"。
 *       做法是给每张参与的卡打同一个 txId，
 *       渲染时画一条同源标记。
 *
 *   三、**整体撤销**。一次操作撤掉整组，而不是逐个撤。
 *       撤销栈里它占一格，不是四格。
 */
object ZxTxView {

    data class Group(
        val id: String,
        val name: String,
        /** 参与的 cardId */
        val cards: List<String>,
        val at: Long,
        /** 已撤销？ */
        val undone: Boolean = false,
        /** 每步摘要，给用户看"这次改了什么" */
        val steps: List<String> = emptyList()
    )

    private val groups = ArrayDeque<Group>(60)
    private var seq = 0

    fun begin(name: String, cards: List<String>, steps: List<String> = emptyList()): String {
        val id = "tx${++seq}"
        groups.addLast(Group(id, name, cards, System.currentTimeMillis(), false, steps))
        if (groups.size > 60) groups.removeFirst()
        return id
    }

    /** 卡属于哪个事务（未撤销的最新一个） */
    fun groupOf(cardId: String): Group? =
        groups.lastOrNull { !it.undone && cardId in it.cards }

    fun markUndone(id: String): Boolean {
        val g = groups.firstOrNull { it.id == id } ?: return false
        val i = groups.indexOf(g)
        groups.remove(g)
        // ArrayDeque 没有 set，重建
        val keep = groups.toList()
        groups.clear(); groups.addAll(keep)
        val replaced = g.copy(undone = true)
        val all = groups.toList()
        groups.clear()
        groups.addAll(all)
        // 用 id 标记：直接替换元素
        val idx = all.indexOfFirst { it.id == id }
        if (idx >= 0) {
            val rebuilt = all.toMutableList()
            rebuilt[idx] = replaced
            groups.clear(); groups.addAll(rebuilt)
        }

        return true
    }

    /** 撤销某张卡所在的整组：返回要一起撤销的 cardId */
    fun undo(cardId: String): Pair<Boolean, List<String>> {
        val g = groupOf(cardId) ?: return false to emptyList()
        markUndone(g.id)
        return true to g.cards
    }

    fun remove(cardId: String) {
        val keep = groups.filter { cardId !in it.cards }
        if (keep.size != groups.size) { groups.clear(); groups.addAll(keep) }
    }

    /** 给渲染层用：这张卡要不要画同源标记 */
    fun badge(cardId: String): String? {
        val g = groupOf(cardId) ?: return null
        return if (g.cards.size > 1) "${g.name} · ${g.cards.size} 张" else null
    }

    fun list(): List<Group> = groups.toList()

    fun format(): String {
        if (groups.isEmpty()) return "还没有事务记录。"
        return buildString {
            appendLine("事务 ${groups.size} 条：")
            groups.reversed().take(8).forEach { g ->
                val mark = if (g.undone) "↩ 已撤销" else "✓"
                appendLine("  $mark ${g.id}  「${g.name}」  ${g.cards.size} 张卡")
                g.steps.take(3).forEach { appendLine("      · $it") }
                if (g.steps.size > 3) appendLine("      · …共 ${g.steps.size} 步")
            }
        }
    }

    fun report(): String = buildString {
        appendLine("事务可视化（共 ${groups.size} 条，上限 60）")
        val active = groups.count { !it.undone }
        appendLine("  生效中 $active · 已撤销 ${groups.size - active}")
        val multi = groups.count { !it.undone && it.cards.size > 1 }
        appendLine("  跨卡事务 $multi 条（>1 张卡的才需要在界面上标同源）")
        appendLine()
        appendLine("⑪ 跨卡事务解决「要么全成要么全回滚」，")
        appendLine("但它只在数据层成立，界面上完全看不见：")
        appendLine()
        appendLine("  用户点了「提交」，四张卡一起变了，")
        appendLine("  界面上没有任何东西告诉他「这四张是一起的」。")
        appendLine("  他想撤销这次提交，得按四次撤销——")
        appendLine("  而且那四个操作混在其他操作中间，他不知道撤到第几个。")
        appendLine()
        appendLine("看不见的事务等于没有事务。")
    }

    fun guide(): String = """
        zx_txview(op=begin|undo|get|report|guide, name?, cards?, card?)

        让「一组跨卡改动」在界面上成为一个可撤销单元。

        ⑪ 跨卡事务解决「要么全成要么全回滚」，
        但它只在数据层成立，界面上完全看不见：

          用户点了「提交」，四张卡一起变了
          界面上没有任何东西告诉他「这四张是一起的」
          他想撤销这次提交，得按四次撤销——
          而且那四个操作混在其他操作中间，他不知道撤到第几个

        **看不见的事务等于没有事务。**

        三件事：
          一、命名——事务要有名字（"提交 Q4 预算"）"，
              没有名字的分组用户不认，他不知道该不该撤
          二、边界——给每张参与的卡打同一个 txId，
              渲染时画同源标记
          三、整体撤销——一次操作撤掉整组，占撤销栈一格不是四格
    """.trimIndent()
}
