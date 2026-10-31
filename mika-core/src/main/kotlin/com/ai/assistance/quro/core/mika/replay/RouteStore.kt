package com.ai.assistance.quro.core.mika.replay

/** 已学会的做法。 */
interface RouteStore {
    fun all(): List<Route>
    fun put(r: Route)
    fun remove(id: String)
    fun clear()
    fun count(): Int

    /** 读出 [id] 那条、改写、写回;[change] 返回 null 不改。并发使用的实现要整体加锁,读和写之间不能插进别的改动。 */
    fun update(id: String, change: (Route) -> Route?) {
        all().firstOrNull { it.id == id }?.let(change)?.let(::put)
    }

    /** 同一模板已有路线时用新路线替换旧的(见 [replacing])。并发使用的实现要整体加锁。 */
    fun putReplacing(r: Route) {
        val old = all().filter { it.template == r.template }
        put(replacing(r, old))
        old.filter { it.id != r.id }.forEach { remove(it.id) }
    }
}

/** 同一条路:每步的工具、参数、目标都一样(锚点每次读屏略有出入,不算)。 */
fun samePath(a: Route, b: Route): Boolean =
    a.steps.size == b.steps.size && a.steps.zip(b.steps).all { (x, y) -> x.tool == y.tool && x.argsJson == y.argsJson && x.target == y.target }

/**
 * 替换同模板旧路线时新路线带上什么:成功计数取最大;路径没变就沿用失误计数和停用状态
 * (同一条路之前在哪一步对不上,换个新记录不该抹掉);路径变了是新做法,失误计数从零开始。
 */
fun replacing(r: Route, old: List<Route>): Route {
    val successes = maxOf(r.successes, old.maxOfOrNull { it.successes } ?: 0)
    val same = old.firstOrNull { samePath(it, r) } ?: return r.copy(successes = successes)
    return r.copy(successes = successes, consecutiveMisses = same.consecutiveMisses, missStep = same.missStep, disabled = same.disabled)
}
