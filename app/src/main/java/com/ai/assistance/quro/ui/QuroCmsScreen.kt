package com.ai.assistance.quro.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ai.assistance.quro.core.cms.*
import com.ai.assistance.quro.ui.theme.Line
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * CMSv2模块 — 管理界面（原创，对应 Rust 版 CLI/UI）。
 * 分区：模块 / 授权 / 能力；右上「审计」查看上帝视角日志。
 * 调用能力时经权限中介弹出 4 级授权框（临时/会话/永久/全局）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuroCmsScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current.applicationContext
    val repo = remember { QuroCmsRepository(ctx) }
    val storage = remember { QuroCmsStorage(ctx) }
    val executor = remember { QuroCmsExecutor(ctx) }

    // 首次进入注入演示模块 + 初始化 CMS 状态系统（进入即 re-query 部署态，跨重启持久化）
    LaunchedEffect(Unit) {
        repo.ensureSeed()
        CmsStateStore.init(ctx)
    }
    // 订阅状态系统：部署进度/明确终态/日志实时刷新（解决「装包无实时 UI / 返回不知成功否」）
    val store by CmsStateStore.snapshot.collectAsState()

    var section by remember { mutableStateOf("modules") }
    var modules by remember { mutableStateOf(repo.load()) }
    var auths by remember { mutableStateOf(storage.listAuths()) }
    var caps by remember { mutableStateOf(repo.loadCapabilities()) }
    var showAdd by remember { mutableStateOf(false) }
    var infoModule by remember { mutableStateOf<QuroCmsModule?>(null) }
    var callPair by remember { mutableStateOf<Pair<QuroCmsModule, QuroCmsCapability>?>(null) }
    var showAudit by remember { mutableStateOf(false) }
    var pendingPerm by remember { mutableStateOf<QuroCmsPermission?>(null) }
    var permDeferred = remember { mutableStateOf<CompletableDeferred<AuthorizationLevel?>?>(null) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        modules = repo.load()
        auths = storage.listAuths()
        caps = repo.loadCapabilities()
    }

    // 权限请求回调：挂起直到用户在弹窗里选择
    val uiRequest: suspend (QuroCmsPermission) -> AuthorizationLevel? = { perm ->
        val d = CompletableDeferred<AuthorizationLevel?>()
        pendingPerm = perm
        permDeferred.value = d
        d.await()
    }

    fun completeChoice(level: AuthorizationLevel?) {
        permDeferred.value?.complete(level)
        permDeferred.value = null
        pendingPerm = null
    }

    // 授权导出 / 导入（SAF）
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(storage.exportAuths().toByteArray()) }
            refresh()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return@runCatching
            if (storage.importAuths(text)) refresh()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CMSv2模块", style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold)) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.ArrowBack, null) } },
                actions = {
                    IconButton(onClick = { showAudit = true }) { Icon(Icons.Filled.History, null) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // 分区切换
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = section == "modules", onClick = { section = "modules" }, label = { Text("模块") })
                FilterChip(selected = section == "auth", onClick = { section = "auth" }, label = { Text("授权") })
                FilterChip(selected = section == "caps", onClick = { section = "caps" }, label = { Text("能力") })
            }
            when (section) {
                "modules" -> ModulesSection(
                    modules = modules,
                    store = store,
                    onAdd = { showAdd = true },
                    onInfo = { infoModule = it },
                    onUninstall = { repo.uninstall(it.id); refresh() },
                    onDeployed = { modules = repo.load() },
                )
                "auth" -> AuthSection(
                    auths = auths,
                    onRevoke = { storage.revoke(it.moduleId, it.permissionId); refresh() },
                    onExport = { exportLauncher.launch("cms-auth-backup.json") },
                    onImport = { importLauncher.launch(arrayOf("application/json")) },
                )
                "caps" -> CapsSection(
                    caps = caps,
                    onCall = { callPair = it },
                )
            }
        }
    }

    // 弹窗们
    if (showAdd) {
        AddModuleDialog(
            onDismiss = { showAdd = false },
            onConfirm = { m -> repo.upsert(m); showAdd = false; refresh() },
        )
    }
    infoModule?.let { ModuleInfoDialog(it, onDismiss = { infoModule = null }) }
    callPair?.let { (m, c) ->
        CallDialog(
            module = m, cap = c, executor = executor, uiRequest = uiRequest,
            onDismiss = { callPair = null },
        )
    }
    if (showAudit) {
        AuditDialog(storage, onDismiss = { showAudit = false })
    }
    pendingPerm?.let { perm ->
        PermissionRequestDialog(perm, onChoose = { completeChoice(it) }, onDeny = { completeChoice(null) })
    }
}

