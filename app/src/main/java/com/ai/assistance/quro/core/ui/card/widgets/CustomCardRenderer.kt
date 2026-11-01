package com.ai.assistance.quro.core.ui.card.widgets

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.ai.assistance.quro.core.ui.card.registry.AnimateAware
import com.ai.assistance.quro.core.ui.card.registry.CardRenderer
import com.ai.assistance.quro.core.ui.card.registry.CardState
import com.ai.assistance.quro.core.ui.card.registry.HitTarget
import com.ai.assistance.quro.core.ui.card.registry.LayoutResult
import com.ai.assistance.quro.core.ui.card.render.RenderBackend
import com.ai.assistance.quro.core.ui.card.spec.CardSpec
import com.ai.assistance.quro.core.ui.card.spec.ColorToken
import com.ai.assistance.quro.core.ui.card.spec.LayoutNode

/**
 * 自定义布局卡（type="custom"）——**AI 自写、不内置**的小卡片形态。
 *
 * 设计前提：用户要的小卡片不是固定模板，而是 AI 每次自己设计的卡片。
 * 客户端只提供渲染原语（容器/文本/渐变/环形/进度条/动效相位），
 * 结构、配色、风格全部由 AI 下发的 [CardSpec.layout] 树决定，没有内置样式。
 * （HTML 仅作设计稿参考——这里不渲染 HTML、不用 WebView，全部端上自绘。）
 *
 * 节点类型：
 *  - `card` / `box`：容器，背景纯色 bg(#hex) 或渐变 gradient(["#hex",...]) + angle，
 *    圆角 radius、内边距 padding、子节点间距 gap；children 竖排
 *  - `column` / `row`：竖排 / 横排（row 子节点 weight>0 时按比例分宽）
 *  - `text`：文本；props.countTo+prefix+suffix 时数字入场滚动动画
 *  - `spacer`（props.h 占位高）、`divider`（分隔线）
 *  - `ring`：环形进度（value 0-100、中心 text，入场扫角动画）
 *  - `bar`：进度条（value 0-100，入场生长动画）
 */
class CustomCardState : CardState, AnimateAware {
    /** 入场动画相位 0→1（数字滚动/进度生长），mutableStateOf 供 Canvas 逐帧重绘 */
    var progress by mutableStateOf(0f)

    /** measure 时缓存 density（render 阶段 dp→px 用），默认 2.75 兜底 */
    var density: Float = 2.75f

    override fun onFrame(p: Float) { progress = p }
    override fun canMerge(other: CardState) = other is CustomCardState
}

class CustomCardRenderer : CardRenderer<CustomCardState> {
    override fun createInitialState() = CustomCardState()

    override fun measure(spec: CardSpec, state: CustomCardState, maxWidthPx: Float, density: Float): Size {
        state.density = density
        val root = spec.layout ?: return Size(maxWidthPx, spPx(14f, density) * 3)
        return measureNode(root, maxWidthPx, density)
    }

    override fun layout(spec: CardSpec, state: CustomCardState, size: Size, density: Float): LayoutResult =
        LayoutResult(size.width, size.height)

    override fun render(backend: RenderBackend, spec: CardSpec, result: LayoutResult, state: CustomCardState) {
        val root = spec.layout ?: return
        drawNode(backend, root, 0f, 0f, result.width, state)
    }

    override fun hitTest(p: Offset, result: LayoutResult, state: CustomCardState): HitTarget? = null
}

// ══════════════════ 私有递归：测量 / 排版绘制 ══════════════════

private fun num(props: Map<String, Any?>, key: String, def: Float): Float =
    (props[key] as? Number)?.toFloat() ?: def

private fun easeOutCubic(p: Float): Float {
    val q = 1f - p.coerceIn(0f, 1f)
    return 1f - q * q * q
}

/** 解析颜色：#rrggbb / #aarrggbb / 主题 token 名（primary/onSurface/surface_variant...，大小写与下划线不敏感），失败返回 null。 */
private fun parseColor(any: Any?, backend: RenderBackend): Color? {
    val s = any?.toString() ?: return null
    return try {
        if (s.startsWith("#")) androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(s))
        else {
            // 大小写/下划线不敏感匹配 token（"onSurface"/"onsurface"/"ON_SURFACE" 都认）
            val norm = s.replace("_", "")
            val tok = ColorToken.values().firstOrNull { it.name.equals(norm, ignoreCase = true) } ?: return null
            backend.resolve(tok)
        }
    } catch (_: Exception) { null }
}

