package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.adb.AdbAuthException
import com.ai.assistance.quro.core.adb.QuroAdbManager
import com.ai.assistance.quro.core.adb.QuroAdbWireless
import com.ai.assistance.quro.core.agent.QuroAgentTrace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume

/**
 * ADB 调试工具：本机作为 **ADB 主机** 去调试另一台 Android 设备（USB / TCP）。
 *
 * 与 `adb_term` 的区别（很容易混，提示词里也写明了）：
 *  - `adb_term`：把**本机**当被调试端（启停本机 tcpip adbd、以 root 执行本机命令）；
 *  - 本工具：本机当**主机**，去控制**别人**（USB 直连 / 网络连接、shell、推拉文件、装 APK）。
 *
 * 技术依据见 docs/ADB_ARCHITECTURE.md（AOSP protocol.txt + adb_wifi.md + dadb/Kadb 实现）。
 */
class AdbDevTool : QuroTool {

    override val name: String = "adb_dev"

    override val description: String = """
ADB 调试工具：把本机当成 ADB 主机，去调试**另一台** Android 设备（USB 直连或网络连接）。
可用于：连上对端执行 shell、推拉文件、安装 APK、查看设备列表。

action：
- guide：查「怎么调试别的机器 / 怎么被调试 / USB 怎么识别」的完整技术说明（不确定就先调它）
- usb_list：列出可作 ADB 目标的 USB 设备（含是否已授权本 App）
- usb_connect：连接 USB 设备（deviceId 或 serial，需先授权；未授权时会自动发起授权并等待）
- tcp_connect：连接网络 ADB（host + port；legacy 是 5555，无线调试是随机端口）
- local_connect：连本机回环 127.0.0.1:5555（需本机已开 tcpip）
- devices：列出当前已连接的设备
- shell：在指定设备执行命令（device + command）
- push：推文件（device + local + remote）
- pull：拉文件（device + local + remote）
- install：安装 APK（device + local）
- uninstall：卸载（device + packageName；keep_data=true 保留数据）
- disconnect：断开指定设备
- shell_multi：顺序执行多条命令（device + commands 数组），一次调用跑一批，逐条回显退出码
- logcat：抓对端日志（device + lines 条数默认 200 + tag 过滤 + priority V/D/I/W/E）
- screencap：对端截屏并拉回本地（device + local 保存路径）
- input：注入输入（device + kind=tap/swipe/text/keyevent + 对应参数 x/y/x2/y2/text/keycode）
- pm：包管理（device + op=list/thirdparty/enable/disable/clear/grant + packageName/permission）
- am：应用操作（device + op=start/force-stop/broadcast + packageName/component/action/extra）
- dumpsys：系统状态（device + service=activity/window/battery/meminfo/package + 参数）
- forward：本机端口转发到对端（device + local_port + remote，如 8080 或 127.0.0.1:8080）；remove=true 撤销
- reverse：对端端口反向转发到本机（device + remote_port + local）；remove=true 撤销
- props：设备关键信息一次拿全（型号/Android 版本/SDK/分辨率/density/电池/序列号）
- reboot：重启对端（**必须 confirm=true**，否则拒绝执行）

无线调试（Android 11+，加密 + 免数据线，**不需要 USB**）：
- wireless_status：查配对/连接端口与当前状态（先调它确认系统那边开了没）
- pair：用配对码配对（需 code；port 可省略，自动 mDNS 发现）
- wireless_connect：建立 TLS 连接（port 可省略）
- wireless_exec：在无线连接上执行命令
- wireless_forget：清除本地配对身份

重要：
- USB 直连要求**本机支持 USB Host/OTG**，且对端已开「USB 调试」；
- 首次连接时**对端会弹出「允许 USB 调试」**，必须有人点允许，否则认证失败；
- 网络连接要求对端已执行过 `adb tcpip 5555`（或开着无线调试）。
""".trimIndent()

