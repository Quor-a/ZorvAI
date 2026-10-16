package com.ai.assistance.quro.terminal.utils

import android.graphics.Bitmap
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 内置 VNC 客户端（RFB 3.8 子集）——把「已连接」变成**屏幕上真有画面**。
 *
 * ## 为什么需要它
 * 旧实现只做 TCP 探针，界面显示「已连接 · RFB 003.008」，但用户什么都看不到
 * （原话：「界面连接上界面都没有连接个屁」）。探针只能证明端口在监听，证明不了**能用**。
 * 本客户端连 127.0.0.1:5901（内置服务端或 proot 里的 tigervnc 桌面），
 * 回环流量不进网卡，带宽/延迟都不是问题，因此只需要 RAW 编码，不必实现 zlib/tight。
 *
 * ## 实现范围（够用即止）
 * - 握手：协议版本 → 安全类型协商（VNC Auth DES 挑战 / None）→ ClientInit → ServerInit
 * - 像素格式：**客户端指定** 32bpp / depth 24 / 小端 / true-colour（R:16 G:8 B:0）——
 *   这个布局正好等于 Android `ARGB_8888` 的内存次序，可以直接 `Bitmap.setPixels`。
 * - 编码：RAW(0) + DesktopSize(-223) 伪编码（桌面分辨率变化时重建位图）。
 * - 输入：PointerEvent(5) / KeyEvent(4) / ClientCutText(6)。
 * - 遇到不支持的编码**明确报错并断开**：未知编码长度未知，静默跳过只会永远花屏。
 *
 * 所有回调都在工作线程上触发；界面层负责切主线程。
 */