/** text 节点显示内容：countTo 数字按入场相位滚动；否则静态 text。 */
private fun displayText(node: LayoutNode, state: CustomCardState): String {
    val p = node.props
    val ct = (p["countTo"] as? Number)
    if (ct != null) {
        val v = ct.toFloat() * easeOutCubic(state.progress)
        val pre = p["prefix"]?.toString() ?: ""
        val suf = p["suffix"]?.toString() ?: ""
        val body = if (ct.toFloat() == ct.toLong().toFloat()) {
            @Suppress("UnusedEquals") val l = v.toLong(); "%,d".format(l)
        } else String.format("%.1f", v)
        return pre + body + suf
    }
    return p["text"]?.toString() ?: ""
}

private fun measureNode(node: LayoutNode, availW: Float, d: Float): Size {
    val pad = num(node.props, "padding", node.style.paddingDp) * d
    val gap = num(node.props, "gap", 8f) * d
    val inner = (availW - pad * 2).coerceAtLeast(0f)
    return when (node.type) {
        "card", "box", "column" -> {
            if (node.children.isEmpty()) {
                Size(inner, num(node.props, "h", 40f) * d + pad * 2)
            } else {
                val sizes = node.children.map { measureNode(it, inner, d) }
                val hSum = sizes.fold(0f) { acc, s -> acc + s.height }
                Size(
                    availW,
                    hSum + gap * (sizes.size - 1).coerceAtLeast(0) + pad * 2,
                )
            }
        }
        "row" -> {
            if (node.children.isEmpty()) {
                Size(inner, num(node.props, "h", 20f) * d + pad * 2)
            } else {
                // weight>0 的子节点按比例瓜分剩余宽度（先测非 weight 子）
                val fixed = node.children.filter { it.weight <= 0f }.map { measureNode(it, inner, d) }
                val fixedW = fixed.fold(0f) { acc, s -> acc + s.width }
                val wSum = node.children.filter { it.weight > 0f }.fold(0f) { acc, n -> acc + n.weight }.coerceAtLeast(0.001f)
                val free = (inner - fixedW - gap * (node.children.size - 1)).coerceAtLeast(0f)
                val sizes = node.children.map {
                    if (it.weight > 0f) measureNode(it, free * (it.weight / wSum), d)
                    else measureNode(it, inner, d)
                }
                Size(availW, (sizes.maxOf { it.height }) + pad * 2)
            }
        }
        "text" -> {
            val fs = num(node.props, "fontSize", node.style.fontSizeSp)
            val t = node.props["text"]?.toString()
                ?: (node.props["countTo"]?.toString() ?: "") + (node.props["suffix"]?.toString() ?: "")
            // 宽度粗估：CJK 按 1em、ASCII 按 0.55em
            var w = 0f
            for (ch in t) w += (if (ch.code > 0x2E80) 1f else 0.55f) * fs * d
            Size(w.coerceAtMost(availW), spPx(fs, d))
        }
        "spacer" -> Size(num(node.props, "w", 0f) * d, num(node.props, "h", 8f) * d)
        "divider" -> Size(availW, 1f)
        "ring" -> {
            // 强制 clamp：AI 给的 size 过大时（如 size=300dp 在 inner=200dp 的列里），
            // 直接返回 availW 大小的方框，避免后续 childAllocs/drawChildren 拿未裁剪的内容宽
            // 推算 cy/cx 时把 sibling 推到屏幕外。同时 clamp 高度，避免拉爆 CardSurface 总高度。
            val s = (num(node.props, "size", 90f) * d).coerceAtMost(availW).coerceAtLeast(0f)
            Size(s, s)
        }
        "bar" -> Size(availW, num(node.props, "h", 10f) * d)
        // ── 2026-11-02 补：AI 常写但原来被当成未知节点吞掉（尺寸 0 → 布局塌陷）──
        // 小标签/胶囊：文字宽 + 左右 padding，高 = 行高 + 上下 padding
        "badge", "chip", "tag", "pill" -> {
            val fs = num(node.props, "fontSize", 11f)
            val t = node.props["text"]?.toString() ?: node.props["label"]?.toString() ?: ""
            var w = 0f
            for (ch in t) w += (if (ch.code > 0x2E80) 1f else 0.55f) * fs * d
            val hp = num(node.props, "paddingX", 8f) * d
            Size((w + hp * 2).coerceAtMost(availW), spPx(fs, d) + num(node.props, "paddingY", 4f) * d * 2)
        }
        // 按钮：撑满（或按 width 指定），高 = 行高 + 上下 padding
        "button" -> {
            val fs = num(node.props, "fontSize", 14f)
            val bw = num(node.props, "width", 0f)
            val w = if (bw > 0f) (bw * d).coerceAtMost(availW) else availW
            Size(w, spPx(fs, d) + num(node.props, "paddingY", 10f) * d * 2)
        }
        // 图标/图片：方形占位（客户端不加载网络图，画占位块 + 可选文字）
        "icon", "image", "avatar" -> {
            val s = (num(node.props, "size", 24f) * d).coerceAtMost(availW).coerceAtLeast(0f)
            Size(s, s)
        }
        // 列表：无 children 时按 props.items 的条数占高
        "list", "items", "group", "grid" -> {
            if (node.children.isNotEmpty()) {
                val sizes = node.children.map { measureNode(it, inner, d) }
                val hSum = sizes.fold(0f) { acc, s -> acc + s.height }
                Size(availW, hSum + gap * (sizes.size - 1).coerceAtLeast(0) + pad * 2)
            } else {
                val n = (node.props["items"] as? List<*>)?.size ?: 1
                val rowH = spPx(num(node.props, "fontSize", 14f), d) + gap
                Size(availW, rowH * n.coerceAtLeast(1) + pad * 2)
            }
        }
        // 🔴 未知节点不再返回 0：带 text 的按文本测，否则给最小可视高度。
        // 原来返回 Size(0,0) 会让整块区域消失、后续兄弟节点坐标错位 —— 用户看到的就是「卡片不完整」。
        else -> {
            val t = node.props["text"]?.toString() ?: node.props["label"]?.toString() ?: ""
            if (t.isNotEmpty()) measureNode(node.copy(type = "text"), availW, d)
            else Size(availW, (num(node.props, "h", 0f) * d).coerceAtLeast(1f))
        }
    }
}

