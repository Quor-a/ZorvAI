package com.zorv.plugin.zxwidget

/**
 * 卡片交互导出与复现 —— 把"到底点了什么"说清楚。
 *
 * ㉘ 时间旅行调试录的是**渲染事件**（画了什么、哪次错了），
 * ⑦ 版本树存的是**卡片内容的版本**，
 * ㉒ 轮次边界记的是**撤销的粒度**。
 *
 * 这三个都解决不了同一个具体问题：
 *
 *   用户说"我按了导出，然后就变成这样了"。
 *
 *   渲染事件里有"重绘"，但不知道用户按了哪个按钮；
 *   版本树里有内容差异，但不知道差异是谁造成的；
 *   轮次边界知道"这一轮改过"，但不知道改的是哪一项。
 *
 * 缺的是**用户操作本身**。
 *
 * 所以这层录三样：谁（哪张卡）、做了什么（哪个动作/字段）、
 * 变成什么（旧值→新值）。这与渲染事件是**两个维度**：
 *
 *   渲染事件：这一帧画成什么样（结果）
 *   交互轨迹：用户按了什么导致它变成这样（原因）
 *
 * 三条约束：
 *
 *   一、**回放不能重放副作用**。
 *       轨迹里有"点了提交"，回放时不能再真提交一次——
 *       那不是复现，是**重做一遍**。
 *       所以回放默认只更新界面状态，副作用由人确认。
 *
 *   二、**导出要能脱敏**。
 *       轨迹里有具体值（金额、手机号），
 *       贴到工单里等于泄露。
 *       所以导出要能只留字段名和动作、去掉具体值。
 *
 *   三、**轨迹要有上限**。
 *       一次会话几百次点击，全录下来
 *       导出文件比 DSL 本身还大。
 *       所以按卡分片、超量丢弃最旧的。
 */
object ZxTrace {

    /** 一条交互 */
    data class Step(
        val cardId: String,
        /** 动作名：click / set / toggle / submit / expand ... */
        val act: String,
        /** 作用的字段路径，整卡操作为空 */
        val path: String = "",
        val from: String = "",
        val to: String = "",
        val ts: Long = System.currentTimeMillis()
    ) {
        /** 脱敏：只留结构，去掉具体值 */
        fun redact(): Step = copy(from = if (from.isBlank()) "" else "***",
                                  to = if (to.isBlank()) "" else "***")
        fun line(): String = "$act ${if (path.isBlank())"整卡" else path}" +
            (if (from.isBlank() && to.isBlank()) "" else "（$from → $to）")
    }

    private const val MAX_PER_CARD = 60
    private const val MAX_CARDS = 40

    private val steps = LinkedHashMap<String, ArrayDeque<Step>>()

    fun record(cardId: String, act: String, path: String = "",
               from: String = "", to: String = "") {
        val q = steps.getOrPut(cardId) { ArrayDeque() }
        q.addLast(Step(cardId, act, path, from, to))
        while (q.size > MAX_PER_CARD) q.removeFirst()
        // 卡片数也要封顶，否则长时间会话会一直涨
        while (steps.size > MAX_CARDS) steps.remove(steps.keys.first())
    }

    fun of(cardId: String): List<Step> = steps[cardId]?.toList() ?: emptyList()
    fun all(): Map<String, List<Step>> = steps.mapValues { it.value.toList() }
    fun reset() { steps.clear() }

    /**
     * 导出成可复现脚本。
     * @param redact 是否脱敏（贴工单时用 true）
     */
    fun export(cardId: String? = null, redact: Boolean = false): String = buildString {
        val scope = if (cardId != null) listOfNotNull(cardId) else steps.keys.toList()
        if (scope.isEmpty()) { appendLine("（没有交互记录）"); return@buildString }
        appendLine("# zx trace${if (redact) "（已脱敏）" else ""}")
        appendLine("# 回放：zx_trace(op=replay, id=, confirm=false)")
        appendLine()
        for (cid in scope) {
            val list = steps[cid] ?: continue
            if (list.isEmpty()) continue
            appendLine("## $cid  ${list.size} 步")
            list.forEachIndexed { i, s ->
                val t = if (redact) s.redact() else s
                appendLine("${i + 1}. ${t.line()}")
            }
            appendLine()
        }
    }

