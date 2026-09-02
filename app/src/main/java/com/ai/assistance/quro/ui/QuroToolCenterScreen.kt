package com.ai.assistance.quro.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
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
import com.ai.assistance.quro.core.linux.LinuxDistro
import com.ai.assistance.quro.core.linux.PackageManagerSpec
import com.ai.assistance.quro.core.linux.QuroLinuxEnv
import com.ai.assistance.quro.core.tools.QuroPrivateDbTool
import com.ai.assistance.quro.core.tools.QuroSandboxTool
import com.ai.assistance.quro.ui.icons.LucideIcon
import com.ai.assistance.quro.ui.theme.Muted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

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
                text = if (selected == null) "工具中心" else when (selected) {
                    "sandbox" -> "隔离沙箱"
                    "db" -> "私有数据库"
                    "workbench" -> "小程序工作台"
                    "vispro" -> "可视化编程"
                    "flow" -> "节点编辑器"
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
            "workbench" -> WorkbenchPanel(context)
            "vispro" -> VisProPanel(context)
            "flow" -> NodeEditorPanel(context)
            "pkgmgr" -> PackageManagerPanel(context)
        }
    }
}

@Composable
private fun ToolGrid(onLaunch: (target: String) -> Unit, onSelect: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val cards = listOf(
        Triple("workbench", "小程序", "AI 生成并在对话框渲染的 HTML/JS 小程序"),
        Triple("toolbox", "工具箱", "文件管理 / 浏览器 / IDE"),
        Triple("pkgmgr", "包管理", "apt/apk/dnf/pacman 安装/卸载/升级/查询软件"),
        Triple("sandbox", "隔离沙箱", "免权限文件沙箱与 shell"),
        Triple("db", "私有数据库", "只读查询应用自有 SQLite"),
        Triple("vispro", "可视化编程", "查看 / 编辑 Mermaid 源码，实时渲染并导出 SVG"),
        Triple("flow", "节点编辑器", "拖拽式节点流编程，导出 Mermaid"),
        Triple("browser_ai", "浏览器 AI 操控", "AI 用 browser_act 接管当前浏览器：snapshot/click/fill/eval（先 action=open）"),
    )
    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(cards) { (key, title, desc) ->
            val launch = key in setOf("toolbox", "browser_ai")
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
        scope.launch(Dispatchers.IO) {
            val arg = JSONObject().put("action", action).apply(extra).toString()
            val res = tool.run(context, arg)
            withContext(Dispatchers.Main) { out = res }
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
                onClick = { busy = true; runAction("exec") { put("command", cmd) }.also { busy = false } },
                enabled = cmd.isNotBlank() && !busy,
            ) { Text(if (busy) "执行中…" else "执行") }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = { runAction("status") }) { Text("状态") }
            TextButton(onClick = { runAction("list") }) { Text("列目录") }
            TextButton(onClick = { runAction("reset") }) { Text("清空沙箱") }
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
private fun WorkbenchPanel(context: Context) {
    val cs = MaterialTheme.colorScheme
    val root = remember { File(context.filesDir, "workbench") }
    val projects = remember { (root.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()) }
    var html by remember { mutableStateOf<String?>(null) }
    var current by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        if (html == null) {
            if (projects.isEmpty()) {
                Text("小程序工作台为空（filesDir/workbench 下还没有项目）。用 workbench 工具让 AI 创建小程序。", color = Muted)
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
private fun VisProPanel(context: Context) {
    val cs = MaterialTheme.colorScheme
    val isDark = isSystemInDarkTheme()
    var src by remember { mutableStateOf("") }
    var pageReady by remember { mutableStateOf(false) }
    var lastSvg by remember { mutableStateOf("") }
    var errMsg by remember { mutableStateOf("") }
    val wvRef = remember { mutableStateOf<WebView?>(null) }

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

    fun doRender(wv: WebView?) {
        if (wv == null || !pageReady) return
        val theme = if (isDark) "dark" else "default"
        wv.evaluateJavascript("window.__render(${JSONObject.quote(src)}, ${JSONObject.quote(theme)})", null)
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Mermaid 源码", style = MaterialTheme.typography.labelMedium, color = Muted, modifier = Modifier.weight(1f))
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

        TextField(
            value = src,
            onValueChange = { src = it; doRender(wvRef.value) },
            placeholder = { Text("粘贴 Mermaid 源码查看 / 编辑（AI 生成的可从对话框复制）", color = Muted) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 200.dp),
            colors = TextFieldDefaults.colors(),
            singleLine = false,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )

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
private fun NodeEditorPanel(context: Context) {
    val cs = MaterialTheme.colorScheme
    val wvRef = remember { mutableStateOf<WebView?>(null) }

    val bridge = remember {
        object {
            @JavascriptInterface
            fun copyText(text: String) {
                copyText(context, text)
                Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }

            @JavascriptInterface
            fun saveFile(name: String, content: String) {
                Toast.makeText(context, saveTextFile(context, name, content), Toast.LENGTH_SHORT).show()
            }

            @JavascriptInterface
            fun onReady() {}
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("拖拽节点 / 端口连线 / 双击改名；底部可导出与预览 Mermaid", style = MaterialTheme.typography.labelSmall, color = Muted, modifier = Modifier.weight(1f))
            TextButton(
                onClick = {
                    wvRef.value?.evaluateJavascript("window.__getMermaid()") { r ->
                        val txt = runCatching { JSONObject(r).optString("") }.getOrDefault(r ?: "")
                        copyText(context, txt)
                        Toast.makeText(context, "已复制 Mermaid 源码", Toast.LENGTH_SHORT).show()
                    }
                },
            ) { Text("复制 Mermaid") }
        }
        AndroidView(
            modifier = Modifier.fillMaxSize().weight(1f),
            factory = { ctx ->
                WebView(ctx).apply {
                    webViewClient = WebViewClient()
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    addJavascriptInterface(bridge, "AndroidBridge")
                    loadUrl("file:///android_asset/www/node_editor.html")
                }.also { wvRef.value = it }
            },
        )
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

    fun runCmd(cmd: String) {
        if (running) return
        running = true
        installed = "执行：$cmd"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { QuroLinuxEnv.run(context, cmd, timeoutMs = 60_000L) }
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
            val status = runCatching { QuroLinuxEnv.probeLenient(context) }.getOrNull()
            envReady = status?.available == true
            distro = runCatching { QuroLinuxEnv.detectDistro(context) }.getOrNull()
            pm = runCatching { QuroLinuxEnv.detectPackageManager(context) }.getOrNull()
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
            enabled = pm != null && envReady,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                enabled = pm != null && envReady && !running && query.isNotBlank(),
                onClick = {
                    val manager = pm ?: return@Button
                    runCmd(manager.install(listOf(query.trim())))
                },
            ) { Text(if (running) "执行中…" else "安装") }
            OutlinedButton(
                enabled = pm != null && envReady && !running && query.isNotBlank(),
                onClick = {
                    val manager = pm ?: return@OutlinedButton
                    runCmd(manager.search(query.trim()))
                },
            ) { Text("搜索") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                enabled = pm != null && envReady && !running,
                onClick = { runCmd(pm!!.listInstalled()) },
            ) { Text("列表已装") }
            OutlinedButton(
                enabled = pm != null && envReady && !running,
                onClick = { runCmd(pm!!.info(query.trim())) },
            ) { Text("查看信息") }
            OutlinedButton(
                enabled = pm != null && envReady && !running,
                onClick = { runCmd(pm!!.update()) },
            ) { Text("更新源") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                enabled = pm != null && envReady && !running && query.isNotBlank(),
                onClick = { runCmd(pm!!.remove(listOf(query.trim()))) },
            ) { Text("卸载") }
            OutlinedButton(
                enabled = pm != null && envReady && !running,
                onClick = { runCmd(pm!!.upgrade()) },
            ) { Text("升级") }
            OutlinedButton(
                enabled = pm != null && envReady && !running,
                onClick = { runCmd(pm!!.clean()) },
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
    }
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
