package com.ai.assistance.quro.ui
import androidx.compose.ui.res.stringResource
import com.ai.assistance.quro.R
import com.ai.assistance.quro.util.qstr

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
        val html = qstr(R.string.qk_01868)
            .toByteArray(Charsets.UTF_8)
        val uri = m.media.exportToDownloads(ctx, "zorv_export_sample.html", "text/html", "ZorvAI", html)
        exportMsg = if (uri != null) qstr(R.string.qk_01869) else qstr(R.string.qk_01870)
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
                m.health.writeWorkout(start, end, qstr(R.string.qk_01871))
                dataSourceMsg = qstr(R.string.qk_01872)
            }.onFailure { dataSourceMsg = qstr(R.string.qk_01873, (it.message).toString()) }
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
                IconButton(onClick = onClose) { Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.qk_00143)) }
                Text(stringResource(R.string.qk_01874),
                    style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f),
                )
            }

            Text(stringResource(R.string.qk_01875),
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
            } ?: stringResource(R.string.qk_00472)
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
                Text(stringResource(R.string.qk_01876), color = Muted)
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
                title = stringResource(R.string.qk_01880),
                caption = "READ_MEDIA_IMAGES / VIDEO / AUDIO",
                state = mediaState,
                rationale = stringResource(R.string.qk_01881),
                actionLabel = mediaAction.first,
                onAction = mediaAction.second,
                note = if (mediaState == PermState.NeedSettings) stringResource(R.string.qk_01882) else exportMsg,
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
                title = stringResource(R.string.qk_01886),
                caption = "Health Connect (Steps / HeartRate / Sleep / Weight / Exercise)",
                state = if (healthAvail == false) null else healthState,
                unavailable = healthAvail == false,
                rationale = stringResource(R.string.qk_01887),
                actionLabel = healthAction.first,
                onAction = healthAction.second,
                enabled = healthState != null,
                note = when {
                    healthAvail == false -> stringResource(R.string.qk_01888)
                    healthState == PermState.Granted -> stringResource(R.string.qk_01889, (manager.health.requiredPermissions.size).toString())
                    else -> null
                },
            )

            // ---- 3. 闹钟与提醒 ----
            val alarmAction = when (alarmState) {
                PermState.Granted -> "测试提醒（5 秒后）" to {
                    manager.alarm.setExactAlarm(System.currentTimeMillis() + 5000, qstr(R.string.qk_01891), qstr(R.string.qk_01892))
                    alarmMsg = qstr(R.string.qk_01893)
                }
                else -> "开启精确闹钟" to { manager.alarm.openExactAlarmSettings() }
            }
            FeaturePermCard(
                icon = Icons.Filled.Alarm,
                title = stringResource(R.string.qk_01895),
                caption = "SCHEDULE_EXACT_ALARM",
                state = alarmState,
                rationale = stringResource(R.string.qk_01896),
                actionLabel = alarmAction.first,
                onAction = alarmAction.second,
                note = if (alarmState == PermState.NeedSettings) stringResource(R.string.qk_01897) else alarmMsg,
            )

            // ---- 4. 锁屏显示 / 悬浮窗 ----
            FeaturePermCard(
                icon = Icons.Filled.Home,
                title = stringResource(R.string.qk_01898),
                caption = "SYSTEM_ALERT_WINDOW",
                state = overlayState,
                rationale = stringResource(R.string.qk_01899),
                actionLabel = if (overlayState == PermState.Granted) stringResource(R.string.qk_01900) else stringResource(R.string.qk_01901),
                onAction = { manager.overlay.openOverlaySettings() },
                note = if (overlayState == PermState.Granted) stringResource(R.string.qk_01902) else stringResource(R.string.qk_01903),
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
                title = stringResource(R.string.qk_01905),
                caption = "ACTIVITY_RECOGNITION",
                state = fitnessState,
                rationale = stringResource(R.string.qk_01906),
                actionLabel = fitnessAction.first,
                onAction = fitnessAction.second,
                note = if (fitnessState == PermState.NeedSettings) stringResource(R.string.qk_01882) else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) stringResource(R.string.qk_01907) else null,
            )

            // ---- 6. 文件与文档（所有文件访问）----
            FeaturePermCard(
                icon = Icons.Filled.Description,
                title = stringResource(R.string.qk_01908),
                caption = "MANAGE_EXTERNAL_STORAGE",
                state = allFilesState,
                rationale = stringResource(R.string.qk_01909),
                actionLabel = if (allFilesState == PermState.Granted) stringResource(R.string.qk_01900) else stringResource(R.string.qk_01910),
                onAction = { manager.allFiles.openAllFilesSettings() },
                note = if (allFilesState == PermState.Granted) stringResource(R.string.qk_01902) else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) stringResource(R.string.qk_01911) else stringResource(R.string.qk_01912),
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
                title = stringResource(R.string.qk_01915),
                caption = "ROLE_ASSISTANT",
                state = assistantState,
                rationale = stringResource(R.string.qk_01916),
                actionLabel = assistantAction.first,
                onAction = assistantAction.second,
                note = if (assistantState == PermState.Granted) stringResource(R.string.qk_01917) else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) stringResource(R.string.qk_01918) else stringResource(R.string.qk_01919),
            )

            // ---- 8. 数据源与优先级 ----
            DataSourceCard(
                enabled = healthAvail == true && healthState == PermState.Granted,
                stepsBySource = stepsBySource,
                message = dataSourceMsg,
                onLoadSteps = { loadSteps() },
                onWrite = { writeWorkout() },
            )

            Text(stringResource(R.string.qk_01920),
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
        Text(stringResource(R.string.qk_01921), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.qk_01922), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(installedVersion, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.qk_01923), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(androidVer, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.qk_01924), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (hasExactAlarm) stringResource(R.string.qk_01925) else stringResource(R.string.qk_01926), color = if (hasExactAlarm) pass else fail, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.qk_01927), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (hasAllFiles) stringResource(R.string.qk_01925) else stringResource(R.string.qk_01926), color = if (hasAllFiles) pass else fail, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        }
        Text(stringResource(R.string.qk_01928),
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
                Text(stringResource(R.string.qk_01934), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.qk_01935), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(stringResource(R.string.qk_01936),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(text = stringResource(R.string.qk_01937), onClick = onLoadSteps, modifier = Modifier.weight(1f), enabled = enabled)
            PrimaryButton(text = stringResource(R.string.qk_01938), onClick = onWrite, modifier = Modifier.weight(1f), enabled = enabled)
        }
        if (!enabled) {
            Text(stringResource(R.string.qk_01939), style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        stepsBySource?.let { map ->
            Spacer(Modifier.height(4.dp))
            Text(qstr(R.string.qk_01940), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            if (map.isEmpty()) {
                Text(qstr(R.string.qk_01941), style = MaterialTheme.typography.bodySmall, color = Muted)
            } else {
                map.forEach { (pkg, steps) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(qstr(R.string.qk_01942, (steps).toString()), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
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