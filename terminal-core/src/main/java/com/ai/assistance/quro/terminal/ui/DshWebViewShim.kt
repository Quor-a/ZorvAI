package com.ai.assistance.quro.terminal.ui

import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/**
 * WebView 统一 shim（移植自参考实现 dsh-mobile-apk `WebViewShim.kt`，去品牌化）。
 *
 * 壳侧所有承载动态页面的 WebView 的「版本敏感面」唯一入口：
 *  - 内核包回读（getCurrentWebViewPackage + 主版本号正则）；
 *  - `SDK_INT >= 29 → forceDark` 的深色跟随按 **provider 能力** 判，不按系统版本猜
 *    （API 26–28 + 新内核这类本可跟随的设备不会被错判）；
 *  - androidx.webkit 能力门单面实现。
 *
 * 设计边界（与上游一致，有意收窄）：
 *  - 不做 WebView 子类或包装类——构造器在某些 ROM 上自身就是故障源
 *    （provider 缺失/升级中），包装层解决不了，只会多一个被怀疑对象。
 */
internal object DshWebViewShim {

    // ── 内核 provider 回读 ──────────────────────────────────────────────

    /** 内核包名（如 com.google.android.webview）；读不到回空串（显式未知，不抛）。 */
    fun providerPackageName(): String = try {
        WebView.getCurrentWebViewPackage()?.packageName ?: ""
    } catch (_: Throwable) {
        // provider 正在切换/更新时该调用可抛——回读通路不得成为故障源。
        ""
    }

    /** 内核版本名（形如 "110.0.5481.154.1"）；读不到回空串。 */
    fun providerVersionName(): String = try {
        WebView.getCurrentWebViewPackage()?.versionName ?: ""
    } catch (_: Throwable) {
        ""
    }

    /**
     * WebView provider 是否在场。false 有两种真实情形：设备没有 WebView
     * （无 Google 服务的精简 ROM / AOSP 裁剪机），或 provider 正在升级窗口期
     * （此窗口内 `WebView()` 构造本身会抛）。
     */
    fun providerAvailable(): Boolean = providerPackageName().isNotEmpty()

    /** 「首个点分段数字」主版本号解析。版本串异形或空串一律回 0。 */
    internal fun majorOf(versionName: String): Int =
        Regex("(\\d+)\\.").find(versionName)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    /**
     * 当前 WebView 实际 User-Agent 里的 Chromium 版本。
     * 厂商 provider 包版本（如华为的 `14.0.0.370`）不是 Chromium 版本，
     * 不能与 Chromium 口径的语法门槛比较——三态结果防止把厂商包版本误判成门槛失败。
     */
    internal fun chromiumVersionFromUserAgent(userAgent: String): String =
        Regex("(?:^|\\s)Chrome/([0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+)")
            .find(userAgent)?.groupValues?.get(1).orEmpty()

    /** 三态：unknown / true / false。 */
    internal fun syntaxFloorStatus(chromiumVersion: String, floorMajor: Int): String {
        val major = majorOf(chromiumVersion)
        return when {
            major <= 0 -> "unknown"
            major >= floorMajor -> "true"
            else -> "false"
        }
    }

    // ── androidx.webkit 能力门 ──────────────────────────────────────────

    /** 能力门按**当前 WebView provider** 的实际能力判，失败回 false（不抛）。 */
    fun supports(feature: String): Boolean = try {
        WebViewFeature.isFeatureSupported(feature)
    } catch (_: Throwable) {
        false
    }

    // ── settings 配置档 ─────────────────────────────────────────────────

    /**
     * 共享基线档：承载引擎页的 WebView 必须一致的最小安全与行为集合，版本差异在本函数内一次消化。
     *
     *  - LOAD_NO_CACHE：杜绝 WebView 命中旧 index/旧 bundle 造成「卡 loading 且无诊断层」
     *    （缓存页里没有页面看门狗；荣耀/MagicUI 实测类问题）。
     *  - mediaPlaybackRequiresUserGesture 显式钉 true：值与平台默认一致，但钉死它才能免疫
     *    厂商 ROM 改默认值的漂移。
     *  - appOwnsTheme：引擎 UI 通过主题服务自行配色，禁止 WebView 再次算法改色。
     */
    fun applyBaseline(s: WebSettings, appOwnsTheme: Boolean = false) {
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        // file 方案内容访问全关；file:///android_asset 与 /android_res 不受此限。
        s.allowFileAccess = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.cacheMode = WebSettings.LOAD_NO_CACHE
        s.mediaPlaybackRequiresUserGesture = true
        applyFollowSystemDark(s, appOwnsTheme)
        applyOffscreenPreraster(s)
    }

    /**
     * 引擎 UI 自己拥有浅色/深色/跟随系统主题，必须禁用算法改色，否则系统深色下 CSS 白底会被
     * 再次压暗，手动浅色看起来仍是黑色。引擎的系统偏好由 ThemeBridge 提供，不依赖算法改色。
     *
     * 两层按 **provider 能力**择优，不按 SDK_INT 猜：
     *  1. ALGORITHMIC_DARKENING（webkit 1.5+/API 33 推荐路径）：supported 时只用这一路；
     *  2. FORCE_DARK（旧路径）：API 26–28 + 新内核也能跟随；
     *  3. 两路都不支持：provider 过旧，留默认（不假装跟随）。
     */
    private fun applyFollowSystemDark(s: WebSettings, appOwnsTheme: Boolean) {
        // 包一层：provider 更新窗口期内「特性门过、setter 抛」的竞态如实放弃（留默认）。
        try {
            when {
                supports(WebViewFeature.ALGORITHMIC_DARKENING) ->
                    WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, !appOwnsTheme)
                supports(WebViewFeature.FORCE_DARK) ->
                    @Suppress("DEPRECATION")
                    WebSettingsCompat.setForceDark(
                        s,
                        if (appOwnsTheme) WebSettingsCompat.FORCE_DARK_OFF else WebSettingsCompat.FORCE_DARK_AUTO,
                    )
            }
        } catch (_: Throwable) {
        }
    }

    /** 离屏预光栅：旧内核/低端机的滚动流畅度实测有效（provider 不支持即跳过，不抛）。 */
    private fun applyOffscreenPreraster(s: WebSettings) {
        try {
            if (supports(WebViewFeature.OFF_SCREEN_PRERASTER)) {
                WebSettingsCompat.setOffscreenPreRaster(s, true)
            }
        } catch (_: Throwable) {
        }
    }
}
