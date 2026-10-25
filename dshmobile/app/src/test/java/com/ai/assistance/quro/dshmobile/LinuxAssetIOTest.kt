package com.ai.assistance.quro.dshmobile

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LinuxAssetIO 的判据回归（0.15.0）：来源面、落点面、摘要/体量面、解包面各自的正反例。
 *
 * 负控是重点（父会话点名五条，全部必须真的变红）：路径穿越、符号链接逃逸、解压炸弹、
 * 摘要不符、字节数不符。下载路径用注入的 [LinuxAssetIO.Opener] 真跑一遍，不碰网络。
 */
class LinuxAssetIOTest {

  private val goodUrl =
    "https://github.com/kelai141/dsh-bionilux/releases/download/v1.2.0/linux-runtime-plugin.tar.xz"

  private fun tempRoot(): File = Files.createTempDirectory("linux-asset-io-test").toFile()

  private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  private fun archive(build: (TarArchiveOutputStream) -> Unit): ByteArray {
    val buf = ByteArrayOutputStream()
    XZCompressorOutputStream(buf).use { xz ->
      TarArchiveOutputStream(xz).use { tar ->
        build(tar)
        tar.finish()
      }
    }
    return buf.toByteArray()
  }

  private fun writeEntry(tar: TarArchiveOutputStream, name: String, body: ByteArray) {
    val entry = TarArchiveEntry(name)
    entry.size = body.size.toLong()
    tar.putArchiveEntry(entry)
    tar.write(body)
    tar.closeArchiveEntry()
  }

  private fun writeSymlink(tar: TarArchiveOutputStream, name: String, target: String) {
    val entry = TarArchiveEntry(name, TarConstants.LF_SYMLINK)
    entry.linkName = target
    tar.putArchiveEntry(entry)
    tar.closeArchiveEntry()
  }

  private fun archiveFile(root: File, bytes: ByteArray, name: String = "asset.tar.xz"): File =
    File(root, name).apply { writeBytes(bytes) }

  // ── 清单字面量面（摘要是路径段的来源） ────────────────────────────────────

