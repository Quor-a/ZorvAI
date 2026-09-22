package com.ai.assistance.quro.genui.sdk.dsl

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class UIComponent(
    val type: String,
    val id: String? = null,
    val properties: JsonObject = JsonObject(emptyMap()),
    val style: UIStyle = UIStyle(),
    val children: List<UIComponent> = emptyList(),
    val events: Map<String, GenUIEvent> = emptyMap(),
    val visible: Boolean = true,
    val animation: UIAnimation? = null,
    val dataBinding: Map<String, String> = emptyMap()
)
