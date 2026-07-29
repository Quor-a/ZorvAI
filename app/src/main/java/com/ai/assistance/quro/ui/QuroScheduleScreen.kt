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
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/** RRULE 星期代码 → 中文。 */
private val RRULE_DAYS = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")
private val RRULE_DAY_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

private fun todayRruleDay(): String {
    val c = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
    return when (c) {
        Calendar.MONDAY -> "MO"; Calendar.TUESDAY -> "TU"; Calendar.WEDNESDAY -> "WE"
        Calendar.THURSDAY -> "TH"; Calendar.FRIDAY -> "FR"; Calendar.SATURDAY -> "SA"
        else -> "SU"
    }
}

private fun isValidDateTime(date: String, time: String): Boolean = runCatching {
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).parse("$date $time")
}.isSuccess

/** 由 UI 状态构造 RRULE 字符串。 */
private fun buildRrule(
    freq: String,
    interval: Int,
    selectedDays: Set<String>,
    monthDay: Int,
    yearMonth: Int,
): String {
    val sb = StringBuilder("FREQ=$freq")
    if (interval > 1) sb.append(";INTERVAL=$interval")
    when (freq) {
        "WEEKLY" -> {
            val days = if (selectedDays.isEmpty()) setOf(todayRruleDay()) else selectedDays
            sb.append(";BYDAY=").append(days.joinToString(","))
        }
        "MONTHLY" -> sb.append(";BYMONTHDAY=$monthDay")
        "YEARLY" -> sb.append(";BYMONTH=$yearMonth;BYMONTHDAY=$monthDay")
    }
    return sb.toString()
}

/** 把已有 rrule 解析回 UI 状态（用于编辑旧任务）。 */
private data class RruleUi(
    val freq: String = "DAILY",
    val interval: Int = 1,
    val days: Set<String> = emptySet(),
    val monthDay: Int = 1,
    val yearMonth: Int = 1,
)

