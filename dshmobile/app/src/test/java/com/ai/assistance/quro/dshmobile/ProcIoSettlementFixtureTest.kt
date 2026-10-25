package com.ai.assistance.quro.dshmobile

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-only counterexamples: blocked destroy/close, read failure, and frozen partial snapshots. */
class ProcIoSettlementFixtureTest {
  private class FakeProcess(private val input: InputStream, private val done: Boolean,
    private val waiting: () -> Unit = {}, private val killing: () -> Unit = {}) : Process() {
    override fun getInputStream(): InputStream = input
    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean { waiting(); return done }
    override fun waitFor(): Int = if (done) 0 else throw UnsupportedOperationException()
    override fun exitValue(): Int = if (done) 0 else throw IllegalThreadStateException()
    override fun destroy() {}
    override fun destroyForcibly(): Process { killing(); return this }
  }

  @Test fun blockedDestroyAndCloseCannotExtendCallerDeadlineOrChangeItsSnapshot() {
    val retained = CountDownLatch(1)
    val releaseRead = CountDownLatch(1)
    val releaseClose = CountDownLatch(1)
    val releaseKill = CountDownLatch(1)
    val readerFinished = CountDownLatch(1)
    val stream = object : InputStream() {
      private var step = 0
      override fun read(): Int = throw UnsupportedOperationException()
      override fun read(buf: ByteArray, offset: Int, length: Int): Int = when (step++) {
        0 -> "prefix".toByteArray().also { it.copyInto(buf, offset) }.size
        1 -> {
          retained.countDown()
          releaseRead.await()
          "late-output-that-would-truncate".toByteArray().also { it.copyInto(buf, offset) }.size
        }
        else -> { readerFinished.countDown(); -1 }
      }
      override fun close() { releaseClose.await() }
    }
    val proc = FakeProcess(stream, false, waiting = {
      assertTrue(retained.await(1, TimeUnit.SECONDS))
    }, killing = { releaseKill.await() })
    val call = FutureTask { ProcIo.readBoundedMillis(proc, 1, 8) }
    Thread(call, "fixture-proc-caller").apply { isDaemon = true; start() }
    try {
      val result = call.get(3, TimeUnit.SECONDS)
      assertTrue(result.exitTimedOut)
      assertTrue(result.drainTimedOut)
      assertTrue(result.cleanupIncomplete)
      assertFalse(result.complete)
      assertEquals("prefix", result.text)
      assertFalse(result.truncated)
      releaseRead.countDown()
      assertTrue(readerFinished.await(1, TimeUnit.SECONDS))
      assertEquals("reader publication after return cannot change text", "prefix", result.text)
      assertFalse("nor can late truncation change the returned flag", result.truncated)
    } finally {
      releaseRead.countDown(); releaseClose.countDown(); releaseKill.countDown()
    }
  }

  @Test fun readFailureIsNeitherEofNorKnownEmptyOutput() {
    val failed = CountDownLatch(1)
    val stream = object : InputStream() {
      private var first = true
      override fun read(): Int = throw UnsupportedOperationException()
      override fun read(buf: ByteArray, offset: Int, length: Int): Int {
        if (first) { first = false; "some".toByteArray().copyInto(buf, offset); return 4 }
        failed.countDown()
        throw IOException("fixture-read-failed")
      }
    }
    val result = ProcIo.readBoundedMillis(FakeProcess(stream, true,
      waiting = { assertTrue(failed.await(1, TimeUnit.SECONDS)) }), 10)
    assertEquals("some", result.text)
    assertNotNull(result.readError)
    assertFalse(result.timedOut)
    assertFalse(result.complete)
    assertTrue(result.marker().contains("read-error"))
  }

  @Test fun existingInterruptIsPreservedAndNeverConvertedIntoSuccess() {
    Thread.currentThread().interrupt()
    try {
      val result = ProcIo.readBoundedMillis(FakeProcess(ByteArrayInputStream(ByteArray(0)), true), 10)
      assertTrue(Thread.currentThread().isInterrupted)
      assertTrue(result.exitTimedOut)
      assertFalse(result.complete)
    } finally { Thread.interrupted() }
  }

  @Test fun interruptDuringDrainJoinReturnsWithoutWaitingForTheReader() {
    val waiting = CountDownLatch(1)
    val release = CountDownLatch(1)
    val reader = Thread({ release.await() }, "fixture-held-reader").apply { isDaemon = true; start() }
    val call = FutureTask {
      val result = ProcIo.awaitCompletion(FakeProcess(ByteArrayInputStream(ByteArray(0)), true,
        waiting = { waiting.countDown() }), reader, 10)
      result to Thread.currentThread().isInterrupted
    }
    val caller = Thread(call, "fixture-interrupted-caller").apply { isDaemon = true; start() }
    try {
      assertTrue(waiting.await(1, TimeUnit.SECONDS))
      caller.interrupt()
      val (result, interrupted) = call.get(1, TimeUnit.SECONDS)
      assertTrue(interrupted)
      assertTrue(result.drainTimedOut)
      assertTrue(result.cleanupIncomplete)
    } finally { release.countDown() }
  }
}
