package com.ai.assistance.quro.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.ai.assistance.quro.core.tools.AiwpsCreateTool
import com.ai.assistance.quro.ui.LocalOfficeEditorScreen
import java.io.File

/**
 * 统一文档查看 / 管理（v142 重写为「完全内置」，不再外跳 ONLYOFFICE、不再要求下载安装任何第三方应用）。
 *
 * 文档（.docx / .xlsx / .pptx / .pdf / .txt / .md / 代码 / 图片）一律在应用内
 * [QuroDocumentViewer] 中打开与查看；txt / md / 代码类支持内置编辑并写回原文件。
 * 仅当用户主动点「其他应用」时才调起外部 App（兜底）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuroDocScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    var files by remember { mutableStateOf(listOfficeFiles(ctx)) }
    var viewerFile by remember { mutableStateOf<File?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var editorFile by remember { mutableStateOf<File?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var editorType by remember { mutableStateOf("") } // docx, xlsx, pptx, txt, md等
    val scope = rememberCoroutineScope()

    // 进入即刷新列表
    LaunchedEffect(Unit) { files = listOfficeFiles(ctx) }

    // 选本地文档
    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val name = runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
            }
        }.getOrNull() ?: "doc_${System.currentTimeMillis()}.docx"
        val dest = File(ctx.cacheDir, "quro_office_${System.currentTimeMillis()}_$name")
        runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { os -> input.copyTo(os) } }
        }
        if (dest.exists() && dest.length() > 0) {
            viewerFile = dest
        } else {
            Toast.makeText(ctx, "无法读取该文档，请选择 .docx/.xlsx/.pptx/.pdf 等文件", Toast.LENGTH_SHORT).show()
        }
    }

    if (viewerFile != null) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            QuroDocumentViewer(
                file = viewerFile!!,
                onClose = { viewerFile = null },
                onExternal = {
                    if (!QuroDocOpener.open(ctx, viewerFile!!)) {
                        Toast.makeText(ctx, "未找到可打开该文档的其他应用", Toast.LENGTH_LONG).show()
                    } else viewerFile = null
                },
                readOnly = false
            )
        }
        return
    }
    
    if (showEditor) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            // 对于Office格式，使用本地Office编辑器
            // 对于文本格式，使用全屏编辑器
            when (editorType) {
                "docx", "xlsx", "pptx", "doc", "xls", "ppt" -> {
                    LocalOfficeEditorScreen(
                        file = editorFile,
                        onClose = {
                            showEditor = false
                            editorFile = null
                            editorType = ""
                            files = listOfficeFiles(ctx)
                        }
                    )
                }
                else -> {
                    QuroDocEditorScreen(
                        file = editorFile,
                        onClose = {
                            showEditor = false
                            editorFile = null
                            editorType = ""
                            files = listOfficeFiles(ctx)
                        }
                    )
                }
            }
        }
        return
    }

    if (showCreate) {
        CreateDocDialog(
            onDismiss = { showCreate = false },
            onCreate = { type, title, content ->
                scope.launch(Dispatchers.IO) {
                    // 创建空白文档
                    val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "QuroDocs")
                    dir.mkdirs()
                    val timestamp = System.currentTimeMillis()
                    val filename = "${type}_${timestamp}"
                    val file = File(dir, "$filename.$type")
                    
                    // 创建空白文件
                    when (type) {
                        "docx", "xlsx", "pptx" -> {
                            // 对于 Office 格式，使用 AiwpsCreateTool 创建空白文档
                            val json = JSONObject().apply {
                                put("type", type)
                                put("title", title.ifBlank { filename })
                                put("content", content.ifBlank { " " }) // 空白内容
                            }.toString()
                            val r = runCatching { AiwpsCreateTool().run(ctx, json) }.getOrDefault("生成失败")
                            // 从结果中提取文件路径（兼容多种格式）
                            val path = when {
                                r.contains("已生成") -> {
                                    // 匹配 "已生成 docx 文档：/path/to/file.docx（...）"
                                    Regex("""已生成\s+\w+\s+文档：(.+?)（""").find(r)?.groupValues?.getOrNull(1)
                                }
                                r.contains("文档：") -> {
                                    // 匹配 "文档：/path/to/file（...）"
                                    Regex("""文档：(.+?)（""").find(r)?.groupValues?.getOrNull(1)
                                }
                                else -> null
                            }
                            path?.let {
                                withContext(Dispatchers.Main) {
                                    showCreate = false
                                    files = listOfficeFiles(ctx)
                                    // 根据类型打开专门的编辑器
                                    editorFile = File(it.trim())
                                    editorType = type
                                    showEditor = true
                                }
                            } ?: run {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(ctx, "创建失败: $r", Toast.LENGTH_SHORT).show()
                                    showCreate = false
                                }
                            }
                        }
                        else -> {
                            // 对于文本格式，打开全屏编辑器
                            withContext(Dispatchers.Main) {
                                showCreate = false
                                editorFile = file
                                editorType = type
                                showEditor = true
                            }
                        }
                    }
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("文档") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(
                "文档在应用内直接打开与查看，无需安装任何第三方办公软件。" +
                        "支持 Word / Excel / PPT / PDF / TXT / Markdown / 代码 / 图片；TXT / Markdown / 代码可在应用内编辑。",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp
            )
            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        pickLauncher.launch(
                            arrayOf(
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                                "application/pdf", "text/plain", "text/markdown"
                            )
                        )
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("选择本地文档…") }
                OutlinedButton(
                    onClick = { showCreate = true },
                    modifier = Modifier.weight(1f)
                ) { Text("新建文档") }
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Text("本机 Office 文档", fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp))

            if (files.isEmpty()) {
                Text(
                    "暂无文档。可用 AI「文档生成」产出，或点上方按钮选择本地文件。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                    items(files, key = { it.absolutePath }) { f ->
                        ListItem(
                            headlineContent = { Text(f.name, fontSize = 14.sp) },
                            supportingContent = { Text("${f.length() / 1024} KB", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            leadingContent = { Icon(Icons.Filled.Description, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = { Icon(Icons.Filled.OpenInNew, null) },
                            modifier = Modifier.clickable { viewerFile = f }
                        )
                        HorizontalDivider()
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Text("说明", fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp))
            Text(
                "文档渲染引擎随包内置（mammoth.js / SheetJS / pdf.js），断网也能打开。" +
                        "AI 亦可在对话中直接调用 aiwps_create 生成真实 .docx/.xlsx/.pptx 文档。",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp
            )
        }
    }
}

private fun listOfficeFiles(ctx: Context): List<File> {
    val exts = setOf("docx", "xlsx", "pptx", "pdf", "txt", "md", "markdown", "csv", "json", "xml", "log")
    val dirs = buildList {
        // 应用专属文档目录：aiwps_create / enhanced_doc_create / 文档页「新建」均写到这里
        add(File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "QuroDocs"))
        // 应用专属下载目录（QuroDownloadUtil 写入）
        ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { add(File(it, "Quro")) }
        // 公共目录（兼容旧版静态 createDocument 写入到 Documents/QuroDocs 的情形）
        runCatching { add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)) }
        runCatching { add(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "QuroDocs")) }
        runCatching { add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)) }
        runCatching { add(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Quro")) }
    }
    return dirs.flatMap { d ->
        runCatching { d.listFiles()?.toList().orEmpty() }.getOrDefault(emptyList())
    }.filter { it.isFile && it.extension.lowercase() in exts }
        .sortedByDescending { it.lastModified() }
        .take(200)
}

/**
 * 新建文档对话框：选择文档类型后创建空白文档并进入编辑器。
 * 用户选择类型 → 创建空白文件 → 用 QuroDocEditorScreen 打开编辑。
 */
@Composable
private fun CreateDocDialog(
    onDismiss: () -> Unit,
    onCreate: (type: String, title: String, content: String) -> Unit,
) {
    var type by remember { mutableStateOf("docx") }
    val types = listOf(
        "docx" to "Word", "xlsx" to "Excel", "pptx" to "PPT",
        "pdf" to "PDF", "md" to "Markdown", "txt" to "文本", "csv" to "表格", "html" to "网页"
    )
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { 
                // 创建空白文档，标题为空，内容为空
                onCreate(type, "", "") 
            }) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text("新建文档") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text("选择文档类型", fontSize = 14.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                types.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                        row.forEach { (t, label) ->
                            val sel = type == t
                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { type = t },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (sel) cs.primary else cs.surfaceVariant
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    label,
                                    color = if (sel) cs.onPrimary else cs.onSurface,
                                    fontSize = 13.sp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 12.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "选择类型后将创建空白文档并进入编辑器。",
                    fontSize = 12.sp, color = cs.onSurfaceVariant
                )
            }
        }
    )
}
