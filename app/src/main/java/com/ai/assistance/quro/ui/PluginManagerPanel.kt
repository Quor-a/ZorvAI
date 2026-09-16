package com.ai.assistance.quro.ui

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.core.plugin.QuroPluginHost
import com.ai.assistance.quro.plugin.engine.install.PluginRecord
import com.ai.assistance.quro.ui.theme.Muted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 插件管理界面（带顶部栏的全屏版）。
 *
 * 用途：对话页「插件」入口 / `ui_open_plugins` / 工具中心「插件」卡片都落到这里。
 * 内容只有一层：[PluginManagerBody]。
 *
 * 之所以有「带栏」和「不带栏」两个入口函数：工具中心自己有标题栏，对话页是覆盖层需要自带返回。
 */
@Composable
fun PluginManagerScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
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
                    "插件",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 8.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
            }
        }
        PluginManagerBody(ctx)
    }
}

/** 工具中心内嵌版（无自带标题栏） */
@Composable
fun PluginManagerPanel(context: Context) = PluginManagerBody(context)

@Composable
private fun PluginManagerBody(context: Context) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf<List<PluginRecord>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf("") }
    var loadedCount by remember { mutableStateOf(0) }
    var toolCount by remember { mutableStateOf(0) }
    var ready by remember { mutableStateOf(true) }
    var hasBuiltin by remember { mutableStateOf(false) }
    var builtinCount by remember { mutableStateOf(0) }

    suspend fun refresh() {
        val snap = withContext(Dispatchers.IO) {
            val r = runCatching { QuroPluginHost.installed() }.getOrElse { emptyList() }
            Triple(
                r,
                runCatching { QuroPluginHost.loadedIds().size }.getOrDefault(0),
                runCatching { QuroPluginHost.toolSpecs().size }.getOrDefault(0),
            )
        }
        records = snap.first
        loadedCount = snap.second
        toolCount = snap.third
        ready = runCatching { QuroPluginHost.isReady() }.getOrDefault(false)
        val builtin = withContext(Dispatchers.IO) {
            runCatching { (context.assets.list("plugins") ?: emptyArray()).count { it.endsWith(".apk") } }
                .getOrDefault(0)
        }
        builtinCount = builtin
        hasBuiltin = builtin > 0
    }

    LaunchedEffect(Unit) { refresh() }

    // 选 APK → 拷到私有缓存 → 交给引擎安装（走同签名校验）
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = uri.lastPathSegment?.substringAfterLast('/')?.takeWhile { it != '?' } ?: "plugin.apk"
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

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // ---------- 状态卡 ----------
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("APK 级插件框架", style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (!ready) {
                        "⚠ 插件引擎未初始化（Application 启动阶段未执行到 attach）。下面的操作用不了，请把此现象连同 logcat 里 QuroApplication / QuroPluginHost 的报错一起反馈。"
                    } else {
                        "插件是独立 APK，宿主用 DexClassLoader 加载；插件注册「扩展点」给 AI 加工具，宿主不用改代码。\n\n" +
                            "已安装 ${records.size} 个 · 已加载 $loadedCount 个 · 当前贡献 AI 工具 $toolCount 个"
                    },
                    fontSize = 12.sp,
                    color = if (ready) Muted else cs.error,
                )
                if (log.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(log, fontSize = 12.sp, color = cs.primary, fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                refresh()
                                Toast.makeText(context, "已刷新", Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = !busy,
                    ) { Text("刷新") }
                    Button(
                        onClick = { picker.launch(arrayOf("*/*")) },
                        enabled = !busy && ready,
                    ) { Text("导入插件 APK") }
                }
                if (hasBuiltin) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            busy = true
                            scope.launch {
                                val out = withContext(Dispatchers.IO) { installBuiltin(context) }
                                log = out
                                busy = false
                                Toast.makeText(context, out.lineSequence().lastOrNull() ?: "完成", Toast.LENGTH_LONG).show()
                                refresh()
                            }
                        },
                        enabled = !busy && ready,
                    ) { Text("一键安装内置示例插件（$builtinCount 个）") }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        if (records.isEmpty()) {
            Text(
                "还没有安装任何插件。\n\n" +
                    if (hasBuiltin) "点上面「安装内置示例插件」即可立刻验证：装完插件会贡献 1 个 AI 工具（express_query）、" +
                        "1 个 ACI 能力（query_express）和一条斜杠指令（/express）。\n\n然后在对话框问「帮我查快递 SF1234567890」，AI 应该会调用它。"
                    else "点「导入插件 APK」选择一个插件包。插件必须与宿主同签名，否则会被拒绝安装" +
                        "（插件跑在宿主进程内、拥有同等权限，这是必需的安全边界）。",
                fontSize = 13.sp,
                color = Muted,
            )
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(records, key = { it.pluginId }) { rec ->
                PluginCard(
                    rec = rec,
                    loaded = QuroPluginHost.isLoaded(rec.pluginId),
                    exts = QuroPluginHost.extensionSummary(rec.pluginId),
                    onReload = {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { QuroPluginHost.reload(rec.pluginId) }
                            log = if (ok) "已重载：${rec.pluginId}" else "重载失败：${rec.pluginId}"
                            Toast.makeText(context, log, Toast.LENGTH_SHORT).show()
                            refresh()
                        }
                    },
                    onUninstall = {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { QuroPluginHost.uninstall(rec.pluginId) }
                            log = if (ok) "已卸载：${rec.pluginId}" else "卸载失败：${rec.pluginId}"
                            Toast.makeText(context, log, Toast.LENGTH_SHORT).show()
                            refresh()
                        }
                    },
                    onOpenSurface = { sid, slabel ->
                        runCatching {
                            context.startActivity(PluginSurfaceActivity.intent(context, sid, slabel))
                        }.onFailure {
                            Toast.makeText(context, "打开失败：${it.message}", Toast.LENGTH_LONG).show()
                        }
                    },
                )
            }
        }
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

