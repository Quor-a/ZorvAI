package com.ai.assistance.quro.terminal.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ai.assistance.quro.terminal.desktop.LanxiuDesktop
import com.ai.assistance.quro.terminal.utils.VncClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * LANXIU 图形桌面查看器：连 127.0.0.1:[LanxiuDesktop.VNC_PORT]（LANXIU 内 Xtigervnc 起的
 * 虚拟 X+VNC 服务，Weston 经 x11-backend 把桌面渲染进该 X），用内置 [VncClient] 原生渲染
 * Wayland 桌面帧缓冲，触摸当鼠标、软键盘发字符。
 *
 * 进入时自动确保桌面已启动（VNC 模式：Xtigervnc + weston）；若服务尚未监听则给出「重试连接」。
 * 返回仅断连（不杀桌面，可在「LANXIU 设置」里手动停止）。
 */
@Composable
fun LanxiuDesktopScreen(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var status by remember { mutableStateOf("正在准备 LANXIU 桌面…") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var fbText by remember { mutableStateOf("") }
    var frameTick by remember { mutableStateOf(0L) }
    var showKb by remember { mutableStateOf(false) }
    var ctrlOn by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }

    val bitmapRef = remember { AtomicReference<Bitmap?>(null) }
    val clientRef = remember { mutableStateOf<VncClient?>(null) }

    val client = remember {
        VncClient(
            "127.0.0.1",
            LanxiuDesktop.VNC_PORT,
            LanxiuDesktop.VNC_PASSWORD,
            object : VncClient.Listener {
                override fun onStatus(t: String) {
                    mainHandler.post { status = t }
                }

                override fun onFramebufferSize(w: Int, h: Int) {
                    mainHandler.post { fbText = "${w}×$h" }
                }

                override fun onFrame(bitmap: Bitmap, full: Boolean) {
                    bitmapRef.set(bitmap)
                    mainHandler.post { frameTick++ }
                }

                override fun onFailure(msg: String) {
                    mainHandler.post {
                        errorMsg = msg
                        status = "连接失败：$msg"
                    }
                }

                override fun onClosed() {
                    mainHandler.post { status = "已断开" }
                }
            }
        ).also { clientRef.value = it }
    }

    /** 确保桌面以 VNC 模式（Xtigervnc + weston）启动并尝试连 VNC（本 viewer 只走 VNC 模式）。 */
    fun ensureAndConnect() {
        errorMsg = null
        status = "正在启动/连接 LANXIU 桌面（VNC 模式）…"
        CoroutineScope(Dispatchers.IO).launch {
            // 强制 VNC 模式：先杀残留 weston（可能跑着 X11 后端），再起 Xtigervnc + weston。
            LanxiuDesktop.start(context, LanxiuDesktop.OutputMode.VNC)
            // 给 Xtigervnc + weston 一点时间监听端口
            Thread.sleep(2500)
            mainHandler.post { status = "正在连接 127.0.0.1:${LanxiuDesktop.VNC_PORT} …" }
            client.start()
        }
    }

    DisposableEffect(Unit) {
        ensureAndConnect()
        onDispose { client.stop() }
    }

    fun sendChar(c: Char) {
        if (ctrlOn) {
            client.sendKey(0xFFE3, true)
            client.sendKeyTap(c.code)
            client.sendKey(0xFFE3, false)
        } else {
            client.sendKeyTap(c.code)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalTheme.backgroundColor)
    ) {
        // 顶栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TerminalTheme.surfaceColor)
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = TerminalTheme.onSurfaceColor)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("LANXIU 图形桌面", color = TerminalTheme.onSurfaceColor, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (fbText.isNotEmpty() && errorMsg == null) "$status  ·  $fbText" else status,
                    color = if (errorMsg != null) TerminalTheme.errorColor else TerminalTheme.onSurfaceVariant,
                    fontSize = 12.sp, lineHeight = 16.sp
                )
            }
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { ensureAndConnect() },
                color = TerminalTheme.elevated,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("重试", color = TerminalTheme.onSurfaceColor, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
            }
        }

        // 桌面画面
        Box(modifier = Modifier.weight(1f)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx -> LanxiuDesktopSurfaceView(ctx, client, bitmapRef) },
                update = { it.invalidate() }
            )
            if (errorMsg != null) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(12.dp)),
                    color = TerminalTheme.surfaceVariant.copy(alpha = 0.95f)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text("无法连接 LANXIU 桌面", color = TerminalTheme.onSurfaceColor, fontSize = 14.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(errorMsg ?: "", color = TerminalTheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("请回到「LANXIU 设置」确认桌面已启动，或点右上角重试。", color = TerminalTheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
                    }
                }
            }
        }

        // 键盘切换 + 软键盘条
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { showKb = !showKb },
                color = TerminalTheme.elevated,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("键盘", color = TerminalTheme.onSurfaceColor, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
            }
            Spacer(Modifier.width(8.dp))
            if (showKb) {
                Box(modifier = Modifier.weight(1f)) {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(TerminalTheme.surfaceVariant)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        textStyle = TextStyle(
                            color = TerminalTheme.onSurfaceColor,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp
                        ),
                        cursorBrush = SolidColor(TerminalTheme.accentColor),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { sendTyped(text, ::sendChar) { text = "" } }),
                        decorationBox = { inner ->
                            if (text.isEmpty()) {
                                Text("点此输入…", color = TerminalTheme.onSurfaceVariant, fontSize = 14.sp)
                            }
                            inner()
                        }
                    )
                }
                Spacer(Modifier.width(8.dp))
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { sendTyped(text, ::sendChar) { text = "" } },
                    color = TerminalTheme.elevated,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("发送", color = TerminalTheme.onSurfaceColor, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
                }
                Spacer(Modifier.width(8.dp))
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { ctrlOn = !ctrlOn },
                    color = if (ctrlOn) TerminalTheme.primaryColor else TerminalTheme.elevated,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Ctrl", color = if (ctrlOn) Color.White else TerminalTheme.onSurfaceColor, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
                }
            }
        }

        if (showKb) {
            val keys = listOf(
                "Esc" to 0xFF1B, "Tab" to 0xFF09, "⌫" to 0xFF08, "↵" to 0xFF0D,
                "←" to 0xFF51, "↑" to 0xFF52, "→" to 0xFF53, "↓" to 0xFF54
            )
            Surface(modifier = Modifier.fillMaxWidth(), color = TerminalTheme.surfaceVariant) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    keys.forEach { (label, keysym) ->
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    if (ctrlOn) {
                                        client.sendKey(0xFFE3, true)
                                        client.sendKeyTap(keysym)
                                        client.sendKey(0xFFE3, false)
                                    } else {
                                        client.sendKeyTap(keysym)
                                    }
                                },
                            color = TerminalTheme.elevated,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(label, color = TerminalTheme.onSurfaceColor, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
                        }
                    }
                }
            }
        }
    }
}

