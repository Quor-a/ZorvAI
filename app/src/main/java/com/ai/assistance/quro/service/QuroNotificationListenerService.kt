package com.ai.assistance.quro.service

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * ZorvAI 通知侦听服务（对应 PERMISSIONS.md #8 设备与通知）。
 *
 * 用户授权「通知访问」后，本服务可：
 *  - 读取所有通知（含联系人姓名与通知内容等个人信息）；
 *  - 清除通知、触发通知中的按钮；
 *  - 读取/设置勿扰（DND）状态。
 *
 * 通知内容（敏感信息）仅缓存在本机内存（[NotificationCache]），供 AI 在端侧理解上下文，
 * 不会外传。如需清除通知 / 触发按钮，由 AI 工具经本服务实例调用对应方法完成。
 */
class QuroNotificationListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.isOngoing) return // 跳过持续型（如前台服务通知）
        NotificationCache.ingest(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        NotificationCache.evict(sbn.key)
    }

    /** 供 AI 工具调用：清除某条通知。 */
    fun dismiss(key: String) = cancelNotification(key)

    /** 供 AI 工具调用：清除全部本服务收到的通知。 */
    fun dismissAll() = cancelAllNotifications()

    companion object {
        /** 最近通知的内存缓存（仅本机），供 AI / 工具读取上下文。 */
        object NotificationCache {
            private const val MAX = 200
            private val list = ArrayDeque<Entry>()

            data class Entry(
                val key: String,
                val packageName: String,
                val title: String?,
                val text: String?,
                val postTime: Long,
            )

            @Synchronized
            fun ingest(sbn: StatusBarNotification) {
                val n: Notification = sbn.notification
                val extras: Bundle? = n.extras
                val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                list.removeAll { it.key == sbn.key }
                list.addLast(Entry(sbn.key, sbn.packageName, title, text, sbn.postTime))
                while (list.size > MAX) list.removeFirst()
            }

            @Synchronized
            fun evict(key: String) {
                list.removeAll { it.key == key }
            }

            @Synchronized
            fun snapshot(): List<Entry> = list.toList()
        }
    }
}
