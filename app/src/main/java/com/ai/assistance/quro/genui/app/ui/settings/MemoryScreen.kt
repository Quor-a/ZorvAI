package com.ai.assistance.quro.genui.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.genui.app.agent.tools.BuiltinTools
import com.ai.assistance.quro.genui.app.store.GenStore
import com.ai.assistance.quro.genui.app.ui.theme.GenTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 记忆库屏 —— 模型的长期记忆（memory.* 工具写入的内容）。
 * 可查看、删除单条、清空；记忆索引每次生成都会注入系统提示。
 */
@Composable
fun MemoryScreen(store: GenStore, onBack: () -> Unit) {
    val tools = remember { BuiltinTools(store.context()) }
    var items by remember { mutableStateOf(tools.memory.list()) }
    var confirmClear by remember { mutableStateOf(false) }
    // 单条删除走确认：记忆是模型长期积累的，误删代价高，不该一点就没
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    BackHandler { onBack() }

    fun refresh() { items = tools.memory.list() }

    Scaffold(
        containerColor = GenTheme.Screen,
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().background(GenTheme.Screen).padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← 返回", color = GenTheme.Amber, fontSize = 13.sp) }
                Spacer(Modifier.weight(1f))
                Text("记忆库", color = GenTheme.Text, fontSize = 15.sp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { confirmClear = true }) { Text("清空", color = GenTheme.Red, fontSize = 12.sp) }
            }
        }
    ) { pad ->
        val arr = items.optJSONArray("items")
        Column(Modifier.padding(pad).fillMaxSize()) {
            Text(
                "模型通过 memory.write 自主记录的用户偏好与事实；索引每次生成注入。",
                color = GenTheme.Dim, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            if (arr == null || arr.length() == 0) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("记忆库为空\n和它说「记住我喜欢…」试试", color = GenTheme.Dim, fontSize = 12.sp, lineHeight = 20.sp)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
                    items((0 until arr.length()).toList()) { idx ->
                        val o = arr.getJSONObject(idx)
                        Column(
                            Modifier.fillMaxWidth().background(GenTheme.Panel, RoundedCornerShape(10.dp))
                                .clickable { pendingDelete = o.optString("key") }
                                .padding(12.dp)
                        ) {
                            Row {
                                Text(o.optString("key"), color = GenTheme.Amber, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                Spacer(Modifier.weight(1f))
                                Text(
                                    SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(o.optLong("ts"))),
                                    color = GenTheme.Dim, fontSize = 9.sp, fontFamily = FontFamily.Monospace
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(o.optString("content"), color = GenTheme.Text, fontSize = 12.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                            Text("点按可删除此条", color = GenTheme.Dim.copy(alpha = .7f), fontSize = 9.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }

        pendingDelete?.let { key ->
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                containerColor = GenTheme.Panel,
                title = { Text("删除这条记忆？", color = GenTheme.Text, fontSize = 15.sp) },
                text = {
                    Column {
                        Text(key, color = GenTheme.Amber, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.height(6.dp))
                        Text("删除后模型将不再记得这条信息。", color = GenTheme.Dim, fontSize = 11.sp)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        tools.memory.delete(key); refresh(); pendingDelete = null
                    }) { Text("删除", color = GenTheme.Red) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDelete = null }) { Text("取消", color = GenTheme.Dim) }
                }
            )
        }

        if (confirmClear) AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = GenTheme.Panel,
            title = { Text("清空记忆库？", color = GenTheme.Text, fontSize = 15.sp) },
            text = { Text("模型的所有长期记忆将被删除，且不可恢复。", color = GenTheme.Dim, fontSize = 12.sp) },
            confirmButton = {
                TextButton(onClick = {
                    tools.memory.clear(); refresh(); confirmClear = false
                }) { Text("全部删除", color = GenTheme.Red) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消", color = GenTheme.Dim) } }
        )
    }
}
