package com.ai.assistance.quro.terminal.vt

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.ai.assistance.quro.terminal.runtime.TerminalEnvironment
import com.ai.assistance.quro.vt.ExtraKeyButton
import com.ai.assistance.quro.vt.ExtraKeysConstants
import com.ai.assistance.quro.vt.ExtraKeysInfo
import com.ai.assistance.quro.vt.ExtraKeysView
import com.ai.assistance.quro.vt.TerminalExtraKeys
import com.ai.assistance.quro.vt.TerminalSession
import com.ai.assistance.quro.vt.TerminalSessionClient
import com.ai.assistance.quro.vt.TerminalView
import com.ai.assistance.quro.vt.TerminalViewClient
import com.ai.assistance.quro.vt.QuroTerminalProperties

/**
 * vtlib 增强终端桥接层。
 *
 * 组装 Termux 完整能力栈：
 * - [TerminalSession]（vtlib）：JNI PTY + 完整 VT 模拟器（双缓冲/备用屏幕/滚动/sixel/括号粘贴/OSC52）
 * - [TerminalView]（vtlib）：渲染 + 手势 + 文本选择 + 鼠标协议
 * - [ExtraKeysView]（vtlib）：额外按键栏
 * - [QuroTerminalProperties]（vtlib）：termux.properties 属性系统
 *
 * proot 启动参数复用 [TerminalEnvironment.buildProotLaunch]。
 */
