package com.ai.assistance.quro.dshmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 本地文档通道（issue #262 免责门：免责声明走 APK 内 assets）的判据回归。
 *
 * 与 [ExternalLinksTest] 同形：页面只能点名 key，登记表在壳侧；
 * 差异是有意为之——这里不存在 https 判据（assets 不是外链），判据是「登记在册」。
 */
class LocalDocsTest {

  @Test
  fun `disclaimer key is registered`() {
    val keys = LocalDocs.keys()
    assertTrue("免责声明入口必须在册", keys.contains(LocalDocs.ROOT_DISCLAIMER))
  }

  @Test
  fun `registered key resolves to an asset path`() {
    val verdict = LocalDocs.classify(LocalDocs.ROOT_DISCLAIMER)
    assertTrue(verdict is LocalDocVerdict.Openable)
    assertTrue(
      "登记值必须是 APK assets 内路径",
      (verdict as LocalDocVerdict.Openable).path.startsWith("docs/"),
    )
  }

  @Test
  fun `unknown keys are refused`() {
    assertEquals(LocalDocVerdict.UnknownKey, LocalDocs.classify(""))
    assertEquals(LocalDocVerdict.UnknownKey, LocalDocs.classify("shizuku-download"))
    // 与 ExternalLinks 不同名：外链 key 不得在本通道撞名（页面传错通道时 fail-closed）。
    assertEquals(LocalDocVerdict.UnknownKey, LocalDocs.classify("https://evil.example/doc.html"))
  }

  @Test
  fun `verdicts are distinguishable`() {
    val openable: LocalDocVerdict = LocalDocVerdict.Openable("docs/x.html")
    val unknown: LocalDocVerdict = LocalDocVerdict.UnknownKey
    assertNotEquals(openable, unknown)
  }

  /**
   * 线程纪律契约（2026-09-30 用户实测「点免责声明闪退」的回归钉）。
   *
   * `open()` 由 JavaBridge 线程调用，WebView/AlertDialog 必须在**主线程**创建——
   * 旧实现直接在该线程 `WebView(activity)` + `show()`，bridge 回包带回
   * IllegalStateException 后**进程仍然原生崩溃**（用户看到的闪退）。
   * 判据：open() 体内必须出现 runOnUiThread，且 WebView 构造出现在它之后。
   */
  @Test
  fun `open marshals webview and dialog to the main thread`() {
    val source = listOf(
      File("src/main/java/com/ai/assistance/quro/dshmobile/LocalDocs.kt"),
      File("app/src/main/java/com/ai/assistance/quro/dshmobile/LocalDocs.kt"),
    ).firstOrNull { it.isFile }
      ?: throw AssertionError("找不到 LocalDocs.kt")
    val text = source.readText()
    val body = Regex("fun open\\([\\s\\S]*?\\n  \\}")
      .find(text)?.value ?: throw AssertionError("找不到 open 函数体")
    val postedAt = body.indexOf("runOnUiThread")
    val webviewAt = body.indexOf("WebView(activity)")
    assertTrue("open() 必须把 UI 组装 marshal 到主线程（runOnUiThread）", postedAt >= 0)
    assertTrue("WebView 必须在 runOnUiThread 之内创建（顺序钉死）", webviewAt > postedAt)
    assertTrue("AlertDialog 必须在 runOnUiThread 之内创建", body.indexOf("AlertDialog.Builder") > postedAt)
    assertTrue("UI 组装必须抓 Throwable（Error 也要拦住，否则又是进程级闪退）", body.contains("catch (t: Throwable)"))
  }
}
