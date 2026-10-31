package com.ai.assistance.quro.core.mika.run

/** 提问从 AgentLoop 发出，由当前渠道负责送达；答案仍只从 RunInbox 回来。 */
interface Interaction {
    suspend fun publish(ask: Delivery.Ask): DeliveryState
    fun resolved(askId: String)
}

/** 今天的界面与通知由 App 状态订阅显示，发布即视作可见。 */
object LocalInteraction : Interaction {
    override suspend fun publish(ask: Delivery.Ask) = DeliveryState.Delivered
    override fun resolved(askId: String) = Unit
}

class ChannelInteraction(
    private val outbox: Outbox,
    private val channel: Channel,
    private val peer: ChannelPeer,
) : Interaction {
    override suspend fun publish(ask: Delivery.Ask): DeliveryState = outbox.deliver(channel, peer, ask).state
    override fun resolved(askId: String) = Unit
}
