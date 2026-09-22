package com.ai.assistance.quro.genui.sdk.dsl

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

// === Action Builders ===

fun navigate(route: String, params: Map<String, String> = emptyMap()): GenUIAction.Navigate {
    return GenUIAction.Navigate(route, params)
}

fun updateState(key: String, value: Any): GenUIAction.UpdateState {
    return GenUIAction.UpdateState(key, toJsonElement(value))
}

fun toggleState(key: String): GenUIAction.ToggleState {
    return GenUIAction.ToggleState(key)
}

fun showDialog(dialog: UIComponent): GenUIAction.ShowDialog {
    return GenUIAction.ShowDialog(dialog)
}

val dismissDialog: GenUIAction.DismissDialog
    get() = GenUIAction.DismissDialog

fun emit(name: String, payload: Any = ""): GenUIAction.Emit {
    return GenUIAction.Emit(name, toJsonElement(payload))
}

fun openUrl(url: String): GenUIAction.OpenUrl {
    return GenUIAction.OpenUrl(url)
}

fun copyToClipboard(text: String): GenUIAction.CopyToClipboard {
    return GenUIAction.CopyToClipboard(text)
}

fun haptic(type: String = "click"): GenUIAction.Haptic {
    return GenUIAction.Haptic(type)
}

fun callApi(
    url: String,
    method: String = "GET",
    onSuccess: List<GenUIAction> = emptyList()
): GenUIAction.CallApi {
    return GenUIAction.CallApi(
        url = url,
        method = method,
        body = null,
        headers = emptyMap(),
        onSuccess = onSuccess,
        onError = emptyList()
    )
}

fun customAction(handlerId: String, payload: Any = ""): GenUIAction.Custom {
    return GenUIAction.Custom(handlerId, toJsonElement(payload))
}

fun logAction(message: String): GenUIAction.Log {
    return GenUIAction.Log(message)
}

internal fun toJsonElement(obj: Any?): JsonElement {
    return when (obj) {
        is JsonElement -> obj
        is String -> JsonPrimitive(obj)
        is Number -> JsonPrimitive(obj)
        is Boolean -> JsonPrimitive(obj)
        null -> JsonPrimitive("")
        else -> JsonPrimitive(obj.toString())
    }
}

// === Screen & Component Builders ===

fun genUIScreen(
    id: String? = null,
    title: String? = null,
    state: Map<String, Any> = emptyMap(),
    block: ComponentBuilder.() -> Unit
): UISpec {
    val builder = ComponentBuilder("column")
    builder.block()
    val root = builder.build()
    val stateMap = state.mapValues { toJsonElement(it.value) }
    return UISpec(id = id, title = title, root = root, state = stateMap)
}

fun component(type: String, block: ComponentBuilder.() -> Unit = {}): UIComponent {
    val builder = ComponentBuilder(type)
    builder.block()
    return builder.build()
}

fun column(block: ComponentBuilder.() -> Unit = {}): UIComponent =
    component("column", block)

fun row(block: ComponentBuilder.() -> Unit = {}): UIComponent =
    component("row", block)

fun box(block: ComponentBuilder.() -> Unit = {}): UIComponent =
    component("box", block)

fun spacer(block: ComponentBuilder.() -> Unit = {}): UIComponent =
    component("spacer", block)

fun scroll(block: ComponentBuilder.() -> Unit = {}): UIComponent =
    component("scroll", block)

fun card(block: ComponentBuilder.() -> Unit = {}): UIComponent =
    component("card", block)

fun list(block: ComponentBuilder.() -> Unit = {}): UIComponent =
    component("list", block)

fun textField(block: ComponentBuilder.() -> Unit = {}): UIComponent =
    component("text_field", block)

fun dialog(block: ComponentBuilder.() -> Unit = {}): UIComponent =
    component("dialog", block)

fun text(text: String, block: ComponentBuilder.() -> Unit = {}): UIComponent {
    val builder = ComponentBuilder("text")
    builder.prop("text", text)
    builder.block()
    return builder.build()
}

fun button(label: String, block: ComponentBuilder.() -> Unit = {}): UIComponent {
    val builder = ComponentBuilder("button")
    builder.prop("text", label)
    builder.block()
    return builder.build()
}

fun image(src: String, block: ComponentBuilder.() -> Unit = {}): UIComponent {
    val builder = ComponentBuilder("image")
    builder.prop("src", src)
    builder.block()
    return builder.build()
}

// === Helper Functions ===

fun toDimension(obj: Any?): Dimension? {
    if (obj == null) return null
    if (obj is Dimension) return obj
    if (obj is Number) return Dimension.Fixed(obj.toFloat())
    if (obj is String) {
        return when (obj) {
            "match", "fill" -> Dimension.Match
            "wrap" -> Dimension.Wrap
            else -> {
                if (obj.endsWith("%")) {
                    val value = obj.dropLast(1).toFloatOrNull() ?: 0f
                    Dimension.Weight(value / 100f)
                } else {
                    Dimension.Wrap
                }
            }
        }
    }
    return null
}

fun toEdgeInsets(obj: Any?): EdgeInsets {
    if (obj == null) return EdgeInsets(0f, 0f, 0f, 0f)
    if (obj is Number) return EdgeInsets.all(obj.toFloat())
    if (obj is EdgeInsets) return obj
    if (obj is Map<*, *>) {
        val map = obj
        val all = (map["all"] as? Number)?.toFloat()
        if (all != null) {
            return EdgeInsets.all(all)
        }
        val horizontal = (map["horizontal"] as? Number)?.toFloat() ?: 0f
        val vertical = (map["vertical"] as? Number)?.toFloat() ?: 0f
        val start = (map["start"] as? Number)?.toFloat() ?: horizontal
        val top = (map["top"] as? Number)?.toFloat() ?: vertical
        val end = (map["end"] as? Number)?.toFloat() ?: horizontal
        val bottom = (map["bottom"] as? Number)?.toFloat() ?: vertical
        return EdgeInsets(start, top, end, bottom)
    }
    return EdgeInsets(0f, 0f, 0f, 0f)
}
