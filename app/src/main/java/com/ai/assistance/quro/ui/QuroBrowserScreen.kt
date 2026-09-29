package com.ai.assistance.quro.ui
import androidx.compose.ui.res.stringResource
import com.ai.assistance.quro.R
import com.ai.assistance.quro.util.qstr

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray
import org.json.JSONObject
import android.app.Activity
import android.graphics.Bitmap
import android.view.PixelCopy
import android.os.Environment
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.ai.assistance.quro.core.tools.QuroDownloadUtil
import com.ai.assistance.quro.core.tools.QuroSessionBridge
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

private const val BM_PREFS = "quro_browser"
private const val BM_KEY = "bookmarks"

// 注：WebView 的 scheme 过滤与外部跳转逻辑已迁至 QuroBrowserViewHost（共享单例的单一通用 client）。
private const val SCRIPT_PREFS = "quro_browser"
private const val SCRIPT_KEY = "scripts"
private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
private const val MOBILE_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
private const val TABLET_UA =
    "Mozilla/5.0 (Linux; Android 14; SM-X810) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
private const val LEGACY_UA =
    "Mozilla/5.0 (Linux; Android 10; SM-G960F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/88.0.4324.181 Mobile Safari/537.36"

// 预设 UA 列表
private val UA_PRESETS = listOf(
    "自动" to "",
    "桌面版 (Chrome 120)" to DESKTOP_UA,
    "手机版 (Chrome 120 Mobile)" to MOBILE_UA,
    "平板版 (Chrome 120 Tablet)" to TABLET_UA,
    "兼容模式 (Chrome 88 旧版)" to LEGACY_UA,
)

// —— 浏览器内核：使用系统 WebView（Android 自带 Chromium/WebKit 引擎），保证「能打开网页」——
// 参考 Titanium Browser 仓库结论：自行编译的 Chromium 内核无法作为库嵌入应用，故落地为系统 WebView，
// 由系统提供稳定内核，打开各类网页最可靠。

// —— 网页自动化脚本：数据模型 + 持久化 ——
private data class BrowserScript(val id: String, val name: String, val code: String)

private fun loadScripts(ctx: Context): List<BrowserScript> {
    val sp = ctx.getSharedPreferences(SCRIPT_PREFS, Context.MODE_PRIVATE)
    val raw = sp.getString(SCRIPT_KEY, null) ?: return emptyList()
    return runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            BrowserScript(o.getString("id"), o.getString("name"), o.getString("code"))
        }
    }.getOrDefault(emptyList())
}

private fun saveScripts(ctx: Context, list: List<BrowserScript>) {
    val arr = JSONArray()
    list.forEach { s ->
        arr.put(JSONObject().apply { put("id", s.id); put("name", s.name); put("code", s.code) })
    }
    ctx.getSharedPreferences(SCRIPT_PREFS, Context.MODE_PRIVATE)
        .edit().putString(SCRIPT_KEY, arr.toString()).apply()
}

// —— 内置 Python 控制台：把 Brython 资源读入内存，内联进 HTML 后用 loadDataWithBaseURL 加载（避开 file:// 限制）——
private fun openPythonConsole(ctx: Context, wv: WebView?) {
    if (wv == null) return
    val html = runCatching { ctx.assets.open("www/python_console.html").bufferedReader().readText() }.getOrNull()
    if (html == null) { wv.loadUrl("about:blank"); return }
    val js = runCatching { ctx.assets.open("www/brython.min.js").bufferedReader().readText() }.getOrNull()
    // 把 brython.min.js 内联到 </head> 前，避免 file:// 在 Android 11+ 被 WebView 拦截
    val inlined = if (js != null) html.replace("</head>", "<script>$js</script></head>") else html
    // 注入 Quro 会话桥（window.QuroSession）+ Python 侧 quro_session 包装，
    // 使 Python 可驱动 browser_act、读写 Cookie/Storage（Python↔浏览器会话桥）。
    val qsPy = QuroSessionBridge.QURO_SESSION_PY
    val withQs = inlined.replace("</body>", "<script type=\"text/python\">\n$qsPy\n</script>\n</body>")
    // 先挂载桥（addJavascriptInterface 须在页面加载前），再加载页面
    QuroSessionBridge.register(wv)
    wv.loadDataWithBaseURL("https://quro.local/", withQs, "text/html", "utf-8", null)
}

private val DEFAULT_SCRIPT = """// 原生「眼 + 手」自动化指令（系统 WebView 不开放网页内 JS 求值接口）：
// eye_capture         —— 眼睛截图：PixelCopy 截取当前界面像素，交给 AI/人眼识别
// tap_text:下一步      —— 眼睛看到文本含「下一步」的控件 → 手点击它
// count_buttons       —— 统计当前界面可见可点控件数量
"""

private val SAMPLE_SCRIPTS = listOf(
    BrowserScript("eye_capture", qstr(R.string.qk_00855), "eye_capture"),
    BrowserScript("tap_text_next", qstr(R.string.qk_00856), qstr(R.string.qk_00857)),
    BrowserScript("count_buttons", qstr(R.string.qk_00858), "count_buttons"),
)

private fun nowTs(): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

