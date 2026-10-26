package com.ai.assistance.quro.dshmobile

import android.content.Context
import android.util.Log
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * harness 本地导入热更新（#428）：用户从系统文件选择器选一个 snapshot 压缩包（.tar.xz），
 * 直接更新整个 harness 运行时，无需重装 APK、无需发布服务器。
 *
 * ## 为什么复用在线更新的事务链而不是新写一套
 *
 * 运行时换树是本模块最危险的操作：解压到一半被杀、swap 中途断电、新树起不来——
 * 每一种都对应「用户数据还在、harness 再也起不来」的现场。[UpdateManager] 的在线更新
 * 已经把这条链路走通（事务 marker + 暂存 + 原子 swap + 健康验证期 + 自动回退），
 * 本地导入只是把「下载 + manifest 校验」换成「SAF 选包 + 摘要对账」，其余逐字复用：
 * 同一个 [SnapshotTransaction.Purpose.ONLINE_UPDATE] 语义、同一套回退源。
 *
 * ## 完整性口径（与在线更新的差别）
 *
 * 在线更新的摘要声明来自 manifest（同信道，防意外损坏）；本地导入的摘要来自**用户自备**
 * 的 .sha256 边车文件（可选）。提供了就严格对账（不一致即拒绝，不进入解压）；
 * 没提供也照常更新——摘要仍会被计算并写进事务 marker，作为新运行时的身份指纹。
 * 用户自备包本来就由用户自己负责来源，本层不冒充可信发布通道。
 *
 * ## 调用契约
 *
 * - **不得在主线程调用**（解压 194MB+ 分钟级，健康验证最长 180s+45s）。
 * - [openInput] 必须可**重复打开**（先整体流式算摘要，再重开一路解压）；SAF 的
 *   contentResolver.openInputStream 天然满足。
 * - 换树期间引擎会被停掉（[EngineManager.stopOwnedEngine]，只停本壳持有的子进程）；
 *   换完后由本门面负责把引擎拉起来，健康验证通过才算更新完成。
 */
object HarnessHotUpdate {

  private const val TAG = "dsh-hot-update"

  /** 热更新相位（与 [UpdateManager.UpdateOutcome] 同构，但这是本地导入，没有 NotConfigured）。 */
  enum class Phase { Working, Verifying, Done, Failed }

  /** 一次状态回执：相位 + 用户可见文案（不出现内部标识符）。 */
  data class Status(val phase: Phase, val text: String)

  /** 健康观察期轮询间隔（与在线更新一致）。 */
  private const val SETTLEMENT_POLL_MS = 1_000L

  /** 健康观察期在回退窗之外的额外宽限（与在线更新一致）。 */
  private const val SETTLEMENT_GRACE_MS = 45_000L

