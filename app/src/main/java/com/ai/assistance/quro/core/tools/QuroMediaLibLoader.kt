package com.ai.assistance.quro.core.tools

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore

/**
 * 媒体库扫描加载器（音乐/视频共用）。
 *
 * 从 MediaStore 扫描本地媒体并返回 (uriString, displayName) 列表，按添加时间倒序。
 * 供音乐播放器「整库入队连播」、媒体浏览器等复用。纯框架 API，无第三方依赖。
 */
object QuroMediaLibLoader {

    /** 扫描本地媒体库。kind="music" 扫音频，否则扫视频。返回 (uri, name) 列表。 */
    fun load(ctx: Context, kind: String): List<Pair<String, String>> {
        val coll = if (kind == "music") MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
        )
        val out = mutableListOf<Pair<String, String>>()
        runCatching {
            ctx.contentResolver.query(
                coll, proj, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            )?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                while (c.moveToNext()) {
                    val uri = ContentUris.withAppendedId(coll, c.getLong(idIdx)).toString()
                    val name = c.getString(nameIdx) ?: "未知"
                    out.add(uri to name)
                }
            }
        }
        return out
    }
}
