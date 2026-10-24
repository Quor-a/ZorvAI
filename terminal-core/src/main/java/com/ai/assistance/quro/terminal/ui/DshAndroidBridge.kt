package com.ai.assistance.quro.terminal.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.webkit.JavascriptInterface
import com.ai.assistance.quro.terminal.harness.DeepSeekHarness
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL

/**
 * `window.androidBridge` —— JS 桥（移植自参考实现 dsh-mobile-apk `AndroidBridge.kt`，去品牌化）。
 *
 * **方法面必须与上游逐字对齐**：WebView 的 JS 桥按**实参个数**匹配 Java 方法，页面以零参调用
 * 一个只有单参重载的方法时抛 `Error: Method not found`（参考实现 review C12/C24 真机实测）。
 * 因此本类保留上游全部 `@JavascriptInterface` 面；宿主未接线的能力一律 **fail-closed 结构化
 * 拒绝**（返回 `{"ok":false,"reason":…}` / false / 空串），绝不留空实现假装成功。
 *
 * 与上游的差异只在**接线深度**：引擎健康检查 / pick token / 系统深色 / 剪贴板 / 打开路径 等
 * 接到本宿主的 DeepSeekHarness 与系统能力上；通知中心、悬浮球、虚拟屏、Shizuku、root、
 * APK 自更新、隔离浏览器工作台在本版本不接线（默认拒绝形状）。
 *
 * 异步结果经 `window.__dshBridge.onDirectoryPicked(callbackId, path)` 回页面（由页面侧
 * `@dsh-android/dsh-host-web-compat` 的 PICKER_SCRIPT 提供，本壳不注入该脚本）。
 */
