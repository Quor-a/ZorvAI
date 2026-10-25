package com.ai.assistance.quro.dshmobile

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real Android regression over the retirement transformer and failed profile publication. */
class FactoryRetirementSafetyTest {
  @Test fun unrelatedUnnamedEntriesSurviveAndFailedPublicationPreservesOldProfile() {
    val input = "- id: ordinary-row\n  disabled: false\n- id: user-row\n  config:\n    value: keep-exactly\n- insert:\n    - id: dsh-undo-savepoint\n      name: dsh-undo-savepoint\n"
    // ZorvAI 裁定（2026-10）：RETIRED_FACTORY_ENTRIES 已清空 ⇒ undo 不再被启动自愈摘除。
    val result = FactoryProfilePatch.repairRetiredEntries(input, emptySet(), FactoryProfilePatch.RETIRED_FACTORY_ENTRIES)
    assertTrue("ordinary unnamed row without config must survive retirement", result.text.contains("- id: ordinary-row\n  disabled: false\n"))
    assertTrue("ordinary configured row must survive retirement", result.text.contains("- id: user-row\n  config:\n    value: keep-exactly\n"))
    assertTrue(
      "ZorvAI remount decision: empty retirement table keeps the factory undo",
      result.text.contains("- id: dsh-undo-savepoint\n      name: dsh-undo-savepoint\n"),
    )
    // 机制回归：显式传入退役表时，无 config 的 undo 工厂条目仍被摘除（未来真退役的兜底路径）。
    val retired = FactoryProfilePatch.repairRetiredEntries(
      input,
      emptySet(),
      mapOf("dsh-undo-savepoint" to "dsh-undo-savepoint"),
    )
    assertFalse("explicit retirement map must still drop the unconfigured factory undo", retired.text.contains("- id: dsh-undo-savepoint"))
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val root = File(context.cacheDir, "factory-retirement-" + System.nanoTime()).apply { mkdirs() }
    val target = File(root, "cordis.patch.yml").apply { writeText(input) }
    val oldBytes = target.readBytes()
    try {
      try {
        SnapshotTransaction.writeTextAtomic(target, retired.text, "retirement test", publish = { _, _ -> false }) { }
        throw AssertionError("publication failure must propagate")
      } catch (_: SnapshotFsException) {
        assertTrue("failed retirement publication must keep exact old bytes", target.readBytes().contentEquals(oldBytes))
      }
      SnapshotTransaction.writeTextAtomic(target, retired.text, "retirement test") { }
      assertEquals(retired.text, target.readText())
    } finally {
      SnapshotFs.deletePath(root)
    }
  }
}
