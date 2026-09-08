package com.ai.assistance.quro.build

import android.content.ClipboardManager
import android.content.ClipData
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

/**
 * Zorv 构建台屏幕（从 build-aci 集成，已剥离 ACI 受控端）。
 *
 * 入口：由 QuroAI 工具中心「构建台」卡片经 ChatScreen 全屏覆盖层拉起。
 * 内含：文件树 / Java 编辑器 / 编译日志 / 工具链自检 / 工程设置（包名·图标·签名）。
 * 真正的构建引擎见 [BuildEngine]，全部在 App 进程内执行（ecj→class、d8→dex、apksig 签名），
 * 不依赖 aapt2、不拉子进程，Android 16 亦可用。
 */
@Composable
fun BuildScreen(onClose: () -> Unit) {
    val vm: ProjectViewModel = viewModel()
    BuildApp(vm, onClose)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuildApp(vm: ProjectViewModel = viewModel(), onClose: (() -> Unit)? = null) {
    val context = LocalContext.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showNewDialog by remember { mutableStateOf<NewDialogState?>(null) }
    var showRenameDialog by remember { mutableStateOf<ProjectFile?>(null) }
    var fileToDelete by remember { mutableStateOf<ProjectFile?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) vm.exportDex(context, uri)
    }

    val exportApkLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")
    ) { uri ->
        if (uri != null) vm.exportApk(context, uri)
    }

    var showTools by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    var pendingAlias by rememberSaveable { mutableStateOf("") }
    var pendingStorePass by rememberSaveable { mutableStateOf("") }
    var pendingKeyPass by rememberSaveable { mutableStateOf("") }

    val iconPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importIcon(context, uri)
    }
    val keystorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importKeystore(context, uri, pendingAlias, pendingStorePass, pendingKeyPass)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importFile(context, uri)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            ModalDrawerSheet {
                FileTreeDrawer(
                    files = vm.files,
                    selected = vm.selected,
                    onSelect = { vm.select(it) },
                    onCreateFile = { parent -> showNewDialog = NewDialogState(parent, true) },
                    onCreateFolder = { parent -> showNewDialog = NewDialogState(parent, false) },
                    onRename = { showRenameDialog = it },
                    onDelete = { fileToDelete = it }
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Zorv 构建台", fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        Row {
                            if (onClose != null) {
                                IconButton(onClick = onClose) {
                                    Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                                }
                            }
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "文件树")
                            }
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { vm.buildProject() },
                            enabled = !vm.isBuilding
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "编译工程")
                        }
                        IconButton(
                            onClick = { vm.buildApk() },
                            enabled = !vm.isBuilding && vm.dexPath != null
                        ) {
                            Icon(Icons.Default.Android, contentDescription = "构建 APK")
                        }
                        var menuOpen by remember { mutableStateOf(false) }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("工程设置") },
                                enabled = !vm.isBuilding,
                                onClick = { menuOpen = false; showSettings = true }
                            )
                            DropdownMenuItem(
                                text = { Text("工具链") },
                                enabled = !vm.isBuilding,
                                onClick = { menuOpen = false; showTools = true }
                            )
                            DropdownMenuItem(
                                text = { Text("导出产物") },
                                enabled = vm.dexPath != null,
                                onClick = {
                                    menuOpen = false
                                    if (vm.apkPath != null) exportApkLauncher.launch("app-release.apk")
                                    else if (vm.dexPath != null) exportLauncher.launch("classes.dex")
                                }
                            )
                        }
                    }
                )
            },
            floatingActionButton = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallFloatingActionButton(
                        onClick = { showNewDialog = NewDialogState("", true) }
                    ) { Icon(Icons.Default.Add, contentDescription = "新建文件") }
                    SmallFloatingActionButton(
                        onClick = { showNewDialog = NewDialogState("", false) }
                    ) { Icon(Icons.Default.CreateNewFolder, contentDescription = "新建目录") }
                }
            }
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 12.dp)
            ) {
                // 副标题（从 TopAppBar 移出，避免标题被挤竖排）
                Text(
                    "Java 工程 → DEX → APK",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)
                )
                // 编辑器
                val fileName = vm.selected?.name ?: "未选择文件"
                Text(
                    text = fileName,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
                OutlinedTextField(
                    value = vm.editorText,
                    onValueChange = { vm.onEditorTextChanged(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    placeholder = { Text("在左侧文件树选择或新建文件开始编写代码…") }
                )
                Spacer(Modifier.height(8.dp))
                // 日志
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "编译日志",
                        fontSize = 14.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            val cm = context.getSystemService(ClipboardManager::class.java)
                            cm.setPrimaryClip(ClipData.newPlainText("build_log", vm.log))
                            Toast.makeText(context, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "复制日志")
                    }
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 220.dp)
                ) {
                    Text(
                        vm.log,
                        Modifier
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState()),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 14.sp
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    showNewDialog?.let { state ->
        NewItemDialog(
            isFile = state.isFile,
            parentRel = state.parentRel,
            onConfirm = { name ->
                if (state.isFile) vm.createFile(state.parentRel, name)
                else vm.createFolder(state.parentRel, name)
                showNewDialog = null
            },
            onDismiss = { showNewDialog = null }
        )
    }

    showRenameDialog?.let { file ->
        RenameDialog(
            currentName = file.name,
            onConfirm = { newName ->
                vm.rename(file, newName)
                showRenameDialog = null
            },
            onDismiss = { showRenameDialog = null }
        )
    }

    fileToDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { fileToDelete = null },
            title = { Text("确认删除") },
            text = { Text("确定删除「${file.name}" + (if (file.isDirectory) "」及其全部内容？" else "」？")) },
            confirmButton = {
                TextButton(onClick = { vm.delete(file); fileToDelete = null }) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { fileToDelete = null }) { Text("取消") }
            }
        )
    }

    if (showTools) {
        ToolStatusDialog(
            tools = vm.toolStatus,
            onRefresh = { vm.refreshToolStatus() },
            onDismiss = { showTools = false }
        )
    }

    if (showSettings) {
        Dialog(onDismissRequest = { showSettings = false }) {
            Surface(
                Modifier.fillMaxSize().padding(16.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface
            ) {
                ProjectSettingsSheet(
                    vm = vm,
                    onPickIcon = { iconPicker.launch(arrayOf("image/*")) },
                    onPickKeystore = { a, s, k ->
                        pendingAlias = a; pendingStorePass = s; pendingKeyPass = k
                        keystorePicker.launch(arrayOf("*/*"))
                    },
                    onPickFile = { filePicker.launch(arrayOf("*/*")) },
                    onGenerateKeystore = { a, s, k -> vm.generateKeystore(a, s, k) },
                    onDismiss = { showSettings = false }
                )
            }
        }
    }
}

