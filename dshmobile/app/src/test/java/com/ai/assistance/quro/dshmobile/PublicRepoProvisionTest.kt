package com.ai.assistance.quro.dshmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 公共导出目录（`Documents/dshdata`）供给结果与展示口径的行为回归（0.14.1 用户反馈）。
 *
 * 缺陷：`Documents` 下没有 `dshdata`，导出与日志「没有输出渠道」。定位后的机制是供给动作
 * **挂在引擎启动路径上**——`startEngine()` 在「引擎已可连或进程还活着」时早退会整段跳过它，
 * 而 `onResume` 的探活发现引擎活着就不走启动流程，于是**授权之后没有任何东西会再跑一次**；
 * 同时失败只 `Log.w`（用户侧不可见），引导页的存储 chip 在 API<30 上一律显示「已授权」。
 *
 * 本类的判据全部落在**纯逻辑**上（不需要 Robolectric）：
 *  - [PublicRepoProvision.needsRetry]：除 OK 外都要重试 —— 这正是本缺陷缺掉的性质；
 *  - [PublicRepoProvision.presentation]：**只有 OK 可以显示「已就绪」**；
 *  - [PublicRepoStatus.fromWire]：畸形/未知落盘一律 UNKNOWN，**不得当成成功**。
 *
 * 反证方式：把 `needsRetry` 改成恒 false（退化为「失败后不再重试」），
 * 或把 `presentation` 的 UNKNOWN 分支改成 READY（退化为旧 chip 的谎），下面用例必红。
 */
class PublicRepoProvisionTest {

  // ── 可重试性（缺陷本体）──────────────────────────────────────────

  /** 核心反证：只要还没成功过就必须还能再试——旧实现失败后再无任何重试点。 */
  @Test
  fun everyStatusExceptOkStillWantsAnotherAttempt() {
    assertTrue("从未探测过：当然要试", PublicRepoProvision.needsRetry(PublicRepoStatus.UNKNOWN))
    assertTrue("缺授权：授权之后必须还能再试", PublicRepoProvision.needsRetry(PublicRepoStatus.NOT_AUTHORIZED))
    assertTrue("授权够了却失败：可能是一次瞬时错误，仍要能再试", PublicRepoProvision.needsRetry(PublicRepoStatus.FAILED))
    assertFalse("已就绪：幂等跳过，不必每次回前台都做文件系统操作", PublicRepoProvision.needsRetry(PublicRepoStatus.OK))
  }

  // ── 展示口径（文案与事实必须对齐）──────────────────────────────────

  /** 核心反证：**只有 OK 可以显示「已就绪」**——把未探测或失败渲染成已就绪就是本缺陷的文案面。 */
  @Test
  fun onlyOkIsPresentedAsReady() {
    assertEquals(PublicRepoPresentation.READY, PublicRepoProvision.presentation(PublicRepoStatus.OK))
    for (status in listOf(PublicRepoStatus.UNKNOWN, PublicRepoStatus.NOT_AUTHORIZED, PublicRepoStatus.FAILED)) {
      assertFalse(
        "非 OK 一律不得显示「已就绪」（$status）——界面说正常而目录不在，用户既看不到问题也没有授权入口",
        PublicRepoProvision.presentation(status) == PublicRepoPresentation.READY,
      )
    }
    assertEquals(
      "「尚未探测」必须有自己的口径（S1-9）：既不是就绪、也不是「去授权」——已授权用户被劝再授权一次，"
        + "点了之后什么都不会变，是另一种把状态说错的方向",
      PublicRepoPresentation.PENDING,
      PublicRepoProvision.presentation(PublicRepoStatus.UNKNOWN),
    )
    assertFalse(
      "PENDING 不得与 NEEDS_GRANT 合流",
      PublicRepoProvision.presentation(PublicRepoStatus.UNKNOWN) == PublicRepoPresentation.NEEDS_GRANT,
    )
  }

  /**
   * S1-8：存储 chip 的点击动作必须跟着状态走。
   *
   * 反证形态：把 WRITE_FAILED 改回 REQUEST_GRANT（旧行为）即判红——旧实现无论什么状态点击
   * 都走「请求授权」，而写入失败时授权本来就够，点它一定没有任何效果：用户手上唯一那个
   * 可点的东西是无效的。
   */
  @Test
  fun storageChipActionFollowsTheState() {
    assertEquals(StorageChipAction.NONE, storageChipAction(PublicRepoPresentation.READY))
    assertEquals(
      "未探测时唯一能改变现状的动作是再探一次",
      StorageChipAction.PROBE_AGAIN,
      storageChipAction(PublicRepoPresentation.PENDING),
    )
    assertEquals(StorageChipAction.REQUEST_GRANT, storageChipAction(PublicRepoPresentation.NEEDS_GRANT))
    assertEquals(
      "写入失败时授权已经够了——该给的是失败详情（拿去反馈/排查），不是再弹一次授权页",
      StorageChipAction.COPY_FAILURE_DETAIL,
      storageChipAction(PublicRepoPresentation.WRITE_FAILED),
    )
  }

  /** 「缺授权」与「授权够了但写不进去」必须分开——前者要授权入口，后者要查原因。 */
  @Test
  fun missingGrantAndWriteFailureAreDistinct() {
    assertEquals(
      PublicRepoPresentation.NEEDS_GRANT,
      PublicRepoProvision.presentation(PublicRepoStatus.NOT_AUTHORIZED),
    )
    assertEquals(
      PublicRepoPresentation.WRITE_FAILED,
      PublicRepoProvision.presentation(PublicRepoStatus.FAILED),
    )
  }