    override val parametersJson: String = """
{
  "type": "object",
  "properties": {
    "action": {"type": "string", "description": "guide / usb_list / usb_connect / tcp_connect / local_connect / devices / shell / shell_multi / logcat / screencap / input / pm / am / dumpsys / forward / reverse / props / push / pull / install / uninstall / reboot / disconnect / wireless_*"},
    "device": {"type": "string", "description": "目标设备标识（usb_list/devices 返回的名字），单设备时可省略"},
    "deviceId": {"type": "integer", "description": "usb_connect 用的 UsbDevice.deviceId"},
    "serial": {"type": "string", "description": "usb_connect 用的设备序列号（比 deviceId 稳定，拔插后仍可用）"},
    "host": {"type": "string", "description": "tcp_connect 的目标 IP"},
    "port": {"type": "integer", "description": "tcp_connect / local_connect 的端口，默认 5555"},
    "command": {"type": "string", "description": "shell 要执行的命令"},
    "local": {"type": "string", "description": "push/pull/install 的本地路径"},
    "remote": {"type": "string", "description": "push/pull 的设备端路径"},
    "reinstall": {"type": "boolean", "description": "install 是否 -r 覆盖安装，默认 true"},
    "code": {"type": "string", "description": "pair 用的 6 位配对码（设备「使用配对码配对设备」弹窗里的数字）"},
    "timeout_ms": {"type": "integer", "description": "超时毫秒，默认 60000"}
  },
  "required": ["action"]
}
""".trimIndent()

    override fun run(context: Context, arguments: String): String {
        val obj = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
        val action = obj.optString("action", "guide").ifBlank { "guide" }

        return runCatching {
            when (action) {
                "guide" -> guide()
                "usb_list" -> usbList(context)
                "usb_connect" -> usbConnect(context, obj)
                "tcp_connect" -> tcpConnect(context, obj)
                "local_connect" -> localConnect(context, obj)
                "devices" -> devices()
                "shell" -> shell(obj)
                "push" -> push(obj)
                "pull" -> pull(obj)
                "install" -> install(obj)
                "disconnect" -> disconnect(obj)

                // ── 完整 shell 功能集（全部由底层 shell() 组合，不新增协议面）──
                "shell_multi" -> shellMulti(obj)
                "logcat" -> logcat(obj)
                "screencap" -> screencap(context, obj)
                "input" -> input(obj)
                "pm" -> pm(obj)
                "am" -> am(obj)
                "dumpsys" -> dumpsys(obj)
                "forward" -> forward(obj)
                "reverse" -> reverse(obj)
                "props" -> props(obj)
                "uninstall" -> uninstall(obj)
                "reboot" -> reboot(obj)

                // ── 无线调试（配对 + TLS）──
                "wireless_status" -> QuroAdbWireless.status(context)
                "pair" -> QuroAdbWireless.pair(
                    context,
                    port = if (obj.has("port")) obj.optInt("port", 0).takeIf { it > 0 } else null,
                    code = obj.optString("code", ""),
                )
                "wireless_connect" -> QuroAdbWireless.connect(
                    context,
                    port = if (obj.has("port")) obj.optInt("port", 0).takeIf { it > 0 } else null,
                )
                "wireless_exec" -> QuroAdbWireless.exec(
                    obj.optString("command", ""),
                    obj.optInt("timeout_ms", 60_000),
                )
                "wireless_forget" -> {
                    QuroAdbWireless.forget(context)
                    "已清除本地无线调试配对身份（设备侧授权需在系统设置里撤销）"
                }
                else -> "未知 action：$action"
            }
        }.getOrElse { t ->
            when (t) {
                is AdbAuthException -> "❌ 认证失败：${t.message}"
                else -> "❌ ${action} 失败：${t.message ?: t.javaClass.simpleName}"
            }
        }
    }

    // ─────────────────────────── actions ───────────────────────────

