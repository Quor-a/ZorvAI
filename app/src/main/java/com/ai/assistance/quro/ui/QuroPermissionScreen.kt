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
import androidx.lifecycle.repeatOnLifecycle
import com.ai.assistance.quro.core.permissions.QuroPermissionHelper
import com.ai.assistance.quro.core.permissions.QuroPermissionItem
import com.ai.assistance.quro.core.policy.QuroPolicyStore
import com.ai.assistance.quro.core.privilege.*
import com.ai.assistance.quro.core.shizuku.QuroShizuku
import com.ai.assistance.quro.core.shizuku.QuroShizukuPkg
import com.ai.assistance.quro.service.QuroAccessibilityService
import com.ai.assistance.quro.ui.theme.QuroTheme
import com.ai.assistance.quro.ui.theme.Accent
import com.ai.assistance.quro.ui.theme.AccentSoft
import com.ai.assistance.quro.ui.theme.Sage
import com.ai.assistance.quro.ui.theme.Muted
import com.ai.assistance.quro.ui.theme.Line
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.collectAsState

/** L2 轮询兜底间隔（主路径是 Binder 事件监听，故可放宽）。 */
private const val L2_POLL_INTERVAL_MS = 2500L

/** 异步探测尚未返回时的占位文案。 */
private const val PROBING_HINT = "正在探测…"

/**
 * 生成「探测中」占位状态，保证 states 在任何时刻都含全部四个等级。
 *
 * 必要性：UI 用 states[LEVEL] 取值渲染卡片，而真正的 probeAsync() 要等首帧组合之后
 * 的 LaunchedEffect 才执行。若初值为 emptyMap()，首帧就会取不到值。
 */
