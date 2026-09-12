package com.ai.assistance.quro.genui.app.ui.shell

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.activity.compose.BackHandler
import androidx.webkit.WebViewAssetLoader
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.genui.app.agent.AgentLoop
import com.ai.assistance.quro.genui.app.agent.ToolGate
import com.ai.assistance.quro.ui.genui.GenUiCanvas
import com.ai.assistance.quro.genui.app.render.CanvasNativeView
import com.ai.assistance.quro.genui.app.render.ComposeDescRenderer
import com.ai.assistance.quro.genui.app.store.GeneratedPage
import com.ai.assistance.quro.genui.app.store.GenStore
import com.ai.assistance.quro.genui.app.store.ModelProvider
import com.ai.assistance.quro.genui.app.ui.theme.GenTheme
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

    /** ThinkingTimeline.Kind 的短名，方便事件映射阅读 */
private typealias Kind = com.ai.assistance.quro.genui.app.agent.ThinkingTimeline.Kind

/**
 * 原生渲染态 —— AI 声明了 xml / compose 通道时，画布上叠一层真实原生渲染。
 * Compose 描述是 JSON（AI 写的数据结构），XML 是布局源码；两者都不需要编译。
 */
sealed interface NativeRender {
    data class Xml(val xml: String) : NativeRender
    data class Compose(val json: String) : NativeRender

    /**
     * 代码呈现层 —— Kotlin / Java / C++ / Python 这类端上跑不了的语言，
     * 作为**界面题材**由端上统一渲染成代码视图（行号 + 高亮 + 复制）。
     *
     * 只在 AI 没有自己用 HTML 画代码视图、直接甩了裸代码块时启用。
     * 这是兜底，不是规范：AI 想自己画就自己画，端上不抢。
     */
    data class Code(val blocks: List<com.ai.assistance.quro.genui.app.render.RenderChannel.CodeBlock>) : NativeRender

    /**
     * GenCanvas 原生画布层 —— AI 写一份绘制指令 JSON，端上解释为真实安卓原生画面。
     * 见 [com.ai.assistance.quro.genui.app.render.CanvasNativeView]。
     */
    data class Canvas(val json: String) : NativeRender
}

/**
 * 主屏 = 壳（状态行 + A2UI 画布 + 指令条）+ 界面栈入口。
 * 这里是全 App 仅有的"内置 UI"，WebView 内的一切皆由 AI 写出。
 */
/** 主屏可达的子系统导航目标 */
enum class NavTarget { Soul, Memory, Perms, ModelConfig, Settings }

/**
 * 生成阶段 —— 状态行的语义骨架。
 * 状态行不该只是一串会变的字：得让人一眼看出"现在处于哪个阶段"。
 * 每个阶段有自己的标签、颜色、以及"要不要显示秒表"。
 */
