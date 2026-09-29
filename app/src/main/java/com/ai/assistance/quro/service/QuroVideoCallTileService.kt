package com.ai.assistance.quro.service
import com.ai.assistance.quro.util.qstr

import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.ai.assistance.quro.R

/**
 * 视频通话快捷磁贴：点击启动/切换 [QuroVideoCallService]（开/关）。
 * 与语音球磁贴（[QuroVoiceTileService]）同源的启动方式。
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
        val i = Intent(this, QuroVideoCallService::class.java).apply {
            action = QuroVideoCallService.ACTION_VIDEO_CALL
        }
        startForegroundService(i)
    }
}