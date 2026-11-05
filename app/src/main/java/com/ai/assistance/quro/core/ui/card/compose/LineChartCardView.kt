package com.ai.assistance.quro.core.ui.card.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.ai.assistance.quro.core.ui.card.spec.CardSpec
import com.ai.assistance.quro.core.ui.card.spec.ColorToken

/**
 * 折线图卡的**兼容入口**。
 *
 * 2026-11 重构后所有图表（折线 / 面积 / 柱状 / 饼环 / 迷你线）都收敛到 [ChartCardView]：
 * 它们共用坐标轴、图例、x 轴标签与动画相位 —— 之前每种图各写一份，改一处字号要改五个文件，
 * 而且同一份数据在 `line_chart` / `bar_chart` 两种写法下留白都不一致。
 *
 * 这个函数保留下来只为两件事：`CardModule` 的注册不变、既有调用点与单测不破。
 * 真正的绘制逻辑见 [ChartCardView]。
 */
@Composable
fun LineChartCardView(
    spec: CardSpec,
    modifier: Modifier = Modifier,
    resolveToken: (ColorToken) -> Color,
) {
    ChartCardView(spec, modifier, resolveToken)
}

/** 数值标注去尾零：8432.0→"8432"、0.85→"0.85"，超长截断到 8 字符。 */
internal fun trimNum(v: Float): String {
    val s = if (v == v.toLong().toFloat()) v.toLong().toString()
    else String.format("%.2f", v).trimEnd('0').trimEnd('.')
    return if (s.length > 8) s.take(8) else s
}