  /**
   * 导入并激活一个本地快照包（阻塞，后台线程内调用）。
   *
   * @param openInput 可重复打开的归档流（先算摘要、后解压，各开一路）。
   * @param declaredSize 归档字节数（仅用于进度；0 = 未知）。
   * @param expectedSha256 可选的随附摘要（如 .sha256 边车文件内容）；提供且不匹配即拒绝。
   * @param onStatus 进度与终态回执（调用线程）。
   */
  fun importArchive(
    context: Context,
    openInput: () -> InputStream,
    declaredSize: Long = 0L,
    expectedSha256: String? = null,
    onStatus: (Status) -> Unit,
  ) {
    val app = context.applicationContext
    val filesDir = app.filesDir
    val stage = SnapshotTransaction.stageRoot(filesDir)
    var transactionStarted = false
    var probationStarted = false
    try {
      // 与在线更新共用一把闸门：同一时刻只允许一场运行时事务（UI 检查更新 / 本地导入互斥）。
      if (!OnlineUpdateGate.begin()) {
        throw IllegalStateException("另一场运行时更新正在进行，请稍后再试")
      }
      onStatus(Status(Phase.Working, "正在校验更新包…"))
      val archiveSha = sha256(openInput())
      val declaredSha = expectedSha256?.trim()?.lowercase(java.util.Locale.ROOT)?.takeIf { it.isNotEmpty() }
      if (declaredSha != null && declaredSha != archiveSha) {
        throw IllegalStateException(
          "更新包与随附摘要不一致（expected=" + declaredSha.take(12) + "… actual=" + archiveSha.take(12) + "…）",
        )
      }
      // 进程级单一 EngineManager：stopOwnedEngine 只认本壳持有句柄的子进程，
      // 用单例才能停掉「工作台界面/前台服务」拉起的那个引擎（与 UpdateManager 的差异点）。
      val manager = DshmobileControl.manager(app)
      if (!manager.beginOnlineUpdateTransaction()) {
        throw IllegalStateException("已有运行时事务或未归属的恢复现场正在进行")
      }
      transactionStarted = true
      val priorFingerprint = File(filesDir, ".snapshot-fingerprint")
        .takeIf { it.isFile }?.readText()?.trim().orEmpty()
      if (!priorFingerprint.matches(Regex("[0-9a-fA-F]{64}"))) {
        throw IllegalStateException("当前运行时缺少可验证的回退指纹，拒绝更新")
      }
      val baseFingerprint = PluginMounts.embeddedFingerprint(app)
        ?: throw IllegalStateException("当前安装缺少可信的内嵌插件身份，拒绝更新")
      val startedAt = System.currentTimeMillis()
      SnapshotTransaction.writeMarker(
        filesDir,
        SnapshotTransaction.Marker(
          SnapshotTransaction.Phase.STAGED,
          archiveSha,
          startedAt,
          purpose = SnapshotTransaction.Purpose.ONLINE_UPDATE,
          priorFingerprint = priorFingerprint,
          baseFingerprint = baseFingerprint,
        ),
      )
      onStatus(Status(Phase.Working, "正在解压更新包…"))
      // 解压目标永远是事务暂存区：live 树在 swap 之前一个字节都不动。
      SnapshotExtractor.extract(
        openInput(), declaredSize, stage, { _, _ -> }, runtimeRoot = filesDir,
      )
      if (!SnapshotFs.exists(File(stage, "usr/bin/node"))) {
        throw IllegalStateException("更新包缺少 node 运行时")
      }
      if (PluginMounts.prepareOnlineHardManifest(app, stage, archiveSha, baseFingerprint) == null) {
        throw IllegalStateException("更新包缺少可验证的插件身份，拒绝更新")
      }
      if (!File(filesDir, "usr/bin/node").isFile) {
        throw IllegalStateException("当前运行时缺少 node，拒绝替换")
      }
      onStatus(Status(Phase.Working, "正在切换运行时…"))
      // 只停本壳持有的子进程；端口被未归属监听器占用时如实拒绝，不按名字杀进程。
      if (!manager.stopOwnedEngine()) {
        throw IllegalStateException(
          "运行时更新已拒绝：" + EngineProbe.ENGINE_AUTHORITY + " 监听进程未归属到本壳，未停止外部进程",
        )
      }
      SnapshotTransaction.swap(
        filesDir = filesDir,
        stagedRoot = stage,
        usrDir = File(filesDir, "usr"),
        homeDir = File(filesDir, "home"),
        preservedNames = SnapshotUserData.preservedNames.toSet(),
        fingerprint = archiveSha,
        startedAt = startedAt,
        purpose = SnapshotTransaction.Purpose.ONLINE_UPDATE,
        priorFingerprint = priorFingerprint,
        baseFingerprint = baseFingerprint,
      )
      probationStarted = true
      File(filesDir, ".update-pending").writeText("1")
      File(filesDir, ".update-pending-at").writeText(System.currentTimeMillis().toString())
      // 先放行启动流的更新闸门，再把引擎拉起来：看门狗已在跑时 start 会早退，
      // 没在跑时由本门面补齐（否则健康验证永远等不到 tick，只能超时回退）。
      OnlineUpdateGate.end()
      onStatus(Status(Phase.Verifying, "运行时已切换，正在重启引擎并验证…"))
      runCatching { DshmobileControl.start(app, 150_000L) }
        .onFailure { Log.w(TAG, "engine restart after hot update failed", it) }
      val settlement = awaitSettlement(app, archiveSha, baseFingerprint, priorFingerprint)
      probationStarted = SnapshotTransaction.readMarker(filesDir) != null ||
        File(filesDir, ".update-pending").exists()
      onStatus(settlement)
    } catch (t: Throwable) {
      if (transactionStarted) {
        val recovered = try {
          DshmobileControl.manager(app).abortOnlineUpdate()
        } catch (rollback: Throwable) {
          t.addSuppressed(rollback)
          false
        }
        if (!recovered) {
          Log.e(TAG, "hot update rollback incomplete; journal and recovery source retained")
        }
        probationStarted = !recovered
      }
      Log.w(TAG, "hot update failed: " + t.message)
      onStatus(Status(Phase.Failed, "更新失败：" + UpdateManager.UpdateFailures.humanize(t)))
    } finally {
      // 归档/暂存区只是原料， never 是恢复源：marker 消失后即可清。
      if (SnapshotTransaction.readMarker(filesDir) == null) SnapshotFs.deletePath(stage)
      if (!probationStarted) EngineManager.onlineUpdateActive.set(false)
      OnlineUpdateGate.end()
    }
  }

