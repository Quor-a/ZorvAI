package com.ai.assistance.quro.core.scripting

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.*

/**
 * 宿主 API 分发器（SandboxPackage / code_runner / ToolPkg 的 Tools.* 网关）。
 *
 * JS 侧 prelude.js 里的 `Tools.Files / Tools.Net / Tools.System / Tools.calc` 全部经
 * QuickJS 桥的 `hostCallApi(api, paramsJson)` 通道到达这里，按 api 前缀分发。
 *
 * 安全模型：
 *  - fs.* 全部限制在 [sandboxRoot] 内（路径穿越直接拒绝）；
 *  - net.fetch 强制超时（默认 15s）+ 1MB 响应上限，防脚本卡死 / 撑爆内存；
 *  - system.* 只暴露信息查询 + 剪贴板 + 通知，无 exec / os / root；
 *  - calc.eval 走白名单数学表达式解析（见 [MathExpr]），不是裸 eval；
 *  - media.* 只在用户已授权 MediaProjection（长按「看懂屏幕」）后可用，绝不静默申请授权。
 */
class HostApiDispatcher(
    private val appContext: Context,
    /** 脚本可访问的根目录（通常是当前工作区）。所有 fs.* 路径都在此内解析。 */
    private val sandboxRoot: File,
) {
    /** 单次 HTTP 响应最大字节数（防脚本把内存打爆）。 */
    var maxResponseBytes: Int = 1 shl 20

    fun dispatch(api: String, paramsJson: String): String {
        val params = runCatching { JSONObject(paramsJson) }.getOrElse { JSONObject() }
        val result = runCatching { route(api, params) }
            .getOrElse { e -> error(e.message ?: e.javaClass.simpleName) }
        return result.toString()
    }

    private fun error(msg: String): JSONObject = JSONObject().put("error", msg)

    private fun route(api: String, p: JSONObject): JSONObject = when (api) {
        /* ---------- Tools.Files ---------- */
        "fs.read" -> {
            val f = resolve(p.optString("path"))
                ?: return error("路径越界或非法：${p.optString("path")}")
            if (!f.isFile) error("文件不存在：${p.optString("path")}")
            else JSONObject().put("content", f.readText())
        }
        "fs.readModule" -> readModule(p.optString("path"))
        "fs.write" -> {
            val f = resolve(p.optString("path")) ?: return error("路径越界或非法")
            f.parentFile?.mkdirs()
            f.writeText(p.optString("content"))
            JSONObject().put("bytes", f.length())
        }
        "fs.append" -> {
            val f = resolve(p.optString("path")) ?: return error("路径越界或非法")
            f.parentFile?.mkdirs()
            f.appendText(p.optString("content"))
            JSONObject().put("ok", true)
        }
        "fs.list" -> {
            val dir = resolve(p.optString("path").ifBlank { "." }) ?: return error("路径越界或非法")
            if (!dir.isDirectory) return error("不是目录：${p.optString("path")}")
            val arr = org.json.JSONArray()
            dir.listFiles()?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name })?.forEach {
                arr.put(JSONObject()
                    .put("name", it.name)
                    .put("isDir", it.isDirectory)
                    .put("size", it.length()))
            }
            JSONObject().put("entries", arr)
        }
        "fs.exists" -> JSONObject().put("exists", resolve(p.optString("path"))?.exists() == true)
        "fs.remove" -> {
            val f = resolve(p.optString("path")) ?: return error("路径越界或非法")
            val ok = if (f.isDirectory) f.deleteRecursively() else f.delete()
            JSONObject().put("ok", ok)
        }
        "fs.mkdir" -> {
            val f = resolve(p.optString("path")) ?: return error("路径越界或非法")
            JSONObject().put("ok", f.mkdirs() || f.isDirectory)
        }
        "fs.stat" -> {
            val f = resolve(p.optString("path")) ?: return error("路径越界或非法")
            if (!f.exists()) return error("不存在：${p.optString("path")}")
            JSONObject()
                .put("name", f.name)
                .put("isDir", f.isDirectory)
                .put("size", f.length())
                .put("lastModified", f.lastModified())
        }

        /* ---------- Tools.Net ---------- */
        "net.fetch" -> httpFetch(p)

        /* ---------- Tools.System ---------- */
        "system.info" -> {
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            @Suppress("DEPRECATION") val mi = android.app.ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
            JSONObject()
                .put("device", Build.DEVICE)
                .put("model", Build.MODEL)
                .put("brand", Build.BRAND)
                .put("androidVersion", Build.VERSION.RELEASE)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("app", "ZorvAI")
                .put("screen", "${appContext.resources.displayMetrics.widthPixels}x${appContext.resources.displayMetrics.heightPixels}")
                .put("totalMemMB", mi?.totalMem?.shr(20) ?: -1)
                .put("availMemMB", mi?.availMem?.shr(20) ?: -1)
        }
        "system.env" -> JSONObject()
            .put("workspaceRoot", sandboxRoot.absolutePath)
            .put("platform", "android-quickjs")
        "system.clipboard" -> {
            val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = p.optString("text", "")
            if (p.has("text")) {
                cm.setPrimaryClip(ClipData.newPlainText("ZorvAI Script", text))
                JSONObject().put("ok", true)
            } else {
                JSONObject().put("text", cm.primaryClip?.getItemAt(0)?.text?.toString() ?: "")
            }
        }
        "system.notify" -> runCatching {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val ch = android.app.NotificationChannel("script_notify", "脚本通知", android.app.NotificationManager.IMPORTANCE_DEFAULT)
            nm.createNotificationChannel(ch)
            val n = android.app.Notification.Builder(appContext, "script_notify")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(p.optString("title", "ZorvAI Script"))
                .setContentText(p.optString("message", ""))
                .build()
            nm.notify((System.currentTimeMillis() % 100000).toInt(), n)
            JSONObject().put("ok", true)
        }.getOrElse { error(it.message ?: "通知失败（可能缺少通知权限）") }

        /* ---------- Tools.calc ---------- */
        "calc.eval" -> {
            val v = MathExpr.eval(p.optString("expr"))
            if (v.isNaN()) error("表达式无法求值：${p.optString("expr")}")
            else JSONObject().put("value", v)
        }

        /* ---------- Tools.Media（MediaProjection 屏幕捕获） ---------- */
        "media.state" -> {
            val c = com.ai.assistance.quro.core.vision.ScreenCaptureController.shared(appContext)
            JSONObject()
                .put("attached", c.isAttached)
                .put("running", c.isRunning)
                .put("state", c.state.value.name)
                .put("lastError", c.lastError.value ?: JSONObject.NULL)
        }
        "media.screenshot" -> mediaScreenshot(p)

        /* ---------- Tools.Git（本地仓库，JGit；路径同样限制在 sandboxRoot 内） ---------- */
        "git.init" -> {
            val d = resolve(p.optString("path").ifBlank { "." }) ?: return error("路径越界或非法")
            GitHostApi.init(d)
        }
        "git.status" -> gitOp(p) { GitHostApi.status(it) }
        "git.add" -> gitOp(p) { d ->
            val filesArr = p.optJSONArray("files")
            val files = if (filesArr == null) emptyList() else (0 until filesArr.length()).map { filesArr.optString(it) }
            GitHostApi.add(d, files)
        }
        "git.commit" -> gitOp(p) { d ->
            GitHostApi.commit(d, p.optString("message"), p.optString("name"), p.optString("email"))
        }
        "git.log" -> gitOp(p) { d -> GitHostApi.log(d, p.optInt("max", 20)) }
        "git.branchList" -> gitOp(p) { GitHostApi.branchList(it) }
        "git.branchCreate" -> gitOp(p) { d ->
            GitHostApi.branchCreate(d, p.optString("name"), p.optBoolean("checkout", false))
        }
        "git.checkout" -> gitOp(p) { d -> GitHostApi.checkout(d, p.optString("name")) }

        else -> error("未知宿主 API：$api")
    }

    /** git.* 公共前置：解析仓库目录（默认工作区根）并校验 .git 存在。 */
    private inline fun gitOp(p: JSONObject, op: (java.io.File) -> JSONObject): JSONObject {
        val d = resolve(p.optString("path").ifBlank { "." }) ?: return error("路径越界或非法")
        if (!java.io.File(d, ".git").exists()) {
            return error("不是 Git 仓库（缺 .git）：先 Tools.Git.init(path)")
        }
        return op(d)
    }

    /* ===================== fs 模块解析（CommonJS） ===================== */

    /**
     * require(path) 的模块解析：依次尝试
     * path / path.js / path.ts / path/index.js / path/index.ts / path/package.json(main)。
     * .ts 源码经 [TsTranspiler] 转译后返回。返回 {code, file} 或 {error}。
     */
    private fun readModule(rawPath: String): JSONObject {
        val base = resolve(rawPath) ?: return error("路径越界或非法：$rawPath")
        val candidates = sequenceOf(
            base,
            File(base.path + ".js"),
            File(base.path + ".ts"),
            File(base, "index.js"),
            File(base, "index.ts"),
        )
        for (c in candidates) {
            if (c.isFile) {
                val src = c.readText()
                val code = if (c.extension.equals("ts", true)) TsTranspiler.transpile(src) else src
                return JSONObject().put("code", code).put("file", c.relativeToOrSelf(sandboxRoot).path)
            }
        }
        // package.json main 入口
        val pkg = File(base, "package.json")
        if (pkg.isFile) {
            val main = runCatching { JSONObject(pkg.readText()).optString("main") }.getOrDefault("")
            if (main.isNotBlank()) return readModule(File(base, main).relativeToOrSelf(sandboxRoot).path)
        }
        return error("找不到模块：$rawPath")
    }

    /** 路径安全解析：拒绝越出 [sandboxRoot] 的路径（../ 穿越 / 绝对路径白名单外）。 */
    private fun resolve(raw: String): File? {
        if (raw.isBlank()) return null
        val f = File(raw)
        val resolved = if (f.isAbsolute) {
            // 绝对路径必须在 sandboxRoot 内（用户显式传工作区绝对路径的场景）
            f
        } else File(sandboxRoot, raw)
        val canonical = runCatching { resolved.canonicalFile }.getOrElse { return null }
        val root = runCatching { sandboxRoot.canonicalFile }.getOrElse { return null }
        // root 自身或其子路径才放行
        if (canonical == root || canonical.path.startsWith(root.path + File.separator)) return canonical
        return null
    }

    /* ===================== Tools.Media：MediaProjection 截屏 ===================== */

    /**
     * media.screenshot 的后端：经全局共享的 [ScreenCaptureController]（用户在对话控制条
     * 长按「看懂屏幕」授权过的 MediaProjection）抓一帧，缩放后存 JPEG 到工作区 screenshots/。
     * 参数：{maxEdge=1280, quality=80, filename="shot_<ts>.jpg"}；返回
     * {ok, path(工作区相对), file(绝对路径), width, height, bytes}。
     */
    private fun mediaScreenshot(p: JSONObject): JSONObject {
        val c = com.ai.assistance.quro.core.vision.ScreenCaptureController.shared(appContext)
        if (!c.isAttached) {
            return error("未授权屏幕捕获：请在对话控制条长按「看懂屏幕」，系统弹窗允许后重试")
        }
        if (!c.isRunning) c.start()
        if (!c.isRunning) {
            return error("屏幕捕获启动失败：${c.lastError.value ?: "未知原因"}（可重新长按「看懂屏幕」再授权）")
        }
        // VirtualDisplay 首帧需要一点时间，短轮询等待（最长 ~1.2s，沙箱整体超时兜底）
        var bmp: android.graphics.Bitmap? = null
        var tries = 0
        while (tries++ < 12) {
            bmp = c.captureLatest()
            if (bmp != null) break
            try { Thread.sleep(100) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
        }
        if (bmp == null) {
            return error("抓帧失败：屏幕捕获未就绪或授权已失效（可重新长按「看懂屏幕」授权）")
        }
        try {
            val maxEdge = p.optInt("maxEdge", 1280).coerceIn(320, 4096)
            val quality = p.optInt("quality", 80).coerceIn(10, 100)
            val w = bmp.width
            val h = bmp.height
            val scale = (maxOf(w, h) / maxEdge).coerceAtLeast(1)
            val target = if (scale > 1) {
                android.graphics.Bitmap.createScaledBitmap(bmp, w / scale, h / scale, true).also { bmp.recycle() }
            } else bmp
            val dir = java.io.File(sandboxRoot, "screenshots").apply { mkdirs() }
            val raw = p.optString("filename", "").trim()
            val name = when {
                raw.isEmpty() -> "shot_${System.currentTimeMillis()}.jpg"
                Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$").matches(raw) -> raw
                else -> return error("filename 不合法（仅英文/数字/横线/下划线/点）：$raw")
            }
            val out = java.io.File(dir, name)
            java.io.FileOutputStream(out).use { target.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, it) }
            val result = JSONObject()
                .put("ok", true)
                .put("path", "screenshots/$name")
                .put("file", out.absolutePath)
                .put("width", target.width)
                .put("height", target.height)
                .put("bytes", out.length())
            target.recycle()
            return result
        } catch (e: Exception) {
            return error("截图失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /* ===================== net.fetch ===================== */

    private fun httpFetch(p: JSONObject): JSONObject {
        val url = p.optString("url")
        if (url.isBlank()) return error("缺少 url")
        if (!url.startsWith("http://") && !url.startsWith("https://")) return error("仅支持 http/https")
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = p.optString("method", "GET").uppercase()
            conn.connectTimeout = p.optInt("timeoutMs", 15000)
            conn.readTimeout = p.optInt("timeoutMs", 15000)
            conn.instanceFollowRedirects = true
            val headers = p.optJSONObject("headers")
            headers?.keys()?.forEach { k -> conn.setRequestProperty(k, headers.optString(k)) }
            val body = p.optString("body", "")
            if (body.isNotEmpty()) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", headers?.optString("Content-Type") ?: "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val status = conn.responseCode
            val stream = if (status in 200..399) conn.inputStream else conn.errorStream
            val text = stream?.use { s ->
                val baos = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val r = s.read(buf)
                    if (r < 0) break
                    total += r
                    if (total > maxResponseBytes) { baos.write(buf, 0, r - (total - maxResponseBytes)); break }
                    baos.write(buf, 0, r)
                }
                baos.toString("UTF-8")
            } ?: ""
            val respHeaders = JSONObject()
            conn.headerFields?.forEach { (k, v) -> if (k != null && v.isNotEmpty()) respHeaders.put(k.lowercase(), v.joinToString(", ")) }
            JSONObject()
                .put("ok", status in 200..299)
                .put("status", status)
                .put("body", text)
                .put("headers", respHeaders)
        } catch (e: Exception) {
            error("请求失败：${e.message}")
        }
    }
}

