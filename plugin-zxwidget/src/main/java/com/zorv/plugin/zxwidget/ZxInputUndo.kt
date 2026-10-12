package com.zorv.plugin.zxwidget

/**
 * 输入级撤销重做。
 *
 * 已有的两层撤销：
 *   ZxUndo   —— 卡片级：撤销"渲染一张卡""删除一张卡"
 *   ZxTx     —— 跨卡级：撤销"一组联动改动"
 *
 * 这层是第三级，粒度最细：**字段级**。
 * 用户在表单卡里改了五个字段，Ctrl+Z 应该撤销最后一个字段，
 * 而不是把整张卡撤掉重填。
 *
 * 两个关键设计：
 *
 * 一、同类快速编辑要合并。
 *     输入 "3500" 是 4 次字符变化，如果不合并，
 *     用户要按 4 次 Ctrl+Z 才能回到 "0"。
 *     所以同一字段、间隔小于 600ms 的连续编辑合并为一步。
 *
 * 二、存 before/after 而不是整卡快照。
 *     整卡快照在表格场景下要吃几十 KB，
 *     字段级只存两个字符串。
 */
object ZxInputUndo {

    /** 合并窗口：600ms。太小还是会被拆成多步，太大用户会觉得"撤销跳得太远" */
    private const val COALESCE_MS = 600L
    private const val MAX_STACK = 50

    data class Edit(
        val cardId: String,
        val path: String,
        val before: String,
        var after: String,
        var ts: Long = System.currentTimeMillis()
    ) {
        val changed: Boolean get() = before != after
    }

    /** 每张卡独立的撤销/重做栈 */
    private val undoStack = mutableMapOf<String, ArrayDeque<Edit>>()
    private val redoStack = mutableMapOf<String, ArrayDeque<Edit>>()

    /**
     * 记录一次编辑。
     * @return 是否合并进了上一步（true 表示栈深度没变）
     */
    fun record(cardId: String, path: String, before: String, after: String): Boolean {
        if (before == after) return false
        val stack = undoStack.getOrPut(cardId) { ArrayDeque() }
        val now = System.currentTimeMillis()
        val top = stack.lastOrNull()

        val merged = top != null &&
                     top.path == path &&
                     now - top.ts < COALESCE_MS &&
                     top.after == before          // 必须是紧接着的连续编辑

        if (merged) {
            top!!.after = after                  // 只改结果值，before 保持最初的那个
            top.ts = now
        } else {
            stack.addLast(Edit(cardId, path, before, after, now))
            if (stack.size > MAX_STACK) stack.removeFirst()
        }
        // 有新编辑就清空重做栈——这是所有撤销实现的标准行为
        redoStack[cardId]?.clear()
        return merged
    }

    /** 撤销一步，返回应恢复到的 (path, value) */
    fun undo(cardId: String): Pair<String, String>? {
        val stack = undoStack[cardId] ?: return null
        val e = stack.removeLastOrNull() ?: return null
        redoStack.getOrPut(cardId) { ArrayDeque() }.addLast(e)
        return e.path to e.before
    }

    /** 重做一步 */
    fun redo(cardId: String): Pair<String, String>? {
        val stack = redoStack[cardId] ?: return null
        val e = stack.removeLastOrNull() ?: return null
        undoStack.getOrPut(cardId) { ArrayDeque() }.addLast(e)
        return e.path to e.after
    }

    fun canUndo(cardId: String): Boolean = (undoStack[cardId]?.size ?: 0) > 0
    fun canRedo(cardId: String): Boolean = (redoStack[cardId]?.size ?: 0) > 0

    fun depth(cardId: String): Pair<Int, Int> =
        (undoStack[cardId]?.size ?: 0) to (redoStack[cardId]?.size ?: 0)

    fun clear(cardId: String) { undoStack.remove(cardId); redoStack.remove(cardId) }

    /**
     * 撤销栈的可读历史。
     * 给用户看"撤到哪一步"——不显示的话用户只能盲按。
     */
    fun history(cardId: String, limit: Int = 8): String {
        val stack = undoStack[cardId]
        if (stack.isNullOrEmpty()) return "（无可撤销的编辑）"
        return buildString {
            appendLine("撤销栈 ${stack.size} 步（显示最近 $limit）：")
            stack.takeLast(limit).reversed().forEachIndexed { i, e ->
                appendLine("  ${i + 1}. ${e.path}: \"${short(e.before)}\" → \"${short(e.after)}\"")
            }
        }
    }

    private fun short(s: String): String =
        if (s.length > 18) s.take(15) + "…" else s

    /**
     * 与卡片级撤销的分工说明，供 /zxundo 输出。
     * 三层不是重复造轮子，粒度不同：
     *   字段级 Ctrl+Z  → 改错一个格子
     *   卡片级          → 撤销"生成这张卡"
     *   跨卡级          → 撤销"一次联动改了 4 张卡"
     */
    fun guide(): String = """
        zx_input_undo(op=undo|redo|history|clear, card=卡片ID)

        粒度：字段级。表单卡里改了五个字段，Ctrl+Z 撤销最后一个字段，
        而不是把整张卡撤掉重填。

        合并规则：同一字段、间隔 < ${COALESCE_MS}ms 的连续编辑合并为一步。
        —— 否则输入 "3500" 要按 4 次 Ctrl+Z 才能回到 "0"。

        与另两层的分工：
          字段级  改错一个格子
          卡片级  撤销「生成这张卡」
          跨卡级  撤销「一次联动改了 4 张卡」
    """.trimIndent()
}
