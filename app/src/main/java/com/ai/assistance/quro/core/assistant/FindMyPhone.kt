package com.ai.assistance.quro.core.assistant

import android.content.Context
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.ai.assistance.quro.util.QuroDiag

/**
 * 查手机 / 找手机。
 *
 * 对标小布的 `FindPhoneActivity`（`heytap.speechassist.action.FIND_PHONE`）：
 * 语音说一句「我手机呢」→ 手机最大声闹铃 + 持续震动 + 点亮屏幕，即使静音/勿扰也尽量出声。
 *
 * 与纯「播放通知音」的区别（这几条是查手机能不能真被听到的关键）：
 * 1. 走 **闹钟流**（STREAM_ALARM）而不是通知流——通知流在静音模式下会被压掉；
 * 2. 用 `RingtoneManager` 的系统闹铃 URI，`isLooping` 循环直到超时；
 * 3. 唤醒屏幕 + 短暂 WakeLock，让人看得见；
 * 4. 定时自动停（默认 30s），避免用户找不到开关时一直响。
 */
object FindMyPhone {
    private const val TAG = "assistant-findphone"
    private const val MAX_SECONDS = 120

    private val main = Handler(Looper.getMainLooper())

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var stopRunnable: Runnable? = null

    @Volatile
    var ringing: Boolean = false
        private set

    /**
     * 开始找手机。
     * @param seconds 持续时间，1..120，默认 30
     * @return 统一封套；失败时 code 说明是「没权限」还是「没闹铃资源」
     */
    fun start(ctx: Context, seconds: Int = 30): AssistantResult {
        val dur = seconds.coerceIn(1, MAX_SECONDS)
        if (ringing) return AssistantResult.ok("device.find-phone", "已经在响铃中；要停请点「停止找手机」或磁贴")
        val app = ctx.applicationContext
        val notes = LinkedHashMap<String, Any?>()

        // 1) 音频
        runCatching {
            val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (am != null) {
                notes["alarmVolume"] = am.getStreamVolume(AudioManager.STREAM_ALARM)
                notes["maxAlarmVolume"] = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                notes["ringerMode"] = am.ringerMode
                // 静音/震动模式下临时放开闹钟流（不改用户设置，只在本次有效由系统自动回落）
                runCatching {
                    if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
                        am.ringerMode = AudioManager.RINGER_MODE_NORMAL
                        notes["ringerModeForced"] = true
                    }
                }
                // 拉满闹钟音量：需要 MODIFY_AUDIO_SETTINGS，缺权限时忽略即可（不阻断）
                runCatching {
                    val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                    am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
                    notes["alarmVolumeAfter"] = am.getStreamVolume(AudioManager.STREAM_ALARM)
                }
            }
        }

        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        if (uri == null) {
            return AssistantResult.fail(
                AssistantCodes.UNSUPPORTED,
                "系统没有可用的闹铃/铃声资源",
                skillId = "device.find-phone",
            )
        }
        val r = runCatching { RingtoneManager.getRingtone(app, uri) }.getOrNull()
            ?: return AssistantResult.fail(
                AssistantCodes.SKILL_FAILED,
                "取不到闹铃对象（铃声资源被移除？）",
                skillId = "device.find-phone",
            )
        r.audioAttributes = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_ALARM)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        runCatching { if (Build.VERSION.SDK_INT >= 28) r.isLooping = true }
        runCatching { r.play() }.onFailure {
            return AssistantResult.fail(
                AssistantCodes.PERMISSION_DENIED,
                "播放闹铃失败：${it.message ?: it.javaClass.simpleName}",
                skillId = "device.find-phone",
            )
        }
        ringtone = r
        notes["ringtoneUri"] = uri.toString()

        // 2) 震动
        runCatching {
            val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vib != null && vib.hasVibrator()) {
                val pattern = longArrayOf(0, 700, 500)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib.vibrate(VibrationEffect.createWaveform(pattern, 0))
                } else {
                    @Suppress("DEPRECATION")
                    vib.vibrate(pattern, 0)
                }
                vibrator = vib
                notes["vibrating"] = true
            }
        }

        // 3) 点亮屏幕
        runCatching {
            val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val wl = pm?.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "Quro:FindMyPhone",
            )
            wl?.setReferenceCounted(false)
            wl?.acquire(dur * 1000L)
            wakeLock = wl
            notes["screenOn"] = true
        }

        // 4) 自动停
        ringing = true
        val stop = Runnable { stop("timeout") }
        stopRunnable = stop
        main.postDelayed(stop, dur * 1000L)

        QuroDiag.log(TAG, "start dur=${dur}s uri=$uri notes=$notes")
        return AssistantResult.ok(
            skillId = "device.find-phone",
            message = "正在找手机：闹铃 + 震动 ${dur}s",
            data = notes,
            actions = listOf(
                AssistantAction("device.find-phone.stop", "停止响铃", AssistantAction.KIND_SKILL, "device.find-phone", primary = true),
            ),
        )
    }

    fun stop(reason: String = "manual"): AssistantResult {
        stopRunnable?.let { main.removeCallbacks(it) }
        stopRunnable = null
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching { vibrator?.cancel() }
        vibrator = null
        runCatching { wakeLock?.release() }
        wakeLock = null
        val was = ringing
        ringing = false
        QuroDiag.log(TAG, "stop reason=$reason wasRinging=$was")
        return AssistantResult.ok("device.find-phone.stop", if (was) "已停止找手机" else "当前没在响")
    }
}
