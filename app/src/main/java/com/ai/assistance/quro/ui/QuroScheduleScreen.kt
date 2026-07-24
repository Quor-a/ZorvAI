package com.ai.assistance.quro.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.core.tools.QuroScheduledTask
import com.ai.assistance.quro.core.tools.QuroScheduledTaskScheduler
import com.ai.assistance.quro.core.tools.QuroScheduledTaskStore
import com.ai.assistance.quro.core.tools.TaskRepeatType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuroScheduleScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    var tasks by remember { mutableStateOf(listOf<QuroScheduledTask>()) }
    var showEditor by remember { mutableStateOf(false) }
    var editingTask by remember { mutableStateOf<QuroScheduledTask?>(null) }

    fun refresh() {
        tasks = QuroScheduledTaskStore.load(context)
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("定时任务", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        editingTask = null
                        showEditor = true
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = "新增")
                    }
                }
            )
        }
    ) { padding ->
        if (tasks.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Filled.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "还没有定时任务",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "点击右上角 + 添加，或让 AI 帮你创建",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(tasks, key = { it.id }) { task ->
                    TaskCard(
                        task = task,
                        onToggle = { enabled ->
                            val updated = task.copy(enabled = enabled)
                            QuroScheduledTaskStore.addOrUpdate(context, updated)
                            if (enabled) {
                                QuroScheduledTaskScheduler.schedule(context, updated)
                            } else {
                                QuroScheduledTaskScheduler.cancel(context, task.id)
                            }
                            refresh()
                        },
                        onEdit = {
                            editingTask = task
                            showEditor = true
                        },
                        onDelete = {
                            QuroScheduledTaskScheduler.cancel(context, task.id)
                            QuroScheduledTaskStore.remove(context, task.id)
                            refresh()
                        }
                    )
                }
            }
        }
    }

    if (showEditor) {
        TaskEditorDialog(
            task = editingTask,
            onDismiss = { showEditor = false },
            onSave = { saved ->
                QuroScheduledTaskStore.addOrUpdate(context, saved)
                QuroScheduledTaskScheduler.ensureChannel(context)
                QuroScheduledTaskScheduler.schedule(context, saved)
                refresh()
                showEditor = false
            }
        )
    }
}

@Composable
private fun TaskCard(
    task: QuroScheduledTask,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        task.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (!task.enabled) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "已禁用",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "${"%02d".format(task.hour)}:${"%02d".format(task.minute)} · ${task.repeatType.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                if (task.content.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        task.content,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }
                // 下次触发时间
                val nextTime = QuroScheduledTaskScheduler.nextTriggerTime(task)
                if (nextTime != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "下次: ${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(nextTime))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
            Switch(checked = task.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Edit, contentDescription = "编辑", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Delete, contentDescription = "删除", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun TaskEditorDialog(
    task: QuroScheduledTask?,
    onDismiss: () -> Unit,
    onSave: (QuroScheduledTask) -> Unit
) {
    val isNew = task == null
    var title by remember(task?.id) { mutableStateOf(task?.title ?: "") }
    var content by remember(task?.id) { mutableStateOf(task?.content ?: "") }
    var hour by remember(task?.id) { mutableStateOf(task?.hour ?: 8) }
    var minute by remember(task?.id) { mutableStateOf(task?.minute ?: 0) }
    var repeatType by remember(task?.id) { mutableStateOf(task?.repeatType ?: TaskRepeatType.ONCE) }
    var dayOfWeek by remember(task?.id) { mutableStateOf(task?.dayOfWeek ?: 1) }
    var dayOfMonth by remember(task?.id) { mutableStateOf(task?.dayOfMonth ?: 1) }
    var month by remember(task?.id) { mutableStateOf(task?.month ?: 1) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "新建定时任务" else "编辑定时任务") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("内容/提醒详情（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = hour.toString(),
                        onValueChange = { it.toIntOrNull()?.let { v -> if (v in 0..23) hour = v } },
                        label = { Text("时") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = minute.toString(),
                        onValueChange = { it.toIntOrNull()?.let { v -> if (v in 0..59) minute = v } },
                        label = { Text("分") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Text("重复类型", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TaskRepeatType.values().forEach { rt ->
                        FilterChip(
                            selected = repeatType == rt,
                            onClick = { repeatType = rt },
                            label = { Text(rt.label, fontSize = 11.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                // 条件参数
                if (repeatType == TaskRepeatType.WEEKLY || repeatType == TaskRepeatType.BIWEEKLY) {
                    Text("星期", style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val dayLabels = listOf("一", "二", "三", "四", "五", "六", "日")
                        dayLabels.forEachIndexed { idx, label ->
                            FilterChip(
                                selected = dayOfWeek == idx + 1,
                                onClick = { dayOfWeek = idx + 1 },
                                label = { Text(label, fontSize = 11.sp) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                if (repeatType == TaskRepeatType.MONTHLY || repeatType == TaskRepeatType.YEARLY) {
                    OutlinedTextField(
                        value = dayOfMonth.toString(),
                        onValueChange = { it.toIntOrNull()?.let { v -> if (v in 1..31) dayOfMonth = v } },
                        label = { Text("几号 (1-31)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (repeatType == TaskRepeatType.YEARLY) {
                    OutlinedTextField(
                        value = month.toString(),
                        onValueChange = { it.toIntOrNull()?.let { v -> if (v in 1..12) month = v } },
                        label = { Text("几月 (1-12)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (title.isBlank()) return@TextButton
                    val saved = (task ?: QuroScheduledTask(id = UUID.randomUUID().toString(), title = title)).copy(
                        title = title,
                        content = content,
                        hour = hour,
                        minute = minute,
                        repeatType = repeatType,
                        dayOfWeek = dayOfWeek,
                        dayOfMonth = dayOfMonth,
                        month = month,
                        enabled = task?.enabled ?: true,
                        createdAt = task?.createdAt ?: System.currentTimeMillis()
                    )
                    onSave(saved)
                },
                enabled = title.isNotBlank()
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