private fun formatEvalResult(raw: String?): String {
    if (raw == null) return qstr(R.string.qk_00859)
    val r = raw.trim().removeSurrounding("\"")
    return try {
        val o = JSONObject(r)
        if (o.optBoolean("ok", true)) {
            val v = o.opt("value")
            when (v) {
                null, JSONObject.NULL -> "(null)"
                is JSONObject -> v.toString(2)
                is JSONArray -> v.toString(2)
                else -> v?.toString() ?: qstr(R.string.qk_00859)
            }
        } else {
            "⚠ 脚本错误: ${o.optString("error")}"
        }
    } catch (_: Exception) {
        r.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
    }
}

// —— 原生「眼 + 手」自动化工具（读 View 树 / PixelCopy 截图 + 模拟触摸）——
private fun findViews(root: View): List<View> {
    val out = mutableListOf<View>()
    if (root is ViewGroup) {
        for (i in 0 until root.childCount) out.addAll(findViews(root.getChildAt(i)))
    } else {
        out.add(root)
    }
    return out
}

private fun countClickable(root: View): Int =
    findViews(root).count {
        it.isClickable && it.visibility == View.VISIBLE && it.width > 0 && it.height > 0
    }

// 向控件中心注入 DOWN+UP 触摸事件（模拟人手点击），作用于根 View 的屏幕坐标
private fun tapView(view: View): Boolean {
    if (!view.isShown) return false
    val loc = IntArray(2)
    view.getLocationOnScreen(loc)
    val x = loc[0] + view.width / 2f
    val y = loc[1] + view.height / 2f
    val root = view.rootView ?: return false
    val t = System.currentTimeMillis()
    val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0).apply {
        source = InputDevice.SOURCE_TOUCHSCREEN
    }
    val up = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0).apply {
        source = InputDevice.SOURCE_TOUCHSCREEN
    }
    root.dispatchTouchEvent(down)
    root.dispatchTouchEvent(up)
    down.recycle()
    up.recycle()
    return true
}

// 「眼睛」：在界面里找到文本包含 key 的可见控件并点击
private fun tapByText(root: View, key: String): String {
    val target = findViews(root).firstOrNull { v ->
        v is TextView && v.visibility == View.VISIBLE && (v.text?.toString()?.contains(key) == true)
    }
    return if (target != null) {
        if (tapView(target)) qstr(R.string.qk_00861, (key).toString()) else qstr(R.string.qk_00862)
    } else {
        qstr(R.string.qk_00863, (key).toString())
    }
}

// 从 Context 中安全取出宿主 Activity（兼容 ContextWrapper 多层包装）
private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c != null) {
        if (c is Activity) return c
        c = if (c is android.content.ContextWrapper) c.baseContext else null
    }
    return null
}

private fun loadBookmarks(ctx: Context): List<Pair<String, String>> {
    val sp = ctx.getSharedPreferences(BM_PREFS, Context.MODE_PRIVATE)
    val raw = sp.getString(BM_KEY, null) ?: return emptyList()
    return runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            o.getString("title") to o.getString("url")
        }
    }.getOrDefault(emptyList())
}

private fun saveBookmarks(ctx: Context, list: List<Pair<String, String>>) {
    val arr = JSONArray()
    list.forEach { (t, u) -> arr.put(JSONObject().apply { put("title", t); put("url", u) }) }
    ctx.getSharedPreferences(BM_PREFS, Context.MODE_PRIVATE)
        .edit().putString(BM_KEY, arr.toString()).apply()
}

/**
 * 应用内置浏览器（系统 WebView 引擎）：
 * 用 Android 系统自带的 WebView（Chromium/WebKit 内核）作为内置浏览器引擎，保证「能打开网页」，
 * 支持前进/后退/刷新/停止、加载进度、桌面版网站、页内查找、收藏夹、分享/复制链接、
 * 正文抓取、网页自动化脚本（注入 JS 执行并回显结果）、代码编辑器入口。
 */
