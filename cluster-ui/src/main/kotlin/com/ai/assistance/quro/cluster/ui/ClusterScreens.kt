package com.ai.assistance.quro.cluster.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.quro.cluster.bridge.HostModelInfo
import com.ai.assistance.quro.cluster.engine.ClusterEngine
import com.ai.assistance.quro.cluster.model.*
import kotlinx.coroutines.launch

/**
 * 集群看板：主持置顶且标记为不可修改身份，其余角色以卡片展示。
 * 宿主只需在自己的 Compose 导航里加一行 `ClusterBoardScreen(clusterId)`。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClusterBoardScreen(clusterId: ClusterId, onOpenTask: (TaskId) -> Unit = {}) {
    val engine = remember { ClusterEngine.get() }
    val scope = rememberCoroutineScope()
    var agents by remember { mutableStateOf(listOf<AgentConfig>()) }
    var models by remember { mutableStateOf(listOf<HostModelInfo>()) }
    var cluster by remember { mutableStateOf<Cluster?>(null) }
    var goal by remember { mutableStateOf("") }

    LaunchedEffect(clusterId) {
        agents = engine.agents(clusterId)
        models = engine.hostModels()
        cluster = engine.cluster(clusterId)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(cluster?.name ?: "集群") }) }
    ) { pad ->
        Column(Modifier.padding(pad).padding(16.dp)) {
            OutlinedTextField(
                value = goal, onValueChange = { goal = it },
                label = { Text("交给主持的任务") }, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    scope.launch {
                        val tid = engine.submit(clusterId, goal)
                        engine.runAsync(tid)
                        onOpenTask(tid)
                    }
                },
                enabled = goal.isNotBlank(), modifier = Modifier.fillMaxWidth()
            ) { Text("交给主持推进") }

            Spacer(Modifier.height(16.dp))
            Text("主持", style = MaterialTheme.typography.titleMedium)
            HostCard(host = cluster?.host, models = models)

            Spacer(Modifier.height(12.dp))
            Text("角色（${agents.size}）", style = MaterialTheme.typography.titleMedium)
            LazyColumn {
                items(agents) { a -> AgentCard(a, models) }
            }
        }
    }
}

@Composable
private fun HostCard(host: HostConfig?, models: List<HostModelInfo>) {
    val name = models.firstOrNull { it.id == host?.modelBinding?.hostModelId }?.displayName
        ?: host?.modelBinding?.hostModelId.orEmpty()
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("🎯 主持（内置·身份不可改）", style = MaterialTheme.typography.titleSmall)
            Text("调度模型：$name", style = MaterialTheme.typography.bodySmall)
            Text(
                "编排：${host?.orchestration?.mode} · 熔断：${host?.termination?.maxTurns}轮/${host?.termination?.maxReplan}次重规划",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun AgentCard(a: AgentConfig, models: List<HostModelInfo>) {
    val m = models.firstOrNull { it.id == a.modelBinding.hostModelId }
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row {
                Text(a.identity.avatarEmoji)
                Spacer(Modifier.width(6.dp))
                Text(a.identity.name, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                Text(if (a.enabled) "启用" else "停用", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "模型：${m?.displayName ?: a.modelBinding.hostModelId}${if (m?.available == false) "（不可用）" else ""}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (a.duties.isNotEmpty()) {
                Text(a.duties.joinToString("、"), style = MaterialTheme.typography.bodySmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * 模型选择下拉 —— 数据源是**宿主自己的模型配置界面**，SDK 只做展示与绑定。
 * 宿主在配置界面里增删改模型后调 bridge.notifyModelsChanged()，这里会自动刷新。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostModelPicker(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val engine = remember { ClusterEngine.get() }
    var models by remember { mutableStateOf(listOf<HostModelInfo>()) }
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { models = engine.hostModels(forceRefresh = true) }

    val current = models.firstOrNull { it.id == selected }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = current?.displayName ?: selected.ifBlank { "（未选择，运行时自动兜底）" },
            onValueChange = {}, readOnly = true,
            label = { Text("模型（来自宿主配置）") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            models.groupBy { it.group ?: "默认" }.forEach { (group, list) ->
                DropdownMenuItem(
                    text = { Text(group, style = MaterialTheme.typography.labelSmall) },
                    onClick = {}, enabled = false
                )
                list.forEach { m ->
                    DropdownMenuItem(
                        text = {
                            Text("${m.displayName}${if (!m.available) " · 不可用" else ""}")
                        },
                        onClick = { onSelect(m.id); expanded = false }
                    )
                }
            }
        }
    }
}

/**
 * 角色编辑页：身份 / 人格 / 职责 / 模型 / 技能 / 策略 一屏改完。
 * 主持复用同一页，但"身份"区块对主持只读。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentEditorScreen(
    agent: AgentConfig,
    isHost: Boolean = false,
    onSave: (AgentConfig) -> Unit
) {
    var editable by remember(agent) { mutableStateOf(agent) }
    Scaffold(topBar = {
        TopAppBar(title = { Text(if (isHost) "主持设置（身份不可改）" else "角色设置") })
    }) { pad ->
        LazyColumn(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                OutlinedTextField(
                    value = editable.identity.name, onValueChange = { },
                    label = { Text("名称") }, enabled = !isHost, modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                OutlinedTextField(
                    value = editable.persona, onValueChange = { editable = editable.copy(persona = it) },
                    label = { Text("人格 / 思维方式") }, minLines = 3, modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                OutlinedTextField(
                    value = editable.duties.joinToString("\n"),
                    onValueChange = { editable = editable.copy(duties = it.lines().filter { s -> s.isNotBlank() }) },
                    label = { Text("工作内容（一行一条）") }, minLines = 3, modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                OutlinedTextField(
                    value = editable.taboos.joinToString("\n"),
                    onValueChange = { editable = editable.copy(taboos = it.lines().filter { s -> s.isNotBlank() }) },
                    label = { Text("禁止事项（一行一条）") }, minLines = 2, modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                // modifier 挡在 onSelect 之后，尾随 lambda 只能绑定最后一个参数 -> 必须用具名参数
                HostModelPicker(
                    selected = editable.modelBinding.hostModelId,
                    onSelect = { picked ->
                        editable = editable.copy(
                            modelBinding = editable.modelBinding.copy(hostModelId = picked)
                        )
                    }
                )
            }
            item {
                OutlinedTextField(
                    value = editable.modelBinding.fallbackChain.joinToString(","),
                    onValueChange = {
                        editable = editable.copy(
                            modelBinding = editable.modelBinding.copy(
                                fallbackChain = it.split(",").map { s -> s.trim() }.filter { s -> s.isNotBlank() }
                            )
                        )
                    },
                    label = { Text("降级链（宿主模型 ID，逗号分隔）") }, modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                Button(onClick = { onSave(editable) }, modifier = Modifier.fillMaxWidth()) { Text("保存") }
            }
        }
    }
}
