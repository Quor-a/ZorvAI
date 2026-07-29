package com.ai.assistance.quro.core.tools

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.util.Log
import java.io.ByteArrayOutputStream
import com.ai.assistance.quro.core.QuroPersonaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 云端 TTS 统一派遣 + 播放层。
 *
 * 不再绑定单一服务商：根据 [QuroTtsProviderPrefs] 选中的服务商分发到 [QuroTtsClients]，
 * 返回的音频按格式播放（mp3→MediaPlayer；wav/pcm16→AudioTrack）。
 */
object QuroCloudTts {
    private const val TAG = "QuroCloudTts"

    /** 播放独占令牌：每次 play 自增；若某次播放的 token 不再是 activeToken，说明已被新的 play 取代，
     *  立即停掉自己释放音频设备，避免两段音频同时播（"同时播"）。 */
    @Volatile private var activeToken = 0L
    @Volatile private var activeMp: MediaPlayer? = null
    @Volatile private var activeTrack: AudioTrack? = null

    /** 中止当前所有云 TTS 播放，交由新的 play 取代，防止多段音频同时播。 */
    fun abortAll() {
        activeToken++
        runCatching { activeMp?.stop() }
        runCatching { activeMp?.release() }
        activeMp = null
        runCatching { activeTrack?.stop() }
        runCatching { activeTrack?.release() }
        activeTrack = null
    }

    /** 解析有效文本：小米 MiMo 保留情绪括号标记做真情感合成；其余服务商剥离，避免被念成字面。 */
    private fun resolveEffectiveText(ctx: Context, rawText: String): String {
        val def = QuroTtsProviders.byId(QuroTtsProviderPrefs.getProvider(ctx))
        return if (def?.kind == QuroTtsProviderKind.MIMO) rawText else QuroVoiceStyle.strip(rawText)
    }

    /**
     * 构建合成请求（统一供 [play] 与 [synthBytes] 复用）。
     * @param voiceOverride 语色路由传入的逐段音色 id；为空则回落人格/全局音色。
     * @param streaming 是否尝试流式（[synthBytes] 强制 false 以拿到完整字节供预取）。
     */
    private suspend fun buildRequest(ctx: Context, effectiveText: String, voiceOverride: String?, streaming: Boolean): QuroTtsSynthRequest {
        val persona = QuroPersonaRepository(ctx).getActive()
        val vp = persona.voiceProfile
        val globalProviderId = QuroTtsProviderPrefs.getProvider(ctx)
        val def = QuroTtsProviders.byId(globalProviderId) ?: throw Exception("未知 TTS 服务商：$globalProviderId")
        if (!QuroTtsProviderPrefs.isConfiguredFor(ctx, globalProviderId)) {
            throw Exception("未配置「${def.name}」：请先在「语音服务」设置中填写所需参数（API Key 等）。")
        }
        val cfg = QuroTtsProviderPrefs.getConfig(ctx, globalProviderId)
        val useStyle = def.kind == QuroTtsProviderKind.MIMO || def.kind == QuroTtsProviderKind.OPENAI_COMPAT
        val style = if (useStyle) QuroSpeechStyleDeriver.deriveStyle(ctx, effectiveText) else ""
        val baseUrl = (cfg.fields["base_url"] ?: "").ifBlank { def.defaultBaseUrl }
        val voiceCompatible = vp == null || vp.providerId.isBlank() || vp.providerId == globalProviderId
        val personaVoice = if (voiceCompatible && vp?.voiceId?.isNotBlank() == true) vp.voiceId else cfg.voice
        val voice = voiceOverride?.takeIf { it.isNotBlank() } ?: personaVoice
        val styleTags = if (vp != null && vp.emotionEnabled && vp.emotionTags.isNotEmpty()) vp.emotionTags else cfg.styleTags
        val speed = vp?.speed ?: 1.0f
        val streamableKind = def.kind == QuroTtsProviderKind.EDGE_TTS
            || def.kind == QuroTtsProviderKind.IFLYTEK
            || def.kind == QuroTtsProviderKind.MIMO
            || def.kind == QuroTtsProviderKind.OPENAI_COMPAT
            || def.kind == QuroTtsProviderKind.MINIMAX
        val wantStream = streaming && streamableKind
        val streamSr = when (def.kind) {
            QuroTtsProviderKind.IFLYTEK -> 16000
            QuroTtsProviderKind.TENCENT -> 16000
            else -> 24000
        }
        val effectiveFormat = if (wantStream) {
            when (def.kind) {
                QuroTtsProviderKind.EDGE_TTS -> "wav"
                else -> "pcm16"
            }
        } else cfg.format
        return QuroTtsSynthRequest(
            ctx = ctx,
            text = effectiveText,
            voice = voice,
            styleTags = styleTags,
            customStyleTags = cfg.customStyleTags,
            styleNL = style,
            format = effectiveFormat.ifBlank { def.defaultFormat },
            model = cfg.model.ifBlank { def.defaultModel },
            fields = cfg.fields,
            baseUrl = baseUrl,
            def = def,
            customVoices = cfg.customVoices,
            speed = speed,
            streaming = wantStream,
        )
    }

