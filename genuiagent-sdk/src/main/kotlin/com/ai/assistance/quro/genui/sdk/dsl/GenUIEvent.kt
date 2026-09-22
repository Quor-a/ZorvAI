package com.ai.assistance.quro.genui.sdk.dsl

import kotlinx.serialization.Serializable

@Serializable
data class GenUIEvent(
    val actions: List<GenUIAction> = emptyList()
)
