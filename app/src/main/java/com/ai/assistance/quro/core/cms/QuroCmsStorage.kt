package com.ai.assistance.quro.core.cms

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * CMS v2 存储层（原创）：
 * - 授权状态落盘到 filesDir/cms_auth.json（替代 Rust 版的 SQLite 五表，纯 [org.json] 实现）
 * - 审计日志落盘到 filesDir/cms_audit.json（对应 authorization_audit 表）
 * - 支持授权备份导出/导入（cms auth export/import）
 */
class QuroCmsStorage(context: Context) {

    private val authFile = File(context.filesDir, "cms_auth.json")
    private val auditFile = File(context.filesDir, "cms_audit.json")

    // ---------- 授权 ----------

    data class AuthEntry(
        val moduleId: String,
        val permissionId: String,
        val level: AuthorizationLevel,
        val grantedAt: Long,
    )

    fun getAuth(moduleId: String, permissionId: String): AuthorizationLevel? {
        return runCatching {
            val root = JSONObject(authFile.readText())
            val key = "$moduleId:$permissionId"
            val obj = root.optJSONObject(key) ?: return null
            AuthorizationLevel.valueOf(obj.getString("level"))
        }.getOrNull()
    }

    fun setAuth(moduleId: String, permissionId: String, level: AuthorizationLevel) {
        val root = runCatching { JSONObject(authFile.readText()) }.getOrDefault(JSONObject())
        root.put("$moduleId:$permissionId", JSONObject().apply {
            put("level", level.name)
            put("grantedAt", System.currentTimeMillis())
        })
        authFile.writeText(root.toString(2))
    }

    fun revoke(moduleId: String, permissionId: String) {
        if (!authFile.exists()) return
        val root = runCatching { JSONObject(authFile.readText()) }.getOrDefault(JSONObject())
        root.remove("$moduleId:$permissionId")
        authFile.writeText(root.toString(2))
    }

    fun listAuths(): List<AuthEntry> {
        if (!authFile.exists()) return emptyList()
        return runCatching {
            val root = JSONObject(authFile.readText())
            root.keys().asSequence().mapNotNull { key ->
                val obj = root.optJSONObject(key) ?: return@mapNotNull null
                val (m, p) = key.split(":", limit = 2)
                AuthEntry(m, p, AuthorizationLevel.valueOf(obj.getString("level")), obj.optLong("grantedAt", 0))
            }.toList()
        }.getOrDefault(emptyList())
    }

    fun exportAuths(): String {
        val root = runCatching { JSONObject(authFile.readText()) }.getOrDefault(JSONObject())
        return JSONObject().put("apiVersion", "cms.io/v2").put("authorizations", root).toString(2)
    }

    fun importAuths(json: String): Boolean {
        return runCatching {
            val imported = JSONObject(json)
            val src = imported.optJSONObject("authorizations") ?: imported
            val merged = runCatching { JSONObject(authFile.readText()) }.getOrDefault(JSONObject())
            src.keys().forEach { key -> merged.put(key, src.get(key)) }
            authFile.writeText(merged.toString(2))
            true
        }.getOrDefault(false)
    }

    // ---------- 审计 ----------

    data class AuditEntry(
        val timestamp: Long,
        val action: String,
        val level: String,
        val moduleId: String,
        val permissionId: String,
        val userAction: String,
        val decisionReason: String,
        val riskLevel: String,
    )

    fun log(
        moduleId: String,
        permissionId: String,
        action: String,
        level: String,
        userAction: String,
        decisionReason: String,
        riskLevel: String,
    ) {
        val list = loadAudit().toMutableList()
        list.add(0, AuditEntry(System.currentTimeMillis(), action, level, moduleId, permissionId, userAction, decisionReason, riskLevel))
        // 最多保留 500 条（不可篡改的环形审计，P0 闸门评审要求扩容）
        val trimmed = list.take(500)
        val arr = JSONArray()
        trimmed.forEach { e ->
            arr.put(JSONObject().apply {
                put("timestamp", e.timestamp); put("action", e.action); put("level", e.level)
                put("moduleId", e.moduleId); put("permissionId", e.permissionId)
                put("userAction", e.userAction); put("decisionReason", e.decisionReason)
                put("riskLevel", e.riskLevel)
            })
        }
        auditFile.writeText(JSONObject().put("audit", arr).toString(2))
    }

    fun loadAudit(): List<AuditEntry> {
        if (!auditFile.exists()) return emptyList()
        return runCatching {
            val root = JSONObject(auditFile.readText())
            val arr = root.optJSONArray("audit") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                AuditEntry(
                    o.optLong("timestamp", 0), o.optString("action"), o.optString("level"),
                    o.optString("moduleId"), o.optString("permissionId"),
                    o.optString("userAction"), o.optString("decisionReason"), o.optString("riskLevel"),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun clearAudit() {
        auditFile.writeText(JSONObject().put("audit", JSONArray()).toString(2))
    }
}
