package com.ai.assistance.quro.dshmobile

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-30 诊断包修复（dsh-diag-20261030-025158.zip）：「zorvAI 操控不了 DSH」的噪声侧根因。
 *
 * 缺陷形态（诊断包 dsh-day.log 实证）：`ControlPoller.loop()` 在端点未确认时照样发请求。
 * `EngineEndpoint.port` 在 binding 或 generation<=0 时返回 0 ⇒ `url = http://127.0.0.1:0` ⇒
 * `URL(...)` 构造即抛 `MalformedURLException: Invalid URL: INVALID_PORT`。app onCreate 后
 * （引擎还没上报端口）每 2 秒刷一条 `poll loop error`，从 00:55:27 刷到 01:40:57——45 分钟
 * 上千行，把真正的失败原因整段埋掉，也让「控制通道」看起来像坏在了壳侧。
 *
 * 修法边界（本文件守住）：
 *  - 端点未确认 ⇒ **不发请求、不记 error**，安静等端口发布（这正是 EngineEndpoint 类注释
 *    「未确认的监听器不得发请求」的壳侧自律）；
 *  - 只静默**一次**（一次性 info 留痕），之后端口发布了要能重新留痕；
 *  - 端点确认后的既有行为（轮询 / 退避 / 异常记日志）**一律不动**。
 *
 * 纯 JVM 源码契约（与 CallSiteContractTest / NoTransactionStartRetryTest 同思路）：
 * ControlPoller 的构造依赖 Android Context 与 org.json，行为层无法在 JVM 复现，
 * 因此断言接线与顺序——撤掉修复即红。
 */
class ControlPollerEndpointGateTest {

  private fun source(name: String): String = listOf(
    File("src/main/java/com/ai/assistance/quro/dshmobile", name),
    File("app/src/main/java/com/ai/assistance/quro/dshmobile", name),
  ).firstOrNull { it.isFile }
    ?.readText()
    ?: throw AssertionError("找不到壳侧源码 " + name + "（工作目录 = " + File(".").absolutePath + "）")

  private fun codeOnly(src: String): String = src.lineSequence()
    .filterNot {
      val t = it.trimStart()
      t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
    }
    .joinToString("\n")

  private fun pollerLoop(): String {
    val code = codeOnly(source("ControlPoller.kt"))
    return code.substringAfter("private fun loop()").substringBefore("private fun execute(")
  }

  /** 端点未确认（port=0）必须在发请求**之前**被拦住：顺序写反等于没修。 */
  @Test
  fun theUnconfirmedEndpointIsGatedBeforeAnyRequest() {
    val loop = pollerLoop()
    assertTrue("轮询循环必须读 EngineEndpoint.port", loop.contains("EngineEndpoint.port == 0"))
    assertTrue(
      "端口门禁必须排在 post(...) 之前（排在后面 = 照样发出 INVALID_PORT 请求）",
      loop.indexOf("EngineEndpoint.port == 0") < loop.indexOf("post("),
    )
    // 步进必须是命名常量而不是字面量：A11yHeartbeatCadenceTest 守着
    // 「ControlPoller.kt 不得出现硬编码 sleep(2_000)」的源码契约（心跳降频判据），
    // 轮询等待与心跳节拍是两件事，不得互相踩。
    assertTrue("等待期间必须让出 CPU（不得忙等）", loop.contains("sleep(ENDPOINT_WAIT_STEP_MS)"))
    assertTrue("等待步进常量必须在 companion 里定义", codeOnly(source("ControlPoller.kt")).contains("private const val ENDPOINT_WAIT_STEP_MS = 2_000L"))
    assertTrue("等待后必须 continue（不得继续本轮请求）", loop.contains("continue"))
  }

  /** 静默只能是一次性的：端口发布后再掉线，必须能重新留痕（否则第二次盲等无声无息）。 */
  @Test
  fun theEndpointWaitIsLoggedExactlyOncePerBlindWindow() {
    val loop = pollerLoop()
    assertTrue("必须用一次性开关包裹等待留痕", loop.contains("endpointWaitLogged"))
    assertTrue("盲等必须留一条 info（不是完全无声）", loop.contains("LogCollector.log(TAG,"))
    assertTrue(
      "端口确认后必须复位一次性开关（否则再次盲水时水无声无息）",
      loop.contains("endpointWaitLogged = false"),
    )
  }

  /** 端点确认后的既有契约不得被破坏：通用 catch 仍要记日志（#181），退避阶梯保持原样。 */
  @Test
  fun theConfirmedEndpointBehaviourIsLeftIntact() {
    val loop = pollerLoop()
    assertTrue("通用异常仍必须可查（0.13.8 #181：不得静默吞掉）", loop.contains("poll loop error: "))
    assertTrue("失败退避必须保持", loop.contains("backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)"))
    assertTrue("长轮询参数不得改动", loop.contains("LONG_POLL_MS"))
    assertTrue(
      "端口门禁不得顺手改掉心跳/执行解耦那条线程契约（E3：独立心跳线程照旧）",
      codeOnly(source("ControlPoller.kt")).contains("runHeartbeatLoop("),
    )
  }
}
