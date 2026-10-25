package com.ai.assistance.quro.dshmobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.util.Log

/** 开机自启（零改动原则：仅恢复用户上次同意状态；白名单/厂商跳转引导由设置面承托）。
 *  独立成文件（Phase 3）：与 AndroidManifest 组件一一对齐——manifest 注册的
 *  receiver 不应寄居在 WatchdogV2.kt 文件尾部。
 *
 *  ST-21（S0 死状态清理）：这里原先读一个**全仓无任何写点、无 UI** 的开机自启偏好键（默认恒 true
 *  的隐式定义）——用户既关不掉也开不了。该键及其读取分支已删除，行为改为显式：开机自启当前
 *  **无条件**。将来若要接设置项，写点（设置页持久化）与读点必须同批落地，禁止再出现「有读无写」。
 *
 *  review C9（2026-09-14）：上面「无条件」与产品语义冲突——用户手动关停（requestShutdown）后
 *  重启设备仍自启，引导页「引擎已停止，不会自动恢复」文案在重启后不成立。现读持久化的
 *  userShutdown（EngineService.setUserShutdown 写点）：用户关停过就不自启；打开应用
 *  （EngineStartFlow.start，用户显式动作）会清掉该状态并拉起引擎。 */
class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
    if (EngineService.isUserShutdownPersisted(context)) {
      LogCollector.log("dsh-watchdog", "boot completed; engine service NOT started (user shut it down; opening the app clears this)")
      return
    }
    LogCollector.log("dsh-watchdog", "boot completed; starting engine service")
    try {
      context.startForegroundService(Intent(context, EngineService::class.java))
    } catch (t: Throwable) {
      Log.e("dsh-watchdog", "boot start failed: " + t.message)
    }
  }
}

object BatteryWhitelist {
  /** 引导跳转忽略电池优化设置页（Android 6+）；写入由授权调试档（appops/deviceidle）完成，未授权时仅引导。 */
  fun isIgnoring(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return if (Build.VERSION.SDK_INT >= 23) pm.isIgnoringBatteryOptimizations(context.packageName) else true
  }

  private const val ACTION = "android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"

  fun requestIntent(context: Context): Intent? {
    return try {
      if (Build.VERSION.SDK_INT >= 23 && !isIgnoring(context)) {
        Intent(ACTION, Uri.parse("package:" + context.packageName))
      } else null
    } catch (_: Exception) {
      null
    }
  }
}
