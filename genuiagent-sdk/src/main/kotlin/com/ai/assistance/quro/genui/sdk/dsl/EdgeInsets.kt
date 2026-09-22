package com.ai.assistance.quro.genui.sdk.dsl

import kotlinx.serialization.Serializable

@Serializable(with = EdgeInsetsSerializer::class)
data class EdgeInsets(
    val start: Float = 0f,
    val top: Float = 0f,
    val end: Float = 0f,
    val bottom: Float = 0f
) {
    val horizontal: Float
        get() = start + end

    val vertical: Float
        get() = top + bottom

    companion object {
        fun all(value: Float): EdgeInsets = EdgeInsets(value, value, value, value)
    }
}
