package com.ai.assistance.quro.dshmobile

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the actual Android filesystem with disposable records, never the live workspace. */
class IncomingRetentionTest {
  @Test fun oldPendingAndUnclaimedDraftBytesSurviveSweepAndUnreadableIndex() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val root = File(context.cacheDir, "incoming-retention-${System.nanoTime()}").apply { mkdirs() }
    val pending = File(root, "pending.txt")
    val draft = File(root, "draft.txt")
    val orphan = File(root, "expired.txt")
    val oldBytes = byteArrayOf(0, 1, 2, -1, 10, 13)
    val now = System.currentTimeMillis()
    val ttl = 7L * 24 * 60 * 60 * 1000
    try {
      listOf(pending, draft, orphan).forEach {
        it.writeBytes(oldBytes)
        assertTrue(it.setLastModified(now - ttl - 60_000))
      }
      val sessions = File(root, ".sessions").apply { mkdirs() }
      File(sessions, "draft.json").writeText(org.json.JSONObject().put("path", draft.absolutePath).toString())
      File(sessions, "broken.json").writeText("{")
      val unreadable = FileIncoming.readQueuedSessionPaths(sessions)
      assertEquals(null, unreadable)
      assertEquals(0, FileIncoming.sweepWorkspace(root, listOf(pending.absolutePath), unreadable, now, ttl))
      assertTrue(orphan.isFile)
      assertTrue(pending.readBytes().contentEquals(oldBytes))
      assertTrue(draft.readBytes().contentEquals(oldBytes))
      File(sessions, "broken.json").writeText("{}")
      assertEquals(null, FileIncoming.readQueuedSessionPaths(sessions))
      assertEquals(0, FileIncoming.sweepWorkspace(root, listOf(pending.absolutePath), FileIncoming.readQueuedSessionPaths(sessions), now, ttl))
      assertTrue(orphan.isFile)
      // Repair only the synthetic corrupt record; valid references remain authoritative.
      assertTrue(File(sessions, "broken.json").delete())
      val queued = FileIncoming.readQueuedSessionPaths(sessions)
      assertEquals(1, FileIncoming.sweepWorkspace(root, listOf(pending.absolutePath), queued, now, ttl))
      assertFalse(orphan.exists())
      assertTrue(pending.readBytes().contentEquals(oldBytes))
      assertTrue(draft.readBytes().contentEquals(oldBytes))
      assertTrue(File(sessions, "draft.json").isFile)
    } finally {
      root.deleteRecursively()
    }
  }
}