private fun probingStates(): Map<PrivilegeLevel, PrivilegeState> =
    PrivilegeLevel.entries.associateWith { PrivilegeState(it, false, PROBING_HINT) }

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

    // FIX P0-2: 旧代码 mgr.probe() 在组合期同步调用 → checkRoot() 阻塞主线程最多 5s → ANR。
    // 改为「探测中」占位 map + LaunchedEffect 异步探测。
    //
    // ⚠️ 必须用占位而不是 emptyMap()：下方 PrivilegeCard 用 states[LEVEL]!! 取值，
    // 而 LaunchedEffect 在首帧组合「之后」才执行 → 首帧拿到 emptyMap 会直接 NPE 崩溃。
    // 用 probing() 保证任何时刻四个等级都有值。
    var states by remember { mutableStateOf(probingStates()) }
    var stdItems by remember { mutableStateOf(stdPerms(ctx)) }
    var pending by remember { mutableStateOf<Pair<PrivilegeLevel, String>?>(null) }
    var deferred = remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    var showAudit by remember { mutableStateOf(false) }

    /** 安全取值：即便某等级缺失也不崩，退化为「探测中」。 */
    fun st(level: PrivilegeLevel): PrivilegeState =
        states[level] ?: PrivilegeState(level, false, PROBING_HINT)

    // 首次进入：异步探测（checkRoot 在 IO 线程跑，不阻塞 UI）
    LaunchedEffect(Unit) { states = mgr.probeAsync() }

    QuroPolicyStore.getPriv(ctx)
    val privPolicy by QuroPolicyStore.privFlow.collectAsState()

    fun refresh() {
        scope.launch { states = mgr.probeAsync() }
        stdItems = stdPerms(ctx)
    }

    // 轻量刷新：仅重探 Shizuku(L2)，避免每次轮询都跑 L4 的 su 检测（#915）。
    // state() 内部要查 PackageManager + ping Binder，属于 IO，放到 Dispatchers.IO 上做，
    // 只把结果切回主线程赋值，避免在主线程/组合期做阻塞调用。
    fun refreshL2() {
        scope.launch {
            val l2 = withContext(Dispatchers.IO) { QuroShizukuBridge.state(ctx) }
            states = states + (PrivilegeLevel.L2 to l2)
        }
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

    // 重探 L2 Shizuku：用户在 Shizuku 应用内单独授权时不一定触发本应用 onResume 或 Binder 事件。
    //
    // FIX（E-4）：旧实现是 LaunchedEffect(Unit){ while(true){ delay(1500); refreshL2() } } ——
    // 无任何生命周期守卫，页面切后台/被覆盖时照跑，且 refreshL2 现已走 IO，等于每 1.5s 白起一个协程。
    // 改为 repeatOnLifecycle(STARTED)：不可见即取消，回到前台自动重启；间隔放宽到 2500ms。
    // 主路径是下方的 Binder 事件监听（addBinderReceivedListener / addBinderDeadListener），
    // 此处轮询仅作兜底：Shizuku 已连接、用户仅在其应用内改授权时不会发 binder 事件。
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(L2_POLL_INTERVAL_MS)
                refreshL2()
            }
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

    // Shizuku Binder 事件驱动刷新（E-4 的「更优解」，轮询降级为兜底）：
    //  - onBinderReceived：用户在 Shizuku 应用中启动服务/授权后 Binder 到达，立即重探
    //  - onBinderDead：Shizuku 服务被杀时立刻把 L2 打回不可用，避免 UI 停留在「已就绪」的假象
    // 两个 listener 都在 onDispose 里移除，反复进出页面不会累积。
    DisposableEffect(ctx) {
        val received = Shizuku.OnBinderReceivedListener { refresh() }
        val dead = Shizuku.OnBinderDeadListener { refreshL2() }
        runCatching {
            Shizuku.addBinderReceivedListener(received)
            Shizuku.addBinderDeadListener(dead)
        }
        onDispose {
            runCatching {
                Shizuku.removeBinderReceivedListener(received)
                Shizuku.removeBinderDeadListener(dead)
            }
        }
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
                state = st(PrivilegeLevel.L1),
                rationale = "UI 交互 / 屏幕内容读取（基础自动化）。",
                onRequest = { requestElevation(PrivilegeLevel.L1, "需要无障碍权限以执行界面自动化与屏幕读取。") },
                testLabel = if (st(PrivilegeLevel.L1).available) "测试" else null,
                onTest = if (st(PrivilegeLevel.L1).available) {
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
                state = st(PrivilegeLevel.L2),
                rationale = "系统 API 调用 / 静默安装 / 冻结应用（免 Root）。",
                onRequest = {
                    // 🔧 立即可见反馈：杜绝"点了按钮完全没反应"的体感
                    Toast.makeText(ctx, "正在请求 Shizuku 授权…", Toast.LENGTH_SHORT).show()

                    // 拉起 Shizuku 应用本身，让用户在应用内把本应用加入允许列表并授权（最可靠的兜底路径，
                    // 因为 Shizuku 的权限本来就是在 Shizuku Manager 应用里授予的；程序化 requestPermission
                    // 在部分机型/版本会"静默失败"——既不弹框也不报错，正是之前"没反应"的真凶）。
                    fun openShizukuManager() {
                        // FIX（E-2）：这里原来把 setPackage 写死成 v11 旧包 "moe.shizuku.manager"，
                        // 在装了主流 v12+（moe.shizuku.privileged.api）的机器上 startActivity 抛
                        // ActivityNotFoundException → 落进 catch → 按钮静默失败，就是「点了没反应」。
                        // 必须用设备上实际安装的包名。
                        // 注意：action 字符串里的 moe.shizuku.manager.* 是协议命名空间不是包名，保持原样。
                        val pkg = QuroShizukuPkg.installed(ctx)
                        if (pkg == null) {
                            Toast.makeText(ctx, "未检测到 Shizuku，请先安装后再授权", Toast.LENGTH_LONG).show()
                            runCatching {
                                ctx.startActivity(
                                    android.content.Intent(android.content.Intent.ACTION_VIEW)
                                        .setData(android.net.Uri.parse("market://details?id=${QuroShizukuPkg.storePackage()}"))
                                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }.onFailure {
                                // 无商店（F-Droid / 无 GMS 设备）：退回官网
                                runCatching {
                                    ctx.startActivity(
                                        android.content.Intent(android.content.Intent.ACTION_VIEW)
                                            .setData(android.net.Uri.parse(QuroShizukuPkg.HOMEPAGE))
                                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            }
                            return
                        }
                        val permIntent = android.content.Intent(QuroShizukuPkg.Action.REQUEST_PERMISSION)
                            .setPackage(pkg)
                        val resolved = runCatching { permIntent.resolveActivity(ctx.packageManager) }.getOrNull()
                        if (resolved != null) {
                            try { ctx.startActivity(permIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) {}
                        }
                        val launch = ctx.packageManager.getLaunchIntentForPackage(pkg)
                        if (launch != null) {
                            try { ctx.startActivity(launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                        } else {
                            // getLaunchIntentForPackage 返回 null 不代表未安装——
                            // Shizuku Manager 的 Launcher Activity 可能被隐藏或受 ROM 限制。
                            // 此时 installed() 已确认包存在，应引导用户手动打开。
                            Toast.makeText(ctx, "无法自动打开 Shizuku 管理器，请手动打开 Shizuku 应用并授权本应用", Toast.LENGTH_LONG).show()
                            // 尝试用通用 ACTION 启动（不依赖 launcher intent）
                            try {
                                ctx.startActivity(android.content.Intent(QuroShizukuPkg.Action.MAIN_ACTIVITY)
                                    .setPackage(pkg)
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                            } catch (_: Exception) { /* 静默 */ }
                        }
                    }

                    if (!QuroShizukuPkg.isInstalled(ctx)) {
                        // 未安装：openShizukuManager 内部会跳商店（无商店则跳官网），不再只弹一个 Toast 了事
                        openShizukuManager()
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
                    // 授权结果监听（两路复用）
                    val listener = Shizuku.OnRequestPermissionResultListener { _req, _grant ->
                        refresh()
                        Toast.makeText(ctx, if (_grant == android.content.pm.PackageManager.PERMISSION_GRANTED) "Shizuku 授权成功 ✓" else "Shizuku 授权被拒绝", Toast.LENGTH_SHORT).show()
                    }
                    // ═══ 关键修复（v436）：requestPermission 仅在 Shizuku Binder 存活（服务运行中）时才会弹系统授权框；
                    // 若 Shizuku 已装但未运行（Binder dead / 服务未启动），调用会「静默失败」——既不弹框也不报错，
                    // 正是之前「点了按钮只打开 App、不弹授权框」的真凶。故先确认 isAlive，未运行则先拉起 Shizuku 并
                    // 等待 Binder 就绪，再授权；避免落入无意义的 fallback。
                    if (!QuroShizuku.isAlive) {
                        Toast.makeText(ctx, "Shizuku 未运行，正在打开并等待服务启动…", Toast.LENGTH_SHORT).show()
                        openShizukuManager()
                        // 轮询等待 Binder 就绪（用户在 Shizuku 应用中通过 ADB/无线调试启动服务后 Binder 才会 ping 通），最多约 12s
                        scope.launch {
                            var alive = false
                            repeat(24) {
                                kotlinx.coroutines.delay(500)
                                if (QuroShizuku.isAlive) { alive = true; return@repeat }
                            }
                            if (alive) {
                                QuroShizuku.requestPermission(act, 1024, listener)
                            } else {
                                Toast.makeText(ctx, "Shizuku 仍未就绪：请先在该应用中启动服务（ADB 无线调试/配对），再点此按钮授权", Toast.LENGTH_LONG).show()
                            }
                        }
                        return@PrivilegeCard
                    }
                    // Binder 已存活：直接弹系统授权框（体验最佳）
                    try {
                        QuroShizuku.requestPermission(act, 1024, listener)
                        // 兜底：若 3s 后仍未授权（极少数机型 requestPermission 仍静默失败），自动拉起 Shizuku 应用
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
                testLabel = if (st(PrivilegeLevel.L2).available) "状态" else null,
                onTest = if (st(PrivilegeLevel.L2).available) {
                    { QuroShizukuBridge.state(ctx).details }
                } else null,
            )
            PrivilegeCard(
                level = PrivilegeLevel.L3,
                title = "设备管理员",
                channel = "DevicePolicyManager",
                state = st(PrivilegeLevel.L3),
                // E-5/E-11：device admin policy 已收敛到 force-lock + disable-camera 两条，
                // 文案必须逐条对应，不得出现「等高级系统管理能力」这类无实现的宽泛表述。
                rationale = "锁屏 / 禁用摄像头（仅此两项）。",
                onRequest = { requestElevation(PrivilegeLevel.L3, "需要设备管理员权限，仅用于锁定屏幕和禁用/恢复摄像头两项操作。") },
                testLabel = if (st(PrivilegeLevel.L3).available) "状态" else null,
                onTest = if (st(PrivilegeLevel.L3).available) {
                    { "设备管理员：${st(PrivilegeLevel.L3).details}（纯净架构下不主动锁屏）" }
                } else null,
            )
            PrivilegeCard(
                level = PrivilegeLevel.L4,
                title = "ROOT 访问",
                channel = "su / Magisk",
                state = st(PrivilegeLevel.L4),
                rationale = "内核级操作 / 系统文件修改 / SELinux（最高风险）。",
                onRequest = { Toast.makeText(ctx, "请在 Root 管理器中允许 CapOS", Toast.LENGTH_LONG).show() },
                testLabel = if (st(PrivilegeLevel.L4).available) "状态" else null,
                onTest = if (st(PrivilegeLevel.L4).available) {
                    { "ROOT：${st(PrivilegeLevel.L4).details}（root 命令经 root_exec / shizuku_root_exec 工具真实执行，受「权限模式」策略约束）" }
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
                // complete() 不是挂起函数，无需再包一层 scope.launch
                deferred.value?.complete(false)
                deferred.value = null
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
                    // FIX（E-3①）：必须先 complete(true)，否则 requestElevation → confirm() → await() 永久挂起
                    // （默认 ASK 策略下走这条路径，每点一次泄漏一个协程）。
                    //
                    // 补充修复：这里必须把 deferred **捕获成局部变量**再用。
                    // 旧写法在协程里读 deferred.value!!，若用户在协程跑完前又触发一次提升，
                    // deferred.value 已被换成新的未完成 Deferred → 老协程 await 到新对象上 → 又挂住；
                    // 且 !! 在 value 被置空时会直接 NPE。
                    val d = deferred.value
                    d?.complete(true)
                    deferred.value = null
                    // 用户确认后引导开启（L1/L2/L3 跳转系统界面；L4 仅提示）
                    mgr.launchIntentFor(level)?.let { ctx.startActivity(it) }
                    scope.launch {
                        // confirm 回调只认捕获到的那个 d；d 为 null 说明已确认过，直接放行
                        mgr.requestElevation("capos.kernel", level, rationale) { d?.await() ?: true }
                        refresh()
                    }
                    pending = null
                }) { Text("授权") }
            },
            dismissButton = {
                TextButton(onClick = {
                    deferred.value?.complete(false)
                    deferred.value = null
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
