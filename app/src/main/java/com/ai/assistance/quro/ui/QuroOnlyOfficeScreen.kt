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
import java.io.File

/**
 * 统一 WPS / 文档中心（v142 重写为「完全内置」，不再外跳 ONLYOFFICE、不再要求下载安装任何第三方应用）。
 *
 * 文档（.docx / .xlsx / .pptx / .pdf / .txt / .md / 代码 / 图片）一律在应用内
 * [QuroDocumentViewer] 中打开与查看；txt / md / 代码类支持内置编辑并写回原文件。
 * 仅当用户主动点「其他应用」时才调起外部 App（兜底）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuroOnlyOfficeScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    var files by remember { mutableStateOf(listOfficeFiles(ctx)) }
    var viewerFile by remember { mutableStateOf<File?>(null) }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WPS / 文档") },
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
                modifier = Modifier.fillMaxWidth()
            ) { Text("选择本地文档…") }

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
