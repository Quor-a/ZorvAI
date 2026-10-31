package com.ai.assistance.quro.core.adb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * ADB 传输抽象：只管「字节怎么进出」，协议层完全无感。
 *
 * 之所以要抽象：USB 与 TCP 在协议层是**完全一致**的，差异只在底层管道。
 * 隔离到这里之后，同一套 AdbConnection / AdbClient 可以同时服务
 * 「USB 直连另一台手机」和「同网 / 回环 TCP」。
 */
interface AdbTransport : Closeable {
    /** 展示用名称，如 `usb:SERIAL` / `tcp:192.168.1.5:5555`。 */
    val name: String
    val input: InputStream
    val output: OutputStream
}

// ─────────────────────────── TCP ───────────────────────────

/**
 * 网络 / 回环传输。
 *
 * 端口注意：
 *  - legacy `adb tcpip 5555` → 5555
 *  - 无线调试（Android 11+）→ **随机高位端口**，必须靠 mDNS 或用户手抄
 *  - 模拟器 → **5555**（奇数），5554 是控制台端口，连了也不通
 */
class TcpAdbTransport(
    private val host: String,
    private val port: Int,
    connectTimeoutMs: Int = 8_000,
    readTimeoutMs: Int = 0,
) : AdbTransport {

    private val socket: Socket = Socket().apply {
        tcpNoDelay = true
        connect(InetSocketAddress(host, port), connectTimeoutMs)
        soTimeout = readTimeoutMs
    }

    override val name: String = "tcp:$host:$port"
    override val input: InputStream = socket.getInputStream()
    override val output: OutputStream = socket.getOutputStream()

    override fun close() {
        runCatching { socket.close() }
    }
}

// ─────────────────────────── USB ───────────────────────────

/**
 * USB ADB 识别与传输（Android 作 **USB Host** 直接说 ADB 协议，不需要 adb server）。
 *
 * 识别规则（Kadb / Google AdbTest / 各家实现一致）：
 * ```
 * interfaceClass    == 0xFF   // vendor specific
 * interfaceSubclass == 0x42   // 'B'
 * interfaceProtocol == 0x01
 * 且同一 interface 上同时有 bulk IN 与 bulk OUT 端点
 * ```
 *
 * 硬约束（必须给用户人话提示，不能假设可用）：
 *  - 需要设备支持 **USB Host / OTG**，并不是所有手机都行；
 *  - 两根手机用 OTG 线连接时，只有一端是 host，另一端必须开「USB 调试」；
 *  - 拔插后 `deviceName`（总线地址）会变，持久身份要用 `serialNumber`。
 */
object UsbAdb {

    private const val TAG = "UsbAdb"

    private data class AdbEndpoints(
        val intf: UsbInterface,
        val input: UsbEndpoint,
        val output: UsbEndpoint,
    )

    /** 该 USB 设备是否暴露了 ADB 接口。 */
    fun isAdbDevice(device: UsbDevice): Boolean = findEndpoints(device) != null

    /** 列出所有可作为 ADB 目标的 USB 设备。 */
    fun findAdbDevices(manager: UsbManager): List<UsbDevice> =
        manager.deviceList.values.filter { findEndpoints(it) != null }.sortedBy { it.deviceName }

    /** 给用户看的设备摘要。 */
    fun describe(device: UsbDevice): String {
        val serial = runCatching { device.serialNumber }.getOrNull()
        return buildString {
            append(device.productName ?: device.deviceName)
            if (!serial.isNullOrBlank()) append(" ($serial)")
            append(" [${Integer.toHexString(device.vendorId)}:${Integer.toHexString(device.productId)}]")
        }
    }

