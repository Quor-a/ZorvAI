package com.ai.assistance.quro.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.ai.assistance.quro.core.tools.QuroVoiceFeaturePrefs
import com.ai.assistance.quro.service.QuroBotBootstrapService
import com.ai.assistance.quro.service.QuroVoiceBallService

/**
 * 开机自启动接收器：监听 BOOT_COMPLETED。
 *
 * 1. 始终拉起「机器人保活服务」[QuroBotBootstrapService]，使其连接所有已启用且已配置的
 *    飞书 / QQ / 微信机器人（它们依赖应用进程常驻，否则开机后无法自动回复消息）。
 * 2. 若用户在「语音设置」开启「后台自启动」，额外拉起常住语音球服务。
 */
class QuroBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        // 1) 机器人保活：开机即连接已启用的机器人平台
        QuroBotBootstrapService.ensureStarted(context)

        // 2) 语音球自启动（按用户偏好）
        if (QuroVoiceFeaturePrefs.getAutostart(context)) {
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
}
