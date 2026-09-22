package com.ai.assistance.quro.genui.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.genui.app.llm.LLMClient
import com.ai.assistance.quro.genui.app.llm.ProviderPresets
import com.ai.assistance.quro.genui.app.store.GenStore
import com.ai.assistance.quro.genui.app.store.ModelProvider
import com.ai.assistance.quro.genui.app.store.Protocol
import com.ai.assistance.quro.genui.app.store.QuroGenUiBridge
import com.ai.assistance.quro.genui.app.ui.theme.GenTheme
import kotlinx.coroutines.launch

/**
 * 模型服务 —— 把"接一个模型"简化到"选预设 + 粘 Key"。
 *
 * 三层结构：
 *  ① 分派   —— 主脑（写界面）与快速（标题/意图）分别用谁，用图形化的连线表达，不只靠文字
 *  ② 供应商 —— 卡片列表，含能力徽标（tools/vision）与温度档位
 *  ③ 编辑器 —— 从预设一键带入端点与推荐模型，可改采样参数与能力标记
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelConfigScreen(
    store: GenStore,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var providers by remember { mutableStateOf(store.loadProviders()) }
    var routing by remember { mutableStateOf(store.loadRouting()) }
    var editing by remember { mutableStateOf<ModelProvider?>(null) }
    var pickingPreset by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }

    BackHandler { onBack() }

    fun save() {
        store.saveProviders(providers)
        store.saveRouting(routing)
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
                Text("模型服务", color = GenTheme.Text, fontSize = 15.sp)
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(64.dp))
            }
        }
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize().background(GenTheme.Screen)) {

            // ———————— ① 分派 ————————
            item {
                SectionLabel("专项模型 · 分派")
                RoutingCard(
                    mainId = routing.mainProviderId,
                    fastId = routing.fastProviderId,
                    providers = providers
                )
                Spacer(Modifier.height(10.dp))
                FeatureRow("主脑 · 写界面", "负责生成整个 HTML 界面。建议选能力最强的模型。",
                    routing.mainProviderId, providers) { id -> routing.mainProviderId = id; save() }
                FeatureRow("快速 · 标题与意图", "轻任务：空态建议、标题压缩、联网意图预检。可用便宜的小模型。",
                    routing.fastProviderId, providers) { id -> routing.fastProviderId = id; save() }
                Spacer(Modifier.height(18.dp))
            }

            // ———————— ② 供应商 ————————
            item { SectionLabel("模型供应商 · ${providers.size}") }
            if (providers.isEmpty()) {
                item {
                    Column(
                        Modifier.padding(horizontal = 14.dp).fillMaxWidth()
                            .background(GenTheme.Panel, RoundedCornerShape(12.dp))
                            .border(0.5.dp, GenTheme.Line, RoundedCornerShape(12.dp))
                            .padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("还没有接入任何模型", color = GenTheme.Text, fontSize = 13.sp)
                        Spacer(Modifier.height(5.dp))
                        Text(
                            "点下面的「添加供应商」从预设里选一个，粘上 API Key 就能用",
                            color = GenTheme.Dim, fontSize = 11.sp, textAlign = TextAlign.Center
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
            items(providers, key = { it.id }) { p ->
                val isMain = p.id == routing.mainProviderId
                val isFast = p.id == routing.fastProviderId
                ProviderRow(
                    p = p, testing = testing == p.id, isMain = isMain, isFast = isFast,
                    onClick = { editing = p },
                    onTest = {
                        scope.launch {
                            testing = p.id
                            val r = runCatching { LLMClient().testDetailed(p) }
                                .getOrElse { LLMClient.TestResult(false, 0, message = it.message ?: "失败") }
                            testing = null
                            snackbar.showSnackbar(
                                if (r.ok) "「${p.name}」连接成功 · ${r.ms}ms" + (if (r.hint.isNotBlank()) " · ${r.hint}" else "")
                                else "「${p.name}」${r.message}" + (if (r.hint.isNotBlank()) " —— ${r.hint}" else "")
                            )
                        }
                    }
                )
                Spacer(Modifier.height(8.dp))
            }
            item {
                Row(Modifier.padding(horizontal = 14.dp)) {
                    TextButton(onClick = { pickingPreset = true }) {
                        Text("+ 添加供应商", color = GenTheme.Amber, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            // ———————— ③ 分派生效说明 ————————
            item {
                val fastReady = routing.fastProviderId.isNotBlank() && routing.fastProviderId != routing.mainProviderId
                Row(
                    Modifier.padding(horizontal = 14.dp).fillMaxWidth()
                        .background(GenTheme.Panel, RoundedCornerShape(12.dp)).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (fastReady) "快速模型已生效" else "快速模型未单独指定",
                            color = if (fastReady) GenTheme.Green else GenTheme.Dim, fontSize = 13.sp
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            if (fastReady)
                                "空态指令建议、界面标题压缩、联网意图预检将走该模型"
                            else
                                "以上轻任务暂由主脑承担（在「快速 · 标题与意图」指定后自动切换）",
                            color = GenTheme.Dim, fontSize = 10.sp, lineHeight = 15.sp
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
            }

            // ———————— ④ 端侧引擎（规划中） ————————
            item {
                SectionLabel("端侧引擎 · 规划中")
                Row(
                    Modifier.padding(horizontal = 14.dp).fillMaxWidth()
                        .background(GenTheme.Panel, RoundedCornerShape(12.dp)).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("MNN / llama.cpp", color = GenTheme.Dim, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                        Text("离线生成 UI · 路线图", color = GenTheme.Dim.copy(alpha = 0.6f), fontSize = 10.sp)
                    }
                    Text("未接入", color = GenTheme.Dim.copy(alpha = 0.5f), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }

    // —— 预设选择器 ——
    if (pickingPreset) {
        PresetPickerSheet(
            onDismiss = { pickingPreset = false },
            onPick = { preset ->
                val np = ModelProvider(
                    id = QuroGenUiBridge.MAIN_ID,
                    name = preset.name,
                    protocol = preset.protocol,
                    baseUrl = preset.baseUrl,
                    apiKey = "",
                    model = preset.defaultModel,
                    supportsTools = preset.supportsTools,
                    supportsVision = preset.supportsVision
                )
                pickingPreset = false
                editing = np   // 直接进入编辑，让用户填 Key
            }
        )
    }

    // —— 编辑弹层 ——
    val target = editing
    if (target != null) {
        ProviderEditorDialog(
            initial = target,
            isNew = providers.none { it.id == target.id },
            onSave = { np ->
                // 集成模式：GenUI 只有一个固定 id="quro_main" 的供应商，与 QuroAI 单一模型配置 1:1 对应。
                // 不论用户从预设新增还是编辑，都落到同一个槽位，避免产生幽灵第二供应商。
                val pinned = np.copy(id = QuroGenUiBridge.MAIN_ID)
                val idx = providers.indexOfFirst { it.id == pinned.id }
                if (idx >= 0) providers[idx] = pinned else providers.add(pinned)
                routing.mainProviderId = pinned.id
                routing.fastProviderId = pinned.id
                save(); editing = null
            },
            // 单一供应商不可删除（删除会清空槽位且无法持久化到 QuroAI 单配置）；如需换模型请直接编辑。
            onDelete = null,
            onDismiss = { editing = null }
        )
    }
}

// ---------- 子组件 ----------

@Composable
private fun SectionLabel(t: String) {
    Text(
        t, color = GenTheme.Dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), letterSpacing = 1.5.sp
    )
}

@Composable
private fun EntryChip(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        label, color = GenTheme.Text, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
        textAlign = TextAlign.Center,
        modifier = modifier
            .background(GenTheme.PanelUp, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
    )
}

/**
 * 分派可视化 —— 用"主脑 / 快速 → 供应商"的连线图替代纯文字，
 * 一眼看清"谁在干活、有没有配重"。
 */
