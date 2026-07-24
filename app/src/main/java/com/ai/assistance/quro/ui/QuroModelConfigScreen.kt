package com.ai.assistance.quro.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import androidx.documentfile.provider.DocumentFile
import com.ai.assistance.quro.core.model.ApiProviderConfigs
import com.ai.assistance.quro.core.model.ApiProviderType
import com.ai.assistance.quro.core.model.QuroCustomProvider
import com.ai.assistance.quro.core.model.QuroCustomProviderRepository
import com.ai.assistance.quro.core.model.QuroLocalModel
import com.ai.assistance.quro.core.model.QuroLocalModelRepository
import com.ai.assistance.quro.core.model.QuroLocalModelType
import com.ai.assistance.quro.core.model.QuroSavedProfile
import com.ai.assistance.quro.core.model.QuroSavedProfileRepository
import com.ai.assistance.quro.core.model.toProfile
import com.ai.assistance.quro.core.network.QuroModelListResult
import com.ai.assistance.quro.ui.theme.Accent
import com.ai.assistance.quro.ui.theme.AccentSoft
import com.ai.assistance.quro.ui.theme.Line
import com.ai.assistance.quro.ui.theme.Line2
import com.ai.assistance.quro.ui.theme.Muted
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext

/** 模型配置界面（独立页，编辑排版风）：OpenAI 兼容接入点 + 服务商（含自定义）+ 本地离线模型 + 已保存预设。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuroModelConfigScreen(vm: QuroModelConfigViewModel, onBack: () -> Unit) {
    Scaffold { padding ->
        val cs = MaterialTheme.colorScheme
        Column(Modifier.fillMaxSize().padding(padding)) {
            // 顶栏：返回 + 眉标 + 衬线大标题
            Row(
                Modifier.fillMaxWidth().padding(16.dp, 14.dp, 12.dp, 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, Modifier.size(40.dp)) {
                    Icon(Icons.Filled.ArrowBack, "返回", Modifier.size(22.dp), tint = cs.onBackground)
                }
                Column(Modifier.padding(start = 4.dp)) {
                    Text("设置 · Settings", fontSize = 11.sp, color = Muted, letterSpacing = 0.5.sp)
                    Text(
                        "模型配置",
                        style = TextStyle(
                            fontFamily = FontFamily.Serif,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 22.sp,
                            color = cs.onBackground,
                        ),
                    )
                }
            }
            QuroModelConfigForm(vm, Modifier.fillMaxWidth().weight(1f))
        }
    }
}

/**
 * 模型配置表单（编辑排版风）：
 * - 服务商选择（内置 35 个 + 「其他供应商」持久化自定义厂商）
 * - 本地离线模型管理（MNN 上传 .mnn / llama.cpp 选择文件夹扫描 .gguf）
 * - 已保存预设
 * 编辑排版风：序号章节标题 + 下划线字段 + 温度滑块 + 工具轮次步进器。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuroModelConfigForm(
    vm: QuroModelConfigViewModel,
    modifier: Modifier = Modifier,
    scroll: Boolean = true,
) {
    val cfg by vm.cfg.collectAsState()
    val modelList by vm.modelList.collectAsState()
    val isFetchingModels by vm.isFetchingModels.collectAsState()
    var showProviderDialog by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var showLocalDialog by remember { mutableStateOf(false) }
    // ── 编辑已有预设 ──
    var showEditProfileDialog by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<QuroSavedProfile?>(null) }
    var editProfileName by remember { mutableStateOf("") }

    val ctx = LocalContext.current
    val profileRepo = remember { QuroSavedProfileRepository(ctx.applicationContext) }
    var savedProfiles by remember { mutableStateOf(profileRepo.loadAll()) }

    val cs = MaterialTheme.colorScheme

    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 8.dp)
            .then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier),
    ) {
        // ====== 01 导入模型 ======
        ChapterLabel("01", "导入模型")
        LocalModelEntryRow(vm = vm, onClick = { showLocalDialog = true })
        Spacer(Modifier.height(18.dp))

        // ====== 02 服务商 ======
        val selectedProvider = ApiProviderType.fromProviderTypeId(cfg.provider) ?: ApiProviderType.OPENAI
        val isLocal = cfg.provider == "MNN" || cfg.provider == "LLAMA_CPP"
        val selectedName = if (isLocal) {
            ""
        } else if (cfg.provider == "OTHER" && cfg.customProviderName.isNotBlank()) {
            cfg.customProviderName
        } else {
            getProviderDisplayName(selectedProvider)
        }
        ChapterLabel("02", "服务商")
        SettingsSelectorRow(
            title = "服务商",
            subtitle = if (cfg.provider == "OTHER" && cfg.customProviderName.isNotBlank())
                "已选自定义厂商：${cfg.customProviderName}" else "选择或自定义 API 服务商以自动填入默认地址",
            value = selectedName,
            color = getProviderColor(selectedProvider),
            onClick = { showProviderDialog = true },
        )
        Spacer(Modifier.height(18.dp))

        // ====== 03 连接 ======
        ChapterLabel("03", "连接")
        QuroField("Base URL", cfg.baseUrl, KeyboardOptions.Default) { vm.update { copy(baseUrl = it) } }
        Spacer(Modifier.height(12.dp))
        ApiKeyField(value = cfg.apiKey, onValueChange = { vm.update { copy(apiKey = it) } })
        Spacer(Modifier.height(18.dp))

        // ====== 04 参数 ======
        ChapterLabel("04", "参数")
        QuroField("模型名", cfg.model, KeyboardOptions.Default) { vm.update { copy(model = it) } }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { vm.fetchModels() }, enabled = !isFetchingModels) {
                Text(if (isFetchingModels) "拉取中…" else "拉取模型列表")
            }
            if (isFetchingModels) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        Spacer(Modifier.height(14.dp))

        // 温度（滑块）
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("温度", fontSize = 14.sp, color = cs.onSurface, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("%.2f".format(cfg.temperature), fontSize = 13.sp, color = Accent, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = cfg.temperature.coerceIn(0f, 1f),
            onValueChange = { vm.update { copy(temperature = it) } },
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = Accent,
                activeTrackColor = Accent,
                inactiveTrackColor = Line2,
            ),
        )
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuroField(
                "最大令牌", cfg.maxTokens.toString(),
                KeyboardOptions(keyboardType = KeyboardType.Number), Modifier.weight(1f),
            ) { vm.update { copy(maxTokens = it.toIntOrNull() ?: 4096) } }
            StepperField(
                "工具轮次", cfg.maxToolRounds,
                { vm.update { copy(maxToolRounds = it) } }, Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "0 = 不限制（默认）：工具调用不设次数上限，ReAct 循环持续直到模型给出最终答复；填正数则按该值封顶。内置 200 轮安全天花板防失控。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        )
        Spacer(Modifier.height(8.dp))
        QuroField(
            "上下文窗口", cfg.contextWindow.toString(),
            KeyboardOptions(keyboardType = KeyboardType.Number),
        ) { vm.update { copy(contextWindow = it.toIntOrNull() ?: 16000) } }
        Spacer(Modifier.height(10.dp))
        Text(
            "输入 token 预算（0=不限制）。长对话自动丢弃最旧轮次、始终保留身份/人格/工具指引，避免窗口撑爆导致丢失上下文或工具调用失效。小米 MiMo 建议 16000–32000。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        )

        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { showSaveDialog = true }) {
                Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("保存为预设")
            }
        }
        Spacer(Modifier.height(20.dp))

        // 保存配置（陶土强调色块）
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Accent)
                .clickable { vm.save() }.padding(14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("保存配置", fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(22.dp))

        // ====== 已保存预设 ======
        if (savedProfiles.isNotEmpty()) {
            ChapterLabel("已保存预设", savedProfiles.size.toString())
            savedProfiles.forEach { profile ->
                Card(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        // 头像圆（取名称首字母）
                        ProviderAvatar(null, profile.name.ifBlank { "?" }.firstOrNull()?.uppercase() ?: "?", 34.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(profile.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${profile.provider} · ${profile.model.ifBlank { "?" }} · ${profile.baseUrl}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        // 编辑按钮
                        IconButton(onClick = {
                            editingProfile = profile
                            editProfileName = profile.name
                            showEditProfileDialog = true
                        }) {
                            Icon(Icons.Filled.Edit, "编辑预设", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        TextButton(onClick = { profileRepo.applyToConfig(profile, vm.repo); vm.reload() }) { Text("加载") }
                        IconButton(onClick = { profileRepo.delete(profile.id); savedProfiles = profileRepo.loadAll() }) {
                            Icon(Icons.Filled.Delete, "删除预设", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showProviderDialog) {
        ApiProviderDialogWithCustom(
            currentCustomName = cfg.customProviderName,
            onDismissRequest = { showProviderDialog = false },
            onProviderSelected = { type ->
                val defaultModel = ApiProviderConfigs.getDefaultModelName(type)
                val defaultEndpoint = ApiProviderConfigs.getDefaultApiEndpoint(type)
                vm.update {
                    var c = copy(provider = type.name, customProviderName = "")
                    if (defaultModel.isNotEmpty()) c = c.copy(model = defaultModel)
                    if (defaultEndpoint.isNotEmpty()) c = c.copy(baseUrl = defaultEndpoint)
                    c
                }
                showProviderDialog = false
                vm.fetchModels()
            },
            onCustomSelected = { cp ->
                vm.update {
                    copy(provider = "OTHER", baseUrl = cp.baseUrl, customProviderName = cp.name,
                        model = if (cp.defaultModel.isNotBlank()) cp.defaultModel else model)
                }
                showProviderDialog = false
            },
        )
    }

    if (showSaveDialog) {
        var presetName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            confirmButton = {
                Button(onClick = {
                    val name = presetName.trim().ifBlank { "预设 ${savedProfiles.size + 1}" }
                    profileRepo.save(cfg.toProfile(name))
                    savedProfiles = profileRepo.loadAll()
                    showSaveDialog = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showSaveDialog = false }) { Text("取消") } },
            title = { Text("保存为预设") },
            text = {
                OutlinedTextField(value = presetName, onValueChange = { presetName = it },
                    label = { Text("预设名称") }, placeholder = { Text("例如：我的 DeepSeek") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
            },
        )
    }

    // ── 编辑已有预设弹窗 ──
    if (showEditProfileDialog && editingProfile != null) {
        val ep = editingProfile!!
        AlertDialog(
            onDismissRequest = { showEditProfileDialog = false; editingProfile = null },
            confirmButton = {
                Button(onClick = {
                    val updated = ep.copy(name = editProfileName.trim().ifBlank { ep.name })
                    profileRepo.save(updated)
                    savedProfiles = profileRepo.loadAll()
                    showEditProfileDialog = false
                    editingProfile = null
                }, enabled = editProfileName.isNotBlank()) { Text("保存修改") }
            },
            dismissButton = { TextButton(onClick = { showEditProfileDialog = false; editingProfile = null }) { Text("取消") } },
            title = { Text("编辑预设", style = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = editProfileName, onValueChange = { editProfileName = it },
                        label = { Text("预设名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    // 只读信息展示
                    Text("服务商：${ep.provider}", style = MaterialTheme.typography.bodySmall, color = Muted)
                    Text("模型：${ep.model.ifBlank { "未设置" }}", style = MaterialTheme.typography.bodySmall, color = Muted)
                    Text("地址：${ep.baseUrl.ifBlank { "未设置" }}", style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 1)
                    Text("修改名称后点「保存修改」即可更新预设显示名。其他字段请删除后重新创建。", style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            },
        )
    }

    if (showLocalDialog) {
        LocalModelDialog(vm = vm, onDismiss = { showLocalDialog = false })
    }

    // ====== 拉取结果弹窗 ======
    val ml = modelList
    if (ml != null) {
        when (ml) {
            is QuroModelListResult.Success -> {
                AlertDialog(onDismissRequest = { vm.clearModelList() }, confirmButton = {},
                    title = { Text("选择模型 (${ml.models.size}个)") },
                    text = {
                        if (ml.models.isEmpty()) Text("未获取到模型，请检查地址 / 密钥。")
                        else LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                            items(ml.models) { id ->
                                TextButton(onClick = { vm.update { copy(model = id) }; vm.clearModelList() },
                                    modifier = Modifier.fillMaxWidth()) {
                                    Text(text = id, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                                }
                            }
                        }
                    })
            }
            is QuroModelListResult.Error -> {
                AlertDialog(onDismissRequest = { vm.clearModelList() },
                    confirmButton = { TextButton(onClick = { vm.clearModelList() }) { Text("确定") } },
                    title = { Text("拉取失败") }, text = { Text(ml.message) })
            }
        }
    }
}


// ==================== 入口行（编辑排版风） ====================

@Composable
private fun LocalModelEntryRow(vm: QuroModelConfigViewModel, onClick: () -> Unit) {
    val cfg by vm.cfg.collectAsState()
    val cs = MaterialTheme.colorScheme
    val isLocal = cfg.provider == "MNN" || cfg.provider == "LLAMA_CPP"
    val title = if (isLocal) "当前：${cfg.provider} · ${cfg.model.ifBlank { "未选模型" }}" else "未选择本地模型"
    val sub = if (isLocal) "MNN / llama.cpp 本地模型" else "MNN .mnn / llama.cpp 文件夹"
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.surface)
            .border(1.dp, if (isLocal) Accent else Line, RoundedCornerShape(14.dp))
            .clickable { onClick() }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(AccentSoft), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Folder, null, Modifier.size(20.dp), tint = Accent)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = cs.onSurface, fontWeight = FontWeight.SemiBold)
            Text(sub, fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 2.dp))
        }
        Icon(Icons.Filled.ChevronRight, null, Modifier.size(16.dp), tint = Muted)
    }
}

// ==================== 本地离线模型管理弹窗 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalModelDialog(vm: QuroModelConfigViewModel, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { QuroLocalModelRepository(ctx.applicationContext) }
    var models by remember { mutableStateOf(repo.loadAll()) }

    // MNN：选择 .mnn 文件 → 复制到私有目录
    val mnnPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            val id = UUID.randomUUID().toString()
            val fileName = queryDisplayName(ctx, it).removeSuffix(".mnn").removeSuffix(".MNN").ifBlank { id }
            val path = copyLocalModelFile(ctx, it, id, "mnn")
            if (path != null) {
                repo.upsert(QuroLocalModel(id = id, type = QuroLocalModelType.MNN, name = fileName, path = path, modelNames = listOf(fileName)))
                models = repo.loadAll()
            }
        }
    }
    // llama.cpp：选择文件夹 → 扫描 .gguf
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let {
            val id = UUID.randomUUID().toString()
            val folderName = DocumentFile.fromTreeUri(ctx, it)?.name ?: "llama-folder"
            val gguf = scanGgufFromTree(ctx, it)
            repo.upsert(QuroLocalModel(id = id, type = QuroLocalModelType.LLAMA_CPP, name = folderName,
                path = it.toString(), modelNames = gguf))
            models = repo.loadAll()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        title = { Text("本地离线模型") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { mnnPicker.launch("*/*") }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("添加 MNN 模型")
                    }
                    Button(onClick = { folderPicker.launch(null) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Folder, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("llama.cpp 文件夹")
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (models.isEmpty()) {
                    Text("还没有本地模型。添加 MNN 的 .mnn 文件，或选择含 .gguf 的 llama.cpp 文件夹。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                models.forEach { m ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .clickable {
                            // 选为当前模型：写入 cfg（provider=类型, 模型名=首个可用模型, 路径）
                            vm.update {
                                copy(provider = m.type.name, localModelPath = m.path,
                                    model = m.modelNames.firstOrNull() ?: m.name, customProviderName = "")
                            }
                            onDismiss()
                        }) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${if (m.type == QuroLocalModelType.LLAMA_CPP) "llama.cpp" else "MNN"} · ${m.name}",
                                    style = MaterialTheme.typography.bodyMedium)
                                Text("可用模型：${if (m.modelNames.isEmpty()) "（无）" else m.modelNames.joinToString(", ")}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            IconButton(onClick = {
                                repo.delete(m.id); models = repo.loadAll()
                            }) {
                                Icon(Icons.Filled.Delete, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("提示：本地模型需接入原生推理运行时（MNN / llama.cpp AAR）才能真正执行；当前已登记并可在模型选择中切换，执行待接入。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

/** 查询 content uri 的显示名。 */
private fun queryDisplayName(ctx: Context, uri: Uri): String {
    return runCatching {
        ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: ""
}

/** 复制选中的本地模型文件到私有目录，返回绝对路径。 */
private fun copyLocalModelFile(ctx: Context, uri: Uri, id: String, ext: String): String? {
    return runCatching {
        val dir = File(ctx.filesDir, "quro_local_models"); dir.mkdirs()
        val dst = File(dir, "$id.$ext")
        ctx.contentResolver.openInputStream(uri)?.use { input -> dst.outputStream().use { out -> input.copyTo(out) } }
        dst.absolutePath
    }.getOrNull()
}

/** 从 document tree 扫描 .gguf 文件名（不含扩展名）。 */
private fun scanGgufFromTree(ctx: Context, treeUri: Uri): List<String> {
    val tree = DocumentFile.fromTreeUri(ctx, treeUri) ?: return emptyList()
    return tree.listFiles()
        .filter { it.isFile && (it.name ?: "").endsWith(".gguf", ignoreCase = true) }
        .map { (it.name ?: "").removeSuffix(".gguf").removeSuffix(".GGUF") }
}

// ==================== 服务商选择对话框（内置 + 自定义「其他供应商」） ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApiProviderDialogWithCustom(
    currentCustomName: String,
    onDismissRequest: () -> Unit,
    onProviderSelected: (ApiProviderType) -> Unit,
    onCustomSelected: (QuroCustomProvider) -> Unit,
) {
    val ctx = LocalContext.current
    val customRepo = remember { QuroCustomProviderRepository(ctx.applicationContext) }
    var customProviders by remember { mutableStateOf(customRepo.loadAll()) }
    val builtIn = remember { ApiProviderType.values().filter { it != ApiProviderType.OTHER } }
    var searchQuery by remember { mutableStateOf("") }
    var showCustomForm by remember { mutableStateOf(false) }

    val filtered = remember(searchQuery) {
        if (searchQuery.isEmpty()) builtIn
        else builtIn.filter { getProviderDisplayName(it).contains(searchQuery, ignoreCase = true) || it.name.contains(searchQuery, ignoreCase = true) }
    }

    if (showCustomForm) {
        var cName by remember { mutableStateOf("") }
        var cUrl by remember { mutableStateOf("") }
        var cModel by remember { mutableStateOf("") }
        // 头像（本地到弹窗）
        var dialogAvatar by remember { mutableStateOf<String?>(null) }
        val dialogPickAvatar = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            dialogAvatar = uri?.toString()
        }
        AlertDialog(
            onDismissRequest = { showCustomForm = false },
            confirmButton = {
                Button(enabled = cName.trim().isNotEmpty() && cUrl.trim().isNotEmpty(), onClick = {
                    val cp = QuroCustomProvider(id = UUID.randomUUID().toString(), name = cName.trim(),
                        baseUrl = cUrl.trim(), defaultModel = cModel.trim(), requiresApiKey = true,
                        avatar = dialogAvatar)
                    customRepo.upsert(cp)
                    customProviders = customRepo.loadAll()
                    onCustomSelected(cp)
                    showCustomForm = false
                }) { Text("添加并选择") }
            },
            dismissButton = { TextButton(onClick = { showCustomForm = false }) { Text("返回") } },
            title = { Text("添加其他供应商", style = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 头像行
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProviderAvatar(dialogAvatar, cName.firstOrNull()?.uppercase() ?: "+", 48.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            TextButton(onClick = { dialogPickAvatar.launch("image/*") }) {
                                Text("上传头像", fontSize = 13.sp, color = Accent, fontWeight = FontWeight.SemiBold)
                            }
                            if (dialogAvatar != null) {
                                TextButton(onClick = { dialogAvatar = null }) {
                                    Text("清除头像", fontSize = 12.sp, color = Muted)
                                }
                            }
                        }
                    }
                    OutlinedTextField(value = cName, onValueChange = { cName = it }, label = { Text("供应商名称") },
                        placeholder = { Text("例如：我的私有服务") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = cUrl, onValueChange = { cUrl = it }, label = { Text("Base URL（基址）") },
                        placeholder = { Text("https://api.example.com/v1") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = cModel, onValueChange = { cModel = it }, label = { Text("默认模型名（可选）") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("添加后会出现在列表里，可随时重新选择；选择即回填 Base URL。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
    } else {
        BasicAlertDialog(onDismissRequest = onDismissRequest) {
            Surface(Modifier.fillMaxWidth().heightIn(max = 520.dp), shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp, shadowElevation = 8.dp) {
                Column(Modifier.padding(16.dp)) {
                    Text(text = "选择服务商", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp))
                    OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it },
                        placeholder = { Text("搜索服务商", style = MaterialTheme.typography.bodySmall) },
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        trailingIcon = { if (searchQuery.isNotEmpty()) IconButton({ searchQuery = "" }, Modifier.size(36.dp)) { Icon(Icons.Filled.Clear, null) } },
                        singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), shape = RoundedCornerShape(8.dp))

                    LazyColumn(Modifier.weight(1f)) {
                        // 自定义「其他供应商」分组
                        if (customProviders.isNotEmpty()) {
                            item {
                                Text("其他供应商", style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 4.dp))
                            }
                            items(customProviders) { cp ->
                                Surface(Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                    .clickable { onCustomSelected(cp) }, shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)) {
                                    Row(Modifier.padding(vertical = 12.dp, horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        // 有头像显示圆头像，否则显示首字母
                                        ProviderAvatar(cp.avatar, cp.name.firstOrNull()?.uppercase() ?: "?", 32.dp)
                                        Spacer(Modifier.width(16.dp))
                                        Column {
                                            Text(cp.name, style = MaterialTheme.typography.bodyLarge)
                                            Text(cp.baseUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                        }
                                        Spacer(Modifier.weight(1f))
                                        IconButton(onClick = { customRepo.delete(cp.id); customProviders = customRepo.loadAll() }) {
                                            Icon(Icons.Filled.Delete, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                        }
                        // 内置厂商
                        items(filtered) { provider ->
                            Surface(Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                .clickable { onProviderSelected(provider) }, shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)) {
                                Row(Modifier.padding(vertical = 12.dp, horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(32.dp).background(getProviderColor(provider), CircleShape), contentAlignment = Alignment.Center) {
                                        Text(getProviderDisplayName(provider).firstOrNull()?.toString() ?: "?", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.bodyMedium)
                                    }
                                    Spacer(Modifier.width(16.dp))
                                    Text(getProviderDisplayName(provider), style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    }

                    Surface(Modifier.fillMaxWidth().padding(top = 8.dp)
                        .clickable { showCustomForm = true }, shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)) {
                        Row(Modifier.padding(vertical = 12.dp, horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Add, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Text("添加其他供应商", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                        }
                    }

                    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismissRequest) { Text("取消") }
                    }
                }
            }
        }
    }
}

// ==================== 服务商展示名（硬编码中文） ====================

private fun getProviderDisplayName(provider: ApiProviderType): String =
    when (provider) {
        ApiProviderType.OPENAI -> "OpenAI"
        ApiProviderType.OPENAI_RESPONSES -> "OpenAI Responses"
        ApiProviderType.OPENAI_RESPONSES_GENERIC -> "OpenAI Responses (自定义)"
        ApiProviderType.OPENAI_GENERIC -> "OpenAI 兼容 (自定义)"
        ApiProviderType.ANTHROPIC -> "Anthropic Claude"
        ApiProviderType.ANTHROPIC_GENERIC -> "Anthropic 兼容 (自定义)"
        ApiProviderType.GOOGLE -> "Google Gemini"
        ApiProviderType.GEMINI_GENERIC -> "Gemini 兼容 (自定义)"
        ApiProviderType.BAIDU -> "百度文心一言"
        ApiProviderType.ALIYUN -> "阿里云通义千问"
        ApiProviderType.XUNFEI -> "讯飞星火"
        ApiProviderType.ZHIPU -> "智谱 GLM"
        ApiProviderType.BAICHUAN -> "百川大模型"
        ApiProviderType.MOONSHOT -> "月之暗面 Kimi"
        ApiProviderType.MIMO -> "小米 MiMo"
        ApiProviderType.DEEPSEEK -> "DeepSeek"
        ApiProviderType.MISTRAL -> "Mistral AI"
        ApiProviderType.SILICONFLOW -> "硅基流动"
        ApiProviderType.IFLOW -> "iFlow"
        ApiProviderType.OPENROUTER -> "OpenRouter"
        ApiProviderType.FOUR_ROUTER -> "4Router"
        ApiProviderType.NOUS_PORTAL -> "Nous Portal"
        ApiProviderType.INFINIAI -> "无问芯穹"
        ApiProviderType.ALIPAY_BAILING -> "支付宝百灵"
        ApiProviderType.DOUBAO -> "火山豆包"
        ApiProviderType.NVIDIA -> "NVIDIA NIM"
        ApiProviderType.LMSTUDIO -> "LM Studio (本地)"
        ApiProviderType.OLLAMA -> "Ollama (本地)"
        ApiProviderType.OPENAI_LOCAL -> "OpenAI 兼容 (本地)"
        ApiProviderType.MNN -> "MNN (本地)"
        ApiProviderType.LLAMA_CPP -> "llama.cpp (本地)"
        ApiProviderType.PPINFRA -> "派欧云"
        ApiProviderType.NOVITA -> "Novita AI"
        ApiProviderType.OTHER -> "其他 / 自定义"
    }

@Composable
private fun getProviderColor(provider: ApiProviderType): Color =
    when (provider) {
        ApiProviderType.OPENAI -> MaterialTheme.colorScheme.primary
        ApiProviderType.OPENAI_RESPONSES -> MaterialTheme.colorScheme.primary.copy(alpha = 0.92f)
        ApiProviderType.OPENAI_RESPONSES_GENERIC -> MaterialTheme.colorScheme.primary.copy(alpha = 0.88f)
        ApiProviderType.OPENAI_GENERIC -> MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
        ApiProviderType.ANTHROPIC -> MaterialTheme.colorScheme.tertiary
        ApiProviderType.ANTHROPIC_GENERIC -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f)
        ApiProviderType.GOOGLE -> MaterialTheme.colorScheme.secondary
        ApiProviderType.GEMINI_GENERIC -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.9f)
        ApiProviderType.BAIDU -> MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
        ApiProviderType.ALIYUN -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.8f)
        ApiProviderType.XUNFEI -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f)
        ApiProviderType.ZHIPU -> MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
        ApiProviderType.BAICHUAN -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)
        ApiProviderType.MOONSHOT -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f)
        ApiProviderType.MIMO -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.66f)
        ApiProviderType.DEEPSEEK -> MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        ApiProviderType.MISTRAL -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.65f)
        ApiProviderType.SILICONFLOW -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f)
        ApiProviderType.IFLOW -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.55f)
        ApiProviderType.OPENROUTER -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.6f)
        ApiProviderType.FOUR_ROUTER -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.56f)
        ApiProviderType.NOUS_PORTAL -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.52f)
        ApiProviderType.INFINIAI -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        ApiProviderType.ALIPAY_BAILING -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.45f)
        ApiProviderType.DOUBAO -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)
        ApiProviderType.NVIDIA -> MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)
        ApiProviderType.LMSTUDIO -> MaterialTheme.colorScheme.tertiary
        ApiProviderType.OLLAMA -> MaterialTheme.colorScheme.primary.copy(alpha = 0.78f)
        ApiProviderType.OPENAI_LOCAL -> MaterialTheme.colorScheme.primary.copy(alpha = 0.82f)
        ApiProviderType.MNN -> MaterialTheme.colorScheme.secondary
        ApiProviderType.LLAMA_CPP -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.9f)
        ApiProviderType.PPINFRA -> MaterialTheme.colorScheme.primaryContainer
        ApiProviderType.NOVITA -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.75f)
        ApiProviderType.OTHER -> MaterialTheme.colorScheme.surfaceVariant
    }