/**
 * 测量节点「内容宽」——row 里非 weight 子节点用它分配真实宽度。
 *
 * 与 [measureNode] 的区别：measureNode 对容器节点一律返回 availW（撑满父宽），
 * 用于顶层卡片撑满屏幕；但在 row 里横向连排时若每个子都吃满父宽，
 * 第 2 个子会被画到 x+inner+gap，直接溢出屏幕（只露半截）。
 * 内容宽 = 子节点实际需要的宽度（取最大/累加），并 clamp 在 availW 内。
 */
private fun measureContent(node: LayoutNode, availW: Float, d: Float): Size {
    val pad = num(node.props, "padding", node.style.paddingDp) * d
    val gap = num(node.props, "gap", 8f) * d
    val inner = (availW - pad * 2).coerceAtLeast(0f)
    return when (node.type) {
        // 叶子节点：measureNode 本身就是内容宽
        "text", "ring", "spacer", "divider", "badge", "chip", "tag", "pill",
        "button", "icon", "image", "avatar" -> measureNode(node, availW, d)
        // 撑满型叶子：与 measureNode 同口径（占 availW），超出部分由 childAllocs/drawChildren 统一 clamp
        "bar" -> measureNode(node, availW, d)
        "card", "box", "column", "list", "items", "group", "grid" -> {
            if (node.children.isEmpty()) measureNode(node, availW, d)
            else {
                val sizes = node.children.map { measureContent(it, inner, d) }
                val w = sizes.fold(0f) { acc, s -> kotlin.math.max(acc, s.width) }
                val h = sizes.fold(0f) { acc, s -> acc + s.height } + gap * (sizes.size - 1).coerceAtLeast(0)
                Size((w + pad * 2).coerceAtMost(availW), h + pad * 2)
            }
        }
        "row" -> {
            if (node.children.isEmpty()) measureNode(node, availW, d)
            else {
                val sizes = node.children.map { measureContent(it, inner, d) }
                val w = sizes.fold(0f) { acc, s -> acc + s.width } + gap * (sizes.size - 1).coerceAtLeast(0)
                val h = sizes.fold(0f) { acc, s -> kotlin.math.max(acc, s.height) }
                Size((w + pad * 2).coerceAtMost(availW), h + pad * 2)
            }
        }
        // 🔴 未知节点也走 measureNode 兜底（带 text 按文本、否则最小高度），
        // 不再返回 0 —— row 里返回 0 宽会让该子节点被跳过、整行错位。
        else -> measureNode(node, availW, d)
    }
}

