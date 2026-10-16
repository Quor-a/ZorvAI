package com.ai.assistance.quro.terminal.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import com.ai.assistance.quro.terminal.Pty
import com.ai.assistance.quro.terminal.runtime.TerminalEnvironment
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * 真正内置的 VNC 服务端：跑在 App host 进程，监听 127.0.0.1:5901，
 * 不依赖 proot / Ubuntu 桌面 —— 没有连接环境也能连（退化成 host busybox 终端）。
 *
 * 渲染思路：把后端 PTY 的输出当成一个字符终端（行/列网格），用 Android Canvas 画到
 * Bitmap，再以 RFB 3.8 + RAW 编码把帧缓冲推给 VNC 客户端。支持 VNC Auth（DES 挑战-响应）、
 * 基本 ANSI 转义跳过、回车/换行/退格游标移动。
 */
object HostVncServer {

    private const val TAG = "HostVncServer"

    // 终端网格与帧缓冲尺寸
    private const val COLS = 80
    private const val ROWS = 25
    private const val CELL_W = 8 // 单字符宽(px)
    private const val CELL_H = 16 // 单字符高(px)
    private val FB_W = COLS * CELL_W
    private val FB_H = ROWS * CELL_H

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptThread: Thread? = null

    @Volatile
    private var backendLabel: String = "—"

