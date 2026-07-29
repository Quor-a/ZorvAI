package com.ai.assistance.quro.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import rikka.shizuku.Shizuku
import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ai.assistance.quro.core.permissions.QuroPermissionHelper
import com.ai.assistance.quro.core.permissions.QuroPermissionItem
import com.ai.assistance.quro.core.policy.QuroPolicyStore
import com.ai.assistance.quro.core.privilege.*
import com.ai.assistance.quro.core.shizuku.QuroShizuku
import com.ai.assistance.quro.service.QuroAccessibilityService
import com.ai.assistance.quro.ui.theme.QuroTheme
import com.ai.assistance.quro.ui.theme.Accent
import com.ai.assistance.quro.ui.theme.AccentSoft
import com.ai.assistance.quro.ui.theme.Sage
import com.ai.assistance.quro.ui.theme.Muted
import com.ai.assistance.quro.ui.theme.Line
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState

/**
 * 权限管理子系统。
 *
 * 分层、受控、可审计：仅"拥有"权限不够，更要"管理"权限。
 * - 特权层级 L1（无障碍）→ L2（Shizuku）→ L3（设备管理员）→ L4（ROOT），逐级提升，普通能力无法越权。
 * - 任意等级提升必须经过 四阶段仲裁：Intent -> Policy Check -> User Confirmation -> Audit Log。
 * - 提供上帝视角的审计页（权限状态概览 + 最近审计日志）。
 * - 标准运行时权限（存储 / 定位 / 悬浮窗 / 电池豁免）保留为独立区块，不回归。
 * 包管理（插件 / 技能 / MCP / 工具）保持不变。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuroPermissionScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val mgr = remember { QuroPrivilegeManager(ctx) }

    var states by remember { mutableStateOf(mgr.probe()) }
    var stdItems by remember { mutableStateOf(stdPerms(ctx)) }
    var pending by remember { mutableStateOf<Pair<PrivilegeLevel, String>?>(null) }
    var deferred = remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    var showAudit by remember { mutableStateOf(false) }

    QuroPolicyStore.getPriv(ctx)
    val privPolicy by QuroPolicyStore.privFlow.collectAsState()

    fun refresh() {
        states = mgr.probe()
        stdItems = stdPerms(ctx)
    }

    // 轻量刷新：仅重探 Shizuku(L2)，避免每次轮询都跑 L4 的 su 检测（#915）
    fun refreshL2() {
        states = states + (PrivilegeLevel.L2 to QuroShizukuBridge.state(ctx))
    }

    // 运行时权限真实请求（存储 / 位置）
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    fun requestRuntime(item: QuroPermissionItem) {
        val perms = when (item.id) {
            "storage" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
            } else {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            "location" -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            else -> emptyArray()
        }
        if (perms.isNotEmpty()) permLauncher.launch(perms)
    }

    fun onClickStd(item: QuroPermissionItem) {
        if (item.id == "storage" || item.id == "location") requestRuntime(item)
        else item.guideIntent?.let { ctx.startActivity(it) }
    }

    // 触发一次特权提升（四阶段仲裁）
    fun requestElevation(level: PrivilegeLevel, rationale: String) {
        val d = CompletableDeferred<Boolean>()
        deferred.value = d
        pending = level to rationale
    }

    OnResume { refresh() }

    // 轮询重探 L2 Shizuku：Shizuku 在自身应用内授权后不会触发本应用 onResume / Binder 事件，
    // 导致权限页状态不刷新、看起来「没修复」。每隔 1.5s 轻量重探一次，授权后立即反映（#915）。
    LaunchedEffect(Unit) {
        while (true) {
            delay(1500)
            refreshL2()
        }
    }

    // 无障碍状态变化即时刷新：用户在系统设置开启/关闭无障碍服务时，此处监听到就重探状态，
    // 解决「授权已开但软件没更新状态」的问题（部分机型开启无障碍不会触发 Activity onResume）。
    DisposableEffect(ctx) {
        val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val listener = AccessibilityManager.AccessibilityStateChangeListener { refresh() }
        am.addAccessibilityStateChangeListener(listener)
        onDispose { am.removeAccessibilityStateChangeListener(listener) }
    }

    // Shizuku Binder 就绪时即时刷新：用户在 Shizuku 应用中授权/启动后，Binder 到达即重探状态，
    // 解决「授权已完成但权限页仍显示未授权」的问题（部分机型不会触发 Activity onResume）。
    DisposableEffect(ctx) {
        val l = Shizuku.OnBinderReceivedListener { refresh() }
        Shizuku.addBinderReceivedListener(l)
        onDispose { Shizuku.removeBinderReceivedListener(l) }
    }

    if (showAudit) {
        QuroAuditScreen(onClose = { showAudit = false })
    }

    QuroTheme {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 28.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") }
                Text("系统权限", style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
                IconButton(onClick = { showAudit = true }) {
                    Icon(Icons.Filled.History, contentDescription = "审计日志")
                }
            }

            Text(
                "分层、受控、可审计：L1 无障碍 → L2 Shizuku → L3 设备管理员 → L4 ROOT，逐级提升。任何越权操作都需经过「意图→策略检查→用户确认→审计」四阶段。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                shape = RoundedCornerShape(10.dp),
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("权限模式", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text(
                        when (privPolicy) {
                            com.ai.assistance.quro.core.policy.QuroPolicy.ALLOW -> "允许（全部允许，不再询问）"
                            com.ai.assistance.quro.core.policy.QuroPolicy.DENY -> "禁止（任何提升都被拒绝）"
                            com.ai.assistance.quro.core.policy.QuroPolicy.ASK -> "询问（每次弹确认）"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // ---- 特权层级 L1-L4 ----
            PrivilegeCard(
                level = PrivilegeLevel.L1,
                title = "无障碍服务",
                channel = "AccessibilityService",
                state = states[PrivilegeLevel.L1]!!,
                rationale = "UI 交互 / 屏幕内容读取（基础自动化）。",
                onRequest = { requestElevation(PrivilegeLevel.L1, "需要无障碍权限以执行界面自动化与屏幕读取。") },
                testLabel = if (states[PrivilegeLevel.L1]!!.available) "测试" else null,
                onTest = if (states[PrivilegeLevel.L1]!!.available) {
                    {
                        val ok = QuroAccessibilityService.instance
                            ?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS) ?: false
                        "全局动作(最近任务)：${if (ok) "已触发" else "服务未连接"}"
                    }
                } else null,
            )
            PrivilegeCard(
                level = PrivilegeLevel.L2,
                title = "Shizuku 服务",
                channel = "Shizuku / ADB Bridge",
                state = states[PrivilegeLevel.L2]!!,
                rationale = "系统 API 调用 / 静默安装 / 冻结应用（免 Root）。",
                onRequest = {
                    // 🔧 立即可见反馈：杜绝"点了按钮完全没反应"的体感
                    Toast.makeText(ctx, "正在请求 Shizuku 授权…", Toast.LENGTH_SHORT).show()

                    // 拉起 Shizuku 应用本身，让用户在应用内把本应用加入允许列表并授权（最可靠的兜底路径，
                    // 因为 Shizuku 的权限本来就是在 Shizuku Manager 应用里授予的；程序化 requestPermission
                    // 在部分机型/版本会"静默失败"——既不弹框也不报错，正是之前"没反应"的真凶）。
                    fun openShizukuManager() {
                        val permIntent = android.content.Intent("moe.shizuku.manager.intent.action.REQUEST_PERMISSION")
                            .setPackage("moe.shizuku.manager")
                        val resolved = runCatching { permIntent.resolveActivity(ctx.packageManager) }.getOrNull()
                        if (resolved != null) {
                            try { ctx.startActivity(permIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) {}
                        }
                        val launch = ctx.packageManager.getLaunchIntentForPackage("moe.shizuku.manager")
                        if (launch != null) {
                            try { ctx.startActivity(launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                        } else {
                            // getLaunchIntentForPackage 返回 null 不代表未安装——
                            // Shizuku Manager 的 Launcher Activity 可能被隐藏或受 ROM 限制。
                            // 此时 isInstalled() 已确认包存在，应引导用户手动打开。
                            Toast.makeText(ctx, "无法自动打开 Shizuku 管理器，请手动打开 Shizuku 应用并授权本应用", Toast.LENGTH_LONG).show()
                            // 尝试用通用 ACTION 启动（不依赖 launcher intent）
                            try {
                                ctx.startActivity(android.content.Intent("moe.shizuku.manager.intent.action.MAIN")
                                    .setPackage("moe.shizuku.manager")
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                            } catch (_: Exception) { /* 静默 */ }
                        }
                    }

                    if (!QuroShizuku.isInstalled(ctx)) {
                        Toast.makeText(ctx, "未检测到 Shizuku，请先安装后再授权", Toast.LENGTH_LONG).show()
                        return@PrivilegeCard
                    }
                    if (QuroShizuku.isReady) {
                        Toast.makeText(ctx, "Shizuku 已授权 ✓ 可直接使用", Toast.LENGTH_SHORT).show()
                        return@PrivilegeCard
                    }
                    // 解包 ContextWrapper 获取真实 Activity（Compose LocalContext 可能返回包装层）
                    fun unwrapActivity(c: android.content.Context): Activity? {
                        var cur = c
                        while (cur is android.content.ContextWrapper) {
                            if (cur is Activity) return cur
                            cur = cur.baseContext
                        }
                        return if (cur is Activity) cur else null
                    }
                    val act = unwrapActivity(ctx)
                    if (act == null) {
                        // 取不到 Activity（极少见）：直接打开 Shizuku 应用授权，不依赖系统弹框
                        Toast.makeText(ctx, "当前上下文无法弹系统框，已打开 Shizuku 应用授权", Toast.LENGTH_SHORT).show()
                        openShizukuManager()
                        return@PrivilegeCard
                    }
                    // 优先尝试程序化授权（弹 Shizuku 授权框，体验最佳）
                    try {
                        val listener = Shizuku.OnRequestPermissionResultListener { _req, _grant ->
                            refresh()
                            Toast.makeText(ctx, if (_grant == android.content.pm.PackageManager.PERMISSION_GRANTED) "Shizuku 授权成功 ✓" else "Shizuku 授权被拒绝", Toast.LENGTH_SHORT).show()
                        }
                        QuroShizuku.requestPermission(act, 1024, listener)
                        // 兜底：若 3s 后仍未授权（requestPermission 静默失败/用户没看到弹框），自动拉起 Shizuku 应用
                        scope.launch {
                            kotlinx.coroutines.delay(3000)
                            if (!QuroShizuku.isReady) {
                                Toast.makeText(ctx, "未弹出授权框，已为你打开 Shizuku 应用，请手动授权本应用", Toast.LENGTH_LONG).show()
                                openShizukuManager()
                            }
                        }
                    } catch (e: Exception) {
                        Toast.makeText(ctx, "程序化授权失败，已改为在 Shizuku 应用中授权", Toast.LENGTH_SHORT).show()
                        openShizukuManager()
                    }
                },
                testLabel = if (states[PrivilegeLevel.L2]!!.available) "状态" else null,
                onTest = if (states[PrivilegeLevel.L2]!!.available) {
                    { QuroShizukuBridge.state(ctx).details }
                } else null,
            )
            PrivilegeCard(
                level = PrivilegeLevel.L3,
                title = "设备管理员",
                channel = "DevicePolicyManager",
                state = states[PrivilegeLevel.L3]!!,
                rationale = "锁屏 / 清除数据 / 禁用摄像头。",
                onRequest = { requestElevation(PrivilegeLevel.L3, "需要设备管理员权限以启用锁屏等高级系统管理能力。") },
                testLabel = if (states[PrivilegeLevel.L3]!!.available) "状态" else null,
                onTest = if (states[PrivilegeLevel.L3]!!.available) {
                    { "设备管理员：${states[PrivilegeLevel.L3]!!.details}（纯净架构下不主动锁屏）" }
                } else null,
            )
            PrivilegeCard(
                level = PrivilegeLevel.L4,
                title = "ROOT 访问",
                channel = "su / Magisk",
                state = states[PrivilegeLevel.L4]!!,
                rationale = "内核级操作 / 系统文件修改 / SELinux（最高风险）。",
                onRequest = { Toast.makeText(ctx, "请在 Root 管理器中允许 CapOS", Toast.LENGTH_LONG).show() },
                testLabel = if (states[PrivilegeLevel.L4]!!.available) "状态" else null,
                onTest = if (states[PrivilegeLevel.L4]!!.available) {
                    { "ROOT：${states[PrivilegeLevel.L4]!!.details}（纯净架构下不执行 root 命令）" }
                } else null,
            )

            // ---- 标准运行时权限（保留，不回归） ----
            GroupCaption("标准运行时权限")
            SetGroup {
                stdItems.forEachIndexed { idx, item ->
                    StdPermRow(item = item, onClick = { onClickStd(item) })
                    if (idx < stdItems.lastIndex) {
                        HorizontalDivider(color = Line)
                    }
                }
            }

            Text(
                "运行时权限会弹系统授权框；ROOT / Shizuku / 设备管理员需你在系统界面主动授权。所有权限使用都会被记录到审计日志。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }

    // 四阶段仲裁的「用户确认」弹窗
    if (pending != null) {
        val (level, rationale) = pending!!
        AlertDialog(
            onDismissRequest = {
                scope.launch { deferred.value?.complete(false) }
                pending = null
            },
            title = { Text("权限提升确认 · ${level.name}") },
            text = {
                Column {
                    Text(rationale)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "通道：${QuroPrivilegeManager.channelOf(level)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    // 用户确认后引导开启（L1/L2/L3 跳转系统界面；L4 仅提示）
                    mgr.launchIntentFor(level)?.let { ctx.startActivity(it) }
                    if (level == PrivilegeLevel.L4) {
                        Toast.makeText(ctx, "请在 Root 管理器中允许 CapOS", Toast.LENGTH_LONG).show()
                    }
                    scope.launch {
                        val granted = mgr.requestElevation("capos.kernel", level, rationale) { deferred.value!!.await() }
                        refresh()
                    }
                    pending = null
                }) { Text("授权") }
            },
            dismissButton = {
                TextButton(onClick = {
                    scope.launch { deferred.value?.complete(false) }
                    pending = null
                }) { Text("拒绝") }
            },
        )
    }
}

