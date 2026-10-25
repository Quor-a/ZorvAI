package com.ai.assistance.quro.dshmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 外部链接通道（[ExternalLinks]）的判据回归（0.14.1 用户定例：设置页「手机控制」的 Shizuku 引导）。
 *
 * 缺陷面：用户被引导到「设置页安装、启动并授权 Shizuku」，而该页**一个入口都没有**——
 * 没有下载页、没有教程、没有拉起 Shizuku 的按钮。本类守的是补上入口之后的**通道纪律**：
 *  - 页面只能点名 key，不能传 URL（[ExternalLinks.classify] 只认登记表）；
 *  - 登记值必须是 https（明文链接不允许交给系统）；
 *  - 「装没装」的判定口径与 [ShizukuTransport] 同源（否则页面与通道会互相打脸）。
 *
 * 反证方式：把 `classify` 的 https 判据删掉，`insecureRegistrationIsRefused` 必红；
 * 把未知 key 回落成任一登记值，`unknownKeyIsRefused` 必红。
 */
class ExternalLinksTest {

  // ── 登记表自身 ──────────────────────────────────────────────

  /** 两个入口都被登记，且都解析为可打开的 https 链接。 */
  @Test
  fun bothIntentionsAreRegisteredAndHttps() {
    val keys = ExternalLinks.keys()
    assertTrue("下载入口必须在册", keys.contains(ExternalLinks.SHIZUKU_DOWNLOAD))
    assertTrue("教程入口必须在册", keys.contains(ExternalLinks.SHIZUKU_TUTORIAL))
    assertEquals(
      "登记表规模变了就要来改这条用例——加外链是有意识的行为，不是顺手加一行",
      2, keys.size,
    )
    for (key in keys) {
      val verdict = ExternalLinks.classify(key)
      assertTrue("登记项 $key 必须可打开，实际 = $verdict", verdict is ExternalLinkVerdict.Openable)
      val url = (verdict as ExternalLinkVerdict.Openable).url
      assertTrue("登记项 $key 必须是 https：$url", url.startsWith("https://"))
    }
  }

  /** 教程链接按用户给定原样保留（含分享来源参数），不做任何清洗。 */
  @Test
  fun tutorialLinkKeepsTheUserGivenShareParam() {
    val verdict = ExternalLinks.classify(ExternalLinks.SHIZUKU_TUTORIAL)
    assertEquals(
      ExternalLinkVerdict.Openable(
        "https://www.bilibili.com/video/BV1iFy7BpECf/?vd_source=f4d91092356f067f076eeacb3ff30380",
      ),
      verdict,
    )
  }

  /** 下载入口指向 Shizuku 官方发布页（不是第三方镜像，也不是应用内下载）。 */
  @Test
  fun downloadLinkPointsAtTheOfficialReleasePage() {
    val verdict = ExternalLinks.classify(ExternalLinks.SHIZUKU_DOWNLOAD)
    assertEquals(
      ExternalLinkVerdict.Openable("https://github.com/RikkaApps/Shizuku/releases"),
      verdict,
    )
  }

  // ── 拒收分支（反向自证）──────────────────────────────────────

  /** 未登记的 key 一律拒收——包括看起来像 URL 的入参（页面不得借桥面传任意地址）。 */
  @Test
  fun unknownKeyIsRefused() {
    assertEquals(ExternalLinkVerdict.UnknownKey, ExternalLinks.classify(""))
    assertEquals(ExternalLinkVerdict.UnknownKey, ExternalLinks.classify("shizuku-docs"))
    assertEquals(
      "页面传进来的 URL 必须当未知 key 处理，不能直接打开",
      ExternalLinkVerdict.UnknownKey,
      ExternalLinks.classify("https://evil.example/payload.apk"),
    )
  }

  /** 登记值不是 https 时拒收（明文 http、javascript:、相对路径三形态）。 */
  @Test
  fun insecureRegistrationIsRefused() {
    val bad = mapOf(
      "http" to "http://example.com/",
      "scheme" to "javascript:alert(1)",
      "relative" to "/releases",
    )
    for ((key, url) in bad) {
      assertEquals(
        "非 https 的登记值必须拒收（$key = $url）",
        ExternalLinkVerdict.InsecureUrl,
        ExternalLinks.classify(key, bad),
      )
    }
  }

  /** 判据结果不相等即「可打开 ↔ 拒收」确实分了岔（防止三条分支被实现成同一个值）。 */
  @Test
  fun verdictsAreDistinguishable() {
    val openable: ExternalLinkVerdict = ExternalLinkVerdict.Openable("https://a.example/")
    val unknown: ExternalLinkVerdict = ExternalLinkVerdict.UnknownKey
    val insecure: ExternalLinkVerdict = ExternalLinkVerdict.InsecureUrl
    assertNotEquals(unknown, insecure)
    assertNotEquals(openable, unknown)
    assertNotEquals(openable, insecure)
  }

  // ── 与 ShizukuTransport 的口径同源 ────────────────────────────

  /**
   * 在场判定必须「共享包名表 + binder 面」，不得退回「单个官方包名否决」。
   *
   * 0.14.8 修法：Shizuku 存在分支实现（Stellar 等，管理器包名不同、经兼容层提供同一套 API），
   * 只查官方包名会把可用分支判成「未检测到 Shizuku」。本契约钉住两点：包名取自
   * [ExternalLinks.SHIZUKU_MANAGER_PACKAGES]（单一真源）；判据保留 binder 面——分支实现只满足这一面。
   */
  @Test
  fun installedProbeUsesTheSharedManagerTableAndBinder() {
    val source = listOf(
      File("src/main/java/com/ai/assistance/quro/dshmobile/ShizukuTransport.kt"),
      File("app/src/main/java/com/ai/assistance/quro/dshmobile/ShizukuTransport.kt"),
    ).firstOrNull { it.isFile }
      ?: throw AssertionError("找不到 ShizukuTransport.kt（工作目录 = " + File(".").absolutePath + "）")
    val text = source.readText()
    assertTrue(
      "ShizukuTransport 的管理器包名必须取自 ExternalLinks.SHIZUKU_MANAGER_PACKAGES（单一真源）",
      text.contains("ExternalLinks.SHIZUKU_MANAGER_PACKAGES"),
    )
    assertTrue(
      "能力判据不得丢掉 binder 面（Shizuku.pingBinder()）——分支实现只满足这一面",
      text.contains("Shizuku.pingBinder()"),
    )
    assertTrue(
      "能力门不得再按 installed 短路（那就是「没装官方包名 = 用不了 Shizuku」的硬性限制）",
      !text.contains("if (!out.optBoolean(\"installed\"))"),
    )
    assertEquals(
      "官方 Shizuku 管理器包名",
      "moe.shizuku.privileged.api",
      ExternalLinks.SHIZUKU_PACKAGE,
    )
    assertTrue(
      "官方包必须在表内",
      ExternalLinks.SHIZUKU_MANAGER_PACKAGES.contains(ExternalLinks.SHIZUKU_PACKAGE),
    )
    assertTrue(
      "分支实现（Stellar）必须在表内",
      ExternalLinks.SHIZUKU_MANAGER_PACKAGES.contains(ExternalLinks.STELLAR_PACKAGE),
    )
  }
}
