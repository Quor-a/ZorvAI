package com.ai.assistance.quro.dshmobile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.14.10 审计 §6.7 回归：`shell-stall` 的 `stalledMs` 必须用**单调时钟**做差。
 *
 * 缺陷形态（0.14.9 审计 §6.7 + 新机首装首启现场）：
 * ```
 * source=shell-stall ... detail=stalledMs=352525 ... pageReady=false
 * source=page-ready  ... {"readyAt":...,"waitedMs":148}
 * ```
 * 页面在首个 HTTP 之后 148ms 就报了就绪，壳侧却记了 352 秒 stall。
 *
 * 真因：起点 `bootStallStartAt` 与做差都用 [System.currentTimeMillis]（墙上时钟）。新装机在首次
 * 联网校时之前系统时间偏慢，起点因而偏小；随后 NTP 把钟**向前拨**，下一拍做差凭空多出几百秒。
 *
 * 本文件的反证方式是**纯数值**：不 sleep、不读真实时钟，直接给 [bootStallElapsedMs] 喂两个时刻。
 * 旧口径（墙上时钟直接相减）在同样的两次读数下会得到 300 秒以上，新口径只得到真实经过的时间。
 */
class BootStallClockTest {

  /** 现场量级：352 秒 = 40 秒判据窗口的 8.8 倍。 */
  private val observedStalledMs = 352_525L

  /**
   * **核心反证**：起点之后墙上时钟向前跳 300 秒，单调时钟只走了 200ms。
   *
   * 旧口径 `wallNow - wallStart` = 300_200ms（远超 40s 窗口 ⇒ 判成 stall，正是 352 秒假读数的
   * 同型形态）；新口径 [bootStallElapsedMs] 用单调读数，只得到 200ms（远低于窗口 ⇒ 不判 stall）。
   *
   * 断言的是**行为语义**（这个差值会不会越过判据窗口），不是实现细节。
   */
  @Test
  fun aForwardWallClockJumpDoesNotInflateTheStalledDuration() {
    // 起点：墙上时钟与单调时钟的读数（两者基准不同，各自单调性也不同）。
    val wallStart = 1_700_000_000_000L
    val monoStart = 5_000L
    // 一拍之后：系统被校时**向前拨了 300 秒**；真实经过只有 200ms。
    val wallNow = wallStart + 300_000L + 200L
    val monoNow = monoStart + 200L

    val legacyStalledMs = wallNow - wallStart
    val fixedStalledMs = bootStallElapsedMs(monoStart, monoNow)

    assertEquals("旧口径必须能复现量级（否则本反证无效）", 300_200L, legacyStalledMs)
    assertEquals("新口径只报真实经过的时间", 200L, fixedStalledMs)
    assertTrue(
      "旧口径会越过 40s 判据窗口（这才是假 stall 的成因）：" + legacyStalledMs,
      legacyStalledMs >= LogCollector.BOOT_STALL_WINDOW_MS,
    )
    assertFalse(
      "新口径不得越过判据窗口（该拍不该被判成卡住）：" + fixedStalledMs,
      fixedStalledMs >= LogCollector.BOOT_STALL_WINDOW_MS,
    )
  }

  /** 与现场同量级：前跳 352 秒、真实经过 148ms（页面自报的 waitedMs 量级）。 */
  @Test
  fun theFieldReadingOfThreeHundredFiftyTwoSecondsIsExplainedByAJumpOnly() {
    val wallStart = 1_700_000_000_000L
    val monoStart = 60_000L
    val wallNow = wallStart + observedStalledMs
    val monoNow = monoStart + 148L

    assertEquals(
      "现场 352 秒只能由墙钟跳变解释——真实经过是毫秒级",
      observedStalledMs, wallNow - wallStart,
    )
    assertEquals(148L, bootStallElapsedMs(monoStart, monoNow))
    assertFalse(
      "按单调时钟，这一拍根本不到 stall 判据",
      bootStallElapsedMs(monoStart, monoNow) >= LogCollector.BOOT_STALL_WINDOW_MS,
    )
  }