@Composable
private fun RoutingCard(mainId: String, fastId: String, providers: List<ModelProvider>) {
    val main = providers.find { it.id == mainId }
    val fast = providers.find { it.id == fastId }

    Column(
        Modifier.padding(horizontal = 14.dp).fillMaxWidth()
            .background(GenTheme.Panel, RoundedCornerShape(12.dp))
            .border(0.5.dp, GenTheme.Line, RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("主脑", color = GenTheme.Dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Box(
                Modifier.weight(1f).padding(horizontal = 8.dp).height(1.dp)
                    .background(main?.let { GenTheme.Amber } ?: GenTheme.Line)
            )
            Text(
                main?.let { "${it.name}" } ?: "未指定",
                color = if (main != null) GenTheme.Text else GenTheme.Red.copy(alpha = .8f),
                fontSize = 12.sp
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("快速", color = GenTheme.Dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Box(
                Modifier.weight(1f).padding(horizontal = 8.dp).height(1.dp)
                    .background(if (fast != null) GenTheme.AmberDim else GenTheme.Line)
            )
            Text(
                fast?.let { "${it.name}" } ?: "同主脑",
                color = if (fast != null) GenTheme.Text else GenTheme.Dim,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun FeatureRow(
    label: String,
    hint: String,
    selectedId: String,
    providers: List<ModelProvider>,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = providers.find { it.id == selectedId }
    Column(Modifier.padding(horizontal = 14.dp)) {
        Row(
            Modifier.fillMaxWidth().background(GenTheme.Panel, RoundedCornerShape(12.dp))
                .clickable { expanded = !expanded }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, color = GenTheme.Text, fontSize = 13.sp)
                Spacer(Modifier.height(2.dp))
                Text(hint, color = GenTheme.Dim, fontSize = 9.5.sp, lineHeight = 13.sp)
                Spacer(Modifier.height(3.dp))
                Text(
                    selected?.let { "${it.name} · ${it.model}" } ?: "未指定",
                    color = if (selected != null) GenTheme.Amber else GenTheme.Red.copy(alpha = .7f),
                    fontSize = 10.sp, fontFamily = FontFamily.Monospace
                )
            }
            Text(if (expanded) "收起" else "选择", color = GenTheme.Amber, fontSize = 11.sp)
        }
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            if (providers.isEmpty()) {
                Text("还没有供应商，先添加一个", color = GenTheme.Dim, fontSize = 11.sp)
            }
            providers.forEach { p ->
                Row(
                    Modifier.fillMaxWidth().background(GenTheme.PanelUp, RoundedCornerShape(8.dp))
                        .clickable { onSelect(p.id); expanded = false }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (p.id == selectedId) "● " else "○ ", color = GenTheme.Amber, fontSize = 12.sp)
                    Text("${p.name} · ${p.model}", color = GenTheme.Text, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    if (!p.supportsTools)
                        Text("无工具", color = GenTheme.Red, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ProviderRow(
    p: ModelProvider,
    testing: Boolean,
    isMain: Boolean,
    isFast: Boolean,
    onClick: () -> Unit,
    onTest: () -> Unit
) {
    Row(
        Modifier.padding(horizontal = 14.dp).fillMaxWidth()
            .background(GenTheme.Panel, RoundedCornerShape(12.dp))
            .border(
                if (isMain) 0.8.dp else 0.5.dp,
                if (isMain) GenTheme.Amber.copy(alpha = 0.5f) else GenTheme.Line,
                RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(34.dp).background(GenTheme.PanelUp, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                p.protocol.name.take(2), color = GenTheme.Amber,
                fontSize = 10.sp, fontFamily = FontFamily.Monospace
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, color = GenTheme.Text, fontSize = 14.sp, maxLines = 1)
                Spacer(Modifier.width(6.dp))
                if (isMain) RoleBadge("主脑", GenTheme.Amber)
                if (isFast) { Spacer(Modifier.width(4.dp)); RoleBadge("快速", GenTheme.AmberDim) }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "${p.model} · 温度 ${p.temperature}",
                color = GenTheme.Dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1
            )
            Spacer(Modifier.height(4.dp))
            Row {
                CapBadge("tools", p.supportsTools)
                Spacer(Modifier.width(5.dp))
                CapBadge("vision", p.supportsVision)
                Spacer(Modifier.width(5.dp))
                if (!p.enabled) {
                    Text(
                        "已停用", color = GenTheme.Red, fontSize = 8.5.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .background(GenTheme.Red.copy(alpha = .12f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
            }
        }
        TextButton(onClick = onTest, enabled = !testing) {
            Text(if (testing) "…测试" else "测试", color = GenTheme.Amber, fontSize = 11.sp)
        }
    }
}

@Composable
private fun RoleBadge(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text, color = color, fontSize = 8.5.sp, fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    )
}

@Composable
private fun CapBadge(name: String, on: Boolean) {
    val c = if (on) GenTheme.Green else GenTheme.Dim.copy(alpha = 0.5f)
    Text(
        (if (on) "✓ " else "✗ ") + name, color = c, fontSize = 8.5.sp, fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .background(c.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    )
}

/**
 * 预设选择器 —— 按区域分组，点一下就把端点与推荐模型带进编辑器。
 * 这是"降低接入门槛"的关键一步：用户不再需要知道 /v1 该不该写。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetPickerSheet(
    onDismiss: () -> Unit,
    onPick: (ProviderPresets.Preset) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val grouped = ProviderPresets.all.groupBy { it.region }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = GenTheme.Panel,
        scrimColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text("选择供应商", color = GenTheme.Text, fontSize = 15.sp, fontFamily = FontFamily.Serif)
            Spacer(Modifier.height(3.dp))
            Text(
                "选一个预设，端点和推荐模型会自动填好，你只需粘 API Key",
                color = GenTheme.Dim, fontSize = 10.sp
            )
            Spacer(Modifier.height(12.dp))
            LazyColumn(Modifier.heightIn(max = 520.dp), contentPadding = PaddingValues(bottom = 28.dp)) {
                listOf("cn", "global", "local").forEach { region ->
                    val list = grouped[region] ?: return@forEach
                    item {
                        Text(
                            ProviderPresets.regionLabel(region),
                            color = GenTheme.Dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.5.sp, modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(list, key = { it.key }) { preset ->
                        Column(
                            Modifier.fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .background(GenTheme.PanelUp, RoundedCornerShape(10.dp))
                                .clickable { onPick(preset) }
                                .padding(horizontal = 13.dp, vertical = 11.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(preset.name, color = GenTheme.Text, fontSize = 13.5.sp)
                                Spacer(Modifier.width(7.dp))
                                Text(
                                    preset.protocol.label,
                                    color = GenTheme.Amber, fontSize = 8.5.sp, fontFamily = FontFamily.Monospace,
                                    modifier = Modifier
                                        .background(GenTheme.Amber.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                            Spacer(Modifier.height(3.dp))
                            Text(
                                preset.defaultModel.ifBlank { "自定义模型" },
                                color = GenTheme.Dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(preset.docHint, color = GenTheme.Dim.copy(alpha = 0.75f), fontSize = 9.5.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderEditorDialog(
    initial: ModelProvider,
    isNew: Boolean,
    onSave: (ModelProvider) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var p by remember { mutableStateOf(initial) }
    var fetching by remember { mutableStateOf(false) }
    var fetchedModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var fetchError by remember { mutableStateOf<String?>(null) }
    var showAdvanced by remember { mutableStateOf(false) }

    BackHandler { onDismiss() }

    fun pullModels() {
        if (fetching) return
        scope.launch {
            fetching = true; fetchError = null; fetchedModels = emptyList()
            fetchedModels = try {
                LLMClient().fetchModels(p)
            } catch (e: Exception) {
                fetchError = e.message ?: "拉取失败"
                emptyList()
            }
            fetching = false
        }
    }

    // 端点缺 /v1 之类是最高频错误——这里做即时提示，不等用户点测试
    val urlWarning: String? = when {
        p.baseUrl.isBlank() -> null
        !p.baseUrl.startsWith("http") -> "缺少 http(s):// 前缀"
        p.baseUrl.endsWith("/chat/completions") || p.baseUrl.endsWith("/messages") ->
            "只填到 /v1 为止即可，不要带具体路径"
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GenTheme.Panel,
        title = { Text(if (isNew) "添加供应商" else "编辑供应商", color = GenTheme.Text, fontSize = 15.sp) },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())
            ) {
                FieldRow("名称", p.name) { p = p.copy(name = it) }
                Spacer(Modifier.height(8.dp))

                Text("协议", color = GenTheme.Dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Row(Modifier.padding(vertical = 6.dp)) {
                    Protocol.entries.forEach { proto ->
                        val on = p.protocol == proto
                        Text(
                            proto.label, color = if (on) GenTheme.Screen else GenTheme.Text,
                            fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .background(if (on) GenTheme.Amber else GenTheme.PanelUp, RoundedCornerShape(6.dp))
                                .clickable { p = p.copy(protocol = proto) }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                }

                FieldRow("端点 Base URL", p.baseUrl, mono = true) { p = p.copy(baseUrl = it) }
                if (urlWarning != null) {
                    Text("⚠ $urlWarning", color = GenTheme.Amber, fontSize = 9.5.sp, modifier = Modifier.padding(top = 3.dp))
                }
                Spacer(Modifier.height(8.dp))

                FieldRow("API Key", p.apiKey, mono = true) { p = p.copy(apiKey = it) }
                Spacer(Modifier.height(8.dp))

                FieldRow("模型 ID", p.model, mono = true) { p = p.copy(model = it) }
                Spacer(Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { pullModels() },
                        enabled = p.baseUrl.isNotBlank() && !fetching,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                    ) {
                        Text(
                            if (fetching) "拉取中…" else "⟳ 从端点拉取模型列表",
                            color = GenTheme.Amber, fontSize = 11.sp, fontFamily = FontFamily.Monospace
                        )
                    }
                    if (fetchedModels.isNotEmpty())
                        Text("找到 ${fetchedModels.size} 个", color = GenTheme.Green, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
                if (fetchError != null)
                    Text(fetchError!!, color = GenTheme.Red, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 2)
                if (fetchedModels.isNotEmpty()) {
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 150.dp).verticalScroll(rememberScrollState())
                            .background(GenTheme.PanelUp, RoundedCornerShape(8.dp)).padding(6.dp)
                    ) {
                        fetchedModels.forEach { m ->
                            val on = m == p.model
                            Text(
                                (if (on) "● " else "○ ") + m,
                                color = if (on) GenTheme.Amber else GenTheme.Text,
                                fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { p = p.copy(model = m) }
                                    .padding(horizontal = 8.dp, vertical = 7.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = GenTheme.Line, thickness = 0.5.dp)
                Spacer(Modifier.height(10.dp))

                // —— 高级：采样参数 + 能力标记 ——
                Text(
                    if (showAdvanced) "▾ 高级参数" else "▸ 高级参数（采样 / 能力）",
                    color = GenTheme.Amber, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.clickable { showAdvanced = !showAdvanced }
                )
                if (showAdvanced) {
                    Spacer(Modifier.height(10.dp))

                    // 温度
                    Text(
                        "温度 · ${p.temperature}  ${ProviderPresets.Sampling.tempLabel(p.temperature)}",
                        color = GenTheme.Dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                    )
                    Slider(
                        value = p.temperature,
                        onValueChange = { p = p.copy(temperature = (Math.round(it * 10) / 10f)) },
                        valueRange = ProviderPresets.Sampling.TEMP_MIN..ProviderPresets.Sampling.TEMP_MAX,
                        colors = SliderDefaults.colors(
                            thumbColor = GenTheme.Amber, activeTrackColor = GenTheme.Amber,
                            inactiveTrackColor = GenTheme.PanelUp
                        )
                    )
                    Row {
                        listOf(0.2f, 0.7f, 1.2f).forEach { t ->
                            Text(
                                "$t", color = GenTheme.Dim, fontSize = 9.5.sp, fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .background(GenTheme.PanelUp, RoundedCornerShape(5.dp))
                                    .clickable { p = p.copy(temperature = t) }
                                    .padding(horizontal = 7.dp, vertical = 3.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                    Spacer(Modifier.height(12.dp))

                    // Top-P
                    Text(
                        "Top-P · ${p.topP}",
                        color = GenTheme.Dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                    )
                    Slider(
                        value = p.topP,
                        onValueChange = { p = p.copy(topP = (Math.round(it * 100) / 100f)) },
                        valueRange = ProviderPresets.Sampling.TOP_P_MIN..ProviderPresets.Sampling.TOP_P_MAX,
                        colors = SliderDefaults.colors(
                            thumbColor = GenTheme.Amber, activeTrackColor = GenTheme.Amber,
                            inactiveTrackColor = GenTheme.PanelUp
                        )
                    )
                    Row {
                        listOf(0.3f, 0.7f, 1.0f).forEach { v ->
                            Text(
                                "$v", color = GenTheme.Dim, fontSize = 9.5.sp, fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .background(GenTheme.PanelUp, RoundedCornerShape(5.dp))
                                    .clickable { p = p.copy(topP = v) }
                                    .padding(horizontal = 7.dp, vertical = 3.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                    Spacer(Modifier.height(12.dp))

                    // 最大输出
                    Text(
                        "单次最大输出 · ${ProviderPresets.Sampling.maxTokensLabel(p.maxTokens)}",
                        color = GenTheme.Dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.height(5.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        ProviderPresets.Sampling.MAX_TOKENS_CHOICES.forEach { v ->
                            val on = p.maxTokens == v
                            Text(
                                ProviderPresets.Sampling.maxTokensLabel(v),
                                color = if (on) GenTheme.Screen else GenTheme.Text,
                                fontSize = 9.5.sp, fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .background(if (on) GenTheme.Amber else GenTheme.PanelUp, RoundedCornerShape(6.dp))
                                    .clickable { p = p.copy(maxTokens = v) }
                                    .padding(horizontal = 8.dp, vertical = 5.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                    Spacer(Modifier.height(12.dp))

                    // 工具调用轮次（0 = 不限制）
                    Text(
                        "工具调用轮次 · ${ProviderPresets.Sampling.maxToolRoundsLabel(p.maxToolRounds)}",
                        color = GenTheme.Dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.height(5.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        ProviderPresets.Sampling.MAX_TOOL_ROUNDS_CHOICES.forEach { v ->
                            val on = p.maxToolRounds == v
                            Text(
                                if (v <= 0) "不限制" else "$v",
                                color = if (on) GenTheme.Screen else GenTheme.Text,
                                fontSize = 9.5.sp, fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .background(if (on) GenTheme.Amber else GenTheme.PanelUp, RoundedCornerShape(6.dp))
                                    .clickable { p = p.copy(maxToolRounds = v) }
                                    .padding(horizontal = 8.dp, vertical = 5.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                    Text(
                        "0 = 不限制：模型想查多少轮就查多少轮，自行用 NO_TOOLS / 直接成稿收尾。" +
                            "选正数仅作软提醒（超阈值轻推一次、不强制中断）；真正兜底的是死循环检测与总时长闸。",
                        color = GenTheme.Dim.copy(alpha = .7f), fontSize = 9.sp, modifier = Modifier.padding(bottom = 8.dp, top = 4.dp)
                    )
                    Spacer(Modifier.height(12.dp))

                    // 上下文窗口（续写尾部长度）
                    Text(
                        "上下文窗口 · ${p.contextChars} 字符",
                        color = GenTheme.Dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.height(5.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        ProviderPresets.Sampling.CONTEXT_CHARS_CHOICES.forEach { v ->
                            val on = p.contextChars == v
                            Text(
                                "$v",
                                color = if (on) GenTheme.Screen else GenTheme.Text,
                                fontSize = 9.5.sp, fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .background(if (on) GenTheme.Amber else GenTheme.PanelUp, RoundedCornerShape(6.dp))
                                    .clickable { p = p.copy(contextChars = v) }
                                    .padding(horizontal = 8.dp, vertical = 5.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                    Text(
                        "续写时喂给模型的尾部片段长度；小模型建议 2000，长页面建议 4000+",
                        color = GenTheme.Dim.copy(alpha = .7f), fontSize = 9.sp, modifier = Modifier.padding(bottom = 8.dp, top = 4.dp)
                    )
                    Spacer(Modifier.height(12.dp))

                    // 能力标记
                    ToggleLine("支持工具调用（function calling）", p.supportsTools) { p = p.copy(supportsTools = it) }
                    Text(
                        "关闭后，Agent 将无法用工具获取实时信息，只能凭已有知识作答",
                        color = GenTheme.Dim.copy(alpha = .7f), fontSize = 9.sp, modifier = Modifier.padding(bottom = 8.dp)
                    )
                    ToggleLine("支持图片输入（vision）", p.supportsVision) { p = p.copy(supportsVision = it) }
                    Spacer(Modifier.height(12.dp))
                }

                ToggleLine("启用此供应商", p.enabled) { p = p.copy(enabled = it) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(p) }) { Text("保存", color = GenTheme.Amber) }
        },
        dismissButton = {
            Row {
                if (onDelete != null)
                    TextButton(onClick = onDelete) { Text("删除", color = GenTheme.Red, fontSize = 12.sp) }
                TextButton(onClick = onDismiss) { Text("取消", color = GenTheme.Dim) }
            }
        }
    )
}

@Composable
private fun ToggleLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = GenTheme.Text, fontSize = 12.sp)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = GenTheme.Screen,
                checkedTrackColor = GenTheme.Amber,
                uncheckedThumbColor = GenTheme.Dim,
                uncheckedTrackColor = GenTheme.PanelUp
            )
        )
    }
}

@Composable
private fun FieldRow(label: String, value: String, mono: Boolean = false, onChange: (String) -> Unit) {
    Column {
        Text(label, color = GenTheme.Dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        BasicTextField(
            value = value, onValueChange = onChange,
            textStyle = TextStyle(
                color = GenTheme.Text, fontSize = 12.sp,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default
            ),
            cursorBrush = SolidColor(GenTheme.Amber),
            modifier = Modifier.fillMaxWidth().background(GenTheme.PanelUp, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 9.dp)
        )
    }
}
