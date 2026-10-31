package com.ai.assistance.quro.core.adb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * ADB 设备与连接管理（App 侧单例）。
 *
 * 三条连接路径：
 *  1. **USB 直连**（`usb_connect`）：本机作 USB Host，直接说 ADB 协议。需要本机支持 OTG + 对端开 USB 调试。
 *  2. **TCP**（`tcp_connect`）：同网 / 回环，对端需已开 `tcpip 5555` 或无线调试的随机端口。
 *  3. **无线配对**（二期）：SPAKE2 + TLS，见 docs/ADB_ARCHITECTURE.md §5。
 *
 * 权限说明（两个不同的东西，经常被混淆）：
 *  - **USB 访问权限**：本机给 App 的，`UsbManager.requestPermission`，一次性。
 *  - **ADB 调试授权**：对端给本机公钥的，发生在握手时对端弹「允许 USB 调试」，也是首次一次。
 */
object QuroAdbManager {

    private const val TAG = "QuroAdbManager"
    private const val ACTION_USB_PERMISSION = "com.ai.assistance.quro.adb.USB_PERMISSION"

    private val clients = ConcurrentHashMap<String, QuroAdbClient>()

    /** 已连接设备列表（transport 名称）。 */
    fun connectedDevices(): List<String> = clients.keys.sorted()

    fun client(name: String): QuroAdbClient? = clients[name]

    /** 密钥目录：`filesDir/adb/`。 */
    private fun keyDir(context: Context): File = File(context.filesDir, "adb").apply { mkdirs() }

    private fun keyPair(context: Context): AdbKeyPair = AdbKeyPair.loadOrCreate(keyDir(context))

    // ─────────────────────────── USB ───────────────────────────

    /** 列出所有 ADB-capable USB 设备及其授权状态。 */
    fun listUsbDevices(context: Context): List<UsbDeviceInfo> {
        val manager = context.getSystemService(UsbManager::class.java) ?: return emptyList()
        return UsbAdb.findAdbDevices(manager).map { device ->
            UsbDeviceInfo(
                device = device,
                display = UsbAdb.describe(device),
                hasPermission = manager.hasPermission(device),
            )
        }
    }

    data class UsbDeviceInfo(val device: UsbDevice, val display: String, val hasPermission: Boolean)

    /**
     * 申请 USB 访问权限并在授予后回调。
     *
     * 注意：Android 14 起动态注册 receiver 必须显式声明导出状态，这里用
     * `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)` 兼容所有版本。
     */
    fun requestUsbPermission(
        context: Context,
        device: UsbDevice,
        timeoutMs: Long = 30_000,
        onResult: (granted: Boolean) -> Unit,
    ) {
        val manager = context.getSystemService(UsbManager::class.java)
        if (manager == null) {
            onResult(false)
            return
        }
        if (manager.hasPermission(device)) {
            onResult(true)
            return
        }

        val app = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != ACTION_USB_PERMISSION) return
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                runCatching { app.unregisterReceiver(this) }
                Log.i(TAG, "USB 权限结果: granted=$granted")
                onResult(granted)
            }
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.RECEIVER_NOT_EXPORTED
        } else {
            ContextCompat.RECEIVER_NOT_EXPORTED
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(ACTION_USB_PERMISSION), flags)

        val pi = PendingIntent.getBroadcast(
            app,
            device.deviceId,
            Intent(ACTION_USB_PERMISSION).setPackage(app.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.requestPermission(device, pi)
    }

    /**
     * 连接已授权的 USB 设备。
     *
     * 阻塞调用，必须放在 IO 线程。
     */
    fun connectUsb(context: Context, device: UsbDevice): QuroAdbClient {
        val manager = context.getSystemService(UsbManager::class.java)
            ?: throw AdbConnectException("当前设备没有 USB 服务（不支持 USB Host）")
        if (!manager.hasPermission(device)) {
            throw AdbConnectException("缺少 USB 访问权限，请先调用 usb_permission 授权")
        }
        val transport = UsbAdb.open(context, device)
        return register(transport, keyPair(context))
    }

    // ─────────────────────────── TCP ───────────────────────────

    /**
     * 连接网络 / 回环 ADB。
     *
     * @param port legacy `tcpip` 是 5555；无线调试是随机端口（用户需现场抄屏幕上的 IP:端口）
     */
    fun connectTcp(
        context: Context,
        host: String,
        port: Int,
        connectTimeoutMs: Int = 8_000,
    ): QuroAdbClient {
        if (host.isBlank()) throw AdbConnectException("缺少 host")
        if (port !in 1..65535) throw AdbConnectException("端口不合法：$port")
        val transport = TcpAdbTransport(host, port, connectTimeoutMs)
        return register(transport, keyPair(context))
    }

    /** 本机回环（需要本机已开 tcpip 5555 或无线调试）。 */
    fun connectLocal(context: Context, port: Int = 5555): QuroAdbClient =
        connectTcp(context, "127.0.0.1", port)

    // ─────────────────────────── 公共 ───────────────────────────

    private fun register(transport: AdbTransport, keyPair: AdbKeyPair): QuroAdbClient {
        val connection = AdbConnection(transport, keyPair)
        try {
            connection.connect()
        } catch (t: Throwable) {
            runCatching { connection.close() }
            throw t
        }
        val client = QuroAdbClient(connection)
        // 同名旧连接先断掉，避免残留
        clients.put(transport.name, client)?.let { runCatching { it.close() } }
        Log.i(TAG, "已注册设备 ${transport.name}")
        return client
    }

    fun disconnect(name: String): Boolean {
        val client = clients.remove(name) ?: return false
        runCatching { client.close() }
        Log.i(TAG, "已断开 $name")
        return true
    }

    fun disconnectAll() {
        clients.keys.toList().forEach { disconnect(it) }
    }
}
