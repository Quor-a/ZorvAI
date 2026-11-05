package com.ai.assistance.quro.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.zIndex
import com.ai.assistance.quro.core.connector.QuroConnectorLauncher

/**
 * 外部连接 / 应用中心 / 后台进程 / 宠物设置：四个全屏覆盖层的统一挂载点。
 *
 * 独立成文件的原因：此前内联在 ChatScreen 的 ChatOverlays 里，四处设置页叠加后
 * 编译产物方法体超过 JVM 单方法 64KB 上限（Method too large），必须物理拆分。
 */
@Composable
fun QuroUtilityOverlays(
    showExternalAccess: Boolean,
    onCloseExternalAccess: () -> Unit,
    showAppCenter: Boolean,
    onCloseAppCenter: () -> Unit,
    showBgProcess: Boolean,
    onCloseBgProcess: () -> Unit,
    showPetSettings: Boolean,
    onClosePetSettings: () -> Unit,
) {
    val cs = androidx.compose.material3.MaterialTheme.colorScheme
    val ctx = LocalContext.current

    // 外部连接：本机 OpenAI 兼容端点 + API 密钥管理（模型服务组，与 MCP 无关）
    if (showExternalAccess) {
        BackHandler { onCloseExternalAccess() }
        Box(Modifier.fillMaxSize().zIndex(100f).background(cs.background)) {
            QuroExternalAccessScreen(onBack = onCloseExternalAccess)
        }
    }

    // 宠物完整设置页：形象切换 / 互动动作 / 行为 / 宠物大小 / 动作间隔。
    // 设置即时落盘（PetPrefs），桌宠服务下次拉起时生效。
    if (showPetSettings) {
        BackHandler { onClosePetSettings() }
        var petSettingsState by remember {
            mutableStateOf(com.ai.assistance.quro.genui.aiapp.ui.PetPrefs.load(ctx))
        }
        Box(Modifier.fillMaxSize().zIndex(101f).background(cs.background)) {
            com.ai.assistance.quro.genui.aiapp.ui.PetSettingsScreen(
                settings = petSettingsState,
                onSettingsChange = {
                    com.ai.assistance.quro.genui.aiapp.ui.PetPrefs.save(ctx, it)
                    petSettingsState = it
                },
                onBack = onClosePetSettings,
            )
        }
    }

    // 应用中心：全屏覆盖层（服务目录：已连接 + 分类 + 搜索）
    if (showAppCenter) {
        BackHandler { onCloseAppCenter() }
        Box(Modifier.fillMaxSize().zIndex(100f).background(cs.background)) {
            QuroAppCenterScreen(onClose = onCloseAppCenter)
        }
    }

    // 连接器：全屏覆盖层（官方 MCP 服务商店：分类浏览 + 一键授权连接）。
    // 🔴 状态内部自管理：ChatOverlays 已逼近 JVM 64KB 方法上限（Method too large
    //    两次被它顶爆），任何加给 ChatScreen 的状态/命令分支都可能压垮它。
    //    连接器的打开走全局事件桥 QuroConnectorLauncher（AI 命令 / 设置入口直接 open）。
    var showConnector by remember { mutableStateOf(false) }
    val connectorEvent by QuroConnectorLauncher.event.collectAsState()
    LaunchedEffect(connectorEvent) {
        if (connectorEvent != null) {
            showConnector = true
            QuroConnectorLauncher.consume()
        }
    }
    if (showConnector) {
        BackHandler { showConnector = false }
        Box(Modifier.fillMaxSize().zIndex(100f).background(cs.background)) {
            QuroConnectorScreen(
                onClose = { showConnector = false },
                onOpenMcpSettings = { showConnector = false },
            )
        }
    }

    // 后台进程管理页：全屏覆盖层（常驻服务一览 + 启停）
    if (showBgProcess) {
        BackHandler { onCloseBgProcess() }
        Box(Modifier.fillMaxSize().zIndex(100f).background(cs.background)) {
            QuroBackgroundProcessScreen(onClose = onCloseBgProcess)
        }
    }
}
