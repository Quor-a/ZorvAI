package com.ai.assistance.quro.ui
import androidx.compose.ui.res.stringResource
import com.ai.assistance.quro.R
import com.ai.assistance.quro.util.qstr

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 全屏文档编辑器 - 类似 WPS/Office 的编辑体验
 * 支持 Markdown 格式，提供丰富的格式化工具栏
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuroDocEditorScreen(
    file: File? = null,
    initialContent: String = "",
    onSave: (String) -> Unit = {},
    onClose: () -> Unit = {}
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    
    var content by remember { mutableStateOf(initialContent) }
    var isModified by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var showFormatBar by remember { mutableStateOf(true) }
    var wordCount by remember { mutableIntStateOf(0) }
    var lineCount by remember { mutableIntStateOf(0) }
    var cursorPosition by remember { mutableStateOf("1:1") }
    var fileStatus by remember { mutableStateOf(qstr(R.string.qk_00334)) }
    var isSaving by remember { mutableStateOf(false) }
    
    // 初始化文件内容
    LaunchedEffect(file) {
        if (file != null && file.exists()) {
            withContext(Dispatchers.Main) {
                fileStatus = qstr(R.string.qk_01804, (file.name).toString())
            }
            withContext(Dispatchers.IO) {
                runCatching {
                    val fileContent = file.readText(Charsets.UTF_8)
                    withContext(Dispatchers.Main) {
                        content = fileContent
                        isModified = false
                        fileStatus = qstr(R.string.qk_01805, (file.name).toString())
                        // 2秒后恢复状态
                        kotlinx.coroutines.delay(2000)
                        fileStatus = qstr(R.string.qk_00334)
                    }
                }.onFailure {
                    withContext(Dispatchers.Main) {
                        fileStatus = qstr(R.string.qk_01806, (it.message).toString())
                        // 3秒后恢复状态
                        kotlinx.coroutines.delay(3000)
                        fileStatus = qstr(R.string.qk_00334)
                    }
                }
            }
        } else {
            withContext(Dispatchers.Main) {
                fileStatus = qstr(R.string.qk_01807)
            }
        }
    }
    
    // 更新字数统计
    LaunchedEffect(content) {
        wordCount = content.split(Regex("\\s+")).filter { it.isNotBlank() }.size
        lineCount = content.lines().size
    }
    
    // 保存文件
    fun saveFile() {
        scope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                isSaving = true
                fileStatus = qstr(R.string.qk_00389)
            }
            
            runCatching {
                if (file != null) {
                    withContext(Dispatchers.Main) {
                        fileStatus = qstr(R.string.qk_01808, (file.absolutePath).toString())
                    }
                    file.writeText(content, Charsets.UTF_8)
                    withContext(Dispatchers.Main) {
                        isModified = false
                        isSaving = false
                        fileStatus = qstr(R.string.qk_01809, (file.name).toString())
                        Toast.makeText(ctx, qstr(R.string.qk_01810, (file.absolutePath).toString()), Toast.LENGTH_SHORT).show()
                        onSave(content)
                        // 3秒后恢复状态
                        kotlinx.coroutines.delay(3000)
                        fileStatus = qstr(R.string.qk_00334)
                    }
                } else {
                    // 新建文件：保存到应用私有 Documents 目录（Android 10+ 分区存储无法直写公共 Download）
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                    val fileName = qstr(R.string.qk_01811, (timestamp).toString())
                    val docsDir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                        ?: File(ctx.filesDir, "documents")
                    if (!docsDir.exists()) docsDir.mkdirs()
                    val newFile = File(docsDir, fileName)
                    
                    withContext(Dispatchers.Main) {
                        fileStatus = qstr(R.string.qk_01812, (newFile.absolutePath).toString())
                    }
                    newFile.writeText(content, Charsets.UTF_8)
                    withContext(Dispatchers.Main) {
                        isModified = false
                        isSaving = false
                        fileStatus = qstr(R.string.qk_01813, (fileName).toString())
                        Toast.makeText(ctx, qstr(R.string.qk_01814, (newFile.absolutePath).toString()), Toast.LENGTH_SHORT).show()
                        onSave(content)
                        // 3秒后恢复状态
                        kotlinx.coroutines.delay(3000)
                        fileStatus = qstr(R.string.qk_00334)
                    }
                }
            }.onFailure {
                withContext(Dispatchers.Main) {
                    isSaving = false
                    fileStatus = qstr(R.string.qk_01815, (it.message).toString())
                    Toast.makeText(ctx, qstr(R.string.qk_01815, (it.message).toString()), Toast.LENGTH_SHORT).show()
                    // 3秒后恢复状态
                    kotlinx.coroutines.delay(3000)
                    fileStatus = qstr(R.string.qk_00334)
                }
            }
        }
    }
    
    // 格式化工具栏
    @Composable
    fun FormatToolbar() {
        val formatActions = listOf(
            Triple(stringResource(R.string.qk_01816), Icons.Filled.FormatBold, "**"),
            Triple(stringResource(R.string.qk_01817), Icons.Filled.FormatItalic, "*"),
            Triple(stringResource(R.string.qk_01818), Icons.Filled.Title, "# "),
            Triple(stringResource(R.string.qk_01819), Icons.Filled.Title, "## "),
            Triple(stringResource(R.string.qk_01820), Icons.Filled.Title, "### "),
            Triple(stringResource(R.string.qk_01821), Icons.Filled.FormatListBulleted, "- "),
            Triple(stringResource(R.string.qk_01822), Icons.Filled.FormatListNumbered, "1. "),
            Triple(stringResource(R.string.qk_01823), Icons.Filled.FormatQuote, "> "),
            Triple(stringResource(R.string.qk_01824), Icons.Filled.Code, "```\n"),
            Triple(stringResource(R.string.qk_01825), Icons.Filled.HorizontalRule, "---\n"),
            Triple(stringResource(R.string.qk_01826), Icons.Filled.Link, stringResource(R.string.qk_01827)),
            Triple(stringResource(R.string.qk_00149), Icons.Filled.Image, stringResource(R.string.qk_01828))
        )
        
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .background(cs.surface)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(formatActions) { (name, icon, format) ->
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    tooltip = { PlainTooltip { Text(name) } },
                    state = rememberTooltipState()
                ) {
                    IconButton(
                        onClick = {
                            // 插入格式化标记
                            content = content + format
                            isModified = true
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            icon,
                            contentDescription = name,
                            tint = cs.onSurface,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (file != null) file.name else stringResource(R.string.qk_01807),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                        // 文件路径显示
                        if (file != null) {
                            Text(
                                text = file.absolutePath,
                                fontSize = 10.sp,
                                color = cs.onSurfaceVariant.copy(alpha = 0.7f),
                                maxLines = 1,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        // 文件状态显示
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            if (isModified) {
                                Text(text = stringResource(R.string.qk_00401),
                                    fontSize = 11.sp,
                                    color = cs.error,
                                    maxLines = 1
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            if (isSaving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 1.5.dp,
                                    color = cs.primary
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Text(
                                text = fileStatus,
                                fontSize = 11.sp,
                                color = when {
                                    fileStatus.contains("失败") -> cs.error
                                    fileStatus.contains("保存中") || fileStatus.contains("创建中") -> cs.primary
                                    else -> cs.onSurfaceVariant.copy(alpha = 0.7f)
                                },
                                maxLines = 1
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isModified) {
                            showSaveDialog = true
                        } else {
                            onClose()
                        }
                    }) {
                        Icon(Icons.Filled.ArrowBack, stringResource(R.string.qk_00143))
                    }
                },
                actions = {
                    // 格式栏切换
                    IconButton(onClick = { showFormatBar = !showFormatBar }) {
                        Icon(
                            if (showFormatBar) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = stringResource(R.string.qk_01830)
                        )
                    }
                    
                    // 保存按钮
                    IconButton(
                        onClick = { saveFile() },
                        enabled = isModified
                    ) {
                        Icon(
                            Icons.Filled.Save,
                            contentDescription = stringResource(R.string.qk_00198),
                            tint = if (isModified) cs.primary else cs.onSurface.copy(alpha = 0.5f)
                        )
                    }
                    
                    // 更多选项：真实分享/导出（通过 FileProvider 授权给系统分享面板）
                    IconButton(onClick = {
                        val target = file ?: run {
                            Toast.makeText(ctx, qstr(R.string.qk_01831), Toast.LENGTH_SHORT).show()
                            return@IconButton
                        }
                        if (!target.exists()) {
                            Toast.makeText(ctx, qstr(R.string.qk_01832), Toast.LENGTH_SHORT).show()
                            return@IconButton
                        }
                        val uri = QuroDocOpener.safeUri(ctx, target)
                        if (uri == null) {
                            Toast.makeText(ctx, qstr(R.string.qk_01833), Toast.LENGTH_SHORT).show()
                            return@IconButton
                        }
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = QuroDocOpener.guessMime(target.name)
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        runCatching {
                            ctx.startActivity(Intent.createChooser(share, qstr(R.string.qk_01834, (target.name).toString())))
                        }.onFailure {
                            Toast.makeText(ctx, qstr(R.string.qk_01835), Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Icon(Icons.Filled.Share, stringResource(R.string.qk_00090))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = cs.surface,
                    titleContentColor = cs.onSurface
                )
            )
        },
        bottomBar = {
            // 状态栏
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = cs.surfaceVariant,
                tonalElevation = 2.dp
            ) {
                Column {
                    // 文件信息行
                    if (file != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = stringResource(R.string.qk_01836, (file.name).toString()),
                                fontSize = 11.sp,
                                color = cs.onSurfaceVariant.copy(alpha = 0.8f),
                                maxLines = 1,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "${file.length() / 1024} KB",
                                fontSize = 11.sp,
                                color = cs.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                        }
                    }
                    // 编辑信息行
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = stringResource(R.string.qk_00405, (lineCount).toString(), (wordCount).toString()),
                            fontSize = 12.sp,
                            color = cs.onSurfaceVariant
                        )
                        Text(
                            text = cursorPosition,
                            fontSize = 12.sp,
                            color = cs.onSurfaceVariant
                        )
                        Text(
                            text = "Markdown",
                            fontSize = 12.sp,
                            color = cs.onSurfaceVariant
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(cs.background)
        ) {
            // 格式化工具栏
            // 根据文件类型选择编辑器
            // 新建文档（file == null）默认按 Markdown 编辑；已存在文件按扩展名判定
            val isMarkdown = file?.name?.endsWith(".md") == true ||
                    file?.name?.endsWith(".markdown") == true ||
                    (file == null)
            
            if (isMarkdown) {
                // Markdown文件使用专业的Markdown编辑器
                QuroMarkdownEditor(
                    content = content,
                    onContentChange = { newValue ->
                        content = newValue
                        isModified = true
                    },
                    modifier = Modifier.weight(1f)
                )
            } else {
                // 其他文件使用普通编辑器
                if (showFormatBar) {
                    FormatToolbar()
                    HorizontalDivider(color = cs.outlineVariant)
                }
                
                // 编辑区域
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                ) {
                    SelectionContainer {
                        OutlinedTextField(
                            value = content,
                            onValueChange = { newValue ->
                                content = newValue
                                isModified = true
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            textStyle = TextStyle(
                                fontSize = 16.sp,
                                lineHeight = 24.sp,
                                fontFamily = FontFamily.Monospace
                            ),
                            placeholder = {
                                Text(text = stringResource(R.string.qk_01837),
                                    fontSize = 16.sp,
                                    color = cs.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                cursorColor = cs.primary
                            )
                        )
                    }
                }
            }
        }
    }
    
    // 保存确认对话框
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text(stringResource(R.string.qk_01838)) },
            text = { Text(stringResource(R.string.qk_01839)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSaveDialog = false
                        saveFile()
                        onClose()
                    }
                ) {
                    Text(stringResource(R.string.qk_00198))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showSaveDialog = false
                        onClose()
                    }
                ) {
                    Text(stringResource(R.string.qk_01840))
                }
            }
        )
    }
}

/**
 * 文档编辑器预览 - 用于快速查看和编辑
 */
@Composable
fun DocumentEditorPreview(
    content: String,
    onContentChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(cs.surfaceVariant)
            .padding(16.dp)
    ) {
        SelectionContainer {
            Text(
                text = content.ifBlank { stringResource(R.string.qk_01841) },
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = if (content.isBlank()) cs.onSurfaceVariant.copy(alpha = 0.5f) else cs.onSurface
            )
        }
    }
}