package com.ai.assistance.quro.dshmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缺陷 D（fx-2）：控制台离线口令 `dsh safe` 的解析判据（纯 JVM）。
 *
 * 为什么这条判据必须收紧：命中口令时控制台**不落 bash**（见 ConsoleActivity.submit），
 * 因此解析过宽 = 吞掉用户本想交给 bash 的命令。反证锚点是 `echo hi` 必须返回 null；
 * 另外 `dsh safe on extra` 这类多词输入也返回 null——宁可让 bash 报错，也不猜用户想干什么。
 */
class SafeConsoleTest {

  @Test
  fun `dsh safe 三种写法都能解析`() {
    assertEquals(SafeAction.ON, parseSafeCommand("dsh safe"))
    assertEquals(SafeAction.ON, parseSafeCommand("dsh safe on"))
    assertEquals(SafeAction.OFF, parseSafeCommand("dsh safe off"))
    assertEquals(SafeAction.STATUS, parseSafeCommand("dsh safe status"))
  }

  /** 反证核心：普通命令绝不能被壳吞掉（否则控制台就不能用了）。 */
  @Test
  fun `普通命令必须返回 null 原样交给 bash`() {
    assertNull("命令不是安全模式口令", parseSafeCommand("echo hi"))
    assertNull(parseSafeCommand("dsh"))
    assertNull(parseSafeCommand("dsh status"))
    assertNull(parseSafeCommand("safe"))
    assertNull(parseSafeCommand(""))
    assertNull("空白输入不得命中", parseSafeCommand("   "))
    // 与安全模式**形近**但不是口径的写法：一律交给 bash（含我们自己的离线 CLI 命令）。
    assertNull(parseSafeCommand("dsh safe-mode on"))
    assertNull(parseSafeCommand("node dsh-undo-emergency.mjs safe-mode on"))
    assertNull("显式快照清单仍交给急救 CLI", parseSafeCommand("node \"\$DSH_FILES_DIR/undo-emergency.mjs\" list"))
    assertNull("显式快照 ID 恢复仍交给急救 CLI", parseSafeCommand("node \"\$DSH_FILES_DIR/undo-emergency.mjs\" restore 20260920-235635-524b"))
    assertNull("CLI 的自动 last-good 选择不得被壳当作 Safe Mode 口令", parseSafeCommand("node \"\$DSH_FILES_DIR/undo-emergency.mjs\" restore-last-good"))
    assertNull("多余词一律不猜", parseSafeCommand("dsh safe on extra"))
    assertNull(parseSafeCommand("dsh safe off now"))
    assertNull(parseSafeCommand("dsh safe enable"))
  }

  @Test
  fun `大小写与空白归一但不放宽词法`() {
    assertEquals(SafeAction.OFF, parseSafeCommand("  DSH   SAFE   OFF  "))
    assertEquals(SafeAction.STATUS, parseSafeCommand("Dsh Safe Status"))
    assertEquals(SafeAction.ON, parseSafeCommand("\tdsh\tsafe\t"))
  }

  /** 用法回执必须点名三条子命令（否则用户在控制台里无从发现它们）。 */
  @Test
  fun `用法回执列出三条子命令`() {
    val usage = safeCommandUsage()
    assertTrue("必须含进入写法", usage.contains("dsh safe"))
    assertTrue("必须含退出写法", usage.contains("dsh safe off"))
    assertTrue("必须含查看写法", usage.contains("dsh safe status"))
  }
}
