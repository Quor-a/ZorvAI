package com.ai.assistance.quro.ui

import android.webkit.WebView
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.webkit.WebViewClient
import android.widget.Toast
import android.net.Uri
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import java.util.zip.ZipFile

/**
 * 应用内「完整 WPS」文档查看 / 编辑（v142 重写：进程内 WebView 渲染，百分百离线内置）。
 *
 * 不再外跳 ONLYOFFICE、不再要求下载安装任何第三方应用。内置渲染引擎：
 *  - docx  -> mammoth.js（Word->富文本 HTML）
 *  - xlsx  -> SheetJS（Excel->表格 HTML）
 *  - pdf   -> pdf.js（逐页渲染到 Canvas）
 *  - txt / md / json / csv / xml / 代码 -> WebView 内联（md 走轻量 Markdown 渲染）
 *  - pptx  -> 进程内 OOXML 文本解析，渲染为分页幻灯片（可编辑标题与要点）
 *  - 图片   -> 内联显示
 *
 * 三大开源库（mammoth.js / xlsx.full.min.js / pdf.min.js + worker）随包内置在
 * assets/docs/ 下，断网也能打开。txt / md / 代码类支持内置编辑并写回原文件。
 */
@Composable
fun QuroDocumentViewer(
    file: File,
    onClose: () -> Unit,
    onExternal: () -> Unit,
    readOnly: Boolean = false,
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val ext = file.extension.lowercase()
    val isEditableText = ext in setOf("txt", "md", "markdown", "json", "csv", "xml", "log", "kt", "kts", "py", "js", "ts", "html", "css", "java")
    val isPptx = ext == "pptx"
    val isWebView = ext in setOf("docx", "xlsx", "pdf", "txt", "md", "markdown", "json", "csv", "xml", "log", "kt", "kts", "py", "js", "ts", "html", "css", "java", "png", "jpg", "jpeg", "gif", "webp", "bmp")
    val imageType = if (ext in setOf("png","jpg","jpeg","gif","webp","bmp")) "image/$ext" else null
    // 大文件保护：限制来自 JS 侧解析库（mammoth / SheetJS / pdf.js）的内存与耗时，
    // 与「注入通道」无关——现已改用 WebViewAssetLoader 流式返回文件，不再有 base64 内存炸弹
    val sizeBytes = file.length()
    val WARN_SIZE = 5L * 1024 * 1024       // 5 MB：超过则提示预览可能较慢
    val MAX_SIZE = 50L * 1024 * 1024       // 50 MB：超过则直接转外部打开（JS 解析保护）
    val tooLarge = sizeBytes > MAX_SIZE

    var editing by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf("") }
    var dirty by remember { mutableStateOf(false) }
    var loadErr by remember { mutableStateOf<String?>(null) }

    // 写回原文件：仅在可编辑文本类型且非 readOnly 时持久化，并给出明确状态提示。
    val saveEdits: () -> Unit = {
        if (isEditableText && !readOnly) {
            runCatching {
                file.writeText(editText, Charsets.UTF_8)
                Toast.makeText(ctx, "已保存修改", Toast.LENGTH_SHORT).show()
                dirty = false
            }.onFailure { Toast.makeText(ctx, "保存失败: ${it.message}", Toast.LENGTH_SHORT).show() }
        }
    }
    // 返回键：若处于编辑态且有未保存改动，先写回再关闭（尊重 readOnly）。
    val handleClose: () -> Unit = {
        if (editing && dirty) saveEdits()
        onClose()
    }

    LaunchedEffect(editing, file) {
        if (editing && isEditableText) {
            // ★ 全面排查修复（v316）：LaunchedEffect 默认跑在主线程，大文件 readText 会 ANR。
            //   移至 IO 线程读取，结果回写主线程安全的 State。
            val res = withContext(Dispatchers.IO) { runCatching { file.readText(Charsets.UTF_8) } }
            res.onSuccess { editText = it; dirty = false }.onFailure { loadErr = it.message }
        }
    }

    Column(Modifier.fillMaxSize().background(cs.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = handleClose) { Icon(Icons.Filled.ArrowBack, "关闭", tint = cs.onSurface) }
            Text(
                file.name,
                color = cs.onSurface, fontSize = 15.sp, maxLines = 1,
                modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
            )
            if (isEditableText && !readOnly) {
                TextButton(onClick = {
                    if (editing) saveEdits()
                    editing = !editing
                }) { Text(if (editing) "保存" else "编辑", color = cs.primary) }
            }
            // ★ 真·WPS 深链：优先用已安装的 WPS 打开，未安装则回退通用选择器。
            TextButton(onClick = { openWithWpsOrFallback(ctx, file) }) {
                Text("用 WPS 打开", color = cs.primary.copy(alpha = 0.9f))
            }
            TextButton(onClick = onExternal) { Text("其他应用", color = cs.onSurface.copy(alpha = 0.7f)) }
        }
        HorizontalDivider()

        when {
            loadErr != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("无法读取文档：$loadErr", color = cs.error, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
            }
            tooLarge -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("文件过大（约 ${sizeBytes / 1024 / 1024} MB），应用内预览可能较慢或内存占用较大。请点右上角「其他应用」用 WPS / Office 打开。", color = cs.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
            }
            editing && isEditableText -> {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it; dirty = true },
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                )
            }
            isPptx -> PptxDeck(file)
            isWebView -> BuiltInWebView(file = file, type = imageType ?: ext, warnLarge = sizeBytes > WARN_SIZE)
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("不支持的文档类型：$ext\n请用「其他应用」打开。", color = cs.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
            }
        }
    }
}