// ---------------- 模块 ----------------

@Composable
private fun ModulesSection(
    modules: List<QuroCmsModule>,
    store: CmsStateStore.Snapshot,
    onAdd: () -> Unit,
    onInfo: (QuroCmsModule) -> Unit,
    onUninstall: (QuroCmsModule) -> Unit,
    onDeployed: () -> Unit,
) {
    val ctx = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var busyOneClick by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(text = "添加模块", modifier = Modifier.weight(1f), onClick = onAdd)
            PrimaryButton(
                text = if (busyOneClick) "部署中…" else "一键部署到终端",
                modifier = Modifier.weight(1f),
                enabled = !busyOneClick,
                onClick = {
                    busyOneClick = true
                    scope.launch(Dispatchers.IO) {
                        try {
                            val b = CmsTerminalDeployer.bootstrap(ctx)
                            // v192 修复：一键部署改为部署当前模块库的全部模块（各自更新自身状态），
                            // 不再恒部署内置 demo-py（此前导致模块自身部署状态永远不更新）。
                            val d = if (b.startsWith("⛔")) "" else modules.joinToString("\n") { CmsTerminalDeployer.deploy(ctx, CmsDeployPackage.fromModule(it)) }
                            val msg = if (b.startsWith("⛔")) b else "$b\n$d"
                            withContext(Dispatchers.Main) {
                                Toast.makeText(ctx, msg.lines().firstOrNull() ?: msg, Toast.LENGTH_LONG).show()
                                onDeployed()
                            }
                        } finally {
                            withContext(Dispatchers.Main) { busyOneClick = false }
                        }
                    }
                },
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "💡 CMS 模块部署到应用内 proot/Alpine Linux 沙箱运行。首次请先在「终端」页安装 Linux 环境（约需联网下载 30MB）；部署时按需 apk add 基础包。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        if (modules.isEmpty()) {
            Text("还没有模块。点上方「添加模块」创建一个能力模块（cms.io/v2）。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        modules.forEach { m ->
            val rec = store.modules[m.id]
            val deployedFile = CmsTerminalDeployer.hostDir(ctx, m.id).resolve("cms-package.json").exists()
            val deployStatus = rec?.deployStatus ?: if (deployedFile) "deployed" else "idle"
            val running = rec?.running ?: false
            val deployTask = store.tasks["deploy:${m.id}"]
            val hasBlockingDep = m.dependencies.any { !it.optional && it.kind != DepKind.CAPABILITY }
            val lastLogs = store.logs[m.id].orEmpty().takeLast(3)
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface).border(1.dp, Line, RoundedCornerShape(12.dp))) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.name, style = MaterialTheme.typography.bodyLarge)
                        Text("${m.id} · v${m.version} · ${m.state.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val ats = m.capabilities.map { it.actionType }.distinct().joinToString("/")
                        Text("权限 ${m.permissions.size} · 能力 ${m.capabilities.size}${if (m.dependencies.isNotEmpty()) " · 依赖 ${m.dependencies.size}" else ""}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (ats.isNotBlank())
                            Text("通道：$ats · 级别 ${m.maxRequiredLevel().name}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        // 部署状态（实时，来自状态系统；进入即 re-query，跨重启持久化）
                        val (statusText, statusColor) = when (deployStatus) {
                            "deploying" -> "● 部署中 ${deployTask?.progressPct ?: 0}%" to MaterialTheme.colorScheme.primary
                            "deployed" -> "● 已部署到终端" to MaterialTheme.colorScheme.primary
                            "failed" -> "⛔ 部署失败" to MaterialTheme.colorScheme.error
                            else -> "○ 未部署" to MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(statusText, style = MaterialTheme.typography.labelSmall, color = statusColor)
                            if (running) {
                                Spacer(Modifier.width(8.dp))
                                Text("· 运行中", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                            }
                            if (hasBlockingDep) {
                                Spacer(Modifier.width(8.dp))
                                Text("⚠ 含需解析依赖", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    IconButton(onClick = { onInfo(m) }) { Icon(Icons.Filled.Info, null) }
                    IconButton(onClick = { onUninstall(m) }) { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) }
                }
                // 实时日志预览（来自状态系统，部署/执行过程可见）
                if (lastLogs.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 4.dp)) {
                        lastLogs.forEach { line ->
                            Text(line, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                    }
                }
                // 部署到终端（CMS v2）：演示内置 demo-py 端到端链路（真实模块包由 v183 内置 bootstrap 提供）
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
                    val scope = rememberCoroutineScope()
                    var busy by remember { mutableStateOf(false) }
                    Button(
                        onClick = {
                            busy = true
                            scope.launch(Dispatchers.IO) {
                                try {
                                    // v192 修复：部署当前选中模块（fromModule）而非硬编码 demo-py，
                                    // 使该模块自身的部署状态（deploying→deployed）正确回显。
                                    val res = CmsTerminalDeployer.deploy(ctx, CmsDeployPackage.fromModule(m))
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(ctx, res.lines().firstOrNull() ?: res, Toast.LENGTH_LONG).show()
                                        onDeployed()
                                    }
                                } finally { withContext(Dispatchers.Main) { busy = false } }
                            }
                        },
                        enabled = !busy && deployStatus != "deploying",
                    ) {
                        Text(if (busy) "部署中…" else "部署到终端")
                    }
                }
            }
            // 终端已部署但不在模块库的插件（如 demo-py / 外部导入包）——对应「终端插件没更新到UI」：
            // 此前这类模块仅写入状态系统、却不在模块库列表，用户在 UI 看不到。现单独列出并带实时状态。
            val repoIds = modules.map { it.id }.toSet()
            val terminalDeployed = store.modules.filter { (id, rec) ->
                id !in repoIds && rec.deployStatus in setOf("deployed", "deploying", "failed")
            }
            if (terminalDeployed.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("📦 终端已部署（未在模块库）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                terminalDeployed.forEach { (id, rec) ->
                    val (stxt, scol) = when (rec.deployStatus) {
                        "deploying" -> "● 部署中" to MaterialTheme.colorScheme.primary
                        "deployed" -> "● 已部署" to MaterialTheme.colorScheme.primary
                        "failed" -> "⛔ 部署失败" to MaterialTheme.colorScheme.error
                        else -> "○ 未部署" to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(id, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(stxt, style = MaterialTheme.typography.labelSmall, color = scol)
                    }
                }
            }
        }
    }
}

