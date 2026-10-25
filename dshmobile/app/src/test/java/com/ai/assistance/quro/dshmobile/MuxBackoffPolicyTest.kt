package com.ai.assistance.quro.dshmobile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.14.10 审计 §6.6 回归：mux 重连的退避与日志节流。
 *
 * 缺陷形态（审计 §6.6 + 新机 20:46:10 / 11 / 13 连续三次 ECONNREFUSED）：
 *  - 退避从 1 秒起步、且只在 sleep **之后**才翻倍，而失败本身的耗时（connect 超时 3s + 握手读）
 *    从不计入间隔 —— 于是「退避看起来没拉开」，两次尝试的真实间隔被失败耗时主导；
 *  - 冷启动窗口（spawn→LISTEN 实测 6.4-9.1 秒）里因此白建 socket、白打日志；
 *  - 注释写着「首败 + 每分钟一条节流」，代码里却是**每次失败都 Log.w** —— 承诺从未落地。
 *
 * 修法的三条判据都与时间无关（纯函数），所以本文件全部是**数值反证**，不 sleep、不碰真实时钟。
 */
class MuxBackoffPolicyTest {

  // ── ① 退避按失败次数算，并从 2 秒起步 ────────────────────────────────────────

  /**
   * **核心反证一**：首败的等待必须是 2 秒量级。
   *
   * 旧实现的首次等待是 1000ms（`var backoff = 1000L`，catch 里只打日志、sleep 完才翻倍），
   * 在「引擎还没起、LISTEN 实测 6.4-9.1 秒」的窗口里就是 7 次以上的空转连接。
   */
  @Test
  fun theFirstRetryWaitsSecondsNotOneSecond() {
    assertEquals("首败必须等 2 秒（旧实现是 1 秒，属审计点名的冷启动噪音）", 2_000L, muxBackoffMs(1))
    assertTrue(
      "起步间隔必须落在审计建议的 2-5 秒区间：实际 " + muxBackoffMs(1),
      muxBackoffMs(1) in 2_000L..5_000L,
    )
    assertTrue("不得退回 1 秒以内（那正是旧行为）", muxBackoffMs(1) > 1_000L)
  }

  /** 序列：2/4/8/10/10…，单调不减、封顶 10 秒（上限沿用原值，不改变最坏恢复时间）。 */
  @Test
  fun backoffGrowsMonotonicallyAndStopsAtTheCap() {
    assertEquals(2_000L, muxBackoffMs(1))
    assertEquals(4_000L, muxBackoffMs(2))
    assertEquals(8_000L, muxBackoffMs(3))
    assertEquals(10_000L, muxBackoffMs(4))
    assertEquals("封顶后不再增长", 10_000L, muxBackoffMs(5))
    assertEquals(10_000L, muxBackoffMs(50))
    var prev = 0L
    for (failures in 1..40) {
      val ms = muxBackoffMs(failures)
      assertTrue("失败 " + failures + " 次的间隔不得小于上一次：" + prev + " -> " + ms, ms >= prev)
      assertTrue("失败 " + failures + " 次不得越过上限：" + ms, ms <= MUX_BACKOFF_CAP_MS)
      prev = ms
    }
  }

  /**
   * **核心反证二：退避是「失败次数」的函数，不是「上次尝试时间」的函数。**
   *
   * 同一个失败次数无论被问几次、在什么时候问，结果都相同——函数签名里根本没有时间参数。
   * 旧实现正是在这一点上出错的：它的间隔取决于「上一轮 sleep 攒到哪一档」，与失败耗时耦合。
   */
  @Test
  fun backoffIsAFunctionOfFailureCountOnly() {
    for (failures in 0..20) {
      val first = muxBackoffMs(failures)
      val second = muxBackoffMs(failures)
      assertEquals("失败 " + failures + " 次必须得到稳定间隔（与调用时机无关）", first, second)
    }
    // 反证：不同失败次数**确实**会给出不同的间隔（否则本判据退化成一个常量，谈不上「按次数」）。
    assertTrue("1 次与 3 次的间隔必须不同", muxBackoffMs(1) != muxBackoffMs(3))
    assertTrue("3 次之后才算到达长间隔档", muxBackoffMs(3) > muxBackoffMs(1))
  }

