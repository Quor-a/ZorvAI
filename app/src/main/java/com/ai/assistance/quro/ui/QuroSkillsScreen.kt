package com.ai.assistance.quro.ui
import androidx.compose.ui.res.stringResource
import com.ai.assistance.quro.R
import com.ai.assistance.quro.util.qstr

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
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.ai.assistance.quro.core.skill.QuroSkill
import com.ai.assistance.quro.core.skill.QuroSkillStore
import com.ai.assistance.quro.core.skill.QuroSkillSuites
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
技能（SKILL）是一段工作指令。它不会自动生效：在对话框「选择技能」里选中后，全文会跟那一条消息一起发给 AI，AI 直接照着做。

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
• 技能指令：技能的正文（留空则选了也没有内容可发给 AI）。
• 触发词：逗号分隔，供搜索与将来自动匹配（如「订票,买火车票」）。
• 参数 Schema (JSON)：当「可作为工具调用」开启时，这是 function calling 的入参定义，必须是标准 JSON Schema：
  {"type":"object","properties":{"city":{"type":"string","description":"城市名"}},"required":["city"]}
• 可作为工具调用：开启后 AI 能直接调用该技能（注册为函数），这是另一条独立通道。
• 常驻系统提示词：是否常驻目录（元数据），不决定本次消息用不用这个技能。
• 没有「启用」开关：技能库只是素材库，用不用由你在对话框选择。

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
 * 技能 SKILL 管理页（= 技能素材库）。
 *
 * - 列出所有技能，支持编辑 / 导出 / 删除；**没有启用/禁用开关**。
 * - 技能在这里只管「库里有什么」，用哪个由用户在对话框「选择技能」里挑选，
 *   选中后全文随那一条消息发给 AI（见 `QuroSkillStore.attachedBlock`）。
 * - 系统提示词里只放一行一条的元数据目录，供 AI 自行按需调 `skill` 工具，
 *   与「用户显式选择」是两条独立通道，后者优先级更高。
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
    var showGithubImport by remember { mutableStateOf(false) }
    val skillsScope = androidx.compose.runtime.rememberCoroutineScope()

    fun refresh() { skills = QuroSkillStore.load(ctx) }

    var query by remember { mutableStateOf("") }
    val visibleSkills = remember(skills, query) {
        val q = query.trim()
        if (q.isEmpty()) skills
        else skills.filter {
            it.name.contains(q, true) || it.description.contains(q, true) ||
                it.descriptionZh.contains(q, true) || it.id.contains(q, true) ||
                it.suite.contains(q, true) || it.trigger.contains(q, true)
        }
    }

    // ═══ 导入技能（从 JSON 文件） ═══
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
        }.getOrNull()
        if (text.isNullOrBlank()) {
            Toast.makeText(ctx, qstr(R.string.qk_02414), Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        // 宽松导入策略：依次尝试 SKILL.md 解析（兼容 anthropics/skills 等生态）→ 应用内技能 JSON →
        // 工具规格 JSON（"技能转换"：工具 → 技能）。三者均失败才报错。
        val toolSpecSkill = runCatching { QuroSkill.fromToolSpec(text) }.getOrNull()
        val imported = QuroSkillStore.parseSkillMd(text).takeIf { it.isNotEmpty() }
            ?: runCatching { parseSkillJson(text) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: toolSpecSkill?.let { listOf(it) }
        if (imported.isNullOrEmpty()) {
            Toast.makeText(ctx, qstr(R.string.qk_02415), Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        imported.forEach { QuroSkillStore.addOrUpdate(ctx, it) }
        refresh()
        Toast.makeText(ctx, qstr(R.string.qk_02416, (imported.size).toString()), Toast.LENGTH_SHORT).show()
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
        }.onFailure { Toast.makeText(ctx, qstr(R.string.qk_01993, (it.message).toString()), Toast.LENGTH_LONG).show() }
    }
    // 选择部分技能导出为应用内 JSON 数组（可被本 App 重新导入）
    val exportSelectedLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val list = pendingExport
        runCatching {
            val arr = JSONArray()
            list.forEach { arr.put(it.toExportJson()) }
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(arr.toString(2).toByteArray()) }
        }.onFailure { Toast.makeText(ctx, qstr(R.string.qk_01993, (it.message).toString()), Toast.LENGTH_LONG).show() }
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
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.qk_00143)) }
                    Text(stringResource(R.string.qk_02417), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp))
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { showExportPicker = true }) {
                        Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.qk_02418), tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }) {
                        Icon(Icons.Filled.FileDownload, contentDescription = stringResource(R.string.qk_02419), tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { showAuthorGuide = true }) {
                        Icon(Icons.Filled.Help, contentDescription = stringResource(R.string.qk_02420), tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = {
                        val r = QuroSkillStore.verifyBuiltinSignatures(ctx)
                        val msg = buildString {
                            append(qstr(R.string.qk_02421))
                            append(qstr(R.string.qk_02422, (r.verified).toString(), (r.total).toString()))
                            if (r.failed > 0) append("，⚠️ 失败(疑似篡改) ${r.failed}：${r.failedNames.joinToString("、")}")
                            if (r.unsigned > 0) append(qstr(R.string.qk_02424, (r.unsigned).toString()))
                        }
                        Toast.makeText(ctx, msg, if (r.failed > 0) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Filled.Verified, contentDescription = stringResource(R.string.qk_02425), tint = MaterialTheme.colorScheme.primary)
                    }
                    Text(
                        "共 ${skills.size} 个 · 在对话框选择后发送",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索技能名称、描述、触发词或套件") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            )

            if (visibleSkills.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (query.isBlank()) stringResource(R.string.qk_02427) else "没有匹配「$query」的技能",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val grouped = remember(visibleSkills) { QuroSkillSuites.group(visibleSkills) }
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    grouped.forEach { (suiteId, groupSkills) ->
                        item(key = "suite_header_$suiteId") {
                            SuiteHeader(suiteId = suiteId, count = groupSkills.size)
                        }
                        items(groupSkills, key = { it.id }) { s ->
                            SkillRow(
                                skill = s,
                                onEdit = { editing = s; showEditor = true },
                                onExport = { pendingSkill = s; exportMdLauncher.launch("${s.name}.skill.md") },
                                onDelete = {
                                    QuroSkillStore.remove(ctx, s.id)
                                    QuroToolRegistry.active?.remove(QuroSkill.toolNameOf(s.name))
                                    refresh()
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { editing = null; showEditor = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primary,
        ) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.qk_02428), tint = MaterialTheme.colorScheme.onPrimary)
        }
        androidx.compose.material3.SmallFloatingActionButton(
            onClick = { showGithubImport = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 92.dp),
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Icon(Icons.Filled.FileDownload, "从 GitHub 导入技能", tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        val packageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            skillsScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val result = runCatching {
                    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("读不到文件")
                    val (a, u) = QuroSkillStore.importSkillPackage(ctx, bytes, "")
                    "导入成功：新增 $a 个，更新 $u 个"
                }.getOrElse { "导入失败：${it.message}" }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    Toast.makeText(ctx, result, Toast.LENGTH_LONG).show()
                    if (result.startsWith("导入成功")) refresh()
                }
            }
        }
        androidx.compose.material3.SmallFloatingActionButton(
            onClick = { packageLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 152.dp),
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Icon(Icons.Filled.FileUpload, "导入技能包（ZIP/.skill）", tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }

    if (showGithubImport) {
        var ghUrl by remember { mutableStateOf("") }
        var importing by remember { mutableStateOf(false) }
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        AlertDialog(
            onDismissRequest = { if (!importing) showGithubImport = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("从 GitHub 导入技能", fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    Text(
                        "支持仓库链接或 /tree/<分支>/<路径> 链接。\n自动扫描包内全部 SKILL.md，同名技能覆盖更新。",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = ghUrl,
                        onValueChange = { ghUrl = it },
                        placeholder = { Text("https://github.com/owner/repo", fontSize = 13.sp) },
                        singleLine = true,
                        enabled = !importing,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (importing) {
                        Spacer(Modifier.height(8.dp))
                        Text("正在读取…", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !importing && ghUrl.isNotBlank(),
                    onClick = {
                        importing = true
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            val result = runCatching { QuroSkillStore.importFromGitHub(ctx, ghUrl.trim(), "") }
                                .fold({ (a, u) -> "导入成功：新增 $a 个，更新 $u 个" },
                                    { "导入失败：${it.message}" })
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                importing = false
                                Toast.makeText(ctx, result, Toast.LENGTH_LONG).show()
                                if (result.startsWith("导入成功")) { showGithubImport = false; refresh() }
                            }
                        }
                    },
                ) { Text("导入") }
            },
            dismissButton = { TextButton(onClick = { showGithubImport = false }) { Text("取消") } },
        )
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
                        Toast.makeText(ctx, qstr(R.string.qk_02429), Toast.LENGTH_SHORT).show()
                    } else {
                        pendingExport = pick
                        exportSelectedLauncher.launch("quro_skills_${pick.size}_${System.currentTimeMillis()}.json")
                    }
                }) { Text(stringResource(R.string.qk_01542, (selected.size).toString())) }
            },
            dismissButton = { TextButton(onClick = { showExportPicker = false }) { Text(stringResource(R.string.qk_00011)) } },
            title = { Text(stringResource(R.string.qk_02430)) },
            text = {
                if (skills.isEmpty()) {
                    Text(stringResource(R.string.qk_02431), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            onSave = { name, description, descriptionZh, prompt, _, trigger, parametersJson, callable, alwaysOn ->
                val base = editing ?: QuroSkill(id = UUID.randomUUID().toString(), name = "")
                QuroSkillStore.addOrUpdate(
                    ctx,
                    base.copy(
                        name = name,
                        description = description,
                        descriptionZh = descriptionZh,
                        prompt = prompt,
                        enabled = true,
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
            confirmButton = { TextButton(onClick = { showAuthorGuide = false }) { Text(stringResource(R.string.qk_00054)) } },
            title = { Text(stringResource(R.string.qk_02432)) },
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
            val skillDesc = if (skill.descriptionZh.isNotBlank()) skill.descriptionZh else skill.description
            if (skillDesc.isNotBlank()) {
                Text(skillDesc, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2)
            }
            Text(
                if (skill.prompt.isBlank()) stringResource(R.string.qk_02433) else stringResource(R.string.qk_02434, (skill.prompt.length).toString()),
                style = MaterialTheme.typography.labelSmall,
                color = if (skill.prompt.isBlank()) cs.error else cs.onSurfaceVariant,
            )
            if (skill.id.startsWith("zorv_") && skill.signState != "unknown") {
                val (label, color) = when (skill.signState) {
                    "verified" -> "签名已校验" to cs.primary
                    "failed" -> "签名失败·疑似篡改" to cs.error
                    "unsigned" -> "未签名" to cs.outline
                    else -> "" to cs.onSurfaceVariant
                }
                if (label.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(label, style = MaterialTheme.typography.labelSmall, color = color)
                }
            }
        }
        IconButton(onClick = onExport) { Icon(Icons.Filled.FileDownload, contentDescription = stringResource(R.string.qk_02438), tint = cs.primary) }
        IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.qk_00299), tint = cs.primary) }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.qk_00091), tint = cs.error) }
        // 这里没有「启用」开关：技能是否生效由用户在对话框选择后随消息发送决定。
        Text(
            if (skill.prompt.isBlank()) "待补正文" else "对话框可选",
            style = MaterialTheme.typography.labelSmall,
            color = if (skill.prompt.isBlank()) cs.error else cs.primary,
        )
    }
}

@Composable
private fun SuiteHeader(suiteId: String, count: Int) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceVariant.copy(alpha = 0.5f),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                QuroSkillSuites.label(suiteId),
                style = MaterialTheme.typography.titleSmall,
                color = cs.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.qk_02439, (count).toString()),
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SkillEditorDialog(
    initial: QuroSkill?,
    onDismiss: () -> Unit,
    onSave: (name: String, description: String, descriptionZh: String, prompt: String, enabled: Boolean, trigger: String, parametersJson: String, callable: Boolean, alwaysOn: Boolean) -> Unit,
) {
    var name by remember { mutableStateOf(TextFieldValue(initial?.name ?: "")) }
    var description by remember { mutableStateOf(TextFieldValue(initial?.description ?: "")) }
    var descriptionZh by remember { mutableStateOf(TextFieldValue(initial?.descriptionZh ?: "")) }
    var prompt by remember { mutableStateOf(TextFieldValue(initial?.prompt ?: "")) }
    var trigger by remember { mutableStateOf(TextFieldValue(initial?.trigger ?: "")) }
    var parametersJson by remember { mutableStateOf(TextFieldValue(initial?.parametersJson ?: DEFAULT_SKILL_PARAMS)) }
    var callable by remember { mutableStateOf(initial?.callable ?: true) }
    var alwaysOn by remember { mutableStateOf(initial?.alwaysOn ?: true) }
    val cs = MaterialTheme.colorScheme

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = {
                val n = name.text.trim()
                // enabled 恒为 true：库内条目一律可选，不再有「关掉就 AI 不知道」的隐性状态。
                if (n.isNotEmpty()) onSave(n, description.text.trim(), descriptionZh.text.trim(), prompt.text, true, trigger.text.trim(), parametersJson.text.ifBlank { DEFAULT_SKILL_PARAMS }, callable, alwaysOn)
            }) { Text(qstr(R.string.qk_00198)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.qk_00011)) } },
        title = { Text(if (initial == null) stringResource(R.string.qk_02428) else stringResource(R.string.qk_02440)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(stringResource(R.string.qk_02441)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = description, onValueChange = { description = it },
                    label = { Text(stringResource(R.string.qk_02442)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = descriptionZh, onValueChange = { descriptionZh = it },
                    label = { Text(stringResource(R.string.qk_02442) + "(中文)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = prompt, onValueChange = { prompt = it },
                    label = { Text(stringResource(R.string.qk_02443)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                    maxLines = 12,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = trigger, onValueChange = { trigger = it },
                    label = { Text(stringResource(R.string.qk_02444)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = parametersJson, onValueChange = { parametersJson = it },
                    label = { Text(stringResource(R.string.qk_02445)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                    maxLines = 8,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.qk_02446), modifier = Modifier.weight(1f))
                    Switch(checked = callable, onCheckedChange = { callable = it })
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.qk_02447), modifier = Modifier.weight(1f))
                    Switch(checked = alwaysOn, onCheckedChange = { alwaysOn = it })
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "保存后到对话框「选择技能」里选中它，才会随那一条消息发给 AI。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
 * 导入时自动生成新 UUID（避免 id 冲突），并统一置为库内可选（不再保留旧的启用状态）。
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
                descriptionZh = o.optString("description_zh", "").trim(),
                prompt = o.optString("prompt", "").trim(),
                enabled = true,
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