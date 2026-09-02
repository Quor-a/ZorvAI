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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.ai.assistance.quro.core.linux.LinuxDistro
import com.ai.assistance.quro.core.linux.PackageManagerSpec
import com.ai.assistance.quro.core.linux.QuroLinuxEnv
import com.ai.assistance.quro.core.tools.QuroPrivateDbTool
import com.ai.assistance.quro.core.tools.QuroSandboxTool
import com.ai.assistance.quro.ui.icons.LucideIcon
import com.ai.assistance.quro.ui.theme.Muted
import com.ai.assistance.quro.core.miniapp.MiniAppEngine
import com.ai.assistance.quro.core.miniapp.MiniAppBridgeInterface
import com.ai.assistance.quro.core.tools.MiniAppStudioTool
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
        val base = name.substringBeforeLast(".", name).ifBlank { name }.ifBlank { "default" }
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

    // 导入工程文件（.qne / .json），读到文本后还原到画布
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()?.takeIf { it.isNotBlank() }?.let { txt ->
            wvRef.value?.evaluateJavascript("window.__restore(${JSONObject.quote(txt)})") {
                Toast.makeText(context, "已导入工程到画布", Toast.LENGTH_SHORT).show()
            }
        } ?: Toast.makeText(context, "导入失败：无法读取文件", Toast.LENGTH_SHORT).show()
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
                        else { val msg = writeFlow(flowName, snap); flowRefresh++; Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
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
                        }) { Text("打开") }
                        TextButton(onClick = {
                            if (File(flowDir, "$name.qne").delete()) { flowRefresh++; Toast.makeText(context, "已删除：$name", Toast.LENGTH_SHORT).show() }
                        }) { Text("删除") }
                    }
                }
            }
        }
        AndroidView(
            modifier = Modifier.fillMaxSize().weight(1f),
            factory = { ctx ->
                WebView(ctx).apply {
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            // 打开即恢复默认工程（AI 写入的节点流在此可见，无需手动打开）
                            val f = File(flowDir, "default.qne")
                            if (f.exists()) {
                                evaluateJavascript("window.__restore(${JSONObject.quote(f.readText(Charsets.UTF_8))})") {}
                            }
                        }
                    }
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

    Column(Modifier.fillMaxSize()) {
        if (current == null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("小程序工作室", style = MaterialTheme.typography.titleMedium, color = cs.onSurface, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        MiniAppStudioTool().run(context, JSONObject().put("action", "create").put("name", "demo").toString())
                        withContext(Dispatchers.Main) { refreshKey++; Toast.makeText(context, "已创建示例小程序 demo", Toast.LENGTH_SHORT).show() }
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
