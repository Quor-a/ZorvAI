package com.ai.assistance.quro.terminal.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.ai.assistance.quro.terminal.privilege.TerminalPrivilegeBridgeHolder
import com.ai.assistance.quro.terminal.privilege.TerminalPrivilegeEntry

/**
 * 终端「权限」面板：显示 ROOT / Shizuku / ADB / LSPosed / 共享存储 状态，并支持一键请求授权。
 *
 * 通过 [TerminalPrivilegeBridgeHolder] 读取 app 侧注入的特权桥；未注入时显示提示。
 */
@Composable
fun TerminalPrivilegePanel(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val bridge = remember { TerminalPrivilegeBridgeHolder.get() }
    val activity = remember(context) { context.findActivity() }

    var entries by remember { mutableStateOf(bridge?.snapshot().orEmpty()) }
    var probing by remember { mutableStateOf(bridge != null) }

    LaunchedEffect(Unit) {
        if (bridge == null) {
            probing = false
            return@LaunchedEffect
        }
        bridge.probe { list ->
            entries = list
            probing = false
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = TerminalTheme.surfaceColor,
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            context.getString(com.ai.assistance.quro.terminal.R.string.terminal_permission_title),
                            color = TerminalTheme.onSurfaceColor,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "关闭", tint = TerminalTheme.onSurfaceVariant)
                    }
                }

                Text(
                    context.getString(com.ai.assistance.quro.terminal.R.string.terminal_permission_hint),
                    color = TerminalTheme.onSurfaceVariant,
                    fontSize = 13.sp
                )

                if (bridge == null) {
                    Text(
                        context.getString(com.ai.assistance.quro.terminal.R.string.permission_no_bridge),
                        color = TerminalTheme.warningColor,
                        fontSize = 13.sp
                    )
                } else if (probing && entries.isEmpty()) {
                    Text(
                        context.getString(com.ai.assistance.quro.terminal.R.string.permission_probing),
                        color = TerminalTheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                }

                entries.forEach { entry ->
                    PrivilegeRow(
                        entry = entry,
                        enabled = bridge != null && activity != null,
                        onRequest = {
                            val act = activity ?: return@PrivilegeRow
                            bridge?.request(act, entry.key) {
                                bridge.probe { list -> entries = list }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PrivilegeRow(
    entry: TerminalPrivilegeEntry,
    enabled: Boolean,
    onRequest: () -> Unit,
) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = TerminalTheme.elevated,
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.title,
                        color = TerminalTheme.onSurfaceColor,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        entry.status,
                        color = if (entry.available) TerminalTheme.successColor else TerminalTheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
                if (entry.available) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = TerminalTheme.successColor,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = enabled) { onRequest() },
                        color = if (enabled) TerminalTheme.primaryColor else TerminalTheme.divider,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            context.getString(com.ai.assistance.quro.terminal.R.string.permission_request),
                            color = if (enabled) Color.White else TerminalTheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }
                }
            }
            if (entry.detail.isNotBlank()) {
                Text(
                    entry.detail,
                    color = TerminalTheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
        }
    }
}

/** 从 Context 中安全取出宿主 Activity（兼容 ContextWrapper 多层包装）。 */
internal fun Context.findActivity(): Activity? {
    var cur: Context? = this
    while (cur is ContextWrapper) {
        if (cur is Activity) return cur
        cur = cur.baseContext
    }
    return cur as? Activity
}
