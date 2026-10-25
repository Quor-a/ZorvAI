package com.ai.assistance.quro.dshmobile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.14.10 审计 §6.1 落地批次第 8 项：spawn 前串行前置的**分段耗时探针**。
 *
 * 审计原文要求「先把各步耗时打出来，再决定是否并行化」。本文件锁三件事：
 *  ① 探针真能量出每一步（注入时钟，不 sleep）；
 *  ② 探针**不改变顺序语义**（记录顺序 = 调用顺序）；
 *  ③ 探针**不得成为故障源**：动作抛异常时耗时照样落账、异常照样向上抛。
 *
 * 另外锁住「接线」：生产路径五步必须都包在 step() 里，且顺序与旧实现逐条一致。
 */
class EngineStartPreludeProbeTest {

  /** 注入式时钟：每次读数前进固定量，避免任何真实等待。 */
  private class FakeClock(private var value: Long = 1_000L) {
    fun now(): Long = value
    fun advance(ms: Long) { value += ms }
  }

  @Test
  fun eachStepIsMeasuredIndependently() {
    val clock = FakeClock()
    val probe = EngineStartPreludeProbe { clock.now() }
    probe.step("a") { clock.advance(7) }
    probe.step("b") { clock.advance(13) }
    probe.step("c") { clock.advance(0) }
    assertEquals(listOf("a" to 7L, "b" to 13L, "c" to 0L), probe.recorded.map { it.name to it.durMs })
  }

  @Test
  fun stepsAreRecordedInCallOrder() {
    val clock = FakeClock()
    val probe = EngineStartPreludeProbe { clock.now() }
    val order = listOf("repair-settings-seed", "retire-reseeded-factory-settings", "repair-profile-patch")
    for (name in order) probe.step(name) { clock.advance(1) }
    assertEquals("探针不得重排步骤（顺序即语义）", order, probe.recorded.map { it.name })
  }

  @Test
  fun theReportLineCarriesEveryStepAndTheTotal() {
    val clock = FakeClock()
    val probe = EngineStartPreludeProbe { clock.now() }
    probe.step("repair-settings-seed") { clock.advance(3) }
    probe.step("apply-runtime-patches") { clock.advance(5) }
    val line = probe.reportLine()
    assertTrue("必须有统一前缀便于 grep：" + line, line.startsWith("perf prelude"))
    assertTrue(line.contains("step=repair-settings-seed durMs=3"))
    assertTrue(line.contains("step=apply-runtime-patches durMs=5"))
    assertTrue("汇总必须等于各段之和", line.contains("prelude-total-durMs=8"))
    assertEquals("单行（日志一行一条，便于设备侧 grep）", 1, line.lines().size)
  }

  /** 探针不得成为故障源：动作抛异常时，耗时仍记账，异常仍向上抛。 */
  @Test
  fun aThrowingStepStillRecordsItsDurationAndRethrows() {
    val clock = FakeClock()
    val probe = EngineStartPreludeProbe { clock.now() }
    var thrown: Throwable? = null
    try {
      probe.step("explodes") {
        clock.advance(9)
        throw IllegalStateException("boom")
      }
    } catch (t: Throwable) {
      thrown = t
    }
    assertTrue("异常必须原样抛出（不得被探针吞掉）", thrown is IllegalStateException)
    assertEquals("抛异常的那一段也要记账", listOf("explodes" to 9L), probe.recorded.map { it.name to it.durMs })
  }

  /** 时钟回退（注入错误 / 基准被重置）不得产出负耗时。 */
  @Test
  fun aBackwardsClockNeverYieldsANegativeDuration() {
    var value = 5_000L
    val probe = EngineStartPreludeProbe { value }
    probe.step("backwards") { value = 1_000L }
    assertEquals("倒序读数必须记 0，不得记负值", 0L, probe.recorded.single().durMs)
  }

  /** 空探针不落行（避免没有前置时打一行空壳噪音）。 */
  @Test
  fun anEmptyProbeReportsNothing() {
    val probe = EngineStartPreludeProbe { 0L }
    assertTrue(probe.recorded.isEmpty())
    assertEquals("perf prelude prelude-total-durMs=0", probe.reportLine())
  }

  // ── 接线层：五步都必须包在 step() 里，且顺序与旧实现一致 ────────────────────

  private fun source(name: String): String = listOf(
    File("src/main/java/com/ai/assistance/quro/dshmobile", name),
    File("app/src/main/java/com/ai/assistance/quro/dshmobile", name),
  ).firstOrNull { it.isFile }
    ?.readText()
    ?: throw AssertionError("找不到壳侧源码 " + name + "（工作目录 = " + File(".").absolutePath + "）")

