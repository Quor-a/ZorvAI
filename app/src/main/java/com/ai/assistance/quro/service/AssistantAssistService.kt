package com.ai.assistance.quro.service

import android.app.SearchManager
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.ai.assistance.quro.core.assistant.AssistantChannel
import com.ai.assistance.quro.core.assistant.AssistantServiceState
import com.ai.assistance.quro.core.directive.AssistantDirectiveBus
import com.ai.assistance.quro.util.QuroDiag

/**
 * 助手唤起入口 —— **组件形态与 action 注册面直接取自小布（com.heytap.speechassist v13.3.5）**。
 *
 * ## 为什么要做成 Service，而不是 Activity
 * 小布把这一整套注册在 `com.heytap.speechassist.core.SpeechService` 上（清单实证）：
 * ```
 * [service] core.SpeechService  exported=true
 *   actions = android.speech.action.WEB_SEARCH
 *             android.intent.action.VOICE_COMMAND
 *             android.speech.action.VOICE_SEARCH_HANDS_FREE
 *             coloros.speech.action.THIRD_PARTY_MESSAGE
 *             android.intent.action.ASSIST
 *             heytap.intent.action.ACTIVATE_SPEECH_ASSIST
 *             heytap.intent.action.OCAR_SPEECH_ASSIST
 *   categories = DEFAULT, INFO
 * ```
 * 我们之前把 ASSIST 挂在 Activity 上，于是：
 *  - 系统长按电源键 / 长按 Home 时给的 ASSIST 会去拉起一整个界面，而不是"就地唤起语音"；
 *  - `VOICE_COMMAND`（**耳机键长按系统就是发这个**）、`VOICE_SEARCH_HANDS_FREE`
 *    （蓝牙耳机免手操作）根本没人接 —— 所以「耳机唤醒」无论怎么写都唤不醒。
 *
 * ## 这三条是"语音唤醒/电源键/耳机键"的真实链路
 * 1. 用户在系统设置里把本应用设为**默认数字助理**（`ROLE_ASSISTANT`）；
 * 2. 长按电源键 → 系统发 `android.intent.action.ASSIST`；长按耳机键 → 系统发
 *    `android.intent.action.VOICE_COMMAND`；蓝牙耳机语音键 → `VOICE_SEARCH_HANDS_FREE`；
 * 3. 系统按包名把 intent 投给**默认助手应用**里注册了该 action 的组件 —— 这里。
 *
 * 也就是说：**能不能被电源键/耳机键唤起，取决于"是不是默认助手"+"action 注册面是否齐全"**，
 * 与有没有一个漂亮的设置页毫无关系。这一版把 action 面补齐，并在设置页给出"设为默认数字助理"入口。
 *
 * ## categories 里的 INFO 不能省
 * 小布两个 category 都声明了。`android.intent.category.INFO` 是系统在**候选助手列表**里
 * 枚举时用的；少了它，部分 ROM 的"默认助手"选择页不会列出本应用。
 */
class AssistantAssistService : android.app.Service() {

    companion object {
        private const val TAG = "assistant-assist"

        /** 对应小布的 `heytap.intent.action.ACTIVATE_SPEECH_ASSIST`（主动唤醒，不带语音输入）。 */
        const val ACTION_ACTIVATE = "com.ai.assistance.quro.action.ACTIVATE_ASSIST"

        /**
         * 小布的 `coloros.speech.action.THIRD_PARTY_MESSAGE` —— **照抄这个 action 名**。
         *
         * 理由很实际：第三方 App / 系统其他组件里已经写死了这个名字来给小布喂文本。
         * 我们注册同一个 action 后，这些调用方（不需要改一行代码）也会把文本投给我们。
         * 这不是"抢小布"，是接同一套已有约定；用户把小布关了或换成我们做默认助手时，
         * 这条链路才不会断。
         */
        const val ACTION_THIRD_PARTY_MESSAGE = "coloros.speech.action.THIRD_PARTY_MESSAGE"

        /** 从 intent 里抽出要说的话（各 action 的来源不一样，逐个试）。 */
        private fun textOf(intent: Intent): String {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { return it }
            intent.getStringExtra(SearchManager.QUERY)?.takeIf { it.isNotBlank() }?.let { return it }
            intent.getStringExtra("query")?.takeIf { it.isNotBlank() }?.let { return it }
            intent.getStringExtra("text")?.takeIf { it.isNotBlank() }?.let { return it }
            intent.getStringExtra("message")?.takeIf { it.isNotBlank() }?.let { return it }
            return ""
        }
    }

