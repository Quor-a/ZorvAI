package com.ai.assistance.quro.dshmobile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UndoGate 闸门纯决策回归（0.14.1）。
 *
 * 为什么需要本文件：本闸门是「自动 undo 到底会不会跑」的**唯一判据**，而它原先整体依赖
 * Context + 文件系统，导致这段决定「是否自救」的逻辑在全仓**零测试覆盖**——`planTick` 的熔断
 * 锁存盲区正是同类「恢复判据无防线」的产物（一个不会失败的判断不是防线）。
 * 0.14.1 把四态判定抽成 [UndoGate.decide]，此处直接断言，不依赖 Android 运行期。
 *
 * 覆盖五态：IDLE / SUPPRESS / ARM / WAIT / EXECUTE，以及两阶段 arm → watch 的边界值。
 */
class UndoGateDecisionTest {

  private val t0 = 1_000_000L

  @Test
  fun retryMarkerSuppressesOnlyTheSameInstallAndFailedPatch() {
    val identity = UndoGate.RetryIdentity("install-a", "patch-a")
    val marker = """{"schema":2,"at":$t0,"installFingerprint":"install-a","patchDigest":"patch-a"}"""
    assertEquals(t0, UndoGate.retrySuppression(marker, identity, t0 + 1_000L).atMs)
    assertEquals(null, UndoGate.retrySuppression(marker, identity.copy(patchDigest = "patch-b"), t0 + 1_000L).atMs)
    assertEquals(null, UndoGate.retrySuppression(marker, identity.copy(installFingerprint = "install-b"), t0 + 1_000L).atMs)
    assertEquals(null, UndoGate.retrySuppression(marker, identity, t0 + UndoGate.RETRY_WINDOW_MS).atMs)
    assertEquals(true, UndoGate.retrySuppression(marker, identity.copy(patchDigest = "patch-b"), t0 + 1_000L).resetWatch)
    assertEquals(true, UndoGate.retrySuppression(marker, identity.copy(installFingerprint = "install-b"), t0 + 1_000L).resetWatch)
  }

  @Test
  fun legacyTimestampRemainsConservativelySuppressedUntilItsWindowExpires() {
    val result = UndoGate.retrySuppression(t0.toString(), UndoGate.RetryIdentity("new", "candidate"), t0 + 1_000L)
    assertEquals(t0, result.atMs)
    assertEquals(true, result.legacyMarker)
    assertEquals(null, UndoGate.retrySuppression(t0.toString(), UndoGate.RetryIdentity("new", "candidate"), t0 + UndoGate.RETRY_WINDOW_MS).atMs)
  }

  @Test
  fun onlyTheExplicitUserRestartClearsTheRetryEpoch() {
    val src = File("src/main/java/com/ai/assistance/quro/dshmobile/EngineStartFlow.kt").readText()
    val clearCalls = Regex("UndoGate\\.clearRetryEpochForUserRestart\\(").findAll(src).count()
    val restart = src.substringAfter("fun restart(): Boolean").substringBefore("fun ")
    assertEquals("automatic start/retry paths must not clear suppression", 1, clearCalls)
    assertTrue("the explicit restart clears only after proving its child stopped", restart.indexOf("stopOwnedEngine()") < restart.indexOf("clearRetryEpochForUserRestart"))
  }

  @Test
  fun belowTriggerThresholdStaysIdle() {
    // 阈值是 6：5 拍（半死阶梯真实会先到的一档）必须不动作，避免误伤正常慢启动。
    assertEquals(
      UndoGate.GateDecision.IDLE,
      UndoGate.decide(
        consecutiveFailures = UndoGate.TRIGGER_CONSEC_FAILURES - 1,
        nowMs = t0,
        lastUndoAtMs = null,
        armedAtMs = null,
      ),
    )
  }

  @Test
  fun firstConfirmedDeathArmsInsteadOfExecuting() {
    // 首次达阈值只起观察窗：留给引擎自愈的最后机会（正常慢启动上限 45s+）。
    assertEquals(
      UndoGate.GateDecision.ARM,
      UndoGate.decide(
        consecutiveFailures = UndoGate.TRIGGER_CONSEC_FAILURES,
        nowMs = t0,
        lastUndoAtMs = null,
        armedAtMs = null,
      ),
    )
  }

  @Test
  fun insideTheWatchWindowItWaits() {
    assertEquals(
      UndoGate.GateDecision.WAIT,
      UndoGate.decide(
        consecutiveFailures = UndoGate.TRIGGER_CONSEC_FAILURES + 4,
        nowMs = t0 + UndoGate.WATCH_MS - 1,
        lastUndoAtMs = null,
        armedAtMs = t0,
      ),
    )
  }