internal class DshAndroidBridge(
    private val context: Context,
    private val onPickRequest: (callbackId: String) -> Unit = {},
    private val onKeepScreen: (enable: Boolean) -> Unit = {},
    private val onNotify: (title: String, text: String) -> Unit = { _, _ -> },
    private val onAllFilesAccessRequest: () -> Unit = {},
    private val onGetSystemDark: () -> Boolean = { true },
    private val onSettingsPathRequest: () -> String = { "" },
    private val onSetImmersiveRequest: (enable: Boolean) -> Unit = {},
    private val onGetUiLanguage: () -> String = { "system" },
    private val onSetUiLanguage: (mode: String) -> String = { it },
    private val onGetImmersiveMode: () -> Boolean = { true },
    private val onCopyTextRequest: (text: String) -> Boolean = { false },
    private val onRestartEngine: () -> Boolean = { false },
    private val onShutdownToGuide: () -> Unit = {},
    private val onReloadWebUI: () -> Unit = {},
    private val onOpenConsole: () -> Unit = {},
    private val onOpenNativePath: (path: String) -> Boolean = { false },
    private val onOpenPathChooser: (path: String, mode: String?) -> String =
        { _, _ -> """{"ok":false,"reason":"host-unsupported"}""" },
    private val onIncomingWorkspacePath: () -> String = { "" },
) {

    // ── 身份 / 主题 / 引擎健康 ──────────────────────────────────────────

    /** 宿主构建戳（versionName 是用户的，锁死不动；区分包装靠它）。 */
    @JavascriptInterface
    fun version(): String = DeepSeekHarness.BUILD_STAMP

    /**
     * 首帧主题：兼容层在 head 解析期**同步**读它。
     *
     * **故意恒为深色，不读系统夜间模式**（与上游壳同口径）：harness 页面整块嵌在终端主题里
     * （TerminalTheme 的底色是写死的深色，和系统主题无关）。系统浅色时若照实返回 false，
     * 兼容层会把首帧画布刷成浅色 —— 页面还没装配完的那一两秒，用户在深色壳里看到一块白。
     */
    @JavascriptInterface
    fun getSystemDark(): Boolean = onGetSystemDark()

    /**
     * 引擎健康探针（页面「引擎未运行」提示与重试按钮的真源）。
     *
     * 口径与上游 `EngineProbe.check` 一致：**200/401/303 都算 running**（401 只是需要重新
     * 认证，不是引擎死了——上游看门狗曾因把 401 摘出 running 而强杀健康引擎，D3/W2 明令禁止）。
     * 同步执行（桥线程允许阻塞 IO），超时 800ms。
     */
    @JavascriptInterface
    fun checkEngine(): String {
        val harness = DeepSeekHarness.get(context)
        val port = harness.activePort()
        if (port <= 0 || !harness.isRunning()) {
            return JSONObject()
                .put("running", false)
                .put("listening", false)
                .put("auth", "unknown")
                .put("error", "engine-not-running")
                .toString()
        }
        return try {
            val conn = URL("http://127.0.0.1:$port/").openConnection(Proxy.NO_PROXY) as HttpURLConnection
            conn.connectTimeout = 800
            conn.readTimeout = 800
            conn.requestMethod = "GET"
            val code = conn.responseCode
            val running = code == 200 || code == 401 || code == 303
            JSONObject()
                .put("running", running)
                .put("code", code)
                .put("auth", when (code) {
                    200 -> "ok"
                    401 -> "required"
                    else -> "unknown"
                })
                .put("listening", true)
                .toString()
        } catch (e: Exception) {
            // TCP 层兜底：HTTP 探针失败时再看端口通不通（区分「进程活着但 HTTP 挂」与「端口都没listen」）。
            val portAlive = try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 400) }
                true
            } catch (_: Exception) {
                false
            }
            JSONObject()
                .put("running", portAlive)
                .put("listening", false)
                .put("auth", "unknown")
                .put("error", e.javaClass.simpleName)
                .toString()
        }
    }

    /** 重启引擎进程（杀旧进程并由上层重新拉起）。返回**是否真的发起了**重启。 */
    @JavascriptInterface
    fun restartEngine(): Boolean = onRestartEngine()

    /** 关闭 harness：停引擎并退回上层界面（不自动重启）。 */
    @JavascriptInterface
    fun shutdownToGuide() {
        onShutdownToGuide()
    }

    // ── 屏幕 / 通知 ─────────────────────────────────────────────────────

    @JavascriptInterface
    fun keepScreenOn(enable: Boolean) {
        onKeepScreen(enable)
    }

    @JavascriptInterface
    fun showNotification(title: String, text: String) {
        onNotify(title, text)
    }

    // ── 目录选择 / 路径 ─────────────────────────────────────────────────

    @JavascriptInterface
    fun pickDirectory(callbackId: String) {
        onPickRequest(callbackId)
    }

    /** 宿主设置文档的绝对路径（`$DSH_HOME/settings.yaml`），不可用时空串。 */
    @JavascriptInterface
    fun settingsPath(): String = onSettingsPathRequest()

    /** 沉浸式开关（设置 → 通用设置）。本页常驻全屏 WebView，写侧为空操作。 */
    @JavascriptInterface
    fun setImmersiveMode(enable: Boolean) {
        onSetImmersiveRequest(enable)
    }

    /** 沉浸式读面：页面以壳侧值为唯一初值。 */
    @JavascriptInterface
    fun getImmersiveMode(): Boolean = onGetImmersiveMode()

    /** 当前 UI 语言（system / zh / en）；壳侧偏好是真源。 */
    @JavascriptInterface
    fun getUiLanguage(): String = onGetUiLanguage()

    /** 存 UI 语言并返回存储后的模式（调用方随后重载页面）。 */
    @JavascriptInterface
    fun setUiLanguage(mode: String): String = onSetUiLanguage(mode)

    /** 原生剪贴板写入（WebView 的 Clipboard API 在 Android 上恒被拒）。 */
    @JavascriptInterface
    fun copyText(text: String): Boolean = onCopyTextRequest(text)

    /** 配置导出（本宿主未接线：fail-closed 结构化拒绝）。 */
    @JavascriptInterface
    fun exportConfig(): String = """{"ok":false,"error":"bridge not wired"}"""

    /** 配置导入（本宿主未接线：fail-closed 结构化拒绝）。 */
    @JavascriptInterface
    fun importConfig(): String = """{"ok":false,"error":"bridge not wired"}"""

    /** 是否持有「所有文件访问」特殊权限（外部工作区要求）。 */
    @JavascriptInterface
    fun hasAllFilesAccess(): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        return Environment.isExternalStorageManager()
    }

    /** 系统「打开方式」选择器（本宿主未提供 SAF→路径 映射：结构化拒绝，页面回退 openNativePath）。 */
    @JavascriptInterface
    fun openPathChooser(path: String, mode: String?): String = onOpenPathChooser(path, mode)

    /** 打开系统「所有文件访问」授权页。 */
    @JavascriptInterface
    fun requestAllFilesAccess() {
        onAllFilesAccessRequest()
    }

    /** dir-pick 端点的一次性鉴权 token（与引擎进程环境里同一份；缺它 compat fail-closed）。 */
    @JavascriptInterface
    fun getPickToken(): String = DeepSeekHarness.get(context).currentPickToken().orEmpty()

    /** 页面「刷新界面」按钮：整页重载（含重新走 token 交换）。 */
    @JavascriptInterface
    fun reloadWebUI() {
        onReloadWebUI()
    }

    /** 打开内置控制台（本宿主未提供该入口：记日志即可）。 */
    @JavascriptInterface
    fun openConsole() {
        onOpenConsole()
    }

    /** 开发者日志开关（本宿主未接线：恒 false，不假装成功）。 */
    @JavascriptInterface
    fun getDevLogEnabled(): Boolean = false

    @JavascriptInterface
    fun setDevLogEnabled(enabled: Boolean) {
        // 本宿主无日志采集器开关，空操作。
    }

    /**
     * 用外部阅读器打开文件系统路径（ACTION_VIEW；引擎原生 opener 只认桌面平台）。
     * 返回是否有阅读器接走；调用方在 false 时回退到引擎 RPC。
     */
    @JavascriptInterface
    fun openNativePath(path: String): Boolean = onOpenNativePath(path)

    /** 外部附件草稿的受信任 UI-only 会话 cwd；永不回源文件路径。 */
    @JavascriptInterface
    fun incomingWorkspacePath(): String = onIncomingWorkspacePath()

    // ── 以下能力本版本不接线：全部 fail-closed 结构化拒绝 ─────────────────

    @JavascriptInterface
    fun getOverlayEnabled(): Boolean = false

    @JavascriptInterface
    fun setOverlayEnabled(enable: Boolean): Boolean = false

    @JavascriptInterface
    fun getScreenScope(): String = "virtual-only"

    @JavascriptInterface
    fun setScreenScope(scope: String): String = "virtual-only"

    @JavascriptInterface
    fun browserHostStatus(): String = """{"ok":false,"reason":"browser-host-not-wired"}"""

    @JavascriptInterface
    fun browserHostCommand(payload: String): String =
        """{"ok":false,"available":false,"reason":"browser-host-not-wired"}"""

    @JavascriptInterface
    fun browserHostShow(url: String?): String = """{"ok":false,"reason":"browser-host-not-wired"}"""

    /** 零参重载：桥按实参个数匹配，页面确实会零参调用（上游 review C12/C24 实测）。 */
    @JavascriptInterface
    fun browserHostShow(): String = """{"ok":false,"reason":"browser-host-not-wired"}"""

    @JavascriptInterface
    fun browserHostHide(): String = """{"ok":false,"reason":"browser-host-not-wired"}"""

    @JavascriptInterface
    fun browserHostReload(): String = """{"ok":false,"reason":"browser-host-not-wired"}"""

    @JavascriptInterface
    fun browserHostBounds(bounds: String): String = """{"ok":false,"reason":"browser-host-not-wired"}"""

    @JavascriptInterface
    fun browserHostViewport(viewport: String): String = """{"ok":false,"reason":"browser-host-not-wired"}"""

    @JavascriptInterface
    fun browserHostClose(): String = """{"ok":false,"reason":"browser-host-not-wired"}"""

    @JavascriptInterface
    fun browserHostIdentity(payload: String): String = """{"ok":false,"reason":"browser-host-not-wired"}"""

    @JavascriptInterface
    fun vdisplayStatus(): String = """{"ok":false,"code":"vdisplay-not-wired"}"""

    @JavascriptInterface
    fun vdisplayCreate(): String = """{"ok":false,"code":"vdisplay-not-wired"}"""

    @JavascriptInterface
    fun vdisplayDestroy(): String = """{"ok":false,"code":"vdisplay-not-wired"}"""

    @JavascriptInterface
    fun vdisplayBounds(bounds: String): String = """{"ok":false,"code":"vdisplay-not-wired"}"""

    @JavascriptInterface
    fun vdisplaySelect(alias: String): String = """{"ok":false,"code":"vdisplay-not-wired"}"""

    @JavascriptInterface
    fun getVdisplayScale(): Double = 0.75

    @JavascriptInterface
    fun setVdisplayScale(value: Double): Double = value

    @JavascriptInterface
    fun getVdisplayFloatEnabled(): Boolean = true

    @JavascriptInterface
    fun setVdisplayFloatEnabled(enable: Boolean): Boolean = enable

    @JavascriptInterface
    fun forceDestroyVdisplay(): String = """{"ok":false,"code":"vdisplay-not-wired"}"""

    @JavascriptInterface
    fun a11yStatus(): String = """{"enabled":false}"""

    @JavascriptInterface
    fun openA11ySettings() {
        // 本宿主未接无障碍控制通道。
    }

    @JavascriptInterface
    fun unlockRestrictedSettings(): String = """{"ok":false,"message":"未接线"}"""

    /**
     * 打开登记在册的外链。页面只能传 key，URL 表在壳侧；未登记一律拒收。
     * 本宿主未登记任何外链（shizuku 下载/教程等页不存在），恒 unknown-key。
     */
    @JavascriptInterface
    fun openExternalLink(key: String): String = """{"ok":false,"reason":"unknown-key"}"""

    @JavascriptInterface
    fun openShizukuManager(): String = """{"ok":false,"reason":"bridge not wired"}"""

    @JavascriptInterface
    fun shizukuStatus(): String = """{"ok":false,"code":"shizuku-not-wired"}"""

    @JavascriptInterface
    fun resetShizukuConnection(): String = """{"ok":false,"code":"shizuku-not-wired"}"""

    @JavascriptInterface
    fun rootGrantState(): String =
        """{"ok":false,"reason":"no-shell-context","canToggle":false,"channelRoot":false}"""

    @JavascriptInterface
    fun setRootGranted(on: Boolean): String = """{"ok":false,"reason":"no-shell-context"}"""

    @JavascriptInterface
    fun setRootConsent(on: Boolean): String = """{"ok":false,"reason":"no-shell-context"}"""

    @JavascriptInterface
    fun openRootDisclaimer(): String = """{"ok":false,"reason":"bridge not wired"}"""

    @JavascriptInterface
    fun requestShizukuPermission(): String = """{"ok":false,"code":"shizuku-not-wired"}"""

    @JavascriptInterface
    fun rootAccessState(): String =
        """{"ok":false,"reason":"no-shell-context","state":"unknown","granted":false}"""

    @JavascriptInterface
    fun requestRootAccess(): String =
        """{"ok":false,"reason":"no-shell-context"}"""

    @JavascriptInterface
    fun repairRootOwnership(): String =
        """{"ok":false,"reason":"no-shell-context"}"""

    @JavascriptInterface
    fun getNotifySetting(key: String?): String =
        """{"ok":false,"reason":"no-shell-context"}"""

    @JavascriptInterface
    fun setNotifySetting(key: String, value: Boolean): String =
        """{"ok":false,"reason":"no-shell-context"}"""

    @JavascriptInterface
    fun apkUpdate(action: String): String =
        """{"ok":false,"state":"idle","reason":"bridge not wired"}"""

    @JavascriptInterface
    fun notifySelfCheck(): String =
        """{"ok":false,"reason":"no-shell-context"}"""

    @JavascriptInterface
    fun openNotifyAppSettings(): Boolean = false

    @JavascriptInterface
    fun openNotifyChannelSettings(channelId: String): Boolean = false

    @JavascriptInterface
    fun notifySendTest(): Int = 0

    @JavascriptInterface
    fun linuxAssetPrepare(specJson: String): String =
        """{"ok":false,"reason":"bridge not wired"}"""

    @JavascriptInterface
    fun linuxAssetPrepareStatus(): String =
        """{"ok":false,"reason":"bridge not wired"}"""

    companion object {
        /**
         * JSON 字符串字面量转义（`JSONObject.quote` 不转义 U+2028/U+2029，而 ES2019 之前
         * 行分隔符出现在字符串字面量里是 SyntaxError —— 注入脚本会整体解析失败）。
         */
        internal fun jsQuote(value: String): String {
            val quoted = JSONObject.quote(value)
            return quoted
                .replace("\u2028", "\\u2028")
                .replace("\u2029", "\\u2029")
        }

        /** 用 ACTION_VIEW 把路径交给外部应用（内部助手：桥与选择器回调共用）。 */
        internal fun openPathExternally(context: Context, path: String): Boolean {
            val uri: Uri = try {
                Uri.parse(path)
            } catch (_: Exception) {
                return false
            }
            val mime = guessMime(path)
            return try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            } catch (_: ActivityNotFoundException) {
                false
            } catch (_: Exception) {
                false
            }
        }

        private fun guessMime(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "pdf" -> "application/pdf"
            "txt", "md", "log" -> "text/plain"
            "json" -> "application/json"
            "html", "htm" -> "text/html"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "zip" -> "application/zip"
            else -> "*/*"
        }

        /** 剪贴板写入（内部助手）。 */
        internal fun writeClipboard(context: Context, text: String): Boolean {
            if (text.isEmpty()) return false
            return try {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    ?: return false
                cm.setPrimaryClip(ClipData.newPlainText("dsh", text))
                true
            } catch (_: Exception) {
                false
            }
        }
    }
}

