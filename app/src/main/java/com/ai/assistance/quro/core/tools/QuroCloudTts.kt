package com.ai.assistance.quro.core.tools

import android.content.Context
import android.media.AudioFormat
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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 云端 TTS 统一派遣 + 播放层。
 *
 * 不再绑定单一服务商：根据 [QuroTtsProviderPrefs] 选中的服务商分发到 [QuroTtsClients]，
 * 返回的音频按格式播放（mp3→MediaPlayer；wav/pcm16→AudioTrack）。
 *
 * ## C-2 修复要点（本轮）
 * 1. **采样率变化必须销毁重建 AudioTrack**：AudioTrack 的采样率在构造时固定且不可修改，
 *    换服务商/音色后采样率从 24k 变 16k（或反之）时若复用旧实例，会以错误速率播放
 *    → 变调 + 变速。[PcmSink] 在 (采样率, 声道) 变化时 release 旧实例再新建。
 * 2. **非流式 pcm16 不再硬编码 24000Hz**：此前 `else -> Triple(bytes, 24000, 1)` 让
 *    讯飞/腾讯（16kHz）的整段 REST 音频以 24kHz 播出，快 1.5 倍、音调明显偏高。
 *    现由 [providerPcmSampleRate] 按服务商推导并全链路透传。
 * 3. **缓冲区钳制 + 初始化校验**：此前 `maxOf(minBuf, pcm.size)` 会把整段音频（可达数 MB）
 *    当作 AudioTrack 共享内存缓冲申请，大文本下构造出 STATE_UNINITIALIZED，
 *    随后 `play()` 抛 IllegalStateException → 整段静默失败。
 * 4. **音频焦点**：播放期间持有焦点，结束归还（详见 [QuroAudioFocus]）。
 * 5. **并发保护**：[abortAll] 可能在任意线程 release 掉正在被写入的 AudioTrack，
 *    写入路径全部加令牌校验 + runCatching，消除 release 后继续 write 的原生崩溃风险。
 * 6. **AudioTrack.Builder + USAGE_ASSISTANT**：替换 API 21 起废弃的 STREAM_MUSIC 构造器。
 */
object QuroCloudTts {
    private const val TAG = "QuroCloudTts"

    /** AudioTrack 缓冲上限：再大也不会更流畅，只会增加申请失败概率与首字延迟。 */
    private const val MAX_TRACK_BUFFER_BYTES = 256 * 1024

    /** 播放独占令牌：每次 play 自增；若某次播放的 token 不再是 activeToken，说明已被新的 play 取代，
     *  立即停掉自己释放音频设备，避免两段音频同时播（"同时播"）。 */
    @Volatile private var activeToken = 0L
    @Volatile private var activeMp: MediaPlayer? = null
    @Volatile private var activeTrack: AudioTrack? = null

    /** 最近一次失败原因，供 TTS 降级链与语音自检页展示（此前失败只进 logcat，用户完全无感）。 */
    @Volatile var lastError: String = ""
        private set

    /** 合成结果：带上采样率，避免下游播放时再猜（猜错就变调）。 */
    data class SynthResult(val bytes: ByteArray, val format: String, val pcmSampleRate: Int) {
        override fun equals(other: Any?): Boolean =
            other is SynthResult && bytes.contentEquals(other.bytes) &&
                format == other.format && pcmSampleRate == other.pcmSampleRate

        override fun hashCode(): Int =
            (bytes.contentHashCode() * 31 + format.hashCode()) * 31 + pcmSampleRate
    }

    /**
     * 服务商裸 PCM 采样率（唯一真源）。
     *
     * 此前该 when 表在 [buildRequest] 与 play 里各抄了一份、且 [playAudioBytes] 又写死 24000，
     * 三处发散正是「换服务商后声音变调」的根因。
     */
    fun providerPcmSampleRate(kind: QuroTtsProviderKind): Int = when (kind) {
        QuroTtsProviderKind.IFLYTEK -> 16000
        QuroTtsProviderKind.TENCENT -> 16000
        else -> 24000
    }

