package com.ai.assistance.quro.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import com.ai.assistance.quro.core.linux.LinuxDistro
import com.ai.assistance.quro.core.linux.PackageManagerSpec
import com.ai.assistance.quro.core.terminal.QuroTerminalBridge
import com.ai.assistance.quro.terminal.data.MirrorSource
import com.ai.assistance.quro.terminal.data.PackageManagerType
import com.ai.assistance.quro.terminal.utils.SourceManager
import com.ai.assistance.quro.core.tools.QuroPrivateDbTool
import com.ai.assistance.quro.core.tools.QuroSandboxTool
import com.ai.assistance.quro.ui.icons.LucideIcon
import com.ai.assistance.quro.ui.theme.Muted
import com.ai.assistance.quro.core.miniapp.MiniAppEngine
import com.ai.assistance.quro.core.miniapp.MiniAppBridgeInterface
import com.ai.assistance.quro.core.tools.MiniAppStudioTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
// KaleidoBox 工具包运行器（进程内 JVM/Dex 引擎）
import com.ai.assistance.quro.kaleidobox.android.KaleidoBoxHost
import com.ai.assistance.quro.kaleidobox.android.KaleidoBoxBridge
import com.ai.assistance.quro.kaleidobox.core.KaleidoRuntime
import com.ai.assistance.quro.kaleidobox.core.model.KValue
import com.ai.assistance.quro.kaleidobox.core.registry.PackageRecord
import com.ai.assistance.quro.kaleidobox.samples.KaleidoBoxSamples
import com.ai.assistance.quro.kaleidobox.samples.KaleidoCatalog
import com.ai.assistance.quro.kaleidobox.samples.PluginScaffold
// "真 Android 组件"：独立承载插件的 Activity + 共享契约
import com.ai.assistance.quro.kaleidobox.KaleidoActivity
import com.ai.assistance.quro.kaleidobox.android.KaleidoAppContract

/**
 * 工具中心（能力聚合入口）。
 *
 * 把此前的分散能力入口归一到一个屏：
 * - 终端 / 小程序 / CMS / 工具箱：已有独立屏，点击直接经 [onLaunch] 打开；
 * - 隔离沙箱（[QuroSandboxTool]）：内联命令面板；
 * - 私有数据库（[QuroPrivateDbTool]）：内联只读查询面板；
 * - 小程序工作台：列出 filesDir/workbench 下的项目，点击用 WebView 渲染 index.html。
 */
@Composable
fun QuroToolCenterScreen(
    context: Context,
    onLaunch: (target: String) -> Unit,
    onClose: () -> Unit,
    initialSelected: String? = null,
    onRenderInChat: (type: String, value: String, label: String) -> Unit = { _, _, _ -> },
    onAskAi: (prompt: String) -> Unit = { _ -> },
) {
    val cs = MaterialTheme.colorScheme
    var selected by remember { mutableStateOf<String?>(initialSelected) }

    Column(Modifier.fillMaxSize().background(cs.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected != null) {
                IconButton(onClick = { selected = null }) {
                    LucideIcon("chevron_left", "返回", Modifier.size(22.dp), tint = cs.onBackground)
                }
            }
            Text(
                text = if (selected == null) "工具中心" else                 when (selected) {
                    "sandbox" -> "隔离沙箱"
                    "db" -> "私有数据库"
                    "workbench" -> "小程序工作台"
                    "vispro" -> "可视化编程"
                    "flow" -> "节点编辑器"
                    "miniapp" -> "小程序工作室"
                    "kaleidobox" -> "工具包运行器"
                    else -> "工具中心"
                },
                style = MaterialTheme.typography.titleLarge,
                color = cs.onBackground,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) {
                LucideIcon("x", "关闭", Modifier.size(22.dp), tint = cs.onBackground)
            }
        }
        when (selected) {
            null -> ToolGrid(onLaunch = onLaunch, onSelect = { selected = it })
            "sandbox" -> SandboxPanel(context)
            "db" -> DbPanel(context)
            "workbench" -> WorkbenchPanel(context, onRenderInChat, onAskAi)
            "vispro" -> VisProPanel(context, onRenderInChat)
            "flow" -> NodeEditorPanel(context, onRenderInChat)
            "miniapp" -> MiniAppStudioPanel(context, onRenderInChat)
            "kaleidobox" -> KaleidoBoxPanel(context, onRenderInChat, onAskAi)
            "pkgmgr" -> PackageManagerPanel(context)
        }
    }
}

@Composable
private fun ToolGrid(onLaunch: (target: String) -> Unit, onSelect: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val cards = listOf(
        Triple("workbench", "小程序", "AI 生成并在对话框渲染的 HTML/JS 小程序"),
        Triple("miniapp", "小程序工作室", "完整移植 MiniAppFramework：AI 写 app.json+页面，原生桥调用真·Android 能力"),
        Triple("toolbox", "工具箱", "文件管理 / 浏览器 / IDE"),
        Triple("pkgmgr", "包管理", "apt/apk/dnf/pacman 安装/卸载/升级/查询软件"),
        Triple("sandbox", "隔离沙箱", "免权限文件沙箱与 shell"),
        Triple("db", "私有数据库", "只读查询应用自有 SQLite"),
        Triple("vispro", "可视化编程", "查看 / 编辑 Mermaid 源码，实时渲染并导出 SVG"),
        Triple("flow", "节点编辑器", "拖拽式节点流编程，导出 Mermaid"),
        Triple("browser_ai", "浏览器 AI 操控", "AI 用 browser_act 接管当前浏览器：snapshot/click/fill/eval（先 action=open）"),
        Triple("build", "构建台", "端侧 APK 构建器：Java → DEX → APK，内置工具链（ecj/d8/apksig），免 aapt2，生成可独立安装的应用"),
        Triple("kaleidobox", "工具包运行器", "KaleidoBox：进程内 JVM/Dex 引擎运行 Kotlin/Java 工具包，列包/装包/调 unit/渲染可交互 UI 表面（内置示例计数器开箱即玩）"),
    )
    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(cards) { (key, title, desc) ->
            val launch = key in setOf("toolbox", "browser_ai", "build")
            Card(
                Modifier.fillMaxWidth().clickable { if (launch) onLaunch(key) else onSelect(key) },
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
                        Spacer(Modifier.height(4.dp))
                        Text(desc, style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                    LucideIcon("chevron_right", null, Modifier.size(20.dp), tint = Muted)
                }
            }
        }
    }
}

