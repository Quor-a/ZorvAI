package com.ai.assistance.quro.dshmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通道判据的真值表**与整条取数链**（复审 2026-10-02 第 5 点：不能只测纯函数）。
 *
 * 这里用可注入的 [ShizukuTransport.RootProbeEnv] 跑**真实的 `probeRootChannel` 调用链**：
 * 假环境记录「有没有真的去绑」「绑成什么样」，从而覆盖「权限面假阴性但通道其实可用」这类回归。
 */
class RootChannelDecisionTest {

  /** 记录调用痕迹的假环境。 */
  private class FakeEnv(
    override val suGranted: Boolean = false,
    override val suBinaryPresent: Boolean = true,
    override val shizukuInstalled: Boolean = true,
    private val ping: Boolean? = true,
    private val preBoundUid: Int? = null,
    private val bindResult: ShizukuTransport.BindOutcome = ShizukuTransport.BindOutcome.DENIED,
    private val postBindUid: Int? = null,
  ) : ShizukuTransport.RootProbeEnv {
    var bindCalls = 0
    override fun binderPing(): Boolean? = ping
    override fun boundServiceUid(): Int? = if (bindCalls > 0) postBindUid else preBoundUid
    override fun bindUserService(): ShizukuTransport.BindOutcome { bindCalls++; return bindResult }
  }

  private fun probe(env: ShizukuTransport.RootProbeEnv) = ShizukuTransport.probeRootChannel(env)

  // ── 整条链：真绑定尝试（复审第 1、5.1、5.2、5.8 点）────────────────────────────

  @Test fun permissionFalseNegativeMustNotBlockTheRealBind() {
    // 复审第 1 点的回归：权限面读成「未授权」时，**仍然必须真去绑定**；
    // 绑上了且 uid 0 ⇒ AVAILABLE（而不是仅凭不可靠信号判 ABSENT ⇒ 误清隔离）。
    val env = FakeEnv(
      suGranted = false, ping = true,
      bindResult = ShizukuTransport.BindOutcome.BOUND, postBindUid = 0,
    )
    val result = probe(env)
    assertEquals(ShizukuTransport.RootChannel.AVAILABLE, result.state)
    assertEquals(1, env.bindCalls)          // ★ 真绑过
    assertTrue(result.detail.contains("bind=BOUND"))
  }

  @Test fun deniedPermissionSurfaceIsUnknownNotAbsent() {
    // 复审第 1 点：客户端权限面自相矛盾/被拒 ⇒ **证明不了**通道不存在 ⇒ UNKNOWN（保留隔离）
    val env = FakeEnv(bindResult = ShizukuTransport.BindOutcome.DENIED)
    val result = probe(env)
    assertEquals(ShizukuTransport.RootChannel.UNKNOWN, result.state)
    assertEquals(1, env.bindCalls)          // 试过了
    assertTrue(result.detail.contains("bind=DENIED"))
  }

  @Test fun alreadyBoundServiceShortCircuitsWithoutANewBind() {
    val env = FakeEnv(preBoundUid = 0)
    val result = probe(env)
    assertEquals(ShizukuTransport.RootChannel.AVAILABLE, result.state)
    assertEquals(0, env.bindCalls)          // 零代价路径：已有活绑定不再绑一次
  }

  @Test fun boundNonRootServiceIsTheOnlyPositiveAbsenceProofFromBinding() {
    val env = FakeEnv(bindResult = ShizukuTransport.BindOutcome.BOUND, postBindUid = 2000)
    val result = probe(env)
    assertEquals(ShizukuTransport.RootChannel.ABSENT, result.state)
  }

  @Test fun serverDefinitelyAbsentIsAbsentAndNeverBinds() {
    val env = FakeEnv(ping = false)
    val result = probe(env)
    assertEquals(ShizukuTransport.RootChannel.ABSENT, result.state)
    assertEquals(0, env.bindCalls)          // 服务端不在 ⇒ 不必绑
    assertTrue(result.detail.contains("bind=SERVER_ABSENT"))
  }

  @Test fun binderThrowStaysUnknownAndNeverBinds() {
    // 复审第 5.7 点：探测过程中 binder 抛异常 ⇒ 绝不能误清
    val env = FakeEnv(ping = null)
    val result = probe(env)
    assertEquals(ShizukuTransport.RootChannel.UNKNOWN, result.state)
    assertEquals(0, env.bindCalls)
    assertTrue(result.detail.contains("ping=throw"))
  }

  @Test fun bindTimeoutOrBinderThrowStaysUnknown() {
    for (outcome in listOf(
      ShizukuTransport.BindOutcome.TIMEOUT,
      ShizukuTransport.BindOutcome.BINDER_THREW,
      ShizukuTransport.BindOutcome.UNKNOWN,
      ShizukuTransport.BindOutcome.NOT_ATTEMPTED,
    )) {
      assertEquals(outcome.toString(), ShizukuTransport.RootChannel.UNKNOWN, probe(FakeEnv(bindResult = outcome)).state)
    }
  }

