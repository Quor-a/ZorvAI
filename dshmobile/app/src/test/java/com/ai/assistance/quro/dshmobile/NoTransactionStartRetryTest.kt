package com.ai.assistance.quro.dshmobile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.14.10 审计 §6.1 回归：**无快照事务在场**时的启动请求必须有界自动重试。
 *
 * 缺陷形态（审计 §6.1）：
 * `deferEngineStartWhileSnapshotTransactionBusy()` 的「无事务」分支只写一行
 * `engine-start-deferred-no-transaction` 然后 return，文案还宣称「下一次入口会重试」——
 * 而**没有任何自动续跑**。新机 boot-fail.log 里该行反复新增，每一次都是一次被静默丢弃的
 * 启动请求（用户按了按钮 / 回到前台，引擎却没起来）。
 *
 * 修法边界（必须在测试里守住，否则容易把两条等待者混成一个）：
 *  - 无事务分支新起的是一个**秒级**有界重试（200ms 步进、3 秒预算），等的是**本实例上一次
 *    启动流的收尾**（flowOwnership）；
 *  - 有事务那条仍是**16 分钟**预算、判据是跨进程 journal 文件——两条**不得**合并。
 *
 * 判据全部是纯函数（[decideNoTransactionRetry]）与常量断言，不 sleep 真时间、不碰 Android 类。
 */
class NoTransactionStartRetryTest {

  // ── ① 决策：生命周期闸门最先 ─────────────────────────────────────────────────

  /**
   * destroyed / 用户已关引擎 / 维修态 ⇒ **STOP**。
   *
   * 这是审计原文点名「必须保持」的语义：生命周期终结时保持不启动。判据顺序若被改成
   * 「先看预算」或「先看所有权」，就会出现「快超时了于是硬启动一次」这种越过闸门的行为。
   */
  @Test
  fun theLifecycleGateIsEvaluatedFirstAndStopsEverything() {
    // 即便所有权看起来空着、也没有事务在场，canRun=false 也必须 STOP（不得 RESUME）。
    assertEquals(
      DeferredStartRetryDecision.STOP,
      decideNoTransactionRetry(canRun = false, transactionPresent = false, ownershipRunning = false, attempts = 0),
    )
    // 即便已经到了超时拍，canRun=false 仍必须是 STOP（不得落成 TIMEOUT 再去写日志/启动）。
    assertEquals(
      DeferredStartRetryDecision.STOP,
      decideNoTransactionRetry(canRun = false, transactionPresent = true, ownershipRunning = true, attempts = 99),
    )
  }

  // ── ② 事务出现 ⇒ 转交给 16 分钟那条（两条等待者不得混成一个） ────────────────

  /**
   * **核心边界**：无事务重试期间如果事务出现了（上一次流推进到解压），必须**转交**给
   * [deferredStartRunnable] 那条 16 分钟等待者，而不是自己继续按秒级预算空等。
   *
   * 反证：若不转交，3 秒后就会写一条「没抢到所有权」的 timeout，而实际上刷新正要跑
   * 8-12 分钟——用户拿到的诊断是错的。
   */
  @Test
  fun anAppearingTransactionIsHandedOverToTheLongWaiter() {
    assertEquals(
      DeferredStartRetryDecision.HAND_OVER_TO_TRANSACTION_WAIT,
      decideNoTransactionRetry(canRun = true, transactionPresent = true, ownershipRunning = false, attempts = 0),
    )
    // 事务在场优先于「所有权空出」：即便所有权空了，也要先交给事务等待者（它会同时判两个条件）。
    assertEquals(
      DeferredStartRetryDecision.HAND_OVER_TO_TRANSACTION_WAIT,
      decideNoTransactionRetry(canRun = true, transactionPresent = true, ownershipRunning = false, attempts = 99),
    )
    assertEquals(
      DeferredStartRetryDecision.HAND_OVER_TO_TRANSACTION_WAIT,
      decideNoTransactionRetry(canRun = true, transactionPresent = true, ownershipRunning = true, attempts = 0),
    )
  }

  // ── ③ 所有权空出 ⇒ 立刻续跑（不等满预算） ────────────────────────────────────

  /**
   * 所有权一空出来就续跑，**不等到预算用尽**：等待本身没有价值，抢到就是抢到了。
   *
   * 这条正是「自动续跑」的实质判据：旧实现在这里根本没有续跑路径，所以无论传什么参数都
   * 只能「丢掉」。
   */
  @Test
  fun freeOwnershipResumesImmediatelyWithoutWaitingOutTheBudget() {
    for (attempts in listOf(0, 1, 5, 14)) {
      assertEquals(
        "第 " + attempts + " 拍时所有权空出，必须立刻续跑",
        DeferredStartRetryDecision.RESUME,
        decideNoTransactionRetry(canRun = true, transactionPresent = false, ownershipRunning = false, attempts = attempts),
      )
    }
    // 预算已耗尽的那一拍同样如此：所有权空了就该续跑，而不是先记一条 timeout。
    assertEquals(
      DeferredStartRetryDecision.RESUME,
      decideNoTransactionRetry(canRun = true, transactionPresent = false, ownershipRunning = false, attempts = 99),
    )
  }

