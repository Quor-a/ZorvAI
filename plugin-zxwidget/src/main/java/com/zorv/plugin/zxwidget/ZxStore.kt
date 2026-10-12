package com.zorv.plugin.zxwidget

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * 卡片状态持久化 + 撤销栈。
 *
 * 两件事，一件比一件容易被漏掉：
 *
 * 1. 持久化：用户在卡片上填的、选的、改的，重开会话必须还在。
 *    否则 AI 上一轮问出来的参数，用户切个会话就得重填一遍。
 *
 * 2. 撤销：气泡里的交互是"可误触"的——看板点一下卡片就换列了，
 *    diff 点一下就拒绝了。没有撤销，用户只能让 AI 重新生成整张卡。
 *
 * 撤销栈用命令模式：每次交互压一条可反向操作的记录，
 * 而不是整卡快照（整卡快照在表格场景会吃掉几十 KB）。
 */
object ZxStore {

    /* ── 撤销栈 ─────────────────────────────────────────────── */

    interface Op {
        val label: String
        fun undo()
        fun redo()
    }

    private val undoStack = ArrayDeque<Op>(64)
    private val redoStack = ArrayDeque<Op>(64)
    private val _depth = MutableStateFlow(0)

    /** 可撤销步数，供卡片上显示"还能撤销几步" */
    val depth: StateFlow<Int> = _depth.asStateFlow()

    fun push(op: Op) {
        undoStack.addLast(op)
        redoStack.clear()          // 新操作作废重做栈，这是所有编辑器的共识
        if (undoStack.size > 64) undoStack.removeFirst()
        _depth.value = undoStack.size
    }

    fun undo(): String {
        val op = undoStack.removeLastOrNull() ?: return "没有可撤销的操作"
        op.undo(); redoStack.addLast(op); _depth.value = undoStack.size
        return "已撤销：${op.label}"
    }

    fun redo(): String {
        val op = redoStack.removeLastOrNull() ?: return "没有可重做的操作"
        op.redo(); undoStack.addLast(op); _depth.value = undoStack.size
        return "已重做：${op.label}"
    }

    fun undoLabels(n: Int = 5): List<String> =
        undoStack.takeLast(n).map { it.label }.asReversed()

    /* ── 具体可撤销操作 ─────────────────────────────────────── */

    /** 表格改单元格 */
    class SetCell(
        private val cardId: String,
        private val row: Int, private val col: Int,
        private val before: String, private val after: String,
        private val apply: (Int, Int, String) -> Unit
    ) : Op {
        override val label = "改单元格 ($row,$col)：$before → $after"
        override fun undo() { apply(row, col, before); ZxRef.propagate(cardId, "rows[$row][$col]", before) }
        override fun redo() { apply(row, col, after);  ZxRef.propagate(cardId, "rows[$row][$col]", after) }
    }

    /** 看板移动卡片 */
    class MoveCard(
        private val cardId: String, private val item: String,
        private val from: Int, private val to: Int,
        private val move: (Int, Int, String) -> Unit
    ) : Op {
        override val label = "移动「$item」：第${from + 1}列 → 第${to + 1}列"
        override fun undo() { move(to, from, item) }
        override fun redo() { move(from, to, item) }
    }

    /** diff 块开关 */
    class ToggleHunk(
        private val idx: Int,
        private val set: (Int, Boolean) -> Unit
    ) : Op {
        override val label = "${if (idx >= 0) "切换" else ""}改动块 #${idx + 1}"
        override fun undo() { set(idx, false) }
        override fun redo() { set(idx, true) }
    }

    /* ── 持久化 ─────────────────────────────────────────────── */

    private var storage: KeyValue? = null

    /** 宿主注入的存储实现（PluginContext.storage） */
    fun attach(kv: KeyValue) { storage = kv }

    interface KeyValue {
        fun getString(key: String): String?
        fun putString(key: String, value: String)
        fun remove(key: String)
    }

    private fun key(sessionId: String, cardId: String) = "zx.state.$sessionId.$cardId"

    /** 落盘：只存用户交互值，DSL 本身不存（那个由会话历史负责） */
    fun save(sessionId: String, cardId: String) {
        val node = ZxRef.get(cardId) ?: return
        if (node.values.isEmpty()) return
        val o = JSONObject()
        node.values.forEach { (k, v) -> o.put(k, v) }
        storage?.putString(key(sessionId, cardId), o.toString())
    }

    fun saveAll(sessionId: String) {
        ZxRef.graph.keys.forEach { save(sessionId, it) }
    }

    /** 还原：会话重建后把值灌回卡片节点，引用下游自动重算 */
    fun restore(sessionId: String): Int {
        var n = 0
        ZxRef.graph.keys.forEach { cardId ->
            val raw = storage?.getString(key(sessionId, cardId)) ?: return@forEach
            val o = runCatching { JSONObject(raw) }.getOrNull() ?: return@forEach
            val node = ZxRef.get(cardId) ?: return@forEach
            o.keys().forEach { path ->
                node.values[path] = o.optString(path)
                ZxRef.propagate(cardId, path, o.optString(path))
            }
            n++
        }
        return n
    }

    fun clearSession(sessionId: String) {
        ZxRef.graph.keys.forEach { storage?.remove(key(sessionId, it)) }
        undoStack.clear(); redoStack.clear(); _depth.value = 0
    }
}
