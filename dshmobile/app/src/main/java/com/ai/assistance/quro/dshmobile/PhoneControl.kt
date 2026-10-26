package com.ai.assistance.quro.dshmobile

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import org.json.JSONObject

/**
 * 手机操控设置门面（#417：功能从 harness WebUI「手机控制」区迁入 ZorvAI 设置）。
 *
 * ── 为什么是「门面」而不是让 ZorvAI 直接调各单例────────────────────────────
 * 壳侧这些单例（[ScreenScopePrefs] / [VdisplayController] / [ShizukuTransport] /
 * [RootGrant] / [RootAccess] / [RootOwnershipJobs] / [DeviceControlService] /
 * [ExternalLinks] / [LocalDocs]）散落在本模块内，且其中有 internal 可见性的成员
 * （`VdisplayPrefs`、`RootOwnershipJobs`、`ShellAppContext`）。ZorvAI 主模块只能
 * 看见 public 面，逐个去调既会把「哪些能调」泄漏成隐式契约，也会让主模块为了一个
 * 开关去 import 一堆内部类。故在本模块内收一个**公开门面**，与 [NotifyCenter]
 * 同一模式：ZorvAI 只依赖 `PhoneControl` 一个 object。
 *
 * ── 数据纪律（从 WebUI 侧继承，改一行即违约）──────────────────────────────
 *  1. **读路径绝不阻塞**：所有 status/snapshot 都是纯读（内部自带 kickBind 自愈，
 *     不等 binder）。写路径一律「写后回读」，回读值才是界面显示值。
 *  2. **读不到就如实说读不到**：解析失败一律回落 `readable:false` 的结构，
 *     绝不拿「未开启 / 0.75 / 仅虚拟屏幕」这类默认值冒充事实。
 *  3. **不含「点击查看教程」外链**：用户明令删除（#417）。本门面只登记
 *     Shizuku **下载**链接；教程 key 不接线、不暴露。
 *  4. 所有方法返回结构化 JSON（`ok` + 业务字段 + `code`/`guidance`），
 *     失败不静默、不抛异常穿过 UI 层。
 */
object PhoneControl {

  private const val TAG = "dsh-phone-control"

  /** 屏幕范围线取值（[ScreenScope.wire] 的稳定别名；界面三选一）。 */
  val SCREEN_SCOPES: List<String> = listOf(
    ScreenScope.VIRTUAL_ONLY.wire,
    ScreenScope.REAL_ONLY.wire,
    ScreenScope.ALL.wire,
  )

  /** 虚拟屏分辨率档位（0.5 / 0.75 / 1.0；1.0 = 原生）。 */
  val VDISPLAY_SCALES: List<Double> = listOf(0.5, 0.75, 1.0)

  // ── 屏幕范围 ─────────────────────────────────────────────────────────────

  /** 当前屏幕范围（读后回读归一化值；缺省/损坏数据按 fail-closed 回落 virtual-only）。 */
  fun screenScope(context: Context): JSONObject =
    JSONObject().put("ok", true).put("scope", ScreenScopePrefs.current(context).wire)

  /** 写入屏幕范围并回读归一化结果（非法值回落 virtual-only，不冒充写入成功）。 */
  fun setScreenScope(context: Context, scope: String?): JSONObject {
    val applied = ScreenScopePrefs.set(context, scope)
    return screenScope(context).put("applied", applied.wire)
  }

  // ── 虚拟屏 ───────────────────────────────────────────────────────────────

  /**
   * 虚拟屏状态（[VdisplayController.status]：state/code/guidance/screens/selected）。
   *
   * 纯读：内部只触发一次非阻塞的后台绑定（kickBind），绝不等待 binder。
   */
  fun vdisplayStatus(context: Context): JSONObject = runCatching {
    VdisplayController.status(context)
  }.getOrElse { readableFailure("vdisplay-status-failed", it) }

  /** 强制销毁全部虚拟屏（无视会话归属；UI 侧三连点确认后才可调）。 */
  fun forceDestroyVdisplay(context: Context): JSONObject = runCatching {
    VdisplayController.forceDestroy(context)
  }.getOrElse { readableFailure("vdisplay-force-destroy-failed", it) }

  /** 当前分辨率档位（0.4–1.0，缺省 0.75）。 */
  fun vdisplayScale(context: Context): Double = VdisplayPrefs.scale(context)

  /** 写入分辨率档位并回读钳制后的生效值（下一次建屏才按新档位创建）。 */
  fun setVdisplayScale(context: Context, value: Double): JSONObject {
    VdisplayPrefs.setScale(context, value)
    return JSONObject().put("ok", true).put("scale", VdisplayPrefs.scale(context))
  }

  /** 退后台浮窗开关当前值（缺省 true）。 */
  fun vdisplayFloat(context: Context): Boolean = VdisplayPrefs.floatEnabled(context)

