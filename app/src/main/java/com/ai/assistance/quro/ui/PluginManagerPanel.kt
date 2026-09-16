@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.ai.assistance.quro.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.core.plugin.QuroPluginHost
import com.ai.assistance.quro.plugin.engine.bridge.HostToolBridge
import com.ai.assistance.quro.plugin.engine.install.PluginRecord
import com.ai.assistance.quro.ui.theme.Muted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ★ 插件桌面（启动器样式）—— APK 级插件框架的主界面。
 *
 * 交互对齐手机桌面启动器（Launcher）：
 * - 顶部：状态条（引擎就绪 / 已装 / 已加载 / 贡献 AI 工具数）
 * - 搜索框：按插件名 / 包名 / 贡献的 AI 工具名过滤
 * - 图标网格：4 列，图标取插件 APK 自带的 launcher 图标（取不到则回退首字母色块）
 * - **单击**图标：有自带界面就直接打开该界面；没有则弹详情面板
 * - **长按**图标：弹出快捷菜单（打开界面 / 详情 / 重载 / 卸载）
 * - 底部 Dock：导入 APK / 装内置插件 / 全部插件工具 / 刷新
 *
 * 入口：对话页「插件」按钮 / `ui_open_plugins` / 工具中心「插件」卡片。
 */
@Composable
fun PluginManagerScreen(onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Text("←", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface)
                }
                Text(
                    "插件桌面",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 4.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
            }
        }
        PluginLauncherBody(LocalContext.current)
    }
}

/** 工具中心内嵌版（无自带标题栏） */
@Composable
fun PluginManagerPanel(context: Context) = PluginLauncherBody(context)

// ═══════════════════════════ 启动器主体 ═══════════════════════════

