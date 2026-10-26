package com.ai.assistance.quro.dshmobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引导页（引擎运行时界面）的**调色板契约** —— 2026-10-26 起为 ZorvAI 品牌暖纸体系。
 *
 * 契约史（本文件类名是历史名，内容已随设计定案切换，勿被 "Monochrome" 字样误导）：
 *   0.14.3 定案「Apple 式黑白极简」——铬面零色相（通道极差 ≤8），色相只留给信号族，
 *     本文件当时守护的就是那条中性灰契约。
 *   2026-10-26 用户截图点名「这个界面还是没有重写」（与控制台 console.html 同一诉求），
 *     拍板整面切换为 ZorvAI 品牌体系（唯一真源 genuiagent-sdk ZorvPalette.kt）：
 *     暖纸底 / 白卡片 / 墨色文字 / 陶土强调 / 暖线。旧的中性灰契约随之作废。
 *   2026-10-26（#446）用户再判「就换个颜色」——**换色不等于重写**。引导页随后做的是
 *     结构重写（三态构图：品牌门面 / 状态英雄卡 / 诊断区），形态契约由
 *     `引导页是三态构图而不是一张大平卡` 守护；色值契约仍由本文件其余用例守护。
 * #447b：用户二次截图「你根本没有重写」截的是诊断区与动作区（#446 变动最小处）——
 *     日志段终端卡化（surface 底 + 圆角描边 + 相位点标题行 + 复制 pill）、控制台入口
 *     降为文本链；同用例 ⑤ 段守护。
 *
 * 现在的守护形态（品牌锚点 + 结构锚点，双 variants 逐值钉死）：
 *   1. 锚点 token 必须逐值等于 ZorvPalette 对应色（亮/暗两套）；
 *   2. 旧 Apple 黑白稿的色值一律不得残留在任何 ds_* token 里（换肤换一半最坑）；
 *   3. 结构断言：根画布平铺 ds_bg 无渐变、主操作是 accent 实心胶囊、
 *      故障仍走 ds_danger 红信号、忙碌态不得用 ds_warn；
 *   4. #446 结构断言：品牌区居中纵向门面 / 状态有光晕环 / 诊断区与主卡分离。
 */
class GuidePaletteMonochromeTest {

  /** 旧 Apple 黑白稿残色谱：任何 ds_* token 再出现这些值即红（换肤不彻底的哨兵）。 */
  private val bannedLegacy = setOf(
    "#F5F5F7", "#1D1D1F", "#6E6E73", "#86868B", "#F2F2F4", "#E8E8ED",
    "#3A3A3C", "#98989D", "#C7C7CC", "#1C1C1E", "#2C2C2E", "#343437",
    "#D70015", "#FF9500", "#34C759", "#30D158", "#FF6960", "#FF9F0A", "#000000",
  )

  /** 品牌锚点（token → 亮色 ZorvPalette / 暗色）。双 variants 必须逐值命中。 */
  private val brandAnchors: Map<String, Pair<String, String>> = mapOf(
    "ds_bg" to ("#F4F1EA" to "#16130F"),                       // Paper / BrandBackgroundDark
    "ds_console_bg" to ("#F4F1EA" to "#16130F"),               // 控制台底色同源
    "ds_surface" to ("#FFFFFF" to "#211D18"),                  // Card / SurfaceDark
    "ds_surface_muted" to ("#ECE7DC" to "#2C2820"),            // Paper2 / ContainerDark
    "ds_chip" to ("#ECE7DC" to "#2C2820"),                     // Paper2 / ContainerDark
    "ds_button_surface" to ("#E3DDD0" to "#332D24"),           // Paper3 / SurfaceHighestDark
    "ds_border" to ("#D8D0C0" to "#4A4439"),                   // Line / LineDark
    "ds_hairline" to ("#E3DDD0" to "#3A352B"),                 // LineSoft / LineSoftDark
    "ds_text_primary" to ("#211E1A" to "#EDE6DA"),             // Ink / OnDark
    "ds_text_secondary" to ("#544D44" to "#A89E90"),           // InkSoft / OnDarkSoft
    "ds_text_tertiary" to ("#938A7E" to "#A89E90"),            // Muted / OnDarkSoft
    "ds_text_on_accent" to ("#FFFFFF" to "#211409"),           // 白 on Terracotta / TerracottaDarkOn
    "ds_accent" to ("#C25A38" to "#D9785A"),                   // Terracotta / TerracottaDark
    "ds_accent_pressed" to ("#A8482B" to "#C25A38"),           // TerracottaPress / Terracotta
    "ds_accent_soft" to ("#F4E4DB" to "#3A2A22"),              // TerracottaSoft / TerracottaDarkContainer
    "ds_danger" to ("#B23A2E" to "#E8836F"),                   // ErrorWarm / ErrorDark
    "ds_danger_soft" to ("#F6E3DF" to "#43201A"),              // ErrorSoft / ErrorDarkContainer
    "ds_progress_track" to ("#E3DDD0" to "#3A352B"),           // LineSoft / LineSoftDark
  )

