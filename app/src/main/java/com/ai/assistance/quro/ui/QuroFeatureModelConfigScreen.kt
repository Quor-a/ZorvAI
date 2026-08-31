package com.ai.assistance.quro.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.core.model.QuroFunctionModelBinding
import com.ai.assistance.quro.core.model.QuroFunctionModelConfigRepository
import com.ai.assistance.quro.core.model.QuroFunctionType
import com.ai.assistance.quro.core.model.QuroModelConfigRepository
import com.ai.assistance.quro.core.network.QuroModelListFetcher
import com.ai.assistance.quro.core.network.QuroModelListResult
import com.ai.assistance.quro.ui.theme.Accent
import com.ai.assistance.quro.ui.theme.AccentSoft
import com.ai.assistance.quro.ui.theme.Line
import com.ai.assistance.quro.ui.theme.Muted
import kotlinx.coroutines.launch

/**
 * 功能模型配置（设置 → 功能模型配置）：参考 FunctionalConfigScreen 的「功能 → 配置」
 * 设计、移植。为 12 类 AI 能力各自绑定模型：默认「跟随主模型」，可切换为独立模型
 * 并从全局接入点的模型列表中选取。
 *
 * 消费机制：引擎入口 [com.ai.assistance.quro.core.QuroAssistant.ask] 经
 * [QuroFunctionModelConfigRepository.resolveConfig] 取各功能最终模型；主对话 (CHAT) 恒用主模型，
 * 其余功能的独立模型绑定将在对应次级调用接入后自动生效（当前 Zorv AI 单接入点架构下，
 * 独立绑定 = 复用主接入点的 baseUrl/apiKey、仅替换 model 名）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuroFeatureModelConfigScreen(onBack: () -> Unit = {}) {
    val ctx = LocalContext.current
    val repo = remember { QuroFunctionModelConfigRepository(ctx) }
    var cfg by remember { mutableStateOf(repo.load()) }
    var pickerType by remember { mutableStateOf<QuroFunctionType?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "功能模型配置",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontFamily = FontFamily.Serif,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") }
                },
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
        ) {
            GroupCaption("为各类 AI 能力绑定模型")
            InfoBox(
                text = "参考「功能 → 配置」设计：每个功能可「跟随主模型」或指定独立模型。" +
                        "已接入引擎并实时生效：主对话(CHAT)、语音球问答(CHAT)、人格蒸馏/自动孵化(PERSONA_INCUBATE)、" +
                        "语音风格推导(UI_CONTROL)。指定独立模型后，对应调用即改用该模型。",
                tone = Accent,
            )
            InfoBox(
                text = "视觉模型配置：图像识别和视频识别需要支持视觉能力的模型（如GPT-4 Vision）。" +
                        "请在「设置 → 模型配置」中配置视觉模型的API密钥和基础URL，" +
                        "然后在下方为图像识别和视频识别选择对应的模型。\n\n" +
                        "路径配置说明：所有功能共享主模型配置的基础URL和API密钥。" +
                        "如需为视觉模型使用不同的端点，请在「设置 → 模型配置」中配置对应的端点地址。",
                tone = Color(0xFF10B981),
            )
            Spacer(Modifier.height(10.dp))
            SetGroup {
                QuroFunctionType.values().forEachIndexed { idx, type ->
                    if (idx > 0) HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
                    FeatureModelRow(
                        type = type,
                        b = cfg[type] ?: QuroFunctionModelBinding(),
                        onToggleGlobal = {
                            repo.setBinding(type, (cfg[type] ?: QuroFunctionModelBinding()).copy(useGlobal = it))
                            cfg = repo.load()
                        },
                        onPick = { pickerType = type },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "提示：默认所有能力跟随主模型（设置 → 模型配置）。图片 / 视频生成建议独立指定对应模型。" +
                        "当前为单接入点架构，独立绑定复用主接入点的地址与密钥，仅替换模型名。",
                fontSize = 11.sp, color = Muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
    }

    // ── 模型选择弹窗 ──
    if (pickerType != null) {
        val type = pickerType!!
        var models by remember { mutableStateOf<List<String>>(emptyList()) }
        var loading by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        val gModel = QuroModelConfigRepository(ctx).load().model
        var manual by remember { mutableStateOf((cfg[type]?.model?.takeIf { it.isNotBlank() } ?: gModel)) }
        var customBaseUrl by remember { mutableStateOf(cfg[type]?.baseUrl ?: "") }
        var customApiKey by remember { mutableStateOf(cfg[type]?.apiKey ?: "") }

        // 仅以「全局已配置的模型」作为兜底项展示；不再自动联网拉取（改为手动）。
        LaunchedEffect(type) {
            val g = QuroModelConfigRepository(ctx).load()
            val globalModel = g.model.takeIf { it.isNotBlank() }
            models = if (globalModel != null) listOf(globalModel) else emptyList()
            error = null
        }

        fun fetchFeatureModels() {
            loading = true; error = null
            val g = QuroModelConfigRepository(ctx).load()
            val globalModel = g.model.takeIf { it.isNotBlank() }
            val seed = if (globalModel != null) listOf(globalModel) else emptyList()
            models = seed
            scope.launch {
                when (val r = QuroModelListFetcher(connectTimeout = 8, readTimeout = 15).fetch(g.baseUrl, g.apiKey)) {
                    is QuroModelListResult.Success -> { models = (seed + r.models.map { it.id }).distinct(); loading = false }
                    is QuroModelListResult.Error -> { error = r.message; models = seed; loading = false }
                }
            }
        }

        AlertDialog(
            onDismissRequest = { pickerType = null },
            confirmButton = {
                TextButton(onClick = {
                    repo.setBinding(type, QuroFunctionModelBinding(
                        useGlobal = false,
                        model = manual.trim(),
                        baseUrl = customBaseUrl.trim(),
                        apiKey = customApiKey.trim(),
                    ))
                    cfg = repo.load()
                    pickerType = null
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { pickerType = null }) { Text("取消") } },
            title = { Text("选择 ${type.label} 模型") },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextButton(onClick = { fetchFeatureModels() }, enabled = !loading) {
                            Text(if (loading) "拉取中…" else "拉取模型列表")
                        }
                        Text("需先在主模型配置填好接入点", fontSize = 11.sp, color = Muted)
                    }
                    Spacer(Modifier.height(8.dp))
                    if (loading) {
                        Text("正在拉取模型列表…", fontSize = 13.sp, color = Muted)
                        Spacer(Modifier.height(6.dp))
                    }
                    if (error != null) {
                        Text("拉取失败：$error\n已为你保留已配置的模型，也可直接手动输入模型名。", fontSize = 12.sp, color = Muted)
                        Spacer(Modifier.height(8.dp))
                    }
                    if (models.isNotEmpty()) {
                        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                            items(models) { m ->
                                Row(
                                    Modifier.fillMaxWidth().clickable { manual = m }.padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selected = manual == m, onClick = { manual = m })
                                    Spacer(Modifier.width(8.dp))
                                    Text(m, fontSize = 13.sp)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    OutlinedTextField(
                        value = manual,
                        onValueChange = { manual = it },
                        label = { Text("模型名（可手动输入）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    
                    // 高级配置：基础URL和API密钥
                    Spacer(Modifier.height(12.dp))
                    Text("高级配置（可选）", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Muted)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = customBaseUrl,
                        onValueChange = { customBaseUrl = it },
                        label = { Text("自定义基础URL（可选）") },
                        placeholder = { Text("留空则使用主模型配置的URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = customApiKey,
                        onValueChange = { customApiKey = it },
                        label = { Text("自定义API密钥（可选）") },
                        placeholder = { Text("留空则使用主模型配置的密钥") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
        )
    }
}

@Composable
private fun FeatureModelRow(
    type: QuroFunctionType,
    b: QuroFunctionModelBinding,
    onToggleGlobal: (Boolean) -> Unit,
    onPick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(featureIcon(type), null, Modifier.size(20.dp), tint = Accent)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(type.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                Text(type.desc, fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 2.dp))
                val wired = engineWired(type)
                Text(
                    wired.label,
                    fontSize = 10.sp,
                    color = if (wired.active) Accent else Muted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (b.useGlobal) "跟随主模型" else "独立模型",
                fontSize = 13.sp,
                color = if (b.useGlobal) cs.onSurface else Accent,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (b.useGlobal) "开启" else "关闭",
                fontSize = 11.sp,
                color = if (b.useGlobal) Accent else Muted,
                modifier = Modifier.padding(end = 6.dp),
            )
            Switch(checked = b.useGlobal, onCheckedChange = { onToggleGlobal(it) })
        }
        if (!b.useGlobal) {
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(cs.surfaceVariant.copy(alpha = 0.5f))
                    .clickable(onClick = onPick)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (b.model.isBlank()) "点击选择模型" else b.model,
                    fontSize = 13.sp,
                    color = if (b.model.isBlank()) Muted else cs.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Filled.ChevronRight, null, Modifier.size(16.dp), tint = Muted)
            }
            
            // 高级配置：基础URL和API密钥
            if (b.baseUrl.isNotBlank() || b.apiKey.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "高级配置：${if (b.baseUrl.isNotBlank()) "自定义端点" else ""}${if (b.baseUrl.isNotBlank() && b.apiKey.isNotBlank()) " + " else ""}${if (b.apiKey.isNotBlank()) "自定义密钥" else ""}",
                    fontSize = 10.sp,
                    color = Muted,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

private data class EngineWired(val label: String, val active: Boolean)

/**
 * 各功能「独立模型绑定」是否真的改变引擎行为。
 * CHAT / PERSONA_INCUBATE / UI_CONTROL 已有独立调用点接入 resolveConfig，开关即时生效；
 * 其余功能在单接入点架构下作为主对话内的工具调用，独立绑定无单独 LLM 调用可路由，故跟随主对话。
 */