  /**
   * 正常收流（close 帧）⇒ 1 秒快速重连，**不**按「失败次数」退避。
   *
   * 这是审计原文点名「保持不变」的那条语义：服务端主动收流说明引擎是健康的。
   */
  @Test
  fun aGracefulServerCloseKeepsTheFastReconnect() {
    assertEquals("正常收流固定 1 秒重连", 1_000L, muxReconnectDelayMs(0, gracefulClose = true))
    // 即便之前的失败计数还没归零，正常收流也必须立刻回到快速档（不得被旧计数拖慢）。
    assertEquals(1_000L, muxReconnectDelayMs(7, gracefulClose = true))
    assertTrue(
      "快速重连必须严格快于「引擎未就绪」的起步档：" + MUX_FAST_RECONNECT_MS,
      muxReconnectDelayMs(0, gracefulClose = true) < muxBackoffMs(1),
    )
    // 反证：非正常收流仍走失败次数档（否则这条判据退化成「恒 1 秒」）。
    assertEquals(muxBackoffMs(3), muxReconnectDelayMs(3, gracefulClose = false))
    assertEquals(2_000L, muxReconnectDelayMs(1, gracefulClose = false))
  }

  /** 非正失败数（刚连上/刚被正常 close 重置）⇒ **不等待**，快速重连。 */
  @Test
  fun aHealthyConnectionReconnectsImmediately() {
    assertEquals("0 = 没失败过，不得退避", 0L, muxBackoffMs(0))
    assertEquals("负数按 0 处理（不得产出负等待）", 0L, muxBackoffMs(-1))
    assertEquals(0L, muxBackoffMs(Int.MIN_VALUE))
  }

  // ── ② 日志节流：首败一条、之后每分钟一条 ──────────────────────────────────────

  /**
   * **核心反证三**：第 2 次失败（距首败远不到一分钟）必须**静默**。
   *
   * 旧实现每次失败都 Log.w —— 引擎冷启动窗口里连打 7 行以上，审计 §6.6 点名这是噪音。
   */
  @Test
  fun onlyTheFirstFailureIsLoggedUntilAMinutePasses() {
    assertTrue("首败必须打（否则出问题时日志里一行都没有）", muxShouldLogFailure(1, 0L, 1_000L))
    assertFalse(
      "次败距首败 1 秒：必须静默（旧实现每次都打）",
      muxShouldLogFailure(2, 1_000L, 2_000L),
    )
    assertFalse("第 3 次失败仍在一分钟内：静默", muxShouldLogFailure(3, 1_000L, 3_000L))
    assertFalse("差 1 毫秒到一分钟：仍然静默（边界不得提前放行）", muxShouldLogFailure(9, 1_000L, 60_999L))
    assertTrue("满一分钟必须放行一条", muxShouldLogFailure(10, 1_000L, 61_000L))
    assertTrue("远超一分钟同样放行", muxShouldLogFailure(11, 1_000L, 3_600_000L))
  }

  /** 节流窗口可注入（判据是纯函数），默认值必须是一分钟。 */
  @Test
  fun theThrottleIntervalIsOneMinuteByDefault() {
    assertEquals(60_000L, MUX_FAILURE_LOG_INTERVAL_MS)
    // 基准取「上一次已落日志的时刻」（而不是 0 = 还没打过）。
    assertTrue("满一分钟必须放行", muxShouldLogFailure(2, 1_000L, 61_000L))
    assertFalse("差 1 毫秒必须静默", muxShouldLogFailure(2, 1_000L, 60_999L))
    // 注入更长的窗口：同一读数下必须判静默（证明判据真的用了参数，不是写死）。
    assertFalse(muxShouldLogFailure(2, 1_000L, 61_000L, intervalMs = 120_000L))
    assertTrue(muxShouldLogFailure(2, 1_000L, 121_000L, intervalMs = 120_000L))
  }

