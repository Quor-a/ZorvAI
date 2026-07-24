package com.ai.assistance.quro.core.tools

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.util.Log
import com.ai.assistance.quro.core.QuroPersonaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 云端 TTS 统一派遣 + 播放层。
 *
 * 不再绑定单一服务商：根据 [QuroTtsProviderPrefs] 选中的服务商分发到 [QuroTtsClients]，
 * 返回的音频按格式播放（mp3→MediaPlayer；wav/pcm16→AudioTrack）。
 */
object QuroCloudTts {
    private const val TAG = "QuroCloudTts"

    /** 合成并播放（按当前选中的服务商；若激活人格含结构化语音组合则优先采用）。 */
    suspend fun play(ctx: Context, text: String) {
        // B1：读取激活人格的结构化语音组合（若有），作为默认 provider/voice/emotion/speed
        val persona = QuroPersonaRepository(ctx).getActive()
        val vp = persona.voiceProfile

        // 决定有效服务商：人格指定且已配置才切换，否则沿用全局选中服务商（避免未配置时硬切导致失败）
        val globalProviderId = QuroTtsProviderPrefs.getProvider(ctx)
        val useProviderId = if (vp != null && vp.providerId.isNotBlank()
            && QuroTtsProviderPrefs.isConfiguredFor(ctx, vp.providerId)) vp.providerId else globalProviderId
        val def = QuroTtsProviders.byId(useProviderId) ?: throw Exception("未知 TTS 服务商：$useProviderId")
        if (!QuroTtsProviderPrefs.isConfiguredFor(ctx, useProviderId)) {
            throw Exception("未配置「${def.name}」：请先在「语音服务」设置中填写所需参数（API Key 等）。")
        }
        val cfg = QuroTtsProviderPrefs.getConfig(ctx, useProviderId)
        // 仅对会使用风格指令的服务商调用 LLM 推导自然语言风格（避免 Edge 等免费服务无谓消耗）
        val useStyle = def.kind == QuroTtsProviderKind.MIMO || def.kind == QuroTtsProviderKind.OPENAI_COMPAT
        val style = if (useStyle) QuroSpeechStyleDeriver.deriveStyle(ctx, text) else ""
        val baseUrl = (cfg.fields["base_url"] ?: "").ifBlank { def.defaultBaseUrl }
        // 人格语音组合覆盖：音色 / 情绪(styleTags) / 语速；未填项回落全局配置
        val voice = vp?.voiceId?.takeIf { it.isNotBlank() } ?: cfg.voice
        val styleTags = if (vp != null && vp.emotionEnabled && vp.emotionTags.isNotEmpty()) vp.emotionTags else cfg.styleTags
        val speed = vp?.speed ?: 1.0f
        val req = QuroTtsSynthRequest(
            ctx = ctx,
            text = text,
            voice = voice,
            styleTags = styleTags,
            customStyleTags = cfg.customStyleTags,
            styleNL = style,
            format = cfg.format.ifBlank { def.defaultFormat },
            model = cfg.model.ifBlank { def.defaultModel },
            fields = cfg.fields,
            baseUrl = baseUrl,
            def = def,
            customVoices = cfg.customVoices,
            speed = speed,
        )
        Log.i(TAG, ">>> play provider=${def.id} voice=$voice fmt=${req.format} persona=${persona.name} vp=$vp")
        val (bytes, fmt) = QuroTtsClients.get(def.kind).synth(req)
        playAudioBytes(ctx, bytes, fmt)
    }

    /** 播放音频字节：mp3 用 MediaPlayer；wav/pcm16 用 AudioTrack（24000Hz 单声道，与本地引擎回退一致）。 */
    fun playAudioBytes(ctx: Context, bytes: ByteArray, format: String) {
        when (format) {
            "mp3" -> playMp3(ctx, bytes)
            else -> {
                val (pcm, sampleRate, channels) = if (format == "wav") parseWav(bytes) else Triple(bytes, 24000, 1)
                playPcm(pcm, sampleRate, channels)
            }
        }
    }

    /** 用 AudioTrack 播放 PCM/WAV 裸流（24kHz 单声道）。 */
    private fun playPcm(pcm: ByteArray, sampleRate: Int, channels: Int) {
        val chMask = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, chMask, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack(
            AudioManager.STREAM_MUSIC, sampleRate, chMask, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, pcm.size), AudioTrack.MODE_STREAM,
        )
        track.play()
        var offset = 0
        while (offset < pcm.size) {
            val wrote = track.write(pcm, offset, pcm.size - offset)
            if (wrote <= 0) break
            offset += wrote
        }
        val bytesPerFrame = channels * 2
        val totalFrames = if (bytesPerFrame > 0) pcm.size / bytesPerFrame else 0
        var guard = 0
        while (guard < 2400) { // 最长等待 120s，兜底防止极端情况下死等
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) break
            if (totalFrames > 0 && track.playbackHeadPosition >= totalFrames) break
            Thread.sleep(50); guard++
        }
        track.stop()
        track.release()
    }

    /** 用 MediaPlayer 播放 mp3 字节（写入临时文件）。 */
    private fun playMp3(ctx: Context, bytes: ByteArray) {
        val file = File(ctx.cacheDir, "quro_tts_${System.currentTimeMillis()}.mp3")
        file.writeBytes(bytes)
        val mp = MediaPlayer()
        try {
            mp.setDataSource(file.absolutePath)
            mp.prepare()
            mp.start()
            while (mp.isPlaying) Thread.sleep(50)
        } finally {
            runCatching { if (mp.isPlaying) mp.stop() }
            runCatching { mp.release() }
            runCatching { file.delete() }
        }
    }

    /** 解析 WAV，返回 (PCM 数据, 采样率, 声道数)。不支持则回退 24kHz 单声道。 */
    fun parseWav(bytes: ByteArray): Triple<ByteArray, Int, Int> {
        return try {
            require(bytes.size > 44 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF")
            val sampleRate = (bytes[24].toInt() and 0xFF) or
                ((bytes[25].toInt() and 0xFF) shl 8) or
                ((bytes[26].toInt() and 0xFF) shl 16) or
                ((bytes[27].toInt() and 0xFF) shl 24)
            val channels = (bytes[22].toInt() and 0xFF) or ((bytes[23].toInt() and 0xFF) shl 8)
            var pos = 12
            var dataStart = -1
            var dataLen = 0
            while (pos + 8 <= bytes.size) {
                val ck = bytes.copyOfRange(pos, pos + 4).toString(Charsets.US_ASCII)
                val ckLen = (bytes[pos + 4].toInt() and 0xFF) or
                    ((bytes[pos + 5].toInt() and 0xFF) shl 8) or
                    ((bytes[pos + 6].toInt() and 0xFF) shl 16) or
                    ((bytes[pos + 7].toInt() and 0xFF) shl 24)
                if (ck == "data") { dataStart = pos + 8; dataLen = ckLen; break }
                pos += 8 + ckLen + (ckLen and 1)
            }
            if (dataStart < 0) throw Exception("no data chunk")
            val pcm = bytes.copyOfRange(dataStart, minOf(dataStart + dataLen, bytes.size))
            Triple(pcm, sampleRate, channels)
        } catch (e: Exception) {
            Log.w(TAG, "parseWav 失败，按 pcm16 24000 处理: ${e.message}")
            Triple(bytes, 24000, 1)
        }
    }
}
