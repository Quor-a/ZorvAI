package com.ai.assistance.quro.activity

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.ImageView
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.text.BasicTextField
import com.ai.assistance.quro.ui.theme.Accent
import com.ai.assistance.quro.ui.theme.Line
import com.ai.assistance.quro.ui.theme.Muted
import java.util.regex.Pattern

/**
 * 默认应用处理 Activity（原创，无外部依赖）。
 *
 * 当 Zorv AI 被设为某类型的默认应用后，系统会把对应 Intent 发到这里。本 Activity 按类型做**功能性**处理，
 * 而不是把用户挡在「已接管」的死胡同里：
 *  - 图片 / 视频 / PDF / 文本 / 网页(http/https) / 邮件 / 拨号 各自渲染或转发。
 *  - PDF / 未知类型提供「用其他应用打开」按钮（走系统选择器，**排除自身**避免自循环）。
 *
 * 不在 Manifest 注册 `CATEGORY_HOME` 过滤器（桌面启动器需要真正的桌面 UI，注册会砖掉主页按钮），
 * 故本 Activity 不处理 HOME 角色；HOME 仅经 RoleManager 申请入口提交（见 QuroDefaultAppScreen）。
 */
class QuroDefaultAppHandlerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = classify(intent)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    HandlerScreen(mode = mode, onClose = { finish() })
                }
            }
        }
    }
}

/** 处理模式（按入站 Intent 分类）。 */
private sealed interface HandleMode {
    val label: String
    data class Image(val uri: Uri?) : HandleMode { override val label = "图片" }
    data class Video(val uri: Uri?) : HandleMode { override val label = "视频" }
    data class Pdf(val uri: Uri?) : HandleMode { override val label = "文档 (PDF)" }
    data class Text(val uri: Uri?) : HandleMode { override val label = "文本" }
    data class Web(val url: String?) : HandleMode { override val label = "网页" }
    data class Email(val address: String?, val subject: String?, val body: String?) : HandleMode { override val label = "邮件" }
    data class Dial(val number: String?) : HandleMode { override val label = "拨号" }
    data object Unknown : HandleMode { override val label = "未知类型" }
}

/** 把入站 Intent 分类为处理模式。 */
private fun classify(intent: Intent): HandleMode {
    val a = intent.action
    val d = intent.data
    val t = intent.type
    if (a == Intent.ACTION_SENDTO || a == Intent.ACTION_SEND) {
        val addr = if (d?.scheme == "mailto") d.schemeSpecificPart else intent.getStringArrayExtra(Intent.EXTRA_EMAIL)?.firstOrNull()
        return HandleMode.Email(addr, intent.getStringExtra(Intent.EXTRA_SUBJECT), intent.getStringExtra(Intent.EXTRA_TEXT))
    }
    if (a == Intent.ACTION_DIAL || (a == Intent.ACTION_VIEW && d?.scheme == "tel")) {
        return HandleMode.Dial(d?.schemeSpecificPart ?: intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER))
    }
    if (a == Intent.ACTION_VIEW && d != null) {
        val scheme = d.scheme
        if (scheme == "http" || scheme == "https") return HandleMode.Web(d.toString())
        if (t != null) {
            return when {
                t.startsWith("image/") -> HandleMode.Image(d)
                t.startsWith("video/") -> HandleMode.Video(d)
                t == "application/pdf" -> HandleMode.Pdf(d)
                t.startsWith("text/") -> HandleMode.Text(d)
                else -> HandleMode.Unknown
            }
        }
        // 无 MIME：按扩展名猜
        if (scheme == "content" || scheme == "file") {
            val path = d.path ?: ""
            return when {
                path.endsWith(".pdf", true) -> HandleMode.Pdf(d)
                path.endsWithAny(listOf(".txt", ".md", ".log", ".json", ".csv", ".xml", ".yaml", ".yml")) -> HandleMode.Text(d)
                path.matchesImg() -> HandleMode.Image(d)
                path.matchesVideo() -> HandleMode.Video(d)
                else -> HandleMode.Unknown
            }
        }
    }
    return HandleMode.Unknown
}

private fun String.endsWithAny(suffixes: List<String>): Boolean = suffixes.any { this.endsWith(it, true) }
private fun String.matchesImg(): Boolean = Pattern.compile(".*\\.(png|jpe?g|gif|webp|bmp)$", Pattern.CASE_INSENSITIVE).matcher(this).matches()
private fun String.matchesVideo(): Boolean = Pattern.compile(".*\\.(mp4|mkv|webm|3gp|avi|m4v|mov)$", Pattern.CASE_INSENSITIVE).matcher(this).matches()

/** 处理界面。 */
@Composable
private fun HandlerScreen(mode: HandleMode, onClose: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // 顶部条
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "关闭", tint = MaterialTheme.colorScheme.onSurface) }
            Spacer(Modifier.width(8.dp))
            Text("Zorv AI · ${mode.label}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        }
        HorizontalDivider(color = Line)
        Box(Modifier.fillMaxSize().padding(12.dp)) {
            when (mode) {
                is HandleMode.Image -> ImageHandler(mode.uri)
                is HandleMode.Video -> VideoHandler(mode.uri)
                is HandleMode.Pdf -> PdfHandler(mode.uri)
                is HandleMode.Text -> TextHandler(mode.uri)
                is HandleMode.Web -> WebHandler(mode.url)
                is HandleMode.Email -> EmailHandler(mode.address, mode.subject, mode.body)
                is HandleMode.Dial -> DialHandler(mode.number)
                is HandleMode.Unknown -> UnknownHandler()
            }
        }
    }
}

