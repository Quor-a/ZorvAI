package com.ai.assistance.quro.ui
import androidx.compose.ui.res.stringResource
import com.ai.assistance.quro.R
import com.ai.assistance.quro.util.qstr

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
            Toast.makeText(ctx, qstr(R.string.qk_01685), Toast.LENGTH_SHORT).show()
            return
        }
        runCatching { launcher.launch(intent) }.onFailure {
            Toast.makeText(ctx, qstr(R.string.qk_01686, (it.message).toString()), Toast.LENGTH_SHORT).show()
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.qk_00143), tint = MaterialTheme.colorScheme.onSurface) }
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.qk_01687), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.weight(1f))
            if (!probing) {
                IconButton(onClick = { refresh() }) { Icon(Icons.Filled.Sync, contentDescription = stringResource(R.string.qk_00459), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                Text(stringResource(R.string.qk_01688), fontSize = 12.sp, color = Muted)
            }
        }
        HorizontalDivider(color = Line)

        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            GroupCaption(stringResource(R.string.qk_01689))
            SetGroup {
                (listOf(DefaultAppRole.HOME, DefaultAppRole.BROWSER, DefaultAppRole.DIALER, DefaultAppRole.SMS)).forEachIndexed { idx, role ->
                    if (idx > 0) HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
                    RoleRow(role, held[role.id] ?: false, holders[role.id], probing, onRequest = { request(role) })
                }
            }

            GroupCaption(stringResource(R.string.qk_01690))
            SetGroup {
                (listOf(DefaultAppRole.GALLERY, DefaultAppRole.VIDEO, DefaultAppRole.EMAIL, DefaultAppRole.DOCUMENT)).forEachIndexed { idx, role ->
                    if (idx > 0) HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
                    RoleRow(role, held[role.id] ?: false, holders[role.id], probing, onRequest = { request(role) })
                }
            }

            GroupCaption(stringResource(R.string.qk_01691))
            SetGroup {
                InfoLine(stringResource(R.string.qk_01692))
                InfoLine(stringResource(R.string.qk_01693))
                InfoLine(stringResource(R.string.qk_01694))
                InfoLine(stringResource(R.string.qk_01695))
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
            Text(if (isHeld) stringResource(R.string.qk_01696) else stringResource(R.string.qk_01697), fontSize = 13.sp, color = if (isHeld) Sage else Accent, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** 当前默认状态文案。 */
private fun statusText(role: DefaultAppRole, isHeld: Boolean, holder: String?, probing: Boolean): String {
    if (probing) return qstr(R.string.qk_01688)
    if (role.platformRole != null) {
        return when {
            isHeld -> qstr(R.string.qk_01698)
            holder != null && holder.isNotBlank() -> qstr(R.string.qk_01699, (shortPkg(holder)).toString())
            else -> qstr(R.string.qk_01700)
        }
    }
    // 非平台角色无法用 RoleManager 查询，引导到系统查看
    return if (isHeld) qstr(R.string.qk_01698) else qstr(R.string.qk_01701)
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