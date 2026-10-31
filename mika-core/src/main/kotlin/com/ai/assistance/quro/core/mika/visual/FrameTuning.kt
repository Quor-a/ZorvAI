package com.ai.assistance.quro.core.mika.visual

/** 动作前后两帧画面的粗比较用的格子与阈值(判动作效果的旁证)。 */
object FrameTuning {
    /** 画面格子的列数、行数。 */
    const val GRID_COLS = 24
    const val GRID_ROWS = 48
    /** 一格亮度差超过这么多算这格变了。 */
    const val CELL_DELTA = 16
    /** 整屏至少这么多格(约 1%)变了才算看得出的变化。 */
    const val FRAME_VISIBLE = 0.01
    /** 目标区域里至少这么多格变了才算区域里看得出的变化。 */
    const val REGION_VISIBLE = 0.15
    /** 整屏这么多格变了算大面积变化。 */
    const val FRAME_LARGE = 0.25
}
