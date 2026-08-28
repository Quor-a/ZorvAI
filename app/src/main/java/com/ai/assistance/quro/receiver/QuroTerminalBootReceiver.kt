package com.ai.assistance.quro.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ai.assistance.quro.service.QuroTerminalKeepAliveService

/**
 * 终端保活开机接收器：监听 BOOT_COMPLETED 与 MY_PACKAGE_REPLACED。
 *
 * 设备重启 / 应用更新后拉起 [QuroTerminalKeepAliveService]，使其恢复默认终端会话保活，
 * 满足「zorvAI 自启动存活终端保持跟随存话」——即便用户未主动打开终端，
 * 默认共享会话也会在应用进程常驻期间保持存活。
 */
class QuroTerminalBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            QuroTerminalKeepAliveService.ensureStarted(context)
        }
    }
}
