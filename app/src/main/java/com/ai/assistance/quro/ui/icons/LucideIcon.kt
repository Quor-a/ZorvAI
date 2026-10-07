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
    "blocks"           -> R.drawable.ic_blocks
    "chevron_left"     -> R.drawable.ic_chevron_left
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
    "bot"             -> R.drawable.ic_bot
    "table"            -> R.drawable.ic_table
    "bookmark"         -> R.drawable.ic_bookmark
    "square"           -> R.drawable.ic_square
    "code"             -> R.drawable.ic_code
    "maximize"         -> R.drawable.ic_maximize

    // ── 工具调用族专用图标（qic_* 前缀，本项目自绘）──
    // 🔴 这些是 drawable 资源名，**必须与 res/drawable 下的文件名逐一对应**。
    // 旧实现里 toolCategory 用的 "terminal" / "globe" / "folder-open" 等名字
    // 在本仓根本没有对应 drawable，全部落到 else -> ic_x（显示一个 X），
    // 所以工具图标此前一律是错的。
    "qic_code_run"     -> R.drawable.qic_code_run
    "qic_file_write"   -> R.drawable.qic_file_write
    "qic_file_read"    -> R.drawable.qic_file_read
    "qic_web"          -> R.drawable.qic_web
    "qic_terminal"     -> R.drawable.qic_terminal
    "qic_device"       -> R.drawable.qic_device
    "qic_system"       -> R.drawable.qic_system
    "qic_doc"          -> R.drawable.qic_doc
    "qic_media"        -> R.drawable.qic_media
    "qic_memory"       -> R.drawable.qic_memory
    "qic_ui"           -> R.drawable.qic_ui
    "qic_search"       -> R.drawable.qic_search
    "qic_other"        -> R.drawable.qic_other

    // ── 工具状态图标（本项目自绘，替代不存在的 lucide check/info/alert-triangle）──
    "qic_ok"           -> R.drawable.qic_ok
    "qic_fail"         -> R.drawable.qic_fail
    "qic_warn"         -> R.drawable.qic_warn
    "qic_info"         -> R.drawable.qic_info
    "qic_running"      -> R.drawable.qic_running

    else                -> R.drawable.ic_x
}
