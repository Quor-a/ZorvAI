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

/** 语音球磁贴：点击切换悬浮语音球（语音球服务内部 toggleBall）。 */
class QuroVoiceTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = qstr(R.string.qk_03572)
            icon = Icon.createWithResource(this@QuroVoiceTileService, R.mipmap.ic_launcher)
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val i = Intent(this, QuroVoiceBallService::class.java).apply {
            action = QuroVoiceBallService.ACTION_VOICE_TALK
        }
        // TileService 上下文可启动前台服务
        startForegroundService(i)
    }
}
/**
 * 能力广场磁贴（对标小布 `SettingsActivity` 的 `QS_TILE_PREFERENCES` 入口）。
 *
 * 为什么值得单独做个磁贴：小布把「我会什么」做成一级入口，用户随时能看见能力清单；
 * 我们此前所有能力都藏在 AI 的背后，用户不知道能问什么。这个磁贴把能力广场变成
 * 下拉面板里一次点击可达，是"可发现性"最便宜的一次补齐。
 */
class QuroSkillsTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            label = "Zorv 能力"
            icon = Icon.createWithResource(this@QuroSkillsTileService, R.mipmap.ic_launcher)
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        // 闸门：助手服务关了 / 磁贴通道关了 → 不启动（小布那条「任何情况下不允许启动」）
        val denied = com.ai.assistance.quro.core.assistant.AssistantServiceState
            .gate(this, com.ai.assistance.quro.core.assistant.AssistantChannel.TILE)
        if (denied != null) {
            com.ai.assistance.quro.util.QuroDiag.log("assistant-tile", "skills tile denied: ${'$'}{denied.wire()}")
            qsTile?.apply { state = Tile.STATE_UNAVAILABLE; updateTile() }
            return
        }
        val i = Intent(this, com.ai.assistance.quro.activity.AssistantSkillPlazaActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivityAndCollapse(i)
    }
}

/**
 * 我手机呢磁贴（对标小布 `FindPhoneActivity` / `heytap.speechassist.action.FIND_PHONE`）。
 *
 * 手机静音/找不到时，最可靠的动作是**从下拉面板直接点**——不用解锁进去找 App。
 * 点一下立即最大声闹铃 + 震动；再点一下停。
 */
class QuroFindPhoneTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = if (com.ai.assistance.quro.core.assistant.FindMyPhone.ringing) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = if (com.ai.assistance.quro.core.assistant.FindMyPhone.ringing) "Zorv 停止响铃" else "Zorv 我手机呢"
            icon = Icon.createWithResource(this@QuroFindPhoneTileService, R.mipmap.ic_launcher)
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val app = applicationContext
        val denied = com.ai.assistance.quro.core.assistant.AssistantServiceState
            .gate(app, com.ai.assistance.quro.core.assistant.AssistantChannel.TILE)
        if (denied != null) {
            com.ai.assistance.quro.util.QuroDiag.log("assistant-tile", "find-phone tile denied: ${'$'}{denied.wire()}")
            qsTile?.apply { state = Tile.STATE_UNAVAILABLE; updateTile() }
            return
        }
        if (com.ai.assistance.quro.core.assistant.FindMyPhone.ringing) {
            com.ai.assistance.quro.core.assistant.FindMyPhone.stop("tile")
        } else {
            // 磁贴上下文不能弹窗，直接调执行体；失败只落日志（返回码在 qsTile 状态上看）
            com.ai.assistance.quro.core.assistant.FindMyPhone.start(app, 30)
        }
        onStartListening()
    }
}
