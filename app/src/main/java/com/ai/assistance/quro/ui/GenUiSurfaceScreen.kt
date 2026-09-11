package com.ai.assistance.quro.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.ai.assistance.quro.ui.genui.GenUiCanvas
import com.ai.assistance.quro.ui.icons.LucideIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 真正的「非文本 GenUI 渲染面」（忠实移植自 GenUI GenScaffold + A2UIRenderer）。
 *
 * 与「普通文本聊天框 / 带 GenUI 标签的聊天框」本质不同：这里**没有文本气泡流**，
 * 整个界面就是 AI 写出的一张完整 HTML 文档，由 WebView 画布渲染出来。
 * 端上提供：离线运行时（echarts/three/vue/react/mermaid/gsap/anime/countup，经 /assets/runtimes/）、
 * MoBridge 设备桥（time/store/notify/haptics/clipboard/net/device/ui）、原生组件（ui.widget → 真实 Android 控件叠层）。
 *
 * 底部只有一条 prompt 输入。提交后 QuroAssistant 一次性生成完整 HTML → 流式灌入画布。
 * 安全边界（与 GenUI 一致）：禁文件/内容访问；外部网络仅走 bridge 的 net.proxy（https + 限流）；
 * 外部链接交给系统浏览器；尊重 A2UI 红线——绝不在画布外执行任何未经验证的脚本。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenUiSurfaceScreen(
    vm: QuroChatViewModel,
    modelVm: QuroModelConfigViewModel,
    darkMode: Boolean = false,
    onToggleDark: () -> Unit = {},
) {
    val ctx = LocalContext.current.applicationContext
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }

    val conversations by vm.conversations.collectAsState()
    val currentId by vm.currentId.collectAsState()
    val generatingIds by vm.generatingIds.collectAsState()
    val history = conversations.map { it.toHistoryItem(it.id == currentId) }

    // ── 渲染状态（本屏持有）──
    var isBusy by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf(TextFieldValue("")) }
    var browserUrl by remember { mutableStateOf<String?>(null) }
    var widgetState by remember { mutableStateOf<Pair<String, String>?>(null) }

    // ── 画布 ──
    val canvasHost = remember { mutableStateOf<GenUiCanvas?>(null) }
    // 进程内切换会话时的本地回放缓存；跨进程重生走 vm.lastGenUiHtml 从持久化恢复
    val drawnHtml = remember { mutableStateMapOf<String, String>() }

    fun generate(prompt: String) {
        val p = prompt.trim()
        if (p.isBlank() || isBusy) return
        val cid = currentId
        isBusy = true
        errorText = null
        input = TextFieldValue("")
        scope.launch(Dispatchers.IO) {
            try {
                // 走 vm 统一管线：记忆注入 + 灵魂/人格注入 + GenUiPrompt + 工具循环 + 会话落盘
                val html = vm.generateGenUi(p)
                val clean = stripFences(html)
                if (clean.isBlank()) {
                    withContext(Dispatchers.Main) { errorText = "模型未输出任何界面内容，请换一种说法重试。" }
                } else {
                    drawnHtml[cid] = clean
                    withContext(Dispatchers.Main) {
                        canvasHost.value?.let { cv ->
                            cv.begin(null)
                            chunkHtml(clean).forEach { cv.writeChunk(it) }
                            cv.end()
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { errorText = "生成失败：${e.message}" }
            } finally {
                withContext(Dispatchers.Main) { isBusy = false }
            }
        }
    }

    // 切换会话：优先回放本地缓存；无则尝试从 vm 持久化会话恢复（跨进程重生后仍能回放）
    LaunchedEffect(currentId) {
        canvasHost.value?.let { cv ->
            val html = drawnHtml[currentId] ?: vm.lastGenUiHtml(currentId)
            if (html != null) {
                cv.begin(null)
                chunkHtml(html).forEach { cv.writeChunk(it) }
                cv.end()
            } else {
                cv.begin(null)
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            HistoryDrawer(
                history = history,
                onClose = { scope.launch { drawerState.close() } },
                onNew = { vm.newConversation(); scope.launch { drawerState.close() } },
                onNewGenUi = { vm.newConversation(genUiType = "genui"); scope.launch { drawerState.close() } },
                onPick = { id -> vm.selectConversation(id); scope.launch { drawerState.close() } },
                onCopyAll = {},
                onDelete = { vm.deleteConversation(it) },
                onDeleteAll = { vm.deleteAllConversations() },
                scaled = { it.sp },
                generatingIds = generatingIds,
            )
        },
    ) {
        Scaffold(
            containerColor = cs.background,
            topBar = {
                TopAppBar(
                    title = { Text("GenUI · 生成式界面", color = cs.onSurface) },
                    navigationIcon = {
                        IconButton(onClick = openDrawer) {
                            LucideIcon("panel_left", "菜单", Modifier.size(24.dp), tint = cs.onSurface)
                        }
                    },
                    actions = {
                        if (isBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp).padding(end = 12.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.surface),
                )
            },
        ) { pad ->
            Column(Modifier.fillMaxSize().padding(pad)) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(if (darkMode) Color(0xFF0F1115) else Color(0xFFFCFAF5)),
                ) {
                    if (errorText != null && drawnHtml[currentId] == null) {
                        Box(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
                            contentAlignment = Alignment.Center) {
                            Text(errorText ?: "", color = cs.error, fontSize = 13.sp)
                        }
                    }
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { c: Context ->
                            WebView(c).also { wv ->
                                wv.setBackgroundColor(0) // 透明：露出主题色画布底，深色模式不刺眼
                                val cv = GenUiCanvas(
                                    c, wv,
                                    dark = darkMode,
                                    onFirstPaint = {},
                                    onPageTitle = {},
                                    onBridgeCall = {},
                                    onWidget = { kind, payload -> widgetState = kind to payload },
                                    onOpenLink = { url -> openInBrowser(ctx, url) },
                                )
                                canvasHost.value = cv
                                cv.begin(null)
                            }
                        },
                    )
                }

                // 底部单条 prompt 输入
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("描述你想要的界面 / 直接下指令…") },
                        maxLines = 4,
                        enabled = !isBusy,
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { generate(input.text) }, enabled = !isBusy) {
                        LucideIcon("arrow_up", "生成", Modifier.size(26.dp), tint = cs.primary)
                    }
                }
            }
        }

        // 原生组件（ui.widget）底部弹出：真实 Android 控件叠层
        widgetState?.let { (kind, payload) ->
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = { widgetState = null },
                sheetState = sheetState,
                containerColor = cs.surface,
            ) {
                NativeWidgetSheet(
                    kind = kind,
                    payload = payload,
                    onResult = { json -> canvasHost.value?.dispatchWidgetEvent(json) },
                    onDismiss = { widgetState = null },
                )
            }
        }
    }

    // 外部链接：系统浏览器
    browserUrl?.let { url ->
        Box(Modifier.fillMaxSize().zIndex(200f).background(cs.background)) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { browserUrl = null }) {
                        LucideIcon("x", "关闭", Modifier.size(22.dp), tint = cs.onSurface)
                    }
                }
                AndroidView(
                    factory = { c: Context ->
                        WebView(c).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            loadUrl(url)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

// ────────────────────────── 原生组件（MoBridge.ui.widget）──────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NativeWidgetSheet(
    kind: String,
    payload: String,
    onResult: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val data = remember(payload) { runCatching { JSONObject(payload) }.getOrDefault(JSONObject()) }
    val title = data.optString("title", "")
    val key = data.optString("key", "")

    Column(Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text(title.ifBlank { kind }, color = cs.onSurface, fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        when (kind) {
            "stat" -> {
                val value = data.optString("value", "")
                val delta = data.optString("delta", "")
                Text(value, fontSize = 36.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = cs.onSurface)
                if (delta.isNotBlank()) Text(delta, fontSize = 14.sp, color = cs.primary)
                val trend = jsonIntArray(data.optJSONArray("trend"))
                if (trend.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Sparkline(trend)
                }
            }
            "bar" -> {
                val items = jsonObjArray(data.optJSONArray("data"))
                val max = (items.maxOfOrNull { it.optDouble("value", 0.0) } ?: 1.0).coerceAtLeast(1.0)
                items.forEach { it2 ->
                    val v = it2.optDouble("value", 0.0)
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(it2.optString("label", ""), fontSize = 12.sp, color = cs.onSurfaceVariant)
                        Box(Modifier.fillMaxWidth().height(18.dp).background(cs.surfaceVariant.copy(alpha = 0.4f))) {
                            Box(Modifier.fillMaxWidth((v / max).toFloat()).height(18.dp).background(cs.primary))
                        }
                    }
                }
            }
            "line" -> {
                val arr = data.optJSONArray("data")
                val nums = if (arr != null) jsonIntArray(arr) else emptyList()
                if (nums.isNotEmpty()) LineChart(nums)
            }
            "progress" -> {
                val value = data.optDouble("value", 0.0)
                val frac = if (value > 1) value / 100.0 else value
                LinearProgressIndicator(progress = frac.toFloat().coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Text("${(frac * 100).toInt()}%", fontSize = 13.sp, color = cs.onSurfaceVariant)
            }
            "list" -> {
                val items = jsonObjArray(data.optJSONArray("data"))
                items.forEachIndexed { idx, it2 ->
                    var done by remember(idx) { mutableStateOf(it2.optBoolean("done", false)) }
                    Row(Modifier.fillMaxWidth().clickable {
                        done = !done
                        onResult(JSONObject().put("key", key).put("index", idx).put("done", done).toString())
                    }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = done, onCheckedChange = {
                            done = it
                            onResult(JSONObject().put("key", key).put("index", idx).put("done", done).toString())
                        })
                        Text(it2.optString("text", ""), fontSize = 15.sp, color = cs.onSurface)
                    }
                }
            }
            "form" -> {
                val fields = jsonObjArray(data.optJSONArray("fields"))
                val values = remember { mutableStateMapOf<String, String>() }
                fields.forEach { f ->
                    val fk = f.optString("key", "")
                    val flabel = f.optString("label", fk)
                    val ftype = f.optString("type", "text")
                    val fdefault = f.optString("default", "")
                    val funit = f.optString("unit", "")
                    val txt = remember(fk) { mutableStateOf(fdefault) }
                    values[fk] = txt.value
                    OutlinedTextField(
                        value = txt.value,
                        onValueChange = { txt.value = it; values[fk] = it },
                        label = { Text(flabel + if (funit.isNotBlank()) " ($funit)" else "") },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                }
                Button(onClick = {
                    val v = JSONObject()
                    values.forEach { (k, vl) -> v.put(k, vl) }
                    onResult(JSONObject().put("key", key).put("values", v).toString())
                    onDismiss()
                }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("提交") }
            }
            "slider" -> {
                val min = data.optDouble("min", 0.0).toFloat()
                val max = data.optDouble("max", 100.0).toFloat()
                val sv = remember(key) { mutableStateOf(data.optDouble("value", (min + max) / 2.0).toFloat()) }
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text("${sv.value.toInt()}", fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = cs.onSurface)
                    Slider(value = sv.value, onValueChange = { sv.value = it }, valueRange = min..max)
                    Button(onClick = {
                        onResult(JSONObject().put("key", key).put("value", sv.value).toString())
                        onDismiss()
                    }, modifier = Modifier.fillMaxWidth()) { Text("确认") }
                }
            }
            "timeline" -> {
                val items = jsonObjArray(data.optJSONArray("data"))
                items.forEach { it2 ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Column(Modifier.width(56.dp)) {
                            Text(it2.optString("time", ""), fontSize = 12.sp, color = cs.primary)
                        }
                        Column {
                            Text(it2.optString("title", ""), fontSize = 15.sp, color = cs.onSurface, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                            val d = it2.optString("desc", "")
                            if (d.isNotBlank()) Text(d, fontSize = 13.sp, color = cs.onSurfaceVariant)
                        }
                    }
                }
            }
            else -> Text("未知原生组件：$kind", fontSize = 13.sp, color = cs.onSurfaceVariant)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Sparkline(values: List<Int>) {
    val cs = MaterialTheme.colorScheme
    Canvas(Modifier.fillMaxWidth().height(48.dp).padding(4.dp)) {
        if (values.size < 2) return@Canvas
        val max = values.maxOrNull()!!.toFloat().coerceAtLeast(1f)
        val min = values.minOrNull()!!.toFloat()
        val span = (max - min).coerceAtLeast(1f)
        val stepX = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * stepX
            val y = size.height - ((v - min) / span) * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = cs.primary, style = Stroke(width = 2.dp.toPx()))
    }
}

@Composable
private fun LineChart(values: List<Int>) {
    val cs = MaterialTheme.colorScheme
    Canvas(Modifier.fillMaxWidth().height(160.dp).padding(8.dp)) {
        if (values.size < 2) return@Canvas
        val max = values.maxOrNull()!!.toFloat().coerceAtLeast(1f)
        val min = values.minOrNull()!!.toFloat()
        val span = (max - min).coerceAtLeast(1f)
        val stepX = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * stepX
            val y = size.height - ((v - min) / span) * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = cs.primary, style = Stroke(width = 2.dp.toPx()))
    }
}

// ────────────────────────── 工具函数 ───────────────────────────

private fun jsonIntArray(a: JSONArray?): List<Int> {
    if (a == null) return emptyList()
    val out = mutableListOf<Int>()
    for (i in 0 until a.length()) out.add(a.optInt(i, 0))
    return out
}

private fun jsonObjArray(a: JSONArray?): List<JSONObject> {
    if (a == null) return emptyList()
    val out = mutableListOf<JSONObject>()
    for (i in 0 until a.length()) out.add(a.optJSONObject(i) ?: JSONObject())
    return out
}

/** 去掉模型偶尔顺手包在 HTML 外的 Markdown 围栏（开头 ```lang / 结尾 ```）。 */
private fun stripFences(raw: String): String {
    var s = raw.trimStart('\uFEFF').trim()
    val lead = Regex("^\\s*```[a-zA-Z0-9_+#-]*\\s*\\n?", RegexOption.DOT_MATCHES_ALL)
    s = lead.replaceFirst(s, "")
    val trail = Regex("\\n?```\\s*$", RegexOption.DOT_MATCHES_ALL)
    s = trail.replaceFirst(s, "")
    return s
}

/** 把完整 HTML 切成若干 chunk，配合画布的流式 document.write。 */
private fun chunkHtml(html: String, size: Int = 4000): List<String> {
    if (html.length <= size) return listOf(html)
    val out = mutableListOf<String>()
    var i = 0
    while (i < html.length) {
        out.add(html.substring(i, (i + size).coerceAtMost(html.length)))
        i += size
    }
    return out
}

private fun openInBrowser(ctx: Context, url: String) {
    try {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    } catch (_: Exception) { /* 无可用浏览器时忽略 */ }
}
