package com.ai.assistance.quro.terminal.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.ConsoleMessage
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

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
 * 上游 boot 看门狗的 console 契约前缀（#380）。
 *
 * 来自 profile 注入的 `@dsh-android/dsh-host-web-compat` 0.14.x（冻结接口，跨仓按**位置**
 * 解析字段，改前缀必须同批改上游）。它把 40s 卡死自诊断打成 `console.error`：
 *   [dsh-boot-stall]  dsh-boot-diag source=page-stall …      （40s 未启动，带 pendingBundles/badBundles/engineHttp）
 *   [dsh-boot-ready]  dsh-boot-diag source=page-ready …      （页面真渲染出来了）
 *   [dsh-boot-failed] dsh-boot-diag source=page-plugin-fail …（客户端插件装配失败，带 failedIds）
 * 另有静态回退支线的 `source=page-static-fallback` / `source=page-error`，同走 stall 前缀。
 *
 * 壳侧此前**一条都不接**：页内看门狗现场采齐了（manifest 条目、/plugins/ bundle 挂起与 4xx、
 * 引擎 HTTP 可达、rendered/pendingBoot）却只在 console 里喊一声就没了。本页 onConsoleMessage
 * 按前缀收进来：ready 到 ⇒ 停 15s 看门狗；stall/failed 到 ⇒ 直接顶诊断面板。
 */
private const val BOOT_STALL_PREFIX = "[dsh-boot-stall]"
private const val BOOT_READY_PREFIX = "[dsh-boot-ready]"
private const val BOOT_FAILED_PREFIX = "[dsh-boot-failed]"

/**
 * 内核探针结论的 title 回传通道前缀（#381）。
 *
 * 为什么需要它：部分 ROM 会把**首文档**的 `evaluateJavascript` 回包丢掉（loadUrl 之后立刻发
 * 的那一次最容易被丢），于是「内核过旧」这个最致命的根因在壳侧反而看不到。document-start
 * 注入的探针把同一份结论同时写进 `document.title` —— title 回调丢不掉。两条通道互为备份。
 */
private const val KERNEL_TITLE_PREFIX = "DSH_KERNEL:"

/**
 * WebView 内核能力预检（#380）：在页面加载**之前**问一遍 JS 引擎会什么。
 *
 * 为什么要它：白屏最难向用户解释的一种是「设备 WebView 内核太老」。这些是**语法级**
 * 需求 —— compat 的 polyfill 只补运行时 API（Promise.withResolvers 等），补不了语法：
 * 缺任何一项，入口 chunk 在解析期整体不执行，React 永不挂载，白屏且几乎没有报错。
 *
 * 出厂口径（B20261024-2115 起，对齐参考实现 kelai141/dsh-mobile-apk）：内置 dist 已在
 * 构建期按上游 `check-browser-syntax-floor` 门禁降到 chrome87（esbuild 0.25.12 双 arm
 * 差分归零 + TS AST 零 static{}）—— `class static{}`（94+）与可选链（80+）已被降级
 * 消除；esbuild 的 chrome87 口径**不降**私有字段（84+）/ 逻辑赋值（85+）/ 空值合并
 * （80+），这三项就是当前的硬门槛。红条只在 `S:` 缺失时出现；`A:` 仅说明内核代次。
 *
 * 返回 `CHROME=<UA 大版本>|ALL-OK` 或 `CHROME=<版本>|S:项…|A:项…`：
 *  - `S:` = 语法级缺失（**致命**，polyfill 救不了，只能升级 WebView 或换 transpile 包）；
 *  - `A:` = 运行时 API 缺失或已降级语法缺失（不致命，仅说明内核代次）。
 * ⚠ 历史坑 1（#380 已修）：v 标志原来用 `new RegExp('[[]','v')` 探测 —— 该图案在 v
 *    模式下**同样非法**，现代引擎也抛 SyntaxError ⇒ 红条对每台设备误报。v 标志只验旗标
 *    接受性；且出厂 chunk 实测零处 v 模式正则，它永远不致命。
 * ⚠ 历史坑 2（B20261024-2115 已修）：static{} 原列为 `S:` 致命项 —— 那是**未降级**
 *    dist 的口径（入口 chunk 实测 2 处，9:1811 / 13:9117）。内置 dist 降级后它已不致命，
 *    改列 `A:`；只有换回未降级包时才回到致命。
 *
 * #381：探针体抽成 [DSH_KERNEL_PROBE] 一个常量，两个消费面共用，杜绝两份漂移：
 *  - [DSH_KERNEL_JS]：loadUrl 之后 evaluateJavascript 现问（部分 ROM 丢首文档回包）；
 *  - [DSH_KERNEL_TITLE_JS]：document-start 注入，结论写 `document.title`（回调丢不掉）。
 */
private const val DSH_KERNEL_PROBE = """
  var miss = [];
  // ── 语法级硬门槛（出厂 chrome87 口径未降级的三项；缺一项入口 chunk 解析期整体不执行）──
  try { new Function('class X{#f=1;g(){return this.#f}}'); } catch (e) { miss.push('S:私有字段 #f（需 84+）'); }
  try { new Function('var a;a??=1'); } catch (e) { miss.push('S:??= 逻辑赋值（需 85+）'); }
  try { new Function('var a;a??1'); } catch (e) { miss.push('S:?? 空值合并（需 80+）'); }
  // ── 已被出厂降级消除的语法（缺失仅说明内核代次，不致命）──
  try { new Function('class X{static{}}'); } catch (e) { miss.push('A:class static{} 块（需 94+；出厂 dist 已降级，换未降级包才致命）'); }
  // ── 运行时 API（compat polyfill 可兜底，缺失不致命，仅说明内核代次）──
  if (typeof [].at !== 'function') miss.push('A:Array.prototype.at（需 92+，可 polyfill）');
  if (typeof Promise.withResolvers !== 'function') miss.push('A:Promise.withResolvers');
  if (typeof structuredClone !== 'function') miss.push('A:structuredClone');
  if (typeof Array.fromAsync !== 'function') miss.push('A:Array.fromAsync');
  if (typeof AbortSignal === 'undefined' || typeof AbortSignal.any !== 'function') miss.push('A:AbortSignal.any');
  if (typeof Iterator === 'undefined') miss.push('A:Iterator');
  // v 标志：只验旗标本身是否被接受（图案用 'x'，不用会在 v 模式下也非法的 '[[]'）。
  try { new RegExp('x', 'v'); } catch (e) { miss.push('A:RegExp v 标志（需 110+，chunk 实测未用到）'); }
  // 内核代次直读：UA 里的 Chrome 大版本即 WebView 内核代次（不经过 eval，CSP 也读得到）
  var m = (navigator.userAgent || '').match(/Chrome\/(\d+)/);
  var rep = 'CHROME=' + (m ? m[1] : '?') + '|' + (miss.length ? miss.join('|') : 'ALL-OK');
"""

