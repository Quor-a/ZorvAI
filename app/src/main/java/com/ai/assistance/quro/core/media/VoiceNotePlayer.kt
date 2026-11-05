package com.ai.assistance.quro.core.media

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import java.io.File

/**
 * 语音条的本地播放器。
 *
 * ## 与 [com.ai.assistance.quro.core.tools.QuroTtsHolder] 的分工
 *
 * TtsHolder 是「实时朗读」：文本 → TTS 引擎 → 即时出声，不留文件、不暂停、不切听筒。
 * 语音条是「可回放的消息」：文本 → 合成 → 落盘音频文件 → 点播放出声，
 * 支持暂停 / 续播 / 听筒扬声器切换 / 删除。两者不能混用——语音条的音频必须落盘，
 * 否则「转文字后想再听一遍」无从谈起。
 *
 * 播放用 [MediaPlayer]（mp3 / wav 都能放），听筒切换靠 [AudioManager]：
 * 扬声器走 STREAM_MUSIC，听筒走 MODE_IN_COMMUNICATION + setSpeakerphoneOn(false)。
 */
class VoiceNotePlayer(private val context: Context) {

    private var player: MediaPlayer? = null
    private var currentFile: File? = null

    @Volatile
    var listener: ((State) -> Unit)? = null

    enum class State { IDLE, PLAYING, PAUSED, COMPLETED, ERROR }

    @Volatile
    var state: State = State.IDLE
        private set

    @Volatile
    var useEarpiece: Boolean = false
        private set

    private val audioManager: AudioManager
        get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** 播放某个音频文件（不存在则静默失败）。 */
    fun play(file: File) {
        if (!file.exists()) {
            setState(State.ERROR); return
        }
        // 同一文件且正在播 → 暂停/续播；不同文件 → 换源。
        if (currentFile == file && state == State.PLAYING) {
            pause(); return
        }
        if (currentFile == file && state == State.PAUSED) {
            resume(); return
        }
        release()
        try {
            currentFile = file
            applyRouting()
            val mp = MediaPlayer()
            mp.setDataSource(file.absolutePath)
            mp.setOnPreparedListener { mp.start(); setState(State.PLAYING) }
            mp.setOnCompletionListener { setState(State.COMPLETED) }
            mp.setOnErrorListener { _, _, _ -> setState(State.ERROR); true }
            mp.prepareAsync()
            player = mp
        } catch (e: Exception) {
            setState(State.ERROR)
        }
    }

    fun pause() {
        if (state != State.PLAYING) return
        runCatching { player?.pause() }
        setState(State.PAUSED)
    }

    fun resume() {
        if (state != State.PAUSED) return
        applyRouting()
        runCatching { player?.start() }
        setState(State.PLAYING)
    }

    fun stop() {
        release()
        setState(State.IDLE)
    }

    /** 切换听筒 / 扬声器；播到一半切换立即生效（重新路由音频流）。 */
    fun toggleEarpiece(): Boolean {
        useEarpiece = !useEarpiece
        applyRouting()
        // 正在播时，需要重建播放器让路由生效（MediaPlayer 音频流属性在 prepare 时定死）。
        val wasPlaying = state == State.PLAYING
        val file = currentFile
        if (wasPlaying && file != null) {
            val pos = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)
            release()
            currentFile = file
            applyRouting()
            try {
                val mp = MediaPlayer()
                mp.setDataSource(file.absolutePath)
                mp.setOnPreparedListener { mp.seekTo(pos); mp.start(); setState(State.PLAYING) }
                mp.setOnCompletionListener { setState(State.COMPLETED) }
                mp.setOnErrorListener { _, _, _ -> setState(State.ERROR); true }
                mp.prepareAsync()
                player = mp
            } catch (e: Exception) {
                setState(State.ERROR)
            }
        }
        return useEarpiece
    }

    private fun applyRouting() {
        if (useEarpiece) {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            runCatching { audioManager.isSpeakerphoneOn = false }
        } else {
            audioManager.mode = AudioManager.MODE_NORMAL
            runCatching { audioManager.isSpeakerphoneOn = false }
        }
    }

    private fun setState(s: State) {
        state = s
        listener?.invoke(s)
    }

    fun release() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        currentFile = null
    }

    companion object {
        /**
         * 把云端 TTS 合成的字节落盘为可回放文件。
         *
         * mp3 直接写；wav 直接写；裸 pcm16 补一个 WAV 头（44 字节）再写，
         * 这样 [MediaPlayer] 都能识别。文件名用内容哈希前 8 位，重复合成幂等。
         */
        fun persistSynth(
            ctx: Context,
            bytes: ByteArray,
            format: String,
            pcmSampleRate: Int,
            contentHash: String,
        ): File {
            val dir = File(ctx.filesDir, "voice_notes").apply { mkdirs() }
            val ext = if (format == "mp3") "mp3" else "wav"
            val file = File(dir, "vn_${contentHash}.$ext")
            if (file.exists() && file.length() > 0) return file
            val data = when (format) {
                "mp3" -> bytes
                "wav" -> bytes
                else -> pcmToWav(bytes, pcmSampleRate)
            }
            file.writeBytes(data)
            return file
        }

        /** 裸 PCM16（单声道）补 WAV 头。 */
        fun pcmToWav(pcm: ByteArray, sampleRate: Int): ByteArray {
            val header = ByteArray(44)
            val total = pcm.size + 36
            val byteRate = sampleRate * 2 // 16bit 单声道
            header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
            writeLe(header, 4, total)
            header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
            header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
            writeLe(header, 16, 16)          // fmt chunk size
            header[20] = 1; header[21] = 0   // PCM
            header[22] = 1; header[23] = 0   // 单声道
            writeLe(header, 24, sampleRate)
            writeLe(header, 28, byteRate)
            header[32] = 2; header[33] = 0   // block align
            header[34] = 16; header[35] = 0  // bits per sample
            header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
            writeLe(header, 40, pcm.size)
            return header + pcm
        }

        private fun writeLe(buf: ByteArray, offset: Int, value: Int) {
            buf[offset] = (value and 0xff).toByte()
            buf[offset + 1] = ((value shr 8) and 0xff).toByte()
            buf[offset + 2] = ((value shr 16) and 0xff).toByte()
            buf[offset + 3] = ((value shr 24) and 0xff).toByte()
        }
    }
}

/**
 * 语音条音频的落盘注册表。
 *
 * 桥接「core 工具层（speak 落盘）」与「UI 聚合层（把 speak 识别成语音条）」：
 * speak 工具在 core 层合成并落盘后，把「文本 → 音频文件路径」写进这里；
 * UI 聚合层遇到 speak 工具调用时，用同一文本的哈希反查路径，构造 [com.ai.assistance.quro.ui.data.VoiceNoteUi]。
 *
 * 用文本哈希做 key（幂等）：同一段文本重复合成命中同一文件，不重复占用磁盘。
 */
object VoiceNoteStore {
    private val map = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun keyOf(text: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(text.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
    }

    fun put(text: String, path: String) {
        map[keyOf(text)] = path
    }

    fun get(text: String): String? = map[keyOf(text)]
}
