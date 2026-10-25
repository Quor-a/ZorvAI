package com.ai.assistance.quro.dshmobile

import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses separate files/preferences; existing application journals are never rewritten. */
class NotifyDeferredRecoveryTest {
  @Test fun mixedDeferredQueueRecoversAfterPermissionReturns() {
    val target = InstrumentationRegistry.getInstrumentation().targetContext
    val fixture = File(target.cacheDir, "notify-recovery-${System.nanoTime()}").apply { mkdirs() }
    val prefix = "notify-recovery-${System.nanoTime()}-"
    val preferences = mutableSetOf<String>()
    var permission = false
    val context = object : ContextWrapper(target) {
      override fun getApplicationContext(): Context = this
      override fun getFilesDir(): File = fixture
      override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        preferences.add(prefix + name)
        return target.getSharedPreferences(prefix + name, mode)
      }
      override fun checkSelfPermission(name: String): Int =
        if (name == android.Manifest.permission.POST_NOTIFICATIONS && !permission) PackageManager.PERMISSION_DENIED
        else super.checkSelfPermission(name)
    }
    val manager = target.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val ids = mutableListOf<Int>()
    try {
      NotifySuppressQueue.restore(context)
      repeat(9) { i ->
        val entry = NotifyEntry(kind = "question", eventId = prefix + i, title = "Recovery fixture $i")
        ids.add(NotifyCenter.notificationId(entry, NotifyCenter.Face.QUESTION))
        assertEquals(NotifyCenter.Result.DEFERRED_RETRY, NotifyCenter.notifyEvent(context, entry))
      }
      repeat(20) { i ->
        val entry = NotifyEntry(kind = "report", sessionId = prefix + i, eventId = prefix + "r$i", title = "Recovery report $i")
        ids.add(NotifyCenter.notificationId(entry, NotifyCenter.Face.REPORT))
        assertTrue(NotifySuppressQueue.enqueue(context, entry, "report:" + entry.sessionId))
      }
      val raw = requireNotNull(NotifyCenter.prefs(context).getString("notify.deferred.v1", null))
      val pending = NotifySuppressQueue.decodeJournal(raw).first
      assertEquals(9, pending.count { it.entry.kind == "question" })
      assertEquals(8, pending.count { it.entry.kind == "report" })
      assertEquals(17, JSONObject(raw).getJSONArray("pending").length())
      permission = true
      repeat(6) { NotifySuppressQueue.retryRetained(context) }
      assertEquals(0, NotifySuppressQueue.pendingCount())
      val finalRaw = requireNotNull(NotifyCenter.prefs(context).getString("notify.deferred.v1", null))
      assertTrue(NotifySuppressQueue.decodeJournal(finalRaw).first.isEmpty())
      // NotificationManager.activeNotifications is updated asynchronously. Wait for the system
      // service to publish the records before treating an empty snapshot as a delivery failure.
      val questions = ids.take(9)
      val deadline = SystemClock.uptimeMillis() + 5_000
      var missing = questions.filter { id -> manager.activeNotifications.none { it.id == id } }
      while (missing.isNotEmpty() && SystemClock.uptimeMillis() < deadline) {
        Thread.sleep(50)
        missing = questions.filter { id -> manager.activeNotifications.none { it.id == id } }
      }
      assertTrue(
        "all nine waiting questions have a visible system notification; missing=$missing",
        missing.isEmpty(),
      )
    } finally {
      NotifySuppressQueue.reset()
      ids.forEach { manager.cancel(it) }
      preferences.forEach { target.deleteSharedPreferences(it) }
      fixture.deleteRecursively()
    }
  }
}
