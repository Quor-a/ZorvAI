package com.ai.assistance.quro.terminal.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.terminal.moyu.MoyuPkg
import com.ai.assistance.quro.terminal.moyu.MoyuRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 墨语言控制台。
 *
 * 设计原则（2026-10-23 重做）：**克制**。
 * 主界面只有三块「运行时 / 我的程序 / 资源」，二级内容（示例、文档、标准库、依赖、网络）
 * 一律点进去看，不再把所有东西摊在一个页面上。
 * 视觉只用一种强调色 + 不透明卡片 + 细边框，不用渐变和玻璃叠层。
 */
@Composable
fun MoyuScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val rt = remember { MoyuRuntime.get(context) }
    val scope = rememberCoroutineScope()
    val pkg = remember { MoyuPkg(context, rt) }

    // 页面路由：null = 首页
    var page by remember { mutableStateOf<String?>(null) }

    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }

    var editing by remember { mutableStateOf<File?>(null) }
    var editText by remember { mutableStateOf("") }
    var showNew by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var viewing by remember { mutableStateOf<Pair<String, String>?>(null) }
    var deleteTarget by remember { mutableStateOf<File?>(null) }

    fun refresh() { tick++ }

    fun runSafe(block: suspend () -> String) {
        busy = true
        scope.launch {
            output = withContext(Dispatchers.IO) { block() }
            refresh()
            busy = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(TerminalTheme.backgroundColor)
    ) {
        // ── 顶栏 ──
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { if (page != null) page = null else onBack() },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(Icons.Default.ArrowBack, "返回", tint = TerminalTheme.onSurfaceColor)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = when (page) {
                        null -> "墨语言"
                        "examples" -> "示例"
                        "docs" -> "文档"
                        "libs" -> "标准库"
                        "deps" -> "依赖包"
                        "net" -> "网络"
                        else -> "墨语言"
                    },
                    color = TerminalTheme.onSurfaceColor,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (page == null) {
                    Text(
                        "v" + rt.version() + " · 独立运行，不经终端",
                        color = TerminalTheme.onSurfaceVariant,
                        fontSize = 10.5.sp
                    )
                }
            }
            if (busy) {
                CircularProgressIndicator(
                    Modifier.size(15.dp), strokeWidth = 2.dp,
                    color = TerminalTheme.accentColor
                )
                Spacer(Modifier.width(8.dp))
            }
        }

        // ── 内容 ──
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            when (page) {
                null -> HomeContent(
                    rt = rt, busy = busy, tick = tick,
                    onInit = {
                        runSafe {
                            val r = rt.ensureInstalled { m -> status = m }
                            if (r.isFailure) "解压失败：" + (r.exceptionOrNull()?.message ?: "")
                            else "运行时已就绪"
                        }
                    },
                    onNew = { newName = "demo" + (System.currentTimeMillis() % 1000) + ".mo"; showNew = true },
                    onEdit = { f -> editing = f; editText = runCatching { f.readText() }.getOrDefault("") },
                    onRun = { f ->
                        runSafe {
                            if (f.name.endsWith(".mo")) compileRun(rt, f)
                            else {
                                val r = rt.exec(f)
                                "退出码 " + r.code + "\n" + r.output.trim()
                            }
                        }
                    },
                    onDelete = { f -> deleteTarget = f },
                    onOpen = { p -> page = p }
                )

                "examples" -> ResourcePage(
                    title = "examples", rt = rt, tick = tick,
                    allowCopy = true, workspace = rt.workspace,
                    onCopied = { name -> status = "已复制 " + name; refresh() },
                    onView = { f -> viewing = f.name to runCatching { f.readText() }.getOrDefault("") }
                )

                "docs" -> ResourcePage(
                    title = "docs", rt = rt, tick = tick,
                    allowCopy = false, workspace = rt.workspace,
                    onCopied = {},
                    onView = { f -> viewing = f.name to runCatching { f.readText() }.getOrDefault("") }
                )

                "libs" -> ResourcePage(
                    title = "lib", rt = rt, tick = tick,
                    allowCopy = false, workspace = rt.workspace,
                    onCopied = {},
                    onView = { f -> viewing = f.name to runCatching { f.readText() }.getOrDefault("") }
                )

                "deps" -> DepsPage(rt = rt, pkg = pkg, tick = tick, busy = busy, runSafe = { blk -> runSafe(blk) }, onChanged = { refresh() })

                "net" -> NetPage(rt = rt, pkg = pkg, busy = busy, runSafe = { blk -> runSafe(blk) })
            }

            if (status.isNotBlank()) {
                Block { SimpleText(status) }
            }
            if (output.isNotBlank()) {
                Block {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SimpleText("输出", TerminalTheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        Tap("清空") { output = "" }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        output.takeLast(5000),
                        color = TerminalTheme.onSurfaceColor,
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }

    // ── 对话框 ──
    if (showNew) {
        AlertDialog(
            onDismissRequest = { showNew = false },
            title = { Text("新建程序") },
            text = {
                OutlinedTextField(
                    value = newName, onValueChange = { newName = it },
                    label = { Text("文件名") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val f = rt.writeSource(newName.trim().ifBlank { "demo.mo" }, SAMPLE)
                    showNew = false; refresh(); editing = f; editText = SAMPLE
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showNew = false }) { Text("取消") } }
        )
    }

    editing?.let { file ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(file.name, fontSize = 14.sp) },
            text = {
                OutlinedTextField(
                    value = editText, onValueChange = { editText = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 380.dp),
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                )
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        runCatching { file.writeText(editText) }
                        editing = null; refresh(); status = "已保存"
                        runSafe { compileRun(rt, file) }
                    }) { Text("保存并运行") }
                    TextButton(onClick = {
                        runCatching { file.writeText(editText) }
                        editing = null; refresh(); status = "已保存"
                    }) { Text("保存") }
                }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } }
        )
    }

    viewing?.let { pair ->
        AlertDialog(
            onDismissRequest = { viewing = null },
            title = { Text(pair.first, fontSize = 13.sp) },
            text = {
                Column(
                    Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 400.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        pair.second.take(20000), color = TerminalTheme.onSurfaceColor,
                        fontSize = 10.5.sp, fontFamily = FontFamily.Monospace
                    )
                }
            },
            confirmButton = { TextButton(onClick = { viewing = null }) { Text("关闭") } }
        )
    }

    deleteTarget?.let { f ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除 " + f.name + "？") },
            text = { Text("不可恢复。", fontSize = 12.sp) },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { f.delete() }
                    deleteTarget = null; refresh(); status = "已删除"
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }
}