    /** 合成并播放（按用户在「云模型配置」中全局选中的服务商；激活人格仅作为兼容的软偏好叠加层）。 */
    suspend fun play(ctx: Context, text: String, voiceOverride: String? = null) {
        val myToken = ++activeToken
        val effectiveText = resolveEffectiveText(ctx, text)
        val req = buildRequest(ctx, effectiveText, voiceOverride, streaming = true)
        val def = req.def
        val cfg = QuroTtsProviderPrefs.getConfig(ctx, def.id)
        val streamSr = when (def.kind) {
            QuroTtsProviderKind.IFLYTEK -> 16000
            QuroTtsProviderKind.TENCENT -> 16000
            else -> 24000
        }
        Log.i(TAG, ">>> play provider=${def.id} voice=${req.voice} fmt=${req.format} streaming=${req.streaming}")
        if (req.streaming) {
            val player = StreamingPcmPlayer(streamSr)
            var emitted = false
            try {
                Log.i(TAG, ">>> 流式播放开始 provider=${def.id}")
                QuroTtsClients.get(def.kind).synth(req) { chunk, fmt ->
                    // token 已变说明被新的 play 取代：abortAll() 已释放旧 track，这里只需停止继续喂数据，
                    // 切勿在此非挂起回调里调用挂起版 player.finish()（会编译报错且没必要）。
                    if (myToken != activeToken) { return@synth }
                    emitted = true
                    player.accept(chunk, fmt)
                }
                Log.i(TAG, ">>> 流式播放正常结束 provider=${def.id}")
            } catch (e: Exception) {
                Log.w(TAG, "流式合成异常 provider=${def.id}: ${e.message}")
                // 已播过部分音频 → 不回退（避免重复/错位），直接抛出让上层处理
                if (emitted) throw e
                // 流式握手/建连失败（如 WS 端点/签名与当前实现不符）→ 回退整段 REST 合成，保证出声
                Log.w(TAG, "流式合成失败，回退整段 REST: ${e.message}")
                try {
                    val buf = ByteArrayOutputStream()
                    var fmt = req.format
                    val restReq = req.copy(streaming = false, format = cfg.format.ifBlank { def.defaultFormat })
                    QuroTtsClients.get(def.kind).synth(restReq) { chunk, f -> buf.write(chunk); fmt = f }
                    if (buf.size() == 0) throw Exception("${def.name} 返回音频为空")
                    playAudioBytes(ctx, buf.toByteArray(), fmt)
                    return@play
                } catch (e2: Exception) {
                    Log.e(TAG, "REST 回退也失败: ${e2.message}")
                    throw e2
                }
            } finally {
                player.finish()
            }
        } else {
            val buf = ByteArrayOutputStream()
            var fmt = req.format
            QuroTtsClients.get(def.kind).synth(req) { chunk, f -> buf.write(chunk); fmt = f }
            if (buf.size() == 0) throw Exception("${def.name} 返回音频为空")
            if (myToken != activeToken) return@play
            playAudioBytes(ctx, buf.toByteArray(), fmt, myToken)
        }
    }

