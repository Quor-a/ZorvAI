package com.zorv.plugin.zxwidget

import org.json.JSONObject

/**
 * 卡片生命周期 —— 让卡片自己说清"我走到哪一步了、下一步能做什么"。
 *
 * 191 个组件之后，一个真实问题浮出来：
 * **卡片的状态是隐式的**。
 *
 *   一张报价卡：填好了吗？提交了吗？对方处理了吗？
 *   一张审批卡：待确认 → 已确认 → 已执行 → 失败？
 *   一张表单卡：填了一半还是填完了？
 *
 * 现在模型只能靠猜：
 *   看 DSL 里有没有值 → 猜填了没有
 *   看有没有收到 zx_form_submit → 猜提交了没有
 *
 * 猜错一次就是一次错误的下一步。
 * 更糟的是**模型会重复问已经答过的问题**——
 * 因为它不知道那张卡已经走到"已提交"了。
 *
 * 关键设计：生命周期**不侵入渲染器**。
 * 渲染器只管画，生命周期在旁边记阶段。
 * 如果做成"每个组件都要实现 phase()"，
 * 191 个组件就是 191 处要改，而且必然漏。
 *
 * 所以：卡片可以在 DSL 的 `_phases` 里声明自己的阶段，
 * 没声明就用默认三段（draft → active → done）。
 */
object ZxLifecycle {

    /** 一个阶段 */
    data class Phase(
        val key: String,
        val label: String,
        /** 这个阶段下一步能走到哪（key 列表），空 = 终态 */
        val next: List<String> = emptyList(),
        /** 是否终态 */
        val terminal: Boolean = false
    )

    /** 默认三段：适用于绝大多数"看完/填完就结束"的卡片 */
    private val DEFAULT = listOf(
        Phase("draft", "待处理", listOf("active")),
        Phase("active", "进行中", listOf("done", "failed")),
        Phase("done", "已完成", terminal = true),
        Phase("failed", "已失败", terminal = true)
    )

    data class State(
        val cardId: String,
        val phase: String,
        val at: Long,
        /** 走过的路径 */
        val history: List<String>
    )

    private val phases = HashMap<String, List<Phase>>()
    private val states = LinkedHashMap<String, State>()

    /**
     * 声明一张卡的阶段表。
     * DSL 里写 `_phases: [{"k":"draft","n":"待填","next":["filled"]}, ...]`
     */
    fun declare(cardId: String, dsl: JSONObject): List<Phase> {
        val arr = dsl.optJSONArray("_phases")
        val list = if (arr == null || arr.length() == 0) {
            DEFAULT
        } else {
            val out = mutableListOf<Phase>()
            for (i in 0 until minOf(arr.length(), 8)) {
                val p = arr.optJSONObject(i) ?: continue
                val k = p.optString("k")
                if (k.isBlank()) continue
                val nextArr = p.optJSONArray("next")
                val next = (0 until (nextArr?.length() ?: 0))
                    .mapNotNull { nextArr?.optString(it) }
                    .filter { it.isNotBlank() }
                out.add(Phase(k, p.optString("n", k), next,
                              p.optBoolean("end", next.isEmpty())))
            }
            // 声明了但一个都没解析出来：退回默认，不要给一张没有阶段的卡
            if (out.isEmpty()) DEFAULT else out
        }
        phases[cardId] = list
        return list
    }

    fun init(cardId: String, dsl: JSONObject) {
        val list = declare(cardId, dsl)
        if (!states.containsKey(cardId))
            states[cardId] = State(cardId, list.first().key, System.currentTimeMillis(),
                                   listOf(list.first().key))
    }

    fun table(cardId: String): List<Phase> = phases[cardId] ?: DEFAULT
    fun state(cardId: String): State? = states[cardId]

    /**
     * 推进到下一阶段。不在 next 里就拒绝——
     * 否则"已失败"能被莫名推回"已完成"，语义就乱了。
     */
    fun advance(cardId: String, to: String): Pair<Boolean, String> {
        val st = states[cardId]
            ?: return false to "卡片 $cardId 还没有生命周期记录"
        val list = table(cardId)
        val cur = list.firstOrNull { it.key == st.phase }
            ?: return false to "当前阶段 ${st.phase} 不在阶段表里"
        if (cur.terminal)
            return false to "${cur.label} 是终态，不能再推进——要重开请用 reset"
        if (to !in cur.next)
            return false to "从「${cur.label}」不能走到 $to；可选：${cur.next.joinToString(" / ")}"
        val next = list.firstOrNull { it.key == to }
            ?: return false to "阶段 $to 不在阶段表里"
        states[cardId] = State(cardId, to, System.currentTimeMillis(), st.history + to)
        return true to "已进入「${next.label}」" +
            if (next.terminal) "（终态）" else "；下一步可选：${next.next.joinToString(" / ")}"
    }

