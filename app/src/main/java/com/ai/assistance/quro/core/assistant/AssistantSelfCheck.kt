package com.ai.assistance.quro.core.assistant

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.ai.assistance.quro.util.QuroDiag

/**
 * 助手各项功能的**真实可用性自检**。
 *
 * ## 为什么必须有这个
 * 用户在设置页看到一排开关，打开之后却发现「长按电源键没反应」「耳机键没反应」，
 * 只能得到"这功能是乱写的"这个结论 —— 但真正的原因往往不在代码里，而是：
 * 没设成默认数字助理 / 没给麦克风 / 没给悬浮窗 / ROM 把 ROLE_ASSISTANT 关了。
 * 这些全部可以在运行时查出来，就该直接摊在界面上，而不是让用户去猜。
 *
 * 每一项都给出三件事：**状态（可用/不可用）→ 判据（查了什么）→ 怎么修（点哪里）**。
 */
object AssistantSelfCheck {

    private const val TAG = "assistant-selfcheck"

    enum class Level { OK, WARN, BLOCKED }

    /**
     * @param id 稳定 id（进日志可 grep）
     * @param title 人话标题
     * @param detail 当前实际状态（含判据）
     * @param fixHint 怎么修；为空表示无需操作
     * @param fixAction 修复动作标识，界面按它决定跳哪
     */
    data class Item(
        val id: String,
        val title: String,
        val level: Level,
        val detail: String,
        val fixHint: String = "",
        val fixAction: FixAction = FixAction.NONE,
    )

    enum class FixAction {
        NONE,
        REQUEST_ASSISTANT_ROLE,
        REQUEST_MIC,
        REQUEST_OVERLAY,
        REQUEST_NOTIFICATION,
        OPEN_QS_PANEL,
    }

    /** 全量自检。 */
    fun run(ctx: Context): List<Item> = buildList {
        add(assistantRole(ctx))
        add(assistIntent(ctx))
        add(voiceCommandIntent(ctx))
        add(microphone(ctx))
        add(overlay(ctx))
        add(notification(ctx))
        add(foregroundService(ctx))
        add(shortcuts(ctx))
    }.also { items ->
        val bad = items.filter { it.level != Level.OK }
        QuroDiag.log(TAG, "self-check: ${items.size - bad.size}/${items.size} OK; blocked=${bad.count { it.level == Level.BLOCKED }} warn=${bad.count { it.level == Level.WARN }}")
        bad.forEach { QuroDiag.log(TAG, "  ${it.id} ${it.level} ${it.detail}") }
    }

    // ── 各项 ─────────────────────────────────────────────────────

    /** 默认数字助理：电源键/耳机键能不能被唤起的总前提。 */
    private fun assistantRole(ctx: Context): Item {
        val held = AssistantRole.isAssistant(ctx)
        val avail = AssistantRole.available(ctx)
        return when {
            held -> Item("assistant-role", "默认数字助理", Level.OK, "已设为默认助手，长按电源键/耳机键会投给本应用")
            !avail -> Item(
                "assistant-role", "默认数字助理", Level.BLOCKED,
                "本机系统不允许申请 ROLE_ASSISTANT（ROM 关闭了该角色）",
                "只能去系统设置里手动挑助手；部分 ROM 完全不给第三方",
                FixAction.NONE,
            )

            else -> Item(
                "assistant-role", "默认数字助理", Level.BLOCKED,
                "未设为默认助手 —— 系统不会把 ASSIST / VOICE_COMMAND 投给本应用",
                "点右侧按钮申请（会弹系统对话框）",
                FixAction.REQUEST_ASSISTANT_ROLE,
            )
        }
    }

    /** ASSIST 是否真能解析到我们（注册对了没有）。 */
    private fun assistIntent(ctx: Context): Item {
        val i = Intent(Intent.ACTION_ASSIST).addCategory(Intent.CATEGORY_DEFAULT)
        val r = runCatching {
            ctx.packageManager.queryIntentActivities(i, PackageManager.MATCH_DEFAULT_ONLY)
        }.getOrDefault(emptyList())
        val mine = r.any { it.activityInfo?.packageName == ctx.packageName }
        val detail = if (r.isEmpty()) "系统里没有任何应用注册 ASSIST" else
            "注册 ASSIST 的应用：" + r.mapNotNull { it.activityInfo?.packageName }.distinct().joinToString("/")
        return if (mine) Item("assist-action", "长按电源键", Level.OK, detail)
        else Item(
            "assist-action", "长按电源键", Level.WARN,
            detail + "（本应用未出现在解析结果里，但可能在 Service 侧注册 —— 以默认助手身份为准）",
            "确认已设为默认助手后再试",
            FixAction.NONE,
        )
    }

