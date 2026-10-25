package com.ai.assistance.quro.dshmobile

/**
 * 公共导出目录（`Documents/dshdata`）的**供给结果**与展示口径（纯逻辑，JVM 可直接测）。
 *
 * ── 为什么需要它（0.14.1 用户反馈）────────────────────────────────────────────
 * 报障：`Documents` 下没有 `dshdata`，于是各种导出与日志「没有输出渠道」。定位后的真机制不是
 * 「失败被缓存所以永不重试」，而是**供给动作挂在引擎启动路径上**：
 *
 *  - `ensurePublicExportRepo` 只从 `EngineManager.startEngine()`（旧 `:929`）与 `shellEnv()`
 *    （只在真正 spawn 引擎时被调）进入，而 `startEngine()` 有两个早退会**整段跳过**它：
 *    「快照刷新进行中」与「引擎已可连或进程还活着」。`MainActivity.onCreate` 从不调用它。
 *  - 首次冷启动时它确实跑了一次——**跑在授权之前**（授权是用户之后手点的事），`mkdirs()` 失败；
 *    此后引擎被 `EngineService` 保活，`onResume` 的探活发现引擎活着就不再走启动流程，
 *    于是**授权之后没有任何东西会再跑一次**。0.14.0 退役内置 adb 时还显式删掉了
 *    「回前台同步 All Files Access 偏好」（`MainActivity` 的注释），原本可能承担这件事的钩子也没了。
 *  - 失败本身是**静默**的（只 `Log.w`），而引导页的存储 chip 在 API < 30 上一律显示「已授权」
 *    （判据写的是 `SDK_INT < 30 || isExternalStorageManager()`），等于告诉用户一切正常。
 *
 * 本模块把三件事从 Android 面剥离，使每条判据都能以行为对照在 JVM 上判红：
 *  ① 结果的状态机与落盘/解析（畸形一律 `UNKNOWN`，**不得当成成功**）；
 *  ② 「还要不要再试」——**除 [PublicRepoStatus.OK] 外都要重试**，这正是本缺陷缺掉的性质；
 *  ③ 展示口径与授权路线的映射（**除 OK 外没有任何一条路径可以显示「已就绪」**，坑 161 同族）。
 */
internal enum class PublicRepoStatus(val wire: String) {
  /** 从未成功供给过，也还没有一次可用的探测结果（含落盘畸形）。 */
  UNKNOWN("unknown"),

  /** 目录、`.nomedia`、`exports/`、README 都在场（**唯一**可显示「已就绪」的状态）。 */
  OK("ok"),

  /** 本机这条路上拿不到写公共目录的授权（API>=30 缺 All Files Access；API<30 缺运行时 WRITE）。 */
  NOT_AUTHORIZED("not-authorized"),

  /** 授权看起来是够的，却仍然写不进去（scoped storage、OEM 拦截、只读挂载等）。 */
  FAILED("failed"),
  ;

  companion object {
    fun fromWire(raw: String?): PublicRepoStatus =
      entries.firstOrNull { it.wire == raw?.trim() } ?: UNKNOWN
  }
}

/** 引导页 / 设置面对公共目录的四种展示口径。 */
internal enum class PublicRepoPresentation {
  /**
   * 还没探测过（冷启动早期 / 落盘畸形 / 落盘尚不存在）。
   *
   * S1-9：**既不能说「已就绪」，也不能劝用户去授权**。旧实现把它并进 NEEDS_GRANT，
   * 于是**已经授过权的用户**一进首屏就看到「去授权存储」——他点了之后什么也不会变
   * （授权本来就够），只能怀疑是不是自己点错了。事实是「我们还没探过」。
   */
  PENDING,
  READY,
  NEEDS_GRANT,
  WRITE_FAILED,
}

/**
 * 存储 chip 的点击动作（S1-8/S1-9；纯函数，JVM 可测）。
 *
 * 为什么把它抽出来：旧实现**无论什么状态**点击都走「请求授权」，而唯独 WRITE_FAILED
 * （授权看着够却写不进去）点它一定没有任何效果——用户手上唯一那个可点的东西是无效的。
 * 修法不是去掉点击，而是让动作跟着状态走：
 *  - 未探测 → 立刻再探一次（这是唯一能改变现状的动作）；
 *  - 需要授权 → 走授权（API 分流在 dirPickerController 里）；
 *  - 写入失败 → 把**失败详情**复制出来（授权已经够了，用户需要的是拿去反馈/自行排查的原文）。
 */
internal enum class StorageChipAction { NONE, PROBE_AGAIN, REQUEST_GRANT, COPY_FAILURE_DETAIL }

internal fun storageChipAction(presentation: PublicRepoPresentation): StorageChipAction = when (presentation) {
  PublicRepoPresentation.PENDING -> StorageChipAction.PROBE_AGAIN
  PublicRepoPresentation.READY -> StorageChipAction.NONE
  PublicRepoPresentation.NEEDS_GRANT -> StorageChipAction.REQUEST_GRANT
  PublicRepoPresentation.WRITE_FAILED -> StorageChipAction.COPY_FAILURE_DETAIL
}

internal object PublicRepoProvision {

