package com.ai.assistance.quro.genui.sdk.dsl

@GenUIDslMarker
class StyleBuilder {

    var width: Any? = null
    var height: Any? = null
    var padding: Any? = null
    var margin: Any? = null
    var backgroundColor: String? = null
    var borderColor: String? = null
    var borderWidth: Float = 0f
    var cornerRadius: Float = 0f
    var elevation: Float = 0f
    var opacity: Float = 1f
    var clip: Boolean = false
    var alignment: String? = null
    var crossAlignment: String? = null
    var arrangement: String? = null
    var gravity: String? = null
    var textColor: String? = null
    var textSize: Float? = null
    var fontWeight: String? = null
    var fontFamily: String? = null
    var textAlign: String? = null
    var letterSpacing: Float? = null
    var lineHeight: Float? = null
    var maxLines: Int? = null
    var overflow: String? = null
    var shape: String? = null

    fun build(): UIStyle {
        return UIStyle(
            width = toDimension(width),
            height = toDimension(height),
            padding = toEdgeInsets(padding),
            margin = toEdgeInsets(margin),
            backgroundColor = backgroundColor,
            borderColor = borderColor,
            borderWidth = borderWidth,
            cornerRadius = cornerRadius,
            elevation = elevation,
            opacity = opacity,
            clip = clip,
            alignment = alignment,
            crossAlignment = crossAlignment,
            arrangement = arrangement,
            gravity = gravity,
            textColor = textColor,
            textSize = textSize,
            fontWeight = fontWeight,
            fontFamily = fontFamily,
            textAlign = textAlign,
            letterSpacing = letterSpacing,
            lineHeight = lineHeight,
            maxLines = maxLines,
            overflow = overflow,
            shape = shape
        )
    }
}