/**
 * 安全数学表达式求值（Tools.calc.eval 的后端）：
 * 递归下降解析 + 白名单函数/常量，支持 + - * / % ^ ( ) 与
 * sin cos tan asin acos atan sqrt pow log ln abs round floor ceil exp min max PI E。
 * 不是 eval，任意代码无法注入。
 */
object MathExpr {
    private var s = ""
    private var pos = 0

    fun eval(expr: String): Double {
        s = expr.replace("×", "*").replace("÷", "/").replace("π", "PI")
        pos = 0
        return try { parseExpr() } catch (e: Exception) { Double.NaN }
    }

    private fun parseExpr(): Double {
        var v = parseTerm()
        while (true) {
            skip()
            when (s.getOrNull(pos)) {
                '+' -> { pos++; v += parseTerm() }
                '-' -> { pos++; v -= parseTerm() }
                else -> return v
            }
        }
    }

    private fun parseTerm(): Double {
        var v = parsePow()
        while (true) {
            skip()
            when (s.getOrNull(pos)) {
                '*', '×' -> { pos++; v *= parsePow() }
                '/', '÷' -> { pos++; v /= parsePow() }
                '%' -> { pos++; v %= parsePow() }
                else -> return v
            }
        }
    }

    private fun parsePow(): Double {
        val base = parseUnary()
        skip()
        return if (s.getOrNull(pos) == '^') { pos++; base.pow(parsePow()) } else base
    }