/** 计算容器内各子节点的分配宽度（row 支持 weight 分配），返回 (width, size) 对。 */
private fun childAllocs(node: LayoutNode, inner: Float, d: Float): List<Pair<Float, Size>> {
    val gap = num(node.props, "gap", 8f) * d
    if (node.type == "row") {
        val n = node.children.size
        val gapTotal = gap * (n - 1).coerceAtLeast(0)
        // 非 weight 子节点按「内容宽」测量：绝不能每个都吃满 inner，否则横向连排必然溢出屏幕。
        val contentSizes = node.children.map { if (it.weight <= 0f) measureContent(it, inner, d) else null }
        val fixedW = contentSizes.filterNotNull().fold(0f) { acc, s -> acc + s.width }
        val wSum = node.children.filter { it.weight > 0f }.fold(0f) { acc, x -> acc + x.weight }.coerceAtLeast(0.001f)
        val free = (inner - fixedW - gapTotal).coerceAtLeast(0f)
        val out = ArrayList<Pair<Float, Size>>(n)
        // 内容宽总和超 inner 时整体等比压缩，保证横向绝不溢出
        val over = (fixedW + gapTotal) - inner
        val shrink = if (over > 0f && fixedW > 0f) (inner - gapTotal).coerceAtLeast(0f) / fixedW else 1f
        node.children.forEachIndexed { i, c ->
            if (c.weight > 0f) {
                val w = free * (c.weight / wSum)
                out.add(w to measureNode(c, w, d))
            } else {
                val s = contentSizes[i]!!
                val w = (s.width * shrink).coerceAtMost(inner)
                out.add(w to s)
            }
        }
        return out
    }
    return node.children.map { inner to measureNode(it, inner, d) }
}

