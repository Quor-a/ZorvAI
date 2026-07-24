package com.ai.assistance.quro.ui

import android.Manifest
import android.content.Context
import android.os.Build
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
import com.ai.assistance.quro.service.QuroAccessibilityService
import com.ai.assistance.quro.ui.theme.QuroTheme
import com.ai.assistance.quro.ui.theme.Accent
import com.ai.assistance.quro.ui.theme.AccentSoft
import com.ai.assistance.quro.ui.theme.Sage
import com.ai.assistance.quro.ui.theme.Muted
import com.ai.assistance.quro.ui.theme.Line
import kotlinx.coroutines.CompletableDeferred
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

    // 无障碍状态变化即时刷新：用户在系统设置开启/关闭无障碍服务时，此处监听到就重探状态，
    // 解决「授权已开但软件没更新状态」的问题（部分机型开启无障碍不会触发 Activity onResume）。
    DisposableEffect(ctx) {
        val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val listener = AccessibilityManager.AccessibilityStateChangeListener { refresh() }
        am.addAccessibilityStateChangeListener(listener)
        onDispose { am.removeAccessibilityStateChangeListener(listener) }
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
                onRequest = { requestElevation(PrivilegeLevel.L2, "需要 Shizuku 权限以调用系统级 API（静默安装/冻结应用）。") },
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
