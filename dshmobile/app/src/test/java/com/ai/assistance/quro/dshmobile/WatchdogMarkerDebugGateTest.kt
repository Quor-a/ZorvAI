package com.ai.assistance.quro.dshmobile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §6.4 标记消费调试日志有界化（0.14.10 功耗面）的回归。
 *
 * 缺陷形态（审计 §6.4）：consumeTaskDoneMarkers 内部 dbg() 直接 appendText 到
 * files/notify-debug.log——每拍至少两行（window 与 done），有标记时每行再各两条；
 * 没有轮转上限、没有开关。是真机上一次纯噪音写盘。
 *
 * 修法（按仓里既有口径）：收进 MainActivity.DevLogPrefs 这条既有开发者日志开关（默认关），
 * 并给文件加容量上限、到上限滚一代。**消费逻辑一行都没少**。
 *
 * 反证口径：把 dbg 改回无条件写、或去掉开关判断、或删掉容量判据，下面都会变红。
 */
class WatchdogMarkerDebugGateTest {

  @Test
  fun 开关关闭时一律不写() {
    assertEquals(
      "默认配置（DevLogPrefs 关）下必须完全不写调试日志",
      WatchdogV2.MarkerDebugStep.SKIP,
      WatchdogV2.markerDebugStep(enabled = false, currentBytes = 0L),
    )
    assertEquals(
      "文件再小也不写：先看开关，再看容量",
      WatchdogV2.MarkerDebugStep.SKIP,
      WatchdogV2.markerDebugStep(enabled = false, currentBytes = 1024L),
    )
  }

  @Test
  fun 开关打开后写_到上限滚一代() {
    assertEquals(
      "开关打开且未到上限：追加",
      WatchdogV2.MarkerDebugStep.APPEND,
      WatchdogV2.markerDebugStep(enabled = true, currentBytes = 0L),
    )
    val limit = WatchdogV2.MARKER_DEBUG_LIMIT_BYTES
    assertEquals("差一字节仍追加", WatchdogV2.MarkerDebugStep.APPEND, WatchdogV2.markerDebugStep(true, limit - 1))
    assertEquals("正好到上限滚一代", WatchdogV2.MarkerDebugStep.ROTATE, WatchdogV2.markerDebugStep(true, limit))
    assertEquals("超上限也滚", WatchdogV2.MarkerDebugStep.ROTATE, WatchdogV2.markerDebugStep(true, limit * 10))
  }

  @Test
  fun 容量上限必须是有界值且滚一代() {
    assertTrue("上限必须存在且小于 4 MiB（否则等于没界）", WatchdogV2.MARKER_DEBUG_LIMIT_BYTES in 1..(4L * 1024 * 1024))
    assertTrue("上限必须至少能装下一拍的两三行", WatchdogV2.MARKER_DEBUG_LIMIT_BYTES >= 64L * 1024)
    assertEquals("调试日志文件名沿用既有的 notify-debug.log", "notify-debug.log", WatchdogV2.MARKER_DEBUG_FILE)
  }

  // ── 源码契约 ───────────────────────────────────────────────

  private fun source(): String {
    val f = listOf(
      File("src/main/java/com/ai/assistance/quro/dshmobile/WatchdogV2.kt"),
      File("app/src/main/java/com/ai/assistance/quro/dshmobile/WatchdogV2.kt"),
    ).firstOrNull { it.isFile }
      ?: throw AssertionError("找不到 WatchdogV2.kt（工作目录 = " + File(".").absolutePath + "）")
    return f.readText()
  }

  private fun codeOnly(src: String): String = src.lineSequence()
    .filterNot {
      val t = it.trimStart()
      t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
    }
    .joinToString("\n")

  private fun memberBody(src: String, signature: String): String {
    val idx = src.indexOf(signature)
    if (idx < 0) throw AssertionError("找不到成员签名 " + signature)
    val rest = src.substring(idx + signature.length)
    val cut = listOf("\n  private fun ", "\n  internal fun ", "\n  fun ")
      .map { rest.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: rest.length
    return rest.substring(0, cut)
  }

  @Test
  fun 调试日志必须走开关与容量判据_不得无条件写() {
    val body = codeOnly(memberBody(source(), "internal fun consumeTaskDoneMarkers("))
    assertTrue("dbg 必须读开关", body.contains("val debugLogOn = devLogEnabled(context)"))
    assertTrue("dbg 必须走容量判据", body.contains("markerDebugStep(debugLogOn,"))
    assertTrue("到上限必须滚一代", body.contains("MARKER_DEBUG_FILE + \".1\""))
    // 无条件写形态：dbg 里直接把 appendText 顶在最前面（没有判据）就是缺陷本体。
    val dbgBody = body.substringAfter("fun dbg(msg: String)").substringBefore("try {\n      val f = taskMarkerFile")
    assertTrue("dbg 内必须先过 markerDebugStep", dbgBody.contains("markerDebugStep("))
    assertTrue("appendText 必须在判据之后（同一 dbg 体内）", dbgBody.indexOf("appendText") > dbgBody.indexOf("markerDebugStep("))
  }

  @Test
  fun 开关必须复用既有的开发者日志偏好而不是新造() {
    val code = codeOnly(source())
    assertTrue(
      "必须复用 MainActivity.DevLogPrefs（既有开关），不得另造一套偏好",
      code.contains("MainActivity.DevLogPrefs.isEnabled(context)"),
    )
    assertFalse("不得引入第二个偏好键", code.contains("notify_debug_enabled"))
  }

  @Test
  fun 消费逻辑一行都不能少() {
    val body = codeOnly(memberBody(source(), "internal fun consumeTaskDoneMarkers("))
    // 这几条是消费语义的全部承重点：读了、解析了、投递了、按整行推进了偏移。
    assertTrue("必须有界读", body.contains("RandomAccessFile("))
    assertTrue("必须走纯函数窗口", body.contains("markerConsumeWindow("))
    assertTrue("必须解析 JSON", body.contains("org.json.JSONObject(line)"))
    assertTrue("必须投递通知（旧信道回退）", body.contains("NotifyStore.legacyFallback(context, title, snippet)"))
    assertTrue("必须按整行推进偏移", body.contains("markerOffsetTo(context, offset + window.advance)"))
    assertTrue("len < offset 必须归零", body.contains("if (len < offset)"))
    // 反证：关掉日志不得让任何一条消费路径变成早退。
    assertFalse("开关不得造成提前 return（那会吞掉标记）", body.contains("if (!debugLogOn) return"))
  }
}