// ═════════════ 首页：只放三块 ═════════════

@Composable
private fun HomeContent(
    rt: MoyuRuntime,
    busy: Boolean,
    tick: Int,
    onInit: () -> Unit,
    onNew: () -> Unit,
    onEdit: (File) -> Unit,
    onRun: (File) -> Unit,
    onDelete: (File) -> Unit,
    onOpen: (String) -> Unit,
) {
    val installed = rt.isInstalled()

    // ① 运行时
    Block {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Title("运行时")
            Spacer(Modifier.weight(1f))
            Dot(installed)
            Spacer(Modifier.width(6.dp))
            SimpleText(if (installed) "就绪" else "未初始化", TerminalTheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(10.dp))
        if (!installed) {
            SimpleText("首次使用需解压内置运行时（27MB，约 10-20 秒）。")
            Spacer(Modifier.height(10.dp))
            PrimaryAction(if (busy) "解压中…" else "初始化运行时", busy) { onInit() }
        } else {
            // 工具链一行概览，不铺表格
            val okCount = MoyuRuntime.TOOLS.count { rt.tool(it).exists() }
            SimpleText("工具链 " + okCount + "/" + MoyuRuntime.TOOLS.size + " 就绪 · qemu 已内置")
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallAction("重新解压") { onInit() }
                SmallAction("网络与协议") { onOpen("net") }
            }
        }
    }

    // ② 我的程序
    val files = remember(tick) {
        rt.workspace.listFiles { f -> f.isFile && f.name.endsWith(".mo") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
    Block {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Title("我的程序")
            Spacer(Modifier.weight(1f))
            SmallAction("新建") { onNew() }
        }
        Spacer(Modifier.height(6.dp))
        if (files.isEmpty()) {
            SimpleText("还没有程序。点「新建」写第一个。")
        } else {
            files.forEach { f ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        f.name, color = TerminalTheme.onSurfaceColor, fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f).clickable { onEdit(f) }
                    )
                    Tap("编辑") { onEdit(f) }
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = { onRun(f) }, modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.Default.PlayArrow, "运行",
                            tint = TerminalTheme.accentColor, modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(onClick = { onDelete(f) }, modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.Default.ArrowBack, "删除",
                            tint = TerminalTheme.onSurfaceVariant.copy(alpha = 0.45f),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }

    // ③ 资源（全部二级页）
    Block {
        Title("资源")
        Spacer(Modifier.height(4.dp))
        val exCount = remember(tick) {
            File(rt.moHome, "examples").listFiles { f -> f.isFile && f.name.endsWith(".mo") }?.size ?: 0
        }
        val docCount = remember(tick) {
            File(rt.moHome, "docs").listFiles { f -> f.isFile && f.name.endsWith(".md") }?.size ?: 0
        }
        val libCount = remember(tick) {
            File(rt.moHome, "lib").listFiles { f -> f.isFile && f.name.endsWith(".mo") }?.size ?: 0
        }
        val depCount = remember(tick) {
            File(rt.workspace, "deps").listFiles { f -> f.isDirectory }?.size ?: 0
        }
        NavRow("示例", exCount.toString() + " 个") { onOpen("examples") }
        NavRow("文档", docCount.toString() + " 篇") { onOpen("docs") }
        NavRow("标准库", libCount.toString() + " 个") { onOpen("libs") }
        NavRow("依赖包", if (depCount == 0) "未安装" else depCount.toString() + " 个") { onOpen("deps") }
    }
}

// ═════════════ 二级：资源列表 ═════════════

@Composable
private fun ResourcePage(
    title: String,
    rt: MoyuRuntime,
    tick: Int,
    allowCopy: Boolean,
    workspace: File,
    onCopied: (String) -> Unit,
    onView: (File) -> Unit,
) {
    val files = remember(tick) {
        val dir = File(rt.moHome, title)
        val ext = if (title == "docs") ".md" else ".mo"
        dir.listFiles { f -> f.isFile && f.name.endsWith(ext) }?.sortedBy { it.name } ?: emptyList()
    }
    Block {
        SimpleText("共 " + files.size + " 项", TerminalTheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        files.forEach { f ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f).clickable { onView(f) }) {
                    Text(
                        f.name, color = TerminalTheme.onSurfaceColor, fontSize = 12.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        (f.length() / 1024).toString() + " KB",
                        color = TerminalTheme.onSurfaceVariant, fontSize = 9.5.sp
                    )
                }
                if (allowCopy) {
                    Tap("复制") {
                        runCatching { f.copyTo(File(workspace, f.name), overwrite = false) }
                        onCopied(f.name)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Tap("查看") { onView(f) }
            }
        }
    }
}

// ═════════════ 二级：依赖 ═════════════

@Composable
private fun DepsPage(
    rt: MoyuRuntime,
    pkg: MoyuPkg,
    tick: Int,
    busy: Boolean,
    runSafe: (suspend () -> String) -> Unit,
    onChanged: () -> Unit,
) {
    var gitUrl by remember { mutableStateOf("") }
    var gitRef by remember { mutableStateOf("main") }
    val projName = remember(tick) { pkg.packageField("name") }

    Block {
        Title("项目")
        Spacer(Modifier.height(6.dp))
        if (projName == null) {
            SimpleText("还没有 mo.toml。初始化后才能声明依赖。")
            Spacer(Modifier.height(10.dp))
            PrimaryAction("初始化项目", busy) { runSafe { pkg.init("demo") }; onChanged() }
        } else {
            SimpleText(projName + " · deps/ 与 mo.lock 已启用")
        }
    }

    Block {
        Title("安装")
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = gitUrl, onValueChange = { gitUrl = it },
            label = { Text("git 地址") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = gitRef, onValueChange = { gitRef = it },
                label = { Text("ref") }, singleLine = true,
                modifier = Modifier.width(110.dp),
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            )
            Spacer(Modifier.width(10.dp))
            PrimaryAction("安装", busy || gitUrl.isBlank()) {
                runSafe {
                    pkg.install(gitUrl.trim(), gitRef.trim().ifBlank { "main" })
                        .fold({ it }, { "失败：" + (it.message ?: "") })
                }
                onChanged()
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallAction("按 mo.toml 拉取") { runSafe { pkg.fetch().fold({ it }, { "失败：" + (it.message ?: "") }) } }
            SmallAction("校验") { runSafe { pkg.verify() } }
            SmallAction("依赖树") { runSafe { pkg.tree() } }
        }
    }

    val installed = remember(tick) { pkg.listInstalled() }
    Block {
        Title("已安装 " + installed.size)
        Spacer(Modifier.height(4.dp))
        if (installed.isEmpty()) {
            SimpleText("暂无依赖。")
        } else {
            installed.forEach { pair ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            pair.first.name, color = TerminalTheme.onSurfaceColor,
                            fontSize = 12.5.sp, fontFamily = FontFamily.Monospace
                        )
                        Text(
                            pair.second, color = TerminalTheme.onSurfaceVariant,
                            fontSize = 9.5.sp, maxLines = 1
                        )
                    }
                    Tap("移除") { pkg.remove(pair.first.name); onChanged() }
                }
            }
        }
    }
}