    /** 中止当前所有云 TTS 播放，交由新的 play 取代，防止多段音频同时播。 */
    fun abortAll() {
        activeToken++
        runCatching { activeMp?.stop() }
        runCatching { activeMp?.release() }
        activeMp = null
        runCatching { activeTrack?.pause() }
        runCatching { activeTrack?.flush() }
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
    private suspend fun buildRequest(
        ctx: Context,
        effectiveText: String,
        voiceOverride: String?,
        streaming: Boolean,
    ): QuroTtsSynthRequest {
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
        val streamSr = providerPcmSampleRate(def.kind)
        Log.i(TAG, ">>> play provider=${def.id} voice=${req.voice} fmt=${req.format} sr=$streamSr streaming=${req.streaming}")
        QuroAudioFocus.acquire(ctx)
        try {
            if (req.streaming) {
                val player = PcmSink(myToken, streamSr)
                var emitted = false
                try {
                    QuroTtsClients.get(def.kind).synth(req) { chunk, fmt ->
                        // token 已变说明被新的 play 取代：abortAll() 已释放旧 track，这里只需停止继续喂数据，
                        // 切勿在此非挂起回调里调用挂起版 player.drain()（会编译报错且没必要）。
                        if (myToken != activeToken) return@synth
                        emitted = true
                        player.accept(chunk, fmt)
                    }
                    Log.i(TAG, ">>> 流式播放正常结束 provider=${def.id}")
                } catch (e: Exception) {
                    Log.w(TAG, "流式合成异常 provider=${def.id}: ${e.message}")
                    // 已播过部分音频 → 不回退（避免重复/错位），直接抛出让上层处理
                    if (emitted) { lastError = "流式合成中断：${e.message}"; throw e }
                    // 流式握手/建连失败（如 WS 端点/签名与当前实现不符）→ 回退整段 REST 合成，保证出声
                    Log.w(TAG, "流式合成失败，回退整段 REST: ${e.message}")
                    try {
                        val buf = ByteArrayOutputStream()
                        var fmt = req.format
                        val restReq = req.copy(streaming = false, format = cfg.format.ifBlank { def.defaultFormat })
                        QuroTtsClients.get(def.kind).synth(restReq) { chunk, f -> buf.write(chunk); fmt = f }
                        if (buf.size() == 0) throw Exception("${def.name} 返回音频为空")
                        // ★ 采样率按服务商推导，不再硬编码 24k：讯飞/腾讯为 16k，硬编码会变调
                        playAudioBytes(ctx, buf.toByteArray(), fmt, myToken, streamSr)
                        return@play
                    } catch (e2: Exception) {
                        Log.e(TAG, "REST 回退也失败: ${e2.message}")
                        lastError = "${def.name} 合成失败：${e2.message}"
                        throw e2
                    }
                } finally {
                    player.drainAndRelease()
                }
            } else {
                val buf = ByteArrayOutputStream()
                var fmt = req.format
                QuroTtsClients.get(def.kind).synth(req) { chunk, f -> buf.write(chunk); fmt = f }
                if (buf.size() == 0) {
                    lastError = "${def.name} 返回音频为空"
                    throw Exception(lastError)
                }
                if (myToken != activeToken) return@play
                playAudioBytes(ctx, buf.toByteArray(), fmt, myToken, streamSr)
            }
        } finally {
            QuroAudioFocus.release(ctx)
        }
    }

    /**
     * 仅合成不播放（供语色路由「边播边合成」预取下一段音频字节）。非流式整段合成。
     * 返回值带采样率，避免下游 [playBytes] 猜错导致变调。
     */
    suspend fun synthBytes(ctx: Context, text: String, voiceOverride: String? = null): SynthResult =
        withContext(Dispatchers.IO) {
            val effectiveText = resolveEffectiveText(ctx, text)
            val req = buildRequest(ctx, effectiveText, voiceOverride, streaming = false)
            val buf = ByteArrayOutputStream()
            var fmt = req.format
            QuroTtsClients.get(req.def.kind).synth(req) { chunk, f -> buf.write(chunk); fmt = f }
            if (buf.size() == 0) {
                lastError = "${req.def.name} 返回音频为空"
                throw Exception(lastError)
            }
            SynthResult(buf.toByteArray(), fmt, providerPcmSampleRate(req.def.kind))
        }

    /** 播放已合成的音频字节（带独占令牌，防止多段同时播）。 */
    suspend fun playBytes(ctx: Context, result: SynthResult) {
        if (result.bytes.isEmpty()) return
        val myToken = ++activeToken
        QuroAudioFocus.acquire(ctx)
        try {
            playAudioBytes(ctx, result.bytes, result.format, myToken, result.pcmSampleRate)
        } finally {
            QuroAudioFocus.release(ctx)
        }
    }

    /**
     * 播放音频字节：mp3 用 MediaPlayer；wav 解析头取真实采样率；裸 pcm16 用 [pcmSampleRate]。
     *
     * @param pcmSampleRate 裸 pcm16 的采样率。**必须由调用方按服务商传入**——此前写死 24000，
     *                      导致 16kHz 服务商（讯飞/腾讯）整段回退播放时明显变调变速。
     */
    suspend fun playAudioBytes(
        ctx: Context,
        bytes: ByteArray,
        format: String,
        token: Long = 0L,
        pcmSampleRate: Int = 24000,
    ) {
        when (format) {
            "mp3" -> playMp3(ctx, bytes, token)
            "wav" -> {
                val (pcm, sr, ch) = parseWav(bytes, pcmSampleRate)
                playPcm(pcm, sr, ch, token)
            }
            else -> playPcm(bytes, pcmSampleRate, 1, token)
        }
    }

    /** 用 AudioTrack 异步播放整段 PCM，用协程 delay 替代 Thread.sleep。 */
    private suspend fun playPcm(pcm: ByteArray, sampleRate: Int, channels: Int, token: Long = 0L) =
        withContext(Dispatchers.IO) {
            if (pcm.isEmpty()) return@withContext
            val sink = PcmSink(token, sampleRate)
            try {
                sink.acceptPcm(pcm, sampleRate, channels)
            } finally {
                sink.drainAndRelease()
            }
        }

    /** 用 MediaPlayer 异步播放 mp3 字节（写入临时文件），用 setOnCompletionListener 回调替代 Thread.sleep 忙等。 */
    private suspend fun playMp3(ctx: Context, bytes: ByteArray, token: Long = 0L) =
        suspendCancellableCoroutine { cont ->
            cleanupStaleMp3Cache(ctx)
            val file = File(ctx.cacheDir, "quro_tts_${System.nanoTime()}.mp3")
            file.writeBytes(bytes)
            val mp = MediaPlayer()
            try {
                // 与 AudioTrack / 系统 TTS 使用同一套语音属性，保证音频路由一致
                runCatching { mp.setAudioAttributes(QuroAudioFocus.SPEECH_ATTRIBUTES) }
                mp.setDataSource(file.absolutePath)
                mp.setOnCompletionListener {
                    runCatching { mp.release() }
                    runCatching { file.delete() }
                    if (activeMp === mp) activeMp = null
                    if (cont.isActive) cont.resume(Unit)
                }
                mp.setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "playMp3 onError: what=$what extra=$extra")
                    lastError = "音频解码失败（MediaPlayer $what/$extra）"
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
                lastError = "音频播放失败：${e.message}"
                runCatching { mp.release() }
                runCatching { file.delete() }
                if (cont.isActive) cont.resumeWithException(e)
            }
        }

