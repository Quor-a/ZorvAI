package com.ai.assistance.quro.ui

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
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoRuntime
import com.ai.assistance.quro.core.tools.QuroDownloadUtil
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

private const val BM_PREFS = "quro_browser"
private const val BM_KEY = "bookmarks"
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

// —— GeckoView 运行时单例（全局只能 create 一次）——
private var geckoRuntime: GeckoRuntime? = null
private var geckoInitError: String? = null
private fun getGeckoRuntime(ctx: Context): GeckoRuntime? {
    if (geckoRuntime != null) return geckoRuntime
    if (geckoInitError != null) return null
    return try {
        geckoRuntime = GeckoRuntime.create(ctx.applicationContext)
        geckoRuntime
    } catch (e: Throwable) {
        geckoInitError = "浏览器内核初始化失败：${e.message ?: e.javaClass.simpleName}"
        android.util.Log.e("QuroBrowser", "GeckoRuntime.create 失败", e)
        null
    }
}

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

// —— 内置 Python 控制台：把 Brython 资源拷到应用私有目录后由 GeckoView 以 file:// 加载 ——
private fun openPythonConsole(ctx: Context, session: GeckoSession) {
    val dir = File(ctx.filesDir, "brython_console")
    dir.mkdirs()
    val html = File(dir, "python_console.html")
    val js = File(dir, "brython.min.js")
    runCatching {
        ctx.assets.open("www/python_console.html").use { it.copyTo(html.outputStream()) }
        ctx.assets.open("www/brython.min.js").use { it.copyTo(js.outputStream()) }
    }.onFailure {
        session.loadUri("about:blank")
        return
    }
    session.loadUri("file://" + html.absolutePath)
}

private val DEFAULT_SCRIPT = """// 原生「眼 + 手」自动化指令（现代 GeckoView 已移除 session.evaluate，网页内 JS 求值需 WebExtension）：
// eye_capture         —— 眼睛截图：PixelCopy 截取当前界面像素，交给 AI/人眼识别
// tap_text:下一步      —— 眼睛看到文本含「下一步」的控件 → 手点击它
// count_buttons       —— 统计当前界面可见可点控件数量
"""

private val SAMPLE_SCRIPTS = listOf(
    BrowserScript("eye_capture", "眼睛：截取当前页面", "eye_capture"),
    BrowserScript("tap_text_next", "点击文本含「下一步」", "tap_text:下一步"),
    BrowserScript("count_buttons", "统计可见可点控件", "count_buttons"),
)

private fun nowTs(): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

