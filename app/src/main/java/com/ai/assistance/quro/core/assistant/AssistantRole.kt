package com.ai.assistance.quro.core.assistant

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.ai.assistance.quro.core.privilege.QuroRootGateway
import com.ai.assistance.quro.core.shizuku.QuroShizuku
import com.ai.assistance.quro.service.AssistantAssistService
import com.ai.assistance.quro.service.QuroVoiceInteractionService
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
 * ## 两条身份通道都要有（缺一条就有 ROM 唤不醒）
 * - **角色**：`ROLE_ASSISTANT` 持有者。系统按角色决定「谁是默认助手」。
 * - **VoiceInteractionService 组件**：`Settings.Secure.voice_interaction_service` 只接受
 *   真正的 `VoiceInteractionService`。本应用现在已提供
 *   [com.ai.assistance.quro.service.QuroVoiceInteractionService]，它的会话不做识别，
 *   只把控制权交回应用内语音球 —— 这样长按电源键在「走 voiceinteraction」的 ROM 上也能直达。
 *
 * 只注册 action 而没有上面任一条，系统根本不会把 ASSIST 投过来，
 * 于是表现为「设置页有开关但唤不醒」。
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
     * 诊断：把「能不能成为默认助手」这条链路的每一环摊开。
     *
     * 为什么需要：这条链路在 ColorOS / HyperOS 上会**静默失败**——
     * 角色不可用、设置页不存在、后台拉起被拦，表现全都是「点了没反应」。
     * 把每一环的布尔值直接给用户看，才能知道卡在哪一步，而不是反复点。
     */
    fun diagnostics(ctx: Context): List<String> = buildList {
        val sdk = Build.VERSION.SDK_INT
        add("系统版本：Android ${Build.VERSION.RELEASE}（API $sdk）")
        add("角色接口：${if (sdk >= Build.VERSION_CODES.Q) "RoleManager 可用" else "无 RoleManager（改用 assistant 设置项）"}")
        if (sdk >= Build.VERSION_CODES.Q) {
            add("ROLE_ASSISTANT 可申请：${if (available(ctx)) "是" else "否（该 ROM 关闭了这个角色）"}")
            add("当前是否已持有：${if (isAssistant(ctx)) "是" else "否"}")
        }
        add("本应用 VoiceInteractionService：${if (hasVoiceInteractionService(ctx)) "已在清单声明" else "未声明（角色无法授予）"}")
        add("「数字助理」设置页：${if (canResolve(ctx, Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))) "可打开" else "本机没有这个页面"}")
        if (sdk >= Build.VERSION_CODES.Q) {
            add("「默认应用」设置页：${if (canResolve(ctx, Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))) "可打开" else "本机没有这个页面"}")
        }
        add("Root / Shizuku：${if (privilegeReady()) "可用（可直接写入系统配置）" else "不可用"}")
    }

    /** 本应用是否声明了真正的 VoiceInteractionService（角色能否授予取决于它）。 */
    fun hasVoiceInteractionService(ctx: Context): Boolean = runCatching {
        val intent = Intent("android.service.voice.VoiceInteractionService").setPackage(ctx.packageName)
        ctx.packageManager.queryIntentServices(intent, 0).any { it.serviceInfo.packageName == ctx.packageName }
    }.getOrDefault(false)

    private fun canResolve(ctx: Context, intent: Intent): Boolean =
        runCatching { ctx.packageManager.resolveActivity(intent, 0) != null }.getOrDefault(false)

    private fun privilegeReady(): Boolean = runCatching {
        QuroShizuku.isReady || QuroRootGateway.exec(null, "id", 3_000L, "assistant.role.probe").success
    }.getOrDefault(false)

    /**
     * 不弹窗、直接生效：经 Root / Shizuku 写系统设置。
     *
     * 为什么需要这条路：ColorOS 的「数字助理」设置页可能根本不存在，
     * ROLE_ASSISTANT 也可能被 ROM 关掉，此时系统对话框永远弹不出来。
     * 有特权时直接写 `voice_interaction_service` 是唯一确定能生效的方式。
     *
     * @return 成功与否 + 可展示的说明
     */
    fun applyViaPrivilege(ctx: Context): Pair<Boolean, String> {
        val pkg = ctx.packageName
        val vis = "$pkg/${QuroVoiceInteractionService::class.java.name}"
        val assist = "$pkg/${AssistantAssistService::class.java.name}"
        val commands = listOf(
            "settings put secure voice_interaction_service $vis",
            "settings put secure assistant $assist",
        )
        val failures = mutableListOf<String>()
        commands.forEach { cmd ->
            val r = QuroRootGateway.exec(ctx, cmd, 8_000L, "assistant.role.write")
            if (!r.success) failures += "${cmd.substringBeforeLast(' ')}"
        }
        return if (failures.isEmpty()) {
            true to "已写入系统助手配置：$vis\n重启后长按电源键将由本应用接管"
        } else {
            false to "写入失败（需要 Root 或已启动的 Shizuku）：${failures.joinToString()}"
        }
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
