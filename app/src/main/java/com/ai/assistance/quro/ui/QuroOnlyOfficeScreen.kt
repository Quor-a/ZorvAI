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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.ai.assistance.quro.core.tools.AiwpsCreateTool
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

    if (showCreate) {
        CreateDocDialog(
            onDismiss = { showCreate = false },
            onCreate = { type, title, content ->
                scope.launch(Dispatchers.IO) {
                    val json = JSONObject().apply {
                        put("type", type)
                        put("title", title)
                        put("content", content)
                    }.toString()
                    val r = runCatching { AiwpsCreateTool().run(ctx, json) }.getOrDefault("生成失败")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(ctx, if (r.startsWith("已生成")) "已新建文档" else r, Toast.LENGTH_LONG).show()
                        showCreate = false
                        files = listOfficeFiles(ctx)
                        val path = Regex("""文档：(.+?)（""").find(r)?.groupValues?.getOrNull(1)
                        path?.let { viewerFile = File(it.trim()) }
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
        add(File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "QuroDocs"))
        runCatching { add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)) }
        runCatching { add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)) }
    }
    return dirs.flatMap { d ->
        runCatching { d.listFiles()?.toList().orEmpty() }.getOrDefault(emptyList())
    }.filter { it.isFile && it.extension.lowercase() in exts }
        .sortedByDescending { it.lastModified() }
        .take(200)
}

/**
 * 新建文档对话框：复用 [AiwpsCreateTool] 自研 OOXML 生成能力，产出真实可打开的
 * .docx / .xlsx / .txt / .md（文本级撰写，非富文本回写）。生成成功后由调用方刷新列表并打开。
 */
@Composable
private fun CreateDocDialog(
    onDismiss: () -> Unit,
    onCreate: (type: String, title: String, content: String) -> Unit,
) {
    var type by remember { mutableStateOf("docx") }
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    val types = listOf(
        "docx" to "Word", "xlsx" to "Excel", "pptx" to "PPT",
        "pdf" to "PDF", "md" to "Markdown", "txt" to "文本", "csv" to "表格", "html" to "网页"
    )
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { if (content.isNotBlank()) onCreate(type, title, content) }) { Text("生成") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text("新建文档") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text("格式", fontSize = 13.sp, color = cs.onSurfaceVariant)
                types.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                        row.forEach { (t, label) ->
                            val sel = type == t
                            Text(
                                label,
                                color = if (sel) cs.onPrimary else cs.onSurface,
                                fontSize = 13.sp,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                    .background(if (sel) cs.primary else cs.surfaceVariant)
                                    .clickable { type = t }.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(title, { title = it }, label = { Text("标题（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    content, { content = it }, label = { Text("正文") }, minLines = 4, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("docx 按换行分段（`**加粗**`/表格）；xlsx 按换行分行、制表/逗号分列（`### 表名` 多表）；pptx 首行标题+要点（`---` 分页）；pdf/md/txt/csv/html 原样写入") }
                )
                Spacer(Modifier.height(6.dp))
                Text("保存位置：应用私有 Documents/QuroDocs，生成后自动出现在下方列表并打开。当前为文本级撰写（结构化生成真实 OOXML，非富文本回写）。", fontSize = 12.sp, color = cs.onSurfaceVariant)
            }
        }
    )
}
