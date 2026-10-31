package com.ai.assistance.quro.termux.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ai.assistance.quro.termux.R
import com.ai.assistance.quro.termux.shell.QuroTerminalSession
import com.ai.assistance.quro.termux.util.TermuxConstants
import com.ai.assistance.quro.terminal.runtime.TerminalEnvironment
import com.ai.assistance.quro.vt.TerminalSession
import com.ai.assistance.quro.vt.TerminalSessionClient

/**
 * 增强终端宿主前台服务（移植自 Termux `TermuxService`，去品牌化精简版）。
 *
 * 职责：
 * - 常驻前台服务，保证 proot 终端会话在后台不被系统回收
 * - 管理会话列表（创建/切换/重命名/删除）
 * - 转发终端回调（文本变化/标题变化/会话结束）到 Activity
 * - 通知栏展示会话状态 + Exit / Wake lock 动作
 * - WakeLock 防止屏幕关闭后 CPU 休眠
 */
class TermuxHostService : Service(), TerminalSessionClient {

    companion object {
        private const val TAG = "TermuxHostService"

        const val CHANNEL_ID = TermuxConstants.TERMUX_APP_NOTIFICATION_CHANNEL_ID
        const val NOTIFICATION_ID = TermuxConstants.TERMUX_APP_NOTIFICATION_ID

        /** 会话数上限（防止无界增长，Termux 默认无硬限，这里保护性限制）。 */
        const val MAX_SESSIONS = 16
    }

    /** 宿主 Activity 客户端回调接口。 */
    interface TermuxTerminalSessionActivityClient {
        fun onSessionListChanged()
        fun onSessionSelected(session: QuroTerminalSession)
        fun onSessionFinished(session: QuroTerminalSession)
        fun onTextChanged(session: QuroTerminalSession)
        fun onTitleChanged(session: QuroTerminalSession)
    }

    class LocalBinder : Binder() {
        lateinit var service: TermuxHostService
    }

    private val mBinder = LocalBinder()
    private val mHandler = Handler(Looper.getMainLooper())

    /** 会话列表（按创建顺序）。 */
    private val mTermuxSessions = ArrayList<QuroTerminalSession>()

    /** 当前会话索引。 */
    private var mCurrentSessionIndex = -1

    /** Activity 客户端（Activity 绑定后设置）。 */
    private var mTermuxTerminalSessionActivityClient: TermuxTerminalSessionActivityClient? = null

    /** 终端环境（rootfs 准备 + proot 启动参数），延迟初始化。 */
    private val mTerminalEnvironment: TerminalEnvironment by lazy { TerminalEnvironment(this) }

    /** 唤醒锁。 */
    private var mWakeLock: PowerManager.WakeLock? = null

