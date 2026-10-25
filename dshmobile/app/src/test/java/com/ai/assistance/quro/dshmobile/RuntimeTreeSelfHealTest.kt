package com.ai.assistance.quro.dshmobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * task-79（Bug A）(b) 链接失败自愈的纯判据单测（JVM，可注入文本与预算状态）。
 *
 * 反证方式：**靠传参**（注入日志文本 / 预算位），不就地改生产源码。
 */
class RuntimeTreeSelfHealTest {

  /**
   * 正向：真机 logcat 的原文必须被识别。
   *
   * 原文取自用户报障附件（小米 25079RPDCC / Android 36 / arm64 / 0.14.2-fx-1）：
   *   F linker  : CANNOT LINK EXECUTABLE "/data/user/0/com.ai.assistance.quro/files/usr/bin/node":
   *               library "libz.so.1" not found: needed by main executable
   */
  @Test
  fun `real device CANNOT LINK line is detected`() {
    val real = "F linker  : CANNOT LINK EXECUTABLE \"/data/user/0/com.ai.assistance.quro/files/usr/bin/node\": " +
      "library \"libz.so.1\" not found: needed by main executable"
    assertTrue("真机原文必须被识别", RuntimeTree.snapshotLinkFailure(real))
  }

  @Test
  fun `library not found form is detected even without CANNOT LINK`() {
    // 某些机型/版本只打后一种措辞 ⇒ 只认 CANNOT LINK 会在那些机型上漏判。
    val onlyLib = "library \"libsqlite3.so\" not found"
    assertTrue("缺库形态必须被识别", RuntimeTree.snapshotLinkFailure(onlyLib))
  }

  /** 反证核心：不能把「普通 not found」误判成树损坏（那会白付一次全量重抽取）。 */
  @Test
  fun `counter-proof generic not found must not be treated as tree damage`() {
    val pluginMissing = "failed to import loader entry foo (some-plugin): module not found"
    assertFalse("插件 not found 不得当成动态链接失败", RuntimeTree.snapshotLinkFailure(pluginMissing))
    assertFalse("空日志", RuntimeTree.snapshotLinkFailure(""))
    assertFalse("null 日志", RuntimeTree.snapshotLinkFailure(null))
    assertFalse("普通启动失败", RuntimeTree.snapshotLinkFailure("engine exited with code 1"))
  }

  /** 预算：本次运行已自愈过 ⇒ 不再允许（避免重抽取死循环）。 */
  @Test
  fun `self heal is allowed only once per app run`() {
    assertTrue("第一次必须允许", RuntimeTree.maySelfHeal(alreadyHealedThisRun = false))
    assertFalse("第二次必须拒绝（否则重抽取→再失败→再重抽取的死循环）", RuntimeTree.maySelfHeal(alreadyHealedThisRun = true))
  }

  @Test
  fun `damage marker name is the shell side ledger not the engine log`() {
    // 为什么单列一条：engine.log 每次 spawn 被 redirectOutput 截断 ⇒ 不能当账本；标记必须是独立文件。
    assertTrue("标记必须是独立文件", RuntimeTree.DAMAGE_MARKER.isNotBlank())
    assertFalse("不得复用 engine.log（会被截断）", RuntimeTree.DAMAGE_MARKER.contains("engine.log"))
  }

  /**
   * 标记**必须有读取方**（Lead 裁决：否则是死账本——写进去没人看得见）。
   *
   * 这里做源码级断言（唯一性）：production 里除了「写（EngineStartFlow）」「清（EngineStartFlow）」之外，
   * 必须存在**至少一处读取**并把它带进诊断包（EngineManager 的 info.txt `runtime_tree_damage` 行）。
   * 为什么这条只能源码级：读取方的效果是「诊断包文本里多一行」，而诊断包生成依赖 Context/文件系统，
   * 本仓测试面无 Robolectric ⇒ 用「调用点在场」把它钉住（与 `BootFailLogTest` 的源码级接线断言同族）。
   */
  @Test
  fun `damage marker has a reader wired into the diagnostics package`() {
    val em = java.io.File("src/main/java/com/ai/assistance/quro/dshmobile/EngineManager.kt")
      .takeIf { it.isFile }
      ?: java.io.File("app/src/main/java/com/ai/assistance/quro/dshmobile/EngineManager.kt")
    val text = em.readText()
    assertTrue(
      "标记必须被读取（否则是死账本）",
      text.contains("DAMAGE_MARKER") && text.contains("runtime_tree_damage"),
    )
    assertTrue(
      "必须把标记带进诊断包 info.txt（存在才有该行）",
      text.contains("runtime_tree_damage") && text.contains("buildDiagnosticsText"),
    )
  }
}
