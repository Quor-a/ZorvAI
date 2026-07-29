package com.ai.assistance.quro.ui

import android.content.Context
import android.content.Intent
import android.provider.OpenableColumns
import android.widget.Toast
import kotlinx.coroutines.withContext
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
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.ai.assistance.quro.core.tools.QuroKnowledgeFiles
import com.ai.assistance.quro.core.knowledge.buildRagPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * 知识库管理界面（Path ② 文件知识库的可视化门面，本地 RAG）。
 *
 * 与 [com.ai.assistance.quro.core.tools.QuroToolsKnowledge] 共用 `knowledge_base/` 目录：
 * 这里手动浏览/查看/新建/追加/删除文档，AI 侧则通过 knowledge_search / knowledge_add 工具读写同一目录。
 * 两侧共享同一份数据，互不影响。
 *
 * UI 重写（v329）：改为移动端单栏卡片式（复用 SetGroup / SetRowClickable / InfoBox / UnderlineField 等
 * 设置风组件），点击文件进入详情预览，遵循「一层一层返回」导航；彻底移除已删除的云盘知识来源入口。
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
    // 协程作用域（声明在 exportLauncher 之前，供 lambda 内 launch 使用）
    val scope = rememberCoroutineScope()
    // 导出：把 knowledge_base/ 整库打包为 zip 经 SAF 写出
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            runCatching {
                ctx.contentResolver.openOutputStream(uri)?.use { os ->
                    java.util.zip.ZipOutputStream(os).use { zos ->
                        dir.walkTopDown().filter { it.isFile }.forEach { f ->
                            val entryName = runCatching { f.relativeTo(dir).path }.getOrDefault(f.name)
                            zos.putNextEntry(java.util.zip.ZipEntry(entryName))
                            f.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                }
                withContext(Dispatchers.Main) { Toast.makeText(ctx, "知识库已导出", Toast.LENGTH_SHORT).show() }
            }.onFailure {
                withContext(Dispatchers.Main) { Toast.makeText(ctx, "导出失败：${it.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }
    var selected by remember { mutableStateOf<File?>(null) }
    var selectedText by remember { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    val filtered = remember(files, query) {
        if (query.isBlank()) files
        else files.filter {
            it.name.contains(query, true) || runCatching { it.relativeTo(dir).path }.getOrDefault(it.name).contains(query, true)
        }
    }

    fun rel(f: File) = runCatching { f.relativeTo(dir).path }.getOrDefault(f.name)

    fun loadFile(f: File) {
        selected = f
        selectedText = "读取中…"
        // 点击回调跑在主线程，大文档 OOXML 解析/读盘会 ANR → 移至 IO 线程，先给占位文案避免界面假死。
        scope.launch(Dispatchers.IO) {
            selectedText = if (f.extension.lowercase() in setOf("docx", "xlsx", "pptx"))
                extractOfficeText(f) else runCatching { f.readText() }.getOrDefault("（无法读取内容）")
        }
    }

    fun deleteFile(f: File) {
        f.delete()
        files = listKnowledgeFiles(dir)
        if (selected == f) { selected = null; selectedText = "" }
        scope.launch(Dispatchers.IO) { runCatching { buildRagPipeline(ctx).syncDirectory(dir) } }
    }

    // 分层返回：详情视图下，硬件返回键先回到列表（不选 清空 selected）；列表态下本 BackHandler 失效，
    // 由父层（ChatScreen 的 showKnowledge）BackHandler 关闭整个知识库屏。保证「详情→列表→关闭」逐层返回。
    BackHandler(enabled = selected != null) {
        selected = null
        selectedText = ""
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selected != null) selected!!.name else "知识库",
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (selected != null) { selected = null; selectedText = "" } else onClose() }) {
                        Icon(Icons.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    if (selected == null) {
                        IconButton(onClick = { exportLauncher.launch("quro_knowledge_${System.currentTimeMillis()}.zip") }) {
                            Icon(Icons.Filled.Share, "导出知识库（ZIP）")
                        }
                        IconButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Filled.FileDownload, "导入文档")
                        }
                        IconButton(onClick = { showAdd = true }) { Icon(Icons.Filled.Add, "添加文档") }
                    } else {
                        IconButton(onClick = { selected?.let { deleteFile(it) } }) {
                            Icon(Icons.Filled.Delete, "删除文档", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { pad ->
        if (selected == null) {
            // —— 列表视图（单栏卡片式）——
            Column(
                Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                InfoBox(
                    "本地知识库（RAG）。文档保存在应用私有 knowledge_base/ 目录，仅在你的设备上做向量化检索，" +
                            "不上传任何云端。你也可以直接对 AI 说「把 XX 存进知识库」。",
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索文件名…") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                GroupCaption("文档（${filtered.size}）")
                if (files.isEmpty()) {
                    SetGroup {
                        Text(
                            "知识库为空。点右上角 + 添加文档，或从系统文件选择器导入。",
                            Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                        )
                    }
                } else {
                    SetGroup {
                        filtered.forEachIndexed { i, f ->
                            SetRowClickable(
                                icon = Icons.Filled.Description,
                                name = f.name,
                                sub = rel(f),
                                onClick = { loadFile(f) },
                            )
                            if (i < filtered.lastIndex) HorizontalDivider()
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                SetGroup {
                    SetRowClickable(
                        icon = Icons.Filled.Refresh,
                        name = "重建索引",
                        sub = "重新扫描并向量化全部文档",
                        onClick = {
                            scope.launch(Dispatchers.IO) { runCatching { buildRagPipeline(ctx).syncDirectory(dir) } }
                        },
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        } else {
            // —— 详情预览视图（一层一层返回）——
            Column(
                Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(
                    selected!!.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    rel(selected!!),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    selectedText,
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 19.sp,
                )
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
    onConfirm: (path: String, content: String, append: Boolean) -> Unit,
) {
    var path by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var append by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {},
        title = { Text("添加知识文档") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState())
            ) {
                UnderlineField(
                    label = "路径",
                    value = path,
                    onValueChange = { path = it },
                    placeholder = "如 规范/编码风格.md",
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("文档内容") },
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    maxLines = 12,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = append, onCheckedChange = { append = it })
                    Text("追加模式（不勾 = 覆盖原文件）", fontSize = 13.sp)
                }
                Spacer(Modifier.height(12.dp))
                DialogActions(
                    onCancel = onDismiss,
                    onConfirm = { if (path.isNotBlank()) onConfirm(path, content, append) },
                )
            }
        },
    )
}