    override fun onBind(intent: Intent?): android.os.IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 语音球自己有前台通知；本 Service 只是"接门铃"，处理完即停，不留常驻
        try {
            handle(intent)
        } catch (t: Throwable) {
            QuroDiag.log(TAG, "handle failed: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun handle(intent: Intent?) {
        val action = intent?.action ?: ACTION_ACTIVATE
        val text = if (intent != null) textOf(intent) else ""

        // 通道判定按 action 精确分流 —— 通道开关要能真的各管各的：
        //   ASSIST              ← 长按电源键 / 长按 Home / 系统助手键
        //   VOICE_COMMAND       ← **耳机键长按，系统发的就是它**（线控与蓝牙耳机都走这条）
        //   VOICE_SEARCH_HANDS_FREE ← 蓝牙耳机免手操作
        //   WEB_SEARCH          ← 系统搜索键 / 语音搜索
        //   THIRD_PARTY_MESSAGE ← 第三方 App 喂文本（外部指令面）
        val channel = when (action) {
            ACTION_THIRD_PARTY_MESSAGE, Intent.ACTION_WEB_SEARCH -> AssistantChannel.DIRECTIVE
            Intent.ACTION_ASSIST, ACTION_ACTIVATE -> AssistantChannel.ASSIST_KEY
            else -> if (hasBluetoothAudio()) AssistantChannel.HEADSET_BT else AssistantChannel.HEADSET_WIRED
        }

        val denied = AssistantServiceState.gate(this, channel)
        if (denied != null) {
            QuroDiag.log(TAG, "denied action=$action channel=${channel.wire} ${denied.wire()}")
            return
        }

        QuroDiag.log(TAG, "wake action=$action channel=${channel.wire} hasText=${text.isNotEmpty()}")

        // 带文本的（THIRD_PARTY_MESSAGE / ASSIST 带 query）→ 直接当指令投信箱，界面起来就发出去
        if (text.isNotEmpty()) {
            AssistantDirectiveBus.post(text, "assist:$action")
            runCatching {
                startActivity(
                    Intent(this, com.ai.assistance.quro.activity.QuroMainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                )
            }.onFailure { QuroDiag.log(TAG, "open chat failed: ${it.message}") }
            return
        }

        // 不带文本的（长按电源键 / 长按耳机键）→ 唤起语音球，直接进入聆听
        val started = runCatching {
            startForegroundService(
                Intent(this, QuroVoiceBallService::class.java).apply {
                    this.action = QuroVoiceBallService.ACTION_VOICE_TALK
                }
            )
        }.isSuccess

        if (!started) {
            QuroDiag.log(TAG, "voice ball 起不来（可能缺悬浮窗/前台服务权限），回落打开对话")
            runCatching {
                startActivity(
                    Intent(this, com.ai.assistance.quro.activity.QuroMainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                )
            }.onFailure { QuroDiag.log(TAG, "open chat failed: ${it.message}") }
        }
    }

    /**
     * 有蓝牙音频输出就当"蓝牙耳机"通道，否则"线控耳机"通道。
     *
     * 系统发的 `VOICE_COMMAND` 不区分耳机类型（同一个 action），所以只能按当前音频路由近似。
     * 与耳机 receiver 里的判定保持一致，两个入口的开关行为才不会打架。
     */
    private fun hasBluetoothAudio(): Boolean = runCatching {
        val am = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return@runCatching false
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
}