class VtTerminalBridge(
    private val context: Context,
    private val environment: TerminalEnvironment
) : TerminalViewClient, TerminalSessionClient {

    private val tag = "VtTerminalBridge"

    /** vtlib 终端会话（proot shell）。 */
    var terminalSession: TerminalSession? = null
        private set

    /** vtlib 终端视图。 */
    var terminalView: TerminalView? = null
        private set

    /** 额外按键栏。 */
    var extraKeysView: ExtraKeysView? = null
        private set

    /** 属性系统。 */
    val properties: QuroTerminalProperties by lazy {
        val file = java.io.File(context.filesDir, ".termux/termux.properties")
        QuroTerminalProperties(context, file)
    }

    /** 是否已启动。 */
    val isRunning: Boolean get() = terminalSession != null

    /** 会话结束回调。 */
    var onSessionFinished: ((Int) -> Unit)? = null

    /** 标题变化回调。 */
    var onTitleChanged: ((String?) -> Unit)? = null

    /**
     * 启动 proot 终端会话。
     *
     * Termux 的 [TerminalSession] 构造器只保存参数，真正创建子进程发生在
     * [TerminalSession.initializeEmulator]（由视图有尺寸后的 updateSize 触发）。
     * 本桥接层没有 Activity 生命周期，若不主动初始化，会话会永远停在「未启动」，
     * 表现为黑屏/无进程。因此这里用默认 80×24 立即初始化，后续视图布局完成后再
     * 由 TerminalView 的 onSizeChanged → updateSize 重设真实尺寸。
     *
     * @param transcriptRows 历史回滚行数，默认 5000（Termux 默认）。
     */
    fun start(transcriptRows: Int = 5000): Boolean {
        if (terminalSession != null) return true
        return runCatching {
            val spec = environment.buildProotLaunch()
            val session = TerminalSession(
                spec.shellPath,
                spec.hostCwd.absolutePath,
                spec.args,
                spec.env.map { "${it.key}=${it.value}" }.toTypedArray(),
                transcriptRows,
                this
            )
            // 立即用默认尺寸初始化 PTY 子进程（否则进程永远不会启动）
            session.initializeEmulator(80, 24, 10, 18)
            terminalSession = session
            true
        }.onFailure {
            Log.e(tag, "启动 vtlib 终端会话失败", it)
            terminalSession = null
        }.getOrDefault(false)
    }

    /** 绑定 [TerminalView] 到当前会话。 */
    fun attach(view: TerminalView): Boolean {
        val session = terminalSession ?: return false
        view.setTerminalViewClient(this)
        // 🔴 必须先在视图上初始化渲染器（mRenderer），否则 attachSession → updateSize()
        // 访问 mRenderer.mFontWidth 时 NPE，直接在主线程崩溃闪退（且无崩溃记录）。
        // 上游 Termux 由 Activity 调用 setTextSize() 完成初始化，桥接层必须补这一步。
        val textSizePx = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP,
            14f,
            context.resources.displayMetrics
        )
        view.setTextSize(textSizePx.toInt())
        // 🔴 终端视图必须可聚焦/可触摸聚焦，否则 requestFocus() 无效、软键盘弹不出来
        //（onCheckIsTextEditor 返回 true 但 View 默认 isFocusable=false）。
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        val attached = view.attachSession(session)
        if (attached) {
            terminalView = view
        }
        return attached
    }

    /** 创建并绑定额外按键栏。 */
    fun createExtraKeysView(container: android.view.ViewGroup): ExtraKeysView? {
        val view = ExtraKeysView(context, null)
        view.setExtraKeysViewClient(TerminalExtraKeys(terminalView ?: error("TerminalView 未绑定")))
        extraKeysView = view
        container.addView(
            view,
            android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // 🔴 必须调用 reload() 才会真正生成按键按钮！旧实现只 setExtraKeysViewClient
        // 从不 reload，导致按键栏永远空白（「壳」的细节缺失）。
        // 从 termux.properties 的 extra-keys 读取布局 JSON（默认两行：ESC//TAB/CTRL/方向键等）。
        return runCatching {
            val propsJson = properties.getProperty(
                QuroTerminalProperties.KEY_EXTRA_KEYS
            ) ?: QuroTerminalProperties.DEFAULT_VALUE_EXTRA_KEYS
            val style = properties.getStringProperty(
                QuroTerminalProperties.KEY_EXTRA_KEYS_STYLE,
                QuroTerminalProperties.DEFAULT_VALUE_EXTRA_KEYS_STYLE,
                setOf("default", "arrows-only", "arrows-all", "all", "none")
            )
            val info = ExtraKeysInfo(
                propsJson,
                style,
                ExtraKeysConstants.CONTROL_CHARS_ALIASES
            )
            // 按键高度：约 48dp（Material 按钮标准高度）
            val heightPx = 48f * context.resources.displayMetrics.density
            view.reload(info, heightPx)
            view
        }.onFailure {
            Log.e(tag, "加载额外按键栏失败", it)
            extraKeysView = null
            container.removeView(view)
        }.getOrNull()
    }

    /** 输入一行（换行结尾自动补）。 */
    fun write(input: String) {
        terminalSession?.write(input.toByteArray(Charsets.UTF_8), 0, input.toByteArray(Charsets.UTF_8).size)
    }

    /** 发送中断信号（Ctrl+C）。 */
    fun sendInterrupt() {
        // 通过额外按键语义：直接写 Ctrl+C (0x03)
        write("\u0003")
    }

    /** 调整尺寸（由 TerminalView 自动调用，这里透传）。 */
    fun updateSize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        terminalSession?.updateSize(columns, rows, cellWidthPixels, cellHeightPixels)
    }

    /** 停止并销毁会话。 */
    fun stop() {
        terminalSession?.finishIfRunning()
        terminalSession = null
        terminalView = null
        extraKeysView = null
    }

    // ── TerminalViewClient ──

    override fun onScale(scale: Float): Float = scale

    override fun onSingleTapUp(e: MotionEvent) {
        // 默认行为：请求焦点
        terminalView?.requestFocus()
        // 🔴 点击终端后弹出软键盘（Termux 宿主在 onSingleTapUp 里显示 IME）
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        terminalView?.let { imm?.showSoftInput(it, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT) }
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean =
        properties.getStringProperty(
            QuroTerminalProperties.KEY_BACK_KEY_BEHAVIOUR,
            QuroTerminalProperties.DEFAULT_VALUE_BACK_KEY_BEHAVIOUR,
            QuroTerminalProperties.BACK_KEY_BEHAVIOUR_VALUES
        ) == QuroTerminalProperties.IVALUE_BACK_KEY_BEHAVIOUR_ESCAPE

    override fun shouldEnforceCharBasedInput(): Boolean =
        properties.isPropertyTrue(QuroTerminalProperties.KEY_ENFORCE_CHAR_BASED_INPUT)

    override fun shouldUseCtrlSpaceWorkaround(): Boolean =
        properties.isPropertyTrue(QuroTerminalProperties.KEY_USE_CTRL_SPACE_WORKAROUND)

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) {}

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean = false

    override fun readAltKey(): Boolean = false

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false

    override fun onEmulatorSet() {}

    override fun logError(tag: String, message: String) { Log.e(tag, message) }
    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "stacktrace", e) }

    // ── TerminalSessionClient ──

    override fun onTextChanged(changedSession: TerminalSession) {
        // 🔴 必须通知 TerminalView 重绘！Termux 宿主在 onTextChanged 里调用
        // mTerminalView.onScreenUpdated() 触发 invalidate。如果不调，emulator 的
        // 内存屏幕缓冲区一直在更新（proot 输出已到达），但视图从不重绘，
        // 表现为「黑屏 / 有数据但看不见」。
        terminalView?.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        onTitleChanged?.invoke(changedSession.title)
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        val code = finishedSession.exitStatus
        Log.d(tag, "终端会话结束 exit=$code")
        onSessionFinished?.invoke(code)
        terminalSession = null
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("terminal", text))
        }
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = cm.primaryClip ?: return
            if (clip.itemCount > 0) {
                val text = clip.getItemAt(0).coerceToText(context).toString()
                session?.write(text.toByteArray(Charsets.UTF_8), 0, text.toByteArray(Charsets.UTF_8).size)
            }
        }
    }

    override fun onBell(session: TerminalSession) {}

    override fun onColorsChanged(session: TerminalSession) {}

    override fun onTerminalCursorStateChange(state: Boolean) {}

    /** 光标样式：由 termux.properties 的 terminal-cursor-style 控制；返回 null 使用默认 block。 */
    override fun getTerminalCursorStyle(): Int? {
        val style = properties.getStringProperty(
            QuroTerminalProperties.KEY_TERMINAL_CURSOR_STYLE,
            QuroTerminalProperties.DEFAULT_VALUE_TERMINAL_CURSOR_STYLE,
            QuroTerminalProperties.TERMINAL_CURSOR_STYLE_VALUES
        )
        return when (style) {
            QuroTerminalProperties.VALUE_TERMINAL_CURSOR_STYLE_UNDERLINE ->
                com.ai.assistance.quro.vt.TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE
            QuroTerminalProperties.VALUE_TERMINAL_CURSOR_STYLE_BAR ->
                com.ai.assistance.quro.vt.TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR
            else ->
                com.ai.assistance.quro.vt.TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK
        }
    }

    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {
        Log.d(tag, "shell pid=$pid")
    }
}