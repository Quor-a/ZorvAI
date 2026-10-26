package com.ai.assistance.quro.ui.icons

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `LucideIcon` 的名字覆盖契约（#479）。
 *
 * ## 为什么需要这条测试
 * 2026-10-26 用户截图：对话框「工具菜单」里
 *
 *   可视化小卡片 / 动态UI组件 / AIP文档排版 / GenUI Agent
 *
 * 四行的图标全是**同一个「×」**。原因不是画错了，而是 `iconRes` 是 `when` 精确匹配，
 * 而这四个调用点写的名字（`credit_card`、`layout-dashboard`、`file-text`、
 * `layout_dashboard`）在映射表里**一个都没有**，于是统一掉进 `else -> R.drawable.ic_x`
 * ——`x` 是「关闭/删除」的语义，于是整列长出一排叉，用户完全读不出功能含义。
 *
 * ## 这条测试守三件事
 * 1. **名字覆盖率**：全仓每一处 `LucideIcon("…")` 的名字都必须能被映射表解析（含连字符/
 *    下划线/大小写归一化）。新增调用点写了个没配图标的名字 → 立刻红，而不是等到截图投诉。
 * 2. **兜底不得是×**：`else` 分支不许回到 `ic_x`（语义误导），只能给中性图标。
 * 3. **归一化在场**：`layout-dashboard` 这类连字符写法必须能落到 `layout_dashboard`。
 */
class LucideIconCoverageTest {

  private fun repoFile(relative: String): File {
    val candidates = listOf(
      File("../$relative"),
      File(relative),
      File("D:/Calw OS-project/QuroAI/$relative"),
    )
    return candidates.firstOrNull { it.isFile }
      ?: throw AssertionError(
        "找不到 $relative（工作目录 = " + File(".").absolutePath + "）",
      )
  }

  private fun repoDir(relative: String): File? =
    listOf(
      File("../$relative"),
      File(relative),
      File("D:/Calw OS-project/QuroAI/$relative"),
    ).firstOrNull { it.isDirectory }

  /**
   * 去掉注释后的代码。判据必须只看代码：本次修复的注释里**引用了旧写法**
   * （`LucideIcon("layout_dashboard")`）来说明故障，对全文断言会被自己的说明判红。
   */
  private fun codeOnly(text: String): String =
    text.lineSequence()
      .map { line ->
        val trimmed = line.trimStart()
        when {
          trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*") -> ""
          else -> line.substringBefore("//")
        }
      }
      .joinToString("\n")

  private val mapSource: String =
    repoFile("app/src/main/java/com/ai/assistance/quro/ui/icons/LucideIcon.kt").readText()

  /** 映射表里登记过的名字（去重）。 */
  private val known: Set<String> =
    Regex("\"([a-z0-9_]+)\"\\s*->\\s*R\\.drawable\\.").findAll(mapSource)
      .map { it.groupValues[1] }
      .toSet()

  /** 全仓 Kotlin 主源码里的 `LucideIcon("…")` 调用名 → 出现文件。 */
  private fun callSites(): Map<String, List<String>> {
    val roots = listOf(
      "app/src/main/java",
      "terminal-core/src/main/java",
      "dshmobile/app/src/main/java",
    )
    val out = mutableMapOf<String, MutableList<String>>()
    roots.forEach { root ->
      val dir = repoDir(root) ?: return@forEach
      dir.walkTopDown()
        .filter { it.isFile && it.name.endsWith(".kt") }
        .forEach { f ->
          val text = runCatching { codeOnly(f.readText()) }.getOrNull() ?: return@forEach
          Regex("LucideIcon\\(\\s*\"([^\"]+)\"").findAll(text).forEach { m ->
            out.getOrPut(m.groupValues[1]) { mutableListOf() }.add(f.name)
          }
        }
    }
    return out
  }

  private fun normalize(name: String): String =
    name.trim().lowercase().replace('-', '_').replace(' ', '_')

  @Test
  fun `每一处 LucideIcon 调用都必须有对应图标`() {
    val missing = callSites()
      .filterKeys { normalize(it) !in known }
      .toSortedMap()
    assertEquals(
      "以下图标名没有任何映射分支，会静默渲染成兜底图标（历史上是「×」）：" +
        missing.entries.joinToString("; ") { "${it.key} ← ${it.value.distinct()}" },
      emptyMap<String, List<String>>(),
      missing,
    )
  }

  @Test
  fun `兜底不得是关闭语义的 ic_x`() {
    val body = mapSource.substringAfter("private fun iconRes(")
    assertFalse(
      "iconRes 的 else 不能回 ic_x：任何漏配的名字都会让界面长出一个「×」",
      Regex("else\\s*->\\s*R\\.drawable\\.ic_x").containsMatchIn(body),
    )
    assertTrue(
      "兜底应给中性图标 qic_other",
      Regex("else\\s*->\\s*R\\.drawable\\.qic_other").containsMatchIn(body),
    )
  }

  @Test
  fun `连字符与大小写写法必须被归一化解析`() {
    assertTrue(
      "iconRes 必须先归一化再匹配（否则 layout-dashboard 永远解析不到）",
      mapSource.contains("normalizeIconName(name)"),
    )
    // 归一化的实现要真的把连字符换成下划线，不能只是 trim。
    val norm = mapSource.substringAfter("private fun normalizeIconName(")
      .substringBefore("\n\n")
    assertTrue("归一化必须处理连字符", norm.contains("replace('-', '_')"))
    // 这五个名字历史上全部漏配（对话框三行 + GenUI + 文件夹/链接/菜单），逐一登记。
    listOf("credit_card", "layout_dashboard", "folder", "link", "menu").forEach { name ->
      assertTrue("映射表必须登记 $name", known.contains(name))
    }
  }

  @Test
  fun `GenUI Agent 用自身的官方标志而不是通用图标`() {
    val chat = repoFile("app/src/main/java/com/ai/assistance/quro/ui/ChatScreen.kt").readText()
    // GenUI 那行的图标来源必须是 genui_logo（它自己的应用 Logo），且不得被 tint 染色。
    val rowStart = chat.indexOf("内置 GenUI Agent 入口")
    assertTrue("找不到 GenUI 入口注释锚点", rowStart >= 0)
    val row = codeOnly(chat.substring(rowStart, (rowStart + 1400).coerceAtMost(chat.length)))
    assertTrue("GenUI 行必须使用 R.drawable.genui_logo", row.contains("R.drawable.genui_logo"))
    assertTrue(
      "genui_logo 是彩色矢量，必须 tint = Color.Unspecified",
      row.contains("Color.Unspecified"),
    )
    assertFalse(
      "GenUI 行不得再走 LucideIcon（其名字此前解析不到，渲染成 ×）",
      row.contains("LucideIcon(\"layout_dashboard\""),
    )
    // 资产本身要在场（应用内空状态也在用同一枚 Logo）。
    assertTrue(
      "genui_logo 矢量必须存在",
      repoFile("app/src/main/res/drawable/genui_logo.xml").isFile,
    )
  }
}