    private fun guide(): String = """
【怎么调试另一台机器】
1. 对端开「开发者选项 → USB 调试」，用 OTG 线把两台手机连起来（本机是 Host）。
2. 本机先 usb_list 看有没有识别到（识别规则：USB 接口 class=0xFF / subclass=0x42 / protocol=0x01，且有 bulk IN+OUT）。
3. usb_connect 连接；**对端会弹「允许 USB 调试」，必须点允许**，否则报认证失败。
4. 连上后用 shell / push / pull / install。

网络方式（免线）：
- 对端先「adb tcpip 5555」（需要能连一次 USB，或对端有 root）；
- 然后 tcp_connect(host=对端IP, port=5555)。
- Android 11+ 的「无线调试」是**随机端口**，要在对端屏幕上抄 IP:端口，再用 tcp_connect。

【怎么被调试（本机当设备）】
- 让电脑调本机：本机开「无线调试」（用户手动开），电脑 adb pair + adb connect；或用 adb_term 开 tcpip 5555（**需要 root**，Shizuku 权限不够）。
- 让另一台手机调本机：同上，本机当被调试端。

【USB 连接后怎么识别】
- UsbManager.deviceList 里逐个看 interface：class=0xFF(255)、subclass=0x42(66)、protocol=0x01(1)，且同一 interface 同时有 bulk IN 和 bulk OUT 端点 → 这就是 ADB 接口。
- 注意：不是所有手机支持 USB Host/OTG；拔插后 deviceName 会变，持久身份用 serialNumber。

【必须知道的坑】
- 无线调试的配对端口开很短时间，过期要重新打开页面拿新码。
- sets 属性启 tcpip（setprop service.adb.tcp.port）需要 root，Shizuku 会被 SELinux 拒绝。
- 认证失败 = 对端拒绝公钥；解法：对端「撤销 USB 调试授权」后重插。
""".trimIndent()

    private fun usbList(context: Context): String {
        val list = QuroAdbManager.listUsbDevices(context)
        if (list.isEmpty()) {
            return "未发现 USB ADB 设备。\n可能原因：\n" +
                "1) 对端没开「USB 调试」；\n" +
                "2) 本机不支持 USB Host/OTG（很多机型只能充电/U盘）；\n" +
                "3) 用的是充电线（需要数据线）；\n" +
                "4) OTG 线方向不对（host 必须在有 OTG 的一端）。"
        }
        return buildString {
            appendLine("发现 ${list.size} 台 USB ADB 设备：")
            list.forEach {
                val granted = if (it.hasPermission) "已授权" else "未授权"
                appendLine("- ${it.display}  deviceId=${it.device.deviceId}  [$granted]")
            }
        }.trim()
    }

    private fun usbConnect(context: Context, obj: JSONObject): String {
        val list = QuroAdbManager.listUsbDevices(context)
        if (list.isEmpty()) return usbList(context)

        val deviceId = if (obj.has("deviceId")) obj.optInt("deviceId", -1) else -1
        val serial = obj.optString("serial", "")
        val target = when {
            deviceId >= 0 -> list.firstOrNull { it.device.deviceId == deviceId }
            serial.isNotBlank() -> list.firstOrNull {
                runCatching { it.device.serialNumber }.getOrNull() == serial
            }
            list.size == 1 -> list.first()
            else -> return "有多台 USB 设备，请指定 deviceId 或 serial：\n" +
                list.joinToString("\n") { "- ${it.display} deviceId=${it.device.deviceId}" }
        } ?: return "没找到指定设备（deviceId=$deviceId serial=$serial）"

        QuroAgentTrace.action("adb_dev", "USB 连接", target.display)

        // 未授权先发起授权并等待（最多 30s）
        if (!target.hasPermission) {
            val granted = runBlocking {
                withTimeoutOrNull(30_000) {
                    suspendCancellableCoroutine<Boolean> { cont ->
                        QuroAdbManager.requestUsbPermission(context, target.device) { ok ->
                            if (cont.isActive) cont.resume(ok)
                        }
                    }
                }
            } ?: false
            if (!granted) return "USB 授权被拒绝或超时。请重新连接并在系统弹窗中点「允许」。"
        }

        val client = runBlocking(Dispatchers.IO) {
            QuroAdbManager.connectUsb(context, target.device)
        }
        return "✅ 已连接 ${client.transportName}\n设备：${target.display}"
    }