@Composable
private fun PluginLauncherBody(context: Context) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    var records by remember { mutableStateOf<List<PluginRecord>>(emptyList()) }
    var loadedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var extOf by remember { mutableStateOf<Map<String, Map<String, Int>>>(emptyMap()) }
    var toolsOf by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var surfacesOf by remember { mutableStateOf<Map<String, List<Pair<String, String>>>>(emptyMap()) }
    var allToolSpecs by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) } // name → desc
    var builtinCount by remember { mutableStateOf(0) }
    var ready by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var detailOf by remember { mutableStateOf<PluginRecord?>(null) }   // 详情面板
    var menuOf by remember { mutableStateOf<PluginRecord?>(null) }     // 长按快捷菜单
    var showToolList by remember { mutableStateOf(false) }             // 全部插件工具

    suspend fun refresh() {
        val snap = withContext(Dispatchers.IO) {
            val recs = runCatching { QuroPluginHost.installed() }.getOrElse { emptyList() }
            val loaded = runCatching { QuroPluginHost.loadedIds().toSet() }.getOrDefault(emptySet())
            val exts = recs.associate { r ->
                r.pluginId to runCatching { QuroPluginHost.extensionSummary(r.pluginId) }.getOrDefault(emptyMap())
            }
            val specs = runCatching { HostToolBridge.collectToolSpecs() }.getOrElse { emptyList() }
            val tools = recs.associate { r ->
                r.pluginId to specs.filter { s ->
                    runCatching {
                        com.ai.assistance.quro.plugin.engine.registry.ExtensionRegistry
                            .pluginIdOf(com.ai.assistance.quro.plugin.extension.ExtensionType.AI_TOOL, s.name) == r.pluginId
                    }.getOrDefault(false)
                }.map { it.name }
            }
            val surfaces = recs.associate { r ->
                r.pluginId to runCatching {
                    HostToolBridge.uiSurfaces()
                        .filter { HostToolBridge.pluginIdOfSurface(it.id) == r.pluginId }
                        .map { it.id to it.label }
                }.getOrDefault(emptyList())
            }
            val builtin = runCatching {
                (context.assets.list("plugins") ?: emptyArray()).count { it.endsWith(".apk") }
            }.getOrDefault(0)
            LauncherSnapshot(
                records = recs,
                loadedIds = loaded,
                extOf = exts,
                toolsOf = tools,
                surfacesOf = surfaces,
                allToolSpecs = specs.map { it.name to it.description },
                builtinCount = builtin,
            )
        }
        records = snap.records
        loadedIds = snap.loadedIds
        extOf = snap.extOf
        toolsOf = snap.toolsOf
        surfacesOf = snap.surfacesOf
        allToolSpecs = snap.allToolSpecs
        builtinCount = snap.builtinCount
        ready = runCatching { QuroPluginHost.isReady() }.getOrDefault(false)
    }

    LaunchedEffect(Unit) { refresh() }

    // 选 APK → 拷到私有缓存 → 交给引擎安装（走同签名校验）
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // 文件名从 SAF URI 推出来，可能根本没有 .apk 后缀
                    // （不少 provider 的 lastPathSegment 是纯数字 id），补上再落盘。
                    val raw = uri.lastPathSegment?.substringAfterLast('/')?.takeWhile { it != '?' }
                    val name = (raw ?: "plugin.apk")
                        .let { if (it.endsWith(".apk", ignoreCase = true)) it else "$it.apk" }
                    val tmp = File(context.cacheDir, name)
                    context.contentResolver.openInputStream(uri)?.use { i ->
                        tmp.outputStream().use { o -> i.copyTo(o) }
                    } ?: return@runCatching "无法读取所选文件"
                    val r = QuroPluginHost.install(tmp)
                    tmp.delete()
                    if (r.success) "安装成功：${r.pluginId}" else "安装失败：${r.message}"
                }.getOrElse { "安装异常：${it.message}" }
            }
            log = result
            busy = false
            Toast.makeText(context, result, Toast.LENGTH_LONG).show()
            refresh()
        }
    }

    val shown = records.filter { rec ->
        query.isBlank() ||
            rec.name.contains(query, true) ||
            rec.pluginId.contains(query, true) ||
            (toolsOf[rec.pluginId] ?: emptyList()).any { it.contains(query, true) }
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            LauncherStatusBar(
                ready = ready,
                installed = records.size,
                loaded = loadedIds.size,
                toolCount = allToolSpecs.size,
                builtinCount = builtinCount,
            )

            if (!ready) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "⚠ 插件引擎未初始化（Application 启动阶段未执行到 QuroPluginHost.attach）。" +
                        "下面的操作用不了，请把此现象连同 logcat 里 QuroApplication / QuroPluginHost 的报错一起反馈。",
                    fontSize = 12.sp, color = cs.error,
                )
            }

            if (log.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(log, fontSize = 12.sp, color = cs.primary, fontFamily = FontFamily.Monospace)
            }

            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                placeholder = { Text("搜索插件 / 包名 / AI 工具", fontSize = 13.sp, color = Muted) },
            )

            Spacer(Modifier.height(14.dp))

            if (shown.isEmpty()) {
                LauncherEmptyState(
                    query = query,
                    hasAny = records.isNotEmpty(),
                    hasBuiltin = builtinCount > 0,
                    onInstallBuiltin = {
                        busy = true
                        scope.launch {
                            val out = withContext(Dispatchers.IO) { installBuiltin(context) }
                            log = out
                            busy = false
                            Toast.makeText(context, out.lineSequence().lastOrNull() ?: "完成", Toast.LENGTH_LONG).show()
                            refresh()
                        }
                    },
                    onImport = { picker.launch(arrayOf("*/*")) },
                )
            } else {
                // 4 列图标网格（手动分块，避免 LazyVerticalGrid 在嵌套容器里退化）
                shown.chunked(4).forEach { rowItems ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        rowItems.forEach { rec ->
                            LauncherIconCell(
                                rec = rec,
                                loaded = rec.pluginId in loadedIds,
                                surfaceCount = surfacesOf[rec.pluginId]?.size ?: 0,
                                toolCount = toolsOf[rec.pluginId]?.size ?: 0,
                                modifier = Modifier.weight(1f),
                                onTap = {
                                    val surfaces = surfacesOf[rec.pluginId].orEmpty()
                                    if (surfaces.isNotEmpty()) {
                                        openSurface(context, surfaces.first().first, surfaces.first().second)
                                    } else {
                                        detailOf = rec
                                    }
                                },
                                onLongPress = { menuOf = rec },
                            )
                        }
                        // 补空格，保证最后一行也保持 4 列对齐
                        repeat(4 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
        }

        LauncherDock(
            busy = busy,
            toolCount = allToolSpecs.size,
            builtinCount = builtinCount,
            enabled = ready && !busy,
            onImport = { picker.launch(arrayOf("*/*")) },
            onInstallBuiltin = {
                busy = true
                scope.launch {
                    val out = withContext(Dispatchers.IO) { installBuiltin(context) }
                    log = out
                    busy = false
                    Toast.makeText(context, out.lineSequence().lastOrNull() ?: "完成", Toast.LENGTH_LONG).show()
                    refresh()
                }
            },
            onToolList = { showToolList = true },
            onRefresh = {
                scope.launch {
                    refresh()
                    Toast.makeText(context, "已刷新", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    // ───── 详情面板 ─────
    detailOf?.let { rec ->
        PluginDetailSheet(
            rec = rec,
            loaded = rec.pluginId in loadedIds,
            exts = extOf[rec.pluginId].orEmpty(),
            tools = toolsOf[rec.pluginId].orEmpty(),
            surfaces = surfacesOf[rec.pluginId].orEmpty(),
            onDismiss = { detailOf = null },
            onReload = {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { QuroPluginHost.reload(rec.pluginId) }
                    log = if (ok) "已重载：${rec.pluginId}" else "重载失败：${rec.pluginId}"
                    detailOf = null
                    Toast.makeText(context, log, Toast.LENGTH_SHORT).show()
                    refresh()
                }
            },
            onUninstall = {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { QuroPluginHost.uninstall(rec.pluginId) }
                    log = if (ok) "已卸载：${rec.pluginId}" else "卸载失败：${rec.pluginId}"
                    detailOf = null
                    Toast.makeText(context, log, Toast.LENGTH_SHORT).show()
                    refresh()
                }
            },
            onOpenSurface = { sid, label -> detailOf = null; openSurface(context, sid, label) },
        )
    }

    // ───── 长按快捷菜单 ─────
    menuOf?.let { rec ->
        val surfaces = surfacesOf[rec.pluginId].orEmpty()
        ModalBottomSheet(onDismissRequest = { menuOf = null }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PluginIconBox(rec, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(rec.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(rec.pluginId, fontSize = 11.sp, color = Muted, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        if (rec.pluginId in loadedIds) "已加载" else "未加载",
                        fontSize = 11.sp,
                        color = if (rec.pluginId in loadedIds) cs.primary else cs.error,
                    )
                }
                Spacer(Modifier.height(12.dp))
                if (surfaces.isNotEmpty()) {
                    ListItem(
                        headlineContent = { Text("打开界面") },
                        supportingContent = { Text(surfaces.joinToString("、") { it.second }) },
                        modifier = Modifier.combinedClickable { openSurface(context, surfaces.first().first, surfaces.first().second); menuOf = null },
                    )
                }
                ListItem(
                    headlineContent = { Text("详情") },
                    supportingContent = { Text("扩展点 / AI 工具 / 版本信息") },
                    modifier = Modifier.combinedClickable { detailOf = rec; menuOf = null },
                )
                ListItem(
                    headlineContent = { Text("重载") },
                    supportingContent = { Text("重新实例化插件入口，改完插件立刻生效") },
                    modifier = Modifier.combinedClickable {
                        menuOf = null
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { QuroPluginHost.reload(rec.pluginId) }
                            Toast.makeText(context, if (ok) "已重载：${rec.pluginId}" else "重载失败", Toast.LENGTH_SHORT).show()
                            refresh()
                        }
                    },
                )
                ListItem(
                    headlineContent = { Text("卸载", color = cs.error) },
                    supportingContent = { Text("连同它贡献的 AI 工具 / ACI 能力 / 界面一起移除") },
                    modifier = Modifier.combinedClickable {
                        menuOf = null
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { QuroPluginHost.uninstall(rec.pluginId) }
                            Toast.makeText(context, if (ok) "已卸载：${rec.pluginId}" else "卸载失败", Toast.LENGTH_SHORT).show()
                            refresh()
                        }
                    },
                )
            }
        }
    }

    // ───── 全部插件工具 ─────
    if (showToolList) {
        ModalBottomSheet(onDismissRequest = { showToolList = false }) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)
                    .heightIn(max = 520.dp).verticalScroll(rememberScrollState())
            ) {
                Text("插件贡献的 AI 工具（${allToolSpecs.size}）", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "这些工具已直接进入 AI 的工具集，对话框里直接说需求即可（例如「查一下 SF1234567890」→ express_query）。" +
                        "若某个工具没生效，AI 也能用 apk_plugin(action=\"call\", name=\"工具名\") 兜底调用。",
                    fontSize = 12.sp, color = Muted,
                )
                Spacer(Modifier.height(12.dp))
                if (allToolSpecs.isEmpty()) {
                    Text("暂无插件工具。先装几个插件吧。", fontSize = 13.sp, color = Muted)
                }
                allToolSpecs.forEach { (n, d) ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(n, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = cs.primary)
                        Text(d, fontSize = 12.sp, color = Muted)
                    }
                    HorizontalDivider(color = cs.outlineVariant)
                }
            }
        }
    }

    // ───── 内置插件包清单 ─────
}

