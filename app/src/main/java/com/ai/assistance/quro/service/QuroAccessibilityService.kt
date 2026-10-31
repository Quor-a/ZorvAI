package com.ai.assistance.quro.service
import com.ai.assistance.quro.R
import com.ai.assistance.quro.util.qstr

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
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import com.ai.assistance.quro.activity.QuroMainActivity
import com.ai.assistance.quro.dshmobile.DeviceControlService

/**
 * Quro 无障碍服务（CapOS L1 通道）：**全应用唯一的无障碍服务**。
 *
 * #484（用户截图 `Zorv AI` + `ZorvAI 设备控制` 两项并列）：
 * 合并前本应用在系统「无障碍」列表里注册了**两个**服务——ZorvAI 自己的
 * [QuroAccessibilityService] 与 harness 的
 * [com.ai.assistance.quro.dshmobile.DeviceControlService]。两者是同一项系统权限的两张门，
 * 用户必须开两个开关，关一个就有一半能力失效（「手机操控」报未授权），是实打实的重复授权。
 *
 * 现在**只保留一个服务类**：本类直接继承 DeviceControlService，一套生命周期同时承担
 *   - ZorvAI 侧：节点读取 / 点头 / 粘贴键盘 / 全局动作（本文件原有能力）；
 *   - harness 侧：DeviceControlService 的语义快照、按路径回指的动作、takeScreenshot、
 *     ControlCarrier 轮询登记（父类 onServiceConnected 里完成）。
 * 于是系统设置里只剩一项，开一次两边都通。清单里另一处 `<service>` 已删除，
 * `QuroPermissionHelper` 的启用判据也收敛成单一 ComponentName。
 *
 * 仅实现标准无障碍能力，不收集任何隐私内容。
 *
 * 保活机制：
 * 1. 前台通知：提升进程优先级，减少被系统回收
 * 2. 定时自检：检测服务是否正常运行
 * 3. 掉线通知：服务断开时通知用户重新开启
 */
class QuroAccessibilityService : DeviceControlService() {
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
        // 🔴 super 必须最先调：父类 DeviceControlService 在这里完成 harness 侧的登记
        // （instance / a11yEnabled 标记 / 控制令牌 / ControlCarrier 轮询承接）。
        // 合并服务后这一段就是「一个服务同时供养两边」的关键，漏调会让 harness 侧永远
        // 认为无障碍未开启（症状：手机操控报未授权，而系统里明明已经打开）。
        super.onServiceConnected()
        Log.i(TAG, "无障碍服务已连接（含设备控制通道）")
        instance = this
        // #499：本回调在系统主线程执行，未捕获异常会崩整个 App 进程（无障碍与 Shizuku
        // 两条通道同进程共存，一崩全掉）。super 已做防崩加固，这里的后续步骤同样只裹不外抛。
        runCatching {
            onServiceStatusChanged?.invoke(true)
            // 启动前台通知
            startForegroundNotification()
            // 启动定时自检
            startSelfCheck()
        }.onFailure { t ->
            Log.w(TAG, "onServiceConnected 后续初始化降级: " + (t.message ?: t.javaClass.simpleName))
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 父类用事件给「语义快照」打失效标记（窗口/内容/滚动，200ms 节流），必须保留。
        super.onAccessibilityEvent(event)
        // 记录最近事件时间，用于判断服务是否正常接收事件
        event?.let {
            lastEventTime = System.currentTimeMillis()
        }
        // 预留扩展点：界面自动化 / 屏幕读取。当前不收集数据。
    }

    // ═══════════════ 后台虚拟屏支持（移植自 Mika 的 ScreenAccessibility）═══════════════
    //
    // 关键能力：无障碍服务能**按 displayId** 拿到那块后台虚拟屏上的窗口树，
    // 也能**在指定屏上**下发手势。没有这两条，后台屏就只能靠截图猜坐标；
    // 有了它们，AI 才能真正「读界面结构 → 精确定位控件 → 点击」。

    /**
     * 指定屏上的全部窗口（含输入法、浮层）。
     *
     * 该屏没有窗口、或系统不支持按屏枚举时返回空列表。
     * 注意：不要用 `windows`（那是当前活跃屏），后台屏不是活跃屏。
     */
    fun windowsOn(displayId: Int): List<AccessibilityWindowInfo> {
        if (displayId < 0) return emptyList()
        return runCatching {
            val all = windowsOnAllDisplays ?: return emptyList()
            all.get(displayId).orEmpty()
        }.getOrElse {
            Log.w(TAG, "windowsOn($displayId) 失败: ${it.message}")
            emptyList()
        }
    }

    /**
     * 在指定屏上下发一次手势（单指移动/点击/滑动都由 [paths] 描述）。
     *
     * 与后台注入（Shizuku injectInputEvent）相比，这条路不需要任何特权，
     * 只要无障碍服务在跑；缺点是不能跨屏乱发（这里也只发到指定屏）。
     *
     * @return 手势是否被系统接受并完成
     */
    fun gestureOn(displayId: Int, paths: List<Path>, durationMs: Long): Boolean {
        if (paths.isEmpty()) return false
        // GestureDescription.Builder.setDisplayId 是 API 30 才有的；低于此版本只能走主屏
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        if (displayId < 0) return false

        return runCatching {
            val builder = GestureDescription.Builder().setDisplayId(displayId)
            paths.forEach { builder.addStroke(GestureDescription.StrokeDescription(it, 0, durationMs.coerceAtLeast(1))) }
            dispatchGesture(builder.build(), null, null)
        }.getOrElse {
            Log.w(TAG, "gestureOn($displayId) 失败: ${it.message}")
            false
        }
    }

    /** 在指定屏上点一下（无障碍手势通道，无需 Shizuku）。 */
    fun tapOn(displayId: Int, x: Float, y: Float, durationMs: Long = 40): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return gestureOn(displayId, listOf(path), durationMs)
    }

    /** 在指定屏上从 (x1,y1) 滑到 (x2,y2)（无障碍手势通道，无需 Shizuku）。 */
    fun swipeOn(displayId: Int, x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        return gestureOn(displayId, listOf(path), durationMs)
    }

    override fun onInterrupt() {        // 注意：onInterrupt 由系统在「中断无障碍反馈」时调用（正常交互中频繁触发），
        // 并不代表服务已断开/被禁用，因此绝不能在此清空 instance —— 否则实时检测信号会抖动，
        // 表现为「授权已开但软件显示未检测到」。instance 只在服务真正销毁时清空（见 onDestroy）。
        super.onInterrupt()
    }

    override fun onDestroy() {
        Log.w(TAG, "无障碍服务已销毁")
        stopSelfCheck()
        // 先清本类实例，再交给父类做 harness 侧 teardown（置标记、注销 ControlCarrier）。
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
                    qstr(R.string.qk_02300),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = qstr(R.string.qk_03430)
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