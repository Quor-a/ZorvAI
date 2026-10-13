package com.ai.assistance.quro.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ai.assistance.quro.R
import com.ai.assistance.quro.service.QuroWebUiService
import com.ai.assistance.quro.util.qstr
import kotlinx.coroutines.delay

/**
 * Web UI 的**设置**（用户 2026-10-13 拍板：「ZorvAI 内的 web UI 只是设置，通过外部打开」）。
 *
 * ── 这里**不再**嵌 WebView ─────────────────────────────────────────────
 * 改前本文件是 `WebUiDialog`：弹一个内嵌浏览器给用户「在手机上看手机」。
 * 两个问题都很硬：
 *   ① 那个内嵌界面**一关，服务就没了**（服务是在它的 `remember` 里创建的），
 *      于是「返回 → 链接打不开」（用户原话）。
 *   ② 在手机上看手机上同一块屏幕，本身就说明定位错了 ——
 *      Web UI 的价值是「手机之外的屏幕」。
 * 现在这个页面只做三件事：开关、显示地址、复制。
 * 真正的使用者是别的设备上的浏览器。
 *
 * 服务本体在 [QuroWebUiService]（前台服务），地址由 [QuroWebUiHost] 提供 ——
 * 二者都不依赖本界面是否存在，所以关掉这个弹窗**不会**断掉链接。
 *
 * @param dark        当前主题（推给服务，网页跟着换皮肤）
 * @param modelName   顶栏模型名（与原生 ChatTopBar 同源，避免两边说法不一致）
 * @param personaName 顶栏人格名（同上）
 */
@Composable
fun WebUiLinkDialog(
    dark: Boolean,
    modelName: String,
    personaName: String,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current

    var enabled by remember { mutableStateOf(QuroWebUiService.isEnabled(ctx)) }
    var url by remember { mutableStateOf(QuroWebUiHost.accessUrl()) }
    var copied by remember { mutableStateOf(false) }
    // 服务是异步起来的（startForegroundService → onCreate 里才 bind），
    // 直接读一次必然读到 null，所以打开期间按秒刷新，直到拿到地址。
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            url = QuroWebUiHost.accessUrl()
            if (enabled != QuroWebUiService.isEnabled(ctx)) enabled = QuroWebUiService.isEnabled(ctx)
            // 顶栏/主题与会话同步：地址拿不到说明服务还没起来，这几句是幂等的
            QuroWebUiHost.update {
                it.dark = dark
                it.modelName = modelName
                it.personaName = personaName
            }
            delay(1000)
            tick++
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(qstr(R.string.qk_04088)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    qstr(R.string.qk_04089),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = enabled,
                        onCheckedChange = { on ->
                            enabled = on
                            if (on) {
                                QuroWebUiService.start(ctx)
                            } else {
                                QuroWebUiService.stop(ctx)
                                url = null
                            }
                            copied = false
                        },
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (enabled) qstr(R.string.qk_04074) else qstr(R.string.qk_04085),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(Modifier.height(10.dp))

                val u = url
                when {
                    u != null -> {
                        Text(
                            qstr(R.string.qk_04090),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            u,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .padding(vertical = 6.dp),
                        )
                        Spacer(Modifier.height(4.dp))
                        TextButton(onClick = {
                            clip.setText(AnnotatedString(u))
                            copied = true
                        }) {
                            Text(if (copied) qstr(R.string.qk_04082) else qstr(R.string.qk_04081))
                        }
                    }
                    enabled -> Text(
                        qstr(R.string.qk_04092),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> Text(
                        qstr(R.string.qk_04091),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(qstr(R.string.qk_00011)) }
        },
    )
}
