package com.ai.assistance.quro.termux.app

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.media.AudioManager
import android.text.TextUtils
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.ListView
import androidx.drawerlayout.widget.DrawerLayout
import com.ai.assistance.quro.termux.R
import com.ai.assistance.quro.termux.util.DataUtils
import com.ai.assistance.quro.termux.util.KeyboardUtils
import com.ai.assistance.quro.termux.util.Logger
import com.ai.assistance.quro.termux.util.ShareUtils
import com.ai.assistance.quro.termux.util.TermuxConstants
import com.ai.assistance.quro.termux.util.TermuxUrlUtils
import com.ai.assistance.quro.vt.KeyHandler
import com.ai.assistance.quro.vt.SpecialButton
import com.ai.assistance.quro.vt.TerminalEmulator
import com.ai.assistance.quro.vt.TerminalSession
import com.ai.assistance.quro.vt.TerminalView
import com.ai.assistance.quro.vt.TerminalViewClient
import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashSet

/**
 * 终端视图客户端（移植自 Termux `TermuxTerminalViewClient`，去品牌化精简版）。
 *
 * 实现 [TerminalViewClient] 与宿主 Activity 的全部交互：
 * - 硬件快捷键（Ctrl+Alt+组合键：新建/切换/重命名会话、键盘、菜单、URL、粘贴、字体）
 * - 虚拟 Fn 键映射（方向键/翻页/功能键/特殊键）
 * - 额外按键特殊按钮（CTRL/ALT/SHIFT/FN）读取
 * - 软键盘管理（显示/隐藏/禁用/启动时隐藏）
 * - 光标闪烁
 * - 单击 URL 打开 / 长按分享 / 上下文菜单
 */
