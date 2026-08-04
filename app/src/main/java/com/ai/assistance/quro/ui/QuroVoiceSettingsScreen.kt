package com.ai.assistance.quro.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ai.assistance.quro.core.QuroConversationMeta
import com.ai.assistance.quro.core.tools.QuroSttPrefs
import com.ai.assistance.quro.core.tools.QuroTtsPrefs
import com.ai.assistance.quro.core.tools.QuroTtsProviderPrefs
import com.ai.assistance.quro.core.tools.QuroTtsProviders
import com.ai.assistance.quro.core.tools.QuroTtsProviderKind
import com.ai.assistance.quro.core.tools.QuroVoiceFeaturePrefs
import com.ai.assistance.quro.core.tools.QuroCloudTtsCatalog

/**
 * 语音设置（v355 重写 · 纸感设计系统）：
 * 改用与「语音识别 (STT)」一致的 ChapterLabel + SetGroup + SetRow 排版，
 * 把原本 7 个 Tab 摊平成可滚动的章节，每个能力一个独立开关 + 折叠详情。
 *
 * 数据落在 [QuroVoiceFeaturePrefs]（持久化）。悬浮语音球总开关复用 Activity 的 [onToggleVoiceBall]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuroVoiceSettingsScreen(
    onBack: () -> Unit = {},
    onToggleVoiceBall: (Boolean) -> Unit = {},
    voiceBallEnabled: Boolean = false,
) {
    val ctx = LocalContext.current.applicationContext
    var autoRead by remember { mutableStateOf(QuroVoiceFeaturePrefs.getAutoRead(ctx)) }
    var dialogVoice by remember { mutableStateOf(QuroVoiceFeaturePrefs.getDialogVoiceButton(ctx)) }
    var sttSource by remember { mutableStateOf(QuroSttPrefs.getSource(ctx)) }
    var autoStart by remember { mutableStateOf(QuroVoiceFeaturePrefs.getAutostart(ctx)) }

    var bindSessionId by remember { mutableStateOf(QuroVoiceFeaturePrefs.getVoiceBallSessionId(ctx)) }
    var bindEnabled by remember { mutableStateOf(bindSessionId.isNotBlank()) }
    val conversations = runCatching { QuroChatViewModel.instance.conversations }.getOrNull()
        ?.collectAsState() ?: remember { mutableStateOf(emptyList<QuroConversationMeta>()) }

    var emotionEnabled by remember { mutableStateOf(QuroVoiceFeaturePrefs.getEmotionTagsEnabled(ctx)) }
    var voiceColorRouting by remember { mutableStateOf(QuroVoiceFeaturePrefs.getVoiceColorRoutingEnabled(ctx)) }

    val cs = MaterialTheme.colorScheme

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("语音设置") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") } },
            )
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "统一配置语音球、自动朗读、对话框语音按钮与情绪 / 语色能力。各开关独立控制，互不影响。",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )

            // ── 01 悬浮语音球 ──────────────────────────────────────────────
            ChapterLabel("01", "悬浮语音球")
            SetGroup {
                Column {
                    SetRow(
                        icon = Icons.Filled.GraphicEq,
                        name = "悬浮语音球",
                        sub = "任意界面挂可点击的球，STT→LLM→TTS 随时语音对话（需悬浮窗与麦克风权限）。语音能力总闸。",
                        checked = voiceBallEnabled,
                        onToggle = { onToggleVoiceBall(!voiceBallEnabled) },
                    )
                    HorizontalDivider()
                    Text(
                        "关闭后，自动朗读 / 对话框语音按钮等仍可按各自开关独立工作。",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }

            // ── 02 自动朗读 ────────────────────────────────────────────────
            ChapterLabel("02", "自动朗读")
            SetGroup {
                Column {
                    SetRow(
                        icon = Icons.Filled.VolumeUp,
                        name = "自动朗读 AI 回复",
                        sub = "收到 AI 文字回复时，自动用 TTS 朗读出来。",
                        checked = autoRead,
                        onToggle = { autoRead = !autoRead; QuroVoiceFeaturePrefs.setAutoRead(ctx, autoRead) },
                    )
                    HorizontalDivider()
                    Text(
                        "朗读使用「语音合成 (TTS)」页所选的模型与服务商，可在「语音服务 → 语音合成」中更改；语音球对话、数字人也使用同一全局设置。",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }

            // ── 03 对话框语音按钮 ──────────────────────────────────────────
            ChapterLabel("03", "对话框语音按钮")
            SetGroup {
                Column {
                    SetRow(
                        icon = Icons.Filled.Mic,
                        name = "对话框语音按钮",
                        sub = "输入框旁显示语音输入按钮，长按说话、放开结束，识别文本填入输入框。",
                        checked = dialogVoice,
                        onToggle = { dialogVoice = !dialogVoice; QuroVoiceFeaturePrefs.setDialogVoiceButton(ctx, dialogVoice) },
                    )
                    if (dialogVoice) {
                        HorizontalDivider()
                        val modelName = QuroSttPrefs.getModelName(ctx).ifBlank { QuroSttPrefs.getModelRef(ctx) }
                        val options = listOf(
                            QuroSttPrefs.SOURCE_LOCAL to "本地识别（设备原生 SpeechRecognizer）",
                            QuroSttPrefs.SOURCE_MODEL to "云端模型（已配置的 AI 转写）",
                            QuroSttPrefs.SOURCE_ONDEVICE to "端侧模型（离线 Sherpa-NCNN）",
                        )
                        options.forEachIndexed { i, (id, label) ->
                            Row(
                                Modifier.fillMaxWidth().clickable { sttSource = id; QuroSttPrefs.setSource(ctx, id) }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = sttSource == id, onClick = { sttSource = id; QuroSttPrefs.setSource(ctx, id) })
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(label, style = MaterialTheme.typography.bodyMedium)
                                    if (id != QuroSttPrefs.SOURCE_LOCAL && sttSource == id && modelName.isNotBlank()) {
                                        Text("已配置模型：$modelName", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                                    }
                                }
                            }
                            if (i != options.lastIndex) HorizontalDivider()
                        }
                        Text(
                            "对话框按钮与语音球共用全局 STT 引擎设置。云端 / 端侧引擎需在「语音识别（STT）」页先配置模型与 API Key。",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }
            }

            // ── 04 语音球绑定对话框 ────────────────────────────────────────
            ChapterLabel("04", "语音球绑定对话框")
            SetGroup {
                Column {
                    SetRow(
                        icon = Icons.Filled.Link,
                        name = "语音球绑定对话框",
                        sub = "默认把对话写进「当前正在看的对话框」；开启后可固定写进某个会话。",
                        checked = bindEnabled,
                        onToggle = {
                            val on = !bindEnabled
                            bindEnabled = on
                            if (on) {
                                if (bindSessionId.isBlank()) {
                                    bindSessionId = runCatching { QuroChatViewModel.instance.activeConversationId }.getOrNull() ?: ""
                                }
                            } else {
                                bindSessionId = ""
                            }
                            QuroVoiceFeaturePrefs.setVoiceBallSessionId(ctx, bindSessionId)
                        },
                    )
                    if (bindEnabled) {
                        HorizontalDivider()
                        Text("选择目标对话框：", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 10.dp, end = 16.dp))
                        Row(
                            Modifier.fillMaxWidth().clickable { bindSessionId = ""; QuroVoiceFeaturePrefs.setVoiceBallSessionId(ctx, "") }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = bindSessionId.isBlank(), onClick = { bindSessionId = ""; QuroVoiceFeaturePrefs.setVoiceBallSessionId(ctx, "") })
                            Spacer(Modifier.width(8.dp))
                            Text("跟随当前对话框（自动）")
                        }
                        HorizontalDivider()
                        conversations.value.forEach { meta ->
                            Row(
                                Modifier.fillMaxWidth().clickable { bindSessionId = meta.id; QuroVoiceFeaturePrefs.setVoiceBallSessionId(ctx, meta.id) }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = bindSessionId == meta.id, onClick = { bindSessionId = meta.id; QuroVoiceFeaturePrefs.setVoiceBallSessionId(ctx, meta.id) })
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(meta.title.ifBlank { "新对话" })
                                    if (meta.preview.isNotBlank()) {
                                        Text(meta.preview, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1)
                                    }
                                }
                            }
                            HorizontalDivider()
                        }
                        if (conversations.value.isEmpty()) {
                            Text("暂无其它对话框。", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                        }
                    }
                }
            }

            // ── 05 后台自启动 ──────────────────────────────────────────────
            ChapterLabel("05", "后台自启动")
            SetGroup {
                Column {
                    SetRow(
                        icon = Icons.Filled.PowerSettingsNew,
                        name = "后台自启动",
                        sub = "开机后自动拉起常住语音球（含通知栏），不自动聆听，等点按开始。",
                        checked = autoStart,
                        onToggle = { autoStart = !autoStart; QuroVoiceFeaturePrefs.setAutostart(ctx, autoStart) },
                    )
                    HorizontalDivider()
                    Text(
                        "开启后，设备开机完成会尝试启动前台语音球服务（仅挂通知栏、不主动录音）。若厂商 ROM 限制了自启动，请在系统「电池 / 自启动管理」里允许 Zorv AI。",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }

            // ── 06 LLM 情绪标签 ────────────────────────────────────────────
            ChapterLabel("06", "LLM 情绪标签")
            SetGroup {
                Column {
                    SetRow(
                        icon = Icons.Filled.AutoAwesome,
                        name = "LLM 情绪标签",
                        sub = "开启后 AI 回复自然穿插情绪 / 语气，让语音更有温度。情绪来源自动跟随「语音合成」所选服务商。",
                        checked = emotionEnabled,
                        onToggle = { emotionEnabled = !emotionEnabled; QuroVoiceFeaturePrefs.setEmotionTagsEnabled(ctx, emotionEnabled) },
                    )
                    if (emotionEnabled) {
                        HorizontalDivider()
                        val src = QuroTtsPrefs.getSource(ctx)
                        val isLocalLike = src == QuroTtsPrefs.SOURCE_LOCAL || src == QuroTtsPrefs.SOURCE_MODEL
                        val effProviderId = if (src == QuroTtsPrefs.SOURCE_MIMO) "mimo" else QuroTtsProviderPrefs.getProvider(ctx)
                        val effDef = QuroTtsProviders.byId(effProviderId)
                        val isMimo = effDef?.kind == QuroTtsProviderKind.MIMO
                        val providerLabel = effDef?.name ?: effProviderId
                        InfoBox(
                            text = if (isLocalLike) {
                                "当前语音来源为本地系统 TTS，不解析情绪标记，AI 会以自然语言（措辞 / 语气词）体现情绪，朗读无额外情感起伏。"
                            } else {
                                "当前播放服务商：$providerLabel。${if (isMimo) "✅ 支持逐段真实情感合成（中文括号标记）。" else "该服务商不解析括号标记，AI 以自然语言体现情绪（无标记式情感合成）。"}"
                            },
                        )
                        Text(
                            "更换情绪效果，请到「语音服务 → 语音合成 (TTS)」切换云服务商（如选小米 MiMo 可获得最佳情感合成）。自动朗读、数字人均使用同一全局设置，无需分别配置。",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }
            }

            // ── 07 语色路由 ────────────────────────────────────────────────
            ChapterLabel("07", "语色路由（AI 自动选角）")
            SetGroup {
                Column {
                    SetRow(
                        icon = Icons.Filled.Palette,
                        name = "语色路由（AI 自动选角）",
                        sub = "开启后 AI 按内容自动为不同段落分配不同音色（如旁白 / 角色音），并可「边播边合成」无缝衔接。",
                        checked = voiceColorRouting,
                        onToggle = { voiceColorRouting = !voiceColorRouting; QuroVoiceFeaturePrefs.setVoiceColorRoutingEnabled(ctx, voiceColorRouting) },
                    )
                    if (voiceColorRouting) {
                        HorizontalDivider()
                        val src = QuroTtsPrefs.getSource(ctx)
                        val isCloudLike = src == QuroTtsPrefs.SOURCE_CLOUD || src == QuroTtsPrefs.SOURCE_MIMO
                        InfoBox(
                            text = if (isCloudLike) "✅ 当前语音来源为云端 / MiMo，语色路由已可生效（小米 MiMo 命名预置音色支持最佳）。" else "当前语音来源为本地系统 TTS，不解析语色标记，请到「语音服务 → 语音合成 (TTS)」切换为云端服务商（推荐小米 MiMo）。",
                        )
                        Text("可选语色（AI 自由选用，可在代码 VOICE_COLOR_PALETTE 扩展）：", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 16.dp, top = 10.dp, end = 16.dp))
                        Spacer(Modifier.height(6.dp))
                        val palette = QuroCloudTtsCatalog.VOICE_COLOR_PALETTE.keys
                        FlowRow(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            palette.forEach { name ->
                                Surface(shape = MaterialTheme.shapes.small, color = cs.surfaceVariant, contentColor = cs.onSurfaceVariant) {
                                    Text(name, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "语色路由与「LLM 情绪标签」互补——情绪决定语气，语色决定音色。两者可叠加，例如 (语色:旁白)(温柔) 故事开始了……",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}
