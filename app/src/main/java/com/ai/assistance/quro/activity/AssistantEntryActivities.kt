package com.ai.assistance.quro.activity

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.ai.assistance.quro.core.assistant.AssistantChannel
import com.ai.assistance.quro.core.assistant.AssistantCodes
import com.ai.assistance.quro.core.assistant.AssistantResult
import com.ai.assistance.quro.core.assistant.AssistantServiceState
import com.ai.assistance.quro.core.assistant.BackgroundTaskRelay
import com.ai.assistance.quro.core.assistant.FindMyPhone
import com.ai.assistance.quro.util.QuroDiag

/**
 * 后台任务中转入口 —— **组件形态取自小布**。
 *
 * 清单实证，小布这一项是 **Activity**：
 * ```
 * [activity] com.heytap.speechassist.background.impl.BackgroundTaskRelayActivity  exported=true
 *   actions = heytap.speech.intent.action.SUBMIT_BACKGROUND_TASK_RELAY
 * ```
 * 我先前把它做成了**广播**（在 AssistantDirectiveReceiver 里），这是错的：
 *  - 广播没有"调用方身份"，回执只能靠约定包名反查；
 *  - 广播在后台被系统限流（Android 8+ 隐式广播限制），长任务容易丢；
 *  - 更关键：**Activity 能让调用方 `startActivityForResult`，天然拿到返回**。
 *
 * 本 Activity 只做三件事：解析请求 → 交给 [BackgroundTaskRelay] → Toast 回显。执行完立即 finish，
 * 不留界面（`noHistory` + 透明主题在清单里）。
 *
 * 协议（extras，全部可空）：
 *   `skill` / `skill_id`  —— 要执行的助手能力 id
 *   `request_id`          —— 幂等去重键；空则自动生成
 *   `target_package`      —— 回执广播定向包名
 *   其余 extras 全部当作技能参数（key → value，非字符串取 toString）
 */
class AssistantBackgroundRelayActivity : Activity() {

    companion object {
        private const val TAG = "assistant-relay-ui"

        /** 中转入口 action（我们自己的一方，形态与小布 SUBMIT_BACKGROUND_TASK_RELAY 一致）。 */
        const val ACTION_SUBMIT = "com.ai.assistance.quro.action.SUBMIT_BACKGROUND_TASK_RELAY"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = intent
        if (intent == null) {
            finish()
            return
        }

        val denied = AssistantServiceState.gate(this, AssistantChannel.RELAY)
        if (denied != null) {
            QuroDiag.log(TAG, "denied ${denied.wire()}")
            toast(denied.message)
            finish()
            return
        }

        val skill = (intent.getStringExtra("skill") ?: intent.getStringExtra("skill_id")).orEmpty()
        if (skill.isBlank()) {
            QuroDiag.log(TAG, "missing skill extra")
            toast("缺少 skill：${AssistantCodes.SKILL_BAD_ARGS}")
            finish()
            return
        }

        val rid = intent.getStringExtra("request_id")
            ?: intent.getStringExtra(BackgroundTaskRelay.EXTRA_REQUEST_ID)
        val target = intent.getStringExtra("target_package")
            ?: intent.getStringExtra(BackgroundTaskRelay.EXTRA_TARGET_PACKAGE)

        // 除保留键外，其余 extras 一律作为技能参数（这样调用方不必知道我们的参数白名单）
        val reserved = setOf("skill", "skill_id", "request_id", "target_package", "android.intent.extra.REFERRER_NAME")
        val args = LinkedHashMap<String, String>()
        intent.extras?.keySet()?.forEach { k ->
            if (k in reserved) return@forEach
            val v = intent.extras?.get(k) ?: return@forEach
            // 参数值做长度上限保护：这是外部输入，不能让它撑爆内存/日志
            args[k] = v.toString().take(4096)
        }

        QuroDiag.log(TAG, "submit skill=$skill rid=$rid args=${args.keys.joinToString(",")}")
        val r = BackgroundTaskRelay.submit(
            ctx = applicationContext,
            skillId = skill,
            args = args,
            requestId = rid.orEmpty(),
            targetPackage = target,
        )
        toast(if (r.ok) "已提交：$skill（回执将发到 ${BackgroundTaskRelay.RELAY_RESULT_ACTION}）" else r.message)
        finish()
    }

    private fun toast(msg: String) {
        runCatching { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }
}

/**
 * 查手机入口 —— **组件形态取自小布**。
 *
 * 清单实证：
 * ```
 * [activity] com.heytap.speechassist.FindPhoneActivity  exported=true
 *   actions = android.intent.action.MAIN, heytap.speechassist.action.FIND_PHONE
 * ```
 * 小布这里同时声明了 MAIN —— 意味着它既是一个可被显式拉起的页面，也是能被系统/第三方
 * 用 `FIND_PHONE` 直接叫起来的入口。我们照同样的形态做：本 Activity 不带界面，收到就响铃。
 *
 * 为什么不只留磁贴：磁贴要用户手动加到下拉面板；而这个 action 是**别的设备/别的 App
 * 调用"让你手机响"**的标准姿势（比如手表、耳机 App、家人的手机）。
 */
class AssistantFindPhoneActivity : Activity() {

    companion object {
        const val ACTION_FIND_PHONE = "com.ai.assistance.quro.action.FIND_PHONE"

        /** 响铃时长（秒）；调用方可用 extra `seconds` 覆盖。 */
        private const val DEFAULT_SECONDS = 30
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = intent
        val denied = AssistantServiceState.gate(this, AssistantChannel.TILE)
        if (denied != null) {
            QuroDiag.log("assistant-findphone", "denied ${denied.wire()}")
            toast(denied.message)
            finish()
            return
        }

        // 已经在响 → 再叫一次就停（与磁贴的切换语义保持一致）
        if (FindMyPhone.ringing) {
            val r = FindMyPhone.stop("find-phone-activity")
            toast(if (r.ok) "已停止响铃" else r.message)
            finish()
            return
        }

        val seconds = i?.getIntExtra("seconds", DEFAULT_SECONDS) ?: DEFAULT_SECONDS
        val r: AssistantResult = FindMyPhone.start(applicationContext, seconds)
        QuroDiag.log("assistant-findphone", "start seconds=$seconds ${r.wire()}")
        toast(if (r.ok) "正在响铃 ${seconds}s" else r.message)
        finish()
    }

    private fun toast(msg: String) {
        runCatching { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }
}
