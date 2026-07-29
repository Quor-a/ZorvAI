package com.ai.assistance.quro.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.ai.assistance.quro.core.QuroCrashReporter
import com.ai.assistance.quro.ui.theme.QuroTheme

/**
 * Quro 主壳（原创）：全屏聊天界面（MoWen UI 风格），设置以内部覆盖层展示。
 * 同时挂载崩溃自报告：把设备上才看得到的真实异常就地弹窗。
 */
@Composable
fun QuroApp(
    voiceBallEnabled: Boolean,
    onToggleVoiceBall: (Boolean) -> Unit,
) {
    val ctx = LocalContext.current.applicationContext
    QuroCrashReporter.crashDir = ctx.filesDir
    val chatVm = remember { QuroChatViewModel(ctx) }
    val modelVm = remember { QuroModelConfigViewModel(ctx) }
    val personaVm = remember { QuroPersonaViewModel(ctx) }

    val crash by QuroCrashReporter.lastCrash.collectAsState()
    val clipboard = LocalClipboardManager.current

    var pendingCrash by remember { mutableStateOf(QuroCrashReporter.loadPending()) }
    var skipCrashViewer by remember { mutableStateOf(false) }
    if (pendingCrash != null && !skipCrashViewer) {
        CrashViewerScreen(
            text = pendingCrash ?: "",
            onCopy = {
                clipboard.setText(AnnotatedString(pendingCrash ?: ""))
                QuroCrashReporter.clearPending()
                pendingCrash = null
            },
            onContinue = {
                QuroCrashReporter.clearPending()
                pendingCrash = null
                skipCrashViewer = true
            },
        )
        return
    }

    var darkMode by remember { mutableStateOf(chatVm.isDarkMode()) }
    QuroTheme(darkOverride = darkMode) {
        Box(Modifier.fillMaxSize()) {
            ChatScreen(
                chatVm,
                modelVm,
                personaVm,
                voiceBallEnabled = voiceBallEnabled,
                onToggleVoiceBall = onToggleVoiceBall,
                darkMode = darkMode,
                onToggleDark = {
                    darkMode = !darkMode
                    chatVm.setDarkMode(darkMode)
                },
            )
        }

        // 崩溃自报告弹窗
        if (crash != null) {
            AlertDialog(
                onDismissRequest = { QuroCrashReporter.clear() },
                confirmButton = {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(crash ?: ""))
                        QuroCrashReporter.clear()
                    }) { Text("复制并关闭") }
                },
                dismissButton = {
                    TextButton(onClick = { QuroCrashReporter.clear() }) { Text("仅关闭") }
                },
                title = { Text("⚠️ 运行出错（已捕获）") },
                text = {
                    Box(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                        Text(crash ?: "", style = MaterialTheme.typography.bodySmall)
                    }
                },
            )
        }
    }
}

/**
 * 启动期崩溃回放屏（原创，极简、零依赖）：若上一次运行发生未捕获崩溃，
 * 下次启动不再无声闪退，而是展示捕获到的堆栈，供用户「复制并关闭」发回定位。
 *
 * 关键修复：本屏宿主（ComposeView 处于带 weight 的 LinearLayout 中）会以
 * 「无限高度约束」测量根 Composable；而 `verticalScroll` 在无限高度下会抛
 * IllegalStateException（即用户此前回传的那条崩溃）。因此不再用
 * `Column(fillMaxSize().verticalScroll())`，改为 Box 吸收填充约束、内层 Column
 * 用「屏幕高度这一有限值」显式定高后再 verticalScroll——scrollable 拿到的是
 * 有限约束，既不会崩，也保留可滚动查看长堆栈的能力。
 */
@Composable
private fun CrashViewerScreen(
    text: String,
    onCopy: () -> Unit,
    onContinue: () -> Unit,
) {
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    MaterialTheme {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(screenHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
            ) {
                Text("上次运行崩溃（已捕获）", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "请把下方内容复制发给开发者，以便定位「设置闪退 / 无法对话」的真凶。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text(text, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(20.dp))
                Row {
                    Button(onClick = onCopy) { Text("复制并关闭") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onContinue) { Text("清除并继续") }
                }
            }
        }
    }
}
