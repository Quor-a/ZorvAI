package io.gencanvas.runtime

import io.gencanvas.model.AnimSpec
import io.gencanvas.model.BrushSpec
import io.gencanvas.model.Dim
import io.gencanvas.model.EventSpec
import io.gencanvas.model.Flex
import io.gencanvas.model.Keyframe
import io.gencanvas.model.Node
import io.gencanvas.model.Op
import io.gencanvas.model.PressAnim
import io.gencanvas.model.TextStyle
import io.gencanvas.model.Theme
import io.gencanvas.model.UiDocument
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 净化层（L1）：AI 产物 → 保证安全的 [UiDocument]。
 *
 * 这是"自由化"能成立的前提 —— 因为放弃了对输入的约束，
 * 就必须在入口把风险全部挡掉。
 *
 * 铁律：**任何路径都不 throw 到调用方。**
 */
object Sanitizer {

    // ---------------------------------------------------------- 资源围栏
    object Limits {
        const val MAX_DEPTH = 32
        const val MAX_NODES = 2000
        const val MAX_OPS_PER_NODE = 256
        const val MAX_STRING = 64 * 1024
        const val MAX_CHILDREN = 500
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String): UiDocument? = runCatching {
        val el = json.parseToJsonElement(raw.take(Limits.MAX_STRING * 8))
        val o = el as? JsonObject ?: return null
        UiDocument(
            version = o.get("version")?.int() ?: 1,
            theme = parseTheme(o["theme"] as? JsonObject),
            vars = (o["vars"] as? JsonObject)?.toMap() ?: emptyMap(),
            root = parseNode(o["root"] as? JsonObject, 0) ?: Node(),
            actions = (o["actions"] as? JsonObject)?.toMap() ?: emptyMap(),
        )
    }.getOrNull()

    private fun parseTheme(o: JsonObject?) = Theme(
        colors = o.strMap("colors"),
        radii = o.floatMap("radii"),
        spacings = o.floatMap("spacings"),
        textStyles = (o?.get("textStyles") as? JsonObject)?.mapValues { (_, v) ->
            val t = v as? JsonObject ?: return@mapValues TextStyle()
            TextStyle(t["size"].float(), t["weight"].str(), t["color"].str(),
                t["letterSpacing"].float(), t["lineHeight"].float())
        } ?: emptyMap(),
    )

    private fun parseNode(o: JsonObject?, depth: Int): Node? {
        if (o == null || depth > Limits.MAX_DEPTH) return null
        val kids = (o["children"] as? JsonArray)
            ?.mapNotNull { parseNode(it as? JsonObject, depth + 1) }
            ?.take(Limits.MAX_CHILDREN)
            ?: emptyList()
        return Node(
            id = o.get("id")?.str(),
            flex = parseFlex(o["flex"] as? JsonObject),
            ops = (o["ops"] as? JsonArray)
                ?.mapNotNull { it.asOp() }
                ?.take(Limits.MAX_OPS_PER_NODE)
                ?: emptyList(),
            children = kids,
            anim = (o["anim"] as? JsonObject)?.let { a ->
                AnimSpec(
                    trigger = a["trigger"].str() ?: "appear",
                    dur = a["dur"].int() ?: 300,
                    delay = a["delay"].int() ?: 0,
                    easing = a["easing"].str() ?: "standard",
                    from = a.floats("from"), to = a.floats("to"),
                    repeat = a["repeat"].str(),
                    keyframes = (a["keyframes"] as? JsonArray)?.mapNotNull { k ->
                        val ko = k as? JsonObject ?: return@mapNotNull null
                        Keyframe(ko.get("at")?.float() ?: 0f, ko.floats("v"))
                    },
                    watch = a["watch"].str(),
                )
            },
            event = (o["event"] as? JsonObject)?.let { e ->
                EventSpec(
                    onClick = e["onClick"],
                    onLongPress = e["onLongPress"],
                    hitPadding = e["hitPadding"].float(),
                    hitShape = e["hitShape"].str(),
                    hitRadius = e["hitRadius"].float(),
                    pressAnim = (e["pressAnim"] as? JsonObject)?.let { p ->
                        PressAnim(p["scale"].float(), p["alpha"].float(), p["dur"].int() ?: 120)
                    },
                )
            },
            blur = o.get("blur")?.float(),
            layer = (o["layer"] as? JsonPrimitive)?.booleanOrNull ?: false,
            visible = o.get("visible")?.str(),
            contentDesc = o.get("contentDesc")?.str(),
        )
    }

