package com.ai.assistance.quro.ui

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
        status = "已保存并启用云模型服务 ✓"
        Toast.makeText(ctx, "云模型配置已保存", Toast.LENGTH_SHORT).show()
    }

    /** 删除当前服务商的已保存配置（删除已配置模型 / 服务商），回落到「未配置」状态。 */
    fun clearCurrentConfig() {
        QuroTtsProviderPrefs.clearConfig(ctx, providerId)
        loadFor(providerId)
        status = "已清除「${def.name}」的已保存配置"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("云模型配置 · 语音合成") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    if (saving) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Accent)
                    } else {
                        TextButton(onClick = { clearCurrentConfig() }) { Text("清除", color = cs.error) }
                        TextButton(onClick = { save() }) { Text("保存", color = Accent) }
                    }
                },
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "选择一个云端 TTS 服务商并填写所需参数。保存后自动启用「云模型服务」作为语音来源，聊天/语音球朗读立即走云端，无需再到「语音合成」里手动切换。",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
            )

            // ── 服务商选择（可折叠卡片 + 上下滑动） ───────────────────────
            Text("服务商", style = MaterialTheme.typography.titleSmall)
            Text(
                "点击卡片选择服务商，选中后展开查看所需参数；列表可上下滑动浏览全部 ${QuroTtsProviders.ALL.size} 家。",
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
                                "无需任何参数（免费可用）"
                            } else {
                                "必填：" + p.requiredFields.joinToString(" / ") { fk ->
                                    p.fields.firstOrNull { it.key == fk }?.label ?: fk
                                }
                            }
                            val cfgState = if (p.requiredFields.isEmpty()) {
                                "默认可用"
                            } else {
                                if (QuroTtsProviderPrefs.isConfiguredFor(ctx, p.id)) "已配置 ✓" else "未配置"
                            }
                            Text(
                                "$need · $cfgState",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (cfgState.startsWith("已配置") || p.requiredFields.isEmpty()) cs.primary else cs.error,
                            )
                        }
                    }
                    if (p.id != QuroTtsProviders.ALL.last().id) HorizontalDivider()
                }
            }

            HorizontalDivider()

            // ── 当前服务商参数 ───────────────────────────────────────────
            Text("「${def.name}」参数", style = MaterialTheme.typography.titleSmall)

            if (def.fields.isEmpty()) {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("该服务商无需任何密钥/参数即可使用（如 Edge TTS）。", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
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
            Text("音色 (Voice)", style = MaterialTheme.typography.titleSmall)
            val cloneVoices = customVoices.filter { it.type == "clone" }
            if (def.voices.isNotEmpty() || cloneVoices.isNotEmpty()) {
                var voiceMenu by remember { mutableStateOf(false) }
                val voiceLabel = if (voice.isBlank()) {
                    "请选择音色"
                } else if (voice.startsWith("custom::")) {
                    val cn = voice.removePrefix("custom::")
                    val cv = cloneVoices.firstOrNull { it.name == cn }
                    "复刻：$cn${if (cv?.registeredId?.isNotBlank() == true) " ✓已注册" else ""}"
                } else {
                    voice
                }
                ListItem(
                    headlineContent = { Text(voiceLabel) },
                    supportingContent = { Text("点击选择预置音色 / 已创建的复刻音色") },
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
                    label = "音色名称（自由填写）",
                    value = voice,
                    onValueChange = { voice = it },
                    placeholder = "如 alloy / 自定义网关音色 ID / 或上方选择复刻音色",
                    modifier = Modifier.fillMaxWidth(),
                )
                if (def.cloneSupport) {
                    Text(
                        "提示：启用「语音克隆」并在上方「自定义音色」中创建复刻条目后，可直接从「音色」下拉选择；也可在此填入官方平台创建的克隆音色 ID。",
                        style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                    )
                }
            }
            if (def.voices.isEmpty() && !def.voiceFreeText && cloneVoices.isEmpty()) {
                Text("该服务商无需指定音色。", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }

            // ── 输出格式 ─────────────────────────────────────────────────
            if (def.formatOptions.size > 1) {
                Text("输出格式", style = MaterialTheme.typography.titleSmall)
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
                        Text("流式输出 (Streaming)", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "实时逐句返回音频，降低首字延迟。已支持边收边播：Edge / 讯飞（WebSocket）与 MiMo / OpenAI / MiniMax（HTTP 流式）。火山 / 腾讯当前为整段合成后播放（其 WebSocket 流式在部分账号引发卡顿，暂未启用）。",
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
                Text("风格标签", style = MaterialTheme.typography.titleSmall)
                Text(
                    "每个标签独立开关：开启 = 允许 AI 在语音合成时自由组合该风格；关闭 = 禁用。默认全部关闭，按需开启，不会强制「全部使用」。",
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
                    Text("自定义标签", style = MaterialTheme.typography.labelSmall, color = cs.primary)
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
                                trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "移除", Modifier.size(14.dp)) },
                            )
                        }
                    }
                }
                var newTag by remember { mutableStateOf("") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    UnderlineField(
                        label = "添加自定义标签",
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
                    }, colors = ButtonDefaults.buttonColors(containerColor = cs.surfaceVariant)) { Text("添加") }
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
                                            else -> "设计：${cv.designText}"
                                        },
                                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { customVoices = customVoices - cv }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "删除", tint = cs.error)
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
                UnderlineField(value = cvName, onValueChange = { cvName = it }, label = "音色名称", placeholder = "", modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("类型：", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(8.dp))
                    if (def.id == "mimo") {
                        FilterChip(selected = cvType == "design", onClick = { cvType = "design" }, label = { Text("文字设计") })
                        Spacer(Modifier.width(8.dp))
                    }
                    FilterChip(selected = cvType == "clone", onClick = { cvType = "clone" }, label = { Text("音频复刻") })
                }
                Spacer(Modifier.height(8.dp))
                if (cvType == "design") {
                    UnderlineField(
                        value = cvDesc,
                        onValueChange = { cvDesc = it },
                        label = "音色描述（如：温柔的少女音）",
                        placeholder = "如：温柔的少女音，20-30岁女性",
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
                        label = "音频样本（导入/URI）",
                        placeholder = "点「导入」选择音频文件，或粘贴音频 URI/URL",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { audioPicker.launch("audio/*") }) { Text("📁 导入音频文件") }
                        Text("或粘贴公网可访问的音频 URL", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    }
                    if (cvUri.isNotBlank() && cvUri.startsWith("content://")) {
                        Text("✅ 已选择本地音频文件（零样本克隆）", style = MaterialTheme.typography.labelSmall, color = cs.primary)
                    }
                    Spacer(Modifier.height(6.dp))
                    UnderlineField(
                        value = cvNarration,
                        onValueChange = { cvNarration = it },
                        label = "参考音频旁白文本",
                        placeholder = "硅基流动复刻必需：参考音频对应的文字内容",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    if (cvName.isNotBlank()) {
                        val (uri, desc, narr) = if (cvType == "clone") Triple(cvUri.trim(), "", cvNarration.trim()) else Triple("", cvDesc.trim(), "")
                        when {
                            cvType == "clone" && uri.isBlank() -> status = "请先导入或粘贴复刻音频样本"
                            cvType == "clone" && def.id == "siliconflow" && narr.isBlank() -> status = "硅基流动复刻需填写「参考音频旁白文本」"
                            else -> {
                                customVoices = customVoices + CloudCustomVoice(
                                    name = cvName.trim(), type = cvType,
                                    designText = desc, cloneUri = uri, cloneText = narr, registeredId = "",
                                )
                                cvName = ""; cvDesc = ""; cvUri = ""; cvNarration = ""
                            }
                        }
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = cs.surfaceVariant)) { Text("添加自定义音色") }
                HorizontalDivider()
            }

            // ── 语音克隆（支持的服务商） ─────────────────────────────────
            if (def.cloneSupport) {
                Text("语音克隆 (Voice Cloning)", style = MaterialTheme.typography.titleSmall)
                Card(
                    Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        val tip = when (def.id) {
                            "mimo" -> "MiMo 为零样本内联复刻：在上方「自定义音色」添加「音频复刻」条目并选中即可，无需预注册，合成时直接内联音频样本。"
                            "minimax", "siliconflow" -> "为注册式复刻：在上方「自定义音色」添加「音频复刻」条目（硅基流动需填旁白文本）并选中，合成时会自动上传样本并创建克隆音色（首次联网，之后复用已注册 ID）。"
                            else -> "请先在「${def.name}」官方平台创建克隆音色，再于「音色」字段（自由填写）填入克隆音色 ID/名称即可调用。"
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
                    Text("启用语音克隆", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = cloneEnabled, onCheckedChange = { cloneEnabled = it })
                }
                HorizontalDivider()
            }

            // ── 试听文本 + 操作 ───────────────────────────────────────────
            Text("试听文本", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = preview,
                onValueChange = { preview = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("输入要朗读的文本") },
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
                                status = "正在试听…"
                                QuroCloudTts.play(ctx, preview.ifBlank { " " })
                                status = "试听已发起 ✅"
                            } catch (e: Exception) {
                                status = "试听失败 ❌\n${e.message}"
                            } finally {
                                saving = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    modifier = Modifier.weight(1f),
                ) { Text("保存并试听") }
                OutlinedButton(
                    onClick = { save() },
                    modifier = Modifier.weight(1f),
                ) { Text("仅保存") }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "提示：保存即自动启用「云模型服务」语音来源（如未填写必填项，状态会显示「未配置参数」，需在「语音合成 (TTS)」来源中选回本地系统）。",
                style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
            )
        }
    }
}
