package com.ai.assistance.quro.service

import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * 快捷设置磁贴：
 * 下拉快捷面板一键 显示/关闭 桌宠；无悬浮窗权限时跳权限页。
 */
class PetTileService : TileService() {

    override fun onClick() {
        super.onClick()
        val enabled = getSharedPreferences("pet_prefs", Context.MODE_PRIVATE)
            .getBoolean("pet_visible", true)
        if (enabled) {
            getSharedPreferences("pet_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("pet_visible", false).apply()
            sendBroadcast(
                android.content.Intent("com.ai.assistance.quro.action.DESKTOP_PET_APPLY_SETTINGS")
                    .setPackage(packageName)
            )
            updateTile(false)
        } else {
            if (android.provider.Settings.canDrawOverlays(this)) {
                getSharedPreferences("pet_prefs", Context.MODE_PRIVATE)
                    .edit().putBoolean("pet_visible", true).apply()
                runCatching {
                    com.ai.assistance.quro.service.QuroDesktopPetService.start(this)
                }
                sendBroadcast(
                    android.content.Intent("com.ai.assistance.quro.action.DESKTOP_PET_APPLY_SETTINGS")
                        .setPackage(packageName)
                )
                updateTile(true)
            } else {
                runCatching {
                    startActivity(
                        android.content.Intent(
                            this,
                            com.ai.assistance.quro.activity.QuroMainActivity::class.java
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTile(
            getSharedPreferences("pet_prefs", Context.MODE_PRIVATE)
                .getBoolean("pet_visible", true)
        )
    }

    private fun updateTile(on: Boolean) {
        qsTile?.apply {
            state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = if (on) "关闭全部宠物" else "显示全部宠物"
            updateTile()
        }
    }
}