  /** 写退后台浮窗开关并回读生效值。 */
  fun setVdisplayFloat(context: Context, enable: Boolean): JSONObject {
    VdisplayPrefs.setFloatEnabled(context, enable)
    return JSONObject().put("ok", true).put("float", VdisplayPrefs.floatEnabled(context))
  }

  // ── Shizuku 特权通道 ─────────────────────────────────────────────────────

  /**
   * Shizuku 通道状态（[ShizukuTransport.status]：installed/running/granted/bound/
   * binding/bindAttempts/connectFailures/startFailed/diagnostics + code/guidance）。
   *
   * 纯读：先跑绑定看门狗自愈，再返回当前事实；不阻塞、不发起同步绑定。
   */
  fun shizukuStatus(context: Context): JSONObject = runCatching {
    ShizukuTransport.status(context)
  }.getOrElse { readableFailure("shizuku-status-failed", it) }

  /** 重置链接（丢弃当前绑定Attempt，下一次轮询/操作重新发起）。 */
  fun resetShizukuConnection(context: Context): JSONObject = runCatching {
    ShizukuTransport.resetConnection(context)
  }.getOrElse { readableFailure("shizuku-reset-failed", it) }

  /**
   * 请求 Shizuku 授权（弹出管理器确认框）。
   *
   * 授权只能由用户在 Shizuku 内完成——本方法只负责**请求**，不视为已授予
   * （与 [ExternalLinks.openShizukuManager] 同口径）。
   */
  fun requestShizukuPermission(activity: Activity): JSONObject = runCatching {
    ShizukuTransport.requestPermission(activity)
  }.getOrElse { readableFailure("shizuku-request-failed", it) }

  /** 打开 Shizuku 下载页（GitHub Releases；登记在 [ExternalLinks] 的固定 https 链接）。 */
  fun openShizukuDownload(activity: Activity): JSONObject =
    answerOf { ExternalLinks.open(activity, ExternalLinks.SHIZUKU_DOWNLOAD) }

  /** 打开 Shizuku 管理器界面（官方/分支实现逐个尝试；未安装如实报 not-installed）。 */
  fun openShizukuManager(activity: Activity): JSONObject =
    answerOf { ExternalLinks.openShizukuManager(activity) }

  // ── 无障碍通道（harness 设备控制服务）────────────────────────────────────

  /**
   * 无障碍服务状态（[DeviceControlService.statusJson]：enabled/hint/
   * restrictedSettingsApplies/tokenConfigured/sdk/label）。
   *
   * `enabled` 只反映 **harness 的** `.DeviceControlService` 是否已连接；
   * ZorvAI 自有的无障碍服务是另一个组件，由 ZorvAI 权限屏单独检测（#433 权限融合）。
   */
  fun a11yStatus(context: Context): JSONObject = runCatching {
    JSONObject(DeviceControlService.statusJson(context))
  }.getOrElse { readableFailure("a11y-status-failed", it) }