    private fun parseUnary(): Double {
        skip()
        return when (s.getOrNull(pos)) {
            '-' -> { pos++; -parseUnary() }
            '+' -> { pos++; parseUnary() }
            else -> parseAtom()
        }
    }

    private fun parseAtom(): Double {
        skip()
        val c = s.getOrNull(pos) ?: return Double.NaN
        if (c == '(') {
            pos++
            val v = parseExpr()
            skip()
            if (s.getOrNull(pos) == ')') pos++
            return v
        }
        if (c.isDigit() || c == '.') return parseNumber()
        // 标识符：常量或函数调用
        val start = pos
        while (pos < s.length && (s[pos].isLetterOrDigit() || s[pos] == '_')) pos++
        val name = s.substring(start, pos).lowercase()
        return when (name) {
            "pi" -> PI
            "e" -> E
            else -> {
                skip()
                if (s.getOrNull(pos) == '(') {
                    pos++
                    val args = ArrayList<Double>()
                    if (s.getOrNull(pos) != ')') {
                        args.add(parseExpr())
                        while (true) {
                            skip()
                            if (s.getOrNull(pos) == ',') { pos++; args.add(parseExpr()) } else break
                        }
                    }
                    skip()
                    if (s.getOrNull(pos) == ')') pos++
                    callFn(name, args)
                } else Double.NaN
            }
        }
    }

