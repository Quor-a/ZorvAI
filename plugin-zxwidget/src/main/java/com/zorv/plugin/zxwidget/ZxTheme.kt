package com.zorv.plugin.zxwidget

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 主题变体 —— 同一份 DSL，三种密度渲染。
 *
 * 为什么需要：同一张表，有人觉得正好，有人觉得字太小挤得慌，
 * 弱视用户要大字号高对比。如果为此让 AI 输出三份 DSL，
 * 等于把一份存储和维护成本变成三份，而且三份还会各自漂移。
 *
 * 所以做"渲染期变体"：DSL 不变，主题决定间距/字号/圆角/描边粗细。
 * 变体不是换配色——配色是品牌层的事，变体只解决"看得清 vs 装得下"。
 *
 * 三种预设对应三种真实场景：
 *   COMPACT      信息优先，一屏多装 40%，通勤路上扫一眼
 *   COMFORTABLE  默认，正常阅读
 *   ACCESSIBLE   无障碍，1.3× 字号 + 高对比 + 加粗分隔线（配合 ZxA11y）
 *
 * 关键约束：变体只能改"度量"，不能改"结构"。
 * 列数、层级、能不能点这些由 DSL 决定，主题无权改变——
 * 否则同一份 DSL 在不同主题下语义不同，模型就没法预期渲染结果了。
 */
object ZxTheme {

    enum class Variant(val label: String, val desc: String) {
        COMPACT("紧凑", "一屏多装约 40%，通勤快速扫读"),
        COMFORTABLE("标准", "默认阅读密度"),
        ACCESSIBLE("无障碍", "1.3× 字号 + 高对比 + 加粗分隔线")
    }

    /** 一整套度量 */
    data class Metrics(
        /** 卡片内边距 */
        val pad: Dp,
        /** 行间距 */
        val gapRow: Dp,
        /** 块间距 */
        val gapBlock: Dp,
        /** 正文 */
        val fsBody: TextUnit,
        /** 标题 */
        val fsTitle: TextUnit,
        /** 次要 */
        val fsCaption: TextUnit,
        /** 行高倍数 */
        val lineHeight: Float,
        /** 圆角 */
        val radius: Dp,
        /** 分隔线粗细 */
        val stroke: Dp,
        /** 最小可点尺寸 */
        val minTouch: Dp
    )

    private val M = mapOf(
        Variant.COMPACT to Metrics(
            pad = 8.dp, gapRow = 4.dp, gapBlock = 6.dp,
            fsBody = 11.sp, fsTitle = 13.sp, fsCaption = 9.5.sp,
            lineHeight = 1.32f, radius = 8.dp, stroke = 0.5.dp, minTouch = 32.dp
        ),
        Variant.COMFORTABLE to Metrics(
            pad = 11.dp, gapRow = 7.dp, gapBlock = 11.dp,
            fsBody = 12.sp, fsTitle = 14.sp, fsCaption = 10.5.sp,
            lineHeight = 1.5f, radius = 11.dp, stroke = 1.dp, minTouch = 44.dp
        ),
        Variant.ACCESSIBLE to Metrics(
            pad = 13.dp, gapRow = 9.dp, gapBlock = 14.dp,
            fsBody = 15.6.sp, fsTitle = 18.2.sp, fsCaption = 13.65.sp,
            lineHeight = 1.62f, radius = 12.dp, stroke = 1.6.dp, minTouch = 48.dp
        )
    )

    private var current: Variant = Variant.COMFORTABLE
    private var custom: Metrics? = null

    fun current(): Variant = current
    fun set(v: Variant) { current = v; custom = null }
    fun metrics(): Metrics = custom ?: M[current]!!

    /** 允许宿主覆盖度量（系统字号跟随等场景） */
    fun override(m: Metrics) { custom = m }

    /**
     * 按内容规模自动挑变体。
     * 依据：行数 × 列数越大，越该压缩——这是纯几何判断，不涉及语义。
     */
    fun autoFor(rows: Int, cols: Int): Variant {
        val cells = rows * cols.coerceAtLeast(1)
        return when {
            cells > 60 -> Variant.COMPACT
            cells < 12 -> Variant.ACCESSIBLE   // 内容少就放大，别留大片空白
            else -> Variant.COMFORTABLE
        }
    }

    fun paddingValues(): PaddingValues = metrics().let {
        PaddingValues(it.pad, it.pad, it.pad, it.pad)
    }

    /** 规格说明，供 /zxtheme 输出 */
    fun spec(): String = buildString {
        appendLine("当前变体：${current.label}（${current.desc}）")
        M.forEach { (v, m) ->
            appendLine("  ${v.label.padEnd(4)} 内边距 ${m.pad.value.toInt()}dp · 行距 ${m.gapRow.value.toInt()}dp · " +
                       "正文 ${m.fsBody.value.toInt()}sp · 行高 ${m.lineHeight} · 圆角 ${m.radius.value.toInt()}dp")
        }
        appendLine()
        appendLine("变体只改度量，不改结构（列数/层级/可点性由 DSL 决定）")
    }

    /** 供模型参考：什么时候该切变体 */
    fun guide(): String = """
        zx_theme(op=set, variant=compact|comfortable|accessible)
        zx_theme(op=auto, rows=20, cols=6)   → 按内容规模自动挑

        规则：单元格数 >60 用紧凑，<12 用无障碍，其余标准。
        变体只影响间距/字号/圆角/描边，不影响列数与结构。
    """.trimIndent()
}
