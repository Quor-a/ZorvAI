package com.ai.assistance.quro.dshmobile

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 「插件树挂死」形态的阶梯回归（2026-09-21，设备实证：注入坏插件后自动回滚根本不触发）。
 *
 * 现场：注入一个 `lib/index.js` 直接 throw 的插件并把挂载项写进 `cordis.patch.yml` → 重启引擎。
 * 引擎日志（`files/engine.log.1`）实读：
 * ```
 * Error: dsh: plugin tree failed to load: failed to apply loader entry include (cordis:include):
 *        failed to import loader entry dsh-bad-probe (@dsh-android/dsh-bad-probe): INJECTED-BAD-PLUGIN
 * ```
 * 而 `assessProbe` 判为 `DEGRADED_LOG`（HTTP 活着），`planTick` 在这一拍**直接早退 IDLE**（0.14.1 为
 * 「不打断活动 turn」刻意保留的语义）⇒ ①`undoReady()` 永不被求值；②调用方在 IDLE 拍还会
 * `UndoGate.disarm`。结果是自动 undo 与自动重启**同时失效**，用户只剩手动重启。
 *
 * 本文件钉住修法：
 *  - 只有**插件树装配失败**这一条签名参与升级（`UncaughtException` 类「活引擎炸过一次」不打扰）；
 *  - 该形态下放行到 undo 决策，且熔断不得把它锁成永久 HOLD；
 *  - 计数参与 [WatchdogV2.effectiveFailureCount]（否则闸门凑不够触发拍数）；`reset()` 清干净。
 */
class WatchdogPluginTreeTest {

  @Before fun setUp() = WatchdogV2.reset()

  @After fun tearDown() = WatchdogV2.reset()

  @Test
  fun 只有插件树签名参与升级计数() {
    // 正例：DEGRADED_LOG + 插件树签名 → 记账
    assertEquals(1, WatchdogV2.nextPluginTreeCount(WatchdogV2.ProbeState.DEGRADED_LOG, WatchdogV2.SIGNATURE_PLUGIN_TREE, 0))
    assertEquals(4, WatchdogV2.nextPluginTreeCount(WatchdogV2.ProbeState.DEGRADED_LOG, WatchdogV2.SIGNATURE_PLUGIN_TREE, 3))
    // 否定：同状态但别的签名（活引擎炸过一次）不记账——否则会为了「不打断 turn」的语义反着来
    assertEquals(0, WatchdogV2.nextPluginTreeCount(WatchdogV2.ProbeState.DEGRADED_LOG, WatchdogV2.SIGNATURE_UNCAUGHT, 3))
    assertEquals(0, WatchdogV2.nextPluginTreeCount(WatchdogV2.ProbeState.DEGRADED_LOG, null, 3))
    // 否定：其它状态一律清零
    for (st in listOf(WatchdogV2.ProbeState.HEALTHY, WatchdogV2.ProbeState.DEGRADED_HTTP, WatchdogV2.ProbeState.DEAD)) {
      assertEquals("$st 必须清零", 0, WatchdogV2.nextPluginTreeCount(st, WatchdogV2.SIGNATURE_PLUGIN_TREE, 3))
    }
    assertTrue(WatchdogV2.pluginTreeHung(WatchdogV2.ProbeState.DEGRADED_LOG, WatchdogV2.SIGNATURE_PLUGIN_TREE))
    assertFalse(WatchdogV2.pluginTreeHung(WatchdogV2.ProbeState.DEGRADED_LOG, WatchdogV2.SIGNATURE_UNCAUGHT))
    assertFalse(WatchdogV2.pluginTreeHung(WatchdogV2.ProbeState.HEALTHY, WatchdogV2.SIGNATURE_PLUGIN_TREE))
  }

