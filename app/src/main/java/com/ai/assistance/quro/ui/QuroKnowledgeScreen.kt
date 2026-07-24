package com.ai.assistance.quro.ui

import android.content.Context
import android.content.Intent
import android.provider.OpenableColumns
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.ai.assistance.quro.core.tools.QuroKnowledgeFiles
import com.ai.assistance.quro.core.knowledge.buildRagPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * 知识库管理界面（Path ② 文件知识库的可视化门面）。
 *
 * 与 [com.ai.assistance.quro.core.tools.QuroToolsKnowledge] 共用 `knowledge_base/` 目录：
 * 这里手动浏览/查看/新建/追加/删除文档，AI 侧则通过 knowledge_search / knowledge_add 工具读写同一目录。
 * 两侧共享同一份数据，互不影响。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuroKnowledgeScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val dir = remember { QuroKnowledgeFiles.dir(ctx) }
    // 文件列表状态（声明在 importLauncher 之前，确保 lambda 内可见，避免前向引用歧义）
    var files by remember { mutableStateOf(listKnowledgeFiles(dir)) }
    // 导入：从系统文件选择器挑选文档，复制到 knowledge_base（支持任意类型，按原名落盘）
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { u ->
            runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val name = runCatching {
                ctx.contentResolver.query(u, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
                }
            }.getOrNull() ?: "imported_${System.currentTimeMillis()}"
            val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val target = File(dir, safe)
            runCatching {
                ctx.contentResolver.openInputStream(u)?.use { input ->
                    target.outputStream().use { out -> input.copyTo(out) }
                }
            }
            files = listKnowledgeFiles(dir)
        }
    }
    var selected by remember { mutableStateOf<File?>(null) }
    var selectedText by remember { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }
    var showSources by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    val filtered = remember(files, query) {
        if (query.isBlank()) files
        else files.filter {
            it.name.contains(query, true) || runCatching { it.relativeTo(dir).path }.getOrDefault(it.name).contains(query, true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("知识库") },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.ArrowBack, "返回") }
                },
                actions = {
                    TextButton(onClick = { showSources = true }) { Text("云盘") }
                    IconButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Filled.FileDownload, "导入文档")
                    }
                    IconButton(onClick = { showAdd = true }) { Icon(Icons.Filled.Add, "添加文档") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { pad ->
        Row(Modifier.fillMaxSize().padding(pad)) {
            // 左栏：文件列表 + 搜索
            Column(Modifier.weight(0.42f).fillMaxHeight()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索文件名…") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(8.dp)
                )
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 8.dp),
                    contentPadding = PaddingValues(bottom = 12.dp)
                ) {
                    if (files.isEmpty()) {
                        item {
                            Text(
                                "知识库为空。点右上角 + 添加文档，或直接对 AI 说「把 XX 存进知识库」。",
                                Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp
                            )
                        }
                    }
                    items(filtered, key = { it.absolutePath }) { f ->
                        val rel = runCatching { f.relativeTo(dir).path }.getOrDefault(f.name)
                        ListItem(
                            headlineContent = { Text(f.name, fontSize = 14.sp) },
                            supportingContent = {
                                Text(rel, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            },
                            leadingContent = { Icon(Icons.Filled.Description, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                IconButton(onClick = {
                                    f.delete()
                                    files = listKnowledgeFiles(dir)
                                    if (selected == f) { selected = null; selectedText = "" }
                                    scope.launch(Dispatchers.IO) { runCatching { buildRagPipeline(ctx).syncDirectory(dir) } }
                                }) {
                                    Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error)
                                }
                            },
                            modifier = Modifier.clickable {
                                selected = f
                                selectedText = if (f.extension.lowercase() in setOf("docx", "xlsx", "pptx"))
                                    extractOfficeText(f) else runCatching { f.readText() }.getOrDefault("（无法读取内容）")
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }
            // 右栏：内容预览
            Box(
                Modifier.weight(0.58f).fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            ) {
                if (selected != null) {
                    Column(Modifier.fillMaxSize().padding(14.dp)) {
                        Text(selected!!.name, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            selectedText,
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("选择左侧文件查看内容", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddDocDialog(
            onDismiss = { showAdd = false },
            onConfirm = { path, content, append ->
                runCatching {
                    dir.mkdirs()
                    val f = File(dir, path.trimStart('/'))
                    f.parentFile?.mkdirs()
                    if (append) f.appendText(content) else f.writeText(content)
                }
                files = listKnowledgeFiles(dir)
                showAdd = false
                scope.launch(Dispatchers.IO) { runCatching { buildRagPipeline(ctx).syncDirectory(dir) } }
            }
        )
    }

    if (showSources) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            QuroKnowledgeSourcesScreen(onClose = { showSources = false })
        }
    }
}

/** 列出 knowledge_base 下的文档（Markdown/JSON/TXT + Office/WPS：docx/xlsx/pptx，递归、按相对路径排序）。 */
private val KB_TEXT_EXTS = setOf("md", "txt", "json")
private val KB_OFFICE_EXTS = setOf("docx", "xlsx", "pptx")
private fun listKnowledgeFiles(dir: File): List<File> {
    if (!dir.exists()) dir.mkdirs()
    return dir.walkTopDown()
        .filter { it.isFile && it.extension.lowercase() in (KB_TEXT_EXTS + KB_OFFICE_EXTS) }
        .sortedWith(compareBy({ runCatching { it.relativeTo(dir).path }.getOrDefault(it.name) }, { it.name }))
        .toList()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddDocDialog(
    onDismiss: () -> Unit,
    onConfirm: (path: String, content: String, append: Boolean) -> Unit
) {
    var path by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var append by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(enabled = path.isNotBlank(), onClick = { onConfirm(path, content, append) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text("添加知识文档") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text("路径（如 规范/编码风格.md）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("文档内容") },
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    maxLines = 12
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = append, onCheckedChange = { append = it })
                    Text("追加模式（不勾 = 覆盖原文件）", fontSize = 13.sp)
                }
            }
        }
    )
}
