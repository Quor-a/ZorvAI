package com.ai.assistance.quro.ui.chat

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.R
import com.ai.assistance.quro.core.media.VoiceNotePlayer
import com.ai.assistance.quro.core.tools.QuroCloudTts
import com.ai.assistance.quro.ui.data.VoiceNoteUi
import com.ai.assistance.quro.util.qstr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 语音条气泡：AI 主动发的一条可回放语音消息。
 *
 * 形态：
 *  - 播放 / 暂停；
 *  - 转文字 / 收起文字；
 *  - 听筒 / 扬声器切换；
 *  - 复制文字 / 删除（删除由外层消息菜单统一处理，这里只提供播放与文字）。
 */
@Composable
fun VoiceNoteBubble(
    voiceNote: VoiceNoteUi,
    modifier: Modifier = Modifier,
    onCopyText: () -> Unit = {},
    onDelete: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val player = remember { VoiceNotePlayer(ctx) }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(VoiceNotePlayer.State.IDLE) }
    var earpiece by remember { mutableStateOf(false) }
    var showTranscript by remember { mutableStateOf(true) }
    var audioPath by remember { mutableStateOf(voiceNote.audioPath) }

    DisposableEffect(Unit) {
        player.listener = { state = it }
        onDispose { player.release() }
    }

    fun ensureAudio(then: (String) -> Unit) {
        if (audioPath.isNotBlank() && File(audioPath).exists()) { then(audioPath); return }
        // 懒合成：speak 落盘还没完成（或落盘失败）时，现场合成一份再播。
        scope.launch {
            try {
                val synth = withContext(Dispatchers.IO) { QuroCloudTts.synthBytes(ctx, voiceNote.text) }
                val f = VoiceNotePlayer.persistSynth(
                    ctx, synth.bytes, synth.format, synth.pcmSampleRate,
                    com.ai.assistance.quro.core.media.VoiceNoteStore.keyOf(voiceNote.text),
                )
                audioPath = f.absolutePath
                then(f.absolutePath)
            } catch (_: Exception) {
                // 合成失败：静默，播放按钮无响应（提示由外层负责）。
            }
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(cs.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 播放 / 暂停
        IconButton(
            onClick = {
                ensureAudio { path ->
                    when (state) {
                        VoiceNotePlayer.State.PLAYING -> player.pause()
                        VoiceNotePlayer.State.PAUSED -> player.resume()
                        else -> player.play(File(path))
                    }
                }
            },
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                if (state == VoiceNotePlayer.State.PLAYING) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (state == VoiceNotePlayer.State.PLAYING)
                    qstr(R.string.qk_04526) else qstr(R.string.qk_04527),
                tint = cs.primary,
            )
        }
        Spacer(Modifier.width(6.dp))
        // 转文字 / 收起
        Column(Modifier.weight(1f)) {
            if (showTranscript) {
                Text(
                    voiceNote.text,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = cs.onSurface,
                    maxLines = 4,
                )
            } else {
                Text(
                    qstr(R.string.qk_04528),
                    fontSize = 12.sp,
                    color = cs.onSurface.copy(alpha = 0.55f),
                )
            }
        }
        // 听筒 / 扬声器切换
        IconButton(onClick = { earpiece = player.toggleEarpiece() }, modifier = Modifier.size(32.dp)) {
            Text(
                if (earpiece) qstr(R.string.qk_04529) else qstr(R.string.qk_04530),
                fontSize = 11.sp,
                color = cs.onSurface.copy(alpha = 0.7f),
            )
        }
        // 转文字开关
        IconButton(onClick = { showTranscript = !showTranscript }, modifier = Modifier.size(32.dp)) {
            Text(
                qstr(R.string.qk_04531),
                fontSize = 11.sp,
                color = cs.onSurface.copy(alpha = 0.7f),
            )
        }
        // 🔴 复制文字 / 删除语音条（2026-11-05）
        IconButton(onClick = {
            onCopyText()
            android.widget.Toast.makeText(ctx, "已复制语音文字", android.widget.Toast.LENGTH_SHORT).show()
        }, modifier = Modifier.size(32.dp)) {
            Text("复制", fontSize = 11.sp, color = cs.onSurface.copy(alpha = 0.7f))
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Text("删除", fontSize = 11.sp, color = cs.error.copy(alpha = 0.8f))
        }
    }
}
