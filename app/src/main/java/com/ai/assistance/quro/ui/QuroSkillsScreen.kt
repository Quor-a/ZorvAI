package com.ai.assistance.quro.ui

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.ai.assistance.quro.core.skill.QuroSkill
import com.ai.assistance.quro.core.skill.QuroSkillStore
import com.ai.assistance.quro.core.skill.DEFAULT_SKILL_PARAMS
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * 技能 SKILL 管理页。
 *
 * - 列出用户所有技能，支持启用开关 / 编辑 / 删除。
 * - 右下角 + 新增技能（名称 + 简介 + 指令正文 + 启用）。
 * - 启用的技能由 [com.ai.assistance.quro.ui.QuroChatViewModel.buildSystemPrompt] 注入系统提示词。
 */
@Composable
fun QuroSkillsScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    var skills by remember { mutableStateOf(QuroSkillStore.load(ctx)) }
    var showEditor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<QuroSkill?>(null) }

    fun refresh() { skills = QuroSkillStore.load(ctx) }

    // ═══ 导入技能（从 JSON 文件） ═══
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
        }.getOrNull()
        if (text.isNullOrBlank()) {
            Toast.makeText(ctx, "读取文件失败或内容为空", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        val imported = runCatching { parseSkillJson(text) }.getOrElse {
            Toast.makeText(ctx, "JSON 解析失败：${it.message}", Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        if (imported.isEmpty()) {
            Toast.makeText(ctx, "未识别到有效技能（需含 name 字段的 JSON）", Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        imported.forEach { QuroSkillStore.addOrUpdate(ctx, it) }
        refresh()
        Toast.makeText(ctx, "已导入 ${imported.size} 个技能", Toast.LENGTH_SHORT).show()
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                    Text("技能 SKILL", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp))
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }) {
                        Icon(Icons.Filled.FileDownload, contentDescription = "导入技能", tint = MaterialTheme.colorScheme.primary)
                    }
                    Text(
                        "${skills.count { it.enabled }} 启用 / ${skills.size} 共",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }

            if (skills.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "还没有技能。\n点右下角 + 新增一个，或在对话框里让 AI 帮你写。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    items(skills, key = { it.id }) { s ->
                        SkillRow(
                            skill = s,
                            onToggle = { enabled ->
                                QuroSkillStore.addOrUpdate(ctx, s.copy(enabled = enabled, updatedAt = System.currentTimeMillis()))
                                refresh()
                            },
                            onEdit = { editing = s; showEditor = true },
                            onDelete = { QuroSkillStore.remove(ctx, s.id); refresh() },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { editing = null; showEditor = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primary,
        ) {
            Icon(Icons.Filled.Add, contentDescription = "新增技能", tint = MaterialTheme.colorScheme.onPrimary)
        }
    }

    if (showEditor) {
        SkillEditorDialog(
            initial = editing,
            onDismiss = { showEditor = false; editing = null },
            onSave = { name, description, prompt, enabled, trigger, parametersJson, callable, alwaysOn ->
                val base = editing ?: QuroSkill(id = UUID.randomUUID().toString(), name = "")
                QuroSkillStore.addOrUpdate(
                    ctx,
                    base.copy(
                        name = name,
                        description = description,
                        prompt = prompt,
                        enabled = enabled,
                        trigger = trigger,
                        parametersJson = parametersJson,
                        callable = callable,
                        alwaysOn = alwaysOn,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                showEditor = false
                editing = null
                refresh()
            },
        )
    }
}

@Composable
private fun SkillRow(
    skill: QuroSkill,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(skill.name, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface)
            if (skill.description.isNotBlank()) {
                Text(skill.description, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2)
            }
            Text(
                if (skill.prompt.isBlank()) "（无指令，启用也不会注入）" else "指令 ${skill.prompt.length} 字",
                style = MaterialTheme.typography.labelSmall,
                color = if (skill.prompt.isBlank()) cs.error else cs.onSurfaceVariant,
            )
        }
        IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "编辑", tint = cs.primary) }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "删除", tint = cs.error) }
        Switch(checked = skill.enabled, onCheckedChange = onToggle)
    }
}

