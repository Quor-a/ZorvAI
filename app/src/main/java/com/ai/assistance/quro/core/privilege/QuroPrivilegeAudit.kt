package com.ai.assistance.quro.core.privilege

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CapOS 权限审计日志（原创）。所有权限使用都会被记录，用户可追溯到任何一个 capsule 的行为。
 * 存储于 filesDir/capos_audit.json，最多保留最近 200 条。
 */
data class AuditEntry(
    val timestamp: String,
    val capsuleId: String,
    val level: String,
    val action: String,
    val result: Boolean,
)

object QuroPrivilegeAudit {
    private const val FILE = "capos_audit.json"
    private const val MAX = 200
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun log(ctx: Context, capsuleId: String, level: PrivilegeLevel, action: String, result: Boolean) {
        log(ctx, capsuleId, level.name, action, result)
    }

    fun log(ctx: Context, capsuleId: String, level: String, action: String, result: Boolean) {
        runCatching {
            val file = File(ctx.filesDir, FILE)
            val arr = runCatching { JSONArray(file.readText()) }.getOrDefault(JSONArray())
            val obj = JSONObject()
            obj.put("timestamp", fmt.format(Date()))
            obj.put("capsuleId", capsuleId)
            obj.put("level", level)
            obj.put("action", action)
            obj.put("result", result)
            arr.put(obj)
            while (arr.length() > MAX) arr.remove(0)
            file.writeText(JSONObject().put("audit", arr).toString())
        }
    }

    fun load(ctx: Context): List<AuditEntry> {
        val file = File(ctx.filesDir, FILE)
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONObject(file.readText()).getJSONArray("audit")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                AuditEntry(
                    o.optString("timestamp", ""),
                    o.optString("capsuleId", ""),
                    o.optString("level", ""),
                    o.optString("action", ""),
                    o.optBoolean("result", false),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun clear(ctx: Context) {
        runCatching { File(ctx.filesDir, FILE).delete() }
    }
}
