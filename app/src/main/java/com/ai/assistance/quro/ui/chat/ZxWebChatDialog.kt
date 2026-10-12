package com.ai.assistance.quro.ui.chat

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ai.assistance.quro.R
import com.ai.assistance.quro.core.QuroAttachment
import com.ai.assistance.quro.core.QuroMessage
import com.ai.assistance.quro.core.QuroToolCall
import com.ai.assistance.quro.core.tools.ZxWebCardQueue
import com.ai.assistance.quro.ui.QuroChatViewModel
import com.ai.assistance.quro.util.qstr
import org.json.JSONObject

/**
 * ZorvAI 第三个对话框 —— Web UI 对话框。
 *
 * 定位（用户 2026-10-13 拍板）：**ZorvAI 对话框的 Web UI 复制品**。
 * ZorvAI 对话框有的功能这里全有——消息流、Markdown、流式输出、思考过程、
 * 工具调用、附件、输入框、停止/新对话——只是整套 UI 用网页实现，跑在 WebView 里。
 * 参照物：DeepSeek Harness 的 Web 对话框（`dsh web`）、operit 的外部链接对话框。
 *
 * zxwidget 在这里的角色：**动态交互界面**。AI 下发 `zx_web_card` 工具调用时，
 * Web 侧用 zx 组件契约把 DSL 编译成真正的网页控件（表格/表单/图表/看板/时间线…），
 * 用户直接在卡片上操作，交互结构化回传宿主并作为一条用户消息送回模型。
 *
 * 与 [ZxWebCardDialog] 的区别：那个是「单张卡片」对话框；本对话框是完整聊天端。
 */
object ZxWebChat {

    const val ASSET_NAME = "zx_chat_runtime.html"
    const val CONTRACTS_NAME = "zx_contracts.json"

    /**
     * 把组件契约注入运行时 HTML。
     *
     * 契约走 `<script type="application/json">` 原文注入：只挡 `</` 与 `<!--`
     * 两个序列结束符，其余直出。190 KB 的 JSON 若按 JS 字符串字面量转义，
     * 漏一个字符就整段白屏，且体积翻倍。
     */
    fun buildHtml(assetHtml: String, contractsJson: String): String {
        val safe = contractsJson
            .replace("</", "<\\/")
            .replace("<!--", "<\\!--")
        return assetHtml.replace("/*__ZX_CONTRACTS__*/", safe.ifBlank { "{}" })
    }

    /** 把附件列表转成 Web 侧可用的 JSON（WebView 私有目录文件用 file:// 可直接读）。 */
    fun attachmentsJson(list: List<QuroAttachment>?): String {
        val arr = org.json.JSONArray()
        list?.forEach { a ->
            arr.put(JSONObject().apply {
                put("type", a.type)
                put("uri", "file://" + a.uri)
                put("name", a.name)
            })
        }
        return arr.toString()
    }

    /** JS 字符串字面量转义：所有要 evaluateJavascript 传进去的字符串都过这里。 */
    fun js(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("'", "\\'")
        .replace("\n", "\\n")
        .replace("\r", "")
        .replace("<", "\\u003c")
        .replace(">", "\\u003e")
        .replace("&", "\\u0026")
}

/**
 * 宿主消息流 → Web UI 的增量投影器。
 *
 * 为什么要投影而不是整表重载：助手消息是**流式追加**的（content 一长再长），
 * 工具调用是**逐个落地**的（先 name+args，后 result+duration）。
 * 整表重载会让 Web 侧每次 token 都重建整个 DOM，流式直接卡死；
 * 增量投影只发「新增的那几个字」，和原生对话框的行为一致。
 */
private class WebChatProjector {

    /** 已投影的消息 id → 已下发的 content 长度 */
    private val contentLen = HashMap<String, Int>()
    /** 已投影的消息 id → 已下发的 reasoning 长度 */
    private val reasoningLen = HashMap<String, Int>()
    /** 已投影的消息 id → 工具调用下发状态 key=name@index → (有结果, 结果长度) */
    private val toolState = HashMap<String, MutableMap<String, Pair<Boolean, Int>>>()
    /** 已投影的消息 id 集合（用于判断新消息） */
    private val rendered = LinkedHashSet<String>()

    fun reset() {
        contentLen.clear(); reasoningLen.clear(); toolState.clear(); rendered.clear()
    }

