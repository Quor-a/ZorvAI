package com.ai.assistance.quro.dshmobile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 0.14.10 审计 §6.1 同批回归：applyAssetPatch 的快路径**必须**保留内容比对兜底。
 *
 * 缺陷风险（安全敏感面）：applyAssetPatch 会把上游 package 的 lib/index.js 覆盖成我们的补丁
 * 版本。若把快路径做成「只看 mtime/size 就跳过」，一旦产物被写坏（同尺寸改写、字节翻转、
 * 半截写入后还原时间戳），就会永久判「已打好」——缺陷被静默固化，而运行的是未打补丁的上游代码。
 *
 * 所以本文件的**核心用例**是：构造一份 size 与 mtime 都与记录完全相同、内容却不同的目标文件，
 * 断言它仍被判为不一致、被资产覆盖（快路径不得命中）。
 *
 * 全部用真实文件（TemporaryFolder）+ 内存资产，不碰 Android 类。
 */
class AssetPatchFastPathTest {

  @get:Rule
  val tmp = TemporaryFolder()

  private lateinit var target: File
  private lateinit var record: File
  private lateinit var root: File
  private val assetBytes = "// patched-by-us\nconsole.log(1)\n".toByteArray()
  private val apkIdentity = "53:1710000000000"

  private fun setUpFiles() {
    root = tmp.newFolder("runtime-" + System.nanoTime())
    target = File(root, "lib/index.js")
    record = File(tmp.newFolder("records-" + System.nanoTime()), "patched_index.js.rec")
  }

  private fun apply(readAsset: () -> ByteArray = { assetBytes }): AssetPatchOutcome =
    applyAssetPatchCore("patched/index.js", target, record, apkIdentity, readAsset)

  // ── ① 核心反证：mtime/size 相同但内容不同 ⇒ 必须走内容比对并改写 ─────────────

  /**
   * **本文件的核心用例（安全边界）**：把目标文件改成同尺寸的另一串字节，并把 mtime 精确还原成
   * 记录里的值；同时把伪造来源做得更狠一点——记录本身也用坏内容的摘要重建；只有**摘要**
   * 能区分它。
   *
   * 断言：快路径不得命中（[assetPatchFastPathHit] 返回 false）、必须回到内容比对、
   * 目标必须被资产覆盖。
   *
   * 反证：若把判据做成「size + mtime 相同即命中」，本条必红（目标保持坏内容，返回 SKIPPED_*）。
   */
  @Test
  fun aSameSizeAndSameMtimeButDifferentContentIsStillOverwritten() {
    setUpFiles()
    assertEquals(AssetPatchOutcome.WRITTEN, apply())
    val goodMtime = target.lastModified()
    val goodSize = target.length()

    // 同尺寸、内容不同的坏字节，并精确还原 mtime。
    val corrupted = ByteArray(assetBytes.size) { (assetBytes[it].toInt() xor 0x20).toByte() }
    assertFalse("前置：坏内容必须与资产不同", corrupted.contentEquals(assetBytes))
    target.writeBytes(corrupted)
    assertEquals("前置：尺寸必须相同", goodSize, target.length())
    assertTrue("前置：必须能把 mtime 还原", target.setLastModified(goodMtime))
    assertEquals("前置：mtime 必须相同", goodMtime, target.lastModified())

    // 元数据全相同的相似性**确实**足以让「只看 size+mtime」的判据误判——本断言把这个陷阱钉住。
    val metadataOnlyHit = assetPatchFastPathHit(
      record = AssetPatchRecord.parse(record.readText()),
      apkIdentity = apkIdentity,
      targetExists = true,
      targetSize = target.length(),
      targetMtime = target.lastModified(),
      targetDigest = null, // 没有内容摘要 ⇒ 不得判命中
    )
    assertFalse("缺内容摘要时不得命中（size/mtime 相同也不行）", metadataOnlyHit)

    // 真判据：算摘要后必须判不命中，且 apply 必须把目标改回资产内容。
    val digestOfCorrupted = sha256Hex(corrupted)
    assertFalse(
      "坏内容的摘要必须与记录不符",
      assetPatchFastPathHit(
        record = AssetPatchRecord.parse(record.readText()),
        apkIdentity = apkIdentity,
        targetExists = true,
        targetSize = target.length(),
        targetMtime = target.lastModified(),
        targetDigest = digestOfCorrupted,
      ),
    )
    assertEquals("必须走内容比对并写入", AssetPatchOutcome.WRITTEN, apply())
    assertTrue("目标必须被修正为资产内容", target.readBytes().contentEquals(assetBytes))
  }

