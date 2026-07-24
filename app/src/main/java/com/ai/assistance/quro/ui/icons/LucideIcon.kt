package com.ai.assistance.quro.ui.icons

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.ai.assistance.quro.R

// Unified Lucide icon entry point.
// Pass the icon name (same as the svg filename under icons/) and it is
// rendered via painterResource. The drawable uses a white stroke and is
// tinted at the call site through the tint parameter.
@Composable
fun LucideIcon(
    name: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified
) {
    Icon(
        painter = painterResource(id = iconRes(name)),
        contentDescription = contentDescription,
        modifier = modifier,
        tint = tint
    )
}

private fun iconRes(name: String): Int = when (name) {
    "panel_left"       -> R.drawable.ic_panel_left
    "chevron_down"     -> R.drawable.ic_chevron_down
    "chevron_up"       -> R.drawable.ic_chevron_up
    "chevron_right"    -> R.drawable.ic_chevron_right
    "settings"         -> R.drawable.ic_settings
    "file_text"        -> R.drawable.ic_file_text
    "image"            -> R.drawable.ic_image
    "video"            -> R.drawable.ic_video
    "paperclip"        -> R.drawable.ic_paperclip
    "arrow_up"         -> R.drawable.ic_arrow_up
    "x"                -> R.drawable.ic_x
    "square_pen"       -> R.drawable.ic_square_pen
    "sparkles"         -> R.drawable.ic_sparkles
    "moon"             -> R.drawable.ic_moon
    "type"             -> R.drawable.ic_type
    "bell"             -> R.drawable.ic_bell
    "corner_down_left"  -> R.drawable.ic_corner_down_left
    "download"         -> R.drawable.ic_download
    "trash_2"         -> R.drawable.ic_trash_2
    "table"            -> R.drawable.ic_table
    "bookmark"         -> R.drawable.ic_bookmark
    "square"           -> R.drawable.ic_square
    "code"             -> R.drawable.ic_code
    "maximize"         -> R.drawable.ic_maximize
    else                -> R.drawable.ic_x
}