private fun parseRrule(rrule: String): RruleUi {
    if (rrule.isBlank()) return RruleUi()
    val p = rrule.split(";").mapNotNull { kv ->
        val i = kv.indexOf("="); if (i < 0) null else kv.substring(0, i).trim().uppercase() to kv.substring(i + 1).trim()
    }.toMap()
    val freq = p["FREQ"] ?: "DAILY"
    val interval = p["INTERVAL"]?.toIntOrNull() ?: 1
    val days = p["BYDAY"]?.split(",")?.map { it.trim().uppercase() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
    val monthDay = p["BYMONTHDAY"]?.toIntOrNull() ?: 1
    val yearMonth = p["BYMONTH"]?.toIntOrNull() ?: 1
    return RruleUi(freq, interval, days, monthDay, yearMonth)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuroScheduleScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    var tasks by remember { mutableStateOf(listOf<QuroScheduledTask>()) }
    var showEditor by remember { mutableStateOf(false) }
    var editingTask by remember { mutableStateOf<QuroScheduledTask?>(null) }

    fun refresh() { tasks = QuroScheduledTaskStore.load(context) }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("定时任务 / 自动化", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { editingTask = null; showEditor = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "新增")
                    }
                }
            )
        }
    ) { padding ->
        if (tasks.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Filled.Schedule, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                Spacer(Modifier.height(16.dp))
                Text("还没有定时任务", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                Spacer(Modifier.height(8.dp))
                Text("点击右上角 + 添加，或让 AI 帮你创建", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(tasks, key = { it.id }) { task ->
                    TaskCard(
                        task = task,
                        onToggle = { enabled ->
                            val updated = task.copy(enabled = enabled)
                            QuroScheduledTaskStore.addOrUpdate(context, updated)
                            if (enabled) QuroScheduledTaskScheduler.schedule(context, updated)
                            else QuroScheduledTaskScheduler.cancel(context, task.id)
                            refresh()
                        },
                        onEdit = { editingTask = task; showEditor = true },
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
    val scheduleSummary = if (task.scheduleType == "once") "一次性 @ ${task.scheduledAt}"
    else QuroScheduledTaskScheduler.humanRrule(task.rrule).takeIf { it.isNotBlank() } ?: task.rrule
    val nextTime = QuroScheduledTaskScheduler.nextTriggerTime(task)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(task.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (!task.enabled) {
                        Spacer(Modifier.width(8.dp))
                        Text("已禁用", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(scheduleSummary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                if (task.content.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(task.content, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
                if (nextTime != null) {
                    Spacer(Modifier.height(2.dp))
                    Text("下次: ${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(nextTime))}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                }
            }
            Switch(checked = task.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Edit, null, Modifier.size(18.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Delete, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
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
    val context = LocalContext.current
    val isNew = task == null
    var title by remember(task?.id) { mutableStateOf(task?.title ?: "") }
    var prompt by remember(task?.id) { mutableStateOf(task?.content ?: "") }
    var targetMode by remember(task?.id) { mutableStateOf(if (task?.autoNew == true) "auto" else if (task?.cwds.isNullOrBlank()) "default" else "specific") }
    var selectedConvId by remember(task?.id) { mutableStateOf(task?.cwds ?: "") }
    var scheduleType by remember(task?.id) { mutableStateOf(task?.scheduleType ?: "recurring") }
    var endDate by remember(task?.id) { mutableStateOf(task?.endAt ?: "") }

    // once 时间
    val initOnce = if (task?.scheduleType == "once" && task.scheduledAt.isNotBlank())
        task.scheduledAt.split(" ") else listOf("", "")
    var onceDate by remember(task?.id) { mutableStateOf(initOnce.getOrNull(0) ?: "") }
    var onceTime by remember(task?.id) { mutableStateOf(initOnce.getOrNull(1) ?: "") }

    // recurring 状态（由 rrule 反解）
    val initR = parseRrule(task?.rrule ?: "")
    var freq by remember(task?.id) { mutableStateOf(initR.freq) }
    var interval by remember(task?.id) { mutableStateOf(initR.interval) }
    var selectedDays by remember(task?.id) { mutableStateOf(initR.days) }
    var monthDay by remember(task?.id) { mutableStateOf(initR.monthDay) }
    var yearMonth by remember(task?.id) { mutableStateOf(initR.yearMonth) }

    var dateError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "新建定时任务" else "编辑定时任务") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("标题") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = prompt, onValueChange = { prompt = it }, label = { Text("指令 / 提醒内容（可选）") }, modifier = Modifier.fillMaxWidth(), minLines = 2)

                Text("类型", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = scheduleType == "once", onClick = { scheduleType = "once" }, label = { Text("一次性") }, modifier = Modifier.weight(1f))
                    FilterChip(selected = scheduleType == "recurring", onClick = { scheduleType = "recurring" }, label = { Text("重复") }, modifier = Modifier.weight(1f))
                }

                if (scheduleType == "once") {
                    Text("触发时间", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                val c = Calendar.getInstance()
                                if (onceDate.isNotBlank()) QuroScheduledTaskScheduler.parseLocal("$onceDate 00:00")?.let { c.timeInMillis = it }
                                DatePickerDialog(
                                    context,
                                    { _, y, m, d -> onceDate = String.format("%04d-%02d-%02d", y, m + 1, d); dateError = false },
                                    c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
                                ).show()
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text(if (onceDate.isBlank()) "选择日期" else onceDate) }
                        Button(
                            onClick = {
                                val c = Calendar.getInstance()
                                if (onceTime.isNotBlank()) {
                                    val parts = onceTime.split(":")
                                    if (parts.size == 2) {
                                        c.set(Calendar.HOUR_OF_DAY, parts[0].toIntOrNull() ?: 0)
                                        c.set(Calendar.MINUTE, parts[1].toIntOrNull() ?: 0)
                                    }
                                }
                                TimePickerDialog(
                                    context,
                                    { _, h, m -> onceTime = String.format("%02d:%02d", h, m); dateError = false },
                                    c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true
                                ).show()
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text(if (onceTime.isBlank()) "选择时间" else onceTime) }
                    }
                    if (dateError) Text("请先选择日期和时间", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                } else {
                    Text("频率", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("DAILY" to "每天", "WEEKLY" to "每周", "MONTHLY" to "每月", "YEARLY" to "每年").forEach { (f, label) ->
                            FilterChip(selected = freq == f, onClick = { freq = f }, label = { Text(label, fontSize = 11.sp) }, modifier = Modifier.weight(1f))
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = interval.toString(),
                            onValueChange = { it.toIntOrNull()?.let { v -> if (v in 1..365) interval = v } },
                            label = { Text("间隔") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true, modifier = Modifier.width(80.dp)
                        )
                        Text("（每 ${interval} 个周期）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (freq == "WEEKLY") {
                        Text("星期（可多选）", style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            RRULE_DAYS.forEachIndexed { idx, code ->
                                FilterChip(
                                    selected = selectedDays.contains(code),
                                    onClick = {
                                        selectedDays = if (selectedDays.contains(code)) selectedDays - code else selectedDays + code
                                    },
                                    label = { Text(RRULE_DAY_LABELS[idx], fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                    if (freq == "MONTHLY" || freq == "YEARLY") {
                        OutlinedTextField(
                            value = monthDay.toString(),
                            onValueChange = { it.toIntOrNull()?.let { v -> if (v in 1..31) monthDay = v } },
                            label = { Text("几号 (1-31)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (freq == "YEARLY") {
                        OutlinedTextField(
                            value = yearMonth.toString(),
                            onValueChange = { it.toIntOrNull()?.let { v -> if (v in 1..12) yearMonth = v } },
                            label = { Text("几月 (1-12)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                    }
                    // 结束日期（可选）：到达后停止重复
                    Text("结束日期（可选，留空=永久重复）", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = {
                                val c = Calendar.getInstance()
                                if (endDate.isNotBlank()) QuroScheduledTaskScheduler.parseLocal("$endDate 23:59")?.let { c.timeInMillis = it }
                                DatePickerDialog(
                                    context,
                                    { _, y, m, d -> endDate = String.format("%04d-%02d-%02d", y, m + 1, d) },
                                    c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
                                ).show()
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text(if (endDate.isBlank()) "选择结束日期" else endDate) }
                        if (endDate.isNotBlank()) {
                            TextButton(onClick = { endDate = "" }) { Text("清除") }
                        }
                    }
                }

                Text("目标会话", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = targetMode == "specific", onClick = { targetMode = "specific" }, label = { Text("指定会话") }, modifier = Modifier.weight(1f))
                    FilterChip(selected = targetMode == "auto", onClick = { targetMode = "auto" }, label = { Text("自动新建会话") }, modifier = Modifier.weight(1f))
                }
                if (targetMode == "specific") {
                    val convs = QuroChatViewModel.instance.conversations.value.sortedByDescending { it.updatedAt }
                    if (convs.isEmpty()) {
                        Text("暂无历史会话，可改用「自动新建会话」", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Card(Modifier.fillMaxWidth().heightIn(max = 220.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))) {
                            LazyColumn(Modifier.fillMaxWidth().padding(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                items(convs, key = { it.id }) { c ->
                                    val sel = selectedConvId == c.id
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .clickable { selectedConvId = c.id }
                                            .padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(selected = sel, onClick = { selectedConvId = c.id })
                                        Spacer(Modifier.width(8.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(c.title.ifBlank { "未命名会话" }, style = MaterialTheme.typography.bodyMedium)
                                            if (c.preview.isNotBlank())
                                                Text(c.preview, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else if (targetMode == "auto") {
                    Text("每次触发都会新建一个独立会话，不污染当前 / 历史对话。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("不指定则沿用当前 / 最近的会话。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (title.isBlank()) return@TextButton
                    val (finalScheduledAt, finalRrule) = if (scheduleType == "once") {
                        if (!isValidDateTime(onceDate, onceTime)) { dateError = true; return@TextButton }
                        Pair("$onceDate $onceTime", "")
                    } else {
                        Pair("", buildRrule(freq, interval, selectedDays, monthDay, yearMonth))
                    }
                    val finalEndAt = if (scheduleType == "recurring") endDate.trim() else ""
                    val saved = (task ?: QuroScheduledTask(id = UUID.randomUUID().toString(), title = title)).copy(
                        title = title,
                        content = prompt,
                        scheduleType = scheduleType,
                        scheduledAt = finalScheduledAt,
                        rrule = finalRrule,
                        endAt = finalEndAt,
                        cwds = if (targetMode == "specific") selectedConvId.trim() else "",
                        autoNew = targetMode == "auto",
                        enabled = task?.enabled ?: true,
                        createdAt = task?.createdAt ?: System.currentTimeMillis(),
                    )
                    onSave(saved)
                },
                enabled = title.isNotBlank()
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