private data class NewDialogState(val parentRel: String, val isFile: Boolean)

@Composable
private fun FileTreeDrawer(
    files: List<ProjectFile>,
    selected: ProjectFile?,
    onSelect: (ProjectFile) -> Unit,
    onCreateFile: (String) -> Unit,
    onCreateFolder: (String) -> Unit,
    onRename: (ProjectFile) -> Unit,
    onDelete: (ProjectFile) -> Unit
) {
    Column(Modifier.fillMaxHeight().widthIn(min = 240.dp, max = 320.dp)) {
        Text(
            "工程文件",
            fontSize = 18.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            modifier = Modifier.padding(16.dp)
        )
        HorizontalDivider()
        Box(Modifier.weight(1f)) {
            if (files.isEmpty()) {
                Text(
                    "暂无文件，点击 + 新建",
                    Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val scroll = rememberScrollState()
                Column(Modifier.verticalScroll(scroll)) {
                    files.forEach { file ->
                        FileTreeItem(
                            file = file,
                            selected = selected?.relativePath == file.relativePath,
                            onClick = { if (!file.isDirectory) onSelect(file) },
                            onLongClick = {
                                if (file.isDirectory) {
                                    onCreateFile(file.relativePath)
                                } else {
                                    onRename(file)
                                }
                            },
                            onAddFile = { onCreateFile(file.relativePath) },
                            onAddFolder = { onCreateFolder(file.relativePath) },
                            onRename = { onRename(file) },
                            onDelete = { onDelete(file) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FileTreeItem(
    file: ProjectFile,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onAddFile: () -> Unit,
    onAddFolder: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(true) }
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    Surface(
        color = bg,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (file.depth * 16).dp)
            .heightIn(min = 40.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            if (file.isDirectory) {
                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                        contentDescription = null
                    )
                }
            } else {
                Spacer(Modifier.width(24.dp))
            }
            Icon(
                imageVector = if (file.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                file.name,
                fontSize = 13.sp,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 10.dp)
                    .noRippleClickable { if (file.isDirectory) expanded = !expanded else onClick() }
            )
            if (file.isDirectory) {
                Row {
                    IconButton(onClick = onAddFile, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Add, contentDescription = "新建文件", modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = onAddFolder, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = "新建目录", modifier = Modifier.size(18.dp))
                    }
                }
            } else {
                IconButton(onClick = onRename, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = "重命名", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "删除", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun Modifier.noRippleClickable(onClick: () -> Unit): Modifier = this.then(
    clickable(
        indication = null,
        interactionSource = remember { MutableInteractionSource() }
    ) { onClick() }
)

@Composable
private fun NewItemDialog(
    isFile: Boolean,
    parentRel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    val title = if (isFile) "新建文件" else "新建目录"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (parentRel.isNotEmpty()) {
                    Text("位置：$parentRel", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(if (isFile) "文件名（如 Main.java）" else "目录名") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name) }, enabled = name.isNotBlank()) {
                Text("确定")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun RenameDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("新名称") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name) }, enabled = name.isNotBlank()) {
                Text("确定")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ToolStatusDialog(
    tools: List<BuildEngine.ToolStatus>,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("工具链状态") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (tools.isEmpty()) {
                    Text("尚未检测，点击刷新。")
                } else {
                    tools.forEach { t ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (t.present) Icons.Default.CheckCircle else Icons.Default.Error,
                                contentDescription = null,
                                tint = if (t.present) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(t.name, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Text(t.note, fontSize = 11.sp, lineHeight = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (t.present) {
                                    Text(t.path, fontSize = 10.sp, lineHeight = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "提示：base.apk 已含 AndroidManifest + resources.arsc + 默认图标。构建 APK 时按工程设置改写包名/应用名/图标、注入用户 classes.dex 并签名，即可在设备独立安装（不同包名互不冲突）。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRefresh) { Text("刷新") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text, fontSize = 15.sp, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    isPassword: Boolean = false,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None
    )
}

@Composable
private fun ProjectSettingsSheet(
    vm: ProjectViewModel,
    onPickIcon: () -> Unit,
    onPickKeystore: (alias: String, storePass: String, keyPass: String) -> Unit,
    onPickFile: () -> Unit,
    onGenerateKeystore: (alias: String, storePass: String, keyPass: String) -> Unit,
    onDismiss: () -> Unit
) {
    val cfg = vm.projectConfig
    val scroll = rememberScrollState()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("工程设置", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "关闭") }
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()

        SectionTitle("基础信息")
        LabeledField("包名（applicationId）", cfg.packageName) { vm.setPackageName(it) }
        LabeledField("应用名（桌面显示）", cfg.appLabel) { vm.setAppLabel(it) }
        LabeledField("版本名", cfg.versionName) { vm.setVersionName(it) }
        Text(
            "每个工程用不同包名，安装时才不会互相冲突（覆盖安装还需同签名）。",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()

        SectionTitle("APK 图标")
        Row(verticalAlignment = Alignment.CenterVertically) {
            val iconBytes = vm.loadIconBytes()
            if (iconBytes != null) {
                val bmp = remember(iconBytes) {
                    android.graphics.BitmapFactory.decodeByteArray(iconBytes, 0, iconBytes.size)?.asImageBitmap()
                }
                if (bmp != null) Image(bmp, contentDescription = null, Modifier.size(56.dp))
                else Icon(Icons.Default.Android, contentDescription = null, Modifier.size(56.dp))
            } else {
                Icon(Icons.Default.Android, contentDescription = null, Modifier.size(56.dp))
            }
            Spacer(Modifier.width(12.dp))
            Button(onClick = onPickIcon) {
                Icon(Icons.Default.Image, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("导入图标 PNG")
            }
        }
        Text(
            "默认使用内置占位图标；导入后该 PNG 会替换 APK 桌面图标。",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()

        SectionTitle("签名")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("使用自定义签名", Modifier.weight(1f))
            Switch(checked = cfg.signing.useCustom, onCheckedChange = { vm.setUseCustomSigning(it) })
        }
        if (cfg.signing.useCustom) {
            LabeledField("别名 alias", cfg.signing.alias) { vm.setSigningAlias(it) }
            LabeledField("keystore 密码", cfg.signing.storePassword, isPassword = true) { vm.setStorePassword(it) }
            LabeledField("密钥密码", cfg.signing.keyPassword, isPassword = true) { vm.setKeyPassword(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onGenerateKeystore(cfg.signing.alias, cfg.signing.storePassword, cfg.signing.keyPassword) }) {
                    Icon(Icons.Default.VpnKey, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("生成新签名")
                }
                Button(onClick = { onPickKeystore(cfg.signing.alias, cfg.signing.storePassword, cfg.signing.keyPassword) }) {
                    Icon(Icons.Default.Upload, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("导入 keystore")
                }
            }
            if (!cfg.signing.keystorePath.isNullOrBlank()) {
                Text("当前 keystore：${cfg.signing.keystorePath}", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
            } else {
                Text("尚未选择 keystore（生成或导入后生效）。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "生成依赖设备 BouncyCastle，部分机型可能不可用；若失败请用「导入 keystore」（用 keytool 在电脑生成后导入）。",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                "当前使用内置 debug.keystore（android/android/androiddebugkey）。",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()

        SectionTitle("导入文件到工程")
        Button(onClick = onPickFile) {
            Icon(Icons.Default.FileOpen, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("导入文件")
        }
        Text(
            "· .java / .kt → 按源码 package 落位到 src/，参与编译；\n" +
                "· 其它文件 → 放入 assets/，打包进 APK 的 assets/（代码经 AssetManager 读取）。",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
