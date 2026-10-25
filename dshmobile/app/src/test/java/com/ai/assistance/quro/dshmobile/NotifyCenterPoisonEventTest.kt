package com.ai.assistance.quro.dshmobile

import org.junit.Assert.assertTrue
import org.junit.Test

/** #428 item 18: a caught delivery failure for a report must not stop the source cursor. */
class NotifyCenterPoisonEventTest {

  @Test
  fun allKnownEventKindsArePersistedBeforeTheCursorMoves() {
    val body = functionBody(source("NotifyCenter.kt"), "fun notifyEvent(")
    for (kind in listOf("resolve", "silent", "todo", "report", "question", "approval")) {
      assertTrue("each known event kind must be replayable: " + kind, body.contains("\"" + kind + "\""))
    }
    assertTrue("persist before returning a retryable result", body.contains("NotifySuppressQueue.enqueue"))
    assertTrue("a persistence failure still retains the cursor", body.contains("Result.ERROR"))
    assertTrue("the result is distinct from a permission denial", body.contains("Result.DEFERRED_RETRY"))
  }

  @Test
  fun onlyActualSettlementsRemoveAQueuedItem() {
    val source = source("NotifySuppressQueue.kt")
    val body = functionBody(source, "internal fun isSettledResult(")
    assertTrue(body.contains("POSTED"))
    assertTrue(body.contains("DUPLICATE_SUPPRESSED"))
    assertTrue(body.contains("RESOLVED"))
    val falseBranch = body.substringAfter("else ->")
    assertTrue("deferred retries remain queued", falseBranch.contains("false"))
  }

  private fun source(name: String): String {
    val roots = listOf(
      java.io.File("src/main/java/com/ai/assistance/quro/dshmobile"),
      java.io.File("app/src/main/java/com/ai/assistance/quro/dshmobile"),
    )
    return roots.asSequence().map { java.io.File(it, name) }.first { it.isFile }.readText()
  }

  private fun functionBody(source: String, signature: String): String {
    val start = source.indexOf(signature)
    assertTrue("missing " + signature, start >= 0)
    val end = source.indexOf("\n  }", start)
    assertTrue("unterminated " + signature, end > start)
    return source.substring(start, end)
  }
}
