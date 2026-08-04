package com.ai.assistance.quro.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/**
 * 文档打开统一入口：通过 FileProvider 调起系统 / WPS / Office 打开本地文档（.docx/.xlsx/.pptx/.pdf 等）。
 *
 * 设计约定（与「文档」入口对齐）：
 *  1. 所有文档打开链路统一「WPS 优先」——若系统已安装 WPS Office，则用 WPS 打开；
 *     未安装再回退到系统选择器（含用户已装的 ONLYOFFICE 等）。
 *  2. 不再硬编码 ONLYOFFICE 包名优先（旧实现与「文档」命名自相矛盾）。
 *  3. FileProvider 的 Uri 获取统一走 [safeUri]，路径未被 res/xml/file_paths.xml 覆盖时
 *     返回 null 而非抛 IllegalArgumentException 导致整页崩溃。
 */
object QuroDocOpener {

    /** WPS Office 候选包名（海外版 / 国内版）。 */
    private val WPS_PACKAGES = listOf("cn.wps.moffice_eng", "com.kingsoft.wpsoffice")

    /**
     * 安全获取 FileProvider Uri。若文件不存在或路径未被 file_paths.xml 覆盖，
     * getUriForFile 会抛异常，此处捕获并返回 null，由调用方优雅降级。
     */
    fun safeUri(context: Context, file: File): Uri? = runCatching {
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    }.getOrNull()

    /**
     * 打开文档。返回 true 表示已成功发起打开动作；false 表示无法打开（文件不存在 / 无可用应用）。
     */
    fun open(context: Context, file: File): Boolean {
        if (!file.exists()) return false
        val uri = safeUri(context, file) ?: return false
        val mime = guessMime(file.name)
        val pm = context.packageManager

        // 1) WPS 优先（对齐「文档」入口：用户装了 WPS 就走 WPS）
        val wpsPkg = WPS_PACKAGES.firstOrNull { pkg ->
            runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess
        }
        if (wpsPkg != null) {
            val wpsIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                setPackage(wpsPkg)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(wpsIntent) }.onSuccess { return true }
            // WPS 无法处理该类型时落到下方系统选择器
        }

        // 2) 兜底：系统选择器（用户已装的 ONLYOFFICE / Office 等都会出现）
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