  /** 「本次运行还没打过日志」（lastLoggedAt <= 0）时放行，避免单调时钟基线重置后永久静默。 */
  @Test
  fun aResetClockBaseStillLogsOnce() {
    assertTrue(muxShouldLogFailure(5, 0L, 10L))
    assertTrue("负数时间戳同样按「还没打过」处理", muxShouldLogFailure(5, -1L, 10L))
  }

  // ── ③ 接线层：缺陷原句不得复活（撤掉修复即红） ────────────────────────────────

  private fun source(name: String): String = listOf(
    File("src/main/java/com/ai/assistance/quro/dshmobile", name),
    File("app/src/main/java/com/ai/assistance/quro/dshmobile", name),
  ).firstOrNull { it.isFile }
    ?.readText()
    ?: throw AssertionError("找不到壳侧源码 " + name + "（工作目录 = " + File(".").absolutePath + "）")

  private fun codeOnly(src: String): String = src.lineSequence()
    .filterNot {
      val t = it.trimStart()
      t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
    }
    .joinToString("\n")

  /**
   * 锁三件事，且只锁这三件（不做过宽的源码形态断言）：
   *  - 旧退避原句 `backoff = (backoff * 2)...` 不得复活；
   *  - sleep 的时长必须来自 [muxBackoffMs]（按失败次数），不是本地累加变量；
   *  - Log.w 必须落在 [muxShouldLogFailure] 的判断之内（否则节流又变回空话）。
   */
  @Test
  fun theReconnectLoopUsesTheFailureCountPolicyAndThrottlesItsLogging() {
    val code = codeOnly(source("MuxClient.kt"))
    val loop = code.substringAfter("private fun loop()").substringBefore("private fun connectAndServe")

    assertFalse(
      "旧的本地翻倍变量不得复活（退避必须按失败次数算）",
      loop.contains("backoff = (backoff * 2)"),
    )
    assertTrue("等待时长必须来自 muxBackoffMs(failures)", loop.contains("muxBackoffMs(failures)"))
    assertTrue("退避必须喂给 sleep", Regex("Thread\\.sleep\\(delay\\)").containsMatchIn(loop))
    assertTrue("失败计数必须在连上后归零（正常 close 帧 → 快速重连）", loop.contains("failures = 0"))
    assertTrue("日志判定必须经 muxShouldLogFailure", loop.contains("muxShouldLogFailure("))
    // Log.w 必须**包在**节流判定的 if 体内（撤掉 if 即红），而不是同分支里排在它后面。
    assertTrue(
      "Log.w 必须落在 muxShouldLogFailure 的 if 体内",
      Regex("if \\(muxShouldLogFailure\\([^)]*\\)\\) \\{[\\s\\S]{0,300}?Log\\.w\\(TAG").containsMatchIn(loop),
    )
    // 失败分支里不得出现「catch 之后立刻 Log.w」的旧形态：第一句必须是 failures++。
    val failCatch = loop.substringAfter("} catch (e: Exception) {").substringBefore("}")
    assertTrue("失败分支的第一件事必须是累计失败次数", failCatch.trimStart().startsWith("if (!running) return\n        failures++"))
    // 失败分支里若有 Log.w，它必须排在节流判定**之后**（撤掉节流即红）。
    val logInCatch = failCatch.indexOf("Log.w(")
    if (logInCatch >= 0) {
      val throttleInCatch = failCatch.indexOf("muxShouldLogFailure(")
      assertTrue(
        "失败分支的 Log.w 必须晚于节流判定（旧形态是 catch 里直接打）",
        throttleInCatch in 0 until logInCatch,
      )
    }
    // 正常收流走快速重连且不计失败（审计原文要求：这一条不变）。
    assertTrue("服务端正常收流必须单独判定", loop.contains("MuxServerClosedException"))
    assertTrue("正常收流必须归零失败计数", loop.contains("failures = 0"))
    assertTrue("等待时长必须经 muxReconnectDelayMs(failures, gracefulClose)", loop.contains("muxReconnectDelayMs(failures, gracefulClose)"))
    assertTrue(
      "正常收流后的间隔必须严格小于「引擎未就绪」的起步档",
      MUX_FAST_RECONNECT_MS < MUX_BACKOFF_FIRST_MS,
    )
  }
}