/**
 * 界面语言偏好（移植自参考实现 `AppLanguage` 的偏好面，去品牌化）。
 *
 * 三档：跟随系统 / 中文 / 英文。壳侧偏好是唯一真源；引擎页面经 document-start 的
 * `__DSH_LOCALE__` 引导脚本与桥 `getUiLanguage()` / `setUiLanguage()` 读写同一份值。
 */
internal object DshUiLanguage {
    const val MODE_SYSTEM = "system"
    const val MODE_ZH = "zh"
    const val MODE_EN = "en"

    private val MODES = listOf(MODE_SYSTEM, MODE_ZH, MODE_EN)

    private const val PREFS = "dsh_ui_language"
    private const val KEY_MODE = "mode"

    /** 存储的模式；读不出来（preference 损坏等）退回跟随系统。 */
    fun get(context: Context): String = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, MODE_SYSTEM) ?: MODE_SYSTEM
    } catch (_: Exception) {
        MODE_SYSTEM
    }

    /** 只收三档；别的值一律当成跟随系统。返回规范化后的模式。 */
    fun set(context: Context, mode: String): String {
        val normalized = if (mode in MODES) mode else MODE_SYSTEM
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_MODE, normalized).apply()
        } catch (_: Exception) {
            // 持久化失败：本次会话仍返回应显示的模式
        }
        return normalized
    }
}
