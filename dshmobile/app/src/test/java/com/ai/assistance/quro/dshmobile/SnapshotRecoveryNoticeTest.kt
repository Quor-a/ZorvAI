package com.ai.assistance.quro.dshmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.14.2-fx-2 缺口：快照恢复期「拒绝回滚」必须有用户可见上报。
 *
 * 缺陷现场：`applyRecovery` 的 ROLLBACK_FAILED 只写 logcat + `pendingRecoveryFailure`，
 * 没有任何面向 UI 的通道 ⇒ 数据被保护了（拒绝把空备份搬回去）但用户完全不知情。
 * 对照：`refreshSnapshot` 失败那条有完整上报（writeBootFail + 错误页 + 诊断包）。
 *
 * 判据落点：`SnapshotRecoveryNotice.forRejection` 是纯函数，测试直接驱动它；
 * 接线由源码级断言钉住（本仓测试面无 Robolectric，Activity 依赖不可实例化——
 * 与 `BootFailLogTest` 的接线断言同族）。**不就地改生产源码**。
 */
class SnapshotRecoveryNoticeTest {

  /** 正向：有拒绝明细 ⇒ 必须产出上报（标题/原因都在）。 */
  @Test
  fun `recovery rejection produces a user visible notice`() {
    val notice = SnapshotRecoveryNotice.forRejection("备份是空的（半份/未完成残渣）")
    assertNotNull("拒绝必须产出上报（否则用户不知情）", notice)
    assertEquals("稳定码必须固定（供 grep 归类）", "snapshot-recovery-rejected", notice!!.code)
    assertTrue("标题必须说清「被拒绝」", notice.title.contains("拒绝"))
    assertTrue("原因必须出现在文案里（可归因）", notice.hint.contains("备份是空的"))
  }

  /**
   * 文案纪律 1：必须告诉用户**数据没被动**。
   *
   * 为什么单列一条：拒绝的全部意义就是「保护现场而不替换」。若只说「被拒绝」，
   * 用户会以为自己数据已经坏了/被删了——那是比不提示更坏的体验。
   */
  @Test
  fun `notice states that user data was left untouched`() {
    val notice = SnapshotRecoveryNotice.forRejection("半份备份")!!
    assertTrue("必须说明未做替换", notice.hint.contains("未做任何替换") || notice.hint.contains("未做替换"))
    assertTrue("必须说明已保留", notice.hint.contains("保留"))
  }

  /**
   * 文案纪律 2：不得越界承诺（lead 明令）。
   *
   * 恢复失败可能来自半份备份/属主异常等，壳侧无从保证下一次一定收敛。
   */
  @Test
  fun `notice does not promise that retry will fix it`() {
    val hint = SnapshotRecoveryNotice.forRejection("半份备份")!!.hint
    for (word in listOf("一定能", "保证", "必定", "一定可以", "肯定能")) {
      assertFalse("不得出现承诺词: " + word, hint.contains(word))
    }
    assertTrue("必须给出去哪看（可排查）", hint.contains("控制台") || hint.contains("诊断包"))
  }

  /**
   * 反向（最关键的一条）：**没有拒绝就不得造提示**。
   *
   * 若这里返回非 null，正常启动的用户每次都会看到一条虚假的「更新被拒绝」——
   * 那比不提示更坏（狼来了）。
   */
  @Test
  fun `no rejection means no notice`() {
    assertNull("null 明细不得产出提示", SnapshotRecoveryNotice.forRejection(null))
    assertNull("空串明细不得产出提示", SnapshotRecoveryNotice.forRejection(""))
    assertNull("纯空白明细不得产出提示", SnapshotRecoveryNotice.forRejection("   "))
  }

  /** 用户可见文案不得含 markdown 记号（会原样显示成星号）。 */
  @Test
  fun `notice text carries no markdown markers`() {
    val n = SnapshotRecoveryNotice.forRejection("半份备份")!!
    assertFalse("标题不得含 markdown", n.title.contains("**"))
    assertFalse("文案不得含 markdown", n.hint.contains("**"))
  }

  // ── 一次性标记：读/删分离（lead 硬要求 1）────────────────────────────────────

  /** 有标记 + 注入成功 ⇒ 记内存且**删标记**（跨轮次不重复唠叨）。 */
  @Test
  fun `successful injection consumes the marker and remembers the text`() {
    SnapshotRecoveryNotice.forget()
    var consumed = false
    val delivered = SnapshotRecoveryNotice.deliver(
      pending = "标题\n正文",
      inject = { true },
      consumeOnSuccess = { consumed = true },
    )
    assertTrue("有标记必须尝试注入", delivered)
    assertTrue("注入成功必须删标记（否则以后每次启动都唠叨）", consumed)
    assertEquals("必须记内存（供页面重载后重注）", "标题\n正文", SnapshotRecoveryNotice.inProcessText)
    SnapshotRecoveryNotice.forget()
  }

