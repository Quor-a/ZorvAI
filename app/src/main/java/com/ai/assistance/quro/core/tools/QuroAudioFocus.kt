package com.ai.assistance.quro.core.tools

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log

/**
 * 语音播放音频焦点统一管理（C-2 修复项：此前全工程 0 行音频焦点代码）。
 *
 * 修复前的实际表现：
 *  - TTS 与音乐/导航/视频同时出声，互相盖住；
 *  - 来电、闹钟、其它助手抢焦点时 TTS 不停，用户听到两路声音叠加；
 *  - 播完不归还焦点，被 duck 压低音量的音乐无法自动恢复。
 *
 * 设计：
 *  - **引用计数**：系统 TTS 分段入队、云 TTS 流式播放、自检回放可能重叠请求，
 *    只有计数归零才真正 abandon，避免中途误放导致音乐抢回焦点。
 *  - `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`：语音播报是短时行为，压低而非杀死背景音乐，
 *    体验优于 GAIN（会让音乐彻底停掉且不恢复）。
 *  - `USAGE_ASSISTANT + CONTENT_TYPE_SPEECH`：让系统按「助手语音」路由（车机/蓝牙耳机
 *    会走通话/导航通道而非媒体通道），也是 [SPEECH_ATTRIBUTES] 供 AudioTrack/TextToSpeech 复用的原因。
 *  - 焦点永久丢失（AUDIOFOCUS_LOSS，如来电）时回调 [onFocusLost]，由 TTS 层立即停播。
 *
 * minSdk 26，`AudioFocusRequest` / `USAGE_ASSISTANT` 均可直接使用，无需版本分支。
 */
object QuroAudioFocus {
    private const val TAG = "QuroAudioFocus"

    /** 语音播报统一音频属性：AudioTrack / TextToSpeech / MediaPlayer 共用，保证路由一致。 */
    val SPEECH_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val lock = Any()
    private var request: AudioFocusRequest? = null
    private var holders: Int = 0

    /** 焦点被永久抢走（来电等）时的回调；由 [QuroTtsHolder] 注册为「立即停播」。 */
    @Volatile
    var onFocusLost: (() -> Unit)? = null

    /** 最近一次焦点请求的结果描述，供语音自检页展示。 */
    @Volatile
    var lastResult: String = "未请求"
        private set

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.i(TAG, "焦点永久丢失（来电/其它应用独占），停止语音播报")
                lastResult = "焦点被抢占（AUDIOFOCUS_LOSS）"
                // 永久丢失后系统已回收，计数清零避免后续 abandon 计数错乱
                synchronized(lock) { holders = 0; request = null }
                runCatching { onFocusLost?.invoke() }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.i(TAG, "焦点短暂丢失，停止语音播报")
                lastResult = "焦点短暂丢失（AUDIOFOCUS_LOSS_TRANSIENT）"
                runCatching { onFocusLost?.invoke() }
            }
            // MAY_DUCK / GAIN 无需处理：系统自动压低或恢复本应用音量
            else -> Unit
        }
    }

    private fun manager(ctx: Context?): AudioManager? {
        val c = ctx?.applicationContext ?: return null
        return runCatching { c.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }.getOrNull()
    }

    /**
     * 申请语音播报焦点（引用计数 +1）。
     *
     * @return true 表示已持有焦点（或系统返回 GRANTED）。false 表示申请被拒——调用方**仍应继续播放**，
     *         因为部分 ROM 在后台服务里会拒绝授予焦点，但音频通道本身可用；拒绝只做日志与自检展示。
     */
    fun acquire(ctx: Context?): Boolean {
        val am = manager(ctx) ?: run { lastResult = "无 AudioManager"; return false }
        synchronized(lock) {
            if (holders > 0 && request != null) {
                holders++
                return true
            }
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(SPEECH_ATTRIBUTES)
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            val r = runCatching { am.requestAudioFocus(req) }
                .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
            val ok = r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            lastResult = if (ok) "已获得焦点（TRANSIENT_MAY_DUCK）" else "申请被拒（code=$r）"
            if (ok) {
                request = req
                holders = 1
            }
            Log.i(TAG, "acquire → $lastResult")
            return ok
        }
    }

    /** 归还焦点（引用计数 -1，归零才真正 abandon）。 */
    fun release(ctx: Context?) {
        val am = manager(ctx) ?: return
        synchronized(lock) {
            if (holders > 0) holders--
            if (holders > 0) return
            val req = request ?: return
            runCatching { am.abandonAudioFocusRequest(req) }
            request = null
            lastResult = "已归还焦点"
            Log.i(TAG, "release → 已归还焦点")
        }
    }

    /** 强制归还（stop/reset 路径用：无论计数多少一次清干净，防止计数泄漏后永久占用焦点）。 */
    fun forceRelease(ctx: Context?) {
        val am = manager(ctx) ?: return
        synchronized(lock) {
            val req = request
            holders = 0
            request = null
            if (req != null) {
                runCatching { am.abandonAudioFocusRequest(req) }
                lastResult = "已强制归还焦点"
                Log.i(TAG, "forceRelease → 已强制归还焦点")
            }
        }
    }

    /** 当前持有计数，供自检页诊断焦点泄漏。 */
    fun holderCount(): Int = synchronized(lock) { holders }
}
