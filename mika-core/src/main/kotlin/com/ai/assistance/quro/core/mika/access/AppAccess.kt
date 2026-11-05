package com.ai.assistance.quro.core.mika.access

/** 应用分类仅用于整理列表,不决定执行权限。名字按界面语言放在 app 侧资源里(values/strings_appaccess.xml)。 */
enum class AppCategory {
    Finance, Social, Game, Shopping, Productivity, Media, Other, Unknown;
}

data class AppClassification(val category: AppCategory, val reason: String = "", val manual: Boolean = false)

data class AppAccessState(
    val blocked: Set<String> = emptySet(),
    val shareAllowed: Set<String> = emptySet(),
    val classifications: Map<String, AppClassification> = emptyMap(),
    val available: Boolean = true,
    val revision: Long = 0,
) {
    fun denial(packageName: String?): AppAccessDenied? = when {
        !available -> AppAccessDenied("app_access_unavailable", null)
        packageName.isNullOrBlank() && blocked.isNotEmpty() -> AppAccessDenied("app_access_unverified", null)
        packageName in blocked -> AppAccessDenied("app_blocked", packageName)
        else -> null
    }

    fun canShare(packageName: String): Boolean = available && (packageName !in blocked || packageName in shareAllowed)
}

/** 每次读取最新状态,不在任务开始时冻结权限。 */
fun interface AppAccessPolicy {
    fun current(): AppAccessState

    companion object {
        val ALLOW = AppAccessPolicy { AppAccessState() }
    }
}

class AppAccessDenied(val code: String, val packageName: String?) : RuntimeException(
    when (code) {
        "app_blocked" ->
            "Application $packageName is disabled by the user in Application access. Do not retry it, read it again, or try to bypass this with shell or another app. " +
                "Reconsider the plan: if another app, screen, share target or web route can still achieve the user's goal, continue there. " +
                "If this app is required, stop and tell the user plainly that this app is disabled in Settings > Application access and can be allowed there; do not repeat this error or show its code."
        "app_access_protected" ->
            "This is an authorization or screen-sharing interface that only the user may operate. Stop and ask the user to handle this step. Do not tap or type here."
        "app_access_unavailable" ->
            "Application access settings are unavailable, so permission cannot be checked. Do not use phone tools. Tell the user to repair or reset Application access in Settings."
        else ->
            "Cannot verify which application is in front. This operation was not performed and no permission was changed. Observe the screen again to identify the app; if it stays uncertain, stop and ask the user."
    },
) {
    companion object {
        /** 应用访问拒绝不是普通操作失败:要交给模型,不能当成任务异常。 */
        fun isAppAccessCode(code: String): Boolean = code == "app_blocked" || code.startsWith("app_access_")
    }
}
