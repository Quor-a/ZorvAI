package com.ai.assistance.quro.terminal.kai

import androidx.compose.runtime.Immutable

/** One cell in the terminal grid. [fg]/[bg] are 0–15 ANSI palette indices. */
@Immutable
data class TerminalCell(
    val char: Char = ' ',
    val fg: Int = 7,
    val bg: Int = 0,
    val bold: Boolean = false,
)

/**
 * Immutable view of the screen for Compose. [revision] bumps on every change so
 * collectors recompose even when dimensions stay the same.
 *
 * [hyperlinks] are URIs extracted from OSC 8 sequences (xterm hyperlinks). TUIs
 * like Grok put login URLs there while showing only "click here" on the grid.
 *
 * 移植自 Kai，去掉 kotlinx.collections.immutable 依赖（[hyperlinks] 用普通 List）。
 */
@Immutable
data class TerminalSnapshot(
    val columns: Int,
    val rows: Int,
    /** Row-major cells, size = columns * rows. */
    val cells: List<TerminalCell>,
    val cursorCol: Int,
    val cursorRow: Int,
    val cursorVisible: Boolean,
    /** DECCKM state — decides how the key row encodes arrows. */
    val applicationCursorKeys: Boolean = false,
    /** What the running app asked to hear about touches, if anything. */
    val mouse: TerminalMouseState = TerminalMouseState(),
    val revision: Long,
    val hyperlinks: List<String> = emptyList(),
) {
    fun cellAt(col: Int, row: Int): TerminalCell {
        if (col !in 0 until columns || row !in 0 until rows) return TerminalCell()
        return cells[row * columns + col]
    }

    companion object {
        fun blank(columns: Int = DEFAULT_COLUMNS, rows: Int = DEFAULT_ROWS) = TerminalSnapshot(
            columns = columns,
            rows = rows,
            cells = List(columns * rows) { TerminalCell() },
            cursorCol = 0,
            cursorRow = 0,
            cursorVisible = true,
            applicationCursorKeys = false,
            mouse = TerminalMouseState(),
            revision = 0L,
            hyperlinks = emptyList(),
        )
    }
}

const val DEFAULT_COLUMNS = 80
const val DEFAULT_ROWS = 24

const val MIN_COLUMNS = 20
const val MAX_COLUMNS = 300
const val MIN_ROWS = 8
const val MAX_ROWS = 120
