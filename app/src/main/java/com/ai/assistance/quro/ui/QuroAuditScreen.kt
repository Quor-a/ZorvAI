package com.ai.assistance.quro.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ai.assistance.quro.core.privilege.*
import com.ai.assistance.quro.ui.theme.QuroSettingsTheme

/**
 * CapOS 审计页（上帝视角）：权限状态概览 + 最近审计日志（等宽字体）。
 * 让用户信任 CapOS：所有权限使用都可追溯。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuroAuditScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val mgr = remember { QuroPrivilegeManager(ctx) }
    var states by remember { mutableStateOf(mgr.probe()) }
    var logs by remember { mutableStateOf(QuroPrivilegeAudit.load(ctx)) }
    var showClear by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        QuroSettingsTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize()) {
                    TopAppBar(
                        title = { Text("CapOS 审计") },
                        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.ArrowBack, null) } },
                        actions = {
                            IconButton(onClick = { showClear = true }) { Icon(Icons.Filled.DeleteSweep, null) }
                        },
                    )
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("权限状态概览", style = MaterialTheme.typography.titleMedium)
                        states.values.forEach { s ->
                            Card(
                                Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (s.available) Color(0xFF34C759).copy(alpha = 0.1f) else Color(0xFFFF3B30).copy(alpha = 0.1f),
                                ),
                            ) {
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = when (s.level) {
                                            PrivilegeLevel.L1 -> Icons.Filled.TouchApp
                                            PrivilegeLevel.L2 -> Icons.Filled.Hub
                                            PrivilegeLevel.L3 -> Icons.Filled.Security
                                            PrivilegeLevel.L4 -> Icons.Filled.Warning
                                        },
                                        contentDescription = null,
                                        tint = if (s.available) Color(0xFF34C759) else Color(0xFFFF3B30),
                                    )
                                    Spacer(Modifier.width(16.dp))
                                    Column {
                                        Text("Level ${s.level.name}", fontWeight = FontWeight.Bold)
                                        Text(
                                            s.details.ifBlank { if (s.available) "可用" else "未授权" },
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        Text("最近审计日志", style = MaterialTheme.typography.titleMedium)
                        if (logs.isEmpty()) {
                            Text("暂无审计记录。任何权限提升都会被记录在这里。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            logs.reversed().forEach { log ->
                                Text(
                                    "[${log.timestamp}] ${log.capsuleId} · ${log.level}: ${log.action} → ${if (log.result) "OK" else "DENY"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showClear) {
        AlertDialog(
            onDismissRequest = { showClear = false },
            title = { Text("清空审计日志") },
            text = { Text("确定要删除全部 ${logs.size} 条审计记录吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    QuroPrivilegeAudit.clear(ctx)
                    logs = emptyList()
                    showClear = false
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { showClear = false }) { Text("取消") } },
        )
    }
}
