package com.ai.assistance.quro.ui

import android.media.AudioManager
import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import kotlinx.coroutines.delay

/**
 * 应用内全功能视频播放器（百分百开源，基于 Android 框架 VideoView）。
 * 支持播放 / 暂停、进度拖动、静音；uri 支持 content://、file:// 与 http(s)。
 * 若 uri 为空（如从工具箱「视频播放器」入口进入），则展示媒体库选择器先选片。
 */
@Composable
fun QuroVideoPlayerScreen(
    uri: String,
    title: String,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    var currentUri by remember { mutableStateOf(uri) }
    var currentTitle by remember { mutableStateOf(title) }
    var showPicker by remember { mutableStateOf(uri.isBlank()) }

    var videoView by remember { mutableStateOf<VideoView?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var position by remember { mutableStateOf(0) }
    var duration by remember { mutableStateOf(0) }
    var muted by remember { mutableStateOf(false) }

    // 进度刷新
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            val v = videoView
            if (v != null) {
                position = v.currentPosition.coerceAtLeast(0)
                if (duration == 0) duration = v.duration
            }
            delay(500)
        }
    }

    if (showPicker) {
        QuroMediaBrowser(
            kind = "video",
            onPick = { pickedUri, pickedName ->
                currentUri = pickedUri.toString()
                currentTitle = pickedName
                showPicker = false
            },
            onClose = { if (currentUri.isBlank()) onClose() else showPicker = false },
        )
        return
    }

    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.ArrowBack, "返回", tint = Color.White)
                }
                Text(
                    currentTitle.ifEmpty { "视频播放" },
                    color = Color.White,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
            }

            Box(
                Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                key(currentUri) {
                    AndroidView(
                        factory = { c ->
                            VideoView(c).apply {
                                setVideoURI(Uri.parse(currentUri))
                                setOnPreparedListener { mp ->
                                    duration = mp.duration
                                    mp.isLooping = false
                                    start()
                                    isPlaying = true
                                }
                                setOnCompletionListener {
                                    isPlaying = false
                                    position = duration
                                }
                                setOnErrorListener { _, _, _ ->
                                    isPlaying = false
                                    true
                                }
                            }.also { videoView = it }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Slider(
                    value = position.toFloat().coerceIn(0f, duration.coerceAtLeast(1).toFloat()),
                    onValueChange = {
                        val v = videoView ?: return@Slider
                        v.seekTo(it.toInt())
                        position = it.toInt()
                    },
                    valueRange = 0f..(duration.coerceAtLeast(1)).toFloat(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        formatVideoMs(position) + " / " + formatVideoMs(duration),
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = {
                        val v = videoView ?: return@IconButton
                        if (v.isPlaying) { v.pause(); isPlaying = false }
                        else { v.start(); isPlaying = true }
                    }) {
                        Icon(
                            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            null,
                            tint = Color.White,
                        )
                    }
                    IconButton(onClick = {
                        muted = !muted
                        runCatching {
                            val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
                            @Suppress("DEPRECATION")
                            am.setStreamMute(AudioManager.STREAM_MUSIC, muted)
                        }
                    }) {
                        Icon(
                            if (muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                            null,
                            tint = Color.White,
                        )
                    }
                }
            }
        }
    }
}

private fun formatVideoMs(ms: Int): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val m = s / 60
    val sec = s % 60
    return String.format("%d:%02d", m, sec)
}
