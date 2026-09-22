package com.ai.assistance.quro.genui.aiapp

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ai.assistance.quro.genui.aiapp.ui.ChatScreen
import com.ai.assistance.quro.genui.aiapp.viewmodel.ChatViewModel

/**
 * 应用导航路由常量
 */
object Routes {
    const val CHAT = "chat"
}

/**
 * 应用导航图
 *
 * @param navController 导航控制器
 * @param startDestination 起始目标
 * @param initialPrompt 外部（ZorvAI 的 `genui_agent_open` 工具）带进来的首轮需求；
 *        非空时进入聊天页后自动替用户发出，省掉「打开再重说一遍」。
 *
 * 说明：模型配置与人格（灵魂）已改为沿用 ZorvAI 主设置（见 ZorvBrain），
 * 本应用内不再有独立配置页，故仅保留 CHAT 一个路由。
 */
@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController(),
    startDestination: String = Routes.CHAT,
    initialPrompt: String? = null
) {
    val chatViewModel: ChatViewModel = viewModel()

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Routes.CHAT) {
            // 只自动发一次（回前台时不会重发）。
            androidx.compose.runtime.LaunchedEffect(initialPrompt) {
                if (!initialPrompt.isNullOrBlank()) chatViewModel.send(initialPrompt)
            }
            ChatScreen(viewModel = chatViewModel)
        }
    }

    // 可视化询问弹窗（与 ZorvAI 主对话同一套）。
    // 挂在 NavHost 之外，保证任何页面/覆盖层之上都能弹出——
    // 「本轮用哪条渲染通道」就是靠它问的（见 ChatViewModel.askRenderChannel）。
    com.ai.assistance.quro.ui.VisualDialogs()
}
