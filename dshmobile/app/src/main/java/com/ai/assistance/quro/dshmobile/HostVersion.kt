package com.ai.assistance.quro.dshmobile

import android.content.Context
import android.os.Build

/**
 * 宿主（ZorvAI）版本单一读点。
 *
 * 本模块以 library 形式内置进宿主 app：library 的 BuildConfig **不含**
 * VERSION_NAME / VERSION_CODE（这两个字段只属于 application 模块）。壳侧 UI /
 * 桥 / 诊断 / 引擎环境变量原先读 BuildConfig.VERSION_*，统一改读这里——直接问
 * 宿主的 PackageManager，杜绝写死字面量与宿主真实版本脱节（漂移）。
 *
 * context 传 null 时回落进程级 ShellAppContext（由 EngineAuth.initContext 绑定）。
 */
internal object HostVersion {

  fun name(context: Context? = null): String = try {
    val ctx = context ?: ShellAppContext.get() ?: return "unknown"
    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "unknown"
  } catch (_: Throwable) {
    "unknown"
  }

  fun code(context: Context? = null): Long = try {
    val ctx = context ?: ShellAppContext.get() ?: return 0L
    val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      pi.longVersionCode
    } else {
      @Suppress("DEPRECATION")
      pi.versionCode.toLong()
    }
  } catch (_: Throwable) {
    0L
  }
}
