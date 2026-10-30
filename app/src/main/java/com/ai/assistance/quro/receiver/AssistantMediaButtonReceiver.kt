package com.ai.assistance.quro.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import com.ai.assistance.quro.core.assistant.AssistantChannel
import com.ai.assistance.quro.core.assistant.AssistantServiceState
import com.ai.assistance.quro.service.QuroVoiceBallService
import com.ai.assistance.quro.util.QuroDiag

/**
 * 耳机按键唤醒。
 *
 * 对标小布设置页的两项：
 * > **线控耳机唤醒**「长按线控耳机播放键唤醒」
 * > **蓝牙耳机唤醒**「触碰或长按蓝牙耳机唤醒」
 *
 * ## 为什么不吞掉短按
 * 耳机键的第一用途永远是**控制音乐**。所以本接收器只在**长按**（≥[LONG_PRESS_MS]）时动作，
 * 短按一律不拦截、不 abort —— 否则用户一按播放键音乐就断，这个功能会被立刻关掉。
 *
 * ## 怎么区分线控和蓝牙
 * `MEDIA_BUTTON` 广播本身不带"从哪来"的信息。用当前音频输出设备近似判断：
 * 存在 A2DP/蓝牙 SCO/LE 输出就先算蓝牙，否则算线控。**近似而非精确**，但足够让两个
 * 独立开关各自生效；判断不出来时按线控处理（线控开关默认开）。
 *
 * ## 受两层闸门约束
 * 总开关（[AssistantServiceState.isServiceEnabled]）关了一律不响应；对应通道关了也不响应。
 */
class AssistantMediaButtonReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "assistant-headset"

        /** 长按阈值：与小布「长按 0.5 秒」对齐。 */
        private const val LONG_PRESS_MS = 500L

        /** 只认这些键；其他键（音量等）完全不碰。 */
        private val WAKE_KEYS = setOf(
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK,
        )

        /** 记录各键按下的时刻（多键同时按下时互不干扰）。 */
        private val downAt = HashMap<Int, Long>()
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        val ev = keyEventOf(intent) ?: return
        if (ev.keyCode !in WAKE_KEYS) return

        // 重复事件（长按期间系统会连发 DOWN）不重置起点，否则永远凑不满时长
        when (ev.action) {
            KeyEvent.ACTION_DOWN -> {
                if (ev.repeatCount == 0) downAt[ev.keyCode] = SystemClock.elapsedRealtime()
            }

            KeyEvent.ACTION_UP -> {
                val start = downAt.remove(ev.keyCode) ?: return
                val held = SystemClock.elapsedRealtime() - start
                if (held < LONG_PRESS_MS) {
                    // 短按：音乐归你，我们不碰
                    return
                }
                handleWake(context, ev.keyCode, held)
            }
        }
    }

    private fun handleWake(ctx: Context, keyCode: Int, heldMs: Long) {
        val channel = channelOf(ctx)
        val app = ctx.applicationContext

        // 闸门：服务关了 / 通道关了 → 明确记日志并退出（不吞按键、不弹任何界面）
        val denied = AssistantServiceState.gate(app, channel)
        if (denied != null) {
            QuroDiag.log(TAG, "wake denied key=$keyCode held=${heldMs}ms ${denied.wire()}")
            return
        }

        QuroDiag.log(TAG, "wake key=$keyCode held=${heldMs}ms channel=${channel.wire}")

        // 首选取把语音球叫起来（对标小布"长按耳机键直接说话"）；
        // 语音球起不来（没给悬浮窗权限 / 服务被系统限制）就退一步打开对话，绝不静默无反应。
        val started = runCatching {
            ctx.startForegroundService(
                Intent(ctx, QuroVoiceBallService::class.java).apply {
                    action = QuroVoiceBallService.ACTION_VOICE_TALK
                }
            )
        }.isSuccess

        if (!started) {
            QuroDiag.log(TAG, "voice ball 启动失败，回落为打开对话")
            // 不往信箱投空文本（post 会拒绝空串）；这里只是把界面带到前台，让用户直接说/打
            runCatching {
                ctx.startActivity(
                    Intent(ctx, com.ai.assistance.quro.activity.QuroMainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                )
            }.onFailure { QuroDiag.log(TAG, "open chat failed: ${it.message}") }
        }
    }

    /** 有蓝牙音频输出就算蓝牙通道，否则算线控。 */
    private fun channelOf(ctx: Context): AssistantChannel {
        val bt = runCatching {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return@runCatching false
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { d ->
                when (d.type) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                    AudioDeviceInfo.TYPE_BLE_SPEAKER,
                    -> true

                    else -> false
                }
            }
        }.getOrDefault(false)
        return if (bt) AssistantChannel.HEADSET_BT else AssistantChannel.HEADSET_WIRED
    }

    private fun keyEventOf(intent: Intent): KeyEvent? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        }
    }.getOrNull()
}
