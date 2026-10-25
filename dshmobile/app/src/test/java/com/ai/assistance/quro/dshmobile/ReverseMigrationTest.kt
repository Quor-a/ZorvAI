package com.ai.assistance.quro.dshmobile

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReverseMigrationTest {
  @Test
  fun partialCopyFailureCanRetryFromIntactPublicSource() {
    val root = Files.createTempDirectory("reverse-migration-retry").toFile()
    try {
      val public = File(root, "public/sessions").apply { mkdirs() }
      val private = File(root, "private/sessions")
      File(public, "one.jsonl").writeText("one")
      File(public, "nested").mkdirs()
      File(public, "nested/two.jsonl").writeText("two")
      val copyFailure: (File, File) -> Unit = { _, stage ->
        File(stage, "one.jsonl").writeText("partial")
        throw IOException("injected interrupted copy")
      }

      assertThrows(IOException::class.java) {
        ReverseMigration.migrateDir(private, public, copyTree = copyFailure)
      }
      assertFalse("failed attempt must not publish partial files as live", private.exists())
      assertEquals("one", File(public, "one.jsonl").readText())

      ReverseMigration.migrateDir(private, public)

      assertEquals(mapOf("nested/two.jsonl" to "two", "one.jsonl" to "one"), contents(private))
      assertFalse("public source is retired only after verified activation", public.exists())
      assertFalse(File(root, "private/sessions.reverse-migration-stage").exists())
      assertFalse(File(root, "private/sessions.reverse-migration-owner").exists())
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun nonEmptyPrivateConflictIsPreservedAndPublicSourceBackedUp() {
    val root = Files.createTempDirectory("reverse-migration-conflict").toFile()
    try {
      val public = File(root, "public/sessions").apply { mkdirs() }
      val private = File(root, "private/sessions").apply { mkdirs() }
      File(public, "session.jsonl").writeText("public-copy")
      File(private, "session.jsonl").writeText("private-authoritative")

      ReverseMigration.migrateDir(private, public)

      assertEquals("private-authoritative", File(private, "session.jsonl").readText())
      val backup = File(root, "public/sessions.public-backup")
      assertEquals("public-copy", File(backup, "session.jsonl").readText())
      assertFalse(public.exists())
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun nonDirectoryPrivateCollisionIsPreservedAndPublicSourceRemains() {
    val root = Files.createTempDirectory("reverse-migration-file-conflict").toFile()
    try {
      val public = File(root, "public/sessions").apply { mkdirs() }
      File(public, "session.jsonl").writeText("public-source")
      val private = File(root, "private/sessions").apply { parentFile.mkdirs(); writeText("unknown-private-file") }

      assertThrows(IOException::class.java) { ReverseMigration.migrateDir(private, public) }

      assertEquals("unknown-private-file", private.readText())
      assertEquals("public-source", File(public, "session.jsonl").readText())
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun unreadablePublicDirectoryCannotBeAcceptedAsAnEmptyCopy() {
    val root = Files.createTempDirectory("reverse-migration-unreadable-source").toFile()
    try {
      val public = File(root, "public/sessions").apply { mkdirs() }
      File(public, "session.jsonl").writeText("must-survive")
      val private = File(root, "private/sessions")

      assertThrows(IOException::class.java) {
        ReverseMigration.migrateDir(private, public, listFiles = { source ->
          if (source == public) null else source.listFiles()
        })
      }

      assertFalse(private.exists())
      assertEquals("must-survive", File(public, "session.jsonl").readText())
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun interruptedOwnerMarkerPreparationDoesNotBlockRetryOrDeleteUnknownTemp() {
    val root = Files.createTempDirectory("reverse-migration-owner-marker").toFile()
    try {
      val public = File(root, "public/sessions").apply { mkdirs() }
      File(public, "session.jsonl").writeText("source")
      val private = File(root, "private/sessions")
      val orphanTemp = File(root, "private/sessions.reverse-migration-owner.crashed.tmp")

      assertThrows(IOException::class.java) {
        ReverseMigration.migrateDir(private, public, writeOwner = { owner ->
          orphanTemp.writeText("partial marker")
          throw IOException("injected interruption during marker preparation")
        })
      }
      assertFalse("no incomplete marker may be published", File(root, "private/sessions.reverse-migration-owner").exists())

      ReverseMigration.migrateDir(private, public)

      assertEquals("source", File(private, "session.jsonl").readText())
      assertFalse(public.exists())
      assertEquals("partial marker", orphanTemp.readText())
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun retryAfterActivationCompletesPublicCleanupUsingOwnerMarker() {
    val root = Files.createTempDirectory("reverse-migration-post-activation").toFile()
    try {
      val public = File(root, "public/sessions").apply { mkdirs() }
      val private = File(root, "private/sessions")
      File(public, "session.jsonl").writeText("complete")
      var failCleanupOnce = true

      assertThrows(IOException::class.java) {
        ReverseMigration.migrateDir(
          private,
          public,
          deleteTree = { target ->
            if (target == public && failCleanupOnce) {
              failCleanupOnce = false
              throw IOException("injected interruption after activation")
            }
            SnapshotFs.deletePath(target)
          },
        )
      }
      assertEquals("complete", File(private, "session.jsonl").readText())
      assertTrue(public.exists())
      assertTrue(File(root, "private/sessions.reverse-migration-owner").exists())

      ReverseMigration.migrateDir(private, public)

      assertEquals("complete", File(private, "session.jsonl").readText())
      assertFalse(public.exists())
      assertFalse(File(root, "private/sessions.reverse-migration-owner").exists())
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun unownedSiblingStageIsNeverDeletedOrAdopted() {
    val root = Files.createTempDirectory("reverse-migration-unowned-stage").toFile()
    try {
      val public = File(root, "public/sessions").apply { mkdirs() }
      File(public, "session.jsonl").writeText("source")
      val private = File(root, "private/sessions")
      val stage = File(root, "private/sessions.reverse-migration-stage").apply { mkdirs() }
      File(stage, "unknown.txt").writeText("unowned")

      assertThrows(IOException::class.java) { ReverseMigration.migrateDir(private, public) }

      assertEquals("unowned", File(stage, "unknown.txt").readText())
      assertEquals("source", File(public, "session.jsonl").readText())
      assertFalse(private.exists())
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun relativeMigrationPathsFailBeforeWritingIntoTheProcessWorkingDirectory() {
    val root = Files.createTempDirectory("reverse-migration-relative-path").toFile()
    try {
      val public = File(root, "public/sessions").apply { mkdirs() }
      File(public, "session.jsonl").writeText("source")
      val relativeStem = root.name + "-relative"
      val bareRelative = File(relativeStem + "-leaf")
      val nestedRelative = File(relativeStem + "-parent/sessions")

      assertThrows(IOException::class.java) { ReverseMigration.migrateDir(bareRelative, public) }
      assertThrows(IOException::class.java) { ReverseMigration.migrateDir(nestedRelative, public) }

      assertFalse(bareRelative.exists())
      assertFalse(File(relativeStem + "-leaf.reverse-migration-stage").exists())
      assertFalse(File(relativeStem + "-leaf.reverse-migration-owner").exists())
      assertFalse(nestedRelative.exists())
      assertFalse(File(relativeStem + "-parent/sessions.reverse-migration-stage").exists())
      assertFalse(File(relativeStem + "-parent/sessions.reverse-migration-owner").exists())
      assertEquals("source", File(public, "session.jsonl").readText())
    } finally {
      root.deleteRecursively()
    }
  }

  private fun contents(root: File): Map<String, String> = root.walkTopDown()
    .filter { it.isFile }
    .associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
}
