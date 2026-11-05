package com.ai.assistance.quro.core.ui.card

import com.ai.assistance.quro.core.ui.card.compose.ButtonGroupCardView
import com.ai.assistance.quro.core.ui.card.compose.ChartCardView
import com.ai.assistance.quro.core.ui.card.compose.CustomCardView
import com.ai.assistance.quro.core.ui.card.compose.FormCardView
import com.ai.assistance.quro.core.ui.card.compose.LineChartCardView
import com.ai.assistance.quro.core.ui.card.compose.MediaCardView
import com.ai.assistance.quro.core.ui.card.compose.MetricCardView
import com.ai.assistance.quro.core.ui.card.compose.SkeletonCardView
import com.ai.assistance.quro.core.ui.card.compose.StatusCardView
import com.ai.assistance.quro.core.ui.card.compose.TableCardView
import com.ai.assistance.quro.core.ui.card.registry.CardRegistry
import com.ai.assistance.quro.core.ui.card.spec.CardTypes

/**
 * 自研卡片渲染的装配入口（与 dynamic UI 完全独立，不合并）。
 *
 * ## 分层（改这里之前先看这张图）
 * ```
 *  AI 输出 ```quro-card 围栏 JSON
 *        │  parseCardSpec()            ← spec/CardSpecParser（协议层，纯 JVM）
 *        ▼
 *  CardSpec(type + data + layout + style + title)
 *        │  CardRegistry.resolveOrInfer()  ← registry（编排层：三级解析）
 *        ▼
 *  @Composable 渲染器                 ← compose 包下各渲染器（表现层，Compose 原语 + 局部 Canvas）
 *        │  CardShell 统一外壳
 *        ▼
 *  对话框内联渲染
 * ```
 *
 * ## 注册的是「一种数据形态 + 一个渲染器」，不是「一种名字」
 * `metric` / `line_chart` / `bar_chart` … 都是同一套 [ChartCardView] 的不同数据形态；
 * 别名（`bar`、`spark`、`kpi`…）只是让模型的随手写法也能命中，定义全部集中在 [CardTypes]。
 *
 * ⚠️ 与 `core.cards.CardSdk`（103 种组件库）的类型名**不允许重叠**：
 * 重叠的名字会被这里的白名单抢走，而两边字段名不兼容，结果是「本来能渲染的卡变空白」。
 * 详见 [CardTypes.ALIASES] 的注释与 `CardPipelineTest` 的相关断言。
 */
object CardModule {
    private var initialized = false

    fun init() {
        if (initialized) return

        // ── 指标 / 图表族：共用 ChartCardView，按 data.chartType 分派 ──
        CardRegistry.register(CardTypes.METRIC) { spec, modifier, _, resolveToken ->
            MetricCardView(spec, modifier, resolveToken)
        }
        CardRegistry.register(CardTypes.LINE_CHART) { spec, modifier, _, resolveToken ->
            LineChartCardView(spec, modifier, resolveToken)
        }
        CardRegistry.register(CardTypes.BAR_CHART) { spec, modifier, _, resolveToken ->
            ChartCardView(spec, modifier, resolveToken)
        }
        CardRegistry.register(CardTypes.PIE_CHART) { spec, modifier, _, resolveToken ->
            ChartCardView(spec, modifier, resolveToken)
        }
        CardRegistry.register(CardTypes.AREA_CHART) { spec, modifier, _, resolveToken ->
            ChartCardView(spec, modifier, resolveToken)
        }
        CardRegistry.register(CardTypes.SPARKLINE) { spec, modifier, _, resolveToken ->
            ChartCardView(spec, modifier, resolveToken)
        }

        // ── 结构族：表格 / 媒体 / 表单 ──
        CardRegistry.register(CardTypes.TABLE) { spec, modifier, _, resolveToken ->
            TableCardView(spec, modifier, resolveToken)
        }
        CardRegistry.register(CardTypes.MEDIA) { spec, modifier, _, resolveToken ->
            MediaCardView(spec, modifier, resolveToken)
        }
        CardRegistry.register(CardTypes.CODE) { spec, modifier, _, resolveToken ->
            MediaCardView(spec, modifier, resolveToken)
        }
        CardRegistry.register(CardTypes.IMAGES) { spec, modifier, _, resolveToken ->
            MediaCardView(spec, modifier, resolveToken)
        }
        CardRegistry.register(CardTypes.FORM) { spec, modifier, onAction, resolveToken ->
            FormCardView(spec, modifier, onAction, resolveToken)
        }
        CardRegistry.register(CardTypes.BUTTON_GROUP) { spec, modifier, onAction, resolveToken ->
            ButtonGroupCardView(spec, modifier, onAction, resolveToken)
        }

        // ── 状态族：进度 / 错误 / 工具调用 / 骨架 ──
        CardRegistry.register(CardTypes.STATUS) { spec, modifier, onAction, resolveToken ->
            StatusCardView(spec, modifier, onAction, resolveToken)
        }
        CardRegistry.register(CardTypes.SKELETON) { spec, modifier, _, resolveToken ->
            SkeletonCardView(spec, modifier, resolveToken)
        }

        // ── 自由布局：AI 自写 layout 树 ──
        CardRegistry.register(CardTypes.CUSTOM) { spec, modifier, onAction, resolveToken ->
            CustomCardView(spec, modifier, onAction, resolveToken)
        }

        // ── 别名：模型的随手写法（定义在 CardTypes，只指向上面已注册的类型）──
        CardTypes.ALIASES.forEach { (alias, canonical) -> CardRegistry.registerAlias(alias, canonical) }

        initialized = true
    }

    fun registeredTypes(): Set<String> = CardRegistry.registeredTypes()
}