// ═════════════ 二级：网络 ═════════════

@Composable
private fun NetPage(
    rt: MoyuRuntime,
    pkg: MoyuPkg,
    busy: Boolean,
    runSafe: (suspend () -> String) -> Unit,
) {
    var port by remember { mutableStateOf("8080") }

    Block {
        Title("网络")
        Spacer(Modifier.height(6.dp))
        SimpleText(
            "墨语言的 socket 经 qemu 直通 Android 内核，用的是 ZorvAI 的网络，" +
                "可收发外网流量，也可在本机监听端口。"
        )
    }

    Block {
        Title("HTTP 服务")
        Spacer(Modifier.height(6.dp))
        SimpleText("用 examples/httpd.mo 现场编译并启动。")
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                label = { Text("端口") }, singleLine = true,
                modifier = Modifier.width(110.dp),
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            )
            Spacer(Modifier.width(10.dp))
            PrimaryAction("启动", busy) {
                val p = port.toIntOrNull() ?: 8080
                runSafe {
                    val ex = File(rt.moHome, "examples/httpd.mo")
                    if (!ex.exists()) return@runSafe "找不到 examples/httpd.mo"
                    val work = File(rt.workspace, "httpd.mo")
                    runCatching { ex.copyTo(work, overwrite = true) }
                    val c = rt.compile(work, File(rt.workspace, "httpd.elf"))
                    if (!c.ok) return@runSafe "编译失败：\n" + c.output.trim()
                    val r = rt.exec(File(rt.workspace, "httpd.elf"), listOf(p.toString()), timeoutMs = 4000)
                    r.output.trim() + "\n\n监听 127.0.0.1:" + p
                }
            }
        }
    }

    Block {
        Title("能力清单")
        Spacer(Modifier.height(6.dp))
        listOf(
            "TCP 监听/连接" to "lib/net.mo",
            "HTTP 服务端" to "examples/httpd.mo",
            "慢连接超时" to "examples/httpto.mo",
            "异步 IO" to "lib/aio.mo",
            "JSON" to "lib/json.mo",
            "TLS/HTTPS" to "无（需自实现）",
        ).forEach { p ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(p.first, color = TerminalTheme.onSurfaceColor, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(p.second, color = TerminalTheme.accentColor.copy(alpha = 0.75f), fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace)
            }
        }
    }

    Block {
        Title("下载源诊断")
        Spacer(Modifier.height(6.dp))
        SimpleText("装依赖走「直连 → ghfast → gh-proxy → ghproxy」四级回退。")
        Spacer(Modifier.height(10.dp))
        SmallAction("测试各源可达性") { runSafe { pkg.netDiag() } }
    }
}