    /**
     * 同步一批消息。[eval] 是把 JS 片段送进 WebView 的通道。
     * 返回 true 表示发生了可见变化（调用方据此决定是否滚动，由 Web 侧自己判断）。
     */
    fun sync(messages: List<QuroMessage>, eval: (String) -> Unit) {
        val visible = messages.filter { !it.hidden }
        val liveIds = visible.map { it.id }.toSet()
        // 会话被清空/切换：整表重载
        if (rendered.isNotEmpty() && liveIds.none { it in rendered }) {
            reset()
            eval("window.ZorvAI.chat.reset()")
            eval("window.ZorvAI.chat.load(" + ZxWebChat.js(loadJson(visible)) + ")")
            return
        }

        visible.forEach { m ->
            if (m.id !in rendered) {
                rendered += m.id
                when (m.role) {
                    "user" -> eval(
                        "window.ZorvAI.chat.user(" +
                            ZxWebChat.js(m.content) + "," +
                            ZxWebChat.attachmentsJson(m.attachments) + ")"
                    )
                    "assistant" -> {
                        if (m.content.isNotEmpty()) {
                            eval("window.ZorvAI.chat.beginAi(" + ZxWebChat.js(m.id) + ")")
                            eval("window.ZorvAI.chat.delta(" + ZxWebChat.js(m.id) + "," + ZxWebChat.js(m.content) + ")")
                        } else {
                            eval("window.ZorvAI.chat.beginAi(" + ZxWebChat.js(m.id) + ")")
                        }
                        if (!m.reasoning.isNullOrEmpty()) {
                            eval("window.ZorvAI.chat.reasoning(" + ZxWebChat.js(m.id) + "," + ZxWebChat.js(m.reasoning!!) + ")")
                            reasoningLen[m.id] = m.reasoning!!.length
                        }
                        if (!m.toolCalls.isNullOrEmpty()) {
                            m.toolCalls.forEachIndexed { i, tc ->
                                pushTool(m.id, i, tc, eval)
                                toolState.getOrPut(m.id) { mutableMapOf() }[tc.name + "@" + i] =
                                    (tc.result != null) to (tc.result?.length ?: 0)
                            }
                        }
                        if (m.content.isNotEmpty()) {
                            eval("window.ZorvAI.chat.endAi(" + ZxWebChat.js(m.id) + ")")
                        }
                        contentLen[m.id] = m.content.length
                    }
                    else -> { /* system/tool 管道消息：Web UI 不展示，与原生对话框一致 */ }
                }
                return@forEach
            }

            // 已投影的助手消息：增量
            if (m.role == "assistant") {
                val cl = contentLen[m.id] ?: 0
                if (m.content.length > cl) {
                    val chunk = m.content.substring(cl)
                    eval("window.ZorvAI.chat.delta(" + ZxWebChat.js(m.id) + "," + ZxWebChat.js(chunk) + ")")
                    contentLen[m.id] = m.content.length
                }
                val rl = reasoningLen[m.id] ?: 0
                val r = m.reasoning ?: ""
                if (r.length > rl) {
                    eval("window.ZorvAI.chat.reasoning(" + ZxWebChat.js(m.id) + "," + ZxWebChat.js(r.substring(rl)) + ")")
                    reasoningLen[m.id] = r.length
                }
                val calls = m.toolCalls ?: emptyList()
                calls.forEachIndexed { i, tc ->
                    val key = tc.name + "@" + i
                    val prev = toolState[m.id]?.get(key)
                    val hasResult = tc.result != null
                    val rLen = tc.result?.length ?: 0
                    if (prev == null || prev.first != hasResult || (hasResult && prev.second != rLen)) {
                        pushTool(m.id, i, tc, eval)
                        toolState.getOrPut(m.id) { mutableMapOf() }[key] = hasResult to rLen
                    }
                }
            }
        }

        // 流结束：把仍在流式状态的消息收尾
        visible.lastOrNull()?.let { last ->
            if (last.role == "assistant" && last.id in rendered) {
                eval("window.ZorvAI.chat.endAi(" + ZxWebChat.js(last.id) + ")")
            }
        }
    }

    private fun pushTool(id: String, index: Int, tc: QuroToolCall, eval: (String) -> Unit) {
        val status = if (tc.result != null) "ok" else "run"
        eval(
            "window.ZorvAI.chat.tool(" + ZxWebChat.js(id) + "," + ZxWebChat.js(tc.name) + "," +
                ZxWebChat.js(tc.arguments) + "," + ZxWebChat.js(status) + "," +
                ZxWebChat.js(tc.result ?: "") + "," + tc.durationMs + ")"
        )
    }

    private fun loadJson(list: List<QuroMessage>): String {
        val arr = org.json.JSONArray()
        list.forEach { m ->
            val o = JSONObject().apply {
                put("role", m.role)
                put("content", m.content)
                put("reasoning", m.reasoning ?: "")
                put("senderName", m.senderName ?: "")
                if (!m.toolCalls.isNullOrEmpty()) {
                    val tc = org.json.JSONArray()
                    m.toolCalls.forEach { t ->
                        tc.put(JSONObject().apply {
                            put("name", t.name)
                            put("arguments", t.arguments)
                            put("result", t.result ?: JSONObject.NULL)
                            put("durationMs", t.durationMs)
                        })
                    }
                    put("toolCalls", tc)
                }
                if (!m.attachments.isNullOrEmpty()) {
                    put("attachments", org.json.JSONArray(ZxWebChat.attachmentsJson(m.attachments)))
                }
            }
            arr.put(o)
        }
        return arr.toString()
    }
}

/**
 * Web UI 对话框本体。
 *
 * @param vm       宿主聊天 ViewModel——本对话框与 ZorvAI 主对话框**同一个会话**，
 *                 不是另起炉灶：消息、工具、历史全部实时同步。
 * @param modelCfg 当前模型配置（发送用）
 * @param dark     深色主题
 * @param onDismiss 关闭
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ZxWebChatDialog(
    vm: QuroChatViewModel,
    dark: Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }

    val assetHtml = remember(context) {
        runCatching {
            context.assets.open(ZxWebChat.ASSET_NAME).bufferedReader().use { it.readText() }
        }.getOrElse { "" }
    }
    val contractsJson = remember(context) {
        runCatching {
            context.assets.open(ZxWebChat.CONTRACTS_NAME).bufferedReader().use { it.readText() }
        }.getOrElse { "" }
    }

    val busy by vm.busy.collectAsState()

    /**
     * WebView 是否已加载完页面。
     *
     * 必须用 onPageFinished 门控，不能用 LaunchedEffect(Unit) 直接下发：
     * ① 进组合时 webView 仍为 null，此时的投影会被 webView?.let 静默吞掉；
     * ② loadDataWithBaseURL 是异步的，页面脚本（window.ZorvAI.chat.*）此时尚未定义，
     *    evaluateJavascript 会打到一个还没有运行时的页面。
     * 早期版本就是踩了这个坑——打开对话框时历史消息与标题全空。
     */
    var wvReady by remember { mutableStateOf(false) }

