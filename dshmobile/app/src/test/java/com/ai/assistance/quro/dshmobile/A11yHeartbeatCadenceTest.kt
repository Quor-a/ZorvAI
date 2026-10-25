package com.ai.assistance.quro.dshmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §6.5 无障碍心跳降频（0.14.10 功耗面）的回归。
 *
 * 缺陷形态（审计 §6.5）：ControlPoller 的独立心跳线程 sleep(2_000)，而每次心跳都是
 * DeviceControlService.heartbeat 里的一次 SharedPreferences edit().apply()——2 秒一次
 * 无谓写盘。消费方的新鲜窗口是 A11Y_FRESH_MS = 20_000（引擎侧桥插件），2 秒是十倍余量。
 *
 * 判据（审计与用户口径）：降到 5 秒，余量仍有 4 倍。
 *
 * 反证口径：把节拍改回 2 秒、或把「先发心跳再等一拍」改成「先等再发」，下面都会变红。
 */
class A11yHeartbeatCadenceTest {

  /** 假等待：只记账，不 sleep 真实时间（用户口径要求）。 */
  private class FakeWaiter {
    val waits = ArrayList<Long>()
    fun record(ms: Long) {
      waits += ms
    }
  }

  @Test
  fun 心跳节拍必须是五秒() {
    assertEquals("0.14.10 §6.5：2 秒 -> 5 秒", 5_000L, ControlPoller.HEARTBEAT_INTERVAL_MS)
    // 消费方新鲜窗口 20 秒：5 秒一拍余量 4 倍（这是降频的上界依据）。
    val consumerFreshMs = 20_000L
    assertEquals("余量必须恰好是 4 倍", 4L, consumerFreshMs / ControlPoller.HEARTBEAT_INTERVAL_MS)
    assertTrue(
      "节拍必须明显大于 2 秒（否则等于没降频）",
      ControlPoller.HEARTBEAT_INTERVAL_MS >= 5_000L,
    )
  }

  @Test
  fun 循环节奏按注入的等待函数走_不发真实心跳也不睡真时间() {
    val waiter = FakeWaiter()
    var beats = 0
    var running = true
    // 跑到第 3 次等待后让循环退出：证明节奏由 intervalMs 驱动，且不依赖真实时钟。
    ControlPoller.runHeartbeatLoop(
      isRunning = { running },
      heartbeat = {
        beats++
        if (beats == 3) running = false
      },
      intervalMs = ControlPoller.HEARTBEAT_INTERVAL_MS,
      waitMs = { waiter.record(it) },
    )
    assertEquals("三次心跳", 3, beats)
    assertEquals("每次等待都是一拍（5 秒）", listOf(5_000L, 5_000L, 5_000L), waiter.waits)
    assertEquals("等待次数 = 心跳次数（先发后等）", beats, waiter.waits.size)
  }

  @Test
  fun 首拍必须立刻发心跳而不是先等五秒() {
    // 反证：若实现改成「先等再发」，服务刚连上时引擎侧最多 5 秒看不到新鲜心跳，
    // 这段窗口里 A11Y 通道会被误判为「没起来」。下面按调用序列断言顺序。
    val order = ArrayList<String>()
    var running = true
    ControlPoller.runHeartbeatLoop(
      isRunning = { running },
      heartbeat = {
        order += "beat"
        running = false
      },
      waitMs = { order += "wait" },
    )
    assertEquals("必须「先发心跳、后等一拍」", listOf("beat", "wait"), order)
  }

  @Test
  fun 心跳异常不得打断后续节拍() {
    val waiter = FakeWaiter()
    var calls = 0
    var running = true
    ControlPoller.runHeartbeatLoop(
      isRunning = { running },
      heartbeat = {
        calls++
        if (calls == 2) running = false
        throw IllegalStateException("prefs 写失败（模拟）")
      },
      waitMs = { waiter.record(it) },
    )
    assertEquals("异常被吞掉，节拍继续", 2, calls)
    assertEquals("异常拍也要继续排下一拍", 2, waiter.waits.size)
  }

  @Test
  fun 服务启动必须用可注入等待的循环而不是硬编码sleep() {
    // 源码契约：把 sleep(2_000) 写回去、或绕开可注入入口，这条直接判红。
    val src = listOf(
      java.io.File("src/main/java/com/ai/assistance/quro/dshmobile/ControlPoller.kt"),
      java.io.File("app/src/main/java/com/ai/assistance/quro/dshmobile/ControlPoller.kt"),
    ).first { it.isFile }.readText()
    val code = src.lineSequence()
      .filterNot {
        val t = it.trimStart()
        t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
      }
      .joinToString("\n")
    assertFalse("不得再出现硬编码的 2 秒心跳 sleep", code.contains("sleep(2_000)"))
    assertTrue("心跳线程必须走可注入等待的循环", code.contains("runHeartbeatLoop(isRunning = { running.get() }, heartbeat = heartbeat)"))
    val loop = code.substringAfter("internal fun runHeartbeatLoop(").substringBefore("fun stop()")
    assertTrue("先发心跳", loop.indexOf("heartbeat()") in 0 until loop.indexOf("waitMs(intervalMs)"))
    assertTrue("再等一拍", loop.contains("waitMs(intervalMs)"))
  }
}