  /**
   * 五步的**顺序契约**：与修复前逐条一致（并行化会改顺序，正是本修复刻意不动的地方）。
   */
  @Test
  fun theFiveStepsAreInstrumentedInTheOriginalOrder() {
    val code = source("EngineManager.kt")
    val prelude = code.substringAfter("val preludeProbe = EngineStartPreludeProbe()")
      .substringBefore("val args = arrayOf(")
    val expected = listOf(
      "repair-settings-seed" to "repairSettingsSeed()",
      "retire-reseeded-factory-settings" to "SnapshotUserData.retireReseededFactorySettings(",
      "repair-profile-patch" to "repairProfilePatch()",
      "provision-public-repo" to "provisionPublicRepo(",
      "apply-runtime-patches" to "applyRuntimePatches()",
    )
    var lastAt = -1
    for ((stepName, call) in expected) {
      val stepAt = prelude.indexOf("\"" + stepName + "\"")
      assertTrue("步骤必须被 step() 包住：" + stepName, stepAt >= 0)
      assertTrue("步骤顺序必须与旧实现一致：" + stepName, stepAt > lastAt)
      lastAt = stepAt
      val bodyAt = prelude.indexOf(call, stepAt)
      assertTrue("step() 里必须调用真正的动作：" + stepName, bodyAt > stepAt)
    }
    assertTrue("必须落一行汇总", prelude.contains("preludeProbe.report(TAG, context)"))
    assertFalse("探针不得吞掉动作（不得出现 try/catch 包住 step 的形态）", prelude.contains("catch (_: Throwable) { }"))
    // 反证「只是加了函数但没接上」：五步之外不得还有裸调用。
    for ((_, call) in expected) {
      assertEquals("动作只能在 step() 里出现一次：" + call, 1, Regex(Regex.escape(call)).findAll(prelude).count())
    }
  }

  /**
   * 探针必须走**无条件可读**的落盘通道（0.14.10 设备实测缺口）。
   *
   * 缺陷形态：探针原本只走 `LogCollector.log`，而那条通道有两道门——`appContext` 未初始化
   * 时直接 return，且 `start()` 受开发者日志开关（`DevLogPrefs`，默认关）门住。默认安装上
   * `files/log/` 目录不存在，探针写出去等于没写，设备侧取不到「各步耗时」，审计 §6.1 第 8 项
   * 的「先打出来再决定」就落不了地。
   *
   * 反证形态：把 report() 里的 writeBootDiag 那行删掉（退回只写开发者日志），本用例即红。
   */
  @Test
  fun theProbeReportsThroughAnUnconditionalChannel() {
    val code = source("EngineManager.kt")
    val report = code.substringAfter("fun report(tag: String, context: Context? = null)")
      .substringBefore("LogCollector.log(tag, line)")
    assertTrue(
      "汇总必须无条件写 boot-diag（默认安装上开发者日志通道是关的）",
      report.contains("LogCollector.writeBootDiag("),
    )
    assertTrue("写 boot-diag 必须带来源标识，便于设备侧 grep", report.contains("\"perf-prelude\""))
    assertTrue("开发者日志通道仍要保留（开了开关的场景）", code.contains("LogCollector.log(tag, line)"))
    // 接线侧：调用点必须把 context 传进来，否则上面那条等于没生效。
    assertTrue(
      "调用点必须传 context，否则无条件通道根本走不到",
      code.contains("preludeProbe.report(TAG, context)"),
    )
    // asset-patch 探针同口径（同一缺口的两处）。
    assertTrue(
      "asset-patch 探针也必须走无条件通道",
      code.contains("LogCollector.writeBootDiag(context, \"perf-asset-patch\", perfLine)"),
    )
  }

  /** 探针必须用单调时钟（墙上时钟量毫秒级耗时会受校时影响，见审计 §6.7）。 */
  @Test
  fun theProductionProbeUsesTheMonotonicClock() {
    val code = source("EngineManager.kt")
    val probe = code.substringAfter("internal class EngineStartPreludeProbe(")
      .substringBefore("internal fun sha256Hex(")
    assertTrue("必须用 elapsedRealtime", probe.contains("android.os.SystemClock.elapsedRealtime()"))
    assertFalse("不得用墙上时钟做差", probe.contains("System.currentTimeMillis()"))
  }
}