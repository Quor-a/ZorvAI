package com.ai.assistance.quro.ui

import android.content.Context
import android.content.Intent
import android.provider.OpenableColumns
import android.widget.Toast
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.OpenInNew
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
    // 协程作用域（供 导入/导出/加载 的 lambda 内 launch 使用，必须在各 launcher 之前声明）
    val scope = rememberCoroutineScope()
    // 导入：从系统文件选择器挑选文档，复制到 knowledge_base（支持任意类型，按原名落盘）
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val name = runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
            }
        }.getOrNull()
        if (name == null) {
            Toast.makeText(ctx, "无法读取所选文件，导入失败", Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        val ext = name.substringAfterLast('.', "").lowercase()
        val supported = setOf("md", "txt", "json", "csv", "docx", "xlsx", "pptx")
        if (ext !in supported) {
            Toast.makeText(ctx, "暂不支持导入该类型：$ext（仅支持 md/txt/json/csv/docx/xlsx/pptx）", Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        // takePersistableUriPermission 仅用于长期持久化，失败不影响本次读取，故忽略异常
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val target = File(dir, safe)
        val ok = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { out -> input.copyTo(out) }
            }
        }.onFailure {
            Toast.makeText(ctx, "导入失败：${it.message}", Toast.LENGTH_LONG).show()
        }.isSuccess
        if (ok) {
            files = listKnowledgeFiles(dir)
            Toast.makeText(ctx, "已导入：$safe", Toast.LENGTH_SHORT).show()
            scope.launch(Dispatchers.IO) { runCatching { buildRagPipeline(ctx).syncDirectory(dir) } }
        }
    }
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
    var showRename by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<File?>(null) }
    var query by remember { mutableStateOf("") }
    // 内容编辑（仅文本类文档 md/txt/json/csv 支持内联编辑；Office 文档走重新导入覆盖）
    var showEdit by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<File?>(null) }
    // 富预览：用应用内 QuroDocumentViewer 渲染（docx/xlsx/pptx 走 WebView 富文本，txt/md 可编辑），
    // 解决"知识库不支持多种文档预览"——此前详情仅展示纯文本提取，Office 文档看不出结构。
    var viewFile by remember { mutableStateOf<File?>(null) }
    // 富编辑器：md/txt/json/csv 用全屏 QuroDocEditorScreen（格式工具栏 + 完整 md 预览 + 字数统计），
    // 新建文档也走这里——替代此前"路径 + 200dp 文本框"的简陋创建对话框。
    var editorFile by remember { mutableStateOf<File?>(null) }

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

    fun renameFile(f: File, newName: String) {
        val raw = newName.trim()
        if (raw.isBlank()) return
        val safe = raw.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val target = File(f.parentFile, if (safe.contains('.')) safe else "$safe.${f.extension}")
        if (target.exists()) {
            Toast.makeText(ctx, "已存在同名文件，无法重命名", Toast.LENGTH_SHORT).show()
            return
        }
        if (f.renameTo(target)) {
            files = listKnowledgeFiles(dir)
            if (selected == f) selected = target
            Toast.makeText(ctx, "已重命名为：${target.name}", Toast.LENGTH_SHORT).show()
            scope.launch(Dispatchers.IO) { runCatching { buildRagPipeline(ctx).syncDirectory(dir) } }
        } else {
            Toast.makeText(ctx, "重命名失败", Toast.LENGTH_SHORT).show()
        }
    }

    // 分层返回：详情视图下，硬件返回键先回到列表（不选 清空 selected）；列表态下本 BackHandler 失效，
    // 由父层（ChatScreen 的 showKnowledge）BackHandler 关闭整个知识库屏。保证「详情→列表→关闭」逐层返回。
    BackHandler(enabled = selected != null) {
        selected = null
        selectedText = ""
    }

    // 富预览优先：Office 文档用应用内渲染器展示（docx/xlsx/pptx 富文本、可编辑）。
    if (viewFile != null) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            QuroDocumentViewer(
                file = viewFile!!,
                onClose = { viewFile = null },
                onExternal = {
                    if (!QuroDocOpener.open(ctx, viewFile!!)) {
                        Toast.makeText(ctx, "未找到可打开该文档的其他应用", Toast.LENGTH_LONG).show()
                    } else viewFile = null
                },
                readOnly = false,
            )
        }
        return
    }

    // 富编辑器（md/txt/json/csv + 新建文档）：全屏编辑，保存后自动重建 RAG 索引并刷新列表。
    if (editorFile != null) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            QuroDocEditorScreen(
                file = editorFile!!,
                onSave = {
                    files = listKnowledgeFiles(dir)
                    scope.launch(Dispatchers.IO) { runCatching { buildRagPipeline(ctx).syncDirectory(dir) } }
                },
                onClose = {
                    editorFile = null
                    files = listKnowledgeFiles(dir)
                    // 若正停留在该文件详情页，同步刷新内容
                    selected?.let { sel -> if (sel.exists()) loadFile(sel) }
                },
            )
        }
        return
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
                        // 文本类（md/txt/json/csv）走富编辑器（自带完整 md 预览）；Office 走 viewer
                        val openDoc: (File) -> Unit = { f ->
                            if (f.extension.lowercase() in KB_TEXT_EXTS) editorFile = f else viewFile = f
                        }
                        IconButton(onClick = { selected?.let { openDoc(it) } }) {
                            Icon(Icons.Filled.OpenInNew, "打开预览（富文本/可编辑）")
                        }
                        // 所有支持的文档类型都可编辑（文本类用内置富编辑器，Office 文档用 viewer 编辑）
                        IconButton(onClick = { selected?.let { openDoc(it) } }) {
                            Icon(Icons.Filled.EditNote, "编辑内容")
                        }
                        IconButton(onClick = { selected?.let { renameTarget = it; showRename = true } }) {
                            Icon(Icons.Filled.Edit, "重命名文档")
                        }
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
                if (selected!!.extension.lowercase() == "md") {
                    // Markdown 完整排版渲染（标题/表格/代码块/图片/删除线/任务列表/引用/链接）
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        MarkdownPreview(selectedText)
                    }
                } else {
                    Text(
                        selectedText,
                        Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 19.sp,
                    )
                }
                if (selected!!.extension.lowercase() in KB_OFFICE_EXTS) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Office 文档（docx/xlsx/pptx）：点击右上角「打开预览」可在应用内查看与编辑文本内容。修改后会自动重建 RAG 索引。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showAdd) {
        AddDocDialog(
            onDismiss = { showAdd = false },
            onConfirm = { path ->
                runCatching {
                    dir.mkdirs()
                    var name = path.trim().trimStart('/').replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    // 无扩展名默认按 Markdown 文档创建（保证出现在知识库列表里）
                    if (!name.contains('.')) name = "$name.md"
                    val f = File(dir, name)
                    f.parentFile?.mkdirs()
                    if (!f.exists()) f.writeText("")
                    showAdd = false
                    // 直接进入富编辑器撰写（格式工具栏 + 完整 md 预览），保存即入库并重建索引
                    editorFile = f
                }.onFailure {
                    Toast.makeText(ctx, "创建失败：${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    if (showRename && renameTarget != null) {
        RenameDialog(
            initial = renameTarget!!.name,
            onDismiss = { showRename = false; renameTarget = null },
            onConfirm = { newName ->
                renameTarget?.let { renameFile(it, newName) }
                showRename = false
                renameTarget = null
            }
        )
    }

    if (showEdit && editTarget != null) {
        EditDocDialog(
            file = editTarget!!,
            onDismiss = { showEdit = false; editTarget = null },
            onConfirm = { newContent ->
                editTarget?.let { f ->
                    runCatching { f.writeText(newContent) }
                    files = listKnowledgeFiles(dir)
                    selectedText = newContent
                    scope.launch(Dispatchers.IO) { runCatching { buildRagPipeline(ctx).syncDirectory(dir) } }
                }
                showEdit = false
                editTarget = null
            }
        )
    }
}

/** 列出 knowledge_base 下的文档（Markdown/JSON/TXT + Office/WPS：docx/xlsx/pptx，递归、按相对路径排序）。 */
private val KB_TEXT_EXTS = setOf("md", "txt", "json", "csv")
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
    onConfirm: (path: String) -> Unit,
) {
    var path by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {},
        title = { Text("新建知识文档") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "输入文档名后直接进入富编辑器撰写（支持 Markdown 格式工具栏与实时预览）。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                UnderlineField(
                    label = "文档名",
                    value = path,
                    onValueChange = { path = it },
                    placeholder = "如 编码规范（默认 .md）",
                )
                Spacer(Modifier.height(12.dp))
                DialogActions(
                    onCancel = onDismiss,
                    onConfirm = { if (path.isNotBlank()) onConfirm(path) },
                )
            }
        },
    )
}

@Composable
private fun RenameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (newName: String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text("重命名文档") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("新文件名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}

@Composable
private fun EditDocDialog(
    file: File,
    onDismiss: () -> Unit,
    onConfirm: (content: String) -> Unit,
) {
    var content by remember {
        mutableStateOf(runCatching { file.readText() }.getOrDefault(""))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(content) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text("编辑内容 · ${file.name}") },
        text = {
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                label = { Text("文档内容") },
                modifier = Modifier.fillMaxWidth().height(320.dp),
                maxLines = 20,
            )
        },
    )
}