/** 插件桌面的一次性快照（在 IO 线程组装，避免组合期反复查引擎） */
private data class LauncherSnapshot(
    val records: List<PluginRecord>,
    val loadedIds: Set<String>,
    val extOf: Map<String, Map<String, Int>>,
    val toolsOf: Map<String, List<String>>,
    val surfacesOf: Map<String, List<Pair<String, String>>>,
    val allToolSpecs: List<Pair<String, String>>,
    val builtinCount: Int,
)

// ═══════════════════════════ 组件 ═══════════════════════════

/** 顶部状态条：像启动器的「状态行」，一眼看全框架健康度 */
@Composable
private fun LauncherStatusBar(ready: Boolean, installed: Int, loaded: Int, toolCount: Int, builtinCount: Int) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(8.dp).clip(CircleShape)
                        .background(if (ready) cs.primary else cs.error)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (ready) "插件引擎就绪" else "插件引擎未初始化",
                    fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.onSurface,
                )
                Spacer(Modifier.weight(1f))
                Text("DexClassLoader", fontSize = 11.sp, color = Muted, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                StatCell("已安装", installed.toString(), Modifier.weight(1f))
                StatCell("已加载", loaded.toString(), Modifier.weight(1f))
                StatCell("AI 工具", toolCount.toString(), Modifier.weight(1f))
                StatCell("内置包", builtinCount.toString(), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        Text(label, fontSize = 11.sp, color = Muted)
    }
}

