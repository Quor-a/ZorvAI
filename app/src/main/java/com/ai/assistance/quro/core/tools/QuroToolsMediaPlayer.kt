package com.ai.assistance.quro.core.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.ai.assistance.quro.core.media.QuroVideoLauncher
import com.ai.assistance.quro.service.QuroMediaService
import org.json.JSONArray
import org.json.JSONObject

/**
 * 百分百开源本地音乐播放器（工具）。
 * 基于 Android 框架 MediaPlayer + 前台播放服务（AOSP, Apache-2.0），无任何第三方播放库。
 * 支持在「不打开前台」的情况下后台播放；聊天界面会显示播放/暂停卡片。
 * 参数 {"uri":"content://... 或 /sdcard/.../x.mp3","title":"可选"}。
 */
class LocalMusicPlayerTool : QuroTool {
    override val name = "local_music_player"
    override val description = "百分百开源的本地音乐播放器：在后台播放设备上的本地音频文件（不打开前台也能持续播放）。" +
        "参数 {\"uri\":\"音频文件的 content:// 或文件路径\",\"title\":\"可选，曲名\"}。" +
        "返回「正在后台播放」后，聊天界面会出现播放/暂停卡片，AI 也可随时调用以切换曲目。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "uri":{"type":"string","description":"本地音频 URI（content:// 或文件路径，如 /sdcard/Music/x.mp3）"},
            "title":{"type":"string","description":"可选，曲名，用于通知与播放卡片展示"}
        },
        "required":["uri"]
    }"""
    override fun run(context: Context, arguments: String): String {
        val jo = JSONObject(arguments)
        val uri = jo.optString("uri", "").trim()
        val title = jo.optString("title", "").trim()
        if (uri.isEmpty()) return "缺少 uri 参数"
        if (uri.startsWith("content://")) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        val intent = Intent(context, QuroMediaService::class.java)
            .putExtra(QuroMediaService.EXTRA_URI, uri)
            .putExtra(QuroMediaService.EXTRA_TITLE, title)
        runCatching { context.startForegroundService(intent) }
            .onFailure { return "启动音乐播放服务失败: ${it.message}" }
        return "正在后台播放：${title.ifEmpty { uri }}"
    }
}

/**
 * 百分百开源本地音乐播放器（播放列表版，v135）。
 * 支持一次传入多首曲目（tracks），后台连续播放，并支持循环/随机；聊天界面显示播放卡片，
 * 也可随时调用 ui_open_music_player 打开全屏播放器控制列表循环/随机/倍速。
 * 参数：{"tracks":[{"uri":"...","title":"..."}],"index":0} 或兼容单首 {"uri":"...","title":"..."}。
 */
class MusicPlayTool : QuroTool {
    override val name = "music_play"
    override val description = "百分百开源的本地音乐播放器（播放列表）：后台连续播放多首本地音频文件。" +
        "参数 {\"tracks\":[{\"uri\":\"音频 URI\",\"title\":\"曲名\"}],\"index\":0}；也可兼容单首 {\"uri\":\"...\",\"title\":\"...\"}。" +
        "返回「正在后台播放列表」后，聊天界面出现播放卡片，AI 也可调用 ui_open_music_player 打开全屏播放器控制循环/随机/倍速。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "tracks":{"type":"array","description":"曲目列表，每项 {\"uri\":\"content:// 或文件路径\",\"title\":\"曲名\"}","items":{"type":"object"}},
            "uri":{"type":"string","description":"兼容单首：音频 URI"},
            "title":{"type":"string","description":"兼容单首：曲名"},
            "index":{"type":"integer","description":"可选，从第几首开始（默认 0）"}
        },
        "required":[]
    }"""
    override fun run(context: Context, arguments: String): String {
        val jo = JSONObject(arguments)
        val uris = ArrayList<String>()
        val titles = ArrayList<String>()
        val arr = jo.optJSONArray("tracks")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                val u = t.optString("uri", "").trim()
                if (u.isNotEmpty()) { uris.add(u); titles.add(t.optString("title", "").trim()) }
            }
        }
        // 兼容单首
        val single = jo.optString("uri", "").trim()
        if (uris.isEmpty() && single.isNotEmpty()) {
            uris.add(single)
            titles.add(jo.optString("title", "").trim())
        }
        if (uris.isEmpty()) return "缺少 tracks 或 uri 参数"
        // 授予持久读取权限（content://）
        uris.forEach { u ->
            if (u.startsWith("content://")) runCatching {
                context.contentResolver.takePersistableUriPermission(Uri.parse(u), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        val idx = jo.optInt("index", 0).coerceIn(0, uris.size - 1)
        val intent = Intent(context, QuroMediaService::class.java)
            .putStringArrayListExtra(QuroMediaService.EXTRA_QUEUE_URIS, uris)
            .putStringArrayListExtra(QuroMediaService.EXTRA_QUEUE_TITLES, titles)
            .putExtra(QuroMediaService.EXTRA_INDEX, idx)
        runCatching { context.startForegroundService(intent) }
            .onFailure { return "启动音乐播放服务失败: ${it.message}" }
        return "正在后台播放列表（共 ${uris.size} 首，从第 ${idx + 1} 首开始）：${titles.getOrNull(idx)?.takeIf { it.isNotEmpty() } ?: uris[idx]}"
    }
}

/**
 * 百分百开源本地视频播放器（工具）。
 * 在应用内全功能视频播放器播放设备上的视频文件（AOSP, Apache-2.0，基于框架 VideoView），
 * 不调起外部播放器。支持播放/暂停、进度拖动、倍速(0.5x–2x)、静音、横竖屏旋转。
 * 对话中直接触发「播放本地视频」。参数 {"uri":"...","title":"可选"}。
 */
class LocalVideoPlayerTool : QuroTool {
    override val name = "local_video_player"
    override val description = "百分百开源的本地视频播放器：在应用内全功能播放器播放设备上的视频（支持播放/暂停、进度、倍速、静音、横竖屏）。" +
        "参数 {\"uri\":\"视频文件的 content:// 或文件路径或 http(s) 链接\",\"title\":\"可选，片名\"}。" +
        "对话中触发后即在应用内视频播放器播放该视频。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "uri":{"type":"string","description":"视频 URI（content:// 或文件路径或 http(s) 链接）"},
            "title":{"type":"string","description":"可选，片名"}
        },
        "required":["uri"]
    }"""
    override fun run(context: Context, arguments: String): String {
        val jo = JSONObject(arguments)
        val uri = jo.optString("uri", "").trim()
        val title = jo.optString("title", "").trim()
        if (uri.isEmpty()) return "缺少 uri 参数"
        QuroVideoLauncher.open(uri, title)
        return "正在应用内播放视频：${title.ifEmpty { uri }}"
    }
}