private fun stdPerms(ctx: android.content.Context): List<QuroPermissionItem> =
    QuroPermissionHelper.getItems(ctx).filter { it.id in setOf("storage", "location", "overlay", "battery") }

@Composable
private fun PrivilegeCard(
    level: PrivilegeLevel,
    title: String,
    channel: String,
    state: PrivilegeState,
    rationale: String,
    onRequest: () -> Unit,
    testLabel: String?,
    onTest: (() -> String)?,
) {
    val statusColor = if (state.available) Sage else Muted
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, Line, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Accent, shape = RoundedCornerShape(6.dp), modifier = Modifier.size(34.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(level.name, color = Color.White, style = MaterialTheme.typography.labelMedium)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                    Text(channel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Surface(
                    color = statusColor.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Text(
                        if (state.available) "可用" else "未授权",
                        color = statusColor,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            Text(rationale, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.details.isNotBlank()) {
                Text(state.details, style = MaterialTheme.typography.bodySmall, color = statusColor)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (level != PrivilegeLevel.L4) {
                    PrimaryButton(text = "请求授权", modifier = Modifier.weight(1f), onClick = onRequest)
                } else {
                    Text("Root 无法在应用内引导，请在 Root 管理器中授权。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                }
            }
            PrivilegeTestSlot(testLabel, onTest)
        }
}

@Composable
private fun PrivilegeTestSlot(testLabel: String?, onTest: (() -> String)?) {
    var result by remember { mutableStateOf<String?>(null) }
    if (testLabel != null && onTest != null) {
        TextButton(onClick = { result = onTest() }) { Text(testLabel) }
        result?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun StdPermRow(item: QuroPermissionItem, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(AccentSoft), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Security, null, Modifier.size(20.dp), tint = Accent)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, fontSize = 15.sp, color = cs.onSurface, fontWeight = FontWeight.SemiBold)
            Text(item.desc, fontSize = 12.sp, color = cs.onSurfaceVariant)
            if (!item.granted && item.note.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(item.note, fontSize = 12.sp, color = Muted)
            }
        }
        if (item.granted) {
            Text("已开启", color = Sage, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        } else {
            Box(
                Modifier.clip(RoundedCornerShape(8.dp)).background(AccentSoft)
                    .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text("开启", fontSize = 13.sp, color = Accent, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * 轻量 OnResume：监听宿主生命周期的 RESUME 事件，用户从系统设置返回时刷新权限状态。
 */
@Composable
private fun OnResume(block: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) block()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}