  private fun source(name: String): String {
    val candidates = listOf(
      File("src/main/java/com/ai/assistance/quro/dshmobile", name),
      File("app/src/main/java/com/ai/assistance/quro/dshmobile", name),
    )
    val f = candidates.firstOrNull { it.isFile }
      ?: throw AssertionError("找不到壳侧源码 " + name + "（工作目录 = " + File(".").absolutePath + "）")
    return f.readText()
  }

  private fun resXml(name: String): String {
    val candidates = listOf(
      File("src/main/res", name),
      File("app/src/main/res", name),
    )
    val f = candidates.firstOrNull { it.isFile }
      ?: throw AssertionError("找不到资源 " + name)
    return f.readText()
  }

  /** 解析 <color name="ds_*">#RRGGBB</color>（忽略注释行，与全仓「只看代码」口径一致）。 */
  private fun dsColors(xml: String): Map<String, String> {
    val out = mutableMapOf<String, String>()
    Regex("""<color name="(ds_[a-z_]+)">(#[0-9A-Fa-f]{6,8})</color>""")
      .findAll(xml).forEach { m ->
        out[m.groupValues[1]] = m.groupValues[2].uppercase()
      }
    return out
  }

  /** 去掉注释行——与全仓门禁「只看代码」的口径一致。 */
  private fun codeOnly(src: String): String = src.lineSequence()
    .filterNot {
      val t = it.trimStart()
      t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
    }
    .joinToString("\n")

  @Test
  fun `ds 色板必须逐值命中 ZorvAI 品牌锚点（亮暗双套）`() {
    for ((index, variant) in listOf("values/colors.xml", "values-night/colors.xml").withIndex()) {
      val colors = dsColors(resXml(variant))
      assertTrue(variant + " 里一个 ds_* 颜色都没解析到，正则或文件结构已漂移", colors.size >= 15)
      for ((token, pair) in brandAnchors) {
        val want = if (index == 0) pair.first else pair.second
        assertTrue(
          variant + " 的 " + token + "=" + colors[token] + "，应为品牌锚点 " + want +
            "（ZorvPalette；引导页 2026-10-26 起为品牌暖纸体系，不再是 Apple 黑白稿）",
          colors[token] == want,
        )
      }
    }
  }

  @Test
  fun `旧 Apple 黑白稿色值不得残留在任何 ds_ token`() {
    for (variant in listOf("values/colors.xml", "values-night/colors.xml")) {
      val colors = dsColors(resXml(variant))
      for ((token, hex) in colors) {
        assertFalse(
          variant + " 的 " + token + " 仍是旧 Apple 黑白稿色值 " + hex +
            "——换肤换一半比不换更坑（品牌锚点见上一张测试）",
          bannedLegacy.contains(hex),
        )
      }
    }
  }

  @Test
  fun `引导页根节点不得再有装饰性渐变与双圈描边`() {
    val chrome = codeOnly(source("GuideChrome.kt"))
    assertFalse(
      "旧形态用 LinearGradient 在根节点铺光晕——品牌稿画布是纯平纸色",
      chrome.contains("LinearGradient"),
    )
    assertTrue("根画布必须直接铺 ds_bg 平色", chrome.contains("setBackgroundColor(color(R.color.ds_bg))"))
    assertFalse("双圈描边的 shell 层已移除", chrome.contains("ds_radius_shell") || chrome.contains("ds_shell"))
    assertFalse("光晕色已删除，不得再被引用", chrome.contains("ds_glow"))
  }

