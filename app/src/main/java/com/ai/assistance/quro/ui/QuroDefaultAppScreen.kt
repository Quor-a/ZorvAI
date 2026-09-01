package com.ai.assistance.quro.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import com.ai.assistance.quro.core.approle.DefaultAppRole
import com.ai.assistance.quro.core.approle.QuroDefaultAppManager
import com.ai.assistance.quro.ui.theme.Accent
import com.ai.assistance.quro.ui.theme.AccentSoft
import com.ai.assistance.quro.ui.theme.Card
import com.ai.assistance.quro.ui.theme.Line
import com.ai.assistance.quro.ui.theme.Muted
import com.ai.assistance.quro.ui.theme.Sage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 默认应用角色管理（路线图标 ①：设置新增「默认应用」入口）。
 *
 * 覆盖用户要求的 8 项：桌面启动器(HOME) / 浏览器(BROWSER) / 相册 / 视频 / 邮箱 / 文档 / 消息(SMS) / 拨号(DIALER)。
 *
 * - 平台角色（HOME/BROWSER/DIALER/SMS）经 [RoleManager] 申请与查询（API 29+）。
 * - 非平台角色（相册/视频/邮箱/文档）靠 `QuroDefaultAppHandlerActivity` 的 Manifest 过滤器成为候选，
 *   由本页构造隐式意图触发系统选择器，用户选「Zorv AI + 总是」即设为默认。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuroDefaultAppScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var probing by remember { mutableStateOf(true) }
    // 各角色「本应用是否持有」+「当前默认持有者包名」
    var held by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var holders by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }

    fun refresh() {
        scope.launch {
            probing = true
            val (h, ho) = withContext(Dispatchers.IO) {
                val hMap = mutableMapOf<String, Boolean>()
                val hoMap = mutableMapOf<String, String?>()
                DefaultAppRole.entries.forEach { role ->
                    hMap[role.id] = QuroDefaultAppManager.isHeld(ctx, role)
                    hoMap[role.id] = QuroDefaultAppManager.currentHolder(ctx, role)
                }
                hMap to hoMap
            }
            held = h
            holders = ho
            probing = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    // 系统角色申请 / 选择器触发：startActivityForResult 需要 Activity 上下文（本页在 MainActivity 内组合，LocalContext 即 Activity）。
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // 返回即刷新（用户可能在系统角色框确认/取消，或选了默认应用）
        refresh()
    }

    fun request(role: DefaultAppRole) {
        val intent = runCatching { QuroDefaultAppManager.requestIntent(ctx, role) }.getOrNull()
        if (intent == null) {
            Toast.makeText(ctx, "当前系统不支持该角色（需 Android 10+）", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching { launcher.launch(intent) }.onFailure {
            Toast.makeText(ctx, "无法发起系统选择：${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = MaterialTheme.colorScheme.onSurface) }
            Spacer(Modifier.width(8.dp))
            Text("默认应用", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.weight(1f))
            if (!probing) {
                IconButton(onClick = { refresh() }) { Icon(Icons.Filled.Sync, contentDescription = "刷新", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                Text("探测中…", fontSize = 12.sp, color = Muted)
            }
        }
        HorizontalDivider(color = Line)

        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            GroupCaption("平台角色（系统角色，API 29+）")
            SetGroup {
                (listOf(DefaultAppRole.HOME, DefaultAppRole.BROWSER, DefaultAppRole.DIALER, DefaultAppRole.SMS)).forEachIndexed { idx, role ->
                    if (idx > 0) HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
                    RoleRow(role, held[role.id] ?: false, holders[role.id], probing, onRequest = { request(role) })
                }
            }

            GroupCaption("其它默认（靠系统选择器设为默认）")
            SetGroup {
                (listOf(DefaultAppRole.GALLERY, DefaultAppRole.VIDEO, DefaultAppRole.EMAIL, DefaultAppRole.DOCUMENT)).forEachIndexed { idx, role ->
                    if (idx > 0) HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
                    RoleRow(role, held[role.id] ?: false, holders[role.id], probing, onRequest = { request(role) })
                }
            }

            GroupCaption("说明")
            SetGroup {
                InfoLine("设为默认后，打开对应类型（网页 / 图片 / 视频 / 邮件 / 文档 / 拨号 / 短信 / 桌面）时由 Zorv AI 接管处理。")
                InfoLine("平台角色（桌面启动器 / 浏览器 / 拨号 / 短信）经系统角色框申请；本应用未实现桌面 UI，故「桌面启动器」仅提交角色申请，是否生效取决于系统是否将其列为合格候选。")
                InfoLine("相册 / 视频 / 邮箱 / 文档无系统角色，点「设为默认」会弹出系统选择器，请选择 Zorv AI 并勾选「总是」。")
                InfoLine("取消默认请到系统设置 → 应用 → Zorv AI → 默认打开 / 默认应用，或对应类型的默认应用管理页。")
            }
        }
    }
}

/** 单个角色行：图标 + 名称/副标题 + 当前状态 + 设为默认按钮。 */
@Composable
private fun RoleRow(role: DefaultAppRole, isHeld: Boolean, holder: String?, probing: Boolean, onRequest: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val icon = iconFor(role.iconName)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(AccentSoft), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(20.dp), tint = Accent)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(role.label, fontSize = 15.sp, color = cs.onSurface, fontWeight = FontWeight.SemiBold)
            Text(role.desc, fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 2.dp))
            val status = statusText(role, isHeld, holder, probing)
            if (status.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(status, fontSize = 12.sp, color = if (isHeld) Sage else Muted)
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.clip(RoundedCornerShape(8.dp))
                .background(if (isHeld) Sage.copy(alpha = 0.15f) else AccentSoft)
                .clickable(onClick = onRequest)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(if (isHeld) "已是默认" else "设为默认", fontSize = 13.sp, color = if (isHeld) Sage else Accent, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** 当前默认状态文案。 */
private fun statusText(role: DefaultAppRole, isHeld: Boolean, holder: String?, probing: Boolean): String {
    if (probing) return "探测中…"
    if (role.platformRole != null) {
        return when {
            isHeld -> "✓ Zorv AI 已是默认"
            holder != null && holder.isNotBlank() -> "当前默认：${shortPkg(holder)}"
            else -> "当前未设置默认"
        }
    }
    // 非平台角色无法用 RoleManager 查询，引导到系统查看
    return if (isHeld) "✓ Zorv AI 已是默认" else "请在系统默认应用管理中查看"
}

private fun shortPkg(pkg: String): String = pkg.substringAfterLast('.').ifBlank { pkg }

private fun iconFor(name: String): ImageVector = when (name) {
    "home" -> Icons.Filled.Home
    "public" -> Icons.Filled.Public
    "call" -> Icons.Filled.Call
    "sms" -> Icons.Filled.Sms
    "image" -> Icons.Filled.Image
    "movie" -> Icons.Filled.Movie
    "email" -> Icons.Filled.Email
    "description" -> Icons.Filled.Description
    else -> Icons.Filled.Apps
}

/** 纯文本说明行。 */
@Composable
private fun InfoLine(text: String) {
    Text(
        text, fontSize = 12.sp, color = Muted, lineHeight = 18.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
    )
}
