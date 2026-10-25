package com.ai.assistance.quro.dshmobile

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class SnapshotUserDataTest {

  @Test
  fun neverRollsBackDataThatTheLiveTreeAlreadyHas() {
    val root = Files.createTempDirectory("snapshot-user-data-additive-test").toFile()
    try {
      val backup = File(root, "backup").apply { mkdirs() }
      File(backup, "sessions").mkdirs()
      File(backup, "sessions/a.jsonl").writeText("0123456789")
      File(backup, "sessions/b.jsonl").writeText("recovered")
      val destination = File(root, "destination/.dsh").apply { mkdirs() }
      File(destination, "sessions").mkdirs()
      File(destination, "sessions/a.jsonl").writeText("01234567890123456789")

      val result = SnapshotUserData.restoreLegacyBackup(backup, destination) { }

      assertEquals(1, result.copiedEntries)
      assertEquals(1, result.skippedExisting)
      assertEquals("01234567890123456789", File(destination, "sessions/a.jsonl").readText())
      assertEquals("recovered", File(destination, "sessions/b.jsonl").readText())
    } finally {
      SnapshotFs.deletePath(root)
    }
  }

  @Test
  fun completesATruncatedFileFromTheBackup() {
    val root = Files.createTempDirectory("snapshot-user-data-truncated-test").toFile()
    try {
      val backup = File(root, "backup/sessions").apply { mkdirs() }
      File(backup, "a.jsonl").writeText("01234567890123456789")
      val destination = File(root, "destination/.dsh/sessions").apply { mkdirs() }
      File(destination, "a.jsonl").writeText("01234")

      val result = SnapshotUserData.restoreLegacyBackup(File(root, "backup"), File(root, "destination/.dsh")) { }

      assertEquals(1, result.copiedEntries)
      assertEquals("01234567890123456789", File(destination, "a.jsonl").readText())
    } finally {
      SnapshotFs.deletePath(root)
    }
  }

  @Test
  fun restoresSingletonSettingsWholesale() {
    val root = Files.createTempDirectory("snapshot-user-data-singleton-test").toFile()
    try {
      val backup = File(root, "backup").apply { mkdirs() }
      File(backup, "settings.yaml").writeText("user: true\n")
      val destination = File(root, "destination/.dsh").apply { mkdirs() }
      File(destination, "settings.yaml").writeText("factory: true\n")

      SnapshotUserData.restoreLegacyBackup(backup, destination) { }

      assertEquals("user: true\n", File(destination, "settings.yaml").readText())
    } finally {
      SnapshotFs.deletePath(root)
    }
  }

  @Test
  fun recreatesMissingLinksAndSkipsBrokenLinksWithoutMutatingTheLiveTree() {
    val root = Files.createTempDirectory("snapshot-user-data-links-test").toFile()
    try {
      val backup = File(root, "backup/sessions").apply { mkdirs() }
      val payload = File(backup, "payload.txt").apply { writeText("saved") }
      val validLink = File(backup, "payload-link")
      val brokenLink = File(backup, "missing-link")
      try {
        Files.createSymbolicLink(validLink.toPath(), java.nio.file.Paths.get(payload.name))
        Files.createSymbolicLink(brokenLink.toPath(), java.nio.file.Paths.get("missing"))
      } catch (_: Throwable) {
        assumeTrue("The host must permit symbolic-link creation for this regression test", false)
      }
      val destination = File(root, "destination/.dsh/sessions").apply { mkdirs() }
      val brokenLinks = mutableListOf<File>()

      val result = SnapshotUserData.restoreLegacyBackup(File(root, "backup"), File(root, "destination/.dsh")) {
        brokenLinks += it
      }

      assertEquals(1, result.skippedBrokenLinks)
      assertEquals(1, brokenLinks.size)
      assertEquals("missing-link", brokenLinks.single().name)
      assertTrue(Files.isSymbolicLink(File(destination, "payload-link").toPath()))
      assertFalse(Files.exists(File(destination, "missing-link").toPath(), NOFOLLOW_LINKS))
      // The backup tree is read-only input: its broken link must still be there.
      assertTrue(Files.exists(brokenLink.toPath(), NOFOLLOW_LINKS))
    } finally {
      SnapshotFs.deletePath(root)
    }
  }

  @Test
  fun doesNothingWhenThereIsNoLegacyBackup() {
    val root = Files.createTempDirectory("snapshot-user-data-absent-test").toFile()
    try {
      val destination = File(root, "destination/.dsh").apply { mkdirs() }
      File(destination, "settings.yaml").writeText("user: true\n")

      val result = SnapshotUserData.restoreLegacyBackup(File(root, "missing"), destination) { }

      assertEquals(0, result.copiedEntries)
      assertEquals("user: true\n", File(destination, "settings.yaml").readText())
    } finally {
      SnapshotFs.deletePath(root)
    }
  }
}