  // ── ④ 有界：始终被占 ⇒ 到预算收手并如实记 timeout ───────────────────────────

  /**
   * 所有权**始终**被占 ⇒ 有界收手。
   *
   * 反证「无限重试」：拍数无限增长时决策必须变成 TIMEOUT，且**边界精确**——
   * 预算 3000ms / 步进 200ms ⇒ 第 15 拍（3000ms）起判超时，第 14 拍（2800ms）仍在预算内。
   */
  @Test
  fun anEndlessOwnerIsBoundedByTheBudget() {
    assertEquals(
      DeferredStartRetryDecision.RETRY,
      decideNoTransactionRetry(canRun = true, transactionPresent = false, ownershipRunning = true, attempts = 0),
    )
    assertEquals(
      "第 14 拍 = 2800ms，仍在 3000ms 预算内",
      DeferredStartRetryDecision.RETRY,
      decideNoTransactionRetry(canRun = true, transactionPresent = false, ownershipRunning = true, attempts = 14),
    )
    assertEquals(
      "第 15 拍 = 3000ms，恰好到预算",
      DeferredStartRetryDecision.TIMEOUT,
      decideNoTransactionRetry(canRun = true, transactionPresent = false, ownershipRunning = true, attempts = 15),
    )
    assertEquals(
      DeferredStartRetryDecision.TIMEOUT,
      decideNoTransactionRetry(canRun = true, transactionPresent = false, ownershipRunning = true, attempts = Int.MAX_VALUE),
    )
  }

  /** 负的拍数（注入错误）不得被当成「已经等过很久」而提前超时，也不得产出 RETRY 以外的行为。 */
  @Test
  fun aNegativeAttemptCountIsTreatedAsTheFirstTick() {
    assertEquals(
      DeferredStartRetryDecision.RETRY,
      decideNoTransactionRetry(canRun = true, transactionPresent = false, ownershipRunning = true, attempts = -5),
    )
  }

  // ── ⑥bis 刷新在飞（journal 未落的解压段）⇒ 同样交棒，不烧秒级预算 ──────────────

  /**
   * 2026-10-30 诊断包修复（dsh-diag-20261030-025158.zip）：`beginSnapshotTransaction()`
   * 只置 companion CAS、**不写 journal**，marker 要到解压完成后的 STAGED 相位才落盘——
   * 那 45-60s 里 `readMarker()` 恒为 null，而所有权正被刷新流占着。旧实现把这整段误判成
   * 「上一次流收尾，毫秒级」，秒级预算烧穿后落一条误导的 timeout；诊断包 boot-fail.log
   * 实证 4 条 `engine-start-deferred-no-transaction-timeout`（transaction=absent），
   * 每一次对应一次被静默丢弃的启动按压。
   *
   * 本条守住：**无 journal 但刷新在飞必须交棒**——不得 RESUME、不得 RETRY 到超时。
   */
  @Test
  fun anInFlightRefreshWithoutJournalHandsOverInsteadOfBurningTheShortBudget() {
    // 主场景：无事务、所有权被占（刷新流持有）、刷新在飞 ⇒ 必须交棒给 16 分钟等待者。
    assertEquals(
      DeferredStartRetryDecision.HAND_OVER_TO_TRANSACTION_WAIT,
      decideNoTransactionRetry(
        canRun = true, transactionPresent = false, ownershipRunning = true,
        attempts = 0, refreshInFlight = true,
      ),
    )
    // 反证：即便所有权看起来空着（另一条控制面在刷新、没持所有权），也不得 RESUME ——
    // 此刻续跑只会撞上 beginSnapshotTransaction 让行，把「别人正在做」记成一次失败。
    assertEquals(
      DeferredStartRetryDecision.HAND_OVER_TO_TRANSACTION_WAIT,
      decideNoTransactionRetry(
        canRun = true, transactionPresent = false, ownershipRunning = false,
        attempts = 0, refreshInFlight = true,
      ),
    )
    // 反证：到了超时拍同样交棒（不得先落一条误导的 no-transaction-timeout）。
    assertEquals(
      DeferredStartRetryDecision.HAND_OVER_TO_TRANSACTION_WAIT,
      decideNoTransactionRetry(
        canRun = true, transactionPresent = false, ownershipRunning = true,
        attempts = Int.MAX_VALUE, refreshInFlight = true,
      ),
    )
    // 生命周期闸门仍然最先：destroyed / 用户关引擎时一律 STOP，刷新在飞也不例外。
    assertEquals(
      DeferredStartRetryDecision.STOP,
      decideNoTransactionRetry(
        canRun = false, transactionPresent = false, ownershipRunning = true,
        attempts = 0, refreshInFlight = true,
      ),
    )
  }

