package com.ai.assistance.quro.ui
import androidx.compose.ui.res.stringResource
import com.ai.assistance.quro.R
import com.ai.assistance.quro.util.qstr

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
import com.ai.assistance.quro.core.model.QuroGgufNaming
import com.ai.assistance.quro.core.model.QuroLocalModelType
import com.ai.assistance.quro.core.model.QuroSavedProfile
import com.ai.assistance.quro.core.model.QuroSavedProfileRepository
import com.ai.assistance.quro.core.model.toProfile
import com.ai.assistance.quro.core.model.localModelCapabilitySummary
import com.ai.assistance.quro.core.network.LocalModelLoaders
import com.ai.assistance.quro.core.network.LocalModelLoader
import com.ai.assistance.quro.core.network.QuroLocalEnginePrefs
import com.ai.assistance.quro.core.network.QuroModelListResult
import com.ai.assistance.quro.util.QuroDiag
import com.ai.assistance.quro.ui.theme.Accent
import com.ai.assistance.quro.ui.theme.AccentSoft
import com.ai.assistance.quro.ui.theme.Line
import com.ai.assistance.quro.ui.theme.Line2
import com.ai.assistance.quro.ui.theme.Muted
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
                    Icon(Icons.Filled.ArrowBack, stringResource(R.string.qk_00143), Modifier.size(22.dp), tint = cs.onBackground)
                }
                Column(Modifier.padding(start = 4.dp)) {
                    Text(stringResource(R.string.qk_02102), fontSize = 11.sp, color = Muted, letterSpacing = 0.5.sp)
                    Text(stringResource(R.string.qk_02103),
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

    // 进入模型配置界面时只读取本地缓存的模型列表，不自动联网（手动拉取仍由 fetchModels() 触发）。
    LaunchedEffect(Unit) { vm.loadCachedModels() }

    val cs = MaterialTheme.colorScheme

    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 8.dp)
            .then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier),
    ) {
        // ====== 01 导入模型 ======
        ChapterLabel("01", stringResource(R.string.qk_02104))
        LocalModelEntryRow(vm = vm, onClick = { showLocalDialog = true })
        Spacer(Modifier.height(18.dp))

        // ====== 02 服务商 / 03 连接 ======
        // 本地离线模型（MNN / llama.cpp）与云端模型严格隔离：本地模式隐藏「服务商 / Base URL / Api Key」，
        // 仅在 01 导入模型管理器里配置，避免和云端模型混在一起。
        val isLocal = cfg.provider == "MNN" || cfg.provider == "LLAMA_CPP"
        if (isLocal) {
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(cs.surface).border(1.dp, Accent, RoundedCornerShape(14.dp)).padding(14.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(AccentSoft), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Folder, null, Modifier.size(20.dp), tint = Accent)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.qk_02105), fontSize = 14.sp, color = cs.onSurface, fontWeight = FontWeight.SemiBold)
                            Text("${cfg.provider} · ${cfg.model.ifBlank { "未选模型" }}", fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "模型路径：${cfg.localModelPath.ifBlank { "未设置" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.qk_02107),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = {
                        vm.update {
                            copy(provider = "OPENAI", localModelPath = "", model = "gpt-4o-mini",
                                baseUrl = "https://api.openai.com/v1", customProviderName = "")
                        }
                    }) { Text(stringResource(R.string.qk_02108)) }
                }
            }
            Spacer(Modifier.height(18.dp))
        } else {
            val selectedProvider = ApiProviderType.fromProviderTypeId(cfg.provider) ?: ApiProviderType.OPENAI
            val selectedName = if (cfg.provider == "OTHER" && cfg.customProviderName.isNotBlank()) {
                cfg.customProviderName
            } else {
                getProviderDisplayName(selectedProvider)
            }
            ChapterLabel("02", stringResource(R.string.qk_01472))
            SettingsSelectorRow(
                title = stringResource(R.string.qk_01472),
                subtitle = if (cfg.provider == "OTHER" && cfg.customProviderName.isNotBlank())
                    stringResource(R.string.qk_02109, (cfg.customProviderName).toString()) else stringResource(R.string.qk_02110),
                value = selectedName,
                color = getProviderColor(selectedProvider),
                onClick = { showProviderDialog = true },
            )
            Spacer(Modifier.height(18.dp))

            // ====== 03 连接 ======
            ChapterLabel("03", stringResource(R.string.qk_02111))
            QuroField("Base URL", cfg.baseUrl, KeyboardOptions.Default) { vm.update { copy(baseUrl = it) } }
            Spacer(Modifier.height(12.dp))
            ApiKeyField(value = cfg.apiKey, onValueChange = { vm.update { copy(apiKey = it) } })
            Spacer(Modifier.height(18.dp))
        }

        // ====== 04 参数 ======
        ChapterLabel("04", stringResource(R.string.qk_00142))
        QuroField(stringResource(R.string.qk_02112), cfg.model, KeyboardOptions.Default) { vm.update { copy(model = it) } }
        Spacer(Modifier.height(6.dp))
        if (!isLocal) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { vm.fetchModels() }, enabled = !isFetchingModels && cfg.baseUrl.isNotBlank()) {
                    Text(if (isFetchingModels) stringResource(R.string.qk_02113) else stringResource(R.string.qk_02114))
                }
                if (isFetchingModels) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            Spacer(Modifier.height(14.dp))
        }

        if (isLocal) {
            // ══════════════ 本地离线模型独立参数（与云端完全隔离） ══════════════
            Text(stringResource(R.string.qk_02115),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )

            // 本地温度（滑块）
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.qk_02116), fontSize = 14.sp, color = cs.onSurface, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("%.2f".format(cfg.localTemperature), fontSize = 13.sp, color = Accent, fontWeight = FontWeight.SemiBold)
            }
            Slider(
                value = cfg.localTemperature.coerceIn(0f, 1f),
                onValueChange = { vm.update { copy(localTemperature = it) } },
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = Accent,
                    activeTrackColor = Accent,
                    inactiveTrackColor = Line2,
                ),
            )
            Spacer(Modifier.height(6.dp))

            QuroField(
                stringResource(R.string.qk_02117), cfg.localMaxTokens.toString(),
                KeyboardOptions(keyboardType = KeyboardType.Number),
            ) { vm.update { copy(localMaxTokens = it.toIntOrNull() ?: 512) } }
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.qk_02118),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))

            // 本地工具调用开关
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = cfg.localEnableTools, onCheckedChange = { vm.update { copy(localEnableTools = it) } })
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.qk_02119), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.qk_02120),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

            // 本地引擎独立进程开关（进程隔离，硬规则第 1 条）
            //
            // 为什么把这个决定权交给用户而不是直接切默认值：
            //   进程隔离是**运行期行为变更** —— 推理从主进程搬到 `:llm` 进程，
            //   绑定、回调、取消、OOM 降级都是新的执行路径，必须真机验证过才敢做默认。
            //   保留开关意味着某个机型上隔离路径有问题时，用户自己能切回来，
            //   而不是只能等下一个版本。这是这类底层改造应有的退路。
            val isolatedNow = remember { mutableStateOf(QuroLocalEnginePrefs.isIsolated(ctx)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = isolatedNow.value,
                    onCheckedChange = {
                        isolatedNow.value = it
                        QuroLocalEnginePrefs.set(ctx, it)
                    },
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.qk_03905), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.qk_03906),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

            // L5 · 温控自适应开关。
            //
            // 与上面的进程隔离放在一起是有意的：两者都是「改变本地推理运行期行为」
            // 的开关，用户要能在一个地方同时看到、同时回退。区别在于出问题的表现：
            // 隔离失败会崩，温控失败只是变慢 —— 所以这里默认关闭。
            val thermalNow = remember { mutableStateOf(QuroLocalEnginePrefs.isThermalAdaptive()) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = thermalNow.value,
                    onCheckedChange = {
                        thermalNow.value = it
                        QuroLocalEnginePrefs.setThermalAdaptive(it)
                    },
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.qk_03907), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.qk_03908),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

            Text(stringResource(R.string.qk_02121),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // ══════════════ 云端模型参数 ══════════════
            // 温度（滑块）
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.qk_02116), fontSize = 14.sp, color = cs.onSurface, fontWeight = FontWeight.SemiBold)
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
                    stringResource(R.string.qk_02122), cfg.maxTokens.toString(),
                    KeyboardOptions(keyboardType = KeyboardType.Number), Modifier.weight(1f),
                ) { vm.update { copy(maxTokens = it.toIntOrNull() ?: 65536) } }
                StepperField(
                    stringResource(R.string.qk_02123), cfg.maxToolRounds,
                    { vm.update { copy(maxToolRounds = it) } }, Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.qk_02124),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            QuroField(
                stringResource(R.string.qk_02125), cfg.contextWindow.toString(),
                KeyboardOptions(keyboardType = KeyboardType.Number),
            ) { vm.update { copy(contextWindow = it.toIntOrNull() ?: 262144) } }
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.qk_02126),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            )
            Spacer(Modifier.height(12.dp))
            // ═══ 云端工具调用开关（v453 补回：此前云端段缺失 enableTools / useFullTools 开关，
            //    若活跃配置被置为关闭则无任何入口可重新打开 → 模型拿不到工具、不会主动调用）═══
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = cfg.enableTools, onCheckedChange = { vm.update { copy(enableTools = it) } })
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.qk_02119), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.qk_02127),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = cfg.useFullTools, onCheckedChange = { vm.update { copy(useFullTools = it) } })
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.qk_02128), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.qk_02129),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { showSaveDialog = true }) {
                Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.qk_02130))
            }
        }
        Spacer(Modifier.height(20.dp))

        // 保存配置（陶土强调色块）
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Accent)
                .clickable { vm.save() }.padding(14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.qk_02131), fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(22.dp))

        // ====== 已保存预设 ======
        if (savedProfiles.isNotEmpty()) {
            ChapterLabel(stringResource(R.string.qk_00037), savedProfiles.size.toString())
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
                            Icon(Icons.Filled.Edit, qstr(R.string.qk_02132), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        TextButton(onClick = { profileRepo.applyToConfig(profile, vm.repo); vm.reload() }) { Text(qstr(R.string.qk_02133)) }
                        IconButton(onClick = { profileRepo.delete(profile.id); savedProfiles = profileRepo.loadAll() }) {
                            Icon(Icons.Filled.Delete, qstr(R.string.qk_02134), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
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
                    val name = presetName.trim().ifBlank { qstr(R.string.qk_02135, (savedProfiles.size + 1).toString()) }
                    profileRepo.save(cfg.toProfile(name))
                    savedProfiles = profileRepo.loadAll()
                    showSaveDialog = false
                }) { Text(stringResource(R.string.qk_00198)) }
            },
            dismissButton = { TextButton(onClick = { showSaveDialog = false }) { Text(stringResource(R.string.qk_00011)) } },
            title = { Text(stringResource(R.string.qk_02130)) },
            text = {
                OutlinedTextField(value = presetName, onValueChange = { presetName = it },
                    label = { Text(stringResource(R.string.qk_02136)) }, placeholder = { Text(stringResource(R.string.qk_02137)) },
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
                }, enabled = editProfileName.isNotBlank()) { Text(stringResource(R.string.qk_02138)) }
            },
            dismissButton = { TextButton(onClick = { showEditProfileDialog = false; editingProfile = null }) { Text(qstr(R.string.qk_00011)) } },
            title = { Text(stringResource(R.string.qk_02132), style = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = editProfileName, onValueChange = { editProfileName = it },
                        label = { Text(stringResource(R.string.qk_02136)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    // 只读信息展示
                    Text(stringResource(R.string.qk_02139, (ep.provider).toString()), style = MaterialTheme.typography.bodySmall, color = Muted)
                    Text("模型：${ep.model.ifBlank { "未设置" }}", style = MaterialTheme.typography.bodySmall, color = Muted)
                    Text("地址：${ep.baseUrl.ifBlank { "未设置" }}", style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 1)
                    Text(stringResource(R.string.qk_02142), style = MaterialTheme.typography.bodySmall, color = Muted)
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
                    title = { Text(stringResource(R.string.qk_02143, (ml.models.size).toString())) },
                    text = {
                        if (ml.models.isEmpty()) Text(stringResource(R.string.qk_02144))
                        else LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                            items(ml.models) { info ->
                                TextButton(onClick = {
                                    vm.update {
                                        // 总开关：把从 /models 接口取到的真实上下文长度写回 contextWindow，
                                        // 不再硬编码 1048576；并记入 modelContextLength 作为单次输入硬上限来源。
                                        copy(
                                            model = info.id,
                                            modelContextLength = info.contextLength,
                                            contextWindow = if (info.contextLength > 0) info.contextLength else this.contextWindow,
                                        )
                                    }
                                    vm.clearModelList()
                                },
                                    modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = if (info.contextLength > 0) "${info.id}  (ctx ${info.contextLength})" else info.id,
                                        modifier = Modifier.fillMaxWidth(),
                                        textAlign = TextAlign.Start
                                    )
                                }
                            }
                        }
                    })
            }
            is QuroModelListResult.Error -> {
                AlertDialog(onDismissRequest = { vm.clearModelList() },
                    confirmButton = { TextButton(onClick = { vm.clearModelList() }) { Text(stringResource(R.string.qk_02020)) } },
                    title = { Text(stringResource(R.string.qk_02145)) }, text = { Text(ml.message) })
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
    val title = if (isLocal) "当前：${cfg.provider} · ${cfg.model.ifBlank { "未选模型" }}" else stringResource(R.string.qk_02147)
    val sub = if (isLocal) stringResource(R.string.qk_02148) else stringResource(R.string.qk_02149)
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
    var importing by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    // 正在编辑运行参数的模型（null = 未打开参数面板）
    var paramsTarget by remember { mutableStateOf<QuroLocalModel?>(null) }
    val scope = rememberCoroutineScope()

    // 选文件夹（MNN 模型目录 / llama.cpp 含 .gguf 目录）→ 递归复制到私有目录存真实路径。
    // 类型按内容自动判定：含 llm_config.json → MNN；含 .gguf → llama.cpp。
    // 关键修复（#1109）：所有 Compose state 改写（models / importing / errorMsg）必须回到主线程，
    // 原先在 Dispatchers.IO 协程里直接改 state 触发 IllegalStateException: Cannot mutate state
    // without a snapshot → 未捕获 → 进程崩溃（用户所见「闪退」）。现已用 withContext(Dispatchers.IO)
    // 只做 IO，state 改写回到主线程，并整体 try/catch 兜底，失败时仅在 UI 提示而非崩溃。
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri: Uri? ->
        treeUri?.let { uri ->
            importing = qstr(R.string.qk_02150)
            errorMsg = null
            val treeName = DocumentFile.fromTreeUri(ctx, uri)?.name ?: "local-model"
            val id = UUID.randomUUID().toString()
            val dstDir = File(ctx.filesDir, "quro_local_models/$id")
            scope.launch {
                try {
                    val ok = withContext(Dispatchers.IO) {
                        val okCopy = copyDocumentTree(ctx, uri, dstDir)
                        if (okCopy) {
                            val hasMnnConfig = File(dstDir, "llm_config.json").exists()
                            // 🛡️ 分片归一化：同一组分片只登记一个基名，绝不把 N 个分片当 N 个模型。
                            // 否则 load() 取 modelNames.first() 时，ext4 哈希序下选片不确定，
                            // 可能加载到非首分片 → llama.cpp 加载失败 → 聊天被门禁拦（与本次 Bug 同症状）。
                            val gguf = QuroGgufNaming.collapseShards(
                                dstDir.walkTopDown()
                                    .filter { f -> f.isFile && f.name.endsWith(".gguf", ignoreCase = true) }
                                    .map { QuroGgufNaming.stem(it.name) }
                                    .toList()
                            )
                            val type = if (hasMnnConfig) QuroLocalModelType.MNN else QuroLocalModelType.LLAMA_CPP
                            val modelNames = if (hasMnnConfig) listOf(treeName) else gguf
                            repo.upsert(
                                QuroLocalModel(
                                    id = id, type = type, name = treeName,
                                    path = dstDir.absolutePath, modelNames = modelNames,
                                )
                            )
                        }
                        okCopy
                    }
                    models = repo.loadAll()
                    importing = null
                    if (!ok) errorMsg = qstr(R.string.qk_02151)
                } catch (e: Exception) {
                    QuroDiag.log("LocalModel", "folder import error: ${e.stackTraceToString()}")
                    importing = null
                    errorMsg = qstr(R.string.qk_02152, (e.message ?: e.javaClass.simpleName).toString())
                }
            }
        }
    }

    // 直接选单个 .gguf 文件（最简单直观），复制到私有目录按绝对路径加载。
    val ggufPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { fileUri: Uri? ->
        fileUri?.let { uri ->
            importing = qstr(R.string.qk_02150)
            errorMsg = null
            val id = UUID.randomUUID().toString()
            val rawName = DocumentFile.fromSingleUri(ctx, uri)?.name ?: "model.gguf"
            val fileName = if (rawName.endsWith(".gguf", ignoreCase = true)) rawName else "$rawName.gguf"
            val dstDir = File(ctx.filesDir, "quro_local_models/$id")
            val dstFile = File(dstDir, fileName)
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        dstDir.mkdirs()
                        ctx.contentResolver.openInputStream(uri)?.use { input ->
                            dstFile.outputStream().use { out -> input.copyTo(out) }
                        }
                    }
                    val modelName = dstFile.name.removeSuffix(".gguf").removeSuffix(".GGUF")
                    repo.upsert(
                        QuroLocalModel(
                            id = id, type = QuroLocalModelType.LLAMA_CPP, name = modelName,
                            path = dstDir.absolutePath, modelNames = listOf(modelName),
                        )
                    )
                    models = repo.loadAll()
                    importing = null
                } catch (e: Exception) {
                    QuroDiag.log("LocalModel", "gguf import error: ${e.stackTraceToString()}")
                    importing = null
                    errorMsg = qstr(R.string.qk_02152, (e.message ?: e.javaClass.simpleName).toString())
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        title = { Text(stringResource(R.string.qk_00041)) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.qk_02153) + stringResource(R.string.qk_02154),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { folderPicker.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Folder, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.qk_02155))
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { folderPicker.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Folder, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.qk_02156))
                }
                Spacer(Modifier.height(6.dp))
                Button(onClick = { ggufPicker.launch("application/octet-stream") }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.qk_02157))
                }
                Spacer(Modifier.height(8.dp))
                if (importing != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(importing!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (errorMsg != null) {
                    Text(errorMsg!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                }
                if (models.isEmpty()) {
                    Text(stringResource(R.string.qk_02158),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val loader = LocalModelLoaders.get()
                var loadTick by remember { mutableStateOf(0) }
                // 标记正在后台加载的模型 id，避免主线程被 nativeCreateSession 堵死（#ANR 修复）。
                var loadingId by remember { mutableStateOf<String?>(null) }
                models.forEach { m ->
                    val _r = loadTick // 订阅 loadTick，使「加载/卸载」状态变化触发本区域重组
                    val active = loader.isLoaded(m)
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .clickable {
                            // 选为当前模型：写入 cfg（provider=类型, 模型名=首个可用模型, 真实路径）
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
                                Text(qstr(R.string.qk_02160, (m.path).toString()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                Text(localModelCapabilitySummary(m), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                                if (active) {
                                    Text(qstr(R.string.qk_02161),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary, maxLines = 1)
                                }
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                if (active) {
                                    TextButton(onClick = {
                                        // ⚠️ #ANR 修复：unload 内部会调原生 release，必须离主线程。
                                        scope.launch(Dispatchers.IO) {
                                            loader.unload()
                                            withContext(Dispatchers.Main) { loadTick++ }
                                        }
                                    }) {
                                        Text(qstr(R.string.qk_00434))
                                    }
                                } else {
                                    TextButton(
                                        enabled = loadingId != m.id,
                                        onClick = {
                                            // ⚠️ #ANR 修复：loader.load() 内部 LlamaSession.create→nativeCreateSession
                                            // 是把 GGUF 整包读进内存的重活，必须在 IO 线程执行，否则主线程被堵死 → ANR。
                                            scope.launch(Dispatchers.IO) {
                                                loadingId = m.id
                                                val res = loader.load(m)
                                                withContext(Dispatchers.Main) {
                                                    loadingId = null
                                                    loadTick++
                                                    if (res is LocalModelLoader.LoadResult.Failure) errorMsg = res.message
                                                    // 加载成功时同时选为当前模型，避免
                                                    // cfg.localModelPath 仍指向旧模型导致
                                                    // routeLocal 找到的模型与 holder 不匹配。
                                                    if (res is LocalModelLoader.LoadResult.Success) {
                                                        vm.update {
                                                            copy(provider = m.type.name, localModelPath = m.path,
                                                                model = m.modelNames.firstOrNull() ?: m.name, customProviderName = "")
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    ) {
                                        if (loadingId == m.id) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp), strokeWidth = 2.dp
                                            )
                                        } else {
                                            Text(qstr(R.string.qk_02133))
                                        }
                                    }
                                }
                                Row {
                                    // 运行参数（线程数 / 上下文 / 计算精度 / 后端 …）。
                                    // 以前这些只能吃硬编码默认值，MNN 只跑单后端、llama 窗口固定，
                                    // 这里给出可视化入口，改完需重新「加载」才生效。
                                    IconButton(onClick = { paramsTarget = m }) {
                                        Icon(Icons.Filled.Edit, qstr(R.string.qk_02162), Modifier.size(18.dp))
                                    }
                                    IconButton(onClick = {
                                        scope.launch {
                                            withContext(Dispatchers.IO) { runCatching { repo.delete(m.id) } }
                                            models = repo.loadAll()
                                        }
                                    }) {
                                        Icon(Icons.Filled.Delete, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.qk_02163),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )

    // 运行参数面板（叠在上面这层对话框之上）
    paramsTarget?.let { target ->
        LocalModelParamsDialog(
            model = target,
            onDismiss = { paramsTarget = null },
            onSave = { updated ->
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { repo.upsert(updated) } }
                    models = repo.loadAll()
                    paramsTarget = null
                }
            },
        )
    }
}

/**
 * 本地模型「运行参数」编辑面板。
 *
 * 覆盖用户点名缺失的那几项：**计算精度（计算类型）、线程数、上下文长度、计算后端、GPU 层数**。
 * 全部留空/0 即"自动"，与 [QuroLocalModel.resolveThreads] 等方法给出的安全默认值一致。
 * 参数写进 `quro_local_models.json`，下次「加载」模型时由 LocalModelSessionHolder 读取生效。
 */
@Composable
private fun LocalModelParamsDialog(
    model: QuroLocalModel,
    onDismiss: () -> Unit,
    onSave: (QuroLocalModel) -> Unit,
) {
    val isLlama = model.type == QuroLocalModelType.LLAMA_CPP
    var threads by remember { mutableStateOf(if (model.threads > 0) model.threads.toString() else "") }
    var ctxSize by remember { mutableStateOf(if (model.contextSize > 0) model.contextSize.toString() else "") }
    var gpuLayers by remember { mutableStateOf(if (model.gpuLayers > 0) model.gpuLayers.toString() else "") }
    var useMmap by remember { mutableStateOf(model.useMmap) }
    var kvUnified by remember { mutableStateOf(model.kvUnified) }
    var backend by remember { mutableStateOf(model.backend.ifBlank { "cpu" }) }
    var precision by remember { mutableStateOf(model.precision.ifBlank { "low" }) }
    var memoryMode by remember { mutableStateOf(model.memoryMode) }

    val autoThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.qk_02164, (model.name).toString())) },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    model.copy(
                        threads = threads.toIntOrNull()?.coerceIn(1, 16) ?: 0,
                        contextSize = ctxSize.toIntOrNull()?.coerceIn(512, 32768) ?: 0,
                        gpuLayers = gpuLayers.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                        useMmap = useMmap,
                        kvUnified = kvUnified,
                        backend = backend,
                        precision = precision,
                        memoryMode = memoryMode,
                    )
                )
            }) { Text(qstr(R.string.qk_00198)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.qk_00011)) } },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.qk_02165),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = threads, onValueChange = { s -> threads = s.filter { it.isDigit() }.take(2) },
                    label = { Text(stringResource(R.string.qk_02166, (autoThreads).toString())) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))

                if (isLlama) {
                    OutlinedTextField(
                        value = ctxSize, onValueChange = { s -> ctxSize = s.filter { it.isDigit() }.take(5) },
                        label = { Text(stringResource(R.string.qk_02167)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.qk_02168),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = gpuLayers, onValueChange = { s -> gpuLayers = s.filter { it.isDigit() }.take(3) },
                        label = { Text(stringResource(R.string.qk_02169)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = useMmap, onCheckedChange = { useMmap = it })
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.qk_02170), style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(R.string.qk_02171),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = kvUnified, onCheckedChange = { kvUnified = it })
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.qk_02172), style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(R.string.qk_02173),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    Text(stringResource(R.string.qk_02174), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    ParamChipRow(
                        options = listOf("cpu", "opencl", "opengl", "vulkan"),
                        selected = backend,
                        onSelect = { backend = it },
                    )
                    Text(stringResource(R.string.qk_02175),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))

                    Text(stringResource(R.string.qk_02176), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    ParamChipRow(
                        options = listOf("low", "normal", "high"),
                        selected = precision,
                        onSelect = { precision = it },
                    )
                    Text(stringResource(R.string.qk_02177),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))

                    Text(stringResource(R.string.qk_02178), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    ParamChipRow(
                        options = listOf("", "low", "normal"),
                        labels = listOf(stringResource(R.string.qk_00850), "low", "normal"),
                        selected = memoryMode,
                        onSelect = { memoryMode = it },
                    )
                }
            }
        },
    )
}

