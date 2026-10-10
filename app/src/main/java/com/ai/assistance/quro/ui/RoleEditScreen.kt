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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject
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
    // #215：角色类型标签（可添加、带内容，参考灵魂注入标签）
    var tags by remember { mutableStateOf(role.tags.joinToString("、")) }
    // #190：绑定的真技能（id）。与上面的「技能标签」不同 —— 这里选的是技能库里的真技能，
    // 勾上之后技能正文会真正注入该角色的系统提示词。
    var skillIds by remember { mutableStateOf(role.skillIds) }
    var skillPickerOpen by remember { mutableStateOf(false) }
    // 技能库懒加载：只有点开选择器才读，避免每次进编辑器都读一遍技能库。
    // 🔴 #200：这里必须读**集群**技能库（ClusterSkillStore），不是全局 QuroSkillStore。
    // 集群角色绑定的是集群技能 id，运行时注入也只认集群库 ——
    // 这里给全局库的 id，用户绑完了运行时照样找不到技能，等于白绑。
    val library by produceState<List<com.ai.assistance.quro.core.cluster.ClusterSkillStore.ClusterSkill>>(
        emptyList(), skillPickerOpen
    ) {
        if (skillPickerOpen) {
            value = runCatching {
                com.ai.assistance.quro.core.cluster.ClusterSkillStore.load(ctx)
            }.getOrElse { emptyList() }
        }
    }
    var kind by remember { mutableStateOf(role.role) }
    var modelId by remember { mutableStateOf(role.modelProfileId) }
    var historyRounds by remember { mutableStateOf(role.context.historyRounds.toString()) }
    var temperature by remember { mutableStateOf(role.context.temperature.toString()) }
    // 🔴 #209：不再有「最大输出 token」这一层。
    // 模型配置（QuroModelConfig.maxTokens）本来就有这个值，角色级再配一次
    // 只会把它**覆盖成更小的值**（角色默认 4096、UI 还 coerceIn 到 32768，
    // 而模型配置是 65536）—— skills 需要大量上下文与输出，这层必须去掉。
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
        tags != role.tags.joinToString("、") ||
        skillIds != role.skillIds ||
        modelId != role.modelProfileId ||
        (if (isHost) RoleKind.HOST else kind) != role.role ||
        historyRounds.toIntOrNull() != role.context.historyRounds ||
        temperature.toFloatOrNull() != role.context.temperature ||
        seeOthers != role.context.seeOtherRoles

    fun save() {
        val ctxPolicy = role.context.copy(
            historyRounds = historyRounds.toIntOrNull()?.coerceIn(0, 64)
                ?: role.context.historyRounds,
            temperature = temperature.toFloatOrNull()?.coerceIn(0f, 2f)
                ?: role.context.temperature,
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
                tags = split(tags),
                skillIds = skillIds,
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
                        Box(Modifier.fillMaxSize()) {
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
                            // #190：分工扩到四档（原来只有专家/评审，缺「谁执行」与「谁规划」）
                            RoleKind.selectable.forEach { k ->
                                FilterChip(
                                    selected = kind == k,
                                    onClick = { kind = k },
                                    label = { Text(k.label) },
                                )
                            }
                        }
                        Text(
                            when (kind) {
                                RoleKind.CRITIC -> "评审：专职挑毛病、验收产出。系统不会给它改文件/操作设备的工具，" +
                                    "它只能读和判 —— 这样验收才不会被它自己改掉。"
                                RoleKind.PLANNER -> "规划：拆解目标、定验收标准、给方案，不动手产出。"
                                RoleKind.EXECUTOR -> "执行：真正动手的活。会被下发文件、终端、网络、设备操作等工具，" +
                                    "可以调工具真的把事做完。"
                                else -> "专家：按职责产出具体内容，可查证资料。"
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
                label = { Text("技能标签（自由描述）") },
                placeholder = { Text("例：数据分析、检索、写作") },
                supportingText = { Text("只是几句描述，不产生约束力。真正的手艺请在下方勾选技能库。") },
                modifier = Modifier.fillMaxWidth(),
            )
            // #215：角色类型标签 —— 参考灵魂注入的标签功能，可添加、带内容。
            // 不限于 RoleKind 那 5 个固定枚举，用户可自由定义「这个角色是什么类型」。
            OutlinedTextField(
                value = tags,
                onValueChange = { tags = it },
                label = { Text("角色类型标签（可自定义）") },
                placeholder = { Text("例：前端、视觉、文案、验收、数据") },
                supportingText = { Text("顿号/逗号分隔。会作为身份标记注入系统提示词，角色据此自我定位。") },
                modifier = Modifier.fillMaxWidth(),
            )

            // ══ 真技能勾选器：角色能力的真正来源 ══
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("绑定技能（技能库）", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (skillIds.isEmpty())
                                    "未绑定 —— 这个角色只能靠通用知识凭空发挥"
                                else
                                    "已绑定 ${skillIds.size} 个：" +
                                        skillIds.mapNotNull { id ->
                                            library.firstOrNull { it.id == id }?.name
                                        }.joinToString("、").ifBlank { skillIds.joinToString("、") },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (skillIds.isEmpty()) cs.error else cs.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { skillPickerOpen = !skillPickerOpen }) {
                            Text(if (skillPickerOpen) "收起" else "选择技能")
                        }
                    }

                    if (skillPickerOpen) {
                        if (library.isEmpty()) {
                            Text(
                                "集群技能库为空或正在加载…若持续为空，请到「集群 → 技能」页确认内置技能已播种。",
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        } else {
                            // 按分工推荐：先给该分工最该有的手艺，再折叠其余全部
                            val recommended = remember(kind, library) {
                                com.ai.assistance.quro.core.cluster.ClusterSkillMarket
                                    .recommendFor(ctx, kind.name.lowercase(), library)
                            }
                            val recSkills = library.filter { it.id in recommended }
                            val otherSkills = library.filter { it.id !in recommended }
                            Column(
                                Modifier.fillMaxWidth().heightIn(max = 420.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(top = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                if (recSkills.isNotEmpty()) {
                                    Text(
                                        "适合「${kind.label}」的技能（${recSkills.size}）",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = cs.primary,
                                    )
                                    recSkills.forEach { sk ->
                                        SkillRow(sk, skillIds.contains(sk.id)) { checked ->
                                            skillIds = if (checked) {
                                                (skillIds + sk.id).distinct()
                                            } else {
                                                skillIds - sk.id
                                            }
                                        }
                                    }
                                }
                                if (otherSkills.isNotEmpty()) {
                                    Text(
                                        "其他技能（${otherSkills.size}）",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = cs.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 8.dp),
                                    )
                                    otherSkills.forEach { sk ->
                                        SkillRow(sk, skillIds.contains(sk.id)) { checked ->
                                            skillIds = if (checked) {
                                                (skillIds + sk.id).distinct()
                                            } else {
                                                skillIds - sk.id
                                            }
                                        }
                                    }
                                }
                                // #191：本机没有的手艺，去开源社区现拉 —— 不要让用户卡在「库里没有」
                                OpenSkillInstallRow(
                                    onInstalled = { newIds, newNames ->
                                        skillIds = (skillIds + newIds).distinct()
                                    },
                                )
                            }
                        }
                    }
                }
            }

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
                    // 🔴 #209：这里原本有个「最大输出 token」输入框。
                    // 它把模型配置的 65536 覆盖成 <=32768 —— 同一件事配两遍，
                    // 且第二遍只会更小。token 上限现在只由「模型配置」决定。
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
/**
 * 技能库里的一行（勾选框 + 名称 + 说明 + 可激活标记）。
 *
 * 整个行可点：勾选框只有 20dp，单独点很难点中 —— 与 ModelRow 同理，
 * 把透明 Button 铺满整行使整行都可点。
 */
@Composable
private fun SkillRow(
    skill: com.ai.assistance.quro.core.cluster.ClusterSkillStore.ClusterSkill,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Column(Modifier.weight(1f).padding(start = 4.dp, top = 2.dp, bottom = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    skill.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (checked) androidx.compose.ui.text.font.FontWeight.Bold
                    else androidx.compose.ui.text.font.FontWeight.Normal,
                )
                // 🔴 #200：集群技能没有「可激活」也没有 suite（
                // 它只是一段注入系统提示词的规程）。改显示它能覆盖的能力词，
                // 这样用户一眼就知道它能干什么（也是主持拆能力标签时的匹配依据）。
                if (skill.abilityWords.isNotBlank()) {
                    Text(
                        "  能力：" + skill.abilityWords.split(" ").take(6).joinToString("、"),
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.primary,
                    )
                }
            }
            if (skill.description.isNotBlank()) {
                Text(
                    skill.description.take(90),
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
            }
        }
        // 整行可点：透明按钮铺满（勾选框太小，中间隔一大片空白点了没反应）
        Box(Modifier.fillMaxSize()) {
            Button(
                onClick = { onCheckedChange(!checked) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                    contentColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
                elevation = null,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                modifier = Modifier.fillMaxSize().alpha(0f),
            ) {}
        }
    }
}

/**
 * ★ 去开源社区装技能 ★（#191）
 *
 * 为什么需要它：本机技能库只有随包那 67 个。用户要的手艺很可能不在里面 ——
 * 这时正确的做法不是「那就没有了」，而是**去开源社区现拉**。
 *
 * 交互：折叠 → 输关键词 →「搜索」→ 列出命中技能（标注来源与是否已装）→ 点「装」→
 * 装完直接勾进本角色的技能列表（省掉用户再去上面勾一遍）。
 *
 * 🔴 诚实性：网络失败 / 源拉不到时**如实显示失败原因**，不显示成「没找到技能」。
 */
@Composable
private fun OpenSkillInstallRow(onInstalled: (ids: List<String>, names: List<String>) -> Unit) {
    val ctx = LocalContext.current.applicationContext
    val cs = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errMsg by remember { mutableStateOf("") }
    var hits by remember {
        mutableStateOf<List<com.ai.assistance.quro.core.cluster.ClusterOpenSkillHub.RemoteEntry>>(emptyList())
    }
    var sourceFails by remember { mutableStateOf<List<String>>(emptyList()) }
    var justInstalled by remember { mutableStateOf<List<String>>(emptyList()) }

    Column(Modifier.padding(top = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("去开源社区找技能", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (hits.isEmpty()) "本机没有的手艺，从开源仓库下载安装"
                    else "搜到 ${hits.size} 个",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
            }
            TextButton(onClick = { open = !open }) {
                Text(if (open) "收起" else "搜索开源技能")
            }
        }

        if (open) {
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("技能关键词") },
                    placeholder = { Text("如 frontend / docx / brainstorm") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        busy = true
                        errMsg = ""; justInstalled = emptyList(); hits = emptyList(); sourceFails = emptyList()
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                            val out = runCatching {
                                org.json.JSONObject(
                                    com.ai.assistance.quro.core.cluster.ClusterOpenSkillSearchTool()
                                        .run(ctx, searchArgs(query))
                                )
                            }
                            busy = false
                            out.onSuccess { j ->
                                if (j.optBoolean("ok")) {
                                    val arr = j.optJSONArray("skills")
                                    hits = (0 until (arr?.length() ?: 0)).map { i ->
                                        val o = arr!!.optJSONObject(i)
                                        com.ai.assistance.quro.core.cluster.ClusterOpenSkillHub.RemoteEntry(
                                            sourceId = o?.optString("sourceId").orEmpty(),
                                            dir = o?.optString("dir").orEmpty(),
                                            name = o?.optString("name").orEmpty(),
                                            description = o?.optString("description").orEmpty(),
                                        )
                                    }
                                    val fa = j.optJSONArray("sourceFailures")
                                    sourceFails = (0 until (fa?.length() ?: 0)).map { fa!!.optString(it) }
                                    // 全部源都失败 ≠ 社区没这技能，必须把原因摊开
                                    if (hits.isEmpty() && sourceFails.isNotEmpty()) {
                                        errMsg = "没能从开源社区拉到内容：" + sourceFails.joinToString("；")
                                    }
                                } else {
                                    errMsg = j.optString("error")
                                }
                            }.onFailure {
                                errMsg = "搜索失败：${it.message ?: it.javaClass.simpleName}"
                            }
                        }
                    },
                    enabled = !busy,
                ) { Text(if (busy) "搜索中" else "搜索") }
            }

            if (errMsg.isNotBlank()) {
                Text(
                    errMsg,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (justInstalled.isNotEmpty()) {
                Text(
                    "已装并勾选：" + justInstalled.joinToString("、"),
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.primary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            if (hits.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    com.ai.assistance.quro.core.cluster.ClusterOpenSkillHub.SOURCES.forEach { src ->
                        val inSrc = hits.filter { it.sourceId == src.id }
                        if (inSrc.isEmpty()) return@forEach
                        Text(
                            "${src.label}（${inSrc.size}）",
                            style = MaterialTheme.typography.labelMedium,
                            color = cs.primary,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        inSrc.forEach { e ->
                            OpenSkillRow(
                                sourceLabel = src.label,
                                dir = e.dir,
                                installed = com.ai.assistance.quro.core.cluster.ClusterOpenSkillHub
                                    .isInstalled(ctx, e),
                                busy = busy,
                                onInstall = {
                                    busy = true; errMsg = ""
                                    kotlinx.coroutines.CoroutineScope(
                                        kotlinx.coroutines.Dispatchers.IO
                                    ).launch {
                                        val out = runCatching {
                                            org.json.JSONObject(
                                                com.ai.assistance.quro.core.cluster.ClusterOpenSkillInstallTool()
                                                    .run(ctx, installArgs(e.key))
                                            )
                                        }
                                        busy = false
                                        out.onSuccess { j ->
                                            if (j.optBoolean("ok")) {
                                                val nm = j.optString("name")
                                                justInstalled = justInstalled + nm
                                                // 直接勾进本角色：装它就是为了用它
                                                onInstalled(listOf(j.optString("id")), listOf(nm))
                                            } else {
                                                errMsg = j.optString("error")
                                            }
                                        }.onFailure {
                                            errMsg = "安装失败：" + (it.message ?: it.javaClass.simpleName)
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 组装 cluster_skill_search 的参数 JSON（用 JSONObject 拼，别手拼字符串）。 */
private fun searchArgs(q: String): String =
    org.json.JSONObject().put("query", q.trim()).toString()

/** 组装 cluster_skill_install 的参数 JSON。 */
private fun installArgs(key: String): String =
    org.json.JSONObject().put("key", key).toString()

/** 开源技能列表里的一行：来源标签 + 目录名 + 安装按钮。 */
@Composable
private fun OpenSkillRow(
    sourceLabel: String,
    dir: String,
    installed: Boolean,
    busy: Boolean,
    onInstall: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 6.dp)) {
            Text(dir, style = MaterialTheme.typography.bodyMedium)
            Text(
                sourceLabel,
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
            )
        }
        if (installed) {
            Text("已装", style = MaterialTheme.typography.labelMedium, color = cs.primary)
        } else {
            OutlinedButton(onClick = onInstall, enabled = !busy) { Text("装") }
        }
    }
}