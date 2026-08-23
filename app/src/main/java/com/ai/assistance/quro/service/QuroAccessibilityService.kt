package com.ai.assistance.quro.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Quro 无障碍服务（CapOS L1 通道）：
 * 服务连接后注册自身实例，供 CapOS 内核调用 performAction / dispatchGesture 执行界面自动化。
 * 仅实现标准无障碍能力，不收集任何隐私内容。
 *
 * 保活策略：
 * 1. 监听系统广播，服务断开后自动重启
 * 2. 添加心跳检测，定期检查服务状态
 * 3. 优化事件处理，避免过于频繁的回调导致服务被杀死
 */
class QuroAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "QuroAccessibility"
        var instance: QuroAccessibilityService? = null
        private var isServiceRunning = false
        private val handler = Handler(Looper.getMainLooper())
        private var heartbeatRunnable: Runnable? = null

        // 心跳间隔（毫秒）
        private const val HEARTBEAT_INTERVAL = 30_000L // 30秒

        /**
         * 检查无障碍服务是否可用
         */
        fun isServiceAvailable(): Boolean {
            return instance != null && isServiceRunning
        }

        /**
         * 请求重新连接无障碍服务
         */
        fun requestReconnect(context: Context) {
            try {
                // 打开无障碍设置页面，让用户手动重新启用
                val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Log.w(TAG, "请求用户重新启用无障碍服务")
            } catch (e: Exception) {
                Log.e(TAG, "无法打开无障碍设置: ${e.message}")
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isServiceRunning = true
        Log.i(TAG, "无障碍服务已连接")
        startHeartbeat()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 优化：减少日志输出，避免频繁回调导致性能问题
        // 仅在调试模式下输出详细日志
        if (event == null) return

        // 预留扩展点：界面自动化 / 屏幕读取。当前不收集数据。
        // 避免处理过于频繁的事件类型，防止服务被系统杀死
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                // 这些事件非常频繁，不做任何处理
                return
            }
        }
    }

    override fun onInterrupt() {
        // 注意：onInterrupt 由系统在「中断无障碍反馈」时调用（正常交互中频繁触发），
        // 并不代表服务已断开/被禁用，因此绝不能在此清空 instance —— 否则实时检测信号会抖动，
        // 表现为「授权已开但软件显示未检测到」。instance 只在服务真正销毁时清空（见 onDestroy）。
        Log.d(TAG, "无障碍服务被中断（正常行为）")
    }

    override fun onDestroy() {
        stopHeartbeat()
        instance = null
        isServiceRunning = false
        Log.w(TAG, "无障碍服务已销毁")
        super.onDestroy()
    }

    /**
     * 启动心跳检测，定期检查服务状态
     */
    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatRunnable = object : Runnable {
            override fun run() {
                if (isServiceRunning && instance != null) {
                    Log.d(TAG, "无障碍服务心跳检测：正常")
                    handler.postDelayed(this, HEARTBEAT_INTERVAL)
                } else {
                    Log.w(TAG, "无障碍服务心跳检测：异常，尝试重启")
                    tryRestart()
                }
            }
        }
        handler.postDelayed(heartbeatRunnable!!, HEARTBEAT_INTERVAL)
    }

    /**
     * 停止心跳检测
     */
    private fun stopHeartbeat() {
        heartbeatRunnable?.let { handler.removeCallbacks(it) }
        heartbeatRunnable = null
    }

    /**
     * 尝试重启服务
     */
    private fun tryRestart() {
        try {
            // 通知系统重新绑定服务
            val intent = Intent(this, QuroAccessibilityService::class.java)
            startService(intent)
            Log.i(TAG, "尝试重启无障碍服务")
        } catch (e: Exception) {
            Log.e(TAG, "重启无障碍服务失败: ${e.message}")
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