    /** 仅合成不播放（供语色路由「边播边合成」预取下一段音频字节）。非流式整段合成，返回 (字节, 格式)。 */
    suspend fun synthBytes(ctx: Context, text: String, voiceOverride: String? = null): Pair<ByteArray, String> = withContext(Dispatchers.IO) {
        val effectiveText = resolveEffectiveText(ctx, text)
        val req = buildRequest(ctx, effectiveText, voiceOverride, streaming = false)
        val buf = ByteArrayOutputStream()
        var fmt = req.format
        QuroTtsClients.get(req.def.kind).synth(req) { chunk, f -> buf.write(chunk); fmt = f }
        if (buf.size() == 0) throw Exception("${req.def.name} 返回音频为空")
        buf.toByteArray() to fmt
    }

    /** 播放已合成的音频字节（带独占令牌，防止多段同时播）。 */
    suspend fun playBytes(ctx: Context, bytes: ByteArray, format: String) {
        val myToken = ++activeToken
        if (bytes.isEmpty()) return
        if (myToken != activeToken) return
        playAudioBytes(ctx, bytes, format, myToken)
    }

    /** 播放音频字节（异步，不阻塞调用线程）：mp3 用 MediaPlayer；wav/pcm16 用 AudioTrack（24kHz 单声道）。 */
    suspend fun playAudioBytes(ctx: Context, bytes: ByteArray, format: String, token: Long = 0L) {
        when (format) {
            "mp3" -> playMp3(ctx, bytes, token)
            else -> {
                val (pcm, sampleRate, channels) = if (format == "wav") parseWav(bytes) else Triple(bytes, 24000, 1)
                playPcm(pcm, sampleRate, channels, token)
            }
        }
    }

