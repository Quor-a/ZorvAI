package com.ai.assistance.quro.ui
import androidx.compose.ui.res.stringResource
import com.ai.assistance.quro.R
import com.ai.assistance.quro.util.qstr

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ai.assistance.quro.core.tools.*
import com.ai.assistance.quro.ui.theme.Accent
import kotlinx.coroutines.launch

/**
 * 云端 TTS 模型配置屏（真实数据驱动）。
 *
 * 数据层：
 *  - [QuroTtsProviders.ALL]：13 家服务商的定义（字段、音色、格式、必填项）。
 *  - [QuroTtsProviderPrefs]：选中服务商 + 各字段/音色/风格标签的读写。
 *  - [QuroCloudTtsCatalog]：情绪/风格标签词库、MiMo 预置音色。
 *
 * 与 [QuroCloudTts.play] 共用同一份持久化配置，配置项与合成引擎严格一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuroCloudTtsConfigScreen(onBack: () -> Unit = {}) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    var providerId by remember { mutableStateOf(QuroTtsProviderPrefs.getProvider(ctx)) }
    val def = QuroTtsProviders.byId(providerId) ?: QuroTtsProviders.byId("edge")!!

    var fieldValues by remember { mutableStateOf(QuroTtsProviderPrefs.getConfig(ctx, providerId).fields.toMutableMap()) }
    var voice by remember { mutableStateOf(QuroTtsProviderPrefs.getConfig(ctx, providerId).voice) }
    var format by remember { mutableStateOf(QuroTtsProviderPrefs.getConfig(ctx, providerId).format) }
    var styleTags by remember { mutableStateOf(QuroTtsProviderPrefs.getConfig(ctx, providerId).styleTags.toList()) }
    var customStyleTags by remember { mutableStateOf(QuroTtsProviderPrefs.getConfig(ctx, providerId).customStyleTags.toList()) }
    var preview by remember { mutableStateOf(QuroTtsProviderPrefs.getConfig(ctx, providerId).preview) }
    var customVoices by remember { mutableStateOf(QuroTtsProviderPrefs.getConfig(ctx, providerId).customVoices) }
    var streaming by remember { mutableStateOf(QuroTtsProviderPrefs.getConfig(ctx, providerId).streaming) }
    var cloneEnabled by remember { mutableStateOf(QuroTtsProviderPrefs.getConfig(ctx, providerId).cloneEnabled) }

    var status by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    fun loadFor(id: String) {
        val c = QuroTtsProviderPrefs.getConfig(ctx, id)
        fieldValues = c.fields.toMutableMap()
        voice = c.voice
        format = c.format
        styleTags = c.styleTags.toList()
        customStyleTags = c.customStyleTags.toList()
        preview = c.preview
        customVoices = c.customVoices
        streaming = c.streaming
        cloneEnabled = c.cloneEnabled
    }

    fun save() {
        val cfg = QuroTtsProviderConfig(
            fields = fieldValues,
            voice = voice,
            styleTags = styleTags,
            customStyleTags = customStyleTags,
            format = format,
            model = fieldValues["model"] ?: def.defaultModel,
            preview = preview,
            customVoices = customVoices,
            streaming = streaming,
            cloneEnabled = cloneEnabled,
        )
        QuroTtsProviderPrefs.saveConfig(ctx, providerId, cfg)
        QuroTtsProviderPrefs.setProvider(ctx, providerId)
        // ★ 修复「选了云模型没生效」：保存云模型配置即把 TTS 来源切到云模型服务，
        // 否则「语音来源」仍停在 local，真实朗读（聊天/语音球）不会进 QuroCloudTts.play。
        QuroTtsPrefs.setSource(ctx, QuroTtsPrefs.SOURCE_CLOUD)
        status = qstr(R.string.qk_01466)
        Toast.makeText(ctx, qstr(R.string.qk_01467), Toast.LENGTH_SHORT).show()
    }

    /** 删除当前服务商的已保存配置（删除已配置模型 / 服务商），回落到「未配置」状态。 */
    fun clearCurrentConfig() {
        QuroTtsProviderPrefs.clearConfig(ctx, providerId)
        loadFor(providerId)
        status = qstr(R.string.qk_01468, (def.name).toString())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.qk_01469)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.qk_00143)) }
                },
                actions = {
                    if (saving) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Accent)
                    } else {
                        TextButton(onClick = { clearCurrentConfig() }) { Text(stringResource(R.string.qk_01470), color = cs.error) }
                        TextButton(onClick = { save() }) { Text(stringResource(R.string.qk_00198), color = Accent) }
                    }
                },
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.qk_01471),
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
            )

            // ── 服务商选择（可折叠卡片 + 上下滑动） ───────────────────────
            Text(stringResource(R.string.qk_01472), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.qk_01473, (QuroTtsProviders.ALL.size).toString()),
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
            )
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 360.dp)
                    .border(1.dp, cs.outline, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp)),
            ) {
                items(QuroTtsProviders.ALL) { p ->
                    val selected = providerId == p.id
                    Column(
                        Modifier.fillMaxWidth()
                            .background(if (selected) cs.primaryContainer else cs.surface)
                            .clickable {
                                providerId = p.id
                                loadFor(p.id)
                                status = null
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = selected,
                                onClick = {
                                    providerId = p.id
                                    loadFor(p.id)
                                    status = null
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = MaterialTheme.typography.bodyMedium)
                                Text(p.desc, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            }
                            Icon(
                                if (selected) Icons.Filled.Check else Icons.Filled.ChevronRight,
                                contentDescription = null,
                                tint = if (selected) cs.primary else cs.onSurfaceVariant,
                            )
                        }
                        if (selected) {
                            Spacer(Modifier.height(8.dp))
                            HorizontalDivider(color = cs.outline.copy(alpha = 0.6f))
                            Spacer(Modifier.height(8.dp))
                            val need = if (p.requiredFields.isEmpty()) {
                                stringResource(R.string.qk_01474)
                            } else {
                                stringResource(R.string.qk_01475) + p.requiredFields.joinToString(" / ") { fk ->
                                    p.fields.firstOrNull { it.key == fk }?.label ?: fk
                                }
                            }
                            val cfgState = if (p.requiredFields.isEmpty()) {
                                stringResource(R.string.qk_01476)
                            } else {
                                if (QuroTtsProviderPrefs.isConfiguredFor(ctx, p.id)) stringResource(R.string.qk_01477) else stringResource(R.string.qk_00015)
                            }
                            Text(
                                "$need · $cfgState",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (cfgState.startsWith(stringResource(R.string.qk_01478)) || p.requiredFields.isEmpty()) cs.primary else cs.error,
                            )
                        }
                    }
                    if (p.id != QuroTtsProviders.ALL.last().id) HorizontalDivider()
                }
            }

            HorizontalDivider()

            // ── 当前服务商参数 ───────────────────────────────────────────
            Text(stringResource(R.string.qk_01479, (def.name).toString()), style = MaterialTheme.typography.titleSmall)

            if (def.fields.isEmpty()) {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.qk_01480), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
            } else {
                def.fields.forEach { f ->
                    val value = fieldValues[f.key] ?: ""
                    UnderlineField(
                        label = f.label,
                        value = value,
                        onValueChange = { fieldValues = fieldValues.toMutableMap().apply { put(f.key, it) } },
                        placeholder = f.placeholder,
                        isSecret = f.secret,
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            // ── 音色 ─────────────────────────────────────────────────────
            Text(stringResource(R.string.qk_01481), style = MaterialTheme.typography.titleSmall)
            val cloneVoices = customVoices.filter { it.type == "clone" }
            if (def.voices.isNotEmpty() || cloneVoices.isNotEmpty()) {
                var voiceMenu by remember { mutableStateOf(false) }
                val voiceLabel = if (voice.isBlank()) {
                    stringResource(R.string.qk_01482)
                } else if (voice.startsWith("custom::")) {
                    val cn = voice.removePrefix("custom::")
                    val cv = cloneVoices.firstOrNull { it.name == cn }
                    "复刻：$cn${if (cv?.registeredId?.isNotBlank() == true) " ✓已注册" else ""}"
                } else {
                    voice
                }
                ListItem(
                    headlineContent = { Text(voiceLabel) },
                    supportingContent = { Text(stringResource(R.string.qk_01484)) },
                    trailingContent = { Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = cs.onSurfaceVariant) },
                    modifier = Modifier.fillMaxWidth()
                        .border(1.dp, cs.outline, RoundedCornerShape(12.dp))
                        .clickable { voiceMenu = true },
                )
                DropdownMenu(expanded = voiceMenu, onDismissRequest = { voiceMenu = false }) {
                    def.voices.forEach { v ->
                        DropdownMenuItem(
                            text = { Text("${v.name}${if (v.gender.isNotBlank()) " · ${v.gender}" else ""}${if (v.lang.isNotBlank()) " · ${v.lang}" else ""}") },
                            onClick = { voice = v.id; voiceMenu = false },
                        )
                    }
                    cloneVoices.forEach { cv ->
                        DropdownMenuItem(
                            text = { Text("复刻：${cv.name}${if (cv.registeredId.isNotBlank()) " ✓已注册" else "（未注册）"}") },
                            onClick = { voice = "custom::${cv.name}"; voiceMenu = false },
                        )
                    }
                }
            }
            if (def.voiceFreeText) {
                UnderlineField(
                    label = stringResource(R.string.qk_01486),
                    value = voice,
                    onValueChange = { voice = it },
                    placeholder = stringResource(R.string.qk_01487),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (def.cloneSupport) {
                    Text(stringResource(R.string.qk_01488),
                        style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                    )
                }
            }
            if (def.voices.isEmpty() && !def.voiceFreeText && cloneVoices.isEmpty()) {
                Text(stringResource(R.string.qk_01489), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }

            // ── 输出格式 ─────────────────────────────────────────────────
            if (def.formatOptions.size > 1) {
                Text(stringResource(R.string.qk_01490), style = MaterialTheme.typography.titleSmall)
                var fmtMenu by remember { mutableStateOf(false) }
                ListItem(
                    headlineContent = { Text(format) },
                    supportingContent = { Text("可选：${def.formatOptions.joinToString(" / ")}") },
                    trailingContent = { Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = cs.onSurfaceVariant) },
                    modifier = Modifier.fillMaxWidth()
                        .border(1.dp, cs.outline, RoundedCornerShape(12.dp))
                        .clickable { fmtMenu = true },
                )
                DropdownMenu(expanded = fmtMenu, onDismissRequest = { fmtMenu = false }) {
                    def.formatOptions.forEach { f ->
                        DropdownMenuItem(text = { Text(f) }, onClick = { format = f; fmtMenu = false })
                    }
                }
            }

            // ── 流式输出开关（全部服务商默认开启） ─────────────────────────
            if (def.streamingSupport) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.qk_01492), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.qk_01493),
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = streaming, onCheckedChange = { streaming = it })
                }
                HorizontalDivider()
            }

            HorizontalDivider()

            // ── 风格标签（支持的服务商） ──────────────────────────────────
            if (def.styleSupport) {
                Text(stringResource(R.string.qk_01494), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.qk_01495),
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                )
                val tagPool = if (def.providerTags.isNotEmpty()) def.providerTags else QuroCloudTtsCatalog.ALL_EMOTION_TAGS
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 320.dp)
                        .border(1.dp, cs.outline, RoundedCornerShape(12.dp))
                        .clip(RoundedCornerShape(12.dp)),
                ) {
                    items(tagPool) { tag ->
                        val on = styleTags.contains(tag)
                        Row(
                            Modifier.fillMaxWidth()
                                .background(if (on) cs.primaryContainer.copy(alpha = 0.5f) else cs.surface)
                                .clickable { styleTags = if (on) styleTags - tag else styleTags + tag }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(tag, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Switch(checked = on, onCheckedChange = { styleTags = if (it) styleTags + tag else styleTags - tag })
                        }
                        if (tag != tagPool.last()) HorizontalDivider()
                    }
                }

                // 自定义风格标签
                if (customStyleTags.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.qk_01496), style = MaterialTheme.typography.labelSmall, color = cs.primary)
                    androidx.compose.foundation.layout.FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        customStyleTags.forEach { tag ->
                            InputChip(
                                selected = true,
                                onClick = { customStyleTags = customStyleTags - tag },
                                label = { Text(tag) },
                                trailingIcon = { Icon(Icons.Filled.Close, contentDescription = qstr(R.string.qk_00159), Modifier.size(14.dp)) },
                            )
                        }
                    }
                }
                var newTag by remember { mutableStateOf("") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    UnderlineField(
                        label = stringResource(R.string.qk_01497),
                        value = newTag,
                        onValueChange = { newTag = it },
                        placeholder = "",
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = {
                        val t = newTag.trim()
                        if (t.isNotBlank() && !customStyleTags.contains(t)) {
                            customStyleTags = customStyleTags + t
                            newTag = ""
                        }
                    }, colors = ButtonDefaults.buttonColors(containerColor = cs.surfaceVariant)) { Text(stringResource(R.string.qk_01498)) }
                }
                HorizontalDivider()
            }

            // ── 自定义音色（设计 / 复刻） ────────────────────────────
            if (def.cloneSupport && (def.id == "mimo" || def.id == "minimax" || def.id == "siliconflow")) {
                Text("自定义音色（${if (def.id == "mimo") "设计 / 复刻" else "音频复刻"}）", style = MaterialTheme.typography.titleSmall)
                if (customVoices.isNotEmpty()) {
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 200.dp)
                            .border(1.dp, cs.outline, RoundedCornerShape(12.dp))
                            .verticalScroll(rememberScrollState()),
                    ) {
                        customVoices.forEach { cv ->
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(cv.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        when (cv.type) {
                                            "clone" -> "复刻：${cv.cloneUri}${if (cv.registeredId.isNotBlank()) " · 已注册(${cv.registeredId.take(24)})" else ""}"
                                            else -> qstr(R.string.qk_01501, (cv.designText).toString())
                                        },
                                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { customVoices = customVoices - cv }) {
                                    Icon(Icons.Filled.Delete, contentDescription = qstr(R.string.qk_00091), tint = cs.error)
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                var cvName by remember { mutableStateOf("") }
                var cvType by remember { mutableStateOf(if (def.id == "mimo") "design" else "clone") }
                var cvDesc by remember { mutableStateOf("") }       // 设计文本
                var cvUri by remember { mutableStateOf("") }        // 复刻音频 URI/URL
                var cvNarration by remember { mutableStateOf("") }  // 复刻旁白文本
                UnderlineField(value = cvName, onValueChange = { cvName = it }, label = stringResource(R.string.qk_01502), placeholder = "", modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.qk_01503), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(8.dp))
                    if (def.id == "mimo") {
                        FilterChip(selected = cvType == "design", onClick = { cvType = "design" }, label = { Text(stringResource(R.string.qk_01504)) })
                        Spacer(Modifier.width(8.dp))
                    }
                    FilterChip(selected = cvType == "clone", onClick = { cvType = "clone" }, label = { Text(stringResource(R.string.qk_01505)) })
                }
                Spacer(Modifier.height(8.dp))
                if (cvType == "design") {
                    UnderlineField(
                        value = cvDesc,
                        onValueChange = { cvDesc = it },
                        label = stringResource(R.string.qk_01506),
                        placeholder = stringResource(R.string.qk_01507),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    // 复刻：导入/粘贴音频 + 旁白文本
                    val audioPicker = rememberLauncherForActivityResult(
                        ActivityResultContracts.GetContent()
                    ) { uri ->
                        if (uri != null) cvUri = uri.toString()
                    }
                    UnderlineField(
                        value = cvUri,
                        onValueChange = { cvUri = it },
                        label = stringResource(R.string.qk_01508),
                        placeholder = stringResource(R.string.qk_01509),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { audioPicker.launch("audio/*") }) { Text(stringResource(R.string.qk_01510)) }
                        Text(stringResource(R.string.qk_01511), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    }
                    if (cvUri.isNotBlank() && cvUri.startsWith("content://")) {
                        Text(stringResource(R.string.qk_01512), style = MaterialTheme.typography.labelSmall, color = cs.primary)
                    }
                    Spacer(Modifier.height(6.dp))
                    UnderlineField(
                        value = cvNarration,
                        onValueChange = { cvNarration = it },
                        label = stringResource(R.string.qk_01513),
                        placeholder = stringResource(R.string.qk_01514),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    if (cvName.isNotBlank()) {
                        val (uri, desc, narr) = if (cvType == "clone") Triple(cvUri.trim(), "", cvNarration.trim()) else Triple("", cvDesc.trim(), "")
                        when {
                            cvType == "clone" && uri.isBlank() -> status = qstr(R.string.qk_01515)
                            cvType == "clone" && def.id == "siliconflow" && narr.isBlank() -> status = qstr(R.string.qk_01516)
                            else -> {
                                customVoices = customVoices + CloudCustomVoice(
                                    name = cvName.trim(), type = cvType,
                                    designText = desc, cloneUri = uri, cloneText = narr, registeredId = "",
                                )
                                cvName = ""; cvDesc = ""; cvUri = ""; cvNarration = ""
                            }
                        }
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = cs.surfaceVariant)) { Text(stringResource(R.string.qk_01517)) }
                HorizontalDivider()
            }

            // ── 语音克隆（支持的服务商） ─────────────────────────────────
            if (def.cloneSupport) {
                Text(stringResource(R.string.qk_01518), style = MaterialTheme.typography.titleSmall)
                Card(
                    Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        val tip = when (def.id) {
                            "mimo" -> stringResource(R.string.qk_01519)
                            "minimax", "siliconflow" -> stringResource(R.string.qk_01520)
                            else -> stringResource(R.string.qk_01521, (def.name).toString())
                        }
                        Text(tip, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(R.string.qk_01522), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = cloneEnabled, onCheckedChange = { cloneEnabled = it })
                }
                HorizontalDivider()
            }

            // ── 试听文本 + 操作 ───────────────────────────────────────────
            Text(stringResource(R.string.qk_01523), style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = preview,
                onValueChange = { preview = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.qk_01524)) },
                minLines = 2, maxLines = 4, singleLine = false,
            )
            status?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium,
                    color = if (it.contains("失败") || it.contains("❌")) cs.error else androidx.compose.ui.graphics.Color(0xFF2E7D32))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            saving = true
                            try {
                                save()
                                status = qstr(R.string.qk_01525)
                                QuroCloudTts.play(ctx, preview.ifBlank { " " })
                                status = qstr(R.string.qk_01526)
                            } catch (e: Exception) {
                                status = qstr(R.string.qk_01527, (e.message).toString())
                            } finally {
                                saving = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.qk_01528)) }
                OutlinedButton(
                    onClick = { save() },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.qk_01529)) }
            }

            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.qk_01530),
                style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
            )
        }
    }
}