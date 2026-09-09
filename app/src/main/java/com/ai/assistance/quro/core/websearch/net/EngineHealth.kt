package com.ai.assistance.quro.core.websearch.net

/**
 * EngineHealth —— 引擎健康度跟踪（熔断器）。
 *
 * 解决一个很现实的问题：
 * 在网络受限环境下，Google / DuckDuckGo 这类不可达源每次都要等到超时（8-10 秒）才失败。
 * 多引擎并发时总耗时取决于最慢的那个，于是"明明有一个能用的源"却被拖到十几秒才返回，
 * 体感上就是"联网很慢/经常失败"。
 *
 * 熔断策略：
 * - 连续失败 3 次 → 进入冷却（默认 10 分钟），冷却期内直接跳过，不再消耗超时预算；
 * - 冷却期结束 → 自动放行一次探针请求，成功即恢复；
 * - 从无成功记录的引擎 → 使用更短的超时（4 秒），减少无谓等待。
 *
 * 状态保存在内存中（进程内有效）。如需跨进程保留，可把 snapshot() 结果存入 DataStore。
 */
object EngineHealth {

    private class Stat(
        var failStreak: Int = 0,
        var successCount: Int = 0,
        var lastFailAt: Long = 0L,
        var lastSuccessAt: Long = 0L
    ) {
        val everSucceeded: Boolean get() = successCount > 0
    }

    private val stats = HashMap<String, Stat>()

    private const val FAIL_THRESHOLD = 3
    private const val COOLDOWN_MS = 10 * 60_000L

    /** 快速超时：从未成功过的引擎不值得等满 8 秒 */
    const val FAST_TIMEOUT_MS = 4_000L
    /** 常规超时：已验证可用过的引擎可以多给一点时间 */
    const val NORMAL_TIMEOUT_MS = 9_000L

    @Synchronized
    fun recordSuccess(id: String) {
        val s = stats.getOrPut(id) { Stat() }
        s.failStreak = 0
        s.successCount++
        s.lastSuccessAt = System.currentTimeMillis()
    }

    @Synchronized
    fun recordFailure(id: String) {
        val s = stats.getOrPut(id) { Stat() }
        s.failStreak++
        s.lastFailAt = System.currentTimeMillis()
    }

    /**
     * 当前是否应该跳过该引擎。
     * 冷却期内跳过；冷却结束后放行（给一次自愈机会）。
     */
    @Synchronized
    fun shouldSkip(id: String): Boolean {
        val s = stats[id] ?: return false
        if (s.failStreak < FAIL_THRESHOLD) return false
        return System.currentTimeMillis() - s.lastFailAt < COOLDOWN_MS
    }

    /** 建议超时：未验证过的引擎用快速超时，避免拖慢整体 */
    @Synchronized
    fun timeoutFor(id: String): Long {
        val s = stats[id] ?: return FAST_TIMEOUT_MS
        return if (s.everSucceeded) NORMAL_TIMEOUT_MS else FAST_TIMEOUT_MS
    }

    @Synchronized
    fun everSucceeded(id: String): Boolean = stats[id]?.everSucceeded ?: false

    @Synchronized
    fun summary(): String {
        if (stats.isEmpty()) return "无记录"
        return stats.entries.joinToString("; ") { (k, v) ->
            "$k(成功${v.successCount}次/连续失败${v.failStreak})"
        }
    }

    /** 自检前调用，强制重置熔断状态 */
    @Synchronized
    fun reset() = stats.clear()

    @Synchronized
    fun snapshot(): Map<String, Pair<Int, Int>> =
        stats.mapValues { (_, v) -> v.successCount to v.failStreak }
}