  /**
   * 端到端退化路径：记录被第三方改写（size/mtime 都对、身份也对，但摘要对不上当前内容）
   * ⇒ 必须回到内容比对并修正目标。
   */
  @Test
  fun aRecordWhoseDigestNoLongerMatchesForcesContentComparison() {
    setUpFiles()
    target.parentFile?.mkdirs()
    val corrupted = ByteArray(assetBytes.size) { 0x41 }
    target.writeBytes(corrupted)
    // 伪造一条「只看元数据就完全命中」的记录：身份 / size / mtime 全对，摘要不对。
    val fake = AssetPatchRecord(
      apkIdentity = apkIdentity,
      assetSize = assetBytes.size.toLong(),
      contentSha256 = sha256Hex(assetBytes), // 资产摘要 ≠ 目标当前摘要
      targetSize = target.length(),
      targetMtime = target.lastModified(),
    )
    record.parentFile?.mkdirs()
    record.writeText(fake.encode())

    var assetReads = 0
    val outcome = apply { assetReads++; assetBytes }
    assertEquals("摘要不符 ⇒ 必须走内容比对并写入", AssetPatchOutcome.WRITTEN, outcome)
    assertEquals("必须真的读了资产", 1, assetReads)
    assertTrue("目标必须被修正为资产内容", target.readBytes().contentEquals(assetBytes))
  }

  /** 记录完全缺席（首次启动 / 升级后）⇒ 必须走内容比对，不得凭空判命中。 */
  @Test
  fun anAbsentRecordNeverShortCircuits() {
    setUpFiles()
    assertFalse("没有记录时不得命中快路径", record.exists())
    var assetReads = 0
    val outcome = apply { assetReads++; assetBytes }
    assertEquals(AssetPatchOutcome.WRITTEN, outcome)
    assertEquals("缺记录时必须真的读一次资产做比对", 1, assetReads)
  }

  /** 记录损坏/畸形（半截写入、乱码）⇒ 解析为 null ⇒ 走内容比对，不得当成命中。 */
  @Test
  fun aCorruptRecordIsTreatedAsAbsent() {
    setUpFiles()
    record.parentFile?.mkdirs()
    for (raw in listOf("", "garbage", "53:1 12 nothex 12 1700000000000", "53:1 12 abc", "53:1 -1 abc 12 1")) {
      record.writeText(raw)
      assertEquals("畸形记录必须解析为 null：" + raw, null, AssetPatchRecord.parse(record.readText()))
    }
    assertEquals("畸形记录 ⇒ 仍会读资产并写入", AssetPatchOutcome.WRITTEN, apply())
  }

  // ── ② 快路径确实生效（否则修了个注水的东西） ──────────────────────────────

  /**
   * 正常路径：写入一次后，第二次启动必须**不读资产**直接跳过（本修复的收益）。
   *
   * 反证「假装快路径」：命中不了则 assetReads > 0，本用例即红。
   */
  @Test
  fun aTrustedRecordSkipsTheAssetReadEntirely() {
    setUpFiles()
    assertEquals(AssetPatchOutcome.WRITTEN, apply())
    var assetReads = 0
    val second = apply { assetReads++; assetBytes }
    assertEquals(AssetPatchOutcome.SKIPPED_FAST_PATH, second)
    assertEquals("快路径命中时不得再读资产（本修复的全部收益）", 0, assetReads)
  }

