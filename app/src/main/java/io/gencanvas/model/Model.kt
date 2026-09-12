package io.gencanvas.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 顶层文档：AI 产物的根。
 *
 * 设计要点：
 * 1. 所有字段都有默认值 —— 缺字段不该让解析失败。
 * 2. Op 采用 [type + 参数表] 而非 sealed 多态 —— 未知指令可沉默丢弃，
 *    这是"AI 自由发挥"能被容忍的前提。
 */

data class UiDocument(
    val version: Int = 1,
    val theme: Theme = Theme(),
    val vars: Map<String, JsonElement> = emptyMap(),
    val root: Node,
    val actions: Map<String, JsonElement> = emptyMap(),
)

data class Theme(
    val colors: Map<String, String> = emptyMap(),
    val radii: Map<String, Float> = emptyMap(),
    val spacings: Map<String, Float> = emptyMap(),
    val textStyles: Map<String, TextStyle> = emptyMap(),
)

data class TextStyle(
    val size: Float? = null,
    val weight: String? = null,
    val color: String? = null,
    val letterSpacing: Float? = null,
    val lineHeight: Float? = null,
)

/**
 * 节点：只回答"我在哪"和"我画什么"，不回答"我是什么组件"。
 */
data class Node(
    val id: String? = null,
    val flex: Flex = Flex(),
    val ops: List<Op> = emptyList(),
    val children: List<Node> = emptyList(),
    val anim: AnimSpec? = null,
    val event: EventSpec? = null,
    val blur: Float? = null,
    val layer: Boolean = false,
    val visible: String? = null,     // 支持 {{/path}} 绑定
    val contentDesc: String? = null,
)

// ---------------------------------------------------------------- Layout

/**
 * Yoga / Flexbox 语义的布局描述。
 * 尺寸统一为 [Dim]，支持 dp、百分比、auto。
 */
data class Flex(
    val w: Dim? = null, val h: Dim? = null,
    val minW: Dim? = null, val maxW: Dim? = null,
    val minH: Dim? = null, val maxH: Dim? = null,
    val grow: Float? = null, val shrink: Float? = null, val basis: Dim? = null,
    val direction: String? = null,
    val justify: String? = null,
    val align: String? = null,
    val alignSelf: String? = null,
    val wrap: String? = null,
    val gap: Float? = null,
    val pad: Float? = null, val padX: Float? = null, val padY: Float? = null,
    val padL: Float? = null, val padT: Float? = null,
    val padR: Float? = null, val padB: Float? = null,
    val margin: Float? = null, val marginX: Float? = null, val marginY: Float? = null,
    val marginL: Float? = null, val marginT: Float? = null,
    val marginR: Float? = null, val marginB: Float? = null,
    val position: String? = null,
    val l: Float? = null, val t: Float? = null,
    val r: Float? = null, val b: Float? = null,
    val ratio: Float? = null,
)

sealed interface Dim {
    data class Pt(val v: Float) : Dim
    data class Pct(val v: Float) : Dim      // 0..100
    object Auto : Dim
}

// ---------------------------------------------------------------- Paint

/**
 * 绘制指令。未知 [type] 在实现中被丢弃。
 */
data class Op(
    val type: String,
    val p: JsonObject = JsonObject(emptyMap()),
)

/** 画刷：solid / 三种渐变，四选一，全空则透明。 */
data class BrushSpec(
    val solid: String? = null,
    val linear: GradientSpec? = null,
    val radial: GradientSpec? = null,
    val sweep: GradientSpec? = null,
)

data class GradientSpec(
    val colors: List<String> = emptyList(),
    val stops: List<Float>? = null,
    val from: List<Float>? = null,     // 0..1 相对坐标
    val to: List<Float>? = null,
    val center: List<Float>? = null,
    val radius: Float? = null,
    val tile: String? = null,
)

// ---------------------------------------------------------------- Anim