  /** 正常经过：单调时钟的差值就是经过的毫秒数（不是 0、也不是被放大/缩小的值）。 */
  @Test
  fun normalElapsedTimeIsThePlainDifference() {
    assertEquals(0L, bootStallElapsedMs(1_000L, 1_000L))
    assertEquals(1L, bootStallElapsedMs(1_000L, 1_001L))
    assertEquals(5_000L, bootStallElapsedMs(0L, 5_000L))
    assertEquals(40_000L, bootStallElapsedMs(7L, 40_007L))
    assertTrue(
      "窗口边界必须落在判据之内（40s 恰好达标）",
      bootStallElapsedMs(0L, LogCollector.BOOT_STALL_WINDOW_MS) >= LogCollector.BOOT_STALL_WINDOW_MS,
    )
  }

  /** 相等与倒序：恒非负（负的「已经卡了 -300 秒」是另一种假读数）。 */
  @Test
  fun aNonIncreasingClockNeverYieldsANegativeDuration() {
    assertEquals("同一毫秒 ⇒ 0", 0L, bootStallElapsedMs(500L, 500L))
    assertEquals("now 早于起点 ⇒ 0（不得输出负数）", 0L, bootStallElapsedMs(500L, 499L))
    assertEquals(0L, bootStallElapsedMs(0L, -1L))
    for (startedAt in listOf(0L, 1L, 12_345L, Long.MAX_VALUE)) {
      for (now in listOf(-5L, 0L, startedAt, startedAt - 1L)) {
        val ms = bootStallElapsedMs(startedAt, now)
        assertTrue("startedAt=" + startedAt + " now=" + now + " 产出负值：" + ms, ms >= 0L)
      }
    }
  }

  /** 单调基准本身必须是可用的正读数（设备开机后 elapsedRealtime 恒 > 0；这里只锁「能取到值且不变小」）。 */
  @Test
  fun theProductionClockIsMonotonicAndUsable() {
    val first = bootStallNow()
    val second = bootStallNow()
    assertTrue("单调时钟必须能取到非负读数：" + first, first >= 0L)
    assertTrue("同一进程内连续读数不得变小时光倒流：" + first + " -> " + second, second >= first)
    assertEquals(
      "两个实现必须口径一致（同一个时钟）",
      bootStallElapsedMs(first, second),
      bootStallElapsedMs(first, second),
    )
  }

  // ── 接线层：判据本体不得回退成墙上时钟做差（撤掉修复即红） ─────────────────────

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
   * 这条是**本文件对实现形态的唯一约束**，且只约束「做差」这一件事：
   *  - 不得再出现 `System.currentTimeMillis() - bootStallStartAt`（缺陷原句）；
   *  - 判据必须经 [bootStallElapsedMs] 得出；
   *  - 起点与复位都必须取单调时钟 [bootStallNow]（三处：字段初值 / startMonitor / resetBootStall）。
   *
   * 不做「全文不得出现 currentTimeMillis」这种过宽断言——该文件里还有合法的墙上时钟用途
   * （例如事务 journal 的跨进程账龄，见 snapshotTransactionAccountedAgeMs）。
   */
  @Test
  fun theStallJudgementNoLongerSubtractsWallClockReadings() {
    val flow = codeOnly(source("EngineStartFlow.kt"))
    assertFalse(
      "判据不得再用墙上时钟做差（这正是 352 秒假 stall 的成因）",
      flow.contains("System.currentTimeMillis() - bootStallStartAt"),
    )
    assertTrue("stalledMs 必须经 bootStallElapsedMs 得出", flow.contains("bootStallElapsedMs(bootStallStartAt, bootStallNow())"))
    assertEquals(
      "起点/复位三处都必须取单调时钟（字段初值 + startMonitor + resetBootStall）",
      3, Regex("bootStallStartAt = bootStallNow\\(\\)").findAll(flow).count(),
    )
    assertTrue(
      "单调基准必须真的用 elapsedRealtime（不是 uptimeMillis / currentTimeMillis）",
      flow.contains("internal fun bootStallNow(): Long = android.os.SystemClock.elapsedRealtime()"),
    )
  }
}