    /**
     * 清理历史 mp3 临时文件。
     * 进程被杀 / 播放异常时 `quro_tts_*.mp3` 会残留在 cacheDir 里持续膨胀，此前无任何清理。
     */
    private fun cleanupStaleMp3Cache(ctx: Context) {
        runCatching {
            val cutoff = System.currentTimeMillis() - 10 * 60 * 1000L
            ctx.cacheDir.listFiles { f -> f.name.startsWith("quro_tts_") && f.name.endsWith(".mp3") }
                ?.forEach { f -> if (f.lastModified() < cutoff) f.delete() }
        }
    }

    /**
     * 解析 WAV，返回 (PCM 数据, 采样率, 声道数)。
     * @param fallbackSampleRate 解析失败时的兜底采样率（按服务商传入，不再写死 24000）。
     */
    @JvmOverloads
    fun parseWav(bytes: ByteArray, fallbackSampleRate: Int = 24000): Triple<ByteArray, Int, Int> {
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
            // 采样率字段异常（0 或离谱值）时兜底，避免 AudioTrack 构造失败导致整段静默
            val safeSr = if (sampleRate in 4000..192000) sampleRate else fallbackSampleRate
            val safeCh = if (channels in 1..2) channels else 1
            Triple(pcm, safeSr, safeCh)
        } catch (e: Exception) {
            Log.w(TAG, "parseWav 失败，按 pcm16 ${fallbackSampleRate}Hz 处理: ${e.message}")
            Triple(bytes, fallbackSampleRate, 1)
        }
    }

    /**
     * PCM 输出槽：持有**唯一** AudioTrack，并在 (采样率, 声道) 变化时**销毁重建**。
     *
     * ⚠️ 硬约束：AudioTrack 的采样率/声道在构造时固定、运行期不可修改。切换服务商或音色后
     * 采样率会变（24k ↔ 16k），复用旧实例必然变调变速。因此 [ensureTrack] 检测到参数变化时
     * 先 stop+release 旧实例再新建，绝不复用。
     *
     * 同时承担：
     *  - 边收边播（流式，降低首字延迟）；
     *  - 令牌校验：被新的 play 取代后立刻停止写入，杜绝两段同时出声；
     *  - 写入全程 runCatching：[abortAll] 可能在别的线程把 track release 掉，
     *    释放后继续 write 属未定义行为（原生崩溃），必须吞掉。
     */
    private class PcmSink(private val token: Long, private val defaultSampleRate: Int) {
        private var track: AudioTrack? = null
        private var wavBuf: ByteArrayOutputStream? = null
        private var wavParsed = false
        private var curSampleRate = 0
        private var curChannels = 0
        private var writtenBytes = 0L

        /** 流式分块入口：WAV 首块含 44 字节头，解析出真实采样率后建轨；pcm16 用构造时的采样率。 */
        fun accept(chunk: ByteArray, fmt: String) {
            if (chunk.isEmpty()) return
            if (fmt == "wav" && !wavParsed) {
                val buf = wavBuf ?: ByteArrayOutputStream().also { wavBuf = it }
                buf.write(chunk)
                val data = buf.toByteArray()
                if (data.size < 44) return
                val (pcm, sr, ch) = parseWav(data, defaultSampleRate)
                wavParsed = true
                wavBuf = null
                if (pcm.isNotEmpty()) acceptPcm(pcm, sr, ch) else ensureTrack(sr, ch)
                return
            }
            if (fmt == "wav") {
                // 头已解析，后续块是纯 PCM 续流，沿用已建轨参数
                acceptPcm(chunk, curSampleRate.takeIf { it > 0 } ?: defaultSampleRate, curChannels.coerceAtLeast(1))
                return
            }
            acceptPcm(chunk, defaultSampleRate, 1)
        }

        /** 按给定采样率/声道写入 PCM；参数与当前轨不一致则销毁重建。 */
        fun acceptPcm(pcm: ByteArray, sampleRate: Int, channels: Int) {
            if (pcm.isEmpty()) return
            if (token != activeToken) return
            val t = ensureTrack(sampleRate, channels) ?: return
            var off = 0
            while (off < pcm.size) {
                if (token != activeToken) return
                val w = runCatching { t.write(pcm, off, pcm.size - off) }.getOrDefault(-1)
                if (w <= 0) {
                    if (w < 0) Log.w(TAG, "AudioTrack.write 返回 $w，停止写入")
                    return
                }
                writtenBytes += w
                off += w
            }
        }

        /**
         * 取得可用 AudioTrack；(采样率, 声道) 变化时销毁旧实例重建。
         * @return null 表示建轨失败（参数不被设备支持等），调用方应放弃本段播放。
         */
        private fun ensureTrack(sampleRate: Int, channels: Int): AudioTrack? {
            val sr = if (sampleRate in 4000..192000) sampleRate else defaultSampleRate
            val ch = channels.coerceIn(1, 2)
            val existing = track
            if (existing != null && sr == curSampleRate && ch == curChannels) return existing
            if (existing != null) {
                // ★ 采样率/声道变了：AudioTrack 不支持运行期修改，必须销毁重建，禁止复用
                Log.i(TAG, "采样率变化 ${curSampleRate}Hz/${curChannels}ch → ${sr}Hz/${ch}ch，销毁并重建 AudioTrack")
                runCatching { existing.pause() }
                runCatching { existing.flush() }
                runCatching { existing.stop() }
                runCatching { existing.release() }
                if (activeTrack === existing) activeTrack = null
                track = null
                writtenBytes = 0
            }
            val built = buildTrack(sr, ch)
            if (built == null) {
                lastError = "音频输出初始化失败（${sr}Hz/${ch}ch 不被设备支持）"
                Log.e(TAG, lastError)
                return null
            }
            track = built
            curSampleRate = sr
            curChannels = ch
            activeTrack = built
            runCatching { built.play() }.onFailure {
                lastError = "音频输出启动失败：${it.message}"
                Log.e(TAG, lastError)
                runCatching { built.release() }
                track = null
                if (activeTrack === built) activeTrack = null
                return null
            }
            return built
        }

        /** 异步等待播放完毕并释放（用 delay 替代 Thread.sleep，不阻塞线程）。必须在协程内调用。 */
        suspend fun drainAndRelease() {
            val t = track ?: return
            val bytesPerFrame = curChannels * 2
            val frames = if (bytesPerFrame > 0) writtenBytes / bytesPerFrame else 0L
            // 容错：若本播放已被新 play 的 abortAll() 释放过 track，playState/stop 会抛异常，统一吞掉
            runCatching {
                var guard = 0
                // 上限按数据量推算（每帧 1/sr 秒）再放宽一倍，最少 5s、最多 180s，
                // 避免固定 20s 上限把长音频提前掐断，也避免死等。
                val estMs = if (curSampleRate > 0) frames * 1000L / curSampleRate else 0L
                val maxTicks = ((estMs * 2 + 5_000L) / 50L).coerceIn(100L, 3_600L).toInt()
                while (guard < maxTicks) {
                    if (token != activeToken) break
                    if (t.playState != AudioTrack.PLAYSTATE_PLAYING) break
                    if (frames > 0 && t.playbackHeadPosition.toLong() >= frames) break
                    delay(50); guard++
                }
                t.stop()
            }
            runCatching { t.release() }
            track = null
            if (activeTrack === t) activeTrack = null
        }
    }

    /**
     * 建轨：AudioTrack.Builder（替代 API 21 起废弃的 STREAM_MUSIC 构造器）+ 语音音频属性。
     * 缓冲区上限钳制在 [MAX_TRACK_BUFFER_BYTES]，并校验 STATE_INITIALIZED——
     * 此前用整段音频长度当缓冲，长文本下会静默拿到 UNINITIALIZED 实例，play() 直接抛异常。
     */
    private fun buildTrack(sampleRate: Int, channels: Int): AudioTrack? {
        val chMask = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuf = runCatching {
            AudioTrack.getMinBufferSize(sampleRate, chMask, AudioFormat.ENCODING_PCM_16BIT)
        }.getOrDefault(AudioTrack.ERROR_BAD_VALUE)
        if (minBuf <= 0) {
            Log.e(TAG, "getMinBufferSize 失败: sr=$sampleRate ch=$channels ret=$minBuf")
            return null
        }
        val bufBytes = (minBuf * 4).coerceIn(minBuf, MAX_TRACK_BUFFER_BYTES.coerceAtLeast(minBuf))
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(chMask)
            .build()
        val t = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(QuroAudioFocus.SPEECH_ATTRIBUTES)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(bufBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrElse {
            Log.e(TAG, "AudioTrack 构造失败 sr=$sampleRate ch=$channels: ${it.message}")
            return null
        }
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack 未初始化 sr=$sampleRate ch=$channels buf=$bufBytes")
            runCatching { t.release() }
            return null
        }
        return t
    }
}
