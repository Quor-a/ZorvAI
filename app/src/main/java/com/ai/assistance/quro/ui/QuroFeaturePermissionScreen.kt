package com.ai.assistance.quro.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ai.assistance.quro.permissions.HealthPermissionHelper
import com.ai.assistance.quro.permissions.PermState
import com.ai.assistance.quro.permissions.PermissionsManager
import com.ai.assistance.quro.ui.theme.Accent
import com.ai.assistance.quro.ui.theme.AccentSoft
import com.ai.assistance.quro.ui.theme.Line
import com.ai.assistance.quro.ui.theme.Muted
import com.ai.assistance.quro.ui.theme.QuroTheme
import com.ai.assistance.quro.ui.theme.Sage
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/**
 * AI 助手「功能权限」引导页：把 4 类权限（文件/媒体、健康/健身、闹钟/提醒、数据源优先级）
 * 的 [PermState] 状态机接到用户可操作的引导 UX 上。
 *
 * 请求时机与拒绝后引导（即用户要求的 UX 流程图落地）：
 * - [PermState.Granted]      → 直接可用，展示能力/演示。
 * - [PermState.NeedRequest]  → 调起系统运行时授权框（媒体 / 健康）；精确闹钟无运行时框，固定走设置页。
 * - [PermState.NeedSettings] → 媒体：跳应用设置页（已被永久拒绝）；精确闹钟：跳精确闹钟设置页；
 *                               健康：跳 Health Connect 管理页（撤销/重授）。
 * - Health Connect 不可用    → 引导安装/打开（Android 14+ 为系统模块，正常情况下始终可用）。
 *
 * 请求发起统一用 Compose 的 [rememberLauncherForActivityResult]，避免在非 onCreate 阶段
 * 调用 activity.registerForActivityResult 抛 IllegalStateException。
 */