  /**
   * 健康观察期（与 [UpdateManager.awaitOnlineSettlement] 同口径）。
   *
   * 引擎重启后由看门狗喂健康 tick，连续 [EngineManager.UPDATE_CONFIRM_TICKS] 拍健康即提交
   * （删 marker、写 `.online-snapshot` 身份）；观察窗内始终不健康则自动回退到原运行时。
   */
  private fun awaitSettlement(
    app: Context,
    expectedFingerprint: String,
    baseFingerprint: String,
    priorFingerprint: String,
  ): Status {
    val deadline = System.currentTimeMillis() + EngineManager.UPDATE_ROLLBACK_MS + SETTLEMENT_GRACE_MS
    while (System.currentTimeMillis() < deadline) {
      val markerPresent = SnapshotTransaction.readMarker(app.filesDir) != null
      val pendingPresent = File(app.filesDir, ".update-pending").exists()
      val liveFingerprint = File(app.filesDir, ".snapshot-fingerprint")
        .takeIf { it.isFile }?.let { runCatching { it.readText().trim() }.getOrNull() }.orEmpty()
      val onlineIdentity = readOnlineIdentity(File(app.filesDir, ".online-snapshot"))
      when (
        UpdateManager.OnlineSettlement.classify(
          markerPresent, pendingPresent, liveFingerprint, onlineIdentity.first, onlineIdentity.second,
          expectedFingerprint, baseFingerprint, priorFingerprint,
        )
      ) {
        UpdateManager.OnlineSettlement.State.COMMITTED ->
          return Status(Phase.Done, "更新已通过启动与健康验证")
        UpdateManager.OnlineSettlement.State.ROLLED_BACK ->
          return Status(Phase.Failed, "新版本未通过健康验证，已自动回退到原版本；用户数据已保留")
        UpdateManager.OnlineSettlement.State.INCONSISTENT ->
          return Status(Phase.Failed, "更新状态无法确认；恢复现场已保留，请打开控制台检查日志")
        UpdateManager.OnlineSettlement.State.PENDING -> Unit
      }
      try {
        Thread.sleep(SETTLEMENT_POLL_MS)
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        return Status(Phase.Failed, "健康验证等待已中断；恢复现场已保留，请重新启动应用完成恢复")
      }
    }
    return Status(Phase.Failed, "启动与健康验证超时；恢复现场已保留，请重新启动应用并查看控制台日志")
  }

  private fun readOnlineIdentity(file: File): Pair<String, String> {
    if (!file.isFile) return "" to ""
    return try {
      val values = file.readLines().mapNotNull { line ->
        val split = line.indexOf('=')
        if (split <= 0) null else line.substring(0, split) to line.substring(split + 1)
      }
      val map = values.toMap()
      if (values.size != 2 || map.size != 2 || map.keys != setOf("base", "archive")) "" to ""
      else map["base"].orEmpty() to map["archive"].orEmpty()
    } catch (_: Throwable) { "" to "" }
  }

  /** 流式 SHA-256（64KB 缓冲，不整读入内存）。 */
  private fun sha256(open: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    open.use { input ->
      val buf = ByteArray(64 * 1024)
      var n = input.read(buf)
      while (n >= 0) {
        digest.update(buf, 0, n)
        n = input.read(buf)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }
}
