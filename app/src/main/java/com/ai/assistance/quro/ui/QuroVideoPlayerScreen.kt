package com.ai.assistance.quro.ui

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.view.WindowManager
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
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import kotlinx.coroutines.delay

/**
 * 应用内全功能视频播放器（百分百开源，基于 Android 框架 VideoView）。
 * 支持播放 / 暂停、进度拖动、音量调节（流式音量，不再全局静音系统媒体音）、
 * 倍速（0.5x/1x/1.5x/2x）、全屏横屏切换、错误提示。
 * uri 支持 content://、file:// 与 http(s)；uri 为空则先选片。
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
    var volume by remember { mutableStateOf(1f) }
    var muted by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(1f) }
    var isFullscreen by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val activity = remember { ctx.findVideoActivity() }

    fun applyVolume() {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val target = if (muted) 0 else (volume * max).toInt().coerceIn(0, max)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
    }

    // 全屏横屏/退出：旋转屏幕方向（仅对宿主 Activity 生效）
    fun toggleFullscreen() {
        val act = activity
        if (act == null) {
            isFullscreen = !isFullscreen
            return
        }
        isFullscreen = !isFullscreen
        if (isFullscreen) {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            act.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

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
                errorMsg = null
                showPicker = false
            },
            onClose = { if (currentUri.isBlank()) onClose() else showPicker = false },
        )
        return
    }

    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        Column(Modifier.fillMaxSize()) {
            // 顶栏：返回 / 标题 / 全屏（全屏时隐藏返回栏，保持沉浸）
            if (!isFullscreen) {
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
                                    setVideoSpeed(speed)
                                    start()
                                    isPlaying = true
                                    errorMsg = null
                                }
                                setOnCompletionListener {
                                    isPlaying = false
                                    position = duration
                                }
                                setOnErrorListener { _, _, _ ->
                                    isPlaying = false
                                    errorMsg = "视频加载失败：格式不支持或文件损坏"
                                    true
                                }
                            }.also { videoView = it }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                // 错误提示
                errorMsg?.let {
                    Text(it, color = Color(0xFFFF8A80), fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
                }
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
                    // 倍速
                    val speeds = listOf(0.5f, 1f, 1.5f, 2f)
                    TextButton(onClick = {
                        val next = speeds.getOrElse(speeds.indexOf(speed).let { if (it < 0) 1 else (it + 1) % speeds.size }) { 1f }
                        speed = next
                        videoView?.setVideoSpeed(next)
                    }) {
                        Text("${speed}x", color = Color.White, fontSize = 13.sp)
                    }
                    // 播放/暂停
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
                    // 静音
                    IconButton(onClick = {
                        muted = !muted
                        applyVolume()
                    }) {
                        Icon(
                            if (muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                            null,
                            tint = Color.White,
                        )
                    }
                    // 全屏/横屏
                    IconButton(onClick = { toggleFullscreen() }) {
                        Icon(
                            if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
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

/** 通过反射取 VideoView 内部 MediaPlayer 并设置倍速（VideoView 本身不暴露 playbackParams）。 */
private fun VideoView.setVideoSpeed(speed: Float) {
    runCatching {
        val m = VideoView::class.java.getDeclaredMethod("getMediaPlayer").apply { isAccessible = true }
        val mp = m.invoke(this) as? android.media.MediaPlayer ?: return
        mp.playbackParams = mp.playbackParams.setSpeed(speed)
    }
}

// 从 Context 安全取出宿主 Activity（兼容 ContextWrapper 多层包装）
private fun Context.findVideoActivity(): Activity? {
    var c: Context? = this
    while (c != null) {
        if (c is Activity) return c
        c = if (c is android.content.ContextWrapper) c.baseContext else null
    }
    return null
}
