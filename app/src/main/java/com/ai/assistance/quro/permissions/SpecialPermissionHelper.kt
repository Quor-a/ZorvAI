package com.ai.assistance.quro.permissions

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 四类「特殊 / 角色」权限的纯逻辑助手集合（无运行时 launcher 注册，避免在页面导航打开时
 * 抛 IllegalStateException；请求统一由 Compose 侧 [rememberLauncherForActivityResult] 发起）。
 *
 * 对应「功能权限」页新增的卡片：
 * 1. [OverlayPermissionHelper]   锁屏显示 / 悬浮窗（`SYSTEM_ALERT_WINDOW`）—— appop 特殊权限。
 * 2. [FitnessPermissionHelper]   健身与运动（`ACTIVITY_RECOGNITION`）—— 运行时危险权限（API29+）。
 * 3. [AllFilesPermissionHelper]  文件与文档（`MANAGE_EXTERNAL_STORAGE`）—— 全文件系统 appop 特殊权限。
 * 4. [AssistantRoleHelper]       数字助理应用完整功能（`ROLE_ASSISTANT`）—— 系统角色，经 RoleManager 申请。
 */

/** 1. 锁屏显示 / 悬浮窗（SYSTEM_ALERT_WINDOW）。 */
class OverlayPermissionHelper(private val context: Context) {

    fun overlayState(): PermState =
        if (Settings.canDrawOverlays(context)) PermState.Granted else PermState.NeedSettings

    fun openOverlaySettings() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
            data = android.net.Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

/** 2. 健身与运动（ACTIVITY_RECOGNITION，API29+ 运行时危险权限）。 */
class FitnessPermissionHelper(private val activity: AppCompatActivity) {

    /** 是否已经向用户发起过请求（区分"首次弹窗"与"已永久拒绝"）。 */
    var hasRequested: Boolean = false

    fun permissionsNeeded(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        arrayOf(Manifest.permission.ACTIVITY_RECOGNITION)
    } else emptyArray()

    fun hasPermissions(): Boolean = permissionsNeeded().all {
        ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
    }

    fun shouldShowRationale(): Boolean = permissionsNeeded().any {
        ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
    }

    fun fitnessState(): PermState {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return PermState.Granted // 旧版本无此权限概念
        return when {
            hasPermissions() -> PermState.Granted
            shouldShowRationale() || !hasRequested -> PermState.NeedRequest
            else -> PermState.NeedSettings
        }
    }
}

/** 3. 文件与文档（MANAGE_EXTERNAL_STORAGE，Android 11+ 全文件系统 appop 特殊权限）。 */
class AllFilesPermissionHelper(private val context: Context) {

    fun allFilesState(): PermState =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) PermState.Granted else PermState.NeedSettings
        } else PermState.Granted // Android 10 及以下走分区存储/READ_WRITE，无需此特殊权限

    fun openAllFilesSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}

/** 4. 数字助理应用完整功能（ROLE_ASSISTANT，API29+ 系统角色）。 */
class AssistantRoleHelper(private val context: Context) {

    fun assistantState(): PermState {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return PermState.Granted // 旧版本无 ROLE 概念
        val rm = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager ?: return PermState.Granted
        return if (rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)) PermState.Granted else PermState.NeedRequest
    }

    /**
     * 构造「申请数字助理角色」的系统意图。返回 null 表示当前平台不支持（API < 29 或无 RoleManager）。
     * 调用方用 [androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult] 发起；
     * 系统会弹出角色选择/确认框，用户确认后本应用即成为默认数字助理。
     */
    fun createRequestIntent(): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val rm = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager ?: return null
        return runCatching { rm.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT) }.getOrNull()
    }
}
