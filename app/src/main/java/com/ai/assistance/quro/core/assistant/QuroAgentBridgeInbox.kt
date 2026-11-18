package com.ai.assistance.quro.core.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 厂商助手接管的「可观测收件箱」（app 主进程，净室实现）。
 *
 * Hook 进程跑完 [QuroAgentTurnRunner] 后把「入口 / 请求 / 回复 / 成败 / 耗时」投递到这里，
 * 系统增强页就能看到每次接管到底发生了什么，而不是一个静默的黑盒桥。
 * 只保留最近 [MAX] 条，落在 `quro_agent_inbox` 这一份 SharedPreferences 里，不写主聊天库。
 */
object QuroAgentBridgeInbox {

    private const val PREFS = "quro_agent_inbox"
    private const val KEY_RECORDS = "records"
    const val MAX = 30

    data class Record(
        val source: String,
        val request: String,
        val reply: String,
        val ok: Boolean,
        val error: String,
        val elapsedMs: Long,
        val ts: Long,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("source", source)
            put("source_label", QuroAgentBridgeProtocol.sourceLabel(source))
            put("request", request)
            put("reply", reply)
            put("ok", ok)
            put("error", error)
            put("elapsed_ms", elapsedMs)
            put("ts", ts)
        }
    }

    @Synchronized
    fun append(ctx: Context, record: Record) {
        runCatching {
            val prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val all = JSONArray(prefs.getString(KEY_RECORDS, "[]") ?: "[]")
            val next = JSONArray()
            next.put(record.toJson())
            for (i in 0 until all.length()) {
                if (next.length() >= MAX) break
                next.put(all.get(i))
            }
            prefs.edit().putString(KEY_RECORDS, next.toString()).apply()
        }
    }

    @Synchronized
    fun recent(ctx: Context, limit: Int = 10): List<Record> = runCatching {
        val prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val all = JSONArray(prefs.getString(KEY_RECORDS, "[]") ?: "[]")
        (0 until minOf(all.length(), limit.coerceIn(1, MAX))).map { i ->
            val o = all.getJSONObject(i)
            Record(
                source = o.optString("source", QuroAgentBridgeProtocol.SOURCE_UNKNOWN),
                request = o.optString("request"),
                reply = o.optString("reply"),
                ok = o.optBoolean("ok"),
                error = o.optString("error"),
                elapsedMs = o.optLong("elapsed_ms"),
                ts = o.optLong("ts"),
            )
        }
    }.getOrDefault(emptyList())

    @Synchronized
    fun clear(ctx: Context) {
        runCatching {
            ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_RECORDS).apply()
        }
    }
}
