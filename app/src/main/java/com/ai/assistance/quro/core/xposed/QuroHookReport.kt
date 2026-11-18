package com.ai.assistance.quro.core.xposed

import android.content.ContentResolver
import android.content.Context
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 跨进程的「Hook 到底装上了什么」记录。
 *
 * ## 为什么需要它
 * Hook 跑在 system_server / 厂商 App 里，日志只进框架的 Xposed 日志。用户装完打开设置页，
 * 看到的是「开关开着」，但完全不知道钩子是装上了、目标缺失，还是装上了却从来没被调用。
 * 这三件事以前的实现全都无法区分，所以每次「不能用」都只能靠猜。
 *
 * ## 通道
 * 写入 `Settings.Secure` 的自定义键：Hook 进程（system_server 有系统权限）直接写，
 * 应用进程直接读。不依赖 root、不依赖日志、不需要 adb。
 * 每个功能域各占一个槽位，读-改-写，保留最近 [MAX_EVENTS] 条触发事件。
 *
 * ## 边界
 * 一律 runCatching：写不进去（厂商进程没有 WRITE_SECURE_SETTINGS）就当没有诊断，
 * 绝不因为记录失败影响被 Hook 的宿主进程。
 */
internal object QuroHookReport {

    /** 自定义键名；应用侧读同一个键。 */
    const val SECURE_KEY = "quro_hook_report_v1"

    private const val MAX_EVENTS = 24
    private const val MAX_DETAIL_CHARS = 160

    // ---- Hook 侧写入 ----

    /** 记录一个功能域的安装结果。安装完成后调用一次。 */
    fun publish(context: Context?, group: String, entries: List<QuroHookEntry>) {
        if (context == null) return
        mutate(context) { root ->
            val groups = root.optJSONObject("groups") ?: org.json.JSONObject().also {
                root.put("groups", it)
            }
            val items = org.json.JSONArray()
            entries.take(48).forEach { entry ->
                items.put(
                    org.json.JSONObject()
                        .put("id", entry.id)
                        .put("status", entry.status.name)
                        .put("desc", entry.description.take(MAX_DETAIL_CHARS))
                        .put("detail", entry.detail?.take(MAX_DETAIL_CHARS)),
                )
            }
            val counts = entries.groupingBy { it.status }.eachCount()
            groups.put(
                group,
                org.json.JSONObject()
                    .put("installed", counts[QuroHookStatus.INSTALLED] ?: 0)
                    .put("missing", counts[QuroHookStatus.MISSING] ?: 0)
                    .put("failed", counts[QuroHookStatus.FAILED] ?: 0)
                    .put("skipped", counts[QuroHookStatus.SKIPPED] ?: 0)
                    .put("items", items),
            )
            root.put("updatedAt", System.currentTimeMillis())
        }
    }

    /**
     * 记录一次真实触发。
     *
     * 这是区分「钩子装上了但没被调用」与「钩子被调用了但决策失败」的唯一手段：
     * 事件为空说明厂商/系统根本没走到这个入口；事件有而结果失败，才说明是我们的逻辑问题。
     */
    fun recordEvent(context: Context?, what: String, detail: String?) {
        if (context == null) return
        mutate(context) { root ->
            val events = root.optJSONArray("events") ?: org.json.JSONArray().also {
                root.put("events", it)
            }
            val next = org.json.JSONArray()
            // 新的在前，超出上限丢最旧的。
            next.put(
                org.json.JSONObject()
                    .put("at", System.currentTimeMillis())
                    .put("what", what.take(32))
                    .put("detail", detail?.take(MAX_DETAIL_CHARS)),
            )
            for (i in 0 until minOf(events.length(), MAX_EVENTS - 1)) {
                next.put(events.opt(i))
            }
            root.put("events", next)
            root.put("updatedAt", System.currentTimeMillis())
        }
    }

    private fun mutate(context: Context, block: (org.json.JSONObject) -> Unit) {
        runCatching {
            val resolver = context.contentResolver
            val raw = readRaw(resolver)
            val root = if (raw.isNullOrBlank()) org.json.JSONObject() else {
                runCatching { org.json.JSONObject(raw) }.getOrElse { org.json.JSONObject() }
            }
            block(root)
            writeRaw(resolver, root.toString())
        }
    }

    // ---- 应用侧读取 ----

    /** 读出已发布的报告文本；没有就返回 null。 */
    fun read(ctx: Context): Parsed? {
        val raw = readRaw(ctx.contentResolver) ?: return null
        return runCatching { Parsed(org.json.JSONObject(raw)) }.getOrNull()
    }

    fun clear(ctx: Context) {
        runCatching { writeRaw(ctx.contentResolver, "") }
    }

    /** 报告的可读视图。 */
    class Parsed(private val root: org.json.JSONObject) {

        val updatedAt: Long get() = root.optLong("updatedAt", 0L)

        fun groupNames(): List<String> {
            val groups = root.optJSONObject("groups") ?: return emptyList()
            return groups.keys().asSequence().toList()
        }

        fun counts(group: String): List<Int> {
            val obj = root.optJSONObject("groups")?.optJSONObject(group) ?: return listOf(0, 0, 0, 0)
            return listOf(
                obj.optInt("installed"),
                obj.optInt("missing"),
                obj.optInt("failed"),
                obj.optInt("skipped"),
            )
        }

        /** 某个功能域里「缺失 + 失败」的条目，这是真正需要修的东西。 */
        fun problems(group: String): List<String> {
            val items = root.optJSONObject("groups")?.optJSONObject(group)?.optJSONArray("items")
                ?: return emptyList()
            val out = ArrayList<String>()
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val status = item.optString("status")
                if (status != QuroHookStatus.MISSING.name && status != QuroHookStatus.FAILED.name) continue
                val desc = item.optString("desc")
                val detail = item.optString("detail").takeIf { it.isNotBlank() && it != "null" }
                out += if (detail == null) desc else "$desc（$detail）"
            }
            return out
        }

        /** 最近触发事件，新的在前。 */
        fun events(): List<Triple<Long, String, String>> {
            val arr = root.optJSONArray("events") ?: return emptyList()
            val out = ArrayList<Triple<Long, String, String>>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                out += Triple(
                    item.optLong("at"),
                    item.optString("what"),
                    item.optString("detail").takeIf { it.isNotBlank() && it != "null" } ?: "",
                )
            }
            return out
        }

        fun stamp(millis: Long): String = if (millis <= 0) "未知" else {
            runCatching {
                SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(millis))
            }.getOrDefault("未知")
        }
    }

    // ---- Settings.Secure 读写（按用户，透视 system_server 侧） ----

    private fun readRaw(resolver: ContentResolver): String? = runCatching {
        Settings.Secure.getString(resolver, SECURE_KEY)
    }.getOrNull()

    private fun writeRaw(resolver: ContentResolver, value: String) {
        runCatching {
            val method = Settings.Secure::class.java.getDeclaredMethod(
                "putStringForUser",
                ContentResolver::class.java,
                String::class.java,
                String::class.java,
                Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            method.invoke(null, resolver, SECURE_KEY, value, currentUserId())
        }.onFailure {
            // 厂商进程通常没有 WRITE_SECURE_SETTINGS；退回普通写入，仍失败就放弃。
            runCatching { Settings.Secure.putString(resolver, SECURE_KEY, value) }
        }
    }

    private fun currentUserId(): Int = runCatching {
        Class.forName("android.app.ActivityManager").getDeclaredMethod("getCurrentUser").invoke(null) as Int
    }.getOrDefault(0)
}
