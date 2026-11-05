package com.ai.assistance.quro.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color as AndroidColor
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.ai.assistance.quro.ui.icons.LucideIcon
import kotlinx.coroutines.delay

/**
 * 沉浸式工作区（2026-11-05 重写，取代旧的 FullscreenWorkSurface）。
 *
 * ## 要解决的真问题
 * 「小程序 / Web 应用 / 桌面模式 / 节点编辑器」要的是**画面铺满整屏**。旧实现每一层
 * 都往 `Column` 里加一行（系统状态栏 + 工具中心标题行 + 面板操作行 + 详情标题行 +
 * 工程行…），每加一层内容区就少一截，隐藏系统栏也照样铺不满 —— 用户说的
 * 「顶部加了一层又一层根本不达到全屏」。
 *
 * ## 唯一约定
 * - 内容永远是**根 Box 的 fillMaxSize**，不参与任何 Column 的高度分配；
 * - 所有控制项只允许出现在**一条悬浮控制条**里，它是覆盖在内容之上的 overlay，
 *   不占布局高度，3.2 秒后自动收起为顶部一条 44×9dp 的细把；点细把重新唤出；
 * - 系统状态栏/导航栏由 [ImmersiveSystemBars] 隐藏，离开面板自动恢复；
 * - 编辑器类面板（节点编辑器）的成排工具不常驻，收进控制条的「工具」按钮，
 *   展开时才浮出，关闭后画面依旧全屏。
 */

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * 真·全屏：边到边 + 隐藏系统栏 + 挖孔区域交给内容 + 透明系统栏底色。
 * [active] 为 false 或组合体退出时恢复默认（fitsSystemWindows=true + 显示系统栏）。
 * 必须在「当前屏是工作区」时才组合进树，退出屏即自动恢复，不污染对话页。
 */
@Suppress("DEPRECATION")
@Composable
fun ImmersiveSystemBars(active: Boolean = true) {
    val view = LocalView.current
    val context = LocalContext.current
    DisposableEffect(view, active) {
        val window = context.findActivity()?.window
        if (window != null && active) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val lp = window.attributes
                lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                window.attributes = lp
            }
            window.statusBarColor = AndroidColor.TRANSPARENT
            window.navigationBarColor = AndroidColor.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
            WindowInsetsControllerCompat(window, view).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        onDispose {
            if (window != null && active) {
                WindowCompat.setDecorFitsSystemWindows(window, true)
                WindowInsetsControllerCompat(window, view).show(WindowInsetsCompat.Type.systemBars())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val lp = window.attributes
                    lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                    window.attributes = lp
                }
            }
        }
    }
}

/**
 * 独立 Activity（桌面模式宿主）用：[ImmersiveSystemBars] 的 Activity 版。
 * 与 Compose 版共用同一套规则：边到边 + 挖孔交给内容 + 透明系统栏 + 隐藏系统栏。
 */
@Suppress("DEPRECATION")
fun Activity.enterImmersiveFullscreen() {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val lp = window.attributes
        lp.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        window.attributes = lp
    }
    window.statusBarColor = AndroidColor.TRANSPARENT
    window.navigationBarColor = AndroidColor.TRANSPARENT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        window.isNavigationBarContrastEnforced = false
    }
    WindowInsetsControllerCompat(window, window.decorView).apply {
        hide(WindowInsetsCompat.Type.systemBars())
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}

/**
 * 全屏内容 + 唯一一条自动隐藏的悬浮控制条。
 *
 * @param title 控制条上的标题（只在控制条可见时出现，不占内容高度）
 * @param onBack 返回上一层的动作；传 null 则不显示返回按钮
 * @param onClose 直接退出整个工作区的动作；传 null 则不显示关闭按钮
 * @param menuItems 次要操作（label → action），全部收进 ⋮，不额外占一行
 * @param toolsContent 编辑器类面板的成排工具，点「工具」才浮出，默认不渲染
 * @param content 内容，**必须自己 fillMaxSize**，它是根布局唯一的全屏子项
 */
@Composable
fun ImmersiveWorkSurface(
    title: String,
    onBack: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    menuItems: List<Pair<String, () -> Unit>> = emptyList(),
    toolsContent: (@Composable () -> Unit)? = null,
    chromeContainerColor: Color? = null,
    chromeContentColor: Color? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    // 桌面模式是深色墙纸，默认浅色控制条会刺眼；允许宿主覆盖配色。
    val chromeBg = chromeContainerColor ?: cs.surface.copy(alpha = 0.90f)
    val chromeFg = chromeContentColor ?: cs.onSurface
    var chromeVisible by remember { mutableStateOf(true) }
    var toolsOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    // 工具面板展开时不自动收起，避免用户正在操作工程名/按钮时控制条消失。
    LaunchedEffect(chromeVisible, toolsOpen) {
        if (chromeVisible && !toolsOpen) {
            delay(3200L)
            chromeVisible = false
        }
    }

    Box(modifier.fillMaxSize()) {
        content()

        if (!chromeVisible) {
            // 收起态：视觉上只有一条 44×4dp 细把，但点击热区是 88×20dp（好点）；
            // 它是 overlay，不遮挡内容布局、不占任何一行高度。
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 2.dp)
                    .size(width = 88.dp, height = 20.dp)
                    .clickable { chromeVisible = true; toolsOpen = false },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(width = 44.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(chromeFg.copy(alpha = 0.38f)),
                )
            }
        } else {
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(chromeBg)
                    .padding(horizontal = 4.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().height(40.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            LucideIcon("chevron_left", "返回", Modifier.size(20.dp), tint = chromeFg)
                        }
                    }
                    Text(
                        title.ifBlank { "—" },
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        color = chromeFg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (toolsContent != null) {
                        IconButton(onClick = { toolsOpen = !toolsOpen }) {
                            LucideIcon("settings", "工具", Modifier.size(18.dp), tint = chromeFg)
                        }
                    }
                    if (menuItems.isNotEmpty()) {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                LucideIcon("menu", "更多", Modifier.size(18.dp), tint = chromeFg)
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                menuItems.forEach { (label, act) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = { menuOpen = false; act() },
                                    )
                                }
                            }
                        }
                    }
                    if (onClose != null) {
                        IconButton(onClick = onClose) {
                            LucideIcon("x", "关闭", Modifier.size(18.dp), tint = chromeFg)
                        }
                    }
                    IconButton(onClick = { chromeVisible = false; toolsOpen = false }) {
                        LucideIcon("chevron_up", "收起", Modifier.size(18.dp), tint = chromeFg)
                    }
                }
                if (toolsOpen && toolsContent != null) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                        toolsContent()
                    }
                }
            }
        }
    }
}
