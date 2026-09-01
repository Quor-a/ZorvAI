package com.ai.assistance.quro.core.termux

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.ai.assistance.quro.core.termux.terminal.TerminalSession
import com.ai.assistance.quro.core.termux.terminal.TerminalSessionClient

/**
 * Termux [TerminalSessionClient] 的 Zorv AI 实现：把模拟器的文本变化、
 * 标题、剪贴板、日志等回调桥接到 Android 侧。
 *
 * [textChanged] 由宿主界面（Compose 中的 [com.ai.assistance.quro.core.termux.view.TerminalView]）
 * 注入，用来触发重绘（调用 `TerminalView.onScreenUpdated()`）。
 */
class QuroTermuxSessionClient(
    private val context: Context,
    var textChanged: () -> Unit,
) : TerminalSessionClient {

    override fun onTextChanged(changedSession: TerminalSession) {
        textChanged()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {}

    override fun onSessionFinished(finishedSession: TerminalSession) {}

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("ZorvAI 沙盒终端", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        if (session == null) return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
        session.emulator?.paste(text)
    }

    override fun onBell(session: TerminalSession) {}

    override fun onColorsChanged(session: TerminalSession) {}

    override fun onTerminalCursorStateChange(state: Boolean) {}

    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}

    override fun getTerminalCursorStyle(): Int? = null

    // Java interface 返回 void；Log.* 返回 Int。显式丢弃返回值避免类型不匹配。
    override fun logError(tag: String, message: String) { Log.e(tag, message) }
    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "", e) }
}
