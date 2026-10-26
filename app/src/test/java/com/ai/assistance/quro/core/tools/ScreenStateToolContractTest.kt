package com.ai.assistance.quro.core.tools

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `get_screen_state` 的契约（#483）。
 *
 * ## 用户实测的两条故障
 * 1. **崩溃**（两条通道同错，可复现）：
 *    `Tried to obtain display from a Context not associated with one.` ——
 *    工具拿到的永远是 **application Context**，而 `context.display` 在非 visual Context 上
 *    直接抛 `UnsupportedOperationException`。`WindowManager.getDefaultDisplay()` 也救不了：
 *    application Context 拿到的 WindowManager 同样未关联 display，抛的是同一句。
 * 2. **假数据**：旧实现把 `"screen_on"` 硬编码成 `true`，熄屏时照样报「亮着」，
 *    而工具描述里第一句就是「屏幕亮着吗」。
 *
 * 修法：Display 走 `DisplayManager.getDisplay(DEFAULT_DISPLAY)`；亮屏走
 * `PowerManager.isInteractive`；锁屏走 `KeyguardManager.isKeyguardLocked`。
 */
class ScreenStateToolContractTest {

  private val src: String by lazy {
    val candidates = listOf(
      java.io.File("../app/src/main/java/com/ai/assistance/quro/core/tools/QuroToolsAccessibility.kt"),
      java.io.File("app/src/main/java/com/ai/assistance/quro/core/tools/QuroToolsAccessibility.kt"),
      java.io.File("D:/Calw OS-project/QuroAI/app/src/main/java/com/ai/assistance/quro/core/tools/QuroToolsAccessibility.kt"),
    )
    val f = candidates.firstOrNull { it.isFile }
      ?: throw AssertionError("找不到 QuroToolsAccessibility.kt（工作目录 = " + File(".").absolutePath + "）")
    f.readText()
  }

  /** 只取 `GetScreenStateTool` 这一个类，并**去掉注释**：本轮修复的说明里刻意写了旧写法
   *  （`context.display`）作为背景，对全文断言会被自己的注释判红。 */
  private val toolSource: String by lazy {
    val start = src.indexOf("class GetScreenStateTool")
    assertTrue("找不到 GetScreenStateTool", start >= 0)
    val raw = src.substring(start, (start + 4000).coerceAtMost(src.length))
    val noBlock = Regex("/\\*[\\s\\S]*?\\*/").replace(raw, "")
    noBlock.lineSequence()
      .map { line -> if (line.trimStart().startsWith("//")) "" else line.substringBefore("//") }
      .joinToString("\n")
  }

  @Test
  fun `不得再用 context_点_display 取 Display`() {
    assertFalse(
      "context.display 在 application Context 上会抛 UnsupportedOperationException（用户实测的崩溃原句）",
      toolSource.contains("context.display"),
    )
    assertTrue(
      "Display 必须从 DisplayManager 取",
      toolSource.contains("Context.DISPLAY_SERVICE") && toolSource.contains("Display.DEFAULT_DISPLAY"),
    )
    assertTrue("必须显式引用 android.view.Display", toolSource.contains("Display?"))
  }

  @Test
  fun `亮屏判据必须是 PowerManager_isInteractive`() {
    assertTrue("必须用 isInteractive 判断亮屏", toolSource.contains("isInteractive"))
    assertFalse(
      "不得再把 screen_on 硬编码成 true（熄屏也报亮屏的假数据）",
      Regex("\"screen_on\":true").containsMatchIn(toolSource),
    )
    assertTrue(
      "响应里必须回 screen_on 字段（值来自 isInteractive）",
      toolSource.contains("\"screen_on\":\$screenOn"),
    )
  }

  @Test
  fun `锁屏状态必须上报`() {
    assertTrue("必须用 KeyguardManager 判断锁屏", toolSource.contains("isKeyguardLocked"))
    assertTrue("响应里必须回 locked 字段", toolSource.contains("\"locked\":\$locked"))
    // off / locked / on 三态要能区分（只回布尔值区分不出「亮着但锁着」）。
    assertTrue("必须有 off 态", toolSource.contains("\"off\""))
    assertTrue("必须有 locked 态", toolSource.contains("\"locked\""))
  }
}
