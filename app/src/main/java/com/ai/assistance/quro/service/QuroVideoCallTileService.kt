package com.ai.assistance.quro.service
import com.ai.assistance.quro.util.qstr

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.ai.assistance.quro.R

/**
 * 视频通话快捷磁贴：点击启动/切换 [QuroVideoCallService]（开/关）。
 * 与语音球磁贴（[QuroVoiceBallService]）同源的启动方式。
 *
 * ## 🔴 磁贴是独立入口，必须自己查权限
 * 对话框那条路径（`ChatScreen.startVideoCall`）已经检查过悬浮窗权限并申请过相机/麦克风，
 * 但磁贴完全绕过那一段。旧实现无条件 `startForegroundService` → 服务内 startForeground
 * 抛 SecurityException → `stopSelf()` → 用户看到「点一下磁贴，视频通话闪一下就没了」，
 * 且全程零提示。现在补齐三道前置检查，缺权限就直接告知并把用户送到对应系统设置页。
 */
class QuroVideoCallTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = qstr(R.string.qk_03550)
            icon = Icon.createWithResource(this@QuroVideoCallTileService, R.mipmap.ic_launcher)
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        // ① 悬浮窗权限：通话界面是 WindowManager 悬浮窗，没有就必然挂不上。
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, qstr(R.string.qk_03911), Toast.LENGTH_LONG).show()
            openSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            return
        }
        // ② 相机 / 麦克风：Android 14+ 前台服务类型必须与已授予的运行时权限匹配，缺一个就启动失败。
        // 磁贴没有 Activity 承载权限弹窗，无法就地申请，只能告知并送到应用设置页。
        val missing = buildList {
            if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
                add(qstr(R.string.qk_03913))
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                add(qstr(R.string.qk_03914))
        }
        if (missing.isNotEmpty()) {
            Toast.makeText(this, missing.joinToString(" / "), Toast.LENGTH_LONG).show()
            openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            return
        }
        // ③ Android 12+ 从后台启动前台服务受限，失败也要让用户看见。
        try {
            startForegroundService(
                Intent(this, QuroVideoCallService::class.java).apply {
                    action = QuroVideoCallService.ACTION_VIDEO_CALL
                }
            )
        } catch (e: Throwable) {
            Toast.makeText(this, qstr(R.string.qk_03912), Toast.LENGTH_LONG).show()
        }
    }

    private fun openSettings(action: String) {
        runCatching {
            startActivity(
                Intent(action)
                    .setData(Uri.parse("package:" + packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}