// ═════════════ 基础组件（极小集合）════════════

@Composable
private fun Block(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = TerminalTheme.surfaceColor,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
    ) {
        Column(Modifier.padding(14.dp)) {
            content()
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(text, color = TerminalTheme.onSurfaceColor, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun SimpleText(text: String, color: Color = TerminalTheme.onSurfaceVariant) {
    Text(text, color = color, fontSize = 11.5.sp)
}

@Composable
private fun Dot(ok: Boolean) {
    Box(
        Modifier
            .size(7.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (ok) TerminalTheme.successColor else TerminalTheme.onSurfaceVariant.copy(alpha = 0.4f))
    )
}

@Composable
private fun PrimaryAction(text: String, disabled: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(11.dp))
            .clickable(enabled = !disabled) { onClick() },
        color = if (disabled) TerminalTheme.primaryColor.copy(alpha = 0.35f) else TerminalTheme.primaryColor,
        shape = RoundedCornerShape(11.dp)
    ) {
        Text(
            text, color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
        )
    }
}

@Composable
private fun SmallAction(text: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .clickable { onClick() },
        color = Color.White.copy(alpha = 0.06f),
        shape = RoundedCornerShape(9.dp)
    ) {
        Text(
            text, color = TerminalTheme.onSurfaceColor, fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun Tap(text: String, onClick: () -> Unit) {
    Text(
        text, color = TerminalTheme.accentColor, fontSize = 11.5.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 4.dp)
    )
}

@Composable
private fun NavRow(label: String, meta: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(vertical = 11.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TerminalTheme.onSurfaceColor, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(meta, color = TerminalTheme.onSurfaceVariant, fontSize = 10.5.sp)
        Spacer(Modifier.width(4.dp))
        Icon(
            Icons.Default.KeyboardArrowRight, null,
            tint = TerminalTheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(18.dp)
        )
    }
}

private suspend fun compileRun(rt: MoyuRuntime, src: File): String {
    val (c, r) = rt.compileAndRun(src)
    return buildString {
        appendLine("编译 → " + (if (c.timedOut) "超时" else "退出码 " + c.code))
        if (c.output.isNotBlank()) appendLine(c.output.trim())
        appendLine()
        append("运行 → " + (if (r == null) "编译失败" else if (r.timedOut) "超时" else "退出码 " + r.code))
        if (r != null && r.output.isNotBlank()) append("\n" + r.output.trim())
    }
}

private const val SAMPLE = "func main() {\n" +
    "    print(\"Hello, Mo!\")\n" +
    "}\n"
