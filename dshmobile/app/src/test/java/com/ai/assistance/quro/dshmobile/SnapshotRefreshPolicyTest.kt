package com.ai.assistance.quro.dshmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D2（0.14.1）快照刷新失败账本与降级判据的行为回归。
 *
 * 缺陷（详档 §1.3）：冷启动 `if (!snapshotFresh()) refreshSnapshot(...)`，失败即停在引导页且
 * **没有任何跨进程记账**——`EngineStartFlow.engineRetryCount` 是 #118 的**引擎启动**重试计数，
 * 进程一死即清零，管不到快照刷新。于是同一份快照上的失败形态是「每次冷启动都重跑一次注定失败的
 * 全量刷新、每次同样失败」，用户被无限拦在引导页。
 *
 * 判据必须落在 **live 树完整性**上而不是失败次数：refresh 失败的常见真因是快照缺失/解压不全，
 * 那时「放行」等于拉起一棵不完整的运行时（以「引擎能起但插件缺」的形态静默劣化），比拦住更坏。
 *
 * 纯 JVM（无 Robolectric）。
 */
class SnapshotRefreshPolicyTest {

  private val fp = "snap-abc123"

  // ── 账本编解码 ────────────────────────────────────────────────

  @Test
  fun theLedgerRoundTripsAndIsHumanReadable() {
    val encoded = SnapshotRefreshPolicy.encode(fp, 3)
    assertEquals("snap-abc123\t3", encoded)
    assertEquals(fp to 3, SnapshotRefreshPolicy.parse(encoded))
  }

  /** 畸形账本一律 null：**不得**把「读不懂」当成「已失败多次」而误放行。 */
  @Test
  fun aMalformedLedgerIsNotTreatedAsFailures() {
    for (bad in listOf(null, "", "   ", "no-tab", "\t3", "fp\t", "fp\tx", "fp\t-1", "\t")) {
      assertNull("畸形账本必须判为不可用：$bad", SnapshotRefreshPolicy.parse(bad))
      assertFalse(
        "账本不可用时绝不能降级：$bad",
        SnapshotRefreshPolicy.shouldDegrade(bad, fp, liveComplete = true),
      )
    }
  }

  // ── 计数累积与清零 ────────────────────────────────────────────

  @Test
  fun failuresAccumulateOnTheSameFingerprint() {
    var raw = SnapshotRefreshPolicy.afterFailure(null, fp)
    assertEquals(fp to 1, SnapshotRefreshPolicy.parse(raw))
    raw = SnapshotRefreshPolicy.afterFailure(raw, fp)
    raw = SnapshotRefreshPolicy.afterFailure(raw, fp)
    assertEquals("同一份快照上必须累积", fp to 3, SnapshotRefreshPolicy.parse(raw))
  }

  /** 换快照（App 升级）即视为新问题：计数必须清零重来，否则一次失败会跨版本继承。 */
  @Test
  fun aNewFingerprintResetsTheCount() {
    var raw = SnapshotRefreshPolicy.afterFailure(null, fp)
    raw = SnapshotRefreshPolicy.afterFailure(raw, fp)
    raw = SnapshotRefreshPolicy.afterFailure(raw, "snap-new-999")
    assertEquals("换快照后必须从 1 重新计", "snap-new-999" to 1, SnapshotRefreshPolicy.parse(raw))
  }

  // ── 降级判据（三个条件缺一不可）────────────────────────────────

  @Test
  fun degradationRequiresTheThresholdOnTheSameFingerprint() {
    val two = SnapshotRefreshPolicy.afterFailure(SnapshotRefreshPolicy.afterFailure(null, fp), fp)
    assertFalse("两次还不够", SnapshotRefreshPolicy.shouldDegrade(two, fp, liveComplete = true))
    val three = SnapshotRefreshPolicy.afterFailure(two, fp)
    assertTrue("达阈且 live 完整 → 降级", SnapshotRefreshPolicy.shouldDegrade(three, fp, liveComplete = true))
    assertFalse(
      "账本指纹与当前内嵌快照不一致（换了快照）→ 不得降级，必须真刷一次",
      SnapshotRefreshPolicy.shouldDegrade(three, "snap-other", liveComplete = true),
    )
  }

  /** 核心反证：**live 不完整时无论失败多少次都不许降级**（否则会拉起缺件的运行时）。 */
  @Test
  fun anIncompleteLiveTreeNeverDegradesNoMatterHowManyFailures() {
    var raw: String? = null
    repeat(10) { raw = SnapshotRefreshPolicy.afterFailure(raw, fp) }
    assertFalse(
      "live 不完整时放行 = 拉起一棵缺件的运行时（幽灵缺陷形态），比拦在引导页更坏",
      SnapshotRefreshPolicy.shouldDegrade(raw, fp, liveComplete = false),
    )
    assertTrue("同一份账本在 live 完整时才降级（证明上面那条不是因为账本本身没达阈）",
      SnapshotRefreshPolicy.shouldDegrade(raw, fp, liveComplete = true))
  }

  @Test
  fun anEmptyFingerprintNeverDegrades() {
    var raw: String? = null
    repeat(5) { raw = SnapshotRefreshPolicy.afterFailure(raw, fp) }
    assertFalse("取不到内嵌指纹（旧构建）时无判据可用，不得降级", SnapshotRefreshPolicy.shouldDegrade(raw, "  ", liveComplete = true))
  }

  @Test
  fun theThresholdIsConfigurableButNeverBelowOne() {
    assertTrue(SnapshotRefreshPolicy.shouldDegrade(SnapshotRefreshPolicy.encode(fp, 1), fp, true, threshold = 1))
    assertFalse(
      "阈值为 0/负值时按 1 处理（不得出现「0 次失败就降级」）",
      SnapshotRefreshPolicy.shouldDegrade(SnapshotRefreshPolicy.encode(fp, 0), fp, true, threshold = 0),
    )
  }
}