@Composable
private fun SkillEditorDialog(
    initial: QuroSkill?,
    onDismiss: () -> Unit,
    onSave: (name: String, description: String, prompt: String, enabled: Boolean, trigger: String, parametersJson: String, callable: Boolean, alwaysOn: Boolean) -> Unit,
) {
    var name by remember { mutableStateOf(TextFieldValue(initial?.name ?: "")) }
    var description by remember { mutableStateOf(TextFieldValue(initial?.description ?: "")) }
    var prompt by remember { mutableStateOf(TextFieldValue(initial?.prompt ?: "")) }
    var trigger by remember { mutableStateOf(TextFieldValue(initial?.trigger ?: "")) }
    var parametersJson by remember { mutableStateOf(TextFieldValue(initial?.parametersJson ?: DEFAULT_SKILL_PARAMS)) }
    var callable by remember { mutableStateOf(initial?.callable ?: true) }
    var alwaysOn by remember { mutableStateOf(initial?.alwaysOn ?: true) }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }
    val cs = MaterialTheme.colorScheme

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = {
                val n = name.text.trim()
                if (n.isNotEmpty()) onSave(n, description.text.trim(), prompt.text, enabled, trigger.text.trim(), parametersJson.text.ifBlank { DEFAULT_SKILL_PARAMS }, callable, alwaysOn)
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text(if (initial == null) "新增技能" else "编辑技能") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("名称（必填）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = description, onValueChange = { description = it },
                    label = { Text("简介（可选）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = prompt, onValueChange = { prompt = it },
                    label = { Text("技能指令（注入系统提示词）") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                    maxLines = 12,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = trigger, onValueChange = { trigger = it },
                    label = { Text("触发词 / trigger（可选，逗号分隔，用于将来自动匹配）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = parametersJson, onValueChange = { parametersJson = it },
                    label = { Text("参数 Schema (JSON，function calling 入参)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("可作为工具调用（注册为 AI 可调用函数）", modifier = Modifier.weight(1f))
                    Switch(checked = callable, onCheckedChange = { callable = it })
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("常驻系统提示词（关闭则仅触发词命中时注入）", modifier = Modifier.weight(1f))
                    Switch(checked = alwaysOn, onCheckedChange = { alwaysOn = it })
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("启用（关闭则不会注入系统提示词）", modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }
        },
    )
}

// ==================== 技能导入（JSON 文件解析） ====================

/**
 * 从 JSON 字符串解析技能列表。
 *
 * 支持两种格式：
 * - 单个对象：{"name":"...","prompt":"...","description":"...","enabled":true}
 * - 数组：[{"name":"...","prompt":"..."}, ...]
 *
 * 导入时自动生成新 UUID（避免 id 冲突），保留原有 enabled 状态。
 */
private fun parseSkillJson(json: String): List<QuroSkill> {
    val raw = json.trim()
    val arr = if (raw.startsWith("[")) JSONArray(raw) else JSONArray("[$raw]")
    val out = mutableListOf<QuroSkill>()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val name = o.optString("name", "").trim()
        if (name.isEmpty()) continue
        out.add(
            QuroSkill(
                id = UUID.randomUUID().toString(),
                name = name,
                description = o.optString("description", "").trim(),
                prompt = o.optString("prompt", "").trim(),
                enabled = o.optBoolean("enabled", true),
                trigger = o.optString("trigger", "").trim(),
                parametersJson = o.optString("parametersJson", DEFAULT_SKILL_PARAMS),
                callable = o.optBoolean("callable", true),
                alwaysOn = o.optBoolean("alwaysOn", true),
                updatedAt = System.currentTimeMillis(),
            )
        )
    }
    return out
}
