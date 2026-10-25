package com.ai.assistance.quro.dshmobile

import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引导页 Apple 式黑白稿的**调色板契约**（0.14.3 重构）：
 *
 * 设计定案是「铬面零色相」——画布/卡片/描边/文字/主操作只允许近中性灰，
 * 色相只留给语义信号族（ok/warn/danger/danger_soft）。旧稿的 teal accent +
 * 顶部光晕渐变就是「装饰性上色」的反面教材。若有人重新往 ds_* 铬面色里掺
 * 色相、或给引导页根节点加回渐变，本文件应变红。
 */
class GuidePaletteMonochromeTest {

  /** 语义信号族：刻意保留色相（红=故障、绿=成功、橙=提醒），不参与无色相断言。 */
  private val signalHues = setOf("ds_ok", "ds_warn", "ds_danger", "ds_danger_soft")

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

  /** 去掉注释行——与全仓门禁「只看代码」的口径一致。 */
  private fun codeOnly(src: String): String = src.lineSequence()
    .filterNot {
      val t = it.trimStart()
      t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
    }
    .joinToString("\n")

  private fun rgbSpread(argbHex: String): Int {
    val hex = argbHex.removePrefix("#")
    val rgb = if (hex.length == 8) hex.substring(2) else hex
    require(rgb.length == 6) { "非预期颜色字面量 " + argbHex }
    val r = rgb.substring(0, 2).toInt(16)
    val g = rgb.substring(2, 4).toInt(16)
    val b = rgb.substring(4, 6).toInt(16)
    return maxOf(abs(r - g), abs(g - b), abs(r - b))
  }

  @Test
  fun `ds 铬面色板除信号族外必须无色相`() {
    // 容差 8：容纳羊皮纸 #F5F5F7（差 2）一类 Apple 式近中性灰；teal #0E7A72（差 108）必然撞上。
    for (variant in listOf("values/colors.xml", "values-night/colors.xml")) {
      val xml = resXml(variant)
      var seen = 0
      Regex("""<color name="(ds_[a-z_]+)">(#[0-9A-Fa-f]{6,8})</color>""")
        .findAll(xml).forEach { m ->
          val name = m.groupValues[1]
          if (name in signalHues) return@forEach
          seen += 1
          val spread = rgbSpread(m.groupValues[2])
          assertTrue(
            variant + " 的 " + name + "=" + m.groupValues[2] +
              " 偏离中性灰（通道极差 " + spread + " > 8）：黑白稿的色相只允许出现在信号族",
            spread <= 8,
          )
        }
      assertTrue(variant + " 里一个 ds_* 颜色都没解析到，正则或文件结构已漂移", seen >= 10)
    }
  }

  @Test
  fun `引导页根节点不得再有装饰性渐变与双圈描边`() {
    val chrome = codeOnly(source("GuideChrome.kt"))
    assertFalse(
      "旧形态用 LinearGradient 在根节点铺光晕——黑白稿画布是纯平色",
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
      "Extracting/Updating 不再用琥珀色——单色稿里「忙」是墨色脉冲，色相留给故障",
      renderer.contains("R.color.ds_warn"),
    )
    assertTrue("Error/Closed 必须仍是红色信号", renderer.contains("R.color.ds_danger"))
    val chrome = codeOnly(source("GuideChrome.kt"))
    assertTrue("主操作必须是 accent 实心胶囊", chrome.contains("DsUi.roundRect(color(R.color.ds_accent), dim(R.dimen.ds_radius_pill))"))
  }
}
