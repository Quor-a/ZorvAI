package com.ai.assistance.quro.terminal.view

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ai.assistance.quro.terminal.TerminalEnv
import com.ai.assistance.quro.terminal.TerminalManager
import com.ai.assistance.quro.terminal.navigation.TerminalRoutes
import com.ai.assistance.quro.terminal.ui.SetupScreen
import com.ai.assistance.quro.terminal.ui.TerminalHome
import com.ai.assistance.quro.terminal.ui.MoyuScreen
import com.ai.assistance.quro.terminal.ui.EnhancedTerminalScreen
import com.ai.assistance.quro.terminal.utils.UpdateChecker
import kotlinx.coroutines.launch

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun TerminalScreen(
    env: TerminalEnv,
    onLaunchEnhancedTerminal: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val coroutineScope = rememberCoroutineScope()

    val manager = remember { TerminalManager.getInstance(context) }
    
    // 更新检查器
    val updateChecker = remember { UpdateChecker(context) }

    // 直接决定起始页，不再等待会话初始化完成（隐藏加载界面，先进终端界面）。
    // 终端会话在后台异步初始化，TerminalHome 内会在未就绪时给出轻量提示。
    val startDestination = remember {
        val sharedPreferences = context.getSharedPreferences("terminal_prefs", Context.MODE_PRIVATE)
        val isFirstLaunch = sharedPreferences.getBoolean("is_first_launch", true)
        if (env.forceShowSetup || isFirstLaunch) TerminalRoutes.SETUP_ROUTE
        else TerminalRoutes.TERMINAL_HOME_ROUTE
    }

    // 后台静默检查更新，不显示 Toast
    LaunchedEffect(Unit) {
        coroutineScope.launch {
            updateChecker.checkForUpdates(showToast = true)
        }
    }

    // 使用 NavHost 处理所有导航（无 loading 页，直接进入终端/配置）
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(TerminalRoutes.TERMINAL_HOME_ROUTE) {
            TerminalHome(
                env = env,
                onNavigateToSetup = {
                    navController.navigate(TerminalRoutes.SETUP_ROUTE)
                },
                onNavigateToMoyu = {
                    navController.navigate(TerminalRoutes.MOYU_ROUTE)
                },
                onNavigateToEnhanced = {
                    if (onLaunchEnhancedTerminal != null) {
                        onLaunchEnhancedTerminal()
                    } else {
                        navController.navigate(TerminalRoutes.ENHANCED_TERMINAL_ROUTE)
                    }
                }
            )
        }

        // 墨语言（Mo）管理页：独立运行时（qemu + x86_64 静态工具链），不经 Ubuntu。
        composable(TerminalRoutes.MOYU_ROUTE) {
            MoyuScreen(onBack = { navController.popBackStack() })
        }

        // 增强终端（vtlib 完整 VT 模拟器 + 额外按键栏）
        // 当宿主注入了 onLaunchEnhancedTerminal（app 壳层启动完整 TermuxHostActivity）时，
        // 此路由不再使用轻量实现，直接由回调拉起完整移植的 Termux 宿主。
        composable(TerminalRoutes.ENHANCED_TERMINAL_ROUTE) {
            val launch = onLaunchEnhancedTerminal
            if (launch != null) {
                LaunchedEffect(Unit) {
                    launch()
                    navController.popBackStack()
                }
            } else {
                EnhancedTerminalScreen(
                    environment = manager.terminalEnvironment,
                    onBack = { navController.popBackStack() }
                )
            }
        }
        
        composable(TerminalRoutes.SETUP_ROUTE) {
            SetupScreen(
                onBack = {
                    val sharedPreferences = context.getSharedPreferences("terminal_prefs", Context.MODE_PRIVATE)
                    sharedPreferences.edit().putBoolean("is_first_launch", false).apply()
                    navController.navigate(TerminalRoutes.TERMINAL_HOME_ROUTE) {
                        popUpTo(TerminalRoutes.SETUP_ROUTE) { inclusive = true }
                    }
                },
                onSetup = { commands ->
                    val sharedPreferences = context.getSharedPreferences("terminal_prefs", Context.MODE_PRIVATE)
                    sharedPreferences.edit().putBoolean("is_first_launch", false).apply()
                    env.onSetup(commands)
                    navController.navigate(TerminalRoutes.TERMINAL_HOME_ROUTE) {
                        popUpTo(TerminalRoutes.SETUP_ROUTE) { inclusive = true }
                    }
                }
            )
        }
    }
}