class VncClient(
    private val host: String,
    private val port: Int,
    private val password: String,
    private val listener: Listener,
) {

    interface Listener {
        /** 连接阶段的一句话进度（供界面显示）。 */
        fun onStatus(text: String)

        /** 服务端桌面分辨率（首次 ServerInit 或之后的 DesktopSize）。 */
        fun onFramebufferSize(width: Int, height: Int)

        /**
         * 一帧处理完成。[full] 表示整屏都变了（界面可据此做一次完整重绘）。
         * [bitmap] 是**复用**的对象，回调返回后内容可能被下一帧覆盖。
         */
        fun onFrame(bitmap: Bitmap, full: Boolean)

        /** 失败（一句话原因）。之后不会再有任何回调。 */
        fun onFailure(message: String)

        /** 主动关闭或对端断开。 */
        fun onClosed()
    }

    private class VncException(message: String) : Exception(message)

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null

    @Volatile
    private var running = false

    @Volatile
    private var worker: Thread? = null

    private var fbWidth = 0
    private var fbHeight = 0
    private var bitmap: Bitmap? = null

    private var rectBytes = ByteArray(0)
    private var rectPixels = IntArray(0)

    /** 写通道要串行：主线程发鼠标、工作线程发更新请求，共用同一个流。 */
    private val writeLock = Any()

    fun isRunning(): Boolean = running

    fun start() {
        if (running) return
        running = true
        worker = Thread({
            try {
                runSession()
                if (running) listener.onClosed()
            } catch (e: Throwable) {
                if (running) {
                    val msg = e.message ?: e.javaClass.simpleName
                    Log.w(TAG, "VNC 会话结束：$msg", e)
                    listener.onFailure(msg)
                }
            } finally {
                running = false
                closeQuietly()
            }
        }, "vnc-client").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        closeQuietly()
        runCatching { worker?.interrupt() }
    }

    // ------------------------------------------------------------------ 输入

    /** 鼠标：mask 位含义 1=左键 2=中键 4=右键 8=滚轮上 16=滚轮下。 */
    fun sendPointer(x: Int, y: Int, mask: Int) {
        val out = output ?: return
        if (!running) return
        synchronized(writeLock) {
            runCatching {
                out.writeByte(MSG_POINTER)
                out.writeByte(mask and 0xFF)
                out.writeShort(x.coerceIn(0, 0xFFFF))
                out.writeShort(y.coerceIn(0, 0xFFFF))
                out.flush()
            }
        }
    }

    /** 键盘：X11 keysym（可打印 ASCII 直接用字符码）。 */
    fun sendKey(keysym: Int, down: Boolean) {
        val out = output ?: return
        if (!running) return
        synchronized(writeLock) {
            runCatching {
                out.writeByte(MSG_KEY)
                out.writeByte(if (down) 1 else 0)
                out.writeByte(0)
                out.writeShort(0)
                out.writeInt(keysym)
                out.flush()
            }
        }
    }

    fun sendKeyTap(keysym: Int) {
        sendKey(keysym, true)
        sendKey(keysym, false)
    }

    // ------------------------------------------------------------------ 主流程

    private fun runSession() {
        listener.onStatus("正在连接 $host:$port …")
        val sock = Socket()
        sock.tcpNoDelay = true
        sock.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        sock.soTimeout = HANDSHAKE_TIMEOUT_MS
        socket = sock

        val din = DataInputStream(BufferedInputStream(sock.getInputStream(), 1 shl 16))
        val dout = DataOutputStream(BufferedOutputStream(sock.getOutputStream(), 1 shl 16))
        input = din
        output = dout

        handshake(din, dout)

        // 握手完成：此后可能长时间没有新画面（桌面没变化），不能再用读超时判死
        sock.soTimeout = 0
        listener.onStatus("已连接")

        requestUpdate(incremental = 0)
        loop(din)
    }

    private fun handshake(din: DataInputStream, dout: DataOutputStream) {
        val serverVersion = ByteArray(12)
        din.readFully(serverVersion)
        val version = String(serverVersion, Charsets.US_ASCII).trim()
        if (!version.startsWith("RFB ")) {
            throw VncException("对端不是 VNC 服务（首包为「$version」）")
        }
        // 固定回复 3.8：服务端会按两者较小值协商（3.3/3.7/3.8 都受支持的那条路）
        dout.write("RFB 003.008\n".toByteArray(Charsets.US_ASCII))
        dout.flush()

        val typeCount = din.readUnsignedByte()
        if (typeCount == 0) {
            throw VncException("服务端拒绝连接：${readReason(din)}")
        }
        val types = ByteArray(typeCount)
        din.readFully(types)
        val offered = types.map { it.toInt() and 0xFF }
        val chosen = when {
            SEC_VNC_AUTH in offered -> SEC_VNC_AUTH
            SEC_NONE in offered -> SEC_NONE
            else -> -1
        }
        if (chosen < 0) {
            throw VncException("服务端只提供认证方式 $offered，本机客户端不支持")
        }
        dout.writeByte(chosen)
        dout.flush()

        if (chosen == SEC_VNC_AUTH) {
            val challenge = ByteArray(16)
            din.readFully(challenge)
            dout.write(desResponse(challenge, password))
            dout.flush()
        }

        val result = din.readInt()
        if (result != 0) {
            val reason = runCatching { readReason(din) }.getOrDefault("")
            throw VncException(
                if (password.isEmpty()) "VNC 需要密码，但当前密码为空"
                else "VNC 认证失败（密码错误）${if (reason.isNullOrBlank()) "" else "：$reason"}"
            )
        }

        // ClientInit：shared = 1（不踢掉已在线的其它客户端）
        dout.writeByte(1)
        dout.flush()

        // ServerInit
        fbWidth = din.readUnsignedShort()
        fbHeight = din.readUnsignedShort()
        val pixelFormat = ByteArray(16)
        din.readFully(pixelFormat)
        val nameLen = din.readInt()
        val name = if (nameLen in 1..4096) ByteArray(nameLen).also { din.readFully(it) }
        else ByteArray(0)
        val desc = String(name, Charsets.UTF_8)
        Log.i(TAG, "ServerInit ${fbWidth}x$fbHeight name=$desc")
        if (fbWidth <= 0 || fbHeight <= 0) {
            throw VncException("服务端上报的桌面尺寸非法（${fbWidth}x$fbHeight）")
        }
        listener.onFramebufferSize(fbWidth, fbHeight)
        allocateBitmap(fbWidth, fbHeight)

        // SetPixelFormat：32bpp / depth24 / 小端 / true-colour（R:16 G:8 B:0）
        dout.writeByte(MSG_SET_PIXEL_FORMAT)
        dout.writeByte(32)
        dout.writeByte(24)
        dout.writeByte(0)      // big-endian-flag = 0
        dout.writeByte(1)      // true-colour-flag = 1
        dout.writeShort(255)   // red-max
        dout.writeShort(255)   // green-max
        dout.writeShort(255)   // blue-max
        dout.writeByte(16)     // red-shift
        dout.writeByte(8)      // green-shift
        dout.writeByte(0)      // blue-shift
        dout.writeByte(0)      // padding
        dout.writeByte(0)
        dout.writeByte(0)

        // SetEncodings：RAW + DesktopSize 伪编码（顺序即优先级）
        dout.writeByte(MSG_SET_ENCODINGS)
        dout.writeShort(2)
        dout.writeInt(ENC_RAW)
        dout.writeInt(ENC_DESKTOP_SIZE)
        dout.flush()
    }

    private fun loop(din: DataInputStream) {
        while (running) {
            when (val type = din.readUnsignedByte()) {
                MSG_FRAMEBUFFER_UPDATE -> handleUpdate(din)
                MSG_SET_COLOUR_MAP -> {
                    din.readUnsignedByte()
                    val count = din.readUnsignedShort()
                    skipFully(din, count * 6)
                }
                MSG_BELL -> Unit
                MSG_SERVER_CUT_TEXT -> {
                    din.readUnsignedByte()
                    din.readUnsignedByte()
                    din.readUnsignedByte()
                    val len = din.readInt()
                    if (len > 0) skipFully(din, len)
                }
                else -> throw VncException("收到未知的服务端消息类型（$type）")
            }
        }
    }

    private fun handleUpdate(din: DataInputStream) {
        din.readUnsignedByte() // padding
        val rectCount = din.readUnsignedShort()
        var full = false
        var aborted = false
        var i = 0
        while (i < rectCount && !aborted) {
            val x = din.readUnsignedShort()
            val y = din.readUnsignedShort()
            val w = din.readUnsignedShort()
            val h = din.readUnsignedShort()
            val encoding = din.readInt()
            when (encoding) {
                ENC_RAW -> readRawRect(din, x, y, w, h)
                ENC_DESKTOP_SIZE -> {
                    // 分辨率变了：重建位图并要求整屏重传；同一消息里后续矩形按规范不可信
                    Log.i(TAG, "桌面分辨率变化 → ${w}x$h")
                    fbWidth = w
                    fbHeight = h
                    listener.onFramebufferSize(w, h)
                    allocateBitmap(w, h)
                    full = true
                    aborted = true
                }
                else -> throw VncException("服务端使用了本机客户端不支持的编码（$encoding）")
            }
            i++
        }
        val bmp = bitmap ?: return
        listener.onFrame(bmp, full)
    }

    private fun readRawRect(din: DataInputStream, x: Int, y: Int, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        if (x + w > fbWidth || y + h > fbHeight) {
            throw VncException("RAW 矩形越界（$x,$y ${w}x$h 超出 ${fbWidth}x$fbHeight）")
        }
        val need = w * h * 4
        if (rectBytes.size < need) rectBytes = ByteArray(need)
        din.readFully(rectBytes, 0, need)

        if (rectPixels.size < w * h) rectPixels = IntArray(w * h)
        var si = 0
        var di = 0
        // 线序为 B,G,R,X（小端 + R:16 G:8 B:0）→ 直接拼成 ARGB int
        while (di < w * h) {
            val b = rectBytes[si].toInt() and 0xFF
            val g = rectBytes[si + 1].toInt() and 0xFF
            val r = rectBytes[si + 2].toInt() and 0xFF
            rectPixels[di] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            si += 4
            di++
        }
        bitmap?.setPixels(rectPixels, 0, w, x, y, w, h)
    }

    // ------------------------------------------------------------------ 工具

    private fun allocateBitmap(width: Int, height: Int) {
        val old = bitmap
        if (old != null && old.width == width && old.height == height) return
        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        rectBytes = ByteArray(0)
        rectPixels = IntArray(0)
    }

    private fun requestUpdate(incremental: Int) {
        val out = output ?: return
        if (!running) return
        val w = fbWidth
        val h = fbHeight
        if (w <= 0 || h <= 0) return
        synchronized(writeLock) {
            runCatching {
                out.writeByte(MSG_FRAMEBUFFER_UPDATE_REQUEST)
                out.writeByte(incremental)
                out.writeShort(0)
                out.writeShort(0)
                out.writeShort(w)
                out.writeShort(h)
                out.flush()
            }
        }
    }

    private fun readReason(din: DataInputStream): String {
        val len = din.readInt()
        if (len !in 1..4096) return ""
        val buf = ByteArray(len)
        din.readFully(buf)
        return String(buf, Charsets.UTF_8)
    }

    /** `InputStream.skip` 不保证跳满（可能被短读中断），必须循环。 */
    private fun skipFully(din: DataInputStream, count: Int) {
        var left = count
        val buf = ByteArray(4096)
        while (left > 0) {
            val n = din.read(buf, 0, minOf(left, buf.size))
            if (n < 0) throw VncException("连接在传输过程中被关闭")
            left -= n
        }
    }

    private fun closeQuietly() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null
        output = null
        socket = null
    }

    private companion object {
        private const val TAG = "VncClient"

        private const val CONNECT_TIMEOUT_MS = 5000
        private const val HANDSHAKE_TIMEOUT_MS = 8000

        private const val SEC_NONE = 1
        private const val SEC_VNC_AUTH = 2

        private const val MSG_SET_PIXEL_FORMAT = 0
        private const val MSG_SET_ENCODINGS = 2
        private const val MSG_FRAMEBUFFER_UPDATE_REQUEST = 3
        private const val MSG_KEY = 4
        private const val MSG_POINTER = 5

        private const val MSG_FRAMEBUFFER_UPDATE = 0
        private const val MSG_SET_COLOUR_MAP = 1
        private const val MSG_BELL = 2
        private const val MSG_SERVER_CUT_TEXT = 3

        private const val ENC_RAW = 0
        private const val ENC_DESKTOP_SIZE = -223

        /**
         * VNC 认证应答：把 16 字节挑战用「密码派生的 DES key」加密后回发。
         *
         * 密码派生规则（RFB 规范 §5.2.2）：取前 8 字节、不足补 0，每个字节
         * **按位翻转**（bit 0 ↔ bit 7），得到 56 位 DES key。
         */
        private fun desResponse(challenge: ByteArray, password: String): ByteArray {
            val raw = password.toByteArray(Charsets.US_ASCII)
            val key = ByteArray(8)
            for (i in 0 until 8) {
                val c = if (i < raw.size) raw[i].toInt() and 0xFF else 0
                key[i] = mirrorByte(c).toByte()
            }
            val cipher = Cipher.getInstance("DES/ECB/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
            return cipher.doFinal(challenge)
        }

        private fun mirrorByte(value: Int): Int {
            var v = value and 0xFF
            var r = 0
            for (i in 0 until 8) {
                if (v and (1 shl i) != 0) r = r or (1 shl (7 - i))
            }
            return r
        }
    }
}