  /** 默认口径不变：不传 refreshInFlight（刷新不在飞）时，行为与本修复前完全一致。 */
  @Test
  fun theRefreshCriterionDefaultsToAbsentAndKeepsTheOldBehaviour() {
    assertEquals(
      DeferredStartRetryDecision.RESUME,
      decideNoTransactionRetry(canRun = true, transactionPresent = false, ownershipRunning = false, attempts = 0),
    )
    assertEquals(
      DeferredStartRetryDecision.RETRY,
      decideNoTransactionRetry(
        canRun = true, transactionPresent = false, ownershipRunning = true, attempts = 0, refreshInFlight = false,
      ),
    )
    assertEquals(
      DeferredStartRetryDecision.TIMEOUT,
      decideNoTransactionRetry(
        canRun = true, transactionPresent = false, ownershipRunning = true, attempts = 15, refreshInFlight = false,
      ),
    )
  }

  // ── ⑤ 预算口径：秒级、且**不得**与 16 分钟那条混用 ───────────────────────────

  /**
   * 两条等待者的口径必须能明确区分，且本条的预算必须落在「秒级」。
   *
   * 反证「把两条混成一个」：若有人把 [NO_TRANSACTION_RETRY_BUDGET_MS] 改成 16 分钟那条的值
   * （或直接复用它），用户点完按钮会对着「正在启动引擎…」空等十几分钟，本条即红。
   */
  @Test
  fun theShortBudgetIsSecondsAndNeverReusesTheLongTransactionBudget() {
    assertEquals("步进必须是审计建议的 200ms", 200L, NO_TRANSACTION_RETRY_STEP_MS)
    assertTrue(
      "预算必须在数秒级（1s < budget <= 10s）：实际 " + NO_TRANSACTION_RETRY_BUDGET_MS,
      NO_TRANSACTION_RETRY_BUDGET_MS in 1_000L..10_000L,
    )
    assertNotEquals(
      "无事务预算**不得**等于 16 分钟事务等待预算（那正是「两条混成一个」的形态）",
      SNAPSHOT_TRANSACTION_WAIT_BUDGET_MS, NO_TRANSACTION_RETRY_BUDGET_MS,
    )
    assertTrue(
      "无事务预算必须比 16 分钟那条小三个数量级以上：短等待 vs 解压等待",
      NO_TRANSACTION_RETRY_BUDGET_MS * 100 < SNAPSHOT_TRANSACTION_WAIT_BUDGET_MS,
    )
    assertTrue("16 分钟那条必须原样保留（本条修复不得动它）", SNAPSHOT_TRANSACTION_WAIT_BUDGET_MS == 16 * 60_000L)
    assertTrue(
      "步进必须能整除预算（否则边界拍判据会漂）：budget=" + NO_TRANSACTION_RETRY_BUDGET_MS,
      NO_TRANSACTION_RETRY_BUDGET_MS % NO_TRANSACTION_RETRY_STEP_MS == 0L,
    )
    assertEquals(
      "预算内应恰好有 " + (NO_TRANSACTION_RETRY_BUDGET_MS / NO_TRANSACTION_RETRY_STEP_MS) + " 拍重试机会",
      15L, NO_TRANSACTION_RETRY_BUDGET_MS / NO_TRANSACTION_RETRY_STEP_MS,
    )
  }

  // ── ⑥ 接线层：分支真的排了重试（撤掉修复即红） ──────────────────────────────

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

