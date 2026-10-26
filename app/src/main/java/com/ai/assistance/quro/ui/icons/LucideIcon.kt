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

/**
 * 图标名归一化：`layout-dashboard` / `Layout-Dashboard` / ` layout_dashboard ` 一律变成
 * `layout_dashboard`。
 *
 * 为什么必须有这一层：本函数是 `when` 的**精确字符串匹配**，而调用点散在 app / terminal-core /
 * dshmobile 三个模块里，写法从不统一（lucide 官网用连字符，Kotlin 里有人写连字符、有人写下划线）。
 * 任何一个拼错的名字都会一路掉到 `else`，而 `else` 此前回的是 **`ic_x`** —— 于是界面上出现一堆
 * 语义完全无关的「×」（2026-10-26 用户截图：「可视化小卡片 / 动态UI组件 / AIP文档排版」三行全是 ×，
 * GenUI Agent 那行也是 ×）。名字归一化让连字符/下划线/大小写不再能造成这种「静默降级」。
 */
private fun normalizeIconName(name: String): String =
    name.trim().lowercase().replace('-', '_').replace(' ', '_')

private fun iconRes(name: String): Int = when (normalizeIconName(name)) {
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

    // ── 语义图标：补齐此前**只被调用、从未被映射**的名字（全部曾静默渲染成「×」）──
    // 判据来自全仓扫描（app / terminal-core / dshmobile 的每一处 LucideIcon("…") 调用名
    // 与映射表差分）：credit_card / layout_dashboard / folder / link / menu 五个名字没有
    // 任何对应分支。前三者正是对话框「工具菜单」里可视化小卡片 / 动态UI组件 / AIP文档排版
    // 三行的图标（用户 2026-10-26 截图：整列 ×）。
    "credit_card"      -> R.drawable.qic_card   // 可视化小卡片：卡面 + 折线（单块数据）
    "layout_dashboard" -> R.drawable.qic_layout // 动态UI组件：分区面板 + 旋钮（可交互）
    "folder"           -> R.drawable.qic_folder
    "link"             -> R.drawable.qic_link
    "menu"             -> R.drawable.qic_menu
    // AIP文档排版沿用 ic_file_text（本就有，此前只是调用名写成了 "file-text" 连字符）。

    // 🔴 兜底**绝不能再是 `ic_x`**：`x` 是「关闭/删除」的语义，任何一个拼错的名字都会让
    // 界面长出一个「×」，用户看到的是「这里有个叉」，而不是「图标漏配了」。
    // 现在兜到 `qic_other`（中性「其它」图标），既不会误导用户，也便于一眼看出是漏配。
    else               -> R.drawable.qic_other
}
