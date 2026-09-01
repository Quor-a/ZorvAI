package com.ai.assistance.quro.core.approle

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.ai.assistance.quro.BuildConfig

/**
 * 默认应用角色管理（原创，无外部依赖）。
 *
 * 覆盖用户要求的 8 项：桌面启动器(HOME) / 浏览器(BROWSER) / 相册 / 视频 / 邮箱 / 文档 / 消息(SMS) / 拨号(DIALER)。
 *
 * ## 两类角色
 * - **平台角色**（[platformRole] 非空）：HOME / BROWSER / DIALER / SMS。
 *   经 `RoleManager`（API 29+）申请与查询，系统弹角色选择/确认框。
 * - **非平台角色**（相册 / 视频 / 邮箱 / 文档）：Android 无对应 RoleManager 角色，
 *   靠 `QuroDefaultAppHandlerActivity` 在 Manifest 注册的 `<intent-filter>` 成为候选，
 *   由本类构造一个隐式意图触发系统选择器，用户选「Zorv AI + 总是」即设为默认。
 *
 * ## 安全约束
 * HOME（桌面启动器）需要真正的桌面 UI 才能作为可用启动器。本项目未实现桌面 UI，
 * 故 [HOME] 仅提供 RoleManager 申请入口（系统若无合格 home 过滤器不会把它列为候选，安全 no-op），
 * 不在 Manifest 注册 `CATEGORY_HOME` 过滤器，避免把用户主页按钮「砖」成无界面 Activity。
 */
enum class DefaultAppRole(
    val id: String,
    val label: String,
    val desc: String,
    val platformRole: String?,
) {
    HOME("home", "默认桌面启动器", "接管系统桌面 / 主屏", RoleManager.ROLE_HOME),
    BROWSER("browser", "默认浏览器", "打开网页链接", RoleManager.ROLE_BROWSER),
    DIALER("dialer", "默认拨号", "拨打电话", RoleManager.ROLE_DIALER),
    SMS("sms", "默认短信", "收发短信", RoleManager.ROLE_SMS),
    GALLERY("gallery", "默认相册", "查看图片", null),
    VIDEO("video", "默认视频", "播放视频", null),
    EMAIL("email", "默认邮箱", "收发邮件", null),
    DOCUMENT("document", "默认文档", "打开文档", null),
    ;

    /** 图标名（UI 层映射为 Material 图标）。 */
    val iconName: String
        get() = when (id) {
            "home" -> "home"
            "browser" -> "public"
            "dialer" -> "call"
            "sms" -> "sms"
            "gallery" -> "image"
            "video" -> "movie"
            "email" -> "email"
            "document" -> "description"
            else -> "apps"
        }
}

object QuroDefaultAppManager {

    /** 平台角色：本应用当前是否就是该角色持有者（RoleManager 持有判定）。 */
    fun isHeld(ctx: Context, role: DefaultAppRole): Boolean {
        val pr = role.platformRole ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val rm = ctx.getSystemService(Context.ROLE_SERVICE) as? RoleManager ?: return false
        val rm2: RoleManager = rm
        return runCatching { rm2.isRoleHeld(pr) }.getOrDefault(false)
    }

    /** 平台角色：当前默认持有者包名（用于展示"当前默认"）。非平台角色返回 null。
     *  android-36 的 RoleManager 仅暴露 isRoleHeld / createRequestRoleIntent，
     *  无 getRoleHolders，故"当前持有者"在 isRoleHeld 为真时即本应用包名，否则为 null。 */
    fun currentHolder(ctx: Context, role: DefaultAppRole): String? {
        return if (isHeld(ctx, role)) selfPackage() else null
    }

    /** 本应用包名（供判断"当前默认是不是本应用"）。 */
    fun selfPackage(): String = BuildConfig.APPLICATION_ID

    /**
     * 构造「设为默认」意图：
     * - 平台角色（API 29+）→ `RoleManager.createRequestRoleIntent(role)`，系统弹角色确认框。
     * - 非平台角色 → 隐式意图触发系统选择器，用户选 Zorv AI + 总是。
     * 返回 null 表示当前平台不支持（如 API < 29 且无 RoleManager）。
     */
    fun requestIntent(ctx: Context, role: DefaultAppRole): Intent? {
        if (role.platformRole != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = ctx.getSystemService(Context.ROLE_SERVICE) as? RoleManager ?: return null
            return runCatching { rm.createRequestRoleIntent(role.platformRole) }.getOrNull()
        }
        return triggerIntent(role)
    }

    /** 非平台角色：触发系统选择器（隐式意图，NEW_TASK）。 */
    private fun triggerIntent(role: DefaultAppRole): Intent = when (role.id) {
        "gallery" -> Intent(Intent.ACTION_VIEW).setType("image/*")
        "video" -> Intent(Intent.ACTION_VIEW).setType("video/*")
        "email" -> Intent(Intent.ACTION_SENDTO).setData(Uri.parse("mailto:"))
        "document" -> Intent(Intent.ACTION_VIEW).setType("application/pdf")
        else -> Intent(Intent.ACTION_VIEW)
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