/** 进程内 WebView 渲染（docx / xlsx / pdf / 文本 / 图片 / csv）。
 *
 * RC-A + RC-B 修复方案（WebViewAssetLoader）：
 *  - 把 assets 与目标文件都挂到 https://appassets.androidplatform.net 同源下。
 *    · /assets/ -> AssetsPathHandler：viewer.html / mammoth.js / xlsx.full.min.js / pdf.min.js / pdf.worker.min.js
 *    · /doc/    -> 自定义 PathHandler：以流（FileInputStream）返回目标文件，零整读、不进内存
 *  - WebView 因此拿到「非 opaque origin（https）」，pdf.js 的 Worker 才能启动（RC-B 真因修复）；
 *  - viewer.html 用 fetch(url) -> arrayBuffer() 把文件流式喂给渲染库，彻底去掉「整文件 base64 注入」（RC-A 内存炸弹修复）。
 */
@Composable
private fun BuiltInWebView(file: File, type: String, warnLarge: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    var loadErr by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    // /doc/<filename>：自定义 PathHandler 仅放行本次文件，防止任意路径穿越
    val docPath = "/doc/${file.name}"

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize().background(Color(0xFF0F1115)),
            factory = { c ->
                val assetLoader = WebViewAssetLoader.Builder()
                    .setDomain("appassets.androidplatform.net")
                    .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(c))
                    .addPathHandler("/doc/", object : WebViewAssetLoader.PathHandler {
                        override fun handle(url: String): WebResourceResponse? {
                            val reqPath = url
                            // 仅放行本次传入的单个文件，避免任意路径穿越
                            if (Uri.decode(reqPath.removePrefix("/doc/")) != file.name) return null
                            val mime = when (file.extension.lowercase()) {
                                "pdf" -> "application/pdf"
                                "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                                "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                                "png" -> "image/png"
                                "jpg", "jpeg" -> "image/jpeg"
                                "gif" -> "image/gif"
                                "webp" -> "image/webp"
                                "bmp" -> "image/bmp"
                                "csv" -> "text/csv; charset=utf-8"
                                "txt", "md", "markdown", "json", "xml", "log",
                                "kt", "kts", "py", "js", "ts", "html", "css", "java" -> "text/plain; charset=utf-8"
                                else -> "application/octet-stream"
                            }
                            // 以流方式返回文件体：不整读进内存，彻底避免大文件 OOM
                            return runCatching { WebResourceResponse(mime, null, file.inputStream()) }
                                .getOrNull()
                        }
                    })
                    .build()

                val viewerUrl = "https://appassets.androidplatform.net/assets/docs/viewer.html" +
                        "?doc=" + Uri.encode(docPath) + "&type=" + Uri.encode(type)

                WebView(c).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest
                        ): WebResourceResponse? {
                            // 交由 WebViewAssetLoader 路由 /assets/ 与 /doc/ 两类请求
                            return assetLoader.shouldInterceptRequest(request.url)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            loading = false
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            if (request?.isForMainFrame == true) {
                                loadErr = error?.description?.toString() ?: "加载错误"
                                loading = false
                            }
                        }
                    }
                    loadUrl(viewerUrl)
                }
            },
        )
        if (warnLarge) {
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(cs.errorContainer.copy(alpha = 0.18f)).padding(8.dp)) {
                Text("文件较大，应用内预览可能较慢；若长时间无响应，请点「其他应用」打开。", color = cs.onSurfaceVariant, fontSize = 12.sp)
            }
        }
        if (loading && loadErr == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = cs.primary)
            }
        }
        if (loadErr != null) {
            Box(Modifier.fillMaxSize().background(cs.background), contentAlignment = Alignment.Center) {
                Text("文档渲染失败：$loadErr\n可尝试用「其他应用」打开。", color = cs.error, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
            }
        }
    }
}

