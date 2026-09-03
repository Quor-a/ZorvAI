package com.ai.assistance.quro.terminal.ui

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.terminal.TerminalManager
import com.ai.assistance.quro.terminal.data.PackageManagerType
import com.ai.assistance.quro.terminal.utils.SourceManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import android.util.Log
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.collect

enum class InstallStatus {
    CHECKING,
    INSTALLED,
    NOT_INSTALLED
}

data class PackageItem(
    val id: String,
    val name: String,
    val command: String,
    val description: String = ""
)

data class PackageCategory(
    val id: String,
    val name: String,
    val description: String,
    val packages: List<PackageItem>
)

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    onBack: () -> Unit,
    onSetup: (List<String>) -> Unit
) {
    val context = LocalContext.current
    val sourceManager = remember { SourceManager(context) }

    val packageCategories by remember {
        derivedStateOf {
            listOf(
                PackageCategory(
                    id = "nodejs",
                    name = context.getString(com.ai.assistance.quro.terminal.R.string.category_nodejs_name),
                    description = context.getString(com.ai.assistance.quro.terminal.R.string.category_nodejs_desc),
                    packages = listOf(
                        PackageItem("nodejs", context.getString(com.ai.assistance.quro.terminal.R.string.package_nodejs_name), "curl -fsSL https://deb.nodesource.com/setup_24.x | bash - && apt install -y nodejs", context.getString(com.ai.assistance.quro.terminal.R.string.package_nodejs_desc)),
                        PackageItem("pnpm", context.getString(com.ai.assistance.quro.terminal.R.string.package_pnpm_name), "typescript", context.getString(com.ai.assistance.quro.terminal.R.string.package_pnpm_desc))
                    )
                ),
                PackageCategory(
                    id = "python",
                    name = context.getString(com.ai.assistance.quro.terminal.R.string.category_python_name),
                    description = context.getString(com.ai.assistance.quro.terminal.R.string.category_python_desc),
                    packages = listOf(
                        PackageItem("python-is-python3", context.getString(com.ai.assistance.quro.terminal.R.string.package_python_link_name), "python-is-python3", context.getString(com.ai.assistance.quro.terminal.R.string.package_python_link_desc)),
                        PackageItem("python3-venv", context.getString(com.ai.assistance.quro.terminal.R.string.package_python_venv_name), "python3-venv", context.getString(com.ai.assistance.quro.terminal.R.string.package_python_venv_desc)),
                        PackageItem("python3-pip", context.getString(com.ai.assistance.quro.terminal.R.string.package_python_pip_name), "python3-pip", context.getString(com.ai.assistance.quro.terminal.R.string.package_python_pip_desc)),
                        PackageItem("uv", context.getString(com.ai.assistance.quro.terminal.R.string.package_uv_name), "pipx install uv", context.getString(com.ai.assistance.quro.terminal.R.string.package_uv_desc))
                    )
                ),
                PackageCategory(
                    id = "java",
                    name = context.getString(com.ai.assistance.quro.terminal.R.string.category_java_name),
                    description = context.getString(com.ai.assistance.quro.terminal.R.string.category_java_desc),
                    packages = listOf(
                        PackageItem("openjdk-17", context.getString(com.ai.assistance.quro.terminal.R.string.package_openjdk_name), "openjdk-17-jdk", context.getString(com.ai.assistance.quro.terminal.R.string.package_openjdk_desc))
                    )
                ),
                PackageCategory(
                    id = "rust",
                    name = context.getString(com.ai.assistance.quro.terminal.R.string.category_rust_name),
                    description = context.getString(com.ai.assistance.quro.terminal.R.string.category_rust_desc),
                    packages = listOf(
                        PackageItem("rust", context.getString(com.ai.assistance.quro.terminal.R.string.package_rust_name), "RUST_INSTALL_COMMAND", context.getString(com.ai.assistance.quro.terminal.R.string.package_rust_desc))
                    )
                ),
                PackageCategory(
                    id = "go",
                    name = context.getString(com.ai.assistance.quro.terminal.R.string.category_go_name),
                    description = context.getString(com.ai.assistance.quro.terminal.R.string.category_go_desc),
                    packages = listOf(
                        PackageItem("go", context.getString(com.ai.assistance.quro.terminal.R.string.package_go_name), "golang-go", context.getString(com.ai.assistance.quro.terminal.R.string.package_go_desc))
                    )
                )
            )
        }
    }

    // 跟踪每个分类的展开状态
    val expandedCategories = remember { mutableStateMapOf<String, Boolean>() }

    // 跟踪选中的包
    val selectedPackages = remember { mutableStateMapOf<String, Boolean>() }

    // 跟踪每个分类的全选状态
    val categorySelectAll = remember { mutableStateMapOf<String, Boolean>() }

    // 新增：跟踪包的安装状态
    val packageStatus = remember { mutableStateMapOf<String, InstallStatus>() }
    val terminalManager = remember(context) { TerminalManager.getInstance(context) }
    val coroutineScope = rememberCoroutineScope()
    var checkSessionId by remember { mutableStateOf<String?>(null) }

    // 创建一个用于检查的会话，并在 Composable 销毁时关闭它
    DisposableEffect(terminalManager) {
        val job = coroutineScope.launch {
            try {
                val session = terminalManager.createNewSession("setup-check")
                checkSessionId = session.id
                Log.d("SetupScreen", "Created check session: ${session.id}")
            } catch (e: Exception) {
                Log.e("SetupScreen", "Failed to create check session", e)
            }
        }

        onDispose {
            job.cancel()
            checkSessionId?.let {
                Log.d("SetupScreen", "Closing check session $it")
                terminalManager.closeSession(it)
            }
        }
    }

    // 当会话准备好后，开始检查包状态
    LaunchedEffect(checkSessionId) {
        val sessionId = checkSessionId ?: return@LaunchedEffect

        // 初始化所有包为检查中状态
        val allPackages = packageCategories.flatMap { it.packages }
        allPackages.forEach { pkg ->
            packageStatus[pkg.id] = InstallStatus.CHECKING
        }

        // 并发检查所有包
        allPackages.forEach { pkg ->
            launch {
                val isInstalled = checkPackageInstalled(terminalManager, sessionId, pkg, this)
                if (isInstalled) {
                    packageStatus[pkg.id] = InstallStatus.INSTALLED
                    selectedPackages[pkg.id] = true
                } else {
                    packageStatus[pkg.id] = InstallStatus.NOT_INSTALLED
                }

                // 检查是否需要更新分类的全选状态
                val category = packageCategories.find { c -> c.packages.any { it.id == pkg.id } }
                category?.let { cat ->
                    val allInCategoryFinishedChecking = cat.packages.all { p -> packageStatus[p.id] != InstallStatus.CHECKING }
                    if (allInCategoryFinishedChecking) {
                        val allInCategorySelected = cat.packages.all { p -> selectedPackages[p.id] == true }
                        categorySelectAll[cat.id] = allInCategorySelected
                    }
                }
            }
        }
    }

    var showSetupDialog by remember { mutableStateOf(false) }
    val commandsToRun = remember { mutableStateOf<List<String>>(emptyList()) }

    if (showSetupDialog) {
        AlertDialog(
            onDismissRequest = { showSetupDialog = false },
            title = { Text(context.getString(com.ai.assistance.quro.terminal.R.string.setup_dialog_title), color = SettingsTheme.onSurfaceColor, fontWeight = FontWeight.SemiBold) },
            text = { Text(context.getString(com.ai.assistance.quro.terminal.R.string.setup_dialog_message), color = SettingsTheme.onSurfaceVariant) },
            confirmButton = {
                Button(
                    onClick = {
                        showSetupDialog = false
                        // 在开始设置前，显式关闭检查会话
                        checkSessionId?.let { sid ->
                            Log.d("SetupScreen", "Closing check session $sid before starting setup.")
                            terminalManager.closeSession(sid)
                            checkSessionId = null // 防止 onDispose 重复关闭
                        }
                        onSetup(commandsToRun.value)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SettingsTheme.primaryColor)
                ) {
                    Text(context.getString(com.ai.assistance.quro.terminal.R.string.dialog_confirm), color = androidx.compose.ui.graphics.Color.White)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showSetupDialog = false },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SettingsTheme.onSurfaceVariant)
                ) {
                    Text(context.getString(com.ai.assistance.quro.terminal.R.string.dialog_cancel), color = SettingsTheme.onSurfaceVariant)
                }
            },
            containerColor = SettingsTheme.surfaceColor
        )
    }

    val startSetup = {
        val commands = mutableListOf<String>()

        // 系统修复（串行）
        commands.add("dpkg --configure -a")
        commands.add("apt install -f -y")

        // 更新软件源
        commands.add("apt update -y")

        // 系统升级
        commands.add("apt upgrade -y")

        // 为 pip/pipx 设置国内镜像（永久配置）
        commands.add("mkdir -p ~/.config/pip")
        commands.add("echo '[global]' > ~/.config/pip/pip.conf")
        commands.add("echo 'index-url = https://pypi.tuna.tsinghua.edu.cn/simple' >> ~/.config/pip/pip.conf")

        // 为 uv/uvx 设置国内镜像（永久配置）
        commands.add("mkdir -p ~/.config/uv")
        commands.add("echo 'index-url = \"https://pypi.tuna.tsinghua.edu.cn/simple\"' > ~/.config/uv/uv.toml")

        // 收集选中的包
        val selectedAptPackages = mutableListOf<String>()
        val selectedNpmPackages = mutableListOf<String>()
        val selectedCustomCommands = mutableListOf<String>()

        packageCategories.forEach { category ->
            category.packages.forEach { pkg ->
                if (selectedPackages[pkg.id] == true && packageStatus[pkg.id] != InstallStatus.INSTALLED) {
                    // 根据分类和包 ID 判断包管理器
                    if (pkg.id == "rust") {
                        // 获取当前选择的 Rust 镜像源
                        val rustSource = sourceManager.getSelectedSource(PackageManagerType.RUST)
                        val rustEnvCommand = sourceManager.getRustSourceEnvCommand(rustSource)
                        // 添加环境变量设置和安装命令
                        selectedCustomCommands.add("$rustEnvCommand && curl -v --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y")
                    } else if (pkg.id == "uv" || pkg.id == "nodejs") {
                        selectedCustomCommands.add(pkg.command)
                    } else if (category.id == "nodejs" && pkg.id != "nodejs") {
                        selectedNpmPackages.add(pkg.command)
                    } else {
                        selectedAptPackages.add(pkg.command)
                    }
                }
            }
        }

        // 添加 pipx 作为 uv 的依赖
        if (selectedPackages.getOrDefault("uv", false) && packageStatus["uv"] != InstallStatus.INSTALLED) {
            selectedAptPackages.add("pipx")
        }

        // 首先安装所有依赖包
        val allAptDeps = mutableSetOf<String>()

        // 添加自定义命令的依赖
        if (selectedCustomCommands.isNotEmpty()) {
            if (selectedPackages.getOrDefault("rust", false)) {
                allAptDeps.add("curl")
                allAptDeps.add("build-essential")
            }
            if (selectedPackages.getOrDefault("nodejs", false)) {
                allAptDeps.add("curl")
            }
        }

        // 添加选中的 apt 包
        allAptDeps.addAll(selectedAptPackages)

        // 使用 apt 安装所有 apt 包和依赖
        if (allAptDeps.isNotEmpty()) {
            commands.add("apt install -y ${allAptDeps.joinToString(" ")}")
        }

        // 然后运行自定义命令（如安装 rust, uv, nodejs 等）
        if (selectedCustomCommands.isNotEmpty()) {
            commands.addAll(selectedCustomCommands)

            // 如果安装了 uv，则需要确保 pipx 路径可用
            if (selectedPackages.getOrDefault("uv", false)) {
                commands.add("pipx ensurepath")
                commands.add("source ~/.profile")
            }
        }

        // 安装 NPM 包（如果 nodejs 已经安装或被选中）
        if (selectedNpmPackages.isNotEmpty()) {
            // 更换为淘宝源
            commands.add("npm config set registry https://registry.npmmirror.com/")
            // 清理 npm 缓存
            commands.add("npm cache clean --force")
            // 安装 pnpm
            commands.add("npm install -g pnpm")
            // 使用 pnpm 安装其他包
            commands.add("pnpm add -g ${selectedNpmPackages.joinToString(" ")}")
        }

        commandsToRun.value = commands
        showSetupDialog = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SettingsTheme.backgroundColor)
    ) {
        // 顶部操作栏：跳过 + 品牌
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onBack) {
                Text(
                    text = context.getString(com.ai.assistance.quro.terminal.R.string.skip),
                    color = SettingsTheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
            }
            Text(
                text = "ZorvAI Terminal",
                color = SettingsTheme.primaryColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // 标题区
        Text(
            text = context.getString(com.ai.assistance.quro.terminal.R.string.setup_title),
            color = SettingsTheme.onSurfaceColor,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Serif,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Text(
            text = context.getString(com.ai.assistance.quro.terminal.R.string.setup_subtitle),
            color = SettingsTheme.onSurfaceVariant,
            fontSize = 14.sp,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = 16.dp)
        )

        // 包分类列表（扁平行，不再用大白卡片）
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
        ) {
            items(packageCategories) { category ->
                EnvironmentItem(
                    category = category,
                    isExpanded = expandedCategories[category.id] ?: false,
                    onExpandToggle = { expandedCategories[category.id] = !expandedCategories.getOrDefault(category.id, false) },
                    selectedPackages = selectedPackages,
                    categorySelectAll = categorySelectAll,
                    packageStatus = packageStatus
                )
            }
        }

        // 底部：单个悬浮主按钮，彻底去掉并排双按钮
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            ExtendedFloatingActionButton(
                text = { Text(context.getString(com.ai.assistance.quro.terminal.R.string.start_setup)) },
                icon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
                onClick = startSetup,
                containerColor = SettingsTheme.primaryColor,
                contentColor = androidx.compose.ui.graphics.Color.White,
                expanded = true
            )
        }
    }
}