    @Synchronized
    fun start(context: Context): Boolean {
        if (serverSocket != null) return true
        val env = TerminalEnvironment(context)
        if (!env.ensureHostBackendReady()) {
            Log.e(TAG, "宿主运行环境初始化失败，无法启动 VNC")
            return false
        }
        return try {
            val ss = ServerSocket(
                RemoteAccessManager.VNC_PORT,
                0,
                InetAddress.getByName("127.0.0.1")
            )
            serverSocket = ss
            backendLabel = env.backendLabel()
            acceptThread = thread(name = "vnc-accept") {
                while (!ss.isClosed) {
                    try {
                        val sock = ss.accept()
                        thread(name = "vnc-session") { handleSession(sock, env) }
                    } catch (_: SocketException) {
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "VNC accept 异常", e)
                    }
                }
            }
            Log.i(
                TAG,
                "VNC 服务已启动：127.0.0.1:${RemoteAccessManager.VNC_PORT}（后端 $backendLabel）"
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "VNC 服务启动失败", e)
            runCatching { serverSocket?.close() }
            serverSocket = null
            false
        }
    }

    @Synchronized
    fun stop() {
        runCatching { acceptThread?.interrupt() }
        runCatching { serverSocket?.close() }
        serverSocket = null
        backendLabel = "—"
    }

    fun isRunning(): Boolean = serverSocket != null
    fun backend(): String = backendLabel

    // ---------------------------------------------------------------- 会话

    private fun handleSession(socket: Socket, env: TerminalEnvironment) {
        val din = DataInputStream(socket.getInputStream())
        val dout = DataOutputStream(socket.getOutputStream())
        try {
            // 1. 协议版本（服务端先发）
            dout.write("RFB 003.008\n".toByteArray(Charsets.ISO_8859_1))
            dout.flush()
            val clientVersion = readLine(din).trim()
            if (!clientVersion.startsWith("RFB 003.00")) {
                Log.w(TAG, "不支持的客户端版本：$clientVersion")
                return
            }
            // 2. 安全类型：只给 VNC Authentication (2)
            dout.writeByte(1) // 数量
            dout.writeByte(2) // type 2 = VNC Auth
            dout.flush()
            val chosen = din.readUnsignedByte()
            if (chosen != 2) {
                Log.w(TAG, "客户端未选择 VNC Auth：$chosen")
                return
            }
            // 3. 16 字节随机挑战
            val challenge = ByteArray(16)
            for (i in challenge.indices) challenge[i] = (Math.random() * 256).toInt().toByte()
            dout.write(challenge)
            dout.flush()
            // 4. 16 字节响应
            val response = ByteArray(16)
            din.readFully(response)
            val ok = vncAuthResponse(challenge, RemoteAccessManager.LOGIN_PASSWORD)
                .contentEquals(response)
            dout.writeInt(if (ok) 0 else 1) // 4 字节结果：0=成功
            dout.flush()
            if (!ok) {
                Log.w(TAG, "VNC 密码错误")
                return
            }
            // 5. ClientInit
            din.readUnsignedByte()
            // 6. ServerInit
            dout.writeShort(FB_W)
            dout.writeShort(FB_H)
            // pixel format（32bpp / 24 depth / 小端 / true-color）
            dout.writeByte(32) // bits-per-pixel
            dout.writeByte(24) // depth
            dout.writeByte(0) // big-endian = false
            dout.writeByte(1) // true-color
            dout.writeShort(255) // red-max
            dout.writeShort(255) // green-max
            dout.writeShort(255) // blue-max
            dout.writeByte(16) // red-shift
            dout.writeByte(8) // green-shift
            dout.writeByte(0) // blue-shift
            dout.writeByte(0); dout.writeByte(0); dout.writeByte(0) // padding(3)
            val name = "ZorvAI".toByteArray(Charsets.ISO_8859_1)
            dout.writeInt(name.size)
            dout.write(name)
            dout.flush()

            // 启动后端 PTY
            val pty = try {
                Pty.start(env.backendShellCommand(), env.baseEnvironment(), env.filesDirectory)
            } catch (e: Exception) {
                Log.e(TAG, "VNC 后端 PTY 启动失败", e)
                return
            }

            VncSession(din, dout, pty).loop()
        } catch (_: EOFException) {
            // 客户端断开
        } catch (e: Exception) {
            Log.e(TAG, "VNC 会话异常", e)
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun readLine(din: DataInputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = din.readUnsignedByte()
            if (b == 0x0A) break
            if (b != 0x0D) sb.append(b.toChar())
        }
        return sb.toString()
    }

    /**
     * VNC Auth 的 DES 挑战-响应。密码（≤8 字节）作 DES 密钥，但每个字节的位序会被反转、
     * 再修正为奇校验（与标准 VNC 实现一致），随后对 16 字节挑战做 ECB 加密。
     */
    private fun vncAuthResponse(challenge: ByteArray, password: String): ByteArray {
        val key = ByteArray(8)
        val pw = password.toByteArray(Charsets.US_ASCII)
        for (i in 0 until 8) key[i] = if (i < pw.size) pw[i] else 0
        // 反转每个字节的位序
        for (i in 0 until 8) {
            var b = key[i].toInt() and 0xFF
            var r = 0
            for (j in 0 until 8) {
                r = (r shl 1) or (b and 1)
                b = b shr 1
            }
            key[i] = r.toByte()
        }
        // 修正奇校验（置最低位）
        for (i in 0 until 8) {
            val b = key[i].toInt() and 0xFE
            var c = 0
            for (j in 1 until 8) if ((b and (1 shl j)) != 0) c++
            key[i] = if (c and 1 == 0) (b or 1).toByte() else b.toByte()
        }
        val cipher = Cipher.getInstance("DES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
        val result = ByteArray(16)
        cipher.doFinal(challenge, 0, 8, result, 0)
        cipher.doFinal(challenge, 8, 8, result, 8)
        return result
    }

    // ---------------------------------------------------------------- 单会话

    private class VncSession(
        private val din: DataInputStream,
        private val dout: DataOutputStream,
        private val pty: Pty,
    ) {
        private val screen = Array(ROWS) { CharArray(COLS) { ' ' } }
        private val fb = Bitmap.createBitmap(FB_W, FB_H, Bitmap.Config.ARGB_8888)
        private val canvas = Canvas(fb)
        private val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = (CELL_H - 2).toFloat()
            typeface = Typeface.MONOSPACE
            isAntiAlias = false
        }
        private val bgPaint = Paint().apply { color = Color.BLACK }
        private val cursorPaint = Paint().apply { color = Color.DKGRAY }
        private val lock = Any()

        private var cursorRow = 0
        private var cursorCol = 0
        @Volatile private var dirty = true
        private var esc = false
        private var closed = false

        fun loop() {
            // 读取 PTY 输出 → 写入终端网格
            val reader = thread(name = "vnc-pty-read") {
                val buf = ByteArray(4096)
                try {
                    while (!closed) {
                        val n = pty.stdout.read(buf)
                        if (n < 0) break
                        feed(buf, n)
                    }
                } catch (_: Throwable) {
                }
            }
            try {
                while (!closed) {
                    readClientMessage() ?: break
                }
            } catch (_: Throwable) {
            } finally {
                closed = true
                runCatching { reader.interrupt() }
                runCatching { pty.destroy() }
            }
        }

        private fun readClientMessage(): Int? {
            return try {
                val type = din.readUnsignedByte()
                when (type) {
                    0 -> { // SetPixelFormat（忽略负载，固定 RAW 32bpp）
                        skip(19)
                        TYPE_MISC
                    }
                    2 -> { // SetEncodings
                        din.readUnsignedByte() // padding
                        val count = din.readInt()
                        for (i in 0 until count) din.readInt()
                        TYPE_MISC
                    }
                    3 -> { // FramebufferUpdateRequest
                        val incremental = din.readUnsignedByte() // 0=整屏, 1=增量
                        din.readUnsignedShort(); din.readUnsignedShort() // x, y
                        din.readUnsignedShort(); din.readUnsignedShort() // w, h
                        // 首次（非增量）必发确保客户端拿到首帧；之后仅在内容变化(dirty)时发
                        if (incremental == 0 || dirty) sendFrame()
                        TYPE_MISC
                    }
                    4 -> { // KeyEvent
                        val down = din.readUnsignedByte()
                        din.readUnsignedShort() // padding
                        val key = din.readInt()
                        handleKey(down != 0, key)
                        TYPE_MISC
                    }
                    5 -> { // PointerEvent
                        din.readUnsignedByte() // button mask
                        din.readUnsignedShort(); din.readUnsignedShort() // x, y
                        TYPE_MISC
                    }
                    6 -> { // ClientCutText
                        skip(3) // padding
                        val len = din.readInt()
                        skip(len)
                        TYPE_MISC
                    }
                    else -> {
                        Log.w(TAG, "未知 VNC 客户端消息类型 $type")
                        null
                    }
                }
            } catch (_: EOFException) {
                null
            } catch (_: Throwable) {
                null
            }
        }

        private fun skip(n: Int) {
            if (n > 0) din.skipBytes(n)
        }

        private fun handleKey(down: Boolean, key: Int) {
            if (!down) return
            val bytes = when (key) {
                0xFF08 -> byteArrayOf(0x08) // BackSpace
                0xFF09 -> byteArrayOf(0x09) // Tab
                0xFF0D -> byteArrayOf(0x0D) // Enter
                0xFF1B -> byteArrayOf(0x1B) // Escape
                0xFF51 -> byteArrayOf(0x1B, 0x5B, 0x44) // Left
                0xFF52 -> byteArrayOf(0x1B, 0x5B, 0x41) // Up
                0xFF53 -> byteArrayOf(0x1B, 0x5B, 0x43) // Right
                0xFF54 -> byteArrayOf(0x1B, 0x5B, 0x42) // Down
                else -> if (key in 0x20..0x7E) byteArrayOf(key.toByte()) else null
            }
            bytes?.let { runCatching { pty.stdin.write(it); pty.stdin.flush() } }
        }

        private fun sendFrame() {
            render()
            dout.writeByte(0) // FramebufferUpdate
            dout.writeByte(0) // padding
            dout.writeShort(1) // 1 个矩形
            dout.writeShort(0); dout.writeShort(0) // x, y
            dout.writeShort(FB_W); dout.writeShort(FB_H) // w, h
            dout.writeInt(0) // encoding 0 = RAW
            val pixels = IntArray(FB_W * FB_H)
            fb.getPixels(pixels, 0, FB_W, 0, 0, FB_W, FB_H)
            for (p in pixels) {
                dout.writeByte(p and 0xFF) // B
                dout.writeByte((p shr 8) and 0xFF) // G
                dout.writeByte((p shr 16) and 0xFF) // R
                dout.writeByte((p shr 24) and 0xFF) // A
            }
            dout.flush()
            dirty = false
        }

        private fun render() {
            synchronized(lock) {
                canvas.drawRect(0f, 0f, FB_W.toFloat(), FB_H.toFloat(), bgPaint)
                for (r in 0 until ROWS) {
                    val rowStr = String(screen[r]).trimEnd()
                    if (rowStr.isNotEmpty()) {
                        canvas.drawText(
                            rowStr,
                            0f,
                            (r * CELL_H + CELL_H - 3).toFloat(),
                            textPaint
                        )
                    }
                }
                if (cursorCol < COLS && cursorRow < ROWS) {
                    canvas.drawRect(
                        (cursorCol * CELL_W).toFloat(),
                        (cursorRow * CELL_H).toFloat(),
                        ((cursorCol + 1) * CELL_W).toFloat(),
                        ((cursorRow + 1) * CELL_H).toFloat(),
                        cursorPaint
                    )
                }
            }
        }

        private fun feed(buf: ByteArray, n: Int) {
            synchronized(lock) {
                for (i in 0 until n) {
                    val c = buf[i].toInt() and 0xFF
                    if (esc) {
                        // 转义序列：消费直到终结字节（@..~）
                        if (c in 0x40..0x7E) esc = false
                        continue
                    }
                    when (c) {
                        0x1B -> esc = true
                        0x0D -> cursorCol = 0 // CR
                        0x0A -> newLine() // LF
                        0x08, 0x7F -> { // BackSpace / DEL
                            if (cursorCol > 0) {
                                cursorCol--
                                screen[cursorRow][cursorCol] = ' '
                            }
                        }
                        0x20 -> putChar(' ')
                        else -> if (c >= 0x20) putChar(c.toChar())
                    }
                }
                dirty = true
            }
        }

        private fun putChar(ch: Char) {
            if (cursorCol >= COLS) {
                cursorCol = 0
                newLine()
            }
            screen[cursorRow][cursorCol] = ch
            cursorCol++
            if (cursorCol >= COLS) {
                cursorCol = 0
                newLine()
            }
        }

        private fun newLine() {
            cursorRow++
            cursorCol = 0
            if (cursorRow >= ROWS) {
                for (r in 0 until ROWS - 1) screen[r] = screen[r + 1]
                screen[ROWS - 1] = CharArray(COLS) { ' ' }
                cursorRow = ROWS - 1
            }
        }
    }

    private const val TYPE_MISC = -1
}
