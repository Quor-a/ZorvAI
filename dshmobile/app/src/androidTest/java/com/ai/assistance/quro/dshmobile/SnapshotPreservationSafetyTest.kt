package com.ai.assistance.quro.dshmobile

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Disposable Android filesystem checks for the real pre-overwrite preservation transaction. */
class SnapshotPreservationSafetyTest {
  @Test fun failedAndCorruptCopiesKeepOriginalWhileVerifiedCopiesSurviveCommit() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    for (mode in listOf("failure", "corrupt", "success")) {
      val root = Files.createTempDirectory(context.cacheDir.toPath(), "preservation-").toFile()
      try {
        val live = File(root, "live")
        val stage = SnapshotTransaction.stageRoot(root)
        for ((dir, node) in listOf(live to "old", stage to "new")) {
          File(dir, "usr/bin/node").apply { parentFile!!.mkdirs(); writeText(node) }
          File(dir, "home/.dsh/profiles/web/cordis.yml").apply { parentFile!!.mkdirs(); writeText("[]\n") }
        }
        val relative = "home/.dsh/profiles/web/node_modules/p/user.bin"
        val original = byteArrayOf(0, 1, 2, 127, -1, 10)
        val target = File(live, relative).apply { parentFile!!.mkdirs(); writeBytes(original) }
        File(stage, relative).apply { parentFile!!.mkdirs(); writeText("factory") }
        if (mode == "success") assertTrue("fixture read-only mode must be applied", target.setReadOnly())
        val stagedProfiles = File(stage, "home/.dsh/profiles")
        Files.createSymbolicLink(File(stagedProfiles, "cycle").toPath(), stagedProfiles.toPath())
        assertEquals("reservation skips staged symlink cycle", original.size.toLong(),
          SnapshotTransaction.preservedBytes(stagedProfiles, File(live, "home/.dsh/profiles")))
        val failure = runCatching {
          SnapshotTransaction.swap(root, stage, File(live, "usr"), File(live, "home"), emptySet(), "fixture", 1L,
            preserveCopy = { from, to -> when (mode) {
              "failure" -> throw IOException("fixture copy failure")
              "corrupt" -> to.writeBytes(ByteArray(from.length().toInt()))
              else -> Files.copy(from.toPath(), to.toPath())
            }; Unit },
          )
        }.exceptionOrNull()
        if (mode != "success") {
          assertTrue("$mode must abort", failure is SnapshotFsException)
          assertEquals(CODE_PRESERVE_FAILED, (failure as SnapshotFsException).code)
          assertTrue("$mode must preserve original bytes", target.readBytes().contentEquals(original))
        } else {
          if (failure != null) throw failure
          SnapshotTransaction.finish(root)
          assertEquals("factory", target.readText())
          val copies = target.parentFile!!.listFiles().orEmpty().filter { it.name.startsWith(".pre-user.bin-") }
          assertTrue("verified old bytes must survive transaction commit", copies.any { it.readBytes().contentEquals(original) })
          assertTrue("preserved read-only mode must survive", copies.any { !it.canWrite() })
        }
      } finally { SnapshotFs.deletePath(root) }
    }
  }
}