@Composable
private fun SandboxPanel(context: Context) {
    val cs = MaterialTheme.colorScheme
    val tool = remember { QuroSandboxTool() }
    val scope = rememberCoroutineScope()
    var cmd by remember { mutableStateOf("") }
    var out by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun runAction(action: String, extra: JSONObject.() -> Unit = {}) {
        if (busy) return
        busy = true
        scope.launch(Dispatchers.IO) {
            val arg = JSONObject().put("action", action).apply(extra).toString()
            val res = runCatching { tool.run(context, arg) }.getOrElse { "执行失败：${it.message}" }
            withContext(Dispatchers.Main) { out = res; busy = false }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = cmd,
                onValueChange = { cmd = it },
                placeholder = { Text("输入 shell 命令，在沙箱内执行", color = Muted) },
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                colors = TextFieldDefaults.colors(),
                singleLine = false,
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { runAction("exec") { put("command", cmd) } },
                enabled = cmd.isNotBlank() && !busy,
            ) { Text(if (busy) "执行中…" else "执行") }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = { runAction("status") }, enabled = !busy) { Text("状态") }
            TextButton(onClick = { runAction("list") }, enabled = !busy) { Text("列目录") }
            TextButton(onClick = { runAction("reset") }, enabled = !busy) { Text("清空沙箱") }
        }
        Spacer(Modifier.height(8.dp))
        Text("结果", style = MaterialTheme.typography.labelMedium, color = Muted)
        Box(
            Modifier.fillMaxSize().weight(1f).clip(RoundedCornerShape(10.dp))
                .background(cs.surfaceVariant).verticalScroll(rememberScrollState()).padding(12.dp),
        ) {
            Text(
                out.ifBlank { "（暂无输出）" },
                fontFamily = FontFamily.Monospace,
                color = cs.onSurface,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DbPanel(context: Context) {
    val cs = MaterialTheme.colorScheme
    val tool = remember { QuroPrivateDbTool() }
    val scope = rememberCoroutineScope()
    var dbs by remember { mutableStateOf<List<String>>(emptyList()) }
    var chosen by remember { mutableStateOf("") }
    var sql by remember { mutableStateOf("SELECT name FROM sqlite_master WHERE type='table' LIMIT 20") }
    var out by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            val res = tool.run(context, JSONObject().put("action", "db_list").toString())
            val arr = JSONObject(res).optJSONArray("databases")
            val list = mutableListOf<String>()
            arr?.let { for (i in 0 until it.length()) list.add(it.getJSONObject(i).optString("name")) }
            withContext(Dispatchers.Main) { dbs = list; out = res }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("数据库：${if (chosen.isBlank()) "（请选择）" else chosen}", style = MaterialTheme.typography.labelMedium, color = Muted)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 120.dp)) {
            items(dbs) { name ->
                Text(
                    name,
                    Modifier.fillMaxWidth().clickable { chosen = name }.padding(8.dp),
                    color = if (chosen == name) cs.primary else cs.onSurface,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        TextField(
            value = sql,
            onValueChange = { sql = it },
            placeholder = { Text("只读 SQL（SELECT/PRAGMA/WITH）", color = Muted) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            colors = TextFieldDefaults.colors(),
            singleLine = false,
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                if (chosen.isBlank()) {
                    out = JSONObject().put("ok", false).put("error", "请先选择数据库").toString()
                    return@Button
                }
                scope.launch(Dispatchers.IO) {
                    val res = tool.run(
                        context,
                        JSONObject().put("action", "db_query").put("db", chosen).put("sql", sql).toString(),
                    )
                    withContext(Dispatchers.Main) { out = res }
                }
            },
            enabled = chosen.isNotBlank(),
        ) { Text("查询") }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier.fillMaxSize().weight(1f).clip(RoundedCornerShape(10.dp))
                .background(cs.surfaceVariant).verticalScroll(rememberScrollState()).padding(12.dp),
        ) {
            Text(
                out.ifBlank { "（暂无输出）" },
                fontFamily = FontFamily.Monospace,
                color = cs.onSurface,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun WorkbenchPanel(
    context: Context,
    onRenderInChat: (type: String, value: String, label: String) -> Unit,
    onAskAi: (prompt: String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val root = remember { File(context.filesDir, "workbench") }
    var refreshKey by remember { mutableStateOf(0) }
    val projects = remember(refreshKey) {
        root.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
    }
    var html by remember { mutableStateOf<String?>(null) }
    var current by remember { mutableStateOf<String?>(null) }

    // 导入本地 HTML 文件为小程序项目（项目名取文件名）
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && i >= 0) c.getString(i) else null
            }
        }.getOrNull() ?: "imported_${System.currentTimeMillis()}"
        val base = name.substringBeforeLast(".", name).ifBlank { "imported_${System.currentTimeMillis()}" }
        val content = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (content.isNullOrBlank()) {
            Toast.makeText(context, "导入失败：无法读取文件", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        val dir = File(root, base)
        if (dir.exists()) {
            Toast.makeText(context, "已存在同名项目：$base", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        dir.mkdirs()
        File(dir, "index.html").writeText(content, Charsets.UTF_8)
        refreshKey++
        Toast.makeText(context, "已导入项目：$base", Toast.LENGTH_SHORT).show()
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        if (html == null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("小程序工作台", style = MaterialTheme.typography.titleMedium, color = cs.onSurface, modifier = Modifier.weight(1f))
                TextButton(onClick = { onAskAi("请使用 workbench 工具为我创建一个实用的 HTML/JS 小程序（例如：待办清单、计算器、记账本或单位换算器），并保存到小程序工作台（workbench）。生成后我会在工具中心-小程序工作台里打开并渲染到对话框。") }) { Text("AI 生成小程序") }
                TextButton(onClick = { importLauncher.launch("text/html") }) { Text("导入") }
            }
            Spacer(Modifier.height(8.dp))
            if (projects.isEmpty()) {
                Text("小程序工作台为空（filesDir/workbench 下还没有项目）。点「AI 生成小程序」让 AI 用 workbench 工具创建，或点「导入」载入本地 HTML。", color = Muted)
            } else {
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(projects) { name ->
                        Card(
                            Modifier.fillMaxWidth().clickable {
                                val idx = File(root, "$name/index.html")
                                html = if (idx.exists()) idx.readText() else "<h3>$name</h3><p>未找到 index.html</p>"
                                current = name
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant),
                        ) {
                            Text(name, Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
                        }
                    }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = { html = null; current = null }) { Text("← 返回列表") }
                Text(current ?: "", Modifier.weight(1f).padding(12.dp), color = Muted)
                TextButton(
                    onClick = {
                        onRenderInChat("miniapp", html ?: "", current ?: "小程序")
                        Toast.makeText(context, "已发送到对话框渲染", Toast.LENGTH_SHORT).show()
                    },
                ) { Text("渲染到对话框") }
                TextButton(
                    onClick = {
                        if (current != null && File(root, current!!).deleteRecursively()) {
                            Toast.makeText(context, "已删除项目：$current", Toast.LENGTH_SHORT).show()
                            html = null; current = null; refreshKey++
                        } else {
                            Toast.makeText(context, "删除失败", Toast.LENGTH_SHORT).show()
                        }
                    },
                ) { Text("删除") }
            }
            AndroidView(
                modifier = Modifier.fillMaxSize().weight(1f).clip(RoundedCornerShape(10.dp)),
                factory = { ctx ->
                    WebView(ctx).apply {
                        webViewClient = WebViewClient()
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        loadDataWithBaseURL(
                            "file://${File(root, current ?: "").absolutePath}/",
                            html ?: "",
                            "text/html",
                            "utf-8",
                            null,
                        )
                    }
                },
                update = { wv ->
                    wv.loadDataWithBaseURL(
                        "file://${File(root, current ?: "").absolutePath}/",
                        html ?: "",
                        "text/html",
                        "utf-8",
                        null,
                    )
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 可视化编程：Mermaid 源码编辑器 + 离线实时渲染
// ---------------------------------------------------------------------------

@Composable
private fun VisProPanel(
    context: Context,
    onRenderInChat: (type: String, value: String, label: String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val isDark = isSystemInDarkTheme()
    var src by remember { mutableStateOf("") }
    var projName by remember { mutableStateOf("") }
    var pageReady by remember { mutableStateOf(false) }
    var lastSvg by remember { mutableStateOf("") }
    var errMsg by remember { mutableStateOf("") }
    val wvRef = remember { mutableStateOf<WebView?>(null) }

    fun doRender(wv: WebView?) {
        if (wv == null || !pageReady) return
        val theme = if (isDark) "dark" else "default"
        wv.evaluateJavascript("window.__render(${JSONObject.quote(src)}, ${JSONObject.quote(theme)})", null)
    }

    // 「产物 + 可视化」模型：每个工程 = 一份 Mermaid 源码，存于 filesDir/studio/vispro/<name>.mmd
    // 多工程并存、写入单一干净（AI 用 visual 工具写入的也在列表里）
    val visproDir = remember { File(context.filesDir, "studio/vispro") }
    var refreshKey by remember { mutableStateOf(0) }
    val savedFiles = remember(refreshKey) {
        visproDir.listFiles()?.filter { it.extension == "mmd" }?.map { it.nameWithoutExtension }?.sorted() ?: emptyList()
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val txt = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (txt.isNullOrBlank()) {
            Toast.makeText(context, "导入失败：无法读取文件", Toast.LENGTH_SHORT).show()
        } else {
            src = txt
            doRender(wvRef.value)
            Toast.makeText(context, "已导入 Mermaid 源码", Toast.LENGTH_SHORT).show()
        }
    }

    val bridge = remember {
        object {
            @JavascriptInterface
            fun onSvg(svg: String) { lastSvg = svg; errMsg = "" }

            @JavascriptInterface
            fun onError(msg: String) { errMsg = msg }

            @JavascriptInterface
            fun onReady() {}

            @JavascriptInterface
            fun onHeight(h: Int) {}
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Mermaid 源码", style = MaterialTheme.typography.labelMedium, color = Muted, modifier = Modifier.weight(1f))
            TextButton(
                onClick = {
                    if (src.isBlank()) {
                        Toast.makeText(context, "源码为空", Toast.LENGTH_SHORT).show()
                    } else {
                        onRenderInChat("mermaid", src, "可视化编程")
                        Toast.makeText(context, "已发送到对话框渲染", Toast.LENGTH_SHORT).show()
                    }
                },
            ) { Text("渲染到对话框") }
            TextButton(
                onClick = {
                    if (src.isBlank()) {
                        Toast.makeText(context, "源码为空，无法保存", Toast.LENGTH_SHORT).show()
                    } else if (projName.isBlank()) {
                        Toast.makeText(context, "请先填写工程名", Toast.LENGTH_SHORT).show()
                    } else {
                        visproDir.mkdirs()
                        File(visproDir, "$projName.mmd").writeText(src, Charsets.UTF_8)
                        refreshKey++
                        Toast.makeText(context, "已保存到工程「$projName」", Toast.LENGTH_SHORT).show()
                    }
                },
            ) { Text("保存") }
            TextButton(onClick = { importLauncher.launch("text/plain,application/json") }) { Text("导入") }
            TextButton(
                onClick = {
                    if (lastSvg.isBlank()) {
                        Toast.makeText(context, "请先等待渲染完成", Toast.LENGTH_SHORT).show()
                    } else {
                        val name = "mermaid-${System.currentTimeMillis()}.svg"
                        Toast.makeText(context, saveTextFile(context, name, lastSvg), Toast.LENGTH_SHORT).show()
                    }
                },
            ) { Text("下载 SVG") }
            TextButton(
                onClick = { copyText(context, src); Toast.makeText(context, "已复制源码", Toast.LENGTH_SHORT).show() },
            ) { Text("复制源码") }
        }

        // 工程名 + 载入（与 AI 的 visual 工具共享同一份命名工程）
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = projName,
                onValueChange = { projName = it },
                label = { Text("工程名") },
                singleLine = true,
                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                colors = TextFieldDefaults.colors(),
            )
            TextButton(onClick = {
                val name = projName.ifBlank { "" }
                if (name.isBlank()) { Toast.makeText(context, "请先填写工程名", Toast.LENGTH_SHORT).show(); return@TextButton }
                val f = File(visproDir, "$name.mmd")
                if (f.exists()) {
                    src = f.readText(); doRender(wvRef.value)
                    Toast.makeText(context, "已载入工程「$name」", Toast.LENGTH_SHORT).show()
                } else Toast.makeText(context, "工程不存在：$name", Toast.LENGTH_SHORT).show()
            }) { Text("载入") }
        }

        TextField(
            value = src,
            onValueChange = { src = it; doRender(wvRef.value) },
            placeholder = { Text("粘贴 Mermaid 源码查看 / 编辑（AI 可用 visual 工具直接写入命名工程）", color = Muted) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 200.dp),
            colors = TextFieldDefaults.colors(),
            singleLine = false,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )

        // 已保存的命名工程列表（打开 / 删除）
        if (savedFiles.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("已保存工程（${savedFiles.size}）", style = MaterialTheme.typography.labelSmall, color = Muted)
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 140.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(savedFiles) { name ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(cs.surfaceVariant).padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = cs.onSurface)
                        TextButton(onClick = {
                            val f = File(visproDir, "$name.mmd")
                            if (f.exists()) { src = f.readText(); projName = name; doRender(wvRef.value) }
                        }) { Text("打开") }
                        TextButton(onClick = {
                            if (File(visproDir, "$name.mmd").delete()) { refreshKey++ }
                        }) { Text("删除") }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        Box(
            Modifier.fillMaxSize().weight(1f).clip(RoundedCornerShape(10.dp))
                .background(if (isDark) cs.surfaceVariant else androidx.compose.ui.graphics.Color.White)
                .verticalScroll(rememberScrollState()).padding(10.dp),
        ) {
            if (errMsg.isNotBlank()) {
                Text(errMsg, color = androidx.compose.ui.graphics.Color(0xffe5484d), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    pageReady = true
                                    doRender(this@apply)
                                }
                            }
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            addJavascriptInterface(bridge, "AndroidBridge")
                            loadUrl("file:///android_asset/www/mermaid_render.html")
                        }.also { wvRef.value = it }
                    },
                    update = { wv -> doRender(wv) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 节点编辑器：拖拽式节点流编程（离线 HTML，导出 Mermaid）
// ---------------------------------------------------------------------------

@Composable
private fun NodeEditorPanel(
    context: Context,
    onRenderInChat: (type: String, value: String, label: String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val wvRef = remember { mutableStateOf<WebView?>(null) }
    val flowDir = remember { File(context.filesDir, "studio/flow") }
    var flowName by remember { mutableStateOf("default") }
    var flowRefresh by remember { mutableStateOf(0) }
    val flowProjects = remember(flowRefresh) {
        flowDir.listFiles()?.filter { it.extension == "qne" }?.map { it.nameWithoutExtension }?.sorted() ?: emptyList()
    }

    fun writeFlow(name: String, content: String): String {
        flowDir.mkdirs()
        val raw = name.substringBeforeLast(".", name).ifBlank { name }.ifBlank { "default" }
        val base = raw.replace(Regex("[^A-Za-z0-9_.\\-]"), "_").replace("..", "_")
        val f = File(flowDir, "$base.qne")
        return runCatching { f.writeText(content, Charsets.UTF_8); "已保存到工程「$base」" }.getOrElse { "保存失败：${it.message}" }
    }

    val bridge = remember {
        object {
            @JavascriptInterface
            fun copyText(text: String) {
                copyText(context, text)
                Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }

            @JavascriptInterface
            fun saveFile(name: String, content: String) {
                // 画布内「保存工程」也写入共享工作区，与 AI 的 node_editor 工具同一份文件
                val msg = writeFlow(name, content)
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                flowRefresh++
            }

            @JavascriptInterface
            fun onReady() {}
        }
    }

    // ══ #667 修复：AI 写入节点流工程后，画布实时跟随刷新（无需用户手动重开面板）══
    // 关键修正：只自动刷新「当前正在查看的工程」自身文件（按 flowName 定位），
    // 不再用全局 maxByOrNull 取最新 .qne —— 否则 AI 往别的工程写文件会强行切走你的画布视图与工程名。
    // 用户在画布上未保存的编辑不会改变文件 mtime，因此不会被轮询覆盖；只有 AI 调
    // node_editor 写入「当前工程」文件（或你点「保存工程」）才会触发刷新。
    val scope = rememberCoroutineScope()
    var lastLoadedMtime by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            val wv = wvRef.value ?: continue
            val cur = File(flowDir, "$flowName.qne")
            if (cur.exists() && cur.lastModified() != lastLoadedMtime) {
                lastLoadedMtime = cur.lastModified()
                wv.evaluateJavascript("window.__restore(${JSONObject.quote(cur.readText(Charsets.UTF_8))})") {}
            }
        }
    }

    // 导入工程文件（.qne / .json），读到文本后还原到画布
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val txt = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (txt.isNullOrBlank()) {
            Toast.makeText(context, "导入失败：无法读取文件", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        // __restore 返回 true/false：成功才提示「已导入」，失败提示「文件格式错误」
        // （此前忽略返回值，坏 JSON 也误报成功，且画布保持原样造成状态不同步）
        wvRef.value?.evaluateJavascript(
            "JSON.stringify([window.__restore(${JSONObject.quote(txt)})])"
        ) { r ->
            val ok = r?.let { it.trim().trim('"') == "true" || it.contains("true") } ?: false
            Toast.makeText(
                context,
                if (ok) "已导入工程到画布" else "导入失败：文件不是有效的工程格式",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "拖拽节点 / 端口连线 / 双击改名；AI 可直接用 node_editor 工具读写本工程",
                style = MaterialTheme.typography.labelSmall, color = Muted, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { importLauncher.launch("application/json,text/plain") }) { Text("导入工程") }
            TextButton(
                onClick = {
                    wvRef.value?.evaluateJavascript("JSON.stringify([window.__snapshot()])") { r ->
                        val snap = decodeJsString(r)
                        if (snap.isBlank()) Toast.makeText(context, "画布为空，无可保存内容", Toast.LENGTH_SHORT).show()
                        else {
                            val msg = writeFlow(flowName, snap)
                            flowRefresh++
                            lastLoadedMtime = File(flowDir, "${flowName.ifBlank { "default" }}.qne").lastModified()
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            ) { Text("保存工程") }
            TextButton(
                onClick = {
                    wvRef.value?.evaluateJavascript("JSON.stringify([window.__getMermaid()])") { r ->
                        val txt = decodeJsString(r)
                        if (txt.isBlank()) Toast.makeText(context, "画布为空，暂无可复制的 Mermaid", Toast.LENGTH_SHORT).show()
                        else { copyText(context, txt); Toast.makeText(context, "已复制 Mermaid 源码", Toast.LENGTH_SHORT).show() }
                    }
                },
            ) { Text("复制 Mermaid") }
            TextButton(
                onClick = {
                    wvRef.value?.evaluateJavascript("JSON.stringify([window.__getMermaid()])") { r ->
                        val txt = decodeJsString(r)
                        if (txt.isBlank()) Toast.makeText(context, "画布为空，暂无可渲染内容", Toast.LENGTH_SHORT).show()
                        else { onRenderInChat("mermaid", txt, "节点编辑器"); Toast.makeText(context, "已发送到对话框渲染", Toast.LENGTH_SHORT).show() }
                    }
                },
            ) { Text("渲染到对话框") }
        }
        // 工程名 + 项目列表（多工程并存，AI 写入的也在列表里）
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = flowName,
                onValueChange = { flowName = it },
                label = { Text("工程名") },
                singleLine = true,
                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                colors = TextFieldDefaults.colors(),
            )
            TextButton(onClick = {
                val f = File(flowDir, "${flowName.ifBlank { "default" }}.qne")
                if (f.exists()) {
                    wvRef.value?.evaluateJavascript("window.__restore(${JSONObject.quote(f.readText(Charsets.UTF_8))})") {}
                    lastLoadedMtime = f.lastModified()
                    Toast.makeText(context, "已载入工程「${flowName}」", Toast.LENGTH_SHORT).show()
                } else Toast.makeText(context, "工程不存在：$flowName", Toast.LENGTH_SHORT).show()
            }) { Text("载入") }
        }
        if (flowProjects.isNotEmpty()) {
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 96.dp).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(flowProjects) { name ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(cs.surfaceVariant).padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = cs.onSurface)
                        TextButton(onClick = {
                            val f = File(flowDir, "$name.qne")
                            wvRef.value?.evaluateJavascript("window.__restore(${JSONObject.quote(f.readText(Charsets.UTF_8))})") {}
                            flowName = name
                            lastLoadedMtime = f.lastModified()
                        }) { Text("打开") }
                        TextButton(onClick = {
                            if (File(flowDir, "$name.qne").delete()) { flowRefresh++; Toast.makeText(context, "已删除：$name", Toast.LENGTH_SHORT).show() }
                        }) { Text("删除") }
                    }
                }
            }
        }
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { ctx ->
                WebView(ctx).apply {
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            // 只恢复「当前正在查看的工程」自身文件，绝不取全局最新 .qne 劫持画布/改工程名
                            // （#667 真实要修的 bug 就在这里：原先用 maxByOrNull 全局取最新 .qne，
                            //  AI 往别的工程写文件，面板一打开就被强行切走视图并改名）。
                            val cur = File(flowDir, "${flowName.ifBlank { "default" }}.qne")
                            if (cur.exists()) {
                                lastLoadedMtime = cur.lastModified()
                                view?.post {
                                    evaluateJavascript("window.__restore(${JSONObject.quote(cur.readText(Charsets.UTF_8))})") {}
                                }
                            }
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            errorCode: Int,
                            description: String?,
                            failingUrl: String?,
                        ) {
                            super.onReceivedError(view, errorCode, description, failingUrl)
                            android.util.Log.e("NodeEditor", "WebView error code=$errorCode desc=$description url=$failingUrl")
                            // 仅主框架（node_editor.html 本身）加载失败才算致命；mermaid.min.js 等子资源
                            // 偶尔被 ROM 拦截时不应弹「加载失败」吓用户（编辑器本体已渲染，预览降级即可）。
                            val isMain = failingUrl == null || failingUrl.endsWith("node_editor.html")
                            if (isMain) {
                                Toast.makeText(context, "节点编辑器加载失败: $description (code=$errorCode)", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // 与能正常渲染的 MermaidWebView 完全对齐：放开同目录 file:// 资源访问、强制 UTF-8、
                    // 透明底色，避免部分 ROM/WebView 内核下 mermaid.min.js 被拦截或整页白屏。
                    settings.allowUniversalAccessFromFileURLs = true
                    settings.loadsImagesAutomatically = true
                    settings.defaultTextEncodingName = "UTF-8"
                    setBackgroundColor(0xFF0F1115.toInt()) // 实色底色，杜绝整页透出背后白/黑 surface（节点编辑器白/黑屏根因）
                    // ══ 白屏修复：不要 loadWithOverviewMode / useWideViewPort。
                    // node_editor.html 用 html,body{height:100%;overflow:hidden} + meta viewport，
                    // 这两项会让部分 WebView 内核算出 0 高可见视口 → 整页白屏。
                    // 对齐能正常渲染的 mermaid 面板（它不设这两项）。
                    settings.allowFileAccess = true
                    settings.allowFileAccessFromFileURLs = true
                    settings.allowContentAccess = true
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                    // 注意：不要 setLayerType(HARDWARE)。部分 ROM / WebView 内核下强制硬件合成层
                    // 会让 file:// WebView 渲染成空白（白屏），#667 加这行反而没修好。交给 WebView 自行决定合成层。
                    addJavascriptInterface(bridge, "AndroidBridge")
                    loadUrl("file:///android_asset/www/node_editor.html")
                }.also { wvRef.value = it }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// 小程序工作室：完整移植 MiniAppFramework，AI 用 miniapp 工具写入的工程在此渲染
// ---------------------------------------------------------------------------

@Composable
private fun MiniAppStudioPanel(
    context: Context,
    onRenderInChat: (type: String, value: String, label: String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val root = remember { File(context.filesDir, "studio/miniapp") }
    var refreshKey by remember { mutableStateOf(0) }
    val projects = remember(refreshKey) {
        root.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
    }
    var current by remember { mutableStateOf<String?>(null) }
    val wvRef = remember { mutableStateOf<WebView?>(null) }
    val engineRef = remember { mutableStateOf<MiniAppEngine?>(null) }
    val scope = rememberCoroutineScope()

    // 系统返回键：小程序内部有多页历史时先在小程序内返回（engine.handleBack），否则退回工程列表
    BackHandler(enabled = current != null) {
        val handled = engineRef.value?.handleBack() ?: false
        if (!handled) { current = null; engineRef.value = null }
    }

    Column(Modifier.fillMaxSize()) {
        if (current == null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("小程序工作室", style = MaterialTheme.typography.titleMedium, color = cs.onSurface, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        val res = MiniAppStudioTool().run(context, JSONObject().put("action", "create").put("name", "demo").toString())
                        withContext(Dispatchers.Main) { refreshKey++; Toast.makeText(context, res.take(120), Toast.LENGTH_SHORT).show() }
                    }
                }) { Text("新建示例") }
            }
            Spacer(Modifier.height(8.dp))
            if (projects.isEmpty()) {
                Text("小程序工作台为空（filesDir/studio/miniapp 下还没有工程）。点「新建示例」，或让 AI 用 miniapp 工具创建并写入。", color = Muted, modifier = Modifier.padding(16.dp))
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(projects) { name ->
                        Card(
                            Modifier.fillMaxWidth().clickable { current = name },
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant),
                        ) {
                            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
                                TextButton(onClick = { current = name }) { Text("打开") }
                            }
                        }
                    }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { current = null; engineRef.value = null }) { Text("← 返回列表") }
                Text(current ?: "", Modifier.weight(1f).padding(12.dp), color = Muted)
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        val html = MiniAppStudioTool().run(context, JSONObject().put("action", "run").put("name", current).toString())
                        withContext(Dispatchers.Main) {
                            if (html.startsWith("❌")) Toast.makeText(context, html, Toast.LENGTH_SHORT).show()
                            else { onRenderInChat("miniapp", html, current ?: "小程序"); Toast.makeText(context, "已发送到对话框预览", Toast.LENGTH_SHORT).show() }
                        }
                    }
                }) { Text("对话框预览") }
                TextButton(onClick = {
                    if (current != null && File(root, current!!).deleteRecursively()) { refreshKey++; current = null; Toast.makeText(context, "已删除工程", Toast.LENGTH_SHORT).show() }
                }) { Text("删除") }
            }
            AndroidView(
                modifier = Modifier.fillMaxSize().weight(1f),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = true
                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                        val bridge = MiniAppBridgeInterface(ctx, this)
                        val engine = MiniAppEngine(this, bridge)
                        engine.configure()
                        engine.start(File(root, current ?: "demo"))
                        engineRef.value = engine
                        wvRef.value = this
                    }
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 包管理：apt/apk/dnf/pacman 安装 / 查询 / 列表（Linux 沙箱内执行）
// ---------------------------------------------------------------------------

@Composable
private fun PackageManagerPanel(context: Context) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var distro by remember { mutableStateOf<LinuxDistro?>(null) }
    var pm by remember { mutableStateOf<PackageManagerSpec?>(null) }
    var installed by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var envReady by remember { mutableStateOf(false) }

    // 软件源管理（镜像源选择，复用终端侧 SourceManager，与 rootfs 源配置同一套偏好）
    val sourceManager = remember { SourceManager(context) }
    val sourcePms = remember { listOf(PackageManagerType.APT, PackageManagerType.PIP, PackageManagerType.NPM, PackageManagerType.RUST) }
    var sourceRefresh by remember { mutableStateOf(0) }
    var sourceDialogPm by remember { mutableStateOf<PackageManagerType?>(null) }

    // 环境守卫：未就绪时 Toast 引导，而不是把按钮灰掉让人摸不着头脑
    fun requireEnv(): Boolean {
        if (envReady && pm != null) return true
        Toast.makeText(context, "环境未就绪：请先在终端页安装 rootfs", Toast.LENGTH_SHORT).show()
        return false
    }

    fun requireQuery(): Boolean {
        if (query.isNotBlank()) return true
        Toast.makeText(context, "请先输入软件名", Toast.LENGTH_SHORT).show()
        return false
    }

    fun runCmd(cmd: String) {
        if (running) return
        running = true
        installed = "执行：$cmd"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { QuroTerminalBridge.run(context, cmd, timeoutMs = 300_000L) }
            }.getOrElse { -1 to "执行失败：${it.message}" }
            running = false
            installed = buildString {
                appendLine("[exit=${result.first}] $cmd")
                appendLine(result.second.take(4000))
            }
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            envReady = runCatching { QuroTerminalBridge.envReady(context) }.getOrDefault(false)
            distro = runCatching { QuroTerminalBridge.distro(context) }.getOrNull()
            pm = runCatching { QuroTerminalBridge.packageManager(context) }.getOrNull()
        }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("包管理（Linux 沙箱）", style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
        Surface(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(cs.surfaceVariant),
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("环境状态", fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (envReady) "就绪" else "未就绪（请先在终端页安装 rootfs）",
                        color = if (envReady) cs.primary else cs.error,
                        fontSize = 13.sp,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "发行版：${distro?.displayName ?: "未检测"}",
                    fontSize = 13.sp, color = cs.onSurfaceVariant,
                )
                Text(
                    "包管理器：${pm?.let { "${it.displayName} (${it.binary})" } ?: "未检测"}",
                    fontSize = 13.sp, color = cs.onSurfaceVariant,
                )
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("搜索 / 安装软件名") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                enabled = !running,
                onClick = {
                    if (!requireEnv()) return@Button
                    if (!requireQuery()) return@Button
                    runCmd(pm!!.install(listOf(query.trim())))
                },
            ) { Text(if (running) "执行中…" else "安装") }
            OutlinedButton(
                enabled = !running,
                onClick = {
                    if (!requireEnv()) return@OutlinedButton
                    if (!requireQuery()) return@OutlinedButton
                    runCmd(pm!!.search(query.trim()))
                },
            ) { Text("搜索") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                enabled = !running,
                onClick = {
                    if (!requireEnv()) return@OutlinedButton
                    runCmd(pm!!.listInstalled())
                },
            ) { Text("列表已装") }
            OutlinedButton(
                enabled = !running,
                onClick = {
                    if (!requireEnv()) return@OutlinedButton
                    if (!requireQuery()) return@OutlinedButton
                    runCmd(pm!!.info(query.trim()))
                },
            ) { Text("查看信息") }
            OutlinedButton(
                enabled = !running,
                onClick = {
                    if (!requireEnv()) return@OutlinedButton
                    runCmd(pm!!.update())
                },
            ) { Text("更新源") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                enabled = !running,
                onClick = {
                    if (!requireEnv()) return@OutlinedButton
                    if (!requireQuery()) return@OutlinedButton
                    runCmd(pm!!.remove(listOf(query.trim())))
                },
            ) { Text("卸载") }
            OutlinedButton(
                enabled = !running,
                onClick = {
                    if (!requireEnv()) return@OutlinedButton
                    runCmd(pm!!.upgrade())
                },
            ) { Text("升级") }
            OutlinedButton(
                enabled = !running,
                onClick = {
                    if (!requireEnv()) return@OutlinedButton
                    runCmd(pm!!.clean())
                },
            ) { Text("清理") }
        }
        Surface(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(cs.surface),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("输出", fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                Spacer(Modifier.height(4.dp))
                Text(
                    installed ?: "尚无输出。安装/搜索/列表 命令执行后会在此显示（截取 4000 字）。",
                    fontSize = 12.sp,
                    color = cs.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        // —— 软件源管理（镜像源选择，与终端 rootfs 源配置同一套偏好）——
        Surface(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(cs.surfaceVariant),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("软件源管理", fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                Spacer(Modifier.height(6.dp))
                sourcePms.forEach { pmType ->
                    val selected = remember(sourceRefresh, pmType) { sourceManager.getSelectedSource(pmType) }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .clickable { sourceDialogPm = pmType }
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(pmType.displayName, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
                            Spacer(Modifier.height(2.dp))
                            Text("当前源：${selected.name}", fontSize = 12.sp, color = cs.onSurfaceVariant)
                        }
                        LucideIcon("chevron_right", null, Modifier.size(18.dp), tint = Muted)
                    }
                    if (pmType != sourcePms.last()) {
                        HorizontalDivider(color = cs.outlineVariant)
                    }
                }
            }
        }
    }

    // 源选择对话框
    sourceDialogPm?.let { pmType ->
        val sources = remember(pmType, sourceRefresh) {
            when (pmType) {
                PackageManagerType.APT -> sourceManager.aptSources
                PackageManagerType.PIP -> sourceManager.pipSources
                PackageManagerType.NPM -> sourceManager.npmSources
                PackageManagerType.RUST -> sourceManager.rustSources
            }
        }
        var selectedId by remember(pmType) { mutableStateOf(sourceManager.getSelectedSourceId(pmType)) }
        var showAddCustom by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { sourceDialogPm = null },
            title = {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("选择 ${pmType.displayName} 源", Modifier.weight(1f))
                    TextButton(onClick = { showAddCustom = true }) { Text("+ 自定义") }
                }
            },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    sources.forEach { source ->
                        Row(
                            Modifier.fillMaxWidth().clickable { selectedId = source.id }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selectedId == source.id, onClick = { selectedId = source.id })
                            Spacer(Modifier.width(6.dp))
                            Column(Modifier.weight(1f)) {
                                Text(source.name, color = cs.onSurface)
                                Text(source.url, fontSize = 11.sp, color = cs.onSurfaceVariant, maxLines = 1)
                            }
                            if (source.id.startsWith("custom_")) {
                                TextButton(onClick = {
                                    sourceManager.deleteCustomSource(pmType, source.id)
                                    sourceRefresh++
                                }) { Text("删除", color = cs.error) }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    sourceManager.setSelectedSourceId(pmType, selectedId)
                    val chosen = sources.find { it.id == selectedId }
                    if (chosen != null && pmType != PackageManagerType.RUST) {
                        val cmd = when (pmType) {
                            PackageManagerType.APT -> sourceManager.getAptSourceChangeCommand(chosen)
                            PackageManagerType.PIP -> sourceManager.getPipSourceChangeCommand(chosen)
                            else -> sourceManager.getNpmSourceChangeCommand(chosen)
                        }
                        // 不再依赖可见终端会话（原来 sendCommandToSession 在终端没打开时静默丢失，
                        // 选了源实际不生效）——改为后台经终端桥真实执行并提示结果
                        scope.launch {
                            val res = withContext(Dispatchers.IO) {
                                runCatching { QuroTerminalBridge.run(context, cmd, timeoutMs = 60_000L) }
                                    .getOrElse { -1 to "执行失败：${it.message}" }
                            }
                            val msg = if (res.first == 0) "已切换 ${pmType.displayName} 源：${chosen.name}"
                            else "源命令执行失败（exit=${res.first}）：${res.second.take(200)}"
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            }
                        }
                    } else if (chosen != null) {
                        Toast.makeText(context, "Rust 镜像源已更新为: ${chosen.name}（下次安装 Rust 时生效）", Toast.LENGTH_SHORT).show()
                    }
                    sourceRefresh++
                    sourceDialogPm = null
                }) { Text("确认") }
            },
            dismissButton = { TextButton(onClick = { sourceDialogPm = null }) { Text("取消") } },
        )

        if (showAddCustom) {
            AddCustomSourceDialog(
                pmType = pmType,
                sourceManager = sourceManager,
                onDismiss = { showAddCustom = false },
                onAdded = { sourceRefresh++; showAddCustom = false },
            )
        }
    }
}

@Composable
private fun AddCustomSourceDialog(
    pmType: PackageManagerType,
    sourceManager: SourceManager,
    onDismiss: () -> Unit,
    onAdded: () -> Unit,
) {
    var customName by remember { mutableStateOf("") }
    var customUrl by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加自定义 ${pmType.displayName} 源") },
        text = {
            Column {
                OutlinedTextField(
                    value = customName,
                    onValueChange = { customName = it },
                    label = { Text("源名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = customUrl,
                    onValueChange = { customUrl = it },
                    label = { Text("源地址") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = customName.isNotBlank() && customUrl.isNotBlank(),
                onClick = {
                    val id = "custom_${pmType.name.lowercase()}_${System.currentTimeMillis()}"
                    sourceManager.saveCustomSource(pmType, MirrorSource(id, customName, customUrl, true))
                    onAdded()
                },
            ) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ---------------------------------------------------------------------------
// 共享工具
// ---------------------------------------------------------------------------

private fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    cm?.setPrimaryClip(ClipData.newPlainText("quro", text))
}

private fun saveTextFile(context: Context, name: String, content: String): String {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                "已保存到下载目录：$name"
            } else {
                fallbackSave(context, name, content)
            }
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            fallbackSaveToDir(dir, name, content)
        }
    } catch (e: Exception) {
        fallbackSave(context, name, content)
    }
}

private fun fallbackSave(context: Context, name: String, content: String): String {
    val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
    return fallbackSaveToDir(dir, name, content)
}

private fun fallbackSaveToDir(dir: java.io.File?, name: String, content: String): String {
    return try {
        dir?.mkdirs()
        val f = File(dir, name)
        f.writeText(content, Charsets.UTF_8)
        "已保存到：${f.absolutePath}"
    } catch (e: Exception) {
        "保存失败：${e.message}"
    }
}

/**
 * 把 WebView.evaluateJavascript 回调收到的 JSON 字面量还原成普通 Kotlin 字符串。
 * evaluateJavascript 返回的是「被 JSON 编码过的字符串」（带外层引号、内部转义），
 * 直接当普通文本用会带上转义引号 / 换行符。约定调用方用 JSON.stringify([expr]) 包裹，
 * 这里用 JSONArray 取第 0 项即可无失真还原。解析失败则原样返回。
 */
private fun decodeJsString(raw: String?): String {
    if (raw == null) return ""
    return runCatching { JSONArray(raw).optString(0) }.getOrDefault(raw)
}

// ---------------------------------------------------------------------------
// KaleidoBox 工具包运行器：把"进程内 JVM/Dex 引擎"的包列表 + 装包 + UI 渲染
// 组装成一个可直接操作的面板，解决"kaleidobox 有引擎但用户没界面"的问题。
// ---------------------------------------------------------------------------

@Composable
private fun KaleidoBoxPanel(
    context: Context,
    onRenderInChat: (type: String, value: String, label: String) -> Unit,
    onAskAi: (prompt: String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val runtime = remember { runCatching { KaleidoBoxHost.get().runtime }.getOrNull() }
    var packages by remember { mutableStateOf<List<PackageRecord>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val catalog = remember { KaleidoCatalog.entries }

    fun refreshPackages() {
        packages = runtime?.installedPackages() ?: emptyList()
        status = if (runtime == null) "KaleidoBox 未初始化" else "已安装 ${packages.size} 个工具包"
    }

    // 导入本地 .zip 包（含 kaleido.json + classes.dex）
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && i >= 0) c.getString(i) else null
            }
        }.getOrNull() ?: "plugin_${System.currentTimeMillis()}.zip"
        val tmp = File(context.cacheDir, "kaleidobox/imports/${name.replace("[^A-Za-z0-9_.\\-]".toRegex(), "_")}")
        tmp.parentFile?.mkdirs()
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { ins -> tmp.outputStream().use { ins.copyTo(it) } }; true
        }.getOrNull() == true
        if (!copied) {
            Toast.makeText(context, "导入失败：无法读取文件", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        scope.launch(Dispatchers.IO) {
            val out = KaleidoBoxBridge.installZip(context, tmp)
            withContext(Dispatchers.Main) {
                if (out is KValue.Obj && out.value["ok"]?.asBoolOr() == true) {
                    refreshPackages(); Toast.makeText(context, "已安装：${out.value["id"]?.asString()}", Toast.LENGTH_SHORT).show()
                } else Toast.makeText(context, "安装失败：${out.asString()}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // 从链接导入对话框
    var urlDialog by remember { mutableStateOf(false) }
    var urlText by remember { mutableStateOf("") }

    // AI 生成并编译对话框
    var genDialog by remember { mutableStateOf(false) }
    var genSrc by remember { mutableStateOf("") }
    var genClass by remember { mutableStateOf("com.ai.assistance.quro.kaleidobox.gen.MyToolkit") }
    var genId by remember { mutableStateOf("dev.kaleidobox.gen.plugin") }
    var genName by remember { mutableStateOf("我的工具") }
    var genDesc by remember { mutableStateOf("AI 生成的 KaleidoBox 工具包") }

    LaunchedEffect(Unit) { refreshPackages() }

    fun firstSurfaceOf(pkgId: String): String? =
        runtime?.installedPackages()?.firstOrNull { it.id == pkgId }?.manifest?.ui?.firstOrNull()?.id

    fun openPlugin(pkgId: String) {
        val surfaceId = firstSurfaceOf(pkgId)
        if (surfaceId == null) {
            Toast.makeText(context, "该工具包没有可打开的 UI 表面", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(context, KaleidoActivity::class.java).apply {
            putExtra(KaleidoAppContract.EXTRA_PKG_ID, pkgId)
            putExtra(KaleidoAppContract.EXTRA_SURFACE_ID, surfaceId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { Toast.makeText(context, "打开失败：${it.message}", Toast.LENGTH_LONG).show() }
    }

    fun installCatalog(id: String) {
        if (busy) return
        busy = true
        scope.launch(Dispatchers.IO) {
            val out = KaleidoBoxBridge.installCatalogEntry(context, id)
            withContext(Dispatchers.Main) {
                busy = false
                if (out is KValue.Obj && out.value["ok"]?.asBoolOr() == true) {
                    refreshPackages()
                    Toast.makeText(context, "已安装：${out.value["id"]?.asString()}", Toast.LENGTH_SHORT).show()
                } else Toast.makeText(context, "安装失败：${out.asString()}", Toast.LENGTH_LONG).show()
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("工具包运行器", style = MaterialTheme.typography.titleMedium, color = cs.onSurface, modifier = Modifier.weight(1f))
            Button(onClick = {
                if (runtime == null) { Toast.makeText(context, "KaleidoBox 未初始化", Toast.LENGTH_SHORT).show(); return@Button }
                runCatching { KaleidoCatalog.installBuiltins(runtime) }
                refreshPackages()
                Toast.makeText(context, "已确保内置示例包装载", Toast.LENGTH_SHORT).show()
            }) { Text("装示例包") }
        }
        Spacer(Modifier.height(4.dp))
        Text(status.ifBlank { "（暂无工具包）" }, style = MaterialTheme.typography.bodySmall, color = Muted)

        // —— 导入入口：从文件 / 从链接 / AI 生成 ——（解决"kaleidobox 没有导入 UI"）
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { importLauncher.launch("application/zip") }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("从文件导入") }
            OutlinedButton(onClick = { urlDialog = true }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("从链接导入") }
            Button(onClick = {
                genSrc = PluginScaffold.fillTemplate("MyToolkit")
                genDialog = true
            }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("AI 生成插件") }
        }

        // —— 已安装：每卡直接「打开」+「卸载」，不再点两次 ——
        val availableCatalog = remember(packages) { catalog.filter { e -> packages.none { it.id == e.id } } }

        if (packages.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("已安装（${packages.size}）", style = MaterialTheme.typography.labelMedium, color = Muted)
            Spacer(Modifier.height(6.dp))
            packages.forEach { rec ->
                val name = rec.manifest.name["zh"] ?: rec.manifest.name["en"] ?: rec.id
                val cat = catalog.firstOrNull { it.id == rec.id }
                val desc = cat?.descZh ?: rec.manifest.description["zh"] ?: rec.manifest.description["en"] ?: ""
                val surfaces = rec.manifest.ui.takeIf { it.isNotEmpty() }?.joinToString { it.id } ?: "无"
                Card(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant),
                ) {
                    Row(
                        Modifier
                            .padding(12.dp)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
                            Text(desc, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 2)
                            Text("v${rec.version} · 表面=$surfaces", style = MaterialTheme.typography.bodySmall, color = Muted)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Button(
                                onClick = { openPlugin(rec.id) },
                                enabled = !busy,
                            ) { Text("打开") }
                            TextButton(
                                onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        val out = KaleidoBoxBridge.uninstall(rec.id)
                                        withContext(Dispatchers.Main) {
                                            if (out is KValue.Obj && out.value["ok"]?.asBoolOr() == true) {
                                                refreshPackages(); Toast.makeText(context, "已卸载：${rec.id}", Toast.LENGTH_SHORT).show()
                                            } else Toast.makeText(context, "卸载失败：${out.asString()}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                                enabled = !busy,
                            ) { Text("卸载", color = cs.error) }
                        }
                    }
                }
            }
        }

        // —— 插件目录：只显示未安装的，点安装后直接可打开 ——
        if (availableCatalog.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("插件目录（${availableCatalog.size} 个可安装）", style = MaterialTheme.typography.labelMedium, color = Muted)
            Spacer(Modifier.height(6.dp))
            availableCatalog.forEach { e ->
                Card(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant),
                ) {
                    Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${e.displayName}  ·  ${e.kind.name}", style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
                            Text(e.descZh, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 2)
                        }
                        Button(
                            onClick = { installCatalog(e.id) },
                            enabled = !busy,
                        ) { Text(if (e.kind == KaleidoCatalog.Kind.SRC) "编译并装" else "安装") }
                    }
                }
            }
        }

        // 空状态
        if (packages.isEmpty() && availableCatalog.isEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("还没有工具包，且插件目录为空。点上方「装示例包」或「AI 生成插件」。", color = Muted)
        } else if (packages.isEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("还没有工具包。点上方「插件目录」一键装一个，或点「AI 生成插件」写一段 Java 让设备内编译运行。", color = Muted)
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                onAskAi("请使用 kaleido 工具为我安装一个 KaleidoBox 工具包（例如示例计数器或单位换算），并告诉我它提供了哪些 unit 和 UI 表面。")
            }) { Text("让 AI 装一个包") }
        } else if (availableCatalog.isEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("插件目录已全部安装，点击「打开」运行插件。", color = Muted)
        }
    }

    // 从链接导入对话框
    if (urlDialog) {
        AlertDialog(
            onDismissRequest = { urlDialog = false },
            title = { Text("从链接导入插件") },
            text = {
                Column {
                    Text("输入 .zip 包下载地址（含 kaleido.json + classes.dex）：", style = MaterialTheme.typography.bodySmall, color = Muted)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = urlText,
                        onValueChange = { urlText = it },
                        placeholder = { Text("https://.../plugin.zip") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val url = urlText.trim()
                    urlDialog = false
                    if (url.isBlank()) return@TextButton
                    scope.launch(Dispatchers.IO) {
                        val out = KaleidoBoxBridge.installUrl(context, url)
                        withContext(Dispatchers.Main) {
                            if (out is KValue.Obj && out.value["ok"]?.asBoolOr() == true) {
                                refreshPackages(); Toast.makeText(context, "已安装：${out.value["id"]?.asString()}", Toast.LENGTH_SHORT).show()
                            } else Toast.makeText(context, "安装失败：${out.asString()}", Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text("导入") }
            },
            dismissButton = { TextButton(onClick = { urlDialog = false }) { Text("取消") } },
        )
    }

    // AI 生成并编译对话框
    if (genDialog) {
        AlertDialog(
            onDismissRequest = { genDialog = false },
            title = { Text("AI 生成插件（端侧编译）") },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    Text("粘贴 Java 源码（实现 KaleidoToolkit，public 类名须等于下面入口类的末段）。点「编译并安装」将在设备内 ecj→d8 编译运行。", style = MaterialTheme.typography.bodySmall, color = Muted)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(value = genClass, onValueChange = { genClass = it }, label = { Text("入口类全名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(value = genId, onValueChange = { genId = it }, label = { Text("包 id") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(value = genName, onValueChange = { genName = it }, label = { Text("展示名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = genSrc,
                        onValueChange = { genSrc = it },
                        label = { Text("Java 源码") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        singleLine = false,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val src = genSrc.trim()
                    val cls = genClass.trim()
                    genDialog = false
                    if (src.isBlank() || cls.isBlank()) { Toast.makeText(context, "源码与入口类不能为空", Toast.LENGTH_SHORT).show(); return@TextButton }
                    scope.launch(Dispatchers.IO) {
                        val manifest = PluginScaffold.buildManifest(genId.trim(), genName.trim(), genName.trim(), genDesc.trim(), cls)
                        val out = KaleidoBoxBridge.writeAndInstall(context, src, cls, manifest, genId.trim())
                        withContext(Dispatchers.Main) {
                            if (out is KValue.Obj && out.value["ok"]?.asBoolOr() == true) {
                                refreshPackages(); Toast.makeText(context, "已编译并安装：${out.value["id"]?.asString()}", Toast.LENGTH_LONG).show()
                            } else Toast.makeText(context, "编译/安装失败：${out.asString()}", Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text("编译并安装") }
            },
            dismissButton = { TextButton(onClick = { genDialog = false }) { Text("取消") } },
        )
    }
}
