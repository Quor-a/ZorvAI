// 本页用了 ExposedDropdownMenuBox / menuAnchor 等在 Material3 中仍标记实验性的 API，
// 需显式 OptIn。用 @file 级注解覆盖整页，避免在多个函数上重复标注。
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ai.assistance.quro.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.core.model.ApiProviderType
import com.ai.assistance.quro.core.model.QuroMultiProviderRepository
import com.ai.assistance.quro.core.model.QuroProviderConfig
import com.ai.assistance.quro.core.model.FailoverConfig
import com.ai.assistance.quro.core.model.FailoverStrategy
import kotlinx.coroutines.launch

/**
 * 多提供商管理界面
 * 
 * 提供提供商配置管理、优先级排序、健康检查和故障转移设置。
 * 参考 Agora 和 Kai 的多提供商设计。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuroMultiProviderScreen(
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = remember { QuroMultiProviderRepository(context) }
    val scope = rememberCoroutineScope()
    
    var providers by remember { mutableStateOf(repository.getAllConfigs()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf<QuroProviderConfig?>(null) }
    var showFailoverSettings by remember { mutableStateOf(false) }
    var showHealthCheck by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        "多提供商管理",
                        fontWeight = FontWeight.Bold
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showFailoverSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "故障转移设置")
                    }
                    IconButton(onClick = { showHealthCheck = true }) {
                        Icon(Icons.Default.Refresh, contentDescription = "健康检查")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, contentDescription = "添加提供商")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 统计信息卡片
            ProviderStatsCard(providers)
            
            // 提供商列表
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(providers, key = { it.id }) { provider ->
                    ProviderCard(
                        provider = provider,
                        onEdit = { showEditDialog = provider },
                        onToggleEnabled = { enabled ->
                            val updated = provider.copy(enabled = enabled)
                            repository.updateConfig(updated)
                            providers = repository.getAllConfigs()
                        },
                        onDelete = {
                            repository.removeConfig(provider.id)
                            providers = repository.getAllConfigs()
                        },
                        onMoveUp = {
                            val newPriority = (provider.priority - 1).coerceAtLeast(0)
                            val updated = provider.copy(priority = newPriority)
                            repository.updateConfig(updated)
                            providers = repository.getAllConfigs()
                        },
                        onMoveDown = {
                            val newPriority = provider.priority + 1
                            val updated = provider.copy(priority = newPriority)
                            repository.updateConfig(updated)
                            providers = repository.getAllConfigs()
                        }
                    )
                }
            }
        }
    }
    
    // 添加提供商对话框
    if (showAddDialog) {
        AddProviderDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { config ->
                repository.addConfig(config)
                providers = repository.getAllConfigs()
                showAddDialog = false
            }
        )
    }
    
    // 编辑提供商对话框
    showEditDialog?.let { provider ->
        EditProviderDialog(
            provider = provider,
            onDismiss = { showEditDialog = null },
            onUpdate = { updated ->
                repository.updateConfig(updated)
                providers = repository.getAllConfigs()
                showEditDialog = null
            }
        )
    }
    
    // 故障转移设置对话框
    if (showFailoverSettings) {
        FailoverSettingsDialog(
            onDismiss = { showFailoverSettings = false },
            onSave = { config ->
                repository.saveFailoverConfig(config)
                showFailoverSettings = false
            }
        )
    }
    
    // 健康检查对话框
    if (showHealthCheck) {
        HealthCheckDialog(
            onDismiss = { showHealthCheck = false }
        )
    }
}

@Composable
fun ProviderStatsCard(providers: List<QuroProviderConfig>) {
    val enabledCount = providers.count { it.enabled }
    val healthyCount = providers.count { 
        it.enabled && it.healthStatus == QuroProviderConfig.HealthStatus.HEALTHY 
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "提供商概览",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem("总提供商", providers.size.toString())
                StatItem("已启用", enabledCount.toString())
                StatItem("健康", healthyCount.toString())
                StatItem("故障", (enabledCount - healthyCount).toString())
            }
        }
    }
}

@Composable
fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
    }
}

@Composable
fun ProviderCard(
    provider: QuroProviderConfig,
    onEdit: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    val healthColor = when (provider.healthStatus) {
        QuroProviderConfig.HealthStatus.HEALTHY -> Color(0xFF4CAF50)
        QuroProviderConfig.HealthStatus.DEGRADED -> Color(0xFFFFC107)
        QuroProviderConfig.HealthStatus.UNHEALTHY -> Color(0xFFFF9800)
        QuroProviderConfig.HealthStatus.FAILED -> Color(0xFFF44336)
        QuroProviderConfig.HealthStatus.UNKNOWN -> Color(0xFF9E9E9E)
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = if (provider.enabled) 
                MaterialTheme.colorScheme.surface 
            else 
                MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // 头部：名称、状态、优先级
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = provider.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (provider.enabled) 
                            MaterialTheme.colorScheme.onSurface 
                        else 
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    
                    Text(
                        text = provider.providerType.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
                
                // 健康状态指示器
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(healthColor)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = provider.healthStatus.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = healthColor
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // 详细信息
            Column {
                InfoRow("URL", provider.baseUrl)
                InfoRow("模型", provider.defaultModel)
                InfoRow("优先级", provider.priority.toString())
                if (provider.isLocal) {
                    InfoRow("类型", "本地模型")
                }
                if (provider.lastError.isNotBlank()) {
                    InfoRow("错误", provider.lastError, isError = true)
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // 操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 启用/禁用开关
                Switch(
                    checked = provider.enabled,
                    onCheckedChange = onToggleEnabled,
                    modifier = Modifier.padding(end = 8.dp)
                )
                
                // 上移按钮
                IconButton(
                    onClick = onMoveUp,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.ArrowUpward,
                        contentDescription = "上移",
                        modifier = Modifier.size(16.dp)
                    )
                }
                
                // 下移按钮
                IconButton(
                    onClick = onMoveDown,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.ArrowDownward,
                        contentDescription = "下移",
                        modifier = Modifier.size(16.dp)
                    )
                }
                
                // 编辑按钮
                IconButton(
                    onClick = onEdit,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "编辑",
                        modifier = Modifier.size(16.dp)
                    )
                }
                
                // 删除按钮
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(32.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除",
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun InfoRow(label: String, value: String, isError: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) 
                MaterialTheme.colorScheme.error 
            else 
                MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddProviderDialog(
    onDismiss: () -> Unit,
    onAdd: (QuroProviderConfig) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var providerType by remember { mutableStateOf(ApiProviderType.OPENAI) }
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var defaultModel by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf(0) }
    var isLocal by remember { mutableStateOf(false) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加提供商") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("提供商名称") },
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // 提供商类型选择
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it }
                ) {
                    OutlinedTextField(
                        value = providerType.name,
                        onValueChange = {},
                        label = { Text("提供商类型") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }
                    )
                    
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        ApiProviderType.values().forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type.name) },
                                onClick = {
                                    providerType = type
                                    expanded = false
                                }
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("API基础URL") },
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API密钥") },
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                OutlinedTextField(
                    value = defaultModel,
                    onValueChange = { defaultModel = it },
                    label = { Text("默认模型") },
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                OutlinedTextField(
                    value = priority.toString(),
                    onValueChange = { priority = it.toIntOrNull() ?: 0 },
                    label = { Text("优先级") },
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isLocal,
                        onCheckedChange = { isLocal = it }
                    )
                    Text("本地模型")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val config = QuroProviderConfig(
                        name = name,
                        providerType = providerType,
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        defaultModel = defaultModel,
                        priority = priority,
                        isLocal = isLocal
                    )
                    onAdd(config)
                },
                enabled = name.isNotBlank() && baseUrl.isNotBlank()
            ) {
                Text("添加")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProviderDialog(
    provider: QuroProviderConfig,
    onDismiss: () -> Unit,
    onUpdate: (QuroProviderConfig) -> Unit
) {
    var name by remember { mutableStateOf(provider.name) }
    var baseUrl by remember { mutableStateOf(provider.baseUrl) }
    var apiKey by remember { mutableStateOf(provider.apiKey) }
    var defaultModel by remember { mutableStateOf(provider.defaultModel) }
    var priority by remember { mutableStateOf(provider.priority.toString()) }
    var enabled by remember { mutableStateOf(provider.enabled) }
    var isLocal by remember { mutableStateOf(provider.isLocal) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑提供商") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("提供商名称") },
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("API基础URL") },
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API密钥") },
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                OutlinedTextField(
                    value = defaultModel,
                    onValueChange = { defaultModel = it },
                    label = { Text("默认模型") },
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                OutlinedTextField(
                    value = priority,
                    onValueChange = { priority = it },
                    label = { Text("优先级") },
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = enabled,
                        onCheckedChange = { enabled = it }
                    )
                    Text("启用")
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isLocal,
                        onCheckedChange = { isLocal = it }
                    )
                    Text("本地模型")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val updated = provider.copy(
                        name = name,
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        defaultModel = defaultModel,
                        priority = priority.toIntOrNull() ?: provider.priority,
                        enabled = enabled,
                        isLocal = isLocal
                    )
                    onUpdate(updated)
                },
                enabled = name.isNotBlank() && baseUrl.isNotBlank()
            ) {
                Text("更新")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
fun FailoverSettingsDialog(
    onDismiss: () -> Unit,
    onSave: (FailoverConfig) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = remember { QuroMultiProviderRepository(context) }
    var config by remember { mutableStateOf(repository.getFailoverConfig()) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("故障转移设置") },
        text = {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = config.enabled,
                        onCheckedChange = { config = config.copy(enabled = it) }
                    )
                    Text("启用故障转移")
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // 策略选择
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it }
                ) {
                    OutlinedTextField(
                        value = config.strategy.name,
                        onValueChange = {},
                        label = { Text("故障转移策略") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }
                    )
                    
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        FailoverStrategy.values().forEach { strategy ->
                            DropdownMenuItem(
                                text = { Text(strategy.name) },
                                onClick = {
                                    config = config.copy(strategy = strategy)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                OutlinedTextField(
                    value = config.maxRetries.toString(),
                    onValueChange = { config = config.copy(maxRetries = it.toIntOrNull() ?: 3) },
                    label = { Text("最大重试次数") },
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                OutlinedTextField(
                    value = config.failureThreshold.toString(),
                    onValueChange = { config = config.copy(failureThreshold = it.toIntOrNull() ?: 3) },
                    label = { Text("失败阈值（断路器）") },
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                OutlinedTextField(
                    value = (config.healthCheckIntervalMs / 1000 / 60).toString(),
                    onValueChange = { 
                        val minutes = it.toLongOrNull() ?: 5
                        config = config.copy(healthCheckIntervalMs = minutes * 1000 * 60)
                    },
                    label = { Text("健康检查间隔（分钟）") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(config) }) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
fun HealthCheckDialog(
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = remember { QuroMultiProviderRepository(context) }
    val scope = rememberCoroutineScope()
    var isChecking by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("健康检查") },
        text = {
            Column {
                if (isChecking) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "正在检查提供商健康状态...",
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                } else {
                    if (results.isEmpty()) {
                        Text("点击下方按钮开始健康检查")
                    } else {
                        Text("检查结果:")
                        Spacer(modifier = Modifier.height(8.dp))
                        results.forEach { (name, status) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Text(
                                    text = name,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = status,
                                    color = if (status == "健康") 
                                        Color(0xFF4CAF50) 
                                    else 
                                        Color(0xFFF44336)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    isChecking = true
                    scope.launch {
                        // 模拟健康检查
                        val configs = repository.getEnabledConfigs()
                        val checkResults = mutableListOf<Pair<String, String>>()
                        
                        for (config in configs) {
                            // 简单检查配置是否完整
                            val isHealthy = config.baseUrl.isNotBlank() && 
                                           (config.apiKey.isNotBlank() || !config.requiresApiKey) &&
                                           config.defaultModel.isNotBlank()
                            
                            checkResults.add(config.name to if (isHealthy) "健康" else "不健康")
                            
                            // 更新状态
                            repository.updateHealthStatus(
                                config.id,
                                if (isHealthy) 
                                    QuroProviderConfig.HealthStatus.HEALTHY 
                                else 
                                    QuroProviderConfig.HealthStatus.FAILED
                            )
                        }
                        
                        results = checkResults
                        isChecking = false
                    }
                },
                enabled = !isChecking
            ) {
                Text("开始检查")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}
