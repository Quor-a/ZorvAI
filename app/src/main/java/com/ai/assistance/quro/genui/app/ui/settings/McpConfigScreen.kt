package com.ai.assistance.quro.genui.app.ui.settings

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.genui.app.agent.McpManager
import com.ai.assistance.quro.genui.app.ui.theme.GenTheme
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

/**
 * MCP 服务器 —— 连接外部 Model Context Protocol 服务器（Streamable HTTP 传输）。
 *
 * Agent 决策循环会自动聚合所有已启用服务器的 tools（resources/prompts 通过
 * 后续 meta 工具按需读取）；每个工具族 "mcp" 默认每次询问授权，可在权限屏改。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpConfigScreen(
    context: Context,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf(McpManager.servers(context)) }
    var editing by remember { mutableStateOf<McpManager.ServerCfg?>(null) }
    var adding by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    // 状态快照：连接操作后整体刷新
    var statusTick by remember { mutableStateOf(0) }

    BackHandler { onBack() }

    fun refresh() {
        list = McpManager.servers(context)
        statusTick++
    }

    Scaffold(
        containerColor = GenTheme.Screen,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().background(GenTheme.Screen)
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) { Text("← 返回", color = GenTheme.Amber, fontSize = 13.sp) }
                Spacer(Modifier.weight(1f))
                Text("MCP 服务器", color = GenTheme.Text, fontSize = 15.sp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { adding = true }) { Text("+ 添加", color = GenTheme.Amber, fontSize = 13.sp) }
            }
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
        ) {
            Text(
                "让 Agent 通过 MCP 协议调用外部工具。填 MCP 服务器的 HTTP 端点（Streamable HTTP），" +
                    "如 https://host/mcp。可选自定义 Header（JSON，如 {\"Authorization\":\"Bearer xxx\"}）。" +
                    "保存并连接后，服务器的工具会自动出现在 Agent 的工具列表里（名称前缀 mcp_）。",
                color = GenTheme.Dim, fontSize = 11.sp, lineHeight = 16.sp
            )
            Spacer(Modifier.height(14.dp))

            if (list.isEmpty() && !adding) {
                Text("还没有添加 MCP 服务器。", color = GenTheme.Dim, fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 24.dp))
            }

            for (cfg in list) {
                val st = McpManager.statusOf(cfg.id)
                Card(
                    Modifier.fillMaxWidth().padding(bottom = 10.dp)
                        .border(1.dp, GenTheme.Panel, RoundedCornerShape(10.dp)),
                    colors = CardDefaults.cardColors(containerColor = GenTheme.Panel.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(cfg.name, color = GenTheme.Text, fontSize = 14.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                modifier = Modifier.weight(1f))
                            Switch(checked = cfg.enabled, onCheckedChange = { on ->
                                val nl = McpManager.servers(context).map {
                                    if (it.id == cfg.id) it.copy(enabled = on) else it
                                }
                                McpManager.save(context, nl)
                                if (!on) McpManager.disconnect(cfg.id)
                                refresh()
                            })
                        }
                        Text(cfg.url, color = GenTheme.Dim, fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace, maxLines = 2)
                        Spacer(Modifier.height(6.dp))
                        val stColor = when {
                            st.startsWith("已连接") -> androidx.compose.ui.graphics.Color(0xFF4C9A6A)
                            st.startsWith("连接失败") -> GenTheme.Amber
                            else -> GenTheme.Dim
                        }
                        Text("● $st", color = stColor, fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace, maxLines = 2)
                        Spacer(Modifier.height(8.dp))
                        Row {
                            TextButton(onClick = {
                                busy = cfg.id
                                scope.launch {
                                    val msg = try {
                                        McpManager.connect(context, cfg)
                                    } catch (e: Exception) {
                                        "连接失败：${e.message?.take(90)}"
                                    }
                                    snackbar.showSnackbar(msg)
                                    busy = null; refresh()
                                }
                            }, enabled = busy == null) {
                                Text(if (busy == cfg.id) "连接中…" else "连接 / 测试",
                                    color = GenTheme.Amber, fontSize = 12.sp)
                            }
                            TextButton(onClick = { editing = cfg }) {
                                Text("编辑", color = GenTheme.Dim, fontSize = 12.sp)
                            }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = {
                                McpManager.disconnect(cfg.id)
                                McpManager.save(context, McpManager.servers(context).filter { it.id != cfg.id })
                                refresh()
                            }) {
                                Text("删除", color = GenTheme.Amber, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    // —— 添加 / 编辑表单 ——
    val showForm = adding || editing != null
    if (showForm) {
        val initial = editing
        var name by remember(showForm) { mutableStateOf(initial?.name ?: "") }
        var url by remember(showForm) { mutableStateOf(initial?.url ?: "") }
        var headers by remember(showForm) {
            mutableStateOf(initial?.headers?.toString()?.takeIf { it != "{}" } ?: "")
        }
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { adding = false; editing = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Column(
                Modifier.fillMaxWidth(0.94f)
                    .background(GenTheme.Screen, RoundedCornerShape(14.dp))
                    .verticalScroll(rememberScrollState()).padding(16.dp)
            ) {
                Text(if (initial == null) "添加 MCP 服务器" else "编辑 MCP 服务器",
                    color = GenTheme.Amber, fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(12.dp))
                Text("名称", color = GenTheme.Dim, fontSize = 11.sp)
                BasicTextField(value = name, onValueChange = { name = it },
                    textStyle = TextStyle(color = GenTheme.Text, fontSize = 13.sp),
                    cursorBrush = SolidColor(GenTheme.Amber),
                    modifier = Modifier.fillMaxWidth().background(GenTheme.Panel, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    singleLine = true, decorationBox = { inner ->
                        if (name.isEmpty()) Text("例：filesystem", color = GenTheme.Dim, fontSize = 13.sp)
                        inner()
                    })
                Spacer(Modifier.height(10.dp))
                Text("端点 URL（Streamable HTTP）", color = GenTheme.Dim, fontSize = 11.sp)
                BasicTextField(value = url, onValueChange = { url = it },
                    textStyle = TextStyle(color = GenTheme.Text, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                    cursorBrush = SolidColor(GenTheme.Amber),
                    modifier = Modifier.fillMaxWidth().background(GenTheme.Panel, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    singleLine = true, decorationBox = { inner ->
                        if (url.isEmpty()) Text("https://example.com/mcp", color = GenTheme.Dim, fontSize = 13.sp)
                        inner()
                    })
                Spacer(Modifier.height(10.dp))
                Text("自定义 Header（JSON，可选）", color = GenTheme.Dim, fontSize = 11.sp)
                BasicTextField(value = headers, onValueChange = { headers = it },
                    textStyle = TextStyle(color = GenTheme.Text, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                    cursorBrush = SolidColor(GenTheme.Amber),
                    modifier = Modifier.fillMaxWidth().background(GenTheme.Panel, RoundedCornerShape(8.dp))
                        .padding(10.dp).heightIn(min = 64.dp),
                    decorationBox = { inner ->
                        if (headers.isEmpty()) Text("{\"Authorization\":\"Bearer xxx\"}",
                            color = GenTheme.Dim, fontSize = 12.sp)
                        inner()
                    })
                Spacer(Modifier.height(14.dp))
                Row {
                    TextButton(onClick = { adding = false; editing = null }) {
                        Text("取消", color = GenTheme.Dim, fontSize = 13.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        val hObj = runCatching {
                            if (headers.isBlank()) JSONObject() else JSONObject(headers)
                        }.getOrNull()
                        if (name.isBlank() || url.isBlank() || hObj == null) {
                            scope.launch { snackbar.showSnackbar("名称与 URL 必填，Headers 须为合法 JSON") }
                            return@TextButton
                        }
                        val cfg = (initial?.copy(name = name, url = url.trim(), headers = hObj)
                            ?: McpManager.ServerCfg(
                                id = UUID.randomUUID().toString().take(8),
                                name = name.trim(), url = url.trim(),
                                headers = hObj, enabled = true))
                        val nl = McpManager.servers(context).filter { it.id != cfg.id } + cfg
                        McpManager.save(context, nl)
                        adding = false; editing = null
                        refresh()
                        // 保存后立即试连，给即时反馈
                        scope.launch {
                            val msg = try { McpManager.connect(context, cfg) }
                            catch (e: Exception) { "已保存，但连接失败：${e.message?.take(70)}" }
                            snackbar.showSnackbar(msg); refresh()
                        }
                    }) {
                        Text("保存并连接", color = GenTheme.Amber, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
