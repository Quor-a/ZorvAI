package com.ai.assistance.quro.core.mika.run

import com.ai.assistance.quro.core.mika.action.ConfirmChoice

data class ChannelPeer(val channelId: String, val peerId: String, val threadId: String)

sealed interface Delivery {
    val id: String
    val threadId: String

    data class Ask(
        override val id: String, override val threadId: String, val askId: String,
        val kind: Kind, val text: String,
    ) : Delivery {
        enum class Kind { Input, Confirm, Takeover }
    }

    data class Result(
        override val id: String, override val threadId: String, val text: String,
        val restored: Boolean = false, val title: String = "ZorvAI 任务结果",
    ) : Delivery
    data class Progress(override val id: String, override val threadId: String, val text: String) : Delivery
    data class Notice(override val id: String, override val threadId: String, val text: String) : Delivery
}

sealed interface ChannelInput {
    val peer: ChannelPeer
    data class Text(override val peer: ChannelPeer, val messageId: String, val text: String, val replyToAskId: String? = null) : ChannelInput
    data class Confirm(override val peer: ChannelPeer, val askId: String, val choice: ConfirmChoice) : ChannelInput
    data class Stop(override val peer: ChannelPeer, val runId: RunId) : ChannelInput
}

fun interface ChannelInputSink { suspend fun receive(input: ChannelInput) }

enum class SendOutcome { Delivered, Failed, Uncertain }

/** 渠道负责收发，不决定谁是主人、谁能批准。 */
interface Channel {
    val id: String
    /** 能在这个渠道里回答付款确认(只有付款要问,只在手机上同意)。 */
    val confirms: Boolean
    fun isAlive(peer: ChannelPeer): Boolean = true
    fun start(sink: ChannelInputSink): AutoCloseable
    suspend fun send(peer: ChannelPeer, delivery: Delivery): SendOutcome
}

/** 配对不等于主人；只有已绑定的 peer 才能给 Run 发消息。 */
interface OwnerIdentity {
    fun isOwner(peer: ChannelPeer): Boolean
}

class MemoryOwnerIdentity : OwnerIdentity {
    private val owners = mutableSetOf<Pair<String, String>>()
    @Synchronized fun bind(peer: ChannelPeer) { owners += peer.channelId to peer.peerId }
    @Synchronized fun unbind(peer: ChannelPeer) { owners -= peer.channelId to peer.peerId }
    @Synchronized override fun isOwner(peer: ChannelPeer): Boolean = peer.channelId to peer.peerId in owners
}
