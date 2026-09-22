package com.ai.assistance.quro.genui.sdk.dsl

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class UISpec(
    val id: String? = null,
    val title: String? = null,
    val root: UIComponent,
    val state: Map<String, JsonElement> = emptyMap(),
    val theme: String? = null,
    val schemaVersion: String? = null
)
