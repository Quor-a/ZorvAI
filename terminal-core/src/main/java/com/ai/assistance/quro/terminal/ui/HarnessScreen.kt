package com.ai.assistance.quro.terminal.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ai.assistance.quro.terminal.harness.DeepSeekHarness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * DeepSeek Harness 页。
 *
 * 只做「宿主该做的事」：把运行时装好、把服务拉起来、把日志摊开、把**它自己的界面**装进
 * WebView。harness 的会话、插件、设置、主题全部由它自带的前端负责 —— App 侧不重做一遍 UI，
 * 也不裁剪它的功能。
 */
@Composable
fun HarnessScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val harness = remember { DeepSeekHarness.get(context) }
    val scope = rememberCoroutineScope()

    var installed by remember { mutableStateOf(harness.isInstalled()) }
    var running by remember { mutableStateOf(harness.isRunning()) }
    var port by remember { mutableStateOf(harness.activePort()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var logs by remember { mutableStateOf("") }
    var showWeb by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    /** 实际要加载的地址：能拿到 launch token 时就带 token，让引擎自己 303 + Set-Cookie 自愈。 */
    var harnessUrl by remember { mutableStateOf("") }
    /** 401 自动重取鉴权的次数上限 —— 没有它，坏 token 会让 WebView 与壳侧互相刷新成死循环。 */
    var authRetries by remember { mutableStateOf(0) }

    fun refresh() {
        installed = harness.isInstalled()
        running = harness.isRunning()
        port = harness.activePort()
        logs = harness.logs()
    }

    // 进程在跑时定时刷日志与状态
    LaunchedEffect(running) {
        while (running) {
            delay(1500)
            refresh()
        }
    }

    // 进入 harness 界面。
    //
    // **主路径是带 token 的 URL**（上游 MainActivity.loadConfirmedEnginePage）：
    //   loadUrl(if (token != null) ENGINE_URL + "/?token=" + token else ENGINE_URL)
    // 让引擎自己走 303 + Set-Cookie 完成自愈。这条链不依赖壳侧任何 cookie 操作，
    // 也就不依赖「token 行还在日志里」「CookieManager 落库时序」这些容易碎的环节 ——
    // 之前裸 loadUrl + 只注入 cookie 的那版，任何一个环节慢一步就是整屏白。
    //
    // cookie 注入降级为**预热**：提前把 /api 请求带上鉴权，少一次 401 往返；
    // 换不到也不阻塞打开界面（token URL 自己会换）。
    fun openHarnessUi() {
        busy = true
        status = ""
        scope.launch {
            val token = harness.launchToken()
            val cookie = harness.ensureAuthCookie()
            if (cookie != null) {
                runCatching {
                    android.webkit.CookieManager.getInstance().apply {
                        setCookie(harness.baseUrl(), cookie)
                        flush()
                    }
                }.onSuccess { status = "已注入鉴权 cookie" }
                    .onFailure { status = "注入 cookie 失败：" + it.message }
            } else {
                status = "未取到鉴权 cookie（日志里还没有 token，或该引擎版本不要求鉴权）"
            }
            harnessUrl = if (token != null) {
                harness.baseUrl() + "/?token=" + token
            } else {
                harness.baseUrl()
            }
            authRetries = 0
            busy = false
            showWeb = true
        }
    }

    if (showWeb) {
        // harness 的 Web UI 自带一整条顶栏（左侧返回、右侧刷新）。之前 Compose 再画一条顶栏，
        // 就成了两层标题叠着 —— 那是重复，不是层次。所以这里不再画顶栏，
        // 只在右下角放一个不与它打架的悬浮退出入口。
        Box(
            Modifier
                .fillMaxSize()
                .background(TerminalTheme.backgroundColor)
        ) {
            HarnessWebView(
                url = harnessUrl.ifBlank { harness.baseUrl() },
                onClose = { showWeb = false },
                onAuthRetry = {
                    // 401 只自动重取有限次：token 过期/失效时会与 WebView 互相刷新成死循环
                    if (authRetries < 2) {
                        authRetries += 1
                        openHarnessUi()
                    } else {
                        status = "多次 401：请点「诊断」看引擎日志，或停止后重启服务"
                    }
                },
                onWebViewReady = { webViewRef = it },
            )
        }
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(TerminalTheme.backgroundColor)
    ) {
        // ── 顶栏 ──
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack, "返回",
                    tint = TerminalTheme.onSurfaceColor
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "DeepSeek Harness",
                    color = TerminalTheme.onSurfaceColor,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    if (running) "运行中 · 127.0.0.1:" + port else "未运行",
                    color = TerminalTheme.onSurfaceVariant,
                    fontSize = 10.5.sp
                )
            }
            if (busy) {
                CircularProgressIndicator(
                    Modifier.size(15.dp), strokeWidth = 2.dp,
                    color = TerminalTheme.accentColor
                )
                Spacer(Modifier.width(8.dp))
            }
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ① 运行时
            HarnessBlock {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HarnessTitle("运行时")
                    Spacer(Modifier.weight(1f))
                    HarnessDot(installed)
                    Spacer(Modifier.width(6.dp))
                    HarnessText(
                        if (installed) "已安装" else "未安装",
                        TerminalTheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
                HarnessText("内置 Node.js 26（bionic 构建）与完整 dsh 包，含它自带的前端。")
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!installed) {
                        HarnessPrimary(if (busy) "解压中…" else "安装运行时", busy) {
                            busy = true
                            status = ""
                            scope.launch {
                                val r = withContext(Dispatchers.IO) {
                                    harness.ensureInstalled { m -> status = m }
                                }
                                busy = false
                                status = r.fold({ "运行时已就绪" }, {
                                    "安装失败：" + (it.message ?: it.javaClass.simpleName)
                                })
                                refresh()
                            }
                        }
                    } else {
                        HarnessAction(if (running) "停止" else "启动") {
                            busy = true
                            status = ""
                            scope.launch {
                                if (running) {
                                    withContext(Dispatchers.IO) { harness.stop() }
                                    status = "已停止"
                                } else {
                                    val r = withContext(Dispatchers.IO) { harness.start() }
                                    status = r.fold(
                                        { "已启动，端口 $it" },
                                        { "启动失败：" + (it.message ?: it.javaClass.simpleName) }
                                    )
                                }
                                busy = false
                                refresh()
                            }
                        }
                        if (running) {
                            HarnessAction("打开界面") { openHarnessUi() }
                        }
                        HarnessAction("诊断") {
                            busy = true
                            scope.launch {
                                status = withContext(Dispatchers.IO) { harness.diagnose() }
                                busy = false
                            }
                        }
                    }
                }
            }

            // ② 界面入口（独立成块，强调"界面是它自己的"）
            HarnessBlock {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HarnessTitle("Harness 界面")
                    Spacer(Modifier.weight(1f))
                    if (running) {
                        HarnessAction("进入") { openHarnessUi() }
                    }
                }
                Spacer(Modifier.height(8.dp))
                HarnessText(
                    if (running)
                        "加载 " + harness.baseUrl() + " —— 会话、插件、设置全部由 harness 自己的前端提供。"
                    else
                        "先启动服务，再进入它的完整界面。"
                )
            }

            if (status.isNotBlank()) {
                HarnessBlock { HarnessText(status) }
            }

            if (logs.isNotBlank()) {
                HarnessBlock {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HarnessText("日志", TerminalTheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        HarnessTap("清空") { harness.clearLogs(); refresh() }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        logs.takeLast(4000),
                        color = TerminalTheme.onSurfaceColor,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

/**
 * 主帧走完后探一次 DOM：`#root` 子节点数与正文长度都是 0 ⇒ 纯白屏。
 *
 * SPA 的 hydration 失败**不会**触发任何 WebView 回调（主帧是 200、正文也收到了），
 * 只有真的去看 DOM 才知道。返回 `子节点数|正文长度`。
 */
private const val DSH_BLANK_JS = """
(function(){
  try {
    var r = document.getElementById('root');
    var n = r ? r.childElementCount : -1;
    var t = (document.body && document.body.innerText) ? document.body.innerText.trim().length : -1;
    return n + '|' + t;
  } catch (e) { return 'err|' + e; }
})()
"""

/**
 * 诊断现场：把 WebView 里真实的状态读出来，不靠猜。
 *
 * 重点是 `Promise.withResolvers` / `structuredClone` / `AbortSignal.any` / `Iterator` 四项 ——
 * 引擎的 boot-readiness 标记就挂在 `Promise.withResolvers()` 上，宿主兼容层
 * （`@dsh-android/dsh-host-web-compat`）没注入成功时它是个 TypeError，客户端 entry 会
 * **永久 await**，表现就是白屏且没有任何报错。这一项是区分「资源没到」与「 polyfill 没生效」的关键。
 */
private const val DSH_DIAG_JS = """
(function(){
  var L = [];
  function p(k, v) { L.push(k + ': ' + v); }
  p('UA', navigator.userAgent);
  p('URL', location.href);
  p('readyState', document.readyState);
  var root = document.getElementById('root');
  p('#root 子节点', root ? root.childElementCount : '(无 #root)');
  p('正文长度', document.body ? document.body.innerText.trim().length : -1);
  p('HTML 长度', document.documentElement ? document.documentElement.outerHTML.length : -1);
  var d = window.__dshDiag;
  p('JS 错误数', d && d.errors ? d.errors.length : 0);
  if (d && d.errors && d.errors.length) {
    for (var i = Math.max(0, d.errors.length - 6); i < d.errors.length; i++) p('  错误', d.errors[i]);
  }
  p('Promise.withResolvers', typeof Promise.withResolvers);
  p('structuredClone', typeof structuredClone);
  p('AbortSignal.any', (typeof AbortSignal !== 'undefined') ? typeof AbortSignal.any : 'AbortSignal 缺失');
  p('Iterator', typeof globalThis.Iterator);
  p('Cookie', document.cookie ? document.cookie.replace(/=[^;]{12,}/g, '=<已脱敏>') : '(空)');
  return L.join('\n');
})()
"""

/** `evaluateJavascript` 回包是 JSON 字符串，按 JSON 规则反转义回来（单遍扫描，不连环替换）。 */
private fun unescapeJsString(raw: String): String {
    val s = if (raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
        raw.substring(1, raw.length - 1)
    } else {
        raw
    }
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length) {
            when (s[i + 1]) {
                'n' -> { sb.append('\n'); i += 2 }
                't' -> { sb.append("  "); i += 2 }
                'r' -> { sb.append('\r'); i += 2 }
                '"' -> { sb.append('"'); i += 2 }
                '\\' -> { sb.append('\\'); i += 2 }
                '/' -> { sb.append('/'); i += 2 }
                'b' -> { sb.append('\b'); i += 2 }
                'f' -> { sb.append('\u000C'); i += 2 }
                'u' -> if (i + 6 <= s.length) {
                    val hex = s.substring(i + 2, i + 6)
                    val cp = hex.toIntOrNull(16)
                    if (cp != null) { sb.append(cp.toChar()); i += 6 } else { sb.append(c); i++ }
                } else { sb.append(c); i++ }
                else -> { sb.append(c); i++ }
            }
        } else {
            sb.append(c); i++
        }
    }
    return sb.toString()
}

/**
 * harness 自带前端的宿主。
 *
 * 本地 SPA 依赖 localStorage / IndexedDB / fetch 到同源 API，所以 domStorage 与 JS 必须开；
 * 页面只跑在 127.0.0.1，不涉及外网混合内容。
 *
 * ## 白屏是怎么被抓住的
 *
 * 主框架 200、正文正常，但整屏纯白 —— 这是 SPA 最典型的坏法，而 `onReceivedError`
 * **不会**报主框架。能观察到它的只有三处，这里全都接上了：
 *  1. `onReceivedError` 里 `isForMainFrame == false` 的那些（子资源 404 / 失败）——
 *     原来的实现把它们整段丢掉，所以白屏时界面一个字都不显示；
 *  2. document-start 注入的 `window.__dshDiag.errors`（JS 期异常 / unhandledrejection）；
 *  3. `onPageFinished` 之后探一次 `#root` 的子节点数与正文长度 —— 两个都是 0 就是白屏。
 * 任一中招就把诊断面板顶上来（半透明盖在 WebView 上，不销毁页面），
 * 右下角常驻一个「诊断」悬浮钮，随时读现场。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun HarnessWebView(
    url: String,
    onClose: () -> Unit,
    onAuthRetry: () -> Unit,
    onWebViewReady: (WebView?) -> Unit = {},
) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableStateOf(0) }
    var loadError by remember { mutableStateOf<String?>(null) }
    /** 子资源（JS / CSS / 字体）失败清单 —— 主框架 200 但整屏全白，原因九成在这里。 */
    var subErrors by remember { mutableStateOf<List<String>>(emptyList()) }
    /** 主帧加载完成后 `#root` 仍是空的 ⇒ 纯白屏。 */
    var blankScreen by remember { mutableStateOf(false) }
    /** 诊断按钮读回来的现场。 */
    var diag by remember { mutableStateOf<String?>(null) }
    var pageDone by remember { mutableStateOf(false) }

    // 主帧走完 2.5s 后探一次实机 DOM。SPA 的 hydration 失败不触发任何 WebView 回调，
    // 只有真的去看 #root 才知道是不是白屏。
    LaunchedEffect(pageDone, url) {
        if (!pageDone) return@LaunchedEffect
        delay(2500)
        val wv = webView ?: return@LaunchedEffect
        wv.evaluateJavascript(DSH_BLANK_JS) { raw ->
            val body = unescapeJsString(raw.orEmpty())
            val kids = body.substringBefore('|').trim().toIntOrNull() ?: -1
            val textLen = body.substringAfter('|').trim().toIntOrNull() ?: -1
            blankScreen = kids == 0 && textLen == 0
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.let { wv ->
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.stopLoading()
                wv.destroy()
            }
            webView = null
        }
    }

    // 返回键：能后退就后退，退无可退才退出界面 —— 直接退出会把会话里的页面栈丢掉
    BackHandler {
        val wv = webView
        if (wv != null && wv.canGoBack()) wv.goBack() else onClose()
    }

    fun reload() {
        loadError = null
        blankScreen = false
        subErrors = emptyList()
        diag = null
        pageDone = false
        webView?.loadUrl(url)
    }

    fun collectDiag() {
        val wv = webView
        if (wv == null) {
            diag = "WebView 尚未创建"
            return
        }
        diag = "读取中…"
        wv.evaluateJavascript(DSH_DIAG_JS) { raw -> diag = unescapeJsString(raw.orEmpty()) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(TerminalTheme.backgroundColor)
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                // 系统导航栏与输入法避让：否则引擎页底部（输入框、会话列表）会被手势区/键盘压住
                .windowInsetsPadding(WindowInsets.navigationBars)
                .imePadding(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        // 本地引擎页面不需要文件/内容访问，也不需要混合内容：
                        // 开着只会扩大攻击面（引擎页与本地 127.0.0.1 同源，不需要跨源放行）
                        allowFileAccess = false
                        allowContentAccess = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        cacheMode = WebSettings.LOAD_NO_CACHE
                        mediaPlaybackRequiresUserGesture = true
                        javaScriptCanOpenWindowsAutomatically = false
                        setSupportMultipleWindows(false)
                        setGeolocationEnabled(false)
                        loadWithOverviewMode = true
                        useWideViewPort = true
                        setSupportZoom(true)
                        builtInZoomControls = true
                        displayZoomControls = false
                        defaultTextEncodingName = "utf-8"
                    }
                    // 深色跟随 App 自身主题。这项能力在 WebView provider 上、不在系统版本上，
                    // 所以必须走能力门而不是判 SDK_INT —— App 本身就是深色，故两路反色都关掉，
                    // 否则页面会被二次压暗（部分 ROM 还会把 WebView 强行反色）。
                    runCatching {
                        when {
                            WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING) ->
                                WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false)
                            WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK) -> {
                                @Suppress("DEPRECATION")
                                WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_OFF)
                            }
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean {
                            val u = request.url.toString()
                            // 引擎自己的页面（含会话内跳转、token 交换后的 303 回跳）照常加载
                            if (u.startsWith(url) || u.contains("127.0.0.1")) return false
                            // 站外链接交给系统，不在引擎页里打开
                            return runCatching {
                                context.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW, request.url
                                    )
                                )
                            }.isSuccess
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            pageDone = true
                        }

                        override fun onReceivedHttpError(
                            view: WebView,
                            request: WebResourceRequest,
                            errorResponse: WebResourceResponse,
                        ) {
                            // 上游把每个 /api 都挡在签名 cookie 后面（401）；
                            // 主框架被拒说明 cookie 过期或没取到 → 重新走一遍鉴权再加载
                            if (errorResponse.statusCode == 401 && request.isForMainFrame) {
                                onAuthRetry()
                            }
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError,
                        ) {
                            val line = "${error.errorCode} ${error.description} ← ${request.url}"
                            if (request.isForMainFrame) {
                                loadError = "主框架加载失败：$line"
                            } else {
                                // 子资源失败不弹错页（页面还能用），但必须记账：
                                // 主框架 200 而整屏全白，就是某个 JS chunk 在这里 404 了
                                subErrors = subErrors + line
                                android.util.Log.w("HarnessScreen", "子资源失败: $line")
                            }
                        }
                    }
                    loadUrl(url)
                    webView = this
                    onWebViewReady(this)
                    // document-start 注入：修 viewport + 抓页面错误。
                    // dsh 的前端是桌面布局，viewport 若写成固定宽度（如 width=1280），
                    // 在手机上会按桌面视口渲染再缩放 —— 表现为只看到页面最左边一条。
                    // 这里在文档开始前就把 viewport 改成设备宽度，并在运行期持续纠正
                    // （前端可能在 hydration 后重写它）。同时把 error / unhandledrejection
                    // 收进 __dshDiag，诊断按钮直接读它 —— 不再靠猜。
                    runCatching {
                        androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                            this,
                            """
(function(){
  try {
    var applying = false;
    var fixViewport = function(){
      if (applying) return;
      applying = true;
      try {
        var m = document.querySelector('meta[name="viewport"]');
        if (!m && document.head) {
          m = document.createElement('meta');
          m.setAttribute('name', 'viewport');
          document.head.appendChild(m);
        }
        if (m) m.setAttribute('content', 'width=device-width, initial-scale=1, viewport-fit=cover');
      } catch (e) {}
      applying = false;
    };
    fixViewport();
    document.addEventListener('DOMContentLoaded', fixViewport);
    try {
      new MutationObserver(fixViewport).observe(document.documentElement, {
        attributes: true, childList: true, subtree: true
      });
    } catch (e) {}
  } catch (e) {}
  window.__dshDiag = { errors: [] };
  window.addEventListener('error', function (e) {
    try { window.__dshDiag.errors.push((e.message || 'error') + ' @' + (e.filename || '') + ':' + (e.lineno || 0)); } catch (x) {}
  }, true);
  window.addEventListener('unhandledrejection', function (e) {
    try {
      var r = e.reason;
      window.__dshDiag.errors.push('unhandledrejection: ' + (r && r.message ? r.message : String(r)));
    } catch (x) {}
  });
})();
                            """.trimIndent(),
                            setOf("*")
                        )
                    }.onFailure { android.util.Log.w("HarnessScreen", "注入失败", it) }
                }
            },
            update = { wv ->
                // about:blank / 尚未导航：补上首次加载（factory 里已经 loadUrl 过，这里是兜底）
                if (wv.url.isNullOrBlank() || wv.url == "about:blank") wv.loadUrl(url)
            }
        )

        // 右下角悬浮入口：退出 + 诊断。harness 的 Web UI 自带一整条顶栏（返回/刷新），
        // 所以 Compose 不再画顶栏，只放这两个不与它打架的圆形钮。
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(18.dp)
        ) {
            Column(horizontalAlignment = Alignment.End) {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = Color.Black.copy(alpha = 0.45f),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                    ) {
                        Text(
                            "诊断",
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 11.sp,
                            modifier = Modifier.clickable { collectDiag() }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = Color.Black.copy(alpha = 0.45f),
                ) {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack, "退出 harness 界面",
                            tint = Color.White.copy(alpha = 0.9f)
                        )
                    }
                }
            }
        }

        // 加载进度：只在没走完时显示，走完就撤（不挡页面）
        if (pageDone.not() && progress in 1..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                color = TerminalTheme.accentColor,
                trackColor = Color.Transparent,
            )
        }

        // 白屏 / 加载失败诊断面板。半透明盖在 WebView 上而不是替换它 ——
        // 万一只是 hydration 慢，用户关掉面板页面还在。
        val err = loadError
        val blank = blankScreen
        if (err != null || blank || diag != null) {
            Surface(
                color = TerminalTheme.backgroundColor.copy(alpha = 0.97f),
                modifier = Modifier.fillMaxSize(),
            ) {
                Column(
                    Modifier
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        if (err != null) "页面加载失败"
                        else if (blank) "页面加载完成，但没有渲染出内容（白屏）"
                        else "WebView 诊断现场",
                        color = TerminalTheme.onSurfaceColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(10.dp))
                    if (err != null) {
                        Text(
                            err,
                            color = TerminalTheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    if (subErrors.isNotEmpty()) {
                        Text(
                            "子资源失败 ${subErrors.size} 个（主框架是好的，坏在这些）",
                            color = TerminalTheme.onSurfaceColor,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            subErrors.take(12).joinToString("\n") { "· $it" },
                            color = TerminalTheme.onSurfaceVariant,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    val d = diag
                    if (d != null) {
                        Text(
                            d,
                            color = TerminalTheme.onSurfaceColor,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HarnessAction("重试") { reload() }
                        HarnessAction("读诊断") { collectDiag() }
                        HarnessAction("重新取鉴权") {
                            loadError = null
                            blankScreen = false
                            diag = null
                            onAuthRetry()
                        }
                        if (err == null && !blank) {
                            HarnessAction("关闭诊断") { diag = null }
                        }
                        HarnessAction("退出") { onClose() }
                    }
                }
            }
        }
    }
}

// ── 与 MoyuScreen 同风格的极小组件集（文件级 private，不与其他页冲突）──

@Composable
private fun HarnessBlock(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = TerminalTheme.surfaceColor,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
    ) {
        Column(Modifier.padding(14.dp)) { content() }
    }
}

@Composable
private fun HarnessTitle(text: String) {
    Text(text, color = TerminalTheme.onSurfaceColor, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun HarnessText(text: String, color: Color = TerminalTheme.onSurfaceVariant) {
    Text(text, color = color, fontSize = 11.5.sp)
}

@Composable
private fun HarnessDot(ok: Boolean) {
    Box(
        Modifier
            .size(7.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(
                if (ok) TerminalTheme.successColor
                else TerminalTheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
    )
}

@Composable
private fun HarnessPrimary(text: String, disabled: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(11.dp))
            .clickable(enabled = !disabled) { onClick() },
        color = if (disabled) TerminalTheme.primaryColor.copy(alpha = 0.35f) else TerminalTheme.primaryColor,
        shape = RoundedCornerShape(11.dp)
    ) {
        Text(
            text, color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
        )
    }
}

@Composable
private fun HarnessAction(text: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .clickable { onClick() },
        color = Color.White.copy(alpha = 0.06f),
        shape = RoundedCornerShape(9.dp)
    ) {
        Text(
            text, color = TerminalTheme.onSurfaceColor, fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun HarnessTap(text: String, onClick: () -> Unit) {
    Text(
        text, color = TerminalTheme.accentColor, fontSize = 11.5.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 4.dp)
    )
}
