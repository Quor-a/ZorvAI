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
        status = "已保存 ✓"
        Toast.makeText(ctx, "云模型配置已保存", Toast.LENGTH_SHORT).show()
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
                "选择一个云端 TTS 服务商并填写所需参数。配置后，在「语音合成」来源中选择「云模型服务」即可调用。",
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
                    OutlinedTextField(
                        value = value,
                        onValueChange = { fieldValues = fieldValues.toMutableMap().apply { put(f.key, it) } },
                        label = { Text(f.label) },
                        placeholder = if (f.placeholder.isNotBlank()) ({ Text(f.placeholder) }) else null,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (f.secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            // ── 音色 ─────────────────────────────────────────────────────
            Text("音色 (Voice)", style = MaterialTheme.typography.titleSmall)
            if (def.voices.isNotEmpty()) {
                var voiceMenu by remember { mutableStateOf(false) }
                ListItem(
                    headlineContent = { Text(if (voice.isBlank()) "请选择音色" else voice) },
                    supportingContent = { Text("点击选择预置音色") },
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
                }
            } else if (def.voiceFreeText && !(def.cloneSupport && cloneEnabled)) {
                OutlinedTextField(
                    value = voice,
                    onValueChange = { voice = it },
                    label = { Text("音色名称（自由填写）") },
                    placeholder = { Text("如 alloy / 自定义网关音色 ID") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
            } else {
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
                            "实时逐句返回音频，降低首字延迟。全部服务商默认开启。",
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
                    OutlinedTextField(
                        value = newTag,
                        onValueChange = { newTag = it },
                        label = { Text("添加自定义标签") },
                        modifier = Modifier.weight(1f), singleLine = true,
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

            // ── MiMo 自定义音色（设计 / 复刻） ────────────────────────────
            if (def.id == "mimo") {
                Text("自定义音色（MiMo 设计 / 复刻）", style = MaterialTheme.typography.titleSmall)
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
                                        if (cv.type == "clone") "复刻：${cv.cloneUri}" else "设计：${cv.designText}",
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
                var cvType by remember { mutableStateOf("design") }
                var cvText by remember { mutableStateOf("") }
                OutlinedTextField(value = cvName, onValueChange = { cvName = it }, label = { Text("音色名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("类型：", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = cvType == "design", onClick = { cvType = "design" }, label = { Text("文字设计") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = cvType == "clone", onClick = { cvType = "clone" }, label = { Text("音频复刻") })
                }
                Spacer(Modifier.height(8.dp))
                // ═─ 零样本音色复刻：文件导入（v184：用户反馈"零样本是导入不是 URI"） ═══
                val audioPicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.GetContent()
                ) { uri ->
                    if (uri != null) {
                        // 用户选择了本地音频文件 → 保存为 content:// URI 供合成时读取
                        cvText = uri.toString()
                    }
                }
                OutlinedTextField(
                    value = cvText,
                    onValueChange = { cvText = it },
                    label = {
                        Text(if (cvType == "clone") "音频样本（导入/URI）" else "音色描述（如：温柔的少女音）")
                    },
                    placeholder = {
                        Text(if (cvType == "clone") "点「导入」选择音频文件，或粘贴音频 URI/路径" else "如：温柔的少女音，20-30岁女性")
                    },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
                if (cvType == "clone") {
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { audioPicker.launch("audio/*") }) { Text("📁 导入音频文件") }
                        Text("或粘贴公网可访问的音频 URL", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    }
                    if (cvText.isNotBlank() && cvText.startsWith("content://")) {
                        Text("✅ 已选择本地音频文件（零样本克隆）", style = MaterialTheme.typography.labelSmall, color = cs.primary)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    if (cvName.isNotBlank() && cvText.isNotBlank()) {
                        customVoices = customVoices + CloudCustomVoice(cvName.trim(), cvType, cvText.trim(), if (cvType == "clone") cvText.trim() else "")
                        cvName = ""; cvText = ""
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
                        Text(
                            "该服务商支持语音克隆 / 声音复刻。请先在「${def.name}」官方平台创建克隆音色（如上传音频样本），再在下方或「音色」字段填入克隆音色 ID/名称即可调用。",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                        )
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
                if (cloneEnabled && def.id != "mimo") {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = voice,
                        onValueChange = { voice = it },
                        label = { Text("克隆音色 ID / 名称") },
                        placeholder = { Text("在平台创建的克隆音色标识") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    Text(
                        "提示：克隆音色 ID 即上方「音色」字段的值，启用克隆后请在此填写。",
                        style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                    )
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
                "提示：配置保存后，回到「语音合成 (TTS)」→ 语音来源选择「云模型服务」即可使用。未填写必填项时状态会显示「未配置参数」。",
                style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
            )
        }
    }
}
