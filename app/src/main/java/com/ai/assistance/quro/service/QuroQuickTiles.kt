package com.ai.assistance.quro.service
import com.ai.assistance.quro.util.qstr

import android.content.Intent
import android.graphics.drawable.Icon
import com.ai.assistance.quro.R
import com.ai.assistance.quro.activity.QuroMainActivity
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * 状态栏快捷开关（Quick Settings Tile，非通知栏）。
 *
 * 用户从下拉快捷面板添加这两个磁贴后即可一键：
 *  - [QuroChatTileService]  进入对话（打开主界面并落到对话）
 *  - [QuroVoiceTileService] 切换悬浮语音球（开/关）
 *
 * Tile 在 Android 7+ 受支持；图标用应用 launcher 图标兜底，避免额外资源依赖。
 */

/** 进入对话磁贴。 */
class QuroChatTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            label = qstr(R.string.qk_03460)
            icon = Icon.createWithResource(this@QuroChatTileService, R.mipmap.ic_launcher)
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val i = Intent(this, QuroMainActivity::class.java).apply {
            action = QuroVoiceBallService.ACTION_OPEN_CHAT
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivityAndCollapse(i)
    }
}

/**
 * 语音唤起磁贴 —— 点一下直接进入聆听。
 *
 * 下拉面板是所有唤起方式里**最快**的一条（不用解锁进 App、不用长按电源键）。
 */
class QuroVoiceTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            label = "Zorv 语音"
            icon = Icon.createWithResource(this@QuroVoiceTileService, R.mipmap.ic_launcher)
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val denied = com.ai.assistance.quro.core.assistant.AssistantServiceState
            .gate(this, com.ai.assistance.quro.core.assistant.AssistantChannel.TILE)
        if (denied != null) {
            com.ai.assistance.quro.util.QuroDiag.log("assistant-tile", "voice tile denied: ${denied.wire()}")
            qsTile?.apply { state = Tile.STATE_UNAVAILABLE; updateTile() }
            return
        }
        val started = runCatching {
            startForegroundService(
                Intent(this, QuroVoiceBallService::class.java).apply {
                    action = QuroVoiceBallService.ACTION_VOICE_TALK
                }
            )
        }.isSuccess
        if (!started) {
            com.ai.assistance.quro.util.QuroDiag.log("assistant-tile", "voice ball 起不来，回落打开对话")
            startActivityAndCollapse(
                Intent(this, com.ai.assistance.quro.activity.QuroMainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            )
        }
    }
}
