package com.ai.assistance.quro.dshmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * issue #258 反证用例：无障碍 nx/ny 归一化的基准必须是**整屏**，不得是**当前窗口**。
 *
 * 缺陷复现数据取自 issue 实测（HUAWEI BTK-W00，物理 2200x1440；DSH 浮窗
 * bounds=(1438,106) 733x1389；`nx=0.712` 实际落到 522 = 0.712*733，而不是 0.712*2200=1566）。
 *
 * **判据要能反证**（本仓铁律）：[multiWindowMustUseMaximumWindowBoundsNotCurrentWindow] 用真实
 * 缺陷数值构造夹具，若有人把基准改回「当前窗口」（即把 [CoordBasisPolicy.resolve] 的优先级
 * 调回去、或让 API 30+ 分支取 `currentWindowMetrics`），该用例立刻判红——
 * 它同时断言「必须等于整屏」与「必须不等于窗口」，后者是真正的反证方向。
 *
 * 纯 JVM（无 Robolectric）：判定面 [CoordBasisPolicy.resolve] 是纯函数，Android API 只出现在
 * 取数面 [CoordBasisPolicy.fromContext]（本类不触达）。
 */
class CoordBasisPolicyTest {

  /** 缺陷实测值：窗口/整屏两组尺寸在**同一次读取**里同时给出（多窗口设备的真实形态）。 */
  private val issueMaxWindow = 2200 to 1440
  private val issueCurrentWindow = 733 to 1389

  /**
   * 主修判据：多窗口（API 30+，整屏与窗口都读得到）必须选**整屏最大窗口**。
   *
   * 反证方向是把基准改回窗口尺寸：那样 width 会是 733、`nx=0.712` 会换算成 522（缺陷值），
   * 两条断言都会红。
   */
  @Test
  fun multiWindowMustUseMaximumWindowBoundsNotCurrentWindow() {
    val basis = CoordBasisPolicy.resolve(
      CoordBasisPolicy.Reading(
        sdkInt = 30,
        maxWindow = issueMaxWindow,
        realMetrics = issueMaxWindow,
        currentWindow = issueCurrentWindow,
      ),
    )
    assertEquals(CoordBasisPolicy.WIRE_SCREEN_MAX, basis.wire)
    assertEquals(2200, basis.width)
    assertEquals(1440, basis.height)
    assertTrue("整屏基准必须被标为屏幕范围", basis.isScreenScope)

    // 反证：三个「不等于窗口尺寸」的断言——基准一旦回退到当前窗口，这里必红。
    assertNotEquals("基准不得是窗口宽度 733（issue #258 的根本缺陷）", 733, basis.width)
    assertNotEquals("基准不得是窗口高度 1389", 1389, basis.height)

    // 端到端换算复算：issue 里 nx=0.712 的期望落点是 x≈1566（DSH 浮窗内），不是 522。
    assertEquals(1566, Math.round(0.712 * basis.width))
    assertNotEquals("0.712 若按窗口宽换算会得到 522（缺陷实测值）", 522, Math.round(0.712 * basis.width))
  }

  /**
   * minSdk 26 兼容分支：API 26-29 **没有** `getMaximumWindowMetrics`（API 30 新增），
   * 必须走 `Display.getRealMetrics()`（整屏），不得因为该 API 缺失而退回当前窗口。
   */
  @Test
  fun api26To29UsesRealMetricsBecauseMaximumWindowMetricsDoesNotExist() {
    val basis = CoordBasisPolicy.resolve(
      CoordBasisPolicy.Reading(
        sdkInt = 26,
        maxWindow = null,
        realMetrics = issueMaxWindow,
        currentWindow = issueCurrentWindow,
      ),
    )
    assertEquals(CoordBasisPolicy.WIRE_SCREEN_REAL, basis.wire)
    assertEquals(2200, basis.width)
    assertEquals(1440, basis.height)
    assertTrue(basis.isScreenScope)
    assertNotEquals(733, basis.width)
  }