  // ── 落盘编解码 ───────────────────────────────────────────────────

  @Test
  fun theStatusLineRoundTrips() {
    val line = PublicRepoProvision.encode(PublicRepoStatus.NOT_AUTHORIZED, "onResume", "EACCES: denied", 1_790_000_000_000L)
    assertEquals(PublicRepoStatus.NOT_AUTHORIZED, PublicRepoProvision.parseStatus(line))
    assertEquals("onResume", PublicRepoProvision.parseTrigger(line))
    assertEquals("EACCES: denied", PublicRepoProvision.parseDetail(line))
  }

  /** 单行纪律：detail 里的换行必须折叠——未折叠会让整行在人工查看/grep 时被截断。 */
  @Test
  fun theDetailIsFoldedToOneLine() {
    val line = PublicRepoProvision.encode(PublicRepoStatus.FAILED, "onCreate", "first\r\nsecond\nthird", 1L)
    assertEquals("编码结果必须是单行", false, line.contains('\n'))
    assertEquals("第二行不得丢失", "first second third", PublicRepoProvision.parseDetail(line))
  }

  /** 畸形/空落盘一律 UNKNOWN：**不得**把「读不懂」当成「已就绪」。 */
  @Test
  fun aMalformedStatusLineIsNeverTreatedAsOk() {
    for (bad in listOf(null, "", "   ", "ok", "|", "unknown|", "wat|onResume|1|x", "OK|onResume|1|x")) {
      assertFalse(
        "畸形落盘不得解析成 OK：$bad",
        PublicRepoProvision.parseStatus(bad) == PublicRepoStatus.OK,
      )
    }
    assertEquals(PublicRepoStatus.UNKNOWN, PublicRepoProvision.parseStatus(null))
    assertEquals("OK 的大小写必须逐字匹配（避免把 'OK' 这种脏值当成功）", PublicRepoStatus.UNKNOWN, PublicRepoProvision.parseStatus("OK|t|1|d"))
  }

  @Test
  fun parseHelpersAreSafeOnMalformedInput() {
    assertEquals("", PublicRepoProvision.parseDetail(null))
    assertEquals("", PublicRepoProvision.parseDetail("ok"))
    assertEquals("", PublicRepoProvision.parseTrigger(null))
    assertEquals("", PublicRepoProvision.parseTrigger("ok"))
  }

  // ── 失败归类 ─────────────────────────────────────────────────────

  /** 授权不到位 → 要给授权入口；授权到位却写不进去 → 要查原因。两者不可混。 */
  @Test
  fun failureIsClassifiedByWhetherTheGrantLooksEnough() {
    assertEquals(
      "拿不到写公共目录的授权：不是「写入失败」，而是「还没授权」",
      PublicRepoStatus.NOT_AUTHORIZED,
      PublicRepoProvision.classifyFailure(publicWritableCapability = false),
    )
    assertEquals(
      "授权看起来够却写不进去（scoped storage / OEM / 只读挂载）：要如实记成写入失败",
      PublicRepoStatus.FAILED,
      PublicRepoProvision.classifyFailure(publicWritableCapability = true),
    )
  }

  // ── 授权路线（API<30 按了没反应的那条）─────────────────────────────

  /** 核心反证：API<30 上没有 All Files Access 这个权限模型，开它的系统页是空操作。 */
  @Test
  fun belowApi30TheGrantGoesThroughRuntimePermissions() {
    for (sdk in listOf(26, 28, 29)) {
      assertEquals(
        "API $sdk 必须走运行时 READ/WRITE（旧实现在这里直接 return，用户按了毫无反应）",
        PublicRepoProvision.GrantRoute.RUNTIME_STORAGE_PERMISSION,
        PublicRepoProvision.grantRoute(sdk),
      )
    }
  }

  @Test
  fun api30AndAboveOpensTheAllFilesAccessScreen() {
    for (sdk in listOf(30, 31, 34, 36)) {
      assertEquals(
        "API $sdk 走 All Files Access 系统页",
        PublicRepoProvision.GrantRoute.ALL_FILES_ACCESS_SCREEN,
        PublicRepoProvision.grantRoute(sdk),
      )
    }
  }

  // ── 线格式稳定性（跨版本读旧文件）────────────────────────────────

  /** wire 字符串是跨进程/跨版本契约，改名会让旧落盘读不出来（退化成 UNKNOWN 而不是错判成功）。 */
  @Test
  fun theWireFormatIsStable() {
    assertEquals("unknown", PublicRepoStatus.UNKNOWN.wire)
    assertEquals("ok", PublicRepoStatus.OK.wire)
    assertEquals("not-authorized", PublicRepoStatus.NOT_AUTHORIZED.wire)
    assertEquals("failed", PublicRepoStatus.FAILED.wire)
  }

  /** 落盘文件必须放**私有目录**：公共目录刚建失败时往它里面写结果必然也失败。 */
  @Test
  fun theStatusFileIsNotPlacedInThePublicRepo() {
    assertEquals(".public-repo-status", PublicRepoProvision.STATUS_FILE_NAME)
    assertFalse("结果文件名不得暗示公共目录（它落在 filesDir）", PublicRepoProvision.STATUS_FILE_NAME.contains('/'))
  }
}
