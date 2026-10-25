package com.ai.assistance.quro.dshmobile

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * issue #271 的行为回归：**move 目标残留 → 事务失败 → 半搬态 → 引擎 90s 死亡并反复重启**。
 *
 * 真机现场（ColorOS / arm64，0.14.1 vc40 → 0.14.2-fx-1 vc42）：
 * ```
 * FileSystemException: files/usr -> files/.snapshot-previous/usr: Directory not empty
 *   at SnapshotFs.move(SnapshotFs.kt:89)
 *   at SnapshotTransaction.replaceEntry(SnapshotTransaction.kt:829)
 * ```
 * 之后 live 缺 node ⇒ `exec …/files/usr/bin/node: No such file or directory` ⇒ 引擎在 90s 预算内死亡。
 * 触发它的残留是**非应用属主**的：工厂快照里 `usr/lib/python3.14/x/__pycache__` 被打上 `root:root`，
 * 作者对照实测 app uid 递归删有 32 处 Permission denied、root 删立即成功。
 *
 * 本类的判据分三组，**正反成对**（只做反证会让「加断言」变成「把正常路径也弄脆」）：
 *  A. 反证：目标非空且删不掉 ⇒ `move` 必须**明确失败**（抛 SnapshotFsException，带 code）；
 *  B. 反证：删不掉时必须**抛**（旧的容错 deletePath 会静默返回）；
 *  C. 正证：正常路径（目标已清空 / 不存在）`move` **必须照常成功** —— 不得因加断言而变脆。
 *
 * 为什么能在普通 JVM 上判红：`SnapshotFs.move` 与 `swap` 的 move 原语都**可注入**
 * （本仓既有惯例：`deletePath` 的 `listChildren`/`delete` 就是这么做的）。
 * 注入的替身精确模拟「文件系统拒绝删除」这一设备侧事实——直接依赖真实文件权限做不到
 * （本仓已有实锤记录：删掉兜底后用例照样全绿）。
 */
class SnapshotMoveResidueTest {

  private fun tempDir(): File = Files.createTempDirectory("snapshot-move-residue").toFile()

  /** 目标非空的现场：`previous/usr` 里留着旧树（issue 里那份 9 天前的 `usr/lib`）。 */
  private fun seedNonEmptyPrevious(filesDir: File) {
    val previous = SnapshotTransaction.previousRoot(filesDir)
    File(previous, "usr/lib/python3.14/x/__pycache__").mkdirs()
    File(previous, "usr/lib/python3.14/x/__pycache__/m.pyc").writeText("stale")
  }

  // ── A. move 自保：目标非空且删不掉 ⇒ 明确失败（不是半搬） ──────────────────────────

  @Test
  fun moveRefusesWhenTheDestinationCannotBeEmptied() {
    val filesDir = tempDir()
    try {
      val source = File(filesDir, "source").apply { mkdirs() }
      File(source, "node").writeText("new-node")
      // 目标非空且「删不掉」——issue #271 的真机现场（.snapshot-previous/usr 留着 9 天前的旧树，
      // 里面还混着 root:root 的 __pycache__ ⇒ app uid 删不掉）。
      val destination = File(filesDir, "destination").apply { mkdirs() }
      File(destination, "stale/lib").mkdirs()
      File(destination, "stale/lib/node").writeText("stale-node")

      // 注入 EACCES：列出 stale 这一层被拒（与 SnapshotFsTest 既有的注入惯例同款）。
      val denied = java.nio.file.AccessDeniedException(destination.absolutePath)
      try {
        SnapshotFs.move(source, destination) { p: Path ->
          if (p.fileName.toString() == "stale") throw denied
          Files.newDirectoryStream(p).use { it.toList() }
        }
        fail("目标删不净时 move 必须明确失败（旧实现会硬 move → Directory not empty → 半搬态）")
      } catch (t: SnapshotFsException) {
        assertEquals(CODE_DELETE_RESIDUE, t.code)
        assertTrue("必须点名残留（现场可归因）", t.residue.isNotEmpty())
      }
      // 关键：**不得半搬**——源必须还在原位、目标必须没被换掉。
      assertTrue("源必须仍在原位（没有搬走）", SnapshotFs.exists(File(source, "node")))
      assertEquals("目标内容必须没被动过", "stale-node", File(destination, "stale/lib/node").readText())
    } finally {
      SnapshotFs.deletePath(filesDir)
    }
  }

