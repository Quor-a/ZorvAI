package com.ai.assistance.quro.dshmobile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AtomicFileReplaceTest {

  private fun tempDir(): File = java.nio.file.Files.createTempDirectory("atomic-replace-test").toFile()

  @Test
  fun replacementKeepsTheOldTargetUntilTheNewFileIsReady() {
    val root = tempDir()
    try {
      val target = File(root, "existing.apk")
      target.writeText("old")
      val tmp = File(root, "existing.apk.tmp")
      tmp.writeText("new")
      assertTrue(AtomicFileReplace.publish(tmp, target))
      assertEquals("new", target.readText())
      assertFalse("成功发布后临时文件已被消费", tmp.exists())
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun failedPublishLeavesTheOldTargetAndTheCompletedTemp() {
    val root = tempDir()
    try {
      val target = File(root, "existing.zip")
      target.writeText("old")
      val tmp = File(root, "missing.zip.tmp")
      assertFalse(AtomicFileReplace.publish(tmp, target))
      assertEquals("old", target.readText())
      assertFalse("缺失的临时文件不能成为目标", tmp.exists())
    } finally {
      root.deleteRecursively()
    }
  }
}