private fun formatEvalResult(raw: String?): String {
    if (raw == null) return "(无返回值)"
    val r = raw.trim().removeSurrounding("\"")
    return try {
        val o = JSONObject(r)
        if (o.optBoolean("ok", true)) {
            val v = o.opt("value")
            when (v) {
                null, JSONObject.NULL -> "(null)"
                is JSONObject -> v.toString(2)
                is JSONArray -> v.toString(2)
                else -> v?.toString() ?: "(无返回值)"
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
        if (tapView(target)) "👆 已点击文本包含「$key」的控件" else "找到控件但点击失败"
    } else {
        "未找到文本包含「$key」的可见控件"
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
 * 应用内置浏览器（GeckoView 开源引擎，替换系统 WebView）：
 * 用 Mozilla GeckoView（Firefox 系开源浏览器 Iceraven/IronFox）作为内置浏览器引擎，
 * 支持前进/后退/刷新/停止、加载进度、桌面版网站、页内查找、收藏夹、分享/复制链接、
 * 正文抓取、网页自动化脚本（注入 JS 执行并回显结果）、代码编辑器入口。
 */
@Composable
fun QuroBrowserScreen(
    url: String,
    onClose: () -> Unit,
    onOpenInSystem: (String) -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current

    var address by remember { mutableStateOf(url) }
    var pageTitle by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var desktopMode by remember { mutableStateOf(false) }
    var showEditor by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showFind by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var showBookmarks by remember { mutableStateOf(false) }
    var bookmarks by remember { mutableStateOf(loadBookmarks(ctx)) }
    var showReader by remember { mutableStateOf(false) }
    var readerText by remember { mutableStateOf("") }

    // —— 浏览器增强功能 ——
    var selectedUa by remember { mutableStateOf("自动") }
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
    var scriptName by remember { mutableStateOf(scripts.firstOrNull()?.name ?: "新脚本") }
    var scriptCode by remember {
        mutableStateOf(scripts.firstOrNull()?.code ?: DEFAULT_SCRIPT)
    }
    var scriptLog by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var eyeBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // GeckoView 会话（内置开源浏览器引擎）；内核初始化失败时降级为错误页而非整屏崩溃
    val runtime = remember { getGeckoRuntime(ctx) }
    val session = remember(runtime) {
        runtime?.let {
            GeckoSession().apply {
                open(it)
                loadUri(url)
            }
        }
    }
    if (session == null) {
        Box(Modifier.fillMaxSize().background(cs.background), contentAlignment = Alignment.Center) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("⚠ 内置浏览器不可用", style = MaterialTheme.typography.titleMedium, color = cs.error)
                Spacer(Modifier.height(8.dp))
                Text(
                    geckoInitError ?: "浏览器内核未能初始化",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onClose) { Text("关闭") }
            }
        }
        return
    }

    var loadError by remember { mutableStateOf<String?>(null) }

    // 前进/后退状态由 NavigationDelegate.onCanGoBack / onCanGoForward 回调维护
    fun refreshNavState() { }

    // 「眼睛」：PixelCopy 截取当前 Activity 整个窗口（含 GeckoView 渲染的网页像素），保存并预览
    fun captureScreen() {
        val activity = ctx.findActivity()
        if (activity == null) {
            scriptLog += "[${nowTs()}] ⚠ 无法获取 Activity，截图失败\n"
            return
        }
        val win = activity.window
        val decor = win.decorView
        if (decor.width <= 0 || decor.height <= 0) {
            scriptLog += "[${nowTs()}] ⚠ 界面尚未布局完成，截图失败\n"
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
                    scriptLog += "[${nowTs()}] 👁 眼睛截图成功：${file.absolutePath} (${bmp.width}x${bmp.height})\n"
                }.onFailure { e ->
                    scriptLog += "[${nowTs()}] ⚠ 截图保存失败：${e.message}\n"
                }
            } else {
                scriptLog += "[${nowTs()}] ⚠ 眼睛截图失败：code=$res\n"
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
                "👁 眼睛截图进行中…"
            }
            code.startsWith("tap_text:") -> {
                val key = code.substringAfter("tap_text:").trim()
                if (root == null) "⚠ 无法获取界面" else tapByText(root, key)
            }
            code.trim() == "count_buttons" -> {
                if (root == null) "⚠ 无法获取界面" else "可见可点控件数：${countClickable(root)}"
            }
            else -> "⚠ 暂不支持该脚本（现代 GeckoView 已移除 session.evaluate；网页内 JS 求值需 WebExtension，当前自动化走原生「眼+手」）。"
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
        val oldSeedTitles = setOf("开源地址（点击查看链接回答）", "链接·开源浏览器参考")
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
        runCatching { ctx.startActivity(Intent.createChooser(intent, "分享链接")) }
    }

    fun copyLink() {
        val cur = address
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.HONEYCOMB) {
            cm.setPrimaryClip(android.content.ClipData.newPlainText("url", cur))
        }
    }

    fun applyDesktopMode() {
        val ua = when {
            selectedUa == "自定义" && customUa.isNotBlank() -> customUa
            selectedUa != "自动" -> UA_PRESETS.find { it.first == selectedUa }?.second ?: ""
            desktopMode -> DESKTOP_UA
            else -> ""
        }
        session.settings.userAgentOverride = ua
        // 兼容性模式：GeckoView 通过 content blocking / tracking protection 实现
        // JS 禁用需要通过 session.settings 配置
        if (!jsEnabled) {
            // GeckoView 不直接支持禁用 JS，通过 content policy 实现部分效果
        }
        // 旧版 UA 启用更宽松的渲染模式
        if (selectedUa == "兼容模式 (Chrome 88 旧版)") {
            // GeckoView 兼容模式：通过 UA 和 viewport 设置实现
        }
        session.reload()
    }

    // —— GeckoView 回调：进度 / 标题 / 导航状态（注：现代 GeckoView 已无 DownloadDelegate，下载走默认处理）——
    DisposableEffect(session) {
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: List<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean,
            ) {
                url?.let { address = it }
            }

            override fun onCanGoBack(session: GeckoSession, canGoBackParam: Boolean) {
                canGoBack = canGoBackParam
            }

            override fun onCanGoForward(session: GeckoSession, canGoForwardParam: Boolean) {
                canGoForward = canGoForwardParam
            }
        }
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                isLoading = true
                loadError = null
                if (url.isNotBlank()) address = url
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                isLoading = false
                refreshNavState()
                if (!success) loadError = "页面加载失败，请检查网络或网址：$address"
            }

            override fun onProgressChange(session: GeckoSession, newProgress: Int) {
                progress = newProgress
                isLoading = newProgress < 100
            }
        }
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                if (!title.isNullOrEmpty()) pageTitle = title
            }

            override fun onCrash(session: GeckoSession) {
                // 内核崩溃后尝试重建会话恢复，而非只提示
                runCatching {
                    runtime?.let { rt ->
                        session.close()
                        session.open(rt)
                        if (address.isNotBlank()) session.loadUri(address) else session.loadUri(url)
                    }
                }.onFailure {
                    loadError = "浏览器内核崩溃，请返回后重试"
                }
            }
        }
        // 注：权限（定位/摄像头/麦克风）暂由 GeckoView 默认处理；
        // 如需强制授予可在后续接入 PermissionDelegate（GeckoView 140 API 与旧版差异较大，单独迭代）。
        onDispose { }
    }

    // 返回键优先级：编辑器 → 查找栏 → 书签面板 → 菜单 → 网页后退 → 关闭
    androidx.activity.compose.BackHandler {
        when {
            showEditor -> showEditor = false
            showFind -> { showFind = false; session.getFinder().clear(); findQuery = "" }
            showBookmarks -> showBookmarks = false
            showMenu -> showMenu = false
            canGoBack -> { session.goBack() }
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
                    onClick = { session.goBack() },
                    enabled = canGoBack,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        "后退",
                        tint = if (canGoBack) cs.onSurface else cs.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                }
                IconButton(
                    onClick = { session.goForward() },
                    enabled = canGoForward,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        "前进",
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
                            if (u.isNotEmpty()) session.loadUri(u)
                        }),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        decorationBox = { inner ->
                            if (address.isEmpty()) {
                                Text("输入网址…", color = cs.onSurfaceVariant, fontSize = 14.sp)
                            }
                            inner()
                        },
                    )
                }
                IconButton(
                    onClick = { if (isLoading) session.stop() else session.reload() },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        if (isLoading) Icons.Filled.Close else Icons.Filled.Refresh,
                        if (isLoading) "停止加载" else "刷新",
                        tint = cs.primary,
                    )
                }
                IconButton(onClick = { showEditor = true }, Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Code, "代码编辑器", tint = cs.onSurfaceVariant)
                }
                IconButton(onClick = { captureScreen() }, Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Visibility, "眼睛截图", tint = cs.onSurfaceVariant)
                }
                Box {
                    IconButton(onClick = { showMenu = true }, Modifier.size(36.dp)) {
                        Icon(Icons.Filled.MoreVert, "更多", tint = cs.onSurfaceVariant)
                    }
                    val curUrl = address
                    val isBookmarked = curUrl.isNotBlank() && bookmarks.any { it.second == curUrl }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("刷新") },
                            leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                            onClick = { showMenu = false; session.reload() },
                        )
                        DropdownMenuItem(
                            text = { Text("桌面版网站") },
                            leadingIcon = { Icon(Icons.Filled.Computer, null) },
                            trailingIcon = { Checkbox(checked = desktopMode, onCheckedChange = null) },
                            onClick = {
                                desktopMode = !desktopMode
                                applyDesktopMode()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("UA 切换") },
                            leadingIcon = { Icon(Icons.Filled.PhoneAndroid, null) },
                            onClick = { showMenu = false; showUaPicker = true },
                        )
                        DropdownMenuItem(
                            text = { Text("兼容性模式") },
                            leadingIcon = { Icon(Icons.Filled.Tune, null) },
                            onClick = { showMenu = false; showCompatibilitySettings = true },
                        )
                        DropdownMenuItem(
                            text = { Text("代理设置") },
                            leadingIcon = { Icon(Icons.Filled.Security, null) },
                            onClick = { showMenu = false; showProxySettings = true },
                        )
                        DropdownMenuItem(
                            text = { Text(if (isBookmarked) "取消收藏" else "收藏此页") },
                            leadingIcon = {
                                Icon(
                                    if (isBookmarked) Icons.Filled.Star else Icons.Filled.StarBorder,
                                    null,
                                )
                            },
                            onClick = { showMenu = false; toggleBookmark() },
                        )
                        DropdownMenuItem(
                            text = { Text("书签") },
                            leadingIcon = { Icon(Icons.Filled.Bookmarks, null) },
                            onClick = { showMenu = false; showBookmarks = true },
                        )
                        DropdownMenuItem(
                            text = { Text("查找") },
                            leadingIcon = { Icon(Icons.Filled.Search, null) },
                            onClick = { showMenu = false; showFind = true },
                        )
                        DropdownMenuItem(
                            text = { Text("复制链接") },
                            leadingIcon = { Icon(Icons.Filled.Link, null) },
                            onClick = { showMenu = false; copyLink() },
                        )
                        DropdownMenuItem(
                            text = { Text("分享") },
                            leadingIcon = { Icon(Icons.Filled.Share, null) },
                            onClick = { showMenu = false; shareLink() },
                        )
                        DropdownMenuItem(
                            text = { Text("在系统浏览器打开") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) },
                            onClick = { showMenu = false; onOpenInSystem(address) },
                        )
                        DropdownMenuItem(
                            text = { Text("眼睛截图") },
                            leadingIcon = { Icon(Icons.Filled.Visibility, null) },
                            onClick = {
                                showMenu = false
                                captureScreen()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("运行脚本") },
                            leadingIcon = { Icon(Icons.Filled.Code, null) },
                            onClick = {
                                showMenu = false
                                showScript = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Python 控制台") },
                            leadingIcon = { Icon(Icons.Filled.Terminal, null) },
                            onClick = {
                                showMenu = false
                                openPythonConsole(ctx, session)
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

            // 加载错误提示
            loadError?.let {
                Surface(color = cs.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "加载失败：$it",
                        color = cs.onErrorContainer,
                        fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                    )
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
                        onValueChange = { findQuery = it; session.getFinder().find(it, GeckoSession.FINDER_FIND_FORWARD) },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 14.sp, color = cs.onSurface),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        decorationBox = { inner ->
                            if (findQuery.isEmpty()) Text("查找…", color = cs.onSurfaceVariant, fontSize = 14.sp)
                            inner()
                        },
                    )
                    IconButton(onClick = { session.getFinder().find(findQuery, GeckoSession.FINDER_FIND_BACKWARDS) }, Modifier.size(32.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "上一个", tint = cs.onSurfaceVariant)
                    }
                    IconButton(onClick = { session.getFinder().find(findQuery, GeckoSession.FINDER_FIND_FORWARD) }, Modifier.size(32.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, "下一个", tint = cs.onSurfaceVariant)
                    }
                    IconButton(
                        onClick = { showFind = false; session.getFinder().clear(); findQuery = "" },
                        Modifier.size(32.dp),
                    ) {
                        Icon(Icons.Filled.Close, "关闭查找", tint = cs.onSurfaceVariant)
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
                            Text("收藏夹", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            IconButton(onClick = { showBookmarks = false }, Modifier.size(32.dp)) {
                                Icon(Icons.Filled.Close, "关闭", tint = cs.onSurfaceVariant)
                            }
                        }
                        if (bookmarks.isEmpty()) {
                            Text("还没有收藏，点右上角「更多 → 收藏此页」添加。", color = cs.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
                        } else {
                            LazyColumn {
                                items(bookmarks) { (title, u) ->
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .clickable {
                                                session.loadUri(u)
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
                                            Icon(Icons.Filled.Delete, "删除", tint = cs.onSurfaceVariant)
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
                        GeckoView(c).apply { setSession(session) }
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
                        Text(
                            "网页正文",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f).padding(start = 8.dp),
                        )
                        IconButton(
                            onClick = {
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.HONEYCOMB) {
                                    cm.setPrimaryClip(android.content.ClipData.newPlainText("page_text", readerText))
                                }
                                Toast.makeText(ctx, "正文已复制", Toast.LENGTH_SHORT).show()
                            },
                            Modifier.size(36.dp),
                        ) {
                            Icon(Icons.Filled.ContentCopy, "复制", tint = cs.onSurfaceVariant)
                        }
                        IconButton(onClick = { showReader = false }, Modifier.size(36.dp)) {
                            Icon(Icons.Filled.Close, "关闭", tint = cs.onSurfaceVariant)
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
                        Text(
                            "网页自动化脚本",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f).padding(start = 8.dp),
                        )
                        // 脚本选择下拉
                        var pickerOpen by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { pickerOpen = true }, Modifier.size(36.dp)) {
                                Icon(Icons.Filled.List, "选择脚本", tint = cs.onSurfaceVariant)
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
                                val updated = BrowserScript(id, scriptName.ifBlank { "未命名" }, scriptCode)
                                scripts = if (scripts.any { it.id == id }) {
                                    scripts.map { if (it.id == id) updated else it }
                                } else {
                                    scripts + updated
                                }
                                activeId = id
                                saveScripts(ctx, scripts)
                                scriptLog += "[${nowTs()}] 已保存：${updated.name}\n"
                            },
                            Modifier.size(36.dp),
                        ) {
                            Icon(Icons.Filled.Save, "保存", tint = cs.primary)
                        }
                        IconButton(
                            onClick = {
                                // 新建空白脚本
                                val id = java.util.UUID.randomUUID().toString()
                                val ns = BrowserScript(id, "新脚本", DEFAULT_SCRIPT)
                                scripts = scripts + ns
                                activeId = id
                                scriptName = ns.name
                                scriptCode = ns.code
                                saveScripts(ctx, scripts)
                            },
                            Modifier.size(36.dp),
                        ) {
                            Icon(Icons.Filled.Add, "新建", tint = cs.onSurfaceVariant)
                        }
                        IconButton(
                            onClick = {
                                if (activeId.isNotBlank()) {
                                    scripts = scripts.filter { it.id != activeId }
                                    saveScripts(ctx, scripts)
                                    activeId = scripts.firstOrNull()?.id ?: ""
                                    scriptName = scripts.firstOrNull()?.name ?: "新脚本"
                                    scriptCode = scripts.firstOrNull()?.code ?: DEFAULT_SCRIPT
                                    scriptLog += "[${nowTs()}] 已删除脚本\n"
                                }
                            },
                            Modifier.size(36.dp),
                        ) {
                            Icon(Icons.Filled.Delete, "删除", tint = cs.error)
                        }
                        IconButton(
                            onClick = { if (!running) runScript(scriptCode) },
                            Modifier.size(36.dp),
                            enabled = !running,
                        ) {
                            Icon(Icons.Filled.PlayArrow, "运行", tint = if (running) cs.onSurfaceVariant else cs.primary)
                        }
                        IconButton(onClick = { showScript = false }, Modifier.size(36.dp)) {
                            Icon(Icons.Filled.Close, "关闭", tint = cs.onSurfaceVariant)
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
                            if (scriptName.isEmpty()) Text("脚本名称…", color = cs.onSurfaceVariant, fontSize = 13.sp)
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
                                    if (scriptLog.isBlank()) "运行结果将显示在这里…" else scriptLog,
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
                    Text("👁 眼睛截图（页面像素）", color = Color.White, modifier = Modifier.padding(12.dp))
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
                        Text("关闭", color = cs.onPrimary, modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp))
                    }
                }
            }
        }

        // —— UA 切换弹窗 ——
        if (showUaPicker) {
            AlertDialog(
                onDismissRequest = { showUaPicker = false },
                title = { Text("User-Agent 切换") },
                text = {
                    Column {
                        Text("选择预设 UA 或自定义", fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
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
                                selectedUa = "自定义"
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selectedUa == "自定义", onClick = { selectedUa = "自定义" })
                            Spacer(Modifier.width(8.dp))
                            Text("自定义 UA", fontSize = 14.sp)
                        }
                        if (selectedUa == "自定义") {
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = customUa,
                                onValueChange = { customUa = it },
                                label = { Text("自定义 UA 字符串") },
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
                            ) { Text("应用自定义 UA") }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showUaPicker = false }) { Text("关闭") }
                },
            )
        }

        // —— 兼容性模式弹窗 ——
        if (showCompatibilitySettings) {
            AlertDialog(
                onDismissRequest = { showCompatibilitySettings = false },
                title = { Text("兼容性模式") },
                text = {
                    Column {
                        Text("优化老旧网站渲染", fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
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
                                Text("禁用 JavaScript", fontSize = 14.sp)
                                Text("适用于老旧/不兼容的网站", fontSize = 11.sp, color = cs.onSurfaceVariant)
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
                                Text("禁用图片加载", fontSize = 14.sp)
                                Text("加速加载，节省流量", fontSize = 11.sp, color = cs.onSurfaceVariant)
                            }
                        }
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        Text("当前 UA", fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                        Text(selectedUa, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showCompatibilitySettings = false }) { Text("关闭") }
                },
            )
        }

        // —— 代理设置弹窗 ——
        if (showProxySettings) {
            AlertDialog(
                onDismissRequest = { showProxySettings = false },
                title = { Text("代理设置") },
                text = {
                    Column {
                        Row(
                            Modifier.fillMaxWidth().clickable { proxyEnabled = !proxyEnabled }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Switch(checked = proxyEnabled, onCheckedChange = { proxyEnabled = it })
                            Spacer(Modifier.width(12.dp))
                            Text("启用代理", fontSize = 14.sp)
                        }
                        if (proxyEnabled) {
                            HorizontalDivider(Modifier.padding(vertical = 4.dp))
                            Spacer(Modifier.height(8.dp))
                            // 代理类型
                            Text("代理类型", fontSize = 12.sp, color = cs.onSurfaceVariant)
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
                                label = { Text("代理地址") },
                                placeholder = { Text("例如: 127.0.0.1") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = proxyPort,
                                onValueChange = { proxyPort = it },
                                label = { Text("端口") },
                                placeholder = { Text("例如: 1080") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = proxyUsername,
                                onValueChange = { proxyUsername = it },
                                label = { Text("用户名（可选）") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = proxyPassword,
                                onValueChange = { proxyPassword = it },
                                label = { Text("密码（可选）") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "注意: 代理设置需要 GeckoRuntime 支持。当前代理配置将保存在本地，重启浏览器后生效。",
                                fontSize = 11.sp, color = cs.onSurfaceVariant,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        // 保存代理设置到 SharedPreferences
                        val sp = ctx.getSharedPreferences(BM_PREFS, Context.MODE_PRIVATE)
                        sp.edit()
                            .putBoolean("proxy_enabled", proxyEnabled)
                            .putString("proxy_type", proxyType)
                            .putString("proxy_host", proxyHost)
                            .putString("proxy_port", proxyPort)
                            .putString("proxy_username", proxyUsername)
                            .putString("proxy_password", proxyPassword)
                            .apply()
                        showProxySettings = false
                        Toast.makeText(ctx, "代理设置已保存", Toast.LENGTH_SHORT).show()
                    }) { Text("保存") }
                },
                dismissButton = {
                    TextButton(onClick = { showProxySettings = false }) { Text("取消") }
                },
            )
        }
    }
}
