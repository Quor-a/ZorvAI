package com.ai.assistance.quro.dshmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D3（0.14.6）安全模式重启判定的行为回归。
 *
 * 缺陷真因（源码链）：GuidePageRenderer.enterSafeMode() 只调 activity.startEngineFlow()，而那条路
 * 先探活、探到旧引擎仍在就 early-return 并 showWeb()——**从不经过** EngineManager.startEngine，
 * 于是「快照新鲜度」与「live 树完整性」两道既有闸门一次都不执行。后果是点「安全模式启动」后仍是
 * **旧进程服务旧 manifest**（含刚被摘掉的第三方插件条目），页面再次报同样的失败并连环报错，
 * 安全模式这一改根本没生效（与 0.14.4 的 DEVICE-FINDING-3 同族）。
 *
 * 本判定回答「这一步该不该去停旧进程并重新拉起」。两条**相反**的用例是本类的核心价值：
 *  - live 完整 + 快照未就绪 => **允许**放行（issue #240 的有意降级窗，必须保留）；
 *  - live 不完整 + 快照未就绪 => **绝不**放行（负例必须红）。
 *
 * 纯 JVM（无 Robolectric，不触碰任何 Android 类）。
 */
class SafeModeRestartDecisionTest {

  // ── 正例：live 完整 + 快照就绪 => 正常重启 ──────────────────────────────

  @Test
  fun aFreshSnapshotWithACompleteRuntimeProceeds() {
    val d = safeModeRestartDecision(liveComplete = true, snapshotFresh = true, ownedStopOk = true)
    assertEquals(SafeModeRestartDecision.PROCEED, d)
    assertTrue("快照就绪时必须允许重启", safeModeRestartCanProceed(d))
  }

  /** #240 的有意降级窗：快照未就绪**不等于**运行时不可用，必须保留放行。 */
  @Test
  fun aStaleSnapshotWithACompleteRuntimeStillProceeds() {
    val d = safeModeRestartDecision(liveComplete = true, snapshotFresh = false, ownedStopOk = true)
    assertEquals(SafeModeRestartDecision.PROCEED_SNAPSHOT_STALE, d)
    assertTrue("#240 有意降级窗必须保留放行（不得矫枉过正）", safeModeRestartCanProceed(d))
  }

  // ── 负例：live 不完整 => 绝不放行 ─────────────────────────────────────

  /** 负例必须红：live 树残缺时停掉旧进程 = 把用户从「能跑但插件错」推到「跑不了」。 */
  @Test
  fun anIncompleteLiveRuntimeIsNeverAllowedToRestart() {
    for (fresh in listOf(true, false)) {
      val d = safeModeRestartDecision(liveComplete = false, snapshotFresh = fresh, ownedStopOk = true)
      assertEquals(
        "live 不完整时无论快照是否就绪都必须拒绝（snapshotFresh=$fresh）",
        SafeModeRestartDecision.REFUSE_LIVE_INCOMPLETE, d,
      )
      assertFalse("live 不完整绝不放行（snapshotFresh=$fresh）", safeModeRestartCanProceed(d))
    }
  }

  // ── 负例：归属权不属于本壳 => 绝不盲目重启 ───────────────────────────────

  @Test
  fun aForeignListenerIsNeverRestarted() {
    val d = safeModeRestartDecision(liveComplete = true, snapshotFresh = true, ownedStopOk = false)
    assertEquals(SafeModeRestartDecision.REFUSE_OWNERSHIP, d)
    assertFalse("端口未归属本壳时绝不停止/重启", safeModeRestartCanProceed(d))
  }

  /**
   * 「停了但没起来」的收口：stopOwnedEngine() 返回 false 时（未归属 / 停止失败）判定必须是拒绝面，
   * 绝不能被当成放行而继续 spawn——那正是「覆盖未知监听器」的形态。
   */
  @Test
  fun aFailedOwnedStopEndsInTheRefusalFace() {
    val d = safeModeRestartDecision(liveComplete = true, snapshotFresh = true, ownedStopOk = false)
    assertFalse("停止失败/未归属时不得继续启动", safeModeRestartCanProceed(d))
    assertEquals(SafeModeRestartDecision.REFUSE_OWNERSHIP, d)
  }

  // ── 两条相反用例必须真的相反（防回归成恒真/恒假）────────────────────────

  @Test
  fun theDegradeCaseAndTheIncompleteCaseDisagree() {
    val degrade = safeModeRestartDecision(liveComplete = true, snapshotFresh = false, ownedStopOk = true)
    val refuse = safeModeRestartDecision(liveComplete = false, snapshotFresh = false, ownedStopOk = true)
    assertNotEquals("live 完整性必须是两条相反用例的唯一分界", degrade, refuse)
    assertTrue("live 完整面放行", safeModeRestartCanProceed(degrade))
    assertFalse("live 不完整面拒绝", safeModeRestartCanProceed(refuse))
  }

  // ── 如实文案（D4/D5）：不得宣称已就绪，也不得隐瞒失败 ─────────────────────

  /** 放行面此刻旧已停、新未起：文案必须是中间态，**不得**出现「已就绪/已生效/已启动完成」。 */
  @Test
  fun theProceedHintsDescribeAnInProgressRestart() {
    for (d in listOf(SafeModeRestartDecision.PROCEED, SafeModeRestartDecision.PROCEED_SNAPSHOT_STALE)) {
      val hint = safeModeRestartHint(d)
      assertTrue("放行面文案不得为空（$d）", hint.isNotBlank())
      assertTrue("放行面必须说明正在重启（$d）", hint.contains("重启"))
      for (claim in listOf("已就绪", "已生效", "启动完成")) {
        assertFalse("放行面不得宣称「$claim」（$d): $hint", hint.contains(claim))
      }
    }
  }

  /** 拒绝面必须说清「安全模式的插件改动本次不生效」——不许让用户以为已经生效。 */
  @Test
  fun theRefusalHintsStateThatNothingTookEffect() {
    for (d in listOf(SafeModeRestartDecision.REFUSE_LIVE_INCOMPLETE, SafeModeRestartDecision.REFUSE_OWNERSHIP)) {
      val hint = safeModeRestartHint(d)
      assertTrue("拒绝面文案不得为空（$d）", hint.isNotBlank())
      assertTrue("拒绝面必须写明改动未生效（$d): $hint", hint.contains("不生效"))
      assertTrue("拒绝面必须写明未停止未重启（$d）", hint.contains("未停止") || hint.contains("未重启"))
    }
  }

  /** 判据必须覆盖取值域：每个 decision 都有非空文案（新增取值忘了补文案即红）。 */
  @Test
  fun everyDecisionHasItsOwnHint() {
    val hints = SafeModeRestartDecision.entries.map { safeModeRestartHint(it) }
    assertEquals("取值域与文案必须一一对应", SafeModeRestartDecision.entries.size, hints.size)
    assertEquals("每条文案必须互不相同", hints.size, hints.toSet().size)
    assertTrue("不得有空白文案", hints.all { it.isNotBlank() })
  }
}
