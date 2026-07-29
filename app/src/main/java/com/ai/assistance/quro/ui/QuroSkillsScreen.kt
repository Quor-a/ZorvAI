package com.ai.assistance.quro.ui

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Share
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
import com.ai.assistance.quro.core.tools.QuroToolRegistry
import com.ai.assistance.quro.core.tools.QuroDownloadUtil
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * 「如何添加技能」说明（开发者文档，可一键下载到本地）。
 */
private val SKILL_AUTHOR_GUIDE = """
技能（SKILL）让你的 AI 多出可注入系统提示词的指令，或注册成 AI 可调用函数。

一、最小可用技能（SKILL.md 风格）：
---
name: 示例技能
description: 一句话说清这个技能做什么，AI 据此决定是否启用
---
# 指令正文
当用户说 XXX 时，你应当……（注入系统提示词的内容）

二、应用内字段对照：
• 名称（必填）：技能的 key，建议小写蛇形。
• 简介：对应 description，影响 AI 自动匹配。
• 技能指令：注入系统提示词的正文（留空则启用也不生效）。
• 触发词：逗号分隔，未来用于自动匹配（如「订票,买火车票」）。
• 参数 Schema (JSON)：当「可作为工具调用」开启时，这是 function calling 的入参定义，必须是标准 JSON Schema：
  {"type":"object","properties":{"city":{"type":"string","description":"城市名"}},"required":["city"]}
• 可作为工具调用：开启后 AI 能直接调用该技能（注册为函数）；关闭则只注入提示词。
• 常驻系统提示词：开启则始终注入；关闭则仅触发词命中时注入。
• 启用：总开关，关闭则不注入。

三、参数怎么写（JSON Schema 要点）：
• 根必须是 {"type":"object","properties":{...},"required":[...]}。
• 每个参数：type ∈ string/integer/number/boolean/array/object；用 description 说明含义。
• required 数组列出必填参数名。
• 例：{"type":"object","properties":{"a":{"type":"string"},"n":{"type":"integer"}},"required":["a"]}

四、导入 / 导出 / 转换：
• 右上「导入」支持开源 SKILL.md、应用内技能 JSON、工具规格 JSON（工具→技能转换）。
• 单技能可导出为标准 SKILL.md，与 anthropics/skills 等生态兼容。
"""

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
    var showAuthorGuide by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<QuroSkill?>(null) }
    var showExportPicker by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<List<QuroSkill>>(emptyList()) }

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
        // 宽松导入策略：依次尝试 SKILL.md 解析（兼容 anthropics/skills 等生态）→ 应用内技能 JSON →
        // 工具规格 JSON（"技能转换"：工具 → 技能）。三者均失败才报错。
        val toolSpecSkill = runCatching { QuroSkill.fromToolSpec(text) }.getOrNull()
        val imported = QuroSkillStore.parseSkillMd(text).takeIf { it.isNotEmpty() }
            ?: runCatching { parseSkillJson(text) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: toolSpecSkill?.let { listOf(it) }
        if (imported.isNullOrEmpty()) {
            Toast.makeText(ctx, "导入失败：内容不是有效的 SKILL.md / 技能 JSON / 工具规格 JSON", Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        imported.forEach { QuroSkillStore.addOrUpdate(ctx, it) }
        refresh()
        Toast.makeText(ctx, "已导入 ${imported.size} 个技能", Toast.LENGTH_SHORT).show()
    }

    // ═══ 导出技能 ═══
    var pendingSkill by remember { mutableStateOf<QuroSkill?>(null) }
    // 单个技能导出为开放标准 SKILL.md（与 anthropics/skills 等生态兼容）
    val exportMdLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val s = pendingSkill ?: return@rememberLauncherForActivityResult
        pendingSkill = null
        runCatching {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(s.toSkillMd().toByteArray()) }
        }.onFailure { Toast.makeText(ctx, "导出失败：${it.message}", Toast.LENGTH_LONG).show() }
    }
    // 选择部分技能导出为应用内 JSON 数组（可被本 App 重新导入）
    val exportSelectedLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val list = pendingExport
        runCatching {
            val arr = JSONArray()
            list.forEach { arr.put(it.toExportJson()) }
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(arr.toString(2).toByteArray()) }
        }.onFailure { Toast.makeText(ctx, "导出失败：${it.message}", Toast.LENGTH_LONG).show() }
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
                    IconButton(onClick = { showExportPicker = true }) {
                        Icon(Icons.Filled.Share, contentDescription = "导出全部技能（JSON）", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }) {
                        Icon(Icons.Filled.FileDownload, contentDescription = "从 SKILL.md / JSON / 工具规格导入技能", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { showAuthorGuide = true }) {
                        Icon(Icons.Filled.Help, contentDescription = "如何添加技能（说明）", tint = MaterialTheme.colorScheme.primary)
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
                        "还没有技能。\n点右上角导入按钮，从开源 SKILL.md 粘贴导入；\n或点右下角 + 新增一个，也可在对话框里让 AI 帮你写。",
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
                            onExport = { pendingSkill = s; exportMdLauncher.launch("${s.name}.skill.md") },
                            onDelete = {
                                QuroSkillStore.remove(ctx, s.id)
                                QuroToolRegistry.active?.remove("skill__${s.name}")
                                refresh()
                            },
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

    if (showExportPicker) {
        var selected by remember(showExportPicker) { mutableStateOf(skills.map { it.id }.toSet()) }
        AlertDialog(
            onDismissRequest = { showExportPicker = false },
            confirmButton = {
                Button(onClick = {
                    val pick = skills.filter { it.id in selected }
                    showExportPicker = false
                    if (pick.isEmpty()) {
                        Toast.makeText(ctx, "未选择任何技能", Toast.LENGTH_SHORT).show()
                    } else {
                        pendingExport = pick
                        exportSelectedLauncher.launch("quro_skills_${pick.size}_${System.currentTimeMillis()}.json")
                    }
                }) { Text("导出选中 (${selected.size})") }
            },
            dismissButton = { TextButton(onClick = { showExportPicker = false }) { Text("取消") } },
            title = { Text("选择要导出的技能") },
            text = {
                if (skills.isEmpty()) {
                    Text("当前没有可导出的技能。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(skills) { s ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { selected = if (s.id in selected) selected - s.id else selected + s.id }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(checked = s.id in selected, onCheckedChange = { selected = if (it) selected + s.id else selected - s.id })
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(s.name, style = MaterialTheme.typography.bodyMedium)
                                    if (s.description.isNotBlank())
                                        Text(s.description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            },
        )
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

    if (showAuthorGuide) {
        AlertDialog(
            onDismissRequest = { showAuthorGuide = false },
            confirmButton = { TextButton(onClick = { showAuthorGuide = false }) { Text("知道了") } },
            title = { Text("如何添加技能") },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    Text(SKILL_AUTHOR_GUIDE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
    }
}

@Composable
private fun SkillRow(
    skill: QuroSkill,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onExport: () -> Unit,
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
        IconButton(onClick = onExport) { Icon(Icons.Filled.FileDownload, contentDescription = "导出 SKILL.md", tint = cs.primary) }
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
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                    maxLines = 8,
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
