package com.ai.assistance.quro.dshmobile

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.14.7 / task-9 快照链路回归：**解码之前的**内嵌资产摘要预检 + 流关闭。
 *
 * 为什么必须有它（真机实证）：华为 SCA-AL00 在 0.14.5-fx-2 与 0.14.6 上**都**启动失败，logcat 唯一
 * 真因是 `org.tukaani.xz.CorruptedInputException: Compressed data is corrupt`；而落盘诊断只有
 * `error=none(boolean-failure-path)`。本文件锁三件事：
 *  ① 资产字节与声明值不一致时，判据必须产出**专属错误码**且 detail 里带 expected/actual/bytes；
 *  ② 摘要计算必须是**流式**的（固定缓冲、不整读入内存），且条目数与字节数如实回报；
 *  ③ 解压流在**异常路径**上也被关闭（D-3 的 try/finally），不靠「正常结束才 close」。
 *
 * 这些用例**不**声称真机的 asset 为何损坏（设备未连接，无法比对 base.apk）；它们只锁
 * 「下一次失败能把真因留档」与「摘要对账的判据真的会红」。
 */
class SnapshotBundledAssetTest {

  private fun streamOf(text: String) = text.toByteArray(Charsets.UTF_8).inputStream()

  // ── ① 判据：命中与不命中都必须可分辨 ────────────────────────────────────────

  @Test
  fun matchingDigestPassesAndMismatchYieldsTheDedicatedCode() {
    val declared = "a".repeat(64)
    val same = SnapshotBundledAsset.Digest(declared, 123L)
    assertEquals("摘要一致必须放行（防「恒红」的假判据）", null, SnapshotBundledAsset.verdict(declared, same))

    val actual = SnapshotBundledAsset.Digest("b".repeat(64), 194_618_624L)
    val bad = SnapshotBundledAsset.verdict(declared, actual)
    assertNotNull("摘要不一致必须判红", bad)
    assertEquals("必须用专属错误码（不与解码器异常混为一谈）", SnapshotBundledAsset.CODE_CORRUPT, bad!!.first)
    // detail 必须自证：这正是 logcat 那条 CorruptedInputException 给不了的三项。
    assertTrue("detail 必须带 expected", bad.second.contains("expected=" + declared))
    assertTrue("detail 必须带 actual", bad.second.contains("actual=" + "b".repeat(64)))
    assertTrue("detail 必须带实际字节数（区分截断与改写）", bad.second.contains("bytes=194618624"))
  }

  @Test
  fun declaredValueMissingDoesNotProduceAFalsePositive() {
    // 声明缺失/非法由 SnapshotFingerprintPolicy 归类（有它自己的码），本层不得重复判红——
    // 否则「安装包缺指纹」会被误报成「资产损坏」，指向完全不同的修法。
    assertEquals(null, SnapshotBundledAsset.verdict(null, SnapshotBundledAsset.Digest("c".repeat(64), 1L)))
    assertEquals(null, SnapshotBundledAsset.verdict("", SnapshotBundledAsset.Digest("c".repeat(64), 1L)))
  }

  // ── ② 摘要必须流式、如实回报大小 ───────────────────────────────────────────

  @Test
  fun digestIsStreamedAndReportsExactByteCount() {
    val body = "x".repeat(200_000)
    val d = SnapshotBundledAsset.digest({ streamOf(body) }, bufferSize = 4096)
    assertEquals("字节数必须如实回报（截断形态靠它区分）", body.length.toLong(), d.bytes)
    // 与 JDK 一次性实现对照：流式分块不得改变结果。
    val expected = java.security.MessageDigest.getInstance("SHA-256")
      .digest(body.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    assertEquals("分块流式的摘要必须与整读一致", expected, d.hex)
    assertEquals(64, d.hex.length)
  }

  @Test
  fun digestReadsAcrossManySmallChunksWithoutBufferingTheWholeStream() {
    // 用 1KB 缓冲读 5MB：证明它**不依赖**「一次性读进来」（194MB 资产不能整读入内存）。
    val chunk = ByteArray(1024) { (it % 251).toByte() }
    val total = 5L * 1024L * 1024L
    val stream = object : java.io.InputStream() {
      var served = 0L
      override fun read(): Int = throw UnsupportedOperationException("必须走批量 read（逐字节读等于把 IO 放大 1 万倍）")
      override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (served >= total) return -1
        val n = minOf(len.toLong(), total - served).toInt()
        System.arraycopy(chunk, 0, b, off, n)
        served += n
        return n
      }
    }
    val d = SnapshotBundledAsset.digest({ stream }, bufferSize = 1024)
    assertEquals(total, d.bytes)
    assertTrue("固定缓冲下摘要必须产出完整 64 位十六进制", Regex("[0-9a-f]{64}").matches(d.hex))
  }

  // ── ④ 诊断字段：内嵌指纹必须与 live 指纹**并列且一眼可分**（task-9 要求 3）────