    private fun callFn(name: String, a: List<Double>): Double = when (name) {
        "sin" -> sin(a.getOrNull(0) ?: return Double.NaN)
        "cos" -> cos(a.getOrNull(0) ?: return Double.NaN)
        "tan" -> tan(a.getOrNull(0) ?: return Double.NaN)
        "asin" -> asin(a.getOrNull(0) ?: return Double.NaN)
        "acos" -> acos(a.getOrNull(0) ?: return Double.NaN)
        "atan" -> atan(a.getOrNull(0) ?: return Double.NaN)
        "sqrt" -> sqrt(a.getOrNull(0) ?: return Double.NaN)
        "cbrt" -> Math.cbrt(a.getOrNull(0) ?: return Double.NaN)
        "pow" -> (a.getOrNull(0) ?: return Double.NaN).pow(a.getOrNull(1) ?: return Double.NaN)
        "log", "log10" -> log10(a.getOrNull(0) ?: return Double.NaN)
        "ln", "log2" -> ln(a.getOrNull(0) ?: return Double.NaN)
        "abs" -> abs(a.getOrNull(0) ?: return Double.NaN)
        "round" -> round(a.getOrNull(0) ?: return Double.NaN)
        "floor" -> floor(a.getOrNull(0) ?: return Double.NaN)
        "ceil" -> ceil(a.getOrNull(0) ?: return Double.NaN)
        "exp" -> exp(a.getOrNull(0) ?: return Double.NaN)
        "min" -> a.minOrNull() ?: Double.NaN
        "max" -> a.maxOrNull() ?: Double.NaN
        else -> Double.NaN
    }

    private fun parseNumber(): Double {
        val start = pos
        while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
        return s.substring(start, pos).toDoubleOrNull() ?: Double.NaN
    }

    private fun skip() { while (pos < s.length && s[pos].isWhitespace()) pos++ }
}
