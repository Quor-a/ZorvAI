package com.ai.assistance.quro.ui
import androidx.compose.ui.res.stringResource
import com.ai.assistance.quro.R
import com.ai.assistance.quro.util.qstr

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
private val RRULE_DAY_LABELS = listOf(qstr(R.string.qk_02370), qstr(R.string.qk_02371), qstr(R.string.qk_02372), qstr(R.string.qk_02373), qstr(R.string.qk_02374), qstr(R.string.qk_02375), qstr(R.string.qk_02376))

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
                title = { Text(stringResource(R.string.qk_02377), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.qk_00143))
                    }
                },
                actions = {
                    IconButton(onClick = { editingTask = null; showEditor = true }) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.qk_02378))
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
                Text(stringResource(R.string.qk_02379), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.qk_02380), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
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
    val scheduleSummary = if (task.scheduleType == "once") stringResource(R.string.qk_02381, (task.scheduledAt).toString())
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
                        Text(stringResource(R.string.qk_00777), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
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
        title = { Text(if (isNew) stringResource(R.string.qk_02383) else stringResource(R.string.qk_02384)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text(stringResource(R.string.qk_01066)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = prompt, onValueChange = { prompt = it }, label = { Text(stringResource(R.string.qk_02385)) }, modifier = Modifier.fillMaxWidth(), minLines = 2)

                Text(stringResource(R.string.qk_02240), style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = scheduleType == "once", onClick = { scheduleType = "once" }, label = { Text(stringResource(R.string.qk_02386)) }, modifier = Modifier.weight(1f))
                    FilterChip(selected = scheduleType == "recurring", onClick = { scheduleType = "recurring" }, label = { Text(stringResource(R.string.qk_02387)) }, modifier = Modifier.weight(1f))
                }

                if (scheduleType == "once") {
                    Text(stringResource(R.string.qk_02388), style = MaterialTheme.typography.labelMedium)
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
                        ) { Text(if (onceDate.isBlank()) stringResource(R.string.qk_02389) else onceDate) }
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
                        ) { Text(if (onceTime.isBlank()) stringResource(R.string.qk_02390) else onceTime) }
                    }
                    if (dateError) Text(stringResource(R.string.qk_02391), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                } else {
                    Text(stringResource(R.string.qk_02392), style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("DAILY" to stringResource(R.string.qk_02393), "WEEKLY" to stringResource(R.string.qk_02394), "MONTHLY" to stringResource(R.string.qk_02395), "YEARLY" to stringResource(R.string.qk_02396)).forEach { (f, label) ->
                            FilterChip(selected = freq == f, onClick = { freq = f }, label = { Text(label, fontSize = 11.sp) }, modifier = Modifier.weight(1f))
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = interval.toString(),
                            onValueChange = { it.toIntOrNull()?.let { v -> if (v in 1..365) interval = v } },
                            label = { Text(stringResource(R.string.qk_02397)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true, modifier = Modifier.width(80.dp)
                        )
                        Text(stringResource(R.string.qk_02398, (interval).toString()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (freq == "WEEKLY") {
                        Text(stringResource(R.string.qk_02399), style = MaterialTheme.typography.labelMedium)
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
                            label = { Text(stringResource(R.string.qk_02400)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (freq == "YEARLY") {
                        OutlinedTextField(
                            value = yearMonth.toString(),
                            onValueChange = { it.toIntOrNull()?.let { v -> if (v in 1..12) yearMonth = v } },
                            label = { Text(stringResource(R.string.qk_02401)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                    }
                    // 结束日期（可选）：到达后停止重复
                    Text(stringResource(R.string.qk_02402), style = MaterialTheme.typography.labelMedium)
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
                        ) { Text(if (endDate.isBlank()) stringResource(R.string.qk_02403) else endDate) }
                        if (endDate.isNotBlank()) {
                            TextButton(onClick = { endDate = "" }) { Text(stringResource(R.string.qk_01470)) }
                        }
                    }
                }

                Text(stringResource(R.string.qk_02404), style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = targetMode == "specific", onClick = { targetMode = "specific" }, label = { Text(stringResource(R.string.qk_02405)) }, modifier = Modifier.weight(1f))
                    FilterChip(selected = targetMode == "auto", onClick = { targetMode = "auto" }, label = { Text(stringResource(R.string.qk_02406)) }, modifier = Modifier.weight(1f))
                }
                if (targetMode == "specific") {
                    val convs = QuroChatViewModel.instance.conversations.value.sortedByDescending { it.updatedAt }
                    if (convs.isEmpty()) {
                        Text(stringResource(R.string.qk_02407), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                            Text(c.title.ifBlank { stringResource(R.string.qk_02408) }, style = MaterialTheme.typography.bodyMedium)
                                            if (c.preview.isNotBlank())
                                                Text(c.preview, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else if (targetMode == "auto") {
                    Text(stringResource(R.string.qk_02409), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(stringResource(R.string.qk_02410), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            ) { Text(stringResource(R.string.qk_00198)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.qk_00011)) } }
    )
}