@Composable
private fun EnvironmentItem(
    category: PackageCategory,
    isExpanded: Boolean,
    onExpandToggle: () -> Unit,
    selectedPackages: MutableMap<String, Boolean>,
    categorySelectAll: MutableMap<String, Boolean>,
    packageStatus: Map<String, InstallStatus>
) {
    val context = LocalContext.current
    val allInstalled = category.packages.all { packageStatus[it.id] == InstallStatus.INSTALLED }
    val allChecked = category.packages.all { selectedPackages[it.id] == true || packageStatus[it.id] == InstallStatus.INSTALLED }
    val anyChecking = category.packages.any { packageStatus[it.id] == InstallStatus.CHECKING }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !allInstalled) { onExpandToggle() }
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 图标徽标
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(SettingsTheme.surfaceColor)
                    .border(1.dp, SettingsTheme.divider, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = categoryIcon(category.id),
                    contentDescription = null,
                    tint = SettingsTheme.primaryColor,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            // 名称与描述
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = category.name,
                        color = SettingsTheme.onSurfaceColor,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (category.id == "nodejs" || category.id == "python") {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            color = SettingsTheme.warningColor.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = context.getString(com.ai.assistance.quro.terminal.R.string.zorvai_required),
                                color = SettingsTheme.warningColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Text(
                    text = category.description,
                    color = SettingsTheme.mutedColor,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            Spacer(Modifier.width(8.dp))

            // 右侧状态：全装完显示绿标，否则 Switch 作为全选
            when {
                allInstalled -> {
                    Surface(
                        color = SettingsTheme.successColor.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = SettingsTheme.successColor,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = context.getString(com.ai.assistance.quro.terminal.R.string.installed),
                                color = SettingsTheme.successColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                anyChecking -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = SettingsTheme.primaryColor,
                        strokeWidth = 2.dp
                    )
                }
                else -> {
                    Switch(
                        checked = allChecked,
                        onCheckedChange = { selectAll ->
                            categorySelectAll[category.id] = selectAll
                            category.packages.forEach { pkg ->
                                if (packageStatus[pkg.id] != InstallStatus.INSTALLED) {
                                    selectedPackages[pkg.id] = selectAll
                                }
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = SettingsTheme.primaryColor,
                            checkedTrackColor = SettingsTheme.primaryColor.copy(alpha = 0.5f),
                            uncheckedThumbColor = SettingsTheme.mutedColor,
                            uncheckedTrackColor = SettingsTheme.mutedColor.copy(alpha = 0.3f)
                        )
                    )
                }
            }
        }

        // 展开后的包明细
        if (isExpanded) {
            Column(
                modifier = Modifier
                    .padding(start = 56.dp, bottom = 8.dp)
            ) {
                category.packages.forEach { pkg ->
                    PackageCheckRow(
                        packageItem = pkg,
                        isSelected = selectedPackages[pkg.id] ?: false,
                        onSelectionChange = { selected ->
                            selectedPackages[pkg.id] = selected
                            categorySelectAll[category.id] = category.packages.all { p ->
                                selectedPackages[p.id] == true || packageStatus[p.id] == InstallStatus.INSTALLED
                            }
                        },
                        status = packageStatus[pkg.id] ?: InstallStatus.NOT_INSTALLED
                    )
                }
            }
            HorizontalDivider(color = SettingsTheme.divider)
        }
    }
}

@Composable
private fun PackageCheckRow(
    packageItem: PackageItem,
    isSelected: Boolean,
    onSelectionChange: (Boolean) -> Unit,
    status: InstallStatus
) {
    val context = LocalContext.current
    val isInstalled = status == InstallStatus.INSTALLED
    val isChecking = status == InstallStatus.CHECKING

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isInstalled) { onSelectionChange(!isSelected) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isChecking) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = SettingsTheme.primaryColor,
                strokeWidth = 2.dp
            )
            Spacer(Modifier.width(12.dp))
        } else {
            Checkbox(
                checked = isSelected || isInstalled,
                onCheckedChange = onSelectionChange,
                enabled = !isInstalled,
                colors = CheckboxDefaults.colors(
                    checkedColor = SettingsTheme.primaryColor,
                    uncheckedColor = SettingsTheme.mutedColor,
                    disabledCheckedColor = SettingsTheme.successColor.copy(alpha = 0.5f)
                ),
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = packageItem.name,
                    color = if (isInstalled) SettingsTheme.successColor else SettingsTheme.onSurfaceColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                if (isInstalled) {
                    Text(
                        text = " (${context.getString(com.ai.assistance.quro.terminal.R.string.installed)})",
                        color = SettingsTheme.successColor,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(start = 3.dp)
                    )
                }
            }
            if (packageItem.description.isNotEmpty()) {
                Text(
                    text = packageItem.description,
                    color = SettingsTheme.mutedColor,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 1.dp)
                )
            }
        }
    }
}

