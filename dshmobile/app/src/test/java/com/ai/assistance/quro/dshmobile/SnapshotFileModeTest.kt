package com.ai.assistance.quro.dshmobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotFileModeTest {

  @Test
  fun identifiesOnlyElfAndShebangPayloadsAsDirectExecutables() {
    assertTrue(SnapshotFileMode.isDirectlyExecutable(byteArrayOf(0x7f.toByte(), 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()), 4))
    assertTrue(SnapshotFileMode.isDirectlyExecutable(byteArrayOf('#'.code.toByte(), '!'.code.toByte(), '/'.code.toByte(), 'b'.code.toByte()), 4))
    assertFalse(SnapshotFileMode.isDirectlyExecutable("{\"a\"".toByteArray(), 4))
    assertFalse(SnapshotFileMode.isDirectlyExecutable("cons".toByteArray(), 4))
    assertFalse(SnapshotFileMode.isDirectlyExecutable(byteArrayOf('#'.code.toByte()), 1))
  }
}
