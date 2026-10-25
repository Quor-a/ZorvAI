package com.ai.assistance.quro.dshmobile

import com.ai.assistance.quro.dshmobile.DeviceControlService.WindowPick
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 无障碍窗口选择回归（坑 136，0.14.0 模拟器实锤）。
 *
 * 缺陷形态：虚拟屏上 android_ui_dump 给出 66 个节点、句柄 n52 指向壳侧 row 57，
 * 紧接着用该 ref 点击却报「行 57 已不存在（页面已变化）」——而 row 57 明明在快照范围内。
 * 真因是**解析时用的根与建树时不是同一棵**：重定位时重新按 active/focused 选窗口，
 * 焦点一变就换树，childPath 的下标随即指向别的子树。
 *
 * 本测试钉住的正是那条不变量：**span 被 pin 的窗口必须被恒选**。
 */
class WindowPickTest {

  private fun w(id: Int, active: Boolean = false, focused: Boolean = false, root: Boolean = true) =
    WindowPick.Fact(id = id, active = active, focused = focused, hasRoot = root)

  @Test
  fun pinsTheWindowUsedForTheSnapshotEvenWhenFocusMoves() {
    // 建树用了 id=7；随后焦点转到 id=9（IME / 装饰窗口抢焦点是常态）。
    val facts = listOf(w(7), w(9, active = true, focused = true))
    val chosen = WindowPick.order(facts, pinnedId = 7)
    assertEquals("必须回到建树时那棵树（否则 childPath 换坐标系）", 7, chosen.id)
  }

  @Test
  fun fallsBackToActiveThenFocusedThenFirstWhenNothingIsPinned() {
    val facts = listOf(w(1), w(2, focused = true), w(3, active = true))
    assertEquals(3, WindowPick.order(facts, pinnedId = -1).id)
    val noActive = listOf(w(1), w(2, focused = true), w(3))
    assertEquals(2, WindowPick.order(noActive, pinnedId = -1).id)
    val bare = listOf(w(1), w(2))
    assertEquals(1, WindowPick.order(bare, pinnedId = -1).id)
  }

  @Test
  fun pinnedWindowGoneFallsBackAndReportsTheNewWindow() {
    // 钉住的窗口已消失：退回通用选择，让调用方重新 pin（旧路径自然会报 stale，那是对的）。
    val facts = listOf(w(9, active = true))
    assertEquals(9, WindowPick.order(facts, pinnedId = 7).id)
  }

  @Test
  fun ignoresWindowsWithoutRoot() {
    // root==null 的装饰窗口取不到树，哪怕它 active。
    val facts = listOf(w(5, active = true, root = false), w(6))
    assertEquals(6, WindowPick.order(facts, pinnedId = -1).id)
  }

  @Test
  fun noUsableWindowYieldsSentinel() {
    assertEquals(-1, WindowPick.order(listOf(w(5, root = false)), pinnedId = -1).id)
    assertEquals(-1, WindowPick.order(emptyList(), pinnedId = 3).id)
  }
}