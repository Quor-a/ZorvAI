package com.ai.assistance.quro.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ai.assistance.quro.ui.theme.Accent

/**
 * 语音服务（导航中心）。
 * v136 重构：从「TTS 配置页」改为「三入口导航 Hub」：
 *   1. 语音合成 (TTS) → TTS 设置 / 云服务商配置
 *   2. 语音识别 (STT) → STT 引擎选择与参数
 *   3. 语音设置 → 功能开关（悬浮语音球 / 自动朗读 / 对话框语音按钮等）
 *
 * ⚠️ 历史坑（v152–v157）：曾支持 embedded=true 内嵌进 TTS 页的 verticalScroll，
 * 结果 Scaffold 嵌套在 scroll 里拿到无限高度 → `Size(986 x 2147483647)` 崩溃；
 * 即便去掉 Scaffold，内嵌 Hub 的回调又会陷入"弹回 TTS / 再开 Hub"的死循环（进不去）。
 * v158 起彻底移除 embedded 模式：本组件**只**由 ChatScreen 的 showVoiceService 面板
 * 以独立 Scaffold 形态承载，回调正确路由到 showTts/showStt/showVoice 子面板。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuroVoiceServiceScreen(
    onBack: () -> Unit = {},
    onOpenTts: () -> Unit = {},
    onOpenStt: () -> Unit = {},
    onOpenVoiceSettings: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("语音服务") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") } },
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            VoiceServiceHubContent(
                cs = cs,
                onOpenTts = onOpenTts,
                onOpenStt = onOpenStt,
                onOpenVoiceSettings = onOpenVoiceSettings,
            )
        }
    }
}

@Composable
private fun VoiceServiceHubContent(
    cs: ColorScheme,
    onOpenTts: () -> Unit,
    onOpenStt: () -> Unit,
    onOpenVoiceSettings: () -> Unit,
) {
    Text(
        "语音合成、语音识别与语音功能的统一入口。",
        style = MaterialTheme.typography.bodySmall,
        color = cs.onSurfaceVariant,
    )

    // ── 1. 语音合成 (TTS) ──
    Card(
        Modifier.fillMaxWidth().clickable { onOpenTts() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.VolumeUp, null, tint = Accent, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("语音合成 (TTS)", style = MaterialTheme.typography.titleMedium)
                Text("云端服务商、音色、风格标签、试听", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            Icon(Icons.Filled.ArrowBack, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }

    // ── 2. 语音识别 (STT) ──
    Card(
        Modifier.fillMaxWidth().clickable { onOpenStt() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Mic, null, tint = Accent, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("语音识别 (STT)", style = MaterialTheme.typography.titleMedium)
                Text("本地 / 云端模型 / 端侧引擎", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            Icon(Icons.Filled.ArrowBack, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }

    // ── 3. 语音设置 ──
    Card(
        Modifier.fillMaxWidth().clickable { onOpenVoiceSettings() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Settings, null, tint = Accent, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("语音设置", style = MaterialTheme.typography.titleMedium)
                Text("悬浮语音球、自动朗读、对话框语音按钮", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            Icon(Icons.Filled.ArrowBack, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}