  /**
   * 反向（lead 硬要求 1 的关键一半）：**注入失败 ⇒ 标记不得删**。
   *
   * 若这里删了，那次失败就把提示永久吞掉：用户没看到、也没有下一次机会。
   */
  @Test
  fun `failed injection keeps the marker for a later retry`() {
    SnapshotRecoveryNotice.forget()
    var consumed = false
    SnapshotRecoveryNotice.deliver(
      pending = "标题\n正文",
      inject = { false },
      consumeOnSuccess = { consumed = true },
    )
    assertFalse("注入失败**不得**删标记", consumed)
    assertNull("注入失败不得记内存（否则重载会把失败当成功重注）", SnapshotRecoveryNotice.inProcessText)
  }

  /** 注入抛异常同样不得删标记、不得让调用方崩。 */
  @Test
  fun `inject throwing is treated as failure without consuming`() {
    SnapshotRecoveryNotice.forget()
    var consumed = false
    SnapshotRecoveryNotice.deliver(
      pending = "标题\n正文",
      inject = { throw IllegalStateException("webview gone") },
      consumeOnSuccess = { consumed = true },
    )
    assertFalse("抛异常不得删标记", consumed)
  }

  /** 无标记 ⇒ 不注入、不删（避免凭空造告警）。 */
  @Test
  fun `no marker means no delivery attempt`() {
    var injected = false
    val delivered = SnapshotRecoveryNotice.deliver(
      pending = null,
      inject = { injected = true; true },
      consumeOnSuccess = {},
    )
    assertFalse("无标记不得尝试注入", delivered)
    assertFalse("无标记不得调用注入", injected)
  }

  // ── 注入脚本：不伤页面（lead 硬要求 3）──────────────────────────────────────

  /** 不遮挡交互 + 不自动隐藏 + 固定 id（重复注入先删旧节点）。 */
  @Test
  fun `injection script is non blocking and never auto hides`() {
    val js = SnapshotRecoveryNotice.injectionScript("标题", "正文")
    assertTrue("必须 pointer-events:none（不遮挡核心交互）", js.contains("pointer-events:none"))
    assertTrue("必须有稳定 id", js.contains(SnapshotRecoveryNotice.DOM_ID))
    assertTrue("必须 position:fixed", js.contains("position:fixed"))
    assertTrue("重复注入必须先移除旧节点（防叠字）", js.contains("removeChild"))
    assertFalse("不得 setInterval", js.contains("setInterval"))
    assertFalse("不得 setTimeout（自动隐藏/自动消失）", js.contains("setTimeout"))
  }

  /** 文案里的引号/反斜杠/换行必须被转义（否则整段脚本语法错误 ⇒ 静默失败）。 */
  @Test
  fun `script escapes quotes backslashes and newlines`() {
    val js = SnapshotRecoveryNotice.injectionScript("含有\"引号\"的标题", "第一行\n第二行\\反斜杠")
    assertTrue("引号必须转义", js.contains("\\\"引号\\\""))
    assertTrue("换行必须转义为 \\n", js.contains("第一行\\n第二行"))
    assertTrue("反斜杠必须转义", js.contains("\\\\反斜杠"))
    assertFalse("裸换行不得进入脚本字面量", js.contains("第一行\n"))
  }

  /** 标记拆分：标题/正文各归其位（否则重载后标题会被当正文再显示一遍）。 */
  @Test
  fun `marker splits into title and body`() {
    val (t, b) = SnapshotRecoveryNotice.splitMarker("运行时更新被拒绝\n已保留数据")
    assertEquals("标题", "运行时更新被拒绝", t)
    assertEquals("正文", "已保留数据", b)
    val (t2, b2) = SnapshotRecoveryNotice.splitMarker("只有一行")
    assertEquals("单行时用默认标题", SnapshotRecoveryNotice.DEFAULT_TITLE, t2)
    assertEquals("单行整行当正文", "只有一行", b2)
  }

  // ── 收敛即复位（lead 硬要求 2）──────────────────────────────────────────────

  /**
   * 收敛 ⇒ 应复位；未收敛（ROLLBACK_FAILED）⇒ 保持。
   *
   * 缺陷形态：`pendingRecoveryFailure` 原先从不清空 ⇒ 历史上拒绝过一次后，
   * 每次启动都命中陈旧明细（诊断面被污染 + 提示变常驻假告警）。
   */
  @Test
  fun `pending failure is cleared when recovery converged`() {
    assertTrue("NONE/DISCARDED/ROLLED_BACK/ROLLED_FORWARD 都算收敛 ⇒ 复位", SnapshotRecoveryNotice.recoveryConverged(false))
    assertFalse("ROLLBACK_FAILED 未收敛 ⇒ 保持（仍要上报）", SnapshotRecoveryNotice.recoveryConverged(true))
  }

