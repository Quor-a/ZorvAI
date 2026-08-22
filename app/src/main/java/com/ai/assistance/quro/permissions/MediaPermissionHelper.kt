package com.ai.assistance.quro.permissions

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.FileOutputStream

/**
 * 文件与媒体权限（Android 13+ 分区存储）。
 *
 * 链路：
 * 1. Manifest 声明 READ_MEDIA_IMAGES / READ_MEDIA_VIDEO / READ_MEDIA_AUDIO（TIRAMISU+）
 *    以及 READ/WRITE_EXTERNAL_STORAGE（低版本 maxSdkVersion 兜底）。
 * 2. 运行时请求由调用方（Compose 侧用 [rememberLauncherForActivityResult]）发起，
 *    本类只负责"需要哪些权限 / 是否已授权 / 是否应展示说明"等纯逻辑，
 *    避免在构造函数里注册 ActivityResultLauncher（那会在页面被导航打开时抛
 *    IllegalStateException: must be called before they are STARTED）。
 * 3. 导出走 MediaStore.Downloads（RELATIVE_PATH），**无需任何权限**即可写入公共 Download。
 *
 * 不依赖 MANAGE_ALL_FILES（全文件系统访问），避免上架 Google Play 的宽泛权限审查。
 */
class MediaPermissionHelper(private val activity: AppCompatActivity) {

    /**
     * 是否已经向用户发起过请求。
     * 用于区分"首次（应弹窗请求）"与"已永久拒绝（应引导去设置页）"——
     * [shouldShowRationale] 在两者下都为 false，必须靠此标记区分。
     * 调用方在 launch 请求前将其置 true。
     */
    var hasRequested: Boolean = false

    /** 按系统版本自适应返回需要请求的权限数组。 */
    fun permissionsNeeded(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO
        )
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q -> arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun hasPermissions(): Boolean = permissionsNeeded().all {
        ContextCompat.checkSelfPermission(activity, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /** 是否应展示权限说明（曾被拒但未永久拒绝，系统仍会弹窗）。 */
    fun shouldShowRationale(): Boolean = permissionsNeeded().any {
        ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
    }

    /**
     * 归一化状态：
     * - [PermState.Granted]     已授权。
     * - [PermState.NeedRequest] 首次（从未请求过）或曾被拒但可再次弹窗 → 应发起运行时请求。
     * - [PermState.NeedSettings] 已永久拒绝（"不再询问"）→ 无法弹窗，必须引导去应用设置页。
     */
    fun mediaState(): PermState = when {
        hasPermissions() -> PermState.Granted
        shouldShowRationale() || !hasRequested -> PermState.NeedRequest
        else -> PermState.NeedSettings
    }

    /**
     * 导出文件到公共 Download 目录，**无需任何运行时权限**（Android 10+ 经 MediaStore）。
     *
     * @param fileName  文件名（含扩展名）
     * @param mimeType  MIME 类型，如 "text/html"
     * @param subDir    Download 下的子目录，默认 "ZorvAI"
     * @param data      文件字节
     * @return 写入后的 Uri；失败返回 null
     */
    fun exportToDownloads(
        context: Context,
        fileName: String,
        mimeType: String,
        subDir: String = "ZorvAI",
        data: ByteArray
    ): Uri? {
        val resolver = context.contentResolver

        // Android 9 及以下：直接写文件到公共 Download（无分区存储）
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val target = java.io.File(dir, "$subDir/$fileName")
            runCatching {
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { it.write(data) }
            }.onFailure { return null }
            return Uri.fromFile(target)
        }

        // Android 10+：MediaStore.Downloads + RELATIVE_PATH，系统自动落盘到公共 Download
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$subDir")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return null
        runCatching {
            resolver.openOutputStream(uri)?.use { it.write(data) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }.onFailure {
            resolver.delete(uri, null, null)
            return null
        }
        return uri
    }
}
