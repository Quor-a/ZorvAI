package com.ai.assistance.quro.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.ai.assistance.quro.core.tools.QuroVoiceFeaturePrefs
import com.ai.assistance.quro.service.QuroVoiceBallService

/**
 * 开机自启动接收器：监听 BOOT_COMPLETED。
 * 仅当用户在「语音设置」开启「后台自启动」时，才拉起常住语音球服务
 * （服务会展示常驻通知栏 + 悬浮球，但不自动开始聆听，等用户点按）。
 */
class QuroBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!QuroVoiceFeaturePrefs.getAutostart(context)) return
        val i = Intent(context, QuroVoiceBallService::class.java).apply {
            putExtra(QuroVoiceBallService.EXTRA_NO_LISTEN, true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(i)
        } else {
            context.startService(i)
        }
    }
}
