package com.ai.assistance.quro.terminal.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.JavascriptInterface
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
import java.util.Locale
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
        // 进程可能刚刚死掉（按钮是照着 running 快照画的）。这时候打开 WebView 只会得到一块
        // 拒绝连接的白屏，还得靠用户自己回过味来 —— 先在这儿挡住，说清楚发生了什么。
        if (!harness.isRunning()) {
            refresh()
            status = "引擎未在运行，先启动服务再进入界面"
            return
        }
        busy = true
        status = ""
        scope.launch {
            // launchToken 读日志文件、ensureAuthCookie 可能真打一次 HTTP —— 都在 IO 上做，
            // 主线程只留状态赋值。原来整个函数跑在 Main dispatcher 上，冷启动时足以卡住界面。
            val token = withContext(Dispatchers.IO) { harness.launchToken() }
            val cookie = withContext(Dispatchers.IO) { harness.ensureAuthCookie() }
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
                // 构建戳：版本号是锁死的，界面上看不到它就意味着桌上装的是旧包
                Text(
                    "构建 " + DeepSeekHarness.BUILD_STAMP,
                    color = TerminalTheme.onSurfaceVariant.copy(alpha = 0.65f),
                    fontSize = 9.sp
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
 * 主帧看门狗阈值：超过这么久还没 `onPageFinished` 就认定「卡住」并自动弹诊断面板。
 *
 * 15s 而不是 3s：引擎首启要装配插件（`/plugins/??@…` 那条 combo URL 后面是一整串 chunk），
 * 冷启动慢；但也不宜更长 —— 停住的页面一个字不出，用户等 30s 和等 3s 是一样的绝望。
 */
private const val MAIN_FRAME_STALL_MS = 15_000L

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
 * 网络探针：document-start 期就开始记账，把「谁没回来」留下来。
 *
 * 白屏最难判的一种是**入口 chunk 挂在 `<head>` 的阻塞 `<script src>` 上**：主帧状态码是 200、
 * 正文也收到了，但脚本不返回 → 后面的 React 永不执行 → 整屏纯白，且 WebView 一个回调都不给。
 * `performance.getEntriesByType('resource')` 里 `responseEnd === 0` 的条目就是这种请求，
 * 桌面 Chrome 一切正常、设备上挂住，只有它能看见。
 *
 * 每 2s 一拍，最多留 40 拍（覆盖 80s 窗口），诊断按钮直接读。
 */
private const val DSH_NET_JS = """
(function(){
  try {
    if (window.__dshNet) return;
    var MAX_SNAP = 40;
    var snaps = [];
    window.__dshNet = { snaps: snaps };
    var snap = function(){
      try {
        var e = performance.getEntriesByType('resource');
        var pending = [], slow = [];
        for (var i = 0; i < e.length; i++) {
          var r = e[i];
          if (!r.name) continue;
          if (r.responseEnd === 0) pending.push(r.name);
          else if (r.duration > 3000) slow.push(r.name + ' (' + Math.round(r.duration) + 'ms)');
        }
        var nav = performance.getEntriesByType('navigation')[0];
        snaps.push({
          t: Math.round(performance.now()),
          rs: document.readyState,
          nav: nav ? Math.round(nav.duration) + 'ms' : '-',
          total: e.length,
          pending: pending,
          slow: slow
        });
        if (snaps.length > MAX_SNAP) snaps.splice(0, snaps.length - MAX_SNAP);
      } catch (x) {}
    };
    snap();
    document.addEventListener('DOMContentLoaded', snap);
    window.addEventListener('load', snap);
    setInterval(snap, 2000);
  } catch (e) {}
})();
"""

/**
 * 诊断现场：把 WebView 里真实的状态读出来，不靠猜。
 *
 * 重点是 `Promise.withResolvers` / `structuredClone` / `AbortSignal.any` / `Iterator` 四项 ——
 * 引擎的 boot-readiness 标记就挂在 `Promise.withResolvers()` 上，宿主兼容层
 * （`@dsh-android/dsh-host-web-compat`）没注入成功时它是个 TypeError，客户端 entry 会
 * **永久 await**，表现就是白屏且没有任何报错。这一项是区分「资源没到」与「 polyfill 没生效」的关键。
 *
 * 另外补三段（#379）：
 *  - `androidBridge` 在不在场、pick token 取不取到（上游 dir-pick 端点 fail-closed，缺 token 就每 500ms 一次 403）；
 *  - 网络探针快照：哪个请求 `responseEnd === 0`（挂在半空）、哪些 >3s；
 *  - `<script src>` 声明数与资源计时条数对不上 ⇒ 有脚本根本没被请求。
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
  var scripts = document.getElementsByTagName('script');
  var withSrc = 0;
  for (var i = 0; i < scripts.length; i++) if (scripts[i].src) withSrc++;
  p('script src 声明', withSrc + ' / 共 ' + scripts.length + ' 个 script');
  var d = window.__dshDiag;
  p('JS 错误数', d && d.errors ? d.errors.length : 0);
  if (d && d.errors && d.errors.length) {
    for (var i = Math.max(0, d.errors.length - 6); i < d.errors.length; i++) p('  错误', d.errors[i]);
  }
  p('androidBridge', window.androidBridge ? '在场' : '缺失（dir-pick 每 500ms 拿 403，刷新按钮也不可用）');
  if (window.androidBridge && window.androidBridge.getPickToken) {
    var tk = '';
    try { tk = String(window.androidBridge.getPickToken()); } catch (e) {}
    p('pick token', tk ? '已取到（' + tk.length + ' 位）' : '取到空串');
  }
  var n = window.__dshNet;
  if (n && n.snaps && n.snaps.length) {
    var last = n.snaps[n.snaps.length - 1];
    p('资源请求总数', last.total);
    p('未返回(挂起)', last.pending.length);
    for (var i2 = 0; i2 < last.pending.length && i2 < 8; i2++) p('  挂起', last.pending[i2]);
    p('慢请求(>3s)', last.slow.length);
    for (var i3 = 0; i3 < last.slow.length && i3 < 8; i3++) p('  慢', last.slow[i3]);
    p('导航耗时', last.nav + '，快照 ' + n.snaps.length + ' 拍');
  } else {
    p('网络探针', '未注入或无数据');
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
 * `window.androidBridge` —— 上游兼容层（`@dsh-android/dsh-host-web-compat` / `ui-responsive`）
 * 与页面共同依赖的那座桥。**不是可选装饰**：缺了它
 *  - `dir-pick` 端点 fail-closed，页面每 500ms 拿一次 403，目录选择永远转空；
 *  - 首帧主题拿不到 `getSystemDark()`，部分 ROM 的 WebView `matchMedia` 卡在浅色 → 深色下白闪；
 *  - 页面自己的「刷新」按钮直接弹「应用与页面的连接未装配」。
 *
 * 上游 `app/src/main` 的 `AndroidBridge.kt` 有 35 个方法，这里只实现**本页真能用上的最小集**，
 * 其余留空实现并返回上游约定的「不可用」形状（上游全部调用点都有守卫，缺方法不会炸）。
 * 目录选择器（`pickDirectory`） deliberately 不接：它要把 SAF 树 URI 翻成引擎能读的路径，
 * 没有壳侧存储映射就只能是半成品，而它压根不在白屏路径上。
 */
private class DshBridge(
    private val context: Context,
    private val onReload: () -> Unit,
) {
    /** dir-pick 端点的鉴权头来源。与引擎进程环境里的 `DSH_PICK_TOKEN` 是同一份（轮换后旧值立即失效）。 */
    @JavascriptInterface
    fun getPickToken(): String = DeepSeekHarness.get(context).currentPickToken().orEmpty()

    /**
     * 首帧主题：兼容层在 head 解析期同步读它，拿不到才回落 `matchMedia`。
     *
     * **故意恒为深色，不读系统夜间模式**：harness 页面整块嵌在终端主题里（`TerminalTheme`
     * 的底色 `#0B0E14` 是写死的深色，和系统主题无关）。系统是浅色时若照实返回 false，
     * 兼容层会把首帧画布刷成 `#f1f3f1` —— 页面还没装配完的那一两秒，用户在深色壳里看到
     * 一块**白屏**，然后才被上游主题接管。那正是要消灭的东西，所以这里报宿主真实的深色。
     */
    @JavascriptInterface
    fun getSystemDark(): Boolean = true

    /** 页面只认 zh / en / system 三态，其余语言一律交给 system（跟随 document.lang）。 */
    @JavascriptInterface
    fun getUiLanguage(): String = when (Locale.getDefault().language) {
        "zh" -> "zh"
        "en" -> "en"
        else -> "system"
    }

    /** 页面「刷新界面」按钮：整页重载（含重新走 token 交换）。 */
    @JavascriptInterface
    fun reloadWebUI() {
        onReload()
    }

    /** 复制回退通道：上游多处 `navigator.clipboard.writeText` 失败时走这里。 */
    @JavascriptInterface
    fun copyText(text: String?): Boolean {
        val t = text ?: return false
        if (t.isEmpty()) return false
        return runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return false
            cm.setPrimaryClip(ClipData.newPlainText("dsh", t))
            true
        }.getOrDefault(false)
    }

    /** 站外链接交给系统浏览器。 */
    @JavascriptInterface
    fun openExternalLink(url: String?) {
        val u = url?.trim().orEmpty()
        if (u.isEmpty()) return
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(u)).apply {
                    if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }

    /** 沉浸式开关：本页常驻全屏 WebView，没有可切的状态栏，恒 true、写入为空操作。 */
    @JavascriptInterface
    fun getImmersiveMode(): Boolean = true

    @JavascriptInterface
    fun setImmersiveMode(enabled: Boolean) {
        // 上游 GeneralSettings 的开关有 localStorage 镜像兜底，这里不写也不会丢状态。
    }

    /** 控制台入口宿主未提供 —— 记日志即可，上游对缺失有明确的人话提示。 */
    @JavascriptInterface
    fun openConsole() {
        android.util.Log.i("HarnessScreen", "页面请求打开控制台（宿主未提供该入口）")
    }

    /** 打开路径：宿主没有 SAF→路径 的映射，按上游约定的不可用形状返回。 */
    @JavascriptInterface
    fun openPathChooser(path: String?, mode: String?): String =
        """{"ok":false,"reason":"host-unsupported"}"""

    @JavascriptInterface
    fun openNativePath(path: String?): Boolean = false

    /** 目录选择：见类注释，宿主不实现选择器；轮询侧已 fail-closed，不会有人调到这里。 */
    @JavascriptInterface
    fun pickDirectory(requestId: String?) {
        android.util.Log.w("HarnessScreen", "页面请求选择目录 requestId=$requestId（宿主未提供）")
    }

    @JavascriptInterface
    fun settingsPath(): String = ""

    @JavascriptInterface
    fun incomingWorkspacePath(): String = ""
}

/**
 * 只放行 harness 自己的源（scheme://host:port 完全一致）。
 *
 * 桥上带着 pick token，所以**不能**再像原来那样见 `127.0.0.1` 就放行 —— 那等于把本机任意
 * 端口上的页面都请进 WebView，别的本地进程起个服务就能读到 token。303 回跳与站内跳转
 * 都落在同一个源上，收紧后照常通行。
 */
private fun sameOrigin(a: String, b: String): Boolean {
    fun origin(s: String): String {
        val i = s.indexOf("://")
        if (i < 0) return s
        val rest = s.substring(i + 3)
        val j = rest.indexOf('/')
        val host = if (j < 0) rest else rest.substring(0, j)
        return if (host.startsWith("localhost:")) "127.0.0.1:" + host.substring(10) else host
    }
    if (a.isBlank() || b.isBlank()) return false
    return origin(a) == origin(b)
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
 * **不会**报主框架。能观察到它的有五处，这里全都接上了：
 *  1. `onReceivedError` 里 `isForMainFrame == false` 的那些（子资源 404 / 失败）——
 *     原来的实现把它们整段丢掉，所以白屏时界面一个字都不显示；
 *  2. `onReceivedHttpError` 的子资源分支（>=400，与 ① 互补：网络层错误 vs HTTP 层错误）；
 *  3. document-start 注入的 `window.__dshDiag.errors`（JS 期异常 / unhandledrejection）；
 *  4. document-start 注入的 `window.__dshNet` 网络探针（哪个请求 `responseEnd === 0` 挂半空）；
 *  5. `onPageFinished` 之后探一次 `#root` 的子节点数与正文长度 —— 两个都是 0 就是白屏。
 * 任一中招，或主帧 15s 还没走完（进度条停住、页面不出字），就把诊断面板顶上来
 * （半透明盖在 WebView 上，不销毁页面），右下角常驻一个「诊断」悬浮钮，随时读现场。
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
    /** 诊断面板是否被强制顶出来（子资源 4xx/5xx、主帧停滞时自动开，用户也可主动读诊断）。 */
    var panelOpen by remember { mutableStateOf(false) }
    /** 主帧超过 15s 还没走完：进度条停住、页面不出字，白屏的一种。 */
    var stalledMain by remember { mutableStateOf(false) }
    /** 最近一次主帧导航的起点，主帧看门狗按它重启计时。 */
    var navStartedAt by remember { mutableStateOf(0L) }
    /** 已经 loadUrl 过的那个地址。url 变了（重新取鉴权换了 token）才重载 ——
     *  否则 AndroidView 的 update 只在 url 空白时补载，401 重试换了新 token 也不会重载，
     *  于是拿着旧 token 的页面反复 401，卡死成白屏。 */
    var loadedUrl by remember { mutableStateOf("") }

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
        stalledMain = false
        panelOpen = false
        subErrors = emptyList()
        diag = null
        pageDone = false
        navStartedAt = System.currentTimeMillis()
        loadedUrl = url
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

    /** 自动把诊断面板顶出来，并顺手读一份现场 —— 用户点一下就能看到，不用先猜要点哪。 */
    fun autoOpenPanel(reason: String) {
        stalledMain = true
        panelOpen = true
        loadError = null
        blankScreen = false
        collectDiag()
        android.util.Log.w("HarnessScreen", "白屏自证：$reason")
    }

    // 主帧看门狗：15s 还没 onPageFinished，说明卡在某个子资源上（最典型的是 <head> 里的
    // 阻塞 <script src> 不返回）。进度条早在 100% 前就停了，页面一个字不出 —— 这时不自动弹，
    // 用户看到的就只有一块白，没有任何线索。
    LaunchedEffect(navStartedAt) {
        if (navStartedAt == 0L) return@LaunchedEffect
        delay(MAIN_FRAME_STALL_MS)
        if (!pageDone) {
            autoOpenPanel("主帧 ${MAIN_FRAME_STALL_MS / 1000}s 未加载完成（进度 $progress%）")
        }
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
                            // 引擎自己的页面（含会话内跳转、token 交换后的 303 回跳）照常加载。
                            // 判据收紧成「同源」：桥上带着 pick token，不能再像原来那样见
                            // 127.0.0.1 就放行 —— 那等于让本机任意端口的页面都能读 token。
                            if (sameOrigin(u, url)) return false
                            // 站外链接交给系统，不在引擎页里打开
                            return runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, request.url)
                                )
                            }.isSuccess
                        }

                        override fun onPageStarted(
                            view: WebView?,
                            url: String?,
                            favicon: android.graphics.Bitmap?,
                        ) {
                            // 新导航一开始就复位。**不 override 这个方法**是老实现的坑：
                            // pageDone 只有 reload() 会复位，于是 onPageFinished 之后再发生导航
                            // （303 回跳、站内跳转）时它一直是 true —— 看门狗与白屏探针全部失效。
                            pageDone = false
                            stalledMain = false
                            panelOpen = false
                            blankScreen = false
                            subErrors = emptyList()
                            navStartedAt = System.currentTimeMillis()
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            pageDone = true
                        }

                        override fun onReceivedHttpError(
                            view: WebView,
                            request: WebResourceRequest,
                            errorResponse: WebResourceResponse,
                        ) {
                            val code = errorResponse.statusCode
                            // 上游把每个 /api 都挡在签名 cookie 后面（401）；
                            // 主框架被拒说明 cookie 过期或没取到 → 重新走一遍鉴权再加载
                            if (code == 401 && request.isForMainFrame) {
                                onAuthRetry()
                                return
                            }
                            // 子资源的 HTTP 层错误：与 onReceivedError（网络层失败）互补。
                            // 主帧 200 而整屏全白，八成是 bootstrap / 某个 chunk 在这里 404/500 了。
                            if (!request.isForMainFrame && code >= 400) {
                                val line = "HTTP $code ← ${request.url}"
                                subErrors = subErrors + line
                                android.util.Log.w("HarnessScreen", "子资源 HTTP 错误: $line")
                                val u = request.url.toString()
                                // 关键路径（插件装配 / 静态资源）失败 → 直接弹面板，别等用户自己发现
                                if (u.contains("/plugins/") || u.contains("/assets/")) {
                                    autoOpenPanel("关键子资源失败：$line")
                                }
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
                    // 桥必须在 loadUrl 之前装好：页面 head 解析期就同步调 getPickToken()/getSystemDark()
                    addJavascriptInterface(DshBridge(ctx) { reload() }, "androidBridge")
                    loadedUrl = url
                    navStartedAt = System.currentTimeMillis()
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
  // ── 首帧画布（2026-10-24 实测后补）───────────────────────────────────────
  // 上游 index.html 的 boot <style> 把画布钉成浅色：
  //   :root{color-scheme:light}body{background-color:#fff}
  //   @media(prefers-color-scheme:dark){:root{color-scheme:dark}body{background-color:#151517}}
  // 而 compat 的 applyCanvasTheme() 只写 documentElement.style.background —— body 自己有
  // 背景色时那一条被完全盖住，F1 那条「首帧即主题色」的修复在这里**不生效**。
  // 宿主界面恒为深色终端主题（TerminalTheme.backgroundColor #0B0E14），浅色设备上
  // 「11MB 引导包还在下」的那几秒就是一块纯白 —— 用户看到的就是白屏。
  // 这里在 document-start（早于一切解析）把画布钉成终端底色，
  // #root 长出子节点（React 挂载）即刻松手，主题交还页面自己。
  (function(){
    try {
      if (window.__dshHostCanvas) return;
      window.__dshHostCanvas = true;
      var BOOT = '#0B0E14';
      var st = document.createElement('style');
      st.textContent = 'html,body{background-color:' + BOOT + ' !important;color-scheme:dark !important;}';
      (document.head || document.documentElement).appendChild(st);
      var released = false;
      var release = function(){
        if (released) return;
        var r = document.getElementById('root');
        if (r && r.childElementCount > 0) {
          released = true;
          if (st.parentNode) st.parentNode.removeChild(st);
          return true;
        }
        return false;
      };
      document.addEventListener('DOMContentLoaded', release);
      if (window.MutationObserver) {
        new MutationObserver(release).observe(document.documentElement, {childList:true, subtree:true});
      }
      // 兜底轮询：个别 ROM 上 MutationObserver 回调被节流，别让这条样式和页面打架
      var tick = setInterval(function(){ if (release()) clearInterval(tick); }, 500);
      setTimeout(function(){ clearInterval(tick); }, 20000);
    } catch (e) {}
  })();
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
""" + DSH_NET_JS + """
                            """.trimIndent(),
                            setOf("*")
                        )
                    }.onFailure { android.util.Log.w("HarnessScreen", "注入失败", it) }
                }
            },
            update = { wv ->
                // url 变了就重载。401 重试会重新取 token 生成新 url —— 老实现只在 url 空白时
                // 补载，于是新 url 永远不加载，页面停在旧 token 的反复 401 上，白屏到死。
                // blank 判断保留：factory 重建（WebView 被销毁过）时兜底。
                if (wv.url.isNullOrBlank() || wv.url == "about:blank" || loadedUrl != url) {
                    loadedUrl = url
                    navStartedAt = System.currentTimeMillis()
                    wv.loadUrl(url)
                }
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
        val stalled = stalledMain
        if (err != null || blank || stalled || panelOpen || diag != null) {
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
                        when {
                            err != null -> "页面加载失败"
                            stalled -> "主帧加载停滞（${MAIN_FRAME_STALL_MS / 1000}s 未完成，进度 $progress%）"
                            blank -> "页面加载完成，但没有渲染出内容（白屏）"
                            panelOpen -> "关键资源加载失败（主框架是好的，坏在这些）"
                            else -> "WebView 诊断现场"
                        },
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
                            stalledMain = false
                            panelOpen = false
                            diag = null
                            onAuthRetry()
                        }
                        if (err == null && !blank && !stalled) {
                            HarnessAction("关闭诊断") { panelOpen = false; diag = null }
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