  /**
   * 真机缺陷链的**端到端**断言：staged → live 的搬入失败时，live 必须被放回原位——
   * 这正是 issue #271 里「`usr` 已被搬走、新树没搬进来 ⇒ node 缺失 ⇒ 引擎 90s 死亡」的判据。
   */
  @Test
  fun swapRestoresLiveWhenTheStagedMoveFails() {
    val filesDir = tempDir()
    try {
      val live = File(filesDir, "live").apply { mkdirs() }
      writeRuntime(live, "old-node", "old-profile")
      val stage = SnapshotTransaction.stageRoot(filesDir)
      writeRuntime(stage, "new-node", "new-profile")

      // 注入：第二条 rename（staged → live）失败，模拟设备上「目标非空/权限」那一次失败。
      var calls = 0
      val flakyMove: (File, File) -> Unit = { from, to ->
        calls += 1
        if (calls == 2) throw IOException("Directory not empty（注入：模拟设备侧 move 失败）")
        SnapshotFs.move(from, to)
      }

      try {
        SnapshotTransaction.swap(
          filesDir, stage, File(live, "usr"), File(live, "home"), emptySet(), "fp1", 1L,
          move = flakyMove,
        )
        fail("搬入失败时 swap 必须抛出（不得留下半搬态）")
      } catch (t: IOException) {
        // 期望路径
      }
      // 核心判据：node 必须还在 live（回滚把 live 放回了原位），而不是「node: No such file or directory」。
      assertTrue(
        "搬入失败后 live 的 usr 必须被放回（否则就是 issue #271 的 node 缺失半搬态）",
        SnapshotFs.exists(File(live, "usr/bin/node")),
      )
    } finally {
      SnapshotFs.deletePath(filesDir)
    }
  }

  // ── B. 严格删除：删不掉必须抛（旧容错版静默返回） ────────────────────────────────────

