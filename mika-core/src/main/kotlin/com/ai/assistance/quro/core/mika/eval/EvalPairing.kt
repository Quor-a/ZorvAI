package com.ai.assistance.quro.core.mika.eval

/** 并行评测的分组:同一组的题同时跑。 */
object EvalPairing {
    /**
     * 按题目顺序贪心:每组从第一道还没分出去的题开始,往后找用到的 App 与组里都不相交的题,直到满 [n] 道。
     * 同一个 App 不能同时在两块屏上,所以 App 有重叠的题不同组;没写 `apps` 的题不知道会用到什么,单独一组。
     */
    fun groups(tasks: List<EvalTask>, n: Int): List<List<EvalTask>> {
        val left = tasks.toMutableList()
        val out = mutableListOf<List<EvalTask>>()
        while (left.isNotEmpty()) {
            val first = left.removeAt(0)
            val group = mutableListOf(first)
            if (first.apps.isNotEmpty()) {
                val used = first.apps.toMutableSet()
                val it = left.iterator()
                while (group.size < n && it.hasNext()) {
                    val t = it.next()
                    if (t.apps.isEmpty() || t.apps.any { a -> a in used }) continue
                    group += t
                    used += t.apps
                    it.remove()
                }
            }
            out += group
        }
        return out
    }
}