/** 桌面帧缓冲画布：等比适配 + 居中绘制，触摸反推帧缓冲坐标发鼠标事件（复用 VncClient）。 */
private class LanxiuDesktopSurfaceView(
    context: Context,
    private val client: VncClient,
    private val bitmapRef: AtomicReference<Bitmap?>,
) : View(context) {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var scale = 1f
    private var offX = 0f
    private var offY = 0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(TerminalTheme.backgroundColor.toArgb())
        val bmp = bitmapRef.get() ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        if (bw <= 0f || bh <= 0f) return
        scale = minOf(vw / bw, vh / bh)
        offX = (vw - bw * scale) / 2f
        offY = (vh - bh * scale) / 2f
        canvas.drawBitmap(bmp, null, RectF(offX, offY, offX + bw * scale, offY + bh * scale), paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bmp = bitmapRef.get() ?: return false
        val bx = ((event.x - offX) / scale).toInt().coerceIn(0, (bmp.width - 1).coerceAtLeast(0))
        val by = ((event.y - offY) / scale).toInt().coerceIn(0, (bmp.height - 1).coerceAtLeast(0))
        val mask = when (event.action) {
            MotionEvent.ACTION_DOWN -> 1
            MotionEvent.ACTION_MOVE -> 1
            MotionEvent.ACTION_UP -> 0
            MotionEvent.ACTION_CANCEL -> 0
            else -> return true
        }
        client.sendPointer(bx, by, mask)
        return true
    }
}

private fun sendTyped(text: String, sendChar: (Char) -> Unit, onDone: () -> Unit) {
    if (text.isEmpty()) return
    text.forEach { c -> if (c != '\n') sendChar(c) else sendChar('\r') }
    onDone()
}