// ==================== 头像组件（圆形头像：有图显图、无图显首字母） ====================

/** 异步加载 content uri 位图（避免在主线程解码）。 */
@Composable
private fun rememberProviderBitmap(uri: String?): ImageBitmap? {
    val context = LocalContext.current
    var bmp by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
    androidx.compose.runtime.LaunchedEffect(uri) {
        bmp = withContext(Dispatchers.IO) {
            if (uri.isNullOrEmpty()) null
            else runCatching {
                context.contentResolver.openInputStream(Uri.parse(uri))?.use { stream ->
                    BitmapFactory.decodeStream(stream)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }
    return bmp
}

/**
 * 有头像显示圆形头像图片，否则显示标记字母。
 * 用于自定义供应商列表和添加/编辑供应商弹窗中的头像预览。
 */
@Composable
private fun ProviderAvatar(avatar: String?, mark: String, size: Dp) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.size(size).clip(CircleShape).background(AccentSoft), contentAlignment = Alignment.Center) {
        val bmp = rememberProviderBitmap(avatar)
        if (bmp != null) {
            Image(bitmap = bmp, contentDescription = null, modifier = Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
        } else {
            Text(mark.ifBlank { "?" }, fontSize = 14.sp, color = Accent, fontWeight = FontWeight.SemiBold)
        }
    }
}

