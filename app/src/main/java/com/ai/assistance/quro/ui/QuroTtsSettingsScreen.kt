package com.ai.assistance.quro.ui

import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.speech.tts.Voice
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ai.assistance.quro.core.tools.QuroTtsHolder
import com.ai.assistance.quro.core.tools.QuroTtsPrefs
import com.ai.assistance.quro.core.tools.QuroTtsProviderPrefs
import com.ai.assistance.quro.core.tools.QuroTtsProviders
import com.ai.assistance.quro.ui.theme.Accent
import java.util.Locale

private val TTS_LANGUAGES = listOf(
    "中文（普通话）" to "zh-CN",
    "中文（繁体）" to "zh-TW",
    "粤语" to "yue-Hant",
    "English (US)" to "en-US",
    "English (UK)" to "en-GB",
    "日本語" to "ja-JP",
    "한국어" to "ko-KR",
    "Français" to "fr-FR",
    "Deutsch" to "de-DE",
    "Español" to "es-ES",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuroTtsSettingsScreen(onBack: () -> Unit = {}, onOpenCloudConfig: () -> Unit = {}) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    var source by remember { mutableStateOf(QuroTtsPrefs.getSource(ctx)) }
    var language by remember { mutableStateOf(QuroTtsPrefs.getLanguage(ctx)) }
    var voice by remember { mutableStateOf(QuroTtsPrefs.getVoice(ctx)) }
    var rate by remember { mutableFloatStateOf(QuroTtsPrefs.getRate(ctx)) }
    var pitch by remember { mutableFloatStateOf(QuroTtsPrefs.getPitch(ctx)) }

    var previewText by remember { mutableStateOf("这是一条语音合成测试，Quro AI 正在朗读。") }
    var speakStatus by remember { mutableStateOf<String?>(null) }

    // ── Bug 日志区域 ──
    var bugLogs by remember { mutableStateOf(listOf<String>()) }
    fun addLog(msg: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        bugLogs = bugLogs + "[$ts] $msg"
        if (bugLogs.size > 80) bugLogs = bugLogs.takeLast(60)
    }

    var voices by remember { mutableStateOf<List<Voice>>(emptyList()) }
    var langMenu by remember { mutableStateOf(false) }

    fun refreshVoices() {
        val target = Locale.forLanguageTag(language).language
        voices = runCatching {
            QuroTtsHolder.getVoices().filter { v ->
                val tag = v.locale.toLanguageTag().replace('_', '-')
                tag == language || v.locale.language == target
            }
        }.getOrDefault(emptyList())
    }

    DisposableEffect(Unit) {
        QuroTtsHolder.setLogCallback { msg -> addLog(msg) }
        onDispose { QuroTtsHolder.setLogCallback(null) }
    }

    LaunchedEffect(Unit) {
        addLog("页面加载：开始预初始化 TTS")
        QuroTtsHolder.ensure(ctx) { ok ->
            runCatching {
                addLog("LaunchedEffect ensure 回调: ok=$ok, voices=${runCatching { QuroTtsHolder.getVoices().size }.getOrDefault(0)}")
                refreshVoices()
            }.onFailure { e -> addLog("初始化回调异常(已忽略): ${e.message}") }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("语音合成 (TTS)") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") }
                },
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("文字转语音配置。朗读与悬浮语音球均跟随此处设置；语音引擎由手机系统默认 TTS 引擎接管。", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            OutlinedButton(
                onClick = { onOpenCloudConfig() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("前往语音服务设置 ›") }
            HorizontalDivider()

            // ── 语音来源选择 ───────────────────────────────────────────────
            Text("语音来源", style = MaterialTheme.typography.titleSmall)
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            source = QuroTtsPrefs.SOURCE_LOCAL
                            QuroTtsPrefs.setSource(ctx, QuroTtsPrefs.SOURCE_LOCAL)
                        }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = source == QuroTtsPrefs.SOURCE_LOCAL,
                            onClick = {
                                source = QuroTtsPrefs.SOURCE_LOCAL
                                QuroTtsPrefs.setSource(ctx, QuroTtsPrefs.SOURCE_LOCAL)
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("本地系统 (TTS 引擎)", style = MaterialTheme.typography.bodyMedium)
                            Text("使用手机自带语音引擎，离线可用", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider()
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            source = QuroTtsPrefs.SOURCE_MODEL
                            QuroTtsPrefs.setSource(ctx, QuroTtsPrefs.SOURCE_MODEL)
                        }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = source == QuroTtsPrefs.SOURCE_MODEL,
                            onClick = {
                                source = QuroTtsPrefs.SOURCE_MODEL
                                QuroTtsPrefs.setSource(ctx, QuroTtsPrefs.SOURCE_MODEL)
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("已配置模型", style = MaterialTheme.typography.bodyMedium)
                            Text("使用对话中已配置的 AI 模型合成语音", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider()
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            source = QuroTtsPrefs.SOURCE_CLOUD
                            QuroTtsPrefs.setSource(ctx, QuroTtsPrefs.SOURCE_CLOUD)
                        }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = source == QuroTtsPrefs.SOURCE_CLOUD,
                            onClick = {
                                source = QuroTtsPrefs.SOURCE_CLOUD
                                QuroTtsPrefs.setSource(ctx, QuroTtsPrefs.SOURCE_CLOUD)
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("云模型服务（多服务商）", style = MaterialTheme.typography.bodyMedium)
                            Text("Edge / 小米 / 火山 / 讯飞 / 腾讯 / 阿里 / OpenAI 等，详见「语音服务」", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                    }
                    }
                }
            }
            HorizontalDivider()

            // ── 本地系统引擎配置 ───────────────────────────────────────────
            if (source == QuroTtsPrefs.SOURCE_LOCAL) {
                ListItem(
                    headlineContent = { Text("识别语言") },
                    supportingContent = { Text(TTS_LANGUAGES.firstOrNull { it.second == language }?.first ?: language) },
                    trailingContent = { Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = cs.onSurfaceVariant) },
                    modifier = Modifier.clickable { langMenu = true },
                )
                DropdownMenu(expanded = langMenu, onDismissRequest = { langMenu = false }) {
                    TTS_LANGUAGES.forEach { (label, code) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = {
                            language = code
                            QuroTtsPrefs.setLanguage(ctx, code)
                            langMenu = false
                            refreshVoices()
                        })
                    }
                }
                HorizontalDivider()

                Text(
                    "语音引擎：由手机系统默认 TTS 引擎接管（设置 → 语言与输入 → 文字转语音）。无需在此选择。",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )

                Text("声音 (Voice)", style = MaterialTheme.typography.titleSmall)
                if (voices.isEmpty()) {
                    Text("当前语言无可用声音，请换语言或安装对应 TTS 语音包。", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                } else {
                    LazyColumn(
                        Modifier.fillMaxWidth().heightIn(max = 320.dp)
                            .border(1.dp, cs.outline, RoundedCornerShape(12.dp)),
                    ) {
                        items(voices) { v ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    voice = v.name
                                    QuroTtsPrefs.setVoice(ctx, v.name)
                                }.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = voice == v.name,
                                    onClick = {
                                        voice = v.name
                                        QuroTtsPrefs.setVoice(ctx, v.name)
                                    },
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(v.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "${v.locale} · ${if (v.isNetworkConnectionRequired) "网络" else "本地"}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = cs.onSurfaceVariant,
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
                HorizontalDivider()

                Text("语速：${"%.2f".format(rate)}x", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = rate,
                    onValueChange = { rate = it; QuroTtsPrefs.setRate(ctx, it) },
                    valueRange = 0.5f..2.0f,
                    steps = 15,
                )
                Text("音高：${"%.2f".format(pitch)}x", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = pitch,
                    onValueChange = { pitch = it; QuroTtsPrefs.setPitch(ctx, it) },
                    valueRange = 0.5f..2.0f,
                    steps = 15,
                )
                Text("范围 0.5x – 2.0x，默认 1.0x。", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                HorizontalDivider()

                // ── 试听区 ──────────────────────────────────────────────────
                Text("试听文本", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = previewText,
                    onValueChange = { previewText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("输入要朗读的文本") },
                    minLines = 2,
                    maxLines = 4,
                    singleLine = false,
                )

                speakStatus?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = when {
                            it.contains("失败") || it.contains("异常") || it.contains("❌") -> cs.error
                            it.contains("✅") -> androidx.compose.ui.graphics.Color(0xFF2E7D32)
                            else -> cs.onSurfaceVariant
                        },
                    )
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            bugLogs = emptyList()
                            addLog("━━━ 点击试听 ━━━")

                            scope.launch {
                                speakStatus = "初始化中…"
                                try {
                                    val diag = QuroTtsHolder.audioDiagnostics(ctx)
                                    addLog("音频环境: $diag")

                                    val ok = QuroTtsHolder.ensureReady(ctx)
                                    addLog("ensureReady 返回: $ok")

                                    if (!ok) {
                                        val engineDiag = QuroTtsHolder.diagnoseEngines(ctx)
                                        addLog("诊断: $engineDiag")
                                        speakStatus = "初始化失败 ❌\n音频: $diag\n引擎: $engineDiag"
                                        return@launch
                                    }

                                    speakStatus = "正在朗读…"
                                    addLog("调用 speak()...")
                                    val r = QuroTtsHolder.speak(previewText.ifBlank { " " })
                                    speakStatus = when (r) {
                                        0 -> "已发送朗读请求 ✅\n$diag"
                                        -1 -> "引擎未就绪 ❌\n$diag"
                                        -2 -> {
                                            addLog("正常模式失败(r=-2)，尝试安全模式...")
                                            val r2 = QuroTtsHolder.speakMinimal(previewText.ifBlank { " " })
                                            addLog("speakMinimal 返回: $r2")
                                            if (r2 == 0) "✅ 安全模式成功\n$diag"
                                            else "朗读仍失败 ❌\n$diag"
                                        }
                                        else -> "未知状态($r)\n$diag"
                                    }
                                } catch (e: Exception) {
                                    addLog("异常: ${e.javaClass.simpleName}: ${e.message}")
                                    speakStatus = "异常: ${e.message}"
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Accent),
                        modifier = Modifier.weight(1f),
                    ) { Text("试听") }
                    Button(
                        onClick = {
                            scope.launch {
                                addLog("━━━ 安全试听（裸调） ━━━")
                                val r = QuroTtsHolder.speakMinimal(previewText.ifBlank { " " })
                                speakStatus = if (r == 0) "安全模式成功 ✅" else "安全模式也失败 (r=$r) ❌"
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = cs.surfaceVariant),
                        modifier = Modifier.weight(1f),
                    ) { Text("安全试听") }
                    Button(
                        onClick = {
                            addLog("━━━ 强制重置 TTS ━━━")
                            QuroTtsHolder.reset()
                            speakStatus = "已重置，请重新点试听"
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = cs.errorContainer),
                        modifier = Modifier.weight(1f),
                    ) { Text("重置") }
                }

                // ── Bug 日志区域 ────────────────────────────────────────────
                if (bugLogs.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("📋 Bug 日志 (${bugLogs.size})", style = MaterialTheme.typography.titleSmall, color = cs.primary)
                        Row {
                            TextButton(onClick = {
                                val text = bugLogs.joinToString("\n")
                                val clip = ClipData.newPlainText("QuroTTS", text)
                                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                                speakStatus = "日志已复制到剪贴板 ✅ 直接粘贴给我即可"
                            }) { Text("复制", style = MaterialTheme.typography.labelSmall) }
                            TextButton(onClick = { bugLogs = emptyList() }) { Text("清空", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                    Card(
                        Modifier.fillMaxWidth()
                            .heightIn(min = 80.dp, max = 220.dp)
                            .verticalScroll(rememberScrollState()),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant),
                    ) {
                        SelectionContainer {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                bugLogs.forEach { entry ->
                                    Text(
                                        entry,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = when {
                                            entry.contains("❌") || entry.contains("FAILED") || entry.contains("error") || entry.contains("Error") || entry.contains("exception", ignoreCase = true) -> cs.error
                                            entry.contains("✅") || entry.contains("SUCCESS") || entry.contains("READY") -> androidx.compose.ui.graphics.Color(0xFF2E7D32)
                                            else -> cs.onSurfaceVariant
                                        },
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    )
                                }
                            }
                        }
                    }
                }
            } else if (source == QuroTtsPrefs.SOURCE_CLOUD) {
                val providerId = QuroTtsProviderPrefs.getProvider(ctx)
                val def = QuroTtsProviders.byId(providerId)
                val configured = QuroTtsProviderPrefs.isConfigured(ctx)
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("云模型服务 · 多服务商", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (configured) "已配置 ✓" else "未配置参数",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (configured) androidx.compose.ui.graphics.Color(0xFF2E7D32) else cs.error,
                            )
                        }
                        Text(
                            "已接入 Edge TTS / 小米 MiMo / 火山引擎 / 科大讯飞 / 腾讯云 / 阿里百炼 CosyVoice / OpenAI / MiniMax / 硅基流动 / TTS302 / CozeCn / Gizwits / ACGN 等多家云端 TTS。点击进入「语音服务」选择服务商并配置。",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                        )
                        Text(
                            "当前服务商：${def?.name ?: providerId}",
                            style = MaterialTheme.typography.labelMedium,
                            color = cs.primary,
                        )
                        // ★ 恢复用户熟悉的入口名「前往语音服务设置」，指向独立云模型配置屏
                        // （TTS 云模型服务商选择/配置），不再指向语音服务 Hub，杜绝循环。
                        Button(
                            onClick = { onOpenCloudConfig() },
                            colors = ButtonDefaults.buttonColors(containerColor = Accent),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("前往语音服务设置 ›") }
                            }
                }
            } else {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("敬请期待", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "已配置模型的语音合成将在后续版本开放。届时将直接调用对话中已配置的 AI 模型进行配音，无需依赖手机本地 TTS 引擎。",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                }
            }
        }
}