    /** 回退一步。只退一格，不跳——跳会让历史链断裂 */
    fun back(cardId: String): Pair<Boolean, String> {
        val st = states[cardId] ?: return false to "卡片 $cardId 没有记录"
        if (st.history.size <= 1) return false to "已经在第一个阶段"
        val prev = st.history[st.history.size - 2]
        states[cardId] = State(cardId, prev, System.currentTimeMillis(),
                               st.history.dropLast(1))
        return true to "已回退到「${label(cardId, prev)}」"
    }

    fun reset(cardId: String): Pair<Boolean, String> {
        val list = table(cardId)
        states[cardId] = State(cardId, list.first().key, System.currentTimeMillis(),
                               listOf(list.first().key))
        return true to "已重置到「${list.first().label}」"
    }

    fun label(cardId: String, key: String): String =
        table(cardId).firstOrNull { it.key == key }?.label ?: key

    /** 还有哪些卡没走完——这是模型最该问的一句话 */
    fun pending(): List<String> {
        val out = mutableListOf<String>()
        states.values.forEach { st ->
            val p = table(st.cardId).firstOrNull { it.key == st.phase }
            if (p != null && !p.terminal) out.add(st.cardId)
        }
        return out
    }

    fun remove(cardId: String) { phases.remove(cardId); states.remove(cardId) }
    fun clear() { phases.clear(); states.clear() }
    fun size() = states.size

    fun format(cardId: String): String {
        val st = states[cardId] ?: return "卡片 $cardId 没有生命周期记录"
        val list = table(cardId)
        val cur = list.firstOrNull { it.key == st.phase }
        return buildString {
            appendLine("$cardId：${cur?.label ?: st.phase}")
            appendLine("  路径：${st.history.joinToString(" → ") { label(cardId, it) }}")
            if (cur != null && !cur.terminal)
                appendLine("  下一步可选：${cur.next.joinToString(" / ")}")
            else appendLine("  （终态）")
        }
    }

    fun report(): String = buildString {
        if (states.isEmpty()) { appendLine("还没有卡片声明生命周期。"); return@buildString }
        appendLine("卡片生命周期（共 ${states.size} 张）")
        states.values.forEach { st ->
            val p = table(st.cardId).firstOrNull { it.key == st.phase }
            val mark = if (p?.terminal == true) "✓" else "·"
            appendLine("  $mark ${st.cardId}  ${p?.label ?: st.phase}")
        }
        val pend = pending()
        appendLine()
        appendLine(if (pend.isEmpty()) "全部走完了。"
                   else "${pend.size} 张还没走完：${pend.joinToString(", ")}")
    }

    fun guide(): String = """
        zx_life(op=init|advance|back|reset|get|report|guide, card?, to?)

        卡片生命周期：让卡片自己说清「我走到哪一步了」。

        191 个组件之后，一个真实问题浮出来：
        **卡片的状态是隐式的**。

          一张报价卡：填好了吗？提交了吗？
          一张审批卡：待确认 → 已确认 → 已执行 → 失败？

        现在模型只能靠猜：看 DSL 里有没有值、有没有收到 submit。
        猜错一次就是一次错误的下一步。
        更糟的是模型会重复问已经答过的问题——
        因为它不知道那张卡已经走到「已提交」了。

        声明方式（可选，不写就用默认三段）：
          "_phases":[{"k":"draft","n":"待填","next":["filled"]},
                     {"k":"filled","n":"已填","next":["sent"]},
                     {"k":"sent","n":"已提交","end":true}]

        默认：draft → active → done / failed

        关键：生命周期不侵入渲染器。渲染器只管画，
        生命周期在旁边记阶段。如果做成每个组件都要实现
        phase()，191 个组件就是 191 处要改，而且必然漏。
    """.trimIndent()
}
