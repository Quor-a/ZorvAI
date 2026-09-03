package com.ai.assistance.quro.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.zIndex
import com.ai.assistance.quro.core.cms.*
import com.ai.assistance.quro.core.tools.QuroDownloadUtil
import com.ai.assistance.quro.ui.theme.Line
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * 「怎么添加 CMS v2 模块」说明（开发者文档，可一键下载到本地）。
 */
private val CMS_ADD_MODULE_GUIDE = """
CMS v2 模块 = 一个能力包（cms.io/v2 规范），可部署到「手机端（应用内执行）」或「终端（proot/Ubuntu Linux 沙箱内执行）」。

一、点「+ 添加模块」填写：
• 名称 / 简介：一眼看懂用途。
• 宿主分类：app=手机端执行；terminal=终端 Linux 内执行；dual=双端皆可。
• 能力（Capabilities）：模块对外暴露的能力点，每个含 id / 描述 / 是否需用户确认。
• 依赖（Dependencies）：声明运行所需依赖，kind 选 MODULE（依赖另一个 CMS 模块）/ MCP（别名）/ SKILL（id）/ LINUX（apt 或 pip 包）/ CAPABILITY（依赖另一个模块的能力）。

二、依赖与运行：
• 终端模块首次部署前，先在「终端」页安装 Linux 环境（约需联网下载 30MB），部署时按需 apt-get install 基础包。
• 阻塞依赖（非 optional 且非 CAPABILITY）未满足时会提示先 provision。

三、导入 / 导出：
• 右上「导出模块」生成 cms-package.json；「导入模块」可粘贴他人分享的模块 JSON 恢复。

四、部署：
• 在模块卡片点「部署」，终端模块会写入 proot 宿主目录并启动；手机模块直接在应用内注册能力。
"""

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
        CmsEngineStore.init(ctx)
        CmsEngineStore.checkStale() // 进入即检查是否有卡死的部署态（#911 看门狗）
        if (CmsEngineStore.snapshot.value.ready) CmsEngineStore.probeHealth(ctx)
    }
    // 订阅状态系统：部署进度/明确终态/日志实时刷新（解决「装包无实时 UI / 返回不知成功否」）
    val store by CmsStateStore.snapshot.collectAsState()
    // 订阅CMS引擎状态（部署就绪/健康/共享服务）
    val engineStore by CmsEngineStore.snapshot.collectAsState()

    val scope = rememberCoroutineScope()
    var busyDeployEngine by remember { mutableStateOf(false) }

    // 部署官方CMS引擎（quro-engine）
    fun deployEngine() {
        busyDeployEngine = true
        scope.launch(Dispatchers.IO) {
            try {
                val res = CmsEngineDeployer.deployEngine(ctx, CmsEnginePackage.builtin())
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, res.lines().firstOrNull() ?: res, Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) { busyDeployEngine = false }
            }
        }
    }
    // 导出官方CMS引擎到 Download/Quro（.cmsengine，可分享/本地留存）
    fun exportEngine() {
        val r = QuroDownloadUtil.saveTextToDownloads(ctx, "QuroEngine.cmsengine", "application/json", CmsEngineDeployer.exportPackage(CmsEnginePackage.builtin()))
        Toast.makeText(ctx, if (r.startsWith("OK:")) "已保存CMS引擎到 Download/Quro/" else r, Toast.LENGTH_LONG).show()
    }
    // 导入CMS引擎（.cmsengine）→ 解析后一键部署
    val importEngineLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return@runCatching
            val pkg = CmsEngineDeployer.importPackage(text)
            scope.launch(Dispatchers.IO) {
                val res = CmsEngineDeployer.deployEngine(ctx, pkg)
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, res.lines().firstOrNull() ?: res, Toast.LENGTH_LONG).show()
                }
            }
        }.onFailure {
            Toast.makeText(ctx, "CMS引擎导入失败：${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    var section by remember { mutableStateOf("modules") }
    var modules by remember { mutableStateOf(repo.load()) }
    var auths by remember { mutableStateOf(storage.listAuths()) }
    var caps by remember { mutableStateOf(repo.loadCapabilities()) }
    var showAdd by remember { mutableStateOf(false) }
    var infoModule by remember { mutableStateOf<QuroCmsModule?>(null) }
    var callPair by remember { mutableStateOf<Pair<QuroCmsModule, QuroCmsCapability>?>(null) }
    var showAudit by remember { mutableStateOf(false) }
    var showExportPicker by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<List<QuroCmsModule>>(emptyList()) }
    var pendingPerm by remember { mutableStateOf<QuroCmsPermission?>(null) }
    var permDeferred = remember { mutableStateOf<CompletableDeferred<AuthorizationLevel?>?>(null) }

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

    // 模块导出 / 导入（SAF）
    val exportModulesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val list = if (pendingExport.isNotEmpty()) pendingExport else modules
        runCatching {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(repo.exportModules(list).toByteArray()) }
            refresh()
        }.onFailure {
            Toast.makeText(ctx, "模块导出失败：${it.message}", Toast.LENGTH_LONG).show()
        }
    }
    val importModulesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return@runCatching
            val n = repo.importModules(text)
            Toast.makeText(ctx, if (n > 0) "已导入 $n 个模块" else "未识别到有效的 cms.io/v2 模块", Toast.LENGTH_LONG).show()
            refresh()
        }.onFailure {
            Toast.makeText(ctx, "模块导入失败：${it.message}", Toast.LENGTH_LONG).show()
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
                    engineStore = engineStore,
                    busyDeployEngine = busyDeployEngine,
                    onDeployEngine = { deployEngine() },
                    onImportEngine = { importEngineLauncher.launch(arrayOf("application/json")) },
                    onExportEngine = { exportEngine() },
                    onAdd = { showAdd = true },
                    onInfo = { infoModule = it },
                    onUninstall = { repo.uninstall(it.id); refresh() },
                    onDeployed = { modules = repo.load() },
                    onExport = { showExportPicker = true },
                    onImport = { importModulesLauncher.launch(arrayOf("application/json")) },
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
    // 导出模块选择（#912）：弹窗勾选要导出的模块，再经 SAF 存盘，不再一股脑导出全部
    if (showExportPicker) {
        var selected by remember(showExportPicker) { mutableStateOf(modules.map { it.id }.toSet()) }
        AlertDialog(
            onDismissRequest = { showExportPicker = false },
            confirmButton = {
                Button(onClick = {
                    val pick = modules.filter { it.id in selected }
                    showExportPicker = false
                    if (pick.isEmpty()) {
                        Toast.makeText(ctx, "未选择任何模块", Toast.LENGTH_SHORT).show()
                    } else {
                        pendingExport = pick
                        exportModulesLauncher.launch("cms-modules-${pick.size}.json")
                    }
                }) { Text("导出选中 (${selected.size})") }
            },
            dismissButton = { TextButton(onClick = { showExportPicker = false }) { Text("取消") } },
            title = { Text("选择要导出的模块") },
            text = {
                if (modules.isEmpty()) {
                    Text("当前没有可导出的模块。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(modules) { m ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { selected = if (m.id in selected) selected - m.id else selected + m.id }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = m.id in selected, onCheckedChange = { selected = if (it) selected + m.id else selected - m.id })
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(m.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(m.id, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            },
        )
    }
}

// ---------------- 模块 ----------------

/** 按能力 runOn 推导模块所属宿主分组：app(手机) / terminal(终端) / dual(双端)。 */
private fun cmsHostCategory(m: QuroCmsModule): String {
    val hasApp = m.capabilities.any { it.runOn.contains(RuntimeHost.APP) }
    val hasTerm = m.capabilities.any { it.runOn.contains(RuntimeHost.TERMINAL) }
    return when {
        hasTerm && hasApp -> "dual"
        hasTerm -> "terminal"
        else -> "app"
    }
}

/** 宿主分组中文标签（用于卡片徽标）。 */
private fun cmsHostLabel(m: QuroCmsModule): String = when (cmsHostCategory(m)) {
    "terminal" -> "🖥 终端"
    "dual" -> "🔁 双端"
    else -> "📱 手机"
}

@Composable
private fun ModulesSection(
    modules: List<QuroCmsModule>,
    store: CmsStateStore.Snapshot,
    engineStore: CmsEngineStore.EngineSnapshot,
    busyDeployEngine: Boolean,
    onDeployEngine: () -> Unit,
    onImportEngine: () -> Unit,
    onExportEngine: () -> Unit,
    onAdd: () -> Unit,
    onInfo: (QuroCmsModule) -> Unit,
    onUninstall: (QuroCmsModule) -> Unit,
    onDeployed: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    val ctx = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var busyOneClick by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        // ---- CMS引擎状态卡（一级部署单元，区别于模块）----
        val es = engineStore
        val (eLabel, eColor) = when {
            es.deploying -> "● 部署中…" to MaterialTheme.colorScheme.primary
            es.ready && es.health -> "● 引擎已就绪" to MaterialTheme.colorScheme.primary
            es.ready && !es.health -> "● 已部署（健康检查异常）" to MaterialTheme.colorScheme.error
            es.lastError.isNotBlank() -> "⛔ 部署失败" to MaterialTheme.colorScheme.error
            else -> "○ 未部署引擎" to MaterialTheme.colorScheme.onSurfaceVariant
        }
        var showEngineScripts by remember { mutableStateOf(false) }
        var editingBootstrap by remember { mutableStateOf("") }
        var editingProvisioner by remember { mutableStateOf("") }
        var isEditingBootstrap by remember { mutableStateOf(false) }
        var isEditingProvisioner by remember { mutableStateOf(false) }
        val enginePackage = remember { CmsEnginePackage.builtin() }

        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface).border(1.dp, Line, RoundedCornerShape(12.dp))) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("🔧 CMS引擎", style = MaterialTheme.typography.bodyLarge)
                    Text("${es.engineVersion.ifBlank { "-" }} · ${es.services.size} 个共享服务", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(eLabel, style = MaterialTheme.typography.labelSmall, color = eColor)
                    if (es.lastError.isNotBlank())
                        Text(es.lastError, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 2)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                PrimaryButton(text = if (busyDeployEngine) "部署中…" else "部署CMS引擎", modifier = Modifier.fillMaxWidth(), enabled = !busyDeployEngine, onClick = onDeployEngine)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onImportEngine, modifier = Modifier.weight(1f)) { Text("导入CMS引擎") }
                OutlinedButton(onClick = onExportEngine, modifier = Modifier.weight(1f)) { Text("导出CMS引擎") }
            }
            if (es.services.isNotEmpty()) {
                Text("共享服务：${es.services.joinToString(", ")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
            }

            // 查看/编辑脚本按钮
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                OutlinedButton(
                    onClick = { showEngineScripts = !showEngineScripts },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        if (showEngineScripts) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (showEngineScripts) "收起脚本" else "查看/编辑引擎脚本")
                }
            }

            // 脚本编辑区
            if (showEngineScripts) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(8.dp)
                ) {
                    // Bootstrap 脚本
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Bootstrap 引导脚本", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row {
                            IconButton(
                                onClick = {
                                    val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("Bootstrap脚本", enginePackage.bootstrapContent)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(ctx, "脚本已复制", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Filled.ContentCopy, "复制", modifier = Modifier.size(16.dp))
                            }
                            IconButton(
                                onClick = {
                                    isEditingBootstrap = !isEditingBootstrap
                                    editingBootstrap = enginePackage.bootstrapContent
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    if (isEditingBootstrap) Icons.Filled.Save else Icons.Filled.Edit,
                                    if (isEditingBootstrap) "保存" else "编辑",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                    if (isEditingBootstrap) {
                        OutlinedTextField(
                            value = editingBootstrap,
                            onValueChange = { editingBootstrap = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 150.dp, max = 300.dp),
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                    } else {
                        Text(
                            enginePackage.bootstrapContent,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    // Provisioner 脚本
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Provisioner 环境脚本", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row {
                            IconButton(
                                onClick = {
                                    val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("Provisioner脚本", enginePackage.provisionerContent)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(ctx, "脚本已复制", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Filled.ContentCopy, "复制", modifier = Modifier.size(16.dp))
                            }
                            IconButton(
                                onClick = {
                                    isEditingProvisioner = !isEditingProvisioner
                                    editingProvisioner = enginePackage.provisionerContent
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    if (isEditingProvisioner) Icons.Filled.Save else Icons.Filled.Edit,
                                    if (isEditingProvisioner) "保存" else "编辑",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                    if (isEditingProvisioner) {
                        OutlinedTextField(
                            value = editingProvisioner,
                            onValueChange = { editingProvisioner = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 100.dp, max = 200.dp),
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                    } else {
                        Text(
                            enginePackage.provisionerContent,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 150.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(text = "添加模块", modifier = Modifier.weight(1f), onClick = onAdd)
            OutlinedButton(
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
                modifier = Modifier.weight(1f),
                enabled = !busyOneClick,
            ) { Text(if (busyOneClick) "部署中…" else "一键部署到终端") }
        }
        // 模块导入 / 导出（SAF）
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f)) { Text("导出模块") }
            OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f)) { Text("导入模块") }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "💡 CMS 模块部署到应用内 proot/Ubuntu Linux 沙箱运行。首次请先在「终端」页安装 Linux 环境（约需联网下载 30MB）；部署时按需 apt-get install 基础包。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        var showAddGuide by remember { mutableStateOf(false) }
        if (modules.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text("还没有模块。点上方「添加模块」创建一个能力模块（cms.io/v2）。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showAddGuide = true }, modifier = Modifier.weight(1f)) { Text("查看添加说明") }
                    Button(onClick = {
                        val r = QuroDownloadUtil.saveTextToDownloads(ctx, "CMSv2_添加模块说明.md", "text/markdown", CMS_ADD_MODULE_GUIDE)
                        Toast.makeText(ctx, if (r.startsWith("OK:")) "已保存说明到 Download/Quro/" else r, Toast.LENGTH_LONG).show()
                    }, modifier = Modifier.weight(1f)) { Text("下载说明") }
                }
            }
        }
        if (showAddGuide) {
            AlertDialog(
                onDismissRequest = { showAddGuide = false },
                confirmButton = { TextButton(onClick = { showAddGuide = false }) { Text("知道了") } },
                title = { Text("怎么添加 CMS v2 模块") },
                text = {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                        Text(CMS_ADD_MODULE_GUIDE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
            )
        }
        // 按宿主（手机 / 终端 / 双端）分组渲染，落实「手机模块与终端模块分开」
        val groups = listOf(
            "app" to "📱 手机模块（应用内执行）",
            "terminal" to "🖥 终端模块（proot 内执行）",
            "dual" to "🔁 双端模块（手机 / 终端皆可）",
        )
        val byCat = modules.groupBy { cmsHostCategory(it) }
        groups.forEach { (cat, title) ->
            val grpList = byCat[cat].orEmpty()
            if (grpList.isNotEmpty()) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                grpList.forEach { m ->
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
                        val (bLabel, bColor) = when (cmsHostCategory(m)) {
                            "terminal" -> "🖥 终端" to Color(0xFFE6794A)
                            "dual" -> "🔁 双端" to Color(0xFF7A5CFF)
                            else -> "📱 手机" to Color(0xFF34C759)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(m.name, style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.width(6.dp))
                            Box(Modifier.background(bColor.copy(alpha = 0.15f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                                Text(bLabel, style = MaterialTheme.typography.labelSmall, color = bColor)
                            }
                        }
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
                    OutlinedButton(
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

                // 终端模块脚本查看/编辑区
                if (m.terminalEntry.isNotBlank()) {
                    var showModuleScript by remember { mutableStateOf(false) }
                    var editingModuleEntry by remember { mutableStateOf("") }
                    var isEditingModuleEntry by remember { mutableStateOf(false) }

                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showModuleScript = !showModuleScript },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                if (showModuleScript) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(if (showModuleScript) "收起 entry.sh" else "查看/编辑 entry.sh")
                        }
                    }

                    if (showModuleScript) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("entry.sh 脚本", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row {
                                    IconButton(
                                        onClick = {
                                            val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            val clip = ClipData.newPlainText("entry.sh", m.terminalEntry)
                                            clipboard.setPrimaryClip(clip)
                                            Toast.makeText(ctx, "脚本已复制", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Filled.ContentCopy, "复制", modifier = Modifier.size(16.dp))
                                    }
                                    IconButton(
                                        onClick = {
                                            isEditingModuleEntry = !isEditingModuleEntry
                                            editingModuleEntry = m.terminalEntry
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            if (isEditingModuleEntry) Icons.Filled.Save else Icons.Filled.Edit,
                                            if (isEditingModuleEntry) "保存" else "编辑",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                            if (isEditingModuleEntry) {
                                OutlinedTextField(
                                    value = editingModuleEntry,
                                    onValueChange = { editingModuleEntry = it },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 100.dp, max = 250.dp),
                                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                )
                            } else {
                                Text(
                                    m.terminalEntry,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 200.dp)
                                        .verticalScroll(rememberScrollState())
                                )
                            }
                        }
                    }
                }
            }
            // 终端已部署但不在模块库的插件（如 demo-py / 外部导入包）——对            }
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
                    Text("• ${c.id} [${c.actionType}] ${c.runOn.joinToString("/") { it.label }}：${c.summary}", style = MaterialTheme.typography.bodySmall)
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
                        val tgt = d.spec.ifBlank { d.capability }
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
                            dependencies = deps,
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
                        Text("宿主：${c.runOn.joinToString("/") { it.label }}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
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
                    "terminal" -> "终端命令（应用内 proot/Ubuntu 沙箱）"
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