    private fun parseFlex(o: JsonObject?) = Flex(
        w = o.dim("w"), h = o.dim("h"),
        minW = o.dim("minW"), maxW = o.dim("maxW"),
        minH = o.dim("minH"), maxH = o.dim("maxH"),
        grow = o?.get("grow")?.float(), shrink = o?.get("shrink")?.float(), basis = o.dim("basis"),
        direction = o?.get("direction")?.str(), justify = o?.get("justify")?.str(),
        align = o?.get("align")?.str(), alignSelf = o?.get("alignSelf")?.str(), wrap = o?.get("wrap")?.str(),
        gap = o?.get("gap")?.float(),
        pad = o?.get("pad")?.float(), padX = o?.get("padX")?.float(), padY = o?.get("padY")?.float(),
        padL = o?.get("padL")?.float(), padT = o?.get("padT")?.float(),
        padR = o?.get("padR")?.float(), padB = o?.get("padB")?.float(),
        margin = o?.get("margin")?.float(), marginX = o?.get("marginX")?.float(), marginY = o?.get("marginY")?.float(),
        marginL = o?.get("marginL")?.float(), marginT = o?.get("marginT")?.float(),
        marginR = o?.get("marginR")?.float(), marginB = o?.get("marginB")?.float(),
        position = o?.get("position")?.str(),
        l = o?.get("l")?.float(), t = o?.get("t")?.float(), r = o?.get("r")?.float(), b = o?.get("b")?.float(),
        ratio = o?.get("ratio")?.float(),
    )

    private fun JsonObject?.dim(k: String): Dim? = when (val v = this?.get(k)) {
        null -> null
        is JsonPrimitive -> v.content.let { s ->
            when {
                s == "auto" || s == "wrap" -> Dim.Auto
                s.endsWith("%") -> Dim.Pct(s.dropLast(1).toFloatOrNull() ?: return null)
                else -> Dim.Pt(s.toFloatOrNull() ?: return null)
            }
        }
        else -> null
    }

    private fun JsonElement?.asOp(): Op? {
        val o = this as? JsonObject ?: return null
        val t = o.get("op")?.str() ?: return null
        return Op(t, o)
    }

    // ------------------------------------------------------ 取值 helper（全容错）

    private fun JsonElement?.str(): String? =
        (this as? JsonPrimitive)?.content?.take(Limits.MAX_STRING)

    private fun JsonElement?.float(): Float? =
        (this as? JsonPrimitive)?.floatOrNull?.takeIf { !it.isNaN() && !it.isInfinite() }

    private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.intOrNull

    private fun JsonObject.floats(k: String): Map<String, Float> =
        (get(k) as? JsonObject)?.mapNotNull { (kk, vv) ->
            vv.float()?.let { kk to it }
        }?.toMap() ?: emptyMap()

    private fun JsonObject?.strMap(k: String): Map<String, String> =
        (this?.get(k) as? JsonObject)?.mapNotNull { (kk, vv) ->
            vv.str()?.let { kk to it }
        }?.toMap() ?: emptyMap()

    private fun JsonObject?.floatMap(k: String): Map<String, Float> =
        (this?.get(k) as? JsonObject)?.mapNotNull { (kk, vv) ->
            vv.float()?.let { kk to it }
        }?.toMap() ?: emptyMap()

    /** 节点总数超限检查（防止 AI 生成超大树拖垮布局）。 */
    fun countNodes(n: Node, cap: Int = Limits.MAX_NODES): Int {
        var c = 0
        fun walk(x: Node) {
            if (++c > cap) return
            x.children.forEach(::walk)
        }
        walk(n)
        return c
    }
}
