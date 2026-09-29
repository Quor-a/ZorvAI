package com.ai.assistance.quro.ui
import androidx.compose.ui.res.stringResource
import com.ai.assistance.quro.R
import com.ai.assistance.quro.util.qstr

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
                title = { Text(stringResource(R.string.qk_00235)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.qk_00143)) } },
            )
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.qk_03065),
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )

            // ── 01 悬浮语音球 ──────────────────────────────────────────────
            ChapterLabel("01", stringResource(R.string.qk_03066))
            SetGroup {
                Column {
                    SetRow(
                        icon = Icons.Filled.GraphicEq,
                        name = stringResource(R.string.qk_03066),
                        sub = stringResource(R.string.qk_03067),
                        checked = voiceBallEnabled,
                        onToggle = { onToggleVoiceBall(!voiceBallEnabled) },
                    )
                    HorizontalDivider()
                    Text(stringResource(R.string.qk_03068),
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }

            // ── 02 自动朗读 ────────────────────────────────────────────────
            ChapterLabel("02", stringResource(R.string.qk_03069))
            SetGroup {
                Column {
                    SetRow(
                        icon = Icons.Filled.VolumeUp,
                        name = stringResource(R.string.qk_03070),
                        sub = stringResource(R.string.qk_03071),
                        checked = autoRead,
                        onToggle = { autoRead = !autoRead; QuroVoiceFeaturePrefs.setAutoRead(ctx, autoRead) },
                    )
                    HorizontalDivider()
                    Text(stringResource(R.string.qk_03072),
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }

            // ── 03 对话框语音按钮 ──────────────────────────────────────────
            ChapterLabel("03", stringResource(R.string.qk_03073))
            SetGroup {
                Column {
                    SetRow(
                        icon = Icons.Filled.Mic,
                        name = stringResource(R.string.qk_03073),
                        sub = stringResource(R.string.qk_03074),
                        checked = dialogVoice,
                        onToggle = { dialogVoice = !dialogVoice; QuroVoiceFeaturePrefs.setDialogVoiceButton(ctx, dialogVoice) },
                    )
                    if (dialogVoice) {
                        HorizontalDivider()
                        val modelName = QuroSttPrefs.getModelName(ctx).ifBlank { QuroSttPrefs.getModelRef(ctx) }
                        val options = listOf(
                            QuroSttPrefs.SOURCE_LOCAL to stringResource(R.string.qk_03075),
                            QuroSttPrefs.SOURCE_MODEL to stringResource(R.string.qk_03076),
                            QuroSttPrefs.SOURCE_ONDEVICE to stringResource(R.string.qk_03077),
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
                                        Text(qstr(R.string.qk_03078, (modelName).toString()), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                                    }
                                }
                            }
                            if (i != options.lastIndex) HorizontalDivider()
                        }
                        Text(stringResource(R.string.qk_03079),
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }
            }

            // ── 04 语音球绑定对话框 ────────────────────────────────────────
            ChapterLabel("04", stringResource(R.string.qk_03080))
            SetGroup {
                Column {
                    SetRow(
                        icon = Icons.Filled.Link,
                        name = stringResource(R.string.qk_03080),
                        sub = stringResource(R.string.qk_03081),
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
                        Text(stringResource(R.string.qk_03082), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 10.dp, end = 16.dp))
                        Row(
                            Modifier.fillMaxWidth().clickable { bindSessionId = ""; QuroVoiceFeaturePrefs.setVoiceBallSessionId(ctx, "") }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = bindSessionId.isBlank(), onClick = { bindSessionId = ""; QuroVoiceFeaturePrefs.setVoiceBallSessionId(ctx, "") })
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.qk_03083))
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
                                    Text(meta.title.ifBlank { qstr(R.string.qk_00282) })
                                    if (meta.preview.isNotBlank()) {
                                        Text(meta.preview, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1)
                                    }
                                }
                            }
                            HorizontalDivider()
                        }
                        if (conversations.value.isEmpty()) {
                            Text(stringResource(R.string.qk_03084), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                        }
                    }
                }
            }

            // ── 05 后台自启动 ──────────────────────────────────────────────
            ChapterLabel("05", stringResource(R.string.qk_03085))
            SetGroup {
                Column {
                    SetRow(
                        icon = Icons.Filled.PowerSettingsNew,
                        name = stringResource(R.string.qk_03085),
                        sub = stringResource(R.string.qk_03086),
                        checked = autoStart,
                        onToggle = { autoStart = !autoStart; QuroVoiceFeaturePrefs.setAutostart(ctx, autoStart) },
                    )
                    HorizontalDivider()
                    Text(stringResource(R.string.qk_03087),
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }

            // ── 06 LLM 情绪标签 ────────────────────────────────────────────
            ChapterLabel("06", stringResource(R.string.qk_03088))
            SetGroup {
                Column {
                    SetRow(
                        icon = Icons.Filled.AutoAwesome,
                        name = stringResource(R.string.qk_03088),
                        sub = stringResource(R.string.qk_03089),
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
                                stringResource(R.string.qk_03090)
                            } else {
                                "当前播放服务商：$providerLabel。${if (isMimo) "✅ 支持逐段真实情感合成（中文括号标记）。" else "该服务商不解析括号标记，AI 以自然语言体现情绪（无标记式情感合成）。"}"
                            },
                        )
                        Text(stringResource(R.string.qk_03092),
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }
            }

            // ── 07 语色路由 ────────────────────────────────────────────────
            ChapterLabel("07", stringResource(R.string.qk_03093))
            SetGroup {
                Column {
                    SetRow(
                        icon = Icons.Filled.Palette,
                        name = stringResource(R.string.qk_03093),
                        sub = stringResource(R.string.qk_03094),
                        checked = voiceColorRouting,
                        onToggle = { voiceColorRouting = !voiceColorRouting; QuroVoiceFeaturePrefs.setVoiceColorRoutingEnabled(ctx, voiceColorRouting) },
                    )
                    if (voiceColorRouting) {
                        HorizontalDivider()
                        val src = QuroTtsPrefs.getSource(ctx)
                        val isCloudLike = src == QuroTtsPrefs.SOURCE_CLOUD || src == QuroTtsPrefs.SOURCE_MIMO
                        val vpDef = QuroTtsProviders.byId(QuroTtsProviderPrefs.getProvider(ctx)) ?: QuroTtsProviders.byId("edge")!!
                        val vpCfg = QuroTtsProviderPrefs.getConfig(ctx, vpDef.id)
                        val providerLabel = vpDef.name
                        InfoBox(
                            text = if (isCloudLike) stringResource(R.string.qk_03095, (providerLabel).toString()) else stringResource(R.string.qk_03096),
                        )
                        Text(stringResource(R.string.qk_03097, (providerLabel).toString()), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 16.dp, top = 10.dp, end = 16.dp))
                        Spacer(Modifier.height(6.dp))
                        val palette = QuroCloudTtsCatalog.selectableVoiceNames(vpDef, vpCfg)
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
                        Text(stringResource(R.string.qk_03098),
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