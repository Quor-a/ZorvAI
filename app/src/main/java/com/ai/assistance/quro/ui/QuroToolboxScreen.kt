@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ai.assistance.quro.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.BackHandler
import com.ai.assistance.quro.core.tools.GetPackageNameTool
import com.ai.assistance.quro.core.tools.AiwpsCreateTool
import com.ai.assistance.quro.core.tools.ImportedToolDef
import com.ai.assistance.quro.core.tools.QuroTool
import com.ai.assistance.quro.core.tools.QuroToolRegistry
import com.ai.assistance.quro.core.tools.QuroImportedToolRegistry
import com.ai.assistance.quro.ui.theme.Accent
import com.ai.assistance.quro.ui.theme.AccentSoft
import com.ai.assistance.quro.ui.theme.Line
import com.ai.assistance.quro.ui.theme.Muted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.ai.assistance.quro.core.media.QuroDocLauncher
import com.ai.assistance.quro.service.QuroPasteKeyboardService
import java.io.File

/**
 * 工具箱：集中呈现 Quro 的本地工具能力。
 * 入口：对话框输入框「+」工具 → 工具箱。内部可切换 文件管理 / 查看包名 / 工作区。
 * 内置浏览器已移至输入框「+」工具，不在此处。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuroToolboxScreen(
    onClose: () -> Unit,
    onOpenOnlyOffice: () -> Unit = {},
    onOpenMusic: () -> Unit = {},
    onOpenVideo: (String, String) -> Unit = { _: String, _: String -> },
    allTools: List<QuroTool> = emptyList(),
    onImportTool: (ImportedToolDef) -> Unit = {},
) {
    var screen by remember { mutableStateOf("home") }
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val scaled: (Int) -> androidx.compose.ui.unit.TextUnit = { it.sp }
    var showDocGen by remember { mutableStateOf(false) }
    var showToolsList by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(cs.background)) {
        TopAppBar(
            title = { Text(if (screen == "home") "工具箱" else when (screen) {
                "files" -> "文件管理"
                "package" -> "查看软件包名"
                "workspace" -> "工作区"
                else -> "工具箱"
            }, style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold)) },
            navigationIcon = {
                IconButton(onClick = { if (screen == "home") onClose() else screen = "home" }) {
                    Icon(Icons.Filled.ArrowBack, null)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background),
        )
        when (screen) {
            "home" -> ToolboxHome(
                onOpenFiles = { screen = "files" },
                onOpenPackage = { screen = "package" },
                onOpenWorkspace = { screen = "workspace" },
                onOpenDocGen = { showDocGen = true },
                onOpenToolsList = { showToolsList = true },
                onOpenOnlyOffice = onOpenOnlyOffice,
                onOpenMusic = onOpenMusic,
                onOpenVideo = onOpenVideo,
                onOpenAvatar = { screen = "avatar" },
            )
            "files" -> QuroFileManager(onExitToHome = { screen = "home" })
            "package" -> PackageNameFinder()
            "workspace" -> WorkspaceScreen(onExitToHome = { screen = "home" })
            "avatar" -> QuroDigitalHumanScreen(onExitToHome = { screen = "home" })
        }
    }

    // —— 以下能力原在对话框「+」菜单，已移动到工具箱（移动而非复制，原位不再保留） ——
    if (showDocGen) {
        var docType by remember { mutableStateOf("docx") }
        var docTitle by remember { mutableStateOf("") }
        var docContent by remember { mutableStateOf("") }
        var docTemplate by remember { mutableStateOf("空白") }
        var docResult by remember { mutableStateOf<String?>(null) }
        val formats = listOf(
            "docx" to "Word", "xlsx" to "Excel", "pptx" to "PPT",
            "pdf" to "PDF", "md" to "Markdown", "txt" to "文本", "csv" to "表格", "html" to "网页"
        )
        val templates = mapOf(
            "空白" to "",
            "会议纪要" to "# 会议纪要\n时间：\n地点：\n参会人：\n议题：\n决议：\n行动项：",
            "合同" to "# 合作协议\n甲方：\n乙方：\n标的：\n金额：\n期限：\n违约责任：",
            "简历" to "# 个人简历\n姓名：\n联系方式：\n教育背景：\n工作经历：\n技能：",
            "报表" to "| 项目 | 数值 |\n| --- | --- |\n| 收入 | 0 |\n| 支出 | 0 |"
        )
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        AlertDialog(
            onDismissRequest = { showDocGen = false },
            confirmButton = {
                TextButton(onClick = {
                    val json = JSONObject().apply {
                        put("type", docType)
                        put("title", docTitle)
                        put("content", docContent)
                    }.toString()
                    // ★ ANR 修复：aiWPS 文档生成是重 I/O（写 zip/OOXML），
                    // 原本在点击回调（主线程）同步执行 → 阻塞 UI 线程触发「Zorv AI 没有响应」。
                    // 改为 IO 协程执行，结果回主线程落 state。
                    scope.launch(Dispatchers.IO) {
                        val r = runCatching { AiwpsCreateTool().run(ctx, json) }
                            .getOrElse { "生成失败：$it" }
                        withContext(Dispatchers.Main) { docResult = r }
                    }
                }) { Text("生成") }
            },
            dismissButton = { TextButton(onClick = { showDocGen = false }) { Text("关闭") } },
            title = { Text("文档生成（aiWPS）") },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    Text("格式", fontSize = scaled(12), color = Muted)
                    formats.chunked(4).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                            row.forEach { (t, label) ->
                                val sel = docType == t
                                Text(
                                    label,
                                    color = if (sel) cs.onPrimary else cs.onSurface,
                                    fontSize = scaled(12),
                                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                        .background(if (sel) cs.primary else cs.surfaceVariant)
                                        .clickable { docType = t }.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("模板（点击填充示例）", fontSize = scaled(12), color = Muted)
                    templates.keys.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                            row.forEach { name ->
                                val sel = docTemplate == name
                                Text(
                                    name,
                                    color = if (sel) cs.onPrimary else cs.onSurface,
                                    fontSize = scaled(11),
                                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                        .background(if (sel) cs.primary else cs.surfaceVariant)
                                        .clickable {
                                            docTemplate = name
                                            templates[name]?.let { docContent = it }
                                        }.padding(horizontal = 8.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(docTitle, { docTitle = it }, label = { Text("标题（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        docContent, { docContent = it }, label = { Text("正文") }, minLines = 4, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("docx 按换行分段；xlsx 按换行分行、制表/逗号分列；pptx 标题+正文；md/txt/csv/html 原样写入") }
                    )
                    Spacer(Modifier.height(8.dp))
                    if (docResult != null) {
                        val ok = (docResult ?: "").startsWith("已生成")
                        Text(docResult ?: "", fontSize = scaled(12), color = if (ok) cs.primary else cs.error)
                        if (ok) {
                            val path = Regex("""文档：(.+?)（""").find(docResult ?: "")?.groupValues?.getOrNull(1)
                            val genFile = path?.let { File(it.trim()) }
                            if (genFile != null) {
                                Spacer(Modifier.height(6.dp))
                                TextButton(onClick = {
                                    QuroDocLauncher.open(genFile)
                                }) { Text("打开文档", color = cs.primary) }
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("后台生成真实文件（docx/xlsx/pptx/pdf 为二进制格式，md/txt/csv/html 为纯文本），可用应用内查看器或 WPS / Office 打开；AI 也可在对话中直接调用 aiwps_create 工具。", fontSize = scaled(11), color = Muted)
                }
            }
        )
    }

    if (showToolsList) {
        // 可删除范围：技能工具（skill__<name>，删除会级联删技能）+ 导入工具（删除会持久化移除，防重启复活）
        val importedNames = remember(allTools) { QuroImportedToolRegistry.all().map { it.name }.toSet() }
        var toolList by remember(allTools) { mutableStateOf(allTools) }
        AlertDialog(
            onDismissRequest = { showToolsList = false },
            confirmButton = {},
            title = { Text("已注册工具（${toolList.size}）") },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    if (toolList.isEmpty()) {
                        Text("暂无已注册工具。", fontSize = scaled(12), color = Muted)
                    }
                    toolList.forEach { t ->
                        val deletable = t.name.startsWith("skill__") || t.name in importedNames
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(t.name, fontSize = scaled(12), color = cs.onSurface, modifier = Modifier.widthIn(min = 120.dp, max = 150.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(t.description.take(48), fontSize = scaled(11), color = Muted, maxLines = 1, modifier = Modifier.weight(1f))
                            if (deletable) {
                                IconButton(onClick = {
                                    // 反向级联：删 skill__ 工具 → QuroToolRegistry.remove 同步删技能；
                                    // 删导入工具 → 同步持久化移除，防重启复活（#913）
                                    QuroToolRegistry.active?.remove(t.name)
                                    if (t.name in importedNames) QuroImportedToolRegistry.remove(ctx, t.name)
                                    toolList = toolList.filter { it.name != t.name }
                                }) {
                                    Icon(Icons.Filled.Delete, "删除", Modifier.size(18.dp), tint = cs.error)
                                }
                            }
                        }
                        HorizontalDivider(Modifier.padding(vertical = 2.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { showImport = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Download, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("导入工具（AI 自写 / 粘贴 JSON）")
                    }
                }
            }
        )
    }

    if (showImport) {
        var jsonText by remember { mutableStateOf("") }
        var err by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showImport = false },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        val o = JSONObject(jsonText)
                        val name = o.optString("name", "").trim()
                        val kind = o.optString("kind", "http").trim()
                        if (name.isBlank()) { err = "name 不能为空"; return@TextButton }
                        if (kind !in setOf("http", "intent", "broadcast")) { err = "kind 仅支持 http / intent / broadcast"; return@TextButton }
                        onImportTool(
                            ImportedToolDef(
                                name = name,
                                description = o.optString("description", ""),
                                parametersJson = o.optString("parametersJson", ""),
                                kind = kind,
                                config = o.optString("config", "{}"),
                            )
                        )
                        showImport = false
                        jsonText = ""
                        err = ""
                    } catch (e: Exception) {
                        err = "JSON 解析失败：${e.message}"
                    }
                }) { Text("导入") }
            },
            dismissButton = { TextButton(onClick = { showImport = false }) { Text("取消") } },
            title = { Text("导入工具（AI 自写 / 粘贴 JSON）") },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        jsonText, { jsonText = it },
                        label = { Text("工具 JSON") },
                        minLines = 6, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("{\"name\":\"my_tool\",\"description\":\"...\",\"parametersJson\":\"{...}\",\"kind\":\"http\",\"config\":\"{\\\"url\\\":\\\"https://...\\\"}\"}") },
                    )
                    if (err.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(err, color = cs.error, fontSize = scaled(11))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("支持 kind：http / intent / broadcast。也可让 AI 生成 JSON 后粘贴导入（AI 自写工具）。", fontSize = scaled(11), color = Muted)
                }
            }
        )
    }
}

@Composable
private fun ToolboxHome(
    onOpenFiles: () -> Unit,
    onOpenPackage: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onOpenDocGen: () -> Unit,
    onOpenToolsList: () -> Unit,
    onOpenOnlyOffice: () -> Unit,
    onOpenMusic: () -> Unit,
    onOpenVideo: (String, String) -> Unit,
    onOpenAvatar: () -> Unit,
) {
    val ctx = LocalContext.current
    val tools = listOf(
        ToolItem(Icons.Filled.Folder, "文件管理", "浏览应用文件、查看文本/代码内容", onOpenFiles),
        ToolItem(Icons.Filled.Apps, "查看软件包名", "输入应用显示名，反查其精确包名", onOpenPackage),
        ToolItem(Icons.Filled.FolderOpen, "工作区", "在应用沙箱内创建/编辑文件与文件夹", onOpenWorkspace),
        ToolItem(Icons.Filled.Article, "文档生成", "生成 Word/Excel/PPT/PDF 等真实文档", onOpenDocGen),
        ToolItem(Icons.Filled.List, "已有工具", "查看已注册工具，可导入 AI 自写工具", onOpenToolsList),
        ToolItem(Icons.Filled.Description, "WPS文档", "用 ONLYOFFICE 打开 / 编辑 Office 文档", onOpenOnlyOffice),
        ToolItem(Icons.Filled.MusicNote, "音乐播放器", "在应用内播放本地音乐（后台持续播放）", onOpenMusic),
        ToolItem(Icons.Filled.Movie, "视频播放器", "在应用内全功能视频播放器播放本地视频", { onOpenVideo("", "") }),
        ToolItem(Icons.Filled.Person, "数字人", "云端口/离线可选·可自制 3D 模型·语音→LLM→TTS 闭环", onClick = onOpenAvatar),
        ToolItem(Icons.Filled.Keyboard, "AI 键盘", "AI 替你打字·注册为系统输入法·任意 App 可用", onClick = {
            // 打开系统输入法设置页，引导用户启用 Zorv AI 键盘
            try {
                ctx.startActivity(android.content.Intent("android.settings.INPUT_METHOD_SETTINGS"))
            } catch (_: Exception) {
                ctx.startActivity(android.content.Intent("android.settings.INPUT_METHOD_SUBTYPE_SETTINGS"))
            }
        }),
    )
    // 使用手动 Row+weight 布局替代 LazyVerticalGrid（GridCells.Fixed(2) 在某些容器内退化为单列）
    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GroupCaption("本地工具能力，全部在设备上运行，无需联网即可使用大部分功能。")
        tools.chunked(2).forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                row.forEach { t ->
                    ToolboxCard(t.icon, t.title, t.subtitle, t.onClick, Modifier.weight(1f))
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private data class ToolItem(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val onClick: () -> Unit,
)

/** 工具箱首页卡片：2 列网格布局，描述完整换行不截断（区别于设置页窄宽 ToolCard）。 */
@Composable
private fun ToolboxCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(cs.surfaceVariant)
            .border(1.dp, Line, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(24.dp), tint = Accent)
            Spacer(Modifier.width(10.dp))
            Text(
                title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                color = cs.onSurface, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            subtitle, fontSize = 12.sp, color = cs.onSurfaceVariant,
            lineHeight = 16.sp,
        )
    }
}

