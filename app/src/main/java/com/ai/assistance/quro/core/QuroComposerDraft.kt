package com.ai.assistance.quro.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 输入框草稿持久化（按会话 id）。
 *
 * 用户切会话/退出重进后，输入框文字与附件列表能恢复，避免「打了一半的字丢了」。
 * 附件仅保存可序列化的元信息（type/uri/name/mime/size），恢复时若文件已不存在则跳过。
 */
object QuroComposerDraft {

    private const val PREFS = "quro_composer_draft"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 保存某会话的草稿（文字 + 附件元信息）。 */
    fun save(ctx: Context, conversationId: String, text: String, attachments: List<QuroAttachment>) {
        val key = conversationId.ifBlank { "_new_chat_" }
        val obj = JSONObject().apply {
            put("text", text)
            put("attachments", JSONArray().apply {
                attachments.forEach { a ->
                    put(JSONObject().apply {
                        put("type", a.type)
                        put("uri", a.uri)
                        put("name", a.name)
                        put("mime", a.mime)
                        put("size", a.size)
                    })
                }
            })
        }
        prefs(ctx).edit().putString(key, obj.toString()).apply()
    }

    /** 读取某会话的草稿文字。 */
    fun loadText(ctx: Context, conversationId: String): String {
        val key = conversationId.ifBlank { "_new_chat_" }
        val raw = prefs(ctx).getString(key, null) ?: return ""
        return runCatching { JSONObject(raw).optString("text", "") }.getOrDefault("")
    }

    /** 读取某会话的草稿附件（文件已不存在的会被过滤掉）。 */
    fun loadAttachments(ctx: Context, conversationId: String): List<QuroAttachment> {
        val key = conversationId.ifBlank { "_new_chat_" }
        val raw = prefs(ctx).getString(key, null) ?: return emptyList()
        return runCatching {
            val arr = JSONObject(raw).optJSONArray("attachments") ?: return emptyList()
            val out = mutableListOf<QuroAttachment>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val uri = o.optString("uri", "")
                // 附件文件必须仍存在，否则跳过
                if (uri.isNotBlank() && java.io.File(uri).exists()) {
                    out.add(
                        QuroAttachment(
                            type = o.optString("type", "file"),
                            uri = uri,
                            name = o.optString("name", ""),
                            mime = o.optString("mime", ""),
                            size = o.optLong("size", 0L),
                        )
                    )
                }
            }
            out
        }.getOrDefault(emptyList())
    }

    /** 清除某会话草稿（发送成功后调用，避免残留）。 */
    fun clear(ctx: Context, conversationId: String) {
        val key = conversationId.ifBlank { "_new_chat_" }
        prefs(ctx).edit().remove(key).apply()
    }
}
