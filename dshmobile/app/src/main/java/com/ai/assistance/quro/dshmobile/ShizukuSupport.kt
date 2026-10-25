package com.ai.assistance.quro.dshmobile

import android.content.Context

/**
 * Optional Shizuku integration (M2 keep-alive boost, stage 1): detect the
 * Shizuku server and report status. The appops-application step needs the
 * shell-exec API (Shizuku.newProcess is not public in api 13.1.5; upgrade the
 * dependency or route via a user service) — deferred, see docs/design.md.
 * Everything degrades gracefully when Shizuku is absent.
 *
 * 零依赖反射化（2026-08-23）：Shizuku（Apache-2.0）仅作为 ADB 提权的说明性示例被
 * 部分引用——此处只用其公开静态 API（pingBinder/checkSelfPermission/getVersion）做
 * 探活日志，不参与授权链与 transport（0.14.0 起直连由 ShizukuTransport/ShizukuUserService
 * 承担，gradle 已引 dev.rikka.shizuku:api/provider 13.1.5；本文件保留反射使探活在 aar
 * 未装载时也能降级）；Shizuku 未安装/未运行时一律优雅降级。
 */
object ShizukuSupport {

  /** 经 Class.forName 反射取 rikka.shizuku.Shizuku 类；未安装 → null。 */
  private fun shizukuClass(): Class<*>? {
    return try {
      Class.forName("rikka.shizuku.Shizuku")
    } catch (_: Throwable) {
      null
    }
  }

  /** True when the Shizuku server binder is reachable. */
  fun isAvailable(): Boolean {
    return try {
      val cls = shizukuClass() ?: return false
      val pinged = (cls.getMethod("pingBinder").invoke(null) as? Boolean) == true
      val granted =
        (cls.getMethod("checkSelfPermission").invoke(null) as? Int) ==
          android.content.pm.PackageManager.PERMISSION_GRANTED
      pinged && granted
    } catch (_: Throwable) {
      false
    }
  }

  /** Status text for the UI; never throws. */
  fun status(context: Context): String {
    return try {
      val cls = shizukuClass()
      if (cls == null) {
        "Shizuku 未安装（可选：后台保活增强需要它）"
      } else if (isAvailable()) {
        "Shizuku 已授权（v" + cls.getMethod("getVersion").invoke(null) + "）——保活增强就绪"
      } else {
        "Shizuku 未运行（可选：后台保活增强需要它）"
      }
    } catch (_: Throwable) {
      "Shizuku 未运行（可选：后台保活增强需要它）"
    }
  }
}
