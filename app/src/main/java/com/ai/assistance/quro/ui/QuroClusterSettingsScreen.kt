package com.ai.assistance.quro.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ai.assistance.quro.R
import com.ai.assistance.quro.core.QuroPersonaRepository
import com.ai.assistance.quro.core.cluster.ClusterBudget
import com.ai.assistance.quro.core.cluster.ClusterRuntime
import com.ai.assistance.quro.core.cluster.CloseReason
import com.ai.assistance.quro.core.cluster.ModelProfile
import com.ai.assistance.quro.core.cluster.ClusterRoleCard
import com.ai.assistance.quro.core.cluster.ClusterRoleCardTool
import com.ai.assistance.quro.core.cluster.ClusterRoleCards
import com.ai.assistance.quro.core.cluster.RoleKind
import com.ai.assistance.quro.core.cluster.RoleProfile
import com.ai.assistance.quro.core.cluster.RoleRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * 集群设置页。
 *
 * 为什么必须有这个页：集群重写成 tool-first 后，配置入口从旧版面板挪到了工具里，
 * 结果就是「用户想招人 / 想给角色换模型，却只能去对话框里用嘴说」——不可接受。
 * 这里把 [RoleRegistry] 的能力原样暴露成界面：
 *   招人（enroll 人格卡）/ 移除 / 启停 / 绑定模型 / 主持熔断参数 / 直接发起任务。
 *
 * 主持（persona_cluster_host）内置且不可删，界面上只开放它的模型与熔断配置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuroClusterSettingsScreen(onBack: () -> Unit = {}) {
    val ctx = LocalContext.current.applicationContext
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    // bump 用于在增删改后强制重读注册表（RoleRegistry 本身是文件存储，无观察者）
    var bump by remember { mutableIntStateOf(0) }
    var showEnroll by remember { mutableStateOf(false) }
    // #191 角色卡（= 一个 skills 聚合）选择器
    var showRoleCards by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<RoleProfile?>(null) }
    var editing by remember { mutableStateOf<RoleProfile?>(null) }

    val roles = remember(bump) {
        runCatching { RoleRegistry.ensureHost(ctx); RoleRegistry.roles(ctx) }
            .getOrDefault(emptyList())
    }
    val models = remember(bump) {
        runCatching { ClusterRuntime.models() }.getOrDefault(emptyList())
    }
    val host = roles.firstOrNull { RoleRegistry.isHost(it.personaId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.qk_03946)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.qk_00143))
                    }
                },
            )
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ——— 角色列表 ———
            Text(
                stringResource(R.string.qk_03957),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (roles.none { it.role != RoleKind.HOST }) {
                Text(
                    "还没有可用角色。集群只有主持无法干活，请先添加至少一名专家。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            roles.forEach { r ->
                RoleCard(
                    role = r,
                    models = models,
                    onEdit = { editing = r },
                    onToggleEnabled = { on ->
                        RoleRegistry.upsert(
                            ctx,
                            RoleRegistry.get(ctx, r.personaId)!!.copy(enabled = on),
                        )
                        bump++
                    },
                    onBindModel = { m ->
                        RoleRegistry.upsert(
                            ctx,
                            RoleRegistry.get(ctx, r.personaId)!!.copy(modelProfileId = m.id),
                        )
                        bump++
                        toast(ctx, "已绑定 ${m.displayName}")
                    },
                    onDelete = { pendingDelete = r },
                )
            }

            Row(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(onClick = { showEnroll = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("  添加角色")
                }
                // #191 角色卡 = 一个 skills 聚合，一键建角并自动绑好这组技能
                OutlinedButton(onClick = { showRoleCards = true }) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                    Text("  从角色卡创建")
                }
            }

            // ——— 主持熔断参数 ———
            if (host != null) {
                HostConfigSection(
                    models = models,
                    budget = ClusterRuntime.defaultCluster().budget,
                    onApply = { budget, modelId ->
                        scope.launch {
                            val cur = RoleRegistry.get(ctx, RoleRegistry.HOST_PERSONA_ID)
                            if (cur != null) {
                                RoleRegistry.upsert(
                                    ctx,
                                    cur.copy(
                                        modelProfileId = modelId,
                                        version = cur.version + 1,
                                    ),
                                )
                            }
                            val c = ClusterRuntime.defaultCluster()
                            ClusterRuntime.updateCluster(
                                c.copy(budget = budget)
                            )
                            bump++
                            toast(ctx, "主持配置已保存")
                        }
                    },
                )
            }

            // ——— 直接发起任务 ———
            StartTaskSection()

            Box(Modifier.padding(16.dp))
        }
    }

    // ——— 角色卡对话框（#191）——
    if (showRoleCards) {
        RoleCardDialog(
            ctx = ctx,
            models = models,
            onDismiss = { showRoleCards = false },
            onDone = { created ->
                showRoleCards = false
                bump++
                toast(ctx, created)
            },
        )
    }

    // ——— 招人对话框 ———
    if (showEnroll) {
        EnrollDialog(
            ctx = ctx,
            models = models,
            onDismiss = { showEnroll = false },
            onDone = {
                showEnroll = false
                bump++
                toast(ctx, "已加入集群")
            },
        )
    }

    // ——— 角色编辑（职责/禁忌/技能/推理参数）——
    // 🔴 角色编辑器是**全屏页**而非对话框：9 个控件塞进 AlertDialog 会被
    // 对话框内边距再压一次，底部控件被裁、看着在屏幕上却点不到（用户实机反馈）。
    if (editing != null) {
        BackHandler { editing = null }
        Box(Modifier.fillMaxSize().zIndex(101f).background(cs.background)) {
            RoleEditScreen(
                role = editing!!,
                models = models,
                onBack = { editing = null },
                onSaved = {
                    editing = null
                    bump++
                    toast(ctx, "角色已保存")
                },
            )
        }
    }

    // ——— 删除确认（主持不可删，理论上进不到这里）——
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("移出集群") },
            text = { Text("「${personaName(ctx, target.personaId)}」将不再被主持点名。人格卡本身不会被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    val id = target.personaId
                    scope.launch(Dispatchers.IO) {
                        runCatching { RoleRegistry.delete(ctx, id) }
                        withContext(Dispatchers.Main) {
                            pendingDelete = null
                            bump++
                        }
                    }
                }) { Text("移出") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun RoleCard(
    role: RoleProfile,
    models: List<ModelProfile>,
    onEdit: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onBindModel: (ModelProfile) -> Unit,
    onDelete: () -> Unit,
) {
    val ctx = LocalContext.current.applicationContext
    val isHost = RoleRegistry.isHost(role.personaId)
    var menuOpen by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        personaName(ctx, role.personaId),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        if (isHost) "主持（内置不可删除）" else role.role.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = "编辑角色")
                }
                if (!isHost) {
                    Switch(checked = role.enabled, onCheckedChange = onToggleEnabled)
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "移出集群",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            // 模型绑定：不同角色可以跑不同模型
            Box {
                OutlinedButton(onClick = { menuOpen = true }) {
                    Text(models.firstOrNull { it.id == role.modelProfileId }?.displayName ?: "未绑定（运行时兜底）")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    models.forEach { m ->
                        DropdownMenuItem(
                            text = { Text(m.displayName + if (m.available) "" else "（不可用）") },
                            onClick = {
                                menuOpen = false
                                onBindModel(m)
                            },
                        )
                    }
                }
            }

            if (role.duties.isNotEmpty()) {
                Text(
                    "职责：" + role.duties.joinToString("、"),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** 主持的熔断 / 并发参数 */
@Composable
private fun HostConfigSection(
    models: List<ModelProfile>,
    budget: ClusterBudget,
    onApply: (ClusterBudget, String) -> Unit,
) {
    var maxTurns by remember { mutableStateOf(budget.maxTurns.toString()) }
    var maxReplan by remember { mutableStateOf(budget.maxReplan.toString()) }
    var fanout by remember { mutableStateOf(budget.proposeFanout.toString()) }
    var modelId by remember { mutableStateOf(models.firstOrNull()?.id ?: "cloud:current") }
    var modelOpen by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("主持与熔断", style = MaterialTheme.typography.titleMedium)

            Box {
                OutlinedButton(onClick = { modelOpen = true }) {
                    Text(models.firstOrNull { it.id == modelId }?.displayName ?: "自动选择")
                }
                DropdownMenu(expanded = modelOpen, onDismissRequest = { modelOpen = false }) {
                    models.forEach { m ->
                        DropdownMenuItem(text = { Text(m.displayName) }, onClick = {
                            modelOpen = false; modelId = m.id
                        })
                    }
                }
            }

            OutlinedTextField(
                value = maxTurns, onValueChange = { maxTurns = it.filter(Char::isDigit) },
                label = { Text("最大轮次（防跑飞）") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = maxReplan, onValueChange = { maxReplan = it.filter(Char::isDigit) },
                label = { Text("最多重规划次数") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = fanout, onValueChange = { fanout = it.filter(Char::isDigit) },
                label = { Text("一次点名几个角色") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = {
                    onApply(
                        budget.copy(
                            maxTurns = maxTurns.toIntOrNull() ?: budget.maxTurns,
                            maxReplan = maxReplan.toIntOrNull() ?: budget.maxReplan,
                            proposeFanout = (fanout.toIntOrNull() ?: budget.proposeFanout).coerceIn(1, 5),
                        ),
                        modelId,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("保存") }
        }
    }
}

/** 从这一页直接发起一次集群任务（同步等待结果） */
@Composable
private fun StartTaskSection() {
    val ctx = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var goal by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("发起任务", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = goal,
                onValueChange = { goal = it },
                label = { Text("目标，例如：调研国产折叠屏芯片现状并出对比报告") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            Button(
                onClick = {
                    val g = goal.trim()
                    if (g.isBlank()) return@Button
                    busy = true; result = null
                    scope.launch {
                        // 驱动是同步阻塞的（要等集群跑完），必须放后台线程，别卡 UI。
                        // 直接调集群引擎而不是绕 QuroToolRegistry.active：
                        // active 是 ViewModel 构造时才赋值的伴生对象，
                        // 拿不到时旧代码只会显示「请重启应用」，掩盖真实原因。
                        val out = withContext(Dispatchers.IO) {
                            runCatching {
                                val engine = com.ai.assistance.quro.core.cluster.ClusterRuntime.get()
                                val cluster = com.ai.assistance.quro.core.cluster.ClusterRuntime
                                    .defaultCluster()
                                val task = engine.submit(cluster, g)
                                val reason = withTimeoutOrNull(30 * 60 * 1000L) {
                                    engine.drive(cluster, task)
                                } ?: CloseReason.CIRCUIT_BROKEN
                                val (done, total) = task.progress()
                                buildString {
                                    append(if (reason == CloseReason.GOAL_REACHED) "✅ 任务达成" else "⚠ 任务结束：${reason.name}")
                                    append("\n进度 $done/$total · 轮次 ${task.turnCount} · token ${task.tokenUsed}")
                                    if (!task.summary.isNullOrBlank()) append("\n\n").append(task.summary)
                                    append("\n\n（任务 ID：${task.id}，之后可用 cluster_status 查进度）")
                                }
                            }.getOrElse { e ->
                                "启动失败：${e.message ?: e.javaClass.simpleName}"
                            }
                        }
                        busy = false
                        result = out
                    }
                },
                enabled = !busy && goal.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "集群执行中…" else "开始（等待完成）") }

            result?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** 招人：列出还没入集群的人格卡 */
@Composable
private fun EnrollDialog(
    ctx: Context,
    models: List<ModelProfile>,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
) {
    val enrolled = remember { RoleRegistry.roles(ctx).map { it.personaId }.toSet() }
    val candidates = remember {
        QuroPersonaRepository(ctx).loadAll().filter { it.id !in enrolled && it.id != RoleRegistry.HOST_PERSONA_ID }
    }
    var sel by remember { mutableStateOf(candidates.firstOrNull()?.id) }
    var kind by remember { mutableStateOf(RoleKind.EXPERT) }
    var duties by remember { mutableStateOf("") }
    var modelId by remember { mutableStateOf(models.firstOrNull()?.id ?: "cloud:current") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加角色") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (candidates.isEmpty()) {
                    Text("没有可用人格卡。请先在「人格」里创建一张。")
                } else {
                    Text(
                        "选用人格卡",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    LazyColumn(
                        Modifier.fillMaxWidth().heightIn(max = 200.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(candidates, key = { it.id }) { p ->
                            FilterChip(
                                selected = sel == p.id,
                                onClick = { sel = p.id },
                                label = { Text(p.name) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    // #190 RoleKind 扩到 5 档后，这里原来写死的 if(EXPERT) "专家" else "评审"
                    // 会让「规划/执行」两档都显示成「专家」。必须用 k.label。
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RoleKind.selectable.forEach { k ->
                            FilterChip(
                                selected = kind == k,
                                onClick = { kind = k },
                                label = { Text(k.label) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = duties,
                        onValueChange = { duties = it },
                        label = { Text("职责（顿号/逗号分隔）") },
                        supportingText = {
                            Text("会原样进该角色的系统提示词；填得越具体，角色越像专家")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                    // 角色深度 = 人格卡(角色设定/描述) × 集群职责/禁忌/技能。
                    // 两边都空就一定是个「简单介绍」—— 明确告知，否则用户以为招完就完了。
                    val pickedPersona = candidates.firstOrNull { it.id == sel }
                    if (pickedPersona != null &&
                        pickedPersona.roleSetting.isBlank() &&
                        duties.isBlank()
                    ) {
                        Text(
                            "⚠ 这张卡的「角色设定」是空的，你也没填职责 —— 这样招出来的会是" +
                                "一个只有名字的空壳。请先填职责，或去「设置 → 人格」补角色设定。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = candidates.isNotEmpty() && sel != null,
                onClick = {
                    val id = sel ?: return@TextButton
                    RoleRegistry.enroll(
                        ctx, id,
                        modelProfileId = modelId,
                        role = kind,
                        duties = duties.split(',', '，')
                            .map { it.trim() }.filter { it.isNotBlank() },
                    )
                    onDone()
                },
            ) { Text("加入") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun personaName(ctx: Context, personaId: String): String =
    runCatching { QuroPersonaRepository(ctx).get(personaId)?.name }.getOrNull()
        ?.takeIf { it.isNotBlank() } ?: personaId

private fun toast(ctx: Context, msg: String) =
    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

/**
 * ★ 角色卡选择器（#191）★
 *
 * 一张角色卡 = 分工 + 灵魂注入正文 + **一整组 skills**。
 * 用户不需要先去 67 个技能里一格格勾 —— 选一张卡，角色连同技能一起建好。
 *
 * 建角走 [com.ai.assistance.quro.core.cluster.ClusterRoleCardTool]，
 * 开源技能由它联网现拉现装；失败会在结果里如实回报（gaps），这里照原样显示，
 * 不把「没绑上技能」的角色说成满配。
 */
@Composable
private fun RoleCardDialog(
    ctx: Context,
    models: List<ModelProfile>,
    onDismiss: () -> Unit,
    onDone: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var sel by remember { mutableStateOf(ClusterRoleCards.BUILT_IN.firstOrNull()?.id) }
    var modelId by remember { mutableStateOf(models.firstOrNull()?.id ?: "cloud:current") }
    var nameOverride by remember { mutableStateOf("") }
    // 卡片的技能清单：本地已装 / 本地缺失 / 需联网现拉
    var previews by remember { mutableStateOf<Map<String, Pair<List<String>, List<String>>>>(emptyMap()) }

    LaunchedEffect(Unit) {
        // 🔴 #202：这里原来seed +读**全局** QuroSkillStore，
        // 与建角（ClusterRoleCard.resolveForRole，只认集群库）不是同一套口径，
        // 表现为「卡片显示本地已装 → 建角却说缺技能」。
        // 现在只读集群库，播种交给 ClusterSkillStore.seed（内部按需触发）。
        runCatching { com.ai.assistance.quro.core.cluster.ClusterSkillStore.seed(ctx) }
        previews = ClusterRoleCards.BUILT_IN.associate { c ->
            // 直接调localOnlyPreview —— 它就是建角用的同一份判定。
            // 这里曾把逻辑手抄一遍，等于第三份口径，迟早与建角分叉。
            val (hits, missing) = ClusterRoleCards.localOnlyPreview(ctx, c)
            c.id to (hits.map { it.name } to missing)
        }
    }

    val cards = remember(query) { ClusterRoleCards.search(query) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("从角色卡创建角色") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "角色卡 = 一个技能聚合：选一张卡，角色的分工、灵魂设定与那组技能会一次性配好。",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索角色卡") },
                    placeholder = { Text("如 ui / 前端 / 写作 / 评审") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 230.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(cards, key = { it.id }) { c ->
                        val pv = previews[c.id]
                        FilterChip(
                            selected = sel == c.id,
                            onClick = { sel = c.id; nameOverride = "" },
                            label = {
                                Column {
                                    Text("${c.emoji} ${c.label} · ${c.kind.label}")
                                    Text(
                                        c.tagline,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = cs.onSurfaceVariant,
                                    )
                                    // 诚实：把「本地还没装/需联网拉」明写出来
                                    val open = c.skills.count { it.isOpenSource }
                                    val miss = pv?.second?.size ?: 0
                                    Text(
                                        buildString {
                                            append("技能 ${c.skills.size} 门")
                                            if (open > 0) append(" · $open 门联网现拉")
                                            if (miss > 0) append(" · $miss 门本地缺失")
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (miss > 0) cs.error else cs.primary,
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                OutlinedTextField(
                    value = nameOverride,
                    onValueChange = { nameOverride = it },
                    label = { Text("角色名（留空用卡片名）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    label = { Text("模型 profile id") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = sel != null && !busy,
                onClick = {
                    val cardId = sel ?: return@TextButton
                    busy = true
                    scope.launch {
                        // 建角要联网拉开源技能 → 放 IO 线程，别卡 UI
                        val out = withContext(Dispatchers.IO) {
                            runCatching {
                                org.json.JSONObject(
                                    ClusterRoleCardTool().run(
                                        ctx,
                                        org.json.JSONObject()
                                            .put("mode", "create")
                                            .put("cardId", cardId)
                                            .put("modelId", modelId)
                                            .put(
                                                "personaName",
                                                if (nameOverride.isBlank()) "" else nameOverride.trim()
                                            ).toString()
                                    )
                                )
                            }
                        }
                        busy = false
                        out.onSuccess { j ->
                            if (j.optBoolean("ok")) {
                                // 缺技能必须让用户看见，不能一句「已创建」糊过去
                                val gaps = j.optJSONArray("gaps")
                                val gapMsg = if (gaps != null && gaps.length() > 0) {
                                    "\n⚠ " + (0 until gaps.length()).joinToString("\n") { gaps.optString(it) }
                                } else ""
                                onDone(j.optString("message") + gapMsg)
                            } else {
                                toast(ctx, j.optString("error").ifBlank { "创建失败" })
                            }
                        }.onFailure {
                            toast(ctx, "创建失败：" + (it.message ?: it.javaClass.simpleName))
                        }
                    }
                },
            ) { Text(if (busy) "创建中…" else "创建") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } },
    )

}