class TermuxTerminalViewClient(
    private val mActivity: TermuxHostActivity,
    private val mTermuxTerminalSessionActivityClient: TermuxTerminalSessionActivityClient
) : TerminalViewClient {

    private val LOG_TAG = "TermuxTerminalViewClient"

    /** 记录软键盘与硬件键的 Ctrl/Fn 状态。 */
    private var mVirtualControlKeyDown = false
    private var mVirtualFnKeyDown = false

    private var mShowSoftKeyboardRunnable: Runnable? = null
    private var mShowSoftKeyboardIgnoreOnce = false
    private var mShowSoftKeyboardWithDelayOnce = false

    private var mTerminalCursorBlinkerStateAlreadySet = false

    /** 会话快捷键列表（来自 termux.properties shortcut.* 键）。 */
    private var mSessionShortcuts: ArrayList<KeyboardShortcut>? = null

    fun getActivity(): TermuxHostActivity = mActivity

    /** 在 Activity.onCreate() 时调用。 */
    fun onCreate() {
        onReloadProperties()
        mActivity.terminalView.setTextSize(mActivity.preferences.getFontSize())
        mActivity.terminalView.setKeepScreenOn(mActivity.preferences.shouldKeepScreenOn())
    }

    /** 在 Activity.onStart() 时调用。 */
    fun onStart() {
        // 键盘按键日志（默认关闭）
        val isKeyLoggingEnabled = mActivity.preferences.isTerminalViewKeyLoggingEnabled()
        mActivity.terminalView.setIsTerminalViewKeyLoggingEnabled(isKeyLoggingEnabled)
    }

    /** 在 Activity.onResume() 时调用。 */
    fun onResume() {
        setSoftKeyboardState(true, mActivity.isActivityRecreated())
        mTerminalCursorBlinkerStateAlreadySet = false
        if (mActivity.terminalView.mEmulator != null) {
            setTerminalCursorBlinkerState(true)
            mTerminalCursorBlinkerStateAlreadySet = true
        }
    }

    /** 在 Activity.onStop() 时调用。 */
    fun onStop() {
        setTerminalCursorBlinkerState(false)
    }

    /** 在 Activity.reloadProperties() 时调用。 */
    fun onReloadProperties() {
        setSessionShortcuts()
    }

    /** 在 Activity.reloadActivityStyling() 时调用。 */
    fun onReloadActivityStyling() {
        setSoftKeyboardState(false, true)
        setTerminalCursorBlinkerState(true)
    }

    /** 当终端模拟器被设置时调用。 */
    override fun onEmulatorSet() {
        if (!mTerminalCursorBlinkerStateAlreadySet) {
            setTerminalCursorBlinkerState(true)
            mTerminalCursorBlinkerStateAlreadySet = true
        }
    }

    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            changeFontSize(scale > 1.0f)
            return 1.0f
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent) {
        val session = mActivity.currentSession ?: return
        val term: TerminalEmulator = session.getEmulator()

        if (mActivity.properties.shouldOpenTerminalTranscriptURLOnClick()) {
            val columnAndRow = mActivity.terminalView.getColumnAndRow(e, true)
            val wordAtTap = term.getScreen().getWordAtLocation(columnAndRow[0], columnAndRow[1])
            val urlSet: LinkedHashSet<CharSequence> = TermuxUrlUtils.extractUrls(wordAtTap)
            if (!urlSet.isEmpty()) {
                val url = urlSet.iterator().next().toString()
                ShareUtils.openUrl(mActivity, url)
                return
            }
        }

        if (!term.isMouseTrackingActive() && !e.isFromSource(InputDevice.SOURCE_MOUSE)) {
            if (!KeyboardUtils.areDisableSoftKeyboardFlagsSet(mActivity)) {
                KeyboardUtils.showSoftKeyboard(mActivity, mActivity.terminalView)
            }
        }
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean =
        mActivity.properties.isBackKeyTheEscapeKey()

    override fun shouldEnforceCharBasedInput(): Boolean =
        mActivity.properties.isEnforcingCharBasedInput()

    override fun shouldUseCtrlSpaceWorkaround(): Boolean =
        mActivity.properties.isUsingCtrlSpaceWorkaround()

    override fun isTerminalViewSelected(): Boolean =
        mActivity.terminalToolbarViewPager == null || mActivity.isTerminalViewSelected() || mActivity.terminalView.hasFocus()

    override fun copyModeChanged(copyMode: Boolean) {
        mActivity.drawer.setDrawerLockMode(
            if (copyMode) DrawerLayout.LOCK_MODE_LOCKED_CLOSED else DrawerLayout.LOCK_MODE_UNLOCKED
        )
    }

    @SuppressLint("RtlHardcoded")
    override fun onKeyDown(keyCode: Int, e: KeyEvent, currentSession: TerminalSession): Boolean {
        if (handleVirtualKeys(keyCode, e, true)) return true

        if (keyCode == KeyEvent.KEYCODE_ENTER && !currentSession.isRunning()) {
            mTermuxTerminalSessionActivityClient.removeFinishedSession(currentSession)
            return true
        } else if (!mActivity.properties.areHardwareKeyboardShortcutsDisabled() &&
            e.isCtrlPressed() && e.isAltPressed()
        ) {
            val unicodeChar = e.getUnicodeChar(0)
            when {
                keyCode == KeyEvent.KEYCODE_DPAD_DOWN || unicodeChar == 'n'.code -> mTermuxTerminalSessionActivityClient.switchToSession(true)
                keyCode == KeyEvent.KEYCODE_DPAD_UP || unicodeChar == 'p'.code -> mTermuxTerminalSessionActivityClient.switchToSession(false)
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> mActivity.drawer.openDrawer(Gravity.LEFT)
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> mActivity.drawer.closeDrawers()
                unicodeChar == 'k'.code -> onToggleSoftKeyboardRequest()
                unicodeChar == 'm'.code -> mActivity.terminalView.showContextMenu()
                unicodeChar == 'r'.code -> mTermuxTerminalSessionActivityClient.renameSession(mActivity.currentQuroSession ?: return true)
                unicodeChar == 'c'.code -> mTermuxTerminalSessionActivityClient.addNewSession(false, null)
                unicodeChar == 'u'.code -> showUrlSelection()
                unicodeChar == 'v'.code -> doPaste()
                unicodeChar == '+'.code || e.getUnicodeChar(KeyEvent.META_SHIFT_ON) == '+'.code -> changeFontSize(true)
                unicodeChar == '-'.code -> changeFontSize(false)
                unicodeChar in '1'.code..'9'.code -> mTermuxTerminalSessionActivityClient.switchToSession(unicodeChar - '1'.code)
            }
            return true
        }
        return false
    }

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean {
        // 模拟器未设置（如 rootfs 准备失败用户关闭了错误对话框）时返回退出
        if (keyCode == KeyEvent.KEYCODE_BACK && mActivity.terminalView.mEmulator == null) {
            mActivity.finishActivityIfNotFinishing()
            return true
        }
        return handleVirtualKeys(keyCode, e, false)
    }

    /** 处理音量键作为虚拟 Ctrl/Fn。 */
    private fun handleVirtualKeys(keyCode: Int, event: KeyEvent, down: Boolean): Boolean {
        val inputDevice = event.device
        if (mActivity.properties.areVirtualVolumeKeysDisabled()) {
            return false
        } else if (inputDevice != null && inputDevice.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
            return false
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            mVirtualControlKeyDown = down
            return true
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            mVirtualFnKeyDown = down
            return true
        }
        return false
    }

    override fun readControlKey(): Boolean =
        readExtraKeysSpecialButton(SpecialButton.CTRL) || mVirtualControlKeyDown

    override fun readAltKey(): Boolean =
        readExtraKeysSpecialButton(SpecialButton.ALT)

    override fun readShiftKey(): Boolean =
        readExtraKeysSpecialButton(SpecialButton.SHIFT)

    override fun readFnKey(): Boolean =
        readExtraKeysSpecialButton(SpecialButton.FN)

    private fun readExtraKeysSpecialButton(specialButton: SpecialButton): Boolean {
        val extraKeysView = mActivity.extraKeysView ?: return false
        val state = extraKeysView.readSpecialButton(specialButton, true) ?: return false
        return state
    }

    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        if (mVirtualFnKeyDown) {
            var resultingKeyCode = -1
            var resultingCodePoint = -1
            var altDown = false
            val lowerCase = Character.toLowerCase(codePoint)
            when (lowerCase) {
                // 方向键
                'w'.code -> resultingKeyCode = KeyEvent.KEYCODE_DPAD_UP
                'a'.code -> resultingKeyCode = KeyEvent.KEYCODE_DPAD_LEFT
                's'.code -> resultingKeyCode = KeyEvent.KEYCODE_DPAD_DOWN
                'd'.code -> resultingKeyCode = KeyEvent.KEYCODE_DPAD_RIGHT
                // 翻页
                'p'.code -> resultingKeyCode = KeyEvent.KEYCODE_PAGE_UP
                'n'.code -> resultingKeyCode = KeyEvent.KEYCODE_PAGE_DOWN
                // 特殊键
                't'.code -> resultingKeyCode = KeyEvent.KEYCODE_TAB
                'i'.code -> resultingKeyCode = KeyEvent.KEYCODE_INSERT
                'h'.code -> resultingCodePoint = '~'.code
                // 特殊字符
                'u'.code -> resultingCodePoint = '_'.code
                'l'.code -> resultingCodePoint = '|'.code
                // 功能键 F1-F10
                in '1'.code..'9'.code -> resultingKeyCode = (codePoint - '1'.code) + KeyEvent.KEYCODE_F1
                '0'.code -> resultingKeyCode = KeyEvent.KEYCODE_F10
                // 其他
                'e'.code -> resultingCodePoint = 27 // Escape
                '.'.code -> resultingCodePoint = 28 // ^\
                'b'.code, 'f'.code, 'x'.code -> {
                    resultingCodePoint = lowerCase
                    altDown = true
                }
                // 音量控制
                'v'.code -> {
                    resultingCodePoint = -1
                    val audio = mActivity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    audio.adjustSuggestedStreamVolume(
                        AudioManager.ADJUST_SAME,
                        AudioManager.USE_DEFAULT_STREAM_TYPE,
                        AudioManager.FLAG_SHOW_UI
                    )
                }
                // 写作模式：切换工具栏
                'q'.code, 'k'.code -> {
                    mActivity.toggleTerminalToolbar()
                    mVirtualFnKeyDown = false
                }
            }

            if (resultingKeyCode != -1) {
                val term = session.getEmulator()
                session.write(KeyHandler.getCode(resultingKeyCode, 0, term.isCursorKeysApplicationMode(), term.isKeypadApplicationMode()))
            } else if (resultingCodePoint != -1) {
                session.writeCodePoint(altDown, resultingCodePoint)
            }
            return true
        } else if (ctrlDown) {
            if (codePoint == 106 && !session.isRunning()) { // Ctrl+j
                mTermuxTerminalSessionActivityClient.removeFinishedSession(session)
                return true
            }

            val shortcuts = mSessionShortcuts
            if (shortcuts != null && !shortcuts.isEmpty()) {
                val codePointLowerCase = Character.toLowerCase(codePoint)
                for (i in shortcuts.indices.reversed()) {
                    val shortcut = shortcuts[i]
                    if (codePointLowerCase == shortcut.codePoint) {
                        when (shortcut.shortcutAction) {
                            TermuxConstants.ACTION_SHORTCUT_CREATE_SESSION -> {
                                mTermuxTerminalSessionActivityClient.addNewSession(false, null)
                                return true
                            }
                            TermuxConstants.ACTION_SHORTCUT_NEXT_SESSION -> {
                                mTermuxTerminalSessionActivityClient.switchToSession(true)
                                return true
                            }
                            TermuxConstants.ACTION_SHORTCUT_PREVIOUS_SESSION -> {
                                mTermuxTerminalSessionActivityClient.switchToSession(false)
                                return true
                            }
                            TermuxConstants.ACTION_SHORTCUT_RENAME_SESSION -> {
                                mTermuxTerminalSessionActivityClient.renameSession(mActivity.currentQuroSession ?: return true)
                                return true
                            }
                        }
                    }
                }
            }
        }
        return false
    }

    /** 设置会话快捷键（从 termux.properties shortcut.* 读取码点）。 */
    private fun setSessionShortcuts() {
        val sessionShortcuts = ArrayList<KeyboardShortcut>()
        for ((propertyKey, action) in TermuxConstants.TERMUX_PROPERTY_CONSTANTS.MAP_SESSION_SHORTCUTS) {
            val codePoint = mActivity.properties.getInternalPropertyValue(propertyKey, null)
            if (codePoint != null) {
                sessionShortcuts.add(KeyboardShortcut(codePoint, action))
            }
        }
        mSessionShortcuts = sessionShortcuts
    }

    fun changeFontSize(increase: Boolean) {
        mActivity.preferences.changeFontSize(increase)
        mActivity.terminalView.setTextSize(mActivity.preferences.getFontSize())
    }

    /** 切换软键盘（extra keys 的 KEYBOARD 键或 Ctrl+Alt+K）。 */
    fun onToggleSoftKeyboardRequest() {
        if (mActivity.properties.shouldEnableDisableSoftKeyboardOnToggle()) {
            if (!KeyboardUtils.areDisableSoftKeyboardFlagsSet(mActivity)) {
                mActivity.preferences.setSoftKeyboardEnabled(false)
                KeyboardUtils.disableSoftKeyboard(mActivity, mActivity.terminalView)
            } else {
                mActivity.preferences.setSoftKeyboardEnabled(true)
                KeyboardUtils.clearDisableSoftKeyboardFlags(mActivity)
                if (mShowSoftKeyboardWithDelayOnce) {
                    mShowSoftKeyboardWithDelayOnce = false
                    mActivity.terminalView.postDelayed(getShowSoftKeyboardRunnable(), 500)
                    mActivity.terminalView.requestFocus()
                } else {
                    KeyboardUtils.showSoftKeyboard(mActivity, mActivity.terminalView)
                }
            }
        } else {
            if (!mActivity.preferences.isSoftKeyboardEnabled()) {
                KeyboardUtils.disableSoftKeyboard(mActivity, mActivity.terminalView)
            } else {
                KeyboardUtils.clearDisableSoftKeyboardFlags(mActivity)
                KeyboardUtils.toggleSoftKeyboard(mActivity)
            }
        }
    }

    /** 设置软键盘状态（启动/属性重载时）。 */
    fun setSoftKeyboardState(isStartup: Boolean, isReloadTermuxProperties: Boolean) {
        var noShowKeyboard = false

        if (KeyboardUtils.shouldSoftKeyboardBeDisabled(
                mActivity,
                mActivity.preferences.isSoftKeyboardEnabled(),
                mActivity.preferences.isSoftKeyboardEnabledOnlyIfNoHardware()
            )
        ) {
            KeyboardUtils.disableSoftKeyboard(mActivity, mActivity.terminalView)
            mActivity.terminalView.requestFocus()
            noShowKeyboard = true
            if (isStartup && mActivity.isOnResumeAfterOnCreate()) {
                mShowSoftKeyboardWithDelayOnce = true
            }
        } else {
            KeyboardUtils.setSoftInputModeAdjustResize(mActivity)
            KeyboardUtils.clearDisableSoftKeyboardFlags(mActivity)

            if (isStartup && mActivity.properties.shouldSoftKeyboardBeHiddenOnStartup()) {
                KeyboardUtils.setSoftKeyboardAlwaysHiddenFlags(mActivity)
                KeyboardUtils.hideSoftKeyboard(mActivity, mActivity.terminalView)
                mActivity.terminalView.requestFocus()
                noShowKeyboard = true
                mShowSoftKeyboardIgnoreOnce = true
            }
        }

        mActivity.terminalView.setOnFocusChangeListener { _, hasFocus ->
            var textInputViewHasFocus = false
            val textInputView = mActivity.findViewById<EditText>(R.id.terminal_toolbar_text_input)
            if (textInputView != null) textInputViewHasFocus = textInputView.hasFocus()

            if (mShowSoftKeyboardIgnoreOnce) {
                mShowSoftKeyboardIgnoreOnce = false
                return@setOnFocusChangeListener
            }
            KeyboardUtils.setSoftKeyboardVisibility(
                getShowSoftKeyboardRunnable(),
                mActivity,
                mActivity.terminalView,
                hasFocus || textInputViewHasFocus
            )
        }

        if (!isReloadTermuxProperties && !noShowKeyboard) {
            mActivity.terminalView.requestFocus()
            mActivity.terminalView.postDelayed(getShowSoftKeyboardRunnable(), 300)
        }
    }

    private fun getShowSoftKeyboardRunnable(): Runnable {
        if (mShowSoftKeyboardRunnable == null) {
            mShowSoftKeyboardRunnable = Runnable {
                KeyboardUtils.showSoftKeyboard(mActivity, mActivity.terminalView)
            }
        }
        return mShowSoftKeyboardRunnable!!
    }

    /** 设置终端光标闪烁。 */
    fun setTerminalCursorBlinkerState(start: Boolean) {
        if (start) {
            if (mActivity.terminalView.setTerminalCursorBlinkerRate(mActivity.properties.getTerminalCursorBlinkRate())) {
                mActivity.terminalView.setTerminalCursorBlinkerState(true, true)
            }
        } else {
            mActivity.terminalView.setTerminalCursorBlinkerState(false, true)
        }
    }

    /** 分享会话转录文本。 */
    fun shareSessionTranscript() {
        val session = mActivity.currentSession ?: return
        val transcriptText = getTerminalSessionTranscriptText(session, false, true) ?: return
        val truncated = DataUtils.getTruncatedCommandOutput(
            transcriptText,
            DataUtils.TRANSACTION_SIZE_LIMIT_IN_BYTES,
            false,
            true,
            false
        ).trim()
        ShareUtils.shareText(
            mActivity,
            mActivity.getString(R.string.title_share_transcript),
            truncated,
            mActivity.getString(R.string.title_share_transcript_with)
        )
    }

    /** 分享选中的文本。 */
    fun shareSelectedText() {
        val selectedText = mActivity.terminalView.getStoredSelectedText() ?: return
        if (TextUtils.isEmpty(selectedText)) return
        ShareUtils.shareText(
            mActivity,
            mActivity.getString(R.string.title_share_selected_text),
            selectedText,
            mActivity.getString(R.string.title_share_selected_text_with)
        )
    }

    /** 显示 URL 选择对话框。 */
    fun showUrlSelection() {
        val session = mActivity.currentSession ?: return
        val text = getTerminalSessionTranscriptText(session, true, true) ?: return

        val urlSet = TermuxUrlUtils.extractUrls(text)
        if (urlSet.isEmpty()) {
            AlertDialog.Builder(mActivity).setMessage(R.string.title_select_url_none_found).show()
            return
        }

        val urls = urlSet.toTypedArray()
        // 最新在前
        val urlList = urls.toMutableList()
        Collections.reverse(urlList)
        val urlArray = urlList.toTypedArray()

        val dialog = AlertDialog.Builder(mActivity)
            .setItems(urlArray) { _, which ->
                val url = urlArray[which].toString()
                ShareUtils.copyTextToClipboard(mActivity, url, mActivity.getString(R.string.msg_select_url_copied_to_clipboard))
            }
            .setTitle(R.string.title_select_url_dialog)
            .create()

        dialog.setOnShowListener {
            val lv = dialog.listView
            lv.setOnItemLongClickListener { _, _, position, _ ->
                dialog.dismiss()
                val url = urlArray[position].toString()
                ShareUtils.openUrl(mActivity, url)
                true
            }
        }
        dialog.show()
    }

    fun doPaste() {
        val session = mActivity.currentSession ?: return
        if (!session.isRunning()) return
        val text = ShareUtils.getTextStringFromClipboardIfSet(mActivity, true) ?: return
        session.getEmulator().paste(text)
    }

    private fun getTerminalSessionTranscriptText(session: TerminalSession, readable: Boolean, replaceColors: Boolean): String? {
        val emulator = session.getEmulator() ?: return null
        return emulator.getScreen().getTranscriptText()
    }

    // ==================== TerminalViewClient 日志 ====================

    override fun logError(tag: String, message: String) { Logger.logError(tag, message) }
    override fun logWarn(tag: String, message: String) { Logger.logWarn(tag, message) }
    override fun logInfo(tag: String, message: String) { Logger.logInfo(tag, message) }
    override fun logDebug(tag: String, message: String) { Logger.logDebug(tag, message) }
    override fun logVerbose(tag: String, message: String) { Logger.logVerbose(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Logger.logStackTraceWithMessage(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception) { Logger.logStackTrace(tag, e) }
}