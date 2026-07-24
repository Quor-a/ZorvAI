package com.ai.assistance.quro.core.tools

import android.Manifest
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import org.json.JSONObject

/** 媒体库查询（READ_MEDIA_IMAGES/VIDEO 运行时权限，按 API 版本选择）。 */
class ListMediaTool : QuroTool {
    override val name = "list_media"
    override val description = "列出媒体库中的图片或视频(名称+大小+日期)，参数为 {\"kind\":\"image|video\",\"limit\":20}。"
    override val parametersJson = """{"type":"object","properties":{"kind":{"type":"string","description":"image 或 video，默认 image"},"limit":{"type":"integer","description":"返回条数默认20"}}}"""
    override val requiredPermissions = listOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_EXTERNAL_STORAGE,
    )
    override fun run(context: Context, arguments: String): String {
        val kind = JSONObject(arguments).optString("kind", "image").lowercase()
        val perm = if (Build.VERSION.SDK_INT >= 33) {
            if (kind == "video") Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        needsPermission(context, perm)?.let { return it }
        val limit = JSONObject(arguments).optInt("limit", 20).coerceIn(1, 100)
        val coll = if (kind == "video") {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val proj = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
        )
        return try {
            context.contentResolver.query(
                coll, proj, null, null,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC LIMIT $limit",
            )?.use { c ->
                val out = mutableListOf<String>()
                while (c.moveToNext() && out.size < limit) {
                    val name = c.getString(0) ?: ""
                    val size = c.getLong(1)
                    val date = c.getLong(2)
                    out.add("$name (${size}B, added=$date)")
                }
                if (out.isEmpty()) "（媒体库为空）" else out.joinToString("\n")
            } ?: "（媒体库为空）"
        } catch (e: Exception) {
            "读取媒体库失败: ${e.message}"
        }
    }
}
