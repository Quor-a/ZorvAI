package com.ai.assistance.quro.workflow.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.ai.assistance.quro.R
import org.json.JSONObject
import java.io.File

/**
 * 设备动作与通知辅助（全部基于 Android 框架，无新依赖）。
 *
 *  - notify        本地通知（需要通知渠道，API 26+ 自动创建）
 *  - launchApp     通过包名启动应用
 *  - sendBroadcast 发送（有序/普通）广播
 *  - fileOp        应用私有目录内的文件读写（避免存储权限）
 *  - openMedia     用系统查看器打开媒体（URL 或应用私有文件，经 FileProvider 授权）
 *  - playMedia     用系统播放器播放音频/视频（同上）
 *  - capturePhoto  调起系统相机拍照并保存（headless 下仅发起，结果写入指定文件）
 *
 * 应用私有文件通过 FileProvider（authority = ${applicationId}.fileprovider）以
 * content:// URI 形式临时授权给第三方查看/播放应用，无需任何存储权限。
 */
object Device {

    /** FileProvider authority（与 AndroidManifest 中 <provider> 的 android:authorities 对应）。 */
    private fun fileAuthority(ctx: Context): String = ctx.packageName + ".fileprovider"

    private const val CHANNEL_ID = "workflow_aci"
    private const val CHANNEL_NAME = "工作流"
    private var notifSeq = 1000

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT
                )
                mgr.createNotificationChannel(ch)
            }
        }
    }

    fun notify(ctx: Context, title: String, body: String) {
        ensureChannel(ctx)
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val nb = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setContentTitle(title.ifBlank { "工作流" })
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
        mgr.notify(notifSeq++, nb.build())
    }

    fun launchApp(ctx: Context, pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        return try {
            val pm = ctx.packageManager
            val intent = pm.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
                true
            } else false
        } catch (_: Exception) {
            false
        }
    }

    fun sendBroadcast(ctx: Context, action: String?, extrasJson: String?, ordered: Boolean): Boolean {
        if (action.isNullOrBlank()) return false
        return try {
            val intent = Intent(action)
            extrasJson?.takeIf { it.isNotBlank() }?.let {
                runCatching {
                    val o = JSONObject(it)
                    o.keys().forEach { k -> intent.putExtra(k, o.optString(k)) }
                }
            }
            if (ordered) ctx.sendOrderedBroadcast(intent, null) else ctx.sendBroadcast(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 文件读写（限定在 App 私有 filesDir 内，避免存储权限）。read 返回文件内容，其它返回 ""。 */
    fun fileOp(ctx: Context, path: String?, mode: String?, content: String?): String {
        val rel = (path ?: "workflow_file.txt").trim().trimStart('/')
        val f = File(ctx.filesDir, rel)
        return when ((mode ?: "read").lowercase()) {
            "write" -> {
                f.parentFile?.mkdirs()
                f.writeText(content ?: "")
                ""
            }
            "append" -> {
                f.parentFile?.mkdirs()
                f.appendText(content ?: "")
                ""
            }
            else -> runCatching { f.readText() }.getOrDefault("")
        }
    }

    /**
     * 用系统查看器打开媒体。target 可为 http(s)/content URL，或应用私有文件路径
     * （相对 filesDir）。返回是否成功发起 Intent。
     */
    fun openMedia(ctx: Context, target: String?): Boolean {
        if (target.isNullOrBlank()) return false
        return launchViewIntent(ctx, target, null)
    }

    /**
     * 用系统播放器播放音频/视频。target 同上；按扩展名猜测 mime 以正确选择播放器。
     */
    fun playMedia(ctx: Context, target: String?): Boolean {
        if (target.isNullOrBlank()) return false
        return launchViewIntent(ctx, target, guessMediaMime(target))
    }

    /**
     * 调起系统相机拍照。结果写入应用私有目录下的 path（默认 captured_photo.jpg）。
     * 注意：引擎在 headless（服务/Receiver）上下文运行，无 Activity 接收回调，
     * 因此只负责发起拍照并指定输出文件，照片由相机应用写入该文件。
     */
    fun capturePhoto(ctx: Context, path: String?): Boolean {
        return try {
            val rel = (path ?: "captured_photo.jpg").trim().trimStart('/')
            val f = File(ctx.filesDir, rel)
            f.parentFile?.mkdirs()
            val uri = FileProvider.getUriForFile(ctx, fileAuthority(ctx), f)
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            ctx.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 按扩展名猜测媒体 mime（用于播放器/查看器选择）。 */
    private fun guessMediaMime(target: String): String? {
        val lower = target.lowercase()
        return when {
            lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".m4a") ||
                lower.endsWith(".aac") || lower.endsWith(".ogg") || lower.endsWith(".flac") -> "audio/*"
            lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm") ||
                lower.endsWith(".3gp") || lower.endsWith(".mov") -> "video/*"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
                lower.endsWith(".gif") || lower.endsWith(".webp") -> "image/*"
            else -> null
        }
    }

    /**
     * 解析 target 为可查看的 Uri 并发起 ACTION_VIEW。
     *  - URL / content://：直接使用；
     *  - 应用私有文件：经 FileProvider 转换为 content:// 并授予临时读取权限。
     */
    private fun launchViewIntent(ctx: Context, target: String, mime: String?): Boolean {
        return try {
            val uri = when {
                target.startsWith("http://", true) || target.startsWith("https://", true) ||
                    target.startsWith("content://", true) -> Uri.parse(target)
                else -> {
                    val f = File(ctx.filesDir, target.trim().trimStart('/'))
                    if (!f.exists()) return false
                    FileProvider.getUriForFile(ctx, fileAuthority(ctx), f)
                }
            }
            val resolvedMime = mime ?: guessMediaMime(target) ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, resolvedMime)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