  @Test
  fun validatesTheDigestAsAPathSegmentBeforeItCanReachTheFilesystem() {
    val ok = LinuxAssetIO.Spec(goodUrl, "a".repeat(64), 16L)
    assertNull("合法 64 位小写十六进制必须放行", LinuxAssetIO.specVerdict(ok))

    for (bad in listOf("", "..", ".", "../../../app_state", "/abs/path", "staging/../../x")) {
      assertNotNull("含路径语义的摘要必须判红：$bad", LinuxAssetIO.specVerdict(ok.copy(sha256 = bad)))
    }
    for (bad in listOf("a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64), "z".repeat(64))) {
      assertNotNull("非 64 位小写十六进制必须判红：$bad", LinuxAssetIO.specVerdict(ok.copy(sha256 = bad)))
    }
    assertNotNull("负体量必须判红", LinuxAssetIO.specVerdict(ok.copy(size = -1L)))
    assertNotNull(
      "超过兜底上限的体量必须判红",
      LinuxAssetIO.specVerdict(ok.copy(size = LinuxAssetIO.MAX_DOWNLOAD_BYTES + 1L)),
    )

    // 下载面自己也判这道（防调用方绕过 LinuxAssetPrepare 直接调它）：必须**早于**任何文件系统动作。
    val root = tempRoot()
    try {
      val destination = File(root, "x.tar.xz")
      val failure = LinuxAssetIO.download(ok.copy(sha256 = "../../../app_state"), destination, root) { _, _, _ ->
        throw AssertionError("must not fetch")
      }
      assertNotNull(failure)
      assertEquals(LinuxAssetIO.CODE_SPEC_INVALID, failure!!.code)
      assertFalse("不合规清单不得留下任何文件", destination.exists())
    } finally {
      SnapshotFs.deletePath(root)
    }
  }

  // ── 来源面 ────────────────────────────────────────────────────────────────

  @Test
  fun acceptsOnlyVersionedHttpsAssetsFromThisRepoReleases() {
    assertNull("本仓 Releases 的版本化资产必须放行", LinuxAssetIO.urlVerdict(goodUrl))

    // 负控：明文 http（可被中间人改写）
    assertNotNull(LinuxAssetIO.urlVerdict(goodUrl.replace("https://", "http://")))
    // 负控：非白名单主机（即便路径形似）
    assertNotNull(LinuxAssetIO.urlVerdict(goodUrl.replace("github.com", "evil.example.com")))
    // 负控：白名单主机但不是本仓 Releases 资产路径（latest/download 不是版本化资产）
    assertNotNull(
      LinuxAssetIO.urlVerdict(
        "https://github.com/kelai141/dsh-bionilux/releases/latest/download/linux-runtime-plugin.tar.xz",
      ),
    )
    // 负控：其它仓的 Releases（跨仓来源不得放行）
    assertNotNull(LinuxAssetIO.urlVerdict(goodUrl.replace("dsh-bionilux", "dsh-mobile-apk")))
    // 负控：带 query（可被用来改写后续跳转）
    assertNotNull(LinuxAssetIO.urlVerdict(goodUrl + "?x=1"))
    // 负控：资产路径多一段目录（不是恰好 <tag>/<asset>）
    assertNotNull(LinuxAssetIO.urlVerdict(goodUrl.replace("v1.2.0/", "v1.2.0/extra/")))
    // GitHub 资产 CDN 是合法重定向落点（路径/query 由 CDN 生成，只守主机）
    assertNull(LinuxAssetIO.hopVerdict("https://objects.githubusercontent.com/github-production-release-asset/x?y=1"))
    assertNull(LinuxAssetIO.hopVerdict("https://release-assets.githubusercontent.com/x"))
    // 负控：重定向落到非白名单主机必须被拒
    assertNotNull(LinuxAssetIO.hopVerdict("https://evil.example.com/payload.tar.xz"))
    // 负控：重定向降级到 http 必须被拒
    assertNotNull(LinuxAssetIO.hopVerdict("http://objects.githubusercontent.com/x"))
  }

  // ── 落点面 ────────────────────────────────────────────────────────────────

  @Test
  fun resolvesRelativeNamesInsideTheRootOnly() {
    val root = tempRoot()
    try {
      assertNotNull(LinuxAssetIO.resolveInside(root, "plugins/runtime/abc/lib/index.js"))
      // 负控：绝对路径
      assertNull(LinuxAssetIO.resolveInside(root, File(root, "x").absolutePath))
      // 负控：`..` 越界
      assertNull(LinuxAssetIO.resolveInside(root, "../outside"))
      assertNull(LinuxAssetIO.resolveInside(root, "plugins/../../outside"))
      // 负控：空名与 NUL
      assertNull(LinuxAssetIO.resolveInside(root, ""))
      assertNull(LinuxAssetIO.resolveInside(root, "a\u0000b"))
    } finally {
      SnapshotFs.deletePath(root)
    }
  }

  /**
   * 符号链接逃逸负控：走 [SnapshotExtractor.isLinkTargetAllowed] 这个**纯判据**。
   *
   * 为什么不建真实文件系统软链：Windows 上 `Files.createSymbolicLink` 需要特权，测试会以
   * 「客户端没有所需的特权」失败（那是环境限制，不是判据结果）。解包器真正依赖的就是这个纯函数，
   * 直接判它既确定又跨平台；真实软链落地由归档级用例断言「逃逸链接不落地」。
   */
  @Test
  fun rejectsSymlinkTargetsThatEscapeTheRootButAllowsInnerOnes() {
    val root = tempRoot()
    try {
      val env = File(root, LinuxAssetIO.ENV_DIR).apply { mkdirs() }
      val dest = File(env, "stage").apply { mkdirs() }
      val linkParent = File(dest, "lib").apply { mkdirs() }
      val destCanon = dest.canonicalPath
      val rootCanon = env.canonicalPath

      assertTrue(
        "包内相对链接必须放行",
        SnapshotExtractor.isLinkTargetAllowed("./index.js", linkParent, destCanon, rootCanon),
      )
      // 负控：`..` 逃逸到允许根之外
      assertFalse(
        "逃逸到允许根之外必须拒绝",
        SnapshotExtractor.isLinkTargetAllowed("../../../../etc/passwd", linkParent, destCanon, rootCanon),
      )
      // 负控：绝对路径逃逸
      assertFalse(
        "绝对路径逃逸必须拒绝",
        SnapshotExtractor.isLinkTargetAllowed(File(root, "outside/less").absolutePath, linkParent, destCanon, rootCanon),
      )
      // Termux 残留目标（`/data/data/com.termux/...`）的拒绝由本仓既有的 SnapshotExtractorTest 覆盖：
      // 那条判据依赖 POSIX 绝对路径语义，Windows 上 `Paths.get("/data/...")` 不是绝对路径，
      // 断言会因宿主平台而变形 —— 这里只留跨平台确定的那几条。
    } finally {
      SnapshotFs.deletePath(root)
    }
  }

  @Test
  fun refusesToActBeforeTheLinuxEnvironmentIsActuallyLive() {
    val root = tempRoot()
    try {
      // 负控：根目录不在场
      assertNotNull(LinuxAssetIO.environmentVerdict(root))
      // 负控：根在场但控制面状态文件缺席（= 插件没被启用）
      val env = File(root, LinuxAssetIO.ENV_DIR).apply { mkdirs() }
      assertNotNull("控制面未启用时必须拒绝（不当通用下载器）", LinuxAssetIO.environmentVerdict(root))
      // 正控：bootstrap 的 load() 会写出初始状态文件
      File(env, LinuxAssetIO.STATE_FILE).writeText("{\"schemaVersion\":1,\"enabled\":false}\n")
      assertNull(LinuxAssetIO.environmentVerdict(root))
      // 负控：缺 DSH_FILES_DIR
      assertNotNull(LinuxAssetIO.environmentVerdict(null))
    } finally {
      SnapshotFs.deletePath(root)
    }
  }

  // ── 摘要/体量面 ───────────────────────────────────────────────────────────

  @Test
  fun verifiesBothDigestAndByteSize() {
    val body = "linux-runtime".toByteArray()
    val spec = LinuxAssetIO.Spec(goodUrl, sha256(body), body.size.toLong())
    val digest = SnapshotBundledAsset.digest({ body.inputStream() })
    assertNull(LinuxAssetIO.verifyVerdict(digest, spec))

    // 负控：字节数不符（截断/加长）
    val wrongSize = spec.copy(size = body.size + 1L)
    val sizeFailure = LinuxAssetIO.verifyVerdict(digest, wrongSize)
    assertNotNull("字节数不符必须判红", sizeFailure)
    assertTrue(sizeFailure!!.contains("字节数"))

    // 负控：摘要不符
    val wrongDigest = spec.copy(sha256 = "0".repeat(64))
    val digestFailure = LinuxAssetIO.verifyVerdict(digest, wrongDigest)
    assertNotNull("摘要不符必须判红", digestFailure)
    assertTrue(digestFailure!!.contains("SHA-256"))
  }

  // ── 解包面 ────────────────────────────────────────────────────────────────

  @Test
  fun extractsInsideTheRootAndRejectsAnythingThatEscapesIt() {
    val root = tempRoot()
    try {
      val env = File(root, LinuxAssetIO.ENV_DIR).apply { mkdirs() }
      // 正控归档刻意**不含软链**：Android/Windows 上建真实软链需要特权，正控不该依赖它。
      val ok = archive { tar ->
        writeEntry(tar, "lib/index.js", "export const ok = true\n".toByteArray())
      }
      val dest = File(env, "stage").apply { mkdirs() }
      assertNull(LinuxAssetIO.extract(archiveFile(root, ok), dest, env))
      assertEquals("export const ok = true\n", File(dest, "lib/index.js").readText())

      // 负控：解包目标自己越出允许根 → 直接拒绝
      val outside = File(root, "outside").apply { mkdirs() }
      val escapeDest = LinuxAssetIO.extract(archiveFile(root, ok), outside, env)
      assertNotNull("解包目标越出允许根必须判红", escapeDest)
      assertEquals(LinuxAssetIO.CODE_DESTINATION_ESCAPE, escapeDest!!.code)

      // 负控：路径穿越（tar 条目含 ../）—— 解包器对穿越条目是**跳过**（resolveEntry 返回 null），
      // 安全属性是「不得在允许根外产生文件」，所以判据落在落地事实上而不是异常上。
      val traversal = archive { tar -> writeEntry(tar, "../evil.txt", "pwned".toByteArray()) }
      val stage2 = File(env, "stage2").apply { mkdirs() }
      LinuxAssetIO.extract(archiveFile(root, traversal), stage2, env)
      assertFalse("穿越条目不得落地到目标内", File(stage2, "evil.txt").exists())
      assertFalse("穿越条目不得落地到环境根", File(env, "evil.txt").exists())
      assertFalse("穿越条目不得落地到环境根之外", File(root, "evil.txt").exists())
      assertFalse("穿越条目不得落地到环境根的父目录", File(root.parentFile, "evil.txt").exists())

      // 负控：符号链接逃逸 —— 无论解包器选择「跳过不安全链接」还是「中止」，安全属性都必须成立：
      // 逃逸链接不得落地在目标目录内、更不得改写允许根外的文件。
      val linkEscape = archive { tar ->
        writeEntry(tar, "lib/keep.js", "keep".toByteArray())
        writeSymlink(tar, "lib/leak.js", "../../../../etc/passwd")
      }
      val stage3 = File(env, "stage3").apply { mkdirs() }
      LinuxAssetIO.extract(archiveFile(root, linkEscape), stage3, env)
      assertFalse("逃逸符号链接不得落地", File(stage3, "lib/leak.js").exists())
      assertFalse("逃逸目标不得被创建", File(stage3, "etc/passwd").exists())
      assertTrue("同归档里的正常条目仍须解出", File(stage3, "lib/keep.js").exists())

      // 负控：解压炸弹（条目数）—— 复用 SnapshotExtractor 的上限口径
      val bomb = archive { tar -> repeat(6) { writeEntry(tar, "lib/f$it.js", ByteArray(8)) } }
      val bombFailure = LinuxAssetIO.extract(
        archiveFile(root, bomb), File(env, "stage4").apply { mkdirs() }, env,
        SnapshotExtractor.Limits(maxEntries = 5),
      )
      assertNotNull("解压炸弹（条目数超限）必须判红", bombFailure)

      // 负控：解压炸弹（单文件超限）
      val wide = archive { tar -> writeEntry(tar, "lib/one.js", ByteArray(4096)) }
      val wideFailure = LinuxAssetIO.extract(
        archiveFile(root, wide), File(env, "stage5").apply { mkdirs() }, env,
        SnapshotExtractor.Limits(maxSingleFileBytes = 1024),
      )
      assertNotNull("单文件超限必须判红", wideFailure)

      // 负控：归档缺席
      val missing = LinuxAssetIO.extract(File(root, "nope.tar.xz"), File(env, "stage6").apply { mkdirs() }, env)
      assertNotNull(missing)
    } finally {
      SnapshotFs.deletePath(root)
    }
  }

  // ── 下载面（注入 Opener，不碰网络） ────────────────────────────────────────

  @Test
  fun downloadVerifiesBeforePublishingAndCleansUpOnFailure() {
    val root = tempRoot()
    try {
      val env = File(root, LinuxAssetIO.ENV_DIR).apply { mkdirs() }
      File(env, LinuxAssetIO.STATE_FILE).writeText("{}\n")
      val body = "plugin-bytes".toByteArray()
      val spec = LinuxAssetIO.Spec(goodUrl, sha256(body), body.size.toLong())
      val dest = File(env, "cache/runtime.tar.xz")

      // 正控：摘要与体量都对 → 落盘
      val okOpener = LinuxAssetIO.Opener { _, target, _ -> target.writeBytes(body); body.size.toLong() }
      assertNull(LinuxAssetIO.download(spec, dest, env, okOpener))
      assertTrue(dest.isFile)
      assertFalse("tmp 不得留下", File(dest.parentFile, dest.name + ".tmp").exists())

      // 负控：摘要不符 → 判红且不发布
      val wrongDigest = spec.copy(sha256 = "1".repeat(64))
      val digestDest = File(env, "cache/a.tar.xz")
      val digestFailure = LinuxAssetIO.download(wrongDigest, digestDest, env, okOpener)
      assertEquals(LinuxAssetIO.CODE_DIGEST_MISMATCH, digestFailure!!.code)
      assertFalse("校验失败不得留下资产", digestDest.exists())
      assertFalse("校验失败不得留下 tmp", File(digestDest.parentFile, digestDest.name + ".tmp").exists())

      // 负控：字节数不符（下载器多写一个字节）→ 判红且不发布
      val sizeDest = File(env, "cache/b.tar.xz")
      val greedy = LinuxAssetIO.Opener { _, target, _ -> target.writeBytes(body + 0x00); (body.size + 1).toLong() }
      val sizeFailure = LinuxAssetIO.download(spec, sizeDest, env, greedy)
      assertEquals(LinuxAssetIO.CODE_SIZE_MISMATCH, sizeFailure!!.code)
      assertFalse(sizeDest.exists())

      // 负控：下载目标越出允许根 → 判红（且不得触达网络）
      val outsideDest = File(root, "outside.tar.xz")
      val mustNotFetch = LinuxAssetIO.Opener { _, _, _ -> throw AssertionError("must not fetch") }
      val destFailure = LinuxAssetIO.download(spec, outsideDest, env, mustNotFetch)
      assertEquals(LinuxAssetIO.CODE_DESTINATION_ESCAPE, destFailure!!.code)
      assertFalse(outsideDest.exists())

      // 负控：来源不合规 → 判红（同样不得触达网络）
      val badUrl = LinuxAssetIO.Spec("http://evil.example.com/x.tar.xz", spec.sha256, spec.size)
      val urlFailure = LinuxAssetIO.download(badUrl, File(env, "cache/c.tar.xz"), env, mustNotFetch)
      assertEquals(LinuxAssetIO.CODE_URL_REJECTED, urlFailure!!.code)

      // 负控：下载器报失败 → 判红且不留残件
      val failDest = File(env, "cache/d.tar.xz")
      val failing = LinuxAssetIO.Opener { _, _, _ -> -1L }
      assertEquals(LinuxAssetIO.CODE_DOWNLOAD_FAILED, LinuxAssetIO.download(spec, failDest, env, failing)!!.code)
      assertFalse(failDest.exists())

      // 负控：下载体量超过兜底上限 → 判红
      val hugeDest = File(env, "cache/e.tar.xz")
      val huge = LinuxAssetIO.Opener { _, target, max -> target.writeBytes(body); max + 1L }
      val hugeFailure = LinuxAssetIO.download(spec, hugeDest, env, huge)
      assertNotNull(hugeFailure)
      assertFalse(hugeDest.exists())
    } finally {
      SnapshotFs.deletePath(root)
    }
  }

  @Test
  fun downloadFailureLeavesNoPartialAssetEvenWhenTheStreamThrows() {
    val root = tempRoot()
    try {
      val env = File(root, LinuxAssetIO.ENV_DIR).apply { mkdirs() }
      val body = "partial".toByteArray()
      val spec = LinuxAssetIO.Spec(goodUrl, sha256(body), body.size.toLong())
      val dest = File(env, "cache/throw.tar.xz")
      val throwing = LinuxAssetIO.Opener { _, target, _ ->
        target.writeBytes(body)
        throw java.io.IOException("connection reset")
      }
      val failure = LinuxAssetIO.download(spec, dest, env, throwing)
      assertEquals(LinuxAssetIO.CODE_DOWNLOAD_FAILED, failure!!.code)
      assertFalse("异常路径不得留下资产", dest.exists())
      assertFalse("异常路径不得留下 tmp", File(dest.parentFile, dest.name + ".tmp").exists())
    } finally {
      SnapshotFs.deletePath(root)
    }
  }
}