    private fun findEndpoints(device: UsbDevice): AdbEndpoints? {
        for (index in 0 until device.interfaceCount) {
            val intf = device.getInterface(index)
            if (intf.interfaceClass != UsbConstants.USB_CLASS_VENDOR_SPEC) continue
            if (intf.interfaceSubclass != 0x42 || intf.interfaceProtocol != 0x01) continue

            val bulk = (0 until intf.endpointCount)
                .map { intf.getEndpoint(it) }
                .filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
            val input = bulk.firstOrNull { it.direction == UsbConstants.USB_DIR_IN }
            val output = bulk.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT }
            if (input == null || output == null) continue
            if (input.maxPacketSize <= 0 || output.maxPacketSize <= 0) continue
            return AdbEndpoints(intf, input, output)
        }
        return null
    }

    /**
     * 建立 USB 传输。**调用前必须已获得 USB 访问权限**（`UsbManager.hasPermission`）。
     *
     * 权限申请（`requestPermission`）由上层负责，因为它需要 Activity/PendingIntent 上下文。
     */
    fun open(context: Context, device: UsbDevice, timeoutMs: Int = 5_000): AdbTransport {
        val manager = context.applicationContext.getSystemService(UsbManager::class.java)
            ?: throw AdbConnectException("当前设备没有 USB 服务")
        val endpoints = findEndpoints(device)
            ?: throw AdbConnectException("该 USB 设备没有 ADB 接口（未开 USB 调试，或不是 Android 设备）")
        if (!manager.hasPermission(device)) {
            throw AdbConnectException("缺少 USB 设备访问权限：请先授权（App 内的 USB 授权弹窗）")
        }

        val connection = manager.openDevice(device)
            ?: throw AdbConnectException("打开 USB 设备失败（可能被其他进程占用）")
        if (!connection.claimInterface(endpoints.intf, true)) {
            runCatching { connection.close() }
            throw AdbConnectException("独占 ADB 接口失败（接口被占用）")
        }

        val serial = runCatching { device.serialNumber }.getOrNull() ?: device.deviceName
        Log.i(TAG, "USB ADB 已连接: $serial")
        return UsbAdbTransport(connection, endpoints.intf, endpoints.input, endpoints.output, timeoutMs, "usb:$serial")
    }
}

/**
 * 把 USB bulk 端点包装成字节流。
 *
 * 读超时的处理：`bulkTransfer` 超时返回 -1，与「对端关闭」无法区分。
 * 这里连续多次超时才判定断开，避免把正常的空闲误判成断连。
 */
private class UsbAdbTransport(
    private val connection: UsbDeviceConnection,
    private val intf: UsbInterface,
    private val inEndpoint: UsbEndpoint,
    private val outEndpoint: UsbEndpoint,
    private val timeoutMs: Int,
    override val name: String,
) : AdbTransport {

    @Volatile
    private var closed = false

    override val input: InputStream = object : InputStream() {
        private var idleTimeouts = 0

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (closed) return -1
            if (len == 0) return 0
            while (true) {
                if (closed) return -1
                val n = runCatching {
                    connection.bulkTransfer(inEndpoint, b, off, len, timeoutMs)
                }.getOrDefault(-1)
                if (n > 0) {
                    idleTimeouts = 0
                    return n
                }
                // 超时/失败：连续多次才认为断开
                idleTimeouts++
                if (closed || idleTimeouts >= MAX_IDLE_TIMEOUTS) return -1
            }
        }

        override fun close() {
            closed = true
        }
    }

    override val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) throw IOException("USB 通道已关闭（$name）")
            var sent = 0
            while (sent < len) {
                val n = connection.bulkTransfer(outEndpoint, b, off + sent, len - sent, timeoutMs)
                if (n <= 0) throw IOException("USB 写入失败（已发送 $sent/$len 字节，$name）")
                sent += n
            }
        }

        override fun close() {
            closed = true
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { connection.releaseInterface(intf) }
        runCatching { connection.close() }
        Log.i(TAG, "USB ADB 已断开: $name")
    }

    private companion object {
        const val TAG = "UsbAdbTransport"
        const val MAX_IDLE_TIMEOUTS = 3
    }
}
