package com.ai.assistance.quro.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import com.ai.assistance.quro.activity.QuroMainActivity

/**
 * Quro 无障碍服务（CapOS L1 通道）：
 * 服务连接后注册自身实例，供 CapOS 内核调用 performAction / dispatchGesture 执行界面自动化。
 * 仅实现标准无障碍能力，不收集任何隐私内容。
 *
 * 保活机制：
 * 1. 前台通知：提升进程优先级，减少被系统回收
 * 2. 定时自检：检测服务是否正常运行
 * 3. 掉线通知：服务断开时通知用户重新开启
 */
class QuroAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "QuroA11y"
        private const val CHANNEL_ID = "quro_a11y_channel"
        private const val NOTIFICATION_ID = 9527
        private const val SELF_CHECK_INTERVAL_MS = 30_000L // 30秒自检一次

        var instance: QuroAccessibilityService? = null
            private set

        /** 服务状态回调 */
        var onServiceStatusChanged: ((Boolean) -> Unit)? = null

        /**
         * 检查无障碍服务是否可用
         */
        fun isServiceEnabled(context: Context): Boolean {
            return try {
                val enabledServices = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: ""
                val serviceName = "${context.packageName}/${QuroAccessibilityService::class.java.canonicalName}"
                enabledServices.contains(serviceName)
            } catch (e: Exception) {
                false
            }
        }

        /**
         * 检查服务实例是否存活
         */
        fun isServiceRunning(): Boolean = instance != null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var selfCheckRunnable: Runnable? = null
    private var lastEventTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "无障碍服务已连接")
        instance = this
        onServiceStatusChanged?.invoke(true)

        // 启动前台通知
        startForegroundNotification()

        // 启动定时自检
        startSelfCheck()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 记录最近事件时间，用于判断服务是否正常接收事件
        event?.let {
            lastEventTime = System.currentTimeMillis()
        }
        // 预留扩展点：界面自动化 / 屏幕读取。当前不收集数据。
    }

    override fun onInterrupt() {
        // 注意：onInterrupt 由系统在「中断无障碍反馈」时调用（正常交互中频繁触发），
        // 并不代表服务已断开/被禁用，因此绝不能在此清空 instance —— 否则实时检测信号会抖动，
        // 表现为「授权已开但软件显示未检测到」。instance 只在服务真正销毁时清空（见 onDestroy）。
    }

    override fun onDestroy() {
        Log.w(TAG, "无障碍服务已销毁")
        stopSelfCheck()
        instance = null
        onServiceStatusChanged?.invoke(false)
        super.onDestroy()
    }

    /**
     * 启动前台通知，提升进程优先级
     */
    private fun startForegroundNotification() {
        try {
            // 创建通知渠道（Android 8.0+）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "无障碍服务",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "保持无障碍服务运行"
                    setShowBadge(false)
                }
                val nm = getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(channel)
            }

            // 点击通知打开主界面
            val intent = Intent(this, QuroMainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Zorv AI 无障碍服务")
                .setContentText("正在运行 · 点击打开设置")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setSilent(true)
                .build()

            startForeground(NOTIFICATION_ID, notification)
            Log.d(TAG, "前台通知已启动")
        } catch (e: Exception) {
            Log.e(TAG, "启动前台通知失败", e)
        }
    }

    /**
     * 启动定时自检
     */
    private fun startSelfCheck() {
        stopSelfCheck()
        selfCheckRunnable = object : Runnable {
            override fun run() {
                selfCheck()
                handler.postDelayed(this, SELF_CHECK_INTERVAL_MS)
            }
        }
        handler.postDelayed(selfCheckRunnable!!, SELF_CHECK_INTERVAL_MS)
    }

    /**
     * 停止定时自检
     */
    private fun stopSelfCheck() {
        selfCheckRunnable?.let { handler.removeCallbacks(it) }
        selfCheckRunnable = null
    }

    /**
     * 自检逻辑：验证服务是否正常运行
     */
    private fun selfCheck() {
        try {
            // 检查1：服务实例是否存活
            if (instance == null) {
                Log.e(TAG, "自检失败：服务实例为 null")
                notifyServiceDown("无障碍服务实例已丢失")
                return
            }

            // 检查2：服务是否仍在系统启用列表中
            if (!isServiceEnabled(this)) {
                Log.e(TAG, "自检失败：服务已被系统禁用")
                notifyServiceDown("无障碍服务已被系统禁用，请重新开启")
                return
            }

            // 检查3：检查是否长时间未收到事件（可选，用于判断服务是否卡死）
            // 注意：静默时段（用户不操作手机）不算异常
            val now = System.currentTimeMillis()
            if (lastEventTime > 0 && now - lastEventTime > 5 * 60 * 1000) {
                // 超过5分钟没收到事件，可能是服务卡死，也可能是用户没操作
                // 这里不主动报警，仅记录日志
                Log.d(TAG, "已5分钟未收到无障碍事件")
            }

            Log.d(TAG, "自检通过")
        } catch (e: Exception) {
            Log.e(TAG, "自检异常", e)
        }
    }

    /**
     * 通知服务异常
     */
    private fun notifyServiceDown(reason: String) {
        Log.e(TAG, "服务异常: $reason")
        // 发送通知提醒用户
        try {
            val nm = getSystemService(NotificationManager::class.java)
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val pendingIntent = PendingIntent.getActivity(
                this, 1, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("⚠️ 无障碍服务异常")
                .setContentText(reason)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()

            nm.notify(NOTIFICATION_ID + 1, notification)
        } catch (e: Exception) {
            Log.e(TAG, "发送异常通知失败", e)
        }
    }

    /** 执行 UI 操作（供 CapOS 内核调用）。 */
    fun performAction(nodeInfo: AccessibilityNodeInfo?, action: Int): Boolean {
        return nodeInfo?.performAction(action) ?: false
    }

    /** 全局手势（例如下滑打开控制中心）。 */
    fun dispatchGesture(gesture: GestureDescription) {
        dispatchGesture(gesture, null, null)
    }

    /**
     * 把文字填入当前界面第一个可编辑框，供「粘贴键盘」调用。
     * 优先 ACTION_SET_TEXT（直接写入，无需焦点），失败回退：复制到剪贴板后 ACTION_PASTE。
     * 注意：这是通用辅助能力，不针对任何特定 App，不自动发送、不含任何绕过风控逻辑。
     */
    fun performPaste(text: String): String {
        val root = rootInActiveWindow ?: return "⚠️ 无法获取窗口根节点（APP 是否在前台？）"
        val target = findEditable(root) ?: return "❌ 未找到输入框：请先在目标 App 点一下要填的输入框"
        return try {
            val arg = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arg)) {
                "✅ 已填入输入框"
            } else {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("quro", text))
                if (target.performAction(AccessibilityNodeInfo.ACTION_PASTE)) "✅ 已粘贴" else "❌ 输入失败"
            }
        } catch (e: Exception) {
            "❌ 输入失败：${e.message}"
        }
    }

    private fun findEditable(root: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 12) return null
        if (root.isEditable) return root
        for (i in 0 until root.childCount.coerceAtMost(40)) {
            val child = root.getChild(i) ?: continue
            val found = findEditable(child, depth + 1)
            if (found != null) return found
        }
        return null
    }
}
