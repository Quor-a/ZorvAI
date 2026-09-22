package com.ai.assistance.quro.genui.sdk.dsl

import kotlinx.serialization.Serializable

@Serializable
data class UIAnimation(
    val type: String = "fade",
    val durationMs: Int = 300,
    val delayMs: Int = 0,
    val easing: String? = null,
    val repeat: String? = null
)