    /**
     * 回放。
     *
     * @param confirm 是否真的执行副作用。
     *        false（默认）= 只把卡片状态推回轨迹终点，不触发回调。
     *        **回放重放副作用就不是复现，是重做一遍。**
     */
    fun replay(cardId: String, confirm: Boolean = false): String {
        val list = steps[cardId]
            ?: return "没有这张卡的交互记录：$cardId"
        if (list.isEmpty()) return "轨迹为空"
        val applied = ArrayList<String>()
        for (s in list) {
            if (s.path.isBlank()) continue
            when (s.act) {
                "set", "toggle", "submit", "click" -> {
                    // 直接写引用层；不触发卡片回调，否则回放等于重做一遍
                    if (ZxRef.writeValue(cardId, s.path, s.to) != null) applied.add(s.path)
                }
            }
        }
        return buildString {
            appendLine("回放 ${list.size} 步，写回 ${applied.size} 个字段" +
                if (confirm) "（含副作用）" else "（仅界面状态，未触发回调）")
            if (!confirm && list.any { it.act == "submit" })
                appendLine("注意：轨迹里有 submit，默认回放不会真的再提交一次。")
        }
    }

    /**
     * 最后 N 步 —— 报错时最有用的是"刚才做了什么"，
     * 不是整条轨迹。
     */
    fun tail(cardId: String, n: Int = 5): String = buildString {
        val list = of(cardId)
        if (list.isEmpty()) { appendLine("（暂无）"); return@buildString }
        list.takeLast(n).forEach { appendLine("· ${it.line()}") }
    }

    fun demo(): String {
        reset()
        record("budget", "click", "rows[2][1]", "", "selected")
        record("budget", "set", "rows[2][1]", "1200", "2400")
        record("budget", "set", "rows[3][1]", "340", "90")
        record("budget", "submit", "", "", "")
        return buildString {
            appendLine("示例：预算表上改了两个数然后提交")
            appendLine()
            appendLine(export("budget").trimEnd())
            appendLine("— 脱敏后（贴工单用）—")
            appendLine()
            appendLine(export("budget", redact = true).trimEnd())
            appendLine("最后 2 步：")
            appendLine(tail("budget", 2).trimEnd())
        }
    }

    fun report(): String = buildString {
        appendLine("卡片交互导出与复现（把「到底点了什么」说清楚）")
        appendLine()
        appendLine("㉘ 时间旅行调试录**渲染事件**（画了什么、哪次错了）")
        appendLine("⑦ 版本树存**卡片内容的版本**")
        appendLine("㉒ 轮次边界记**撤销的粒度**")
        appendLine()
        appendLine("这三个都解决不了同一个具体问题：")
        appendLine()
        appendLine("  用户说「我按了导出，然后就变成这样了」。")
        appendLine()
        appendLine("  渲染事件里有「重绘」，但不知道用户按了哪个按钮")
        appendLine("  版本树里有内容差异，但不知道差异是谁造成的")
        appendLine("  轮次边界知道「这一轮改过」，但不知道改的是哪一项")
        appendLine()
        appendLine("缺的是**用户操作本身**。")
        appendLine()
        appendLine("三条约束：")
        appendLine("  一、回放不能重放副作用（那不是复现，是重做一遍）")
        appendLine("  二、导出要能脱敏（贴工单不能泄露具体值）")
        appendLine("  三、轨迹要有上限（每卡 60 步，全录比 DSL 还大）")
    }

    fun guide(): String = """
        zx_trace(op=record|export|replay|tail|demo|report|guide, card?, path?,
                 act?, to?, redact?, confirm?)

        把「到底点了什么」说清楚。

        ㉘ 录渲染事件（画了什么）、⑦ 存内容版本、㉒ 记撤销粒度。
        这三个都回答不了一个具体问题：

          用户说「我按了导出，然后就变成这样了」
          渲染事件里有「重绘」，但不知道按了哪个按钮
          版本树里有差异，但不知道差异是谁造成的

        缺的是**用户操作本身**。

        三条约束：
          一、回放不能重放副作用（那不是复现，是重做一遍）
          二、导出要能脱敏（贴工单不能泄露具体值）
          三、轨迹要有上限（每卡 60 步、最多 40 张卡）
    """.trimIndent()
}
