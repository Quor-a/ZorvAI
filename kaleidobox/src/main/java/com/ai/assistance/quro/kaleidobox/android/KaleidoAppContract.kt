package com.ai.assistance.quro.kaleidobox.android

import android.net.Uri

/**
 * 共享契约常量：宿主 App 与 KaleidoBox 运行时共用。
 *
 * 让 deep-link scheme / host、隐式 Action、Intent Extra 的 key 在两端保持一致，
 * 避免"真 Android 组件"（桌面图标 / 分享 / 通知 / 独立 Activity）与运行时各写一份、对不上。
 *
 * 典型用法：
 *   - 能力 `app.shortcut.create` 用 [deepLink] 拼一个指向某插件某 UI 表面的隐式 Intent，
 *     请求 launcher 固定到桌面；
 *   - [KaleidoActivity] 用 [EXTRA_PKG_ID] / [EXTRA_SURFACE_ID] 或 deep-link 取插件身份，
 *     全屏承载插件 UI。
 */
object KaleidoAppContract {
    const val DEEP_LINK_SCHEME = "kaleido"
    const val DEEP_LINK_HOST = "plugin"
    const val ACTION_VIEW_PLUGIN = "com.ai.assistance.quro.kaleidobox.ACTION_VIEW_PLUGIN"
    const val EXTRA_PKG_ID = "pkg_id"
    const val EXTRA_SURFACE_ID = "surface_id"
    const val EXTRA_TITLE = "title"

    /** 拼一个指向某插件某 UI 表面的 deep-link Uri（kaleido://plugin/<pkgId>?surface=<surfaceId>）。 */
    fun deepLink(pkgId: String, surfaceId: String? = null): Uri {
        val b = StringBuilder("$DEEP_LINK_SCHEME://$DEEP_LINK_HOST/")
        b.append(pkgId.trim().replace("/", "_"))
        if (!surfaceId.isNullOrBlank()) b.append("?surface=").append(surfaceId)
        return Uri.parse(b.toString())
    }
}
