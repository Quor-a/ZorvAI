package com.ai.assistance.quro.dshmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D6（0.14.1）报告栏「上拉/下拉 + 可滚动正文」的行为回归。
 *
 * 设备实报：「长按查看详情…无法在不改变窗口大小的情况下滚动查看输出」。
 * 代码层事实（详档 §4.2 三条，均可离线确证）：
 *  1. 拖拽手柄是**纯装饰**（原注释自述「不做手势」）——需求里的「上拉/下拉栏」从未落地；
 *  2. 窗口高度在 **attach 前**量一次就写死（`measure(AT_MOST)` → `lp.height = measuredHeight`），
 *     没有下限，也不随 attach 后的重排纠正；
 *  3. 正文被构造性限制成 3 行，而 summary 经 120 字硬截断 → **滚动区间恒为 0**。
 * 第 3 条是本次的实质：**不是滚动坏了，是没有可滚的内容**。故修法分两半：
 * 插件侧把该轮可见正文有界落盘（D6 的投影面，见 notify-projection），壳侧把它渲染进可滚动区。
 *
 * 纯 JVM（无 Robolectric）：[reportBodyText] / [reportBarInitialHeight] /
 * [reportBarHeightAfterDrag] / [reportLines] 都是顶层纯函数。
 */
class OverlayReportBarTest {

  private fun reportEntry(body: String = "", summary: String = "", text: String = "") = NotifyEntry(
    kind = "report",
    outcome = "completed",
    outcomeLabel = "已完成",
    summary = summary,
    body = body,
    text = text,
  )

  // ── 正文内容：可滚动区必须有真东西（缺陷本体）────────────────────────

  /** 核心：有 body 时必须渲染全文（而不是只有 120 字摘要）——这是「可滚」的前提。 */
  @Test
  fun theScrollableBodyCarriesTheFullTextNotJustTheSummary() {
    val full = (1..200).joinToString("\n") { "第 $it 行：这一行足够长，用来把内容高度顶过视口" }
    val entry = reportEntry(body = full, summary = full.replace("\n", " ").take(120))
    assertEquals("正文必须是全文，且保留换行", full, reportBodyText(entry))
    assertTrue("全文必须显著长于摘要（否则滚动区间仍为 0）", reportBodyText(entry).length > 120)
  }

  /** 兜底：旧条目没有 body 字段时必须回落 summary/text，报告栏不得因此空掉。 */
  @Test
  fun missingBodyFallsBackToSummaryThenText() {
    assertEquals("摘要兜底", reportBodyText(reportEntry(summary = "摘要兜底")))
    assertEquals("正文兜底", reportBodyText(reportEntry(text = "正文兜底")))
    assertEquals("", reportBodyText(reportEntry()))
    assertEquals("", reportBodyText(null))
  }

  /** body 只有空白时按「没有正文」处理（不得渲染一片空白区）。 */
  @Test
  fun aBlankBodyIsTreatedAsAbsent() {
    assertEquals("摘要兜底", reportBodyText(reportEntry(body = "   \n  ", summary = "摘要兜底")))
  }

  /** 元信息三行口径不得回归（正文是独立区域，不混进 `lines[0]`）。 */
  @Test
  fun theMetaLinesKeepTheirExistingShape() {
    val lines = reportLines(
      NotifyEntry(
        kind = "report",
        outcome = "completed",
        outcomeLabel = "已完成",
        summary = "修好了三处回执",
        body = "全文很长很长…",
        durationMs = 42_000,
        toolCount = 7,
        presentedFiles = listOf("a.kt", "b.kt"),
      ),
    )
    assertEquals(3, lines.size)
    assertEquals("已完成 · 修好了三处回执", lines[0])
    assertTrue(lines[1].startsWith("用时 ") && lines[1].contains("工具 ×7"))
    assertEquals("产出：a.kt、b.kt", lines[2])
  }

  // ── 高度：初始按内容取，夹在上下限之间 ────────────────────────────────

  /** 短汇报不得强占 40% 屏高，但也不得小于下限（低于下限连手柄都放不下）。 */
  @Test
  fun aShortReportGetsACompactBarNotTheFullCap() {
    assertEquals("内容不足下限时必须用下限（手柄要放得下）", 140, reportBarInitialHeight(80, 140, 1200))
    assertEquals("内容在区间内时按内容取", 400, reportBarInitialHeight(400, 140, 1200))
    assertEquals("内容超出上限时必须夹到上限（抽屉形态）", 1200, reportBarInitialHeight(9000, 140, 1200))
  }

  /** 上下限反了也不能出现非法高度（防御式：cap < floor 时以 floor 为准）。 */
  @Test
  fun theBoundsAreNormalisedWhenInverted() {
    assertEquals(200, reportBarInitialHeight(50, 200, 100))
    assertEquals(1, reportBarInitialHeight(0, 0, 0))
  }

  // ── 高度：拖拽（「上拉/下拉栏」这条需求的可判据形态）────────────────────

  /** 上拉变高、下拉变矮，且都在区间内。 */
  @Test
  fun draggingUpGrowsAndDraggingDownShrinksWithinTheBounds() {
    assertEquals("上拉 200px", 600, reportBarHeightAfterDrag(400, 200, 140, 1200))
    assertEquals("下拉 200px", 200, reportBarHeightAfterDrag(400, -200, 140, 1200))
    assertEquals("越上限只夹取", 1200, reportBarHeightAfterDrag(1100, 500, 140, 1200))
    assertEquals("越下限只夹取", 140, reportBarHeightAfterDrag(200, -5000, 140, 1200))
  }

  /** 拖到头再回拉必须能回原位（越界只夹取、不重设原点）。 */
  @Test
  fun draggingPastTheLimitAndBackRestoresTheIntermediateHeight() {
    // 手指从 400 上拉到 1400（越上限 1200）后回拉到 +300：结果按 startHeight+300 计算，
    // 不因中途越界而改变原点——否则用户会觉得「拖到头以后回拉没反应」。
    val start = 400
    assertEquals(1200, reportBarHeightAfterDrag(start, 1000, 140, 1200))
    assertEquals(700, reportBarHeightAfterDrag(start, 300, 140, 1200))
  }

  /** 高度为 0 的拖拽必须仍是合法值（不得出现 0 高窗口）。 */
  @Test
  fun aZeroDeltaStillYieldsALegalHeight() {
    assertTrue(reportBarHeightAfterDrag(0, 0, 140, 1200) >= 140)
  }

  /** 用户观感判据：正文超过视口时，视口高度必须真的小于内容高度（否则「能滚但滚不动」）。 */
  @Test
  fun whenContentOverflowsTheViewportIsGenuinelySmaller() {
    val contentH = 3000
    val barH = reportBarInitialHeight(contentH, 140, 1200)
    assertEquals(1200, barH)
    assertTrue("内容高于上限时窗口取上限，视口因此小于内容 → 有真实滚动区间", contentH > barH)
    assertFalse("这不是「内容不足、无需滚动」的场景", contentH <= barH)
  }
}
