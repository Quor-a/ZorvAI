package com.ai.assistance.quro.dshmobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleThemeSourceTest {
  private fun find(relative: String): File = listOf(File(relative), File("app/$relative"))
    .firstOrNull { it.isFile } ?: throw AssertionError("找不到源码 $relative")

  @Test
  fun consoleUsesTheZorvBrandPaletteAndCompactControls() {
    val html = find("src/main/assets/console.html").readText()
    assertTrue(html.contains("@media (prefers-color-scheme: dark)"))
    // ZorvAI 品牌色板（唯一真源：genuiagent-sdk ZorvPalette.kt）：
    // 亮色暖纸底 Paper #F4F1EA / 暗色暖近黑 BrandBackgroundDark #16130F。
    assertTrue(html.contains("--ds-bg: #F4F1EA"))
    assertTrue(html.contains("--ds-bg: #16130F"))
    // 主强调必须是陶土系（亮 Terracotta / 暗 TerracottaDark），不是墨黑或蓝紫。
    assertTrue(html.contains("--ds-accent: #C25A38"))
    assertTrue(html.contains("--ds-accent: #D9785A"))
    assertTrue(html.contains("--ds-radius: 8px"))
    assertFalse(html.contains("#3DDBCA"))
    assertFalse(html.contains("#5EEAD4"))
    assertFalse(html.contains("radial-gradient"))
  }

  @Test
  fun consoleKeepsItsWorkingControlsAndKeyboardInsetContract() {
    val html = find("src/main/assets/console.html").readText()
    for (id in listOf("close", "enginePill", "copyBtn", "clearBtn", "restartBtn", "output", "chips", "cmd", "send")) {
      assertTrue("console control #$id must remain", html.contains("id=\"$id\""))
    }
    assertTrue(html.contains("window.__consoleInsets = function (top, bottom, ime)"))
    assertTrue(html.contains("window.consoleBridge.submit(text)"))
  }

  @Test
  fun nativeConsoleBarsFollowTheSameDayNightBackground() {
    val activity = find("src/main/java/com/ai/assistance/quro/dshmobile/ConsoleActivity.kt").readText()
    // 控制台页面底色已品牌化为 ZorvAI 纸色（ds_console_bg：亮 #F4F1EA / 暗 #16130F），
    // 原生状态栏/导航栏/WebView 底色必须跟随页面底色。2026-10-26 起全局 ds_bg 也切到
    // 同一套品牌暖纸体系（Paper #F4F1EA / BrandBackgroundDark #16130F），两侧同源无差分界；
    // ds_console_bg 保留为独立 token（控制台可单独演进，不受引导页色板牵连）。
    assertTrue(activity.contains("window.statusBarColor = getColor(R.color.ds_console_bg)"))
    assertTrue(activity.contains("window.navigationBarColor = getColor(R.color.ds_console_bg)"))
    assertTrue(activity.contains("setBackgroundColor(getColor(R.color.ds_console_bg))"))
    assertTrue(activity.contains("isAppearanceLightStatusBars = !isNight"))
    assertTrue(activity.contains("isAppearanceLightNavigationBars = !isNight"))
  }
}
