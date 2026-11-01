package com.ai.assistance.quro.core.adb

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.regex.Pattern

/**
 * 无线调试**配对码上通知栏**。
 *
 * ## 为什么必须走无障碍读屏（而不是 API）
 *
 * Android 11+ 的「无线调试」配对码由系统 `adb_wifi` 服务随机生成，**只渲染在
 * 系统设置的弹窗上**，shell / 普通 API 一律读不到（安全设计，SELinux 拒绝）。
 * 但本应用的无障碍服务能读到屏幕文本 —— 用户在系统里点出「使用配对码配对设备」
 * 弹窗的瞬间，我们把 **6 位配对码 + IP:端口** 抓出来发成**常驻通知**：
 * 另一台设备/电脑要连这台机时，不用再去开发者选项里翻，拉下通知栏直接看。
 *
 * ## 抓取策略（只认系统设置页，防误报）
 *
 * - 触发：`com.android.settings` 包的窗口状态/内容变化事件，600ms 节流；
 * - IP:端口：正则 `\d{1,3}(\.\d{1,3}){3}:\d{2,5}`（配对弹窗独有格式）；
 * - 配对码：含「配对码 / pairing code」文案的窗口里，取**独立的 6 位数字**
 *   （`(?<!\d)\d{6}(?!\d)`，IP:端口的端口是 4–5 位不会撞）。
 *
 * 抓到即发/更新通知（同 ID 覆盖）；弹窗关掉后通知保留最后已知值（码本身随弹窗关闭失效，
 * 通知就是「最近一次」的记录，用户重新点开弹窗会自动刷新）。
 */
object QuroAdbPairNotifier {

    private const val CHANNEL_ID = "quro_adb_pair"
    private const val NOTIFY_ID = 2043

    private const val SETTINGS_PKG = "com.android.settings"

    private val IP_PORT: Pattern = Pattern.compile("""\b\d{1,3}(?:\.\d{1,3}){3}:\d{2,5}\b""")
    private val CODE6: Pattern = Pattern.compile("""(?<!\d)\d{6}(?!\d)""")
    private val CODE_HINT = listOf("配对码", "pairing code", "wifi pairing", "wi-fi pairing")

    private var lastNotifyAt = 0L

    /** 无障碍事件入口（由 [QuroAccessibilityService.onAccessibilityEvent] 调用）。 */
    fun onAccessibilityEvent(service: AccessibilityService, pkg: String?, type: Int) {
        if (pkg != SETTINGS_PKG) return
        val isWindow = type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        val isContent = type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        if (!isWindow && !isContent) return
        // 节流：设置页内容变化很频繁，600ms 抓一次足够（弹窗至少停留几秒）
        val now = System.currentTimeMillis()
        if (now - lastNotifyAt < 600) return
        lastNotifyAt = now

        try {
            val root = service.rootInActiveWindow ?: return
            val texts = StringBuilder()
            collectTexts(root, texts, 0)
            if (texts.isEmpty()) return
            val joined = texts.toString()

            val mIp = IP_PORT.matcher(joined)
            val ipPort = if (mIp.find()) mIp.group() else null
            // 配对码：弹窗里出现「配对码」字样才认 6 位数字（避免抓到设置页其它编号）
            val lower = joined.lowercase()
            val hasHint = CODE_HINT.any { lower.contains(it) }
            if (!hasHint && ipPort == null) return

            var code: String? = null
            if (hasHint) {
                val m6 = CODE6.matcher(joined)
                if (m6.find()) code = m6.group()
            }
            if (code == null && ipPort == null) return
            notify(service, code, ipPort)
        } catch (_: Throwable) {
            // 无障碍读屏失败静默忽略：不能因为抓码影响服务其它能力
        }
    }

    /** 深度优先收集节点文本（防环 + 限深）。 */
    private fun collectTexts(node: AccessibilityNodeInfo?, out: StringBuilder, depth: Int) {
        if (node == null || depth > 30) return
        node.text?.let { if (it.isNotBlank()) out.append(it).append('\n') }
        node.contentDescription?.let { if (it.isNotBlank()) out.append(it).append('\n') }
        for (i in 0 until node.childCount) {
            runCatching { node.getChild(i) }.getOrNull()?.let {
                collectTexts(it, out, depth + 1)
                it.recycle()
            }
        }
    }

    /** 发/更新常驻通知；点击跳系统开发者选项（用户去核对/刷新配对弹窗）。 */
    private fun notify(ctx: Context, code: String?, ipPort: String?) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "无线调试配对码",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { setDescription("系统弹出无线调试配对码时自动抓取并显示在这里") },
            )
        }
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            ctx, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (code != null) "无线调试配对码：$code" else "无线调试已打开"
        val text = buildString {
            ipPort?.let { append("连接地址 $it") }
            if (code != null) {
                if (isNotEmpty()) append(" · ")
                append("码只在配对弹窗打开期间有效")
            } else {
                append("在系统「无线调试」里点「使用配对码配对设备」即可自动抓取")
            }
        }
        val n: Notification = Notification.Builder(ctx, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(title + "\n" + text))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .build()
        runCatching { nm.notify(NOTIFY_ID, n) }
    }

    /** 清掉配对码通知（面板里主动关闭时调用，可选）。 */
    fun clear(ctx: Context) {
        runCatching {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(NOTIFY_ID)
        }
    }
}