    private fun tcpConnect(context: Context, obj: JSONObject): String {
        val host = obj.optString("host", "").trim()
        if (host.isBlank()) return "缺少 host"
        val port = obj.optInt("port", 5555).let { if (it <= 0) 5555 else it }
        QuroAgentTrace.action("adb_dev", "TCP 连接", "$host:$port")
        val client = runBlocking(Dispatchers.IO) { QuroAdbManager.connectTcp(context, host, port) }
        return "✅ 已连接 ${client.transportName}"
    }

    private fun localConnect(context: Context, obj: JSONObject): String {
        val port = obj.optInt("port", 5555).let { if (it <= 0) 5555 else it }
        QuroAgentTrace.action("adb_dev", "本机回环连接", "127.0.0.1:$port")
        val client = runBlocking(Dispatchers.IO) { QuroAdbManager.connectLocal(context, port) }
        return "✅ 已连接本机回环 ${client.transportName}"
    }

    private fun devices(): String {
        val list = QuroAdbManager.connectedDevices()
        if (list.isEmpty()) return "当前没有已连接的 ADB 设备"
        return buildString {
            appendLine("已连接 ${list.size} 台：")
            list.forEach { appendLine("- $it") }
        }.trim()
    }

    private fun resolveClient(obj: JSONObject): Pair<String, com.ai.assistance.quro.core.adb.QuroAdbClient> {
        val wanted = obj.optString("device", "").trim()
        if (wanted.isNotBlank()) {
            val c = QuroAdbManager.client(wanted)
                ?: throw IllegalStateException("设备未连接：$wanted（已连接：${QuroAdbManager.connectedDevices()}）")
            return wanted to c
        }
        val all = QuroAdbManager.connectedDevices()
        if (all.isEmpty()) throw IllegalStateException("还没有连接任何设备，先 usb_connect 或 tcp_connect")
        if (all.size > 1) throw IllegalStateException("有多台设备，请用 device 指定：$all")
        return all.first() to QuroAdbManager.client(all.first())!!
    }

    private fun shell(obj: JSONObject): String {
        val cmd = obj.optString("command", "").trim()
        if (cmd.isBlank()) return "缺少 command"
        val (name, client) = resolveClient(obj)
        val timeout = obj.optLong("timeout_ms", 60_000).coerceIn(1_000, 600_000)
        QuroAgentTrace.action("adb_dev", "shell@$name", cmd)
        return runBlocking(Dispatchers.IO) { client.shell(cmd, timeout) }
    }

    private fun push(obj: JSONObject): String {
        val local = obj.optString("local", "")
        val remote = obj.optString("remote", "")
        if (local.isBlank() || remote.isBlank()) return "缺少 local / remote"
        val f = File(local)
        if (!f.isFile) return "本地文件不存在：$local"
        val (name, client) = resolveClient(obj)
        val timeout = obj.optLong("timeout_ms", 300_000)
        QuroAgentTrace.action("adb_dev", "push@$name", "$local -> $remote")
        runBlocking(Dispatchers.IO) { client.push(f, remote, timeout) }
        return "✅ 已推送 ${f.length()} 字节到 $name:$remote"
    }

    private fun pull(obj: JSONObject): String {
        val remote = obj.optString("remote", "")
        val local = obj.optString("local", "")
        if (remote.isBlank() || local.isBlank()) return "缺少 remote / local"
        val (name, client) = resolveClient(obj)
        val timeout = obj.optLong("timeout_ms", 300_000)
        QuroAgentTrace.action("adb_dev", "pull@$name", "$remote -> $local")
        val target = File(local)
        runBlocking(Dispatchers.IO) { client.pull(remote, target, timeout) }
        return "✅ 已拉取 $name:$remote → ${target.absolutePath}（${target.length()} 字节）"
    }

