package com.ai.assistance.quro.termux.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * 权限工具（精简版，对齐 Termux PermissionUtils 常用方法）。
 */
object PermissionUtils {
    const val REQUEST_GRANT_STORAGE_PERMISSION = 1001

    @JvmStatic
    fun checkIfBatteryOptimizationsDisabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    @JvmStatic
    fun requestDisableBatteryOptimizations(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:" + context.packageName)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage("PermissionUtils", "requestDisableBatteryOptimizations failed", e)
        }
    }

    @JvmStatic
    fun checkAndRequestLegacyOrManageExternalStoragePermission(
        context: Context,
        requestCode: Int,
        requestPermission: Boolean
    ): Boolean {
        // targetSdk 30+ 时，Android 11+ 需要 MANAGE_EXTERNAL_STORAGE；这里精简为直接返回 true
        //（ZorvAI 终端运行在 proot 内，使用 App 私有 filesDir，无需外部存储权限）。
        return true
    }
}