  /**
   * API 门槛必须真的挡在 30：即便有人在低版本上塞进一个「最大窗口」读数（现实中取不到），
   * 也不得采用——否则 26-29 的兼容分支形同虚设。
   */
  @Test
  fun maximumWindowMetricsIsNeverAdoptedBelowApi30() {
    val basis = CoordBasisPolicy.resolve(
      CoordBasisPolicy.Reading(
        sdkInt = 29,
        maxWindow = 1080 to 2340,
        realMetrics = issueMaxWindow,
        currentWindow = issueCurrentWindow,
      ),
    )
    assertNotEquals(CoordBasisPolicy.WIRE_SCREEN_MAX, basis.wire)
    assertEquals(CoordBasisPolicy.WIRE_SCREEN_REAL, basis.wire)
    assertEquals(2200, basis.width)
  }

  /**
   * 最后兜底：整屏尺寸两种途径都取不到时才用当前窗口，且**必须带 `window-current` 标记**——
   * 回显里看得出「本机没给出整屏尺寸」，这是缺陷基准不被静默使用的唯一保证。
   */
  @Test
  fun currentWindowIsTheLastResortAndIsLabelledAsTheDefectBasis() {
    val basis = CoordBasisPolicy.resolve(
      CoordBasisPolicy.Reading(
        sdkInt = 30,
        maxWindow = null,
        realMetrics = null,
        currentWindow = issueCurrentWindow,
      ),
    )
    assertEquals(CoordBasisPolicy.WIRE_WINDOW_CURRENT, basis.wire)
    assertEquals(733, basis.width)
    assertFalse("窗口基准不得被标为屏幕范围", basis.isScreenScope)
  }

  /** 零/负尺寸不算有效读数（不得把 0x0 当整屏基准，也不得在全部无效时谎报整屏）。 */
  @Test
  fun zeroSizedReadingsAreNeverAcceptedAsAscreenBasis() {
    val zeroThenWindow = CoordBasisPolicy.resolve(
      CoordBasisPolicy.Reading(sdkInt = 30, maxWindow = 0 to 0, realMetrics = 0 to 0, currentWindow = issueCurrentWindow),
    )
    assertEquals(CoordBasisPolicy.WIRE_WINDOW_CURRENT, zeroThenWindow.wire)
    assertEquals(733, zeroThenWindow.width)

    val nothing = CoordBasisPolicy.resolve(
      CoordBasisPolicy.Reading(sdkInt = 30, maxWindow = null, realMetrics = null, currentWindow = null),
    )
    assertEquals("全部读数缺失时必须 fail-closed 到窗口标记，不得谎报整屏", CoordBasisPolicy.WIRE_WINDOW_CURRENT, nothing.wire)
    assertFalse(nothing.isScreenScope)
    assertEquals(0, nothing.width)
  }

  /** 全屏形态（窗口 == 屏幕）下基准不变——修复不得改变全屏行为（回归对照）。 */
  @Test
  fun fullscreenWindowEqualsScreenSoTheBasisIsUnchanged() {
    val basis = CoordBasisPolicy.resolve(
      CoordBasisPolicy.Reading(sdkInt = 30, maxWindow = 900 to 1600, realMetrics = 900 to 1600, currentWindow = 900 to 1600),
    )
    assertEquals(CoordBasisPolicy.WIRE_SCREEN_MAX, basis.wire)
    assertEquals(900, basis.width)
    assertEquals(1600, basis.height)
  }
}

/**
 * 单一真源接线契约（issue #258 实现要求）：归一化的换算点只能有一处。
 *
 * 为什么用源码契约而不是行为测试：执行侧 [DeviceControlService] 是 AccessibilityService，
 * 纯 JVM（无 Robolectric）无法实例化；而这里要防的缺陷形态是「某条路径漏改、又各自写一份换算」
 * ——正是最适合源码断言的形态（与仓内 `CallSiteContractTest` 同一思路）。
 */
class CoordBasisWiringContractTest {

