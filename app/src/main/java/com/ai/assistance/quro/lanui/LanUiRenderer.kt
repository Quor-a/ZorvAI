package com.ai.assistance.quro.lanui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 后端 UI 描述的 Compose 渲染器（递归渲染 [LanComponent]）。
 * 组件集合刻意精简（heading/text/button/card/divider/spacer/listitem/input），
 * 真实场景可在此扩展；前端本身不发版，界面由后端 JSON 决定。
 */
@Composable
fun LanUiScreen(
    screen: LanScreen?,
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
            s.components.forEach { comp -> LanComponentView(comp, onAction) }
        } ?: run {
            Text("未连接到后端", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun LanComponentView(comp: LanComponent, onAction: (String, Map<String, String>) -> Unit) {
    when (comp) {
        is LanComponent.Heading -> {
            Spacer(Modifier.height(8.dp))
            Text(comp.text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        is LanComponent.Text -> {
            Spacer(Modifier.height(4.dp))
            Text(comp.text, style = MaterialTheme.typography.bodyMedium)
        }
        is LanComponent.Button -> {
            Spacer(Modifier.height(8.dp))
            Button(onClick = { onAction(comp.action, emptyMap()) }, modifier = Modifier.fillMaxWidth()) {
                Text(comp.label)
            }
        }
        is LanComponent.Card -> {
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
        is LanComponent.Divider -> {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
        }
        is LanComponent.Spacer -> Spacer(Modifier.height(12.dp))
        is LanComponent.ListItem -> {
            Spacer(Modifier.height(4.dp))
            Text("• ${comp.text}", style = MaterialTheme.typography.bodyMedium)
        }
        is LanComponent.Input -> {
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