@Composable
fun QuroFeaturePermissionScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var mediaState by remember { mutableStateOf<PermState>(PermState.NeedRequest) }
    var alarmState by remember { mutableStateOf<PermState>(PermState.NeedRequest) }
    var overlayState by remember { mutableStateOf<PermState>(PermState.NeedSettings) }
    var fitnessState by remember { mutableStateOf<PermState>(PermState.NeedRequest) }
    var allFilesState by remember { mutableStateOf<PermState>(PermState.NeedSettings) }
    var assistantState by remember { mutableStateOf<PermState>(PermState.NeedRequest) }
    var healthState by remember { mutableStateOf<PermState?>(null) }   // null = 探测中
    var healthAvail by remember { mutableStateOf<Boolean?>(null) }     // null = 探测中
    var exportMsg by remember { mutableStateOf<String?>(null) }
    var alarmMsg by remember { mutableStateOf<String?>(null) }
    var stepsBySource by remember { mutableStateOf<Map<String, Long>?>(null) }
    var dataSourceMsg by remember { mutableStateOf<String?>(null) }

    val act = unwrapActivity(ctx)
    val pm = remember(act) { act?.let { PermissionsManager(it) } }
    val manager = pm

    fun refresh() {
        val m = manager ?: return
        mediaState = m.mediaState()
        alarmState = m.alarmState()
        overlayState = m.overlayState()
        fitnessState = m.fitnessState()
        allFilesState = m.allFilesState()
        assistantState = m.assistantState()
        scope.launch {
            val avail = HealthPermissionHelper.isHealthConnectAvailable(ctx)
            healthAvail = avail
            healthState = if (avail) {
                if (m.health.hasAllPermissions()) PermState.Granted else PermState.NeedRequest
            } else null
        }
    }

    fun exportSample() {
        val m = manager ?: return
        val html = "<!doctype html><html><body><h1>ZorvAI 导出示例</h1><p>由功能权限演示经 MediaStore 导出，无需存储权限。</p></body></html>"
            .toByteArray(Charsets.UTF_8)
        val uri = m.media.exportToDownloads(ctx, "zorv_export_sample.html", "text/html", "ZorvAI", html)
        exportMsg = if (uri != null) "已导出到 Download/ZorvAI ✓" else "导出失败（请检查存储可用性）"
    }

    fun loadSteps() {
        val m = manager ?: return
        scope.launch {
            runCatching {
                val end = Instant.now()
                val start = end.minus(Duration.ofDays(7))
                stepsBySource = m.health.readStepsBySource(start, end)
            }.onFailure { stepsBySource = mapOf("读取失败: ${it.message}" to 0L) }
        }
    }

    fun writeWorkout() {
        val m = manager ?: return
        scope.launch {
            runCatching {
                val end = Instant.now()
                val start = end.minus(Duration.ofMinutes(30))
                m.health.writeWorkout(start, end, "ZorvAI 跑步")
                dataSourceMsg = "已写入一条跑步记录（来源=本应用）✓"
            }.onFailure { dataSourceMsg = "写入失败: ${it.message}" }
        }
    }

    val mediaLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        scope.launch { refresh() }
    }
    val healthLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        scope.launch { refresh() }
    }
    val fitnessLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        scope.launch { refresh() }
    }
    val assistantLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        scope.launch { refresh() }
    }

    // 首次进入异步探测（Health Connect 状态需 IO）
    LaunchedEffect(Unit) { refresh() }

    // 从设置页返回时刷新（用户可能刚授权/撤销）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    QuroTheme {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 28.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") }
                Text(
                    "功能权限",
                    style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f),
                )
            }

            Text(
                "AI 助手需要这些权限来读写你的文件与文档、健康与健身数据、识别运动状态、常驻锁屏/悬浮窗、设为默认数字助理，并设置精准提醒。所有请求都走系统标准授权流程，你可随时在系统设置中撤销。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 本机自诊断：直接读出「已安装版本 / 系统版本 / 本包是否声明两项特殊权限」，
            // 避免「应用不在系统列表里」这类无法判断到底是旧包还是系统未索引的扯皮。
            val pkgInfo = remember(ctx) {
                runCatching {
                    ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
                }.getOrNull()
            }
            val installedVersion = pkgInfo?.let {
                val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode else it.versionCode.toLong()
                "${it.versionName} ($code)"
            } ?: "未知"
            val declaredPerms = pkgInfo?.requestedPermissions?.toSet().orEmpty()
            val hasExactAlarm = declaredPerms.contains("android.permission.SCHEDULE_EXACT_ALARM")
            val hasAllFiles = declaredPerms.contains("android.permission.MANAGE_EXTERNAL_STORAGE")
            val androidVer = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
            DiagnosticCard(
                installedVersion = installedVersion,
                androidVer = androidVer,
                hasExactAlarm = hasExactAlarm,
                hasAllFiles = hasAllFiles,
            )

            if (manager == null) {
                Text("当前上下文无法初始化权限管理器（缺少 Activity），请联系开发者。", color = Muted)
                return@Column
            }

            // ---- 1. 文件与媒体 ----
            val mediaAction = when (mediaState) {
                PermState.Granted -> "导出示例文件" to { exportSample() }
                PermState.NeedRequest -> "请求媒体权限" to {
                    manager.media.hasRequested = true
                    mediaLauncher.launch(manager.media.permissionsNeeded())
                }
                else -> "前往应用设置" to { openAppSettings(ctx) }
            }
            FeaturePermCard(
                icon = Icons.Filled.Folder,
                title = "文件与媒体",
                caption = "READ_MEDIA_IMAGES / VIDEO / AUDIO",
                state = mediaState,
                rationale = "读取本地素材、把生成结果导出到公共 Download。导出走 MediaStore，无需任何存储权限。",
                actionLabel = mediaAction.first,
                onAction = mediaAction.second,
                note = if (mediaState == PermState.NeedSettings) "已被永久拒绝，需到「设置 → 应用 → 权限」手动开启。" else exportMsg,
            )

            // ---- 2. 健康与健身 ----
            val healthAction = when {
                healthAvail == false -> "打开 / 安装 Health Connect" to { HealthPermissionHelper.openHealthConnectManage(ctx) }
                healthState == PermState.Granted -> "管理 Health Connect" to { HealthPermissionHelper.openHealthConnectManage(ctx) }
                healthState == null -> "探测中…" to {}
                else -> "授权健康数据" to { healthLauncher.launch(manager.health.requiredPermissions) }
            }
            FeaturePermCard(
                icon = Icons.Filled.FavoriteBorder,
                title = "健康与健身",
                caption = "Health Connect (Steps / HeartRate / Sleep / Weight / Exercise)",
                state = if (healthAvail == false) null else healthState,
                unavailable = healthAvail == false,
                rationale = "读取/写入步数、心率、睡眠等健康数据，并在多来源间区分优先级。",
                actionLabel = healthAction.first,
                onAction = healthAction.second,
                enabled = healthState != null,
                note = when {
                    healthAvail == false -> "设备未提供 Health Connect（Android 14+ 应为系统模块）。可尝试在应用商店安装，或后续接入厂商健康 SDK。"
                    healthState == PermState.Granted -> "已授权 ${manager.health.requiredPermissions.size} 项数据类型。"
                    else -> null
                },
            )

            // ---- 3. 闹钟与提醒 ----
            val alarmAction = when (alarmState) {
                PermState.Granted -> "测试提醒（5 秒后）" to {
                    manager.alarm.setExactAlarm(System.currentTimeMillis() + 5000, "ZorvAI 提醒", "这是一条测试提醒")
                    alarmMsg = "已设置 5 秒后精确闹钟 ✓"
                }
                else -> "开启精确闹钟" to { manager.alarm.openExactAlarmSettings() }
            }
            FeaturePermCard(
                icon = Icons.Filled.Alarm,
                title = "闹钟与提醒",
                caption = "SCHEDULE_EXACT_ALARM",
                state = alarmState,
                rationale = "在设定的时间精准提醒你运动、喝水等。精确闹钟为特殊权限，无法运行时弹窗，必须到设置页开启。",
                actionLabel = alarmAction.first,
                onAction = alarmAction.second,
                note = if (alarmState == PermState.NeedSettings) "需到「设置 → 应用 → 精确闹钟」手动开启。" else alarmMsg,
            )

            // ---- 4. 锁屏显示 / 悬浮窗 ----
            FeaturePermCard(
                icon = Icons.Filled.Home,
                title = "锁屏显示",
                caption = "SYSTEM_ALERT_WINDOW",
                state = overlayState,
                rationale = "让 AI 助手以悬浮窗 / 锁屏卡片形式常驻显示，随时唤出对话与快捷操作。该权限为特殊权限，需到设置页开启。",
                actionLabel = if (overlayState == PermState.Granted) "前往设置（可关闭）" else "开启锁屏显示",
                onAction = { manager.overlay.openOverlaySettings() },
                note = if (overlayState == PermState.Granted) "已授权，可在系统设置中关闭。" else "需到「设置 → 应用 → 特殊应用权限 → 显示在其他应用上层」手动开启。",
            )

            // ---- 5. 健身与运动 ----
            val fitnessAction = when (fitnessState) {
                PermState.Granted -> "前往应用设置" to { openAppSettings(ctx) }
                PermState.NeedRequest -> "请求健身与运动权限" to {
                    manager.fitness.hasRequested = true
                    fitnessLauncher.launch(manager.fitness.permissionsNeeded())
                }
                else -> "前往应用设置" to { openAppSettings(ctx) }
            }
            FeaturePermCard(
                icon = Icons.Filled.Favorite,
                title = "健身与运动",
                caption = "ACTIVITY_RECOGNITION",
                state = fitnessState,
                rationale = "读取设备活动识别（步行 / 跑步等），自动记录运动状态、联动健康数据。Android 10+ 需运行时授予。",
                actionLabel = fitnessAction.first,
                onAction = fitnessAction.second,
                note = if (fitnessState == PermState.NeedSettings) "已被永久拒绝，需到「设置 → 应用 → 权限」手动开启。" else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) "当前系统版本无需此权限。" else null,
            )

            // ---- 6. 文件与文档（所有文件访问）----
            FeaturePermCard(
                icon = Icons.Filled.Description,
                title = "文件与文档",
                caption = "MANAGE_EXTERNAL_STORAGE",
                state = allFilesState,
                rationale = "访问设备全部文件系统（含文档、下载、外部 SD），便于跨目录读取/整理你的文件与资料。该权限为特殊权限，需到设置页开启。",
                actionLabel = if (allFilesState == PermState.Granted) "前往设置（可关闭）" else "开启所有文件访问",
                onAction = { manager.allFiles.openAllFilesSettings() },
                note = if (allFilesState == PermState.Granted) "已授权，可在系统设置中关闭。" else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "需到「设置 → 应用 → 特殊应用权限 → 所有文件访问权限」手动开启。" else "当前系统版本无需此特殊权限。",
            )

            // ---- 7. 数字助理应用完整功能 ----
            val assistantAction = when (assistantState) {
                PermState.Granted -> "前往默认助理设置" to { openAppSettings(ctx) }
                PermState.NeedRequest -> "设为默认数字助理" to {
                    val intent = manager.assistant.createRequestIntent()
                    if (intent != null) assistantLauncher.launch(intent) else openAppSettings(ctx)
                }
                else -> "前往应用设置" to { openAppSettings(ctx) }
            }
            FeaturePermCard(
                icon = Icons.Filled.Assistant,
                title = "数字助理应用完整功能",
                caption = "ROLE_ASSISTANT",
                state = assistantState,
                rationale = "将 Zorv AI 设为系统默认数字助理，接管长按 Home / 侧键唤醒的助手手势，提供全局语音/文本助理能力。经系统角色选择框授予。",
                actionLabel = assistantAction.first,
                onAction = assistantAction.second,
                note = if (assistantState == PermState.Granted) "已是默认数字助理。可在「设置 → 默认应用 → 数字助理」更改。" else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) "当前系统版本不支持数字助理角色。" else "经系统「默认数字助理」选择框授予，确认后即可全局唤醒。",
            )

            // ---- 8. 数据源与优先级 ----
            DataSourceCard(
                enabled = healthAvail == true && healthState == PermState.Granted,
                stepsBySource = stepsBySource,
                message = dataSourceMsg,
                onLoadSteps = { loadSteps() },
                onWrite = { writeWorkout() },
            )

            Text(
                "说明：精确闹钟在 Android 12+ 属特殊权限，没有系统弹窗，只能跳转设置页；媒体与健康的运行时请求在首次或曾被拒（未选「不再询问」）时才会弹窗，一旦勾选「不再询问」即降级为「去设置页」。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }
}