    private fun install(obj: JSONObject): String {
        val local = obj.optString("local", "")
        if (local.isBlank()) return "缺少 local"
        val f = File(local)
        if (!f.isFile) return "APK 不存在：$local"
        val (name, client) = resolveClient(obj)
        val reinstall = obj.optBoolean("reinstall", true)
        val timeout = obj.optLong("timeout_ms", 300_000)
        QuroAgentTrace.action("adb_dev", "install@$name", local)
        return runBlocking(Dispatchers.IO) { client.install(f, reinstall, timeout) }
    }

    /** 顺序执行多条命令：一次调用跑一批，逐条回显（失败也继续，最后汇总）。 */
    private fun shellMulti(obj: JSONObject): String {
        val arr = obj.optJSONArray("commands") ?: return "缺少 commands（JSON 字符串数组）"
        if (arr.length() == 0) return "commands 为空"
        val (name, client) = resolveClient(obj)
        val timeout = obj.optLong("timeout_ms", 60_000).coerceIn(1_000, 600_000)
        QuroAgentTrace.action("adb_dev", "批量 shell@$name", "${arr.length()} 条")
        val sb = StringBuilder()
        var failed = 0
        for (i in 0 until arr.length()) {
            val cmd = arr.optString(i).trim()
            if (cmd.isEmpty()) continue
            val out = runBlocking(Dispatchers.IO) { client.shell(cmd, timeout) }
            val bad = out.contains("exit=1") || out.contains("exit=2") || out.contains("exit=127")
            if (bad) failed++
            sb.append("[${i + 1}] $ ").append(cmd).append('\n').append(out.trim()).append("\n\n")
        }
        return sb.append(if (failed == 0) "✅ 全部成功" else "⚠️ $failed 条失败").toString()
    }

    /** 对端日志：logcat -d 一次性抓取（-t 限制条数），支持 tag 与优先级过滤。 */
    private fun logcat(obj: JSONObject): String {
        val (name, client) = resolveClient(obj)
        val lines = obj.optInt("lines", 200).coerceIn(10, 5000)
        val tag = obj.optString("tag", "").trim()
        val prio = obj.optString("priority", "").trim().uppercase()
        val filter = buildString {
            if (tag.isNotBlank()) {
                append(tag)
                if (prio in setOf("V", "D", "I", "W", "E")) append(":$prio")
                append(" ")
            }
            append("*:${prio.ifBlank { "I" }}")
        }
        val cmd = "logcat -d -t $lines $filter"
        QuroAgentTrace.action("adb_dev", "logcat@$name", "$lines 行")
        return runBlocking(Dispatchers.IO) { client.shell(cmd, 30_000) }
    }

    /** 对端截屏 → 拉回本地文件（screencap -p 到 /sdcard 临时文件再 pull）。 */
    private fun screencap(context: Context, obj: JSONObject): String {
        val (name, client) = resolveClient(obj)
        val stamp = System.currentTimeMillis()
        val remote = "/sdcard/quro_adb_screencap_$stamp.png"
        val local = File(
            obj.optString("local", "").ifBlank {
                File(context.getExternalFilesDir(null) ?: context.filesDir, "adb_screencap_$stamp.png").absolutePath
            },
        )
        QuroAgentTrace.action("adb_dev", "截屏@$name", remote)
        return runBlocking(Dispatchers.IO) {
            val cap = client.shell("screencap -p $remote", 30_000)
            if (!cap.contains("exit=0")) return@runBlocking "截屏失败：$cap"
            try {
                client.pull(remote, local, 60_000)
                "✅ 已保存：${local.absolutePath}"
            } finally {
                runCatching { client.shell("rm -f $remote", 10_000) }
            }
        }
    }

