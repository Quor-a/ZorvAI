package com.ai.assistance.quro.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ai.assistance.quro.core.QuroPersonaRepository
import com.ai.assistance.quro.core.cluster.ModelProfile
import com.ai.assistance.quro.core.cluster.RoleContextPolicy
import com.ai.assistance.quro.core.cluster.RoleKind
import com.ai.assistance.quro.core.cluster.RoleProfile
import com.ai.assistance.quro.core.cluster.RoleRegistry

/**
 * 🔴 角色编辑 —— 全屏页（原先是 AlertDialog，真机被裁切且点不到）。
 *
 * ## 为什么从 Dialog 改成全屏页
 *
 * 用户实机截图暴露四个问题，根因都是「把一整个表单塞进 AlertDialog」：
 * 1. **内容被裁**：`verticalScroll + heightIn(max=460.dp)` 在 AlertDialog 的
 *    内边距里再被压一次，底部「可见其他角色」那一行只露出半个 Switch。
 * 2. **组件重叠**：DropdownMenu 挂在 `Box` 里，弹层宽度按内容自适应，
 *    和满宽按钮不对齐；再叠加 OutlinedButton 的边框，看着像输入框被压住。
 * 3. **点不到**：被裁掉的部分落在对话框外的触摸拦截区外，看着在屏幕上、实际不响应。
 * 4. **没有滚动条**：460dp 里塞了 9 个控件，滑到哪完全没概念。
 *
 * 本仓既有范式是**全屏设置页**（见 `QuroClusterSettingsScreen` /
 * `QuroAidlAciCenterScreen`：`Scaffold + TopAppBar + actions + verticalScroll`），
 * 这里直接对齐—— TopAppBar 右侧放保存，表单整页可滚，底部按钮不再被裁。
 *
 * ## 为什么把「人格卡」做成可折叠而不是删掉
 *
 * 角色的深度 = 人格卡（角色设定/描述）× 集群职责/禁忌/技能。
 * 只在设置页配职责、却不管人格卡 → 角色照样单薄。所以这里保留只读预览 +
 * 缺内容时的醒目提示，但**默认折叠**，避免一进来就是一大段灰字占满屏。
 *
 * 真正的编辑入口在「设置 → 人格」（那里已是完整编辑器），不重复造。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoleEditScreen(
    role: RoleProfile,
    models: List<ModelProfile>,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val ctx = LocalContext.current.applicationContext
    val cs = MaterialTheme.colorScheme
    val isHost = RoleRegistry.isHost(role.personaId)
    val persona = remember(role.personaId) {
        runCatching { QuroPersonaRepository(ctx).get(role.personaId) }.getOrNull()
    }

    var duties by remember { mutableStateOf(role.duties.joinToString("、")) }
    var taboos by remember { mutableStateOf(role.taboos.joinToString("、")) }
    var skills by remember { mutableStateOf(role.skills.joinToString("、")) }
    var kind by remember { mutableStateOf(role.role) }
    var modelId by remember { mutableStateOf(role.modelProfileId) }
    var historyRounds by remember { mutableStateOf(role.context.historyRounds.toString()) }
    var temperature by remember { mutableStateOf(role.context.temperature.toString()) }
    var maxTokens by remember { mutableStateOf(role.context.maxTokens.toString()) }
    var seeOthers by remember { mutableStateOf(role.context.seeOtherRoles) }
    var modelOpen by remember { mutableStateOf(false) }
    var personaOpen by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }

    fun split(s: String) = s.split('、', ',', '，', '\n')
        .map { it.trim() }.filter { it.isNotBlank() }

    // 脏检查：没改就不该拦用户返回
    val dirty = duties != role.duties.joinToString("、") ||
        taboos != role.taboos.joinToString("、") ||
        skills != role.skills.joinToString("、") ||
        modelId != role.modelProfileId ||
        (if (isHost) RoleKind.HOST else kind) != role.role ||
        historyRounds.toIntOrNull() != role.context.historyRounds ||
        temperature.toFloatOrNull() != role.context.temperature ||
        maxTokens.toIntOrNull() != role.context.maxTokens ||
        seeOthers != role.context.seeOtherRoles

    fun save() {
        val ctxPolicy = role.context.copy(
            historyRounds = historyRounds.toIntOrNull()?.coerceIn(0, 64)
                ?: role.context.historyRounds,
            temperature = temperature.toFloatOrNull()?.coerceIn(0f, 2f)
                ?: role.context.temperature,
            maxTokens = maxTokens.toIntOrNull()?.coerceIn(256, 32768)
                ?: role.context.maxTokens,
            seeOtherRoles = seeOthers,
        )
        RoleRegistry.upsert(
            ctx,
            role.copy(
                modelProfileId = modelId,
                role = if (isHost) RoleKind.HOST else kind,
                duties = split(duties),
                taboos = split(taboos),
                skills = split(skills),
                context = ctxPolicy,
                version = role.version + 1,
            ),
        )
        onSaved()
    }

    fun requestBack() {
        if (dirty) confirmExit = true else onBack()
    }

    Scaffold(
        containerColor = cs.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            if (isHost) "编辑主持" else "编辑角色",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            persona?.name ?: role.personaId,
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { requestBack() }) {
                        Icon(Icons.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { save() }) {
                        Icon(Icons.Filled.Check, "保存", tint = cs.primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background),
            )
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                // 底部留白：保证最后一个控件能被完整滚到、不贴系统手势条
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {

            // ══ 分组 1：身份卡 ══
            SectionLabel("身份")
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant.copy(alpha = 0.35f)),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val thin = persona == null ||
                        persona.roleSetting.isBlank() || persona.description.isBlank()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "人格卡",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        if (thin) {
                            Icon(
                                Icons.Filled.Warning,
                                "缺少角色设定",
                                tint = cs.error,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        TextButton(onClick = { personaOpen = !personaOpen }) {
                            Text(if (personaOpen) "收起" else "查看")
                        }
                    }

                    if (thin) {
                        Text(
                            "这张卡的「角色设定 / 描述」是空的。角色的深度主要由它决定——" +
                                "只填下面的职责，出来的仍然是个浅角色。" +
                                "请先去「设置 → 人格」补上，再回来。",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.error,
                        )
                    } else {
                        Text(
                            "来自「设置 → 人格」的角色设定与描述。改它请去人格页。",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                        )
                    }

                    if (personaOpen && persona != null) {
                        HorizontalDivider()
                        LabelledText("角色设定", persona.roleSetting.ifBlank { "（空）" })
                        LabelledText("描述", persona.description.ifBlank { "（空）" })
                        LabelledText("表达约束", persona.chatSetting.ifBlank { "（空）" })
                    }
                }
            }

            // ══ 分组 2：模型与类型 ══
            SectionLabel("模型与类型")

            // 模型：用整行可点的行而不是按钮，避免「像输入框」的错视 + 弹层对不齐
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Column {
                    Box(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("绑定模型", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                models.firstOrNull { it.id == modelId }?.displayName
                                    ?: "未绑定（运行时兜底）",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = cs.primary,
                                maxLines = 1,
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                                textAlign = TextAlign.End,
                            )
                        }
                        // 透明按钮铺满整行：点击区域大，且不引入按钮边框造成的错视
                        Box(Modifier.matchParentSize()) {
                            OutlinedButton(
                                onClick = { modelOpen = true },
                                Modifier.fillMaxSize().alpha(0f),
                                content = {},
                            )
                        }
                        DropdownMenu(expanded = modelOpen, onDismissRequest = { modelOpen = false }) {
                            if (models.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("没有可用模型，请先在设置里配置") },
                                    onClick = { modelOpen = false },
                                )
                            }
                            models.forEach { m ->
                                DropdownMenuItem(
                                    text = {
                                        Text(m.displayName + if (m.available) "" else "（不可用）")
                                    },
                                    onClick = { modelOpen = false; modelId = m.id },
                                )
                            }
                        }
                    }
                    Text(
                        "不同角色的模型可以绑不同厂商，各自走自己的通道。",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
                    )
                }
            }

            if (!isHost) {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("角色类型", style = MaterialTheme.typography.bodyMedium)
                        Row(
                            Modifier.fillMaxWidth().padding(top = 8.dp).selectableGroup(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            listOf(RoleKind.EXPERT to "专家", RoleKind.CRITIC to "评审").forEach { (k, lbl) ->
                                FilterChip(
                                    selected = kind == k,
                                    onClick = { kind = k },
                                    label = { Text(lbl) },
                                )
                            }
                        }
                        Text(
                            when (kind) {
                                RoleKind.CRITIC -> "评审：专职挑毛病、验收产出，不负责产出。"
                                else -> "专家：按职责产出具体成果。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }

            // ══ 分组 3：职责（决定角色深度的主战场）══
            SectionLabel("职责与约束")
            OutlinedTextField(
                value = duties,
                onValueChange = { duties = it },
                label = { Text("职责") },
                placeholder = { Text("例：检索行业数据、比对竞品参数") },
                supportingText = { Text("会原样进该角色的系统提示词；填得越具体，角色越像专家") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = taboos,
                onValueChange = { taboos = it },
                label = { Text("禁忌") },
                placeholder = { Text("例：不许编造数据、不许替主持拍板") },
                supportingText = { Text("违反即失败，用于约束角色边界") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = skills,
                onValueChange = { skills = it },
                label = { Text("技能标签") },
                placeholder = { Text("例：数据分析、检索、写作") },
                modifier = Modifier.fillMaxWidth(),
            )

            // ══ 分组 4：推理参数 ══
            SectionLabel("推理参数")
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Column(
                    Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("可见其他角色的发言", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (seeOthers) "开：能看到别人说了什么，容易从众"
                                else "关：只看任务和主持指令，独立判断",
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant,
                            )
                        }
                        Switch(checked = seeOthers, onCheckedChange = { seeOthers = it })
                    }
                    HorizontalDivider()
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = historyRounds,
                            onValueChange = { historyRounds = it.filter(Char::isDigit) },
                            label = { Text("历史轮次") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = temperature,
                            onValueChange = { temperature = it },
                            label = { Text("温度") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    OutlinedTextField(
                        value = maxTokens,
                        onValueChange = { maxTokens = it.filter(Char::isDigit) },
                        label = { Text("最大输出 token") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // ══ 底部主按钮（TopAppBar 已有保存，这里给个大目标）══
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(onClick = { requestBack() }, Modifier.weight(1f)) {
                    Text("取消")
                }
                Button(
                    onClick = { save() },
                    Modifier.weight(1f),
                ) { Text("保存") }
            }
        }
    }

    // —— 离开确认：只有真的改了才拦 ——
    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("放弃修改？") },
            text = { Text("有未保存的改动，离开后会丢失。") },
            confirmButton = {
                TextButton(onClick = { confirmExit = false; onBack() }) { Text("放弃") }
            },
            dismissButton = {
                TextButton(onClick = { confirmExit = false }) { Text("继续编辑") }
            },
        )
    }
}

/** 分组标题 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 2.dp, top = 4.dp),
    )
}

/** 「标签：值」两行只读展示（人格卡预览用） */
@Composable
private fun LabelledText(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}