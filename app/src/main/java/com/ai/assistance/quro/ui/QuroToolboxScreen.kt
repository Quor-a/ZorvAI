@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ai.assistance.quro.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
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
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
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
    onOpenWorkflow: () -> Unit = {},
) {
    var screen by remember { mutableStateOf("home") }
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val scaled: (Int) -> androidx.compose.ui.unit.TextUnit = { it.sp }
    var showDocGen by remember { mutableStateOf(false) }
    var showToolsList by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var editorFile by remember { mutableStateOf<File?>(null) }
    var showEditor by remember { mutableStateOf(false) }

    // 系统返回键逐级回退：内部子页面(包名/工作流/文件/工作区) → 工具箱首页 → 关闭工具箱，
    // 保证「一层一层返回」。对话框/编辑器打开时让位给它自身关闭逻辑，避免误关整个工具箱。
    BackHandler(enabled = !showDocGen && !showToolsList && !showImport && !showEditor) {
        if (screen == "home") onClose() else screen = "home"
    }

    // 工作流作为工具箱内的一个子页面（自带 TopAppBar），返回直接回到工具箱首页，保证「一层返回」。
    if (screen == "workflow") {
        QuroWorkflowScreen(onClose = { screen = "home" })
        return
    }

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
                onOpenWorkflow = { screen = "workflow" },
            )
            "files" -> QuroFileManager(onExitToHome = { screen = "home" })
            "package" -> PackageNameFinder()
            "workspace" -> WorkspaceScreen(onExitToHome = { screen = "home" })
        }
    }

    // —— 以下能力原在对话框「+」菜单，已移动到工具箱（移动而非复制，原位不再保留） ——
    if (showDocGen) {
        var docType by remember { mutableStateOf("docx") }
        var docTitle by remember { mutableStateOf("") }
        var docContent by remember { mutableStateOf("") }
        var docTemplate by remember { mutableStateOf("空白") }
        var docResult by remember { mutableStateOf<String?>(null) }
        // 编辑器模式：edit = 富文本编辑, preview = 实时预览
        var editorMode by remember { mutableStateOf("edit") }
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

        // 辅助：在光标位置插入 Markdown 格式标记
        var cursorPos by remember { mutableIntStateOf(0) }

        AlertDialog(
            onDismissRequest = { showDocGen = false },
            confirmButton = {
                TextButton(onClick = {
                    // 创建空白文档并打开编辑器
                    val json = JSONObject().apply {
                        put("type", docType)
                        put("title", docTitle.ifBlank { "${docType}_${System.currentTimeMillis()}" })
                        put("content", docContent.ifBlank { " " }) // 空白内容
                    }.toString()
                    scope.launch(Dispatchers.IO) {
                        val r = runCatching { AiwpsCreateTool().run(ctx, json) }
                            .getOrElse { "生成失败：$it" }
                        withContext(Dispatchers.Main) {
                            docResult = r
                            // 从结果中提取文件路径并打开编辑器（兼容多种格式）
                            val path = when {
                                r.contains("已生成") -> {
                                    Regex("""已生成\s+\w+\s+文档：(.+?)（""").find(r)?.groupValues?.getOrNull(1)
                                }
                                r.contains("文档：") -> {
                                    Regex("""文档：(.+?)（""").find(r)?.groupValues?.getOrNull(1)
                                }
                                else -> null
                            }
                            path?.let {
                                showDocGen = false
                                editorFile = File(it.trim())
                                showEditor = true
                            }
                        }
                    }
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showDocGen = false }) { Text("关闭") } },
            title = { Text("新建文档") },
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

                    // —— 编辑/预览模式切换 ——
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(cs.surfaceVariant)
                            .padding(2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        listOf("edit" to "✏️ 编辑", "preview" to "👁️ 预览").forEach { (mode, label) ->
                            val selected = editorMode == mode
                            Text(
                                label,
                                color = if (selected) cs.onPrimary else cs.onSurfaceVariant,
                                fontSize = scaled(12),
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) cs.primary else Color.Transparent)
                                    .clickable { editorMode = mode }
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                                    .wrapContentWidth(Alignment.CenterHorizontally),
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))

                    // —— 富文本格式化工具栏 ——
                    if (editorMode == "edit") {
                        val toolbarBtnColor = cs.surfaceVariant
                        val toolbarTextColor = cs.onSurface
                        Column(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(toolbarBtnColor)
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        ) {
                            // 第一行：标题 + 加粗 + 斜体 + 列表
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                listOf(
                                    "H1" to "# ",
                                    "H2" to "## ",
                                    "H3" to "### ",
                                    "**B**" to "**",
                                    "_I_" to "_",
                                    "• 列表" to "- ",
                                    "1." to "1. ",
                                ).forEach { (label, prefix) ->
                                    Text(
                                        label,
                                        fontSize = scaled(11),
                                        fontWeight = FontWeight.Medium,
                                        color = toolbarTextColor,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(cs.background)
                                            .clickable {
                                                val insertText = if (prefix.length == 1 && (prefix == "*" || prefix == "_")) {
                                                    "${prefix}粗体文本${prefix}"
                                                } else prefix
                                                docContent = docContent + insertText
                                            }
                                            .padding(horizontal = 8.dp, vertical = 5.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            // 第二行：表格 + 分割线 + 引用 + 链接 + 代码块
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                listOf(
                                    "📊 表格" to "| 列1 | 列2 |\n| --- | --- |\n| 数据 | 数据 |",
                                    "━━ 分割" to "\n---\n",
                                    "> 引用" to "> 引用文本\n",
                                    "🔗 链接" to "[链接文本](https://)",
                                    "</> 代码" to "```\n代码\n```\n",
                                    "☑️ 待办" to "- [ ] 待办事项\n",
                                ).forEach { (label, insert) ->
                                    Text(
                                        label,
                                        fontSize = scaled(10),
                                        color = toolbarTextColor,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(cs.background)
                                            .clickable { docContent = docContent + insert }
                                            .padding(horizontal = 6.dp, vertical = 5.dp),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            docContent, { docContent = it; cursorPos = it.length },
                            label = { Text("正文（支持 Markdown 排版）") },
                            minLines = 6,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 320.dp),
                            textStyle = LocalTextStyle.current.copy(
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 18.sp,
                            ),
                            placeholder = { Text("使用工具栏快速插入格式，docx 按换行分段；xlsx 按换行分行；md/txt/html 原样写入") }
                        )
                    } else {
                        // —— 实时预览区（Markdown 渲染）——
                        Column(
                            Modifier.fillMaxWidth()
                                .heightIn(min = 160.dp, max = 320.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(cs.surface)
                                .border(1.dp, Line, RoundedCornerShape(10.dp))
                                .padding(12.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            if (docTitle.isNotBlank()) {
                                Text(
                                    docTitle,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = cs.onSurface,
                                    modifier = Modifier.padding(bottom = 8.dp),
                                )
                            }
                            if (docContent.isBlank()) {
                                Text("（暂无内容，请切换到编辑模式输入）", color = Muted, fontSize = 13.sp)
                            } else {
                                // 简易 Markdown 预览渲染
                                DocMarkdownPreview(docContent, scaled)
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    if (docResult != null) {
                        val ok = (docResult ?: "").startsWith("已生成")
                        Text(docResult ?: "", fontSize = scaled(12), color = if (ok) cs.primary else cs.error)
                        if (ok) {
                            // 兼容多种返回格式
                            val path = when {
                                (docResult ?: "").contains("已生成") -> {
                                    Regex("""已生成\s+\w+\s+文档：(.+?)（""").find(docResult ?: "")?.groupValues?.getOrNull(1)
                                }
                                (docResult ?: "").contains("文档：") -> {
                                    Regex("""文档：(.+?)（""").find(docResult ?: "")?.groupValues?.getOrNull(1)
                                }
                                else -> null
                            }
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
                    Text("后台生成真实文件（docx/xlsx/pptx/pdf 为二进制格式，md/txt/csv/html 为纯文本），可用应用内查看器或 WPS / Office 打开。", fontSize = scaled(11), color = Muted)
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
    
    if (showEditor) {
        Box(Modifier.fillMaxSize().background(cs.background)) {
            QuroDocEditorScreen(
                file = editorFile,
                onClose = {
                    showEditor = false
                    editorFile = null
                }
            )
        }
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
    onOpenWorkflow: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val tools = listOf(
        ToolItem(Icons.Filled.Folder, "文件管理", "浏览应用文件、查看文本/代码内容", onOpenFiles),
        ToolItem(Icons.Filled.Apps, "查看软件包名", "输入应用显示名，反查其精确包名", onOpenPackage),
        ToolItem(Icons.Filled.FolderOpen, "工作区", "在应用沙箱内创建/编辑文件与文件夹", onOpenWorkspace),
        ToolItem(Icons.Filled.Article, "文档生成", "生成 Word/Excel/PPT/PDF 等真实文档", onOpenDocGen),
        ToolItem(Icons.Filled.List, "已有工具", "查看已注册工具，可导入 AI 自写工具", onOpenToolsList),
        ToolItem(Icons.Filled.Description, "文档", "在应用内预览本地与生成文档（Word/Excel/PPT/PDF/文本）；文本可编辑，Office 文档可调起系统 WPS 打开", onOpenOnlyOffice),
        ToolItem(Icons.Filled.MusicNote, "音乐播放器", "在应用内播放本地音乐（后台持续播放）", onOpenMusic),
        ToolItem(Icons.Filled.Movie, "视频播放器", "在应用内全功能视频播放器播放本地视频", { onOpenVideo("", "") }),
        ToolItem(Icons.Filled.Keyboard, "AI 键盘", "AI 替你打字·注册为系统输入法·任意 App 可用", onClick = {
            // 打开系统输入法设置页，引导用户启用 Zorv AI 键盘
            try {
                ctx.startActivity(android.content.Intent("android.settings.INPUT_METHOD_SETTINGS"))
            } catch (_: Exception) {
                ctx.startActivity(android.content.Intent("android.settings.INPUT_METHOD_SUBTYPE_SETTINGS"))
            }
        }),
        ToolItem(Icons.Filled.AccountTree, "工作流", "创建和管理自动化工作流，支持定时触发、条件分支", onOpenWorkflow),
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

// ==================== 简易 Markdown 预览渲染 ====================

/**
 * 简易 Markdown 预览渲染器，用于文档生成器的实时预览。
 * 支持：标题(h1-h3)、加粗、斜体、代码块、行内代码、表格、列表、引用、分割线。
 * 纯 Compose Text 实现，不依赖外部库，适合对话框内轻量预览。
 */
@Composable
private fun DocMarkdownPreview(
    markdown: String,
    scaled: (Int) -> androidx.compose.ui.unit.TextUnit,
) {
    val cs = MaterialTheme.colorScheme
    val lines = markdown.split("\n")
    var inCodeBlock = false
    var codeBlockLines = mutableListOf<String>()
    var inTable = false
    var tableRows = mutableListOf<List<String>>()

    // 辅助：检测标题级别
    fun headingLevel(line: String): Int? {
        val m = Regex("^(#{1,3})\\s+(.+)").find(line.trim())
        return m?.groupValues?.get(1)?.length
    }

    // 辅助：检测列表
    fun listContent(line: String): String? {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("- [ ] ")) return "☐ ${trimmed.removePrefix("- [ ] ")}"
        if (trimmed.startsWith("- [x] ")) return "☑ ${trimmed.removePrefix("- [x] ")}"
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) return "• ${trimmed.drop(2)}"
        val numMatch = Regex("^(\\d+)\\.\\s+(.+)").find(trimmed)
        if (numMatch != null) return "${numMatch.groupValues[1]}. ${numMatch.groupValues[2]}"
        return null
    }

    // 辅助：检测引用
    fun quoteContent(line: String): String? {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("> ")) return trimmed.removePrefix("> ")
        return null
    }

    for (line in lines) {
        // 代码块处理
        if (line.trimStart().startsWith("```")) {
            if (inCodeBlock) {
                // 结束代码块
                Text(
                    codeBlockLines.joinToString("\n"),
                    fontSize = scaled(11),
                    fontFamily = FontFamily.Monospace,
                    color = cs.onSurface,
                    modifier = Modifier.fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(cs.surfaceVariant)
                        .padding(8.dp),
                )
                codeBlockLines = mutableListOf()
                inCodeBlock = false
            } else {
                inCodeBlock = true
            }
            continue
        }
        if (inCodeBlock) {
            codeBlockLines.add(line)
            continue
        }

        // 表格处理
        val tableCells = line.trim().let { l ->
            if (l.startsWith("|") && l.endsWith("|")) {
                l.split("|").drop(1).dropLast(1).map { it.trim() }
            } else null
        }
        if (tableCells != null && tableCells.isNotEmpty()) {
            // 跳过分隔行
            if (tableCells.all { it.matches(Regex("^[-:]+$")) }) continue
            inTable = true
            tableRows.add(tableCells)
            continue
        } else if (inTable) {
            // 表格结束，渲染
            if (tableRows.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, Line, RoundedCornerShape(6.dp)),
                ) {
                    tableRows.forEachIndexed { ri, cells ->
                        Row(
                            Modifier.fillMaxWidth()
                                .background(if (ri == 0) cs.primaryContainer else cs.surface)
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            cells.forEach { cell ->
                                Text(
                                    cell,
                                    fontSize = scaled(11),
                                    fontWeight = if (ri == 0) FontWeight.Bold else FontWeight.Normal,
                                    color = cs.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
            tableRows = mutableListOf()
            inTable = false
            // 当前非表格行，继续正常处理
        }

        val trimmed = line.trim()
        when {
            // 空行
            trimmed.isEmpty() -> Spacer(Modifier.height(4.dp))
            // 分割线
            trimmed == "---" || trimmed == "***" || trimmed == "___" -> {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 6.dp),
                    color = Line,
                )
            }
            // 标题
            headingLevel(trimmed) != null -> {
                val level = headingLevel(trimmed)!!
                val titleText = Regex("^#{1,3}\\s+(.+)").find(trimmed)!!.groupValues[1]
                Text(
                    titleText,
                    fontSize = when (level) { 1 -> scaled(20); 2 -> scaled(17); 3 -> scaled(15); else -> scaled(14) },
                    fontWeight = FontWeight.Bold,
                    color = cs.onSurface,
                    modifier = Modifier.padding(top = if (level == 1) 8.dp else 4.dp, bottom = 2.dp),
                )
            }
            // 引用
            quoteContent(trimmed) != null -> {
                Row(
                    Modifier.fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(cs.surfaceVariant.copy(alpha = 0.5f))
                        .padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                ) {
                    Text("│", color = cs.primary, fontSize = scaled(14), modifier = Modifier.padding(end = 6.dp))
                    Text(quoteContent(trimmed)!!, fontSize = scaled(12), color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                }
            }
            // 列表
            listContent(trimmed) != null -> {
                Text(
                    listContent(trimmed)!!,
                    fontSize = scaled(12),
                    color = cs.onSurface,
                    modifier = Modifier.padding(start = 8.dp, top = 1.dp, bottom = 1.dp),
                )
            }
            // 加粗标题（## **标题**）
            trimmed.startsWith("#") -> {
                Text(trimmed, fontSize = scaled(13), fontWeight = FontWeight.Bold, color = cs.onSurface, modifier = Modifier.padding(top = 4.dp))
            }
            // 普通段落（处理行内格式）
            else -> {
                // 简易行内 Markdown：**bold**, _italic_, `code`
                val annotatedText = buildAnnotatedString {
                    var remaining = trimmed
                    while (remaining.isNotEmpty()) {
                        val boldMatch = Regex("^\\*\\*(.+?)\\*\\*|^__(.+?)__").find(remaining)
                        val italicMatch = Regex("^_(.+?)_|^\\*(.+?)\\*").find(remaining)
                        val codeMatch = Regex("^`(.+?)`").find(remaining)
                        when {
                            boldMatch != null -> {
                                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                                append(boldMatch.groupValues[1].ifBlank { boldMatch.groupValues[2] })
                                pop()
                                remaining = remaining.removeRange(0, boldMatch.range.last + 1)
                            }
                            codeMatch != null -> {
                                pushStyle(SpanStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = scaled(12),
                                    background = cs.surfaceVariant,
                                ))
                                append(codeMatch.groupValues[1])
                                pop()
                                remaining = remaining.removeRange(0, codeMatch.range.last + 1)
                            }
                            italicMatch != null -> {
                                pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                                append(italicMatch.groupValues[1].ifBlank { italicMatch.groupValues[2] })
                                pop()
                                remaining = remaining.removeRange(0, italicMatch.range.last + 1)
                            }
                            else -> {
                                append(remaining)
                                remaining = ""
                            }
                        }
                    }
                }
                Text(
                    annotatedText,
                    fontSize = scaled(12),
                    color = cs.onSurface,
                    modifier = Modifier.padding(top = 1.dp, bottom = 1.dp),
                )
            }
        }
    }
    // 处理末尾未关闭的表格
    if (inTable && tableRows.isNotEmpty()) {
        Column(
            Modifier.fillMaxWidth()
                .padding(vertical = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, Line, RoundedCornerShape(6.dp)),
        ) {
            tableRows.forEachIndexed { ri, cells ->
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (ri == 0) cs.primaryContainer else cs.surface)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    cells.forEach { cell ->
                        Text(
                            cell,
                            fontSize = scaled(11),
                            fontWeight = if (ri == 0) FontWeight.Bold else FontWeight.Normal,
                            color = cs.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}
