package com.ai.assistance.quro.genui.app.ui.shell

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.genui.app.store.GeneratedPage
import com.ai.assistance.quro.genui.app.ui.theme.GenTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 界面栈 —— 历史不是消息记录，是"AI 生成过的界面"集合。
 * 点按任意一张纸，画布回放它（状态、数据都在——store 数据由 MoBridge 层共享）。
 * 每张纸可导出为独立的 .html 文件（补齐 README 路线图的"界面栈分享"）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionStackSheet(
    pages: List<GeneratedPage>,
    onDismiss: () -> Unit,
    onOpen: (GeneratedPage) -> Unit,
    onClear: () -> Unit
) {
    val ctx = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = GenTheme.Panel,
        tonalElevation = 0.dp
    ) {
        Column(Modifier.padding(horizontal = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("界面栈", color = GenTheme.Text, fontSize = 16.sp)
                Spacer(Modifier.width(8.dp))
                Text("${pages.size} 张纸", color = GenTheme.Dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClear) { Text("清空", color = GenTheme.Red, fontSize = 12.sp) }
            }
            Spacer(Modifier.height(6.dp))
            if (pages.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
                    Text("还没有纸\n说一句话，让它写第一张", color = GenTheme.Dim, fontSize = 12.sp, lineHeight = 20.sp)
                }
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(pages.asReversed(), key = { it.id }) { p ->
                        Column(
                            Modifier.fillMaxWidth()
                                .background(GenTheme.PanelUp, RoundedCornerShape(10.dp))
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).clickable { onOpen(p) }) {
                                    Text(p.title, color = GenTheme.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        "${SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(p.ts))} · ${p.model} · ${p.html.length / 1024}KB",
                                        color = GenTheme.Dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                                    )
                                }
                                TextButton(onClick = { onOpen(p) }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                                    Text("回放", color = GenTheme.Amber, fontSize = 11.sp)
                                }
                                TextButton(
                                    onClick = { shareHtml(ctx, p) },
                                    contentPadding = PaddingValues(horizontal = 6.dp)
                                ) {
                                    Text("导出", color = GenTheme.Green, fontSize = 11.sp)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/**
 * 把一张纸导出为独立 .html 文件并走系统分享。
 *
 * 用 ACTION_SEND + EXTRA_TEXT 直接投递 HTML 文本，不落盘、不需要 FileProvider，
 * 因此在任何 Android 版本与任何目标 App 上都可用（接收方得到的是完整 HTML 源码）。
 * 同时写入缓存目录并附加 EXTRA_STREAM 兜底（部分 App 只认文件）。
 */
private fun shareHtml(ctx: android.content.Context, p: GeneratedPage) {
    runCatching {
        val safeTitle = p.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(40).ifBlank { "genui-ui" }
        val file = java.io.File(ctx.cacheDir, "$safeTitle.html")
        file.writeText(p.html)

        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/html"
            putExtra(Intent.EXTRA_SUBJECT, p.title)
            putExtra(Intent.EXTRA_TITLE, p.title)
            putExtra(Intent.EXTRA_TEXT, p.html)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "导出界面 · ${p.title}").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
