package com.ai.assistance.quro.dshmobile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 派活文本回传修复的回归（2026-10-29，真机派障：「Harness 执行完成但无文本答复」）。
 *
 * 根因：`session/page` 的 throughSeq 是**含端游标**（官方 schema 原话「Inclusive log cut
 * obtained from the corresponding follow opening frame」），`paginate()` 从 throughSeq
 * **倒序**收集到 maxMessages 为止——旧代码写死 `throughSeq=0`，每页只拿到 events[0]
 * 一条，`assistant/message` 永远缺席，于是「任务明明成功、答复却不存在」。
 *
 * 修法：`sessionCursor()` 以 Number.MAX_SAFE_INTEGER 探越界，服务端必回
 * `session page through seq X is past cursor N`（HTTP 200，错误在 result.error.message），
 * 解析出真实游标后再分页；探查失败才回退旧值。
 *
 * 本测试锁三件事：
 *  ① 解析纯函数对上游真实文案、负游标（空会话）、无关报错、变体文案四种输入全部判对；
 *  ② 反证：任何「恒回 null」或「恒回 0」的实现都会挂（用 fixture 逐个校验）；
 *  ③ 源码契约：assistantTexts 必须经 sessionCursor 取游标，不得再写死 throughSeq 0。
 */
class SessionCursorProbeTest {

  // ── ① 解析纯函数 ───────────────────────────────────────────────────────────

  /** 上游真实文案（dsh-api-session-controller page() 逐字）。 */
  @Test
  fun parsesTheRealUpstreamMessage() {
    val msg = "session page through seq 9007199254740991 is past cursor 42"
    assertEquals(42L, DshmobileControl.parseCursorFromError(msg))
  }

  /** 空会话：sourceCursor = -1（`sourceLog.at(-1)?.seq ?? -1`），负游标必须原样返回。 */
  @Test
  fun parsesNegativeCursorForEmptySession() {
    val msg = "session page through seq 9007199254740991 is past cursor -1"
    assertEquals(-1L, DshmobileControl.parseCursorFromError(msg))
  }

  /** 反证：无关报错（会话未注册）不得误取出游标。 */
  @Test
  fun unrelatedErrorsReturnNull() {
    assertNull(DshmobileControl.parseCursorFromError("session \"sid-1\" not found"))
    assertNull(DshmobileControl.parseCursorFromError("throughSeq must be an integer greater than or equal to -1"))
    assertNull(DshmobileControl.parseCursorFromError(""))
    assertNull(DshmobileControl.parseCursorFromError("   "))
  }

  /** 反证：把游标读成 0 或 MAX 都是错的——必须精确等于报文里的 N。 */
  @Test
  fun cursorIsExactNotClamped() {
    val msg = "session page through seq 9007199254740991 is past cursor 1234567"
    val cursor = DshmobileControl.parseCursorFromError(msg)
    assertEquals(1234567L, cursor)
    assertTrue("不得退化成 0", cursor != 0L)
    assertTrue("不得退化成探测值", cursor != 9007199254740991L)
  }

  /** 上游改文案时的兜底：`cursor N` 形态仍可解析。 */
  @Test
  fun fallbackPatternCoversRewordedMessage() {
    assertEquals(7L, DshmobileControl.parseCursorFromError("page rejected: cursor 7"))
    assertEquals(7L, DshmobileControl.parseCursorFromError("page rejected: cursor: 7"))
  }

  /** 反证：消息里带其它数字（seq 值）不得被误抓。 */
  @Test
  fun doesNotGrabTheThroughSeqValue() {
    // 文案里有两个数字：throughSeq 与 cursor。必须取后者。
    val msg = "session page through seq 9007199254740991 is past cursor 9"
    assertEquals(9L, DshmobileControl.parseCursorFromError(msg))
  }

  // ── ③ 源码契约：assistantTexts 必须走游标发现 ──────────────────────────────

  private fun source(): String {
    val f = File("src/main/java/com/ai/assistance/quro/dshmobile/DshmobileControl.kt")
    assertTrue("找不到 DshmobileControl.kt（工作目录应为 dshmobile/app）", f.isFile)
    return f.readText()
  }

  @Test
  fun assistantTextsGoesThroughSessionCursor() {
    val src = source()
    val body = src.substringAfter("private fun assistantTexts(").substringBefore("private fun rpc(")
    assertTrue("assistantTexts 必须调用 sessionCursor 取真实游标", body.contains("sessionCursor(app, sid)"))
    assertTrue("assistantTexts 必须把游标用于 throughSeq", body.contains("throughSeq"))
    assertTrue(
      "assistantTexts 不得再写死 throughSeq 0（那正是『执行完成但无文本答复』的根因）",
      !body.contains("put(\"throughSeq\", 0)"),
    )
  }

  @Test
  fun probeUsesMaxSafeInteger() {
    val src = source()
    assertTrue(
      "探测值必须是 Number.MAX_SAFE_INTEGER（9007199254740991）——safe integer 校验上限",
      src.contains("9_007_199_254_740_991L"),
    )
  }
}