/** 单个桌面图标格：图标 + 名称 + 角标 */
@Composable
private fun LauncherIconCell(
    rec: PluginRecord,
    loaded: Boolean,
    surfaceCount: Int,
    toolCount: Int,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier.padding(horizontal = 4.dp)
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            PluginIconBox(rec, 58.dp)
            // 未加载角标
            if (!loaded) {
                Box(
                    Modifier.align(Alignment.TopEnd).size(10.dp).clip(CircleShape).background(cs.error)
                )
            }
            // 有界面/工具时右下角显示数量小标
            if (surfaceCount > 0 || toolCount > 0) {
                Box(
                    Modifier.align(Alignment.BottomEnd)
                        .clip(RoundedCornerShape(6.dp))
                        .background(cs.primary)
                        .padding(horizontal = 4.dp)
                ) {
                    Text(
                        if (surfaceCount > 0) "$surfaceCount 界面" else "$toolCount 工具",
                        fontSize = 8.sp, color = cs.onPrimary,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            rec.name,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = cs.onSurface,
        )
    }
}

/** 插件图标：优先用插件 APK 自带的 launcher 图标，取不到回退首字母渐变色块 */
@Composable
private fun PluginIconBox(rec: PluginRecord, size: androidx.compose.ui.unit.Dp) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val icon by remember(rec.pluginId, rec.apkPath) {
        mutableStateOf(loadPluginIcon(context, rec.apkPath))
    }
    val gradient = remember(rec.pluginId) { gradientFor(rec.pluginId) }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size / 4))
            .background(Brush.linearGradient(gradient)),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            androidx.compose.foundation.Image(
                bitmap = icon!!,
                contentDescription = rec.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(size / 8),
            )
        } else {
            Text(
                rec.name.ifBlank { rec.pluginId }.take(1).uppercase(),
                fontSize = (size.value / 2.2f).sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
        // 未加载整体压暗，像桌面里「禁用」的应用
        if (!QuroPluginHost.isLoaded(rec.pluginId)) {
            Box(Modifier.fillMaxSize().background(cs.surface.copy(alpha = 0.45f)))
        }
    }
}

