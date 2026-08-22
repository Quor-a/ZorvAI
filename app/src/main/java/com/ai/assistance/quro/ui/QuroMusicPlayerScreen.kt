package com.ai.assistance.quro.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.core.tools.QuroMediaController
import com.ai.assistance.quro.service.QuroMediaService
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious

/**
 * 应用内全屏音乐播放器（百分百开源，基于 QuroMediaService + 框架 MediaPlayer）。
 * 订阅 [QuroMediaController] 全局状态，并通过 Intent 向 [QuroMediaService] 下发控制命令。
 * 空状态时提供「选择音乐」入口（QuroMediaBrowser），选曲即后台播放。
 */
@Composable
fun QuroMusicPlayerScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val media by QuroMediaController.state.collectAsState()
    var showPicker by remember { mutableStateOf(media.uri.isEmpty()) }

    fun sendControl(action: String, fill: (Intent) -> Unit = {}) {
        val intent = Intent(ctx, QuroMediaService::class.java).setAction(action)
        fill(intent)
        runCatching { ctx.startService(intent) }
    }

    fun pickAndPlay(uri: String, title: String) {
        val intent = Intent(ctx, QuroMediaService::class.java)
            .putExtra(QuroMediaService.EXTRA_URI, uri)
            .putExtra(QuroMediaService.EXTRA_TITLE, title)
        runCatching { ctx.startForegroundService(intent) }
    }

    if (showPicker) {
        QuroMediaBrowser(
            kind = "music",
            onPick = { uri, name ->
                pickAndPlay(uri.toString(), name)
                showPicker = false
            },
            onClose = { if (media.uri.isEmpty()) onClose() else showPicker = false },
        )
        return
    }

    Surface(Modifier.fillMaxSize(), color = cs.background) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) { Icon(Icons.Filled.ArrowBack, "返回") }
                Text(
                    "音乐播放器",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
            }

            Spacer(Modifier.height(24.dp))

            // 封面占位
            Box(
                Modifier.size(200.dp).align(Alignment.CenterHorizontally)
                    .clip(RoundedCornerShape(20.dp))
                    .padding(0.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)),
                    color = cs.surfaceVariant,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.MusicNote,
                            null,
                            tint = cs.primary,
                            modifier = Modifier.size(72.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            Text(
                media.title.ifEmpty { "本地音乐" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (media.uri.isEmpty()) "未在播放" else (if (media.isPlaying) "正在播放" else "已暂停"),
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))

            // 进度
            val dur = media.durationMs.coerceAtLeast(1)
            Slider(
                value = media.positionMs.toFloat().coerceIn(0f, dur.toFloat()),
                onValueChange = {
                    sendControl(QuroMediaService.ACTION_SEEK) { i ->
                        i.putExtra(QuroMediaService.EXTRA_SEEK_MS, it.toInt())
                    }
                },
                valueRange = 0f..dur.toFloat(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(formatMusicMs(media.positionMs), fontSize = 12.sp, color = cs.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text(formatMusicMs(media.durationMs), fontSize = 12.sp, color = cs.onSurfaceVariant)
            }

            Spacer(Modifier.height(8.dp))

            // 主控制：上一首 / 播放暂停 / 下一首
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { sendControl(QuroMediaService.ACTION_PREV) }) {
                    Icon(Icons.Filled.SkipPrevious, "上一首", Modifier.size(36.dp))
                }
                Spacer(Modifier.width(24.dp))
                FloatingActionButton(
                    onClick = { sendControl(QuroMediaService.ACTION_PLAY_PAUSE) },
                    containerColor = cs.primary,
                ) {
                    Icon(
                        if (media.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        null,
                        tint = Color.White,
                        modifier = Modifier.size(32.dp),
                    )
                }
                Spacer(Modifier.width(24.dp))
                IconButton(onClick = { sendControl(QuroMediaService.ACTION_NEXT) }) {
                    Icon(Icons.Filled.SkipNext, "下一首", Modifier.size(36.dp))
                }
            }

            Spacer(Modifier.height(12.dp))

            // 次级控制：循环 / 随机 / 倍速
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val loopIcon = when (media.loopMode) {
                    QuroMediaController.LOOP_ONE -> Icons.Filled.RepeatOne
                    QuroMediaController.LOOP_ALL -> Icons.Filled.Repeat
                    else -> Icons.Filled.Repeat
                }
                IconButton(onClick = {
                    val next = when (media.loopMode) {
                        QuroMediaController.LOOP_OFF -> QuroMediaController.LOOP_ALL
                        QuroMediaController.LOOP_ALL -> QuroMediaController.LOOP_ONE
                        else -> QuroMediaController.LOOP_OFF
                    }
                    sendControl(QuroMediaService.ACTION_SET_LOOP) {
                        it.putExtra(QuroMediaService.EXTRA_LOOP, next)
                    }
                }) {
                    Icon(
                        loopIcon,
                        "循环模式",
                        tint = if (media.loopMode != QuroMediaController.LOOP_OFF) cs.primary else cs.onSurfaceVariant,
                    )
                }
                IconButton(onClick = {
                    sendControl(QuroMediaService.ACTION_SET_SHUFFLE) {
                        it.putExtra(QuroMediaService.EXTRA_SHUFFLE, !media.shuffle)
                    }
                }) {
                    Icon(
                        Icons.Filled.Shuffle,
                        "随机播放",
                        tint = if (media.shuffle) cs.primary else cs.onSurfaceVariant,
                    )
                }
                val speeds = listOf(0.5f, 1f, 1.5f, 2f)
                val nextSpeed = speeds.getOrElse(speeds.indexOf(media.speed).let { if (it < 0) 1 else (it + 1) % speeds.size }) { 1f }
                TextButton(onClick = {
                    sendControl(QuroMediaService.ACTION_SET_SPEED) {
                        it.putExtra(QuroMediaService.EXTRA_SPEED, nextSpeed)
                    }
                }) {
                    Text("${media.speed}x", color = cs.primary)
                }
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { showPicker = true },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Icon(Icons.Filled.MusicNote, null)
                Spacer(Modifier.width(8.dp))
                Text("选择音乐")
            }
        }
    }
}

private fun formatMusicMs(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val m = s / 60
    val sec = s % 60
    return String.format("%d:%02d", m, sec)
}