// ==================== 文件管理 ====================

@Composable
private fun QuroFileManager(onExitToHome: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val internalRoot = ctx.filesDir.absolutePath
    val externalRoot = ctx.getExternalFilesDir(null)?.absolutePath ?: internalRoot
    var currentPath by remember { mutableStateOf<String?>(null) }
    var content by remember { mutableStateOf<String?>(null) }

    // 系统返回键逐级回退：先关预览 → 回上层目录 → 回工具箱首页；已在首页则交还外层关闭
    BackHandler {
        when {
            content != null -> content = null
            currentPath != null -> currentPath = null
            else -> onExitToHome()
        }
    }

    val entries = remember(currentPath) {
        if (currentPath == null) {
            listOf(
                FileEntry("📂 内部存储 (应用私有)", internalRoot, true),
                FileEntry("📂 外部存储 (应用私有)", externalRoot, true),
            )
        } else {
            val dir = File(currentPath)
            val list = dir.listFiles()?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() }) ?: emptyList()
            list.map { FileEntry(it.name + if (it.isDirectory) "/" else "", it.absolutePath, it.isDirectory) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 路径面包屑
        Surface(color = cs.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
            Text(
                currentPath ?: "根目录（应用可访问范围）",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        if (content != null) {
            // 文件内容预览
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { content = null }) { Text("← 返回列表") }
                    Spacer(Modifier.weight(1f))
                }
                SelectionContainer(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
                    Text(content ?: "", fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = cs.onSurface)
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(entries) { e ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {
                                if (e.isDir) currentPath = e.path
                                else {
                                    val f = File(e.path)
                                    content = if (f.length() > 512 * 1024) "文件过大（${(f.length() / 1024)}KB），建议用其他方式查看"
                                    else runCatching { f.readText(Charsets.UTF_8) }.getOrNull() ?: "（无法读取，可能不是文本文件）"
                                }
                            }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (e.isDir) Icons.Filled.Folder else Icons.Filled.Description,
                            null, tint = if (e.isDir) cs.primary else cs.onSurfaceVariant, modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(e.name, fontSize = 14.sp, color = cs.onSurface, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

private data class FileEntry(val name: String, val path: String, val isDir: Boolean)

// ==================== 查看软件包名 ====================

@Composable
private fun PackageNameFinder() {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var name by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("输入应用显示名（如「微信」「快手」），反查其精确包名。", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("应用名称") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        PrimaryButton(
            text = "查询包名",
            enabled = name.trim().isNotEmpty(),
            onClick = {
                scope.launch(Dispatchers.IO) {
                    val r = GetPackageNameTool().run(ctx, JSONObject().put("app_name", name.trim()).toString())
                    withContext(Dispatchers.Main) { result = r }
                }
            },
        )
        Spacer(Modifier.height(16.dp))
        result?.let {
            SelectionContainer(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text(it, fontSize = 15.sp, color = cs.onSurface)
            }
        }
    }
}

// ==================== 工作区 ====================

@Composable
private fun WorkspaceScreen(onExitToHome: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val root = remember { File(ctx.getExternalFilesDir(null), "QuroWorkspace").apply { mkdirs() } }
    var currentPath by remember { mutableStateOf<String?>(null) }
    var content by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var showNewFile by remember { mutableStateOf(false) }
    var showNewFolder by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newFileContent by remember { mutableStateOf("") }

    // 系统返回键逐级回退：先关预览 → 回上层目录 → 回工具箱首页
    BackHandler {
        when {
            content != null -> content = null
            currentPath != null -> currentPath = null
            else -> onExitToHome()
        }
    }

    val dir = if (currentPath == null) root else File(currentPath!!)
    val entries = remember(currentPath, refresh) {
        if (currentPath == null) {
            listOf(FileEntry("📂 根目录 (QuroWorkspace)", root.absolutePath, true))
        } else {
            val list = dir.listFiles()?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() }) ?: emptyList()
            list.map { FileEntry(it.name + if (it.isDirectory) "/" else "", it.absolutePath, it.isDirectory) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Surface(color = cs.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
            Text(
                currentPath ?: "工作区根目录（QuroWorkspace）",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(text = "新建文件", modifier = Modifier.weight(1f), onClick = { newName = ""; newFileContent = ""; showNewFile = true })
            PrimaryButton(text = "新建文件夹", modifier = Modifier.weight(1f), onClick = { newName = ""; showNewFolder = true })
        }
        if (content != null) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { content = null }) { Text("← 返回列表") }
                    Spacer(Modifier.weight(1f))
                }
                SelectionContainer(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
                    Text(content ?: "", fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = cs.onSurface)
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(entries) { e ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {
                                if (e.isDir) currentPath = e.path
                                else {
                                    val f = File(e.path)
                                    content = if (f.length() > 512 * 1024) "文件过大（${(f.length() / 1024)}KB），建议用其他方式查看"
                                    else runCatching { f.readText(Charsets.UTF_8) }.getOrNull() ?: "（无法读取，可能不是文本文件）"
                                }
                            }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (e.isDir) Icons.Filled.Folder else Icons.Filled.Description,
                            null, tint = if (e.isDir) cs.primary else cs.onSurfaceVariant, modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(e.name, fontSize = 14.sp, color = cs.onSurface, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = {
                            val f = File(e.path)
                            runCatching { if (f.isDirectory) f.deleteRecursively() else f.delete() }
                            refresh++
                        }) {
                            Icon(Icons.Filled.Delete, null, Modifier.size(18.dp), tint = cs.error)
                        }
                    }
                }
            }
        }
    }

    if (showNewFile) {
        AlertDialog(
            onDismissRequest = { showNewFile = false },
            title = { Text("新建文件") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(newName, { newName = it }, label = { Text("文件名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(newFileContent, { newFileContent = it }, label = { Text("内容") }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp))
                }
            },
            confirmButton = {
                Button(onClick = {
                    val name = newName.trim()
                    if (name.isNotEmpty()) {
                        val f = File(dir, name)
                        if (!f.exists()) f.writeText(newFileContent, Charsets.UTF_8)
                        refresh++
                    }
                    showNewFile = false
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showNewFile = false }) { Text("取消") } },
        )
    }

    if (showNewFolder) {
        AlertDialog(
            onDismissRequest = { showNewFolder = false },
            title = { Text("新建文件夹") },
            text = {
                OutlinedTextField(newName, { newName = it }, label = { Text("文件夹名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                Button(onClick = {
                    val name = newName.trim()
                    if (name.isNotEmpty()) {
                        val d = File(dir, name)
                        if (!d.exists()) d.mkdirs()
                        refresh++
                    }
                    showNewFolder = false
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showNewFolder = false }) { Text("取消") } },
        )
    }
}
