package com.ai.assistance.quro.terminal.kai

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.core.terminal.QuroShellSession

/**
 * 真·终端渲染面板：把 [QuroShellSession.vt]（Kai 移植的 VT100/xterm 引擎）画到画布上，
 * 叠加透明 IME 输入层（捕获键盘），底部一排特殊功能键（Ctrl/Alt/方向等）。
 *
 * 这是「kai9000 那种终端」——带 ANSI 颜色 / 光标 / 加粗的真终端，而非旧版 LazyColumn 纯文本。
 * 若 [QuroShellSession.vt] 为 null（未启用 VT），本面板不会用于该窗格，旧 UI 继续走纯文本。
 */
@Composable
fun KaiTerminalPane(
    session: QuroShellSession,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val fontSizeDp = 13
    val fontSizePx = with(density) { fontSizeDp.dp.toPx() }
    val cellW = fontSizePx * 0.6f
    val cellH = fontSizePx * 1.4f
    val baseline = fontSizePx * 1.15f

    var ctrlOn by remember { mutableStateOf(false) }
    var altOn by remember { mutableStateOf(false) }
    var kbReq by remember { mutableIntStateOf(0) }
    var lastSize by remember { mutableStateOf(IntSize.Zero) }

    val snap = session.vtSnapshot.value
    val appKeys = snap?.applicationCursorKeys ?: false
    val latch = TerminalModifiers(ctrl = ctrlOn, alt = altOn)

    val onKey: (TerminalKey, TerminalModifiers) -> Unit = { key, hw ->
        val m = latch + hw
        session.sendKey(TerminalKeyEncoder.encode(key, m, appKeys))
        ctrlOn = false
        altOn = false
    }
    val onText: (String, TerminalModifiers) -> Unit = { text, hw ->
        val m = latch + hw
        session.sendKey(TerminalKeyEncoder.encodeText(text, m))
        ctrlOn = false
        altOn = false
    }

    Column(modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black)
                .onSizeChanged { size ->
                    if (size == lastSize) return@onSizeChanged
                    lastSize = size
                    if (session.vt == null) {
                        session.vt = TerminalScreen(MIN_COLUMNS, MIN_ROWS)
                    }
                    val cols = (size.width / cellW).toInt().coerceIn(MIN_COLUMNS, MAX_COLUMNS)
                    val rows = (size.height / cellH).toInt().coerceIn(MIN_ROWS, MAX_ROWS)
                    session.vt?.resize(cols, rows)
                    session.vt?.let { session.vtSnapshot.value = it.snapshot() }
                },
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val s = session.vtSnapshot.value ?: return@Canvas
                drawGrid(this, s, cellW, cellH, baseline, fontSizePx)
            }
            // 透明输入层：捕获软/硬键盘，点击即弹出键盘
            KaiTerminalInputLayer(
                showKeyboardRequest = kbReq,
                onKey = onKey,
                onText = onText,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 特殊功能键行
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF121212))
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            KeyChip("Ctrl", active = ctrlOn) { ctrlOn = !ctrlOn }
            KeyChip("Alt", active = altOn) { altOn = !altOn }
            KeyChip("Esc") { session.sendKey(TerminalKeyEncoder.encode(TerminalKey.Escape)) }
            KeyChip("Tab") { session.sendKey(TerminalKeyEncoder.encode(TerminalKey.Tab)) }
            KeyChip("⌨") { kbReq++ }
            Spacer(Modifier.width(6.dp))
            KeyChip("←") { session.sendKey(TerminalKeyEncoder.encode(TerminalKey.Left, latch, appKeys)) }
            KeyChip("→") { session.sendKey(TerminalKeyEncoder.encode(TerminalKey.Right, latch, appKeys)) }
            KeyChip("↑") { session.sendKey(TerminalKeyEncoder.encode(TerminalKey.Up, latch, appKeys)) }
            KeyChip("↓") { session.sendKey(TerminalKeyEncoder.encode(TerminalKey.Down, latch, appKeys)) }
        }
    }
}

private fun drawGrid(
    scope: DrawScope,
    snap: TerminalSnapshot,
    cellW: Float,
    cellH: Float,
    baseline: Float,
    fontSizePx: Float,
) {
    val paint = Paint().apply {
        textSize = fontSizePx
        typeface = MONO
        isAntiAlias = true
    }
    val bgPaint = Paint().apply { style = Paint.Style.FILL }
    scope.drawIntoCanvas { canvas ->
        val nc = canvas.nativeCanvas
        // 整屏黑底
        nc.drawColor(android.graphics.Color.BLACK)
        // 背景层
        for (row in 0 until snap.rows) {
            for (col in 0 until snap.columns) {
                val cell = snap.cellAt(col, row)
                val bg = ANSI_PALETTE[cell.bg.coerceIn(0, 15)]
                if (bg != 0xFF000000.toInt()) {
                    bgPaint.color = bg
                    nc.drawRect(col * cellW, row * cellH, (col + 1) * cellW, (row + 1) * cellH, bgPaint)
                }
            }
        }
        // 字符层
        for (row in 0 until snap.rows) {
            for (col in 0 until snap.columns) {
                val cell = snap.cellAt(col, row)
                if (cell.char == ' ') continue
                paint.color = ANSI_PALETTE[cell.fg.coerceIn(0, 15)]
                paint.isFakeBoldText = cell.bold
                nc.drawText(cell.char.toString(), col * cellW, row * cellH + baseline, paint)
            }
        }
        // 光标块
        if (snap.cursorVisible) {
            val cx = snap.cursorCol * cellW
            val cy = snap.cursorRow * cellH
            bgPaint.color = 0xFFBFBFBF.toInt()
            nc.drawRect(cx, cy, cx + cellW, cy + cellH, bgPaint)
            val c = snap.cellAt(snap.cursorCol, snap.cursorRow)
            if (c.char != ' ') {
                paint.color = 0xFF000000.toInt()
                paint.isFakeBoldText = c.bold
                nc.drawText(c.char.toString(), cx, cy + baseline, paint)
            }
        }
    }
}

@Composable
private fun KeyChip(label: String, active: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .wrapContentSize()
            .background(if (active) Color(0xFF3A6EA5) else Color(0xFF2A2A2A))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 12.sp)
    }
}

private val MONO: Typeface = Typeface.MONOSPACE

/**
 * 16 色 ANSI 调色板（xterm 风格）。索引 0=黑 … 15=白。
 */
private val ANSI_PALETTE = intArrayOf(
    0xFF000000.toInt(), // 0 black
    0xFFCD0000.toInt(), // 1 red
    0xFF00CD00.toInt(), // 2 green
    0xFFCDCD00.toInt(), // 3 yellow
    0xFF0000EE.toInt(), // 4 blue
    0xFFCD00CD.toInt(), // 5 magenta
    0xFF00CDCD.toInt(), // 6 cyan
    0xFFE5E5E5.toInt(), // 7 white
    0xFF7F7F7F.toInt(), // 8 bright black
    0xFFFF0000.toInt(), // 9 bright red
    0xFF00FF00.toInt(), // 10 bright green
    0xFFFFFF00.toInt(), // 11 bright yellow
    0xFF5C5CFF.toInt(), // 12 bright blue
    0xFFFF00FF.toInt(), // 13 bright magenta
    0xFF00FFFF.toInt(), // 14 bright cyan
    0xFFFFFFFF.toInt(), // 15 bright white
)
