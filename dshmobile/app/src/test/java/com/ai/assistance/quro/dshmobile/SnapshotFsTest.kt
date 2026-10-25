package com.ai.assistance.quro.dshmobile

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * D1（0.14.1）`SnapshotFs.deletePath` 容错契约的行为回归。
 *
 * 背景（issue #240 的剩余缺口）：`deletePath` 同时是快照刷新的第一步（`EngineManager` 清理残留）
 * 与回滚路径的公共原语（`SnapshotTransaction.rollbackEntry`），而它的容错契约此前**只兜 `Exception`**。
 * 一旦 `Error` 从这里逃逸：既绕过本方法的逐项容错，又绕过 `EngineManager` 紧随其后的「残渣改名挪开」
 * 兜底，一路打穿到刷新失败的 `catch (t: Throwable)` 去走回滚——而回滚走同一个原语。
 * 现场形状即「回滚也失败 → marker 永久留存 → 每次冷启动重试同样失败」。
 *
 * 反证方式：把 [isTolerableDeletionFailure] 改回「只容忍 `Exception`」，下面
 * `aLinkageErrorWhileListingIsReportedInsteadOfEscaping` 与
 * `siblingsAreStillDeletedWhenOneEntryFailsWithALinkageError` 必红——即本缺陷的形状。
 */
class SnapshotFsTest {

  private fun tempDir(): File = Files.createTempDirectory("snapshot-fs-test").toFile()

  // ── 容错边界的判定本身（纯函数，逐条钉住）─────────────────────────────

  @Test
  fun linkageErrorsAreTolerableSoTheChainSurvivesAMissingApi() {
    // issue #240 的真实形态：Stream.toList() 在 API < 34 上抛的就是这个。
    assertTrue(isTolerableDeletionFailure(NoSuchMethodError("Stream.toList()")))
    assertTrue(isTolerableDeletionFailure(NoClassDefFoundError("java/nio/file/Path")))
    assertTrue(isTolerableDeletionFailure(IncompatibleClassChangeError("shape changed")))
  }

  @Test
  fun plainExceptionsStayTolerable() {
    // 既有容错面不得回归：元数据损坏的目录 / EACCES / 并发删除都是 Exception。
    assertTrue(isTolerableDeletionFailure(java.nio.file.DirectoryNotEmptyException("busy")))
    assertTrue(isTolerableDeletionFailure(java.nio.file.AccessDeniedException("denied")))
    assertTrue(isTolerableDeletionFailure(IllegalStateException("boom")))
  }

  @Test
  fun virtualMachineAndOtherErrorsMustPropagate() {
    // 刻意**不**写成裸 catch (Throwable)：把 OOM 降级成「继续删」只会放大失败。
    assertFalse(isTolerableDeletionFailure(OutOfMemoryError("heap")))
    assertFalse(isTolerableDeletionFailure(StackOverflowError()))
    assertFalse(isTolerableDeletionFailure(InternalError("jvm")))
    assertFalse("非 LinkageError 的 Error 表示 JVM 层已不可信，不得降级", isTolerableDeletionFailure(ThreadDeath()))
    assertFalse(isTolerableDeletionFailure(AssertionError("invariant")))
  }

  // ── 行为面：列目录时抛 Error 不得逃逸 ──────────────────────────────

  @Test
  fun aLinkageErrorWhileListingIsReportedInsteadOfEscaping() {
    val root = tempDir()
    try {
      File(root, "home/.dsh").mkdirs()
      val boom = NoSuchMethodError("java.util.stream.Stream.toList()")
      val reported = mutableListOf<Pair<String, Throwable>>()
      try {
        SnapshotFs.deletePath(root, onFailure = { f, t -> reported += f.name to t }, listChildren = { throw boom })
      } catch (t: Throwable) {
        fail("deletePath 不得让 Error 逃逸（issue #240 的形态）：" + t.javaClass.name + ": " + t.message)
      }
      assertEquals("必须恰好上报一次", 1, reported.size)
      assertEquals("上报的必须是被列目录的那一层", root.name, reported[0].first)
      assertSame("必须把原始 Error 交出去（供诊断），不得包装成别的类型", boom, reported[0].second)
      assertTrue("列目录失败时该层不得被删除（宁可留残渣也不假装删干净）", SnapshotFs.exists(root))
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun siblingsAreStillDeletedWhenOneEntryFailsWithALinkageError() {
    // 逐项容错的实质：一个坏条目不得让整棵树停下。这里让 `bad` 子目录「列不动」，
    // 断言它的兄弟条目仍然被删净（这正是「能删的必须删掉」的判据）。
    val root = tempDir()
    try {
      File(root, "first.txt").writeText("1")
      File(root, "bad").mkdirs()
      File(root, "second.txt").writeText("2")
      val boom = NoSuchMethodError("列不动这一层")
      val reported = mutableListOf<String>()
      SnapshotFs.deletePath(
        root,
        onFailure = { f, _ -> reported += f.name },
        // 只让 `bad` 这一层列不动，其余走真实实现。
        listChildren = { p: Path ->
          if (p.fileName.toString() == "bad") throw boom
          Files.newDirectoryStream(p).use { it.toList() }
        },
      )
      assertFalse("兄弟条目必须仍被删净", SnapshotFs.exists(File(root, "first.txt")))
      assertFalse("兄弟条目必须仍被删净", SnapshotFs.exists(File(root, "second.txt")))
      assertTrue("坏条目自己留下来（是残渣，不是失败链）", SnapshotFs.exists(File(root, "bad")))
      assertTrue("必须点名是 `bad` 这一层失败：$reported", reported.contains("bad"))
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun aVirtualMachineErrorStillEscapesTheDeletionLoop() {
    // 反面的反面：容忍范围被放大到「一律吞掉」同样是缺陷（会把 OOM 说成「清理成功」）。
    val root = tempDir()
    try {
      File(root, "x").writeText("x")
      var reported = 0
      try {
        SnapshotFs.deletePath(root, onFailure = { _, _ -> reported += 1 }, listChildren = { throw OutOfMemoryError("heap") })
        fail("VirtualMachineError 必须原样抛出，不得被降级成一次「已上报的失败」")
      } catch (_: OutOfMemoryError) {
        // 期望路径
      }
      assertEquals("重抛的 Error 不得同时被当成「已上报」", 0, reported)
    } finally {
      root.deleteRecursively()
    }
  }

  // ── 生产默认路径不得回归（真实文件系统上的正常删除）─────────────────────

  @Test
  fun theDefaultListingStillDeletesAWholeTreeSilently() {
    val root = tempDir()
    try {
      File(root, "usr/bin").mkdirs()
      File(root, "usr/bin/node").writeText("node")
      File(root, "home/.dsh").mkdirs()
      File(root, "home/.dsh/settings.yaml").writeText("user: true\n")
      val reported = mutableListOf<String>()
      // 不传 listChildren：走生产默认实现（newDirectoryStream + stdlib toList）。
      SnapshotFs.deletePath(root) { f, _ -> reported += f.name }
      assertFalse("整棵树必须被删除", SnapshotFs.exists(root))
      assertTrue("健康树不得上报任何失败：$reported", reported.isEmpty())
    } finally {
      root.deleteRecursively()
    }
  }
}
