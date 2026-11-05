package com.ai.assistance.quro.core.mika.agent

import com.ai.assistance.quro.core.mika.net.ProxyFailure
import com.ai.assistance.quro.core.mika.model.ModelError
import kotlin.random.Random

/**
 * 模型调用遇到一时的故障要不要再试、等多久。一时的故障:限流(429)、服务端出错(5xx)、连不上 / 连接被断 / 超时、
 * 流式回复没收尾连接就断了(这时回复还没写进历史、也没执行任何工具,重发是安全的)。
 * 其余错误(Key、额度、模型名、内容被拒、其他 4xx、解析不了)再试也一样,不重试;上下文超长另由压缩处理。
 * 限流带了 Retry-After 就照它等(有上限);其余按指数退避加抖动,每次调用最多重试 [maxRetries] 次。
 * 一个任务里花在等重试上的总时间有上限 [taskWaitBudgetMs](默认 5 分钟,只是安全网):用完之后再出错就直接交出,不再等。
 * 纯函数:抖动由 [jitter] 给(0..1),测试里换成定值。
 */
class RetryPolicy(
    val maxRetries: Int = 3,
    private val baseMs: Long = 1_000,
    private val maxBackoffMs: Long = 8_000,
    private val retryAfterCapMs: Long = 30_000,
    /** 一个任务里所有重试等待加起来的上限。 */
    val taskWaitBudgetMs: Long = 5 * 60_000,
    private val jitter: () -> Double = { Random.nextDouble() },
) {
    init {
        require(maxRetries >= 0 && baseMs > 0 && maxBackoffMs >= baseMs && retryAfterCapMs >= 0 && taskWaitBudgetMs >= 0)
    }

    /**
     * 这次调用已重试 [retriesSoFar] 次后又遇到 [e],本任务已为重试等了 [waitedInTaskMs]:返回再试前要等的毫秒数;
     * 不该再试(不是一时的故障、次数到了、等这一次会超出任务的等待上限)为 null。
     */
    fun delayFor(e: Throwable, retriesSoFar: Int, waitedInTaskMs: Long = 0): Long? {
        if (retriesSoFar >= maxRetries || !transient(e)) return null
        val wait = (e as? ModelError.RateLimited)?.retryAfterMs?.coerceIn(0, retryAfterCapMs) ?: run {
            val ceiling = minOf(maxBackoffMs, baseMs shl minOf(retriesSoFar, 20))
            // 一半固定、一半随机:同时出错的几个任务不会在同一刻再撞上去
            ceiling / 2 + (ceiling / 2 * jitter().coerceIn(0.0, 1.0)).toLong()
        }
        return wait.takeIf { waitedInTaskMs + it <= taskWaitBudgetMs }
    }

    companion object {
        /** 不重试:出错就交出(轻量模型出错时这一步直接改问主模型)。 */
        val NONE = RetryPolicy(maxRetries = 0)

        /** 一时的故障,过一会儿再发同一个请求可能就好了。 */
        fun transient(e: Throwable): Boolean = when (e) {
            is ModelError.RateLimited, is ModelError.Network, is ModelError.StreamCut -> true
            is ModelError.Server -> e.status in 500..599
            // 代理一时没起来、刚重启:过一会儿可能就通了;账号密码不对重试没用
            is ModelError.Proxy -> e.failure == ProxyFailure.Unreachable
            else -> false
        }

        /** 运行记录里这次故障的名字。 */
        fun label(e: Throwable): String = when (e) {
            is ModelError.RateLimited -> "rate_limited"
            is ModelError.Network -> "network"
            is ModelError.StreamCut -> "stream_cut"
            is ModelError.Server -> "server_${e.status}"
            is ModelError.Proxy -> if (e.failure == ProxyFailure.Unreachable) "proxy_unreachable" else "proxy_auth"
            else -> e::class.simpleName.orEmpty()
        }
    }
}
