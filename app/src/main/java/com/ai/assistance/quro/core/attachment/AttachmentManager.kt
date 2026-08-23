package com.ai.assistance.quro.core.attachment

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 统一附件管理器
 * 支持：照片、屏幕内容(OCR)、通知、位置、工作区、ACI、技能等上下文
 * 格式：XML 标签内联到用户消息中
 */
class AttachmentManager(private val context: Context) {
    companion object {
        private const val TAG = "AttachmentManager"
    }

    // 附件状态
    private val _attachments = MutableStateFlow<List<AttachmentInfo>>(emptyList())
    val attachments: StateFlow<List<AttachmentInfo>> = _attachments

    private val attachmentListLock = Any()

    data class AttachmentInfo(
        val id: String,
        val fileName: String,
        val mimeType: String,
        val content: String,
        val fileSize: Long = content.length.toLong()
    )

    /**
     * 添加附件
     */
    fun addAttachment(attachment: AttachmentInfo) {
        synchronized(attachmentListLock) {
            val current = _attachments.value.toMutableList()
            // 去重：如果已存在相同 id 的附件，则替换
            current.removeAll { it.id == attachment.id }
            current.add(attachment)
            _attachments.value = current
        }
    }

    /**
     * 移除附件
     */
    fun removeAttachment(id: String) {
        synchronized(attachmentListLock) {
            _attachments.value = _attachments.value.filter { it.id != id }
        }
    }

    /**
     * 清空所有附件
     */
    fun clearAttachments() {
        synchronized(attachmentListLock) {
            _attachments.value = emptyList()
        }
    }

    /**
     * 构建附件 XML 标签
     * 格式：<attachment id="..." filename="..." type="..." size="...">content</attachment>
     */
    fun buildAttachmentXml(attachment: AttachmentInfo): String {
        return buildString {
            append("<attachment")
            append(" id=\"${attachment.id}\"")
            append(" filename=\"${escapeXml(attachment.fileName)}\"")
            append(" type=\"${escapeXml(attachment.mimeType)}\"")
            append(" size=\"${attachment.fileSize}\"")
            append(">")
            append(attachment.content)
            append("</attachment>")
        }
    }

    /**
     * 构建所有附件的 XML 标签
     */
    fun buildAllAttachmentsXml(): String {
        val currentAttachments = _attachments.value
        if (currentAttachments.isEmpty()) return ""
        return currentAttachments.joinToString(" ") { buildAttachmentXml(it) }
    }

    // ========== 上下文捕获方法 ==========

    /**
     * 捕获屏幕内容（OCR）
     */
    suspend fun captureScreenContent() = withContext(Dispatchers.IO) {
        try {
            // TODO: 实现屏幕截图和 OCR
            val captureId = "screen_ocr_${System.currentTimeMillis()}"
            val content = "屏幕内容捕获功能待实现"
            val attachment = AttachmentInfo(
                id = captureId,
                fileName = "screen_content.txt",
                mimeType = "text/plain",
                content = content
            )
            addAttachment(attachment)
            Log.d(TAG, "Screen content captured")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture screen content", e)
        }
    }

    /**
     * 捕获通知
     */
    suspend fun captureNotifications(limit: Int = 10) = withContext(Dispatchers.IO) {
        try {
            // TODO: 实现通知捕获
            val captureId = "notifications_${System.currentTimeMillis()}"
            val content = "通知捕获功能待实现"
            val attachment = AttachmentInfo(
                id = captureId,
                fileName = "notifications.json",
                mimeType = "application/json",
                content = content
            )
            addAttachment(attachment)
            Log.d(TAG, "Notifications captured")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture notifications", e)
        }
    }

    /**
     * 捕获位置
     */
    suspend fun captureLocation(highAccuracy: Boolean = true) = withContext(Dispatchers.IO) {
        try {
            // TODO: 实现位置捕获
            val captureId = "location_${System.currentTimeMillis()}"
            val content = "位置捕获功能待实现"
            val attachment = AttachmentInfo(
                id = captureId,
                fileName = "location.json",
                mimeType = "application/json",
                content = content
            )
            addAttachment(attachment)
            Log.d(TAG, "Location captured")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture location", e)
        }
    }

    /**
     * 添加工作区上下文
     */
    fun addWorkspaceContext(workspacePath: String, workspaceName: String) {
        val attachment = AttachmentInfo(
            id = "workspace_$workspacePath",
            fileName = "workspace.txt",
            mimeType = "text/plain",
            content = buildString {
                appendLine("当前工作区：$workspaceName")
                appendLine("工作区路径：$workspacePath")
                appendLine("你可以使用 workspace_write、workspace_read、workspace_list 工具操作此工作区。")
            }
        )
        addAttachment(attachment)
    }

    /**
     * 添加 ACI 上下文
     */
    fun addAciContext(aciName: String, packageName: String) {
        val attachment = AttachmentInfo(
            id = "aci_$packageName",
            fileName = "aci.txt",
            mimeType = "text/plain",
            content = buildString {
                appendLine("当前 ACI 应用：$aciName")
                appendLine("包名：$packageName")
                appendLine("你可以使用 aci_list、aci_call 工具与该应用交互。")
            }
        )
        addAttachment(attachment)
    }

    /**
     * 添加技能上下文
     */
    fun addSkillsContext(skillNames: List<String>, skillCount: Int) {
        if (skillNames.isEmpty()) return
        val attachment = AttachmentInfo(
            id = "skills_context",
            fileName = "skills.txt",
            mimeType = "text/plain",
            content = buildString {
                appendLine("用户已启用 $skillCount 个技能：${skillNames.joinToString("、")}")
                appendLine("请根据技能能力处理用户消息。")
            }
        )
        addAttachment(attachment)
    }

    /**
     * 添加当前时间上下文
     */
    fun addCurrentTimeContext() {
        val timeText = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val attachment = AttachmentInfo(
            id = "current_time",
            fileName = "time.txt",
            mimeType = "text/plain",
            content = "当前时间：$timeText"
        )
        addAttachment(attachment)
    }

    // ========== 工具方法 ==========

    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
