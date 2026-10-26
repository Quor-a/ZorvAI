package com.ai.assistance.quro.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「Harness 权限设置」页的重写契约（#482）。
 *
 * ## 用户原话与判据
 * 「手机操控改名 Harness 权限设置然后重写 UI」——他把「改名」和「重写」并列，说明
 * **只改名不算数**。历史上这一页被投诉过三次「没重写」，前两次都是删按钮/调间距，
 * 骨架始终是同一套「GroupCaption + SetGroup(SetRow/PcActionChip)」的**设置列表语法**。
 *
 * 所以本测试守的是**骨架是否换了**：
 *   1. 标题/名称为「Harness 权限设置」（qk_04167 与页面标题 qk_04398）；
 *   2. 页面不再出现旧骨架的组件调用（GroupCaption / SetGroup / SetRow / PcActionChip /
 *      PcRadioRow）——注释里提到它们作为背景说明是允许的，所以判据用「去注释后的代码」；
 *   3. 五个新组件必须真的被实例化（不是定义了不用）；
 *   4. 状态总览必须有「x / n 就绪」计数与刷新入口；
 *   5. 本页**不得再出现授权申请**（授权门唯一在「设置 → 权限」）。
 */
class HarnessPermissionScreenTest {

  private fun repoFile(relative: String): File {
    val candidates = listOf(
      File("../$relative"),
      File(relative),
      File("D:/Calw OS-project/QuroAI/$relative"),
    )
    return candidates.firstOrNull { it.isFile }
      ?: throw AssertionError("找不到 $relative（工作目录 = " + File(".").absolutePath + "）")
  }

  /** 去掉行注释与块注释，判据只看代码（本轮注释里刻意引用了旧组件名作背景）。 */
  private fun codeOnly(text: String): String {
    val noBlock = Regex("/\\*[\\s\\S]*?\\*/").replace(text, "")
    return noBlock.lineSequence()
      .map { line -> if (line.trimStart().startsWith("//")) "" else line.substringBefore("//") }
      .joinToString("\n")
  }

  private val screen: String by lazy {
    codeOnly(repoFile("app/src/main/java/com/ai/assistance/quro/ui/PhoneControlScreen.kt").readText())
  }
  private val zh: String by lazy { repoFile("app/src/main/res/values/strings_i18n.xml").readText() }
  private val en: String by lazy { repoFile("app/src/main/res/values-en/strings_i18n.xml").readText() }

  private fun stringValue(xml: String, id: String): String =
    Regex("name=\"$id\"[^>]*>([^<]*)").find(xml)?.groupValues?.get(1)?.trim() ?: ""

  @Test
  fun `名称必须是 Harness 权限设置`() {
    // 权限页那一行（qk_04167）与页面标题（qk_04398）都要改，只改一处会两处叫法不一致。
    listOf("qk_04167", "qk_04398").forEach { id ->
      assertTrue("$id 应为 Harness 权限设置，实际：${stringValue(zh, id)}",
        stringValue(zh, id).contains("Harness 权限设置"))
    }
    assertTrue(
      "英文文案也要跟着改（不能留 Phone control）",
      stringValue(en, "qk_04398").contains("Harness", ignoreCase = true),
    )
    assertFalse(
      "本页标题不得再用旧名「手机操控」",
      stringValue(zh, "qk_04398").contains("手机操控"),
    )
  }

  @Test
  fun `旧设置列表骨架必须已被移除`() {
    listOf("GroupCaption(", "SetGroup {", "SetRow(", "SetRowClickable(", "PcActionChip(", "PcRadioRow(")
      .forEach { token ->
        assertFalse("旧骨架调用仍在：$token（说明只是换了外层，没重写）", screen.contains(token))
      }
  }

  @Test
  fun `五个新组件必须真的被实例化`() {
    listOf(
      "HarnessStatusPanel(",
      "HarnessDomainCard(",
      "HarnessChoiceTile(",
      "HarnessActionRow(",
      "HarnessToggleRow(",
    ).forEach { token ->
      assertTrue("缺少新组件的使用点：$token", screen.contains(token))
    }
    // 编号域卡：页面必须有 02..07 的编号（能力清单的顺序感是这次重写的核心表达）。
    listOf("\"02\"", "\"03\"", "\"04\"", "\"05\"", "\"06\"", "\"07\"").forEach { idx ->
      assertTrue("缺少编号域卡 $idx", screen.contains("index = $idx"))
    }
  }

  @Test
  fun `状态总览必须给就绪计数与刷新`() {
    assertTrue("缺少就绪计数文案", stringValue(zh, "qk_04400").contains("就绪"))
    assertTrue("就绪计数必须带两个参数（已就绪 / 总数）",
      stringValue(zh, "qk_04400").contains("%1\$d") && stringValue(zh, "qk_04400").contains("%2\$d"))
    assertTrue("状态面板必须接收 readyCount", screen.contains("readyCount ="))
    assertTrue("状态面板必须有刷新入口", screen.contains("onRefresh = { reload() }"))
    // 四个能力域都要在总览里出现（屏幕 / 特权 / 无障碍 / Root）。
    listOf("qk_04402", "qk_04403", "qk_04404", "qk_04405").forEach { id ->
      assertTrue("状态总览缺少域条目 $id", screen.contains("R.string.$id"))
    }
  }

  @Test
  fun `本页不得再出现授权申请`() {
    // 授权门唯一在「设置 → 权限」。本页只允许修复动作。
    assertFalse("不得在本页请求 Shizuku 授权", screen.contains("requestShizukuPermission"))
    assertFalse("不得在本页拉起无障碍开启页", screen.contains("openA11ySettings"))
    assertFalse("不得在本页请求 root 权限以外的授权入口", screen.contains("openPermissionSettings"))
  }
}
