package com.ai.assistance.quro.dshmobile

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotRefreshStageTest {
  @Test
  fun badArchiveAfterPartialWriteDiscardsStageAndRetainsLiveRuntime() {
    val root = Files.createTempDirectory("snapshot-stage-failure").toFile()
    try {
      val live = File(root, "live/usr/bin/node").apply { parentFile.mkdirs(); writeText("previous-runtime") }
      val stage = File(root, ".snapshot-stage").apply { mkdirs() }
      var validationCalled = false

      val ready = SnapshotRefreshStage.extractAndValidate(
        stage,
        extract = { target ->
          File(target, "usr/bin/node").apply { parentFile.mkdirs(); writeText("partial-new-runtime") }
          false // archive read/copy failed after publishing some files into the isolated stage
        },
        validate = { validationCalled = true; true },
      )

      assertFalse(ready)
      assertFalse("failed extraction never reaches staged-runtime validation", validationCalled)
      assertFalse("owned stage is removed after extraction failure", stage.exists())
      assertEquals("previous-runtime", live.readText())
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun secondRepairValidationFailureAlsoDiscardsStageBeforeStopping() {
    val root = Files.createTempDirectory("snapshot-stage-repair-failure").toFile()
    try {
      val live = File(root, "live/usr/bin/node").apply { parentFile.mkdirs(); writeText("known-good-runtime") }
      val stage = File(root, ".snapshot-stage").apply { mkdirs() }
      var extractionAttempts = 0
      var validationAttempts = 0

      repeat(2) {
        val ready = SnapshotRefreshStage.extractAndValidate(
          stage,
          extract = { target ->
            extractionAttempts++
            File(target, "usr/bin/node").apply { parentFile.mkdirs(); writeText("repair-$extractionAttempts") }
            true
          },
          validate = { validationAttempts++; false },
        )
        assertFalse("incomplete repair is not eligible for a live swap", ready)
        assertFalse("failed repair stage is discarded before returning", stage.exists())
      }

      assertEquals(2, extractionAttempts)
      assertEquals(2, validationAttempts)
      assertEquals("known-good-runtime", live.readText())
      assertTrue("next repair begins with an empty stage namespace", stage.mkdirs())
    } finally {
      root.deleteRecursively()
    }
  }
}
