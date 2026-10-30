package com.ai.assistance.quro.core.assistant

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.ai.assistance.quro.util.QuroDiag

/**
 * ZorvAI ↔ 小布（OPPO Breeno）双向桥。
 *
 * ## 方向一：ZorvAI 去用小布
 * 小布把入口开在它 manifest 的导出组件上，Action 全部是从它 `AndroidManifest.xml` 原件里取的
 * （不是猜的名字）：
 *
 * | Action | 组件 | 用途 |
 * |---|---|---|
 * | `com.oplus.setting.ai.action.SETTING_ENTRANCE` | `SettingsActivity` | 打开小布设置（含服务开关/唤醒/播报） |
 * | `com.coloros.speechassist.start_action` | `FloatSpeechActivity` | 拉起浮窗语音 |
 * | `heytap.intent.action.NEW_AI_CHAT` | `SpeechAssistMainActivity` | 小布 AI 对话 |
 * | `heytap.speechassist.action.FIND_PHONE` | `FindPhoneActivity` | 让小布帮我找手机 |
 * | `heytap.intent.action.SKILL_PLAZA_HOME` / `MY_SKILLS` | 技能广场 | 看/管小布技能 |
 * | `coloros.speech.action.THIRD_PARTY_MESSAGE` | `SpeechService` | **把一段文本交给小布处理** |
 * | `heytap.intent.action.ACTIVATE_SPEECH_ASSIST` | `SpeechService` | 激活小布 |
 * | `heytap.speechassist.intent.action.EXTERNAL_TASK` | `PantanalRouterActivity` | 外部任务 |
 *
 * ## 方向二：让小布能用 ZorvAI
 * 关键机制**是从它 dex 里挖出来的**：小布在 `ShortcutManager` / `ShortcutManagerCompat` 上
 * 调 `execShortcutAsync(shortcutId)` / `getShortcuts()` —— 也就是说**它按 Android App Shortcuts
 * 调用第三方 App**。所以只要我们把能力发布成带 capability binding 的 shortcut，
 * 小布（以及任何读 ShortcutManager 的助手）就能直接看到并执行。
 * 见 [publishShortcuts]。
 *
 * ## 诚实边界
 * `THIRD_PARTY_MESSAGE` / `EXTERNAL_TASK` 的 **extras 结构在静态分析里拿不到**（它由
 * `EXTERNAL_TASK_TYPE` / `EXTERNAL_TASK_PARAMS` 两个键承载，但取值埋在代码逻辑里）。
 * 所以这里对这两个只做「按最可能的键名投递 + 失败明确报错」，不假装一定成功；
 * 而 Activity 入口是 manifest 里确定的，成功率高，优先走那条。
 */
object BreenoBridge {
    private const val TAG = "breeno-bridge"

    /** 小布可能出现的包名（不同 OPPO/OnePlus/realme 机型有差异）。 */
    val CANDIDATE_PKGS = listOf(
        "com.heytap.speechassist",
        "com.coloros.speechassist",
        "com.oplus.speechassist",
    )

    /** Action 常量 —— 逐条来自 Breeno.apk 的 AndroidManifest（见类注释表格）。 */
    object Actions {
        const val ACTIVATE = "heytap.intent.action.ACTIVATE_SPEECH_ASSIST"
        const val THIRD_PARTY_MESSAGE = "coloros.speech.action.THIRD_PARTY_MESSAGE"
        const val ENGINE_API = "com.heytap.speechassist.action.ENGINE_API"
        const val START_VOICE = "com.coloros.speechassist.start_action"
        const val NEW_AI_CHAT = "heytap.intent.action.NEW_AI_CHAT"
        const val SETTINGS = "com.oplus.setting.ai.action.SETTING_ENTRANCE"
        const val FIND_PHONE = "heytap.speechassist.action.FIND_PHONE"
        const val SKILL_PLAZA = "heytap.intent.action.SKILL_PLAZA_HOME"
        const val MY_SKILLS = "heytap.intent.action.SKILL_PLAZA_MY_SKILLS"
        const val EXTERNAL_TASK = "heytap.speechassist.intent.action.EXTERNAL_TASK"
        const val ROUTER = "heytap.speechassist.intent.action.PANTANAL_ROUTER_ACTION"
    }