  /** 目标已与资产一致但**还没有记录**（升级后的首启）⇒ 不写文件，但必须补记录。 */
  @Test
  fun anAlreadyEqualTargetWritesNoBytesButRecordsForNextLaunch() {
    setUpFiles()
    target.parentFile?.mkdirs()
    target.writeBytes(assetBytes)
    val mtimeBefore = target.lastModified()
    assertEquals(AssetPatchOutcome.SKIPPED_ALREADY_EQUAL, apply())
    assertEquals("内容一致时不得改写文件", mtimeBefore, target.lastModified())
    assertTrue("必须补记录，否则下次仍然要重读资产比对", record.isFile)
    var assetReads = 0
    assertEquals(AssetPatchOutcome.SKIPPED_FAST_PATH, apply { assetReads++; assetBytes })
    assertEquals(0, assetReads)
  }

  // ── ③ 快路径的所有使能条件都必须真的使能 ──────────────────────────────────

  /** 安装包身份变了（覆盖安装）⇒ 记录必须失效 ⇒ 走内容比对。 */
  @Test
  fun aNewApkIdentityInvalidatesTheRecord() {
    setUpFiles()
    assertEquals(AssetPatchOutcome.WRITTEN, apply())
    var assetReads = 0
    val outcome = applyAssetPatchCore(
      asset = "patched/index.js", target = target, recordFile = record,
      apkIdentity = "54:1710000009999",
      readAsset = { assetReads++; assetBytes },
    )
    assertEquals("换安装包后必须走内容比对", AssetPatchOutcome.SKIPPED_ALREADY_EQUAL, outcome)
    assertEquals("换安装包后必须真的读资产", 1, assetReads)
  }

  /** 目标文件没了（产物被删/换树）⇒ 不得命中，必须重新写入。 */
  @Test
  fun aDeletedTargetIsRecreated() {
    setUpFiles()
    assertEquals(AssetPatchOutcome.WRITTEN, apply())
    assertTrue(target.delete())
    assertEquals(AssetPatchOutcome.WRITTEN, apply())
    assertTrue(target.readBytes().contentEquals(assetBytes))
  }

  /** 目标被 truncate（size 变了）⇒ 不得命中。 */
  @Test
  fun aResizedTargetInvalidatesTheRecord() {
    setUpFiles()
    assertEquals(AssetPatchOutcome.WRITTEN, apply())
    target.writeBytes(assetBytes.copyOf(assetBytes.size - 1))
    var assetReads = 0
    val outcome = apply { assetReads++; assetBytes }
    assertEquals(AssetPatchOutcome.WRITTEN, outcome)
    assertEquals(1, assetReads)
    assertTrue(target.readBytes().contentEquals(assetBytes))
  }

  /** 资产读不出来（打包缺件）⇒ ASSET_MISSING，与旧实现「跳过 + 告警」一致，不得抛。 */
  @Test
  fun anUnreadableAssetIsReportedAndNeverCrashes() {
    setUpFiles()
    val outcome = apply { throw IllegalStateException("asset missing") }
    assertEquals(AssetPatchOutcome.ASSET_MISSING, outcome)
    assertFalse("资产读不出来时不得写入任何东西", target.exists())
  }

  /** 记录序列化往返：编解码必须同口径（否则快路径永远不命中，等于白做）。 */
  @Test
  fun theRecordRoundTripsThroughText() {
    val original = AssetPatchRecord("53:1710000000000", 42L, "a".repeat(64), 42L, 1_700_000_000_000L)
    assertEquals(original, AssetPatchRecord.parse(original.encode()))
    assertEquals("单行（记录文件不允许多行）", 1, original.encode().lines().size)
    assertEquals(null, AssetPatchRecord.parse(null))
  }

  // ── ④ 接线层：生产调用点必须接上核心（撤掉修复即红） ──────────────────────

  private fun source(name: String): String = listOf(
    File("src/main/java/com/ai/assistance/quro/dshmobile", name),
    File("app/src/main/java/com/ai/assistance/quro/dshmobile", name),
  ).firstOrNull { it.isFile }
    ?.readText()
    ?: throw AssertionError("找不到壳侧源码 " + name + "（工作目录 = " + File(".").absolutePath + "）")

  private fun codeOnly(src: String): String = src.lineSequence()
    .filterNot {
      val t = it.trimStart()
      t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
    }
    .joinToString("\n")