private enum class Phase(val label: String, val color: androidx.compose.ui.graphics.Color, val timed: Boolean) {
    Idle("待命", GenTheme.Dim, false),
    Thinking("思考", GenTheme.AmberDim, true),
    Deciding("决策", GenTheme.Amber, true),
    Tooling("调用工具", Color(0xFF5FA8A0), true),
    Awaiting("等待授权", GenTheme.Red, false),
    Rendering("绘制界面", Color(0xFF8A7BC8), true),
    Done("完成", GenTheme.Green, false),
    Failed("失败", GenTheme.Red, false),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenScaffold(
    store: GenStore,
    configVersion: Int = 0,
    onNavigate: (NavTarget) -> Unit,
    dark: Boolean = false,
    onPushToChat: (html: String, title: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    // WebView / 渲染器只创建一次：用 remember 持有，绝不放进任何会变化的状态里，
    // 否则 AndroidView 的 factory 会重跑、把正在流式写入的文档整个销毁重建。
    val webRef = remember { mutableStateOf<WebView?>(null) }
    val renderer = remember { mutableStateOf<GenUiCanvas?>(null) }
    val agentRef = remember { mutableStateOf<AgentLoop?>(null) }

    var cmd by remember { mutableStateOf("") }
    val soul = remember {
        val ss = com.ai.assistance.quro.genui.app.agent.SoulStore(ctx)
        ss.load() ?: ss.fallback
    }
    var building by remember { mutableStateOf(false) }
    var chunkBytes by remember { mutableStateOf(0) }
    var bridgeCalls by remember { mutableStateOf(0) }
    // 界面栈计数单独一个状态：只驱动文字变化，不参与 AndroidView 的 key
    var stackCount by remember { mutableStateOf(store.loadPages().size) }
    var showStack by remember { mutableStateOf(false) }
    // 思考时间线：整轮生成的轨迹，实时追加、随时可翻看
    val timeline = remember { com.ai.assistance.quro.genui.app.agent.ThinkingTimeline() }
    var showThinking by remember { mutableStateOf(false) }
    // 状态行的结构化信息（比一行裸字符串信息密度高得多）
    var phase by remember { mutableStateOf(Phase.Idle) }
    var phaseDetail by remember { mutableStateOf("") }
    var toolCalls by remember { mutableStateOf(0) }
    var ticks by remember { mutableStateOf(0) }          // 秒表驱动（仅在运行时变化）
    var lastElapsed by remember { mutableStateOf(0L) }   // 上一轮耗时，用于完成态展示
    // 停止后保留的「已写出的部分 HTML」，用于续写
    var partialHtml by remember { mutableStateOf<String?>(null) }
    // 快速模型生成的指令建议（专项模型分派的真实用途：空态给用户三个起点）
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    // 顶部导航聚合菜单
    var showQuickNav by remember { mutableStateOf(false) }
    // 抖音式历史浏览（全屏上下滑动翻看已生成的界面）
    var browsing by remember { mutableStateOf(false) }
    // 本轮生成共享的"当前运行条目"槽位（跨回调记忆位置）
    val runSlot = remember { RunSlot() }
    var startedAt by remember { mutableStateOf(0L) }
    // 原生渲染层：AI 声明 xml / compose 通道时，这里持有待原生渲染的内容
    var nativeRender by remember { mutableStateOf<NativeRender?>(null) }
    // 上一份产出的渲染通道，用于状态行标注"这一屏是用什么画的"
    var lastChannel by remember { mutableStateOf("html") }
    val snackbar = remember { SnackbarHostState() }
    // 供应商配置缓存：store.loadProviders() 是磁盘读 + JSON 解析，
    // 绝不能放在组合函数里每次重组都跑（会直接拖垮滚动/动画帧率）。
    // 缓存键 = 外部传入的 configVersion：设置页里改完模型、关掉设置页时由
    // MainActivity 把它 +1，这里就会重新读盘。
    // 没有这个键就会出现真实 bug：用户新增/改好模型后回到主屏，cachedProvider
    // 还是旧值 → 点发送要么报"未配置"，要么用了旧地址。
    fun resolveProvider(): ModelProvider? {
        val routing = store.loadRouting()
        val all = store.loadProviders().filter { it.enabled }
        return all.find { it.id == routing.mainProviderId } ?: all.firstOrNull()
    }
    val cachedProvider: ModelProvider? = remember(configVersion) { resolveProvider() }

    // 秒表：仅在生成中走字，避免空转耗电
    LaunchedEffect(building) {
        while (building) {
            ticks++
            kotlinx.coroutines.delay(1000)
        }
    }

    /** 当前应显示的耗时（秒） */
    fun elapsedSec(): Long =
        if (building && startedAt > 0) (System.currentTimeMillis() - startedAt) / 1000
        else lastElapsed / 1000

    // 启动时问一次快速模型要三条建议。失败/未配置时静默为空（不显示这一栏）。
    LaunchedEffect(Unit) {
        val fast = com.ai.assistance.quro.genui.app.llm.FastClient(store)
        val memBrief = runCatching {
            com.ai.assistance.quro.genui.app.agent.AgentMemory(ctx).indexForPrompt().lines().firstOrNull().orEmpty()
        }.getOrDefault("")
        suggestions = fast.suggestPrompts(soul.name, memBrief)
    }

    // —— 原生组件：AI 页面经 MoBridge.ui.widget 唤起，Compose BottomSheet 渲染，结果回写页面 ——
    var nativeWidget by remember { mutableStateOf<Pair<String, String>?>(null) }
    nativeWidget?.let { (kind, payload) ->
        NativeWidgetSheet(
            kind = kind, payload = payload,
            onDismiss = { nativeWidget = null },
            onResult = { result ->
                val js = "(function(){try{window.dispatchEvent(new MessageEvent('mo:widget',{data:" +
                    JSONObject.quote(result.toString()) + "}))}catch(e){}})()"
                webRef.value?.evaluateJavascript(js, null)
            }
        )
    }

    // —— 实时授权卡（L3/L4 工具调用时弹出，Agent 线程挂起等待裁决） ——
    val permRequest = remember { mutableStateOf<com.ai.assistance.quro.genui.app.agent.PermRequest?>(null) }
    permRequest.value?.let { pr ->
        AlertDialog(
            onDismissRequest = { },
            containerColor = GenTheme.Panel,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("L${pr.level}", color = GenTheme.Red, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(8.dp))
                    Text("Agent 请求调用工具", color = GenTheme.Text, fontSize = 15.sp)
                }
            },
            text = {
                Column {
                    Text("工具：${pr.tool}", color = GenTheme.Amber, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(6.dp))
                    Text("参数：${pr.brief}", color = GenTheme.Dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "这是 ${pr.level} 级能力（${ToolGate.level(pr.tool)}层），由 Agent 主动发起。你可以拒绝，模型会自行调整方案。",
                        color = GenTheme.Dim, fontSize = 11.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { pr.complete(true); permRequest.value = null }) {
                    Text("本次允许", color = GenTheme.Green)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        // 对接 ZorvAI 后权限由 QuroToolEngine 内部把关；这里把"永久允许"偏好落到
                        // GenUI 权限存储（与「权限设置」屏共用），保持设置屏一致。
                        agentRef.value?.tools?.grantAlways(pr.tool)
                        pr.complete(true); permRequest.value = null
                    }) { Text("永久允许", color = GenTheme.Amber, fontSize = 12.sp) }
                    TextButton(onClick = { pr.complete(false); permRequest.value = null }) {
                        Text("拒绝", color = GenTheme.Red)
                    }
                }
            }
        )
    }

    // —— 画布历史：系统返回 = 退回上一张纸 ——
    val canvasStack = remember { mutableStateListOf<GeneratedPage>() }

    /**
     * 根据一份产出的 HTML 恢复原生渲染层。
     * 必须与 replay 一起调用——否则从界面栈翻回一张"用原生渲染画过的界面"时，
     * 只能看到 HTML 部分，原生块凭空消失（真实 bug）。
     */
    fun restoreNative(html: String) {
        val plan = com.ai.assistance.quro.genui.app.render.RenderChannel.analyze(html)
        lastChannel = when (plan.kind) {
            com.ai.assistance.quro.genui.app.render.RenderChannel.Kind.XML -> "xml"
            com.ai.assistance.quro.genui.app.render.RenderChannel.Kind.COMPOSE -> "compose"
            com.ai.assistance.quro.genui.app.render.RenderChannel.Kind.CANVAS -> "canvas"
            else -> "html"
        }
        nativeRender = when (plan.kind) {
            com.ai.assistance.quro.genui.app.render.RenderChannel.Kind.XML ->
                plan.xml?.takeIf { it.isNotBlank() }?.let { NativeRender.Xml(it) }
            com.ai.assistance.quro.genui.app.render.RenderChannel.Kind.COMPOSE ->
                plan.composeJson?.takeIf { it.isNotBlank() }?.let { NativeRender.Compose(it) }
            com.ai.assistance.quro.genui.app.render.RenderChannel.Kind.CANVAS ->
                plan.canvasJson?.takeIf { it.isNotBlank() }?.let { NativeRender.Canvas(it) }
            // HTML 宿主里 AI 只甩了裸代码块（没自己排版）→ 端上接管呈现
            else -> plan.codeBlocks.takeIf { it.isNotEmpty() }?.let { NativeRender.Code(it) }
        }
    }

    BackHandler(enabled = canvasStack.size > 1) {
        canvasStack.removeAt(canvasStack.lastIndex)
        val prev = canvasStack.last()
        renderer.value?.replay(prev.html)
        restoreNative(prev.html)
        phase = Phase.Idle; phaseDetail = "← 返回「${prev.title}」 · ${prev.model}"
    }

    /** 界面栈前进：统一入口，保证同一张纸不会重复堆积 */
    fun pushPage(p: GeneratedPage) {
        canvasStack.removeAll { it.id == p.id }
        canvasStack.add(p)
        // 栈上限，防止长会话内存膨胀（单页 HTML 可能很大）
        while (canvasStack.size > 30) canvasStack.removeAt(0)
    }

    /**
     * 生成。
     * @param seed 续写种子：非空时把它作为已有内容写回画布，模型续写其后。
     */
    suspend fun generate(prompt: String, seed: String? = null) {
        val provider = cachedProvider ?: run {
            snackbar.showSnackbar("尚未配置模型服务：先到「模型服务」里添加一个供应商")
            return
        }
        building = true; chunkBytes = 0; bridgeCalls = 0; toolCalls = 0; ticks = 0
        startedAt = System.currentTimeMillis()
        timeline.clear()
        // 新一轮生成：清掉上一屏的原生渲染层，避免它与流式内容叠加打架
        nativeRender = null
        phase = Phase.Thinking
        phaseDetail = if (seed == null) "${provider.name} · ${provider.model}"
        else "接续 ${seed.length / 1024}KB 已有内容"

        val r = renderer.value
        if (r == null) { building = false; return }
        r.begin(seed)
        if (seed != null) chunkBytes = seed.length

        // Agent 回调发生在 IO 线程：所有 UI 状态更新与渲染注入都 post 回主线程
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val agent = AgentLoop(
            context = ctx,
            store = store,
            // onStatus 承载的是粗粒度人类文案，把它降级为细节行；阶段语义由 onEvent 负责
            onStatus = { s -> main.post { if (!building || phase != Phase.Rendering) phaseDetail = s } },
            onHtmlDelta = { delta ->
                main.post {
                    chunkBytes += delta.length
                    // 渲染阶段的细节行显示实时体量 + 桥调用次数（真实数据，不是装饰）
                    if (phase == Phase.Rendering)
                        phaseDetail = "${(chunkBytes / 1024.0).format1()}KB · bridge ×$bridgeCalls"
                    r.writeChunk(delta)
                }
            },
            // —— 思考/工具事件 → 时间线 + 状态行 ——
            onEvent = { ev ->
                main.post {
                    applyEvent(timeline, ev, runSlot) { p, d, tc, el ->
                        phase = p; phaseDetail = d
                        if (tc > 0) toolCalls = tc
                        if (el > 0) lastElapsed = el
                    }
                }
            },
            // ZorvAI 式实时授权：L3/L4 工具每次调用弹授权卡，等待用户裁决
            onAskPermission = { tool, briefArg, level ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
                        permRequest.value = com.ai.assistance.quro.genui.app.agent.PermRequest(tool, briefArg, level, cont)
                    }
                }
            }
        )
        agentRef.value = agent

        agent.run(
            provider = provider,
            userPrompt = prompt,
            seedHtml = seed,
            onDirectHtml = { },
            onDone = { full, title ->
                main.post {
                    r.end()
                    val page = GeneratedPage(GenStore.newId(), title, full, provider.model, System.currentTimeMillis())
                    store.appendPage(page)
                    pushPage(page)
                    stackCount = store.loadPages().size
                    partialHtml = null
                    // —— 渲染通道分派 ——
                    // AI 声明了 xml / compose 通道时，把画布上方交给真实原生渲染。
                    // 不影响 HTML 部分：网页继续承载整体排版，原生块叠在其上。
                    restoreNative(full)
                    // 把整屏结果回写 ZorvAI 对话框：作为小程序 WebView 气泡出现在当前会话，
                    // 实现「返回 ZorvAI 对话框」（用户可在普通对话里看到/点开这次 GenUI 产物）。
                    onPushToChat(full, title)
                    phase = Phase.Done
                    phaseDetail = "「$title」· ${(full.length / 1024.0).format1()}KB" +
                        if (lastChannel != "html") " · 原生 $lastChannel" else ""
                    building = false
                }
            },
            onError = { msg ->
                main.post {
                    r.stop()
                    // 失败但已有部分内容：保留下来，让用户能续写而不是从零重来
                    r.readHtml { html ->
                        partialHtml = html.takeIf { it.length > 400 }
                    }
                    phase = Phase.Failed
                    phaseDetail = msg.take(80)
                    building = false
                    scope.launch { snackbar.showSnackbar(msg) }
                }
            }
        )
    }

    Scaffold(
        containerColor = GenTheme.Screen,
        snackbarHost = { SnackbarHost(snackbar) },
        // targetSdk 36 强制 edge-to-edge：inset 由我们自己接管
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column(
                Modifier.fillMaxWidth().statusBarsPadding()
                    .background(GenTheme.Screen)
                    .padding(horizontal = 14.dp, vertical = 9.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 品牌章：极简字标，替代裸图标
                    Text(
                        soul.name.take(1),
                        color = GenTheme.Screen, fontSize = 11.sp, fontFamily = FontFamily.Serif,
                        modifier = Modifier
                            .background(GenTheme.Amber, RoundedCornerShape(6.dp))
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                    Spacer(Modifier.width(9.dp))
                    // 阶段徽标：状态行的"主语"
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(phase.color.copy(alpha = 0.12f), RoundedCornerShape(5.dp))
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    ) {
                        Box(
                            Modifier.size(5.dp)
                                .background(phase.color, RoundedCornerShape(50))
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            phase.label,
                            color = phase.color, fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace, letterSpacing = 0.5.sp
                        )
                    }
                    if (phase.timed && elapsedSec() > 0) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "${elapsedSec()}s",
                            color = GenTheme.Dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                        )
                    }
                    // 技术通道徽标：让用户一眼知道"这屏是用什么画的"。
                    // 只在非默认通道时出现——默认 html 不必刷存在感。
                    if (lastChannel != "html" && !building) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when (lastChannel) {
                                "xml" -> "XML 原生"
                                "compose" -> "Compose"
                                else -> lastChannel
                            },
                            color = Color(0xFF8A7BC8), fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .background(Color(0x1A8A7BC8), RoundedCornerShape(5.dp))
                                .padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    // 思考过程入口：有轨迹时才可点
                    if (timeline.entries.isNotEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clickable { showThinking = true }
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                            Text(
                                "思考",
                                color = GenTheme.Amber, fontSize = 11.sp, fontFamily = FontFamily.Serif
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "${timeline.entries.size}",
                                color = GenTheme.Dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    // 聚合导航入口：≡ 按钮弹出小卡片，里面集合「栈 / 魂 / 记 / 权 / 模」
                    Box {
                        Text(
                            "≡",
                            color = GenTheme.Amber, fontSize = 18.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .clickable { showQuickNav = true }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                        DropdownMenu(
                            expanded = showQuickNav,
                            onDismissRequest = { showQuickNav = false },
                            shape = RoundedCornerShape(12.dp),
                            containerColor = GenTheme.Panel,
                            tonalElevation = 0.dp,
                            shadowElevation = 0.dp,
                            border = BorderStroke(0.5.dp, GenTheme.Line),
                            modifier = Modifier.widthIn(min = 164.dp)
                        ) {
                            val navItems = listOf(
                                Triple("栈", stackCount.toString()) {
                                    browsing = true
                                    showQuickNav = false
                                },
                                Triple("设置", "") {
                                    onNavigate(NavTarget.Settings)
                                    showQuickNav = false
                                }
                            )
                            navItems.forEachIndexed { i, (label, badge, action) ->
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                label,
                                                color = GenTheme.Amber, fontSize = 15.sp,
                                                fontFamily = FontFamily.Serif
                                            )
                                            if (badge.isNotBlank()) {
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    badge,
                                                    color = GenTheme.Dim, fontSize = 11.sp,
                                                    fontFamily = FontFamily.Monospace
                                                )
                                            }
                                        }
                                    },
                                    onClick = { showQuickNav = false; action() },
                                    leadingIcon = {
                                        Text(
                                            when (label) {
                                                "栈" -> "◱"
                                                "魂" -> "◎"
                                                "记" -> "✦"
                                                "权" -> "✓"
                                                "模" -> "⚙"
                                                else -> "•"
                                            },
                                            color = GenTheme.AmberDim, fontSize = 13.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    },
                                    colors = MenuDefaults.itemColors(
                                        textColor = GenTheme.Text,
                                        leadingIconColor = GenTheme.AmberDim
                                    )
                                )
                                if (i < navItems.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 12.dp),
                                        thickness = 0.5.dp,
                                        color = GenTheme.Line
                                    )
                                }
                            }
                        }
                    }
                }
                // 第二行：细节（只有存在时才占位，避免空行浪费高度）
                if (phaseDetail.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        phaseDetail,
                        color = GenTheme.Dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 2.dp)
                    )
                }
                // 生成中：一条极细的进度脉冲线（不确定进度，仅表达"还在跑"）
                if (building) {
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier.fillMaxWidth().height(1.dp)
                            .background(GenTheme.Line)
                    )
                }
            }
        },
        bottomBar = {
            Column(Modifier.background(GenTheme.Screen).navigationBarsPadding()) {
                // 空态：快速模型给的三个起点（点击直接生成）。已有内容或未配置快速模型时不显示。
                if (!building && cmd.isEmpty() && partialHtml == null && suggestions.isNotEmpty() && canvasStack.isEmpty()) {
                    androidx.compose.foundation.lazy.LazyRow(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(suggestions.size) { i ->
                            val s = suggestions[i]
                            Text(
                                s,
                                color = GenTheme.Dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                modifier = Modifier
                                    .background(GenTheme.Panel, RoundedCornerShape(8.dp))
                                    .clickable { cmd = s }
                                    .padding(horizontal = 10.dp, vertical = 7.dp)
                            )
                        }
                    }
                }
                // 中断留下的半成品：给一条续写入口，而不是让用户重头再来
                partialHtml?.let { seed ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp)
                            .background(GenTheme.Panel, RoundedCornerShape(10.dp))
                            .clickable {
                                val s = seed
                                partialHtml = null
                                scope.launch { generate("继续把界面写完整，直接从上次中断处接着写，不要重复已有内容。", s) }
                            }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("↻", color = GenTheme.Amber, fontSize = 13.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "上次中断留下 ${seed.length / 1024}KB —— 点此续写",
                            color = GenTheme.Amber, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f)
                        )
                        Text("×", color = GenTheme.Dim, fontSize = 13.sp,
                            modifier = Modifier.clickable { partialHtml = null }.padding(horizontal = 4.dp))
                    }
                }
                Row(
                    Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                        .background(GenTheme.Panel, RoundedCornerShape(14.dp))
                        .then(
                            if (building) Modifier
                            else Modifier.border(0.5.dp, GenTheme.Line, RoundedCornerShape(14.dp))
                        )
                        .padding(start = 16.dp, end = 6.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Box(Modifier.weight(1f).padding(vertical = 9.dp)) {
                        if (cmd.isEmpty()) {
                            Text(
                                if (building) "正在写…（可点右侧停止）" else "给 ${soul.name} 一句话，它来写整个界面…",
                                color = GenTheme.Dim, fontSize = 14.sp
                            )
                        }
                        BasicTextField(
                            value = cmd, onValueChange = { if (it.length <= 4000) cmd = it },
                            enabled = !building,
                            textStyle = TextStyle(
                                color = if (building) GenTheme.Dim else GenTheme.Text,
                                fontSize = 14.sp, lineHeight = 20.sp
                            ),
                            cursorBrush = SolidColor(GenTheme.Amber),
                            maxLines = 6,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 22.dp, max = 132.dp)
                        )
                    }
                    if (cmd.isNotBlank() && !building) {
                        Text(
                            "${cmd.length}",
                            color = GenTheme.Dim.copy(alpha = 0.6f), fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(bottom = 12.dp, end = 4.dp)
                        )
                    }
                    if (building) {
                        IconButton(onClick = {
                            agentRef.value?.cancel()
                            renderer.value?.stop()
                            building = false
                            phase = Phase.Idle
                            phaseDetail = "已停止，画布保留已写入部分"
                            // 停止后取回已写内容，供续写
                            renderer.value?.readHtml { html ->
                                partialHtml = html.takeIf { it.length > 400 }
                            }
                        }) {
                            Box(Modifier.size(14.dp).background(GenTheme.Red, RoundedCornerShape(3.dp)))
                        }
                    } else {
                        IconButton(
                            onClick = {
                                if (cmd.isNotBlank()) { val p = cmd; cmd = ""; scope.launch { generate(p) } }
                            },
                            enabled = cmd.isNotBlank()
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "发送",
                                tint = if (cmd.isNotBlank()) GenTheme.Amber else GenTheme.Dim.copy(alpha = 0.4f),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().background(GenTheme.Screen)) {
            // —— A2UI 画布：AI 的世界 ——
            // 注意：AndroidView 的 factory 在首次组合时仅执行一次。这里刻意不引用任何
            // 会变化的状态（pages / stackCount 等），否则每次生成完成都会重建 WebView，
            // 把刚流式写入的文档整个清掉（历史上的"生成完画布变白"）。
            AndroidView(
                factory = { c ->
                    WebView(c).also { wv ->
                        setupWebView(wv)
                        webRef.value = wv
                        renderer.value = GenUiCanvas(
                            context = c,
                            webView = wv,
                            dark = dark,
                            onFirstPaint = {},
                            onPageTitle = {},
                            onBridgeCall = { bridgeCalls++ },
                            onWidget = { kind, payload -> nativeWidget = kind to payload }
                        )
                        wv.loadUrl("about:blank")
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // —— 原生渲染层 ——
            // AI 声明了 xml / compose 通道时，画布交给真实原生渲染。
            // 设计：画布是"HTML 宿主 + 原生叠加"。原生渲染是**独立于 WebView 的
            // 第二条渲染通道**——AI 写 XML 布局或 Compose 组件树，端上用真实
            // Android 控件 / Compose 组件渲染出来，不是网页仿真。
            //
            // 生成过程中不叠加（避免与流式内容打架）；生成完成后一次性呈现。
            // 用户可以随时关掉原生层，回看 HTML 部分——两条通道互不破坏。
            val native = nativeRender
            if (!building && native != null) {
                Box(
                    Modifier.fillMaxSize()
                        .background(GenTheme.Screen)
                        .verticalScroll(rememberScrollState())
                ) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when (native) {
                                    is NativeRender.Xml -> "◧ 原生布局"
                                    is NativeRender.Compose -> "◨ Compose"
                                    is NativeRender.Canvas -> "◩ GenCanvas"
                                    is NativeRender.Code -> "▤ 代码视图"
                                },
                                color = GenTheme.AmberDim, fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace, letterSpacing = 1.sp
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                "关掉原生层",
                                color = GenTheme.Dim, fontSize = 10.sp,
                                modifier = Modifier.clickable { nativeRender = null }
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        when (native) {
                            is NativeRender.Xml -> XmlNativeView(native.xml)
                            is NativeRender.Compose -> ComposeDescRenderer.Render(native.json) { action ->
                                // 原生控件的事件回传给 AI 页面：派发 mo:compose 事件
                                renderer.value?.dispatchComposeAction(action)
                            }
                            is NativeRender.Canvas -> CanvasNativeView(
                                native.json,
                                Modifier.fillMaxSize().padding(top = 4.dp)
                            ) { action ->
                                // 画布的事件回传给 AI 页面：派发 mo:canvas 事件
                                renderer.value?.dispatchCanvasAction(action)
                            }
                            is NativeRender.Code -> Column(
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                native.blocks.forEach { block ->
                                    com.ai.assistance.quro.genui.app.render.CodeBlockView(block)
                                }
                            }
                        }
                    }
                }
            }

            // —— WebView 生命周期释放 ——
            // WebView 即使离开界面也会继续跑 JS 定时器、持有网络请求与 Context，
            // 不主动销毁就是内存泄漏（长会话反复切换时尤其明显）。
            // 离开组合时：停加载 → 清历史 → 解绑视图 → destroy，并清空引用。
            DisposableEffect(Unit) {
                onDispose {
                    agentRef.value?.cancel()
                    webRef.value?.let { wv ->
                        runCatching { wv.stopLoading() }
                        runCatching { wv.loadUrl("about:blank") }
                        runCatching { wv.clearHistory() }
                        runCatching { wv.removeAllViews() }
                        runCatching { wv.destroy() }
                    }
                    webRef.value = null
                    renderer.value = null
                    agentRef.value = null
                }
            }

            // —— 首次使用 / 空画布：设计过的引导，而不是一屏空白 ——
            // 冷启动时用户看到的是 WebView 的空白页。不给引导 = 不知道该干什么。
            val hasProvider = cachedProvider != null
            if (!building && canvasStack.isEmpty() && partialHtml == null) {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 34.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (!hasProvider) {
                        // ① 还没配模型：这是所有新用户的第一步，必须明确指路
                        EmptyGlyph(GenTheme.Red)
                        Spacer(Modifier.height(18.dp))
                        Text(
                            "还差一步", color = GenTheme.Text, fontSize = 17.sp,
                            fontFamily = FontFamily.Serif, letterSpacing = 1.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "GenUI 需要一个模型来生成界面。\n去「模」页选一个供应商，粘上 API Key 就能用。",
                            color = GenTheme.Dim, fontSize = 12.sp, lineHeight = 19.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(20.dp))
                        OnboardButton("去配置模型") { onNavigate(NavTarget.Settings) }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "国内推荐 DeepSeek / 智谱 GLM，注册即送额度",
                            color = GenTheme.Dim.copy(alpha = .7f), fontSize = 10.sp
                        )
                    } else {
                        // ② 已配模型：告诉他能做什么，并给三个起点
                        EmptyGlyph(GenTheme.Amber)
                        Spacer(Modifier.height(18.dp))
                        Text(
                            soul.greeting.ifBlank { "说一句话，我给你写一个界面。" },
                            color = GenTheme.Text, fontSize = 16.sp,
                            fontFamily = FontFamily.Serif, lineHeight = 24.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "不是聊天回复 —— 是一屏真实可点击、可填写的界面\n" +
                                "网页 / 原生 XML 布局 / Compose 组件 / 代码演示，你说用哪种就用哪种",
                            color = GenTheme.Dim, fontSize = 11.sp, textAlign = TextAlign.Center,
                            lineHeight = 17.sp
                        )
                        Spacer(Modifier.height(24.dp))
                        listOf(
                            "帮我做个记账本，能记每天花销",
                            "用原生控件做个设置页，要有开关和滑杆",
                            "今天有什么值得关注的新闻？",
                            "用 Compose 做一个专注计时器，25分钟一轮"
                        ).forEach { s ->
                            Text(
                                s, color = GenTheme.Text.copy(alpha = .9f), fontSize = 12.5.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .background(GenTheme.Panel, RoundedCornerShape(10.dp))
                                    .border(0.5.dp, GenTheme.Line, RoundedCornerShape(10.dp))
                                    .clickable { cmd = s }
                                    .padding(horizontal = 14.dp, vertical = 11.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // —— 抖音式历史浏览：全屏上下滑动翻看已生成界面 ——
    if (browsing) {
        HistoryBrowser(
            pages = store.loadPages(),
            onExit = { browsing = false }
        )
    }

    // —— 思考过程抽屉 ——
    if (showThinking) {
        ThinkingSheet(
            timeline = timeline,
            building = building,
            onDismiss = { showThinking = false }
        )
    }

    // —— 界面栈抽屉 ——
    if (showStack) SessionStackSheet(
        pages = store.loadPages(),
        onDismiss = { showStack = false },
        onOpen = { p ->
            showStack = false
            renderer.value?.replay(p.html)
            restoreNative(p.html)
            canvasStack.clear()
            canvasStack.add(p)          // 回放 = 以该纸为新栈底，返回键不再穿透旧栈
            phase = Phase.Idle; phaseDetail = "回放「${p.title}」 · ${p.model}"
        },
        onClear = {
            store.clearPages()
            stackCount = 0
            canvasStack.clear()
        }
    )
}

private fun Double.format1(): String = String.format(Locale.US, "%.1f", this)

/**
 * 把一条 Agent 事件同时写进「时间线」（给人看）和「状态行」（给一眼看）。
 *
 * 为什么要一个函数做两件事：这两处必须永远一致。分开写迟早会漂移
 * （时间线说"调用 web_search"，状态行还写"思考中"）。
 *
 * [run] 是本轮生成共享的可变槽位：记录"当前正在运行的那条"索引，
 * 因为 ToolStarted 与 ToolFinished 是两次独立回调，中间必须记住位置。
 */
/** 绘制阶段的中文短名（语义由 PaintProbe 定义，这里只做展示映射） */
private fun paintStepLabel(s: com.ai.assistance.quro.genui.app.agent.AgentEvent.PaintStep): String = when (s) {
    com.ai.assistance.quro.genui.app.agent.AgentEvent.PaintStep.HEAD -> "读取文档"
    com.ai.assistance.quro.genui.app.agent.AgentEvent.PaintStep.STYLE -> "铺设样式"
    com.ai.assistance.quro.genui.app.agent.AgentEvent.PaintStep.LAYOUT -> "搭建骨架"
    com.ai.assistance.quro.genui.app.agent.AgentEvent.PaintStep.CONTENT -> "填充内容"
    com.ai.assistance.quro.genui.app.agent.AgentEvent.PaintStep.CHART -> "绘制图表"
    com.ai.assistance.quro.genui.app.agent.AgentEvent.PaintStep.SCRIPT -> "接线交互"
    com.ai.assistance.quro.genui.app.agent.AgentEvent.PaintStep.POLISH -> "收尾校验"
}

private class RunSlot {
    /** 当前正在执行的工具条目索引（ToolStarted → ToolFinished 之间保持） */
    var idx = -1
    /** 当前正在更新的绘制条目索引（Painting / RenderProgress 原地复用） */
    var paintIdx = -1
}

private inline fun applyEvent(
    tl: com.ai.assistance.quro.genui.app.agent.ThinkingTimeline,
    ev: com.ai.assistance.quro.genui.app.agent.AgentEvent,
    slot: RunSlot,
    report: (Phase, String, Int, Long) -> Unit
) {
    when (ev) {
        is com.ai.assistance.quro.genui.app.agent.AgentEvent.Started -> {
            slot.idx = -1
            tl.add(Kind.START, "开始生成", "「${ev.prompt.take(60)}」\n${ev.provider} · ${ev.model}")
            report(Phase.Thinking, "${ev.provider} · ${ev.model}", 0, 0L)
        }
        is com.ai.assistance.quro.genui.app.agent.AgentEvent.Thinking -> {
            tl.add(Kind.THINK, ev.note, running = true)
            report(Phase.Thinking, ev.note, 0, 0L)
        }
        is com.ai.assistance.quro.genui.app.agent.AgentEvent.Decided -> {
            tl.add(Kind.DECIDE, ev.reason, if (ev.toolCount > 0) "将调用 ${ev.toolCount} 个工具" else "")
            report(Phase.Deciding, ev.reason, 0, 0L)
        }
        is com.ai.assistance.quro.genui.app.agent.AgentEvent.ToolStarted -> {
            slot.idx = tl.add(Kind.TOOL, "调用 ${ev.tool}", "参数：${ev.argsBrief}", level = ev.level, running = true)
            report(Phase.Tooling, "${ev.tool}（L${ev.level}）", 0, 0L)
        }
        is com.ai.assistance.quro.genui.app.agent.AgentEvent.ToolAwaitingAuth -> {
            tl.add(Kind.AUTH, "等待授权 · ${ev.tool}", "L${ev.level} 需要你确认")
            report(Phase.Awaiting, ev.tool, 0, 0L)
        }
        is com.ai.assistance.quro.genui.app.agent.AgentEvent.ToolDenied -> {
            tl.add(Kind.DENIED, "已拒绝 · ${ev.tool}", ev.reason)
            report(Phase.Tooling, "${ev.tool} 被拒绝，正在调整方案", 0, 0L)
        }
        is com.ai.assistance.quro.genui.app.agent.AgentEvent.ToolFinished -> {
            if (slot.idx >= 0) {
                tl.update(slot.idx, false, text = "${ev.tool} · ${ev.ms}ms", detail = ev.summary)
                slot.idx = -1
            } else {
                tl.add(Kind.RESULT, "${ev.tool} · ${ev.ms}ms", ev.summary)
            }
            report(Phase.Tooling, if (ev.ok) "${ev.tool} 完成" else "${ev.tool} 失败", 0, 0L)
        }
        is com.ai.assistance.quro.genui.app.agent.AgentEvent.Rendering -> {
            tl.add(Kind.RENDER, ev.note, running = true)
            slot.paintIdx = -1   // 新的一轮绘制开始，绘制条目重新开一条
            report(Phase.Rendering, "流式写入中", 0, 0L)
        }
        is com.ai.assistance.quro.genui.app.agent.AgentEvent.Painting -> {
            // 绘制过程：把"正在画什么"写成一条可读的账。
            // 关键取舍——**原地更新同一条，而不是每次新增**：
            // 否则一屏界面会产生十几条"正在写…"，时间线被稀释成噪音。
            // 只有阶段真正推进时才更新文字，用户看到的是"文字在变"，而不是刷屏。
            val text = "${paintStepLabel(ev.step)} · ${(ev.pct * 100).toInt()}%"
            if (slot.paintIdx >= 0) {
                tl.update(slot.paintIdx, true, text = text, detail = ev.detail)
            } else {
                slot.paintIdx = tl.add(Kind.PAINT, text, ev.detail, running = true)
            }
            report(Phase.Rendering, "${ev.detail} · ${(ev.pct * 100).toInt()}%", 0, 0L)
        }
        is com.ai.assistance.quro.genui.app.agent.AgentEvent.RenderProgress -> {
            // 体量心跳：并入绘制条目，不新增，避免与语义进度重复
            if (slot.paintIdx >= 0) tl.update(slot.paintIdx, true, detail = "${ev.bytes / 1024}KB 已写入")
            report(Phase.Rendering, "${ev.bytes / 1024}KB", 0, 0L)
        }
        is com.ai.assistance.quro.genui.app.agent.AgentEvent.Finished -> {
            // 收尾：把仍在 running 的绘制条目定格为完成，否则它永远转圈
            if (slot.paintIdx >= 0) { tl.update(slot.paintIdx, false); slot.paintIdx = -1 }
            tl.add(Kind.DONE, "完成「${ev.title}」",
                "${ev.bytes / 1024}KB · ${ev.toolCalls} 次工具调用 · ${ev.elapsedMs / 1000.0}s")
            report(Phase.Done, "「${ev.title}」· ${ev.bytes / 1024}KB", ev.toolCalls, ev.elapsedMs)
        }
        is com.ai.assistance.quro.genui.app.agent.AgentEvent.Failed -> {
            tl.add(Kind.ERROR, "出错", "${ev.message}\n已保留 ${ev.partialBytes / 1024}KB 可续写")
            report(Phase.Failed, ev.message.take(80), 0, 0L)
        }
    }
}

/** WebView 基础安全配置（__moHost 监听器由 A2UIRenderer 统一注册，避免重复注册崩溃） */
@SuppressLint("SetJavaScriptEnabled")
private fun setupWebView(wv: WebView) {
    wv.settings.javaScriptEnabled = true
    wv.settings.domStorageEnabled = true
    wv.settings.allowFileAccess = false
    wv.settings.allowContentAccess = false
    wv.settings.mediaPlaybackRequiresUserGesture = true
    wv.setBackgroundColor(android.graphics.Color.parseColor("#F0E9DC"))
}

/**
 * 历史浏览用的 WebView 配置 —— 必须与主画布完全一致，否则历史页会空白。
 *
 * 主画布用 base URL `https://genui.local/` + WebViewAssetLoader 提供运行时脚本与字体；
 * AI 生成的界面经常依赖这些资源（Vue/React/Mermaid/字体）。之前的 HistoryBrowser 用
 * `about:blank` 作 base URL 且不挂 asset loader，相对路径的运行时/字体请求解析到
 * about:blank 而加载失败 → 整页只剩米色背景（"空白历史"）。
 *
 * 这里同样挂上 asset loader（shouldInterceptRequest 截获 genui.local/assets/），
 * 并按 genui.local 加载，历史页才能和主画布一样正常渲染。
 */
private fun setupHistoryWebView(wv: WebView) {
    val ctx = wv.context
    val assetLoader = WebViewAssetLoader.Builder()
        .setDomain("genui.local")
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx))
        .build()
    with(wv.settings) {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        mediaPlaybackRequiresUserGesture = true
        loadsImagesAutomatically = true
    }
    wv.setBackgroundColor(android.graphics.Color.parseColor("#F0E9DC"))
    wv.webViewClient = object : android.webkit.WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?
        ): WebResourceResponse? = request?.url?.let { assetLoader.shouldInterceptRequest(it) }
    }
}

/**
 * 原生 XML 布局渲染容器 —— 把 AI 写的 Android XML 布局真实渲染成原生控件，
 * 再嵌进 Compose 画布。
 *
 * 为什么用 AndroidView 而不是 Compose 重写控件：
 * AI 写的是 **Android XML 布局**，它的目标就是 Android 原生控件体系。
 * 用 AndroidView 承载才能得到货真价实的原生控件（系统字体、系统触感、
 * 系统控件的真实绘制），这正是"支持 XML"的意义所在。
 */
@Composable
private fun XmlNativeView(xml: String) {
    val ctx = LocalContext.current
    // 只有 xml 变化时才重新解析渲染——否则每次重组都会重建整棵 View 树
    val view = remember(xml) {
        com.ai.assistance.quro.genui.app.render.XmlLayoutRenderer.render(ctx, xml).view
    }
    AndroidView(
        factory = { view },
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * 空态图形 —— 一个"正在生长中的界面骨架"：外框 + 三条长短不一的横条。
 * 用 drawBehind 直接画，不需要 drawable 资源；颜色随状态传入（未配置=红，就绪=琥珀）。
 */
@Composable
private fun EmptyGlyph(tint: Color) {
    Box(
        Modifier.size(76.dp).drawBehind {
            val stroke = 1.6.dp.toPx()
            val r = 10.dp.toPx()
            val inset = 2.dp.toPx()
            // 外框（圆角矩形，手绘：四段线 + 四角弧）
            val left = inset; val top = inset
            val right = size.width - inset; val bottom = size.height - inset
            drawLine(tint.copy(alpha = 0.55f), Offset(left + r, top), Offset(right - r, top), stroke)
            drawLine(tint.copy(alpha = 0.55f), Offset(left + r, bottom), Offset(right - r, bottom), stroke)
            drawLine(tint.copy(alpha = 0.55f), Offset(left, top + r), Offset(left, bottom - r), stroke)
            drawLine(tint.copy(alpha = 0.55f), Offset(right, top + r), Offset(right, bottom - r), stroke)
            // 四角
            drawArc(tint.copy(alpha = 0.55f), 180f, 90f, false,
                topLeft = Offset(left, top), size = Size(r * 2, r * 2), style = Stroke(stroke))
            drawArc(tint.copy(alpha = 0.55f), 270f, 90f, false,
                topLeft = Offset(right - r * 2, top), size = Size(r * 2, r * 2), style = Stroke(stroke))
            drawArc(tint.copy(alpha = 0.55f), 0f, 90f, false,
                topLeft = Offset(right - r * 2, bottom - r * 2), size = Size(r * 2, r * 2), style = Stroke(stroke))
            drawArc(tint.copy(alpha = 0.55f), 90f, 90f, false,
                topLeft = Offset(left, bottom - r * 2), size = Size(r * 2, r * 2), style = Stroke(stroke))
            // 内部三条"内容占位"，长短错落，暗示"这里将被填满"
            val barH = 3.dp.toPx()
            val x0 = size.width * 0.22f
            val rows = listOf(0.62f, 0.44f, 0.54f)
            rows.forEachIndexed { i, wFrac ->
                val y = size.height * (0.34f + i * 0.16f)
                drawRoundRect(
                    color = if (i == 0) tint else tint.copy(alpha = 0.35f),
                    topLeft = Offset(x0, y),
                    size = Size(size.width * wFrac - x0 + size.width * 0.22f, barH),
                    cornerRadius = CornerRadius(barH / 2)
                )
            }
        }
    )
}

/** 空态主行动按钮（自绘，保持与整体调性一致） */
@Composable
private fun OnboardButton(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = GenTheme.Screen, fontSize = 13.sp,
        fontFamily = FontFamily.Monospace, letterSpacing = 0.5.sp,
        modifier = Modifier
            .background(GenTheme.Amber, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 11.dp)
    )
}

/**
 * 抖音式历史浏览：全屏覆盖层，上下滑动翻看过去生成的界面，像刷视频一样逐屏浏览。
 *
 * 设计取舍（为什么不复用主画布的 WebView）：
 *  - 浏览是「只读回看」，不需要主画布那套流式注入 / 桥接 / 原生叠加，单独一个干净 WebView 更稳；
 *  - 用 `key(pos)` 强制重建 WebView，每切一屏就重新 load 对应 HTML，避免 WebView 跨页状态串台；
 *  - 手势层全屏覆盖在 WebView 之上，捕获垂直滑动做翻页——浏览态下界面本就不交互，正合此意。
 */
@Composable
private fun HistoryBrowser(
    pages: List<GeneratedPage>,
    onExit: () -> Unit
) {
    var pos by remember { mutableStateOf(maxOf(0, pages.lastIndex)) }
    Box(Modifier.fillMaxSize().background(GenTheme.Screen)) {
        if (pages.isEmpty()) {
            Column(
                Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally
            ) {
                Text("还没有生成过的界面", color = GenTheme.Dim, fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))
                Text(
                    "点此返回", color = GenTheme.Amber, fontSize = 12.sp,
                    modifier = Modifier.clickable { onExit() }.padding(10.dp)
                )
            }
            return
        }
        val page = pages[pos]
        // 用 key(pos) 重建 WebView，确保每屏都重新加载对应 HTML。
        // 必须按 genui.local 加载 + 挂 asset loader（见 setupHistoryWebView），
        // 否则 AI 页面依赖的运行时/字体解析不到，历史页会空白。
        key(pos) {
            AndroidView(
                factory = { c ->
                    WebView(c).also { wv ->
                        setupHistoryWebView(wv)
                        wv.loadDataWithBaseURL("https://genui.local/", page.html, "text/html", "UTF-8", null)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
        // 全屏手势层：覆盖 WebView，捕获上下滑动翻页（浏览态不交互，正好）
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                var acc = 0f
                detectVerticalDragGestures(
                    onDragStart = { _ -> acc = 0f },
                    onVerticalDrag = { _, d -> acc += d },
                    onDragEnd = {
                        val th = 80.dp.toPx()
                        if (acc < -th && pos > 0) pos--
                        else if (acc > th && pos < pages.lastIndex) pos++
                    }
                )
            }
        ) {}
        // 顶部信息条
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .background(GenTheme.Screen.copy(alpha = 0.85f))
                .padding(horizontal = 14.dp, vertical = 9.dp)
                .statusBarsPadding(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "✕", color = GenTheme.Text, fontSize = 15.sp,
                modifier = Modifier.clickable { onExit() }.padding(6.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "${pos + 1} / ${pages.size}",
                color = GenTheme.Amber, fontSize = 11.sp, fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.width(10.dp))
            Text(
                page.title, color = GenTheme.Dim, fontSize = 11.sp, maxLines = 1,
                modifier = Modifier.weight(1f)
            )
        }
        // 底部操作提示
        Text(
            "↑ 上滑看更早    ↓ 下滑看更新    ✕ 退出",
            color = GenTheme.Dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp)
                .background(GenTheme.Panel.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}