    /** 输入注入：tap / swipe / text / keyevent。 */
    private fun input(obj: JSONObject): String {
        val kind = obj.optString("kind", "tap").lowercase()
        val (name, client) = resolveClient(obj)
        val cmd = when (kind) {
            "tap" -> "input tap ${obj.optInt("x", -1)} ${obj.optInt("y", -1)}"
            "swipe" -> "input swipe ${obj.optInt("x", -1)} ${obj.optInt("y", -1)} ${obj.optInt("x2", -1)} ${obj.optInt("y2", -1)} ${obj.optLong("duration_ms", 300)}"
            "text" -> "input text '${obj.optString("text", "").replace("'", "\\'")}'"
            "keyevent" -> "input keyevent ${obj.optInt("keycode", -1)}"
            else -> return "未知 kind：$kind（tap/swipe/text/keyevent）"
        }
        if (cmd.endsWith("-1")) return "参数不全（x/y 等）"
        QuroAgentTrace.action("adb_dev", "input@$name", cmd)
        return runBlocking(Dispatchers.IO) {
            val out = client.shell(cmd, 15_000)
            if (out.contains("exit=0")) "✅ $kind 已注入" else "注入失败：$out"
        }
    }

    /** 包管理：list/thirdparty/enable/disable/clear/grant。 */
    private fun pm(obj: JSONObject): String {
        val op = obj.optString("op", "list").lowercase()
        val pkg = obj.optString("packageName", "").trim()
        val (name, client) = resolveClient(obj)
        val cmd = when (op) {
            "list" -> "pm list packages"
            "thirdparty" -> "pm list packages -3"
            "enable" -> "pm enable $pkg"
            "disable" -> "pm disable-user $pkg"
            "clear" -> "pm clear $pkg"
            "grant" -> "pm grant $pkg ${obj.optString("permission", "")}"
            else -> return "未知 op：$op"
        }
        if (cmd.endsWith(" ") || cmd.endsWith("$pkg")) {
            if (op in setOf("enable", "disable", "clear", "grant") && pkg.isBlank()) return "缺少 packageName"
        }
        QuroAgentTrace.action("adb_dev", "pm@$name", cmd)
        return runBlocking(Dispatchers.IO) { client.shell(cmd, 60_000) }
    }

    /** 应用操作：start / force-stop / broadcast。 */
    private fun am(obj: JSONObject): String {
        val op = obj.optString("op", "start").lowercase()
        val (name, client) = resolveClient(obj)
        val cmd = when (op) {
            "start" -> {
                val comp = obj.optString("component", "").trim()
                val act = obj.optString("action", "").trim()
                when {
                    comp.isNotBlank() -> "am start -n $comp"
                    act.isNotBlank() -> "am start -a $act"
                    else -> return "start 需要 component 或 action"
                }
            }
            "force-stop" -> "am force-stop ${obj.optString("packageName", "").ifBlank { return "缺少 packageName" }}"
            "broadcast" -> "am broadcast -a ${obj.optString("action", "").ifBlank { return "broadcast 缺少 action" }}"
            else -> return "未知 op：$op"
        }
        QuroAgentTrace.action("adb_dev", "am@$name", cmd)
        return runBlocking(Dispatchers.IO) { client.shell(cmd, 30_000) }
    }

    /** 系统状态：dumpsys 指定服务（截断到前 4000 字符防刷屏）。 */
    private fun dumpsys(obj: JSONObject): String {
        val service = obj.optString("service", "battery").trim()
        val (name, client) = resolveClient(obj)
        QuroAgentTrace.action("adb_dev", "dumpsys@$name", service)
        val out = runBlocking(Dispatchers.IO) { client.shell("dumpsys $service", 30_000) }
        return if (out.length > 4000) out.take(4000) + "\n…（已截断，共 ${out.length} 字符，可用 shell 分段取）" else out
    }

