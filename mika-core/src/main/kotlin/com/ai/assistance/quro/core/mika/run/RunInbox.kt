package com.ai.assistance.quro.core.mika.run

import com.ai.assistance.quro.core.mika.action.ConfirmChoice
import com.ai.assistance.quro.core.mika.tool.HandBack
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow

/** 运行中的输入唯一入口;用户原话无限排队且逐字保留,停止信号直接送达。 */
class RunInbox {
    data class UserSteer(val messageId: String, val text: String)

    internal interface Handler {
        suspend fun answer(text: String, askId: String?): Boolean
        suspend fun confirm(choice: ConfirmChoice, askId: String?): Boolean
        suspend fun handBack(result: HandBack, askId: String?): Boolean
        fun cancel()
    }

    private val lock = Any()
    private val steers = ArrayDeque<UserSteer>()
    @Volatile private var handler: Handler? = null
    @Volatile private var closed = false
    @Volatile private var stopRequested = false
    private var remaining: List<UserSteer> = emptyList()
    /** 每句插话都唤醒正在等待的模型请求(撤掉它,带着这句话重问);正在执行的工具不受影响。 */
    internal val redirects = Channel<Unit>(Channel.CONFLATED)
    private val pending = MutableStateFlow(false)
    /** 有插话还没交给模型:撤掉也不丢东西的等待据此提前结束(见 [waitOrSteer])。 */
    val signal = SteerSignal(pending)

    internal fun bind(to: Handler) {
        handler = to
        if (stopRequested) to.cancel()
    }

    val wasCancelled: Boolean get() = stopRequested

    fun cancel(): InputReceipt {
        if (closed) return InputReceipt.Closed
        stopRequested = true
        handler?.cancel()
        return InputReceipt.Accepted
    }

    suspend fun send(input: RunInput): InputReceipt = when (input) {
        is RunInput.Steer -> queue(input.messageId, input.text)
        is RunInput.Redirect -> queue(input.messageId, input.text)
        RunInput.Cancel -> cancel()
        is RunInput.Answer -> if (closed) InputReceipt.Closed else
            if (handler?.answer(input.text, input.askId) == true) InputReceipt.Accepted else InputReceipt.NoPendingInteraction
        is RunInput.Confirm -> if (closed) InputReceipt.Closed else
            if (handler?.confirm(input.choice, input.askId) == true) InputReceipt.Accepted else InputReceipt.NoPendingInteraction
        is RunInput.HandBack -> if (closed) InputReceipt.Closed else
            if (handler?.handBack(input.result, input.askId) == true) InputReceipt.Accepted else InputReceipt.NoPendingInteraction
    }

    private fun queue(id: String, text: String): InputReceipt = synchronized(lock) {
        if (closed) InputReceipt.NextTask else {
            steers += UserSteer(id, text)
            pending.value = true
            redirects.trySend(Unit)
            InputReceipt.Accepted
        }
    }

    /** 有没有还没交给模型的插话;只看不取(批量执行在子步之间据此停下)。 */
    fun hasSteers(): Boolean = synchronized(lock) { steers.isNotEmpty() }

    /** 只在模型调用之前或一批工具完成之后取;清除已不再需要的 redirect 唤醒。 */
    internal fun takeSteers(): List<UserSteer> = synchronized(lock) {
        val out = steers.toList()
        steers.clear()
        pending.value = false
        while (redirects.tryReceive().isSuccess) Unit
        out
    }

    /** 收尾以后还没消费的插话交给宿主作为下一任务。 */
    internal fun close(): List<UserSteer> = synchronized(lock) {
        if (closed) return@synchronized remaining
        closed = true
        handler = null
        redirects.close()
        pending.value = false
        remaining = steers.toList().also { steers.clear() }
        remaining
    }

    /**
     * 要交出回答、就此收尾时调用:还有没交给模型的插话就不关(返回 false,调用方带着它接着做);
     * 没有就关上(返回 true),此后的插话才成为下一任务。看和关在同一把锁里,中间插不进话。
     */
    internal fun closeUnlessSteered(): Boolean = synchronized(lock) {
        if (steers.isNotEmpty()) return@synchronized false
        close()
        true
    }

    fun nextTaskMessages(): List<UserSteer> = synchronized(lock) { remaining.toList() }

    /** 宿主收尾或取消尚未开跑的任务时，把未消费的用户原话交给下一个任务。 */
    fun handoffUnconsumed(): List<UserSteer> = synchronized(lock) {
        close()
        remaining.also { remaining = emptyList() }
    }
}
