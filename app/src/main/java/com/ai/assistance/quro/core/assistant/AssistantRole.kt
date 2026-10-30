package com.ai.assistance.quro.core.assistant

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.ai.assistance.quro.util.QuroDiag

/**
 * 「默认数字助理」身份管理 —— **这一条是电源键/耳机键唤醒能不能用的总开关**。
 *
 * ## 为什么必须做这一层（用户问的"语音唤醒怎么用"的答案就在这里）
 * 小布能被长按电源键唤起，**不是因为它写了什么界面**，而是因为：
 * 1. 用户在系统里把「默认数字助理」设成了小布；
 * 2. 长按电源键时，**系统**（不是我们的 App）发出一条广播给这个默认助手。
 *
 * 具体发什么：
 * | 手势 | 系统发出的 action |
 * |---|---|
 * | 长按电源键 / 长按 Home / 手势导航上滑停住 | `android.intent.action.ASSIST` |
 * | **长按线控/蓝牙耳机播放键** | `android.intent.action.VOICE_COMMAND` |
 * | 蓝牙耳机语音键（免手操作） | `android.speech.action.VOICE_SEARCH_HANDS_FREE` |
 * | 语音搜索键 | `android.speech.action.WEB_SEARCH` |
 *
 * 这四条 action 全部由系统投给**默认助手应用**。所以：
 * - 我们在 [com.ai.assistance.quro.service.AssistantAssistService] 里注册齐了这四个 action；
 * - **但只有用户把本应用设为默认助理后，系统才会真的发过来**。
 * 缺这一条，代码写得再全也唤不醒 —— 这正是"设置页有个开关但不工作"的根因。
 *
 * ## 为什么不用 VoiceInteractionService
 * 完整的语音交互服务（`VoiceInteractionService` + `RecognitionService` + 常驻会话）
 * 是 Android 给 Google Assistant 那套用的，要求提供 `RecognitionService`、
 * 常驻语音输入会话与完整的 UI 会话栈，接入面很大且各 ROM 行为不一。
 * 小布走的是较轻的 `ROLE_ASSISTANT` + ASSIST/VOICE_COMMAND 组合，我们与它保持一致。
 */
object AssistantRole {

    private const val TAG = "assistant-role"

    /** 当前是否已持有默认数字助理角色。 */
    fun isAssistant(ctx: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = ctx.getSystemService(RoleManager::class.java) ?: return@runCatching false
            rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        } else {
            // Android 9 及以下没有 RoleManager：用系统的"助手"设置项读当前值比对包名
            @Suppress("DEPRECATION")
            val cur = Settings.Secure.getString(ctx.contentResolver, "assistant")
            cur != null && cur.contains(ctx.packageName)
        }
    }.getOrDefault(false)

    /** 系统是否允许申请该角色（部分 ROM 会把 ROLE_ASSISTANT 关掉）。 */
    fun available(ctx: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ctx.getSystemService(RoleManager::class.java)
                ?.isRoleAvailable(RoleManager.ROLE_ASSISTANT) ?: false
        } else {
            true
        }
    }.getOrDefault(false)

    /**
     * 弹出「设为默认数字助理」的系统对话框。
     *
     * 🔴 **必须由 Activity 发起**（`startActivityForResult`），不能在 Service/Receiver 里调：
     * 系统角色弹窗属于用户交互，后台发起会被直接丢弃。
     */
    fun request(activity: Activity, requestCode: Int = REQ_ASSISTANT): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return runCatching {
                val rm = activity.getSystemService(RoleManager::class.java)
                if (rm == null || !rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                    QuroDiag.log(TAG, "ROLE_ASSISTANT 不可用，改为跳设置页")
                    openSystemSettings(activity)
                    return@runCatching false
                }
                activity.startActivityForResult(
                    rm.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT),
                    requestCode,
                )
                QuroDiag.log(TAG, "已弹出 ROLE_ASSISTANT 申请")
                true
            }.getOrElse {
                QuroDiag.log(TAG, "request role failed: ${it.javaClass.simpleName}: ${it.message}")
                openSystemSettings(activity)
                false
            }
        }
        openSystemSettings(activity)
        return false
    }

    /**
     * 兜底：直接跳系统的「默认应用 / 数字助理」设置页，让用户手动选。
     *
     * 为什么需要兜底：ColorOS / HyperOS 等 ROM 有时不返回 `ROLE_ASSISTANT` 弹窗
     * （直接返回空结果或静默失败），此时只能把用户带到设置页。
     */
    fun openSystemSettings(ctx: Context): Boolean {
        val candidates = buildList {
            add(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            }
            add(Intent(Settings.ACTION_SETTINGS))
        }
        for (i in candidates) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val ok = runCatching { ctx.startActivity(i) }.isSuccess
            QuroDiag.log(TAG, "open settings ${i.action} ok=$ok")
            if (ok) return true
        }
        return false
    }

    const val REQ_ASSISTANT = 0x4A51
}
