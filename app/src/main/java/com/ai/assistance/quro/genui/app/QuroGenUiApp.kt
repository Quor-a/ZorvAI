package com.ai.assistance.quro.genui.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.ai.assistance.quro.genui.app.store.GenStore
import com.ai.assistance.quro.genui.app.ui.settings.MemoryScreen
import com.ai.assistance.quro.genui.app.ui.settings.ModelConfigScreen
import com.ai.assistance.quro.genui.app.ui.settings.PermissionScreen
import com.ai.assistance.quro.genui.app.ui.settings.SettingsItem
import com.ai.assistance.quro.genui.app.ui.settings.SettingsScreen
import com.ai.assistance.quro.genui.app.ui.settings.SoulScreen
import com.ai.assistance.quro.genui.app.ui.shell.GenScaffold
import com.ai.assistance.quro.genui.app.ui.shell.NavTarget
import com.ai.assistance.quro.genui.app.ui.theme.GenTheme

/**
 * QuroAI 的「GenUI 模式」完整入口（对应上游 MainActivity）。
 *
 * 这是与文本对话框【平级】的全新全屏界面生成器：点切换按钮进入，整个屏幕即 AI 的回复。
 * 自身含：灵魂 / 记忆库 / 工具授权 / 模型服务 四个子系统（单字直达），
 * 以及画布状态阶段机、思考时间线、界面栈、历史浏览、三种原生叠加层（XML/Compose/Canvas）。
 *
 * 不再依附于文本对话框（之前把它塞进 ChatScreen 的 generateGenUi 是错误做法）。
 */
@Composable
fun QuroGenUiApp(
    dark: Boolean = false,
    onPushToChat: (html: String, title: String) -> Unit,
    onExitToChat: () -> Unit,
    onTextReply: (text: String) -> Unit,
) {
    val ctx = LocalContext.current
    val store = remember { GenStore(ctx) }
    // null = 主屏；其他 = 设置类子页（叠在主屏之上，返回时主屏 WebView 不重建）
    var screen by remember { mutableStateOf<NavTarget?>(null) }
    // 设置页改了模型/灵魂/权限后 +1，让主屏供应商缓存失效并重读盘
    var configVersion by remember { mutableStateOf(0) }

    // 手机系统权限：通知（Android 13+ 启动即问一次）
    val notifPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // 系统返回键拦截：GenUI 是根屏，默认返回会 finish Activity 直接退回手机桌面。
    // 这里改为「设置子页开着 → 先关子页；否则退回 ZorvAI 文本对话框」。
    // 注意：GenScaffold 内部还有一层 BackHandler（仅当界面栈 size>1 时启用，用于翻回上一屏），
    // 它注册在更内层、优先级更高，所以多页时返回键先弹栈，到根屏才落到本处理。
    BackHandler {
        if (screen != null) {
            if (screen == NavTarget.Settings) { configVersion++; screen = null }
            else screen = NavTarget.Settings
        } else {
            onExitToChat()
        }
    }

    Surface(Modifier.fillMaxSize(), color = GenTheme.Screen) {
        Box(Modifier.fillMaxSize()) {
            // 主屏始终保留在组合树中，避免设置页返回时画布重建丢失已生成界面
            GenScaffold(
                store = store,
                configVersion = configVersion,
                dark = dark,
                onNavigate = { screen = it },
                onPushToChat = onPushToChat,
                onExitToChat = onExitToChat,
                onTextReply = onTextReply,
            )

            val target = screen
            if (target != null) {
                val close = {
                    configVersion++
                    screen = null
                }
                Box(Modifier.fillMaxSize().background(GenTheme.Screen)) {
                    when (target) {
                        NavTarget.Soul -> SoulScreen(store = store, onBack = { screen = NavTarget.Settings })
                        NavTarget.Memory -> MemoryScreen(store = store, onBack = { screen = NavTarget.Settings })
                        NavTarget.Perms -> PermissionScreen(store = store, onBack = { screen = NavTarget.Settings })
                        NavTarget.ModelConfig -> ModelConfigScreen(store = store, onBack = { screen = NavTarget.Settings })
                        NavTarget.Settings -> SettingsScreen(
                            store = store,
                            onBack = close,
                            onItem = { item ->
                                screen = when (item) {
                                    SettingsItem.Soul -> NavTarget.Soul
                                    SettingsItem.Memory -> NavTarget.Memory
                                    SettingsItem.Perms -> NavTarget.Perms
                                    SettingsItem.ModelConfig -> NavTarget.ModelConfig
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