/** 参数选项的一行小胶囊选择器。 */
@Composable
private fun ParamChipRow(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    labels: List<String> = options,
) {
    Row(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, opt ->
            FilterChip(
                selected = selected == opt,
                onClick = { onSelect(opt) },
                label = { Text(labels.getOrElse(i) { opt }, style = MaterialTheme.typography.bodySmall) },
                modifier = Modifier.padding(end = 6.dp),
            )
        }
    }
}

/** 递归复制整个 document tree 到目标私有目录，返回是否成功。 */
private fun copyDocumentTree(ctx: Context, treeUri: Uri, dstDir: File): Boolean {
    return runCatching {
        val tree = DocumentFile.fromTreeUri(ctx, treeUri) ?: return@runCatching false
        dstDir.mkdirs()
        copyDocumentRecursive(ctx, tree, dstDir)
        true
    }.getOrDefault(false)
}

/** 递归复制：文件直接拷贝内容，目录递归展开。 */
private fun copyDocumentRecursive(ctx: Context, src: DocumentFile, dst: File) {
    if (src.isFile) {
        dst.parentFile?.mkdirs()
        ctx.contentResolver.openInputStream(src.uri)?.use { input ->
            dst.outputStream().use { out -> input.copyTo(out) }
        }
    } else if (src.isDirectory) {
        dst.mkdirs()
        src.listFiles().forEach { child ->
            copyDocumentRecursive(ctx, child, File(dst, child.name ?: "file"))
        }
    }
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
    // 本地离线引擎（MNN / llama.cpp）不走云端服务商列表，仅在「本地离线模型」管理器配置，避免与云端模型混排。
    val builtIn = remember {
        ApiProviderType.values().filter {
            it != ApiProviderType.OTHER && it != ApiProviderType.MNN && it != ApiProviderType.LLAMA_CPP
        }
    }
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
                }) { Text(qstr(R.string.qk_02179)) }
            },
            dismissButton = { TextButton(onClick = { showCustomForm = false }) { Text(stringResource(R.string.qk_00143)) } },
            title = { Text(stringResource(R.string.qk_02180), style = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 头像行
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProviderAvatar(dialogAvatar, cName.firstOrNull()?.uppercase() ?: "+", 48.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            TextButton(onClick = { dialogPickAvatar.launch("image/*") }) {
                                Text(stringResource(R.string.qk_02181), fontSize = 13.sp, color = Accent, fontWeight = FontWeight.SemiBold)
                            }
                            if (dialogAvatar != null) {
                                TextButton(onClick = { dialogAvatar = null }) {
                                    Text(stringResource(R.string.qk_00194), fontSize = 12.sp, color = Muted)
                                }
                            }
                        }
                    }
                    OutlinedTextField(value = cName, onValueChange = { cName = it }, label = { Text(stringResource(R.string.qk_02182)) },
                        placeholder = { Text(stringResource(R.string.qk_02183)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = cUrl, onValueChange = { cUrl = it }, label = { Text(stringResource(R.string.qk_02184)) },
                        placeholder = { Text("https://api.example.com/v1") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = cModel, onValueChange = { cModel = it }, label = { Text(stringResource(R.string.qk_02185)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.qk_02186),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
    } else {
        BasicAlertDialog(onDismissRequest = onDismissRequest) {
            Surface(Modifier.fillMaxWidth().heightIn(max = 520.dp), shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp, shadowElevation = 8.dp) {
                Column(Modifier.padding(16.dp)) {
                    Text(text = stringResource(R.string.qk_02187), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp))
                    OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it },
                        placeholder = { Text(stringResource(R.string.qk_02188), style = MaterialTheme.typography.bodySmall) },
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        trailingIcon = { if (searchQuery.isNotEmpty()) IconButton({ searchQuery = "" }, Modifier.size(36.dp)) { Icon(Icons.Filled.Clear, null) } },
                        singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), shape = RoundedCornerShape(8.dp))

                    LazyColumn(Modifier.weight(1f)) {
                        // 自定义「其他供应商」分组
                        if (customProviders.isNotEmpty()) {
                            item {
                                Text(stringResource(R.string.qk_00040), style = MaterialTheme.typography.labelMedium,
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
                            Text(stringResource(R.string.qk_02180), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                        }
                    }

                    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismissRequest) { Text(stringResource(R.string.qk_00011)) }
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
        ApiProviderType.OPENAI_RESPONSES_GENERIC -> qstr(R.string.qk_02189)
        ApiProviderType.OPENAI_GENERIC -> qstr(R.string.qk_02190)
        ApiProviderType.ANTHROPIC -> "Anthropic Claude"
        ApiProviderType.ANTHROPIC_GENERIC -> qstr(R.string.qk_02191)
        ApiProviderType.GOOGLE -> "Google Gemini"
        ApiProviderType.GEMINI_GENERIC -> qstr(R.string.qk_02192)
        ApiProviderType.BAIDU -> qstr(R.string.qk_02193)
        ApiProviderType.ALIYUN -> qstr(R.string.qk_02194)
        ApiProviderType.XUNFEI -> qstr(R.string.qk_02195)
        ApiProviderType.ZHIPU -> qstr(R.string.qk_02196)
        ApiProviderType.BAICHUAN -> qstr(R.string.qk_02197)
        ApiProviderType.MOONSHOT -> qstr(R.string.qk_02198)
        ApiProviderType.MIMO -> qstr(R.string.qk_02199)
        ApiProviderType.DEEPSEEK -> "DeepSeek"
        ApiProviderType.MISTRAL -> "Mistral AI"
        ApiProviderType.SILICONFLOW -> qstr(R.string.qk_02200)
        ApiProviderType.IFLOW -> "iFlow"
        ApiProviderType.OPENROUTER -> "OpenRouter"
        ApiProviderType.FOUR_ROUTER -> "4Router"
        ApiProviderType.NOUS_PORTAL -> "Nous Portal"
        ApiProviderType.INFINIAI -> qstr(R.string.qk_02201)
        ApiProviderType.ALIPAY_BAILING -> qstr(R.string.qk_02202)
        ApiProviderType.DOUBAO -> qstr(R.string.qk_02203)
        ApiProviderType.NVIDIA -> "NVIDIA NIM"
        ApiProviderType.LMSTUDIO -> qstr(R.string.qk_02204)
        ApiProviderType.OLLAMA -> qstr(R.string.qk_02205)
        ApiProviderType.OPENAI_LOCAL -> qstr(R.string.qk_02206)
        ApiProviderType.MNN -> qstr(R.string.qk_02207)
        ApiProviderType.LLAMA_CPP -> qstr(R.string.qk_02208)
        ApiProviderType.PPINFRA -> qstr(R.string.qk_02209)
        ApiProviderType.NOVITA -> "Novita AI"
        ApiProviderType.OPENCODE_GO -> "OpenCode Go"
        ApiProviderType.OTHER -> qstr(R.string.qk_02210)
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
        ApiProviderType.OPENCODE_GO -> MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
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
