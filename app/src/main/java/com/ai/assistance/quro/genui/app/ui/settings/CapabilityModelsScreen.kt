package com.ai.assistance.quro.genui.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.genui.app.store.GenStore
import com.ai.assistance.quro.genui.app.ui.theme.GenTheme

/**
 * 能力模型配置 —— 视觉理解 / 图片生成 / 视频生成 / TTS / STT / 声音克隆 / 视频通话 / 语音通话。
 *
 * 设计（LLM 驱动的可扩展体系）：每个能力槽是独立的 OpenAI 兼容端点配置
 * （baseUrl + apiKey + model + protocol）。已对接的工具（vision_analyze / image_generate /
 * tts_speak / 语音输入）在运行时读取对应槽位；未对接的槽位为日后开发预留——
 * 新工具只要按槽位取配置即可驱动，无需再改配置层。
 */
@Composable
fun CapabilityModelsScreen(store: GenStore, onBack: () -> Unit) {
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = GenTheme.Screen,
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) },
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().background(GenTheme.Screen)
                .padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.TextButton(onClick = onBack) {
                    Text("← 返回", color = GenTheme.Amber, fontSize = 13.sp)
                }
                Spacer(Modifier.weight(1f))
                Text("能力模型", color = GenTheme.Text, fontSize = 15.sp)
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(72.dp))
            }
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(14.dp)
        ) {
            Text(
                "按能力独立配置 OpenAI 兼容端点。已对接：视觉理解（vision_analyze）、图片生成（image_generate）、语音合成（tts_speak）。" +
                    "其余槽位为后续工具预留，先配置先生效。",
                color = GenTheme.Dim, fontSize = 10.5.sp, lineHeight = 15.sp
            )
            Spacer(Modifier.height(12.dp))

            store.CAP_SLOTS.forEach { slot ->
                val saved = remember(slot) { store.loadCapability(slot) }
                var enabled by remember(slot) { mutableStateOf(saved["baseUrl"]?.isNotBlank() == true) }
                var baseUrl by remember(slot) { mutableStateOf(saved["baseUrl"] ?: "") }
                var apiKey by remember(slot) { mutableStateOf(saved["apiKey"] ?: "") }
                var model by remember(slot) { mutableStateOf(saved["model"] ?: "") }
                var msg by remember(slot) { mutableStateOf("") }

                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(GenTheme.Panel)
                        .padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(store.CAP_LABELS[slot] ?: slot, color = GenTheme.Text,
                            fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Switch(
                            checked = enabled,
                            onCheckedChange = { enabled = it },
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = GenTheme.Amber,
                                checkedThumbColor = GenTheme.Screen
                            )
                        )
                    }
                    if (enabled) {
                        Spacer(Modifier.height(6.dp))
                        CapField("Base URL（如 https://api.openai.com/v1）", baseUrl) { baseUrl = it }
                        Spacer(Modifier.height(6.dp))
                        CapField("API Key", apiKey, isPassword = true) { apiKey = it }
                        Spacer(Modifier.height(6.dp))
                        CapField("模型 ID（如 gpt-4o-mini / tts-1 / whisper-1 / dall-e-3）", model) { model = it }
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = {
                                    if (baseUrl.isBlank() || model.isBlank()) {
                                        msg = "Base URL 与模型 ID 必填"
                                    } else {
                                        store.saveCapability(slot, baseUrl.trim(), apiKey.trim(), model.trim())
                                        msg = "已保存 · ${store.CAP_LABELS[slot]}端点就绪"
                                    }
                                    
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = GenTheme.Amber, contentColor = GenTheme.Screen)
                            ) { Text("保存", fontSize = 12.sp) }
                            Spacer(Modifier.width(10.dp))
                            Text(msg, color = GenTheme.Dim, fontSize = 10.sp)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
private fun CapField(label: String, value: String, isPassword: Boolean = false, onChange: (String) -> Unit) {
    Column {
        Text(label, color = GenTheme.Dim, fontSize = 9.5.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(2.dp))
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(
                color = GenTheme.Text, fontSize = 12.sp, fontFamily = FontFamily.Monospace
            ),
            visualTransformation = if (isPassword)
                androidx.compose.ui.text.input.PasswordVisualTransformation()
            else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier = Modifier.fillMaxWidth()
                .background(GenTheme.Screen, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp)
        )
    }
}