  @Test
  fun 日志尾部签名解析_插件树优先() {
    assertEquals(WatchdogV2.SIGNATURE_PLUGIN_TREE, WatchdogV2.logSignatureOf("... plugin tree failed to load: ..."))
    assertEquals(WatchdogV2.SIGNATURE_UNCAUGHT, WatchdogV2.logSignatureOf("... java.lang.UncaughtException ..."))
    assertEquals(
      "两种签名同时出现时以装配失败为准（它是不可自愈的那个）",
      WatchdogV2.SIGNATURE_PLUGIN_TREE,
      WatchdogV2.logSignatureOf("UncaughtException\nplugin tree failed to load"),
    )
    assertEquals(null, WatchdogV2.logSignatureOf(""))
    assertEquals(null, WatchdogV2.logSignatureOf("engine listening on 3080"))
  }

  @Test
  fun 插件树挂死必须走到undo决策而不是早退IDLE() {
    // 造 6 拍连续的插件树失败（阈值与 watch 一致）
    repeat(6) { WatchdogV2.recordProbe(WatchdogV2.ProbeState.DEGRADED_LOG, WatchdogV2.SIGNATURE_PLUGIN_TREE) }
    assertEquals("计数必须进入失败总数", 6, WatchdogV2.effectiveFailureCount())

    val plan = WatchdogV2.planTick(
      state = WatchdogV2.ProbeState.DEGRADED_LOG,
      now = 1_000_000L,
      nextRestartAllowedAt = 0L,
      engineReady = true,
      engineProcessAlive = true,
      // 越过 90s 冷启动预算：托管子进程还在场，但早就不是「正在冷启动」
      bootAgeMs = 200_000L,
      restartDeadConfirmations = 2,
      logSignature = WatchdogV2.SIGNATURE_PLUGIN_TREE,
      feedProbe = {},
      consumeMarkers = {},
      refreshWake = {},
      undoReady = { true },
    )
    // 改前这里返回 IDLE（HTTP 活着就早退），因此这条断言在修复前必红。
    assertEquals(WatchdogV2.TickAction.UNDO, plan.action)
  }

  @Test
  fun 活引擎的未捕获异常不得被升级() {
    repeat(6) { WatchdogV2.recordProbe(WatchdogV2.ProbeState.DEGRADED_LOG, WatchdogV2.SIGNATURE_UNCAUGHT) }
    assertEquals("别的签名不计数", 0, WatchdogV2.effectiveFailureCount())
    val plan = WatchdogV2.planTick(
      state = WatchdogV2.ProbeState.DEGRADED_LOG,
      now = 1_000_000L,
      nextRestartAllowedAt = 0L,
      engineReady = true,
      engineProcessAlive = true,
      bootAgeMs = 200_000L,
      restartDeadConfirmations = 2,
      logSignature = WatchdogV2.SIGNATURE_UNCAUGHT,
      feedProbe = {},
      consumeMarkers = {},
      refreshWake = {},
      undoReady = { true },
    )
    assertEquals("活动 turn 语义：HTTP 活着且非装配失败 → IDLE", WatchdogV2.TickAction.IDLE, plan.action)
  }

  @Test
  fun 插件树挂死不得被熔断锁成永久HOLD() {
    repeat(WatchdogV2.MAX_CONSEC_FAILURES + 1) { WatchdogV2.recordProbe(WatchdogV2.ProbeState.DEGRADED_LOG, WatchdogV2.SIGNATURE_PLUGIN_TREE) }
    assertTrue("计数已越过熔断阈值", WatchdogV2.tripped())
    val plan = WatchdogV2.planTick(
      state = WatchdogV2.ProbeState.DEGRADED_LOG,
      now = 1_000_000L,
      nextRestartAllowedAt = 0L,
      engineReady = true,
      engineProcessAlive = true,
      bootAgeMs = 200_000L,
      restartDeadConfirmations = 2,
      logSignature = WatchdogV2.SIGNATURE_PLUGIN_TREE,
      feedProbe = {},
      consumeMarkers = {},
      refreshWake = {},
      // undo 被 30 分钟重试窗闸掉：此时若再被熔断锁死就彻底没有自动路径了
      undoReady = { false },
    )
    assertEquals("undo 不可用时至少还能重启（受退避节流）", WatchdogV2.TickAction.RESTART, plan.action)
  }
}