private fun drawNode(backend: RenderBackend, node: LayoutNode, x: Float, y: Float, allocW: Float, state: CustomCardState) {
    val d = state.density
    val pad = num(node.props, "padding", node.style.paddingDp) * d
    val gap = num(node.props, "gap", 8f) * d
    when (node.type) {
        "card", "box" -> {
            // 防御性二次 clamp：measureNode 对 card/box/column 默认 width=availW 已撑满父，
            // 但若被夹在 row 中（allocW 可能小于 parent 内原始 availW）或其他递归层漏算，
            // 仍把 width 钳到 allocW，避免 drawRect/drawGradientRect 画到 CardSurface 右边界外。
            val size = measureNode(node, allocW, d)
            val clampedW = size.width.coerceAtMost(allocW).coerceAtLeast(0f)
            val radius = num(node.props, "radius", node.style.cornerDp)
            val grad = (node.props["gradient"] as? List<*>)?.mapNotNull { parseColor(it, backend) }
            if (grad != null && grad.size >= 2) {
                backend.drawGradientRect(x, y, x + clampedW, y + size.height, grad, num(node.props, "angle", 135f), radius)
            } else {
                val bg = parseColor(node.props["bg"], backend)
                    ?: parseColor(node.style.bg.name.lowercase(), backend)
                    ?: backend.resolve(ColorToken.SurfaceVariant)
                backend.drawRect(x, y, x + clampedW, y + size.height, bg, radius)
            }
            drawChildren(backend, node, x + pad, y + pad, (allocW - pad * 2).coerceAtLeast(0f), state, d)
        }
        "column" -> drawChildren(backend, node, x + pad, y + pad, (allocW - pad * 2).coerceAtLeast(0f), state, d)
        "row" -> drawChildren(backend, node, x + pad, y + pad, (allocW - pad * 2).coerceAtLeast(0f), state, d)
        // 列表类容器：与 column 同绘制（有 children 就竖排；没 children 按 items 画文字行）
        "list", "items", "group", "grid" -> {
            if (node.children.isNotEmpty()) {
                drawChildren(backend, node, x + pad, y + pad, (allocW - pad * 2).coerceAtLeast(0f), state, d)
            } else {
                val items = (node.props["items"] as? List<*>) ?: emptyList<Any?>()
                val fs = num(node.props, "fontSize", 14f)
                val rowH = spPx(fs, d) + gap
                val color = parseColor(node.props["color"], backend) ?: backend.resolve(ColorToken.OnSurface)
                items.forEachIndexed { i, it ->
                    val t = when (it) {
                        is Map<*, *> -> (it["text"] ?: it["label"] ?: it["title"] ?: "").toString()
                        else -> it?.toString() ?: ""
                    }
                    if (t.isNotEmpty()) backend.drawText(t, x + pad, y + pad + rowH * i, color, fs, 400)
                }
            }
        }
        "text" -> {
            val fs = num(node.props, "fontSize", node.style.fontSizeSp)
            // 🔴 字体粗细读 fontWeight / bold；`weight` 是布局权重（结构性键），不能当字体粗细用
            val weight = when {
                node.props.containsKey("fontWeight") -> num(node.props, "fontWeight", 400f).toInt()
                (node.props["bold"] as? Boolean) == true -> 700
                else -> node.style.fontWeight
            }
            val color = parseColor(node.props["color"], backend)
                ?: parseColor(node.style.fg.name.lowercase(), backend)
                ?: backend.resolve(ColorToken.OnSurface)
            backend.drawText(displayText(node, state), x, y, color, fs, weight)
        }
        // ── 2026-11-02 补：胶囊标签 / 按钮 / 图标占位（原来落到 else 被完全忽略）──
        "badge", "chip", "tag", "pill" -> {
            val fs = num(node.props, "fontSize", 11f)
            val t = node.props["text"]?.toString() ?: node.props["label"]?.toString() ?: ""
            var tw = 0f
            for (ch in t) tw += (if (ch.code > 0x2E80) 1f else 0.55f) * fs * d
            val hp = num(node.props, "paddingX", 8f) * d
            val bx = num(node.props, "paddingY", 4f) * d
            val w = (tw + hp * 2).coerceAtMost(allocW)
            val h = spPx(fs, d) + bx * 2
            val bgc = parseColor(node.props["bg"], backend) ?: backend.resolve(ColorToken.Primary)
            backend.drawRect(x, y, x + w, y + h, bgc, num(node.props, "radius", h / 2f))
            if (t.isNotEmpty()) {
                val fg = parseColor(node.props["color"], backend) ?: backend.resolve(ColorToken.OnPrimary)
                backend.drawText(t, x + hp, y + bx, fg, fs, 600)
            }
        }
        "button" -> {
            val fs = num(node.props, "fontSize", 14f)
            val bw = num(node.props, "width", 0f)
            val w = if (bw > 0f) (bw * d).coerceAtMost(allocW) else allocW
            val py = num(node.props, "paddingY", 10f) * d
            val h = spPx(fs, d) + py * 2
            val bgc = parseColor(node.props["bg"], backend) ?: backend.resolve(ColorToken.Primary)
            backend.drawRect(x, y, x + w, y + h, bgc, num(node.props, "radius", 10f))
            val t = node.props["text"]?.toString() ?: node.props["label"]?.toString() ?: ""
            if (t.isNotEmpty()) {
                var tw = 0f
                for (ch in t) tw += (if (ch.code > 0x2E80) 1f else 0.55f) * fs * d
                val fg = parseColor(node.props["color"], backend) ?: backend.resolve(ColorToken.OnPrimary)
                backend.drawText(t, x + ((w - tw) / 2f).coerceAtLeast(0f), y + py, fg, fs, 600)
            }
        }
        "icon", "image", "avatar" -> {
            val s = (num(node.props, "size", 24f) * d).coerceAtMost(allocW).coerceAtLeast(0f)
            val bgc = parseColor(node.props["bg"], backend) ?: backend.resolve(ColorToken.SurfaceVariant)
            val radius = if (node.type == "avatar") s / 2f else num(node.props, "radius", 6f)
            backend.drawRect(x, y, x + s, y + s, bgc, radius)
            val t = node.props["text"]?.toString() ?: node.props["emoji"]?.toString() ?: ""
            if (t.isNotEmpty()) {
                val fs = num(node.props, "fontSize", 14f)
                backend.drawText(t, x + s * 0.15f, y + s * 0.25f, backend.resolve(ColorToken.OnSurface), fs, 400)
            }
        }
        "spacer" -> { /* 纯占位 */ }
        "divider" -> backend.drawLine(x, y, x + allocW, y, backend.resolve(ColorToken.Outline), 1f)
        "ring" -> {
            // 空间不足时缩小圆环（而非按自身 size 硬画导致溢出屏幕）
            val side = (num(node.props, "size", 90f) * d).coerceAtMost(allocW).coerceAtLeast(0f)
            val stroke = num(node.props, "stroke", 8f)
            val v = num(node.props, "value", 0f)
            val col = parseColor(node.props["color"], backend) ?: backend.resolve(ColorToken.Primary)
            // 底环 + 前景环（入场扫角动画）
            backend.drawArc(x, y, x + side, y + side, 0f, 360f, backend.resolve(ColorToken.Outline), stroke)
            val sweep = 360f * (v / 100f).coerceIn(0f, 1f) * easeOutCubic(state.progress)
            if (sweep > 0.5f) backend.drawArc(x, y, x + side, y + side, -90f, sweep, col, stroke)
            // 中心文字
            val ct = node.props["text"]?.toString()
            if (!ct.isNullOrEmpty()) {
                val fs = num(node.props, "textSize", 20f)
                val cx = x + side / 2f
                var w = 0f
                for (ch in ct) w += (if (ch.code > 0x2E80) 1f else 0.55f) * fs * d
                backend.drawText(ct, (cx - w / 2f).coerceAtLeast(x), y + side / 2f - spPx(fs, d) / 2f, backend.resolve(ColorToken.OnSurface), fs, 700)
            }
        }
        "bar" -> {
            val h = num(node.props, "h", 10f) * d
            val radius = num(node.props, "radius", 6f)
            val v = num(node.props, "value", 0f)
            backend.drawRect(x, y, x + allocW, y + h, backend.resolve(ColorToken.SurfaceVariant), radius)
            val w = allocW * (v / 100f).coerceIn(0f, 1f) * easeOutCubic(state.progress)
            if (w > 1f) {
                val grad = (node.props["gradient"] as? List<*>)?.mapNotNull { parseColor(it, backend) }
                if (grad != null && grad.size >= 2) {
                    backend.drawGradientRect(x, y, x + w, y + h, grad, 0f, radius)
                } else {
                    val col = parseColor(node.props["color"], backend) ?: backend.resolve(ColorToken.Primary)
                    backend.drawRect(x, y, x + w, y + h, col, radius)
                }
            }
        }
        // 🔴 未知节点兜底（必须是最后一个分支）：带文字就按文字画出来，绝不让内容凭空消失。
        // 原来这里是 `else -> { /* 未知节点忽略 */ }`，AI 写 image/chart/progress 等
        // 未实现节点时整块内容直接不见了 —— 用户看到的就是「卡片不完整」。
        else -> {
            val t = node.props["text"]?.toString() ?: node.props["label"]?.toString() ?: ""
            if (t.isNotEmpty()) {
                val fs = num(node.props, "fontSize", node.style.fontSizeSp)
                val color = parseColor(node.props["color"], backend)
                    ?: backend.resolve(ColorToken.OnSurface)
                backend.drawText(t, x, y, color, fs, 400)
            }
        }
    }
}

/** 竖排/横排子节点递归绘制（row 横向推进、其余纵向推进）。 */
private fun drawChildren(
    backend: RenderBackend, node: LayoutNode, x: Float, y: Float, inner: Float,
    state: CustomCardState, d: Float,
) {
    val gap = num(node.props, "gap", 8f) * d
    val allocs = childAllocs(node, inner, d)
    var cx = x
    var cy = y
    // 横向硬边界：row 连排时任何估算误差都不允许画到父容器右边界之外（防溢出屏幕）
    val rightLimit = x + inner
    node.children.forEachIndexed { i, child ->
        val (w, s) = allocs[i]
        if (node.type == "row") {
            if (cx >= rightLimit - 0.5f) return   // 已无剩余空间，停止绘制后续子节点
            drawNode(backend, child, cx, cy, w.coerceAtMost(rightLimit - cx), state)
            cx += w + gap
        } else {
            drawNode(backend, child, cx, cy, w, state)
            cy += s.height + gap
        }
    }
}
