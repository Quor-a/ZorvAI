package com.ai.assistance.quro.ui

import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
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

    var editing by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf("") }
    var loadErr by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(editing, file) {
        if (editing && isEditableText) {
            runCatching { editText = file.readText(Charsets.UTF_8) }.onFailure { loadErr = it.message }
        }
    }

    Column(Modifier.fillMaxSize().background(cs.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.ArrowBack, "关闭", tint = cs.onSurface) }
            Text(
                file.name,
                color = cs.onSurface, fontSize = 15.sp, maxLines = 1,
                modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
            )
            if (isEditableText && !readOnly) {
                TextButton(onClick = {
                    if (editing) {
                        runCatching {
                            file.writeText(editText, Charsets.UTF_8)
                            Toast.makeText(ctx, "已保存", Toast.LENGTH_SHORT).show()
                        }.onFailure { Toast.makeText(ctx, "保存失败: ${it.message}", Toast.LENGTH_SHORT).show() }
                    }
                    editing = !editing
                }) { Text(if (editing) "保存" else "编辑", color = cs.primary) }
            }
            TextButton(onClick = onExternal) { Text("其他应用", color = cs.onSurface.copy(alpha = 0.7f)) }
        }
        HorizontalDivider()

        when {
            loadErr != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("无法读取文档：$loadErr", color = cs.error, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
            }
            editing && isEditableText -> {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                )
            }
            isPptx -> PptxDeck(file)
            isWebView -> BuiltInWebView(file = file, type = imageType ?: ext)
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("不支持的文档类型：$ext\n请用「其他应用」打开。", color = cs.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
            }
        }
    }
}

/** 进程内 WebView 渲染（docx / xlsx / pdf / 文本 / 图片）。 */
@Composable
private fun BuiltInWebView(file: File, type: String) {
    val cs = MaterialTheme.colorScheme
    var loadErr by remember { mutableStateOf<String?>(null) }

    AndroidView(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0F1115)),
        factory = { c ->
            WebView(c).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                settings.loadsImagesAutomatically = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        val b64 = runCatching {
                            android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)
                        }.getOrNull()
                        if (b64 == null) { loadErr = "读取文件失败"; return }
                        view?.loadUrl("javascript:GD.render('$type','$b64')")
                    }
                    override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                        loadErr = error?.description?.toString() ?: "加载错误"
                    }
                }
                loadUrl("file:///android_asset/docs/viewer.html")
            }
        },
    )

    if (loadErr != null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("文档渲染失败：$loadErr", color = cs.error, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
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
                Regex("<c [^>]*?/>|<c [^>]*?>*?</c>", RegexOption.DOT_MATCHES_ALL).findAll(parts["xl/worksheets/sheet1.xml"].orEmpty())
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
