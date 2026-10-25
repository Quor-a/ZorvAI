package com.ai.assistance.quro.dshmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #427 item 3 / #428 item 18: an interactive event must be durably deferred before its source
 * cursor moves. A caught notification exception or a missing permission must not become
 * PERMISSION_DENIED, and must not leave an undeliverable poison row at the cursor.
 */
class NotifyPermissionRetryTest {

  @Test
  fun deferredRetryIsADistinctResultFromPermissionDenied() {
    val names = NotifyCenter.Result.values().map { it.name }
    assertTrue("a durable retry result must exist", names.contains("DEFERRED_RETRY"))
    assertTrue("permission denial remains its own result", names.contains("PERMISSION_DENIED"))
    assertTrue("retry is not a silent success", NotifyCenter.Result.DEFERRED_RETRY != NotifyCenter.Result.POSTED)
  }

  @Test
  fun interactivePermissionFailureUsesTheDurableQueue() {
    val source = source("NotifyCenter.kt")
    val body = functionBody(source, "private fun deliverEvent(")
    val permission = body.indexOf("if (!hasPermission(app))")
    val retry = body.indexOf("retry:" , permission)
    val denied = body.indexOf("return Result.PERMISSION_DENIED", permission)
    assertTrue("permission branch exists", permission >= 0)
    assertTrue("interactive item must be persisted", retry > permission)
    assertTrue("non-interactive path still returns permission denied", denied > retry)
  }

  @Test
  fun caughtExceptionDefersInteractiveItemBeforeReturningError() {
    val body = functionBody(source("NotifyCenter.kt"), "fun notifyEvent(")
    val caught = body.indexOf("catch (t: Throwable)")
    val enqueue = body.indexOf("NotifySuppressQueue.enqueue", caught)
    val error = body.indexOf("Result.ERROR", caught)
    assertTrue("exception is caught", caught >= 0)
    assertTrue("interactive item is persisted before advancing", enqueue > caught)
    assertTrue("persistence failure still retains the cursor", error > enqueue)
  }

  @Test
  fun caughtExceptionAlsoDefersReportsSoOnePoisonRowCannotBlockTheStream() {
    val body = functionBody(source("NotifyCenter.kt"), "fun notifyEvent(")
    assertTrue("known non-interactive kinds are replayable", body.contains("setOf(\"resolve\""))
    assertTrue("the replay path is not restricted to interactive faces", !body.contains("val interactive"))
  }

  @Test
  fun drainRetriesRetainedInteractions() {
    val body = functionBody(source("NotifyStore.kt"), "fun drain(context: Context, trigger: String)")
    assertTrue("retained interactive items are retried", body.contains("retryRetained("))
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
