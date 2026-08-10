package com.ai.assistance.quro.core.aidlaci

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ACI 调用审计日志（原创）。
 *
 * 持久化「控制端 ZorvAI 发起的每一次 ACI 调用」全生命周期，
 * 用户可事后审查「AI 昨天通过 ACI 调了哪些能力、成功与否、耗时多少、状态码是什么」。
 * 这是 ACI 治理层（凭证 / 审计 / 可观测）的第一块拼图，直接闭合
 * feature-aci-governance-roadmap 中「控制端 ACI 调用审计」P0 项。
 *
 * 存储格式与 [com.ai.assistance.quro.core.privilege.QuroPrivilegeAudit] 保持一致：
 * filesDir/aci_call_audit.json，内容为 {"audit":[...]}，最多保留最近 500 条。
 */
data class AciCallAuditEntry(
    val timestamp: String,
    val targetPackage: String,
    val capability: String,
    val code: Int,
    val ok: Boolean,
    val durationMs: Long,
)

object QuroAidlAciCallAudit {
    private const val FILE = "aci_call_audit.json"
    private const val MAX = 500
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /**
     * 记录一次 ACI 调用结果。
     * @param code AidlAciResponse.code（成功通常 200，错误走 AidlAciError 4xx/5xx）
     * @param ok   code < 400 视为成功
     * @param durationMs 本次调用耗时（毫秒）
     */
    fun log(ctx: Context, targetPackage: String, capability: String, code: Int, ok: Boolean, durationMs: Long) {
        runCatching {
            val file = File(ctx.filesDir, FILE)
            val arr = runCatching { JSONArray(file.readText()) }.getOrDefault(JSONArray())
            val obj = JSONObject()
            obj.put("timestamp", fmt.format(Date()))
            obj.put("targetPackage", targetPackage)
            obj.put("capability", capability)
            obj.put("code", code)
            obj.put("ok", ok)
            obj.put("durationMs", durationMs)
            arr.put(obj)
            while (arr.length() > MAX) arr.remove(0)
            file.writeText(JSONObject().put("audit", arr).toString())
        }
    }

    fun load(ctx: Context): List<AciCallAuditEntry> {
        val file = File(ctx.filesDir, FILE)
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONObject(file.readText()).getJSONArray("audit")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                AciCallAuditEntry(
                    o.optString("timestamp", ""),
                    o.optString("targetPackage", ""),
                    o.optString("capability", ""),
                    o.optInt("code", 0),
                    o.optBoolean("ok", false),
                    o.optLong("durationMs", 0L),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun clear(ctx: Context) {
        runCatching { File(ctx.filesDir, FILE).delete() }
    }
}