  @Test
  fun `忙碌态圆点统一为墨色-故障才用红`() {
    val renderer = codeOnly(source("GuidePageRenderer.kt"))
    assertFalse(
      "Extracting/Updating 不用琥珀色——品牌稿里「忙」是陶土脉冲，色相留给故障",
      renderer.contains("R.color.ds_warn"),
    )
    assertTrue("Error/Closed 必须仍是红色信号", renderer.contains("R.color.ds_danger"))
    val chrome = codeOnly(source("GuideChrome.kt"))
    assertTrue("主操作必须是 accent 实心胶囊", chrome.contains("DsUi.roundRect(color(R.color.ds_accent), dim(R.dimen.ds_radius_pill))"))
  }

  // ── #446：结构重写契约（「换个颜色」不算重写的反证）─────────────────────────

  /**
   * #448（用户第五次投诉「我要的是重写，不是改」）结构契约：
   *
   * 重写的判据不是「文案变了 / 颜色变了 / 间距变了」，而是**组件与骨架换了**：
   *   · 首屏不再有居中英雄区，改成一条横向状态条（logo + 名称 + 相位胶囊）；
   *   · 状态主体是自绘环形仪表（GuideStatusDial），不是白卡里的 30dp 圆环 + 标题；
   *   · 多了相位步骤条（GuideStepper）与深色控制台窗（GuideConsolePlate）两个新组件；
   *   · 渲染层把相位喂给仪表/步骤条/胶囊（同一判据三处表达）。
   *
   * 判据只取代码里稳定存在的形态行（变量名 + addView 归属 + 调用点），
   * 不对注释断言——注释里会复述旧形态作为背景，对全文断言会被自己的说明判红。
   */
  @Test
  fun `引导页是仪表盘构图且组件确实是新的`() {
    val chrome = codeOnly(source("GuideChrome.kt"))
    val renderer = codeOnly(source("GuidePageRenderer.kt"))

    // ① 首屏是横向状态条，不是居中英雄区
    val topBar = chrome.substringAfter("val topBar = LinearLayout(activity)").substringBefore("content.addView(")
    assertTrue("状态条必须横向", topBar.contains("orientation = LinearLayout.HORIZONTAL"))
    assertTrue("状态条右端必须有相位胶囊", chrome.contains("val phaseChip = TextView(activity)"))
    assertTrue("相位胶囊必须挂进状态条", chrome.contains("topBar.addView("))
    assertFalse(
      "不得再回到居中的 56dp 品牌门面（ds_logo_size 是旧英雄区的尺寸）",
      chrome.contains("dpix(R.dimen.ds_logo_size)"),
    )

    // ② 三个新组件必须在场并真的被用上
    assertTrue("必须有环形仪表类", chrome.contains("GuideStatusDial(activity)"))
    assertTrue("必须有相位步骤条", chrome.contains("GuideStepper(activity)"))
    assertTrue("必须有控制台窗", chrome.contains("GuideConsolePlate(activity)"))
    assertTrue("仪表必须由相位驱动", renderer.contains("chrome.dial.setTone("))
    assertTrue("步骤条必须由相位驱动", renderer.contains("chrome.stepper.applyPhase("))
    assertTrue("胶囊必须写相位短词", renderer.contains("chrome.phaseChip.text ="))

    // ③ 仪表不画编造比例（产品口径：解压总量不可信）
    assertTrue("仪表进度必须恒传 null", renderer.contains("chrome.dial.setFraction(null)"))

    // ④ 状态标题的包装层必须专属（渲染层会隐藏它的 parent）
    assertTrue("engineStatus 必须有专属包装层", chrome.contains("val engineStatusWrap = LinearLayout(activity)"))

    // ⑤ 诊断区：等宽控制台窗，且「分享诊断包」不挂进窗里（窗会因无日志而隐藏）
    assertTrue("必须有独立诊断区块", chrome.contains("val diagSection = LinearLayout(activity)"))
    assertTrue("日志段必须挂进诊断区", chrome.contains("diagSection.addView("))
    assertTrue("日志必须是控制台窗", chrome.contains("consolePlate.attachBody(logSummary)"))
    assertTrue("分享诊断包必须挂诊断区而不是窗内", chrome.contains("diagSection.addView(") && chrome.contains("shareLog"))

    // ⑥ 控制台入口仍是文本链（不与主按钮等重）
    assertTrue("控制台入口必须是文本链", chrome.contains("val consoleButton = makeTextLink("))
  }
}