/** evaluateJavascript 版内核探针：返回结论串（回包被 ROM 丢掉时由 title 通道兜底）。 */
private const val DSH_KERNEL_JS = """
(function(){
$DSH_KERNEL_PROBE
  return rep;
})()
"""

/**
 * document-start 版内核探针：结论同时写 `window.__dshKernel` 与 `document.title`。
 *
 * title 通道的价值：`evaluateJavascript` 在**首文档**上的回包会被部分 ROM 丢弃，
 * 而 onReceivedTitle 丢不掉 —— 内核过旧这种「白屏且无报错」的根因必须有一条稳的回传路径。
 */
private const val DSH_KERNEL_TITLE_JS = """
(function(){
  try {
$DSH_KERNEL_PROBE
    window.__dshKernel = rep;
    try { document.title = '$KERNEL_TITLE_PREFIX' + rep; } catch (e2) {}
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
 *
 * #380 再补**装配链判据**（按依赖顺序，断点即根因）：document-start 注入 →
 * `__ModuleLoader__`（head 首个内联）→ `__DSH_BOOT__` manifest → 看门狗脚本 `__dshBootDiag` →
 * 静态回退条 `__dshStaticFallback` → body 末尾 `__DSH_BOOT_READY__` 承诺；
 * 外加三个契约行全局镜像（console 投递万一丢，页面侧原文仍在）。
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
  // ── #380：页面侧装配链判据（按依赖顺序，断在哪一环一眼可见）──────────────────
  // 顺序：document-start 注入 → head 首个内联(__ModuleLoader__) → 阻塞 client.js →
  //       manifest(__DSH_BOOT__) → compat 注入脚本(看门狗/polyfill/静态回退) → body 末尾 boot-ready 承诺。
  // 任何一环缺失，其后的环节全都不可能出现 —— 断点位置即根因位置。
  p('document-start 注入', typeof window.__dshHostCanvas !== 'undefined' ? ('已生效（钉色位置 ' + (window.__dshHostCanvasPos || '?') + '）') : '未生效（addDocumentStartJavaScript 没跑：画布钉色/错误收集/网络探针/内核探针全丢）');
  p('内核探针(title)', typeof window.__dshKernel !== 'undefined' ? String(window.__dshKernel) : '未执行（document-start 注入没跑，或 title 被页面提前覆盖）');
  var ml = window.__ModuleLoader__;
  if (ml) {
    var q = ml.pendingQueue;
    p('__ModuleLoader__', 'mode=' + (ml.mode || '?') + '，待决队列=' + (q && q.length !== undefined ? q.length : '?'));
  } else {
    p('__ModuleLoader__', '缺失（head 第一个内联脚本没跑）');
  }
  var b = window.__DSH_BOOT__;
  p('manifest(__DSH_BOOT__)', b ? 'rev=' + (b.rev || '?') + '，条目=' + ((b.entries || []).length) : '缺失（head 内联 manifest 没跑）');
  p('看门狗脚本(__dshBootDiag)', typeof window.__dshBootDiag !== 'undefined' ? '已执行（40s 卡死诊断在线）' : '未执行（compat 注入脚本没跑，console 契约不会来）');
  p('静态回退条(__dshStaticFallback)', typeof window.__dshStaticFallback !== 'undefined' ? '在场' : '缺失');
  p('boot-ready 承诺(__DSH_BOOT_READY__)', typeof window.__DSH_BOOT_READY__ !== 'undefined' ? '在场' : '缺失（body 末尾解析语句没跑）');
  // 契约行的页面侧镜像：console 投递万一丢，这三个全局仍留着看门狗的原文
  if (window.__dshBootReady) p('契约就绪行', String(window.__dshBootReady).slice(0, 240));
  if (window.__dshBootFail) p('契约失败行', String(window.__dshBootFail).slice(0, 300));
  if (window.__dshBootStallReport && window.__dshBootStallReport.line) p('契约卡死行', String(window.__dshBootStallReport.line).slice(0, 300));
  p('Promise.withResolvers', typeof Promise.withResolvers);
  p('structuredClone', typeof structuredClone);
  p('AbortSignal.any', (typeof AbortSignal !== 'undefined') ? typeof AbortSignal.any : 'AbortSignal 缺失');
  // #390：self 而不是 globalThis —— globalThis 是 Chrome 71+ 的 API，地板内核（62/63）上
  // 读它直接 ReferenceError，而这里不在 try 里：整个诊断脚本当场中断，按「诊断」零输出。
  // self 是 window 的同义词，所有代次都有。口径与 #389 host-webserver 注入行模板同款修正。
  p('Iterator', typeof self.Iterator);
  p('Cookie', document.cookie ? document.cookie.replace(/=[^;]{12,}/g, '=<已脱敏>') : '(空)');
  return L.join('\n');
})()
"""

