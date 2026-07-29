package com.ai.assistance.quro.core.tools

import android.Manifest
import android.content.Context
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import org.json.JSONObject

/** 读取最近短信（READ_SMS 运行时权限）。 */
class ReadSmsTool : QuroTool {
    override val name = "read_sms"
    override val description = "读取最近收到的短信(发件人+正文)，参数为 {\"limit\":20}(可选，默认20)。"
    override val parametersJson = """{"type":"object","properties":{"limit":{"type":"integer","description":"返回条数，默认20"}}}"""
    override val requiredPermissions = listOf(Manifest.permission.READ_SMS)
    override fun run(context: Context, arguments: String): String {
        needsPermission(context, Manifest.permission.READ_SMS)?.let { return it }
        val limit = JSONObject(arguments).optInt("limit", 20).coerceIn(1, 100)
        return try {
            val proj = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
            // 🔧 #768 修复：同媒体库，去掉 sortOrder 里的 "LIMIT $limit"（部分实现不支持 → Invalid token LIMIT），
            //   数量由下方 while(out.size < limit) 截断。
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI, proj, null, null,
                "${Telephony.Sms.DATE} DESC",
            )?.use { c ->
                val out = mutableListOf<String>()
                while (c.moveToNext() && out.size < limit) {
                    val addr = c.getString(0) ?: ""
                    val body = c.getString(1) ?: ""
                    out.add("[$addr] $body")
                }
                if (out.isEmpty()) "（无短信）" else out.joinToString("\n")
            } ?: "（无短信）"
        } catch (e: Exception) {
            "读取短信失败: ${e.message}"
        }
    }
}

/** 发送短信（SEND_SMS 运行时权限）。 */
class SendSmsTool : QuroTool {
    override val name = "send_sms"
    override val description = "发送一条短信（当用户要发短信 / 给某人发消息时使用）。参数为 {\"phone\":\"138...\",\"message\":\"内容\"}。"
    override val parametersJson = """{"type":"object","properties":{"phone":{"type":"string","description":"收件号码"},"message":{"type":"string","description":"短信内容"}},"required":["phone","message"]}"""
    override val requiredPermissions = listOf(Manifest.permission.SEND_SMS)
    override fun run(context: Context, arguments: String): String {
        needsPermission(context, Manifest.permission.SEND_SMS)?.let { return it }
        val phone = JSONObject(arguments).optString("phone", "")
        val msg = JSONObject(arguments).optString("message", "")
        if (phone.isEmpty() || msg.isEmpty()) return "缺少 phone 或 message"
        return try {
            val sm = SmsManager.getDefault()
            if (msg.length > 140) {
                val parts = sm.divideMessage(msg)
                sm.sendMultipartTextMessage(phone, null, parts, null, null)
            } else {
                sm.sendTextMessage(phone, null, msg, null, null)
            }
            "已发送短信给 $phone"
        } catch (e: Exception) {
            "发送失败: ${e.message}"
        }
    }
}

/** 读取联系人（READ_CONTACTS 运行时权限）。 */
class ReadContactsTool : QuroTool {
    override val name = "read_contacts"
    override val description = "列出联系人(姓名+号码)，可按姓名片段过滤，参数为 {\"query\":\"张\"} (可选)。"
    override val parametersJson = """{"type":"object","properties":{"query":{"type":"string","description":"按姓名过滤(可选)"}}}"""
    override val requiredPermissions = listOf(Manifest.permission.READ_CONTACTS)
    override fun run(context: Context, arguments: String): String {
        needsPermission(context, Manifest.permission.READ_CONTACTS)?.let { return it }
        val q = JSONObject(arguments).optString("query", "").lowercase()
        return try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val proj = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            )
            val sel = if (q.isEmpty()) null else "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val selArgs = if (q.isEmpty()) null else arrayOf("%$q%")
            context.contentResolver.query(
                uri, proj, sel, selArgs,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
            )?.use { c ->
                val out = mutableListOf<String>()
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: ""
                    val num = c.getString(1) ?: ""
                    out.add("$name: $num")
                }
                if (out.isEmpty()) "（无匹配联系人）" else out.joinToString("\n")
            } ?: "（无联系人）"
        } catch (e: Exception) {
            "读取联系人失败: ${e.message}"
        }
    }
}
