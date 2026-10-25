package com.ai.assistance.quro.dshmobile

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock

/** Serialize native maintenance against privileged app-controlled dispatch, without blocking UI. */
internal object RootExecutionFence {
  private val lock = ReentrantReadWriteLock(true)
  val maintenanceActive: Boolean get() = lock.isWriteLocked
  val ownsMaintenance: Boolean get() = lock.isWriteLockedByCurrentThread

  private fun busy(): JSONObject = JSONObject().put("ok", false)
    .put("code", "root-maintenance-busy").put("reason", "root-maintenance-busy")
    .put("guidance", "正在维护本应用文件属主，请等待维护结果后再执行特权操作。")

  fun command(context: Context, block: () -> JSONObject): JSONObject {
    RootMaintenanceLease.outstanding(context)?.let { return it }
    val read = lock.readLock()
    val acquired = try { read.tryLock(0, TimeUnit.MILLISECONDS) }
    catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
    if (!acquired) return busy()
    return try { RootMaintenanceLease.outstanding(context) ?: block() } finally { read.unlock() }
  }

  fun maintenance(context: Context, block: () -> JSONObject): JSONObject {
    RootMaintenanceLease.outstanding(context)?.let { return it }
    val write = lock.writeLock()
    val acquired = try { write.tryLock(1, TimeUnit.SECONDS) }
    catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
    if (!acquired) return busy()
    return try { RootMaintenanceLease.outstanding(context) ?: block() } finally { write.unlock() }
  }
}