  @Test
  fun exactlyAtTheWatchWindowBoundaryItExecutes() {
    // 边界值（WATCH_MS 整）：条件为 `now - armedAt < WATCH_MS` 才 WAIT，故整点即放行。
    assertEquals(
      UndoGate.GateDecision.EXECUTE,
      UndoGate.decide(
        consecutiveFailures = UndoGate.TRIGGER_CONSEC_FAILURES + 4,
        nowMs = t0 + UndoGate.WATCH_MS,
        lastUndoAtMs = null,
        armedAtMs = t0,
      ),
    )
  }

  @Test
  fun afterTheWatchWindowItExecutes() {
    assertEquals(
      UndoGate.GateDecision.EXECUTE,
      UndoGate.decide(
        consecutiveFailures = UndoGate.TRIGGER_CONSEC_FAILURES + 10,
        nowMs = t0 + UndoGate.WATCH_MS + 1,
        lastUndoAtMs = null,
        armedAtMs = t0,
      ),
    )
  }

  @Test
  fun insideTheRetryWindowItIsSuppressedEvenWithAStaleArm() {
    // 防循环：一次成功执行后的 30 分钟窗口内不得再自动执行（无论观察窗是否走完）。
    assertEquals(
      UndoGate.GateDecision.SUPPRESS,
      UndoGate.decide(
        consecutiveFailures = UndoGate.TRIGGER_CONSEC_FAILURES + 10,
        nowMs = t0 + UndoGate.RETRY_WINDOW_MS - 1,
        lastUndoAtMs = t0,
        armedAtMs = t0 - UndoGate.WATCH_MS,
      ),
    )
  }

  @Test
  fun exactlyAtTheRetryWindowBoundaryItIsNoLongerSuppressed() {
    assertEquals(
      UndoGate.GateDecision.EXECUTE,
      UndoGate.decide(
        consecutiveFailures = UndoGate.TRIGGER_CONSEC_FAILURES,
        nowMs = t0 + UndoGate.RETRY_WINDOW_MS,
        lastUndoAtMs = t0,
        armedAtMs = t0 - UndoGate.WATCH_MS,
      ),
    )
  }

  @Test
  fun retryWindowOutranksArming() {
    // 抑制期优先于起窗：不得靠"重新 arm"绕过防循环窗口。
    assertEquals(
      UndoGate.GateDecision.SUPPRESS,
      UndoGate.decide(
        consecutiveFailures = UndoGate.TRIGGER_CONSEC_FAILURES,
        nowMs = t0 + 1_000L,
        lastUndoAtMs = t0,
        armedAtMs = null,
      ),
    )
  }

  @Test
  fun idleOutranksEveryLaterStage() {
    // 未达阈值时，即使残留 arm 文件也不得误触发（旧实现会读 arm 文件后走 WAIT/EXECUTE 分支）。
    assertEquals(
      UndoGate.GateDecision.IDLE,
      UndoGate.decide(
        consecutiveFailures = 0,
        nowMs = t0 + UndoGate.WATCH_MS + UndoGate.RETRY_WINDOW_MS,
        lastUndoAtMs = null,
        armedAtMs = t0,
      ),
    )
  }

  @Test
  fun twoPhaseSequenceArmsThenExecutes() {
    // 端到端两阶段语义（与 onProbeFailure 的落盘路径同序）：ARM → WAIT → EXECUTE。
    val threshold = UndoGate.TRIGGER_CONSEC_FAILURES
    assertEquals(UndoGate.GateDecision.ARM, UndoGate.decide(threshold, t0, null, null))
    val armedAt = t0
    assertEquals(UndoGate.GateDecision.WAIT, UndoGate.decide(threshold, t0 + 5_000L, null, armedAt))
    assertEquals(UndoGate.GateDecision.EXECUTE, UndoGate.decide(threshold, t0 + UndoGate.WATCH_MS, null, armedAt))
  }

  @Test
  fun watchWindowCannotBeRestartedByRepeatedArming() {
    // 回归：观察窗起点由 arm 文件承载；重复调用不得把窗口无限顺延（否则永不 EXECUTE）。
    // 边界口径与实现一致：`now - armedAt < WATCH_MS` 才 WAIT，故「恰好 WATCH_MS」已 EXECUTE。
    val armedAt = t0
    val threshold = UndoGate.TRIGGER_CONSEC_FAILURES
    // 窗口内（未到点）：仍是 WAIT。循环上界必须停在窗口**内**——曾误写成 repeat(10) 推进到
    // +50s，第 3 次(+15s) 就已越界，断言必然不成立（测试写错，非实现错）。
    for (k in 1..2) { // +5s / +10s，均 < WATCH_MS(15s)
      assertEquals(
        "第 $k 次窗口内调用必须仍为 WAIT（窗口未到点）",
        UndoGate.GateDecision.WAIT,
        UndoGate.decide(threshold, armedAt + k * 5_000L, null, armedAt),
      )
    }
    // 窗口内**反复**观察同一个 armedAt：不得把窗口重置/顺延（幂等，仍是 WAIT）。
    repeat(3) {
      assertEquals(
        "同一 armedAt 被反复观察不得改变窗口语义",
        UndoGate.GateDecision.WAIT,
        UndoGate.decide(threshold, armedAt + UndoGate.WATCH_MS - 1, null, armedAt),
      )
    }
    // 边界：恰好到 WATCH_MS → EXECUTE（证明窗口没被无限顺延）。
    assertEquals(
      "恰好到 WATCH_MS 必须 EXECUTE，不得因反复调用永远 WAIT",
      UndoGate.GateDecision.EXECUTE,
      UndoGate.decide(threshold, armedAt + UndoGate.WATCH_MS, null, armedAt),
    )
    // 再往后仍然 EXECUTE（窗口不会被重置回 WAIT）。
    assertEquals(
      UndoGate.GateDecision.EXECUTE,
      UndoGate.decide(threshold, armedAt + 10 * UndoGate.WATCH_MS, null, armedAt),
    )
  }

