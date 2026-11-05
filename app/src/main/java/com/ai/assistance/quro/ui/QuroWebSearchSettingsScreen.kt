package com.ai.assistance.quro.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ai.assistance.quro.core.websearch.net.WebSearchKeys

/**
 * 「搜索服务」设置页。
 *
 * 联网检索采用「多引擎并发 + 投票」架构，本页让用户：
 * 1. 开 / 关联网总开关（关闭后 AI 调用 web_search 会明确反馈，而非静默）；
 * 2. 配置各可选商业搜索提供方的 API Key（Brave / Kagi / Serper / Tavily）——
 *    未配置的引擎自动跳过，不影响免 key 的 Bing / 百度 / DuckDuckGo；
 * 3. 配置自建 SearXNG 实例地址（可选，填了才启用该引擎）；
 * 4. 调整结果条数（1-10，影响精读篇数）。
 *
 * 所有凭据仅存本机 SharedPreferences（MODE_PRIVATE），绝不外传。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuroWebSearchSettingsScreen(onBack: () -> Unit = {}) {
    val ctx = LocalContext.current.applicationContext
    val cs = MaterialTheme.colorScheme

    // 一次性读取到本地可变状态；写操作直接落 WebSearchKeys（SharedPreferences）。
    var enabled by remember { mutableStateOf(WebSearchKeys.webSearchEnabled) }
    var braveKey by remember { mutableStateOf(WebSearchKeys.braveKey) }
    var kagiKey by remember { mutableStateOf(WebSearchKeys.kagiKey) }
    var serperKey by remember { mutableStateOf(WebSearchKeys.serperKey) }
    var tavilyKey by remember { mutableStateOf(WebSearchKeys.tavilyKey) }
    var bothubKey by remember { mutableStateOf(WebSearchKeys.bothubKey) }
    var searxngUrl by remember { mutableStateOf(WebSearchKeys.searxngUrl) }
    var resultCount by remember { mutableStateOf(WebSearchKeys.resultCount) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("搜索服务") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // ── 联网总开关 ──
            ChapterLabel("01", "联网搜索")
            SetGroup {
                Column {
                    SetRow(
                        icon = Icons.Filled.Language,
                        name = "联网搜索",
                        sub = "AI 可在需要时自主联网搜索实时信息",
                        checked = enabled,
                        onToggle = {
                            enabled = !enabled
                            WebSearchKeys.webSearchEnabled = enabled
                        },
                    )
                    HorizontalDivider()
                    Text(
                        "关闭后 AI 调用 web_search / read_url 时会明确提示已关闭；免 key 的 Bing / 百度 / DuckDuckGo 仍会随总开关一起启停。",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }

            // ── 商业搜索 API Key ──
            ChapterLabel("02", "商业搜索提供方（可选）")
            SetGroup {
                Column {
                    ApiKeyRow("Brave Search", "独立索引，与 Bing 互补；注册即送免费额度", braveKey, Icons.Filled.Key) {
                        braveKey = it; WebSearchKeys.braveKey = it
                    }
                    HorizontalDivider()
                    ApiKeyRow("Kagi Search", "付费账号，需 Kagi API Token", kagiKey, Icons.Filled.Key) {
                        kagiKey = it; WebSearchKeys.kagiKey = it
                    }
                    HorizontalDivider()
                    ApiKeyRow("Serper.dev", "Google 结果商业中转，稳定 JSON", serperKey, Icons.Filled.Key) {
                        serperKey = it; WebSearchKeys.serperKey = it
                    }
                    HorizontalDivider()
                    ApiKeyRow("Tavily", "面向 AI 的搜索 API，自带摘要", tavilyKey, Icons.Filled.Key) {
                        tavilyKey = it; WebSearchKeys.tavilyKey = it
                    }
                    HorizontalDivider()
                    ApiKeyRow("BotHub 搜索", "BotHub 网关（OpenAI 兼容）联网检索模型，填 BotHub API Key", bothubKey, Icons.Filled.Key) {
                        bothubKey = it; WebSearchKeys.bothubKey = it
                    }
                    HorizontalDivider()
                    Text(
                        "未配置 key 的提供方会自动跳过，不影响免 key 引擎。所有密钥仅存本机，绝不上传。",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }

            // ── 自建 SearXNG ──
            ChapterLabel("03", "自建 SearXNG（可选）")
            SetGroup {
                Column {
                    ApiKeyRow(
                        "SearXNG 实例地址",
                        "自建元搜索，无配额、无 key、无封锁，如 https://your-searx.example.com",
                        searxngUrl,
                        Icons.Filled.Link,
                        isUrl = true,
                    ) {
                        searxngUrl = it; WebSearchKeys.searxngUrl = it
                    }
                }
            }

            // ── 结果条数 ──
            ChapterLabel("04", "结果条数")
            SetGroup {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Tune, null, tint = cs.primary)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "精读篇数：$resultCount",
                            style = MaterialTheme.typography.bodyLarge,
                            color = cs.onSurface,
                        )
                    }
                    Slider(
                        value = resultCount.toFloat(),
                        onValueChange = { resultCount = it.toInt() },
                        onValueChangeFinished = { WebSearchKeys.resultCount = resultCount },
                        valueRange = 1f..10f,
                        steps = 8,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    Text(
                        "影响 AI 联网后精读并打包进上下文的资料篇数，越大越全但越慢、越占上下文。",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

/** API Key 输入行：图标 + 名称/说明 + 密文输入框，输入即持久化。 */
@Composable
private fun ApiKeyRow(
    name: String,
    desc: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isUrl: Boolean = false,
    onChange: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = cs.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface)
                if (desc.isNotBlank()) {
                    Text(desc, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
        }
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            placeholder = { Text(if (isUrl) "https://…" else "粘贴 API Key…") },
            visualTransformation = if (isUrl) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = if (isUrl) KeyboardType.Uri else KeyboardType.Text),
            singleLine = true,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }
}
