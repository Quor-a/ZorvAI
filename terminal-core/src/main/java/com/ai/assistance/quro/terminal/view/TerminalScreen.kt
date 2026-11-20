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
import com.ai.assistance.quro.terminal.ui.TerminalBootScreen
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

    // 统一从启动引导页进入（对齐 DeepSeek Harness「打开即自动启动」）。
    // 引导页自动检测 Ubuntu rootfs：已就绪秒进终端；首次/解压中自动完成初始化并显示进度；
    // 只有 ASSET_MISSING（没有内置包）才落到环境配置页。forceShowSetup 仍保留为强制入口。
    val startDestination = remember {
        if (env.forceShowSetup) TerminalRoutes.SETUP_ROUTE else TerminalRoutes.BOOT_ROUTE
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
        // 启动引导页：首次进入自动检测/初始化环境，就绪后自动进终端主界面。
        composable(TerminalRoutes.BOOT_ROUTE) {
            TerminalBootScreen(
                manager = manager,
                onReady = {
                    val sharedPreferences = context.getSharedPreferences("terminal_prefs", Context.MODE_PRIVATE)
                    sharedPreferences.edit().putBoolean("is_first_launch", false).apply()
                    navController.navigate(TerminalRoutes.TERMINAL_HOME_ROUTE) {
                        popUpTo(TerminalRoutes.BOOT_ROUTE) { inclusive = true }
                    }
                },
                onOpenSetup = {
                    navController.navigate(TerminalRoutes.SETUP_ROUTE) {
                        popUpTo(TerminalRoutes.BOOT_ROUTE) { inclusive = true }
                    }
                },
            )
        }

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

        // Termux（vtlib 完整 VT 模拟器 + 额外按键栏）
        // 当宿主注入了 onLaunchEnhancedTerminal（app 壳层启动完整移植的 TermuxActivity）时，
        // 此路由不再使用轻量实现，直接由回调拉起完整移植的 Termux 官方宿主。
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