private fun engineWired(type: QuroFunctionType): EngineWired = when (type) {
    QuroFunctionType.CHAT,
    QuroFunctionType.PERSONA_INCUBATE,
    QuroFunctionType.UI_CONTROL -> EngineWired("已接入引擎·开关生效", true)
    else -> EngineWired("对话内调用·跟随主对话", false)
}

private fun featureIcon(type: QuroFunctionType): ImageVector = when (type) {
    QuroFunctionType.CHAT -> Icons.Filled.AutoAwesome
    QuroFunctionType.SUMMARY -> Icons.Filled.Summarize
    QuroFunctionType.MEMORY -> Icons.Filled.Memory
    QuroFunctionType.UI_CONTROL -> Icons.Filled.AutoAwesome
    QuroFunctionType.TRANSLATION -> Icons.Filled.Translate
    QuroFunctionType.GREP -> Icons.Filled.Memory
    QuroFunctionType.PERSONA_INCUBATE -> Icons.Filled.AutoAwesome
    QuroFunctionType.IMAGE_RECOGNITION -> Icons.Filled.Image
    QuroFunctionType.AUDIO_RECOGNITION -> Icons.Filled.Image
    QuroFunctionType.VIDEO_RECOGNITION -> Icons.Filled.Videocam
    QuroFunctionType.IMAGE_GEN -> Icons.Filled.Image
    QuroFunctionType.VIDEO_GEN -> Icons.Filled.Videocam
}