  @Test
  fun strictDeleteThrowsWhenAnEntryCannotBeRemoved() {
    val root = tempDir()
    try {
      File(root, "usr/lib").mkdirs()
      File(root, "usr/lib/stuck.pyc").writeText("root-owned in the field")
      // 注入「这一层列不动/删不掉」：设备侧是 EACCES（root:root 残留）。
      val denied = java.nio.file.AccessDeniedException(root.absolutePath)
      try {
        SnapshotFs.deletePathStrict(root) { p: Path ->
          if (p.fileName.toString() == "lib") throw denied
          Files.newDirectoryStream(p).use { it.toList() }
        }
        fail("删不净必须抛（旧的容错 deletePath 会静默返回，正是本 issue 的起点）")
      } catch (t: SnapshotFsException) {
        assertEquals(CODE_DELETE_RESIDUE, t.code)
        assertTrue("必须点名残留条目", t.residue.isNotEmpty())
      }
      assertTrue("删不掉时目标必须还在（不得假装删干净）", SnapshotFs.exists(root))
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun strictDeleteSucceedsSilentlyOnAHealthyTree() {
    val root = tempDir()
    try {
      File(root, "usr/bin").mkdirs()
      File(root, "usr/bin/node").writeText("node")
      // 正证：健康树必须照常删净、不抛。
      SnapshotFs.deletePathStrict(root)
      assertFalse(SnapshotFs.exists(root))
    } finally {
      root.deleteRecursively()
    }
  }

  // ── C. 正证：正常路径必须照常成功（不得因加断言而变脆） ─────────────────────────────

  @Test
  fun moveStillWorksWhenTheDestinationIsAbsentOrEmpty() {
    val filesDir = tempDir()
    try {
      // 目标不存在：必须成功。
      val src = File(filesDir, "src").apply { mkdirs() }
      File(src, "node").writeText("n")
      val dstAbsent = File(filesDir, "dst-absent")
      SnapshotFs.move(src, dstAbsent)
      assertTrue("目标不存在时 move 必须成功", SnapshotFs.exists(File(dstAbsent, "node")))

      // 目标存在但为空（正常事务路径：previous 已先删净重建）：必须成功。
      val src2 = File(filesDir, "src2").apply { mkdirs() }
      File(src2, "node").writeText("n2")
      val dstEmpty = File(filesDir, "dst-empty").apply { mkdirs() }
      SnapshotFs.move(src2, dstEmpty)
      assertTrue("目标为空时 move 必须成功", SnapshotFs.exists(File(dstEmpty, "node")))
      assertFalse("源必须已搬走", SnapshotFs.exists(src2))
    } finally {
      SnapshotFs.deletePath(filesDir)
    }
  }

  /**
   * 正常路径的**端到端正证**：live 完整、previous 干净时，`swap` 必须把 staged 换进 live。
   * 这一条是「加了前置断言之后不许把正常升级路径弄坏」的守门判据。
   */
  @Test
  fun swapStillActivatesTheStagedRuntimeOnTheHealthyPath() {
    val filesDir = tempDir()
    try {
      val live = File(filesDir, "live").apply { mkdirs() }
      writeRuntime(live, "old-node", "old-profile")
      val stage = SnapshotTransaction.stageRoot(filesDir)
      writeRuntime(stage, "new-node", "new-profile")

      SnapshotTransaction.swap(
        filesDir, stage, File(live, "usr"), File(live, "home"), emptySet(), "fp1", 1L,
      )

      assertEquals("新运行时必须被换进 live", "new-node", File(live, "usr/bin/node").readText())
      assertEquals("旧运行时必须留在 previous（回滚源）", "old-node",
        File(SnapshotTransaction.previousRoot(filesDir), "usr/bin/node").readText())
    } finally {
      SnapshotFs.deletePath(filesDir)
    }
  }

  // ── D. 预检：非应用属主残留必须在**动树之前**判死 ──────────────────────────────────

  @Test
  fun swapRefusesBeforeTouchingAnythingWhenPreviousHasForeignOwnedResidue() {
    val filesDir = tempDir()
    try {
      val live = File(filesDir, "live").apply { mkdirs() }
      writeRuntime(live, "live-node", "live-profile")
      val stage = SnapshotTransaction.stageRoot(filesDir)
      writeRuntime(stage, "new-node", "new-profile")
      seedNonEmptyPrevious(filesDir)
      val previous = SnapshotTransaction.previousRoot(filesDir)
      val foreign = File(previous, "usr/lib/python3.14/x/__pycache__")

      try {
        SnapshotTransaction.swap(
          filesDir, stage, File(live, "usr"), File(live, "home"), emptySet(), "fp1", 1L,
          ownerProbe = { file -> file != foreign },
        )
        fail("存在非应用属主残留时必须在动树之前判死")
      } catch (t: SnapshotFsException) {
        assertEquals(CODE_FOREIGN_OWNER, t.code)
        assertTrue("必须点名非应用属主条目", t.residue.contains(foreign))
      }
      // 关键：live 必须**完好无损**（这正是「提前判死」相对「半搬后再失败」的价值）。
      assertEquals("预检失败不得动 live", "live-node", File(live, "usr/bin/node").readText())
    } finally {
      SnapshotFs.deletePath(filesDir)
    }
  }

  @Test
  fun foreignOwnedScanFindsNestedEntriesAndRespectsDepth() {
    val root = tempDir()
    try {
      File(root, "a/b/c").mkdirs()
      File(root, "a/b/c/deep.pyc").writeText("x")
      val foreign = File(root, "a/b")
      val found = SnapshotTransaction.foreignOwnedUnder(root, { it != foreign }, 6)
      assertTrue("必须扫到浅层非应用属主条目", found.contains(foreign))
      val tooShallow = SnapshotTransaction.foreignOwnedUnder(root, { it != foreign }, 1)
      assertTrue("深度上限之外不扫（启动路径必须廉价）", !tooShallow.contains(foreign))
    } finally {
      SnapshotFs.deletePath(root)
    }
  }

  // ── E. 失败残渣清理（issue #271 ⑤） ────────────────────────────────────────────────

  @Test
  fun failedResidueFromThePreviousAttemptIsClearedWithoutTheAgeGate() {
    val filesDir = tempDir()
    try {
      // 现场形态：usr.failed-<ts> 与 home/.dsh/profiles.failed-<ts> 并存（数百 MB 全量树副本）。
      val usrFailed = File(filesDir, "usr.failed-1790100675609").apply { mkdirs() }
      File(usrFailed, "bin").mkdirs()
      File(usrFailed, "bin/node").writeText("stale")
      val dshFailed = File(filesDir, "home/.dsh/profiles.failed-1790100675609").apply { mkdirs() }
      File(dshFailed, "x").writeText("stale")
      // 不能被误删的：previous / stage（半程事务的回滚源）。
      val previous = SnapshotTransaction.previousRoot(filesDir).apply { mkdirs() }
      File(previous, "usr").mkdirs()

      val cleared = SnapshotTransaction.clearFailedResidue(filesDir)

      assertFalse("上一轮的失败残渣必须被清（不依赖 30 分钟年龄门槛）", SnapshotFs.exists(usrFailed))
      assertFalse("home/.dsh 下的失败残渣同样要清", SnapshotFs.exists(dshFailed))
      assertTrue("previous 是回滚源，绝不能被这一步删", SnapshotFs.exists(previous))
      assertEquals("必须如实回报清掉了什么", 2, cleared.size)
    } finally {
      SnapshotFs.deletePath(filesDir)
    }
  }

  @Test
  fun failedResidueCleanupRequiresARealWriteSlotAndAStrictTimestamp() {
    val filesDir = tempDir()
    try {
      val oldUsr = File(filesDir, "usr.failed-1790100675609").apply { mkdirs() }
      File(oldUsr, "bin/node").apply { parentFile?.mkdirs() }.writeText("factory residue")
      val oldProfiles = File(filesDir, "home/.dsh/profiles.failed-1790100675609").apply { mkdirs() }
      File(oldProfiles, "web/package.json").apply { parentFile?.mkdirs() }.writeText("factory residue")
      val rootProfiles = File(filesDir, "profiles.failed-1790100675609").apply { mkdirs() }
      File(rootProfiles, "web/package.json").apply { parentFile?.mkdirs() }.writeText("factory residue")

      val unknownBytes = byteArrayOf(0, 1, 2, 127, -1, 10)
      val report = File(filesDir, "report.failed-123").apply { mkdirs() }
      File(report, "report.bin").writeBytes(unknownBytes)
      val unknownProfiles = File(filesDir, "home/.dsh/report.failed-123").apply { mkdirs() }
      File(unknownProfiles, "notes.txt").writeText("user-owned")
      val unknownHome = File(filesDir, "home/sessions.failed-123").apply { mkdirs() }
      File(unknownHome, "session.json").writeText("user-owned")

      val malformed = listOf(
        File(filesDir, "usr.failed-legacy"),
        File(filesDir, "usr.failed-0"),
        File(filesDir, "usr.failed--1"),
        File(filesDir, "home/.dsh/profiles.failed-1790100675609.extra"),
        File(filesDir, SnapshotTransaction.STAGE_ORPHAN_PREFIX + "legacy"),
      ).onEach { it.mkdirs() }
      val oldOrphan = File(filesDir, SnapshotTransaction.STAGE_ORPHAN_PREFIX + "1790100675609").apply { mkdirs() }

      val cleared = SnapshotTransaction.clearFailedResidue(filesDir)

      assertFalse("真实 usr 写点仍须回收", oldUsr.exists())
      assertFalse("真实 profiles 写点仍须回收", oldProfiles.exists())
      assertFalse("compensateFailedProfilesMerge 的 root profiles 写点仍须回收", rootProfiles.exists())
      assertTrue("clearFailedResidue 不得越权清 stage orphan", oldOrphan.exists())
      assertTrue("未知名称必须视为用户数据", report.exists())
      assertTrue("未知名称必须逐字节保留", unknownBytes.contentEquals(File(report, "report.bin").readBytes()))
      assertTrue("home/.dsh 下的未知名称也必须保留", unknownProfiles.exists())
      assertTrue("home 顶层不是 .failed 写点", unknownHome.exists())
      for (entry in malformed) assertTrue("畸形后缀必须保留: " + entry.name, entry.exists())
      assertEquals("回收三个真实 failed 写点", 3, cleared.size)
      val reclaimed = SnapshotTransaction.reclaimResidue(filesDir, 1_790_100_675_609L + 31L * 60L * 1000L)
      assertFalse("reclaimResidue 的年龄窗口仍须回收 stage orphan", oldOrphan.exists())
      assertEquals("年龄窗口只回收 stage orphan", 1, reclaimed.size)
    } finally {
      SnapshotFs.deletePath(filesDir)
    }
  }

  // ── F. 归类码可归因（issue #271 ④） ──────────────────────────────────────────────

  @Test
  fun snapshotFsExceptionsCarryAStableDiagnosableCode() {
    val t = SnapshotFsException(CODE_DELETE_RESIDUE, listOf(File("/x/usr")), "删不净")
    assertEquals(CODE_DELETE_RESIDUE, t.code)
    assertTrue("错误码必须进 message（boot-fail.log 一行就能读出归类）", t.message!!.contains("[snapshot-delete-residue]"))
    assertTrue("诊断行必须点名残留", t.diagnosticLine().contains("usr"))
    assertTrue(SnapshotFsException(CODE_MOVE_BLOCKED, message = "目标非空").message!!.contains("snapshot-move-blocked"))
    assertTrue(SnapshotFsException(CODE_FOREIGN_OWNER, message = "非应用属主").message!!.contains("snapshot-foreign-owner"))
  }

  private fun writeRuntime(root: File, nodeMarker: String, profileMarker: String) {
    File(root, "usr/bin").mkdirs()
    File(root, "usr/bin/node").writeText(nodeMarker)
    File(root, "home/.dsh/profiles/web").mkdirs()
    File(root, "home/.dsh/profiles/web/cordis.yml").writeText(profileMarker)
  }
}