/**
 * `evaluateJavascript` 的挂起包装：回包即 JSON 字符串原文（可能为 `null` 字面量）。
 *
 * 存在的理由：看门狗要在协程里**同步等一次页面答复**（探 `#root`、发 JS 心跳），而
 * evaluateJavascript 本身是异步回调式。异常（WebView 已销毁等）与超时都由调用方兜。
 */
private suspend fun WebView.evalJs(code: String): String? = suspendCancellableCoroutine { cont ->
    val delivered = try {
        evaluateJavascript(code) { raw ->
            if (cont.isActive) cont.resume(raw)
        }
        true
    } catch (_: Throwable) {
        false
    }
    if (!delivered && cont.isActive) cont.resume(null)
}

/** 探一次 `#root` 子节点数；拿不到（WebView 没了 / JS 抛了）回 -1。 */
private suspend fun probeRootKids(wv: WebView): Int {
    val raw = withTimeoutOrNull(3_000) { wv.evalJs(DSH_BLANK_JS) } ?: return -1
    return unescapeJsString(raw).substringBefore('|').trim().toIntOrNull() ?: -1
}

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

/** 设备语言标签（按偏好序，供 dsh 自己的 locale 探测器用）。 */
private fun systemLanguages(context: Context): List<String> {
    val locales = try {
        context.applicationContext.resources.configuration.locales
    } catch (_: Exception) {
        null
    }
    val tags = locales?.let { list -> (0 until list.size()).map { list[it].toLanguageTag() } }.orEmpty()
    return tags.filter { it.isNotBlank() }.ifEmpty { listOf(Locale.getDefault().toLanguageTag()) }
}

/**
 * 当前模式最终会显示的语言（`zh` / `en`）。
 *
 * 移植自参考实现 `AppLanguage.expectedLocale`：显式选择直接返回；跟随系统时按 dsh 自己的
 * 解析口径 —— 第一个命中已发布语言 id 的设备标签获胜（精确先于主子标签），都不中回落 `en`。
 */
private fun expectedLocale(mode: String, languages: List<String>): String {
    if (mode == DshUiLanguage.MODE_ZH || mode == DshUiLanguage.MODE_EN) return mode
    for (tag in languages.filter { it.isNotBlank() }.distinct()) {
        val requested = tag.lowercase(Locale.ROOT)
        if (requested == DshUiLanguage.MODE_ZH || requested == DshUiLanguage.MODE_EN) return requested
        val primary = requested.substringBefore('-')
        if (primary == DshUiLanguage.MODE_ZH || primary == DshUiLanguage.MODE_EN) return primary
    }
    return DshUiLanguage.MODE_EN
}

/**
 * document-start 注入的 locale 引导脚本（移植自参考实现 `AppLanguage.bootstrapScript`）。
 *
 * dsh 只通过 WebView 里的 `__DSH_LOCALE__` 读语言偏好；没有它页面退回浏览器语言，
 * 设置页会显示一个壳侧从未应用过的语言。桥在每次页面加载时读壳侧偏好，所以切换后
 * 重载一次即可收敛。
 *
 * #390：挂点是 `self.__DSH_LOCALE__` 而不是 `globalThis.__DSH_LOCALE__` —— globalThis 是
 * Chrome 71+ 的 API，地板内核（62/63）上读它 ReferenceError，本脚本又整段包在 try 里：
 * 异常被吞、`__DSH_LOCALE__` 永不挂上，旧设备上语言引导静默失效（页面退浏览器语言）。
 * self 是 window 同义词，所有代次可用；与 #389 host-webserver 注入行模板同款修正。
 *
 * `onChange` 是唯一的「引擎 → 壳」通道，而 dsh 在页面加载期间会自行发布（一次是它激活的
 * locale，一次是持久化设置域把值推进运行时时）—— 那些自动发布**不带用户意图**，只忽略
 * 第一条会把剩下的全写回，那正是「跟随系统」被钉死成单一语言的原因（参考实现 issue #487）。
 * 因此写回需要页面上一次真实交互（click / keydown / touchend 武装），且必须改变壳侧当前
 * 显示的语言；「当前显示的语言」在调用时从桥里读，而不是在这里烘死 —— 本脚本每个 Activity
 * 只装一次，而模式在它底下每次切换都在变。
 *
 * @param mode 壳侧存储的偏好（`system` / `zh` / `en`）；桥读不到时的兜底答案。
 * @param languages 设备语言标签（按偏好序）。
 */
