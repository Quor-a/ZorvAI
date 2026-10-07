package com.ai.assistance.quro.ui
import androidx.compose.ui.res.stringResource
import com.ai.assistance.quro.R
import com.ai.assistance.quro.util.qstr

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
 * 设计、移植。为 13 类 AI 能力各自绑定模型：默认「跟随主模型」，可切换为独立模型
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
                        stringResource(R.string.qk_03764),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontFamily = FontFamily.Serif,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.qk_00143)) }
                },
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
        ) {
            GroupCaption(stringResource(R.string.qk_03765))
            InfoBox(
                text = stringResource(R.string.qk_03903),
                tone = Accent,
            )
            InfoBox(
                text = stringResource(R.string.qk_03904),
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
                stringResource(R.string.qk_03768),
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
                }) { Text(stringResource(R.string.qk_02020)) }
            },
            dismissButton = { TextButton(onClick = { pickerType = null }) { Text(stringResource(R.string.qk_00011)) } },
            title = { Text(stringResource(R.string.qk_03774, stringResource(type.labelRes))) },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextButton(onClick = { fetchFeatureModels() }, enabled = !loading) {
                            Text(if (loading) stringResource(R.string.qk_02113) else stringResource(R.string.qk_02114))
                        }
                        Text(stringResource(R.string.qk_03769), fontSize = 11.sp, color = Muted)
                    }
                    Spacer(Modifier.height(8.dp))
                    if (loading) {
                        Text(stringResource(R.string.qk_03770), fontSize = 13.sp, color = Muted)
                        Spacer(Modifier.height(6.dp))
                    }
                    if (error != null) {
                        // error 是 remember 委托属性，不能智能转换为非空；先取本地快照
                        val err = error ?: ""
                        Text(stringResource(R.string.qk_03771, err), fontSize = 12.sp, color = Muted)
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
                        label = { Text(stringResource(R.string.qk_01862)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    
                    // 高级配置：基础URL和API密钥
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.qk_01863), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Muted)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = customBaseUrl,
                        onValueChange = { customBaseUrl = it },
                        label = { Text(stringResource(R.string.qk_01864)) },
                        placeholder = { Text(stringResource(R.string.qk_01865)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = customApiKey,
                        onValueChange = { customApiKey = it },
                        label = { Text(stringResource(R.string.qk_01866)) },
                        placeholder = { Text(stringResource(R.string.qk_01867)) },
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
                Text(stringResource(type.labelRes), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                Text(stringResource(type.descRes), fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 2.dp))
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
                if (b.useGlobal) stringResource(R.string.qk_03775) else stringResource(R.string.qk_03776),
                fontSize = 13.sp,
                color = if (b.useGlobal) cs.onSurface else Accent,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (b.useGlobal) stringResource(R.string.qk_02340) else stringResource(R.string.qk_00065),
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
                    if (b.model.isBlank()) stringResource(R.string.qk_03777) else b.model,
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
                    when {
                        b.baseUrl.isNotBlank() && b.apiKey.isNotBlank() -> stringResource(R.string.qk_03782)
                        b.baseUrl.isNotBlank() -> stringResource(R.string.qk_03780)
                        else -> stringResource(R.string.qk_03781)
                    },
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
 * IMAGE_RECOGNITION 由 VisualAnalysisTool 的 Level2 降级路径接入 resolveConfig，
 *   同时也是**视频通话实时画面理解的保底视觉模型**（见 QuroFrameVisionRouter 第 3 级）；
 * VIDEO_RECOGNITION 由 video_understanding 工具接入 resolveConfig
 *   —— 🔴 它分析的是用户主动发起的**整段视频文件**，与视频通话的实时单帧理解是两条独立链路；
 * VIDEO_CALL 由 QuroVideoCallService 消费，除了实时对话模型外，
 *   当主模型没有视觉能力时，**这个绑定的模型会直接被拿来识别摄像头画面**（降级链第 2 级）。
 * 以上均真实消费绑定模型。
 * 其余功能在单接入点架构下作为主对话内的工具调用，独立绑定无单独 LLM 调用可路由，故跟随主对话。
 */
private fun engineWired(type: QuroFunctionType): EngineWired = when (type) {
    QuroFunctionType.CHAT,
    QuroFunctionType.PERSONA_INCUBATE,
    QuroFunctionType.UI_CONTROL,
    QuroFunctionType.IMAGE_RECOGNITION,
    QuroFunctionType.VIDEO_RECOGNITION,
    QuroFunctionType.VIDEO_CALL -> EngineWired(qstr(R.string.qk_03778), true)
    else -> EngineWired(qstr(R.string.qk_03779), false)
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
    QuroFunctionType.VIDEO_CALL -> Icons.Filled.Videocam
    QuroFunctionType.IMAGE_GEN -> Icons.Filled.Image
    QuroFunctionType.VIDEO_GEN -> Icons.Filled.Videocam
}