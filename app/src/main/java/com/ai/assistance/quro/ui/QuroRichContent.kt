package com.ai.assistance.quro.ui
import androidx.compose.ui.res.stringResource
import com.ai.assistance.quro.R

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.ai.assistance.quro.core.QuroAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 代码块（IDE 风格）：等宽字体 + 横向滚动 + 语言标签 + 一键复制。 */
@Composable
fun CodeBlock(code: String, lang: String) {
    val clipboard = LocalClipboardManager.current
    Surface(
        color = Color(0xFF0F172A),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    lang.ifBlank { "code" },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF94A3B8),
                )
                IconButton(
                    onClick = { clipboard.setText(AnnotatedString(code)) },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.qk_00258),
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            HorizontalDivider(color = Color(0xFF1E293B))
            Text(
                code,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFE2E8F0),
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
            )
        }
    }
}

/** 图片附件卡片：本地解码并显示。 */
@Composable
fun ImageAttachmentCard(att: QuroAttachment) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(att.uri) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(att.uri) }.getOrNull()
        }
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        if (bitmap != null) {
            // 自适应比例：按图片原始宽高比决定气泡高度，铺满宽度；
            // 超高（竖图）时在 260dp 上限内裁切，避免被拉成统一高度框。
            val bmp = bitmap!!.asImageBitmap()
            val ratio = bmp.width.toFloat() / bmp.height.toFloat().coerceAtLeast(1f)
            Image(
                bitmap = bmp,
                contentDescription = att.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(ratio)
                    .heightIn(max = 260.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
        } else {
            Text("🖼️ ${att.name}", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** 视频附件卡片：内嵌播放器。 */
@Composable
fun VideoAttachmentCard(att: QuroAttachment) {
    val ctx = LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        AndroidView(
            factory = { context ->
                VideoView(context).apply {
                    setVideoPath(att.uri)
                    val mc = MediaController(context)
                    mc.setAnchorView(this)
                    setMediaController(mc)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 280.dp),
        )
    }
}

/** 文件附件卡片：图标 + 名称 + 大小，点击用系统查看器打开。 */
@Composable
fun FileAttachmentCard(att: QuroAttachment) {
    val ctx = LocalContext.current
    val sizeText = if (att.size > 0) " · ${(att.size / 1024.0).toInt()} KB" else ""
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clickable {
                runCatching {
                    val uri = FileProvider.getUriForFile(
                        ctx,
                        ctx.packageName + ".fileprovider",
                        File(att.uri),
                    )
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, att.mime.ifBlank { "application/octet-stream" })
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    ctx.startActivity(intent)
                }
            },
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(att.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                Text(stringResource(R.string.qk_02368, (sizeText).toString()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// 🔴 已删除旧版 `ThinkingCard` / `TypingIndicator`（2026-11-05 全面排查）：
//   · ThinkingCard 默认 `expanded = true` 且把 reasoning **全文平铺**，就是
//     「旧的思考显示混乱 / 收不起来」的那套实现；全项目零调用，留着只会被误接回渲染链。
//   · TypingIndicator 是第三套「思考中…」等待指示，与 ChatScreen.WaitingDots /
//     WorkingIndicator 重复。状态行是 spinner↔完成态二选一，不做叠加。
//   现在唯一口径：ChatScreen.ThinkFoldRow（默认折叠一行）+ ThinkProcessSheet（全量按需）
//   + WebUI renderThink（最近 3 步），等待指示由 WaitingDots / WorkingIndicator 互斥承担。

/** 一组附件的渲染（图片/视频/文件分派到对应卡片）。 */
@Composable
fun AttachmentList(attachments: List<QuroAttachment>) {
    Column(Modifier.fillMaxWidth()) {
        attachments.forEach { att ->
            when (att.type) {
                "image" -> ImageAttachmentCard(att)
                "video" -> VideoAttachmentCard(att)
                else -> FileAttachmentCard(att)
            }
        }
    }
}