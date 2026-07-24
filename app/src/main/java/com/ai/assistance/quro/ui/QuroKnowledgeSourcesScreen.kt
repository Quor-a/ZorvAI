package com.ai.assistance.quro.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.core.knowledge.QuroExternalKnowledgeSync
import com.ai.assistance.quro.core.knowledge.QuroExternalSource
import com.ai.assistance.quro.core.knowledge.QuroExternalSourceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 第三方云盘知识源管理界面（#591）。
 *
 * 列出/增删改/启停第三方知识来源（腾讯文档 / ima / 乐享 / 自定义），可单源或全量同步，
 * 同步结果落 knowledge_base/external/<id>/ 并触发 RAG 重索引。由 [QuroKnowledgeScreen] 以全屏覆盖层承载。
 */

private val SOURCE_TYPES = listOf("tencent_doc", "ima", "lexing", "generic")
private val SOURCE_TYPE_LABELS = mapOf(
    "tencent_doc" to "腾讯文档",
    "ima" to "ima 知识库",
    "lexing" to "乐享",
    "generic" to "自定义 API",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuroKnowledgeSourcesScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { QuroExternalSourceStore(ctx) }
    var sources by remember { mutableStateOf(store.list()) }
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<QuroExternalSource?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    fun refresh() { sources = store.list() }

    fun syncOne(src: QuroExternalSource) {
        busy = true
        scope.launch(Dispatchers.IO) {
            QuroExternalKnowledgeSync.syncOne(ctx, src)
            withContext(Dispatchers.Main) { refresh(); busy = false }
        }
    }

    fun syncAll() {
        busy = true
        scope.launch(Dispatchers.IO) {
            QuroExternalKnowledgeSync.syncAllEnabled(ctx)
            withContext(Dispatchers.Main) { refresh(); busy = false }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("云盘知识来源") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.ArrowBack, "返回") } },
                actions = {
                    if (sources.any { it.enabled }) {
                        TextButton(onClick = { syncAll() }, enabled = !busy) { Text("全部同步") }
                    }
                    IconButton(onClick = { showAdd = true }) { Icon(Icons.Filled.Add, "添加来源") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { pad ->
        if (sources.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text(
                    "暂无知识来源。点右上角 + 添加腾讯文档 / ima / 乐享，或自定义 OpenAPI 文档端点。",
                    Modifier.padding(20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(sources, key = { it.id }) { src ->
                    SourceCard(
                        src = src,
                        busy = busy,
                        onToggle = { enabled ->
                            store.upsert(src.copy(enabled = enabled))
                            refresh()
                        },
                        onEdit = { editing = src },
                        onDelete = { store.remove(src.id); refresh() },
                        onSync = { syncOne(src) },
                    )
                }
            }
        }
    }

    if (showAdd) {
        SourceEditDialog(
            initial = null,
            onDismiss = { showAdd = false },
            onSave = { src ->
                store.upsert(src)
                showAdd = false
                refresh()
            },
        )
    }
    editing?.let { src ->
        SourceEditDialog(
            initial = src,
            onDismiss = { editing = null },
            onSave = { updated ->
                store.upsert(updated)
                editing = null
                refresh()
            },
        )
    }
}

@Composable
private fun SourceCard(
    src: QuroExternalSource,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSync: () -> Unit,
) {
    val lastText = if (src.lastSync > 0) {
        "上次同步 ${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(src.lastSync))}"
    } else {
        "从未同步"
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(src.name, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    Text(
                        SOURCE_TYPE_LABELS[src.type] ?: src.type,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (src.enabled) "启用" else "禁用", fontSize = 12.sp)
                    Switch(checked = src.enabled, onCheckedChange = onToggle)
                }
            }
            Spacer(Modifier.height(6.dp))
            if (src.baseUrl.isNotBlank()) {
                Text(src.baseUrl, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            if (src.lastError.isNotBlank()) {
                Text("错误：${src.lastError}", fontSize = 12.sp, color = MaterialTheme.colorScheme.error, maxLines = 2)
            } else {
                Text(lastText, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onEdit) { Text("编辑") }
                TextButton(onClick = onSync, enabled = !busy && src.token.isNotBlank() && src.baseUrl.isNotBlank()) { Text("同步") }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceEditDialog(
    initial: QuroExternalSource?,
    onDismiss: () -> Unit,
    onSave: (QuroExternalSource) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var type by remember { mutableStateOf(initial?.type ?: "tencent_doc") }
    var baseUrl by remember { mutableStateOf(initial?.baseUrl ?: "") }
    var token by remember { mutableStateOf(initial?.token ?: "") }
    var enabled by remember { mutableStateOf(initial?.enabled ?: false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(
                        QuroExternalSource(
                            id = initial?.id ?: java.util.UUID.randomUUID().toString(),
                            name = name.trim(),
                            type = type,
                            baseUrl = baseUrl.trim(),
                            token = token.trim(),
                            enabled = enabled,
                            lastSync = initial?.lastSync ?: 0L,
                            lastError = initial?.lastError ?: "",
                        ),
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text(if (initial == null) "添加知识来源" else "编辑知识来源") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称（如 我的腾讯文档）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text("类型", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SOURCE_TYPES.forEach { t ->
                        val selected = t == type
                        FilterChip(
                            selected = selected,
                            onClick = { type = t },
                            label = { Text(SOURCE_TYPE_LABELS[t] ?: t, fontSize = 12.sp) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("API 端点（含导出路径）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("访问令牌（Bearer Token）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = enabled, onCheckedChange = { enabled = it })
                    Text("启用（参与「全部同步」与知识库检索）", fontSize = 13.sp)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "说明：真实鉴权令牌由你提供；本层以 Bearer 拉取并解析为 Markdown 落盘到知识库。不同平台端点/字段差异较大，请填入你可用的值。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}
