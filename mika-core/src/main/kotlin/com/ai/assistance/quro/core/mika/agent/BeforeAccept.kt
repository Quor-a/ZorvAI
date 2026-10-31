package com.ai.assistance.quro.core.mika.agent

/**
 * 一段不带调用的回复被当作最终回答收下之前的那一刻(接缝):外部在这里还能插一句,例如任务进行中用户又发来的话。
 * [Accept] 照常收下(再去核对);[Continue] 不收,把 [note] 作为一条提醒交给模型,任务接着做。
 */
sealed interface BeforeAccept {
    data object Accept : BeforeAccept
    data class Continue(val note: String) : BeforeAccept
}
