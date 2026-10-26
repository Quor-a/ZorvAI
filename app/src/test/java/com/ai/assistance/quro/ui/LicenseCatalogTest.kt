package com.ai.assistance.quro.ui

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 开源许可清单的契约（#485 全项目排查）。
 *
 * ## 为什么要有这条测试
 * 关于页原来把 32 条组件名**硬编码**在 Kotlin 里，结果：
 *   · **漏**：实际随包的组件远不止这些——ECJ（EPL-2.0）、LibreOffice Kit（MPL-2.0）、
 *     libvips（LGPL-3.0）、Node.js、Python、BusyBox / bash / proot / QEMU（GPL）、Zig、
 *     box64、ripgrep、PCRE2、Ubuntu rootfs、p5.js（LGPL-2.1）、GSAP（非 OSI 许可）全部没列；
 *   · **错**：Shizuku 标 Apache-2.0（Maven 元数据实测 **MIT**）、Yoga 标 Apache-2.0（实测 **MIT**）、
 *     D3.js 未标许可证（实测 **ISC**）；
 *   · **过期**：React / ReactDOM / Recharts / Sucrase（genui 模块已删）、libVLC（死依赖）仍挂着。
 *
 * 现在清单来自 `assets/legal/third_party_licenses.json`（`tools/gen_licenses.py` 生成，
 * 与仓库 NOTICE 同源）。本测试守的是「这份数据本身」：
 *   1. 结构合法（分类有中英标题、条目有许可、copyleft 字段与许可字符串自洽）；
 *   2. 关键组件必须在场（尤其 GPL / LGPL / MPL / EPL 这些有义务的）；
 *   3. 那三处**历史错误**必须持续正确（防止有人改回旧值）；
 *   4. 过期条目不得回潮；
 *   5. 关于页必须读资产，而不是又在 Kotlin 里硬编码一份。
 */
class LicenseCatalogTest {

  private fun repoFile(relative: String): File {
    val candidates = listOf(
      File("../$relative"),
      File(relative),
      File("D:/Calw OS-project/QuroAI/$relative"),
    )
    return candidates.firstOrNull { it.isFile }
      ?: throw AssertionError("找不到 $relative（工作目录 = " + File(".").absolutePath + "）")
  }

  private val json: JSONObject by lazy {
    JSONObject(repoFile("app/src/main/assets/legal/third_party_licenses.json").readText(Charsets.UTF_8))
  }

  /** 所有条目扁平化：name → license / copyleft。 */
  private val entries: List<Triple<String, String, Int>> by lazy {
    val out = mutableListOf<Triple<String, String, Int>>()
    val cats = json.getJSONArray("categories")
    for (i in 0 until cats.length()) {
      val es = cats.getJSONObject(i).getJSONArray("entries")
      for (j in 0 until es.length()) {
        val e = es.getJSONObject(j)
        out.add(Triple(e.getString("name"), e.getString("license"), e.optInt("copyleft")))
      }
    }
    out
  }

  private fun find(keyword: String): Triple<String, String, Int>? =
    entries.firstOrNull { it.first.contains(keyword, ignoreCase = true) }

