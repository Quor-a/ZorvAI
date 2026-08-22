package com.ai.assistance.quro.ui

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * 工作区选择器：选择已创建的工作区、创建工作区或自定义选择文件夹。
 * 按钮添加到权限模式栏。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceSelectionDialog(
    onDismiss: () -> Unit,
    onWorkspaceSelected: (String) -> Unit,
    onClearWorkspace: () -> Unit,
    initialSelectedPath: String? = null,
) {
    val ctx = LocalContext.current
    var selectedPath by remember { mutableStateOf(initialSelectedPath) }
    var showCreateDialog by remember { mutableStateOf(false) }

    // 自定义文件夹选择器
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val path = uriToPath(ctx, uri)
            if (path != null) {
                selectedPath = path
                onWorkspaceSelected(path)
                onDismiss()
            } else {
                Toast.makeText(ctx, "无法获取文件夹路径", Toast.LENGTH_SHORT).show()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择工作区") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                // 当前选中
                if (selectedPath != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.FolderOpen, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text("当前工作区", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                Text(
                                    selectedPath!!.substringAfterLast('/'),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Gray,
                                )
                            }
                            TextButton(onClick = {
                                selectedPath = null
                                onClearWorkspace()
                            }) {
                                Text("取消选择")
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                // 默认工作区
                WorkspaceOption(
                    icon = Icons.Filled.Home,
                    title = "默认工作区",
                    subtitle = "QuroWorkspace（应用沙箱）",
                    onClick = {
                        val defaultPath = File(ctx.getExternalFilesDir(null), "QuroWorkspace").absolutePath
                        selectedPath = defaultPath
                        onWorkspaceSelected(defaultPath)
                        onDismiss()
                    },
                )

                // 已创建的工作区
                val workspaceRoot = File(ctx.getExternalFilesDir(null), "QuroWorkspace")
                val workspaces = remember {
                    if (workspaceRoot.exists()) {
                        workspaceRoot.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
                    } else emptyList()
                }

                workspaces.forEach { name ->
                    WorkspaceOption(
                        icon = Icons.Filled.Folder,
                        title = name,
                        subtitle = "QuroWorkspace/$name",
                        onClick = {
                            val path = File(workspaceRoot, name).absolutePath
                            selectedPath = path
                            onWorkspaceSelected(path)
                            onDismiss()
                        },
                    )
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                // 创建新工作区
                WorkspaceOption(
                    icon = Icons.Filled.Add,
                    title = "创建工作区",
                    subtitle = "在默认工作区内新建文件夹",
                    onClick = { showCreateDialog = true },
                )

                // 自定义选择文件夹
                WorkspaceOption(
                    icon = Icons.Filled.CreateNewFolder,
                    title = "自定义选择文件夹",
                    subtitle = "选择手机上任意文件夹作为工作区",
                    onClick = { folderPicker.launch(null) },
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )

    // 创建工作区对话框
    if (showCreateDialog) {
        CreateWorkspaceDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name ->
                val workspaceRoot = File(ctx.getExternalFilesDir(null), "QuroWorkspace")
                val dir = File(workspaceRoot, name)
                dir.mkdirs()
                selectedPath = dir.absolutePath
                onWorkspaceSelected(dir.absolutePath)
                showCreateDialog = false
                onDismiss()
            },
        )
    }
}

@Composable
private fun WorkspaceOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            Icon(Icons.Filled.ChevronRight, null, tint = Color.Gray, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun CreateWorkspaceDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("创建工作区") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = "" },
                    label = { Text("工作区名称") },
                    placeholder = { Text("例如：MyProject") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = error.isNotEmpty(),
                )
                if (error.isNotEmpty()) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (name.isBlank()) { error = "请输入名称"; return@Button }
                if (!name.matches(Regex("^[a-zA-Z0-9_\\-\\u4e00-\\u9fa5]+$"))) { error = "名称只能包含字母、数字、下划线、连字符或中文"; return@Button }
                onCreate(name)
            }) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun uriToPath(context: Context, uri: Uri): String? {
    if (uri.scheme == "content") {
        val cursor = context.contentResolver.query(uri, arrayOf("_data"), null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex("_data")
                if (idx >= 0) return it.getString(idx)
            }
        }
    }
    if (uri.scheme == "file") return uri.path
    return null
}