  /**
   * 接线断言：复位判据必须由 EngineManager 的恢复入口消费（源码级）。
   *
   * 防「纯函数写好了但生产代码仍用旧的不复位逻辑」——那正是本缺陷的原始形态。
   */
  @Test
  fun `engine manager consults the convergence predicate`() {
    val src = java.io.File("src/main/java/com/ai/assistance/quro/dshmobile/EngineManager.kt")
      .takeIf { it.isFile }
      ?: java.io.File("app/src/main/java/com/ai/assistance/quro/dshmobile/EngineManager.kt")
    val text = src.readText()
    assertTrue("恢复入口必须问收敛判据", text.contains("recoveryConverged"))
    assertTrue("必须有清空动作", text.contains("pendingRecoveryFailure = null"))
  }

  /**
   * 接线断言：恢复入口必须调用上报函数，且**上报函数自身**做对三件事（源码级）。
   *
   * 为什么只能源码级：上报效果要 Activity + 文件系统；本仓测试面无 Robolectric。
   * 这条防的是「纯函数写好了但没人调」——正是本缺口的原始形态（逻辑在、通道无）。
   *
   * ── 断言作用域纪律（这条本身是我踩过的坑）────────────────────────────────────
   * 初版断言写成 `text.contains("GuidePhase.Error")`，而**全文件别处**（refreshSnapshot 失败、
   * 引擎启动失败等）本来就含这个串 ⇒ 该断言**恒为真**（为错误的原因通过），锁不住本路径的设计。
   * 现在改成**只在上报函数自己的函数体里**断言：从函数声明切到下一个顶格 `}`，其余行不参与。
   */
  @Test
  fun `recovery entry point wires the rejection notice into the failure surface`() {
    val text = engineStartFlowSource()
    // ① 调用点：恢复入口必须调用上报函数（传的是恢复明细）。
    assertTrue(
      "恢复入口必须调用上报函数",
      text.contains("reportRecoveryRejectionIfAny(activity, activity.engineManager.pendingRecoveryFailure)"),
    )
    // ② 上报函数**体内**必须：落结构化码 + 写一次性标记 + 镜像诊断包。
    val body = functionBody(text, "private fun reportRecoveryRejectionIfAny")
    assertTrue("必须走既有失败通道 writeBootFail", body.contains("writeBootFail"))
    assertTrue("必须落一次性标记（供页面注入）", body.contains("SnapshotRecoveryNotice.markPending"))
    assertTrue("必须镜像诊断包", body.contains("mirrorDiagnosticsToShared"))
    assertTrue("结构化码必须来自单一真源", body.contains("notice.code"))
    // ③ **反向**：该函数体内**不得**切引导页相位 —— 这是 lead 已裁决的设计取舍。
    //    拒绝不阻断启动，紧随其后就会被 Starting/showWeb 覆盖 ⇒ 只留下一闪而过的错误页。
    //    把取舍钉成断言，防止将来有人「顺手补一个错误页」把体验改坏。
    assertFalse("不得在恢复上报里切引导页相位（会被 showWeb 覆盖成一闪而过）", body.contains("applyGuidePhase"))
    assertFalse("不得在恢复上报里 showGuide", body.contains("showGuide"))
  }

  @Test
  fun unresolvedFactoryOrOnlineRecoveryBlocksProbeAndRuntimeStart() {
    assertTrue(SnapshotRecoveryNotice.blocksRuntimeStart("usr rollback failed", true))
    assertTrue(SnapshotRecoveryNotice.blocksRuntimeStart("unknown transaction purpose", true))
    assertFalse(SnapshotRecoveryNotice.blocksRuntimeStart(null, true))
    assertFalse(SnapshotRecoveryNotice.blocksRuntimeStart("stale detail after convergence", false))
  }

  /** 读取 EngineStartFlow.kt（兼容从 apk 仓根或 apk 目录运行测试两种 cwd）。 */
  private fun engineStartFlowSource(): String {
    val f = java.io.File("src/main/java/com/ai/assistance/quro/dshmobile/EngineStartFlow.kt")
      .takeIf { it.isFile }
      ?: java.io.File("app/src/main/java/com/ai/assistance/quro/dshmobile/EngineStartFlow.kt")
    return f.readText()
  }

  /**
   * 截取某函数的**函数体文本**（从声明行到下一个顶格的右花括号），用于把断言限定在该函数内。
   *
   * 为什么需要：源码级断言在整文件范围内极易写成恒真（同名字符串在别处存在）。
   * 本仓已有实锤教训：文本断言锁不住判据。作用域收窄是最低成本的补救。
   */
  private fun functionBody(text: String, signature: String): String {
    val start = text.indexOf(signature)
    if (start < 0) return ""
    val end = text.indexOf("\n}", start)
    return if (end < 0) text.substring(start) else text.substring(start, end)
  }
}
