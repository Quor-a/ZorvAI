package com.ai.assistance.quro.core.aidlaci

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 本地 ACI 控制台 SDUI 的 Compose 渲染器（递归渲染 [AciComponent]）。
 * 纯 UI，不持有任何网络/Binder 逻辑；数据来自 AciConsoleModel.parse(console_ui 快照)。
 *
 * 语义为「本地 ACI 控制台」，通过 Binder 拉取受控端（浏览器）的 console_ui 快照并回传
 * console_action，与「LAN/WiFi 远程控制台」范式彻底解耦。
 */
@Composable
fun AciConsoleScreen(
    screen: AidlAciScreen?,
    onAction: (action: String, payload: Map<String, String>) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.padding(16.dp).fillMaxWidth()) {
        screen?.let { s ->
            Text(s.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (s.subtitle.isNotBlank()) {
                Text(s.subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            s.components.forEach { comp -> AciComponentView(comp, onAction) }
        } ?: run {
            Text("未连接到受控端", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun AciComponentView(comp: AciComponent, onAction: (String, Map<String, String>) -> Unit) {
    when (comp) {
        is AciComponent.Heading -> {
            Spacer(Modifier.height(8.dp))
            Text(comp.text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        is AciComponent.Text -> {
            Spacer(Modifier.height(4.dp))
            Text(comp.text, style = MaterialTheme.typography.bodyMedium)
        }
        is AciComponent.Button -> {
            Spacer(Modifier.height(8.dp))
            Button(onClick = { onAction(comp.action, emptyMap()) }, modifier = Modifier.fillMaxWidth()) {
                Text(comp.label)
            }
        }
        is AciComponent.Card -> {
            Spacer(Modifier.height(8.dp))
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    if (comp.title.isNotBlank()) {
                        Text(comp.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(comp.body, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        is AciComponent.Divider -> {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
        }
        is AciComponent.Spacer -> Spacer(Modifier.height(12.dp))
        is AciComponent.ListItem -> {
            Spacer(Modifier.height(4.dp))
            Text("• ${comp.text}", style = MaterialTheme.typography.bodyMedium)
        }
        is AciComponent.Input -> {
            var text by remember(comp.key) { mutableStateOf(comp.value) }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(comp.label) },
                placeholder = { Text(comp.placeholder) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { onAction(comp.action, mapOf("value" to text, "key" to comp.key)) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("提交")
            }
        }
    }
}
