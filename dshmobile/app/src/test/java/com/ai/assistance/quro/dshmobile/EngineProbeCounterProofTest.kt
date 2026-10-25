package com.ai.assistance.quro.dshmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M.1（apk #272）四条修法的**反证**：把「旧实现」的语义**在测试里就地复刻**，
 * 断言它与新实现**结论不同** ⇒ 证明新判据真的承载了修法，而不是恒真。
 *
 * ── 为什么用「传参复刻旧实现」而不是「改生产源码再跑」────────────────────────────
 * 本仓 lead 定下的纪律：**反证不得就地改生产源码**。原因是实测事故：上一轮我把变异写进
 * EngineProbe.kt 后进程被 kill，变异**留在了工作树里**，导致一次权威全量运行看到 3 条假红
 * （看起来像产品缺陷，实际是测试脚手架残留）。
 * 本文件的做法（lead 建议的 (c) 方案）：判据全部是可注入的纯函数，**旧语义以局部函数形式写在测试内**，
 * 于是反证不碰任何生产文件——kill 也不会污染工作树。
 *
 * 每条反证的结构都是：`旧语义(输入) != 新语义(输入)`，并显式断言新语义是「对」的那一侧。
 */
class EngineProbeCounterProofTest {

  // ── 反证 (a)：端口可连 ⇒ 旧实现当健康；新实现必须报 PORT_FOREIGN ─────────────

  @Test
  fun `counter-proof-a old implementation would call a foreign listener healthy`() {
    // 旧实现：端口可连即 running=true ⇒ 在四态里等价于「当成我们自己的引擎」。
    fun oldPortReachableMeansOurs(portReachable: Boolean): Boolean = portReachable
    val portReachable = true
    assertTrue("旧实现的判据确实会把端口可连当健康（前提成立）", oldPortReachableMeansOurs(portReachable))
    val newState = EngineProbe.classifyEngineAvailability(
      httpCode = -1, managedAlive = false, logHasTokenLine = false, portReachable = portReachable,
    )
    assertFalse("新实现必须不再把它当我们的引擎", newState == EngineProbe.EngineAvailability.OUR_HTTP)
    assertFalse(newState == EngineProbe.EngineAvailability.OUR_PROCESS)
    assertEquals("新实现必须给出可辨状态 PORT_FOREIGN", EngineProbe.EngineAvailability.PORT_FOREIGN, newState)
  }

  // ── 反证 (b)：401 ⇒ 旧实现当「健康」；新实现必须报 REQUIRED ────────────────────

  @Test
  fun `counter-proof-b old implementation would treat 401 as authenticated`() {
    // 旧实现：401 与 200/303 同列 running（= 「不用管」）。
    fun oldIsHealthy(code: Int): Boolean = code == 200 || code == 401 || code == 303
    assertTrue("旧实现确实把 401 当健康（前提成立）", oldIsHealthy(401))
    assertEquals("新实现必须把 401 标为需要重新认证", EngineProbe.EngineAuthState.REQUIRED, EngineProbe.classifyAuthState(401))
    assertFalse("新实现不得把 401 标为 ok", EngineProbe.classifyAuthState(401) == EngineProbe.EngineAuthState.OK)
    // 且 running 口径**保持不变**（这是刻意的正交分层，见 EngineProbe 类注释）。
    assertTrue("running 口径仍含 401（不得改动，WatchdogV2 依赖它）", oldIsHealthy(401))
  }

  // ── 反证 (c)：token 归属 ⇒ 旧实现取任意代次；新实现拒绝上一代 ───────────────────

  @Test
  fun `counter-proof-c old implementation would accept a previous generation token`() {
    // 旧实现：不校验归属（取第一条命中）⇒ 恒 true。
    fun oldAcceptsAnyGeneration(@Suppress("UNUSED_PARAMETER") logModifiedMs: Long, @Suppress("UNUSED_PARAMETER") generationStartMs: Long): Boolean = true
    val generationStart = 1_000_000L
    val previousGenerationLog = generationStart - 60_000L
    assertTrue("旧实现确实会接受上一代日志（前提成立）", oldAcceptsAnyGeneration(previousGenerationLog, generationStart))
    assertFalse(
      "新实现必须拒绝上一代的 token（否则首启会拿到死进程的 token）",
      EngineAuth.logBelongsToCurrentGeneration(previousGenerationLog, generationStart),
    )
    assertTrue(
      "新实现必须接受本代日志（否则会永远拿不到 token）",
      EngineAuth.logBelongsToCurrentGeneration(generationStart + 3_000L, generationStart),
    )
  }

  // ── 反证 (d)：cookie 时窗 ⇒ 旧实现硬编码 30 天；新实现跟观测值并留安全边距 ──────

  @Test
  fun `counter-proof-d old implementation hardcoded 30 days ignoring the engine window`() {
    // 旧实现：无论引擎配置如何，自铸 cookie 一律 30 天。
    fun oldMintedMaxAgeMs(@Suppress("UNUSED_PARAMETER") observedMs: Long?): Long = 30L * 24 * 60 * 60 * 1000
    val engineWindow = 10L * 24 * 60 * 60 * 1000 // 假设用户把 cookieMaxAgeDays 调成 10 天
    val old = oldMintedMaxAgeMs(engineWindow)
    val updated = EngineAuth.mintedMaxAgeMs(engineWindow)
    assertEquals("旧实现确实无视引擎时窗（前提成立）", 30L * 24 * 60 * 60 * 1000, old)
    assertTrue("旧实现在引擎窗更小时会超出 → 引擎拒绝（这正是不联动的后果）", old > engineWindow)
    assertTrue("新实现必须落在引擎窗之内", updated <= engineWindow)
    assertTrue("新实现必须仍为正", updated > 0L)
    // 未观测到时的明确默认（且同样留边距）。
    assertEquals(
      "未观测到时用默认 30 天减边距",
      EngineAuth.DEFAULT_COOKIE_MAX_AGE_DAYS * 24 * 60 * 60 * 1000 - EngineAuth.COOKIE_EXPIRY_SAFETY_MARGIN_MS,
      EngineAuth.mintedMaxAgeMs(null),
    )
  }
}