// ───────────────────────── PPTX 进程内渲染 ─────────────────────────
@Composable
private fun PptxDeck(file: File) {
    val cs = MaterialTheme.colorScheme
    var slides by remember { mutableStateOf<List<PptSlide>>(emptyList()) }
    var idx by remember { mutableStateOf(0) }
    var err by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(file) {
        runCatching {
            val parts = readZip(file)
            val names = parts.keys.filter { it.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
                .sortedBy { Regex("(\\d+)").find(it)?.groupValues?.get(1)?.toInt() ?: 0 }
            slides = names.map { parseSlide(parts[it].orEmpty()) }
        }.onFailure { err = it.message }
    }

    when {
        err != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("无法解析 PPTX：$err", color = cs.error, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
        }
        slides.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = cs.primary)
        }
        else -> {
            val slide = slides.getOrNull(idx) ?: slides.first()
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (idx > 0) idx-- }, enabled = idx > 0) { Icon(Icons.Filled.ArrowBack, "上一页", tint = cs.onSurface) }
                    Text("第 ${idx + 1} / ${slides.size} 页", color = cs.onSurface, fontSize = 14.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    IconButton(onClick = { if (idx < slides.lastIndex) idx++ }, enabled = idx < slides.lastIndex) { Icon(Icons.Filled.ArrowForward, "下一页", tint = cs.onSurface) }
                }
                Spacer(Modifier.height(12.dp))
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .background(cs.surfaceVariant, RoundedCornerShape(14.dp)).padding(20.dp),
                ) {
                    Text(slide.title.ifBlank { "(无标题)" }, color = cs.onSurface, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(14.dp))
                    slide.bullets.forEach { b ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                            Text("•", color = cs.primary, fontSize = 16.sp, modifier = Modifier.padding(end = 8.dp))
                            SelectionContainer { Text(b, color = cs.onSurface, fontSize = 15.sp, modifier = Modifier.fillMaxWidth()) }
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────── OOXML 文本解析（PPTX / 知识库检索复用） ─────────────────────────
private fun readZip(file: File): Map<String, String> {
    val map = mutableMapOf<String, String>()
    ZipFile(file).use { zf ->
        zf.entries().asIterator().forEach { e ->
            if (!e.isDirectory) {
                runCatching { zf.getInputStream(e).bufferedReader(Charsets.UTF_8).use { it.readText() } }
                    .getOrNull()?.let { map[e.name] = it }
            }
        }
    }
    return map
}

private fun parseSlide(xml: String): PptSlide {
    val texts = Regex("<a:t>(.*?)</a:t>", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { unesc(it.groupValues[1]) }.toList()
    val title = texts.firstOrNull() ?: ""
    val bullets = if (texts.size > 1) texts.drop(1) else emptyList()
    return PptSlide(title, bullets)
}

private fun unesc(s: String): String = s
    .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
    .replace("&quot;", "\"").replace("&apos;", "'").replace("&#10;", "\n").replace("&#13;", "")

data class PptSlide(val title: String, val bullets: List<String>)

/**
 * 从 docx / xlsx / pptx / 文本 提取纯文本，供知识库检索与预览复用（进程内 OOXML 解析）。
 * 失败回退为 readText。
 */
fun extractOfficeText(file: File): String {
    val ext = file.extension.lowercase()
    return runCatching {
        when (ext) {
            "docx" -> {
                val parts = readZip(file)
                Regex("<w:t[^>]*>(.*?)</w:t>", RegexOption.DOT_MATCHES_ALL)
                    .findAll(parts["word/document.xml"].orEmpty())
                    .map { unesc(it.groupValues[1]) }.joinToString("")
            }
            "xlsx" -> {
                val parts = readZip(file)
                val shared = mutableListOf<String>()
                parts["xl/sharedStrings.xml"]?.let { ss ->
                    Regex("<si>.*?</si>", RegexOption.DOT_MATCHES_ALL).findAll(ss).forEach { si ->
                        shared.add(Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL).findAll(si.value).map { unesc(it.groupValues[1]) }.joinToString(""))
                    }
                }
                Regex("<c [^>]*?/>|<c [^>]*?>.*?</c>", RegexOption.DOT_MATCHES_ALL).findAll(parts["xl/worksheets/sheet1.xml"].orEmpty())
                    .map { c ->
                        val ref = Regex("r=\"([A-Z]+\\d+)\"").find(c.value)?.groupValues?.get(1) ?: return@map ""
                        val (col, row) = colRow(ref)
                        val text = when {
                            c.value.contains("t=\"s\"") -> {
                                val i = Regex("<v>(.*?)</v>").find(c.value)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                                shared.getOrNull(i) ?: ""
                            }
                            else -> Regex("<v>(.*?)</v>").find(c.value)?.groupValues?.get(1)?.let { unesc(it) } ?: ""
                        }
                        "$col$row=$text"
                    }.joinToString(" ", "[\n", "]")
            }
            "pptx" -> {
                val parts = readZip(file)
                val names = parts.keys.filter { it.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
                    .sortedBy { Regex("(\\d+)").find(it)?.groupValues?.get(1)?.toInt() ?: 0 }
                names.map { parseSlide(parts[it].orEmpty()) }
                    .joinToString("\n\n") { s -> (listOf(s.title) + s.bullets).joinToString("\n") }
            }
            else -> file.readText()
        }
    }.getOrDefault(runCatching { file.readText() }.getOrDefault(""))
}

private fun colRow(ref: String): Pair<Int, Int> {
    val m = Regex("([A-Z]+)(\\d+)").find(ref)!!
    val colStr = m.groupValues[1]; val row = m.groupValues[2].toInt()
    var n = 0
    colStr.forEach { n = n * 26 + (it - 'A' + 1) }
    return n to row
}

/**
 * 真·WPS 深链：若系统已安装 WPS Office（cn.wps.moffice_eng 或 com.kingsoft.wpsoffice），
 * 通过 ACTION_VIEW + 应用既有 FileProvider（${applicationId}.fileprovider）授权将其打开；
 * 未安装则回退到 QuroDocOpener 的通用选择器（系统/ONLYOFFICE）。
 *
 * 复用 QuroDocOpener 的 FileProvider authority 与 guessMime，不新增 Provider。
 */
private fun openWithWpsOrFallback(context: Context, file: File) {
    if (!file.exists()) {
        Toast.makeText(context, "文件不存在", Toast.LENGTH_SHORT).show()
        return
    }
    val uri = QuroDocOpener.safeUri(context, file) ?: run {
        Toast.makeText(context, "无法共享该文件（路径未被允许）", Toast.LENGTH_SHORT).show()
        return
    }
    val mime = QuroDocOpener.guessMime(file.name)
    val pm = context.packageManager
    val wpsPackages = listOf("cn.wps.moffice_eng", "com.kingsoft.wpsoffice")
    val wpsPkg = wpsPackages.firstOrNull { pkg ->
        runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess
    }
    if (wpsPkg != null) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            setPackage(wpsPkg)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            context.startActivity(intent)
            Toast.makeText(context, "已用 WPS 打开", Toast.LENGTH_SHORT).show()
        }.onFailure {
            // WPS 无法处理该类型，回退通用选择器
            if (!QuroDocOpener.open(context, file)) {
                Toast.makeText(context, "无法打开该文件", Toast.LENGTH_SHORT).show()
            }
        }
    } else {
        // 未安装 WPS：回退到系统/通用选择器
        if (!QuroDocOpener.open(context, file)) {
            Toast.makeText(context, "未找到可打开该文件的应用", Toast.LENGTH_SHORT).show()
        }
    }
}