@Composable
fun QuroBrowserScreen(
    url: String,
    onClose: () -> Unit,
    onOpenInSystem: (String) -> Unit = {},
    onMinimize: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current

    // 共享浏览器状态：全屏/化小窗共用同一 WebView，状态由 QuroBrowserViewHost 统一维护
    // （单一通用 client），避免「重挂后某一端 client 被覆盖、UI 不再刷新」。
    val bs = com.ai.assistance.quro.core.tools.QuroBrowserViewHost.collectUiState().value
    var address by remember { mutableStateOf(url) }
    // 地址栏编辑缓冲：导航导致 uiState.url 变化时同步回地址栏（打字时 uiState.url 不变，不会覆盖输入）
    LaunchedEffect(bs.url) {
        if (bs.url.isNotEmpty()) address = bs.url
    }
    // 以下镜像共享状态：collectUiState 在 uiState 变更时触发本组合重算，故普通 val 即可随状态刷新
    val pageTitle = bs.title
    val progress = bs.progress
    val isLoading = bs.isLoading
    val canGoBack = bs.canGoBack
    val canGoForward = bs.canGoForward
    val loadError = bs.loadError
    var desktopMode by remember { mutableStateOf(false) }
    var showEditor by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showFind by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var showBookmarks by remember { mutableStateOf(false) }
    var bookmarks by remember { mutableStateOf(loadBookmarks(ctx)) }
    var showHistory by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(emptyList<com.ai.assistance.quro.core.tools.QuroBrowserViewHost.HistoryEntry>()) }
    var showReader by remember { mutableStateOf(false) }
    var readerText by remember { mutableStateOf("") }

    // —— 浏览器增强功能 ——
    var selectedUa by remember { mutableStateOf(qstr(R.string.qk_00850)) }
    var customUa by remember { mutableStateOf("") }
    var showUaPicker by remember { mutableStateOf(false) }
    var jsEnabled by remember { mutableStateOf(true) }
    var imagesEnabled by remember { mutableStateOf(true) }
    var showCompatibilitySettings by remember { mutableStateOf(false) }
    // 代理设置
    var proxyEnabled by remember { mutableStateOf(false) }
    var proxyType by remember { mutableStateOf("HTTP") } // HTTP / SOCKS5
    var proxyHost by remember { mutableStateOf("") }
    var proxyPort by remember { mutableStateOf("") }
    var proxyUsername by remember { mutableStateOf("") }
    var proxyPassword by remember { mutableStateOf("") }
    var showProxySettings by remember { mutableStateOf(false) }

    // —— 网页自动化脚本（完整闭环）——
    var showScript by remember { mutableStateOf(false) }
    var scripts by remember { mutableStateOf(loadScripts(ctx)) }
    var activeId by remember { mutableStateOf(scripts.firstOrNull()?.id ?: "") }
    var scriptName by remember { mutableStateOf(scripts.firstOrNull()?.name ?: qstr(R.string.qk_00864)) }
    var scriptCode by remember {
        mutableStateOf(scripts.firstOrNull()?.code ?: DEFAULT_SCRIPT)
    }
    var scriptLog by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var eyeBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // 系统 WebView：共享单例（QuroBrowserViewHost），全屏与化小窗重挂不重建；系统自带引擎一定可用。
    // 由下方 AndroidView 工厂在创建/获取共享 WebView 后写入（remember 保持，跨重组成立）。
    var webView by remember { mutableStateOf<WebView?>(null) }

    // 前进/后退状态由 NavigationDelegate.onCanGoBack / onCanGoForward 回调维护
    fun refreshNavState() { }

    // 「眼睛」：PixelCopy 截取当前 Activity 整个窗口（含 WebView 渲染的网页像素），保存并预览
    fun captureScreen() {
        val activity = ctx.findActivity()
        if (activity == null) {
            scriptLog += qstr(R.string.qk_00865, (nowTs()).toString())
            return
        }
        val win = activity.window
        val decor = win.decorView
        if (decor.width <= 0 || decor.height <= 0) {
            scriptLog += qstr(R.string.qk_00866, (nowTs()).toString())
            return
        }
        val bmp = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(win, bmp, { res ->
            if (res == PixelCopy.SUCCESS) {
                eyeBitmap = bmp
                runCatching {
                    val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: ctx.filesDir
                    val file = File(dir, "quro_eye_${System.currentTimeMillis()}.png")
                    FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    scriptLog += qstr(R.string.qk_00867, (nowTs()).toString(), (file.absolutePath).toString(), (bmp.width).toString(), (bmp.height).toString())
                }.onFailure { e ->
                    scriptLog += qstr(R.string.qk_00868, (nowTs()).toString(), (e.message).toString())
                }
            } else {
                scriptLog += qstr(R.string.qk_00869, (nowTs()).toString(), (res).toString())
            }
        }, Handler(Looper.getMainLooper()))
    }

    // 原生「眼 + 手」自动化：解析指令并派发到本应用界面（View 树感知 + 触摸模拟）
    fun runScript(code: String) {
        if (running) return
        running = true
        val root = ctx.findActivity()?.window?.decorView
        val result = when {
            code.trim() == "eye_capture" || code.contains("截图") -> {
                captureScreen()
                qstr(R.string.qk_00871)
            }
            code.startsWith("tap_text:") -> {
                val key = code.substringAfter("tap_text:").trim()
                if (root == null) qstr(R.string.qk_00872) else tapByText(root, key)
            }
            code.trim() == "count_buttons" -> {
                if (root == null) qstr(R.string.qk_00872) else qstr(R.string.qk_00873, (countClickable(root)).toString())
            }
            else -> qstr(R.string.qk_00874)
        }
        scriptLog += "[${nowTs()}] $result\n"
        running = false
    }

    // 书签：接入「开源地址（Iceraven）」+「链接回答」两条入口
    LaunchedEffect(Unit) {
        val wanted = listOf(
            "开源地址（Iceraven 浏览器）" to "https://github.com/fork-maintainers/iceraven-browser",
            "链接回答·开源浏览器清单" to "https://yb.tencent.com/s/NFdWa3f1zpSk",
            "链接回答·百分百开源安卓数字人" to "https://yb.tencent.com/s/I9x5hnu8zJqm",
            "链接回答·3D 全离线（LLM+ASR+TTS+A2BS+渲染都在手机）" to "https://yb.tencent.com/s/TsfOddkjerlh",
        )
        val oldSeedTitles = setOf(qstr(R.string.qk_00879), qstr(R.string.qk_00880))
        var next = bookmarks.filterNot { (t, u) ->
            t in oldSeedTitles || u == "https://yb.tencent.com/s/oRnfpcJJ7fic"
        }
        wanted.forEach { w -> if (next.none { it.second == w.second }) next = next + w }
        if (next != bookmarks) {
            bookmarks = next
            saveBookmarks(ctx, bookmarks)
        }
    }

    // 自动化脚本：首次运行 seed 示例脚本
    LaunchedEffect(Unit) {
        if (scripts.isEmpty()) {
            scripts = SAMPLE_SCRIPTS
            saveScripts(ctx, scripts)
            activeId = scripts.first().id
            scriptName = scripts.first().name
            scriptCode = scripts.first().code
        }
    }

    fun toggleBookmark() {
        val cur = address
        if (cur.isBlank()) return
        val title = pageTitle.ifBlank { cur }
        bookmarks = if (bookmarks.any { it.second == cur }) {
            bookmarks.filter { it.second != cur }
        } else {
            bookmarks + (title to cur)
        }
        saveBookmarks(ctx, bookmarks)
    }

    fun shareLink() {
        val cur = address
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, cur)
        }
        runCatching { ctx.startActivity(Intent.createChooser(intent, qstr(R.string.qk_00881))) }
    }

    fun copyLink() {
        val cur = address
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.HONEYCOMB) {
            cm.setPrimaryClip(android.content.ClipData.newPlainText("url", cur))
        }
    }

    fun applyDesktopMode() {
        val wv = webView ?: return
        val ua = when {
            selectedUa == qstr(R.string.qk_00036) && customUa.isNotBlank() -> customUa
            selectedUa != qstr(R.string.qk_00850) -> UA_PRESETS.find { it.first == selectedUa }?.second ?: ""
            desktopMode -> DESKTOP_UA
            else -> ""
        }
        // 系统 WebView：空 UA = 使用系统默认；禁用 JS / 图片即时落到 WebSettings。
        wv.settings.userAgentString = ua.ifBlank { null }
        wv.settings.javaScriptEnabled = jsEnabled
        wv.settings.loadsImagesAutomatically = imagesEnabled
        wv.reload()
    }

    // —— 系统 WebView 的进度 / 标题 / 导航回调在下方 AndroidView 工厂里通过
    //    WebViewClient / WebChromeClient 配置，这里不再单独挂委托——

    // 返回键优先级：编辑器 → 查找栏 → 书签面板 → 菜单 → 网页后退 → 关闭
    androidx.activity.compose.BackHandler {
        when {
            showEditor -> showEditor = false
            showFind -> { showFind = false; webView?.clearMatches(); findQuery = "" }
            showBookmarks -> showBookmarks = false
            showHistory -> showHistory = false
            showMenu -> showMenu = false
            canGoBack -> { webView?.goBack() }
            else -> onClose()
        }
    }

    Box(Modifier.fillMaxSize().background(cs.background)) {
        Column(Modifier.fillMaxSize().background(cs.background)) {
            // 顶部应用栏
            Row(
                Modifier.fillMaxWidth().background(cs.surface)
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { webView?.goBack() },
                    enabled = canGoBack,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        stringResource(R.string.qk_00882),
                        tint = if (canGoBack) cs.onSurface else cs.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                }
                IconButton(
                    onClick = { webView?.goForward() },
                    enabled = canGoForward,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        stringResource(R.string.qk_00883),
                        tint = if (canGoForward) cs.onSurface else cs.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                }
                Surface(
                    color = cs.surfaceVariant,
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.weight(1f).height(40.dp),
                ) {
                    BasicTextField(
                        value = address,
                        onValueChange = { address = it },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 14.sp, color = cs.onSurface),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = {
                            val u = address.trim()
                            if (u.isNotEmpty()) {
                                val target = com.ai.assistance.quro.core.tools.QuroBrowserController.resolveBrowserInput(u)
                                webView?.loadUrl(target)
                                address = target
                            }
                        }),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        decorationBox = { inner ->
                            if (address.isEmpty()) {
                                Text(stringResource(R.string.qk_00884), color = cs.onSurfaceVariant, fontSize = 14.sp)
                            }
                            inner()
                        },
                    )
                }
                IconButton(
                    onClick = { webView?.let { if (isLoading) it.stopLoading() else it.reload() } },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        if (isLoading) Icons.Filled.Close else Icons.Filled.Refresh,
                        if (isLoading) stringResource(R.string.qk_00885) else stringResource(R.string.qk_00459),
                        tint = cs.primary,
                    )
                }
                IconButton(onClick = { showEditor = true }, Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Code, stringResource(R.string.qk_00886), tint = cs.onSurfaceVariant)
                }
                IconButton(onClick = { captureScreen() }, Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Visibility, stringResource(R.string.qk_00887), tint = cs.onSurfaceVariant)
                }
                IconButton(onClick = onMinimize, Modifier.size(36.dp)) {
                    Icon(Icons.Filled.CloseFullscreen, stringResource(R.string.qk_00888), tint = cs.onSurfaceVariant)
                }
                Box {
                    IconButton(onClick = { showMenu = true }, Modifier.size(36.dp)) {
                        Icon(Icons.Filled.MoreVert, stringResource(R.string.qk_00404), tint = cs.onSurfaceVariant)
                    }
                    val curUrl = address
                    val isBookmarked = curUrl.isNotBlank() && bookmarks.any { it.second == curUrl }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.qk_00459)) },
                            leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                            onClick = { showMenu = false; webView?.reload() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.qk_00889)) },
                            leadingIcon = { Icon(Icons.Filled.Computer, null) },
                            trailingIcon = { Checkbox(checked = desktopMode, onCheckedChange = null) },
                            onClick = {
                                desktopMode = !desktopMode
                                applyDesktopMode()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.qk_00890)) },
                            leadingIcon = { Icon(Icons.Filled.PhoneAndroid, null) },
                            onClick = { showMenu = false; showUaPicker = true },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.qk_00891)) },
                            leadingIcon = { Icon(Icons.Filled.Tune, null) },
                            onClick = { showMenu = false; showCompatibilitySettings = true },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.qk_00892)) },
                            leadingIcon = { Icon(Icons.Filled.Security, null) },
                            onClick = { showMenu = false; showProxySettings = true },
                        )
                        DropdownMenuItem(
                            text = { Text(if (isBookmarked) stringResource(R.string.qk_00893) else stringResource(R.string.qk_00894)) },
                            leadingIcon = {
                                Icon(
                                    if (isBookmarked) Icons.Filled.Star else Icons.Filled.StarBorder,
                                    null,
                                )
                            },
                            onClick = { showMenu = false; toggleBookmark() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.qk_00895)) },
                            leadingIcon = { Icon(Icons.Filled.Bookmarks, null) },
                            onClick = { showMenu = false; showBookmarks = true },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.qk_00896)) },
                            leadingIcon = { Icon(Icons.Filled.History, null) },
                            onClick = {
                                showMenu = false
                                history = com.ai.assistance.quro.core.tools.QuroBrowserViewHost.loadHistory(ctx)
                                showHistory = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.qk_00897)) },
                            leadingIcon = { Icon(Icons.Filled.Search, null) },
                            onClick = { showMenu = false; showFind = true },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.qk_00898)) },
                            leadingIcon = { Icon(Icons.Filled.Link, null) },
                            onClick = { showMenu = false; copyLink() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.qk_00090)) },
                            leadingIcon = { Icon(Icons.Filled.Share, null) },
                            onClick = { showMenu = false; shareLink() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.qk_00899)) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) },
                            onClick = { showMenu = false; onOpenInSystem(address) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.qk_00887)) },
                            leadingIcon = { Icon(Icons.Filled.Visibility, null) },
                            onClick = {
                                showMenu = false
                                captureScreen()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.qk_00900)) },
                            leadingIcon = { Icon(Icons.Filled.Code, null) },
                            onClick = {
                                showMenu = false
                                showScript = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.qk_00901)) },
                            leadingIcon = { Icon(Icons.Filled.Terminal, null) },
                            onClick = {
                                showMenu = false
                                openPythonConsole(ctx, webView)
                            },
                        )
                    }
                }
            }

            // 加载进度条
            if (isLoading && progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = cs.primary,
                )
            }

            // 加载错误提示（点击重试）
            loadError?.let {
                Surface(
                    color = cs.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                        .clickable { webView?.reload() },
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(qstr(R.string.qk_00902, (it).toString()),
                            color = cs.onErrorContainer,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { webView?.reload() }, Modifier.size(28.dp)) {
                            Icon(Icons.Filled.Refresh, qstr(R.string.qk_00092), tint = cs.onErrorContainer)
                        }
                    }
                }
            }

            // 页内查找栏
            if (showFind) {
                Row(
                    Modifier.fillMaxWidth().background(cs.surfaceVariant)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        value = findQuery,
                        onValueChange = { findQuery = it; webView?.findAllAsync(it) },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 14.sp, color = cs.onSurface),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        decorationBox = { inner ->
                            if (findQuery.isEmpty()) Text(stringResource(R.string.qk_00903), color = cs.onSurfaceVariant, fontSize = 14.sp)
                            inner()
                        },
                    )
                    IconButton(onClick = { webView?.findNext(false) }, Modifier.size(32.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.qk_00904), tint = cs.onSurfaceVariant)
                    }
                    IconButton(onClick = { webView?.findNext(true) }, Modifier.size(32.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.qk_00905), tint = cs.onSurfaceVariant)
                    }
                    IconButton(
                        onClick = { showFind = false; webView?.clearMatches(); findQuery = "" },
                        Modifier.size(32.dp),
                    ) {
                        Icon(Icons.Filled.Close, stringResource(R.string.qk_00906), tint = cs.onSurfaceVariant)
                    }
                }
            }

            // 收藏夹面板
            if (showBookmarks) {
                Surface(
                    color = cs.surface,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                ) {
                    Column {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(R.string.qk_00907), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            IconButton(onClick = { showBookmarks = false }, Modifier.size(32.dp)) {
                                Icon(Icons.Filled.Close, stringResource(R.string.qk_00065), tint = cs.onSurfaceVariant)
                            }
                        }
                        if (bookmarks.isEmpty()) {
                            Text(stringResource(R.string.qk_00908), color = cs.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
                        } else {
                            LazyColumn {
                                items(bookmarks) { (title, u) ->
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .clickable {
                                                webView?.loadUrl(u)
                                                address = u
                                                showBookmarks = false
                                            }
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(title.ifBlank { u }, color = cs.onSurface, fontSize = 14.sp, maxLines = 1)
                                            Text(u, color = cs.onSurfaceVariant, fontSize = 12.sp, maxLines = 1)
                                        }
                                        IconButton(
                                            onClick = {
                                                bookmarks = bookmarks.filter { it.second != u }
                                                saveBookmarks(ctx, bookmarks)
                                            },
                                            Modifier.size(32.dp),
                                        ) {
                                            Icon(Icons.Filled.Delete, stringResource(R.string.qk_00091), tint = cs.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 历史记录面板
            if (showHistory) {
                Surface(
                    color = cs.surface,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                ) {
                    Column {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(R.string.qk_00896), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            if (history.isNotEmpty()) {
                                TextButton(onClick = {
                                    com.ai.assistance.quro.core.tools.QuroBrowserViewHost.clearHistory(ctx)
                                    history = emptyList()
                                }) { Text(stringResource(R.string.qk_00764), color = cs.error, fontSize = 13.sp) }
                            }
                            IconButton(onClick = { showHistory = false }, Modifier.size(32.dp)) {
                                Icon(Icons.Filled.Close, stringResource(R.string.qk_00065), tint = cs.onSurfaceVariant)
                            }
                        }
                        if (history.isEmpty()) {
                            Text(stringResource(R.string.qk_00909), color = cs.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
                        } else {
                            LazyColumn {
                                items(history) { h ->
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .clickable {
                                                webView?.loadUrl(h.url)
                                                address = h.url
                                                showHistory = false
                                            }
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(h.title.ifBlank { h.url }, color = cs.onSurface, fontSize = 14.sp, maxLines = 1)
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(h.ts)),
                                                    color = cs.onSurfaceVariant,
                                                    fontSize = 11.sp,
                                                )
                                                Text("  ·  ", color = cs.onSurfaceVariant, fontSize = 11.sp)
                                                Text(h.url, color = cs.onSurfaceVariant, fontSize = 11.sp, maxLines = 1, modifier = Modifier.weight(1f))
                                            }
                                        }
                                        IconButton(
                                            onClick = {
                                                // 单条删除：重写剩余历史
                                                val remain = history.filter { it != h }
                                                history = remain
                                                val sp = ctx.getSharedPreferences("quro_browser", Context.MODE_PRIVATE)
                                                val arr = JSONArray()
                                                remain.forEach { e ->
                                                    arr.put(JSONObject().apply { put("title", e.title); put("url", e.url); put("ts", e.ts) })
                                                }
                                                sp.edit().putString("history", arr.toString()).apply()
                                            },
                                            Modifier.size(32.dp),
                                        ) {
                                            Icon(Icons.Filled.Delete, stringResource(R.string.qk_00091), tint = cs.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 网页内容
            // 注意：AndroidView 不能直接配合 fillMaxSize()+weight(1f)，否则在 Column 子组合测量时
            // 会拿到 maxHeight=Infinity，导致 Size out of range 崩溃。
            // 改为用 weight 的 Box 包一层，AndroidView 只负责 fillMaxSize 填满该 Box（高度有界）。
            Box(Modifier.fillMaxWidth().weight(1f)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { c ->
                        // 复用全局唯一浏览器 WebView（QuroBrowserViewHost）：全屏与化小窗之间只是重挂，
                        // 不重建、不重载 —— 这是消除「化小窗卡顿」的关键。
                        val container = android.widget.FrameLayout(c)
                        val wv = com.ai.assistance.quro.core.tools.QuroBrowserViewHost.getOrCreate(c)
                        // 写入外层 webView 状态，供地址栏/导航按钮/后退-前进等复用同一实例
                        webView = wv
                        // 首次打开才加载初始 url；重挂/还原时 WebView 已加载，loadIfNeeded 零重载。
                        com.ai.assistance.quro.core.tools.QuroBrowserViewHost.loadIfNeeded(url)
                        // 把共享 WebView 挂入本全屏容器（若当前未化小窗）。
                        com.ai.assistance.quro.core.tools.QuroBrowserViewHost.bindMain(container)
                        container
                    },
                    onRelease = {
                        // 离场仅解绑主容器；若仍化小窗则不销毁（WebView 在浮窗复用），真正关闭才 destroy。
                        com.ai.assistance.quro.core.tools.QuroBrowserViewHost.unbindMain(it as ViewGroup)
                    },
                )
            }
        }

        // 内置代码编辑器：全屏覆盖层（浏览网页的同时可写/运行代码，浏览器状态保留在背后）
        if (showEditor) {
            Box(Modifier.fillMaxSize().background(cs.background)) {
                EditorScreen(
                    initialCode = "",
                    initialLang = "javascript",
                    onClose = { showEditor = false },
                )
            }
        }

        // 正文抓取结果：全屏覆盖层（展示/复制网页正文）
        if (showReader) {
            Box(Modifier.fillMaxSize().background(cs.background)) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().background(cs.surface)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.qk_00910),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f).padding(start = 8.dp),
                        )
                        IconButton(
                            onClick = {
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.HONEYCOMB) {
                                    cm.setPrimaryClip(android.content.ClipData.newPlainText("page_text", readerText))
                                }
                                Toast.makeText(ctx, qstr(R.string.qk_00911), Toast.LENGTH_SHORT).show()
                            },
                            Modifier.size(36.dp),
                        ) {
                            Icon(Icons.Filled.ContentCopy, stringResource(R.string.qk_00088), tint = cs.onSurfaceVariant)
                        }
                        IconButton(onClick = { showReader = false }, Modifier.size(36.dp)) {
                            Icon(Icons.Filled.Close, qstr(R.string.qk_00065), tint = cs.onSurfaceVariant)
                        }
                    }
                    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
                        item {
                            Text(
                                readerText,
                                color = cs.onSurface,
                                fontSize = 14.sp,
                                lineHeight = 22.sp,
                            )
                        }
                    }
                }
            }
        }

        // 网页自动化脚本：全屏覆盖层（多脚本管理 + 运行 + 结果日志）
        if (showScript) {
            Box(Modifier.fillMaxSize().background(cs.background)) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().background(cs.surface)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.qk_00912),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f).padding(start = 8.dp),
                        )
                        // 脚本选择下拉
                        var pickerOpen by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { pickerOpen = true }, Modifier.size(36.dp)) {
                                Icon(Icons.Filled.List, stringResource(R.string.qk_00913), tint = cs.onSurfaceVariant)
                            }
                            DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
                                scripts.forEach { s ->
                                    DropdownMenuItem(
                                        text = { Text(s.name) },
                                        onClick = {
                                            pickerOpen = false
                                            activeId = s.id
                                            scriptName = s.name
                                            scriptCode = s.code
                                        },
                                    )
                                }
                            }
                        }
                        IconButton(
                            onClick = {
                                // 保存当前编辑的脚本
                                val id = if (activeId.isBlank()) java.util.UUID.randomUUID().toString() else activeId
                                val updated = BrowserScript(id, scriptName.ifBlank { qstr(R.string.qk_00914) }, scriptCode)
                                scripts = if (scripts.any { it.id == id }) {
                                    scripts.map { if (it.id == id) updated else it }
                                } else {
                                    scripts + updated
                                }
                                activeId = id
                                saveScripts(ctx, scripts)
                                scriptLog += qstr(R.string.qk_00915, (nowTs()).toString(), (updated.name).toString())
                            },
                            Modifier.size(36.dp),
                        ) {
                            Icon(Icons.Filled.Save, stringResource(R.string.qk_00198), tint = cs.primary)
                        }
                        IconButton(
                            onClick = {
                                // 新建空白脚本
                                val id = java.util.UUID.randomUUID().toString()
                                val ns = BrowserScript(id, qstr(R.string.qk_00864), DEFAULT_SCRIPT)
                                scripts = scripts + ns
                                activeId = id
                                scriptName = ns.name
                                scriptCode = ns.code
                                saveScripts(ctx, scripts)
                            },
                            Modifier.size(36.dp),
                        ) {
                            Icon(Icons.Filled.Add, stringResource(R.string.qk_00916), tint = cs.onSurfaceVariant)
                        }
                        IconButton(
                            onClick = {
                                if (activeId.isNotBlank()) {
                                    scripts = scripts.filter { it.id != activeId }
                                    saveScripts(ctx, scripts)
                                    activeId = scripts.firstOrNull()?.id ?: ""
                                    scriptName = scripts.firstOrNull()?.name ?: qstr(R.string.qk_00864)
                                    scriptCode = scripts.firstOrNull()?.code ?: DEFAULT_SCRIPT
                                    scriptLog += qstr(R.string.qk_00917, (nowTs()).toString())
                                }
                            },
                            Modifier.size(36.dp),
                        ) {
                            Icon(Icons.Filled.Delete, stringResource(R.string.qk_00091), tint = cs.error)
                        }
                        IconButton(
                            onClick = { if (!running) runScript(scriptCode) },
                            Modifier.size(36.dp),
                            enabled = !running,
                        ) {
                            Icon(Icons.Filled.PlayArrow, stringResource(R.string.qk_00266), tint = if (running) cs.onSurfaceVariant else cs.primary)
                        }
                        IconButton(onClick = { showScript = false }, Modifier.size(36.dp)) {
                            Icon(Icons.Filled.Close, stringResource(R.string.qk_00065), tint = cs.onSurfaceVariant)
                        }
                    }
                    // 脚本名称
                    BasicTextField(
                        value = scriptName,
                        onValueChange = { scriptName = it },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 13.sp, color = cs.onSurface),
                        modifier = Modifier.fillMaxWidth().background(cs.surfaceVariant)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        decorationBox = { inner ->
                            if (scriptName.isEmpty()) Text(stringResource(R.string.qk_00918), color = cs.onSurfaceVariant, fontSize = 13.sp)
                            inner()
                        },
                    )
                    // 代码编辑器
                    BasicTextField(
                        value = scriptCode,
                        onValueChange = { scriptCode = it },
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(12.dp),
                        textStyle = TextStyle(fontSize = 13.sp, color = cs.onSurface, fontFamily = FontFamily.Monospace),
                    )
                    // 运行日志
                    Surface(color = cs.surfaceVariant, modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                        LazyColumn(Modifier.padding(12.dp)) {
                            item {
                                Text(
                                    if (scriptLog.isBlank()) stringResource(R.string.qk_00919) else scriptLog,
                                    color = cs.onSurface,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }
            }
        }
        // 眼睛截图预览覆盖层
        eyeBitmap?.let { bmp ->
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f))) {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(qstr(R.string.qk_00920), color = Color.White, modifier = Modifier.padding(12.dp))
                    AndroidView(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        factory = { ImageView(it) },
                        update = {
                            it.setImageBitmap(bmp)
                            it.adjustViewBounds = true
                            it.scaleType = ImageView.ScaleType.FIT_CENTER
                        },
                    )
                    Surface(
                        color = cs.primary,
                        modifier = Modifier.padding(12.dp).clickable { eyeBitmap = null },
                    ) {
                        Text(qstr(R.string.qk_00065), color = cs.onPrimary, modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp))
                    }
                }
            }
        }

        // —— UA 切换弹窗 ——
        if (showUaPicker) {
            AlertDialog(
                onDismissRequest = { showUaPicker = false },
                title = { Text(stringResource(R.string.qk_00921)) },
                text = {
                    Column {
                        Text(stringResource(R.string.qk_00922), fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                        UA_PRESETS.forEach { (name, _) ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    selectedUa = name
                                    showUaPicker = false
                                    applyDesktopMode()
                                }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = selectedUa == name,
                                    onClick = {
                                        selectedUa = name
                                        showUaPicker = false
                                        applyDesktopMode()
                                    },
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(name, fontSize = 14.sp)
                            }
                        }
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                selectedUa = qstr(R.string.qk_00036)
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selectedUa == qstr(R.string.qk_00036), onClick = { selectedUa = qstr(R.string.qk_00036) })
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.qk_00923), fontSize = 14.sp)
                        }
                        if (selectedUa == qstr(R.string.qk_00036)) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = customUa,
                                onValueChange = { customUa = it },
                                label = { Text(stringResource(R.string.qk_00924)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    showUaPicker = false
                                    applyDesktopMode()
                                },
                                enabled = customUa.isNotBlank(),
                            ) { Text(stringResource(R.string.qk_00925)) }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showUaPicker = false }) { Text(stringResource(R.string.qk_00065)) }
                },
            )
        }

        // —— 兼容性模式弹窗 ——
        if (showCompatibilitySettings) {
            AlertDialog(
                onDismissRequest = { showCompatibilitySettings = false },
                title = { Text(stringResource(R.string.qk_00891)) },
                text = {
                    Column {
                        Text(stringResource(R.string.qk_00926), fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                jsEnabled = !jsEnabled
                                applyDesktopMode()
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = !jsEnabled, onCheckedChange = { jsEnabled = !it; applyDesktopMode() })
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(stringResource(R.string.qk_00927), fontSize = 14.sp)
                                Text(stringResource(R.string.qk_00928), fontSize = 11.sp, color = cs.onSurfaceVariant)
                            }
                        }
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                imagesEnabled = !imagesEnabled
                                applyDesktopMode()
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = !imagesEnabled, onCheckedChange = { imagesEnabled = !it; applyDesktopMode() })
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(stringResource(R.string.qk_00929), fontSize = 14.sp)
                                Text(stringResource(R.string.qk_00930), fontSize = 11.sp, color = cs.onSurfaceVariant)
                            }
                        }
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        Text(stringResource(R.string.qk_00931), fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                        Text(selectedUa, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showCompatibilitySettings = false }) { Text(qstr(R.string.qk_00065)) }
                },
            )
        }

        // —— 代理设置弹窗 ——
        if (showProxySettings) {
            AlertDialog(
                onDismissRequest = { showProxySettings = false },
                title = { Text(stringResource(R.string.qk_00892)) },
                text = {
                    Column {
                        Row(
                            Modifier.fillMaxWidth().clickable { proxyEnabled = !proxyEnabled }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Switch(checked = proxyEnabled, onCheckedChange = { proxyEnabled = it })
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(R.string.qk_00932), fontSize = 14.sp)
                        }
                        if (proxyEnabled) {
                            HorizontalDivider(Modifier.padding(vertical = 4.dp))
                            Spacer(Modifier.height(8.dp))
                            // 代理类型
                            Text(stringResource(R.string.qk_00933), fontSize = 12.sp, color = cs.onSurfaceVariant)
                            Row(Modifier.padding(top = 4.dp)) {
                                listOf("HTTP", "SOCKS5").forEach { type ->
                                    FilterChip(
                                        selected = proxyType == type,
                                        onClick = { proxyType = type },
                                        label = { Text(type) },
                                        modifier = Modifier.padding(end = 8.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = proxyHost,
                                onValueChange = { proxyHost = it },
                                label = { Text(stringResource(R.string.qk_00934)) },
                                placeholder = { Text(stringResource(R.string.qk_00935)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = proxyPort,
                                onValueChange = { proxyPort = it },
                                label = { Text(stringResource(R.string.qk_00936)) },
                                placeholder = { Text(stringResource(R.string.qk_00937)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = proxyUsername,
                                onValueChange = { proxyUsername = it },
                                label = { Text(stringResource(R.string.qk_00938)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = proxyPassword,
                                onValueChange = { proxyPassword = it },
                                label = { Text(qstr(R.string.qk_00939)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(stringResource(R.string.qk_00940),
                                fontSize = 11.sp, color = cs.onSurfaceVariant,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        // 保存代理设置到 SharedPreferences（commit 确保写入完成后再广播，避免读到的仍是旧值）
                        val sp = ctx.getSharedPreferences(BM_PREFS, Context.MODE_PRIVATE)
                        sp.edit()
                            .putBoolean("proxy_enabled", proxyEnabled)
                            .putString("proxy_type", proxyType)
                            .putString("proxy_host", proxyHost)
                            .putString("proxy_port", proxyPort)
                            .putString("proxy_username", proxyUsername)
                            .putString("proxy_password", proxyPassword)
                            .commit()
                        // 通知 BrowserCore 立即把代理真正注入 WebView 网络栈（Android 11+ 经 ProxyController）
                        ctx.sendBroadcast(Intent("com.ai.assistance.quro.browser.ACTION_PROXY_CHANGED"))
                        showProxySettings = false
                        val tip = if (proxyEnabled) qstr(R.string.qk_00941, (proxyType).toString(), (proxyHost).toString(), (proxyPort).toString())
                                  else qstr(R.string.qk_00942)
                        Toast.makeText(ctx, tip, Toast.LENGTH_SHORT).show()
                    }) { Text(qstr(R.string.qk_00198)) }
                },
                dismissButton = {
                    TextButton(onClick = { showProxySettings = false }) { Text(stringResource(R.string.qk_00011)) }
                },
            )
        }
    }
}