  /** 结果落点文件名。**必须在应用私有目录**：公共目录刚建失败时，往它里面写结果必然也失败。 */
  const val STATUS_FILE_NAME = ".public-repo-status"

  /** 触发来源（写进落盘行，便于回答「最后一次尝试是谁触发的」）。 */
  const val TRIGGER_ON_CREATE = "onCreate"
  const val TRIGGER_ON_RESUME = "onResume"
  const val TRIGGER_ENGINE_START = "engineStart"

  /**
   * 落盘形态：`<status>|<trigger>|<epochMs>|<detail>` 单行。
   * detail 折叠换行——它是单行记录，未折叠的换行会让整行在人工查看与 grep 时被截断。
   */
  fun encode(status: PublicRepoStatus, trigger: String, detail: String, nowMs: Long): String =
    status.wire + "|" + trigger + "|" + nowMs + "|" + fold(detail)

  /**
   * 解析落盘结果。**要求四段齐全**：半截/畸形文件一律 [PublicRepoStatus.UNKNOWN]。
   * 只取首段会让一个被截断成 `ok` 的文件被读成「已就绪」——那正是本缺陷最不该有的误判方向。
   */
  fun parseStatus(raw: String?): PublicRepoStatus {
    val line = raw?.trim() ?: return PublicRepoStatus.UNKNOWN
    val parts = line.split('|')
    if (parts.size < 4) return PublicRepoStatus.UNKNOWN
    return PublicRepoStatus.fromWire(parts[0])
  }

  /** 落盘行里的细节（畸形/缺字段返回空串）。 */
  fun parseDetail(raw: String?): String {
    val line = raw?.trim() ?: return ""
    val parts = line.split('|')
    return if (parts.size >= 4) parts.drop(3).joinToString("|").trim() else ""
  }

  /** 最后一次尝试的触发来源（诊断用；畸形返回空串）。 */
  fun parseTrigger(raw: String?): String {
    val line = raw?.trim() ?: return ""
    val parts = line.split('|')
    return if (parts.size >= 2) parts[1].trim() else ""
  }

  /**
   * 是否值得在下次触发时再试一次。
   *
   * **除 [PublicRepoStatus.OK] 外一律重试**——这条就是本缺陷缺掉的性质：旧实现的失败之后
   * 再没有任何重试点，而成功之后也无需重复做文件系统操作。
   */
  fun needsRetry(status: PublicRepoStatus): Boolean = status != PublicRepoStatus.OK

  /**
   * 展示口径。**只有 [PublicRepoStatus.OK] 可以映射到 [PublicRepoPresentation.READY]**——
   * 把「尚未探测」（[PublicRepoStatus.UNKNOWN]）或失败渲染成「已就绪」正是本缺陷的文案面
   * （与坑 161「把尚未探测渲染成未就绪」同族，方向相反）。
   *
   * S1-9 起 UNKNOWN 走**独立的** [PublicRepoPresentation.PENDING]：它同样不得显示「已就绪」，
   * 但也不得显示「去授权」——把「还没探过」说成「没授权」是另一种把状态说错的方向。
   */
  fun presentation(status: PublicRepoStatus): PublicRepoPresentation = when (status) {
    PublicRepoStatus.OK -> PublicRepoPresentation.READY
    PublicRepoStatus.NOT_AUTHORIZED -> PublicRepoPresentation.NEEDS_GRANT
    PublicRepoStatus.FAILED -> PublicRepoPresentation.WRITE_FAILED
    PublicRepoStatus.UNKNOWN -> PublicRepoPresentation.PENDING
  }

  /** 授权动作该走哪条路。 */
  enum class GrantRoute {
    /** API>=30：开系统「所有文件访问」页。 */
    ALL_FILES_ACCESS_SCREEN,

    /** API<30：没有 All Files Access 这套权限模型，必须请求运行时 READ/WRITE。 */
    RUNTIME_STORAGE_PERMISSION,
  }

  /**
   * API<30 上 All Files Access 这个权限**不存在**，开它的系统页是空操作
   * （旧 `openAllFilesAccessSettings` 直接 `if (SDK_INT < 30) return`）——用户按了没有任何反应。
   * 故授权路线按 SDK 分流，而不是一律开那个页。
   */
  fun grantRoute(sdkInt: Int): GrantRoute =
    if (sdkInt >= 30) GrantRoute.ALL_FILES_ACCESS_SCREEN else GrantRoute.RUNTIME_STORAGE_PERMISSION

  /**
   * 失败归类：授权到位却写不进去是 [PublicRepoStatus.FAILED]（要查 scoped storage / OEM / 挂载），
   * 授权不到位是 [PublicRepoStatus.NOT_AUTHORIZED]（要给用户授权入口）。两者文案与后续动作不同，
   * 混成一句「未就绪」会让用户既不知道该授权什么、也不知道授权之后为什么还不行。
   */
  fun classifyFailure(publicWritableCapability: Boolean): PublicRepoStatus =
    if (publicWritableCapability) PublicRepoStatus.FAILED else PublicRepoStatus.NOT_AUTHORIZED

  /** 折叠换行。CRLF 必须先整体换掉，否则会折成两个空格（实测踩到，用例判红）。 */
  private fun fold(detail: String): String =
    detail.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ').trim()
}
