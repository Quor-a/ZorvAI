package com.ai.assistance.quro.genui.sdk.dsl

import kotlinx.serialization.Serializable

@Serializable(with = DimensionSerializer::class)
sealed class Dimension {

    data class Fixed(val dp: Float) : Dimension() {
        override fun toString(): String = "${dp}dp"
    }

    data object Match : Dimension() {
        override fun toString(): String = "match"
    }

    data object Wrap : Dimension() {
        override fun toString(): String = "wrap"
    }

    data class Weight(val fraction: Float) : Dimension() {
        override fun toString(): String = "${(fraction * 100).toInt()}%"
    }
}
