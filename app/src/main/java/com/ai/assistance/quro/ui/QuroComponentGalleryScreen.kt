package com.ai.assistance.quro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
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

/**
 * Quro 可视化组件画廊（v244 真组件版，所有 Demo 均可交互）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuroComponentGalleryScreen(
    onBack: () -> Unit,
    onComponentSelected: ((String) -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    var switchOn by remember { mutableStateOf(true) }
    var checkboxOn by remember { mutableStateOf(false) }
    var radioSelected by remember { mutableStateOf(0) }
    var sliderVal by remember { mutableStateOf(0.4f) }
    var textValue by remember { mutableStateOf("") }
    var showDialog by remember { mutableStateOf(false) }
    var showSnackbar by remember { mutableStateOf(false) }
    var showSheet by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(showSnackbar) {
        if (showSnackbar) {
            snackbarHostState.showSnackbar("这是一条 Snackbar 提示")
            showSnackbar = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("可视化组件库") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null) } },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 卡片组件
            item { SectionTitle("卡片组件", cs) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GalleryCard(cs, modifier = Modifier.weight(1f)) {
                        Column {
                            Text("状态卡", style = MaterialTheme.typography.labelMedium, color = cs.primary)
                            Spacer(Modifier.height(4.dp))
                            Text("运行正常", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("CPU 23%", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                    }
                    GalleryCard(cs, modifier = Modifier.weight(1f)) {
                        Column {
                            Text("指标卡", style = MaterialTheme.typography.labelMedium, color = cs.tertiary)
                            Spacer(Modifier.height(4.dp))
                            Text("1,284", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("今日调用", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                    }
                }
            }
            item {
                GalleryCard(cs) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(Modifier.size(48.dp).clip(CircleShape), color = cs.primaryContainer) {
                            Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Person, null, tint = cs.onPrimaryContainer) }
                        }
                        Column {
                            Text("人物卡", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text("Quro 助手 · 在线", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = { onComponentSelected?.invoke("操作卡") }) { Text("操作") }
                    }
                }
            }

            // 按钮组件
            item { SectionTitle("按钮组件", cs) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = {}) { Text("主按钮") }
                    OutlinedButton(onClick = {}) { Text("描边") }
                    TextButton(onClick = {}) { Text("文字") }
                    FilledTonalButton(onClick = {}) { Text("色调") }
                    IconButton(onClick = {}) { Icon(Icons.Filled.Favorite, null, tint = cs.primary) }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = true, onClick = {}, label = { Text("已选 Chip") })
                    FilterChip(selected = false, onClick = {}, label = { Text("未选 Chip") })
                    AssistChip(onClick = {}, label = { Text("辅助 Chip") }, leadingIcon = { Icon(Icons.Filled.Add, null, Modifier.size(16.dp)) })
                    SmallFloatingActionButton(onClick = {}) { Icon(Icons.Filled.Add, null) }
                }
            }

            // 输入框组件
            item { SectionTitle("输入框组件", cs) }
            item {
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { textValue = it },
                    label = { Text("文本框") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            item {
                OutlinedTextField(
                    value = "",
                    onValueChange = {},
                    label = { Text("搜索栏") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                )
            }

            // 展示组件
            item { SectionTitle("展示组件", cs) }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Badge { Text("9") }
                    Surface(Modifier.size(40.dp).clip(CircleShape), color = cs.secondaryContainer) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.AccountCircle, null, tint = cs.onSecondaryContainer, modifier = Modifier.size(28.dp)) }
                    }
                    Column(Modifier.weight(1f)) {
                        Text("线性进度", style = MaterialTheme.typography.labelSmall)
                        LinearProgressIndicator({ 0.6f }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    CircularProgressIndicator({ 0.3f })
                    Column {
                        Text("空状态", style = MaterialTheme.typography.labelMedium)
                        Text("暂无数据", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
            }

            // 交互组件
            item { SectionTitle("交互组件", cs) }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = switchOn, onCheckedChange = { switchOn = it })
                        Spacer(Modifier.width(6.dp)); Text(if (switchOn) "开" else "关")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = checkboxOn, onCheckedChange = { checkboxOn = it })
                        Spacer(Modifier.width(6.dp)); Text(if (checkboxOn) "已勾" else "未勾")
                    }
                }
            }
            item {
                Column {
                    Text("滑块: ${(sliderVal * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                    Slider(value = sliderVal, onValueChange = { sliderVal = it })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = radioSelected == 0, onClick = { radioSelected = 0 })
                        Text("选项 A"); Spacer(Modifier.width(12.dp))
                        RadioButton(selected = radioSelected == 1, onClick = { radioSelected = 1 })
                        Text("选项 B")
                    }
                }
            }

            // 覆盖层组件
            item { SectionTitle("覆盖层组件", cs) }
            item {
                Surface(
                    Modifier.fillMaxWidth(),
                    color = cs.primaryContainer,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Info, null, tint = cs.onPrimaryContainer)
                        Text("提示条：这是一条信息提示", color = cs.onPrimaryContainer, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showDialog = true }) { Text("打开对话框") }
                    Button(onClick = { showSnackbar = true }) { Text("触发 Snackbar") }
                    Button(onClick = { showSheet = true }) { Text("底部弹层") }
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = { TextButton(onClick = { showDialog = false }) { Text("确定") } },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text("取消") } },
            title = { Text("对话框") },
            text = { Text("这是一个真实可交互的 Material3 对话框。") },
        )
    }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("底部弹层", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("可在此放置操作列表、表单等内容。", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { showSheet = false }, modifier = Modifier.fillMaxWidth()) { Text("关闭") }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, cs: ColorScheme) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = cs.onSurface)
}

@Composable
private fun GalleryCard(cs: ColorScheme, modifier: Modifier = Modifier.fillMaxWidth(), content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = cs.surfaceContainerLow,
        border = BorderStroke(1.dp, cs.outlineVariant),
        modifier = modifier,
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}
