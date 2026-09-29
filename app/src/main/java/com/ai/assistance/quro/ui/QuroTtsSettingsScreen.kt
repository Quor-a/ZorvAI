package com.ai.assistance.quro.ui
import androidx.compose.ui.res.stringResource
import com.ai.assistance.quro.R
import com.ai.assistance.quro.util.qstr

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ai.assistance.quro.core.tools.QuroTtsHolder
import com.ai.assistance.quro.core.tools.QuroTtsPrefs
import com.ai.assistance.quro.core.tools.QuroTtsProviderPrefs
import com.ai.assistance.quro.core.tools.QuroTtsProviders
import com.ai.assistance.quro.ui.theme.Accent
import java.util.Locale
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.launch

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

    var previewText by remember { mutableStateOf(qstr(R.string.qk_02922)) }
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
        addLog(qstr(R.string.qk_02923))
        QuroTtsHolder.ensure(ctx) { ok ->
            runCatching {
                addLog("LaunchedEffect ensure 回调: ok=$ok, voices=${runCatching { QuroTtsHolder.getVoices().size }.getOrDefault(0)}")
                refreshVoices()
            }.onFailure { e -> addLog(qstr(R.string.qk_02925, (e.message).toString())) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.qk_00232)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.qk_00143)) }
                },
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.qk_02926), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)

            // ── 01 语音来源 ───────────────────────────────────────────────
            ChapterLabel("01", stringResource(R.string.qk_02927))
            SetGroup {
                Column {
                    SourceRadioRow(
                        id = QuroTtsPrefs.SOURCE_LOCAL,
                        title = stringResource(R.string.qk_02928),
                        sub = stringResource(R.string.qk_02929),
                        selected = source == QuroTtsPrefs.SOURCE_LOCAL,
                        onSelect = {
                            source = it
                            QuroTtsPrefs.setSource(ctx, it)
                        },
                    )
                    HorizontalDivider()
                    SourceRadioRow(
                        id = QuroTtsPrefs.SOURCE_CLOUD,
                        title = stringResource(R.string.qk_02930),
                        sub = stringResource(R.string.qk_02931),
                        selected = source == QuroTtsPrefs.SOURCE_CLOUD,
                        onSelect = {
                            source = it
                            QuroTtsPrefs.setSource(ctx, it)
                        },
                    )
                }
            }

            if (source == QuroTtsPrefs.SOURCE_LOCAL) {
                // ── 02 本地系统引擎 ───────────────────────────────────────
                ChapterLabel("02", stringResource(R.string.qk_02932))
                SetGroup {
                    SetRowClickable(
                        icon = Icons.Filled.ChevronRight,
                        name = stringResource(R.string.qk_02631),
                        sub = TTS_LANGUAGES.firstOrNull { it.second == language }?.first ?: language,
                        onClick = { langMenu = true },
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
                }
                Text(stringResource(R.string.qk_02933),
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )

                // ── 03 声音 ───────────────────────────────────────────────
                ChapterLabel("03", stringResource(R.string.qk_02934))
                if (voices.isEmpty()) {
                    Text(stringResource(R.string.qk_02935), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
                } else {
                    SetGroup {
                        Column {
                            voices.forEachIndexed { i, v ->
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
                                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                                        )
                                    }
                                }
                                if (i != voices.lastIndex) HorizontalDivider()
                            }
                        }
                    }
                }

                // ── 04 语速与音高 ─────────────────────────────────────────
                ChapterLabel("04", stringResource(R.string.qk_02936))
                SetGroup {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                        Text(stringResource(R.string.qk_02939), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    }
                }

                // ── 05 试听 ────────────────────────────────────────────────
                ChapterLabel("05", stringResource(R.string.qk_02940))
                SetGroup {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = previewText,
                            onValueChange = { previewText = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(R.string.qk_01524)) },
                            minLines = 2, maxLines = 4, singleLine = false,
                        )
                        speakStatus?.let {
                            Text(
                                it, style = MaterialTheme.typography.bodyMedium,
                                color = when {
                                    it.contains("失败") || it.contains("异常") || it.contains("❌") -> cs.error
                                    it.contains("✅") -> Color(0xFF2E7D32)
                                    else -> cs.onSurfaceVariant
                                },
                            )
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    bugLogs = emptyList()
                                    addLog(qstr(R.string.qk_02941))
                                    scope.launch {
                                        speakStatus = qstr(R.string.qk_02942)
                                        try {
                                            val diag = QuroTtsHolder.audioDiagnostics(ctx)
                                            addLog(qstr(R.string.qk_02943, (diag).toString()))
                                            val ok = QuroTtsHolder.ensureReady(ctx)
                                            addLog(qstr(R.string.qk_02944, (ok).toString()))
                                            if (!ok) {
                                                val engineDiag = QuroTtsHolder.diagnoseEngines(ctx)
                                                addLog(qstr(R.string.qk_02945, (engineDiag).toString()))
                                                speakStatus = qstr(R.string.qk_02946, (diag).toString(), (engineDiag).toString())
                                                return@launch
                                            }
                                            speakStatus = qstr(R.string.qk_02947)
                                            addLog(qstr(R.string.qk_02948))
                                            val r = QuroTtsHolder.speak(previewText.ifBlank { " " })
                                            speakStatus = when (r) {
                                                0 -> qstr(R.string.qk_02949, (diag).toString())
                                                -1 -> qstr(R.string.qk_02950, (diag).toString())
                                                -2 -> {
                                                    addLog(qstr(R.string.qk_02951))
                                                    val r2 = QuroTtsHolder.speakMinimal(previewText.ifBlank { " " })
                                                    addLog(qstr(R.string.qk_02952, (r2).toString()))
                                                    if (r2 == 0) qstr(R.string.qk_02953, (diag).toString()) else qstr(R.string.qk_02954, (diag).toString())
                                                }
                                                else -> qstr(R.string.qk_02955, (r).toString(), (diag).toString())
                                            }
                                        } catch (e: Exception) {
                                            addLog(qstr(R.string.qk_02956, (e.javaClass.simpleName).toString(), (e.message).toString()))
                                            speakStatus = qstr(R.string.qk_02957, (e.message).toString())
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                                modifier = Modifier.weight(1f),
                            ) { Text(stringResource(R.string.qk_02940)) }
                            Button(
                                onClick = {
                                    scope.launch {
                                        addLog(qstr(R.string.qk_02958))
                                        val r = QuroTtsHolder.speakMinimal(previewText.ifBlank { " " })
                                        speakStatus = if (r == 0) qstr(R.string.qk_02959) else qstr(R.string.qk_02960, (r).toString())
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = cs.surfaceVariant),
                                modifier = Modifier.weight(1f),
                            ) { Text(stringResource(R.string.qk_02961)) }
                            Button(
                                onClick = {
                                    addLog(qstr(R.string.qk_02962))
                                    QuroTtsHolder.reset()
                                    speakStatus = qstr(R.string.qk_02963)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = cs.errorContainer),
                                modifier = Modifier.weight(1f),
                            ) { Text(stringResource(R.string.qk_02964)) }
                        }
                    }
                }

                // ── Bug 日志区域 ───────────────────────────────────────────
                if (bugLogs.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.qk_02965, (bugLogs.size).toString()), style = MaterialTheme.typography.titleSmall, color = cs.primary)
                        Row {
                            TextButton(onClick = {
                                val text = bugLogs.joinToString("\n")
                                val clip = ClipData.newPlainText("QuroTTS", text)
                                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                                speakStatus = qstr(R.string.qk_02641)
                            }) { Text(stringResource(R.string.qk_00088), style = MaterialTheme.typography.labelSmall) }
                            TextButton(onClick = { bugLogs = emptyList() }) { Text(stringResource(R.string.qk_00764), style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                    Card(
                        Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 220.dp).verticalScroll(rememberScrollState()),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant),
                    ) {
                        SelectionContainer {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                bugLogs.forEach { entry ->
                                    Text(
                                        entry, style = MaterialTheme.typography.labelSmall,
                                        color = when {
                                            entry.contains("❌") || entry.contains("FAILED") || entry.contains("error") || entry.contains("Error") || entry.contains("exception", ignoreCase = true) -> cs.error
                                            entry.contains("✅") || entry.contains("SUCCESS") || entry.contains("READY") -> Color(0xFF2E7D32)
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
                // ── 02 云模型服务 ─────────────────────────────────────────
                ChapterLabel("02", stringResource(R.string.qk_02966))
                val providerId = QuroTtsProviderPrefs.getProvider(ctx)
                val def = QuroTtsProviders.byId(providerId)
                val configured = QuroTtsProviderPrefs.isConfigured(ctx)
                SetGroup {
                    Column {
                        SetRowClickable(
                            icon = Icons.Filled.ChevronRight,
                            name = stringResource(R.string.qk_02967),
                            sub = stringResource(R.string.qk_02968),
                            value = def?.name ?: providerId,
                            onClick = onOpenCloudConfig,
                        )
                        HorizontalDivider()
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (configured) stringResource(R.string.qk_02969) else stringResource(R.string.qk_02970),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (configured) Color(0xFF2E7D32) else cs.error,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                PrimaryButton(text = stringResource(R.string.qk_02971), onClick = onOpenCloudConfig)
                Text(stringResource(R.string.qk_02972),
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp),
                )
            } else {
                // ── 残留无效来源（如历史 SOURCE_MODEL）─────────────────────
                ChapterLabel("02", stringResource(R.string.qk_02927))
                InfoBox(stringResource(R.string.qk_02973))
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

/** 语音来源单选行（SetGroup 内使用）。 */
@Composable
private fun SourceRadioRow(
    id: String,
    title: String,
    sub: String,
    selected: Boolean,
    onSelect: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable { onSelect(id) }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = { onSelect(id) })
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}