  // ── M.3 F（issue #274 ①）：回滚的**证据门槛** ──────────────────────────────────
  //
  // 真因：assessProbe 在 HTTP 超 2.5s 预算时给 DEGRADED_HTTP（注释自承实测出现过 3061ms）。
  // 长 turn / 慢磁盘即可让它连续 6 拍（30s）⇒ 回滚用户配置 + 强杀活引擎。
  // 一次慢响应不该有这种权限。
  //
  // 判据刻意**不动** [decide]（坑 153 依赖它的计数语义：半死引擎必须仍能走到 EXECUTE），
  // 而是把「证据语义」独立成第二道门。两道门的分工是本修复的关键。

  /** 正证：无证据（默认）时必须**不否决** —— 既有调用点/既有语义不受影响。 */
  @Test
  fun noEvidenceDoesNotRefuseRollback() {
    assertEquals(
      "默认无证据不得否决（否则半死引擎再也救不回来，坑 153 被破坏）",
      null,
      UndoGate.rollbackEvidenceRefusal(UndoGate.RollbackEvidence.NONE),
    )
  }

  /** 反证：日志有 EADDRINUSE ⇒ 否决（端口被别人占着，回滚/重启都不解决问题）。 */
  @Test
  fun eaddrinuseRefusesRollback() {
    val refusal = UndoGate.rollbackEvidenceRefusal(
      UndoGate.RollbackEvidence(logTail = "Error: listen EADDRINUSE :::3080"),
    )
    assertEquals("必须给出可诊断理由", true, refusal != null)
    assertEquals("理由必须点名 EADDRINUSE", true, refusal!!.contains("EADDRINUSE"))
  }

  /** 反证：端口确认**不是**本进程持有 ⇒ 否决（重启我们不解决问题）。 */
  @Test
  fun foreignPortOwnerRefusesRollback() {
    val refusal = UndoGate.rollbackEvidenceRefusal(UndoGate.RollbackEvidence(portOwnedByApp = false))
    assertEquals("端口非本进程持有时必须否决", true, refusal != null)
    assertEquals("理由必须点名端口归属", true, refusal!!.contains("他进程"))
  }

  /**
   * 反证（核心）：**只是略超预算**（长 turn / 慢磁盘）⇒ 否决。
   * 这是 issue #274 ① 的直接靶子：探活 2.5s 预算被 3061ms 打穿就升级成破坏性自愈。
   */
  @Test
  fun merelySlowProbeRefusesRollback() {
    val refusal = UndoGate.rollbackEvidenceRefusal(UndoGate.RollbackEvidence(slowOnly = true))
    assertEquals("仅略超预算时必须否决（一次慢响应不该回滚用户配置）", true, refusal != null)
    assertEquals("理由必须说明是慢而非故障", true, refusal!!.contains("略超预算"))
  }

  /** 边界：端口归属**未知**（拿不到归属）不得否决（不因测量失败放宽/收紧破坏性动作）。 */
  @Test
  fun unknownPortOwnershipDoesNotRefuse() {
    assertEquals(
      "未知归属 = 不否决（测量失败不该变成否决理由）",
      null,
      UndoGate.rollbackEvidenceRefusal(UndoGate.RollbackEvidence(portOwnedByApp = null)),
    )
  }

  /** 边界：普通日志 + 端口归我们 ⇒ 不否决（确认是「我们的引擎半死」时才允许回滚救）。 */
  @Test
  fun ordinaryLogWithOurPortDoesNotRefuse() {
    assertEquals(
      "我们的引擎半死时回滚必须仍可用（这是坑 153 要保的能力）",
      null,
      UndoGate.rollbackEvidenceRefusal(
        UndoGate.RollbackEvidence(logTail = "dsh web: listening on 3080", portOwnedByApp = true),
      ),
    )
  }
}
