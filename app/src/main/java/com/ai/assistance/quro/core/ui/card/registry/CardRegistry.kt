package com.ai.assistance.quro.core.ui.card.registry

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.ai.assistance.quro.core.ui.card.spec.Action
import com.ai.assistance.quro.core.ui.card.spec.CardSpec
import com.ai.assistance.quro.core.ui.card.spec.CardTypes
import com.ai.assistance.quro.core.ui.card.spec.ColorToken

/**
 * 编排层：类型化注册表 → Composable 渲染函数（Server-Driven UI 标准范式）。
 *
 * 每个卡片类型注册一个 `@Composable (CardSpec, Modifier, onAction, resolveToken) -> Unit`，
 * 编排层只做分发，不做任何像素测量/排版/绘制。
 *
 * 离散元素（文字 / 按钮 / 容器）一律交给 Compose 原语（Column / Row / Text / Button）
 * 自动排版——彻底消除旧版「在单个 Canvas 上手绘整棵布局树」的架构反模式：
 * 文字宽度错估、右侧内容消失、行溢出、未知节点塌陷等一连串打补丁式 bug 全部消失。
 * 仅连续几何（折线 / 柱 / 饼 / 环形 / 进度条）才用局部 Canvas。
 *
 * ## 三级解析（这是「小卡片不完整」的根治点）
 * 1. `type` 直接命中 → 用它的渲染器；
 * 2. `type` 归一后命中别名（`bar` → `bar_chart`）→ 用别名指向的渲染器；
 * 3. 都没命中 → [resolveOrInfer] 按 **data 的形状**推断等价渲染器（截断 JSON / 模型自造类型都救得回来）。
 * 三级都落空才算「真不认识」，由宿主降级（绝不崩对话框）。
 */
typealias CardRendererFn = @Composable (
    spec: CardSpec,
    modifier: Modifier,
    onAction: (Action) -> Unit,
    resolveToken: (ColorToken) -> Color,
) -> Unit

object CardRegistry {
    private val renderers = LinkedHashMap<String, CardRendererFn>()
    private val aliases = LinkedHashMap<String, String>()

    /** 注册渲染器（type 自动归一，`Bar-Chart` / `barchart` 都能命中）。 */
    fun register(type: String, renderer: CardRendererFn) {
        renderers[CardTypes.canonical(type)] = renderer
    }

    /**
     * 注册别名（`bar` → `bar_chart`）。
     *
     * 只允许指向已注册的自有类型；指向不存在类型的别名会被忽略，
     * 避免「白名单说认识、渲染器却取不到」的半残状态。
     */
    fun registerAlias(alias: String, canonical: String) {
        val target = CardTypes.canonical(canonical)
        if (target !in renderers) return
        val key = CardTypes.canonical(alias)
        if (key in renderers) return // 真实类型优先，别名不得遮蔽
        aliases[key] = target
    }

    /** 是否属于本通道（决定 ChatScreen 走 CardSurface 还是富卡片层）。 */
    fun isWhitelisted(type: String): Boolean {
        val k = CardTypes.canonical(type)
        return k in renderers || k in aliases
    }

    /** 取渲染函数（含别名）；拿不到返回 null。 */
    fun resolveOrNull(type: String): CardRendererFn? {
        val k = CardTypes.canonical(type)
        renderers[k]?.let { return it }
        return aliases[k]?.let { renderers[it] }
    }

    /**
     * 三级解析的最后一级：type 不认识时按 data 形状推断。
     *
     * @return 实际使用的类型名 → 渲染器；连推断都失败返回 null
     */
    fun resolveOrInfer(spec: CardSpec): Pair<String, CardRendererFn>? {
        resolveOrNull(spec.type)?.let { return CardTypes.canonical(spec.type) to it }
        val inferred = CardTypes.inferType(spec) ?: return null
        val fn = renderers[inferred] ?: return null
        return inferred to fn
    }

    /** 已注册的自有类型（不含别名）。给文档 / 提示词 / 单测用。 */
    fun registeredTypes(): Set<String> = LinkedHashSet(renderers.keys)

    /** 已注册别名（便于调试与单测核对）。 */
    fun registeredAliases(): Map<String, String> = LinkedHashMap(aliases)
}