  /**
   * 生产路径必须：先跑快路径判据（含内容摘要）、再读资产、再逐字节内容比对——顺序即安全边界。
   */
  @Test
  fun theProductionPathKeepsTheContentComparisonFallback() {
    val code = codeOnly(source("EngineManager.kt"))
    assertTrue("applyAssetPatch 必须接上核心", code.contains("applyAssetPatchCore("))
    val core = code.substringAfter("internal fun applyAssetPatchCore(")
      .substringBefore("internal fun writeAssetPatchRecord(")
    val fastPathAt = core.indexOf("assetPatchFastPathHit(")
    val readAssetAt = core.indexOf("readAsset()")
    val compareAt = core.indexOf("existing.contentEquals(assetBytes)")
    assertTrue("必须仍有快路径判据", fastPathAt >= 0)
    assertTrue("必须仍读资产", readAssetAt > fastPathAt)
    assertTrue("必须仍有逐字节内容比对（兜底），且排在快路径之后", compareAt > readAssetAt)
    assertTrue("内容不一致时必须真的写入", core.contains("target.writeBytes(assetBytes)"))
    assertTrue("快路径必须带内容摘要（否则等于只看 size/mtime）", core.contains("targetDigest = sha256Hex(existing)"))
    // 反证：不得出现「只看 size/mtime 就 return」的绕过形态。
    assertFalse(
      "不得用「size/mtime 相同即跳过」的裸判据替代内容比对",
      Regex("if \\(target\\.length\\(\\) == [^)]*\\) return").containsMatchIn(core),
    )
  }

  /** 快路径判据本体必须比对内容摘要，且摘要缺席一律不命中。 */
  @Test
  fun theFastPathJudgeRequiresAContentDigest() {
    val code = codeOnly(source("EngineManager.kt"))
    val judge = code.substringAfter("internal fun assetPatchFastPathHit(")
      .substringBefore("internal fun applyAssetPatchCore(")
    assertTrue("判据必须有 targetDigest 参数", judge.contains("targetDigest: String?"))
    assertTrue("摘要缺席必须 return false", judge.contains("if (targetDigest == null) return false"))
    assertTrue("摘要必须与记录比对", judge.contains("record.contentSha256"))
    // 纯行为对账（不依赖源码形态）
    val rec = AssetPatchRecord("id", 4L, sha256Hex(byteArrayOf(1, 2, 3, 4)), 4L, 100L)
    assertTrue(assetPatchFastPathHit(rec, "id", true, 4L, 100L, sha256Hex(byteArrayOf(1, 2, 3, 4))))
    assertFalse("摘要不符不得命中", assetPatchFastPathHit(rec, "id", true, 4L, 100L, sha256Hex(byteArrayOf(4, 3, 2, 1))))
    assertFalse("摘要缺席不得命中", assetPatchFastPathHit(rec, "id", true, 4L, 100L, null))
    assertFalse("size 不符不得命中", assetPatchFastPathHit(rec, "id", true, 5L, 100L, sha256Hex(byteArrayOf(1, 2, 3, 4))))
    assertFalse("mtime 不符不得命中", assetPatchFastPathHit(rec, "id", true, 4L, 101L, sha256Hex(byteArrayOf(1, 2, 3, 4))))
    assertFalse("身份不符不得命中", assetPatchFastPathHit(rec, "other", true, 4L, 100L, sha256Hex(byteArrayOf(1, 2, 3, 4))))
  }

  /** 记录必须落在应用私有目录（不得写进会被快照整体换掉的运行时树）。 */
  @Test
  fun theRecordLivesOutsideTheRuntimeTree() {
    val code = codeOnly(source("EngineManager.kt"))
    val recordFile = code.substringAfter("private fun assetPatchRecordFile(")
      .substringBefore("private fun assetPatchApkIdentity(")
    assertTrue("记录必须落在 filesDir 下的独立目录", recordFile.contains("context.filesDir"))
    assertFalse("记录不得写进 usr 运行时树", recordFile.contains("usrDir"))
    assertFalse("记录不得写进 home", recordFile.contains("homeDir"))
  }
}