package com.ai.assistance.quro.termux.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.SoundPool
import android.text.TextUtils
import android.widget.ListView
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import com.ai.assistance.quro.termux.R
import com.ai.assistance.quro.termux.shell.QuroTerminalSession
import com.ai.assistance.quro.termux.util.Logger
import com.ai.assistance.quro.termux.util.ShareUtils
import com.ai.assistance.quro.termux.util.TextInputDialogUtils
import com.ai.assistance.quro.vt.TerminalSession
import com.ai.assistance.quro.vt.TerminalSessionClient
import com.ai.assistance.quro.vt.TerminalColors
import com.ai.assistance.quro.vt.TextStyle
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.Properties

/**
 * 终端会话 Activity 客户端（移植自 Termux `TermuxTerminalSessionActivityClient`）。
 *
 * 实现 [TerminalSessionClient] 的 Activity 侧逻辑：
 * - 文本/标题变化 → 刷新视图 + 列表通知
 * - 会话结束 → 提示 + 自动移除
 * - 复制/粘贴/铃声
 * - 字体与配色（colors.properties + font.ttf）
 * - 会话管理：切换/重命名/新建/移除
 */
class TermuxTerminalSessionActivityClient(private val mActivity: TermuxHostActivity) :
    TerminalSessionClient {

    private val LOG_TAG = "TermuxTerminalSessionActivityClient"

    private val MAX_SESSIONS = 8

    private var mBellSoundPool: SoundPool? = null
    private var mBellSoundId = 0

    /** 在 Activity.onCreate() 时调用。 */
    fun onCreate() {
        checkForFontAndColors()
    }

    /** 在 Activity.onStart() 时调用。 */
    fun onStart() {
        if (mActivity.termuxHostService != null) {
            getCurrentStoredSessionOrLast()?.let { setCurrentSession(it) }
            termuxSessionListNotifyUpdated()
        }
        mActivity.terminalView.onScreenUpdated()
    }

    /** 在 Activity.onResume() 时调用。 */
    fun onResume() {
        loadBellSoundPool()
    }

    /** 在 Activity.onStop() 时调用。 */
    fun onStop() {
        setCurrentStoredSession()
        releaseBellSoundPool()
    }

    /** 在 Activity.reloadActivityStyling() 时调用。 */
    fun onReloadActivityStyling() {
        checkForFontAndColors()
    }

    // ==================== TerminalSessionClient ====================

    override fun onTextChanged(@NonNull changedSession: TerminalSession) {
        if (!mActivity.isVisible()) return
        if (mActivity.currentQuroSession?.terminalSession == changedSession) {
            mActivity.terminalView.onScreenUpdated()
        }
    }

    override fun onTitleChanged(@NonNull updatedSession: TerminalSession) {
        if (!mActivity.isVisible()) return
        val current = mActivity.currentQuroSession
        if (updatedSession !== current?.terminalSession) {
            mActivity.showToast(toToastTitle(updatedSession), true)
        }
        termuxSessionListNotifyUpdated()
    }

    override fun onSessionFinished(@NonNull finishedSession: TerminalSession) {
        val service = mActivity.termuxHostService
        if (service == null || service.wantsToStop()) {
            mActivity.finishActivityIfNotFinishing()
            return
        }

        val index = service.getIndexOfSession(finishedSession)

        if (mActivity.isVisible() && finishedSession !== mActivity.currentQuroSession?.terminalSession) {
            if (index >= 0) {
                mActivity.showToast(toToastTitle(finishedSession) + " - exited", true)
            }
        }

        if (mActivity.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
            if (service.termuxSessionCount > 1) {
                removeFinishedSession(finishedSession)
            }
        } else {
            if (finishedSession.getExitStatus() == 0 || finishedSession.getExitStatus() == 130) {
                removeFinishedSession(finishedSession)
            }
        }
    }

    override fun onCopyTextToClipboard(@NonNull session: TerminalSession, text: String) {
        if (!mActivity.isVisible()) return
        ShareUtils.copyTextToClipboard(mActivity, text, "")
    }

    override fun onPasteTextFromClipboard(@Nullable session: TerminalSession?) {
        if (!mActivity.isVisible()) return
        val text = ShareUtils.getTextStringFromClipboardIfSet(mActivity, true) ?: return
        mActivity.terminalView.mEmulator?.paste(text)
    }

    override fun onBell(@NonNull session: TerminalSession) {
        if (!mActivity.isVisible()) return
        when (mActivity.properties.getBellBehaviour()) {
            "vibrate" -> {
                runCatching {
                    val vibrator = mActivity.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
                    if (vibrator.hasVibrator()) {
                        vibrator.vibrate(android.os.VibrationEffect.createOneShot(30, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                    }
                }
            }
            "beep" -> {
                loadBellSoundPool()
                mBellSoundPool?.play(mBellSoundId, 1f, 1f, 1, 0, 1f)
            }
            "ignore" -> { /* 忽略 */ }
        }
    }

    override fun onColorsChanged(@NonNull changedSession: TerminalSession) {
        if (mActivity.currentQuroSession?.terminalSession == changedSession) {
            updateBackgroundColor()
        }
    }

    override fun onTerminalCursorStateChange(enabled: Boolean) {
        if (enabled && !mActivity.isVisible()) return
        mActivity.terminalView.setTerminalCursorBlinkerState(enabled, false)
    }

    override fun setTerminalShellPid(@NonNull terminalSession: TerminalSession, pid: Int) {
        // pid 已存于 QuroTerminalSession.pid
    }

    override fun getTerminalCursorStyle(): Int =
        when (mActivity.properties.getTerminalCursorStyle()) {
            "underline" -> com.ai.assistance.quro.vt.TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE
            "bar" -> com.ai.assistance.quro.vt.TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR
            else -> com.ai.assistance.quro.vt.TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK
        }

    override fun logError(tag: String, message: String) { Logger.logError(tag, message) }
    override fun logWarn(tag: String, message: String) { Logger.logWarn(tag, message) }
    override fun logInfo(tag: String, message: String) { Logger.logInfo(tag, message) }
    override fun logDebug(tag: String, message: String) { Logger.logDebug(tag, message) }
    override fun logVerbose(tag: String, message: String) { Logger.logVerbose(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Logger.logStackTraceWithMessage(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception) { Logger.logStackTrace(tag, e) }

    /** 在 Activity.onResetTerminalSession() 时调用。 */
    fun onResetTerminalSession() {
        mActivity.terminalView.setTerminalCursorBlinkerState(true, true)
    }

    // ==================== 会话管理 ====================

    /** 设置当前会话（切换到指定会话）。 */
    fun setCurrentSession(session: QuroTerminalSession) {
        if (session == null) return
        if (mActivity.terminalView.attachSession(session.terminalSession)) {
            notifyOfSessionChange()
        }
        checkAndScrollToSession(session)
        updateBackgroundColor()
    }

    private fun notifyOfSessionChange() {
        if (!mActivity.isVisible()) return
        if (!mActivity.properties.areTerminalSessionChangeToastsDisabled()) {
            val session = mActivity.currentQuroSession
            if (session != null) mActivity.showToast(toToastTitle(session.terminalSession), false)
        }
    }

    fun switchToSession(forward: Boolean) {
        val service = mActivity.termuxHostService ?: return
        val current = mActivity.currentQuroSession ?: return
        var index = service.getIndexOfSession(current.terminalSession)
        val size = service.termuxSessionCount
        if (index < 0) return
        if (forward) {
            if (++index >= size) index = 0
        } else {
            if (--index < 0) index = size - 1
        }
        val session = service.getSessionAtIndex(index) ?: return
        setCurrentSession(session)
    }

    fun switchToSession(index: Int) {
        val service = mActivity.termuxHostService ?: return
        val session = service.getSessionAtIndex(index) ?: return
        setCurrentSession(session)
    }

    @SuppressLint("InflateParams")
    fun renameSession(sessionToRename: QuroTerminalSession) {
        if (sessionToRename == null) return
        TextInputDialogUtils.textInput(
            mActivity,
            R.string.title_rename_session,
            sessionToRename.name,
            R.string.action_rename_session_confirm,
            { text ->
                renameSession(sessionToRename, text)
                termuxSessionListNotifyUpdated()
            },
            0,
            {},
            -1,
            null,
            null
        )
    }

    private fun renameSession(sessionToRename: QuroTerminalSession, text: String) {
        if (sessionToRename == null) return
        sessionToRename.name = text
    }

    fun addNewSession(isFailSafe: Boolean, sessionName: String?) {
        val service = mActivity.termuxHostService ?: return
        if (service.termuxSessionCount >= MAX_SESSIONS) {
            AlertDialog.Builder(mActivity)
                .setTitle(R.string.title_max_terminals_reached)
                .setMessage(R.string.msg_max_terminals_reached)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        } else {
            val currentSession = mActivity.currentQuroSession
            service.addSession(sessionName, isFailSafe) { newSession ->
                if (newSession != null) {
                    setCurrentSession(newSession)
                    mActivity.drawer.closeDrawers()
                }
            }
        }
    }

    fun setCurrentStoredSession() {
        val currentSession = mActivity.currentQuroSession
        mActivity.preferences.setCurrentSession(currentSession?.terminalSession?.mHandle)
    }

    /** 获取存储的会话或最后一个运行中的会话。 */
    fun getCurrentStoredSessionOrLast(): QuroTerminalSession? {
        val stored = getCurrentStoredSession() ?: run {
            return mActivity.termuxHostService?.getLastSession()
        }
        return stored
    }

    private fun getCurrentStoredSession(): QuroTerminalSession? {
        val sessionHandle = mActivity.preferences.getCurrentSession() ?: return null
        return mActivity.termuxHostService?.getSessionForHandle(sessionHandle)
    }

    fun removeFinishedSession(finishedSession: TerminalSession) {
        val service = mActivity.termuxHostService ?: return
        val index = service.removeSessionForTerminalSession(finishedSession)
        val size = service.termuxSessionCount
        if (size == 0) {
            mActivity.finishActivityIfNotFinishing()
        } else {
            var newIndex = index
            if (newIndex >= size) newIndex = size - 1
            val session = service.getSessionAtIndex(newIndex) ?: return
            setCurrentSession(session)
        }
    }

    fun termuxSessionListNotifyUpdated() {
        mActivity.termuxSessionListNotifyUpdated()
    }

    fun checkAndScrollToSession(session: QuroTerminalSession) {
        if (!mActivity.isVisible()) return
        val service = mActivity.termuxHostService ?: return
        val indexOfSession = service.getIndexOfSession(session.terminalSession)
        if (indexOfSession < 0) return
        val listView = mActivity.findViewById<ListView>(R.id.terminal_sessions_list) ?: return
        listView.setItemChecked(indexOfSession, true)
        listView.postDelayed({ listView.smoothScrollToPosition(indexOfSession) }, 1000)
    }

    fun toToastTitle(session: TerminalSession): String {
        val service = mActivity.termuxHostService ?: return ""
        val indexOfSession = service.getIndexOfSession(session)
        if (indexOfSession < 0) return ""
        val sb = StringBuilder("[" + (indexOfSession + 1) + "]")
        if (!TextUtils.isEmpty(session.mSessionName)) {
            sb.append(" ").append(session.mSessionName)
        }
        val title = session.getTitle()
        if (!TextUtils.isEmpty(title)) {
            sb.append(if (session.mSessionName == null) " " else "\n")
            sb.append(title)
        }
        return sb.toString()
    }

    /** 加载铃声资源。 */
    private fun checkForFontAndColors() {
        try {
            val colorsFile = File(mActivity.filesDir, ".termux/colors.properties")
            val fontFile = File(mActivity.filesDir, ".termux/font.ttf")

            val props = Properties()
            if (colorsFile.isFile) {
                val input: InputStream = FileInputStream(colorsFile)
                input.use { props.load(it) }
            }

            TerminalColors.COLOR_SCHEME.updateWith(props)
            val session = mActivity.currentQuroSession
            if (session != null && session.terminalSession.getEmulator() != null) {
                session.terminalSession.getEmulator().mColors.reset()
            }
            updateBackgroundColor()

            val newTypeface = if (fontFile.exists() && fontFile.length() > 0) {
                Typeface.createFromFile(fontFile)
            } else {
                Typeface.MONOSPACE
            }
            mActivity.terminalView.setTypeface(newTypeface)
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Error in checkForFontAndColors()", e)
        }
    }

    fun updateBackgroundColor() {
        if (!mActivity.isVisible()) return
        val session = mActivity.currentQuroSession
        val emulator = session?.terminalSession?.getEmulator() ?: return
        mActivity.window.decorView.setBackgroundColor(
            emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND]
        )
    }

    @Synchronized
    private fun loadBellSoundPool() {
        if (mBellSoundPool == null) {
            mBellSoundPool = SoundPool.Builder()
                .setMaxStreams(1)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .build()
                )
                .build()
            runCatching {
                mBellSoundId = mBellSoundPool?.load(mActivity, R.raw.bell, 1) ?: 0
            }.onFailure {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to load bell sound pool", it)
            }
        }
    }

    @Synchronized
    private fun releaseBellSoundPool() {
        mBellSoundPool?.release()
        mBellSoundPool = null
    }
}