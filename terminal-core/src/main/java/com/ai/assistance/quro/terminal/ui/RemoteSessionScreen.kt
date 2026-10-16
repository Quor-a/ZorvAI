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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ai.assistance.quro.terminal.R
import com.ai.assistance.quro.terminal.domain.ansi.AnsiTerminalEmulator
import com.ai.assistance.quro.terminal.utils.RemoteAccessManager
import com.ai.assistance.quro.terminal.utils.SshTerminalClient
import com.ai.assistance.quro.terminal.utils.VncClient
import com.ai.assistance.quro.terminal.view.canvas.ConfigurableCanvasTerminal
import java.util.concurrent.atomic.AtomicReference

/**
 * 远程连接的「真界面」：让「已连接」不再只是一行 banner，而是本机内直接可交互的
 * SSH 终端 / VNC 画面。两条通路都连 127.0.0.1（内置服务端），本地连本地。
 *
 * - [RemoteSshScreen]：连本机 2222，把服务端 shell 输出喂给与本地终端同一套
 *   [AnsiTerminalEmulator] 渲染，能直接看到登录 shell 并键入命令。
 * - [RemoteVncScreen]：连本机 5901，把 RFB 帧缓冲画到画布上，支持触摸点击/拖动，
 *   并带一个软键盘条发送字符与特殊键。
 */

@Composable
fun RemoteSshScreen(onClose: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var status by remember { mutableStateOf(context.getString(R.string.remote_connecting)) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var closed by remember { mutableStateOf(false) }
    val clientRef = remember { mutableStateOf<SshTerminalClient?>(null) }

    // 终端模拟器：画布视图改变尺寸时会调用 resize；[AnsiTerminalEmulator] 是 final 类不可继承，
    // 因此用「变化监听器 + 尺寸比对」把新的行列数同步给 SSH 通道（window-change），
    // 让远端 shell 知道窗口大小（仅尺寸真正变化时才发，避免每次输出都发）。
    val resizeSync = remember { intArrayOf(0, 0) }
    val emulator = remember {
        AnsiTerminalEmulator().also { em ->
            em.addChangeListener {
                val buf = em.getScreenContent()
                val rows = buf.size
                val cols = buf.firstOrNull()?.size ?: 0
                if (cols > 0 && rows > 0 &&
                    (cols != resizeSync[0] || rows != resizeSync[1])
                ) {
                    resizeSync[0] = cols
                    resizeSync[1] = rows
                    clientRef.value?.resize(cols, rows)
                }
            }
        }
    }

    val client = remember {
        SshTerminalClient(
            context,
            "127.0.0.1",
            RemoteAccessManager.SSH_PORT,
            "root",
            RemoteAccessManager.LOGIN_PASSWORD,
            object : SshTerminalClient.Listener {
                override fun onStatus(text: String) {
                    mainHandler.post { status = text }
                }

                // 服务端输出在工作线程上到达：直接喂给模拟器 parse（与本地终端同一套并发模型）
                override fun onOutput(text: String) {
                    emulator.parse(text)
                }

                override fun onFailure(message: String) {
                    mainHandler.post {
                        errorMsg = message
                        status = context.getString(R.string.remote_session_error) + message
                    }
                }

                override fun onClosed() {
                    mainHandler.post {
                        closed = true
                        status = context.getString(R.string.remote_session_closed)
                    }
                }
            },
        ).also { clientRef.value = it }
    }

    DisposableEffect(Unit) {
        client.connect(80, 24)
        onDispose { client.close() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalTheme.backgroundColor)
    ) {
        SessionTopBar(
            title = stringResource(R.string.remote_ssh_terminal_title),
            statusText = status,
            errorText = errorMsg,
            onBack = onClose,
        )

        // 终端画布：点击即可唤起软键盘；透传键入到 SSH 通道
        Box(modifier = Modifier.weight(1f)) {
            ConfigurableCanvasTerminal(
                emulator = emulator,
                modifier = Modifier.fillMaxSize(),
                fontSize = 14f,
                backgroundColor = TerminalTheme.backgroundColor.toArgb(),
                foregroundColor = TerminalTheme.onSurfaceColor.toArgb(),
                cursorColor = TerminalTheme.accentColor.toArgb(),
                onInput = { client.send(it) },
            )

            if (closed && errorMsg == null) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(12.dp)),
                    color = TerminalTheme.surfaceVariant.copy(alpha = 0.92f),
                ) {
                    Text(
                        stringResource(R.string.remote_session_closed),
                        color = TerminalTheme.onSurfaceColor,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    )
                }
            }
        }

        Text(
            stringResource(R.string.remote_tap_to_type),
            color = TerminalTheme.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
        )

        // 常用特殊键（ESC / Tab / 方向键 / Ctrl 组合）：补齐软键盘缺失的控制序列
        RemoteSpecialKeysRow(onKey = { client.send(it) })
    }
}

