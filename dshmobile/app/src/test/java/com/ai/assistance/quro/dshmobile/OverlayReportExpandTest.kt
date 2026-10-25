package com.ai.assistance.quro.dshmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3（0.14.2）报告栏「展开后仍被截断」的修复回归。
 *
 * 用户口径：「详情显示的是 CoT，而且不是可滚动的，**展开后也仍旧被截断**」。
 * 设备实测（16416，屏高 1600、density 2；报告栏 frame=[32,960][868,1600]）：
 *  - 长正文下汇报栏**能滚**，第 260/260 行可达（不是滚动坏了）；
 *  - 但「展开」这个手势**零行程**：拖拽前后 dumpsys 都报 `Requested h=640`。
 * 真因：**打开上限与拖拽天花板共用同一个 40% 屏高**（maxH=640），
 * 而长内容一上来就被 `reportBarInitialHeight` 夹到 640 ⇒ 天花板 == 起点，上拉无处可去。
 *
 * 修法：拆成 `reportBarCompactCap`（打开上限，仍是 40%/300dp 地板）与
 * `reportBarExpandCeil`（拖拽天花板，近全屏）。纯 JVM，无 Robolectric。
 */
class OverlayReportExpandTest {

  // 16416 实测几何：900x1600 @320dpi ⇒ density 2.0
  private val sh16416 = 1600
  private val dp16416 = 2.0f
  // 16384 实测几何：1600x900 @240dpi ⇒ density 1.5
  private val sh16384 = 900
  private val dp16384 = 1.5f

  /** 打开上限仍是「屏高 40%」（竖屏 16416：640px），短汇报不该强占屏幕。 */
  @Test
  fun theOpeningCapStaysAtFortyPercentOnPortrait() {
    assertEquals(640, reportBarCompactCap(sh16416, dp16416))
  }

  /** 横屏地板：1600x900 时 40% = 360px，低于 300dp(=450px) ⇒ 取地板 450px。 */
  @Test
  fun theOpeningCapKeepsItsThreeHundredDpFloorInLandscape() {
    assertEquals(450, reportBarCompactCap(sh16384, dp16384))
  }

  /**
   * **本次修复的核心判据**：上拉天花板必须**严格高于**打开上限，
   * 否则「展开」是零行程（修复前的形态就是两者相等 ⇒ 本条判红）。
   */
  @Test
  fun theExpandCeilingIsStrictlyAboveTheOpeningCap() {
    val compact = reportBarCompactCap(sh16416, dp16416)
    val expand = reportBarExpandCeil(sh16416, dp16416)
    assertTrue("展开天花板($expand) 必须高于打开上限($compact)，否则上拉零行程", expand > compact)
    assertEquals("近全屏：屏高 - 24dp 边距", 1600 - 48, expand)
  }

  /** 横屏同样必须有真实上拉行程（地板也不得把天花板顶成等于上限）。 */
  @Test
  fun theExpandCeilingAlsoExceedsTheCapInLandscape() {
    val compact = reportBarCompactCap(sh16384, dp16384)
    val expand = reportBarExpandCeil(sh16384, dp16384)
    assertTrue("横屏展开天花板($expand) 必须高于打开上限($compact)", expand > compact)
  }

  /**
   * 反证（判别力）：若把两个上限**合回同一个值**（修复前的形态），
   * 「上拉行程 > 0」这条断言必须失败 —— 证明这条判据真的能判红。
   */
  @Test
  fun theFixIsRefutableByReMergingTheTwoCaps() {
    val contentH = 7800 // 260 行长正文（实测样本量级）
    val compact = reportBarCompactCap(sh16416, dp16416)
    val expand = reportBarExpandCeil(sh16416, dp16416)

    // 修复后：长内容开场在 compact，仍有 (expand - compact) 的上拉行程
    val opened = reportBarInitialHeight(contentH, 140 * 2, compact)
    assertEquals("长内容开场即到打开上限", compact, opened)
    val draggedFixed = reportBarHeightAfterDrag(opened, 400, 140 * 2, expand)
    assertTrue("修复后上拉必须真的变高", draggedFixed > opened)

    // 修复前形态（天花板 == 打开上限）：上拉零行程，判据判红
    val draggedBroken = reportBarHeightAfterDrag(opened, 400, 140 * 2, compact)
    assertEquals("修复前：上拉无处可去（这正是缺陷本体）", opened, draggedBroken)
    assertFalse("反证：旧形态不满足「上拉真的变高」", draggedBroken > opened)
  }

  /** 拖到展开天花板后，内容区必须真的比视口高（仍有真实滚动区间）。 */
  @Test
  fun aFullyExpandedBarStillScrollsForLongContent() {
    val contentH = 7800
    val expand = reportBarExpandCeil(sh16416, dp16416)
    assertTrue("展开后视口仍小于内容 ⇒ 有真实滚动区间", contentH > expand)
  }

  /** 天花板不得超出可用屏幕（留 24dp 边距），也不得低于下限（防御式）。 */
  @Test
  fun theExpandCeilingStaysInsideTheScreenAndAboveTheFloor() {
    val expand = reportBarExpandCeil(sh16416, dp16416)
    assertTrue("不得高出屏幕", expand <= sh16416)
    assertTrue("必须远高于下限", expand > 140 * 2)
    assertTrue("极端小屏也不得出现非正高度", reportBarExpandCeil(1, 2.0f) >= 1)
  }
}
