package com.ai.assistance.quro.genui.sdk.dsl

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@GenUIDslMarker
class ComponentBuilder(val type: String) {

    var id: String? = null
    var visible: Boolean = true
    val style: StyleBuilder = StyleBuilder()
    var animation: UIAnimation? = null

    private val props: MutableMap<String, JsonElement> = LinkedHashMap()
    private val childList: MutableList<UIComponent> = ArrayList()
    private val eventMap: MutableMap<String, GenUIEvent> = LinkedHashMap()

    fun prop(key: String, value: Any?) {
        if (value != null) {
            props[key] = toJsonElement(value)
        }
    }

    fun props(vararg pairs: Pair<String, Any?>) {
        for ((key, value) in pairs) {
            prop(key, value)
        }
    }

    fun child(component: UIComponent) {
        childList.add(component)
    }

    operator fun UIComponent.unaryPlus() {
        child(this)
    }

    fun onClick(vararg actions: GenUIAction) {
        eventMap["onClick"] = GenUIEvent(actions.toList())
    }

    fun onLongPress(vararg actions: GenUIAction) {
        eventMap["onLongPress"] = GenUIEvent(actions.toList())
    }

    fun onDismiss(vararg actions: GenUIAction) {
        eventMap["onDismiss"] = GenUIEvent(actions.toList())
    }

    fun onChange(vararg actions: GenUIAction) {
        eventMap["onChange"] = GenUIEvent(actions.toList())
    }

    fun event(name: String, vararg actions: GenUIAction) {
        eventMap[name] = GenUIEvent(actions.toList())
    }

    fun build(): UIComponent {
        val propertiesJsonObject = buildJsonObject {
            for ((key, value) in props) {
                put(key, value)
            }
        }
        return UIComponent(
            type = type,
            id = id,
            properties = propertiesJsonObject,
            style = style.build(),
            children = childList.toList(),
            events = eventMap.toMap(),
            visible = visible,
            animation = animation
        )
    }
}