  @Test
  fun bootFingerprintCarriesEmbeddedAssetValueNextToTheLiveOneWithoutRenaming() {
    val files = java.nio.file.Files.createTempDirectory("snapshot-fp-test").toFile()
    try {
      File(files, ".snapshot-fingerprint").writeText("live-value\n")
      val embedded = "7bdc8e2c".padEnd(64, '0')

      val withEmbedded = LogCollector.bootConfigFingerprint(files, embedded)
      // 既有字段名一个都不能改（有消费方与既有单测）：snapshot= 仍指 live 提交值。
      assertTrue("既有 snapshot= 必须仍是 live 指纹", withEmbedded.contains("snapshot=live-value"))
      // 内嵌值必须**并列**出现且字段名可区分 —— 这正是「内嵌资产坏了」与「live 不一致」一眼可分的判据。
      assertTrue("必须并列记录内嵌资产指纹", withEmbedded.contains("snapshotEmbedded=" + embedded))
      assertFalse("两个字段不得混为一谈", withEmbedded.contains("snapshot=" + embedded))

      // 取不到内嵌值时写 na 而非留空（本文件「绝不留空」的纪律）。
      val withoutEmbedded = LogCollector.bootConfigFingerprint(files)
      assertTrue("取不到内嵌指纹时必须写 na", withoutEmbedded.contains("snapshotEmbedded=na"))
      assertFalse("不得出现空值字段", withoutEmbedded.contains("=,"))
    } finally {
      SnapshotFs.deletePath(files)
    }
  }

  @Test
  fun bootFailLineReportsTheRefreshFailureCodeAndStackInsteadOfTheBooleanPlaceholder() {
    // D-1 的验收形态：调用方拿到真因后，boot-fail.log 必须落出**异常类名 + 栈**，
    // 而不是 `error=none(boolean-failure-path)`（真机失败时只有后者，排障只能靠 logcat）。
    val files = java.nio.file.Files.createTempDirectory("snapshot-bootfail-test").toFile()
    try {
      val cause = SnapshotFsException(
        SnapshotBundledAsset.CODE_CORRUPT, emptyList(),
        "内嵌资产损坏 expected=aaa actual=bbb bytes=194618624",
      )
      LogCollector.writeBootFailTo(
        files, SnapshotBundledAsset.CODE_CORRUPT,
        "refreshSnapshot 返回 false（code=" + SnapshotBundledAsset.CODE_CORRUPT + "）",
        cause, 1_700_000_000_000L, "aaa",
      )
      val text = File(files, "boot-fail.log").readText()
      assertTrue("必须落出真因异常类名", text.contains("error=com.ai.assistance.quro.dshmobile.SnapshotFsException"))
      assertFalse("不得再是布尔占位", text.contains("error=none(boolean-failure-path)"))
      assertTrue("必须有可读栈", text.contains("stack=") && !text.contains("stack=none"))
      assertTrue("稳定错误码必须可见", text.contains(SnapshotBundledAsset.CODE_CORRUPT))
      assertTrue("expected/actual/bytes 必须落盘（这是 logcat 给不了的）", text.contains("expected=aaa") && text.contains("bytes=194618624"))
      assertTrue("内嵌指纹与 live 指纹并列在失败行里", text.contains("snapshotEmbedded=aaa"))
    } finally {
      SnapshotFs.deletePath(files)
    }
  }

  // ── ③ D-3：异常路径上流必须关闭（旧实现只在正常结束时 close）───────────────

  /** 造一个 xz 压缩的 tar（与 [SnapshotExtractorTest] 同一形态：用生产同款编码器写真实字节）。 */
  private fun archive(build: (org.apache.commons.compress.archivers.tar.TarArchiveOutputStream) -> Unit): ByteArray {
    val buf = java.io.ByteArrayOutputStream()
    org.apache.commons.compress.compressors.xz.XZCompressorOutputStream(buf).use { xz ->
      org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(xz).use { tar ->
        build(tar)
        tar.finish()
      }
    }
    return buf.toByteArray()
  }

  private fun writeEntry(
    tar: org.apache.commons.compress.archivers.tar.TarArchiveOutputStream,
    name: String,
    body: ByteArray,
  ) {
    val entry = org.apache.commons.compress.archivers.tar.TarArchiveEntry(name)
    entry.size = body.size.toLong()
    tar.putArchiveEntry(entry)
    tar.write(body)
    tar.closeArchiveEntry()
  }

  /** 只统计「底层流是否被关闭」的包装流（close 会向下传递，因此它代表 xz 链条上的关闭发生了）。 */
  private class TrackingInput(private val inner: java.io.InputStream) : java.io.FilterInputStream(inner) {
    val closed = AtomicBoolean(false)
    override fun close() {
      closed.set(true)
      super.close()
    }
  }

  @Test
  fun extractorClosesTarAndXzStreamsOnTheFailurePath() {
    // 判据：用一个**真实可解码**的归档（含一条不可能落地的条目：在普通文件之下建子路径），
    // 让解压在**条目处理中途**抛错 —— 这正是 D-3 要覆盖的那条路径（旧实现把 tar.close() 放在
    // 函数体末尾，异常时永不执行）。包装流以 close 是否被调到为判据。
    val root = java.nio.file.Files.createTempDirectory("snapshot-close-test").toFile()
    try {
      val stage = File(root, "stage").apply { mkdirs() }
      val bytes = archive { tar ->
        writeEntry(tar, "usr/blocked", "file".toByteArray())
        writeEntry(tar, "usr/blocked/child", "cannot-land".toByteArray())
      }
      val tracking = TrackingInput(java.io.ByteArrayInputStream(bytes))

      val failure = runCatching {
        SnapshotExtractor.extract(tracking, 0L, stage, { _, _ -> })
      }.exceptionOrNull()

      assertTrue("中途落不了地的条目必须抛异常（不是静默返回）", failure != null)
      assertTrue("D-3：异常路径上必须关闭底层流（旧实现只在末尾 tar.close()）", tracking.closed.get())
    } finally {
      SnapshotFs.deletePath(root)
    }
  }
}