data class AnimSpec(
    val trigger: String = "appear",    // appear | press | state | loop
    val dur: Int = 300,
    val delay: Int = 0,
    val easing: String = "standard",
    val from: Map<String, Float> = emptyMap(),
    val to: Map<String, Float> = emptyMap(),
    val repeat: String? = null,        // null | "infinite"
    val keyframes: List<Keyframe>? = null,
    val watch: String? = null,         // trigger=state 时监听的 JSON Pointer
)

data class Keyframe(val at: Float, val v: Map<String, Float>)

/** 可动画属性集合（只驱动 transform/alpha → GPU 合成，不触发重绘）。 */
data class AnimValues(
    val alpha: Float = 1f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val rotate: Float = 0f,
    val tx: Float = 0f,
    val ty: Float = 0f,
) {
    companion object {
        fun from(m: Map<String, Float>, base: AnimValues = AnimValues()) = AnimValues(
            alpha = m["alpha"] ?: base.alpha,
            scaleX = m["scaleX"] ?: m["scale"] ?: base.scaleX,
            scaleY = m["scaleY"] ?: m["scale"] ?: base.scaleY,
            rotate = m["rotate"] ?: base.rotate,
            tx = m["tx"] ?: base.tx,
            ty = m["ty"] ?: base.ty,
        )
    }
}

// ---------------------------------------------------------------- Event

data class EventSpec(
    val onClick: JsonElement? = null,      // 字符串(action名) 或 { "set": {...} }
    val onLongPress: JsonElement? = null,
    val hitPadding: Float? = null,
    val hitShape: String? = null,          // rect | rrect | circle
    val hitRadius: Float? = null,
    val pressAnim: PressAnim? = null,
)

data class PressAnim(
    val scale: Float? = null,
    val alpha: Float? = null,
    val dur: Int = 120,
)

// ---------------------------------------------------------------- 布局结果

/** 布局计算后的节点框（像素）。 */
data class Frame(val x: Float, val y: Float, val w: Float, val h: Float) {
    val right get() = x + w
    val bottom get() = y + h

    fun contains(px: Float, py: Float, pad: Float = 0f) =
        px >= x - pad && px <= right + pad && py >= y - pad && py <= bottom + pad
}

/** 布局完成树：节点 + 自身 frame（相对父容器）。 */
class LaidNode(
    val node: Node,
    val frame: Frame,
    val children: List<LaidNode>,
) {
    /** 命中测试：逆序（后绘制的在上层）+ 形状精判。 */
    fun hitTest(px: Float, py: Float, absX: Float, absY: Float): LaidNode? {
        val ev = node.event ?: return children.asReversed().firstNotNullOfOrNull {
            it.hitTest(px, py, absX + frame.x, absY + frame.y)
        }
        val lx = px - (absX + frame.x)
        val ly = py - (absY + frame.y)
        if (!hitShape(lx, ly, ev)) return null
        return this
    }

    private fun hitShape(lx: Float, ly: Float, ev: EventSpec): Boolean {
        val pad = ev.hitPadding ?: 0f
        when (ev.hitShape) {
            "circle" -> {
                val r = frame.w / 2f
                val dx = lx - r; val dy = ly - frame.h / 2f
                return dx * dx + dy * dy <= (r + pad) * (r + pad)
            }
            "rrect" -> {
                val rr = ev.hitRadius ?: 0f
                if (!Frame(0f, 0f, frame.w, frame.h).contains(lx, ly, pad)) return false
                // 圆角外判定：四个角象限内到角心的距离
                val cx = if (lx < rr) rr else if (lx > frame.w - rr) frame.w - rr else lx
                val cy = if (ly < rr) rr else if (ly > frame.h - rr) frame.h - rr else ly
                val dx = lx - cx; val dy = ly - cy
                return dx * dx + dy * dy <= (rr + pad) * (rr + pad)
            }
            else -> return Frame(0f, 0f, frame.w, frame.h).contains(lx, ly, pad)
        }
    }
}
