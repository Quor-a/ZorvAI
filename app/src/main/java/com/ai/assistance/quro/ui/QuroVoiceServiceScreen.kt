package com.ai.assistance.quro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.ui.theme.Accent
import com.ai.assistance.quro.ui.theme.AccentSoft
import com.ai.assistance.quro.ui.theme.Line
import com.ai.assistance.quro.ui.theme.Muted

/**
 * 语音服务（导航中心）— v338 重写：语音能力中心视觉。
 *
 * 顶部 hero 内嵌实时语音球预览，点明「语音能力总入口」；
 * 下方以「能力分层」大号功能磁贴（TTS / STT / 语音设置）呈现，与全 App 视觉一致。
 *
 * ⚠️ 历史坑（v152–v157）：曾支持 embedded=true 内嵌进 TTS 页的 verticalScroll，
 * 结果 Scaffold 嵌套在 scroll 里拿到无限高度 → 崩溃；即便去掉 Scaffold，内嵌 Hub 的回调又会陷入
 * "弹回 TTS / 再开 Hub"的死循环（进不去）。本组件**只**由 ChatScreen 的 showVoiceService 面板
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
            Modifier
                .padding(padding)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            VoiceServiceHero()
            ChapterLabel("01", "能力分层")
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CapabilityTile(
                    icon = Icons.Filled.VolumeUp,
                    title = "语音合成 (TTS)",
                    sub = "云端服务商 · 音色 · 风格标签 · 试听",
                    onClick = onOpenTts,
                )
                CapabilityTile(
                    icon = Icons.Filled.Mic,
                    title = "语音识别 (STT)",
                    sub = "本地 / 云端模型 / 端侧引擎",
                    onClick = onOpenStt,
                )
                CapabilityTile(
                    icon = Icons.Filled.Settings,
                    title = "语音设置",
                    sub = "悬浮语音球 · 自动朗读 · 对话框语音按钮",
                    onClick = onOpenVoiceSettings,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** 顶部 hero：内嵌实时语音球预览 + 标题。语音球本身持续自转，呈现「能力入口」氛围。 */
@Composable
private fun VoiceServiceHero() {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.horizontalGradient(listOf(Accent.copy(alpha = 0.18f), Accent.copy(alpha = 0.05f))))
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QuroVoiceBall(listening = false, speaking = false, paused = false, status = "待命")
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text("语音能力中心", style = MaterialTheme.typography.titleLarge, color = cs.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(
                "语音合成 · 语音识别 · 语音设置，统一入口。",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
        }
    }
}

/** 能力分层大号功能磁贴：AccentSoft 图标盒 + 标题/副标 + 箭头。 */
@Composable
private fun CapabilityTile(
    icon: ImageVector,
    title: String,
    sub: String,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(cs.surface)
            .border(1.dp, Line, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(AccentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(24.dp), tint = Accent)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = cs.onSurface, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(sub, fontSize = 12.sp, color = Muted)
        }
        Icon(Icons.Filled.ChevronRight, null, Modifier.size(18.dp), tint = Muted)
    }
}
