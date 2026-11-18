package com.ai.assistance.quro.core.hint

import android.content.Context
import java.io.File

/**
 * 一条外部投递的待处理提示。
 *
 * [id] 由投递方提供（缺省时由通道生成稳定值），用于去重与 ack；[originFile] 仅目录通道有值，
 * AI 处理完一轮后由分发器归档，避免重复提醒与目录堆积。
 */
data class ExternalHint(
    val id: String,
    val text: String,
    val source: String,
    val priority: Int = 0,
    val ts: Long = System.currentTimeMillis(),
    val originFile: File? = null,
) {
    val urgent: Boolean get() = priority >= 1
}

/**
 * 外部提示的全部用户偏好。与设置页共用 `quro_ui` 这一份 SharedPreferences，
 * 目录通道、广播通道、ACI 通道读的是同一组开关，不另建配置源。
 */
object ExternalHintPrefs {
    private const val PREFS = "quro_ui"

    const val KEY_ENABLED = "quro_inbox_enabled"
    const val KEY_MUTED = "quro_inbox_muted"
    const val KEY_PATH = "quro_inbox_path"
    const val KEY_AUTO_WAKE = "quro_hint_auto_wake"
    const val KEY_MERGE_WINDOW = "quro_hint_merge_window_ms"
    const val KEY_BROADCAST = "quro_hint_broadcast_enabled"
    const val KEY_ACI = "quro_hint_aci_enabled"
    const val KEY_MUTED_SOURCES = "quro_hint_muted_sources"
    const val KEY_ALLOWED_PACKAGES = "quro_hint_allowed_packages"

    const val DEFAULT_MERGE_WINDOW_MS = 10_000L

    fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, true)

    fun muted(context: Context) = prefs(context).getBoolean(KEY_MUTED, false)

    fun autoWake(context: Context) = prefs(context).getBoolean(KEY_AUTO_WAKE, true)

    fun broadcastEnabled(context: Context) = prefs(context).getBoolean(KEY_BROADCAST, true)

    fun aciEnabled(context: Context) = prefs(context).getBoolean(KEY_ACI, true)

    /** 0 表示不做合并，每条提示立即注入。 */
    fun mergeWindowMs(context: Context): Long =
        prefs(context).getLong(KEY_MERGE_WINDOW, DEFAULT_MERGE_WINDOW_MS).coerceIn(0L, 60_000L)

    /** 默认落在外部私有目录，PC / 脚本 / 有文件权限的 App 都能写；用户也可指定任意可写目录。 */
    fun inboxDir(context: Context): File {
        val custom = prefs(context).getString(KEY_PATH, null)
        if (!custom.isNullOrBlank()) return File(custom)
        val external = context.getExternalFilesDir(null)
        return File(external ?: context.filesDir, "quro_inbox")
    }

    fun mutedSources(context: Context): List<String> = split(prefs(context).getString(KEY_MUTED_SOURCES, null))

    fun sourceMuted(context: Context, source: String): Boolean =
        source.isNotBlank() && mutedSources(context).any { it.equals(source, ignoreCase = true) }

    fun toggleMutedSource(context: Context, source: String) {
        val cur = mutedSources(context).toMutableList()
        if (cur.any { it.equals(source, ignoreCase = true) }) {
            cur.removeAll { it.equals(source, ignoreCase = true) }
        } else if (source.isNotBlank()) {
            cur.add(source.trim())
        }
        prefs(context).edit().putString(KEY_MUTED_SOURCES, cur.joinToString(",")).apply()
    }

    fun allowedPackages(context: Context): List<String> = split(prefs(context).getString(KEY_ALLOWED_PACKAGES, null))

    /** ACI 调用方校验：本应用、受控浏览器，或用户在设置里显式授权的包名。 */
    fun isCallerAllowed(context: Context, callerPkg: String?): Boolean {
        if (callerPkg.isNullOrBlank()) return false
        return callerPkg == context.packageName ||
            callerPkg == "com.ai.assistance.quro.browser" ||
            allowedPackages(context).any { it == callerPkg }
    }

    private fun split(raw: String?): List<String> = raw.orEmpty()
        .split(',', '\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}