/** 本机自诊断卡片：把「到底装了哪个版本 / 系统是否支持 / 本包是否声明特殊权限」直接显示出来。 */
@Composable
private fun DiagnosticCard(
    installedVersion: String,
    androidVer: String,
    hasExactAlarm: Boolean,
    hasAllFiles: Boolean,
) {
    val pass = MaterialTheme.colorScheme.primary
    val fail = MaterialTheme.colorScheme.error
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .border(1.dp, Line, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("本机诊断", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("已安装版本", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(installedVersion, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("系统版本", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(androidVer, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("已声明 SCHEDULE_EXACT_ALARM", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (hasExactAlarm) "是 ✓" else "否 ✗", color = if (hasExactAlarm) pass else fail, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("已声明 MANAGE_EXTERNAL_STORAGE", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (hasAllFiles) "是 ✓" else "否 ✗", color = if (hasAllFiles) pass else fail, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        }
        Text(
            "两项均为「是」但系统「特殊应用权限」列表仍无本应用：① 重开设置页或重启设备让系统重新索引；② 闹钟与提醒列表需 Android 12+，所有文件访问需 Android 11+；③ 确认安装的是本版本而非旧包（旧包仍含 USE_EXACT_ALARM 会被系统隐藏）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 单类权限引导卡片。 */
@Composable
private fun FeaturePermCard(
    icon: ImageVector,
    title: String,
    caption: String,
    state: PermState?,
    unavailable: Boolean = false,
    rationale: String,
    actionLabel: String,
    onAction: () -> Unit,
    enabled: Boolean = true,
    note: String? = null,
) {
    val (chipText, chipColor) = when {
        unavailable -> "不可用" to Muted
        state == null -> "探测中" to Muted
        state == PermState.Granted -> "已授权" to Sage
        state == PermState.NeedRequest -> "需授权" to Accent
        else -> "去设置" to Muted
    }
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, Line, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = Accent, shape = RoundedCornerShape(8.dp), modifier = Modifier.size(38.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, Modifier.size(22.dp), tint = Color.White)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Surface(color = chipColor.copy(alpha = 0.15f), shape = RoundedCornerShape(20.dp)) {
                Text(chipText, color = chipColor, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
        Text(rationale, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PrimaryButton(text = actionLabel, onClick = onAction, enabled = enabled)
        note?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = if (state == PermState.Granted) Sage else Muted)
        }
    }
}

/** 数据源与优先级演示卡片：按 DataOrigin 分组读步数 + 写入本应用来源记录。 */
@Composable
private fun DataSourceCard(
    enabled: Boolean,
    stepsBySource: Map<String, Long>?,
    message: String?,
    onLoadSteps: () -> Unit,
    onWrite: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, Line, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = Accent, shape = RoundedCornerShape(8.dp), modifier = Modifier.size(38.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Layers, null, Modifier.size(22.dp), tint = Color.White)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("数据源与优先级", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text("DataOrigin 分组 · 本应用来源可溯源", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            "健康数据可能来自多个 App（手表、Google Fit、本应用等）。通过 DataOrigin.packageName 分组，可区分并优先采用本应用录入的数据。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(text = "读取各来源步数", onClick = onLoadSteps, modifier = Modifier.weight(1f), enabled = enabled)
            PrimaryButton(text = "写入跑步记录", onClick = onWrite, modifier = Modifier.weight(1f), enabled = enabled)
        }
        if (!enabled) {
            Text("需先授权健康数据后使用本演示。", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        stepsBySource?.let { map ->
            Spacer(Modifier.height(4.dp))
            Text("近 7 天步数（按来源）：", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            if (map.isEmpty()) {
                Text("无数据", style = MaterialTheme.typography.bodySmall, color = Muted)
            } else {
                map.forEach { (pkg, steps) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("$steps 步", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Sage) }
    }
}

/** 解包 ContextWrapper 取真实 AppCompatActivity（Compose LocalContext 可能返回包装层）。 */
private fun unwrapActivity(c: Context): AppCompatActivity? {
    var cur: Context? = c
    while (cur is ContextWrapper) {
        if (cur is AppCompatActivity) return cur
        cur = cur.baseContext
    }
    return null
}

/** 跳转到本应用的系统设置详情页（用于被永久拒绝后引导手动开启）。 */
private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}