  @Test fun bothTransportsMissingIsTheStrongestAbsenceProof() {
    assertEquals(ShizukuTransport.RootChannel.ABSENT,
      probe(FakeEnv(shizukuInstalled = false, suBinaryPresent = false, ping = null)).state)
    // 但「没装 Shizuku 却仍有 su 二进制」证明不了 ⇒ UNKNOWN
    assertEquals(ShizukuTransport.RootChannel.UNKNOWN,
      probe(FakeEnv(shizukuInstalled = false, suBinaryPresent = true, ping = null)).state)
  }

  // ── 纯判据真值表 ────────────────────────────────────────────────────────────

  @Test fun decideTableCoversEveryOutcome() {
    val d = { installed: Boolean, su: Boolean, ping: Boolean?, outcome: ShizukuTransport.BindOutcome, uid: Int ->
      ShizukuTransport.decideNoRootChannel(installed, su, ping, outcome, uid)
    }
    val B = ShizukuTransport.BindOutcome.BOUND
    assertEquals(ShizukuTransport.RootChannel.AVAILABLE, d(true, false, true, B, 0))
    assertEquals(ShizukuTransport.RootChannel.ABSENT, d(true, true, true, B, 2000))
    assertEquals(ShizukuTransport.RootChannel.ABSENT, d(false, false, null, ShizukuTransport.BindOutcome.NOT_ATTEMPTED, -1))
    assertEquals(ShizukuTransport.RootChannel.UNKNOWN, d(false, true, null, ShizukuTransport.BindOutcome.NOT_ATTEMPTED, -1))
    assertEquals(ShizukuTransport.RootChannel.ABSENT, d(true, true, false, ShizukuTransport.BindOutcome.SERVER_ABSENT, -1))
    assertEquals(ShizukuTransport.RootChannel.ABSENT, d(true, true, true, ShizukuTransport.BindOutcome.PROTOCOL_TOO_OLD, -1))
    assertEquals(ShizukuTransport.RootChannel.UNKNOWN, d(true, true, true, ShizukuTransport.BindOutcome.DENIED, -1))
    assertEquals(ShizukuTransport.RootChannel.UNKNOWN, d(true, true, null, ShizukuTransport.BindOutcome.TIMEOUT, -1))
  }

  // ── 自动清算准入（复审第 2、5.3、5.5 点；第四轮加 leaseUnknown）────────────────

  @Test fun autoClearNeedsAbsentChannelPlusNoDispatchEvidencePlusNoLiveMaintenance() {
    val ABSENT = ShizukuTransport.RootChannel.ABSENT
    assertTrue(ShizukuTransport.autoClearAllowed(ABSENT, false, false, false))
    // 已派发过 ⇒ 一律否决（外部工作是否结束，本进程证明不了）
    assertFalse(ShizukuTransport.autoClearAllowed(ABSENT, true, false, false))
    // 进程内有在飞维护 ⇒ 否决
    assertFalse(ShizukuTransport.autoClearAllowed(ABSENT, false, false, true))
    // 通道还在/说不清 ⇒ 否决
    assertFalse(ShizukuTransport.autoClearAllowed(ShizukuTransport.RootChannel.AVAILABLE, false, false, false))
    assertFalse(ShizukuTransport.autoClearAllowed(ShizukuTransport.RootChannel.UNKNOWN, false, false, false))
  }

  @Test fun untrustworthyLeaseStateVetoesAutoClearEvenWithoutDispatchEvidence() {
    // 复审第四轮的核心：**「没有派发证据」≠「证明没有派发」**。
    // markDispatched 持久化失败时只置 unknown、dispatched 仍是 false；恢复别的进程的租约同样 unknown
    // ⇒ 这两种情况下 `dispatched=false` 可能正意味着「证据没落盘」⇒ 绝不能自动清。
    val ABSENT = ShizukuTransport.RootChannel.ABSENT
    assertFalse(ShizukuTransport.autoClearAllowed(ABSENT, false, true, false))
    assertFalse(ShizukuTransport.autoClearAllowed(ABSENT, true, true, false))
    assertFalse(ShizukuTransport.autoClearAllowed(ABSENT, false, true, true))
  }

  @Test fun unknownChannelIsNeverAutoClearedUnderAnyEvidenceCombination() {
    for (dispatched in listOf(false, true)) {
      for (unknown in listOf(false, true)) {
        for (active in listOf(false, true)) {
          assertFalse(ShizukuTransport.autoClearAllowed(
            ShizukuTransport.RootChannel.UNKNOWN, dispatched, unknown, active))
        }
      }
    }
  }
}
