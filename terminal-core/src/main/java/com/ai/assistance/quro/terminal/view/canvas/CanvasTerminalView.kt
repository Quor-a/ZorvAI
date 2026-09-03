package com.ai.assistance.quro.terminal.view.canvas

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.content.ClipData
import android.content.ClipboardManager
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.view.KeyEvent
import com.ai.assistance.quro.terminal.domain.ansi.AnsiTerminalEmulator
import com.ai.assistance.quro.terminal.domain.ansi.TerminalChar
import com.ai.assistance.quro.terminal.utils.CommandSanitizer
import kotlin.math.max
import kotlin.math.min
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.abs

/**
 * 基于Canvas的高性能终端视图
 * 使用SurfaceView + 独立渲染线程实现
 */
class CanvasTerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {
    
    // 渲染配置
    private val config = RenderConfig()
    
    // Paint对象（复用以提高性能）
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.MONOSPACE
        textSize = config.fontSize
    }
    
    private val bgPaint = Paint().apply {
        style = Paint.Style.FILL
    }
    
    private val cursorPaint = Paint().apply {
        color = 0xFF2DD4BF.toInt() // 青绿光标
        alpha = 180
        style = Paint.Style.FILL
    }
    
    private val selectionPaint = Paint().apply {
        color = Color.argb(100, 100, 149, 237) // 半透明蓝色
        style = Paint.Style.FILL
    }
    
    // 文本测量工具
    private val textMetrics = TextMetrics(textPaint)
    
    // 终端模拟器
    private var emulator: AnsiTerminalEmulator? = null
    private var emulatorChangeListener: (() -> Unit)? = null
    
    // PTY 引用（用于窗口大小同步）
    private var pty: com.ai.assistance.quro.terminal.Pty? = null
    
    // 渲染线程
    private var renderThread: RenderThread? = null
    private val renderLock = ReentrantLock()
    private val renderCondition = renderLock.newCondition()
    @Volatile
    private var isDirty = true // 是否需要重绘

    // 光标闪烁状态（由渲染线程按 cursorBlinkRate 翻转）
    @Volatile
    private var cursorBlinkVisible = true
    
    // 手势处理
    private lateinit var gestureHandler: GestureHandler
    private val selectionManager = TextSelectionManager()
    
    // 缩放因子
    private var scaleFactor = 1f
        set(value) {
            field = value.coerceIn(0.5f, 3f)
            updateFontSize()
        }
    
    // 滚动偏移
    private var scrollOffsetY = 0f
    
    // 输入回调
    private var inputCallback: ((String) -> Unit)? = null
    
    // 缓存终端尺寸，避免重复调用
    private var cachedRows = 0
    private var cachedCols = 0
    
    // 文本选择ActionMode
    private var actionMode: ActionMode? = null
    
    // 全屏模式标记
    private var isFullscreenMode = true
    
    init {
        holder.addCallback(this)
        setWillNotDraw(false)
        
        // 使视图可以获得焦点以接收输入法输入
        isFocusable = true
        isFocusableInTouchMode = true
        
        // 初始化文本指标
        textMetrics.updateFontSize(config.fontSize * scaleFactor)
        
        // 初始化手势处理器
        initGestureHandler()
    }
    
    private fun initGestureHandler() {
        gestureHandler = GestureHandler(
            context = context,
            onScale = { scale ->
                scaleFactor *= scale
                requestRender()
            },
            onScroll = { _, distanceY ->
                if (!selectionManager.hasSelection()) {
                    scrollOffsetY += distanceY
                    scrollOffsetY = scrollOffsetY.coerceAtLeast(0f)
                    requestRender()
                }
            },
            onDoubleTap = { x, y ->
                // 双击选择单词（可选实现）
                // 也可以用来切换输入法（仅全屏模式）
                if (isFullscreenMode) {
                    if (!hasFocus()) {
                        requestFocus()
                    }
                    showSoftKeyboard()
                }
            },
            onLongPress = { x, y ->
                startTextSelection(x, y)
            }
        )
    }
    
    /**
     * 设置终端模拟器
     */
    fun setEmulator(emulator: AnsiTerminalEmulator) {
        // 移除旧的监听器
        this.emulator?.let { oldEmulator ->
            emulatorChangeListener?.let { listener ->
                oldEmulator.removeChangeListener(listener)
            }
        }
        
        this.emulator = emulator
        
        // 添加新的监听器
        emulatorChangeListener = {
            isDirty = true
            requestRender()
        }
        emulator.addChangeListener(emulatorChangeListener!!)
        
        // 如果 Surface 已经创建，立即同步终端大小
        if (width > 0 && height > 0) {
            updateTerminalSize(width, height)
        }
        
        requestRender()
    }
    
    /**
     * 设置 PTY（用于窗口大小同步）
     */
    fun setPty(pty: com.ai.assistance.quro.terminal.Pty?) {
        this.pty = pty
        
        // 如果 Surface 已经创建且有 emulator，立即同步终端大小
        if (width > 0 && height > 0 && emulator != null) {
            updateTerminalSize(width, height)
        }
    }
    
    /**
     * 设置全屏模式
     */
    fun setFullscreenMode(isFullscreen: Boolean) {
        isFullscreenMode = isFullscreen
        // 非全屏模式下禁用焦点和输入法
        isFocusable = isFullscreen
        isFocusableInTouchMode = isFullscreen
    }
    
    /**
     * 设置输入回调
     */
    fun setInputCallback(callback: (String) -> Unit) {
        this.inputCallback = callback
    }
    
    /**
     * 设置缩放回调
     */
    fun setScaleCallback(callback: (Float) -> Unit) {
        // 当缩放因子变化时调用
        gestureHandler = GestureHandler(
            context = context,
            onScale = { scale ->
                scaleFactor *= scale
                callback(scaleFactor)
                // 更新字体指标和终端大小
                updateTerminalSize(holder.surfaceFrame.width(), holder.surfaceFrame.height())
                requestRender()
            },
            onScroll = { _, distanceY ->
                if (!selectionManager.hasSelection()) {
                    scrollOffsetY += distanceY
                    scrollOffsetY = scrollOffsetY.coerceAtLeast(0f)
                    requestRender()
                }
            },
            onDoubleTap = { x, y ->
                // 双击选择单词（可选实现）
            },
            onLongPress = { x, y ->
                startTextSelection(x, y)
            }
        )
    }
    
    /**
     * 设置性能监控回调
     */
    fun setPerformanceCallback(callback: (fps: Float, frameTime: Long) -> Unit) {
        // 性能监控逻辑可以在RenderThread中实现
        // 这里暂时留空，可以后续扩展
    }
    
    /**
     * 更新字体大小
     */
    private fun updateFontSize() {
        textMetrics.updateFontSize(config.fontSize * scaleFactor)
        requestRender()
    }
    
    /**
     * 请求渲染
     */
    private fun requestRender() {
        isDirty = true
    }
    
    // === SurfaceHolder.Callback 实现 ===
    
    override fun surfaceCreated(holder: SurfaceHolder) {
        startRenderThread()
    }
    
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // 更新终端窗口大小
        updateTerminalSize(width, height)
        
        requestRender()
    }
    
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopRenderThread()
    }
    
    // === 渲染线程 ===
    
    private fun startRenderThread() {
        stopRenderThread()
        renderThread = RenderThread(holder).apply {
            start()
        }
    }
    
    private fun stopRenderThread() {
        renderThread?.let { thread ->
            thread.stopRendering()
            thread.interrupt()
            try {
                thread.join(1000)
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
        }
        renderThread = null
    }
    
    private inner class RenderThread(private val surfaceHolder: SurfaceHolder) : Thread("TerminalRenderThread") {
        @Volatile
        private var running = false
        
        override fun start() {
            running = true
            super.start()
        }
        
        fun stopRendering() {
            running = false
        }
        
        override fun run() {
            val targetFrameTime = 1000L / config.targetFps

            while (running) {
                val currentTime = System.currentTimeMillis()

                // 光标闪烁：按 cursorBlinkRate 周期翻转可见性，翻转时触发重绘
                val blinkOn = (currentTime / config.cursorBlinkRate) % 2 == 0L
                if (blinkOn != cursorBlinkVisible) {
                    cursorBlinkVisible = blinkOn
                    isDirty = true
                }

                // 没有内容变化且光标未翻转时低功耗等待，避免无谓重绘
                if (!isDirty) {
                    try {
                        sleep(targetFrameTime)
                    } catch (e: InterruptedException) {
                        break
                    }
                    continue
                }

                var canvas: Canvas? = null
                try {
                    canvas = surfaceHolder.lockCanvas()
                    canvas?.let {
                        synchronized(surfaceHolder) {
                            drawTerminal(it)
                            isDirty = false
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    canvas?.let {
                        try {
                            surfaceHolder.unlockCanvasAndPost(it)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }

                // 控制帧率
                val frameTime = System.currentTimeMillis() - currentTime
                val sleepTime = targetFrameTime - frameTime
                if (sleepTime > 0) {
                    try {
                        sleep(sleepTime)
                    } catch (e: InterruptedException) {
                        break
                    }
                }
            }
        }
    }
    
    // === 核心渲染方法 ===
    
    private fun drawTerminal(canvas: Canvas) {
        val buffer = emulator?.getScreenContent() ?: return
        
        // 清空背景
        canvas.drawColor(config.backgroundColor)
        
        val charWidth = textMetrics.charWidth
        val charHeight = textMetrics.charHeight
        val baseline = textMetrics.charBaseline
        
        // 计算可见区域
        val visibleRows = (canvas.height / charHeight).toInt() + 1
        val startRow = (scrollOffsetY / charHeight).toInt()
        val endRow = min(startRow + visibleRows, buffer.size)
        
        // 绘制每一行
        for (row in startRow until endRow) {
            if (row >= buffer.size) break
            
            val line = buffer[row]
            val y = (row - startRow) * charHeight - (scrollOffsetY % charHeight)
            
            // 绘制该行的所有字符
            drawLine(canvas, line, row, 0f, y, charWidth, charHeight, baseline)
        }
        
        // 绘制选择区域
        if (selectionManager.hasSelection()) {
            drawSelection(canvas, charWidth, charHeight)
        }
        
        // 绘制光标（闪烁可见时才绘制）
        if (cursorBlinkVisible && emulator?.isCursorVisible() == true) {
            drawCursor(canvas, charWidth, charHeight)
        }
    }
    
    private fun drawLine(
        canvas: Canvas,
        line: Array<TerminalChar>,
        row: Int,
        startX: Float,
        y: Float,
        charWidth: Float,
        charHeight: Float,
        baseline: Float
    ) {
        var x = startX
        var currentBgColor: Int? = null
        var bgStartX = x

        // 批量绘制字符：把连续且样式相同的字符合并为一次 drawText，
        // 避免逐字符 applyStyle（重建 Typeface）+ drawText 的开销。
        // 宽字符（CJK/emoji，占 2 格）必须断开 run 单独绘制：
        // run 批量绘制按「字符数 × charWidth」定位，若混入宽字符会整体错位。
        var runStart = 0
        val runText = StringBuilder()

        for (col in line.indices) {
            val termChar = line[col]

            // 批量绘制相同背景色
            if (termChar.bgColor != config.backgroundColor) {
                if (currentBgColor != termChar.bgColor) {
                    // 绘制之前的背景
                    currentBgColor?.let {
                        bgPaint.color = it
                        canvas.drawRect(bgStartX, y, x, y + charHeight, bgPaint)
                    }
                    currentBgColor = termChar.bgColor
                    bgStartX = x
                }
            } else {
                // 绘制累积的背景
                currentBgColor?.let {
                    bgPaint.color = it
                    canvas.drawRect(bgStartX, y, x, y + charHeight, bgPaint)
                }
                currentBgColor = null
                bgStartX = x + charWidth
            }

            when {
                // 宽字符的第二格占位：无文本（首格已按 2 格宽绘制），背景照常
                termChar.isContinuation -> {
                    if (col > runStart && runText.isNotEmpty()) {
                        drawTextRun(canvas, line, runStart, col, startX, y, baseline, charWidth, charHeight, runText)
                    }
                    runText.setLength(0)
                    runStart = col + 1
                }
                // 宽字符首格：断 run 单独绘制（等宽字体中 CJK/emoji 自然占 2 格宽）
                AnsiTerminalEmulator.displayWidth(termChar.char) == 2 -> {
                    if (col > runStart && runText.isNotEmpty()) {
                        drawTextRun(canvas, line, runStart, col, startX, y, baseline, charWidth, charHeight, runText)
                    }
                    runText.setLength(0)
                    applyCharStyle(termChar)
                    canvas.drawText(termChar.char.toString(), x, y + baseline, textPaint)
                    runStart = col + 1
                }
                else -> {
                    // 样式变化时 flush 上一个 run
                    if (col > runStart && !sameCharStyle(line[runStart], termChar)) {
                        drawTextRun(canvas, line, runStart, col, startX, y, baseline, charWidth, charHeight, runText)
                        runStart = col
                        runText.setLength(0)
                    }

                    val ch = if (termChar.char != ' ' && !termChar.isHidden) termChar.char else ' '
                    runText.append(ch)
                }
            }

            x += charWidth
        }

        // flush 最后一个 run
        if (runStart < line.size && runText.isNotEmpty()) {
            drawTextRun(canvas, line, runStart, line.size, startX, y, baseline, charWidth, charHeight, runText)
        }

        // 绘制行尾的背景
        currentBgColor?.let {
            bgPaint.color = it
            canvas.drawRect(bgStartX, y, x, y + charHeight, bgPaint)
        }
    }

    /**
     * 一次 drawText 绘制一个样式一致的字符 run（含下划线/删除线）。
     */
    private fun drawTextRun(
        canvas: Canvas,
        line: Array<TerminalChar>,
        runStart: Int,
        runEnd: Int,
        startX: Float,
        y: Float,
        baseline: Float,
        charWidth: Float,
        charHeight: Float,
        text: CharSequence
    ) {
        if (runStart >= runEnd) return

        val head = line[runStart]
        applyCharStyle(head)

        val runX = startX + runStart * charWidth
        canvas.drawText(text, 0, text.length, runX, y + baseline, textPaint)

        val runWidth = (runEnd - runStart) * charWidth
        if (head.isUnderline) {
            val underlineY = y + baseline + 2f
            canvas.drawLine(runX, underlineY, runX + runWidth, underlineY, textPaint)
        }
        if (head.isStrikethrough) {
            val strikeY = y + charHeight / 2f
            canvas.drawLine(runX, strikeY, runX + runWidth, strikeY, textPaint)
        }
    }

    /**
     * 按字符样式设置 textPaint（前景色 + 粗斜体）。
     */
    private fun applyCharStyle(termChar: TerminalChar) {
        textMetrics.applyStyle(termChar.isBold, termChar.isItalic)

        var fgColor = termChar.fgColor
        if (termChar.isDim) {
            // 使颜色变暗
            fgColor = Color.argb(
                180,
                Color.red(fgColor),
                Color.green(fgColor),
                Color.blue(fgColor)
            )
        }
        if (termChar.isInverse) {
            // 反转前景和背景色
            fgColor = termChar.bgColor
        }

        textPaint.color = fgColor
    }

    /**
     * 判断两个字符是否属于同一可合并绘制 run（视觉样式完全一致）。
     */
    private fun sameCharStyle(a: TerminalChar, b: TerminalChar): Boolean {
        return a.fgColor == b.fgColor && a.bgColor == b.bgColor &&
            a.isBold == b.isBold && a.isItalic == b.isItalic &&
            a.isDim == b.isDim && a.isInverse == b.isInverse &&
            a.isUnderline == b.isUnderline && a.isStrikethrough == b.isStrikethrough
    }
    
    private fun drawCursor(canvas: Canvas, charWidth: Float, charHeight: Float) {
        // deferred wrap 时光标列可能等于 screenWidth（越界一格），绘制时收敛到最后一列
        val cursorX = (emulator?.getCursorX() ?: 0).coerceAtMost(cachedCols - 1)
        val cursorY = (emulator?.getCursorY() ?: 0).coerceAtMost(cachedRows - 1)

        val x = cursorX * charWidth
        val y = cursorY * charHeight - scrollOffsetY

        // 绘制光标方块
        canvas.drawRect(x, y, x + charWidth, y + charHeight, cursorPaint)
    }
    
    private fun drawSelection(canvas: Canvas, charWidth: Float, charHeight: Float) {
        val selection = selectionManager.selection?.normalize() ?: return
        
        for (row in selection.startRow..selection.endRow) {
            val y = row * charHeight - scrollOffsetY
            
            val startCol = if (row == selection.startRow) selection.startCol else 0
            val endCol = if (row == selection.endRow) {
                selection.endCol
            } else {
                emulator?.getScreenContent()?.getOrNull(row)?.size ?: 0
            }
            
            val x1 = startCol * charWidth
            val x2 = (endCol + 1) * charWidth
            
            canvas.drawRect(x1, y, x2, y + charHeight, selectionPaint)
        }
    }
    
    // === 触摸事件处理 ===
    
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = gestureHandler.onTouchEvent(event)
        
        // 单击时请求焦点并显示输入法（全屏模式下）
        if (event.action == MotionEvent.ACTION_DOWN) {
            if (!hasFocus()) {
                requestFocus()
            }
            // 全屏模式下，单击即显示输入法
            if (isFullscreenMode && !selectionManager.hasSelection()) {
                postDelayed({
                    showSoftKeyboard()
                }, 100) // 延迟100ms确保焦点已获取
            }
        }
        
        // 处理选择移动
        if (selectionManager.hasSelection() && event.action == MotionEvent.ACTION_MOVE) {
            val (row, col) = screenToTerminalCoords(event.x, event.y)
            selectionManager.updateSelection(row, col)
            requestRender()
        }
        
        if (event.action == MotionEvent.ACTION_UP && selectionManager.hasSelection()) {
            showTextSelectionMenu()
        }
        
        return handled || super.onTouchEvent(event)
    }
    
    /**
     * 屏幕坐标转换为终端坐标
     */
    private fun screenToTerminalCoords(x: Float, y: Float): Pair<Int, Int> {
        val row = ((y + scrollOffsetY) / textMetrics.charHeight).toInt()
        val col = (x / textMetrics.charWidth).toInt()
        return Pair(row, col)
    }
    
    /**
     * 开始文本选择
     */
    private fun startTextSelection(x: Float, y: Float) {
        val (row, col) = screenToTerminalCoords(x, y)
        selectionManager.startSelection(row, col)
        requestRender()
    }
    
    /**
     * 显示文本选择菜单
     */
    private fun showTextSelectionMenu() {
        if (actionMode != null) return
        
        actionMode = startActionMode(object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(0, 1, 0, "复制")
                return true
            }
            
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                return false
            }
            
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                when (item.itemId) {
                    1 -> {
                        copySelectedText()
                        mode.finish()
                        return true
                    }
                }
                return false
            }
            
            override fun onDestroyActionMode(mode: ActionMode) {
                actionMode = null
                selectionManager.clearSelection()
                requestRender()
            }
        })
    }
    
    /**
     * 复制选中的文本
     */
    private fun copySelectedText() {
        val selection = selectionManager.selection?.normalize() ?: return
        val buffer = emulator?.getScreenContent() ?: return
        
        val text = buildString {
            for (row in selection.startRow..selection.endRow) {
                if (row >= buffer.size) break
                
                val line = buffer[row]
                val startCol = if (row == selection.startRow) selection.startCol else 0
                val endCol = if (row == selection.endRow) selection.endCol else line.size - 1
                
                for (col in startCol..endCol) {
                    if (col < line.size) {
                        append(line[col].char)
                    }
                }
                
                if (row < selection.endRow) {
                    append('\n')
                }
            }
        }
        
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Terminal", text))
    }
    
    // === 输入法支持 ===
    
    /**
     * 显示软键盘
     */
    private fun showSoftKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }
    
    /**
     * 隐藏软键盘
     */
    fun hideSoftKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(windowToken, 0)
    }

    /**
     * 判断单行文本是否「像粘贴的命令」而非正常逐字输入：
     * 含提示符（$/#/sudo）或代码块围栏等特征时，需要走净化。
     */
    private fun looksLikePastedCommand(s: String): Boolean {
        if (s.length < 4) return false
        val t = s.trim()
        return t.startsWith("$") || t.startsWith("#") || t.startsWith(">") ||
            t.startsWith("sudo") || t.contains("```") || t.contains("~~~") ||
            Regex("\\S+@\\S+[:~]").containsMatchIn(t)
    }
    
    /**
     * 创建输入连接
     */
    override fun onCreateInputConnection(outAttrs: EditorInfo?): InputConnection {
        outAttrs?.apply {
            inputType = EditorInfo.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_NONE
        }
        
        return object : BaseInputConnection(this, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                text?.let {
                    val s = it.toString()
                    // 粘贴多行/带说明文字的命令时净化：去围栏、提示符、注释、sudo
                    if (s.contains('\n') || looksLikePastedCommand(s)) {
                        val cleaned = CommandSanitizer.sanitizeToCommands(s)
                        if (cleaned.isNotEmpty()) {
                            cleaned.forEach { cmd -> inputCallback?.invoke(cmd + "\n") }
                        }
                    } else {
                        inputCallback?.invoke(s)
                    }
                }
                return true
            }
            
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0) {
                    // 发送退格键
                    inputCallback?.invoke("\u007F") // DEL character
                }
                return true
            }
            
            override fun sendKeyEvent(event: KeyEvent?): Boolean {
                event?.let {
                    if (it.action == KeyEvent.ACTION_DOWN) {
                        when (it.keyCode) {
                            KeyEvent.KEYCODE_DEL -> {
                                inputCallback?.invoke("\u007F")
                                return true
                            }
                            KeyEvent.KEYCODE_ENTER -> {
                                inputCallback?.invoke("\n")
                                return true
                            }
                        }
                    }
                }
                return super.sendKeyEvent(event)
            }
        }
    }
    
    /**
     * 检查是否可以显示输入法
     */
    override fun onCheckIsTextEditor(): Boolean {
        return isFullscreenMode
    }
    
    /**
     * 更新终端窗口大小
     */
    private fun updateTerminalSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        
        // 确保字体指标已更新（基于当前缩放因子）
        textMetrics.updateFontSize(config.fontSize * scaleFactor)
        
        // 计算终端尺寸（行和列）
        val cols = (width / textMetrics.charWidth).toInt().coerceAtLeast(1)
        val rows = (height / textMetrics.charHeight).toInt().coerceAtLeast(1)
        
        // 只有当尺寸真正发生变化时才更新
        if (rows == cachedRows && cols == cachedCols) {
            return
        }
        
        cachedRows = rows
        cachedCols = cols
        
        // 更新模拟器尺寸
        emulator?.resize(cols, rows)
        
        // 同步 PTY 窗口尺寸
        pty?.setWindowSize(rows, cols)
    }
}