/** 空态：没插件时像桌面的「添加应用」引导 */
@Composable
private fun LauncherEmptyState(
    query: String,
    hasAny: Boolean,
    hasBuiltin: Boolean,
    onInstallBuiltin: () -> Unit,
    onImport: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val hasQuery = query.isNotBlank()
    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (hasQuery) "🔍" else "🧩", fontSize = 40.sp)
        Spacer(Modifier.height(10.dp))
        Text(
            when {
                hasQuery -> "没有匹配「$query」的插件"
                hasAny -> "没有匹配的插件"
                else -> "插件桌面还是空的"
            },
            fontSize = 15.sp, color = cs.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (hasQuery) "换个关键词试试，也可以搜插件贡献的 AI 工具名。"
            else "插件是独立 APK，装上就能给 AI 加能力：AI 工具 / ACI 能力 / 斜杠指令 / 自带界面，宿主不用改代码。",
            fontSize = 12.sp, color = Muted, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (hasBuiltin) Button(onClick = onInstallBuiltin) { Text("一键安装内置插件") }
            OutlinedButton(onClick = onImport) { Text("导入插件 APK") }
        }
        if (hasBuiltin) {
            Spacer(Modifier.height(12.dp))
            Text(
                "装完可以试试：对话框里问「帮我查快递 SF1234567890」，AI 会调用插件新增的工具。",
                fontSize = 11.sp, color = Muted, textAlign = TextAlign.Center,
            )
        }
    }
}

/** 底部 Dock：导入 / 内置 / 工具清单 / 刷新 */
@Composable
private fun LauncherDock(
    busy: Boolean,
    toolCount: Int,
    builtinCount: Int,
    enabled: Boolean,
    onImport: () -> Unit,
    onInstallBuiltin: () -> Unit,
    onToolList: () -> Unit,
    onRefresh: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.surface, tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            DockItem("导入 APK", "📥", enabled, onImport)
            DockItem("内置插件${if (builtinCount > 0) " $builtinCount" else ""}", "🎁", enabled, onInstallBuiltin)
            DockItem("插件工具 $toolCount", "🧠", true, onToolList)
            DockItem("刷新", "⟳", !busy, onRefresh)
        }
    }
}