@Composable
private fun PluginCard(
    rec: PluginRecord,
    loaded: Boolean,
    exts: Map<String, Int>,
    onReload: () -> Unit,
    onUninstall: () -> Unit,
    onOpenSurface: (String, String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    // 该插件自带的界面（id → 展示名）
    val surfaces = remember(rec.pluginId) {
        runCatching {
            com.ai.assistance.quro.plugin.engine.bridge.HostToolBridge.uiSurfaces()
                .filter {
                    com.ai.assistance.quro.plugin.engine.bridge.HostToolBridge.pluginIdOfSurface(it.id) == rec.pluginId
                }
                .map { it.id to it.label }
        }.getOrDefault(emptyList())
    }
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(rec.name, style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
                    Text(rec.pluginId, fontSize = 11.sp, color = Muted, fontFamily = FontFamily.Monospace)
                }
                AssistChip(
                    onClick = {},
                    label = { Text(if (loaded) "已加载" else "未加载", fontSize = 11.sp) },
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "v${rec.versionName} (${rec.versionCode}) · 入口 ${rec.entryClass.substringAfterLast('.')}",
                fontSize = 12.sp,
                color = Muted,
            )
            if (exts.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "扩展点：" + exts.entries.joinToString("、") { "${extLabel(it.key)}×${it.value}" },
                    fontSize = 12.sp,
                    color = cs.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "AI 工具：" + QuroPluginHost.toolSpecs().map { it.name }
                        .filter { specName ->
                            runCatching {
                                com.ai.assistance.quro.plugin.engine.registry.ExtensionRegistry
                                    .pluginIdOf(
                                        com.ai.assistance.quro.plugin.extension.ExtensionType.AI_TOOL, specName
                                    ) == rec.pluginId
                            }.getOrDefault(false)
                        }.joinToString("、").ifBlank { "无" },
                    fontSize = 12.sp,
                    color = cs.onSurface,
                )
            }
            Spacer(Modifier.height(10.dp))
            // 插件自带的界面（uiSurface 扩展点）：宿主用通用承载 Activity 打开
            if (surfaces.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    surfaces.forEach { (sid, slabel) ->
                        Button(onClick = { onOpenSurface(sid, slabel) }) { Text("打开 $slabel") }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onReload) { Text("重载") }
                OutlinedButton(onClick = onUninstall) { Text("卸载") }
            }
        }
    }
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
