package com.ai.assistance.quro.ui

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/**
 * 工作区代码屏：文件树浏览 + 语法高亮代码编辑。
 * 从对话控制条「工作区 → 代码浏览」进入，rootPath 为当前工作区（未选择时为默认工作区）。
 *
 * 功能：
 * - 文件树：目录展开/收起、点文件打开、长按重命名/删除、新建文件/文件夹、刷新
 * - 编辑器：等宽字体、行号栏、按扩展名的关键字/字符串/注释/数字语法高亮、保存
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun WorkspaceCodeScreen(rootPath: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val root = remember(rootPath) { File(rootPath).apply { mkdirs() } }

    // ── 文件树状态（展开集合 + 版本号，任何文件系统变更 bump version 触发重建可见列表）──
    var expandedDirs by remember(rootPath) { mutableStateOf(setOf<String>()) }
    var version by remember(rootPath) { mutableStateOf(0) }
    val entries = remember(rootPath, expandedDirs, version) { buildVisibleEntries(root, expandedDirs) }

    // ── 编辑器状态 ──
    var editorFile by remember { mutableStateOf<File?>(null) }
    var editorText by remember { mutableStateOf("") }
    var dirty by remember { mutableStateOf(false) }
    var showTree by remember { mutableStateOf(true) }

    // ── 对话框状态 ──
    var newFileTarget by remember { mutableStateOf<File?>(null) }
    var newDirTarget by remember { mutableStateOf<File?>(null) }
    var renameTarget by remember { mutableStateOf<File?>(null) }
    var actionTarget by remember { mutableStateOf<File?>(null) } // 长按弹出的操作单
    var deleteTarget by remember { mutableStateOf<File?>(null) }

    val lang = editorFile?.let { languageOf(it) } ?: "plain"
    val highlightTransformation = remember(lang) { CodeHighlightTransformation(lang) }

    fun refresh() { version++ }

    fun openFile(f: File) {
        if (!isEditableText(f)) {
            Toast.makeText(ctx, "不支持的文件类型或文件过大（>1MB）", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            editorFile = f
            editorText = f.readText()
            dirty = false
        } catch (e: Exception) {
            Toast.makeText(ctx, "读取失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun saveFile() {
        val f = editorFile ?: return
        try {
            f.writeText(editorText)
            dirty = false
            Toast.makeText(ctx, "已保存 ${f.name}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(ctx, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // 新建文件/文件夹的落点：选中目录 > 选中文件父目录 > 工作区根
    fun targetDir(): File {
        val f = editorFile ?: return root
        return if (f.isDirectory) f else (f.parentFile ?: root)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            (editorFile?.let { it.name } ?: "代码浏览") + if (dirty) " •" else "",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                        )
                        Text(
                            editorFile?.let { it.parent?.removePrefix(root.parent ?: "") } ?: root.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showTree = !showTree }) {
                        Icon(Icons.Filled.List, contentDescription = "文件树")
                    }
                    IconButton(onClick = { refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                    IconButton(onClick = { newFileTarget = targetDir() }) {
                        Icon(Icons.Filled.NoteAdd, contentDescription = "新建文件")
                    }
                    IconButton(onClick = { newDirTarget = targetDir() }) {
                        Icon(Icons.Filled.CreateNewFolder, contentDescription = "新建文件夹")
                    }
                    IconButton(
                        onClick = { saveFile() },
                        enabled = editorFile != null,
                    ) {
                        Icon(
                            Icons.Filled.Save,
                            contentDescription = "保存",
                            tint = if (dirty) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
            )
        },
    ) { padding ->
        Row(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // ═══ 左侧文件树 ═══
            if (showTree) {
                Column(
                    Modifier
                        .width(260.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                ) {
                    Text(
                        root.name,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    if (entries.isEmpty()) {
                        Text(
                            "空工作区\n右上角新建文件或文件夹",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    } else {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(vertical = 4.dp)
                        ) {
                            entries.forEach { entry ->
                                TreeRow(
                                    entry = entry,
                                    selected = editorFile?.absolutePath == entry.file.absolutePath,
                                    onClick = {
                                        if (entry.isDir) {
                                            expandedDirs = if (entry.file.absolutePath in expandedDirs)
                                                expandedDirs - entry.file.absolutePath
                                            else expandedDirs + entry.file.absolutePath
                                        } else {
                                            openFile(entry.file)
                                        }
                                    },
                                    onLongClick = { actionTarget = entry.file },
                                )
                            }
                        }
                    }
                }
            }

            // ═══ 右侧编辑区 ═══
            val f = editorFile
            if (f == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "从左侧选择文件开始编辑\n或点击右上角「+」新建",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                CodeEditorArea(
                    text = editorText,
                    onTextChange = { editorText = it; dirty = true },
                    transformation = highlightTransformation,
                    enabled = isEditableText(f),
                )
            }
        }
    }

    // ═══ 对话框们 ═══

    // 新建文件
    if (newFileTarget != null) {
        NameInputDialog(
            title = "新建文件",
            label = "文件名（如 Main.kt / app.py / index.html）",
            initial = "",
            onDismiss = { newFileTarget = null },
            onConfirm = { name ->
                val dir = newFileTarget!!
                val file = File(dir, name)
                try {
                    file.writeText("")
                    refresh()
                    openFile(file)
                    Toast.makeText(ctx, "已创建 $name", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(ctx, "创建失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                newFileTarget = null
            },
        )
    }

    // 新建文件夹
    if (newDirTarget != null) {
        NameInputDialog(
            title = "新建文件夹",
            label = "文件夹名称",
            initial = "",
            onDismiss = { newDirTarget = null },
            onConfirm = { name ->
                try {
                    File(newDirTarget!!, name).mkdirs()
                    refresh()
                    Toast.makeText(ctx, "已创建 $name", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(ctx, "创建失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                newDirTarget = null
            },
        )
    }

    // 长按操作单：重命名 / 删除
    if (actionTarget != null) {
        val target = actionTarget!!
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            title = { Text(target.name) },
            text = { Text(if (target.isDirectory) "文件夹操作" else "文件操作") },
            confirmButton = {
                TextButton(onClick = { renameTarget = target; actionTarget = null }) {
                    Text("重命名", color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = target; actionTarget = null }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }

    // 重命名
    if (renameTarget != null) {
        val target = renameTarget!!
        NameInputDialog(
            title = "重命名",
            label = "新名称",
            initial = target.name,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                val renamed = File(target.parentFile, name)
                val ok = target.renameTo(renamed)
                if (ok) {
                    if (editorFile?.absolutePath == target.absolutePath) editorFile = renamed
                    refresh()
                    Toast.makeText(ctx, "已重命名为 $name", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(ctx, "重命名失败", Toast.LENGTH_SHORT).show()
                }
                renameTarget = null
            },
        )
    }

    // 删除确认
    if (deleteTarget != null) {
        val target = deleteTarget!!
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除${if (target.isDirectory) "文件夹" else "文件"}") },
            text = { Text("确定删除「${target.name}」吗？${if (target.isDirectory) "其内所有内容将一并删除。" else ""}此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    val ok = if (target.isDirectory) target.deleteRecursively() else target.delete()
                    if (ok) {
                        if (editorFile != null && editorFile!!.absolutePath.startsWith(target.absolutePath)) {
                            editorFile = null
                            editorText = ""
                            dirty = false
                        }
                        refresh()
                        Toast.makeText(ctx, "已删除 ${target.name}", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(ctx, "删除失败", Toast.LENGTH_SHORT).show()
                    }
                    deleteTarget = null
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}

// ═══════════════════ 文件树 ═══════════════════

/** 可见树条目（已按展开集合展平）。 */
private data class TreeEntry(
    val file: File,
    val depth: Int,
    val isDir: Boolean,
    val expanded: Boolean,
)