    /** 服务是否希望尽快停止（Activity 已关闭，仅剩后台会话）。 */
    @Volatile
    private var mWantsToStop = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // 启动前台服务（manifest 已声明 specialUse 类型）
        startAsForeground()
        Log.i(TAG, "TermuxHostService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            TermuxConstants.TERMUX_SERVICE.ACTION_STOP_SERVICE -> {
                Log.i(TAG, "ACTION_STOP_SERVICE received, stopping service")
                stopSelf()
                return START_NOT_STICKY
            }
            TermuxConstants.TERMUX_SERVICE.ACTION_STOP_SESSION -> {
                val sessionId = intent.getStringExtra(TermuxConstants.TERMUX_SERVICE.EXTRA_WAKE_LOCK)
                if (sessionId != null) {
                    removeSessionById(sessionId)
                }
            }
            TermuxConstants.TERMUX_SERVICE.ACTION_WAKE_LOCK -> acquireWakeLock()
            TermuxConstants.TERMUX_SERVICE.ACTION_WAKE_UNLOCK -> releaseWakeLock()
        }
        // 服务被杀后不自动重启（避免在无用户意图时重新拉起终端）
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        mBinder.service = this
        return mBinder
    }

    // ==================== 会话管理 ====================

    /** 创建一个新的 proot 终端会话并加入列表。 */
    fun addSession(initialName: String?, failsafe: Boolean, callback: ((QuroTerminalSession?) -> Unit)? = null) {
        if (mTermuxSessions.size >= MAX_SESSIONS) {
            Log.w(TAG, "达到会话数上限 $MAX_SESSIONS，拒绝创建")
            callback?.invoke(null)
            return
        }
        // 在后台线程执行 rootfs 准备（首次 3-8 分钟），避免阻塞主线程
        Thread {
            try {
                val name = initialName ?: "ubuntu"
                val id = generateSessionId()
                val session = QuroTerminalSession(id, name, mTerminalEnvironment, this)
                mHandler.post {
                    mTermuxSessions.add(session)
                    mCurrentSessionIndex = mTermuxSessions.size - 1
                    mTermuxTerminalSessionActivityClient?.onSessionListChanged()
                    mTermuxTerminalSessionActivityClient?.onSessionSelected(session)
                    updateNotification()
                    callback?.invoke(session)
                }
            } catch (e: Exception) {
                Log.e(TAG, "创建会话失败", e)
                mHandler.post { callback?.invoke(null) }
            }
        }.start()
    }

    private fun generateSessionId(): String =
        "${System.currentTimeMillis()}-${(Math.random() * 100000).toInt()}"

    /** 会话列表（Activity 客户端别名）。 */
    val termuxSessions: List<QuroTerminalSession> get() = ArrayList(mTermuxSessions)

    fun isTermuxSessionsEmpty(): Boolean = mTermuxSessions.isEmpty()

    /** 当前会话（Kotlin 属性别名）。 */
    val currentSession: QuroTerminalSession?
        get() = if (mCurrentSessionIndex in mTermuxSessions.indices) mTermuxSessions[mCurrentSessionIndex] else null

    fun getCurrentSessionIndex(): Int = mCurrentSessionIndex

    fun getNextSessionIndex(): Int {
        if (mTermuxSessions.isEmpty()) return -1
        return (mCurrentSessionIndex + 1) % mTermuxSessions.size
    }

    fun getPreviousSessionIndex(): Int {
        if (mTermuxSessions.isEmpty()) return -1
        return if (mCurrentSessionIndex <= 0) mTermuxSessions.size - 1 else mCurrentSessionIndex - 1
    }

    fun switchToSession(index: Int) {
        if (index !in mTermuxSessions.indices) return
        mCurrentSessionIndex = index
        val session = mTermuxSessions[index]
        mTermuxTerminalSessionActivityClient?.onSessionSelected(session)
        updateNotification()
    }

    fun setCurrentSession(session: QuroTerminalSession) {
        val index = mTermuxSessions.indexOf(session)
        if (index >= 0) {
            mCurrentSessionIndex = index
            mTermuxTerminalSessionActivityClient?.onSessionSelected(session)
            updateNotification()
        }
    }

    /** 重命名会话。 */
    fun renameSession(session: QuroTerminalSession, newName: String) {
        session.name = newName
        mTermuxTerminalSessionActivityClient?.onSessionListChanged()
        updateNotification()
    }

    /** 按对象移除会话。 */
    fun removeSession(session: QuroTerminalSession) {
        val index = mTermuxSessions.indexOf(session)
        if (index < 0) return
        session.finishIfRunning()
        mTermuxSessions.removeAt(index)
        if (mCurrentSessionIndex >= mTermuxSessions.size) {
            mCurrentSessionIndex = mTermuxSessions.size - 1
        }
        if (mCurrentSessionIndex >= 0) {
            mTermuxTerminalSessionActivityClient?.onSessionSelected(mTermuxSessions[mCurrentSessionIndex])
        } else {
            mTermuxTerminalSessionActivityClient?.onSessionListChanged()
        }
        mTermuxTerminalSessionActivityClient?.onSessionListChanged()
        updateNotification()
    }

    private fun removeSessionById(sessionId: String) {
        val session = mTermuxSessions.firstOrNull { it.sessionId == sessionId } ?: return
        removeSession(session)
    }

    /** 服务是否希望尽快停止（Activity 关闭时）。 */
    fun wantsToStop(): Boolean = mWantsToStop

    /** 会话数量（Activity 客户端别名）。 */
    val termuxSessionCount: Int get() = mTermuxSessions.size

    /** 获取指定 TerminalSession 对应的 QuroTerminalSession 在列表中的索引。 */
    fun getIndexOfSession(terminalSession: TerminalSession): Int =
        mTermuxSessions.indexOfFirst { it.terminalSession === terminalSession }

    /** 获取指定索引的会话。 */
    fun getSessionAtIndex(index: Int): QuroTerminalSession? =
        if (index in mTermuxSessions.indices) mTermuxSessions[index] else null

    /** 按会话 handle 查找会话。 */
    fun getSessionForHandle(handle: String): QuroTerminalSession? =
        mTermuxSessions.firstOrNull { it.terminalSession.mHandle == handle }

    /** 获取最后一个会话。 */
    fun getLastSession(): QuroTerminalSession? = mTermuxSessions.lastOrNull()

    /** 移除指定 TerminalSession 对应的会话，返回原索引。 */
    fun removeSessionForTerminalSession(terminalSession: TerminalSession): Int {
        val index = getIndexOfSession(terminalSession)
        if (index < 0) return index
        removeSession(mTermuxSessions[index])
        return index
    }

    fun removeAllSessions() {
        for (session in mTermuxSessions) {
            session.finishIfRunning()
        }
        mTermuxSessions.clear()
        mCurrentSessionIndex = -1
        mTermuxTerminalSessionActivityClient?.onSessionListChanged()
        updateNotification()
    }

    // ==================== Activity 客户端绑定 ====================

    fun setTermuxTerminalSessionActivityClient(client: TermuxTerminalSessionActivityClient?) {
        mTermuxTerminalSessionActivityClient = client
        // 绑定后立即同步当前会话
        if (client != null) {
            val current = currentSession
            if (current != null) {
                client.onSessionSelected(current)
            }
        }
    }

    fun unsetTermuxTerminalSessionActivityClient() {
        mTermuxTerminalSessionActivityClient = null
    }

    // ==================== TerminalSessionClient 回调转发 ====================

    private fun findQuroSession(terminalSession: TerminalSession): QuroTerminalSession? =
        mTermuxSessions.firstOrNull { it.terminalSession === terminalSession }

    override fun onTextChanged(changedSession: TerminalSession) {
        findQuroSession(changedSession)?.let {
            mTermuxTerminalSessionActivityClient?.onTextChanged(it)
        }
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        findQuroSession(changedSession)?.let {
            mTermuxTerminalSessionActivityClient?.onTitleChanged(it)
        }
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        val quroSession = findQuroSession(finishedSession)
        mHandler.post {
            if (quroSession != null) {
                mTermuxSessions.remove(quroSession)
                if (mCurrentSessionIndex >= mTermuxSessions.size) {
                    mCurrentSessionIndex = mTermuxSessions.size - 1
                }
                mTermuxTerminalSessionActivityClient?.onSessionFinished(quroSession)
                mTermuxTerminalSessionActivityClient?.onSessionListChanged()
                updateNotification()
            }
        }
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val context = applicationContext
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = clipboard.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(this) ?: return
        session?.write(text.toString().toByteArray(), 0, text.length)
    }

    override fun onBell(session: TerminalSession) {
        // 震动反馈（可被属性 bell-character 控制，这里保持默认 vibrate）
        runCatching {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            if (vibrator.hasVibrator()) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(30, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
    }

    override fun onColorsChanged(session: TerminalSession) {
        // 忽略，颜色变化由 TerminalView 渲染
    }

    override fun onTerminalCursorStateChange(state: Boolean) {
        // 忽略
    }

    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {
        // 忽略，pid 可从 QuroTerminalSession.pid 获取
    }

    override fun getTerminalCursorStyle(): Int =
        when (mProperties.getTerminalCursorStyle()) {
            "underline" -> com.ai.assistance.quro.vt.TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE
            "bar" -> com.ai.assistance.quro.vt.TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR
            else -> com.ai.assistance.quro.vt.TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK
        }

    private val mProperties: com.ai.assistance.quro.termux.settings.TermuxAppSharedProperties by lazy {
        com.ai.assistance.quro.termux.settings.TermuxAppSharedProperties.rebuild(this)
    }

    override fun logError(tag: String, message: String) { Log.e(tag, message) }
    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "stacktrace", e) }

    // ==================== 通知 ====================

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "增强终端会话",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "ZorvAI 增强终端后台会话通知"
            setShowBadge(false)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, TermuxHostActivity::class.java)
        val contentIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val sessionCount = mTermuxSessions.size
        val sessionName = currentSession?.name ?: "无会话"

        // Exit 动作
        val stopIntent = Intent(this, TermuxHostService::class.java).apply {
            action = TermuxConstants.TERMUX_SERVICE.ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Wake lock 动作
        val wakeLocked = mWakeLock?.isHeld == true
        val wakeIntent = Intent(this, TermuxHostService::class.java).apply {
            action = if (wakeLocked) TermuxConstants.TERMUX_SERVICE.ACTION_WAKE_UNLOCK
            else TermuxConstants.TERMUX_SERVICE.ACTION_WAKE_LOCK
        }
        val wakePendingIntent = PendingIntent.getService(
            this, 2, wakeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ZorvAI 增强终端")
            .setContentText("会话：$sessionName（共 $sessionCount 个）")
            .setSmallIcon(R.drawable.ic_service_notification)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, if (wakeLocked) "释放唤醒锁" else "唤醒锁", wakePendingIntent)
            .addAction(0, "退出", stopPendingIntent)
            .build()
    }

    fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    // ==================== WakeLock ====================

    private fun acquireWakeLock() {
        if (mWakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TermuxHostService:wake")
        }
        mWakeLock?.acquire(6 * 60 * 60 * 1000L) // 6 小时
        updateNotification()
        Log.i(TAG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        if (mWakeLock?.isHeld == true) {
            mWakeLock?.release()
        }
        updateNotification()
        Log.i(TAG, "WakeLock released")
    }

    // ==================== 销毁 ====================

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        for (session in mTermuxSessions) {
            session.finishIfRunning()
        }
        mTermuxSessions.clear()
        Log.i(TAG, "TermuxHostService destroyed")
    }
}