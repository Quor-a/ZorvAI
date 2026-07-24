package com.ai.assistance.quro.ui

import android.Manifest
import android.content.Context
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.core.content.ContextCompat

/**
 * 应用内媒体库浏览器（视频/音乐）。
 * 自行用 MediaStore 扫描本地媒体库并以应用 UI 呈现，点击直接在应用内播放，
 * 全程不调用系统文件选择器 / 系统播放器 —— 解决「打开就是手机文件管理 + 系统视频界面」问题。
 */
private data class MediaItem(
    val uri: Uri,
    val name: String,
    val size: Long,
    val durationMs: Long,
)

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format("%.1f GB", bytes / 1_000_000_000f)
    bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1_000_000f)
    bytes >= 1_000 -> String.format("%.1f KB", bytes / 1_000f)
    else -> "$bytes B"
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return ""
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
}

private fun loadMedia(ctx: Context, kind: String): List<MediaItem> {
    val coll = if (kind == "music") MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    val durCol = if (kind == "music") MediaStore.Audio.Media.DURATION else MediaStore.Video.Media.DURATION
    val proj = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.SIZE,
        durCol,
    )
    val out = mutableListOf<MediaItem>()
    runCatching {
        ctx.contentResolver.query(coll, proj, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val durIdx = c.getColumnIndex(durCol)
            while (c.moveToNext()) {
                val id = c.getLong(idIdx)
                val uri = ContentUris.withAppendedId(coll, id)
                val name = c.getString(nameIdx) ?: "未知文件"
                val size = c.getLong(sizeIdx)
                val dur = if (durIdx >= 0) c.getLong(durIdx) else 0L
                out.add(MediaItem(uri, name, size, dur))
            }
        }
    }
    return out
}

@Composable
fun QuroMediaBrowser(
    kind: String,
    onPick: (Uri, String) -> Unit,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val neededPerm = if (Build.VERSION.SDK_INT >= 33) {
        if (kind == "music") Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_MEDIA_VIDEO
    } else Manifest.permission.READ_EXTERNAL_STORAGE

    var items by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var loadErr by remember { mutableStateOf<String?>(null) }
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, neededPerm) == PackageManager.PERMISSION_GRANTED)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (ok) items = loadMedia(ctx, kind)
    }

    LaunchedEffect(granted) {
        if (granted) runCatching { items = loadMedia(ctx, kind) }.onFailure { loadErr = it.message }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = cs.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.ArrowBack, "返回") }
                    Text(
                        if (kind == "music") "选择音乐" else "选择视频",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )
                }
                HorizontalDivider()
                when {
                    !granted -> Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "需要读取${if (kind == "music") "音乐" else "视频"}文件的权限才能浏览媒体库。",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { permLauncher.launch(neededPerm) }) { Text("授予权限") }
                    }
                    loadErr != null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("读取失败：$loadErr", color = cs.error)
                    }
                    items.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("没有找到${if (kind == "music") "音乐" else "视频"}文件")
                    }
                    else -> LazyColumn(modifier = Modifier.fillMaxSize().padding(vertical = 4.dp)) {
                        items(items) { item ->
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { onPick(item.uri, item.name) }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    if (kind == "music") Icons.Filled.MusicNote else Icons.Filled.Movie,
                                    null,
                                    tint = cs.primary,
                                    modifier = Modifier.size(28.dp),
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        item.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    val sub = buildList {
                                        add(formatSize(item.size))
                                        formatDuration(item.durationMs).takeIf { it.isNotEmpty() }?.let { add(it) }
                                    }.joinToString(" · ")
                                    if (sub.isNotEmpty()) {
                                        Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                                    }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}
