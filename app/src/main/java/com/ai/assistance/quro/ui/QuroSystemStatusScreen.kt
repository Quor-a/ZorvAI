package com.ai.assistance.quro.ui

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ai.assistance.quro.BuildConfig
import com.ai.assistance.quro.core.QuroPersonaRepository
import com.ai.assistance.quro.core.cms.CmsStateStore
import com.ai.assistance.quro.core.privilege.PrivilegeLevel
import com.ai.assistance.quro.core.privilege.PrivilegeState
import com.ai.assistance.quro.core.privilege.QuroPrivilegeManager
import com.ai.assistance.quro.ui.theme.Line
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 聚合式「系统状态」浏览界面（原创）：把分散在多处的关键运行态集中到一个屏幕，
 * 方便排查「装了没 / 开了没 / 跑着没 / 心跳在不在」。四分区：
 *   1) 设备信息：型号 / Android 版本 / 应用版本 / 可用存储
 *   2) 权限与能力：已授权通道数 + root·Shizuku·无障碍·设备管理员 可用性（绿/灰点，来自 QuroPrivilegeManager）
 *   3) 模块运行态：订阅 CmsStateStore.snapshot，列出模块部署态 + 最近任务终态
 *   4) 人格心跳：从 QuroPersonaViewModel 读心跳总开关与每卡孵化态（lastIncubatedAt + incubatingStates）
 *
 * 风格沿用现有 QuroAI Material3：卡片用 surface + 1.dp Line 描边，分区标题用 GroupCaption 同款字色。
 * 不引入任何新依赖。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuroSystemStatusScreen(
    onClose: () -> Unit,
    personaVm: QuroPersonaViewModel,
) {
    val ctx = LocalContext.current.applicationContext

    // 模块运行态：直接复用 CMS v2 状态系统的 snapshot（进入即 re-query，跨重启持久化）
    LaunchedEffect(Unit) { CmsStateStore.init(ctx) }
    val store by CmsStateStore.snapshot.collectAsState()

    // 权限与能力：探测 L1-L4 可用性（root 探测在 IO 线程，避免主线程阻塞）
    var priv by remember { mutableStateOf<Map<PrivilegeLevel, PrivilegeState>>(emptyMap()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { priv = runCatching { QuroPrivilegeManager(ctx).probe() }.getOrDefault(emptyMap()) }
    }

    // 人格心跳：直接只读 QuroPersonaViewModel 暴露的状态流
    val personas by personaVm.personas.collectAsState()
    val heartbeatOn by personaVm.personaHeartbeatEnabled.collectAsState()
    val incubating by personaVm.incubatingStates.collectAsState()

    // 设备信息（一次性读取即可）
    val device = remember { readDeviceInfo(ctx) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("系统状态", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            DeviceSection(device)
            PermissionSection(priv)
            ModuleSection(store)
            PersonaSection(personas, heartbeatOn, incubating)
        }
    }
}

// ---------------- 设备信息 ----------------

private data class DeviceInfo(
    val model: String,
    val androidVersion: String,
    val appVersion: String,
    val storageFreeGb: Double,
)

private fun readDeviceInfo(ctx: Context): DeviceInfo {
    val model = "${Build.MANUFACTURER} ${Build.MODEL}".replaceFirstChar { it.uppercase() }
    val androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    val appVersion = BuildConfig.VERSION_NAME
    val freeGb = runCatching {
        val stat = StatFs(Environment.getDataDirectory().path)
        stat.availableBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    }.getOrDefault(0.0)
    return DeviceInfo(model, androidVersion, appVersion, freeGb)
}

@Composable
private fun DeviceSection(info: DeviceInfo) {
    SectionCard(title = "设备信息") {
        InfoRow("设备型号", info.model)
        InfoRow("Android 版本", info.androidVersion)
        InfoRow("应用版本", "v${info.appVersion}")
        InfoRow("可用存储", "%.1f GB".format(info.storageFreeGb))
    }
}

// ---------------- 权限与能力 ----------------

@Composable
private fun PermissionSection(priv: Map<PrivilegeLevel, PrivilegeState>) {
    val rows = listOf(
        PrivilegeLevel.L1 to "无障碍 (Accessibility)",
        PrivilegeLevel.L2 to "Shizuku / ADB",
        PrivilegeLevel.L3 to "设备管理员 (Device Admin)",
        PrivilegeLevel.L4 to "Root (su)",
    )
    val granted = rows.count { (lvl, _) -> priv[lvl]?.available == true }
    SectionCard(title = "权限与能力", subtitle = "已授权通道：$granted / ${rows.size}") {
        if (priv.isEmpty()) {
            Text("权限探测中…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        rows.forEach { (lvl, label) ->
            val st = priv[lvl]
            val ok = st?.available == true
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(10.dp).background(
                        if (ok) Color(0xFF34C759) else MaterialTheme.colorScheme.outlineVariant,
                        CircleShape,
                    ),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                    val detail = st?.details?.takeIf { it.isNotBlank() }
                    if (detail != null)
                        Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    if (ok) "可用" else "不可用",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (ok) Color(0xFF34C759) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------------- 模块运行态 ----------------

@Composable
private fun ModuleSection(store: CmsStateStore.Snapshot) {
    SectionCard(title = "模块运行态", subtitle = "来自 CMS v2 状态系统") {
        val modules = store.modules.values.toList()
        if (modules.isEmpty()) {
            Text("暂无模块运行态记录（部署模块后这里会显示实时状态）。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        modules.forEach { m ->
            val (statusText, statusColor) = when (m.deployStatus) {
                "deploying" -> "● 部署中" to MaterialTheme.colorScheme.primary
                "deployed" -> "● 已部署" to Color(0xFF34C759)
                "failed" -> "⛔ 部署失败" to MaterialTheme.colorScheme.error
                else -> "○ 未部署" to MaterialTheme.colorScheme.onSurfaceVariant
            }
            // 该模块最近一次任务终态（优先看 deploy 任务，否则取任意以它为目标的任务）
            val task = store.tasks["deploy:${m.moduleId}"]
                ?: store.tasks.values.filter { it.target == m.moduleId }.maxByOrNull { it.startedAt }
            val taskEnd = task?.let {
                when (it.status) {
                    "success" -> "成功"
                    "failed" -> "失败"
                    "running" -> "运行中"
                    else -> it.status
                } + if (it.message.isNotBlank()) " · ${it.message}" else ""
            }
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(m.moduleId, style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(statusText, style = MaterialTheme.typography.labelSmall, color = statusColor)
                    if (m.running) {
                        Spacer(Modifier.width(8.dp))
                        Text("· 运行中", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (taskEnd != null)
                    Text("最近任务：$taskEnd", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (m != modules.last()) HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

// ---------------- 人格心跳 ----------------

@Composable
private fun PersonaSection(
    personas: List<com.ai.assistance.quro.core.QuroPersona>,
    heartbeatOn: Boolean,
    incubating: Map<String, Boolean>,
) {
    SectionCard(
        title = "人格心跳",
        subtitle = "心跳总开关：${if (heartbeatOn) "开" else "关"}",
    ) {
        if (personas.isEmpty()) {
            Text("暂无人格卡。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        personas.forEachIndexed { idx, p ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(p.name.ifBlank { "(未命名人格)" }, style = MaterialTheme.typography.bodyMedium)
                    val ago = formatAgo(p.lastIncubatedAt)
                    val inc = incubating[p.id] == true
                    Text(
                        "最近孵化：$ago${if (inc) " · 孵化中…" else ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (inc) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 心跳开关状态（总开关，作用于全部人格）
                Box(
                    Modifier.size(10.dp).background(
                        if (heartbeatOn) Color(0xFF34C759) else MaterialTheme.colorScheme.outlineVariant,
                        CircleShape,
                    ),
                )
                Spacer(Modifier.width(6.dp))
                Text(if (heartbeatOn) "开" else "关", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (idx != personas.lastIndex) HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

// ---------------- 通用组件 ----------------

@Composable
private fun SectionCard(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, Line, RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
        )
        if (subtitle != null)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(92.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

private fun formatAgo(ts: Long): String {
    if (ts <= 0) return "从未孵化"
    val diff = System.currentTimeMillis() - ts
    val min = diff / 60000
    return when {
        min < 1 -> "刚刚"
        min < 60 -> "${min} 分钟前"
        min < 1440 -> "${min / 60} 小时前"
        else -> "${min / 1440} 天前 (${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))})"
    }
}