@Composable
private fun DockItem(label: String, emoji: String, enabled: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).combinedClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(emoji, fontSize = 18.sp)
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            fontSize = 10.sp,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else Muted,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 插件详情底部面板 */
@Composable
private fun PluginDetailSheet(
    rec: PluginRecord,
    loaded: Boolean,
    exts: Map<String, Int>,
    tools: List<String>,
    surfaces: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onReload: () -> Unit,
    onUninstall: () -> Unit,
    onOpenSurface: (String, String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 26.dp)
                .heightIn(max = 560.dp).verticalScroll(rememberScrollState())
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PluginIconBox(rec, 48.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(rec.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(rec.pluginId, fontSize = 11.sp, color = Muted, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                AssistChip(onClick = {}, label = { Text(if (loaded) "已加载" else "未加载", fontSize = 11.sp) })
            }

            Spacer(Modifier.height(12.dp))
            Text("v${rec.versionName} (${rec.versionCode})", fontSize = 12.sp, color = Muted)
            Text("入口：${rec.entryClass}", fontSize = 11.sp, color = Muted, fontFamily = FontFamily.Monospace)
            Text("安装于：${formatTime(rec.installedAt)}", fontSize = 11.sp, color = Muted)

            if (exts.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text("扩展点", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
                Spacer(Modifier.height(6.dp))
                exts.entries.forEach { (k, v) ->
                    Text("· ${extLabel(k)} × $v", fontSize = 12.sp, color = cs.onSurface)
                }
            }

            Spacer(Modifier.height(14.dp))
            Text("贡献的 AI 工具（${tools.size}）", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
            Spacer(Modifier.height(6.dp))
            if (tools.isEmpty()) {
                Text("无", fontSize = 12.sp, color = Muted)
            } else {
                tools.forEach { Text("· $it", fontSize = 12.sp, color = cs.primary, fontFamily = FontFamily.Monospace) }
            }

            if (surfaces.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text("自带界面（${surfaces.size}）", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
                Spacer(Modifier.height(6.dp))
                surfaces.forEach { (sid, label) ->
                    TextButton(onClick = { onOpenSurface(sid, label) }) { Text("打开 $label") }
                }
            }

            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { if (surfaces.isNotEmpty()) onOpenSurface(surfaces.first().first, surfaces.first().second) }, enabled = surfaces.isNotEmpty()) {
                    Text("打开界面")
                }
                OutlinedButton(onClick = onReload) { Text("重载") }
                OutlinedButton(onClick = onUninstall) { Text("卸载", color = cs.error) }
            }
        }
    }
}

// ═══════════════════════════ 辅助 ═══════════════════════════

private fun openSurface(context: Context, surfaceId: String, label: String) {
    runCatching {
        context.startActivity(PluginSurfaceActivity.intent(context, surfaceId, label))
    }.onFailure {
        Toast.makeText(context, "打开失败：${it.message}", Toast.LENGTH_LONG).show()
    }
}

/** 安装宿主 assets/plugins/ 下随包内置的示例插件 */
private fun installBuiltin(context: Context): String {
    val names = runCatching { context.assets.list("plugins")?.toList() }.getOrNull().orEmpty()
        .filter { it.endsWith(".apk") }
    if (names.isEmpty()) return "宿主未内置示例插件"
    val sb = StringBuilder()
    names.forEach { n ->
        val tmp = File(context.cacheDir, "builtin_$n")
        runCatching {
            context.assets.open("plugins/$n").use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            val r = QuroPluginHost.install(tmp)
            sb.appendLine(if (r.success) "内置插件安装成功：${r.pluginId}" else "内置插件安装失败：${r.message}")
        }.onFailure { sb.appendLine("内置插件安装异常：${it.message}") }
        tmp.delete()
    }
    return sb.toString().trim()
}

/** 读插件 APK 自带的 launcher 图标（读不到返回 null，界面回退首字母色块） */
private fun loadPluginIcon(context: Context, apkPath: String): ImageBitmap? = runCatching {
    if (apkPath.isBlank() || !File(apkPath).exists()) return null
    val pm = context.packageManager
    @Suppress("DEPRECATION")
    val info = pm.getPackageArchiveInfo(apkPath, 0) ?: return null
    val ai = info.applicationInfo ?: return null
    ai.sourceDir = apkPath
    ai.publicSourceDir = apkPath
    val d = ai.loadIcon(pm) ?: return null
    val bmp = if (d is BitmapDrawable) {
        d.bitmap
    } else {
        val w = d.intrinsicWidth.coerceAtLeast(1)
        val h = d.intrinsicHeight.coerceAtLeast(1)
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            d.setBounds(0, 0, w, h)
            d.draw(Canvas(it))
        }
    }
    bmp.asImageBitmap()
}.getOrNull()

/** 由插件包名稳定派生一组渐变色（同名永远同色，像桌面图标配色） */
private fun gradientFor(seed: String): List<Color> {
    val palette = listOf(
        Color(0xFF5B7CFA), Color(0xFF31C27C), Color(0xFFF2994A), Color(0xFFEB5757),
        Color(0xFF9B51E0), Color(0xFF2D9CDB), Color(0xFFF2C94C), Color(0xFF00B8A9),
    )
    val h = seed.hashCode()
    val a = palette[Math.floorMod(h, palette.size)]
    val b = palette[Math.floorMod(h / palette.size + 3, palette.size)]
    return listOf(a, b)
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "未知"
    return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(ms))
}

private fun extLabel(type: String): String = when (type) {
    "AI_TOOL" -> "AI 工具"
    "ACI_CAPABILITY" -> "ACI 能力"
    "CHAT_CARD" -> "对话卡片"
    "UI_WIDGET" -> "内联组件"
    "UI_SURFACE" -> "界面"
    "MODEL_PROVIDER" -> "模型接入"
    "RAG_SOURCE" -> "知识源"
    "COMMAND" -> "斜杠指令"
    "SETTING" -> "设置项"
    "SCHEDULE_TASK" -> "定时任务"
    "CHANNEL" -> "消息渠道"
    "FILE_HANDLER" -> "文件处理"
    "CODE_RUNTIME" -> "代码运行时"
    "SPEECH" -> "语音"
    else -> type
}
