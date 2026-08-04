package com.ai.assistance.quro.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/**
 * 通过 FileProvider 调起系统 / WPS / Office 打开本地生成的文档（.docx/.xlsx/.pptx/.pdf 等）。
 *
 * 文档位于应用 external-files 目录，必须经 FileProvider 暴露（见 res/xml/file_paths.xml），
 * 否则第三方 Office 应用无权读取。
 */
object QuroDocOpener {
    fun open(context: Context, file: File): Boolean {
        if (!file.exists()) return false
        val uri: Uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val mime = guessMime(file.name)
        // 优先尝试已安装的开源办公套件 ONLYOFFICE Documents（若已安装则直接打开；否则回退系统选择器）
        val onlyOffice = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            setPackage("com.onlyoffice.documents")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(onlyOffice) }.onSuccess { return true }
        // 兜底：系统选择器
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(Intent.createChooser(intent, "用以下应用打开"))
            true
        }.getOrDefault(false)
    }

    fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "doc" -> "application/msword"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "xls" -> "application/vnd.ms-excel"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "md" -> "text/markdown"
            "json" -> "application/json"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }
}