  /**
   * **本文件的核心反证**：无事务分支不得再「只记账就 return」。
   *
   * 旧形态（缺陷原句）是：写 boot-fail 之后紧跟着 `return`，中间没有任何 postDelayed。
   */
  @Test
  fun theNoTransactionBranchSchedulesAnAutomaticRetry() {
    val flow = codeOnly(source("EngineStartFlow.kt"))
    val defer = flow.substringAfter("private fun deferEngineStartWhileSnapshotTransactionBusy()")
      .substringBefore("private val deferredStartRunnable")

    assertTrue("必须仍留痕（不得因为加了重试就不记账）", defer.contains("\"engine-start-deferred-no-transaction\""))
    assertTrue(
      "无事务分支必须真的排下重试者（旧实现只写日志然后 return）",
      defer.contains("engineMonitorHandler.postDelayed(noTransactionRetry, NO_TRANSACTION_RETRY_STEP_MS)"),
    )
    assertFalse(
      "文案不得再宣称「下一次入口会重试」而实际不重试（旧文案与新行为不符）",
      defer.contains("下一次入口（点按钮 / 回前台探活）会重试"),
    )
    // 重试者本体必须走纯判据，且五种决策全部被处理。
    val runnable = flow.substringAfter("private val noTransactionRetry")
      .substringBefore("private fun canRunEngineWork")
    assertTrue("重试者必须走 decideNoTransactionRetry", runnable.contains("decideNoTransactionRetry("))
    for (decision in listOf("RESUME", "TIMEOUT", "HAND_OVER_TO_TRANSACTION_WAIT", "RETRY", "STOP")) {
      assertTrue("重试者必须处理决策 " + decision, runnable.contains("DeferredStartRetryDecision." + decision))
    }
    assertTrue("续跑必须调用 start()", runnable.contains("start()"))
    assertTrue("超时必须留一条 boot-fail", runnable.contains("\"engine-start-deferred-no-transaction-timeout\""))
    assertTrue(
      "重试者必须再过一次生命周期闸门（与 start 入口同一道）",
      runnable.contains("canRun = canRunEngineWork()"),
    )
  }

  /** 16 分钟那条等待者**一字未动**：判据仍是「事务收口 且 所有权空出」，预算仍是 16 分钟。 */
  @Test
  fun theLongTransactionWaiterIsLeftIntact() {
    val flow = codeOnly(source("EngineStartFlow.kt"))
    val long = flow.substringAfter("private val deferredStartRunnable")
      .substringBefore("private val noTransactionRetry")
    assertTrue("长等待者的续跑条件不得被改写", long.contains("!transactionBusy && !flowOwnership.running"))
    assertTrue("长等待者仍用 16 分钟预算", long.contains("SNAPSHOT_TRANSACTION_WAIT_BUDGET_MS"))
    assertFalse("长等待者不得改用秒级预算", long.contains("NO_TRANSACTION_RETRY_BUDGET_MS"))
  }

  // ── ⑦ 接线层：刷新在飞判据必须接进两条等待者（2026-10-30 诊断包修复） ──────────

  /**
   * **本修复的核心反证**：无事务分支必须**先判刷新在飞**再落秒级重试，且判据要真的接到
   * companion CAS（`EngineManager.snapshotRefreshInFlight()`）上——写成本地字段或常量即红。
   *
   * 判据顺序同样必须是反证点：刷新判据排在秒级重试**之后**，等于解压段整段没判上。
   */
  @Test
  fun theRefreshInFlightCriterionIsWiredIntoBothWaiters() {
    val flow = codeOnly(source("EngineStartFlow.kt"))
    val defer = flow.substringAfter("private fun deferEngineStartWhileSnapshotTransactionBusy()")
      .substringBefore("private val deferredStartRunnable")

    assertTrue(
      "无事务分支必须先判刷新在飞（判据缺失 = 解压段 45-60s 整段误判）",
      defer.contains("activity.engineManager.snapshotRefreshInFlight()"),
    )
    assertTrue(
      "刷新判据必须排在秒级重试**之前**（排在后面等于没判）",
      defer.indexOf("snapshotRefreshInFlight()") < defer.indexOf("postDelayed(noTransactionRetry"),
    )
    assertTrue("刷新在飞必须落专属 boot-fail stage", defer.contains("\"engine-start-deferred-refresh-in-flight\""))
    assertTrue(
      "刷新在飞必须挂 16 分钟那条等待者（而不是秒级重试）",
      defer.contains("engineMonitorHandler.postDelayed(deferredStartRunnable, SNAPSHOT_TRANSACTION_WAIT_POLL_STEP_MS)"),
    )

    val runnable = flow.substringAfter("private val noTransactionRetry")
      .substringBefore("private fun canRunEngineWork")
    assertTrue(
      "秒级重试者也必须把刷新在飞接进 decideNoTransactionRetry",
      runnable.contains("refreshInFlight = activity.engineManager.snapshotRefreshInFlight()"),
    )

    val long = flow.substringAfter("private val deferredStartRunnable")
      .substringBefore("private val noTransactionRetry")
    assertTrue(
      "16 分钟等待者的续跑条件也必须排除刷新在飞（否则所有权瞬间空出就续跑，必撞让行）",
      long.contains("!refreshInFlight"),
    )

    // 判据出口必须在 EngineManager 侧真实存在（companion CAS 的只读出口）。
    val manager = codeOnly(source("EngineManager.kt"))
    assertTrue(
      "EngineManager 必须暴露 snapshotRefreshInFlight()（读 companion snapshotRefreshing）",
      manager.contains("fun snapshotRefreshInFlight(): Boolean = snapshotRefreshing.get()"),
    )
  }
}
