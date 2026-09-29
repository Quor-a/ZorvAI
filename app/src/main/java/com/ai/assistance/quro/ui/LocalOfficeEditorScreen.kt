package com.ai.assistance.quro.ui
import androidx.compose.ui.res.stringResource
import com.ai.assistance.quro.R
import com.ai.assistance.quro.util.qstr

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.core.tools.LocalOfficeEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 本地Office文档编辑器界面
 * 使用Apache POI实现真正的本地Word/Excel/PPT编辑功能
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalOfficeEditorScreen(
    file: File?,
    onClose: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    
    var content by remember { mutableStateOf("") }
    var isModified by remember { mutableStateOf(false) }
    var fileStatus by remember { mutableStateOf(qstr(R.string.qk_00334)) }
    var isSaving by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<LocalOfficeEditor.DocumentEditor?>(null) }
    var wordCount by remember { mutableIntStateOf(0) }
    var lineCount by remember { mutableIntStateOf(0) }
    
    // 初始化编辑器
    LaunchedEffect(file) {
        if (file != null && file.exists()) {
            withContext(Dispatchers.Main) {
                fileStatus = qstr(R.string.qk_00385, (file.name).toString())
            }
            withContext(Dispatchers.IO) {
                val officeEditor = LocalOfficeEditor(ctx)
                val docEditor = officeEditor.openDocument(file)
                if (docEditor != null) {
                    editor = docEditor
                    val fileContent = docEditor.readContent()
                    withContext(Dispatchers.Main) {
                        content = fileContent
                        isModified = false
                        fileStatus = qstr(R.string.qk_00386, (file.name).toString())
                        kotlinx.coroutines.delay(2000)
                        fileStatus = qstr(R.string.qk_00334)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        fileStatus = qstr(R.string.qk_00387, (file.name).toString())
                        kotlinx.coroutines.delay(3000)
                        fileStatus = qstr(R.string.qk_00334)
                    }
                }
            }
        } else {
            withContext(Dispatchers.Main) {
                fileStatus = qstr(R.string.qk_00388)
            }
        }
    }
    
    // 更新统计信息
    LaunchedEffect(content) {
        wordCount = content.split(Regex("\\s+")).filter { it.isNotBlank() }.size
        lineCount = content.lines().size
    }
    
    // 保存文件
    fun saveFile() {
        val docEditor = editor ?: return
        
        scope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                isSaving = true
                fileStatus = qstr(R.string.qk_00389)
            }
            
            val success = docEditor.writeContent(content)
            
            withContext(Dispatchers.Main) {
                isSaving = false
                if (success) {
                    isModified = false
                    fileStatus = qstr(R.string.qk_00390, (file?.name).toString())
                    Toast.makeText(ctx, qstr(R.string.qk_00391, (file?.absolutePath).toString()), Toast.LENGTH_SHORT).show()
                } else {
                    fileStatus = qstr(R.string.qk_00392)
                    Toast.makeText(ctx, qstr(R.string.qk_00392), Toast.LENGTH_SHORT).show()
                }
                kotlinx.coroutines.delay(3000)
                fileStatus = qstr(R.string.qk_00334)
            }
        }
    }
    
    // 获取文档类型图标
    @Composable
    fun getFileIcon(extension: String): androidx.compose.ui.graphics.vector.ImageVector {
        return when (extension.lowercase()) {
            "docx", "doc" -> Icons.Filled.Description
            "xlsx", "xls" -> Icons.Filled.TableChart
            "pptx", "ppt" -> Icons.Filled.Slideshow
            else -> Icons.Filled.InsertDriveFile
        }
    }
    
    // 获取文档类型名称
    fun getFileTypeName(extension: String): String {
        return when (extension.lowercase()) {
            "docx" -> qstr(R.string.qk_00393)
            "doc" -> qstr(R.string.qk_00394)
            "xlsx" -> qstr(R.string.qk_00395)
            "xls" -> qstr(R.string.qk_00396)
            "pptx" -> qstr(R.string.qk_00397)
            "ppt" -> qstr(R.string.qk_00398)
            else -> qstr(R.string.qk_00399)
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = file?.name ?: stringResource(R.string.qk_00400),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                        if (file != null) {
                            Text(
                                text = "${getFileTypeName(file.extension)} • ${file.absolutePath}",
                                fontSize = 10.sp,
                                color = cs.onSurfaceVariant.copy(alpha = 0.7f),
                                maxLines = 1,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
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
                                    fileStatus.contains("保存中") || fileStatus.contains("打开中") -> cs.primary
                                    else -> cs.onSurfaceVariant.copy(alpha = 0.7f)
                                },
                                maxLines = 1
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        editor?.close()
                        onClose()
                    }) {
                        Icon(Icons.Filled.ArrowBack, stringResource(R.string.qk_00143))
                    }
                },
                actions = {
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
                    
                    // 更多选项
                    IconButton(onClick = { /* 更多选项 */ }) {
                        Icon(Icons.Filled.MoreVert, stringResource(R.string.qk_00404))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = cs.surface,
                    titleContentColor = cs.onSurface
                )
            )
        },
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = cs.surfaceVariant,
                tonalElevation = 2.dp
            ) {
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
                    Text(text = stringResource(R.string.qk_00406),
                        fontSize = 12.sp,
                        color = cs.onSurfaceVariant
                    )
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
            // 文档信息栏
            if (file != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(cs.surfaceVariant.copy(alpha = 0.3f))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        getFileIcon(file.extension),
                        contentDescription = null,
                        tint = cs.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = getFileTypeName(file.extension),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = cs.onSurface
                        )
                        Text(text = stringResource(R.string.qk_00407),
                            fontSize = 10.sp,
                            color = cs.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "${file.length() / 1024} KB",
                        fontSize = 11.sp,
                        color = cs.onSurfaceVariant
                    )
                }
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
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        placeholder = {
                            Text(text = stringResource(R.string.qk_00408),
                                fontSize = 14.sp,
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