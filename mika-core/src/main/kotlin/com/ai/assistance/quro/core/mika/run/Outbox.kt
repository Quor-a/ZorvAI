package com.ai.assistance.quro.core.mika.run

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException

enum class DeliveryState { Pending, Attempting, Delivered, Failed, Uncertain }

data class DeliveryRecord(val peer: ChannelPeer, val delivery: Delivery, val state: DeliveryState)

interface OutboxStore {
    fun get(id: String): DeliveryRecord?
    fun save(record: DeliveryRecord)
    fun all(): List<DeliveryRecord>
}

class MemoryOutboxStore : OutboxStore {
    private val records = linkedMapOf<String, DeliveryRecord>()
    @Synchronized override fun get(id: String): DeliveryRecord? = records[id]
    @Synchronized override fun save(record: DeliveryRecord) { records[record.delivery.id] = record }
    @Synchronized override fun all(): List<DeliveryRecord> = records.values.toList()
}

/** 先记账再发送。结果不明的发送不自动重试，以免用户收到两份。 */
class Outbox(private val store: OutboxStore = MemoryOutboxStore()) {
    private val lock = Mutex()

    suspend fun deliver(channel: Channel, peer: ChannelPeer, delivery: Delivery): DeliveryRecord = lock.withLock {
        require(peer.channelId == channel.id)
        val old = store.get(delivery.id)
        if (old != null) {
            require(old.peer == peer && old.delivery == delivery) { "delivery id used for different content" }
            if (old.state == DeliveryState.Attempting) {
                return@withLock old.copy(state = DeliveryState.Uncertain).also(store::save)
            }
            if (old.state != DeliveryState.Pending) return@withLock old
        }
        val pending = old ?: DeliveryRecord(peer, delivery, DeliveryState.Pending).also(store::save)
        if (!channel.isAlive(peer)) return@withLock pending.copy(state = DeliveryState.Failed).also(store::save)
        store.save(pending.copy(state = DeliveryState.Attempting))
        val state = try {
            when (channel.send(peer, delivery)) {
                SendOutcome.Delivered -> DeliveryState.Delivered
                SendOutcome.Failed -> DeliveryState.Failed
                SendOutcome.Uncertain -> DeliveryState.Uncertain
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Throwable) { DeliveryState.Uncertain }
        pending.copy(state = state).also(store::save)
    }

    fun pendingRecovery(): List<DeliveryRecord> = store.all().filter { it.state == DeliveryState.Pending }
    fun uncertain(): List<DeliveryRecord> = store.all().filter { it.state == DeliveryState.Attempting || it.state == DeliveryState.Uncertain }
    fun get(id: String): DeliveryRecord? = store.get(id)
}
