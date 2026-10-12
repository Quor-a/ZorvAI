package com.zorv.plugin.zxwidget

/**
 * 跨卡表单联动校验。
 *
 * 为什么需要：联动（①）解决的是"一张卡改了，另一张跟着变"，
 * 但跟着变之后可能变出非法组合——比如预算卡改成 2000，
 * 清单卡里已选的 items 加起来是 3500，界面不会报错，
 * 但用户点提交时才发现不成立，白填半天。
 *
 * 所以约束要做成"边填边提示，提交时兜底"：
 *   边填边提示 —— 违反的卡显示错误条，但不阻止继续输入
 *   （阻止输入会让用户以为键盘坏了）
 *   提交时兜底 —— zx_form_submit 前检查，违反就拒绝并说明
 *
 * 约束的双方可以是卡片字段，也可以是跨卡的 {{ref.xxx}}。
 */
object ZxConstraint {

    enum class Op(val symbol: String, val label: String) {
        GT(">", "大于"), GTE("≥", "大于等于"), LT("<", "小于"),
        LTE("≤", "小于等于"), EQ("=", "等于"), NEQ("≠", "不等于")
    }

    data class Rule(
        val id: String,
        /** 被约束方 */
        val cardId: String,
        val field: String,
        val op: Op,
        /** 参照方：可以是常量，也可以是 {{ref.cardId.field}} */
        val other: String,
        /** 违反时的提示，会显示在卡片上 */
        val msg: String
    )

    data class Violation(val rule: Rule, val actual: String, val expected: String)

    private val rules = mutableListOf<Rule>()

    fun add(rule: Rule): Result {
        // 同卡同字段同 op 的重复规则没有意义，会让提示重复两遍
        if (rules.any { it.cardId == rule.cardId && it.field == rule.field &&
                        it.op == rule.op && it.other == rule.other })
            return Result(false, "重复规则：${rule.cardId}.${rule.field} ${rule.op.symbol} ${rule.other}")
        rules.add(rule)
        return Result(true, "已添加约束 ${rule.id}")
    }

    fun remove(id: String): Boolean = rules.removeAll { it.id == id }
    fun clear() = rules.clear()
    fun all(): List<Rule> = rules.toList()

    /** 解析参照值：支持 {{ref.xxx}} 与常量 */
    private fun resolve(other: String): Double? {
        val t = other.trim()
        if (t.startsWith("{{") && t.endsWith("}}")) {
            val path = t.removePrefix("{{").removeSuffix("}}").trim()
            val v = ZxRef.readValue(path.substringBeforeLast('.'), path.substringAfterLast('.'))
            return v?.toDoubleOrNull()
        }
        return t.toDoubleOrNull()
    }

    private fun actualOf(cardId: String, field: String): Double? =
        ZxRef.readValue(cardId, field)?.toDoubleOrNull()

    /** 检查一张卡的所有约束 */
    fun check(cardId: String): List<Violation> {
        val out = mutableListOf<Violation>()
        for (r in rules) {
            if (r.cardId != cardId) continue
            val a = actualOf(r.cardId, r.field)
            val b = resolve(r.other)
            if (a == null || b == null) continue    // 值还不存在，等填了再判
            val ok = when (r.op) {
                Op.GT -> a > b; Op.GTE -> a >= b
                Op.LT -> a < b; Op.LTE -> a <= b
                Op.EQ -> kotlin.math.abs(a - b) < 1e-9
                Op.NEQ -> kotlin.math.abs(a - b) >= 1e-9
            }
            if (!ok) out.add(Violation(r, fmt(a), fmt(b)))
        }
        return out
    }

    fun checkAll(): List<Violation> = rules.map { it.cardId }.distinct().flatMap { check(it) }

    /**
     * 提交前兜底。
     * 返回 false 时提交被拒绝——这是唯一一处会"阻止"用户操作的地方，
     * 因为提交是不可逆的一步，放行非法数据比拦一下代价大得多。
     */
    fun guardSubmit(cardId: String): Result {
        val v = check(cardId)
        if (v.isEmpty()) return Result(true, "")
        return Result(false, buildString {
            appendLine("提交被拦截，${v.size} 条约束不满足：")
            v.forEach { appendLine("  · ${it.rule.msg}（当前 ${it.actual}，要求 ${it.rule.op.symbol} ${it.expected}）") }
            appendLine("改完再提交，或 zx_constraint(op=remove, id=...) 撤掉约束。")
        })
    }

    private fun fmt(v: Double): String =
        if (v == kotlin.math.floor(v) && kotlin.math.abs(v) < 1e9) v.toLong().toString()
        else "%.2f".format(v)

    /** 卡片上显示的错误条文本 */
    fun banner(cardId: String): String? {
        val v = check(cardId)
        if (v.isEmpty()) return null
        return v.joinToString(" · ") { "${it.rule.msg}（${it.actual} vs ${it.expected}）" }
    }

    fun spec(): String = buildString {
        appendLine("跨卡约束 ${rules.size} 条：")
        if (rules.isEmpty()) appendLine("  （无）")
        rules.forEach { r ->
            appendLine("  ${r.id}  ${r.cardId}.${r.field} ${r.op.symbol} ${r.other}")
            appendLine("      违反提示：${r.msg}")
        }
        appendLine()
        appendLine("other 可以是常量，也可以是 {{ref.cardId.field}}")
        appendLine("违反时卡片显示错误条但不阻止输入；提交时才拦截。")
    }

    data class Result(val ok: Boolean, val reason: String)
}
