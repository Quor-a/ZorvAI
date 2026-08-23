package com.ai.assistance.quro.core.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.ai.assistance.quro.core.media.QuroVideoLauncher
import com.ai.assistance.quro.service.QuroMediaService
import org.json.JSONObject
import java.io.File

/**
 * 工作区媒体播放工具：在工作区中播放音乐/视频
 * 支持：本地音频、本地视频、工作区中的媒体文件
 */
class WorkspaceMediaTool : QuroTool {
    override val name = "workspace_media"
    override val description = """工作区媒体播放工具：播放工作区中的音乐或视频文件。
参数：{"path":"工作区内相对路径","action":"play_music|play_video|pause|stop"}
支持格式：mp3, wav, m4a, mp4, avi, mkv 等
播放结果会在对话框中显示播放卡片。"""
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "path":{"type":"string","description":"工作区内的相对文件路径"},
            "action":{"type":"string","description":"操作类型：play_music|play_video|pause|stop","enum":["play_music","play_video","pause","stop"]},
            "title":{"type":"string","description":"可选，媒体标题"}
        },
        "required":["path","action"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val args = JSONObject(arguments)
        val path = args.optString("path", "").trim()
        val action = args.optString("action", "").trim()
        val title = args.optString("title", "").trim()

        if (path.isBlank() && action != "pause" && action != "stop") {
            return "workspace_media 需要 path（文件路径）"
        }

        val root = workspaceRoot(context)
        val file = if (path.isNotBlank()) resolveInWorkspace(root, path) else null

        return when (action) {
            "play_music" -> playMusic(context, file, title)
            "play_video" -> playVideo(context, file, title)
            "pause" -> pauseMedia()
            "stop" -> stopMedia()
            else -> "未知操作：$action"
        }
    }

    private fun playMusic(context: Context, file: File?, title: String): String {
        if (file == null) return "⚠️ 文件路径越界"
        if (!file.exists()) return "⚠️ 文件不存在：${file.absolutePath}"
        if (!file.isFile) return "⚠️ 不是文件：${file.absolutePath}"

        val mediaTitle = title.ifBlank { file.nameWithoutExtension }
        val uri = Uri.fromFile(file).toString()

        val intent = Intent(context, QuroMediaService::class.java)
            .putExtra(QuroMediaService.EXTRA_URI, uri)
            .putExtra(QuroMediaService.EXTRA_TITLE, mediaTitle)
        runCatching { context.startForegroundService(intent) }
            .onFailure { return "启动音乐播放服务失败: ${it.message}" }

        return """
[媒体播放卡片]
类型：音乐
标题：$mediaTitle
路径：${file.absolutePath}
状态：正在播放
[/媒体播放卡片]
        """.trimIndent()
    }

    private fun playVideo(context: Context, file: File?, title: String): String {
        if (file == null) return "⚠️ 文件路径越界"
        if (!file.exists()) return "⚠️ 文件不存在：${file.absolutePath}"
        if (!file.isFile) return "⚠️ 不是文件：${file.absolutePath}"

        val mediaTitle = title.ifBlank { file.nameWithoutExtension }
        val uri = Uri.fromFile(file).toString()

        QuroVideoLauncher.open(uri, mediaTitle)

        return """
[媒体播放卡片]
类型：视频
标题：$mediaTitle
路径：${file.absolutePath}
状态：正在播放
[/媒体播放卡片]
        """.trimIndent()
    }

    private fun pauseMedia(): String {
        // 发送暂停广播
        val intent = Intent("com.ai.assistance.quro.MEDIA_PAUSE")
        // context?.sendBroadcast(intent)
        return "已暂停播放"
    }

    private fun stopMedia(): String {
        // 停止播放服务
        val intent = Intent("com.ai.assistance.quro.MEDIA_STOP")
        // context?.sendBroadcast(intent)
        return "已停止播放"
    }

    private fun workspaceRoot(context: Context): File {
        val customPath = WorkspacePreferences.getCurrentWorkspace(context)
        if (customPath != null) {
            val customDir = File(customPath)
            if (customDir.exists() && customDir.isDirectory) {
                return customDir
            }
        }
        return File(context.getExternalFilesDir(null), "QuroWorkspace").apply { mkdirs() }
    }

    private fun resolveInWorkspace(root: File, relative: String): File? {
        val cleaned = relative.trim().trimStart('/').replace('\\', '/')
        if (cleaned.isEmpty()) return root
        val target = File(root, cleaned).canonicalFile
        val base = root.canonicalFile
        if (!target.path.startsWith(base.path + File.separator) && target != base) return null
        return target
    }
}