    /** 用 AudioTrack 异步播放 PCM/WAV 裸流（24kHz 单声道），用协程 delay 替代 Thread.sleep。 */
    private suspend fun playPcm(pcm: ByteArray, sampleRate: Int, channels: Int, token: Long = 0L) = withContext(Dispatchers.IO) {
        val chMask = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, chMask, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack(
            AudioManager.STREAM_MUSIC, sampleRate, chMask, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, pcm.size), AudioTrack.MODE_STREAM,
        )
        activeTrack = track
        track.play()
        var offset = 0
        while (offset < pcm.size) {
            val wrote = track.write(pcm, offset, pcm.size - offset)
            if (wrote <= 0) break
            offset += wrote
        }
        val bytesPerFrame = channels * 2
        val totalFrames = if (bytesPerFrame > 0) pcm.size / bytesPerFrame else 0
        // ★ v312 修复 ANR：用 delay(50) 替代 Thread.sleep(50)，不阻塞线程
        var guard = 0
        while (guard < 2400) { // 最长等待 120s，兜底防止极端情况下死等
            if (token != activeToken) { runCatching { track.stop() }; runCatching { track.release() }; if (activeTrack === track) activeTrack = null; return@withContext }
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) break
            if (totalFrames > 0 && track.playbackHeadPosition >= totalFrames) break
            delay(50); guard++
        }
        if (token != activeToken) { runCatching { track.stop() }; runCatching { track.release() }; if (activeTrack === track) activeTrack = null; return@withContext }
        track.stop()
        track.release()
        if (activeTrack === track) activeTrack = null
    }

    /** 用 MediaPlayer 异步播放 mp3 字节（写入临时文件），用 setOnCompletionListener 回调替代 Thread.sleep 忙等。 */
    private suspend fun playMp3(ctx: Context, bytes: ByteArray, token: Long = 0L) = suspendCancellableCoroutine { cont ->
        val file = File(ctx.cacheDir, "quro_tts_${System.currentTimeMillis()}.mp3")
        file.writeBytes(bytes)
        val mp = MediaPlayer()
        try {
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener {
                runCatching { mp.release() }
                runCatching { file.delete() }
                if (activeMp === mp) activeMp = null
                if (cont.isActive) cont.resume(Unit)
            }
            mp.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "playMp3 onError: what=$what extra=$extra")
                runCatching { mp.release() }
                runCatching { file.delete() }
                if (activeMp === mp) activeMp = null
                if (cont.isActive) cont.resumeWithException(Exception("MediaPlayer error $what/$extra"))
                true
            }
            mp.prepare()
            activeMp = mp
            if (token != activeToken) {
                // 已被新的 play 取代：不发声，直接释放并结束，避免"同时播"
                runCatching { mp.release() }
                runCatching { file.delete() }
                if (activeMp === mp) activeMp = null
                if (cont.isActive) cont.resume(Unit)
                return@suspendCancellableCoroutine
            }
            mp.start()
            cont.invokeOnCancellation {
                runCatching { if (mp.isPlaying) mp.stop() }
                runCatching { mp.release() }
                runCatching { file.delete() }
            }
        } catch (e: Exception) {
            runCatching { mp.release() }
            runCatching { file.delete() }
            if (cont.isActive) cont.resumeWithException(e)
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

    /**
     * 增量流式播放器：把逐块到达的裸 PCM / WAV 数据边收边喂给 AudioTrack，
     * 大幅降低首字延迟。WAV 首块含 44 字节头，解析出采样率/声道后建轨并写入剩余 PCM；
     * pcm16 按构造时指定的采样率（默认 24kHz；讯飞 16kHz）单声道直接建轨。
     * 仅用于流式路径（Edge→wav、讯飞/MiMo/OpenAI/MiniMax→pcm16）。
     */
    private class StreamingPcmPlayer(private val sampleRate: Int = 24000) {
        private var track: AudioTrack? = null
        private var wavBuf: ByteArrayOutputStream? = null
        private var wavParsed = false
        private var channels = 1
        private var totalBytes = 0

        fun accept(chunk: ByteArray, fmt: String) {
            if (fmt == "wav" && !wavParsed) {
                val buf = wavBuf ?: ByteArrayOutputStream().also { wavBuf = it }
                buf.write(chunk)
                val data = buf.toByteArray()
                if (data.size < 44) return
                val (pcm, sr, ch) = parseWav(data)
                initTrack(sr, ch)
                wavParsed = true
                wavBuf = null
                if (pcm.isNotEmpty()) writeChunk(pcm)
                return
            }
            if (track == null) {
                if (fmt == "wav") return // 不应到达：wav 头已在上面建好轨
                initTrack(sampleRate, 1)
            }
            writeChunk(chunk)
        }

        private fun writeChunk(chunk: ByteArray) {
            var off = 0
            while (off < chunk.size) {
                val w = track!!.write(chunk, off, chunk.size - off)
                if (w <= 0) break
                totalBytes += w
                off += w
            }
        }

        private fun initTrack(sampleRate: Int, ch: Int) {
            channels = ch
            val chMask = if (ch >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            val minBuf = AudioTrack.getMinBufferSize(sampleRate, chMask, AudioFormat.ENCODING_PCM_16BIT)
            track = AudioTrack(
                AudioManager.STREAM_MUSIC, sampleRate, chMask, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, 8192), AudioTrack.MODE_STREAM,
            )
            activeTrack = track
            track!!.play()
        }

        /** 异步等待播放完毕（用 delay 替代 Thread.sleep，不阻塞线程）。必须在协程内调用。 */
        suspend fun finish() {
            val t = track ?: return
            val bytesPerFrame = channels * 2
            val frames = if (bytesPerFrame > 0) totalBytes / bytesPerFrame else 0
            // ★ 容错：若本播放已被新 play 的 abortAll() 释放过 track，下面的 playState/stop 会抛异常，统一吞掉。
            runCatching {
                // ★ v312 修复 ANR：用 delay(50) 替代 Thread.sleep(50)
                var guard = 0
                while (guard < 400) { // 上限 20s，避免极端情况下长时间占用
                    if (t.playState != AudioTrack.PLAYSTATE_PLAYING) break
                    if (frames > 0 && t.playbackHeadPosition >= frames) break
                    delay(50); guard++
                }
                t.stop()
            }
            runCatching { t.release() }
            track = null
            if (activeTrack === t) activeTrack = null
        }
    }
}