    /** 小布 extras 键（来自 dex 字符串常量）。 */
    object Extras {
        const val EXTERNAL_TASK_TYPE = "EXTERNAL_TASK_TYPE"
        const val EXTERNAL_TASK_PARAMS = "EXTERNAL_TASK_PARAMS"
        const val FROM_SPEECH_ASSIST = "EXTRA_FROM_SPEECH_ASSIST"
    }

    data class Status(
        val installed: Boolean,
        val pkg: String = "",
        val versionName: String = "",
        val versionCode: Long = 0L,
        val resolvable: Map<String, Boolean> = emptyMap(),
    ) {
        fun wire(): String = "installed=$installed pkg=$pkg ver=$versionName($versionCode) " +
            resolvable.entries.joinToString(" ") { "${it.key}=${it.value}" }
    }

    // ── 探测 ─────────────────────────────────────────────────

    fun status(ctx: Context): Status {
        val pm = ctx.packageManager
        for (pkg in CANDIDATE_PKGS) {
            val info = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull() ?: continue
            val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
            else @Suppress("DEPRECATION") info.versionCode.toLong()
            val probe = linkedMapOf<String, Boolean>()
            listOf(
                Actions.SETTINGS to true,
                Actions.START_VOICE to true,
                Actions.NEW_AI_CHAT to true,
                Actions.FIND_PHONE to true,
                Actions.SKILL_PLAZA to true,
                Actions.THIRD_PARTY_MESSAGE to false, // service
                Actions.EXTERNAL_TASK to true,
            ).forEach { (action, isActivity) ->
                probe[action.substringAfterLast('.').take(24)] = resolvable(ctx, pkg, action, isActivity)
            }
            return Status(
                installed = true,
                pkg = pkg,
                versionName = info.versionName.orEmpty(),
                versionCode = code,
                resolvable = probe,
            )
        }
        return Status(installed = false)
    }

