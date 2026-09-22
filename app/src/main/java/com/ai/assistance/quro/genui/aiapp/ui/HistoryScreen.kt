package com.ai.assistance.quro.genui.aiapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 历史对话页：界面回放（点条目恢复画布）+ 干净的往来记录。
 */
@Composable
fun HistoryScreen(
    works: List<com.ai.assistance.quro.genui.aiapp.data.GenUISessionStore.WorkItem>,
    messages: List<com.ai.assistance.quro.genui.aiapp.core.GenUIChatMessage>,
    personaName: String,
    stripAssistant: (String) -> String,
    formatTime: (Long) -> String,
    onBack: () -> Unit,
    onReplay: (com.ai.assistance.quro.genui.aiapp.data.GenUISessionStore.WorkItem) -> Unit
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("← 返回", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp,
                    modifier = Modifier.clickable { onBack() }.padding(horizontal = 8.dp, vertical = 4.dp))
                Spacer(Modifier.weight(1f))
                Text("历史对话", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(64.dp))
            }

            Text("界面回放 · 点条目恢复到画布", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(6.dp))

            if (works.isEmpty()) {
                Text("暂无界面记录", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
            works.forEach { w ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                        .clickable { onReplay(w) }
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("🎨", fontSize = 18.sp)
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            // 渲染类型徽章：这条作品当初走的是哪条通道，一眼可辨。
                            // 标题里历史遗留的「[通道] 」前缀顺手剥掉——有徽章就不需要它了。
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RenderTypeBadge(w.channel)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    w.title.removePrefix("[通道] "),
                                    fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1
                                )
                            }
                            Text(w.request, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        Text(formatTime(w.time), fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                        Text("回放 ▶", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("往来记录", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(6.dp))

            val chat = messages.filter { it.role == "user" || it.role == "assistant" }
            if (chat.isEmpty()) {
                Text("暂无往来记录", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
            chat.forEach { m ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(if (m.role == "user") "🧑" else "🤖", fontSize = 14.sp)
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(
                            if (m.role == "user") "你" else personaName.ifBlank { "AI" },
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.outline, fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (m.role == "assistant") stripAssistant(m.content).ifBlank { "（已生成界面，见上方回放）" } else m.content,
                            fontSize = 12.sp
                        )
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
