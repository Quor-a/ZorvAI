package com.ai.assistance.quro.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ai.assistance.quro.core.memory.QuroMemoryEntry
import com.ai.assistance.quro.core.memory.QuroMemoryRepository
import com.ai.assistance.quro.core.QuroPersona
import com.ai.assistance.quro.core.tools.QuroCloudTtsCatalog
import com.ai.assistance.quro.core.tools.QuroTtsProviders
import com.ai.assistance.quro.core.QuroTag
import com.ai.assistance.quro.core.QuroTagRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 灵魂注入 / 灵魂卡 / 记忆库 / 标签 的 UI（原创）。
 * - SoulInjectionSheet：底部弹出，小卡片网格展示灵魂卡，每张卡支持激活 / 编辑 / 删除。
 * - PersonaEditDialog：全屏编辑（头像[仅图片上传+裁剪] / 描述 / 角色设定 / 开场白 / 聊天设定 / 语音设定 / AI孵化 / 选择标签[从全局标签池] / 管理标签）。
 * - TagManageScreen：独立的全局标签管理界面（创建 / 展开编辑含 JSON / 删除）。
 * - MemoryDialog：全屏记忆库。
 *
 * 注：表情头像已移除，头像仅支持图片上传（含裁剪）；未上传时退化为「名称首字母」圆标。
 */
private val AVATAR_BG = Color(0xFF211E1A)

/** 将用户选择的图片头像复制到应用私有目录，返回绝对路径（失败返回 null）。 */
private fun copyAvatarToInternal(ctx: Context, uri: Uri, id: String): String? {
    return runCatching {
        val dir = File(ctx.filesDir, "quro_avatars")
        dir.mkdirs()
        val dst = File(dir, "$id.jpg")
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            dst.outputStream().use { out -> input.copyTo(out) }
        }
        dst.absolutePath
    }.getOrNull()
}

// ==================== 灵魂注入主入口（小卡片网格） ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoulInjectionSheet(
    vm: QuroPersonaViewModel,
    onDismiss: () -> Unit,
    onCreate: () -> Unit,
    onEdit: (String) -> Unit,
    onManageMemory: () -> Unit,
) {
    val personas by vm.personas.collectAsState()
    val active by vm.activePersona.collectAsState()
    val heartbeatOn by vm.personaHeartbeatEnabled.collectAsState()
    val incubatingStates by vm.incubatingStates.collectAsState()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(16.dp).padding(bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("灵魂注入", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onCreate) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("新建")
                }
                TextButton(onClick = onManageMemory) {
                    Icon(Icons.Filled.Memory, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("记忆库")
                }
            }
            Text(
                "选择一张灵魂卡，AI 将以它的身份与你交流",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
            )

            // 心跳孵化全局开关
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Favorite,
                    "心跳孵化",
                    tint = if (heartbeatOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("心跳孵化（后台自动孵化每张灵魂卡）", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                Text(
                    if (heartbeatOn) "开启" else "关闭",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (heartbeatOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Switch(checked = heartbeatOn, onCheckedChange = { vm.setPersonaHeartbeatEnabled(it) })
            }

            LazyColumn(
                modifier = Modifier.heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(personas, key = { it.id }) { p ->
                    PersonaSmallCard(
                        persona = p,
                        isActive = p.id == active?.id,
                        incubating = incubatingStates[p.id] == true,
                        lastIncubatedAt = p.lastIncubatedAt,
                        onClick = { vm.setActive(p.id); onDismiss() },
                        onEdit = { onEdit(p.id) },
                        onDelete = {
                            vm.delete(p.id)
                            if (personas.size <= 1) onDismiss()
                        },
                        onIncubate = { vm.incubate(p) },
                    )
                }
            }

            if (personas.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("还没有灵魂卡", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("点击「新建」创建第一张灵魂卡", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** 灵魂卡小卡片：激活 / 编辑 / 删除 / 手动孵化 四个操作可见，并显示每卡独立心跳状态。 */
@Composable
private fun PersonaSmallCard(
    persona: QuroPersona,
    isActive: Boolean,
    incubating: Boolean,
    lastIncubatedAt: Long,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onIncubate: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (isActive) cs.primaryContainer else cs.surfaceVariant,
        tonalElevation = if (isActive) 4.dp else 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AvatarContent(persona.avatarUri, persona.name, 36)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        persona.name.ifBlank { "未命名" },
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        persona.description.ifBlank { "暂无描述" },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isActive) cs.onPrimaryContainer else cs.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (isActive) {
                    Icon(Icons.Filled.Check, null, tint = cs.primary, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Edit, "编辑", Modifier.size(16.dp), tint = cs.onSurfaceVariant)
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Delete, "删除", Modifier.size(16.dp), tint = cs.error)
                }
                // 手动「AI孵化」按钮（独立触发该卡孵化）
                IconButton(onClick = onIncubate, enabled = !incubating, modifier = Modifier.size(28.dp)) {
                    if (incubating) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Favorite, "AI孵化", Modifier.size(16.dp), tint = cs.primary)
                    }
                }
            }

            // 每卡独立孵化状态
            val status = when {
                incubating -> "孵化中…"
                lastIncubatedAt > 0 -> {
                    val mins = (System.currentTimeMillis() - lastIncubatedAt) / 60000
                    if (mins < 60) "最近孵化 ${mins}分钟前" else "最近孵化 ${mins / 60}小时前"
                }
                else -> "未孵化"
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Favorite,
                    null,
                    tint = when {
                        incubating -> cs.primary
                        lastIncubatedAt > 0 -> cs.primary.copy(alpha = 0.6f)
                        else -> cs.onSurfaceVariant
                    },
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    status,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (incubating) cs.primary else cs.onSurfaceVariant,
                )
            }

            if (persona.tags.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    persona.tags.take(3).map { tag ->
                        // 安全兜底：若标签内容意外包含 JSON（旧数据残留），提取 name 字段
                        val clean = if (tag.startsWith("{")) {
                            val extracted = runCatching { org.json.JSONObject(tag).optString("name", "").trim() }.getOrNull()
                            extracted?.takeIf { it.isNotBlank() } ?: tag
                        } else tag
                        clean
                    }.forEach { tag ->
                        SuggestionChip(onClick = { }, label = { Text(tag, fontSize = 11.sp) })
                    }
                    if (persona.tags.size > 3) {
                        Text("+${persona.tags.size - 3}", fontSize = 11.sp, color = cs.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterVertically))
                    }
                }
            }
        }
    }
}