private fun buildVisibleEntries(root: File, expanded: Set<String>): List<TreeEntry> {
    val out = mutableListOf<TreeEntry>()
    fun walk(dir: File, depth: Int) {
        val children = dir.listFiles()
            ?.filter { !it.name.startsWith(".") } // 隐藏 .git/.idea 等噪音目录
            ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            ?: return
        for (c in children) {
            val isOpen = c.absolutePath in expanded
            out.add(TreeEntry(c, depth, c.isDirectory, isOpen))
            if (c.isDirectory && isOpen) walk(c, depth + 1)
        }
    }
    walk(root, 0)
    return out
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TreeRow(
    entry: TreeEntry,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) cs.primaryContainer.copy(alpha = 0.4f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = (8 + entry.depth * 14).dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (entry.isDir) {
            Icon(
                Icons.Filled.Folder, null,
                tint = Color(0xFFD99A2B),
                modifier = Modifier.size(16.dp),
            )
        } else {
            Icon(
                fileIcon(entry.file),
                null,
                tint = fileTint(entry.file),
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            entry.file.name,
            fontSize = 12.sp,
            color = if (selected) cs.primary else cs.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (entry.isDir) {
            Spacer(Modifier.width(4.dp))
            Text(
                if (entry.expanded) "▾" else "▸",
                fontSize = 10.sp,
                color = cs.onSurfaceVariant,
            )
        }
    }
}

private fun fileIcon(f: File) = when (f.extension.lowercase()) {
    "md", "txt", "log" -> Icons.Filled.Description
    "kt", "kts", "java", "py", "js", "ts", "jsx", "tsx",
    "c", "h", "cpp", "hpp", "cc", "go", "rs", "rb", "php",
    "sh", "html", "htm", "css", "xml", "json", "yml", "yaml", "toml", "gradle" -> Icons.Filled.Description
    else -> Icons.Filled.InsertDriveFile
}

private fun fileTint(f: File): Color = when (f.extension.lowercase()) {
    "kt", "kts" -> Color(0xFF8E44AD)
    "java" -> Color(0xFFE76F00)
    "py" -> Color(0xFF2B7A9E)
    "js", "jsx" -> Color(0xFFC9A227)
    "ts", "tsx" -> Color(0xFF2D6FBA)
    "html", "htm", "xml" -> Color(0xFFD45B3E)
    "css", "scss" -> Color(0xFF3E7BD4)
    "json" -> Color(0xFF5B8C5A)
    "md" -> Color(0xFF4A90A4)
    "sh", "bash" -> Color(0xFF5A6B48)
    else -> Color(0xFF8B95A5)
}

// ═══════════════════ 编辑区（行号 + 语法高亮） ═══════════════════

@Composable
private fun CodeEditorArea(
    text: String,
    onTextChange: (String) -> Unit,
    transformation: VisualTransformation,
    enabled: Boolean,
) {
    val cs = MaterialTheme.colorScheme
    val editorStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        color = cs.onSurface,
    )
    val scroll = rememberScrollState()
    val lineCount = text.count { it == '\n' } + 1
    // 行号列宽度按位数自适应
    val gutterWidth = ((lineCount.toString().length + 2) * 9).dp

    Row(Modifier.fillMaxSize()) {
        // 行号栏（与编辑器共享同一个滚动状态，超长文件跳过行号避免卡顿）
        if (lineCount <= 10_000) {
            Column(
                Modifier
                    .width(gutterWidth)
                    .fillMaxHeight()
                    .verticalScroll(scroll)
                    .background(cs.surfaceVariant.copy(alpha = 0.3f))
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.End,
            ) {
                Text(
                    (1..lineCount).joinToString("\n"),
                    style = editorStyle.copy(
                        color = cs.onSurfaceVariant.copy(alpha = 0.6f),
                        fontSize = 11.sp,
                    ),
                    textAlign = TextAlign.End,
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
        }
        BasicTextField(
            value = text,
            onValueChange = { if (enabled) onTextChange(it) },
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(scroll)
                .padding(horizontal = 10.dp, vertical = 10.dp),
            textStyle = editorStyle,
            visualTransformation = transformation,
            cursorBrush = SolidColor(cs.primary),
            readOnly = !enabled,
        )
    }
}

/** 名称输入对话框（新建文件/文件夹、重命名共用）。 */
@Composable
private fun NameInputDialog(
    title: String,
    label: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = "" },
                    label = { Text(label) },
                    singleLine = true,
                    isError = error.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error.isNotEmpty()) {
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val n = name.trim()
                when {
                    n.isBlank() -> error = "名称不能为空"
                    n.contains('/') || n.contains('\\') -> error = "名称不能包含路径分隔符"
                    else -> onConfirm(n)
                }
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ═══════════════════ 语法高亮 ═══════════════════

private class CodeHighlightTransformation(private val lang: String) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(highlightCodeAnnotated(text.text, lang), OffsetMapping.Identity)
}

private object CodePalette {
    val keyword = Color(0xFF8E24AA)
    val string = Color(0xFF0B7A3E)
    val number = Color(0xFF0B62C4)
    val comment = Color(0xFF8A8F98)
    val annotation = Color(0xFF9E6A03)
}

private val KEYWORDS: Map<String, Set<String>> = mapOf(
    "kotlin" to setOf(
        "package", "import", "class", "object", "interface", "fun", "val", "var", "when",
        "if", "else", "for", "while", "return", "is", "in", "as", "by", "private", "internal",
        "public", "protected", "override", "open", "abstract", "sealed", "data", "companion",
        "lateinit", "suspend", "try", "catch", "finally", "throw", "this", "super", "null",
        "true", "false", "const", "enum", "typealias", "operator", "inline", "vararg", "out",
        "reified", "do", "break", "continue", "where", "init", "constructor", "get", "set", "it",
    ),
    "java" to setOf(
        "package", "import", "class", "interface", "enum", "extends", "implements", "public",
        "private", "protected", "static", "final", "abstract", "synchronized", "volatile",
        "transient", "native", "void", "new", "return", "if", "else", "for", "while", "do",
        "switch", "case", "default", "break", "continue", "try", "catch", "finally", "throw",
        "throws", "this", "super", "null", "true", "false", "instanceof", "var", "record", "sealed",
    ),
    "python" to setOf(
        "def", "class", "import", "from", "as", "return", "if", "elif", "else", "for", "while",
        "try", "except", "finally", "raise", "with", "as", "pass", "break", "continue", "lambda",
        "global", "nonlocal", "yield", "assert", "del", "in", "is", "not", "and", "or", "None",
        "True", "False", "self", "async", "await", "match", "case",
    ),
    "javascript" to setOf(
        "function", "return", "if", "else", "for", "while", "do", "switch", "case", "default",
        "break", "continue", "try", "catch", "finally", "throw", "new", "delete", "typeof",
        "instanceof", "in", "of", "var", "let", "const", "class", "extends", "super", "this",
        "null", "undefined", "true", "false", "async", "await", "yield", "import", "export",
        "from", "static", "get", "set",
    ),
    "json" to setOf("true", "false", "null"),
    "c" to setOf(
        "auto", "break", "case", "char", "const", "continue", "default", "do", "double",
        "else", "enum", "extern", "float", "for", "goto", "if", "int", "long", "register",
        "return", "short", "signed", "sizeof", "static", "struct", "switch", "typedef",
        "union", "unsigned", "void", "volatile", "while", "include", "define", "NULL",
    ),
    "go" to setOf(
        "break", "case", "chan", "const", "continue", "default", "defer", "else",
        "fallthrough", "for", "func", "go", "goto", "if", "import", "interface", "map",
        "package", "range", "return", "select", "struct", "switch", "type", "var", "nil",
        "true", "false", "make", "new",
    ),
    "rust" to setOf(
        "fn", "let", "mut", "const", "if", "else", "match", "for", "while", "loop", "return",
        "break", "continue", "struct", "enum", "trait", "impl", "pub", "use", "mod", "crate",
        "self", "Self", "super", "where", "as", "in", "ref", "move", "async", "await", "dyn",
        "unsafe", "type", "true", "false", "Some", "None", "Ok", "Err",
    ),
    "shell" to setOf(
        "if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case", "esac",
        "function", "return", "exit", "local", "export", "readonly", "shift", "source",
        "echo", "cd", "set", "unset", "trap",
    ),
)

/** 行注释前缀：按语言区分（markup 无行注释）。 */
private fun lineCommentPrefix(lang: String): String? = when (lang) {
    "python", "shell", "ruby" -> "#"
    "kotlin", "java", "javascript", "c", "go", "rust", "css", "typescript" -> "//"
    else -> null
}

/** 判断扩展名是否为可编辑文本。 */
private val TEXT_EXTS = setOf(
    "kt", "kts", "java", "py", "js", "ts", "jsx", "tsx", "mjs", "json", "xml", "html", "htm",
    "svg", "css", "scss", "md", "txt", "sh", "bash", "zsh", "c", "h", "cpp", "hpp", "cc",
    "go", "rs", "rb", "php", "yml", "yaml", "toml", "ini", "properties", "gradle", "sql",
    "swift", "dart", "vue", "csv", "log", "conf", "cfg", "cmake", "mk", "pro", "gitignore", "env",
)

private const val MAX_EDIT_BYTES = 1_000_000L

private fun isEditableText(file: File): Boolean {
    if (!file.isFile) return false
    if (file.length() > MAX_EDIT_BYTES) return false
    val ext = file.extension.lowercase()
    if (ext in TEXT_EXTS) return true
    if (ext.isNotEmpty()) return false
    // 无扩展名：嗅探前 2KB 是否含 NUL 字节（二进制标志）
    return try {
        val head = ByteArray(minOf(2048L, file.length()).toInt().coerceAtLeast(1))
        java.io.FileInputStream(file).use { ins -> ins.read(head) }
        !head.contains(0.toByte())
    } catch (e: Exception) {
        false
    }
}

private fun languageOf(file: File): String = when (file.extension.lowercase()) {
    "kt", "kts" -> "kotlin"
    "java" -> "java"
    "py" -> "python"
    "js", "jsx", "mjs" -> "javascript"
    "ts", "tsx" -> "javascript"
    "json" -> "json"
    "xml", "html", "htm", "svg" -> "markup"
    "css", "scss" -> "css"
    "md" -> "markdown"
    "sh", "bash", "zsh" -> "shell"
    "c", "h" -> "c"
    "cpp", "hpp", "cc" -> "c"
    "go" -> "go"
    "rs" -> "rust"
    "rb" -> "ruby"
    else -> "plain"
}

/**
 * 轻量语法高亮：按优先级（注释 > 字符串 > 注解 > 数字/关键字）扫描，
 * 低优先级 token 与已占用区间重叠时跳过。纯函数、1:1 偏移，可安全用作 VisualTransformation。
 */
fun highlightCodeAnnotated(code: String, lang: String): AnnotatedString {
    if (lang == "plain" || lang == "markdown") return AnnotatedString(code)
    val builder = AnnotatedString.Builder(code)
    val occupied = ArrayList<IntRange>()

    fun tryStyle(style: SpanStyle, start: Int, end: Int): Boolean {
        if (start < 0 || end > code.length || start >= end) return false
        val r = start until end
        for (o in occupied) if (o.last >= r.first && o.first <= r.last) return false
        occupied.add(r)
        builder.addStyle(style, start, end)
        return true
    }

    // 1. 块注释 /* */ 与 <!-- -->
    for (m in Regex("""/\*[\s\S]*?\*/""").findAll(code)) {
        tryStyle(SpanStyle(color = CodePalette.comment), m.range.first, m.range.last + 1)
    }
    for (m in Regex("""<!--[\s\S]*?-->""").findAll(code)) {
        tryStyle(SpanStyle(color = CodePalette.comment), m.range.first, m.range.last + 1)
    }

    // 2. 行注释
    lineCommentPrefix(lang)?.let { prefix ->
        for (m in Regex(Regex.escape(prefix) + """[^\n]*""").findAll(code)) {
            // 行注释必须位于行首（允许缩进），避免 URL 中的 // 被误判
            val lineStart = code.lastIndexOf('\n', m.range.first.coerceAtMost(code.length - 1)) + 1
            val before = code.substring(lineStart, m.range.first)
            if (before.isBlank()) {
                tryStyle(SpanStyle(color = CodePalette.comment), m.range.first, m.range.last + 1)
            }
        }
    }

    // 3. 字符串（单/双/反引号，支持反斜杠转义，不跨行）
    val stringPatterns = listOf(
        """"(?:\\.|[^"\\\n])*"""",
        """'(?:\\.|[^'\\\n])*'""",
        """`(?:\\.|[^`\\\n])*`""",
    )
    for (re in stringPatterns) {
        for (m in Regex(re).findAll(code)) {
            tryStyle(SpanStyle(color = CodePalette.string), m.range.first, m.range.last + 1)
        }
    }

    // 4. 注解 @Xxx（kotlin/java）
    if (lang == "kotlin" || lang == "java") {
        for (m in Regex("""@[\w.]+""").findAll(code)) {
            tryStyle(SpanStyle(color = CodePalette.annotation, fontWeight = FontWeight.SemiBold), m.range.first, m.range.last + 1)
        }
    }

    // 5. 数字
    for (m in Regex("""\b\d[\d_]*(?:\.\d+)?[fFlLdDxXbB]*\b""").findAll(code)) {
        tryStyle(SpanStyle(color = CodePalette.number), m.range.first, m.range.last + 1)
    }

    // 6. 关键字
    KEYWORDS[lang]?.let { kws ->
        val pattern = kws.joinToString("|") { Regex.escape(it) }
        for (m in Regex("""\b(?:$pattern)\b""").findAll(code)) {
            tryStyle(SpanStyle(color = CodePalette.keyword, fontWeight = FontWeight.SemiBold), m.range.first, m.range.last + 1)
        }
    }

    return builder.toAnnotatedString()
}
