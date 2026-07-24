package com.ai.assistance.quro.ui

import android.content.Context
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Summarize
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
import com.ai.assistance.quro.core.model.FeatureModelBinding
import com.ai.assistance.quro.core.model.FeatureModelKey
import com.ai.assistance.quro.core.model.QuroFeatureModelConfigRepository
import com.ai.assistance.quro.core.model.QuroModelConfigRepository
import com.ai.assistance.quro.core.network.QuroModelListFetcher
import com.ai.assistance.quro.core.network.QuroModelListResult
import com.ai.assistance.quro.ui.theme.Accent
import com.ai.assistance.quro.ui.theme.AccentSoft
import com.ai.assistance.quro.ui.theme.Line
import com.ai.assistance.quro.ui.theme.Muted

/**
 * 功能模型配置（设置 → 功能模型配置）：为 5 类 AI 子能力各自绑定模型。
 * 每项默认「跟随主模型」，可切换为独立模型并从全局接入点的模型列表中选取。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuroFeatureModelConfigScreen(onBack: () -> Unit = {}) {
    val ctx = LocalContext.current
    val repo = remember { QuroFeatureModelConfigRepository(ctx) }
    var cfg by remember { mutableStateOf(repo.load()) }
    var pickerKey by remember { mutableStateOf<FeatureModelKey?>(null) }

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
            SetGroup {
                FeatureModelKey.values().forEachIndexed { idx, key ->
                    if (idx > 0) HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
                    FeatureModelRow(
                        key = key,
                        b = cfg.binding(key),
                        onToggleGlobal = {
                            repo.update(key, cfg.binding(key).copy(useGlobal = !cfg.binding(key).useGlobal))
                            cfg = repo.load()
                        },
                        onPick = { pickerKey = key },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "提示：默认所有能力跟随主模型（设置 → 模型配置）。视频 / 图片生成建议独立指定对应模型，AI 将直接调用。",
                fontSize = 11.sp, color = Muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
    }

    // ── 模型选择弹窗 ──
    if (pickerKey != null) {
        val key = pickerKey!!
        var models by remember { mutableStateOf<List<String>>(emptyList()) }
        var loading by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf<String?>(null) }
        var manual by remember { mutableStateOf(cfg.binding(key).model) }

        LaunchedEffect(key) {
            loading = true; error = null
            val g = QuroModelConfigRepository(ctx).load()
            when (val r = QuroModelListFetcher().fetch(g.baseUrl, g.apiKey)) {
                is QuroModelListResult.Success -> { models = r.models; loading = false }
                is QuroModelListResult.Error -> { error = r.message; loading = false }
            }
        }

        AlertDialog(
            onDismissRequest = { pickerKey = null },
            confirmButton = {
                TextButton(onClick = {
                    repo.update(key, FeatureModelBinding(useGlobal = false, model = manual.trim()))
                    cfg = repo.load()
                    pickerKey = null
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { pickerKey = null }) { Text("取消") } },
            title = { Text("选择 ${key.label} 模型") },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    if (loading) {
                        Text("正在拉取模型列表…", fontSize = 13.sp, color = Muted)
                    } else {
                        if (error != null) {
                            Text("拉取失败：$error\n可直接手动输入模型名。", fontSize = 12.sp, color = Muted)
                            Spacer(Modifier.height(8.dp))
                        }
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
                        OutlinedTextField(
                            value = manual,
                            onValueChange = { manual = it },
                            label = { Text("模型名（可手动输入）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun FeatureModelRow(
    key: FeatureModelKey,
    b: FeatureModelBinding,
    onToggleGlobal: () -> Unit,
    onPick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(featureIcon(key), null, Modifier.size(20.dp), tint = Accent)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(key.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                Text(key.desc, fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 2.dp))
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
            Switch(checked = b.useGlobal, onCheckedChange = { onToggleGlobal() })
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
        }
    }
}

private fun featureIcon(key: FeatureModelKey): ImageVector = when (key) {
    FeatureModelKey.CONTEXT_SUMMARY -> Icons.Filled.Summarize
    FeatureModelKey.MEMORY_UPDATE -> Icons.Filled.Memory
    FeatureModelKey.PERSONA_INCUBATE -> Icons.Filled.AutoAwesome
    FeatureModelKey.VIDEO_GEN -> Icons.Filled.Videocam
    FeatureModelKey.IMAGE_GEN -> Icons.Filled.Image
}