@Composable
fun RemoteVncScreen(onClose: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var status by remember { mutableStateOf(context.getString(R.string.remote_connecting)) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var fbText by remember { mutableStateOf("") }
    var frameTick by remember { mutableStateOf(0L) }
    var showKb by remember { mutableStateOf(false) }
    var ctrlOn by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }

    val bitmapRef = remember { AtomicReference<Bitmap?>(null) }

    val client = remember {
        VncClient(
            "127.0.0.1",
            RemoteAccessManager.VNC_PORT,
            RemoteAccessManager.LOGIN_PASSWORD,
            object : VncClient.Listener {
                override fun onStatus(text: String) {
                    mainHandler.post { status = text }
                }

                override fun onFramebufferSize(width: Int, height: Int) {
                    mainHandler.post { fbText = "${width}×$height" }
                }

                override fun onFrame(bitmap: Bitmap, full: Boolean) {
                    bitmapRef.set(bitmap)
                    mainHandler.post { frameTick++ }
                }

                override fun onFailure(message: String) {
                    mainHandler.post {
                        errorMsg = message
                        status = context.getString(R.string.remote_session_error) + message
                    }
                }

                override fun onClosed() {
                    mainHandler.post { status = context.getString(R.string.remote_session_closed) }
                }
            },
        )
    }

    DisposableEffect(Unit) {
        client.start()
        onDispose { client.stop() }
    }

    fun sendChar(c: Char) {
        if (ctrlOn) {
            client.sendKey(0xFFE3, true)      // Ctrl 按下
            client.sendKeyTap(c.code)
            client.sendKey(0xFFE3, false)     // Ctrl 抬起
        } else {
            client.sendKeyTap(c.code)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalTheme.backgroundColor)
    ) {
        SessionTopBar(
            title = stringResource(R.string.remote_vnc_screen_title),
            statusText = if (fbText.isNotEmpty() && errorMsg == null) "$status  ·  $fbText" else status,
            errorText = errorMsg,
            onBack = onClose,
        )

        // VNC 画面：自定义 View 直接绘制帧缓冲，触摸 → 鼠标事件
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            factory = { ctx -> VncSurfaceView(ctx, client, bitmapRef) },
            update = { it.invalidate() },
        )

        // 键盘切换 + 软键盘条
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SessionActionChip(stringResource(R.string.remote_keyboard)) { showKb = !showKb }
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
                            fontSize = 14.sp,
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(TerminalTheme.accentColor),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { sendTyped(text, ::sendChar) { text = "" } }),
                        decorationBox = { inner ->
                            if (text.isEmpty()) {
                                Text(
                                    stringResource(R.string.remote_tap_to_type),
                                    color = TerminalTheme.onSurfaceVariant,
                                    fontSize = 14.sp,
                                )
                            }
                            inner()
                        },
                    )
                }
                Spacer(Modifier.width(8.dp))
                SessionActionChip(stringResource(R.string.remote_send)) {
                    sendTyped(text, ::sendChar) { text = "" }
                }
                Spacer(Modifier.width(8.dp))
                SessionActionChip(
                    stringResource(R.string.remote_ctrl),
                    active = ctrlOn,
                ) { ctrlOn = !ctrlOn }
            }
        }

        if (showKb) {
            RemoteVncSpecialKeysRow(
                ctrlOn = ctrlOn,
                onSpecial = { keysym, withCtrl ->
                    if (withCtrl) {
                        client.sendKey(0xFFE3, true)
                        client.sendKeyTap(keysym)
                        client.sendKey(0xFFE3, false)
                    } else {
                        client.sendKeyTap(keysym)
                    }
                },
            )
        }
    }
}

