package com.ai.assistance.quro.core.camera

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * 相机输出工具：为系统相机 [android.provider.MediaStore.ACTION_IMAGE_CAPTURE] 生成
 * FileProvider 安全的输出 Uri（写入应用私有目录，路径稳定、可被对话附件系统读取）。
 *
 * 注意：authority 必须与 AndroidManifest 中 `androidx.core.content.FileProvider` 声明一致
 * （${applicationId}.fileprovider）。
 */
object QuroCameraUtil {
    /** 在应用私有 files/camera 下生成一张 JPEG 输出文件，并返回其 FileProvider Uri。 */
    fun createOutputUri(c: Context): Uri {
        val dir = File(c.filesDir, "camera")
        dir.mkdirs()
        val file = File(dir, "shot_${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(c, "${c.packageName}.fileprovider", file)
    }
}