    // 投影器：消息流 → Web UI（增量）
    val projector = remember { WebChatProjector() }
    LaunchedEffect(wvReady) {
        if (!wvReady) return@LaunchedEffect
        vm.messages.collect { list ->
            webView?.let { wv -> projector.sync(list) { js -> wv.evaluateJavascript(js, null) } }
        }
    }
    LaunchedEffect(wvReady, busy) {
        if (wvReady) webView?.evaluateJavascript("window.ZorvAI.chat.setBusy($busy);", null)
    }
    LaunchedEffect(wvReady, dark) {
        if (wvReady) webView?.evaluateJavascript("window.ZorvAI.chat.setDark($dark);", null)
    }
    // 初始标题
    LaunchedEffect(wvReady) {
        if (wvReady) {
            webView?.evaluateJavascript(
                "window.ZorvAI.chat.title(" + ZxWebChat.js(qstr(R.string.qk_04074)) + ");", null
            )
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxSize(0.97f),
        ) {
            Box(Modifier.fillMaxSize()) {
                if (assetHtml.isBlank()) {
                    Text(
                        qstr(R.string.qk_04068, ZxWebChat.ASSET_NAME),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center).padding(20.dp),
                    )
                } else {
                    AndroidView(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(6.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (dark) Color(0xFF0D0F13) else Color(0xFFF6F7F9)),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                webView = this
                                setBackgroundColor(AndroidColor.TRANSPARENT)
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.loadWithOverviewMode = true
                                settings.useWideViewPort = true
                                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                                settings.textZoom = 100
                                setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                                addJavascriptInterface(
                                    object {
                                        /** 用户在 Web UI 里发消息 → 走宿主同一会话 */
                                        @JavascriptInterface
                                        fun send(text: String) {
                                            val t = text.trim()
                                            // cfg 走 vm.send 默认值（repo.load()），与主对话框同一会话同一配置
                                            if (t.isNotEmpty()) vm.send(t)
                                        }

                                        /** 停止生成 */
                                        @JavascriptInterface
                                        fun stop() { vm.stop() }

                                        /** 新对话 */
                                        @JavascriptInterface
                                        fun newChat() { vm.newConversation() }

                                        /**
                                         * zxwidget 卡片交互 → 回传宿主。
                                         *
                                         * 两条回路，与单卡片对话框保持同一语义：
                                         * ① 有 zx_web_card 工具正在等这张卡（卡片是内联渲染的，
                                         *    JS 载荷里只有组件名，只能按组件名匹配）→ 作为**工具结果**回传，
                                         *    本轮对话直接继续，模型不必白等 120s 超时；
                                         * ② 没有工具在等（卡片来自动态 UI 动作 / 历史重放）→
                                         *    整理成一条**用户消息**发出去。
                                         */
                                        @JavascriptInterface
                                        fun interact(payload: String) {
                                            val json = runCatching { JSONObject(payload) }.getOrElse {
                                                JSONObject().put("action", "raw").put("summary", payload)
                                            }
                                            val comp = json.optString("component").ifBlank { "zx_card" }
                                            val text = ZxWebCard.summarize(comp, json)
                                            if (!ZxWebCardQueue.submitByComponent(comp, text)) vm.send(text)
                                        }
                                    },
                                    "ZorvAIBridge",
                                )
                                webViewClient = object : WebViewClient() {
                                    /** 页面脚本就绪后才开门控，之后的投影/主题/忙态才下得进去 */
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        wvReady = true
                                    }
                                }
                                loadDataWithBaseURL(
                                    null,
                                    ZxWebChat.buildHtml(assetHtml, contractsJson),
                                    "text/html",
                                    "utf-8",
                                    null,
                                )
                            }
                        },
                    )
                }

                // 关闭按钮浮在右上角（Web UI 自带顶栏，这里只放退出）
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        qstr(R.string.qk_00065),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.removeJavascriptInterface("ZorvAIBridge")
            webView?.destroy()
            webView = null
        }
    }
}
