package com.ai.assistance.quro.core.privilege

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences

/**
 * LSPosed 跨应用注入桥的接收端：当 QuroXposedModule 钩中目标包 Activity.onCreate 时，
 * 会向本应用发送 [ACTION_APP_OPENED] 广播；本接收器把它记录到私有 SharedPreferences，
 * 供应用侧（priv_exec status / 后续能力）读取「当前前台 App」——补充 get_foreground_app
 * 在无 PACKAGE_USAGE_STATS 权限时的盲区。
 *
 * 不定义任何 ai.aci.permission.*；仅记录包名/Activity 类名（非敏感信息），且广播本就由本应用
 * 模块发出。这里把接收器导出是为了能跨 UID（被钩中的目标 App 进程）收到广播。
 */
class QuroLsposeBridgeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (ACTION_APP_OPENED != intent.action) return
        val pkg = intent.getStringExtra("package") ?: return
        val activity = intent.getStringExtra("activity") ?: ""
        val ts = intent.getLongExtra("ts", 0L)
        runCatching {
            prefs(context).edit().apply {
                putString(KEY_PKG, pkg)
                putString(KEY_ACTIVITY, activity)
                putLong(KEY_TS, ts)
            }.apply()
        }
    }

    companion object {
        const val ACTION_APP_OPENED = "com.ai.assistance.quro.LSPOSED_APP_OPENED"
        private const val PREFS = "lsposed_bridge"
        private const val KEY_PKG = "last_foreground_pkg"
        private const val KEY_ACTIVITY = "last_foreground_activity"
        private const val KEY_TS = "last_foreground_ts"

        private fun prefs(ctx: Context): SharedPreferences =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /** 读取最近一次由 LSPosed 桥上报的前台 App（无则返回 null）。 */
        fun lastForegroundApp(ctx: Context): Triple<String?, String?, Long>? {
            return runCatching {
                val p = prefs(ctx)
                val pkg = p.getString(KEY_PKG, null)
                if (pkg.isNullOrEmpty()) null
                else Triple(pkg, p.getString(KEY_ACTIVITY, ""), p.getLong(KEY_TS, 0L))
            }.getOrNull()
        }
    }
}
