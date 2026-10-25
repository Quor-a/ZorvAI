package com.ai.assistance.quro.dshmobile

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 真实 Android cache 夹具：回收入口只删除事务真实写点，未知用户目录逐字节保留。 */
class SnapshotResidueOwnershipTest {
  @Test
  fun cleanupPreservesUnknownUserContentAndReclaimsOnlyOwnedResidue() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val root = Files.createTempDirectory(context.cacheDir.toPath(), "snapshot-residue-").toFile()
    try {
      val old = 1_790_100_675_609L
      val ownedUsr = File(root, "usr.failed-$old").apply { mkdirs() }
      File(ownedUsr, "bin/node").apply { parentFile?.mkdirs() }.writeText("factory residue")
      val ownedProfiles = File(root, "home/.dsh/profiles.failed-$old").apply { mkdirs() }
      File(ownedProfiles, "web/package.json").apply { parentFile?.mkdirs() }.writeText("factory residue")
      val rootProfiles = File(root, "profiles.failed-$old").apply { mkdirs() }
      File(rootProfiles, "web/package.json").apply { parentFile?.mkdirs() }.writeText("factory residue")
      val ownedOrphan = File(root, SnapshotTransaction.STAGE_ORPHAN_PREFIX + old).apply { mkdirs() }

      val userBytes = byteArrayOf(0, 1, 2, 127, -1, 10)
      val unknown = File(root, "report.failed-123").apply { mkdirs() }
      File(unknown, "report.bin").writeBytes(userBytes)
      val unknownProfile = File(root, "home/.dsh/report.failed-123").apply { mkdirs() }
      File(unknownProfile, "notes.txt").writeText("user-owned")
      val malformedUsr = File(root, "usr.failed-1790100675609.extra").apply { mkdirs() }
      val malformedOrphan = File(root, SnapshotTransaction.STAGE_ORPHAN_PREFIX + "legacy").apply { mkdirs() }

      assertTrue(
        "已知写点必须进入现有回收入口",
        SnapshotTransaction.residueDirs(root, old + 31L * 60L * 1000L).containsAll(
          listOf(ownedUsr, ownedProfiles, rootProfiles, ownedOrphan),
        ),
      )
      val cleared = SnapshotTransaction.clearFailedResidue(root)

      assertTrue("unknown user entry must survive", unknown.exists())
      assertTrue("unknown user bytes must be unchanged", userBytes.contentEquals(File(unknown, "report.bin").readBytes()))
      assertTrue("unknown .dsh entry must survive", unknownProfile.exists())
      assertTrue("malformed usr suffix must survive", malformedUsr.exists())
      assertTrue("malformed stage suffix must survive", malformedOrphan.exists())
      assertTrue("clearFailedResidue must not reap the age-gated stage orphan", ownedOrphan.exists())
      assertEquals("clearFailedResidue 回收三个 failed 写点", 3, cleared.size)
      assertFalse("owned usr residue must be reclaimed", ownedUsr.exists())
      assertFalse("owned profile residue must be reclaimed", ownedProfiles.exists())
      assertFalse("root profile residue must be reclaimed", rootProfiles.exists())
      val reclaimed = SnapshotTransaction.reclaimResidue(root, old + 31L * 60L * 1000L)
      assertFalse("reclaimResidue 的年龄窗口仍须回收 stage orphan", ownedOrphan.exists())
      assertEquals("年龄窗口只回收 stage orphan", 1, reclaimed.size)
    } finally {
      SnapshotFs.deletePath(root)
    }
  }
}