// ==================== 头像展示（图片 / 首字母退化） ====================

@Composable
fun AvatarContent(avatarUri: String, name: String, size: Int = 40) {
    if (avatarUri.isNotBlank()) {
        var bmp by remember(avatarUri) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
        LaunchedEffect(avatarUri) {
            bmp = withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeFile(avatarUri)?.asImageBitmap() }.getOrNull()
            }
        }
        if (bmp != null) {
            Image(bitmap = bmp!!, contentDescription = name.ifBlank { "头像" },
                modifier = Modifier.size(size.dp).clip(CircleShape))
            return
        }
    }
    // 无图：退化为名称首字母圆标（已移除表情头像）
    val safeName = name.ifBlank { "Q" }
    val letter = when (val c = safeName.firstOrNull()?.uppercase()) {
        null, "?" -> "Q"  // 兜底：绝不显示问号
        else -> c
    }
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(AVATAR_BG),
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, fontSize = (size * 0.45f).sp, color = Color.White)
    }
}

// ==================== 灵魂卡编辑对话框 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonaEditDialog(
    initial: QuroPersona,
    vm: QuroPersonaViewModel,
    isNew: Boolean,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var avatarType by remember { mutableStateOf(initial.avatarType.ifBlank { "image" }) }
    var avatarUri by remember { mutableStateOf(initial.avatarUri) }
    var description by remember { mutableStateOf(initial.description) }
    var roleSetting by remember { mutableStateOf(initial.roleSetting) }
    var opening by remember { mutableStateOf(initial.opening) }
    var chatSetting by remember { mutableStateOf(initial.chatSetting) }
    var voiceSetting by remember { mutableStateOf(initial.voiceSetting) }
    var selectedTags by remember { mutableStateOf(initial.tags) }   // 仅存全局标签名称
    var showTagManager by remember { mutableStateOf(false) }

    val ctx = LocalContext.current
    val tagRepo = remember { QuroTagRepository(ctx.applicationContext) }

    // 裁剪 → 复制到私有目录（先定义，供 imagePicker 调用）
    val cropLauncher = rememberLauncherForActivityResult(CropImageContract()) { result ->
        if (result.isSuccessful) {
            result.uriContent?.let { cropped ->
                val path = copyAvatarToInternal(ctx, cropped, initial.id)
                if (path != null) { avatarUri = path; avatarType = "image" }
            }
        }
    }
    // 图片选择 → 裁剪
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            cropLauncher.launch(
                CropImageContractOptions(
                    uri,
                    CropImageOptions(guidelines = CropImageView.Guidelines.ON, cropShape = CropImageView.CropShape.OVAL),
                )
            )
        }
    }

    val incubating by vm.incubating.collectAsState()
    val result by vm.incubateResult.collectAsState()

    LaunchedEffect(result) {
        if (result is IncubateResult.Success) {
            val s = result as IncubateResult.Success
            roleSetting = s.roleSetting
            opening = s.opening
            chatSetting = s.chatSetting
            voiceSetting = s.voiceSetting
            vm.clearIncubateResult()
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text(if (isNew) "灵魂注入" else "灵魂编辑") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.Filled.ArrowBack, null) }
                    },
                    actions = {
                        if (!isNew) {
                            IconButton(onClick = { vm.delete(initial.id); onDismiss() }) {
                                Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    },
                )
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // ── 头像区（仅图片上传 + 裁剪，无表情头像） ──
                    Text("头像", style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        AvatarContent(avatarUri, name, 64)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            if (avatarUri.isNotBlank()) {
                                Text("图片头像", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                OutlinedButton(onClick = { imagePicker.launch("image/*") }, modifier = Modifier.padding(top = 4.dp)) {
                                    Icon(Icons.Filled.Edit, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("更换 / 裁剪图片")
                                }
                            } else {
                                Text("未设置头像（将显示名称首字母）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                OutlinedButton(onClick = { imagePicker.launch("image/*") }, modifier = Modifier.padding(top = 4.dp)) {
                                    Icon(Icons.Filled.Add, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("上传图片")
                                }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                    }

                    HorizontalDivider(Modifier.padding(vertical = 4.dp))

                    OutlinedTextField(name, { name = it }, label = { Text("灵魂卡名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(description, { description = it }, label = { Text("描述") }, modifier = Modifier.fillMaxWidth(), minLines = 2)

                    OutlinedTextField(roleSetting, { roleSetting = it }, label = { Text("角色设定（系统提示词核心）") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                    OutlinedTextField(opening, { opening = it }, label = { Text("开场白") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                    OutlinedTextField(chatSetting, { chatSetting = it }, label = { Text("聊天设定") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                    OutlinedTextField(voiceSetting, { voiceSetting = it }, label = { Text("语音设定（自然语言，可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = { vm.incubate(name, description, tagRepo.resolve(selectedTags)) },
                            enabled = !incubating && (name.isNotBlank() || description.isNotBlank() || selectedTags.isNotEmpty()),
                        ) {
                            if (incubating) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(if (incubating) "孵化中…" else "AI 孵化")
                        }
                        if (result is IncubateResult.Error) {
                            Text((result as IncubateResult.Error).message, color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
                        }
                        if (result is IncubateResult.Success) {
                            Text("已回填 ✓", color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
                        }
                    }

                    HorizontalDivider(Modifier.padding(vertical = 4.dp))

                    // ── 标签：从全局标签池选择 / 进入标签管理 ──
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("标签", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { showTagManager = true }) {
                            Icon(Icons.Filled.Memory, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("管理标签")
                        }
                    }

                    // 已选标签（按名称展示，可移除）
                    if (selectedTags.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            selectedTags.forEach { tagName ->
                                AssistChip(
                                    onClick = { selectedTags = selectedTags - tagName },
                                    label = { Text(tagName) },
                                    trailingIcon = { Icon(Icons.Filled.Clear, null, Modifier.size(14.dp)) },
                                )
                            }
                        }
                    } else {
                        Text("还没有标签，点「管理标签」新建并选择。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = {
                        val now = System.currentTimeMillis()
                        vm.upsert(
                            initial.copy(
                                name = name.trim(),
                                avatarEmoji = "",
                                avatarUri = avatarUri,
                                avatarType = if (avatarUri.isNotBlank()) "image" else "emoji",
                                description = description.trim(),
                                roleSetting = roleSetting.trim(),
                                opening = opening.trim(),
                                chatSetting = chatSetting.trim(),
                                voiceSetting = voiceSetting.trim(),
                                tags = selectedTags,
                                createdAt = if (isNew) now else initial.createdAt,
                                updatedAt = now,
                            ),
                        )
                        onDismiss()
                    }) {
                        Text("保存灵魂卡")
                    }
                }
            }
        }
    }

    if (showTagManager) {
        TagManageScreen(
            onBack = { showTagManager = false },
            selectedNames = selectedTags,
            onToggleSelect = { name ->
                selectedTags = if (selectedTags.contains(name)) selectedTags - name else selectedTags + name
            },
        )
    }
}

// ==================== 全局标签管理界面（独立屏幕） ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagManageScreen(
    onBack: () -> Unit,
    selectedNames: List<String> = emptyList(),
    onToggleSelect: (String) -> Unit = {},
) {
    val ctx = LocalContext.current
    val repo = remember { QuroTagRepository(ctx.applicationContext) }
    var tags by remember { mutableStateOf(repo.loadAll()) }
    var showCreate by remember { mutableStateOf(false) }

    fun refresh() { tags = repo.loadAll() }

    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text("标签管理") },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null) } },
                    actions = {
                        IconButton(onClick = { showCreate = true }) {
                            Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                )
                LazyColumn(
                    Modifier.weight(1f).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(tags, key = { it.name }) { tag ->
                        TagManageCard(
                            tag = tag,
                            selected = selectedNames.contains(tag.name),
                            onToggleSelect = onToggleSelect,
                            onChanged = { repo.upsert(it); refresh() },
                            onDelete = { repo.delete(tag.name); refresh() },
                        )
                    }
                }
                if (tags.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("还没有标签，点右上角 + 新建。", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    if (showCreate) {
        TagEditDialog(
            initial = null,
            onSave = { repo.upsert(it); refresh(); showCreate = false },
            onDismiss = { showCreate = false },
        )
    }
}

/** 单个标签管理卡片：点击「设置」按钮展开编辑（含 JSON 字段）。 */
@Composable
private fun TagManageCard(
    tag: QuroTag,
    selected: Boolean = false,
    onToggleSelect: (String) -> Unit = {},
    onChanged: (QuroTag) -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(
                    onClick = { expanded = !expanded },
                    label = { Text(tag.name) },
                    trailingIcon = { Icon(if (expanded) Icons.Default.ArrowDropDown else Icons.Default.ArrowRight, null, Modifier.size(16.dp)) },
                )
                if (tag.description.isNotBlank()) {
                    Text(tag.description, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp).weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    Spacer(Modifier.weight(1f))
                }
                IconButton(onClick = { onToggleSelect(tag.name) }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        if (selected) Icons.Filled.Check else Icons.Filled.Add,
                        if (selected) "已选" else "选择",
                        Modifier.size(16.dp),
                        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Delete, "删除标签", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                }
            }
            if (expanded) {
                TagEditFields(tag = tag, onSave = onChanged)
            }
        }
    }
}

/** 标签编辑字段（名称 / 描述 / 提示内容 / JSON），用于卡片展开与新建对话框共用。 */
@Composable
private fun TagEditFields(
    tag: QuroTag,
    onSave: (QuroTag) -> Unit,
) {
    var name by remember(tag) { mutableStateOf(tag.name) }
    var desc by remember(tag) { mutableStateOf(tag.description) }
    var hint by remember(tag) { mutableStateOf(tag.hint) }
    var json by remember(tag) { mutableStateOf(tag.json) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(desc, { desc = it }, label = { Text("描述") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(hint, { hint = it }, label = { Text("AI 提示内容") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(json, { json = it }, label = { Text("JSON 配置（可选，自定义结构化参数）") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        TextButton(onClick = {
            val n = name.trim()
            if (n.isNotEmpty()) onSave(QuroTag(n, desc.trim(), hint.trim(), json.trim()))
        }, enabled = name.isNotBlank()) { Text("保存") }
    }
}

/** 新建标签对话框。 */
@Composable
private fun TagEditDialog(
    initial: QuroTag?,
    onSave: (QuroTag) -> Unit,
    onDismiss: () -> Unit,
) {
    val base = initial ?: QuroTag("", "", "", "")
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.widthIn(max = 420.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text(if (initial == null) "新建标签" else "编辑标签", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                TagEditFields(tag = base, onSave = onSave)
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("取消") }
            }
        }
    }
}

// ==================== 记忆库 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryDialog(
    personaVm: QuroPersonaViewModel,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val repo = remember { QuroMemoryRepository(ctx) }
    val active by personaVm.activePersona.collectAsState()
    val defaultPersonaId = active?.id ?: ""

    var entries by remember { mutableStateOf(repo.loadAll()) }
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<QuroMemoryEntry?>(null) }
    var showAdd by remember { mutableStateOf(false) }

    val shown = if (query.isBlank()) entries else repo.search(query)

    // 导出为 JSON 文件（SAF）
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let {
            runCatching { ctx.contentResolver.openOutputStream(it)?.use { os -> os.write(repo.exportJson().toByteArray()) } }
        }
    }
    // 从 JSON 文件导入（SAF）
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val text = runCatching { ctx.contentResolver.openInputStream(it)?.use { s -> s.bufferedReader().readText() } }.getOrNull() ?: ""
            if (text.isNotBlank()) {
                val imported = repo.parseJson(text)
                if (imported.isNotEmpty()) { repo.mergeImport(imported); entries = repo.loadAll() }
            }
        }
    }

    fun reload() { entries = repo.loadAll() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text("记忆库") },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Filled.ArrowBack, null) } },
                    actions = {
                        IconButton(onClick = { exportLauncher.launch("quro_memory_export.json") }) { Icon(Icons.Filled.FileDownload, "导出") }
                        IconButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) }) { Icon(Icons.Filled.FileUpload, "导入") }
                        IconButton(onClick = { showAdd = true }) { Icon(Icons.Filled.Add, "添加") }
                    },
                )
                OutlinedTextField(
                    query, { query = it },
                    placeholder = { Text("搜索内容 / 标题 / 标签 / 分组") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    if (shown.isEmpty()) {
                        item { Text("暂无记忆。点右上角 + 添加，或从文件导入一份备份。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    items(shown) { e ->
                        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { editing = e }) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    if (e.title.isNotBlank()) {
                                        Text(e.title, style = MaterialTheme.typography.bodyMedium)
                                    }
                                    Text(e.content, style = MaterialTheme.typography.bodySmall)
                                    val meta = buildList {
                                        if (e.group.isNotBlank()) add("分组:" + e.group)
                                        if (e.tags.isNotEmpty()) add(e.tags.joinToString(","))
                                    }
                                    if (meta.isNotEmpty()) {
                                        Text(meta.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                IconButton(onClick = { repo.delete(e.id); reload() }) {
                                    Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        MemoryEditDialog(
            initial = QuroMemoryEntry(personaId = defaultPersonaId),
            onDismiss = { showAdd = false },
            onConfirm = { entry -> repo.add(entry); reload(); showAdd = false },
        )
    }
    editing?.let { e ->
        MemoryEditDialog(
            initial = e,
            onDismiss = { editing = null },
            onConfirm = { entry -> repo.update(entry); reload(); editing = null },
        )
    }
}

/** 记忆条目编辑（新增 / 修改通用）：标题、内容、分组、标签。 */
@Composable
private fun MemoryEditDialog(
    initial: QuroMemoryEntry,
    onDismiss: () -> Unit,
    onConfirm: (QuroMemoryEntry) -> Unit,
) {
    var title by remember { mutableStateOf(initial.title) }
    var content by remember { mutableStateOf(initial.content) }
    var group by remember { mutableStateOf(initial.group) }
    var tagInput by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf(initial.tags) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(enabled = content.isNotBlank(), onClick = {
                onConfirm(
                    initial.copy(
                        title = title.trim(),
                        content = content.trim(),
                        group = group.trim(),
                        tags = tags,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text("记忆详情") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("标题（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(content, { content = it }, label = { Text("内容") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(group, { group = it }, label = { Text("分组（可选，如 偏好 / 工作）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (tags.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        tags.forEach { tag ->
                            AssistChip(
                                onClick = { },
                                label = { Text(tag) },
                                trailingIcon = {
                                    IconButton(onClick = { tags = tags - tag }, modifier = Modifier.size(18.dp)) {
                                        Icon(Icons.Filled.Clear, null, Modifier.size(14.dp))
                                    }
                                },
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(tagInput, { tagInput = it }, label = { Text("标签（可选）") }, singleLine = true, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        val t = tagInput.trim()
                        if (t.isNotBlank() && !tags.contains(t)) { tags = tags + t; tagInput = "" }
                    }) { Text("加标签") }
                }
            }
        },
    )
}