    /** VOICE_COMMAND：**耳机键长按系统发的就是它**。 */
    private fun voiceCommandIntent(ctx: Context): Item {
        // 这条 action 是投给 Service 的，queryIntentServices 才查得到
        val i = Intent(Intent.ACTION_VOICE_COMMAND)
        val svc = runCatching {
            ctx.packageManager.queryIntentServices(i, PackageManager.MATCH_DEFAULT_ONLY)
        }.getOrDefault(emptyList())
        val mine = svc.any { it.serviceInfo?.packageName == ctx.packageName }
        val names = svc.mapNotNull { it.serviceInfo?.let { s -> "${s.packageName}" } }.distinct()
        return if (mine) Item(
            "voice-command", "长按耳机播放键", Level.OK,
            "VOICE_COMMAND 已注册（" + names.joinToString("/") + "）",
        ) else Item(
            "voice-command", "长按耳机播放键", Level.WARN,
            if (svc.isEmpty()) "系统里没有应用注册 VOICE_COMMAND" else "注册 VOICE_COMMAND 的是：" + names.joinToString("/"),
            "需同时设为默认数字助理，系统才会把耳机键事件投过来",
            FixAction.NONE,
        )
    }

    private fun microphone(ctx: Context): Item {
        val granted = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        return if (granted) Item("mic", "麦克风权限", Level.OK, "已授权")
        else Item(
            "mic", "麦克风权限", Level.BLOCKED,
            "未授权 —— 语音球与语音唤醒都拿不到声音",
            "点右侧按钮授权",
            FixAction.REQUEST_MIC,
        )
    }

    /** 悬浮窗：语音球要浮在别的应用上面。 */
    private fun overlay(ctx: Context): Item {
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(ctx) else true
        return if (ok) Item("overlay", "悬浮窗权限", Level.OK, "已授权，语音球可浮在其他应用上")
        else Item(
            "overlay", "悬浮窗权限", Level.BLOCKED,
            "未授权 —— 语音球无法显示（会被降级为'打开对话'）",
            "点右侧按钮去系统授权",
            FixAction.REQUEST_OVERLAY,
        )
    }

    private fun notification(ctx: Context): Item {
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return if (ok) Item("notification", "通知权限", Level.OK, "已授权，语音球前台通知可显示")
        else Item(
            "notification", "通知权限", Level.WARN,
            "未授权 —— 前台服务通知会被系统隐藏（服务本身仍可运行）",
            "点右侧按钮授权",
            FixAction.REQUEST_NOTIFICATION,
        )
    }

    /** 电池优化 / 后台限制：常驻类功能被杀的主因。 */
    private fun foregroundService(ctx: Context): Item {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val ignoring = pm?.isIgnoringBatteryOptimizations(ctx.packageName) ?: false
        return if (ignoring) Item("battery", "后台不被限制", Level.OK, "已忽略电池优化")
        else Item(
            "battery", "后台不被限制", Level.WARN,
            "系统仍可能限制后台 —— 常驻语音监听/语音球容易被杀",
            "可在系统设置里对本应用关闭电池优化",
            FixAction.NONE,
        )
    }

    /** 快捷方式是否已发布。 */
    private fun shortcuts(ctx: Context): Item {
        val n = runCatching {
            ctx.getSystemService(android.content.pm.ShortcutManager::class.java)
                ?.let { it.dynamicShortcuts.size + it.manifestShortcuts.size } ?: 0
        }.getOrDefault(0)
        return if (n > 0) Item("shortcuts", "应用快捷方式", Level.OK, "已有 $n 条快捷方式")
        else Item(
            "shortcuts", "应用快捷方式", Level.WARN,
            "0 条快捷方式",
            "",
            FixAction.NONE,
        )
    }

}
