package com.ai.assistance.quro.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ai.assistance.quro.core.QuroConversationMeta
import com.ai.assistance.quro.core.tools.QuroVoiceFeaturePrefs

/**
 * 语音设置（v139 重构 · 人格卡式分页）：
 * 把语音相关能力拆成若干「标签（页面）」，每个标签顶部都有独立的「开启」开关（标签开启功能），
 * 开关关闭则该能力整体停用。新增「语音球绑定对话框」标签——手动选择语音球把对话写进哪个会话，
 * 而不是永远写进「当前正在看的对话框」。
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
    var tab by remember { mutableStateOf(0) }

    var autoRead by remember { mutableStateOf(QuroVoiceFeaturePrefs.getAutoRead(ctx)) }
    var dialogVoice by remember { mutableStateOf(QuroVoiceFeaturePrefs.getDialogVoiceButton(ctx)) }
    var source by remember { mutableStateOf(QuroVoiceFeaturePrefs.getSource(ctx)) }
    var voiceName by remember { mutableStateOf(QuroVoiceFeaturePrefs.getVoiceName(ctx)) }
    var speed by remember { mutableFloatStateOf(QuroVoiceFeaturePrefs.getSpeed(ctx)) }
    var autoStart by remember { mutableStateOf(QuroVoiceFeaturePrefs.getAutostart(ctx)) }

    // 语音球绑定对话框
    var bindSessionId by remember { mutableStateOf(QuroVoiceFeaturePrefs.getVoiceBallSessionId(ctx)) }
    var bindEnabled by remember { mutableStateOf(bindSessionId.isNotBlank()) }
    val conversations = runCatching { QuroChatViewModel.instance.conversations }.getOrNull()
        ?.collectAsState() ?: remember { mutableStateOf(emptyList<QuroConversationMeta>()) }

    val tabs = listOf("悬浮语音球", "自动朗读", "对话框按钮", "语音球绑定", "默认语音", "后台自启动")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("语音设置") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") } },
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            ScrollableTabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                }
            }
            HorizontalDivider()

            Column(
                Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (tab) {
                    0 -> VoiceBallPage(voiceBallEnabled, onToggleVoiceBall)
                    1 -> AutoReadPage(autoRead) { v -> autoRead = v; QuroVoiceFeaturePrefs.setAutoRead(ctx, v) }
                    2 -> DialogVoicePage(dialogVoice) { v -> dialogVoice = v; QuroVoiceFeaturePrefs.setDialogVoiceButton(ctx, v) }
                    3 -> BindPage(
                        bindEnabled = bindEnabled,
                        bindSessionId = bindSessionId,
                        conversations = conversations,
                        onToggle = { on ->
                            bindEnabled = on
                            if (on) {
                                // 启用时：若无已选会话，默认绑定当前正在看的对话框
                                if (bindSessionId.isBlank()) {
                                    bindSessionId = runCatching { QuroChatViewModel.instance.activeConversationId }.getOrNull() ?: ""
                                }
                            } else {
                                bindSessionId = ""
                            }
                            QuroVoiceFeaturePrefs.setVoiceBallSessionId(ctx, bindSessionId)
                        },
                        onSelect = { id ->
                            bindSessionId = id
                            QuroVoiceFeaturePrefs.setVoiceBallSessionId(ctx, id)
                        },
                    )
                    4 -> DefaultVoicePage(
                        source = source,
                        voiceName = voiceName,
                        speed = speed,
                        onSource = { source = it; QuroVoiceFeaturePrefs.setSource(ctx, it) },
                        onVoiceName = { voiceName = it; QuroVoiceFeaturePrefs.setVoiceName(ctx, it) },
                        onSpeed = { speed = it; QuroVoiceFeaturePrefs.setSpeed(ctx, it) },
                    )
                    5 -> AutoStartPage(autoStart) { v -> autoStart = v; QuroVoiceFeaturePrefs.setAutostart(ctx, v) }
                }
            }
        }
    }
}

/** 「悬浮语音球」页：总开关（复用 Activity 状态）。 */
@Composable
private fun VoiceBallPage(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    PageHeader(
        title = "悬浮语音球",
        desc = "在任意界面挂一个可点击的球，STT → LLM → TTS 随时语音对话（需悬浮窗与麦克风权限）。这是语音能力的「总闸」。",
        enabled = enabled,
        onToggle = onToggle,
    )
    Text(
        "关闭后，自动朗读 / 对话框语音按钮等仍可按各自开关独立工作。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 「自动朗读 AI 回复」页。 */
@Composable
private fun AutoReadPage(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    PageHeader(
        title = "自动朗读 AI 回复",
        desc = "收到 AI 文字回复时，自动用 TTS 朗读出来。",
        enabled = enabled,
        onToggle = onToggle,
    )
}

/** 「对话框语音按钮」页。 */
@Composable
private fun DialogVoicePage(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    PageHeader(
        title = "对话框语音按钮",
        desc = "在输入框旁显示语音输入按钮，点按即说话，识别文本填入输入框。",
        enabled = enabled,
        onToggle = onToggle,
    )
}

/** 「后台自启动」页：开机拉起常住语音球（v151）。 */
@Composable
private fun AutoStartPage(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    PageHeader(
        title = "后台自启动",
        desc = "开机后自动拉起常住语音球（含通知栏），不自动聆听，等点按开始。需系统授予「自启动/后台运行」权限才生效。",
        enabled = enabled,
        onToggle = onToggle,
    )
    Text(
        "开启后，设备开机完成会尝试启动前台语音球服务（仅挂通知栏、不主动录音）。若厂商 ROM 限制了自启动，请在系统「电池/自启动管理」里允许 QuroAI。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 「语音球绑定对话框」页：手动选择语音球把对话写进哪个会话。 */
@Composable
private fun BindPage(
    bindEnabled: Boolean,
    bindSessionId: String,
    conversations: State<List<QuroConversationMeta>>,
    onToggle: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
) {
    PageHeader(
        title = "语音球绑定对话框",
        desc = "语音球默认把对话写进「你当前正在看的对话框」。开启后可手动指定写进某一个固定会话，即使你正看着别的对话框也不变。",
        enabled = bindEnabled,
        onToggle = onToggle,
    )
    if (bindEnabled) {
        HorizontalDivider()
        Text("选择目标对话框：", style = MaterialTheme.typography.titleSmall)
        Row(
            Modifier.fillMaxWidth().clickable { onSelect("") }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = bindSessionId.isBlank(), onClick = { onSelect("") })
            Spacer(Modifier.width(8.dp))
            Text("跟随当前对话框（自动）")
        }
        conversations.value.forEach { meta ->
            Row(
                Modifier.fillMaxWidth().clickable { onSelect(meta.id) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = bindSessionId == meta.id, onClick = { onSelect(meta.id) })
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(meta.title.ifBlank { "新对话" })
                    if (meta.preview.isNotBlank()) {
                        Text(
                            meta.preview,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        if (conversations.value.isEmpty()) {
            Text("暂无其它对话框。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 「默认语音」页：来源 / 音色 / 语速。 */
@Composable
private fun DefaultVoicePage(
    source: String,
    voiceName: String,
    speed: Float,
    onSource: (String) -> Unit,
    onVoiceName: (String) -> Unit,
    onSpeed: (Float) -> Unit,
) {
    PageHeader(
        title = "默认语音配置",
        desc = "设置对话框 / 语音球默认使用的语音来源、音色与语速。",
        enabled = true,
        onToggle = {},
        showToggle = false,
    )
    Text("默认语音来源", style = MaterialTheme.typography.titleSmall)
    val sources = listOf("local" to "本地 TTS", "cloud" to "云端模型", "model" to "小米 MiMo")
    sources.forEach { (id, label) ->
        Row(
            Modifier.fillMaxWidth().clickable { onSource(id) }.padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = source == id, onClick = { onSource(id) })
            Spacer(Modifier.width(8.dp))
            Text(label)
        }
    }
    Spacer(Modifier.height(8.dp))
    HorizontalDivider()
    OutlinedTextField(
        value = voiceName,
        onValueChange = onVoiceName,
        label = { Text("默认音色") },
        placeholder = { Text("例如：温柔女声 / 低沉男声") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Text("默认语速：${"%.2f".format(speed)}x", style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = speed,
        onValueChange = onSpeed,
        valueRange = 0.5f..2.0f,
        steps = 15,
    )
    Text("范围 0.5x – 2.0x，默认 1.0x。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** 每页共用的「标题 + 独立开启开关」头（人格卡式「标签开启」）。 */
@Composable
private fun PageHeader(
    title: String,
    desc: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    showToggle: Boolean = true,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (showToggle) {
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}