/** 把软键盘输入的字符串逐个发送（回车等由输入法以 \n 形式传来，直接按字符发即可）。 */
private fun sendTyped(text: String, sendChar: (Char) -> Unit, onDone: () -> Unit) {
    if (text.isEmpty()) return
    text.forEach { c -> if (c != '\n') sendChar(c) else sendChar('\r') }
    onDone()
}

// ----------------------------------------------------------------- 公共部件

@Composable
private fun SessionTopBar(
    title: String,
    statusText: String,
    errorText: String?,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TerminalTheme.surfaceColor)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.Default.ArrowBack,
                contentDescription = stringResource(R.string.remote_disconnect),
                tint = TerminalTheme.onSurfaceColor,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = TerminalTheme.onSurfaceColor,
                fontSize = 16.sp,
            )
            Text(
                statusText,
                color = if (errorText != null) TerminalTheme.errorColor else TerminalTheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
        }
        SessionActionChip(stringResource(R.string.remote_disconnect)) { onBack() }
    }
}

@Composable
private fun SessionActionChip(text: String, active: Boolean = false, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() },
        color = if (active) TerminalTheme.primaryColor else TerminalTheme.elevated,
        shape = RoundedCornerShape(10.dp),
    ) {
        Text(
            text,
            color = if (active) Color.White else TerminalTheme.onSurfaceColor,
            fontSize = 13.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
        )
    }
}

/** SSH 终端底部的特殊键：发出对应的 ANSI 转义序列 / 控制字符。 */
@Composable
private fun RemoteSpecialKeysRow(onKey: (String) -> Unit) {
    // Ctrl 为粘滞键：点亮后，紧接着点下的普通键会带上 Ctrl 前缀（如 Ctrl+C = 0x03）
    var ctrlOn by remember { mutableStateOf(false) }
    val keys = listOf(
        SpecialKey("ESC", "\u001B"),
        SpecialKey("Tab", "\t"),
        SpecialKey("←", "\u001B[D"),
        SpecialKey("→", "\u001B[C"),
        SpecialKey("↑", "\u001B[A"),
        SpecialKey("↓", "\u001B[B"),
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = TerminalTheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SessionActionChip("Ctrl", active = ctrlOn) { ctrlOn = !ctrlOn }
            keys.forEach { (label, seq) ->
                SessionActionChip(label) {
                    if (ctrlOn && seq.length == 1) {
                        // 单字符键 + Ctrl：转成控制字符（'c' → 0x03）
                        onKey(((seq[0].code and 0x1F).toChar().toString()))
                    } else {
                        onKey(seq)
                    }
                }
            }
        }
    }
}

private data class SpecialKey(val label: String, val seq: String)

/** VNC 画面底部的特殊键（X11 keysym）。 */
@Composable
private fun RemoteVncSpecialKeysRow(
    ctrlOn: Boolean,
    onSpecial: (keysym: Int, withCtrl: Boolean) -> Unit,
) {
    val keys = listOf(
        "Esc" to 0xFF1B,
        "Tab" to 0xFF09,
        "⌫" to 0xFF08,
        "↵" to 0xFF0D,
        "←" to 0xFF51,
        "↑" to 0xFF52,
        "→" to 0xFF53,
        "↓" to 0xFF54,
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = TerminalTheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            keys.forEach { (label, keysym) ->
                SessionActionChip(label) { onSpecial(keysym, ctrlOn) }
            }
        }
    }
}

/**
 * VNC 帧缓冲画布：直接把 [VncClient] 复用的 Bitmap 按「等比适配 + 居中」画出来，
 * 触摸坐标反推回帧缓冲坐标后发给服务端当鼠标用。
 */
private class VncSurfaceView(
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