    /** 端口转发：本机 <local_port> → 对端 <remote>（如 8080 或 127.0.0.1:8080）。 */
    private fun forward(obj: JSONObject): String {
        val (name, client) = resolveClient(obj)
        val cmd = if (obj.optBoolean("remove", false)) {
            "forward --remove tcp:${obj.optInt("local_port", -1)}"
        } else {
            val lp = obj.optInt("local_port", -1)
            val remote = obj.optString("remote", "").ifBlank { return "缺少 remote（如 8080 或 127.0.0.1:8080）" }
            "forward tcp:$lp tcp:$remote"
        }
        QuroAgentTrace.action("adb_dev", "forward@$name", cmd)
        return runBlocking(Dispatchers.IO) {
            val out = client.shell(cmd, 15_000)
            if (out.contains("exit=0")) "✅ forward 完成" else "失败：$out"
        }
    }

    /** 反向转发：对端 <remote_port> → 本机 <local>。 */
    private fun reverse(obj: JSONObject): String {
        val (name, client) = resolveClient(obj)
        val cmd = if (obj.optBoolean("remove", false)) {
            "reverse --remove tcp:${obj.optInt("remote_port", -1)}"
        } else {
            val rp = obj.optInt("remote_port", -1)
            val local = obj.optString("local", "").ifBlank { return "缺少 local（如 8080 或 127.0.0.1:8080）" }
            "reverse tcp:$rp tcp:$local"
        }
        QuroAgentTrace.action("adb_dev", "reverse@$name", cmd)
        return runBlocking(Dispatchers.IO) {
            val out = client.shell(cmd, 15_000)
            if (out.contains("exit=0")) "✅ reverse 完成" else "失败：$out"
        }
    }

    /** 设备关键信息一次拿全（模型排查对端的第一步）。 */
    private fun props(obj: JSONObject): String {
        val (name, client) = resolveClient(obj)
        QuroAgentTrace.action("adb_dev", "props@$name", "")
        val script = listOf(
            "model" to "getprop ro.product.model",
            "brand" to "getprop ro.product.brand",
            "android" to "getprop ro.build.version.release",
            "sdk" to "getprop ro.build.version.sdk",
            "serial" to "getprop ro.serialno",
            "resolution" to "wm size",
            "density" to "wm density",
            "battery" to "dumpsys battery | grep -E 'level|status'",
        ).joinToString("; ") { (k, c) -> "echo $k=\$($c)" }
        return runBlocking(Dispatchers.IO) { client.shell(script, 30_000) }
    }

    /** 卸载（默认保留数据可 -k）。 */
    private fun uninstall(obj: JSONObject): String {
        val pkg = obj.optString("packageName", "").trim()
        if (pkg.isBlank()) return "缺少 packageName"
        val (name, client) = resolveClient(obj)
        val keep = if (obj.optBoolean("keep_data", false)) " -k" else ""
        QuroAgentTrace.action("adb_dev", "uninstall@$name", pkg)
        return runBlocking(Dispatchers.IO) { client.shell("pm uninstall$keep $pkg", 60_000) }
    }

    /** 重启对端 —— 危险操作，必须显式 confirm=true。 */
    private fun reboot(obj: JSONObject): String {
        if (!obj.optBoolean("confirm", false)) {
            return "重启会中断对端正在进行的所有操作。确认无误时带 confirm=true 再调。"
        }
        val (name, client) = resolveClient(obj)
        QuroAgentTrace.action("adb_dev", "reboot@$name", "")
        return runBlocking(Dispatchers.IO) {
            val out = client.shell("reboot", 15_000)
            if (out.contains("exit=0") || out.isBlank()) "✅ 重启指令已下发（对端将在数秒内断开）" else "失败：$out"
        }
    }

    private fun disconnect(obj: JSONObject): String {
        val wanted = obj.optString("device", "").trim()
        if (wanted.isBlank()) {
            QuroAdbManager.disconnectAll()
            return "已断开全部设备"
        }
        return if (QuroAdbManager.disconnect(wanted)) "已断开 $wanted" else "没有这个设备：$wanted"
    }
}
