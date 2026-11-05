package com.ai.assistance.quro.ui

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 视频切片：把视频抽成 N 帧，作为多张图片喂给视觉模型。
 */

data class VideoSliceResult(
    val uri: String,
    val frameCount: Int,
    val intervalMs: Long,
)

object VideoSliceDefaults {
    fun defaultFrameCount(durationMs: Long): Int {
        val seconds = durationMs / 1000
        return when {
            seconds < 10 -> 3
            seconds < 30 -> 5
            seconds < 60 -> 8
            else -> maxOf(2, minOf(20, (seconds / 5).toInt()))
        }
    }
}

@Composable
fun VideoSliceDialog(
    videoUri: String,
    durationMs: Long,
    onConfirm: (VideoSliceResult) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val seconds = durationMs / 1000
    val defaultFrames = remember(durationMs) { VideoSliceDefaults.defaultFrameCount(durationMs) }

    var frameCount by remember { mutableIntStateOf(defaultFrames) }
    var thumbnail by remember(videoUri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(videoUri) {
        thumbnail = withContext(Dispatchers.IO) {
            try {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, Uri.parse(videoUri))
                    val bitmap = retriever.frameAtTime
                    bitmap?.let {
                        Bitmap.createScaledBitmap(
                            it, 512, (512f * it.height / it.width).roundToInt(), true,
                        ).also { scaled -> if (scaled !== bitmap) bitmap.recycle() }
                    }
                } finally {
                    retriever.release()
                }
            } catch (_: Exception) { null }
        }
    }
    DisposableEffect(videoUri) {
        onDispose { thumbnail?.let { if (!it.isRecycled) it.recycle() } }
    }

    val effectiveIntervalMs = if (frameCount > 1) durationMs / frameCount else 0L
    val durationFormatted = remember(durationMs) {
        val m = seconds / 60
        val s = seconds % 60
        "${m}:${s.toString().padStart(2, '0')}"
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("视频切片", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "时长 $durationFormatted · 将抽取 $frameCount 帧发给 AI 理解",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(12.dp))

                thumbnail?.let { bmp ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
                    ) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "视频预览",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }

                Text(
                    "帧数：$frameCount",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Slider(
                    value = frameCount.toFloat(),
                    onValueChange = { frameCount = it.roundToInt().coerceIn(2, 20) },
                    valueRange = 2f..20f,
                    steps = 17,
                )
                val betweenLabel = (effectiveIntervalMs / 1000f).let {
                    if (it < 1) "${(it * 1000).roundToInt()}ms" else "${it.roundToInt()}s"
                }
                Text(
                    "帧间隔约 $betweenLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(12.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss, shape = RoundedCornerShape(50)) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onConfirm(VideoSliceResult(videoUri, frameCount, effectiveIntervalMs)) },
                        shape = RoundedCornerShape(50),
                    ) {
                        Text("抽取 $frameCount 帧")
                    }
                }
            }
        }
    }
}
