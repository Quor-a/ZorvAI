package com.ai.assistance.quro.core.mika.run

import com.ai.assistance.quro.core.mika.action.ConfirmChoice

/** 渠道入站在这里验主人、问题编号与确认权限；普通新消息始终新开任务。 */
class ChannelGateway(
    private val owners: OwnerIdentity,
    private val activeAsk: (String) -> Delivery.Ask?,
    private val route: Route,
) {
    interface Route {
        suspend fun newTask(peer: ChannelPeer, messageId: String, text: String)
        suspend fun answer(threadId: String, askId: String, text: String): Boolean
        suspend fun confirm(threadId: String, askId: String, choice: ConfirmChoice): Boolean
        suspend fun stop(runId: RunId)
        suspend fun cancelAsk(threadId: String)
    }

    fun start(channel: Channel): AutoCloseable = channel.start(ChannelInputSink { input ->
        if (input.peer.channelId != channel.id || !owners.isOwner(input.peer)) return@ChannelInputSink
        when (input) {
            is ChannelInput.Stop -> route.stop(input.runId)
            is ChannelInput.Text -> {
                val ask = activeAsk(input.peer.threadId)
                if (input.replyToAskId != null && ask?.askId == input.replyToAskId && ask.kind == Delivery.Ask.Kind.Input) {
                    route.answer(input.peer.threadId, ask.askId, input.text)
                } else {
                    if (ask != null) route.cancelAsk(input.peer.threadId)
                    route.newTask(input.peer, input.messageId, input.text)
                }
            }
            is ChannelInput.Confirm -> {
                val ask = activeAsk(input.peer.threadId)
                if (ask?.askId == input.askId && ask.kind == Delivery.Ask.Kind.Confirm && channel.confirms) {
                    route.confirm(input.peer.threadId, ask.askId, input.choice)
                }
            }
        }
    })
}
