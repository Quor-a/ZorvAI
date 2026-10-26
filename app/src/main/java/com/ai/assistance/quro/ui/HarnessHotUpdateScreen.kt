package com.ai.assistance.quro.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.R
import com.ai.assistance.quro.dshmobile.HarnessHotUpdate
import com.ai.assistance.quro.ui.theme.Accent
import com.ai.assistance.quro.ui.theme.Line
import com.ai.assistance.quro.ui.theme.Muted
import com.ai.assistance.quro.ui.theme.Sage
import com.ai.assistance.quro.util.qstr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Harness 热更新屏（#428）：设置 → 数据 → Harness 热更新。
 *
 * 用户从 SAF 选快照包（.tar.xz，可选 .sha256 边车），导入交给
 * [HarnessHotUpdate.importArchive]（事务式换树 + 健康验证 + 失败回退）。
 * 本屏只做选择与进度呈现，不重复实现任何更新语义。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HarnessHotUpdateScreen(
    onClose: () -> Unit,
    scaled: (Int) -> TextUnit = { it.sp },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dangerColor = Color(android.graphics.Color.parseColor("#C0432F"))

    var archiveUri by remember { mutableStateOf<Uri?>(null) }
    var archiveName by remember { mutableStateOf("") }
    var archiveSize by remember { mutableStateOf(0L) }
    var sidecarUri by remember { mutableStateOf<Uri?>(null) }
    var sidecarName by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    // 进度行：按回执顺序追加。
    val logLines = remember { mutableStateListOf<String>() }
    var lastPhase by remember { mutableStateOf<String?>(null) } // working / verifying / done / failed

    fun takeReadPermission(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Throwable) {
            // 取不到持久授权不影响本次导入（同一次组合生命周期内流仍可读）。
        }
    }

    val archivePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            takeReadPermission(uri)
            archiveUri = uri
            archiveName = queryDisplayName(context, uri) ?: uri.lastPathSegment ?: "snapshot.tar.xz"
            archiveSize = querySize(context, uri)
        }
    }
    val sidecarPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            takeReadPermission(uri)
            sidecarUri = uri
            sidecarName = queryDisplayName(context, uri) ?: uri.lastPathSegment ?: "snapshot.sha256"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.qk_04116),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontFamily = FontFamily.Serif,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.qk_00143))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                stringResource(R.string.qk_04117),
                fontSize = scaled(13), color = Muted,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            InfoBox(stringResource(R.string.qk_04118))
            Spacer(Modifier.height(16.dp))

            // ── 快照包选择 ──
            Text(
                stringResource(R.string.qk_04119),
                fontSize = scaled(14), fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            SelectorRow(
                icon = Icons.Filled.Archive,
                name = archiveName.ifBlank { stringResource(R.string.qk_04123) },
                sub = if (archiveUri != null) formatBytes(archiveSize) else "",
                enabled = !running,
                onClick = { archivePicker.launch(SNAPSHOT_MIME_TYPES) },
            )
            Spacer(Modifier.height(14.dp))

            // ── 摘要边车选择（可选） ──
            Text(
                stringResource(R.string.qk_04121),
                fontSize = scaled(14), fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            SelectorRow(
                icon = Icons.Filled.Fingerprint,
                name = sidecarName.ifBlank { stringResource(R.string.qk_04123) },
                sub = stringResource(R.string.qk_04122),
                enabled = !running,
                onClick = { sidecarPicker.launch(CHECKSUM_MIME_TYPES) },
            )
            Spacer(Modifier.height(20.dp))

            // ── 开始更新 ──
            PrimaryButton(
                text = if (finished) stringResource(R.string.qk_04129) else stringResource(R.string.qk_04124),
                enabled = !running,
                onClick = {
                    val uri = archiveUri
                    if (uri == null) {
                        Toast.makeText(context, qstr(R.string.qk_04126), Toast.LENGTH_SHORT).show()
                        return@PrimaryButton
                    }
                    running = true
                    finished = false
                    logLines.clear()
                    lastPhase = "working"
                    val size = archiveSize
                    val sidecar = sidecarUri
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            val sidecarSha = sidecar?.let { readChecksumFile(context, it) }
                            if (sidecar != null && sidecarSha == null) {
                                scope.launch(Dispatchers.Main) {
                                    running = false
                                    finished = true
                                    lastPhase = "failed"
                                    logLines.add(qstr(R.string.qk_04128))
                                }
                                return@withContext
                            }
                            HarnessHotUpdate.importArchive(
                                context = context,
                                openInput = {
                                    context.contentResolver.openInputStream(uri)
                                        ?: throw IllegalStateException("无法读取所选的快照包")
                                },
                                declaredSize = size,
                                expectedSha256 = sidecarSha,
                                onStatus = { st ->
                                    scope.launch(Dispatchers.Main) {
                                        lastPhase = when (st.phase) {
                                            HarnessHotUpdate.Phase.Working -> "working"
                                            HarnessHotUpdate.Phase.Verifying -> "verifying"
                                            HarnessHotUpdate.Phase.Done -> "done"
                                            HarnessHotUpdate.Phase.Failed -> "failed"
                                        }
                                        logLines.add(st.text)
                                        if (st.phase == HarnessHotUpdate.Phase.Done ||
                                            st.phase == HarnessHotUpdate.Phase.Failed
                                        ) {
                                            running = false
                                            finished = true
                                        }
                                    }
                                },
                            )
                        }
                    }
                },
            )
            if (!running && !finished) {
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.qk_04125),
                    fontSize = scaled(11), color = Muted, lineHeight = scaled(16),
                )
            }

            // ── 进度 ──
            if (logLines.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (lastPhase) {
                        "done" -> Icon(Icons.Filled.CheckCircle, null, Modifier.size(18.dp), tint = Sage)
                        "failed" -> Icon(Icons.Filled.ErrorOutline, null, Modifier.size(18.dp), tint = dangerColor)
                        else -> if (running) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Accent)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.qk_04127),
                        fontSize = scaled(14), fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(12.dp),
                ) {
                    val lastLine = logLines.last()
                    for (line in logLines) {
                        Text(
                            line,
                            fontSize = scaled(12),
                            color = when {
                                line != lastLine -> MaterialTheme.colorScheme.onSurface
                                lastPhase == "done" -> Sage
                                lastPhase == "failed" -> dangerColor
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                            lineHeight = scaled(18),
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 选择行（本屏内使用：图标 + 名称 + 副说明，点击弹出 SAF 选择器）。 */
@Composable
private fun SelectorRow(
    icon: ImageVector,
    name: String,
    sub: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, Line, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(20.dp), tint = Accent)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (sub.isNotBlank()) {
                Text(
                    sub, fontSize = 11.sp, color = Muted,
                    modifier = Modifier.padding(top = 2.dp),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** SAF 展示名（读不到返回 null，由调用方兜底）。 */
private fun queryDisplayName(context: Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
    }
} catch (_: Throwable) { null }

/** SAF 字节数（读不到返回 0，只用于进度展示）。 */
private fun querySize(context: Context, uri: Uri): Long = try {
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(OpenableColumns.SIZE)
        if (idx >= 0 && c.moveToFirst()) c.getLong(idx) else 0L
    } ?: 0L
} catch (_: Throwable) { 0L }

/**
 * 读 .sha256 边车：取文本里第一个 64 位十六进制串。
 * 兼容 `sha256sum` 输出的 `<hash>  <filename>` 与裸哈希两种形态。
 */
private fun readChecksumFile(context: Context, uri: Uri): String? = try {
    val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return null
    Regex("[0-9a-fA-F]{64}").find(text)?.value
} catch (_: Throwable) { null }

/** 字节数的人类可读形态（B / KB / MB / GB）。 */
private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return ""
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.size - 1) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "${value.toLong()} ${units[unit]}" else String.format("%.1f %s", value, units[unit])
}

/** harness 快照压缩包的 SAF MIME（xz 归档；部分文件管理器报 octet-stream，一并放行）。 */
private val SNAPSHOT_MIME_TYPES = arrayOf("application/x-xz", "application/octet-stream")

/** 摘要边车的 SAF MIME（纯文本；一并放行 octet-stream）。 */
private val CHECKSUM_MIME_TYPES = arrayOf("text/plain", "application/octet-stream")