private fun dshLocaleBootstrapScript(mode: String, languages: List<String>): String {
    val preference = if (mode == DshUiLanguage.MODE_SYSTEM) "null" else DshAndroidBridge.jsQuote(mode)
    val ordered = languages.filter { it.isNotBlank() }.distinct()
    val tags = (ordered.ifEmpty { listOf(DshUiLanguage.MODE_ZH) }).joinToString(",") {
        DshAndroidBridge.jsQuote(it)
    }
    val baked = DshAndroidBridge.jsQuote(expectedLocale(mode, languages))
    return "(function(){try{" +
        "var tags=[$tags];var baked=$baked;var armed=false;" +
        "function arm(){armed=true;setTimeout(function(){armed=false},0)}" +
        "function shown(){" +
        "var mode=null;" +
        "try{var b=window.androidBridge;" +
        "if(b&&typeof b.getUiLanguage==='function')mode=String(b.getUiLanguage())}catch(e){}" +
        "if(mode==='zh'||mode==='en')return mode;" +
        "if(mode==='system'){for(var i=0;i<tags.length;i++){" +
        "var t=String(tags[i]).toLowerCase();" +
        "if(t==='zh'||t==='en')return t;" +
        "var p=t.split('-')[0];if(p==='zh'||p==='en')return p}" +
        "return 'en'}" +
        "return baked}" +
        "try{document.addEventListener('click',arm,true);" +
        "document.addEventListener('keydown',arm,true);" +
        "document.addEventListener('touchend',arm,true)}catch(e){}" +
        "self.__DSH_LOCALE__={read:function(){return Promise.resolve({languages:tags,preference:$preference})}," +
        "onChange:function(locale){if(!armed)return;armed=false;" +
        "var next=String(locale);" +
        // 桥只认三档；语言包 id 不在这里静默降级成「跟随系统」。
        "if(next!=='zh'&&next!=='en')return;" +
        "if(next===shown())return;" +
        "try{var b=window.androidBridge;" +
        "if(b&&typeof b.setUiLanguage==='function')b.setUiLanguage(next)" +
        "}catch(e){}}};" +
        "}catch(e){}})();"
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
 * **不会**报主框架。能观察到它的有七处，这里全都接上了：
 *  1. `onReceivedError` 里 `isForMainFrame == false` 的那些（子资源 404 / 失败）——
 *     原来的实现把它们整段丢掉，所以白屏时界面一个字都不显示；
 *  2. `onReceivedHttpError` 的子资源分支（>=400，与 ① 互补：网络层错误 vs HTTP 层错误）；
 *  3. document-start 注入的 `window.__dshDiag.errors`（JS 期异常 / unhandledrejection）；
 *  4. document-start 注入的 `window.__dshNet` 网络探针（哪个请求 `responseEnd === 0` 挂半空）；
 *  5. `onPageFinished` 之后探一次 `#root` 的子节点数与正文长度 —— 两个都是 0 就是白屏；
 *  6. （#380）`onConsoleMessage` 接上游 boot 看门狗的 console 契约：`[dsh-boot-stall]`
 *     / `[dsh-boot-ready]` / `[dsh-boot-failed]` 三条前缀，40s 卡死现场（挂起 bundle、
 *     4xx bundle、引擎 HTTP、rendered/pendingBoot）从此不再丢失；ready 到达即停 15s 误报；
 *  7. （#380）加载前的内核能力预检（`DSH_KERNEL_JS`）：缺私有字段（84+）/ 逻辑赋值（85+）
 *     这类**语法**能力 ⇒ 入口 chunk 解析期整体不执行，页顶直接挂警示条，把白屏翻译成
 *     「升级 WebView」。（B20261024-2115 起内置 dist 已在构建期降到 chrome87，`static{}`
 *     不再致命；口径与历史坑见 DSH_KERNEL_JS 注释。）
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
    /** 上游 boot 看门狗的 console 契约行（#380，见 BOOT_STALL_PREFIX 等处注释）。 */
    var bootLines by remember { mutableStateOf<List<String>>(emptyList()) }
    /** [dsh-boot-ready] 到达 ⇒ 页面自己报「渲染出来了」，15s 主帧看门狗必须停（不再误报卡死）。 */
    var bootReady by remember { mutableStateOf(false) }
    /** 契约驱动的告因：stall/failed 行到达时自动顶面板，标题直接用它。 */
    var bootAlert by remember { mutableStateOf<String?>(null) }
    /** 内核能力预检（DSH_KERNEL_JS / DSH_KERNEL_TITLE_JS 双通道）：null=未测，`OK`=全过，`BAD|S:…|A:…`=缺能力。 */
    var kernelReport by remember { mutableStateOf<String?>(null) }
    /** 主帧停滞时的**自动**重载次数（本次导航内）：自愈要有界，否则坏页面会被重载成死循环。 */
    var autoReloads by remember { mutableIntStateOf(0) }
    /** 首帧是否已提交（onPageCommitVisible）：渲染进程失活与「慢启动」的区分判据之一。 */
    var frameCommitted by remember { mutableStateOf(false) }
    /** WebView 重建纪元：渲染进程消失（onRenderProcessGone）时 +1，让 AndroidView 重新走 factory。 */
    var webViewEpoch by remember { mutableIntStateOf(0) }
    /** 页面协程作用域（引擎重启等长动作；DisposableEffect 销毁时随组合一起取消）。 */
    val scope2 = rememberCoroutineScope()

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

    /**
     * 重载页面。[auto] = 看门狗自动发起的自愈重载（不重置 [autoReloads] 计数，
     * 否则计数永远到不了上限、自愈变死循环）；用户/页面刷新按钮发起的重载重置计数。
     */
    fun reload(auto: Boolean = false) {
        loadError = null
        blankScreen = false
        stalledMain = false
        bootAlert = null
        panelOpen = false
        subErrors = emptyList()
        diag = null
        pageDone = false
        frameCommitted = false
        bootLines = emptyList()
        bootReady = false
        if (!auto) autoReloads = 0
        navStartedAt = System.currentTimeMillis()
        loadedUrl = url
        webView?.loadUrl(url)
    }

    /** 页面「刷新界面」按钮与诊断面板「重启引擎」共用的引擎重启（停止旧进程 → 重新拉起 → 重载）。 */
    fun restartEngine(): Boolean {
        val h = DeepSeekHarness.get(context)
        if (!h.isRunning() && autoReloads >= 2) return false
        scope2.launch {
            withContext(Dispatchers.IO) { h.stop() }
            val r = withContext(Dispatchers.IO) { h.start() }
            r.onSuccess { reload(auto = true) }
                .onFailure {
                    loadError = "引擎重启失败：${it.message}"
                    panelOpen = true
                }
        }
        return true
    }

    fun collectDiag() {
        val wv = webView
        if (wv == null) {
            diag = "WebView 尚未创建"
            return
        }
        diag = "读取中…"
        wv.evaluateJavascript(DSH_DIAG_JS) { raw ->
            val body = unescapeJsString(raw.orEmpty())
            diag = buildString {
                append(body)
                // #381：首帧提交状态（onPageCommitVisible）。「慢启动」与「死页面」的
                // 区分判据 —— 帧都没画出来过，与加载完只是 hydration 慢是两种病。
                append("\n\n── 首帧提交 ──\n")
                append(if (frameCommitted) "已提交（页面画出来过至少一帧）" else "未提交（渲染进程未画出首帧）")
                append("\n自动重载次数: ").append(autoReloads).append(" / 2")
                // #380：上游看门狗契约行紧跟现场之后 —— 它带着 pendingBundles/badBundles/
                // rendered/pendingBoot，是「资源没到」与「入口没执行」的分水岭。
                if (bootLines.isNotEmpty()) {
                    append("\n\n── 上游 boot 契约（看门狗 console，")
                    append(bootLines.size)
                    append(" 条） ──\n")
                    append(bootLines.joinToString("\n"))
                }
                val kr = kernelReport
                if (kr != null) {
                    append("\n\n── 内核能力预检 ──\n")
                    append("WebView 内核 Chrome/").append(kr.removePrefix("CHROME=").substringBefore('|')).append('\n')
                    if (kr.endsWith("ALL-OK")) {
                        append("语法与运行时能力全部通过（含 class static{} / 私有字段 / ??=）")
                    } else {
                        append(kr.removePrefix("CHROME=").split('|').joinToString("\n") { "· $it" })
                    }
                }
                // #381：引擎进程状态与日志尾部。白屏最难判的一种是引擎自己退了（23:26 实锤
                // code=0）：页面请求挂在半空，WebView 一个回调都不给，只有日志尾部能一眼定案。
                val harness = DeepSeekHarness.get(context)
                append("\n\n── 引擎进程 ──\n")
                append("存活: ").append(
                    if (harness.isRunning()) "是（127.0.0.1:${harness.activePort()}）" else "否（已退出）",
                )
                val engineLogs = runCatching { harness.logs() }.getOrDefault("")
                if (engineLogs.isNotBlank()) {
                    val lines = engineLogs.lines()
                    append("\n── 引擎日志（尾部 ${minOf(lines.size, 40)} / ${lines.size} 行） ──\n")
                    append(lines.takeLast(40).joinToString("\n"))
                }
            }
        }
    }

    /** 自动把诊断面板顶出来，并顺手读一份现场 —— 用户点一下就能看到，不用先猜要点哪。
     *
     *  @param stall true = 主帧 15s 未走完（标题走「主帧加载停滞」并隐藏关闭钮）；
     *               false = 其它原因（关键子资源失败 / 上游契约行），标题用 reason 本身。
     *               老实现恒为 true，子资源失败也被说成「主帧停滞」，标题与实因对不上。 */
    fun autoOpenPanel(reason: String, stall: Boolean = false) {
        stalledMain = stall
        if (!stall) bootAlert = reason
        panelOpen = true
        loadError = null
        blankScreen = false
        collectDiag()
        android.util.Log.w("HarnessScreen", "白屏自证：$reason")
    }

    /** [dsh-boot-ready] 到达：页面自己报「渲染出来了」，此前所有白屏告警全部作废
     *  （15s 看门狗、契约 stall/failed、空白探针都可能是误报 ——  healthy boot 慢而已）。 */
    fun onBootReady() {
        bootReady = true
        loadError = null
        blankScreen = false
        stalledMain = false
        bootAlert = null
        panelOpen = false
        diag = null
    }

    // 主帧看门狗（#381 重写）：15s 还没 onPageFinished ⇒ 卡在某个子资源上（最典型的是 head 里的
    // 阻塞 combo 脚本不返回 —— 它挂着，body 末尾的 boot-ready 承诺就永不执行，入口 chunk 永久
    // await，白屏且进度条停住）。三级处置，逐级升级：
    //   1. 先探 #root：已有内容 = 只是慢，再给 30s（45s 仍无 boot 契约才升级，不误伤慢启动）；
    //   2. #root 空且**引擎进程已退出** ⇒ 请求挂在半空、永远不会返回（23:26 实锤 code=0 的形态），
    //      直接顶面板并附引擎日志尾部 —— 这条路径以前完全没有覆盖；
    //   3. #root 空且引擎活着 ⇒ 有界自动重载（≤2 次）自愈瞬时挂起，仍不动才顶面板。
    LaunchedEffect(navStartedAt) {
        if (navStartedAt == 0L) return@LaunchedEffect
        delay(MAIN_FRAME_STALL_MS)
        if (pageDone || bootReady) return@LaunchedEffect
        val wv = webView
        val kids = if (wv != null) probeRootKids(wv) else -1
        if (kids > 0) {
            // 页面在渲染只是慢：再给 30s，到 45s 仍无 boot 契约才升级
            delay(30_000)
            if (!pageDone && !bootReady) {
                autoOpenPanel("启动 45s 未完成（boot 契约未到达，进度 $progress%）", stall = true)
            }
            return@LaunchedEffect
        }
        // #root 空：先看引擎还在不在 —— 引擎退了，页面请求挂在半空，等再久也不会动
        val harness = DeepSeekHarness.get(context)
        if (!harness.isRunning()) {
            autoOpenPanel(
                "引擎进程已退出 —— 页面请求挂在半空，永远不会返回。诊断里已附引擎日志尾部。",
                stall = true,
            )
            return@LaunchedEffect
        }
        if (autoReloads < 2) {
            autoReloads++
            reload(auto = true)
            return@LaunchedEffect
        }
        autoOpenPanel("主帧 ${MAIN_FRAME_STALL_MS / 1000}s 未加载完成（进度 $progress%）", stall = true)
    }

    // 冻结看门狗（移植自参考实现 EngineStartFlow.freezeRunnable，issue #36 荣耀 MagicUI 6.1 实锤）：
    // 页面 JS 主线程冻结（渲染进程失活）时，页内定时器也跑不动，WebView 一个回调都不给 ——
    // 用户看到「卡死」而不是任何提示。主线程每 10s 发一次 JS 心跳，回调不再返回即判失活：
    // 页面在场 >45s 且 >20s 无应答 ⇒ 前台静默 reload 一次（有界，latch 防重复）。
    // 主帧没走完时不参与（那归主帧看门狗管，口径与上游「onPageFinished 处启动」一致）。
    LaunchedEffect(navStartedAt) {
        if (navStartedAt == 0L) return@LaunchedEffect
        while (isActive && !pageDone) {
            delay(1_000)
        }
        val startedAt = System.currentTimeMillis()
        var jsAckAt = startedAt
        var pingOutstanding = false
        var freezeReloaded = false
        while (isActive) {
            delay(10_000)
            val wv = webView ?: continue
            if (bootReady) break
            val now = System.currentTimeMillis()
            if (now - startedAt > 45_000 && now - jsAckAt > 20_000) {
                if (!freezeReloaded) {
                    freezeReloaded = true
                    android.util.Log.w(
                        "HarnessScreen",
                        "webview JS 无响应，渲染进程冻结（frozenMs=${now - jsAckAt}）",
                    )
                    reload(auto = true)
                }
                jsAckAt = now
                pingOutstanding = false
            } else if (!pingOutstanding) {
                pingOutstanding = true
                val ack = withTimeoutOrNull(5_000) { wv.evalJs("1") }
                if (ack != null) {
                    jsAckAt = System.currentTimeMillis()
                }
                pingOutstanding = false
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(TerminalTheme.backgroundColor)
    ) {
        // #381：WebView 重建纪元 —— 渲染进程消失（onRenderProcessGone）时 epoch+1，
        // key 变化让 AndroidView 重新走 factory 拿一个**新** WebView。不重建则界面留在
        // 一个永不响应的 WebView 上，用户看到「卡死」而不是「崩了」（参考实现同款处置）。
        key(webViewEpoch) {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    // 系统导航栏与输入法避让：否则引擎页底部（输入框、会话列表）会被手势区/键盘压住
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .imePadding(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        // 版本敏感设置单面真源（移植自参考实现 WebViewShim.applyBaseline）：
                        // provider 能力门判深色跟随 + appOwnsTheme（引擎 UI 自配色，
                        // 禁止 WebView 算法改色把页面二次压暗）。
                        DshWebViewShim.applyBaseline(settings, appOwnsTheme = true)
                        settings.apply {
                            allowContentAccess = false
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
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress
                            }

                            // #381：内核探针的 title 回传通道。document-start 注入的探针把结论
                            // 写进 document.title（DSH_KERNEL:…）—— 部分 ROM 会把首文档的
                            // evaluateJavascript 回包丢掉，title 回调丢不掉。
                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                val t = title ?: return
                                if (!t.startsWith(KERNEL_TITLE_PREFIX)) return
                                val rep = t.removePrefix(KERNEL_TITLE_PREFIX)
                                if (rep.isBlank()) return
                                kernelReport = rep
                                if (rep.contains("S:")) {
                                    android.util.Log.e("HarnessScreen", "内核能力预检(title 通道)：语法级缺失 ⇒ $rep")
                                }
                            }

                            // #380：上游 boot 看门狗的 console 契约入口（三条前缀见文件内常量）。
                            // 页内 40s 看门狗现场采得再齐（pendingBundles / badBundles / engineHttp /
                            // rendered / pendingBoot），壳侧不接就全部丢失 —— 白屏只剩「等 40s」。
                            // ready ⇒ 停 15s 看门狗并清误报；stall/failed ⇒ 直接顶诊断面板。
                            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                                val cm = consoleMessage ?: return false
                                val msg = cm.message() ?: return false
                                if (!msg.startsWith(BOOT_STALL_PREFIX) &&
                                    !msg.startsWith(BOOT_READY_PREFIX) &&
                                    !msg.startsWith(BOOT_FAILED_PREFIX)
                                ) {
                                    return false
                                }
                                val lvl = when (cm.messageLevel()) {
                                    ConsoleMessage.MessageLevel.ERROR -> "E"
                                    ConsoleMessage.MessageLevel.WARNING -> "W"
                                    ConsoleMessage.MessageLevel.TIP -> "I"
                                    ConsoleMessage.MessageLevel.DEBUG -> "D"
                                    else -> "L"
                                }
                                val line = "[$lvl] $msg"
                                android.util.Log.e("HarnessScreen", "boot 契约: $msg")
                                bootLines = (bootLines + line).takeLast(30)
                                when {
                                    msg.startsWith(BOOT_READY_PREFIX) -> onBootReady()
                                    msg.startsWith(BOOT_FAILED_PREFIX) ->
                                        autoOpenPanel("上游看门狗：客户端插件装配失败（boot 契约 failed 行，见下）")
                                    else ->
                                        autoOpenPanel("上游看门狗：启动 40s 未完成（boot 契约 stall 行，见下）")
                                }
                                return true
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
                                frameCommitted = false
                                stalledMain = false
                                panelOpen = false
                                blankScreen = false
                                subErrors = emptyList()
                                // #380：契约行与就绪标记按文档轮换 —— 上一份文档报的 ready
                                // 不担保新文档（303 回跳会走第二个文档）。
                                bootLines = emptyList()
                                bootReady = false
                                bootAlert = null
                                navStartedAt = System.currentTimeMillis()
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                pageDone = true
                            }

                            // #381：首帧提交（移植自参考实现 #242）。登记「页面画出来过一帧」——
                            // 「慢启动」与「死页面」的区分判据；只登记不改背景色
                            // （深色底色的可诊断性是它存在的全部理由）。
                            // 形参改名 pageUrl：本方法的入参就叫 url，会遮住 composable 的
                            // 目标页 url（老写法 this@HarnessWebView 对无接收者函数同样不成立）。
                            override fun onPageCommitVisible(view: WebView?, pageUrl: String?) {
                                val u = pageUrl ?: return
                                if (sameOrigin(u, url)) frameCommitted = true
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

                            // #381：TLS 失败（引擎走 http://127.0.0.1 不走 TLS，本回调只在被跳到
                            // 外部 https 页面时触发）。保持默认拒绝语义，只落诊断 ——
                            // 安全敏感面不得为诊断而弱化（与参考实现同口径）。
                            override fun onReceivedSslError(
                                view: WebView,
                                handler: android.webkit.SslErrorHandler,
                                error: android.net.http.SslError,
                            ) {
                                android.util.Log.w(
                                    "HarnessScreen",
                                    "SSL 错误: ${error.primaryError} ← ${error.url}",
                                )
                                handler.cancel()
                            }

                            // #381：渲染进程被杀（低内存/OOM/厂商治理）—— 不处理则 Activity 留在
                            // 一个永不响应的 WebView 上，用户看到「卡死」而不是「崩了」。
                            // 处置：落日志 + epoch+1 重建 WebView（引擎不重启；页面随新 WebView 重载）。
                            // 类型是 android.webkit.RenderProcessGoneDetail（没有 WebView 前缀）。
                            override fun onRenderProcessGone(
                                view: WebView,
                                detail: android.webkit.RenderProcessGoneDetail,
                            ): Boolean {
                                android.util.Log.w(
                                    "HarnessScreen",
                                    "渲染进程消失 didCrash=${detail.didCrash()}",
                                )
                                (view.parent as? ViewGroup)?.removeView(view)
                                runCatching { view.destroy() }
                                if (webView === view) webView = null
                                webViewEpoch++
                                return true
                            }
                        }
                        // 桥必须在 loadUrl 之前装好：页面 head 解析期就同步调 getPickToken()/getSystemDark()。
                        // 方法面与上游 AndroidBridge 逐字对齐（移植自参考实现，去品牌化）——
                        // WebView 的 JS 桥按实参个数匹配 Java 方法，缺方法 = 页面侧 Error: Method not found。
                        addJavascriptInterface(
                            DshAndroidBridge(
                                context = ctx,
                                onKeepScreen = { enable ->
                                    val a = ctx as? Activity
                                    a?.runOnUiThread {
                                        if (enable) {
                                            a.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                        } else {
                                            a.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                        }
                                    }
                                },
                                onGetSystemDark = { true },
                                onSettingsPathRequest = {
                                    val f = java.io.File(
                                        java.io.File(DeepSeekHarness.get(ctx).home, ".dsh"),
                                        "settings.yaml",
                                    )
                                    if (f.isFile) f.absolutePath else ""
                                },
                                onGetUiLanguage = { DshUiLanguage.get(ctx) },
                                onSetUiLanguage = { mode -> DshUiLanguage.set(ctx, mode) },
                                onGetImmersiveMode = { true },
                                onCopyTextRequest = { text -> DshAndroidBridge.writeClipboard(ctx, text) },
                                onRestartEngine = { restartEngine() },
                                onShutdownToGuide = { onClose() },
                                onReloadWebUI = { reload() },
                                onOpenConsole = {
                                    android.util.Log.i("HarnessScreen", "页面请求打开控制台（宿主未提供该入口）")
                                },
                                onOpenNativePath = { path -> DshAndroidBridge.openPathExternally(ctx, path) },
                                onIncomingWorkspacePath = { DeepSeekHarness.get(ctx).workspace.absolutePath },
                            ),
                            "androidBridge",
                        )
                        // #381（致命修正）：document-start 注入必须在**第一次 loadUrl 之前**注册。
                        // 老实现把 addDocumentStartJavaScript 放在 loadUrl 之后 —— 首次导航
                        // 完全不生效：viewport 修正 / 画布钉色 / 错误收集 / 网络探针 / 内核探针
                        // 全丢，浅色设备首帧就是一块纯白，且白屏时一行诊断都采不到。
                        // 顺序：locale 引导（页面同步读 __DSH_LOCALE__）→ 内核 title 探针
                        //       → viewport/画布/错误收集 → 网络探针。
                        runCatching {
                            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                                this,
                                dshLocaleBootstrapScript(
                                    DshUiLanguage.get(ctx),
                                    systemLanguages(ctx),
                                ) + "\n" +
                                    DSH_KERNEL_TITLE_JS + "\n" +
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
  // ── 首帧画布（#381 重写）───────────────────────────────────────────────
  // 上游 index.html 的 boot <style> 把画布钉成浅色：
  //   :root{color-scheme:light}body{background-color:#fff}
  //   @media(prefers-color-scheme:dark){:root{color-scheme:dark}body{background-color:#151517}
  // 而 compat 的 applyCanvasTheme() 只写 documentElement.style.background —— body 自己有
  // 背景色时那一条被完全盖住，F1 那条「首帧即主题色」的修复在这里**不生效**。
  // 宿主界面恒为深色终端主题（TerminalTheme.backgroundColor #0B0E14），浅色设备上
  // 「11MB 引导包还在下」的那几秒就是一块纯白 —— 用户看到的就是白屏。
  // 这里在文档开始前把画布钉成终端底色，#root 长出子节点（React 挂载）即刻松手。
  //
  // #381 两处修正（老版在这两点上全部落败）：
  //  ① document-start 期 documentElement 通常还没建好，老版一次性 append 静默失败；
  //  ② 老版挂在 head 且不重排，与 prepend 注入的 boot 样式同序打平后级联落败。
  // 现在：documentElement/head 任一就绪即挂，未就绪等 DOM 事件 + 16ms 轮换重试（≤3s）；
  //      最终形态是 html 元素末尾子节点（文档序最后 ⇒ 级联必胜）；选择器带 :root
  //      才能压过 boot 的 :root{color-scheme:light}（:root 特异性高于裸 html/body）。
  (function(){
    try {
      if (window.__dshHostCanvas) return;
      var BOOT = '#0B0E14';
      var st = document.createElement('style');
      st.textContent = ':root,html,body{background-color:' + BOOT + ' !important;color-scheme:dark !important;}';
      var done = false;
      var place = function(){
        if (done) return true;
        try {
          var host = document.documentElement || document.head;
          if (!host) return false;
          host.appendChild(st);
          return true;
        } catch (e) { return false; }
      };
      if (!place()) {
        var tries = 0;
        var timer = setInterval(function(){
          tries++;
          if (place() || tries > 180) clearInterval(timer);
        }, 16);
        document.addEventListener('DOMContentLoaded', function(){ place(); });
        window.addEventListener('load', function(){ place(); });
      }
      // 只有真的挂进去了才置位 —— 老版本在 appendChild **之前**置 flag，
      // append 抛错时诊断里显示「已生效」，把没注入说成已注入。
      if (st.parentNode) {
        window.__dshHostCanvas = true;
        window.__dshHostCanvasPos = (document.documentElement && st.parentNode === document.documentElement) ? 'html-last' : 'head';
      }
      var release = function(){
        if (done) return true;
        var r = document.getElementById('root');
        if (r && r.childElementCount > 0) {
          done = true;
          if (st.parentNode) st.parentNode.removeChild(st);
          window.__dshHostCanvasPos = 'released';
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
""" +                                     DSH_NET_JS,
                                setOf("*"),
                            )
                        }.onFailure { android.util.Log.w("HarnessScreen", "注入失败", it) }
                        // #390：WebView 底色钉成终端深色（与 document-start 的画布钉色同值）。
                        // WebView 默认背景是纯白，而画布钉色只覆盖**文档内容** —— 首帧之前
                        // （渲染进程刚起、文档还没 commit 的那几帧）露出来的是 WebView 自己的
                        // 背景色：浅色设备上那就是一次白闪，用户看到的就是「白屏」。
                        // 这一行在 loadUrl 之前，保证从第一帧起就是深色。
                        setBackgroundColor(0xFF0B0E14.toInt())
                        // #390：UA 直读兜底种子。JS 探针有两条回传通道（evaluateJavascript
                        // 回包 + document.title），部分 ROM 两条都会丢 —— 那种设备上
                        // kernelReport 永远是 null，诊断面板里「内核能力预检」整节消失，
                        // 白屏时最该看的「这台设备内核多少」反而看不到。Kotlin 侧读 UA 不经过
                        // JS、不经过回调，100% 可靠：先落地做底，JS 探针回包到了再覆盖成完整
                        // 结论（探针的 S:/A: 判定以它的为准，本种子不带任何能力判定，
                        // 因此也不会误触红条 —— 红条只认 S: 前缀）。
                        val uaText = settings.userAgentString.orEmpty()
                        val uaMajor = Regex("Chrome/(\\d+)").find(uaText)?.groupValues?.get(1)
                        kernelReport = if (uaMajor != null) {
                            "CHROME=$uaMajor|UA 直读兜底（JS 探针回包未达，仅版本号可靠）"
                        } else {
                            "CHROME=?|UA 直读兜底（未解析到 Chrome 版本：${uaText.take(80)}）"
                        }
                        loadedUrl = url
                        navStartedAt = System.currentTimeMillis()
                        loadUrl(url)
                        webView = this
                        onWebViewReady(this)
                        // #380：内核能力预检（evaluateJavascript 通道；title 通道的备份，
                        // 两条通道结论一致，ROM 丢哪条都不影响红条判定）。
                        // 缺私有字段（84+）/ 逻辑赋值（85+）这类**语法**能力时入口 chunk
                        // 解析期整体不执行：React 永不挂载 —— 白屏、无报错、重试无效。
                        runCatching {
                            evaluateJavascript(DSH_KERNEL_JS) { raw ->
                                val r = unescapeJsString(raw.orEmpty()).trim()
                                kernelReport = r.ifEmpty { null }
                                // 语法级缺失 = 入口 chunk 在这台设备上不可能解析成功，必须留痕
                                if (r.contains("S:")) {
                                    android.util.Log.e("HarnessScreen", "内核能力预检：语法级缺失 ⇒ $r")
                                }
                            }
                        }
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
        }

        // #380：内核能力预警条。**仅语法级缺失（S:）才挂**——那意味着入口 chunk 在解析期
        // 整体不执行，引擎界面在这台设备上不可能跑起来，把「白屏」翻译成「升级 WebView」。
        // 仅运行时 API 缺失（A:）不挂条：compat 的 polyfill 正是为它准备的，不打扰页面。
        // 红条**列出真实缺失项与内核版本**——一张截图即可定论，不必再猜是不是误报
        // （v0 的教训：v 标志测试图案写错，红条曾对每台设备误报）。
        val kernelSyntaxMissing = kernelReport
            ?.split('|')
            ?.filter { it.startsWith("S:") }
            ?: emptyList()
        val kernelChrome = kernelReport
            ?.removePrefix("CHROME=")
            ?.substringBefore('|')
            ?: "?"
        if (kernelSyntaxMissing.isNotEmpty()) {
            Surface(
                color = Color(0xFF7A2424),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 10.dp, top = 46.dp),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(
                    "⚠ WebView 内核过旧（Chrome/$kernelChrome），引擎界面无法运行：缺 " +
                        kernelSyntaxMissing.joinToString("、") { it.removePrefix("S:") } +
                        " —— 入口模块会被整体跳过，这就是白屏的原因。" +
                        "请升级「Android System WebView」或系统后重试；无法升级请把此版本号反馈给开发者。",
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }

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
                            bootAlert != null -> bootAlert!!
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
                        HarnessAction("重启引擎") { restartEngine() }
                        HarnessAction("重新取鉴权") {
                            loadError = null
                            blankScreen = false
                            stalledMain = false
                            panelOpen = false
                            diag = null
                            onAuthRetry()
                        }
                        if (err == null && !blank && !stalled && bootAlert == null) {
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
