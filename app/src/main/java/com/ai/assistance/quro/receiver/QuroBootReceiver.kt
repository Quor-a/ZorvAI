package com.ai.assistance.quro.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.ai.assistance.quro.core.tools.QuroVoiceFeaturePrefs
import com.ai.assistance.quro.service.QuroBotBootstrapService
import com.ai.assistance.quro.service.QuroDesktopPetService
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
        val action = intent?.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

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

        // 3) 应用内闹钟重建排程（开机后 AlarmManager 已清空，需按持久化数据重排）
        com.ai.assistance.quro.permissions.QuroAlarmScheduler(context).scheduleAll()

        // 4) 桌面宠物自恢复（2026-11-05 自启动模块）：
        //    开关持久化了却从不开机拉起，用户重启手机后桌宠必须手动再开一次。
        //    start() 内部会检查悬浮窗权限，缺失时安静返回 false。
        if (QuroVoiceFeaturePrefs.getDesktopPet(context)) {
            runCatching { QuroDesktopPetService.start(context) }
        }

        // 5) 覆盖安装后的完整性校验：安装过程会强杀进程，若刚好撞上写盘窗口，
        //    这里提前把损坏文件隔离并留下备份，避免用户首次启动直接面对空会话列表。
        //    放后台线程执行，广播接收不能做全量 JSON 解析这类重 IO。
        if (action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Thread({
                runCatching {
                    com.ai.assistance.quro.core.QuroConversationRepository(context.applicationContext).loadAll()
                }
            }, "quro-conv-verify").apply { isDaemon = true }.start()
        }
    }
}