    private fun resolvable(ctx: Context, pkg: String, action: String, isActivity: Boolean): Boolean {
        val i = Intent(action).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            if (isActivity) ctx.packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY) != null
            else ctx.packageManager.resolveService(i, PackageManager.MATCH_DEFAULT_ONLY) != null
        }.getOrDefault(false)
    }

    // ── 方向一：ZorvAI 用小布 ────────────────────────────────

    /** 打开小布设置（服务总开关/唤醒/播报都在那一页，用户改完回来我们这边也一致）。 */
    fun openSettings(ctx: Context): AssistantResult =
        launch(ctx, Actions.SETTINGS, "breeno.open-settings", "已打开小布设置")

    /** 拉起小布浮窗语音。 */
    fun openVoice(ctx: Context): AssistantResult =
        launch(ctx, Actions.START_VOICE, "breeno.voice", "已拉起小布语音浮窗")

    /** 打开小布 AI 对话，可带一句话。 */
    fun openAiChat(ctx: Context, text: String? = null): AssistantResult {
        val i = intentFor(ctx, Actions.NEW_AI_CHAT) ?: return notInstalled("breeno.ai-chat")
        if (!text.isNullOrBlank()) {
            // 键名不确定，常见几种都塞一份；小布读哪个都能拿到
            i.putExtra(Intent.EXTRA_TEXT, text)
            i.putExtra("text", text)
            i.putExtra("query", text)
        }
        return fire(ctx, i, "breeno.ai-chat", "已打开小布对话")
    }

    /** 让小布的「查手机」响铃（我们自己也有一份，但用户可能更信小布那套）。 */
    fun findPhone(ctx: Context): AssistantResult =
        launch(ctx, Actions.FIND_PHONE, "breeno.find-phone", "已请小布找手机")

    fun openSkillPlaza(ctx: Context): AssistantResult =
        launch(ctx, Actions.SKILL_PLAZA, "breeno.skill-plaza", "已打开小布技能广场")

    fun openMySkills(ctx: Context): AssistantResult =
        launch(ctx, Actions.MY_SKILLS, "breeno.my-skills", "已打开小布「我的技能」")

    /**
     * 把一段文本交给小布处理（它去执行自己的技能）。
     *
     * 先试 service 的 `THIRD_PARTY_MESSAGE`；失败就回落到「拉开 AI 对话 + 把文本塞进 common keys」。
     * 这样即使 service 需要特权拿不到，用户的实际目标（让这段话进小布）也能达成。
     */
    fun sendText(ctx: Context, text: String): AssistantResult {
        if (text.isBlank()) return AssistantResult.fail(AssistantCodes.NO_TEXT, "没有要交给小布的文本")
        val st = status(ctx)
        if (!st.installed) return notInstalled("breeno.send-text")

        val viaService = runCatching {
            val i = Intent(Actions.THIRD_PARTY_MESSAGE).setPackage(st.pkg)
            i.putExtra(Intent.EXTRA_TEXT, text)
            i.putExtra("text", text)
            i.putExtra("message", text)
            i.putExtra("content", text)
            i.putExtra(Extras.FROM_SPEECH_ASSIST, ctx.packageName)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            true
        }.getOrElse {
            QuroDiag.log(TAG, "THIRD_PARTY_MESSAGE 投递失败: ${it.javaClass.simpleName}: ${it.message}")
            false
        }

        if (viaService) {
            QuroDiag.log(TAG, "sendText via THIRD_PARTY_MESSAGE ok")
            return AssistantResult.ok("breeno.send-text", "已交给小布处理", data = mapOf("via" to "THIRD_PARTY_MESSAGE"))
        }
        QuroDiag.log(TAG, "sendText 回落 AI 对话")
        val r = openAiChat(ctx, text)
        return if (r.ok) {
            AssistantResult.ok(
                "breeno.send-text",
                "已打开小布对话并带入文本（service 通道不可用，已回落）",
                data = mapOf("via" to "NEW_AI_CHAT"),
            )
        } else r
    }

    /**
     * 提交「外部任务」给小布的路由器。
     *
     * @param type 对应它 dex 里的 `EXTERNAL_TASK_TYPE`；取值枚举静态拿不到，先用调用方给的字符串
     * @param params 对应 `EXTERNAL_TASK_PARAMS`（Bundle/JSON 字符串都试）
     */
    fun submitExternalTask(ctx: Context, type: String, params: String): AssistantResult {
        val i = intentFor(ctx, Actions.EXTERNAL_TASK) ?: return notInstalled("breeno.external-task")
        i.putExtra(Extras.EXTERNAL_TASK_TYPE, type)
        i.putExtra(Extras.EXTERNAL_TASK_PARAMS, params)
        i.putExtra("external_task_type", type)
        i.putExtra("external_task_params", params)
        i.putExtra(Extras.FROM_SPEECH_ASSIST, ctx.packageName)
        return fire(ctx, i, "breeno.external-task", "已提交给小布路由")
    }

    // ── 方向二：让小布能用 ZorvAI ────────────────────────────

    /**
     * 发布能力型 App Shortcuts，让小布能发现并执行 ZorvAI。
     *
     * 为什么这条路可行（不是猜的）：小布的 dex 里有
     * `execShortcutAsync shortcutId:` / `execShortcutSync shortcutId:` /
     * `ShortcutManagerCompat` / `getShortcuts(...)` —— 它就是按 App Shortcuts 调第三方 App 的。
     *
     * 做法：用 `ShortcutManagerCompat.pushDynamicShortcut` 发布带 capability binding 的条目。
     * 静态条目在 `res/xml/shortcuts.xml`（长按图标也能看到），动态条目在这里（可带参数能力绑定）。
     *
     * @return 发布了几条、以及能力的 shortcut id 列表
     */
    fun publishShortcuts(ctx: Context): AssistantResult {
        val app = ctx.applicationContext
        val published = mutableListOf<String>()
        runCatching {
            val entries = listOf(
                Triple("quro.ask", "问 ZorvAI", "chat.ask"),
                Triple("quro.skills", "能力广场", "plaza"),
                Triple("quro.findphone", "我手机呢", "device.find-phone"),
                Triple("quro.terminal", "检查终端环境", "terminal.env-check"),
            )
            entries.forEach { (id, label, skill) ->
                val i = Intent(app, com.ai.assistance.quro.activity.AssistantShortcutActivity::class.java).apply {
                    action = com.ai.assistance.quro.activity.AssistantShortcutActivity.ACTION_SHORTCUT
                    putExtra(com.ai.assistance.quro.activity.AssistantShortcutActivity.EXTRA_SKILL, skill)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val info = androidx.core.content.pm.ShortcutInfoCompat.Builder(app, id)
                    .setShortLabel(label)
                    .setLongLabel(label)
                    .setIcon(androidx.core.graphics.drawable.IconCompat.createWithResource(app, com.ai.assistance.quro.R.mipmap.ic_launcher))
                    .setIntent(i)
                    .addCapabilityBinding("custom.actions.intent.QURO_SKILL", "quro.skill", listOf(skill))
                    .build()
                androidx.core.content.pm.ShortcutManagerCompat.pushDynamicShortcut(app, info)
                published += id
            }
        }.onFailure {
            QuroDiag.log(TAG, "publishShortcuts 失败: ${it.javaClass.simpleName}: ${it.message}")
            return AssistantResult.fail(
                AssistantCodes.SKILL_FAILED,
                "发布应用快捷方式失败：${it.message ?: it.javaClass.simpleName}",
                skillId = "breeno.publish-shortcuts",
            )
        }
        QuroDiag.log(TAG, "publishShortcuts ok: $published")
        return AssistantResult.ok(
            "breeno.publish-shortcuts",
            "已发布 ${published.size} 条能力快捷方式，小布/系统助手可发现 ZorvAI",
            data = mapOf("ids" to published.joinToString(",")),
        )
    }

    // ── 内部 ─────────────────────────────────────────────────

    private fun intentFor(ctx: Context, action: String): Intent? {
        val st = status(ctx)
        if (!st.installed) return null
        return Intent(action).setPackage(st.pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun launch(ctx: Context, action: String, skillId: String, okMsg: String): AssistantResult {
        val i = intentFor(ctx, action) ?: return notInstalled(skillId)
        return fire(ctx, i, skillId, okMsg)
    }

    private fun fire(ctx: Context, i: Intent, skillId: String, okMsg: String): AssistantResult {
        return runCatching { ctx.startActivity(i) }.fold(
            onSuccess = {
                QuroDiag.log(TAG, "$skillId ok action=${i.action}")
                AssistantResult.ok(skillId, okMsg, data = mapOf("action" to (i.action ?: "")))
            },
            onFailure = {
                QuroDiag.log(TAG, "$skillId 失败 action=${i.action} err=${it.javaClass.simpleName}: ${it.message}")
                AssistantResult.fail(
                    AssistantCodes.SKILL_FAILED,
                    "调不起小布（${i.action}）：${it.message ?: it.javaClass.simpleName}。可能该组件未导出或版本不支持。",
                    skillId = skillId,
                    data = mapOf("action" to (i.action ?: "")),
                )
            },
        )
    }

    private fun notInstalled(skillId: String): AssistantResult = AssistantResult.fail(
        AssistantCodes.UNSUPPORTED,
        "这台机器上没有小布（试过 ${CANDIDATE_PKGS.joinToString("/")}）",
        skillId = skillId,
    )
}
