package com.ai.assistance.quro.dshmobile

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure helper fixtures: no Binder, Android process, shell, su, or device execution is needed. */
class ShizukuCaptureSettlementFixtureTest {
  private class FakeProcess(private val input: InputStream, private val done: Boolean = true,
    private val code: Int = 0, private val waiting: () -> Unit = {}) : Process() {
    override fun getInputStream(): InputStream = input
    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean { waiting(); return done }
    override fun waitFor(): Int = if (done) code else throw UnsupportedOperationException()
    override fun exitValue(): Int = if (done) code else throw IllegalThreadStateException()
    override fun destroy() {}
    override fun destroyForcibly(): Process = this
  }

  private fun withSpool(test: (File) -> Unit) {
    val folder = Files.createTempDirectory("capture-settlement-fixture").toFile()
    try { test(File(folder, "capture.out")) }
    finally { folder.deleteRecursively() }
  }

  @Test fun liveDrainerCannotPublishAReadyPathOrChangeTheReturnedBytes() = withSpool { file ->
    val retained = CountDownLatch(1)
    val releaseRead = CountDownLatch(1)
    val releaseClose = CountDownLatch(1)
    val eof = CountDownLatch(1)
    val stream = object : InputStream() {
      private var step = 0
      override fun read(): Int = throw UnsupportedOperationException()
      override fun read(buf: ByteArray, offset: Int, length: Int): Int = when (step++) {
        0 -> "first".toByteArray().also { it.copyInto(buf, offset) }.size
        1 -> {
          retained.countDown(); releaseRead.await()
          "late".toByteArray().also { it.copyInto(buf, offset) }.size
        }
        else -> { eof.countDown(); -1 }
      }
      override fun close() { releaseClose.await() }
    }
    try {
      val result = ShizukuCaptureIo.capture(FakeProcess(stream, waiting = {
        assertTrue(retained.await(1, TimeUnit.SECONDS))
      }), file, 10, 16, 1_024, drainMs = 25, cleanupMs = 50)
      assertFalse(result.exitTimedOut)
      assertTrue(result.drainTimedOut)
      assertTrue(result.cleanupIncomplete)
      assertFalse(result.complete)
      assertFalse(result.spoolReady)
      assertEquals("", result.path)
      assertFalse("incomplete writer must retain only its private .part", file.exists())
      assertEquals(5L, result.size)
      assertArrayEquals("first".toByteArray(), result.inline)
      val callerBytes = result.inline
      callerBytes[0] = 0
      releaseRead.countDown()
      assertTrue(eof.await(1, TimeUnit.SECONDS))
      assertEquals(5L, result.size)
      assertArrayEquals("first".toByteArray(), result.inline)
      assertEquals("", result.path)
    } finally { releaseRead.countDown(); releaseClose.countDown() }
  }

  @Test fun readExceptionNeverBecomesOkOrAReadySpool() = withSpool { file ->
    val stream = object : InputStream() {
      override fun read(): Int = throw IOException("capture-reader-failed")
    }
    val result = ShizukuCaptureIo.capture(FakeProcess(stream), file, 10, 16, 1_024)
    assertFalse(result.complete)
    assertFalse(result.spoolReady)
    assertNotNull(result.readError)
    assertEquals("", result.path)
    assertFalse(file.exists())
  }

  @Test fun cappedSpoolIsExplicitlyTruncatedAndNotAReadyCompleteResult() = withSpool { file ->
    val result = ShizukuCaptureIo.capture(FakeProcess(ByteArrayInputStream("abcdef".toByteArray())),
      file, 10, 2, 3)
    assertTrue(result.truncated)
    assertEquals(3L, result.size)
    assertArrayEquals("ab".toByteArray(), result.inline)
    assertFalse(result.complete)
    assertFalse(result.spoolReady)
    assertEquals("", result.path)
    assertFalse(file.exists())
  }

  @Test fun acknowledgedNonzeroExitStillHasAnImmutableCompleteOutcome() = withSpool { file ->
    val result = ShizukuCaptureIo.capture(FakeProcess(ByteArrayInputStream("failure-output".toByteArray()),
      code = 7), file, 10, 32, 1_024)
    assertTrue(result.complete)
    assertTrue(result.spoolReady)
    assertEquals(7, result.exitCode)
    assertEquals(file.absolutePath, result.path)
    assertArrayEquals("failure-output".toByteArray(), file.readBytes())
    val callerBytes = result.inline
    callerBytes[0] = 0
    assertArrayEquals("failure-output".toByteArray(), result.inline)
    assertArrayEquals("failure-output".toByteArray(), file.readBytes())
  }

  @Test fun parentKillAcknowledgementIsNotDescendantDrainSettlement() = withSpool { file ->
    val retained = CountDownLatch(1)
    val release = CountDownLatch(1)
    val stream = object : InputStream() {
      override fun read(): Int { retained.countDown(); release.await(); return -1 }
      override fun close() {} // Parent cleanup cannot close a descriptor held by its descendant.
    }
    try {
      val result = ShizukuCaptureIo.capture(FakeProcess(stream, done = false, waiting = {
        assertTrue(retained.await(1, TimeUnit.SECONDS))
      }), file, 10, 16, 1_024, drainMs = 25, cleanupMs = 50)
      assertTrue(result.exitTimedOut)
      assertTrue(result.drainTimedOut)
      assertTrue(result.cleanupIncomplete)
      assertFalse(result.spoolReady)
      assertFalse(result.complete)
      assertEquals("", result.path)
    } finally { release.countDown() }
  }
}