  /**
   * 跳系统无障碍设置页（复刻 MainActivity 私有实现：ACCESSIBILITY_SETTINGS →
   * SETTINGS 兜底；都打不开时 Toast 明说，不静默）。
   *
   * @return 是否成功发起跳转（false = 已 Toast 告知用户手动前往）。
   */
  fun openA11ySettings(activity: Activity): Boolean {
    val candidates = listOf(
      Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS),
      Intent(android.provider.Settings.ACTION_SETTINGS),
    )
    for (intent in candidates) {
      try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent)
        return true
      } catch (t: Throwable) {
        Log.w(TAG, "openA11ySettings failed: " + t.message)
      }
    }
    Toast.makeText(
      activity,
      UiText.t(activity, "无法打开系统设置，请手动前往 系统设置 → 无障碍", "Cannot open system settings; go to Settings → Accessibility manually"),
      Toast.LENGTH_LONG,
    ).show()
    return false
  }

  /**
   * Android 13+ 侧载应用「受限设置」一键解锁（复刻 MainActivity 私有实现）：
   * 经 Shizuku 特权 shell 跑 `appops set … ACCESS_RESTRICTED_SETTINGS allow`。
   *
   * 返回与旧实现同形 `{ok, message}`（message 为面向用户的一句话，失败即原因）。
   */
  fun unlockRestrictedSettings(activity: Activity): JSONObject {
    val result = runCatching {
      ShizukuTransport.runShell(
        activity,
        "appops set --user 0 " + activity.packageName + " ACCESS_RESTRICTED_SETTINGS allow",
      )
    }.getOrElse { return readableFailure("unlock-restricted-failed", it) }
    val ok = result.optBoolean("ok")
    val message = if (ok) {
      UiText.t(activity, "已解锁受限设置（Shizuku 特权 shell）", "Restricted settings unlocked via the Shizuku privileged shell")
    } else {
      result.optString("guidance").ifBlank { result.optString("error").ifBlank {
        UiText.t(activity, "解锁失败", "Unlock failed")
      } }
    }
    return JSONObject().put("ok", ok).put("message", message)
  }

  // ── Root 权限 ────────────────────────────────────────────────────────────

  /**
   * 「AI root 权限」开关面（[RootGrant.state]：granted/consentValid/canToggle/
   * channelRoot/channelUid/rootGranted/rootState/ownership/honesty）。
   *
   * 通道 uid 读不到按 -1（fail-closed，不猜 root）。
   */
  fun rootGrantState(context: Context): JSONObject = runCatching {
    RootGrant.state(context, RootGrant.channelUidNow(context))
  }.getOrElse { readableFailure("root-grant-state-failed", it) }

  /**
   * 开/关「AI root 权限」开关（[RootGrant.setGranted]：决策门不满足时拒绝并带
   * code/guidance；关闭无需资格）。返回写后回读状态。
   */
  fun setRootGranted(context: Context, on: Boolean): JSONObject = runCatching {
    RootGrant.setGranted(context, on)
  }.getOrElse { readableFailure("root-grant-set-failed", it) }

  /**
   * 勾选/取消「已阅读免责声明」（[RootGrant.setConsent]：取消即连带关闭开关，
   * 不留「未同意但已授权」的矛盾态）。返回写后回读状态。
   */
  fun setRootConsent(context: Context, on: Boolean): JSONObject = runCatching {
    RootGrant.setConsent(context, on)
  }.getOrElse { readableFailure("root-consent-set-failed", it) }

  /** 打开免责声明（APK assets 内本地文档，应用内对话框渲染；不经系统浏览器）。 */
  fun openRootDisclaimer(activity: Activity): JSONObject =
    answerOf { LocalDocs.open(activity, LocalDocs.ROOT_DISCLAIMER) }

  /**
   * 应用级 root 授权状态（[RootAccess.state]：suExists/state/uid/granted/requesting/
   * manager/guidance/ownership）。**纯读，永不触发弹窗**。
   */
  fun rootAccessState(context: Context): JSONObject = runCatching {
    RootAccess.state(context)
  }.getOrElse { readableFailure("root-access-state-failed", it) }

  /**
   * 显式检测 root 授权（[RootAccess.requestGrant]：后台跑一次 `su -c id`，25s 有界）。
   *
   * 不承诺管理器自动弹窗（多数管理器需用户自己允许）——识别到管理器名时在
   * guidance 里带上，识别不到就说「你使用的 Root 管理器」。
   */
  fun requestRootAccess(context: Context): JSONObject = runCatching {
    RootAccess.requestGrant(context)
  }.getOrElse { readableFailure("root-access-request-failed", it) }

  /**
   * 文件属主维护（[RootOwnershipJobs.request]：UI 入口立即返回，既有 root 状态
   * 轮询观察同一次共享结果；不可重复触发——已在跑就报 repair-running）。
   */
  fun repairRootOwnership(context: Context): JSONObject = runCatching {
    RootOwnershipJobs.request(context)
  }.getOrElse { readableFailure("root-ownership-repair-failed", it) }

  // ── 聚合读面（UI 单次轮询） ──────────────────────────────────────────────

  /**
   * 一次拉全部手机操控状态（设置页 2s 轮询走这里，避免九个字段各发一次读）。
   *
   * 全纯读：vdisplayStatus/shizukuStatus 内部的 kickBind 是非阻塞自愈；
   * RootGrant.state/RootAccess.state 只读缓存与 su 存在性，不弹窗、不起进程。
   */
  fun snapshot(context: Context): JSONObject {
    val out = JSONObject().put("ok", true)
    out.put("scope", ScreenScopePrefs.current(context).wire)
    out.put("vdisplay", vdisplayStatus(context))
    out.put("vdisplayScale", VdisplayPrefs.scale(context))
    out.put("vdisplayFloat", VdisplayPrefs.floatEnabled(context))
    out.put("shizuku", shizukuStatus(context))
    out.put("a11y", a11yStatus(context))
    out.put("rootGrant", rootGrantState(context))
    out.put("rootAccess", rootAccessState(context))
    return out
  }

  // ── 内部工具 ─────────────────────────────────────────────────────────────

  /**
   * 读路径失败的结构化回落：`readable:false` + code + guidance。
   *
   * 绝不返回「看起来正常」的默认结构——调用方（UI）据此显示「状态不可读」，
   * 而不是把默认值当事实展示。
   */
  private fun readableFailure(code: String, error: Throwable): JSONObject =
    JSONObject().put("ok", false).put("readable", false).put("code", code)
      .put("guidance", error.javaClass.simpleName + ": " + (error.message ?: ""))

  /** 把壳侧既有的「JSON 文本」回包解析成 JSONObject（解析失败同样结构化，不抛）。 */
  private fun answerOf(block: () -> String): JSONObject = runCatching {
    JSONObject(block())
  }.getOrElse { readableFailure("answer-parse-failed", it) }
}