  @Test
  fun `清单结构与字段自洽`() {
    assertEquals("schema 必须为 1", 1, json.getInt("schema"))
    assertTrue("生成日期必填", json.getString("generatedAt").matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
    val total = json.getInt("total")
    assertEquals("total 必须等于实际条目数", entries.size, total)
    assertTrue("组件条目应覆盖全项目（>=80），实际 $total", total >= 80)

    val cats = json.getJSONArray("categories")
    assertTrue("分类数应 >= 8，实际 ${cats.length()}", cats.length() >= 8)
    val ids = mutableSetOf<String>()
    for (i in 0 until cats.length()) {
      val c = cats.getJSONObject(i)
      assertTrue("分类必须有 id", c.getString("id").isNotBlank())
      assertTrue("分类 id 不得重复：${c.getString("id")}", ids.add(c.getString("id")))
      assertTrue("分类 ${c.getString("id")} 缺中文标题", c.getString("titleZh").isNotBlank())
      assertTrue("分类 ${c.getString("id")} 缺英文标题", c.getString("titleEn").isNotBlank())
      assertTrue("分类 ${c.getString("id")} 不能为空", c.getJSONArray("entries").length() > 0)
    }

    // copyleft 字段必须与许可字符串自洽：GPL 只能标 2，LGPL/MPL/EPL 只能标 1，其余必须 0。
    entries.forEach { (name, lic, copyleft) ->
      val expected = when {
        lic.startsWith("GPL") || lic.startsWith("AGPL") -> 2
        lic.startsWith("LGPL") || lic.startsWith("MPL") || lic.startsWith("EPL") -> 1
        else -> 0
      }
      assertEquals("$name 的 copyleft 标记与许可（$lic）不一致", expected, copyleft)
      assertTrue("$name 缺许可字段", lic.isNotBlank())
    }
  }

  @Test
  fun `有义务的 copyleft 组件必须在场`() {
    // 强 copyleft：分开的可执行程序，但必须列出来并给出源码获取方式。
    listOf("proot", "BusyBox", "GNU Bash", "QEMU").forEach { key ->
      val e = find(key) ?: throw AssertionError("缺少强 copyleft 组件：$key")
      assertEquals("$key 应标为强 copyleft", 2, e.third)
    }
    // 弱 copyleft：LGPL / MPL / EPL，一个都不能漏。
    val weak = entries.filter { it.third == 1 }.map { it.first }
    listOf("talloc", "libvips", "p5.js", "LibreOffice", "GeckoView", "JGit", "ECJ").forEach { key ->
      assertTrue("缺少弱 copyleft 组件：$key（实际：$weak）", weak.any { it.contains(key, true) })
    }
  }

  @Test
  fun `运行时与引擎组件必须在场`() {
    // 这些是「整包排查」的核心：缺任何一项都说明只看了 Gradle。
    listOf(
      "Node.js", "Python", "Zig", "box64", "ripgrep", "PCRE2",
      "Ubuntu", "Alpine", "DeepSeek Harness", "sharp", "tslib", "ECJ", "D8",
    ).forEach { key ->
      assertTrue("缺少组件：$key", find(key) != null)
    }
  }

  @Test
  fun `历史写错的许可证不得改回`() {
    // 三处实测纠正（凭 Maven 元数据 / 文件 banner 得出，不是猜的）。
    assertEquals("Shizuku 实测 MIT", "MIT", find("Shizuku")?.second)
    assertEquals("Yoga 实测 MIT", "MIT", find("Yoga")?.second)
    assertEquals("D3 实测 ISC", "ISC", find("D3")?.second)
    // 顺带钉住几个容易搞错的。
    assertEquals("org.json 是公有领域", "Public Domain", find("org.json")?.second)
    assertEquals("xz for Java 是 0BSD", "0BSD", find("XZ for Java")?.second)
  }

  @Test
  fun `已删除的组件不得回潮`() {
    // genui 模块在 v1.0.96 已删除；libVLC 是「声明但零使用」的死依赖，也已移除。
    listOf("Recharts", "Sucrase", "libVLC", "VLC").forEach { stale ->
      assertTrue("清单里出现了已不存在的组件：$stale", find(stale) == null)
    }
  }

  @Test
  fun `关于页必须读资产而不是硬编码清单`() {
    val src = repoFile("app/src/main/java/com/ai/assistance/quro/ui/QuroAboutScreen.kt").readText()
    assertTrue("关于页必须通过 LicenseCatalog.load 读取清单", src.contains("LicenseCatalog.load("))
    assertFalse(
      "不得再出现旧的手写清单（Triple(qk_00519 …)）",
      Regex("Triple\\(stringResource\\(R\\.string\\.qk_00519\\)").containsMatchIn(src),
    )
    // 弹窗必须渲染分类与 copyleft 说明，否则「排查结果」在界面上看不到。
    assertTrue("必须渲染分类", src.contains("c.categories.forEach"))
    assertTrue("必须渲染许可义务说明", src.contains("qk_04416"))
    assertTrue("必须显示条目数", src.contains("qk_04414"))
  }

  @Test
  fun `仓库 NOTICE 与应用内清单同源`() {
    val notice = repoFile("NOTICE").readText()
    assertTrue("NOTICE 必须声明由生成脚本产出", notice.contains("tools/gen_licenses.py"))
    // 抽样核对两处：GPL 义务说明与一个只在清单里才有的组件。
    assertTrue("NOTICE 缺 copyleft 义务段", notice.contains("COPYLEFT OBLIGATIONS"))
    assertTrue("NOTICE 缺 libvips（LGPL）", notice.contains("libvips"))
    assertTrue("NOTICE 缺 Ubuntu 镜像", notice.contains("Ubuntu 24.04"))
    assertTrue(
      "NOTICE 与应用内清单生成日期应一致",
      notice.contains(json.getString("generatedAt")),
    )
  }
}
