package com.ai.assistance.quro.core.shizuku

import android.content.Context

/**
 * Shizuku 应用包名的唯一真相来源。
 *
 * ## 为什么需要这个类
 *
 * 主流 Shizuku（RikkaApps/Shizuku v12+）的 `applicationId` 是 **`moe.shizuku.privileged.api`**；
 * `moe.shizuku.manager` 只是 v11 及更早版本的旧包名。
 *
 * 历史上项目里散落着 4 处硬编码包名，其中两处只写了旧包名，导致：
 *   `getLaunchIntentForPackage("moe.shizuku.manager")` 在装了主流 Shizuku 的机器上返回 null
 *   → 落到 `market://details?id=moe.shizuku.manager` 把用户导去装一个不存在的应用
 *   → 用户视角就是「请求授权点了没反应」。
 *
 * 现在所有需要 Shizuku 包名的地方都必须经过本对象，禁止再硬编码字符串。
 *
 * ## 注意：包名 ≠ action 名
 *
 * Shizuku 的 Intent action（如 `moe.shizuku.manager.intent.action.REQUEST_PERMISSION`）
 * 是**协议常量**，即使在 v12+ 上依然以 `moe.shizuku.manager.` 为前缀，与 applicationId 无关。
 * 不要把 action 里的 `moe.shizuku.manager` 也「顺手改掉」——那会真的破坏协议。
 * action 常量统一放在 [Action] 里。
 */
object QuroShizukuPkg {

    /** v12+ 主流 Shizuku 的 applicationId。 */
    const val MAIN: String = "moe.shizuku.privileged.api"

    /** v11 及更早版本的旧 applicationId（部分老设备仍在用）。 */
    const val LEGACY: String = "moe.shizuku.manager"

    /** 探测顺序：先主流，后旧版。 */
    private val CANDIDATES: List<String> = listOf(MAIN, LEGACY)

    /**
     * 返回设备上**实际安装**的 Shizuku 包名；未安装返回 null。
     *
     * @param ctx 任意 Context（只用 packageManager，不持有引用）。
     */
    fun installed(ctx: Context): String? = CANDIDATES.firstOrNull { pkg ->
        runCatching { ctx.packageManager.getPackageInfo(pkg, 0) }.getOrNull() != null
    }

    /** Shizuku 是否已安装（任一包名命中即为已安装）。 */
    fun isInstalled(ctx: Context): Boolean = installed(ctx) != null

    /**
     * 商店安装页 Intent 用的包名。
     * 未安装时统一引导到 v12+ 主流包，避免把用户导去一个已下架的旧应用。
     */
    fun storePackage(): String = MAIN

    /** Shizuku 官网（商店不可用时的兜底引导，如 F-Droid / 无 GMS 设备）。 */
    const val HOMEPAGE: String = "https://shizuku.rikka.app/"

    /**
     * Shizuku 的 Intent action 协议常量。
     *
     * ⚠️ 这些字符串里的 `moe.shizuku.manager` 是**协议命名空间**，不是包名，
     * 在 v12+ 上依然如此。任何情况下都不要改成 [MAIN]。
     */
    object Action {
        const val REQUEST_PERMISSION: String = "moe.shizuku.manager.intent.action.REQUEST_PERMISSION"
        const val MAIN_ACTIVITY: String = "moe.shizuku.manager.intent.action.MAIN"
    }
}
