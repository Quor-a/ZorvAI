package com.ai.assistance.quro.core.ui.card

import com.ai.assistance.quro.core.ui.card.registry.CardRegistry
import com.ai.assistance.quro.core.ui.card.registry.CardRenderer
import com.ai.assistance.quro.core.ui.card.widgets.ButtonGroupRenderer
import com.ai.assistance.quro.core.ui.card.widgets.ButtonGroupState
import com.ai.assistance.quro.core.ui.card.widgets.LineChartRenderer
import com.ai.assistance.quro.core.ui.card.widgets.LineChartState
import com.ai.assistance.quro.core.ui.card.widgets.MetricCardRenderer
import com.ai.assistance.quro.core.ui.card.widgets.MetricCardState
import com.ai.assistance.quro.core.ui.card.widgets.SkeletonRenderer
import com.ai.assistance.quro.core.ui.card.widgets.SkeletonState

/**
 * 新功能「自研卡片渲染」的装配入口（与 dynamic UI 完全独立，不合并）。
 *
 * 把 4 个自研渲染器注册进白名单。以后每加一种卡片，只需在此 + widgets/ 里
 * 注册一个渲染器，架构不再动（按用户贴的「建议的落地顺序」：先跑通主干）。
 */
object CardModule {
    private var initialized = false

    fun init() {
        if (initialized) return
        CardRegistry.register("metric", MetricCardRenderer() as CardRenderer<MetricCardState>)
        CardRegistry.register("line_chart", LineChartRenderer() as CardRenderer<LineChartState>)
        CardRegistry.register("button_group", ButtonGroupRenderer() as CardRenderer<ButtonGroupState>)
        CardRegistry.register("skeleton", SkeletonRenderer() as CardRenderer<SkeletonState>)
        // 媒体/表单/状态其他子类按需追加……
        initialized = true
    }

    fun registeredTypes(): Set<String> = CardRegistry.registeredTypes()
}
