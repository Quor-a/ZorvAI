package com.ai.assistance.quro.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.core.aidlaci.QuroAidlAciManager

/**
 * ACI 应用选择器组件。
 *
 * 显示已发现的 ACI 应用列表，允许用户选择一个作为默认 ACI 应用。
 * 类似于技能插件选择器，但专门用于 ACI 应用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AciAppSelector(
    selectedPackage: String?,
    onAppSelected: (String, String) -> Unit, // packageName, appName
    modifier: Modifier = Modifier,
    showOnlyBound: Boolean = true,
) {
    val mgr = remember { QuroAidlAciManager.getInstance() }
    var statuses by remember { mutableStateOf(mgr.getAppStatuses()) }
    var searchQuery by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }

    // 筛选应用
    val filteredApps = remember(statuses, searchQuery, showOnlyBound) {
        statuses.filter { app ->
            // 如果只显示已绑定的应用
            if (showOnlyBound && !app.bound) return@filter false

            // 搜索过滤
            if (searchQuery.isNotBlank()) {
                val query = searchQuery.lowercase()
                app.appName.lowercase().contains(query) ||
                    app.packageName.lowercase().contains(query)
            } else {
                true
            }
        }.sortedBy { it.appName }
    }

    // 找到当前选中的应用名称
    val selectedAppName = remember(selectedPackage, statuses) {
        statuses.find { it.packageName == selectedPackage }?.appName ?: selectedPackage ?: "未选择"
    }

    Column(modifier = modifier) {
        // 下拉选择框
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
        ) {
            OutlinedTextField(
                value = selectedAppName,
                onValueChange = {},
                readOnly = true,
                label = { Text("选择 ACI 应用") },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth(),
                leadingIcon = {
                    Icon(Icons.Filled.Search, "搜索", tint = Color.Gray)
                }
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                // 搜索框
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("搜索应用...") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    singleLine = true,
                )

                // 应用列表
                if (filteredApps.isEmpty()) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                "未找到匹配的 ACI 应用",
                                color = Color.Gray,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        onClick = { expanded = false },
                        enabled = false,
                    )
                } else {
                    filteredApps.forEach { app ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        app.appName,
                                        fontWeight = FontWeight.Medium,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        app.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.Gray
                                    )
                                    Text(
                                        "${app.capabilities.size} 个能力",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (app.bound) Color(0xFF34C759) else Color(0xFFFF3B30)
                                    )
                                }
                            },
                            onClick = {
                                onAppSelected(app.packageName, app.appName)
                                expanded = false
                                searchQuery = ""
                            },
                            leadingIcon = {
                                // 绑定状态指示器
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .padding(2.dp)
                                ) {
                                    Surface(
                                        modifier = Modifier.fillMaxSize(),
                                        color = if (app.bound) Color(0xFF34C759) else Color(0xFFFF3B30),
                                        shape = MaterialTheme.shapes.small,
                                    ) {}
                                }
                            },
                        )
                    }
                }
            }
        }

        // 选中应用的能力预览
        if (selectedPackage != null) {
            val selectedApp = statuses.find { it.packageName == selectedPackage }
            if (selectedApp != null && selectedApp.capabilities.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "已选择: ${selectedApp.appName}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "能力 (${selectedApp.capabilities.size}):",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        // 显示前3个能力
                        selectedApp.capabilities.take(3).forEach { cap ->
                            Text(
                                "• ${cap.id}: ${cap.description}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                        if (selectedApp.capabilities.size > 3) {
                            Text(
                                "...还有 ${selectedApp.capabilities.size - 3} 个能力",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * ACI 应用选择对话框。
 *
 * 弹出对话框让用户选择 ACI 应用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AciAppSelectionDialog(
    onDismiss: () -> Unit,
    onAppSelected: (String, String) -> Unit,
    initialSelectedPackage: String? = null,
) {
    var selectedPackage by remember { mutableStateOf(initialSelectedPackage) }
    var selectedAppName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("选择 ACI 应用")
        },
        text = {
            AciAppSelector(
                selectedPackage = selectedPackage,
                onAppSelected = { pkg, name ->
                    selectedPackage = pkg
                    selectedAppName = name
                },
                modifier = Modifier.fillMaxWidth(),
                showOnlyBound = false, // 显示所有应用，包括未绑定的
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    if (selectedPackage != null) {
                        onAppSelected(selectedPackage!!, selectedAppName)
                        onDismiss()
                    }
                },
                enabled = selectedPackage != null,
            ) {
                Text("选择")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}