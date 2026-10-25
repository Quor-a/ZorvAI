package com.ai.assistance.quro.dshmobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * 悬浮球控制器（W7，PRD-0.13.2 §4）：开关持久化 + OverlayService 生命周期 +
 * SYSTEM_ALERT_WINDOW 权限引导。桥（DevSection 开关）与 MainActivity.onResume
 * （权限授予后自动补启）共用此入口。
 *
 * ST-02（真源收敛，F-APK-02）：开关的事实来源 = 偏好 && Settings.canDrawOverlays &&
 * 服务实例在场；权限缺失时偏好回落 false（展示值与桥回值同时收敛），且宿主自身
 * 永不再弹系统页——回落让 onResume 的补启路径直接短路，只有用户显式再点开关才会重新引导。
 */
object OverlayController {

  private const val TAG = "dsh-overlay"
  private const val PREFS = "dsh-overlay"
  private const val KEY_ENABLED = "enabled"

  /**
   * 用户**表达过的开启意图**（S2-17）。
   *
   * 为什么需要单独记一笔：`KEY_ENABLED` 在权限缺失时会被回落 false（ST-02 的纪律——开关的
   * 展示值必须等于事实，不能让「显示开、球却不在」）。但回落把**用户的意图**一起丢了：
   * 用户去系统页授完权回来，偏好是 false ⇒ onResume 的补启路径直接短路 ⇒ 球不出现、开关自己
   * 变回关闭，全程零解释（S2-17 的现场）。
   * 因此把意图单独落盘：权限拿到后据此**自动补启一次**，两边都不违反。
   */
  private const val KEY_PENDING_ENABLE = "pendingEnable"

  private fun prefs(context: Context) =
    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  /** 偏好原值（不掺真源；仅内部决策用——对外一律看 [isEnabled]）。 */
  private fun enabledPref(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

  /** 是否有一笔「想开但当时没权限」的意图在等权限。 */
  fun hasPendingEnable(context: Context): Boolean = prefs(context).getBoolean(KEY_PENDING_ENABLE, false)

  private fun setPendingEnable(context: Context, pending: Boolean) {
    prefs(context).edit().putBoolean(KEY_PENDING_ENABLE, pending).apply()
  }

  /**
   * 真正的开启入口：授权后就绪则起服务；缺权限则**如实告知 + 记下意图 + 引导一次**。
   * @return 球此刻是否已在运行。
   */
  fun enableWithPermission(context: Context): Boolean {
    prefs(context).edit().putBoolean(KEY_ENABLED, true).apply()
    if (canDrawOverlays(context)) {
      setPendingEnable(context, false)
      return ensureStarted(context)
    }
    // S2-17：失败必须有回执（旧实现只写日志，用户看到的是「点了开关，它自己弹回去了」）。
    setPendingEnable(context, true)
    notifyUser(context, "还没有「显示在其他应用的上层」权限，悬浮球无法显示。已打开系统设置——授权后悬浮球会自动开启。")
    launchSettings(context)
    return false
  }

  /**
   * 回前台结算（MainActivity.onResume 调用）：有一笔待启意图且权限已到手 → 自动补启（S2-17）。
   *
   * 这条路径同时守住 ST-02：偏好仍由 [isEnabled] 的合成值决定展示，绝不在没权限时把
   * `KEY_ENABLED` 置 true 冒充「已开启」。
   */
  fun settlePendingEnable(context: Context): Boolean {
    if (!hasPendingEnable(context)) return false
    if (!canDrawOverlays(context)) return false
    setPendingEnable(context, false)
    prefs(context).edit().putBoolean(KEY_ENABLED, true).apply()
    val started = ensureStarted(context)
    notifyUser(context, if (started) "已获得悬浮窗权限，悬浮球已自动开启。" else "已获得悬浮窗权限，但悬浮球启动失败——请再点一次开关。")
    return started
  }

  /**
   * ST-02：真源合并。原先只读偏好——在系统里撤销「显示在其他应用的上层」后设置页开关
   * 仍显示「开」，而球已消失（展示值与事实不一致）。服务实例 = 进程内 onCreate/onDestroy
   * 维护的 @Volatile 引用（可靠；若某 ROM 上不可用，按退化口径 偏好 && canDrawOverlays 登记差异）。
   */
  fun isEnabled(context: Context): Boolean =
    enabledPref(context) && canDrawOverlays(context) && OverlayService.instance != null

  /** 桥入口：持久化 + 启停；未授 overlay 权限时把偏好回落 false 并引导一次。返回当前是否已启动。 */
  fun setEnabled(context: Context, enable: Boolean): Boolean {
    prefs(context).edit().putBoolean(KEY_ENABLED, enable).apply()
    if (enable) return enableWithPermission(context)
    setPendingEnable(context, false)
    stop(context)
    return false
  }

  /**
   * 幂等启动。权限缺失时：① 偏好回落 false（真源优先——开关展示值、桥回值、实际球态三者一致）；
   * ② 只在**这次显式开启动作**上跳一次系统授权页，随后 onResume 的补启因偏好已回落而短路，
   * 不再每次回前台弹页（ST-02 判据：未授权态弹页次数 = 0）。
   *
   * S2-17 起「引导一次」的记账方式改为 [KEY_PENDING_ENABLE]（见该常量的注释）：意图被记住，
   * 授权回来的那一次 onResume 会据此自动补启——不再是「用户授了权、球还是不出现」。
   */
  fun ensureStarted(context: Context): Boolean {
    if (!enabledPref(context)) return false
    if (!canDrawOverlays(context)) {
      prefs(context).edit().putBoolean(KEY_ENABLED, false).apply()
      Log.w(TAG, "overlay permission missing; enabled preference rolled back to false")
      LogCollector.log(TAG, "overlay permission missing: enabled pref rolled back to false (guide once, no onResume loop)")
      stop(context)
      return false
    }
    try {
      context.startService(Intent(context, OverlayService::class.java))
      return true
    } catch (e: Exception) {
      Log.e(TAG, "overlay service start failed: " + e.message)
      return false
    }
  }

  /** 面向用户的回执（S2-17：这几条路径此前只有日志，用户看不到任何解释）。 */
  private fun notifyUser(context: Context, msg: String) {
    try {
      android.os.Handler(android.os.Looper.getMainLooper()).post {
        android.widget.Toast.makeText(context.applicationContext, msg, android.widget.Toast.LENGTH_LONG).show()
      }
    } catch (_: Throwable) {
      LogCollector.log(TAG, "overlay toast failed: " + msg)
    }
  }

  fun stop(context: Context) {
    try {
      context.stopService(Intent(context, OverlayService::class.java))
    } catch (e: Exception) {
      Log.e(TAG, "overlay service stop failed: " + e.message)
    }
  }

  /** 系统授权页（仅在权限缺失且用户刚显式开启时调用一次）。 */
  private fun launchSettings(context: Context) {
    try {
      val i = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:" + context.packageName),
      ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      context.startActivity(i)
    } catch (e: Exception) {
      Log.e(TAG, "overlay settings launch failed: " + e.message)
    }
  }

  fun canDrawOverlays(context: Context): Boolean =
    Build.VERSION.SDK_INT >= 23 && Settings.canDrawOverlays(context)
}