@Composable
private fun categoryIcon(categoryId: String) = when (categoryId) {
    "nodejs" -> Icons.Default.Code
    "python" -> Icons.Default.Extension
    "java" -> Icons.Default.Coffee
    "rust" -> Icons.Default.Settings
    "go" -> Icons.Default.Memory
    else -> Icons.Default.Code
}

@RequiresApi(Build.VERSION_CODES.O)
private suspend fun checkPackageInstalled(
    terminalManager: TerminalManager,
    sessionId: String,
    pkg: PackageItem,
    scope: CoroutineScope
): Boolean {
    val command: String = when (pkg.id) {
        "rust" -> "command -v rustc"
        "uv" -> "command -v uv"
        "nodejs" -> "node -v 2>/dev/null"
        "pnpm" -> "test -f \"$(npm prefix -g)/bin/pnpm\" && echo FOUND_PNPM"
        "go" -> "command -v go"
        else -> "dpkg -s ${pkg.command.split(" ").first()}"
    }

    val output = executeCommandAndGetOutput(terminalManager, sessionId, command, scope)
    if (output == null) return false // 超时或错误

    return when (pkg.id) {
        "nodejs" -> {
            // 检查 Node.js 版本是否 >= 24
            if (output.isBlank() || output.contains("not found")) return false
            val versionMatch = Regex("""v(\d+)\..*""").find(output.trim())
            val majorVersion = versionMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            majorVersion >= 24
        }
        "rust", "uv", "go" -> output.isNotBlank() && !output.contains("not found")
        "pnpm" -> output.contains("FOUND_PNPM")
        else -> output.contains("Status: install ok installed")
    }
}

@RequiresApi(Build.VERSION_CODES.O)
private suspend fun executeCommandAndGetOutput(
    terminalManager: TerminalManager,
    sessionId: String,
    command: String,
    scope: CoroutineScope
): String? {
    val deferred = CompletableDeferred<String>()
    val output = StringBuilder()
    val commandId = UUID.randomUUID().toString()
    val collectorReady = CompletableDeferred<Unit>()

    val job = scope.launch {
        terminalManager.commandExecutionEvents
            .filter { it.sessionId == sessionId && it.commandId == commandId }
            .onStart { collectorReady.complete(Unit) }
            .collect { event ->
                output.append(event.outputChunk)
                if (event.isCompleted) {
                    if (!deferred.isCompleted) {
                        deferred.complete(output.toString())
                    }
                }
            }
    }

    collectorReady.await()
    terminalManager.switchToSession(sessionId)
    terminalManager.sendCommand(command, commandId)

    val result = withTimeoutOrNull(15000L) { // 15s timeout
        deferred.await()
    }

    job.cancel()
    return result
}
