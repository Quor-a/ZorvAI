package com.ai.assistance.quro.lanui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * LAN 控制台前端 Activity（主应用作为原生安卓前端的入口）。
 *
 * - 默认连接同设备本地后端（[LanBackendService]，127.0.0.1:8080）；
 * - 可在顶栏切换为局域网内其他设备的后端地址（跨设备场景）；
 * - 每 3 秒轮询一次 UI 快照并渲染；按钮/输入回传 action 给后端。
 * 原 ACI 与现有 UI 完全不动，本 Activity 是独立入口。
 */
class LanUiActivity : ComponentActivity() {

    private var baseUrl by mutableStateOf("http://127.0.0.1:8080")
    private var screen by mutableStateOf<LanScreen?>(null)
    private var status by mutableStateOf("未连接")
    private var urlInput by mutableStateOf("http://127.0.0.1:8080")

    private var polling = false
    private var client: LanUiClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // demo：确保同设备后端已启动；再读取实际端口决定连接地址
        LanBackendService.start(this)
        val port = LanBackendService.getPort(this).takeIf { it != 0 } ?: 8080
        baseUrl = LanUiClient.defaultLocalUrl(port)
        urlInput = baseUrl
        client = LanUiClient(baseUrl)

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize()) {
                        TopBar()
                        HorizontalDivider()
                        Box(
                            Modifier.weight(1f)
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp)
                        ) {
                            LanUiScreen(screen = screen, onAction = { a, p -> onAction(a, p) })
                        }
                    }
                }
            }
        }

        startPolling()
    }

    @Composable
    private fun TopBar() {
        Column(Modifier.padding(12.dp)) {
            Text("ZorvAI · LAN 控制台", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                label = { Text("后端地址") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { connect() }, modifier = Modifier.weight(1f)) { Text("连接") }
                OutlinedButton(onClick = { disconnect() }, modifier = Modifier.weight(1f)) { Text("断开") }
            }
            Spacer(Modifier.height(6.dp))
            Text(status, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    private fun connect() {
        val u = urlInput.trim().trimEnd('/')
        if (u.isBlank()) return
        baseUrl = u
        client = LanUiClient(baseUrl)
        startPolling()
    }

    private fun disconnect() {
        polling = false
        status = "已断开"
    }

    private fun startPolling() {
        polling = true
        lifecycleScope.launch {
            while (polling && isActive) {
                try {
                    val s = client?.fetchUi()
                    if (s != null) {
                        screen = s
                        status = "已连接 · 更新于 ${fmt(s.updatedAt)}"
                    } else {
                        status = "客户端未初始化"
                    }
                } catch (e: Throwable) {
                    status = "连接失败：${e.message}"
                }
                delay(POLL_MS)
            }
        }
    }

    private fun onAction(action: String, payload: Map<String, String>) {
        lifecycleScope.launch {
            try {
                val ok = client?.postAction(action, payload) ?: false
                status = if (ok) "已发送动作：$action" else "动作发送失败：$action"
                runCatching { client?.fetchUi()?.let { screen = it } }
            } catch (e: Throwable) {
                status = "动作异常：${e.message}"
            }
        }
    }

    override fun onDestroy() {
        polling = false
        super.onDestroy()
    }

    private fun fmt(ts: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(ts))

    companion object {
        private const val POLL_MS = 3000L
    }
}