@Composable
private fun ModuleInfoDialog(module: QuroCmsModule, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        title = { Text(module.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("id: ${module.id}", style = MaterialTheme.typography.bodySmall)
                Text("版本: ${module.version} · 作者: ${module.author.ifBlank { "-" }}", style = MaterialTheme.typography.bodySmall)
                Text(module.description, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Text("权限声明：", style = MaterialTheme.typography.labelMedium)
                module.permissions.forEach { p ->
                    Text("• ${p.id} [${p.level.name}] → ${p.authorization.name}：${p.rationale}", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                Text("能力 (${module.capabilities.size})：", style = MaterialTheme.typography.labelMedium)
                module.capabilities.forEach { c ->
                    Text("• ${c.id} [${c.actionType}]：${c.summary}", style = MaterialTheme.typography.bodySmall)
                    if (c.requiresPermissions.isNotEmpty())
                        Text("  需权限：${c.requiresPermissions.joinToString(", ")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val con = c.constraints
                    val conItems = buildList {
                        if (con.allowedPaths.isNotEmpty()) add("路径=${con.allowedPaths}")
                        if (con.allowedCommands.isNotEmpty()) add("命令=${con.allowedCommands}")
                        if (con.allowedDomains.isNotEmpty()) add("域名=${con.allowedDomains}")
                        if (con.maxExecutionTimeSecs != 30) add("超时=${con.maxExecutionTimeSecs}s")
                        if (con.maxMemoryMb != 64) add("内存=${con.maxMemoryMb}MB")
                    }
                    if (conItems.isNotEmpty())
                        Text("  约束：${conItems.joinToString(" · ")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (module.dependencies.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("依赖 (${module.dependencies.size})：", style = MaterialTheme.typography.labelMedium)
                    module.dependencies.forEach { d ->
                        val tgt = if (d.kind == DepKind.ENV) d.spec else (d.spec.ifBlank { d.capability })
                        Text("• [${d.kind.name}] $tgt @${d.version}${if (d.optional) "（可选）" else ""}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("元信息：", style = MaterialTheme.typography.labelMedium)
                Text("生命周期：${module.lifecycle} · 目录：${module.catalog.ifBlank { "-" }}", style = MaterialTheme.typography.bodySmall)
                if (module.signature.isNotBlank())
                    Text("签名：${module.signature.take(32)}…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddModuleDialog(onDismiss: () -> Unit, onConfirm: (QuroCmsModule) -> Unit) {
    var id by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var version by remember { mutableStateOf("1.0.0") }
    var description by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    var license by remember { mutableStateOf("Apache-2.0") }
    var perms by remember { mutableStateOf(mutableListOf<QuroCmsPermission>()) }
    var caps by remember { mutableStateOf(mutableListOf<QuroCmsCapability>()) }
    var deps by remember { mutableStateOf(mutableListOf<QuroCmsDependency>()) }
    var envProfiles by remember { mutableStateOf(mutableSetOf<String>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = {
                    if (id.isNotBlank()) onConfirm(
                        QuroCmsModule(
                            id = id.trim(), name = name.ifBlank { id.trim() }, version = version.ifBlank { "1.0.0" },
                            description = description, author = author, license = license,
                            state = ModuleState.Ready, permissions = perms, capabilities = caps,
                            dependencies = deps + envProfiles.map { QuroCmsDependency(kind = DepKind.ENV, spec = it, optional = false) },
                        )
                    )
                },
                enabled = id.isNotBlank(),
            ) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text("添加能力模块", style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(id, { id = it }, label = { Text("模块 ID *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(name, { name = it }, label = { Text("显示名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(version, { version = it }, label = { Text("版本") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(description, { description = it }, label = { Text("描述") }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), minLines = 2)
                OutlinedTextField(author, { author = it }, label = { Text("作者") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(license, { license = it }, label = { Text("许可证") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("权限声明 (${perms.size})", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { perms.add(QuroCmsPermission("perm_${perms.size}", PermissionLevel.Normal, "", "*", AuthorizationLevel.Session)) }) { Text("+ 权限") }
                }
                perms.forEachIndexed { i, p ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(p.id, { perms[i] = p.copy(id = it) }, label = { Text("id") }, singleLine = true, modifier = Modifier.weight(1f))
                        EnumDropdown(p.level) { perms[i] = p.copy(level = it) }
                        IconButton(onClick = { perms.removeAt(i) }) { Icon(Icons.Filled.Delete, null) }
                    }
                    OutlinedTextField(p.rationale, { perms[i] = p.copy(rationale = it) }, label = { Text("理由") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }

                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("能力 (${caps.size})", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { caps.add(QuroCmsCapability("cap_${caps.size}", "", "{}", emptyList(), PermissionConstraints(), "intent", "")) }) { Text("+ 能力") }
                }
                caps.forEachIndexed { i, c ->
                    OutlinedTextField(c.id, { caps[i] = c.copy(id = it) }, label = { Text("能力 id") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(c.summary, { caps[i] = c.copy(summary = it) }, label = { Text("摘要") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(c.action, { caps[i] = c.copy(action = it) }, label = { Text("动作(intent JSON / js 脚本 / api 操作 / terminal 命令)") }, singleLine = true, modifier = Modifier.weight(1f))
                        StringDropdown(c.actionType, listOf("intent", "js", "api", "terminal")) { caps[i] = c.copy(actionType = it) }
                    }
                    OutlinedTextField(c.requiresPermissions.joinToString(","), { caps[i] = c.copy(requiresPermissions = it.split(",").map { s -> s.trim() }.filter { s -> s.isNotBlank() }) }, label = { Text("所需权限 id(逗号分隔)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    Text("约束（可选，越细越安全）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(c.constraints.allowedCommands.joinToString(","), { caps[i] = c.copy(constraints = c.constraints.copy(allowedCommands = it.split(",").map { s -> s.trim() }.filter { s -> s.isNotBlank() })) }, label = { Text("命令白名单(逗号分隔)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(c.constraints.allowedPaths.joinToString(","), { caps[i] = c.copy(constraints = c.constraints.copy(allowedPaths = it.split(",").map { s -> s.trim() }.filter { s -> s.isNotBlank() })) }, label = { Text("允许路径(逗号分隔)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(c.constraints.allowedDomains.joinToString(","), { caps[i] = c.copy(constraints = c.constraints.copy(allowedDomains = it.split(",").map { s -> s.trim() }.filter { s -> s.isNotBlank() })) }, label = { Text("允许域名(逗号分隔)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(c.constraints.maxExecutionTimeSecs.toString(), { v -> v.toIntOrNull()?.let { caps[i] = c.copy(constraints = c.constraints.copy(maxExecutionTimeSecs = it)) } }, label = { Text("最大执行秒数") }, singleLine = true, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(c.constraints.maxMemoryMb.toString(), { v -> v.toIntOrNull()?.let { caps[i] = c.copy(constraints = c.constraints.copy(maxMemoryMb = it)) } }, label = { Text("最大内存 MB") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("依赖 (${deps.size})", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { deps.add(QuroCmsDependency(capability = "", version = "1.0.0", optional = false)) }) { Text("+ 依赖") }
                }
                deps.forEachIndexed { i, d ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(d.capability, { deps[i] = d.copy(capability = it) }, label = { Text("能力 id") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(d.version, { deps[i] = d.copy(version = it) }, label = { Text("版本") }, singleLine = true, modifier = Modifier.weight(1f))
                        IconButton(onClick = { deps.removeAt(i) }) { Icon(Icons.Filled.Delete, null) }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = d.optional, onCheckedChange = { deps[i] = d.copy(optional = it) })
                        Text("可选依赖", style = MaterialTheme.typography.bodySmall)
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text("终端环境栈（部署到终端时自动装配，勾选所需档）", style = MaterialTheme.typography.labelMedium)
                EnvProfile.entries.forEach { ep ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = envProfiles.contains(ep.name),
                            onCheckedChange = { checked ->
                                envProfiles = if (checked) (envProfiles + ep.name).toMutableSet()
                                else (envProfiles - ep.name).toMutableSet()
                            },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(ep.name, style = MaterialTheme.typography.bodyMedium)
                            Text(ep.profileName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
    )
}

// ---------------- 授权 ----------------

@Composable
private fun AuthSection(
    auths: List<QuroCmsStorage.AuthEntry>,
    onRevoke: (QuroCmsStorage.AuthEntry) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(text = "导出备份", modifier = Modifier.weight(1f), onClick = onExport)
            PrimaryButton(text = "导入", modifier = Modifier.weight(1f), onClick = onImport)
        }
        Spacer(Modifier.height(8.dp))
        if (auths.isEmpty()) {
            Text("暂无授权记录。在「能力」页调用能力时，会按 4 级授权（临时/会话/永久/全局）向用户请求。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        auths.forEach { a ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface).border(1.dp, Line, RoundedCornerShape(12.dp))) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${a.moduleId} : ${a.permissionId}", style = MaterialTheme.typography.bodyMedium)
                        Text("授权级别：${a.level.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { onRevoke(a) }) { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

// ---------------- 能力 ----------------

@Composable
private fun CapsSection(
    caps: List<Pair<QuroCmsModule, QuroCmsCapability>>,
    onCall: (Pair<QuroCmsModule, QuroCmsCapability>) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        if (caps.isEmpty()) item { Text("还没有能力。先到「模块」页添加含能力的模块。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(caps) { (m, c) ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface).border(1.dp, Line, RoundedCornerShape(12.dp))) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.id, style = MaterialTheme.typography.bodyMedium)
                        Text("${m.name} · ${c.summary}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (c.requiresPermissions.isNotEmpty())
                            Text("需权限：${c.requiresPermissions.joinToString(", ")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    PrimaryButton(text = "调用", modifier = Modifier.width(IntrinsicSize.Min), onClick = { onCall(m to c) })
                }
            }
        }
    }
}

@Composable
private fun CallDialog(
    module: QuroCmsModule,
    cap: QuroCmsCapability,
    executor: QuroCmsExecutor,
    uiRequest: suspend (QuroCmsPermission) -> AuthorizationLevel?,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val argNames = remember { cap.argNames() }
    val args = remember { argNames.associateWith { mutableStateOf("") } }
    var result by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = {
                    running = true
                    result = null
                    val map = args.mapValues { it.value.value }
                    scope.launch(Dispatchers.IO) {
                        val r = executor.execute(module, cap, map, uiRequest)
                        withContext(Dispatchers.Main) { result = r; running = false }
                    }
                },
                enabled = !running,
            ) { Text(if (running) "执行中…" else "执行") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        title = { Text("调用能力 · ${cap.id}", style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold)) },
        text = {
            Column {
                Text(cap.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("模块：${module.name} · 通道级别：${module.maxRequiredLevel().name}", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                val actionLabel = when (cap.actionType) {
                    "js" -> "JS 脚本（应用内 QuickJS 沙箱）"
                    "api" -> "API 操作"
                    "terminal" -> "终端命令（应用内 proot/Alpine 沙箱）"
                    else -> "Intent（应用内派发）"
                }
                Text("$actionLabel：${cap.action}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(8.dp))
                argNames.forEach { name ->
                    OutlinedTextField(args[name]!!.value, { args[name]!!.value = it }, label = { Text("参数 $name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                result?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = if (it.startsWith("⛔")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
            }
        },
    )
}

// ---------------- 审计 ----------------

@Composable
private fun AuditDialog(storage: QuroCmsStorage, onDismiss: () -> Unit) {
    var entries by remember { mutableStateOf(storage.loadAudit()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = {
            TextButton(onClick = { storage.clearAudit(); entries = emptyList() }) { Text("清空") }
        },
        title = { Text("审计日志（上帝视角）", style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold)) },
        text = {
            if (entries.isEmpty()) {
                Text("暂无审计记录。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(entries) { e ->
                        val t = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(e.timestamp))
                        Text(
                            "[$t] ${e.action} | ${e.level} | ${e.moduleId}:${e.permissionId}\n  ${e.userAction} · ${e.decisionReason}",
                            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                        )
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    }
                }
            }
        },
    )
}

// ---------------- 权限请求弹窗 ----------------

@Composable
private fun PermissionRequestDialog(
    perm: QuroCmsPermission,
    onChoose: (AuthorizationLevel) -> Unit,
    onDeny: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDeny,
        title = { Text("🔐 权限请求", style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold)) },
        text = {
            Column {
                Text("模块能力需要权限：", style = MaterialTheme.typography.bodySmall)
                Text("权限：${perm.id} [${perm.level.name}]", style = MaterialTheme.typography.bodyMedium)
                Text("理由：${perm.rationale}", style = MaterialTheme.typography.bodySmall)
                Text("作用域：${perm.scope}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text("请选择授权级别：", style = MaterialTheme.typography.labelMedium)
            }
        },
        confirmButton = {
            Column {
                TextButton(onClick = { onChoose(AuthorizationLevel.Temporary) }) { Text("仅本次允许（推荐）") }
                TextButton(onClick = { onChoose(AuthorizationLevel.Session) }) { Text("本次会话允许") }
                TextButton(onClick = { onChoose(AuthorizationLevel.Permanent) }) { Text("永久允许") }
                TextButton(onClick = { onChoose(AuthorizationLevel.Global) }) { Text("全局允许（危险!）") }
            }
        },
        dismissButton = { TextButton(onClick = onDeny) { Text("拒绝") } },
    )
}

// ---------------- 通用下拉 ----------------

@Composable
private inline fun <reified T : Enum<T>> EnumDropdown(value: T, noinline onPick: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text(value.name) }
        DropdownMenu(expanded, { expanded = false }) {
            enumValues<T>().forEach { v ->
                DropdownMenuItem(text = { Text(v.name) }, onClick = { onPick(v); expanded = false })
            }
        }
    }
}

@Composable
private fun StringDropdown(value: String, options: List<String>, onPick: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text(value) }
        DropdownMenu(expanded, { expanded = false }) {
            options.forEach { v ->
                DropdownMenuItem(text = { Text(v) }, onClick = { onPick(v); expanded = false })
            }
        }
    }
}