  private fun source(name: String): String {
    val candidates = listOf(
      File("src/main/java/com/ai/assistance/quro/dshmobile", name),
      File("app/src/main/java/com/ai/assistance/quro/dshmobile", name),
    )
    val f = candidates.firstOrNull { it.isFile }
      ?: throw AssertionError("找不到壳侧源码 " + name + "（工作目录 = " + File(".").absolutePath + "）")
    return f.readText()
  }

  /**
   * 断言面必须**剥掉注释**：本类刚踩过——KDoc 里描述修复前实现时会写出 `currentWindowMetrics`
   * 字样，用整文件原文断言会把「注释提到缺陷 API」误判成「代码用了缺陷 API」（判据自伤）。
   * 与仓内 `CallSiteContractTest` 的 `codeOnly` 同一口径。
   */
  private val service = codeOnly(source("DeviceControlService.kt"))

  /** 反证核心：执行侧**不得**再出现「当前窗口」这一缺陷基准的取数调用。 */
  @Test
  fun executionSideNeverDerivesTheBasisFromCurrentWindowMetrics() {
    assertFalse(
      "issue #258：execution side must not read currentWindowMetrics for the coordinate basis",
      service.contains("currentWindowMetrics"),
    )
    assertFalse("不得回退到会返回窗口尺寸的 Resources 取数面", service.contains("resources.displayMetrics"))
    assertTrue("基准必须经唯一真源解析", service.contains("CoordBasisPolicy.screenBasis(this)"))
  }

  /** 行注释 / 块注释 / KDoc 一律剥除（保留代码行，行号会变但本类只做包含性断言）。 */
  private fun codeOnly(src: String): String = src
    .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
    .lineSequence()
    .map { it.substringBefore("//") }
    .joinToString("\n")

  /** 四条消费路径必须共用同一真源：click / longClick 显式经 coordBasis()，快照与滚动经 screenSize()。 */
  @Test
  fun everyCoordinateConsumerGoesThroughTheSingleSourceOfTruth() {
    val click = memberBody("private fun handleClick(")
    val longClick = memberBody("private fun handleLongClick(")
    assertTrue("click 的归一化必须用 coordBasis()", click.contains("coordBasis()"))
    assertTrue("longClick 的归一化必须用 coordBasis()", longClick.contains("coordBasis()"))
    // 归一化分支不得再出现任何本地的 metrics 变量（否则又是一份独立换算）。
    assertFalse("click 的 nx/ny 分支不得再自取尺寸", click.contains("screenSize()"))
    assertFalse("longClick 的 nx/ny 分支不得再自取尺寸", longClick.contains("screenSize()"))
    assertTrue("屏幕尺寸唯一入口必须委托真源", memberBody("private fun screenSize(").contains("coordBasis()"))
  }

  /** 回显基准：两条归一化动作的返回值都必须带 basis/basisWidth/basisHeight。 */
  @Test
  fun normalizedActionsEchoTheBasisTheyUsed() {
    val withBasis = memberBody("private fun withBasis(")
    assertTrue(withBasis.contains("\"basis\""))
    assertTrue(withBasis.contains("\"basisWidth\""))
    assertTrue(withBasis.contains("\"basisHeight\""))
    assertTrue("click 必须回显基准", memberBody("private fun handleClick(").contains("withBasis("))
    assertTrue("longClick 必须回显基准", memberBody("private fun handleLongClick(").contains("withBasis("))
  }

  /** 按签名取**函数体**（整文件正则跨成员会误判，见 `BootDiagnosticsContractTest` 的教训）。 */
  private fun memberBody(signature: String): String {
    val idx = service.indexOf(signature)
    if (idx < 0) throw AssertionError("找不到成员签名 " + signature)
    val rest = service.substring(idx + signature.length)
    val cut = listOf("\n  private fun ", "\n  internal fun ", "\n  override fun ", "\n  fun ")
      .map { rest.indexOf(it) }
      .filter { it >= 0 }
      .minOrNull() ?: rest.length
    return rest.substring(0, cut)
  }
}