@Composable
private fun ImageHandler(uri: Uri?) {
    if (uri == null) { EmptyNote("未收到图片") ; return }
    var error by remember { mutableStateOf<String?>(null) }
    var bmp by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val ctx = LocalContext.current
    LaunchedEffect(uri) {
        runCatching {
            bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }.onFailure { error = it.message }
    }
    if (error != null) EmptyNote("无法加载图片：$error")
    else if (bmp == null) CircularProgressIndicator()
    else AndroidView(factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setImageBitmap(bmp) } }, modifier = Modifier.fillMaxSize())
}

@Composable
private fun VideoHandler(uri: Uri?) {
    if (uri == null) { EmptyNote("未收到视频") ; return }
    AndroidView(
        factory = { ctx ->
            VideoView(ctx).apply {
                setVideoURI(uri)
                val mc = MediaController(ctx)
                mc.setAnchorView(this)
                setMediaController(mc)
                setOnPreparedListener { start() }
                setOnErrorListener { _, what, extra -> Toast.makeText(ctx, "视频播放失败：$what/$extra", Toast.LENGTH_SHORT).show(); true }
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun PdfHandler(uri: Uri?) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.Description, null, Modifier.size(48.dp), tint = Muted)
        Spacer(Modifier.height(12.dp))
        Text("Zorv AI 暂未内置 PDF 渲染器", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(16.dp))
        Button(onClick = {
            if (uri != null) {
                val view = Intent(Intent.ACTION_VIEW).setData(uri).setType("application/pdf").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { ctx.startActivity(Intent.createChooser(view, "用其他应用打开")) }
                    .onFailure { Toast.makeText(ctx, "无法打开：${it.message}", Toast.LENGTH_SHORT).show() }
            } else Toast.makeText(ctx, "无文档 URI", Toast.LENGTH_SHORT).show()
        }) { Text("用其他应用打开") }
    }
}

@Composable
private fun TextHandler(uri: Uri?) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(uri) {
        runCatching {
            text = uri?.let {
                ctx.contentResolver.openInputStream(it)?.bufferedReader()?.use { r ->
                    // 限制读取量，避免超长文件卡 UI
                    val sb = StringBuilder()
                    val buf = CharArray(64 * 1024)
                    var n = r.read(buf)
                    var total = 0
                    while (total < 256 * 1024 && n != -1) {
                        sb.append(buf, 0, n); total += n
                        n = r.read(buf)
                    }
                    if (n != -1) sb.append("\n…（内容过长，已截断）")
                    sb.toString()
                }
            }
        }.onFailure { error = it.message }
    }
    when {
        error != null -> EmptyNote("无法读取文本：$error")
        text == null -> CircularProgressIndicator()
        else -> Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
            Text(text ?: "", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun WebHandler(url: String?) {
    if (url.isNullOrBlank()) { EmptyNote("未收到网页地址") ; return }
    val ctx = LocalContext.current
    AndroidView(
        factory = { ctx2 ->
            android.webkit.WebView(ctx2).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                webViewClient = android.webkit.WebViewClient()
                loadUrl(url)
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun EmailHandler(address: String?, subject: String?, body: String?) {
    val ctx = LocalContext.current
    var to by remember { mutableStateOf(TextFieldValue(address ?: "")) }
    var subj by remember { mutableStateOf(TextFieldValue(subject ?: "")) }
    var bodyText by remember { mutableStateOf(TextFieldValue(body ?: "")) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextField(to, "收件人", "name@example.com", onValueChange = { to = it })
        TextField(subj, "主题", "主题", onValueChange = { subj = it })
        TextField(bodyText, "正文", "写点什么…", singleLine = false, onValueChange = { bodyText = it })
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            val mail = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:${to.text}")
                putExtra(Intent.EXTRA_SUBJECT, subj.text)
                putExtra(Intent.EXTRA_TEXT, bodyText.text)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { ctx.startActivity(Intent.createChooser(mail, "发送邮件")) }
                .onFailure { Toast.makeText(ctx, "无邮件应用：${it.message}", Toast.LENGTH_SHORT).show() }
        }) { Text("发送") }
        Text("Zorv AI 不存储邮件，发送经你选择的邮件应用完成。", fontSize = 12.sp, color = Muted)
    }
}

@Composable
private fun DialHandler(number: String?) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("号码：${number ?: "（未提供）"}", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
        Button(onClick = {
            val dial = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:${number ?: ""}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { ctx.startActivity(dial) }.onFailure { Toast.makeText(ctx, "无法拨号：${it.message}", Toast.LENGTH_SHORT).show() }
        }) { Text("拨打") }
        Text("Zorv AI 仅拉起系统拨号盘，不自动外呼。", fontSize = 12.sp, color = Muted)
    }
}

@Composable
private fun UnknownHandler() {
    EmptyNote("Zorv AI 暂不支持以默认应用处理此类型。\n可在系统设置 → 应用 → Zorv AI → 默认打开 中清除该默认关联。")
}

@Composable
private fun EmptyNote(msg: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(msg, fontSize = 14.sp, color = Muted, modifier = Modifier.padding(24.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun TextField(value: TextFieldValue, label: String, placeholder: String, singleLine: Boolean = true, onValueChange: (TextFieldValue) -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    Column {
        Text(label, fontSize = 12.sp, color = Muted, modifier = Modifier.padding(bottom = 4.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                .background(cs.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).padding(12.dp),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = cs.onSurface),
            decorationBox = { it2 -> if (value.text.isEmpty()) Text(placeholder, fontSize = 14.sp, color = Muted); it2() },
        )
    }
}
