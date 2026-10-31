package com.ai.assistance.quro.core.mika.uistate

import kotlin.math.hypot

/**
 * 不给编号、按描述指一个元素:[text] 是它显示的字或无障碍名字(打字时也可以是输入框的提示语),[role] 可选,
 * [field] 为真时只找输入框(打字用)。
 */
data class ElementQuery(val text: String, val role: String? = null, val field: Boolean = false)

sealed interface DescribedMatch {
    /** [exact]:名字(或提示语)和描述完全相同;否则是按开头或包含找到的,结果里要说清实际对上的是谁。 */
    data class Found(val element: UiElement, val exact: Boolean) : DescribedMatch
    /** 对得上的不止一个;[candidates] 能操作的在前,其余按读屏顺序。 */
    data class Ambiguous(val candidates: List<UiElement>) : DescribedMatch
    data object None : DescribedMatch
}

/**
 * 在一屏里按描述找唯一的元素。比较前去掉空白、不分大小写。按从严到宽分层找,哪一层先有结果就用哪一层:
 * 名字(或提示语)完全相同 → 名字以描述开头 → 名字包含描述(这一层只认能操作的元素,免得一段正文里碰巧含这几个字)。
 * 同一层里能操作的(打字看能不能输入,其余看能不能点;不可用的不算)优先:恰好一个就是它;
 * 好几个、或一个能操作的都没有却有好几个对上,是 [DescribedMatch.Ambiguous],不猜。[role] 给了时只看这个角色。
 */
object DescribedTarget {
    fun match(elements: List<UiElement>, q: ElementQuery): DescribedMatch {
        val want = norm(q.text)
        if (want.isEmpty()) return DescribedMatch.None
        val pool = elements.filter { e ->
            (!q.field || e.editable) && (q.role == null || e.role.equals(q.role.trim(), ignoreCase = true))
        }
        val tiers: List<Pair<(String) -> Boolean, Boolean>> = listOf(
            { s: String -> s == want } to false, { s: String -> s.startsWith(want) } to false, { s: String -> want in s } to true,
        )
        for ((i, tier) in tiers.withIndex()) {
            val (test, actionableOnly) = tier
            val hits = pool.filter { e -> labels(e, q.field).any(test) && (!actionableOnly || usable(e, q.field)) }
            if (hits.isEmpty()) continue
            val usable = hits.filter { usable(it, q.field) }
            return when {
                usable.size == 1 -> DescribedMatch.Found(usable[0], exact = i == 0)
                usable.isEmpty() && hits.size == 1 -> DescribedMatch.Found(hits[0], exact = i == 0)
                else -> DescribedMatch.Ambiguous(usable + (hits - usable.toSet()))
            }
        }
        return DescribedMatch.None
    }

    /** 能操作:打字看能不能输入,其余看能不能点或输入;页面标了不可用的不算。 */
    fun usable(e: UiElement, field: Boolean): Boolean =
        UiStates.DISABLED !in e.states && (if (field) e.editable else e.clickable || e.editable)

    /** 离 [e] 最近的另一段有名字的字(和它不同名):候选同名时靠它分辨是哪一个。 */
    fun neighbour(e: UiElement, elements: List<UiElement>): String? = elements
        .filter { it !== e && it.name.isNotBlank() && norm(it.name) != norm(e.name) }
        .minByOrNull { hypot((it.centerX - e.centerX).toDouble(), (it.centerY - e.centerY).toDouble()) }?.name

    /** 元素能被描述的字:名字;输入框还有它自己的提示语。 */
    private fun labels(e: UiElement, field: Boolean): List<String> =
        listOfNotNull(e.name, e.placeholder.takeIf { field || e.editable }).map(::norm).filter { it.isNotEmpty() }

    private fun norm(s: String) = s.filterNot(Char::isWhitespace).lowercase()
}
