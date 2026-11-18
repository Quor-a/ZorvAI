package com.ai.assistance.quro.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ai.assistance.quro.core.hint.ExternalHintDispatcher
import com.ai.assistance.quro.core.hint.ExternalHintLedger
import com.ai.assistance.quro.core.hint.ExternalHintPrefs
import com.ai.assistance.quro.core.hint.ExternalHintReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * 外部事件设置页：三条投递通道共用的开关、合并策略、目录、来源静音与运行记录。
 * 以对话框形式挂在设置面板内部，不占用主界面状态。
 */
@Composable
fun QuroExternalHintSettingsScreen(
    onClose: () -> Unit,
    scaled: (Int) -> TextUnit = { it.sp },
) {
    val context = LocalContext.current
    val app = context.applicationContext
    val prefs = remember { ExternalHintPrefs.prefs(app) }
    val scope = rememberCoroutineScope()

    var enabled by remember { mutableStateOf(ExternalHintPrefs.enabled(app)) }
    var autoWake by remember { mutableStateOf(ExternalHintPrefs.autoWake(app)) }
    var muted by remember { mutableStateOf(ExternalHintPrefs.muted(app)) }
    var broadcastOn by remember { mutableStateOf(ExternalHintPrefs.broadcastEnabled(app)) }
    var aciOn by remember { mutableStateOf(ExternalHintPrefs.aciEnabled(app)) }
    var windowMs by remember { mutableStateOf(ExternalHintPrefs.mergeWindowMs(app)) }
    var path by remember { mutableStateOf(ExternalHintPrefs.inboxDir(app).absolutePath) }
    var sources by remember { mutableStateOf(ExternalHintPrefs.mutedSources(app)) }
    var sourceInput by remember { mutableStateOf("") }
    var recent by remember { mutableStateOf(emptyList<String>()) }
    var pending by remember { mutableStateOf(0) }
    var acked by remember { mutableStateOf(0) }
    var note by remember { mutableStateOf("") }

    // 账本读写放到 IO，避免设置页展开时在主线程读文件。
    suspend fun loadStats() {
        val lines = withContext(Dispatchers.IO) { ExternalHintLedger.recentLines(app).take(8) }
        val p = withContext(Dispatchers.IO) { ExternalHintLedger.pendingCount(app) }
        val a = withContext(Dispatchers.IO) { ExternalHintLedger.ackedCount(app) }
        recent = lines
        pending = p
        acked = a
    }
    LaunchedEffect(Unit) { loadStats() }

    fun refresh() {
        enabled = ExternalHintPrefs.enabled(app)
        autoWake = ExternalHintPrefs.autoWake(app)
        muted = ExternalHintPrefs.muted(app)
        broadcastOn = ExternalHintPrefs.broadcastEnabled(app)
        aciOn = ExternalHintPrefs.aciEnabled(app)
        windowMs = ExternalHintPrefs.mergeWindowMs(app)
        path = ExternalHintPrefs.inboxDir(app).absolutePath
        sources = ExternalHintPrefs.mutedSources(app)
        scope.launch { loadStats() }
    }

    fun put(key: String, value: Boolean, restart: Boolean = false) {
        prefs.edit().putBoolean(key, value).apply()
        if (restart) ExternalHintDispatcher.restart(app)
        refresh()
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(max = 640.dp),
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 8.dp,
        ) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("外部事件", fontSize = scaled(18), fontWeight = FontWeight.SemiBold)
                        Text(
                            "第三方应用投递待处理提示，作为系统提醒进入对话，不伪造用户消息",
                            fontSize = scaled(11), color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = null)
                    }
                }

                GroupCaption("通道与行为")
                SetGroup {
                    SetRow(
                        icon = Icons.Filled.Hub,
                        name = "启用外部事件",
                        sub = "关闭后目录监听与所有投递通道一并停用",
                        checked = enabled,
                        onToggle = { put(ExternalHintPrefs.KEY_ENABLED, !enabled, restart = true) },
                        scaled = scaled,
                    )
                    SetRow(
                        icon = Icons.Filled.Bolt,
                        name = "自动唤醒 AI",
                        sub = "会话空闲时立刻起一轮处理提示；关闭则只注入，等下一次发言再处理",
                        checked = autoWake,
                        onToggle = { put(ExternalHintPrefs.KEY_AUTO_WAKE, !autoWake) },
                        scaled = scaled,
                        enabled = enabled,
                    )
                    SetRow(
                        icon = Icons.Filled.Notifications,
                        name = "静音",
                        sub = "事件照常去重归档，但不注入、不通知、不唤醒",
                        checked = muted,
                        onToggle = { put(ExternalHintPrefs.KEY_MUTED, !muted) },
                        scaled = scaled,
                        enabled = enabled,
                    )
                    SetRow(
                        icon = Icons.Filled.Hub,
                        name = "广播通道",
                        sub = ExternalHintReceiver.ACTION,
                        checked = broadcastOn,
                        onToggle = { put(ExternalHintPrefs.KEY_BROADCAST, !broadcastOn) },
                        scaled = scaled,
                        enabled = enabled,
                    )
                    SetRow(
                        icon = Icons.Filled.Hub,
                        name = "ACI 能力",
                        sub = "quro_inject_hint，沿用现有 ACI 授权体系",
                        checked = aciOn,
                        onToggle = { put(ExternalHintPrefs.KEY_ACI, !aciOn) },
                        scaled = scaled,
                        enabled = enabled,
                    )
                }

                GroupCaption("合并窗口")
                SetGroup {
                    Text(
                        "短时间内的多条投递合并为一条「N 条待处理」提醒，避免连续刷屏与重复调用模型。priority=1 的事件立即注入。",
                        fontSize = scaled(11),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(0L to "不合并", 10_000L to "10 秒", 30_000L to "30 秒").forEach { (ms, label) ->
                            TextButton(
                                onClick = {
                                    prefs.edit().putLong(ExternalHintPrefs.KEY_MERGE_WINDOW, ms).apply()
                                    refresh()
                                },
                                enabled = enabled,
                            ) {
                                Text(
                                    if (windowMs == ms) "● $label" else label,
                                    fontSize = scaled(12),
                                    color = if (windowMs == ms) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                GroupCaption("投递目录")
                SetGroup {
                    Text(
                        path,
                        fontSize = scaled(11),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    Text(
                        "写入 {\"id\":\"...\",\"ts\":...,\"source\":\"...\",\"text\":\"...\",\"priority\":0} 或直接写纯文本；" +
                            "AI 处理完一轮后文件自动归档到 processed/。",
                        fontSize = scaled(11),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(onClick = {
                            prefs.edit().remove(ExternalHintPrefs.KEY_PATH).apply()
                            ExternalHintDispatcher.restart(app)
                            refresh()
                        }) { Text("外部目录", fontSize = scaled(12)) }
                        TextButton(onClick = {
                            prefs.edit().putString(ExternalHintPrefs.KEY_PATH, File(app.filesDir, "quro_inbox").absolutePath).apply()
                            ExternalHintDispatcher.restart(app)
                            refresh()
                        }) { Text("私有目录", fontSize = scaled(12)) }
                        TextButton(onClick = {
                            scope.launch(Dispatchers.IO) {
                                runCatching {
                                    val dir = ExternalHintPrefs.inboxDir(app)
                                    dir.mkdirs()
                                    val id = "test-${System.currentTimeMillis()}"
                                    val body = JSONObject()
                                        .put("id", id)
                                        .put("ts", System.currentTimeMillis())
                                        .put("source", "settings-test")
                                        .put("text", "这是一条测试提示，请简短确认已收到外部事件。")
                                        .put("priority", 1)
                                    File(dir, "$id.json").writeText(body.toString())
                                    ExternalHintDispatcher.restart(app)
                                }
                            }
                            note = "已写入测试事件，稍后会出现一条系统提醒"
                        }) { Text("投递测试事件", fontSize = scaled(12)) }
                    }
                    TextButton(
                        onClick = { refresh(); note = "运行记录已刷新" },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                        Text("刷新运行记录", fontSize = scaled(12))
                    }
                }

                GroupCaption("来源静音")
                SetGroup {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = sourceInput,
                            onValueChange = { sourceInput = it },
                            label = { Text("来源标识", fontSize = scaled(12)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            enabled = enabled,
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (sourceInput.isNotBlank()) {
                                    ExternalHintPrefs.toggleMutedSource(app, sourceInput)
                                    sourceInput = ""
                                    refresh()
                                }
                            },
                            enabled = enabled,
                        ) { Text("添加 / 移除", fontSize = scaled(12)) }
                    }
                    Text(
                        if (sources.isEmpty()) "当前无静音来源"
                        else "已静音：${sources.joinToString("、")}",
                        fontSize = scaled(11),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }

                GroupCaption("接入方式")
                SetGroup {
                    AccessHint(
                        "广播",
                        "${ExternalHintReceiver.ACTION} · extras: id / text / source / priority；" +
                            "发送方需声明 ai.aci.permission.SEND_ASSISTANT_DIRECTIVE",
                        scaled,
                    )
                    AccessHint(
                        "ACI",
                        "capability: quro_inject_hint · args: { text, id?, source?, priority? }；" +
                            "调用方需在本页 ACI 能力放行范围内",
                        scaled,
                    )
                    AccessHint(
                        "目录",
                        "任何能写入上面目录的脚本 / 应用都可投递，无需额外权限",
                        scaled,
                    )
                }

                GroupCaption("运行记录")
                SetGroup {
                    Text(
                        "待补投 ${pending} 条 · 合并队列 ${ExternalHintDispatcher.queuedCount()} 条 · 已处理 ${acked} 条",
                        fontSize = scaled(11),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    if (recent.isEmpty()) {
                        Text(
                            "暂无事件",
                            fontSize = scaled(11),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    } else {
                        recent.forEach {
                            Text(
                                it,
                                fontSize = scaled(10),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                            )
                        }
                    }
                }

                if (note.isNotBlank()) {
                    Text(
                        note,
                        fontSize = scaled(11),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun AccessHint(title: String, detail: String, scaled: (Int) -> TextUnit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, fontSize = scaled(12), fontWeight = FontWeight.Medium)
            Text(
                detail,
                fontSize = scaled(10),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
